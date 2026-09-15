package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-04.D host-decision tests (D-146; spec sections 3.2.3/3.3.2): the pure [P503HostCoordinator]
 * batch skeleton. The sequential per-item dispatch loop starts at most once at a time — a
 * duplicate start is dropped until the loop's run completes (its final main-dispatcher hop
 * releases the slot after the last per-item result hop, so a paused/abandoned batch always
 * leaves the slot free before the user can act); one Unknown-item replay check may be in
 * flight and duplicate evaluations are dropped until its verdict landed (不自动重试： the check
 * is always an explicit user action). Counting-callback pattern (P503HostCoordinatorTest
 * precedent).
 */
class P503ImportBatchHostCoordinatorTest {
    @Test
    fun theDispatchLoopIsSingleFlightUntilItsRunCompletes() {
        var runs = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )

        assertTrue(coordinator.startImportBatchDispatchOnce { runs += 1 })
        assertFalse(coordinator.startImportBatchDispatchOnce { runs += 1 })
        assertEquals(1, runs)

        // The run completing re-arms the single-flight slot.
        coordinator.importBatchDispatchCompleted()
        assertTrue(coordinator.startImportBatchDispatchOnce { runs += 1 })
        assertEquals(2, runs)
    }

    @Test
    fun theUnknownItemCheckIsSingleFlightUntilItsVerdictLanded() {
        var checks = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )

        assertTrue(coordinator.submitImportUnknownCheckOnce { checks += 1 })
        assertFalse(coordinator.submitImportUnknownCheckOnce { checks += 1 })
        assertEquals(1, checks)

        coordinator.importUnknownCheckCompleted()
        assertTrue(coordinator.submitImportUnknownCheckOnce { checks += 1 })
        assertEquals(2, checks)
    }
}
