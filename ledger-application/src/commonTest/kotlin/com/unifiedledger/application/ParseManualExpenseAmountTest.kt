package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
                "35.8" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "35.800" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "35,80" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "3.58e1" to ManualExpenseAmountFormatError.INVALID_FORMAT,
                "35 .80" to ManualExpenseAmountFormatError.INTERNAL_WHITESPACE,
                "92233720368547758.08" to ManualExpenseAmountFormatError.INVALID_FORMAT,
            )
        for ((text, expectedError) in vectors) {
            val result = assertIs<ParseManualExpenseAmount.Result.Invalid>(parser.parse(text, cny))
            assertEquals(expectedError, result.error, "for input $text")
        }
    }

    @Test
    fun precisionAlwaysComesFromTheSelectedAccountCurrency() {
        val zeroPrecision = CurrencyUnit("JPY", 0)

        val valid = assertIs<ParseManualExpenseAmount.Result.Valid>(parser.parse("3580", zeroPrecision))
        assertEquals(3580L, valid.minorUnits)

        val invalid =
            assertIs<ParseManualExpenseAmount.Result.Invalid>(
                parser.parse("35.80", zeroPrecision),
            )
        assertEquals(ManualExpenseAmountFormatError.INVALID_FORMAT, invalid.error)
    }
}
