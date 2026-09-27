package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 06.1 (D-176; spec sections 3.2, 4.5, 5.1 and 6): the shared stable-storage resolution,
 * the silent-empty-database prohibitions, the journal gate and the non-destructive legacy
 * upgrade with its crash ordering. Every test maps to a spec section 6 failure vector.
 */
class LedgerStableStorageTest {
    private val fileSystem = LedgerFileSystemFake()
    private val layout = ledgerStorageLayout(fileSystem, "/data")

    private val generationsDirectory get() = layout.generationsDirectory
    private val pointer get() = layout.activePointerFile
    private val journal get() = layout.switchJournalFile
    private val generationOneDirectory get() = layout.generationDirectory(1)
    private val generationOneMain get() = layout.mainFile(generationOneDirectory)

    // ---------------------------------------------------------------- resolution rules (section 3.2)

    @Test
    fun noGenerationDirectoryAndNoLegacyIsTheOnlyFreshInstallPath() {
        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        val plan = assertIs<LedgerStorageResolution.Planned>(resolution).plan
        assertIs<LedgerStoragePlan.FreshInstall>(plan)
        assertEquals(generationOneMain, plan.mainFile)
    }

    @Test
    fun existingGenerationDirectoryWithNoPointerFailsClosedInsteadOfFreshInstalling() {
        // Rule 4: the rule 1 (a)->(d) crash window leaves exactly this state; it must never be
        // mistaken for a fresh install (the silent-empty-DB prohibition).
        fileSystem.putDirectory(generationsDirectory)

        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        assertEquals(
            LedgerStorageFailure.POINTER_MISSING,
            assertIs<LedgerStorageResolution.Rejected>(resolution).failure,
        )
    }

    @Test
    fun existingGenerationDirectoryWithAnInvalidPointerFailsClosed() {
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putFile(pointer, "not-a-generation")

        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        assertEquals(
            LedgerStorageFailure.POINTER_INVALID,
            assertIs<LedgerStorageResolution.Rejected>(resolution).failure,
        )
    }

    @Test
    fun pointerToAMissingGenerationFailsClosedWithoutCreatingIt() {
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putFile(pointer, "gen-7")

        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        assertEquals(
            LedgerStorageFailure.ACTIVE_GENERATION_MISSING,
            assertIs<LedgerStorageResolution.Rejected>(resolution).failure,
        )
        assertFalse(fileSystem.hasDirectory(layout.generationDirectory(7)))
    }

    @Test
    fun pointerToAZeroLengthMainFileFailsClosedOnThePreOpenGuard() {
        // Section 4.5 / NEW-1: the pointer is durable but the new generation is only partially
        // on disk. A mere existence check would let create-on-open build an empty schema.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, ByteArray(0))
        fileSystem.putFile(pointer, "gen-1")

        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        assertEquals(
            LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE,
            assertIs<LedgerStorageResolution.Rejected>(resolution).failure,
        )
    }

