package com.unifiedledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyCategoryCurrencyTotal
import com.unifiedledger.application.MonthlyCategoryTotal
import com.unifiedledger.application.MonthlyTrend
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import kotlinx.datetime.YearMonth

// P7-03.C/D shared ledger-view presentations (D-145; spec sections 4.2/6.4). All amounts go
// through [formatMinorUnits] with signs preserved (R-Q07-3: refunds and negative values are
// never masked with absolute values); TalkBack reads the exact rendered values and currencies,
// never imprecise percentages. The graphics are proportion aids only — the exact value tables
// always accompany them, and zero/net-negative categories never draw a pie sector (R-Q07-3,
// spec 6.4). The month card three values are 普通收入/净支出/结余 (结余 is never an account
// balance or a cash flow, plan :110). Empty months/ledgers render explicit copy (该月无交易),
// distinct from read failures which are typed surfaces (R-Q06-4); a month whose payload was not
// loaded says so explicitly instead of reading as empty (F2). Special kinds are disclosed as a
// single count line (R-5) and ordinary postings without a category mapping are shown as the
// explicit 无分类 row (P703SPEC-09/F10). Every load-bearing decision here is a pure function in
// P503LedgerViewPresentation.kt so it is covered by the app-ui JVM tests (F6).

/** Fixed slice palette for the small proportion pies; indices cycle, colors carry no meaning. */
private val P703_PIE_COLORS =
    listOf(
        Color(0xFF1976D2),
        Color(0xFF388E3C),
        Color(0xFFF57C00),
        Color(0xFF7B1FA2),
        Color(0xFF00897B),
        Color(0xFFC2185B),
        Color(0xFF5D4037),
        Color(0xFF455A64),
    )

/**
 * The unified month card (spec 4.2.1): per-currency 普通收入 / 净支出 / 结余 plus the month's
 * transaction count (all effective kinds). The headline copy is the pure
 * [monthRegionHeadline] decision: 该月无交易 for an empty month/ledger, 暂无月度数据 before the
 * first payload, and an explicit 月度数据未加载——请重新选择月份 for the post-retry absence —
 * never zeros, and never a failure disguised as empty (R-Q06-4; F2). When [reloadRequired] the
 * card also offers the re-select action ([onReloadMonth]) that dispatches SelectMonth (trigger
 * (b), which always re-requests); without such an action the copy degrades instead of instructing
 * an impossible one (G4).
 */
@Composable
internal fun P503MonthCard(
    activity: MonthlyActivity?,
    reloadRequired: Boolean = false,
    onReloadMonth: (() -> Unit)? = null,
) {
    Text("月度小结", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    val state = monthlyRegionState(activity, reloadRequired)
    Text(
        monthRegionHeadline(
            state = state,
            monthLabel = activity?.month?.toString().orEmpty(),
            transactionCount = activity?.currencies?.firstOrNull()?.transactionCount ?: 0,
            reloadPossible = onReloadMonth != null,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state == MonthlyRegionState.LOADED) {
        activity?.currencies?.forEach { row ->
            Text(
                monthCardCurrencyLineText(row),
                style = MaterialTheme.typography.bodyMedium,
                // C04 (spec 6.4): the three exact values with sign and currency code stay
                // reachable as one explicit TalkBack label.
                modifier = Modifier.semantics { contentDescription = monthCardCurrencyLineText(row) },
            )
        }
    }
    if (state == MonthlyRegionState.NOT_LOADED && onReloadMonth != null) {
        TextButton(onClick = onReloadMonth) {
            Text("重新加载该月")
        }
    }
}

/**
 * The month selector over the frozen SelectMonth domain (P703SPEC-10): 上一月/下一月 steps
 * inside `[first transaction statistics month, 本月]`. The buttons disable at the domain
 * edges and stay disabled entirely when nothing is selectable (empty ledger, residual
 * boundary (b)). [selectedMonth] `null` = 本月. The edges are the pure [monthStepEdges] decision,
 * shared with the trend arrows (G3).
 */
@Composable
internal fun P503MonthSelector(
    selectedMonth: YearMonth?,
    resolvedCurrentMonth: YearMonth?,
    selectableMonths: List<YearMonth>,
    onSelectMonth: (YearMonth) -> Unit,
) {
    val edges = monthStepEdges(selectedMonth, resolvedCurrentMonth, selectableMonths)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "月份：${selectedMonth ?: resolvedCurrentMonth ?: "本月"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = edges.previous != null, onClick = { edges.previous?.let(onSelectMonth) }) {
            Text("上一月")
        }
        TextButton(enabled = edges.next != null, onClick = { edges.next?.let(onSelectMonth) }) {
            Text("下一月")
        }
    }
}

