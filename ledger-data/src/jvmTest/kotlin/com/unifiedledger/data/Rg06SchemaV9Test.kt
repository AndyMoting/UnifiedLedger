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

class Rg06SchemaV9Test {
    @Test
    fun versionEightToNinePreservesV8OwnersAndAddsEmptyRg06Owners() {
        val path = Files.createTempFile("ledger-data-v8-v9-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 8)
                driver.execute(null, "INSERT INTO rg05_operation_request VALUES ('ledger-a','existing','MANUAL_MERGED_PAYMENT')", 0)
            }
                JdbcSqliteDriver(url, properties).use { driver -> LedgerDatabase.Schema.migrate(driver, 8, 12) }
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(27, LedgerDatabase.Schema.version)
                assertEquals(1L, database.ledgerQueries.countRg05OperationRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg06Relations().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg06Installments().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg06Sources().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun authenticVersionNineGainsRg07OwnersAtVersionTen() {
        val path = Files.createTempFile("ledger-data-v9-v10-rg07-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 9)
                driver.execute(null, "SELECT 1", 0)
            }
            JdbcSqliteDriver(url, properties).use { driver ->
                assertEquals(0L, driver.executeQuery(null, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'rg07%'", { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
                }, 0).value)
                LedgerDatabase.Schema.migrate(driver, 9, 10)
            }
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'rg07_operation'").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rg06NormalizedOwnersRejectDirectMutationAndCrossOwnerReferences() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            seedRg06Graph(driver)

            assertSqlRejected(driver, "UPDATE rg06_relation SET payload_marker = 'changed' WHERE relation_id = 'relation-a'")
            assertSqlRejected(driver, "DELETE FROM rg06_lifecycle_history WHERE history_id = 'history-a'")
            assertSqlRejected(driver, "UPDATE rg06_installment SET amount_minor = 7999 WHERE payment_id = 'payment-a'")
            assertSqlRejected(driver, "DELETE FROM rg06_source WHERE source_id = 'source-a'")
            assertSqlRejected(driver, "UPDATE rg06_candidate SET role_fact = 'FINAL' WHERE candidate_id = 'candidate-a'")
            assertSqlRejected(driver, "DELETE FROM rg06_candidate_status_history WHERE status_id = 'status-a'")
            assertSqlRejected(driver, "UPDATE rg06_confirmation SET payment_role = 'FINAL' WHERE confirmation_id = 'confirmation-a'")
            assertSqlRejected(driver, "DELETE FROM rg06_evidence_link WHERE link_id = 'link-a'")
            assertSqlRejected(driver, "UPDATE rg06_evidence SET payment_id = 'payment-b' WHERE evidence_id = 'evidence-a'")
            assertSqlRejected(driver, "UPDATE rg06_posting_reconciliation SET status = 'PENDING' WHERE reconciliation_id = 'reconciliation-a'")

            assertSqlRejected(driver, "INSERT INTO rg06_source VALUES ('ledger-b','mirror-cross','MIRROR',8000,'CNY',2,'2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','SOURCE_PAYMENT_AT','source-a')")
            assertSqlRejected(driver, "INSERT INTO rg06_confirmation(ledger_id,confirmation_id,identity_value,confirmation_kind,candidate_id,relation_id,payment_id,payment_role,category_id,funding_account_id) VALUES ('ledger-a','confirmation-cross','record-a','CANDIDATE_CONFIRMATION','candidate-b','relation-a','payment-a','DEPOSIT','expense-service','asset-bank')")
            assertSqlRejected(driver, "INSERT INTO rg06_evidence_link VALUES ('ledger-a','link-cross','evidence-a','payment-a','posting-b-asset','IMPORTED')")
            assertSqlRejected(driver, "INSERT INTO rg06_reconciliation_history VALUES ('ledger-a','reconciliation-a',3,'MATCHED','link-b')")
        } finally {
            driver.close()
        }
    }

