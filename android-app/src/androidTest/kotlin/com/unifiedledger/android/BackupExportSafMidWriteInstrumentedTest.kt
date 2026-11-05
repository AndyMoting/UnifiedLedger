package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.ui.BackupExportFailure
import com.unifiedledger.ui.BackupExportRequest
import com.unifiedledger.ui.BackupExportResult
import com.unifiedledger.ui.BackupExportUseCase
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerRuntimeOwner
import com.unifiedledger.ui.LedgerRuntimeState
import com.unifiedledger.ui.LedgerStartupResult
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.ledgerStorageLayout
import com.unifiedledger.ui.openGenerationForSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P706-A10 second half (D-239 item 4): the SAF target stops accepting bytes MID-WRITE.
 *
 * Why it exists
 * -------------
 * D-286 closed A10's first half with a real out-of-space condition, but the target there refuses the
 * FIRST chunk — that vector never shows what the export does once a delivery is already partially
 * written. This case is the missing half: the write succeeds for a while and then fails, so the
 * assertions are about the failure path a half-written container leaves behind.
 *
 * How the failure is produced (and why it is a platform failure, not a stub)
 * ------------------------------------------------------------------------
 * `ContentResolver.openOutputStream` — the real production call — resolves the androidTest-only
 * [FailAfterBytesBackupTargetProvider]. That provider accepts exactly [FAIL_AFTER_BYTES], then
 * closes its pipe's read end, so the product's next write raises `EPIPE`: an `IOException` from the
 * transport, mid-stream. Only the SAF picker UI is replaced (a synchronous poster plus an immediate
 * result), exactly like the ENOSPC rig; the opener and everything after it is the real platform
 * path.
 *
 * Assertions, in order
 * --------------------
 * 1. the export RETURNS and reports `Failed(TARGET_WRITE_FAILED)` — never a crash, never a success;
 * 2. the provider's own drained-byte counter (read back over `ContentProvider.call`, so it survives
 *    the process boundary) equals its failing window: the failure happened AFTER bytes were
 *    accepted, which is the difference between this vector and an open-time refusal;
 * 3. the private staging artifacts (snapshot + container) are GONE — `export`'s `finally` deletes
 *    them, so a mid-write failure leaves nothing a next-start sweep must trust;
 * 4. the ledger is untouched: `PRAGMA quick_check` = ok, active pointer and generation set
 *    unchanged, and one REAL production startup still resolves `gen-1`.
 *
 * Data safety
 * -----------
 * The user's production ledger is never touched: the context is wrapped so
 * `getDatabasePath("ledger.db")` resolves inside a fresh directory under `filesDir` (and
 * [redirectedContext] fails the test unless that redirection is really in effect). Every directory
 * this case creates is removed in `finally`. No filesystem is filled, so unlike the ENOSPC rig this
 * case is safe to run in the ordinary device regression.
 *
 * Shared helpers are COPIED from `BackupExportEnospcInstrumentedTest`, not imported: those helpers
 * are private to that class, and that rig must not be changed by this batch.
 */
