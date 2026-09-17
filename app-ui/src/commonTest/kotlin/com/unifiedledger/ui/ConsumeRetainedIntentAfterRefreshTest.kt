package com.unifiedledger.ui

import com.unifiedledger.application.EntryType
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A-PERF (APQUAL-02, spec section 2.3 rework 2): the retained-intent consumption decision of the
 * background refresh's main-dispatcher hop. The refresh must consume ONLY the instance it
 * captured at its admission point — an intent submitted while the read was in flight must
 * survive for its own refresh (the review's reproduce scenario: capture I1 -> in-flight window
 * -> submit writes I2 -> the hop landing with an unconditional clear would destroy I2).
 */
class ConsumeRetainedIntentAfterRefreshTest {
    private fun intent(
        paymentId: String,
        categoryId: String,
    ) = RetainedEntryIntent(
        type = EntryType.EXPENSE,
        amountText = "35.80",
        paymentAccountId = AccountId(paymentId),
        categoryId = CategoryId(categoryId),
        note = "",
        occurredAt = null,
        originTab = P503Tab.HOME,
    )

    @Test
    fun theCapturedInstanceIsConsumedWhenItIsStillTheCurrentOne() {
        val captured = intent("asset-payment-a", "expense-category-a")
        // The happy path: nothing replaced the intent while the read ran.
        assertNull(consumeRetainedIntentAfterRefresh(current = captured, captured = captured))
    }

    @Test
    fun anIntentRewrittenDuringTheInFlightWindowSurvives() {
        val captured = intent("asset-payment-a", "expense-category-a")
        val rewritten = intent("asset-payment-b", "expense-category-b")
        // The reproduce scenario of the finding: the hop must NOT clear the newer intent —
        // I2 survives for its own refresh.
        val after = consumeRetainedIntentAfterRefresh(current = rewritten, captured = captured)
        assertSame(rewritten, after)
    }

    @Test
    fun bothNullStaysNull() {
        assertNull(consumeRetainedIntentAfterRefresh(current = null, captured = null))
    }

    @Test
    fun anEqualValuedButDistinctInstanceIsNotConsumed() {
        // Identity, not equality: a fresh equal-valued instance written during the window must
        // survive (the retained-intent lifecycle is instance-matched throughout).
        val captured = intent("asset-payment-a", "expense-category-a")
        val distinctEqual = intent("asset-payment-a", "expense-category-a")
        val after = consumeRetainedIntentAfterRefresh(current = distinctEqual, captured = captured)
        assertSame(distinctEqual, after)
    }
}
