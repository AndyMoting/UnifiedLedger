package com.unifiedledger.desktop

import com.unifiedledger.ui.BackupRestoreSwitchResult
import com.unifiedledger.ui.P503StartupState
import com.unifiedledger.ui.RestorePreflightResult
import com.unifiedledger.ui.ledgerStorageLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * P706-A09 cross-device leg: a container **exported on the Android device** is restored by the REAL
 * desktop composition root (DesktopStartupController + DesktopRestoreWiring: the real preflight and
 * confirm use cases, the real crypto primitives, the real isolated-database port, the real bounded
 * Swing source port with only the file chooser replaced by the fixture path).
 *
 * The container is machine-local evidence (`local/artifacts/p706-a09-crossdevice/`), so the case
 * takes its path from `-Dul.crossdevice.container=` and SKIPS when the property is absent: the
 * fixture must never be committed, and a silent pass without it would be a fake reading.
 *
 * What the assertions pin:
 * 1. the preflight authenticates the Android container and yields a preview (v2 container, the
 *    same chunked AEAD the device wrote);
 * 2. the explicit confirm COMMITS the switch;
 * 3. the resulting generation carries the device's content: 50,002 transactions, a 60-tag
 *    catalog, and `PRAGMA quick_check = ok`.
 */
class DesktopCrossDeviceRestoreTest {
    private fun containerPath(): Path? =
        (System.getenv(CONTAINER_ENV) ?: System.getProperty(CONTAINER_PROPERTY))
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf { it.isRegularFile() }

    @Test
    fun anAndroidExportedContainerRestoresThroughTheDesktopCompositionRoot(): Unit =
        runBlocking {
            val container = containerPath()
            Assume.assumeTrue(
                "set $CONTAINER_ENV (or -D$CONTAINER_PROPERTY) to the android .ulbk to run the cross-device leg " +
                    "(the fixture is machine-local and never committed)",
                container != null,
            )
            val host = Files.createTempDirectory("p706a09-crossdevice-")
            try {
                val fileSystem = DesktopLedgerFileSystem()
                val layout = ledgerStorageLayout(fileSystem, host.absolutePathString())
                // The real desktop host: a published pointer plus the production opener, so each
                // reopen (startup and the confirm flow's step-8 reopen) opens a FRESH graph over
                // whatever generation the pointer names — exactly the composition root's lambda.
                Files.createDirectories(Path.of(layout.generationDirectory(1)))
                Files.createDirectories(Path.of(layout.activePointerFile).parent)
                Files.writeString(Path.of(layout.activePointerFile), "gen-1")
                val controller =
                    DesktopStartupController(
                        openDatabase = {
                            val pointer = Files.readString(Path.of(layout.activePointerFile)).trim()
                            val generation = pointer.removePrefix("gen-").toInt()
                            openDesktopLedger("jdbc:sqlite:${layout.mainFile(layout.generationDirectory(generation))}")
                        },
                        restoreWiring =
                            DesktopRestoreWiring(
                                fileSystem = fileSystem,
                                layout = layout,
                                sourcePort =
                                    DesktopBackupSourcePort(
                                        showOpenFileChooser = { _, _ -> File(container!!.absolutePathString()) },
                                    ),
                                isolatedDatabase = DesktopRestoreIsolatedDatabasePort(),
                            ),
                        // The production step-8 reopen: the confirm switch opens the named
                        // generation DIRECTLY, because its own `switched` journal is still on disk
                        // and the startup pointer path would consume it and roll back.
                        openExplicitGeneration = { generation -> openExplicitDesktopLedger(fileSystem, layout, generation) },
                    )
                controller.start()
                assertEquals(P503StartupState.Ready, controller.state)
                val ledger = assertNotNull(controller.ledger)
                val wiring = assertNotNull(ledger.restoreWiring)

                val launch =
                    assertNotNull(
                        ledger.restorePreflightLaunch(EXPORT_PASSWORD),
                        "the Ready owner must resolve a preflight request for the current generation",
                    )

                val preflight = assertIs<RestorePreflightResult.PreviewReady>(wiring.preflight.preflight(launch.request))

                val switch = assertIs<BackupRestoreSwitchResult.Committed>(wiring.confirm.confirm(preflight.token))
                assertNotNull(switch.landingGeneration)

                // Read the restored generation straight from disk: the switch is durable, and the
                // content must be the device's own.
                val pointer = File(layout.activePointerFile).readText().trim()
                val mainFile = layout.mainFile(layout.generationDirectory(pointer.removePrefix("gen-").toInt()))
                java.sql.DriverManager.getConnection("jdbc:sqlite:$mainFile").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM ledger_transaction WHERE ledger_id = '$LEDGER_ID'").use { rows ->
                            rows.next()
                            assertEquals(
                                EXPECTED_TRANSACTIONS,
                                rows.getInt(1),
                                "the restored generation must carry the device's 50,002 transactions",
                            )
                        }
                        statement.executeQuery("SELECT count(*) FROM catalog_tag WHERE ledger_id = '$LEDGER_ID'").use { rows ->
                            rows.next()
                            assertEquals(EXPECTED_TAGS, rows.getInt(1), "the restored generation must carry the device's 60-tag catalog")
                        }
                        statement.executeQuery("PRAGMA quick_check").use { rows ->
                            rows.next()
                            assertEquals("ok", rows.getString(1), "the restored ledger must be structurally clean")
                        }
                    }
                }
            } finally {
                host.toFile().deleteRecursively()
            }
        }

    private companion object {
        const val CONTAINER_ENV = "UL_CROSSDEVICE_CONTAINER"
        const val CONTAINER_PROPERTY = "ul.crossdevice.container"

        /** The password the device export used (see the artifacts README). */
        const val EXPORT_PASSWORD = "CrossDevice1"

        const val LEDGER_ID = "ledger-local-test"
        const val EXPECTED_TRANSACTIONS = 50_002
        const val EXPECTED_TAGS = 60
    }
}
