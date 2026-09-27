package com.unifiedledger.desktop

import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.ledgerStorageLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-06 06.1 (D-176; spec sections 3.3/3.4/4.5/5.1 and 6): the desktop stable storage over the
 * real filesystem adapter — the per-OS host directory resolution, the product open through the
 * generation directory and atomic pointer, the fail-closed prohibitions and the normal-reopen
 * regression (P706-A06/A10).
 */
class DesktopStableStorageTest {
    private fun tempHost(): Path = Files.createTempDirectory("p7-06-desktop-storage-")

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun theProductHostDirectoryIsResolvedFromTheRuntimeEnvironmentOnly() {
        // Synthetic roots only (no real user paths): the resolver reads the environment/system
        // properties and appends the fixed product subdirectory.
        val windowsRoot = File("synthetic-root", "local-appdata").path
        val windows = resolveDesktopHostDirectory(environment = { if (it == "LOCALAPPDATA") windowsRoot else null }, osName = "Windows 11", userHome = "")
        assertEquals(File(windowsRoot, "UnifiedLedger").path, windows)

        val linuxRoot = File("synthetic-root", "xdg-data").path
        val linux = resolveDesktopHostDirectory(environment = { if (it == "XDG_DATA_HOME") linuxRoot else null }, osName = "Linux", userHome = "")
        assertEquals(File(linuxRoot, "UnifiedLedger").path, linux)

        val macHome = File("synthetic-root", "mac-home").path
        val mac = resolveDesktopHostDirectory(environment = { null }, osName = "Mac OS X", userHome = macHome)
        assertEquals(File(File(macHome, "Library/Application Support").path, "UnifiedLedger").path, mac)
    }

    @Test
    fun anUnresolvableHostDirectoryFailsClosedInsteadOfFallingBackToTemp() {
        assertFailsWith<IllegalStateException> {
            resolveDesktopHostDirectory(environment = { null }, osName = "Windows 11", userHome = "")
        }
    }

    @Test
    fun theProductOpenCreatesAGenerationAndPublishesThePointerOnFirstStart() {
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())

            val graph = openStableStorageDesktopLedger(fileSystem, layout)

