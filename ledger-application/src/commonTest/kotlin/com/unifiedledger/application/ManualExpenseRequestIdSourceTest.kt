package com.unifiedledger.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val UUID_V7 =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

class ManualExpenseRequestIdSourceTest {
    @Test
    fun requestIdSourceMintsExactlyOneCanonicalUuidV7PerNext() {
        val source = UuidV7ManualExpenseRequestIdSource(UuidV7Generator({ FIXED_BYTES }, { FIXED_MILLIS }))

        val first = source.next()
        val second = source.next()

        assertTrue(UUID_V7.matches(first.value), "requestId must be canonical UUIDv7: ${first.value}")
        assertTrue(UUID_V7.matches(second.value), "requestId must be canonical UUIDv7: ${second.value}")
        assertEquals(first, second, "independent generator is deterministic per call")
    }

    @Test
    fun requestIdSourceIsIndependentFromTheSixIdCommitSource() {
        val requestSource = UuidV7ManualExpenseRequestIdSource(UuidV7Generator({ FIXED_BYTES }, { FIXED_MILLIS }))
        val sixIdSource = UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator({ FIXED_BYTES }, { FIXED_MILLIS }))

        // Consuming the six-id source must not advance or change the request source.
        val commitIds = sixIdSource.next()
        val requestId = requestSource.next()

        assertTrue(UUID_V7.matches(requestId.value))
        assertTrue(UUID_V7.matches(commitIds.confirmationId.value))
        assertTrue(UUID_V7.matches(commitIds.expenseIds.transactionId.value))
        assertTrue(UUID_V7.matches(commitIds.expenseIds.versionId.value))
        assertTrue(UUID_V7.matches(commitIds.expenseIds.postingSetId.value))
        assertTrue(UUID_V7.matches(commitIds.expenseIds.expensePostingId.value))
        assertTrue(UUID_V7.matches(commitIds.expenseIds.paymentPostingId.value))
    }

    private companion object {
        val FIXED_BYTES = ByteArray(10) { 1 }
        const val FIXED_MILLIS = 0L
    }
}