    @Test
    fun pointerToAnInvalidHeaderMainFileFailsClosedOnThePreOpenGuard() {
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, "not a sqlite database at all")
        fileSystem.putFile(pointer, "gen-1")

        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        assertEquals(
            LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE,
            assertIs<LedgerStorageResolution.Rejected>(resolution).failure,
        )
    }

    @Test
    fun validPointerAndUsableMainFileSelectsTheGeneration() {
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile(pointer, "gen-1")

        val plan = assertIs<LedgerStorageResolution.Planned>(resolveLedgerStorage(fileSystem, layout, null)).plan

        val open = assertIs<LedgerStoragePlan.OpenGeneration>(plan)
        assertEquals(1, open.generation)
        assertEquals(generationOneMain, open.mainFile)
    }

    @Test
    fun anExistingGenerationDirectoryIsNeverOverriddenByALegacyFile() {
        // Rule 2: once a generation directory exists the legacy location is not consulted again.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putFile(pointer, "gen-1")
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile("/data/databases/ledger.db", sqliteLikeBytes())

        val plan = assertIs<LedgerStorageResolution.Planned>(resolveLedgerStorage(fileSystem, layout, "/data/databases/ledger.db")).plan

        assertIs<LedgerStoragePlan.OpenGeneration>(plan)
    }

    @Test
    fun aLegacyDatabaseWithoutAGenerationDirectorySelectsTheUpgradePath() {
        fileSystem.putFile("/data/databases/ledger.db", sqliteLikeBytes())

        val plan = assertIs<LedgerStorageResolution.Planned>(resolveLedgerStorage(fileSystem, layout, "/data/databases/ledger.db")).plan

        val upgrade = assertIs<LedgerStoragePlan.UpgradeLegacy>(plan)
        assertEquals("/data/databases/ledger.db", upgrade.legacyMainFile)
    }

    // ---------------------------------------------------------------- journal gate (section 5.1 step 2)

    @Test
    fun aPresentJournalFailsClosedBeforeAnyGenerationIsSelected() {
        // 06.1 never writes a journal; a present one is a 06.D-era state it cannot roll back.
        fileSystem.putFile(journal, "prepared")
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile(pointer, "gen-1")

        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        assertEquals(
            LedgerStorageFailure.JOURNAL_PRESENT,
            assertIs<LedgerStorageResolution.Rejected>(resolution).failure,
        )
    }

    @Test
    fun theJournalGateDoesNotDeleteOrRewriteTheJournal() {
        fileSystem.putFile(journal, "prepared")

        resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)

        assertEquals("prepared", fileSystem.fileBytes(journal)?.decodeToString())
    }

    // ---------------------------------------------------------------- pre-open guard (section 4.5)

    @Test
    fun thePreOpenGuardAcceptsOnlyANonEmptyValidSqliteHeader() {
        fileSystem.putFile("/m/valid.db", sqliteLikeBytes())
        fileSystem.putFile("/m/empty.db", ByteArray(0))
        fileSystem.putFile("/m/short.db", LEDGER_SQLITE_HEADER.copyOfRange(0, 4))
        fileSystem.putFile("/m/wrong.db", "SQLite format 4\u0000".encodeToByteArray())

        assertTrue(isUsableSqliteMainFile(fileSystem, "/m/valid.db"))
        assertFalse(isUsableSqliteMainFile(fileSystem, "/m/empty.db"))
        assertFalse(isUsableSqliteMainFile(fileSystem, "/m/short.db"))
        assertFalse(isUsableSqliteMainFile(fileSystem, "/m/wrong.db"))
        assertFalse(isUsableSqliteMainFile(fileSystem, "/m/absent.db"))
    }

    @Test
    fun thePreOpenGuardReadsOnlyTheHeaderPrefixNotTheWholeLedger() {
        // Review Fix 6: the guard must inspect only the 16-byte SQLite header, never allocate the
        // whole (possibly hundreds-of-MB) ledger on the startup path.
        fileSystem.putFile("/m/valid.db", sqliteLikeBytes())

        assertTrue(isUsableSqliteMainFile(fileSystem, "/m/valid.db"))

        assertEquals(listOf(LEDGER_SQLITE_HEADER.size), fileSystem.readPrefixLengths)
        assertTrue(fileSystem.operationsSnapshot().none { it.startsWith("readBytes:") })
    }

    // ---------------------------------------------------------------- explicit reopen (06.D spec 3.7/3.8)

    @Test
    fun openExplicitGenerationOpensTheNamedGenerationWithoutRunningTheJournalGate() {
        // P7-06 06.D (D-182; spec section 3 step 8 (with 3.8)): the confirm switch's step-8 reopen runs while
        // its OWN `switched` journal is still on disk (it is removed only after the read-back). The
        // explicit opener must NOT run the startup gate: the gate would republish the OLD pointer
        // and delete the NEW generation directory, rolling the switch back. Reverting step 8 to the
        // pointer route is what this primitive exists to prevent.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile(pointer, "gen-2")
        val generationTwoDirectory = layout.generationDirectory(2)
        fileSystem.putDirectory(generationTwoDirectory)
        val generationTwoMain = layout.mainFile(generationTwoDirectory)
        fileSystem.putFile(generationTwoMain, sqliteLikeBytes(filler = 1))
        fileSystem.putFile(journal, ledgerSwitchJournalBytes(LedgerSwitchJournal(LedgerSwitchJournalStage.Switched, 1, 2)))
        val targets = mutableListOf<LedgerOpenTarget>()

        val graph =
            openExplicitGeneration(fileSystem, layout, 2) { target ->
                targets += target
                "graph"
            }

        assertEquals("graph", graph)
        assertEquals(listOf(LedgerOpenTarget(generationTwoMain, allowCreateOnOpen = false)), targets)
        // Nothing was recovered or rewritten: the pointer still names the new generation, the
        // journal is untouched and the new generation directory survives.
        assertEquals("gen-2", fileSystem.fileBytes(pointer)?.decodeToString())
        assertTrue(fileSystem.hasFile(journal))
        assertTrue(fileSystem.hasDirectory(generationTwoDirectory))
    }

    @Test
    fun openExplicitGenerationFailsClosedOnAnUnusableNamedGeneration() {
        // The same silent-empty-database prohibition as every non-fresh startup path: a named
        // generation that is missing, empty or header-less must fail closed and never reach the
        // create-on-open factory.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        val generationOneMainUnusable = layout.mainFile(generationOneDirectory)
        fileSystem.putFile(generationOneMainUnusable, ByteArray(0))
        var opened = false

        val rejected =
            assertFailsWith<LedgerStorageRejectedException> {
                openExplicitGeneration(fileSystem, layout, 1) {
                    opened = true
                    "graph"
                }
            }

        assertEquals(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE, rejected.failure)
        assertFalse(opened)
    }

    // ---------------------------------------------------------------- structured generation deletion (06.D defect 2)

    @Test
    fun deleteGenerationDirectoryRemovesARollbackJournalSidecarToo() {
        // P7-06 06.D device-gate defect 2 (D-183): a read-WRITE framework open leaves a transient
        // `<main>-journal` beside the main file (the repo documents this on device). The deletion
        // must cover it, or the directory stays non-empty and the platform `delete` silently no-ops.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile("$generationOneMain-journal", ByteArray(0))

        deleteGenerationDirectory(fileSystem, layout, 1)

        assertFalse(fileSystem.hasFile("$generationOneMain-journal"), "the transient journal must be deleted with the main file")
        assertFalse(fileSystem.hasDirectory(generationOneDirectory), "the emptied generation directory must be gone")
    }

    @Test
    fun deleteGenerationDirectoryThrowsWhenTheDirectorySurvives() {
        // Defect 2's second half: the platform `delete` silently no-ops on a non-empty directory
        // (the fake models this). The deletion must therefore VERIFY absence and fail loud, so a
        // caller that depends on the directory being gone (the discard branch, the confirm-time
        // delete-then-stage) can never report success it did not achieve. A foreign child that is
        // not part of the deletable sidecar set is the surviving-content case.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile(fileSystem.join(generationOneDirectory, "foreign.bin"), ByteArray(4))

        assertFailsWith<LedgerGenerationDirectoryDeleteException> {
            deleteGenerationDirectory(fileSystem, layout, 1)
        }
        assertTrue(fileSystem.hasDirectory(generationOneDirectory))
    }

    @Test
    fun removeLegacyFilesAlsoRemovesARollbackJournalSidecar() {
        // The legacy deletion shares the same discipline: a transient `-journal` beside the legacy
        // main file must not survive it either (the deletion-only superset).
        val legacy = "/data/databases/ledger.db"
        fileSystem.putFile(legacy, sqliteLikeBytes())
        fileSystem.putFile("$legacy-wal", "wal-bytes")
        fileSystem.putFile("$legacy-shm", "shm-bytes")
        fileSystem.putFile("$legacy-journal", "journal-bytes")

        removeLegacyFiles(fileSystem, legacy)

        assertFalse(fileSystem.hasFile(legacy))
        assertFalse(fileSystem.hasFile("$legacy-wal"))
        assertFalse(fileSystem.hasFile("$legacy-shm"))
        assertFalse(fileSystem.hasFile("$legacy-journal"), "the transient legacy journal must be removed too")
    }

    @Test
    fun theLegacyUpgradeCopiesOnlyTheConsistentSidecarSetAndNeverTheTransientJournal() {
        // The COPY list must stay NARROWER than the deletion set: a transient `-journal` must never
        // be copied into a new generation (a stale journal beside a copied database is a corruption
        // hazard). Only `-wal`/`-shm` travel.
        val legacy = "/data/databases/ledger.db"
        fileSystem.putFile(legacy, sqliteLikeBytes())
        fileSystem.putFile("$legacy-wal", "wal-bytes")
        fileSystem.putFile("$legacy-journal", "journal-bytes")

        openStableStorageLedger(fileSystem, layout, legacy, closeGraph = {}) { "graph" }

        assertTrue(fileSystem.hasFile("$generationOneDirectory/ledger.db-wal"))
        assertFalse(
            fileSystem.hasFile("$generationOneDirectory/ledger.db-journal"),
            "the transient journal must never be copied into a new generation",
        )
        assertFalse(fileSystem.hasFile(legacy))
    }

    // ---------------------------------------------------------------- fresh install / upgrade sequence

    @Test
    fun freshInstallIsTheOnlyPathThatAllowsCreateOnOpenAndItPublishesThePointer() {
        val targets = mutableListOf<LedgerOpenTarget>()

        openStableStorageLedger(fileSystem, layout, legacyMainFile = null, closeGraph = {}) { target ->
            targets += target
            fileSystem.putFile(target.mainFile, sqliteLikeBytes())
            "graph"
        }

        assertEquals(listOf(LedgerOpenTarget(generationOneMain, allowCreateOnOpen = true)), targets)
        assertEquals("gen-1", fileSystem.fileBytes(pointer)?.decodeToString())
    }

    @Test
    fun theLegacyUpgradeFollowsTheFrozenNonDestructiveOrder() {
        val legacy = "/data/databases/ledger.db"
        fileSystem.putFile(legacy, sqliteLikeBytes())
        fileSystem.putFile("$legacy-wal", "wal-bytes")
        fileSystem.putFile("$legacy-shm", "shm-bytes")

        var legacyPresentAtOpen = false
        var pointerPublishedAtOpen = false
        openStableStorageLedger(fileSystem, layout, legacy, closeGraph = {}) { target ->
            assertFalse(target.allowCreateOnOpen)
            legacyPresentAtOpen = fileSystem.hasFile(legacy)
            pointerPublishedAtOpen = fileSystem.hasFile(pointer)
            "graph"
        }

        // (c) the authoritative open happens while the legacy set is still in place and the
        // pointer is not yet published.
        assertTrue(legacyPresentAtOpen)
        assertFalse(pointerPublishedAtOpen)
        // (b) the new set was fsynced before the pointer publish, and the pointer directory too.
        val operations = fileSystem.operationsSnapshot()
        val copyWal = operations.indexOfFirst { it == "copy:$legacy-wal->$generationOneDirectory/ledger.db-wal" }
        val copyShm = operations.indexOfFirst { it == "copy:$legacy-shm->$generationOneDirectory/ledger.db-shm" }
        val fsyncMain = operations.indexOfFirst { it == "fsyncFile:$generationOneMain" }
        val publish = operations.indexOfFirst { it == "writeAtomic:$pointer" }
        val fsyncHost = operations.indexOfFirst { it == "fsyncDirectory:/data" }
        val deleteLegacy = operations.indexOfFirst { it == "delete:$legacy" }
        assertTrue(copyWal in 0 until fsyncMain, "sidecars are copied before the new set is fsynced")
        assertTrue(copyShm in 0 until fsyncMain)
        assertTrue(fsyncMain in 0 until publish, "the new generation is fsynced before the pointer publish")
        assertTrue(fsyncHost > publish, "the pointer directory is fsynced as part of the publish")
        assertTrue(deleteLegacy > publish, "the legacy files are removed only after the pointer publish")
        // (e) the legacy set is gone and the new set is complete.
        assertFalse(fileSystem.hasFile(legacy))
        assertFalse(fileSystem.hasFile("$legacy-wal"))
        assertFalse(fileSystem.hasFile("$legacy-shm"))
        assertEquals(sqliteLikeBytes().size, fileSystem.fileBytes(generationOneMain)?.size)
        assertEquals("wal-bytes", fileSystem.fileBytes("$generationOneDirectory/ledger.db-wal")?.decodeToString())
        assertEquals("shm-bytes", fileSystem.fileBytes("$generationOneDirectory/ledger.db-shm")?.decodeToString())
    }

    @Test
    fun aLegacyUpgradeWithoutSidecarsStillStagesAndPublishes() {
        val legacy = "/data/databases/ledger.db"
        fileSystem.putFile(legacy, sqliteLikeBytes())

        openStableStorageLedger(fileSystem, layout, legacy, closeGraph = {}) { "graph" }

        assertTrue(fileSystem.hasFile(generationOneMain))
        assertEquals("gen-1", fileSystem.fileBytes(pointer)?.decodeToString())
        assertFalse(fileSystem.hasFile(legacy))
    }

    @Test
    fun aFailureAtEveryUpgradeStepLeavesTheLegacySetIntact() {
        // Section 6 row "旧路径存在但尚未升级": a crash anywhere in (a)-(e) must leave the old set
        // recoverable; the tests below walk each operation of the sequence. The pointer may only
        // be published at/after `writeAtomic`; before that the old set must still be the only
        // complete copy.
        val legacy = "/data/databases/ledger.db"
        val failurePoints =
            listOf(
                "createDirectories:$generationOneDirectory" to false,
                "copy:$legacy->$generationOneMain" to false,
                "fsyncFile:$generationOneMain" to false,
                "fsyncDirectory:$generationOneDirectory" to false,
                "writeAtomic:$pointer" to false,
                "fsyncDirectory:/data" to true,
                "delete:$legacy" to true,
            )

        for ((failing, pointerPublishedBeforeFailure) in failurePoints) {
            val fs = LedgerFileSystemFake()
            fs.putFile(legacy, sqliteLikeBytes())
            fs.putFile("$legacy-wal", "wal-bytes")
            fs.failOn = failing
            var opened = false

            assertFailsWith<InjectedFileSystemFailure> {
                openStableStorageLedger(fs, ledgerStorageLayout(fs, "/data"), legacy, closeGraph = {}) {
                    opened = true
                    "graph"
                }
            }

            // The legacy set survives every failure point.
            assertTrue(fs.hasFile(legacy), "legacy main file survives failure at $failing")
            assertEquals("wal-bytes", fs.fileBytes("$legacy-wal")?.decodeToString(), "legacy sidecar survives failure at $failing")
            if (pointerPublishedBeforeFailure) {
                assertTrue(opened, "the open preceded the pointer publish at $failing")
            } else {
                assertFalse(fs.hasFile("${ledgerStorageLayout(fs, "/data").activePointerFile}"), "no pointer published when failing at $failing")
            }
        }
    }

    @Test
    fun aLegacyFileThatIsNotAUsableDatabaseFailsClosedWithoutTouchingIt() {
        val legacy = "/data/databases/ledger.db"
        fileSystem.putFile(legacy, "not a sqlite database")

        val rejected =
            assertFailsWith<LedgerStorageRejectedException> {
                openStableStorageLedger(fileSystem, layout, legacy, closeGraph = {}) { "graph" }
            }

        assertEquals(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE, rejected.failure)
        assertTrue(fileSystem.hasFile(legacy))
        assertFalse(fileSystem.hasFile(pointer))
    }

    @Test
    fun anOpenFailureDuringTheLegacyUpgradeFailsClosedAndLeavesTheLegacySet() {
        val legacy = "/data/databases/ledger.db"
        fileSystem.putFile(legacy, sqliteLikeBytes())

        assertFailsWith<IllegalStateException> {
            openStableStorageLedger(fileSystem, layout, legacy, closeGraph = {}) { throw IllegalStateException("injected open failure") }
        }

        assertTrue(fileSystem.hasFile(legacy))
        assertFalse(fileSystem.hasFile(pointer))
    }

    @Test
    fun aRejectedResolutionNeverReachesTheOpenCallback() {
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putFile(pointer, "gen-1")
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, ByteArray(0))
        var opened = false

        val rejected =
            assertFailsWith<LedgerStorageRejectedException> {
                openStableStorageLedger(fileSystem, layout, null, closeGraph = {}) {
                    opened = true
                    "graph"
                }
            }

        assertEquals(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE, rejected.failure)
        assertFalse(opened, "a fail-closed resolution must never call the create-on-open factory")
    }

    @Test
    fun aPresentJournalStopsTheStartupBeforeAnyOpen() {
        fileSystem.putFile(journal, "switched")
        var opened = false

        val rejected =
            assertFailsWith<LedgerStorageRejectedException> {
                openStableStorageLedger(fileSystem, layout, null, closeGraph = {}) {
                    opened = true
                    "graph"
                }
            }

        assertEquals(LedgerStorageFailure.JOURNAL_PRESENT, rejected.failure)
        assertFalse(opened)
        assertEquals("switched", fileSystem.fileBytes(journal)?.decodeToString())
    }

    // ---------------------------------------------------------------- post-open cleanup (fix 3)

    @Test
    fun aFailureAfterASuccessfulOpenClosesTheGraphInsteadOfLeakingIt() {
        // Review Fix 3: the graph is opened before the pointer publish, so a failure in the
        // remaining fallible post-open steps must close it (the driver would otherwise leak).
        // Fault-inject the pointer publish on the fresh-install path.
        val closed = mutableListOf<String>()
        fileSystem.failOn = "writeAtomic:$pointer"

        assertFailsWith<InjectedFileSystemFailure> {
            openStableStorageLedger(fileSystem, layout, null, closeGraph = { graph -> closed += graph }) {
                fileSystem.putFile(it.mainFile, sqliteLikeBytes())
                "graph"
            }
        }

        assertEquals(listOf("graph"), closed, "the opened graph must be closed when the pointer publish fails")
        assertFalse(fileSystem.hasFile(pointer))
    }

    @Test
    fun aFailureAfterASuccessfulLegacyUpgradeOpenClosesTheGraph() {
        // Same guarantee on the upgrade path, with the failure injected at the legacy removal step.
        val legacy = "/data/databases/ledger.db"
        fileSystem.putFile(legacy, sqliteLikeBytes())
        val closed = mutableListOf<String>()
        fileSystem.failOn = "delete:$legacy"

        assertFailsWith<InjectedFileSystemFailure> {
            openStableStorageLedger(fileSystem, layout, legacy, closeGraph = { graph -> closed += graph }) {
                "graph"
            }
        }

        assertEquals(listOf("graph"), closed, "the opened graph must be closed when the legacy removal fails")
        assertTrue(fileSystem.hasFile(legacy), "the legacy set is still intact")
    }

    // ---------------------------------------------------------------- 06.B staging sweep (section 6)

    @Test
    fun theStartupSweepRemovesLeftoverStagingSnapshotAndContainerFiles() {
        // P7-06 06.B (D-177; spec section 6): a process killed mid-export leaves its staging files;
        // the next start must sweep them. Only the frozen staging prefixes are removed.
        fileSystem.putDirectory(layout.backupStagingDirectory)
        fileSystem.putFile(layout.backupSnapshotFile("stale-1"), ByteArray(10))
        fileSystem.putFile(layout.backupContainerFile("stale-1"), ByteArray(20))
        fileSystem.putFile(layout.backupSnapshotFile("stale-2"), ByteArray(30))

        openStableStorageLedger(fileSystem, layout, legacyMainFile = null, closeGraph = {}) {
            fileSystem.putFile(it.mainFile, sqliteLikeBytes())
            "graph"
        }

        assertFalse(fileSystem.hasFile(layout.backupSnapshotFile("stale-1")))
        assertFalse(fileSystem.hasFile(layout.backupContainerFile("stale-1")))
        assertFalse(fileSystem.hasFile(layout.backupSnapshotFile("stale-2")))
    }

    @Test
    fun theStartupSweepNeverTouchesNonStagingFilesOrTheGenerationSet() {
        // The sweep must be scoped: a generation, the pointer and an unrelated file must survive.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile(pointer, "gen-1")
        fileSystem.putDirectory(layout.backupStagingDirectory)
        fileSystem.putFile(fileSystem.join(layout.backupStagingDirectory, "keep-me"), ByteArray(5))
        fileSystem.putFile(layout.backupContainerFile("stale"), ByteArray(5))

        openStableStorageLedger(fileSystem, layout, legacyMainFile = null, closeGraph = {}) { "graph" }

        assertTrue(fileSystem.hasFile(generationOneMain))
        assertTrue(fileSystem.hasFile(pointer))
        assertTrue(fileSystem.hasFile(fileSystem.join(layout.backupStagingDirectory, "keep-me")))
        assertFalse(fileSystem.hasFile(layout.backupContainerFile("stale")))
    }

    @Test
    fun theStartupSweepAlsoRemovesLeftoverRestorePreflightStagingFiles() {
        // P2-5b (P7-06 06.C, D-179 spec section 6.5): the 06.C preflight shares the staging
        // directory and its `restore-*` prefixes must be swept too, so a killed preflight's PLAINTEXT
        // snapshot does not linger. Ablating the restore-prefix clause in `sweepBackupStaging` would
        // leave these files behind and this test would go red.
        fileSystem.putDirectory(generationsDirectory)
        fileSystem.putDirectory(generationOneDirectory)
        fileSystem.putFile(generationOneMain, sqliteLikeBytes())
        fileSystem.putFile(pointer, "gen-1")
        fileSystem.putDirectory(layout.backupStagingDirectory)
        fileSystem.putFile(layout.restoreContainerFile("stale"), ByteArray(20))
        fileSystem.putFile(layout.restoreSnapshotFile("stale"), ByteArray(30))
        fileSystem.putFile(layout.restoreMigratedFile("stale"), ByteArray(40))
        fileSystem.putFile(fileSystem.join(layout.backupStagingDirectory, "keep-me"), ByteArray(5))

        openStableStorageLedger(fileSystem, layout, legacyMainFile = null, closeGraph = {}) { "graph" }

        assertFalse(fileSystem.hasFile(layout.restoreContainerFile("stale")))
        assertFalse(fileSystem.hasFile(layout.restoreSnapshotFile("stale")))
        assertFalse(fileSystem.hasFile(layout.restoreMigratedFile("stale")))
        // The sweep stays scoped: the generation set, the pointer and an unrelated file survive.
        assertTrue(fileSystem.hasFile(generationOneMain))
        assertTrue(fileSystem.hasFile(pointer))
        assertTrue(fileSystem.hasFile(fileSystem.join(layout.backupStagingDirectory, "keep-me")))
    }

    @Test
    fun aSweepListingFailureNeverFailsStartup() {
        // Best effort by contract: cleanup must never turn a good startup into a failure.
        fileSystem.failOn = "listDirectory:${layout.backupStagingDirectory}"

        val graph =
            openStableStorageLedger(fileSystem, layout, legacyMainFile = null, closeGraph = {}) {
                fileSystem.putFile(it.mainFile, sqliteLikeBytes())
                "graph"
            }

        assertEquals("graph", graph)
        assertEquals("gen-1", fileSystem.fileBytes(pointer)?.decodeToString())
    }
}
