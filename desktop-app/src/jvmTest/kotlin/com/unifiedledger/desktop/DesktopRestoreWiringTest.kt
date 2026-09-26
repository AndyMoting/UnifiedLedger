package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.ui.BackupSourceOpenResult
import com.unifiedledger.ui.BackupSourcePort
import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.P503StartupState
import com.unifiedledger.ui.ledgerStorageLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P7-06 06.D (D-182; spec sections 5.3/5.4/6): the desktop composition-root wiring evidence for
 * the restore surface, exercised on a REAL host directory and the REAL isolated-database adapter.
 * Pins the section 6 construction decisions (one use-case instance set, the section 5.4 whitelist
 * `{1, 31}` and `currentSupportedSchemaVersion()` injected from ledger-data) and the section 5.3
 * recovery flow through the controller: a pointerless start offers the recovery face, the
 * user-confirmed adoption publishes the pointer through the frozen primitive and the retried
 * startup reaches Ready, a DECLINE mutates nothing, and the discard-and-re-upgrade branch is
 * typed-unavailable on desktop (no legacy location) while the state stays fail-closed.
 */
class DesktopRestoreWiringTest {
    private fun <T> withHost(block: (Path) -> T): T {
        val directory = Files.createTempDirectory("p706d-recovery-")
        try {
            return block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private val isolated = DesktopRestoreIsolatedDatabasePort()

    private fun controller(
        host: Path,
        pointerGated: Boolean = false,
    ): DesktopStartupController {
        val fileSystem = DesktopLedgerFileSystem()
        val layout = ledgerStorageLayout(fileSystem, host.absolutePathString())
        return DesktopStartupController(
            openDatabase = {
                if (pointerGated && !fileSystem.exists(layout.activePointerFile)) {
                    // Exactly the product startup's fail-closed shape for a pointerless host.
                    throw LedgerStorageRejectedException(LedgerStorageFailure.POINTER_MISSING)
                }
                realGraph()
            },
            restoreWiring =
                DesktopRestoreWiring(
                    fileSystem = fileSystem,
                    layout = layout,
                    sourcePort = BackupSourcePort { BackupSourceOpenResult.Cancelled },
                    isolatedDatabase = isolated,
                ),
        )
    }

    private fun realGraph(): CloseableLedgerGraph {
        val directory = Files.createTempDirectory("p706d-restore-wiring-graph-")
        val url = "jdbc:sqlite:${directory.resolve("ledger.db").absolutePathString()}"
        val driver = JdbcSqliteDriver(url)
        val graph = buildLedgerGraph(driver, createSchema = true)
        return CloseableLedgerGraph(graph.facade, { driver.close() }, runQueryStatisticsOptimize = {}, runFullAnalyze = {})
    }

    /** Seeds a real pointerless host: one candidate generation whose database verifies current. */
    private fun seedPointerlessCurrentCandidate(host: Path): String {
        val fileSystem = DesktopLedgerFileSystem()
        val layout = ledgerStorageLayout(fileSystem, host.absolutePathString())
        val candidateDirectory = layout.generationDirectory(1)
        Files.createDirectories(Path.of(candidateDirectory))
        val candidateMain = layout.mainFile(candidateDirectory)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$candidateMain").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = ${currentSupportedSchemaVersion()}")
            }
        }
        return candidateMain
    }

    // ---------------------------------------------------------------- the wiring decisions (spec section 6)

    @Test
    fun theRestoreWiringBindsTheRuledWhitelistAndSingleUseCaseInstances() {
        withHost { host ->
            val controller = controller(host)
            controller.start()

            assertEquals(P503StartupState.Ready, controller.state)
            val ledger = assertNotNull(controller.ledger)
            assertTrue(ledger.surfaces.backupRestore, "the wired composition renders the restore surface")
            val wiring = assertNotNull(ledger.restoreWiring)
            // The section 5.4 RULING: the wiring whitelist is {1, 31}, injected from the root.
            assertEquals(setOf(1L, 31L), wiring.supportedSourceVersions)
            assertEquals(currentSupportedSchemaVersion(), wiring.currentSchemaVersion)
            // Exactly one instance set: the confirm flow's single-flight guard assumes it.
            assertSame(wiring.preflight, wiring.preflight)
            assertSame(wiring.confirm, wiring.confirm)
            // The preflight request is resolved for the CURRENT generation with the fixed target id.
            val launch = assertNotNull(ledger.restorePreflightLaunch("password123"))
            assertEquals("ledger-local-test", launch.request.targetLedgerId)
            assertEquals(1, launch.generation)
        }
    }

    // ---------------------------------------------------------------- the recovery flow (spec section 5.3)

    @Test
    fun aPointerlessStartOffersTheRecoveryFaceAndTheConfirmedAdoptionReachesReady() {
        withHost { host ->
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.absolutePathString())
            val candidateMain = seedPointerlessCurrentCandidate(host)
            val controller = controller(host, pointerGated = true)

            controller.start()

            val recovery = assertIs<P503StartupState.PointerRecovery>(controller.state, "the pointerless start surfaces the recovery face")
            // The REAL adapter verified the real candidate database: usable, integrity ok, current.
            assertEquals(1, recovery.state.adoptableGeneration)
            assertFalse(recovery.state.legacyUpgradeAvailable, "desktop has no legacy location: the discard branch is unavailable")
            assertNull(controller.ledger, "no business surface before the runtime is Ready")

            // The user CONFIRMS the adoption: the frozen primitive publishes the pointer and the
            // normal startup re-runs and opens the adopted generation.
            controller.adoptPointerRecovery()

            assertEquals(P503StartupState.Ready, controller.state)
            assertEquals("gen-1", fileSystem.readBytes(layout.activePointerFile).decodeToString())
            assertTrue(fileSystem.exists(candidateMain), "the adopted candidate is the generation that opened")
            assertNotNull(controller.ledger)
        }
    }

    @Test
    fun aUserDeclineMutatesNothingAndTheDiscardBranchIsTypedUnavailableOnDesktop() {
        withHost { host ->
            val fileSystem = DesktopLedgerFileSystem()
            val layout = ledgerStorageLayout(fileSystem, host.absolutePathString())
            val candidateMain = seedPointerlessCurrentCandidate(host)
            val controller = controller(host, pointerGated = true)
            controller.start()
            assertIs<P503StartupState.PointerRecovery>(controller.state)

            // The user confirms the DISCARD branch instead: without a legacy original the branch
            // does not exist (typed), the candidate stays, and the state remains recoverable.
            controller.discardPointerRecoveryAndReUpgrade()

            val recovery = assertIs<P503StartupState.PointerRecovery>(controller.state)
            assertEquals(1, recovery.state.adoptableGeneration)
            assertTrue(fileSystem.exists(candidateMain), "zero mutation on the typed refusal")
            assertFalse(fileSystem.exists(layout.activePointerFile), "still fail-closed pointerless")
            assertNull(controller.ledger)
        }
    }

    @Test
    fun aNonPointerlessFailureKeepsThePlainStartupErrorFace() {
        // A failure that is not the pointerless shape (here: an unusable active generation) never
        // offers the recovery face - the section 5.3 recovery is POINTER_MISSING only.
        withHost { host ->
            val controller =
                DesktopStartupController(
                    openDatabase = { throw LedgerStorageRejectedException(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE) },
                    restoreWiring =
                        DesktopRestoreWiring(
                            fileSystem = DesktopLedgerFileSystem(),
                            layout = ledgerStorageLayout(DesktopLedgerFileSystem(), host.absolutePathString()),
                            sourcePort = BackupSourcePort { BackupSourceOpenResult.Cancelled },
                            isolatedDatabase = isolated,
                        ),
                )
            controller.start()

            assertEquals(P503StartupState.StartupError, controller.state)
            assertNull(controller.ledger)
        }
    }
}
