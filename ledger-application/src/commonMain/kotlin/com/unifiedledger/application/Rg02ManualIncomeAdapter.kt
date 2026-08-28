package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import kotlin.time.Instant

data class Rg02ManualIncomeContext(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val caseTimeZone: String = "Asia/Shanghai",
    val validNumericOffset: String = "+08:00",
    val catalog: LedgerCatalog? = null,
)

data class Rg02ParsedManualIncomeInput(
    val saveInput: ManualIncomeSaveInput,
    val originalAmountText: String?,
    val originalOccurredAtText: String,
)

enum class Rg02ManualIncomeContractErrorReason {
    MISSING_REQUIRED_FIELD,
    NULL_NOT_ALLOWED,
    INVALID_DECIMAL,
    INVALID_TIMESTAMP,
    CURRENCY_MISMATCH,
    EXPLICIT_CONFIRMATION_REQUIRED,
    INVALID_ID,
    TIMEZONE_OFFSET_MISMATCH,
    UNSUPPORTED_TIMEZONE,
}

data class Rg02ManualIncomeContractError(
    val fieldPath: String,
    val reason: Rg02ManualIncomeContractErrorReason,
)

sealed interface Rg02ManualIncomeAdaptResult {
    data class Success(
        val value: Rg02ParsedManualIncomeInput,
    ) : Rg02ManualIncomeAdaptResult

    data class InvalidContract(
        val error: Rg02ManualIncomeContractError,
    ) : Rg02ManualIncomeAdaptResult
}

fun adaptRg02ManualIncomeInput(
    context: Rg02ManualIncomeContext,
    input: Rg02DecodedManualIncomeInput,
): Rg02ManualIncomeAdaptResult {
    if (!context.ledgerId.value.isRg02StableId()) {
        return rg02IncomeContractError("$.case.ledger_id", Rg02ManualIncomeContractErrorReason.INVALID_ID)
    }

    val requestId =
        when (val field = input.requestId) {
            Rg02JsonField.Omitted -> return rg02IncomeInputError(
                "request_id",
                Rg02ManualIncomeContractErrorReason.MISSING_REQUIRED_FIELD,
            )
            Rg02JsonField.Null -> return rg02IncomeInputError(
                "request_id",
                Rg02ManualIncomeContractErrorReason.NULL_NOT_ALLOWED,
            )
            is Rg02JsonField.Value -> field.value
        }
    if (!requestId.isRg02StableId()) {
        return rg02IncomeInputError("request_id", Rg02ManualIncomeContractErrorReason.INVALID_ID)
    }

    val currency =
        when (val field = input.currency) {
            Rg02JsonField.Omitted -> return rg02IncomeInputError(
                "currency",
                Rg02ManualIncomeContractErrorReason.MISSING_REQUIRED_FIELD,
            )
            Rg02JsonField.Null -> return rg02IncomeInputError(
                "currency",
                Rg02ManualIncomeContractErrorReason.NULL_NOT_ALLOWED,
            )
            is Rg02JsonField.Value -> field.value
        }
    if (currency != context.currency.code) {
        return rg02IncomeInputError(
            "currency",
            Rg02ManualIncomeContractErrorReason.CURRENCY_MISMATCH,
        )
    }

    val originalOccurredAtText =
        when (val field = input.occurredAt) {
            Rg02JsonField.Omitted -> return rg02IncomeInputError(
                "occurred_at",
                Rg02ManualIncomeContractErrorReason.MISSING_REQUIRED_FIELD,
            )
            Rg02JsonField.Null -> return rg02IncomeInputError(
                "occurred_at",
                Rg02ManualIncomeContractErrorReason.NULL_NOT_ALLOWED,
            )
            is Rg02JsonField.Value -> field.value
        }
    val occurredAt =
        when (
            val parsed = parseRg02CaseTimestamp(context, originalOccurredAtText)
        ) {
            is Rg02TimestampParseResult.Success -> parsed.instant
            is Rg02TimestampParseResult.Error -> return rg02IncomeInputError(
                "occurred_at",
                parsed.reason,
            )
        }

    val confirmation =
        when (val field = input.explicitConfirmation) {
            Rg02JsonField.Omitted -> return rg02IncomeInputError(
                "explicit_confirmation",
                Rg02ManualIncomeContractErrorReason.MISSING_REQUIRED_FIELD,
            )
            Rg02JsonField.Null -> return rg02IncomeInputError(
                "explicit_confirmation",
                Rg02ManualIncomeContractErrorReason.NULL_NOT_ALLOWED,
            )
            is Rg02JsonField.Value -> field.value
        }
    if (!confirmation) {
        return rg02IncomeInputError(
            "explicit_confirmation",
            Rg02ManualIncomeContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED,
        )
    }

    val originalAmountText = input.amount.rg02ValueOrNull()
    val amount = originalAmountText?.let { parseRg02ExactMoney(it, context.currency) }
    if (originalAmountText != null && amount == null) {
        return rg02IncomeInputError("amount", Rg02ManualIncomeContractErrorReason.INVALID_DECIMAL)
    }

    val categoryId =
        when (val field = input.categoryId) {
            Rg02JsonField.Omitted, Rg02JsonField.Null -> null
            is Rg02JsonField.Value -> {
                if (!field.value.isRg02StableId()) {
                    return rg02IncomeInputError(
                        "category_id",
                        Rg02ManualIncomeContractErrorReason.INVALID_ID,
                    )
                }
                CategoryId(field.value)
            }
        }
    val receivingAccountId =
        when (val field = input.receivingAccountId) {
            Rg02JsonField.Omitted, Rg02JsonField.Null -> null
            is Rg02JsonField.Value -> {
                if (!field.value.isRg02StableId()) {
                    return rg02IncomeInputError(
                        "receiving_account_id",
                        Rg02ManualIncomeContractErrorReason.INVALID_ID,
                    )
                }
                AccountId(field.value)
            }
        }
    val note =
        when (val field = input.note) {
            Rg02JsonField.Omitted -> ""
            Rg02JsonField.Null -> return rg02IncomeInputError(
                "note",
                Rg02ManualIncomeContractErrorReason.NULL_NOT_ALLOWED,
            )
            is Rg02JsonField.Value -> field.value
        }

    return Rg02ManualIncomeAdaptResult.Success(
        Rg02ParsedManualIncomeInput(
            saveInput =
                ManualIncomeSaveInput(
                    ledgerId = context.ledgerId,
                    requestId = RequestId(requestId),
                    amount = amount,
                    categoryId = categoryId,
                    receivingAccountId = receivingAccountId,
                    occurredAt = occurredAt,
                    note = note,
                    confirmation = ExplicitManualSave,
                ),
            originalAmountText = originalAmountText,
            originalOccurredAtText = originalOccurredAtText,
        ),
    )
}

