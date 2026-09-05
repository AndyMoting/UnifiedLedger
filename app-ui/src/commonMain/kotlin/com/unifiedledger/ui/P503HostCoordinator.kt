package com.unifiedledger.ui

import com.unifiedledger.application.RequestId

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
 */
internal class P503HostCoordinator(
    private val onRefresh: () -> Unit,
    private val onSubmit: (draft: ManualExpenseDraft, requestId: RequestId) -> Unit,
    private val onCheck: (draft: ManualExpenseDraft, requestId: RequestId) -> Unit,
) {
    /** The transient-result instance whose automatic refresh has already been dispatched. */
    private var refreshAfterResultServed: P503AppState? = null

    /** The unknown-commit instance (reference) that already triggered the read-only check. */
    private var unknownCheckServed: P503AppState? = null

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
        val draft: ManualExpenseDraft,
        val requestId: RequestId,
    ) : HostAction

    data object RefreshAfterResult : HostAction

    data class UnknownCheck(
        val draft: ManualExpenseDraft,
        val requestId: RequestId,
    ) : HostAction
}
