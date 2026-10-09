package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import java.nio.file.Files
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 07.T (D-184 item 4) v31 -> v32 time-projection migration evidence.
 *
 * The edge adds `transaction_version.statistics_at_epoch_nanos` (epoch nanoseconds, spec section
 * 6.5 option (b)), its range index `(ledger_id, statistics_at_epoch_nanos, transaction_id)`, and
 * backfills every existing `statistics_at` (spec section 6.4 items 2/3).
 *
 * Covered here:
 *  - mixed historical formats (whole-second "Z", millisecond "Z", and raw offset "+08:00" text)
 *    backfill to the correct projection, and the Kotlin mirror agrees with the SQL;
 *  - an unparseable row aborts with the typed diagnostic BEFORE any structure or data change, and
 *    the database stays openable (the P3-3 anti-brick fix) — the v31 surface is byte-for-byte
 *    intact and `user_version` stays 31. The read-only pre-check runs before the ADD COLUMN, so
 *    the guarantee holds even for an unwrapped caller (no half-added column to retry against);
 *  - the window returns only the current effective version per transaction (spec section 6.4
 *    item 6), so corrected transactions do not double count and voided ones do not appear;
 *  - the pre-check refuses SQL-parseable but Kotlin-unparseable or out-of-window shapes;
 *  - the range index exists with the exact fresh Ledger.sq definition, and fresh = migrated schema
 *    text for the projection objects.
 *
 * A v31 database is staged as `Schema.create` (v32) minus the projection column and index with
 * `PRAGMA user_version = 31` (the 30.sqm sentinel discipline precedent).
 */
class P707TimeProjectionMigrationV31ToV32Test {
    @Test
    fun versionThirtyTwoIsCurrent() {
        assertEquals(35, LedgerDatabase.Schema.version)
    }

