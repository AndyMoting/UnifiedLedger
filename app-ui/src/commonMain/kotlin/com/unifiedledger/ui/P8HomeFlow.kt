package com.unifiedledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.ui.theme.LocalP8Palette
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// P8-00 b4b: the home tab's content organisation (spec section 2, 首页). Everything below the
// composables is a pure fold over data the screen already receives, so the summary card and the
// day headers can never disagree with the month card or the analysis regions about the month they
// describe: both read the same projection and the same frozen region state.

/** The three summary pages the card pages through (spec section 2: 支出/收入/结余). */
internal enum class P8SummaryPage(
    val label: String,
) {
    EXPENSE("支出"),
    INCOME("收入"),
    BALANCE("结余"),
}

/** One exact per-currency value on a summary page; the precision travels with it. */
internal data class P8SummaryLine(
    val currencyCode: String,
    val precision: Int,
    val amountMinorUnits: Long,
)

/**
 * One page's per-currency values in the fold's order (currency code, then precision). A null
 * [activity] — payload not loaded, or no movement in the month — yields an empty list, so the card
 * shows the explicit not-loaded copy instead of a zero that would read as a real amount (R-Q06-4).
 */
internal fun p8SummaryLines(
    activity: MonthlyActivity?,
    page: P8SummaryPage,
): List<P8SummaryLine> {
    if (activity == null) return emptyList()
    return activity.currencies.map { row ->
        val amount =
            when (page) {
                P8SummaryPage.EXPENSE -> row.netExpenseMinorUnits
                P8SummaryPage.INCOME -> row.ordinaryIncomeMinorUnits
                P8SummaryPage.BALANCE -> row.balanceMinorUnits
            }
        P8SummaryLine(row.currency.code, row.currency.precision, amount)
    }
}

/**
 * The page's values, gated on the month's own region state (R-Q06-4 / F2): only a LOADED month may
 * state amounts. An empty month, an absent payload and the post-retry absence return no lines, so
 * the card shows a dash plus the frozen copy instead of a zero the ledger never stated.
 */
internal fun p8SummaryLinesFor(
    activity: MonthlyActivity?,
    page: P8SummaryPage,
    region: MonthlyRegionState,
): List<P8SummaryLine> = if (region == MonthlyRegionState.LOADED) p8SummaryLines(activity, page) else emptyList()

/** The TalkBack sentence of one summary page: every exact value with its currency code. */
internal fun p8SummaryPageDescription(
    page: P8SummaryPage,
    lines: List<P8SummaryLine>,
): String {
    if (lines.isEmpty()) {
        return "${page.label}：暂无月度数据"
    }
    val values =
        lines.joinToString("；") { line ->
            "${line.currencyCode} ${formatMinorUnits(line.amountMinorUnits, line.precision)}"
        }
    return page.label + "：" + values
}

/**
 * One reporting day of the home flow. [rows] keep the port's display order
 * (statistics_at DESC -> occurred_at DESC -> transaction_id ASC), so grouping never reorders.
 * The header states this day's count only: the money fold needs the ledger catalog on the
 * presentation state, which it does not carry yet (registered residual — the header never invents
 * a sum, and never folds a partially paged list into one).
 */
internal data class P8DayGroup(
    val date: LocalDate,
    val rows: List<LedgerEntryRow>,
) {
    val count: Int get() = rows.size
}

/** The reporting-zone calendar day of a statistics instant (R-Q06-2 frozen zone). */
internal fun p8ReportingDay(statisticsAt: Instant): LocalDate = statisticsAt.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date

/**
 * Whether the home tab presents the ledger-wide empty voice.
 *
 * Keyed on the LEDGER's transaction total, never on the loaded flow page: a wired flow surface is
 * never null, so keying on it made the empty branch unreachable and a brand-new ledger presented the
 * month card plus 流水 · 共 0 笔 instead of the empty state the baseline canvas documents (D-307). An
 * empty MONTH inside a populated ledger keeps the month row and the flow scaffolding — that is a
 * different state and must not claim an empty ledger.
 */
internal fun p8ShowsEmptyLedger(transactionTotal: Int): Boolean = transactionTotal <= 0

