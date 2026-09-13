package com.unifiedledger.ui

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

/** The copy of one month-region state; the three failure/empty/awaiting texts stay distinct. */
internal fun monthRegionHeadline(
    state: MonthlyRegionState,
    monthLabel: String,
    transactionCount: Int,
): String =
    when (state) {
        // Distinct from 该月无交易 (EMPTY_MONTH) and from the failure banner (R-Q06-4, spec 6.4).
        MonthlyRegionState.NOT_LOADED -> "月度数据未加载——请重新选择月份。"
        MonthlyRegionState.AWAITING -> "暂无月度数据。"
        MonthlyRegionState.EMPTY_MONTH -> "$monthLabel：该月无交易。"
        MonthlyRegionState.LOADED -> "$monthLabel · 交易 $transactionCount 笔"
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
            // countByKind is per currency; a transaction belongs to one currency, so the
            // per-kind count is the maximum across the month's currency rows.
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
