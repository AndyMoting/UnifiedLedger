package com.unifiedledger.ui

import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class P503DraftValidationTest {
    private val validation = P503DraftValidation(ParseManualExpenseAmount())
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    @Test
    fun occurredAtTextMustReconcileToTheDraftInstant() {
        // Displayed text matches the draft instant: Continue may proceed.
        assertTrue(validation.occurredAtTextReconciles(occurredAt.toString(), occurredAt))
        // Blank text reconciles to a missing occurred-at (the reducer reports the field error).
        assertTrue(validation.occurredAtTextReconciles("", null))
        // Garbage text with a stale valid draft must NOT reconcile (finding P503IMPL-Q-001).
        assertFalse(validation.occurredAtTextReconciles("not-a-time", occurredAt))
        // A different valid instant must NOT reconcile.
        assertFalse(validation.occurredAtTextReconciles("2026-01-15T00:31:00Z", occurredAt))
        // Garbage text with a missing draft must NOT reconcile.
        assertFalse(validation.occurredAtTextReconciles("not-a-time", null))
    }

    @Test
    fun isValidUsesTheSuppliedParseCurrency() {
        val draft =
            ManualExpenseDraft(
                paymentAccountId = AccountId("asset-payment-local"),
                categoryId = CategoryId("expense-category-breakfast"),
                amountText = "35.80",
                occurredAt = occurredAt,
            )

        // Valid under the selected account's CNY precision 2.
        assertTrue(validation.isValid(draft, CurrencyUnit("CNY", 2)))
        // "35.80" is an invalid amount under a zero-precision currency.
        assertFalse(validation.isValid(draft, CurrencyUnit("JPY", 0)))
    }

    // D-131 R1: lenient amounts are valid under their currency precision and lenient
    // rejections still carry the amount-format error (spec 2.6).
    @Test
    fun lenientAmountsAreValidUnderCnyAndJpy() {
        val cny = CurrencyUnit("CNY", 2)
        val jpy = CurrencyUnit("JPY", 0)

        assertTrue(validation.isValid(amountDraft("35.8"), cny))
        assertTrue(validation.isValid(amountDraft("11"), cny))
        assertTrue(validation.isValid(amountDraft("358.0"), jpy))
        assertTrue(validation.isValid(amountDraft("35.00"), jpy))
    }

    @Test
    fun lenientRejectionsStillCarryTheAmountFormatError() {
        val cny = CurrencyUnit("CNY", 2)
        assertTrue(validation.errors(amountDraft("35.812"), cny).amountFormatError != null)
        assertTrue(validation.errors(amountDraft("011"), cny).amountFormatError != null)
    }

    private fun amountDraft(amountText: String): ManualExpenseDraft =
        ManualExpenseDraft(
            paymentAccountId = AccountId("asset-payment-local"),
            categoryId = CategoryId("expense-category-breakfast"),
            amountText = amountText,
            occurredAt = occurredAt,
        )
}
