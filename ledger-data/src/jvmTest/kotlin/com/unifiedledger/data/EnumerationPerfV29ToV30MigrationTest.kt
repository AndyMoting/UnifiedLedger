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
 * P7-05 enumeration performance batch: v29 -> v30 additive migration evidence. The one new
 * object is the plain covering index `import_duplicate_candidate_subject_idx`
 * (ledger_id, subject_source_id) behind the session-level duplicate-review batch query
 * (`importDuplicateReviewsForSession`); structure-only (zero backfill), a same-name slot
 * occupation aborts the whole migration and rolls it back inside the caller's outer
 * transaction, a same-version reopen is a no-op, and fresh = migrated schema text for the
 * import duplicate-review object family (the index included).
 *
 * A v29 database is staged as `Schema.create` (v30) minus the new index with
 * `PRAGMA user_version = 29` — exactly the v29 terminal state, since the v30 migration's
 * only object is the index (the 28.sqm sentinel discipline precedent for staging older
 * surfaces without a full v1 chain).
 */
class EnumerationPerfV29ToV30MigrationTest {
    @Test
    fun versionThirtyIsCurrent() {
        assertEquals(30, LedgerDatabase.Schema.version)
    }

    @Test
    fun versionTwentyNineToThirtyAddsTheCoveringIndexWithZeroBackfill() {
        val path = Files.createTempFile("p7-v29-v30-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            // A genuine v29 database: the full current schema minus the v30 index.
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                driver.execute(null, "DROP INDEX import_duplicate_candidate_subject_idx", 0)
                driver.execute(null, "PRAGMA user_version = 29", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 29, 30)
                // The generated migrate chain does not bump PRAGMA user_version by itself on
                // this JDBC path (the migration-test convention: staged tests set it
                // explicitly); mirror the wrapper's advance.
                driver.execute(null, "PRAGMA user_version = 30", 0)
                val database = LedgerDatabase(driver)
                // Zero backfill: no duplicate-review row exists after the migration.
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM import_duplicate_candidate"))
                assertEquals(0L, database.ledgerQueries.countImportRequests().executeAsOne())
                // The new index exists with the exact fresh Ledger.sq column list.
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'import_duplicate_candidate_subject_idx'"))
                assertEquals(
                    "CREATE INDEX import_duplicate_candidate_subject_idx ON import_duplicate_candidate(ledger_id, subject_source_id)",
                    indexSql(driver, "import_duplicate_candidate_subject_idx"),
                )
                assertEquals(30L, queryLong(driver, "PRAGMA user_version"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaEqualsMigratedSchemaForTheImportDuplicateReviewFamily() {
        val freshPath = Files.createTempFile("p7-v29-v30-fresh-", ".db")
        val migratedPath = Files.createTempFile("p7-v29-v30-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                driver.execute(null, "DROP INDEX import_duplicate_candidate_subject_idx", 0)
                driver.execute(null, "PRAGMA user_version = 29", 0)
                LedgerDatabase.Schema.migrate(driver, 29, 30)
            }
            // The whole import duplicate-review object family compares equal between a fresh
            // v30 database and a migrated v29 -> v30 one — the new index included.
            assertEquals(
                schemaText(migratedUrl, "import_duplicate%"),
                schemaText(freshUrl, "import_duplicate%"),
            )
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun sameVersionReopenIsANoOp() {
        val path = Files.createTempFile("p7-v29-v30-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                // Schema.create does not seed PRAGMA user_version on this JDBC path; a
                // current-version database is user_version 30 by the migration convention.
                driver.execute(null, "PRAGMA user_version = 30", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 30, 30)
                assertEquals(30L, queryLong(driver, "PRAGMA user_version"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'import_duplicate_candidate_subject_idx'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun slotOccupationRollsBackTheWholeMigrationAndKeepsTheV29Surface() {
        val path = Files.createTempFile("p7-v29-v30-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                driver.execute(null, "DROP INDEX import_duplicate_candidate_subject_idx", 0)
                driver.execute(null, "PRAGMA user_version = 29", 0)
                // Occupy the index name slot so 29.sqm's CREATE INDEX aborts inside the
                // caller's outer transaction.
                driver.execute(null, "CREATE INDEX import_duplicate_candidate_subject_idx ON import_duplicate_candidate(ledger_id)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 29, 30) }
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(29L, queryLong(driver, "PRAGMA user_version"))
                // The occupied blocker is the only object of the aborted migration; the fresh
                // two-column index text never landed.
                assertEquals(
                    0L,
                    queryLong(
                        driver,
                        "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'import_duplicate_candidate_subject_idx' AND sql LIKE '%subject_source_id%'",
                    ),
                )
                assertTrue(queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'import_duplicate_candidate_subject_idx'") == 1L)
            }
        } finally {
            Files.deleteIfExists(path)
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

    private fun indexSql(
        driver: JdbcSqliteDriver,
        name: String,
    ): String =
        driver
            .executeQuery(
                null,
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$name'",
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
                        "SELECT type || '|' || name || '|' || replace(replace(trim(sql), '  ', ' '), char(10), ' ') FROM sqlite_master WHERE name LIKE '$namePattern' AND sql IS NOT NULL ORDER BY type, name",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getString(1))
                        }
                    }
            }
        }
}