    @Test
    fun versionThirtyOneToThirtyTwoAddsTheProjectionAndBackfillsEveryObservedFormat() {
        val path = Files.createTempFile("p707t-v31-v32-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV31(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // Three real historical shapes: whole-second Z, millisecond Z, and a raw offset
                // string (the RG-03/04/05 passthrough form). Two of them denote the SAME instant
                // in different text, which lexicographic order cannot see (spec section 6.3).
                seedVersion(driver, "version-z-whole", "2026-03-05T02:00:00Z")
                seedVersion(driver, "version-z-millis", "2026-03-05T02:00:00.500Z")
                seedVersion(driver, "version-offset", "2026-01-21T11:00:00+08:00")
                seedVersion(driver, "version-offset-equal", "2026-01-21T03:00:00Z")
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 31, 32)
                driver.execute(null, "PRAGMA user_version = 32", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(32L, queryLong(driver, "PRAGMA user_version"))
                // The original TEXT column is untouched (spec section 6.4 item 1).
                assertEquals(
                    "2026-03-05T02:00:00.500Z",
                    queryText(driver, "SELECT statistics_at FROM transaction_version WHERE version_id = 'version-z-millis'"),
                )
                // Every row is projected, and the projection equals the Kotlin mirror of the same
                // instant (the SQL and Kotlin halves cannot drift).
                assertEquals(
                    1_772_676_000_000_000_000L,
                    queryLong(driver, "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = 'version-z-whole'"),
                )
                assertEquals(
                    1_772_676_000_500_000_000L,
                    queryLong(driver, "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = 'version-z-millis'"),
                )
                assertEquals(
                    1_768_964_400_000_000_000L,
                    queryLong(driver, "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = 'version-offset'"),
                )
                // The two equal instants in different text project to the SAME value: the whole
                // point of the numeric projection.
                assertEquals(
                    queryLong(driver, "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = 'version-offset'"),
                    queryLong(driver, "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = 'version-offset-equal'"),
                )
                // The Kotlin mirror agrees with the SQL backfill for all four shapes.
                listOf(
                    "version-z-whole" to "2026-03-05T02:00:00Z",
                    "version-z-millis" to "2026-03-05T02:00:00.500Z",
                    "version-offset" to "2026-01-21T11:00:00+08:00",
                    "version-offset-equal" to "2026-01-21T03:00:00Z",
                ).forEach { (versionId, text) ->
                    val expected = StatisticsAtProjection.project(kotlin.time.Instant.parse(text))
                    assertEquals(
                        expected,
                        queryLong(driver, "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = '$versionId'"),
                        "projection drift for $versionId ($text)",
                    )
                }
                // The fail-loud probe reads zero on a correctly migrated ledger.
                assertEquals(0L, LedgerDatabase(driver).ledgerQueries.statisticsAtProjectionMissingForLedger("ledger-a").executeAsOne())
                // The range index exists with the exact fresh definition.
                assertEquals(
                    1L,
                    queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'transaction_version_statistics_at_range_idx'"),
                )
                assertEquals(
                    "CREATE INDEX transaction_version_statistics_at_range_idx\n  ON transaction_version(ledger_id, statistics_at_epoch_nanos, transaction_id)",
                    queryText(driver, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'transaction_version_statistics_at_range_idx'"),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun theReadPortReturnsOnlyTheCurrentEffectiveVersionPerTransaction() {
        // P2-1 (spec section 6.4 item 6): the window must return the CURRENT version of each
        // EFFECTIVE transaction. A corrected transaction contributes only its latest version
        // (superseded versions must not appear, or the budget read would double count), and a
        // voided transaction contributes nothing. Without the effective/current-version join
        // this test goes RED (it would see version-old and tx-voided too).
        val path = Files.createTempFile("p707t-effective-window-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                // Corrected transaction: version-old then version-new (current), same window.
                seedVersion(database, driver, "version-old", "tx-corrected", "2026-03-05T02:00:00Z", versionNumber = 1L)
                seedVersion(database, driver, "version-new", "tx-corrected", "2026-03-06T02:00:00Z", versionNumber = 2L)
                // Voided transaction: one version in the window, but not effective.
                seedVersion(database, driver, "version-voided", "tx-voided", "2026-03-07T02:00:00Z")
                seedVoidFact(driver, "tx-voided", "version-voided")

                val port = SqlDelightStatisticsAtProjectionReadPort(database)
                val marchStart = requireNotNull(StatisticsAtProjection.project(Instant.parse("2026-03-01T00:00:00Z")))
                val aprilStart = requireNotNull(StatisticsAtProjection.project(Instant.parse("2026-04-01T00:00:00Z")))
                val rows = port.projectedVersionsInWindow(LedgerId("ledger-a"), marchStart, aprilStart)
                assertEquals(listOf("version-new"), rows.map { it.versionId.value })
                // Sanity: the raw table really does hold all three rows, so the single returned
                // row is the join's effect and not a missing seed.
                assertEquals(3L, queryLong(driver, "SELECT count(*) FROM transaction_version WHERE ledger_id = 'ledger-a'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun anUnparseableRowAbortsWithTheTypedDiagnosticAndLeavesTheDatabaseOpenable() {
        val path = Files.createTempFile("p707t-v31-v32-brick-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV31(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                seedVersion(driver, "version-ok", "2026-03-05T02:00:00Z")
                // A row whose statistics_at has no Z/offset terminator: outside the declared
                // bound and unparseable as an ISO instant of the supported shape.
                seedVersion(driver, "version-bad", "not-a-timestamp")
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                val failure =
                    assertFailsWith<SQLException> {
                        LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 31, 32) }
                    }
                // The typed diagnostic name is the guard constraint, and it reports no raw text.
                assertTrue(
                    failure.message.orEmpty().contains("P707T_UNPARSEABLE_STATISTICS_AT"),
                    "expected the typed diagnostic, got: ${failure.message}",
                )
                assertTrue(!failure.message.orEmpty().contains("not-a-timestamp"), "diagnostic must not leak raw time text")
            }
            // The anti-brick fix: the database is still openable and byte-for-byte the v31 surface.
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(31L, queryLong(driver, "PRAGMA user_version"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM pragma_table_info('transaction_version') WHERE name = 'statistics_at_epoch_nanos'"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'transaction_version_statistics_at_range_idx'"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'p707t\\_%' ESCAPE '\\'"))
                // The pre-existing rows survive value-for-value.
                assertEquals(2L, queryLong(driver, "SELECT count(*) FROM transaction_version"))
                assertEquals("not-a-timestamp", queryText(driver, "SELECT statistics_at FROM transaction_version WHERE version_id = 'version-bad'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun anUnwrappedMigrationOnABadRowLeavesTheDatabaseRemigratableWithoutDuplicateColumn() {
        // P2-2: the read-only pre-check runs BEFORE the ADD COLUMN, so even a caller that runs
        // Schema.migrate OUTSIDE any transaction cannot leave the projection column added before
        // the abort. The database stays re-migratable: fixing the bad row and retrying succeeds
        // (an ALTER-first order would have left the column and failed on "duplicate column").
        val path = Files.createTempFile("p707t-unwrapped-brick-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            stageV31(url)
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                seedVersion(driver, "version-bad", "not-a-timestamp")
            }
            // First attempt: NO outer transaction (a hypothetical unwrapped caller).
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertFailsWith<SQLException> {
                    LedgerDatabase.Schema.migrate(driver, 31, 32)
                }
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                // The abort happened before the ALTER: the projection column was never added, so
                // the database is still v31 and re-migratable. The pre-check guard table may be
                // left behind by an unwrapped abort (SQLite has no implicit per-statement
                // rollback), which is why the migration begins the pre-check with DROP TABLE IF
                // EXISTS; the retry below proves that residue does not brick.
                assertEquals(31L, queryLong(driver, "PRAGMA user_version"))
                assertEquals(
                    0L,
                    queryLong(driver, "SELECT count(*) FROM pragma_table_info('transaction_version') WHERE name = 'statistics_at_epoch_nanos'"),
                )
                // Repair the row and retry: the migration now succeeds with no duplicate-column error.
                driver.execute(null, "UPDATE transaction_version SET statistics_at = '2026-03-05T02:00:00Z' WHERE version_id = 'version-bad'", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 31, 32)
                driver.execute(null, "PRAGMA user_version = 32", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                assertEquals(32L, queryLong(driver, "PRAGMA user_version"))
                assertEquals(
                    1L,
                    queryLong(driver, "SELECT count(*) FROM pragma_table_info('transaction_version') WHERE name = 'statistics_at_epoch_nanos'"),
                )
                assertEquals(1_772_676_000_000_000_000L, queryLong(driver, "SELECT statistics_at_epoch_nanos FROM transaction_version WHERE version_id = 'version-bad'"))
                // The successful retry left no guard residue (the DROP IF EXISTS cleared it).
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'p707t\\_%' ESCAPE '\\'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun thePreCheckRejectsKotlinUnparseableAndOutOfWindowShapes() {
        // P3-2/P3-6: the pre-check must refuse forms SQLite's strftime accepts but
        // kotlin.time.Instant.parse rejects (2026-02-30 rolls to March, a date-only string, a
        // space separator), and parseable-but-out-of-window timestamps (9999). Each is seeded
        // alone so exactly one bad row aborts the migration with the typed diagnostic.
        listOf(
            "2026-02-30T00:00:00Z" to "rolled calendar date",
            "2026-01-21" to "date-only",
            "2026-01-21 11:00:00Z" to "space separator",
            "9999-01-01T00:00:00Z" to "outside the 64-bit window",
        ).forEach { (badText, reason) ->
            val path = Files.createTempFile("p707t-precheck-reject-", ".db")
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            try {
                stageV31(url)
                JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                    seedVersion(driver, "version-bad", badText)
                }
                JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                    val failure =
                        assertFailsWith<SQLException>(reason) {
                            LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 31, 32) }
                        }
                    assertTrue(
                        failure.message.orEmpty().contains("P707T_UNPARSEABLE_STATISTICS_AT"),
                        "$reason: expected the typed diagnostic, got: ${failure.message}",
                    )
                }
                JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                    // Nothing was added and the row survived: the database is untouched and openable.
                    assertEquals(31L, queryLong(driver, "PRAGMA user_version"))
                    assertEquals(
                        0L,
                        queryLong(driver, "SELECT count(*) FROM pragma_table_info('transaction_version') WHERE name = 'statistics_at_epoch_nanos'"),
                    )
                    assertEquals(badText, queryText(driver, "SELECT statistics_at FROM transaction_version WHERE version_id = 'version-bad'"))
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun freshSchemaEqualsMigratedSchemaForTheProjectionObjects() {
        val freshPath = Files.createTempFile("p707t-v31-v32-fresh-", ".db")
        val migratedPath = Files.createTempFile("p707t-v31-v32-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationProperties()).use { driver -> LedgerDatabase.Schema.create(driver) }
            stageV31(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 31, 32)
            }
            // The projection index compares equal; the column list of transaction_version is
            // compared by name/type/nullability (the ALTER-appended column's stored text differs
            // in whitespace only from the inline DDL, so a raw text compare is not the contract).
            assertEquals(
                indexSql(migratedUrl, "transaction_version_statistics_at_range_idx"),
                indexSql(freshUrl, "transaction_version_statistics_at_range_idx"),
            )
            assertEquals(columnInfo(migratedUrl), columnInfo(freshUrl))
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun sameVersionReopenIsANoOp() {
        val path = Files.createTempFile("p707t-v32-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 32", 0)
            }
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 32, 32)
                assertEquals(32L, queryLong(driver, "PRAGMA user_version"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'transaction_version_statistics_at_range_idx'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun theReadPortFailsLoudOnAMissingProjectionInsteadOfTreatingItAsTimeZero() {
        val path = Files.createTempFile("p707t-missing-projection-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                seedProjectedVersion(LedgerDatabase(driver), driver, "version-projected", "2026-03-05T02:00:00Z")
                // A second version row on the SAME transaction, with an absent projection: the
                // read that needs the projection must fail loudly (spec section 6.4 item 5),
                // never treat it as time zero.
                driver.execute(
                    null,
                    "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                        "VALUES ('version-unprojected', 'tx-version-projected', 'ledger-a', 2, 'posting-set-version-projected', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', NULL)",
                    0,
                )
                val port = SqlDelightStatisticsAtProjectionReadPort(LedgerDatabase(driver))
                val failure =
                    assertFailsWith<MissingStatisticsAtProjectionException> {
                        port.projectedVersionsInWindow(com.unifiedledger.domain.LedgerId("ledger-a"), 0L, Long.MAX_VALUE)
                    }
                assertEquals(1L, failure.missingCount)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun theReadPortReturnsProjectedRowsInOrderWithinTheWindow() {
        val path = Files.createTempFile("p707t-window-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, migrationProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                seedProjectedVersion(database, driver, "version-march", "2026-03-05T02:00:00Z")
                seedProjectedVersion(database, driver, "version-april", "2026-04-05T02:00:00Z")
                val port = SqlDelightStatisticsAtProjectionReadPort(database)
                val marchStart = requireNotNull(StatisticsAtProjection.project(kotlin.time.Instant.parse("2026-03-01T00:00:00Z")))
                val aprilStart = requireNotNull(StatisticsAtProjection.project(kotlin.time.Instant.parse("2026-04-01T00:00:00Z")))
                val rows = port.projectedVersionsInWindow(com.unifiedledger.domain.LedgerId("ledger-a"), marchStart, aprilStart)
                assertEquals(listOf("version-march"), rows.map { it.versionId.value })
                // The projected row round-trips back to its exact instant.
                assertEquals(kotlin.time.Instant.parse("2026-03-05T02:00:00Z"), rows.single().statisticsAt)
                assertNotNull(StatisticsAtProjection.toInstant(rows.single().statisticsAtEpochNanos))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /** `Schema.create` at the current version minus the v32 projection column/index, stamped v31. */
    private fun stageV31(url: String) {
        JdbcSqliteDriver(url, migrationProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
            driver.execute(null, "DROP INDEX transaction_version_statistics_at_range_idx", 0)
            driver.execute(null, "ALTER TABLE transaction_version DROP COLUMN statistics_at_epoch_nanos", 0)
            driver.execute(null, "PRAGMA user_version = 31", 0)
        }
    }

    /** Inserts a version row plus its transaction/current-version chain on the v31 surface. */
    private fun seedVersion(
        driver: JdbcSqliteDriver,
        versionId: String,
        statisticsAt: String,
    ) {
        val transactionId = "tx-$versionId"
        val postingSetId = "posting-set-$versionId"
        driver.execute(
            null,
            "INSERT OR IGNORE INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId', 'ledger-a', 'EXPENSE')",
            0,
        )
        driver.execute(null, "INSERT OR IGNORE INTO posting_set(posting_set_id, ledger_id) VALUES ('$postingSetId', 'ledger-a')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                "VALUES ('$versionId', '$transactionId', 'ledger-a', 1, '$postingSetId', '$statisticsAt', '$statisticsAt', '$statisticsAt', NULL)",
            0,
        )
        driver.execute(
            null,
            "INSERT OR REPLACE INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ('$transactionId', 'ledger-a', '$versionId')",
            0,
        )
    }

    /** Inserts a fully projected version row through the production write statement. */
    private fun seedProjectedVersion(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        versionId: String,
        statisticsAt: String,
    ) {
        val transactionId = "tx-$versionId"
        val postingSetId = "posting-set-$versionId"
        database.ledgerQueries.insertTransaction(transactionId, "ledger-a", "EXPENSE")
        database.ledgerQueries.insertPostingSet(postingSetId, "ledger-a")
        database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            "ledger-a",
            1L,
            postingSetId,
            statisticsAt,
            statisticsAt,
            statisticsAt,
            null,
        )
        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, "ledger-a", versionId)
    }

    /**
     * P7-07 07.T: seeds a projected version row on an explicit transaction id and points the
     * current version at it (used to stage corrected/voided chains). Uses raw SQL with
     * INSERT OR IGNORE so two versions of the same transaction can be staged in order.
     */
    private fun seedVersion(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        versionId: String,
        transactionId: String,
        statisticsAt: String,
        versionNumber: Long = 1L,
    ) {
        val postingSetId = "posting-set-$versionId"
        driver.execute(
            null,
            "INSERT OR IGNORE INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId', 'ledger-a', 'EXPENSE')",
            0,
        )
        driver.execute(null, "INSERT OR IGNORE INTO posting_set(posting_set_id, ledger_id) VALUES ('$postingSetId', 'ledger-a')", 0)
        database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            "ledger-a",
            versionNumber,
            postingSetId,
            statisticsAt,
            statisticsAt,
            statisticsAt,
            null,
        )
        driver.execute(
            null,
            "INSERT OR REPLACE INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ('$transactionId', 'ledger-a', '$versionId')",
            0,
        )
    }

    /** P7-07 07.T: appends a `void` fact so `transaction_effective_state` marks the tx ineffective. */
    private fun seedVoidFact(
        driver: JdbcSqliteDriver,
        transactionId: String,
        requestId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO transaction_void_request(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) " +
                "VALUES ('ledger-a', '$requestId', '$transactionId', 'void', 'mis_entered', NULL, 'explicit_manual_save')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                "VALUES ('ledger-a', '$transactionId', 1, 'fact-$requestId', 'void', 'mis_entered', NULL, '$requestId', 'confirmation-$requestId', '2026-03-08T00:00:00Z')",
            0,
        )
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

    private fun indexSql(
        url: String,
        name: String,
    ): String =
        java.sql.DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$name'").use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    /** `transaction_version` column name|type|notnull|pk, in declared order, for both surfaces. */
    private fun columnInfo(url: String): List<String> =
        java.sql.DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(transaction_version)").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add("${rows.getString("name")}|${rows.getString("type")}|${rows.getInt("notnull")}|${rows.getInt("pk")}")
                        }
                    }
                }
            }
        }
}
