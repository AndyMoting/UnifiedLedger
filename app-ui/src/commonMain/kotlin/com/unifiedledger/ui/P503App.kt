package com.unifiedledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySaveInput
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualExpenseInputFailure
import com.unifiedledger.application.ManualExpenseInputField
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ManualIncomeRequestSnapshot
import com.unifiedledger.application.ManualIncomeSaveInput
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.ManualIncomeSubmissionResult
import com.unifiedledger.application.ManualTransferInputField
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferSaveInput
import com.unifiedledger.application.ManualTransferSaveResult
import com.unifiedledger.application.ManualTransferSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransferDraft
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import kotlinx.coroutines.launch

/**
 * D-134 D2-D1 shared dual-theme wrapper: an explicit light/dark colorScheme following the
 * system mode plus a full-size background paint, so screens without their own background
 * (edit, startup, infrastructure failure) never expose the platform window default
 * (P6-ENTRY-THEME-002; spec section 4/D2-D1). Shared by Android and Desktop; the platform
 * root may wrap its whole content with this composable (MainActivity pre-Ready path) —
 * nesting is harmless, while a bare inner MaterialTheme would reset to the default light
 * scheme.
 */
@Composable
fun P503Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

/**
 * P5-03 shared demo surface entry (spec sections 4.7/7/8). The composition root builds the
 * [P503LedgerFacade] and calls this composable once startup is ready. The host executes all
 * asynchronous work (authoritative queries, submission orchestration, result refresh) and
 * dispatches result events into the pure [P503Reducer].
 *
 * P7-02.A: the editor is typed (EXPENSE/INCOME); the host chooses the per-type save input and
 * snapshot, injects the retained intent on the determinate-success refresh (E-2/G-C), and
 * routes the income commit-status check to the income resolver.
 */
