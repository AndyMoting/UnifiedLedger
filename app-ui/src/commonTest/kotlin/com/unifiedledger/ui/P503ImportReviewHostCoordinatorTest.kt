package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-04.C host-decision tests (D-146; spec sections 4.1/4.2/6.2): the pure
 * [P503HostCoordinator] import skeleton. A picked file starts the bounded-read + intake pipeline
 * exactly once at a time (single flight, the checkCommitStatus precedent) and a concurrent second
 * pick is dropped until the pipeline completes; cancellations and typed platform failures start
 * nothing (spec 4.1.4: zero diagnostics on a cancellation); a duplicate duplicate-review
 * submission is dropped while one is in flight and resumes only after its result completed
 * (期间禁重复提交). Counting-callback pattern (P503HostCoordinatorTest precedent).
 */
class P503ImportReviewHostCoordinatorTest {
    private val pickedFile = PickedImportFile(displayName = "synthetic-bill.csv", sizeBytes = null) { BoundedFileRead.Bytes(ByteArray(0)) }

    @Test
    fun pickedFileStartsTheIntakePipelineOnceAtATime() {
        var intakes = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
                onImportPickIntake = { intakes += 1 },
            )

        val first = coordinator.handleImportPickResult(ImportFilePickResult.Picked(pickedFile))
        val started = assertIs<ImportPickIntakeDecision.StartIntake>(first)
        assertEquals("synthetic-bill.csv", started.file.displayName)
        assertEquals(1, intakes)

        // Single flight: a concurrent second pick is dropped (its channel events are absorbed by
        // the reducer; the host starts no second pipeline).
        assertEquals(ImportPickIntakeDecision.AlreadyInFlight, coordinator.handleImportPickResult(ImportFilePickResult.Picked(pickedFile)))
        assertEquals(1, intakes)

        // The pipeline completing re-arms the single-flight slot.
        coordinator.importIntakeCompleted()
        assertIs<ImportPickIntakeDecision.StartIntake>(coordinator.handleImportPickResult(ImportFilePickResult.Picked(pickedFile)))
        assertEquals(2, intakes)
    }

    @Test
    fun cancelledAndFailedPicksStartNoPipeline() {
        var intakes = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
                onImportPickIntake = { intakes += 1 },
            )

        assertEquals(ImportPickIntakeDecision.NoPipeline, coordinator.handleImportPickResult(ImportFilePickResult.Cancelled))
        assertEquals(ImportPickIntakeDecision.NoPipeline, coordinator.handleImportPickResult(ImportFilePickResult.Failed(ImportFilePickFailure.PICKER_LAUNCH_FAILED)))
        assertEquals(0, intakes)
    }

    @Test
    fun duplicateReviewSubmissionIsSingleFlightUntilCompleted() {
        var submissions = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )

        assertTrue(coordinator.submitImportDuplicateReviewOnce { submissions += 1 })
        // 期间禁重复提交: a duplicate evaluation while one submission is in flight is dropped.
        assertFalse(coordinator.submitImportDuplicateReviewOnce { submissions += 1 })
        assertEquals(1, submissions)

        // The review result completing re-arms the single-flight slot.
        coordinator.importDuplicateReviewCompleted()
        assertTrue(coordinator.submitImportDuplicateReviewOnce { submissions += 1 })
        assertEquals(2, submissions)
    }

    /**
     * P704C-SPEC-01/QUAL-02: the 整组确认页 enumeration is single-flight — a double tap must not
     * interleave two enumerations (two Start events would race the page state); the slot re-arms
     * once the enumeration outcome has been dispatched.
     */
    @Test
    fun groupEnumerationIsSingleFlightUntilCompleted() {
        var enumerations = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )

        assertTrue(coordinator.startImportDuplicateGroupDispositionOnce { enumerations += 1 })
        assertFalse(coordinator.startImportDuplicateGroupDispositionOnce { enumerations += 1 })
        assertEquals(1, enumerations)

        coordinator.importGroupEnumerationCompleted()
        assertTrue(coordinator.startImportDuplicateGroupDispositionOnce { enumerations += 1 })
        assertEquals(2, enumerations)
    }

    /**
     * P704C-SPEC-01/QUAL-02: the group-disposition per-item review loop is single-flight — a
     * double tap must not interleave two core review loops; the slot re-arms once the loop result
     * has been dispatched.
     */
    @Test
    fun groupDispositionLoopIsSingleFlightUntilCompleted() {
        var loops = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )

        assertTrue(coordinator.confirmImportDuplicateGroupDispositionOnce { loops += 1 })
        assertFalse(coordinator.confirmImportDuplicateGroupDispositionOnce { loops += 1 })
        assertEquals(1, loops)

        coordinator.importGroupDispositionCompleted()
        assertTrue(coordinator.confirmImportDuplicateGroupDispositionOnce { loops += 1 })
        assertEquals(2, loops)
    }
}
