package com.unifiedledger.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.data.verifyAndroidSnapshotFile
import org.junit.After
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
