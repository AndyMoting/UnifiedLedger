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
     *
     * [pinned] is the store's authoritative post-toggle membership
     * ([com.unifiedledger.application.EntryPinResult.Toggled.pinned]); the reducer sets the render
     * copy to that value instead of flipping its own, so a render copy that had diverged can never
     * invert the persisted state (A02PIN-002). [catalogSnapshot] is the management projection
     * re-derived from the updated pin set by the host, so the ACCOUNTS lists re-sort on the spot
     * (A02PIN-001); `null` keeps the current projection and never touches the notice/dialog.
     */
    data class TogglePin(
        val target: EntryPinTarget,
        val pinned: Boolean,
        val catalogSnapshot: CatalogSnapshotView? = null,
    ) : P503UiEvent

    /**
     * The host obtains the requestId per spec section 4.6 and dispatches it. P5-04.3: the
     * host may attach display labels resolved from ManualExpenseOptions; the reducer falls
     * back to the draft id values when a label is absent. A-02 FIX-CONFIRM-1 adds the two
     * type-owned labels the confirmation page needs: the transfer destination account and the
     * lending counterparty. Both stay `null` for the types that own no such row.
     */
    data class Continue(
        val requestId: RequestId,
        val paymentAccountLabel: String? = null,
        val categoryLabel: String? = null,
        val destinationAccountLabel: String? = null,
        val counterpartyLabel: String? = null,
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
     * or, when none is selected, 本月 resolved from the reducer's injected clock. The shift uses
     * the same admission rule as [SelectMonth] (P703SPEC-10): a target outside the selectable
     * domain `[first transaction statistics month, 本月]`, and any shift without a usable base or
     * domain, is absorbed with zero state change, so the analysis region can never request a
     * month the selector will never offer. The host re-requests the monthly payload only when
     * the reduced shift actually moved the cursor inside that domain (trigger (c) of the frozen
     * P703SPEC-04 re-request set, spec 6.2): an absorbed shift re-requests nothing.
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

    // ---- P7-04.C import review events (D-146; spec sections 3.3.1/6.1/6.2, table 6.2a) ----
    // Discipline: every new event is absorbed in every state outside its designed effect and
    // never throws anywhere (table 6.2a); every pre-existing unlisted combination stays ISE (G-B).

    /**
     * Starts one platform file pick for the matrix format. Effect on OverviewEmpty is the host's
     * pick launch (经宿主发起平台选择器；不切态 — the reducer state itself is unchanged, the
     * coordinator/testable host action owns the effect); absorbed everywhere else.
     */
    data class StartImportFilePick(
        val format: com.unifiedledger.application.ImportFormatId,
    ) : P503UiEvent

    /**
     * Host-channel pick events (table 6.2a: absorbed in EVERY state — the pick itself never
     * switches state). A [ImportFilePicked] file's bounded read + intake run off the UI thread and
     * the result flows back as [ImportFileIntakeResult]; [ImportFilePickCancelled] is the user's
     * cancellation (non-failure); [ImportFilePickFailed] is the typed platform launch failure.
     */
    data class ImportFilePicked(
        val file: PickedImportFile,
    ) : P503UiEvent

    data object ImportFilePickCancelled : P503UiEvent

    data class ImportFilePickFailed(
        val reason: ImportFilePickFailure,
    ) : P503UiEvent

    /**
     * The pick pipeline's result (OverviewEmpty effect: the session summary replaces the previous
     * one; a successful intake replaces the candidate list, a typed failure keeps the previous
     * list and surfaces the explicit failure banner). Absorbed everywhere else.
     */
    data class ImportFileIntakeResult(
        val session: ImportIntakeSessionSummary,
        val rows: com.unifiedledger.application.ImportReviewRowsResult,
    ) : P503UiEvent

    /**
     * Review-list refresh request intent. Effect on OverviewEmpty is the host's re-read (the
     * reducer state itself is unchanged; the result lands as [ImportReviewResult]); absorbed
     * everywhere else.
     */
    data object RefreshImportReview : P503UiEvent

    /**
     * The review-list read result (OverviewEmpty effect: success replaces the rows and clears the
     * banner; a typed failure keeps the previous successful list and surfaces the explicit
     * failure banner, F1). Absorbed everywhere else (the list refreshes after the detail closes).
     */
    data class ImportReviewResult(
        val result: com.unifiedledger.application.ImportReviewRowsResult,
    ) : P503UiEvent

    /**
     * Opens the import candidate detail from an IMPORT list row (仅 IMPORT 清单行可达； the
     * SelectTransaction precedent — the reducer effect is unconditional, the UI affordance
     * restricts). [detail] and [duplicates] are the host-resolved typed payloads; the reducer
     * restores the same candidate's in-session decision draft when one exists.
     */
    data class SelectImportCandidate(
        val candidateId: com.unifiedledger.application.ImportCandidateId,
        val detail: com.unifiedledger.application.ImportCandidateDetailResult,
        val duplicates: com.unifiedledger.application.ImportDuplicateReviewsResult,
    ) : P503UiEvent

    /**
     * Closes the detail back to the exact preserved IMPORT overview (list payload and selection
     * kept; the decision draft written back so a re-enter of the same candidate keeps it,
     * spec section 6.2 表单字段保留). Effect only on ImportCandidateDetail; absorbed everywhere
     * else.
     */
    data object CloseImportCandidateDetail : P503UiEvent

    /**
     * One typed decision-field update of the detail's pure form (表单为纯 reducer 草稿). Effect
     * only on ImportCandidateDetail; absorbed everywhere else.
     */
    data class UpdateImportDecisionField(
        val update: ImportDecisionFieldUpdate,
    ) : P503UiEvent

    /**
     * Toggles one candidate in the batch-selection set under the section 3.3.1 gate (先审后勾：
     * a non-selectable classification absorbs the toggle; unknown ids absorb). Effect on
     * OverviewEmpty and ImportCandidateDetail (详情内勾选同门 — the detail updates its carried
     * overview's selection); absorbed everywhere else.
     */
    data class ToggleImportCandidateSelection(
        val candidateId: com.unifiedledger.application.ImportCandidateId,
    ) : P503UiEvent

    /**
     * Submits one duplicate review to the core use case (decision set frozen to the three values,
     * P704SPEC-09; the target is the detail's first unreviewed EXACT_BUSINESS_TUPLE/DEFERRED row).
     * Effect only on ImportCandidateDetail (the host calls the core use case and returns
     * [ImportDuplicateReviewResult]; a duplicate submit while one is in flight is absorbed —
     * 期间禁重复提交); absorbed everywhere else.
     */
    data class SubmitImportDuplicateReview(
        val decision: ImportDuplicateReviewUiDecision,
        val reasonToken: String,
    ) : P503UiEvent

    /**
     * The core duplicate-review result plus the host's post-review re-read (OverviewEmpty and
     * ImportCandidateDetail effect: success refreshes the list/详情重复状态; a typed rejection is
     * presented typed with zero writes). Absorbed everywhere else.
     *
     * P704D-SPEC-02 (final delta, registered for review — spec section 6.1 names the nominal
     * `(result)` shape): the payload now also carries the UI-owned infrastructure-failure code of
     * a submission whose EXECUTION threw (the core never returned a verdict; the store's claim
     * transaction rolled back — zero writes, never a fabricated `SPINE_` diagnostic). Host
     * contract: EITHER `review != null && refresh != null && uiFailureCode == null` (the
     * unchanged core-verdict path) OR `review == null && refresh == null && uiFailureCode != null`
     * (the UI failure path: the reducer clears the in-flight marker, surfaces the typed banner
     * and keeps the previous payloads, F1). The nullability widening keeps every pre-existing
     * constructor site compiling unchanged.
     */
    data class ImportDuplicateReviewResult(
        val review: com.unifiedledger.application.ImportDuplicateReviewResult?,
        val refresh: ImportDuplicateReviewRefresh?,
        val uiFailureCode: String? = null,
    ) : P503UiEvent

    /**
     * P704SPEC-12 registered mechanism: opens the 整组确认页 for the current pick session's
     * suspected-duplicate group. The host resolved the per-item enumeration (each item one
     * duplicate candidate with its privacy-safe comparison snapshot); the page's confirm action
     * authorizes the per-item sequential core review loop (逐项独立 requestId/reviewId、可见部分
     * 成功、已成功项经组重算幂等不重复处置). Effect only on OverviewEmpty; absorbed everywhere else.
     */
    data class StartImportDuplicateGroupDisposition(
        val inputRef: String,
        val items: List<ImportDuplicateGroupDispositionItem>,
    ) : P503UiEvent

    /**
     * The group disposition loop's completion: the per-item outcomes plus the refreshed list
     * (OverviewEmpty effect; a list re-read failure keeps the previous rows and surfaces the
     * typed banner, F1). Absorbed everywhere else.
     */
    data class ImportDuplicateGroupDispositionResult(
        val outcomes: List<ImportDuplicateGroupItemOutcome>,
        val rows: com.unifiedledger.application.ImportReviewRowsResult,
    ) : P503UiEvent

    /** Closes the group disposition page (already-disposed items stay disposed core-side). */
    data object CloseImportDuplicateGroupDisposition : P503UiEvent

    // ---- P7-05 enumeration performance events (session-level batch read + progress surface) ----

    /**
     * P7-05: the host's session-level enumeration behind the 整组确认页 started. Effect only on
     * OverviewEmpty (sets [ImportReviewView.groupEnumerationInProgress] so the overview renders
     * the explicit progress line); absorbed everywhere else. The host dispatches it right
     * before running the single-flight enumeration and always follows it by
     * [ImportGroupEnumerationCompleted].
     */
    data object ImportGroupEnumerationStarted : P503UiEvent

    /**
     * P7-05: the session-level enumeration finished (Ready or ReadFailed, whatever the
     * outcome). Effect only on OverviewEmpty (clears the in-progress marker); absorbed
     * everywhere else. The host dispatches it in the enumeration's final main-dispatcher hop.
     */
    data object ImportGroupEnumerationCompleted : P503UiEvent

    // ---- P7-04.D batch confirmation events (D-146; spec sections 3.2.3/3.3.2/6.2 table 6.2a) ----
    // Discipline: every event is absorbed in every state outside its designed effect and never
    // throws anywhere (table 6.2a); every pre-existing unlisted combination stays ISE (G-B).

    /**
     * Opens the 授权快照确认页. OverviewEmpty effect only for a non-empty selection (空集
     * absorbed); the ImportCandidateDetail effect 携详情决策进入确认页 (the reducer writes the
     * detail's draft back into the carried overview so the confirm page presents it, SPEC:283).
     * Absorbed everywhere else.
     */
    data object RequestImportBatchConfirm : P503UiEvent

    /**
     * Cancels the confirm page back to the exact preserved overview (保留勾选集与清单).
     * Effect only on ImportBatchConfirm; absorbed everywhere else.
     */
    data object CancelImportBatchConfirm : P503UiEvent

    /**
     * The authorization action. [confirmedAt] is the host's ONE LedgerClock sample of this user
     * action (Q09.4: 经 explicitConfirmedAt 全项复用， mixed 必填； the reducer is IO-free and
     * randomness-free, so the sample and the per-item requestIds are host-minted and ride the
     * event — the SelectTransaction host-resolved-payload precedent). Effect only on
     * ImportBatchConfirm: builds the authorization snapshot (the deterministic selection
     * ordering) and enters [P503AppState.ImportBatchSubmitting]; the host then starts the
     * sequential per-item dispatch. A selected id without a minted requestId absorbs
     * defensively (the wired host always mints one per selected candidate). Absorbed everywhere
     * else.
     */
    data class AuthorizeImportBatch(
        val confirmedAt: String,
        val requestIds: Map<com.unifiedledger.application.ImportCandidateId, com.unifiedledger.application.ImportRequestId>,
    ) : P503UiEvent

    /**
     * One per-item dispatch outcome (table 6.2a `ImportItemResult(item, outcome)`). Effect only
     * on ImportBatchSubmitting: records the item's outcome (a resolved item is never
     * overwritten); an [ImportBatchItemOutcome.Unknown] pauses the loop (置核对入口； dispatchPaused)
     * and every item reaching a terminal outcome moves the batch closer to the automatic leave
     * (全部项终态 → OverviewEmpty(IMPORT) 保留结果摘要). Absorbed everywhere else.
     */
    data class ImportItemResult(
        val item: ImportBatchItem,
        val outcome: ImportBatchItemOutcome,
    ) : P503UiEvent

    /**
     * The paused batch's explicit continue: the host dispatches it and then runs the loop
     * continuation over the still-undispatched items (同授权快照内， 复用同次 LedgerClock 取样与既有
     * requestId； 已核对项不重复派发). Effect only on ImportBatchSubmitting: with no undispatched item
     * left the state leaves to OverviewEmpty(IMPORT) with the result summary (Unknown items keep
     * their check entries there); otherwise the pause flag clears and the run continues.
     * Absorbed everywhere else.
     */
    data object ResumeImportBatchDispatch : P503UiEvent

    /**
     * The paused batch's explicit abandon (义务③： 授权快照解散， 剩余项即普通待确认清单项——无隐藏
     * 中间 UI 态). Effect only on ImportBatchSubmitting: straight to OverviewEmpty(IMPORT) with the
     * completed items' result summary retained (未派发项持久状态保持 pending_confirmation， 可再
     * 授权——新授权 = 新意图、新 requestId 与新时钟取样). Absorbed everywhere else.
     */
    data object AbandonImportBatch : P503UiEvent

    /**
     * The 核对 intent for one Unknown item (Q10.2): the host runs the equivalent replay (同
     * requestId + 等价 snapshot, the spine's claim-gated resolveConfirm) — the reducer state is
     * unchanged (不切态； the coordinator test pins the host action). The check target lives in the
     * submitting snapshot or in the overview's retained result summary (核对入口在 IMPORT 结果摘要
     * 内). Absorbed everywhere else.
     */
    data class ImportUnknownItemCheck(
        val candidateId: com.unifiedledger.application.ImportCandidateId,
    ) : P503UiEvent

    /**
     * The replay verdict of one Unknown item ([item] identifies the checked item — more than one
     * Unknown can accumulate across pauses, so the result must name its target; the spec left the
     * payload shape open). Effect on ImportBatchSubmitting (更新该项； 仅全部项终态后可离开) and on
     * OverviewEmpty (the summary item's outcome updates in place). Absorbed everywhere else.
     */
    data class ImportUnknownItemCheckResult(
        val item: ImportBatchItem,
        val outcome: ImportUnknownCheckOutcome,
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
         * fresh overview (success result / READ retry). A-02 FIX-PIN-4: `null` keeps the pin set
         * the reduced state already carries (the ordinary overview refresh self-heals from the
         * host mirror only when the host supplies one), so a refresh can never silently empty a
         * pin set it did not read.
         */
        val pinnedTargets: Set<EntryPinTarget>? = null,
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
