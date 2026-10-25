package com.unifiedledger.data

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionIdSource
import com.unifiedledger.application.CorrectTransactionVersionIds
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P7-05 transfer-slice correction commit evidence (transfer spec sections 3/8.3; D-228 J1, and
 * the slice-3 DP-10 narrowing + D-113 composition of D-228 J3): real claim-first port commits
 * of manual-transfer corrections across the frozen field set - note, destination credit, both
 * endpoints and the fee (both leg-count directions) - plus the CAS/replay family and the
 * voided-target rejection. Every vector seeds a real manual transfer chain (transaction,
 * version 1, posting set, postings, current-version pointer) in the frozen creation leg order.
 *
 * The DP-10/J3 matrix vectors seed the reconciliation and evidence rows the product path never
 * writes (manual transfers carry none, J3 evidence facts 2/3): a PENDING placeholder row
 * (evidence_link_id NULL), and an active evidence link whose invalidation the narrowed gate
 * must compose inside the correction transaction. Transfer restore end-to-end lives in
 * `P705TransferRestorePathTest`; values are anonymous synthetic.
 */
class P705TransferCorrectionCommitPortTest {
    private val ledgerId = P705Fixture.ledgerId

    /** Self-sufficient id source: mints the transfer slice's third (FEE-leg) posting id too. */
    private class TransferIds(
        private val prefix: String,
    ) {
        private var counter = 0

        private fun next(): String = "$prefix-${++counter}"

        fun requestId(): RequestId = RequestId(next())

        val correctSource: CorrectTransactionVersionIdSource =
            CorrectTransactionVersionIdSource {
                CorrectTransactionVersionIds(
                    confirmationId = ConfirmationId(next()),
                    versionId = TransactionVersionId(next()),
                    postingSetId = PostingSetId(next()),
                    categoryPostingId = PostingId(next()),
                    fundingPostingId = PostingId(next()),
                    feePostingId = PostingId(next()),
                )
            }
    }

