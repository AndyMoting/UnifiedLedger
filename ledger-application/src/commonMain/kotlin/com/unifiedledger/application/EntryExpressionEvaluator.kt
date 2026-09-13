package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.pow10Exact

/**
 * P7-02 E-3/P2-2: the stable, typed rejection codes of the frozen entry-expression grammar
 * (spec section 5.3). `code` is the stable failure string; the enum name is never compared.
 */
enum class EntryExpressionCode(
    val code: String,
) {
    Invalid("EntryExpressionInvalid"),
    DivideByZero("EntryExpressionDivideByZero"),
    Overflow("EntryExpressionOverflow"),
    NonCurrencyPrecision("EntryExpressionNonCurrencyPrecision"),
    NegativeResult("EntryExpressionNegativeResult"),
}

/**
 * P7-02 E-3/P2-2 pure entry-expression evaluator (application layer, zero IO).
 *
 * Frozen grammar (spec section 3.4 E-3): non-negative decimal literals, the binary operators
 * `+ - * × / ÷` and parentheses; standard precedence (`× ÷` above `+ -`); parentheses nest.
 * Unary minus and negative literals do not exist — a `-` is only valid as a binary infix
 * operator, so such input is rejected as [EntryExpressionCode.Invalid]. Frozen limits (R-2):
 * at most [MAX_TOKEN_COUNT] tokens and a nesting depth of at most [MAX_PAREN_DEPTH].
 *
 * Every value (literal, intermediate and final) is carried in exact Long minor units at the
 * target currency precision; binary floating point and silent rounding are forbidden. A
 * multiplication or division whose exact result is not representable at the target precision
 * (for example `1/3`, which never terminates, or `1/8 = 0.125`, which needs a third nonzero
 * fraction digit) is a typed [EntryExpressionCode.NonCurrencyPrecision] rejection; division by
 * zero, checked-operation overflow and a negative final result map to their own typed codes.
 */
class EntryExpressionEvaluator {
    sealed interface Result {
        data class Valid(
            val minorUnits: Long,
        ) : Result

        data class Invalid(
            val code: EntryExpressionCode,
        ) : Result
    }

    fun evaluate(
        expression: String,
        currency: CurrencyUnit,
    ): Result {
        val precision = currency.precision
        if (precision !in 0..18) return Result.Invalid(EntryExpressionCode.Invalid)
        val trimmed = expression.trim(::isAsciiWhitespace)
        if (trimmed.isEmpty()) return Result.Invalid(EntryExpressionCode.Invalid)
        val tokens =
            when (val outcome = lex(trimmed, precision)) {
                is LexOutcome.Failure -> return Result.Invalid(outcome.code)
                is LexOutcome.Tokens -> outcome.tokens
            }
        if (tokens.isEmpty()) return Result.Invalid(EntryExpressionCode.Invalid)
        val parser = Parser(tokens, precision)
        val value = parser.parse() ?: return Result.Invalid(checkNotNull(parser.failure))
        if (value < 0L) return Result.Invalid(EntryExpressionCode.NegativeResult)
        return Result.Valid(value)
    }

    // ---- lexical stage (frozen shape; any violation is a typed rejection) ----

    private sealed interface LexToken

    private data object LeftParen : LexToken

    private data object RightParen : LexToken

    private data class Operator(
        val symbol: Char,
    ) : LexToken

    private data class Numeral(
        val minorUnits: Long,
    ) : LexToken

    private sealed interface LexOutcome {
        data class Tokens(
            val tokens: List<LexToken>,
        ) : LexOutcome

        data class Failure(
            val code: EntryExpressionCode,
        ) : LexOutcome
    }

    private sealed interface LiteralOutcome {
        data class Value(
            val minorUnits: Long,
        ) : LiteralOutcome

        data object Overflow : LiteralOutcome

        data object NonCurrencyPrecision : LiteralOutcome
    }

