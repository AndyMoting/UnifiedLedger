package com.unifiedledger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEF-EXPORT-OOM (D-269 item 7): the two opt-in heap-capture gates. These assertions are the guard
 * that the diagnostic seam can never fire in production: a release build has `FLAG_DEBUGGABLE`
 * clear, so [heapCaptureEnabled] must always refuse, whatever the other input says — and the wiring
 * then hands the adapter no hook at all.
 *
 * The timing assertions encode review OOM-5: the A06 run shows `118MB/142MB` in use BEFORE the
 * export starts, so an absolute heap bar alone fires on the first read of pass 1 and the dump would
 * predate the request under investigation. [HEAP_CAPTURE_MIN_CHUNKS] is what defers it.
 */
class BackupHeapCapturePolicyTest {
    /** The registered failure's growth limit (`dalvik.vm.heapgrowthlimit=192m`). */
    private val registeredLimit: Long = 201_326_592L

    /** The registered pre-failure footprint that preceded the 134086668 byte request. */
    private val registeredUsed: Long = 98_824_992L

    /** The run's pre-export heap reading (`118MB/142MB`, A06 logcat). */
    private val preExportUsed: Long = 118L * 1024L * 1024L

    /** Past the chunk floor, so the heap bar is the only variable these cases exercise. */
    private val pastFloor: Long = HEAP_CAPTURE_MIN_CHUNKS + 1

    @Test
    fun aReleaseBuildNeverEnablesTheCaptureEvenWhenTheOperatorOptedIn() {
        assertFalse(
            heapCaptureEnabled(debuggable = false, optedIn = true),
            "a release build must never capture a heap dump",
        )
    }

    @Test
    fun aDebuggableBuildWithoutTheSentinelNeverEnablesTheCapture() {
        assertFalse(
            heapCaptureEnabled(debuggable = true, optedIn = false),
            "without the operator sentinel the hook must stay inert",
        )
    }

    @Test
    fun onlyADebuggableBuildWithTheSentinelEnablesTheCapture() {
        assertTrue(heapCaptureEnabled(debuggable = true, optedIn = true))
    }

    @Test
    fun theRegisteredPreFailureFootprintIsDueOncePastTheChunkFloor() {
        assertTrue(
            heapCaptureDue(usedBytes = registeredUsed, limitBytes = registeredLimit, chunksObserved = pastFloor),
            "the registered pre-failure footprint must trigger the capture",
        )
    }

    @Test
    fun theAlreadyHeavyPreExportHeapIsNotDueOnTheFirstReads() {
        // This is the OOM-5 guard: a heavy heap on its own is NOT enough, or the dump would be
        // taken before pass 1 streamed anything. 118 MB clears the 45% bar and must still wait.
        assertTrue(preExportUsed >= thresholdBytes(registeredLimit), "the fixture must clear the bar")
        assertFalse(
            heapCaptureDue(usedBytes = preExportUsed, limitBytes = registeredLimit, chunksObserved = 1L),
            "the first read must not dump, whatever the heap already holds",
        )
        assertTrue(
            heapCaptureDue(usedBytes = preExportUsed, limitBytes = registeredLimit, chunksObserved = HEAP_CAPTURE_MIN_CHUNKS),
            "once the floor is reached the same heap is due",
        )
    }

    @Test
    fun anIdleExportBelowTheThresholdIsNotDueEvenPastTheFloor() {
        assertFalse(
            heapCaptureDue(usedBytes = registeredLimit / 10, limitBytes = registeredLimit, chunksObserved = Long.MAX_VALUE / 2),
            "an idle export must not pay for a heap dump",
        )
    }

    @Test
    fun theBarIsUnderHalfTheLimitAndTheFloorIsATangibleAmountOfStreaming() {
        assertEquals(90_596_925L, thresholdBytes(registeredLimit))
        // The bar must sit at or below the recorded pre-failure footprint, or it would miss it.
        assertTrue(thresholdBytes(registeredLimit) <= registeredUsed)
        assertEquals(45L, HEAP_CAPTURE_THRESHOLD_NUMERATOR)
        assertEquals(100L, HEAP_CAPTURE_THRESHOLD_DIVISOR)
        // 256 chunks of the frozen 64 KiB is 16 MiB: enough to be inside pass 1, not the first read.
        assertEquals(16L * 1024L * 1024L, HEAP_CAPTURE_MIN_CHUNKS * 64L * 1024L)
    }

    @Test
    fun aZeroOrUnknownHeapLimitIsNeverDue() {
        assertFalse(
            heapCaptureDue(usedBytes = 0L, limitBytes = 0L, chunksObserved = Long.MAX_VALUE / 2),
            "an unknown limit must refuse rather than divide by zero",
        )
    }
}
