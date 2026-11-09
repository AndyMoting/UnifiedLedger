package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

abstract class MigrationTestSupport {
    protected fun seedV25ProjectionWorld(url: String) {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
        }
        JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
            LedgerDatabase.Schema.migrate(driver, 1, 25)
            driver.execute(null, "INSERT INTO import_request VALUES ('ledger-b26','request-b26','intake')", 0)
            driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-b26','source-b26','request-b26','batch-b26',0,'transfer_flow_source','hash-b26',2,'valid_complete',9900,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled')", 0)
            driver.execute(null, "INSERT INTO import_evidence VALUES ('ledger-b26','evidence-b26','source-b26','source_observation','2026-08-10T12:00:01+08:00')", 0)
            driver.execute(null, "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-b26','ledger-b26','ACCOUNT_TRANSFER',NULL)", 0)
            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-b26','ledger-b26')", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-b26','tx-b26','ledger-b26',1,'posting-set-b26','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)", 0)
            driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('tx-b26','ledger-b26','version-b26')", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('posting-b26-out','posting-set-b26','ledger-b26',0,'account-bank-a',-9900,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) VALUES ('ledger-b26','request-link-b26','confirm_link','fp-b26','ACCEPTED')", 0)
            driver.execute(null, "INSERT INTO reconciliation_request_snapshot(ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision) VALUES ('ledger-b26','request-link-b26','evidence-b26','candidate-b26','posting-b26-out','tx-b26',9900,'CNY',2,'out','account-bank-a','real_account_posting',1,'amount,currency,direction,occurred_at_window,account',2,0,'2026-08-10T12:00:00+08:00','2026-08-10T13:00:00+08:00','confirm_match')", 0)
            driver.execute(null, "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('ledger-b26','link-b26','evidence-b26','posting-b26-out','tx-b26','real_account_posting',1,'amount,currency,direction,occurred_at_window,account','candidate-b26','request-link-b26','2026-08-10T13:00:01+08:00')", 0)
            driver.execute(null, "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) VALUES ('ledger-b26','link-b26',1,'active','confirmed','request-link-b26','2026-08-10T13:00:01+08:00')", 0)
            driver.execute(null, "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) VALUES ('ledger-b26','reconciliation-b26-out','posting-b26-out','CHECKED',2)", 0)
            driver.execute(null, "INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('ledger-b26','reconciliation-b26-out',1,'PENDING',NULL,'request-link-b26','2026-08-10T12:00:02+08:00')", 0)
            driver.execute(null, "INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('ledger-b26','reconciliation-b26-out',2,'CHECKED','link-b26','request-link-b26','2026-08-10T13:00:02+08:00')", 0)
            driver.execute(null, "INSERT INTO reconciliation_receipt(ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence) VALUES ('ledger-b26','request-link-b26','ACCEPTED','link-b26','reconciliation-b26-out',2)", 0)
        }
    }

    protected fun seedV35CorrectionWorld(url: String) {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
        }
        JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
            LedgerDatabase.Schema.migrate(driver, 1, 35)
            driver.execute(null, "PRAGMA user_version = 35", 0)
            driver.execute(null, "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-c36','ledger-c36','EXPENSE',NULL)", 0)
            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-c36-a','ledger-c36')", 0)
            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-c36-b','ledger-c36')", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-c36-base','tx-c36','ledger-c36',1,'posting-set-c36-a','2026-10-20T09:00:00+08:00','2026-10-20T09:00:00+08:00','2026-10-20T09:00:00+08:00',NULL)", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-c36-corrected','tx-c36','ledger-c36',2,'posting-set-c36-b','2026-10-20T09:00:00+08:00','2026-10-21T18:00:00+08:00','2026-10-20T09:00:00+08:00','corrected note')", 0)
            driver.execute(null, "INSERT INTO transaction_correction_request(ledger_id, request_id, transaction_id, expected_current_version_id, note, statistics_at, amount_minor, currency_code, currency_precision, category_id, funding_account_id, confirmation_marker) VALUES ('ledger-c36','request-c36','tx-c36','version-c36-base',NULL,'2026-10-21T18:00:00+08:00',4567,'CNY',2,'category-c36','account-cash-c36','explicit_manual_save')", 0)
            driver.execute(null, "INSERT INTO transaction_correction_receipt(ledger_id, request_id, confirmation_id, transaction_id, version_id, expected_current_version_id) VALUES ('ledger-c36','request-c36','confirmation-c36','tx-c36','version-c36-corrected','version-c36-base')", 0)
        }
    }
}

