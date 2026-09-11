package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * P7-01 desktop migration fix evidence (spec section 5.4, D-143). Covers the three frozen
 * branches of [migrateToCurrentSchema]: a lower existing version migrates in place, an equal
 * version reopens directly, and a higher version fails closed without touching the file.
 */
class DesktopCatalogMigrationTest {
    @Test
    fun lowerExistingVersionIsMigratedInPlaceWithoutDeletingTheFile() {
        val path: Path = Files.createTempFile("p7-01-desktop-lower-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            seedVersionTwentySevenFile(url)
            assertEquals(27L, queryUserVersion(url))
            JdbcSqliteDriver(url).use { driver ->
                migrateToCurrentSchema(driver)
                assertEquals(28L, driver.userVersion())
                val database = LedgerDatabase(driver)
                assertEquals(0L, database.ledgerQueries.countCatalogAccounts("ledger-local-test").executeAsOne())
            }
            // The same file still exists and now opens straight into the graph.
            val graph = openDesktopLedger(url)
            graph.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun currentVersionFileReopensDirectly() {
        val path: Path = Files.createTempFile("p7-01-desktop-current-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            // The production open path creates and stamps a fresh file at the current version.
            JdbcSqliteDriver(url).use { driver -> migrateToCurrentSchema(driver) }
            assertEquals(28L, queryUserVersion(url))
            JdbcSqliteDriver(url).use { driver ->
                migrateToCurrentSchema(driver)
                assertEquals(28L, driver.userVersion())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun newerVersionFailsClosedAndLeavesTheFileIntact() {
        val path: Path = Files.createTempFile("p7-01-desktop-newer-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 29", 0)
            }
            assertFailsWith<IllegalStateException> {
                JdbcSqliteDriver(url).use { driver -> migrateToCurrentSchema(driver) }
            }
            JdbcSqliteDriver(url).use { driver -> assertEquals(29L, driver.userVersion()) }
            assertEquals(true, Files.exists(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedLedgerWithNoVersionStampOpensAndGetsStampedInsteadOfFailing() {
        val path: Path = Files.createTempFile("p7-01-desktop-untagged-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            // A3: a file already carrying the full current schema but with user_version == 0
            // (written before the version stamp existed) must be stamped, not handed to
            // Schema.create (which would fail with "table ... already exists").
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 0", 0)
            }
            assertEquals(0L, queryUserVersion(url))

            JdbcSqliteDriver(url).use { driver ->
                migrateToCurrentSchema(driver)
                assertEquals(28L, driver.userVersion())
            }
            // The same file still open through the real path, and it still has its tables.
            val graph = openDesktopLedger(url)
            graph.close()
            assertEquals(true, Files.exists(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun legacyPopulatedLedgerWithNoVersionStampMigratesInPlace() {
        val path: Path = Files.createTempFile("p7-01-desktop-legacy-untagged-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            // A shell with only a core table stands in for a pre-catalog ledger that was never
            // stamped: the version-less path must run the v27 migration rather than Schema.create.
            JdbcSqliteDriver(url).use { driver ->
                driver.execute(
                    null,
                    "CREATE TABLE ledger_transaction (transaction_id TEXT NOT NULL PRIMARY KEY, ledger_id TEXT NOT NULL, kind TEXT NOT NULL, canonical_kind TEXT, UNIQUE (transaction_id, ledger_id))",
                    0,
                )
                driver.execute(null, "PRAGMA user_version = 0", 0)
            }

            JdbcSqliteDriver(url).use { driver ->
                migrateToCurrentSchema(driver)
                assertEquals(28L, driver.userVersion())
                assertEquals(
                    1L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM sqlite_master WHERE name = 'catalog_version'",
                    ),
                )
            }
            assertEquals(true, Files.exists(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /**
     * Builds a genuine v27 file from the current schema by dropping the six additive `catalog_*`
     * product tables `27.sqm` introduces (the migration is structure-only, so the remainder is
     * byte-for-byte the v27 surface), then stamps `user_version = 27`.
     */
    private fun seedVersionTwentySevenFile(url: String) {
        JdbcSqliteDriver(url).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            listOf(
                "catalog_command_receipt",
                "catalog_command_request",
                "catalog_name_history",
                "catalog_category",
                "catalog_account",
                "catalog_version",
            ).forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
            driver.execute(null, "PRAGMA user_version = 27", 0)
        }
    }

    private fun queryUserVersion(url: String): Long = JdbcSqliteDriver(url).use { driver -> driver.userVersion() }

    private fun JdbcSqliteDriver.userVersion(): Long {
        var version = 0L
        executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                if (cursor.next().value) version = cursor.getLong(0) ?: 0L
                app.cash.sqldelight.db.QueryResult.Unit
            },
            0,
        )
        return version
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
}
