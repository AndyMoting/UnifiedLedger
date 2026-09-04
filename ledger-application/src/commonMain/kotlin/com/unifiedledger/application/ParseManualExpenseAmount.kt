package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.parseExactDecimal
import com.unifiedledger.domain.parseExactDecimalLenient

/**
 * P5-03 exact manual-expense amount parser (D-119 section 3.3; plan section 3.2.1; D-131
 * R1 spec 2.3).
 *
 * The parser trims only ASCII space/tab/CR/LF from both ends, rejects a remaining empty
 * string and any internal ASCII whitespace, then parses strict-first with the shared
 * domain [parseExactDecimal] against the precision of the selected payment account. Only
 * when strict parsing fails does it fall back to the lenient [parseExactDecimalLenient]
 * (zero-padded short fractions, exact-division long fractions), so every previously
 * accepted input keeps its byte-identical result. The UI never supplies its own precision
 * and this wrapper is never duplicated in a platform root.
 */
enum class ManualExpenseAmountFormatError {
    EMPTY,
    INTERNAL_WHITESPACE,
    INVALID_FORMAT,
}

class ParseManualExpenseAmount {
    sealed interface Result {
        data class Valid(
            val minorUnits: Long,
        ) : Result

        data class Invalid(
            val error: ManualExpenseAmountFormatError,
        ) : Result
    }

    fun parse(
        text: String,
        currency: CurrencyUnit,
    ): Result {
        val trimmed = text.trim(::isAsciiWhitespace)
        if (trimmed.isEmpty()) {
            return Result.Invalid(ManualExpenseAmountFormatError.EMPTY)
        }
        if (trimmed.any(::isAsciiWhitespace)) {
            return Result.Invalid(ManualExpenseAmountFormatError.INTERNAL_WHITESPACE)
        }
        val minorUnits =
            parseExactDecimal(trimmed, currency.precision)
                ?: parseExactDecimalLenient(trimmed, currency.precision)
                ?: return Result.Invalid(ManualExpenseAmountFormatError.INVALID_FORMAT)
        return Result.Valid(minorUnits)
    }

    private companion object {
        fun isAsciiWhitespace(character: Char): Boolean = character == ' ' || character == '\t' || character == '\r' || character == '\n'
    }
}
