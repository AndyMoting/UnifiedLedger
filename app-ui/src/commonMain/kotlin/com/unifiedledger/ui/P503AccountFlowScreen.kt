package com.unifiedledger.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.P806AccountPostingRow
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.ui.theme.LocalP8Palette

/**
 * P8-00 b3 (V-5; D-301 spec sections 1.3/2.6); read source replaced by P8-06 batch b (D-343
 * sections 1.3/3.2.2/3.7): the read-only account-flow secondary surface — the canvas-06 face
 * reached from an asset-screen account row. The header is the account's balance headline + the
 * 「名称 · 类型 · 币种」-style副行 the host projected at open time; the rows are the account's own
 * postings from one P806 account snapshot per request (page 100, the read's own order and cursor,
 * full history), each carrying 「资金发生日期」 (the occurred-at Shanghai local day, the same axis
 * as the read's sort) + note/kind + that account's exact signed leg amount through
 * [formatMinorUnits] (Long minor units, never floating point). The success count is the
 * snapshot's exact posting count rendered as 「N 条账户变动」.
 *
 * 显式六态 (D-343 sections 3.7/3.8): an unwired graph, the not-loaded window, an in-flight read, a typed
 * failure, an empty success and a non-empty success are distinct faces. A loading or failed
 * request keeps the last success visible and LABELS it as old context — it never renders as a new
 * zero; only an empty SUCCESS renders 「该账户暂无流水」. The typed failure copy is per failure
 * reason (a catalog advance says the retry starts from the first page).
 *
 * The body scrolls ([androidx.compose.foundation.verticalScroll]); the surface's [ScrollState] is
 * hoisted the way the assets list already is, so the position sampled when the reconciliation
 * queue opened is restored on return and consumed once through `ListScrollRestored`.
 *
 * Zero write paths, zero reconciliation writes, zero accounting semantics change: the surface is
 * display-only; the exits are 返回 (the host's Back dispatch) and the body/footer's
 * load-more/retry (both read-only page reads). P8-06 typed returnTo 批 1 (D-360/D-361) adds the
 * ONE read-only detail entry: a posting row opens that transaction's detail carrying this whole
 * surface as the typed return target ([onSelectTransaction]); the detail entered this way renders
 * none of the correction/void/annotation entries ([detailSubSurfaceEntriesVisible]), and 返回
 * restores this surface whole (loaded rows, count and scroll position preserved).
 */
