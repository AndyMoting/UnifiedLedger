package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.Rg06Action
import com.unifiedledger.application.Rg06CandidateId
import com.unifiedledger.application.Rg06CandidateStatusId
import com.unifiedledger.application.Rg06ConfirmationId
import com.unifiedledger.application.Rg06EvidenceId
import com.unifiedledger.application.Rg06EvidenceLinkId
import com.unifiedledger.application.Rg06ExecutionResult
import com.unifiedledger.application.Rg06FieldPath
import com.unifiedledger.application.Rg06Operation
import com.unifiedledger.application.Rg06ReconciliationId
import com.unifiedledger.application.Rg06RejectionReason
import com.unifiedledger.application.Rg06SourceId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.InstallmentPaymentId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.StagedPaymentHistoryId
import com.unifiedledger.domain.StagedPaymentInstallmentIds
import com.unifiedledger.domain.StagedPaymentLifecycleId
import com.unifiedledger.domain.StagedPaymentRelationId
import com.unifiedledger.domain.StagedPaymentRole
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class SqlDelightRg06StorePortFanOutTest : Rg06StoreTestSupport() {
    @Test
    fun storePortsAllThirtyFourGeneratedIdentityCollisionBranchesAtomically() {
        data class Occurrence(
            val field: String,
            val kind: String,
            val value: String,
        )

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

        fun paymentOccurrences(ids: StagedPaymentInstallmentIds) =
            listOf(
                Occurrence("payment", "payment", ids.paymentId.value),
                Occurrence("history", "history", ids.historyId.value),
                Occurrence("transaction", "transaction", ids.expenseIds.transactionId.value),
                Occurrence("version", "version", ids.expenseIds.versionId.value),
                Occurrence("posting_set", "posting_set", ids.expenseIds.postingSetId.value),
                Occurrence("expense_posting", "posting", ids.expenseIds.expensePostingId.value),
                Occurrence("asset_posting", "posting", ids.expenseIds.paymentPostingId.value),
            )

        fun correctedPayment(
            ids: StagedPaymentInstallmentIds,
            field: String,
        ): StagedPaymentInstallmentIds =
            when (field) {
                "payment" -> ids.copy(paymentId = InstallmentPaymentId("${ids.paymentId.value}-corrected"))
                "history" -> ids.copy(historyId = StagedPaymentHistoryId("${ids.historyId.value}-corrected"))
                "transaction" -> ids.copy(expenseIds = ids.expenseIds.copy(transactionId = TransactionId("${ids.expenseIds.transactionId.value}-corrected")))
                "version" -> ids.copy(expenseIds = ids.expenseIds.copy(versionId = TransactionVersionId("${ids.expenseIds.versionId.value}-corrected")))
                "posting_set" -> ids.copy(expenseIds = ids.expenseIds.copy(postingSetId = PostingSetId("${ids.expenseIds.postingSetId.value}-corrected")))
                "expense_posting" -> ids.copy(expenseIds = ids.expenseIds.copy(expensePostingId = PostingId("${ids.expenseIds.expensePostingId.value}-corrected")))
                "asset_posting" -> ids.copy(expenseIds = ids.expenseIds.copy(paymentPostingId = PostingId("${ids.expenseIds.paymentPostingId.value}-corrected")))
                else -> ids
            }
        val branches =
            listOf(
                Branch(Rg06Action.CREATE_STAGED_PAYMENT, { createOperation() }, none, { operation ->
                    val op = operation as Rg06Operation.CreateStagedPayment
                    listOf(
                        Occurrence("relation", "relation", op.ids.relationId.value),
                        Occurrence("lifecycle", "lifecycle", op.ids.lifecycleId.value),
                        Occurrence("history", "history", op.ids.historyId.value),
                    )
                }, { operation, field ->
                    val op = operation as Rg06Operation.CreateStagedPayment
                    op.copy(
                        ids =
                            when (field) {
                                "relation" -> op.ids.copy(relationId = StagedPaymentRelationId("${op.ids.relationId.value}-corrected"))
                                "lifecycle" -> op.ids.copy(lifecycleId = StagedPaymentLifecycleId("${op.ids.lifecycleId.value}-corrected"))
                                else -> op.ids.copy(historyId = StagedPaymentHistoryId("${op.ids.historyId.value}-corrected"))
                            },
                    )
                }),
                Branch(Rg06Action.RECORD_STAGED_PAYMENT_INSTALLMENT, { record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")) }, created, { operation ->
                    val op = operation as Rg06Operation.RecordStagedPaymentInstallment
                    listOf(
                        Occurrence("confirmation", "confirmation", op.ids.confirmationId.value),
                        Occurrence("reconciliation", "reconciliation", op.ids.reconciliationId.value),
                    ) + paymentOccurrences(op.ids.paymentIds)
                }, { operation, field ->
                    val op = operation as Rg06Operation.RecordStagedPaymentInstallment
                    op.copy(
                        ids =
                            when (field) {
                                "confirmation" -> op.ids.copy(confirmationId = Rg06ConfirmationId("${op.ids.confirmationId.value}-corrected"))
                                "reconciliation" -> op.ids.copy(reconciliationId = Rg06ReconciliationId("${op.ids.reconciliationId.value}-corrected"))
                                else -> op.ids.copy(paymentIds = correctedPayment(op.ids.paymentIds, field))
                            },
                    )
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
                    op.copy(
                        ids =
                            when (field) {
                                "confirmation" -> op.ids.copy(confirmationId = Rg06ConfirmationId("${op.ids.confirmationId.value}-corrected"))
                                "link" -> op.ids.copy(evidenceLinkId = Rg06EvidenceLinkId("${op.ids.evidenceLinkId.value}-corrected"))
                                "candidate_status" -> op.ids.copy(confirmedStatusId = Rg06CandidateStatusId("${op.ids.confirmedStatusId.value}-corrected"))
                                "reconciliation" -> op.ids.copy(reconciliationId = Rg06ReconciliationId("${op.ids.reconciliationId.value}-corrected"))
                                else -> op.ids.copy(paymentIds = correctedPayment(op.ids.paymentIds, field))
                            },
                    )
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
                val accepted =
                    assertIs<Rg06ExecutionResult.Accepted>(
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
}
