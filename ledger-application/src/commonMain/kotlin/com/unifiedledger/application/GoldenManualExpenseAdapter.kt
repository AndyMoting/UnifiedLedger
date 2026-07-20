package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryExpenseViolation
import kotlin.time.Instant

sealed interface Rg01JsonField<out T> {
    data object Omitted : Rg01JsonField<Nothing>

    data object Null : Rg01JsonField<Nothing>

    data class Value<T>(val value: T) : Rg01JsonField<T>
}

data class Rg01DecodedManualExpenseInput(
    val requestId: Rg01JsonField<String>,
    val amount: Rg01JsonField<String>,
    val currency: Rg01JsonField<String>,
    val categoryId: Rg01JsonField<String>,
    val paymentAccountId: Rg01JsonField<String>,
    val occurredAt: Rg01JsonField<String>,
    val note: Rg01JsonField<String>,
    val explicitConfirmation: Rg01JsonField<Boolean>,
)

data class Rg01ManualExpenseContext(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val catalog: LedgerCatalog? = null,
    val caseTimeZone: String = "Asia/Shanghai",
    val validNumericOffset: String = "+08:00",
)

data class Rg01ParsedManualExpenseInput(
    val saveInput: ManualExpenseSaveInput,
    val originalAmountText: String?,
    val originalOccurredAtText: String,
)

enum class Rg01ContractErrorReason {
    MISSING_REQUIRED_FIELD,
    NULL_NOT_ALLOWED,
    INVALID_DECIMAL,
    INVALID_TIMESTAMP,
    CURRENCY_MISMATCH,
    EXPLICIT_CONFIRMATION_REQUIRED,
    INVALID_ID,
    INVALID_REFERENCE,
    UNREGISTERED_REJECTION,
    TIMEZONE_OFFSET_MISMATCH,
    UNSUPPORTED_TIMEZONE,
}

data class Rg01ContractError(
    val fieldPath: String,
    val reason: Rg01ContractErrorReason,
)

sealed interface Rg01AttemptedExpenseResult {
    data class Mapped(val projection: Rg01OutcomeProjection) : Rg01AttemptedExpenseResult

    data class InvalidContract(val error: Rg01ContractError) : Rg01AttemptedExpenseResult
}

sealed interface Rg01ManualExpenseParseResult {
    data class Success(val value: Rg01ParsedManualExpenseInput) : Rg01ManualExpenseParseResult

    data class InvalidContract(val error: Rg01ContractError) : Rg01ManualExpenseParseResult
}

