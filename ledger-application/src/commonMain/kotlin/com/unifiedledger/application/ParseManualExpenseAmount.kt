package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.parseExactDecimal

/**
 * P5-03 exact manual-expense amount parser (D-119 section 3.3; plan section 3.2.1).
 *
 * The parser trims only ASCII space/tab/CR/LF from both ends, rejects a remaining empty
 * string and any internal ASCII whitespace, then delegates the exact grammar to the shared
 * domain [parseExactDecimal] with the precision of the selected payment account. The UI
 * never supplies its own precision and this wrapper is never duplicated in a platform root.
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
                ?: return Result.Invalid(ManualExpenseAmountFormatError.INVALID_FORMAT)
        return Result.Valid(minorUnits)
    }

    private companion object {
        fun isAsciiWhitespace(character: Char): Boolean = character == ' ' || character == '\t' || character == '\r' || character == '\n'
    }
}
