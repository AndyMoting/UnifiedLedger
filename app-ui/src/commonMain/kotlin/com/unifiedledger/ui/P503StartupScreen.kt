package com.unifiedledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Startup fail-closed state (spec section 8). The platform composition root owns the
 * driver/schema/create/open lifecycle, catches failures and exposes these states; the
 * shared UI renders them. Error state offers only Retry and Exit.
 */
sealed interface P503StartupState {
    data object Starting : P503StartupState

    data object Ready : P503StartupState

    /** LocalDatabaseUnavailable: the demo has exactly one startup failure mode. */
    data object StartupError : P503StartupState

    /**
     * P7-06 06.D (D-182; spec section 5.3): the pointerless-generations shape of the startup
     * failure (a `POINTER_MISSING` rejection with candidate generation directories on disk). The
     * composition root reaches it ONLY through the read-only recovery probe after a failed start -
     * the startup gates themselves are untouched - and the face offers the two user-confirmed
     * recovery actions of spec section 5.3 plus the plain retry/exit.
     */
    data class PointerRecovery(
        val state: PointerMissingRecoveryState,
    ) : P503StartupState
}

@Composable
fun P503StartupScreen(
    state: P503StartupState,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    // P7-06 06.D (spec section 5.3): the user-confirmed recovery actions. Null renders no
    // affordance (an unwired composition keeps the plain StartupError face); the adopt affordance
    // exists only when a candidate verified complete-and-current, the discard-and-re-upgrade
    // affordance only when the legacy original is available and no candidate is adoptable.
    onAdoptPointer: (() -> Unit)? = null,
    onDiscardAndReUpgrade: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            P503StartupState.Starting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "正在打开本地账本…",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            P503StartupState.Ready -> {
                Text("本地账本已就绪", style = MaterialTheme.typography.bodyLarge)
            }
            P503StartupState.StartupError -> {
                Text(
                    "无法打开本地账本（本地数据库不可用）",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text("重试")
                }
                TextButton(onClick = onExit) {
                    Text("退出")
                }
            }
            is P503StartupState.PointerRecovery -> {
                // P7-06 06.D (spec section 5.3): the pointerless-generations recovery face. Every
                // load-bearing decision is a pure presentation decision; the two actions are the
                // explicit user confirmations - nothing on this face runs automatically.
                Text(
                    "无法打开本地账本：缺少活动代指针，但磁盘上存在候选代目录。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                Spacer(Modifier.height(8.dp))
                for (line in restoreRecoveryCandidateLines(state.state)) {
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                if (restoreRecoveryAdoptVisible(state.state)) {
                    Button(onClick = { onAdoptPointer?.invoke() }, enabled = onAdoptPointer != null) {
                        Text("采纳通过验证的代目录")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (restoreRecoveryDiscardVisible(state.state)) {
                    Button(onClick = { onDiscardAndReUpgrade?.invoke() }, enabled = onDiscardAndReUpgrade != null) {
                        Text("废弃无法验证的代目录并重跑旧库升级")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = onRetry) {
                    Text("重试")
                }
                TextButton(onClick = onExit) {
                    Text("退出")
                }
            }
        }
    }
}
