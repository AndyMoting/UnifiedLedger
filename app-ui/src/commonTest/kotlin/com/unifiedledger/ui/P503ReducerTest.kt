package com.unifiedledger.ui

import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualExpenseInputFailure
import com.unifiedledger.application.ManualExpenseInputField
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class P503ReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val categoryId = CategoryId("expense-category-breakfast")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId1 = RequestId("request-uuid-v7-1")
    private val requestId2 = RequestId("request-uuid-v7-2")
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())
    private val oneTransactionState =
        LedgerCurrentState(
            ledgerId = ledgerId,
            transactions =
                listOf(
                    CurrentVersionRow(
                        transactionId = TransactionId("tx-1"),
                        currentVersionId = TransactionVersionId("version-1"),
                        kind = TransactionKind.EXPENSE,
                        occurredAt = occurredAt,
                        postings =
                            listOf(
                                Posting(PostingId("posting-1"), paymentAccountId, Money.ofMinor(-3_580L, cny)),
                                Posting(PostingId("posting-2"), categoryPostingAccountId(), Money.ofMinor(3_580L, cny)),
                            ),
                    ),
                ),
            balances =
                listOf(
                    AccountCurrencyBalance(
                        accountId = paymentAccountId,
                        currency = cny,
                        ledgerSignedMinorUnits = -3_580L,
                        displayMinorUnits = -3_580L,
                    ),
                ),
        )

    private fun categoryPostingAccountId(): AccountId = AccountId("expense-account-local")

    private fun fullDraft() =
        ManualExpenseDraft(
            paymentAccountId = paymentAccountId,
            categoryId = categoryId,
            amountText = "35.80",
            occurredAt = occurredAt,
        )

    private fun reduceFrom(
        state: P503AppState,
        vararg events: P503UiEvent,
    ): P503AppState = events.fold(state) { current, event -> reducer.reduce(current, event) }

    @Test
    fun emptyToEditToAwaitingToCreatedThenAuthoritativeRefresh() {
        val state =
            reduceFrom(
                P503AppState.OverviewEmpty(emptyState),
                P503UiEvent.StartNewExpense,
                P503UiEvent.UpdatePaymentAccount(paymentAccountId),
                P503UiEvent.UpdateCategory(categoryId),
                P503UiEvent.UpdateAmount("35.80"),
                P503UiEvent.UpdateOccurredAt(occurredAt),
                P503UiEvent.Continue(requestId1),
            )
        val awaiting = assertIs<P503AppState.AwaitingConfirmation>(state)
        assertEquals(fullDraft(), awaiting.draft)
        assertEquals(requestId1, awaiting.requestId)

        val submitted =
            reduceFrom(
                awaiting,
                P503UiEvent.Confirm,
                P503UiEvent.SubmissionResult(submissionCreated()),
            )
        assertEquals(P503AppState.Created, submitted)

        val refreshed =
            reduceFrom(
                submitted,
                P503UiEvent.RefreshResult(oneTransactionState),
            )
        val overview = assertIs<P503AppState.OverviewEmpty>(refreshed)
        assertEquals(oneTransactionState, overview.state)
        assertEquals(1, overview.state.transactions.size)
    }

    @Test
    fun cancelFromAwaitingReturnsToEditingWithoutWritingAndDropsTheIntentId() {
        val state =
            reduceFrom(
                P503AppState.OverviewEmpty(emptyState),
                P503UiEvent.StartNewExpense,
                P503UiEvent.UpdatePaymentAccount(paymentAccountId),
                P503UiEvent.UpdateCategory(categoryId),
                P503UiEvent.UpdateAmount("35.80"),
                P503UiEvent.UpdateOccurredAt(occurredAt),
                P503UiEvent.Continue(requestId1),
                P503UiEvent.Cancel,
            )
        val editing = assertIs<P503AppState.Editing>(state)
        assertEquals(fullDraft(), editing.draft)
        assertNull(editing.requestId)
    }

    @Test
    fun fieldErrorRetainsInputAndTheAllocatedRequestId() {
        val state =
            reduceFrom(
                P503AppState.OverviewEmpty(emptyState),
                P503UiEvent.StartNewExpense,
                P503UiEvent.UpdateAmount("35.80"),
                P503UiEvent.Continue(requestId1),
            )
        val editing = assertIs<P503AppState.Editing>(state)
        assertEquals("35.80", editing.draft.amountText)
        assertNull(editing.draft.paymentAccountId)
        assertEquals(requestId1, editing.requestId)

        // Completing the fields on the same intent keeps the same requestId.
        val awaiting =
            reduceFrom(
                editing,
                P503UiEvent.UpdatePaymentAccount(paymentAccountId),
                P503UiEvent.UpdateCategory(categoryId),
                P503UiEvent.UpdateOccurredAt(occurredAt),
                P503UiEvent.Continue(requestId1),
            )
        assertEquals(requestId1, assertIs<P503AppState.AwaitingConfirmation>(awaiting).requestId)
    }

    @Test
    fun noChangeAppendsNoSecondRowAfterRefresh() {
        val state =
            reduceFrom(
                P503AppState.Submitting(fullDraft(), requestId1),
                P503UiEvent.SubmissionResult(
                    ManualExpenseSubmissionResult.Application(
                        ManualExpenseSaveResult.Executed(
                            ConfirmedManualExpenseResult.NoChange(
                                ConfirmedExpenseReceipt(ConfirmationId("confirmation-1"), TransactionId("tx-1")),
                            ),
                        ),
                    ),
                ),
            )
        assertEquals(P503AppState.NoChange, state)

        val overview =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(state, P503UiEvent.RefreshResult(oneTransactionState)),
            )
        assertEquals(1, overview.state.transactions.size)
    }

    @Test
    fun unknownCommitForbidsRetryRefreshAndNewRequestId() {
        val state =
            reduceFrom(
                P503AppState.Submitting(fullDraft(), requestId1),
                P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.UnknownCommit),
            )
        assertEquals(P503AppState.UnknownCommit, state)

        val afterEvents =
            reduceFrom(
                state,
                P503UiEvent.RetrySubmission,
                P503UiEvent.RetryRefresh,
                P503UiEvent.Continue(requestId2),
                P503UiEvent.StartNewExpense,
                P503UiEvent.RefreshResult(emptyState),
            )
        assertEquals(P503AppState.UnknownCommit, afterEvents)
    }

    @Test
    fun conflictKeepsRequestIdUntilExplicitAbandon() {
        val identity = ManualExpenseRequestIdentity(ledgerId, requestId1)
        val state =
            reduceFrom(
                P503AppState.Submitting(fullDraft(), requestId1),
                P503UiEvent.SubmissionResult(
                    ManualExpenseSubmissionResult.Application(
                        ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.RequestIdentityConflict(identity)),
                    ),
                ),
            )
        val conflict = assertIs<P503AppState.RequestIdentityConflict>(state)
        assertEquals(fullDraft(), conflict.draft)
        assertEquals(requestId1, conflict.requestId)

        // Editing the same intent keeps the requestId.
        val edited =
            reduceFrom(
                conflict,
                P503UiEvent.UpdateAmount("35.81"),
            )
        val editing = assertIs<P503AppState.Editing>(edited)
        assertEquals(requestId1, editing.requestId)
        assertEquals("35.81", editing.draft.amountText)

        // Explicit abandon starts a new save intent.
        val abandoned =
            reduceFrom(
                conflict,
                P503UiEvent.AbandonConflict,
            )
        assertNull(assertIs<P503AppState.Editing>(abandoned).requestId)
    }

    @Test
    fun rejectedRetainsInputAndRequestIdAndEditsReturnToEditing() {
        val state =
            reduceFrom(
                P503AppState.Submitting(fullDraft(), requestId1),
                P503UiEvent.SubmissionResult(
                    ManualExpenseSubmissionResult.Application(
                        ManualExpenseSaveResult.Executed(
                            ConfirmedManualExpenseResult.Rejected(OrdinaryExpenseViolation.AmountMustBePositive),
                        ),
                    ),
                ),
            )
        val rejected = assertIs<P503AppState.DomainRejected>(state)
        assertEquals(fullDraft(), rejected.draft)
        assertEquals(requestId1, rejected.requestId)

        val edited = reduceFrom(rejected, P503UiEvent.UpdateAmount("35.81"))
        val editing = assertIs<P503AppState.Editing>(edited)
        assertEquals(requestId1, editing.requestId)
        assertEquals("35.81", editing.draft.amountText)
    }

    @Test
    fun startupErrorSupportsRetryAndExit() {
        assertEquals(P503AppState.StartupError, reducer.reduce(P503AppState.Starting, P503UiEvent.StartupFailed))
        assertEquals(P503AppState.Starting, reducer.reduce(P503AppState.StartupError, P503UiEvent.StartRetry))
        assertEquals(P503AppState.StartupError, reducer.reduce(P503AppState.StartupError, P503UiEvent.Exit))
    }

    @Test
    fun startupCompletedThenInitialLoadReachesOverview() {
        val ready = reducer.reduce(P503AppState.Starting, P503UiEvent.StartupCompleted)
        assertEquals(P503AppState.Ready, ready)

        val overview =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(ready, P503UiEvent.InitialLoadResult(emptyState)),
            )
        assertEquals(emptyState, overview.state)
    }

    @Test
    fun initialLoadFailureMapsToReadInfrastructureFailureAndRetries() {
        val failed = reducer.reduce(P503AppState.Ready, P503UiEvent.InitialLoadFailed)
        val readFailure = assertIs<P503AppState.InfrastructureFailure>(failed)
        assertEquals(InfrastructureFailureContext.READ, readFailure.context)
        assertNull(readFailure.draft)
        assertNull(readFailure.requestId)

        val stillFailed = reducer.reduce(readFailure, P503UiEvent.RetryRefresh)
        assertEquals(readFailure, stillFailed)

        val refreshed = reducer.reduce(readFailure, P503UiEvent.RefreshResult(emptyState))
        assertEquals(P503AppState.OverviewEmpty(emptyState), refreshed)
    }

    @Test
    fun submissionInfrastructureFailureRetriesSameIntentAndCancelsBackToEditing() {
        val failed =
            reduceFrom(
                P503AppState.Submitting(fullDraft(), requestId1),
                P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.InfrastructureFailure),
            )
        val submissionFailure = assertIs<P503AppState.InfrastructureFailure>(failed)
        assertEquals(InfrastructureFailureContext.SUBMISSION, submissionFailure.context)
        assertEquals(fullDraft(), submissionFailure.draft)
        assertEquals(requestId1, submissionFailure.requestId)

        val retried = reduceFrom(submissionFailure, P503UiEvent.RetrySubmission)
        assertEquals(P503AppState.Submitting(fullDraft(), requestId1), retried)

        val cancelled = reduceFrom(submissionFailure, P503UiEvent.Cancel)
        assertEquals(P503AppState.Editing(fullDraft(), requestId1), cancelled)
    }

    @Test
    fun invalidInputFallbackReturnsToEditingRetainingInputAndRequestId() {
        val state =
            reduceFrom(
                P503AppState.Submitting(fullDraft(), requestId1),
                P503UiEvent.SubmissionResult(
                    ManualExpenseSubmissionResult.Application(
                        ManualExpenseSaveResult.InvalidInput(
                            setOf(ManualExpenseInputFailure.Missing(ManualExpenseInputField.AMOUNT)),
                        ),
                    ),
                ),
            )
        val editing = assertIs<P503AppState.Editing>(state)
        assertEquals(fullDraft(), editing.draft)
        assertEquals(requestId1, editing.requestId)
    }

    @Test
    fun recoveredRefreshesToOverview() {
        val state =
            reduceFrom(
                P503AppState.Submitting(fullDraft(), requestId1),
                P503UiEvent.SubmissionResult(
                    ManualExpenseSubmissionResult.Recovered(
                        ConfirmedExpenseReceipt(ConfirmationId("confirmation-1"), TransactionId("tx-1")),
                    ),
                ),
            )
        assertEquals(P503AppState.Recovered, state)

        val overview =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(state, P503UiEvent.RefreshResult(oneTransactionState)),
            )
        assertEquals(1, overview.state.transactions.size)
    }

    @Test
    fun createdRefreshFailureMapsToReadInfrastructureFailure() {
        val failed =
            reduceFrom(
                P503AppState.Created,
                P503UiEvent.RefreshFailed,
            )
        val readFailure = assertIs<P503AppState.InfrastructureFailure>(failed)
        assertEquals(InfrastructureFailureContext.READ, readFailure.context)
    }

    @Test
    fun initialLoadResultEntersOverviewOnTheHomeTab() {
        val overview =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(P503AppState.Ready, P503UiEvent.InitialLoadResult(oneTransactionState)),
            )
        assertEquals(oneTransactionState, overview.state)
        assertEquals(P503Tab.HOME, overview.selectedTab)
    }

    @Test
    fun selectTabSwitchesWithinOverviewAndKeepsTheAuthoritativeState() {
        val accounts =
            reduceFrom(
                P503AppState.OverviewEmpty(oneTransactionState),
                P503UiEvent.SelectTab(P503Tab.ACCOUNTS),
            )
        val accountsOverview = assertIs<P503AppState.OverviewEmpty>(accounts)
        assertEquals(P503Tab.ACCOUNTS, accountsOverview.selectedTab)
        assertEquals(oneTransactionState, accountsOverview.state)

        val analysis = reduceFrom(accounts, P503UiEvent.SelectTab(P503Tab.ANALYSIS))
        assertEquals(P503Tab.ANALYSIS, assertIs<P503AppState.OverviewEmpty>(analysis).selectedTab)

        val home = reduceFrom(analysis, P503UiEvent.SelectTab(P503Tab.HOME))
        val homeOverview = assertIs<P503AppState.OverviewEmpty>(home)
        assertEquals(P503Tab.HOME, homeOverview.selectedTab)
        assertEquals(oneTransactionState, homeOverview.state)
    }

    @Test
    fun authoritativeRefreshAlwaysReturnsToTheHomeTab() {
        val overview =
            assertIs<P503AppState.OverviewEmpty>(
                reduceFrom(P503AppState.Created, P503UiEvent.RefreshResult(oneTransactionState)),
            )
        assertEquals(oneTransactionState, overview.state)
        assertEquals(P503Tab.HOME, overview.selectedTab)
    }

    @Test
    fun selectTabInsideEditingIsAProgrammingError() {
        val editing = P503AppState.Editing(fullDraft(), requestId1)
        assertFailsWith<IllegalStateException> {
            reducer.reduce(editing, P503UiEvent.SelectTab(P503Tab.HOME))
        }
    }

    @Test
    fun unknownCommitAbsorbsSelectTab() {
        val state =
            reduceFrom(
                P503AppState.UnknownCommit,
                P503UiEvent.SelectTab(P503Tab.ANALYSIS),
            )
        assertEquals(P503AppState.UnknownCommit, state)
    }

    private fun submissionCreated(): ManualExpenseSubmissionResult =
        ManualExpenseSubmissionResult.Application(
            ManualExpenseSaveResult.Executed(
                ConfirmedManualExpenseResult.Created(
                    ConfirmedExpenseReceipt(ConfirmationId("confirmation-1"), TransactionId("tx-1")),
                ),
            ),
        )
}
