package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Rg04SchemaV6Test {
    @Test
    fun freshSchemaIsVersionSixAndOwnsRg04StateSeparately() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            assertEquals(10, LedgerDatabase.Schema.version)
            assertEquals(0L, database.ledgerQueries.countRg04OperationRequests().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04Relations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg04PostingReconciliations().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun mixedReceiptRequiresBothRelationPostingMembersAndCompositionComponents() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            assertMixedReceiptInvariants(driver)
        }
    }

    @Test
    fun migratedSchemaEnforcesMixedReceiptInvariants() {
        val path = Files.createTempFile("rg04-migrated-trigger-", ".db")
        try {
            Files.copy(repoFile("ledger-data/src/commonMain/sqldelight/databases/1.db"), path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}", sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 6)
                assertMixedReceiptInvariants(driver)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaProtectsAcceptedRg04Aggregate() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            assertAcceptedRg04AggregateIsProtected(driver)
        }
    }

    @Test
    fun migratedSchemaProtectsAcceptedRg04Aggregate() {
        val path = Files.createTempFile("rg04-migrated-aggregate-", ".db")
        try {
            Files.copy(repoFile("ledger-data/src/commonMain/sqldelight/databases/1.db"), path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}", sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 6)
                assertAcceptedRg04AggregateIsProtected(driver)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaRejectsAcceptedRelationMemberFromAnotherPostingSet() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            assertAcceptedRelationMemberFromAnotherPostingSetIsRejected(driver)
        }
    }

    @Test
    fun migratedSchemaRejectsAcceptedRelationMemberFromAnotherPostingSet() {
        withMigratedSchema("rg04-migrated-posting-set-") { driver ->
            assertAcceptedRelationMemberFromAnotherPostingSetIsRejected(driver)
        }
    }

    @Test
    fun freshSchemaRejectsAcceptedRepaymentCompositionSnapshotInsert() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            assertAcceptedRepaymentCompositionSnapshotInsertIsRejected(driver)
        }
    }

    @Test
    fun migratedSchemaRejectsAcceptedRepaymentCompositionSnapshotInsert() {
        withMigratedSchema("rg04-migrated-composition-snapshot-") { driver ->
            assertAcceptedRepaymentCompositionSnapshotInsertIsRejected(driver)
        }
    }

    @Test
    fun freshSchemaRejectsAcceptedRepaymentMixedExpenseStructure() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            assertAcceptedRepaymentMixedExpenseStructureIsRejected(driver)
        }
    }

    @Test
    fun migratedSchemaRejectsAcceptedRepaymentMixedExpenseStructure() {
        withMigratedSchema("rg04-migrated-repayment-exclusivity-") { driver ->
            assertAcceptedRepaymentMixedExpenseStructureIsRejected(driver)
        }
    }

    @Test
    fun freshSchemaRejectsAcceptedRepaymentDirectComponentBypass() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            assertAcceptedRepaymentDirectComponentBypassIsRejected(driver)
        }
    }

    @Test
    fun migratedSchemaRejectsAcceptedRepaymentDirectComponentBypass() {
        withMigratedSchema("rg04-migrated-repayment-component-bypass-") { driver ->
            assertAcceptedRepaymentDirectComponentBypassIsRejected(driver)
        }
    }

    private fun assertMixedReceiptInvariants(driver: JdbcSqliteDriver) {
        assertMixedReceiptRejected(driver, "posting-members", relationPostingCount = 1, compositionComponentCount = 2, expectedMessage = "invalid mixed posting members")
        assertMixedReceiptRejected(driver, "composition-components", relationPostingCount = 2, compositionComponentCount = 1, expectedMessage = "invalid mixed composition components")
    }

    private fun assertMixedReceiptRejected(
        driver: JdbcSqliteDriver,
        suffix: String,
        relationPostingCount: Int,
        compositionComponentCount: Int,
        expectedMessage: String,
    ) {
        val requestId = "request-$suffix"
        val transactionId = "tx-$suffix"
        val postingSetId = "set-$suffix"
        val versionId = "version-$suffix"
        val expensePostingId = "expense-posting-$suffix"
        val assetPostingId = "asset-posting-$suffix"
        val liabilityPostingId = "liability-posting-$suffix"
        val confirmationId = "confirmation-$suffix"
        val relationId = "relation-$suffix"
        listOf(
            "INSERT INTO rg04_operation_request VALUES ('ledger-a', '$requestId', 'MANUAL_MIXED_EXPENSE')",
            "INSERT INTO rg04_mixed_expense_snapshot VALUES ('ledger-a', '$requestId', '2026-02-10T12:00:00+08:00', 12000, 'CNY', 2, 'daily', 13500, 1500, 12000, 'explicit_manual_save')",
            "INSERT INTO rg04_mixed_expense_component_snapshot VALUES ('ledger-a', '$requestId', 0, 'asset', 7000, 'CNY', 2)",
            "INSERT INTO rg04_mixed_expense_component_snapshot VALUES ('ledger-a', '$requestId', 1, 'liability', 5000, 'CNY', 2)",
            "INSERT INTO posting_set VALUES ('$postingSetId', 'ledger-a')",
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId', 'ledger-a', 'EXPENSE')",
            "INSERT INTO transaction_version VALUES ('$versionId', '$transactionId', 'ledger-a', 1, '$postingSetId', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', NULL)",
            "INSERT INTO ledger_transaction_current_version VALUES ('$transactionId', 'ledger-a', '$versionId')",
            "INSERT INTO posting VALUES ('$expensePostingId', '$postingSetId', 'ledger-a', 0, 'expense', 12000, 'CNY', 2)",
            "INSERT INTO posting VALUES ('$assetPostingId', '$postingSetId', 'ledger-a', 1, 'asset', -7000, 'CNY', 2)",
            "INSERT INTO posting VALUES ('$liabilityPostingId', '$postingSetId', 'ledger-a', 2, 'liability', -5000, 'CNY', 2)",
            "INSERT INTO rg04_confirmation VALUES ('ledger-a', '$confirmationId', '$requestId', '$transactionId', 'EXPLICIT_MANUAL_SAVE')",
            "INSERT INTO formal_relation VALUES ('ledger-a', '$relationId', 'mixed_payment')",
            "INSERT INTO rg04_mixed_composition VALUES ('ledger-a', '$relationId', '$transactionId', '混合支付', 12000, 'CNY', 2, 1, 0)",
            "INSERT INTO formal_relation_member VALUES ('ledger-a', '$relationId', 0, 'TRANSACTION', '$transactionId', NULL)",
        ).forEach { driver.execute(null, it, 0) }
        listOf(assetPostingId, liabilityPostingId).take(relationPostingCount).forEachIndexed { index, postingId ->
            driver.execute(null, "INSERT INTO formal_relation_member VALUES ('ledger-a', '$relationId', ${index + 1}, 'POSTING', NULL, '$postingId')", 0)
        }
        listOf(
            "'$assetPostingId', 'asset', 7000",
            "'$liabilityPostingId', 'liability', 5000",
        ).take(compositionComponentCount).forEachIndexed { index, values ->
            driver.execute(null, "INSERT INTO rg04_mixed_composition_component VALUES ('ledger-a', '$relationId', $index, $values, 'CNY', 2)", 0)
        }

        val failure = assertFailsWith<SQLException> {
            driver.execute(null, "INSERT INTO rg04_operation_receipt VALUES ('ledger-a', '$requestId', '$confirmationId', '$transactionId', 'ACCEPTED')", 0)
        }
        assertContains(failure.message.orEmpty(), expectedMessage)
    }

    private fun assertAcceptedRg04AggregateIsProtected(driver: JdbcSqliteDriver) {
        insertAcceptedManualAggregate(driver)
        assertNoAcceptedAggregateViolation(driver)
        driver.execute(null, "UPDATE rg04_mixed_composition SET display_name = 'Updated decoy' WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-decoy'", 0)
        assertNoAcceptedAggregateViolation(driver)
        driver.execute(null, "INSERT INTO formal_relation VALUES ('ledger-a', 'relation-second', 'mixed_payment')", 0)
        assertNoAcceptedAggregateViolation(driver)
        assertAcceptedMutationRejected(driver, "INSERT INTO rg04_mixed_composition VALUES ('ledger-a', 'relation-second', 'tx-protected', 'Second', 12000, 'CNY', 2, 1, 0)")
        assertAcceptedMutationRejected(driver, "INSERT INTO rg04_repayment_snapshot VALUES ('ledger-a', 'request-protected', '2026-02-10T12:00:00+08:00', 'asset', 'liability', 5000, 'CNY', 2, 'explicit_manual_save')")
        assertAcceptedMutationRejected(driver, "INSERT INTO rg04_posting_reconciliation VALUES ('ledger-a', 'reconciliation-expense-extra', 'expense-posting-protected', 'PENDING')")
        assertAcceptedMutationRejected(driver, "UPDATE posting SET amount_minor = 11900 WHERE ledger_id = 'ledger-a' AND posting_id = 'expense-posting-protected'")
        assertAcceptedMutationRejected(driver, "INSERT INTO posting VALUES ('posting-extra-protected', 'set-protected', 'ledger-a', 3, 'asset', 100, 'CNY', 2)")
        assertAcceptedMutationRejected(driver, "DELETE FROM posting WHERE ledger_id = 'ledger-a' AND posting_id = 'liability-posting-protected'")
        assertAcceptedMutationRejected(driver, "UPDATE ledger_transaction SET kind = 'CREDIT_REPAYMENT' WHERE ledger_id = 'ledger-a' AND transaction_id = 'tx-protected'")
        assertAcceptedMutationRejected(driver, "UPDATE transaction_version SET occurred_at = '2026-02-11T12:00:00+08:00' WHERE ledger_id = 'ledger-a' AND version_id = 'version-protected'")
        assertAcceptedMutationRejected(driver, "UPDATE transaction_version SET version_number = 2 WHERE ledger_id = 'ledger-a' AND version_id = 'version-protected'")
        assertAcceptedMutationRejected(driver, "UPDATE posting SET posting_index = 3 WHERE ledger_id = 'ledger-a' AND posting_id = 'expense-posting-protected'")
        driver.execute(null, "INSERT INTO transaction_version VALUES ('version-metadata-only', 'tx-protected', 'ledger-a', 2, 'set-protected', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', 'metadata only')", 0)
        assertNoAcceptedAggregateViolation(driver)
        assertAcceptedMutationRejected(driver, "UPDATE ledger_transaction_current_version SET current_version_id = 'version-metadata-only' WHERE ledger_id = 'ledger-a' AND transaction_id = 'tx-protected'")
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-set-alias', 'ledger-a', 'EXPENSE')", 0)
        assertNoAcceptedAggregateViolation(driver)
        assertAcceptedMutationRejected(driver, "INSERT INTO transaction_version VALUES ('version-set-alias', 'tx-set-alias', 'ledger-a', 1, 'set-protected', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', NULL)")
        assertAcceptedMutationRejected(driver, "DELETE FROM ledger_transaction_current_version WHERE ledger_id = 'ledger-a' AND transaction_id = 'tx-protected'")
        assertAcceptedMutationRejected(driver, "DELETE FROM posting_set WHERE ledger_id = 'ledger-a' AND posting_set_id = 'set-protected'")
        assertSqlRejected(driver, "UPDATE rg04_operation_request SET action_type = 'CREDIT_PRINCIPAL_REPAYMENT' WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected'", "accepted RG04 request")
        assertSqlRejected(driver, "DELETE FROM rg04_operation_request WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected'", "accepted RG04")
        assertSqlRejected(driver, "UPDATE rg04_mixed_expense_snapshot SET original_minor = 13600, discount_minor = 1600 WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected'", "accepted RG04 mixed snapshot")
        assertSqlRejected(driver, "DELETE FROM rg04_mixed_expense_snapshot WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected'", "accepted RG04")
        assertSqlRejected(driver, "UPDATE rg04_mixed_expense_component_snapshot SET amount_minor = 6999 WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected' AND component_index = 0", "accepted RG04 component snapshot")
        assertSqlRejected(driver, "DELETE FROM rg04_mixed_expense_component_snapshot WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected' AND component_index = 1", "accepted RG04 component snapshot")
        assertSqlRejected(driver, "UPDATE rg04_confirmation SET confirmation_id = 'confirmation-mutated' WHERE ledger_id = 'ledger-a' AND confirmation_id = 'confirmation-protected'", "accepted RG04 confirmation")
        assertSqlRejected(driver, "DELETE FROM rg04_confirmation WHERE ledger_id = 'ledger-a' AND confirmation_id = 'confirmation-protected'", "accepted RG04 confirmation")
        assertSqlRejected(driver, "UPDATE rg04_posting_semantic SET role = 'mixed_expense_credit_funding' WHERE ledger_id = 'ledger-a' AND posting_id = 'asset-posting-protected'", "accepted RG04 posting semantic")
        assertSqlRejected(driver, "DELETE FROM rg04_posting_semantic WHERE ledger_id = 'ledger-a' AND posting_id = 'asset-posting-protected'", "accepted RG04 posting semantic")
        assertSqlRejected(driver, "UPDATE rg04_settlement_explanation SET original_minor = 13600, discount_minor = 1600 WHERE ledger_id = 'ledger-a' AND transaction_id = 'tx-protected'", "accepted RG04 settlement")
        assertSqlRejected(driver, "DELETE FROM rg04_settlement_explanation WHERE ledger_id = 'ledger-a' AND transaction_id = 'tx-protected'", "accepted RG04 settlement")
        assertSqlRejected(driver, "DELETE FROM formal_relation_member WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected' AND member_index = 2", "accepted RG04 relation member")
        assertSqlRejected(driver, "UPDATE formal_relation_member SET posting_id = 'expense-posting-protected' WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected' AND member_index = 1", "accepted RG04 relation member")
        assertSqlRejected(driver, "UPDATE formal_relation_member SET posting_id = 'asset-posting-protected' WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected' AND member_index = 2", "accepted RG04 relation member")
        assertSqlRejected(driver, "DELETE FROM rg04_mixed_composition WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected'", "accepted RG04 composition")
        assertSqlRejected(driver, "UPDATE rg04_mixed_composition SET display_name = 'Changed' WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected'", "accepted RG04 composition")
        assertSqlRejected(driver, "UPDATE rg04_mixed_composition SET transaction_id = 'tx-decoy' WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected'", "accepted RG04 composition")
        assertSqlRejected(driver, "DELETE FROM rg04_mixed_composition_component WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected' AND component_index = 1", "accepted RG04 composition component")
        assertSqlRejected(driver, "UPDATE rg04_mixed_composition_component SET amount_minor = 6999 WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected' AND component_index = 0", "accepted RG04 composition component")
        assertSqlRejected(driver, "UPDATE rg04_mixed_composition_component SET relation_id = 'relation-decoy' WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected' AND component_index = 1", "accepted RG04 composition component")
        assertSqlRejected(driver, "DELETE FROM rg04_posting_reconciliation WHERE ledger_id = 'ledger-a' AND reconciliation_id = 'reconciliation-asset-protected'", "accepted RG04 reconciliation")
        assertSqlRejected(driver, "UPDATE rg04_posting_reconciliation SET posting_id = 'expense-posting-protected' WHERE ledger_id = 'ledger-a' AND reconciliation_id = 'reconciliation-asset-protected'", "accepted RG04 reconciliation")
        assertSqlRejected(driver, "UPDATE rg04_initial_reconciliation_identity SET posting_id = 'expense-posting-protected' WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected' AND reconciliation_index = 0", "accepted RG04 reconciliation identity")
        assertSqlRejected(driver, "UPDATE rg04_initial_reconciliation_identity SET reconciliation_id = 'reconciliation-decoy', posting_id = 'posting-decoy' WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected' AND reconciliation_index = 0", "accepted RG04 reconciliation identity")
        assertSqlRejected(driver, "DELETE FROM rg04_operation_receipt WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected'", "accepted RG04 receipt")
        assertSqlRejected(driver, "UPDATE rg04_operation_receipt SET outcome = 'ACCEPTED' WHERE ledger_id = 'ledger-a' AND request_id = 'request-protected'", "accepted RG04 receipt")

        insertAcceptedRepaymentAggregate(driver)
        assertNoAcceptedAggregateViolation(driver)
        assertAcceptedMutationRejected(driver, "INSERT INTO posting VALUES ('repayment-posting-extra', 'repayment-set-protected', 'ledger-a', 2, 'asset', 1, 'CNY', 2)")
        assertAcceptedMutationRejected(driver, "UPDATE posting SET amount_minor = -4999 WHERE ledger_id = 'ledger-a' AND posting_id = 'repayment-asset-posting-protected'")
        assertSqlRejected(driver, "UPDATE rg04_operation_request SET action_type = 'MANUAL_MIXED_EXPENSE' WHERE ledger_id = 'ledger-a' AND request_id = 'repayment-request-protected'", "accepted RG04 request")
        assertSqlRejected(driver, "UPDATE rg04_repayment_snapshot SET principal_minor = 4999 WHERE ledger_id = 'ledger-a' AND request_id = 'repayment-request-protected'", "accepted RG04 repayment snapshot")
        assertSqlRejected(driver, "DELETE FROM rg04_repayment_snapshot WHERE ledger_id = 'ledger-a' AND request_id = 'repayment-request-protected'", "accepted RG04 repayment snapshot")
        assertSqlRejected(driver, "DELETE FROM rg04_posting_reconciliation WHERE ledger_id = 'ledger-a' AND reconciliation_id = 'repayment-reconciliation-asset'", "accepted RG04 reconciliation")
    }

    private fun assertAcceptedRelationMemberFromAnotherPostingSetIsRejected(driver: JdbcSqliteDriver) {
        insertAcceptedManualAggregate(driver)
        driver.execute(
            null,
            """
            CREATE TRIGGER test_cross_posting_set_alias
            AFTER UPDATE OF posting_id ON formal_relation_member
            WHEN NEW.ledger_id = 'ledger-a' AND NEW.relation_id = 'relation-protected' AND NEW.member_index = 1
            BEGIN
              UPDATE rg04_mixed_composition_component
              SET posting_id = NEW.posting_id
              WHERE ledger_id = NEW.ledger_id AND relation_id = NEW.relation_id AND component_index = 0;
            END
            """.trimIndent(),
            0,
        )
        assertAcceptedMutationRejected(driver, "UPDATE formal_relation_member SET posting_id = 'cross-asset-posting-protected' WHERE ledger_id = 'ledger-a' AND relation_id = 'relation-protected' AND member_index = 1")
    }

    private fun assertAcceptedRepaymentCompositionSnapshotInsertIsRejected(driver: JdbcSqliteDriver) {
        insertAcceptedRepaymentAggregate(driver)
        assertAcceptedMutationRejected(driver, "INSERT INTO rg04_accepted_composition_snapshot VALUES ('ledger-a', 'repayment-request-protected', 'repayment-relation-snapshot', 'repayment-tx-protected', 'Repayment decoy', 1, 'CNY', 2, 1, 0)")
    }

    private fun assertAcceptedRepaymentMixedExpenseStructureIsRejected(driver: JdbcSqliteDriver) {
        insertAcceptedRepaymentAggregate(driver)
        driver.execute(null, "INSERT INTO formal_relation VALUES ('ledger-a', 'repayment-relation-extra', 'mixed_payment')", 0)
        assertAcceptedMutationRejected(driver, "INSERT INTO rg04_mixed_composition VALUES ('ledger-a', 'repayment-relation-extra', 'repayment-tx-protected', 'Repayment decoy', 1, 'CNY', 2, 1, 0)")
        assertAcceptedMutationRejected(driver, "INSERT INTO formal_relation_member VALUES ('ledger-a', 'repayment-relation-extra', 0, 'TRANSACTION', 'repayment-tx-protected', NULL)")
        assertAcceptedMutationRejected(driver, "INSERT INTO formal_relation_member VALUES ('ledger-a', 'repayment-relation-extra', 1, 'POSTING', NULL, 'repayment-asset-posting-protected')")
        assertAcceptedMutationRejected(driver, "INSERT INTO rg04_settlement_explanation VALUES ('ledger-a', 'repayment-tx-protected', 1, 0, 1, 0, 'CNY', 2)")
    }

    private fun assertAcceptedRepaymentDirectComponentBypassIsRejected(driver: JdbcSqliteDriver) {
        insertAcceptedRepaymentAggregate(driver)
        driver.execute(null, "INSERT INTO formal_relation VALUES ('ledger-a', 'repayment-decoy-relation', 'mixed_payment')", 0)
        assertNoAcceptedAggregateViolation(driver)
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('repayment-decoy-tx', 'ledger-a', 'EXPENSE')", 0)
        assertNoAcceptedAggregateViolation(driver)
        driver.execute(null, "INSERT INTO rg04_mixed_composition VALUES ('ledger-a', 'repayment-decoy-relation', 'repayment-decoy-tx', 'Decoy composition', 1, 'CNY', 2, 1, 0)", 0)
        assertNoAcceptedAggregateViolation(driver)
        assertAcceptedMutationRejected(driver, "INSERT INTO rg04_mixed_composition_component VALUES ('ledger-a', 'repayment-decoy-relation', 0, 'repayment-asset-posting-protected', 'asset', 1, 'CNY', 2)")
    }

    private fun insertAcceptedManualAggregate(driver: JdbcSqliteDriver) {
        listOf(
            "INSERT INTO rg04_operation_request VALUES ('ledger-a', 'request-protected', 'MANUAL_MIXED_EXPENSE')",
            "INSERT INTO rg04_mixed_expense_snapshot VALUES ('ledger-a', 'request-protected', '2026-02-10T12:00:00+08:00', 12000, 'CNY', 2, 'daily', 13500, 1500, 12000, 'explicit_manual_save')",
            "INSERT INTO rg04_mixed_expense_component_snapshot VALUES ('ledger-a', 'request-protected', 0, 'asset', 7000, 'CNY', 2)",
            "INSERT INTO rg04_mixed_expense_component_snapshot VALUES ('ledger-a', 'request-protected', 1, 'liability', 5000, 'CNY', 2)",
            "INSERT INTO posting_set VALUES ('set-protected', 'ledger-a')",
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-protected', 'ledger-a', 'EXPENSE')",
            "INSERT INTO transaction_version VALUES ('version-protected', 'tx-protected', 'ledger-a', 1, 'set-protected', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', '2026-02-10T12:00:00+08:00', NULL)",
            "INSERT INTO ledger_transaction_current_version VALUES ('tx-protected', 'ledger-a', 'version-protected')",
            "INSERT INTO posting VALUES ('expense-posting-protected', 'set-protected', 'ledger-a', 0, 'expense', 12000, 'CNY', 2)",
            "INSERT INTO posting VALUES ('asset-posting-protected', 'set-protected', 'ledger-a', 1, 'asset', -7000, 'CNY', 2)",
            "INSERT INTO posting VALUES ('liability-posting-protected', 'set-protected', 'ledger-a', 2, 'liability', -5000, 'CNY', 2)",
            "INSERT INTO posting_set VALUES ('set-decoy', 'ledger-a')",
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-decoy', 'ledger-a', 'CREDIT_REPAYMENT')",
            "INSERT INTO transaction_version VALUES ('version-decoy', 'tx-decoy', 'ledger-a', 1, 'set-decoy', '2026-03-01T00:00:00+08:00', '2026-03-01T00:00:00+08:00', '2026-03-01T00:00:00+08:00', NULL)",
            "INSERT INTO ledger_transaction_current_version VALUES ('tx-decoy', 'ledger-a', 'version-decoy')",
            "INSERT INTO posting VALUES ('posting-decoy', 'set-decoy', 'ledger-a', 0, 'asset', -1, 'CNY', 2)",
            "INSERT INTO posting VALUES ('cross-asset-posting-protected', 'set-decoy', 'ledger-a', 1, 'asset', -7000, 'CNY', 2)",
            "INSERT INTO rg04_posting_semantic VALUES ('ledger-a', 'expense-posting-protected', 'expense', 'daily', 0)",
            "INSERT INTO rg04_posting_semantic VALUES ('ledger-a', 'asset-posting-protected', 'mixed_expense_asset_funding', NULL, 1)",
            "INSERT INTO rg04_posting_semantic VALUES ('ledger-a', 'liability-posting-protected', 'mixed_expense_credit_funding', NULL, 1)",
            "INSERT INTO rg04_posting_semantic VALUES ('ledger-a', 'posting-decoy', 'credit_repayment_asset_outflow', NULL, 1)",
            "INSERT INTO rg04_posting_semantic VALUES ('ledger-a', 'cross-asset-posting-protected', 'mixed_expense_asset_funding', NULL, 1)",
            "INSERT INTO rg04_confirmation VALUES ('ledger-a', 'confirmation-protected', 'request-protected', 'tx-protected', 'EXPLICIT_MANUAL_SAVE')",
            "INSERT INTO rg04_settlement_explanation VALUES ('ledger-a', 'tx-protected', 13500, 1500, 12000, 0, 'CNY', 2)",
            "INSERT INTO formal_relation VALUES ('ledger-a', 'relation-protected', 'mixed_payment')",
            "INSERT INTO formal_relation_member VALUES ('ledger-a', 'relation-protected', 0, 'TRANSACTION', 'tx-protected', NULL)",
            "INSERT INTO formal_relation_member VALUES ('ledger-a', 'relation-protected', 1, 'POSTING', NULL, 'asset-posting-protected')",
            "INSERT INTO formal_relation_member VALUES ('ledger-a', 'relation-protected', 2, 'POSTING', NULL, 'liability-posting-protected')",
            "INSERT INTO rg04_mixed_composition VALUES ('ledger-a', 'relation-protected', 'tx-protected', '混合支付', 12000, 'CNY', 2, 1, 0)",
            "INSERT INTO rg04_mixed_composition_component VALUES ('ledger-a', 'relation-protected', 0, 'asset-posting-protected', 'asset', 7000, 'CNY', 2)",
            "INSERT INTO rg04_mixed_composition_component VALUES ('ledger-a', 'relation-protected', 1, 'liability-posting-protected', 'liability', 5000, 'CNY', 2)",
            "INSERT INTO formal_relation VALUES ('ledger-a', 'relation-decoy', 'mixed_payment')",
            "INSERT INTO rg04_mixed_composition VALUES ('ledger-a', 'relation-decoy', 'tx-decoy', 'Mixed payment', 1, 'CNY', 2, 1, 0)",
            "INSERT INTO rg04_posting_reconciliation VALUES ('ledger-a', 'reconciliation-asset-protected', 'asset-posting-protected', 'PENDING')",
            "INSERT INTO rg04_posting_reconciliation VALUES ('ledger-a', 'reconciliation-liability-protected', 'liability-posting-protected', 'PENDING')",
            "INSERT INTO rg04_posting_reconciliation VALUES ('ledger-a', 'reconciliation-decoy', 'posting-decoy', 'PENDING')",
            "INSERT INTO rg04_initial_reconciliation_identity VALUES ('ledger-a', 'request-protected', 0, 'reconciliation-asset-protected', 'asset-posting-protected')",
            "INSERT INTO rg04_initial_reconciliation_identity VALUES ('ledger-a', 'request-protected', 1, 'reconciliation-liability-protected', 'liability-posting-protected')",
            "INSERT INTO rg04_operation_receipt VALUES ('ledger-a', 'request-protected', 'confirmation-protected', 'tx-protected', 'ACCEPTED')",
        ).forEach { driver.execute(null, it, 0) }
    }

    private fun insertAcceptedRepaymentAggregate(driver: JdbcSqliteDriver) {
        listOf(
            "INSERT INTO rg04_operation_request VALUES ('ledger-a', 'repayment-request-protected', 'CREDIT_PRINCIPAL_REPAYMENT')",
            "INSERT INTO rg04_repayment_snapshot VALUES ('ledger-a', 'repayment-request-protected', '2026-03-05T09:00:00+08:00', 'asset', 'liability', 5000, 'CNY', 2, 'explicit_manual_save')",
            "INSERT INTO posting_set VALUES ('repayment-set-protected', 'ledger-a')",
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('repayment-tx-protected', 'ledger-a', 'CREDIT_REPAYMENT')",
            "INSERT INTO transaction_version VALUES ('repayment-version-protected', 'repayment-tx-protected', 'ledger-a', 1, 'repayment-set-protected', '2026-03-05T09:00:00+08:00', '2026-03-05T09:00:00+08:00', '2026-03-05T09:00:00+08:00', NULL)",
            "INSERT INTO ledger_transaction_current_version VALUES ('repayment-tx-protected', 'ledger-a', 'repayment-version-protected')",
            "INSERT INTO posting VALUES ('repayment-asset-posting-protected', 'repayment-set-protected', 'ledger-a', 0, 'asset', -5000, 'CNY', 2)",
            "INSERT INTO posting VALUES ('repayment-liability-posting-protected', 'repayment-set-protected', 'ledger-a', 1, 'liability', 5000, 'CNY', 2)",
            "INSERT INTO rg04_posting_semantic VALUES ('ledger-a', 'repayment-asset-posting-protected', 'credit_repayment_asset_outflow', NULL, 1)",
            "INSERT INTO rg04_posting_semantic VALUES ('ledger-a', 'repayment-liability-posting-protected', 'credit_repayment_liability_principal', NULL, 1)",
            "INSERT INTO rg04_confirmation VALUES ('ledger-a', 'repayment-confirmation-protected', 'repayment-request-protected', 'repayment-tx-protected', 'EXPLICIT_MANUAL_SAVE')",
            "INSERT INTO rg04_posting_reconciliation VALUES ('ledger-a', 'repayment-reconciliation-asset', 'repayment-asset-posting-protected', 'PENDING')",
            "INSERT INTO rg04_posting_reconciliation VALUES ('ledger-a', 'repayment-reconciliation-liability', 'repayment-liability-posting-protected', 'PENDING')",
            "INSERT INTO rg04_initial_reconciliation_identity VALUES ('ledger-a', 'repayment-request-protected', 0, 'repayment-reconciliation-asset', 'repayment-asset-posting-protected')",
            "INSERT INTO rg04_initial_reconciliation_identity VALUES ('ledger-a', 'repayment-request-protected', 1, 'repayment-reconciliation-liability', 'repayment-liability-posting-protected')",
            "INSERT INTO rg04_operation_receipt VALUES ('ledger-a', 'repayment-request-protected', 'repayment-confirmation-protected', 'repayment-tx-protected', 'ACCEPTED')",
        ).forEach { driver.execute(null, it, 0) }
    }

    private fun assertSqlRejected(driver: JdbcSqliteDriver, sql: String, message: String) {
        val failure = assertFailsWith<SQLException> { driver.execute(null, sql, 0) }
        assertContains(failure.message.orEmpty(), message)
    }

    private fun assertAcceptedMutationRejected(driver: JdbcSqliteDriver, sql: String) {
        assertSqlRejected(driver, sql, "accepted RG04")
        assertNoAcceptedAggregateViolation(driver)
    }

    private fun assertNoAcceptedAggregateViolation(driver: JdbcSqliteDriver) {
        val count = driver.executeQuery(
            identifier = null,
            sql = "SELECT count(*) FROM rg04_accepted_aggregate_violation",
            mapper = { cursor ->
                check(cursor.next().value)
                QueryResult.Value(requireNotNull(cursor.getLong(0)))
            },
            parameters = 0,
        ).value
        assertEquals(0L, count)
    }

    private fun withMigratedSchema(prefix: String, block: (JdbcSqliteDriver) -> Unit) {
        val path = Files.createTempFile(prefix, ".db")
        try {
            Files.copy(repoFile("ledger-data/src/commonMain/sqldelight/databases/1.db"), path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}", sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 6)
                block(driver)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

private fun sqliteProperties() = Properties().apply { setProperty("foreign_keys", "true") }

private fun repoFile(relative: String): Path {
    var candidate = Path.of("").toAbsolutePath()
    repeat(6) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
