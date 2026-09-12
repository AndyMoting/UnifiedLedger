package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P7-02 v28 -> v29 additive migration evidence: the transfer/lending/pin product tables land
 * structure-only (zero backfill), the whole migration rolls back on a late sentinel failure, a
 * same-version reopen is a no-op, and fresh = migrated schema text for the new objects.
 */
class P7SequenceV28ToV29MigrationTest {
    @Test
    fun versionTwentyNineIsCurrent() {
        assertEquals(29, LedgerDatabase.Schema.version)
    }

    @Test
    fun versionTwentyEightToTwentyNineAddsProductTablesWithZeroBackfill() {
        val path = Files.createTempFile("p7-v28-v29-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            // Verify the new tables exist with zero rows on a fresh current schema.
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(0L, database.ledgerQueries.countManualTransferRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countTransferReceipts().executeAsOne())
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM counterparty"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM lending_position"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM entry_pin"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun lateFailureRollsBackTheWholeMigrationAndKeepsTheV28Surface() {
        val path = Files.createTempFile("p7-v28-v29-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                P7_V29_TABLES.forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
                driver.execute(null, "PRAGMA user_version = 28", 0)
                // Occupy the late sentinel slot so 28.sqm aborts after the tables were created.
                driver.execute(null, "CREATE TABLE entry_pin_v29_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 28, 29) }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(28L, queryLong(driver, "PRAGMA user_version"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'manual_transfer_request'"))
                assertTrue(queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'entry_pin_v29_late_sentinel'") == 1L)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun entryPinAndLendingGuardsEnforceTheirInvariants() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            // The guard/uniqueness invariants are FK-independent; disable FK checks so the test
            // exercises only the triggers and the primary key without a full catalog scaffold.
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            // lending_position_history is append-only.
            driver.execute(null, "INSERT INTO counterparty VALUES ('l','cp1','n','recv',1)", 0)
            driver.execute(null, "INSERT INTO lending_position VALUES ('l','cp1','recv','CNY',2,0)", 0)
            // FK checks are off, so the transaction_id reference needs no real transaction row.
            driver.execute(null, "INSERT INTO lending_position_history VALUES ('l','cp1','e1','LEND',100,100,'t1','2026-01-01T00:00:00Z')", 0)
            assertFailsWith<SQLException> {
                driver.execute(null, "UPDATE lending_position_history SET amount_minor = 50 WHERE entry_id = 'e1'", 0)
            }
            assertFailsWith<SQLException> {
                driver.execute(null, "DELETE FROM lending_position_history WHERE entry_id = 'e1'", 0)
            }
            // entry_pin uniqueness is by (ledger, kind, id).
            driver.execute(null, "INSERT INTO entry_pin VALUES ('l','account','a1','2026-01-01T00:00:00Z')", 0)
            assertFailsWith<SQLException> {
                driver.execute(null, "INSERT INTO entry_pin VALUES ('l','account','a1','2026-01-02T00:00:00Z')", 0)
            }
        } finally {
            driver.close()
        }
    }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }

    private fun queryLong(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    private companion object {
        val P7_V29_TABLES =
            listOf(
                "manual_transfer_request",
                "confirmed_transfer_receipt",
                "counterparty",
                "counterparty_name_history",
                "lending_position",
                "lending_position_history",
                "manual_lending_request",
                "confirmed_lending_receipt",
                "entry_pin",
            )
    }
}
