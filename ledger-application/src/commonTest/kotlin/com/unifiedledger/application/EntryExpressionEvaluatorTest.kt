package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P7-02.E E-3/B06 frozen expression vectors: exact decimal evaluation in Long minor units,
 * binary floating point and silent rounding are forbidden, and every rejection is typed per
 * the section 5.3 table (`EntryExpressionInvalid`/`DivideByZero`/`Overflow`/
 * `NonCurrencyPrecision`/`NegativeResult`).
 */
class EntryExpressionEvaluatorTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val evaluator = EntryExpressionEvaluator()

    private fun valid(
        expression: String,
        minorUnits: Long,
        currency: CurrencyUnit = cny,
    ) {
        assertEquals(EntryExpressionEvaluator.Result.Valid(minorUnits), evaluator.evaluate(expression, currency), expression)
    }

    private fun invalid(
        expression: String,
        code: EntryExpressionCode,
        currency: CurrencyUnit = cny,
    ) {
        assertEquals(EntryExpressionEvaluator.Result.Invalid(code), evaluator.evaluate(expression, currency), expression)
    }

    @Test
    fun b06AdditionVectorYieldsExactMinorUnits() {
        // 12.5+8 -> 20.50
        valid("12.5+8", 2050L)
    }

    @Test
    fun b06ParenthesizedMultiplicationHonorsPrecedence() {
        // 2×(3+4) -> 14; the parenthesized sum is evaluated first.
        valid("2×(3+4)", 1400L)
        valid("2*(3+4)", 1400L)
        // ×÷ bind tighter than +-: 1+2×3 = 7, not 9.
        valid("1+2×3", 700L)
        valid("2×3+1", 700L)
    }

    @Test
    fun b06ExactDivisionRepresentableAtTargetPrecisionIsAccepted() {
        // 10/4 = 2.50: exact, representable at precision 2 (trailing zero).
        valid("10/4", 250L)
        valid("10÷4", 250L)
        valid("1/2", 50L)
        valid("0/5", 0L)
        valid("2/0.5", 400L)
    }

    @Test
    fun b06NonTerminatingDivisionIsRejected() {
        // 1/3 never terminates exactly.
        invalid("1/3", EntryExpressionCode.NonCurrencyPrecision)
        invalid("1÷3", EntryExpressionCode.NonCurrencyPrecision)
        invalid("10/3", EntryExpressionCode.NonCurrencyPrecision)
    }

    @Test
    fun b06TerminatingDivisionBeyondCurrencyPrecisionIsRejected() {
        // 1/8 = 0.125 terminates but needs a third fraction digit with a nonzero last digit.
        invalid("1/8", EntryExpressionCode.NonCurrencyPrecision)
        invalid("1.25*1.1", EntryExpressionCode.NonCurrencyPrecision)
    }

    @Test
    fun b06NegativeResultIsRejected() {
        // 1-2 -> -1.
        invalid("1-2", EntryExpressionCode.NegativeResult)
        invalid("2-3×1", EntryExpressionCode.NegativeResult)
        // Negative intermediates that cancel out stay acceptable; only the final result decides.
        valid("1-2+2", 100L)
        valid("(1-2)*(1-2)", 100L)
        invalid("(1-2)/2", EntryExpressionCode.NegativeResult)
    }

    @Test
    fun divisionByZeroIsTypedRejected() {
        invalid("5/0", EntryExpressionCode.DivideByZero)
        invalid("5÷0.00", EntryExpressionCode.DivideByZero)
        invalid("1/(2-2)", EntryExpressionCode.DivideByZero)
    }

    @Test
    fun overflowIsTypedRejected() {
        // 1e10 * 1e10 yuan = 1e20 minor units, beyond the Long minor-unit range.
        invalid("10000000000*10000000000", EntryExpressionCode.Overflow)
        // Exact division whose scaled numerator leaves the Long range.
        invalid("92233720368547757.99/0.01", EntryExpressionCode.Overflow)
        // Addition overflow: the largest representable literal doubled.
        invalid("92233720368547757.99+92233720368547757.99", EntryExpressionCode.Overflow)
        // Subtraction overflow through the negative range.
        invalid("0-92233720368547757.99-92233720368547757.99", EntryExpressionCode.Overflow)
        // A literal that cannot be carried in Long minor units at precision 2.
        invalid("99999999999999999.99", EntryExpressionCode.Overflow)
    }

    @Test
    fun unaryMinusAndNegativeLiteralsAreLexicallyRejected() {
        invalid("-1", EntryExpressionCode.Invalid)
        invalid("2*-3", EntryExpressionCode.Invalid)
        invalid("(-1+2)", EntryExpressionCode.Invalid)
        invalid("1-(-2)", EntryExpressionCode.Invalid)
    }

    @Test
    fun malformedExpressionsAreTypedRejected() {
        invalid("", EntryExpressionCode.Invalid)
        invalid("   ", EntryExpressionCode.Invalid)
        invalid("12.5 + 8", EntryExpressionCode.Invalid)
        invalid("1+", EntryExpressionCode.Invalid)
        invalid("*3", EntryExpressionCode.Invalid)
        invalid("(1+2", EntryExpressionCode.Invalid)
        invalid("1+2)", EntryExpressionCode.Invalid)
        invalid("()", EntryExpressionCode.Invalid)
        invalid(".", EntryExpressionCode.Invalid)
        invalid("1.", EntryExpressionCode.Invalid)
        invalid(".5", EntryExpressionCode.Invalid)
        invalid("007", EntryExpressionCode.Invalid)
        invalid("1..5", EntryExpressionCode.Invalid)
        invalid("1a+2", EntryExpressionCode.Invalid)
        invalid("1,5+2", EntryExpressionCode.Invalid)
        invalid("1 2", EntryExpressionCode.Invalid)
    }

    @Test
    fun frozenTokenAndParenDepthLimitsAreEnforced() {
        // 32 literals joined by 31 pluses = 63 tokens: within the frozen 64-token budget.
        valid((1..32).joinToString("+") { "1" }, 3200L)
        // 33 literals joined by 32 pluses = 65 tokens: over the budget.
        invalid((1..33).joinToString("+") { "1" }, EntryExpressionCode.Invalid)
        // Depth 8 is exactly at the frozen limit; depth 9 is rejected.
        valid("((((((((1))))))))", 100L)
        valid("((((((((1+1))))))))", 200L)
        invalid("(((((((((1)))))))))", EntryExpressionCode.Invalid)
    }

    @Test
    fun literalsAllowZeroPaddedTrailingFractionDigitsOnly() {
        valid("1.5", 150L)
        valid("1.50", 150L)
        valid("1.500", 150L)
        // 1.005 needs a third fraction digit with a nonzero last digit.
        invalid("1.005", EntryExpressionCode.NonCurrencyPrecision)
        valid("0.01", 1L)
        valid("0", 0L)
    }

    @Test
    fun currencyPrecisionDrivesRepresentation() {
        val jpy = CurrencyUnit("JPY", 0)
        valid("10÷2", 5L, jpy)
        // 2.5 is not representable at precision 0.
        invalid("10/4", EntryExpressionCode.NonCurrencyPrecision, jpy)
    }

    @Test
    fun fullWidthDigitsAreRejectedAsIllegalTokens() {
        // P702IMPL-03: the frozen grammar is ASCII decimal only; Unicode Nd digits are not
        // decimal literals and must not slip into the magnitude parse.
        invalid("１２+１", EntryExpressionCode.Invalid)
        invalid("12.５", EntryExpressionCode.Invalid)
    }

    @Test
    fun evaluationIsPureAndRepeatable() {
        val first = evaluator.evaluate("12.5+8", cny)
        val second = evaluator.evaluate("12.5+8", cny)
        assertEquals(first, second)
    }
}
