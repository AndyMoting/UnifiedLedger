package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import kotlin.time.Instant

data class Rg03AdapterContext(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val caseTimeZone: String = "Asia/Shanghai",
    val validNumericOffset: String = "+08:00",
    val defaultFeeCategoryId: CategoryId = CategoryId("expense-category-transfer-fee"),
)

enum class Rg03ContractErrorReason {
    MISSING_REQUIRED_FIELD,
    NULL_NOT_ALLOWED,
    INVALID_DECIMAL,
    NEGATIVE_FEE,
    INVALID_TIMESTAMP,
    TIMEZONE_OFFSET_MISMATCH,
    UNSUPPORTED_TIMEZONE,
    INVALID_ID,
    CURRENCY_MISMATCH,
    SAME_CURRENCY_REQUIRED,
    EXPLICIT_CONFIRMATION_REQUIRED,
    INVALID_COMPLETENESS,
}

data class Rg03ContractError(
    val fieldPath: String,
    val reason: Rg03ContractErrorReason,
)

sealed interface Rg03AdaptResult {
    data class Success(val command: Rg03Command) : Rg03AdaptResult
    data class Invalid(val error: Rg03ContractError) : Rg03AdaptResult
}

fun adaptRg03Operation(
    context: Rg03AdapterContext,
    operation: Rg03DecodedOperation,
): Rg03AdaptResult = try {
    if (!context.ledgerId.value.isRg03StableId()) fail("case.ledger_id", Rg03ContractErrorReason.INVALID_ID)
    if (context.currency.code != "CNY" || context.currency.precision != 2) {
        fail("case.currency", Rg03ContractErrorReason.CURRENCY_MISMATCH)
    }
    when (operation.actionType) {
        Rg03ActionType.MANUAL_ACCOUNT_TRANSFER -> adaptManual(context, operation.input)
        Rg03ActionType.IMPORT_SOURCE_RECORD -> adaptSource(context, operation.input)
        Rg03ActionType.EXPLICIT_CANDIDATE_CONFIRMATION -> adaptConfirmation(context, operation.input)
        Rg03ActionType.IMPORT_MIRROR_RECORD -> adaptMirror(context, operation.input)
    }
} catch (failure: Rg03AdaptFailure) {
    failure.invalid
}

private fun adaptManual(context: Rg03AdapterContext, input: Rg03DecodedInput): Rg03AdaptResult {
    val requestId = requiredId(input.requestId, "request_id")
    val occurredAt = requiredTimestamp(context, input.occurredAt, "occurred_at")
    val occurredAtText = (input.occurredAt as Rg03JsonField.Value).value
    val sourceAccount = requiredId(input.sourceAccountId, "source_account_id")
    val destinationAccount = requiredId(input.destinationAccountId, "destination_account_id")
    val sourceDebit = requiredMoney(context, input.sourceDebitAmount, "source_debit_amount")
    val destinationCredit = requiredMoney(context, input.destinationCreditAmount, "destination_credit_amount")
    val fee = requiredMoney(context, input.feeAmount, "fee_amount")
    if (fee.minorUnits < 0L) return bad("$.input.fee_amount", Rg03ContractErrorReason.NEGATIVE_FEE)
    sameCurrencyInput(context, input)
    val feeCategory = requiredId(input.feeCategoryId, "fee_category_id")
    val confirmed = requiredBoolean(input.explicitConfirmation, "explicit_confirmation")
    if (!confirmed) return bad("$.input.explicit_confirmation", Rg03ContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED)
    return Rg03AdaptResult.Success(
        Rg03Command.ManualTransfer(
            Rg03ManualTransferSnapshot(
                context.ledgerId,
                RequestId(requestId),
                occurredAt,
                AccountId(sourceAccount),
                AccountId(destinationAccount),
                sourceDebit,
                destinationCredit,
                fee,
                CategoryId(feeCategory),
                occurredAtText,
            ),
            input,
        ),
    )
}

