package com.unifiedledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.P806CategoryCurrencyTotal
import com.unifiedledger.application.P806CategoryView
import com.unifiedledger.application.P806CurrencyView
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806Periods
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806ResolvedPeriod
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.domain.CurrencyUnit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * P8-06 batch b (D-343 sections 3.1/3.3/3.7/3.9; the approved T2 daily-average presentation): the
 * analysis page's independent report region — its slot state, its pure presentation decisions and
 * its read-only rendering.
 *
 * The region is NOT the month card: it owns its own period cursor (independent of the shared
 * SelectMonth/AnalysisMonthShift cursor), it renders only a real P806 report snapshot, and it never
 * reuses a monthly payload nor fabricates a zero for a state that has no period. All amount text is
 * exact minor units at the currency's precision; all decisions that matter are pure functions here
 * so the JVM tests hold them without a Compose runtime.
 *
 * Drilldown entries, the calendar, the typed detail return target and any write action are OUT of
 * this slice by the batch contract, so every category row here is display-only.
 */

/**
 * The analysis page's report slot (D-343 section 3.7: the report is an optional payload on the
 * overview, not a state of its own).
 *
 * [period] is the requested period definition — its default carries no explicit anchor, so the
 * APPLICATION layer resolves the Shanghai current month; the UI never owns that conversion. [kind]
 * is the VISIBLE period selection and is deliberately separate from the loaded payload: switching
 * the selection never renames a payload that was read for another kind. [landedKind] is the kind the
 * last success was actually read for, so its label and its amounts stay that kind's own.
 *
 * The custom texts are drafts: editing them changes nothing until the user commits the range, and
 * selecting the custom kind only reveals the inputs.
 *
 * [requestToken] is the host's monotonic request identity and [loading] marks the ONE request in
 * flight; [failure] is the last request's typed failure while [landed]/[landedAsOf]/[landedKind]
 * stay the last SUCCESS beside it (labelled with its own period, axis and read instant, never
 * presented as the current result). [landedGeneration]/[landedCatalogVersion] record the provenance
 * of that success. [unwired] is the explicit "this graph cannot read reports" state — it renders
 * nothing at all.
 */
data class P806ReportRegionState(
    val period: P806Period = P806Period(P806PeriodKind.MONTH),
    val kind: P806PeriodKind = P806PeriodKind.MONTH,
    val customStartText: String = "",
    val customEndText: String = "",
    val requestToken: Long = 0L,
    /**
     * The monotonic PRE-WRITE fence (D-343 section 3.7): a determinate write raises it to the token of
     * the request that was in flight and ends that request. Any result at or below the fence is
     * ineligible — it can neither land nor make the region READY nor be re-seeded by the host — even
     * if it arrives after the invalidation and before the authoritative refresh's own landing.
     */
    val fenceToken: Long = 0L,
    val loading: Boolean = false,
    val failure: P806SnapshotFailure? = null,
    val unwired: Boolean = false,
    val landed: P806ReportView? = null,
    val landedKind: P806PeriodKind? = null,
    val landedAsOf: Instant? = null,
    val landedGeneration: Generation? = null,
    val landedCatalogVersion: Long? = null,
    /**
     * True from the determinate-write invalidation until the next accepted landing: the last success
     * is OLD data, the region is pending the authoritative re-read, and neither the payload nor the
     * period may be treated as current (no READY, no re-seed).
     */
    val writeStale: Boolean = false,
)

/** The six distinguishable payload states of the region (D-343 section 3.7.7), plus "no surface". */
internal enum class P806ReportRegionFace {
    /** No report capability in this graph: render nothing (never a zero, never an empty chart). */
    NOT_RENDERED,
    NOT_LOADED,
    LOADING,
    FAILED,
    EMPTY,
    READY,
}

/**
 * The region's face: loading is shown ONLY while a request is actually pending (`loading`) — the
 * write's owed authoritative re-read included — so the surface never claims a read that is not
 * running. Then a failure wins over an old success (which stays visible as labelled old context),
 * and a write-fenced payload with nothing in flight is that same failure face (a retry entry, never
 * a fake loading): its last success is old data, never the current result. "Empty" separates the two
 * honest empty successes — no period at all (empty `ALL`/empty ledger) and a known period with zero
 * ordinary contribution.
 */
