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

    fun dispatch(event: P503UiEvent) {
        state = reducer.reduce(state, event)
    }

    // P5-04.2: system back only intercepts while the editor flow is on screen and carries the
    // overview snapshot needed to close back to the originating tab. Submitting swallows the
    // back to avoid exiting the process mid-submission; only non-Submitting states dispatch.
    val current = state
    val editFlowBackEnabled =
        when (current) {
            is P503AppState.Editing -> current.overview != null
            is P503AppState.AwaitingConfirmation -> current.overview != null
            is P503AppState.Submitting -> current.overview != null
            is P503AppState.RequestIdentityConflict -> current.overview != null
            is P503AppState.DomainRejected -> current.overview != null
            is P503AppState.InfrastructureFailure ->
                current.context == InfrastructureFailureContext.SUBMISSION && current.overview != null
            else -> false
        }
    backHandler?.invoke(editFlowBackEnabled) {
        if (state !is P503AppState.Submitting) dispatch(P503UiEvent.Back)
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

    fun submit(
        draft: ManualExpenseDraft,
        requestId: RequestId,
    ) {
        val currency = resolvedCurrency(draft)
        val parsed = facade.parseAmount.parse(draft.amountText, currency)
        val amount = (parsed as? ParseManualExpenseAmount.Result.Valid)?.let { Money.ofMinor(it.minorUnits, currency) }
        val categoryId = draft.categoryId
        val paymentAccountId = draft.paymentAccountId
        val occurredAt = draft.occurredAt
        if (amount == null || categoryId == null || paymentAccountId == null || occurredAt == null) {
            // Invariant: Submitting is reachable only from a validated AwaitingConfirmation,
            // so the draft is complete here. If the invariant is ever violated, fall back to
            // the defensive InvalidInput transition (spec 7.2) instead of silently stranding
            // Submitting.
            dispatch(
                P503UiEvent.SubmissionResult(
                    ManualExpenseSubmissionResult.Application(
                        ManualExpenseSaveResult.InvalidInput(
                            buildSet {
                                if (amount == null) {
                                    add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT))
                                }
                                if (categoryId == null) {
                                    add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.CATEGORY))
                                }
                                if (paymentAccountId == null) {
                                    add(ManualExpenseInputFailure.Missing(ManualExpenseInputField.PAYMENT_ACCOUNT))
                                }
                            },
                        ),
                    ),
                ),
            )
            return
        }
        val input =
            ManualExpenseSaveInput(
                ledgerId = facade.ledgerId,
                requestId = requestId,
                amount = amount,
                categoryId = categoryId,
                paymentAccountId = paymentAccountId,
                occurredAt = occurredAt,
                note = "",
                confirmation = ExplicitManualSave,
            )
        scope.launch {
            val submissionResult = facade.submitExpense.submit(input)
            dispatch(P503UiEvent.SubmissionResult(submissionResult))
        }
    }

    // Initial authoritative load (the facade already implies startup completed).
    LaunchedEffect(Unit) {
        when (val result = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> dispatch(P503UiEvent.InitialLoadResult(result.state))
            else -> dispatch(P503UiEvent.InitialLoadFailed)
        }
    }

    // Authoritative refresh after Created/NoChange/Recovered; never build the list from
    // the submission return value or accumulate balances in the UI.
    LaunchedEffect(state) {
        val current = state
        if (current is P503AppState.Created || current is P503AppState.NoChange || current is P503AppState.Recovered) {
            refresh()
        }
    }

    MaterialTheme {
        when (val current = state) {
            P503AppState.Starting -> P503StartupScreen(P503StartupState.Starting, onRetry = {}, onExit = onExit)
            P503AppState.Ready -> P503StartupScreen(P503StartupState.Starting, onRetry = {}, onExit = onExit)
            P503AppState.StartupError -> P503StartupScreen(P503StartupState.StartupError, onRetry = {}, onExit = onExit)
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
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onContinue = {
                        // requestId single rule (spec 7.4): allocate unconditionally when the
                        // draft has none, reuse it otherwise; never reallocate mid-intent.
                        val requestId = current.requestId ?: facade.requestIdSource.next()
                        dispatch(P503UiEvent.Continue(requestId))
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
            P503AppState.UnknownCommit,
            -> P503ResultScreen(current)
            is P503AppState.RequestIdentityConflict ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
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
                )
            is P503AppState.DomainRejected ->
                P503EditScreen(
                    draft = current.draft,
                    options = options,
                    validation = validation,
                    currency = resolvedCurrency(current.draft),
                    onUpdateAmount = { dispatch(P503UiEvent.UpdateAmount(it)) },
                    onUpdatePaymentAccount = { dispatch(P503UiEvent.UpdatePaymentAccount(it)) },
                    onUpdateCategory = { dispatch(P503UiEvent.UpdateCategory(it)) },
                    onUpdateOccurredAt = { dispatch(P503UiEvent.UpdateOccurredAt(it)) },
                    onContinue = null,
                    banner = { P503RejectedBanner() },
                )
            is P503AppState.InfrastructureFailure ->
                when (current.context) {
                    InfrastructureFailureContext.READ ->
                        P503InfrastructureReadScreen(
                            onRetryRefresh = {
                                dispatch(P503UiEvent.RetryRefresh)
                                refresh()
                            },
                        )
                    InfrastructureFailureContext.SUBMISSION ->
                        P503InfrastructureSubmissionScreen(
                            onRetry = {
                                val draft = current.draft ?: return@P503InfrastructureSubmissionScreen
                                val requestId = current.requestId ?: return@P503InfrastructureSubmissionScreen
                                dispatch(P503UiEvent.RetrySubmission)
                                submit(draft, requestId)
                            },
                            onCancel = { dispatch(P503UiEvent.Cancel) },
                        )
                }
        }
    }
}

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
