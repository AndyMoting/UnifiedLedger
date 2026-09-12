package com.unifiedledger.ui

import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import kotlin.time.Instant

/**
 * P5-03 UI events (spec section 7.1). User-initiated events plus the seven async result
 * events. The UI host executes all asynchronous work and dispatches result events; the
 * reducer only consumes events and never performs IO or holds a fallible handle.
 */
sealed interface P503UiEvent {
    // ---- user-initiated events ----
    data object Exit : P503UiEvent

    data object StartNewExpense : P503UiEvent

    /**
     * Switches the overview tab; valid only while the overview is on screen. P7-01.D: when the
     * host switches to ACCOUNTS it also passes the authoritative [catalogSnapshot] it read from
     * the facade, so the management surface never renders bare ids.
     */
    data class SelectTab(
        val tab: P503Tab,
        val catalogSnapshot: CatalogSnapshotView? = null,
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

    // ---- P7-01.D catalog management events (spec section 7) ----
    data class OpenAccountCreateDialog(
        val kind: AccountKind = AccountKind.ASSET,
    ) : P503UiEvent

    data class OpenAccountRenameDialog(
        val accountId: AccountId,
        val currentName: String,
    ) : P503UiEvent

    data class OpenCategoryGroupDialog(
        val kind: CategoryKind,
    ) : P503UiEvent

    data class OpenCategoryAppendChildDialog(
        val parentId: CategoryId,
    ) : P503UiEvent

    data class OpenCategoryRenameDialog(
        val categoryId: CategoryId,
        val currentName: String,
    ) : P503UiEvent

    data class OpenCategoryDeleteDialog(
        val categoryId: CategoryId,
    ) : P503UiEvent

    /** A-5/C-5: one-tap deactivate/reactivate; the host runs the command immediately. */
    data class ManageAccountActive(
        val accountId: AccountId,
        val active: Boolean,
    ) : P503UiEvent

    /** C-5/C-6: one-tap deactivate for a leaf or a whole group. */
    data class ManageCategoryActive(
        val categoryId: CategoryId,
        val active: Boolean,
    ) : P503UiEvent

    /**
     * C-8: "整组启用" is a distinct command
     * ([com.unifiedledger.application.CatalogCommandPayload.EnableCategoryGroup]); unlike
     * [ManageCategoryActive] it also reactivates every child. The host executes it and dispatches
     * [CatalogCommandCompleted]; the pure reducer only absorbs the intent.
     */
    data class EnableCategoryGroup(
        val parentId: CategoryId,
    ) : P503UiEvent

    data class UpdateCatalogFormText(
        val text: String,
    ) : P503UiEvent

    data class UpdateCatalogFormSecondaryText(
        val text: String,
    ) : P503UiEvent

    data class UpdateCatalogFormKind(
        val kind: AccountKind,
    ) : P503UiEvent

    data object DismissCatalogDialog : P503UiEvent

    data object DismissCatalogNotice : P503UiEvent

    /**
     * Async completion of one catalog command. The host ran the command, then (on success)
     * called `refreshCatalog` and read the fresh authoritative snapshot. The reducer only maps
     * the result to a banner and stores the snapshot; it never retries a stale write.
     */
    data class CatalogCommandCompleted(
        val result: CatalogCommandResult,
        val snapshot: CatalogSnapshotView,
    ) : P503UiEvent

    /** Authoritative snapshot produced by an explicit refresh with no command (e.g. after conflict). */
    data class CatalogSnapshotRefreshed(
        val snapshot: CatalogSnapshotView,
    ) : P503UiEvent

    // ---- async result events ----
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
