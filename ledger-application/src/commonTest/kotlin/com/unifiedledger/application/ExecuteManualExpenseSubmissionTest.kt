package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ExecuteManualExpenseSubmissionTest {
    private val fixture = SubmissionFixture()

    @Test
    fun createdWrapsAsApplication() {
        val harness = SubmissionHarness(ReturningCommitPort(ConfirmedManualExpenseResult.Created(fixture.receipt)), SubmissionFixedReadPort(null))

        val result = harness.submit(fixture.input())

        assertEquals(
            ManualExpenseSubmissionResult.Application(
                ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.Created(fixture.receipt)),
            ),
            result,
        )
        assertTrue(harness.tracker.commitOnceInvoked)
    }

    @Test
    fun noChangeWrapsAsApplication() {
        val harness = SubmissionHarness(ReturningCommitPort(ConfirmedManualExpenseResult.NoChange(fixture.receipt)), SubmissionFixedReadPort(null))

        val result = harness.submit(fixture.input())

        assertEquals(
            ManualExpenseSubmissionResult.Application(
                ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.NoChange(fixture.receipt)),
            ),
            result,
        )
    }

    @Test
    fun requestIdentityConflictWrapsAsApplication() {
        val identity = ManualExpenseRequestIdentity(fixture.ledgerId, fixture.input().requestId)
        val harness = SubmissionHarness(ReturningCommitPort(ConfirmedManualExpenseResult.RequestIdentityConflict(identity)), SubmissionFixedReadPort(null))

        val result = harness.submit(fixture.input())

        assertEquals(
            ManualExpenseSubmissionResult.Application(
                ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.RequestIdentityConflict(identity)),
            ),
            result,
        )
    }

    @Test
    fun rejectedWrapsAsApplication() {
        val harness = SubmissionHarness(ReturningCommitPort(ConfirmedManualExpenseResult.Rejected(OrdinaryExpenseViolation.AmountMustBePositive)), SubmissionFixedReadPort(null))

        val result = harness.submit(fixture.input())

        assertEquals(
            ManualExpenseSubmissionResult.Application(
                ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.Rejected(OrdinaryExpenseViolation.AmountMustBePositive)),
            ),
            result,
        )
    }

    @Test
    fun commitOnceEntrySetsHandoffMarkerBeforeResult() {
        // P5-04.4 S4 T-H1: entering commitOnce sets the handoff marker before the delegate
        // returns, so an exception after the entry is post-handoff (classified as unknown, not
        // retryable), while an exception before the entry remains InfrastructureFailure.
        val recordingPort = RecordingCommitPort()
        val tracker = CommitOnceInvocationTracker(recordingPort)
        recordingPort.markerProbe = { tracker.commitOnceInvoked }
        val submission =
            ExecuteManualExpenseSubmission(
                executeSave =
                    ExecuteManualExpenseSave(
                        ExecuteConfirmedManualExpense(
                            commitPort = tracker,
                            idSource = SubmissionFailOnCallIdSource(),
                            createFormalTransaction = SubmissionFailOnCallTransactionFactory(),
                        ),
                    ),
                tracker = tracker,
                resolver = ResolveManualExpenseCommitStatus(SubmissionFixedReadPort(null)),
            )

        val result = submission.submit(fixture.input())

        // The delegate port returns its own receipt; T-H1's point is the handoff marker, not
        // the payload: the submission completes as an Application result.
        assertTrue(result is ManualExpenseSubmissionResult.Application)
        assertTrue(tracker.commitOnceInvoked)
        assertEquals(1, recordingPort.invocationCount)
        assertTrue(recordingPort.commitOnceInvokedAtEntry)
    }

    @Test
    fun invalidInputWrapsAsApplicationWithoutInvokingCommitOnce() {
        val recordingPort = RecordingCommitPort()
        val harness = SubmissionHarness(recordingPort, SubmissionFixedReadPort(null))

        val result = harness.submit(fixture.input().copy(amount = null))

        assertEquals(
            ManualExpenseSubmissionResult.Application(
                ManualExpenseSaveResult.InvalidInput(
                    setOf(ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT)),
                ),
            ),
            result,
        )
        assertEquals(0, recordingPort.invocationCount)
        assertFalse(harness.tracker.commitOnceInvoked)
    }

    @Test
    fun preSubmitZeroCallFailureIsInfrastructureFailure() {
        // wireTrackerIntoChain=false simulates a failure that happens before the save chain
        // reaches the tracker's commitOnce, so the submission is provably zero-call and
        // retryable with the same requestId.
        val harness = SubmissionHarness(ThrowingCommitPort(), SubmissionFixedReadPort(null), wireTrackerIntoChain = false)

        val result = harness.submit(fixture.input())

        assertEquals(ManualExpenseSubmissionResult.InfrastructureFailure, result)
        assertFalse(harness.tracker.commitOnceInvoked)
    }

    @Test
    fun postHandoffMatchingReceiptRecovers() {
        val record =
            ManualExpenseCommitRecord(
                ledgerId = fixture.ledgerId,
                requestId = fixture.input().requestId,
                snapshot = fixture.snapshot(),
                receipt = fixture.receipt,
                currentVersionId = TransactionVersionId("version-1"),
            )
        val harness = SubmissionHarness(ThrowingCommitPort(), SubmissionFixedReadPort(record))

        val result = harness.submit(fixture.input())

        assertEquals(ManualExpenseSubmissionResult.Recovered(fixture.receipt), result)
    }

    @Test
    fun postHandoffSnapshotConflictMapsToStableConflict() {
        val conflictingSnapshot = fixture.snapshot().copy(amount = Money.ofMinor(3_581L, fixture.cny))
        val record =
            ManualExpenseCommitRecord(
                ledgerId = fixture.ledgerId,
                requestId = fixture.input().requestId,
                snapshot = conflictingSnapshot,
                receipt = fixture.receipt,
                currentVersionId = TransactionVersionId("version-1"),
            )
        val harness = SubmissionHarness(ThrowingCommitPort(), SubmissionFixedReadPort(record))

        val result = harness.submit(fixture.input())

        assertEquals(
            ManualExpenseSubmissionResult.Application(
                ManualExpenseSaveResult.Executed(
                    ConfirmedManualExpenseResult.RequestIdentityConflict(
                        ManualExpenseRequestIdentity(fixture.ledgerId, fixture.input().requestId),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun postHandoffAbsentStaysUnknownCommit() {
        val harness = SubmissionHarness(ThrowingCommitPort(), SubmissionFixedReadPort(null))

        val result = harness.submit(fixture.input())

        assertEquals(ManualExpenseSubmissionResult.UnknownCommit, result)
    }

    @Test
    fun postHandoffUnavailableStaysUnknownCommit() {
        val harness = SubmissionHarness(ThrowingCommitPort(), SubmissionThrowingReadPort())

        val result = harness.submit(fixture.input())

        assertEquals(ManualExpenseSubmissionResult.UnknownCommit, result)
    }

    @Test
    fun secondSubmissionPreHandoffFailureIsNotContaminatedByPreviousHandoff() {
        // A completed first submission leaves a handoff trace on the tracker.
        val tracker = CommitOnceInvocationTracker(ReturningCommitPort(ConfirmedManualExpenseResult.Created(fixture.receipt)))
        val firstSubmission =
            ExecuteManualExpenseSubmission(
                executeSave =
                    ExecuteManualExpenseSave(
                        ExecuteConfirmedManualExpense(
                            commitPort = tracker,
                            idSource = SubmissionFailOnCallIdSource(),
                            createFormalTransaction = SubmissionFailOnCallTransactionFactory(),
                        ),
                    ),
                tracker = tracker,
                resolver = ResolveManualExpenseCommitStatus(SubmissionFixedReadPort(null)),
            )
        assertEquals(
            ManualExpenseSubmissionResult.Application(
                ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.Created(fixture.receipt)),
            ),
            firstSubmission.submit(fixture.input()),
        )
        assertTrue(tracker.commitOnceInvoked)

        // The second submission fails before the commit handoff (zero-call in this
        // submission). submit() starts with tracker.reset(), so the previous submission's
        // handoff trace cannot misclassify this failure as UnknownCommit.
        val secondSubmission =
            ExecuteManualExpenseSubmission(
                executeSave =
                    ExecuteManualExpenseSave(
                        ExecuteConfirmedManualExpense(
                            commitPort = ThrowingCommitPort(),
                            idSource = SubmissionFailOnCallIdSource(),
                            createFormalTransaction = SubmissionFailOnCallTransactionFactory(),
                        ),
                    ),
                tracker = tracker,
                resolver = ResolveManualExpenseCommitStatus(SubmissionFixedReadPort(null)),
            )

        val result = secondSubmission.submit(fixture.input())

        assertEquals(ManualExpenseSubmissionResult.InfrastructureFailure, result)
        assertFalse(tracker.commitOnceInvoked)
    }
}

private class SubmissionFixture {
    val ledgerId = LedgerId("ledger-a")
    val cny = CurrencyUnit("CNY", 2)
    val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    val receipt =
        ConfirmedExpenseReceipt(
            confirmationId = ConfirmationId("confirmation-1"),
            transactionId = TransactionId("tx-1"),
        )

    fun input() =
        ManualExpenseSaveInput(
            ledgerId = ledgerId,
            requestId = RequestId("request-p5-03"),
            amount = Money.ofMinor(3_580L, cny),
            categoryId = CategoryId("expense-category-breakfast"),
            paymentAccountId = AccountId("asset-payment-local"),
            occurredAt = occurredAt,
            note = "",
            confirmation = ExplicitManualSave,
        )

    fun snapshot() =
        ManualExpenseRequestSnapshot(
            ledgerId = ledgerId,
            amount = Money.ofMinor(3_580L, cny),
            categoryId = CategoryId("expense-category-breakfast"),
            paymentAccountId = AccountId("asset-payment-local"),
            occurredAt = occurredAt,
            note = "",
        )
}

private class SubmissionHarness(
    private val commitPort: ConfirmedManualExpenseCommitPort,
    private val readPort: LedgerCurrentStateReadPort,
    private val wireTrackerIntoChain: Boolean = true,
) {
    val tracker = CommitOnceInvocationTracker(commitPort)
    val submission =
        ExecuteManualExpenseSubmission(
            executeSave =
                ExecuteManualExpenseSave(
                    ExecuteConfirmedManualExpense(
                        commitPort = if (wireTrackerIntoChain) tracker else commitPort,
                        idSource = SubmissionFailOnCallIdSource(),
                        createFormalTransaction = SubmissionFailOnCallTransactionFactory(),
                    ),
                ),
            tracker = tracker,
            resolver = ResolveManualExpenseCommitStatus(readPort),
        )

    fun submit(input: ManualExpenseSaveInput): ManualExpenseSubmissionResult = submission.submit(input)
}

private class ReturningCommitPort(
    private val result: ConfirmedManualExpenseResult,
) : ConfirmedManualExpenseCommitPort {
    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult = result
}

private class ThrowingCommitPort : ConfirmedManualExpenseCommitPort {
    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult = throw IllegalStateException("database unavailable")
}

private class RecordingCommitPort : ConfirmedManualExpenseCommitPort {
    var invocationCount = 0
        private set

    /** Set by tests to probe the tracker's handoff marker at commit entry (P5-04.4 S4 T-H1). */
    var markerProbe: (() -> Boolean)? = null

    var commitOnceInvokedAtEntry = false
        private set

    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult {
        invocationCount += 1
        commitOnceInvokedAtEntry = markerProbe?.invoke() ?: false
        return ConfirmedManualExpenseResult.Created(
            ConfirmedExpenseReceipt(ConfirmationId("confirmation-unused"), TransactionId("tx-unused")),
        )
    }
}

private class SubmissionFixedReadPort(
    private val record: ManualExpenseCommitRecord?,
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = record

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = record
}

private class SubmissionThrowingReadPort : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = throw IllegalStateException("database unavailable")

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = throw IllegalStateException("database unavailable")
}

private class SubmissionFailOnCallIdSource : ConfirmedManualExpenseIdSource {
    override fun next(): ConfirmedManualExpenseCommitIds = error("The recording commit port must not request IDs")
}

private class SubmissionFailOnCallTransactionFactory : ConfirmedExpenseTransactionFactory {
    override fun create(
        request: ManualExpenseRequestSnapshot,
        ids: ConfirmedManualExpenseCommitIds,
    ): DomainResult<ConfirmedManualExpenseCommit> = error("The recording commit port must not create a transaction")
}
