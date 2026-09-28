package com.unifiedledger.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.data.verifyAndroidSnapshotFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P7-06 06.B (D-177; spec section 3.4): on-device regression guard for the Android snapshot
 * verification surface.
 *
 * The first 06.B draft opened the snapshot through `AndroidSqliteDriver(schema, context, name)` with
 * a `NoOpSnapshotSchema` whose `version = 0L`. That path constructs
 * `androidx.sqlite.db.SupportSQLiteOpenHelper$Callback(version)`, and the AOSP `SQLiteOpenHelper`
 * private constructor throws `IllegalArgumentException("Version must be >= 1, was 0")` for any
 * `version < 1` (AOSP `android/database/sqlite/SQLiteOpenHelper.java`, the private constructor's
 * version guard) — at CONSTRUCTION, before the first `PRAGMA integrity_check`, so every Android
 * export failed. The name form was also a relative path under `databases/`, which
 * `Context.getDatabasePath` rejects.
 *
 * [verifyAndroidSnapshotFile] now opens the ABSOLUTE path read-only through the framework
 * `SQLiteDatabase` (no helper, no schema/version logic, no create/migrate). This test produces a
 * real snapshot file with the product's `user_version` (31) and asserts the verification opens it,
 * reports `integrity_check = ok`, reads the version hint and never migrates or writes it.
 *
 * Data safety: everything runs in the instrumentation target's cache dir; the production
 * `databases/` tree is never touched.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBackupSnapshotVerificationInstrumentedTest {
    private val createdDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        for (dir in createdDirs) {
            dir.deleteRecursively()
        }
        createdDirs.clear()
    }

    @Test
    fun aValidSnapshotWithProductUserVersionVerifiesWithoutThrowing() {
        val snapshot = createSnapshot(newTestDir(), userVersion = 31)

        // The regression: the old driver-with-version-0 path threw at construction. This call must
        // return normally and report the snapshot self-consistent.
        val verification = verifyAndroidSnapshotFile(snapshot.absolutePath)

        assertTrue("a freshly vacuumed snapshot must pass integrity_check", verification.integrityOk)
        assertEquals("the snapshot's user_version must be read as the header hint", 31L, verification.schemaVersion)
    }

    @Test
    fun verificationNeverMigratesOrWritesTheSnapshot() {
        // Each test owns a dedicated directory (see [newTestDir]) instead of sharing one
        // timestamped parent, so the "did verification add anything?" check below cannot be
        // polluted by a sibling test or by the shared cache parent.
        val dir = newTestDir()
        val snapshot = createSnapshot(dir, userVersion = 31)

        // Precondition, made explicit: the SETUP's own read-WRITE framework open
        // (`SQLiteDatabase.openOrCreateDatabase` in [createSnapshot]) is what leaves a 0-byte
        // rollback-journal sidecar (`<name>-journal`) beside the snapshot on Android, even after
        // `close()` — the same behaviour the app's real `AndroidSqliteDriver` database shows on
        // device. That sidecar is a SETUP artifact, not a verification artifact, so remove it here
        // before taking the "before" listing. Without this, verification could recreate a
        // same-named sidecar and the name-set comparison below would not notice, because the name
        // was already present. `delete()` returning false when no sidecar exists is fine: the
        // precondition is "the setup is finished and its sidecars are gone", not "a sidecar
        // existed".
        File(dir, snapshot.name + "-journal").delete()

        // The "before" listing is taken only after the setup fully completes and its sidecar is
        // removed, so every name that appears afterwards is attributable to the verification.
        val beforeNames =
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .toSortedSet()
        val beforeBytes = snapshot.readBytes()

        verifyAndroidSnapshotFile(snapshot.absolutePath)

        // The verification is read-only in effect: it added nothing to the directory (no
        // `-journal`, `-wal` or `-shm` sidecar, no new file at all) and the snapshot bytes are
        // unchanged (no create, no migrate, no in-place write).
        val afterNames =
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .toSortedSet()
        assertEquals("verification must add no file to the snapshot directory", beforeNames, afterNames)
        assertTrue("the snapshot must be byte-identical after verification", beforeBytes.contentEquals(snapshot.readBytes()))
    }

    @Test
    fun aCorruptSnapshotIsReportedRatherThanSilentlyAccepted() {
        val bogus = File(newTestDir(), "bogus-snapshot").apply { writeBytes(ByteArray(4096) { 0x5A }) }

        // A non-database file either throws or reports not-ok; what it must NEVER do is crash with
        // the old "Version must be >= 1, was 0" construction error.
        val verification = runCatching { verifyAndroidSnapshotFile(bogus.absolutePath) }
        val failure = verification.exceptionOrNull()
        if (failure != null) {
            assertFalse(
                "the old version-0 construction defect must not recur",
                failure.message?.contains("Version must be >= 1") == true,
            )
        } else {
            assertFalse(verification.getOrThrow().integrityOk)
        }
    }

    @Test
    fun aCorruptSnapshotIsNeverDeletedByVerification() {
        // DG-1 (D-183): verifyAndroidSnapshotFile used to open with the 3-argument
        // SQLiteDatabase.openDatabase(path, null, OPEN_READONLY) overload, whose null error handler
        // the constructor replaces with new DefaultDatabaseErrorHandler() (AOSP SQLiteDatabase.java
        // :493); that default's onCorruption CLOSES then DELETES the file (AOSP
        // DefaultDatabaseErrorHandler.java:53-108). So verifying a CORRUPT export snapshot deleted
        // it — the same destructive defect the 06.D isolated opens fixed. The open now passes the
        // shared non-deleting PreservingIsolatedDatabaseErrorHandler, so the snapshot must survive
        // BYTE-IDENTICAL whether verification throws (typed corruption signal) or reports not-ok.
        //
        // F-3 (measured ablation): the fixture must make SQLite discover the corruption while
        // STEPPING rows, not while PREPARING the statement.
        //  - `PRAGMA integrity_check` on a file whose PAGE 1 is already corrupt (a header-less
        //    `ByteArray(4096) { 0x5A }`, `"SQLite format 3\u0000"` plus garbage, or even a valid
        //    header with the rest overwritten) fails during statement COMPILE. Measured on the API 36
        //    device: the exception says "..., while compiling: PRAGMA integrity_check" and the stack
        //    is `SQLiteConnection.nativePrepareStatement <- SQLiteConnection$PreparedStatementCache
        //    .createStatement`. `SQLiteProgram`'s constructor has no `SQLiteDatabaseCorruptException`
        //    catch, so the throw passes straight out and `SQLiteDatabase.onCorruption()` — hence the
        //    error handler — is NEVER invoked (measured: a custom counting handler stayed at 0 and the
        //    file survived even with the default handler installed). Such a fixture therefore cannot
        //    discriminate the handler fix.
        //  - The callback fires only for corruption reached at STEP time: `SQLiteQuery.fillWindow`
        //    catches `SQLiteDatabaseCorruptException` and calls `onCorruption()` (AOSP
        //    SQLiteQuery.java:66-68). So the fixture keeps PAGE 1 (header + `sqlite_master` root)
        //    byte-INTACT and corrupts page 2 onward: the statement compiles, then faults while
        //    scanning the corrupt pages, which is exactly the callback the reverted null-handler
        //    open's `DefaultDatabaseErrorHandler` answers by DELETING the file (measured: handler
        //    count 1, `DefaultDatabaseErrorHandler: Corruption reported by sqlite` then `deleting the
        //    database file`).
        //  - The SQLITE_NOTADB(26)-vs-SQLITE_CORRUPT(11) error code is NOT the discriminator: on this
        //    platform BOTH codes surface as `SQLiteDatabaseCorruptException` (measured directly), so
        //    the code number alone changes nothing. What changes the outcome is the detection PHASE.
        val dir = newTestDir()
        val bogus = createPageOneIntactCorruptSnapshot(dir)
        val beforeBytes = bogus.readBytes()

        val outcome = runCatching { verifyAndroidSnapshotFile(bogus.absolutePath) }

        // The failure must be SURFACED (a thrown typed failure or a not-ok report): silently
        // accepting a corrupt snapshot is the other half of the defect.
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            assertFalse(
                "a corrupt snapshot must be surfaced, not reported ok",
                failure.message?.contains("Version must be >= 1") == true,
            )
        } else {
            assertFalse("a corrupt snapshot must never verify ok", outcome.getOrThrow().integrityOk)
        }

        // The load-bearing device pin: the corrupt fixture is still there, byte-identical, and no
        // sidecar or rewritten file appeared. Reverting to the 3-argument null-handler open deletes
        // this fixture during the platform's corruption callback and turns this assertion RED.
        assertTrue("a corrupt snapshot must never be deleted by verification", bogus.exists())
        assertArrayEquals(
            "verification must not rewrite the corrupt snapshot",
            beforeBytes,
            bogus.readBytes(),
        )
    }

    /**
     * A snapshot whose page 1 (the database header plus the `sqlite_master` root, which is what
     * statement PREPARE reads) is byte-INTACT, but whose every later page is corrupted (F-3). This is
     * the only shape that reaches the platform corruption CALLBACK under the read-only open used by
     * [verifyAndroidSnapshotFile]:
     *
     * - The header parses (valid magic, page size and format versions), so the open and the
     *   `PRAGMA integrity_check` compile both succeed.
     * - `PRAGMA integrity_check` then faults while STEPPING onto the corrupt pages.
     *   `SQLiteQuery.fillWindow` catches the resulting `SQLiteDatabaseCorruptException` and calls
     *   `onCorruption()` (AOSP SQLiteQuery.java:66-68) — the callback the reverted null-handler
     *   open's `DefaultDatabaseErrorHandler` answers by deleting the file.
     *
     * A header-only or magic-only image instead fails while the statement is COMPILED (its corruption
     * is in page 1), and `SQLiteProgram`'s constructor has no corruption catch, so no callback and no
     * deletion would ever happen: such a fixture cannot discriminate the handler fix. The seed is
     * therefore made multi-page, and only the bytes from the first page boundary onward are
     * overwritten.
     */
    private fun createPageOneIntactCorruptSnapshot(dir: File): File {
        val seed = createMultiPageSnapshot(dir, userVersion = 31)
        val corrupt = File(dir, "bogus-snapshot")
        val corruptBytes = seed.readBytes()
        // Self-check (P2-3, D-183 record-fix): the seed MUST spill past page 1, otherwise the loop
        // below would corrupt nothing and the fixture would silently stop reaching the step-time
        // corruption callback, de-pinning the device test. A future filler reduction that makes the
        // seed fit in one page turns this RED instead of silently weakening the pin.
        assertTrue(
            "the corrupt fixture must span more than one page",
            corruptBytes.size > pageSizeBytes(corruptBytes),
        )
        for (index in pageSizeBytes(corruptBytes) until corruptBytes.size) {
            corruptBytes[index] = 0x7F
        }
        corrupt.writeBytes(corruptBytes)
        seed.delete()
        File(dir, seed.name + "-journal").delete()
        return corrupt
    }

    /** The page size recorded in the header (bytes 16-17), per the SQLite file-format spec 1.1. */
    private fun pageSizeBytes(header: ByteArray): Int {
        val encoded = ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
        return if (encoded == 1) 65536 else encoded
    }

    /**
     * A real SQLite file carrying [userVersion] whose rows spill over several pages, so that
     * overwriting everything after page 1 leaves page 1 (header and schema) valid.
     */
    private fun createMultiPageSnapshot(
        dir: File,
        userVersion: Int,
    ): File {
        val snapshot = File(dir, "snapshot-${System.nanoTime()}.db")
        val database = SQLiteDatabase.openOrCreateDatabase(snapshot, null)
        try {
            database.execSQL("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            val filler = "X".repeat(6000)
            for (row in 1..40) {
                database.execSQL("INSERT INTO sample (value) VALUES ('row-$row-$filler')")
            }
            database.execSQL("PRAGMA user_version = $userVersion")
        } finally {
            database.close()
        }
        return snapshot
    }

    /**
     * Produces a real SQLite file carrying [userVersion] in [dir]. The verification under test only
     * needs a valid SQLite file with the product `user_version`; the production snapshot mechanism
     * (`VACUUM INTO`) is covered by the JVM driver test and the spec's registered on-device item,
     * not here.
     */
    private fun createSnapshot(
        dir: File,
        userVersion: Int,
    ): File {
        val snapshot = File(dir, "snapshot-${System.nanoTime()}.db")
        val database = SQLiteDatabase.openOrCreateDatabase(snapshot, null)
        try {
            database.execSQL("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            database.execSQL("INSERT INTO sample (value) VALUES ('kept')")
            database.execSQL("PRAGMA user_version = $userVersion")
        } finally {
            database.close()
        }
        return snapshot
    }

    /**
     * A dedicated per-test directory under the instrumentation target's cache dir. Each test gets
     * its own directory so a directory-listing assertion observes only that test's own artifacts;
     * the shared `p706b-snapshot` parent is not used as the listing root.
     */
    private fun newTestDir(): File {
        val dir =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "p706b-snapshot/test-${System.nanoTime()}",
            )
        dir.mkdirs()
        createdDirs += dir
        return dir
    }
}
