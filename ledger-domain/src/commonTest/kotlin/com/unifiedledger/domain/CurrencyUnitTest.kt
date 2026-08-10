package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CurrencyUnitTest {
    @Test
    fun acceptsValidCurrencies() {
        val cny = CurrencyUnit("CNY", 2)
        assertEquals("CNY", cny.code)
        assertEquals(2, cny.precision)
        assertEquals(CurrencyUnit("USD", 2), CurrencyUnit("USD", 2))
    }

    @Test
    fun acceptsPrecisionBoundaries() {
        assertEquals(0, CurrencyUnit("CNY", 0).precision)
        assertEquals(18, CurrencyUnit("CNY", 18).precision)
    }

    @Test
    fun rejectsBlankCode() {
        assertFailsWith<IllegalArgumentException> { CurrencyUnit("", 2) }
        assertFailsWith<IllegalArgumentException> { CurrencyUnit("   ", 2) }
    }

    @Test
    fun rejectsNegativePrecision() {
        assertFailsWith<IllegalArgumentException> { CurrencyUnit("CNY", -1) }
    }

    @Test
    fun rejectsPrecisionAboveMaximum() {
        assertFailsWith<IllegalArgumentException> { CurrencyUnit("CNY", 19) }
    }

    @Test
    fun moneyOfMinorValidatesThroughCurrencyUnit() {
        val money = Money.ofMinor(100, CurrencyUnit("CNY", 2))
        assertEquals(100L, money.minorUnits)
        assertEquals(CurrencyUnit("CNY", 2), money.currency)
        assertFailsWith<IllegalArgumentException> { Money.ofMinor(100, CurrencyUnit("", 2)) }
        assertFailsWith<IllegalArgumentException> { Money.ofMinor(100, CurrencyUnit("CNY", 19)) }
    }
}