@RunWith(AndroidJUnit4::class)
class BackupExportSafMidWriteInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val createdRoots = mutableListOf<File>()

    @Test
    fun aTargetThatStopsAcceptingBytesMidWriteIsTypedAndLeavesTheLedgerIntact() {
        val redirectRoot = newRoot(targetContext.filesDir, "backup-midwrite-rig")
        val context = redirectedContext(redirectRoot)
        val fileSystem = AndroidLedgerFileSystem()
        val (hostDirectory, _) = androidStableStoragePaths(context.getDatabasePath(DATABASE_NAME))
        val layout = ledgerStorageLayout(fileSystem, hostDirectory)
        var graph: CloseableLedgerGraph? = null
        var owner: LedgerRuntimeOwner<CloseableLedgerGraph>? = null
        try {
            // 1. The real production startup over the redirected host (App.kt's wiring shape).
            val importPort =
                AndroidImportFilePickPort<Uri>(
                    launchOpenDocument = { error("the mid-write rig never launches a picker") },
                    resolveMetadata = { error("the mid-write rig never resolves a pick") },
                    openInputStream = { error("the mid-write rig never opens a pick stream") },
                    onResult = { error("the mid-write rig never receives a pick result") },
                )
            val importChannel = ImportFilePickResultChannel()
            val runtimeOwner =
                LedgerRuntimeOwner<CloseableLedgerGraph>(
                    openGeneration = { selection ->
                        openGenerationForSelection(
                            selection = selection,
                            openActivePointer = {
                                val opened = openAndroidStableStorageLedger(context, importPort, importChannel)
                                graph = opened
                                opened
                            },
                            openExplicit = { generation ->
                                val opened = openAndroidExplicitGeneration(context, importPort, importChannel, generation)
                                graph = opened
                                opened
                            },
                        )
                    },
                    closeGraph = { opened -> opened.close() },
                    facadeOf = { opened -> opened.facade },
                )
            owner = runtimeOwner
            assertEquals(
                "the redirected fresh install must start on gen-1",
                LedgerStartupResult.Started(1),
                runtimeOwner.startup(),
            )
            assertEquals("the export rig needs a Ready runtime", LedgerRuntimeState.Ready, runtimeOwner.state)

            // 2. Baseline: the state the mid-write failure must leave untouched.
            val pointerBefore = readActivePointer(layout)
            assertEquals("the redirected fresh install must publish gen-1", "gen-1", pointerBefore)
            assertEquals("the fresh install must create exactly one generation", listOf("gen-1"), generationNames(layout))
            val mainFile = mainFileOf(layout, pointerBefore)
            assertTrue("the generation main file must exist: $mainFile", mainFile.isFile)
            assertEquals("the fresh ledger must be structurally clean", QUICK_CHECK_OK, quickCheck(mainFile))
            val snapshotFile = File(layout.backupSnapshotFile(TARGET_TOKEN))
            val containerFile = File(layout.backupContainerFile(TARGET_TOKEN))
            assertFalse("the private snapshot staging file must not exist before the export", snapshotFile.exists())
            assertFalse("the private container staging file must not exist before the export", containerFile.exists())

            // 3. The REAL export over the REAL graph; only the picker UI is replaced.
            var port: AndroidBackupTargetPort<Uri>? = null
            val targetPort =
                AndroidBackupTargetPort<Uri>(
                    postToMainThread = { block -> block() },
                    launchCreateDocument = { port?.onCreateDocumentResult(FAILING_TARGET_URI) },
                    openOutputStream = { picked ->
                        // The real platform call: it resolves the androidTest-only provider above.
                        targetContext.contentResolver.openOutputStream(picked, "w")
                    },
                )
            port = targetPort
            val exportUseCase =
                BackupExportUseCase(
                    owner = runtimeOwner,
                    fileSystem = fileSystem,
                    layout = layout,
                    snapshotProvider = { graph?.snapshotPort },
                    target = targetPort,
                    crypto = JvmBackupCryptoPrimitives(),
                    newToken = { TARGET_TOKEN },
                )
            val result = exportUseCase.export(BackupExportRequest(PASSWORD, mainFile.absolutePath))

            // 4. The typed outcome.
            assertTrue(
                "a target that stops accepting bytes must produce a typed Failed, never a crash or Succeeded: $result",
                result is BackupExportResult.Failed,
            )
            assertEquals(
                "the refused target write must surface as TARGET_WRITE_FAILED",
                BackupExportFailure.TARGET_WRITE_FAILED,
                (result as BackupExportResult.Failed).reason,
            )

            // 5. MID-WRITE proof: the provider accepted its whole window before the transport broke,
            //    so the failure happened with bytes already delivered — not at open.
            val drained = drainedBytes()
            assertEquals(
                "the provider must have accepted its full window before the write failed " +
                    "(drained=$drained, window=$FAIL_AFTER_BYTES); a smaller count means the stream " +
                    "never reached the vector under test",
                FAIL_AFTER_BYTES,
                drained,
            )

            // 6. No private staging residue.
            assertFalse("a failed export must delete its private snapshot staging file", snapshotFile.exists())
            assertFalse("a failed export must delete its private container staging file", containerFile.exists())
            assertNoStagingResidue(layout, TARGET_TOKEN)

            // 7. The ledger is untouched.
            assertEquals("a refused target write must not touch the ledger", QUICK_CHECK_OK, quickCheck(mainFile))
            assertEquals("the active pointer must be unchanged", "gen-1", readActivePointer(layout))
            assertEquals("no generation may be created by a failed export", listOf("gen-1"), generationNames(layout))
            val reopened = openAndroidStableStorageLedger(context, importPort, importChannel)
            try {
                assertEquals("the reopen must resolve the existing gen-1", "gen-1", readActivePointer(layout))
                assertEquals("the reopen must not create a generation", listOf("gen-1"), generationNames(layout))
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { owner?.closeActiveGraph() }
            runCatching { graph?.close() }
            createdRoots.forEach { deleteRecursively(it) }
            createdRoots.clear()
        }
    }

    /** The provider's own view of how many bytes it accepted, read across the process boundary. */
    private fun drainedBytes(): Long {
        val answer = targetContext.contentResolver.call(FAILING_TARGET_URI, "drainedBytes", null, null)
        return answer?.getLong("drainedBytes") ?: -1L
    }

    // ------------------------------------------------------------------ rig helpers (copied)

    private fun redirectedContext(root: File): Context {
        check(root.isDirectory) { "the rig root must exist: $root" }
        val wrapper =
            object : ContextWrapper(targetContext) {
                override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(root, name)
            }
        val effective = wrapper.getDatabasePath(DATABASE_NAME).absolutePath
        val production = targetContext.getDatabasePath(DATABASE_NAME).absolutePath
        assertNotEquals("the rig must never resolve the production database path", production, effective)
        assertTrue(
            "the effective database path must stay inside the rig root: $effective",
            effective.startsWith(root.absolutePath),
        )
        return wrapper
    }

    private fun newRoot(
        parent: File,
        prefix: String,
    ): File {
        val root = File(parent, "$prefix-${System.currentTimeMillis()}")
        check(root.mkdirs()) { "the rig must create its own directory: $root" }
        createdRoots += root
        return root
    }

    private fun readActivePointer(layout: LedgerStorageLayout): String = File(layout.activePointerFile).readText().trim()

    private fun generationNames(layout: LedgerStorageLayout): List<String> {
        val files = File(layout.generationsDirectory).listFiles().orEmpty()
        return files.filter { it.isDirectory }.map { it.name }.sorted()
    }

    private fun mainFileOf(
        layout: LedgerStorageLayout,
        pointer: String,
    ): File {
        val generationDirectory = File(layout.generationsDirectory, pointer).absolutePath
        return File(layout.mainFile(generationDirectory))
    }

    private fun quickCheck(mainFile: File): String =
        SQLiteDatabase.openDatabase(mainFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                assertTrue("PRAGMA quick_check must return a row", cursor.moveToFirst())
                val result = cursor.getString(0)
                assertFalse("PRAGMA quick_check must return exactly one row", cursor.moveToNext())
                result
            }
        }

    private fun assertNoStagingResidue(
        layout: LedgerStorageLayout,
        token: String,
    ) {
        val staging = File(layout.backupStagingDirectory)
        val residue = staging.listFiles().orEmpty().filter { it.name.contains(token) }
        assertTrue("the private staging directory must hold no artifact for this export: $residue", residue.isEmpty())
    }

    private fun deleteRecursively(root: File): Boolean {
        if (!root.exists()) return true
        root.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) deleteRecursively(child) else child.delete()
        }
        return root.delete()
    }

    private companion object {
        /** The legacy product database name the production startup resolves through the context. */
        const val DATABASE_NAME = "ledger.db"

        /** `PRAGMA quick_check`'s only accepted answer. */
        const val QUICK_CHECK_OK = "ok"

        /** The opaque export token this case pins, so the private staging paths are known exactly. */
        const val TARGET_TOKEN = "backup-export-midwrite"

        /** A synthetic password that satisfies the container's 8-code-point minimum. */
        const val PASSWORD = "backup-midwrite-device-password"

        /** The manifest authority of the failing provider (see src/androidTest/AndroidManifest.xml). */
        const val FAILING_TARGET_AUTHORITY = "com.unifiedledger.android.p706a10.failtarget"

        /** The document URI inside that provider; the path is irrelevant to the fixture. */
        val FAILING_TARGET_URI: Uri = Uri.parse("content://$FAILING_TARGET_AUTHORITY/target.ulbk")

        /**
         * The provider's failing window, mirrored from
         * [FailAfterBytesBackupTargetProvider.FAIL_AFTER_BYTES] as a literal so this test keeps ZERO
         * reference to the Java fixture's class (it lives in the test package's own APK). A
         * divergent value fails the drained-byte assertion instead of passing silently.
         */
        const val FAIL_AFTER_BYTES = 1024L * 1024L
    }
}
