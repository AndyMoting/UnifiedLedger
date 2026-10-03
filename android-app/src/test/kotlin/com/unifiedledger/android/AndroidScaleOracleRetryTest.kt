package com.unifiedledger.android

import android.database.sqlite.SQLiteDatabaseLockedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidScaleOracleRetryTest {
    @Test
    fun backoffGrowsExponentiallyThenCapsAtFiveSeconds() {
        assertEquals(1000L, AndroidScaleOracleRetry.lockRetryBackoffMs(1))
        assertEquals(2000L, AndroidScaleOracleRetry.lockRetryBackoffMs(2))
        assertEquals(4000L, AndroidScaleOracleRetry.lockRetryBackoffMs(3))
        assertEquals(5000L, AndroidScaleOracleRetry.lockRetryBackoffMs(4))
        assertEquals(5000L, AndroidScaleOracleRetry.lockRetryBackoffMs(5))
    }

    @Test
    fun backoffCapHoldsForEveryRemainingRetry() {
        (6 until AndroidScaleOracleRetry.LOCK_RETRY_MAX_ATTEMPTS).forEach { attempt ->
            assertEquals(5000L, AndroidScaleOracleRetry.lockRetryBackoffMs(attempt))
        }
        assertEquals(5000L, AndroidScaleOracleRetry.lockRetryBackoffMs(1000))
    }

    @Test
    fun theExactLockExceptionClassIsRetryableByClassLiteral() {
        // SQLiteDatabaseLockedException cannot be constructed on the JVM
        // (stub android.jar methods throw "Stub!"), so the true branch is
        // pinned against the exact class literal via the type-level
        // classifier; the subclass rule is pinned with a declared-but-never-
        // instantiated subclass.
        assertTrue(AndroidScaleOracleRetry.isLockRetryableType(SQLiteDatabaseLockedException::class.java))
        assertTrue(AndroidScaleOracleRetry.isLockRetryableType(LockExceptionSubclass::class.java))
    }

    @Test
    fun nonLockFailuresAreNeverRetryable() {
        // The throwable-level classifier delegates to the type-level one, so
        // these constructible stand-ins also pin the wrapper on JVM.
        assertFalse(AndroidScaleOracleRetry.isLockRetryable(IllegalStateException("pointer missing")))
        assertFalse(AndroidScaleOracleRetry.isLockRetryable(AssertionError("generation changed")))
        assertFalse(AndroidScaleOracleRetry.isLockRetryable(RuntimeException("database corruption")))
        assertFalse(AndroidScaleOracleRetry.isLockRetryable(null))
        assertFalse(AndroidScaleOracleRetry.isLockRetryableType(IllegalStateException::class.java))
        assertFalse(AndroidScaleOracleRetry.isLockRetryableType(AssertionError::class.java))
    }

    @Test
    fun theAttemptBoundStaysPinned() {
        // Initial attempt + 11 retries, bounded independently of deadlines.
        assertEquals(12, AndroidScaleOracleRetry.LOCK_RETRY_MAX_ATTEMPTS)
    }

    @Test
    fun quiescenceRequiresEmptyJournalAndConsecutiveThreshold() {
        // D-211: PROCEED needs both an empty/absent journal and the
        // consecutive-empty threshold; fewer consecutive empty polls wait.
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.WAIT,
            AndroidScaleOracleRetry.journalWaitDecision(0L, 0, 0L),
        )
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.WAIT,
            AndroidScaleOracleRetry.journalWaitDecision(0L, 1, 0L),
        )
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.PROCEED,
            AndroidScaleOracleRetry.journalWaitDecision(0L, AndroidScaleOracleRetry.JOURNAL_QUIESCE_CONSECUTIVE, 0L),
        )
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.PROCEED,
            AndroidScaleOracleRetry.journalWaitDecision(0L, AndroidScaleOracleRetry.JOURNAL_QUIESCE_CONSECUTIVE + 3, 0L),
        )
    }

    @Test
    fun nonEmptyJournalAlwaysWaitsWhileBudgetRemains() {
        // D-211: a non-empty journal waits regardless of the consecutive count.
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.WAIT,
            AndroidScaleOracleRetry.journalWaitDecision(512L, 0, 0L),
        )
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.WAIT,
            AndroidScaleOracleRetry.journalWaitDecision(1L, AndroidScaleOracleRetry.JOURNAL_QUIESCE_CONSECUTIVE + 3, 0L),
        )
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.WAIT,
            AndroidScaleOracleRetry.journalWaitDecision(512L, 0, AndroidScaleOracleRetry.JOURNAL_WAIT_BUDGET_MS - 1),
        )
    }

    @Test
    fun exhaustedBudgetGivesUpEvenWhenJournalNonEmpty() {
        // D-211: give-up proceeds with the attempt rather than failing; a
        // bounded observer that cannot confirm quiescence still tries.
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.GIVE_UP,
            AndroidScaleOracleRetry.journalWaitDecision(512L, 0, AndroidScaleOracleRetry.JOURNAL_WAIT_BUDGET_MS),
        )
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.GIVE_UP,
            AndroidScaleOracleRetry.journalWaitDecision(1L, AndroidScaleOracleRetry.JOURNAL_QUIESCE_CONSECUTIVE + 3, AndroidScaleOracleRetry.JOURNAL_WAIT_BUDGET_MS),
        )
    }

    @Test
    fun theJournalWaitBudgetBoundaryIsInclusiveGiveUpNotWait() {
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.GIVE_UP,
            AndroidScaleOracleRetry.journalWaitDecision(512L, 1, AndroidScaleOracleRetry.JOURNAL_WAIT_BUDGET_MS),
        )
        assertEquals(
            AndroidScaleOracleRetry.JournalWait.WAIT,
            AndroidScaleOracleRetry.journalWaitDecision(512L, 1, AndroidScaleOracleRetry.JOURNAL_WAIT_BUDGET_MS - 1),
        )
    }

    @Test
    fun theJournalWaitConstantsStayPinned() {
        assertEquals(1000L, AndroidScaleOracleRetry.JOURNAL_WAIT_POLL_MS)
        assertEquals(300_000L, AndroidScaleOracleRetry.JOURNAL_WAIT_BUDGET_MS)
        assertEquals(2, AndroidScaleOracleRetry.JOURNAL_QUIESCE_CONSECUTIVE)
    }

    @Test
    fun theBusyTimeoutConstantStaysPinned() {
        // D-212: kernel-level busy wait per lock wait, layered over the D-209
        // attempts and the D-211 quiescence gate.
        assertEquals(60_000L, AndroidScaleOracleRetry.ORACLE_BUSY_TIMEOUT_MS)
    }

    @Test
    fun theBusyTimeoutPragmaIsExact() {
        // D-212: the pragma the oracle executes once per attempt right after
        // the read-only connection opens and before its transaction begins.
        assertEquals("PRAGMA busy_timeout=60000", AndroidScaleOracleRetry.busyTimeoutPragma())
    }
}

/** Declared only for the subclass rule; never constructed on the JVM. */
private class LockExceptionSubclass : SQLiteDatabaseLockedException("never constructed")
