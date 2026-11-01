package com.unifiedledger.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.application.ImportFileIntakeInput
import com.unifiedledger.application.ImportFileIntakeOutcome
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportIntakeBatchFailure
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.ledgerStorageLayout
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

/**
 * DEF-IMPORT-ENOSPC-CRASH-01 device rig (D-276 item 2): a REAL out-of-space condition, produced by
 * the device itself, on the REAL intake commit path.
 *
 * Why it exists
 * -------------
 * D-276 item 2 recorded the defect on a device: at `/data` 100 % a 10,000-row import died with
 * `SQLiteFullException` out of `SqlDelightImportSpineStore.commitIntake`, i.e. an uncaught exception
 * took the process down and the UI never got a receipt. The JVM suites pin the SHAPE of the fix
 * (the typed `ImportIntakeBatchFailure.StorageUnavailable`, the stop-the-dispatch rule, the fixed
 * copy), but they can only fake the failure. Only a device can produce a genuine `ENOSPC` and show
 * that the product path converts it into a typed result instead of a fatal crash. This class is
 * that rig.
 *
 * What it drives and asserts
 * --------------------------
 * 1. The real production startup (`openAndroidStableStorageLedger`, the ordinary `FreshInstall`
 *    path) over a redirected host directory, so gen-1 and its active pointer exist BEFORE the
 *    disk fills.
 * 2. The real intake orchestration reached exactly as the host reaches it: `facade.importFileIntake`
 *    (`JvmImportFileIntake` + `ExecuteImportIntake` + the real `SqlDelightImportSpineStore`) over a
 *    real, fully anonymous, synthetic CMB CSV built in memory — no user file, no fixture on disk.
 * 3. A controlled fill of the app cache until the filesystem itself refuses a write, so the commit
 *    below runs against a genuinely full `/data` (a "near full" reading is accepted — see
 *    [NEAR_FULL_TOLERANCE_BYTES] — and the batch is sized so it still cannot commit).
 * 4. Assertions, in this order: the call RETURNS (the process is alive — reaching the next
 *    assertion at all is the crash half), the result is
 *    `ImportFileIntakeOutcome.Rejected(ImportIntakeBatchFailure.StorageUnavailable)`, the failure
 *    carries no detail payload, the active pointer and the generation set are unchanged, and after
 *    releasing the fill the ledger's own `PRAGMA quick_check` is `ok` with NO HALF-WRITTEN evidence.
 *    Ruling B allows a batch to commit a PREFIX, so this rig never asserts that the import tables
 *    are empty: completeness equalities and an empty `PRAGMA foreign_key_check` are the claim, and
 *    every row the review list showed must belong to a committed candidate. If `quick_check` fails,
 *    a real startup is run once to prove the host still resolves gen-1 (never a FreshInstall) before
 *    the case fails, so corruption and data loss stay distinguishable.
 *
 * Data safety (the MUST-FIX-A discipline of `AndroidRestoreRecoveryInstrumentedTest`)
 * ----------------------------------------------------------------------------------
 * The rig NEVER touches the user's production ledger. The target context is wrapped so
 * `getDatabasePath("ledger.db")` resolves inside a fresh directory under `filesDir`; the guard in
 * [redirectedContext] fails the test unless the redirection is really in effect. The fill lives in
 * a fresh directory under `cacheDir` and holds only empty synthetic chunks. Both directories are
 * created by this test, recorded in [createdRoots], and removed in `finally`.
 *
 * Hazards the operator must know
 * ------------------------------
 * - While the fill is held, `/data` is genuinely full: other apps on that device may misbehave, and
 *   any system write can fail. Run it on a disposable emulator, never on a phone with real data.
 * - The fill is released INSIDE the test body (step 7) before the method returns, so the runner can
 *   still write its own result files; `finally` releases it again on every failure path.
 * - If the process is killed mid-test, the fill directory survives and the disk stays full. Recovery:
 *   `adb shell pm clear com.unifiedledger.android` (or delete
 *   `cache/enospc-fill-*` under the app's private dir with `run-as`).
 * - The rig SKIPS (never passes) when the fill cannot exhaust the filesystem: a host with more free
 *   space than [MAX_FILL_BYTES] proves nothing about ENOSPC.
 *
 * Opt-in only, never in the ordinary device regression
 * ----------------------------------------------------
 * The single case runs only when the instrumentation argument [OPT_IN_ARGUMENT] is `1`; without it
 * JUnit reports it skipped. Turning it into a real run:
 *
 * ```
 * adb shell am instrument -w -e ul.enospc 1 \
 *   -e class com.unifiedledger.android.ImportEnospcInstrumentedTest \
 *   com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Adding this class also REQUIRES the two exclusions that keep the ordinary 41-case inventory
 * exact — `tools/python/android_scale/inventory.py` `EXCLUDED` and the `notClass` list in
 * `.github/workflows/android-instrumented.yml`. Without both, the CI job's source-inventory check
 * (or its post-run XML check) fails on the extra class. This batch adds both dedicated exclusions
 * in the same change.
 */
