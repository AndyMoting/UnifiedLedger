package com.unifiedledger.application.export.readable

import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.LedgerId
import com.unifiedledger.application.QueryLedgerFlowPage
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P8-05 批 a (V-3): the fake page source behind the bounded-memory assertions. Reproduces the
 * frozen keyset walk over a canned row list and exposes the two observables the spec defines:
 *
 * - [nextCallCount]: one count per `firstPage`/`pageAfter` request — the export must end with
 *   `请求数 = 页数` (every page requested exactly once, zero prefetch, zero re-read);
 * - [inFlightViolations]: at each NEXT request, the previously delivered page must already be
 *   fully consumed. The test wires [consumedRowsProvider] to the fake writer's row counter, so
 *   "已交付未消费页 ≤ 1" is observed at the source itself, never inferred from memory sampling.
 */
class FakeFlowPageSource(
    rows: List<LedgerEntryRow>,
    private val pageSize: Int = 2,
    private val consumedRowsProvider: () -> Int = { 0 },
) {
    private val sorted =
        rows.sortedWith(
            compareByDescending<LedgerEntryRow> { it.statisticsAt }
                .thenByDescending { it.transactionId.value },
        )

    private val ledgerId = LedgerId("ledger-bounded-memory")

    var nextCallCount: Int = 0
        private set

    var inFlightViolations: Int = 0
        private set

    /** When true, `effectiveTransactionCount` throws (the total-read failure vector). */
    var failTotal: Boolean = false

    /** When true, every page request AFTER the first throws (the mid-walk failure vector). */
    var failFromSecondCall: Boolean = false

    private var deliveredRows = 0

    /** The `QueryLedgerFlowPage` the export walks (the real use-case entry point). */
    fun flowPage(): QueryLedgerFlowPage =
        QueryLedgerFlowPage(
            object : LedgerCurrentStateReadPort() {
                override fun loadLedgerFlowPage(
                    ledgerId: LedgerId,
                    after: LedgerCurrentStateReadPort.LedgerFlowCursor?,
                    pageSize: Int,
                ): LedgerCurrentStateReadPort.LedgerFlowPage {
                    if (failFromSecondCall && nextCallCount > 0) {
                        throw IllegalStateException("simulated mid-walk page read failure")
                    }
                    if (nextCallCount > 0 && consumedRowsProvider() < deliveredRows) {
                        inFlightViolations++
                    }
                    nextCallCount++
                    val filtered =
                        sorted.filter { row ->
                            val nanos = row.statisticsAt.epochSeconds * 1_000_000_000L + row.statisticsAt.nanosecondsOfSecond.toLong()
                            after == null ||
                                nanos < after.statisticsAtEpochNanos ||
                                (nanos == after.statisticsAtEpochNanos && row.transactionId.value < after.transactionId)
                        }
                    val pageRows = filtered.take(pageSize)
                    deliveredRows += pageRows.size
                    val nextCursor =
                        if (filtered.size > pageRows.size) {
                            pageRows.lastOrNull()?.let { row ->
                                LedgerCurrentStateReadPort.LedgerFlowCursor(row.statisticsAt.epochSeconds * 1_000_000_000L + row.statisticsAt.nanosecondsOfSecond.toLong(), row.transactionId.value)
                            }
                        } else {
                            null
                        }
                    return LedgerCurrentStateReadPort.LedgerFlowPage(pageRows, nextCursor)
                }

                override fun effectiveTransactionCount(ledgerId: LedgerId): Int {
                    if (failTotal) throw IllegalStateException("simulated total read failure")
                    return sorted.size
                }
            },
            ledgerId,
        )
}

/** Builds one two-leg expense row (the smallest walkable shape for the fake source). */
fun expenseRow(
    transactionId: String,
    statisticsAt: Instant,
    occurredAt: Instant = statisticsAt,
    minorUnits: Long = 1_00L,
    note: String? = null,
): LedgerEntryRow =
    LedgerEntryRow(
        transactionId = TransactionId(transactionId),
        currentVersionId = TransactionVersionId("v-$transactionId"),
        kind = TransactionKind.EXPENSE,
        occurredAt = occurredAt,
        statisticsAt = statisticsAt,
        note = note,
        postings =
            listOf(
                Posting(PostingId("p0-$transactionId"), AccountId("expense-account"), Money.ofMinor(minorUnits, CurrencyUnit("CNY", 2))),
                Posting(PostingId("p1-$transactionId"), AccountId("asset-account"), Money.ofMinor(-minorUnits, CurrencyUnit("CNY", 2))),
            ),
    )
