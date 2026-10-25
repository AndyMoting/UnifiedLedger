package com.unifiedledger.data

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.CorrectTransactionVersionIdSource
import com.unifiedledger.application.CorrectTransactionVersionIds
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryMonthlyActivity
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    private val catalog: LedgerCatalog = P705Fixture.catalog()

    private fun state(harness: P705Database): LedgerCurrentState =
        assertIs<LedgerCurrentStateResult.Success>(
            QueryLedgerCurrentState(harness.readAdapter, ledgerId, catalog).query(),
        ).state

    private fun marchActivity(harness: P705Database): MonthlyActivity =
        assertIs<MonthlyActivityResult.Success>(
            QueryMonthlyActivity(
                harness.readAdapter,
                ledgerId,
                catalog,
                fixedClock(Instant.parse("2026-03-20T00:00:00Z")),
            ).query(YearMonth(2026, 3)),
        ).activity

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
            // A second, unrelated ordinary expense keeps the ledger's activity currency and the
            // monthly projection non-degenerate, so the post-void zero assertions below cannot
            // pass merely because the whole read side collapsed to an empty ledger.
            harness.insertOrdinaryExpense("tx-expense-bystander", amountMinor = 5_000L)
            insertManualTransfer(harness, "tx-transfer-voided", destinationCreditMinor = 10_000L, feeMinor = 200L)

            // Baseline BEFORE the void (T-06 non-empty positive control): each surface the vector
            // later asserts must be observably non-degenerate with the transfer present.
            assertEquals(2, harness.readAdapter.loadLedgerEntryRows(ledgerId).size)
            val beforeBalances = state(harness).balances.associate { it.accountId.value to it.ledgerSignedMinorUnits }
            // The bystander expense also debits bankA (5_000) and credits the expense posting
            // account (5_000), so each baseline carries both transactions' effects.
            assertEquals(-15_200L, beforeBalances.getValue(P705Fixture.bankA.value))
            assertEquals(10_000L, beforeBalances.getValue(P705Fixture.bankB.value))
            assertEquals(5_200L, beforeBalances.getValue(P705Fixture.expenseAccount.value))
            val beforeSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(2, beforeSummary.totalTransactionCount)
            assertEquals(1, beforeSummary.countByKind.getValue(TransactionKind.ACCOUNT_TRANSFER))
            val marchStart = requireNotNull(StatisticsAtProjection.project(Instant.parse("2026-03-01T00:00:00Z")))
            val aprilStart = requireNotNull(StatisticsAtProjection.project(Instant.parse("2026-04-01T00:00:00Z")))
            val projection = SqlDelightStatisticsAtProjectionReadPort(harness.database)
            assertEquals(
                listOf("tx-expense-bystander-version-1", "tx-transfer-voided-version-1").sorted(),
                projection
                    .projectedVersionsInWindow(ledgerId, marchStart, aprilStart)
                    .map { it.versionId.value }
                    .sorted(),
            )
            assertEquals(2, marchActivity(harness).currencies.single().transactionCount)

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
            // candidate A): the version rows stay while every surface asserted below drops the
            // whole chain, and the bystander expense keeps the ledger itself non-degenerate.
            // Faces N2-N5 (the monthly-contribution, widget, and tag/merchant adapters) are NOT
            // asserted here - they need a bootstrapped catalog store and extra adapters - and
            // remain open for T-06; this vector covers #1-#5, N1, N10 and the monthly use case.
            assertEquals(1L, harness.versionCount("tx-transfer-voided"))
            // Faces #1-#3: the input row set.
            assertEquals(listOf("tx-expense-bystander"), harness.readAdapter.loadLedgerEntryRows(ledgerId).map { it.transactionId.value })
            // Face #4: HOME balances - both principal legs leave and the fee leg leaves ordinary
            // expense, leaving only the bystander's effects (bankA -5_000, expense 5_000).
            val afterBalances = state(harness).balances.associate { it.accountId.value to it.ledgerSignedMinorUnits }
            assertEquals(-5_000L, afterBalances.getValue(P705Fixture.bankA.value))
            assertNull(afterBalances[P705Fixture.bankB.value])
            assertEquals(5_000L, afterBalances.getValue(P705Fixture.expenseAccount.value))
            // Face #5: the activity summary drops the transfer and its kind count.
            val afterSummary = SummarizeLedgerActivity(catalog).summarize(state(harness))
            assertEquals(1, afterSummary.totalTransactionCount)
            assertEquals(0, afterSummary.countByKind[TransactionKind.ACCOUNT_TRANSFER] ?: 0)
            // Face N1: the statistics projection window drops the voided version. Sorted on both
            // sides so the comparison never depends on the query's row order.
            assertEquals(
                listOf("tx-expense-bystander-version-1"),
                projection
                    .projectedVersionsInWindow(ledgerId, marchStart, aprilStart)
                    .map { it.versionId.value }
                    .sorted(),
            )
            // The monthly surface also drops the transfer (count and kind count) while keeping the
            // bystander's currency row alive.
            val afterActivity = marchActivity(harness)
            val monthCurrency = afterActivity.currencies.single()
            assertEquals(1, monthCurrency.transactionCount)
            assertEquals(0, monthCurrency.countByKind[TransactionKind.ACCOUNT_TRANSFER] ?: 0)
            // Face N10: the DP-13 refund probe is structurally insensitive to voiding a transfer
            // (spec section 5.2, DP-T5): its effective predicate joins on the refund
            // transaction's confirmation, while the filter is the voided original. The assertion
            // records the frozen no-op invariant and is deliberately NOT counted as T-06
            // red-able coverage; the liveness control below proves the probe is wired.
            assertEquals(false, harness.readAdapter.hasEffectiveRefundLink(ledgerId, TransactionId("tx-transfer-voided")))
            // Liveness positive control: the same probe DOES answer true for a real
            // product-linked refund, so a broken join would red here.
            harness.insertOrdinaryExpense("tx-expense-refunded", amountMinor = 1_000L)
            harness.insertOrdinaryExpense("tx-refund-linked", amountMinor = 400L)
            harness.insertProductLinkedRefund(
                originalTransactionId = "tx-expense-refunded",
                refundTransactionId = "tx-refund-linked",
            )
            assertEquals(true, harness.readAdapter.hasEffectiveRefundLink(ledgerId, TransactionId("tx-expense-refunded")))
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
     * Transfer spec section 7.4, correction side, restricted to the owners the spec marks as
     * unconditionally zero-write: the lending principal history (DP-4), the import owner (DP-5)
     * and `evidence_projection`. This vector performs a note-only correction, so the posting set
     * is reused and no funding leg changes.
     *
     * `evidence_projection` belongs to the spec's reconciliation-owner row, whose correction-side
     * writes are "only the J3-decided surface", so asserting it unchanged needs its own
     * justification rather than the blanket DP-4/DP-5 one. The port only ever composes D-113
     * invalidation with `resultState = MISSING` - the CHECKED successor re-proof is a later
     * slice - and the projection authority branch is reached only for CHECKED, so a transfer
     * correction writes no projection even when a leg changes. `posting_reconciliation*` and
     * `evidence_link*` stay out because invalidation legitimately touches them on a changed leg,
     * and the DP-10 matrix vectors above pin that.
     *
     * This is NOT the spec's T-11 row either: T-11 is worded for the void/restore path. The
     * transfer void/restore owner leg remains unasserted and stays OPEN.
     */
    @Test
    fun aNoteOnlyTransferCorrectionWritesNoneOfTheLendingImportOrProjectionOwners() {
        P705Database.create("p705-t-owner-").use { harness ->
            insertManualTransfer(harness, "tx-transfer-owner", destinationCreditMinor = 10_000L, feeMinor = 200L)
            seedCatalogRows(harness)
            // Non-empty baselines: an all-zero start would make every comparison red-able only
            // from zero to zero. The import spine here hangs off an unrelated bystander, never
            // off the corrected transfer, so the creation-lineage gate is not what rejects us.
            harness.insertOrdinaryExpense("tx-expense-owner-bystander", amountMinor = 5_000L)
            harness.insertImportCreationConfirmation("tx-expense-owner-bystander")
            // `lending_position` FKs both `counterparty` and `catalog_account`, so the
            // counterparty row must exist too - seeding only the position would leave an
            // FK-invalid row that lands solely because this harness does not enforce FKs.
            harness.database.ledgerQueries.insertCounterparty(
                ledgerId.value,
                "counterparty-owner",
                "counterparty-owner-name",
                P705Fixture.bankA.value,
                1L,
            )
            harness.database.ledgerQueries.insertLendingPosition(
                ledger_id = ledgerId.value,
                counterparty_id = "counterparty-owner",
                receivable_account_id = P705Fixture.bankA.value,
                currency_code = P705Fixture.cny.code,
                currency_precision = P705Fixture.cny.precision.toLong(),
                principal_balance_minor = 7_000L,
            )
            harness.database.ledgerQueries.insertLendingPositionHistory(
                ledger_id = ledgerId.value,
                counterparty_id = "counterparty-owner",
                entry_id = "entry-owner",
                behavior_code = "LEND",
                amount_minor = 7_000L,
                principal_balance_after_minor = 7_000L,
                transaction_id = "tx-expense-owner-bystander",
                occurred_at = P705Fixture.marchStatistics.toString(),
            )
            harness.database.ledgerQueries.insertEvidenceProjection(
                ledger_id = ledgerId.value,
                projection_id = "projection-owner",
                evidence_id = "evidence-p705-expense-owner-bystander",
                source_id = "source-p705-expense-owner-bystander",
                source_hash = "content-hash-p705",
                target_account_id = P705Fixture.bankA.value,
                currency_code = P705Fixture.cny.code,
                currency_precision = P705Fixture.cny.precision.toLong(),
                raw_amount_minor = 5_000L,
                raw_currency_precision = P705Fixture.cny.precision.toLong(),
                normalized_amount_minor = 5_000L,
                direction_token = "out",
                state = "READY",
                rejection_code = null,
                rule_id = "rule-owner",
                rule_version = 1L,
                materialization_request_id = "materialization-owner",
                materialized_at = P705Fixture.marchStatistics.toString(),
            )
            harness.database.ledgerQueries.insertImportReceipt(
                ledgerId.value,
                "request-p705-expense-owner-bystander",
                "accepted",
                "source-p705-expense-owner-bystander",
                "evidence-p705-expense-owner-bystander",
                "candidate-p705-expense-owner-bystander",
                "confirmation-p705-expense-owner-bystander",
                "tx-expense-owner-bystander",
            )
            val ownerTables =
                listOf(
                    "evidence_projection",
                    "lending_position",
                    "lending_position_history",
                    "import_confirmation",
                    "import_receipt",
                    "import_candidate_status_history",
                )
            // The harness does not enforce foreign keys, so an FK-invalid seed would land
            // silently and the zero-write comparison below would be built on a broken baseline.
            // Assert the whole ledger's seed set is FK-valid before comparing.
            assertEquals(
                emptyList<String>(),
                harness.ledgerQueryTexts("SELECT \"table\" FROM pragma_foreign_key_check"),
            )
            val before = ownerTables.associateWith { table -> ownerRowsOf(harness, table) }
            // Non-degeneracy guard: every owner table really does hold rows, so the equality
            // below compares content rather than two empty lists.
            for (table in ownerTables) {
                assertTrue(before.getValue(table).isNotEmpty(), "$table must be non-empty before the correction")
            }

            val ids = TransferIds("p705-t-owner")
            assertIs<CorrectTransactionVersionResult.Created>(
                correct(
                    harness,
                    ids,
                    ids.requestId(),
                    transactionId = "tx-transfer-owner",
                    note = "renamed",
                    destinationCreditMinor = 10_000L,
                    feeMinor = 200L,
                    feeCategoryId = P705Fixture.food,
                ),
            )
            assertEquals(2L, harness.versionCount("tx-transfer-owner"))
            // Row-for-row equality over every column of every row, read as stored text through a
            // multi-column mapper. This detects added, removed and changed rows; it does not
            // distinguish an INTEGER-to-TEXT storage-class change of an unchanged value, which a
            // zero-write question does not need.
            assertEquals(before, ownerTables.associateWith { table -> ownerRowsOf(harness, table) })
        }
    }

    /**
     * The complete column tuple of every row in [table], read through the same driver the
     * claim-first port uses, with a multi-column mapper so NULLs keep their place instead of
     * swallowing neighbouring values. Rows are ordered by all columns, which makes the
     * comparison deterministic without assuming a particular primary key.
     */
    private fun ownerRowsOf(
        harness: P705Database,
        table: String,
    ): List<List<String?>> {
        val columns = harness.ledgerQueryTexts("SELECT name FROM pragma_table_info('$table') ORDER BY cid")
        val projection = columns.joinToString(", ")
        val order = columns.joinToString(", ")
        return harness.driverQuery(
            "SELECT $projection FROM $table WHERE ledger_id = ? ORDER BY $order",
            ledgerId.value,
        ) { cursor ->
            buildList {
                while (cursor.next().value) {
                    add(columns.indices.map { index -> cursor.getString(index) })
                }
            }
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
