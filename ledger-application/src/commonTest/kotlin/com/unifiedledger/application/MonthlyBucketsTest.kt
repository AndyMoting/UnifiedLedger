package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-03.B frozen month bucketing vectors (R-Q06-2, spec sections 3.1/C01): Asia/Shanghai
 * month boundaries, cross-year and leap-February edges, the 12-month trend window and
 * the SelectMonth domain [first statistics month, current month]. All data synthetic and
 * anonymous with fixed instants.
 */
class MonthlyBucketsTest {
    private val ledgerId = LedgerId("ledger-bucket-test")
    private val assetId = AccountId("account-asset")

    @Test
    fun bucketKeyResolvesTheShanghaiMonthBoundaries() {
        // 2026-04-01 00:00 +08:00 == 2026-03-31T16:00:00Z (C01 boundary vector).
        assertEquals(YearMonth(2026, 3), MonthlyBuckets.bucketKey(Instant.parse("2026-03-31T15:59:59Z")))
        assertEquals(YearMonth(2026, 4), MonthlyBuckets.bucketKey(Instant.parse("2026-03-31T16:00:00Z")))
    }

    @Test
    fun bucketKeyHandlesCrossYearAndLeapFebruaryEdges() {
        assertEquals(YearMonth(2025, 12), MonthlyBuckets.bucketKey(Instant.parse("2025-12-31T15:59:59Z")))
        assertEquals(YearMonth(2026, 1), MonthlyBuckets.bucketKey(Instant.parse("2025-12-31T16:00:00Z")))
        // February 2028 has 29 days: the Mar-1 local midnight equals 2028-02-29T16:00:00Z.
        assertEquals(YearMonth(2028, 2), MonthlyBuckets.bucketKey(Instant.parse("2028-02-29T15:59:59Z")))
        assertEquals(YearMonth(2028, 3), MonthlyBuckets.bucketKey(Instant.parse("2028-02-29T16:00:00Z")))
        // Non-leap February ends one day earlier (2026-03-01 00:00 +08 = 2026-02-28T16:00:00Z).
        assertEquals(YearMonth(2026, 2), MonthlyBuckets.bucketKey(Instant.parse("2026-02-28T15:59:59Z")))
        assertEquals(YearMonth(2026, 3), MonthlyBuckets.bucketKey(Instant.parse("2026-02-28T16:00:00Z")))
    }

    @Test
    fun currentMonthResolvesTheInjectedClockInstantInShanghai() {
        assertEquals(YearMonth(2026, 3), MonthlyBuckets.currentMonth(FixedLedgerClock(Instant.parse("2026-03-31T15:59:59Z"))))
        assertEquals(YearMonth(2026, 4), MonthlyBuckets.currentMonth(FixedLedgerClock(Instant.parse("2026-03-31T16:00:00Z"))))
    }

    @Test
    fun monthWindowReturnsTwelveMonthsOldToNewInclusive() {
        val window = MonthlyBuckets.monthWindow(YearMonth(2026, 9))
        assertEquals(12, window.size)
        assertEquals(YearMonth(2025, 10), window.first())
        assertEquals(YearMonth(2026, 9), window.last())
        assertEquals(window, window.sorted())
        assertTrue(YearMonth(2026, 3) in window)
    }

    @Test
    fun selectableMonthRangeSpansFirstStatisticsMonthToCurrentMonth() {
        val rows =
            listOf(
                entryWithStatisticsAt("2026-02-10T02:00:00Z"),
                entryWithStatisticsAt("2025-11-20T02:00:00Z"),
                entryWithStatisticsAt("2026-02-25T02:00:00Z"),
            )
        assertEquals(YearMonth(2025, 11)..YearMonth(2026, 4), MonthlyBuckets.selectableMonthRange(rows, YearMonth(2026, 4)))
    }

    @Test
    fun selectableMonthRangeIsNullForEmptyLedgersAndFutureOnlyStatistics() {
        assertNull(MonthlyBuckets.selectableMonthRange(emptyList(), YearMonth(2026, 4)))
        // Every transaction statistics into a month after "this month": the frozen domain
        // [first statistics month, current month] is empty (spec section 6.2 residual (c)).
        val futureRows = listOf(entryWithStatisticsAt("2026-06-10T02:00:00Z"))
        assertNull(MonthlyBuckets.selectableMonthRange(futureRows, YearMonth(2026, 4)))
    }

    private fun entryWithStatisticsAt(statisticsAt: String) =
        LedgerEntryRow(
            transactionId = TransactionId("tx-$statisticsAt"),
            currentVersionId = TransactionVersionId("version-1"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse(statisticsAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = null,
            postings =
                listOf(
                    Posting(PostingId("posting-1"), assetId, Money.ofMinor(-100L, CurrencyUnit("CNY", 2))),
                ),
        )
}
