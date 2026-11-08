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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.domain.AccountId
import com.unifiedledger.ui.theme.LocalP8Palette

/**
 * P8-00 b3 (V-5; D-301 spec sections 1.3/2.6): the read-only account-flow secondary surface —
 * the canvas-06 face reached from an asset-screen account row. The header is the account's
 * balance headline + the 「名称 · 类型 · 币种」-style副行 the host projected at open time; the
 * rows are the account's legs filtered from the existing whole-ledger keyset pages (200 行/页
 * 约定内的内存过滤， no data-layer change), each carrying date + note/kind + that account's exact
 * leg amount through [formatMinorUnits] (Long minor units, never floating point).
 *
 * 诚实呈现: no per-account total exists, so NO count is ever claimed beyond the rows actually
 * loaded ([P503AppState.AccountFlow.loadedCount], rendered as-is); the footer follows the loaded
 * whole-ledger cursor — 加载更多 while the walk continues, 已到末页 once it ends (an honest
 * whole-ledger end, not a per-account claim), and the typed retry state keeps every loaded row
 * visible beside it (R-Q06-4 fail-closed, never a silent truncation). An empty flow renders the
 * honest empty copy — never a fabricated zero (the canvas-06 空态 ruling; the widget-snapshot
 * 不可用-over-zero precedent).
 *
 * 空流水判定 is itself a pure function ([accountFlowEmptyText]): an empty first page of a walk
 * that has ended is the honest 「该账户暂无流水」; an empty first page with the walk still open
 * says the account's flow has not loaded yet — a not-loaded state never reads as empty (the F2
 * 月度数据未加载 precedent). The FIRST read's typed Unavailable outranks every empty copy
 * ([accountFlowBodyFace], the F-Q1 fix round): an unavailable read renders the failure face with
 * its real retry affordance — it is never degraded into 「该账户暂无流水」.
 *
 * The body scrolls ([androidx.compose.foundation.verticalScroll], the F-Q3 fix round): multi-page
 * content is physically reachable; the surface's own scroll position is still destroyed on close
 * (the registered batch trade-off — no late write-back exists).
 *
 * Zero write paths, zero reconciliation writes, zero accounting semantics change: the surface is
 * display-only; the only exits are 返回 (the host's CloseAccountFlow/Back dispatch) and the
 * body/footer's load-more/retry (both read-only page reads).
 */
