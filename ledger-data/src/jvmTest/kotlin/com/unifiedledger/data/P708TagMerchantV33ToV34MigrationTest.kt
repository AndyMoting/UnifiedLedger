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
 * P7-08 08.A (D-187) v33 -> v34 tag/merchant catalog and transaction annotation migration evidence.
 *
 * The edge adds non-rgXX_ product objects — the `catalog_tag` / `catalog_merchant` stable
 * identities with their guard triggers, the shared `catalog_item_name_history`, the claim-first
 * `catalog_item_command_request` / `catalog_item_command_receipt` pair, and the annotation
 * aggregate (`transaction_annotation_revision`, `transaction_annotation_tag`,
 * `transaction_annotation_current`, `transaction_annotation_command_request`,
 * `transaction_annotation_command_receipt`) — structure only (zero backfill).
 *
 * A v33 database is staged as `Schema.create` (v34) minus the new objects with
 * `PRAGMA user_version = 33` (the 31.sqm/32.sqm sentinel discipline precedent).
 */
class P708TagMerchantV33ToV34MigrationTest {
    private val newObjects =
        listOf(
            "catalog_tag",
            "catalog_merchant",
            "catalog_item_name_history",
            "catalog_item_version",
            "catalog_item_command_request",
            "catalog_item_command_receipt",
            "transaction_annotation_revision",
            "transaction_annotation_tag",
            "transaction_annotation_current",
            "transaction_annotation_command_request",
            "transaction_annotation_command_receipt",
            "transaction_annotation_revision_by_merchant",
            "transaction_annotation_tag_by_tag",
            "catalog_tag_guard_update",
            "catalog_tag_guard_delete",
            "catalog_merchant_guard_update",
            "catalog_merchant_guard_delete",
            "catalog_item_name_history_guard_update",
            "catalog_item_name_history_guard_delete",
            "catalog_item_version_guard_update",
            "catalog_item_command_receipt_guard_update",
            "catalog_item_command_receipt_guard_delete",
            "transaction_annotation_revision_guard_update",
            "transaction_annotation_revision_guard_delete",
            "transaction_annotation_tag_cardinality_guard",
            "transaction_annotation_tag_guard_update",
            "transaction_annotation_tag_guard_delete",
            "transaction_annotation_current_guard_update",
            "transaction_annotation_current_guard_delete",
            "transaction_annotation_command_receipt_guard_update",
            "transaction_annotation_command_receipt_guard_delete",
        )

    /** The eleven new tables, in child-before-parent drop order. */
    private val newTables =
        listOf(
            "transaction_annotation_command_receipt",
            "transaction_annotation_command_request",
            "transaction_annotation_current",
            "transaction_annotation_tag",
            "transaction_annotation_revision",
            "catalog_item_command_receipt",
            "catalog_item_command_request",
            "catalog_item_name_history",
            "catalog_item_version",
            "catalog_merchant",
            "catalog_tag",
        )

    @Test
    fun versionThirtyFiveIsCurrent() {
        assertEquals(35, LedgerDatabase.Schema.version)
    }

