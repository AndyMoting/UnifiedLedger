package com.unifiedledger.ui

import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualRefundCandidate
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.application.ManualRefundReceiptCommitResolution
import com.unifiedledger.application.ManualRefundReceiptRecord
import com.unifiedledger.application.ManualRefundReceiptSubmissionResult
import com.unifiedledger.application.ManualRefundRejectionCode
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RefundReceiptDraft
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P8-03 i2b (D-312) — the refund-receipt draft in the shared reducer.
 *
 * V-1/V-3: the Continue gate advances a complete refund and its confirmation page carries the
 * INHERITED category resolved from the candidate page; V-18: "record again" from a refund opens an
 * EMPTY expense draft; the two result families' four-outcome mappings; and the candidate event
 * family's frozen effects (editor-only, everything else absorbed).
 */
class P803RefundEntryReducerTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val ledgerId = LedgerId("ledger-local-test")
    private val emptyState = LedgerCurrentState(ledgerId, emptyList(), emptyList())
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId = RequestId("request-refund-1")
    private val ledgerClock = LedgerClock { Instant.parse("2026-09-11T00:00:00Z") }
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny, ledgerClock)
    private val originalTransactionId = TransactionId("tx-original")
    private val destinationAccountId = AccountId("asset-destination")

    private fun refundDraft(
        original: TransactionId? = originalTransactionId,
        destination: AccountId? = destinationAccountId,
        amountText: String = "35.80",
        occurredAtInstant: Instant? = occurredAt,
    ) = RefundReceiptDraft(
        originalTransactionId = original,
        destinationAccountId = destination,
        amountText = amountText,
        occurredAt = occurredAtInstant,
        note = "lunch",
    )

    private fun candidate(transactionId: TransactionId = TransactionId("tx-original")) =
        ManualRefundCandidate(
            transactionId = transactionId,
            occurredAt = occurredAt,
            categoryId = CategoryId("expense-leaf"),
            categoryName = "餐饮",
            structuralCap = Money.ofMinor(10_000L, cny),
            remaining = Money.ofMinor(8_000L, cny),
        )

    private fun loadedCandidates(vararg candidates: ManualRefundCandidate) = ManualRefundCandidatesResult.Loaded(candidates.toList(), hasMore = false)

    // ---- V-1/V-3: the Continue gate and the confirmation page ----

    @Test
    fun theContinueGateAdvancesACompleteRefundWithTheInheritedCategoryAndOriginalLine() {
        val editing =
            P503AppState.Editing(
                draft = refundDraft(),
                requestId = null,
                overview = emptyState,
                refundCandidates = loadedCandidates(candidate()),
            )

        val awaiting =
            assertIs<P503AppState.AwaitingConfirmation>(
                reducer.reduce(
                    editing,
                    P503UiEvent.Continue(requestId = requestId, paymentAccountLabel = "本地账户", categoryLabel = ""),
                ),
            )

        // The category is the candidate's current name — the user never picked it (spec 2.4).
        assertEquals("餐饮", awaiting.categoryLabel)
        assertEquals("2026-01-15 · 餐饮", awaiting.originalTransactionLabel)
        assertEquals("本地账户", awaiting.paymentAccountLabel)
        assertEquals(requestId, awaiting.requestId)
    }

    @Test
    fun anUnresolvedOriginalFallsBackToItsIdAndAnEmptyCategory() {
        val editing =
            P503AppState.Editing(
                draft = refundDraft(),
                requestId = null,
                overview = emptyState,
                refundCandidates = loadedCandidates(),
            )

        val awaiting =
            assertIs<P503AppState.AwaitingConfirmation>(
                reducer.reduce(editing, P503UiEvent.Continue(requestId = requestId, categoryLabel = "")),
            )

        assertEquals("", awaiting.categoryLabel)
        assertEquals("tx-original", awaiting.originalTransactionLabel)
    }

    @Test
    fun anIncompleteRefundDraftStaysInTheEditorWithItsRequestId() {
        val editing = P503AppState.Editing(draft = refundDraft(original = null, destination = null), requestId = null, overview = emptyState)

        val stayed = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.Continue(requestId = requestId)))

        // Field errors retain both the input and the already-allocated request id.
        assertEquals(requestId, stayed.requestId)
        assertEquals(editing.draft, stayed.draft)
    }

    // ---- V-18: "record again" from a refund opens an empty expense draft ----

    @Test
    fun saveAndRecordAgainFromARefundOpensAnEmptyExpenseDraft() {
        val overview =
            P503AppState.OverviewEmpty(
                state = emptyState,
                retainedIntent =
                    RetainedEntryIntent(
                        type = EntryType.REFUND_RECEIPT,
                        amountText = "35.80",
                        paymentAccountId = destinationAccountId,
                        categoryId = CategoryId("expense-leaf"),
                        note = "lunch",
                        occurredAt = occurredAt,
                        originTab = P503Tab.HOME,
                    ),
            )

        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.SaveAndRecordAgain()))

        val draft = assertIs<ExpenseDraft>(editing.draft)
        // V-18: no inherited category, no original primary account, no arrival account, empty
        // amount and note, occurrence at the current clock instant (whole seconds).
        assertNull(draft.categoryId)
        assertNull(draft.paymentAccountId)
        assertEquals("", draft.amountText)
        assertEquals("", draft.note)
        assertEquals(Instant.parse("2026-09-11T00:00:00Z"), draft.occurredAt)
        assertNull(editing.requestId)
    }

    // ---- the shared result families' refund members ----

    @Test
    fun everyRefundSubmissionOutcomeMapsToItsState() {
        val draft = refundDraft()
        val receipt = ManualRefundReceiptRecord(relationshipId = "relationship-1", refundTransactionId = TransactionId("tx-refund"))

        fun submit(result: ManualRefundReceiptSubmissionResult) =
            reducer.reduce(
                P503AppState.Submitting(draft, requestId),
                P503UiEvent.SubmissionResult(ManualEntrySubmissionResult.RefundReceipt(result)),
            )

        assertEquals(P503AppState.Created, submit(ManualRefundReceiptSubmissionResult.Created(receipt)))
        // The house replay shape: an exact replay is the determinate no-change surface, not a fresh
        // success (the other five families map their replay member the same way).
        assertEquals(P503AppState.NoChange, submit(ManualRefundReceiptSubmissionResult.Replayed(receipt)))
        assertEquals(P503AppState.Recovered, submit(ManualRefundReceiptSubmissionResult.Recovered(receipt)))
        assertEquals(P503AppState.UnknownCommit(draft, requestId), submit(ManualRefundReceiptSubmissionResult.UnknownCommit))
        assertEquals(
            P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, draft, requestId),
            submit(ManualRefundReceiptSubmissionResult.InfrastructureFailure),
        )

        val rejected = submit(ManualRefundReceiptSubmissionResult.Rejected(ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING))
        val rejectedState = assertIs<P503AppState.DomainRejected>(rejected)
        // D9: the typed closed-set member rides the state and the screen renders its sentence.
        assertEquals(ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING, rejectedState.refundRejectionCode)
        assertEquals("退款金额超过剩余可退金额", refundRejectionText(ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING))

        // Spec section 2.6: the request-identity conflict is the same stable conflict screen the
        // read-back conflict lands, not a re-entry rejection page.
        val conflict = submit(ManualRefundReceiptSubmissionResult.Rejected(ManualRefundRejectionCode.REFUND_REQUEST_CONFLICT))
        assertIs<P503AppState.RequestIdentityConflict>(conflict)
    }

    @Test
    fun everyRefundCommitResolutionOutcomeMapsToItsState() {
        val draft = refundDraft()
        val unknown = P503AppState.UnknownCommit(draft, requestId, emptyState, P503Tab.HOME)
        val receipt = ManualRefundReceiptRecord(relationshipId = "relationship-1", refundTransactionId = TransactionId("tx-refund"))

        fun resolve(resolution: ManualRefundReceiptCommitResolution) = reducer.reduce(unknown, P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.RefundReceipt(resolution)))

        assertEquals(P503AppState.Recovered, resolve(ManualRefundReceiptCommitResolution.MatchingReceipt(receipt)))

        val conflict = assertIs<P503AppState.RequestIdentityConflict>(resolve(ManualRefundReceiptCommitResolution.SnapshotConflict))
        assertEquals(draft, conflict.draft)
        assertEquals(requestId, conflict.requestId)

        // Absent proves the row never landed, so the editor returns with the SAME request id
        // retained (a retry must reuse it — a new id would bypass idempotency).
        val backToEditor = assertIs<P503AppState.Editing>(resolve(ManualRefundReceiptCommitResolution.Absent))
        assertEquals(draft, backToEditor.draft)
        assertEquals(requestId, backToEditor.requestId)
        assertNull(backToEditor.refundCandidates, "the returned editor re-reads its candidate page")

        val unavailable = assertIs<P503AppState.UnknownCommit>(resolve(ManualRefundReceiptCommitResolution.Unavailable))
        assertEquals(UnknownCommitCheckOutcome.UNAVAILABLE, unavailable.lastCheckOutcome)
        assertEquals(requestId, unavailable.requestId)
    }

    // ---- the candidate event family ----

    @Test
    fun theCandidateEventsWriteTheRefundDraftOnly() {
        val editing = P503AppState.Editing(draft = refundDraft(original = null), requestId = null, overview = emptyState)

        val selected =
            assertIs<P503AppState.Editing>(
                reducer.reduce(editing, P503UiEvent.SelectRefundOriginal(TransactionId("tx-picked"))),
            )
        assertEquals(TransactionId("tx-picked"), assertIs<RefundReceiptDraft>(selected.draft).originalTransactionId)

        val loaded =
            assertIs<P503AppState.Editing>(
                reducer.reduce(editing, P503UiEvent.RefundCandidatesLoaded(loadedCandidates(candidate()))),
            )
        assertEquals(loadedCandidates(candidate()), loaded.refundCandidates)

        val withFailure =
            assertIs<P503AppState.Editing>(
                reducer.reduce(editing, P503UiEvent.RefundCandidatesLoaded(ManualRefundCandidatesResult.Unavailable)),
            )
        val retried = assertIs<P503AppState.Editing>(reducer.reduce(withFailure, P503UiEvent.RetryRefundCandidates))
        assertNull(retried.refundCandidates, "a retry clears the payload back to the loading window")
        assertEquals(1, retried.refundCandidatesAttempt)
    }

    @Test
    fun theCandidateEventsAreAbsorbedByANonRefundDraft() {
        val expense = ExpenseDraft(AccountId("asset-a"), CategoryId("expense-leaf"), "35.80", occurredAt)
        val editing = P503AppState.Editing(draft = expense, requestId = null, overview = emptyState)

        assertEquals(editing, reducer.reduce(editing, P503UiEvent.SelectRefundOriginal(TransactionId("tx-picked"))))
        assertEquals(editing, reducer.reduce(editing, P503UiEvent.RefundCandidatesLoaded(loadedCandidates(candidate()))))
        assertEquals(editing, reducer.reduce(editing, P503UiEvent.RetryRefundCandidates))
    }

    @Test
    fun theCandidateEventsAreAbsorbedOutsideTheEditor() {
        val states =
            listOf(
                P503AppState.Ready(),
                P503AppState.OverviewEmpty(emptyState),
                P503AppState.AwaitingConfirmation(refundDraft(), requestId),
                P503AppState.Submitting(refundDraft(), requestId),
                P503AppState.Created,
                P503AppState.NoChange,
                P503AppState.Recovered,
                P503AppState.UnknownCommit(refundDraft(), requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, refundDraft(), requestId),
            )
        val events =
            listOf<P503UiEvent>(
                P503UiEvent.SelectRefundOriginal(TransactionId("tx-picked")),
                P503UiEvent.RefundCandidatesLoaded(loadedCandidates(candidate())),
                P503UiEvent.RetryRefundCandidates,
            )
        for (state in states) {
            for (event in events) {
                assertEquals(state, reducer.reduce(state, event), "absorbed $event in $state")
            }
        }
    }

    @Test
    fun theReentryScreensKeepTheRefundInputAndRouteTheCandidateIntents() {
        val screens: List<P503AppState> =
            listOf(
                P503AppState.RequestIdentityConflict(refundDraft(), requestId),
                P503AppState.DomainRejected(refundDraft(), requestId),
            )
        for (screen in screens) {
            val selected =
                assertIs<P503AppState.Editing>(
                    reducer.reduce(screen, P503UiEvent.SelectRefundOriginal(TransactionId("tx-picked"))),
                )
            assertEquals(TransactionId("tx-picked"), assertIs<RefundReceiptDraft>(selected.draft).originalTransactionId)
            assertEquals(requestId, selected.requestId, "the re-entry keeps the request id")
            val retried = assertIs<P503AppState.Editing>(reducer.reduce(screen, P503UiEvent.RetryRefundCandidates))
            assertNull(retried.refundCandidates, "a retry returns to the editor and re-reads")
        }
    }
}
