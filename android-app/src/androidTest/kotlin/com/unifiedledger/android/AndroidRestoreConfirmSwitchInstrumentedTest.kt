package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.backup.BackupContainerSink
import com.unifiedledger.application.backup.BackupPlaintextReader
import com.unifiedledger.application.backup.BackupPlaintextSource
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.application.backup.writeBackupContainer
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.data.defaultCatalogSeed
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.BackupRestoreSwitchResult
import com.unifiedledger.ui.BackupSourcePort
import com.unifiedledger.ui.ConfirmBackupRestoreUseCase
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerRuntimeOwner
import com.unifiedledger.ui.LedgerRuntimeState
import com.unifiedledger.ui.LedgerStartupResult
import com.unifiedledger.ui.RestorePreflightRequest
import com.unifiedledger.ui.RestorePreflightResult
import com.unifiedledger.ui.RestorePreflightUseCase
import com.unifiedledger.ui.ledgerStorageLayout
import com.unifiedledger.ui.openGenerationForSelection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md` section 7,
 * the device-instrumented row): the CONFIRM SWITCH END TO END on a real device. The spec's device
 * row requires on-device coverage of the confirm switch end to end plus the recovery faces; the
 * recovery faces live in `AndroidRestoreRecoveryInstrumentedTest`, and this file is the
 * confirm-switch half. It was deliberately withdrawn while the P1 was open — the P1 was that step
 * 8's reopen re-entered the startup switch-journal gate and consumed the flow's OWN live
 * `switched` journal, rolling the switch back while still reporting `Committed`. With the P1 fixed
 * (step 8 opens the just-published generation through `GenerationSelection.Explicit`, i.e. the
 * production `openAndroidExplicitGeneration`), the switch must now be observable as committed ON
 * DISK.
 *
 * What runs for real here (nothing shape-only or faked):
 *
 * - the CURRENT generation `gen-1` is a REAL product ledger database created by the product factory
 *   [createAndroidLedgerDatabase] (the same call `App.kt` wires) with its catalog bootstrapped from
 *   the product seed, so the production startup below opens a genuine graph over it;
 * - the restore payload is a SECOND real product database (the same factory, so the same generated
 *   schema at `currentSupportedSchemaVersion()` with the same bootstrapped catalog), wrapped in a
 *   REAL authenticated container by the product writer `writeBackupContainer` with the real JCE
 *   primitives that the composition root injects;
 * - that container is delivered through the REAL [AndroidBackupSourcePort] into the REAL
 *   [RestorePreflightUseCase] over the REAL [AndroidLedgerFileSystem] and the REAL
 *   [AndroidRestoreIsolatedDatabasePort] (a real framework SQLite open + `PRAGMA integrity_check` +
 *   identity sweep + domain validation + owner counts produce the preview and the opaque token);
 * - the confirm runs the REAL [ConfirmBackupRestoreUseCase] over an owner wired EXACTLY like
 *   `App.kt`'s `AndroidStartupController`: [openGenerationForSelection] dispatching `ActivePointer`
 *   to the production [openAndroidStableStorageLedger] (the full startup sequence, INCLUDING the
 *   switch-journal gate) and `Explicit` to the production [openAndroidExplicitGeneration] (the
 *   direct, gate-free opener). Both routes build REAL graphs (the product driver factory and the
 *   product graph builder run).
 *
 * THE P1 GUARD (why the on-disk assertions are load-bearing, and not the `Committed` assertion): if
 * step 8 reverted to `GenerationSelection.ActivePointer`, the pointer route would run
 * `resolveLedgerStorage` on this flow's own live `switched` journal, republish `gen-1` and DELETE
 * the staged `gen-2` directory — and would then still open `gen-1` successfully, so the flow would
 * return `Committed` anyway. Only the on-disk assertions (pointer = `gen-2`, the `gen-2` directory
 * and main file survive, the journal is gone, the reported generation is the post-reopen one) turn
 * that failure red. This suite is the device-level counterpart of the JVM wiring test
 * `AndroidRestoreWiringTest.theCompositionRootOwnerReopensThePublishedGenerationExplicitlyAndCommits`
 * and of `ConfirmBackupRestoreUseCaseTest`.
 *
 * SCOPE DISCLOSURE (what this does not cover):
 *
 * - the restore plaintext is built by the product factory at a second path rather than by the 06.B
 *   export's `VACUUM INTO` snapshot primitive: the Android `VACUUM INTO`-through-the-SQLDelight-
 *   binder path is a separately registered open item (D-177 spec, D-180 5(d)) and this suite must
 *   not depend on it. The payload is a real product-schema, real-`user_version` database with the
 *   real catalog, which is what the preflight's strict checks read;
 * - the SAF picker UX and the `OpenableColumns.SIZE` query are not re-driven here — they are covered
 *   by `AndroidRestoreSourcePortInstrumentedTest` and
 *   `AndroidRestoreDocumentSizeInstrumentedTest`; the real port is injected with a synchronous
 *   poster and an in-memory stream over the real container bytes. The device's real provider
 *   size-metadata distribution (spec section 8, new item 6) stays open.
 *
 * Data safety: the production ledger is never touched. [redirectedContext] resolves RELATIVE
 * database names under a fresh per-test directory in the target's cache dir, and passes ABSOLUTE
 * names through unchanged (the framework's own absolute branch, which is how a generation file is
 * opened — every absolute path this test builds is derived from the redirected host directory).
 * [assertIsolatedFromProduction] hard-fails on any file that is not under the cache dir or that
 * resolves into the production `databases/` tree. Runs only as `connectedDebugAndroidTest`; no
 * Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRestoreConfirmSwitchInstrumentedTest {
    private val createdDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        for (directory in createdDirectories) {
            directory.deleteRecursively()
        }
        createdDirectories.clear()
    }

    @Test
    fun theConfirmedSwitchCommitsOnDeviceAndTheNewGenerationSurvivesOnDisk() {
        val context = redirectedContext()
        val fileSystem = AndroidLedgerFileSystem()
        val hostDirectory = androidStableStoragePaths(context.getDatabasePath(LEGACY_DATABASE_NAME)).first
        val layout = ledgerStorageLayout(fileSystem, hostDirectory)

        // The active disk generation: a REAL product ledger at gen-1, catalog bootstrapped, opened
        // by the production startup below through the pointer.
        val generation1Directory = layout.generationDirectory(1)
        fileSystem.createDirectories(generation1Directory)
        val generation1Main = layout.mainFile(generation1Directory)
        seedProductLedger(context, generation1Main)
        assertIsolatedFromProduction(File(generation1Main))
        fileSystem.writeAtomic(layout.activePointerFile, "gen-1".encodeToByteArray())

        // The restore payload: a REAL product ledger database with the same bootstrapped catalog,
        // wrapped in a REAL authenticated container.
        val payloadFile = File(hostDirectory, "p706d-confirm-payload.db")
        seedProductLedger(context, payloadFile.absolutePath)
        assertIsolatedFromProduction(payloadFile)
        val container = containerAround(payloadFile, PASSWORD)

        // The owner wired exactly like App.kt's AndroidStartupController: ActivePointer runs the
        // full production startup (journal gate included), Explicit runs the direct opener.
        val importPort =
            AndroidImportFilePickPort<Uri>(
                launchOpenDocument = {},
                resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
                openInputStream = { null },
                onResult = {},
            )
        val importChannel = ImportFilePickResultChannel()
        val owner =
            LedgerRuntimeOwner<CloseableLedgerGraph>(
                openGeneration = { selection ->
                    openGenerationForSelection(
                        selection = selection,
                        openActivePointer = { openAndroidStableStorageLedger(context, importPort, importChannel) },
                        openExplicit = { generation ->
                            openAndroidExplicitGeneration(context, importPort, importChannel, generation)
                        },
                    )
                },
                closeGraph = { graph -> graph.close() },
                facadeOf = { graph -> graph.facade },
            )
        assertEquals(LedgerStartupResult.Started(1), owner.startup())
        assertEquals(LedgerRuntimeState.Ready, owner.state)

        // ---- The real preflight over the redirected context: real container, real decryption,
        // real isolated migration/validation/owner counts.
        val preflight =
            RestorePreflightUseCase(
                owner = owner,
                fileSystem = fileSystem,
                layout = layout,
                source = deliveringSourcePort(container),
                isolatedDatabase = AndroidRestoreIsolatedDatabasePort(),
                crypto = JvmBackupCryptoPrimitives(),
                newToken = { TOKEN },
                nowMillis = { 0L },
            )
        val request =
            RestorePreflightRequest(
                password = PASSWORD,
                targetLedgerId = RESTORE_TARGET_LEDGER_ID,
                supportedSourceVersions = RESTORE_SUPPORTED_SOURCE_VERSIONS,
                currentSchemaVersion = currentSupportedSchemaVersion(),
            )
        val preview = preflight.preflight(request)
        assertTrue("the real preflight must produce a preview: $preview", preview is RestorePreflightResult.PreviewReady)
        val ready = preview as RestorePreflightResult.PreviewReady
        // The section 5.5 field-6 owner counts come from a REAL read of the payload: the A-7 default
        // seed is 2 accounts / 2 categories and the ledger has no transactions yet.
        assertEquals(2L, ready.summary.accountsCount)
        assertEquals(2L, ready.summary.categoriesCount)
        assertEquals(0L, ready.summary.transactionsCount)
        // The preflight is zero-pointer-write (spec section 7, P706-A03): gen-1 is still active.
        assertEquals("gen-1", fileSystem.readBytes(layout.activePointerFile).decodeToString())

        // ---- The confirm: the frozen nine-step switch, live on device.
        val confirm =
            ConfirmBackupRestoreUseCase(
                owner = owner,
                fileSystem = fileSystem,
                layout = layout,
                crypto = JvmBackupCryptoPrimitives(),
                targetLedgerId = RESTORE_TARGET_LEDGER_ID,
            )
        val result = runBlocking { confirm.confirm(ready.token) }

        // ---- The committed end state ON DISK (the P1 anchor).
        assertTrue("the switch must commit on device: $result", result is BackupRestoreSwitchResult.Committed)
        assertEquals(
            "the reported generation is the post-reopen owner generation (startup 1 -> reopen 2)",
            2,
            (result as BackupRestoreSwitchResult.Committed).runtimeGeneration,
        )
        assertEquals(
            "the on-disk active pointer must name the NEW generation",
            "gen-2",
            fileSystem.readBytes(layout.activePointerFile).decodeToString(),
        )
        assertTrue(
            "the new generation directory must survive the switch",
            fileSystem.isDirectory(layout.generationDirectory(2)),
        )
        assertTrue(
            "the new generation main file must survive the switch",
            fileSystem.exists(layout.mainFile(layout.generationDirectory(2))),
        )
        assertFalse(
            "a committed switch must not leave its journal behind",
            fileSystem.exists(layout.switchJournalFile),
        )
        assertEquals("the owner must be Ready on the restored graph", LedgerRuntimeState.Ready, owner.state)
        assertNotNull("the owner must hold an active generation", owner.activeGeneration)
        assertTrue(
            "the old generation is the rollback anchor and must be retained (spec section 5.2)",
            fileSystem.exists(generation1Main),
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Creates a REAL product ledger database at [mainFilePath] through the product factory and
     * bootstraps its catalog with the product seed, so the database carries the generated schema at
     * `currentSupportedSchemaVersion()`, the fixed target ledger identity and the A-7 catalog rows.
     * The factory closes its driver via the handle's `use`, leaving a complete, self-consistent
     * database file.
     */
    private fun seedProductLedger(
        context: Context,
        mainFilePath: String,
    ) {
        createAndroidLedgerDatabase(context, mainFilePath).use { handle ->
            handle.catalogStore.bootstrap(LedgerId(RESTORE_TARGET_LEDGER_ID), defaultCatalogSeed())
        }
    }

    /**
     * Writes one real authenticated container around the file at [plaintextFile] with the product
     * writer and the real JCE primitives (the same primitives the composition root injects), so the
     * preflight exercises its real header, decryption, digest and length steps.
     */
    private fun containerAround(
        plaintextFile: File,
        password: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeBackupContainer(
            plaintext = filePlaintextSource(plaintextFile),
            sink = BackupContainerSink { bytes, offset, length -> out.write(bytes, offset, length) },
            password = password,
            schemaVersion = currentSupportedSchemaVersion(),
            plaintextLength = plaintextFile.length(),
            crypto = JvmBackupCryptoPrimitives(),
        )
        return out.toByteArray()
    }

    /** A re-openable file-backed plaintext source (the writer reads the payload twice). */
    private fun filePlaintextSource(file: File): BackupPlaintextSource =
        BackupPlaintextSource {
            val stream = FileInputStream(file)
            object : BackupPlaintextReader {
                override fun read(buffer: ByteArray): Int = stream.read(buffer)

                override fun close() {
                    stream.close()
                }
            }
        }

    /**
     * The REAL [AndroidBackupSourcePort] delivering [container] as bytes: the SAF launch is invoked
     * synchronously (this suite runs the preflight on the instrumentation thread rather than the
     * production background dispatch), the stream open returns the container bytes, and the reported
     * size is the container's own length. The SAF provider path is covered by
     * [AndroidRestoreSourcePortInstrumentedTest]; the `OpenableColumns.SIZE` query that production
     * injects here is covered by [AndroidRestoreDocumentSizeInstrumentedTest].
     */
    private fun deliveringSourcePort(container: ByteArray): BackupSourcePort {
        var portRef: AndroidBackupSourcePort<Uri>? = null
        val port =
            AndroidBackupSourcePort<Uri>(
                postToMainThread = { action -> action() },
                launchOpenDocument = { portRef?.onOpenDocumentResult(SOURCE_URI) },
                openInputStream = { ByteArrayInputStream(container) },
                sizeOf = { container.size.toLong() },
            )
        portRef = port
        return port
    }

    /**
     * Builds a [ContextWrapper] whose `getDatabasePath` resolves RELATIVE names under a fresh
     * per-test temp directory, and asserts the redirection is actually in effect (fails loudly
     * otherwise, so the test can never run against the production ledger).
     *
     * An ABSOLUTE name is returned unchanged: that is the platform's own branch for absolute paths
     * (`Context.getDatabasePath` resolves the parent directory itself), and it is the branch the
     * framework driver uses to open a generation file by absolute path (the P0 hotfix's guarantee).
     * Every absolute path this test produces is derived from the redirected host directory, so it is
     * inside the temp directory; [assertIsolatedFromProduction] proves that for every seeded and
     * opened file.
     */
    private fun redirectedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "p706d-confirm-switch-" + System.nanoTime()).apply { mkdirs() }
        createdDirectories += root
        val wrapper =
            object : ContextWrapper(target) {
                override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(root, name)
            }
        val effective = wrapper.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath
        val production = target.getDatabasePath(LEGACY_DATABASE_NAME).absolutePath
        assertNotEquals("the test must not resolve to the production database path", production, effective)
        assertTrue("the effective database path must be inside the test temp directory: $effective", effective.startsWith(root.absolutePath))
        return wrapper
    }

    /**
     * Hard guard (the 06.C/06.D driver-suite pattern): a test-created file must live under the
     * app-private cache dir and must NOT resolve into the production `databases/` tree.
     */
    private fun assertIsolatedFromProduction(file: File) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val productionDatabases = target.getDatabasePath(LEGACY_DATABASE_NAME).parentFile?.absolutePath
        assertTrue(
            "the test database must be under the cache dir: ${file.absolutePath}",
            file.absolutePath.startsWith(target.cacheDir.absolutePath),
        )
        assertTrue(
            "the test database must not resolve into the production databases directory: ${file.absolutePath}",
            productionDatabases == null || !file.absolutePath.startsWith(productionDatabases),
        )
    }

    private companion object {
        const val LEGACY_DATABASE_NAME = "ledger.db"

        /** The deterministic preflight token, so the staging file names are known to the test. */
        const val TOKEN = "p706d-confirm-switch-token"

        const val PASSWORD = "p706d-device-confirm-password"

        val SOURCE_URI: Uri = Uri.parse("content://com.unifiedledger.android.p706d.confirm-switch/source")
    }
}
