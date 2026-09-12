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
                assertEquals(29L, driver.userVersion())
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
            assertEquals(29L, queryUserVersion(url))
            JdbcSqliteDriver(url).use { driver ->
                migrateToCurrentSchema(driver)
                assertEquals(29L, driver.userVersion())
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
                driver.execute(null, "PRAGMA user_version = 30", 0)
            }
            assertFailsWith<IllegalStateException> {
                JdbcSqliteDriver(url).use { driver -> migrateToCurrentSchema(driver) }
            }
            JdbcSqliteDriver(url).use { driver -> assertEquals(30L, driver.userVersion()) }
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
                assertEquals(29L, driver.userVersion())
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
            // R2: a genuine v27 surface (current schema minus the additive catalog tables, which
            // keeps all 26.sqm objects) that was never stamped at the current version. The
            // version-less path must verify the v27 sentinel and run the migration.
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                (
                    listOf(
                        "catalog_command_receipt",
                        "catalog_command_request",
                        "catalog_name_history",
                        "catalog_category",
                        "catalog_account",
                        "catalog_version",
                    ) + P7_V29_TABLES
                ).forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
                driver.execute(null, "PRAGMA user_version = 0", 0)
            }

            JdbcSqliteDriver(url).use { driver ->
                migrateToCurrentSchema(driver)
                assertEquals(29L, driver.userVersion())
                assertEquals(
                    1L,
                    queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'catalog_version'"),
                )
            }
            assertEquals(true, Files.exists(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedLedgerWithoutTheV27SentinelIsStampedOnlyAndNeverGuessedMigrated() {
        val path: Path = Files.createTempFile("p7-01-desktop-untagged-v26-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            // R2: a populated file whose real version is unknown and which lacks the v27 rebuild
            // objects (stands in for a real v26-or-older database). Running v27 -> v28 here would
            // silently skip the 26 -> 27 structural rebuild, so the path must only stamp the
            // current version and let the downstream bootstrap fail closed if the schema is wrong.
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
                assertEquals(29L, driver.userVersion())
                // No migration ran: the catalog tables were not created, and the v27 objects the
                // guarded branch would have relied on are still absent.
                assertEquals(
                    0L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM sqlite_master WHERE name IN ('catalog_version','evidence_projection_current_by_evidence','reconciliation_correction_snapshot')",
                    ),
                )
                // The pre-existing table is untouched: never deleted or overwritten.
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'ledger_transaction'"))
            }
            assertEquals(true, Files.exists(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun guardsOnlySurfaceFromAnUntaggedV26FileIsNotMistakenForV27() {
        val path: Path = Files.createTempFile("p7-01-desktop-untagged-v26-guards-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            // R2: a real v26 database has the two 25.sqm evidence-projection guards but not the
            // 26.sqm-unique index / correction snapshot. The `.all` conjunction must reject it.
            JdbcSqliteDriver(url).use { driver ->
                driver.execute(
                    null,
                    "CREATE TABLE ledger_transaction (transaction_id TEXT NOT NULL PRIMARY KEY, ledger_id TEXT NOT NULL, kind TEXT NOT NULL, canonical_kind TEXT, UNIQUE (transaction_id, ledger_id))",
                    0,
                )
                driver.execute(
                    null,
                    "CREATE TABLE evidence_projection (ledger_id TEXT NOT NULL, projection_id TEXT NOT NULL, evidence_id TEXT NOT NULL, source_id TEXT NOT NULL, source_hash TEXT NOT NULL, target_account_id TEXT NOT NULL, currency_code TEXT NOT NULL, currency_precision INTEGER NOT NULL, raw_amount_minor INTEGER NOT NULL, raw_currency_precision INTEGER NOT NULL, normalized_amount_minor INTEGER NOT NULL, direction_token TEXT NOT NULL, state TEXT NOT NULL, rejection_code TEXT, rule_id TEXT NOT NULL, rule_version INTEGER NOT NULL, materialization_request_id TEXT NOT NULL, materialized_at TEXT NOT NULL, PRIMARY KEY (ledger_id, projection_id))",
                    0,
                )
                driver.execute(
                    null,
                    "CREATE TRIGGER evidence_projection_guard_update BEFORE UPDATE ON evidence_projection BEGIN SELECT RAISE(ABORT, 'cannot update evidence projection'); END",
                    0,
                )
                driver.execute(
                    null,
                    "CREATE TRIGGER evidence_projection_guard_delete BEFORE DELETE ON evidence_projection BEGIN SELECT RAISE(ABORT, 'cannot delete evidence projection'); END",
                    0,
                )
                driver.execute(null, "PRAGMA user_version = 0", 0)
            }

            JdbcSqliteDriver(url).use { driver ->
                migrateToCurrentSchema(driver)
                assertEquals(29L, driver.userVersion())
                // The v27-only objects were never created: the guards alone did not pass the gate.
                assertEquals(
                    0L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM sqlite_master WHERE name IN ('catalog_version','evidence_projection_current_by_evidence','reconciliation_correction_snapshot')",
                    ),
                )
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'evidence_projection'"))
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
            (
                listOf(
                    "catalog_command_receipt",
                    "catalog_command_request",
                    "catalog_name_history",
                    "catalog_category",
                    "catalog_account",
                    "catalog_version",
                ) + P7_V29_TABLES
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