fun parseRg01ManualExpenseInput(
    context: Rg01ManualExpenseContext,
    input: Rg01DecodedManualExpenseInput,
): Rg01ManualExpenseParseResult {
    val requestId = when (val field = input.requestId) {
        Rg01JsonField.Omitted -> return contractError("request_id", Rg01ContractErrorReason.MISSING_REQUIRED_FIELD)
        Rg01JsonField.Null -> return contractError("request_id", Rg01ContractErrorReason.NULL_NOT_ALLOWED)
        is Rg01JsonField.Value -> field.value
    }
    if (!requestId.isStableId()) {
        return contractError("request_id", Rg01ContractErrorReason.INVALID_ID)
    }
    val originalAmountText = when (val field = input.amount) {
        Rg01JsonField.Omitted -> return contractError("amount", Rg01ContractErrorReason.MISSING_REQUIRED_FIELD)
        Rg01JsonField.Null -> return contractError("amount", Rg01ContractErrorReason.NULL_NOT_ALLOWED)
        is Rg01JsonField.Value -> field.value
    }
    val amount = parseExactMoney(originalAmountText, context.currency)
        ?: return contractError("amount", Rg01ContractErrorReason.INVALID_DECIMAL)
    val currency = when (val field = input.currency) {
        Rg01JsonField.Omitted -> return contractError("currency", Rg01ContractErrorReason.MISSING_REQUIRED_FIELD)
        Rg01JsonField.Null -> return contractError("currency", Rg01ContractErrorReason.NULL_NOT_ALLOWED)
        is Rg01JsonField.Value -> field.value
    }
    if (currency != context.currency.code) {
        return contractError("currency", Rg01ContractErrorReason.CURRENCY_MISMATCH)
    }

    val occurredAtText = when (val field = input.occurredAt) {
        Rg01JsonField.Omitted -> return contractError("occurred_at", Rg01ContractErrorReason.MISSING_REQUIRED_FIELD)
        Rg01JsonField.Null -> return contractError("occurred_at", Rg01ContractErrorReason.NULL_NOT_ALLOWED)
        is Rg01JsonField.Value -> field.value
    }
    val occurredAt = when (val parsed = parseCaseTimestamp(context, occurredAtText)) {
        is Rg01TimestampParseResult.Success -> parsed.instant
        is Rg01TimestampParseResult.Error -> return contractError("occurred_at", parsed.reason)
    }

    val note = when (val field = input.note) {
        Rg01JsonField.Omitted -> return contractError("note", Rg01ContractErrorReason.MISSING_REQUIRED_FIELD)
        Rg01JsonField.Null -> return contractError("note", Rg01ContractErrorReason.NULL_NOT_ALLOWED)
        is Rg01JsonField.Value -> field.value
    }

    val categoryText = when (val field = input.categoryId) {
        Rg01JsonField.Omitted -> return contractError("category_id", Rg01ContractErrorReason.MISSING_REQUIRED_FIELD)
        Rg01JsonField.Null -> return contractError("category_id", Rg01ContractErrorReason.NULL_NOT_ALLOWED)
        is Rg01JsonField.Value -> field.value
    }
    if (!categoryText.isStableId()) {
        return contractError("category_id", Rg01ContractErrorReason.INVALID_ID)
    }
    val paymentAccountText = when (val field = input.paymentAccountId) {
        Rg01JsonField.Omitted -> return contractError(
            "payment_account_id",
            Rg01ContractErrorReason.MISSING_REQUIRED_FIELD,
        )
        Rg01JsonField.Null -> return contractError(
            "payment_account_id",
            Rg01ContractErrorReason.NULL_NOT_ALLOWED,
        )
        is Rg01JsonField.Value -> field.value
    }
    if (!paymentAccountText.isStableId()) {
        return contractError("payment_account_id", Rg01ContractErrorReason.INVALID_ID)
    }

    val confirmation = when (val field = input.explicitConfirmation) {
        Rg01JsonField.Omitted -> return contractError(
            "explicit_confirmation",
            Rg01ContractErrorReason.MISSING_REQUIRED_FIELD,
        )
        Rg01JsonField.Null -> return contractError(
            "explicit_confirmation",
            Rg01ContractErrorReason.NULL_NOT_ALLOWED,
        )
        is Rg01JsonField.Value -> field.value
    }
    if (!confirmation) {
        return contractError(
            "explicit_confirmation",
            Rg01ContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED,
        )
    }

    val categoryId = CategoryId(categoryText)
    val paymentAccountId = AccountId(paymentAccountText)

    return Rg01ManualExpenseParseResult.Success(
        Rg01ParsedManualExpenseInput(
            saveInput = ManualExpenseSaveInput(
                ledgerId = context.ledgerId,
                requestId = RequestId(requestId),
                amount = amount,
                categoryId = categoryId,
                paymentAccountId = paymentAccountId,
                occurredAt = occurredAt,
                note = note,
                confirmation = ExplicitManualSave,
            ),
            originalAmountText = originalAmountText,
            originalOccurredAtText = occurredAtText,
        ),
    )
}