private fun adaptSource(context: Rg03AdapterContext, input: Rg03DecodedInput): Rg03AdaptResult {
    val requestId = requiredId(input.requestId, "request_id")
    val sourceId = requiredId(input.sourceId, "source_record.id")
    val evidenceId = requiredId(input.evidenceId, "source_record.evidence_id")
    val observedAt = requiredTimestamp(context, input.observedAt, "source_record.observed_at")
    val observedAtText = (input.observedAt as Rg03JsonField.Value).value
    val sourceAccount = requiredId(input.sourceAccountId, "source_record.source_account_id")
    val sourceDebit = requiredMoney(context, input.sourceDebitAmount, "source_record.source_debit_amount")
    sameCurrencyInput(context, input, "source_record.currency")
    val completeness = when (val value = requiredString(input.completeness, "source_record.completeness")) {
        "complete" -> SourceCompleteness.COMPLETE
        "missing_destination" -> SourceCompleteness.MISSING_DESTINATION
        else -> return bad("$.input.source_record.completeness", Rg03ContractErrorReason.INVALID_COMPLETENESS)
    }
    val destinationAccount: AccountId?
    val destinationCredit: Money?
    val fee: Money?
    if (completeness == SourceCompleteness.COMPLETE) {
        destinationAccount = AccountId(requiredId(input.destinationAccountId, "source_record.destination_account_id"))
        destinationCredit = requiredMoney(context, input.destinationCreditAmount, "source_record.destination_credit_amount")
        fee = requiredMoney(context, input.feeAmount, "source_record.fee_amount")
        if (fee.minorUnits < 0L) return bad("$.input.source_record.fee_amount", Rg03ContractErrorReason.NEGATIVE_FEE)
    } else {
        destinationAccount = optionalId(input.destinationAccountId, "source_record.destination_account_id")
        destinationCredit = optionalMoney(context, input.destinationCreditAmount, "source_record.destination_credit_amount")
        fee = optionalMoney(context, input.feeAmount, "source_record.fee_amount")
    }
    return Rg03AdaptResult.Success(
        Rg03Command.ImportSource(
            Rg03SourceSnapshot(
                context.ledgerId,
                RequestId(requestId),
                SourceRecordId(sourceId),
                EvidenceId(evidenceId),
                observedAt,
                AccountId(sourceAccount),
                destinationAccount,
                sourceDebit,
                destinationCredit,
                fee,
                context.defaultFeeCategoryId,
                completeness,
                observedAtText,
            ),
        ),
    )
}

private fun adaptConfirmation(context: Rg03AdapterContext, input: Rg03DecodedInput): Rg03AdaptResult {
    val requestId = requiredId(input.requestId, "request_id")
    val candidateId = requiredId(input.candidateId, "candidate_id")
    val confirmed = requiredBoolean(input.confirmed, "confirmed")
    if (!confirmed) return bad("$.input.confirmed", Rg03ContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED)
    return Rg03AdaptResult.Success(
        Rg03Command.ConfirmCandidate(RequestId(requestId), CandidateId(candidateId), confirmed, context.ledgerId),
    )
}

private fun adaptMirror(context: Rg03AdapterContext, input: Rg03DecodedInput): Rg03AdaptResult {
    val requestId = requiredId(input.requestId, "request_id")
    val sourceId = requiredId(input.sourceId, "source_record.id")
    val evidenceId = requiredId(input.evidenceId, "source_record.evidence_id")
    val observedAt = requiredTimestamp(context, input.observedAt, "source_record.observed_at")
    val observedAtText = (input.observedAt as Rg03JsonField.Value).value
    val accountId = requiredId(input.accountId, "source_record.account_id")
    val credit = requiredMoney(context, input.creditAmount, "source_record.credit_amount")
    sameCurrencyInput(context, input, "source_record.currency")
    return Rg03AdaptResult.Success(
        Rg03Command.ImportMirror(
            Rg03MirrorSnapshot(
                context.ledgerId,
                RequestId(requestId),
                SourceRecordId(sourceId),
                EvidenceId(evidenceId),
                observedAt,
                AccountId(accountId),
                credit,
                observedAtText,
            ),
        ),
    )
}

private fun requiredString(field: Rg03JsonField<String>, name: String): String = when (field) {
    Rg03JsonField.Omitted -> fail(name, Rg03ContractErrorReason.MISSING_REQUIRED_FIELD)
    Rg03JsonField.Null -> fail(name, Rg03ContractErrorReason.NULL_NOT_ALLOWED)
    is Rg03JsonField.Value -> field.value
}

private fun requiredId(field: Rg03JsonField<String>, name: String): String {
    val value = requiredString(field, name)
    return if (value.isRg03StableId()) value else fail(name, Rg03ContractErrorReason.INVALID_ID)
}

private fun optionalId(field: Rg03JsonField<String>, name: String): AccountId? = when (field) {
    Rg03JsonField.Omitted, Rg03JsonField.Null -> null
    is Rg03JsonField.Value -> if (field.value.isRg03StableId()) AccountId(field.value) else {
        fail(name, Rg03ContractErrorReason.INVALID_ID)
    }
}

private fun requiredBoolean(field: Rg03JsonField<Boolean>, name: String): Boolean = when (field) {
    Rg03JsonField.Omitted -> fail(name, Rg03ContractErrorReason.MISSING_REQUIRED_FIELD)
    Rg03JsonField.Null -> fail(name, Rg03ContractErrorReason.NULL_NOT_ALLOWED)
    is Rg03JsonField.Value -> field.value
}

