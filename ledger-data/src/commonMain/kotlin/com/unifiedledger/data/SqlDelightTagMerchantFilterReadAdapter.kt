package com.unifiedledger.data

import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MerchantAccumulationReadPort
import com.unifiedledger.application.MerchantAccumulationReadResult
import com.unifiedledger.application.TagMerchantCatalogReader
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterEntry
import com.unifiedledger.application.TagMerchantFilterFailureCode
import com.unifiedledger.application.TagMerchantFilterPage
import com.unifiedledger.application.TagMerchantFilterReadPort
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.data.db.TagMerchantAccumulationRowsInWindow
import com.unifiedledger.data.db.TagMerchantFilterRows
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/*
 * P7-08 08.C bounded filter/accumulation read adapter (spec sections 5.2/5.3/5.4; open items 3/6/9).
 *
 * Implements [TagMerchantFilterReadPort] and [MerchantAccumulationReadPort]. Every read runs in
 * ONE `transactionWithResult(noEnclosing = true)` (the
 * [SqlDelightMonthlyContributionReadAdapter] discipline) so SQLite keeps a single read snapshot:
 *
 *  1. the fail-loud missing-projection probe — a non-zero whole-ledger count is
 *     [TagMerchantFilterFailureCode.FILTER_MISSING_PROJECTION], never a silent omission (a NULL
 *     projection row can never satisfy a keyset or window predicate, so it must never be
 *     silently dropped);
 *  2. the tag/merchant catalog generation load — the dedicated `catalog_item_version` counter
 *     (open item 9, D-218 item 6: an INDEPENDENT counter from `catalog_version`); an unreadable
 *     authority is [TagMerchantFilterFailureCode.FILTER_BAD_CATALOG];
 *  3. the generation gate — a snapshot generation differing from the caller's
 *     `expectedCatalogItemVersion` is the typed mismatch (the D-186 section 2(b)
 *     compare-in-transaction mechanism on the dedicated counter), never a stale page;
 *  4. the rows (one named query per read shape; both spec read steps live inside the filter
 *     statement as EXISTS semi-joins, so no multi-tag JOIN multiplication, no amount DISTINCT
 *     and no SQL-side group totals can occur) plus the batched current-tag query for the page.
 *
 * Amounts fold in Kotlin by the frozen classifier; this adapter never SUMs or DISTINCTs in SQL.
 * Read-only: no Posting is created, no balance/reconciliation is changed.
 */
