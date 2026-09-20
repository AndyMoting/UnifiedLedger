package com.unifiedledger.data

import com.unifiedledger.application.CatalogAdmissionReader
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.QueryRecycleBin
import com.unifiedledger.application.RecycleBinResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05.C void/restore commit and recycle-bin evidence (spec sections 3.3/3.4/4.3/4.4;
 * acceptance vectors V-02, V-03, V-10, V-11, V-12, V-13, V-14, V-15, V-21 and V-22).
 */
class P705VoidRestoreCommitPortTest {
    private val ledgerId = P705Fixture.ledgerId
    private val catalog = P705Fixture.catalog()

    private fun void(
        harness: P705Database,
        ids: P705Ids,
        requestId: RequestId,
        transactionId: String = "tx-expense-100",
        reason: VoidReason? = VoidReason(VoidReasonCode.MIS_ENTERED, "wrong amount"),
    ): VoidTransactionResult =
        ExecuteVoidTransaction(harness.voidPort, ids.voidSource, fixedClock(P705Fixture.voidedAt))
            .execute(
                VoidTransactionRequest(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    transactionId = TransactionId(transactionId),
                    reason = reason,
                    confirmation = ExplicitManualSave,
                ),
            )

    private fun restore(
        harness: P705Database,
        ids: P705Ids,
        requestId: RequestId,
        transactionId: String = "tx-expense-100",
        reason: VoidReason? = VoidReason(VoidReasonCode.VOIDED_IN_ERROR, "restore"),
        admissionReader: CatalogAdmissionReader = P705Fixture.admissionReader,
    ): VoidTransactionResult =
        ExecuteRestoreTransaction(
            harness.voidPort,
            ids.voidSource,
            fixedClock(Instant.parse("2026-05-01T01:00:00Z")),
            admissionReader,
        ).execute(
            VoidTransactionRequest(
                ledgerId = ledgerId,
                requestId = requestId,
                transactionId = TransactionId(transactionId),
                reason = reason,
                confirmation = ExplicitManualSave,
            ),
        )

    private fun recycleBin(harness: P705Database): List<com.unifiedledger.application.RecycleBinRow> = assertIs<RecycleBinResult.Success>(QueryRecycleBin(harness.readAdapter, ledgerId, catalog).query()).rows

    @Test
    fun voidKeepsEveryHistoricalRowAndTheCreationLineageReadable() {
        P705Database.create("p705-void-history-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            harness.insertManualExpenseCreationReceipt("tx-expense-100")
            val ids = P705Ids("p705-void-history")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId()))