internal fun insertVersionThirteenAdjustment(
    driver: JdbcSqliteDriver,
    explainedAmountMinor: Long,
    includeHistory: Boolean = true,
    historyRemainingAmountMinor: Long = 3_000 - explainedAmountMinor,
    historyState: String = if (explainedAmountMinor == 0L) "OPEN" else "PARTIALLY_EXPLAINED",
) {
    driver.execute(
        null,
        """
        INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES
          ('transaction-adjustment-v13', 'ledger-v13', 'ACCOUNT_TRANSFER', 'BALANCE_ADJUSTMENT'),
          ('transaction-real-v13', 'ledger-v13', 'ACCOUNT_TRANSFER', 'ACCOUNT_TRANSFER'),
          ('transaction-reversal-v13', 'ledger-v13', 'ACCOUNT_TRANSFER', 'BALANCE_ADJUSTMENT_REVERSAL')
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
        INSERT INTO rg09_source(
          ledger_id, source_id, source_type, observed_at, observed_at_text, account_id,
          amount_minor, currency_code, currency_precision, immutable_payload_digest
        ) VALUES (
          'ledger-v13', 'source-v13', 'BALANCE_OBSERVATION', '2026-01-31T15:59:59Z',
          '2026-01-31T23:59:59+08:00', 'asset-v13', 13000, 'CNY', 2, 'digest-v13'
        )
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
        INSERT INTO rg09_observation(
          ledger_id, observation_id, source_id, account_id, target_amount_minor,
          currency_code, currency_precision, target_observed_at, target_observed_at_text,
          saved_at, saved_at_text
        ) VALUES (
          'ledger-v13', 'observation-v13', 'source-v13', 'asset-v13', 13000, 'CNY', 2,
          '2026-01-31T15:59:59Z', '2026-01-31T23:59:59+08:00',
          '2026-02-01T00:00:00Z', '2026-02-01T08:00:00+08:00'
        )
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
        INSERT INTO rg09_balance_adjustment(
          ledger_id, adjustment_id, transaction_id, observation_id, target_account_id,
          equity_account_id, currency_code, currency_precision, target_observed_at,
          target_observed_at_text, replayed_amount_minor, target_amount_minor,
          original_delta_minor, explained_amount_minor, remaining_amount_minor, state
        ) VALUES (
          'ledger-v13', 'adjustment-v13', 'transaction-adjustment-v13', 'observation-v13',
          'asset-v13', 'equity-v13', 'CNY', 2, '2026-01-31T15:59:59Z',
          '2026-01-31T23:59:59+08:00', 10000, 13000, 3000, $explainedAmountMinor,
          ${3_000 - explainedAmountMinor},
          '${if (explainedAmountMinor == 0L) "OPEN" else "PARTIALLY_EXPLAINED"}'
        )
        """.trimIndent(),
        0,
    )
    if (includeHistory) {
        driver.execute(
            null,
            """
            INSERT INTO rg09_adjustment_history(
              ledger_id, adjustment_id, history_sequence, history_id, state, occurred_at,
              occurred_at_text, created_at, created_at_text, allocation_id, remaining_amount_minor
            ) VALUES (
              'ledger-v13', 'adjustment-v13', 1, 'history-v13',
              '$historyState',
              '2026-02-01T00:00:00Z', '2026-02-01T08:00:00+08:00',
              '2026-02-01T00:00:00Z', '2026-02-01T08:00:00+08:00',
              'allocation-v13', $historyRemainingAmountMinor
            )
            """.trimIndent(),
            0,
        )
    }
    driver.execute(
        null,
        """
        INSERT INTO rg09_allocation(
          ledger_id, allocation_id, adjustment_id, target_account_id, amount_minor,
          currency_code, currency_precision, real_transaction_id, reversal_transaction_id,
          confirmed_at, discovered_at, discovered_at_text, confirmed_at_text,
          created_at, created_at_text
        ) VALUES (
          'ledger-v13', 'allocation-v13', 'adjustment-v13', 'asset-v13', 1000, 'CNY', 2,
          'transaction-real-v13', 'transaction-reversal-v13', '2026-02-01T00:00:00Z',
          '2026-01-30T00:00:00Z', '2026-01-30T08:00:00+08:00',
          '2026-02-01T08:00:00+08:00', '2026-02-01T00:00:00Z',
          '2026-02-01T08:00:00+08:00'
        )
        """.trimIndent(),
        0,
    )
}

