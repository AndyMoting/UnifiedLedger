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
 * P7-05 transfer-slice correction commit evidence (transfer spec sections 3/8.3; D-228 J1):
 * real claim-first port commits of manual-transfer corrections across the frozen field set -
 * note, destination credit, both endpoints and the fee (both leg-count directions) - plus the
 * CAS/replay family and the voided-target rejection. Every vector seeds a real manual transfer
 * chain (transaction, version 1, posting set, postings, current-version pointer) in the frozen
 * creation leg order; manual transfers carry no reconciliation or evidence rows (J3 evidence
 * facts 2/3), so the seed writes none and the DP-10 placeholder passes untouched. Transfer
 * restore semantics are slice 3 and deliberately unasserted here; values are anonymous
 * synthetic.
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
}
