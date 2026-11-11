package com.unifiedledger.data

import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.ResolvedSearchCondition
import com.unifiedledger.application.SearchFailureCode
import com.unifiedledger.application.SearchPage
import com.unifiedledger.application.SearchReadPort
import com.unifiedledger.application.SearchReadResult
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.data.db.P802SearchPage
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/*
 * P8-02 i1 bounded search read adapter (spec docs/specs/2026-11-11-p8-02-search-filter-design.md
 * sections 2.3/4).
 *
 * Implements [SearchReadPort]. One page read runs in ONE `transactionWithResult(noEnclosing = true)`
 * (the [SqlDelightTagMerchantFilterReadAdapter] discipline) so SQLite keeps a single read snapshot:
 *
 *  1. the fail-loud missing-projection probe — a non-zero whole-ledger count is
 *     [SearchFailureCode.SEARCH_MISSING_PROJECTION], never a silent omission (a NULL projection row
 *     can never satisfy the half-open window predicate, so it must never be dropped quietly);
 *  2. exactly ONE page statement (`p802SearchPage`) — the whole condition group lives inside it, so
 *     there is no per-row or per-condition query and no N+1;
 *  3. exactly ONE count statement (`p802SearchCount`) over the same condition group, read in the
 *     same snapshot as the page (the spec's registered "may be a different snapshot" caveat applies
 *     only to a caller that reads the two apart).
 *
 * `pageSize + 1` detects "more pages" without a third statement: the CTE binds `LIMIT :page_size`
 * per TRANSACTION, so the lookahead is whole transactions and the page never exceeds the bound.
 *
 * The empty-condition sentinels are resolved here, next to the binding: an empty tag list binds a
 * one-element list that matches nothing while `min_tag_hits = 0` admits every row (the filter
 * adapter's precedent), and an empty keyword id set binds the same sentinel while its arm switch is
 * 0, so `IN ()` is never constructed.
 *
 * Amounts are returned raw and folded in Kotlin; this adapter never SUMs or DISTINCTs. Read-only: no
 * Posting is created, no balance, version or reconciliation state is changed.
 */
