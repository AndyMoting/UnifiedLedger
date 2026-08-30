package com.unifiedledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Result presentation (spec sections 7.1/7.3.6). Created/NoChange/Recovered are transient
 * banners followed by an authoritative refresh; UnknownCommit only presents a resolving
 * state with no automatic retry and no requestId replacement.
 */
@Composable
fun P503ResultScreen(state: P503AppState) {
    val message =
        when (state) {
            P503AppState.Created -> "支出已创建"
            P503AppState.NoChange -> "支出无变化（已存在相同记录）"
            P503AppState.Recovered -> "支出已恢复"
            P503AppState.UnknownCommit -> "提交结果未知，正在核对中…"
            else -> return
        }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state == P503AppState.UnknownCommit) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

@Composable
internal fun P503SubmittingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "正在提交…",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
