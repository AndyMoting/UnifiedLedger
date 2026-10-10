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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId

// P7-08 08.B-2 (D-221 residual R-221-1; spec section 4.4). The annotation-edit surface: an existing
// not-yet-voided transaction's tag/merchant association is replaced as one whole new revision through
// an explicit confirmation. Like the P7-05 screens it is a thin renderer — every affordance takes a
// callback the composition root wires, and an absent callback renders no dead button. The screen
// never reads the database: the selectable options come from the caller's catalog projection, which
// the facade has already filtered to `active && !tombstoned` (spec section 2.2 — a disabled or
// tombstoned item is never offered for a new association). The association is optional on every
// path: an empty tag set and a null merchant are valid "no association" states, and this surface
// never adds a required field to entry (spec section 4.1).
//
// The four-state outcome is surfaced verbatim (accepted / no-change / rejected-with-code /
// conflict); nothing is silently swallowed, and a stale CAS keeps the user on the form with the
// typed rejection rather than a faked success.

/**
 * P7-08 08.B-2: the annotation-edit form. [tagOptions]/[merchantOptions] are the already-filtered
 * selectable catalogs; [tagIds]/[merchantId] are the pending selection carried by the state's
 * intent. [onConfirm] is `null` until the composition root wires the command (then the confirm
 * affordance renders disabled rather than dead); while a commit is in flight ([submitting]) the
 * confirm disables (提交中不重入) and the caller's guards absorb the exits (提交中不得离开).
 */
@Composable
internal fun P503TransactionAnnotationEditScreen(
    state: P503AppState.TransactionAnnotationEdit,
    tagOptions: List<CatalogItem>?,
    merchantOptions: List<CatalogItem>?,
    onToggleTag: (TagId) -> Unit,
    onSelectMerchant: (MerchantId?) -> Unit,
    onConfirm: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    val intent = state.intent
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("标签与商家", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onCancel != null) {
                OutlinedButton(onClick = onCancel, enabled = !state.submitting) { Text("返回") }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The whole set is replaced on confirm (spec section 4.4: no partial update); the statement
        // tells the user that plainly rather than implying an incremental edit.
        Text(
            "本次选择会整体替换该交易的标签与商家；修改不影响金额、分录与余额。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        state.outcome?.let { outcome ->
            val message = annotationEditOutcomeText(outcome)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    when (outcome) {
                        is TransactionAnnotationEditOutcome.Accepted,
                        is TransactionAnnotationEditOutcome.NoChange,
                        -> MaterialTheme.colorScheme.primary
                        is TransactionAnnotationEditOutcome.Rejected,
                        is TransactionAnnotationEditOutcome.Conflict,
                        -> MaterialTheme.colorScheme.error
                    },
                modifier =
                    Modifier.semantics {
                        contentDescription = message
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("标签（可选，可多选）", style = MaterialTheme.typography.titleSmall)
        when {
            // Null = not yet loaded / unwired: an honest 载入中, never an authoritative empty picker.
            tagOptions == null -> Text("标签目录载入中…", style = MaterialTheme.typography.bodySmall)
            tagOptions.isEmpty() -> Text("当前没有可选的标签。", style = MaterialTheme.typography.bodySmall)
            else ->
                tagOptions.forEach { tag ->
                    val id = TagId(tag.id)
                    val selected = id in intent.tagIds
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = selected,
                            onCheckedChange = { onToggleTag(id) },
                            enabled = !state.submitting,
                        )
                        Text(tag.name)
                    }
                }
        }
        Spacer(Modifier.height(12.dp))
        Text("商家（可选，最多一个）", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = intent.merchantId == null,
                onClick = { onSelectMerchant(null) },
                enabled = !state.submitting,
            )
            Text("不关联商家")
        }
        if (merchantOptions == null) {
            // Null = not yet loaded / unwired: honest 载入中, never an authoritative empty picker.
            Text("商家目录载入中…", style = MaterialTheme.typography.bodySmall)
        } else {
            merchantOptions.forEach { merchant ->
                val id = MerchantId(merchant.id)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = intent.merchantId == id,
                        onClick = { onSelectMerchant(id) },
                        enabled = !state.submitting,
                    )
                    Text(merchant.name)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel ?: {}, enabled = onCancel != null && !state.submitting) {
                Text("取消")
            }
            Spacer(Modifier.fillMaxWidth().weight(1f))
            Button(
                onClick = { onConfirm?.invoke() },
                enabled = onConfirm != null && !state.submitting,
            ) {
                Text(if (state.submitting) "提交中…" else "确认修改")
            }
        }
    }
}

/**
 * P7-08 08.B-2: the surfaced four-state text. `failureCode` is the frozen stable literal from
 * [com.unifiedledger.application.AnnotationFailureCode.code]; it is shown verbatim so the user and
 * any report read the same token the store produced (never a paraphrased success).
 */
internal fun annotationEditOutcomeText(outcome: TransactionAnnotationEditOutcome): String =
    when (outcome) {
        is TransactionAnnotationEditOutcome.Accepted -> "已保存（修订 ${outcome.newAnnotationRevision}）。"
        is TransactionAnnotationEditOutcome.NoChange -> "本次请求与已有结果一致（修订 ${outcome.newAnnotationRevision}），未重复写入。"
        is TransactionAnnotationEditOutcome.Rejected -> "未保存：${outcome.failureCode}"
        is TransactionAnnotationEditOutcome.Conflict -> "未保存（冲突）：${outcome.failureCode}"
    }
