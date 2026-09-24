package com.unifiedledger.android

import com.unifiedledger.ui.LedgerOpenTarget
import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.ledgerStorageLayout
import com.unifiedledger.ui.openStableStorageLedger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-06 06.1 (D-176; spec sections 3.2/3.4/4.5/6): the Android stable storage at the platform seam
 * without an emulator — the host/legacy path resolution, the absolute driver target (P0 hotfix
 * defect 1) and the shared sequence's legacy upgrade against the Android layout (the legacy
 * `databases/ledger.db` plus its `-wal`/`-shm` sidecars). The emulator-only driver behaviour stays
 * with the instrumented fail-closed suite (P706-A10).
 */
class AndroidStableStorageTest {
    private fun tempDatabasesDirectory(): Path = Files.createTempDirectory("p7-06-android-storage-")

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun theHostDirectoryIsThePrivateDatabasesDirectoryAndTheLegacyPathIsLedgerDb() {
        val databases = tempDatabasesDirectory()
        try {
            val databasePath = databases.resolve("ledger.db").toFile()
            val (hostDirectory, legacyMainFile) = androidStableStoragePaths(databasePath)

            assertEquals(databases.toFile().absolutePath, hostDirectory)
            assertEquals(databasePath.absolutePath, legacyMainFile)
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun bothGenerationPlansResolveAnAbsoluteMainFileTarget() {
        // P0 hotfix (defect 1): the driver name must be the ABSOLUTE generation main file. A
        // relative name containing a path separator is rejected by the framework
        // (Context.makeFilename throws "contains a path separator"), so both generation plans —
        // the legacy upgrade and the fresh install — must produce an absolute target main file.
        //
        // Scope: this pins that the shared sequence's `target.mainFile` is absolute. It does NOT
        // by itself prove the composition root passes it to the driver; that wiring is covered by
        // the instrumented AndroidAbsoluteDatabasePathInstrumentedTest, which records the name at
        // the production openDriver seam.
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val legacy = File(host, "ledger.db")
            legacy.writeBytes(sqliteHeaderBytes())
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)

            // Legacy-upgrade plan.
            val upgraded = openStableStorageLedger(fileSystem, layout, legacy.absolutePath, closeGraph = {}) { it }
            assertTrue(File(upgraded.mainFile).isAbsolute, "legacy-upgrade target must be absolute")
            assertFalse(upgraded.allowCreateOnOpen)

            // Fresh-install plan (no legacy file): a second host directory keeps the plans apart.
            val freshHost = Files.createTempDirectory("p7-06-android-fresh-").toFile().absolutePath
            val freshLayout = ledgerStorageLayout(fileSystem, freshHost)
            val fresh = openStableStorageLedger(fileSystem, freshLayout, null, closeGraph = {}) { it }
            assertTrue(File(fresh.mainFile).isAbsolute, "fresh-install target must be absolute")
            assertTrue(fresh.allowCreateOnOpen)
            deleteRecursively(File(freshHost).toPath())
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun aRelativeDriverNameIsRejectedAtTheAppSeam() {
        // P0 hotfix (defect 1): the exact seam App.kt uses must fail loudly if a relative name
        // (the defect shape: a path separator in a name the framework would treat as a bare
        // filename) ever reaches it again, instead of deferring to the framework's late throw.
        val rejected =
            assertFailsWith<IllegalArgumentException> {
                androidGenerationDriverName("ledger-generations/gen-1/ledger.db")
            }
        assertTrue(rejected.message.orEmpty().contains("must be absolute"))
    }

    @Test
    fun theLegacyDatabaseIsUpgradedNonDestructivelyWithItsSidecars() {
        // Spec section 3.2 rule 1 / section 6: the first generation-aware start must migrate the
        // existing product database, never treat it as an empty install.
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val legacy = File(host, "ledger.db")
            legacy.writeBytes(sqliteHeaderBytes())
            File(host, "ledger.db-wal").writeText("wal-bytes")
            File(host, "ledger.db-shm").writeText("shm-bytes")
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)

            val target = openStableStorageLedger(fileSystem, layout, legacy.absolutePath, closeGraph = {}) { it }

            val generationMain = File(layout.mainFile(layout.generationDirectory(1)))
            assertTrue(generationMain.exists())
            assertEquals("wal-bytes", File(layout.generationDirectory(1), "ledger.db-wal").readText())
            assertEquals("shm-bytes", File(layout.generationDirectory(1), "ledger.db-shm").readText())
            assertEquals(layout.mainFile(layout.generationDirectory(1)), target.mainFile)
            assertFalse(target.allowCreateOnOpen, "a non-fresh path never uses create-on-open")
            assertFalse(legacy.exists(), "the legacy files are removed only after the pointer publish")
            assertEquals("gen-1", File(layout.activePointerFile).readText())
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun aPreSeededActiveGenerationReachesTheOpenCallbackWithoutCreateOnOpen() {
        // Supports the instrumented concurrency test's non-vacuity (MUST FIX 2): a redirected host
        // pre-seeded with ledger-generations/gen-1/ledger.db + an active-generation pointer
        // resolves OpenGeneration and reaches the open callback, so two concurrent opens both get
        // as far as the driver. Without the pointer the same host fails closed POINTER_MISSING
        // BEFORE the callback — the shortcut that made the concurrency test vacuous.
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)
            val generationDirectory = File(layout.generationDirectory(1))
            generationDirectory.mkdirs()
            File(layout.mainFile(layout.generationDirectory(1))).writeBytes(sqliteHeaderBytes())

            // No pointer yet: the callback must NOT run.
            var reachedWithoutPointer = false
            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    openStableStorageLedger(fileSystem, layout, null, closeGraph = {}) {
                        reachedWithoutPointer = true
                        it
                    }
                }
            assertEquals(LedgerStorageFailure.POINTER_MISSING, rejected.failure)
            assertFalse(reachedWithoutPointer)

            // With the pointer: the callback runs and the target is non-fresh.
            File(layout.activePointerFile).writeText("gen-1")
            var reachedWithPointer = false
            val target =
                openStableStorageLedger(fileSystem, layout, null, closeGraph = {}) {
                    reachedWithPointer = true
                    it
                }
            assertTrue(reachedWithPointer)
            assertFalse(target.allowCreateOnOpen)
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun anAlreadyUpgradedInstallIsSelectedThroughThePointerOnTheNextStart() {
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val legacy = File(host, "ledger.db")
            legacy.writeBytes(sqliteHeaderBytes())
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)
            openStableStorageLedger(fileSystem, layout, legacy.absolutePath, closeGraph = {}) { it }

            val second = openStableStorageLedger(fileSystem, layout, legacy.absolutePath, closeGraph = {}) { it }

            assertEquals(layout.mainFile(layout.generationDirectory(1)), second.mainFile)
            assertEquals(false, second.allowCreateOnOpen)
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun aJournalLeftByASwitchFailsClosed() {
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)
            File(layout.switchJournalFile).writeText("prepared")

            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    openStableStorageLedger(fileSystem, layout, null, closeGraph = {}) { it }
                }

