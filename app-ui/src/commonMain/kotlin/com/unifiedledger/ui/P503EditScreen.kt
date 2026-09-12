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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.ManualExpenseOptions
import com.unifiedledger.application.ManualIncomeOptions
import com.unifiedledger.application.ManualLendingOptions
import com.unifiedledger.application.ManualTransferOptions
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Edit screen (spec section 7.3.2; P7-02 sections 6/S-2/S-4). Supports the two entry types
 * implemented by P7-02.A (EXPENSE/INCOME) through a shared typed draft. The payment/receiving
 * account, secondary category, amount, optional note and occurred time are edited here; the
 * currency shown follows the selected account and is never free text. Field errors retain the
 * input and are semantically associated with their field (isError+supportingText for text
 * fields, a merged label+error semantics node for the selector groups), so a screen reader
 * reads each error as part of its field. A non-null [onContinue] shows the Continue button;
 * `null` hides it (used by the conflict/rejection result presentation where the user must
 * modify a field or abandon the conflict before continuing). A non-null [onClose] shows the
 * visible close button (P5-04.3); it dispatches the same Back event as the system back.
 *
 * D-131 R2: the occurred-at field gains a picker entry (DatePickerDialog then TimePicker,
 * spec 3.2); the selected local date-time converts through the fixed Asia/Shanghai zone and is
 * written via [onUpdateOccurredAt] (the reducer is untouched). The initial picker value is the
 * draft instant or the composition-root-injected [ledgerClock]'s current instant; a conversion
 * that fails the round-trip check (historical DST gap) surfaces the field error and dispatches
 * nothing (fail-closed, spec 3.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P503EditScreen(
    draft: TypedEntryDraft,
    options: ManualExpenseOptions,
    validation: P503DraftValidation,
    currency: CurrencyUnit,
    ledgerClock: LedgerClock,
    parseOccurredAt: ParseManualExpenseOccurredAt,
    onUpdateAmount: (String) -> Unit,
    onUpdatePaymentAccount: (AccountId) -> Unit,
    onUpdateCategory: (CategoryId) -> Unit,
    onUpdateOccurredAt: (Instant) -> Unit,
    occurredAtText: String,
    onOccurredAtTextChange: (String) -> Unit,
    onContinue: (() -> Unit)?,
    incomeOptions: ManualIncomeOptions = ManualIncomeOptions(emptyList(), emptyList()),
    transferOptions: ManualTransferOptions = ManualTransferOptions(emptyList(), emptyList()),
    lendingOptions: ManualLendingOptions = ManualLendingOptions(emptyList(), emptyList()),
    onSelectEntryType: (EntryType) -> Unit = {},
    onUpdateNote: (String) -> Unit = {},
    onUpdateReceivingAccount: (AccountId) -> Unit = onUpdatePaymentAccount,
    onUpdateIncomeCategory: (CategoryId) -> Unit = onUpdateCategory,
    onUpdateTransferSourceAccount: (AccountId) -> Unit = onUpdatePaymentAccount,
    onUpdateTransferDestinationAccount: (AccountId) -> Unit = {},
    onUpdateTransferDestinationCredit: (String) -> Unit = onUpdateAmount,
    onUpdateTransferFee: (String) -> Unit = {},
    onUpdateTransferFeeCategory: (CategoryId) -> Unit = onUpdateCategory,
    onUpdateLendCounterparty: (com.unifiedledger.domain.CounterpartyId) -> Unit = {},
    onUpdateLendFundingAccount: (AccountId) -> Unit = onUpdatePaymentAccount,
    onUpdateLendAmount: (String) -> Unit = onUpdateAmount,
    onUpdateCollectCounterparty: (com.unifiedledger.domain.CounterpartyId) -> Unit = {},
    onUpdateCollectDestinationAccount: (AccountId) -> Unit = {},
    onUpdateCollectTotal: (String) -> Unit = onUpdateAmount,
    onUpdateCollectPrincipal: (String) -> Unit = {},
    onUpdateCollectInterest: (String) -> Unit = {},
    onUpdateCollectInterestCategory: (CategoryId) -> Unit = onUpdateCategory,
    banner: (@Composable () -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onDialogVisibilityChanged: (Boolean) -> Unit = {},
) {
    var occurredAtParseError by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var pickedLocalDate by remember { mutableStateOf<LocalDate?>(null) }

    val latestOnDialogVisibilityChanged by rememberUpdatedState(onDialogVisibilityChanged)
    LaunchedEffect(datePickerOpen, timePickerOpen) {
        latestOnDialogVisibilityChanged(datePickerOpen || timePickerOpen)
    }
    // 防御性自愈：编辑屏离开组合时补报关闭，防止 editDialogOpen 陈旧滞留
    DisposableEffect(Unit) {
        onDispose { latestOnDialogVisibilityChanged(false) }
    }

    val isIncome = draft is IncomeDraft
    val isTransfer = draft is TransferDraft
    val accountOptions = if (isIncome) incomeOptions.receivingAccounts else options.paymentAccounts
    val accountFieldLabel = if (isIncome) "收款账户" else "支付账户"
    val categoryOptions: List<Pair<CategoryId, String>> =
        if (isIncome) {
            incomeOptions.incomeCategories.map { it.categoryId to it.label }
        } else {
            options.expenseCategories.map { it.categoryId to it.label }
        }
    val categoryFieldLabel = if (isIncome) "收入分类" else "费用分类"
    val amountCurrencyCode =
        accountOptions.firstOrNull { it.accountId == draft.primaryAccountId }?.currency?.code ?: "—"

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .onPreviewKeyEvent { event ->
                    // D-137: 仅当选择器对话框打开且 Escape KeyDown 时，关闭开着的对话框并消费；
                    // 焦点在主窗口内容时由本 handler 兜底（对话框内容路径由 dismissOnEscape 覆盖）。
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        when {
                            timePickerOpen -> {
                                timePickerOpen = false
                                true
                            }
                            datePickerOpen -> {
                                datePickerOpen = false
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
    ) {
        banner?.invoke()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (draft) {
                    is TransferDraft -> "新增手工转账"
                    is IncomeDraft -> "新增手工收入"
                    is LendDraft -> "新增手工借出"
                    is CollectDraft -> "新增手工收回"
                    else -> "新增手工支出"
                },
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

        // P7-02 S-2: EXPENSE/INCOME/TRANSFER are selectable in this batch (LEND/COLLECT in C);
        // the type switch uses the frozen retention matrix in the reducer.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("类型：", style = MaterialTheme.typography.titleSmall)
            listOf(EntryType.EXPENSE, EntryType.INCOME, EntryType.TRANSFER, EntryType.LEND, EntryType.COLLECT).forEach { type ->
                Row(
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = draft.entryType == type,
                        onClick = { onSelectEntryType(type) },
                    )
                    Text(
                        when (type) {
                            EntryType.INCOME -> "收入"
                            EntryType.TRANSFER -> "转账"
                            EntryType.LEND -> "借出"
                            EntryType.COLLECT -> "收回"
                            else -> "支出"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val errors = validation.errors(draft, currency)

        if (draft is TransferDraft) {
            SelectorField(
                label = "转出账户",
                hasError = errors.missingPaymentAccount,
                errorMessage = "请选择转出账户",
            ) {
                transferOptions.ownedAssetAccounts.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.accountId == draft.sourceAccountId, onClick = { onUpdateTransferSourceAccount(option.accountId) })
                        Text("${option.label}（${option.currency.code}）", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            SelectorField(
                label = "转入账户",
                hasError = errors.missingDestinationAccount,
                errorMessage = "请选择转入账户",
            ) {
                transferOptions.ownedAssetAccounts.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.accountId == draft.destinationAccountId, onClick = { onUpdateTransferDestinationAccount(option.accountId) })
                        Text("${option.label}（${option.currency.code}）", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            val transferCurrencyCode =
                transferOptions.ownedAssetAccounts
                    .firstOrNull { it.accountId == draft.sourceAccountId }
                    ?.currency
                    ?.code ?: "—"
            OutlinedTextField(
                value = draft.destinationCredit,
                onValueChange = onUpdateTransferDestinationCredit,
                label = { Text("到账本金（$transferCurrencyCode）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = errors.missingAmount || errors.amountFormatError != null,
                supportingText = { Text("转出金额 = 到账本金 + 手续费") },
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = draft.fee,
                onValueChange = onUpdateTransferFee,
                label = { Text("手续费") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = errors.feeFormatError != null,
                supportingText = { Text(if (errors.feeFormatError != null) "手续费格式无效" else "无手续费填 0.00") },
            )
            Spacer(Modifier.height(8.dp))

            // A fee category is only meaningful (and required) when the fee is positive.
            if (errors.transferFeeCategoryRequired) {
                SelectorField(
                    label = "手续费分类",
                    hasError = errors.missingCategory,
                    errorMessage = "请选择手续费分类",
                ) {
                    transferOptions.feeCategories.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.categoryId == draft.feeCategoryId, onClick = { onUpdateTransferFeeCategory(option.categoryId) })
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        } else if (draft is LendDraft) {
            SelectorField(label = "往来对象", hasError = errors.missingCategory, errorMessage = "请选择往来对象") {
                lendingOptions.counterparties.filter { it.active }.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option.counterpartyId == draft.counterpartyId, onClick = { onUpdateLendCounterparty(option.counterpartyId) })
                        Text(option.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SelectorField(label = "出资账户", hasError = errors.missingPaymentAccount, errorMessage = "请选择出资账户") {
                lendingOptions.ownedAssetAccounts.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option.accountId == draft.fundingAccountId, onClick = { onUpdateLendFundingAccount(option.accountId) })
                        Text("${option.label}（${option.currency.code}）", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val lendCurrency =
                lendingOptions.ownedAssetAccounts
                    .firstOrNull { it.accountId == draft.fundingAccountId }
                    ?.currency
                    ?.code ?: "—"
            OutlinedTextField(
                value = draft.amount,
                onValueChange = onUpdateLendAmount,
                label = { Text("借出金额（$lendCurrency）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = errors.missingAmount || errors.amountFormatError != null,
            )
        } else if (draft is CollectDraft) {
            SelectorField(label = "往来对象", hasError = errors.missingCategory, errorMessage = "请选择往来对象") {
                lendingOptions.counterparties.filter { it.active }.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option.counterpartyId == draft.counterpartyId, onClick = { onUpdateCollectCounterparty(option.counterpartyId) })
                        Text(option.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SelectorField(label = "到账账户", hasError = errors.missingPaymentAccount, errorMessage = "请选择到账账户") {
                lendingOptions.ownedAssetAccounts.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option.accountId == draft.destinationAccountId, onClick = { onUpdateCollectDestinationAccount(option.accountId) })
                        Text("${option.label}（${option.currency.code}）", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val collectCurrency =
                lendingOptions.ownedAssetAccounts
                    .firstOrNull { it.accountId == draft.destinationAccountId }
                    ?.currency
                    ?.code ?: "—"
            OutlinedTextField(value = draft.totalReceived, onValueChange = onUpdateCollectTotal, label = { Text("实收总额（$collectCurrency）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = errors.missingAmount)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = draft.principal, onValueChange = onUpdateCollectPrincipal, label = { Text("本金") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = draft.interest, onValueChange = onUpdateCollectInterest, label = { Text("利息") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Spacer(Modifier.height(8.dp))
            SelectorField(label = "利息分类", hasError = errors.missingCategory, errorMessage = "请选择利息分类") {
                lendingOptions.interestCategories.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option.categoryId == draft.interestCategoryId, onClick = { onUpdateCollectInterestCategory(option.categoryId) })
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            SelectorField(
                label = accountFieldLabel,
                hasError = errors.missingPaymentAccount,
                errorMessage = "请选择$accountFieldLabel",
            ) {
                accountOptions.forEach { option ->
                    val selected = option.accountId == draft.primaryAccountId
                    Row(
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { if (isIncome) onUpdateReceivingAccount(option.accountId) else onUpdatePaymentAccount(option.accountId) },
                        )
                        Text("${option.label}（${option.currency.code}）", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            SelectorField(
                label = categoryFieldLabel,
                hasError = errors.missingCategory,
                errorMessage = "请选择$categoryFieldLabel",
            ) {
                categoryOptions.forEach { (optionCategoryId, label) ->
                    val selected = optionCategoryId == draft.categoryId
                    Row(
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { if (isIncome) onUpdateIncomeCategory(optionCategoryId) else onUpdateCategory(optionCategoryId) },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = draft.amountText,
                onValueChange = onUpdateAmount,
                label = { Text("金额（$amountCurrencyCode）") },
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
        }

        // P7-02.A S-4: optional note, length limit enforced by the reducer/commit validation.
        OutlinedTextField(
            value = draft.note,
            onValueChange = onUpdateNote,
            label = { Text("备注（可选）") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        OccurredAtField(
            text = occurredAtText,
            isError = occurredAtParseError || errors.missingOccurredAt,
            supportingMessage =
                when {
                    occurredAtParseError -> "无法识别的时间格式，示例：2026-09-15 08:30 或 2026-09-15T00:30:00Z"
                    errors.missingOccurredAt -> "请输入发生时间"
                    else -> "示例：2026-09-15 08:30 或 2026-09-15T00:30:00Z"
                },
            onTextChange = { newText ->
                onOccurredAtTextChange(newText)
                // D-138 parse-on-type (spec 2.3): lenient parsing with the same parser and
                // clock instances as the Continue gate; blank keeps the missing-field path,
                // invalid non-blank text never dispatches and surfaces the inline error.
                val parsed = parseOccurredAt.parse(newText, ledgerClock)
                occurredAtParseError = parsed is ParseManualExpenseOccurredAt.Result.Invalid && newText.isNotBlank()
                if (parsed is ParseManualExpenseOccurredAt.Result.Valid) {
                    onUpdateOccurredAt(parsed.instant)
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
        val dateState =
            rememberDatePickerState(
                // The initial calendar day is the Asia/Shanghai local day of the draft
                // instant (or now), never the UTC day (finding INPUTUX-IMPL-001).
                initialSelectedDateMillis = occurredAtPickerInitialMillis(draft.occurredAt ?: ledgerClock.now()),
            )
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
                                            pickedDate.month.ordinal + 1,
                                            pickedDate.day,
                                            timeState.hour,
                                            timeState.minute,
                                        )
                                    // Fail-closed (spec 3.3): a local time inside a historical
                                    // DST gap converts to a different instant and is rejected
                                    // as a field error instead of being guessed.
                                    val instant = occurredAtFromLocalDateTime(local)
                                    occurredAtParseError = instant == null
                                    if (instant != null) {
                                        // D-139 (spec 2.2): the text state has no key, so the picker product must be synced explicitly; an equal selection keeps the current text (D-138 spec 2.4).
                                        if (instant != draft.occurredAt) {
                                            onOccurredAtTextChange(instant.toString())
                                        }
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