@Composable
fun P503App(
    facade: P503LedgerFacade,
    onExit: () -> Unit,
    backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
) {
    val reducer = remember(facade) { P503ReducerImpl(facade.parseAmount, facade.currency, facade.ledgerClock) }
    val validation = remember(facade) { P503DraftValidation(facade.parseAmount, facade.parseOccurredAt, facade.ledgerClock) }
    // D-140-style authoritative options snapshot. Reading the version here makes the options
    // reload after a catalog refresh (spec 7.4), because the facade exposes the refreshed
    // session models; options/reads/summaries then all follow one authoritative catalog version.
    val catalogVersion = facade.catalogSnapshot()?.catalogVersion
    val options = remember(facade, catalogVersion) { facade.optionsProvider.queryOptions() }
    val incomeOptions = remember(facade, catalogVersion) { facade.incomeOptionsProvider.queryOptions() }
    val transferOptions = remember(facade, catalogVersion) { facade.transferOptionsProvider.queryOptions() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<P503AppState>(P503AppState.Ready) }
    val latestState = remember { mutableStateOf<P503AppState>(P503AppState.Ready) }
    // P5-04.3 single-flight marker for the unknown-commit status check (read-only resolve).
    var statusCheckInFlight by remember { mutableStateOf(false) }
    var editDialogOpen by remember { mutableStateOf(false) }
    // D-140 (spec 2.1): 键入文本宿主级提升；null = 未初始化，显示按 draft 派生。
    var hoistedOccurredAtText by remember { mutableStateOf<String?>(null) }
    // P7-02.A E-2 (G-C): the host-held memory of one determinate-success intent for "record
    // again". Captured before submission, consumed by the authoritative refresh; never persisted.
    var retainedIntent by remember { mutableStateOf<RetainedEntryIntent?>(null) }

    fun dispatch(event: P503UiEvent) {
        // D-140 (spec 2.2): 全新草稿流事件重置 hoisted 文本（枚举表：#1 唯一）。
        if (event is P503UiEvent.StartNewExpense) hoistedOccurredAtText = null
        // P7-02.A E-2 lifecycle: the retained intent is cleared once a new intent starts
        // (StartNewExpense / record again / cancel or abandon back into Editing), so a stale
        // intent can never be injected into an unrelated later refresh.
        if (
            event is P503UiEvent.StartNewExpense ||
            event is P503UiEvent.SaveAndRecordAgain ||
            event is P503UiEvent.Cancel ||
            event is P503UiEvent.AbandonConflict
        ) {
            retainedIntent = null
        }
        state = reducer.reduce(state, event).also { latestState.value = it }
    }

    // P5-04.2: system back only intercepts while the editor flow is on screen and carries the
    // overview snapshot needed to close back to the originating tab. Submitting swallows the
    // back to avoid exiting the process mid-submission; only non-Submitting states dispatch.
    // D-137: while a picker dialog is open, the back channel is additionally disabled, so
    // Esc / system back reaches only the dialog layer and the edit page stays open.
    val backEnabled = isBackEnabled(state)
    backHandler?.invoke(backEnabled && !editDialogOpen) {
        // P5-04.3 double-fire guard: re-check at dispatch time and only dispatch while the
        // state is still Back-legal, so a repeated back (fast double Esc / double system
        // back) is ignored instead of crashing on (OverviewEmpty, Back).
        if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back)
    }

    // The parse/display currency follows the selected primary account (spec section 4.1);
    // fall back to the facade currency only when no account is selected yet.
    fun resolvedCurrency(draft: TypedEntryDraft): CurrencyUnit =
        when (draft) {
            is IncomeDraft ->
                incomeOptions.receivingAccounts.firstOrNull { it.accountId == draft.receivingAccountId }?.currency ?: facade.currency
            is ExpenseDraft ->
                options.paymentAccounts.firstOrNull { it.accountId == draft.paymentAccountId }?.currency ?: facade.currency
            is TransferDraft ->
                transferOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.sourceAccountId }?.currency ?: facade.currency
        }

    fun refresh() {
        val intent = retainedIntent
        when (val result = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> {
                // P7-02.A E-2: the intent is consumed only by a successful authoritative refresh;
                // a failed read keeps it so the READ retry can still forward it (P3-3).
                retainedIntent = null
                dispatch(P503UiEvent.RefreshResult(result.state, intent))
            }
            else -> dispatch(P503UiEvent.RefreshFailed)
        }
    }

    // P5-04.3: shared input construction for the submission and the unknown-commit status
    // check so both build a field-identical per-type snapshot (the resolver compares field by
    // field). Returns null when the draft is incomplete.
    fun expenseSaveInput(
        draft: ExpenseDraft,
        requestId: RequestId,
    ): ManualExpenseSaveInput? {
        val currency = resolvedCurrency(draft)
        val parsed = facade.parseAmount.parse(draft.amountText, currency)
        val amount = (parsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val categoryId = draft.categoryId
        val paymentAccountId = draft.paymentAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || categoryId == null || paymentAccountId == null || occurredAt == null) {
            return null
        }
        return ManualExpenseSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            amount = amount,
            categoryId = categoryId,
            paymentAccountId = paymentAccountId,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun incomeSaveInput(
        draft: IncomeDraft,
        requestId: RequestId,
    ): ManualIncomeSaveInput? {
        val currency = resolvedCurrency(draft)
        val parsed = facade.parseAmount.parse(draft.amountText, currency)
        val amount = (parsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val categoryId = draft.categoryId
        val receivingAccountId = draft.receivingAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || categoryId == null || receivingAccountId == null || occurredAt == null) {
            return null
        }
        return ManualIncomeSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            amount = amount,
            categoryId = categoryId,
            receivingAccountId = receivingAccountId,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun transferSaveInput(
        draft: TransferDraft,
        requestId: RequestId,
    ): ManualTransferSaveInput? {
        val currency = resolvedCurrency(draft)
        val creditParsed = facade.parseAmount.parse(draft.destinationCredit, currency)
        val destinationCredit = (creditParsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val fee =
            when {
                draft.fee.isBlank() -> Money.ofMinor(0L, currency)
                else -> (facade.parseAmount.parse(draft.fee, currency) as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
            }
        val sourceAccountId = draft.sourceAccountId
        val destinationAccountId = draft.destinationAccountId
        val occurredAt = draft.occurredAt
        if (destinationCredit == null || fee == null || sourceAccountId == null || destinationAccountId == null || occurredAt == null) {
            return null
        }
        return ManualTransferSaveInput(
            ledgerId = facade.ledgerId,
            requestId = requestId,
            sourceAccountId = sourceAccountId,
            destinationAccountId = destinationAccountId,
            destinationCredit = destinationCredit,
            fee = fee,
            feeCategoryId = draft.feeCategoryId,
            occurredAt = occurredAt,
            note = draft.note,
            confirmation = ExplicitManualSave,
        )
    }

    fun submit(
        draft: TypedEntryDraft,
        requestId: RequestId,
    ) {
        // P7-02 E-2: capture the retained intent from the pre-submit draft before it leaves.
        retainedIntent = draft.toRetainedIntent(currentOriginTab(latestState.value))
        val result =
            when (draft) {
                is ExpenseDraft -> {
                    val input = expenseSaveInput(draft, requestId)
                    if (input == null) {
                        ManualEntrySubmissionResult.Expense(
                            ManualExpenseSubmissionResult.Application(
                                ManualExpenseSaveResult.InvalidInput(
                                    buildSet {
                                        add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT))
                                        add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY))
                                        add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT))
                                    },
                                ),
                            ),
                        )
                    } else {
                        facade.submitEntryOrExpense().submit(ManualEntrySaveInput.Expense(input))
                    }
                }
                is IncomeDraft -> {
                    val input = incomeSaveInput(draft, requestId)
                    val submission = facade.submitIncome
                    if (input == null || submission == null) {
                        ManualEntrySubmissionResult.Income(
                            ManualIncomeSubmissionResult.Application(
                                ManualIncomeSaveResult.InvalidInput(
                                    buildSet {
                                        add(com.unifiedledger.application.ManualIncomeInputField.AMOUNT)
                                        add(com.unifiedledger.application.ManualIncomeInputField.CATEGORY)
                                        add(com.unifiedledger.application.ManualIncomeInputField.RECEIVING_ACCOUNT)
                                    },
                                ),
                            ),
                        )
                    } else {
                        ManualEntrySubmissionResult.Income(submission.submit(input))
                    }
                }
                is TransferDraft -> {
                    val input = transferSaveInput(draft, requestId)
                    val submission = facade.submitEntry
                    if (input == null || submission == null) {
                        ManualEntrySubmissionResult.Transfer(
                            ManualTransferSubmissionResult.Application(
                                ManualTransferSaveResult.InvalidInput(
                                    buildSet {
                                        add(ManualTransferInputField.SOURCE_ACCOUNT)
                                        add(ManualTransferInputField.DESTINATION_ACCOUNT)
                                        add(ManualTransferInputField.DESTINATION_CREDIT)
                                    },
                                ),
                            ),
                        )
                    } else {
                        submission.submit(ManualEntrySaveInput.Transfer(input))
                    }
                }
            }
        dispatch(P503UiEvent.SubmissionResult(result))
    }

    // P5-04.3: one read-only commit-status check for the unknown-commit flow, per entry type.
    fun checkCommitStatus(
        draft: TypedEntryDraft,
        requestId: RequestId,
    ) {
        if (statusCheckInFlight) return
        when (draft) {
            is ExpenseDraft -> {
                val input = expenseSaveInput(draft, requestId) ?: return
                val attempted =
                    ManualExpenseRequestSnapshot(
                        ledgerId = input.ledgerId,
                        amount = checkNotNull(input.amount),
                        categoryId = checkNotNull(input.categoryId),
                        paymentAccountId = checkNotNull(input.paymentAccountId),
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution = facade.resolveCommitStatus.resolve(facade.ledgerId, requestId, attempted)
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Expense(resolution)))
                }
            }
            is IncomeDraft -> {
                val input = incomeSaveInput(draft, requestId) ?: return
                val resolver = facade.resolveIncomeCommitStatus ?: return
                val attempted =
                    ManualIncomeRequestSnapshot(
                        ledgerId = input.ledgerId,
                        amount = checkNotNull(input.amount),
                        categoryId = checkNotNull(input.categoryId),
                        receivingAccountId = checkNotNull(input.receivingAccountId),
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution = resolver.resolve(facade.ledgerId, requestId, attempted)
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Income(resolution)))
                }
            }
            is TransferDraft -> {
                val input = transferSaveInput(draft, requestId) ?: return
                val resolver = facade.resolveTransferCommitStatus ?: return
                val attempted =
                    ManualTransferRequestSnapshot(
                        ledgerId = input.ledgerId,
                        sourceAccountId = checkNotNull(input.sourceAccountId),
                        destinationAccountId = checkNotNull(input.destinationAccountId),
                        destinationCredit = checkNotNull(input.destinationCredit),
                        fee = checkNotNull(input.fee),
                        feeCategoryId = input.feeCategoryId,
                        occurredAt = input.occurredAt,
                        note = input.note,
                    )
                statusCheckInFlight = true
                scope.launch {
                    val resolution = resolver.resolve(facade.ledgerId, requestId, attempted)
                    statusCheckInFlight = false
                    dispatch(P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Transfer(resolution)))
                }
            }
        }
    }

    // Initial authoritative load (the facade already implies startup completed).
    LaunchedEffect(Unit) {
        when (val result = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> dispatch(P503UiEvent.InitialLoadResult(result.state))
            else -> dispatch(P503UiEvent.InitialLoadFailed)
        }
    }

    // P5-04.4: host-behavior decision skeleton (Created/NoChange/Recovered auto-refresh,
    // UnknownCommit auto-check, manual retry triggers) lives in a pure coordinator so it is
    // JVM-testable; the callbacks below are the actual IO/dispatch performed in this
    // composition root. The closures capture the stable state delegate and facade, so the
    // remembered coordinator stays current across recompositions.
    val coordinator =
        remember {
            P503HostCoordinator(
                onRefresh = ::refresh,
                onSubmit = { draft, requestId -> submit(draft, requestId) },
                onCheck = { draft, requestId -> checkCommitStatus(draft, requestId) },
            )
        }

    // Authoritative refresh after Created/NoChange/Recovered; never build the list from
    // the submission return value or accumulate balances in the UI.
    LaunchedEffect(state) {
        coordinator.decide(state)
    }

    // P7-01.D: run one catalog command, then refresh the shared session and read the fresh
    // authoritative snapshot. `refreshCatalog()` reloads the catalog so options/reads/summaries
    // continue from the same version without a restart; a typed conflict is surfaced as a
    // banner and never retried automatically.
    fun dispatchCatalogCommandResult(result: CatalogCommandResult) {
        if (shouldRefreshReadModelAfterCatalogCommand(result)) {
            facade.refreshCatalog()
            // R1 (spec 6.2/7.3, D-027): HOME's balances/transaction lines come from the read
            // model, which now reads through the refreshed session; re-query it via the existing
            // refresh channel so a rename/deactivate shows new names on HOME without a restart.
            refresh()
        }
        val fresh =
            facade.catalogSnapshot()
                ?: (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot
                ?: return
        // F1 (N-5): refresh() may have failed into InfrastructureFailure(READ), which has no
        // transition for management events; publish the outcome only while still on the overview.
        dispatchCatalogOutcomeIfOverview(latestState.value, P503UiEvent.CatalogCommandCompleted(result, fresh), ::dispatch)
    }

    fun runCatalogToggle(event: P503UiEvent) {
        val command = facade.executeCatalogCommand ?: return
        val version = (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot?.catalogVersion ?: return
        scope.launch {
            val result =
                when (event) {
                    is P503UiEvent.ManageAccountActive ->
                        command.setAccountActive(facade.ledgerId, event.accountId, event.active, version)
                    is P503UiEvent.ManageCategoryActive ->
                        command.setCategoryActive(facade.ledgerId, event.categoryId, event.active, version)
                    is P503UiEvent.EnableCategoryGroup ->
                        command.enableCategoryGroup(facade.ledgerId, event.parentId, version)
                    else -> return@launch
                }
            dispatchCatalogCommandResult(result)
        }
    }

    fun runCatalogForm(dialog: CatalogDialog) {
        val command = facade.executeCatalogCommand ?: return
        val version = (latestState.value as? P503AppState.OverviewEmpty)?.catalogSnapshot?.catalogVersion ?: return
        scope.launch {
            val result =
                when (dialog) {
                    is CatalogDialog.CreateAccount ->
                        command.createAccount(facade.ledgerId, dialog.nameText, dialog.kind, version)
                    is CatalogDialog.RenameAccount ->
                        command.renameAccount(facade.ledgerId, dialog.accountId, dialog.nameText, version)
                    is CatalogDialog.CreateCategoryGroup ->
                        command.createCategoryGroup(facade.ledgerId, dialog.kind, dialog.groupNameText, dialog.firstChildNameText, version)
                    is CatalogDialog.AppendCategoryChild ->
                        command.appendCategoryChild(facade.ledgerId, dialog.parentId, dialog.nameText, version)
                    is CatalogDialog.RenameCategory ->
                        command.renameCategory(facade.ledgerId, dialog.categoryId, dialog.nameText, version)
                    is CatalogDialog.ConfirmCategoryDelete ->
                        command.deleteCategory(facade.ledgerId, dialog.categoryId, version)
                    CatalogDialog.None -> return@launch
                }
            dispatchCatalogCommandResult(result)
        }
    }

    // Explicit refresh used by the management screen: reload the shared session and install the
    // reloaded projection (a no-op command success path). The read model is re-queried against
    // the reloaded session too, for the same HOME-freshness reason as the command success path.
    fun refreshCatalogSnapshot() {
        facade.refreshCatalog()
        refresh()
        val fresh = facade.catalogSnapshot() ?: return
        // F1 (N-5): same overview-only guard as the command success path.
        dispatchCatalogOutcomeIfOverview(latestState.value, P503UiEvent.CatalogSnapshotRefreshed(fresh), ::dispatch)
    }

    // P7-02.A E-2: the "record again" action availability and its host reset (hoisted
    // occurred-at text) are deferred to the efficiency batch; the reducer effect, the
    // retainedIntent payload and the injection channel are frozen and implemented here.

    // P7-02.A: the account/category labels depend on the draft type; both are resolved from
    // the authoritative options and fall back to the draft id values in the reducer.
    fun accountLabel(draft: TypedEntryDraft): String =
        when (draft) {
            is IncomeDraft -> incomeOptions.receivingAccounts.firstOrNull { it.accountId == draft.receivingAccountId }?.label ?: ""
            is ExpenseDraft -> options.paymentAccounts.firstOrNull { it.accountId == draft.paymentAccountId }?.label ?: ""
            is TransferDraft -> transferOptions.ownedAssetAccounts.firstOrNull { it.accountId == draft.sourceAccountId }?.label ?: ""
        }

    fun categoryLabel(draft: TypedEntryDraft): String =
        when (draft) {
            is IncomeDraft -> incomeOptions.incomeCategories.firstOrNull { it.categoryId == draft.categoryId }?.label ?: ""
            is ExpenseDraft -> options.expenseCategories.firstOrNull { it.categoryId == draft.categoryId }?.label ?: ""
            is TransferDraft -> transferOptions.feeCategories.firstOrNull { it.categoryId == draft.feeCategoryId }?.label ?: ""
        }

    P503Theme {
        when (val current = state) {
            P503AppState.Ready -> P503StartupScreen(P503StartupState.Starting, onRetry = {}, onExit = onExit)
            is P503AppState.OverviewEmpty ->
                P503TabShell(
                    selectedTab = current.selectedTab,
                    onSelectTab = { tab ->
                        if (tab == P503Tab.ACCOUNTS) {
                            dispatch(P503UiEvent.SelectTab(tab, facade.catalogSnapshot()))
                        } else {
                            dispatch(P503UiEvent.SelectTab(tab))
                        }
                    },
                    onStartNewExpense = { dispatch(P503UiEvent.StartNewExpense) },
                ) {
                    when (current.selectedTab) {
                        P503Tab.HOME -> P503OverviewScreen(current.state)
                        P503Tab.ACCOUNTS ->
                            P503CatalogManagementScreen(
                                state = current,
                                onEvent = { event ->
                                    when (event) {
                                        is P503UiEvent.ManageAccountActive,
                                        is P503UiEvent.ManageCategoryActive,
                                        is P503UiEvent.EnableCategoryGroup,
                                        -> runCatalogToggle(event)
                                        else -> dispatch(event)
                                    }
                                },
                                onSubmit = { dialog -> runCatalogForm(dialog) },
                                onRefresh = { refreshCatalogSnapshot() },
                            )
                        P503Tab.ANALYSIS -> P503AnalysisScreen(current.state, facade.summarizeActivity)
                    }
                }
            is P503AppState.Editing ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    incomeOptions = incomeOptions,
                    transferOptions = transferOptions,
                    onUpdateTransferSourceAccount = { dispatch(P503UiEvent.UpdateTransferSourceAccount(it)) },
                    onUpdateTransferDestinationAccount = { dispatch(P503UiEvent.UpdateTransferDestinationAccount(it)) },
                    onUpdateTransferDestinationCredit = { dispatch(P503UiEvent.UpdateTransferDestinationCredit(it)) },
                    onUpdateTransferFee = { dispatch(P503UiEvent.UpdateTransferFee(it)) },
                    onUpdateTransferFeeCategory = { dispatch(P503UiEvent.UpdateTransferFeeCategory(it)) },
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
                    ledgerClock = facade.ledgerClock,
                    parseOccurredAt = facade.parseOccurredAt,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                    onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
                    onUpdateReceivingAccount = { dispatch(P503UiEvent.UpdateReceivingAccount(it)) },
                    onUpdateIncomeCategory = { dispatch(P503UiEvent.UpdateIncomeCategory(it)) },
                    occurredAtText = hoistedOccurredAtText ?: (current.draft.occurredAt?.toString() ?: ""),
                    onOccurredAtTextChange = { hoistedOccurredAtText = it },
                    onContinue = {
                        dispatchCurrentP503Action(current, latestState.value, {
                            // requestId single rule (spec 7.4): allocate unconditionally when the
                            // draft has none, reuse it otherwise; never reallocate mid-intent.
                            val requestId = current.requestId ?: facade.requestIdSource.next()
                            P503UiEvent.Continue(
                                requestId,
                                // P5-04.3: display labels resolved from the options; absent
                                // options fall back to the draft id values in the reducer.
                                paymentAccountLabel = accountLabel(current.draft),
                                categoryLabel = categoryLabel(current.draft),
                            )
                        }, ::dispatch)
                    },
                    onClose =
                        if (current.overview != null) {
                            { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) }
                        } else {
                            null
                        },
                    onDialogVisibilityChanged = { editDialogOpen = it },
                )
            is P503AppState.AwaitingConfirmation ->
                P503ConfirmationScreen(
                    draft = current.draft,
                    currencyCode = resolvedCurrency(current.draft).code,
                    paymentAccountLabel = current.paymentAccountLabel,
                    categoryLabel = current.categoryLabel,
                    onCancel = { dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.Cancel }, ::dispatch) },
                    confirmEnabled = latestState.value === current,
                    cancelEnabled = latestState.value === current,
                    onConfirm = {
                        dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.Confirm }, ::dispatch) {
                            submit(current.draft, current.requestId)
                        }
                    },
                )
            is P503AppState.Submitting -> P503SubmittingScreen()
            P503AppState.Created,
            P503AppState.NoChange,
            P503AppState.Recovered,
            -> P503ResultScreen(current)
            is P503AppState.UnknownCommit ->
                P503UnknownCommitScreen(current) {
                    // P5-04.3: manual re-check mirrors the RetrySubmission pattern — dispatch
                    // the state-preserving event and run the read-only check directly.
                    dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.RetryCommitStatusCheck }, ::dispatch) {
                        coordinator.retryCommitStatusCheck(current)
                    }
                }
            is P503AppState.RequestIdentityConflict ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    incomeOptions = incomeOptions,
                    transferOptions = transferOptions,
                    onUpdateTransferSourceAccount = { dispatch(P503UiEvent.UpdateTransferSourceAccount(it)) },
                    onUpdateTransferDestinationAccount = { dispatch(P503UiEvent.UpdateTransferDestinationAccount(it)) },
                    onUpdateTransferDestinationCredit = { dispatch(P503UiEvent.UpdateTransferDestinationCredit(it)) },
                    onUpdateTransferFee = { dispatch(P503UiEvent.UpdateTransferFee(it)) },
                    onUpdateTransferFeeCategory = { dispatch(P503UiEvent.UpdateTransferFeeCategory(it)) },
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
                    ledgerClock = facade.ledgerClock,
                    parseOccurredAt = facade.parseOccurredAt,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                    onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
                    onUpdateReceivingAccount = { dispatch(P503UiEvent.UpdateReceivingAccount(it)) },
                    onUpdateIncomeCategory = { dispatch(P503UiEvent.UpdateIncomeCategory(it)) },
                    occurredAtText = hoistedOccurredAtText ?: (current.draft.occurredAt?.toString() ?: ""),
                    onOccurredAtTextChange = { hoistedOccurredAtText = it },
                    onContinue = null,
                    banner = {
                        P503ConflictBanner(
                            onAbandonConflict = { dispatch(P503UiEvent.AbandonConflict) },
                        )
                    },
                    onClose =
                        if (current.overview != null) {
                            { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) }
                        } else {
                            null
                        },
                    onDialogVisibilityChanged = { editDialogOpen = it },
                )
            is P503AppState.DomainRejected ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    incomeOptions = incomeOptions,
                    transferOptions = transferOptions,
                    onUpdateTransferSourceAccount = { dispatch(P503UiEvent.UpdateTransferSourceAccount(it)) },
                    onUpdateTransferDestinationAccount = { dispatch(P503UiEvent.UpdateTransferDestinationAccount(it)) },
                    onUpdateTransferDestinationCredit = { dispatch(P503UiEvent.UpdateTransferDestinationCredit(it)) },
                    onUpdateTransferFee = { dispatch(P503UiEvent.UpdateTransferFee(it)) },
                    onUpdateTransferFeeCategory = { dispatch(P503UiEvent.UpdateTransferFeeCategory(it)) },
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
                    ledgerClock = facade.ledgerClock,
                    parseOccurredAt = facade.parseOccurredAt,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onSelectEntryType = { dispatch(P503UiEvent.SelectEntryType(it)) },
                    onUpdateNote = { dispatch(P503UiEvent.UpdateNote(it)) },
                    onUpdateReceivingAccount = { dispatch(P503UiEvent.UpdateReceivingAccount(it)) },
                    onUpdateIncomeCategory = { dispatch(P503UiEvent.UpdateIncomeCategory(it)) },
                    occurredAtText = hoistedOccurredAtText ?: (current.draft.occurredAt?.toString() ?: ""),
                    onOccurredAtTextChange = { hoistedOccurredAtText = it },
                    onContinue = null,
                    banner = { P503RejectedBanner() },
                    onClose =
                        if (current.overview != null) {
                            { if (isBackDispatchSafe(latestState.value)) dispatch(P503UiEvent.Back) }
                        } else {
                            null
                        },
                    onDialogVisibilityChanged = { editDialogOpen = it },
                )
            is P503AppState.InfrastructureFailure ->
                when (current.context) {
                    InfrastructureFailureContext.READ ->
                        P503InfrastructureReadScreen(
                            onRetryRefresh = {
                                dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.RetryRefresh }, ::dispatch) {
                                    coordinator.retryRefresh(current)
                                }
                            },
                        )
                    InfrastructureFailureContext.SUBMISSION ->
                        P503InfrastructureSubmissionScreen(
                            onRetry = {
                                // Defensive (P5-04.4 I-001): verify draft/requestId before
                                // dispatching RetrySubmission, matching the baseline guard.
                                // The reducer's SUBMISSION branch uses checkNotNull, so the
                                // retry entry must not dispatch a degraded event.
                                if (current.draft == null || current.requestId == null) {
                                    return@P503InfrastructureSubmissionScreen
                                }
                                dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.RetrySubmission }, ::dispatch) {
                                    coordinator.retrySubmission(current)
                                }
                            },
                            onCancel = { dispatchCurrentP503Action(current, latestState.value, { P503UiEvent.Cancel }, ::dispatch) },
                        )
                }
        }
    }
}

