package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D-131 R1: tests for the lenient exact-decimal parser (spec 2.1/2.4). The grammar is
 * `-?(0|[1-9][0-9]*)(\.[0-9]+)?`; fraction digits short of [precision] are zero-padded and
 * digits beyond [precision] are accepted only when every excess digit is `0` (exact
 * division, never rounding).
 */
class ExactDecimalLenientTest {
    @Test
    fun padsFractionDigitsShortOfPrecisionWithZeros() {
        assertEquals(3580L, parseExactDecimalLenient("35.8", 2))
        assertEquals(1100L, parseExactDecimalLenient("11", 2))
        assertEquals(1100L, parseExactDecimalLenient("11.0", 2))
        assertEquals(0L, parseExactDecimalLenient("0", 2))
        assertEquals(-1100L, parseExactDecimalLenient("-11", 2))
    }

    @Test
    fun acceptsExcessFractionDigitsOnlyWhenEveryExcessDigitIsZero() {
        assertEquals(3580L, parseExactDecimalLenient("35.800", 2))
        assertEquals(3581L, parseExactDecimalLenient("35.810", 2))
        assertNull(parseExactDecimalLenient("35.812", 2))
    }

    @Test
    fun zeroPrecisionAcceptsOnlyExactDivisibleFractions() {
        assertEquals(358L, parseExactDecimalLenient("358", 0))
        assertEquals(358L, parseExactDecimalLenient("358.0", 0))
        assertEquals(35L, parseExactDecimalLenient("35.00", 0))
        assertNull(parseExactDecimalLenient("35.01", 0))
    }

    @Test
    fun rejectsTextOutsideTheFrozenGrammar() {
        assertNull(parseExactDecimalLenient("", 2))
        assertNull(parseExactDecimalLenient(".5", 2))
        assertNull(parseExactDecimalLenient("35.", 2))
        assertNull(parseExactDecimalLenient("+35.80", 2))
        assertNull(parseExactDecimalLenient("035.80", 2))
        assertNull(parseExactDecimalLenient("011", 2))
        assertNull(parseExactDecimalLenient("35,80", 2))
        assertNull(parseExactDecimalLenient("3.58e1", 2))
        assertNull(parseExactDecimalLenient("35 .80", 2))
    }

    @Test
    fun rejectsWholeValuesThatOverflowMinorUnits() {
        assertNull(parseExactDecimalLenient("92233720368547758.08", 2))
        assertNull(parseExactDecimalLenient("92233720368547758", 2))
        // The largest whole value at precision 2 still fits and must be accepted.
        assertEquals(9223372036854775700L, parseExactDecimalLenient("92233720368547757", 2))
    }

    @Test
    fun precisionMustStayWithinZeroToEighteen() {
        assertNull(parseExactDecimalLenient("1", -1))
        assertNull(parseExactDecimalLenient("1", 19))
        assertEquals(1L, parseExactDecimalLenient("1", 0))
        // Precision 18 zero-pads the whole part: "1" = 10^18 minor units.
        assertEquals(1000000000000000000L, parseExactDecimalLenient("1", 18))
        assertEquals(1L, parseExactDecimalLenient("0.000000000000000001", 18))
    }

    @Test
    fun negativeZeroIsZero() {
        assertEquals(0L, parseExactDecimalLenient("-0", 2))
        assertEquals(0L, parseExactDecimalLenient("-0.00", 2))
    }

    @Test
    fun strictAcceptanceIsASubsetOfLenientWithIdenticalResults() {
        for (precision in listOf(0, 2, 18)) {
            for (text in listOf("35.80", "0.00", "-0.01", "358", "1", "0")) {
                val strict = parseExactDecimal(text, precision)
                if (strict != null) {
                    assertEquals(strict, parseExactDecimalLenient(text, precision), "for $text precision=$precision")
                }
            }
        }
    }
}
