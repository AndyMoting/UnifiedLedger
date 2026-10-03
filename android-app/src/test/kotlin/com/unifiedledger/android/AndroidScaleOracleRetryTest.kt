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
}

/** Declared only for the subclass rule; never constructed on the JVM. */
private class LockExceptionSubclass : SQLiteDatabaseLockedException("never constructed")
