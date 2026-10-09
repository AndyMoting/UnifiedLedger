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
 * P7-08 08.B-1 (D-221) v34 -> v35 manual-create annotation claim-column migration evidence.
 *
 * The edge `34.sqm` is purely additive with zero backfill: each of the four claim-first manual
 * request tables (`manual_expense_request`, `manual_income_request`, `manual_transfer_request`,
 * `manual_lending_request`) gains two NULLABLE annotation columns (`annotation_tag_ids`,
 * `annotation_merchant_id`) at the table tail. Legacy rows carry NULL and the structured replay
 * matcher (ruling R2) reads NULL as the frozen "no annotation" default, so a pre-v35 request replays
 * equivalently with zero writes.
 *
 * Contract notes (CONTRIBUTING "Schema 迁移批次清单" item 3): `ALTER TABLE ADD COLUMN` cannot be
 * byte-equal to the fresh inline DDL, so the fresh-vs-migrated contract is `columnInfo`
 * (`name|type|notnull|pk` in declaration order, the new columns at the tail), never a raw
 * `sqlite_master` text compare (31.sqm precedent). The five-version restore whitelist `{1,31,32,33,34}`
 * is asserted at the composition-root level by the platform wiring tests; this class proves the edge
 * plus the v1 -> v35 full chain lands on the same columnInfo as a fresh create.
 */
class P708ManualCreateAnnotationV34ToV35MigrationTest {
    private val requestTables =
        listOf(
            "manual_expense_request",
            "manual_income_request",
            "manual_transfer_request",
            "manual_lending_request",
        )

    /** The whole claim-table column set this edge must land on, tail-first. */
    private val expectedTail =
        listOf(
            "annotation_tag_ids|TEXT|0|0",
            "annotation_merchant_id|TEXT|0|0",
        )

    @Test
    fun versionThirtyFiveIsCurrent() {
        assertEquals(35, LedgerDatabase.Schema.version)
    }

