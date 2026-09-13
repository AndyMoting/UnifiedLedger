package com.unifiedledger.ui

import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.EntryExpressionCode
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import kotlin.time.Instant

/**
 * P5-03 shared UI state machine (spec sections 7.1-7.2). Exactly twelve states.
 *
 * `Ready` is the platform-startup-concluded entry state that consumes the initial
 * authoritative load. `OverviewEmpty` carries the authoritative current state: an empty
 * ledger renders the empty state, a non-empty ledger renders the transaction list and
 * per-account balances. `Created`/`NoChange`/`Recovered` are transient result states
 * followed by an authoritative refresh back to `OverviewEmpty`. Startup retry ownership
 * belongs to the platform composition roots and their `P503StartupState`, not this machine.
 */
sealed interface P503AppState {
    data object Ready : P503AppState

    /**
     * P7-01.D catalog management lives on the overview as optional fields (spec section 7):
     * when [selectedTab] is ACCOUNTS the shell renders the manageable accounts plus the shared
     * two-level category tree instead of the read-only balance list. [catalogSnapshot] is the
     * authoritative projection the host read from the facade (names replacing bare ids);
     * [catalogDialog] is the open form and [catalogNotice] the last outcome banner. Every
     * catalog transition stays in the pure reducer. The defaults keep all pre-P7-01 constructor
     * and copy sites compiling.
     */
    data class OverviewEmpty(
        val state: LedgerCurrentState,
        val selectedTab: P503Tab = P503Tab.HOME,
        val catalogSnapshot: CatalogSnapshotView? = null,
        val catalogDialog: CatalogDialog = CatalogDialog.None,
        val catalogNotice: CatalogNotice? = null,
        /**
         * P7-02.A E-2 (G-C): the intent the host carries after one determinate success
         * (Created/NoChange/Recovered) and its authoritative refresh, so "record again" can
         * start a fresh editor from the retained fields. `null` on startup, initial entry and
         * ordinary refreshes; never persisted.
         */
        val retainedIntent: RetainedEntryIntent? = null,
        /**
         * P7-02.D E-4: the reducer's render copy of the persisted pin set. `TogglePin` flips it
         * on the overview only (ordering preference, zero accounting effect); the host seeds it
         * from the [com.unifiedledger.application.EntryPreferenceStore] on load/refresh and
         * persists each toggle.
         */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
    ) : P503AppState

    data class Editing(
        val draft: TypedEntryDraft,
        val requestId: RequestId?,
        // P5-04.2: overview snapshot + source tab captured when the editor flow started.
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        /**
         * P7-02.D E-3 (P2-6): the current entry-expression evaluation result. The calculator
         * shows the exact result (or the typed rejection) first; only an explicit
         * `ApplyExpressionResult` with a valid preview rewrites the amount text — the reducer
         * never silently edits the amount.
         */
        val expressionPreview: ExpressionPreview? = null,
        /**
         * P702SPEC-03: the open counterparty create/rename form, if any. Editor-local dialog
         * state (mirrors the P7-01 catalog dialog pattern at much smaller scope); it is dropped
         * by every transition away from `Editing` and never blocks the draft.
         */
        val counterpartyDialog: CounterpartyDialog? = null,
    ) : P503AppState

    data class AwaitingConfirmation(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        // P5-04.3: display labels carried from Continue (the host resolves them from
        // ManualExpenseOptions); the reducer falls back to the draft id values, so the
        // empty default is defensive only.
        val paymentAccountLabel: String = "",
        val categoryLabel: String = "",
    ) : P503AppState

    data class Submitting(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data object Created : P503AppState

    data object NoChange : P503AppState

    data class RequestIdentityConflict(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data class DomainRejected(
        val draft: TypedEntryDraft,
        val requestId: RequestId,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    data class InfrastructureFailure(
        val context: InfrastructureFailureContext,
        // context == SUBMISSION: draft/requestId are always present (same-intent retry/return);
        // context == READ: both are null (finding P503Q-014).
        val draft: TypedEntryDraft? = null,
        val requestId: RequestId? = null,
        // P5-04.2: overview snapshot + source tab (meaningful only for SUBMISSION; READ is null).
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
    ) : P503AppState

    /**
     * P5-04.3: carries the flow context so the host can run a read-only commit-status
     * check and the flow can leave via Recovered/RequestIdentityConflict; nullable fields
     * follow the InfrastructureFailure SUBMISSION precedent.
     */
    data class UnknownCommit(
        val draft: TypedEntryDraft? = null,
        val requestId: RequestId? = null,
        val overview: LedgerCurrentState? = null,
        val originTab: P503Tab = P503Tab.HOME,
        val lastCheckOutcome: UnknownCommitCheckOutcome = UnknownCommitCheckOutcome.NONE,
    ) : P503AppState

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

/**
 * P5-04.3 unknown-commit check outcome: NONE means a check is in flight; ABSENT and
 * UNAVAILABLE stay actionable with a manual re-check and never claim success or failure.
 */
enum class UnknownCommitCheckOutcome {
    NONE,
    ABSENT,
    UNAVAILABLE,
}

/**
 * P7-02.A E-2: retained intent for "record again". The host holds this in memory across
 * Submitting/UnknownCommit/Recovered; it is injected into [P503AppState.OverviewEmpty] through
 * [P503UiEvent.RefreshResult.retainedIntent] after a determinate success and is never persisted
 * across Exit or sessions.
 */
data class RetainedEntryIntent(
    val type: EntryType,
    val amountText: String,
    val paymentAccountId: AccountId?,
    val categoryId: CategoryId?,
    val note: String,
    val occurredAt: Instant?,
    val originTab: P503Tab,
)

/**
 * P702SPEC-03: the minimal counterparty directory form (create; rename one existing object).
 * No delete and no directory management screen — this only completes the L-1 invocation path
 * from the LEND/COLLECT editors.
 */
sealed interface CounterpartyDialog {
    data class Create(
        val nameText: String = "",
    ) : CounterpartyDialog

    data class Rename(
        val counterpartyId: CounterpartyId,
        val currentName: String,
        val nameText: String,
    ) : CounterpartyDialog
}

/**
 * P7-02.D E-3: the exact expression evaluation result shown by the calculator. A valid preview
 * carries the exact minor units and the display text at the currency precision; an invalid one
 * carries the typed rejection code of the section 5.3 table.
 */
sealed interface ExpressionPreview {
    data class Valid(
        val minorUnits: Long,
        val displayText: String,
    ) : ExpressionPreview

    data class Invalid(
        val code: EntryExpressionCode,
    ) : ExpressionPreview
}

/**
 * The expense draft's former name, kept as a source-compatible alias so pre-P7-02 constructor
 * call sites (tests, legacy reducers) keep compiling; [ExpenseDraft] is the sealed
 * [TypedEntryDraft] EXPENSE subclass and is value-compatible with the old shape.
 */
typealias ManualExpenseDraft = ExpenseDraft
