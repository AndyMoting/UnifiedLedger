package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CategoryShareFailure
import com.unifiedledger.application.CategoryShareInput
import com.unifiedledger.application.CategoryShareProjection
import com.unifiedledger.application.CategoryShareRow
import com.unifiedledger.application.HomeAmountLine
import com.unifiedledger.application.HomeAmountProjection
import com.unifiedledger.application.HomeRowProjection
import com.unifiedledger.application.HomeTitleSource
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.MonthlyTrend
import com.unifiedledger.application.MonthlyTrendResult
import com.unifiedledger.application.OrdinaryFlowClassification
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P806CalendarCurrencyView
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.PresentationCategory
import com.unifiedledger.application.PresentationTime
import com.unifiedledger.application.PresentationTimeRole
import com.unifiedledger.application.PresentationTimes
import com.unifiedledger.application.PresentationValue
import com.unifiedledger.application.SelectableMonthsResult
import com.unifiedledger.application.TransactionAnnotationHistoryEntry
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailLeg
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.TransactionReconciliationLeg
import com.unifiedledger.application.TransactionVersionHistoryResult
import com.unifiedledger.application.occurredPresentationTime
import com.unifiedledger.application.presentationAmountText
import com.unifiedledger.application.presentationCategoryTintSlot
import com.unifiedledger.application.presentationTimes
import com.unifiedledger.application.presentationValueText
import com.unifiedledger.application.projectCategoryShares
import com.unifiedledger.application.projectHomeRow
import com.unifiedledger.application.statisticsPresentationTime
import com.unifiedledger.application.transactionKindBusinessName
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersion
import kotlinx.datetime.YearMonth
import kotlin.time.Instant

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
    flowRows: FlowRowsView?,
    reloadRequired: Boolean,
): FlowRowsView? = if (reloadRequired) null else flowRows

/** DEF-B (D-268): what the flow-list footer renders for the current page state. */
enum class FlowFooterState {
    LOADING,
    RETRY,
    FETCH_MORE,
    END,
}

/**
 * DEF-B (D-268): the footer decision, kept pure so the scroll-end trigger and the retry copy are
 * testable without a Compose runtime. [FlowFooterState.FETCH_MORE] is the scroll-end latch: the
 * footer composes only when the user reaches the end of the loaded rows.
 */
internal fun flowFooterState(view: FlowRowsView): FlowFooterState =
    when {
        view.loadingMore -> FlowFooterState.LOADING
        view.loadFailed -> FlowFooterState.RETRY
        view.nextCursor != null -> FlowFooterState.FETCH_MORE
        else -> FlowFooterState.END
    }

/**
 * DEF-B (D-268): the HOME flow list's page state.
 *
 * `null` at the screen means the monthly cycle is not loaded, so the bounded
 * [com.unifiedledger.application.LedgerCurrentState.transactions] fallback renders instead (G1).
 * A non-null value always carries the accumulated pages in page-key order, the exact total, the
 * next cursor, and the single-flight/failure markers; the screen re-sorts the accumulated pages
 * with `sortLedgerEntryRowsForDisplay`, so P703SPEC-03's display order stays frozen.
 */
data class FlowRowsView(
    val rows: List<LedgerEntryRow> = emptyList(),
    val total: Int = 0,
    val nextCursor: com.unifiedledger.application.LedgerCurrentStateReadPort.LedgerFlowCursor? = null,
    val loadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val generation: Int = 0,
)

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