            // V-02: the version, its posting set and its postings are all still there, byte for byte.
            assertEquals(1L, harness.versionCount("tx-expense-100"))
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM transaction_version WHERE version_id = 'tx-expense-100-version-1' " +
                        "AND posting_set_id = 'tx-expense-100-posting-set-1' AND note = 'lunch'",
                ),
            )
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
            assertEquals(
                listOf(10_000L, -10_000L),
                harness.ledgerQueryLongs(
                    "SELECT amount_minor FROM posting WHERE posting_set_id = 'tx-expense-100-posting-set-1' ORDER BY posting_index",
                ),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM confirmed_expense_receipt WHERE transaction_id = 'tx-expense-100'"),
            )
            // The creation entry stays readable through the recycle-bin path (V-21 dependency material).
            val row = recycleBin(harness).single()
            assertEquals(CreationEntry.MANUAL_CREATED, row.creationEntry)
            assertEquals(listOf(P705Fixture.expenseAccount, P705Fixture.bankA), row.dependencies.map { it.accountId })
            assertEquals(listOf("", "银行A"), row.dependencies.map { it.accountName })
            assertEquals(listOf(true, true), row.dependencies.map { it.accountActive })
            assertEquals(listOf("餐饮-午餐", null), row.dependencies.map { it.categoryName })
            assertEquals(listOf(true, null), row.dependencies.map { it.categoryActive })
            assertEquals(false, row.hasEffectiveRefundLink)
        }
    }

    @Test
    fun restoreHappensExactlyOnceAndASecondRestoreIsATypedRejection() {
        P705Database.create("p705-restore-once-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-restore-once")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId()))
            val restored = assertIs<VoidTransactionResult.Created>(restore(harness, ids, ids.requestId()))

            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(
                listOf("void", "restore"),
                harness.database.ledgerQueries
                    .transactionVoidFactsForTransaction(ledgerId.value, "tx-expense-100") { _, factKind, _, _, _, _, _ ->
                        factKind
                    }.executeAsList(),
            )
            // A second restore with a fresh request id is a typed rejection with zero writes.
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_TRANSACTION_NOT_VOIDED),
                restore(harness, ids, ids.requestId()),
            )
            // A further void exhausts the slice's single void/restore cycle (DP-8).
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_VOID_CYCLE_EXHAUSTED),
                void(harness, ids, ids.requestId()),
            )
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            // The merged void/restore family stores one request/receipt pair per fact.
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_request"))
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_receipt"))
            assertEquals(TransactionVoidFactKind.RESTORE, restored.receipt.factKind)
        }
    }

    @Test
    fun voidingAVoidedTransactionIsATypedRejectionAndRestoringAnEffectiveOneToo() {
        P705Database.create("p705-void-preconditions-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-void-preconditions")
            // Restoring a never-voided transaction is rejected (no state change).
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_TRANSACTION_NOT_VOIDED),
                restore(harness, ids, ids.requestId()),
            )
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId()))
            // Voiding it again is rejected (DP-8: restore first).
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_TRANSACTION_VOIDED),
                void(harness, ids, ids.requestId()),
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            // A missing reason and an over-long note are the same typed rejection.
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_VOID_REASON_REQUIRED),
                void(harness, ids, ids.requestId(), transactionId = "tx-expense-100", reason = null),
            )
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_VOID_REASON_REQUIRED),
                void(
                    harness,
                    ids,
                    ids.requestId(),
                    reason = VoidReason(VoidReasonCode.OTHER, "x".repeat(com.unifiedledger.domain.VOID_REASON_NOTE_MAX_LENGTH + 1)),
                ),
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_request"))
        }
    }

    @Test
    fun unsupportedKindsAndLineagesAreTypedRejectionsWithZeroWrites() {
        P705Database.create("p705-void-unsupported-").use { harness ->
            harness.insertTransactionOfKind("tx-transfer", com.unifiedledger.domain.TransactionKind.ACCOUNT_TRANSFER)
            harness.insertOrdinaryExpense("tx-imported", amountMinor = 10_000L)
            harness.insertImportCreationConfirmation("tx-imported")
            val ids = P705Ids("p705-void-unsupported")
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_KIND_NOT_SUPPORTED),
                void(harness, ids, ids.requestId(), transactionId = "tx-transfer"),
            )
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_CREATION_LINEAGE_NOT_SUPPORTED),
                void(harness, ids, ids.requestId(), transactionId = "tx-imported"),
            )
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_TRANSACTION_NOT_FOUND),
                void(harness, ids, ids.requestId(), transactionId = "tx-missing"),
            )
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_request"))
            // The import candidate keeps its terminal status: the void never touched it.
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM import_candidate_status_history WHERE status = 'confirmed' AND candidate_id = 'candidate-p705-imported'",
                ),
            )
        }
    }

    @Test
    fun aLinkedRefundMakesTheVoidATypedRejectionAndChangesNothingElse() {
        P705Database.create("p705-refund-linked-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            harness.insertOrdinaryExpense("tx-refund-30", amountMinor = 3_000L)
            harness.insertLinkedRefund(
                originalTransactionId = "tx-expense-100",
                refundTransactionId = "tx-refund-30",
            )
            val ids = P705Ids("p705-refund-linked")
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED),
                void(harness, ids, ids.requestId()),
            )
            // The target stays effective and the refund side is value-identical.
            assertEquals(2, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM rg07_refund_relationship WHERE original_transaction_id = 'tx-expense-100' AND refund_transaction_id = 'tx-refund-30'",
                ),
            )
            // The recycle bin reports the dependency instead of hiding it.
            assertEquals(0, recycleBin(harness).size)
        }
    }

    @Test
    fun restoreRevalidatesTheCurrentCatalogAndKeepsTheTransactionVoidedOnRejection() {
        P705Database.create("p705-restore-revalidate-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-restore-revalidate")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId()))

            // A deactivated funding account blocks the restore; the transaction stays voided.
            val deactivated =
                catalogWith(
                    accounts = catalog.accounts.map { if (it.id == P705Fixture.bankA) it.copy(active = false) else it },
                )
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE),
                restore(harness, ids, ids.requestId(), admissionReader = CatalogAdmissionReader { deactivated }),
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(0, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            // A deactivated category blocks it too.
            val deactivatedCategory =
                catalogWith(
                    categories = catalog.categories.map { if (it.id == P705Fixture.food) it.copy(active = false) else it },
                )
            assertEquals(
                VoidTransactionResult.Rejected(P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE),
                restore(harness, ids, ids.requestId(), admissionReader = CatalogAdmissionReader { deactivatedCategory }),
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            // A rename that keeps the references active is admissible (D-156 DP-9 clarification).
            val renamed =
                catalogWith(
                    accounts = catalog.accounts.map { if (it.id == P705Fixture.bankA) it.copy(name = "银行A（新名）") else it },
                    categories = catalog.categories.map { if (it.id == P705Fixture.food) it.copy(name = "餐饮-新名") else it },
                )
            assertIs<VoidTransactionResult.Created>(
                restore(harness, ids, ids.requestId(), admissionReader = CatalogAdmissionReader { renamed }),
            )
            assertEquals(1, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            // The recycle-bin dependency material reports the admissibility before a restore.
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
        }
    }

    @Test
    fun recycleBinShowsTheFrozenReasonAndStableVoidTimeOrdering() {
        P705Database.create("p705-recycle-order-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-a", amountMinor = 1_000L)
            harness.insertOrdinaryExpense("tx-expense-b", amountMinor = 2_000L)
            harness.insertOrdinaryExpense("tx-expense-c", amountMinor = 3_000L)
            val ids = P705Ids("p705-recycle-order")
            // Same instant for a and b: the frozen order breaks the tie by transaction_id ASC.
            assertIs<VoidTransactionResult.Created>(
                void(harness, ids, ids.requestId(), transactionId = "tx-expense-b", reason = VoidReason(VoidReasonCode.DUPLICATE_ENTRY, "duplicate")),
            )
            assertIs<VoidTransactionResult.Created>(
                void(harness, ids, ids.requestId(), transactionId = "tx-expense-a", reason = VoidReason(VoidReasonCode.MIS_ENTERED, null)),
            )
            val later =
                ExecuteVoidTransaction(
                    harness.voidPort,
                    ids.voidSource,
                    fixedClock(Instant.parse("2026-05-02T00:00:00Z")),
                ).execute(
                    VoidTransactionRequest(
                        ledgerId = ledgerId,
                        requestId = ids.requestId(),
                        transactionId = TransactionId("tx-expense-c"),
                        reason = VoidReason(VoidReasonCode.NO_LONGER_APPLICABLE, "no longer needed"),
                        confirmation = ExplicitManualSave,
                    ),
                )
            assertIs<VoidTransactionResult.Created>(later)

            val rows = recycleBin(harness)
            assertEquals(listOf("tx-expense-c", "tx-expense-a", "tx-expense-b"), rows.map { it.voided.transactionId.value })
            assertEquals(
                listOf(Instant.parse("2026-05-02T00:00:00Z"), P705Fixture.voidedAt, P705Fixture.voidedAt),
                rows.map { it.voided.voidedAt },
            )
            assertEquals(
                listOf(
                    VoidReason(VoidReasonCode.NO_LONGER_APPLICABLE, "no longer needed"),
                    VoidReason(VoidReasonCode.MIS_ENTERED, null),
                    VoidReason(VoidReasonCode.DUPLICATE_ENTRY, "duplicate"),
                ),
                rows.map { it.voided.voidReason },
            )
            assertEquals(3L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
        }
    }

    @Test
    fun equivalentVoidReplayReturnsTheOriginalReceiptAndChangedReplayConflicts() {
        P705Database.create("p705-void-replay-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-void-replay")
            val requestId = ids.requestId()
            val created = assertIs<VoidTransactionResult.Created>(void(harness, ids, requestId))

            assertEquals(
                VoidTransactionResult.NoChange(created.receipt),
                void(harness, ids, requestId),
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals(
                VoidTransactionResult.RequestIdentityConflict(
                    TransactionVoidRequestIdentity(ledgerId, requestId),
                ),
                void(harness, ids, requestId, reason = VoidReason(VoidReasonCode.OTHER, "changed")),
            )
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
        }
    }

    @Test
    fun databaseGuardsRejectSequenceGapsNonAlternatingFactsAndHistoryRewrites() {
        P705Database.create("p705-guards-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-guards")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId()))

            // V-15: a sequence gap / a second void / a restore-then-void sequence all abort.
            assertFailsWith<SQLException> {
                harness.insertRawVoidFact(transactionId = "tx-expense-100", sequence = 3L, factKind = "restore")
            }
            assertFailsWith<SQLException> {
                harness.insertRawVoidFact(transactionId = "tx-expense-100", sequence = 2L, factKind = "void")
            }
            assertFailsWith<SQLException> {
                harness.insertRawVoidFact(transactionId = "tx-expense-100", sequence = 2L, factKind = "restore", reuseExistingRequest = true)
            }
            // Append-only: update and delete of a fact row abort.
            assertFailsWith<SQLException> {
                harness.driverExecute("UPDATE transaction_void_fact SET reason_note = 'rewritten' WHERE transaction_id = 'tx-expense-100'")
            }
            assertFailsWith<SQLException> {
                harness.driverExecute("DELETE FROM transaction_void_fact WHERE transaction_id = 'tx-expense-100'")
            }
            // One request yields at most one fact.
            assertFailsWith<SQLException> {
                harness.driverExecute(
                    "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                        "SELECT ledger_id, transaction_id, 2, 'raw-fact-2', 'restore', reason_code, reason_note, request_id, 'raw-confirmation', created_at FROM transaction_void_fact WHERE transaction_id = 'tx-expense-100'",
                )
            }
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_void_fact"))
            assertEquals("wrong amount", harness.ledgerQueryText("SELECT reason_note FROM transaction_void_fact LIMIT 1"))
        }
    }

    @Test
    fun frozenIndexesAndUniqueKeysExistWithTheSpecifiedShape() {
        P705Database.create("p705-indexes-").use { harness ->
            // V-15: the recycle-bin ordering index and the two fact-table unique keys exist.
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'transaction_void_fact_recycle_bin_idx'",
                ),
            )
            assertEquals(
                "CREATE INDEX transaction_void_fact_recycle_bin_idx ON transaction_void_fact(ledger_id, created_at DESC, transaction_id)",
                harness.ledgerQueryText("SELECT sql FROM sqlite_master WHERE name = 'transaction_void_fact_recycle_bin_idx'"),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'sqlite_autoindex_transaction_void_fact_1'"),
            )
            val indexes =
                harness.ledgerQueryText(
                    "SELECT group_concat(name) FROM sqlite_master WHERE type = 'index' AND tbl_name = 'transaction_void_fact'",
                )
            assertTrue(indexes.contains("sqlite_autoindex_transaction_void_fact_1"))
            assertTrue(indexes.contains("sqlite_autoindex_transaction_void_fact_2"))
            assertTrue(indexes.contains("transaction_void_fact_recycle_bin_idx"))
            // The PK prefix (ledger_id, transaction_id) serves the latest-fact lookup: with two
            // facts the latest one is still selected deterministically.
            harness.insertOrdinaryExpense("tx-two-facts", amountMinor = 1_000L)
            val ids = P705Ids("p705-indexes")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), transactionId = "tx-two-facts"))
            assertIs<VoidTransactionResult.Created>(restore(harness, ids, ids.requestId(), transactionId = "tx-two-facts"))
            assertEquals(
                listOf(1L, 2L),
                harness.ledgerQueryLongs("SELECT sequence FROM transaction_void_fact WHERE transaction_id = 'tx-two-facts' ORDER BY sequence"),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount(
                    "SELECT count(*) FROM transaction_effective_state WHERE transaction_id = 'tx-two-facts' AND is_effective = 1",
                ),
            )
        }
    }

    @Test
    fun voidAndRestoreNeverWriteTheReconciliationEvidenceLendingOrImportOwners() {
        P705Database.create("p705-owners-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val owners =
                listOf(
                    "posting_reconciliation",
                    "posting_reconciliation_history",
                    "evidence_link",
                    "evidence_link_history",
                    "evidence_projection",
                    "reconciliation_correction_snapshot",
                    "lending_position",
                    "lending_position_history",
                    "import_confirmation",
                    "import_receipt",
                    "import_candidate_status_history",
                ).associateWith { table -> harness.ledgerQueryCount("SELECT count(*) FROM $table") }
            assertEquals(0L, owners.values.sum())

            val ids = P705Ids("p705-owners")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId()))
            assertIs<VoidTransactionResult.Created>(restore(harness, ids, ids.requestId()))
            assertEquals(
                owners,
                owners.keys.associateWith { table -> harness.ledgerQueryCount("SELECT count(*) FROM $table") },
            )
        }
    }

    /** V-21: the recycle bin must not require (or expose) any log channel for the reason. */
    @Test
    fun recycleBinRowsCarryTheReasonWithoutASecondRepresentation() {
        P705Database.create("p705-reason-").use { harness ->
            harness.insertOrdinaryExpense("tx-expense-100", amountMinor = 10_000L)
            val ids = P705Ids("p705-reason")
            val reason = VoidReason(VoidReasonCode.NO_LONGER_APPLICABLE, "the trip was cancelled")
            assertIs<VoidTransactionResult.Created>(void(harness, ids, ids.requestId(), reason = reason))
            val row = recycleBin(harness).single()
            assertEquals(reason, row.voided.voidReason)
            // V-22: storage form and domain form are value-identical, with no third representation.
            assertEquals(
                reason.code.storageValue,
                harness.ledgerQueryText("SELECT reason_code FROM transaction_void_fact LIMIT 1"),
            )
            assertEquals(
                reason.note,
                harness.ledgerQueryText("SELECT reason_note FROM transaction_void_fact LIMIT 1"),
            )
            assertEquals(
                "void",
                harness.ledgerQueryText("SELECT fact_kind FROM transaction_void_fact LIMIT 1"),
            )
            // The request row carries the same two values, so the identity snapshot round-trips.
            assertEquals(
                reason.code.storageValue,
                harness.ledgerQueryText("SELECT reason_code FROM transaction_void_request LIMIT 1"),
            )
            assertEquals(
                reason.note,
                harness.ledgerQueryText("SELECT reason_note FROM transaction_void_request LIMIT 1"),
            )
        }
    }

    private fun catalogWith(
        accounts: List<com.unifiedledger.domain.Account> = catalog.accounts,
        categories: List<com.unifiedledger.domain.Category> = catalog.categories,
    ): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(accounts, categories)).value
}
