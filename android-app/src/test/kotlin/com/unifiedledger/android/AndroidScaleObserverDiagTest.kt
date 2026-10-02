package com.unifiedledger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidScaleObserverDiagTest {
    @Test
    fun resetIsDueOnlyAfterTheFullIntervalHasPassed() {
        assertTrue(scaleCacheResetDue(5000, 0))
        assertTrue(scaleCacheResetDue(9000, 4000))
        assertFalse(scaleCacheResetDue(4999, 0))
        assertFalse(scaleCacheResetDue(5000, 1))
        assertFalse(scaleCacheResetDue(0, 0))
    }

    @Test
    fun resetBoundaryIsExactAtTheIntervalEdge() {
        assertTrue(scaleCacheResetDue(AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS, -1))
        assertFalse(scaleCacheResetDue(AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS - 1, -1))
        // A poll that arrives late (the loop sleeps 150ms) still resets only once per interval.
        assertTrue(scaleCacheResetDue(15_400, 10_000))
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
    fun jsonSizeGuardTripsOnlyAboveTheLimit() {
        assertFalse(AndroidScaleObserverDiag.jsonOversized(AndroidScaleObserverDiag.MAX_JSON_CHARS))
        assertTrue(AndroidScaleObserverDiag.jsonOversized(AndroidScaleObserverDiag.MAX_JSON_CHARS + 1))
    }

    @Test
    fun diagnosticBoundsStayBounded() {
        assertEquals(200, AndroidScaleObserverDiag.MAX_RECORDS)
        assertEquals(5, AndroidScaleObserverDiag.MAX_TEXT_SAMPLES)
        assertEquals(120, AndroidScaleObserverDiag.MAX_TEXT_CHARS)
        assertEquals(5000L, AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS)
    }
}