    @Test
    fun theEdgeAddsTheTwoAnnotationColumnsAtTheTailOfEveryManualRequestTable() {
        val path = Files.createTempFile("p708b1-v34-v35-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV34(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 34, 35) }
                driver.execute(null, "PRAGMA user_version = 35", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(35L, queryLong(driver, "PRAGMA user_version"))
                requestTables.forEach { table ->
                    val columns = columnInfo(driver, table)
                    assertEquals(
                        expectedTail,
                        columns.takeLast(2),
                        "$table must carry the two annotation columns at the tail",
                    )
                }
                // Zero backfill: no annotation owner gains rows from the edge alone.
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM transaction_annotation_revision"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM transaction_annotation_current"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaEqualsMigratedSchemaByTheColumnInfoContract() {
        val freshPath = Files.createTempFile("p708b1-v34-v35-fresh-", ".db")
        val migratedPath = Files.createTempFile("p708b1-v34-v35-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            stageV34(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 34, 35) }
            }
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { fresh ->
                JdbcSqliteDriver(migratedUrl, migrationProperties()).use { migrated ->
                    requestTables.forEach { table ->
                        assertEquals(
                            columnInfo(fresh, table),
                            columnInfo(migrated, table),
                            "$table fresh-vs-migrated columnInfo drift",
                        )
                    }
                }
            }
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun theFullV1ChainLandsOnTheSameColumnInfoAsAFreshCreate() {
        val freshPath = Files.createTempFile("p708b1-v35-fresh-", ".db")
        val migratedPath = Files.createTempFile("p708b1-v1-v35-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            DriverManager.getConnection(migratedUrl).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, LedgerDatabase.Schema.version)
            }
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { fresh ->
                JdbcSqliteDriver(migratedUrl, migrationProperties()).use { migrated ->
                    requestTables.forEach { table ->
                        assertEquals(
                            columnInfo(fresh, table),
                            columnInfo(migrated, table),
                            "$table fresh-vs-full-chain columnInfo drift",
                        )
                    }
                }
            }
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun legacyRowsSurviveTheEdgeValueForValueAndReplayAsNoAnnotation() {
        val path = Files.createTempFile("p708b1-v34-v35-legacy-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV34(url)
            // A pre-v35 committed manual expense request + receipt (no annotation columns yet).
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-legacy', 'ledger-a', 'EXPENSE')",
                    0,
                )
                driver.execute(null, "INSERT INTO posting_set VALUES ('ps-legacy', 'ledger-a')", 0)
                driver.execute(
                    null,
                    "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                        "VALUES ('v-legacy', 'tx-legacy', 'ledger-a', 1, 'ps-legacy', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', 'lunch')",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO manual_expense_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker) " +
                        "VALUES ('ledger-a', 'req-legacy', 3580, 'CNY', 2, 'cat', 'acct', '2026-03-05T02:00:00Z', 'lunch', 'explicit_manual_save')",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO confirmed_expense_receipt(ledger_id, request_id, confirmation_id, transaction_id) " +
                        "VALUES ('ledger-a', 'req-legacy', 'conf-legacy', 'tx-legacy')",
                    0,
                )
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 34, 35) }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // The row survives value-for-value and its new columns are NULL == "no annotation".
                assertEquals(
                    1L,
                    queryLong(driver, "SELECT count(*) FROM manual_expense_request WHERE request_id = 'req-legacy' AND amount_minor = 3580 AND note = 'lunch'"),
                )
                assertEquals(
                    1L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM manual_expense_request WHERE request_id = 'req-legacy' " +
                            "AND annotation_tag_ids IS NULL AND annotation_merchant_id IS NULL",
                    ),
                )
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun theLateSentinelSlotCollisionRollsBackTheWholeMigrationAndKeepsTheV34Surface() {
        val path = Files.createTempFile("p708b1-v34-v35-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV34(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // Occupy the late sentinel name so the last statements of 34.sqm abort inside the
                // caller's outer transaction, after every ADD COLUMN succeeded.
                driver.execute(
                    null,
                    "CREATE TABLE manual_annotation_v35_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))",
                    0,
                )
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 34, 35) }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(34L, queryLong(driver, "PRAGMA user_version"))
                // Every ADD COLUMN rolled back together: no annotation column on any request table.
                requestTables.forEach { table ->
                    assertEquals(
                        0L,
                        queryLong(driver, "SELECT count(*) FROM pragma_table_info('$table') WHERE name = 'annotation_tag_ids'"),
                        table,
                    )
                }
                assertEquals(
                    1L,
                    queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'manual_annotation_v35_late_sentinel'"),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun sameVersionReopenIsANoOp() {
        val path = Files.createTempFile("p708b1-v35-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 35", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 35, 35)
                assertEquals(35L, queryLong(driver, "PRAGMA user_version"))
                requestTables.forEach { table ->
                    assertEquals(expectedTail, columnInfo(driver, table).takeLast(2), table)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /**
     * The five-version restore whitelist `{1,31,32,33,34}` is asserted at the composition-root level
     * by `AndroidRestoreWiringTest` / `DesktopRestoreWiringTest` (the real platform constants this
     * module cannot import); this class owns only the edge + fresh/migrated schema legs.
     */

    /** `Schema.create` at the current version minus the two annotation columns, stamped as v34. */
    private fun stageV34(url: String) {
        JdbcSqliteDriver(url, migrationProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            requestTables.forEach { table ->
                driver.execute(null, "ALTER TABLE $table DROP COLUMN annotation_tag_ids", 0)
                driver.execute(null, "ALTER TABLE $table DROP COLUMN annotation_merchant_id", 0)
            }
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            driver.execute(null, "PRAGMA user_version = 34", 0)
        }
    }

    private fun migrationProperties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

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

    private fun columnInfo(
        driver: JdbcSqliteDriver,
        table: String,
    ): List<String> =
        driver
            .executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    val rows = buildList {
                        while (cursor.next().value) {
                            add("${cursor.getString(1)}|${cursor.getString(2)}|${cursor.getLong(3)}|${cursor.getLong(5)}")
                        }
                    }
                    app.cash.sqldelight.db.QueryResult.Value(rows)
                },
                0,
            ).value
}
