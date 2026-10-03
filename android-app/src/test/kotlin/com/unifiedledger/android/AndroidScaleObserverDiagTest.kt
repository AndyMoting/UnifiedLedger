package com.unifiedledger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidScaleObserverDiagTest {
    @Test
    fun resetIsDueOnlyAfterTheFullIntervalHasPassed() {
        assertTrue(scaleCacheResetDue(30_000, 0))
        assertTrue(scaleCacheResetDue(64_000, 34_000))
        assertFalse(scaleCacheResetDue(29_999, 0))
        assertFalse(scaleCacheResetDue(30_000, 1))
        assertFalse(scaleCacheResetDue(0, 0))
    }

    @Test
    fun resetBoundaryIsExactAtTheIntervalEdge() {
        assertFalse(scaleCacheResetDue(AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS - 1, 0))
        assertTrue(scaleCacheResetDue(AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS, 0))
        // A poll that arrives late (the loop sleeps 150ms) still resets only once per interval.
        assertTrue(scaleCacheResetDue(64_000, 34_000))
    }

    @Test
    fun atMostSixResetsFitTheColdstartWaitWindow() {
        // 180s / 30s: the interval must keep enough resets to exercise a stalled
        // cache without hammering a synchronous binder call.
        var lastReset = 0L
        var resets = 0
        var now = 0L
        while (now < 180_000) {
            if (scaleCacheResetDue(now, lastReset)) {
                lastReset = now
                resets++
            }
            now += 150
        }
        assertTrue(resets in 1..6, "unexpected reset count in the 180s window: $resets")
    }

    @Test
    fun missResetIsDueOnlyAfterTheShortIntervalHasPassed() {
        assertFalse(missResetDue(4_999, 0))
        assertTrue(missResetDue(5_000, 0))
        assertTrue(missResetDue(5_001, 0))
        assertFalse(missResetDue(0, 0))
        assertTrue(missResetDue(10_000, 5_000))
    }

    @Test
    fun missResetBoundaryIsExactAtTheIntervalEdge() {
        assertFalse(missResetDue(AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS - 1, 0))
        assertTrue(missResetDue(AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS, 0))
    }

    @Test
    fun theSharedStampMakesTheTwoIntervalsSuppressEachOther() {
        // D-208: one shared stamp -- an await periodic reset at t=40s makes a
        // miss reset not due for the next 5s...
        val awaitResetAt = 40_000L
        assertFalse(missResetDue(awaitResetAt + AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS - 1, awaitResetAt))
        assertTrue(missResetDue(awaitResetAt + AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS, awaitResetAt))
        // ...and a miss reset stamps the same field, so it pushes the next
        // periodic reset a full 30s interval out.
        val missResetAt = awaitResetAt + AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS
        assertFalse(scaleCacheResetDue(missResetAt + AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS - 1, missResetAt))
        assertTrue(scaleCacheResetDue(missResetAt + AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS, missResetAt))
    }

    @Test
    fun recordBudgetStopsAtTheCap() {
        assertTrue(AndroidScaleObserverDiag.recordAllowed(0))
        assertTrue(AndroidScaleObserverDiag.recordAllowed(AndroidScaleObserverDiag.MAX_RECORDS - 1))
        assertFalse(AndroidScaleObserverDiag.recordAllowed(AndroidScaleObserverDiag.MAX_RECORDS))
        assertFalse(AndroidScaleObserverDiag.recordAllowed(AndroidScaleObserverDiag.MAX_RECORDS + 1))
    }

    @Test
    fun textSamplesAreTruncatedToCountAndChars() {
        assertEquals(listOf("a", "b"), AndroidScaleObserverDiag.textSamples(listOf("a", "b")))
        val samples = AndroidScaleObserverDiag.textSamples(List(9) { "t$it" })
        assertEquals(AndroidScaleObserverDiag.MAX_TEXT_SAMPLES, samples.size)
        val long = AndroidScaleObserverDiag.textSamples(listOf("x".repeat(500))).single()
        assertEquals("x".repeat(AndroidScaleObserverDiag.MAX_TEXT_CHARS), long)
    }

    @Test
    fun errorTextIsBoundedAndNamed() {
        val failure = IllegalStateException("a".repeat(400))
        val text = AndroidScaleObserverDiag.errorText(failure)
        assertEquals(AndroidScaleObserverDiag.MAX_ERROR_CHARS, text.length)
        assertTrue(text.startsWith("IllegalStateException: "))
        // A throwable without a message still names its class.
        assertEquals("RuntimeException: no message", AndroidScaleObserverDiag.errorText(RuntimeException()))
        assertEquals("synthetic reason", AndroidScaleObserverDiag.errorText("synthetic reason"))
    }

    @Test
    fun writeFailuresAccumulateAndKeepTheLastError() {
        val failures = AndroidScaleObserverDiag.WriteFailures()
        assertEquals(0, failures.count)
        assertNull(failures.lastError)
        failures.record(java.io.IOException("disk full"))
        failures.record(java.io.IOException("disk still full"))
        assertEquals(2, failures.count)
        assertTrue(failures.lastError!!.startsWith("IOException: disk still full"))
        // A clean write never clears the count: it is cumulative device-side history.
        assertEquals(2, failures.count)
    }

    @Test
    fun payloadBudgetReallyCountsDroppedRecords() {
        val budget = AndroidScaleObserverDiag.PayloadBudget(100)
        assertFalse(budget.needsDrop(100))
        assertTrue(budget.needsDrop(101))
        assertFalse(budget.oversized)
        assertEquals(0, budget.recordsDroppedForSize)
        budget.onRecordDropped()
        budget.onRecordDropped()
        assertTrue(budget.oversized)
        assertEquals(2, budget.recordsDroppedForSize)
    }

    @Test
    fun diagnosticBoundsStayBounded() {
        assertEquals(200, AndroidScaleObserverDiag.MAX_RECORDS)
        assertEquals(5, AndroidScaleObserverDiag.MAX_TEXT_SAMPLES)
        assertEquals(120, AndroidScaleObserverDiag.MAX_TEXT_CHARS)
        assertEquals(30000L, AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS)
        assertEquals(5000L, AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS)
    }
}
