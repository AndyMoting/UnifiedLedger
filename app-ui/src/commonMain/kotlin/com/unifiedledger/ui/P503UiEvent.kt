package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import kotlin.time.Instant

/**
 * P5-03 UI events (spec section 7.1). User-initiated events plus the seven async result
 * events. The UI host executes all asynchronous work and dispatches result events; the
 * reducer only consumes events and never performs IO or holds a fallible handle.
 */
sealed interface P503UiEvent {
    // ---- user-initiated events ----
    data object StartRetry : P503UiEvent

    data object Exit : P503UiEvent

    data object StartNewExpense : P503UiEvent

    /** Switches the overview tab; valid only while the overview is on screen. */
    data class SelectTab(
        val tab: P503Tab,
    ) : P503UiEvent

    data class UpdateAmount(
        val text: String,
    ) : P503UiEvent

    data class UpdatePaymentAccount(
        val accountId: AccountId,
    ) : P503UiEvent

    data class UpdateCategory(
        val categoryId: CategoryId,
    ) : P503UiEvent

    data class UpdateOccurredAt(
        val instant: Instant,
    ) : P503UiEvent

    /**
     * The host obtains the requestId per spec section 4.6 and dispatches it. P5-04.3: the
     * host may attach display labels resolved from ManualExpenseOptions; the reducer falls
     * back to the draft id values when a label is absent.
     */
    data class Continue(
        val requestId: RequestId,
        val paymentAccountLabel: String? = null,
        val categoryLabel: String? = null,
    ) : P503UiEvent

    data object Cancel : P503UiEvent

    data object Confirm : P503UiEvent

    data object RetrySubmission : P503UiEvent

    data object RetryRefresh : P503UiEvent

    /** Manual re-check of an unknown commit (P5-04.3); the host runs the read-only resolve. */
    data object RetryCommitStatusCheck : P503UiEvent

    data object AbandonConflict : P503UiEvent

    /** System back: closes the editor flow back to the originating overview tab (P5-04.2). */
    data object Back : P503UiEvent

    // ---- async result events ----
    data object StartupCompleted : P503UiEvent

    data object StartupFailed : P503UiEvent

    data class InitialLoadResult(
        val currentState: LedgerCurrentState,
    ) : P503UiEvent

    data object InitialLoadFailed : P503UiEvent

    data class SubmissionResult(
        val result: ManualExpenseSubmissionResult,
    ) : P503UiEvent

    /** Async result of one unknown-commit status check (P5-04.3); frozen four-outcome union. */
    data class CommitStatusResolved(
        val resolution: ManualExpenseCommitResolution,
    ) : P503UiEvent

    data class RefreshResult(
        val currentState: LedgerCurrentState,
    ) : P503UiEvent

    data object RefreshFailed : P503UiEvent
}
