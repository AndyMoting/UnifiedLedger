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
 * The heap bar is pinned against the registered device figures (`target footprint 98824992` against
 * `growth limit 201326592`, D-269 item 7).
 */
class BackupHeapCapturePolicyTest {
    /** The registered failure's growth limit (`dalvik.vm.heapgrowthlimit=192m`). */
    private val registeredLimit: Long = 201_326_592L

    /** The registered pre-failure footprint that preceded the 134086668 byte request. */
    private val registeredUsed: Long = 98_824_992L

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
    fun theRegisteredPreFailureFootprintIsDueForACapture() {
        assertTrue(
            heapCaptureDue(usedBytes = registeredUsed, limitBytes = registeredLimit),
            "the registered pre-failure footprint must trigger the capture",
        )
    }

    @Test
    fun anIdleExportBelowTheThresholdIsNotDue() {
        assertFalse(
            heapCaptureDue(usedBytes = registeredLimit / 10, limitBytes = registeredLimit),
            "an idle export must not pay for a heap dump",
        )
    }

    @Test
    fun theThresholdIsTwoFifthsOfTheHeapLimit() {
        assertEquals(80_530_636L, thresholdBytes(registeredLimit))
        // The bar must sit below the observed pre-failure footprint, or the capture would miss it.
        assertTrue(thresholdBytes(registeredLimit) < registeredUsed)
        assertEquals(2L, HEAP_CAPTURE_THRESHOLD_NUMERATOR)
        assertEquals(5L, HEAP_CAPTURE_THRESHOLD_DIVISOR)
    }

    @Test
    fun aZeroOrUnknownHeapLimitIsNeverDue() {
        assertFalse(
            heapCaptureDue(usedBytes = 0L, limitBytes = 0L),
            "an unknown limit must refuse rather than divide by zero",
        )
    }
}