    private fun lex(
        trimmed: String,
        precision: Int,
    ): LexOutcome {
        val tokens = ArrayList<LexToken>()
        var depth = 0
        var index = 0
        while (index < trimmed.length) {
            if (tokens.size >= MAX_TOKEN_COUNT) return LexOutcome.Failure(EntryExpressionCode.Invalid)
            when (val character = trimmed[index]) {
                '(' -> {
                    depth++
                    if (depth > MAX_PAREN_DEPTH) return LexOutcome.Failure(EntryExpressionCode.Invalid)
                    tokens.add(LeftParen)
                    index++
                }
                ')' -> {
                    depth--
                    if (depth < 0) return LexOutcome.Failure(EntryExpressionCode.Invalid)
                    tokens.add(RightParen)
                    index++
                }
                '+', '-', '*', '×', '/', '÷' -> {
                    tokens.add(Operator(character))
                    index++
                }
                else -> {
                    // P702IMPL-03: the frozen grammar is ASCII decimal only; Char.isDigit() would
                    // also accept Unicode Nd digits on some platforms.
                    if (character !in '0'..'9') return LexOutcome.Failure(EntryExpressionCode.Invalid)
                    val numberStart = index
                    while (index < trimmed.length && trimmed[index] in '0'..'9') index++
                    var fractionDigits: String? = null
                    if (index < trimmed.length && trimmed[index] == '.') {
                        index++
                        val fractionStart = index
                        if (index >= trimmed.length || trimmed[index] !in '0'..'9') {
                            return LexOutcome.Failure(EntryExpressionCode.Invalid)
                        }
                        while (index < trimmed.length && trimmed[index] in '0'..'9') index++
                        fractionDigits = trimmed.substring(fractionStart, index)
                    }
                    val literalText = trimmed.substring(numberStart, index)
                    val intText = literalText.substringBefore('.')
                    if (intText.length > 1 && intText[0] == '0') return LexOutcome.Failure(EntryExpressionCode.Invalid)
                    when (val literal = literalMinorUnits(intText, fractionDigits, precision)) {
                        is LiteralOutcome.Value -> tokens.add(Numeral(literal.minorUnits))
                        LiteralOutcome.Overflow -> return LexOutcome.Failure(EntryExpressionCode.Overflow)
                        LiteralOutcome.NonCurrencyPrecision -> return LexOutcome.Failure(EntryExpressionCode.NonCurrencyPrecision)
                    }
                }
            }
        }
        if (depth != 0) return LexOutcome.Failure(EntryExpressionCode.Invalid)
        return LexOutcome.Tokens(tokens)
    }

    /**
     * Exact minor units of one decimal literal at [precision]: fraction digits beyond the
     * precision must all be zero (exact division, never rounding); a magnitude beyond the Long
     * minor-unit range is an overflow.
     */
    private fun literalMinorUnits(
        intText: String,
        fractionDigits: String?,
        precision: Int,
    ): LiteralOutcome {
        val whole = intText.toLongOrNull() ?: return LiteralOutcome.Overflow
        val scale = pow10Exact(precision) ?: return LiteralOutcome.Overflow
        val maxWhole = (Long.MAX_VALUE - (scale - 1L)) / scale
        if (whole > maxWhole) return LiteralOutcome.Overflow
        val significantFraction =
            when {
                fractionDigits == null -> ""
                fractionDigits.length > precision ->
                    if (fractionDigits.substring(precision).any { it != '0' }) {
                        return LiteralOutcome.NonCurrencyPrecision
                    } else {
                        fractionDigits.take(precision)
                    }
                else -> fractionDigits
            }.padEnd(precision, '0')
        val fraction = if (significantFraction.isEmpty()) 0L else significantFraction.toLongOrNull() ?: return LiteralOutcome.Overflow
        return LiteralOutcome.Value(whole * scale + fraction)
    }

    // ---- recursive-descent parse + exact signed minor-unit arithmetic ----

