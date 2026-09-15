package com.unifiedledger.ui

import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CounterpartyCommandResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TypedEntryDraft
import kotlin.concurrent.Volatile

/**
 * R1 (spec 6.2/7.3, D-027): a successful catalog command must refresh the authoritative read
 * model too, not just the management projection. HOME's balances/transaction lines come from
 * `queryCurrentState`, which reads through the refreshed catalog session, so the host re-queries
 * it and dispatches the ordinary `RefreshResult`. Rejections/conflicts never refresh.
 */
internal fun shouldRefreshReadModelAfterCatalogCommand(result: CatalogCommandResult): Boolean = result is CatalogCommandResult.Accepted || result is CatalogCommandResult.NoChange

/**
 * P702SPEC-03: a successful counterparty create/rename must refresh the option projections
 * (the LEND/COLLECT editors read the directory through them); a typed rejection is absorbed
 * safely — no refresh, no dispatch, and the open dialog keeps the typed text.
 */
internal fun shouldRefreshOptionsAfterCounterpartyCommand(result: CounterpartyCommandResult): Boolean = result !is CounterpartyCommandResult.Rejected

/**
 * F1 (N-5): a catalog management outcome may only be published while the overview is still on
 * screen. [P503App]'s read-model re-query can fail into `InfrastructureFailure(READ)`, a state
 * with no transition for `CatalogCommandCompleted`/`CatalogSnapshotRefreshed`; dispatching one
 * there would hit the reducer's `unhandled` branch and throw from a coroutine/UI callback. Keep
 * the recoverable read-failure page as the visible state instead: the management projection is
 * re-read fresh from the facade whenever the user next opens the ACCOUNTS tab, so no user-visible
 * information is lost by not publishing the banner. Returns whether the event was dispatched.
 */
internal fun dispatchCatalogOutcomeIfOverview(
    currentState: P503AppState,
    event: P503UiEvent,
    dispatch: (P503UiEvent) -> Unit,
): Boolean {
    if (currentState !is P503AppState.OverviewEmpty) return false
    dispatch(event)
    return true
}

/** Runs on the UI event thread without suspension; obsolete screen callbacks do no work. */
internal fun dispatchCurrentP503Action(
    expectedState: P503AppState,
    currentState: P503AppState,
    event: () -> P503UiEvent,
    dispatch: (P503UiEvent) -> Unit,
    afterDispatch: () -> Unit = {},
) {
    if (currentState !== expectedState) return
    dispatch(event())
    afterDispatch()
}

/**
 * P5-04.4 S5: pure Kotlin host-behavior coordinator (spec section 7).
 *
 * [P503App] is `@Composable`, so its host wiring (which pass-through callback a given state
 * evaluation should run and whether the automatic guard allows it) cannot be covered by a
 * plain JVM test. This coordinator extracts exactly that decision skeleton into a
 * non-`@Composable`, destination-injectable layer: it neither holds Compose state nor a
 * [P503LedgerFacade], and it never writes to the reducer or [P503AppState]. The heavy work
 * (coroutine scope, facade calls, events dispatched to the reducer) stays in [P503App] and is
 * passed in as the [onRefresh]/[onSubmit]/[onCheck] callbacks.
 *
 * Guard semantics:
 * - Automatic authoritative refresh after `Created`/`NoChange`/`Recovered`: exactly once per
 *   handled instance. [P503App] calls [decide] from `LaunchedEffect(state)`, which observes
 *   the state leaving a trigger state and re-arms the guard, so a later entry into the same
 *   object singleton fires again.
 * - Automatic unknown-commit check: exactly once per `UnknownCommit` entry, gated by
 *   `lastCheckOutcome == NONE` plus the per-instance guard.
 * - Manual retry (READ/SUBMISSION) and manual unknown-commit re-check reuse the same injected
 *   callbacks so the automatic and manual paths stay idempotent against duplicate evaluation.
 *
 * P7-03.C (D-145): the unified monthly payload re-request triggers are the FROZEN closed set
 * of spec section 6.2 (P703SPEC-04) and nothing else:
 * (a) initial load — the first overview evaluation ([decideMonthly]);
 * (b) `SelectMonth` — unconditional ([requestMonthlyNow], including the failure recovery
 *     re-dispatch of residual boundary (a));
 * (c) `AnalysisMonthShift` — unconditional ([requestMonthlyNow]);
 * (d) an effective-month change, including 本月 re-resolution across a clock rollover
 *     ([decideMonthly], C01);
 * (e) the authoritative refresh after each determinate success ([decide] fires it alongside
 *     the refresh).
 * Tab switches, `CloseTransactionDetail`, ordinary refreshes and READ-retry recoveries never
 * re-request.
 */