internal fun p806ReportRegionFace(state: P806ReportRegionState): P806ReportRegionFace =
    when {
        state.unwired -> P806ReportRegionFace.NOT_RENDERED
        state.loading -> P806ReportRegionFace.LOADING
        state.failure != null || state.writeStale -> P806ReportRegionFace.FAILED
        state.landed == null -> P806ReportRegionFace.NOT_LOADED
        state.landed.period == null || state.landed.transactionCount == 0 -> P806ReportRegionFace.EMPTY
        else -> P806ReportRegionFace.READY
    }

/**
 * The shared eligibility seam (D-343 section 3.7): a slot whose payload may be treated as the
 * CURRENT result. Only a CONCLUDED SUCCESS qualifies — the landing left a payload, no write fenced
 * it, no request is pending and no failure replaced it. A failed or superseded read keeps its old
 * payload as labelled history, which is exactly why "has a payload" is not enough: the host's
 * last-ready memory, its payload mark and the reducer's re-seed all go through this ONE predicate,
 * so neither a write-stale nor a failed-over payload can be re-seeded as READY.
 */
internal fun p806ReportSlotIsEligible(slot: P806ReportRegionState): Boolean = slot.landed != null && !slot.writeStale && !slot.loading && slot.failure == null

/** The write-invalidation notice shown over the old payload while the authoritative re-read is owed. */
internal fun p806ReportWriteStaleNotice(): String = "账目已更新：正在刷新报表，以下为更新前的旧数据。"

/**
 * Exact minor-unit text at [precision], including the `Long.MIN_VALUE` end.
 *
 * The shared [formatMinorUnits] cannot represent `|Long.MIN_VALUE|` (it substitutes `Long.MAX_VALUE`
 * and is therefore off by one minor unit); a legitimate aggregation or a one-day daily average can
 * reach that value (spec V-17), so the report surface formats it from the exact decimal digits
 * instead. Every other value keeps the shared formatter, and 0 never carries a sign.
 */
internal fun p806MinorUnitsText(
    minorUnits: Long,
    precision: Int,
): String {
    if (minorUnits != Long.MIN_VALUE) return formatMinorUnits(minorUnits, precision)
    require(precision in 0..18) { "currency precision must be in 0..18" }
    val digits = minorUnits.toString().removePrefix("-")
    if (precision == 0) return "-$digits"
    val padded = digits.padStart(precision + 1, '0')
    val whole = padded.substring(0, padded.length - precision)
    val fraction = padded.substring(padded.length - precision)
    return "-$whole.$fraction"
}

/** The display name of a period kind (D-343 section 3.1.1). */
internal fun p806ReportPeriodKindLabel(kind: P806PeriodKind): String =
    when (kind) {
        P806PeriodKind.WEEK -> "周"
        P806PeriodKind.MONTH -> "月"
        P806PeriodKind.YEAR -> "年"
        P806PeriodKind.ALL -> "全部"
        P806PeriodKind.CUSTOM -> "自定义"
    }

/** The static statement that separates the two periods shown on the analysis page (D-343 section 3.1.3). */
internal fun p806ReportIndependentPeriodNotice(): String = "报表期间独立于上方月卡与趋势的月份游标。"

/** One resolved period as the region labels it: the kind, the inclusive local dates and the day count. */
internal fun p806ReportResolvedPeriodText(
    period: P806ResolvedPeriod,
    kind: P806PeriodKind,
): String = "${p806ReportPeriodKindLabel(kind)}：${period.startDate} 至 ${period.endDateInclusive}（${period.dayCount} 天）"

/**
 * The region's period line for the CURRENT selection. It never borrows another kind's dates: the
 * resolved dates appear only when the last success was read for the kind on screen, so switching the
 * selection while a read is in flight (or after it failed) shows the selection and its own state
 * instead of old dates wearing a new kind's label. The last success keeps its own line below
 * ([p806ReportLastSuccessContextText]).
 */
internal fun p806ReportPeriodLabel(state: P806ReportRegionState): String {
    val resolved = state.landed?.period
    val sameKind = state.landedKind == null || state.landedKind == state.kind
    if (resolved != null && sameKind) return p806ReportResolvedPeriodText(resolved, state.kind)
    val suffix = if (state.loading) "读取中" else "尚未取得解析区间"
    return "${p806ReportPeriodKindLabel(state.kind)}：$suffix"
}