@RunWith(AndroidJUnit4::class)
class ImportEnospcInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val createdRoots = mutableListOf<File>()

    @Before
    fun requireExplicitOptIn() {
        val arguments = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(
            "opt-in only: pass -e $OPT_IN_ARGUMENT 1 and -e class com.unifiedledger.android.ImportEnospcInstrumentedTest " +
                "to the instrumentation runner; the ordinary device regression excludes this class",
            arguments.getString(OPT_IN_ARGUMENT) == "1",
        )
    }

    @Test
    fun aRealEnospcOnTheIntakeCommitIsTypedAndLeavesTheLedgerIntact() {
        val redirectRoot = newRoot(targetContext.filesDir, "enospc-rig")
        val fillRoot = newRoot(targetContext.cacheDir, "enospc-fill")
        val context = redirectedContext(redirectRoot)
        val layout = layoutOver(context)
        var graph: CloseableLedgerGraph? = null
        try {
            // 1. The real production startup over the redirected host. This is the ordinary
            // FreshInstall plan (rule 3), so the generation directory, the database file and the
            // active pointer all exist before a single byte of the disk is spent on the fill.
            val opened =
                openAndroidStableStorageLedger(
                    context,
                    AndroidImportFilePickPort<Uri>(
                        launchOpenDocument = { error("the ENOSPC rig never launches a picker") },
                        resolveMetadata = { error("the ENOSPC rig never resolves a pick") },
                        openInputStream = { error("the ENOSPC rig never opens a pick stream") },
                        onResult = { error("the ENOSPC rig never receives a pick result") },
                    ),
                    ImportFilePickResultChannel(),
                )
            graph = opened
            val facade = opened.facade
            val ledgerId = facade.ledgerId

            // 2. Baseline, taken while space is still available: this is the state the failed
            // commit must leave untouched.
            val pointerBefore = readActivePointer(layout)
            val generationsBefore = generationNames(layout)
            assertEquals("the redirected fresh install must publish gen-1", "gen-1", pointerBefore)
            assertEquals("the fresh install must create exactly one generation", listOf("gen-1"), generationsBefore)
            val mainFile = mainFileOf(layout, pointerBefore)
            assertTrue("the generation main file must exist: $mainFile", mainFile.isFile)
            assertNoHalfWrittenEvidence(mainFile)
            assertEquals("the fresh ledger must be structurally clean", QUICK_CHECK_OK, quickCheck(mainFile))
            assertEquals("the live review list must be empty before the failed import", 0, reviewRowCount(facade, ledgerId))

            // 3. Controlled fill of the app cache until the filesystem itself refuses a write.
            // Only empty synthetic chunks are written; nothing private is involved.
            val freeBefore = availableBytes()
            val fill = fillUntilEnospc(fillRoot)
            val freeAfter = availableBytes()
            Assume.assumeTrue(
                "the rig needs a REAL ENOSPC and this host did not reach one: wrote=${fill.bytes} bytes, " +
                    "writeFailed=${fill.writeFailed}, free before=$freeBefore after=$freeAfter, " +
                    "cap=${MAX_FILL_BYTES} bytes, near-full bound=${NEAR_FULL_TOLERANCE_BYTES} bytes. " +
                    "A host with more free space than the cap cannot run this probe.",
                fill.writeFailed && freeAfter <= NEAR_FULL_TOLERANCE_BYTES,
            )
            assertTrue("the fill must actually hold bytes on disk (wrote=${fill.bytes})", fill.bytes > 0L)

            // 4. The real product import over a real anonymous CSV, on a genuinely full disk.
            val session =
                checkNotNull(facade.importIntakeSessionFactory()) {
                    "the product intake session factory must be wired by the composition root"
                }
            val intake =
                checkNotNull(facade.importFileIntake) {
                    "the product file intake must be wired by the composition root"
                }
            val outcome =
                intake.intake(
                    ImportFileIntakeInput(
                        format = ImportFormatCapabilities.CMB_CSV.identifier,
                        platform = ImportPlatformKind.ANDROID,
                        session = session,
                        bytes = anonymousCmbCsv(rows = ANONYMOUS_CSV_ROWS),
                    ),
                )

            // 5. The crash half and the typed half. Reaching this line proves the process survived
            // the failed commit (D-276 item 2: no FATAL EXCEPTION); the assertions prove the escape
            // is now a typed, payload-free batch failure.
            assertTrue(
                "a full disk must produce a typed outcome, never an escaping exception or Accepted: $outcome",
                outcome is ImportFileIntakeOutcome.Rejected,
            )
            val rejected = outcome as ImportFileIntakeOutcome.Rejected
            assertEquals(
                "the failed commit must surface as the payload-free storage failure",
                ImportIntakeBatchFailure.StorageUnavailable,
                rejected.failure,
            )
            assertEquals(
                "the failure must carry no message, cause, or exception class (D06)",
                "StorageUnavailable",
                rejected.failure.toString(),
            )

            // 6. Still full: the ledger's own layout was not rewritten and the live read still
            // answers. Ruling B allows the batch to commit a PREFIX, so the committed row count is
            // deliberately NOT asserted to be zero here — completeness is what "no half-written
            // evidence" means, and it is checked once the fill is released.
            assertEquals("the failed commit must not replace the active pointer", pointerBefore, readActivePointer(layout))
            assertEquals("the failed commit must not add or drop a generation", generationsBefore, generationNames(layout))
            val visibleAfterFailure = reviewRowCount(facade, ledgerId)

            // 7. Release the fill: the disk must come back, and the ledger must re-read clean —
            // no half-written candidate, no residue from the aborted transaction.
            assertTrue("the fill directory must be removable", deleteRecursively(fillRoot))
            val freeRecovered = availableBytes()
            assertTrue(
                "the disk must regain space once the fill is released (full=$freeAfter, recovered=$freeRecovered)",
                freeRecovered > freeAfter,
            )
            assertNoHalfWrittenEvidence(mainFile)
            val committedCandidates = withLedgerDatabase(mainFile) { database -> rowCount(database, "import_candidate") }
            assertTrue(
                "every row the review list showed must belong to a committed candidate " +
                    "(visible=$visibleAfterFailure, committed=$committedCandidates)",
                visibleAfterFailure.toLong() <= committedCandidates,
            )
            // Integrity: quick_check must pass. If it does not, prove FIRST that a real startup still
            // resolves the existing gen-1 (never a FreshInstall), then fail — so a corruption finding
            // is reported as corruption, not as data loss.
            val quickCheckAfter = runCatching { quickCheck(mainFile) }
            if (quickCheckAfter.getOrNull() != QUICK_CHECK_OK) {
                opened.close()
                graph = null
                val reopen = runCatching { assertReopensOnGen1(context, layout) }
                throw AssertionError(
                    "PRAGMA quick_check must stay ok after the aborted commit " +
                        "(quick_check=${quickCheckAfter.exceptionOrNull() ?: quickCheckAfter.getOrNull()}, " +
                        "reopen-on-gen-1=${reopen.exceptionOrNull()?.message ?: "ok"})",
                )
            }

            // 8. Teardown inside the happy path: close the graph first, then remove the rig.
            opened.close()
            graph = null
            assertTrue("the redirected database directory must be removable", deleteRecursively(redirectRoot))
            assertFalse("the redirected database directory must be gone", redirectRoot.exists())
            assertFalse("the fill directory must be gone", fillRoot.exists())
        } finally {
            // Teardown must be unconditional: a failed assertion above must never leave the
            // device with a full disk or an orphaned redirected ledger.
            runCatching { graph?.close() }
            createdRoots.forEach { deleteRecursively(it) }
            createdRoots.clear()
        }
    }

    // ------------------------------------------------------------------ context / layout helpers

    /**
     * Every relative database name resolves inside [root]; ABSOLUTE names pass through unchanged,
     * because that is exactly what the production startup hands to the driver (`ContextImpl` treats
     * a leading separator as an already-resolved path, and the defect-1 guard depends on it).
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

    private fun layoutOver(context: Context): LedgerStorageLayout {
        val (hostDirectory, _) = androidStableStoragePaths(context.getDatabasePath(DATABASE_NAME))
        return ledgerStorageLayout(AndroidLedgerFileSystem(), hostDirectory)
    }

    private fun newRoot(
        parent: File,
        prefix: String,
    ): File {
        val root = File(parent, "$prefix-${System.nanoTime()}")
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

    /**
     * The "no half-written evidence" invariants of one intake attempt. Ruling B allows the batch to
     * commit a PREFIX, so nothing here asserts a total: the claim is that every committed record is
     * COMPLETE — a source record keeps its request claim, its evidence, its candidate and that
     * candidate's initial status history — and that no foreign key dangles. A record applied halfway
     * through the transaction breaks exactly these equalities.
     */
    private fun assertNoHalfWrittenEvidence(mainFile: File) {
        withLedgerDatabase(mainFile) { database ->
            assertEquals(
                "every committed source record must keep its request claim",
                rowCount(database, "import_source_record"),
                rowCount(database, "import_request"),
            )
            assertEquals(
                "every committed source record must keep its evidence",
                rowCount(database, "import_source_record"),
                rowCount(database, "import_evidence"),
            )
            assertEquals(
                "every committed source record must keep its candidate",
                rowCount(database, "import_source_record"),
                rowCount(database, "import_candidate"),
            )
            assertEquals(
                "every committed candidate must keep its initial status history",
                rowCount(database, "import_candidate"),
                rowCount(database, "import_candidate_status_history"),
            )
            database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("no foreign key may dangle after the aborted commit", cursor.moveToFirst())
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

    private fun quickCheck(mainFile: File): String {
        var result = ""
        withLedgerDatabase(mainFile) { database ->
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                assertTrue("PRAGMA quick_check must return a row", cursor.moveToFirst())
                result = cursor.getString(0)
                assertFalse("PRAGMA quick_check must return exactly one row", cursor.moveToNext())
            }
        }
        return result
    }

    /** The live review read over the graph's own connection: no second database connection. */
    private fun reviewRowCount(
        facade: P503LedgerFacade,
        ledgerId: LedgerId,
    ): Int {
        val query = checkNotNull(facade.queryImportReviewRows) { "the product review read must be wired" }
        return when (val result = query.query(ledgerId)) {
            is ImportReviewRowsResult.Rows -> result.rows.size
            ImportReviewRowsResult.Unavailable ->
                error("the live review read must not report Unavailable while the graph is open")
        }
    }

    /**
     * The integrity diagnostic: a REAL production startup on the same host must land on the
     * existing gen-1. A FreshInstall here would mean the ledger directory itself was lost — a far
     * worse finding than corruption — so the two are separated before the case fails.
     */
    private fun assertReopensOnGen1(
        context: Context,
        layout: LedgerStorageLayout,
    ) {
        val reopened =
            openAndroidStableStorageLedger(
                context,
                AndroidImportFilePickPort<Uri>(
                    launchOpenDocument = { error("the reopen probe never launches a picker") },
                    resolveMetadata = { error("the reopen probe never resolves a pick") },
                    openInputStream = { error("the reopen probe never opens a pick stream") },
                    onResult = { error("the reopen probe never receives a pick result") },
                ),
                ImportFilePickResultChannel(),
            )
        try {
            assertEquals("the reopen must resolve the existing gen-1", "gen-1", readActivePointer(layout))
            assertEquals("the reopen must not create a generation", listOf("gen-1"), generationNames(layout))
            checkNotNull(reopened.facade.queryImportReviewRows) { "the reopened graph must still wire the review read" }
        } finally {
            reopened.close()
        }
    }

    // ------------------------------------------------------------------ the fill

    private fun availableBytes(): Long = StatFs(targetContext.filesDir.absolutePath).availableBytes

    /**
     * Writes synthetic chunks into [root] until the filesystem refuses a write. The chunk size
     * shrinks after a failure so the last free blocks are consumed too, which is what makes a
     * later commit fail at its FIRST page write instead of sneaking one transaction through.
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
                // A refused write is the signal we came for; shrinking keeps consuming the last
                // blocks, and only the smallest chunk size is accepted as "the disk is really full".
                file.delete()
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

    // ------------------------------------------------------------------ the anonymous fixture

    /**
     * A fully synthetic CMB CSV in the frozen parser shape (same header, same quoted columns, same
     * BOM + CRLF framing the JVM intake tests use). Amounts, dates and the 交易类型 token are
     * constants: nothing here is derived from a bill, an account, or a person.
     */
    private fun anonymousCmbCsv(rows: Int): ByteArray {
        val comment = (0..5).map { "\"SYN-CMB-META-$it\"" }
        val header = "\"交易日期\",\"交易时间\",\"收入\",\"支出\",\"余额\",\"交易类型\",\"交易备注\""
        val data =
            (0 until rows).map { index ->
                listOf(
                    "\t202609" + (index + 1).toString().padStart(2, '0'),
                    "\t08:30:00",
                    "",
                    "1.00",
                    "1000000.00",
                    "网联协议支付",
                    "",
                ).joinToString(",") { "\"$it\"" }
            }
        val lines = comment + listOf("\"\"") + listOf(header) + data + listOf("\"\"")
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    private data class FillOutcome(
        val writeFailed: Boolean,
        val bytes: Long,
    )

    private companion object {
        /** The instrumentation argument that turns the opt-in case on. */
        const val OPT_IN_ARGUMENT = "ul.enospc"

        /** The legacy product database name the production startup resolves through the context. */
        const val DATABASE_NAME = "ledger.db"

        /** `PRAGMA quick_check`'s only accepted answer. */
        const val QUICK_CHECK_OK = "ok"

        /**
         * Enough accepted records that one batch cannot possibly commit inside the free space the
         * fill leaves behind: a batch this size needs megabytes of pages and WAL frames, so a
         * "near full" disk (see [NEAR_FULL_TOLERANCE_BYTES]) still fails the commit.
         */
        const val ANONYMOUS_CSV_ROWS = 200

        /**
         * "Near full" after a refused write. Exactly zero is too strict: a genuinely full filesystem
         * can still report a few free blocks, and skipping such a run would discard the very
         * evidence this rig exists to produce. 64 KiB is far below one record's write footprint, so
         * the commit below still cannot succeed.
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
