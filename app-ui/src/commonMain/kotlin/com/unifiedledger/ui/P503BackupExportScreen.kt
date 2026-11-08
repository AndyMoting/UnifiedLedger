package com.unifiedledger.ui

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
// confirm affordance rather than a dead button. P8-00 b2 fix round: the face is ALSO the drawer's
// restore CARRIER, so [onOpenRestore] rides the same convention — a non-null callback renders the
// clickable 恢复备份 affordance (issuing [P503UiEvent.OpenBackupRestore] on the carrier), a `null`
// callback renders the honest disabled affordance with its 暂未开放 suffix (the shell batch's
// disabled-row precedent), and a running export disables it (提交中不重入). While an export runs
// the surface keeps its marker: the confirm disables (提交中不重入) and the exits are absorbed by
// the caller's guard (提交中不得离开). The password reaches only the masked text field — never a
// log line (spec section 3.5).

/**
 * P7-06 06.B (spec section 3): the backup-export surface — and, P8-00 b2, the drawer's restore
 * CARRIER face. [onUpdatePassword] writes the in-memory password draft; [onConfirm] starts the
 * export (the host runs it off the UI thread); [onOpenRestore] is the carrier's restore leg — the
 * host passes it only when the restore use cases are wired, and the disabled affordance below
 * covers the unwired shape honestly; [onCancel] closes the surface back to the preserved overview.
 * The outcome banner announces its change (the async-outcome live-region precedent) so a completed
 * or failed export is never a silent repaint.
 */
@Composable
internal fun P503BackupExportScreen(
    state: P503AppState.BackupExport,
    onUpdatePassword: (String) -> Unit,
    onConfirm: (() -> Unit)?,
    onOpenRestore: (() -> Unit)?,
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
            Spacer(Modifier.width(12.dp))
            // P8-00 b2 fix round: the carrier's restore leg, shaped like the drawer's honest
            // three-state convention. Wired = clickable and issues OpenBackupRestore on the carrier;
            // unwired = the same affordance disabled with the 暂未开放 suffix (never a dead button,
            // never a silently absent leg); a running export disables both actions (提交中不重入).
            val restoreEnabled = backupExportRestoreEntryEnabled(restoreWired = onOpenRestore != null, running = state.running)
            OutlinedButton(
                onClick = { onOpenRestore?.invoke() },
                enabled = restoreEnabled,
            ) {
                Text(backupExportRestoreEntryLabel(restoreWired = onOpenRestore != null))
            }
        }
        if (state.running) {
            Spacer(Modifier.height(8.dp))
            Text("正在导出…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
