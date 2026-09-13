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
                        state.copy(monthlyActivity = result.activity, selectableMonths = event.selectableMonths)
                    // 失败 → READ failure while preserving the last successful overview
                    // (spec 4.3/C04: 上一成功载荷保留 + 显式失败条); the retry stays the existing
                    // RetryRefresh branch (spec 6.3) and recovery is a re-dispatched SelectMonth
                    // (residual boundary (a)).
                    com.unifiedledger.application.MonthlyActivityResult.InvalidState,
                    com.unifiedledger.application.MonthlyActivityResult.Unavailable,
                    -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ, monthlyOverview = state)
                }
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
                        state.copy(overview = state.overview.copy(monthlyActivity = result.activity, selectableMonths = event.selectableMonths))
                    com.unifiedledger.application.MonthlyActivityResult.InvalidState,
                    com.unifiedledger.application.MonthlyActivityResult.Unavailable,
                    -> P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ, monthlyOverview = state.overview)
                }
            is P503UiEvent.SelectTransaction,
            is P503UiEvent.SelectMonth,
            is P503UiEvent.AnalysisMonthShift,
            -> state
            else -> unhandled(state, event)
        }

    /** P7-03.C: the shifted month cursor, or `null` when no usable base month exists (absorbed). */
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
        return shifted
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
                    -> state
                    else -> unhandled(state, event)
                }
            InfrastructureFailureContext.READ ->
                when (event) {
                    P503UiEvent.RetryRefresh -> state
                    // P7-02 G-C: a successful read retry still forwards the retained intent;
                    // P7-02.D E-4 also carries the host's pin mirror into the fresh overview.
                    is P503UiEvent.RefreshResult ->
                        P503AppState.OverviewEmpty(
                            state = event.currentState,
                            selectedTab = P503Tab.HOME,
                            retainedIntent = event.retainedIntent,
                            pinnedTargets = event.pinnedTargets,
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