    /**
     * Seeds one manual ACCOUNT_TRANSFER chain in the creation factory's frozen leg order
     * (PRINCIPAL_OUT, PRINCIPAL_IN, FEE on the fee category's posting account when the fee is
     * positive); no reconciliation/evidence row is written (J3 evidence facts 2/3).
     */
    private fun insertManualTransfer(
        harness: P705Database,
        transactionId: String,
        sourceAccount: AccountId = P705Fixture.bankA,
        destinationAccount: AccountId = P705Fixture.bankB,
        destinationCreditMinor: Long,
        feeMinor: Long,
        note: String? = "rent",
    ) {
        val postingSetId = "$transactionId-posting-set-1"
        val versionId = "$transactionId-version-1"
        harness.database.ledgerQueries.insertTransaction(transactionId, ledgerId.value, TransactionKind.ACCOUNT_TRANSFER.name)
        harness.database.ledgerQueries.insertPostingSet(postingSetId, ledgerId.value)
        harness.database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            ledgerId.value,
            1L,
            postingSetId,
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            P705Fixture.marchStatistics.toString(),
            note,
        )
        val legs =
            buildList {
                add(sourceAccount to -(destinationCreditMinor + feeMinor))
                add(destinationAccount to destinationCreditMinor)
                if (feeMinor > 0L) add(P705Fixture.expenseAccount to feeMinor)
            }
        legs.forEachIndexed { index, (accountId, amount) ->
            harness.database.ledgerQueries.insertPosting(
                "$transactionId-posting-$index",
                postingSetId,
                ledgerId.value,
                index.toLong(),
                accountId.value,
                amount,
                P705Fixture.cny.code,
                P705Fixture.cny.precision.toLong(),
            )
        }
        harness.database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId.value, versionId)
    }

    private fun correct(
        harness: P705Database,
        ids: TransferIds,
        requestId: RequestId,
        transactionId: String,
        expectedVersionId: TransactionVersionId = TransactionVersionId("$transactionId-version-1"),
        note: String? = "corrected",
        destinationCreditMinor: Long,
        feeMinor: Long,
        feeCategoryId: CategoryId?,
        sourceAccountId: AccountId = P705Fixture.bankA,
        destinationAccountId: AccountId = P705Fixture.bankB,
        statisticsAt: Instant = P705Fixture.marchStatistics,
    ): CorrectTransactionVersionResult =
        ExecuteCorrectTransactionVersion(harness.correctionPort, ids.correctSource, P705Fixture.admissionReader)
            .execute(
                ExplicitlyConfirmedTransactionCorrection(
                    ledgerId = ledgerId,
                    requestId = requestId,
                    transactionId = TransactionId(transactionId),
                    expectedCurrentVersionId = expectedVersionId,
                    note = note,
                    statisticsAt = statisticsAt,
                    amount = Money.ofMinor(destinationCreditMinor, P705Fixture.cny),
                    categoryId = feeCategoryId,
                    fundingAccountId = sourceAccountId,
                    destinationAccountId = destinationAccountId,
                    feeMinor = feeMinor,
                    confirmation = ExplicitManualSave,
                ),
            )

    @Test
    fun noteOnlyTransferCorrectionReusesThePostingSet() {
        P705Database.create("p705-t-note-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-note", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-note")

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(
                        harness,
                        ids,
                        ids.requestId(),
                        transactionId = "tx-transfer-note",
                        note = "renamed transfer",
                        destinationCreditMinor = 10_000L,
                        feeMinor = 200L,
                        feeCategoryId = P705Fixture.food,
                    ),
                )
            assertEquals(2L, harness.versionCount("tx-transfer-note"))
            assertEquals(created.receipt.versionId.value, harness.currentVersionId("tx-transfer-note"))
            // T-02: only the note changed, so the appended version keeps the current posting
            // set - no posting identity is rebound and no posting row is added.
            assertEquals(
                "tx-transfer-note-posting-set-1",
                harness.ledgerQueryText("SELECT posting_set_id FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals(
                "renamed transfer",
                harness.ledgerQueryText("SELECT note FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals(
                listOf(P705Fixture.bankA.value to -10_200L, P705Fixture.bankB.value to 10_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-note"),
            )
            assertEquals(3L, harness.ledgerQueryCount("SELECT count(*) FROM posting"))
        }
    }

    @Test
    fun destinationCreditCorrectionReshapesAllThreeLegs() {
        P705Database.create("p705-t-amount-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-amount", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-amount")

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(
                        harness,
                        ids,
                        ids.requestId(),
                        transactionId = "tx-transfer-amount",
                        destinationCreditMinor = 8_000L,
                        feeMinor = 200L,
                        feeCategoryId = P705Fixture.food,
                    ),
                )
            // T-01: the new version binds a fresh posting set with the derived source debit
            // 8_000 + 200; the old set keeps its rows byte for byte.
            assertEquals(
                listOf(P705Fixture.bankA.value to -8_200L, P705Fixture.bankB.value to 8_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-amount"),
            )
            assertEquals(
                listOf(-10_200L, 10_000L, 200L),
                harness.ledgerQueryLongs("SELECT amount_minor FROM posting WHERE posting_set_id = 'tx-transfer-amount-posting-set-1' ORDER BY posting_index"),
            )
            // occurred_at/effective_at are copied verbatim; only statistics_at follows the request.
            assertEquals(
                harness.ledgerQueryText("SELECT occurred_at FROM transaction_version WHERE version_id = 'tx-transfer-amount-version-1'"),
                harness.ledgerQueryText("SELECT occurred_at FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            assertEquals(
                harness.ledgerQueryText("SELECT effective_at FROM transaction_version WHERE version_id = 'tx-transfer-amount-version-1'"),
                harness.ledgerQueryText("SELECT effective_at FROM transaction_version WHERE version_id = '${created.receipt.versionId.value}'"),
            )
            // The committed request row stores the transfer columns of the section-8.3 mapping.
            assertEquals(
                P705Fixture.food.value,
                harness.ledgerQueryTextOrNull("SELECT category_id FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-amount'"),
            )
            assertEquals(
                P705Fixture.bankB.value,
                harness.ledgerQueryTextOrNull("SELECT destination_account_id FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-amount'"),
            )
            assertEquals(
                "200",
                harness.ledgerQueryText("SELECT CAST(fee_minor AS TEXT) FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-amount'"),
            )
        }
    }

    @Test
    fun endpointSwapMovesBothPrincipalLegs() {
        P705Database.create("p705-t-endpoints-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-endpoints", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-endpoints")

            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-endpoints",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    sourceAccountId = P705Fixture.bankB,
                    destinationAccountId = P705Fixture.bankA,
                ),
            )
            // T-04: both endpoints moved; the fee leg stays on the fee category's account.
            assertEquals(
                listOf(P705Fixture.bankB.value to -10_200L, P705Fixture.bankA.value to 10_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-endpoints"),
            )
        }
    }

    @Test
    fun feeToZeroDropsTheFeeLegAndTheCategory() {
        P705Database.create("p705-t-fee-zero-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-zero", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-fee-zero")
            val requestId = ids.requestId()

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(harness, ids, requestId, transactionId = "tx-transfer-fee-zero", destinationCreditMinor = 10_000L, feeMinor = 0L, feeCategoryId = null),
                )
            // T-03: the FEE leg is dropped from the new posting set and the category reference
            // goes with it - two principal legs, category_id stored NULL (AR:273).
            assertEquals(
                listOf(P705Fixture.bankA.value to -10_000L, P705Fixture.bankB.value to 10_000L),
                harness.currentPostings("tx-transfer-fee-zero"),
            )
            assertNull(
                harness.ledgerQueryTextOrNull("SELECT category_id FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-fee-zero'"),
            )
            assertEquals(
                "0",
                harness.ledgerQueryText("SELECT CAST(fee_minor AS TEXT) FROM transaction_correction_request WHERE transaction_id = 'tx-transfer-fee-zero'"),
            )
            // T-13 for the fee == 0 shape: replaying the same request id compares the stored
            // NULL category as stored and returns the original receipt, zero new entities.
            assertEquals(
                CorrectTransactionVersionResult.NoChange(created.receipt),
                correct(harness, ids, requestId, transactionId = "tx-transfer-fee-zero", destinationCreditMinor = 10_000L, feeMinor = 0L, feeCategoryId = null),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-fee-zero"))
        }
    }

    @Test
    fun ordinaryRowsRejectTheTransferColumnsAndAMissingCategory() {
        P705Database.create("p705-t-row-shape-").use { harness ->
            harness.insertOrdinaryExpense("tx-ordinary-shape", amountMinor = 10_000L)
            val ids = TransferIds("p705-t-row-shape")
            val useCase = ExecuteCorrectTransactionVersion(harness.correctionPort, ids.correctSource, P705Fixture.admissionReader)
            val request: (CategoryId?, AccountId?) -> ExplicitlyConfirmedTransactionCorrection =
                { categoryId, destinationAccountId ->
                    ExplicitlyConfirmedTransactionCorrection(
                        ledgerId = ledgerId,
                        requestId = ids.requestId(),
                        transactionId = TransactionId("tx-ordinary-shape"),
                        expectedCurrentVersionId = TransactionVersionId("tx-ordinary-shape-version-1"),
                        note = "corrected",
                        statisticsAt = P705Fixture.marchStatistics,
                        amount = Money.ofMinor(8_000L, P705Fixture.cny),
                        categoryId = categoryId,
                        fundingAccountId = P705Fixture.bankA,
                        destinationAccountId = destinationAccountId,
                        confirmation = ExplicitManualSave,
                    )
                }
            // Spec section 8.3's ordinary-row invariants, enforced by kind in the commit
            // transaction: an ordinary row may carry neither a destination account nor a fee,
            // and may never drop its category.
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED),
                useCase.execute(request(P705Fixture.food, P705Fixture.bankB)),
            )
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_FIELD_NOT_SUPPORTED),
                useCase.execute(request(null, null)),
            )
            assertEquals(1L, harness.versionCount("tx-ordinary-shape"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    @Test
    fun zeroFeeSeedGainsACategorisedFeeLeg() {
        P705Database.create("p705-t-fee-add-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-fee-add", destinationCreditMinor = 10_000L, feeMinor = 0L, note = null)
            val ids = TransferIds("p705-t-fee-add")

            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-fee-add",
                    destinationCreditMinor = 8_000L,
                    feeMinor = 150L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            // T-03b: the two-leg seed gains a FEE leg on the fee category's posting account;
            // the derived source debit becomes 8_000 + 150.
            assertEquals(
                listOf(P705Fixture.bankA.value to -8_150L, P705Fixture.bankB.value to 8_000L, P705Fixture.expenseAccount.value to 150L),
                harness.currentPostings("tx-transfer-fee-add"),
            )
        }
    }

    @Test
    fun replayConflictsAndStaleCasBehaveLikeTheOrdinaryFamily() {
        P705Database.create("p705-t-replay-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-replay", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val ids = TransferIds("p705-t-replay")
            val requestId = ids.requestId()

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(harness, ids, requestId, transactionId = "tx-transfer-replay", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
                )
            // T-13: the same request id with the same snapshot replays onto the original
            // receipt, zero new entities.
            assertEquals(
                CorrectTransactionVersionResult.NoChange(created.receipt),
                correct(harness, ids, requestId, transactionId = "tx-transfer-replay", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-replay"))
            // The same request id with a different snapshot is an identity conflict, zero writes.
            assertIs<CorrectTransactionVersionResult.RequestIdentityConflict>(
                correct(harness, ids, requestId, transactionId = "tx-transfer-replay", note = "different", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-replay"))
            // A fresh request id carrying the superseded CAS token is stale, zero writes.
            assertEquals(
                CorrectTransactionVersionResult.StaleCurrentVersion,
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-replay",
                    expectedVersionId = TransactionVersionId("tx-transfer-replay-version-1"),
                    destinationCreditMinor = 6_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-replay"))
            assertEquals(
                listOf(P705Fixture.bankA.value to -8_200L, P705Fixture.bankB.value to 8_000L, P705Fixture.expenseAccount.value to 200L),
                harness.currentPostings("tx-transfer-replay"),
            )
        }
    }

    @Test
    fun voidedTransferLeavesTheEffectiveSurfaceAndCannotBeCorrected() {
        P705Database.create("p705-t-voided-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-voided", destinationCreditMinor = 10_000L, feeMinor = 200L)
            val voidIds = P705Ids("p705-t-voided-v")
            assertIs<VoidTransactionResult.Created>(
                ExecuteVoidTransaction(harness.voidPort, voidIds.voidSource, fixedClock(P705Fixture.voidedAt))
                    .execute(
                        VoidTransactionRequest(
                            ledgerId = ledgerId,
                            requestId = voidIds.requestId(),
                            transactionId = TransactionId("tx-transfer-voided"),
                            reason = VoidReason(VoidReasonCode.MIS_ENTERED, "wrong account"),
                            confirmation = ExplicitManualSave,
                        ),
                    ),
            )
            // The transfer leaves the effective entry surface transaction-granularly (J1
            // candidate A): the version rows stay, the effective read drops the whole chain.
            assertEquals(1L, harness.versionCount("tx-transfer-voided"))
            assertEquals(0, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            // A correction of the voided transfer is a typed zero-write rejection.
            val ids = TransferIds("p705-t-voided")
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_TRANSACTION_VOIDED),
                correct(harness, ids, ids.requestId(), transactionId = "tx-transfer-voided", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
            )
            assertEquals(1L, harness.versionCount("tx-transfer-voided"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    /**
     * Seeds the `catalog_account` rows the narrowed DP-10 gate reads inside the port
     * (`selectCatalogAccounts` -> the real-account set of `affectedFundingLegs`), mirroring
     * [P705Fixture]'s injected catalog: the two real asset endpoints and the fee category's
     * hidden non-real expense posting account. The harness deliberately injects the admission
     * catalog in memory (P705Harness.kt KDoc), so without these rows the port's own catalog read
     * sees an empty ledger and no leg can ever classify as a real funding leg.
     */
    private fun seedCatalogRows(harness: P705Database) {
        fun account(
            accountId: AccountId,
            kind: String,
            realAccount: Long,
        ) {
            harness.database.ledgerQueries.insertCatalogAccount(
                ledger_id = ledgerId.value,
                account_id = accountId.value,
                name = "seed-${accountId.value}",
                kind = kind,
                currency_code = P705Fixture.cny.code,
                currency_precision = P705Fixture.cny.precision.toLong(),
                owned_by_user = 1L,
                real_account = realAccount,
                system_role = null,
                hidden = if (realAccount == 1L) 0L else 1L,
                active = 1L,
            )
        }
        account(P705Fixture.bankA, "ASSET", 1L)
        account(P705Fixture.bankB, "ASSET", 1L)
        account(P705Fixture.expenseAccount, "EXPENSE", 0L)
    }

    /**
     * Seeds a PENDING placeholder reconciliation row (evidence_link_id NULL, the v23-seed shape
     * of spec section 1.6) on one posting, through the same named queries the P408 writers use.
     */
    private fun seedPendingPlaceholder(
        harness: P705Database,
        postingId: String,
        reconciliationId: String,
    ) {
        val seedRequestId = "seed-$reconciliationId-request"
        harness.database.ledgerQueries.claimP408ReconciliationRequest(ledgerId.value, seedRequestId, "migration_seed", "seed-$reconciliationId")
        harness.database.ledgerQueries.updateP408ReconciliationRequest("ACCEPTED", null, ledgerId.value, seedRequestId)
        harness.database.ledgerQueries.insertP408PostingReconciliation(ledgerId.value, reconciliationId, postingId)
        harness.database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
            sequence = 1L,
            status = "PENDING",
            evidence_link_id = null,
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
    }

    /**
     * Seeds one active evidence link on a posting - the row A fixture the product path cannot
     * produce (J3 evidence fact 3): the import evidence chain the link FKs need, an
     * `evidence_link` with `active/confirmed` history, and the posting's reconciliation row
     * advanced to CHECKED exactly the way a confirmLink commit leaves it.
     */
    private fun seedActiveLink(
        harness: P705Database,
        transactionId: String,
        postingId: String,
        linkId: String,
        reconciliationId: String,
    ) {
        val seedRequestId = "seed-$linkId-request"
        harness.database.ledgerQueries.claimP408ReconciliationRequest(ledgerId.value, seedRequestId, "confirm_link", "seed-$linkId")
        harness.database.ledgerQueries.updateP408ReconciliationRequest("ACCEPTED", null, ledgerId.value, seedRequestId)
        harness.database.ledgerQueries.claimImportRequest(ledgerId.value, "seed-$linkId-import", "confirm_candidate")
        harness.database.ledgerQueries.insertImportSourceRecord(
            ledgerId.value,
            "seed-$linkId-source",
            "seed-$linkId-import",
            "input-ref-$linkId",
            0L,
            "ordinary_flow_source",
            "content-hash-$linkId",
            1L,
            "valid_incomplete",
            null,
            null,
            null,
            null,
            null,
            null,
            "UNRESOLVED",
            "rule-$linkId",
            1L,
            P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertImportEvidence(
            ledgerId.value,
            "evidence-$linkId",
            "seed-$linkId-source",
            "source_observation",
            P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408PostingReconciliation(ledgerId.value, reconciliationId, postingId)
        harness.database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
            sequence = 1L,
            status = "PENDING",
            evidence_link_id = null,
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408EvidenceLink(
            ledger_id = ledgerId.value,
            link_id = linkId,
            evidence_id = "evidence-$linkId",
            posting_id = postingId,
            transaction_id = transactionId,
            responsibility = "real_account_posting",
            basis_version = 2L,
            match_basis = "exact",
            candidate_id = "candidate-$linkId",
            request_id = seedRequestId,
            created_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408EvidenceLinkHistory(
            ledger_id = ledgerId.value,
            link_id = linkId,
            sequence = 1L,
            state = "active",
            reason = "confirmed",
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.insertP408PostingReconciliationHistory(
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
            sequence = 2L,
            status = "CHECKED",
            evidence_link_id = linkId,
            request_id = seedRequestId,
            occurred_at = P705Fixture.marchStatistics.toString(),
        )
        harness.database.ledgerQueries.updateP408PostingReconciliation(
            status = "CHECKED",
            latest_sequence = 2L,
            ledger_id = ledgerId.value,
            reconciliation_id = reconciliationId,
        )
    }

    /**
     * Behavior-matrix row C (D-228 J3 rendering (i)/(iv), T-12): a note-only correction is
     * never rejected and never touches reconciliation or evidence rows - neither a PENDING
     * placeholder nor a live active link is a rejection reason when no funding leg changed.
     */
    @Test
    fun noteOnlyCorrectionKeepsSeededReconciliationRowsAndLinksVerbatim() {
        P705Database.create("p705-t-j3-c-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3c", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedPendingPlaceholder(harness, "tx-transfer-j3c-posting-0", "reconciliation-j3c-src")
            seedActiveLink(harness, "tx-transfer-j3c", "tx-transfer-j3c-posting-1", "link-j3c-dst", "reconciliation-j3c-dst")
            val ids = TransferIds("p705-t-j3c")

            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-j3c",
                    note = "note only",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            // The reuse write form kept the posting set, so no funding leg changed: the
            // placeholder row and the active link stay value-identical, and the D-113 request
            // family was never entered (no derived request, receipt or correction snapshot).
            assertEquals(
                listOf("PENDING"),
                harness.ledgerQueryTexts("SELECT status FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3c-src' ORDER BY sequence"),
            )
            assertEquals("PENDING", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE reconciliation_id = 'reconciliation-j3c-src'"))
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3c-dst' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE reconciliation_id = 'reconciliation-j3c-dst'"))
            assertEquals(2L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
        }
    }

    /**
     * Behavior-matrix row B (D-228 J3 rendering (ii), T-12's negative pin): a genuinely changed
     * funding leg that carries only a PENDING placeholder (evidence_link_id NULL) has no
     * invalidation object - the correction succeeds, is never rejected, never reports
     * P408_INVALIDATE_LINK_NOT_ACTIVE, and the placeholder row survives verbatim.
     */
    @Test
    fun changedLegWithOnlyAPendingPlaceholderIsNeverRejectedAndKeepsTheRow() {
        P705Database.create("p705-t-j3-b-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3b", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedPendingPlaceholder(harness, "tx-transfer-j3b-posting-0", "reconciliation-j3b-src")
            val ids = TransferIds("p705-t-j3b")

            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    correct(harness, ids, ids.requestId(), transactionId = "tx-transfer-j3b", destinationCreditMinor = 8_000L, feeMinor = 200L, feeCategoryId = P705Fixture.food),
                )
            // The correction succeeded and rebound the posting set...
            assertEquals(2L, harness.versionCount("tx-transfer-j3b"))
            assertEquals(created.receipt.versionId.value, harness.currentVersionId("tx-transfer-j3b"))
            // ...and the placeholder on the replaced old posting is untouched, with no
            // invalidation side effects anywhere: no linked history row, no derived D-113
            // request beyond the seed, no receipt, no correction snapshot, no evidence link.
            assertEquals(
                listOf("PENDING"),
                harness.ledgerQueryTexts("SELECT status FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3b-src' ORDER BY sequence"),
            )
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM posting_reconciliation_history WHERE evidence_link_id IS NOT NULL"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM evidence_link"))
        }
    }

    /**
     * Behavior-matrix row A (D-228 J3 rendering (i)/(iii)): a changed funding leg that carries
     * an active evidence link has that link invalidated through the reused D-113 store inside
     * the correction transaction - the correction itself succeeds. The invalidation is
     * invalidation-only (`posting_replaced`, `MISSING`, no successor), and unchanged legs - the
     * destination principal leg here, plus the non-real FEE leg, which never participates -
     * keep their rows byte for byte. The active link is a fixture the product path cannot
     * produce yet (rendering (iii): no live trigger point, coverage by explicit construction).
     */
    @Test
    fun changedLegWithAnActiveMatchIsInvalidatedAndTheCorrectionSucceeds() {
        P705Database.create("p705-t-j3-a-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3a", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3a", "tx-transfer-j3a-posting-0", "link-j3a-src", "reconciliation-j3a-src")
            seedActiveLink(harness, "tx-transfer-j3a", "tx-transfer-j3a-posting-1", "link-j3a-dst", "reconciliation-j3a-dst")
            seedPendingPlaceholder(harness, "tx-transfer-j3a-posting-2", "reconciliation-j3a-fee")
            val ids = TransferIds("p705-t-j3a")
            val requestId = ids.requestId()

            // Only the fee moves (200 -> 300): the source principal leg's tuple changes (its
            // derived debit moves) while the destination principal leg and - being non-real -
            // the FEE leg's tuple stay out of the affected set.
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    requestId,
                    transactionId = "tx-transfer-j3a",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 300L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-j3a"))
            // The source leg's match was invalidated: history moves to invalidated/posting_replaced
            // and the posting's reconciliation advances to MISSING with a link-less history row.
            assertEquals(
                listOf("active", "invalidated"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3a-src' ORDER BY sequence"),
            )
            assertEquals(
                listOf("confirmed", "posting_replaced"),
                harness.ledgerQueryTexts("SELECT reason FROM evidence_link_history WHERE link_id = 'link-j3a-src' ORDER BY sequence"),
            )
            assertEquals("MISSING", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3a-posting-0'"))
            assertEquals(
                listOf("PENDING", "CHECKED", "MISSING"),
                harness.ledgerQueryTexts("SELECT status FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3a-src' ORDER BY sequence"),
            )
            assertEquals(
                listOf(-1L, 1L, -1L),
                harness.ledgerQueryLongs(
                    "SELECT CASE WHEN evidence_link_id IS NULL THEN -1 ELSE 1 END FROM posting_reconciliation_history WHERE reconciliation_id = 'reconciliation-j3a-src' ORDER BY sequence",
                ),
            )
            // The derived D-113 request committed as one more reconciliation request/receipt pair.
            assertEquals(
                "ACCEPTED",
                harness.ledgerQueryText("SELECT outcome FROM reconciliation_request WHERE request_id = '${requestId.value}:invalidate:link-j3a-src'"),
            )
            assertEquals(
                1L,
                harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt WHERE request_id = '${requestId.value}:invalidate:link-j3a-src'"),
            )
            assertEquals(
                "posting_replaced",
                harness.ledgerQueryText("SELECT reason FROM reconciliation_correction_snapshot WHERE previous_link_id = 'link-j3a-src'"),
            )
            // Unchanged legs: the destination's active link and CHECKED row, and the FEE leg's
            // placeholder, are all value-identical; no second invalidation request exists.
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3a-dst' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3a-posting-1'"))
            assertEquals("PENDING", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3a-posting-2'"))
            assertEquals(
                4L,
                harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"),
            )
        }
    }

    /**
     * Atomicity of the composition (D-228 J3, same-transaction requirement): a correction whose
     * CAS loses must not leave its invalidation behind. The first, note-only correction
     * advances the version without touching legs; the second, leg-changing correction carries
     * the superseded CAS token, runs its invalidation, then rolls the whole transaction back -
     * the link stays active and no derived D-113 request survives.
     */
    @Test
    fun aStaleCorrectionRollsBackItsInvalidationWrites() {
        P705Database.create("p705-t-j3-stale-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3st", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3st", "tx-transfer-j3st-posting-0", "link-j3st-src", "reconciliation-j3st-src")
            val ids = TransferIds("p705-t-j3-stale")

            // Version 2 via a note-only correction (reuse form: no invalidation, same postings).
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-j3st",
                    note = "first",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            // The leg-changing retry carries the superseded version-1 CAS token.
            assertEquals(
                CorrectTransactionVersionResult.StaleCurrentVersion,
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-j3st",
                    expectedVersionId = TransactionVersionId("tx-transfer-j3st-version-1"),
                    destinationCreditMinor = 8_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            // No partial state: the match is still active, the reconciliation row still CHECKED,
            // the derived D-113 request rolled back with the transaction (only the seed request
            // survives), and the claim left no row.
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3st-src' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3st-posting-0'"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
            assertEquals(2L, harness.versionCount("tx-transfer-j3st"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }

    /**
     * The composition's fail-closed gate: a port built by the platform factory carries no
     * D-113 store (its driverless construction is impossible), so a correction that would need
     * an invalidation is the constraint backstop - never a silent pass-through that leaves the
     * stale match alive, and never a partial write.
     */
    @Test
    fun aPlatformConfiguredPortWithoutTheInvalidationStoreFailsClosedOnAnActiveMatch() {
        P705Database.create("p705-t-j3-platform-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3p", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3p", "tx-transfer-j3p-posting-0", "link-j3p-src", "reconciliation-j3p-src")
            val ids = TransferIds("p705-t-j3-platform")
            val platformPort = SqlDelightTransactionCorrectionCommitPort.forPlatformConfiguredDatabase(harness.database)

            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION),
                ExecuteCorrectTransactionVersion(platformPort, ids.correctSource, P705Fixture.admissionReader)
                    .execute(
                        ExplicitlyConfirmedTransactionCorrection(
                            ledgerId = ledgerId,
                            requestId = ids.requestId(),
                            transactionId = TransactionId("tx-transfer-j3p"),
                            expectedCurrentVersionId = TransactionVersionId("tx-transfer-j3p-version-1"),
                            note = "platform",
                            statisticsAt = P705Fixture.marchStatistics,
                            amount = Money.ofMinor(8_000L, P705Fixture.cny),
                            categoryId = P705Fixture.food,
                            fundingAccountId = P705Fixture.bankA,
                            destinationAccountId = P705Fixture.bankB,
                            feeMinor = 200L,
                            confirmation = ExplicitManualSave,
                        ),
                    ),
            )
            // Zero-write: the version was not appended, the match is still active and the claim
            // left no request row behind.
            assertEquals(1L, harness.versionCount("tx-transfer-j3p"))
            assertEquals(listOf("active"), harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3p-src' ORDER BY sequence"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
            assertEquals(1L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
        }
    }

    /**
     * All-or-nothing of the composition (review finding F-1): when a later leg's invalidation
     * fails after an earlier leg's already succeeded, the rejection must roll the whole
     * transaction back - the earlier leg's match stays active and no derived D-113 row survives.
     * The deterministic mid-loop failure: both principal legs are affected (endpoint swap), and
     * the SECOND derived request id is pre-seeded as a PENDING reconciliation_request with a
     * foreign fingerprint, so the D-113 store resolves that claim as a snapshot mismatch and
     * rejects only after the first invalidation has been applied.
     */
    @Test
    fun aMidInvalidationFailureRollsBackEveryInvalidationWrite() {
        P705Database.create("p705-t-j3-atomic-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-j3at", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            seedActiveLink(harness, "tx-transfer-j3at", "tx-transfer-j3at-posting-0", "link-j3at-src", "reconciliation-j3at-src")
            seedActiveLink(harness, "tx-transfer-j3at", "tx-transfer-j3at-posting-1", "link-j3at-dst", "reconciliation-j3at-dst")
            val ids = TransferIds("p705-t-j3-atomic")
            val requestId = ids.requestId()
            harness.database.ledgerQueries.claimP408ReconciliationRequest(
                ledgerId.value,
                "${requestId.value}:invalidate:link-j3at-dst",
                "invalidate_link",
                "deliberately-foreign-fingerprint",
            )

            // The endpoint swap changes both principal tuples, so both links enter the loop in
            // posting_index order: the source leg's invalidation succeeds first, then the
            // destination leg's store call resolves the blocked claim and rejects.
            assertEquals(
                CorrectTransactionVersionResult.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION),
                correct(
                    harness,
                    ids,
                    requestId,
                    transactionId = "tx-transfer-j3at",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                    sourceAccountId = P705Fixture.bankB,
                    destinationAccountId = P705Fixture.bankA,
                ),
            )
            // All-or-nothing: the first link's invalidation rolled back with the transaction.
            assertEquals(1L, harness.versionCount("tx-transfer-j3at"))
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3at-src' ORDER BY sequence"),
            )
            assertEquals(
                listOf("active"),
                harness.ledgerQueryTexts("SELECT state FROM evidence_link_history WHERE link_id = 'link-j3at-dst' ORDER BY sequence"),
            )
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3at-posting-0'"))
            assertEquals("CHECKED", harness.ledgerQueryText("SELECT status FROM posting_reconciliation WHERE posting_id = 'tx-transfer-j3at-posting-1'"))
            // The derived request for the first leg rolled back too: only the two seed requests
            // and the pre-seeded blocker remain, and neither a receipt nor a snapshot exists.
            assertEquals(3L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM reconciliation_correction_snapshot"))
            // The rolled-back correction leaves no claim row behind: the identity is retryable.
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_correction_request"))
        }
    }
}
