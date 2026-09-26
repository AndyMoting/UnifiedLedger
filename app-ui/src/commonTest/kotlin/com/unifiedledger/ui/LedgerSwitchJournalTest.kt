package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md`
 * section 4, table in 4.1 and rules in 4.2/4.3): the persistent switch journal machine — the
 * restart half of the frozen ROLLBACK rule of the container-format spec section 5.3, executed by
 * [resolveLedgerStorage]'s extended journal gate. Every crash window of the section 4.1 table is
 * exercised through the resolution entry point, with the D-176 fail-closed semantics asserted to
 * survive untouched for anything unrecognizable:
 *
 * - a recognizable `prepared` journal with the pointer still on the old generation discards the
 *   staged new-generation directory (including sidecars), removes the journal and opens the old
 *   generation (crash after the prepared write, before the pointer publish);
 * - a recognizable `prepared` journal whose pointer ALREADY moved, and every `switched` journal,
 *   additionally REPUBLISH the old pointer first (the crash windows after the pointer publish
 *   and before the journal removal), then discard and open the old generation;
 * - a republish or journal-removal failure keeps the frozen fail-closed JOURNAL_PRESENT gate
 *   with the disk exactly as found, so the next start retries deterministically;
 * - a foreign, wrong-version, wrong-stage or wrong-shape journal keeps the frozen fail-closed
 *   JOURNAL_PRESENT gate and is never consumed.
 */
class LedgerSwitchJournalTest {
    private val fileSystem = LedgerFileSystemFake()
    private val layout = ledgerStorageLayout(fileSystem, "/data")

    private val pointer get() = layout.activePointerFile
    private val journal get() = layout.switchJournalFile
    private val generationOneDirectory = layout.generationDirectory(1)
    private val generationOneMain get() = layout.mainFile(generationOneDirectory)
    private val generationTwoDirectory = layout.generationDirectory(2)
    private val generationTwoMain get() = layout.mainFile(generationTwoDirectory)
    private val generationTwoSidecar get() = layout.sidecarFile(generationTwoDirectory, "-wal")

    private fun seedActiveGenerationOne() {
        fileSystem.putDirectory(layout.generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile(pointer, "gen-1")
    }

    private fun seedStagedGenerationTwo() {
        fileSystem.putDirectory(generationTwoDirectory)
        fileSystem.putFile(generationTwoMain, sqliteLikeBytes(filler = 1))
        fileSystem.putFile(generationTwoSidecar, ByteArray(8) { 9 })
    }

    private fun seedJournal(
        stage: LedgerSwitchJournalStage,
        old: Int = 1,
        new: Int = 2,
    ) {
        fileSystem.putFile(journal, ledgerSwitchJournalBytes(LedgerSwitchJournal(stage, old, new)))
    }

    private fun resolution() = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

    // ---------------------------------------------------------------- encoding (spec 4.2)

    @Test
    fun theJournalRoundTripsThroughItsEncodingAndRejectsForeignContent() {
        for (stage in LedgerSwitchJournalStage.entries) {
            val parsed = parseLedgerSwitchJournal(ledgerSwitchJournalBytes(LedgerSwitchJournal(stage, 3, 4)))

            val record = assertIs<LedgerSwitchJournal>(parsed)
            assertEquals(stage, record.stage)
            assertEquals(3, record.oldGeneration)
            assertEquals(4, record.newGeneration)
        }
        // Anything foreign — no header, a future version, an unknown stage, a shape the confirm
        // flow never writes — is unrecognizable and keeps the fail-closed gate.
        assertNull(parseLedgerSwitchJournal("stage=prepared".encodeToByteArray()))
        assertNull(parseLedgerSwitchJournal("unified-ledger switch journal v2\nstage=prepared\nold=gen-1\nnew=gen-2".encodeToByteArray()))
        assertNull(parseLedgerSwitchJournal("unified-ledger switch journal v1\nstage=aborted\nold=gen-1\nnew=gen-2".encodeToByteArray()))
        assertNull(parseLedgerSwitchJournal("unified-ledger switch journal v1\nstage=prepared\nold=gen-1\nnew=gen-1".encodeToByteArray()))
        assertNull(parseLedgerSwitchJournal("unified-ledger switch journal v1\nstage=prepared\nold=gen-1\nnew=gen-5".encodeToByteArray()))
    }

    // ---------------------------------------------------------------- crash windows (spec 4.1 table)

    @Test
    fun aPreparedJournalWithTheOldPointerDiscardsTheStagedDirectoryAndOpensTheOldGeneration() {
        seedActiveGenerationOne()
        seedStagedGenerationTwo()
        seedJournal(LedgerSwitchJournalStage.Prepared)

        val plan = assertIs<LedgerStorageResolution.Planned>(resolution()).plan

        // Crash after the prepared write, before the pointer publish: the pointer never moved,
        // so no republish is needed — the staged directory is discarded and the old generation opens.
        val open = assertIs<LedgerStoragePlan.OpenGeneration>(plan)
        assertEquals(1, open.generation)
        assertEquals("gen-1", fileSystem.fileBytes(pointer)?.decodeToString())
        assertFalse(fileSystem.hasFile(journal))
        assertFalse(fileSystem.hasDirectory(generationTwoDirectory))
        assertTrue(fileSystem.hasDirectory(generationOneDirectory))
    }

    @Test
    fun aPreparedJournalWhosePointerAlreadyMovedRollsBackToTheOldGeneration() {
        seedActiveGenerationOne()
        seedStagedGenerationTwo()
        seedJournal(LedgerSwitchJournalStage.Prepared)
        // The crash window between the pointer publish and the switched journal write: the
        // pointer names the NEW generation while the journal still says prepared.
        fileSystem.putFile(pointer, "gen-2")

        val plan = assertIs<LedgerStorageResolution.Planned>(resolution()).plan

        val open = assertIs<LedgerStoragePlan.OpenGeneration>(plan)
        assertEquals(1, open.generation)
        // The old pointer is republished through the frozen primitive; the staged directory
        // (including its sidecar) is discarded; the journal is removed.
        assertEquals("gen-1", fileSystem.fileBytes(pointer)?.decodeToString())
        assertFalse(fileSystem.hasFile(journal))
        assertFalse(fileSystem.hasDirectory(generationTwoDirectory))
    }

    @Test
    fun aSwitchedJournalRollsBackToTheOldGenerationAndDiscardsTheNewDirectory() {
        seedActiveGenerationOne()
        seedStagedGenerationTwo()
        seedJournal(LedgerSwitchJournalStage.Switched)
        fileSystem.putFile(pointer, "gen-2")

        val plan = assertIs<LedgerStorageResolution.Planned>(resolution()).plan

        val open = assertIs<LedgerStoragePlan.OpenGeneration>(plan)
        assertEquals(1, open.generation)
        assertEquals("gen-1", fileSystem.fileBytes(pointer)?.decodeToString())
        assertFalse(fileSystem.hasFile(journal))
        assertFalse(fileSystem.hasDirectory(generationTwoDirectory))
        assertFalse(fileSystem.hasFile(generationTwoSidecar))
        assertTrue(fileSystem.hasDirectory(generationOneDirectory))
    }

    @Test
    fun aStagedDirectoryDeletionFailureDoesNotBlockTheStartupRecovery() {
        seedActiveGenerationOne()
        seedStagedGenerationTwo()
        seedJournal(LedgerSwitchJournalStage.Prepared)
        fileSystem.failOn = "delete:$generationTwoMain"

        val plan = assertIs<LedgerStorageResolution.Planned>(resolution()).plan

        // The pointer/journal recovery is the obligation; the new-generation deletion is best
        // effort by contract (spec section 4.2), so a deletion failure never fails the start.
        val open = assertIs<LedgerStoragePlan.OpenGeneration>(plan)
        assertEquals(1, open.generation)
        assertFalse(fileSystem.hasFile(journal))
        assertTrue(fileSystem.hasDirectory(generationTwoDirectory))
    }

    // ---------------------------------------------------------------- recovery failure keeps the gate (spec 4.2/4.3)

    @Test
    fun aJournalRecoveryPublishFailureKeepsTheFailClosedGate() {
        seedActiveGenerationOne()
        seedStagedGenerationTwo()
        seedJournal(LedgerSwitchJournalStage.Switched)
        fileSystem.putFile(pointer, "gen-2")
        fileSystem.failOn = "writeAtomic:$pointer"

        val rejected = assertIs<LedgerStorageResolution.Rejected>(resolution())

        assertEquals(LedgerStorageFailure.JOURNAL_PRESENT, rejected.failure)
        // Nothing was consumed or rewritten: the next start retries from the same journal.
        assertEquals("gen-2", fileSystem.fileBytes(pointer)?.decodeToString())
        assertTrue(fileSystem.hasFile(journal))
        assertTrue(fileSystem.hasDirectory(generationTwoDirectory))
    }

    @Test
    fun aJournalRecoveryJournalRemovalFailureKeepsTheFailClosedGate() {
        seedActiveGenerationOne()
        seedStagedGenerationTwo()
        seedJournal(LedgerSwitchJournalStage.Switched)
        fileSystem.putFile(pointer, "gen-2")
        fileSystem.failOn = "delete:$journal"

        val rejected = assertIs<LedgerStorageResolution.Rejected>(resolution())

        assertEquals(LedgerStorageFailure.JOURNAL_PRESENT, rejected.failure)
        // The republish succeeded (the rollback anchor is back) but the journal survived, so
        // the gate stays closed and the staged directory is still pending deletion.
        assertEquals("gen-1", fileSystem.fileBytes(pointer)?.decodeToString())
        assertTrue(fileSystem.hasFile(journal))
        assertTrue(fileSystem.hasDirectory(generationTwoDirectory))
    }

    @Test
    fun anUnrecognizableJournalKeepsTheFailClosedGateAndIsNeverConsumed() {
        seedActiveGenerationOne()
        seedStagedGenerationTwo()
        fileSystem.putFile(journal, "stage=prepared")

        val rejected = assertIs<LedgerStorageResolution.Rejected>(resolution())

        assertEquals(LedgerStorageFailure.JOURNAL_PRESENT, rejected.failure)
        assertEquals("stage=prepared", fileSystem.fileBytes(journal)?.decodeToString())
        assertTrue(fileSystem.hasDirectory(generationTwoDirectory))
    }
}
