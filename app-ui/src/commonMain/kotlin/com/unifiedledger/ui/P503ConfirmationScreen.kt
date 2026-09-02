package com.unifiedledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Awaiting-confirmation screen (spec section 7.3.4). Shows the complete attempted
 * snapshot; cancel performs no save. Confirming enters the single-submission-lock
 * Submitting state. The account and category lines render the display labels carried by
 * the state (P5-04.3); raw ids are never rendered.
 */
@Composable
fun P503ConfirmationScreen(
    draft: ManualExpenseDraft,
    currencyCode: String,
    paymentAccountLabel: String,
    categoryLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text("确认支出", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("支付账户：$paymentAccountLabel", style = MaterialTheme.typography.bodyMedium)
        Text("费用分类：$categoryLabel", style = MaterialTheme.typography.bodyMedium)
        Text("金额：${draft.amountText} $currencyCode", style = MaterialTheme.typography.bodyMedium)
        Text("发生时间：${draft.occurredAt ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text("取消")
            }
            Button(onClick = onConfirm) {
                Text("确认提交")
            }
        }
    }
}
