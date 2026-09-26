package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.D (D-182; spec section 5.3): the POINTER_MISSING explicit-recovery matrix. The startup
 * gates themselves are pinned by [LedgerStableStorageTest]/[LedgerSwitchJournalTest]; this file
 * pins the user-confirmed recovery branches on top of them:
 *
 * - a complete-and-current candidate (usable main + integrity + user_version == current) is
 *   adopted through the frozen publish primitive, highest generation first, and the next
 *   resolution opens the adopted generation;
 * - a complete-but-OLD candidate is the upgrade window's raw copy: never adopted, routed to the
 *   discard-and-re-upgrade branch, which requires the legacy original and re-opens the frozen
 *   `UpgradeLegacy` path;
 * - the discard branch is typed-unavailable without a legacy original (desktop), refuses when an
 *   adoptable candidate appears or foreign content exists, and mutates NOTHING on any refusal;
 * - the probe performs zero disk mutation; a decline (never calling the confirmed actions) is
 *   therefore a zero-mutation outcome by construction;
 * - a failure shape that is not `POINTER_MISSING` offers no recovery face at all.
 */
class BackupRestoreRecoveryTest {
    private val hostDirectory = "/host"
    private val legacyMainFile = "/host/legacy/ledger.db"
    private val pointerFile get() = layout.activePointerFile
    private val generationsDirectory get() = layout.generationsDirectory

    private val fileSystem = LedgerFileSystemFake()
    private val layout = ledgerStorageLayout(fileSystem, hostDirectory)

    private fun mainFileOf(generation: Int): String = layout.mainFile(layout.generationDirectory(generation))

    /** The verdict seeds keyed by the candidate's ABSOLUTE main-file path (the port's contract). */
    private class FakeRecoveryDatabase : RestoreIsolatedDatabasePort {
        val integrityByPath = mutableMapOf<String, Boolean>()
        val versionByPath = mutableMapOf<String, Long>()

        override fun readAuthoritativeUserVersion(snapshotPath: String): Long = requireNotNull(versionByPath[snapshotPath]) { "no version seeded for the candidate" }

        override fun readObservedLedgerIdentities(snapshotPath: String): List<String> = error("the recovery never reads identities")

        override fun migrateStrictly(
            snapshotPath: String,
            fromVersion: Long,
            supportedVersions: Set<Long>,
        ): RestoreMigrationOutcome = error("the recovery never migrates")

        override fun validate(snapshotPath: String): RestoreValidationFacts = error("the recovery never runs the full validation")

        override fun integrityCheckOk(snapshotPath: String): Boolean = integrityByPath[snapshotPath] ?: false

        override fun readOwnerCounts(snapshotPath: String): RestoreOwnerCounts = error("the recovery never reads owner counts")
    }

    private fun useCase(
        isolated: RestoreIsolatedDatabasePort,
        legacy: String?,
    ): PointerMissingRecoveryUseCase =
        PointerMissingRecoveryUseCase(
            fileSystem = fileSystem,
            layout = layout,
            isolatedDatabase = isolated,
            currentSchemaVersion = 31L,
            legacyMainFile = legacy,
        )

    /** Seeds a pointerless-generations state: candidates on disk, NO pointer, no journal. */
    private fun seedPointerless(
        vararg generations: Int,
        withLegacy: Boolean = false,
    ) {
        fileSystem.putDirectory(generationsDirectory)
        for (generation in generations) {
            fileSystem.putFile(mainFileOf(generation), sqliteLikeBytes(filler = generation.toByte()))
        }
        if (withLegacy) fileSystem.putFile(legacyMainFile, sqliteLikeBytes(filler = 9))
    }

    /** Seeds the verification verdicts of one candidate: integrity ok and the given version. */
    private fun seedVerdict(
        generation: Int,
        version: Long,
        integrityOk: Boolean = true,
    ) {
        val isolated = isolatedDatabase as FakeRecoveryDatabase
        isolated.integrityByPath[mainFileOf(generation)] = integrityOk
        isolated.versionByPath[mainFileOf(generation)] = version
    }

    private val isolatedDatabase = FakeRecoveryDatabase()

    // ---------------------------------------------------------------- the recovery face (spec 5.3)

