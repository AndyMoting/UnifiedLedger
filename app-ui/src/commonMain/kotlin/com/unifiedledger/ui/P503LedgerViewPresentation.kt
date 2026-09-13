package com.unifiedledger.ui

import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyTrend
import com.unifiedledger.application.MonthlyTrendResult
import com.unifiedledger.application.OrdinaryFlowClassification
import com.unifiedledger.application.SelectableMonthsResult
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.YearMonth

// P7-03.C/D pure presentation decisions (D-145; spec sections 4.3/6.4, R-Q06-4, R-Q07-3, C04).
// The composables stay thin renderers; every load-bearing decision — which month-region state
// applies, which categories may draw a pie sector, whether a chart is accompanied by the exact
// value table, how special kinds are disclosed (R-5), and how the monthly cycle folds its typed
// sub-read results — is a pure function here, so it is assertable in app-ui's JVM tests without
// a Compose UI-test harness. No Compose or platform API is used in this file.

/**
 * P7-03.C/D (F2): which of the four mutually exclusive month-region presentations applies.
 *
 * [AWAITING] is the pre-first-payload state (月度数据尚未到达); [NOT_LOADED] is the explicit
 * re-select affordance the user lands on when the monthly payload was never (re)requested —
 * notably after a successful READ retry, because `RetryRefresh` is deliberately outside the
 * frozen re-request trigger set (spec 6.2 residual boundary (a)). Neither state is allowed to
 * read like the empty month [EMPTY_MONTH] and neither may be rendered as zeros (R-Q06-4).
 */
internal enum class MonthlyRegionState {
    NOT_LOADED,
    AWAITING,
    EMPTY_MONTH,
    LOADED,
}

/**
 * The month-region state of the current overview. [reloadRequired] wins: the reducer sets it when
 * a monthly failure was recovered by the READ retry, so the payload on screen is known to be
 * absent even though a month cursor and a selectable domain survive.
 */
internal fun monthlyRegionState(
    activity: MonthlyActivity?,
    reloadRequired: Boolean,
): MonthlyRegionState =
    when {
        reloadRequired -> MonthlyRegionState.NOT_LOADED
        activity == null -> MonthlyRegionState.AWAITING
        activity.currencies.isEmpty() || activity.currencies.all { it.transactionCount == 0 } -> MonthlyRegionState.EMPTY_MONTH
        else -> MonthlyRegionState.LOADED
    }

/**
 * The copy of one month-region state; the three failure/empty/awaiting texts stay distinct.
 *
 * [reloadPossible] is `false` when no re-select action can be offered (neither the selected month
 * nor 本月 could be resolved, e.g. a clock failure on the failing cycle): the copy then degrades
 * instead of instructing an action the UI does not provide (G4).
 */
internal fun monthRegionHeadline(
    state: MonthlyRegionState,
    monthLabel: String,
    transactionCount: Int,
    reloadPossible: Boolean = true,
): String =
    when (state) {
        // Distinct from 该月无交易 (EMPTY_MONTH) and from the failure banner (R-Q06-4, spec 6.4).
        MonthlyRegionState.NOT_LOADED ->
            if (reloadPossible) {
                "月度数据未加载——请重新选择月份。"
            } else {
                "月度数据未加载，且当前无法重新选择月份。"
            }
        MonthlyRegionState.AWAITING -> "暂无月度数据。"
        MonthlyRegionState.EMPTY_MONTH -> "$monthLabel：该月无交易。"
        MonthlyRegionState.LOADED -> "$monthLabel · 交易 $transactionCount 笔"
    }

/**
 * G1: which of the three trend-region presentations applies. [NOT_LOADED] means the monthly cycle
 * has not been (re)loaded for the current overview, so the previous cycle's twelve-month table
 * must not be presented as current; [AWAITING] is the pre-first-payload placeholder.
 */
internal enum class TrendRegionState {
    NOT_LOADED,
    AWAITING,
    LOADED,
}

internal fun trendRegionState(
    trend: MonthlyTrend?,
    reloadRequired: Boolean,
): TrendRegionState =
    when {
        reloadRequired -> TrendRegionState.NOT_LOADED
        trend == null -> TrendRegionState.AWAITING
        else -> TrendRegionState.LOADED
    }

/**
 * The trend-region placeholder, or `null` when the loaded twelve-month table applies. G1: the
 * [TrendRegionState.NOT_LOADED] copy is the same class of treatment as the month card and is
 * deliberately different from 暂无趋势数据, which would pass a stale cycle off as an empty trend
 * (R-Q06-4).
 */
internal fun trendRegionPlaceholderText(state: TrendRegionState): String? =
    when (state) {
        TrendRegionState.NOT_LOADED -> "月度数据未加载——趋势需重新选择月份后加载。"
        TrendRegionState.AWAITING -> "暂无趋势数据。"
        TrendRegionState.LOADED -> null
    }

