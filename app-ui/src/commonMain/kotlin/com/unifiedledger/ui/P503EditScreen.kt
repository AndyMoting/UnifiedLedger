package com.unifiedledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualExpenseOptions
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
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
 *
 * D-131 R2: the occurred-at field gains a picker entry (DatePickerDialog then TimePicker,
 * spec 3.2); the selected local date-time converts through the fixed Asia/Shanghai zone
 * and is written via [onUpdateOccurredAt] (the reducer is untouched). The initial picker
 * value is the draft instant or the composition-root-injected [ledgerClock]'s current
 * instant; a conversion that fails the round-trip check (historical DST gap) surfaces the
 * field error and dispatches nothing (fail-closed, spec 3.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P503EditScreen(
    draft: ManualExpenseDraft,
    options: ManualExpenseOptions,
    validation: P503DraftValidation,
    currency: CurrencyUnit,
    ledgerClock: LedgerClock,
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
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var pickedLocalDate by remember { mutableStateOf<LocalDate?>(null) }

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
                    else -> Text("金额示例：11、35.8 或 35.80")
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
            onPickerEntryClick = { datePickerOpen = true },
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

    if (datePickerOpen) {
        val initialInstant = draft.occurredAt ?: ledgerClock.now()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialInstant.toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = dateState.selectedDateMillis
                        if (millis != null) {
                            pickedLocalDate = occurredAtPickerLocalDate(millis)
                            datePickerOpen = false
                            timePickerOpen = true
                        }
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) {
                    Text("取消")
                }
            },
            modifier = Modifier.dismissOnEscape { datePickerOpen = false },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (timePickerOpen) {
        val initialLocal = (draft.occurredAt ?: ledgerClock.now()).toLocalDateTime(occurredAtTimeZone)
        val timeState =
            rememberTimePickerState(
                initialHour = initialLocal.hour,
                initialMinute = initialLocal.minute,
                is24Hour = true,
            )
        Dialog(onDismissRequest = { timePickerOpen = false }) {
            Surface(
                modifier = Modifier.dismissOnEscape { timePickerOpen = false },
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("选择时间", style = MaterialTheme.typography.titleLarge)
                    TimePicker(state = timeState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { timePickerOpen = false }) {
                            Text("取消")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val pickedDate = pickedLocalDate
                                if (pickedDate != null) {
                                    val local =
                                        LocalDateTime(
                                            pickedDate.year,
                                            pickedDate.monthNumber,
                                            pickedDate.dayOfMonth,
                                            timeState.hour,
                                            timeState.minute,
                                        )
                                    // Fail-closed (spec 3.3): a local time inside a historical
                                    // DST gap converts to a different instant and is rejected
                                    // as a field error instead of being guessed.
                                    val instant = occurredAtFromLocalDateTime(local)
                                    occurredAtParseError = instant == null
                                    if (instant != null) {
                                        onUpdateOccurredAt(instant)
                                    }
                                }
                                timePickerOpen = false
                            },
                        ) {
                            Text("确定")
                        }
                    }
                }
            }
        }
    }
}

/**
 * D-131 R2 (spec 3.5): desktop Escape closes only the picker dialog while it owns focus.
 * The JVM-level back dispatcher yields Escape to dialog windows; this deterministic
 * dismissal also covers platforms where the dialog window does not map Escape itself.
 */
private fun Modifier.dismissOnEscape(onDismiss: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onDismiss()
            true
        } else {
            false
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
    onPickerEntryClick: () -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text("发生时间") },
        supportingText = { Text(supportingMessage) },
        isError = isError,
        singleLine = true,
        trailingIcon = {
            // D-131 R2 picker entry (spec 3.6): material3 1.9.0 has no icons transitive
            // dependency, so the entry is a focusable text control carrying the frozen
            // contentDescription. Do not stack minimumInteractiveComponentSize() on it:
            // material3 already applies the 48dp touch-target enforcement (D-127).
            TextButton(
                onClick = onPickerEntryClick,
                modifier = Modifier.semantics { contentDescription = "选择发生时间" },
            ) {
                Text("选择")
            }
        },
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
