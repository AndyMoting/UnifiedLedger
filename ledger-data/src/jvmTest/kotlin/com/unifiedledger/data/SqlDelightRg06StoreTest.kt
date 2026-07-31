package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import java.nio.file.Files
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString

class SqlDelightRg06StoreTest {
    @Test
    fun createPersistsNormalizedStateAndReplaysOriginalOrderedIds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            val operation = createOperation()

            val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
            assertEquals(
                listOf(
                    Rg06ReturnedId.Relation(operation.ids.relationId),
                    Rg06ReturnedId.Lifecycle(operation.ids.lifecycleId),
                ),
                accepted.returnedIds,
            )
            assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(operation))
            assertIs<Rg06ExecutionResult.RequestIdentityConflict>(
                store.commit(operation.copy(input = operation.input.copy(totalAmount = Money.ofMinor(30_001, CNY)))),
            )
            assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06Relations().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06Lifecycles().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06History().executeAsOne())
            assertEquals(listOf("RELATION", "LIFECYCLE"), database.ledgerQueries.selectRg06ReceiptKinds("ledger-a", "request-create").executeAsList())
        } finally {
            driver.close()
        }
    }

    @Test
    fun manualEightActionLifecyclePersistsFormalGraphAndExactEvidence() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val observation = manualObservation()
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { source, evidence ->
                observation.takeIf { source == Rg06SourceId("manual-source") && evidence == Rg06EvidenceId("manual-evidence") }
            }

            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            val deposit = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            val final = record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))
            assertRetryAndConflict(store, deposit, deposit.copy(input = deposit.input.copy(paymentAmount = Money.ofMinor(8_001, CNY))))
            val fulfillment = fulfill()
            assertRetryAndConflict(store, fulfillment, fulfillment.copy(input = fulfillment.input.copy(occurredAt = Instant.parse("2026-04-29T10:00:01+08:00"))))
            assertRetryAndConflict(store, final, final.copy(input = final.input.copy(actualPaymentAt = Instant.parse("2026-05-03T16:31:00+08:00"))))
            val completion = complete()
            assertRetryAndConflict(store, completion, completion.copy(input = completion.input.copy(confirmed = false)))
            val link = Rg06Operation.LinkStagedPaymentEvidence(
                LedgerId("ledger-a"),
                Rg06LinkStagedPaymentEvidenceInput(Rg06SourceId("manual-source"), Rg06EvidenceId("manual-evidence"), deposit.ids.paymentIds.paymentId, deposit.ids.paymentIds.expenseIds.paymentPostingId),
                Rg06EvidenceLinkId("manual-link"),
            )
            assertRetryAndConflict(store, link, link.copy(input = link.input.copy(postingId = PostingId("other"))))
            assertEquals(2L, database.ledgerQueries.countRg06Installments().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
            assertEquals("MATCHED", database.ledgerQueries.selectRg06ReconciliationForPosting("ledger-a", deposit.ids.paymentIds.expenseIds.paymentPostingId.value).executeAsOne().status)
        } finally {
            driver.close()
        }
    }

    @Test
    fun manualEvidenceUsesOneImmutableObservationForValidationAndPersistence() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            var calls = 0
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ ->
                calls += 1
                if (calls == 1) manualObservation() else Rg06ManualBankObservation(Money.ofMinor(-7_999, CNY), manualObservation().observedAt)
            }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            val deposit = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(deposit))
            val link = Rg06Operation.LinkStagedPaymentEvidence(
                LedgerId("ledger-a"),
                Rg06LinkStagedPaymentEvidenceInput(Rg06SourceId("manual-source"), Rg06EvidenceId("manual-evidence"), deposit.ids.paymentIds.paymentId, deposit.ids.paymentIds.expenseIds.paymentPostingId),
                Rg06EvidenceLinkId("manual-link"),
            )

            assertIs<Rg06ExecutionResult.Accepted>(store.commit(link))

            assertEquals(1, calls)
            assertEquals(-8_000L, database.ledgerQueries.selectRg06Source("manual-source").executeAsOne().amount_minor)
        } finally {
            driver.close()
        }
    }

    @Test
    fun sequentialSecondManualLinkReturnsTypedRejectionAndIdentityCanBeReused() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { sourceId, _ ->
                if (sourceId.value == "manual-source-second") {
                    Rg06ManualBankObservation(Money.ofMinor(-22_000, CNY), manualObservation().observedAt)
                } else {
                    manualObservation()
                }
            }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            val deposit = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            val final = record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(deposit))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(final))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(linkDepositEvidence()))
            val duplicate = linkDepositEvidence().copy(
                input = linkDepositEvidence().input.copy(
                    sourceId = Rg06SourceId("manual-source-second"),
                    evidenceId = Rg06EvidenceId("manual-evidence-second"),
                ),
                evidenceLinkId = Rg06EvidenceLinkId("manual-link-second"),
            )
            val before = storeSnapshot(database)

            assertEquals(
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.EVIDENCE_ALREADY_BOUND,
                    Rg06FieldPath.INPUT_POSTING_ID,
                ),
                store.commit(duplicate),
            )
            assertEquals(before, storeSnapshot(database))

            val corrected = duplicate.copy(
                input = duplicate.input.copy(
                    paymentId = final.ids.paymentIds.paymentId,
                    postingId = final.ids.paymentIds.expenseIds.paymentPostingId,
                ),
            )
            val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(corrected))
            assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(corrected))
            assertEquals(2L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun unrelatedFailureAfterDuplicateManualLinkClaimEscapes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> manualObservation() }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            assertIs<Rg06ExecutionResult.Accepted>(
                store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))),
            )
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(linkDepositEvidence()))
            val duplicate = linkDepositEvidence().copy(
                input = linkDepositEvidence().input.copy(
                    sourceId = Rg06SourceId("manual-source-failing"),
                    evidenceId = Rg06EvidenceId("manual-evidence-failing"),
                ),
                evidenceLinkId = Rg06EvidenceLinkId("manual-link-failing"),
            )
            val failingStore = SqlDelightRg06Store(
                database,
                driver,
                catalog(),
                "+08:00",
                Rg06ManualObservationSource { _, _ -> manualObservation() },
                Rg06FailureInjector { point ->
                    if (point == Rg06FailurePoint.AFTER_CLAIM) error("injected-link-failure")
                },
            )
            val before = storeSnapshot(database)

            val thrown = assertFails { failingStore.commit(duplicate) }

            assertEquals("injected-link-failure", thrown.message)
            assertEquals(before, storeSnapshot(database))
        } finally {
            driver.close()
        }
    }

    @Test
    fun finalReceiptRejectsCanonicalGraphMismatchForEveryAction() {
        data class Case(
            val name: String,
            val prepare: (SqlDelightRg06Store) -> Rg06Operation,
            val mutation: String,
        )

        fun accepted(store: SqlDelightRg06Store, operation: Rg06Operation): Rg06Operation {
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

        val cases = listOf(
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
                val receipts = database.ledgerQueries
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
                val failure = assertFailsWith<SQLException>(case.name) {
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
    fun importConfirmAndMirrorUseStoredFactsAndMatchedReconciliation() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            store.commit(createOperation())
            val ingest = ingest()
            assertRetryAndConflict(store, ingest, ingest.copy(input = ingest.input.copy(amount = Money.ofMinor(-8_001, CNY))))
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            val confirmation = confirmCandidate()
            assertRetryAndConflict(store, confirmation, confirmation.copy(input = confirmation.input.copy(categoryId = CategoryId("other"))))
            assertEquals("MATCHED", database.ledgerQueries.selectRg06ReconciliationForPosting("ledger-a", confirmation.ids.paymentIds.expenseIds.paymentPostingId.value).executeAsOne().status)
            val mirror = Rg06Operation.MergeStagedPaymentMirrorEvidence(
                LedgerId("ledger-a"),
                Rg06MergeStagedPaymentMirrorEvidenceInput(Rg06SourceId("mirror-source"), Rg06EvidenceId("mirror-evidence"), confirmation.ids.paymentIds.paymentId, confirmation.ids.paymentIds.expenseIds.paymentPostingId, Money.ofMinor(8_000, CNY), Instant.parse("2026-04-28T10:00:00+08:00"), "2026-04-28T10:00:00+08:00"),
            )
            assertRetryAndConflict(store, mirror, mirror.copy(input = mirror.input.copy(amount = Money.ofMinor(8_001, CNY))))
            assertEquals(2L, database.ledgerQueries.countRg06Sources().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg06Evidence().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg06CandidateStatuses().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun rejectionAndInjectedFailureReserveNothing() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val failed = SqlDelightRg06Store(database, driver, catalog(), "+08:00", Rg06ManualObservationSource { _, _ -> null }, Rg06FailureInjector { if (it == Rg06FailurePoint.AFTER_CLAIM) error("injected") })
            assertFails { failed.commit(createOperation()) }
            assertEquals(0L, database.ledgerQueries.countRg06Operations().executeAsOne())
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            val invalid = createOperation().copy(input = createOperation().input.copy(totalAmount = Money.ofMinor(0, CNY)))
            assertEquals(Rg06ExecutionResult.Rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT), store.commit(invalid))
            assertEquals(0L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
        } finally {
            driver.close()
        }
    }

    @Test
    fun semanticFailurePrecedesSharedFormalCollisionAndCollisionRollsBackClaim() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            store.commit(createOperation())
            driver.execute(null, "INSERT INTO posting_set VALUES ('shared-set','ledger-a')", 0)
            driver.execute(null, "INSERT INTO ledger_transaction VALUES ('shared-transaction','ledger-a','EXPENSE')", 0)
            driver.execute(null, "INSERT INTO transaction_version VALUES ('shared-version','shared-transaction','ledger-a',1,'shared-set','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',NULL)", 0)
            driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('shared-transaction','ledger-a','shared-version')", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('shared-expense','shared-set','ledger-a',0,'expense-service-account',100,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('shared-asset','shared-set','ledger-a',1,'asset-bank',-100,'CNY',2)", 0)
            val collision = record("collision", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).let { operation ->
                operation.copy(ids = operation.ids.copy(paymentIds = operation.ids.paymentIds.copy(expenseIds = operation.ids.paymentIds.expenseIds.copy(transactionId = TransactionId("shared-transaction")))))
            }
            val invalid = collision.copy(input = collision.input.copy(paymentAmount = Money.ofMinor(0, CNY)))
            assertEquals(Rg06ExecutionResult.Rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT), store.commit(invalid))
            assertEquals(Rg06ExecutionResult.Rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY), store.commit(collision))
            assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg06Installments().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun everyLegacyConfirmationOwnerParticipatesInGeneratedIdentityCollisionChecks() {
        listOf("expense", "note-update", "income").forEach { owner ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                seedLegacyConfirmation(driver, owner, "confirmation-deposit")
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))

                assertEquals(
                    Rg06ExecutionResult.Rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY),
                    store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))),
                    owner,
                )
                assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne(), owner)
                assertEquals(0L, database.ledgerQueries.countRg06Installments().executeAsOne(), owner)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun candidateConfirmationRejectsForeignLedgerOwnerAtCandidatePath() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            val foreignIngest = ingest().copy(
                ledgerId = LedgerId("ledger-b"),
                ids = Rg06IngestCommitIds(Rg06CandidateId("foreign-candidate"), Rg06CandidateStatusId("foreign-status-pending")),
            )
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(foreignIngest))
            val confirmation = confirmCandidate().copy(
                input = confirmCandidate().input.copy(candidateId = Rg06CandidateId("foreign-candidate")),
            )

            assertEquals(
                Rg06ExecutionResult.Rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_CANDIDATE_ID),
                store.commit(confirmation),
            )
            assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg06Installments().executeAsOne())
        } finally {
            driver.close()
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
        fun createCase(name: String, total: Long, category: CategoryId?, reason: Rg06RejectionReason, path: Rg06FieldPath) = Case(
            name, Rg06ExecutionResult.Rejected(reason, path), createOnly,
            { createOperation().copy(input = createOperation().input.copy(requestId = RequestId("request-$name"), totalAmount = Money.ofMinor(total, CNY), categoryId = category)) },
            { op -> (op as Rg06Operation.CreateStagedPayment).copy(input = op.input.copy(totalAmount = Money.ofMinor(30_000, CNY), categoryId = CategoryId("expense-service"))) },
        )
        fun depositCase(name: String, amount: Money, account: AccountId = AccountId("asset-bank"), reason: Rg06RejectionReason, path: Rg06FieldPath = Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT) = Case(
            name, Rg06ExecutionResult.Rejected(reason, path), { store -> assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation())) },
            { record("deposit", StagedPaymentRole.DEPOSIT, amount.minorUnits, Instant.parse("2026-04-28T10:00:00+08:00")).copy(input = record("deposit", StagedPaymentRole.DEPOSIT, amount.minorUnits, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(requestId = RequestId("request-$name"), paymentAmount = amount, fundingAccountId = account)) },
            { op -> (op as Rg06Operation.RecordStagedPaymentInstallment).copy(input = op.input.copy(paymentAmount = Money.ofMinor(8_000, CNY), fundingAccountId = AccountId("asset-bank"))) },
        )
        fun finalCase(name: String, amount: Long, reason: Rg06RejectionReason) = Case(
            name, Rg06ExecutionResult.Rejected(reason, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT), createThenDeposit,
            { record("final", StagedPaymentRole.FINAL, amount, Instant.parse("2026-05-03T16:30:00+08:00")).copy(input = record("final", StagedPaymentRole.FINAL, amount, Instant.parse("2026-05-03T16:30:00+08:00")).input.copy(requestId = RequestId("request-$name"), paymentAmount = Money.ofMinor(amount, CNY))) },
            { op -> (op as Rg06Operation.RecordStagedPaymentInstallment).copy(input = op.input.copy(paymentAmount = Money.ofMinor(22_000, CNY))) },
        )
        val cases: List<Case> = listOf(
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
                val corrected: Rg06Operation = if (case.name == "due") {
                    assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))))
                    assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))))
                    val invalidCompletion = invalid as Rg06Operation.ConfirmStagedPaymentCompletion
                    invalidCompletion.copy(input = invalidCompletion.input.copy(confirmed = true))
                } else case.corrected(invalid)
                val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(corrected), case.name)
                assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(corrected), case.name)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun storePortsAllThirtyFourGeneratedIdentityCollisionBranchesAtomically() {
        data class Occurrence(val field: String, val kind: String, val value: String)
        data class Branch(
            val action: Rg06Action,
            val operation: () -> Rg06Operation,
            val prepare: (SqlDelightRg06Store) -> Unit,
            val occurrences: (Rg06Operation) -> List<Occurrence>,
            val corrected: (Rg06Operation, String) -> Rg06Operation,
        )
        val none: (SqlDelightRg06Store) -> Unit = { }
        val created: (SqlDelightRg06Store) -> Unit = { store ->
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
        }
        val recorded: (SqlDelightRg06Store) -> Unit = { store ->
            created(store)
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))))
        }
        val completed: (SqlDelightRg06Store) -> Unit = { store ->
            recorded(store)
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))))
        }
        val ingested: (SqlDelightRg06Store) -> Unit = { store ->
            created(store)
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(ingest()))
        }
        val confirmed: (SqlDelightRg06Store) -> Unit = { store ->
            ingested(store)
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(confirmCandidate()))
        }
        fun paymentOccurrences(ids: StagedPaymentInstallmentIds) = listOf(
            Occurrence("payment", "payment", ids.paymentId.value),
            Occurrence("history", "history", ids.historyId.value),
            Occurrence("transaction", "transaction", ids.expenseIds.transactionId.value),
            Occurrence("version", "version", ids.expenseIds.versionId.value),
            Occurrence("posting_set", "posting_set", ids.expenseIds.postingSetId.value),
            Occurrence("expense_posting", "posting", ids.expenseIds.expensePostingId.value),
            Occurrence("asset_posting", "posting", ids.expenseIds.paymentPostingId.value),
        )
        fun correctedPayment(ids: StagedPaymentInstallmentIds, field: String): StagedPaymentInstallmentIds = when (field) {
            "payment" -> ids.copy(paymentId = InstallmentPaymentId("${ids.paymentId.value}-corrected"))
            "history" -> ids.copy(historyId = StagedPaymentHistoryId("${ids.historyId.value}-corrected"))
            "transaction" -> ids.copy(expenseIds = ids.expenseIds.copy(transactionId = TransactionId("${ids.expenseIds.transactionId.value}-corrected")))
            "version" -> ids.copy(expenseIds = ids.expenseIds.copy(versionId = TransactionVersionId("${ids.expenseIds.versionId.value}-corrected")))
            "posting_set" -> ids.copy(expenseIds = ids.expenseIds.copy(postingSetId = PostingSetId("${ids.expenseIds.postingSetId.value}-corrected")))
            "expense_posting" -> ids.copy(expenseIds = ids.expenseIds.copy(expensePostingId = PostingId("${ids.expenseIds.expensePostingId.value}-corrected")))
            "asset_posting" -> ids.copy(expenseIds = ids.expenseIds.copy(paymentPostingId = PostingId("${ids.expenseIds.paymentPostingId.value}-corrected")))
            else -> ids
        }
        val branches = listOf(
            Branch(Rg06Action.CREATE_STAGED_PAYMENT, { createOperation() }, none, { operation ->
                val op = operation as Rg06Operation.CreateStagedPayment
                listOf(
                    Occurrence("relation", "relation", op.ids.relationId.value),
                    Occurrence("lifecycle", "lifecycle", op.ids.lifecycleId.value),
                    Occurrence("history", "history", op.ids.historyId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.CreateStagedPayment
                op.copy(ids = when (field) {
                    "relation" -> op.ids.copy(relationId = StagedPaymentRelationId("${op.ids.relationId.value}-corrected"))
                    "lifecycle" -> op.ids.copy(lifecycleId = StagedPaymentLifecycleId("${op.ids.lifecycleId.value}-corrected"))
                    else -> op.ids.copy(historyId = StagedPaymentHistoryId("${op.ids.historyId.value}-corrected"))
                })
            }),
            Branch(Rg06Action.RECORD_STAGED_PAYMENT_INSTALLMENT, { record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")) }, created, { operation ->
                val op = operation as Rg06Operation.RecordStagedPaymentInstallment
                listOf(
                    Occurrence("confirmation", "confirmation", op.ids.confirmationId.value),
                    Occurrence("reconciliation", "reconciliation", op.ids.reconciliationId.value),
                ) + paymentOccurrences(op.ids.paymentIds)
            }, { operation, field ->
                val op = operation as Rg06Operation.RecordStagedPaymentInstallment
                op.copy(ids = when (field) {
                    "confirmation" -> op.ids.copy(confirmationId = Rg06ConfirmationId("${op.ids.confirmationId.value}-corrected"))
                    "reconciliation" -> op.ids.copy(reconciliationId = Rg06ReconciliationId("${op.ids.reconciliationId.value}-corrected"))
                    else -> op.ids.copy(paymentIds = correctedPayment(op.ids.paymentIds, field))
                })
            }),
            Branch(Rg06Action.CHANGE_STAGED_PAYMENT_FULFILLMENT, { fulfill() }, recorded, { operation ->
                val op = operation as Rg06Operation.ChangeStagedPaymentFulfillment
                listOf(Occurrence("history", "history", op.historyId.value))
            }, { operation, _ ->
                val op = operation as Rg06Operation.ChangeStagedPaymentFulfillment
                op.copy(historyId = StagedPaymentHistoryId("${op.historyId.value}-corrected"))
            }),
            Branch(Rg06Action.CONFIRM_STAGED_PAYMENT_COMPLETION, { complete() }, completed, { operation ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCompletion
                listOf(Occurrence("history", "history", op.historyId.value))
            }, { operation, _ ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCompletion
                op.copy(historyId = StagedPaymentHistoryId("${op.historyId.value}-corrected"))
            }),
            Branch(Rg06Action.LINK_STAGED_PAYMENT_EVIDENCE, { linkDepositEvidence() }, recorded, { operation ->
                val op = operation as Rg06Operation.LinkStagedPaymentEvidence
                listOf(
                    Occurrence("source", "source", op.input.sourceId.value),
                    Occurrence("evidence", "evidence", op.input.evidenceId.value),
                    Occurrence("link", "link", op.evidenceLinkId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.LinkStagedPaymentEvidence
                when (field) {
                    "source" -> op.copy(input = op.input.copy(sourceId = Rg06SourceId("${op.input.sourceId.value}-corrected")))
                    "evidence" -> op.copy(input = op.input.copy(evidenceId = Rg06EvidenceId("${op.input.evidenceId.value}-corrected")))
                    else -> op.copy(evidenceLinkId = Rg06EvidenceLinkId("${op.evidenceLinkId.value}-corrected"))
                }
            }),
            Branch(Rg06Action.INGEST_STAGED_PAYMENT_BANK_FACT, { ingest() }, none, { operation ->
                val op = operation as Rg06Operation.IngestStagedPaymentBankFact
                listOf(
                    Occurrence("source", "source", op.input.sourceId.value),
                    Occurrence("evidence", "evidence", op.input.evidenceId.value),
                    Occurrence("candidate", "candidate", op.ids.candidateId.value),
                    Occurrence("candidate_status", "candidate_status", op.ids.pendingStatusId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.IngestStagedPaymentBankFact
                when (field) {
                    "source" -> op.copy(input = op.input.copy(sourceId = Rg06SourceId("${op.input.sourceId.value}-corrected")))
                    "evidence" -> op.copy(input = op.input.copy(evidenceId = Rg06EvidenceId("${op.input.evidenceId.value}-corrected")))
                    "candidate" -> op.copy(ids = op.ids.copy(candidateId = Rg06CandidateId("${op.ids.candidateId.value}-corrected")))
                    else -> op.copy(ids = op.ids.copy(pendingStatusId = Rg06CandidateStatusId("${op.ids.pendingStatusId.value}-corrected")))
                }
            }),
            Branch(Rg06Action.CONFIRM_STAGED_PAYMENT_CANDIDATE, { confirmCandidate() }, ingested, { operation ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCandidate
                listOf(
                    Occurrence("confirmation", "confirmation", op.ids.confirmationId.value),
                    Occurrence("link", "link", op.ids.evidenceLinkId.value),
                    Occurrence("candidate_status", "candidate_status", op.ids.confirmedStatusId.value),
                    Occurrence("reconciliation", "reconciliation", op.ids.reconciliationId.value),
                ) + paymentOccurrences(op.ids.paymentIds)
            }, { operation, field ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCandidate
                op.copy(ids = when (field) {
                    "confirmation" -> op.ids.copy(confirmationId = Rg06ConfirmationId("${op.ids.confirmationId.value}-corrected"))
                    "link" -> op.ids.copy(evidenceLinkId = Rg06EvidenceLinkId("${op.ids.evidenceLinkId.value}-corrected"))
                    "candidate_status" -> op.ids.copy(confirmedStatusId = Rg06CandidateStatusId("${op.ids.confirmedStatusId.value}-corrected"))
                    "reconciliation" -> op.ids.copy(reconciliationId = Rg06ReconciliationId("${op.ids.reconciliationId.value}-corrected"))
                    else -> op.ids.copy(paymentIds = correctedPayment(op.ids.paymentIds, field))
                })
            }),
            Branch(Rg06Action.MERGE_STAGED_PAYMENT_MIRROR_EVIDENCE, { mergeDepositMirror() }, confirmed, { operation ->
                val op = operation as Rg06Operation.MergeStagedPaymentMirrorEvidence
                listOf(
                    Occurrence("source", "source", op.input.sourceId.value),
                    Occurrence("evidence", "evidence", op.input.evidenceId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.MergeStagedPaymentMirrorEvidence
                if (field == "source") {
                    op.copy(input = op.input.copy(sourceId = Rg06SourceId("${op.input.sourceId.value}-corrected")))
                } else {
                    op.copy(input = op.input.copy(evidenceId = Rg06EvidenceId("${op.input.evidenceId.value}-corrected")))
                }
            }),
        )
        assertEquals(Rg06Action.entries, branches.map { it.action })
        assertEquals(listOf(3, 9, 1, 1, 3, 4, 11, 2), branches.map { it.occurrences(it.operation()).size })
        val cases = branches.flatMap { branch -> branch.occurrences(branch.operation()).map { branch to it } }
        assertEquals(34, cases.size)

        cases.forEach { (branch, occurrence) ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                branch.prepare(store)
                occupyGeneratedIdentity(database, store, occurrence.kind, occurrence.value)
                val before = storeSnapshot(database)
                val operation = branch.operation()
                assertEquals(
                    Rg06ExecutionResult.Rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY),
                    store.commit(operation),
                    "${branch.action.code}.${occurrence.field}",
                )
                assertEquals(before, storeSnapshot(database), "${branch.action.code}.${occurrence.field}")

                val corrected = branch.corrected(operation, occurrence.field)
                assertNotEquals(operation, corrected, "${branch.action.code}.${occurrence.field}.corrected")
                val accepted = assertIs<Rg06ExecutionResult.Accepted>(
                    store.commit(corrected),
                    "${branch.action.code}.${occurrence.field}.corrected",
                )
                assertEquals(
                    Rg06ExecutionResult.NoChange(accepted.returnedIds),
                    store.commit(corrected),
                    "${branch.action.code}.${occurrence.field}.retry",
                )
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun normalizedSnapshotRehydratesAfterCatalogDriftAndReplaysLifecycleIds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }.apply {
                commit(createOperation())
                commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")))
            }
            val driftedCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    listOf(Account(AccountId("asset-bank"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, true, true)),
                    emptyList(),
                ),
            ).value
            val store = SqlDelightRg06Store(database, driver, driftedCatalog, "+08:00") { _, _ -> null }
            val operation = fulfill()
            val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
            assertEquals(listOf(Rg06ReturnedId.Lifecycle(StagedPaymentLifecycleId("lifecycle"))), accepted.returnedIds)
            assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(operation))
        } finally {
            driver.close()
        }
    }

    @Test
    fun invalidOwnedSnapshotRaisesPersistenceIntegrityFailureAndRollsBackClaim() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            assertIs<Rg06ExecutionResult.Accepted>(
                store.commit(
                    record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")),
                ),
            )

            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-corrupt','ledger-a')", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('posting-corrupt-expense','posting-set-corrupt','ledger-a',0,'expense-service-account',8000,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('posting-corrupt-asset','posting-set-corrupt','ledger-a',1,'asset-bank',-8000,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO transaction_version VALUES ('version-corrupt','transaction-deposit','ledger-a',2,'posting-set-corrupt','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z',NULL)", 0)
            driver.execute(null, "DROP TRIGGER rg06_guard_current_version_update", 0)
            driver.execute(null, "UPDATE ledger_transaction_current_version SET current_version_id = 'version-corrupt' WHERE transaction_id = 'transaction-deposit'", 0)
            val before = storeSnapshot(database)

            val failure = assertFailsWith<Rg06PersistenceIntegrityException> { store.commit(fulfill()) }

            assertTrue(failure.message.orEmpty().contains("invalid persisted RG06 snapshot"))
            assertEquals(before, storeSnapshot(database))
            assertEquals(
                null,
                database.ledgerQueries.selectRg06Operation("ledger-a", fulfill().identity.value).executeAsOneOrNull(),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun malformedOwnedSnapshotIsNotReportedAsMissingOrCrossLedgerRelation() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }

            assertEquals(
                Rg06ExecutionResult.Rejected(Rg06RejectionReason.RELATION_NOT_FOUND, Rg06FieldPath.INPUT_RELATION_ID),
                store.commit(record("missing", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).copy(input = record("missing", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(relationId = StagedPaymentRelationId("relation-missing")))),
            )
            driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-b','relation-cross','staged_payment','{}')", 0)
            assertEquals(
                Rg06ExecutionResult.Rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_RELATION_ID),
                store.commit(record("cross", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).copy(input = record("cross", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(relationId = StagedPaymentRelationId("relation-cross")))),
            )

            driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-a','relation-malformed','staged_payment','{}')", 0)
            val malformed = record("malformed", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).copy(
                input = record("malformed", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(
                    relationId = StagedPaymentRelationId("relation-malformed"),
                ),
            )
            val before = storeSnapshot(database)

            val failure = assertFailsWith<Rg06PersistenceIntegrityException> { store.commit(malformed) }

            assertTrue(failure.message.orEmpty().contains("invalid persisted RG06 snapshot for relation-malformed"))
            assertTrue(failure.message.orEmpty().contains("missing lifecycle"))
            assertEquals(before, storeSnapshot(database))
            assertEquals(
                null,
                database.ledgerQueries.selectRg06Operation("ledger-a", malformed.identity.value).executeAsOneOrNull(),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun concurrentIdenticalCreateCommitsOnceAndReplaysOneReceipt() {
        val path = Files.createTempFile("rg06-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        try {
            JdbcSqliteDriver(url, properties).use { LedgerDatabase.Schema.create(it) }
            val results = mutableListOf<Rg06ExecutionResult>()
            val workers = List(2) {
                thread {
                    JdbcSqliteDriver(url, properties).use { driver ->
                        val result = SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> null }.commit(createOperation())
                        synchronized(results) { results += result }
                    }
                }
            }
            workers.forEach { it.join() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg06ExecutionResult.NoChange })
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Relations().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentChangedInputWithSameIdentityCommitsOneWinnerAndOneConflict() {
        val path = Files.createTempFile("rg06-concurrent-changed-input-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val original = createOperation()
        val changed = original.copy(input = original.input.copy(totalAmount = Money.ofMinor(30_001, CNY)))
        val operations = listOf(original, changed)
        try {
            JdbcSqliteDriver(url, properties).use { LedgerDatabase.Schema.create(it) }
            val results = concurrentCommits(url, properties, operations).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg06ExecutionResult.RequestIdentityConflict })
            val winner = operations[results.indexOfFirst { it is Rg06ExecutionResult.Accepted }]
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                val accepted = results.single { it is Rg06ExecutionResult.Accepted } as Rg06ExecutionResult.Accepted
                assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(winner))
                assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06OperationReceipts().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Relations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Lifecycles().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06History().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctDepositsSerializeLifecycleAndRollBackLoserClaim() {
        val path = Files.createTempFile("rg06-concurrent-lifecycle-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val first = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
        val second = record("deposit-b", StagedPaymentRole.DEPOSIT, 9_000, Instant.parse("2026-04-28T10:00:01+08:00"))
        val operations = listOf(first, second)
        try {
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.create(driver)
                assertIs<Rg06ExecutionResult.Accepted>(
                    SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> null }
                        .commit(createOperation()),
                )
            }
            val results = concurrentCommits(url, properties, operations).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it == Rg06ExecutionResult.Rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
                },
            )
            val winner = operations[results.indexOfFirst { it is Rg06ExecutionResult.Accepted }]
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                val lifecycle = database.ledgerQueries.selectRg06AggregateLifecycle("ledger-a", "relation").executeAsOne()
                assertEquals(winner.input.paymentAmount.minorUnits, lifecycle.paid_minor)
                assertEquals(30_000L - winner.input.paymentAmount.minorUnits, lifecycle.due_minor)
                assertEquals(2L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Installments().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06History().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Reconciliations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctCandidateConfirmationsCommitOneMatchedReconciliation() {
        val path = Files.createTempFile("rg06-concurrent-candidate-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val first = confirmCandidate()
        val second = first.copy(
            input = first.input.copy(requestId = RequestId("request-confirm-b")),
            ids = Rg06CandidateConfirmationCommitIds(
                Rg06ConfirmationId("confirmation-import-b"),
                paymentIds("import-b"),
                Rg06EvidenceLinkId("import-link-b"),
                Rg06CandidateStatusId("status-confirmed-b"),
                Rg06ReconciliationId("reconciliation-import-b"),
            ),
        )
        val operations = listOf(first, second)
        try {
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val store = SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> null }
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(ingest()))
            }
            val results = concurrentCommits(url, properties, operations).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it == Rg06ExecutionResult.Rejected(Rg06RejectionReason.CANDIDATE_NOT_PENDING, Rg06FieldPath.INPUT_CANDIDATE_ID)
                },
            )
            val winner = operations[results.indexOfFirst { it is Rg06ExecutionResult.Accepted }]
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(3L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Installments().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06CandidateStatuses().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Reconciliations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne())
                assertEquals(
                    "MATCHED",
                    database.ledgerQueries.selectRg06ReconciliationForPosting(
                        "ledger-a",
                        winner.ids.paymentIds.expenseIds.paymentPostingId.value,
                    ).executeAsOne().status,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctManualLinksTransitionReconciliationOnceAndRollBackConstraintLoser() {
        val path = Files.createTempFile("rg06-concurrent-reconciliation-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val first = linkDepositEvidence().copy(
            input = linkDepositEvidence().input.copy(sourceId = Rg06SourceId("manual-source-a"), evidenceId = Rg06EvidenceId("manual-evidence-a")),
            evidenceLinkId = Rg06EvidenceLinkId("manual-link-a"),
        )
        val second = linkDepositEvidence().copy(
            input = linkDepositEvidence().input.copy(sourceId = Rg06SourceId("manual-source-b"), evidenceId = Rg06EvidenceId("manual-evidence-b")),
            evidenceLinkId = Rg06EvidenceLinkId("manual-link-b"),
        )
        try {
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val store = SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))))
            }
            val results = concurrentCommits(url, properties, listOf(first, second)).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it == Rg06ExecutionResult.Rejected(
                        Rg06RejectionReason.EVIDENCE_ALREADY_BOUND,
                        Rg06FieldPath.INPUT_POSTING_ID,
                    )
                },
            )
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(3L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Sources().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Evidence().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Reconciliations().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne())
                assertEquals(
                    "MATCHED",
                    database.ledgerQueries.selectRg06ReconciliationForPosting("ledger-a", "posting-deposit-asset").executeAsOne().status,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun createOperation() = Rg06Operation.CreateStagedPayment(
        ledgerId = LedgerId("ledger-a"),
        input = Rg06CreateStagedPaymentInput(
            requestId = RequestId("request-create"),
            totalAmount = Money.ofMinor(30_000, CNY),
            categoryId = CategoryId("expense-service"),
            createdAt = Instant.parse("2026-04-20T09:00:00+08:00"),
        ),
        ids = StagedPaymentCreationIds(
            StagedPaymentRelationId("relation"),
            StagedPaymentLifecycleId("lifecycle"),
            StagedPaymentHistoryId("history-created"),
        ),
    )

    private fun record(label: String, role: StagedPaymentRole, minor: Long, at: Instant) =
        Rg06Operation.RecordStagedPaymentInstallment(
            LedgerId("ledger-a"),
            Rg06RecordStagedPaymentInstallmentInput(RequestId("request-$label"), StagedPaymentRelationId("relation"), role, Money.ofMinor(minor, CNY), AccountId("asset-bank"), at),
            Rg06ManualInstallmentCommitIds(Rg06ConfirmationId("confirmation-$label"), paymentIds(label), Rg06ReconciliationId("reconciliation-$label")),
        )

    private fun fulfill() = Rg06Operation.ChangeStagedPaymentFulfillment(
        LedgerId("ledger-a"), Rg06ChangeStagedPaymentFulfillmentInput(RequestId("request-fulfill"), StagedPaymentRelationId("relation"), StagedPaymentFulfillment.FULFILLED, Instant.parse("2026-04-29T10:00:00+08:00")), StagedPaymentHistoryId("history-fulfilled"),
    )

    private fun complete() = Rg06Operation.ConfirmStagedPaymentCompletion(
        LedgerId("ledger-a"), Rg06ConfirmStagedPaymentCompletionInput(RequestId("request-complete"), StagedPaymentRelationId("relation"), true, Instant.parse("2026-05-04T10:00:00+08:00")), StagedPaymentHistoryId("history-completed"),
    )

    private fun ingest() = Rg06Operation.IngestStagedPaymentBankFact(
        LedgerId("ledger-a"),
        Rg06IngestStagedPaymentBankFactInput(Rg06SourceId("import-source"), Rg06EvidenceId("import-evidence"), Instant.parse("2026-04-28T10:00:00+08:00"), "2026-04-28T10:00:00+08:00", Money.ofMinor(-8_000, CNY), StagedPaymentRole.DEPOSIT),
        Rg06IngestCommitIds(Rg06CandidateId("candidate"), Rg06CandidateStatusId("status-pending")),
    )

    private fun confirmCandidate() = Rg06Operation.ConfirmStagedPaymentCandidate(
        LedgerId("ledger-a"),
        Rg06ConfirmStagedPaymentCandidateInput(RequestId("request-confirm"), Rg06CandidateId("candidate"), StagedPaymentRelationId("relation"), StagedPaymentRole.DEPOSIT, CategoryId("expense-service"), AccountId("asset-bank"), true),
        Rg06CandidateConfirmationCommitIds(Rg06ConfirmationId("confirmation-import"), paymentIds("import"), Rg06EvidenceLinkId("import-link"), Rg06CandidateStatusId("status-confirmed"), Rg06ReconciliationId("reconciliation-import")),
    )

    private fun linkDepositEvidence() = Rg06Operation.LinkStagedPaymentEvidence(
        LedgerId("ledger-a"),
        Rg06LinkStagedPaymentEvidenceInput(
            Rg06SourceId("manual-source"),
            Rg06EvidenceId("manual-evidence"),
            InstallmentPaymentId("payment-deposit"),
            PostingId("posting-deposit-asset"),
        ),
        Rg06EvidenceLinkId("manual-link"),
    )

    private fun mergeDepositMirror() = Rg06Operation.MergeStagedPaymentMirrorEvidence(
        LedgerId("ledger-a"),
        Rg06MergeStagedPaymentMirrorEvidenceInput(
            Rg06SourceId("mirror-source"),
            Rg06EvidenceId("mirror-evidence"),
            InstallmentPaymentId("payment-import"),
            PostingId("posting-import-asset"),
            Money.ofMinor(8_000, CNY),
            Instant.parse("2026-04-28T10:00:00+08:00"),
            "2026-04-28T10:00:00+08:00",
        ),
    )

    private fun occupyGeneratedIdentity(
        database: LedgerDatabase,
        store: SqlDelightRg06Store,
        kind: String,
        value: String,
    ) {
        fun commit(operation: Rg06Operation) {
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation), "occupy.$kind")
        }
        fun createOccupiedAggregate(historyId: StagedPaymentHistoryId = StagedPaymentHistoryId("occupied-history")) {
            commit(
                createOperation().copy(
                    input = createOperation().input.copy(requestId = RequestId("request-occupied-create")),
                    ids = StagedPaymentCreationIds(
                        StagedPaymentRelationId("occupied-relation"),
                        StagedPaymentLifecycleId("occupied-lifecycle"),
                        historyId,
                    ),
                ),
            )
        }
        fun occupiedRecord(ids: Rg06ManualInstallmentCommitIds) {
            val base = record("occupied", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            commit(
                base.copy(
                    input = base.input.copy(
                        requestId = RequestId("request-occupied-record"),
                        relationId = StagedPaymentRelationId("occupied-relation"),
                    ),
                    ids = ids,
                ),
            )
        }

        when (kind) {
            "source" -> database.ledgerQueries.insertRg06Source(
                "ledger-a",
                value,
                "IMPORTED",
                -100,
                "CNY",
                2,
                "2026-04-01T00:00:00Z",
                "2026-04-01T00:00:00Z",
                "SOURCE_PAYMENT_AT",
                null,
            )
            "relation", "lifecycle", "history" -> {
                val ids = StagedPaymentCreationIds(
                    if (kind == "relation") StagedPaymentRelationId(value) else StagedPaymentRelationId("occupied-relation"),
                    if (kind == "lifecycle") StagedPaymentLifecycleId(value) else StagedPaymentLifecycleId("occupied-lifecycle"),
                    if (kind == "history") StagedPaymentHistoryId(value) else StagedPaymentHistoryId("occupied-history"),
                )
                commit(
                    createOperation().copy(
                        input = createOperation().input.copy(requestId = RequestId("request-occupied-create")),
                        ids = ids,
                    ),
                )
            }
            "payment", "transaction", "version", "posting_set", "posting", "confirmation", "reconciliation" -> {
                createOccupiedAggregate()
                var installmentIds = paymentIds("occupied")
                installmentIds = when (kind) {
                    "payment" -> installmentIds.copy(paymentId = InstallmentPaymentId(value))
                    "transaction" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(transactionId = TransactionId(value)))
                    "version" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(versionId = TransactionVersionId(value)))
                    "posting_set" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(postingSetId = PostingSetId(value)))
                    "posting" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(expensePostingId = PostingId(value)))
                    else -> installmentIds
                }
                occupiedRecord(
                    Rg06ManualInstallmentCommitIds(
                        if (kind == "confirmation") Rg06ConfirmationId(value) else Rg06ConfirmationId("occupied-confirmation"),
                        installmentIds,
                        if (kind == "reconciliation") Rg06ReconciliationId(value) else Rg06ReconciliationId("occupied-reconciliation"),
                    ),
                )
            }
            "evidence", "candidate", "candidate_status" -> {
                val base = ingest()
                commit(
                    base.copy(
                        input = base.input.copy(
                            sourceId = Rg06SourceId("occupied-source"),
                            evidenceId = if (kind == "evidence") Rg06EvidenceId(value) else Rg06EvidenceId("occupied-evidence"),
                        ),
                        ids = Rg06IngestCommitIds(
                            if (kind == "candidate") Rg06CandidateId(value) else Rg06CandidateId("occupied-candidate"),
                            if (kind == "candidate_status") Rg06CandidateStatusId(value) else Rg06CandidateStatusId("occupied-status"),
                        ),
                    ),
                )
            }
            "link" -> {
                createOccupiedAggregate()
                val ids = Rg06ManualInstallmentCommitIds(
                    Rg06ConfirmationId("occupied-confirmation"),
                    paymentIds("occupied"),
                    Rg06ReconciliationId("occupied-reconciliation"),
                )
                occupiedRecord(ids)
                commit(
                    Rg06Operation.LinkStagedPaymentEvidence(
                        LedgerId("ledger-a"),
                        Rg06LinkStagedPaymentEvidenceInput(
                            Rg06SourceId("occupied-link-source"),
                            Rg06EvidenceId("occupied-link-evidence"),
                            ids.paymentIds.paymentId,
                            ids.paymentIds.expenseIds.paymentPostingId,
                        ),
                        Rg06EvidenceLinkId(value),
                    ),
                )
            }
            else -> error("unsupported generated identity kind: $kind")
        }
    }

    private fun concurrentCommits(
        url: String,
        properties: Properties,
        operations: List<Rg06Operation>,
    ): List<Result<Rg06ExecutionResult>> {
        val start = CountDownLatch(1)
        val results = MutableList<Result<Rg06ExecutionResult>?>(operations.size) { null }
        val workers = operations.mapIndexed { index, operation ->
            thread {
                start.await()
                val result = runCatching {
                    JdbcSqliteDriver(url, properties).use { driver ->
                        SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                            .commit(operation)
                    }
                }
                synchronized(results) { results[index] = result }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        return results.map { checkNotNull(it) }
    }

    private fun paymentIds(label: String) = StagedPaymentInstallmentIds(
        InstallmentPaymentId("payment-$label"), StagedPaymentHistoryId("history-$label"),
        AssetPaidOrdinaryExpenseIds(TransactionId("transaction-$label"), TransactionVersionId("version-$label"), PostingSetId("posting-set-$label"), PostingId("posting-$label-expense"), PostingId("posting-$label-asset")),
    )

    private fun manualObservation(): Rg06ManualBankObservation {
        val observed = assertIs<Rg06TypedValueResult.Success<Rg06ObservedAt>>(
            Rg06ObservedAt.create(Instant.parse("2026-04-28T10:01:00+08:00"), "2026-04-28T10:01:00+08:00", "+08:00"),
        ).value
        return Rg06ManualBankObservation(Money.ofMinor(-8_000, CNY), observed)
    }

    private fun seedLegacyConfirmation(driver: JdbcSqliteDriver, owner: String, confirmationId: String) {
        driver.execute(null, "INSERT INTO ledger_transaction VALUES ('legacy-tx','ledger-a','EXPENSE')", 0)
        when (owner) {
            "expense" -> {
                driver.execute(null, "INSERT INTO manual_expense_request VALUES ('ledger-a','legacy-request',100,'CNY',2,'expense-service','asset-bank','2026-01-01T00:00:00Z','','explicit_manual_save')", 0)
                driver.execute(null, "INSERT INTO confirmed_expense_receipt VALUES ('ledger-a','legacy-request','$confirmationId','legacy-tx')", 0)
            }
            "note-update" -> {
                driver.execute(null, "INSERT INTO posting_set VALUES ('legacy-set','ledger-a')", 0)
                driver.execute(null, "INSERT INTO transaction_version VALUES ('legacy-version','legacy-tx','ledger-a',1,'legacy-set','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',NULL)", 0)
                driver.execute(null, "INSERT INTO transaction_note_update_request VALUES ('ledger-a','legacy-request','legacy-tx','','explicit_manual_save')", 0)
                driver.execute(null, "INSERT INTO confirmed_transaction_note_update_receipt VALUES ('ledger-a','legacy-request','$confirmationId','legacy-tx','legacy-version','legacy-version')", 0)
            }
            "income" -> {
                driver.execute(null, "INSERT INTO manual_income_request VALUES ('ledger-a','legacy-request',100,'CNY',2,'income-category','asset-bank','2026-01-01T00:00:00Z','','explicit_manual_save')", 0)
                driver.execute(null, "INSERT INTO confirmed_income_receipt VALUES ('ledger-a','legacy-request','$confirmationId','legacy-tx')", 0)
            }
            else -> error("unknown owner")
        }
    }

    private fun assertRetryAndConflict(store: SqlDelightRg06Store, operation: Rg06Operation, changed: Rg06Operation) {
        val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
        assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(operation))
        assertIs<Rg06ExecutionResult.RequestIdentityConflict>(store.commit(changed))
    }

    private fun catalog(): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(
        LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("asset-bank"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, true, true),
                Account(AccountId("expense-service-account"), LedgerId("ledger-a"), AccountKind.EXPENSE, CNY, false, false),
                Account(AccountId("income-account"), LedgerId("ledger-a"), AccountKind.INCOME, CNY, false, false),
                Account(AccountId("asset-nonfinancial"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, true, false),
                Account(AccountId("asset-external"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, false, true),
                Account(AccountId("liability"), LedgerId("ledger-a"), AccountKind.LIABILITY, CNY, true, true),
            ),
            categories = listOf(
                Category(CategoryId("expense-root"), LedgerId("ledger-a"), null, null, true),
                Category(CategoryId("expense-service"), LedgerId("ledger-a"), CategoryId("expense-root"), AccountId("expense-service-account"), true),
                Category(CategoryId("expense-inactive"), LedgerId("ledger-a"), CategoryId("expense-root"), AccountId("expense-service-account"), false),
                Category(CategoryId("income-root"), LedgerId("ledger-a"), null, null, true, CategoryKind.INCOME),
                Category(CategoryId("income-child"), LedgerId("ledger-a"), CategoryId("income-root"), AccountId("income-account"), true, CategoryKind.INCOME),
            ),
        ),
    ).value

    private companion object {
        val CNY = CurrencyUnit("CNY", 2)
        val USD = CurrencyUnit("USD", 2)
    }
}

