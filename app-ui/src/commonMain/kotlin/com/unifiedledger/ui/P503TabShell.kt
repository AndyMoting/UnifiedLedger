package com.unifiedledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.ui.theme.glass.GlassBackdropSource
import com.unifiedledger.ui.theme.glass.GlassSurface
import com.unifiedledger.ui.theme.glass.rememberGlassBackdrop

/**
 * P5-04.1 overview shell (D-122, bottom-bar layout deltas D-123/D-124): a material3 scaffold
 * whose bottom bar is a single row with the three overview tabs in a floating capsule-shaped
 * surface on the left and the new-expense entry point as a circular floating action button on
 * the right, vertically centered with the tab bar. System navigation bar insets are applied
 * once around the whole row, so the FAB center and the tab bar center stay on the same
 * horizontal line. Tab selection lives in the shared reducer state
 * ([P503AppState.OverviewEmpty.selectedTab]); the shell only renders the selected tab and
 * keeps the FAB visible in every tab. P7-02.D E-2: when the host passes
 * [onSaveAndRecordAgain] (HOME tab with a retained determinate-success intent) a "record
 * again" button joins the row.
 */
@Composable
fun P503TabShell(
    selectedTab: P503Tab,
    onSelectTab: (P503Tab) -> Unit,
    onStartNewExpense: () -> Unit,
    onSaveAndRecordAgain: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val glassBackdrop = rememberGlassBackdrop()
    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 24.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSurface(
                    backdrop = glassBackdrop,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        windowInsets = WindowInsets(0.dp),
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == P503Tab.HOME,
                            onClick = { onSelectTab(P503Tab.HOME) },
                            icon = { Text("首") },
                            label = { Text("首页") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == P503Tab.ACCOUNTS,
                            onClick = { onSelectTab(P503Tab.ACCOUNTS) },
                            icon = { Text("账") },
                            label = { Text("账户") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == P503Tab.ANALYSIS,
                            onClick = { onSelectTab(P503Tab.ANALYSIS) },
                            icon = { Text("析") },
                            label = { Text("分析") },
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                if (onSaveAndRecordAgain != null) {
                    Button(
                        onClick = onSaveAndRecordAgain,
                        modifier = Modifier.semantics { contentDescription = "保存后再记一笔" },
                    ) {
                        Text("再记一笔")
                    }
                    Spacer(Modifier.width(16.dp))
                }
                FloatingActionButton(
                    onClick = onStartNewExpense,
                    modifier = Modifier.semantics { contentDescription = "新增支出" },
                ) {
                    Text("+", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) { innerPadding ->
        GlassBackdropSource(
            backdrop = glassBackdrop,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            content()
        }
    }
}

/**
 * Accounts tab content is now the P7-01.D management surface ([P503CatalogManagementScreen] on
 * the shared [P503AppState.OverviewEmpty] management fields): manageable accounts with display
 * names, kind, balance and active flag, plus the shared two-level category tree. The former
 * read-only `P503AccountsScreen` (bare `accountId` rows) is superseded and removed.
 *
 * Analysis tab content (D-122): the pure [SummarizeLedgerActivity] derivation over the
 * authoritative current state, rendered as-is. The UI never accumulates amounts itself;
 * signed totals (which can be negative) go through [formatMinorUnits] unchanged.
 *
 * P7-03.D (D-145): when the composition root wires the ledger-view surface ([showMonthlyRegion])
 * the whole-period summary keeps its frozen D-122 face and the monthly region renders below it
 * (spec 6.1 layering): the month card and category drilldown for the shared month cursor, plus
 * the twelve-month trend (R-Q07-1) with the AnalysisMonthShift affordance. Both tabs consume the
 * same [com.unifiedledger.application.QueryMonthlyActivity] result (plan section 5.1).
 *
 * F4 (spec section 8 R-5): the special-kind divergence between the whole-period summary above and
 * the monthly ordinary totals is disclosed as a single special-kind count line next to the
 * category region, never mixed into the ordinary rows. F10 (P703SPEC-09): ordinary postings
 * without a category mapping appear as the explicit 无分类 row, so Σ分类 reconciles with the month
 * card. F2: [monthlyReloadRequired] swaps the empty-month copy for the explicit re-select
 * affordance after a READ-retry recovery. [interactionsEnabled] is `false` only on the retained
 * read-failure surface, where the analysis shift is absorbed (F1). G1: while
 * [monthlyReloadRequired] the category region and the trend region say explicitly that the monthly
 * data is not loaded instead of rendering an empty or stale surface (R-Q06-4).
 */
@Composable
fun P503AnalysisScreen(
    state: LedgerCurrentState,
    summarizeActivity: SummarizeLedgerActivity,
    showMonthlyRegion: Boolean = false,
    selectedMonth: kotlinx.datetime.YearMonth? = null,
    resolvedCurrentMonth: kotlinx.datetime.YearMonth? = null,
    selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
    monthlyActivity: com.unifiedledger.application.MonthlyActivity? = null,
    monthlyReloadRequired: Boolean = false,
    trend: com.unifiedledger.application.MonthlyTrend? = null,
    onSelectMonth: (kotlinx.datetime.YearMonth) -> Unit = {},
    onAnalysisMonthShift: (Int) -> Unit = {},
    interactionsEnabled: Boolean = true,
) {
    val summary = remember(state, summarizeActivity) { summarizeActivity.summarize(state) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        if (summary.totalTransactionCount == 0) {
            Text("账本为空，还没有任何交易。", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("交易总笔数：${summary.totalTransactionCount}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("按交易类型", style = MaterialTheme.typography.bodyMedium)
            summary.countByKind.forEach { (kind, count) ->
                Text(
                    "$kind：$count",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("按币种收支", style = MaterialTheme.typography.bodyMedium)
            summary.totalsByCurrency.forEach { total ->
                Text(
                    "${total.currency.code}：" +
                        "支出 " + formatMinorUnits(total.expenseMinorUnits, total.currency.precision) +
                        "，收入 " + formatMinorUnits(total.incomeMinorUnits, total.currency.precision),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (showMonthlyRegion) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            P503MonthCard(
                activity = monthlyActivity,
                reloadRequired = monthlyReloadRequired,
                onReloadMonth =
                    if (monthlyReloadRequired) {
                        (selectedMonth ?: resolvedCurrentMonth)?.let { month -> { onSelectMonth(month) } }
                    } else {
                        null
                    },
            )
            Spacer(Modifier.height(4.dp))
            P503MonthSelector(selectedMonth, resolvedCurrentMonth, selectableMonths, onSelectMonth)
            // R-5 disclosure: the special-kind count line explains why this month's ordinary
            // totals need not equal the whole-period figures above.
            specialKindDisclosure(monthlyActivity)?.let { disclosure ->
                Spacer(Modifier.height(8.dp))
                Text(disclosure, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            P503CategoryRegion(
                title = "支出分类",
                categories = monthlyActivity?.expenseCategories ?: emptyList(),
                withPie = true,
                uncategorized = monthlyActivity?.uncategorizedExpenseTotals ?: emptyList(),
                reloadRequired = monthlyReloadRequired,
            )
            Spacer(Modifier.height(8.dp))
            P503CategoryRegion(
                title = "收入分类",
                categories = monthlyActivity?.incomeCategories ?: emptyList(),
                withPie = false,
                uncategorized = monthlyActivity?.uncategorizedIncomeTotals ?: emptyList(),
                reloadRequired = monthlyReloadRequired,
            )
            Spacer(Modifier.height(8.dp))
            P503MonthlyTrendRegion(
                trend = trend,
                onAnalysisMonthShift = onAnalysisMonthShift,
                interactionsEnabled = interactionsEnabled,
                reloadRequired = monthlyReloadRequired,
                selectedMonth = selectedMonth,
                resolvedCurrentMonth = resolvedCurrentMonth,
                selectableMonths = selectableMonths,
            )
        }
    }
}
