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
 * P7-05 v30 -> v31 additive migration evidence (spec section 4.3). The new edge creates the
 * void/restore fact owner, the two claim-first pairs, the single effective-predicate view and
 * its guard/index family, structure-only (zero backfill). A same-name slot occupation aborts
 * the whole migration inside the caller's outer transaction, a same-version reopen is a no-op,
 * and fresh = migrated schema text for every new object.
 *
 * A v30 database is staged as `Schema.create` (v31) minus the v31 objects with
 * `PRAGMA user_version = 30`, the sentinel discipline the 29.sqm edge already uses.
 */
class P705VoidCorrectionMigrationV30ToV31Test {
    private val p705Objects =
        listOf(
            "transaction_void_request",
            "transaction_void_fact",
            "transaction_void_receipt",
            "transaction_correction_request",
            "transaction_correction_receipt",
            "transaction_void_fact_recycle_bin_idx",
            "transaction_void_fact_sequence_guard",
            "transaction_void_fact_alternation_guard",
            "transaction_void_fact_guard_update",
            "transaction_void_fact_guard_delete",
            "transaction_effective_state",
        )

    @Test
    fun versionThirtyOneIsCurrent() {
        assertEquals(31, LedgerDatabase.Schema.version)
    }

    @Test
    fun versionThirtyToThirtyOneAddsTheP705OwnersWithZeroBackfill() {
        val path = Files.createTempFile("p705-v30-v31-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV30(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 30, 31)
                driver.execute(null, "PRAGMA user_version = 31", 0)
                val database = LedgerDatabase(driver)
                // Zero backfill: every new owner is empty after the migration.
                assertEquals(0L, database.ledgerQueries.countTransactionVoidRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countTransactionVoidFacts().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countTransactionVoidReceipts().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countTransactionCorrectionRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countTransactionCorrectionReceipts().executeAsOne())
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM ledger_transaction"))
                // Every new object exists with the fresh shape.
                p705Objects.forEach { name ->
                    assertEquals(
                        1L,
                        queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = '$name'"),
                        name,
                    )
                }
                assertEquals(31L, queryLong(driver, "PRAGMA user_version"))
                // The effective predicate view answers on an empty ledger without error.
                assertEquals(
                    0L,
                    queryLong(driver, "SELECT count(*) FROM transaction_effective_state"),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaEqualsMigratedSchemaForEveryP705Object() {
        val freshPath = Files.createTempFile("p705-v30-v31-fresh-", ".db")
        val migratedPath = Files.createTempFile("p705-v30-v31-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            stageV30(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 30, 31)
            }
            // The whole P7-05 object family compares equal between a fresh v31 database and a
            // migrated v30 -> v31 one — tables, indexes, triggers and the view included.
            assertEquals(
                schemaText(migratedUrl, "transaction\\_void%") + schemaText(migratedUrl, "transaction\\_correction%") + schemaText(migratedUrl, "transaction\\_effective%"),
                schemaText(freshUrl, "transaction\\_void%") + schemaText(freshUrl, "transaction\\_correction%") + schemaText(freshUrl, "transaction\\_effective%"),
            )
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun sameVersionReopenIsANoOp() {
        val path = Files.createTempFile("p705-v31-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 31", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 31, 31)
                assertEquals(31L, queryLong(driver, "PRAGMA user_version"))
                p705Objects.forEach { name ->
                    assertEquals(
                        1L,
                        queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = '$name'"),
                        name,
                    )
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun slotOccupationRollsBackTheWholeMigrationAndKeepsTheV30Surface() {
        val path = Files.createTempFile("p705-v30-v31-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV30(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // Occupy the view name slot so the last CREATE of 30.sqm aborts inside the
                // caller's outer transaction.
                driver.execute(null, "CREATE VIEW transaction_effective_state AS SELECT 1 AS one", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 30, 31) }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(30L, queryLong(driver, "PRAGMA user_version"))
                // The occupied blocker is the only object of the aborted migration.
                assertEquals(
                    0L,
                    queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'transaction\\_void\\_%' ESCAPE '\\'"),
                )
                assertEquals(
                    0L,
                    queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'transaction\\_correction\\_%' ESCAPE '\\'"),
                )
                assertTrue(queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'transaction_effective_state'") == 1L)
                assertTrue(
                    queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'transaction_effective_state' AND sql LIKE '%is_effective%'") == 0L,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migratedV30RowsSurviveTheEdgeValueForValue() {
        val path = Files.createTempFile("p705-v30-v31-populated-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV30(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-existing', 'ledger-a', 'EXPENSE')",
                    0,
                )
                driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-existing', 'ledger-a')", 0)
                driver.execute(
                    null,
                    "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                        "VALUES ('version-existing', 'tx-existing', 'ledger-a', 1, 'posting-set-existing', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', 'lunch')",
                    0,
                )
                driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('tx-existing', 'ledger-a', 'version-existing')", 0)
                driver.execute(null, "INSERT INTO posting VALUES ('posting-expense', 'posting-set-existing', 'ledger-a', 0, 'expense-account', 1000, 'CNY', 2)", 0)
                driver.execute(null, "INSERT INTO posting VALUES ('posting-asset', 'posting-set-existing', 'ledger-a', 1, 'asset-account', -1000, 'CNY', 2)", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 30, 31) }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // The pre-existing chain is untouched and now reads as effective (no fact).
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM ledger_transaction WHERE transaction_id = 'tx-existing'"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM transaction_version WHERE version_id = 'version-existing' AND note = 'lunch'"))
                assertEquals(2L, queryLong(driver, "SELECT count(*) FROM posting"))
                assertEquals(
                    "tx-existing",
                    queryText(driver, "SELECT transaction_id FROM transaction_effective_state WHERE is_effective = 1"),
                )
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM transaction_void_fact"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /** `Schema.create` at the current version minus the v31 objects, stamped as a v30 file. */
    private fun stageV30(url: String) {
        JdbcSqliteDriver(url, migrationProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            driver.execute(null, "DROP VIEW transaction_effective_state", 0)
            driver.execute(null, "DROP TABLE transaction_void_fact", 0)
            driver.execute(null, "DROP TABLE transaction_void_receipt", 0)
            driver.execute(null, "DROP TABLE transaction_void_request", 0)
            driver.execute(null, "DROP TABLE transaction_correction_receipt", 0)
            driver.execute(null, "DROP TABLE transaction_correction_request", 0)
            driver.execute(null, "PRAGMA user_version = 30", 0)
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

    private fun queryText(
        driver: JdbcSqliteDriver,
        sql: String,
    ): String =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getString(0)))
                },
                0,
            ).value

    private fun schemaText(
        url: String,
        namePattern: String,
    ): List<String> =
        java.sql.DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT type || '|' || name || '|' || replace(replace(trim(sql), '  ', ' '), char(10), ' ') FROM sqlite_master " +
                            "WHERE name LIKE '$namePattern' ESCAPE '\\' AND sql IS NOT NULL ORDER BY type, name",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getString(1))
                        }
                    }
            }
        }
}
