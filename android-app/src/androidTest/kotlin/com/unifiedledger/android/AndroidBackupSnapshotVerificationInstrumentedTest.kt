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
    private val createdFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        for (file in createdFiles) {
            file.delete()
        }
        createdFiles.clear()
    }

    @Test
    fun aValidSnapshotWithProductUserVersionVerifiesWithoutThrowing() {
        val snapshot = createSnapshot(userVersion = 31)

        // The regression: the old driver-with-version-0 path threw at construction. This call must
        // return normally and report the snapshot self-consistent.
        val verification = verifyAndroidSnapshotFile(snapshot.absolutePath)

        assertTrue("a freshly vacuumed snapshot must pass integrity_check", verification.integrityOk)
        assertEquals("the snapshot's user_version must be read as the header hint", 31L, verification.schemaVersion)
    }

    @Test
    fun verificationNeverMigratesOrWritesTheSnapshot() {
        val snapshot = createSnapshot(userVersion = 31)
        val before = snapshot.readBytes()

        verifyAndroidSnapshotFile(snapshot.absolutePath)

        // The verification is read-only in effect: the file is byte-identical afterwards (no
        // create, no migrate, no journal sidecar left claiming a write).
        assertTrue("the snapshot must be byte-identical after verification", before.contentEquals(snapshot.readBytes()))
        assertFalse("verification must not leave a -journal sidecar", File(snapshot.parentFile, snapshot.name + "-journal").exists())
    }

    @Test
    fun aCorruptSnapshotIsReportedRatherThanSilentlyAccepted() {
        val bogus = File(tempDir(), "bogus-snapshot").apply { writeBytes(ByteArray(4096) { 0x5A }) }
        createdFiles += bogus

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
     * Produces a real SQLite file carrying [userVersion] at a path with NO path separator in a
     * relative name concern (it is an absolute path, exactly what the verification opens). The
     * verification under test only needs a valid SQLite file with the product `user_version`; the
     * production snapshot mechanism (`VACUUM INTO`) is covered by the JVM driver test and the
     * spec's registered on-device item, not here.
     */
    private fun createSnapshot(userVersion: Int): File {
        val snapshot = File(tempDir(), "snapshot-${System.nanoTime()}.db")
        createdFiles += snapshot
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

    private fun tempDir(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "p706b-snapshot").apply {
            mkdirs()
        }
}
