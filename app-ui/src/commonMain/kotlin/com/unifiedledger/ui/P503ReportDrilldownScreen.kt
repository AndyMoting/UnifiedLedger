package com.unifiedledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.ui.theme.LocalP8Palette

/**
 * P8-06 batch 3 (D-362 1/2; spec sections 3.7/3.9/3.10.4): the read-only report drilldown — ONE
 * full-screen secondary face shared by the report period entry, a level-1 category's 「全部明细」
 * entry and a level-2 category's own entry. The header names the typed target (and, when the last
 * landing captured one, its read instant); the body is the ordinary detail page in the read's own
 * keyset order, grouped by the reporting day with the shared [p8GroupByDay] and rendered with the
 * shared [LedgerEntryFlowRow] — no second row/grouping implementation exists.
 *
 * 显式六态 ([P806DrilldownFace]): an unwired graph, the not-loaded window, an in-flight read, a
 * typed failure, an empty success and a non-empty success are distinct faces. A loading or failed
 * request keeps the last success visible and LABELS it as old context; only an empty SUCCESS renders
 * the explicit empty copy. The header label carries the target and its read instant in every face,
 * so an old payload is never mistaken for the current one.
 *
 * Zero write entries: the only interactions are 返回 (the host's Back dispatch), the page reads
 * (first page / 加载更多 / retry) and the row's read-only transaction detail entry, which carries
 * this surface's own sampled scroll through [onSelectTransaction] (the V-5 one-shot mechanism).
 */