fun evaluateRg01AttemptedManualExpense(
    context: Rg01ManualExpenseContext,
    input: Rg01DecodedManualExpenseInput,
): Rg01AttemptedExpenseResult {
    val requestId = when (val field = input.requestId) {
        Rg01JsonField.Omitted -> return attemptedContractError(
            "request_id",
            Rg01ContractErrorReason.MISSING_REQUIRED_FIELD,
        )
        Rg01JsonField.Null -> return attemptedContractError(
            "request_id",
            Rg01ContractErrorReason.NULL_NOT_ALLOWED,
        )
        is Rg01JsonField.Value -> field.value
    }
    if (!requestId.isStableId()) {
        return attemptedContractError("request_id", Rg01ContractErrorReason.INVALID_ID)
    }

    val categoryText = input.categoryId.valueOrNull()
    val paymentAccountText = input.paymentAccountId.valueOrNull()
    if (categoryText != null && !categoryText.isStableId()) {
        return attemptedContractError("category_id", Rg01ContractErrorReason.INVALID_ID)
    }
    if (paymentAccountText != null && !paymentAccountText.isStableId()) {
        return attemptedContractError("payment_account_id", Rg01ContractErrorReason.INVALID_ID)
    }

    when (val field = input.currency) {
        Rg01JsonField.Omitted -> Unit
        Rg01JsonField.Null -> return attemptedContractError(
            "currency",
            Rg01ContractErrorReason.NULL_NOT_ALLOWED,
        )
        is Rg01JsonField.Value -> if (
            field.value.isEmpty() || field.value != context.currency.code
        ) {
            return attemptedContractError(
                "currency",
                Rg01ContractErrorReason.CURRENCY_MISMATCH,
            )
        }
    }
    when (val field = input.occurredAt) {
        Rg01JsonField.Omitted -> Unit
        Rg01JsonField.Null -> return attemptedContractError(
            "occurred_at",
            Rg01ContractErrorReason.NULL_NOT_ALLOWED,
        )
        is Rg01JsonField.Value -> when (val parsed = parseCaseTimestamp(context, field.value)) {
            is Rg01TimestampParseResult.Success -> Unit
            is Rg01TimestampParseResult.Error -> return attemptedContractError(
                "occurred_at",
                parsed.reason,
            )
        }
    }
    if (input.note == Rg01JsonField.Null) {
        return attemptedContractError("note", Rg01ContractErrorReason.NULL_NOT_ALLOWED)
    }
    if (input.explicitConfirmation == Rg01JsonField.Null) {
        return attemptedContractError(
            "explicit_confirmation",
            Rg01ContractErrorReason.NULL_NOT_ALLOWED,
        )
    }
    if (input.explicitConfirmation is Rg01JsonField.Value &&
        !input.explicitConfirmation.value
    ) {
        return attemptedContractError(
            "explicit_confirmation",
            Rg01ContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED,
        )
    }

    val amountText = input.amount.valueOrNull()
    val amount = amountText?.let { parseExactMoney(it, context.currency, allowNegativeZero = true) }
    if (amountText != null && amount == null) {
        return attemptedContractError("amount", Rg01ContractErrorReason.INVALID_DECIMAL)
    }

    val catalog = context.catalog
    val category = categoryText?.let { id ->
        catalog?.categories?.firstOrNull { it.id == CategoryId(id) }
    }
    val paymentAccount = paymentAccountText?.let { id ->
        catalog?.accounts?.firstOrNull { it.id == AccountId(id) }
    }
    if (
        categoryText != null &&
        (category == null || category.ledgerId != context.ledgerId)
    ) {
        return attemptedContractError("category_id", Rg01ContractErrorReason.INVALID_REFERENCE)
    }
    if (
        paymentAccountText != null &&
        (paymentAccount == null || paymentAccount.ledgerId != context.ledgerId)
    ) {
        return attemptedContractError(
            "payment_account_id",
            Rg01ContractErrorReason.INVALID_REFERENCE,
        )
    }

    val projection = when {
        input.amount !is Rg01JsonField.Value -> frozenRejection(
            "$.attempted_input.amount",
            "missing_required_field",
        )
        input.paymentAccountId !is Rg01JsonField.Value -> frozenRejection(
            "$.attempted_input.payment_account_id",
            "missing_required_field",
        )
        input.categoryId !is Rg01JsonField.Value -> frozenRejection(
            "$.attempted_input.category_id",
            "missing_required_field",
        )
        checkNotNull(amount).minorUnits <= 0L -> frozenRejection(
            "$.attempted_input.amount",
            "must_be_positive",
        )
        checkNotNull(category).parentId == null -> frozenRejection(
            "$.attempted_input.category_id",
            "secondary_category_required",
        )
        !checkNotNull(category).active -> frozenRejection(
            "$.attempted_input.category_id",
            "category_inactive",
        )
        else -> return attemptedContractError(
            "request_id",
            Rg01ContractErrorReason.UNREGISTERED_REJECTION,
        )
    }
    return Rg01AttemptedExpenseResult.Mapped(projection)
}

