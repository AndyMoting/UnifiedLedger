package com.unifiedledger.ui

import com.unifiedledger.application.P806CategoryCurrencyTotal
import com.unifiedledger.application.P806CategoryView
import com.unifiedledger.application.P806CurrencyView
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806ResolvedPeriod
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806Span
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch b (D-343 sections 3.1/3.3/3.7/3.9; the approved T2 presentation choice): the report
 * region's pure presentation decisions.
 *
 * These assertions cover acceptance D without a Compose runtime: the six distinguishable payload
 * states, the old-context labelling (real period, currency axis, read instant), the per-currency
 * signed 2x2 with zero/not-applicable handling, the full-natural-day denominator note, the future
 * marker's ABSENCE at N = 0, the exact minor-unit formatting at the Long.MIN_VALUE end, and the
 * two-level category rows with the explicit uncategorized row — all on anonymous synthetic data.
 * They are NOT P8-06's whole verification vectors: drilldown, calendar and device evidence are
 * outside this slice.
 */
class P806ReportRegionTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val readInstant = Instant.parse("2026-11-18T02:00:00Z")

    private fun monthPeriod(): P806ResolvedPeriod =
        P806ResolvedPeriod(
            window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L),
            startDate = LocalDate(2026, 11, 1),
            endDateInclusive = LocalDate(2026, 11, 30),
            dayCount = 30,
        )

    /** The span every fixture carries beside its payload: two effective dates inside the month. */
    private fun span(
        earliest: String = "2026-11-03T02:00:00Z",
        latest: String = "2026-11-20T02:00:00Z",
    ): P806Span = P806Span(earliest = Instant.parse(earliest), latest = Instant.parse(latest))

    private fun currencyView(
        currency: CurrencyUnit = cny,
        income: Long = 5_000L,
        netExpense: Long = 3_500L,
        dailyAverage: Long? = 117L,
        count: Int = 4,
    ): P806CurrencyView =
        P806CurrencyView(
            currency = currency,
            ordinaryIncomeMinorUnits = income,
            netExpenseMinorUnits = netExpense,
            balanceMinorUnits = income - netExpense,
            positiveExpenseMinorUnits = if (netExpense >= 0L) netExpense else 0L,
            refundMinorUnits = if (netExpense >= 0L) 0L else netExpense,
            dailyAverageMinorUnits = dailyAverage,
            transactionCount = count,
        )

    private fun reportView(
        period: P806ResolvedPeriod? = monthPeriod(),
        currencies: List<P806CurrencyView> = listOf(currencyView()),
        expenseCategories: List<P806CategoryView> = emptyList(),
        incomeCategories: List<P806CategoryView> = emptyList(),
        uncategorizedExpense: List<P806CategoryCurrencyTotal> = emptyList(),
        uncategorizedIncome: List<P806CategoryCurrencyTotal> = emptyList(),
        transactionCount: Int = 4,
        futureCount: Int = 0,
        span: P806Span? = span(),
    ): P806ReportView =
        P806ReportView(
            ledgerId = ledgerId,
            period = period,
            currencies = currencies,
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            uncategorizedExpenseTotals = uncategorizedExpense,
            uncategorizedIncomeTotals = uncategorizedIncome,
            transactionCount = transactionCount,
            futureTransactionCount = futureCount,
            detailPage = P806TransactionPage(rows = emptyList(), nextCursor = null),
            detailCount = transactionCount,
            span = span,
        )

    private fun loaded(
        view: P806ReportView = reportView(),
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        asOf: Instant? = readInstant,
        kind: P806PeriodKind = period.kind,
        loading: Boolean = false,
    ): P806ReportRegionState =
        P806ReportRegionState(
            period = period,
            kind = kind,
            requestToken = 4L,
            loading = loading,
            landed = view,
            landedKind = period.kind,
            landedAsOf = asOf,
        )

    // ---------------------------------------------------------------- the six distinguishable states

    @Test
    fun theSixPayloadStatesStayDistinguishableAndAnUnwiredSurfaceRendersNothing() {
        assertEquals(P806ReportRegionFace.NOT_RENDERED, p806ReportRegionFace(P806ReportRegionState(unwired = true)))
        assertEquals(P806ReportRegionFace.NOT_LOADED, p806ReportRegionFace(P806ReportRegionState()))
        assertEquals(P806ReportRegionFace.LOADING, p806ReportRegionFace(P806ReportRegionState(loading = true, landed = reportView())))
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(P806ReportRegionState(failure = P806SnapshotFailure.Unavailable, landed = reportView())))
        assertEquals(P806ReportRegionFace.EMPTY, p806ReportRegionFace(loaded(reportView(transactionCount = 0, currencies = listOf(currencyView(income = 0L, netExpense = 0L, dailyAverage = 0L, count = 0))))))
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(loaded()))
        // A loaded report with no period at all is its own empty success: no period, no fake zero.
        assertEquals(P806ReportRegionFace.EMPTY, p806ReportRegionFace(loaded(reportView(period = null))))
        // A loading surface always reads as loading, even beside an old failure.
        assertEquals(
            P806ReportRegionFace.LOADING,
            p806ReportRegionFace(P806ReportRegionState(loading = true, failure = P806SnapshotFailure.InvalidWindow)),
        )
    }

    /**
     * N-2: after a determinate-write invalidation the last success is OLD data pending the owed
     * re-read — never READY, never a re-seed source — and the shared eligibility seam is the one
     * place that decides it. Only an accepted landing clears the mark, and a write-stale payload with
     * nothing in flight is a FAILED face with a retry, never a fabricated "loading".
     */
    @Test
    fun aWriteStalePayloadIsNeverReadyNorEligibleUntilANewLanding() {
        // The real invalidation shape: the owed re-read keeps a REAL pending marker over old data.
        val pending = loaded().copy(loading = true, writeStale = true)
        assertEquals(P806ReportRegionFace.LOADING, p806ReportRegionFace(pending))
        assertTrue(!p806ReportSlotIsEligible(pending))
        assertEquals("账目已更新：正在刷新报表，以下为更新前的旧数据。", p806ReportWriteStaleNotice())
        // The old success stays visible, labelled with its own context.
        assertNotNull(p806ReportLastSuccessContextText(pending))
        // With nothing in flight the same stale payload is a failure face (retry entry), not loading.
        val concluded = loaded().copy(writeStale = true)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(concluded))
        assertTrue(!p806ReportSlotIsEligible(concluded))
        // An eligible payload is the only READY source; a payload-less slot is never eligible.
        assertTrue(p806ReportSlotIsEligible(loaded()))
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(loaded()))
        assertTrue(!p806ReportSlotIsEligible(P806ReportRegionState(fenceToken = 9L)))
        // A pending read outranks a previous failure; a stale payload with no read is a failure too.
        assertEquals(P806ReportRegionFace.LOADING, p806ReportRegionFace(P806ReportRegionState(loading = true, failure = P806SnapshotFailure.Unavailable)))
        assertEquals(
            P806ReportRegionFace.LOADING,
            p806ReportRegionFace(P806ReportRegionState(writeStale = true, loading = true, failure = P806SnapshotFailure.Unavailable)),
        )
        // A landing that replaced the payload with a typed failure is never eligible either.
        assertTrue(!p806ReportSlotIsEligible(loaded().copy(failure = P806SnapshotFailure.Unavailable)))
        assertTrue(!p806ReportSlotIsEligible(loaded().copy(loading = true)))
    }

    @Test
    fun anEmptySuccessSaysWhichEmptyItIsAndNeverInventsAPeriod() {
        val noPeriod = p806ReportEmptyText(loaded(reportView(period = null)))
        val zeroPeriod = p806ReportEmptyText(loaded(reportView(transactionCount = 0)))
        assertEquals("该账本还没有可归属的统计期间：不呈现期间金额，日均不适用。", noPeriod)
        assertEquals("该期间没有普通收支贡献：以下零值是明确结果。", zeroPeriod)
        assertEquals(
            "自定义区间的起止日期无效：请检查日期后重试。",
            p806ReportFailureText(P806SnapshotFailure.InvalidWindow),
        )
        assertEquals(
            "报表读取不可用：请重试。",
            p806ReportFailureText(P806SnapshotFailure.Unavailable),
        )
        assertEquals(
            "目录引用不一致（缺账户 1、跨账本 0、币种失配 2）：报表不可用。",
            p806ReportFailureText(P806SnapshotFailure.CatalogReference(unresolvedAccount = 1L, crossLedgerAccount = 0L, currencyMismatch = 2L)),
        )
    }

    // ---------------------------------------------------------------- exact minor-unit display

    @Test
    fun theExactMinorUnitTextIsCorrectAtTheLongMinimumEnd() {
        assertEquals("30.00", p806MinorUnitsText(3_000L, 2))
        assertEquals("-30.00", p806MinorUnitsText(-3_000L, 2))
        assertEquals("0.00", p806MinorUnitsText(0L, 2))
        assertEquals("-0.05", p806MinorUnitsText(-5L, 2))
        assertEquals("30", p806MinorUnitsText(30L, 0))
        // |Long.MIN_VALUE| is not representable: the general formatter is off by one minor unit at
        // this end (V-17's d = 1 daily average or a legitimate aggregation can be this value), so
        // the report surface formats it from its exact decimal digits instead.
        assertEquals("-92233720368547758.08", p806MinorUnitsText(Long.MIN_VALUE, 2))
        assertEquals("-9223372036854775808", p806MinorUnitsText(Long.MIN_VALUE, 0))
        assertEquals("-922337203685477.5808", p806MinorUnitsText(Long.MIN_VALUE, 4))
        assertEquals("-92233720368547758.07", formatMinorUnits(Long.MIN_VALUE, 2))
    }

    // ---------------------------------------------------------------- the per-currency 2x2 (T2)

    @Test
    fun thePerCurrencyTwoByTwoKeepsSignsZerosAndNotApplicableApart() {
        val row = currencyView(income = 5_000L, netExpense = -1_200L, dailyAverage = 0L)
        assertEquals("净支出（含退款） -12.00 CNY", p806ReportNetExpenseText(row))
        assertEquals("普通收入 50.00 CNY", p806ReportIncomeText(row))
        assertEquals("结余 62.00 CNY", p806ReportBalanceText(row))
        // 0 has no minus sign, and a negative net expense is still shown with its sign.
        assertEquals("日均支出 0.00 CNY", p806ReportDailyAverageText(row))
        // A period without a natural day (empty ALL) is the word 不适用, never 0 and never an empty string.
        assertEquals("日均支出 不适用", p806ReportDailyAverageText(currencyView(dailyAverage = null)))
        // Currencies never merge: the same statistic in two currencies is two independent rows.
        assertEquals("净支出（含退款） -12.00 CNY", p806ReportNetExpenseText(currencyView(netExpense = -1_200L)))
        assertEquals("净支出（含退款） -12.00 USD", p806ReportNetExpenseText(currencyView(currency = usd, netExpense = -1_200L)))
        // The exact daily average at the Long.MIN_VALUE end keeps the exact digits.
        assertEquals("日均支出 -92233720368547758.08 CNY", p806ReportDailyAverageText(currencyView(dailyAverage = Long.MIN_VALUE)))
    }

    @Test
    fun theDailyAverageNoteIsTheFullNaturalDayDenominatorAndTheFutureMarkerIsAbsentAtZero() {
        assertEquals(
            "日均按该期间完整自然日数（30 天）计算：进行中的期间不缩短分母。",
            p806ReportDailyAverageDaysText(monthPeriod()),
        )
        assertNull(p806ReportDailyAverageDaysText(null))
        assertNull(p806ReportFutureMarkerText(0))
        assertNull(p806ReportFutureMarkerText(-1))
        assertEquals("含 3 笔未来统计日期的普通收支。", p806ReportFutureMarkerText(3))
    }

    // ---------------------------------------------------------------- old-context labelling and the two periods

    @Test
    fun theLastSuccessIsLabelledWithItsOwnPeriodAxisAndReadInstant() {
        val text =
            assertNotNull(
                p806ReportLastSuccessContextText(
                    P806ReportRegionState(
                        loading = true,
                        landed = reportView(currencies = listOf(currencyView(), currencyView(currency = usd))),
                        landedKind = P806PeriodKind.MONTH,
                        landedAsOf = readInstant,
                    ),
                ),
            )
        assertEquals(
            "最近成功（期间 月：2026-11-01 至 2026-11-30；币种轴 CNY、USD；读取时点 2026-11-18T02:00:00Z）",
            text,
        )
        // Nothing landed yet: no claim at all.
        assertNull(p806ReportLastSuccessContextText(P806ReportRegionState(loading = true)))
        // Without a period the label says so instead of naming dates it does not have.
        assertEquals(
            "最近成功（期间 无；币种轴 CNY；读取时点 不可用）",
            p806ReportLastSuccessContextText(
                P806ReportRegionState(
                    loading = true,
                    landed = reportView(period = null),
                    landedAsOf = null,
                ),
            ),
        )
        // The old payload's amounts are explicitly captioned as old data (R-6).
        assertEquals("以下为最近一次成功载荷（旧数据）：", p806ReportLastSuccessCaption())
        // The two periods on the analysis page are marked apart explicitly.
        assertEquals("报表期间独立于上方月卡与趋势的月份游标。", p806ReportIndependentPeriodNotice())
    }

    @Test
    fun theSelectionLineNeverWearsAnotherKindsDatesWhileReadingOrFailed() {
        // The user picked 年 while the only success is a 月 read: the selection line says 年 and
        // claims no dates, and the last success keeps its own 月 label and its own dates.
        val switched = loaded(kind = P806PeriodKind.YEAR, loading = true)
        assertEquals("年：读取中", p806ReportPeriodLabel(switched))
        assertEquals(
            "最近成功（期间 月：2026-11-01 至 2026-11-30；币种轴 CNY；读取时点 2026-11-18T02:00:00Z）",
            p806ReportLastSuccessContextText(switched),
        )
        // The same selection after the read failed keeps the same separation.
        val failed = switched.copy(loading = false, failure = P806SnapshotFailure.Unavailable)
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(failed))
        assertEquals("年：尚未取得解析区间", p806ReportPeriodLabel(failed))
        assertNotNull(p806ReportLastSuccessContextText(failed))
        // Once the 年 read landed, the selection line wears ITS dates.
        val yearPeriod =
            P806ResolvedPeriod(
                window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L),
                startDate = LocalDate(2026, 1, 1),
                endDateInclusive = LocalDate(2026, 12, 31),
                dayCount = 365,
            )
        val landedYear =
            loaded(view = reportView(period = yearPeriod), period = P806Period(P806PeriodKind.YEAR, year = 2026), kind = P806PeriodKind.YEAR)
        assertEquals("年：2026-01-01 至 2026-12-31（365 天）", p806ReportPeriodLabel(landedYear))
    }

    @Test
    fun aKnownZeroPeriodIsASuccessThatStillStatesItsFullDayCount() {
        val zero = loaded(reportView(transactionCount = 0, currencies = listOf(currencyView(income = 0L, netExpense = 0L, dailyAverage = 0L, count = 0))))
        assertEquals(P806ReportRegionFace.EMPTY, p806ReportRegionFace(zero))
        // The known-zero success names its period and its full natural days (R-6), and its currency
        // rows are TRUE zeros (the axis is explicit) rather than a fabricated empty.
        assertEquals("月：2026-11-01 至 2026-11-30（30 天）", p806ReportPeriodLabel(zero))
        assertEquals(
            "日均按该期间完整自然日数（30 天）计算：进行中的期间不缩短分母。",
            p806ReportDailyAverageDaysText(assertNotNull(zero.landed).period),
        )
        assertEquals("日均支出 0.00 CNY", p806ReportDailyAverageText(assertNotNull(zero.landed).currencies.single()))
    }

    @Test
    fun theCategoryNetStaysExactAtTheExtremeLegalComponents() {
        // The producing fold keeps positive >= 0 and refund <= 0, so the net can never overflow; the
        // two extremes below are the legal boundary, and the illegal pair is the only counterexample
        // (documented on the helper — no API is grown for it).
        assertEquals(
            "92233720368547758.07 CNY",
            p806ReportCategoryTotalsText(listOf(P806CategoryCurrencyTotal(cny, Long.MAX_VALUE, 0L))),
        )
        assertEquals(
            "-92233720368547758.08 CNY",
            p806ReportCategoryTotalsText(listOf(P806CategoryCurrencyTotal(cny, 0L, Long.MIN_VALUE))),
        )
        assertEquals(
            "0.00 CNY",
            p806ReportCategoryTotalsText(listOf(P806CategoryCurrencyTotal(cny, 100L, -100L))),
        )
    }

    @Test
    fun everyKindHasAReadableResolvedPeriodLabel() {
        val month = monthPeriod()
        assertEquals("月：2026-11-01 至 2026-11-30（30 天）", p806ReportResolvedPeriodText(month, P806PeriodKind.MONTH))
        assertEquals("年：2026-11-01 至 2026-11-30（30 天）", p806ReportResolvedPeriodText(month, P806PeriodKind.YEAR))
        assertEquals("全部：2026-11-01 至 2026-11-30（30 天）", p806ReportResolvedPeriodText(month, P806PeriodKind.ALL))
        assertEquals("自定义：2026-11-01 至 2026-11-30（30 天）", p806ReportResolvedPeriodText(month, P806PeriodKind.CUSTOM))
        assertEquals("周：2026-11-01 至 2026-11-30（30 天）", p806ReportResolvedPeriodText(month, P806PeriodKind.WEEK))
        assertEquals("周", p806ReportPeriodKindLabel(P806PeriodKind.WEEK))
        assertEquals("全部", p806ReportPeriodKindLabel(P806PeriodKind.ALL))
        // Before the first landing the label names the requested kind and claims no dates.
        assertEquals("月：尚未取得解析区间", p806ReportPeriodLabel(P806ReportRegionState()))
        assertEquals("月：2026-11-01 至 2026-11-30（30 天）", p806ReportPeriodLabel(loaded()))
    }

    // ---------------------------------------------------------------- categories (no drilldown entry here)

    @Test
    fun theCategoryRowsCarryTheExactTwoLevelValuesAndAnExplicitUncategorizedRow() {
        val breakfast = P806CategoryView(CategoryId("expense-food-breakfast"), "早餐", listOf(P806CategoryCurrencyTotal(cny, 800L, 0L)), emptyList())
        val dinner =
            P806CategoryView(
                CategoryId("expense-food-dinner"),
                "晚餐",
                listOf(P806CategoryCurrencyTotal(cny, 1_200L, -200L), P806CategoryCurrencyTotal(usd, 500L, 0L)),
                emptyList(),
            )
        val food = P806CategoryView(CategoryId("expense-food"), "餐饮", listOf(P806CategoryCurrencyTotal(cny, 2_000L, -200L)), listOf(breakfast, dinner))
        // A level-1 row carries its own total (which equals its level-2 sum by the fold's contract).
        assertEquals("餐饮：18.00 CNY", p806ReportCategoryRowText(food))
        assertEquals("早餐：8.00 CNY", p806ReportCategoryRowText(breakfast))
        // Two currencies never merge into one number.
        assertEquals("晚餐：10.00 CNY，5.00 USD", p806ReportCategoryRowText(dinner))
        assertEquals("一级分类 餐饮，合计 18.00 CNY", p806ReportCategoryAccessibilityText(food, level = 1))
        assertEquals("二级分类 早餐，合计 8.00 CNY", p806ReportCategoryAccessibilityText(breakfast, level = 2))
        // The explicit 无分类 row is its own label, never invented as a category.
        assertEquals("无分类", p806ReportUncategorizedLabel())
        assertEquals(
            "无分类：5.00 CNY",
            p806ReportUncategorizedRowText(listOf(P806CategoryCurrencyTotal(cny, 500L, 0L))),
        )
        // An empty carry renders nothing (no empty row with a fake zero).
        assertNull(p806ReportUncategorizedRowText(emptyList()))
    }

    /**
     * D-2: a level-1 row expands and collapses its OWN level-2 children (D-343 section 3.9.1). The
     * section starts fully collapsed, one tap opens exactly that row (revealing its children), a
     * second tap closes it again, and two level-1 rows of the same section never affect each other.
     * The interactive row announces the action with the row's own name; the level-1 total and the
     * accessibility texts are unchanged by the expansion.
     */
    @Test
    fun aLevelOneRowExpandsAndCollapsesItsOwnChildrenAndNoSiblings() {
        val foodId = CategoryId("anonymous-expand-parent-one")
        val transportId = CategoryId("anonymous-expand-parent-two")
        val breakfast =
            P806CategoryView(
                CategoryId("anonymous-expand-child-one"),
                "早餐",
                listOf(P806CategoryCurrencyTotal(cny, 800L, 0L)),
                emptyList(),
            )
        val food = P806CategoryView(foodId, "餐饮", listOf(P806CategoryCurrencyTotal(cny, 800L, 0L)), listOf(breakfast))
        val transport = P806CategoryView(transportId, "交通", listOf(P806CategoryCurrencyTotal(cny, 300L, 0L)), emptyList())
        // Initial and empty selection: every level-1 row starts collapsed, so no level-2 row renders.
        val initial = p806ReportCategoryInitialExpansion()
        assertTrue(initial.isEmpty())
        assertTrue(!p806ReportCategoryIsExpanded(initial, foodId))
        assertEquals("▸", p806ReportCategoryExpansionMarker(false))
        assertTrue(p806ReportCategoryVisibleChildren(food, expanded = false).isEmpty())
        assertTrue(p806ReportCategoryVisibleChildren(transport, expanded = false).isEmpty())
        // One tap expands exactly that row: its children render, the row keeps its own total.
        val opened = p806ReportCategoryToggled(initial, foodId)
        assertTrue(p806ReportCategoryIsExpanded(opened, foodId))
        assertTrue(!p806ReportCategoryIsExpanded(opened, transportId))
        assertEquals("▾", p806ReportCategoryExpansionMarker(true))
        assertEquals(listOf(breakfast), p806ReportCategoryVisibleChildren(food, expanded = true))
        assertEquals("餐饮：8.00 CNY", p806ReportCategoryRowText(food))
        // A second tap collapses it again, back to the initial state.
        val closed = p806ReportCategoryToggled(opened, foodId)
        assertTrue(!p806ReportCategoryIsExpanded(closed, foodId))
        assertTrue(closed.isEmpty())
        // Two level-1 rows of the same section expand independently in both directions.
        val both = p806ReportCategoryToggled(opened, transportId)
        assertTrue(p806ReportCategoryIsExpanded(both, foodId))
        assertTrue(p806ReportCategoryIsExpanded(both, transportId))
        val foodOnly = p806ReportCategoryToggled(both, foodId)
        assertTrue(!p806ReportCategoryIsExpanded(foodOnly, foodId))
        assertTrue(p806ReportCategoryIsExpanded(foodOnly, transportId))
        // A level-1 row without children reveals nothing even while expanded: no invented row.
        assertTrue(p806ReportCategoryVisibleChildren(transport, expanded = true).isEmpty())
        // The click label carries the action and the row's own current name.
        assertEquals("展开或收起餐饮", p806ReportCategoryExpandActionLabel("餐饮"))
        assertEquals("展开或收起交通", p806ReportCategoryExpandActionLabel(transport.categoryName))
    }

    // ---------------------------------------------------------------- period navigation (host-side, pure)

    @Test
    fun theWeekMonthAndYearCursorsStepFromTheLandedAnchorAndAllAndCustomNeverStep() {
        // A snapshot whose span covers 2019–2030, so every step below stays inside its domain.
        val wide = reportView(span = span(earliest = "2019-01-01T02:00:00Z", latest = "2030-12-31T02:00:00Z"))
        // Before the first landing there is no anchor (and no span): no step is offered.
        assertNull(p806ReportShiftedPeriod(P806ReportRegionState(), offset = 1))
        val anchoredMonth = p806ReportAnchoredPeriod(P806Period(P806PeriodKind.MONTH), reportView())
        assertEquals(YearMonth(2026, 11), anchoredMonth.month)
        assertEquals(
            P806Period(P806PeriodKind.MONTH, month = YearMonth(2026, 12)),
            p806ReportShiftedPeriod(loaded(view = wide, period = anchoredMonth), offset = 1),
        )
        assertEquals(
            P806Period(P806PeriodKind.MONTH, month = YearMonth(2026, 10)),
            p806ReportShiftedPeriod(loaded(view = wide, period = anchoredMonth), offset = -1),
        )
        // A past and a future month are both reachable: nothing here is clamped to 本月.
        assertEquals(
            P806Period(P806PeriodKind.MONTH, month = YearMonth(2030, 1)),
            p806ReportShiftedPeriod(loaded(view = wide, period = P806Period(P806PeriodKind.MONTH, month = YearMonth(2029, 12))), offset = 1),
        )
        // The week cursor keeps an explicit Monday-anchored date; the year cursor steps by year.
        val weekPeriod =
            P806ResolvedPeriod(
                window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L),
                startDate = LocalDate(2026, 11, 2),
                endDateInclusive = LocalDate(2026, 11, 8),
                dayCount = 7,
            )
        val anchoredWeek = p806ReportAnchoredPeriod(P806Period(P806PeriodKind.WEEK), reportView(period = weekPeriod, span = wide.span))
        assertEquals(LocalDate(2026, 11, 2), anchoredWeek.weekStart)
        assertEquals(
            P806Period(P806PeriodKind.WEEK, weekStart = LocalDate(2026, 11, 9)),
            p806ReportShiftedPeriod(loaded(view = reportView(period = weekPeriod, span = wide.span), period = anchoredWeek), offset = 1),
        )
        val year2026 =
            P806ResolvedPeriod(
                window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L),
                startDate = LocalDate(2026, 1, 1),
                endDateInclusive = LocalDate(2026, 12, 31),
                dayCount = 365,
            )
        assertEquals(
            P806Period(P806PeriodKind.YEAR, year = 2027),
            p806ReportShiftedPeriod(loaded(view = reportView(period = year2026, span = wide.span), period = P806Period(P806PeriodKind.YEAR, year = 2026)), offset = 1),
        )
        assertNull(p806ReportShiftedPeriod(loaded(view = wide, period = P806Period(P806PeriodKind.ALL)), offset = 1))
        assertNull(p806ReportShiftedPeriod(loaded(view = wide), offset = 0))
    }

    @Test
    fun theSteppingIsClosedOverTheSnapshotDomainAndNeverLeavesIt() {
        // The span's dates are 2020-01-10 … 2020-03-20, so the MONTH domain is 2020-01 … 2020-03.
        val bounded =
            reportView(
                period =
                    P806ResolvedPeriod(
                        window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L),
                        startDate = LocalDate(2020, 2, 1),
                        endDateInclusive = LocalDate(2020, 2, 29),
                        dayCount = 29,
                    ),
                span = span(earliest = "2020-01-10T02:00:00Z", latest = "2020-03-20T02:00:00Z"),
            )
        val domain = assertNotNull(p806ReportReachableDomain(bounded, P806PeriodKind.MONTH))
        assertEquals(LocalDate(2020, 1, 1), domain.firstStart)
        assertEquals(LocalDate(2020, 3, 31), domain.lastEnd)
        val february = loaded(view = bounded, period = P806Period(P806PeriodKind.MONTH, month = YearMonth(2020, 2)))
        assertEquals(P806Period(P806PeriodKind.MONTH, month = YearMonth(2020, 1)), p806ReportShiftedPeriod(february, offset = -1))
        assertEquals(P806Period(P806PeriodKind.MONTH, month = YearMonth(2020, 3)), p806ReportShiftedPeriod(february, offset = 1))
        // One more step outward is refused: the affordance never offers a period the domain excludes.
        val march = loaded(view = bounded, period = P806Period(P806PeriodKind.MONTH, month = YearMonth(2020, 3)))
        assertNull(p806ReportShiftedPeriod(march, offset = 1))
        // From a default 本月 OUTSIDE the covered domain a step toward it is allowed, outward is not.
        val outside = loaded(view = bounded, period = P806Period(P806PeriodKind.MONTH, month = YearMonth(2026, 11)))
        assertEquals(P806Period(P806PeriodKind.MONTH, month = YearMonth(2026, 10)), p806ReportShiftedPeriod(outside, offset = -1))
        assertNull(p806ReportShiftedPeriod(outside, offset = 1))
        // No period in the snapshot (无期间), no span, a pending read or another kind: no step at all.
        assertNull(p806ReportShiftedPeriod(loaded(view = reportView(period = null, span = null)), offset = 1))
        assertNull(p806ReportShiftedPeriod(loaded(view = reportView(span = null)), offset = 1))
        assertNull(p806ReportShiftedPeriod(loaded(view = bounded, loading = true), offset = 1))
        assertNull(p806ReportShiftedPeriod(loaded(view = bounded, kind = P806PeriodKind.YEAR), offset = 1))
    }

    @Test
    fun aKindChipKeepsTheAnchoredPeriodItAlreadyReadAndOtherwiseStartsRelative() {
        val anchored = P806Period(P806PeriodKind.MONTH, month = YearMonth(2026, 11))
        val loadedSlot = loaded(period = anchored)
        // Re-tapping the kind that was read keeps exactly that period (the request is then absorbed).
        assertEquals(anchored, p806ReportKindPeriod(loadedSlot, P806PeriodKind.MONTH))
        // Another kind starts its own relative definition, which the application resolves.
        assertEquals(P806Period(P806PeriodKind.YEAR), p806ReportKindPeriod(loadedSlot, P806PeriodKind.YEAR))
        assertEquals(P806Period(P806PeriodKind.ALL), p806ReportKindPeriod(loadedSlot, P806PeriodKind.ALL))
    }

    @Test
    fun theCustomRangeIsParsedExactlyAndAnUnparseableTextStaysUnparseable() {
        val parsed = p806CustomPeriod("2026-11-01", " 2026-11-16 ")
        assertEquals(P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 1), customEnd = LocalDate(2026, 11, 16)), parsed)
        // An unparseable side stays null: the application answers the typed InvalidWindow, and no
        // silently invented neighbouring date is ever sent.
        assertEquals(P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 1)), p806CustomPeriod("2026-11-01", "not-a-date"))
        assertEquals(P806Period(P806PeriodKind.CUSTOM), p806CustomPeriod("", ""))
        // A reversed range is passed through untouched: the typed rejection belongs to the read, not
        // to a host-side date rewrite.
        assertEquals(
            P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 16), customEnd = LocalDate(2026, 11, 1)),
            p806CustomPeriod("2026-11-16", "2026-11-01"),
        )
    }

    @Test
    fun theTransactionCountLineCountsOrdinaryContributionsOnly() {
        assertEquals("普通收支笔数：4", p806ReportTransactionCountText(reportView()))
        assertEquals("普通收支笔数：0", p806ReportTransactionCountText(reportView(transactionCount = 0, currencies = emptyList())))
    }

    // ---- P8-06 batch 3 (D-362 1/2; spec section 3.9): the drilldown entry's shared eligibility seam ----

    @Test
    fun theDrilldownEntryReusesTheSlotEligibilitySeamAndRequiresAPeriodWindow() {
        // READY non-empty with a period + wired capability: the entry is offered.
        assertTrue(p806ReportDrilldownEntryVisible(loaded(), wired = true))
        // An EMPTY zero-contribution report with a period still offers the period entry (the
        // category entries are withheld by the screen: no category row exists).
        assertTrue(
            p806ReportDrilldownEntryVisible(
                loaded(reportView(transactionCount = 0, currencies = listOf(currencyView(income = 0L, netExpense = 0L, dailyAverage = 0L, count = 0)))),
                wired = true,
            ),
        )
        // The same eligibility seam the host's re-seed and the landing decision share: a pending
        // read, a typed failure and a write-stale payload each withhold the entry.
        assertTrue(!p806ReportDrilldownEntryVisible(loaded(loading = true), wired = true))
        assertTrue(!p806ReportDrilldownEntryVisible(loaded().copy(failure = P806SnapshotFailure.Unavailable), wired = true))
        assertTrue(!p806ReportDrilldownEntryVisible(loaded().copy(writeStale = true), wired = true))
        // Unwired / no slot / no period (empty ledger or ALL without a span): no entry, no zero.
        assertTrue(!p806ReportDrilldownEntryVisible(loaded(), wired = false))
        assertTrue(!p806ReportDrilldownEntryVisible(null, wired = true))
        assertTrue(!p806ReportDrilldownEntryVisible(P806ReportRegionState(unwired = true), wired = true))
        assertTrue(!p806ReportDrilldownEntryVisible(loaded(reportView(period = null)), wired = true))
    }

    @Test
    fun theLevelOneExpansionInteractionIsUnchangedByTheEntryAddition() {
        // Tapping a level-1 row still only toggles its own expansion state; the row keeps its
        // 「展开或收起<name>」 click label and the new entry is a separate child button.
        val categoryId = CategoryId("category-food")
        assertEquals(emptySet(), p806ReportCategoryInitialExpansion())
        assertEquals(setOf(categoryId), p806ReportCategoryToggled(p806ReportCategoryInitialExpansion(), categoryId))
        assertEquals(emptySet(), p806ReportCategoryToggled(setOf(categoryId), categoryId))
        assertEquals("展开或收起餐饮", p806ReportCategoryExpandActionLabel("餐饮"))
    }

    @Test
    fun theDrilldownEntryClickLabelsNameTheirRow() {
        // The child 全部明细 entry and the level-2 row carry the SAME frozen strings as their
        // click-action labels (P8-06 batch 3 §2.5); the composable wires them through
        // `clickable(onClickLabel = …)`, so these pure helpers are the single string source.
        assertEquals("查看餐饮全部明细", p806ReportCategoryAllDetailActionLabel("餐饮"))
        assertEquals("查看餐饮明细", p806ReportCategoryDetailActionLabel("餐饮"))
    }
}