class SqlDelightSearchReadAdapter(
    private val database: LedgerDatabase,
) : SearchReadPort {
    override fun readPage(
        ledgerId: LedgerId,
        condition: ResolvedSearchCondition,
        pageSize: Int,
        cursor: TagMerchantFilterCursor?,
    ): SearchReadResult {
        require(pageSize > 0) { "search page size must be positive" }
        if (condition.windowStartInclusive >= condition.windowEndExclusive) {
            // A reversed or zero-length window cannot contain a row; refuse the caller's input typed
            // instead of returning an empty page.
            return failed(SearchFailureCode.SEARCH_INVALID_WINDOW)
        }
        // A caller window bound outside the projection's representable range is the caller's input:
        // refuse it as SEARCH_INVALID_WINDOW, never as an empty page. Read and probe failures below
        // stay SEARCH_UNAVAILABLE.
        val startNanos = StatisticsAtProjection.project(condition.windowStartInclusive) ?: return failed(SearchFailureCode.SEARCH_INVALID_WINDOW)
        val endNanos = StatisticsAtProjection.project(condition.windowEndExclusive) ?: return failed(SearchFailureCode.SEARCH_INVALID_WINDOW)
        val tags =
            condition.tagIds
                .map { it.value }
                .distinct()
                .sorted()
        val tagList = if (tags.isEmpty()) listOf("") else tags
        val minTagHits =
            when {
                tags.isEmpty() -> 0L
                condition.tagMatch == TagMerchantTagMatchMode.Any -> 1L
                else -> tags.size.toLong()
            }
        val categoryIds =
            sentinelWhenEmpty(
                condition.keywordCategoryIds
                    .map { it.value }
                    .distinct()
                    .sorted(),
            )
        val merchantIds =
            sentinelWhenEmpty(
                condition.keywordMerchantIds
                    .map { it.value }
                    .distinct()
                    .sorted(),
            )
        val categoryArm = if (condition.categoryArm) 1L else 0L
        val merchantArm = if (condition.merchantArm) 1L else 0L
        return try {
            database.transactionWithResult(noEnclosing = true) {
                val missing = database.ledgerQueries.statisticsAtProjectionMissingForLedger(ledgerId.value).executeAsOne()
                if (missing != 0L) {
                    return@transactionWithResult failed(SearchFailureCode.SEARCH_MISSING_PROJECTION)
                }
                val raw =
                    database.ledgerQueries
                        .p802SearchPage(
                            ledger_id = ledgerId.value,
                            window_start_nanos = startNanos,
                            window_end_nanos = endNanos,
                            keyword = condition.keyword,
                            keyword_category_ids = categoryIds,
                            category_arm = categoryArm,
                            keyword_merchant_ids = merchantIds,
                            merchant_arm = merchantArm,
                            min_tag_hits = minTagHits,
                            tags = tagList,
                            merchant_id = condition.merchantFilterId?.value,
                            cursor_nanos = cursor?.statisticsAtNanos,
                            cursor_transaction_id = cursor?.transactionId ?: "",
                            page_size = pageSize.toLong() + 1L,
                        ).executeAsList()
                        .groupBy { it.transaction_id }
                        .toList()
                val totalCount =
                    database.ledgerQueries
                        .p802SearchCount(
                            ledger_id = ledgerId.value,
                            window_start_nanos = startNanos,
                            window_end_nanos = endNanos,
                            keyword = condition.keyword,
                            keyword_category_ids = categoryIds,
                            category_arm = categoryArm,
                            keyword_merchant_ids = merchantIds,
                            merchant_arm = merchantArm,
                            min_tag_hits = minTagHits,
                            tags = tagList,
                            merchant_id = condition.merchantFilterId?.value,
                        ).executeAsOne()
                val hasMore = raw.size > pageSize
                val pageRows = if (hasMore) raw.dropLast(1) else raw
                val entries = pageRows.map { (transactionId, grouped) -> searchEntryRow(transactionId, grouped) }
                val lastPageRow = pageRows.lastOrNull()
                SearchReadResult.Success(
                    SearchPage(
                        entries = entries,
                        hasMore = hasMore,
                        nextCursor =
                            if (hasMore && lastPageRow != null) {
                                TagMerchantFilterCursor(
                                    // The queried projection column, never a re-parse of the statistics
                                    // TEXT: the cursor keyset must be the value the page ordered by.
                                    statisticsAtNanos = requireNotNull(lastPageRow.second.first().statistics_at_epoch_nanos),
                                    transactionId = lastPageRow.first,
                                )
                            } else {
                                null
                            },
                        totalCount = totalCount,
                    ),
                )
            }
        } catch (failure: Exception) {
            failed(SearchFailureCode.SEARCH_UNAVAILABLE)
        }
    }

    /** A one-element sentinel for an empty id set: it matches nothing and its arm is switched off. */
    private fun sentinelWhenEmpty(ids: List<String>): List<String> = if (ids.isEmpty()) listOf("") else ids

    /** The page's entry assembly (the filter/monthly read adapters' grouping, index-ordered postings). */
    private fun searchEntryRow(
        transactionId: String,
        groupedRows: List<P802SearchPage>,
    ): LedgerEntryRow {
        val first = groupedRows.first()
        return LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId(first.current_version_id),
            kind = TransactionKind.valueOf(first.kind),
            occurredAt = Instant.parse(first.occurred_at),
            statisticsAt = Instant.parse(first.statistics_at),
            note = first.note,
            postings =
                groupedRows
                    .sortedBy { it.posting_index }
                    .map { posting ->
                        Posting(
                            id = PostingId(posting.posting_id),
                            accountId = AccountId(posting.account_id),
                            amount =
                                Money.ofMinor(
                                    posting.amount_minor,
                                    CurrencyUnit(posting.currency_code, posting.currency_precision.toInt()),
                                ),
                        )
                    },
        )
    }

    private fun failed(failure: SearchFailureCode): SearchReadResult = SearchReadResult.Failed(failure)
}