internal fun migrationRepositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}

internal fun queryCount(
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

internal fun triggerSql(
    driver: JdbcSqliteDriver,
    name: String,
): String =
    driver
        .executeQuery(
            null,
            "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = '$name'",
            { cursor ->
                check(cursor.next().value)
                app.cash.sqldelight.db.QueryResult
                    .Value(requireNotNull(cursor.getString(0)))
            },
            0,
        ).value

internal fun tableSql(
    driver: JdbcSqliteDriver,
    name: String,
): String =
    driver
        .executeQuery(
            null,
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$name'",
            { cursor ->
                check(cursor.next().value)
                app.cash.sqldelight.db.QueryResult
                    .Value(requireNotNull(cursor.getString(0)))
            },
            0,
        ).value

internal fun tableColumnNames(
    driver: JdbcSqliteDriver,
    table: String,
): List<String> =
    driver
        .executeQuery(
            null,
            "SELECT name FROM pragma_table_info('$table') ORDER BY cid",
            { cursor ->
                val names = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
                app.cash.sqldelight.db.QueryResult
                    .Value(names)
            },
            0,
        ).value

internal data class SchemaMetadata(
    val objects: List<String>,
    val foreignKeys: List<String>,
    val indexes: List<String>,
)

internal fun schemaMetadata(url: String): SchemaMetadata =
    DriverManager.getConnection(url).use { connection ->
        val objects =
            buildList {
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
        val tableNames =
            objects
                .asSequence()
                .filter { it.startsWith("table|") }
                .map { it.substringAfter('|').substringBefore('|') }
                .toList()
        val foreignKeys =
            buildList {
                tableNames.forEach { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA foreign_key_list('$table')").use { rows ->
                            while (rows.next()) {
                                add(
                                    listOf(
                                        table,
                                        rows.getInt("id"),
                                        rows.getInt("seq"),
                                        rows.getString("table"),
                                        rows.getString("from"),
                                        rows.getString("to"),
                                        rows.getString("on_update"),
                                        rows.getString("on_delete"),
                                        rows.getString("match"),
                                    ).joinToString("|"),
                                )
                            }
                        }
                    }
                }
            }.sorted()
        val indexes =
            buildList {
                tableNames.forEach { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA index_list('$table')").use { rows ->
                            while (rows.next()) {
                                add(
                                    listOf(
                                        table,
                                        rows.getString("name"),
                                        rows.getInt("unique"),
                                        rows.getString("origin"),
                                        rows.getInt("partial"),
                                    ).joinToString("|"),
                                )
                            }
                        }
                    }
                }
            }.sorted()
        SchemaMetadata(objects, foreignKeys, indexes)
    }

internal fun normalizeSql(sql: String): String =
    sql
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace("( ", "(")
        .replace(" )", ")")
        .replace("\"rg07_operation\"", "rg07_operation")

// sqlite_master.sql stores CREATE TRIGGER text without the trailing statement
// semicolon; drop it before comparing against the source text.
internal fun normalizeTriggerText(sql: String): String = normalizeSql(sql).removeSuffix(";")

internal fun migrationSqliteProperties(): Properties =
    Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