    private class Parser(
        private val tokens: List<LexToken>,
        precision: Int,
    ) {
        /** Non-null when [parse] produced a null value: the typed reason of the rejection. */
        var failure: EntryExpressionCode? = null

        private val scale: Long = pow10Exact(precision) ?: 1L
        private var position = 0
        private var arithmeticFailure: EntryExpressionCode? = null

        fun parse(): Long? {
            val value = expression() ?: return null
            if (position != tokens.size) {
                failure = EntryExpressionCode.Invalid
                return null
            }
            return value
        }

        /** `expr := term (('+' | '-') term)*` */
        private fun expression(): Long? {
            var left = term() ?: return null
            while (position < tokens.size && (isSymbol('+') || isSymbol('-'))) {
                val symbol = (tokens[position] as Operator).symbol
                position++
                val right = term() ?: return null
                left =
                    if (symbol == '+') {
                        checkedAdd(left, right) ?: return reject(EntryExpressionCode.Overflow)
                    } else {
                        checkedSubtract(left, right) ?: return reject(EntryExpressionCode.Overflow)
                    }
            }
            return left
        }

        /** `term := factor (('*' | '×' | '/' | '÷') factor)*` */
        private fun term(): Long? {
            var left = factor() ?: return null
            while (position < tokens.size && (isSymbol('*') || isSymbol('×') || isSymbol('/') || isSymbol('÷'))) {
                val symbol = (tokens[position] as Operator).symbol
                position++
                val right = factor() ?: return null
                left =
                    if (symbol == '/' || symbol == '÷') {
                        divide(left, right) ?: return reject(checkNotNull(arithmeticFailure))
                    } else {
                        multiply(left, right) ?: return reject(checkNotNull(arithmeticFailure))
                    }
            }
            return left
        }

        /** `factor := numeral | '(' expr ')'` — no unary sign exists in the frozen grammar. */
        private fun factor(): Long? =
            when (val token = tokens.getOrNull(position)) {
                is Numeral -> {
                    position++
                    token.minorUnits
                }
                is LeftParen -> {
                    position++
                    val value = expression() ?: return null
                    if (tokens.getOrNull(position) !is RightParen) return reject(EntryExpressionCode.Invalid)
                    position++
                    value
                }
                else -> reject(EntryExpressionCode.Invalid)
            }

        private fun isSymbol(symbol: Char): Boolean = tokens[position] is Operator && (tokens[position] as Operator).symbol == symbol

        private fun reject(code: EntryExpressionCode): Long? {
            failure = code
            return null
        }

        // Exact multiply/divide in scale-`precision` minor units. The gcd reduction keeps every
        // intermediate product inside the Long range before the checked multiply, so no silent
        // rounding can occur.

        private fun multiply(
            left: Long,
            right: Long,
        ): Long? {
            arithmeticFailure = null
            val magnitude = absChecked(left) ?: return rejectArithmetic(EntryExpressionCode.Overflow)
            val magnitudeRight = absChecked(right) ?: return rejectArithmetic(EntryExpressionCode.Overflow)
            val negative = (left < 0L) xor (right < 0L)
            // a * b / scale is an integer exactly when scale/gcd(a, scale) divides b.
            val common = gcd(magnitude, scale)
            val reducedLeft = magnitude / common
            val remainingScale = scale / common
            if (magnitudeRight % remainingScale != 0L) return rejectArithmetic(EntryExpressionCode.NonCurrencyPrecision)
            val unsigned = checkedMultiply(reducedLeft, magnitudeRight / remainingScale) ?: return rejectArithmetic(EntryExpressionCode.Overflow)
            return applySign(unsigned, negative) ?: rejectArithmetic(EntryExpressionCode.Overflow)
        }

        private fun divide(
            left: Long,
            right: Long,
        ): Long? {
            arithmeticFailure = null
            if (right == 0L) return rejectArithmetic(EntryExpressionCode.DivideByZero)
            val magnitude = absChecked(left) ?: return rejectArithmetic(EntryExpressionCode.Overflow)
            val magnitudeRight = absChecked(right) ?: return rejectArithmetic(EntryExpressionCode.Overflow)
            val negative = (left < 0L) xor (right < 0L)
            // a * scale / b is an integer exactly when b/gcd(a, b) divides scale (covers 1/3, 1/8).
            val common = gcd(magnitude, magnitudeRight)
            val reducedDivisor = magnitudeRight / common
            if (scale % reducedDivisor != 0L) return rejectArithmetic(EntryExpressionCode.NonCurrencyPrecision)
            val unsigned = checkedMultiply(magnitude / common, scale / reducedDivisor) ?: return rejectArithmetic(EntryExpressionCode.Overflow)
            return applySign(unsigned, negative) ?: rejectArithmetic(EntryExpressionCode.Overflow)
        }

        private fun rejectArithmetic(code: EntryExpressionCode): Long? {
            arithmeticFailure = code
            return null
        }
    }

    private companion object {
        /** Frozen E-3 limits (spec section 3.4 E-3 / R-2). */
        const val MAX_TOKEN_COUNT: Int = 64

        const val MAX_PAREN_DEPTH: Int = 8

        fun isAsciiWhitespace(character: Char): Boolean = character == ' ' || character == '\t' || character == '\r' || character == '\n'

        fun gcd(
            a: Long,
            b: Long,
        ): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val next = x % y
                x = y
                y = next
            }
            return x
        }

        fun absChecked(value: Long): Long? =
            when {
                value >= 0L -> value
                value == Long.MIN_VALUE -> null
                else -> -value
            }

        fun checkedAdd(
            a: Long,
            b: Long,
        ): Long? {
            val result = a + b
            return if (((a xor result) and (b xor result)) >= 0L) result else null
        }

        fun checkedSubtract(
            a: Long,
            b: Long,
        ): Long? {
            if (b == Long.MIN_VALUE) return null
            return checkedAdd(a, -b)
        }

        fun checkedMultiply(
            a: Long,
            b: Long,
        ): Long? {
            if (a == 0L || b == 0L) return 0L
            val result = a * b
            return if (result / a == b && result / b == a) result else null
        }

        fun applySign(
            magnitude: Long,
            negative: Boolean,
        ): Long? =
            if (negative) {
                if (magnitude == Long.MIN_VALUE) {
                    null
                } else {
                    -magnitude
                }
            } else {
                magnitude
            }
    }
}
