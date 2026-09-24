package com.unifiedledger.ui

import com.unifiedledger.application.CatalogCommandReceipt
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogFailureCode
import com.unifiedledger.application.CatalogReceiptOutcome
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * P5-04.4 S5: pure JVM hold of the [P503HostCoordinator] host-wiring decision skeleton
 * (T-C1..T-C5, spec section 7). Verifies RetryRefresh -> refresh, RetrySubmission reusing the
 * same draft/requestId, Created -> exactly one authoritative refresh, UnknownCommit entry ->
 * exactly one read-only check with `lastCheckOutcome` + per-instance guards.
 */
class P503HostCoordinatorTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val categoryId = CategoryId("expense-category-breakfast")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId = RequestId("request-uuid-v7-1")
    private val draft = ManualExpenseDraft(paymentAccountId, categoryId, "35.80", occurredAt)

    @Test
    fun duplicateConfirmAndRetrySubmitOncePerLiveState() {
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val confirmation = P503AppState.AwaitingConfirmation(draft, requestId)
        var state: P503AppState = confirmation
        var submissions = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { submittedDraft, submittedRequestId ->
                    assertSame(draft, submittedDraft)
                    assertEquals(requestId, submittedRequestId)
                    assertIs<P503AppState.Submitting>(state)
                    submissions += 1
                },
                onCheck = { _, _ -> error("unused") },
            )

        fun dispatch(event: P503UiEvent) {
            state = reducer.reduce(state, event)
        }
        repeat(2) {
            dispatchCurrentP503Action(confirmation, state, { P503UiEvent.Confirm }, ::dispatch) {
                submissions += 1
            }
        }
        assertEquals(1, submissions)
        dispatch(P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.InfrastructureFailure))
        val failure = assertIs<P503AppState.InfrastructureFailure>(state)
        repeat(2) {
            dispatchCurrentP503Action(failure, state, { P503UiEvent.RetrySubmission }, ::dispatch) {
                coordinator.retrySubmission(failure)
            }
        }
        assertEquals(2, submissions)
        dispatch(P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.InfrastructureFailure))
        val nextFailure = assertIs<P503AppState.InfrastructureFailure>(state)
        dispatchCurrentP503Action(failure, state, { P503UiEvent.RetrySubmission }, ::dispatch) { error("stale retry") }
        assertSame(nextFailure, state)
        dispatchCurrentP503Action(nextFailure, state, { P503UiEvent.RetrySubmission }, ::dispatch) {
            coordinator.retrySubmission(nextFailure)
        }
        assertEquals(3, submissions)
    }

    @Test
    fun cancelledConfirmationCannotSubmitOrCancelANewerIntent() {
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val confirmation = P503AppState.AwaitingConfirmation(draft, requestId)
        var state: P503AppState = confirmation

        fun dispatch(event: P503UiEvent) {
            state = reducer.reduce(state, event)
        }
        dispatchCurrentP503Action(confirmation, state, { P503UiEvent.Cancel }, ::dispatch)
        val editing = assertIs<P503AppState.Editing>(state)
        dispatchCurrentP503Action(confirmation, state, { P503UiEvent.Confirm }, ::dispatch) { error("cancelled submit") }
        dispatchCurrentP503Action(confirmation, state, { P503UiEvent.Cancel }, ::dispatch)
        assertSame(editing, state)
        state = confirmation.copy()
        val newConfirmation = state
        dispatchCurrentP503Action(confirmation, state, { P503UiEvent.Confirm }, ::dispatch) { error("old submit") }
        dispatchCurrentP503Action(confirmation, state, { P503UiEvent.Cancel }, ::dispatch)
        assertSame(newConfirmation, state)
    }

    @Test
    fun duplicateContinueAllocatesOneRequestAndStaleContinueAllocatesNone() {
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val editing = P503AppState.Editing(draft, null)
        var state: P503AppState = editing
        var allocations = 0
        repeat(2) {
            dispatchCurrentP503Action(
                editing,
                state,
                {
                    allocations += 1
                    P503UiEvent.Continue(requestId)
                },
                { state = reducer.reduce(state, it) },
            )
        }
        assertEquals(1, allocations)
        assertEquals(requestId, assertIs<P503AppState.AwaitingConfirmation>(state).requestId)
        state = editing.copy(draft = draft.copy(amountText = "42.00"))
        dispatchCurrentP503Action(editing, state, { error("stale allocation") }, { error("stale dispatch") })
    }

    @Test
    fun retryRefreshInvokesRefreshCallback() {
        var refreshCalls = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshCalls += 1 },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )
        val readFailure = P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)

        assertEquals(HostAction.RetryRefresh, coordinator.retryRefresh(readFailure))
        assertEquals(1, refreshCalls)
    }

    @Test
    fun retrySubmissionReusesSameRequestId() {
        var receivedDraft: TypedEntryDraft? = null
        var receivedRequestId: RequestId? = null
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { draft, requestId ->
                    receivedDraft = draft
                    receivedRequestId = requestId
                },
                onCheck = { _, _ -> error("unused") },
            )
        val submissionFailure =
            P503AppState.InfrastructureFailure(
                context = InfrastructureFailureContext.SUBMISSION,
                draft = draft,
                requestId = requestId,
            )

        assertEquals(
            HostAction.RetrySubmission(draft, requestId),
            coordinator.retrySubmission(submissionFailure),
        )
        assertEquals(draft, receivedDraft)
        assertEquals(requestId, receivedRequestId)
    }

    @Test
    fun createdTriggersAuthoritativeRefreshExactlyOnce() {
        var refreshCalls = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshCalls += 1 },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )

        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshCalls)

        // Same instance re-evaluated does not re-trigger.
        assertNull(coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshCalls)
    }

    @Test
    fun unknownCommitEntryTriggersReadOnlyCheckExactlyOnceThenGuardBlocks() {
        var checkCount = 0
        var receivedDraft: TypedEntryDraft? = null
        var receivedRequestId: RequestId? = null
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { draft, requestId ->
                    checkCount += 1
                    receivedDraft = draft
                    receivedRequestId = requestId
                },
            )
        val entry = P503AppState.UnknownCommit(draft, requestId)

        assertEquals(HostAction.UnknownCheck(draft, requestId), coordinator.decide(entry))
        assertEquals(1, checkCount)
        assertEquals(draft, receivedDraft)
        assertEquals(requestId, receivedRequestId)

        // Same instance re-evaluated does not re-check.
        assertNull(coordinator.decide(entry))
        assertEquals(1, checkCount)

        // After ABSENT is recorded the guard blocks regardless of a different instance.
        val absent = P503AppState.UnknownCommit(draft, requestId, lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
        assertNull(coordinator.decide(absent))
        assertEquals(1, checkCount)
        val unavailable = P503AppState.UnknownCommit(draft, requestId, lastCheckOutcome = UnknownCommitCheckOutcome.UNAVAILABLE)
        assertNull(coordinator.decide(unavailable))
        assertEquals(1, checkCount)
    }

    @Test
    fun unknownCommitManualRetryStaysGuardedByIdempotent() {
        var checkCount = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> checkCount += 1 },
            )
        val entry = P503AppState.UnknownCommit(draft, requestId)

        // Manual re-check fires for the current instance.
        assertEquals(HostAction.UnknownCheck(draft, requestId), coordinator.retryCommitStatusCheck(entry))
        assertEquals(1, checkCount)

        // The per-instance marker prevents a duplicate manual trigger on the same instance.
        assertNull(coordinator.retryCommitStatusCheck(entry))
        assertEquals(1, checkCount)

        // The automatic decide path is now guarded too by the per-instance marker (still NONE).
        assertNull(coordinator.decide(entry))
        assertEquals(1, checkCount)

        // Recording an outcome keeps the automatic guard from re-triggering.
        val absent = P503AppState.UnknownCommit(draft, requestId, lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
        assertNull(coordinator.decide(absent))
        assertEquals(1, checkCount)
    }

    @Test
    fun onlySuccessfulCatalogCommandsRefreshTheReadModel() {
        // R1: Accepted/NoChange refresh HOME's read model; Rejected/Conflict never do.
        val accepted =
            CatalogCommandResult.Accepted(
                CatalogCommandReceipt(CatalogRequestId("r1"), CatalogReceiptOutcome.ACCEPTED, 2L),
            )
        val noChange =
            CatalogCommandResult.NoChange(
                CatalogCommandReceipt(CatalogRequestId("r2"), CatalogReceiptOutcome.NO_CHANGE, 2L),
            )
        assertEquals(true, shouldRefreshReadModelAfterCatalogCommand(accepted))
        assertEquals(true, shouldRefreshReadModelAfterCatalogCommand(noChange))
        assertEquals(false, shouldRefreshReadModelAfterCatalogCommand(CatalogCommandResult.Rejected(CatalogFailureCode.CATALOG_NAME_CONFLICT)))
        assertEquals(false, shouldRefreshReadModelAfterCatalogCommand(CatalogCommandResult.Conflict(CatalogFailureCode.CATALOG_VERSION_CONFLICT)))
    }

    @Test
    fun catalogOutcomeIsSuppressedOnceTheReadRefreshFailed() {
        // F1 (N-5): a command can succeed while the follow-up read re-query fails into
        // InfrastructureFailure(READ), which has no transition for the management events. The
        // host guard must drop them (no throw), leaving the recoverable read-failure page up.
        val readFailure = P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
        var dispatched = 0

        val published =
            dispatchCatalogOutcomeIfOverview(
                readFailure,
                P503UiEvent.CatalogSnapshotRefreshed(
                    CatalogSnapshotView(catalogVersion = 2L, manageableAccounts = emptyList(), categories = emptyList()),
                ),
            ) { dispatched += 1 }

        assertEquals(false, published)
        assertEquals(0, dispatched)

        // A reducer's direct dispatch would be an unhandled (state, event) pair - the reason the
        // guard exists. Lock that down so a future reducer change stays deliberate.
        assertFailsWith<IllegalStateException> {
            P503ReducerImpl(ParseManualExpenseAmount(), cny)
                .reduce(readFailure, P503UiEvent.CatalogCommandCompleted(CatalogCommandResult.Accepted(CatalogCommandReceipt(CatalogRequestId("r3"), CatalogReceiptOutcome.ACCEPTED, 2L)), CatalogSnapshotView(2L, emptyList(), emptyList())))
        }
    }

    @Test
    fun catalogOutcomeIsStillPublishedWhileOnTheOverview() {
        val overview = P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-local-test"), emptyList(), emptyList()))
        var received: P503UiEvent? = null
        val event = P503UiEvent.CatalogSnapshotRefreshed(CatalogSnapshotView(3L, emptyList(), emptyList()))

        val published = dispatchCatalogOutcomeIfOverview(overview, event) { received = it }

        assertEquals(true, published)
        assertEquals(event, received)
    }

    @Test
    fun theP705ManualRecheckRunsAtMostOnceAtATimeAndIsReusableAfterCompletion() {
        // V-19 (D-173): the manual re-check single flight. A double tap must not overlap two
        // read-only resolves of the same surface; unlike the P7-02 per-instance guard the SAME
        // surface may be re-checked repeatedly (it stays unknown), so only a concurrent double-fire
        // is dropped and the marker releases on each landing.
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> error("unused") },
                onCheck = { _, _ -> error("unused") },
            )
        var started = 0
        assertEquals(true, coordinator.recheckP705CommitOnce { started += 1 })
        assertEquals(1, started)
        // A concurrent duplicate while the first is in flight is dropped.
        assertEquals(false, coordinator.recheckP705CommitOnce { started += 1 })
        assertEquals(1, started)
        coordinator.p705RecheckCompleted()
        // The marker released, so the same surface is re-checkable again.
        assertEquals(true, coordinator.recheckP705CommitOnce { started += 1 })
        assertEquals(2, started)
        coordinator.p705RecheckCompleted()
        assertEquals(true, coordinator.recheckP705CommitOnce { started += 1 })
        assertEquals(3, started)
    }
}