/**
 * The last success's own context (kind, period, currency axis, read instant) — the label the region
 * shows whenever the current face is loading or failed, so an old payload is never mistaken for the
 * current result. `null` when nothing landed yet.
 */
internal fun p806ReportLastSuccessContextText(state: P806ReportRegionState): String? {
    val view = state.landed ?: return null
    val periodText = view.period?.let { period -> "${period.startDate} 至 ${period.endDateInclusive}" } ?: "无"
    val kindPrefix = state.landedKind?.let { kind -> "${p806ReportPeriodKindLabel(kind)}：" }.orEmpty()
    val axis =
        view.currencies
            .map { row -> row.currency.code }
            .distinct()
            .joinToString("、")
            .ifEmpty { "无" }
    val asOf = state.landedAsOf?.toString() ?: "不可用"
    return "最近成功（期间 $kindPrefix$periodText；币种轴 $axis；读取时点 $asOf）"
}

/** The explicit "this block is old" caption above the last success's amounts (D-343 section 3.7.7). */
internal fun p806ReportLastSuccessCaption(): String = "以下为最近一次成功载荷（旧数据）："

/** The typed failure copy (D-343 section 3.8): explainable, never a bare zero and never a retry loop. */
internal fun p806ReportFailureText(failure: P806SnapshotFailure): String =
    when (failure) {
        P806SnapshotFailure.Unavailable -> "报表读取不可用：请重试。"
        P806SnapshotFailure.CatalogVersionMismatch -> "目录版本已变化：请重试。"
        P806SnapshotFailure.MissingProjection -> "统计时间投影缺失：报表不可用。"
        P806SnapshotFailure.OccurredAtUnparseable -> "存在无法解析的资金发生时间：报表不可用。"
        P806SnapshotFailure.InvalidWindow -> "自定义区间的起止日期无效：请检查日期后重试。"
        P806SnapshotFailure.InvalidState -> "报表数据不一致：请重试。"
        is P806SnapshotFailure.CatalogReference ->
            "目录引用不一致（缺账户 ${failure.unresolvedAccount}、跨账本 ${failure.crossLedgerAccount}、" +
                "币种失配 ${failure.currencyMismatch}）：报表不可用。"
    }

/** The two honest empty successes (D-343 section 3.7.7): no period at all, or a zero-contribution period. */
internal fun p806ReportEmptyText(state: P806ReportRegionState): String =
    if (state.landed?.period == null) {
        "该账本还没有可归属的统计期间：不呈现期间金额，日均不适用。"
    } else {
        "该期间没有普通收支贡献：以下零值是明确结果。"
    }

/** One exact per-currency amount with its currency code (sign preserved, no float, no rounding here). */
internal fun p806ReportAmountText(
    label: String,
    minorUnits: Long,
    currency: CurrencyUnit,
): String = "$label ${p806MinorUnitsText(minorUnits, currency.precision)} ${currency.code}"

/** 净支出（含退款）: the frozen inclusive-of-refunds net expense, sign preserved. */
internal fun p806ReportNetExpenseText(row: P806CurrencyView): String = p806ReportAmountText("净支出（含退款）", row.netExpenseMinorUnits, row.currency)

/** 普通收入 of one currency. */
internal fun p806ReportIncomeText(row: P806CurrencyView): String = p806ReportAmountText("普通收入", row.ordinaryIncomeMinorUnits, row.currency)

/** 结余 = 普通收入 − 净支出 (D-343 section 1.2), never an account balance. */
internal fun p806ReportBalanceText(row: P806CurrencyView): String = p806ReportAmountText("结余", row.balanceMinorUnits, row.currency)

/** 日均支出: the signed daily average, or the explicit 不适用 when the period has no natural day. */
internal fun p806ReportDailyAverageText(row: P806CurrencyView): String =
    row.dailyAverageMinorUnits?.let { average -> p806ReportAmountText("日均支出", average, row.currency) }
        ?: "日均支出 不适用"

/** The static denominator statement: the daily average divides by the period's full natural days. */
internal fun p806ReportDailyAverageDaysText(period: P806ResolvedPeriod?): String? = period?.let { resolved -> "日均按该期间完整自然日数（${resolved.dayCount} 天）计算：进行中的期间不缩短分母。" }

