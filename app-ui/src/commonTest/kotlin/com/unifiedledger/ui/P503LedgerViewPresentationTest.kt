package com.unifiedledger.ui

import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.MonthlyTrend
import com.unifiedledger.application.MonthlyTrendResult
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.SelectableMonthsResult
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionReconciliationLeg
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    // ---- G batch: post-retry honesty, banner copy, arrow edges, degraded copy ---------------

    @Test
    fun monthRegionCopyDegradesWhenNoReloadActionIsPossible() {
        // G4: without a resolvable month the card must not instruct an action the UI cannot offer.
        val reloadable = monthRegionHeadline(MonthlyRegionState.NOT_LOADED, marchLabel, transactionCount = 0, reloadPossible = true)
        val degraded = monthRegionHeadline(MonthlyRegionState.NOT_LOADED, marchLabel, transactionCount = 0, reloadPossible = false)
        assertEquals("月度数据未加载——请重新选择月份。", reloadable)
        assertTrue(degraded != reloadable)
        assertTrue(!degraded.contains("请重新选择月份"))
        assertEquals("月度数据未加载，且当前无法重新选择月份。", degraded)
        // The other states are unaffected by the degraded variant.
        assertEquals(
            monthRegionHeadline(MonthlyRegionState.EMPTY_MONTH, marchLabel, transactionCount = 0, reloadPossible = true),
            monthRegionHeadline(MonthlyRegionState.EMPTY_MONTH, marchLabel, transactionCount = 0, reloadPossible = false),
        )
    }

    @Test
    fun trendRegionStateReplacesTheStaleTableAfterARetryRecovery() {
        // G1: a not-loaded cycle must not present the previous cycle's table as current; the
        // NOT_LOADED treatment is a different class from the 暂无趋势数据 placeholder.
        assertEquals(TrendRegionState.AWAITING, trendRegionState(null, reloadRequired = false))
        assertEquals(TrendRegionState.LOADED, trendRegionState(trend(), reloadRequired = false))
        assertEquals(TrendRegionState.NOT_LOADED, trendRegionState(trend(), reloadRequired = true))
        assertEquals(TrendRegionState.NOT_LOADED, trendRegionState(null, reloadRequired = true))

        assertEquals(null, trendRegionPlaceholderText(TrendRegionState.LOADED))
        val awaiting = trendRegionPlaceholderText(TrendRegionState.AWAITING)
        val notLoaded = trendRegionPlaceholderText(TrendRegionState.NOT_LOADED)
        assertEquals("暂无趋势数据。", awaiting)
        assertEquals("月度数据未加载——趋势需重新选择月份后加载。", notLoaded)
        assertTrue(notLoaded != awaiting)
        assertTrue(!notLoaded!!.contains("暂无"))
    }

    @Test
    fun categoryRegionSaysNotLoadedInsteadOfRenderingNothing() {
        // G1: an un-reloaded cycle must not read as "this month has no categories".
        assertEquals(null, categoryRegionPlaceholderText(reloadRequired = false))
        assertEquals("月度数据未加载——分类合计需重新选择月份后加载。", categoryRegionPlaceholderText(reloadRequired = true))
    }

    @Test
    fun flowRowsFallBackToTheFreshCurrentStateAfterARetryRecovery() {
        // G1 (R-Q06-4): the stale display-ordered rows must not be presented as the current list.
        val rows = listOf(ledgerRow())
        assertSame(rows, flowRowsForDisplay(rows, reloadRequired = false))
        assertNull(flowRowsForDisplay(rows, reloadRequired = true))
        assertNull(flowRowsForDisplay(null, reloadRequired = false))
    }

    @Test
    fun trendArrowEdgesMirrorTheMonthSelectorAdmissionRule() {
        // G3: an edge that would be absorbed by the reducer must not be offered as a live arrow.
        // The returned month is the domain's extreme in that direction — the pre-existing selector
        // semantics (上一月 jumps to the earliest selectable month, 下一月 to the latest). What the
        // arrow enablement depends on is whether such a month exists at all, which for the
        // contiguous frozen domain `[first statistics month, 本月]` is exactly the condition for a
        // one-month step to stay inside it.
        val domain = listOf(YearMonth(2026, 1), YearMonth(2026, 2), march)
        val middle = monthStepEdges(selectedMonth = YearMonth(2026, 2), resolvedCurrentMonth = null, selectableMonths = domain)
        assertEquals(YearMonth(2026, 1), middle.previous)
        assertEquals(march, middle.next)

        val atFirst = monthStepEdges(selectedMonth = YearMonth(2026, 1), resolvedCurrentMonth = null, selectableMonths = domain)
        assertNull(atFirst.previous)
        assertTrue(atFirst.next != null)

        val atLast = monthStepEdges(selectedMonth = march, resolvedCurrentMonth = null, selectableMonths = domain)
        assertEquals(YearMonth(2026, 1), atLast.previous)
        assertNull(atLast.next)

        // The presence of an edge is equivalent to the one-month step landing inside the domain.
        assertEquals(atFirst.next != null, YearMonth(2026, 2) in domain)
        assertEquals(atLast.previous != null, YearMonth(2026, 2) in domain)

        // Before the first payload (no domain) and on a single-month ledger both edges are absent.
        val empty = monthStepEdges(selectedMonth = null, resolvedCurrentMonth = null, selectableMonths = emptyList())
        assertNull(empty.previous)
        assertNull(empty.next)
        val single = monthStepEdges(selectedMonth = null, resolvedCurrentMonth = march, selectableMonths = listOf(march))
        assertNull(single.previous)
        assertNull(single.next)
    }

    @Test
    fun monthStepEdgesFallBackToTheResolvedCurrentMonth() {
        // 本月 (selectedMonth == null) resolves to the injected current month, exactly like the
        // month label and the reducer's shift base.
        val edges = monthStepEdges(selectedMonth = null, resolvedCurrentMonth = march, selectableMonths = listOf(YearMonth(2026, 2), march))
        assertEquals(YearMonth(2026, 2), edges.previous)
        assertNull(edges.next)
    }

    @Test
    fun retainedFailureBannerNeverOverClaimsALoadedMonth() {
        // G2: the banner copy follows the retained overview's own month-region state.
        val loaded = retainedReadFailureBannerText(MonthlyRegionState.LOADED)
        val emptyMonth = retainedReadFailureBannerText(MonthlyRegionState.EMPTY_MONTH)
        val awaiting = retainedReadFailureBannerText(MonthlyRegionState.AWAITING)
        val notLoaded = retainedReadFailureBannerText(MonthlyRegionState.NOT_LOADED)
        assertEquals("月度数据读取失败，以下为上一次成功加载的月份。", loaded)
        assertEquals(loaded, emptyMonth)
        assertEquals("月度数据读取失败，本月的月度数据尚未加载。", awaiting)
        assertEquals(awaiting, notLoaded)
        assertTrue(loaded != awaiting)
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

    // ---- TalkBack labels (C04, spec section 6.4) -------------------------------------------

    @Test
    fun monthCardLabelStatesTheThreeExactValuesWithSignAndCurrencyCode() {
        // A refund month keeps the negative sign (R-Q07-3: never masked with an absolute value).
        val row =
            MonthlyCurrencyActivity(
                currency = cny,
                ordinaryIncomeMinorUnits = 10_500L,
                netExpenseMinorUnits = -3_000L,
                balanceMinorUnits = 13_500L,
                positiveExpenseMinorUnits = 0L,
                refundMinorUnits = -3_000L,
                transactionCount = 2,
                countByKind = emptyMap(),
            )
        assertEquals("CNY：普通收入 105.00，净支出 -30.00，结余 135.00", monthCardCurrencyLineText(row))
        assertTrue(monthCardCurrencyLineText(row.copy(currency = usd)).startsWith("USD："))
    }

    @Test
    fun flowRowLabelAnnouncesKindStatisticsNoteAndExactPostingValues() {
        val row =
            LedgerEntryRow(
                transactionId = TransactionId("tx-flow-label"),
                currentVersionId = TransactionVersionId("version-flow-label-1"),
                kind = TransactionKind.LEND,
                occurredAt = kotlin.time.Instant.parse("2026-03-01T02:00:00Z"),
                statisticsAt = kotlin.time.Instant.parse("2026-03-05T02:00:00Z"),
                note = "lend note",
                postings =
                    listOf(
                        Posting(PostingId("posting-flow-out"), AccountId("account-asset"), Money.ofMinor(-10_000L, cny)),
                        Posting(PostingId("posting-flow-in"), AccountId("account-receivable"), Money.ofMinor(10_000L, cny)),
                    ),
            )
        val names = mapOf(AccountId("account-asset") to "资产-现金", AccountId("account-receivable") to "资产-应收")
        val label = flowRowContentDescription(row, names)
        assertTrue(label.contains("LEND"))
        assertTrue(label.contains("统计时间 2026-03-05T02:00:00Z"))
        assertTrue(label.contains("备注 lend note"))
        assertTrue(label.contains("资产-现金 -100.00 CNY"))
        assertTrue(label.contains("资产-应收 100.00 CNY"))
        // An unmapped account falls back to the account id; a note-less row omits the note part.
        val fallback = flowRowContentDescription(row.copy(note = null), emptyMap())
        assertTrue(fallback.contains("account-asset -100.00 CNY"))
        assertTrue(!fallback.contains("备注"))
    }

    @Test
    fun categoryRowLabelCarriesTheCurrentNameAndExactTotals() {
        assertEquals(
            "餐饮：CNY 正向 35.80，退款 -30.00",
            categoryRowContentDescription("餐饮", listOf(MonthlyCategoryCurrencyTotal(cny, 3_580L, -3_000L))),
        )
        assertEquals("餐饮：该分类本月无金额", categoryRowContentDescription("餐饮", emptyList()))
    }

    @Test
    fun chartLabelAnnouncesExactSectorValuesAndNeverAPercentage() {
        val categories =
            listOf(
                category("category-food", "餐饮", MonthlyCategoryCurrencyTotal(cny, 3_000L, 0L)),
                category("category-transport", "交通", MonthlyCategoryCurrencyTotal(cny, 1_200L, -200L)),
                category("category-zero", "零额", MonthlyCategoryCurrencyTotal(cny, 0L, 0L)),
            )
        val sectors = categoryChartSectors(categories, withPie = true).getValue(cny)
        val label = categoryChartContentDescription(cny, sectors)
        assertTrue(label.contains("CNY 构成比例，精确数值："))
        assertTrue(label.contains("餐饮 净 30.00 CNY"))
        assertTrue(label.contains("交通 净 10.00 CNY"))
        // A zero category draws no sector (R-Q07-3) and its name stays out of the label.
        assertTrue(!label.contains("零额"))
        // C04: TalkBack never hears a precisionless percentage.
        assertTrue(!label.contains("%"))
    }

    @Test
    fun trendMonthLabelKeepsExactValuesAndTheDistinctEmptyMonthCopy() {
        val loaded = trendMonthLineText(activity(currencies = listOf(currencyRow(transactionCount = 1))))
        assertEquals("2026-03：CNY 普通收入 0.00，净支出 35.80，结余 -35.80", loaded)
        // R-Q06-4: the explicit empty-month copy is distinct from any read-failure copy.
        val empty = trendMonthLineText(activity(currencies = emptyList()))
        assertEquals("2026-03：该月无交易", empty)
        assertTrue(!empty.contains("读取失败"))
        assertTrue(empty != retainedReadFailureBannerText(MonthlyRegionState.AWAITING))
    }

    @Test
    fun detailLegLabelsStateExactAmountsCategoryAndReconciliationStatus() {
        val leg =
            TransactionDetailLeg(
                postingId = PostingId("posting-detail-label"),
                accountId = AccountId("account-expense"),
                accountName = "支出-早餐账户",
                amount = Money.ofMinor(-3_000L, cny),
                categoryName = "早餐（改名后）",
            )
        assertEquals(
            "支出-早餐账户 -30.00 CNY（分类：早餐（改名后））",
            transactionDetailLegContentDescription(leg),
        )
        assertTrue(transactionDetailLegContentDescription(leg.copy(categoryName = null)).endsWith("（无分类）"))

        val checked =
            TransactionReconciliationLeg(
                leg = leg,
                eligible = true,
                status = P408ReconciliationStatus.CHECKED,
            )
        val checkedLabel = reconciliationLegContentDescription(checked)
        assertTrue(checkedLabel.contains("支出-早餐账户 -30.00 CNY"))
        assertTrue(checkedLabel.contains(P408ReconciliationStatus.CHECKED.label))
        val ineligible =
            TransactionReconciliationLeg(
                leg = leg,
                eligible = false,
                status = null,
            )
        assertTrue(reconciliationLegContentDescription(ineligible).contains("—（无对账资格）"))
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

    private fun ledgerRow(): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId("tx-presentation-1"),
            currentVersionId = TransactionVersionId("version-presentation-1"),
            kind = TransactionKind.EXPENSE,
            occurredAt = kotlin.time.Instant.parse("2026-03-02T02:00:00Z"),
            statisticsAt = kotlin.time.Instant.parse("2026-03-02T02:00:00Z"),
            note = null,
            postings =
                listOf(
                    Posting(PostingId("posting-presentation-1"), AccountId("account-presentation-asset"), Money.ofMinor(-3_580L, cny)),
                ),
        )
}
