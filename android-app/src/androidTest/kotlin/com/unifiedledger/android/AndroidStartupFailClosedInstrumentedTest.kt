package com.unifiedledger.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unifiedledger.data.createAndroidLedgerDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * P5-04.5-FOUND-001 T-C (D-132 section 5.2): on-device fail-closed evidence for the four frozen
 * paths - normal open, corruption injection, migration-failure injection, permission failure.
 * Each path drives the real production open (the same `createAndroidLedgerDatabase` call that
 * App.kt wires into the startup controller) and asserts the hard acceptance criteria: the open
 * throws (fail-closed) and the original database file is preserved byte-for-byte.
 *
 * The instrumented tests deliberately use public cross-module API only. The exception-to-
 * StartupError mapping itself stays pinned by the JVM `AndroidStartupControllerTest` (T-B/T-D),
 * whose injected-failure tests cover exactly the exceptions these paths produce on device; the
 * on-device new evidence is that the open throws instead of silently rebuilding (the D-130
 * FOUND-001 defect). Run manually on the managed emulator as gate evidence; CI keeps zero
 * connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStartupFailClosedInstrumentedTest {
    private val createdDatabases = mutableListOf<String>()

    @After
    fun cleanUpInjectedDatabases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in createdDatabases) {
            // Test-injected files only; deleteDatabase also removes the -wal/-shm companions.
            context.deleteDatabase(name)
        }
        createdDatabases.clear()
    }

    @Test
    fun normalOpenSucceedsAndLeavesTheDatabaseFileUntouched() {
        val dbFile = databaseFile("found001-normal.db")

        // First open creates the v27 schema; the fresh empty ledger answers its first query.
        createAndroidLedgerDatabase(context(), "found001-normal.db").use { handle ->
            assertNull(
                handle
                    .database
                    .ledgerQueries
                    .selectCommittedRequest(
                        ledger_id = "ledger-found001-a",
                        request_id = "request-found001-a",
                    ).executeAsOneOrNull(),
            )
        }

        val hashBefore = sha256(dbFile)

        // Healthy startup path (onCreate skipped, onConfigure/onOpen run); the controller maps
        // this open success to Ready (pinned by AndroidStartupControllerTest).
        createAndroidLedgerDatabase(context(), "found001-normal.db").use { handle ->
            assertNull(
                handle
                    .database
                    .ledgerQueries
                    .selectCommittedRequest(
                        ledger_id = "ledger-found001-a",
                        request_id = "request-found001-a",
                    ).executeAsOneOrNull(),
            )
        }

        assertEquals(hashBefore, sha256(dbFile))
    }

    @Test
    fun corruptedFileFailsClosedAndIsPreservedByteForByte() {
        val dbFile = databaseFile("found001-corrupt.db")
        // D-130 field-injection shape: non-SQLite bytes where the ledger database should be.
        val corruptBytes =
            "UnifiedLedger FOUND-001 corruption injection: not a SQLite database"
                .toByteArray(Charsets.US_ASCII)
        dbFile.writeBytes(corruptBytes)
        val hashBefore = sha256(dbFile)
        val companionsBefore = companionSnapshot(dbFile)

        assertOpenFailsClosed("found001-corrupt.db")

        // FOUND-001 direct counter-evidence: the main file is still exactly the corrupt bytes we
        // injected - no delete, no rebuild of an empty ledger, no rename (D-2). The -wal/-shm
        // companions (D-3) are preserved too; they may legitimately exist or not at injection
        // time, so only companions present before the failed open are asserted.
        assertEquals(hashBefore, sha256(dbFile))
        assertCompanionsPreserved(companionsBefore)
    }

    @Test
    fun incompatibleSchemaFailsClosedAndIsPreservedByteForByte() {
        val dbFile = databaseFile("found001-migration.db")
        // Migration-failure injection: an empty foreign database stamped at user_version 26.
        // The v26 -> v27 migration (26.sqm) aborts on its first pre-guard read ("no such table:
        // evidence_projection") inside one outer transaction, so the rollback restores the file
        // bytes exactly; a lower user_version with an incomplete schema is the frozen T-C path 3.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { foreign ->
            foreign.version = 26
        }
        val hashBefore = sha256(dbFile)

        assertOpenFailsClosed("found001-migration.db")

        assertEquals(hashBefore, sha256(dbFile))
    }

    @Test
    fun unreadableFileFailsClosedAndKeepsTheFileInPlace() {
        val dbFile = databaseFile("found001-permission.db")
        // Healthy seed state through the real open path: the A-1 eager probe inside the factory
        // forces the open, and the seed ledger answers its first query like the normal path.
        createAndroidLedgerDatabase(context(), "found001-permission.db").use { handle ->
            assertNull(
                handle
                    .database
                    .ledgerQueries
                    .selectCommittedRequest(
                        ledger_id = "ledger-found001-d",
                        request_id = "request-found001-d",
                    ).executeAsOneOrNull(),
            )
        }
        val hashBefore = sha256(dbFile)
        // Permission-failure mechanism (documented for T-C path 4): POSIX mode bits. The app
        // process owns the file, so clearing every read bit makes SQLite's open fail with
        // EACCES. An in-process exclusive SQLite lock cannot block a second connection of the
        // same process (POSIX locks are per-process), so mode bits are the deterministic
        // mechanism on an emulator.
        assertTrue(dbFile.setReadable(false, false))

        assertOpenFailsClosed("found001-permission.db")

        // Under restricted permission the frozen criterion is existence plus invariance: the
        // file is still at its original location with its original bytes (read back after the
        // test-side permission restore, which is not app behaviour).
        assertTrue(dbFile.exists())
        assertTrue(dbFile.setReadable(true, false))
        assertEquals(hashBefore, sha256(dbFile))
    }

    /**
     * The fail-closed core assertion: the factory call itself must throw. The A-1 eager probe
     * runs inside [createAndroidLedgerDatabase] before any handle exists, so a pre-return throw
     * leaves no handle to close and no open file descriptor. Returning a handle would mean
     * androidx silently deleted the original file and rebuilt an empty ledger - the exact D-130
     * defect this batch closes. Any exception type satisfies the assertion (the retried open
     * surfaces the original SQLiteException; the fixed corruption override type may also
     * surface), because the controller mapping is type-independent by D-5.
     */
    private fun assertOpenFailsClosed(name: String) {
        assertThrows(Exception::class.java) {
            createAndroidLedgerDatabase(context(), name)
        }
    }

    /**
     * D-3 companion snapshot: existence and bytes of the -wal/-shm files next to [mainFile].
     * A null value records that the companion legitimately did not exist at snapshot time.
     */
    private fun companionSnapshot(mainFile: File): Map<String, ByteArray?> =
        listOf("-wal", "-shm").associate { suffix ->
            val file = File(mainFile.path + suffix)
            file.path to (if (file.exists()) file.readBytes() else null)
        }

    /** Companions present in [before] must still exist with identical bytes after the failure. */
    private fun assertCompanionsPreserved(before: Map<String, ByteArray?>) {
        for ((path, bytes) in before) {
            if (bytes == null) {
                continue
            }
            val file = File(path)
            assertTrue(file.exists())
            assertTrue(file.readBytes().contentEquals(bytes))
        }
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun databaseFile(name: String): File {
        createdDatabases += name
        val file = context().getDatabasePath(name)
        file.parentFile?.mkdirs()
        return file
    }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
