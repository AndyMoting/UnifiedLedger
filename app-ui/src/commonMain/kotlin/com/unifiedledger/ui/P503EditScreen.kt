package com.unifiedledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.ManualExpenseOptions
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import kotlin.time.Instant

/**
 * Edit screen (spec section 7.3.2). Payment account, secondary expense category, amount
 * and occurred time; the currency shown follows the selected payment account and is never
 * free text. Field errors retain the input and are semantically associated with their
 * field (isError+supportingText for text fields, a merged label+error semantics node for
 * the selector groups), so a screen reader reads each error as part of its field. A
 * non-null [onContinue] shows the Continue button; `null` hides it (used by the
 * conflict/rejection result presentation where the user must modify a field or abandon the
 * conflict before continuing). A non-null [onClose] shows the visible close button
 * (P5-04.3); it dispatches the same Back event as the system back: drop the draft and
 * return to the originating overview tab.
 */
@Composable
fun P503EditScreen(
    draft: ManualExpenseDraft,
    options: ManualExpenseOptions,
    validation: P503DraftValidation,
    currency: CurrencyUnit,
    onUpdateAmount: (String) -> Unit,
    onUpdatePaymentAccount: (AccountId) -> Unit,
    onUpdateCategory: (CategoryId) -> Unit,
    onUpdateOccurredAt: (Instant) -> Unit,
    onContinue: (() -> Unit)?,
    banner: (@Composable () -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    var occurredAtText by remember(draft.occurredAt) { mutableStateOf(draft.occurredAt?.toString() ?: "") }
    var occurredAtParseError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        banner?.invoke()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "新增手工支出",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            // P5-04.3 visible close entry: same Back semantics as the system back.
            // Do not stack a manual minimumInteractiveComponentSize() on this TextButton:
            // material3 already applies the 48dp touch-target enforcement internally to its
            // clickable Surface (LocalMinimumInteractiveComponentEnforcement defaults to
            // true), and duplicating it here produced a misaligned double hit-target layer
            // that swallowed taps over most of the button (device gate defect, D-127).
            if (onClose != null) {
                TextButton(onClick = onClose) {
                    Text("关闭")
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val errors = validation.errors(draft, currency)

        SelectorField(
            label = "支付账户",
            hasError = errors.missingPaymentAccount,
            errorMessage = "请选择支付账户",
        ) {
            options.paymentAccounts.forEach { option ->
                val selected = option.accountId == draft.paymentAccountId
                Row(
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onUpdatePaymentAccount(option.accountId) })
                    Text("${option.label}（${option.currency.code}）", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        SelectorField(
            label = "费用分类",
            hasError = errors.missingCategory,
            errorMessage = "请选择费用分类",
        ) {
            options.expenseCategories.forEach { option ->
                val selected = option.categoryId == draft.categoryId
                Row(
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onUpdateCategory(option.categoryId) })
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val selectedAccount = options.paymentAccounts.firstOrNull { it.accountId == draft.paymentAccountId }
        OutlinedTextField(
            value = draft.amountText,
            onValueChange = onUpdateAmount,
            label = { Text("金额（${selectedAccount?.currency?.code ?: "—"}）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = errors.missingAmount || errors.amountFormatError != null,
            supportingText = {
                when {
                    errors.missingAmount -> Text("请输入金额")
                    errors.amountFormatError != null -> Text("金额格式无效")
                    else -> Text("精确金额，两位小数")
                }
            },
        )
        Spacer(Modifier.height(8.dp))

        OccurredAtField(
            text = occurredAtText,
            isError = occurredAtParseError || errors.missingOccurredAt,
            supportingMessage =
                when {
                    occurredAtParseError -> "时间格式无效"
                    errors.missingOccurredAt -> "请输入发生时间"
                    else -> "ISO 8601，如 2026-01-15T00:30:00Z"
                },
            onTextChange = { newText ->
                occurredAtText = newText
                val parsed = runCatching { Instant.parse(newText) }.getOrNull()
                occurredAtParseError = parsed == null && newText.isNotBlank()
                if (parsed != null) {
                    onUpdateOccurredAt(parsed)
                }
            },
        )
        Spacer(Modifier.height(16.dp))

        if (onContinue != null) {
            Button(
                onClick = {
                    // Continue gate (finding P503IMPL-Q-001): the displayed occurred-at text
                    // must re-parse to exactly the draft's instant; otherwise the value shown
                    // would differ from what the reducer validates, so block and surface the
                    // parse error instead.
                    if (validation.occurredAtTextReconciles(occurredAtText, draft.occurredAt)) {
                        onContinue()
                    } else {
                        occurredAtParseError = true
                    }
                },
            ) {
                Text("继续")
            }
        }
    }
}

/**
 * Selector field with a field-level error that is semantically associated with the field:
 * the label and the visible error text merge into one semantics node read by screen
 * readers as the field's label plus its error, while the selectable options stay
 * individually accessible.
 */
@Composable
private fun SelectorField(
    label: String,
    hasError: Boolean,
    errorMessage: String,
    options: @Composable () -> Unit,
) {
    Column {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (hasError) {
                FieldError(errorMessage)
            }
        }
        options()
    }
}

@Composable
private fun OccurredAtField(
    text: String,
    isError: Boolean,
    supportingMessage: String,
    onTextChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text("发生时间") },
        supportingText = { Text(supportingMessage) },
        isError = isError,
        singleLine = true,
    )
}

@Composable
private fun FieldError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
internal fun P503ConflictBanner(
    onAbandonConflict: () -> Unit,
) {
    Column {
        Text(
            "请求标识冲突：同一 requestId 已存在不同快照，未创建新交易。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "修改任一字段可返回编辑；或显式放弃该冲突草稿后新建。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAbandonConflict) {
            Text("放弃冲突并新建")
        }
    }
}

@Composable
internal fun P503RejectedBanner() {
    Text(
        "业务校验未通过，未创建交易。修改输入后可重新提交。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
