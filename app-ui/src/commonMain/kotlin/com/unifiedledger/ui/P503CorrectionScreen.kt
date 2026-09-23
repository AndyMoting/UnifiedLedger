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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RecycleBinRow
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.VoidReasonCode

// P7-05 slice 1b screens (D-156; spec sections 3.5/4.4). The three surfaces are thin renderers over
// the pure presentation decisions in P503CorrectionPresentation.kt: the correction form plus its
// field-by-field difference preview and explicit confirmation (「历史版本保留、旧版本失效」), the
// void confirmation page (frozen reason-code picker + optional bounded note + the impact statement),
// and the recycle-bin list with its nested restore confirmation. Every affordance takes a callback
// the composition root wires later (slice 1b Piece 4); an absent callback renders no dead button.
// The reason text reaches only visible text and `Modifier.semantics` labels — never a log line
// (V-21). The screens never read the database; names come from the caller's catalog projection.

/**
 * P7-05.B: the independent correction surface (spec section 4.4). The field form writes the pure
 * draft through [onUpdateField]; the difference preview is requested explicitly ([onPreview]) and
 * renders each frozen field's old value against the new one — a preview is a display-only read and
 * never commit permission (spec section 3.2). The confirmation states the frozen
 * 「历史版本保留、旧版本失效」 statement. [onConfirm] is the host commit call site: it is `null`
 * until the composition root wires the correction use case (slice 1b Piece 4), and a `null` commit
 * renders the disabled confirm affordance rather than a dead button. While a commit is in flight
 * the surface keeps its marker: the exits are absorbed by the caller's guards (提交中不得离开) and
 * the confirm button disables (提交中不重入).
 */