enum class Rg01OutcomeStatus {
    ACCEPTED,
    NO_CHANGE,
    REJECTED,
}

data class Rg01ReturnedId(
    val kind: String,
    val value: String,
)

data class Rg01OutcomeProjection(
    val status: Rg01OutcomeStatus,
    val fieldPath: String? = null,
    val reasonCode: String? = null,
    val returnedIds: Set<Rg01ReturnedId>,
)

sealed interface Rg01ProjectionResult {
    data class Mapped(val projection: Rg01OutcomeProjection) : Rg01ProjectionResult

    data class Unsupported(val result: ManualExpenseSaveResult) : Rg01ProjectionResult
}

fun projectRg01ManualExpenseResult(result: ManualExpenseSaveResult): Rg01ProjectionResult =
    when (result) {
        is ManualExpenseSaveResult.InvalidInput -> projectInvalidInput(result)
        is ManualExpenseSaveResult.Executed -> when (val executed = result.result) {
            is ConfirmedManualExpenseResult.Created -> Rg01ProjectionResult.Mapped(
                Rg01OutcomeProjection(
                    status = Rg01OutcomeStatus.ACCEPTED,
                    returnedIds = executed.receipt.asReturnedIds(),
                ),
            )
            is ConfirmedManualExpenseResult.NoChange -> Rg01ProjectionResult.Mapped(
                Rg01OutcomeProjection(
                    status = Rg01OutcomeStatus.NO_CHANGE,
                    reasonCode = "idempotent_replay",
                    returnedIds = executed.receipt.asReturnedIds(),
                ),
            )
            is ConfirmedManualExpenseResult.RequestIdentityConflict ->
                Rg01ProjectionResult.Unsupported(result)
            is ConfirmedManualExpenseResult.Rejected -> when (executed.violation) {
                OrdinaryExpenseViolation.AmountMustBePositive -> rejected(
                    fieldPath = "$.attempted_input.amount",
                    reasonCode = "must_be_positive",
                )
                OrdinaryExpenseViolation.SecondaryCategoryRequired -> rejected(
                    fieldPath = "$.attempted_input.category_id",
                    reasonCode = "secondary_category_required",
                )
                OrdinaryExpenseViolation.CategoryInactive -> rejected(
                    fieldPath = "$.attempted_input.category_id",
                    reasonCode = "category_inactive",
                )
                else -> Rg01ProjectionResult.Unsupported(result)
            }
        }
    }

private fun projectInvalidInput(
    result: ManualExpenseSaveResult.InvalidInput,
): Rg01ProjectionResult {
    val fields = result.failures.mapNotNull { failure ->
        (failure as? ManualExpenseInputFailure.Missing)?.field
    }.toSet()
    val fieldPath = when {
        ManualExpenseInputField.AMOUNT in fields -> "$.attempted_input.amount"
        ManualExpenseInputField.PAYMENT_ACCOUNT in fields -> "$.attempted_input.payment_account_id"
        ManualExpenseInputField.CATEGORY in fields -> "$.attempted_input.category_id"
        else -> return Rg01ProjectionResult.Unsupported(result)
    }
    return rejected(fieldPath, "missing_required_field")
}

private fun rejected(fieldPath: String, reasonCode: String): Rg01ProjectionResult =
    Rg01ProjectionResult.Mapped(
        Rg01OutcomeProjection(
            status = Rg01OutcomeStatus.REJECTED,
            fieldPath = fieldPath,
            reasonCode = reasonCode,
            returnedIds = emptySet(),
        ),
    )