/** The future-statistics marker: `null` at N = 0 (absent, never a "0 future" claim). */
internal fun p806ReportFutureMarkerText(count: Int): String? = if (count <= 0) null else "含 $count 笔未来统计日期的普通收支。"

/** The ordinary contributing transaction count of the loaded period. */
internal fun p806ReportTransactionCountText(view: P806ReportView): String = "普通收支笔数：${view.transactionCount}"

/** The explicit 无分类 row's label (D-343 section 3.9.4: never invented as a category). */
internal fun p806ReportUncategorizedLabel(): String = "无分类"

/**
 * One category's exact per-currency net contribution; currencies never merge.
 *
 * The net is `positive + refund`, which cannot overflow because the producing fold keeps the frozen
 * split `positive >= 0`, `refund <= 0` (`com.unifiedledger.application.P806Folds.Accumulator` sums
 * the gross and refund columns separately with checked arithmetic, so every reachable pair is legal
 * and the sum lies between the two operands). The public
 * [com.unifiedledger.application.P806CategoryCurrencyTotal] carries no `init` guard of its own, so a
 * hand-built illegal pair (`positive > 0 && refund > 0`) is the only counterexample — and no
 * production path constructs one; the display therefore stays a plain checked-free addition instead
 * of growing the API.
 */
internal fun p806ReportCategoryTotalsText(totals: List<P806CategoryCurrencyTotal>): String =
    totals.joinToString("，") { total ->
        "${p806MinorUnitsText(total.positiveMinorUnits + total.refundMinorUnits, total.currency.precision)} ${total.currency.code}"
    }

/** One category row: its current name plus the exact per-currency values. */
internal fun p806ReportCategoryRowText(category: P806CategoryView): String = "${category.categoryName}：${p806ReportCategoryTotalsText(category.totals)}"

/** The accessibility label of one category row: the level, the current name and the exact values. */
internal fun p806ReportCategoryAccessibilityText(
    category: P806CategoryView,
    level: Int,
): String = "${if (level <= 1) "一级分类" else "二级分类"} ${category.categoryName}，合计 ${p806ReportCategoryTotalsText(category.totals)}"

/** The explicit 无分类 row of one section; `null` when that section has none. */
internal fun p806ReportUncategorizedRowText(totals: List<P806CategoryCurrencyTotal>): String? = if (totals.isEmpty()) null else "${p806ReportUncategorizedLabel()}：${p806ReportCategoryTotalsText(totals)}"

/**
 * The period anchored on the landed window: after a success the requested kind carries an explicit
 * anchor (the resolved start date), so the week/month/year cursors step from what was actually read
 * instead of from a second clock read. `ALL` and `CUSTOM` keep their definition.
 */
internal fun p806ReportAnchoredPeriod(
    period: P806Period,
    view: P806ReportView?,
): P806Period {
    val start = view?.period?.startDate ?: return period
    return when (period.kind) {
        P806PeriodKind.WEEK -> period.copy(weekStart = start)
        P806PeriodKind.MONTH -> period.copy(month = YearMonth(start.year, start.month))
        P806PeriodKind.YEAR -> period.copy(year = start.year)
        P806PeriodKind.ALL,
        P806PeriodKind.CUSTOM,
        -> period
    }
}

/**
 * The period a kind chip requests: the ANCHORED period the slot already holds when that kind was the
 * one actually read (so re-tapping the current kind is the same period and is absorbed — no read),
 * otherwise that kind's relative definition, which the application resolves.
 */
internal fun p806ReportKindPeriod(
    state: P806ReportRegionState,
    kind: P806PeriodKind,
): P806Period = if (state.period.kind == kind && state.landedKind == kind) state.period else P806Period(kind)

/**
 * The reachable period domain of the LAST success snapshot (D-343 section 3.1.3): the periods of one
 * kind that contain the earliest and the latest effective statistics date, both ends inclusive.
 */
internal data class P806ReportReachableDomain(
    val kind: P806PeriodKind,
    val firstStart: LocalDate,
    val lastEnd: LocalDate,
)