class SqlDelightTagMerchantFilterReadAdapter(
    private val database: LedgerDatabase,
    private val accountCatalogReader: CatalogAuthorityReader,
    private val tagMerchantCatalogReader: TagMerchantCatalogReader,
) : TagMerchantFilterReadPort,
    MerchantAccumulationReadPort {
    override fun readPage(
        ledgerId: LedgerId,
        condition: TagMerchantFilterCondition,
        expectedCatalogItemVersion: Long,
        pageSize: Int,
        cursor: TagMerchantFilterCursor?,
    ): TagMerchantFilterReadResult {
        require(pageSize > 0) { "filter page size must be positive" }
        val tags =
            condition.tagIds
                .map { it.value }
                .distinct()
                .sorted()
        // An empty tag condition is "all transactions" (spec 5.1); the IN list must never be
        // empty, so the dormant EXISTS receives a one-element list that matches nothing while
        // `min_tag_hits = 0` admits every row.
        val tagList = if (tags.isEmpty()) listOf("") else tags
        val minTagHits =
            when {
                tags.isEmpty() -> 0L
                condition.tagMatch == TagMerchantTagMatchMode.Any -> 1L
                else -> tags.size.toLong()
            }
        return try {
            database.transactionWithResult(noEnclosing = true) {
                val missing = database.ledgerQueries.statisticsAtProjectionMissingForLedger(ledgerId.value).executeAsOne()
                if (missing != 0L) {
                    return@transactionWithResult failed(TagMerchantFilterFailureCode.FILTER_MISSING_PROJECTION)
                }
                val authority =
                    tagMerchantCatalogReader.load(ledgerId)
                        ?: return@transactionWithResult failed(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG)
                if (authority.catalogItemVersion != expectedCatalogItemVersion) {
                    return@transactionWithResult failed(TagMerchantFilterFailureCode.FILTER_CATALOG_GENERATION_MISMATCH)
                }
                val accounts =
                    accountCatalogReader.load(ledgerId)
                        ?: return@transactionWithResult failed(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG)
                // pageSize + 1 detects "more pages" without a second count query; the extra
                // TRANSACTION is dropped (the CTE binds the LIMIT per transaction, so the
                // lookahead is whole transactions) and the page never exceeds the bound.
                val raw =
                    database.ledgerQueries
                        .tagMerchantFilterRows(
                            ledger_id = ledgerId.value,
                            min_tag_hits = minTagHits,
                            tags = tagList,
                            merchant_id = condition.merchantId?.value,
                            cursor_nanos = cursor?.statisticsAtNanos,
                            cursor_transaction_id = cursor?.transactionId ?: "",
                            page_size = pageSize.toLong() + 1L,
                        ).executeAsList()
                        .groupBy { it.transaction_id }
                        .toList()
                val hasMore = raw.size > pageSize
                val groupedRows = if (hasMore) raw.dropLast(1) else raw
                val tagIdsByTransaction =
                    if (groupedRows.isEmpty()) {
                        emptyMap()
                    } else {
                        database.ledgerQueries
                            .tagMerchantFilterTagsForTransactions(
                                ledger_id = ledgerId.value,
                                transaction_ids = groupedRows.map(Pair<String, List<TagMerchantFilterRows>>::first),
                            ).executeAsList()
                            .groupBy({ it.transaction_id }, { it.tag_id })
                    }
                val entries =
                    groupedRows.map { (transactionId, grouped) ->
                        val first = grouped.first()
                        TagMerchantFilterEntry(
                            row = filterEntryRow(transactionId, grouped),
                            tagIds = (tagIdsByTransaction[transactionId] ?: emptyList()).sorted().map(::TagId),
                            merchantId = first.annotation_merchant_id?.let(::MerchantId),
                        )
                    }
                val last = entries.lastOrNull()?.row
                TagMerchantFilterReadResult.Success(
                    TagMerchantFilterPage(
                        catalogItemVersion = authority.catalogItemVersion,
                        entries = entries,
                        hasMore = hasMore,
                        catalog = accounts.catalog,
                        nextCursor =
                            if (hasMore && last != null) {
                                TagMerchantFilterCursor(
                                    statisticsAtNanos = requireNotNull(StatisticsAtProjection.project(last.statisticsAt)),
                                    transactionId = last.transactionId.value,
                                )
                            } else {
                                null
                            },
                    ),
                )
            }
        } catch (failure: Exception) {
            failed(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
        }
    }

    override fun readMerchantRows(
        ledgerId: LedgerId,
        merchantId: MerchantId,
        startInclusive: Instant,
        endExclusive: Instant,
        expectedCatalogItemVersion: Long,
    ): MerchantAccumulationReadResult {
        // A window bound outside the projection's representable range cannot be read; refuse
        // rather than silently returning an empty (zero) window (the monthly read's discipline).
        val startNanos = StatisticsAtProjection.project(startInclusive) ?: return failedAccumulation(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
        val endNanos = StatisticsAtProjection.project(endExclusive) ?: return failedAccumulation(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
        return try {
            database.transactionWithResult(noEnclosing = true) {
                val missing = database.ledgerQueries.statisticsAtProjectionMissingForLedger(ledgerId.value).executeAsOne()
                if (missing != 0L) {
                    return@transactionWithResult failedAccumulation(TagMerchantFilterFailureCode.FILTER_MISSING_PROJECTION)
                }
                val authority =
                    tagMerchantCatalogReader.load(ledgerId)
                        ?: return@transactionWithResult failedAccumulation(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG)
                if (authority.catalogItemVersion != expectedCatalogItemVersion) {
                    return@transactionWithResult failedAccumulation(TagMerchantFilterFailureCode.FILTER_CATALOG_GENERATION_MISMATCH)
                }
                val accounts =
                    accountCatalogReader.load(ledgerId)
                        ?: return@transactionWithResult failedAccumulation(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG)
                val rows =
                    database.ledgerQueries
                        .tagMerchantAccumulationRowsInWindow(
                            ledger_id = ledgerId.value,
                            start_nanos = startNanos,
                            end_nanos = endNanos,
                            merchant_id = merchantId.value,
                        ).executeAsList()
                        .groupBy { it.transaction_id }
                        .map { (transactionId, grouped) -> accumulationEntryRow(transactionId, grouped) }
                MerchantAccumulationReadResult.Success(
                    catalogItemVersion = authority.catalogItemVersion,
                    catalog = accounts.catalog,
                    rows = rows,
                )
            }
        } catch (failure: Exception) {
            failedAccumulation(TagMerchantFilterFailureCode.FILTER_UNAVAILABLE)
        }
    }

    /** The filter page's entry assembly (the monthly read adapter's grouping, index-ordered postings). */
    private fun filterEntryRow(
        transactionId: String,
        groupedRows: List<TagMerchantFilterRows>,
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

    /** The accumulation window's entry assembly (the same 12-column shape, no annotation columns). */
    private fun accumulationEntryRow(
        transactionId: String,
        groupedRows: List<TagMerchantAccumulationRowsInWindow>,
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

    private fun failed(failure: TagMerchantFilterFailureCode): TagMerchantFilterReadResult = TagMerchantFilterReadResult.Failed(failure)

    private fun failedAccumulation(failure: TagMerchantFilterFailureCode): MerchantAccumulationReadResult = MerchantAccumulationReadResult.Failed(failure)
}
