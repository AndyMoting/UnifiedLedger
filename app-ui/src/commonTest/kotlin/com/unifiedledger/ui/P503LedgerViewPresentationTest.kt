package com.unifiedledger.ui

import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.MonthlyTrend
import com.unifiedledger.application.MonthlyTrendResult
import com.unifiedledger.application.SelectableMonthsResult
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-03.D presentation-decision vectors (spec section 6.4, R-Q06-4, R-Q07-3, C04; F3/F6). app-ui
 * deliberately has no Compose UI-test harness, so the load-bearing presentation decisions are
 * pure functions and are asserted here: the empty-month vs awaiting vs not-loaded copy, pie
 * sector eligibility (zero and net-negative categories draw nothing), the always-present exact
 * value table, the special-kind disclosure line (R-5) and the fail-closed folding of the monthly
 * cycle's sub-reads. All data is anonymous synthetic with fixed instants; no platform or Compose
 * API is exercised.
 */
class P503LedgerViewPresentationTest {
    private val ledgerId = LedgerId("ledger-presentation-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val march = YearMonth(2026, 3)
    private val marchLabel = "2026-03"

    // ---- month-region state and copy (F2/F6) ----------------------------------------------

    @Test
    fun monthRegionStateDistinguishesEmptyMonthFromAwaitingAndNotLoaded() {
        assertEquals(MonthlyRegionState.AWAITING, monthlyRegionState(null, reloadRequired = false))
        assertEquals(MonthlyRegionState.EMPTY_MONTH, monthlyRegionState(activity(currencies = emptyList()), reloadRequired = false))
        assertEquals(
            MonthlyRegionState.EMPTY_MONTH,
            monthlyRegionState(activity(currencies = listOf(currencyRow(transactionCount = 0))), reloadRequired = false),
        )
        assertEquals(
            MonthlyRegionState.LOADED,
            monthlyRegionState(activity(currencies = listOf(currencyRow(transactionCount = 3))), reloadRequired = false),
        )
        // F2: the post-retry absence wins over any retained payload.
        assertEquals(
            MonthlyRegionState.NOT_LOADED,
            monthlyRegionState(activity(currencies = listOf(currencyRow(transactionCount = 3))), reloadRequired = true),
        )
        assertEquals(MonthlyRegionState.NOT_LOADED, monthlyRegionState(null, reloadRequired = true))
    }

    @Test
    fun monthRegionCopyKeepsEmptyAwaitingAndNotLoadedTextsDistinct() {
        val notLoaded = monthRegionHeadline(MonthlyRegionState.NOT_LOADED, marchLabel, transactionCount = 0)
        val awaiting = monthRegionHeadline(MonthlyRegionState.AWAITING, marchLabel, transactionCount = 0)
        val emptyMonth = monthRegionHeadline(MonthlyRegionState.EMPTY_MONTH, marchLabel, transactionCount = 0)
        val loaded = monthRegionHeadline(MonthlyRegionState.LOADED, marchLabel, transactionCount = 3)

        // The failure/recovery copy must not read as an empty month or as "no data yet" (R-Q06-4).
        assertEquals("月度数据未加载——请重新选择月份。", notLoaded)
        assertTrue(notLoaded != emptyMonth && notLoaded != awaiting && notLoaded != loaded)
        assertTrue(emptyMonth != awaiting && emptyMonth != loaded && awaiting != loaded)
        assertEquals("$marchLabel：该月无交易。", emptyMonth)
        assertEquals("$marchLabel · 交易 3 笔", loaded)
        assertTrue(!notLoaded.contains("无交易"))
    }

    // ---- sector eligibility (R-Q07-3/C04) --------------------------------------------------

    @Test
    fun onlyPositiveNetCategoriesDrawASector() {
        val positive = category("category-food", "餐饮", MonthlyCategoryCurrencyTotal(cny, 3_000L, 0L))
        val zero = category("category-transport", "交通", MonthlyCategoryCurrencyTotal(cny, 0L, 0L))
        val refundOnly = category("category-refund", "退款", MonthlyCategoryCurrencyTotal(cny, 0L, -500L))
        val netZero = category("category-net-zero", "净零", MonthlyCategoryCurrencyTotal(cny, 600L, -600L))

        val sectors = categoryChartSectors(listOf(positive, zero, refundOnly, netZero), withPie = true)
        val cnySectors = assertIs<List<CategoryChartSector>>(sectors[cny])
        assertEquals(1, cnySectors.size)
        assertEquals(3_000L, cnySectors.single().netMinorUnits)
        assertEquals(3_000L, cnySectors.single().total.positiveMinorUnits)
        assertNull(sectors[usd])
        // No chart is requested for a region without a pie (income categories).
        assertTrue(categoryChartSectors(listOf(positive), withPie = false).isEmpty())
    }

    @Test
    fun aCurrencyWithoutAnyPositiveNetContributionDrawsNoSector() {
        val cancelled = category("category-food", "餐饮", MonthlyCategoryCurrencyTotal(cny, 500L, -500L))
        assertTrue(categoryChartSectors(listOf(cancelled), withPie = true).isEmpty())
    }

    @Test
    fun uncategorizedTotalsAreDisclosedButNeverDrawASector() {
        // F10/P703SPEC-09: the 无分类 row is disclosed in the exact values and is not a category,
        // so it never claims a chart proportion.
        val payload =
            activity(
                currencies = listOf(currencyRow(transactionCount = 2)),
                uncategorizedExpenseTotals = listOf(MonthlyCategoryCurrencyTotal(cny, 700L, 0L)),
                uncategorizedIncomeTotals = listOf(MonthlyCategoryCurrencyTotal(cny, 100L, -20L)),
            )
        assertTrue(categoryChartSectors(payload.expenseCategories, withPie = true).isEmpty())
        assertEquals("CNY 正向 7.00，退款 0.00", categoryTotalsText(payload.uncategorizedExpenseTotals))
        assertEquals("CNY 正向 1.00，退款 -0.20", categoryTotalsText(payload.uncategorizedIncomeTotals))
    }

    // ---- chart / exact-table pairing (C04) -------------------------------------------------

    @Test
    fun theExactValueTableAlwaysAccompaniesTheChart() {
        val zeroOnly = listOf(category("category-transport", "交通", MonthlyCategoryCurrencyTotal(cny, 0L, 0L)))
        val withPositive = listOf(category("category-food", "餐饮", MonthlyCategoryCurrencyTotal(cny, 3_000L, 0L)))

        val noSector = categoryChartPresentation(withPie = true, categories = zeroOnly)
        assertEquals(false, noSector.rendersChart)
        assertEquals(true, noSector.rendersExactTable)
        assertEquals(true, noSector.chartAccompaniedByExactTable)

        val withSector = categoryChartPresentation(withPie = true, categories = withPositive)
        assertEquals(true, withSector.rendersChart)
        assertEquals(true, withSector.rendersExactTable)
        assertEquals(true, withSector.chartAccompaniedByExactTable)

        val incomeRegion = categoryChartPresentation(withPie = false, categories = withPositive)
        assertEquals(false, incomeRegion.rendersChart)
        assertEquals(true, incomeRegion.rendersExactTable)

        val emptyRegion = categoryChartPresentation(withPie = true, categories = emptyList())
        assertEquals(false, emptyRegion.rendersChart)
        assertEquals(false, emptyRegion.rendersExactTable)
        assertEquals(true, emptyRegion.chartAccompaniedByExactTable)
    }

    @Test
    fun exactCategoryValuesKeepTheirSigns() {
        assertEquals("该分类本月无金额", categoryTotalsText(emptyList()))
        assertEquals("CNY 正向 35.80，退款 0.00", categoryTotalsText(listOf(MonthlyCategoryCurrencyTotal(cny, 3_580L, 0L))))
        // R-Q07-3: a refund stays signed, never masked with an absolute value.
        assertEquals("CNY 正向 0.00，退款 -30.00", categoryTotalsText(listOf(MonthlyCategoryCurrencyTotal(cny, 0L, -3_000L))))
    }

    // ---- special-kind disclosure (F4/R-5) --------------------------------------------------

    @Test
    fun specialKindDisclosureCountsOnlySpecialKinds() {
        val disclosure =
            specialKindDisclosure(
                activity(
                    currencies =
                        listOf(
                            currencyRow(
                                transactionCount = 4,
                                countByKind =
                                    mapOf(
                                        TransactionKind.EXPENSE to 2,
                                        TransactionKind.STORED_VALUE_RECHARGE to 1,
                                        TransactionKind.BALANCE_ADJUSTMENT to 1,
                                    ),
                            ),
                        ),
                ),
            )
        assertEquals("特殊科目（不计入普通收支）：BALANCE_ADJUSTMENT 1 笔、STORED_VALUE_RECHARGE 1 笔", disclosure)
        assertTrue(disclosure!!.contains("BALANCE_ADJUSTMENT 1 笔"))
        assertTrue(disclosure.contains("STORED_VALUE_RECHARGE 1 笔"))
        assertTrue(!disclosure.contains(TransactionKind.EXPENSE.name))
    }

    @Test
    fun specialKindDisclosureIsAbsentWithoutSpecialKinds() {
        assertNull(
            specialKindDisclosure(
                activity(currencies = listOf(currencyRow(transactionCount = 2, countByKind = mapOf(TransactionKind.EXPENSE to 2)))),
            ),
        )
        assertNull(specialKindDisclosure(null))
    }

    // ---- monthly cycle folding (F3) --------------------------------------------------------

    @Test
    fun theCycleIsReadyOnlyWhenEverySubReadSucceeds() {
        val payload = activity(currencies = listOf(currencyRow(transactionCount = 1)))
        val ready =
            assertIs<MonthlyCycleOutcome.Ready>(
                foldMonthlyCycle(
                    monthResult = MonthlyActivityResult.Success(payload),
                    selectableMonthsResult = SelectableMonthsResult.Success(listOf(march)),
                    trendResult = MonthlyTrendResult.Success(trend()),
                ),
            )
        assertEquals(payload, ready.activity)
        assertEquals(listOf(march), ready.selectableMonths)
        val trendMonths = ready.trend.months
        assertEquals(1, trendMonths.size)
        assertEquals(march, trendMonths.single().month)
    }

    @Test
    fun everySubReadShortfallFoldsIntoATypedFailure() {
        val payload = activity(currencies = listOf(currencyRow(transactionCount = 1)))
        val successMonth = MonthlyActivityResult.Success(payload)
        val successMonths = SelectableMonthsResult.Success(listOf(march))
        val successTrend = MonthlyTrendResult.Success(trend())

        assertEquals(
            MonthlyActivityResult.Unavailable,
            assertIs<MonthlyCycleOutcome.Failed>(foldMonthlyCycle(MonthlyActivityResult.Unavailable, successMonths, successTrend)).result,
        )
        assertEquals(
            MonthlyActivityResult.InvalidState,
            assertIs<MonthlyCycleOutcome.Failed>(foldMonthlyCycle(MonthlyActivityResult.InvalidState, successMonths, successTrend)).result,
        )
        // A failed SelectMonth domain is a read failure, never an empty (disabled) selector.
        assertEquals(
            MonthlyActivityResult.Unavailable,
            assertIs<MonthlyCycleOutcome.Failed>(foldMonthlyCycle(successMonth, SelectableMonthsResult.Unavailable, successTrend)).result,
        )
        // A failed trend is a read failure, never a bare 暂无趋势数据 placeholder.
        assertEquals(
            MonthlyActivityResult.InvalidState,
            assertIs<MonthlyCycleOutcome.Failed>(foldMonthlyCycle(successMonth, successMonths, MonthlyTrendResult.InvalidState)).result,
        )
        assertEquals(
            MonthlyActivityResult.Unavailable,
            assertIs<MonthlyCycleOutcome.Failed>(foldMonthlyCycle(successMonth, successMonths, MonthlyTrendResult.Unavailable)).result,
        )
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun activity(
        currencies: List<MonthlyCurrencyActivity>,
        expenseCategories: List<MonthlyCategoryTotal> = emptyList(),
        incomeCategories: List<MonthlyCategoryTotal> = emptyList(),
        uncategorizedExpenseTotals: List<MonthlyCategoryCurrencyTotal> = emptyList(),
        uncategorizedIncomeTotals: List<MonthlyCategoryCurrencyTotal> = emptyList(),
    ): MonthlyActivity =
        MonthlyActivity(
            ledgerId = ledgerId,
            month = march,
            currencies = currencies,
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            uncategorizedExpenseTotals = uncategorizedExpenseTotals,
            uncategorizedIncomeTotals = uncategorizedIncomeTotals,
        )

    private fun currencyRow(
        transactionCount: Int,
        countByKind: Map<TransactionKind, Int> = emptyMap(),
    ): MonthlyCurrencyActivity =
        MonthlyCurrencyActivity(
            currency = cny,
            ordinaryIncomeMinorUnits = 0L,
            netExpenseMinorUnits = 3_580L,
            balanceMinorUnits = -3_580L,
            positiveExpenseMinorUnits = 3_580L,
            refundMinorUnits = 0L,
            transactionCount = transactionCount,
            countByKind = countByKind,
        )

    private fun category(
        id: String,
        name: String,
        total: MonthlyCategoryCurrencyTotal,
    ): MonthlyCategoryTotal =
        MonthlyCategoryTotal(
            categoryId = CategoryId(id),
            categoryName = name,
            totals = listOf(total),
            children = emptyList(),
        )

    private fun trend(): MonthlyTrend {
        val payload = activity(currencies = listOf(currencyRow(transactionCount = 1)))
        return MonthlyTrend(ledgerId = ledgerId, window = listOf(march), months = listOf(payload))
    }
}
