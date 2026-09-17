package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-PERF (P7-04 read-governance batch, spec section 2.3, rework path 1a): the authoritative
 * current-state read's single-flight COALESCING admission — `refresh()` and the initial load
 * share one slot, so a request arriving mid-read merges into the running read and the completion
 * starts exactly one fresh re-run (a plain drop could land data captured before a
 * just-committed entry; more than one re-run would duplicate reads). The result-dispatch
 * serialization itself (one load at a time, completion before re-run) is the coordinator's
 * structural guarantee.
 */
class P503CurrentStateLoadCoordinatorTest {
    @Test
    fun theFirstRequestStartsTheLoadAndConcurrentRequestsCoalesce() {
        var starts = 0
        val coordinator = P503CurrentStateLoadCoordinator()

        assertTrue(coordinator.startLoadOnce { starts += 1 })
        // Two concurrent requests while the read runs: both coalesce, neither starts a second
        // read.
        assertFalse(coordinator.startLoadOnce { starts += 1 })
        assertFalse(coordinator.startLoadOnce { starts += 1 })
        assertEquals(1, starts)

        // Completion reports exactly ONE deferred re-run regardless of how many merged.
        assertTrue(coordinator.loadCompleted())
        assertTrue(coordinator.startLoadOnce { starts += 1 })
        assertEquals(2, starts)
    }

    @Test
    fun aCompletedLoadWithoutMergedRequestsReArmsTheSlot() {
        var starts = 0
        val coordinator = P503CurrentStateLoadCoordinator()
        assertTrue(coordinator.startLoadOnce { starts += 1 })

        // No request merged: no re-run is requested.
        assertFalse(coordinator.loadCompleted())
        assertEquals(1, starts)

        // The slot is free again — the next request starts a real load.
        assertTrue(coordinator.startLoadOnce { starts += 1 })
        assertEquals(2, starts)
    }

    @Test
    fun theCoalescedReRunIsNotItselfDeferredAgain() {
        val coordinator = P503CurrentStateLoadCoordinator()
        assertTrue(coordinator.startLoadOnce { })
        assertFalse(coordinator.startLoadOnce { }) // coalesces

        // The re-run's own completion reports no further re-run (the deferred marker was
        // consumed once).
        assertTrue(coordinator.loadCompleted())
        assertTrue(coordinator.startLoadOnce { }) // the re-run
        assertFalse(coordinator.loadCompleted())
    }
}