@Composable
internal fun P503TransactionEditScreen(
    state: P503AppState.TransactionEdit,
    currency: CurrencyUnit,
    categoryNames: Map<CategoryId, String>,
    accountNames: Map<AccountId, String>,
    categoryOptions: List<Pair<CategoryId, String>>,
    accountOptions: List<Pair<AccountId, String>>,
    onUpdateField: (TransactionCorrectionFieldUpdate) -> Unit,
    onPreview: () -> Unit,
    onConfirm: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onRecheck: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("修正交易", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        state.notice?.let { notice ->
            Text(
                p705NoticeText(notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = p705NoticeText(notice) },
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = state.draft.note,
            onValueChange = { onUpdateField(TransactionCorrectionFieldUpdate.Note(it)) },
            label = { Text("备注") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.draft.statisticsAtText,
            onValueChange = { onUpdateField(TransactionCorrectionFieldUpdate.StatisticsAt(it)) },
            label = { Text("统计时间") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.draft.amountText,
            onValueChange = { onUpdateField(TransactionCorrectionFieldUpdate.Amount(it)) },
            label = { Text("金额（${currency.code}）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("分类", style = MaterialTheme.typography.titleSmall)
        categoryOptions.forEach { (categoryId, label) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.draft.categoryId == categoryId,
                    onClick = { onUpdateField(TransactionCorrectionFieldUpdate.Category(categoryId)) },
                )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("资金账户", style = MaterialTheme.typography.titleSmall)
        accountOptions.forEach { (accountId, label) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.draft.fundingAccountId == accountId,
                    onClick = { onUpdateField(TransactionCorrectionFieldUpdate.FundingAccount(accountId)) },
                )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPreview) { Text("生成差异预览") }
        val preview = state.preview
        if (preview != null) {
            Text("差异预览（旧值 → 新值）", style = MaterialTheme.typography.titleMedium)
            transactionEditDiffLines(preview, categoryNames, accountNames, currency).forEach { line ->
                Text(
                    "${line.label}：${line.oldText} → ${line.newText}${if (line.changed) "（已修改）" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    // C04 precedent: each preview line announces its old and new value as one node.
                    modifier = Modifier.semantics { contentDescription = transactionEditDiffLineContentDescription(line) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(TRANSACTION_EDIT_CONFIRM_STATEMENT, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { onConfirm?.invoke() }, enabled = onConfirm != null && !state.submitting) { Text("确认修正") }
            if (onCancel != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel, enabled = !state.submitting) { Text("取消") }
            }
        }
        if (state.submitting) {
            Spacer(Modifier.height(8.dp))
            P705SubmittingRow(onRecheck = onRecheck, outcome = state.checkOutcome)
        }
    }
}

/** One preview line's TalkBack label: the field, its old value, its new value and whether it changed. */
internal fun transactionEditDiffLineContentDescription(line: TransactionEditDiffLine): String = "${line.label}，旧值 ${line.oldText}，新值 ${line.newText}${if (line.changed) "，已修改" else "，未修改"}"

/**
 * P7-05.C: the void confirmation page (spec section 4.4). The reason-code picker covers the frozen
 * five-value [VoidReasonCode] set; the note is optional and its bound is the domain's
 * (`VOID_REASON_NOTE_MAX_LENGTH`), never restated here. The impact statement names exactly what a
 * void does (the transaction leaves the month and the flow, and appears in the recycle bin). The
 * confirm button disables without an admissible reason (DP-11: the reason is mandatory) and while
 * a commit is in flight (提交中不重入); a submitting page does not leave (the caller guards the back).
 * [onConfirm] is the host commit call site and is `null` until Piece 4 wires the void use case; a
 * `null` commit renders the disabled confirm affordance rather than a dead button.
 */
@Composable
internal fun P503VoidConfirmScreen(
    state: P503AppState.VoidConfirm,
    onUpdateReason: (VoidReasonFieldUpdate) -> Unit,
    onConfirm: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onRecheck: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("作废交易", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        state.notice?.let { notice ->
            Text(
                p705NoticeText(notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                // V-17: an async rejection/stale outcome must be announced, not only painted —
                // the same treatment as the edit screen's notice and the failure-banner
                // `liveRegion = Assertive` precedent.
                modifier =
                    Modifier.semantics {
                        contentDescription = p705NoticeText(notice)
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        Text("作废原因", style = MaterialTheme.typography.titleSmall)
        VoidReasonCode.entries.forEach { code ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.reason.code == code,
                    onClick = { onUpdateReason(VoidReasonFieldUpdate.Code(code)) },
                )
                Text(voidReasonCodeLabel(code), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.reason.note,
            onValueChange = { onUpdateReason(VoidReasonFieldUpdate.Note(it)) },
            label = { Text("补充说明（可选，最多 ${com.unifiedledger.domain.VOID_REASON_NOTE_MAX_LENGTH} 字）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(VOID_IMPACT_STATEMENT, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { onConfirm?.invoke() },
                enabled = onConfirm != null && !state.submitting && voidReasonDraftRejection(state.reason) == null,
            ) {
                Text("确认作废")
            }
            if (onCancel != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel, enabled = !state.submitting) { Text("取消") }
            }
        }
        if (state.submitting) {
            Spacer(Modifier.height(8.dp))
            P705SubmittingRow(onRecheck = onRecheck, outcome = state.checkOutcome)
        }
    }
}

/**
 * P7-05.C: the recycle-bin list (spec section 4.4). The rows render in the query's frozen
 * `(void time DESC, transaction_id ASC)` total order and are never re-sorted here. Each row shows
 * its reason, void time and dependency explanation; the restore affordance opens the nested
 * confirmation only for an admissible row ([RecycleBinRow.restoreAdmissible]) — an inadmissible
 * row states the typed rejection instead of offering a dead action. A typed read failure keeps the
 * explicit failure copy and never reads as an empty bin (the R-Q06-4 discipline).
 */
@Composable
internal fun P503RecycleBinScreen(
    state: P503AppState.RecycleBin,
    onOpenRestore: (com.unifiedledger.domain.TransactionId) -> Unit,
    onClose: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回收站", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onClose != null) {
                OutlinedButton(onClick = onClose) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        val failure = recycleBinFailureText(state.rows)
        if (failure != null) {
            Text(failure, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val rows = (state.rows as RecycleBinResult.Success).rows
        if (rows.isEmpty()) {
            Text(RECYCLE_BIN_EMPTY_TEXT, style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        rows.forEach { row ->
            val text = recycleBinRowText(row)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // C04 precedent: the row announces its reason, void time, dependencies and
                        // restore admissibility as one node (V-21 keeps the reason off any log).
                        .semantics(mergeDescendants = true) { contentDescription = text.contentDescription }
                        .padding(vertical = 4.dp),
            ) {
                Text(text.title, style = MaterialTheme.typography.bodyMedium)
                Text("原因：${text.reason}", style = MaterialTheme.typography.bodySmall)
                Text("作废时间：${text.voidTime}", style = MaterialTheme.typography.bodySmall)
                Text(text.dependencies, style = MaterialTheme.typography.bodySmall)
                Text(text.restoreStatus, style = MaterialTheme.typography.bodySmall)
                if (recycleBinRestoreAdmissible(row)) {
                    TextButton(onClick = { onOpenRestore(row.voided.transactionId) }) { Text("恢复") }
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * P7-05.C: the nested restore confirmation (spec section 4.4). It mirrors the void page — the
 * mandatory typed reason and the per-operation marker — and shows the catalog revalidation result
 * the bin row already carries: an admissible row confirms; an inadmissible row states the frozen
 * rejection and offers no confirm (the transaction stays voided, DP-9). [onConfirm] is the host
 * commit call site and is `null` until Piece 4 wires the restore use case.
 */
@Composable
internal fun P503RestoreConfirmScreen(
    restore: RestoreConfirm,
    row: RecycleBinRow?,
    onUpdateReason: (VoidReasonFieldUpdate) -> Unit,
    onConfirm: (() -> Unit)?,
    onClose: (() -> Unit)?,
    onRecheck: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("恢复交易", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onClose != null) {
                OutlinedButton(onClick = onClose) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        restore.notice?.let { notice ->
            Text(
                p705NoticeText(notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                // V-17: the restore page's async rejection/stale outcome is announced too (the same
                // assertive live region as the void page and the edit screen's notice).
                modifier =
                    Modifier.semantics {
                        contentDescription = p705NoticeText(notice)
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        val admissible = row?.let(::recycleBinRestoreAdmissible) ?: false
        if (row == null) {
            Text("无法定位该交易（回收站数据已刷新）。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else {
            Text("目录重校验：${recycleBinRestoreStatusText(row)}", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text("恢复原因", style = MaterialTheme.typography.titleSmall)
        VoidReasonCode.entries.forEach { code ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = restore.reason.code == code,
                    onClick = { onUpdateReason(VoidReasonFieldUpdate.Code(code)) },
                )
                Text(voidReasonCodeLabel(code), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = restore.reason.note,
            onValueChange = { onUpdateReason(VoidReasonFieldUpdate.Note(it)) },
            label = { Text("补充说明（可选，最多 ${com.unifiedledger.domain.VOID_REASON_NOTE_MAX_LENGTH} 字）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(RESTORE_IMPACT_STATEMENT, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { onConfirm?.invoke() },
                enabled = onConfirm != null && admissible && !restore.submitting && voidReasonDraftRejection(restore.reason) == null,
            ) {
                Text("确认恢复")
            }
            if (onClose != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClose, enabled = !restore.submitting) { Text("取消") }
            }
        }
        if (restore.submitting) {
            Spacer(Modifier.height(8.dp))
            P705SubmittingRow(onRecheck = onRecheck, outcome = restore.checkOutcome)
        }
    }
}

/**
 * P7-05 (V-19; D-173): the shared "commit in flight" line of the three surfaces. When the host
 * still holds the retained request snapshot it also renders the manual 「重新核对」 affordance — the
 * P7-02 D-126 R4 precedent ([P503UnknownCommitStayScreen]'s button). A `null` callback renders the
 * bare line rather than a dead button. [outcome] is the surface's last manual re-check outcome: an
 * [P705CommitCheckOutcome.ABSENT]/[P705CommitCheckOutcome.UNAVAILABLE] result renders the
 * [p705RecheckStatusText] line beside the button, so the re-check is never a silent no-op; the
 * plain [P705CommitCheckOutcome.NONE] keeps only the 「正在提交…」 line. No
 * `minimumInteractiveComponentSize()`: material3 already enforces the 48dp touch target and a
 * manual modifier creates a dead-zone hit layer (D-127). The button carries no extra
 * `contentDescription`: this file adds none to its buttons (the [P503UnknownCommitStayScreen]
 * precedent), and the visible 重新核对 text is what TalkBack reads. The status line follows the
 * file's notice pattern (a `contentDescription` on the node) so a resolved-but-empty re-check is
 * announced, not only painted.
 */
@Composable
private fun P705SubmittingRow(
    onRecheck: (() -> Unit)?,
    outcome: P705CommitCheckOutcome = P705CommitCheckOutcome.NONE,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("正在提交…", style = MaterialTheme.typography.bodyMedium)
        if (onRecheck != null) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRecheck) {
                Text("重新核对")
            }
            p705RecheckStatusText(outcome)?.let { status ->
                Spacer(Modifier.width(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { contentDescription = status },
                )
            }
        }
    }
}
