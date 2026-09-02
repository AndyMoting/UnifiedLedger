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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Result presentation (spec sections 7.1/7.3.6). Created/NoChange/Recovered are transient
 * banners followed by an authoritative refresh. UnknownCommit has its own presentation
 * ([P503UnknownCommitScreen], P5-04.3): no automatic retry, no requestId replacement.
 */
@Composable
fun P503ResultScreen(state: P503AppState) {
    val message =
        when (state) {
            P503AppState.Created -> "支出已创建"
            P503AppState.NoChange -> "支出无变化（已存在相同记录）"
            P503AppState.Recovered -> "支出已恢复"
            else -> return
        }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

/**
 * Unknown-commit presentation (P5-04.3): a resolving banner while the entry check is in
 * flight (NONE), and an actionable stay screen once the outcome is recorded. The text never
 * claims success or failure: absence cannot prove rollback (D-119).
 */
@Composable
internal fun P503UnknownCommitScreen(
    state: P503AppState.UnknownCommit,
    onRetryCheck: () -> Unit,
) {
    when (state.lastCheckOutcome) {
        UnknownCommitCheckOutcome.NONE -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "提交结果未知，正在核对中…",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
        UnknownCommitCheckOutcome.ABSENT ->
            P503UnknownCommitStayScreen(
                message = "提交结果未知：账本中未找到该提交的入库记录。",
                onRetryCheck = onRetryCheck,
            )
        UnknownCommitCheckOutcome.UNAVAILABLE ->
            P503UnknownCommitStayScreen(
                message = "提交结果未知：核对暂不可用（本地数据库读取失败）。",
                onRetryCheck = onRetryCheck,
            )
    }
}

@Composable
private fun P503UnknownCommitStayScreen(
    message: String,
    onRetryCheck: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Spacer(Modifier.height(16.dp))
        // No manual minimumInteractiveComponentSize(): it duplicates material3's built-in
        // 48dp touch-target enforcement and creates a dead-zone hit layer (D-127).
        Button(onClick = onRetryCheck) {
            Text("重新核对")
        }
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