@Composable
internal fun P503AccountFlowScreen(
    state: P503AppState.AccountFlow,
    accountNames: Map<AccountId, String>,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    // P8-06 typed returnTo 批 1 (D-360/D-361): the posting row's read-only detail entry. The host
    // wires it only when the detail read is wired (the same capability-probe pattern as
    // onOpenReconciliation), so an unwired graph renders no dead affordance.
    onSelectTransaction: ((TransactionId) -> Unit)? = null,
    onOpenReconciliation: (() -> Unit)? = null,
    registerListScroll: ((androidx.compose.foundation.ScrollState) -> Unit)? = null,
    restoreScrollTo: ListScrollPosition? = null,
    onScrollRestored: () -> Unit = {},
) {
    val palette = LocalP8Palette.current
    // 返回保滚动位 (F6): the queue entry samples THIS state and the queue's back restores it.
    val listScrollState = rememberScrollState()
    LaunchedEffect(registerListScroll) { registerListScroll?.invoke(listScrollState) }
    val pendingRestore = restoreScrollTo
    LaunchedEffect(pendingRestore) {
        if (pendingRestore != null) {
            runCatching { listScrollState.scrollTo(pendingRestore.firstVisibleItemScrollOffset) }
            onScrollRestored()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(listScrollState).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("账户流水", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // P8-01a i3a (spec section 2.2 entry A): the 「账户对账」 action of this account, left of
            // 返回 (the canvas-06 header row). Null when the composition did not wire the read face,
            // so no dead affordance is rendered; the button stays an 动作, never a "there is no
            // reconciliation here" claim.
            if (onOpenReconciliation != null) {
                OutlinedButton(
                    onClick = onOpenReconciliation,
                    modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = RECONCILIATION_TITLE },
                ) { Text(RECONCILIATION_TITLE) }
            }
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
        val accountName = accountNames[state.accountId] ?: state.accountId.value
        val face = accountFlowBodyFace(state)
        when (face) {
            AccountFlowBodyFace.UNWIRED -> Text(ACCOUNT_FLOW_UNWIRED_TEXT, style = MaterialTheme.typography.bodyLarge)
            AccountFlowBodyFace.NOT_LOADED -> {
                // Explicit first-page action (D-343 section 3.7: 未请求 is its own state). A surface
                // whose first read never landed — e.g. the user entered the reconciliation queue
                // before it completed — must be able to ask again by hand; nothing is re-read
                // automatically and the late result was already discarded.
                Text(ACCOUNT_FLOW_NOT_LOADED_TEXT, style = MaterialTheme.typography.bodyLarge)
                accountFlowLoadActionLabel(face)?.let { label ->
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onLoadMore) { Text(label) }
                }
            }
            AccountFlowBodyFace.LOADING -> Text(ACCOUNT_FLOW_LOADING_TEXT, style = MaterialTheme.typography.bodyLarge)
            AccountFlowBodyFace.FAILED -> AccountFlowRetryFace(state, onRetryLoadMore)
            AccountFlowBodyFace.EMPTY -> Text(ACCOUNT_FLOW_EMPTY_TEXT, style = MaterialTheme.typography.bodyLarge)
            AccountFlowBodyFace.LIST -> {
                // The count is the last successful snapshot's exact posting count; a surface that
                // never succeeded has none (the earlier faces cover it), so no zero is invented.
                state.totalCount?.let { totalCount ->
                    Text(
                        accountFlowCountText(totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceVariant,
                    )
                }
                // 旧上下文 (D-343 section 3.7): a loading/failed request keeps the last success
                // visible, labelled as the previous payload — never disguised as a new zero.
                accountFlowOldContextText(state)?.let { oldContext ->
                    Text(
                        oldContext,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceVariant,
                    )
                }
                // A failed continuation shows its typed copy beside the previous payload — the
                // failure is never disguised as the last page. While the retry is in flight the
                // footer already says so, so the stale failure copy is not repeated.
                if (!state.rowsLoading) {
                    state.failure?.let { failure ->
                        Text(
                            accountFlowFailureText(failure),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.loadedRows.forEach { row ->
                    P8AccountFlowRow(row, accountName, onSelectTransaction)
                    HorizontalDivider(color = palette.divider)
                }
                AccountFlowFooter(state, onLoadMore, onRetryLoadMore)
            }
        }
    }
}

/**
 * P8-06 batch b (D-343 sections 3.7/3.8): the body-face decision over the surface's explicit state.
 * [UNWIRED] is a graph without the account read; [NOT_LOADED] is the not-requested window (never
 * an empty claim; it offers the explicit 加载账户流水 action); [LOADING] is a first-page read in
 * flight; [FAILED] is a typed failure with no success yet; [EMPTY] is an empty SUCCESS; [LIST] is a
 * non-empty success — also the shape where a loading/failed continuation keeps the previous rows
 * with their old-context label and the footer's loading/retry affordance.
 */
internal enum class AccountFlowBodyFace {
    UNWIRED,
    NOT_LOADED,
    LOADING,
    FAILED,
    EMPTY,
    LIST,
}

/** The pure body-face decision the screen branches on (see [AccountFlowBodyFace]). */
internal fun accountFlowBodyFace(state: P503AppState.AccountFlow): AccountFlowBodyFace =
    when {
        state.unwired -> AccountFlowBodyFace.UNWIRED
        state.totalCount == null && state.rowsLoading -> AccountFlowBodyFace.LOADING
        state.totalCount == null && state.failure != null -> AccountFlowBodyFace.FAILED
        state.totalCount == null -> AccountFlowBodyFace.NOT_LOADED
        state.totalCount == 0 && state.failure == null -> AccountFlowBodyFace.EMPTY
        else -> AccountFlowBodyFace.LIST
    }

/** The explicit not-loaded copy: the surface has issued no successful read yet. */
internal const val ACCOUNT_FLOW_NOT_LOADED_TEXT: String = "尚未加载账户流水。"

/** The first-page read in flight with no previous success. */
internal const val ACCOUNT_FLOW_LOADING_TEXT: String = "正在加载账户流水…"

/** The graph wires no account-postings read: its own state, never an empty account. */
internal const val ACCOUNT_FLOW_UNWIRED_TEXT: String = "账户流水不可用（本版本未接线）。"

/** The empty SUCCESS copy: only an empty success may render it. */
internal const val ACCOUNT_FLOW_EMPTY_TEXT: String = "该账户暂无流水。"

/** The success count in the business wording (D-343 section 3.4: 「N 条账户变动」). */
internal fun accountFlowCountText(totalCount: Int): String = "$totalCount 条账户变动"

/** The explicit first-page action label (D-343 section 3.7: 未请求 owns its own load action). */
internal const val ACCOUNT_FLOW_LOAD_ACTION_TEXT: String = "加载账户流水"

/**
 * The explicit action a face offers, or `null` when it has none (the `LIST` face pages through its
 * footer; loading/failed/empty/unwired add nothing here). The [AccountFlowBodyFace.NOT_LOADED]
 * button calls the SAME request handler as 加载更多 — it never re-reads automatically and the
 * already-discarded late result is never re-accepted.
 */
internal fun accountFlowLoadActionLabel(face: AccountFlowBodyFace): String? = if (face == AccountFlowBodyFace.NOT_LOADED) ACCOUNT_FLOW_LOAD_ACTION_TEXT else null

/**
 * P8-06 batch b (D-343 section 3.7): the old-context label of a previous success kept beside a
 * loading or failed request (`null` = no previous success, or the request is settled). The label
 * never turns a failed/loading request into a zero or a fresh payload.
 */
internal fun accountFlowOldContextText(state: P503AppState.AccountFlow): String? =
    if (state.totalCount != null && (state.rowsLoading || state.failure != null)) {
        "以下为上次成功读取的内容，可能已过期。"
    } else {
        null
    }

/** The typed failure copy — one reason per line, never a zero and never an empty account. */
internal fun accountFlowFailureText(failure: P806SnapshotFailure): String =
    when (failure) {
        P806SnapshotFailure.Unavailable -> "账户流水读取失败，可点击重试。"
        P806SnapshotFailure.CatalogVersionMismatch -> "账目目录已更新，请从第一页重试。"
        is P806SnapshotFailure.CatalogReference -> "账户目录引用不一致（缺账户 / 跨账本 / 币种不符），不可用。"
        P806SnapshotFailure.MissingProjection -> "账目缺少统计时间投影，账户流水不可用。"
        P806SnapshotFailure.OccurredAtUnparseable -> "存在无法解析的资金发生时间，账户流水不可用。"
        P806SnapshotFailure.InvalidState -> "账户流水数据不一致，不可用。"
        P806SnapshotFailure.InvalidWindow -> "账户流水时间范围无效，不可用。"
    }

/**
 * The typed-failure face with a REAL retry affordance (an OutlinedButton, the footer's RETRY
 * shape) — the failure copy is never a clickable-looking dead text. While the retry read is in
 * flight the face says so instead of offering an inert button (一次一页 absorbs a duplicate click).
 */
@Composable
private fun AccountFlowRetryFace(
    state: P503AppState.AccountFlow,
    onRetry: () -> Unit,
) {
    if (state.rowsLoading) {
        Text(ACCOUNT_FLOW_LOADING_TEXT, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            accountFlowFailureText(state.failure ?: P806SnapshotFailure.Unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

/**
 * One posting row of the account surface (the canvas-06 行 = 资金发生日期 + 备注/类目 + 该账户腿
 * 金额). The visible line carries 「资金发生日期」 and the occurred-at Shanghai local day — the same
 * axis the page is sorted by (D-343 section 3.2.2) — the note (else the effective kind) and this
 * account's exact signed leg amount. TalkBack reads the same values through
 * [accountFlowRowContentDescription].
 *
 * P8-06 typed returnTo 批 1 (D-360/D-361): the row opens the transaction's read-only detail (the
 * [LedgerEntryFlowRow] click shape, the 「查看交易详情」 label) when the host wired
 * [P503AccountFlowScreen.onSelectTransaction]; a null callback (an unwired detail read) renders
 * the row without a dead click affordance (F1). The click announces the affordance through the
 * click label while the exact values stay in the visible texts and the semantics node.
 */
@Composable
private fun P8AccountFlowRow(
    row: P806AccountPostingRow,
    accountName: String,
    onSelectTransaction: ((TransactionId) -> Unit)?,
) {
    val palette = LocalP8Palette.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // P8-06 typed returnTo 批 1: the detail entry (照 LedgerEntryFlowRow 的形制). The
                // row is otherwise unchanged — same padding, same semantics node.
                .clickable(
                    enabled = onSelectTransaction != null,
                    onClickLabel = "查看交易详情",
                ) {
                    onSelectTransaction?.invoke(row.transactionId)
                }
                // C04: the row announces its exact values (the date axis, note, the leg with sign
                // and currency) as one node.
                .semantics { contentDescription = accountFlowRowContentDescription(row, accountName) }
                .padding(vertical = 6.dp),
    ) {
        Text(
            accountFlowRowHeadlineText(row),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "$accountName " +
                formatMinorUnits(row.amount.minorUnits, row.amount.currency.precision) +
                " ${row.amount.currency.code}",
            style = MaterialTheme.typography.bodySmall,
            color = if (row.amount.minorUnits < 0L) palette.expense else palette.onSurface,
        )
    }
}

/** The visible headline of one posting row: 「资金发生日期」 + the Shanghai local day + the note/kind. */
internal fun accountFlowRowHeadlineText(row: P806AccountPostingRow): String = "资金发生日期 " + occurredAtLocalDateText(row.occurredAt) + " · " + (row.note ?: row.kind.name)

/**
 * The posting row's accessibility label: effective kind, the SAME 「资金发生日期」 + Shanghai local
 * day as the visible line, the note and the exact signed leg amount with the account name, sign and
 * currency code (the [flowRowContentDescription] family shape).
 */
internal fun accountFlowRowContentDescription(
    row: P806AccountPostingRow,
    accountName: String,
): String {
    val head = "${row.kind}，资金发生日期 " + occurredAtLocalDateText(row.occurredAt) + (row.note?.let { "，备注 $it" } ?: "")
    val leg =
        "$accountName " +
            formatMinorUnits(row.amount.minorUnits, row.amount.currency.precision) +
            " ${row.amount.currency.code}"
    return "$head；$leg"
}

/**
 * P8-06 batch b: the footer decision (the [flowFooterState] family, over the account surface's own
 * fields). While the posting walk continues the 加载更多 affordance stays; 已到末页 is the honest
 * P806 cursor end, never a fabricated total.
 */
internal fun accountFlowFooterState(state: P503AppState.AccountFlow): FlowFooterState =
    when {
        state.rowsLoading -> FlowFooterState.LOADING
        state.failure != null -> FlowFooterState.RETRY
        state.hasMorePages -> FlowFooterState.FETCH_MORE
        else -> FlowFooterState.END
    }

@Composable
private fun AccountFlowFooter(
    state: P503AppState.AccountFlow,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
) {
    P8FlowFooter(accountFlowFooterState(state), onLoadMore, onRetryLoadMore)
}

/**
 * The keyset-page footer shared by every paged list of the P8-00/P8-01a read surfaces (F12b): the
 * account flow and the reconciliation queue render the SAME copy and the same affordances instead
 * of a second three-line copy. [FlowFooterState] is the frozen vocabulary; 已到末页 is the honest
 * keyset end of the surface's own read.
 */
@Composable
internal fun P8FlowFooter(
    state: FlowFooterState,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    when (state) {
        FlowFooterState.LOADING -> Text("正在加载更多…", style = MaterialTheme.typography.bodySmall)
        FlowFooterState.RETRY -> OutlinedButton(onClick = onRetryLoadMore) { Text("加载失败，点击重试") }
        FlowFooterState.FETCH_MORE -> OutlinedButton(onClick = onLoadMore) { Text("加载更多") }
        FlowFooterState.END -> Text("已到末页", style = MaterialTheme.typography.bodySmall)
    }
}
