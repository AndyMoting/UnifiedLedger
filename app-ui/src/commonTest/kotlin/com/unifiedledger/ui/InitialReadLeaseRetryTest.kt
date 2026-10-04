package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-215: the bounded retry policy for the initial current-state read's transient lease refusal.
 * The retryable token is EXACTLY `leaseNotReady`; the D-214 `readThrew`/`readResult` causes are
 * never retried. The policy is pure, so the JVM tests pin the constants and every classifier
 * boundary against the real D-214 token strings.
 */
class InitialReadLeaseRetryTest {
    @Test
    fun theAttemptBoundAndDelayStayPinned() {
        // Initial attempt + three retries; worst case adds 300ms to the initial load.
        assertEquals(4, InitialReadLeaseRetry.MAX_ATTEMPTS)
        assertEquals(100L, InitialReadLeaseRetry.RETRY_DELAY_MS)
    }

    @Test
    fun exactlyTheLeaseNotReadyTokenIsRetryable() {
        assertTrue(InitialReadLeaseRetry.shouldRetryCause("leaseNotReady"))
        assertEquals("leaseNotReady", InitialReadLeaseRetry.LEASE_NOT_READY_CAUSE)
    }

    @Test
    fun everyD214NonTransientCauseIsNeverRetryable() {
        // D-214 branch 2/3 shapes: a real read failure or a non-Success read result is fatal
        // and must surface immediately.
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("readThrew errorType=SQLiteException"))
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("readThrew errorType=IllegalStateException"))
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("readResult variant=Unavailable"))
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("readResult variant=InvalidState"))
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("readResult variant=null"))
    }

    @Test
    fun unknownAndEmptyCausesAreNeverRetryable() {
        assertFalse(InitialReadLeaseRetry.shouldRetryCause(""))
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("unknown"))
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("leaseNotReady "))
        assertFalse(InitialReadLeaseRetry.shouldRetryCause("stale"))
    }

    @Test
    fun retryStopsAtTheAttemptBoundForTheTransientCause() {
        // attemptsMade counts attempts already performed: the first attempt (1) may still
        // retry; the fourth (== MAX_ATTEMPTS) is the last and must not.
        assertTrue(InitialReadLeaseRetry.shouldRetry(1, "leaseNotReady"))
        assertTrue(InitialReadLeaseRetry.shouldRetry(2, "leaseNotReady"))
        assertTrue(InitialReadLeaseRetry.shouldRetry(3, "leaseNotReady"))
        assertFalse(InitialReadLeaseRetry.shouldRetry(4, "leaseNotReady"))
        assertFalse(InitialReadLeaseRetry.shouldRetry(5, "leaseNotReady"))
    }

    @Test
    fun aNonTransientCauseIsNeverRetriedEvenOnTheFirstAttempt() {
        assertFalse(InitialReadLeaseRetry.shouldRetry(1, "readThrew errorType=SQLiteException"))
        assertFalse(InitialReadLeaseRetry.shouldRetry(1, "readResult variant=Unavailable"))
    }
}
