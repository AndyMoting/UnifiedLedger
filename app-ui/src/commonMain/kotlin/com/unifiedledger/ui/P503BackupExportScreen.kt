package com.unifiedledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

// P7-06 06.B (D-177; spec sections 3/5/6) screens. The export surface is a thin renderer over the
// pure presentation decisions in P503BackupExportPresentation.kt: a masked password field, the
// frozen "not recoverable" warning, an explicit confirm and the typed outcome banner. [onConfirm]
// is the host export call site; a `null` confirm (an unwired composition) renders the disabled
// confirm affordance rather than a dead button. While an export runs the surface keeps its marker:
// the confirm disables (提交中不重入) and the exits are absorbed by the caller's guard (提交中不得
// 离开). The password reaches only the masked text field — never a log line (spec section 3.5).

/**
 * P7-06 06.B (spec section 3): the backup-export surface. [onUpdatePassword] writes the in-memory
 * password draft; [onConfirm] starts the export (the host runs it off the UI thread); [onCancel]
 * closes the surface back to the preserved overview. The outcome banner announces its change (the
 * async-outcome live-region precedent) so a completed or failed export is never a silent repaint.
 */
@Composable
internal fun P503BackupExportScreen(
    state: P503AppState.BackupExport,
    onUpdatePassword: (String) -> Unit,
    onConfirm: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("导出备份", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, enabled = !state.running) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        backupExportOutcomeText(state.outcome)?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (backupExportOutcomeIsError(state.outcome)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                // Async outcome: announce the change, not only paint it (the P7-05 notice precedent).
                modifier =
                    Modifier.semantics {
                        contentDescription = text
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = state.password,
            onValueChange = onUpdatePassword,
            label = { Text(BACKUP_EXPORT_PASSWORD_LABEL) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(BACKUP_EXPORT_PASSWORD_WARNING, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = { onConfirm?.invoke() },
                enabled = onConfirm != null && backupExportConfirmEnabled(state.password, state.running),
            ) {
                Text("开始导出")
            }
        }
        if (state.running) {
            Spacer(Modifier.height(8.dp))
            Text("正在导出…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