    @Test
    fun aCompleteAndCurrentCandidateIsAdoptedAndTheNextStartOpensIt() {
        seedPointerless(1)
        seedVerdict(1, version = 31L)

        val probe = assertIs<PointerMissingRecoveryProbe.Recoverable>(useCase(isolatedDatabase, null).probe())
        val candidates = probe.state.candidates
        assertEquals(listOf(1), candidates.map { it.generation })
        assertEquals(PointerRecoveryCandidateVerdict.Adoptable, candidates.first().verdict)
        assertEquals(1, probe.state.adoptableGeneration)

        val adopted = assertIs<PointerRecoveryAdoptionResult.Adopted>(useCase(isolatedDatabase, null).adopt())

        assertEquals(1, adopted.generation)
        assertEquals("gen-1", fileSystem.fileBytes(pointerFile)?.decodeToString())
        // The frozen resolution then opens the adopted generation through the pointer.
        val plan = assertIs<LedgerStoragePlan.OpenGeneration>(assertIs<LedgerStorageResolution.Planned>(resolveLedgerStorage(fileSystem, layout, null)).plan)
        assertEquals(1, plan.generation)
    }

    @Test
    fun theHighestAdoptableCandidateWinsTheAdoption() {
        seedPointerless(1, 2)
        seedVerdict(1, version = 30L) // the upgrade window's raw copy
        seedVerdict(2, version = 31L)

        val probe = assertIs<PointerMissingRecoveryProbe.Recoverable>(useCase(isolatedDatabase, legacyMainFile).probe())
        assertEquals(2, probe.state.adoptableGeneration)
        val firstCandidate = probe.state.candidates.first { it.generation == 1 }
        assertEquals(PointerRecoveryCandidateVerdict.WrongVersion, firstCandidate.verdict)

        val adopted = assertIs<PointerRecoveryAdoptionResult.Adopted>(useCase(isolatedDatabase, legacyMainFile).adopt())

        assertEquals(2, adopted.generation)
        assertEquals("gen-2", fileSystem.fileBytes(pointerFile)?.decodeToString())
    }

    @Test
    fun aWrongVersionCandidateIsNeverAdoptedAndRoutesToTheDiscardBranch() {
        seedPointerless(1, withLegacy = true)
        seedVerdict(1, version = 30L)

        val probe = assertIs<PointerMissingRecoveryProbe.Recoverable>(useCase(isolatedDatabase, legacyMainFile).probe())
        assertNull(probe.state.adoptableGeneration)
        assertTrue(probe.state.legacyUpgradeAvailable)

        val adoption = useCase(isolatedDatabase, legacyMainFile).adopt()
        assertIs<PointerRecoveryAdoptionResult.NoAdoptableCandidate>(adoption)
        assertFalse(fileSystem.hasFile(pointerFile), "no partial adoption: the pointer must stay absent")

        val discarded = assertIs<PointerRecoveryDiscardResult.DiscardedAwaitingUpgrade>(useCase(isolatedDatabase, legacyMainFile).discardUnvalidatableAndReUpgrade())

        assertFalse(fileSystem.hasDirectory(layout.generationDirectory(1)))
        assertFalse(fileSystem.hasDirectory(generationsDirectory), "the emptied generations parent must not keep blocking the re-upgrade")
        assertTrue(fileSystem.hasFile(legacyMainFile), "the legacy original is never touched by the discard")
        // The next normal start re-runs the frozen UpgradeLegacy path from the real legacy file.
        val plan = assertIs<LedgerStoragePlan.UpgradeLegacy>(assertIs<LedgerStorageResolution.Planned>(resolveLedgerStorage(fileSystem, layout, legacyMainFile)).plan)
        assertEquals(legacyMainFile, plan.legacyMainFile)
    }

    @Test
    fun aFailedIntegrityCandidateIsUnusableAndNeverAdopted() {
        seedPointerless(1)
        seedVerdict(1, version = 31L, integrityOk = false)

        val probe = assertIs<PointerMissingRecoveryProbe.Recoverable>(useCase(isolatedDatabase, null).probe())

        val onlyCandidate = probe.state.candidates.single()
        assertEquals(PointerRecoveryCandidateVerdict.Unusable, onlyCandidate.verdict)
        assertNull(probe.state.adoptableGeneration)
        assertFalse(fileSystem.hasFile(pointerFile))
    }

