package com.unifiedledger.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.data.StrictMigrationResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.data.migrateIsolatedSnapshotStrictlyOn
import com.unifiedledger.data.openAndroidReadWriteDriver
import com.unifiedledger.data.readAuthoritativeUserVersionOn
import com.unifiedledger.data.readObservedLedgerIdsOn
import com.unifiedledger.data.validateDomainOn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P7-06 06.C (D-179; spec `2026-09-25-p7-06-restore-preflight-design.md` section 8.2): on-device
 * verification for [openAndroidReadWriteDriver] / `AndroidFrameworkSqlDriver` — the custom,
 * non-create-on-open `SqlDriver` adapter over the framework `SQLiteDatabase` that is the ONLY
 * Android path for the strict restore migration (the spec's preferred `FrameworkSQLiteDatabase`
 * candidate is not on `ledger-data` androidMain's compile classpath). Until this file was added the
 * adapter had ZERO test coverage of any kind; the `UNVERIFIED` registration in
 * `AndroidFrameworkSqlDriver.kt` is discharged only when this suite runs green on a device.
 *
 * Covered on a real framework database at an isolated temp path, through the adapter only:
 *
 * - a transaction that COMMITS (the same `Transacter.transaction` path
 *   `migrateIsolatedSnapshotStrictlyOn` uses) is visible after close + reopen;
 * - a transaction that ROLLS BACK (the block throws, so `setTransactionSuccessful` is never called)
 *   leaves no writes after close + reopen;
 * - the two probe shapes the identity sweep depends on work through the adapter: `PRAGMA
 *   table_info(...)` returns the carrying table's columns, and the real sweep helper
 *   ([readObservedLedgerIdsOn], which scans `sqlite_master` and runs `SELECT DISTINCT ledger_id`
 *   per carrying table) observes the expected identities;
 * - the strict migrate on device: a v1-shaped fixture migrated 1 -> current
 *   (`LedgerDatabase.Schema.version`) in ONE transaction through `Schema.migrate`, then stamped and
 *   validated (integrity/FK/domain) through the adapter.
 *
 * Stated gap (fixture fidelity, not faked): the v1 fixture below is a LOCAL reproduction of the
 * frozen `VERSION_ONE_STATEMENTS` seed (ledger-data jvmTest `LedgerDatabaseMigrationTest`), kept
 * local for the same reason as the desktop port test's copy — that fixture is not on this module's
 * androidTest classpath. The migration CHAIN itself is the real generated one; what this does not
 * prove is golden-level data equivalence of a full production snapshot, which stays with the JVM
 * suite and the spec's registered acceptance runs.
 *
 * Data safety: every database file is created under the instrumentation target's cache dir in a
 * per-test directory (the `p706b-snapshot` precedent); [assertIsolatedFromProduction] refuses to run
 * if the file resolves outside that directory or into the production `databases/` tree. The
 * production ledger is never opened, copied, written or deleted.
 */
@RunWith(AndroidJUnit4::class)
class AndroidFrameworkSqlDriverInstrumentedTest {
    private val createdDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        for (dir in createdDirs) {
            dir.deleteRecursively()
        }
        createdDirs.clear()
    }

    @Test
    fun aCommittedTransactionIsVisibleAfterReopen() {
        val dbFile = newIsolatedDatabaseFile()

        val first = openAndroidReadWriteDriver(dbFile.absolutePath)
        try {
            first.execute(null, "CREATE TABLE probe (value TEXT NOT NULL)", 0, null)
            LedgerDatabase(first).transaction {
                first.execute(null, "INSERT INTO probe (value) VALUES ('kept')", 0, null)
            }
        } finally {
            first.close()
        }

        val second = openAndroidReadWriteDriver(dbFile.absolutePath)
        try {
            assertEquals(listOf("kept"), readFirstColumn(second, "SELECT value FROM probe ORDER BY rowid"))
        } finally {
            second.close()
        }
    }

    @Test
    fun aRolledBackTransactionLeavesNoWritesAfterReopen() {
        val dbFile = newIsolatedDatabaseFile()

        val first = openAndroidReadWriteDriver(dbFile.absolutePath)
        try {
            first.execute(null, "CREATE TABLE probe (value TEXT NOT NULL)", 0, null)
            // Committed baseline first, so the reopen assertion can distinguish "rollback kept the
            // transaction's writes out" from "the file stayed empty".
            LedgerDatabase(first).transaction {
                first.execute(null, "INSERT INTO probe (value) VALUES ('kept')", 0, null)
            }
            // The block throwing is exactly how a failed `Schema.migrate` aborts the strict
            // migration transaction; the adapter must NOT have marked the transaction successful.
            val sentinelEscaped =
                runCatching {
                    LedgerDatabase(first).transaction {
                        first.execute(null, "INSERT INTO probe (value) VALUES ('discarded')", 0, null)
                        throw RollbackSentinel()
                    }
                }.isFailure
            assertTrue("the rollback sentinel must escape the transaction block", sentinelEscaped)
        } finally {
            first.close()
        }

        val second = openAndroidReadWriteDriver(dbFile.absolutePath)
        try {
            assertEquals(
                "only the committed baseline may survive the rollback",
                listOf("kept"),
                readFirstColumn(second, "SELECT value FROM probe ORDER BY rowid"),
            )
        } finally {
            second.close()
        }
    }

    @Test
    fun theIdentitySweepProbesWorkThroughTheAdapter() {
        val dbFile = newIsolatedDatabaseFile()
        val driver = openAndroidReadWriteDriver(dbFile.absolutePath)
        try {
            driver.execute(null, "CREATE TABLE probe_owner (id INTEGER PRIMARY KEY, ledger_id TEXT NOT NULL)", 0, null)
            driver.execute(null, "INSERT INTO probe_owner (id, ledger_id) VALUES (1, 'ledger-a')", 0, null)
            driver.execute(null, "INSERT INTO probe_owner (id, ledger_id) VALUES (2, 'ledger-b')", 0, null)

            // Probe shape 1: `PRAGMA table_info(...)` (the sweep's column probe) returns the carrying
            // table's columns, with the name in column index 1.
            val columns = readFirstColumn(driver, "PRAGMA table_info(\"probe_owner\")", columnIndex = 1)
            assertEquals(setOf("id", "ledger_id"), columns.toSet())

            // Probe shape 2: the REAL sweep helper (sqlite_master scan + `SELECT DISTINCT ledger_id`
            // per carrying table) observes exactly the inserted identities through the adapter.
            assertEquals(setOf("ledger-a", "ledger-b"), readObservedLedgerIdsOn(driver).toSet())
        } finally {
            driver.close()
        }
    }

    @Test
    fun aSupportedV1FixtureStrictMigratesOnDeviceThroughTheAdapter() {
        val dir = newTestDir()
        val dbFile = newIsolatedDatabaseFile(dir)
        // Build the v1 base surface with the frozen-shape seed statements, then let the adapter run
        // the real generated chain 1 -> current in one transaction and stamp the current version.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { fixture ->
            VERSION_ONE_STATEMENTS_DEVICE.forEach(fixture::execSQL)
            fixture.version = 1
        }

        val driver = openAndroidReadWriteDriver(dbFile.absolutePath)
        try {
            val result = migrateIsolatedSnapshotStrictlyOn(driver, 1L, setOf(1L))
            assertTrue("the strict migration must succeed on device: $result", result is StrictMigrationResult.Migrated)
            val migrated = result as StrictMigrationResult.Migrated
            assertEquals(1L, migrated.fromVersion)
            assertEquals(LedgerDatabase.Schema.version, migrated.targetVersion)
            // The in-transaction stamp survived, and the migrated payload validates without any seed
            // bootstrap.
            assertEquals(LedgerDatabase.Schema.version, readAuthoritativeUserVersionOn(driver))
            assertEquals(listOf("ledger-a"), readObservedLedgerIdsOn(driver))
            assertTrue("the migrated payload must pass the no-bootstrap domain validation", validateDomainOn(driver).ok)
        } finally {
            driver.close()
        }
    }

    /**
     * Creates a per-test directory and, inside it, a real zero-page SQLite main file: the adapter
     * opens with `OPEN_READWRITE` and NO `CREATE_IF_NECESSARY`, so the file must already exist.
     */
    private fun newIsolatedDatabaseFile(dir: File = newTestDir()): File {
        val file = File(dir, "restore-driver-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).close()
        assertIsolatedFromProduction(file)
        return file
    }

    /**
     * A dedicated per-test directory under the instrumentation target's cache dir (the 06.B
     * snapshot-verification precedent), so no listing or cleanup can collide with another test.
     */
    private fun newTestDir(): File {
        val dir =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "p706c-driver/test-${System.nanoTime()}",
            )
        dir.mkdirs()
        createdDirs += dir
        return dir
    }

    /**
     * Hard guard: the test database must live under the app-private cache dir and must NOT resolve
     * into the production `databases/` tree, so a broken path helper can never point this test at
     * the real ledger.
     */
    private fun assertIsolatedFromProduction(dbFile: File) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val productionDatabases = target.getDatabasePath("ledger.db").parentFile?.absolutePath
        assertTrue(
            "the test database must be under the cache dir: ${dbFile.absolutePath}",
            dbFile.absolutePath.startsWith(target.cacheDir.absolutePath),
        )
        assertTrue(
            "the test database must not resolve into the production databases directory: ${dbFile.absolutePath}",
            productionDatabases == null || !dbFile.absolutePath.startsWith(productionDatabases),
        )
    }

    /**
     * Reads one column through the adapter into a list, in cursor order. Used for value probes
     * (`SELECT value ...`), column-name probes (`PRAGMA table_info`, name at index 1) and the
     * sweep's own row shape.
     */
    private fun readFirstColumn(
        driver: SqlDriver,
        sql: String,
        columnIndex: Int = 0,
    ): List<String> {
        val values = mutableListOf<String>()
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    while (cursor.next().value) {
                        cursor.getString(columnIndex)?.let { values += it }
                    }
                    QueryResult.Unit
                },
                0,
                null,
            ).value
        return values
    }

    private class RollbackSentinel : RuntimeException("rollback sentinel")

    private companion object {
        /**
         * The v1 base surface for the strict-migrate case: the formal core tables plus one balanced
         * transaction, with the same FOREIGN KEY constraints as the frozen `VERSION_ONE_STATEMENTS`
         * (ledger-data jvmTest `LedgerDatabaseMigrationTest`). Kept local because that fixture is
         * not on this module's androidTest classpath (the desktop port test keeps the same local
         * reproduction).
         */
        val VERSION_ONE_STATEMENTS_DEVICE: List<String> =
            listOf(
                """
                CREATE TABLE ledger_transaction (
                  transaction_id TEXT NOT NULL PRIMARY KEY,
                  ledger_id TEXT NOT NULL,
                  kind TEXT NOT NULL CHECK (kind IN ('OPENING_BALANCE', 'EXPENSE')),
                  UNIQUE (transaction_id, ledger_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE posting_set (
                  posting_set_id TEXT NOT NULL PRIMARY KEY,
                  ledger_id TEXT NOT NULL,
                  UNIQUE (posting_set_id, ledger_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE transaction_version (
                  version_id TEXT NOT NULL PRIMARY KEY,
                  transaction_id TEXT NOT NULL,
                  ledger_id TEXT NOT NULL,
                  version_number INTEGER NOT NULL CHECK (version_number > 0),
                  posting_set_id TEXT NOT NULL,
                  occurred_at TEXT NOT NULL,
                  statistics_at TEXT NOT NULL,
                  effective_at TEXT NOT NULL,
                  note TEXT,
                  UNIQUE (transaction_id, version_number),
                  UNIQUE (transaction_id, version_id, ledger_id),
                  FOREIGN KEY (transaction_id, ledger_id)
                    REFERENCES ledger_transaction(transaction_id, ledger_id)
                    DEFERRABLE INITIALLY DEFERRED,
                  FOREIGN KEY (posting_set_id, ledger_id)
                    REFERENCES posting_set(posting_set_id, ledger_id)
                    DEFERRABLE INITIALLY DEFERRED
                )
                """.trimIndent(),
                """
                CREATE TABLE posting (
                  posting_id TEXT NOT NULL PRIMARY KEY,
                  posting_set_id TEXT NOT NULL,
                  ledger_id TEXT NOT NULL,
                  posting_index INTEGER NOT NULL CHECK (posting_index >= 0),
                  account_id TEXT NOT NULL,
                  amount_minor INTEGER NOT NULL,
                  currency_code TEXT NOT NULL,
                  currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
                  UNIQUE (posting_set_id, posting_index),
                  UNIQUE (posting_id, ledger_id),
                  FOREIGN KEY (posting_set_id, ledger_id)
                    REFERENCES posting_set(posting_set_id, ledger_id)
                    DEFERRABLE INITIALLY DEFERRED
                )
                """.trimIndent(),
                """
                CREATE TABLE ledger_transaction_current_version (
                  transaction_id TEXT NOT NULL,
                  ledger_id TEXT NOT NULL,
                  current_version_id TEXT NOT NULL,
                  PRIMARY KEY (transaction_id, ledger_id),
                  FOREIGN KEY (transaction_id, ledger_id)
                    REFERENCES ledger_transaction(transaction_id, ledger_id)
                    DEFERRABLE INITIALLY DEFERRED,
                  FOREIGN KEY (transaction_id, current_version_id, ledger_id)
                    REFERENCES transaction_version(transaction_id, version_id, ledger_id)
                    DEFERRABLE INITIALLY DEFERRED
                )
                """.trimIndent(),
                "INSERT INTO posting_set VALUES ('set-1', 'ledger-a')",
                "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-1', 'ledger-a', 'EXPENSE')",
                """
                INSERT INTO transaction_version VALUES (
                  'v-1', 'tx-1', 'ledger-a', 1, 'set-1',
                  '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', NULL
                )
                """.trimIndent(),
                "INSERT INTO ledger_transaction_current_version VALUES ('tx-1', 'ledger-a', 'v-1')",
                "INSERT INTO posting VALUES ('p-1', 'set-1', 'ledger-a', 0, 'expense', 3580, 'CNY', 2)",
                "INSERT INTO posting VALUES ('p-2', 'set-1', 'ledger-a', 1, 'asset', -3580, 'CNY', 2)",
            )
    }
}