private fun frozenRejection(fieldPath: String, reasonCode: String): Rg01OutcomeProjection =
    Rg01OutcomeProjection(
        status = Rg01OutcomeStatus.REJECTED,
        fieldPath = fieldPath,
        reasonCode = reasonCode,
        returnedIds = emptySet(),
    )

private fun attemptedContractError(
    fieldName: String,
    reason: Rg01ContractErrorReason,
): Rg01AttemptedExpenseResult.InvalidContract =
    Rg01AttemptedExpenseResult.InvalidContract(
        Rg01ContractError("$.attempted_input.$fieldName", reason),
    )

private fun <T> Rg01JsonField<T>.valueOrNull(): T? =
    (this as? Rg01JsonField.Value<T>)?.value

private fun String.isStableId(): Boolean =
    isNotEmpty() && all { character ->
        character.code !in 0..31 && character.code != 127
    }

private fun ConfirmedExpenseReceipt.asReturnedIds(): Set<Rg01ReturnedId> =
    setOf(
        Rg01ReturnedId("confirmation", confirmationId.value),
        Rg01ReturnedId("transaction", transactionId.value),
    )

private fun contractError(
    fieldName: String,
    reason: Rg01ContractErrorReason,
): Rg01ManualExpenseParseResult.InvalidContract =
    Rg01ManualExpenseParseResult.InvalidContract(
        Rg01ContractError("$.input.$fieldName", reason),
    )

private fun parseExactMoney(
    text: String,
    currency: CurrencyUnit,
    allowNegativeZero: Boolean = false,
): Money? {
    val precision = currency.precision
    if (precision < 0) return null
    val pattern = if (precision == 0) {
        Regex("-?(?:0|[1-9][0-9]*)")
    } else {
        Regex("-?(?:0|[1-9][0-9]*)\\.[0-9]{$precision}")
    }
    if (!pattern.matches(text)) return null

    val negative = text.startsWith('-')
    val unsigned = if (negative) text.substring(1) else text
    val digits = unsigned.replace(".", "")
    if (negative && digits.all { it == '0' } && !allowNegativeZero) return null
    val signedDigits = if (negative) "-$digits" else digits
    val minorUnits = signedDigits.toLongOrNull() ?: return null
    return Money.ofMinor(minorUnits, currency)
}

private val strictRfc3339 = Regex(
    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
        "(?:\\.[0-9]+)?(?:Z|\\+(?:0[0-9]|1[0-3]):[0-5][0-9]|\\+14:00|" +
        "-(?!00:00)(?:0[0-9]|1[0-3]):[0-5][0-9]|-14:00)",
)

private sealed interface Rg01TimestampParseResult {
    data class Success(val instant: Instant) : Rg01TimestampParseResult

    data class Error(val reason: Rg01ContractErrorReason) : Rg01TimestampParseResult
}

private fun parseCaseTimestamp(
    context: Rg01ManualExpenseContext,
    text: String,
): Rg01TimestampParseResult {
    if (
        context.caseTimeZone != "Asia/Shanghai" ||
        context.validNumericOffset != "+08:00"
    ) {
        return Rg01TimestampParseResult.Error(Rg01ContractErrorReason.UNSUPPORTED_TIMEZONE)
    }
    if (!strictRfc3339.matches(text) || text.endsWith("-00:00")) {
        return Rg01TimestampParseResult.Error(Rg01ContractErrorReason.INVALID_TIMESTAMP)
    }
    if (!text.endsWith('Z') && !text.endsWith(context.validNumericOffset)) {
        return Rg01TimestampParseResult.Error(
            Rg01ContractErrorReason.TIMEZONE_OFFSET_MISMATCH,
        )
    }
    return try {
        Rg01TimestampParseResult.Success(Instant.parse(text))
    } catch (_: IllegalArgumentException) {
        Rg01TimestampParseResult.Error(Rg01ContractErrorReason.INVALID_TIMESTAMP)
    }
}