/**
 * The reachable domain of [view] for [kind], or `null` when it does not exist.
 *
 * The span comes from the SAME snapshot as the payload ([P806ReportView.span]) — never from the month
 * card's upper bound, never from a second read — and its dates are projected through the
 * application's frozen reporting zone ([MonthlyBuckets.REPORT_TIME_ZONE]), so no literal offset or
 * hand-written conversion enters the UI. A snapshot without a span has no domain at all: an absent
 * span is NOT an infinite range. `ALL` and `CUSTOM` keep their approved rules and never step.
 */
internal fun p806ReportReachableDomain(
    view: P806ReportView,
    kind: P806PeriodKind,
): P806ReportReachableDomain? {
    if (kind == P806PeriodKind.ALL || kind == P806PeriodKind.CUSTOM) return null
    val span = view.span ?: return null
    val earliest = span.earliest.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date
    val latest = span.latest.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date
    val first = p806ReportContainingPeriod(kind, earliest) ?: return null
    val last = p806ReportContainingPeriod(kind, latest) ?: return null
    return P806ReportReachableDomain(kind = kind, firstStart = first.first, lastEnd = last.second)
}

/** The inclusive local-date bounds of the period of [kind] that contains [date]. */
internal fun p806ReportContainingPeriod(
    kind: P806PeriodKind,
    date: LocalDate,
): Pair<LocalDate, LocalDate>? =
    when (kind) {
        P806PeriodKind.WEEK -> {
            val start = P806Periods.weekStartOf(date)
            start to start.plus(6, DateTimeUnit.DAY)
        }
        P806PeriodKind.MONTH -> {
            val start = YearMonth(date.year, date.month).firstDay
            start to start.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        }
        P806PeriodKind.YEAR -> LocalDate(date.year, 1, 1) to LocalDate(date.year, 12, 31)
        P806PeriodKind.ALL,
        P806PeriodKind.CUSTOM,
        -> null
    }

/**
 * Whether one step lands on a reachable period: inside the domain, or — when the current period sits
 * OUTSIDE the domain (a default 本月 beyond the ledger's span) — strictly toward it. A step that
 * leaves the domain outward, or that moves away from it, is refused, so the affordance never offers
 * a request the approved domain excludes.
 */
internal fun p806ReportStepReachable(
    domain: P806ReportReachableDomain,
    current: P806ResolvedPeriod,
    candidate: Pair<LocalDate, LocalDate>,
): Boolean {
    val (candidateStart, candidateEnd) = candidate
    if (candidateStart >= domain.firstStart && candidateEnd <= domain.lastEnd) return true
    if (current.endDateInclusive < domain.firstStart) return candidateStart > current.startDate
    if (current.startDate > domain.lastEnd) return candidateEnd < current.endDateInclusive
    return false
}

/**
 * The next/previous period of the selected kind, or `null` when the kind does not step (`ALL`,
 * `CUSTOM`), the offset is 0, a read is in flight (the landed payload is old context), the snapshot
 * has no period (无期间禁步进), or the candidate falls outside the reachable domain (D-343 section
 * 3.1.3). The arithmetic is plain local-date arithmetic on the application geometry — never a fixed
 * offset, never a time-zone conversion.
 */
internal fun p806ReportShiftedPeriod(
    state: P806ReportRegionState,
    offset: Int,
): P806Period? {
    if (offset == 0) return null
    if (state.loading) return null
    val view = state.landed ?: return null
    val current = view.period ?: return null
    val kind = state.kind
    if (state.landedKind != null && state.landedKind != kind) return null
    val domain = p806ReportReachableDomain(view, kind) ?: return null
    val candidate =
        when (kind) {
            P806PeriodKind.WEEK -> {
                val anchor = state.period.takeIf { period -> period.kind == P806PeriodKind.WEEK }?.weekStart ?: current.startDate
                P806Period(P806PeriodKind.WEEK, weekStart = anchor.plus(offset * 7, DateTimeUnit.DAY))
            }
            P806PeriodKind.MONTH -> {
                val anchor =
                    state.period
                        .takeIf { period -> period.kind == P806PeriodKind.MONTH }
                        ?.month
                        ?.firstDay
                        ?: current.startDate
                val shifted = anchor.plus(offset, DateTimeUnit.MONTH)
                P806Period(P806PeriodKind.MONTH, month = YearMonth(shifted.year, shifted.month))
            }
            P806PeriodKind.YEAR -> {
                val anchor = state.period.takeIf { period -> period.kind == P806PeriodKind.YEAR }?.year ?: current.startDate.year
                P806Period(P806PeriodKind.YEAR, year = anchor + offset)
            }
            P806PeriodKind.ALL,
            P806PeriodKind.CUSTOM,
            -> return null
        }
    val candidateAnchor =
        when (kind) {
            P806PeriodKind.WEEK -> candidate.weekStart
            P806PeriodKind.MONTH -> candidate.month?.firstDay
            P806PeriodKind.YEAR -> candidate.year?.let { year -> LocalDate(year, 1, 1) }
            P806PeriodKind.ALL,
            P806PeriodKind.CUSTOM,
            -> null
        } ?: return null
    val candidateBounds = p806ReportContainingPeriod(kind, candidateAnchor) ?: return null
    return if (p806ReportStepReachable(domain, current, candidateBounds)) candidate else null
}

