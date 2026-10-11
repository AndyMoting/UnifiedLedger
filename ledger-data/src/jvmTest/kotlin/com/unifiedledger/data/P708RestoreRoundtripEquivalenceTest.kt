package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P7-08 08.D A04/A08 restore round-trip evidence (spec sections 4.3/6.1; plan :181 P706-A01
 * "往返逐 owner/稳定 ID 等价…不只数交易行" and :302/:307; the acceptance registration belongs to the
 * main agent — nothing here records a vector PASS). This class closes the two latest conditional-
 * admission rings of the restore whitelist: **R-221-2 (v34)** and the **R-218-3 chain's newest two
 * links (v33)**. v1/v32 and older rings stay registered to their original batches and are NOT
 * claimed here.
 *
 * The supply chain is the real one (BackupSnapshotDriverTest.kt:27 precedent): a seeded library
 * (catalog command receipt + multi-revision annotation history with its current pointer + a manual
 * expense request row carrying the annotation columns) is copied out with `VACUUM INTO`, read back
 * through the isolated-strict-migration path, and compared TABLE BY TABLE, VALUE BY VALUE — every
 * 08.A owner table (the 11 tables D-218 §3 froze), the four `manual_*_request` annotation columns
 * and the receipt tables. Three legs:
 *
 *  - (a) v35 direct: the snapshot's authoritative version equals the current schema
 *    (`needsMigration == false`) and the read-back is value-identical;
 *  - (b) v34 → v35 (the `34.sqm` edge): the manual_*_request annotation columns are NULL on the
 *    legacy rows (the frozen "no annotation" default) and every other owner row is value-identical;
 *  - (c) v33 → v35 (the full chain through `33.sqm` + `34.sqm`): the pre-v34 rows survive
 *    value-for-value and the 11 08.A tables land EMPTY (zero backfill).
 *
 * Contract honesty (R-221-3 precedent): the v34/v33 fixtures are reverse-engineered from the fresh
 * schema (DROP COLUMN / DROP TABLE), so the legs prove the `columnInfo` contract and VALUE
 * equivalence, NOT byte-equality with a real historical v34/v33 file.
 */
class P708RestoreRoundtripEquivalenceTest {
    private val ledgerId = "ledger-restore-roundtrip"

    /** The 08.A owner tables (D-218 §3), compared value-for-value on every leg that has them. */
    private val annotationOwnerTables =
        listOf(
            "catalog_item_version",
            "catalog_tag",
            "catalog_merchant",
            "catalog_item_name_history",
            "catalog_item_command_request",
            "catalog_item_command_receipt",
            "transaction_annotation_revision",
            "transaction_annotation_tag",
            "transaction_annotation_current",
            "transaction_annotation_command_request",
            "transaction_annotation_command_receipt",
        )

    private val manualRequestTables =
        listOf(
            "manual_expense_request",
            "manual_income_request",
            "manual_transfer_request",
            "manual_lending_request",
        )

    /** The tables the v33 fixture seeds; they exist below v34 and must survive the whole chain. */
    private val preV34SurvivorTables =
        listOf("ledger_transaction", "transaction_version", "posting", "ledger_transaction_current_version")

    /**
     * The manual_*_request columns shared by v34 and v35 shapes: the value comparison across the
     * `34.sqm` edge projects onto these (the edge ADDS the two annotation columns, so a `SELECT *`
     * would compare different column counts — the annotation columns are asserted NULL separately).
     */
    private val commonManualExpenseColumns =
        "ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker"

