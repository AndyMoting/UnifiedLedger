package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LedgerMigrationV20ToV37Test : MigrationTestSupport() {
    @Test
    fun freshSchemaCreatesEveryLedgerDataTableAtVersionTwenty() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            SqlDelightConfirmedManualExpenseCommitPort(database, driver)

            assertEquals(37, LedgerDatabase.Schema.version)
            assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
            assertEquals(0, database.ledgerQueries.countRequests().executeAsOne())
            assertEquals(0, database.ledgerQueries.countReceipts().executeAsOne())
            assertEquals(0, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
            assertEquals(0, database.ledgerQueries.countIncomeReceipts().executeAsOne())
            assertEquals(0, database.ledgerQueries.countTransactionNoteUpdateRequests().executeAsOne())
            assertEquals(0, database.ledgerQueries.countTransactionNoteUpdateReceipts().executeAsOne())
            assertEquals(0, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0, database.ledgerQueries.countVersions().executeAsOne())
            assertEquals(0, database.ledgerQueries.countPostingSets().executeAsOne())
            assertEquals(0, database.ledgerQueries.countPostings().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun versionTwentyTwoToTwentyThreeSeedsOnlyCurrentEligiblePostings() {
        val path = Files.createTempFile("ledger-data-v22-v23-p408-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 22)
                val seed =
                    listOf(
                        "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-transfer','ledger-a','ACCOUNT_TRANSFER',NULL)",
                        "INSERT INTO posting_set VALUES ('posting-set-transfer','ledger-a')",
                        "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-transfer','tx-transfer','ledger-a',1,'posting-set-transfer','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
                        "INSERT INTO ledger_transaction_current_version VALUES ('tx-transfer','ledger-a','version-transfer')",
                        "INSERT INTO posting VALUES ('posting-transfer-out','posting-set-transfer','ledger-a',0,'account-bank-a',-1000,'CNY',2)",
                        "INSERT INTO posting VALUES ('posting-transfer-in','posting-set-transfer','ledger-a',1,'account-platform-b',1000,'CNY',2)",
                        "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-transfer-stale','ledger-a','ACCOUNT_TRANSFER',NULL)",
                        "INSERT INTO posting_set VALUES ('posting-set-transfer-stale','ledger-a')",
                        "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-transfer-stale','tx-transfer-stale','ledger-a',1,'posting-set-transfer-stale','2026-08-11T12:00:00+08:00','2026-08-11T12:00:00+08:00','2026-08-11T12:00:00+08:00',NULL)",
                        "INSERT INTO posting VALUES ('posting-transfer-stale','posting-set-transfer-stale','ledger-a',0,'account-bank-stale',-1000,'CNY',2)",
                        "INSERT INTO rg03_transfer_posting_semantic VALUES ('ledger-a','posting-expense-existing','TRANSFER_PRINCIPAL_OUT',NULL,1)",
                    )
                seed.forEach { driver.execute(null, it, 0) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 22, 23)
                val database = LedgerDatabase(driver)
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting_reconciliation"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting_reconciliation_history"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM reconciliation_request WHERE request_id = 'migration-v23-seed'"))
                assertEquals(
                    "PENDING",
                    database.ledgerQueries
                        .selectP408PostingReconciliation("ledger-a", "posting-transfer-out")
                        .executeAsOne()
                        .status,
                )
                assertEquals(
                    "PENDING",
                    database.ledgerQueries
                        .selectP408PostingReconciliation("ledger-a", "posting-transfer-in")
                        .executeAsOne()
                        .status,
                )
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM posting_reconciliation WHERE posting_id = 'posting-expense-existing'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM posting_reconciliation WHERE posting_id = 'posting-transfer-stale'"))
                assertEquals(3L, queryCount(driver, "SELECT count(*) FROM ledger_transaction"))
                assertEquals(5L, queryCount(driver, "SELECT count(*) FROM posting"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun confirmationOnMigratedV23AdvancesSeededReconciliationRow() {
        val path = Files.createTempFile("ledger-data-v22-v23-confirm-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 22)
                val seed =
                    listOf(
                        "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-transfer','ledger-a','ACCOUNT_TRANSFER',NULL)",
                        "INSERT INTO posting_set VALUES ('posting-set-transfer','ledger-a')",
                        "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-transfer','tx-transfer','ledger-a',1,'posting-set-transfer','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
                        "INSERT INTO ledger_transaction_current_version VALUES ('tx-transfer','ledger-a','version-transfer')",
                        "INSERT INTO posting VALUES ('posting-transfer-out','posting-set-transfer','ledger-a',0,'account-bank-a',-1000,'CNY',2)",
                        "INSERT INTO import_request VALUES ('ledger-a','import-a','intake')",
                        "INSERT INTO import_source_record VALUES ('ledger-a','source-a','import-a','batch-a',0,'ordinary_flow_source','hash-a',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled')",
                        "INSERT INTO import_evidence VALUES ('ledger-a','evidence-a','source-a','source_observation','2026-08-10T12:00:01+08:00')",
                    )
                seed.forEach { driver.execute(null, it, 0) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                // Seed at the v23 generation, then complete the chain to the current terminal so the
                // modern store (which owns the projection authority) operates on its own schema
                // (D-112 second batch). The terminal matters for the widened write face too: it reads
                // `transaction_effective_state`, which `30.sqm` creates, so a confirm on a pre-v30
                // schema would fail on a missing view.
                LedgerDatabase(driver).transaction {
                    LedgerDatabase.Schema.migrate(driver, 22, 37)
                }
                val database = LedgerDatabase(driver)
                val store = SqlDelightP408ReconciliationStore(database, driver)
                // P8-01b: catalog_account first exists at v27, so the composite-mark row the widened
                // write face requires can only be seeded after the chain passed 27.
                driver.execute(
                    null,
                    "INSERT INTO catalog_account VALUES ('ledger-a','account-bank-a','bank a','ASSET','CNY',2,1,1,NULL,0,1)",
                    0,
                )

                val seeded = database.ledgerQueries.selectP408PostingReconciliation("ledger-a", "posting-transfer-out").executeAsOne()
                assertEquals("PENDING", seeded.status)
                assertEquals(1L, seeded.latest_sequence)

                val accepted =
                    assertIs<P408ReconciliationResult.Accepted>(
                        store.confirmLink(
                            P408ConfirmLinkRequest(
                                ledgerId = "ledger-a",
                                requestId = "request-a",
                                evidenceId = "evidence-a",
                                candidateId = "candidate-transient-a",
                                postingId = "posting-transfer-out",
                                transactionId = "tx-transfer",
                                amountMinor = 1000,
                                currencyCode = "CNY",
                                currencyPrecision = 2,
                                direction = "out",
                                accountId = "account-bank-a",
                                responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                                basisVersion = 2,
                                projectionId = "proj-evidence-a",
                                projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
                                projectionRuleVersion = 1,
                                normalizedAmountMinor = 1000,
                                rawAmountMinor = 1000,
                                rawCurrencyPrecision = 2,
                                matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
                                windowDays = 2,
                                naturalDayDistance = 0,
                                sourceOccurredAt = "2026-08-10T12:00:00+08:00",
                                confirmedAt = "2026-08-10T13:00:00+08:00",
                                linkId = "link-a",
                                reconciliationId = "reconciliation-posting-transfer-out",
                                createdAt = "2026-08-10T13:00:00+08:00",
                            ),
                        ),
                    )
                assertEquals(2L, accepted.receipt.historySequence)
                assertEquals("reconciliation-posting-transfer-out", accepted.receipt.reconciliationId)

                val advanced = database.ledgerQueries.selectP408PostingReconciliation("ledger-a", "posting-transfer-out").executeAsOne()
                assertEquals("CHECKED", advanced.status)
                assertEquals(2L, advanced.latest_sequence)
                assertEquals(
                    1L,
                    queryCount(driver, "SELECT count(*) FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-posting-transfer-out' AND sequence = 1"),
                )
                assertEquals(
                    1L,
                    queryCount(driver, "SELECT count(*) FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-posting-transfer-out' AND sequence = 2"),
                )
                assertEquals(
                    1L,
                    queryCount(driver, "SELECT count(*) FROM reconciliation_receipt WHERE request_id = 'request-a'"),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionTwentyTwoToTwentyThreeDdlFailureRollsBackEverySharedP408Owner() {
        val path = Files.createTempFile("ledger-data-v22-v23-p408-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 22)
                driver.execute(null, "CREATE TABLE reconciliation_request(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction {
                        LedgerDatabase.Schema.migrate(driver, 22, 23)
                    }
                }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val sharedTables =
                    driver
                        .executeQuery(
                            null,
                            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('reconciliation_request','reconciliation_request_snapshot','evidence_link','evidence_link_history','posting_reconciliation','posting_reconciliation_history','reconciliation_receipt') ORDER BY name",
                            { cursor ->
                                val names = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
                                app.cash.sqldelight.db.QueryResult
                                    .Value(names)
                            },
                            0,
                        ).value
                assertEquals(listOf("reconciliation_request"), sharedTables)
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'reconciliation\\_%' ESCAPE '\\'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'evidence\\_link\\_%' ESCAPE '\\'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'posting\\_reconciliation\\_%' ESCAPE '\\'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshV23EqualsMigratedV23ForP408SharedObjects() {
        val freshPath = Files.createTempFile("ledger-data-fresh-v23-p408-", ".db")
        val migratedPath = Files.createTempFile("ledger-data-migrated-v23-p408-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
            }
            DriverManager.getConnection(migratedUrl).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 22)
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 22, 23)
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 23, 37)
            }

            assertEquals(schemaMetadata(freshUrl), schemaMetadata(migratedUrl))
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun freshV25EqualsMigratedV25ForP406CreditAndP407Owners() {
        val freshPath = Files.createTempFile("ledger-data-fresh-v25-p406-", ".db")
        val migratedPath = Files.createTempFile("ledger-data-migrated-v25-p406-", ".db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${freshPath.absolutePathString()}", migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
            }
            DriverManager.getConnection("jdbc:sqlite:${migratedPath.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver("jdbc:sqlite:${migratedPath.absolutePathString()}", migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 37)
            }
            assertEquals(
                schemaMetadata("jdbc:sqlite:${freshPath.absolutePathString()}"),
                schemaMetadata("jdbc:sqlite:${migratedPath.absolutePathString()}"),
            )
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun populatedV24ToV25RebuildPreservesV1V2RowsAndAddsCreditStructuresAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v24-v25-p406-populated-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 24)
                // One v1 ordinary chain and one v2 transfer chain, populated at v24.
                driver.execute(null, "INSERT INTO import_request VALUES ('ledger-p406', 'request-p406-v1', 'intake')", 0)
                driver.execute(null, "INSERT INTO import_request VALUES ('ledger-p406', 'request-p406-v2', 'intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('ledger-p406', 'source-p406-v1', 'request-p406-v1', 'batch-p406', 0, 'ordinary_flow_source', 'sha256:p406-v1', 1, 'valid_complete', 3580, 'CNY', 2, '2026-08-22T10:00:00+08:00', 'out', 'settled', 'SETTLED', 'legacy-settled-v1', 1, 'generated-v1')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('ledger-p406', 'evidence-p406-v1', 'source-p406-v1', 'source_observation', '2026-08-22T10:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO import_candidate(ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) VALUES ('ledger-p406', 'candidate-p406-v1', 'source-p406-v1', 'ordinary_flow', '1.00', 'ordinary_flow_source', 1)", 0)
                driver.execute(null, "INSERT INTO import_candidate_status_history VALUES ('ledger-p406', 'candidate-p406-v1', 1, 'history-p406-v1', 'pending_confirmation', 'request-p406-v1', 'creation')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at) VALUES ('ledger-p406', 'source-p406-v2', 'request-p406-v2', 'batch-p406', 1, 'transfer_flow_source', 'sha256:p406-v2', 2, 'valid_complete', 2000, 'CNY', 2, '2026-08-22T11:00:00+08:00', 'in', 'settled', 'SETTLED', 'legacy-settled-v1', 1, 'generated-v2')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('ledger-p406', 'evidence-p406-v2', 'source-p406-v2', 'source_observation', '2026-08-22T11:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO import_candidate(ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) VALUES ('ledger-p406', 'candidate-p406-v2', 'source-p406-v2', 'transfer_flow', '1.00', 'transfer_flow_source', 1)", 0)
                driver.execute(null, "INSERT INTO import_candidate_status_history VALUES ('ledger-p406', 'candidate-p406-v2', 1, 'history-p406-v2', 'pending_confirmation', 'request-p406-v2', 'creation')", 0)
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 24, 25) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                // v1/v2 rows survive value-for-value, funding columns included.
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-p406-v1' AND record_kind='ordinary_flow_source' AND contract_version=1 AND amount_minor=3580 AND funding_state='SETTLED' AND funding_rule_id='legacy-settled-v1' AND candidate_generated_at='generated-v1'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-p406-v2' AND record_kind='transfer_flow_source' AND contract_version=2 AND amount_minor=2000 AND funding_state='SETTLED'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_candidate WHERE candidate_id='candidate-p406-v1' AND candidate_kind='ordinary_flow'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_candidate WHERE candidate_id='candidate-p406-v2' AND candidate_kind='transfer_flow'"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM import_evidence"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM import_candidate_status_history"))
                // v3 structures exist with zero rows (built-but-unused).
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM import_candidate_payment_profile"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM mixed_payment_group"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM mixed_payment_group_leg"))
                // The five new decision-snapshot columns exist.
                assertEquals(5L, queryCount(driver, "SELECT count(*) FROM pragma_table_info('import_candidate_decision_snapshot') WHERE name IN ('credit_liability_account_id', 'asset_account_id', 'original_transaction_id', 'asset_leg_minor', 'credit_leg_minor')"))
                listOf(
                    "import_candidate_payment_profile_guard_update",
                    "import_candidate_payment_profile_guard_delete",
                    "mixed_payment_group_guard_update",
                    "mixed_payment_group_guard_delete",
                    "mixed_payment_group_leg_guard_update",
                    "mixed_payment_group_leg_guard_delete",
                    "mixed_payment_group_complete",
                    "mixed_payment_group_leg_before_head",
                ).forEach { trigger ->
                    assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'"), trigger)
                }
                // The v25 CHECK accepts a v3 kind pairing (shape contract of the rebuilt table).
                driver.execute(null, "INSERT INTO import_request VALUES ('ledger-p406', 'request-p406-v3', 'intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p406', 'source-p406-v3', 'request-p406-v3', 'batch-p406', 2, 'credit_repayment_source', 'sha256:p406-v3', 3, 'valid_complete', 5620, 'CNY', 2, '2026-08-22T12:00:00+08:00', 'out', 'settled')", 0)
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-p406-v3' AND contract_version=3"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun lateV24ToV25FailureRollsBackCreditStructuresAndSpineRows() {
        val path = Files.createTempFile("ledger-data-v24-v25-p406-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 24)
                driver.execute(null, "INSERT INTO import_request VALUES ('ledger-a', 'request-p406-rollback', 'intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-a', 'source-p406-rollback', 'request-p406-rollback', 'batch-p406-rollback', 0, 'ordinary_flow_source', 'sha256:rollback', 1, 'valid_complete', 100, 'CNY', 2, '2026-08-22T10:00:00+08:00', 'out', 'settled')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('ledger-a', 'evidence-p406-rollback', 'source-p406-rollback', 'source_observation', '2026-08-22T10:00:00+08:00')", 0)
                // Blocker occupying a Stage 1 table name: the migration must fail inside
                // the caller's transaction and roll back every created structure.
                driver.execute(null, "CREATE TABLE mixed_payment_group(blocker TEXT)", 0)
                assertFailsWith<SQLException> { LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 24, 25) } }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                // Stage 1 DDL rolled back: the credit profile table never survived.
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='import_candidate_payment_profile'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='mixed_payment_group_leg'"))
                // Stage 4 never ran or fully rolled back: the v24 decision-snapshot shape survives.
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_table_info('import_candidate_decision_snapshot') WHERE name='credit_liability_account_id'"))
                // The v24 data is intact and the failed identity stays retryable.
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-p406-rollback' AND amount_minor=100"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_evidence WHERE evidence_id='evidence-p406-rollback'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedV23ToV24RebuildPreservesImportChainAndMigrationSentinelsAcrossReopen() {
        val path = Files.createTempFile("ledger-data-v23-v24-p407-populated-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 23)
                driver.execute(null, "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('ledger-p407', 'request-p407', 'intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p407', 'source-p407', 'request-p407', 'batch-p407', 0, 'ordinary_flow_source', 'sha256:p407', 1, 'valid_complete', 1234, 'CNY', 2, '2026-08-19T10:00:00+08:00', 'out', 'settled')", 0)
                driver.execute(null, "INSERT INTO import_evidence(ledger_id, evidence_id, source_id, evidence_kind, observed_at) VALUES ('ledger-p407', 'evidence-p407', 'source-p407', 'source_observation', '2026-08-19T10:00:00+08:00')", 0)
                driver.execute(null, "INSERT INTO import_candidate(ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) VALUES ('ledger-p407', 'candidate-p407', 'source-p407', 'ordinary_flow', '1.00', 'ordinary_flow_source', 1)", 0)
                driver.execute(null, "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p407', 'candidate-p407', 1, 'history-p407', 'pending_confirmation', 'request-p407', 'creation')", 0)
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 23, 24) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-p407' AND amount_minor=1234 AND funding_state='UNRESOLVED' AND candidate_generated_at='migration-v24-unresolved'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_evidence WHERE evidence_id='evidence-p407'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_candidate_status_history WHERE status_id='history-p407'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedV23ToV24RebuildPreservesFormalAndP408GraphForeignKeysAndGuards() {
        val path = Files.createTempFile("ledger-data-v23-v24-p408-populated-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val p408Triggers =
            listOf(
                "reconciliation_request_guard_update",
                "reconciliation_request_guard_delete",
                "reconciliation_snapshot_guard_update",
                "reconciliation_snapshot_guard_delete",
                "reconciliation_receipt_guard_update",
                "reconciliation_receipt_guard_delete",
                "evidence_link_guard_update",
                "evidence_link_guard_delete",
                "evidence_link_history_guard_update",
                "evidence_link_history_guard_delete",
                "posting_reconciliation_guard_delete",
                "posting_reconciliation_history_guard_update",
                "posting_reconciliation_history_guard_delete",
                "evidence_link_history_sequence_guard",
                "evidence_link_history_transition_guard",
                "posting_reconciliation_history_sequence_guard",
                "posting_reconciliation_history_link_guard",
                "posting_reconciliation_update_guard",
            )
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 23)
                driver.execute(null, "INSERT INTO import_request VALUES ('ledger-a','request-p408','intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id,source_id,owner_request_id,input_ref,record_ordinal,record_kind,content_hash,contract_version,completeness,amount_minor,currency_code,currency_precision,occurred_at,direction_token,status_token) VALUES ('ledger-a','source-p408','request-p408','batch-p408',0,'ordinary_flow_source','sha256:p408',1,'valid_complete',3580,'CNY',2,'2026-01-15T00:00:00Z','out','settled')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('ledger-a','evidence-p408','source-p408','source_observation','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO reconciliation_request VALUES ('ledger-a','recon-request-p408','migration_seed','fp-p408','ACCEPTED',NULL)", 0)
                driver.execute(null, "INSERT INTO reconciliation_request_snapshot VALUES ('ledger-a','recon-request-p408','evidence-p408','candidate-p408','posting-expense-existing','tx-existing',3580,'CNY',2,'out','expense-account-breakfast','real_account_posting',1,'exact_amount',3,0,'2026-01-15T00:00:00Z','2026-01-15T00:00:00Z','confirm_match')", 0)
                driver.execute(null, "INSERT INTO evidence_link VALUES ('ledger-a','link-p408','evidence-p408','posting-expense-existing','tx-existing','real_account_posting',1,'exact_amount','candidate-p408','recon-request-p408','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO evidence_link_history VALUES ('ledger-a','link-p408',1,'active','confirmed','recon-request-p408','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO posting_reconciliation VALUES ('ledger-a','reconciliation-p408','posting-expense-existing','PENDING',1)", 0)
                driver.execute(null, "INSERT INTO posting_reconciliation_history VALUES ('ledger-a','reconciliation-p408',1,'PENDING','link-p408','recon-request-p408','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO reconciliation_receipt VALUES ('ledger-a','recon-request-p408','ACCEPTED','link-p408','reconciliation-p408',1)", 0)
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 23, 24) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM ledger_transaction WHERE transaction_id='tx-existing'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM transaction_version WHERE version_id='version-existing-v1'"))
                assertEquals(2L, queryCount(driver, "SELECT count(*) FROM posting WHERE posting_set_id='posting-set-existing'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_request WHERE request_id='request-p408'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-p408' AND funding_state='UNRESOLVED'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_evidence WHERE evidence_id='evidence-p408'"))
                listOf("reconciliation_request", "reconciliation_request_snapshot", "evidence_link", "evidence_link_history", "posting_reconciliation", "posting_reconciliation_history", "reconciliation_receipt").forEach { table ->
                    assertEquals(1L, queryCount(driver, "SELECT count(*) FROM $table"))
                }
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                p408Triggers.forEach { trigger -> assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='$trigger'")) }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun lateV23ToV24FailureRollsBackSourceRebuildAndP408Rows() {
        val path = Files.createTempFile("ledger-data-v23-v24-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) } }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 23)
                driver.execute(null, "INSERT INTO import_request VALUES ('ledger-a','request-rollback','intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id,source_id,owner_request_id,input_ref,record_ordinal,record_kind,content_hash,contract_version,completeness,amount_minor,currency_code,currency_precision,occurred_at,direction_token,status_token) VALUES ('ledger-a','source-rollback','request-rollback','batch-rollback',0,'ordinary_flow_source','sha256:rollback',1,'valid_complete',100,'CNY',2,'2026-01-15T00:00:00Z','out','settled')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('ledger-a','evidence-rollback','source-rollback','source_observation','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO reconciliation_request VALUES ('ledger-a','recon-rollback','migration_seed','fp-rollback','ACCEPTED',NULL)", 0)
                driver.execute(null, "INSERT INTO reconciliation_request_snapshot VALUES ('ledger-a','recon-rollback','evidence-rollback','candidate-rollback','posting-expense-existing','tx-existing',100,'CNY',2,'out','expense-account-breakfast','real_account_posting',1,'exact_amount',3,0,'2026-01-15T00:00:00Z','2026-01-15T00:00:00Z','confirm_match')", 0)
                driver.execute(null, "INSERT INTO evidence_link VALUES ('ledger-a','link-rollback','evidence-rollback','posting-expense-existing','tx-existing','real_account_posting',1,'exact_amount','candidate-rollback','recon-rollback','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO evidence_link_history VALUES ('ledger-a','link-rollback',1,'active','confirmed','recon-rollback','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO posting_reconciliation VALUES ('ledger-a','reconciliation-rollback','posting-expense-existing','PENDING',1)", 0)
                driver.execute(null, "INSERT INTO posting_reconciliation_history VALUES ('ledger-a','reconciliation-rollback',1,'PENDING','link-rollback','recon-rollback','2026-01-15T00:00:00Z')", 0)
                driver.execute(null, "INSERT INTO reconciliation_receipt VALUES ('ledger-a','recon-rollback','ACCEPTED','link-rollback','reconciliation-rollback',1)", 0)
                driver.execute(null, "CREATE TABLE import_duplicate_candidate(blocker TEXT)", 0)
                assertFailsWith<SQLException> { LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 23, 24) } }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM pragma_table_info('import_source_record') WHERE name='funding_state'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_source_record WHERE source_id='source-rollback' AND amount_minor=100"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM import_evidence WHERE evidence_id='evidence-rollback'"))
                listOf("reconciliation_request", "reconciliation_request_snapshot", "evidence_link", "evidence_link_history", "posting_reconciliation", "posting_reconciliation_history", "reconciliation_receipt").forEach { table ->
                    assertEquals(1L, queryCount(driver, "SELECT count(*) FROM $table"))
                }
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='import_duplicate_candidate'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'import_duplicate_%'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedV25ToV26ProjectionBackfillEqualsFreshEquivalentRows() {
        val migratedPath = Files.createTempFile("ledger-data-migrated-v26-proj-", ".db")
        val freshPath = Files.createTempFile("ledger-data-fresh-v26-proj-", ".db")
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            seedV25ProjectionWorld(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 25, 28) }
            }
            // Fresh equivalent construction at v26 mirrors the deterministic predicate.
            JdbcSqliteDriver("jdbc:sqlite:${freshPath.absolutePathString()}", migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "INSERT INTO import_request VALUES ('ledger-b26','request-b26','intake')", 0)
                driver.execute(null, "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-b26','source-b26','request-b26','batch-b26',0,'transfer_flow_source','hash-b26',2,'valid_complete',9900,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled')", 0)
                driver.execute(null, "INSERT INTO import_evidence VALUES ('ledger-b26','evidence-b26','source-b26','source_observation','2026-08-10T12:00:01+08:00')", 0)
                driver.execute(null, "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-b26','ledger-b26','ACCOUNT_TRANSFER',NULL)", 0)
                driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-b26','ledger-b26')", 0)
                driver.execute(null, "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-b26','tx-b26','ledger-b26',1,'posting-set-b26','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)", 0)
                driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('tx-b26','ledger-b26','version-b26')", 0)
                driver.execute(null, "INSERT INTO posting VALUES ('posting-b26-out','posting-set-b26','ledger-b26',0,'account-bank-a',-9900,'CNY',2)", 0)
                driver.execute(null, "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) VALUES ('ledger-b26','request-link-b26','confirm_link','fp-b26','ACCEPTED')", 0)
                driver.execute(null, "INSERT INTO reconciliation_request_snapshot(ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision, projection_id, projection_rule_id, projection_rule_version, normalized_amount_minor, raw_amount_minor, raw_currency_precision) VALUES ('ledger-b26','request-link-b26','evidence-b26','candidate-b26','posting-b26-out','tx-b26',9900,'CNY',2,'out','account-bank-a','real_account_posting',1,'amount,currency,direction,occurred_at_window,account',2,0,'2026-08-10T12:00:00+08:00','2026-08-10T13:00:00+08:00','confirm_match',NULL,NULL,NULL,NULL,9900,2)", 0)
                driver.execute(null, "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES ('ledger-b26','link-b26','evidence-b26','posting-b26-out','tx-b26','real_account_posting',1,'amount,currency,direction,occurred_at_window,account','candidate-b26','request-link-b26','2026-08-10T13:00:01+08:00')", 0)
                driver.execute(null, "INSERT INTO evidence_link_history(ledger_id, link_id, sequence, state, reason, request_id, occurred_at) VALUES ('ledger-b26','link-b26',1,'active','confirmed','request-link-b26','2026-08-10T13:00:01+08:00')", 0)
                driver.execute(null, "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) VALUES ('ledger-b26','reconciliation-b26-out','posting-b26-out','CHECKED',2)", 0)
                driver.execute(null, "INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('ledger-b26','reconciliation-b26-out',1,'PENDING',NULL,'request-link-b26','2026-08-10T12:00:02+08:00')", 0)
                driver.execute(null, "INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('ledger-b26','reconciliation-b26-out',2,'CHECKED','link-b26','request-link-b26','2026-08-10T13:00:02+08:00')", 0)
                driver.execute(null, "INSERT INTO reconciliation_receipt(ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence) VALUES ('ledger-b26','request-link-b26','ACCEPTED','link-b26','reconciliation-b26-out',2)", 0)
                driver.execute(null, "INSERT INTO evidence_projection(ledger_id, projection_id, evidence_id, source_id, source_hash, target_account_id, currency_code, currency_precision, raw_amount_minor, raw_currency_precision, normalized_amount_minor, direction_token, state, rejection_code, rule_id, rule_version, materialization_request_id, materialized_at) VALUES ('ledger-b26','proj-evidence-b26','evidence-b26','source-b26','hash-b26','account-bank-a','CNY',2,9900,2,9900,'out','READY',NULL,'p408_evidence_projection_backfill_v26',1,'p408-evidence-projection-backfill-v26','1970-01-01T00:00:00Z')", 0)
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                val db = LedgerDatabase(driver)
                assertEquals(37, LedgerDatabase.Schema.version)
                assertEquals(1L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
                val rows = queryCount(driver, "SELECT count(*) FROM evidence_projection WHERE state='READY' AND normalized_amount_minor=9900 AND rule_id='p408_evidence_projection_backfill_v26'")
                assertEquals(1L, rows)
            }
            // TP-16 full two-sided equivalence: the seven v23-era shared tables plus
            // the rebuilt snapshot/evidence_link (all columns) and the projection
            // table compare row-for-row between migrated and fresh-equivalent worlds.
            val tableSelects =
                listOf(
                    "reconciliation_request",
                    "reconciliation_request_snapshot",
                    "evidence_link",
                    "evidence_link_history",
                    "posting_reconciliation",
                    "posting_reconciliation_history",
                    "reconciliation_receipt",
                    "evidence_projection",
                )
            val migratedRows = tableSelects.associateWith { selectRowsFlat(migratedUrl, it) }
            val freshRows = tableSelects.associateWith { selectRowsFlat("jdbc:sqlite:${freshPath.absolutePathString()}", it) }
            tableSelects.forEach { table ->
                assertEquals(freshRows.getValue(table), migratedRows.getValue(table), "row diff on $table")
            }
            // Widened CHECK texts are identical on both sides.
            val checkSelect = "SELECT sql FROM sqlite_master WHERE type='table' AND name IN ('reconciliation_request_snapshot','evidence_link') ORDER BY name"
            val migratedCheck = selectRaw(migratedUrl, checkSelect)
            val freshCheck = selectRaw("jdbc:sqlite:${freshPath.absolutePathString()}", checkSelect)
            assertEquals(freshCheck, migratedCheck)
            freshCheck.forEach { sql -> assertEquals(true, sql.contains("basis_version IN (1, 2)"), sql) }
        } finally {
            Files.deleteIfExists(migratedPath)
            Files.deleteIfExists(freshPath)
        }
    }

    @Test
    fun lateV25ToV26FailureRollsBackProjectionObjectsAndKeepsLegacyRows() {
        val path = Files.createTempFile("ledger-data-v25-v26-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            seedV25ProjectionWorld(url)
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                // SPEC-006: the sentinel name is created after the backfill (step 5),
                // so the READY backfill has already executed when the migration
                // aborts — full late-stage rollback is asserted below.
                driver.execute(null, "CREATE TABLE p408_v26_late_sentinel(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 25, 26) }
                }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='evidence_projection_guard_update'"))
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('evidence_projection','p408_v26_row_guard','p408_v26_pre_guard')"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM evidence_link WHERE link_id='link-b26'"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM reconciliation_request_snapshot WHERE request_id='request-link-b26' AND basis_version=1"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='reconciliation_request_snapshot' AND sql LIKE '%basis_version = 1%'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /** Seeds a terminal-v35 world holding one committed correction request + receipt pair. */

    @Test
    fun populatedV35ToV36CorrectionRebuildPreservesCommittedRowsValueForValue() {
        val migratedPath = Files.createTempFile("ledger-data-migrated-v36-correction-", ".db")
        val freshPath = Files.createTempFile("ledger-data-fresh-v36-correction-", ".db")
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        try {
            seedV35CorrectionWorld(migratedUrl)
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 35, 36) }
                driver.execute(null, "PRAGMA user_version = 36", 0)
            }
            // Fresh equivalent construction at v36: the same committed pair, with the two
            // transfer-carrying columns (D-228 spec section 8.3) at their ordinary-row
            // values — destination_account_id NULL and fee_minor 0.
            JdbcSqliteDriver(freshUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                driver.execute(null, "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-c36','ledger-c36','EXPENSE',NULL)", 0)
                driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-c36-a','ledger-c36')", 0)
                driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-c36-b','ledger-c36')", 0)
                driver.execute(null, "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-c36-base','tx-c36','ledger-c36',1,'posting-set-c36-a','2026-10-20T09:00:00+08:00','2026-10-20T09:00:00+08:00','2026-10-20T09:00:00+08:00',NULL)", 0)
                driver.execute(null, "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-c36-corrected','tx-c36','ledger-c36',2,'posting-set-c36-b','2026-10-20T09:00:00+08:00','2026-10-21T18:00:00+08:00','2026-10-20T09:00:00+08:00','corrected note')", 0)
                driver.execute(null, "INSERT INTO transaction_correction_request(ledger_id, request_id, transaction_id, expected_current_version_id, note, statistics_at, amount_minor, currency_code, currency_precision, category_id, funding_account_id, confirmation_marker, destination_account_id, fee_minor) VALUES ('ledger-c36','request-c36','tx-c36','version-c36-base',NULL,'2026-10-21T18:00:00+08:00',4567,'CNY',2,'category-c36','account-cash-c36','explicit_manual_save',NULL,0)", 0)
                driver.execute(null, "INSERT INTO transaction_correction_receipt(ledger_id, request_id, confirmation_id, transaction_id, version_id, expected_current_version_id) VALUES ('ledger-c36','request-c36','confirmation-c36','tx-c36','version-c36-corrected','version-c36-base')", 0)
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                assertEquals(37, LedgerDatabase.Schema.version)
                assertEquals(36L, queryCount(driver, "PRAGMA user_version"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM transaction_correction_request WHERE destination_account_id IS NULL AND fee_minor = 0"))
            }
            // The staged rebuild makes fresh = migrated hold byte for byte on both rebuilt
            // tables, and the row copy is value-preserving column by column.
            val rebuiltDdl = "SELECT sql FROM sqlite_master WHERE type='table' AND name IN ('transaction_correction_request','transaction_correction_receipt') ORDER BY name"
            assertEquals(selectRaw(freshUrl, rebuiltDdl), selectRaw(migratedUrl, rebuiltDdl))
            val tableSelects = listOf("transaction_correction_request", "transaction_correction_receipt")
            val migratedRows = tableSelects.associateWith { selectRowsFlat(migratedUrl, it) }
            val freshRows = tableSelects.associateWith { selectRowsFlat(freshUrl, it) }
            tableSelects.forEach { table ->
                assertEquals(freshRows.getValue(table), migratedRows.getValue(table), "row diff on $table")
            }
        } finally {
            Files.deleteIfExists(migratedPath)
            Files.deleteIfExists(freshPath)
        }
    }

    @Test
    fun lateV35ToV36FailureRollsBackCorrectionRebuildAndKeepsLegacyRows() {
        val path = Files.createTempFile("ledger-data-v35-v36-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            seedV35CorrectionWorld(url)
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                // The count guard is created after the staged rebuild and the row copy, so
                // both rebuilt tables already hold the copied rows when the migration
                // aborts — full late-stage rollback is asserted below.
                driver.execute(null, "CREATE TABLE p705_rebuild_guard(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 35, 36) }
                }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                assertEquals(35L, queryCount(driver, "PRAGMA user_version"))
                assertEquals(
                    true,
                    tableSql(driver, "transaction_correction_request").contains("category_id TEXT NOT NULL"),
                )
                assertEquals(
                    listOf("ledger_id", "request_id", "transaction_id", "expected_current_version_id", "note", "statistics_at", "amount_minor", "currency_code", "currency_precision", "category_id", "funding_account_id", "confirmation_marker"),
                    tableColumnNames(driver, "transaction_correction_request"),
                )
                assertEquals(0L, queryCount(driver, "SELECT count(*) FROM sqlite_master WHERE name IN ('transaction_correction_request_stage','transaction_correction_receipt_stage')"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM transaction_correction_request WHERE request_id='request-c36' AND category_id='category-c36' AND amount_minor=4567 AND note IS NULL"))
                assertEquals(1L, queryCount(driver, "SELECT count(*) FROM transaction_correction_receipt WHERE request_id='request-c36' AND version_id='version-c36-corrected'"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