    // ---------------------------------------------------------------- the discard branch guards

    @Test
    fun theDiscardBranchIsTypedUnavailableWithoutALegacyOriginal() {
        // The desktop shape: no legacy location exists, so the branch does not exist at all.
        seedPointerless(1)
        seedVerdict(1, version = 30L)

        val probe = assertIs<PointerMissingRecoveryProbe.Recoverable>(useCase(isolatedDatabase, null).probe())
        assertFalse(probe.state.legacyUpgradeAvailable)

        assertIs<PointerRecoveryDiscardResult.LegacyOriginalMissing>(useCase(isolatedDatabase, null).discardUnvalidatableAndReUpgrade())

        assertTrue(fileSystem.hasDirectory(layout.generationDirectory(1)), "zero mutation on the typed refusal")
        assertFalse(fileSystem.hasFile(pointerFile))
    }

    @Test
    fun anAdoptableCandidateAppearingBlocksTheDiscardBeforeAnyDeletion() {
        seedPointerless(1, withLegacy = true)
        seedVerdict(1, version = 31L)

        assertIs<PointerRecoveryDiscardResult.AdoptableCandidatePresent>(useCase(isolatedDatabase, legacyMainFile).discardUnvalidatableAndReUpgrade())

        assertTrue(fileSystem.hasDirectory(layout.generationDirectory(1)))
        assertTrue(fileSystem.hasDirectory(generationsDirectory))
    }

    @Test
    fun foreignContentInTheGenerationsDirectoryRefusesTheDiscardUpFront() {
        seedPointerless(1, withLegacy = true)
        seedVerdict(1, version = 30L)
        fileSystem.putFile("$generationsDirectory/keeper.txt", "not a candidate".encodeToByteArray())

        assertIs<PointerRecoveryDiscardResult.UnexpectedContent>(useCase(isolatedDatabase, legacyMainFile).discardUnvalidatableAndReUpgrade())

        assertTrue(fileSystem.hasDirectory(layout.generationDirectory(1)), "zero mutation: the candidate survives")
        assertTrue(fileSystem.hasFile("$generationsDirectory/keeper.txt"))
    }

    @Test
    fun aStateThatMovedBetweenTheFaceAndTheTapRefusesTypedActions() {
        seedPointerless(1)
        seedVerdict(1, version = 31L)
        val recovery = useCase(isolatedDatabase, null)
        assertIs<PointerMissingRecoveryProbe.Recoverable>(recovery.probe())
        // The shape moved: a concurrent start published a pointer between the face and the tap.
        fileSystem.putFile(pointerFile, "gen-1")

        assertIs<PointerRecoveryAdoptionResult.StateChanged>(recovery.adopt())
        assertIs<PointerRecoveryDiscardResult.StateChanged>(recovery.discardUnvalidatableAndReUpgrade())
        assertEquals("gen-1", fileSystem.fileBytes(pointerFile)?.decodeToString())
    }

    @Test
    fun aNonPointerMissingFailureShapeOffersNoRecoveryFace() {
        // An unrecognizable journal keeps the frozen fail-closed gate; the recovery use case must
        // not offer a face for it (the spec's recovery shape is POINTER_MISSING only).
        seedPointerless(1, withLegacy = true)
        fileSystem.putFile(layout.switchJournalFile, "stage=prepared".encodeToByteArray())

        val probe = useCase(isolatedDatabase, legacyMainFile).probe()

        assertIs<PointerMissingRecoveryProbe.NotPointerMissing>(probe)
    }

    @Test
    fun theProbePerformsZeroDiskMutation() {
        seedPointerless(1, withLegacy = true)
        seedVerdict(1, version = 31L)
        val opsBefore = fileSystem.operationsSnapshot().size

        useCase(isolatedDatabase, legacyMainFile).probe()

        val newOps = fileSystem.operationsSnapshot().drop(opsBefore)
        assertTrue(newOps.none { it.startsWith("writeAtomic:") || it.startsWith("delete:") }, "the probe must be read-only: $newOps")
        assertFalse(fileSystem.hasFile(pointerFile))
        assertTrue(fileSystem.hasDirectory(layout.generationDirectory(1)))
    }
}