// The v19 CREATE TRIGGER texts (byte-identical to 18.sqm; compared normalized).
internal val V19_MATCH_GUARD_SQL =
    """
    CREATE TRIGGER rg12_match_current_guard_insert BEFORE INSERT ON rg12_reconciliation_match BEGIN
      SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM rg12_posting_semantic AS semantic
        WHERE semantic.ledger_id = new.ledger_id AND semantic.posting_id = new.posting_id
          AND semantic.reconciliation_eligible = 1
      ) THEN RAISE(ABORT, 'rg12 match current ownership')
      WHEN EXISTS (
        -- a posting that already owns a reconciliation fact cannot acquire a match:
        -- the runtime always writes match history before the fact, so a pre-existing
        -- fact means the pair (fact, match-without-history) is not representable
        SELECT 1 FROM rg12_posting_reconciliation AS fact
        WHERE fact.ledger_id = new.ledger_id AND fact.posting_id = new.posting_id
      ) THEN RAISE(ABORT, 'rg12 match current ownership') END;
    END;
    """.trimIndent()

internal val V19_FACT_GUARD_SQL =
    """
    CREATE TRIGGER rg12_reconciliation_current_guard_insert BEFORE INSERT ON rg12_posting_reconciliation BEGIN
      SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM rg12_posting_semantic AS semantic
        WHERE semantic.ledger_id = new.ledger_id AND semantic.posting_id = new.posting_id
          AND semantic.reconciliation_eligible = 1
      ) THEN RAISE(ABORT, 'rg12 reconciliation current ownership')
      WHEN EXISTS (
        -- a match that never received its entry-1 history owns the posting: the
        -- match is not representable in the domain and no fact may be built on it
        SELECT 1 FROM rg12_reconciliation_match AS match_row
        WHERE match_row.ledger_id = new.ledger_id AND match_row.posting_id = new.posting_id
          AND NOT EXISTS (
            SELECT 1 FROM rg12_reconciliation_match_history AS history_row
            WHERE history_row.ledger_id = match_row.ledger_id AND history_row.match_id = match_row.match_id
          )
      ) THEN RAISE(ABORT, 'rg12 reconciliation current ownership')
      WHEN EXISTS (
        SELECT 1 FROM rg12_reconciliation_match AS match_row
        JOIN rg12_reconciliation_match_history AS latest
          ON latest.ledger_id = match_row.ledger_id AND latest.match_id = match_row.match_id
        WHERE match_row.ledger_id = new.ledger_id AND match_row.posting_id = new.posting_id
          AND latest.entry_sequence = (
            SELECT MAX(history_row.entry_sequence)
            FROM rg12_reconciliation_match_history AS history_row
            WHERE history_row.ledger_id = match_row.ledger_id AND history_row.match_id = match_row.match_id
          )
          AND latest.status = 'MATCHED'
          AND new.status != 'MATCHED'
      ) THEN RAISE(ABORT, 'rg12 reconciliation current ownership')
      WHEN new.status = 'MATCHED' AND NOT EXISTS (
        SELECT 1 FROM rg12_reconciliation_match AS match_row
        JOIN rg12_reconciliation_match_history AS latest
          ON latest.ledger_id = match_row.ledger_id AND latest.match_id = match_row.match_id
        WHERE match_row.ledger_id = new.ledger_id AND match_row.posting_id = new.posting_id
          AND latest.entry_sequence = (
            SELECT MAX(history_row.entry_sequence)
            FROM rg12_reconciliation_match_history AS history_row
            WHERE history_row.ledger_id = match_row.ledger_id AND history_row.match_id = match_row.match_id
          )
          AND latest.status = 'MATCHED'
      ) THEN RAISE(ABORT, 'rg12 reconciliation current ownership')
      END;
    END;
    """.trimIndent()

internal val V19_HISTORY_GUARD_SQL =
    """
    CREATE TRIGGER rg12_match_history_fact_consistency BEFORE INSERT ON rg12_reconciliation_match_history BEGIN
      SELECT CASE WHEN new.status = 'MATCHED' AND EXISTS (
        SELECT 1 FROM rg12_reconciliation_match AS match_row
        JOIN rg12_posting_reconciliation AS fact
          ON fact.ledger_id = match_row.ledger_id AND fact.posting_id = match_row.posting_id
        WHERE match_row.ledger_id = new.ledger_id AND match_row.match_id = new.match_id
          AND fact.status = 'PENDING'
      ) THEN RAISE(ABORT, 'rg12 match history fact consistency') END;
    END;
    """.trimIndent()

