package com.unifiedledger.ui

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

/**
 * Home tab content (D-122): the authoritative current state rendered as-is. An empty
 * ledger shows the empty state; a non-empty ledger shows the current transaction list and
 * per-account per-currency balances with display signs. The new-expense entry point lives
 * in the shell's floating action button, not in this content.
 */
@Composable
fun P503OverviewScreen(
    state: LedgerCurrentState,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("账本：${state.ledgerId.value}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (state.transactions.isEmpty()) {
            Text("账本为空，还没有任何交易。", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("当前交易", style = MaterialTheme.typography.titleMedium)
            state.transactions.forEach { row ->
                CurrentTransactionRow(row)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("账户余额", style = MaterialTheme.typography.titleMedium)
            state.balances.forEach { balance ->
                Text(
                    "${balance.accountId.value}（${balance.currency.code}）：" +
                        formatMinorUnits(balance.displayMinorUnits, balance.currency.precision),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CurrentTransactionRow(row: CurrentVersionRow) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            "${row.kind} · ${row.occurredAt}",
            style = MaterialTheme.typography.bodyMedium,
        )
        row.postings.forEach { posting ->
            Text(
                "${posting.accountId.value} " +
                    formatMinorUnits(posting.amount.minorUnits, posting.amount.currency.precision) +
                    " ${posting.amount.currency.code}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
