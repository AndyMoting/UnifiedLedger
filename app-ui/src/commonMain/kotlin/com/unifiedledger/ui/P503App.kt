package com.unifiedledger.ui

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
import androidx.compose.material3.Text
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
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.ManualExpenseInputFailure
import com.unifiedledger.application.ManualExpenseInputField
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import kotlinx.coroutines.launch

/**
 * P5-03 shared demo surface entry (spec sections 4.7/7/8). The composition root builds the
 * [P503LedgerFacade] and calls this composable once startup is ready. The host executes all
 * asynchronous work (authoritative queries, submission orchestration, result refresh) and
 * dispatches result events into the pure [P503Reducer].
 */
@Composable
fun P503App(
    facade: P503LedgerFacade,
    onExit: () -> Unit,
    backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
) {
    val reducer = remember(facade) { P503ReducerImpl(facade.parseAmount, facade.currency) }
    val validation = remember(facade) { P503DraftValidation(facade.parseAmount) }
    val options = remember(facade) { facade.optionsProvider.queryOptions() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<P503AppState>(P503AppState.Ready) }
    // P5-04.3 single-flight marker for the unknown-commit status check (read-only resolve).
    var statusCheckInFlight by remember { mutableStateOf(false) }

    fun dispatch(event: P503UiEvent) {
        state = reducer.reduce(state, event)
    }

    // P5-04.2: system back only intercepts while the editor flow is on screen and carries the
    // overview snapshot needed to close back to the originating tab. Submitting swallows the
    // back to avoid exiting the process mid-submission; only non-Submitting states dispatch.
    val editFlowBackEnabled = isEditFlowBackEnabled(state)
    backHandler?.invoke(editFlowBackEnabled) {
        // P5-04.3 double-fire guard: re-check at dispatch time and only dispatch while the
        // state is still Back-legal, so a repeated back (fast double Esc / double system
        // back) is ignored instead of crashing on (OverviewEmpty, Back).
        if (isBackDispatchSafe(state)) dispatch(P503UiEvent.Back)
    }

    // The parse/display currency follows the selected payment account (spec section 4.1);
    // fall back to the facade currency only when no account is selected yet.
    fun resolvedCurrency(draft: ManualExpenseDraft): CurrencyUnit = options.paymentAccounts.firstOrNull { it.accountId == draft.paymentAccountId }?.currency ?: facade.currency

    fun refresh() {
        when (val result = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> dispatch(P503UiEvent.RefreshResult(result.state))
            else -> dispatch(P503UiEvent.RefreshFailed)
        }
    }

    // P5-04.3: shared input construction for the submission and the unknown-commit status
    // check so both build a field-identical snapshot (the resolver compares field by field).
    fun manualExpenseSaveInput(
        draft: ManualExpenseDraft,
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
            note = "",
            confirmation = ExplicitManualSave,
        )
    }

    fun submit(
        draft: ManualExpenseDraft,
        requestId: RequestId,
    ) {
        val input = manualExpenseSaveInput(draft, requestId)
        if (input == null) {
            // Invariant: Submitting is reachable only from a validated AwaitingConfirmation,
            // so the draft is complete here. If the invariant is ever violated, fall back to
            // the defensive InvalidInput transition (spec 7.2) instead of silently stranding
            // Submitting (P5-04.3 keeps this branch); the defensive payload reports every
            // field as missing.
            dispatch(
                P503UiEvent.SubmissionResult(
                    ManualExpenseSubmissionResult.Application(
                        ManualExpenseSaveResult.InvalidInput(
                            buildSet {
                                add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT))
                                add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY))
                                add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT))
                            },
                        ),
                    ),
                ),
            )
            return
        }
        scope.launch {
            val submissionResult = facade.submitExpense.submit(input)
            dispatch(P503UiEvent.SubmissionResult(submissionResult))
        }
    }

    // P5-04.3: one read-only commit-status check for the unknown-commit flow. The silent
    // return below is purely defensive and unreachable once the caller guard (draft and
    // requestId non-null) has passed: UnknownCommit is only reached from a validated
    // AwaitingConfirmation, so the draft is complete and the shared construction succeeds.
    fun checkCommitStatus(
        draft: ManualExpenseDraft,
        requestId: RequestId,
    ) {
        if (statusCheckInFlight) return
        val input = manualExpenseSaveInput(draft, requestId) ?: return
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
            dispatch(P503UiEvent.CommitStatusResolved(resolution))
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

    MaterialTheme {
        when (val current = state) {
            P503AppState.Ready -> P503StartupScreen(P503StartupState.Starting, onRetry = {}, onExit = onExit)
            is P503AppState.OverviewEmpty ->
                P503TabShell(
                    selectedTab = current.selectedTab,
                    onSelectTab = { dispatch(P503UiEvent.SelectTab(it)) },
                    onStartNewExpense = { dispatch(P503UiEvent.StartNewExpense) },
                ) {
                    when (current.selectedTab) {
                        P503Tab.HOME -> P503OverviewScreen(current.state)
                        P503Tab.ACCOUNTS -> P503AccountsScreen(current.state, facade.catalog)
                        P503Tab.ANALYSIS -> P503AnalysisScreen(current.state, facade.summarizeActivity)
                    }
                }
            is P503AppState.Editing ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
                    ledgerClock = facade.ledgerClock,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onContinue = {
                        // requestId single rule (spec 7.4): allocate unconditionally when the
                        // draft has none, reuse it otherwise; never reallocate mid-intent.
                        val requestId = current.requestId ?: facade.requestIdSource.next()
                        dispatch(
                            P503UiEvent.Continue(
                                requestId,
                                // P5-04.3: display labels resolved from the options; absent
                                // options fall back to the draft id values in the reducer.
                                paymentAccountLabel =
                                    options.paymentAccounts.firstOrNull { it.accountId == current.draft.paymentAccountId }?.label,
                                categoryLabel =
                                    options.expenseCategories.firstOrNull { it.categoryId == current.draft.categoryId }?.label,
                            ),
                        )
                    },
                    onClose =
                        if (current.overview != null) {
                            { if (isBackDispatchSafe(state)) dispatch(P503UiEvent.Back) }
                        } else {
                            null
                        },
                )
            is P503AppState.AwaitingConfirmation ->
                P503ConfirmationScreen(
                    draft = current.draft,
                    currencyCode =
                        options.paymentAccounts
                            .firstOrNull { it.accountId == current.draft.paymentAccountId }
                            ?.currency
                            ?.code
                            ?: facade.currency.code,
                    paymentAccountLabel = current.paymentAccountLabel,
                    categoryLabel = current.categoryLabel,
                    onCancel = { dispatch(P503UiEvent.Cancel) },
                    onConfirm = {
                        dispatch(P503UiEvent.Confirm)
                        submit(current.draft, current.requestId)
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
                    dispatch(P503UiEvent.RetryCommitStatusCheck)
                    coordinator.retryCommitStatusCheck(current)
                }
            is P503AppState.RequestIdentityConflict ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
                    ledgerClock = facade.ledgerClock,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onContinue = null,
                    banner = {
                        P503ConflictBanner(
                            onAbandonConflict = { dispatch(P503UiEvent.AbandonConflict) },
                        )
                    },
                    onClose =
                        if (current.overview != null) {
                            { if (isBackDispatchSafe(state)) dispatch(P503UiEvent.Back) }
                        } else {
                            null
                        },
                )
            is P503AppState.DomainRejected ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
                    ledgerClock = facade.ledgerClock,
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onContinue = null,
                    banner = { P503RejectedBanner() },
                    onClose =
                        if (current.overview != null) {
                            { if (isBackDispatchSafe(state)) dispatch(P503UiEvent.Back) }
                        } else {
                            null
                        },
                )
            is P503AppState.InfrastructureFailure ->
                when (current.context) {
                    InfrastructureFailureContext.READ ->
                        P503InfrastructureReadScreen(
                            onRetryRefresh = {
                                dispatch(P503UiEvent.RetryRefresh)
                                coordinator.retryRefresh(current)
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
                                dispatch(P503UiEvent.RetrySubmission)
                                coordinator.retrySubmission(current)
                            },
                            onCancel = { dispatch(P503UiEvent.Cancel) },
                        )
                }
        }
    }
}

/** P5-04.2: the editor flow intercepts system back only with an overview to close back to. */
private fun isEditFlowBackEnabled(state: P503AppState): Boolean =
    when (state) {
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
 * P5-04.3: dispatching Back is only legal from the Back-able editor-flow states; Submitting
 * swallows the back and every other state must not dispatch one.
 */
private fun isBackDispatchSafe(state: P503AppState): Boolean = isEditFlowBackEnabled(state) && state !is P503AppState.Submitting

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
