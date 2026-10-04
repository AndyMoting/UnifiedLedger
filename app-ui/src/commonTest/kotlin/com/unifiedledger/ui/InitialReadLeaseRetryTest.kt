package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-215: the bounded retry policy for the initial current-state read's lease refusal. The retry
 * covers a bare `leaseNotReady` (the only observable `LeaseOutcome.NotReady` shape) but the
 * policy is bounded to [InitialReadLeaseRetry.MAX_ATTEMPTS]; the D-214 `readThrew`/`readResult`
 * causes are never retried. The policy is pure, so the JVM tests pin the constants, every
 * classifier boundary, and that the production loop shape terminates.
 */
class InitialReadLeaseRetryTest {
    @Test
    fun theAttemptBoundAndDelayStayPinned() {
        // Initial attempt + three retries; worst case adds 300ms to the initial load.
        assertEquals(4, InitialReadLeaseRetry.MAX_ATTEMPTS)
        assertEquals(100L, InitialReadLeaseRetry.RETRY_DELAY_MS)
    }

    @Test
    fun exactlyTheLeaseNotReadyTokenIsRetryableAtTheSharedConstant() {
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
    fun theAttemptBoundaryIsExclusiveAtMaxAttempts() {
        // attemptsMade counts attempts already performed: the first three may still retry, the
        // fourth (== MAX_ATTEMPTS) is the last and must not.
        assertTrue(InitialReadLeaseRetry.attemptsRemaining(1))
        assertTrue(InitialReadLeaseRetry.attemptsRemaining(2))
        assertTrue(InitialReadLeaseRetry.attemptsRemaining(3))
        assertFalse(InitialReadLeaseRetry.attemptsRemaining(4))
        assertFalse(InitialReadLeaseRetry.attemptsRemaining(5))
    }

    @Test
    fun retryStopsAtTheAttemptBoundForTheTransientCause() {
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

    @Test
    fun theProductionLoopShapeTerminatesAtTheAttemptBound() {
        // Mirrors the P503App initial-load loop: while the outcome is NotReady AND the policy
        // admits another attempt, retry. `refusals` is far larger than the bound, so the POLICY
        // (not the synthetic input) must stop the loop at MAX_ATTEMPTS total attempts.
        var attemptsMade = 1
        var refusals = Int.MAX_VALUE
        while (refusals > 0 && InitialReadLeaseRetry.shouldRetry(attemptsMade, InitialReadLeaseRetry.LEASE_NOT_READY_CAUSE)) {
            attemptsMade += 1
            refusals -= 1
        }
        assertEquals(InitialReadLeaseRetry.MAX_ATTEMPTS, attemptsMade)
    }

    @Test
    fun theProductionLoopShapeExitsImmediatelyOnASuccessOutcome() {
        // A first-attempt success is not NotReady (`refusals == 0`), so the loop never runs and
        // the landing hop sees a Completed outcome on attempt 1.
        var attemptsMade = 1
        var refusals = 0
        while (refusals > 0 && InitialReadLeaseRetry.shouldRetry(attemptsMade, InitialReadLeaseRetry.LEASE_NOT_READY_CAUSE)) {
            attemptsMade += 1
            refusals -= 1
        }
        assertEquals(1, attemptsMade)
    }
}
