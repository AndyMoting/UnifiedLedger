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
}

@Composable
fun P503StartupScreen(
    state: P503StartupState,
    onRetry: () -> Unit,
    onExit: () -> Unit,
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
        }
    }
}
