package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-03 i2b (D-312; spec section 2.2) — the shared submission fold's refund delegate.
 *
 * The shared entry point must route a `ManualEntrySaveInput.RefundReceipt` to its own delegate and
 * wrap the per-type result back into `ManualEntrySubmissionResult.RefundReceipt`; the shared
 * note-length gate must never pre-empt it (a refund request carries no note at all, so the gate's
 * zero-write rejection is unreachable for this type). No database is involved: the refund delegate
 * is the real `SubmitManualRefundReceipt` over a fixed-result fake commit port, and the expense/
 * income delegates are throwing stubs the refund path never touches.
 */
class P803ManualRefundEntryFoldTest {
    private val request =
        ManualRefundReceiptRequest(
            ledgerId = LedgerId("ledger-a"),
            requestId = RequestId("request-refund-1"),
            originalTransactionId = TransactionId("tx-original"),
            amount = Money.ofMinor(3_580L, CurrencyUnit("CNY", 2)),
            destinationAccountId = AccountId("asset-destination"),
            arrivedAt = Instant.parse("2026-01-15T00:30:00Z"),
            confirmedAt = Instant.parse("2026-01-15T00:31:00Z"),
        )

    @Test
    fun theRefundInputIsDelegatedAndWrappedInTheSharedUnion() {
        val commitPort = P803FixedRefundCommitPort(ManualRefundReceiptCommitResult.Created(p803Receipt))

        val result =
            p803EntrySubmission(refund = p803RefundDelegate(commitPort))
                .submit(ManualEntrySaveInput.RefundReceipt(request))

        assertEquals(
            ManualEntrySubmissionResult.RefundReceipt(ManualRefundReceiptSubmissionResult.Created(p803Receipt)),
            result,
        )
        // The note gate never runs for a refund (`ManualEntrySaveInput.RefundReceipt.note` is
        // constantly empty), so the commit port — not the gate — is what answers the submission:
        // exactly one commit attempt, no expense/income delegation.
        assertEquals(1, commitPort.invocationCount)
    }

    @Test
    fun aRefundRejectionKeepsItsOwnEightCodeResultInsideTheUnion() {
        val commitPort =
            P803FixedRefundCommitPort(
                ManualRefundReceiptCommitResult.Rejected(ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING),
            )

        val result = p803EntrySubmission(refund = p803RefundDelegate(commitPort)).submit(ManualEntrySaveInput.RefundReceipt(request))

        assertEquals(
            ManualEntrySubmissionResult.RefundReceipt(
                ManualRefundReceiptSubmissionResult.Rejected(ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING),
            ),
            result,
        )
    }

    @Test
    fun anUncertainCommitAnswerStaysUnknownNeverADefiniteRejection() {
        // Spec section 2.6: the commit port's Unavailable answer says the relationship row does not
        // answer the attempt AND its own re-read failed — "not a proof of rollback". The use case
        // must land the typed UNKNOWN, never a definite rejection that invites a fresh-request-id
        // retry (a second refund). A retry reuses the request id and goes through the resolver.
        val commitPort = P803FixedRefundCommitPort(ManualRefundReceiptCommitResult.Unavailable)

        val result = p803EntrySubmission(refund = p803RefundDelegate(commitPort)).submit(ManualEntrySaveInput.RefundReceipt(request))

        assertEquals(
            ManualEntrySubmissionResult.RefundReceipt(ManualRefundReceiptSubmissionResult.UnknownCommit),
            result,
        )
    }

    @Test
    fun theRefundSaveInputCarriesItsOwnIdentityAndAnEmptyNote() {
        val input = ManualEntrySaveInput.RefundReceipt(request)
        assertEquals(LedgerId("ledger-a"), input.ledgerId)
        assertEquals(RequestId("request-refund-1"), input.requestId)
        // The frozen invariant that makes the shared note gate unreachable for this type.
        assertEquals("", input.note)
        assertTrue(input.note.isEmpty())
    }

    private fun p803RefundDelegate(commitPort: ManualRefundReceiptCommitPort): SubmitManualRefundReceipt =
        SubmitManualRefundReceipt(
            commitPort = commitPort,
            idSource = { error("a fixed-result commit port must not request ids") },
            resolver = ResolveManualRefundReceiptCommitStatus(P803EmptyRefundReadPort),
        )

    /**
     * The real shared entry point over the refund delegate and two throwing stubs. The stubs prove
     * the refund path never falls through to another type's delegate: any accidental routing would
     * throw instead of returning a result.
     */
    private fun p803EntrySubmission(refund: SubmitManualRefundReceipt): ExecuteManualEntrySubmission {
        val readPort = P803EmptyReadPort
        val failingExpenseCommit: ConfirmedManualExpenseCommitPort = { _, _, _, _ -> error("expense delegate must not run for a refund") }
        val failingIncomeCommit: ConfirmedManualIncomeCommitPort = { _, _, _, _ -> error("income delegate must not run for a refund") }
        val expenseTracker = CommitOnceInvocationTracker(failingExpenseCommit)
        val incomeTracker = CommitOnceInvocationTrackerIncome(failingIncomeCommit)
        return ExecuteManualEntrySubmission(
            expense =
                ExecuteManualExpenseSubmission(
                    executeSave =
                        ExecuteManualExpenseSave(
                            ExecuteConfirmedManualExpense(
                                commitPort = expenseTracker,
                                idSource = ConfirmedManualExpenseIdSource { error("no expense ids") },
                                createFormalTransaction = ConfirmedExpenseTransactionFactory { _, _ -> error("no expense factory") },
                            ),
                        ),
                    tracker = expenseTracker,
                    resolver = ResolveManualExpenseCommitStatus(readPort),
                ),
            income =
                ExecuteManualIncomeSubmission(
                    executeSave =
                        ExecuteManualIncomeSave(
                            ExecuteConfirmedManualIncome(
                                incomeTracker,
                                ConfirmedManualIncomeIdSource { error("no income ids") },
                                ConfirmedIncomeTransactionFactory { _, _ -> error("no income factory") },
                            ),
                        ),
                    tracker = incomeTracker,
                    resolver = ResolveManualIncomeCommitStatus(readPort),
                ),
            refund = refund,
        )
    }
}

private val p803Receipt = ManualRefundReceiptRecord(relationshipId = "relationship-1", refundTransactionId = TransactionId("tx-refund"))

private object P803EmptyRefundReadPort : ManualRefundReceiptReadPort {
    override fun findByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualRefundReceiptReadRecord? = null
}

private object P803EmptyReadPort : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = null

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = null

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null
}

private class P803FixedRefundCommitPort(
    private val result: ManualRefundReceiptCommitResult,
) : ManualRefundReceiptCommitPort {
    var invocationCount = 0
        private set

    override fun commitOnce(
        request: ManualRefundReceiptRequest,
        requestFingerprint: String,
        mintIds: () -> ManualRefundReceiptIds,
    ): ManualRefundReceiptCommitResult {
        invocationCount += 1
        return result
    }
}