    @Test
    fun aV35SnapshotReadsBackValueIdenticalWithoutMigration() {
        val dir = tempDir()
        val source = File(dir, "source.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V35)
            val snapshot = File(dir, "snapshot-v35")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            // Leg (a): the snapshot is already at the current schema — nothing to migrate.
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}").use { snapshotDriver ->
                assertEquals(LedgerDatabase.Schema.version, readAuthoritativeUserVersionOn(snapshotDriver))
                assertValueIdentical(source.path, snapshot.path, annotationOwnerTables + manualRequestTables + listOf("confirmed_expense_receipt"))
                // The 08.D read path returns the whole history off the restored snapshot.
                val database = LedgerDatabase(snapshotDriver)
                val history =
                    SqlDelightTransactionAnnotationStore
                        .forPlatformConfiguredDatabase(database)
                        .loadHistory(LedgerId(ledgerId), TransactionId("tx-annotated"))
                assertEquals(listOf(1L, 2L), history!!.map { it.annotationRevision })
                assertEquals(listOf("tag-a"), history[0].tagIds.map { it.value })
                assertEquals("merchant-1", history[0].merchantId?.value)
                assertEquals(0, history[1].tagIds.size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aV34SnapshotMigratesStrictlyAndReadsBackValueIdenticalWithNullAnnotationColumns() {
        val dir = tempDir()
        val source = File(dir, "source-v34.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V34)
            val snapshot = File(dir, "snapshot-v34")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}", foreignKeyProperties()).use { isolated ->
                val migrated = assertIs<StrictMigrationResult.Migrated>(migrateIsolatedSnapshotStrictlyOn(isolated, 34, setOf(1L, 31L, 32L, 33L, 34L)))
                assertEquals(34L, migrated.fromVersion)
                assertEquals(LedgerDatabase.Schema.version, migrated.targetVersion)
                // Every owner row survives value-for-value; the manual_expense_request legacy
                // row's annotation columns are NULL == the frozen "no annotation" default
                // (D-221 R2) and the three unseeded manual tables stay empty.
                assertValueIdentical(source.path, snapshot.path, annotationOwnerTables)
                assertValueIdentical(
                    source.path,
                    snapshot.path,
                    manualRequestTables,
                ) { table -> if (table == "manual_expense_request") commonManualExpenseColumns else null }
                manualRequestTables.forEach { table ->
                    val expectedRows = if (table == "manual_expense_request") 1L else 0L
                    assertEquals(expectedRows, countOn(isolated, "SELECT count(*) FROM $table"), table)
                }
                assertEquals(
                    1L,
                    countOn(isolated, "SELECT count(*) FROM manual_expense_request WHERE annotation_tag_ids IS NULL AND annotation_merchant_id IS NULL"),
                    "the legacy row must read as the frozen no-annotation default",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aV33SnapshotMigratesThroughTheFullChainWithZeroBackfillAndSurvivingRows() {
        val dir = tempDir()
        val source = File(dir, "source-v33.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V33)
            val snapshot = File(dir, "snapshot-v33")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}", foreignKeyProperties()).use { isolated ->
                assertIs<StrictMigrationResult.Migrated>(migrateIsolatedSnapshotStrictlyOn(isolated, 33, setOf(1L, 31L, 32L, 33L)))
                // The 11 08.A tables land as the fresh schema (columnInfo contract) and EMPTY —
                // a purely additive chain never invents annotation rows (zero backfill).
                val fresh = Files.createTempFile("p708-roundtrip-fresh-", ".db")
                try {
                    JdbcSqliteDriver("jdbc:sqlite:${fresh.absolutePathString()}", foreignKeyProperties()).use { freshDriver ->
                        LedgerDatabase.Schema.create(freshDriver)
                        annotationOwnerTables.forEach { table ->
                            assertEquals(columnInfo(freshDriver, table), columnInfo(isolated, table), "$table fresh-vs-migrated columnInfo drift")
                            assertEquals(0L, countOn(isolated, "SELECT count(*) FROM $table"), table)
                        }
                    }
                } finally {
                    Files.deleteIfExists(fresh)
                }
                // The pre-v34 owner rows survive value-for-value through the whole chain (the
                // manual claim row projected onto its v33 columns — the edge added the two
                // annotation columns).
                assertValueIdentical(source.path, snapshot.path, preV34SurvivorTables)
                assertValueIdentical(source.path, snapshot.path, listOf("manual_expense_request")) { commonManualExpenseColumns }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFailedMigrationLeavesTheSnapshotUntouched() {
        val dir = tempDir()
        val source = File(dir, "source-v34-rollback.db")
        try {
            seedCurrentLibrary("jdbc:sqlite:${source.path}", Stage.V34)
            val snapshot = File(dir, "snapshot-v34-rollback")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            // Occupy the late sentinel so the migration aborts inside its outer transaction.
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}", foreignKeyProperties()).use { isolated ->
                isolated.execute(null, "CREATE TABLE manual_annotation_v35_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))", 0)
                assertIs<StrictMigrationResult.Failed>(migrateIsolatedSnapshotStrictlyOn(isolated, 34, setOf(34L)))
                assertEquals(34L, readAuthoritativeUserVersionOn(isolated))
            }
            // The failed migration left the snapshot's DATA untouched: the owner tables still
            // read value-for-value against the source library (the isolation guard's round-trip
            // side: a failed restore must never damage the source snapshot). The byte-level claim
            // is NOT asserted: the sentinel tamper above is a deliberate write to this copy.
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}").use { reopened ->
                assertEquals(34L, readAuthoritativeUserVersionOn(reopened))
                assertValueIdentical(source.path, snapshot.path, annotationOwnerTables)
                assertValueIdentical(source.path, snapshot.path, manualRequestTables) { table ->
                    if (table == "manual_expense_request") commonManualExpenseColumns else null
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ seeding

    private enum class Stage {
        /** Fresh current schema. */
        V35,

        /** Current schema minus the four manual_*_request annotation columns. */
        V34,

        /** v34 minus the 11 08.A owner tables. */
        V33,
    }

    /**
     * Seeds one anonymous library at [stage]: a manual expense chain + its claim row and receipt
     * (with the annotation columns at v35 only), and — at v34/v35 — the tag catalog with its
     * command receipt plus a two-revision annotation history with its current pointer.
     */
    private fun seedCurrentLibrary(
        url: String,
        stage: Stage,
    ) {
        JdbcSqliteDriver(url, foreignKeyProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            if (stage != Stage.V35) {
                driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
                if (stage == Stage.V33) {
                    annotationOwnerTables.forEach { table -> driver.execute(null, "DROP TABLE $table", 0) }
                }
                manualRequestTables.forEach { table ->
                    driver.execute(null, "ALTER TABLE $table DROP COLUMN annotation_tag_ids", 0)
                    driver.execute(null, "ALTER TABLE $table DROP COLUMN annotation_merchant_id", 0)
                }
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            }
            driver.execute(null, "PRAGMA user_version = ${stageVersion(stage)}", 0)

            val database = LedgerDatabase(driver)
            // One manual expense chain.
            database.ledgerQueries.insertTransaction("tx-annotated", ledgerId, "EXPENSE")
            database.ledgerQueries.insertPostingSet("tx-annotated-posting-set-1", ledgerId)
            database.ledgerQueries.insertTransactionVersion(
                "tx-annotated-version-1",
                "tx-annotated",
                ledgerId,
                1L,
                "tx-annotated-posting-set-1",
                "2026-03-05T02:00:00Z",
                "2026-03-05T02:00:00Z",
                "2026-03-05T02:00:00Z",
                "lunch",
            )
            database.ledgerQueries.insertPosting("tx-annotated-posting-0", "tx-annotated-posting-set-1", ledgerId, 0L, "expense-account-local", 3_580L, "CNY", 2L)
            database.ledgerQueries.insertPosting("tx-annotated-posting-1", "tx-annotated-posting-set-1", ledgerId, 1L, "asset-payment-local", -3_580L, "CNY", 2L)
            database.ledgerQueries.insertTransactionCurrentVersion("tx-annotated", ledgerId, "tx-annotated-version-1")

            // The manual expense claim row: the annotation columns exist only at v35.
            if (stage == Stage.V35) {
                driver.execute(
                    null,
                    "INSERT INTO manual_expense_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker, annotation_tag_ids, annotation_merchant_id) " +
                        "VALUES ('$ledgerId', 'req-annotated', 3580, 'CNY', 2, 'cat-local', 'asset-payment-local', '2026-03-05T02:00:00Z', 'lunch', 'explicit_manual_save', 'tag-a', 'merchant-1')",
                    0,
                )
            } else {
                driver.execute(
                    null,
                    "INSERT INTO manual_expense_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker) " +
                        "VALUES ('$ledgerId', 'req-annotated', 3580, 'CNY', 2, 'cat-local', 'asset-payment-local', '2026-03-05T02:00:00Z', 'lunch', 'explicit_manual_save')",
                    0,
                )
            }
            database.ledgerQueries.insertConfirmedExpenseReceipt(ledgerId, "req-annotated", "confirmation-tx-annotated", "tx-annotated")

            if (stage != Stage.V33) {
                // Tag catalog + its command receipt (the 08.A owner receipt surface).
                database.ledgerQueries.insertCatalogItemVersion(ledgerId, 0L)
                database.ledgerQueries.advanceCatalogItemVersion(ledgerId)
                database.ledgerQueries.insertCatalogTag(ledger_id = ledgerId, tag_id = "tag-a", active = 1L, tombstoned = 0L, revision = 1L)
                database.ledgerQueries.insertCatalogMerchant(ledger_id = ledgerId, merchant_id = "merchant-1", active = 1L, tombstoned = 0L, revision = 1L)
                database.ledgerQueries.insertCatalogItemNameHistory(ledger_id = ledgerId, owner_kind = "tag", owner_id = "tag-a", version_number = 1L, name = "标签甲", status = "CURRENT")
                database.ledgerQueries.insertCatalogItemNameHistory(ledger_id = ledgerId, owner_kind = "merchant", owner_id = "merchant-1", version_number = 1L, name = "商家一", status = "CURRENT")
                // The receipt's FK targets the command REQUEST row: seed the claim first (the
                // claim-first protocol row the real chain writes).
                database.ledgerQueries.claimCatalogItemCommandRequest(
                    ledger_id = ledgerId,
                    request_id = "catalog-request-tag-a",
                    command = "create_item",
                    owner_kind = "tag",
                    owner_id = "tag-a",
                    request_snapshot = "{}",
                    input_fingerprint = "fp",
                    outcome = "ACCEPTED",
                    expected_revision = 0L,
                )
                database.ledgerQueries.insertCatalogItemCommandReceipt(
                    ledger_id = ledgerId,
                    request_id = "catalog-request-tag-a",
                    outcome = "ACCEPTED",
                    owner_kind = "tag",
                    owner_id = "tag-a",
                    new_revision = 1L,
                )

                // Multi-revision annotation history + current pointer (revision 1 kept, revision 2
                // cleared — the immutable history the restore must carry whole).
                database.ledgerQueries.insertTransactionAnnotationRevision(
                    ledger_id = ledgerId,
                    transaction_id = "tx-annotated",
                    annotation_revision = 1L,
                    observed_transaction_version_id = "tx-annotated-version-1",
                    merchant_id = "merchant-1",
                    request_id = "annot-request-1",
                    created_at = "2026-03-05T03:00:00Z",
                )
                database.ledgerQueries.insertTransactionAnnotationTag(ledgerId, "tx-annotated", 1L, "tag-a")
                database.ledgerQueries.insertTransactionAnnotationRevision(
                    ledger_id = ledgerId,
                    transaction_id = "tx-annotated",
                    annotation_revision = 2L,
                    observed_transaction_version_id = "tx-annotated-version-1",
                    merchant_id = null,
                    request_id = "annot-request-2",
                    created_at = "2026-03-06T03:00:00Z",
                )
                database.ledgerQueries.insertTransactionAnnotationCurrent(ledgerId, "tx-annotated", 2L)
                // The receipt's FK targets the annotation command REQUEST row (claim-first).
                database.ledgerQueries.claimTransactionAnnotationCommandRequest(
                    ledger_id = ledgerId,
                    request_id = "annot-request-2",
                    transaction_id = "tx-annotated",
                    request_snapshot = "{}",
                    input_fingerprint = "fp",
                    expected_annotation_revision = 1L,
                    expected_current_version_id = "tx-annotated-version-1",
                    outcome = "ACCEPTED",
                )
                database.ledgerQueries.insertTransactionAnnotationCommandReceipt(ledgerId, "annot-request-2", "ACCEPTED", "tx-annotated", 2L)
            }
        }
    }

    private fun stageVersion(stage: Stage): Long =
        when (stage) {
            Stage.V35 -> 35L
            Stage.V34 -> 34L
            Stage.V33 -> 33L
        }

    // ------------------------------------------------------------------ equivalence probes

    /** Reads EVERY row of [table] on both files and compares them as values ([projection] defaults to all columns). */
    private fun assertValueIdentical(
        sourcePath: String,
        snapshotPath: String,
        tables: List<String>,
        projection: (String) -> String? = { null },
    ) {
        tables.forEach { table ->
            val columns = projection(table) ?: "*"
            assertEquals(rowsOf(sourcePath, table, columns), rowsOf(snapshotPath, table, columns), "$table round-trip drift")
        }
    }

    /**
     * All rows of [table] (rowid order), the projected columns as stored values; NULL stays NULL.
     * The JDBC access rides the same `JdbcSqliteDriver` the rest of the module uses (a bare
     * `DriverManager.getConnection` has no registered sqlite driver in this JVM).
     */
    private fun rowsOf(
        path: String,
        table: String,
        columns: String,
    ): List<List<String?>> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return try {
            driver.getConnection().createStatement().use { statement ->
                statement.executeQuery("SELECT $columns FROM $table ORDER BY rowid").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add((1..rows.metaData.columnCount).map { column -> rows.getString(column) })
                        }
                    }
                }
            }
        } finally {
            driver.close()
        }
    }

    private fun countOn(
        driver: SqlDriver,
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
        driver: SqlDriver,
        table: String,
    ): List<String> =
        driver
            .executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    val rows =
                        buildList {
                            while (cursor.next().value) {
                                add("${cursor.getString(1)}|${cursor.getString(2)}|${cursor.getLong(3)}|${cursor.getLong(5)}")
                            }
                        }
                    app.cash.sqldelight.db.QueryResult
                        .Value(rows)
                },
                0,
            ).value

    private fun tempDir(): File {
        val directory = Files.createTempDirectory("p708-restore-roundtrip").toFile()
        directory.deleteOnExit()
        return directory
    }

    private fun foreignKeyProperties(): java.util.Properties = java.util.Properties().apply { setProperty("foreign_keys", "true") }
}