            assertEquals(true, Files.exists(Path.of(layout.activePointerFile)))
            assertEquals("gen-1", Files.readString(Path.of(layout.activePointerFile)))
            assertEquals(true, Files.exists(Path.of(layout.mainFile(layout.generationDirectory(1)))))
            graph.close()
        } finally {
            deleteRecursively(host)
        }
    }

    @Test
    fun aSecondProductOpenSelectsTheSameGenerationAndSurvivesRestart() {
        // P706-A06: a normal reopen of the same stable location must work (the desktop root no
        // longer creates a fresh temp directory per start).
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())
            val first = openStableStorageDesktopLedger(fileSystem, layout)
            first.close()

            val second = openStableStorageDesktopLedger(fileSystem, layout)

            assertEquals("gen-1", Files.readString(Path.of(layout.activePointerFile)))
            second.close()
        } finally {
            deleteRecursively(host)
        }
    }

    @Test
    fun aZeroLengthActiveMainFileFailsClosedWithoutCreatingAnEmptyLedger() {
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())
            val generationDirectory = File(layout.generationDirectory(1))
            generationDirectory.mkdirs()
            // The pointer is durable but the generation is only partially on disk.
            File(layout.mainFile(layout.generationDirectory(1))).writeBytes(ByteArray(0))
            File(layout.activePointerFile).writeText("gen-1")

            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    openStableStorageDesktopLedger(fileSystem, layout)
                }

            assertEquals(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE, rejected.failure)
            assertEquals(0L, File(layout.mainFile(layout.generationDirectory(1))).length())
        } finally {
            deleteRecursively(host)
        }
    }

    @Test
    fun aPresentJournalFailsClosedAndIsNotRemoved() {
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())
            File(layout.switchJournalFile).writeText("switched")

            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    openStableStorageDesktopLedger(fileSystem, layout)
                }

            assertEquals(LedgerStorageFailure.JOURNAL_PRESENT, rejected.failure)
            assertEquals("switched", File(layout.switchJournalFile).readText())
        } finally {
            deleteRecursively(host)
        }
    }

    @Test
    fun aGenerationDirectoryWithoutAPointerFailsClosedInsteadOfFreshInstalling() {
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())
            File(layout.generationsDirectory).mkdirs()

            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    openStableStorageDesktopLedger(fileSystem, layout)
                }

            assertEquals(LedgerStorageFailure.POINTER_MISSING, rejected.failure)
            assertFalse(Files.exists(Path.of(layout.mainFile(layout.generationDirectory(1)))))
        } finally {
            deleteRecursively(host)
        }
    }

    @Test
    fun theDemoTestTempPathIsStillUsableAndIsolatedFromTheProductLocation() {
        // Spec section 3.3: the temp-directory entry is retained for tests/demo but is no longer
        // the product path; the demo URL still opens an isolated ledger.
        val url = createDemoDatabaseUrl()
        try {
            val graph = openDesktopLedger(url)
            graph.close()
        } finally {
            Files.deleteIfExists(Path.of(url.removePrefix("jdbc:sqlite:")))
        }
    }

    @Test
    fun theExplicitReopenOpensTheNamedGenerationWithoutConsumingItsOwnSwitchedJournal() {
        // P7-06 06.D (D-182; spec section 3 step 8 (with 3.8)): the desktop confirm switch's step-8 reopen
        // opens the named generation DIRECTLY. The startup route would run the journal gate on the
        // flow's own still-present `switched` journal, republish the old pointer and delete the new
        // generation — the switch would roll itself back while reporting success. This pins the
        // bypass on the real filesystem, with a real SQLite generation opened.
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())
            // A real current-schema generation 2, published, with the switched journal present.
            // Materialize it through the product open so the schema/bootstrap is genuine, then
            // publish it and stage the journal exactly as the confirm flow leaves them.
            val generationTwoDirectory = layout.generationDirectory(2)
            Files.createDirectories(Path.of(generationTwoDirectory))
            val generationTwoMain = layout.mainFile(generationTwoDirectory)
            openDesktopLedger("jdbc:sqlite:$generationTwoMain").close()
            File(layout.activePointerFile).writeText("gen-2")
            File(layout.switchJournalFile).writeText("unified-ledger switch journal v1\nstage=switched\nold=gen-1\nnew=gen-2")

            val graph = openExplicitDesktopLedger(fileSystem, layout, 2)

            // The generation really opened and the journal gate did NOT run: the pointer is
            // untouched, the journal survives and the new generation directory is intact.
            assertEquals("gen-2", File(layout.activePointerFile).readText())
            assertTrue(File(layout.switchJournalFile).exists(), "the explicit route must not consume its own journal")
            assertTrue(File(generationTwoMain).exists())
            graph.close()
        } finally {
            deleteRecursively(host)
        }
    }

    @Test
    fun theExplicitReopenFailsClosedOnAnUnusableNamedGeneration() {
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())
            val generationOneDirectory = layout.generationDirectory(1)
            Files.createDirectories(Path.of(generationOneDirectory))
            File(layout.mainFile(generationOneDirectory)).writeBytes(ByteArray(0))

            val rejected =
                assertFailsWith<LedgerStorageRejectedException> {
                    openExplicitDesktopLedger(fileSystem, layout, 1)
                }

            assertEquals(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE, rejected.failure)
        } finally {
            deleteRecursively(host)
        }
    }

    @Test
    fun theAtomicPointerPublishReplacesThePreviousPointer() {
        val host = tempHost()
        try {
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.toString())
            fileSystem.writeAtomic(layout.activePointerFile, "gen-1".encodeToByteArray())
            fileSystem.writeAtomic(layout.activePointerFile, "gen-2".encodeToByteArray())

            assertEquals("gen-2", File(layout.activePointerFile).readText())
            // No temp residue is left behind by the atomic replace.
            assertFalse(Files.exists(Path.of(layout.activePointerFile + ".tmp")))
        } finally {
            deleteRecursively(host)
        }
    }
}
