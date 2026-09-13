package com.unifiedledger.ui

import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogSnapshotView
import com.unifiedledger.application.EntryPinTarget
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ManualIncomeCommitResolution
import com.unifiedledger.application.ManualIncomeSubmissionResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.TransactionId
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

    // ---- P7-02.A typed-entry events (S-2/S-4) ----

    /**
     * Switches the editor's entry type. Valid only in Editing; the new draft is derived by the
     * frozen [com.unifiedledger.application.EntryFieldRetention] matrix, and a target type the
     * current batch does not implement (LEND/COLLECT pre-C) leaves the state untouched. Absorbed
     * in every other state, never an ISE (§6.2a).
     */
    data class SelectEntryType(
        val type: EntryType,
    ) : P503UiEvent

    /** P7-02.A S-4: writes the optional note draft field. */
    data class UpdateNote(
        val text: String,
    ) : P503UiEvent

    /** P7-02.A income receiving-account field update. */
    data class UpdateReceivingAccount(
        val accountId: AccountId,
    ) : P503UiEvent

    /** P7-02.A income category field update. */
    data class UpdateIncomeCategory(
        val categoryId: CategoryId,
    ) : P503UiEvent

    // ---- P7-02.B transfer events ----

    /** Transfer source (drawer) account update. */
    data class UpdateTransferSourceAccount(
        val accountId: AccountId,
    ) : P503UiEvent

    /** Transfer destination (recipient) account update. */
    data class UpdateTransferDestinationAccount(
        val accountId: AccountId,
    ) : P503UiEvent

    /** Transfer destination-credit amount text update (the transfer main amount field). */
    data class UpdateTransferDestinationCredit(
        val text: String,
    ) : P503UiEvent

    /** Transfer fee text update. */
    data class UpdateTransferFee(
        val text: String,
    ) : P503UiEvent

    /** Transfer fee-category update (only meaningful when fee > 0). */
    data class UpdateTransferFeeCategory(
        val categoryId: CategoryId,
    ) : P503UiEvent

    // ---- P7-02.C lending events ----

    /** Lend counterparty selection (type-specific: cleared on a switch away). */
    data class UpdateLendCounterparty(
        val counterpartyId: CounterpartyId,
    ) : P503UiEvent

    /** Lend funding account (belongs to the shared asset-account class). */
    data class UpdateLendFundingAccount(
        val accountId: AccountId,
    ) : P503UiEvent

    /** Lend principal amount text. */
    data class UpdateLendAmount(
        val text: String,
    ) : P503UiEvent

    /** Collect counterparty selection (type-specific). */
    data class UpdateCollectCounterparty(
        val counterpartyId: CounterpartyId,
    ) : P503UiEvent

    /** Collect destination account. */
    data class UpdateCollectDestinationAccount(
        val accountId: AccountId,
    ) : P503UiEvent

    /** Collect total-received amount text (the collect main amount field). */
    data class UpdateCollectTotal(
        val text: String,
    ) : P503UiEvent

    /** Collect principal component text. */
    data class UpdateCollectPrincipal(
        val text: String,
    ) : P503UiEvent

    /** Collect interest component text. */
    data class UpdateCollectInterest(
        val text: String,
    ) : P503UiEvent

    /** Collect exact active leaf INCOME interest category. */
    data class UpdateCollectInterestCategory(
        val categoryId: CategoryId,
    ) : P503UiEvent

    /**
     * P7-02.A E-2: "record again" from the post-success overview. Only meaningful on the
     * overview with a retained intent; absorbed everywhere else (§6.2a). P7-02.D: [revalidation]
     * is the authoritative catalog view the host snapshots at re-record time, so invalid
     * objects are not carried over; a `null` payload (legacy call sites) keeps the frozen
     * pre-D carry-over behavior.
     */
    data class SaveAndRecordAgain(
        val revalidation: RetainedIntentRevalidation? = null,
    ) : P503UiEvent

    /**
     * P7-02.D E-3: evaluates the amount expression the user typed in the calculator. Only
     * `Editing` reacts (it writes [ExpressionPreview]); every other state absorbs it.
     */
    data class EvaluateEntryExpression(
        val expression: String,
    ) : P503UiEvent

    /**
     * P7-02.D E-3: applies the current preview to the main amount field. Only a valid preview
     * rewrites the amount; without one the event is a no-op on `Editing` and absorbed elsewhere.
     */
    data object ApplyExpressionResult : P503UiEvent

    // ---- P702SPEC-03 counterparty create/rename affordance (editor-local dialog) ----

    /** Opens the counterparty create form in the editor; absorbed everywhere else (§6.2a). */
    data object OpenCounterpartyCreateDialog : P503UiEvent

    /** Opens the rename form for one existing counterparty row; absorbed everywhere else. */
    data class OpenCounterpartyRenameDialog(
        val counterpartyId: CounterpartyId,
        val currentName: String,
    ) : P503UiEvent

    /** Writes the counterparty form's name text while the dialog is open. */
    data class UpdateCounterpartyFormText(
        val text: String,
    ) : P503UiEvent

    /** Closes the counterparty form (dismiss or successful command). */
    data object DismissCounterpartyDialog : P503UiEvent

    /**
     * P7-02.D E-4: toggles one account/category pin from the overview lists. Ordering
     * preference only — zero accounting effect; the host persists it through the
     * EntryPreferenceStore and only dispatches after a successful toggle. Absorbed in every
     * other state (§6.2a).
     */
    data class TogglePin(
        val target: EntryPinTarget,
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

    // ---- P7-03.C/D ledger-view read-only events (spec sections 6.1/6.2; zero accounting effect) ----

    /**
     * Opens the read-only transaction detail from a HOME flow row (仅 HOME 行可达). [result] is
     * the typed payload the host resolved from
     * [com.unifiedledger.application.QueryTransactionDetail] before dispatching (Success or the
     * typed NotFound/InvalidState/Unavailable failure — the page renders all four). Absorbed in
     * every state other than OverviewEmpty (table 6.2a).
     */
    data class SelectTransaction(
        val transactionId: TransactionId,
        val result: com.unifiedledger.application.TransactionDetailResult,
    ) : P503UiEvent

    /**
     * Closes the detail page back to the exact preserved overview (tab, month cursor and
     * monthly payload kept, C03). Effect only on TransactionDetail; absorbed everywhere else.
     */
    data object CloseTransactionDetail : P503UiEvent

    /**
     * Selects the overview month (the shared month cursor of the home month card and the
     * analysis monthly region). Effect only on OverviewEmpty and only within the frozen
     * SelectMonth domain `[first transaction statistics month, 本月]` (P703SPEC-10): an
     * out-of-domain month is absorbed with zero state change, as is any selection on an empty
     * domain (residual boundary (b)). The host re-requests the monthly payload on every
     * accepted selection (trigger (b), spec 6.2).
     */
    data class SelectMonth(
        val month: kotlinx.datetime.YearMonth,
    ) : P503UiEvent

    /**
     * Shifts the shared month cursor by [offset] months for the analysis monthly region
     * (trend/month-card linkage). Effect only on OverviewEmpty; the base is the selected month
     * or, when none is selected, 本月 resolved from the reducer's injected clock — without a
     * usable base the shift is absorbed. The host re-requests the monthly payload on every
     * shift (trigger (c), spec 6.2).
     */
    data class AnalysisMonthShift(
        val offset: Int,
    ) : P503UiEvent

    /**
     * Monthly payload event (RefreshResult-shaped, spec section 6.2 table 6.2a): the host's
     * unified monthly cycle result for the effective overview month plus the fresh SelectMonth
     * domain it read alongside. Success updates the overview payload; a typed failure
     * (InvalidState/Unavailable) surfaces the READ failure while preserving the last successful
     * overview (spec 4.3: 上一成功载荷保留 + 显式失败条). Effect on OverviewEmpty and
     * TransactionDetail (详情态同语义， updating the stored overview); absorbed everywhere else.
     */
    data class MonthlyActivityResult(
        val result: com.unifiedledger.application.MonthlyActivityResult,
        val selectableMonths: List<kotlinx.datetime.YearMonth> = emptyList(),
    ) : P503UiEvent

    // ---- async result events ----
    data class InitialLoadResult(
        val currentState: LedgerCurrentState,
        /**
         * P7-02.D E-4: the persisted pin set the host read from the EntryPreferenceStore at
         * startup, so pins survive an app restart. Backward compatible default.
         */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
    ) : P503UiEvent

    data object InitialLoadFailed : P503UiEvent

    data class SubmissionResult(
        val result: ManualEntrySubmissionResult,
    ) : P503UiEvent {
        /** Source compatibility with pre-P7-02 expense-only call sites. */
        constructor(result: ManualExpenseSubmissionResult) : this(ManualEntrySubmissionResult.Expense(result))

        constructor(result: ManualIncomeSubmissionResult) : this(ManualEntrySubmissionResult.Income(result))
    }

    /** Async result of one unknown-commit status check (P5-04.3); frozen four-outcome union. */
    data class CommitStatusResolved(
        val resolution: ManualEntryCommitResolution,
    ) : P503UiEvent {
        /** Source compatibility with pre-P7-02 expense-only call sites. */
        constructor(resolution: ManualExpenseCommitResolution) : this(ManualEntryCommitResolution.Expense(resolution))

        constructor(resolution: ManualIncomeCommitResolution) : this(ManualEntryCommitResolution.Income(resolution))
    }

    data class RefreshResult(
        val currentState: LedgerCurrentState,
        /**
         * P7-02.A E-2 (G-C): the host injects the retained intent it captured before submission
         * on the authoritative refresh that follows one determinate success
         * (Created/NoChange/Recovered). Backward compatible; `null` for ordinary refreshes.
         */
        val retainedIntent: RetainedEntryIntent? = null,
        /**
         * P7-02.D E-4: the host's current pin mirror, carried on the refreshes that build a
         * fresh overview (success result / READ retry); an ordinary overview refresh keeps the
         * state's existing set. Backward compatible default.
         */
        val pinnedTargets: Set<EntryPinTarget> = emptySet(),
    ) : P503UiEvent

    data object RefreshFailed : P503UiEvent
}

/**
 * P7-02.D E-2: the authoritative catalog view the host snapshots from its current options at
 * re-record time, so the reducer can revalidate the retained intent against the current
 * authoritative catalog and clear (not carry) objects that are no longer offered. All five
 * entry types share the same owned-real-ASSET account option set; the category sets are
 * per kind (EXPENSE for expense/fee categories, INCOME for income/interest categories).
 */
data class RetainedIntentRevalidation(
    val accountIds: Set<AccountId>,
    val expenseCategoryIds: Set<CategoryId>,
    val incomeCategoryIds: Set<CategoryId>,
)