/**
 * The custom period assembled from the two drafts. An unparseable or blank side stays `null` and a
 * reversed range is passed through untouched: the application layer answers the typed
 * `InvalidWindow`, so the host never silently rewrites the dates the user typed.
 */
internal fun p806CustomPeriod(
    startText: String,
    endText: String,
): P806Period =
    P806Period(
        P806PeriodKind.CUSTOM,
        customStart = startText.trim().takeIf { it.isNotEmpty() }?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() },
        customEnd = endText.trim().takeIf { it.isNotEmpty() }?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() },
    )

/** One exact statistic cell of the per-currency 2x2 (the whole line is one TalkBack label). */
@Composable
private fun RowScope.P806ReportStatCell(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .semantics { contentDescription = text },
    )
}

/** The per-currency 2x2 of the approved T2 style: net expense / income, balance / daily average. */
@Composable
private fun P806ReportCurrencyBlock(row: P806CurrencyView) {
    Text(row.currency.code, style = MaterialTheme.typography.titleSmall)
    Row(modifier = Modifier.fillMaxWidth()) {
        P806ReportStatCell(p806ReportNetExpenseText(row))
        P806ReportStatCell(p806ReportIncomeText(row))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        P806ReportStatCell(p806ReportBalanceText(row))
        P806ReportStatCell(p806ReportDailyAverageText(row))
    }
    Spacer(Modifier.height(6.dp))
}

/** One category section: level-1 rows with their level-2 children and the explicit 无分类 row. */
@Composable
private fun P806ReportCategorySection(
    title: String,
    categories: List<P806CategoryView>,
    uncategorized: List<P806CategoryCurrencyTotal>,
) {
    val uncategorizedRow = p806ReportUncategorizedRowText(uncategorized)
    if (categories.isEmpty() && uncategorizedRow == null) return
    Spacer(Modifier.height(4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    categories.forEach { levelOne ->
        Text(
            p806ReportCategoryRowText(levelOne),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = p806ReportCategoryAccessibilityText(levelOne, level = 1) },
        )
        levelOne.children.forEach { child ->
            Text(
                p806ReportCategoryRowText(child),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .padding(start = 16.dp)
                        .semantics { contentDescription = p806ReportCategoryAccessibilityText(child, level = 2) },
            )
        }
    }
    uncategorizedRow?.let { uncategorizedText ->
        Text(
            uncategorizedText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = uncategorizedText },
        )
    }
}

/**
 * The last success block shown beside a loading or failed face: its own label (kind, period, axis,
 * read instant) plus its VISIBLE amounts, captioned as old data. A no-period success shows the label
 * only — never a fabricated zero.
 */
@Composable
private fun P806ReportLastSuccessBlock(state: P806ReportRegionState) {
    val view = state.landed ?: return
    p806ReportLastSuccessContextText(state)?.let { context ->
        Text(context, style = MaterialTheme.typography.bodySmall)
    }
    if (view.period == null) return
    Text(p806ReportLastSuccessCaption(), style = MaterialTheme.typography.bodySmall)
    Text(p806ReportTransactionCountText(view), style = MaterialTheme.typography.bodySmall)
    view.currencies.forEach { row -> P806ReportCurrencyBlock(row) }
}

/**
 * The analysis page's report region (D-343 sections 3.1/3.3/3.7/3.9).
 *
 * [state] is the slot; the callbacks are the host's requests (they own every IO — the region reads
 * nothing itself). The region renders NOTHING when the graph has no report capability, and it never
 * renders a zero for an unloaded, failed or no-period state. The five kind chips drive the VISIBLE
 * selection ([P806ReportRegionState.kind]); the custom chip only reveals the two inputs, and the
 * explicit 查询自定义区间 commit is the only thing that reads a custom range.
 */