/**
 * The category-region placeholder, or `null` when the region renders its rows. G1: an un-reloaded
 * cycle must not read as "this month has no categories"; a genuinely empty month keeps today's
 * rendering (the region is omitted and the month card says 该月无交易).
 */
internal fun categoryRegionPlaceholderText(reloadRequired: Boolean): String? = if (reloadRequired) "月度数据未加载——分类合计需重新选择月份后加载。" else null

/**
 * G1: the flow list HOME actually renders. A not-loaded monthly cycle must not present the
 * previous cycle's rows as the current list (R-Q06-4), so the fresh authoritative
 * `state.transactions` projection is rendered instead; otherwise the display-ordered entry rows
 * are used when the host provides them.
 */
internal fun flowRowsForDisplay(
    entryRows: List<LedgerEntryRow>?,
    reloadRequired: Boolean,
): List<LedgerEntryRow>? = if (reloadRequired) null else entryRows

/**
 * G3: whether one `AnalysisMonthShift` evaluation actually moved the shared cursor, i.e. whether
 * trigger (c) must re-request the monthly payload. An absorbed shift (out of the frozen SelectMonth
 * domain, no resolvable base month) leaves the cursor unchanged and must not fire a wasted read.
 * Both sides must be the same overview shape — the reducer never turns a non-overview state into an
 * overview on a shift — so anything else returns `null` (no re-request).
 */
internal fun analysisMonthShiftReRequest(
    before: P503AppState?,
    after: P503AppState?,
): P503AppState.OverviewEmpty? {
    val beforeOverview = before as? P503AppState.OverviewEmpty ?: return null
    val afterOverview = after as? P503AppState.OverviewEmpty ?: return null
    return if (afterOverview.selectedMonth != beforeOverview.selectedMonth) afterOverview else null
}

/** G3: the frozen-domain month-stepper edges shared by the month selector and the trend arrows. */
internal data class MonthStepEdges(
    val previous: YearMonth?,
    val next: YearMonth?,
)

/**
 * G3: the same admission rule the month selector uses (P703SPEC-10). A `null` edge means the step
 * in that direction would be absorbed by the reducer, so the affordance is disabled and no
 * re-request is fired.
 */
internal fun monthStepEdges(
    selectedMonth: YearMonth?,
    resolvedCurrentMonth: YearMonth?,
    selectableMonths: List<YearMonth>,
): MonthStepEdges {
    val effectiveMonth = selectedMonth ?: resolvedCurrentMonth
    return MonthStepEdges(
        previous = effectiveMonth?.let { current -> selectableMonths.firstOrNull { it < current } },
        next = effectiveMonth?.let { current -> selectableMonths.lastOrNull { it > current } },
    )
}

/**
 * G2: the retained READ-failure banner copy. It must never over-claim a loaded month: when the
 * retained overview has no successful monthly payload (an initial trigger-(a) failure), the banner
 * says the monthly data has not been loaded rather than promising a previously loaded month.
 */
internal fun retainedReadFailureBannerText(state: MonthlyRegionState): String =
    when (state) {
        MonthlyRegionState.LOADED,
        MonthlyRegionState.EMPTY_MONTH,
        -> "月度数据读取失败，以下为上一次成功加载的月份。"
        MonthlyRegionState.NOT_LOADED,
        MonthlyRegionState.AWAITING,
        -> "月度数据读取失败，本月的月度数据尚未加载。"
    }

/** One chart sector: the exact category total it came from and its positive net contribution. */
internal data class CategoryChartSector(
    val total: MonthlyCategoryCurrencyTotal,
    val netMinorUnits: Long,
)

/** The signed net contribution of one category/currency total, or `null` when it is not positive. */
internal fun positiveNetMinorUnits(total: MonthlyCategoryCurrencyTotal): Long? {
    val net = total.positiveMinorUnits + total.refundMinorUnits
    return if (net > 0L) net else null
}

/**
 * R-Q07-3 sector eligibility: a category draws a sector only when its signed net contribution is
 * positive; zero and net-negative categories never do. Currencies are never mixed (D-120), and a
 * currency whose eligible contributions do not sum to a positive value draws nothing at all.
 */
internal fun categoryChartSectors(
    categories: List<MonthlyCategoryTotal>,
    withPie: Boolean,
): Map<CurrencyUnit, List<CategoryChartSector>> {
    if (!withPie || categories.isEmpty()) return emptyMap()
    return categories
        .flatMap { it.totals }
        .groupBy { it.currency }
        .mapNotNull { (currency, totals) ->
            val sectors =
                totals.mapNotNull { total ->
                    positiveNetMinorUnits(total)?.let { net -> CategoryChartSector(total, net) }
                }
            if (sectors.sumOf { it.netMinorUnits } <= 0L) null else currency to sectors
        }.toMap()
}

/**
 * The chart + exact-table pairing of one category region (R-Q07-3/C04): the graphic is a
 * proportion aid only and must never replace the exact values.
 */
