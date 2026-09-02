package com.unifiedledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.LedgerCatalog

/**
 * P5-04.1 overview shell (D-122): a material3 scaffold with the three overview tabs in a
 * bottom navigation bar and the new-expense entry point as a centered floating action
 * button. Tab selection lives in the shared reducer state ([P503AppState.OverviewEmpty.selectedTab]);
 * the shell only renders the selected tab and keeps the FAB visible in every tab.
 */
@Composable
fun P503TabShell(
    selectedTab: P503Tab,
    onSelectTab: (P503Tab) -> Unit,
    onStartNewExpense: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onStartNewExpense,
                modifier = Modifier.semantics { contentDescription = "新增支出" },
            ) {
                Text("+")
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            content()
        }
    }
}

/**
 * Accounts tab content (D-122): one row per account-currency balance from the
 * authoritative read payload, annotated with the catalog account kind. Accounts without
 * postings do not appear because the payload only carries accounts with balances; no
 * per-account drill-down exists in this batch.
 */
@Composable
fun P503AccountsScreen(
    state: LedgerCurrentState,
    catalog: LedgerCatalog,
) {
    val accountsById = remember(catalog) { catalog.accounts.associateBy { it.id } }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        if (state.balances.isEmpty()) {
            Text("还没有任何账户余额。", style = MaterialTheme.typography.bodyLarge)
        } else {
            state.balances.forEach { balance ->
                Text(
                    "${balance.accountId.value}（${accountKindLabel(accountsById, balance.accountId)}）" +
                        "${balance.currency.code}：" +
                        formatMinorUnits(balance.displayMinorUnits, balance.currency.precision),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
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

private fun accountKindLabel(
    accountsById: Map<AccountId, Account>,
    accountId: AccountId,
): String = accountsById[accountId]?.kind?.name ?: "未知账户"