internal val VERSION_ONE_STATEMENTS =
    listOf(
        """
        CREATE TABLE ledger_transaction (
          transaction_id TEXT NOT NULL PRIMARY KEY,
          ledger_id TEXT NOT NULL,
          kind TEXT NOT NULL CHECK (kind IN ('OPENING_BALANCE', 'EXPENSE')),
          UNIQUE (transaction_id, ledger_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE posting_set (
          posting_set_id TEXT NOT NULL PRIMARY KEY,
          ledger_id TEXT NOT NULL,
          UNIQUE (posting_set_id, ledger_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE transaction_version (
          version_id TEXT NOT NULL PRIMARY KEY,
          transaction_id TEXT NOT NULL,
          ledger_id TEXT NOT NULL,
          version_number INTEGER NOT NULL CHECK (version_number > 0),
          posting_set_id TEXT NOT NULL,
          occurred_at TEXT NOT NULL,
          statistics_at TEXT NOT NULL,
          effective_at TEXT NOT NULL,
          note TEXT,
          UNIQUE (transaction_id, version_number),
          UNIQUE (transaction_id, version_id, ledger_id),
          FOREIGN KEY (transaction_id, ledger_id)
            REFERENCES ledger_transaction(transaction_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED,
          FOREIGN KEY (posting_set_id, ledger_id)
            REFERENCES posting_set(posting_set_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED
        )
        """.trimIndent(),
        """
        CREATE TABLE posting (
          posting_id TEXT NOT NULL PRIMARY KEY,
          posting_set_id TEXT NOT NULL,
          ledger_id TEXT NOT NULL,
          posting_index INTEGER NOT NULL CHECK (posting_index >= 0),
          account_id TEXT NOT NULL,
          amount_minor INTEGER NOT NULL,
          currency_code TEXT NOT NULL,
          currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
          UNIQUE (posting_set_id, posting_index),
          FOREIGN KEY (posting_set_id, ledger_id)
            REFERENCES posting_set(posting_set_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED
        )
        """.trimIndent(),
        """
        CREATE TABLE ledger_transaction_current_version (
          transaction_id TEXT NOT NULL,
          ledger_id TEXT NOT NULL,
          current_version_id TEXT NOT NULL,
          PRIMARY KEY (transaction_id, ledger_id),
          FOREIGN KEY (transaction_id, ledger_id)
            REFERENCES ledger_transaction(transaction_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED,
          FOREIGN KEY (transaction_id, current_version_id, ledger_id)
            REFERENCES transaction_version(transaction_id, version_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED
        )
        """.trimIndent(),
        "INSERT INTO posting_set VALUES ('posting-set-existing', 'ledger-a')",
        "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-existing', 'ledger-a', 'EXPENSE')",
        """
        INSERT INTO transaction_version VALUES (
          'version-existing-v1', 'tx-existing', 'ledger-a', 1, 'posting-set-existing',
          '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', NULL
        )
        """.trimIndent(),
        "INSERT INTO ledger_transaction_current_version VALUES ('tx-existing', 'ledger-a', 'version-existing-v1')",
        """
        INSERT INTO posting VALUES (
          'posting-expense-existing', 'posting-set-existing', 'ledger-a', 0,
          'expense-account-breakfast', 3580, 'CNY', 2
        )
        """.trimIndent(),
        """
        INSERT INTO posting VALUES (
          'posting-bank-existing', 'posting-set-existing', 'ledger-a', 1,
          'asset-bank-a', -3580, 'CNY', 2
        )
        """.trimIndent(),
    )

internal fun selectRowsFlat(
    url: String,
    table: String,
): List<List<String?>> =
    DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $table ORDER BY 1,2,3,4").use { rows ->
                val meta = rows.metaData
                val out = mutableListOf<List<String?>>()
                while (rows.next()) {
                    out.add((1..meta.columnCount).map { rows.getObject(it)?.toString() })
                }
                out
            }
        }
    }

internal fun selectRaw(
    url: String,
    sql: String,
): List<String> =
    DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val out = mutableListOf<String>()
                while (rows.next()) out.add(rows.getString(1))
                out
            }
        }
    }