internal data class CategoryChartPresentation(
    val sectors: Map<CurrencyUnit, List<CategoryChartSector>>,
    val exactTableRows: List<MonthlyCategoryTotal>,
) {
    val rendersChart: Boolean get() = sectors.isNotEmpty()

    val rendersExactTable: Boolean get() = exactTableRows.isNotEmpty()

    /** Frozen invariant R-Q07-3: whenever a chart is drawn, the exact value table is present. */
    val chartAccompaniedByExactTable: Boolean get() = !rendersChart || rendersExactTable
}

/** The presentation decision of one category region; the exact rows are always the rendered list. */
internal fun categoryChartPresentation(
    withPie: Boolean,
    categories: List<MonthlyCategoryTotal>,
): CategoryChartPresentation =
    CategoryChartPresentation(
        sectors = categoryChartSectors(categories, withPie),
        exactTableRows = categories,
    )

/**
 * The exact per-currency values of one category node or of the 无分类 row (R-Q07-3: signs are
 * preserved, never masked with absolute values).
 */
internal fun categoryTotalsText(totals: List<MonthlyCategoryCurrencyTotal>): String =
    if (totals.isEmpty()) {
        "该分类本月无金额"
    } else {
        totals.joinToString("；") { total ->
            "${total.currency.code} 正向 ${formatMinorUnits(total.positiveMinorUnits, total.currency.precision)}" +
                "，退款 ${formatMinorUnits(total.refundMinorUnits, total.currency.precision)}"
        }
    }

/**
 * R-5 mitigation (spec section 8): special kinds stay out of the ordinary income/expense rows, so
 * a ledger that has them shows monthly ordinary totals that cannot equal the whole-period
 * `SummarizeLedgerActivity` figures. The category region discloses them as a single count line —
 * counts only, never mixed into an ordinary row — so the divergence is explained instead of
 * silently presented. `null` when the month has no special-kind transaction.
 */
internal fun specialKindDisclosure(activity: MonthlyActivity?): String? {
    if (activity == null) return null
    val counts =
        TransactionKind.entries
            .filterNot { kind -> OrdinaryFlowClassification.isOrdinary(kind) }
            // G5: countByKind is a single month-level map that MonthlyBuckets copies onto every
            // currency row of that month, so any row carries the same per-kind count. Taking the
            // maximum across rows is therefore equal to any row's value today and only guards the
            // disclosure if the projection ever becomes genuinely per-currency.
            .mapNotNull { kind ->
                val count = activity.currencies.maxOfOrNull { it.countByKind[kind] ?: 0 } ?: 0
                if (count > 0) "$kind $count 笔" else null
            }
    if (counts.isEmpty()) return null
    return "特殊科目（不计入普通收支）：${counts.joinToString("、")}"
}

/**
 * F1 (spec 4.3/C04): the retained overview a READ failure must keep on screen behind its explicit
 * failure banner, or `null` when the bare recoverable failure page applies — every failure path
 * without a retained monthly overview (all pre-P7-03 READ failures) and every SUBMISSION failure.
 */
internal fun retainedReadFailureOverview(failure: P503AppState.InfrastructureFailure): P503AppState.OverviewEmpty? = failure.monthlyOverview

/** One fully successful monthly cycle (F3): payload, SelectMonth domain and trend read together. */
internal sealed interface MonthlyCycleOutcome {
    data class Ready(
        val activity: MonthlyActivity,
        val selectableMonths: List<YearMonth>,
        val trend: MonthlyTrend,
    ) : MonthlyCycleOutcome

    /** Typed read failure of the cycle; `Unavailable`/`InvalidState` never render as zeros (R-Q06-4). */
    data class Failed(
        val result: MonthlyActivityResult,
    ) : MonthlyCycleOutcome
}

/**
 * F3 (R-Q06-4, spec 4.3): the month payload, the SelectMonth domain and the trend are one typed
 * cycle. A failure in any of them surfaces as the same typed failure family — the month result
 * first (most specific), then the domain, then the trend — instead of a disabled selector or a
 * 暂无趋势数据 placeholder with no indication. Only a fully successful cycle yields [Ready].
 */
internal fun foldMonthlyCycle(
    monthResult: MonthlyActivityResult,
    selectableMonthsResult: SelectableMonthsResult,
    trendResult: MonthlyTrendResult,
): MonthlyCycleOutcome {
    if (monthResult !is MonthlyActivityResult.Success) return MonthlyCycleOutcome.Failed(monthResult)
    if (selectableMonthsResult !is SelectableMonthsResult.Success) {
        return MonthlyCycleOutcome.Failed(MonthlyActivityResult.Unavailable)
    }
    if (trendResult !is MonthlyTrendResult.Success) {
        return MonthlyCycleOutcome.Failed(
            if (trendResult is MonthlyTrendResult.InvalidState) {
                MonthlyActivityResult.InvalidState
            } else {
                MonthlyActivityResult.Unavailable
            },
        )
    }
    return MonthlyCycleOutcome.Ready(
        activity = monthResult.activity,
        selectableMonths = selectableMonthsResult.months,
        trend = trendResult.trend,
    )
}