/** P5-04.2: the editor flow intercepts system back only with an overview to close back to. */
private fun isBackEnabled(state: P503AppState): Boolean =
    when (state) {
        // P7-01.D: on the ACCOUNTS tab back closes an open catalog dialog, or leaves for HOME.
        is P503AppState.OverviewEmpty -> state.selectedTab == P503Tab.ACCOUNTS
        is P503AppState.Editing -> state.overview != null
        is P503AppState.AwaitingConfirmation -> state.overview != null
        is P503AppState.Submitting -> state.overview != null
        is P503AppState.RequestIdentityConflict -> state.overview != null
        is P503AppState.DomainRejected -> state.overview != null
        is P503AppState.InfrastructureFailure ->
            state.context == InfrastructureFailureContext.SUBMISSION && state.overview != null
        else -> false
    }

/**
 * P5-04.3: dispatching Back is only legal from the Back-able states; Submitting swallows the
 * back and every other state (including the HOME overview root) must not dispatch one.
 */
private fun isBackDispatchSafe(state: P503AppState): Boolean = isBackEnabled(state) && state !is P503AppState.Submitting

/** P7-02.A E-2: origin tab of the in-flight entry flow, for the retained intent. */
private fun currentOriginTab(state: P503AppState): P503Tab =
    when (state) {
        is P503AppState.Submitting -> state.originTab
        is P503AppState.AwaitingConfirmation -> state.originTab
        is P503AppState.Editing -> state.originTab
        is P503AppState.RequestIdentityConflict -> state.originTab
        is P503AppState.DomainRejected -> state.originTab
        is P503AppState.InfrastructureFailure -> state.originTab
        is P503AppState.UnknownCommit -> state.originTab
        else -> P503Tab.HOME
    }

/** P7-02.A E-2: captures the reusable fields of one determinate-success draft. */
private fun TypedEntryDraft.toRetainedIntent(originTab: P503Tab): RetainedEntryIntent =
    RetainedEntryIntent(
        type = entryType,
        amountText = amountText,
        paymentAccountId = primaryAccountId,
        categoryId = categoryId,
        note = note,
        occurredAt = occurredAt,
        originTab = originTab,
    )

/** P7-02.A: the typed submit entry point, falling back to the expense-only path for legacy roots. */
private fun P503LedgerFacade.submitEntryOrExpense(): com.unifiedledger.application.ExecuteManualEntrySubmission = submitEntry ?: throw IllegalStateException("facade is missing the typed entry submission")

@Composable
private fun P503InfrastructureReadScreen(onRetryRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "无法读取账本数据（本地数据库不可用）",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetryRefresh) {
            Text("重试")
        }
    }
}

@Composable
private fun P503InfrastructureSubmissionScreen(
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "提交失败（本地数据库不可用），可重试或返回修改。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text("返回修改")
            }
            Button(onClick = onRetry) {
                Text("重试提交")
            }
        }
    }
}