/** One chart sector: the exact category total it came from, its category name and positive net contribution. */
internal data class CategoryChartSector(
    val total: MonthlyCategoryCurrencyTotal,
    val netMinorUnits: Long,
    val categoryName: String,
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
 * Each sector carries its category name so the chart's accessibility label can enumerate the
 * exact per-category values (C04: exact values, never precisionless percentages).
 */
internal fun categoryChartSectors(
    categories: List<MonthlyCategoryTotal>,
    withPie: Boolean,
): Map<CurrencyUnit, List<CategoryChartSector>> {
    if (!withPie || categories.isEmpty()) return emptyMap()
    return categories
        .flatMap { category -> category.totals.map { total -> category.categoryName to total } }
        .groupBy { it.second.currency }
        .mapNotNull { (currency, namedTotals) ->
            val sectors =
                namedTotals.mapNotNull { (categoryName, total) ->
                    positiveNetMinorUnits(total)?.let { net -> CategoryChartSector(total, net, categoryName) }
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

// ---------------------------------------------------------------- TalkBack labels (C04, spec 6.4)
// Pure accessibility-label builders for the P7-03 read-only surfaces. Every label states the
// exact `formatMinorUnits` values with their sign and currency code, so TalkBack reads precise
// numbers and currencies (never precisionless percentages), and every valued element is
// reachable as one announced node (C04: 长列表数值对无障碍可达). The composables pass these
// builders into `Modifier.semantics` only; the visible copy stays exactly as frozen.

/** The month-card line of one currency: the three exact values, signs and code preserved (spec 6.4). */
internal fun monthCardCurrencyLineText(row: MonthlyCurrencyActivity): String =
    "${row.currency.code}：普通收入 " +
        formatMinorUnits(row.ordinaryIncomeMinorUnits, row.currency.precision) +
        "，净支出 " +
        formatMinorUnits(row.netExpenseMinorUnits, row.currency.precision) +
        "，结余 " +
        formatMinorUnits(row.balanceMinorUnits, row.currency.precision)

/**
 * The flow-row accessibility label (C04): effective kind, the R-Q06-2 bucket key (the statistics
 * time), the current note and every exact posting amount with account name, sign and currency
 * code, so one announced node carries all values of the ordered flow row.
 */
internal fun flowRowContentDescription(
    row: LedgerEntryRow,
    accountNames: Map<AccountId, String>,
    amountText: (Long, Int) -> String = ::formatMinorUnits,
): String {
    val head = "${row.kind}，统计时间 ${row.statisticsAt}" + (row.note?.let { "，备注 $it" } ?: "")
    val legs =
        row.postings.joinToString("；") { posting ->
            flowRowPostingLineText(
                accountNames[posting.accountId] ?: posting.accountId.value,
                posting.amount.minorUnits,
                posting.amount.currency.precision,
                posting.amount.currency.code,
                amountText,
            )
        }
    return if (legs.isEmpty()) head else "$head；$legs"
}

/**
 * The LEGACY header of the shared flow row, extracted verbatim so the pre-B output stays
 * byte-identical: effective kind, the raw statistics instant and the note when it is present.
 */
internal fun flowRowLegacyHeaderText(row: LedgerEntryRow): String = "${row.kind} · 统计 ${row.statisticsAt}" + (row.note?.let { note -> " · $note" } ?: "")

/**
 * D-369 B / 规格 §7.1 共享流水行契约: the shared flow row is used by HOME, the search result list and the report
 * drilldown, so its profile decision lives here rather than in each caller.
 *
 * [UiPresentationProfile.ANDROID] renders the Chinese business name, the statistics time as the
 * frozen local wall clock ([statisticsPresentationTime], never a bare UTC instant) and the note only
 * when it carries content; postings keep their own account name, signed exact amount and currency
 * code, and the caller's [amountText] still formats them (the P806 drilldown passes
 * [p806MinorUnitsText] so `Long.MIN_VALUE` stays exact). [UiPresentationProfile.LEGACY] keeps the
 * pre-B text exactly.
 */
internal fun flowRowHeaderText(
    profile: UiPresentationProfile,
    row: LedgerEntryRow,
): String =
    if (androidTabPresentation(profile)) {
        val time = statisticsPresentationTime(row.statisticsAt).text()
        val note = row.note?.takeIf { it.isNotBlank() }
        transactionKindBusinessName(row.kind) + " · 统计 " + time + (note?.let { " · $it" } ?: "")
    } else {
        flowRowLegacyHeaderText(row)
    }

/**
 * The shared flow row's TalkBack sentence for the current profile. Android announces the same
 * Chinese business name and local time it displays plus every exact amount line; LEGACY delegates
 * to [flowRowContentDescription] unchanged.
 */
internal fun flowRowContentDescriptionFor(
    profile: UiPresentationProfile,
    row: LedgerEntryRow,
    accountNames: Map<AccountId, String>,
    amountText: (Long, Int) -> String = ::formatMinorUnits,
): String {
    if (!androidTabPresentation(profile)) return flowRowContentDescription(row, accountNames, amountText)
    val time = statisticsPresentationTime(row.statisticsAt).text()
    val note = row.note?.takeIf { it.isNotBlank() }
    val head = transactionKindBusinessName(row.kind) + "，统计时间 " + time + (note?.let { "，备注 $it" } ?: "")
    val legs =
        row.postings.joinToString("；") { posting ->
            flowRowPostingLineText(
                accountNames[posting.accountId] ?: posting.accountId.value,
                posting.amount.minorUnits,
                posting.amount.currency.precision,
                posting.amount.currency.code,
                amountText,
            )
        }
    return if (legs.isEmpty()) head else "$head；$legs"
}

/**
 * One flow-row posting line, shared by the row's VISIBLE text and its TalkBack label so the two can
 * never format a value differently. [amountText] defaults to the legacy [formatMinorUnits]; the
 * P806 drilldown passes [p806MinorUnitsText] so a legitimate `Long.MIN_VALUE` posting renders its
 * exact signed value instead of the legacy off-by-one at that end. Every other caller keeps the
 * previous behaviour bit-for-bit.
 */
internal fun flowRowPostingLineText(
    accountName: String,
    minorUnits: Long,
    precision: Int,
    currencyCode: String,
    amountText: (Long, Int) -> String = ::formatMinorUnits,
): String = "$accountName ${amountText(minorUnits, precision)} $currencyCode"

/**
 * The category-row accessibility label (C04): the category's current name plus its exact
 * per-currency totals (positive and refund parts, signs preserved, R-Q07-3) — one announced
 * node per category row, drilldown row or 无分类 row.
 */
internal fun categoryRowContentDescription(
    categoryName: String,
    totals: List<MonthlyCategoryCurrencyTotal>,
): String = "$categoryName：${categoryTotalsText(totals)}"

/**
 * The pie's accessibility label (C04): the exact per-sector net values with category names and
 * currency code — never precisionless percentages; the graphic stays a proportion aid only and
 * the exact value table always accompanies it (R-Q07-3).
 */
internal fun categoryChartContentDescription(
    currency: CurrencyUnit,
    sectors: List<CategoryChartSector>,
): String {
    val values =
        sectors.joinToString("；") { sector ->
            "${sector.categoryName} 净 ${formatMinorUnits(sector.netMinorUnits, currency.precision)} ${currency.code}"
        }
    return "${currency.code} 构成比例，精确数值：$values；图形仅供参考，数值以文本为准"
}

/**
 * The trend line of one trend month (C04/R-Q07-1): exact per-currency values, or the explicit
 * 该月无交易 copy for an empty month — distinct from any read-failure copy (R-Q06-4).
 */
internal fun trendMonthLineText(month: MonthlyActivity): String {
    val values =
        if (month.currencies.isEmpty()) {
            "该月无交易"
        } else {
            month.currencies.joinToString("；") { row ->
                "${row.currency.code} 普通收入 " +
                    formatMinorUnits(row.ordinaryIncomeMinorUnits, row.currency.precision) +
                    "，净支出 " +
                    formatMinorUnits(row.netExpenseMinorUnits, row.currency.precision) +
                    "，结余 " +
                    formatMinorUnits(row.balanceMinorUnits, row.currency.precision)
            }
        }
    return "${month.month}：$values"
}

/**
 * The detail amount-leg label (C04): account name, exact signed amount, currency code and the
 * category current name (or the explicit 无分类 absence, P703SPEC-09).
 */
internal fun transactionDetailLegContentDescription(leg: TransactionDetailLeg): String =
    "${leg.accountName} " +
        formatMinorUnits(leg.amount.minorUnits, leg.amount.currency.precision) +
        " ${leg.amount.currency.code}" +
        (leg.categoryName?.let { "（分类：$it）" } ?: "（无分类）")

/**
 * The reconciliation-leg accessibility label (C04): the leg's exact signed amount with currency
 * code plus its displayed status (eligible legs their status label, ineligible legs the
 * explicit 无对账资格 absence, R-Q07-4).
 */
internal fun reconciliationLegContentDescription(leg: TransactionReconciliationLeg): String {
    val amount =
        formatMinorUnits(leg.leg.amount.minorUnits, leg.leg.amount.currency.precision) +
            " ${leg.leg.amount.currency.code}"
    val status =
        if (leg.eligible) {
            leg.status?.label ?: P408ReconciliationStatus.PENDING.label
        } else {
            "—（无对账资格）"
        }
    return "${leg.leg.accountName} $amount：对账状态 $status"
}

// ---------------------------------------------------------------------------------------------
// B: three main tabs (D-369 spec section 7.1)
// ---------------------------------------------------------------------------------------------
// The Android profile changes layout and display decisions on the three main tabs; the LEGACY
// profile (desktop composition root, every pre-B test call site) keeps the pre-B output. Every
// decision below is a pure function, so a JVM test asserts it without a Compose harness. Nothing
// here reads a database, changes a query, or touches an accounting rule.

/** Whether the three main tabs take the Android layout at all. */
internal fun androidTabPresentation(profile: UiPresentationProfile): Boolean = profile == UiPresentationProfile.ANDROID

/**
 * HOME: the account-balances tail duplicates the ACCOUNTS tab, so the Android first screen drops
 * it. The LEGACY profile keeps rendering it exactly as before.
 */
internal fun homeShowsAccountBalancesTail(profile: UiPresentationProfile): Boolean = !androidTabPresentation(profile)

/**
 * The flow footer decision with the profile applied. `null` means "render no footer line": on
 * Android the 已到末页 statement is meaningless (the list simply ends), while loading, a failed
 * page and its retry stay explicit because they change what the user must do.
 */
internal fun flowFooterForProfile(
    view: FlowRowsView,
    profile: UiPresentationProfile,
): FlowFooterState? =
    when (val state = flowFooterState(view)) {
        FlowFooterState.END -> if (androidTabPresentation(profile)) null else state
        else -> state
    }

/**
 * One home flow row as the Android profile renders it: the §4.7 title, the posting-ordered
 * category badges, every real-asset/liability leg as its own exact line, and both business times.
 * Amount lines are NEVER folded: a transfer or a mixed payment keeps each real account's signed
 * amount, and two currencies never combine.
 */
internal data class HomeRowAndroidPresentation(
    val title: String,
    val titleSource: HomeTitleSource,
    val categoryBadges: List<PresentationCategory>,
    /** 「其余 N 个」 when more than three categories are attached; null when all badges fit. */
    val remainingBadgesText: String?,
    val hasUnclassified: Boolean,
    val amountLines: List<HomeAmountLine>,
    /** The explicit 未加载 / 读取失败 copy when no amount line can be stated; null otherwise. */
    val amountAbsenceText: String?,
    val statisticsText: String,
    val occurredText: String,
)

/** The row's Android presentation, or `null` under the LEGACY profile (the legacy row renders). */
internal fun homeRowAndroidPresentationFor(
    profile: UiPresentationProfile,
    row: LedgerEntryRow,
    snapshot: CatalogSnapshotView?,
    catalog: LedgerCatalog?,
): HomeRowAndroidPresentation? = if (androidTabPresentation(profile)) homeRowAndroidPresentation(row, snapshot, catalog) else null

/**
 * The Android home row: consumes A's pure projection ([projectHomeRow]) and keeps its decisions —
 * title priority, three-badge cap with the remaining count, per-leg amounts, both times.
 */
internal fun homeRowAndroidPresentation(
    row: LedgerEntryRow,
    snapshot: CatalogSnapshotView?,
    catalog: LedgerCatalog?,
): HomeRowAndroidPresentation {
    val projection: HomeRowProjection = projectHomeRow(row, snapshot, catalog)
    val amounts = projection.amounts
    return HomeRowAndroidPresentation(
        title = projection.title,
        titleSource = projection.titleSource,
        categoryBadges = projection.categoryBadges,
        remainingBadgesText = homeRemainingBadgesText(projection.remainingCategoryCount),
        hasUnclassified = projection.hasUnclassified,
        amountLines = (amounts as? HomeAmountProjection.Lines)?.lines.orEmpty(),
        amountAbsenceText =
            when (amounts) {
                HomeAmountProjection.NotLoaded -> presentationValueText(PresentationValue.NotLoaded)
                HomeAmountProjection.Failed -> presentationValueText(PresentationValue.Failed)
                is HomeAmountProjection.Lines -> null
            },
        statisticsText = projection.times.statistics.text(),
        occurredText = projection.times.occurred.text(),
    )
}

/** One badge's visible text: the stable `#` mark plus the category's display name (path-aware). */
internal fun homeBadgeText(badge: PresentationCategory): String = "#" + badge.displayName

/**
 * D-369 B: the two halves of one display pair — the authoritative catalog snapshot and the
 * [LedgerCatalog] the same leased read produced. They are cached, landed and handed to the Android
 * home row together.
 */
internal data class CatalogDisplayPair(
    val snapshot: CatalogSnapshotView?,
    val catalog: LedgerCatalog?,
)

/**
 * D-369 B: whether one authoritative snapshot read produced a USABLE display pair.
 *
 * The snapshot and the catalog it belongs to land together, and only together: a read whose catalog
 * half failed keeps the previous successful pair, so the home row can never render a fresh snapshot
 * beside a stale catalog (or the reverse). This is the predicate the landing decision below uses.
 */
internal fun catalogDisplayPairComplete(
    snapshot: CatalogSnapshotView?,
    catalog: LedgerCatalog?,
): Boolean = snapshot != null && catalog != null

/**
 * D-369 B: the ONE landing decision shared by the initial background load, the catalog-command
 * success path and the manual/pin refresh. An incomplete fresh pair (either half null) keeps BOTH
 * current halves; it never installs the fresh snapshot alone beside the old catalog (or the
 * reverse). A failed read therefore leaves the last successful pair standing instead of opening a
 * one-half-new window the Android home row could render.
 */
internal fun landCatalogDisplayPair(
    currentSnapshot: CatalogSnapshotView?,
    currentCatalog: LedgerCatalog?,
    freshPair: CatalogDisplayPair,
): CatalogDisplayPair =
    if (catalogDisplayPairComplete(freshPair.snapshot, freshPair.catalog)) {
        freshPair
    } else {
        CatalogDisplayPair(currentSnapshot, currentCatalog)
    }

/** 「其余 N 个」 for the categories beyond the third badge; null when nothing was dropped. */
internal fun homeRemainingBadgesText(remaining: Int): String? = if (remaining > 0) "其余 $remaining 个" else null

/**
 * The badge's palette index: A's stable `CategoryId` slot folded onto the existing
 * `P8Palette.categoryTints`, so one category keeps one colour across screens.
 */
internal fun homeBadgeTintSlot(
    categoryId: CategoryId,
    slotCount: Int,
): Int = presentationCategoryTintSlot(categoryId, slotCount)

/**
 * The home row's TalkBack sentence. It carries the SAME values the row shows — title, every badge
 * with its path, the remaining count, the 未分类 state and each exact amount with its currency
 * code — so the announced and the visible values cannot diverge and colour is never the only
 * carrier of the category fact.
 */
internal fun homeRowAndroidContentDescription(presentation: HomeRowAndroidPresentation): String {
    val parts = ArrayList<String>(presentation.categoryBadges.size + 4)
    parts.add(presentation.title)
    if (presentation.categoryBadges.isNotEmpty()) {
        parts.add(presentation.categoryBadges.joinToString("、", transform = ::homeBadgeText))
    }
    presentation.remainingBadgesText?.let(parts::add)
    if (presentation.hasUnclassified) parts.add(presentationValueText(PresentationValue.Unclassified))
    parts.add("统计时间 ${presentation.statisticsText}")
    parts.add("发生时间 ${presentation.occurredText}")
    if (presentation.amountLines.isEmpty()) {
        presentation.amountAbsenceText?.let(parts::add)
    } else {
        presentation.amountLines.forEach { line -> parts.add("${line.accountName} ${line.amount.text}") }
    }
    return parts.joinToString("；")
}

/**
 * ACCOUNTS: whether the 账户与分类管理 surface is the independent secondary route. It is a route
 * only under the Android profile and only while the reducer's add-only flag is set; LEGACY keeps
 * the pre-B single surface with every management section inline.
 */
internal fun accountsCatalogManagementRouteActive(
    profile: UiPresentationProfile,
    catalogManagementSurface: Boolean,
): Boolean = androidTabPresentation(profile) && catalogManagementSurface

/**
 * Whether the ACCOUNTS surface renders the management sections (add/rename/disable/pin/category/
 * tag/merchant). On Android those live on the route; the tab's first screen keeps the per-currency
 * net worth, the lending placeholders, the real asset/liability accounts and the account-flow
 * entries. LEGACY always renders them where they always were.
 */
internal fun accountsShowsManagementSections(
    profile: UiPresentationProfile,
    managementRouteActive: Boolean,
): Boolean = if (androidTabPresentation(profile)) managementRouteActive else true

/**
 * ACCOUNTS: the surface's title. Only the Android tab's first screen (`!managementRouteActive`)
 * says 「账户」; the management secondary route and every LEGACY rendering keep the pre-B
 * 「账户与分类管理」, so the desktop output is byte-identical to before B.
 */
internal fun catalogManagementScreenTitle(
    profile: UiPresentationProfile,
    managementRouteActive: Boolean,
): String =
    if (androidTabPresentation(profile) && !managementRouteActive) {
        "账户"
    } else {
        "账户与分类管理"
    }

/**
 * The analysis page's section decisions. On Android the page leads with the month summary, the
 * category ranking and the budget; the raw per-kind enumeration and the per-currency income/expense
 * stack leave the top of the page, an income section with no income is not rendered at all, and the
 * twelve-month trend and the independent report move behind 更多分析 (existing routes, unchanged
 * query and period semantics). LEGACY keeps today's inline layout.
 */
internal data class AndroidAnalysisLayout(
    val showsRawSummary: Boolean,
    val showsExpenseRanking: Boolean,
    val showsIncomeRegion: Boolean,
    val keepsTrendInline: Boolean,
    val keepsReportInline: Boolean,
    val showsMoreAnalysis: Boolean,
)

internal fun androidAnalysisLayout(
    profile: UiPresentationProfile,
    activity: MonthlyActivity?,
    monthlyReloadRequired: Boolean = false,
): AndroidAnalysisLayout =
    if (androidTabPresentation(profile)) {
        AndroidAnalysisLayout(
            showsRawSummary = false,
            showsExpenseRanking = true,
            // A null activity or a reload-required month is NOT "no income": the region must stay
            // present and state the not-loaded copy (the host pairs this with the region's
            // not-loaded flag). Only a loaded month can state that it has no ordinary income.
            showsIncomeRegion = monthlyReloadRequired || activity == null || activityHasIncome(activity),
            keepsTrendInline = false,
            keepsReportInline = false,
            showsMoreAnalysis = true,
        )
    } else {
        AndroidAnalysisLayout(
            showsRawSummary = true,
            // LEGACY keeps the pre-B expense region: the pie chart plus the exact table, chosen by
            // [P503CategoryRegion] with `withPie = true` — never the Android ranking.
            showsExpenseRanking = false,
            showsIncomeRegion = true,
            keepsTrendInline = true,
            keepsReportInline = true,
            showsMoreAnalysis = false,
        )
    }

/**
 * Whether the loaded month states any ordinary income at all (a real zero is not income). Only a
 * loaded activity can answer "no income"; `null` is the not-yet-loaded window and must not be read
 * as a claim about the month's income.
 */
internal fun activityHasIncome(activity: MonthlyActivity?): Boolean {
    if (activity == null) return false
    if (activity.incomeCategories.isNotEmpty()) return true
    return activity.currencies.any { row -> row.ordinaryIncomeMinorUnits != 0L }
}

/**
 * D-369 B: the 更多分析 toggle. The open flag is a view preference of this one section, not ledger
 * state, so it stays in the screen with `remember` — the query and period cursors it reveals live in
 * the shared state and are untouched by collapsing the section. No `rememberSaveable` precedent
 * exists in this module and no saveable artifact is declared by `app-ui`'s compose dependencies, so
 * this batch keeps the plain in-composition state rather than adding a dependency for it. Accepted
 * limit: the section collapses again if the screen's composition scope is recreated; the data it
 * shows is re-derived from the same state either way.
 */
internal fun moreAnalysisOpenLabel(open: Boolean): String = if (open) "收起更多分析" else "更多分析"

internal fun toggledMoreAnalysis(open: Boolean): Boolean = !open

/** One exact-table row of a category ranking (always available, even when the bars are refused). */
internal data class CategoryShareExactRow(
    val categoryId: CategoryId,
    val name: String,
    val amountMinorUnits: Long,
)

/**
 * One currency's horizontal ranking. [chartRows] is empty exactly when [failure] is non-null: the
 * page then states the explicit no-chart explanation, while the exact table rendered below already
 * carries every category and its signed amount. A single category is one row at `100.0%`.
 */
internal data class AndroidCategoryShareSection(
    val currency: CurrencyUnit,
    val chartRows: List<CategoryShareRow>,
    val exactRows: List<CategoryShareExactRow>,
    val failure: CategoryShareFailure?,
    val denominatorMinorUnits: Long,
) {
    val rendersBars: Boolean get() = chartRows.isNotEmpty()
}

/**
 * The horizontal ranking per currency, over A's [projectCategoryShares].
 *
 * Only zero amounts leave the chart input (a zero category would otherwise draw a 0.0% bar that
 * says nothing); a zero category stays visible in [exactRows]. A negative net is NOT filtered out —
 * it reaches the helper, which answers [CategoryShareProjection.NoChart] with
 * [CategoryShareFailure.NEGATIVE_AMOUNT] instead of letting the remaining categories claim a
 * misleading 100%. [exactRows] always keeps every category with its exact signed amount.
 * Currencies are never mixed or converted.
 */
internal fun androidCategoryShareSections(categories: List<MonthlyCategoryTotal>): List<AndroidCategoryShareSection> {
    val exactByCurrency = LinkedHashMap<CurrencyUnit, MutableList<CategoryShareExactRow>>()
    for (category in categories) {
        for (total in category.totals) {
            // The two parts are a fold of exact postings (MonthlyBuckets), so their sum is exact;
            // projectCategoryShares additionally refuses any overflow it meets on the way.
            val net = total.positiveMinorUnits + total.refundMinorUnits
            exactByCurrency
                .getOrPut(total.currency) { ArrayList() }
                .add(CategoryShareExactRow(category.categoryId, category.categoryName, net))
        }
    }
    return exactByCurrency.map { (currency, exactRows) ->
        val inputs =
            exactRows
                .filter { row -> row.amountMinorUnits != 0L }
                .map { row -> CategoryShareInput(row.categoryId, row.name, row.amountMinorUnits) }
        val projection = projectCategoryShares(inputs)
        when (projection) {
            is CategoryShareProjection.Chart ->
                AndroidCategoryShareSection(
                    currency = currency,
                    chartRows = projection.rows,
                    exactRows = exactRows,
                    failure = null,
                    denominatorMinorUnits = projection.denominatorMinorUnits,
                )
            is CategoryShareProjection.NoChart ->
                AndroidCategoryShareSection(currency, emptyList(), exactRows, projection.reason, 0L)
            // Every input was zero (the empty case A reports as NoCategories): the exact table still
            // renders, and the explicit zero-denominator refusal replaces a blank graphic.
            CategoryShareProjection.NoCategories ->
                AndroidCategoryShareSection(currency, emptyList(), exactRows, CategoryShareFailure.ZERO_DENOMINATOR, 0L)
        }
    }
}

/** One ranking row: category name, exact amount with its currency code, and the one-decimal share. */
internal fun categoryShareRowText(
    row: CategoryShareRow,
    currency: CurrencyUnit,
): String = "${row.name} ${formatMinorUnits(row.amountMinorUnits, currency.precision)} ${currency.code} ${row.percentText()}"

/** The explicit explanation shown instead of a chart; the exact table below the ranking stays. */
internal fun categoryShareNoChartText(failure: CategoryShareFailure): String =
    when (failure) {
        CategoryShareFailure.ZERO_DENOMINATOR -> "无法作图：分类金额合计为零。"
        CategoryShareFailure.NEGATIVE_AMOUNT -> "无法作图：存在负数分类金额。"
        CategoryShareFailure.OVERFLOW -> "无法作图：金额超出可计算范围。"
    }

/**
 * CALENDAR: whether a loaded day states any movement. A day whose axes are all zero and whose
 * count is zero is a REAL ZERO day (a stated value, not an absence): Android renders its date only.
 * Zero amounts are dropped from the cell rather than printed as `0.00`.
 */
internal fun calendarDayShowsAmounts(day: P806CalendarDayView): Boolean = day.transactionCount > 0 || day.currencies.any { row -> row.netExpenseMinorUnits != 0L || row.ordinaryIncomeMinorUnits != 0L }

/** The cell's per-currency rows: non-zero rows only on Android; every axis currency on LEGACY. */
internal fun calendarVisibleCurrencyRows(
    day: P806CalendarDayView,
    profile: UiPresentationProfile,
): List<P806CalendarCurrencyView> =
    if (androidTabPresentation(profile)) {
        day.currencies.filter { row -> row.netExpenseMinorUnits != 0L || row.ordinaryIncomeMinorUnits != 0L }
    } else {
        day.currencies
    }

/** The cell's count line: hidden on an Android day with no movement, always shown on LEGACY. */
internal fun calendarDayCountText(
    day: P806CalendarDayView,
    profile: UiPresentationProfile,
): String? =
    if (androidTabPresentation(profile) && !calendarDayShowsAmounts(day)) {
        null
    } else {
        "${day.transactionCount} 笔"
    }

/**
 * D-369 B: the bounded current-state fallback row ([com.unifiedledger.application.CurrentVersionRow],
 * rendered when the monthly cycle is not loaded). It has no catalog on the presentation state, so
 * every display value comes from the pure projection alone: the Chinese business type, the local
 * wall-clock occurred time and the exact `amount + code` per posting. The Android profile never
 * prints the enum constant or a bare UTC instant here, and it never invents an amount.
 */
internal fun currentRowAndroidHeader(
    kind: TransactionKind,
    occurredAt: Instant,
): String = transactionKindBusinessName(kind) + " · 发生 " + occurredPresentationTime(occurredAt).text()

/** One current-state posting line, shared by the visible text and the TalkBack sentence. */
internal fun currentRowAndroidAmountLine(
    accountName: String,
    minorUnits: Long,
    currency: CurrencyUnit,
): String = "$accountName ${presentationAmountText(minorUnits, currency)}"

/** The current-state row's one announced sentence: the header plus every exact amount line. */
internal fun currentRowAndroidContentDescription(
    header: String,
    amountLines: List<String>,
): String = if (amountLines.isEmpty()) header else header + "；" + amountLines.joinToString("；")

// =============================================================================
// D-369 C: secondary-surface presentation (detail, history, annotations, queue)
// =============================================================================
// One pure layer the C batch's secondary screens consume. Each rule here is profile-driven: LEGACY
// returns the pre-existing text byte-for-byte (Desktop renders the same composition root without
// naming a profile), ANDROID selects the frozen business language — Chinese business names, the
// `Asia/Shanghai` wall clock, `带符号金额 + 空格 + 币种代码` and a collapsed 技术信息 area that keeps
// every identifier, request id and diagnostic code readable instead of dropping it.

/** The business name of a kind: the frozen Chinese name on Android, the enum constant on LEGACY. */
internal fun cBusinessKindText(kind: TransactionKind, profile: UiPresentationProfile): String =
    when (profile) {
        UiPresentationProfile.LEGACY -> kind.name
        UiPresentationProfile.ANDROID -> transactionKindBusinessName(kind)
    }

/**
 * One BUSINESS instant as text, bound to its own role. The role is a required parameter, so a
 * caller cannot quietly present a statistics instant as if it were the occurred one: the Android
 * branch constructs [PresentationTime] with the role the caller named, and [PresentationTime]'s own
 * `init` rejects a swapped pair. LEGACY keeps the raw instant text it always rendered.
 */
internal fun cBusinessTimeText(
    instant: Instant,
    role: PresentationTimeRole,
    profile: UiPresentationProfile,
): String =
    when (profile) {
        UiPresentationProfile.LEGACY -> instant.toString()
        UiPresentationProfile.ANDROID -> PresentationTime(instant, role).text()
    }

/** The same rule for an already-role-bound [PresentationTime] (no second role argument exists). */
internal fun cBusinessTimeText(
    time: PresentationTime,
    profile: UiPresentationProfile,
): String = cBusinessTimeText(time.instant, time.role, profile)

/**
 * A READ instant (when a payload was read), which is neither a business occurred nor statistics
 * time. It carries no business role by construction — the surrounding label says 读取时点 — and the
 * Android branch still renders the frozen `Asia/Shanghai` wall clock. LEGACY keeps the raw value.
 */
internal fun cReadInstantText(
    instant: Instant,
    profile: UiPresentationProfile,
): String =
    when (profile) {
        UiPresentationProfile.LEGACY -> instant.toString()
        UiPresentationProfile.ANDROID -> occurredPresentationTime(instant).text()
    }


/**
 * The occurred/statistics pair as one line, each value labelled with its own role. The two roles
 * never substitute for each other, and the Android branch never prints either as a bare UTC instant.
 */
internal fun cPresentationTimesText(times: PresentationTimes, profile: UiPresentationProfile): String =
    when (profile) {
        UiPresentationProfile.LEGACY ->
            "发生时间 ${times.occurred.instant} · 统计时间 ${times.statistics.instant}"
        UiPresentationProfile.ANDROID ->
            "${times.occurred.role.label} ${times.occurred.text()} · ${times.statistics.role.label} ${times.statistics.text()}"
    }

/** An exact amount: LEGACY keeps the bare numeric text, ANDROID appends the currency code. */
internal fun cAmountText(
    minorUnits: Long,
    currency: CurrencyUnit,
    profile: UiPresentationProfile,
): String = presentationAmountForProfile(minorUnits, currency, profile)

// ------------------------------------------------------------------ 技术信息 (technical area)

internal const val C_TECHNICAL_INFO_HEADER = "技术信息"
internal const val C_TECHNICAL_INFO_EXPAND_LABEL = "展开技术信息"
internal const val C_TECHNICAL_INFO_COLLAPSE_LABEL = "收起技术信息"

/**
 * One row of the collapsed technical area. [value] is the ORIGINAL stored value — never truncated,
 * abbreviated or hashed; collapsing hides it from the normal rows, it never deletes it.
 */
internal data class TechnicalInfoRow(
    val label: String,
    val value: String,
)

/**
 * The 技术信息 area of one record: every technical fact plus whether the user expanded it.
 *
 * [visibleText] is exactly what the screen prints when expanded and empty while collapsed, and
 * [spokenText] is the TalkBack sentence for the same state, so the announced content matches the
 * visible content in both states instead of reading a hidden identifier or skipping a shown one.
 */
internal data class TechnicalInfoDisclosure(
    val rows: List<TechnicalInfoRow>,
    val expanded: Boolean,
) {
    val header: String get() = C_TECHNICAL_INFO_HEADER

    val toggleLabel: String get() = if (expanded) C_TECHNICAL_INFO_COLLAPSE_LABEL else C_TECHNICAL_INFO_EXPAND_LABEL

    /** The rows the expanded area prints; empty while collapsed. */
    val visibleRows: List<TechnicalInfoRow> get() = if (expanded) rows else emptyList()

    fun visibleText(): String = if (expanded) rows.joinToString("\n") { row -> "${row.label}：${row.value}" } else ""

    fun spokenText(): String =
        when {
            rows.isEmpty() -> "$C_TECHNICAL_INFO_HEADER：无"
            !expanded -> "$C_TECHNICAL_INFO_HEADER，已收起，共 ${rows.size} 项，$C_TECHNICAL_INFO_EXPAND_LABEL"
            else -> "$C_TECHNICAL_INFO_HEADER，共 ${rows.size} 项：" + rows.joinToString("；") { row -> "${row.label} ${row.value}" }
        }
}

internal fun cTechnicalInfoDisclosure(
    rows: List<TechnicalInfoRow>,
    expanded: Boolean,
): TechnicalInfoDisclosure = TechnicalInfoDisclosure(rows = rows, expanded = expanded)

/** The user-facing lines of a transaction detail, in the order the detail card renders them. */
internal fun cTransactionDetailUserLines(
    detail: TransactionDetail,
    profile: UiPresentationProfile,
): List<String> =
    listOf(
        "发生时间：${cBusinessTimeText(detail.occurredAt, PresentationTimeRole.OCCURRED, profile)}",
        "统计时间：${cBusinessTimeText(detail.statisticsAt, PresentationTimeRole.STATISTICS, profile)}",
        "备注：${detail.note?.takeIf { note -> note.isNotBlank() } ?: "无"}",
        "类型：${cBusinessKindText(detail.kind, profile)}",
    )

/**
 * The detail's identifiers. They leave the normal rows on the Android profile and stay complete here:
 * the ledger id, the transaction id, the current financial version id and every posting id.
 */
internal fun cTransactionDetailTechnicalRows(detail: TransactionDetail): List<TechnicalInfoRow> =
    buildList {
        add(TechnicalInfoRow("账本 ID", detail.ledgerId.value))
        add(TechnicalInfoRow("交易 ID", detail.transactionId.value))
        add(TechnicalInfoRow("当前版本 ID", detail.currentVersionId.value))
        detail.legs.forEach { leg -> add(TechnicalInfoRow("分录 ID", leg.postingId.value)) }
    }

/** One read-only financial-version line: the business fields first, its internal id in 技术信息. */
internal fun cTransactionVersionUserLine(
    version: TransactionVersion,
    currentVersionId: com.unifiedledger.domain.TransactionVersionId,
    profile: UiPresentationProfile,
): String {
    val marker = if (version.id == currentVersionId) "（当前版本）" else ""
    val note = version.note ?: "无"
    return when (profile) {
        UiPresentationProfile.LEGACY ->
            "版本 ${version.versionNumber}$marker · 发生 ${version.times.occurredAt} · 统计 ${version.times.statisticsAt} · 备注：$note"
        UiPresentationProfile.ANDROID -> {
            val times = presentationTimes(version.times.occurredAt, version.times.statisticsAt)
            "版本 ${version.versionNumber}$marker · ${cPresentationTimesText(times, profile)} · 备注：$note"
        }
    }
}

internal fun cTransactionVersionTechnicalRows(version: TransactionVersion): List<TechnicalInfoRow> =
    listOf(
        TechnicalInfoRow("版本 ID", version.id.value),
        TechnicalInfoRow("分录集 ID", version.postingSetId.value),
    )

/**
 * One annotation-history line: the revision, the audit instant (Shanghai on Android) and that
 * revision's tags/merchant stay visible; the version the operation saw and the stored request id
 * move to 技术信息.
 */
internal fun cTransactionAnnotationUserLine(
    entry: TransactionAnnotationHistoryEntry,
    tagNameOf: ((String) -> String)?,
    profile: UiPresentationProfile,
): String {
    val tags =
        if (entry.tagIds.isEmpty()) {
            "无"
        } else {
            entry.tagIds.joinToString("、") { tag -> tagNameOf?.invoke(tag.value) ?: tag.value }
        }
    val merchant = entry.merchantId?.value ?: "无"
    val revisionText =
        when (profile) {
            UiPresentationProfile.LEGACY -> "修订 ${entry.annotationRevision}（${entry.createdAt}）"
            UiPresentationProfile.ANDROID ->
                "修订 ${entry.annotationRevision}（${cStoredAuditText(entry.createdAt, profile)}）"
        }
    val sawVersion =
        when (profile) {
            UiPresentationProfile.LEGACY -> "· 所见版本 ${entry.observedTransactionVersionId.value}"
            UiPresentationProfile.ANDROID -> ""
        }
    val request = if (profile == UiPresentationProfile.LEGACY) " · 请求 ${entry.requestId}" else ""
    return "$revisionText$sawVersion$request · 标签：$tags · 商家：$merchant"
}

/** The annotation revision's internal identities, kept complete behind 技术信息. */
internal fun cTransactionAnnotationTechnicalRows(entry: TransactionAnnotationHistoryEntry): List<TechnicalInfoRow> =
    listOf(
        TechnicalInfoRow("所见版本 ID", entry.observedTransactionVersionId.value),
        TechnicalInfoRow("请求 ID", entry.requestId),
    )

/**
 * A stored audit instant TEXT as display text. The audit role is its own: this function never
 * claims the occurred/statistics business roles — it only renders the same frozen reporting zone's
 * wall clock (the projection helper is the zone/formatter, the role label stays the caller's
 * business label). A value that is not a parseable instant is shown verbatim — the stored fact is
 * never replaced by a guess, and an unparseable value is not silently dropped either.
 */
internal fun cStoredAuditText(raw: String, profile: UiPresentationProfile): String =
    when (profile) {
        UiPresentationProfile.LEGACY -> raw
        UiPresentationProfile.ANDROID ->
            runCatching { Instant.parse(raw.trim()) }.getOrNull()?.let { instant ->
                occurredPresentationTime(instant).text()
            } ?: raw
    }

// ------------------------------------------------------------------ unified failure copy

/** Which secondary surface is asking; the reason codes stay the same, only the wording differs. */
internal enum class CPresentationSurface {
    ACCOUNT_FLOW,
    REPORT,
    DRILLDOWN,
    CALENDAR,
}

/**
 * The ONE mapping from the stable [P806SnapshotFailure] type to explainable user copy. The secondary
 * surfaces used to re-implement this `when`; they now delegate here, so a new failure branch is
 * mapped once (the compiler keeps it exhaustive) and every surface explains it. Each surface keeps
 * its own frozen wording.
 */
internal fun p806SnapshotFailureUserText(
    failure: P806SnapshotFailure,
    surface: CPresentationSurface,
): String =
    when (failure) {
        P806SnapshotFailure.Unavailable ->
            when (surface) {
                CPresentationSurface.ACCOUNT_FLOW -> "账户流水读取失败，可点击重试。"
                CPresentationSurface.REPORT -> "报表读取不可用：请重试。"
                CPresentationSurface.DRILLDOWN -> "明细读取失败，可点击重试。"
                CPresentationSurface.CALENDAR -> "日历读取失败，可点击重试。"
            }
        P806SnapshotFailure.CatalogVersionMismatch ->
            when (surface) {
                CPresentationSurface.ACCOUNT_FLOW -> "账目目录已更新，请从第一页重试。"
                CPresentationSurface.REPORT -> "目录版本已变化：请重试。"
                CPresentationSurface.DRILLDOWN -> "账目目录已更新，请从第一页重试。"
                CPresentationSurface.CALENDAR -> "账目目录已更新，可点击重试。"
            }
        is P806SnapshotFailure.CatalogReference ->
            when (surface) {
                CPresentationSurface.ACCOUNT_FLOW -> "账户目录引用不一致（缺账户 / 跨账本 / 币种不符），不可用。"
                CPresentationSurface.REPORT ->
                    "目录引用不一致（缺账户 ${failure.unresolvedAccount}、跨账本 ${failure.crossLedgerAccount}、" +
                        "币种失配 ${failure.currencyMismatch}）：报表不可用。"
                CPresentationSurface.DRILLDOWN -> "目录引用不一致（缺账户 / 跨账本 / 币种不符），明细不可用。"
                CPresentationSurface.CALENDAR -> "目录引用不一致（缺账户 / 跨账本 / 币种不符），日历不可用。"
            }
        P806SnapshotFailure.MissingProjection ->
            when (surface) {
                CPresentationSurface.ACCOUNT_FLOW -> "账目缺少统计时间投影，账户流水不可用。"
                CPresentationSurface.REPORT -> "统计时间投影缺失：报表不可用。"
                CPresentationSurface.DRILLDOWN -> "账目缺少统计时间投影，明细不可用。"
                CPresentationSurface.CALENDAR -> "账目缺少统计时间投影，日历不可用。"
            }
        P806SnapshotFailure.OccurredAtUnparseable ->
            when (surface) {
                CPresentationSurface.ACCOUNT_FLOW -> "存在无法解析的资金发生时间，账户流水不可用。"
                CPresentationSurface.REPORT -> "存在无法解析的资金发生时间：报表不可用。"
                CPresentationSurface.DRILLDOWN -> "存在无法解析的资金发生时间，明细不可用。"
                CPresentationSurface.CALENDAR -> "存在无法解析的资金发生时间，日历不可用。"
            }
        P806SnapshotFailure.InvalidState ->
            when (surface) {
                CPresentationSurface.ACCOUNT_FLOW -> "账户流水数据不一致，不可用。"
                CPresentationSurface.REPORT -> "报表数据不一致：请重试。"
                CPresentationSurface.DRILLDOWN -> "明细数据不一致，不可用。"
                CPresentationSurface.CALENDAR -> "日历数据不一致，不可用。"
            }
        P806SnapshotFailure.InvalidWindow ->
            when (surface) {
                CPresentationSurface.ACCOUNT_FLOW -> "账户流水时间范围无效，不可用。"
                CPresentationSurface.REPORT -> "自定义区间的起止日期无效：请检查日期后重试。"
                CPresentationSurface.DRILLDOWN -> "明细时间范围无效，不可用。"
                CPresentationSurface.CALENDAR -> "日历时间范围无效，不可用。"
            }
    }

/** The detail read's typed outcomes as explainable copy; the stable result type is unchanged. */
internal fun transactionDetailFailureText(result: TransactionDetailResult): String =
    when (result) {
        is TransactionDetailResult.Success -> ""
        TransactionDetailResult.NotFound -> "交易不存在或不在当前账本。"
        TransactionDetailResult.InvalidState -> "交易数据不一致，无法展示详情。"
        TransactionDetailResult.Unavailable -> "无法读取交易详情（本地数据库不可用）。"
    }

/** The version-history read's typed outcomes as explainable copy. */
internal fun transactionVersionHistoryFailureText(result: TransactionVersionHistoryResult): String =
    when (result) {
        is TransactionVersionHistoryResult.Success -> ""
        TransactionVersionHistoryResult.NotFound -> "版本历史不可用：交易不存在或不在当前账本。"
        TransactionVersionHistoryResult.InvalidState -> "版本历史不可用：交易数据不一致。"
        TransactionVersionHistoryResult.Unavailable -> "无法读取版本历史（本地数据库不可用）。"
    }
