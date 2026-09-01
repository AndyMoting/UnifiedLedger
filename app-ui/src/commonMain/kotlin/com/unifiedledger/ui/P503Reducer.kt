package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CurrencyUnit

/**
 * Pure UI state-machine reducer (spec sections 7.1-7.2). The reducer never performs IO,
 * randomness or facade calls; it only consumes events. Any reachable (state, event)
 * combination not listed in the transition table is a programming error and fails fast.
 */
fun interface P503Reducer {
    fun reduce(
        state: P503AppState,
        event: P503UiEvent,
    ): P503AppState
}

class P503ReducerImpl(
    parseAmount: ParseManualExpenseAmount,
    private val currency: CurrencyUnit,
) : P503Reducer {
    private val validation = P503DraftValidation(parseAmount)

    override fun reduce(
        state: P503AppState,
        event: P503UiEvent,
    ): P503AppState =
        when (state) {
            is P503AppState.Starting -> reduceStarting(event)
            is P503AppState.Ready -> reduceReady(event)
            is P503AppState.StartupError -> reduceStartupError(event)
            is P503AppState.OverviewEmpty -> reduceOverviewEmpty(state, event)
            is P503AppState.Editing -> reduceEditing(state, event)
            is P503AppState.AwaitingConfirmation -> reduceAwaitingConfirmation(state, event)
            is P503AppState.Submitting -> reduceSubmitting(state, event)
            is P503AppState.Created -> reduceTransientResult(event, state)
            is P503AppState.NoChange -> reduceTransientResult(event, state)
            is P503AppState.Recovered -> reduceTransientResult(event, state)
            is P503AppState.RequestIdentityConflict -> reduceRequestIdentityConflict(state, event)
            is P503AppState.DomainRejected -> reduceDomainRejected(state, event)
            is P503AppState.InfrastructureFailure -> reduceInfrastructureFailure(state, event)
            is P503AppState.UnknownCommit -> P503AppState.UnknownCommit
        }

    private fun reduceStarting(event: P503UiEvent): P503AppState =
        when (event) {
            P503UiEvent.StartupCompleted -> P503AppState.Ready
            P503UiEvent.StartupFailed -> P503AppState.StartupError
            else -> unhandled(P503AppState.Starting, event)
        }

    private fun reduceReady(event: P503UiEvent): P503AppState =
        when (event) {
            // The authoritative load always lands on the home tab (D-122).
            is P503UiEvent.InitialLoadResult -> P503AppState.OverviewEmpty(event.currentState, P503Tab.HOME)
            P503UiEvent.InitialLoadFailed -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
            else -> unhandled(P503AppState.Ready, event)
        }

    private fun reduceStartupError(event: P503UiEvent): P503AppState =
        when (event) {
            P503UiEvent.StartRetry -> P503AppState.Starting
            // Exit closes the process/window; the host handles the callback and the
            // reducer keeps the state unchanged (no transition row exists).
            P503UiEvent.Exit -> P503AppState.StartupError
            else -> unhandled(P503AppState.StartupError, event)
        }

    private fun reduceOverviewEmpty(
        state: P503AppState.OverviewEmpty,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            // Tab switching keeps the same authoritative LedgerCurrentState reference; no
            // data is re-fetched and the selection survives only within the overview.
            is P503UiEvent.SelectTab -> state.copy(selectedTab = event.tab)
            P503UiEvent.StartNewExpense ->
                P503AppState.Editing(
                    draft =
                        ManualExpenseDraft(
                            paymentAccountId = null,
                            categoryId = null,
                            amountText = "",
                            occurredAt = null,
                        ),
                    requestId = null,
                )
            else -> unhandled(state, event)
        }

    private fun reduceEditing(
        state: P503AppState.Editing,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.UpdateAmount ->
                state.copy(draft = state.draft.copy(amountText = event.text))
            is P503UiEvent.UpdatePaymentAccount ->
                state.copy(draft = state.draft.copy(paymentAccountId = event.accountId))
            is P503UiEvent.UpdateCategory ->
                state.copy(draft = state.draft.copy(categoryId = event.categoryId))
            is P503UiEvent.UpdateOccurredAt ->
                state.copy(draft = state.draft.copy(occurredAt = event.instant))
            is P503UiEvent.Continue ->
                if (validation.isValid(state.draft, currency)) {
                    P503AppState.AwaitingConfirmation(state.draft, event.requestId)
                } else {
                    // Field error retains input and the (already allocated) requestId.
                    state.copy(requestId = event.requestId)
                }
            else -> unhandled(state, event)
        }

    private fun reduceAwaitingConfirmation(
        state: P503AppState.AwaitingConfirmation,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            // Cancelling an unsubmitted draft abandons the save intent; the requestId may
            // be discarded and a later Continue allocates a new one (spec 7.4).
            P503UiEvent.Cancel ->
                P503AppState.Editing(
                    draft = state.draft,
                    requestId = null,
                )
            P503UiEvent.Confirm ->
                P503AppState.Submitting(
                    draft = state.draft,
                    requestId = state.requestId,
                )
            else -> unhandled(state, event)
        }

    private fun reduceSubmitting(
        state: P503AppState.Submitting,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.SubmissionResult ->
                when (val result = event.result) {
                    is ManualExpenseSubmissionResult.Application ->
                        when (val application = result.result) {
                            is ManualExpenseSaveResult.InvalidInput ->
                                P503AppState.Editing(state.draft, state.requestId)
                            is ManualExpenseSaveResult.Executed ->
                                when (application.result) {
                                    is ConfirmedManualExpenseResult.Created -> P503AppState.Created
                                    is ConfirmedManualExpenseResult.NoChange -> P503AppState.NoChange
                                    is ConfirmedManualExpenseResult.RequestIdentityConflict ->
                                        P503AppState.RequestIdentityConflict(state.draft, state.requestId)
                                    is ConfirmedManualExpenseResult.Rejected ->
                                        P503AppState.DomainRejected(state.draft, state.requestId)
                                }
                        }
                    is ManualExpenseSubmissionResult.InfrastructureFailure ->
                        P503AppState.InfrastructureFailure(
                            context = InfrastructureFailureContext.SUBMISSION,
                            draft = state.draft,
                            requestId = state.requestId,
                        )
                    is ManualExpenseSubmissionResult.UnknownCommit -> P503AppState.UnknownCommit
                    is ManualExpenseSubmissionResult.Recovered -> P503AppState.Recovered
                }
            else -> unhandled(state, event)
        }

    private fun reduceTransientResult(
        event: P503UiEvent,
        current: P503AppState,
    ): P503AppState =
        when (event) {
            // The authoritative refresh after a submission flow always returns to the home
            // tab; the submission states carry no tab.
            is P503UiEvent.RefreshResult -> P503AppState.OverviewEmpty(event.currentState, P503Tab.HOME)
            P503UiEvent.RefreshFailed -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
            else -> unhandled(current, event)
        }

    private fun reduceRequestIdentityConflict(
        state: P503AppState.RequestIdentityConflict,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.UpdateAmount ->
                P503AppState.Editing(state.draft.copy(amountText = event.text), state.requestId)
            is P503UiEvent.UpdatePaymentAccount ->
                P503AppState.Editing(state.draft.copy(paymentAccountId = event.accountId), state.requestId)
            is P503UiEvent.UpdateCategory ->
                P503AppState.Editing(state.draft.copy(categoryId = event.categoryId), state.requestId)
            is P503UiEvent.UpdateOccurredAt ->
                P503AppState.Editing(state.draft.copy(occurredAt = event.instant), state.requestId)
            // Explicitly abandoning the conflicting draft starts a new save intent.
            P503UiEvent.AbandonConflict ->
                P503AppState.Editing(
                    draft = state.draft,
                    requestId = null,
                )
            else -> unhandled(state, event)
        }

    private fun reduceDomainRejected(
        state: P503AppState.DomainRejected,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.UpdateAmount ->
                P503AppState.Editing(state.draft.copy(amountText = event.text), state.requestId)
            is P503UiEvent.UpdatePaymentAccount ->
                P503AppState.Editing(state.draft.copy(paymentAccountId = event.accountId), state.requestId)
            is P503UiEvent.UpdateCategory ->
                P503AppState.Editing(state.draft.copy(categoryId = event.categoryId), state.requestId)
            is P503UiEvent.UpdateOccurredAt ->
                P503AppState.Editing(state.draft.copy(occurredAt = event.instant), state.requestId)
            else -> unhandled(state, event)
        }

    private fun reduceInfrastructureFailure(
        state: P503AppState.InfrastructureFailure,
        event: P503UiEvent,
    ): P503AppState =
        when (state.context) {
            InfrastructureFailureContext.SUBMISSION ->
                when (event) {
                    P503UiEvent.RetrySubmission ->
                        P503AppState.Submitting(
                            draft = checkNotNull(state.draft),
                            requestId = checkNotNull(state.requestId),
                        )
                    P503UiEvent.Cancel ->
                        P503AppState.Editing(
                            draft = checkNotNull(state.draft),
                            requestId = checkNotNull(state.requestId),
                        )
                    else -> unhandled(state, event)
                }
            InfrastructureFailureContext.READ ->
                when (event) {
                    P503UiEvent.RetryRefresh -> state
                    is P503UiEvent.RefreshResult -> P503AppState.OverviewEmpty(event.currentState, P503Tab.HOME)
                    P503UiEvent.RefreshFailed -> state
                    else -> unhandled(state, event)
                }
        }

    private fun unhandled(
        state: P503AppState,
        event: P503UiEvent,
    ): Nothing = throw IllegalStateException("Unhandled P5-03 event $event in state $state")
}