internal class P503HostCoordinator(
    private val onRefresh: () -> Unit,
    private val onSubmit: (draft: TypedEntryDraft, requestId: RequestId) -> Unit,
    private val onCheck: (draft: TypedEntryDraft, requestId: RequestId) -> Unit,
    // P7-03.C: the monthly request cycle the host runs (query + trend + entry rows + dispatch).
    private val onMonthlyRequest: () -> Unit = {},
    // P7-03.C: 本月 resolved by the host's reporting clock (R-Q06-2); null when the clock is
    // unusable, which still allows the initial request so the typed failure surfaces (R-Q06-4).
    private val currentMonth: () -> kotlinx.datetime.YearMonth? = { null },
    // P7-04.C: the bounded read + intake pipeline the host runs for a picked file (spec sections
    // 4.1/4.2/6.2); invoked at most once at a time (single flight, the checkCommitStatus
    // precedent). The production host may instead act on the decision
    // [handleImportPickResult] returns; the callback is the injection/counting seam.
    private val onImportPickIntake: (PickedImportFile) -> Unit = {},
) {
    /** The transient-result instance whose automatic refresh has already been dispatched. */
    private var refreshAfterResultServed: P503AppState? = null

    /** The unknown-commit instance (reference) that already triggered the read-only check. */
    private var unknownCheckServed: P503AppState? = null

    /** P7-03.C: whether any monthly request has been served (trigger (a) gate). */
    private var monthlyEverRequested = false

    /** P7-03.C: the effective month of the last monthly request (trigger (d) comparison). */
    private var monthlyLastEffectiveMonth: kotlinx.datetime.YearMonth? = null

    /**
     * P7-04.C (P704C-QUAL-02): an intake pipeline is currently running; a concurrent second pick
     * is dropped. `@Volatile`: the flag is written from the pipeline's completion hop and read on
     * the pick-callback thread — after the P704C-SPEC-01/QUAL-02 threading fix both are the UI
     * thread, but the annotation keeps the single-flight contract safe under any future
     * background access.
     */
    @Volatile
    private var importIntakeInFlight = false

    /**
     * P7-04.C: a duplicate-review submission is currently in flight; duplicates are dropped
     * (期间禁重复提交). `@Volatile` for the same reason as [importIntakeInFlight].
     */
    @Volatile
    private var importDuplicateReviewInFlight = false

    /**
     * P7-04.C (P704C-SPEC-01/QUAL-02): the group-disposition enumeration (the per-candidate
     * duplicate-review reads behind the 整组确认页) is currently running; a concurrent second
     * enumeration is dropped (double-tap interleaving guard).
     */
    @Volatile
    private var importGroupEnumerationInFlight = false

    /**
     * P7-04.C (P704C-SPEC-01/QUAL-02): the group-disposition per-item review loop is currently
     * running; a concurrent second loop is dropped (double-tap interleaving guard).
     */
    @Volatile
    private var importGroupDispositionInFlight = false

    /**
     * State-driven decision, called at every `LaunchedEffect(state)` evaluation (and at the
     * manual callbacks below). Returns the [HostAction] the host should execute for this
     * evaluation, or `null` when the guard blocks it. Invokes the matching injected callback
     * so the decision and the "served" bookkeeping stay in one place; the callback performs
     * the actual IO/dispatch back in [P503App].
     */
    internal fun decide(state: P503AppState): HostAction? =
        when (state) {
            is P503AppState.Created,
            is P503AppState.NoChange,
            is P503AppState.Recovered,
            -> {
                if (refreshAfterResultServed == state) {
                    null
                } else {
                    refreshAfterResultServed = state
                    onRefresh()
                    // Trigger (e): the authoritative refresh of a determinate success re-requests
                    // the monthly payload for the fresh overview (P703SPEC-04).
                    requestMonthlyNow(state)
                    HostAction.RefreshAfterResult
                }
            }

            is P503AppState.UnknownCommit -> {
                val draft = state.draft
                val requestId = state.requestId
                if (
                    state.lastCheckOutcome == UnknownCommitCheckOutcome.NONE &&
                    draft != null &&
                    requestId != null &&
                    unknownCheckServed != state
                ) {
                    unknownCheckServed = state
                    onCheck(draft, requestId)
                    HostAction.UnknownCheck(draft, requestId)
                } else {
                    null
                }
            }

            else -> {
                // Leaving the trigger states re-arms the refresh guard so a later entry into
                // the same result singleton (a new flow) fires again.
                refreshAfterResultServed = null
                null
            }
        }

    /**
     * P7-03.C: the (a)/(d) decision, called at every `LaunchedEffect(state)` evaluation with the
     * current overview state. Requests exactly when no monthly request was served yet (initial
     * load) or when the effective month (selection or clock-resolved 本月) changed since the
     * last request. Every other state returns false without touching the guards.
     */
    internal fun decideMonthly(state: P503AppState): Boolean {
        val overview = state as? P503AppState.OverviewEmpty ?: return false
        val effectiveMonth = overview.selectedMonth ?: currentMonth()
        if (monthlyEverRequested && monthlyLastEffectiveMonth == effectiveMonth) return false
        monthlyEverRequested = true
        monthlyLastEffectiveMonth = effectiveMonth
        onMonthlyRequest()
        return true
    }

    /**
     * P7-03.C: the (b)/(c)/(e) unconditional re-request. The host calls this right after
     * dispatching SelectMonth/AnalysisMonthShift (including the failure-recovery re-dispatch of
     * the same month, residual boundary (a)) and [decide] calls it after the determinate-success
     * refresh. The effective month is recomputed from the (already reduced) state so the
     * (d) guard sees the month that was actually requested.
     */
    internal fun requestMonthlyNow(state: P503AppState): Boolean {
        val effectiveMonth = (state as? P503AppState.OverviewEmpty)?.selectedMonth ?: currentMonth()
        monthlyEverRequested = true
        monthlyLastEffectiveMonth = effectiveMonth
        onMonthlyRequest()
        return true
    }

    /**
     * Manual READ retry button: an `InfrastructureFailure(READ)` state triggers one refresh.
     * This is an explicit user action, so it always fires for the matching state.
     */
    internal fun retryRefresh(state: P503AppState): HostAction? {
        if (state is P503AppState.InfrastructureFailure && state.context == InfrastructureFailureContext.READ) {
            onRefresh()
            return HostAction.RetryRefresh
        }
        return null
    }

    /**
     * Manual SUBMISSION retry button: an `InfrastructureFailure(SUBMISSION)` state re-runs the
     * submission with the same draft/requestId from that state. Explicit user action, always
     * fires for the matching state.
     */
    internal fun retrySubmission(state: P503AppState): HostAction? {
        if (state is P503AppState.InfrastructureFailure && state.context == InfrastructureFailureContext.SUBMISSION) {
            val draft = state.draft
            val requestId = state.requestId
            if (draft != null && requestId != null) {
                onSubmit(draft, requestId)
                return HostAction.RetrySubmission(draft, requestId)
            }
        }
        return null
    }

    /**
     * Manual unknown-commit re-check button. Explicit user action, but gated by the same
     * per-instance marker so a duplicate evaluation of the same instance cannot double-fire;
     * the `lastCheckOutcome` guard in [decide] keeps the automatic path from re-triggering
     * after an outcome is recorded.
     */
    internal fun retryCommitStatusCheck(state: P503AppState): HostAction? {
        if (state is P503AppState.UnknownCommit) {
            val draft = state.draft
            val requestId = state.requestId
            if (draft != null && requestId != null && unknownCheckServed != state) {
                unknownCheckServed = state
                onCheck(draft, requestId)
                return HostAction.UnknownCheck(draft, requestId)
            }
        }
        return null
    }

    // ---- P7-04.C import host decisions (D-146; spec sections 4.1/4.2/6.2) ----

    /**
     * The pick→intake decision skeleton (table 6.2a: the pick channel events are absorbed by the
     * reducer; this method owns what the HOST does). A picked file starts the bounded-read +
     * intake pipeline exactly once at a time — a concurrent second pick is dropped
     * ([ImportPickIntakeDecision.AlreadyInFlight], the single-flight marker the host clears via
     * [importIntakeCompleted]); a cancellation or a typed platform failure starts nothing (zero
     * diagnostics on a cancellation, spec 4.1.4).
     *
     * P704C-QUAL-03: the AlreadyInFlight drop is practically unreachable in the product wiring
     * (SAF is single-shot and the desktop chooser is modal, so two picks cannot overlap); it is
     * kept as the structural single-flight guard, not as a user-facing path.
     */
    internal fun handleImportPickResult(result: ImportFilePickResult): ImportPickIntakeDecision =
        when (result) {
            is ImportFilePickResult.Picked ->
                if (importIntakeInFlight) {
                    ImportPickIntakeDecision.AlreadyInFlight
                } else {
                    importIntakeInFlight = true
                    onImportPickIntake(result.file)
                    ImportPickIntakeDecision.StartIntake(result.file)
                }
            ImportFilePickResult.Cancelled -> ImportPickIntakeDecision.NoPipeline
            is ImportFilePickResult.Failed -> ImportPickIntakeDecision.NoPipeline
        }

    /** Clears the single-flight marker once the pipeline result has been dispatched. */
    internal fun importIntakeCompleted() {
        importIntakeInFlight = false
    }

    /**
     * The duplicate-review single-flight (期间禁重复提交): one core review submission may be in
     * flight; duplicate evaluations are dropped until [importDuplicateReviewCompleted] clears the
     * marker. Returns whether this evaluation started the submission.
     */
    internal fun submitImportDuplicateReviewOnce(action: () -> Unit): Boolean {
        if (importDuplicateReviewInFlight) return false
        importDuplicateReviewInFlight = true
        action()
        return true
    }

    /** Clears the review single-flight marker once the review result has been dispatched. */
    internal fun importDuplicateReviewCompleted() {
        importDuplicateReviewInFlight = false
    }

    /**
     * P7-04.C (P704C-SPEC-01/QUAL-02): the group-enumeration single flight — the per-candidate
     * duplicate-review reads behind the 整组确认页 run at most once at a time; a duplicate
     * evaluation (double tap) is dropped until [importGroupEnumerationCompleted] clears the
     * marker. Returns whether this evaluation started the enumeration.
     */
    internal fun startImportDuplicateGroupDispositionOnce(action: () -> Unit): Boolean {
        if (importGroupEnumerationInFlight) return false
        importGroupEnumerationInFlight = true
        action()
        return true
    }

    /** Clears the group-enumeration single-flight marker once its outcome has been dispatched. */
    internal fun importGroupEnumerationCompleted() {
        importGroupEnumerationInFlight = false
    }

    /**
     * P7-04.C (P704C-SPEC-01/QUAL-02): the group-disposition per-item review loop single flight
     * — the sequential core review loop runs at most once at a time; a duplicate evaluation
     * (double tap) is dropped until [importGroupDispositionCompleted] clears the marker. Returns
     * whether this evaluation started the loop.
     */
    internal fun confirmImportDuplicateGroupDispositionOnce(action: () -> Unit): Boolean {
        if (importGroupDispositionInFlight) return false
        importGroupDispositionInFlight = true
        action()
        return true
    }

    /** Clears the group-disposition loop single-flight marker once its result has been dispatched. */
    internal fun importGroupDispositionCompleted() {
        importGroupDispositionInFlight = false
    }

    // ---- P7-04.D batch host decisions (D-146; spec sections 3.2.3/3.3.2/6.2) ----

    /** P7-04.D: the sequential per-item dispatch loop is currently running (single flight). */
    @Volatile
    private var importBatchDispatchInFlight = false

    /** P7-04.D: one Unknown item's replay check is currently running (single flight). */
    @Volatile
    private var importUnknownCheckInFlight = false

    /**
     * P7-04.D: the per-item dispatch loop single flight — at most one sequential confirm loop may
     * run at a time; a duplicate start is dropped until [importBatchDispatchCompleted] clears the
     * marker (the marker releases in the loop's final main-dispatcher hop, after the last
     * per-item result hop, so a paused/abandoned batch always leaves the slot free before the
     * user can act). Returns whether this evaluation started the loop.
     */
    internal fun startImportBatchDispatchOnce(action: () -> Unit): Boolean {
        if (importBatchDispatchInFlight) return false
        importBatchDispatchInFlight = true
        action()
        return true
    }

    /** Clears the dispatch-loop single-flight marker once its run's dispatches have landed. */
    internal fun importBatchDispatchCompleted() {
        importBatchDispatchInFlight = false
    }

    /**
     * P7-04.D: the Unknown item replay check single flight — one claim-gated replay may be in
     * flight; duplicate evaluations are dropped until [importUnknownCheckCompleted] clears the
     * marker (不自动重试： the check is always an explicit user action).
     */
    internal fun submitImportUnknownCheckOnce(action: () -> Unit): Boolean {
        if (importUnknownCheckInFlight) return false
        importUnknownCheckInFlight = true
        action()
        return true
    }

    /** Clears the replay-check single-flight marker once the check result has been dispatched. */
    internal fun importUnknownCheckCompleted() {
        importUnknownCheckInFlight = false
    }
}

/**
 * P7-04.C: the host decision for one platform pick result. [StartIntake] runs the bounded read +
 * intake pipeline (the injected callback), [AlreadyInFlight] drops a concurrent second pick, and
 * [NoPipeline] means the pick itself resolved (cancellation or typed platform failure) with zero
 * pipeline work.
 */
internal sealed interface ImportPickIntakeDecision {
    data class StartIntake(
        val file: PickedImportFile,
    ) : ImportPickIntakeDecision

    data object AlreadyInFlight : ImportPickIntakeDecision

    data object NoPipeline : ImportPickIntakeDecision
}

/**
 * P5-04.4 S5: the host callback action decided for one evaluation point. The receiver runs the
 * matching callback (refresh for [RefreshAfterResult]/[RetryRefresh], submit for
 * [RetrySubmission], commit-status check for [UnknownCheck]); callback execution lives in
 * [P503App], not in this class.
 */
internal sealed interface HostAction {
    data object RetryRefresh : HostAction

    data class RetrySubmission(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
    ) : HostAction

    data object RefreshAfterResult : HostAction

    data class UnknownCheck(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
    ) : HostAction
}
