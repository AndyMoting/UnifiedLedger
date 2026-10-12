package com.unifiedledger.android

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-09 09.0 (spec section 6.3; D-225 Q19 P1) P2-6 evidence: the process-scoped holder's ONE-start
 * invariant. The holder itself needs an Android `Context` (it builds the real controller), so the
 * single-flight admission is asserted on its pure [ensureStartedOnce] seam — the exact predicate the
 * product's `LedgerProcessHolder.ensureStarted` delegates to. No Robolectric: the seam takes the
 * `AtomicBoolean` gate and the start action directly (the `AndroidStartupControllerTest` injection
 * pattern).
 */
class LedgerProcessHolderStartTest {
    @Test
    fun theControllerIsStartedExactlyOnceAcrossEveryEntry() {
        // The composition, a re-composition and an Activity recreation all call through here; only
        // the first obtains the CAS and starts the ONE controller.
        val gate = AtomicBoolean(false)
        var starts = 0
        assertTrue(ensureStartedOnce(gate) { starts += 1 })
        assertFalse(ensureStartedOnce(gate) { starts += 1 })
        assertFalse(ensureStartedOnce(gate) { starts += 1 })
        assertEquals(1, starts, "the process's single controller must be started exactly once")
    }

    @Test
    fun theStartActionIsNotRunWhenTheGateIsAlreadyClaimed() {
        // A gate claimed by another entry (e.g. a non-composition entry that reached the holder
        // first) suppresses this caller's start entirely.
        val gate = AtomicBoolean(true)
        var starts = 0
        assertFalse(ensureStartedOnce(gate) { starts += 1 })
        assertEquals(0, starts)
    }
}
