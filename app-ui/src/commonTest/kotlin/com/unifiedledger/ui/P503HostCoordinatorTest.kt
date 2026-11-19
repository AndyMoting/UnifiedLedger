package com.unifiedledger.ui

import com.unifiedledger.application.CatalogCommandReceipt
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogFailureCode
import com.unifiedledger.application.CatalogReceiptOutcome
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ResolvedPeriod
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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

    // ------------------------------------------------------------------ P7-08 08.D (A07) refresh 触发向量

    /**
     * P7-08 08.D (P708-A07; spec section 5.5 row 2): EVERY determinate formal-surface landing fires
     * the authoritative refresh exactly once and NO typed failure fires it. The production `P503App`
     * call sites delegate to the four seams below (the D-156 "JVM-assertable seam" precedent — the
     * composable hop itself stays uncovered, no Compose harness exists); each seam's branch is
     * pinned here so a changed trigger matrix turns a vector red.
     *
     * P8-02 i2a retired the filter re-request callback these seams used to carry (D7-3), so the
     * seams no longer arm anything; these vectors assert only the refresh branch that remains.
     */
    private class RefreshProbe {
        var refreshes = 0
    }

    private fun refreshProbeCoordinator(probe: RefreshProbe): P503HostCoordinator =
        P503HostCoordinator(
            onRefresh = { probe.refreshes += 1 },
            onSubmit = { _, _ -> },
            onCheck = { _, _ -> },
        )

    private fun correctionCreated() =
        CorrectTransactionVersionResult.Created(
            com.unifiedledger.application.TransactionCorrectionReceipt(
                confirmationId = com.unifiedledger.application.ConfirmationId("conf-a07"),
                transactionId = com.unifiedledger.domain.TransactionId("tx-a07"),
                versionId = com.unifiedledger.domain.TransactionVersionId("tx-a07-v2"),
                expectedCurrentVersionId = com.unifiedledger.domain.TransactionVersionId("tx-a07-v1"),
            ),
        )

    private fun voidCreated() =
        VoidTransactionResult.Created(
            com.unifiedledger.application.TransactionVoidReceipt(
                confirmationId = com.unifiedledger.application.ConfirmationId("conf-a07-void"),
                transactionId = com.unifiedledger.domain.TransactionId("tx-a07"),
                factId = "fact-a07",
                factKind = com.unifiedledger.domain.TransactionVoidFactKind.VOID,
            ),
        )

    @Test
    fun a07ACorrectionLandingRefreshesOnlyOnADeterminateSuccess() {
        val probe = RefreshProbe()
        val coordinator = refreshProbeCoordinator(probe)
        refreshAndArmAfterP705CommitLanding(correctionCreated(), coordinator)
        assertEquals(1, probe.refreshes)
        // A typed rejection writes nothing: no refresh.
        refreshAndArmAfterP705CommitLanding(CorrectTransactionVersionResult.StaleCurrentVersion, coordinator)
        assertEquals(1, probe.refreshes)
    }

    @Test
    fun a07VoidAndRestoreLandingsShareTheSameRefreshGate() {
        val probe = RefreshProbe()
        val coordinator = refreshProbeCoordinator(probe)
        refreshAndArmAfterVoidRestoreCommit(voidCreated(), coordinator)
        assertEquals(1, probe.refreshes)
        refreshAndArmAfterVoidRestoreCommit(VoidTransactionResult.Rejected(com.unifiedledger.domain.P705FailureCode.P705_TRANSACTION_VOIDED), coordinator)
        assertEquals(1, probe.refreshes)
    }

    @Test
    fun a07AnAnnotationEditLandingRefreshesAndARejectionDoesNot() {
        val probe = RefreshProbe()
        val coordinator = refreshProbeCoordinator(probe)
        refreshAndArmAfterAnnotationEdit(TransactionAnnotationEditOutcome.Accepted(2L), coordinator)
        // onP705EffectiveSurfaceChanged fires the authoritative refresh exactly once.
        assertEquals(1, probe.refreshes)
        refreshAndArmAfterAnnotationEdit(TransactionAnnotationEditOutcome.Rejected("AnnotationRevisionConflict"), coordinator)
        assertEquals(1, probe.refreshes)
    }

    /**
     * P7-09 09.C (spec section 6.4; D-225 Q20; QUAL-008): the annotation-edit landing is a fourth
     * determinate-success route into the SAME refresh chain (it delegates to
     * [P503HostCoordinator.onP705EffectiveSurfaceChanged] on Accepted/NoChange), so it must
     * invalidate every widget exactly once and NOT on a rejection. Pins the branch the QUAL-008
     * finding called out as untested.
     *
     * ABLATION (承重): remove the invalidation call from `onP705EffectiveSurfaceChanged` (which this
     * route delegates to) and this test goes RED on the Accepted assertion.
     */
    @Test
    fun anAnnotationEditAcceptedLandingInvalidatesTheWidgetAndARejectionDoesNot() {
        var invalidations = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                widgetInvalidation = WidgetInvalidationObserver { invalidations += 1 },
            )
        refreshAndArmAfterAnnotationEdit(TransactionAnnotationEditOutcome.Accepted(3L), coordinator)
        assertEquals(1, invalidations)
        refreshAndArmAfterAnnotationEdit(TransactionAnnotationEditOutcome.NoChange(3L), coordinator)
        assertEquals(2, invalidations)
        refreshAndArmAfterAnnotationEdit(TransactionAnnotationEditOutcome.Rejected("AnnotationRevisionConflict"), coordinator)
        assertEquals(2, invalidations)
    }

    @Test
    fun a07AnAllTerminalImportBatchLandingRefreshesExactlyOnce() {
        val probe = RefreshProbe()
        val coordinator = refreshProbeCoordinator(probe)
        val confirmedItem =
            com.unifiedledger.application.ImportReceipt(
                requestId = com.unifiedledger.application.ImportRequestId("req-a07"),
                sourceId = null,
                evidenceId = null,
                candidateId = com.unifiedledger.application.ImportCandidateId("candidate-a07"),
                confirmationId = null,
                transactionId = null,
            )
        val allTerminal =
            P503AppState.OverviewEmpty(
                LedgerCurrentState(LedgerId("ledger-a07"), emptyList(), emptyList()),
                importReview =
                    com.unifiedledger.ui.ImportReviewView(
                        batchResult =
                            com.unifiedledger.ui.ImportBatchResultSummary(
                                confirmedAt = "2026-03-05T02:00:00Z",
                                items =
                                    listOf(
                                        com.unifiedledger.ui.ImportBatchResultItem(
                                            item = com.unifiedledger.ui.ImportBatchItem(com.unifiedledger.application.ImportCandidateId("candidate-a07"), com.unifiedledger.application.ImportRequestId("req-a07")),
                                            outcome =
                                                com.unifiedledger.ui.ImportBatchItemOutcome
                                                    .Confirmed(confirmedItem),
                                        ),
                                    ),
                            ),
                    ),
            )
        armAfterImportBatchConfirmation(allTerminal, coordinator)
        // onImportBatchConfirmed fired the authoritative refresh exactly once.
        assertEquals(1, probe.refreshes)
        // A landing without the all-terminal state refreshes nothing (the mid-batch discipline).
        armAfterImportBatchConfirmation(P503AppState.Created, coordinator)
        assertEquals(1, probe.refreshes)
    }

    // ---------------------------------------------------------- P7-09 09.C widget invalidation leg

    /**
     * P7-09 09.C (spec section 6.4 first leg; D-225 Q20): a KNOWN formal write/restore success
     * invalidates every widget EXACTLY ONCE. The leg reuses the existing success judgements rather
     * than a second rule, so it is asserted at the two coordinator hooks they already gate:
     * [P503HostCoordinator.decide]'s Created/NoChange/Recovered branch (manual entry),
     * [P503HostCoordinator.onP705EffectiveSurfaceChanged] (correction/void/restore) and
     * [P503HostCoordinator.onImportBatchConfirmed] (terminal import batch).
     *
     * ABLATION (承重): delete the `widgetInvalidation.invalidateAllWidgets()` line from any of the
     * three hooks and this test goes RED — the counter stops advancing there.
     */
    @Test
    fun aConfirmedWriteOrRestoreLandingInvalidatesEveryWidgetExactlyOnce() {
        var refreshes = 0
        var invalidations = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                widgetInvalidation = WidgetInvalidationObserver { invalidations += 1 },
            )

        // Manual-entry determinate success: the decide() Created/NoChange/Recovered branch.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshes)
        assertEquals(1, invalidations)
        // The same instance is served once; a re-evaluation invalidates nothing.
        assertNull(coordinator.decide(P503AppState.Created))
        assertEquals(1, invalidations)

        // Financial correction / void / restore determinate landing.
        coordinator.onP705EffectiveSurfaceChanged()
        assertEquals(2, invalidations)

        // A fully terminal import batch confirmation.
        coordinator.onImportBatchConfirmed()
        assertEquals(3, invalidations)
    }

    /**
     * P7-09 09.C (spec section 6.4; D-225 Q20): the invalidation leg must NEVER fire on a non-effect —
     * a typed rejection, a stale CAS, an identity conflict, an absorbed (non-all-terminal) batch, or a
     * pure parse candidate / idle overview. The `onRefresh` callback is wired to `error(...)` so any
     * accidental call is a hard failure, and the widget counter must stay at zero.
     *
     * ABLATION (承重): move the `widgetInvalidation.invalidateAllWidgets()` call OUTSIDE its success
     * branch (e.g. into the `else` of [P503HostCoordinator.decide]) and this test goes RED.
     */
    @Test
    fun aRejectedStaleConflictOrPureParseLandingNeverInvalidatesTheWidget() {
        var invalidations = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("no refresh on a non-success") },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                widgetInvalidation = WidgetInvalidationObserver { invalidations += 1 },
            )

        // The shared success gate rejects every non-effect: nothing refreshes and nothing invalidates.
        refreshAndArmAfterP705CommitLanding(CorrectTransactionVersionResult.StaleCurrentVersion, coordinator)
        refreshAndArmAfterP705CommitLanding(
            CorrectTransactionVersionResult.Rejected(com.unifiedledger.domain.P705FailureCode.P705_TRANSACTION_VOIDED),
            coordinator,
        )
        refreshAndArmAfterVoidRestoreCommit(
            VoidTransactionResult.Rejected(com.unifiedledger.domain.P705FailureCode.P705_TRANSACTION_VOIDED),
            coordinator,
        )
        refreshAndArmAfterAnnotationEdit(TransactionAnnotationEditOutcome.Rejected("AnnotationRevisionConflict"), coordinator)
        // A landing without the all-terminal batch state refreshes (and invalidates) nothing.
        armAfterImportBatchConfirmation(P503AppState.Created, coordinator)
        // A pure parse candidate / idle overview is not a trigger state at all.
        assertNull(
            coordinator.decide(
                P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-widget-none"), emptyList(), emptyList())),
            ),
        )
        assertEquals(0, invalidations)
    }

    /**
     * P7-09 09.C (spec section 6.4; D-225 Q20; QUAL-001): the widget fan-out is a NON-AUTHORITATIVE
     * side effect, so a throw inside it must not skip the authoritative bookkeeping the invalidation
     * rides beside — the P7-03 (e) monthly re-request arm, the P7-07 budget arm and the
     * `HostAction.RefreshAfterResult` return — nor escape into the caller. An observer that always
     * throws is injected; every hook must still complete its authoritative work.
     *
     * ABLATION (承重): drop the `try/catch` in `invalidateWidgetsBestEffort` and the throwing observer
     * propagates out of `decide`/`onP705EffectiveSurfaceChanged`/`onImportBatchConfirmed`, turning
     * this test RED at the first throwing call.
     */
    @Test
    fun aThrowingWidgetInvalidationNeverBreaksTheAuthoritativeRefreshChain() {
        var refreshes = 0
        var budgetRequests = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onBudgetMonthRequest = { budgetRequests += 1 },
                widgetInvalidation = WidgetInvalidationObserver { error("widget fan-out failed") },
            )
        // Load the budget surface so the arm actually arms (budgetEverRequested).
        coordinator.decideBudgetMonth(P503AppState.OverviewEmpty(LedgerCurrentState(LedgerId("ledger-widget-throw"), emptyList(), emptyList())))

        // decide's success branch: the action is still returned and the refresh still fired.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshes)

        // onP705: the refresh fired and the budget re-request arm completed despite the throw.
        coordinator.onP705EffectiveSurfaceChanged()
        assertEquals(2, refreshes)
        // The armed re-request is consumed by a landing exactly once (the arm was not skipped).
        coordinator.consumeMonthlyReRequestAfterRefresh(P503AppState.Created)
        coordinator.consumeBudgetMonthReRequestAfterRefresh(P503AppState.Created)
        assertEquals(2, budgetRequests, "the budget re-request arm must survive a throwing widget fan-out")

        // onImportBatchConfirmed: same guarantee.
        coordinator.onImportBatchConfirmed()
        assertEquals(3, refreshes)
    }

    /**
     * P8-06 batch b (D-343 sections 3.1.3/3.7): the report region's initial-request leg. It is due
     * exactly once per fresh slot, only while the ANALYSIS page is actually showing and the graph
     * wired the read — a tab switch, a read-only return (same slot instance) or a recomposition
     * never starts a second read, while a rebuilt page keeps the period the user chose.
     *
     * ABLATION (承重): drop the `reportServedOverview` identity guard in
     * [P503HostCoordinator.decideReport] and the repeated evaluation below requests twice; drop the
     * `selectedTab` gate in [p806ReportInitialReadDue] and the HOME-tab case requests.
     */
    @Test
    fun theReportRegionRequestsItsFirstPeriodOnceOnTheAnalysisPageOnly() {
        val requested = mutableListOf<P806Period>()
        // The host's request callback runs synchronously: the accepted+enqueued read is what marks
        // the page served (a refusal would leave it unserved), so the fake host reports it back.
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportRequest = { period ->
                    requested += period
                    host?.reportReadEnqueued(period)
                },
            )
        val coordinator = requireNotNull(host)
        val ledgerId = LedgerId("ledger-report-region")

        fun page(
            slot: P806ReportRegionState?,
            tab: P503Tab = P503Tab.ANALYSIS,
            importSurface: Boolean = false,
        ): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = tab,
                importSurface = importSurface,
                reportRegion = slot,
            )

        val fresh = page(P806ReportRegionState())
        assertTrue(p806ReportInitialReadDue(fresh))
        assertTrue(coordinator.decideReport(fresh))
        assertEquals(listOf(P806Period(P806PeriodKind.MONTH)), requested)
        // The same state instance re-evaluated (a recomposition / coalesced effect) never re-reads.
        assertFalse(coordinator.decideReport(fresh))
        assertEquals(1, requested.size)
        // The gates: another tab, the import surface, a loaded slot, a pending read, an unwired slot.
        assertFalse(p806ReportInitialReadDue(page(P806ReportRegionState(), tab = P503Tab.HOME)))
        assertFalse(p806ReportInitialReadDue(page(P806ReportRegionState(), importSurface = true)))
        assertFalse(p806ReportInitialReadDue(page(P806ReportRegionState(landed = reportViewForRegion()))))
        assertFalse(p806ReportInitialReadDue(page(P806ReportRegionState(loading = true))))
        assertFalse(p806ReportInitialReadDue(page(P806ReportRegionState(unwired = true))))
        // A page that has not opened its region yet is due: the HOST opens it, and only when the
        // composition actually wires the report read (the capability gate lives there).
        assertTrue(p806ReportInitialReadDue(page(null)))
        assertFalse(coordinator.decideReport(P503AppState.Created))
        // A REBUILT page (a new overview instance) keeps the period the user actually selected.
        coordinator.retainReportPeriod(P806Period(P806PeriodKind.YEAR, year = 2019))
        assertTrue(coordinator.decideReport(page(P806ReportRegionState())))
        assertEquals(P806Period(P806PeriodKind.YEAR, year = 2019), requested.last())
        // A page whose region was reopened (fresh slot) is due again even when it was served before.
        assertTrue(coordinator.decideReport(page(P806ReportRegionState())))
        assertEquals(P806Period(P806PeriodKind.YEAR, year = 2019), requested.last())
    }

    /**
     * P8-06 batch b (D-343 section 3.7): a determinate write (Created/NoChange/Recovered), an
     * effective-surface change (correction/void/restore, the annotation return included) and a
     * completed import batch re-read the last READY period exactly once — through the EXPLICIT
     * post-write callback, with the ANCHORED period, never through the ordinary same-period selection
     * the refreshed page would absorb.
     *
     * ABLATION (承重): route [P503HostCoordinator.consumeReportReRequestAfterRefresh] through
     * `onReportRequest` instead of `onReportReRequest` and the "explicit path" assertions go RED;
     * read the landed slot's default period instead of `reportedLastPeriod` and the anchored-period
     * assertion goes RED; drop the `reportPayloadLanded` gate in `beginReportWrite` and the
     * never-READY cases below re-read.
     */
    @Test
    fun aDeterminateWriteArmsExactlyOneRetainedPeriodReportRead() {
        val requests = mutableListOf<P806Period>()
        val reRequests = mutableListOf<P806Period>()
        var refreshes = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportRequest = { period -> requests += period },
                onReportReRequest = { period -> reRequests += period },
            )
        val ledgerId = LedgerId("ledger-report-region-write")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )

        // The READY (anchored) week is what a later write re-reads.
        val week = P806Period(P806PeriodKind.WEEK, weekStart = LocalDate(2026, 11, 16))
        assertTrue(coordinator.requestReportNow(page(P806ReportRegionState()), week))
        assertEquals(listOf(week), requests)
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = week,
                    kind = P806PeriodKind.WEEK,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.WEEK,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        // A determinate success refreshes and arms; the landing completes ONE explicit re-read.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshes)
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertEquals(listOf(week), reRequests)
        assertEquals(1, requests.size, "the write re-read never rides the ordinary request path")
        // The arm is consumed exactly once.
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertEquals(1, reRequests.size)
        // The re-read lands → the region is READY again, so the next write owes and re-reads once more.
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = week,
                    kind = P806PeriodKind.WEEK,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.WEEK,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        // An effective-surface change, an import batch confirmation and the annotation return arm it.
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertEquals(2, reRequests.size)
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = week,
                    kind = P806PeriodKind.WEEK,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.WEEK,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        coordinator.onImportBatchConfirmed()
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertEquals(3, reRequests.size)
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = week,
                    kind = P806PeriodKind.WEEK,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.WEEK,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        refreshAndArmAfterAnnotationEdit(TransactionAnnotationEditOutcome.Accepted(4L), coordinator)
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertEquals(4, reRequests.size)
        assertEquals(week, reRequests.last())
    }

    /**
     * P8-06 batch b (D-343 section 3.7): the re-read needs a landed payload (a write must never start
     * a read the user never completed), and a FAILED refresh landing RETAINS the debt — the explicit
     * RetryRefresh path still completes exactly one read on the next eligible page. A callback return
     * alone is not a read: only the accepted+enqueued read clears the debt.
     *
     * ABLATION (承重): clear the debt in [P503HostCoordinator.reportRefreshFailed] and the retained
     * assertion goes RED; launch without an accepted read and the "exactly once" assertion goes RED.
     */
    @Test
    fun theReportRearmNeedsALandedPayloadAndAFailedRefreshRetainsTheDebt() {
        val requests = mutableListOf<P806Period>()
        val reRequests = mutableListOf<P806Period>()
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = {},
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportRequest = { period ->
                    requests += period
                    host?.reportReadEnqueued(period)
                },
                onReportReRequest = { period ->
                    reRequests += period
                    host?.reportReadEnqueued(period)
                },
            )
        val coordinator = requireNotNull(host)
        val ledgerId = LedgerId("ledger-report-region-failure")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )

        fun ready(slot: P806ReportRegionState): P503AppState.OverviewEmpty =
            page(
                slot.copy(
                    landed = reportViewForRegion(),
                    landedKind = slot.period.kind,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            )

        // Never requested and never landed: a write arms nothing and the landing re-reads nothing.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertTrue(reRequests.isEmpty())
        // Requested but not landed yet: still nothing owed.
        assertTrue(coordinator.requestReportNow(page(P806ReportRegionState()), P806Period(P806PeriodKind.ALL)))
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertTrue(reRequests.isEmpty())
        // Landed, then the refresh FAILS: the debt is retained and nothing launches yet.
        coordinator.reportLanded(ready(P806ReportRegionState(period = P806Period(P806PeriodKind.ALL), kind = P806PeriodKind.ALL)))
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.reportRefreshFailed()
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertTrue(reRequests.isEmpty())
        // The retried refresh lands on an eligible page: exactly one owed read, spent by acceptance.
        val eligible = page(P806ReportRegionState())
        coordinator.consumeOwedReportReRequest(eligible)
        assertEquals(listOf(P806Period(P806PeriodKind.ALL)), reRequests)
        // The acceptance marked the page served and cleared the debt: no relaunch, no duplicate entry.
        coordinator.consumeOwedReportReRequest(eligible)
        assertEquals(1, reRequests.size)
        assertFalse(coordinator.decideReport(eligible))
        assertEquals(1, reRequests.size)
        // The positive control: a landed payload with a determinate success re-reads once more.
        coordinator.reportLanded(ready(P806ReportRegionState(period = P806Period(P806PeriodKind.ALL), kind = P806PeriodKind.ALL)))
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertEquals(2, reRequests.size)
        assertEquals(1, requests.size)
    }

    /**
     * P8-06 batch b (D-343 section 3.7): only a determinate success arms the report re-read. A typed
     * P705 rejection, a READ-shape failure, an unknown commit and a non-terminal import batch arm
     * nothing — "失败、不确定、预览不当成成功".
     */
    @Test
    fun aRejectionUnknownOrIncompleteBatchNeverArmsTheReportReread() {
        val reRequests = mutableListOf<P806Period>()
        val coordinator =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportReRequest = { period -> reRequests += period },
            )
        val ledgerId = LedgerId("ledger-report-region-rejected")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = P806Period(P806PeriodKind.MONTH),
                    kind = P806PeriodKind.MONTH,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.MONTH,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        // Not a determinate success: the decision returns no action and arms nothing.
        assertNull(coordinator.decide(P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)))
        assertNull(coordinator.decide(P503AppState.UnknownCommit()))
        // A typed/stale P705 landing and a non-terminal batch arm nothing either.
        refreshAndArmAfterP705CommitLanding(CorrectTransactionVersionResult.StaleCurrentVersion, coordinator)
        armAfterImportBatchConfirmation(P503AppState.Created, coordinator)
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertTrue(reRequests.isEmpty())
    }

    /**
     * N-2 (承重) regression, ACTUAL order: a landed payload, then a determinate write, then a FAILED
     * refresh, then a successful RetryRefresh. The write invalidates the report memory AT ITS START
     * (before the refresh's read), so the retried success can never re-seed the pre-write payload; the
     * debt RETAINS through the failure and the retry's eligible page fills it exactly once.
     *
     * ABLATION (承重): move `beginReportWrite()` after `onRefresh()`/drop it and the invalidation order
     * assertion goes RED; clear the debt in [P503HostCoordinator.reportRefreshFailed] and the
     * "exactly one re-read" assertion goes RED.
     */
    @Test
    fun aFailedRefreshFollowedByARetryFillsTheRetainedDebtExactlyOnce() {
        val reRequests = mutableListOf<P806Period>()
        val invalidations = mutableListOf<Int>()
        var refreshes = 0
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportReRequest = { period ->
                    reRequests += period
                    host?.reportReadEnqueued(period)
                },
                onReportWriteInvalidation = { invalidations += refreshes },
            )
        val coordinator = requireNotNull(host)
        val ledgerId = LedgerId("ledger-report-region-retry")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )
        val month = P806Period(P806PeriodKind.MONTH)
        // Landed, but carried on the account-flow surface: the carrier table is what the coordinator reads.
        coordinator.reportLanded(
            P503AppState.AccountFlow(
                overview =
                    page(
                        P806ReportRegionState(
                            period = month,
                            kind = P806PeriodKind.MONTH,
                            landed = reportViewForRegion(),
                            landedKind = P806PeriodKind.MONTH,
                            landedGeneration = 3,
                            landedCatalogVersion = 7L,
                        ),
                    ),
                accountId = AccountId("asset-payment"),
                balanceLine = "餐饮 · 支出 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = CurrencyUnit("CNY", 2),
            ),
        )
        // Determinate write: the invalidation fires BEFORE the refresh (0 refreshes observed so far).
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        assertEquals(listOf(0), invalidations)
        assertEquals(1, refreshes)
        // The refresh FAILED: the refresh-in-flight mark clears, the debt is retained, nothing launches.
        coordinator.reportRefreshFailed()
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertTrue(reRequests.isEmpty())
        // RetryRefresh succeeds and lands a fresh page: exactly one owed read, then the debt is spent.
        val eligible = page(P806ReportRegionState())
        coordinator.consumeOwedReportReRequest(eligible)
        assertEquals(listOf(month), reRequests)
        coordinator.consumeOwedReportReRequest(eligible)
        assertFalse(coordinator.decideReport(eligible))
        assertEquals(1, reRequests.size)
        // The read-only paths invalidate nothing (the zero-read cancel path does not regress).
        assertNull(coordinator.decide(page(P806ReportRegionState())))
        assertEquals(1, invalidations.size)
    }

    /**
     * N-2: the invalidation is UNCONDITIONAL at every determinate-write entry point — it does not
     * wait for the region to be READY (a stale memory must not survive an unrelated write) and it
     * runs before each refresh.
     */
    @Test
    fun everyDeterminateWritePathInvalidatesTheReportMemoryBeforeItsRefresh() {
        val invalidations = mutableListOf<Int>()
        var refreshes = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportWriteInvalidation = { invalidations += refreshes },
            )
        // Never READY: the invalidation still fires, and it sees the pre-refresh count.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        coordinator.onP705EffectiveSurfaceChanged()
        coordinator.onImportBatchConfirmed()
        assertEquals(listOf(0, 1, 2), invalidations)
        assertEquals(3, refreshes)
        // A non-success decision never invalidates.
        assertNull(coordinator.decide(P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)))
        assertEquals(3, invalidations.size)
    }

    /**
     * N-2: the owed post-write re-read is distinguished from the refresh in flight. A refresh landing
     * on a NON-Overview state (a carried-overview detour) must RETAIN the debt — not silently drop the
     * contract's refresh — and the next eligible Overview page launches it exactly once, with the
     * ordinary entry unable to duplicate it.
     *
     * ABLATION (承重): clear the debt before the Overview check in
     * [P503HostCoordinator.consumeReportReRequestAfterRefresh] and the "retained" assertions go RED;
     * launch without clearing the debt and the "exactly once" assertion goes RED.
     */
    @Test
    fun anOwedReportRereadSurvivesANonOverviewRefreshLandingAndLaunchesOnce() {
        val reRequests = mutableListOf<P806Period>()
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = {},
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportReRequest = { period ->
                    reRequests += period
                    host?.reportReadEnqueued(period)
                },
            )
        val coordinator = requireNotNull(host)
        val ledgerId = LedgerId("ledger-report-region-owed")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )

        fun carried(overview: P503AppState.OverviewEmpty): P503AppState.AccountFlow =
            P503AppState.AccountFlow(
                overview = overview,
                accountId = AccountId("asset-payment"),
                balanceLine = "餐饮 · 支出 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = CurrencyUnit("CNY", 2),
            )
        val month = P806Period(P806PeriodKind.MONTH)
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = month,
                    kind = P806PeriodKind.MONTH,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.MONTH,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        // The refresh landed on a carried (non-Overview) state: the refresh-in-flight mark clears, the
        // debt is RETAINED and nothing launches yet.
        coordinator.consumeReportReRequestAfterRefresh(carried(page(P806ReportRegionState())))
        assertTrue(reRequests.isEmpty())
        coordinator.consumeOwedReportReRequest(carried(page(P806ReportRegionState())))
        assertTrue(reRequests.isEmpty())
        // The next eligible page launches it exactly once; the ordinary entry cannot duplicate it.
        val eligible = page(P806ReportRegionState())
        coordinator.consumeOwedReportReRequest(eligible)
        assertEquals(listOf(month), reRequests)
        coordinator.consumeOwedReportReRequest(eligible)
        assertFalse(coordinator.decideReport(eligible))
        assertEquals(1, reRequests.size)
    }

    /**
     * ② of the lifecycle contract (承重): W1 starts R1; W2 arrives during R1's wait. R1's landing must
     * neither release the owed re-read nor clear the debt, the interim page starts no ordinary read,
     * and only the LAST landing of the merged chain issues exactly one read covering W2.
     *
     * ABLATION (承重): drop the `chainComplete` guard in
     * [P503HostCoordinator.consumeReportReRequestAfterRefresh] and R1's interim landing releases the
     * read (the "no read before the last landing" assertion goes RED); overwrite the debt in
     * `beginReportWrite` instead of merging and W2's obligation is lost (the final "exactly one"
     * assertion goes RED).
     */
    @Test
    fun aSecondWriteDuringTheFirstRefreshesWaitKeepsTheDebtAndTheLastLandingIssuesOneRead() {
        val requested = mutableListOf<P806Period>()
        val reRequests = mutableListOf<P806Period>()
        var refreshes = 0
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportRequest = { period ->
                    requested += period
                    host?.reportReadEnqueued(period)
                },
                onReportReRequest = { period ->
                    reRequests += period
                    host?.reportReadEnqueued(period)
                },
            )
        val coordinator = requireNotNull(host)
        val ledgerId = LedgerId("ledger-report-region-merge")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )
        val week = P806Period(P806PeriodKind.WEEK, weekStart = LocalDate(2026, 11, 16))
        // A landed week is what a write must re-read.
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = week,
                    kind = P806PeriodKind.WEEK,
                    requestToken = 4L,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.WEEK,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        // W1 → R1.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        assertEquals(1, refreshes)
        // W2 during R1's wait: it merges into the debt and coalesces its own refresh.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.NoChange))
        assertEquals(2, refreshes)
        // R1's landing: the chain continues, so nothing is released and the interim page reads nothing.
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()), chainComplete = false)
        assertTrue(reRequests.isEmpty())
        assertFalse(coordinator.decideReport(page(P806ReportRegionState())))
        // R2's landing (the chain's last): exactly one explicit read of the retained week (covering W2).
        val finalPage = page(P806ReportRegionState())
        coordinator.consumeReportReRequestAfterRefresh(finalPage, chainComplete = true)
        assertEquals(listOf(week), reRequests)
        // The consumed read cannot be duplicated by a later evaluation or a page entry.
        assertFalse(coordinator.decideReport(finalPage))
        coordinator.consumeOwedReportReRequest(finalPage)
        assertEquals(1, reRequests.size)
        assertTrue(requested.isEmpty(), "the owed re-read never rides the ordinary request path")
    }

    /**
     * ② second half (承重): W2 arrives while the FIRST owed re-read is still in flight. The fenced read
     * can neither change the slot nor spend W2's obligation; the merged chain's last landing issues
     * exactly one further read, and the old result never lands.
     *
     * ABLATION (承重): clear `reportPayloadLanded` at the write start and W2 owes nothing (the final
     * read assertion goes RED); drop the fence in the invalidation and the old result lands (the
     * "no change" assertion goes RED).
     */
    @Test
    fun aSecondWriteDuringTheOwedReadInFlightFencesTheOldResultAndOwesOneMoreRead() {
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val reRequests = mutableListOf<P806Period>()
        var refreshes = 0
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportReRequest = { period ->
                    reRequests += period
                    host?.reportReadEnqueued(period)
                },
            )
        val coordinator = requireNotNull(host)
        val ledgerId = LedgerId("ledger-report-region-inflight")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )
        val month = P806Period(P806PeriodKind.MONTH)
        val ready =
            P806ReportRegionState(
                period = month,
                kind = P806PeriodKind.MONTH,
                requestToken = 4L,
                landed = reportViewForRegion(),
                landedKind = P806PeriodKind.MONTH,
                landedGeneration = 3,
                landedCatalogVersion = 7L,
            )
        coordinator.reportLanded(page(ready))
        // W1 → R1 lands → the owed read (token 10) is enqueued on the rebuilt page.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        coordinator.consumeReportReRequestAfterRefresh(page(P806ReportRegionState()))
        assertEquals(listOf(month), reRequests)
        // The rebuilt page's slot carries the in-flight owed read; W2 invalidates it.
        val inFlight = P806ReportRegionState(period = month, kind = P806PeriodKind.MONTH, requestToken = 10L, loading = true)
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.NoChange))
        assertEquals(2, refreshes)
        val fenced = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(page(inFlight), P503UiEvent.InvalidateReportRegion))
        val fencedSlot = assertNotNull(fenced.reportRegion)
        assertEquals(10L, fencedSlot.fenceToken)
        // A slot with no payload has nothing to protect and nothing pending: the fence ends the read
        // and the surface stays NOT_LOADED instead of fabricating a loading state.
        assertFalse(fencedSlot.loading)
        assertFalse(fencedSlot.writeStale)
        assertEquals(P806ReportRegionFace.NOT_LOADED, p806ReportRegionFace(fencedSlot))
        // The old result (token 10) arrives: fenced, the slot is unchanged.
        assertSame(
            fenced,
            reducer.reduce(
                fenced,
                P503UiEvent.ReportPeriodResult(
                    read = P806ReportRead.Landed(P806ReportReadCapture(P806ReportRequestIdentity(10L, ledgerId, month), 3, 7L, readInstantForRegion()), result = P806SnapshotResult.Success(reportViewForRegion())),
                    currentGeneration = 3,
                    currentCatalogVersion = 7L,
                ),
            ),
        )
        // The merged chain's last landing: exactly one read covering W2.
        coordinator.consumeReportReRequestAfterRefresh(page(inFlight), chainComplete = true)
        assertEquals(2, reRequests.size)
        assertEquals(month, reRequests.last())
    }

    /**
     * ③ of the lifecycle contract (承重): the authoritative refresh FAILS while the user is on a
     * carried page — the carrier absorbs the refresh family, so the region must converge to the named
     * failure (old payload labelled + retry) instead of a pending face. Back on the overview the owed
     * read is filled exactly once; when that catch-up read fails there is still the explicit retry,
     * and a recomposition never re-reads.
     *
     * ABLATION (承重): drop the post-refresh conclusion in [P503ReducerImpl.reduce] and the carried
     * slot stays write-stale (the FAILED assertion goes RED); clear the debt on the failed refresh and
     * the single catch-up read assertion goes RED.
     */
    @Test
    fun aFailedRefreshOnACarriedPageConvergesToTheNamedFailureAndFillsTheOwedReadOnce() {
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val reRequests = mutableListOf<P806Period>()
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = {},
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportReRequest = { period ->
                    reRequests += period
                    host?.reportReadEnqueued(period)
                },
            )
        val coordinator = requireNotNull(host)
        val ledgerId = LedgerId("ledger-report-region-carried-failure")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )

        fun carried(slot: P806ReportRegionState): P503AppState.AccountFlow =
            P503AppState.AccountFlow(
                overview = page(slot),
                accountId = AccountId("asset-payment"),
                balanceLine = "餐饮 · 支出 · CNY",
                balanceMinorUnits = 0L,
                balanceCurrency = cny,
            )
        val month = P806Period(P806PeriodKind.MONTH)
        val ready =
            P806ReportRegionState(
                period = month,
                kind = P806PeriodKind.MONTH,
                requestToken = 4L,
                landed = reportViewForRegion(),
                landedKind = P806PeriodKind.MONTH,
                landedGeneration = 3,
                landedCatalogVersion = 7L,
            )
        coordinator.reportLanded(carried(ready))
        // W: the invalidation runs at the write start, on the carried slot.
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        val fenced = assertIs<P503AppState.AccountFlow>(reducer.reduce(carried(ready), P503UiEvent.InvalidateReportRegion))
        assertTrue(assertNotNull(fenced.overview.reportRegion).writeStale)
        // The refresh FAILS: the carrier absorbs the event, and the slot concludes as a named failure.
        val failed = assertIs<P503AppState.AccountFlow>(reducer.reduce(fenced, P503UiEvent.RefreshFailed))
        val failedSlot = assertNotNull(failed.overview.reportRegion)
        assertFalse(failedSlot.loading)
        assertFalse(failedSlot.writeStale)
        assertEquals(P806SnapshotFailure.Unavailable, failedSlot.failure)
        assertNotNull(failedSlot.landed, "the old amount stays visible as labelled old context")
        coordinator.reportRefreshFailed()
        // Nothing launches while the page cannot host a read.
        coordinator.consumeOwedReportReRequest(failed)
        assertTrue(reRequests.isEmpty())
        // Back on the overview: one catch-up read of the retained period, exactly once.
        val back = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(failed, P503UiEvent.CloseAccountFlow))
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(assertNotNull(back.reportRegion)))
        coordinator.consumeOwedReportReRequest(back)
        assertEquals(listOf(month), reRequests)
        coordinator.consumeOwedReportReRequest(back)
        assertEquals(1, reRequests.size)
        // The catch-up read fails: a named failure with the explicit retry, and no automatic loop.
        val requesting = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(back, P503UiEvent.RetryReportPeriod(requestToken = 5L, period = month)))
        assertTrue(assertNotNull(requesting.reportRegion).loading)
        val catchUpFailed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    requesting,
                    P503UiEvent.ReportPeriodResult(
                        read = P806ReportRead.Landed(P806ReportReadCapture(P806ReportRequestIdentity(5L, ledgerId, month), 3, 7L, readInstantForRegion()), result = P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)),
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                    ),
                ),
            )
        assertEquals(P806ReportRegionFace.FAILED, p806ReportRegionFace(assertNotNull(catchUpFailed.reportRegion)))
        coordinator.consumeOwedReportReRequest(catchUpFailed)
        assertEquals(1, reRequests.size)
        // The user's explicit retry is the one extra read; a recomposition starts none.
        val retried = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(catchUpFailed, P503UiEvent.RetryReportPeriod(requestToken = 6L)))
        assertTrue(assertNotNull(retried.reportRegion).loading)
        assertFalse(coordinator.decideReport(catchUpFailed))
        assertEquals(1, reRequests.size)
        // A read-only return with no write starts zero reads.
        val detour = assertIs<P503AppState.AccountFlow>(reducer.reduce(retried, P503UiEvent.SelectAccountFlow(AccountId("expense-food"), "餐饮 · 支出 · CNY", 0L, cny)))
        val returned = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(detour, P503UiEvent.CloseAccountFlow))
        assertTrue(assertNotNull(returned.reportRegion).loading)
        coordinator.consumeOwedReportReRequest(returned)
        assertEquals(1, reRequests.size)
    }

    /**
     * Negative control (承重): only a determinate formal import receipt fences the report payload and
     * owes its re-read. A terminal batch that only rejected/skipped items — or whose receipt carries
     * no formal `transactionId` (the candidate-only shape) — writes nothing.
     *
     * ABLATION (承重): gate the report leg on [shouldArmImportBatchConfirmed] alone and the rejected /
     * candidate-only cases fence the payload (the empty-invalidation assertion goes RED).
     */
    @Test
    fun onlyADeterminateImportReceiptFencesTheReportPayload() {
        val invalidations = mutableListOf<String>()
        var refreshes = 0
        val coordinator =
            P503HostCoordinator(
                onRefresh = { refreshes += 1 },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportWriteInvalidation = { invalidations += "write" },
            )
        val candidate = ImportCandidateId("candidate-1")
        val rejectedPage = batchPage(ImportBatchItemOutcome.Rejected("IMPORT_STALE_FINGERPRINT"), candidate)
        assertFalse(p806ImportBatchHasDeterminateWrite(rejectedPage))
        coordinator.onImportBatchConfirmed(reportWrite = p806ImportBatchHasDeterminateWrite(rejectedPage))
        assertTrue(invalidations.isEmpty())
        // A confirmed item whose receipt carries no formal transaction (the candidate-only shape).
        val candidateOnly =
            batchPage(
                ImportBatchItemOutcome.Confirmed(
                    com.unifiedledger.application.ImportReceipt(
                        requestId = com.unifiedledger.application.ImportRequestId("request-1"),
                        sourceId = null,
                        evidenceId = null,
                        candidateId = candidate,
                        confirmationId = com.unifiedledger.application.ImportConfirmationId("confirmation-1"),
                        transactionId = null,
                    ),
                ),
                candidate,
            )
        assertFalse(p806ImportBatchHasDeterminateWrite(candidateOnly))
        coordinator.onImportBatchConfirmed(reportWrite = p806ImportBatchHasDeterminateWrite(candidateOnly))
        assertTrue(invalidations.isEmpty())
        // The real Confirmed receipt carries the formal transactionId of THIS batch candidate.
        val formal =
            batchPage(
                ImportBatchItemOutcome.Confirmed(
                    com.unifiedledger.application.ImportReceipt(
                        requestId = com.unifiedledger.application.ImportRequestId("request-1"),
                        sourceId = null,
                        evidenceId = null,
                        candidateId = candidate,
                        confirmationId = com.unifiedledger.application.ImportConfirmationId("confirmation-1"),
                        transactionId = com.unifiedledger.domain.TransactionId("transaction-1"),
                    ),
                ),
                candidate,
            )
        assertTrue(p806ImportBatchHasDeterminateWrite(formal))
        coordinator.onImportBatchConfirmed(reportWrite = p806ImportBatchHasDeterminateWrite(formal))
        assertEquals(listOf("write"), invalidations)
        assertEquals(3, refreshes, "the monthly/budget/widget legs keep their existing terminal rule")
    }

    /**
     * Negative control (承重): the 换库 closeout drops the retained period, the payload mark and the
     * unpaid obligation — the new graph never re-reads the old ledger's period.
     *
     * ABLATION (承重): remove [P503HostCoordinator.dropReportLifecycleForGraphSwitch]'s resets and the
     * post-switch write still owes (and issues) a read of the old period.
     */
    @Test
    fun aCommittedRestoreSwitchDropsTheRetainedReportLifecycle() {
        val requested = mutableListOf<P806Period>()
        val reRequests = mutableListOf<P806Period>()
        val coordinator =
            P503HostCoordinator(
                onRefresh = {},
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportRequest = { period -> requested += period },
                onReportReRequest = { period -> reRequests += period },
            )
        val ledgerId = LedgerId("ledger-report-region-switch")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )
        val year = P806Period(P806PeriodKind.YEAR, year = 2025)
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = year,
                    kind = P806PeriodKind.YEAR,
                    requestToken = 4L,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.YEAR,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        // The committed switch retires the old graph's lifecycle.
        coordinator.dropReportLifecycleForGraphSwitch()
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        coordinator.reportRefreshFailed()
        coordinator.consumeOwedReportReRequest(page(P806ReportRegionState()))
        assertTrue(reRequests.isEmpty())
        // The fresh page reads its own default period, never the old graph's retained one.
        assertTrue(coordinator.decideReport(page(P806ReportRegionState())))
        assertEquals(P806Period(P806PeriodKind.MONTH), requested.last())
    }

    /**
     * R-4 (承重): a refused / not-enqueued owed read neither spends the debt nor marks the page as
     * served. The attempt stays bounded — one callback per explicit evaluation, no read and no
     * self-triggered loop — and a later accepted enqueue performs both (the other tests).
     *
     * ABLATION (承重): mark `reportServedOverview` before the callback in
     * [P503HostCoordinator.launchOwedReportReRequest] and the second attempt disappears (the
     * "two attempts" assertion goes RED); clear the debt before the callback and the
     * "still owed" assertion goes RED.
     */
    @Test
    fun aRefusedOwedReadKeepsTheDebtAndDoesNotMarkThePageServed() {
        val attempts = mutableListOf<P806Period>()
        val coordinator =
            P503HostCoordinator(
                onRefresh = {},
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                // A refusing host: the callback is observed but never enqueues a read.
                onReportReRequest = { period -> attempts += period },
            )
        val ledgerId = LedgerId("ledger-report-region-refused")

        fun page(slot: P806ReportRegionState?): P503AppState.OverviewEmpty =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(ledgerId, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
                reportRegion = slot,
            )
        val month = P806Period(P806PeriodKind.MONTH)
        coordinator.reportLanded(
            page(
                P806ReportRegionState(
                    period = month,
                    kind = P806PeriodKind.MONTH,
                    landed = reportViewForRegion(),
                    landedKind = P806PeriodKind.MONTH,
                    landedGeneration = 3,
                    landedCatalogVersion = 7L,
                ),
            ),
        )
        assertEquals(HostAction.RefreshAfterResult, coordinator.decide(P503AppState.Created))
        coordinator.reportRefreshFailed()
        val eligible = page(P806ReportRegionState())
        // One explicit evaluation = one callback, with no internal loop and no read enqueued.
        coordinator.consumeOwedReportReRequest(eligible)
        assertEquals(1, attempts.size)
        // The refusal spent nothing: a later evaluation attempts again instead of treating the page
        // as served, and the debt still owns the next read.
        coordinator.consumeOwedReportReRequest(eligible)
        assertEquals(2, attempts.size)
        assertFalse(coordinator.decideReport(eligible))
        assertEquals(listOf(month, month), attempts)
    }

    /**
     * R-1 (承重): only a result whose authoritative landingGeneration differs from the captured one
     * retires the report lifecycle; a rejection that never advanced the graph preserves it, and the
     * fail-closed recovery (no active generation) retires nothing.
     *
     * ABLATION (承重): compare variants (`Committed` only) or the ledger id instead of the captured
     * generation and the RolledBack/post-quiesce-abort rows go RED.
     */
    @Test
    fun onlyAReopenedGenerationRetiresTheReportLifecycle() {
        val preSwitch = 3
        assertTrue(restoreSwitchChangedGraph(BackupRestoreSwitchResult.Committed(runtimeGeneration = 4), preSwitch))
        assertTrue(restoreSwitchChangedGraph(BackupRestoreSwitchResult.RolledBack(runtimeGeneration = 4), preSwitch))
        assertTrue(
            restoreSwitchChangedGraph(
                BackupRestoreSwitchResult.AbortedBeforePublish(
                    reason = BackupRestoreAbortReason.StagingFailed,
                    runtime = BackupRestoreRuntimeOutcome.RestoredReady,
                    runtimeGeneration = 4,
                ),
                preSwitch,
            ),
        )
        assertFalse(
            restoreSwitchChangedGraph(
                BackupRestoreSwitchResult.Stale(
                    reason = BackupRestoreStaleReason.StagingArtifactMissing,
                    runtime = BackupRestoreRuntimeOutcome.RestoredReady,
                    runtimeGeneration = 3,
                ),
                preSwitch,
            ),
        )
        assertFalse(
            restoreSwitchChangedGraph(
                BackupRestoreSwitchResult.RecoveryRequired(BackupRestoreRecoveryCause.RollbackReopenFailed),
                preSwitch,
            ),
        )
    }

    /**
     * F-2 (承重): the production opening/initial-decision seam ([openAndStartReportRead]) driven with
     * the production reducer, the real memory and the real coordinator. A completed slot's editor
     * cancel restores the slot and starts NO read; a never-completed page issues exactly one legal
     * initial read; a late landing with no carrier is absorbed.
     *
     * ABLATION (承重): skip the open step in [openAndStartReportRead] and the cancel case loses the
     * slot (the READY assertion goes RED); launch before the reducer accepts the token and the fresh
     * page launches twice.
     */
    @Test
    fun theProductionOpeningSeamRestoresACompletedSlotAndReadsAFreshPageOnce() {
        val seamLedger = LedgerId("ledger-report-region")
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val memory = P806ReportSlotMemory()
        val sequence = P806ReportRequestSequence()
        val launches = mutableListOf<Long>()
        var state: P503AppState =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(seamLedger, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
            )
        var host: P503HostCoordinator? = null
        host =
            P503HostCoordinator(
                onRefresh = { error("unused") },
                onSubmit = { _, _ -> },
                onCheck = { _, _ -> },
                onReportRequest = { period ->
                    openAndStartReportRead(
                        current = state,
                        wired = true,
                        period = period,
                        explicit = false,
                        memory = memory,
                        sequence = sequence,
                        coordinator = host,
                        currentGeneration = 3,
                        currentCatalogVersion = 7L,
                        dispatch = { event ->
                            state = reducer.reduce(state, event)
                            state
                        },
                        launchRead = { token -> launches += token },
                    )
                },
            )
        val coordinator = requireNotNull(host)
        // 1. A completed slot: one accepted landing through the production reducer.
        state =
            reducer.reduce(
                P503AppState.OverviewEmpty(
                    state = LedgerCurrentState(seamLedger, emptyList(), emptyList()),
                    selectedTab = P503Tab.ANALYSIS,
                    reportRegion = P806ReportRegionState(period = P806Period(P806PeriodKind.MONTH), kind = P806PeriodKind.MONTH, requestToken = 1L, loading = true),
                ),
                P503UiEvent.ReportPeriodResult(
                    read = P806ReportRead.Landed(P806ReportReadCapture(P806ReportRequestIdentity(1L, seamLedger, P806Period(P806PeriodKind.MONTH)), 3, 7L, readInstantForRegion()), result = P806SnapshotResult.Success(reportViewForRegion(period = seamMonth(), transactionCount = 1))),
                    currentGeneration = 3,
                    currentCatalogVersion = 7L,
                ),
            )
        memory.record(state)
        // 2. The editor round trip returns a slotless overview (the flow states carry no report field).
        state = reducer.reduce(state, P503UiEvent.StartNewExpense)
        state = reducer.reduce(state, P503UiEvent.Back)
        assertNull(assertIs<P503AppState.OverviewEmpty>(state).reportRegion)
        // 3. The production decision + seam restore the remembered success and start NO read.
        assertTrue(coordinator.decideReport(state))
        assertTrue(launches.isEmpty())
        val restored = assertNotNull(assertIs<P503AppState.OverviewEmpty>(state).reportRegion)
        assertNotNull(restored.landed)
        assertTrue(!restored.loading)
        assertEquals(P806ReportRegionFace.READY, p806ReportRegionFace(restored))
        // 4. A never-completed page (empty memory) issues exactly ONE legal initial read.
        memory.clear()
        state =
            P503AppState.OverviewEmpty(
                state = LedgerCurrentState(seamLedger, emptyList(), emptyList()),
                selectedTab = P503Tab.ANALYSIS,
            )
        assertTrue(coordinator.decideReport(state))
        assertEquals(1, launches.size)
        assertTrue(assertNotNull(assertIs<P503AppState.OverviewEmpty>(state).reportRegion).loading)
        // 5. A late landing with no carrier is absorbed by the production reducer.
        val transient = P503AppState.Created
        assertSame(
            transient,
            reducer.reduce(
                transient,
                P503UiEvent.ReportPeriodResult(
                    read = P806ReportRead.Landed(P806ReportReadCapture(P806ReportRequestIdentity(2L, seamLedger, P806Period(P806PeriodKind.MONTH)), 3, 7L, readInstantForRegion()), result = P806SnapshotResult.Success(reportViewForRegion())),
                    currentGeneration = 3,
                    currentCatalogVersion = 7L,
                ),
            ),
        )
    }

    private fun readInstantForRegion(): Instant = Instant.parse("2026-11-18T02:00:00Z")

    /** The resolved window a completed report payload was read for (the month every seam fixture names). */
    private fun seamMonth(): P806ResolvedPeriod = P806ResolvedPeriod(window = P806Window(startEpochNanos = 1_000L, endEpochNanos = 2_000L), startDate = LocalDate(2026, 11, 1), endDateInclusive = LocalDate(2026, 11, 30), dayCount = 30)

    private fun batchPage(
        outcome: ImportBatchItemOutcome,
        candidateId: ImportCandidateId,
    ): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(LedgerId("ledger-report-region"), emptyList(), emptyList()),
            selectedTab = P503Tab.ANALYSIS,
            importSurface = true,
            importReview =
                ImportReviewView(
                    batchResult =
                        ImportBatchResultSummary(
                            confirmedAt = "2026-11-18T02:00:00Z",
                            items =
                                listOf(
                                    ImportBatchResultItem(
                                        item = ImportBatchItem(candidateId = candidateId, requestId = com.unifiedledger.application.ImportRequestId("request-1")),
                                        outcome = outcome,
                                    ),
                                ),
                        ),
                ),
        )

    private fun reportViewForRegion(
        period: P806ResolvedPeriod? = null,
        transactionCount: Int = 0,
    ): com.unifiedledger.application.P806ReportView =
        com.unifiedledger.application.P806ReportView(
            ledgerId = LedgerId("ledger-report-region"),
            period = period,
            currencies = emptyList(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            uncategorizedExpenseTotals = emptyList(),
            uncategorizedIncomeTotals = emptyList(),
            transactionCount = transactionCount,
            futureTransactionCount = 0,
            detailPage = com.unifiedledger.application.P806TransactionPage(rows = emptyList(), nextCursor = null),
            detailCount = transactionCount,
        )
}
