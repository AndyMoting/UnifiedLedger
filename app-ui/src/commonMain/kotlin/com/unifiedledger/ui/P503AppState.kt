package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import kotlin.time.Instant

/**
 * P5-03 shared UI state machine (spec sections 7.1-7.2). Exactly fourteen states.
 *
 * `OverviewEmpty` carries the authoritative current state: an empty ledger renders the
 * empty state, a non-empty ledger renders the transaction list and per-account balances.
 * `Created`/`NoChange`/`Recovered` are transient result states followed by an authoritative
 * refresh back to `OverviewEmpty`.
 */
sealed interface P503AppState {
    data object Starting : P503AppState

    data object Ready : P503AppState

    /** LocalDatabaseUnavailable: the demo has exactly one startup failure mode (finding P503Q-011). */
    data object StartupError : P503AppState

    data class OverviewEmpty(
        val state: LedgerCurrentState,
        val selectedTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data class Editing(
        val draft: ManualExpenseDraft,
        val requestId: RequestId?,
        // P5-04.2: overview snapshot + source tab captured when the editor flow started.
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data class AwaitingConfirmation(
        val draft: ManualExpenseDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data class Submitting(
        val draft: ManualExpenseDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data object Created : P503AppState

    data object NoChange : P503AppState

    data class RequestIdentityConflict(
        val draft: ManualExpenseDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data class DomainRejected(
        val draft: ManualExpenseDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data class InfrastructureFailure(
        val context: InfrastructureFailureContext,
        // context == SUBMISSION: draft/requestId are always present (same-intent retry/return);
        // context == READ: both are null (finding P503Q-014).
        val draft: ManualExpenseDraft? = null,
        val requestId: RequestId? = null,
        // P5-04.2: overview snapshot + source tab (meaningful only for SUBMISSION; READ is null).
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data object UnknownCommit : P503AppState

    data object Recovered : P503AppState
}

/**
 * P5-04.1 overview tabs. Tab selection is part of the shared reducer state, so an
 * authoritative refresh can always return the overview to the home tab.
 */
enum class P503Tab {
    HOME,
    ACCOUNTS,
    ANALYSIS,
}

enum class InfrastructureFailureContext {
    READ,
    SUBMISSION,
}

data class ManualExpenseDraft(
    val paymentAccountId: AccountId?,
    val categoryId: CategoryId?,
    val amountText: String,
    val occurredAt: Instant?,
)
