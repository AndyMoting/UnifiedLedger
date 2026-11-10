package com.unifiedledger.data

import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationCursor
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationQueuePage
import com.unifiedledger.application.P801AccountReconciliationQueueRow
import com.unifiedledger.application.P801AccountReconciliationReadFailure
import com.unifiedledger.application.P801AccountReconciliationReadPort
import com.unifiedledger.application.P801AccountReconciliationStatusTotal
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlin.time.Instant

/**
 * P8-01a account reconciliation read adapter (D-308 items 6/8; spec section 2.3).
 *
 * Implements [P801AccountReconciliationReadPort] against the single [LedgerDatabase]. Both reads
 * are read-only named queries: the queue page (`p801AccountReconciliationLegPage`) and its
 * per-(currency, status) totals (`p801AccountReconciliationStatusTotals`). They share one table
 * group, one current-version join, the single effective-predicate definition point
 * (`transaction_effective_state`) and the section 2.1 composite mark over `catalog_account`, so
 * the page and the totals cannot disagree about which legs exist and no second state rule is
 * introduced. Zero writes: no reconciliation, evidence, posting, balance or report row is
 * created or changed here.
 *
 * FAIL-CLOSED (spec section 2.3, V-9; D-089). A database failure, a persisted value that does not
 * map to its domain type (a kind or status outside its enum, an unparseable `occurred_at`), or an
 * integer minor-unit sum that overflows SQLite's INTEGER `sum()` all surface as
 * [P801AccountReconciliationReadFailure.Unavailable]. None of them degrades to an empty page, an
 * empty totals list or an amount of zero — an absent group is absent, a corrupt row is never
 * silently dropped, and a real zero is only ever the sum of real rows.
 *
 * The keyset order is the frozen `occurred_at DESC, posting_id ASC`, applied to the epoch-nanosecond
 * projection the query computes inline from `occurred_at` (no DDL, no added column). `occurred_at`
 * is TEXT in mixed shapes, so text order is not time order; the query applies the frozen
 * `statistics_at` parser to the row text AND to the cursor text the next page hands back, so a page
 * boundary is an instant boundary and the cursor stays exact. An unparseable text projects to the
 * epoch sentinel, keeping the order total (see the query comment).
 *
 * REGISTERED RESIDUAL (D-308 item 9 scope): the queue ignores an rg03
 * `reconciliation_eligible = 0` override, so such a leg would render 待对账 while the detail face
 * derives it ineligible and renders `null`. Unreachable today: the schema ties that value to the
 * fee leg, which posts to an EXPENSE account and so fails the section 2.1 composite mark too.
 */
class SqlDelightP801AccountReconciliationReadAdapter(
    private val database: LedgerDatabase,
) : P801AccountReconciliationReadPort {
    override fun loadAccountReconciliationPage(
        ledgerId: LedgerId,
        accountId: AccountId,
        after: P801AccountReconciliationCursor?,
        pageSize: Int,
    ): P801AccountReconciliationPageResult {
        require(pageSize > 0) { "page size must be positive" }
        return try {
            // One extra row tells the caller whether another page exists without a second query.
            val rawRows =
                database.ledgerQueries
                    .p801AccountReconciliationLegPage(
                        ledger_id = ledgerId.value,
                        account_id = accountId.value,
                        cursor_occurred_at = after?.occurredAtStorageValue,
                        cursor_posting_id = after?.postingId?.value ?: "",
                        page_size = pageSize.toLong() + 1L,
                    ).executeAsList()
            val pageRows = rawRows.take(pageSize)
            val nextCursor =
                if (rawRows.size > pageSize) {
                    pageRows.lastOrNull()?.let { row ->
                        P801AccountReconciliationCursor(
                            occurredAtStorageValue = row.occurred_at,
                            postingId = PostingId(row.posting_id),
                        )
                    }
                } else {
                    null
                }
            P801AccountReconciliationPageResult.Success(
                P801AccountReconciliationQueuePage(
                    rows =
                        pageRows.map { row ->
                            P801AccountReconciliationQueueRow(
                                postingId = PostingId(row.posting_id),
                                transactionId = TransactionId(row.transaction_id),
                                occurredAt = Instant.parse(row.occurred_at),
                                note = row.note,
                                accountId = AccountId(row.account_id),
                                amountMinor = row.amount_minor,
                                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                                status = P408ReconciliationStatus.fromStorage(row.status),
                                hasActiveEvidenceLink = row.active_link_count > 0L,
                                transactionKind = TransactionKind.valueOf(row.kind),
                            )
                        },
                    nextCursor = nextCursor,
                ),
            )
        } catch (failure: Exception) {
            failedPage()
        }
    }

    override fun loadAccountReconciliationSummary(
        ledgerId: LedgerId,
        accountId: AccountId,
    ): P801AccountReconciliationSummaryResult =
        try {
            val totals =
                database.ledgerQueries
                    .p801AccountReconciliationStatusTotals(
                        ledger_id = ledgerId.value,
                        account_id = accountId.value,
                    ).executeAsList()
                    .map { row ->
                        P801AccountReconciliationStatusTotal(
                            currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                            status = P408ReconciliationStatus.fromStorage(row.status),
                            // sum() is typed nullable because an empty input has no value; a GROUP BY
                            // row always has at least one leg, so a null here is a read integrity
                            // failure, never a zero (D-089).
                            totalMinorUnits = checkNotNull(row.total_minor) { "reconciliation status total without a sum" },
                            rowCount = row.row_count.toInt(),
                        )
                    }
            P801AccountReconciliationSummaryResult.Success(totals)
        } catch (failure: Exception) {
            failedSummary()
        }

    private fun failedPage(): P801AccountReconciliationPageResult = P801AccountReconciliationPageResult.Failed(P801AccountReconciliationReadFailure.Unavailable)

    private fun failedSummary(): P801AccountReconciliationSummaryResult = P801AccountReconciliationSummaryResult.Failed(P801AccountReconciliationReadFailure.Unavailable)
}
