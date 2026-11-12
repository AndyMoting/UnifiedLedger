package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg06ExecutionResult
import com.unifiedledger.application.Rg06FieldPath
import com.unifiedledger.application.Rg06Operation
import com.unifiedledger.application.Rg06RejectionReason
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.StagedPaymentRole
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightRg06RejectionEnumerationTest : Rg06StoreTestSupport() {
    @Test
    fun finalReceiptRejectsCanonicalGraphMismatchForEveryAction() {
        data class Case(
            val name: String,
            val prepare: (SqlDelightRg06Store) -> Rg06Operation,
            val mutation: String,
        )

        fun accepted(
            store: SqlDelightRg06Store,
            operation: Rg06Operation,
        ): Rg06Operation {
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
            return operation
        }

        fun created(store: SqlDelightRg06Store) = accepted(store, createOperation())

        fun recorded(store: SqlDelightRg06Store): Rg06Operation {
            created(store)
            return accepted(
                store,
                record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")),
            )
        }

        fun fulfilled(store: SqlDelightRg06Store): Rg06Operation {
            recorded(store)
            return accepted(store, fulfill())
        }

        fun completed(store: SqlDelightRg06Store): Rg06Operation {
            recorded(store)
            accepted(store, record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00")))
            return accepted(store, complete())
        }

        fun linked(store: SqlDelightRg06Store): Rg06Operation {
            recorded(store)
            return accepted(store, linkDepositEvidence())
        }

        fun ingested(store: SqlDelightRg06Store) = accepted(store, ingest())

        fun confirmed(store: SqlDelightRg06Store): Rg06Operation {
            created(store)
            ingested(store)
            return accepted(store, confirmCandidate())
        }

        fun mirrored(store: SqlDelightRg06Store): Rg06Operation {
            confirmed(store)
            return accepted(store, mergeDepositMirror())
        }

        val cases =
            listOf(
                Case("create.amount", ::created, "amount_minor = 30001"),
                Case("create.currency", ::created, "currency_code = 'USD'"),
                Case("create.precision", ::created, "currency_precision = 3"),
                Case("create.category", ::created, "category_id = 'expense-other'"),
                Case("create.time", ::created, "occurred_at = '2026-04-20T01:00:01Z'"),
                Case("record.relation", ::recorded, "relation_id = 'relation-other'"),
                Case("record.role", ::recorded, "payment_role = 'FINAL'"),
                Case("record.amount", ::recorded, "amount_minor = 8001"),
                Case("record.currency", ::recorded, "currency_code = 'USD'"),
                Case("record.precision", ::recorded, "currency_precision = 3"),
                Case("record.funding", ::recorded, "funding_account_id = 'asset-other'"),
                Case("record.time", ::recorded, "occurred_at = '2026-04-28T02:00:01Z'"),
                Case("fulfillment.relation", ::fulfilled, "relation_id = 'relation-other'"),
                Case("fulfillment.status", ::fulfilled, "fulfillment_status = 'IN_PROGRESS'"),
                Case("fulfillment.time", ::fulfilled, "occurred_at = '2026-04-29T02:00:01Z'"),
                Case("completion.relation", ::completed, "relation_id = 'relation-other'"),
                Case("completion.confirmed", ::completed, "confirmed = 0"),
                Case("completion.time", ::completed, "occurred_at = '2026-05-04T02:00:01Z'"),
                Case("link.source", ::linked, "source_id = 'manual-source-other'"),
                Case("link.evidence", ::linked, "evidence_id = 'manual-evidence-other'"),
                Case("link.payment", ::linked, "payment_id = 'payment-other'"),
                Case("link.posting", ::linked, "posting_id = 'posting-other'"),
                Case("ingest.amount", ::ingested, "amount_minor = -8001"),
                Case("ingest.currency", ::ingested, "currency_code = 'USD'"),
                Case("ingest.precision", ::ingested, "currency_precision = 3"),
                Case("ingest.time", ::ingested, "occurred_at = '2026-04-28T02:00:01Z'"),
                Case("ingest.time-text", ::ingested, "occurred_at_text = '2026-04-28T10:00:01+08:00'"),
                Case("ingest.role", ::ingested, "suggested_payment_role = 'FINAL'"),
                Case("candidate.candidate", ::confirmed, "candidate_id = 'candidate-other'"),
                Case("candidate.relation", ::confirmed, "relation_id = 'relation-other'"),
                Case("candidate.role", ::confirmed, "payment_role = 'FINAL'"),
                Case("candidate.category", ::confirmed, "category_id = 'expense-other'"),
                Case("candidate.funding", ::confirmed, "funding_account_id = 'asset-other'"),
                Case("candidate.exact-binding", ::confirmed, "exact_binding_confirmed = 0"),
                Case("mirror.source", ::mirrored, "source_id = 'mirror-source-other'"),
                Case("mirror.evidence", ::mirrored, "evidence_id = 'mirror-evidence-other'"),
                Case("mirror.payment", ::mirrored, "payment_id = 'payment-other'"),
                Case("mirror.posting", ::mirrored, "posting_id = 'posting-other'"),
                Case("mirror.amount", ::mirrored, "amount_minor = 8001"),
                Case("mirror.currency", ::mirrored, "currency_code = 'USD'"),
                Case("mirror.precision", ::mirrored, "currency_precision = 3"),
                Case("mirror.time", ::mirrored, "occurred_at = '2026-04-28T02:00:01Z'"),
                Case("mirror.time-text", ::mirrored, "occurred_at_text = '2026-04-28T10:00:01+08:00'"),
            )

        assertEquals(43, cases.size)
        cases.forEach { case ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                val operation = case.prepare(store)
                val receipts =
                    database.ledgerQueries
                        .selectRg06Receipts(operation.ledgerId.value, operation.identity.value)
                        .executeAsList()
                        .map { it.id_kind to it.id_value }
                check(receipts.isNotEmpty())
                driver.execute(null, "DROP TRIGGER rg06_receipt_guard_delete", 0)
                driver.execute(null, "DROP TRIGGER rg06_operation_guard_update", 0)
                driver.execute(
                    null,
                    "DELETE FROM rg06_operation_receipt WHERE ledger_id = '${operation.ledgerId.value}' AND identity_value = '${operation.identity.value}'",
                    0,
                )
                driver.execute(
                    null,
                    "UPDATE rg06_operation SET ${case.mutation} WHERE ledger_id = '${operation.ledgerId.value}' AND identity_value = '${operation.identity.value}'",
                    0,
                )
                receipts.dropLast(1).forEachIndexed { index, (kind, value) ->
                    driver.execute(
                        null,
                        "INSERT INTO rg06_operation_receipt VALUES ('${operation.ledgerId.value}','${operation.identity.value}',$index,'$kind','$value')",
                        0,
                    )
                }
                val finalIndex = receipts.lastIndex
                val (finalKind, finalValue) = receipts.last()
                val failure =
                    assertFailsWith<SQLException>(case.name) {
                        driver.execute(
                            null,
                            "INSERT INTO rg06_operation_receipt VALUES ('${operation.ledgerId.value}','${operation.identity.value}',$finalIndex,'$finalKind','$finalValue')",
                            0,
                        )
                    }
                assertTrue(
                    failure.message.orEmpty().contains("rg06 incomplete accepted operation"),
                    case.name,
                )
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun storePortsFrozenEighteenRejectionsWithZeroWriteAndIdentityReuse() {
        data class Case(
            val name: String,
            val expected: Rg06ExecutionResult.Rejected,
            val prepare: (SqlDelightRg06Store) -> Unit,
            val invalid: () -> Rg06Operation,
            val corrected: (Rg06Operation) -> Rg06Operation,
        )
        val createOnly: (SqlDelightRg06Store) -> Unit = { }
        val createThenDeposit: (SqlDelightRg06Store) -> Unit = { store ->
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))))
        }
        val createThenBoth: (SqlDelightRg06Store) -> Unit = { store ->
            createThenDeposit(store)
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))))
        }

        fun createCase(
            name: String,
            total: Long,
            category: CategoryId?,
            reason: Rg06RejectionReason,
            path: Rg06FieldPath,
        ) = Case(
            name,
            Rg06ExecutionResult.Rejected(reason, path),
            createOnly,
            { createOperation().copy(input = createOperation().input.copy(requestId = RequestId("request-$name"), totalAmount = Money.ofMinor(total, CNY), categoryId = category)) },
            { op -> (op as Rg06Operation.CreateStagedPayment).copy(input = op.input.copy(totalAmount = Money.ofMinor(30_000, CNY), categoryId = CategoryId("expense-service"))) },
        )

        fun depositCase(
            name: String,
            amount: Money,
            account: AccountId = AccountId("asset-bank"),
            reason: Rg06RejectionReason,
            path: Rg06FieldPath = Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT,
        ) = Case(
            name,
            Rg06ExecutionResult.Rejected(reason, path),
            { store -> assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation())) },
            { record("deposit", StagedPaymentRole.DEPOSIT, amount.minorUnits, Instant.parse("2026-04-28T10:00:00+08:00")).copy(input = record("deposit", StagedPaymentRole.DEPOSIT, amount.minorUnits, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(requestId = RequestId("request-$name"), paymentAmount = amount, fundingAccountId = account)) },
            { op -> (op as Rg06Operation.RecordStagedPaymentInstallment).copy(input = op.input.copy(paymentAmount = Money.ofMinor(8_000, CNY), fundingAccountId = AccountId("asset-bank"))) },
        )

        fun finalCase(
            name: String,
            amount: Long,
            reason: Rg06RejectionReason,
        ) = Case(
            name,
            Rg06ExecutionResult.Rejected(reason, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT),
            createThenDeposit,
            { record("final", StagedPaymentRole.FINAL, amount, Instant.parse("2026-05-03T16:30:00+08:00")).copy(input = record("final", StagedPaymentRole.FINAL, amount, Instant.parse("2026-05-03T16:30:00+08:00")).input.copy(requestId = RequestId("request-$name"), paymentAmount = Money.ofMinor(amount, CNY))) },
            { op -> (op as Rg06Operation.RecordStagedPaymentInstallment).copy(input = op.input.copy(paymentAmount = Money.ofMinor(22_000, CNY))) },
        )
        val cases: List<Case> =
            listOf(
                createCase("zero-total", 0, CategoryId("expense-service"), Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT),
                createCase("negative-total", -100, CategoryId("expense-service"), Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT),
                createCase("null-category", 30_000, null, Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID),
                createCase("primary-category", 30_000, CategoryId("expense-root"), Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID),
                createCase("inactive-category", 30_000, CategoryId("expense-inactive"), Rg06RejectionReason.CATEGORY_INACTIVE, Rg06FieldPath.ATTEMPTED_CATEGORY_ID),
                createCase("income-category", 30_000, CategoryId("income-child"), Rg06RejectionReason.EXPENSE_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID),
                depositCase("zero-payment", Money.ofMinor(0, CNY), reason = Rg06RejectionReason.MUST_BE_POSITIVE),
                depositCase("negative-payment", Money.ofMinor(-100, CNY), reason = Rg06RejectionReason.MUST_BE_POSITIVE),
                depositCase("deposit-equals", Money.ofMinor(30_000, CNY), reason = Rg06RejectionReason.DEPOSIT_MUST_BE_LESS_THAN_TOTAL),
                depositCase("deposit-exceeds", Money.ofMinor(30_100, CNY), reason = Rg06RejectionReason.DEPOSIT_MUST_BE_LESS_THAN_TOTAL),
                finalCase("final-exceeds", 22_100, Rg06RejectionReason.PAYMENT_EXCEEDS_DUE),
                finalCase("final-below", 21_900, Rg06RejectionReason.FINAL_MUST_EQUAL_REMAINING_DUE),
                Case("currency", Rg06ExecutionResult.Rejected(Rg06RejectionReason.SINGLE_CURRENCY_REQUIRED, Rg06FieldPath.ATTEMPTED_CURRENCY), { store -> assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation())) }, { record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).copy(input = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(requestId = RequestId("request-currency"), paymentAmount = Money.ofMinor(8_000, USD))) }, { op: Rg06Operation -> (op as Rg06Operation.RecordStagedPaymentInstallment).copy(input = op.input.copy(paymentAmount = Money.ofMinor(8_000, CNY))) }),
                depositCase("unknown", Money.ofMinor(8_000, CNY), AccountId("asset-missing"), Rg06RejectionReason.UNKNOWN_REAL_ACCOUNT, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
                depositCase("nonfinancial", Money.ofMinor(8_000, CNY), AccountId("asset-nonfinancial"), Rg06RejectionReason.REAL_FINANCIAL_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
                depositCase("external", Money.ofMinor(8_000, CNY), AccountId("asset-external"), Rg06RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
                depositCase("liability", Money.ofMinor(8_000, CNY), AccountId("liability"), Rg06RejectionReason.ASSET_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
                Case("due", Rg06ExecutionResult.Rejected(Rg06RejectionReason.DUE_MUST_BE_ZERO, Rg06FieldPath.ATTEMPTED_PAYMENT_PROGRESS), { store -> assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation())) }, { complete().copy(input = complete().input.copy(requestId = RequestId("request-due"))) }, { op: Rg06Operation -> (op as Rg06Operation.ConfirmStagedPaymentCompletion).copy(input = op.input.copy(confirmed = true)) }),
            )
        assertEquals(18, cases.size)
        cases.forEach { case ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                case.prepare(store)
                val before = storeSnapshot(database)
                val invalid = case.invalid()
                assertEquals(case.expected, store.commit(invalid), case.name)
                assertEquals(before, storeSnapshot(database), case.name)
                val corrected: Rg06Operation =
                    if (case.name == "due") {
                        assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))))
                        assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))))
                        val invalidCompletion = invalid as Rg06Operation.ConfirmStagedPaymentCompletion
                        invalidCompletion.copy(input = invalidCompletion.input.copy(confirmed = true))
                    } else {
                        case.corrected(invalid)
                    }
                val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(corrected), case.name)
                assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(corrected), case.name)
            } finally {
                driver.close()
            }
        }
    }
}