/**
 * One category section (支出分类/收入分类) with the level-1 drilldown into level-2 children
 * (R-Q07-2, current names including deactivated categories) and the always-present exact value
 * table. Positive and refund parts stay separated with signs preserved (R-Q07-3). The chart /
 * exact-table pairing is the pure [categoryChartPresentation] decision: zero and net-negative
 * categories never draw a sector while their exact values stay in the table (C04). [uncategorized]
 * is the explicit 无分类 row of ordinary postings whose account carries no category mapping
 * (P703SPEC-09/F10): it is disclosed, never invented as a category and never mixed into the
 * ordinary rows' totals, so Σ分类 reconciles with the month card.
 *
 * G1: when the monthly cycle has not been loaded for the current overview ([reloadRequired]) the
 * region renders the explicit not-loaded line instead of vanishing, which would read as "this
 * month has no categories".
 */
@Composable
internal fun P503CategoryRegion(
    title: String,
    categories: List<MonthlyCategoryTotal>,
    withPie: Boolean,
    uncategorized: List<MonthlyCategoryCurrencyTotal> = emptyList(),
    reloadRequired: Boolean = false,
) {
    val notLoadedText = categoryRegionPlaceholderText(reloadRequired)
    if (notLoadedText != null) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(notLoadedText, style = MaterialTheme.typography.bodySmall)
        return
    }
    if (categories.isEmpty() && uncategorized.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    val presentation = categoryChartPresentation(withPie, categories)
    if (presentation.rendersChart) {
        P503CategoryPies(presentation.sectors)
    }
    var expandedIds by remember(categories) { mutableStateOf(emptySet<CategoryId>()) }
    categories.forEach { level1 ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "展开或收起${level1.categoryName}") {
                            expandedIds = if (level1.categoryId in expandedIds) expandedIds - level1.categoryId else expandedIds + level1.categoryId
                        }
                        // C04 (spec 6.4): the interactive level-1 row announces its exact totals
                        // (signs and currency codes preserved) alongside the click label.
                        .semantics { contentDescription = categoryRowContentDescription(level1.categoryName, level1.totals) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (level1.categoryId in expandedIds) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(4.dp))
                Text(level1.categoryName, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                categoryTotalsText(level1.totals),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 20.dp),
            )
            if (level1.categoryId in expandedIds) {
                level1.children.forEach { child ->
                    Column(
                        modifier =
                            Modifier
                                .padding(start = 20.dp)
                                // C04: one announced node per drilldown row carrying the exact
                                // child totals with sign and currency code.
                                .semantics(mergeDescendants = true) {
                                    contentDescription = categoryRowContentDescription(child.categoryName, child.totals)
                                },
                    ) {
                        Text(child.categoryName, style = MaterialTheme.typography.bodySmall)
                        Text(
                            categoryTotalsText(child.totals),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
    if (uncategorized.isNotEmpty()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    // C04: the explicit 无分类 row is announced as one node with its exact values.
                    .semantics(mergeDescendants = true) {
                        contentDescription = categoryRowContentDescription("无分类（账户未映射分类）", uncategorized)
                    },
        ) {
            Text("无分类（账户未映射分类）", style = MaterialTheme.typography.bodyMedium)
            Text(
                categoryTotalsText(uncategorized),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}

/**
 * Small per-currency proportion pies over the expense categories (R-Q07-3, spec 6.4): a
 * category draws a sector only when its signed net contribution is positive — zero and
 * net-negative categories never draw a misleading sector (the pure [categoryChartSectors]
 * decision). Currencies are never mixed (D-120); the exact table above always accompanies the
 * graphic.
 */
@Composable
private fun P503CategoryPies(sectors: Map<CurrencyUnit, List<CategoryChartSector>>) {
    sectors.forEach { (currency, currencySectors) ->
        val sum = currencySectors.sumOf { it.netMinorUnits }
        Text(
            "${currency.code} 构成比例（仅正向净额分类）",
            style = MaterialTheme.typography.bodySmall,
        )
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    // C04 (spec 6.4): TalkBack reads the exact per-sector values with currency
                    // codes — never precisionless percentages; the graphic stays a proportion aid.
                    .semantics { contentDescription = categoryChartContentDescription(currency, currencySectors) },
        ) {
            var startAngle = -90f
            currencySectors.forEachIndexed { index, sector ->
                val sweep = 360f * sector.netMinorUnits / sum
                drawArc(
                    color = P703_PIE_COLORS[index % P703_PIE_COLORS.size],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                )
                startAngle += sweep
            }
        }
    }
}

/**
 * The twelve-month trend table (R-Q07-1): one exact line per month, old to new, with explicit
 * zero values for empty months and 该月无交易 for a ledger without any activity currency.
 *
 * [interactionsEnabled] is `false` only on the retained read-failure surface, where
 * AnalysisMonthShift is absorbed (F1). The arrows additionally follow the shared
 * [monthStepEdges] domain decision (G3), so they are disabled exactly where a shift would be
 * absorbed — including before the first payload and on a single-month ledger — and never present
 * a dead affordance. G1: when the monthly cycle has not been loaded for the current overview
 * ([reloadRequired]) the previous cycle's table is replaced by the explicit not-loaded copy
 * instead of being presented as current (R-Q06-4) — the retained read-failure surface keeps the
 * last good table behind its banner and therefore passes `false`.
 */
@Composable
internal fun P503MonthlyTrendRegion(
    trend: MonthlyTrend?,
    onAnalysisMonthShift: (Int) -> Unit,
    interactionsEnabled: Boolean = true,
    reloadRequired: Boolean = false,
    selectedMonth: YearMonth? = null,
    resolvedCurrentMonth: YearMonth? = null,
    selectableMonths: List<YearMonth> = emptyList(),
) {
    val edges = monthStepEdges(selectedMonth, resolvedCurrentMonth, selectableMonths)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("近 12 个月趋势", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(enabled = interactionsEnabled && edges.previous != null, onClick = { onAnalysisMonthShift(-1) }) { Text("前移一月") }
        TextButton(enabled = interactionsEnabled && edges.next != null, onClick = { onAnalysisMonthShift(1) }) { Text("后移一月") }
    }
    Spacer(Modifier.height(4.dp))
    val state = trendRegionState(trend, reloadRequired)
    val placeholder = trendRegionPlaceholderText(state)
    if (placeholder != null) {
        Text(placeholder, style = MaterialTheme.typography.bodyMedium)
        return
    }
    trend?.months?.forEach { month ->
        Text(
            trendMonthLineText(month),
            style = MaterialTheme.typography.bodySmall,
            // C04: the trend row's exact values (or the explicit 该月无交易 copy) stay reachable
            // as one TalkBack label per month, in the frozen old-to-new order.
            modifier = Modifier.semantics { contentDescription = trendMonthLineText(month) },
        )
    }
}

/**
 * P7-03.C read-only transaction detail (spec sections 4.2.2/6.1): both times shown separately
 * (R-Q06-1), amount legs with account names, exact signed amounts, currencies and category
 * current names (absent mapping = 无分类， never a failure, P703SPEC-09), the creation entry
 * (导入创建/手工创建/来源未标注， never guessed) and the read-only multi-leg reconciliation
 * projection with the frozen rollup (R-Q07-4; ineligible legs show 无对账资格). Zero edit
 * entries; the back affordance rides the shared back channel.
 */
@Composable
internal fun P503TransactionDetailScreen(
    detail: TransactionDetailResult,
    onClose: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("交易详情", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onClose != null) {
                OutlinedButton(onClick = onClose) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (detail) {
            is TransactionDetailResult.Success -> {
                val data = detail.detail
                Text("类型：${data.kind}", style = MaterialTheme.typography.bodyMedium)
                Text("发生时间：${data.occurredAt}", style = MaterialTheme.typography.bodyMedium)
                Text("统计时间：${data.statisticsAt}", style = MaterialTheme.typography.bodyMedium)
                Text("备注：${data.note ?: "无"}", style = MaterialTheme.typography.bodyMedium)
                Text("创建入口：${data.creationEntry.label}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("金额明细", style = MaterialTheme.typography.titleMedium)
                data.legs.forEach { leg ->
                    Text(
                        transactionDetailLegContentDescription(leg),
                        style = MaterialTheme.typography.bodySmall,
                        // C04: the leg's exact signed amount, currency and category stay reachable
                        // as one explicit TalkBack label.
                        modifier = Modifier.semantics { contentDescription = transactionDetailLegContentDescription(leg) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("资金腿对账", style = MaterialTheme.typography.titleMedium)
                data.reconciliation.legs.forEach { leg ->
                    val statusText =
                        if (leg.eligible) {
                            leg.status?.label ?: P408ReconciliationStatus.PENDING.label
                        } else {
                            "—（无对账资格）"
                        }
                    Text(
                        "${leg.leg.accountName}：$statusText",
                        style = MaterialTheme.typography.bodySmall,
                        // C04: the reconciliation row announces the leg's exact amount with its
                        // currency code alongside the status (the visible line carries the name
                        // and status only).
                        modifier = Modifier.semantics { contentDescription = reconciliationLegContentDescription(leg) },
                    )
                }
                Text(
                    "对账汇总：${data.reconciliation.rollup?.label ?: "无对账资格"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TransactionDetailResult.NotFound ->
                Text("交易不存在或不在当前账本。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            TransactionDetailResult.InvalidState ->
                Text("交易数据不一致，无法展示详情。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            TransactionDetailResult.Unavailable ->
                Text("无法读取交易详情（本地数据库不可用）。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}
