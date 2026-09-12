package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.EntryFieldRetention
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualIncomeCommitResolution
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.ManualIncomeSubmissionResult
import com.unifiedledger.application.ManualTransferCommitResolution
import com.unifiedledger.application.ManualTransferSaveResult
import com.unifiedledger.application.ManualTransferSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit

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
) : P503Reducer {
    private val validation = P503DraftValidation(parseAmount)

    override fun reduce(
        state: P503AppState,
        event: P503UiEvent,
    ): P503AppState =
        when (state) {
            is P503AppState.Ready -> reduceReady(event)
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
            is P503AppState.UnknownCommit -> reduceUnknownCommit(state, event)
        }

    private fun reduceReady(event: P503UiEvent): P503AppState =
        when (event) {
            // The authoritative load always lands on the home tab (D-122).
            is P503UiEvent.InitialLoadResult -> P503AppState.OverviewEmpty(event.currentState, P503Tab.HOME)
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
            P503UiEvent.SaveAndRecordAgain,
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
            // P7-02 E-2: only a determinate-success retained intent can start a new editor;
            // the amount/note/old confirmation are cleared, occurredAt is the current clock
            // instant, and a new (null) requestId is allocated by the host on Continue.
            P503UiEvent.SaveAndRecordAgain ->
                state.retainedIntent?.let { intent ->
                    P503AppState.Editing(
                        draft =
                            when (intent.type) {
                                com.unifiedledger.application.EntryType.INCOME ->
                                    IncomeDraft(intent.paymentAccountId, intent.categoryId, "", ledgerClock?.now(), "")
                                com.unifiedledger.application.EntryType.TRANSFER ->
                                    TransferDraft(
                                        sourceAccountId = intent.paymentAccountId,
                                        destinationAccountId = null,
                                        destinationCredit = "",
                                        fee = com.unifiedledger.application.DEFAULT_TRANSFER_FEE_TEXT,
                                        feeCategoryId = null,
                                        occurredAt = ledgerClock?.now(),
                                        note = "",
                                    )
                                else ->
                                    ExpenseDraft(intent.paymentAccountId, intent.categoryId, "", ledgerClock?.now(), "")
                            },
                        requestId = null,
                        overview = state.state,
                        originTab = intent.originTab,
                    )
                } ?: state
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
            P503UiEvent.SaveAndRecordAgain,
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
            P503UiEvent.SaveAndRecordAgain,
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

    private fun reduceTransientResult(
        event: P503UiEvent,
        current: P503AppState,
    ): P503AppState =
        when (event) {
            // The authoritative refresh after a submission flow always returns to the home
            // tab; the submission states carry no tab. P7-02 G-C: a determinate-success
            // refresh carries the host-captured retained intent into the new overview.
            is P503UiEvent.RefreshResult ->
                P503AppState.OverviewEmpty(state = event.currentState, selectedTab = P503Tab.HOME, retainedIntent = event.retainedIntent)
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
            P503UiEvent.SaveAndRecordAgain,
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
            // P7-02: a type switch and "record again" are absorbed on the conflict screen.
            is P503UiEvent.SelectEntryType,
            P503UiEvent.SaveAndRecordAgain,
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
            // P7-02: a type switch and "record again" are absorbed on the rejection screen.
            is P503UiEvent.SelectEntryType,
            P503UiEvent.SaveAndRecordAgain,
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
                    P503UiEvent.SaveAndRecordAgain,
                    -> state
                    else -> unhandled(state, event)
                }
            InfrastructureFailureContext.READ ->
                when (event) {
                    P503UiEvent.RetryRefresh -> state
                    // P7-02 G-C: a successful read retry still forwards the retained intent.
                    is P503UiEvent.RefreshResult ->
                        P503AppState.OverviewEmpty(state = event.currentState, selectedTab = P503Tab.HOME, retainedIntent = event.retainedIntent)
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
                    P503UiEvent.SaveAndRecordAgain,
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
    }

/** P7-02: sets the draft's category while preserving the concrete subtype. */
private fun TypedEntryDraft.withCategory(categoryId: CategoryId): TypedEntryDraft =
    when (this) {
        is ExpenseDraft -> copy(categoryId = categoryId)
        is IncomeDraft -> copy(categoryId = categoryId)
        is TransferDraft -> copy(feeCategoryId = categoryId)
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