    @Test
    fun versionThirtyThreeToThirtyFourAddsTheNewObjectsWithZeroBackfill() {
        val path = Files.createTempFile("p708a-v33-v34-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV33(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 33, 34)
                driver.execute(null, "PRAGMA user_version = 34", 0)
                val database = LedgerDatabase(driver)
                // Zero backfill: every new owner is empty after the migration.
                assertEquals(0L, database.ledgerQueries.countCatalogTags("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countCatalogMerchants("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countTransactionAnnotationRevisions("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countTransactionAnnotationCurrents("ledger-a").executeAsOne())
                // Every new object exists with the fresh shape.
                newObjects.forEach { name ->
                    assertEquals(
                        1L,
                        queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = '$name'"),
                        name,
                    )
                }
                assertEquals(34L, queryLong(driver, "PRAGMA user_version"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaEqualsMigratedSchemaForEveryNewObject() {
        val freshPath = Files.createTempFile("p708a-v33-v34-fresh-", ".db")
        val migratedPath = Files.createTempFile("p708a-v33-v34-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            stageV33(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 33, 34)
            }
            // Byte-for-byte equality of every new object, plus the shared name-history table, so a
            // drifting fresh terminal definition cannot pass by touching only named objects.
            assertEquals(
                schemaText(migratedUrl, "catalog\\_item\\_%"),
                schemaText(freshUrl, "catalog\\_item\\_%"),
            )
            assertEquals(
                schemaText(migratedUrl, "catalog\\_tag%"),
                schemaText(freshUrl, "catalog\\_tag%"),
            )
            assertEquals(
                schemaText(migratedUrl, "catalog\\_merchant%"),
                schemaText(freshUrl, "catalog\\_merchant%"),
            )
            assertEquals(
                schemaText(migratedUrl, "transaction\\_annotation\\_%"),
                schemaText(freshUrl, "transaction\\_annotation\\_%"),
            )
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun sourceMigrationRepositoryFreshEqualsMigratedForTheNewObjects() {
        // The SQLDelight migration verifier's own .db snapshots gate CI; this leg additionally
        // proves a full v1 -> v34 chain lands on the same new-object DDL as a fresh create.
        val freshPath = Files.createTempFile("p708a-v34-fresh-", ".db")
        val migratedPath = Files.createTempFile("p708a-v34-migrated-", ".db")
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
            assertEquals(
                schemaText(migratedUrl, "catalog\\_tag%"),
                schemaText(freshUrl, "catalog\\_tag%"),
            )
            assertEquals(
                schemaText(migratedUrl, "transaction\\_annotation\\_%"),
                schemaText(freshUrl, "transaction\\_annotation\\_%"),
            )
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun sameVersionReopenIsANoOp() {
        val path = Files.createTempFile("p708a-v34-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 34", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 34, 34)
                assertEquals(34L, queryLong(driver, "PRAGMA user_version"))
                newObjects.forEach { name ->
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
    fun slotOccupationRollsBackTheWholeMigrationAndKeepsTheV33Surface() {
        val path = Files.createTempFile("p708a-v33-v34-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV33(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // Occupy the late sentinel slot so the last statement of 33.sqm aborts inside the
                // caller's outer transaction, after the new tables were created.
                driver.execute(
                    null,
                    "CREATE TABLE catalog_annotation_v34_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))",
                    0,
                )
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 33, 34) }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(33L, queryLong(driver, "PRAGMA user_version"))
                // The whole new table set rolled back together. The v33 catalog_% surface
                // (catalog_version, catalog_account, catalog_category, catalog_name_history,
                // catalog_command_request, catalog_command_receipt) is untouched, and only the
                // occupied sentinel (which also matches the pattern) survives.
                assertEquals(
                    0L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'catalog\\_%' ESCAPE '\\' " +
                            "AND name NOT IN ('catalog_annotation_v34_late_sentinel', 'catalog_version', " +
                            "'catalog_account', 'catalog_category', 'catalog_name_history', " +
                            "'catalog_command_request', 'catalog_command_receipt')",
                    ),
                )
                assertEquals(
                    0L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name LIKE 'transaction\\_annotation\\_%' ESCAPE '\\'",
                    ),
                )
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'catalog_annotation_v34_late_sentinel'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migratedV33RowsSurviveTheEdgeValueForValue() {
        val path = Files.createTempFile("p708a-v33-v34-populated-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV33(url)
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
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 33, 34) }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM ledger_transaction WHERE transaction_id = 'tx-existing'"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM transaction_version WHERE version_id = 'version-existing' AND note = 'lunch'"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM posting"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_tag"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM transaction_annotation_revision"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /** `Schema.create` at the current version minus the v34 objects, stamped as v33. */
    private fun stageV33(url: String) {
        JdbcSqliteDriver(url, migrationProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            newTables.forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
            driver.execute(null, "PRAGMA user_version = 33", 0)
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