            assertEquals(LedgerStorageFailure.JOURNAL_PRESENT, rejected.failure)
            assertEquals("prepared", File(layout.switchJournalFile).readText())
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun aCorruptGenerationMainFileFailsClosedAndIsPreservedByteForByte() {
        // The FOUND-001 shape in the generation context: the original bytes are never replaced by
        // an empty ledger.
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)
            val generationDirectory = File(layout.generationDirectory(1))
            generationDirectory.mkdirs()
            val mainFile = File(layout.mainFile(layout.generationDirectory(1)))
            val corrupt = "not a SQLite database".encodeToByteArray()
            mainFile.writeBytes(corrupt)
            File(layout.activePointerFile).writeText("gen-1")

            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    openStableStorageLedger(fileSystem, layout, null, closeGraph = {}) { it }
                }

            assertEquals(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE, rejected.failure)
            assertTrue(mainFile.readBytes().contentEquals(corrupt))
        } finally {
            deleteRecursively(databases)
        }
    }

    private fun sqliteHeaderBytes(): ByteArray = "SQLite format 3\u0000".encodeToByteArray() + ByteArray(1024)

    // ---------------------------------------------------------------- non-fresh create-on-open guard (fix 7)

    @Test
    fun aNonFreshTargetWithAMissingMainFileIsRejectedBeforeAnyDriverOpen() {
        // Review Fix 7: the AndroidSqliteDriver creates on open, so the composition root must
        // reject a non-fresh target whose main file is missing/invalid BEFORE the driver runs.
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)
            val missingMain = layout.mainFile(layout.generationDirectory(1))

            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    requireUsableNonFreshTarget(fileSystem, LedgerOpenTarget(missingMain, allowCreateOnOpen = false))
                }

            assertEquals(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE, rejected.failure)
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun aNonFreshTargetWithAZeroLengthMainFileIsRejected() {
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)
            val generationDirectory = File(layout.generationDirectory(1))
            generationDirectory.mkdirs()
            File(layout.mainFile(layout.generationDirectory(1))).writeBytes(ByteArray(0))

            assertFailsWith<LedgerStorageRejectedException> {
                requireUsableNonFreshTarget(
                    fileSystem,
                    LedgerOpenTarget(layout.mainFile(layout.generationDirectory(1)), allowCreateOnOpen = false),
                )
            }
        } finally {
            deleteRecursively(databases)
        }
    }

    @Test
    fun aFreshTargetIsAllowedAndAUsableNonFreshTargetPasses() {
        val databases = tempDatabasesDirectory()
        try {
            val host = databases.toFile().absolutePath
            val fileSystem = DesktopStyleTestFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host)
            val generationDirectory = File(layout.generationDirectory(1))
            generationDirectory.mkdirs()
            val main = File(layout.mainFile(layout.generationDirectory(1)))
            main.writeBytes(sqliteHeaderBytes())

            // A fresh-install target is always allowed (create-on-open is the point).
            requireUsableNonFreshTarget(fileSystem, LedgerOpenTarget(main.absolutePath, allowCreateOnOpen = true))
            // A usable non-fresh target passes the guard.
            requireUsableNonFreshTarget(fileSystem, LedgerOpenTarget(main.absolutePath, allowCreateOnOpen = false))
        } finally {
            deleteRecursively(databases)
        }
    }
}
