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
import kotlin.test.assertTrue

/**
 * P7-01 v27 -> v28 additive catalog migration evidence (spec sections 5.2/5.4, D-143).
 * Zero backfill, single outer transaction rollback, same-version reopen, and fresh=migrated
 * schema text equivalence for the six new `catalog_*` product tables.
 */
class CatalogV27ToV28MigrationTest {
    @Test
    fun versionTwentyEightIsCurrent() {
        assertEquals(28, LedgerDatabase.Schema.version)
    }

    @Test
    fun versionTwentySevenToTwentyEightAddsCatalogTablesWithZeroBackfill() {
        val path = Files.createTempFile("catalog-v27-v28-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            seedVersionOne(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 27)
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('tx-legacy','ledger-a','EXPENSE',NULL)",
                    0,
                )
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 27, 28)
                val database = LedgerDatabase(driver)
                assertEquals(0L, database.ledgerQueries.countCatalogVersions("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countCatalogAccounts("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countCatalogCategories("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countCatalogCommandRequests("ledger-a").executeAsOne())
                assertEquals(0L, database.ledgerQueries.countCatalogCommandReceipts("ledger-a").executeAsOne())
                // The pre-existing formal row is untouched: the migration is structure-only.
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM ledger_transaction WHERE transaction_id = 'tx-legacy'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionTwentyEightToTwentyEightReopenIsANoOp() {
        val path = Files.createTempFile("catalog-v28-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            seedVersionOne(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 28)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 28, 28)
                assertEquals(0L, LedgerDatabase(driver).ledgerQueries.countCatalogAccounts("ledger-a").executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun lateFailureRollsBackTheWholeCatalogMigrationAndKeepsTheVersionTwentySevenSurface() {
        val path = Files.createTempFile("catalog-v27-v28-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            seedVersionOne(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 27)
                driver.execute(null, "PRAGMA user_version = 27", 0)
                // Occupy the late sentinel slot so 27.sqm aborts after the catalog tables
                // were created; the wrapped outer transaction must roll everything back.
                driver.execute(null, "CREATE TABLE catalog_v28_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, 27, 28)
                    }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(27L, queryLong(driver, "PRAGMA user_version"))
                assertEquals(
                    0L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM sqlite_master WHERE name IN ('catalog_version','catalog_account','catalog_category','catalog_name_history','catalog_command_request','catalog_command_receipt')",
                    ),
                )
                // The blocker survives as the only catalog-named object.
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'catalog_v28_late_sentinel'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaTextEqualsMigratedSchemaTextForCatalogObjects() {
        val freshPath = Files.createTempFile("catalog-fresh-", ".db")
        val migratedPath = Files.createTempFile("catalog-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
            }
            seedVersionOne(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 28)
            }
            assertEquals(catalogSchemaText(freshUrl), catalogSchemaText(migratedUrl))
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun nameHistorySupersedeTriggerRejectsIllegalUpdates() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(
                null,
                "INSERT INTO catalog_name_history VALUES ('ledger-a','account','asset-a',1,'旧名','CURRENT')",
                0,
            )
            driver.execute(
                null,
                "UPDATE catalog_name_history SET status = 'SUPERSEDED' WHERE ledger_id = 'ledger-a' AND owner_kind = 'account' AND owner_id = 'asset-a' AND version_number = 1",
                0,
            )
            assertEquals(
                "SUPERSEDED",
                queryString(driver, "SELECT status FROM catalog_name_history WHERE owner_id = 'asset-a'"),
            )
            driver.execute(null, "INSERT INTO catalog_name_history VALUES ('ledger-a','account','asset-a',2,'新名','CURRENT')", 0)
            // Renaming an already SUPERSEDED row is the only illegal transition.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "UPDATE catalog_name_history SET name = '篡改' WHERE owner_id = 'asset-a' AND version_number = 1",
                    0,
                )
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun catalogVersionGuardOnlyAllowsPlusOne() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "INSERT INTO catalog_version VALUES ('ledger-a', 1)", 0)
            driver.execute(null, "UPDATE catalog_version SET version = 2 WHERE ledger_id = 'ledger-a'", 0)
            assertEquals(2L, queryLong(driver, "SELECT version FROM catalog_version WHERE ledger_id = 'ledger-a'"))
            assertFailsWith<SQLException> {
                driver.execute(null, "UPDATE catalog_version SET version = 5 WHERE ledger_id = 'ledger-a'", 0)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun catalogReceiptIsImmutable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "INSERT INTO catalog_version VALUES ('ledger-a', 1)", 0)
            driver.execute(
                null,
                "INSERT INTO catalog_command_request VALUES ('ledger-a','request-a','CreateAccount','{}','sha256:x','ACCEPTED',NULL,1,2)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO catalog_command_receipt VALUES ('ledger-a','request-a','ACCEPTED',2,NULL,NULL,NULL,NULL)",
                0,
            )
            assertFailsWith<SQLException> {
                driver.execute(null, "UPDATE catalog_command_receipt SET new_catalog_version = 9 WHERE request_id = 'request-a'", 0)
            }
            assertFailsWith<SQLException> {
                driver.execute(null, "DELETE FROM catalog_command_receipt WHERE request_id = 'request-a'", 0)
            }
            assertTrue(queryLong(driver, "SELECT count(*) FROM catalog_command_receipt") == 1L)
        } finally {
            driver.close()
        }
    }

    private fun seedVersionOne(url: String) {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                VERSION_ONE_STATEMENTS.forEach(statement::execute)
            }
        }
    }

    private fun catalogSchemaText(url: String): List<String> =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT type || '|' || name || '|' || replace(replace(trim(sql), '  ', ' '), char(10), ' ') FROM sqlite_master WHERE name LIKE 'catalog_%' AND sql IS NOT NULL ORDER BY type, name",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.getString(1))
                            }
                        }
                    }
            }
        }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }
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

private fun queryString(
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