    @Test
    fun reconciliationRequiresExactEligiblePaymentAssetSemanticAndMatchingHistoryStatus() {
        freshSchema { driver ->
            seedRg06Graph(driver, includeReconciliation = false)
            assertSqlRejected(
                driver,
                "INSERT INTO rg06_posting_reconciliation VALUES ('ledger-a','reconciliation-expense','posting-a-expense','PENDING',1)",
            )
            driver.execute(null, "INSERT INTO rg06_posting_reconciliation VALUES ('ledger-a','reconciliation-a','posting-a-asset','PENDING',1)", 0)
            assertSqlRejected(
                driver,
                "INSERT INTO rg06_reconciliation_history VALUES ('ledger-a','reconciliation-a',1,'MATCHED',NULL)",
            )
        }
    }

    @Test
    fun acceptedReceiptRejectsMissingLatestLifecycleHistory() {
        freshSchema { driver ->
            driver.execute(null, "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id) VALUES ('ledger-a','create-incomplete','create_staged_payment','create-incomplete')", 0)
            driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-a','relation-incomplete','staged_payment','{}')", 0)
            driver.execute(null, "INSERT INTO rg06_lifecycle VALUES ('ledger-a','lifecycle-incomplete','relation-incomplete',30000,0,30000,'CNY',2,'expense-service',1)", 0)
            driver.execute(null, "INSERT INTO rg06_relation_member VALUES ('ledger-a','relation-incomplete',0,'LIFECYCLE','lifecycle-incomplete')", 0)
            driver.execute(null, "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','create-incomplete',0,'RELATION','relation-incomplete')", 0)
            assertSqlRejected(
                driver,
                "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','create-incomplete',1,'LIFECYCLE','lifecycle-incomplete')",
            )
        }
        freshSchema { driver ->
            driver.execute(null, "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id) VALUES ('ledger-a','create-mismatch','create_staged_payment','create-mismatch')", 0)
            driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-a','relation-mismatch','staged_payment','{}')", 0)
            driver.execute(null, "INSERT INTO rg06_lifecycle VALUES ('ledger-a','lifecycle-mismatch','relation-mismatch',30000,0,30000,'CNY',2,'expense-service',1)", 0)
            driver.execute(null, "INSERT INTO rg06_relation_member VALUES ('ledger-a','relation-mismatch',0,'LIFECYCLE','lifecycle-mismatch')", 0)
            driver.execute(null, "INSERT INTO rg06_lifecycle_history VALUES ('ledger-a','lifecycle-mismatch',1,'history-mismatch','create-mismatch','GROUP_CREATED','2026-04-20T01:00:00Z',29999,0,29999,NULL,'UNPAID','IN_PROGRESS',0)", 0)
            driver.execute(null, "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','create-mismatch',0,'RELATION','relation-mismatch')", 0)
            assertSqlRejected(
                driver,
                "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','create-mismatch',1,'LIFECYCLE','lifecycle-mismatch')",
            )
        }
    }

    @Test
    fun acceptedCandidateReceiptRejectsStatusEvidenceAndLinkIncoherence() {
        freshSchema { driver ->
            seedRg06Graph(driver, includeConfirmedCandidateStatus = false)
            insertCandidateReceiptsUntilFinal(driver)
            assertSqlRejected(
                driver,
                "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','confirm-a',3,'EVIDENCE_LINK','link-a')",
            )
        }
        freshSchema { driver ->
            seedRg06Graph(driver, evidenceLinkKind = "MANUAL")
            insertCandidateReceiptsUntilFinal(driver)
            assertSqlRejected(
                driver,
                "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','confirm-a',3,'EVIDENCE_LINK','link-a')",
            )
        }
    }

