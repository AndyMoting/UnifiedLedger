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
}