/** Groups the flow rows by reporting day, preserving the incoming order. */
internal fun p8GroupByDay(rows: List<LedgerEntryRow>): List<P8DayGroup> {
    if (rows.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<LocalDate, MutableList<LedgerEntryRow>>()
    for (row in rows) {
        buckets.getOrPut(p8ReportingDay(row.statisticsAt)) { ArrayList() }.add(row)
    }
    return buckets.map { (day, dayRows) -> P8DayGroup(date = day, rows = dayRows) }
}

/**
 * The paged summary card (spec section 2: one large amount, 收入/结余 as secondary lines, three
 * dots as the position indicator). The pager is the gesture; the dots are a read-only indicator,
 * each carrying its own TalkBack sentence, and each page exposes every exact value.
 */
@Composable
internal fun P8SummaryCard(
    activity: MonthlyActivity?,
    reloadRequired: Boolean = false,
    onReloadMonth: (() -> Unit)? = null,
    // Hoisted by the caller: a pager state created inside the lazy item would reset to 支出 every
    // time the card scrolls out of view and back.
    pagerState: PagerState = rememberPagerState { P8SummaryPage.entries.size },
) {
    val palette = LocalP8Palette.current
    val pages = P8SummaryPage.entries
    val region = monthlyRegionState(activity, reloadRequired)
    Surface(
        color = palette.card,
        shape = RoundedCornerShape(palette.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { pageIndex ->
                val page = pages[pageIndex]
                // R-Q06-4 / F2: only a LOADED month may show amounts. An empty month, an absent
                // payload and the post-retry absence all keep the frozen copy and a dash — a zero
                // here would read as a real amount the ledger never stated.
                val lines = p8SummaryLinesFor(activity, page, region)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = p8SummaryPageDescription(page, lines) },
                ) {
                    Text(page.label, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    val head = lines.firstOrNull()
                    Text(
                        if (head == null) "—" else "${head.currencyCode} ${formatMinorUnits(head.amountMinorUnits, head.precision)}",
                        style = MaterialTheme.typography.headlineLarge,
                        color = if (page == P8SummaryPage.EXPENSE && (head?.amountMinorUnits ?: 0L) > 0L) palette.expense else palette.onSurface,
                    )
                    // D-120 forbids cross-currency summation, so every other currency keeps its own
                    // visible line instead of surviving only in the TalkBack sentence.
                    lines.drop(1).forEach { line ->
                        Text(
                            "${line.currencyCode} ${formatMinorUnits(line.amountMinorUnits, line.precision)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceVariant,
                        )
                    }
                    // The month card's own frozen copy decides between 该月无交易, 暂无月度数据 and
                    // the post-retry absence, so the card and this summary cannot describe the same
                    // state twice differently.
                    if (region != MonthlyRegionState.LOADED) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            monthRegionHeadline(
                                state = region,
                                monthLabel = activity?.month?.toString().orEmpty(),
                                transactionCount = activity?.currencies?.firstOrNull()?.transactionCount ?: 0,
                                reloadPossible = onReloadMonth != null,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceVariant,
                        )
                    }
                }
            }
            if (pages.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pages.forEachIndexed { index, page ->
                        val active = index == pagerState.currentPage
                        Box(
                            modifier =
                                Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(if (active) 8.dp else 6.dp)
                                    .clearAndSetSemantics {
                                        contentDescription =
                                            "第 ${index + 1} 页，共 ${pages.size} 页，${page.label}" +
                                            if (active) "（当前）" else ""
                                    },
                        ) {
                            Surface(
                                color = if (active) palette.onSurface else palette.disabledFaint,
                                shape = CircleShape,
                                modifier = Modifier.size(if (active) 8.dp else 6.dp),
                            ) {}
                        }
                    }
                }
            }
            if (reloadRequired && onReloadMonth != null) {
                TextButton(onClick = onReloadMonth) { Text("重新加载该月") }
            }
        }
    }
}

/**
 * The month row: ‹ previous, the month label, next ›, and the optional 收支日历 carrier. The
 * calendar page itself belongs to a later slice, so the host renders that chip only when it wires
 * a destination.
 */
@Composable
internal fun P8HomeMonthRow(
    monthLabel: String,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onOpenCalendar: (() -> Unit)? = null,
) {
    val palette = LocalP8Palette.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(enabled = onPrevious != null, onClick = { onPrevious?.invoke() }) { Text("‹") }
        Text(
            monthLabel,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = onNext != null, onClick = { onNext?.invoke() }) { Text("›") }
        onOpenCalendar?.let { open ->
            TextButton(onClick = open) { Text("收支日历") }
        }
    }
}

/**
 * The search capsule (spec section 2 首页). Search behaviour belongs to P8-02, so the host renders
 * this only when it wires a destination; an unwired host renders nothing and the home keeps no
 * dead affordance.
 */
@Composable
internal fun P8SearchCapsule(onClick: () -> Unit) {
    val palette = LocalP8Palette.current
    Surface(
        onClick = onClick,
        color = palette.card,
        shape = RoundedCornerShape(percent = 50),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .semantics { contentDescription = "搜索账目" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("搜索账目、金额、备注", style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
        }
    }
}

/**
 * One day's header: the date and this day's transaction count. [interactionsEnabled] mirrors the
 * flow rows — on the retained read-failure surface the header keeps its disclosure but offers no
 * click, so a toggle that the reducer would absorb is not presented as live.
 */
@Composable
internal fun P8DayGroupHeader(
    group: P8DayGroup,
    expanded: Boolean,
    interactionsEnabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val palette = LocalP8Palette.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(
                    enabled = interactionsEnabled,
                    onClickLabel = if (expanded) "折叠当天的流水" else "展开当天的流水",
                ) { onToggle() }
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${if (expanded) "▾" else "▸"} ${group.date} · ${group.count} 笔",
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = palette.divider)
}
