package com.unifiedledger.ui

import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.CounterpartyCommandResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.application.VoidTransactionResult
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
 * (c) `AnalysisMonthShift` — only when the reduced shift actually moved the cursor inside the
 *     frozen SelectMonth domain (the G3 `analysisMonthShiftReRequest` decision, then
 *     [requestMonthlyNow]); an absorbed shift re-requests nothing;
 * (d) an effective-month change, including 本月 re-resolution across a clock rollover
 *     ([decideMonthly], C01);
 * (e) the authoritative refresh after each determinate success ([decide] arms the re-request;
 *     the host completes it via [consumeMonthlyReRequestAfterRefresh] once the refreshed
 *     overview lands — A-02 FIX-MONTH-1, D-152);
 * (f) a completed import batch confirmation dispatch run ([onImportBatchConfirmed] arms the
 *     same post-landing re-request — P7-03 FIX-STALE-1, D-153); per-item result landings and
 *     a pause/abandon of the run never re-request.
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
     * A-02 FIX-MONTH-1 (D-152): trigger (e) has fired its authoritative refresh and awaits the
     * post-landing monthly re-request. `@Volatile` for the same reason as the single-flight
     * markers: [decide] writes it on the UI thread and the refresh's main-dispatcher completion
     * hop consumes it. The flag survives coalesced re-runs (the merged
     * [P503CurrentStateLoadCoordinator] read may land later than the trigger) until the first
     * landing, and is consumed exactly once.
     */
    @Volatile
    private var pendingMonthlyReRequestAfterRefresh = false

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
                    // the monthly payload for the fresh overview (P703SPEC-04). A-02 FIX-MONTH-1
                    // (D-152): the re-request is armed here and COMPLETED by the host after the
                    // refreshed overview lands ([consumeMonthlyReRequestAfterRefresh]) — the
                    // A-PERF async rework moved the read off the UI thread, so a synchronous
                    // request beside the refresh would read the still-transient result state and
                    // its payload would be absorbed, leaving the month card AWAITING.
                    pendingMonthlyReRequestAfterRefresh = true
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
     * P7-03.C: the (b)/(c)/(e) re-request — (b) `SelectMonth` and (e) the determinate-success
     * refresh unconditionally, (c) `AnalysisMonthShift` only when the pure G3 decision
     * (`analysisMonthShiftReRequest`) shows the reduced shift actually moved the cursor inside
     * the frozen SelectMonth domain (an absorbed shift never calls this). The host calls this
     * right after dispatching SelectMonth (including the failure-recovery re-dispatch of the
     * same month, residual boundary (a)) and [consumeMonthlyReRequestAfterRefresh] calls it
     * after the determinate-success refresh lands (A-02 FIX-MONTH-1). The effective month is
     * recomputed from the (already reduced) state so the
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
     * A-02 FIX-MONTH-1 (D-152): completes trigger (e)'s armed re-request AFTER the authoritative
     * refresh landed. The host calls this from the refresh's success branch once the
     * `RefreshResult` has been dispatched, passing the LANDED state (the fresh
     * `OverviewEmpty`): the pending flag clears and [requestMonthlyNow] fires exactly one
     * unconditional re-request stamped on the landed month, so the unified payload actually
     * reaches the new overview instead of being absorbed by the former still-transient state.
     * The (a)/(d) guard logic is unchanged: the stamp here keeps the immediately following
     * overview evaluation from double-requesting. A landing without an armed trigger (startup
     * load, ordinary or READ-retry refreshes) is a no-op.
     */
    internal fun consumeMonthlyReRequestAfterRefresh(landedState: P503AppState) {
        if (!pendingMonthlyReRequestAfterRefresh) return
        pendingMonthlyReRequestAfterRefresh = false
        requestMonthlyNow(landedState)
    }

    /**
     * A-02 FIX-MONTH-1 (D-152): the refresh's failure branch (`RefreshFailed` dispatched) clears
     * the armed re-request without firing — a failed landing requests nothing, and recovery stays
     * the P703SPEC-11 residual boundary (a) path (a user re-select or the READ retry). The flag
     * is cleared so a later unrelated refresh landing cannot consume a stale trigger.
     */
    internal fun dropMonthlyReRequestAfterFailedRefresh() {
        pendingMonthlyReRequestAfterRefresh = false
    }

    /**
     * P7-03 FIX-STALE-1 (D-153): trigger (f) — the import batch confirmation dispatch run has
     * reached its terminal state (every item terminal, including a run-level typed pre-phase
     * failure and an Unknown-paused run resumed to completion; a partial success still counts,
     * exactly once per run). The confirmation created formal ledger effects, so the host fires
     * the authoritative refresh and arms the SAME post-landing monthly re-request as the (e) arm
     * in [decide]: the flag is consumed by the same landing hop
     * ([consumeMonthlyReRequestAfterRefresh] on a successful landing — exactly one unconditional
     * request stamped on the landed month, so the month card, SelectMonth domain, trend, and
     * entry rows reflect the confirmed batch this session; [dropMonthlyReRequestAfterFailedRefresh]
     * on a failed landing). Mirrors the (e) arm for the same A-PERF reason: no synchronous
     * request beside the refresh, whose transient result state would absorb the payload.
     *
     * Two host call sites arm it, both gated by ONE shared decision
     * ([shouldArmImportBatchConfirmed]) so a batch run arms exactly once across every ordering
     * (D-153 恰一次): the dispatch loop's `completed` branch in [P503App]
     * `runImportBatchDispatch`, and the Unknown 核对 resolution hop in `checkImportUnknownItem`.
     * A completed run that still has an Unknown item (state still `ImportBatchSubmitting` — the
     * falsified double-arm ordering: pause → resume → complete → later 核对) and a mid-batch
     * resolution arm nothing; the site whose landing leaves the retained summary Unknown-free
     * arms. Never fired by per-item result landings nor by a pause/abandon of the run.
     */
    internal fun onImportBatchConfirmed() {
        onRefresh()
        pendingMonthlyReRequestAfterRefresh = true
    }

    /**
     * P7-05 (D-156; spec sections 3.5/4.3): a successful correction/void/restore commit landed a
     * formal ledger effect, so the host fires the authoritative refresh and arms the SAME
     * post-landing monthly re-request as trigger (e)/(f) — detail/flow/monthly all update on one
     * chain, the voided transaction disappears from the effective surfaces, and a restored one
     * returns. The arm is consumed by the same landing hop ([consumeMonthlyReRequestAfterRefresh]
     * on a successful landing, [dropMonthlyReRequestAfterFailedRefresh] on a failed one), so the
     * refresh stays asynchronous and exactly one unconditional monthly request follows it. The
     * caller gates on a determinate success ([shouldRefreshAfterP705Commit]): a typed rejection, a
     * stale CAS, an identity conflict or an unresolved (still-unknown) commit never calls this, so
     * a non-success leaves the surfaces untouched.
     */
    internal fun onP705EffectiveSurfaceChanged() {
        onRefresh()
        pendingMonthlyReRequestAfterRefresh = true
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
 * A-PERF (P7-04 read-governance batch, spec section 2.3, D-A ruling): the pure decision
 * skeleton of the cached authoritative catalog snapshot's background loading. Non-`@Composable`
 * and stateless of Compose so the single-flight admission and the stale-merge contract are
 * JVM-testable exactly like [P503HostCoordinator] (counting-callback precedent).
 *
 * - [startLoadOnce] is the single-flight admission: concurrent requests merge into the running
 *   load (P704C-SPEC-01/QUAL-02 discipline; only the first request starts the background read).
 * - [loadCompleted] releases the slot AND decides the cache's next value (S2-3): a successful
 *   load installs the fresh snapshot; a failed/absent load keeps the previously loaded value —
 *   a late or failed load can never overwrite a newer cached state, and the first-ever failure
 *   leaves the null loading window standing (the honest 载入中, never a faked empty catalog).
 */
internal class P503CatalogSnapshotLoadCoordinator {
    /**
     * Whether one background snapshot load is running. `@Volatile` for the same reason as the
     * [P503HostCoordinator] single-flight markers: the completion hop writes it on the main
     * dispatcher, and the annotation keeps the contract safe under any future access pattern.
     */
    @Volatile
    private var loadInFlight = false

    /** Single-flight admission. Returns whether THIS call started the load. */
    fun startLoadOnce(start: () -> Unit): Boolean =
        if (loadInFlight) {
            false
        } else {
            loadInFlight = true
            start()
            true
        }

    /**
     * Completion: releases the single-flight slot and returns the snapshot the cache should
     * hold after this load ([current] = the cache value observed at completion time; [fresh] =
     * the background read's outcome, null on failure/absence).
     */
    fun loadCompleted(
        current: CatalogSnapshotView?,
        fresh: CatalogSnapshotView?,
    ): CatalogSnapshotView? {
        loadInFlight = false
        return fresh ?: current
    }
}

/**
 * A-PERF (P7-04 read-governance batch, spec section 2.3, rework path 1a): the pure decision
 * skeleton of the authoritative current-state read's background loading — `refresh()` and the
 * initial load previously ran `facade.queryCurrentState.query()` synchronously on the UI thread
 * (the spec's frozen layer-2 refresh-chain scope, former :264-277/:689-698), the same
 * main-thread wait the ANR trace captured while a background read held the single connection.
 *
 * - [startLoadOnce] is the single-flight admission with COALESCING (P704C-SPEC-01/QUAL-02
 *   discipline): a request arriving while a load runs does NOT start a second read; it marks a
 *   deferred re-run, so the running read's completion is followed by exactly one fresh load that
 *   observes everything committed in between (a plain drop could land data captured before a
 *   just-committed entry and leave it on screen).
 * - [loadCompleted] releases the slot and returns whether the coalesced re-run must start.
 *
 * Serialization note (S2-3): the slot admits exactly one load at a time and the completion hop
 * is the only dispatcher of results, so a late stale result can never interleave with — let
 * alone overwrite — a newer state; the coalesced re-run starts only AFTER the previous result
 * landed.
 */
internal class P503CurrentStateLoadCoordinator {
    /**
     * Whether one background current-state load is running. `@Volatile` for the same reason as
     * the [P503HostCoordinator] single-flight markers.
     */
    @Volatile
    private var loadInFlight = false

    /** Whether at least one request coalesced into the running load awaits a re-run. */
    @Volatile
    private var deferredRequested = false

    /** Single-flight admission with coalescing. Returns whether THIS call started the load. */
    fun startLoadOnce(start: () -> Unit): Boolean =
        if (loadInFlight) {
            deferredRequested = true
            false
        } else {
            loadInFlight = true
            start()
            true
        }

    /**
     * Completion: releases the single-flight slot and returns whether a coalesced request must
     * start one fresh load (exactly one re-run regardless of how many requests merged).
     */
    fun loadCompleted(): Boolean {
        loadInFlight = false
        val rerun = deferredRequested
        deferredRequested = false
        return rerun
    }
}

/**
 * A-PERF (APQUAL-02): the retained-intent consumption decision of the background refresh's
 * main-dispatcher hop. The refresh captured [captured] at its admission point; the hop must
 * consume ONLY that instance — a newer intent submitted while the read was in flight (the
 * submit path writes a fresh one) must survive for its own refresh. Clearing the current value
 * unconditionally would destroy the newer intent; a value-based comparison could consume the
 * wrong (equal-valued) instance, so the decision is identity (`===`) like the rest of the
 * retained-intent lifecycle (instance-matched consumption).
 */
internal fun consumeRetainedIntentAfterRefresh(
    current: RetainedEntryIntent?,
    captured: RetainedEntryIntent?,
): RetainedEntryIntent? = if (current === captured) null else current

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

/**
 * P7-03 FIX-STALE-1 (D-153): THE single arm-gate for trigger (f)'s two host call sites, called
 * with the post-dispatch [landedState] (AFTER the event that just reduced). The run reached
 * all-terminal exactly when the landed state is the `OverviewEmpty` carrying the retained result
 * summary (`batchResult`) with no Unknown outcome left:
 * - a dispatch run completing with every item terminal — the reducer auto-left on the last
 *   per-item result (table 6.2a: 仅全部项终态后可离开), so the `completed` branch's hop observes
 *   the left-to overview state; or
 * - an Unknown 核对 verdict completing the run's terminal set — either the reducer auto-left on
 *   THIS verdict (it was the last non-terminal item) or the batch had already left (Resume with
 *   nothing undispatched / Abandon retained the summary) and the verdict completed the summary's
 *   last Unknown in place.
 *
 * Never arms (`false`) — and this is what keeps exactly ONE arm per batch run across every
 * ordering:
 * - a completed run that still has an Unknown item: the state is still `ImportBatchSubmitting`
 *   (the Unknown blocks the auto-leave — falsified-ordered trace: pause at Unknown → Resume
 *   dispatches the rest → run completes → `completed` branch must NOT arm → the later 核对
 *   resolution completes the run and arms);
 * - a mid-batch 核对 resolution (state still `ImportBatchSubmitting` — the continuation run's
 *   completed branch arms when it finishes without Unknowns);
 * - a StillUnknown or absorbed verdict (the item keeps its check entry — the run stays
 *   incomplete, the user may retry);
 * - another Unknown still remaining in the summary (not all-terminal yet — a later resolution
 *   completes the run and arms);
 * - any landing without the retained summary (the verdict was absorbed — no run effect).
 */
internal fun shouldArmImportBatchConfirmed(landedState: P503AppState): Boolean {
    val summary = (landedState as? P503AppState.OverviewEmpty)?.importReview?.batchResult ?: return false
    return summary.items.none { it.outcome is ImportBatchItemOutcome.Unknown }
}

/**
 * P7-05 (D-156; spec sections 3.2/3.3/4.3): THE single success gate of the correction/void/restore
 * refresh chain. A determinate success — `Created` or `NoChange` (a replay returned its original
 * receipt, so the effect is already in place) — landed a formal ledger effect and fires the
 * authoritative refresh; every other outcome writes nothing and must NOT refresh:
 * - `StaleCurrentVersion` (the CAS lost; zero writes, no auto-retry),
 * - `RequestIdentityConflict` (a different snapshot under the same request id; zero writes),
 * - `Rejected(code)` (a typed rejection; zero writes).
 * An unresolved (still-unknown) commit is not a result at all — the surface keeps its submitting
 * marker and this gate is never consulted. Correction and void/restore share the merged decision
 * so the two host call sites cannot diverge.
 */
internal fun shouldRefreshAfterP705Commit(result: CorrectTransactionVersionResult): Boolean = result is CorrectTransactionVersionResult.Created || result is CorrectTransactionVersionResult.NoChange

/** The void/restore analogue of [shouldRefreshAfterP705Commit] over the merged result family. */
internal fun shouldRefreshAfterP705Commit(result: VoidTransactionResult): Boolean = result is VoidTransactionResult.Created || result is VoidTransactionResult.NoChange