private fun requiredMoney(context: Rg03AdapterContext, field: Rg03JsonField<String>, name: String): Money {
    val value = requiredString(field, name)
    return parseExactMoney(value, context.currency) ?: fail(name, Rg03ContractErrorReason.INVALID_DECIMAL)
}

private fun optionalMoney(context: Rg03AdapterContext, field: Rg03JsonField<String>, name: String): Money? = when (field) {
    Rg03JsonField.Omitted, Rg03JsonField.Null -> null
    is Rg03JsonField.Value -> parseExactMoney(field.value, context.currency) ?: run {
        fail(name, Rg03ContractErrorReason.INVALID_DECIMAL)
    }
}

private fun requiredTimestamp(context: Rg03AdapterContext, field: Rg03JsonField<String>, name: String): Instant {
    val value = requiredString(field, name)
    if (context.caseTimeZone != "Asia/Shanghai" || context.validNumericOffset != "+08:00") {
        return fail(name, Rg03ContractErrorReason.UNSUPPORTED_TIMEZONE)
    }
    if (!RG03_RFC3339.matches(value) || value.endsWith("-00:00")) {
        return fail(name, Rg03ContractErrorReason.INVALID_TIMESTAMP)
    }
    if (!value.endsWith('Z') && !value.endsWith(context.validNumericOffset)) {
        return fail(name, Rg03ContractErrorReason.TIMEZONE_OFFSET_MISMATCH)
    }
    return try { Instant.parse(value) } catch (_: IllegalArgumentException) {
        fail(name, Rg03ContractErrorReason.INVALID_TIMESTAMP)
    }
}

private fun sameCurrencyInput(
    context: Rg03AdapterContext,
    input: Rg03DecodedInput,
    fieldName: String = "currency",
): Unit {
    val currency = presentCurrency(input.currency, fieldName)
    val source = presentCurrency(input.sourceCurrency, "source_currency")
    val destination = presentCurrency(input.destinationCurrency, "destination_currency")
    val primary = currency ?: source
        ?: fail(fieldName, Rg03ContractErrorReason.MISSING_REQUIRED_FIELD)
    if (currency != null && source != null && source != currency) {
        fail("source_currency", Rg03ContractErrorReason.SAME_CURRENCY_REQUIRED)
    }
    if (destination != null && destination != primary) {
        fail("destination_currency", Rg03ContractErrorReason.SAME_CURRENCY_REQUIRED)
    }
    if (primary != context.currency.code) {
        fail(if (currency != null) fieldName else "source_currency", Rg03ContractErrorReason.CURRENCY_MISMATCH)
    }
}

private fun presentCurrency(field: Rg03JsonField<String>, name: String): String? = when (field) {
    Rg03JsonField.Omitted -> null
    Rg03JsonField.Null -> fail(name, Rg03ContractErrorReason.NULL_NOT_ALLOWED)
    is Rg03JsonField.Value -> field.value
}

private fun parseExactMoney(text: String, currency: CurrencyUnit): Money? {
    val pattern = if (currency.precision == 0) {
        Regex("-?(?:0|[1-9][0-9]*)")
    } else {
        Regex("-?(?:0|[1-9][0-9]*)\\.[0-9]{${currency.precision}}")
    }
    if (!pattern.matches(text)) return null
    val negative = text.startsWith('-')
    val digits = (if (negative) text.substring(1) else text).replace(".", "")
    if (negative && digits.all { it == '0' }) return null
    return (if (negative) "-$digits" else digits).toLongOrNull()?.let { Money.ofMinor(it, currency) }
}

private fun String.isRg03StableId(): Boolean = isNotEmpty() && all { it.code !in 0..31 && it.code != 127 }

private fun fail(name: String, reason: Rg03ContractErrorReason): Nothing {
    val path = if (name.startsWith("case.")) "$.${name}" else "$.input.$name"
    throw Rg03AdaptFailure(bad(path, reason))
}

private fun bad(path: String, reason: Rg03ContractErrorReason) =
    Rg03AdaptResult.Invalid(Rg03ContractError(path, reason))

private class Rg03AdaptFailure(val invalid: Rg03AdaptResult.Invalid) : RuntimeException()

private val RG03_RFC3339 = Regex(
    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
        "(?:\\.[0-9]+)?(?:Z|\\+(?:0[0-9]|1[0-3]):[0-5][0-9]|\\+14:00|" +
        "-(?!00:00)(?:0[0-9]|1[0-3]):[0-5][0-9]|-14:00)",
)
