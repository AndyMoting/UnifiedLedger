package com.unifiedledger.ui

import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-00 b4b presentation vectors: the home tab's day grouping and summary pages are pure decisions,
 * so they are asserted here rather than through a Compose harness (app-ui has none by design).
 * Every instant is fixed and every value is anonymous synthetic data; no platform API is touched.
 *
 * The load-bearing claims: grouping preserves the port's display order and buckets by the frozen
 * reporting zone (R-Q06-2), a null catalog degrades a day header to its count instead of inventing
 * a sum, and each summary page reads exactly one field of the month card's own projection so the
 * card can never disagree with the month card or the analysis regions.
 */
class P8HomeFlowTest {
    private val ledgerId = LedgerId("ledger-local")

    private fun row(
        id: String,
        statisticsAt: String,
    ): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId(id),
            currentVersionId = TransactionVersionId("version-$id"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse(statisticsAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = null,
            postings =
                listOf(
                    Posting(
                        id = PostingId("posting-$id"),
                        accountId = AccountId("account-expense"),
                        amount = Money.ofMinor(100L, CurrencyUnit("CNY", 2)),
                    ),
                ),
        )

    @Test
    fun groupingBucketsByReportingDayAndKeepsThePortOrder() {
        val rows =
            listOf(
                row("t3", "2026-11-09T12:00:00Z"),
                row("t2", "2026-11-09T03:00:00Z"),
                row("t1", "2026-11-08T12:00:00Z"),
            )

        val groups = p8GroupByDay(rows)

        assertEquals(listOf(LocalDate(2026, 11, 9), LocalDate(2026, 11, 8)), groups.map { it.date })
        assertEquals(listOf("t3", "t2"), groups[0].rows.map { it.transactionId.value })
        assertEquals(listOf("t1"), groups[1].rows.map { it.transactionId.value })
    }

    @Test
    fun aDayBoundaryIsTheFrozenReportingZoneNotUtc() {
        // 16:30Z is still 2026-11-09 in UTC but already 2026-11-10 00:30 in Asia/Shanghai (R-Q06-2).
        val groups = p8GroupByDay(listOf(row("late", "2026-11-09T16:30:00Z")))

        assertEquals(LocalDate(2026, 11, 10), groups.single().date)
    }


    @Test
    fun noRowsMeansNoGroups() {
        assertTrue(p8GroupByDay(emptyList()).isEmpty())
    }

    @Test
    fun eachSummaryPageReadsExactlyOneProjectionField() {
        val activity =
            MonthlyActivity(
                ledgerId = ledgerId,
                month = YearMonth(2026, 11),
                currencies =
                    listOf(
                        MonthlyCurrencyActivity(
                            currency = CurrencyUnit("CNY", 2),
                            ordinaryIncomeMinorUnits = 120_00L,
                            netExpenseMinorUnits = 35_50L,
                            balanceMinorUnits = 85_00L,
                            positiveExpenseMinorUnits = 35_50L,
                            refundMinorUnits = 0L,
                            transactionCount = 3,
                            countByKind = emptyMap(),
                        ),
                    ),
                expenseCategories = emptyList(),
                incomeCategories = emptyList(),
            )

        assertEquals(
            listOf(P8SummaryLine("CNY", 2, 35_50L)),
            p8SummaryLines(activity, P8SummaryPage.EXPENSE),
        )
        assertEquals(
            listOf(P8SummaryLine("CNY", 2, 120_00L)),
            p8SummaryLines(activity, P8SummaryPage.INCOME),
        )
        assertEquals(
            listOf(P8SummaryLine("CNY", 2, 85_00L)),
            p8SummaryLines(activity, P8SummaryPage.BALANCE),
        )
    }

    @Test
    fun anAbsentPayloadCarriesNoZeroValues() {
        assertTrue(p8SummaryLines(null, P8SummaryPage.EXPENSE).isEmpty())
        assertEquals("支出：暂无月度数据", p8SummaryPageDescription(P8SummaryPage.EXPENSE, emptyList()))
    }

    @Test
    fun anEmptyMonthNeverShowsZeroAmounts() {
        // EMPTY_MONTH still carries a currency axis, so an ungated card would print "CNY 0.00"
        // above the frozen 该月无交易 copy. Only a LOADED region may state amounts (R-Q06-4).
        val activity =
            MonthlyActivity(
                ledgerId = ledgerId,
                month = YearMonth(2026, 11),
                currencies =
                    listOf(
                        MonthlyCurrencyActivity(
                            currency = CurrencyUnit("CNY", 2),
                            ordinaryIncomeMinorUnits = 0L,
                            netExpenseMinorUnits = 0L,
                            balanceMinorUnits = 0L,
                            positiveExpenseMinorUnits = 0L,
                            refundMinorUnits = 0L,
                            transactionCount = 0,
                            countByKind = emptyMap(),
                        ),
                    ),
                expenseCategories = emptyList(),
                incomeCategories = emptyList(),
            )

        assertTrue(
            p8SummaryLinesFor(activity, P8SummaryPage.EXPENSE, MonthlyRegionState.EMPTY_MONTH).isEmpty(),
        )
        assertEquals(
            1,
            p8SummaryLinesFor(activity, P8SummaryPage.EXPENSE, MonthlyRegionState.LOADED).size,
        )
    }

    @Test
    fun theSummaryDescriptionCarriesEveryExactValue() {
        val lines = listOf(P8SummaryLine("CNY", 2, 35_50L), P8SummaryLine("JPY", 0, 100L))

        assertEquals("支出：CNY 35.50；JPY 100", p8SummaryPageDescription(P8SummaryPage.EXPENSE, lines))
    }
}
