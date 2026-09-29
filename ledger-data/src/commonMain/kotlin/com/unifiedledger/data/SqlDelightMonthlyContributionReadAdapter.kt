package com.unifiedledger.data

import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyContributionReadFailure
import com.unifiedledger.application.MonthlyContributionReadPort
import com.unifiedledger.application.MonthlyContributionReadResult
import com.unifiedledger.data.db.LedgerDatabase
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
 * P7-07 07.D-1 bounded contribution read adapter (D-184 item 3 residual; spec section 5.1).
 *
 * Implements the shared [MonthlyContributionReadPort]. The `[start, end)` bounds are projected to
 * epoch nanoseconds ([StatisticsAtProjection]) BEFORE the transaction; inside ONE SQLite read
 * transaction the adapter then:
 *
 *  1. runs the fail-loud missing-projection probe (spec section 6.4 item 5) — a non-zero count
 *     is [MonthlyContributionReadFailure.MissingProjection], never a silent omission;
 *  2. loads the catalog generation ([CatalogAuthorityReader]) and reads the window's current-
 *     version effective rows (`monthlyContributionRowsInWindow`, the projection-indexed
 *     analogue of `ledgerEntryRowsForLedger` with a `[start, end)` predicate);
 *  3. fails with [MonthlyContributionReadFailure.CatalogVersionMismatch] unless the snapshot's
 *     generation equals the caller's `expectedCatalogVersion` (spec section 5.1 / open item 9).
 *
 * Steps 1-3 run inside one `transactionWithResult` on one connection, so SQLite keeps a single
 * read snapshot: the catalog generation and the postings are read from the SAME generation (a
 * catalog write cannot land between the generation read and the row read within one snapshot).
 * The gate is therefore the caller's `expectedCatalogVersion` against that snapshot generation:
 * a caller holding a stale generation, or a catalog write that committed before this read, is
 * detected and never silently zeroed. The adapter reuses the frozen effective predicate
 * (`transaction_effective_state`) through the SQL query and never adds a second state rule.
 * Amounts are folded in Kotlin by the caller's frozen classifier; this adapter never SUMs or
 * DISTINCTs in SQL.
 *
 * Read-only: no Posting is created, no balance/reconciliation is changed.
 */
class SqlDelightMonthlyContributionReadAdapter(
    private val database: LedgerDatabase,
    private val catalogReader: CatalogAuthorityReader,
) : MonthlyContributionReadPort {
    override fun readContributions(
        ledgerId: LedgerId,
        startInclusive: Instant,
        endExclusive: Instant,
        expectedCatalogVersion: Long,
    ): MonthlyContributionReadResult {
        // A window bound outside the projection's representable range cannot be read; refuse
        // rather than silently returning an empty (zero) window (spec section 6.5).
        val startNanos = StatisticsAtProjection.project(startInclusive) ?: return failed(MonthlyContributionReadFailure.Unavailable)
        val endNanos = StatisticsAtProjection.project(endExclusive) ?: return failed(MonthlyContributionReadFailure.Unavailable)
        return try {
            // One read-only snapshot covers the probe, the catalog load and the window rows: the
            // generation and the postings cannot span two catalog generations inside one
            // snapshot, so a single generation comparison is the complete gate. noEnclosing = true
            // does not change the BEGIN mode on this driver version; it makes the nesting contract
            // fail loud instead of silently nesting. (SQLDelight 2.3.2's
            // Transacter.transactionWithResult names this parameter noEnclosing — there is no
            // `readOnly` parameter.)
            database.transactionWithResult(noEnclosing = true) {
                val missing = database.ledgerQueries.statisticsAtProjectionMissingForLedger(ledgerId.value).executeAsOne()
                if (missing != 0L) {
                    return@transactionWithResult failed(MonthlyContributionReadFailure.MissingProjection)
                }
                val authority =
                    catalogReader.load(ledgerId)
                        ?: return@transactionWithResult failed(MonthlyContributionReadFailure.Unavailable)
                val rows = windowRows(ledgerId, startNanos, endNanos)
                // The snapshot generation must be exactly the caller's; a mismatch (a stale
                // caller generation, or a catalog write committed before this read) is typed.
                if (authority.catalogVersion != expectedCatalogVersion) {
                    return@transactionWithResult failed(MonthlyContributionReadFailure.CatalogVersionMismatch)
                }
                MonthlyContributionReadResult.Success(
                    catalogVersion = authority.catalogVersion,
                    catalog = authority.catalog,
                    rows = rows,
                )
            }
        } catch (failure: Exception) {
            // A database/read failure is the typed Unavailable, never an empty (zero) window.
            failed(MonthlyContributionReadFailure.Unavailable)
        }
    }

    private fun windowRows(
        ledgerId: LedgerId,
        startNanos: Long,
        endNanos: Long,
    ): List<LedgerEntryRow> =
        database.ledgerQueries
            .monthlyContributionRowsInWindow(ledgerId.value, startNanos, endNanos)
            .executeAsList()
            .groupBy { it.transaction_id }
            .map { (_, groupedRows) ->
                val first = groupedRows.first()
                LedgerEntryRow(
                    transactionId = TransactionId(first.transaction_id),
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

    private fun failed(failure: MonthlyContributionReadFailure): MonthlyContributionReadResult = MonthlyContributionReadResult.Failed(failure)
}
