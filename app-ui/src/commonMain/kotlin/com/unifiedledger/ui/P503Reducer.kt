package com.unifiedledger.ui

import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ConfirmedManualLendingResult
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.EntryExpressionEvaluator
import com.unifiedledger.application.EntryFieldRetention
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.ManualCollectSaveResult
import com.unifiedledger.application.ManualCollectSubmissionResult
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualIncomeCommitResolution
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.ManualIncomeSubmissionResult
import com.unifiedledger.application.ManualLendSaveResult
import com.unifiedledger.application.ManualLendSubmissionResult
import com.unifiedledger.application.ManualLendingCommitResolution
import com.unifiedledger.application.ManualTransferCommitResolution
import com.unifiedledger.application.ManualTransferSaveResult
import com.unifiedledger.application.ManualTransferSubmissionResult
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Pure UI state-machine reducer (spec sections 7.1-7.2; P7-02 section 6). The reducer never
 * performs IO, randomness or facade calls; it only consumes events. Any reachable (state, event)
 * combination not listed in the transition table is a programming error and fails fast.
 *
 * P7-02: drafts are typed ([TypedEntryDraft]) and the entry-foundation events
 * (`SelectEntryType`/`UpdateNote`/per-type field updates) are absorbed in every state other than
 * their documented effects; the existing events keep their frozen transitions and ISE behavior
 * (G-B).
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
    // P7-02.A E-2: only the deferred "record again" effect reads the clock; the default exists
    // so pre-P7-02 constructions keep compiling (the effect then leaves occurredAt null).
    private val ledgerClock: LedgerClock? = null,
    // P7-02.D E-3: the pure expression evaluator behind EvaluateEntryExpression; stateless, so
    // a default instance keeps pre-D constructions compiling without weakening the product path.
    private val expressionEvaluator: EntryExpressionEvaluator = EntryExpressionEvaluator(),
) : P503Reducer {
    private val validation = P503DraftValidation(parseAmount)

    override fun reduce(
        state: P503AppState,
        event: P503UiEvent,
    ): P503AppState =
        when (state) {
            is P503AppState.Ready -> reduceReady(event)
            is P503AppState.OverviewEmpty -> reduceOverviewEmpty(state, event)
            is P503AppState.TransactionDetail -> reduceTransactionDetail(state, event)
            is P503AppState.ImportCandidateDetail -> reduceImportCandidateDetail(state, event)
            is P503AppState.ImportBatchConfirm -> reduceImportBatchConfirm(state, event)
            is P503AppState.ImportBatchSubmitting -> reduceImportBatchSubmitting(state, event)
            is P503AppState.Editing -> reduceEditing(state, event)
            is P503AppState.AwaitingConfirmation -> reduceAwaitingConfirmation(state, event)
            is P503AppState.Submitting -> reduceSubmitting(state, event)
            is P503AppState.Created -> reduceTransientResult(event, state)
            is P503AppState.NoChange -> reduceTransientResult(event, state)
            is P503AppState.Recovered -> reduceTransientResult(event, state)
            is P503AppState.RequestIdentityConflict -> reduceRequestIdentityConflict(state, event)
            is P503AppState.DomainRejected -> reduceDomainRejected(state, event)
            is P503AppState.InfrastructureFailure -> reduceInfrastructureFailure(state, event)
            is P503AppState.UnknownCommit -> reduceUnknownCommit(state, event)
        }

    private fun reduceReady(event: P503UiEvent): P503AppState =
        when (event) {
            // The authoritative load always lands on the home tab (D-122). P7-02.D E-4: the
            // host seeds the persisted pin set read from the EntryPreferenceStore at startup.
            is P503UiEvent.InitialLoadResult -> P503AppState.OverviewEmpty(event.currentState, P503Tab.HOME, pinnedTargets = event.pinnedTargets)
            P503UiEvent.InitialLoadFailed -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
            // P7-02: the entry-foundation events never throw an ISE in any state (§6.2a); before
            // the overview exists they are absorbed.
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P7-03.C/D: the read-only ledger-view events are absorbed before the overview exists (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // P7-04.C: the import review events are absorbed before the overview exists (table 6.2a).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed before the overview exists
            // (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> P503AppState.Ready
            else -> unhandled(P503AppState.Ready, event)
        }

    private fun reduceOverviewEmpty(
        state: P503AppState.OverviewEmpty,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            // Tab switching keeps the same authoritative LedgerCurrentState reference; no
            // data is re-fetched and the selection survives only within the overview. P7-01.D:
            // leaving a management tab drops any open dialog/notice; entering ACCOUNTS replaces
            // the projection with the host-read authoritative snapshot.
            is P503UiEvent.SelectTab ->
                state.copy(
                    selectedTab = event.tab,
                    catalogSnapshot = event.catalogSnapshot ?: state.catalogSnapshot,
                    catalogDialog = CatalogDialog.None,
                    catalogNotice = null,
                )
            // R1 (spec 6.2/7.3): the host re-queries the authoritative read model after a
            // successful catalog command and after an explicit management refresh, so this
            // transition replaces the overview's read state while staying on the current tab
            // (the catalog notice/dialog are management-only fields and are left untouched).
            // P7-02 G-C: an ordinary overview refresh keeps the existing retainedIntent; only
            // the successful-result refresh (reduceTransientResult) overrides it.
            is P503UiEvent.RefreshResult -> state.copy(state = event.currentState)
            P503UiEvent.RefreshFailed -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
            P503UiEvent.StartNewExpense ->
                P503AppState.Editing(
                    draft = ExpenseDraft(paymentAccountId = null, categoryId = null, amountText = "", occurredAt = null),
                    requestId = null,
                    overview = state.state,
                    originTab = state.selectedTab,
                )
            // P7-02.D E-2: only a determinate-success retained intent can start a new editor.
            // The new editor clears the amount, the note and the old confirmation, takes
            // occurredAt from the current clock instant, and leaves the requestId null so the
            // host allocates a fresh one on the next Continue. P7-02.D: the intent is
            // revalidated against the current authoritative catalog (event.revalidation) —
            // objects the catalog no longer offers are not carried over, their fields are
            // cleared. A null payload (legacy call sites) keeps the frozen pre-D carry-over.
            is P503UiEvent.SaveAndRecordAgain ->
                state.retainedIntent?.let { intent ->
                    val accounts = event.revalidation?.accountIds
                    val expenseCategories = event.revalidation?.expenseCategoryIds
                    val incomeCategories = event.revalidation?.incomeCategoryIds
                    val account = intent.paymentAccountId?.takeIf { accounts == null || it in accounts }
                    val expenseCategory = intent.categoryId?.takeIf { expenseCategories == null || it in expenseCategories }
                    val incomeCategory = intent.categoryId?.takeIf { incomeCategories == null || it in incomeCategories }
                    // P702SPEC-02: truncate to whole seconds (D-138 second-precision formats;
                    // kotlin.time.Instant.toString() would carry a fractional part and the frozen
                    // lenient parser rejects it, blocking Continue on every re-record). The
                    // "occurredAt = current clock instant" semantics are kept at second precision.
                    val now = ledgerClock?.now()?.let { kotlin.time.Instant.fromEpochSeconds(it.epochSeconds) }
                    P503AppState.Editing(
                        draft =
                            when (intent.type) {
                                com.unifiedledger.application.EntryType.EXPENSE ->
                                    ExpenseDraft(account, expenseCategory, "", now, "")
                                com.unifiedledger.application.EntryType.INCOME ->
                                    IncomeDraft(account, incomeCategory, "", now, "")
                                com.unifiedledger.application.EntryType.TRANSFER ->
                                    // The destination account and the fee never carry over; the
                                    // fee resets to 0.00 and a zero fee must not carry a fee
                                    // category (E-1 migration table, T-4).
                                    TransferDraft(
                                        sourceAccountId = account,
                                        destinationAccountId = null,
                                        destinationCredit = "",
                                        fee = com.unifiedledger.application.DEFAULT_TRANSFER_FEE_TEXT,
                                        feeCategoryId = null,
                                        occurredAt = now,
                                        note = "",
                                    )
                                com.unifiedledger.application.EntryType.LEND ->
                                    LendDraft(counterpartyId = null, amount = "", fundingAccountId = account, occurredAt = now, note = "")
                                com.unifiedledger.application.EntryType.COLLECT ->
                                    CollectDraft(
                                        counterpartyId = null,
                                        totalReceived = "",
                                        principal = "",
                                        interest = "",
                                        interestCategoryId = incomeCategory,
                                        destinationAccountId = account,
                                        occurredAt = now,
                                        note = "",
                                    )
                            },
                        requestId = null,
                        overview = state.state,
                        originTab = intent.originTab,
                    )
                } ?: state
            // P7-02.D E-4: a pin toggle is a pure ordering-preference membership flip on the
            // overview; zero accounting effect, and inactive objects stay inactive. The host
            // persists the toggle through the EntryPreferenceStore before dispatching.
            is P503UiEvent.TogglePin ->
                state.copy(
                    pinnedTargets =
                        if (event.target in state.pinnedTargets) {
                            state.pinnedTargets - event.target
                        } else {
                            state.pinnedTargets + event.target
                        },
                )
            // ---- P7-01.D catalog management transitions (pure; no IO) ----
            is P503UiEvent.CatalogCommandCompleted ->
                state.copy(
                    catalogSnapshot = event.snapshot,
                    catalogDialog = CatalogDialog.None,
                    catalogNotice = catalogNoticeFor(event.result),
                )
            is P503UiEvent.CatalogSnapshotRefreshed ->
                state.copy(catalogSnapshot = event.snapshot, catalogNotice = null)
            is P503UiEvent.OpenAccountCreateDialog ->
                state.copy(catalogDialog = CatalogDialog.CreateAccount(kind = AccountKind.ASSET), catalogNotice = null)
            is P503UiEvent.OpenAccountRenameDialog ->
                state.copy(catalogDialog = CatalogDialog.RenameAccount(event.accountId, event.currentName), catalogNotice = null)
            is P503UiEvent.OpenCategoryGroupDialog ->
                state.copy(catalogDialog = CatalogDialog.CreateCategoryGroup(kind = event.kind), catalogNotice = null)
            is P503UiEvent.OpenCategoryAppendChildDialog ->
                state.copy(catalogDialog = CatalogDialog.AppendCategoryChild(event.parentId), catalogNotice = null)
            is P503UiEvent.OpenCategoryRenameDialog ->
                state.copy(catalogDialog = CatalogDialog.RenameCategory(event.categoryId, event.currentName), catalogNotice = null)
            is P503UiEvent.OpenCategoryDeleteDialog ->
                state.copy(catalogDialog = CatalogDialog.ConfirmCategoryDelete(event.categoryId), catalogNotice = null)
            is P503UiEvent.UpdateCatalogFormText ->
                state.copy(catalogDialog = state.catalogDialog.withPrimaryText(event.text))
            is P503UiEvent.UpdateCatalogFormSecondaryText ->
                state.copy(catalogDialog = state.catalogDialog.withSecondaryText(event.text))
            is P503UiEvent.UpdateCatalogFormKind ->
                state.copy(catalogDialog = state.catalogDialog.withAccountKind(event.kind))
            P503UiEvent.DismissCatalogDialog ->
                state.copy(catalogDialog = CatalogDialog.None)
            P503UiEvent.DismissCatalogNotice ->
                state.copy(catalogNotice = null)
            // Active-toggle intents are executed by the host (command + refresh), which then
            // dispatches CatalogCommandCompleted; the pure reducer performs no IO, so the intent
            // leaves the state untouched until that result arrives. "整组启用" (C-8) is its own
            // command, so it joins the absorbed intents rather than reusing SetCategoryActive.
            is P503UiEvent.ManageAccountActive,
            is P503UiEvent.ManageCategoryActive,
            is P503UiEvent.EnableCategoryGroup,
            -> state
            // ---- P7-03.C/D ledger-view read-only transitions (table 6.2a) ----
            is P503UiEvent.SelectTransaction ->
                // Effect (the UI affords it only on HOME rows): enter the read-only detail with
                // the exact overview preserved as the back-target payload (C03).
                P503AppState.TransactionDetail(
                    overview = state,
                    originTab = state.selectedTab,
                    transactionId = event.transactionId,
                    detail = event.result,
                )
            P503UiEvent.CloseTransactionDetail -> state
            // P703SPEC-10: within the frozen SelectMonth domain the cursor moves and the host
            // re-requests (trigger (b)); out-of-domain months and empty domains (empty ledger)
            // are absorbed with zero state change.
            is P503UiEvent.SelectMonth ->
                if (event.month in state.selectableMonths) state.copy(selectedMonth = event.month) else state
            // Trend/month-card linkage: the shared cursor moves from the selected month or 本月
            // (R-Q06-2); without a usable base the shift is absorbed.
            is P503UiEvent.AnalysisMonthShift ->
                analysisMonthShiftTarget(state, event.offset)?.let { state.copy(selectedMonth = it) } ?: state
            is P503UiEvent.MonthlyActivityResult ->
                when (val result = event.result) {
                    is com.unifiedledger.application.MonthlyActivityResult.Success ->
                        state.copy(
                            monthlyActivity = result.activity,
                            selectableMonths = event.selectableMonths,
                            monthlyReloadRequired = false,
                        )
                    // 失败 → READ failure while preserving the last successful overview
                    // (spec 4.3/C04: 上一成功载荷保留 + 显式失败条); the retry stays the existing
                    // RetryRefresh branch (spec 6.3) and recovery is a re-dispatched SelectMonth
                    // (residual boundary (a)). A shortfall in any part of the cycle (month
                    // payload, SelectMonth domain, trend) arrives here as the same typed failure
                    // (F3, R-Q06-4): a disabled selector or a bare 暂无趋势数据 must not stand in
                    // for an explicit failure.
                    com.unifiedledger.application.MonthlyActivityResult.InvalidState,
                    com.unifiedledger.application.MonthlyActivityResult.Unavailable,
                    -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ, monthlyOverview = state)
                }
            // ---- P7-04.C import review transitions (D-146; spec table 6.2a) ----
            // Request-intent events: the effect is the host action (launching the platform picker /
            // re-reading the list); the reducer keeps the state (不切态), so the reducer-observable
            // transition equals the absorbed columns — the coordinator tests pin the host action.
            is P503UiEvent.StartImportFilePick,
            P503UiEvent.RefreshImportReview,
            -> state
            // Host-channel pick events: absorbed in every state (the pick itself never switches
            // state; the pipeline result arrives as ImportFileIntakeResult).
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            -> state
            is P503UiEvent.ImportFileIntakeResult -> reduceImportFileIntakeResult(state, event)
            is P503UiEvent.ImportReviewResult -> reduceImportReviewResult(state, event)
            is P503UiEvent.SelectImportCandidate ->
                // Effect (the UI affords it only on IMPORT rows): enter the detail with the exact
                // overview preserved as the back-target payload and the same candidate's in-session
                // decision draft restored (spec section 6.2 表单字段保留).
                P503AppState.ImportCandidateDetail(
                    overview = state,
                    candidateId = event.candidateId,
                    detail = event.detail,
                    duplicates = event.duplicates,
                    form = state.importReview?.decisionDrafts?.get(event.candidateId) ?: ImportDecisionDraft(),
                )
            // No detail is open on the overview; the form belongs to the detail state.
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            // The review submit lives only in the detail (table 6.2a: absorbed on the overview).
            is P503UiEvent.SubmitImportDuplicateReview,
            -> state
            is P503UiEvent.ToggleImportCandidateSelection -> toggleImportSelectionOnOverview(state, event.candidateId)
            is P503UiEvent.ImportDuplicateReviewResult -> reduceImportDuplicateReviewResultOnOverview(state, event)
            is P503UiEvent.StartImportDuplicateGroupDisposition ->
                state.importReview?.let { view ->
                    state.copy(
                        importReview =
                            view.copy(
                                groupDisposition =
                                    ImportDuplicateGroupDispositionPage(
                                        inputRef = event.inputRef,
                                        items = event.items.map { ImportDuplicateGroupItemState(it) },
                                    ),
                            ),
                    )
                } ?: state
            is P503UiEvent.ImportDuplicateGroupDispositionResult -> reduceImportGroupDispositionResult(state, event)
            P503UiEvent.CloseImportDuplicateGroupDisposition ->
                state.importReview?.let { view ->
                    state.copy(importReview = view.copy(groupDisposition = null))
                } ?: state
            // ---- P7-04.D batch confirmation transitions (table 6.2a) ----
            // The confirm page opens only for a non-empty selection (空集 absorbed； the entry
            // affordance also restricts, but the reducer holds the gate).
            P503UiEvent.RequestImportBatchConfirm ->
                state.importReview
                    ?.takeIf { it.selectedCandidateIds.isNotEmpty() }
                    ?.let { P503AppState.ImportBatchConfirm(overview = state) }
                    ?: state
            // The authorize action, per-item results, the resume/abandon exits and the check
            // events all live in their own states; on the overview the check intent keeps the
            // state (不切态， the host action owns the replay; the coordinator test pins it) and a
            // late check result updates the retained summary below.
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            -> state
            is P503UiEvent.ImportUnknownItemCheckResult -> reduceImportUnknownCheckResultOnOverview(state, event)
            // P7-02: the entry-field intents are only meaningful inside the editor; on the
            // overview they are absorbed (§6.2a).
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            -> state
            // Explicit Back during management closes the open dialog first; with no dialog it
            // leaves the ACCOUNTS tab for HOME (the overview root stays the back floor).
            P503UiEvent.Back ->
                when {
                    state.catalogDialog != CatalogDialog.None -> state.copy(catalogDialog = CatalogDialog.None)
                    state.selectedTab == P503Tab.ACCOUNTS -> state.copy(selectedTab = P503Tab.HOME)
                    else -> unhandled(state, event)
                }
            else -> unhandled(state, event)
        }

    /**
     * P7-03.C read-only detail state (spec section 6.1/6.2). Only its designed events react:
     * CloseTransactionDetail/Back return to the exact preserved overview (tab, month and
     * monthly payload kept, C03), MonthlyActivityResult applies the 详情态同语义 payload update
     * on the stored overview, the other new read-only events are absorbed, and every
     * pre-existing event stays unlisted (ISE, G-B). The page has zero edit entries.
     */
    private fun reduceTransactionDetail(
        state: P503AppState.TransactionDetail,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            P503UiEvent.CloseTransactionDetail -> state.overview
            // System back = CloseTransactionDetail semantics (spec section 6.2 back bullet).
            P503UiEvent.Back -> state.overview
            is P503UiEvent.MonthlyActivityResult ->
                when (val result = event.result) {
                    is com.unifiedledger.application.MonthlyActivityResult.Success ->
                        state.copy(
                            overview =
                                state.overview.copy(
                                    monthlyActivity = result.activity,
                                    selectableMonths = event.selectableMonths,
                                    monthlyReloadRequired = false,
                                ),
                        )
                    com.unifiedledger.application.MonthlyActivityResult.InvalidState,
                    com.unifiedledger.application.MonthlyActivityResult.Unavailable,
                    -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ, monthlyOverview = state.overview)
                }
            is P503UiEvent.SelectTransaction,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            // P7-04.C: the import review events are absorbed inside the read-only detail (table
            // 6.2a; the detail has no import affordances).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> state
            else -> unhandled(state, event)
        }

    /**
     * P7-03.C (F9): the shifted month cursor, or `null` when no usable base month exists or the
     * shifted month would leave the frozen SelectMonth domain `[first transaction statistics
     * month, 本月]` (P703SPEC-10). The analysis region therefore moves the shared cursor under
     * exactly the same admission rule as `SelectMonth` and can never request a month the selector
     * will never offer; an out-of-domain shift is absorbed with zero state change.
     */
    private fun analysisMonthShiftTarget(
        state: P503AppState.OverviewEmpty,
        offset: Int,
    ): kotlinx.datetime.YearMonth? {
        if (offset == 0) return state.selectedMonth
        val base =
            state.selectedMonth ?: ledgerClock?.let { clock ->
                try {
                    MonthlyBuckets.currentMonth(clock)
                } catch (failure: Exception) {
                    // An unusable clock cannot resolve 本月 (R-Q06-4): absorb instead of guessing.
                    null
                }
            } ?: return null
        var shifted = base
        repeat(kotlin.math.abs(offset)) {
            shifted =
                if (offset > 0) {
                    shifted.plus(1, DateTimeUnit.MONTH)
                } else {
                    shifted.minus(1, DateTimeUnit.MONTH)
                }
        }
        return if (shifted in state.selectableMonths) shifted else null
    }

    /**
     * P7-04.C: applies one pick pipeline result on the overview (table 6.2a). The session summary
     * always replaces the previous one; a successful intake replaces the candidate rows while a
     * typed failure keeps the previous list and surfaces the explicit failure banner (失败条 +
     * 保留上一清单); a successful intake whose list re-read failed keeps the previous rows and
     * surfaces the typed read-failure banner (F1). ANY intake result landing also closes an open
     * group disposition page (P704C-SPEC-08: the page's session handle is superseded by the new
     * pick even on a failed pipeline — the old handle's page must not coexist with the new
     * session summary).
     */
    private fun reduceImportFileIntakeResult(
        state: P503AppState.OverviewEmpty,
        event: P503UiEvent.ImportFileIntakeResult,
    ): P503AppState {
        val view = state.importReview ?: ImportReviewView()
        val outcome = event.session.outcome
        val success =
            outcome is ImportIntakePipelineOutcome.Intaken &&
                outcome.outcome !is com.unifiedledger.application.ImportFileIntakeOutcome.Rejected
        val nextRows =
            if (success && event.rows is com.unifiedledger.application.ImportReviewRowsResult.Rows) {
                event.rows.rows
            } else {
                view.rows
            }
        val notice =
            when {
                !success -> ImportReviewNotice.IntakeFailed(event.session.outcome)
                event.rows is com.unifiedledger.application.ImportReviewRowsResult.Unavailable -> ImportReviewNotice.ReviewReadFailed
                else -> null
            }
        return state.copy(
            importReview =
                view.copy(
                    rows = nextRows,
                    lastIntakeSession = event.session,
                    notice = notice,
                    groupDisposition = null,
                ),
        )
    }

    /**
     * P7-04.C: the review-list read result (table 6.2a). Success replaces the rows and clears the
     * banner; a typed failure keeps the previous successful list and surfaces the explicit
     * failure banner (读失败不篡改， F1). A first-ever failure still materializes the projection so
     * the banner has a place to live — the screen never renders a bare empty list next to a
     * failure notice.
     */
    private fun reduceImportReviewResult(
        state: P503AppState.OverviewEmpty,
        event: P503UiEvent.ImportReviewResult,
    ): P503AppState {
        val view = state.importReview ?: ImportReviewView()
        return when (val result = event.result) {
            is com.unifiedledger.application.ImportReviewRowsResult.Rows ->
                state.copy(importReview = view.copy(rows = result.rows, notice = null))
            com.unifiedledger.application.ImportReviewRowsResult.Unavailable ->
                state.copy(importReview = view.copy(notice = ImportReviewNotice.ReviewReadFailed))
        }
    }

    /**
     * P7-04.C: one selection toggle under the section 3.3.1 gate (先审后勾， R-Q10-1/R-Q10-3). A row
     * whose classification is not selectable absorbs the toggle, as does an unknown candidate id
     * or a missing projection; the returned overview is the SAME instance on every absorbed path
     * so the detail state can detect "no change" by reference.
     */
    private fun toggleImportSelectionOnOverview(
        state: P503AppState.OverviewEmpty,
        candidateId: com.unifiedledger.application.ImportCandidateId,
    ): P503AppState.OverviewEmpty {
        val view = state.importReview ?: return state
        val row = view.rows.firstOrNull { it.candidateId == candidateId } ?: return state
        if (!classifyImportCandidate(row).selectable) return state
        val next =
            if (candidateId in view.selectedCandidateIds) {
                view.selectedCandidateIds - candidateId
            } else {
                view.selectedCandidateIds + candidateId
            }
        return state.copy(importReview = view.copy(selectedCandidateIds = next))
    }

    /**
     * P7-04.C: the core duplicate-review result on the overview (the review was submitted inside
     * the detail and the detail closed before the result arrived, or the group loop reported
     * typed). Success refreshes the list (拒绝类型化呈现 otherwise); a list re-read failure keeps
     * the previous rows plus the typed read-failure banner (F1).
     */
    private fun reduceImportDuplicateReviewResultOnOverview(
        state: P503AppState.OverviewEmpty,
        event: P503UiEvent.ImportDuplicateReviewResult,
    ): P503AppState.OverviewEmpty {
        val view = state.importReview ?: return state
        // P704D-SPEC-02: the UI-owned infrastructure failure — the core never returned a
        // verdict, so only the typed banner lands; the previous rows stay (F1 保留旧载荷).
        if (event.uiFailureCode != null) {
            return state.copy(importReview = view.copy(notice = ImportReviewNotice.ReviewSubmitFailed(event.uiFailureCode)))
        }
        // Host contract: a core-verdict event always carries both payloads; anything else
        // changes nothing (defensive absorb).
        val review = event.review ?: return state
        val refresh = event.refresh ?: return state
        val nextRows =
            if (refresh.rows is com.unifiedledger.application.ImportReviewRowsResult.Rows) {
                refresh.rows.rows
            } else {
                view.rows
            }
        val notice =
            when {
                review is com.unifiedledger.application.ImportDuplicateReviewResult.Rejected ->
                    ImportReviewNotice.ReviewRejected(review.diagnostic.code)
                refresh.rows is com.unifiedledger.application.ImportReviewRowsResult.Unavailable ->
                    ImportReviewNotice.ReviewReadFailed
                else -> null
            }
        return state.copy(importReview = view.copy(rows = nextRows, notice = notice))
    }

    /**
     * P7-04.C: the group disposition loop's completion on the open 整组确认页 (可见部分成功).
     * Re-run idempotency (P704C-QUAL-01/SPEC-03): an item whose verdict is already
     * [ImportDuplicateGroupItemResult.Reviewed] is never overwritten by a later result — the host
     * loop never re-submits a Reviewed item, and this merge keeps the guard at the state layer too
     * (a spurious late outcome for an already-succeeded item cannot relabel it 失败).
     */
    private fun reduceImportGroupDispositionResult(
        state: P503AppState.OverviewEmpty,
        event: P503UiEvent.ImportDuplicateGroupDispositionResult,
    ): P503AppState {
        val view = state.importReview ?: return state
        val page = view.groupDisposition ?: return state
        val updatedItems =
            page.items.map { itemState ->
                val outcome = event.outcomes.firstOrNull { it.duplicateCandidateId == itemState.item.duplicateCandidateId }
                when {
                    outcome == null -> itemState
                    // 幂等：已成功判定不被后续结果覆写（已成功项不再被重提交）。
                    itemState.outcome is ImportDuplicateGroupItemResult.Reviewed -> itemState
                    else -> itemState.copy(outcome = outcome.result)
                }
            }
        val nextRows =
            if (event.rows is com.unifiedledger.application.ImportReviewRowsResult.Rows) {
                event.rows.rows
            } else {
                view.rows
            }
        val notice =
            if (event.rows is com.unifiedledger.application.ImportReviewRowsResult.Unavailable) {
                ImportReviewNotice.ReviewReadFailed
            } else {
                null
            }
        return state.copy(
            importReview =
                view.copy(
                    rows = nextRows,
                    notice = notice,
                    groupDisposition = page.copy(items = updatedItems),
                ),
        )
    }

    /**
     * P7-04.C: the import candidate detail state (spec sections 6.1/6.2). Only its designed
     * events react: Close/Back return to the exact preserved IMPORT overview with the decision
     * draft written back (SPEC:281/283), the form update is a pure draft write, the selection
     * toggle applies the same section 3.3.1 gate on the carried overview, the review submit sets
     * the in-flight marker (期间禁重复提交) when a reviewable target exists, and the review result
     * refreshes the 详情内 duplicate state. Every pre-existing event is absorbed (新态不发起编辑/
     * 管理/月度流) except `Exit`, which stays unlisted (ISE, spec section 6.3 / P7-02 §6.2b — the
     * unlisted-combination discipline keeps a future event from being silently swallowed).
     */
    private fun reduceImportCandidateDetail(
        state: P503AppState.ImportCandidateDetail,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            P503UiEvent.CloseImportCandidateDetail -> closeImportCandidateDetail(state)
            // SPEC:281: system back = close semantics (返回 OverviewEmpty(IMPORT) 保留清单/勾选集).
            P503UiEvent.Back -> closeImportCandidateDetail(state)
            is P503UiEvent.UpdateImportDecisionField ->
                state.copy(form = state.form.withImportDecisionUpdate(event.update))
            is P503UiEvent.ToggleImportCandidateSelection -> {
                val row =
                    (state.detail as? com.unifiedledger.application.ImportCandidateDetailResult.Found)
                        ?.detail
                        ?.row
                val carried =
                    if (row != null && row.candidateId == event.candidateId && classifyImportCandidate(row).selectable) {
                        toggleImportSelectionOnOverview(state.overview, event.candidateId)
                    } else {
                        // 详情内勾选同门：the classification gate decides; unknown/unselectable absorbs.
                        state.overview
                    }
                if (carried === state.overview) state else state.copy(overview = carried)
            }
            is P503UiEvent.SubmitImportDuplicateReview ->
                when {
                    // 期间禁重复提交：a duplicate submit while one review is in flight is absorbed.
                    state.reviewPending -> state
                    importDuplicateReviewTarget(state.duplicates) != null -> state.copy(reviewPending = true)
                    // No reviewable target (nothing DEFERRED/EXACT_BUSINESS_TUPLE): absorbed.
                    else -> state
                }
            is P503UiEvent.ImportDuplicateReviewResult -> {
                val refreshedOverview = reduceImportDuplicateReviewResultOnOverview(state.overview, event)
                // P704D-SPEC-02: the UI-owned infrastructure failure — the core never returned a
                // verdict. Clear the in-flight marker (期间禁重复提交 is over; the action is
                // retryable), surface the typed banner and keep the previous payloads (F1).
                if (event.uiFailureCode != null) {
                    return state.copy(
                        overview = refreshedOverview,
                        reviewPending = false,
                        notice = ImportReviewNotice.ReviewSubmitFailed(event.uiFailureCode),
                    )
                }
                // Host contract: a core-verdict event always carries both payloads; anything
                // else changes nothing (defensive absorb).
                val review = event.review ?: return state
                val refresh = event.refresh ?: return state
                val detail = refresh.detail
                val duplicates = refresh.duplicates
                // Success clears any previous rejection banner (成功刷新详情重复状态); a typed
                // rejection surfaces its code (拒绝类型化呈现); a re-read failure keeps the
                // previous payloads and surfaces the typed read-failure banner (F1).
                val notice =
                    when {
                        review is com.unifiedledger.application.ImportDuplicateReviewResult.Rejected ->
                            ImportReviewNotice.ReviewRejected(review.diagnostic.code)
                        refresh.rows is com.unifiedledger.application.ImportReviewRowsResult.Unavailable ->
                            ImportReviewNotice.ReviewReadFailed
                        else -> null
                    }
                state.copy(
                    overview = refreshedOverview,
                    detail = detail ?: state.detail,
                    duplicates = duplicates ?: state.duplicates,
                    reviewPending = false,
                    notice = notice,
                )
            }
            // P7-04.D (table 6.2a): 携详情决策进入确认页 — the detail's decision draft is written
            // back into the carried overview (the same close semantics, SPEC:283) and the confirm
            // page opens over that overview. P704D-SPEC-03: the empty-selection gate aligns this
            // column with the OverviewEmpty column (空集 absorbed); the UI affordance restricts
            // to a non-empty selection too (the SelectTransaction precedent).
            P503UiEvent.RequestImportBatchConfirm ->
                state.overview.importReview
                    ?.takeIf { it.selectedCandidateIds.isNotEmpty() }
                    ?.let { P503AppState.ImportBatchConfirm(overview = closeImportCandidateDetail(state)) }
                    ?: state
            // The host-channel pick events, the pick pipeline result and the list refresh events
            // are absorbed here (table 6.2a; 清单经详情关闭后刷新).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the remaining batch events are absorbed inside the detail (table 6.2a; the
            // batch states own their effects).
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            // A second SelectImportCandidate while a detail is open stays on the open detail
            // (the UI affords no nested navigation).
            is P503UiEvent.SelectImportCandidate,
            // P7-02: the entry-foundation events are absorbed (the new state never starts an
            // editor flow; §6.2a).
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P7-01.D: the catalog management events are absorbed (新增入口仅存在于 overview).
            is P503UiEvent.OpenAccountCreateDialog,
            is P503UiEvent.OpenAccountRenameDialog,
            is P503UiEvent.OpenCategoryGroupDialog,
            is P503UiEvent.OpenCategoryAppendChildDialog,
            is P503UiEvent.OpenCategoryRenameDialog,
            is P503UiEvent.OpenCategoryDeleteDialog,
            is P503UiEvent.ManageAccountActive,
            is P503UiEvent.ManageCategoryActive,
            is P503UiEvent.EnableCategoryGroup,
            is P503UiEvent.UpdateCatalogFormText,
            is P503UiEvent.UpdateCatalogFormSecondaryText,
            is P503UiEvent.UpdateCatalogFormKind,
            P503UiEvent.DismissCatalogDialog,
            P503UiEvent.DismissCatalogNotice,
            is P503UiEvent.CatalogCommandCompleted,
            is P503UiEvent.CatalogSnapshotRefreshed,
            // P7-03.C/D: the read-only ledger-view events are absorbed (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // The remaining pre-existing events are absorbed too (既有事件在新态全部 absorbed，
            // spec section 6.2): tab switching, the edit/submit flows and the async results all
            // leave the import detail untouched.
            is P503UiEvent.SelectTab,
            P503UiEvent.StartNewExpense,
            is P503UiEvent.UpdateAmount,
            is P503UiEvent.UpdatePaymentAccount,
            is P503UiEvent.UpdateCategory,
            is P503UiEvent.UpdateOccurredAt,
            is P503UiEvent.Continue,
            P503UiEvent.Cancel,
            P503UiEvent.Confirm,
            P503UiEvent.RetrySubmission,
            P503UiEvent.RetryRefresh,
            P503UiEvent.RetryCommitStatusCheck,
            P503UiEvent.AbandonConflict,
            is P503UiEvent.SubmissionResult,
            is P503UiEvent.CommitStatusResolved,
            is P503UiEvent.InitialLoadResult,
            P503UiEvent.InitialLoadFailed,
            is P503UiEvent.RefreshResult,
            P503UiEvent.RefreshFailed,
            -> state
            else -> unhandled(state, event)
        }

    /**
     * P7-04.C: close/Back semantics (SPEC:281/283). The exact preserved overview returns with its
     * list payload and selection set, and the current decision draft is written back into the
     * carried projection so re-entering the same candidate keeps it within the session (进程重启
     * 丢失可接受 — the drafts are session memory, never persisted).
     */
    private fun closeImportCandidateDetail(state: P503AppState.ImportCandidateDetail): P503AppState.OverviewEmpty {
        val view = state.overview.importReview ?: return state.overview
        return state.overview.copy(
            importReview = view.copy(decisionDrafts = view.decisionDrafts + (state.candidateId to state.form)),
        )
    }

    // ---- P7-04.D batch confirmation transitions (D-146; spec sections 3.2.3/3.3.2/6.2 table 6.2a) ----

    /**
     * P7-04.D: the 授权快照确认页. Cancel/Back return to the exact preserved overview (保留勾选集
     * 与清单)； the authorize action builds the authorization snapshot — the deterministic selection
     * ordering (rows order, then selected-but-absent ids by value) paired with the host-minted
     * per-item requestIds — and enters the dispatch state with the ONE host-sampled clock instant
     * (Q09.4; the reducer itself performs no IO and no randomness). A selected id without a
     * minted requestId absorbs defensively (the wired host always mints one per selected
     * candidate). Every pre-existing event is absorbed (新态不发起编辑/管理/月度流) except `Exit`,
     * which stays unlisted (ISE, spec section 6.3 / P7-02 §6.2b).
     */
    private fun reduceImportBatchConfirm(
        state: P503AppState.ImportBatchConfirm,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            P503UiEvent.CancelImportBatchConfirm -> state.overview
            // SPEC:281: system back = cancel semantics (→ 原 OverviewEmpty， 保留勾选集与清单).
            P503UiEvent.Back -> state.overview
            is P503UiEvent.AuthorizeImportBatch ->
                state.overview.importReview?.let { view ->
                    val candidateIds = importBatchSnapshotCandidateIds(view)
                    if (candidateIds.any { it !in event.requestIds }) {
                        // Defensive: an unminted selected id never enters a half-snapshot state.
                        state
                    } else {
                        P503AppState.ImportBatchSubmitting(
                            overview = state.overview,
                            confirmedAt = event.confirmedAt,
                            items =
                                candidateIds.map { candidateId ->
                                    ImportBatchSubmittingItem(
                                        ImportBatchItem(candidateId, event.requestIds.getValue(candidateId)),
                                    )
                                },
                        )
                    }
                } ?: state
            is P503UiEvent.RequestImportBatchConfirm,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            // The host-channel pick events and the P7-04.C review surface are absorbed (the
            // confirm page owns no list affordances).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-02: the entry-foundation events are absorbed (§6.2a).
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P7-01.D: the catalog management events are absorbed (新增入口仅存在于 overview).
            is P503UiEvent.OpenAccountCreateDialog,
            is P503UiEvent.OpenAccountRenameDialog,
            is P503UiEvent.OpenCategoryGroupDialog,
            is P503UiEvent.OpenCategoryAppendChildDialog,
            is P503UiEvent.OpenCategoryRenameDialog,
            is P503UiEvent.OpenCategoryDeleteDialog,
            is P503UiEvent.ManageAccountActive,
            is P503UiEvent.ManageCategoryActive,
            is P503UiEvent.EnableCategoryGroup,
            is P503UiEvent.UpdateCatalogFormText,
            is P503UiEvent.UpdateCatalogFormSecondaryText,
            is P503UiEvent.UpdateCatalogFormKind,
            P503UiEvent.DismissCatalogDialog,
            P503UiEvent.DismissCatalogNotice,
            is P503UiEvent.CatalogCommandCompleted,
            is P503UiEvent.CatalogSnapshotRefreshed,
            // P7-03.C/D: the read-only ledger-view events are absorbed (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // The remaining pre-existing events are absorbed too (既有事件在新态全部 absorbed，
            // spec section 6.2).
            is P503UiEvent.SelectTab,
            P503UiEvent.StartNewExpense,
            is P503UiEvent.UpdateAmount,
            is P503UiEvent.UpdatePaymentAccount,
            is P503UiEvent.UpdateCategory,
            is P503UiEvent.UpdateOccurredAt,
            is P503UiEvent.Continue,
            P503UiEvent.Cancel,
            P503UiEvent.Confirm,
            P503UiEvent.RetrySubmission,
            P503UiEvent.RetryRefresh,
            P503UiEvent.RetryCommitStatusCheck,
            P503UiEvent.AbandonConflict,
            is P503UiEvent.SubmissionResult,
            is P503UiEvent.CommitStatusResolved,
            is P503UiEvent.InitialLoadResult,
            P503UiEvent.InitialLoadFailed,
            is P503UiEvent.RefreshResult,
            P503UiEvent.RefreshFailed,
            -> state
            else -> unhandled(state, event)
        }

    /**
     * P7-04.D: the per-item dispatch state (spec section 3.3.2). Per-item results record their
     * outcomes (a resolved item is never overwritten — 已成功不重复标注); an Unknown outcome pauses
     * the loop and keeps the item's check entry; the state leaves to OverviewEmpty(IMPORT) with
     * the retained result summary once EVERY item is terminal (全部项终态——an Unknown item is not
     * terminal: 会话内未知 keeps the batch open until a check resolves it or an explicit
     * Resume/Abandon exits). Resume clears the pause for the host's continuation run — and with
     * no undispatched item left it leaves instead, carrying the still-Unknown items into the
     * summary (their check entries live in the 结果摘要， table 6.2a). Abandon dissolves the
     * authorization snapshot straight back to the overview — the completed items' results stay
     * and the undispatched items are ordinary pending list rows again (义务③： 无隐藏中间 UI 态；
     * 未派发项持久状态保持 pending_confirmation). System back is not listed: the dispatch guards
     * intercept and swallow it (沿既有 Submitting 语义， G-B keeps the unlisted combination an ISE).
     */
    private fun reduceImportBatchSubmitting(
        state: P503AppState.ImportBatchSubmitting,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.ImportItemResult -> applyImportItemOutcome(state, event)
            is P503UiEvent.ImportUnknownItemCheckResult -> applyImportUnknownCheckResult(state, event)
            P503UiEvent.ResumeImportBatchDispatch ->
                if (importBatchHasUndispatchedItems(state)) {
                    // 同授权快照内继续派发未派发项 (复用同次 LedgerClock 取样与既有 requestId).
                    state.copy(dispatchPaused = false)
                } else {
                    // Nothing left to dispatch: the batch leaves with the per-item summary; any
                    // still-Unknown item keeps its check entry inside the summary.
                    leaveImportBatchSubmitting(state, includeUnknowns = true)
                }
            P503UiEvent.AbandonImportBatch ->
                // 义务③: the snapshot dissolves in ONE transition — the completed items keep
                // their results, the undispatched items are ordinary pending rows again.
                leaveImportBatchSubmitting(state, includeUnknowns = true)
            is P503UiEvent.AuthorizeImportBatch,
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            is P503UiEvent.OpenAccountCreateDialog,
            is P503UiEvent.OpenAccountRenameDialog,
            is P503UiEvent.OpenCategoryGroupDialog,
            is P503UiEvent.OpenCategoryAppendChildDialog,
            is P503UiEvent.OpenCategoryRenameDialog,
            is P503UiEvent.OpenCategoryDeleteDialog,
            is P503UiEvent.ManageAccountActive,
            is P503UiEvent.ManageCategoryActive,
            is P503UiEvent.EnableCategoryGroup,
            is P503UiEvent.UpdateCatalogFormText,
            is P503UiEvent.UpdateCatalogFormSecondaryText,
            is P503UiEvent.UpdateCatalogFormKind,
            P503UiEvent.DismissCatalogDialog,
            P503UiEvent.DismissCatalogNotice,
            is P503UiEvent.CatalogCommandCompleted,
            is P503UiEvent.CatalogSnapshotRefreshed,
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            is P503UiEvent.SelectTab,
            P503UiEvent.StartNewExpense,
            is P503UiEvent.UpdateAmount,
            is P503UiEvent.UpdatePaymentAccount,
            is P503UiEvent.UpdateCategory,
            is P503UiEvent.UpdateOccurredAt,
            is P503UiEvent.Continue,
            P503UiEvent.Cancel,
            P503UiEvent.Confirm,
            P503UiEvent.RetrySubmission,
            P503UiEvent.RetryRefresh,
            P503UiEvent.RetryCommitStatusCheck,
            P503UiEvent.AbandonConflict,
            is P503UiEvent.SubmissionResult,
            is P503UiEvent.CommitStatusResolved,
            is P503UiEvent.InitialLoadResult,
            P503UiEvent.InitialLoadFailed,
            is P503UiEvent.RefreshResult,
            P503UiEvent.RefreshFailed,
            -> state
            else -> unhandled(state, event)
        }

    /**
     * P7-04.D: one per-item dispatch result lands on the dispatch state (table 6.2a). The item's
     * outcome records once (a resolved item is never overwritten — the host loop never
     * re-dispatches a resolved item and this guard keeps a spurious late duplicate from
     * relabeling it); an Unknown sets the pause flag; the state leaves once every item is
     * terminal.
     */
    private fun applyImportItemOutcome(
        state: P503AppState.ImportBatchSubmitting,
        event: P503UiEvent.ImportItemResult,
    ): P503AppState {
        if (state.items.none { it.item.candidateId == event.item.candidateId && it.outcome == null }) return state
        val items =
            state.items.map { itemState ->
                if (itemState.item.candidateId == event.item.candidateId && itemState.outcome == null) {
                    itemState.copy(outcome = event.outcome)
                } else {
                    itemState
                }
            }
        val paused = state.dispatchPaused || event.outcome is ImportBatchItemOutcome.Unknown
        val updated = state.copy(items = items, dispatchPaused = paused)
        return if (items.all { isImportBatchOutcomeTerminal(it.outcome) }) leaveImportBatchSubmitting(updated, includeUnknowns = false) else updated
    }

    /**
     * P7-04.D: one Unknown item's replay verdict lands on the dispatch state. Confirmed/conflict
     * outcomes replace the Unknown (the item becomes terminal); StillUnknown keeps the Unknown
     * (仍未知 — the check entry stays). 仅全部项终态后可离开 (table 6.2a): the leave test runs after
     * the update.
     */
    private fun applyImportUnknownCheckResult(
        state: P503AppState.ImportBatchSubmitting,
        event: P503UiEvent.ImportUnknownItemCheckResult,
    ): P503AppState {
        val items =
            state.items.map { itemState ->
                if (itemState.item.candidateId == event.item.candidateId && itemState.outcome is ImportBatchItemOutcome.Unknown) {
                    when (val outcome = event.outcome) {
                        is ImportUnknownCheckOutcome.Confirmed -> itemState.copy(outcome = ImportBatchItemOutcome.Confirmed(outcome.receipt))
                        is ImportUnknownCheckOutcome.Conflict -> itemState.copy(outcome = ImportBatchItemOutcome.CheckConflict(outcome.code))
                        ImportUnknownCheckOutcome.StillUnknown -> itemState
                    }
                } else {
                    itemState
                }
            }
        // No Unknown item matched: nothing changes (StillUnknown on the one item keeps it too).
        if (items == state.items) return state
        val updated = state.copy(items = items)
        return if (items.all { isImportBatchOutcomeTerminal(it.outcome) }) leaveImportBatchSubmitting(updated, includeUnknowns = false) else updated
    }

    /**
     * P7-04.D: the leave transition to OverviewEmpty(IMPORT) with the retained per-item result
     * summary (保留结果摘要). [includeUnknowns] follows the leaving path's semantics: the terminal
     * leave (last item terminal / check resolved) always has no Unknowns left; the explicit
     * Resume-with-nothing-remaining and Abandon leaves carry the still-Unknown items into the
     * summary so their check entries live in the 结果摘要 (table 6.2a). On Abandon the undispatched
     * items (null outcome) never enter the summary — they are ordinary pending list rows again
     * (义务③)； the carried overview keeps its selection set and drafts unchanged, so nothing
     * hidden survives the dissolution.
     */
    private fun leaveImportBatchSubmitting(
        state: P503AppState.ImportBatchSubmitting,
        includeUnknowns: Boolean,
    ): P503AppState {
        val completed =
            state.items.filter { itemState ->
                when {
                    itemState.outcome == null -> false
                    itemState.outcome is ImportBatchItemOutcome.Unknown -> includeUnknowns
                    else -> true
                }
            }
        val summary =
            ImportBatchResultSummary(
                confirmedAt = state.confirmedAt,
                items = completed.map { ImportBatchResultItem(it.item, it.outcome!!) },
            )
        val view = state.overview.importReview ?: ImportReviewView()
        return state.overview.copy(importReview = view.copy(batchResult = summary))
    }

    /**
     * P7-04.D: one Unknown item's replay verdict lands on the overview (the batch already left
     * with the summary retained; the check entry lives inside the 结果摘要). Confirmed/conflict
     * outcomes update the summary item in place; StillUnknown keeps it Unknown.
     */
    private fun reduceImportUnknownCheckResultOnOverview(
        state: P503AppState.OverviewEmpty,
        event: P503UiEvent.ImportUnknownItemCheckResult,
    ): P503AppState {
        val view = state.importReview ?: return state
        val summary = view.batchResult ?: return state
        val items =
            summary.items.map { entry ->
                if (entry.item.candidateId == event.item.candidateId && entry.outcome is ImportBatchItemOutcome.Unknown) {
                    when (val outcome = event.outcome) {
                        is ImportUnknownCheckOutcome.Confirmed -> entry.copy(outcome = ImportBatchItemOutcome.Confirmed(outcome.receipt))
                        is ImportUnknownCheckOutcome.Conflict -> entry.copy(outcome = ImportBatchItemOutcome.CheckConflict(outcome.code))
                        ImportUnknownCheckOutcome.StillUnknown -> entry
                    }
                } else {
                    entry
                }
            }
        // No Unknown summary item matched (or the verdict was StillUnknown): the state instance
        // stays untouched (absorbed).
        if (items == summary.items) return state
        return state.copy(importReview = view.copy(batchResult = summary.copy(items = items)))
    }

    private fun reduceEditing(
        state: P503AppState.Editing,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.UpdateAmount ->
                state.copy(draft = state.draft.withAmountText(event.text))
            is P503UiEvent.UpdatePaymentAccount ->
                state.copy(draft = state.draft.withPrimaryAccount(event.accountId))
            is P503UiEvent.UpdateCategory ->
                state.copy(draft = state.draft.withCategory(event.categoryId))
            is P503UiEvent.UpdateOccurredAt ->
                state.copy(draft = state.draft.withOccurredAt(event.instant))
            // P7-02 S-2/E-1: the frozen retention matrix is the single implementation; an
            // unsupported target type leaves the state untouched.
            is P503UiEvent.SelectEntryType ->
                EntryFieldRetention.switchType(state.draft, event.type)?.let { state.copy(draft = it) } ?: state
            // P7-02 S-4 note write.
            is P503UiEvent.UpdateNote ->
                state.copy(draft = state.draft.withNote(event.text))
            is P503UiEvent.UpdateReceivingAccount ->
                state.copy(draft = state.draft.withPrimaryAccount(event.accountId))
            is P503UiEvent.UpdateIncomeCategory ->
                state.copy(draft = state.draft.withCategory(event.categoryId))
            is P503UiEvent.UpdateTransferSourceAccount ->
                state.copy(draft = state.draft.withTransferSourceAccount(event.accountId))
            is P503UiEvent.UpdateTransferDestinationAccount ->
                state.copy(draft = state.draft.withTransferDestinationAccount(event.accountId))
            is P503UiEvent.UpdateTransferDestinationCredit ->
                state.copy(draft = state.draft.withTransferDestinationCredit(event.text))
            is P503UiEvent.UpdateTransferFee ->
                state.copy(draft = state.draft.withTransferFee(event.text))
            is P503UiEvent.UpdateTransferFeeCategory ->
                state.copy(draft = state.draft.withTransferFeeCategory(event.categoryId))
            is P503UiEvent.UpdateLendCounterparty ->
                state.copy(draft = state.draft.withLendCounterparty(event.counterpartyId))
            is P503UiEvent.UpdateLendFundingAccount ->
                state.copy(draft = state.draft.withLendFundingAccount(event.accountId))
            is P503UiEvent.UpdateLendAmount ->
                state.copy(draft = state.draft.withLendAmount(event.text))
            is P503UiEvent.UpdateCollectCounterparty ->
                state.copy(draft = state.draft.withCollectCounterparty(event.counterpartyId))
            is P503UiEvent.UpdateCollectDestinationAccount ->
                state.copy(draft = state.draft.withCollectDestinationAccount(event.accountId))
            is P503UiEvent.UpdateCollectTotal ->
                state.copy(draft = state.draft.withCollectTotal(event.text))
            is P503UiEvent.UpdateCollectPrincipal ->
                state.copy(draft = state.draft.withCollectPrincipal(event.text))
            is P503UiEvent.UpdateCollectInterest ->
                state.copy(draft = state.draft.withCollectInterest(event.text))
            is P503UiEvent.UpdateCollectInterestCategory ->
                state.copy(draft = state.draft.withCollectInterestCategory(event.categoryId))
            // P702SPEC-03: the editor-local counterparty create/rename form.
            P503UiEvent.OpenCounterpartyCreateDialog ->
                state.copy(counterpartyDialog = CounterpartyDialog.Create())
            is P503UiEvent.OpenCounterpartyRenameDialog ->
                state.copy(counterpartyDialog = CounterpartyDialog.Rename(event.counterpartyId, event.currentName, ""))
            is P503UiEvent.UpdateCounterpartyFormText ->
                state.copy(
                    counterpartyDialog =
                        when (val dialog = state.counterpartyDialog) {
                            is CounterpartyDialog.Create -> dialog.copy(nameText = event.text)
                            is CounterpartyDialog.Rename -> dialog.copy(nameText = event.text)
                            null -> null
                        },
                )
            P503UiEvent.DismissCounterpartyDialog ->
                state.copy(counterpartyDialog = null)
            // P7-02.D E-3: evaluating an expression writes the preview only — the reducer never
            // silently rewrites the amount text; the calculator shows the exact result (or the
            // typed rejection) first and the user confirms explicitly.
            is P503UiEvent.EvaluateEntryExpression ->
                when (val result = expressionEvaluator.evaluate(event.expression, currency)) {
                    is EntryExpressionEvaluator.Result.Valid ->
                        state.copy(
                            expressionPreview =
                                ExpressionPreview.Valid(
                                    result.minorUnits,
                                    formatMinorUnits(result.minorUnits, currency.precision),
                                ),
                        )
                    is EntryExpressionEvaluator.Result.Invalid ->
                        state.copy(expressionPreview = ExpressionPreview.Invalid(result.code))
                }
            // P7-02.D E-3: applying a valid preview is the only path that edits the amount from
            // the calculator; a missing or invalid preview leaves the state untouched.
            P503UiEvent.ApplyExpressionResult ->
                when (val preview = state.expressionPreview) {
                    is ExpressionPreview.Valid ->
                        state.copy(draft = state.draft.withAmountText(preview.displayText), expressionPreview = null)
                    else -> state
                }
            // P7-02.D E-4: pin toggles belong to the overview lists; absorbed here (§6.2a).
            is P503UiEvent.TogglePin -> state
            // P7-03.C/D: the read-only ledger-view events are absorbed inside the editor (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // P7-04.C: the import review events are absorbed here too (table 6.2a).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> state
            is P503UiEvent.Continue ->
                if (validation.isValid(state.draft, currency)) {
                    P503AppState.AwaitingConfirmation(
                        draft = state.draft,
                        requestId = event.requestId,
                        overview = state.overview,
                        originTab = state.originTab,
                        // P5-04.3: host-resolved display labels; fall back to the draft id
                        // values when an option (or its label) is absent.
                        paymentAccountLabel = event.paymentAccountLabel ?: state.draft.primaryAccountId?.value ?: "",
                        categoryLabel = event.categoryLabel ?: state.draft.categoryId?.value ?: "",
                    )
                } else {
                    // Field error retains input and the (already allocated) requestId.
                    state.copy(requestId = event.requestId)
                }
            // System back closes the editor flow back to the originating overview tab,
            // dropping the draft (P5-04.2). Only reachable with a non-null overview.
            P503UiEvent.Back ->
                P503AppState.OverviewEmpty(checkNotNull(state.overview), state.originTab)
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
                P503AppState.Editing(draft = state.draft, requestId = null, overview = state.overview, originTab = state.originTab)
            P503UiEvent.Confirm ->
                P503AppState.Submitting(draft = state.draft, requestId = state.requestId, overview = state.overview, originTab = state.originTab)
            // A second confirm can arrive from a queued UI event after the first event has
            // already been handled. Keep the intent locked to the existing confirmation.
            is P503UiEvent.Continue -> state
            // P7-02: entry-field intents are absorbed while awaiting confirmation (§6.2a).
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P7-03.C/D: the read-only ledger-view events are absorbed here too (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // P7-04.C: the import review events are absorbed here too (table 6.2a).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> state
            // System back drops the draft and closes the editor flow (distinct from Cancel,
            // which keeps it) (P5-04.2).
            P503UiEvent.Back ->
                P503AppState.OverviewEmpty(checkNotNull(state.overview), state.originTab)
            else -> unhandled(state, event)
        }

    private fun reduceSubmitting(
        state: P503AppState.Submitting,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.SubmissionResult ->
                when (val result = event.result) {
                    is ManualEntrySubmissionResult.Expense -> reduceExpenseSubmission(state, result.result)
                    is ManualEntrySubmissionResult.Income -> reduceIncomeSubmission(state, result.result)
                    is ManualEntrySubmissionResult.Transfer -> reduceTransferSubmission(state, result.result)
                    is ManualEntrySubmissionResult.Lend -> reduceLendSubmission(state, result.result)
                    is ManualEntrySubmissionResult.Collect -> reduceCollectSubmission(state, result.result)
                }
            // Submission is single-flight; duplicate confirm/retry events are harmless.
            P503UiEvent.Confirm,
            P503UiEvent.RetrySubmission,
            -> state
            // P7-02: entry-field intents are absorbed while submitting (§6.2a).
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P7-03.C/D: the read-only ledger-view events are absorbed here too (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // P7-04.C: the import review events are absorbed here too (table 6.2a).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> state
            else -> unhandled(state, event)
        }

    private fun reduceExpenseSubmission(
        state: P503AppState.Submitting,
        result: com.unifiedledger.application.ManualExpenseSubmissionResult,
    ): P503AppState =
        when (result) {
            is com.unifiedledger.application.ManualExpenseSubmissionResult.Application ->
                when (val application = result.result) {
                    is ManualExpenseSaveResult.InvalidInput ->
                        P503AppState.Editing(state.draft, state.requestId, state.overview, state.originTab)
                    is ManualExpenseSaveResult.Executed ->
                        when (application.result) {
                            is ConfirmedManualExpenseResult.Created -> P503AppState.Created
                            is ConfirmedManualExpenseResult.NoChange -> P503AppState.NoChange
                            is ConfirmedManualExpenseResult.RequestIdentityConflict ->
                                P503AppState.RequestIdentityConflict(state.draft, state.requestId, state.overview, state.originTab)
                            is ConfirmedManualExpenseResult.Rejected ->
                                P503AppState.DomainRejected(state.draft, state.requestId, state.overview, state.originTab)
                        }
                }
            is com.unifiedledger.application.ManualExpenseSubmissionResult.InfrastructureFailure ->
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, state.draft, state.requestId, state.overview, state.originTab)
            is com.unifiedledger.application.ManualExpenseSubmissionResult.UnknownCommit ->
                P503AppState.UnknownCommit(state.draft, state.requestId, state.overview, state.originTab)
            is com.unifiedledger.application.ManualExpenseSubmissionResult.Recovered -> P503AppState.Recovered
        }

    private fun reduceIncomeSubmission(
        state: P503AppState.Submitting,
        result: ManualIncomeSubmissionResult,
    ): P503AppState =
        when (result) {
            is ManualIncomeSubmissionResult.Application ->
                when (val application = result.result) {
                    is ManualIncomeSaveResult.InvalidInput ->
                        P503AppState.Editing(state.draft, state.requestId, state.overview, state.originTab)
                    is ManualIncomeSaveResult.Executed ->
                        when (application.result) {
                            is ConfirmedManualIncomeResult.Created -> P503AppState.Created
                            is ConfirmedManualIncomeResult.NoChange -> P503AppState.NoChange
                            is ConfirmedManualIncomeResult.RequestIdentityConflict ->
                                P503AppState.RequestIdentityConflict(state.draft, state.requestId, state.overview, state.originTab)
                            is ConfirmedManualIncomeResult.Rejected ->
                                P503AppState.DomainRejected(state.draft, state.requestId, state.overview, state.originTab)
                        }
                }
            is ManualIncomeSubmissionResult.InfrastructureFailure ->
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, state.draft, state.requestId, state.overview, state.originTab)
            is ManualIncomeSubmissionResult.UnknownCommit ->
                P503AppState.UnknownCommit(state.draft, state.requestId, state.overview, state.originTab)
            is ManualIncomeSubmissionResult.Recovered -> P503AppState.Recovered
        }

    private fun reduceTransferSubmission(
        state: P503AppState.Submitting,
        result: ManualTransferSubmissionResult,
    ): P503AppState =
        when (result) {
            is ManualTransferSubmissionResult.Application ->
                when (val application = result.result) {
                    is ManualTransferSaveResult.InvalidInput ->
                        P503AppState.Editing(state.draft, state.requestId, state.overview, state.originTab)
                    is ManualTransferSaveResult.Executed ->
                        when (application.result) {
                            is ConfirmedManualTransferResult.Created -> P503AppState.Created
                            is ConfirmedManualTransferResult.NoChange -> P503AppState.NoChange
                            is ConfirmedManualTransferResult.RequestIdentityConflict ->
                                P503AppState.RequestIdentityConflict(state.draft, state.requestId, state.overview, state.originTab)
                            is ConfirmedManualTransferResult.Rejected ->
                                P503AppState.DomainRejected(state.draft, state.requestId, state.overview, state.originTab)
                        }
                }
            is ManualTransferSubmissionResult.InfrastructureFailure ->
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, state.draft, state.requestId, state.overview, state.originTab)
            is ManualTransferSubmissionResult.UnknownCommit ->
                P503AppState.UnknownCommit(state.draft, state.requestId, state.overview, state.originTab)
            is ManualTransferSubmissionResult.Recovered -> P503AppState.Recovered
        }

    private fun reduceUnknownCommit(
        state: P503AppState.UnknownCommit,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            // P5-04.3: one read-only status check drives the frozen four-outcome resolution
            // (D-119); only MatchingReceipt may recover and a conflict keeps its screen.
            is P503UiEvent.CommitStatusResolved ->
                when (val resolution = event.resolution) {
                    is ManualEntryCommitResolution.Expense -> reduceExpenseResolution(state, resolution.resolution)
                    is ManualEntryCommitResolution.Income -> reduceIncomeResolution(state, resolution.resolution)
                    is ManualEntryCommitResolution.Transfer -> reduceTransferResolution(state, resolution.resolution)
                    is ManualEntryCommitResolution.Lend -> reduceLendResolution(state, resolution.resolution)
                    is ManualEntryCommitResolution.Collect -> reduceCollectResolution(state, resolution.resolution)
                }
            // The host dispatches this alongside the check call in its click handler; the
            // state instance stays untouched so the entry auto-check guard does not re-run.
            P503UiEvent.RetryCommitStatusCheck -> state
            // Every other event is still absorbed: UnknownCommit forbids automatic retry,
            // optimistic refresh and requestId replacement (D-119/D-120).
            else -> state
        }

    private fun reduceExpenseResolution(
        state: P503AppState.UnknownCommit,
        resolution: ManualExpenseCommitResolution,
    ): P503AppState =
        when (resolution) {
            is ManualExpenseCommitResolution.MatchingReceipt -> P503AppState.Recovered
            ManualExpenseCommitResolution.SnapshotConflict ->
                P503AppState.RequestIdentityConflict(checkNotNull(state.draft), checkNotNull(state.requestId), state.overview, state.originTab)
            ManualExpenseCommitResolution.Absent -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
            ManualExpenseCommitResolution.Unavailable -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.UNAVAILABLE)
        }

    private fun reduceIncomeResolution(
        state: P503AppState.UnknownCommit,
        resolution: ManualIncomeCommitResolution,
    ): P503AppState =
        when (resolution) {
            is ManualIncomeCommitResolution.MatchingReceipt -> P503AppState.Recovered
            ManualIncomeCommitResolution.SnapshotConflict ->
                P503AppState.RequestIdentityConflict(checkNotNull(state.draft), checkNotNull(state.requestId), state.overview, state.originTab)
            ManualIncomeCommitResolution.Absent -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
            ManualIncomeCommitResolution.Unavailable -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.UNAVAILABLE)
        }

    private fun reduceTransferResolution(
        state: P503AppState.UnknownCommit,
        resolution: ManualTransferCommitResolution,
    ): P503AppState =
        when (resolution) {
            is ManualTransferCommitResolution.MatchingReceipt -> P503AppState.Recovered
            ManualTransferCommitResolution.SnapshotConflict ->
                P503AppState.RequestIdentityConflict(checkNotNull(state.draft), checkNotNull(state.requestId), state.overview, state.originTab)
            ManualTransferCommitResolution.Absent -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
            ManualTransferCommitResolution.Unavailable -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.UNAVAILABLE)
        }

    private fun reduceLendSubmission(
        state: P503AppState.Submitting,
        result: ManualLendSubmissionResult,
    ): P503AppState =
        when (result) {
            is ManualLendSubmissionResult.Application ->
                when (val application = result.result) {
                    is ManualLendSaveResult.InvalidInput ->
                        P503AppState.Editing(state.draft, state.requestId, state.overview, state.originTab)
                    is ManualLendSaveResult.Executed ->
                        when (application.result) {
                            is ConfirmedManualLendingResult.Created -> P503AppState.Created
                            is ConfirmedManualLendingResult.NoChange -> P503AppState.NoChange
                            is ConfirmedManualLendingResult.RequestIdentityConflict ->
                                P503AppState.RequestIdentityConflict(state.draft, state.requestId, state.overview, state.originTab)
                            is ConfirmedManualLendingResult.Rejected ->
                                P503AppState.DomainRejected(state.draft, state.requestId, state.overview, state.originTab)
                        }
                }
            ManualLendSubmissionResult.InfrastructureFailure ->
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, state.draft, state.requestId, state.overview, state.originTab)
            ManualLendSubmissionResult.UnknownCommit ->
                P503AppState.UnknownCommit(state.draft, state.requestId, state.overview, state.originTab)
            is ManualLendSubmissionResult.Recovered -> P503AppState.Recovered
        }

    private fun reduceCollectSubmission(
        state: P503AppState.Submitting,
        result: ManualCollectSubmissionResult,
    ): P503AppState =
        when (result) {
            is ManualCollectSubmissionResult.Application ->
                when (val application = result.result) {
                    is ManualCollectSaveResult.InvalidInput ->
                        P503AppState.Editing(state.draft, state.requestId, state.overview, state.originTab)
                    is ManualCollectSaveResult.Executed ->
                        when (application.result) {
                            is ConfirmedManualLendingResult.Created -> P503AppState.Created
                            is ConfirmedManualLendingResult.NoChange -> P503AppState.NoChange
                            is ConfirmedManualLendingResult.RequestIdentityConflict ->
                                P503AppState.RequestIdentityConflict(state.draft, state.requestId, state.overview, state.originTab)
                            is ConfirmedManualLendingResult.Rejected ->
                                P503AppState.DomainRejected(state.draft, state.requestId, state.overview, state.originTab)
                        }
                }
            ManualCollectSubmissionResult.InfrastructureFailure ->
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, state.draft, state.requestId, state.overview, state.originTab)
            ManualCollectSubmissionResult.UnknownCommit ->
                P503AppState.UnknownCommit(state.draft, state.requestId, state.overview, state.originTab)
            is ManualCollectSubmissionResult.Recovered -> P503AppState.Recovered
        }

    private fun reduceLendResolution(
        state: P503AppState.UnknownCommit,
        resolution: ManualLendingCommitResolution,
    ): P503AppState =
        when (resolution) {
            is ManualLendingCommitResolution.MatchingReceipt -> P503AppState.Recovered
            ManualLendingCommitResolution.SnapshotConflict ->
                P503AppState.RequestIdentityConflict(checkNotNull(state.draft), checkNotNull(state.requestId), state.overview, state.originTab)
            ManualLendingCommitResolution.Absent -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
            ManualLendingCommitResolution.Unavailable -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.UNAVAILABLE)
        }

    private fun reduceCollectResolution(
        state: P503AppState.UnknownCommit,
        resolution: ManualLendingCommitResolution,
    ): P503AppState =
        when (resolution) {
            is ManualLendingCommitResolution.MatchingReceipt -> P503AppState.Recovered
            ManualLendingCommitResolution.SnapshotConflict ->
                P503AppState.RequestIdentityConflict(checkNotNull(state.draft), checkNotNull(state.requestId), state.overview, state.originTab)
            ManualLendingCommitResolution.Absent -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.ABSENT)
            ManualLendingCommitResolution.Unavailable -> state.copy(lastCheckOutcome = UnknownCommitCheckOutcome.UNAVAILABLE)
        }

    private fun reduceTransientResult(
        event: P503UiEvent,
        current: P503AppState,
    ): P503AppState =
        when (event) {
            // The authoritative refresh after a submission flow always returns to the home
            // tab; the submission states carry no tab. P7-02 G-C: a determinate-success
            // refresh carries the host-captured retained intent into the new overview, and
            // P7-02.D E-4 carries the host's pin mirror.
            is P503UiEvent.RefreshResult ->
                P503AppState.OverviewEmpty(
                    state = event.currentState,
                    selectedTab = P503Tab.HOME,
                    retainedIntent = event.retainedIntent,
                    pinnedTargets = event.pinnedTargets,
                )
            P503UiEvent.RefreshFailed -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
            // P7-02: new entry-foundation events are absorbed in every transient result state.
            is P503UiEvent.SelectEntryType,
            is P503UiEvent.UpdateNote,
            is P503UiEvent.UpdateReceivingAccount,
            is P503UiEvent.UpdateIncomeCategory,
            is P503UiEvent.UpdateTransferSourceAccount,
            is P503UiEvent.UpdateTransferDestinationAccount,
            is P503UiEvent.UpdateTransferDestinationCredit,
            is P503UiEvent.UpdateTransferFee,
            is P503UiEvent.UpdateTransferFeeCategory,
            is P503UiEvent.UpdateLendCounterparty,
            is P503UiEvent.UpdateLendFundingAccount,
            is P503UiEvent.UpdateLendAmount,
            is P503UiEvent.UpdateCollectCounterparty,
            is P503UiEvent.UpdateCollectDestinationAccount,
            is P503UiEvent.UpdateCollectTotal,
            is P503UiEvent.UpdateCollectPrincipal,
            is P503UiEvent.UpdateCollectInterest,
            is P503UiEvent.UpdateCollectInterestCategory,
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P7-03.C/D: the read-only ledger-view events are absorbed in every transient state.
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // P7-04.C: the import review events are absorbed in every transient state (table 6.2a).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed in every transient state (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> current
            else -> unhandled(current, event)
        }

    private fun reduceRequestIdentityConflict(
        state: P503AppState.RequestIdentityConflict,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.UpdateAmount ->
                P503AppState.Editing(state.draft.withAmountText(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdatePaymentAccount ->
                P503AppState.Editing(state.draft.withPrimaryAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCategory ->
                P503AppState.Editing(state.draft.withCategory(event.categoryId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateOccurredAt ->
                P503AppState.Editing(state.draft.withOccurredAt(event.instant), state.requestId, state.overview, state.originTab)
            // P7-02: note/income/transfer field edits return to Editing with typing retention (D-140).
            is P503UiEvent.UpdateNote ->
                P503AppState.Editing(state.draft.withNote(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateReceivingAccount ->
                P503AppState.Editing(state.draft.withPrimaryAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateIncomeCategory ->
                P503AppState.Editing(state.draft.withCategory(event.categoryId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferSourceAccount ->
                P503AppState.Editing(state.draft.withTransferSourceAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferDestinationAccount ->
                P503AppState.Editing(state.draft.withTransferDestinationAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferDestinationCredit ->
                P503AppState.Editing(state.draft.withTransferDestinationCredit(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferFee ->
                P503AppState.Editing(state.draft.withTransferFee(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferFeeCategory ->
                P503AppState.Editing(state.draft.withTransferFeeCategory(event.categoryId), state.requestId, state.overview, state.originTab)
            // P7-02.C: lending field edits return to Editing with typing retention (D-140).
            is P503UiEvent.UpdateLendCounterparty ->
                P503AppState.Editing((state.draft as? LendDraft)?.copy(counterpartyId = event.counterpartyId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateLendFundingAccount ->
                P503AppState.Editing((state.draft as? LendDraft)?.copy(fundingAccountId = event.accountId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateLendAmount ->
                P503AppState.Editing((state.draft as? LendDraft)?.copy(amount = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectCounterparty ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(counterpartyId = event.counterpartyId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectDestinationAccount ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(destinationAccountId = event.accountId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectTotal ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(totalReceived = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectPrincipal ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(principal = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectInterest ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(interest = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectInterestCategory ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(interestCategoryId = event.categoryId) ?: state.draft, state.requestId, state.overview, state.originTab)
            // P7-02: a type switch and "record again" are absorbed on the conflict screen.
            is P503UiEvent.SelectEntryType,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P702SPEC-03: the counterparty form intents are absorbed here too.
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            // P7-03.C/D: the read-only ledger-view events are absorbed here too (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // P7-04.C: the import review events are absorbed here too (table 6.2a).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> state
            // Explicitly abandoning the conflicting draft starts a new save intent.
            P503UiEvent.AbandonConflict ->
                P503AppState.Editing(draft = state.draft, requestId = null, overview = state.overview, originTab = state.originTab)
            P503UiEvent.Back ->
                P503AppState.OverviewEmpty(checkNotNull(state.overview), state.originTab)
            else -> unhandled(state, event)
        }

    private fun reduceDomainRejected(
        state: P503AppState.DomainRejected,
        event: P503UiEvent,
    ): P503AppState =
        when (event) {
            is P503UiEvent.UpdateAmount ->
                P503AppState.Editing(state.draft.withAmountText(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdatePaymentAccount ->
                P503AppState.Editing(state.draft.withPrimaryAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCategory ->
                P503AppState.Editing(state.draft.withCategory(event.categoryId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateOccurredAt ->
                P503AppState.Editing(state.draft.withOccurredAt(event.instant), state.requestId, state.overview, state.originTab)
            // P7-02: note/income/transfer field edits return to Editing with typing retention (D-140).
            is P503UiEvent.UpdateNote ->
                P503AppState.Editing(state.draft.withNote(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateReceivingAccount ->
                P503AppState.Editing(state.draft.withPrimaryAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateIncomeCategory ->
                P503AppState.Editing(state.draft.withCategory(event.categoryId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferSourceAccount ->
                P503AppState.Editing(state.draft.withTransferSourceAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferDestinationAccount ->
                P503AppState.Editing(state.draft.withTransferDestinationAccount(event.accountId), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferDestinationCredit ->
                P503AppState.Editing(state.draft.withTransferDestinationCredit(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferFee ->
                P503AppState.Editing(state.draft.withTransferFee(event.text), state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateTransferFeeCategory ->
                P503AppState.Editing(state.draft.withTransferFeeCategory(event.categoryId), state.requestId, state.overview, state.originTab)
            // P7-02.C: lending field edits return to Editing with typing retention (D-140).
            is P503UiEvent.UpdateLendCounterparty ->
                P503AppState.Editing((state.draft as? LendDraft)?.copy(counterpartyId = event.counterpartyId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateLendFundingAccount ->
                P503AppState.Editing((state.draft as? LendDraft)?.copy(fundingAccountId = event.accountId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateLendAmount ->
                P503AppState.Editing((state.draft as? LendDraft)?.copy(amount = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectCounterparty ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(counterpartyId = event.counterpartyId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectDestinationAccount ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(destinationAccountId = event.accountId) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectTotal ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(totalReceived = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectPrincipal ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(principal = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectInterest ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(interest = event.text) ?: state.draft, state.requestId, state.overview, state.originTab)
            is P503UiEvent.UpdateCollectInterestCategory ->
                P503AppState.Editing((state.draft as? CollectDraft)?.copy(interestCategoryId = event.categoryId) ?: state.draft, state.requestId, state.overview, state.originTab)
            // P7-02: a type switch and "record again" are absorbed on the rejection screen.
            is P503UiEvent.SelectEntryType,
            P503UiEvent.ApplyExpressionResult,
            is P503UiEvent.EvaluateEntryExpression,
            is P503UiEvent.SaveAndRecordAgain,
            is P503UiEvent.TogglePin,
            // P702SPEC-03: the counterparty form intents are absorbed here too.
            P503UiEvent.OpenCounterpartyCreateDialog,
            is P503UiEvent.OpenCounterpartyRenameDialog,
            is P503UiEvent.UpdateCounterpartyFormText,
            P503UiEvent.DismissCounterpartyDialog,
            // P7-03.C/D: the read-only ledger-view events are absorbed here too (§6.2a).
            is P503UiEvent.SelectTransaction,
            P503UiEvent.CloseTransactionDetail,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            is P503UiEvent.MonthlyActivityResult,
            // P7-04.C: the import review events are absorbed here too (table 6.2a).
            is P503UiEvent.StartImportFilePick,
            is P503UiEvent.ImportFilePicked,
            P503UiEvent.ImportFilePickCancelled,
            is P503UiEvent.ImportFilePickFailed,
            is P503UiEvent.ImportFileIntakeResult,
            P503UiEvent.RefreshImportReview,
            is P503UiEvent.ImportReviewResult,
            is P503UiEvent.SelectImportCandidate,
            P503UiEvent.CloseImportCandidateDetail,
            is P503UiEvent.UpdateImportDecisionField,
            is P503UiEvent.ToggleImportCandidateSelection,
            is P503UiEvent.SubmitImportDuplicateReview,
            is P503UiEvent.ImportDuplicateReviewResult,
            is P503UiEvent.StartImportDuplicateGroupDisposition,
            is P503UiEvent.ImportDuplicateGroupDispositionResult,
            P503UiEvent.CloseImportDuplicateGroupDisposition,
            // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            is P503UiEvent.AuthorizeImportBatch,
            is P503UiEvent.ImportItemResult,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            is P503UiEvent.ImportUnknownItemCheck,
            is P503UiEvent.ImportUnknownItemCheckResult,
            -> state
            P503UiEvent.Back ->
                P503AppState.OverviewEmpty(checkNotNull(state.overview), state.originTab)
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
                        P503AppState.Submitting(checkNotNull(state.draft), checkNotNull(state.requestId), state.overview, state.originTab)
                    P503UiEvent.Cancel ->
                        P503AppState.Editing(checkNotNull(state.draft), checkNotNull(state.requestId), state.overview, state.originTab)
                    P503UiEvent.Back ->
                        P503AppState.OverviewEmpty(checkNotNull(state.overview), state.originTab)
                    // P7-02: entry-field intents are absorbed in SUBMISSION failure (§6.2a).
                    is P503UiEvent.SelectEntryType,
                    is P503UiEvent.UpdateNote,
                    is P503UiEvent.UpdateReceivingAccount,
                    is P503UiEvent.UpdateIncomeCategory,
                    is P503UiEvent.UpdateTransferSourceAccount,
                    is P503UiEvent.UpdateTransferDestinationAccount,
                    is P503UiEvent.UpdateTransferDestinationCredit,
                    is P503UiEvent.UpdateTransferFee,
                    is P503UiEvent.UpdateTransferFeeCategory,
                    is P503UiEvent.UpdateLendCounterparty,
                    is P503UiEvent.UpdateLendFundingAccount,
                    is P503UiEvent.UpdateLendAmount,
                    is P503UiEvent.UpdateCollectCounterparty,
                    is P503UiEvent.UpdateCollectDestinationAccount,
                    is P503UiEvent.UpdateCollectTotal,
                    is P503UiEvent.UpdateCollectPrincipal,
                    is P503UiEvent.UpdateCollectInterest,
                    is P503UiEvent.UpdateCollectInterestCategory,
                    P503UiEvent.OpenCounterpartyCreateDialog,
                    is P503UiEvent.OpenCounterpartyRenameDialog,
                    is P503UiEvent.UpdateCounterpartyFormText,
                    P503UiEvent.DismissCounterpartyDialog,
                    P503UiEvent.ApplyExpressionResult,
                    is P503UiEvent.EvaluateEntryExpression,
                    is P503UiEvent.SaveAndRecordAgain,
                    is P503UiEvent.TogglePin,
                    // P7-03.C/D: the read-only ledger-view events are absorbed here too (§6.2a).
                    is P503UiEvent.SelectTransaction,
                    P503UiEvent.CloseTransactionDetail,
                    is P503UiEvent.SelectMonth,
                    is P503UiEvent.AnalysisMonthShift,
                    is P503UiEvent.MonthlyActivityResult,
                    // P7-04.C: the import review events are absorbed here too (table 6.2a).
                    is P503UiEvent.StartImportFilePick,
                    is P503UiEvent.ImportFilePicked,
                    P503UiEvent.ImportFilePickCancelled,
                    is P503UiEvent.ImportFilePickFailed,
                    is P503UiEvent.ImportFileIntakeResult,
                    P503UiEvent.RefreshImportReview,
                    is P503UiEvent.ImportReviewResult,
                    is P503UiEvent.SelectImportCandidate,
                    P503UiEvent.CloseImportCandidateDetail,
                    is P503UiEvent.UpdateImportDecisionField,
                    is P503UiEvent.ToggleImportCandidateSelection,
                    is P503UiEvent.SubmitImportDuplicateReview,
                    is P503UiEvent.ImportDuplicateReviewResult,
                    is P503UiEvent.StartImportDuplicateGroupDisposition,
                    is P503UiEvent.ImportDuplicateGroupDispositionResult,
                    P503UiEvent.CloseImportDuplicateGroupDisposition,
                    // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
                    P503UiEvent.RequestImportBatchConfirm,
                    P503UiEvent.CancelImportBatchConfirm,
                    is P503UiEvent.AuthorizeImportBatch,
                    is P503UiEvent.ImportItemResult,
                    P503UiEvent.ResumeImportBatchDispatch,
                    P503UiEvent.AbandonImportBatch,
                    is P503UiEvent.ImportUnknownItemCheck,
                    is P503UiEvent.ImportUnknownItemCheckResult,
                    -> state
                    else -> unhandled(state, event)
                }
            InfrastructureFailureContext.READ ->
                when (event) {
                    P503UiEvent.RetryRefresh -> state
                    // P7-02 G-C: a successful read retry still forwards the retained intent;
                    // P7-02.D E-4 also carries the host's pin mirror into the fresh overview.
                    // P7-03.D (F2): when the failure came from a monthly read, the retry keeps the
                    // month cursor and the SelectMonth domain that were on screen (so the user is
                    // never stranded on a month-less, stepper-less overview) and marks the monthly
                    // payload as not loaded, because RetryRefresh is deliberately outside the
                    // frozen monthly re-request trigger set (spec 6.2 residual boundary (a)); the
                    // explicit re-select affordance dispatches SelectMonth (trigger (b)). All
                    // pre-P7-03 READ failures have monthlyOverview == null and keep this branch
                    // byte-for-byte at its previous semantics.
                    is P503UiEvent.RefreshResult ->
                        P503AppState.OverviewEmpty(
                            state = event.currentState,
                            selectedTab = P503Tab.HOME,
                            retainedIntent = event.retainedIntent,
                            pinnedTargets = event.pinnedTargets,
                            selectedMonth = state.monthlyOverview?.selectedMonth,
                            selectableMonths = state.monthlyOverview?.selectableMonths ?: emptyList(),
                            monthlyActivity = null,
                            monthlyReloadRequired = state.monthlyOverview != null,
                        )
                    P503UiEvent.RefreshFailed -> state
                    // P7-02: new entry-foundation events are absorbed in READ failure too.
                    is P503UiEvent.SelectEntryType,
                    is P503UiEvent.UpdateNote,
                    is P503UiEvent.UpdateReceivingAccount,
                    is P503UiEvent.UpdateIncomeCategory,
                    is P503UiEvent.UpdateTransferSourceAccount,
                    is P503UiEvent.UpdateTransferDestinationAccount,
                    is P503UiEvent.UpdateTransferDestinationCredit,
                    is P503UiEvent.UpdateTransferFee,
                    is P503UiEvent.UpdateTransferFeeCategory,
                    is P503UiEvent.UpdateLendCounterparty,
                    is P503UiEvent.UpdateLendFundingAccount,
                    is P503UiEvent.UpdateLendAmount,
                    is P503UiEvent.UpdateCollectCounterparty,
                    is P503UiEvent.UpdateCollectDestinationAccount,
                    is P503UiEvent.UpdateCollectTotal,
                    is P503UiEvent.UpdateCollectPrincipal,
                    is P503UiEvent.UpdateCollectInterest,
                    is P503UiEvent.UpdateCollectInterestCategory,
                    P503UiEvent.OpenCounterpartyCreateDialog,
                    is P503UiEvent.OpenCounterpartyRenameDialog,
                    is P503UiEvent.UpdateCounterpartyFormText,
                    P503UiEvent.DismissCounterpartyDialog,
                    P503UiEvent.ApplyExpressionResult,
                    is P503UiEvent.EvaluateEntryExpression,
                    is P503UiEvent.SaveAndRecordAgain,
                    is P503UiEvent.TogglePin,
                    // P7-03.C/D: the read-only ledger-view events are absorbed here too (§6.2a).
                    is P503UiEvent.SelectTransaction,
                    P503UiEvent.CloseTransactionDetail,
                    is P503UiEvent.SelectMonth,
                    is P503UiEvent.AnalysisMonthShift,
                    is P503UiEvent.MonthlyActivityResult,
                    // P7-04.C: the import review events are absorbed here too (table 6.2a).
                    is P503UiEvent.StartImportFilePick,
                    is P503UiEvent.ImportFilePicked,
                    P503UiEvent.ImportFilePickCancelled,
                    is P503UiEvent.ImportFilePickFailed,
                    is P503UiEvent.ImportFileIntakeResult,
                    P503UiEvent.RefreshImportReview,
                    is P503UiEvent.ImportReviewResult,
                    is P503UiEvent.SelectImportCandidate,
                    P503UiEvent.CloseImportCandidateDetail,
                    is P503UiEvent.UpdateImportDecisionField,
                    is P503UiEvent.ToggleImportCandidateSelection,
                    is P503UiEvent.SubmitImportDuplicateReview,
                    is P503UiEvent.ImportDuplicateReviewResult,
                    is P503UiEvent.StartImportDuplicateGroupDisposition,
                    is P503UiEvent.ImportDuplicateGroupDispositionResult,
                    P503UiEvent.CloseImportDuplicateGroupDisposition,
                    // P7-04.D: the batch confirmation events are absorbed here too (table 6.2a).
                    P503UiEvent.RequestImportBatchConfirm,
                    P503UiEvent.CancelImportBatchConfirm,
                    is P503UiEvent.AuthorizeImportBatch,
                    is P503UiEvent.ImportItemResult,
                    P503UiEvent.ResumeImportBatchDispatch,
                    P503UiEvent.AbandonImportBatch,
                    is P503UiEvent.ImportUnknownItemCheck,
                    is P503UiEvent.ImportUnknownItemCheckResult,
                    -> state
                    else -> unhandled(state, event)
                }
        }

    private fun unhandled(
        state: P503AppState,
        event: P503UiEvent,
    ): Nothing = throw IllegalStateException("Unhandled P5-03 event $event in state $state")
}

/** P7-02: sets the draft's primary account while preserving the concrete subtype. */
private fun TypedEntryDraft.withPrimaryAccount(accountId: AccountId): TypedEntryDraft =
    when (this) {
        is ExpenseDraft -> copy(paymentAccountId = accountId)
        is IncomeDraft -> copy(receivingAccountId = accountId)
        is TransferDraft -> copy(sourceAccountId = accountId)
        is LendDraft -> copy(fundingAccountId = accountId)
        is CollectDraft -> copy(destinationAccountId = accountId)
    }

/** P7-02: sets the draft's category while preserving the concrete subtype. */
private fun TypedEntryDraft.withCategory(categoryId: CategoryId): TypedEntryDraft =
    when (this) {
        is ExpenseDraft -> copy(categoryId = categoryId)
        is IncomeDraft -> copy(categoryId = categoryId)
        is TransferDraft -> copy(feeCategoryId = categoryId)
        is LendDraft -> this
        is CollectDraft -> copy(interestCategoryId = categoryId)
    }

/** P7-02.B transfer source drawer; absorbed on non-transfer drafts. */
private fun TypedEntryDraft.withTransferSourceAccount(accountId: AccountId): TypedEntryDraft = if (this is TransferDraft) copy(sourceAccountId = accountId) else this

/** P7-02.B transfer destination drawer; absorbed on non-transfer drafts. */
private fun TypedEntryDraft.withTransferDestinationAccount(accountId: AccountId): TypedEntryDraft = if (this is TransferDraft) copy(destinationAccountId = accountId) else this

/** P7-02.B transfer destination-credit text; absorbed on non-transfer drafts. */
private fun TypedEntryDraft.withTransferDestinationCredit(text: String): TypedEntryDraft = if (this is TransferDraft) copy(destinationCredit = text) else this

/** P7-02.B transfer fee text; absorbed on non-transfer drafts. */
private fun TypedEntryDraft.withTransferFee(text: String): TypedEntryDraft = if (this is TransferDraft) copy(fee = text) else this

/** P7-02.B transfer fee category; absorbed on non-transfer drafts. */
private fun TypedEntryDraft.withTransferFeeCategory(categoryId: CategoryId): TypedEntryDraft = if (this is TransferDraft) copy(feeCategoryId = categoryId) else this

/** P7-02.C lend counterparty; absorbed on non-lend drafts (E-1 type-specific field). */
private fun TypedEntryDraft.withLendCounterparty(counterpartyId: CounterpartyId): TypedEntryDraft = if (this is LendDraft) copy(counterpartyId = counterpartyId) else this

/** P7-02.C lend funding account; absorbed on non-lend drafts. */
private fun TypedEntryDraft.withLendFundingAccount(accountId: AccountId): TypedEntryDraft = if (this is LendDraft) copy(fundingAccountId = accountId) else this

/** P7-02.C lend amount text; absorbed on non-lend drafts. */
private fun TypedEntryDraft.withLendAmount(text: String): TypedEntryDraft = if (this is LendDraft) copy(amount = text) else this

/** P7-02.C collect counterparty; absorbed on non-collect drafts (E-1 type-specific field). */
private fun TypedEntryDraft.withCollectCounterparty(counterpartyId: CounterpartyId): TypedEntryDraft = if (this is CollectDraft) copy(counterpartyId = counterpartyId) else this

/** P7-02.C collect destination account; absorbed on non-collect drafts. */
private fun TypedEntryDraft.withCollectDestinationAccount(accountId: AccountId): TypedEntryDraft = if (this is CollectDraft) copy(destinationAccountId = accountId) else this

/** P7-02.C collect total-received text; absorbed on non-collect drafts. */
private fun TypedEntryDraft.withCollectTotal(text: String): TypedEntryDraft = if (this is CollectDraft) copy(totalReceived = text) else this

/** P7-02.C collect principal component text; absorbed on non-collect drafts. */
private fun TypedEntryDraft.withCollectPrincipal(text: String): TypedEntryDraft = if (this is CollectDraft) copy(principal = text) else this

/** P7-02.C collect interest component text; absorbed on non-collect drafts. */
private fun TypedEntryDraft.withCollectInterest(text: String): TypedEntryDraft = if (this is CollectDraft) copy(interest = text) else this

/** P7-02.C collect interest category; absorbed on non-collect drafts. */
private fun TypedEntryDraft.withCollectInterestCategory(categoryId: CategoryId): TypedEntryDraft = if (this is CollectDraft) copy(interestCategoryId = categoryId) else this
