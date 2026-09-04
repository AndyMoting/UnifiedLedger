package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * D-131 R1: the wrapper keeps strict-first semantics (spec 2.3); `"35.8"`/`"35.800"` moved
 * from the D-119 fixed-reject vectors to the valid vectors via the lenient fallback (spec
 * 2.4/7 narrow replacement), everything else unchanged.
 */
class ParseManualExpenseAmountTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val parser = ParseManualExpenseAmount()

    @Test
    fun fixedValidVectorsParseToExactMinorUnits() {
        val vectors =
            mapOf(
                "35.80" to 3580L,
                " 35.80\t" to 3580L,
                "0.00" to 0L,
                "-0.01" to -1L,
                "35.8" to 3580L,
                "35.800" to 3580L,
                "11" to 1100L,
                "11.0" to 1100L,
                "0" to 0L,
                "35.810" to 3581L,
                "-11" to -1100L,
                " 35.8\t" to 3580L,
            )
        for ((text, expectedMinorUnits) in vectors) {
            val result = assertIs<ParseManualExpenseAmount.Result.Valid>(parser.parse(text, cny))
            assertEquals(expectedMinorUnits, result.minorUnits, "for input $text")
        }
    }

    @Test
    fun fixedRejectVectorsReturnTheStableFormatError() {
        val vectors =
            listOf(
                "" to ManualExpenseAmountFormatError.EMPTY,
                " " to ManualExpenseAmountFormatError.EMPTY,
                "+35.80" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "035.80" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "35,80" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "3.58e1" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "35 .80" to ManualExpenseAmountFormatError.INTERNAL_WHITESPACE,
                "92233720368547758.08" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "35.812" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                ".5" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "011" to ManualExpenseAmountFormatError.INVALID_FORMAT,
            )
        for ((text, expectedError) in vectors) {
            val result = assertIs<ParseManualExpenseAmount.Result.Invalid>(parser.parse(text, cny))
            assertEquals(expectedError, result.error, "for input $text")
        }
    }

    @Test
    fun precisionAlwaysComesFromTheSelectedAccountCurrency() {
        val zeroPrecision = CurrencyUnit("JPY", 0)

        val validVectors =
            mapOf(
                "358" to 358L,
                "358.0" to 358L,
                "35.00" to 35L,
            )
        for ((text, expectedMinorUnits) in validVectors) {
            val valid = assertIs<ParseManualExpenseAmount.Result.Valid>(parser.parse(text, zeroPrecision))
            assertEquals(expectedMinorUnits, valid.minorUnits, "for input $text")
        }

        val invalidVectors =
            listOf(
                "35.80",
                "35.01",
            )
        for (text in invalidVectors) {
            val invalid =
                assertIs<ParseManualExpenseAmount.Result.Invalid>(
                    parser.parse(text, zeroPrecision),
                )
            assertEquals(ManualExpenseAmountFormatError.INVALID_FORMAT, invalid.error, "for input $text")
        }
    }
}