@Composable
internal fun P503ReportDrilldownScreen(
    state: P503AppState.ReportDrilldown,
    accountNames: Map<AccountId, String>,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    // The read-only detail entry. Null renders no dead click (an unwired detail read, the F1
    // precedent); the host passes it only when the detail face is probed.
    onSelectTransaction: ((TransactionId, ListScrollPosition?) -> Unit)?,
    restoreScrollTo: ListScrollPosition? = null,
    onScrollRestored: () -> Unit = {},
) {
    val palette = LocalP8Palette.current
    val listScrollState = rememberScrollState()
    // The read-only detail round trip restores this page's own position, consumed once.
    val pendingRestore = restoreScrollTo
    LaunchedEffect(pendingRestore) {
        if (pendingRestore != null) {
            runCatching { listScrollState.scrollTo(pendingRestore.firstVisibleItemScrollOffset) }
            onScrollRestored()
        }
    }
    val sampleScroll: () -> ListScrollPosition = { ListScrollPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = listScrollState.value) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(listScrollState).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("报表明细", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("返回") }
        }
        Spacer(Modifier.height(4.dp))
        // The typed target + its read instant, present in every face (a loading/failed request must
        // label the old payload whose rows it keeps visible).
        Text(
            p806DrilldownHeaderLabel(state),
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        when (p806DrilldownFace(state)) {
            P806DrilldownFace.UNWIRED ->
                Text(P806_DRILLDOWN_UNWIRED_TEXT, style = MaterialTheme.typography.bodyLarge)
            P806DrilldownFace.NOT_LOADED -> {
                Text(P806_DRILLDOWN_NOT_LOADED_TEXT, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onLoadMore) { Text(P806_DRILLDOWN_LOAD_ACTION_TEXT) }
            }
            P806DrilldownFace.LOADING -> {
                Text(P806_DRILLDOWN_LOADING_TEXT, style = MaterialTheme.typography.bodyMedium)
                // A continuation keeps the previous rows visible, labelled as old context, with the
                // footer saying the next page is in flight; the first-page read has no rows to keep.
                if (state.loadedRows.isNotEmpty()) {
                    P806DrilldownOldRows(state, accountNames, sampleScroll, onSelectTransaction)
                    P8FlowFooter(p806DrilldownFooterState(state), onLoadMore, onRetryLoadMore)
                }
            }
            P806DrilldownFace.FAILED -> {
                Text(
                    p806DrilldownFailureText(state.failure ?: P806SnapshotFailure.Unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                P806DrilldownOldRows(state, accountNames, sampleScroll, onSelectTransaction)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onRetryLoadMore) { Text("重试") }
            }
            P806DrilldownFace.READY_EMPTY -> {
                state.totalCount?.let { totalCount ->
                    Text(p806DrilldownCountText(totalCount), style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
                Text(P806_DRILLDOWN_EMPTY_TEXT, style = MaterialTheme.typography.bodyLarge)
            }
            P806DrilldownFace.READY_NON_EMPTY -> {
                state.totalCount?.let { totalCount ->
                    Text(p806DrilldownCountText(totalCount), style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
                P806DrilldownRows(state, accountNames, sampleScroll, onSelectTransaction)
                P8FlowFooter(p806DrilldownFooterState(state), onLoadMore, onRetryLoadMore)
            }
        }
    }
}

/** The explicit not-loaded copy: the surface has issued no successful read yet. */
internal const val P806_DRILLDOWN_NOT_LOADED_TEXT: String = "尚未加载明细。"

/** The first-page/continuing read in flight. */
internal const val P806_DRILLDOWN_LOADING_TEXT: String = "正在读取明细…"

/** The graph wires no drilldown read: its own state, never an empty result. */
internal const val P806_DRILLDOWN_UNWIRED_TEXT: String = "报表明细不可用（本版本未接线）。"

/** The empty SUCCESS copy: only an empty success may render it. */
internal const val P806_DRILLDOWN_EMPTY_TEXT: String = "该条件下暂无明细。"

/** The explicit first-page action label (未请求 owns its own load action). */
internal const val P806_DRILLDOWN_LOAD_ACTION_TEXT: String = "加载明细"

/** The success count line. */
internal fun p806DrilldownCountText(totalCount: Int): String = "共 $totalCount 笔明细"

/** The captioned label of rows kept beside a loading/failed request (`null` when nothing to label). */
internal fun p806DrilldownOldContextText(state: P503AppState.ReportDrilldown): String? =
    if (state.totalCount != null && (state.rowsLoading || state.failure != null)) {
        "以下为上次成功读取的内容，可能已过期。"
    } else {
        null
    }

/** The typed failure copy — one reason per line, never a zero and never an empty claim. */
internal fun p806DrilldownFailureText(failure: P806SnapshotFailure): String =
    when (failure) {
        P806SnapshotFailure.Unavailable -> "明细读取失败，可点击重试。"
        P806SnapshotFailure.CatalogVersionMismatch -> "账目目录已更新，请从第一页重试。"
        is P806SnapshotFailure.CatalogReference -> "目录引用不一致（缺账户 / 跨账本 / 币种不符），明细不可用。"
        P806SnapshotFailure.MissingProjection -> "账目缺少统计时间投影，明细不可用。"
        P806SnapshotFailure.OccurredAtUnparseable -> "存在无法解析的资金发生时间，明细不可用。"
        P806SnapshotFailure.InvalidState -> "明细数据不一致，不可用。"
        P806SnapshotFailure.InvalidWindow -> "明细时间范围无效，不可用。"
    }

/** The keyset-page footer decision over the drilldown's own fields (the shared [FlowFooterState]). */
internal fun p806DrilldownFooterState(state: P503AppState.ReportDrilldown): FlowFooterState =
    when {
        state.rowsLoading -> FlowFooterState.LOADING
        state.failure != null -> FlowFooterState.RETRY
        state.hasMorePages -> FlowFooterState.FETCH_MORE
        else -> FlowFooterState.END
    }

/** The page's rows grouped by reporting day, each row opening its transaction's read-only detail. */
@Composable
private fun P806DrilldownRows(
    state: P503AppState.ReportDrilldown,
    accountNames: Map<AccountId, String>,
    sampleScroll: () -> ListScrollPosition,
    onSelectTransaction: ((TransactionId, ListScrollPosition?) -> Unit)?,
) {
    val palette = LocalP8Palette.current
    val enabled = onSelectTransaction != null
    p8GroupByDay(state.loadedRows).forEach { group ->
        Text(
            "${group.date} · ${group.count} 笔",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        group.rows.forEach { row ->
            LedgerEntryFlowRow(
                row = row,
                accountNames = accountNames,
                // The row samples THIS page's scroll synchronously at click time (no async hop).
                onSelectTransaction = { transactionId -> onSelectTransaction?.invoke(transactionId, sampleScroll()) },
                interactionsEnabled = enabled,
                // V-12: a balanced posting set may legitimately carry Long.MIN_VALUE, which the
                // legacy formatMinorUnits renders off by one. Both the visible lines and the
                // TalkBack label go through the report surface's exact formatter.
                amountText = ::p806MinorUnitsText,
            )
            HorizontalDivider(color = palette.divider)
        }
    }
}

/**
 * The previous success kept visible beside a loading or failed request, captioned as old context.
 * A surface with no previous success renders nothing (the earlier faces cover it).
 */
@Composable
private fun P806DrilldownOldRows(
    state: P503AppState.ReportDrilldown,
    accountNames: Map<AccountId, String>,
    sampleScroll: () -> ListScrollPosition,
    onSelectTransaction: ((TransactionId, ListScrollPosition?) -> Unit)?,
) {
    p806DrilldownOldContextText(state)?.let { oldContext ->
        Text(oldContext, style = MaterialTheme.typography.bodySmall)
    }
    if (state.loadedRows.isNotEmpty()) {
        state.totalCount?.let { totalCount ->
            Text(p806DrilldownCountText(totalCount), style = MaterialTheme.typography.bodySmall)
        }
    }
    P806DrilldownRows(state, accountNames, sampleScroll, onSelectTransaction)
}
