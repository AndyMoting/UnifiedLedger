package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P7-07 07.T (D-184 item 4; spec sections 6.2(c)/6.4 item 4): every one of the four
 * `INSERT INTO transaction_version` statements maintains the numeric projection in the SAME
 * statement as `statistics_at`, so a version row can never exist with its time set but its
 * projection missing.
 *
 * Each statement is driven directly (no port indirection) so removing its projection clause
 * turns exactly one assertion red:
 *  - `insertTransactionVersion` (the base write and the RG-11/RG-12 appended-version path);
 *  - `copyCurrentVersionWithNewNote` (the note-update path, which copies the source projection);
 *  - `copyCurrentVersionWithNewPostingSet` (the correction new-posting-set path);
 *  - `copyCurrentVersionReusingPostingSet` (the correction reuse path).
 *
 * The appended-version path's end-to-end evidence lives in `SqlDelightRg11StoreTest`
 * (`persistAppendedVersions` writes through `insertTransactionVersion`); this file pins the
 * statement-level contract those callers depend on.
 */
class P707TimeProjectionWriteMaintenanceTest {
    @Test
    fun insertTransactionVersionProjectsTheBoundStatisticsText() {
        withDatabase { database, driver ->
            database.ledgerQueries.insertTransaction("tx-a", "ledger-a", "EXPENSE")
            database.ledgerQueries.insertPostingSet("posting-set-a", "ledger-a")
            database.ledgerQueries.insertTransactionVersion(
                "version-a",
                "tx-a",
                "ledger-a",
                1L,
                "posting-set-a",
                "2026-03-05T02:00:00Z",
                "2026-03-05T02:00:00Z",
                "2026-03-05T02:00:00Z",
                null,
            )
            assertEquals(
                1_772_676_000_000_000_000L,
                projectionOf(driver, "version-a"),
            )
            // The bound shape is the one the four statements freeze; a millisecond text keeps
            // its millisecond precision (lossless nanoseconds).
            database.ledgerQueries.insertTransactionVersion(
                "version-b",
                "tx-a",
                "ledger-a",
                2L,
                "posting-set-a",
                "2026-03-05T02:00:00Z",
                "2026-03-05T02:00:00.500Z",
                "2026-03-05T02:00:00Z",
                null,
            )
            assertEquals(
                1_772_676_000_500_000_000L,
                projectionOf(driver, "version-b"),
            )
        }
    }

    @Test
    fun copyCurrentVersionWithNewNoteCarriesTheSourceProjection() {
        withDatabase { database, driver ->
            seedVersion(database, driver, "version-1", "tx-a", "2026-01-21T11:00:00+08:00")
            database.ledgerQueries.copyCurrentVersionWithNewNote(
                version_id = "version-2",
                note = "updated note",
                transaction_id = "tx-a",
                ledger_id = "ledger-a",
                expected_current_version_id = "version-1",
            )
            // The copy carries the source's projection verbatim (the spec's "carry the source
            // version's projection" form), so the note-update row is never unprojected.
            assertEquals(projectionOf(driver, "version-1"), projectionOf(driver, "version-2"))
            assertEquals(1_768_964_400_000_000_000L, projectionOf(driver, "version-2"))
        }
    }

    @Test
    fun copyCurrentVersionWithNewPostingSetProjectsTheRequestStatisticsText() {
        withDatabase { database, driver ->
            seedVersion(database, driver, "version-1", "tx-a", "2026-03-05T02:00:00Z")
            database.ledgerQueries.copyCurrentVersionWithNewPostingSet(
                version_id = "version-2",
                new_posting_set_id = "posting-set-a",
                statistics_at = "2026-04-05T02:00:00Z",
                note = "corrected month",
                transaction_id = "tx-a",
                ledger_id = "ledger-a",
                expected_current_version_id = "version-1",
            )
            // The correction moved statistics_at, so the projection moves with it.
            assertEquals(1_775_354_400_000_000_000L, projectionOf(driver, "version-2"))
        }
    }

    @Test
    fun copyCurrentVersionReusingPostingSetProjectsTheRequestStatisticsText() {
        withDatabase { database, driver ->
            seedVersion(database, driver, "version-1", "tx-a", "2026-03-05T02:00:00Z")
            database.ledgerQueries.copyCurrentVersionReusingPostingSet(
                version_id = "version-2",
                statistics_at = "2026-04-05T02:00:00.250Z",
                note = "corrected month, same legs",
                transaction_id = "tx-a",
                ledger_id = "ledger-a",
                expected_current_version_id = "version-1",
            )
            assertEquals(1_775_354_400_250_000_000L, projectionOf(driver, "version-2"))
        }
    }

    private fun withDatabase(block: (LedgerDatabase, JdbcSqliteDriver) -> Unit) {
        val path = Files.createTempFile("p707t-write-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                block(LedgerDatabase(driver), driver)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun seedVersion(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        versionId: String,
        transactionId: String,
        statisticsAt: String,
    ) {
        database.ledgerQueries.insertTransaction(transactionId, "ledger-a", "EXPENSE")
        database.ledgerQueries.insertPostingSet("posting-set-a", "ledger-a")
        database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            "ledger-a",
            1L,
            "posting-set-a",
            statisticsAt,
            statisticsAt,
            statisticsAt,
            null,
        )
        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, "ledger-a", versionId)
    }

    private fun projectionOf(
        driver: JdbcSqliteDriver,
        versionId: String,
    ): Long =
        driver
            .executeQuery(
                null,
                "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = '$versionId'",
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }
}
