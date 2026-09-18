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
import com.unifiedledger.application.TypedEntryDraft

/**
 * Awaiting-confirmation screen (spec section 7.3.4). Shows the complete attempted snapshot; cancel
 * performs no save. Confirming enters the single-submission-lock Submitting state. The title and
 * the field rows are the type-aware presentation of [confirmationTitle]/[confirmationRows]; the
 * account and category lines render the display labels carried by the state (P5-04.3), which the
 * reducer falls back to the draft's id value when the host resolves no label.
 */
@Composable
internal fun P503ConfirmationScreen(
    draft: TypedEntryDraft,
    currencyCode: String,
    currencyPrecision: Int,
    labels: ConfirmationLabels,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Text(
            confirmationTitle(draft),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        confirmationRows(draft, labels, currencyCode, currencyPrecision).forEach { row ->
            Text("${row.label}：${row.value}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("备注：${draft.note.ifEmpty { "—" }}", style = MaterialTheme.typography.bodyMedium)
        Text("发生时间：${draft.occurredAt?.let(::occurredAtDisplayText) ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, enabled = cancelEnabled) {
                Text("取消")
            }
            Button(onClick = onConfirm, enabled = confirmEnabled) {
                Text("确认提交")
            }
        }
    }
}
