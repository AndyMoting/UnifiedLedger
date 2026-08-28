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
 * D-113 v26 → v27 additive migration acceptance (spec section 10/11, TP-14):
 * pre-guard fail-closed, staged rebuild without RENAME, zero backfill,
 * fresh=migrated schema-text equivalence, and late-stage rollback.
 */
class P408CorrectionMigrationTest {
    @Test
    fun versionIsTwentySeven() {
        assertEquals(27, LedgerDatabase.Schema.version)
    }

    @Test
    fun lateV26ToV27FailureRollsBackRebuildsAndKeepsLegacySurface() {
        val path = Files.createTempFile("p408-v26-v27-late-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 26)
                seedLegacyLinkedState(driver)
                // Occupy the late-sentinel table name so the migration aborts
                // AFTER the projection rebuild and the new snapshot table ran.
                driver.execute(
                    null,
                    "CREATE TABLE p408_v27_late_sentinel (ok INTEGER NOT NULL CHECK (ok = 1))",
                    0,
                )
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, 26, 27)
                    }
                }
            }
            // Everything rolled back together: evidence_projection kept its v26
            // shape, no correction snapshot table, no partial index, legacy rows
            // intact, and the sentinel was never created by the migration.
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    val projectionSql =
                        statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='evidence_projection'").use { rows ->
                            check(rows.next())
                            rows.getString("sql")
                        }
                    assertTrue(!projectionSql.contains("superseded_by_projection_id"), projectionSql)
                    val snapshotExists =
                        statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='reconciliation_correction_snapshot'").use { rows ->
                            check(rows.next())
                            rows.getLong(1)
                        }
                    assertEquals(0L, snapshotExists)
                    val partialIndex =
                        statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='evidence_projection_current_by_evidence'").use { rows ->
                            check(rows.next())
                            rows.getLong(1)
                        }
                    assertEquals(0L, partialIndex)
                }
            }
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM evidence_projection"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM evidence_link"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM evidence_link_history"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedV26ToV27PreservesRowsAndZeroBackfills() {
        val path = Files.createTempFile("p408-v26-v27-populated-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 26)
                seedLegacyLinkedState(driver)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase(driver).transaction {
                    LedgerDatabase.Schema.migrate(driver, 26, 27)
                }
            }
            // Row preservation with the zero backfill: every legacy projection stays
            // a CURRENT authority (superseded_by NULL), the snapshot table is empty,
            // and the legacy link/history surface is byte-identical.
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM evidence_projection"))
            assertEquals(
                1L,
                queryCount(url, "SELECT count(*) FROM evidence_projection WHERE superseded_by_projection_id IS NULL"),
            )
            // QUAL-003: the projection row survives byte-for-byte (full-column
            // value snapshot, not just counts).
            assertEquals(
                listOf(
                    "ledger-a",
                    "proj-evidence-a",
                    "evidence-a",
                    "source-a",
                    "hash-a",
                    "account-bank-a",
                    "CNY",
                    2L,
                    1000L,
                    2L,
                    1000L,
                    "out",
                    "READY",
                    null,
                    "p408_evidence_projection_v1",
                    1L,
                    "request-migrate",
                    "2026-08-10T13:00:00+08:00",
                    null,
                ),
                queryRow(
                    url,
                    "SELECT ledger_id, projection_id, evidence_id, source_id, source_hash, target_account_id, currency_code, currency_precision, raw_amount_minor, raw_currency_precision, normalized_amount_minor, direction_token, state, rejection_code, rule_id, rule_version, materialization_request_id, materialized_at, superseded_by_projection_id FROM evidence_projection",
                    longColumns = listOf(false, false, false, false, false, false, false, true, true, true, true, false, false, false, false, true, false, false, false),
                ),
            )
            assertEquals(0L, queryCount(url, "SELECT count(*) FROM reconciliation_correction_snapshot"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM evidence_link"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM evidence_link_history"))
            assertEquals(
                1L,
                queryCount(url, "SELECT count(*) FROM evidence_link_history WHERE state='active' AND reason='confirmed'"),
            )
            // The new surface objects exist under fresh schema names/texts.
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='evidence_projection_current_by_evidence'"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='evidence_projection_by_state'"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='evidence_projection_guard_update' AND sql LIKE '%superseded_by_projection_id%'"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='reconciliation_correction_snapshot_guard_update'"))
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='reconciliation_correction_snapshot_guard_delete'"))
            // The rebuild is schema-text evolution, not row mutation: formal and
            // import rows are byte-identical.
            assertEquals(
                listOf("posting-a", "posting-b", "posting-bank-existing", "posting-expense-existing"),
                queryRows(url, "SELECT posting_id FROM posting ORDER BY posting_id"),
            )
            assertEquals(1L, queryCount(url, "SELECT count(*) FROM import_evidence"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedV26WithLegalInvalidatedHistoryMigratesToV27AndMatchesFresh() {
        val migratedPath = Files.createTempFile("p408-legal-invalidation-migrated-", ".db")
        val freshPath = Files.createTempFile("p408-legal-invalidation-fresh-", ".db")
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        try {
            // Migrated side: v26 carrying a LEGAL invalidated terminal row (a link
            // with active seq1 followed by invalidated seq2). The pre-guard must
            // accept it and preserve it verbatim (SPEC-003).
            DriverManager.getConnection(migratedUrl).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 26)
                seedLinkedStateWithLegalInvalidation(driver)
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 26, 27) }
            }
            // Fresh side: the same rows on the current terminal schema.
            JdbcSqliteDriver(freshUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                seedLinkedStateWithLegalInvalidation(driver)
            }
            // TP-14 full-column row equality on the link surface (the migration must
            // carry legal invalidation history byte-for-byte).
            val linkColumns = listOf(false, false, false, false, false, false, true, false, false, false, false)
            val historyColumns = listOf(false, false, true, false, false, false, false)
            assertEquals(
                tableRows(freshUrl, "SELECT ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at FROM evidence_link ORDER BY link_id", linkColumns),
                tableRows(migratedUrl, "SELECT ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at FROM evidence_link ORDER BY link_id", linkColumns),
            )
            assertEquals(
                tableRows(freshUrl, "SELECT ledger_id, link_id, sequence, state, reason, request_id, occurred_at FROM evidence_link_history ORDER BY link_id, sequence", historyColumns),
                tableRows(migratedUrl, "SELECT ledger_id, link_id, sequence, state, reason, request_id, occurred_at FROM evidence_link_history ORDER BY link_id, sequence", historyColumns),
            )
        } finally {
            Files.deleteIfExists(migratedPath)
            Files.deleteIfExists(freshPath)
        }
    }

    @Test
    fun freshV27SchemaTextEqualsMigratedSchemaText() {
        val migratedPath = Files.createTempFile("p408-fresh-migrated-", ".db")
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        val freshPath = Files.createTempFile("p408-fresh-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        try {
            DriverManager.getConnection(migratedUrl).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 27)
            }
            JdbcSqliteDriver(freshUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
            }
            val fresh = schemaMetadata(freshUrl)
            val migrated = schemaMetadata(migratedUrl)
            assertEquals(fresh.size, migrated.size)
            val mismatches = fresh.zip(migrated).withIndex().filter { it.value.first != it.value.second }
            assertEquals(
                emptyList<IndexedValue<Pair<String, String>>>(),
                mismatches.map { IndexedValue(it.index, it.value) }.take(5),
            )
            assertEquals(fresh, migrated)
        } finally {
            Files.deleteIfExists(migratedPath)
            Files.deleteIfExists(freshPath)
        }
    }

    // ------------------------------------------------------------------ data

    /** v26-era linked state: import source/evidence, a formal transfer, one active
     * link plus one (v26) projection row — the exact baseline the migration must
     * preserve with zero backfill. */
    private fun seedLegacyLinkedState(driver: JdbcSqliteDriver) {
        val statements =
            listOf(
                "INSERT INTO import_request VALUES ('ledger-a','import-a','intake')",
                "INSERT INTO import_source_record VALUES ('ledger-a','source-a','import-a','batch-a',0,'ordinary_flow_source','hash-a',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'2026-08-10T12:00:00+08:00')",
                "INSERT INTO import_evidence VALUES ('ledger-a','evidence-a','source-a','source_observation','2026-08-10T12:00:01+08:00')",
                "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-a','ledger-a','ACCOUNT_TRANSFER',NULL)",
                "INSERT INTO posting_set VALUES ('posting-set-a','ledger-a')",
                "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-a','tx-a','ledger-a',1,'posting-set-a','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
                "INSERT INTO ledger_transaction_current_version VALUES ('tx-a','ledger-a','version-a')",
                "INSERT INTO posting VALUES ('posting-a','posting-set-a','ledger-a',0,'account-bank-a',-1000,'CNY',2)",
                "INSERT INTO posting VALUES ('posting-b','posting-set-a','ledger-a',1,'account-platform-b',1000,'CNY',2)",
                "INSERT INTO reconciliation_request VALUES ('ledger-a','request-migrate','migration_seed','schema-v23-seed','ACCEPTED',NULL)",
                "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('ledger-a','link-a','evidence-a','posting-a','tx-a','real_account_posting',1,'amount,currency,direction,account,occurred_at_window','candidate-a','request-migrate','2026-08-10T13:00:00+08:00')",
                "INSERT INTO evidence_link_history VALUES ('ledger-a','link-a',1,'active','confirmed','request-migrate','2026-08-10T13:00:00+08:00')",
                "INSERT INTO evidence_projection(ledger_id, projection_id, evidence_id, source_id, source_hash, target_account_id, currency_code, currency_precision, raw_amount_minor, raw_currency_precision, normalized_amount_minor, direction_token, state, rejection_code, rule_id, rule_version, materialization_request_id, materialized_at) VALUES ('ledger-a','proj-evidence-a','evidence-a','source-a','hash-a','account-bank-a','CNY',2,1000,2,1000,'out','READY',NULL,'p408_evidence_projection_v1',1,'request-migrate','2026-08-10T13:00:00+08:00')",
            )
        statements.forEach { driver.execute(null, it, 0) }
    }

    private fun seedLinkedStateWithLegalInvalidation(driver: JdbcSqliteDriver) {
        val statements =
            listOf(
                "INSERT INTO import_request VALUES ('ledger-a','import-a','intake')",
                "INSERT INTO import_source_record VALUES ('ledger-a','source-a','import-a','batch-a',0,'ordinary_flow_source','hash-a',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'2026-08-10T12:00:00+08:00')",
                "INSERT INTO import_evidence VALUES ('ledger-a','evidence-a','source-a','source_observation','2026-08-10T12:00:01+08:00')",
                "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-a','ledger-a','ACCOUNT_TRANSFER',NULL)",
                "INSERT INTO posting_set VALUES ('posting-set-a','ledger-a')",
                "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-a','tx-a','ledger-a',1,'posting-set-a','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
                "INSERT INTO ledger_transaction_current_version VALUES ('tx-a','ledger-a','version-a')",
                "INSERT INTO posting VALUES ('posting-a','posting-set-a','ledger-a',0,'account-bank-a',-1000,'CNY',2)",
                "INSERT INTO reconciliation_request VALUES ('ledger-a','request-a','confirm_link','fp-a','ACCEPTED',NULL)",
                "INSERT INTO reconciliation_request VALUES ('ledger-a','request-correction','invalidate_link','fp-correction','ACCEPTED',NULL)",
                "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('ledger-a','link-a','evidence-a','posting-a','tx-a','real_account_posting',1,'amount,currency,direction,account,occurred_at_window','candidate-a','request-a','2026-08-10T13:00:00+08:00')",
                "INSERT INTO evidence_link_history VALUES ('ledger-a','link-a',1,'active','confirmed','request-a','2026-08-10T13:00:00+08:00')",
                "INSERT INTO evidence_link_history VALUES ('ledger-a','link-a',2,'invalidated','corrected','request-correction','2026-08-10T14:00:00+08:00')",
            )
        statements.forEach { driver.execute(null, it, 0) }
    }

    private fun tableRows(
        url: String,
        sql: String,
        longColumns: List<Boolean>,
    ): List<List<Any?>> =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                longColumns.mapIndexed { index, isLong ->
                                    if (isLong) rows.getLong(index + 1) else rows.getString(index + 1)
                                },
                            )
                        }
                    }
                }
            }
        }

    private fun queryRow(
        url: String,
        sql: String,
        longColumns: List<Boolean>,
    ): List<Any?> =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    longColumns.mapIndexed { index, isLong ->
                        if (isLong) rows.getLong(index + 1) else rows.getString(index + 1)
                    }
                }
            }
        }

    private fun queryRows(
        url: String,
        sql: String,
    ): List<String> =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString(1))
                        }
                    }
                }
            }
        }

    private fun queryCount(
        url: String,
        sql: String,
    ): Long =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }

    private fun schemaMetadata(url: String): List<String> =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT type, name, tbl_name, sql
                        FROM sqlite_master
                        WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL
                        ORDER BY type, name
                        """.trimIndent(),
                    ).use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    listOf(
                                        rows.getString("type"),
                                        rows.getString("name"),
                                        rows.getString("tbl_name"),
                                        normalizeSql(rows.getString("sql")),
                                    ).joinToString("|"),
                                )
                            }
                        }
                    }
            }
        }

    private fun normalizeSql(sql: String): String =
        sql
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace("( ", "(")
            .replace(" )", ")")

    private fun migrationSqliteProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
            setProperty("busy_timeout", "5000")
        }
}