    @Test
    fun acceptedInstallmentRejectsCurrentVersionDriftAndAllowsUnrelatedCorrections() {
        freshSchema { driver ->
            seedAcceptedRg06Graph(driver)

            driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-a-v2','transaction-a','ledger-a',2,'posting-set-a','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','compatible')", 0)
            assertAcceptedAggregateRejected(
                driver,
                "UPDATE ledger_transaction_current_version SET current_version_id = 'version-a-v2' WHERE transaction_id = 'transaction-a'",
            )

            driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('transaction-unrelated','ledger-a','EXPENSE')", 0)
            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-unrelated','ledger-a')", 0)
            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-unrelated-v2','ledger-a')", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-unrelated','transaction-unrelated','ledger-a',1,'posting-set-unrelated','2026-06-01T00:00:00Z','2026-06-01T00:00:00Z','2026-06-01T00:00:00Z',NULL)", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-unrelated-v2','transaction-unrelated','ledger-a',2,'posting-set-unrelated-v2','2026-06-02T00:00:00Z','2026-06-02T00:00:00Z','2026-06-02T00:00:00Z',NULL)", 0)
            driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('transaction-unrelated','ledger-a','version-unrelated')", 0)
            driver.execute(null, "UPDATE ledger_transaction_current_version SET current_version_id = 'version-unrelated-v2' WHERE transaction_id = 'transaction-unrelated'", 0)

            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM rg06_accepted_aggregate_violation"))
        }
    }

    @Test
    fun operationSchemaForcesUnusedActionFieldsNull() {
        freshSchema { driver ->
            val cases = listOf(
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id,category_id,amount_minor,currency_code,currency_precision,occurred_at,source_id) VALUES ('ledger-a','bad-create','create_staged_payment','bad-create','expense-service',30000,'CNY',2,'2026-04-20T01:00:00Z','unused')",
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id,relation_id,payment_role,funding_account_id,amount_minor,currency_code,currency_precision,occurred_at,candidate_id) VALUES ('ledger-a','bad-record','record_staged_payment_installment','bad-record','relation-a','DEPOSIT','asset-bank',8000,'CNY',2,'2026-04-28T02:00:00Z','unused')",
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id,relation_id,fulfillment_status,occurred_at,amount_minor) VALUES ('ledger-a','bad-fulfillment','change_staged_payment_fulfillment','bad-fulfillment','relation-a','FULFILLED','2026-04-29T02:00:00Z',1)",
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id,relation_id,confirmed,occurred_at,payment_id) VALUES ('ledger-a','bad-completion','confirm_staged_payment_completion','bad-completion','relation-a',1,'2026-05-04T02:00:00Z','unused')",
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,source_id,evidence_id,payment_id,posting_id,request_id) VALUES ('ledger-a','bad-link','link_staged_payment_evidence','source-a','evidence-a','payment-a','posting-a-asset','unused')",
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,source_id,evidence_id,amount_minor,currency_code,currency_precision,occurred_at,occurred_at_text,relation_id) VALUES ('ledger-a','bad-ingest','ingest_staged_payment_bank_fact','source-a','evidence-a',-8000,'CNY',2,'2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','unused')",
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id,candidate_id,relation_id,category_id,funding_account_id,payment_role,exact_binding_confirmed,source_id) VALUES ('ledger-a','bad-candidate','confirm_staged_payment_candidate','bad-candidate','candidate-a','relation-a','expense-service','asset-bank','DEPOSIT',1,'unused')",
                "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,source_id,evidence_id,payment_id,posting_id,amount_minor,currency_code,currency_precision,occurred_at,occurred_at_text,candidate_id) VALUES ('ledger-a','bad-mirror','merge_staged_payment_mirror_evidence','source-a','evidence-a','payment-a','posting-a-asset',8000,'CNY',2,'2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','unused')",
            )

            cases.forEach { sql -> assertSqlRejected(driver, sql) }
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM rg06_operation"))
        }
    }

    @Test
    fun acceptedInstallmentRejectsIncompatibleRepointMutationDeletionAndAliasing() {
        fun accepted(block: (JdbcSqliteDriver) -> Unit) = freshSchema { driver ->
            seedAcceptedRg06Graph(driver)
            block(driver)
        }

        accepted { driver ->
            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-incompatible','ledger-a')", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('posting-incompatible-expense','posting-set-incompatible','ledger-a',0,'expense-service-account',8000,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('posting-incompatible-asset','posting-set-incompatible','ledger-a',1,'asset-bank',-8000,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-incompatible','transaction-a','ledger-a',2,'posting-set-incompatible','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z',NULL)", 0)
            assertAcceptedAggregateRejected(driver, "UPDATE ledger_transaction_current_version SET current_version_id = 'version-incompatible' WHERE transaction_id = 'transaction-a'")
        }
        accepted { driver ->
            assertAcceptedAggregateRejected(driver, "UPDATE posting SET amount_minor = 7999 WHERE posting_id = 'posting-a-expense'")
        }
        accepted { driver ->
            assertAcceptedAggregateRejected(driver, "DELETE FROM posting WHERE posting_id = 'posting-a-asset'")
        }
        accepted { driver ->
            assertAcceptedAggregateRejected(driver, "DELETE FROM ledger_transaction_current_version WHERE transaction_id = 'transaction-a'")
        }
        accepted { driver ->
            driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('transaction-alias','ledger-a','EXPENSE')", 0)
            assertAcceptedAggregateRejected(driver, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-alias','transaction-alias','ledger-a',1,'posting-set-a','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z',NULL)")
        }
    }

    @Test
    fun acceptedCandidateReceiptRequiresExactRoleConfidenceAndRequirementProvenance() {
        fun rejected(mutator: (JdbcSqliteDriver) -> Unit) = freshSchema { driver ->
            seedRg06Graph(driver)
            mutator(driver)
            insertCandidateReceiptsUntilFinal(driver)
            assertIncompleteAcceptedOperationRejected(
                driver,
                "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','confirm-a',3,'EVIDENCE_LINK','link-a')",
            )
        }

        rejected { driver ->
            driver.execute(null, "DROP TRIGGER rg06_candidate_guard_update", 0)
            driver.execute(null, "UPDATE rg06_candidate SET role_fact = 'FINAL' WHERE candidate_id = 'candidate-a'", 0)
        }
        rejected { driver ->
            driver.execute(null, "DROP TRIGGER rg06_candidate_guard_update", 0)
            driver.execute(null, "UPDATE rg06_candidate SET confidence = '0.50' WHERE candidate_id = 'candidate-a'", 0)
        }
        rejected { driver ->
            driver.execute(null, "DROP TRIGGER rg06_source_guard_update", 0)
            driver.execute(null, "UPDATE rg06_source SET amount_minor = -7999 WHERE source_id = 'source-a'", 0)
        }
        rejected { driver ->
            driver.execute(null, "DROP TRIGGER rg06_evidence_guard_update", 0)
            driver.execute(null, "UPDATE rg06_evidence SET observed_at = '2026-04-28T02:00:01Z' WHERE evidence_id = 'evidence-a'", 0)
        }
        repeat(4) { index ->
            rejected { driver ->
                driver.execute(null, "DROP TRIGGER rg06_requirement_guard_update", 0)
                driver.execute(null, "PRAGMA ignore_check_constraints = ON", 0)
                driver.execute(null, "UPDATE rg06_candidate_requirement SET requirement = 'WRONG' WHERE candidate_id = 'candidate-a' AND requirement_index = $index", 0)
                driver.execute(null, "PRAGMA ignore_check_constraints = OFF", 0)
            }
        }
        rejected { driver ->
            driver.execute(null, "DROP TRIGGER rg06_requirement_guard_delete", 0)
            driver.execute(null, "DELETE FROM rg06_candidate_requirement WHERE candidate_id = 'candidate-a' AND requirement_index = 3", 0)
        }
        rejected { driver ->
            driver.execute(null, "PRAGMA ignore_check_constraints = ON", 0)
            driver.execute(null, "INSERT INTO rg06_candidate_requirement VALUES ('ledger-a','candidate-a',4,'WRONG')", 0)
            driver.execute(null, "PRAGMA ignore_check_constraints = OFF", 0)
        }
    }

    private fun freshSchema(block: (JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            block(driver)
        } finally {
            driver.close()
        }
    }

    private fun insertCandidateReceiptsUntilFinal(driver: JdbcSqliteDriver) {
        driver.execute(null, "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','confirm-a',0,'CONFIRMATION','confirmation-a')", 0)
        driver.execute(null, "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','confirm-a',1,'TRANSACTION','transaction-a')", 0)
        driver.execute(null, "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','confirm-a',2,'PAYMENT','payment-a')", 0)
    }

    private fun seedAcceptedRg06Graph(driver: JdbcSqliteDriver) {
        seedRg06Graph(driver)
        insertCandidateReceiptsUntilFinal(driver)
        driver.execute(null, "INSERT INTO rg06_operation_receipt VALUES ('ledger-a','confirm-a',3,'EVIDENCE_LINK','link-a')", 0)
        assertEquals(0L, queryLong(driver, "SELECT count(*) FROM rg06_accepted_aggregate_violation"))
    }

    private fun seedRg06Graph(
        driver: JdbcSqliteDriver,
        includeReconciliation: Boolean = true,
        includeConfirmedCandidateStatus: Boolean = true,
        evidenceLinkKind: String = "IMPORTED",
    ) {
        driver.execute(null, "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id) VALUES ('ledger-a','create-a','create_staged_payment','create-a')", 0)
        driver.execute(null, "INSERT INTO rg06_operation(ledger_id,identity_value,action_type,request_id,candidate_id,relation_id,payment_role,category_id,funding_account_id,exact_binding_confirmed) VALUES ('ledger-a','confirm-a','confirm_staged_payment_candidate','confirm-a','candidate-a','relation-a','DEPOSIT','expense-service','asset-bank',1)", 0)
        driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-a','relation-a','staged_payment','{}')", 0)
        driver.execute(null, "INSERT INTO rg06_lifecycle VALUES ('ledger-a','lifecycle-a','relation-a',30000,0,30000,'CNY',2,'expense-service',1)", 0)
        driver.execute(null, "INSERT INTO rg06_lifecycle_history VALUES ('ledger-a','lifecycle-a',1,'history-a','create-a','GROUP_CREATED','2026-04-20T01:00:00Z',30000,0,30000,NULL,'UNPAID','IN_PROGRESS',0)", 0)
        driver.execute(null, "INSERT INTO rg06_relation_member VALUES ('ledger-a','relation-a',0,'LIFECYCLE','lifecycle-a')", 0)
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('transaction-a','ledger-a','EXPENSE')", 0)
        driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-a','ledger-a')", 0)
        driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-a','transaction-a','ledger-a',1,'posting-set-a','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z',NULL)", 0)
        driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('transaction-a','ledger-a','version-a')", 0)
        driver.execute(null, "INSERT INTO posting VALUES ('posting-a-expense','posting-set-a','ledger-a',0,'expense-service-account',8000,'CNY',2)", 0)
        driver.execute(null, "INSERT INTO posting VALUES ('posting-a-asset','posting-set-a','ledger-a',1,'asset-bank',-8000,'CNY',2)", 0)
        driver.execute(null, "INSERT INTO rg06_installment VALUES ('ledger-a','relation-a',0,'payment-a','DEPOSIT',8000,'CNY',2,'asset-bank','transaction-a','version-a','posting-set-a','posting-a-expense','posting-a-asset','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00')", 0)
        driver.execute(null, "INSERT INTO rg06_posting_semantic VALUES ('ledger-a','posting-a-expense','payment-a','expense','expense-service',0)", 0)
        driver.execute(null, "INSERT INTO rg06_posting_semantic VALUES ('ledger-a','posting-a-asset','payment-a','payment_asset',NULL,1)", 0)
        driver.execute(null, "INSERT INTO rg06_relation_member VALUES ('ledger-a','relation-a',1,'INSTALLMENT','payment-a')", 0)
        driver.execute(null, "INSERT INTO rg06_lifecycle_history VALUES ('ledger-a','lifecycle-a',2,'history-payment-a','confirm-a','PAYMENT_CONFIRMED','2026-04-28T02:00:00Z',30000,8000,22000,'payment-a','PARTIALLY_PAID','IN_PROGRESS',0)", 0)
        driver.execute(null, "UPDATE rg06_lifecycle SET paid_minor = 8000, due_minor = 22000, latest_sequence = 2 WHERE ledger_id = 'ledger-a' AND lifecycle_id = 'lifecycle-a'", 0)
        driver.execute(null, "INSERT INTO rg06_source VALUES ('ledger-a','source-a','IMPORTED',-8000,'CNY',2,'2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','SOURCE_PAYMENT_AT',NULL)", 0)
        driver.execute(null, "INSERT INTO rg06_evidence VALUES ('ledger-a','evidence-a','source-a','BOUND','2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','SOURCE_PAYMENT_AT','payment-a',NULL,NULL)", 0)
        driver.execute(null, "INSERT INTO rg06_candidate VALUES ('ledger-a','candidate-a','source-a','DEPOSIT',8000,'CNY',2,'2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','evidence-a','1.00',1)", 0)
        driver.execute(null, "INSERT INTO rg06_candidate_requirement VALUES ('ledger-a','candidate-a',0,'RELATION_ID')", 0)
        driver.execute(null, "INSERT INTO rg06_candidate_requirement VALUES ('ledger-a','candidate-a',1,'PAYMENT_ROLE')", 0)
        driver.execute(null, "INSERT INTO rg06_candidate_requirement VALUES ('ledger-a','candidate-a',2,'CATEGORY_ID')", 0)
        driver.execute(null, "INSERT INTO rg06_candidate_requirement VALUES ('ledger-a','candidate-a',3,'FUNDING_ACCOUNT_ID')", 0)
        driver.execute(null, "INSERT INTO rg06_candidate_status_history VALUES ('ledger-a','candidate-a',1,'status-a','PENDING_CONFIRMATION')", 0)
        if (includeConfirmedCandidateStatus) {
            driver.execute(null, "INSERT INTO rg06_candidate_status_history VALUES ('ledger-a','candidate-a',2,'status-confirmed-a','CONFIRMED')", 0)
        }
        driver.execute(null, "INSERT INTO rg06_confirmation(ledger_id,confirmation_id,identity_value,confirmation_kind,candidate_id,relation_id,payment_id,payment_role,category_id,funding_account_id) VALUES ('ledger-a','confirmation-a','confirm-a','CANDIDATE_CONFIRMATION','candidate-a','relation-a','payment-a','DEPOSIT','expense-service','asset-bank')", 0)
        driver.execute(null, "INSERT INTO rg06_evidence_link VALUES ('ledger-a','link-a','evidence-a','payment-a','posting-a-asset','$evidenceLinkKind')", 0)
        if (includeReconciliation) {
            driver.execute(null, "INSERT INTO rg06_posting_reconciliation VALUES ('ledger-a','reconciliation-a','posting-a-asset','MATCHED',1)", 0)
            driver.execute(null, "INSERT INTO rg06_reconciliation_history VALUES ('ledger-a','reconciliation-a',1,'MATCHED','link-a')", 0)
        }

        driver.execute(null, "INSERT INTO rg06_source VALUES ('ledger-b','source-b','IMPORTED',-8000,'CNY',2,'2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','SOURCE_PAYMENT_AT',NULL)", 0)
        driver.execute(null, "INSERT INTO rg06_evidence VALUES ('ledger-b','evidence-b','source-b','PENDING','2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','SOURCE_PAYMENT_AT',NULL,NULL,NULL)", 0)
        driver.execute(null, "INSERT INTO rg06_candidate VALUES ('ledger-b','candidate-b','source-b','DEPOSIT',8000,'CNY',2,'2026-04-28T02:00:00Z','2026-04-28T10:00:00+08:00','evidence-b','1.00',1)", 0)
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('transaction-b','ledger-b','EXPENSE')", 0)
        driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-b','ledger-b')", 0)
        driver.execute(null, "INSERT INTO posting VALUES ('posting-b-asset','posting-set-b','ledger-b',0,'asset-bank',-8000,'CNY',2)", 0)
    }

    private fun assertSqlRejected(driver: JdbcSqliteDriver, sql: String) {
        assertFailsWith<SQLException>(sql) { driver.execute(null, sql, 0) }
    }

    private fun assertIncompleteAcceptedOperationRejected(driver: JdbcSqliteDriver, sql: String) {
        val failure = assertFailsWith<SQLException>(sql) { driver.execute(null, sql, 0) }
        assertTrue(failure.message.orEmpty().contains("rg06 incomplete accepted operation"), failure.message)
    }

    private fun assertAcceptedAggregateRejected(driver: JdbcSqliteDriver, sql: String) {
        val failure = assertFailsWith<SQLException>(sql) { driver.execute(null, sql, 0) }
        assertTrue(failure.message.orEmpty().contains("invalid accepted RG06 aggregate"), failure.message)
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            check(cursor.next().value)
            app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value
}
