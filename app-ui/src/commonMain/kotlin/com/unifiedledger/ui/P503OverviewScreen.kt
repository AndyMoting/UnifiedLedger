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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 *
 * P7-03.D (F2) adds [monthlyReloadRequired]: the monthly payload is known to be absent after a
 * READ retry recovered a monthly failure, so the month region presents the explicit
 * 月度数据未加载——请重新选择月份 affordance instead of an empty month. [interactionsEnabled] is
 * `false` only for the retained read-failure surface (F1), which renders the same regions without
 * live clicks the reducer would absorb. G1: with [monthlyReloadRequired] the flow list falls back
 * to the fresh [LedgerCurrentState.transactions] projection instead of presenting the previous
 * cycle's [entryRows] as the current list (R-Q06-4).
 *
 * P7-05.C (D-156; spec sections 3.4/4.4): the recycle bin is a ledger-scoped surface, so its entry
 * affordance lives on the HOME overview — the natural existing home for ledger-wide navigation.
 * This is the deliberate entry-point choice: the bin is ledger-wide (it lists every voided
 * transaction, not one row's context), and DP-12 restricts only the correction/void/restore entries
 * to the detail page (列表行保持只导航), never the bin. [onOpenRecycleBin] is optional: when the
 * composition root passes it the overview offers the 回收站 entry, and when it is absent (this piece
 * does not wire the host) no affordance is rendered.
 */
@Composable
fun P503OverviewScreen(
    state: LedgerCurrentState,
    showMonthlyRegion: Boolean = false,
    selectedMonth: YearMonth? = null,
    resolvedCurrentMonth: YearMonth? = null,
    selectableMonths: List<YearMonth> = emptyList(),
    monthlyActivity: MonthlyActivity? = null,
    monthlyReloadRequired: Boolean = false,
    entryRows: List<LedgerEntryRow>? = null,
    onSelectTransaction: (TransactionId) -> Unit = {},
    onSelectMonth: (YearMonth) -> Unit = {},
    interactionsEnabled: Boolean = true,
    onOpenRecycleBin: (() -> Unit)? = null,
    onOpenBackupExport: (() -> Unit)? = null,
) {
    // G1 (R-Q06-4): a not-loaded monthly cycle must not present the previous cycle's flow rows as
    // the current list, so the fresh authoritative current-state projection is rendered instead.
    val flowRows = flowRowsForDisplay(entryRows, monthlyReloadRequired)
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("账本：${state.ledgerId.value}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        // P7-05.C: the ledger-wide recycle-bin entry, offered only when the host wires it.
        if (onOpenRecycleBin != null) {
            TextButton(onClick = onOpenRecycleBin) { Text("回收站") }
            Spacer(Modifier.height(4.dp))
        }
        // P7-06 06.B (D-177): the ledger-wide backup-export entry, offered only when the host
        // wires it (an unwired composition renders no affordance).
        if (onOpenBackupExport != null) {
            TextButton(onClick = onOpenBackupExport) { Text("导出备份") }
            Spacer(Modifier.height(4.dp))
        }
        if (showMonthlyRegion) {
            P503MonthCard(
                activity = monthlyActivity,
                reloadRequired = monthlyReloadRequired,
                // F2: the explicit re-select action dispatches SelectMonth for the effective
                // month (trigger (b), which always re-requests).
                onReloadMonth =
                    if (monthlyReloadRequired) {
                        (selectedMonth ?: resolvedCurrentMonth)?.let { month -> { onSelectMonth(month) } }
                    } else {
                        null
                    },
            )
            Spacer(Modifier.height(4.dp))
            P503MonthSelector(selectedMonth, resolvedCurrentMonth, selectableMonths, onSelectMonth)
            Spacer(Modifier.height(8.dp))
        }
        if (state.transactions.isEmpty() && flowRows.isNullOrEmpty()) {
            Text("账本为空，还没有任何交易。", style = MaterialTheme.typography.bodyLarge)
        } else {
            if (flowRows != null) {
                Text("流水", style = MaterialTheme.typography.titleMedium)
                flowRows.forEach { row ->
                    LedgerEntryFlowRow(row, state.accountNames, onSelectTransaction, interactionsEnabled)
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
 * in the visible texts. [interactionsEnabled] is `false` only on the retained read-failure
 * surface, where the reducer absorbs the open-detail event: the row then renders without a dead
 * click affordance (F1).
 */
@Composable
private fun LedgerEntryFlowRow(
    row: LedgerEntryRow,
    accountNames: Map<AccountId, String>,
    onSelectTransaction: (TransactionId) -> Unit,
    interactionsEnabled: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = interactionsEnabled, onClickLabel = "查看交易详情") { onSelectTransaction(row.transactionId) }
                // C04 (spec 6.4): the flow row announces as one node carrying the effective
                // kind, the statistics time and every exact posting value with sign, account
                // name and currency code — the ordered list's values stay TalkBack-reachable.
                .semantics { contentDescription = flowRowContentDescription(row, accountNames) }
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