@Composable
internal fun P503ReportRegion(
    state: P806ReportRegionState,
    onSelectKind: (P806PeriodKind) -> Unit,
    onOpenCustomInputs: () -> Unit,
    onShiftPeriod: (Int) -> Unit,
    onUpdateCustomRange: (String, String) -> Unit,
    onCommitCustomRange: () -> Unit,
    onRetry: () -> Unit,
) {
    val face = p806ReportRegionFace(state)
    if (face == P806ReportRegionFace.NOT_RENDERED) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("报表期间", style = MaterialTheme.typography.titleMedium)
        Text(p806ReportIndependentPeriodNotice(), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(
                P806PeriodKind.WEEK,
                P806PeriodKind.MONTH,
                P806PeriodKind.YEAR,
                P806PeriodKind.ALL,
                P806PeriodKind.CUSTOM,
            ).forEach { kind ->
                val selected = state.kind == kind
                TextButton(
                    onClick = {
                        if (kind == P806PeriodKind.CUSTOM) onOpenCustomInputs() else onSelectKind(kind)
                    },
                ) {
                    Text(
                        p806ReportPeriodKindLabel(kind),
                        style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        when (state.kind) {
            P806PeriodKind.WEEK,
            P806PeriodKind.MONTH,
            P806PeriodKind.YEAR,
            ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onShiftPeriod(-1) }) { Text("上一期间") }
                    TextButton(onClick = { onShiftPeriod(1) }) { Text("下一期间") }
                }
            P806PeriodKind.CUSTOM ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = state.customStartText,
                        onValueChange = { text -> onUpdateCustomRange(text, state.customEndText) },
                        label = { Text("起始日期（YYYY-MM-DD）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.customEndText,
                        onValueChange = { text -> onUpdateCustomRange(state.customStartText, text) },
                        label = { Text("结束日期（YYYY-MM-DD）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onCommitCustomRange) { Text("查询自定义区间") }
                }
            P806PeriodKind.ALL -> Unit
        }
        Text(p806ReportPeriodLabel(state), style = MaterialTheme.typography.bodyMedium)
        when (face) {
            P806ReportRegionFace.NOT_LOADED ->
                Text("报表尚未加载。", style = MaterialTheme.typography.bodyMedium)
            P806ReportRegionFace.LOADING -> {
                Text(
                    if (state.writeStale) p806ReportWriteStaleNotice() else "正在读取报表…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                P806ReportLastSuccessBlock(state)
            }
            P806ReportRegionFace.FAILED -> {
                state.failure?.let { failure ->
                    Text(p806ReportFailureText(failure), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onRetry) { Text("重试") }
                P806ReportLastSuccessBlock(state)
            }
            P806ReportRegionFace.EMPTY -> {
                Text(p806ReportEmptyText(state), style = MaterialTheme.typography.bodyMedium)
                val view = state.landed
                if (view != null && view.period != null) {
                    Text(p806ReportTransactionCountText(view), style = MaterialTheme.typography.bodySmall)
                    view.currencies.forEach { row -> P806ReportCurrencyBlock(row) }
                    p806ReportDailyAverageDaysText(view.period)?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            P806ReportRegionFace.READY -> {
                val view = state.landed
                if (view != null) {
                    Text(p806ReportTransactionCountText(view), style = MaterialTheme.typography.bodySmall)
                    p806ReportFutureMarkerText(view.futureTransactionCount)?.let { marker ->
                        Text(marker, style = MaterialTheme.typography.bodySmall)
                    }
                    view.currencies.forEach { row -> P806ReportCurrencyBlock(row) }
                    p806ReportDailyAverageDaysText(view.period)?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                    P806ReportCategorySection(
                        title = "支出分类",
                        categories = view.expenseCategories,
                        uncategorized = view.uncategorizedExpenseTotals,
                    )
                    P806ReportCategorySection(
                        title = "收入分类",
                        categories = view.incomeCategories,
                        uncategorized = view.uncategorizedIncomeTotals,
                    )
                }
            }
            P806ReportRegionFace.NOT_RENDERED -> Unit
        }
    }
}