private data class Rg06StoreSnapshot(
    val operations: Long,
    val receipts: Long,
    val relations: Long,
    val relationMembers: Long,
    val lifecycles: Long,
    val history: Long,
    val installments: Long,
    val postingSemantics: Long,
    val sources: Long,
    val evidence: Long,
    val candidates: Long,
    val requirements: Long,
    val candidateStatuses: Long,
    val confirmations: Long,
    val evidenceLinks: Long,
    val reconciliations: Long,
    val reconciliationHistory: Long,
    val transactions: Long,
    val versions: Long,
    val postingSets: Long,
    val postings: Long,
)

private fun storeSnapshot(database: LedgerDatabase) = Rg06StoreSnapshot(
    operations = database.ledgerQueries.countRg06Operations().executeAsOne(),
    receipts = database.ledgerQueries.countRg06OperationReceipts().executeAsOne(),
    relations = database.ledgerQueries.countRg06Relations().executeAsOne(),
    relationMembers = database.ledgerQueries.countRg06RelationMembers().executeAsOne(),
    lifecycles = database.ledgerQueries.countRg06Lifecycles().executeAsOne(),
    history = database.ledgerQueries.countRg06History().executeAsOne(),
    installments = database.ledgerQueries.countRg06Installments().executeAsOne(),
    postingSemantics = database.ledgerQueries.countRg06PostingSemantics().executeAsOne(),
    sources = database.ledgerQueries.countRg06Sources().executeAsOne(),
    evidence = database.ledgerQueries.countRg06Evidence().executeAsOne(),
    candidates = database.ledgerQueries.countRg06Candidates().executeAsOne(),
    requirements = database.ledgerQueries.countRg06CandidateRequirements().executeAsOne(),
    candidateStatuses = database.ledgerQueries.countRg06CandidateStatuses().executeAsOne(),
    confirmations = database.ledgerQueries.countRg06Confirmations().executeAsOne(),
    evidenceLinks = database.ledgerQueries.countRg06EvidenceLinks().executeAsOne(),
    reconciliations = database.ledgerQueries.countRg06Reconciliations().executeAsOne(),
    reconciliationHistory = database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne(),
    transactions = database.ledgerQueries.countTransactions().executeAsOne(),
    versions = database.ledgerQueries.countVersions().executeAsOne(),
    postingSets = database.ledgerQueries.countPostingSets().executeAsOne(),
    postings = database.ledgerQueries.countPostings().executeAsOne(),
)
