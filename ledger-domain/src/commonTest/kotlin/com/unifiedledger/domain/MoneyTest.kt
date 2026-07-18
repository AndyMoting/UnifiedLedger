package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun representsExactCurrencyValuesInMinorUnits() {
        val cny = CurrencyUnit("CNY", 2)
        val amount = Money.ofMinor(3_580, cny)

        assertEquals(3_580L, amount.minorUnits)
        assertEquals(cny, amount.currency)
        assertEquals(Money.ofMinor(3_580, cny), amount)
    }

    @Test
    fun typedIdsHaveStableValueEquality() {
        assertEquals(AccountId("asset-bank-a"), AccountId("asset-bank-a"))
        assertEquals(CategoryId("expense-category-breakfast"), CategoryId("expense-category-breakfast"))
    }
}
