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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

// P7-06 06.D (D-182; spec sections 3/5.3/5.5/6) screens. The restore surface is a thin renderer
// over the pure presentation decisions in P503RestorePresentation.kt: a masked password field, the
// 7-field preview, the explicit replace confirmation, the typed outcome banners and the
// session-terminal face. The password reaches only the masked text field — never a log line; the
// preview carries only the ruled digest/count fields (spec section 5.5's exclusion set).

/**
 * P7-06 06.D (spec section 3/5.5): the restore confirm & switch surface. Phase 1 runs the preflight
 * ([onPreflight]; the host runs it off the UI thread); a landed [RestorePreflightResult.PreviewReady]
 * fills the preview and phase 2 offers the explicit replace confirmation ([onConfirmSwitch]; the
 * host runs the confirm & switch off the UI thread). While an operation runs the surface keeps its
 * marker: the confirms disable (提交中不重入) and the exits are absorbed by the caller's guard
 * (提交中不得离开). Outcome banners announce their change (the async-outcome live-region precedent).
 */
@Composable
internal fun P503RestoreScreen(
    state: P503AppState.BackupRestore,
    onUpdatePassword: (String) -> Unit,
    onPreflight: (() -> Unit)?,
    onConfirmSwitch: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("恢复备份", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, enabled = !state.runningPreflight && !state.runningConfirm) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        restorePreflightOutcomeText(state.preflightOutcome)?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (restorePreflightOutcomeIsError(state.preflightOutcome)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier.semantics {
                        contentDescription = text
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        restoreSwitchOutcomeText(state.confirmOutcome)?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (restoreSwitchOutcomeIsError(state.confirmOutcome)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
        Row {
            Button(
                onClick = { onPreflight?.invoke() },
                enabled = onPreflight != null && restorePreflightConfirmEnabled(state.password, state.runningPreflight, state.runningConfirm),
            ) {
                Text("读取备份")
            }
        }
        state.preview?.let { summary ->
            Spacer(Modifier.height(12.dp))
            Text(RESTORE_REPLACE_WARNING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            for (line in restorePreviewLines(summary)) {
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConfirmSwitch?.invoke() },
                enabled = onConfirmSwitch != null && restoreSwitchConfirmEnabled(state),
            ) {
                Text("确认替换当前账本并恢复")
            }
        }
        if (state.runningPreflight) {
            Spacer(Modifier.height(8.dp))
            Text("正在读取备份容器…", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.runningConfirm) {
            Spacer(Modifier.height(8.dp))
            Text("正在切换账本，请勿离开…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * P7-06 06.D (spec section 3.8 with the composition-root session-terminal obligation): the
 * fail-closed face after a rollback that also failed. No business affordance exists here — the
 * runtime behind it is Closed or StartupError — only the typed cause text (live region) and the
 * platform exit. The exit goes through the host's [onExit] channel, outside the reducer.
 */
@Composable
internal fun P503RestoreSessionTerminalScreen(
    state: P503AppState.RestoreSessionTerminal,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            restoreRecoveryText(state.cause),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                },
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onExit) {
            Text("退出")
        }
    }
}
