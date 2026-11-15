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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// P8-05 批 b (D-331 spec sections 4 批 b / D-E8-D-E10; V-7) screens. A thin renderer over the pure
// presentation decisions in P503ReadableExportPresentation.kt (the P503BackupExportScreen
// precedent): the frozen U4 purpose note first, the D-E11 consistency declaration, the explicit
// start, the D-E9 counting progress row, the 取消导出 affordance and the typed outcome banner.
// [onStart] is the host export call site; a `null` start (an unwired composition) renders the
// disabled start affordance rather than a dead button. While an export runs the surface keeps its
// markers: the start disables (提交中不重入), 取消导出 becomes reachable, and the exits are
// absorbed by the caller's guard (提交中不得离开) until the export lands or is cancelled.
//
// Async-outcome discipline (the backup face's liveRegion precedent): the outcome banner announces
// its change, so a completed, cancelled or failed export is never a silent repaint (V-8's TalkBack
// leg is instrumented in 批 c).

/**
 * P8-05 批 b: the readable-export surface. [onStart] starts the export (the host runs it off the
 * UI thread); [onRequestCancel] records the cooperative cancel request (the host flips the signal
 * the page loop checks — the request itself never blocks); [onCancel] closes the surface back to
 * the preserved overview while nothing is running.
 */
@Composable
internal fun P503ReadableExportScreen(
    state: P503AppState.ReadableExport,
    onStart: (() -> Unit)?,
    onRequestCancel: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("导出账目", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, enabled = !state.running) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        // U4: the purpose-difference note is the surface's FIRST line (the frozen ruling wording).
        Text(READABLE_EXPORT_PURPOSE_NOTE, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        // D-E11: the page-level-consistency declaration.
        Text(READABLE_EXPORT_CONSISTENCY_NOTE, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        readableExportOutcomeText(state.outcome)?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (readableExportOutcomeIsError(state.outcome)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                // Async outcome: announce the change, not only paint it (the backup face precedent).
                modifier =
                    Modifier.semantics {
                        contentDescription = text
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        Row {
            Button(
                onClick = { onStart?.invoke() },
                enabled = onStart != null && readableExportStartEnabled(state.running),
            ) {
                Text("开始导出")
            }
            Spacer(Modifier.width(12.dp))
            // The cooperative cancel: reachable only while a run is in flight and unrequested; a
            // wired-nothing composition never renders a dead button (the null-callback convention).
            OutlinedButton(
                onClick = { onRequestCancel?.invoke() },
                enabled = onRequestCancel != null && readableExportCancelEnabled(state.running, state.cancelRequested),
            ) {
                Text("取消导出")
            }
        }
        if (state.running) {
            Spacer(Modifier.height(8.dp))
            // D-E9: the counting progress row (liveRegion=Polite so each page refresh is read).
            val progressText = readableExportProgressText(state.exportedRows, state.totalRows)
            Text(
                progressText,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = progressText
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
    }
}