@Composable
internal fun P503AccountFlowScreen(
    state: P503AppState.AccountFlow,
    accountNames: Map<AccountId, String>,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
) {
    val palette = LocalP8Palette.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("账户流水", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
        // 头部 (canvas 06): the balance headline + the host-projected副行 (「名称 · 类型 · 币种」
        // display text resolved from the same authoritative snapshot × balances the asset row
        // rendered). The balance is the account's exact display balance (minor units + currency),
        // formatted through the exact pipeline — never floating point.
        Text(
            state.balanceCurrency.code + " " + formatMinorUnits(state.balanceMinorUnits, state.balanceCurrency.precision),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            state.balanceLine,
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        when (accountFlowBodyFace(state)) {
            // F-Q1 fix round: the typed Unavailable FIRST read renders the failure face with its
            // real retry affordance (F-Q2) — never the 「该账户暂无流水」 empty copy.
            AccountFlowBodyFace.UNAVAILABLE -> AccountFlowRetryFace(state, onRetryLoadMore)
            AccountFlowBodyFace.EMPTY_ENDED ->
                Text(
                    accountFlowEmptyText(state),
                    style = MaterialTheme.typography.bodyLarge,
                )
            // The list face — also the zero-rows-with-an-open-walk shape (已加载 0 笔 + 加载更多,
            // never an empty claim); a failed more-page keeps it with the footer's RETRY.
            AccountFlowBodyFace.LIST -> {
                Text(
                    "已加载 ${state.loadedCount} 笔",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceVariant,
                )
                state.loadedRows.forEach { row ->
                    P8AccountFlowRow(row, accountNames)
                    HorizontalDivider(color = palette.divider)
                }
                AccountFlowFooter(state, onLoadMore, onRetryLoadMore)
            }
        }
    }
}

/**
 * P8-00 b3 fix round (F-Q1): the body face decision over the surface's own markers. A typed
 * Unavailable FIRST read rides the state as [P503AppState.AccountFlow.rowsLoadFailed] with zero
 * rows, so the failure face outranks the empty copy (a typed failure never degrades into
 * 暂无流水, R-Q06-4). A more-page failure WITH loaded rows keeps the LIST face — the loaded rows
 * stay visible beside the footer's retry (the fail-closed 失败保行 discipline, unchanged); the
 * zero-rows open-walk shape stays the LIST face (已加载 0 笔 + 加载更多, never an empty claim),
 * and the walk-ended zero-rows shape is [accountFlowEmptyText]'s honest empty copy. Every other
 * previously reachable shape renders exactly as before the fix round; the first-read failure —
 * which used to degrade into the empty copy — gained its own face, and the zero-rows more-page
 * failure with an open cursor moved from the dead copy into the UNAVAILABLE face (FD-1, strictly
 * more honest).
 *
 * [UNAVAILABLE] is the read-failed-with-zero-rows face (its real retry affordance, F-Q2);
 * [EMPTY_ENDED] is the walk-ended zero-rows honest empty copy; [LIST] is the list face (count +
 * rows + footer, also the zero-rows open-walk shape).
 */
internal enum class AccountFlowBodyFace {
    UNAVAILABLE,
    EMPTY_ENDED,
    LIST,
}

/** The pure body-face decision the screen branches on (see [AccountFlowBodyFace]). */
internal fun accountFlowBodyFace(state: P503AppState.AccountFlow): AccountFlowBodyFace =
    when {
        state.rowsLoadFailed && state.loadedRows.isEmpty() -> AccountFlowBodyFace.UNAVAILABLE
        state.loadedRows.isEmpty() && !state.hasMoreWholeLedgerPages -> AccountFlowBodyFace.EMPTY_ENDED
        else -> AccountFlowBodyFace.LIST
    }

/**
 * P8-00 b3 fix round (F-Q2): the failure face with a REAL retry affordance (an OutlinedButton,
 * the footer's RETRY shape) — the failure copy is never a clickable-looking dead text. While the
 * retry read is in flight the face says so instead of offering an inert button (一次一页 absorbs
 * a duplicate click; the honest loading copy, the footer's LOADING precedent).
 */
@Composable
private fun AccountFlowRetryFace(
    state: P503AppState.AccountFlow,
    onRetry: () -> Unit,
) {
    if (state.rowsLoading) {
        Text("正在重试…", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            "流水读取失败，可点击重试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

/**
 * P8-00 b3: one flow row of the account surface (the canvas-06 行 = 日期 + 备注/类目 + 该账户腿
 * 金额). The visible line carries the statistics date, the note (else the effective kind) and the
 * exact amounts of the row's legs that touch the flow account (the reducer only accumulates rows
 * whose postings include the account, so every posting shown is that account's — a transfer shows
 * both its legs here, each exact, never a fabricated net). TalkBack reads the whole row's exact
 * values through [accountFlowRowContentDescription].
 */
@Composable
private fun P8AccountFlowRow(
    row: LedgerEntryRow,
    accountNames: Map<AccountId, String>,
) {
    val palette = LocalP8Palette.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                // C04: the row announces its exact values (date, note, every leg with sign and
                // currency) as one node.
                .semantics { contentDescription = accountFlowRowContentDescription(row, accountNames) },
    ) {
        Text(
            accountFlowRowHeadlineText(row),
            style = MaterialTheme.typography.bodyMedium,
        )
        row.postings.forEach { posting ->
            Text(
                "${accountNames[posting.accountId] ?: posting.accountId.value} " +
                    formatMinorUnits(posting.amount.minorUnits, posting.amount.currency.precision) +
                    " ${posting.amount.currency.code}",
                style = MaterialTheme.typography.bodySmall,
                color = if (posting.amount.minorUnits < 0L) palette.expense else palette.onSurface,
            )
        }
    }
}

/** P8-00 b3: the visible headline of one flow row — statistics date + note (else the kind). */
internal fun accountFlowRowHeadlineText(row: LedgerEntryRow): String {
    val date = row.statisticsAt.toString().take(10)
    return "$date · " + (row.note ?: row.kind.name)
}

/** P8-00 b3: the honest empty copy (the not-loaded state never reads as empty, the F2 precedent). */
internal fun accountFlowEmptyText(state: P503AppState.AccountFlow): String =
    if (state.nextWholeLedgerCursor != null) {
        "该账户的流水尚未加载完成。"
    } else {
        "该账户暂无流水。"
    }

/**
 * P8-00 b3: the flow row's accessibility label (C04, the [flowRowContentDescription] family,
 * P503LedgerViewPresentation.kt:412 shape — F-Q5 fix round): effective kind, the statistics time,
 * the note and every exact posting amount with the account NAME (a bare id never reaches
 * TalkBack when the name is known), sign and currency code.
 */
internal fun accountFlowRowContentDescription(
    row: LedgerEntryRow,
    accountNames: Map<AccountId, String>,
): String {
    val head = "${row.kind}，统计时间 ${row.statisticsAt}" + (row.note?.let { "，备注 $it" } ?: "")
    val legs =
        row.postings.joinToString("；") { posting ->
            "${accountNames[posting.accountId] ?: posting.accountId.value} " +
                formatMinorUnits(posting.amount.minorUnits, posting.amount.currency.precision) +
                " ${posting.amount.currency.code}"
        }
    return if (legs.isEmpty()) head else "$head；$legs"
}

/**
 * P8-00 b3: the footer decision (the [flowFooterState] family, over the account surface's own
 * fields). While the whole-ledger walk continues the 加载更多 affordance stays; 已到末页 is the
 * honest whole-ledger end (a row could still land in a later page only if the walk is open).
 */
internal fun accountFlowFooterState(state: P503AppState.AccountFlow): FlowFooterState =
    when {
        state.rowsLoading -> FlowFooterState.LOADING
        state.rowsLoadFailed -> FlowFooterState.RETRY
        state.hasMoreWholeLedgerPages -> FlowFooterState.FETCH_MORE
        else -> FlowFooterState.END
    }

@Composable
private fun AccountFlowFooter(
    state: P503AppState.AccountFlow,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    when (accountFlowFooterState(state)) {
        FlowFooterState.LOADING -> Text("正在加载更多…", style = MaterialTheme.typography.bodySmall)
        FlowFooterState.RETRY -> OutlinedButton(onClick = onRetryLoadMore) { Text("加载失败，点击重试") }
        FlowFooterState.FETCH_MORE -> OutlinedButton(onClick = onLoadMore) { Text("加载更多") }
        FlowFooterState.END -> Text("已到末页", style = MaterialTheme.typography.bodySmall)
    }
}
