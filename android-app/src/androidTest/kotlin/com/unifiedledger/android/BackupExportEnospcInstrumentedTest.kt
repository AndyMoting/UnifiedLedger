package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.ui.BackupExportFailure
import com.unifiedledger.ui.BackupExportRequest
import com.unifiedledger.ui.BackupExportResult
import com.unifiedledger.ui.BackupExportUseCase
import com.unifiedledger.ui.CloseResult
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
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * P706-A10 export-half device rig (D-239 item 4, the "A10 部分证据" residual): a REAL out-of-space
 * condition, produced by the device itself, on the REAL backup-export delivery path.
 *
 * Why it exists
 * -------------
 * D-239 registered the export-side gap explicitly: the "full disk" evidence for `BackupExportUseCase`
 * is an ARITHMETIC precheck on `LedgerFileSystem.usableSpace` plus INJECTED writer exceptions
 * (`BackupExportUseCaseTest`'s eleven vectors). No test has ever let an operating system refuse a
 * write on the user-target stream and then shown that the product converts that refusal into its
 * typed `BackupExportFailure.TARGET_WRITE_FAILED` with the ledger untouched. Only a device can
 * produce a genuine `ENOSPC`, because a JVM fake can only throw the exception the test itself wrote.
 * This class is that rig — the export-side mirror of [ImportEnospcInstrumentedTest].
 *
 * Where the out-of-space condition is created, and why it is a REAL OS error
 * -------------------------------------------------------------------------
 * `BackupExportUseCase.export` runs, in this order: disk precheck, `VACUUM INTO` snapshot,
 * `PRAGMA integrity_check`, bounded authenticated container into the private staging, then delivery
 * to the user target. Everything it needs for the first four steps lives under
 * `layout.hostDirectory`, i.e. the same app-private volume as the fill.
 *
 * The fill therefore happens at the ONE point that is both after those steps and still inside the
 * export: the injected `BackupTargetPort`. Its stream opener records that the staging container
 * exists (proof the snapshot and container steps really succeeded), opens the REAL target file, and
 * only then exhausts the filesystem. The first container chunk `BackupTargetWriter.write` then
 * reaches [FileOutputStream.write] on a filesystem with no free blocks, and the kernel answers
 * `ENOSPC` (errno 28) — an `IOException` raised by the OS, not by this test.
 *
 * This ordering is deliberate and is the whole point of the rig:
 * - filling BEFORE the export would make the arithmetic precheck return
 *   `BackupExportFailure.INSUFFICIENT_SPACE` and the delivery would never run — that vector is
 *   already covered on the JVM and is exactly the arithmetic-only evidence D-239 complains about;
 * - filling AFTER the export returns is impossible (the delivery already happened).
 * The target file is opened BEFORE the fill, so inode allocation cannot fail first and turn the
 * outcome into a cancel; only the data write is refused.
 *
 * Assertions, in this order
 * -------------------------
 * 1. the export RETURNS (reaching the next assertion is the "no crash" half: a `FATAL` or an
 *    `OutOfMemoryError` here would take the process down before any receipt existed);
 * 2. the delivery step was actually reached, i.e. the private staging container existed when
 *    [BackupTargetPort.openTarget] ran — without this a `SNAPSHOT_FAILED` would masquerade as the
 *    vector under test;
 * 3. the fill really produced ENOSPC (see [fillUntilEnospc]); a host that cannot exhaust its
 *    filesystem SKIPS and never passes;
 * 4. the result is `BackupExportResult.Failed(BackupExportFailure.TARGET_WRITE_FAILED)`;
 * 5. the OS refused the FIRST chunk and no write ever succeeded, so the outcome cannot be a
 *    late-chunk accident;
 * 6. nothing was written to the target and it was never committed: `commit` never ran and the file
 *    is shorter than the staged container. A failure must not leave a success-claiming artifact;
 * 7. the private staging artifacts (snapshot + container) are GONE — `export`'s `finally` deletes
 *    them, so a failure has no residue a next-start sweep would have to trust;
 * 8. the ledger is structurally intact: active pointer unchanged, generation set unchanged,
 *    `PRAGMA quick_check` = ok, no dangling foreign key, and one REAL production startup still
 *    resolves `gen-1` (never a FreshInstall, which would mean the ledger directory itself was lost).
 *
 * Data safety
 * -----------
 * The user's production ledger is never touched. The target context is wrapped so
 * `getDatabasePath("ledger.db")` resolves inside a fresh directory under `filesDir`, and
 * [redirectedContext] fails the test unless that redirection is really in effect. The fill lives in
 * a fresh `cache/enospc-fill-<digits>` directory (the prefix the ENOSPC driver's narrow host cleanup
 * recognizes) and holds only empty synthetic chunks. Every directory this case creates is recorded
 * in [createdRoots] and removed in `finally`.
 *
 * Hazards the operator must know
 * ------------------------------
 * - While the fill is held `/data` is genuinely full: other apps on that device may misbehave and
 *   any system write can fail. Run it on a disposable emulator, never on a phone with real data.
 * - The fill is released INSIDE the test body before the integrity reads, and `finally` releases it
 *   again on every failure path.
 * - If the process is killed mid-test the fill directory survives and the disk stays full. Recovery:
 *   `adb shell pm clear com.unifiedledger.android`.
 *
 * Opt-in only, never in the ordinary device regression
 * ----------------------------------------------------
 * The single case runs only when the instrumentation argument [OPT_IN_ARGUMENT] is `1`; without it
 * JUnit reports it skipped. Turning it into a real run:
 *
 * ```
 * adb shell am instrument -w -e ul.enospc 1 \
 *   -e class com.unifiedledger.android.BackupExportEnospcInstrumentedTest \
 *   com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Like [ImportEnospcInstrumentedTest] this class must stay out of the ordinary 41-case inventory;
 * the exclusion is declared in `tools/python/android_scale/inventory.py` (`EXCLUDED`).
 *
 * Shared helpers are COPIED, not imported: the helpers in [ImportEnospcInstrumentedTest] are private
 * to that class, and that rig must not be changed by this batch.
 */
@RunWith(AndroidJUnit4::class)
class BackupExportEnospcInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val createdRoots = mutableListOf<File>()

    @Before
    fun requireExplicitOptIn() {
        val arguments = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(
            "opt-in only: pass -e $OPT_IN_ARGUMENT 1 and " +
                "-e class com.unifiedledger.android.BackupExportEnospcInstrumentedTest " +
                "to the instrumentation runner; the ordinary device regression excludes this class",
            arguments.getString(OPT_IN_ARGUMENT) == "1",
        )
    }

    @Test
    fun aRealEnospcOnTheBackupTargetDeliveryIsTypedAndLeavesTheLedgerIntact() {
        val redirectRoot = newRoot(targetContext.filesDir, "backup-enospc-rig")
        val fillRoot = newRoot(targetContext.cacheDir, "enospc-fill")
        val targetRoot = newRoot(targetContext.cacheDir, "enospc-target")
        val context = redirectedContext(redirectRoot)
        val fileSystem = AndroidLedgerFileSystem()
        val (hostDirectory, _) = androidStableStoragePaths(context.getDatabasePath(DATABASE_NAME))
        val layout = ledgerStorageLayout(fileSystem, hostDirectory)
        val delivery = DeliveryRecord()
        var graph: CloseableLedgerGraph? = null
        var owner: LedgerRuntimeOwner<CloseableLedgerGraph>? = null
        try {
            // 1. The real production startup over the redirected host, wired exactly like App.kt's
            // AndroidStartupController: ActivePointer runs the full startup sequence. The graph is
            // captured so the export's snapshot surface is the LIVE graph's (App.kt's
            // `lastOpenedGraph`), never a hand-built substitute.
            val importPort =
                AndroidImportFilePickPort<Uri>(
                    launchOpenDocument = { error("the ENOSPC rig never launches a picker") },
                    resolveMetadata = { error("the ENOSPC rig never resolves a pick") },
                    openInputStream = { error("the ENOSPC rig never opens a pick stream") },
                    onResult = { error("the ENOSPC rig never receives a pick result") },
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

            // 2. Baseline, taken while space is still available: this is the state the refused
            // target write must leave untouched.
            val pointerBefore = readActivePointer(layout)
            val generationsBefore = generationNames(layout)
            assertEquals("the redirected fresh install must publish gen-1", "gen-1", pointerBefore)
            assertEquals("the fresh install must create exactly one generation", listOf("gen-1"), generationsBefore)
            val mainFile = mainFileOf(layout, pointerBefore)
            assertTrue("the generation main file must exist: $mainFile", mainFile.isFile)
            assertEquals("the fresh ledger must be structurally clean", QUICK_CHECK_OK, quickCheck(mainFile))
            val freeBefore = availableBytes()
            Assume.assumeTrue(
                "the rig needs enough free space for the export's own disk precheck " +
                    "(container + plaintext + 64 MiB headroom) before it can fill the rest: free=$freeBefore",
                freeBefore > MIN_EXPORT_START_BYTES,
            )

            // 3. The staging paths for OUR token, so the "no residue" assertion names exact files
            // instead of guessing.
            val snapshotFile = File(layout.backupSnapshotFile(TARGET_TOKEN))
            val containerFile = File(layout.backupContainerFile(TARGET_TOKEN))
            assertFalse("the private snapshot staging file must not exist before the export", snapshotFile.exists())
            assertFalse("the private container staging file must not exist before the export", containerFile.exists())

            // 4. The REAL product export over the REAL graph. `AndroidBackupTargetPort` is the
            // production SAF port; a synchronous poster plus an immediate result delivery replaces
            // only the picker UI, which needs a human. The poster runs the launch inline (the
            // `AndroidBackupTargetPortTest` `RecordingPoster` shape), so the callback lands before
            // the port starts waiting on its latch. The nullable holder is the same pattern that
            // test uses for the port's own instance.
            var port: AndroidBackupTargetPort<Uri>? = null
            val targetPort =
                AndroidBackupTargetPort<Uri>(
                    postToMainThread = { block -> block() },
                    launchCreateDocument = { port?.onCreateDocumentResult(TARGET_URI) },
                    openOutputStream = { picked ->
                        openTargetOnAFullFilesystem(
                            picked = picked,
                            fillRoot = fillRoot,
                            targetRoot = targetRoot,
                            containerFile = containerFile,
                            delivery = delivery,
                        )
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
            val request = BackupExportRequest(PASSWORD, mainFile.absolutePath)
            val result = exportUseCase.export(request)

            // 5. The delivery step must really have been reached BEFORE any skip decision: if the
            // export stopped at the snapshot or the container, the filler never ran and
            // `fillWriteFailed` would be false, so a product defect would masquerade as an
            // environment skip. This assertion is deliberately FIRST.
            assertTrue(
                "the injected target port must be opened by the export's delivery step " +
                    "(result=$result, containerLengthAtDelivery=${delivery.containerLengthAtDelivery})",
                delivery.openerRan,
            )
            assertTrue(
                "the delivery step must be reached after a successful snapshot and container staging " +
                    "(containerLengthAtDelivery=${delivery.containerLengthAtDelivery})",
                delivery.containerLengthAtDelivery > 0L,
            )

            // 6. The skip decision: a host that could not reach ENOSPC can never be reported as a
            // pass. A refusal for any OTHER reason also skips, but names the errno (A10-2), because
            // the rig may not claim an out-of-space condition from a different failure.
            Assume.assumeTrue(
                "the rig was refused for a reason that is NOT ENOSPC " +
                    "(fill=${delivery.fillNonEnospcFailure}, target=${delivery.nonEnospcFailure}); " +
                    "only errno ENOSPC proves the condition under test",
                delivery.nonEnospcFailure == null && delivery.fillNonEnospcFailure == null,
            )
            Assume.assumeTrue(
                "the rig needs a REAL ENOSPC on the target write and this host did not reach one: " +
                    "wrote=${delivery.fillBytes} bytes, writeFailed=${delivery.fillWriteFailed}, " +
                    "cap=${MAX_FILL_BYTES} bytes, near-full bound=${NEAR_FULL_TOLERANCE_BYTES} bytes, " +
                    "free before=$freeBefore. A host with more free space than the cap cannot run this probe.",
                delivery.fillWriteFailed,
            )
            assertTrue("the fill must actually hold bytes on disk (wrote=${delivery.fillBytes})", delivery.fillBytes > 0L)

            // 7. The typed-outcome half. A full disk must surface as the payload-free target write
            // failure; a crash or an exception escaping `export` fails here, not in the field.
            assertTrue(
                "a full target filesystem must produce a typed Failed, never a crash or Succeeded: $result",
                result is BackupExportResult.Failed,
            )
            assertEquals(
                "the refused target write must surface as TARGET_WRITE_FAILED",
                BackupExportFailure.TARGET_WRITE_FAILED,
                (result as BackupExportResult.Failed).reason,
            )

            // 8. No success-claiming artifact on the target. The OS may flush part of the first
            // chunk before refusing it, so the claim is "the first chunk was refused, no write ever
            // succeeded, and the target was never published".
            assertTrue("the OS must refuse the delivery's FIRST chunk", delivery.firstWriteFailed)
            assertEquals(
                "the writer must observe no successful write into a full filesystem",
                0,
                delivery.successfulWrites,
            )
            assertFalse(
                "a failed delivery must never publish the target (commit must not run)",
                delivery.committed,
            )
            assertTrue(
                "the target must be shorter than the staged container " +
                    "(target=${delivery.targetLength}, container=${delivery.containerLengthAtDelivery})",
                delivery.targetLength < delivery.containerLengthAtDelivery,
            )

            // 9. No private staging residue: export's `finally` deletes the snapshot and the
            // container on every path, so a failure leaves nothing a next-start sweep must trust.
            assertFalse("a failed export must delete its private snapshot staging file", snapshotFile.exists())
            assertFalse("a failed export must delete its private container staging file", containerFile.exists())
            assertNoStagingResidue(layout, TARGET_TOKEN)

            // 10. Release the fill: the disk must come back before any write-side read.
            assertTrue("the fill directory must be removable", deleteRecursively(fillRoot))
            val freeRecovered = availableBytes()
            assertTrue(
                "the disk must regain space once the fill is released " +
                    "(recovered=$freeRecovered, near-full bound=$NEAR_FULL_TOLERANCE_BYTES)",
                freeRecovered > NEAR_FULL_TOLERANCE_BYTES,
            )

            // 11. The ledger is structurally intact: layout unchanged, quick_check ok, no foreign
            // key dangling. If quick_check fails, prove FIRST that a real startup still resolves
            // gen-1, so corruption and data loss stay distinguishable.
            assertEquals("the refused write must not replace the active pointer", pointerBefore, readActivePointer(layout))
            assertEquals("the refused write must not add or drop a generation", generationsBefore, generationNames(layout))
            val quickCheckAfter = runCatching { quickCheck(mainFile) }
            if (quickCheckAfter.getOrNull() != QUICK_CHECK_OK) {
                // Release the graph first (the import rig's own ordering) so the reopen probe is a
                // second, independent startup and never fights the owner for the same main file.
                runCatching { runtimeOwner.closeActiveGraph() }
                graph = null
                owner = null
                val reopen = runCatching { assertReopensOnGen1(context, layout, importPort, importChannel) }
                throw AssertionError(
                    "PRAGMA quick_check must stay ok after the refused target write " +
                        "(quick_check=${quickCheckAfter.exceptionOrNull() ?: quickCheckAfter.getOrNull()}, " +
                        "reopen-on-gen-1=${reopen.exceptionOrNull()?.message ?: "ok"})",
                )
            }
            assertNoDanglingForeignKeys(mainFile)

            // 12. The process survived the typed failure and the ledger still opens for real: close
            // the owner's graph, then run one fresh production startup over the same host.
            assertEquals("closing the rig's graph must succeed", CloseResult.Closed, runtimeOwner.closeActiveGraph())
            graph = null
            owner = null
            assertReopensOnGen1(context, layout, importPort, importChannel)

            // 13. Teardown inside the happy path.
            assertTrue("the redirected database directory must be removable", deleteRecursively(redirectRoot))
            assertFalse("the redirected database directory must be gone", redirectRoot.exists())
            assertFalse("the fill directory must be gone", fillRoot.exists())
            assertTrue("the target directory must be removable", deleteRecursively(targetRoot))
        } finally {
            // Unconditional: a failed assertion must never leave the device with a full disk or an
            // orphaned redirected ledger.
            runCatching { owner?.closeActiveGraph() }
            runCatching { graph?.close() }
            createdRoots.forEach { deleteRecursively(it) }
            createdRoots.clear()
        }
    }

    // ------------------------------------------------------------------ the target stream

    /**
     * The delivery seam. Opens the REAL target file, THEN exhausts the filesystem, then returns the
     * stream: the product's writer writes container chunks into [FileOutputStream] on a volume with
     * no free blocks, and the kernel refuses the write with `ENOSPC`.
     *
     * The target file is opened before the fill on purpose. Creating a file needs an inode, and on a
     * completely full volume that can fail first and turn the outcome into a cancel
     * (`AndroidBackupTargetPort.openTarget` maps an opener exception to `null`), which would hide
     * the very vector this rig measures.
     */
    private fun openTargetOnAFullFilesystem(
        picked: Uri,
        fillRoot: File,
        targetRoot: File,
        containerFile: File,
        delivery: DeliveryRecord,
    ): OutputStream? {
        check(picked == TARGET_URI) { "the injected port must deliver the rig's own target handle" }
        delivery.openerRan = true
        // The snapshot and the container are staged before this call; their size is the proof that
        // the export reached the delivery step with real work behind it.
        delivery.containerLengthAtDelivery = if (containerFile.isFile) containerFile.length() else 0L
        check(targetRoot.isDirectory) { "the target root must exist: $targetRoot" }
        val targetFile = File(targetRoot, "unifiedledger-backup.ulbk")
        val base = FileOutputStream(targetFile)
        val fill = fillUntilEnospc(fillRoot)
        delivery.fillWriteFailed = fill.writeFailed
        delivery.fillBytes = fill.bytes
        delivery.fillNonEnospcFailure = fill.nonEnospcFailure
        return RecordingFullDiskStream(base, targetFile, delivery)
    }

    /**
     * Records what the product's writer did with the refused stream. `close` is the discriminator
     * between the two exits of the port's writer: `commit()` flushes and then closes, while `close()`
     * on a failed delivery closes WITHOUT a preceding flush. So [DeliveryRecord.committed] is true
     * only when the product published the target.
     */
    private class RecordingFullDiskStream(
        private val stream: FileOutputStream,
        private val file: File,
        private val delivery: DeliveryRecord,
    ) : OutputStream() {
        private var flushed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            try {
                stream.write(b, off, len)
                delivery.successfulWrites += 1
            } catch (failure: IOException) {
                // A10-2: only a genuine ENOSPC may count as "the target refused the write".
                // EIO/EDQUOT/EFBIG/EINTR arrive as IOException too and prove nothing about a
                // full disk, so the rig records the non-ENOSPC reason and the case SKIPS.
                if (isRealEnospc(failure)) {
                    delivery.firstWriteFailed = true
                } else {
                    delivery.nonEnospcFailure = describeIoFailure(failure)
                }
                throw failure
            }
        }

        override fun flush() {
            flushed = true
            stream.flush()
        }

        override fun close() {
            if (flushed) delivery.committed = true
            runCatching { stream.close() }
            delivery.targetLength = if (file.exists()) file.length() else 0L
        }
    }

    /** What the target seam observed; plain mutable state, no synchronization (one export thread). */
    private class DeliveryRecord {
        var openerRan: Boolean = false
        var containerLengthAtDelivery: Long = 0L
        var fillWriteFailed: Boolean = false
        var fillBytes: Long = 0L
        var fillNonEnospcFailure: String? = null
        var successfulWrites: Int = 0
        var firstWriteFailed: Boolean = false
        var nonEnospcFailure: String? = null
        var committed: Boolean = false
        var targetLength: Long = 0L
    }

    // ------------------------------------------------------------------ the ENOSPC classifier

    /**
     * A10-2: a refused write is ENOSPC only when the OS says so. A bare `IOException` is NOT
     * evidence of a full disk — `EIO`, `EDQUOT` (quota exceeded), `EFBIG` and `EINTR` all arrive as
     * the same type, and treating any of them as "the disk is full" would let the rig report a real
     * ENOSPC it never produced.
     *
     * The authoritative check is the errno of an [ErrnoException] (`ENOSPC` = errno 28), because
     * Android's file I/O raises exactly that subtype for a refused write. The message check is the
     * fallback for a wrapper that rethrows a plain `IOException` while preserving the cause text.
     */
    private fun isRealEnospc(failure: Throwable): Boolean {
        val errno = (failure as? ErrnoException)?.errno
        if (errno != null) return errno == OsConstants.ENOSPC
        if (failure.message?.contains(ENOSPC_MESSAGE, ignoreCase = true) == true) return true
        return failure.cause?.let { cause -> cause !== failure && isRealEnospc(cause) } ?: false
    }

    /** The operator-facing description of a refusal that was NOT ENOSPC. */
    private fun describeIoFailure(failure: Throwable): String {
        val errno = (failure as? ErrnoException)?.errno
        val kind = failure.javaClass.simpleName
        return if (errno != null) "$kind(errno=$errno)" else "$kind: ${failure.message ?: "no message"}"
    }

    // ------------------------------------------------------------------ context / layout helpers

    /**
     * Every relative database name resolves inside [root]; ABSOLUTE names pass through unchanged,
     * because that is exactly what the production startup hands to the driver.
     */
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
        assertEquals(
            "absolute names must pass through the wrapper unchanged (production opens the generation file by absolute path)",
            File(root, "gen-1/ledger.db").absolutePath,
            wrapper.getDatabasePath(File(root, "gen-1/ledger.db").absolutePath).absolutePath,
        )
        return wrapper
    }

    private fun newRoot(
        parent: File,
        prefix: String,
    ): File {
        // A10-10: currentTimeMillis, not nanoTime — nanoTime's origin is arbitrary and may be
        // negative, and a leading dash produces "enospc-fill--123", which the driver's host-side
        // cleanup regex (`enospc-fill-[0-9]+`) deliberately refuses to delete.
        //
        // A10-9 (delayed allocation, NOT fixed here): ext4/f2fs can accept a write into page cache
        // and only fail at flush/writeback. That is why the fill and the target stream both call
        // flush/sync before declaring success, but a device whose writeback lags can still let a
        // short write through and turn an expected refusal into a failed assertion later. The
        // symptom to recognise is a fill that reports writeFailed=false while /data reads ~0 free.
        val root = File(parent, "$prefix-${System.currentTimeMillis()}")
        check(root.mkdirs()) { "the rig must create its own directory: $root" }
        createdRoots += root
        return root
    }

    // ------------------------------------------------------------------ ledger observation helpers

    private fun readActivePointer(layout: LedgerStorageLayout): String = File(layout.activePointerFile).readText().trim()

    private fun generationNames(layout: LedgerStorageLayout): List<String> {
        val files = File(layout.generationsDirectory).listFiles().orEmpty()
        val directories = files.filter { it.isDirectory }
        return directories.map { it.name }.sorted()
    }

    private fun mainFileOf(
        layout: LedgerStorageLayout,
        pointer: String,
    ): File {
        val generationDirectory = File(layout.generationsDirectory, pointer).absolutePath
        return File(layout.mainFile(generationDirectory))
    }

    /** One read/write connection of the ledger file; every call closes it. */
    private fun <T> withLedgerDatabase(
        mainFile: File,
        block: (SQLiteDatabase) -> T,
    ): T = SQLiteDatabase.openDatabase(mainFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun quickCheck(mainFile: File): String =
        withLedgerDatabase(mainFile) { database ->
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                assertTrue("PRAGMA quick_check must return a row", cursor.moveToFirst())
                val result = cursor.getString(0)
                assertFalse("PRAGMA quick_check must return exactly one row", cursor.moveToNext())
                result
            }
        }

    private fun assertNoDanglingForeignKeys(mainFile: File) {
        // A10-11: this check is only meaningful once the ledger HAS rows. A fresh install carries
        // the bootstrapped catalog (2 accounts / 2 categories) and no transactions, so the check
        // asserts the catalog's own referential closure rather than "zero rows, zero violations".
        // The precondition is asserted, so an empty database fails here instead of passing silently.
        withLedgerDatabase(mainFile) { database ->
            assertTrue(
                "the fresh install must carry the bootstrapped catalog, or the foreign-key check is vacuous",
                rowCount(database, "catalog_account") > 0L && rowCount(database, "catalog_category") > 0L,
            )
            database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("no foreign key may dangle after the refused target write", cursor.moveToFirst())
            }
        }
    }

    private fun rowCount(
        database: SQLiteDatabase,
        table: String,
    ): Long =
        database.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
            assertTrue("count(*) on $table must return a row", cursor.moveToFirst())
            cursor.getLong(0)
        }

    /** No staging entry may still name this export's token. */
    private fun assertNoStagingResidue(
        layout: LedgerStorageLayout,
        token: String,
    ) {
        val staging = File(layout.backupStagingDirectory)
        val residue = staging.listFiles().orEmpty().filter { it.name.contains(token) }
        assertTrue("the private staging directory must hold no artifact for this export: $residue", residue.isEmpty())
    }

    /**
     * The integrity diagnostic: a REAL production startup on the same host must land on the existing
     * gen-1. A FreshInstall here would mean the ledger directory itself was lost — a far worse
     * finding than corruption — so the two are separated before the case fails.
     */
    private fun assertReopensOnGen1(
        context: Context,
        layout: LedgerStorageLayout,
        importPort: AndroidImportFilePickPort<Uri>,
        importChannel: ImportFilePickResultChannel,
    ) {
        val reopened = openAndroidStableStorageLedger(context, importPort, importChannel)
        try {
            assertEquals("the reopen must resolve the existing gen-1", "gen-1", readActivePointer(layout))
            assertEquals("the reopen must not create a generation", listOf("gen-1"), generationNames(layout))
        } finally {
            reopened.close()
        }
    }

    // ------------------------------------------------------------------ the fill

    private fun availableBytes(): Long = StatFs(targetContext.filesDir.absolutePath).availableBytes

    /**
     * Writes synthetic chunks into [root] until the filesystem refuses a write. The chunk size
     * shrinks after a failure so the last free blocks are consumed too, which is what makes the next
     * data write fail instead of sneaking a whole first chunk through.
     */
    private fun fillUntilEnospc(root: File): FillOutcome {
        check(root.isDirectory) { "the fill root must exist: $root" }
        val started = SystemClock.elapsedRealtime()
        val chunk = ByteArray(FIRST_CHUNK_BYTES)
        var chunkSize = FIRST_CHUNK_BYTES
        var written = 0L
        var index = 0
        while (written < MAX_FILL_BYTES) {
            if (SystemClock.elapsedRealtime() - started > FILL_DEADLINE_MS) {
                return FillOutcome(writeFailed = false, bytes = written)
            }
            val file = File(root, "fill-" + index.toString().padStart(5, '0') + ".bin")
            index += 1
            try {
                FileOutputStream(file).use { stream ->
                    stream.write(chunk, 0, chunkSize)
                    stream.flush()
                    stream.fd.sync()
                }
            } catch (failure: IOException) {
                // A10-2: a refused write only counts when the OS reported ENOSPC. A different
                // errno (quota, I/O error, signal interruption) is recorded verbatim and the case
                // SKIPS, because it did not produce the condition under test.
                file.delete()
                if (!isRealEnospc(failure)) {
                    return FillOutcome(writeFailed = false, bytes = written, nonEnospcFailure = describeIoFailure(failure))
                }
                // Shrinking keeps consuming the last blocks, and only the smallest chunk size is
                // accepted as "the disk is really full".
                if (chunkSize > FINAL_CHUNK_BYTES) {
                    chunkSize /= 8
                    continue
                }
                return FillOutcome(writeFailed = true, bytes = written + chunkSize)
            }
            written += chunkSize
        }
        return FillOutcome(writeFailed = false, bytes = written)
    }

    private fun deleteRecursively(root: File): Boolean {
        if (!root.exists()) return true
        root.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) deleteRecursively(child) else child.delete()
        }
        return root.delete()
    }

    private data class FillOutcome(
        val writeFailed: Boolean,
        val bytes: Long,
        /** Set when a write was refused for a reason that is NOT ENOSPC (A10-2). */
        val nonEnospcFailure: String? = null,
    )

    private companion object {
        /** The instrumentation argument that turns the opt-in case on (shared with the import rig). */
        const val OPT_IN_ARGUMENT = "ul.enospc"

        /** The legacy product database name the production startup resolves through the context. */
        const val DATABASE_NAME = "ledger.db"

        /** `PRAGMA quick_check`'s only accepted answer. */
        const val QUICK_CHECK_OK = "ok"

        /**
         * A10-2: the kernel's ENOSPC text (`strerror(ENOSPC)`), used only as the fallback when a
         * layer rethrows a plain `IOException` instead of an `ErrnoException`. The errno check in
         * [isRealEnospc] is authoritative.
         */
        const val ENOSPC_MESSAGE = "No space left on device"

        /**
         * The opaque export token this case pins, so the private staging paths are known exactly and
         * the "no residue" assertion can name them.
         */
        const val TARGET_TOKEN = "backup-export-enospc"

        /** A synthetic password that satisfies the container's 8-code-point minimum. */
        const val PASSWORD = "backup-enospc-device-password"

        /** The handle the injected port reports as the user's SAF pick (never a real document). */
        val TARGET_URI: Uri = Uri.parse("content://com.unifiedledger.test.enospc/target")

        /**
         * The export precheck requires `container + plaintext + 64 MiB` free before it stages
         * anything. A fresh product ledger is far below a megabyte, so this bound only has to beat
         * that formula with room to spare; a device below it cannot begin the vector at all and the
         * case skips instead of failing on an unrelated cause.
         */
        const val MIN_EXPORT_START_BYTES = 128L * 1024 * 1024

        /**
         * "Near full" after a refused write. Exactly zero is too strict: a genuinely full filesystem
         * can still report a few free blocks, and skipping such a run would discard the very
         * evidence this rig exists to produce.
         */
        const val NEAR_FULL_TOLERANCE_BYTES = 64L * 1024

        /** First fill chunk: 4 MiB keeps the loop short on a multi-gigabyte free space. */
        const val FIRST_CHUNK_BYTES = 4 * 1024 * 1024

        /** The smallest chunk worth trying once the disk is nearly full. */
        const val FINAL_CHUNK_BYTES = 4 * 1024

        /** A host with more free space than this cannot be exhausted by the rig; the case skips. */
        const val MAX_FILL_BYTES = 16L * 1024 * 1024 * 1024

        /** Fill wall-clock bound (the case is opt-in and must never hang a session). */
        const val FILL_DEADLINE_MS = 15 * 60 * 1000L
    }
}
