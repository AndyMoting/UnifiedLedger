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
import androidx.compose.material3.FloatingActionButton
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
 * keeps the FAB visible in every tab.
 */
@Composable
fun P503TabShell(
    selectedTab: P503Tab,
    onSelectTab: (P503Tab) -> Unit,
    onStartNewExpense: () -> Unit,
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
 */
@Composable
fun P503AnalysisScreen(
    state: LedgerCurrentState,
    summarizeActivity: SummarizeLedgerActivity,
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
    }
}
