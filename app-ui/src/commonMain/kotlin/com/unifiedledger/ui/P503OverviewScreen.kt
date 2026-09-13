package com.unifiedledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.TransactionId
import kotlinx.datetime.YearMonth

/**
 * Home tab content (D-122): the authoritative current state rendered as-is. An empty
 * ledger shows the empty state; a non-empty ledger shows the current transaction list and
 * per-account per-currency balances with display signs. The new-expense entry point lives
 * in the shell's floating action button, not in this content.
 *
 * P7-03.C (D-145): when the composition root wires the ledger-view surface
 * ([showMonthlyRegion]) the home tab gains the unified month card (普通收入/净支出/结余 per
 * currency plus the month's transaction count; 结余 is never an account balance or cash flow),
 * the month selector over the frozen SelectMonth domain and the display-ordered flow list
 * (spec 4.2.5: statistics_at DESC → occurred_at DESC → transaction_id ASC) whose rows open the
 * read-only detail. `entryRows == null` keeps the pre-P7-03 row rendering for legacy facades.
 * Transactions whose statistics time lies after 本月 stay visible in the flow list (spec 6.2
 * residual boundary (c)).
 */
@Composable
fun P503OverviewScreen(
    state: LedgerCurrentState,
    showMonthlyRegion: Boolean = false,
    selectedMonth: YearMonth? = null,
    resolvedCurrentMonth: YearMonth? = null,
    selectableMonths: List<YearMonth> = emptyList(),
    monthlyActivity: MonthlyActivity? = null,
    entryRows: List<LedgerEntryRow>? = null,
    onSelectTransaction: (TransactionId) -> Unit = {},
    onSelectMonth: (YearMonth) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("账本：${state.ledgerId.value}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (showMonthlyRegion) {
            P503MonthCard(monthlyActivity)
            Spacer(Modifier.height(4.dp))
            P503MonthSelector(selectedMonth, resolvedCurrentMonth, selectableMonths, onSelectMonth)
            Spacer(Modifier.height(8.dp))
        }
        if (state.transactions.isEmpty() && entryRows.isNullOrEmpty()) {
            Text("账本为空，还没有任何交易。", style = MaterialTheme.typography.bodyLarge)
        } else {
            if (entryRows != null) {
                Text("流水", style = MaterialTheme.typography.titleMedium)
                entryRows.forEach { row ->
                    LedgerEntryFlowRow(row, state.accountNames, onSelectTransaction)
                    HorizontalDivider()
                }
            } else {
                Text("当前交易", style = MaterialTheme.typography.titleMedium)
                state.transactions.forEach { row ->
                    CurrentTransactionRow(row, state.accountNames)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("账户余额", style = MaterialTheme.typography.titleMedium)
            state.balances.forEach { balance ->
                Text(
                    "${state.accountNames[balance.accountId] ?: balance.accountId.value}（${balance.currency.code}）：" +
                        formatMinorUnits(balance.displayMinorUnits, balance.currency.precision),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * One clickable flow row (P7-03.C): effective kind, statistics time (the R-Q06-2 bucket key)
 * and the current note, followed by the exact posting lines. The click opens the read-only
 * detail; TalkBack announces the affordance via the click label while the exact values stay
 * in the visible texts.
 */
@Composable
private fun LedgerEntryFlowRow(
    row: LedgerEntryRow,
    accountNames: Map<AccountId, String>,
    onSelectTransaction: (TransactionId) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "查看交易详情") { onSelectTransaction(row.transactionId) }
                .padding(vertical = 4.dp),
    ) {
        Text(
            "${row.kind} · 统计 ${row.statisticsAt}" + (row.note?.let { note -> " · $note" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        row.postings.forEach { posting ->
            Text(
                "${accountNames[posting.accountId] ?: posting.accountId.value} " +
                    formatMinorUnits(posting.amount.minorUnits, posting.amount.currency.precision) +
                    " ${posting.amount.currency.code}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CurrentTransactionRow(
    row: CurrentVersionRow,
    accountNames: Map<AccountId, String>,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            "${row.kind} · ${row.occurredAt}",
            style = MaterialTheme.typography.bodyMedium,
        )
        row.postings.forEach { posting ->
            Text(
                "${accountNames[posting.accountId] ?: posting.accountId.value} " +
                    formatMinorUnits(posting.amount.minorUnits, posting.amount.currency.precision) +
                    " ${posting.amount.currency.code}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
