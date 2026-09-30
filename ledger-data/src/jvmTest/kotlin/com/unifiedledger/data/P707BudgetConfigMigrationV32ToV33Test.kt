package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * P7-07 07.B (D-184 item 3) v32 -> v33 budget-configuration migration evidence.
 *
 * The edge adds four non-rgXX_ product tables — `budget_config` (stable identity plus the CAS
 * pointer), `budget_command_request` / `budget_command_receipt` (the claim-first pair) and
 * `budget_settings_history` (the immutable settings history) — with their indexes and guard
 * triggers, structure-only (zero backfill).
 *
 * A v32 database is staged as `Schema.create` (v33) minus the four budget objects with
 * `PRAGMA user_version = 32` (the 31.sqm sentinel discipline precedent).
 */
class P707BudgetConfigMigrationV32ToV33Test {
    private val budgetObjects =
        listOf(
            "budget_config",
            "budget_settings_history",
            "budget_command_request",
            "budget_command_receipt",
            "budget_config_by_month",
            "budget_settings_history_by_category",
            "budget_config_guard_update",
            "budget_config_guard_delete",
            "budget_settings_history_guard_update",
            "budget_settings_history_guard_delete",
            "budget_command_receipt_guard_update",
            "budget_command_receipt_guard_delete",
        )

    @Test
    fun versionThirtyThreeIsCurrent() {
        assertEquals(34, LedgerDatabase.Schema.version)
    }

    @Test
    fun versionThirtyTwoToThirtyThreeAddsTheBudgetTablesWithZeroBackfill() {
        val path = Files.createTempFile("p707b-v32-v33-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV32(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 32, 33)
                driver.execute(null, "PRAGMA user_version = 33", 0)
                val database = LedgerDatabase(driver)
                // Zero backfill: every new owner is empty after the migration.
                assertEquals(0L, database.ledgerQueries.countBudgetConfigs("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countBudgetCommandRequests("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countBudgetCommandReceipts("ledger-a").executeAsOne())
                // Every new object exists with the fresh shape.
                budgetObjects.forEach { name ->
                    assertEquals(
                        1L,
                        queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = '$name'"),
                        name,
                    )
                }
                assertEquals(33L, queryLong(driver, "PRAGMA user_version"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaEqualsMigratedSchemaForEveryBudgetObject() {
        val freshPath = Files.createTempFile("p707b-v32-v33-fresh-", ".db")
        val migratedPath = Files.createTempFile("p707b-v32-v33-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            stageV32(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 32, 33)
            }
            assertEquals(
                schemaText(migratedUrl, "budget\\_%"),
                schemaText(freshUrl, "budget\\_%"),
            )
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun sameVersionReopenIsANoOp() {
        val path = Files.createTempFile("p707b-v33-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 33", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 33, 33)
                assertEquals(33L, queryLong(driver, "PRAGMA user_version"))
                budgetObjects.forEach { name ->
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
    fun slotOccupationRollsBackTheWholeMigrationAndKeepsTheV32Surface() {
        val path = Files.createTempFile("p707b-v32-v33-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV32(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // Occupy the late sentinel slot so the last statement of 32.sqm aborts inside the
                // caller's outer transaction, after the budget tables were created.
                driver.execute(null, "CREATE TABLE budget_v33_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 32, 33) }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(32L, queryLong(driver, "PRAGMA user_version"))
                // The whole budget table set rolled back together; only the occupied sentinel
                // (which also matches the pattern) survives.
                assertEquals(
                    0L,
                    queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'budget\\_%' ESCAPE '\\' AND name != 'budget_v33_late_sentinel'"),
                )
                // The occupied blocker survives as the only budget-named object.
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'budget_v33_late_sentinel'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migratedV32RowsSurviveTheEdgeValueForValue() {
        val path = Files.createTempFile("p707b-v32-v33-populated-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV32(url)
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
                driver.execute(null, "INSERT INTO posting VALUES ('posting-expense', 'posting-set-existing', 'ledger-a', 0, 'expense-account', 1000, 'CNY', 2)", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 32, 33) }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM ledger_transaction WHERE transaction_id = 'tx-existing'"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM transaction_version WHERE version_id = 'version-existing' AND note = 'lunch'"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM posting"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /** `Schema.create` at the current version minus the v33 budget objects, stamped as v32. */
    private fun stageV32(url: String) {
        JdbcSqliteDriver(url, migrationProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            listOf(
                "budget_settings_history",
                "budget_command_receipt",
                "budget_command_request",
                "budget_config",
            ).forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
            driver.execute(null, "PRAGMA user_version = 32", 0)
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

    private fun schemaText(
        url: String,
        namePattern: String,
    ): List<String> =
        DriverManager.getConnection(url).use { connection ->
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