sealed interface Rg02CategoryRenameProjection {
    data class Unsupported(
        val request: Rg02DecodedCategoryRename,
    ) : Rg02CategoryRenameProjection
}

fun projectRg02CategoryRename(
    request: Rg02DecodedCategoryRename,
): Rg02CategoryRenameProjection = Rg02CategoryRenameProjection.Unsupported(request)

private fun parseRg02ExactMoney(
    text: String,
    currency: CurrencyUnit,
): Money? {
    val precision = currency.precision
    if (precision < 0) return null
    val pattern =
        if (precision == 0) {
            Regex("-?(?:0|[1-9][0-9]*)")
        } else {
            Regex("-?(?:0|[1-9][0-9]*)\\.[0-9]{$precision}")
        }
    if (!pattern.matches(text)) return null

    val negative = text.startsWith('-')
    val unsigned = if (negative) text.substring(1) else text
    val digits = unsigned.replace(".", "")
    if (negative && digits.all { it == '0' }) return null
    val signedDigits = if (negative) "-$digits" else digits
    val minorUnits = signedDigits.toLongOrNull() ?: return null
    return Money.ofMinor(minorUnits, currency)
}

private val rg02StrictRfc3339 =
    Regex(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
            "(?:\\.[0-9]+)?(?:Z|\\+(?:0[0-9]|1[0-3]):[0-5][0-9]|\\+14:00|" +
            "-(?!00:00)(?:0[0-9]|1[0-3]):[0-5][0-9]|-14:00)",
    )

private sealed interface Rg02TimestampParseResult {
    data class Success(
        val instant: Instant,
    ) : Rg02TimestampParseResult

    data class Error(
        val reason: Rg02ManualIncomeContractErrorReason,
    ) : Rg02TimestampParseResult
}

private fun parseRg02CaseTimestamp(
    context: Rg02ManualIncomeContext,
    text: String,
): Rg02TimestampParseResult {
    if (
        context.caseTimeZone != "Asia/Shanghai" ||
        context.validNumericOffset != "+08:00"
    ) {
        return Rg02TimestampParseResult.Error(
            Rg02ManualIncomeContractErrorReason.UNSUPPORTED_TIMEZONE,
        )
    }
    if (!rg02StrictRfc3339.matches(text) || text.endsWith("-00:00")) {
        return Rg02TimestampParseResult.Error(
            Rg02ManualIncomeContractErrorReason.INVALID_TIMESTAMP,
        )
    }
    if (!text.endsWith('Z') && !text.endsWith(context.validNumericOffset)) {
        return Rg02TimestampParseResult.Error(
            Rg02ManualIncomeContractErrorReason.TIMEZONE_OFFSET_MISMATCH,
        )
    }
    return try {
        Rg02TimestampParseResult.Success(Instant.parse(text))
    } catch (_: IllegalArgumentException) {
        Rg02TimestampParseResult.Error(
            Rg02ManualIncomeContractErrorReason.INVALID_TIMESTAMP,
        )
    }
}

private fun String.isRg02StableId(): Boolean =
    isNotEmpty() &&
        all { character ->
            character.code !in 0..31 && character.code != 127
        }

private fun <T> Rg02JsonField<T>.rg02ValueOrNull(): T? = (this as? Rg02JsonField.Value<T>)?.value

private fun rg02IncomeInputError(
    fieldName: String,
    reason: Rg02ManualIncomeContractErrorReason,
): Rg02ManualIncomeAdaptResult.InvalidContract = rg02IncomeContractError("$.input.$fieldName", reason)

private fun rg02IncomeContractError(
    fieldPath: String,
    reason: Rg02ManualIncomeContractErrorReason,
): Rg02ManualIncomeAdaptResult.InvalidContract =
    Rg02ManualIncomeAdaptResult.InvalidContract(
        Rg02ManualIncomeContractError(fieldPath, reason),
    )
