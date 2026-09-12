package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.EntryType
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.IncomeDraft
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualEntryCommitResolution
import com.unifiedledger.application.ManualEntrySubmissionResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ManualIncomeCommitResolution
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.ManualIncomeSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TypedEntryDraft
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P7-02.A section 6.2a/6.2b: the new typed-entry events have frozen effects and are absorbed
 * everywhere else (never an ISE), while the locked pre-P7-02 ISE behavior is preserved.
 */
class P503TypedEntryReducerTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val expenseCategoryId = CategoryId("expense-category-breakfast")
    private val incomeAccountId = AccountId("asset-payment-local")
    private val incomeCategoryId = CategoryId("income-category-monthly")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId = RequestId("request-typed-1")
    private val emptyState = LedgerCurrentStateForTests.empty(LedgerId("ledger-local-test"))
    private val ledgerClock = LedgerClock { Instant.parse("2026-09-11T00:00:00Z") }
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny, ledgerClock)

    private fun expenseDraft() = ExpenseDraft(paymentAccountId, expenseCategoryId, "35.80", occurredAt, note = "lunch")

    private fun incomeDraft() = IncomeDraft(incomeAccountId, incomeCategoryId, "300.00", occurredAt, note = "salary")

    private val newEvents =
        listOf<P503UiEvent>(
            P503UiEvent.SelectEntryType(EntryType.INCOME),
            P503UiEvent.UpdateNote("note"),
            P503UiEvent.UpdateReceivingAccount(incomeAccountId),
            P503UiEvent.UpdateIncomeCategory(incomeCategoryId),
        )

    @Test
    fun selectingIncomeRewritesTheDraftWithRetention() {
        val editing = P503AppState.Editing(expenseDraft(), requestId, emptyState, P503Tab.HOME)
        val switched = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.SelectEntryType(EntryType.INCOME)))
        val income = assertIs<IncomeDraft>(switched.draft)
        assertEquals("35.80", income.amountText)
        assertEquals(occurredAt, income.occurredAt)
        assertEquals("lunch", income.note)
        assertNull(income.receivingAccountId)
        assertNull(income.categoryId)
        assertEquals(requestId, switched.requestId)
        assertEquals(emptyState, switched.overview)
        assertEquals(P503Tab.HOME, switched.originTab)
    }

    @Test
    fun unimplementedTypeSwitchIsANoOpOnTheDraft() {
        val editing = P503AppState.Editing(expenseDraft(), requestId)
        val after = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.SelectEntryType(EntryType.LEND)))
        assertEquals(expenseDraft(), after.draft)
        assertEquals(EntryType.EXPENSE, after.draft.entryType)
    }

    @Test
    fun noteAndIncomeFieldWritesEditTheDraft() {
        val editing = P503AppState.Editing(incomeDraft(), requestId)
        val noted = assertIs<P503AppState.Editing>(reducer.reduce(editing, P503UiEvent.UpdateNote("updated")))
        assertEquals("updated", noted.draft.note)
        val reAccounted = assertIs<P503AppState.Editing>(reducer.reduce(noted, P503UiEvent.UpdateReceivingAccount(AccountId("other"))))
        assertEquals(AccountId("other"), reAccounted.draft.primaryAccountId)
        val reCategorized = assertIs<P503AppState.Editing>(reducer.reduce(reAccounted, P503UiEvent.UpdateIncomeCategory(CategoryId("other-cat"))))
        assertEquals(CategoryId("other-cat"), reCategorized.draft.categoryId)
    }

    @Test
    fun newEventsAreAbsorbedInEveryNonEditingState() {
        val states =
            listOf(
                P503AppState.Ready,
                P503AppState.OverviewEmpty(emptyState),
                P503AppState.AwaitingConfirmation(expenseDraft(), requestId),
                P503AppState.Submitting(expenseDraft(), requestId),
                P503AppState.Created,
                P503AppState.NoChange,
                P503AppState.Recovered,
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, expenseDraft(), requestId),
                P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
                P503AppState.UnknownCommit(expenseDraft(), requestId),
            )
        for (state in states) {
            for (event in newEvents + P503UiEvent.SaveAndRecordAgain) {
                assertEquals(state, reducer.reduce(state, event), "absorbed $event in $state")
            }
        }
    }

    @Test
    fun newEventsNeverThrowAndEffectOnlyOnConflictRejectionScreens() {
        // §6.2a: UpdateNote/Update* have an effect (typing retention) on the conflict and
        // rejection screens; all other new-event/state pairs are absorbed. None throws an ISE.
        val conflict = P503AppState.RequestIdentityConflict(expenseDraft(), requestId, emptyState, P503Tab.HOME)
        val rejected = P503AppState.DomainRejected(incomeDraft(), requestId, emptyState, P503Tab.HOME)
        val cases = listOf<Pair<P503AppState, TypedEntryDraft>>(conflict to expenseDraft(), rejected to incomeDraft())
        for ((state, stateDraft) in cases) {
            for (event in newEvents) {
                val result = reducer.reduce(state, event)
                when (event) {
                    is P503UiEvent.SelectEntryType -> assertEquals(state, result, "absorbed $event")
                    else -> {
                        val edited = assertIs<P503AppState.Editing>(result, "effect $event")
                        assertEquals(stateDraft.entryType, edited.draft.entryType, "type preserved for $event")
                    }
                }
            }
        }
        // SaveAndRecordAgain is absorbed on both screens.
        assertEquals(conflict, reducer.reduce(conflict, P503UiEvent.SaveAndRecordAgain))
        assertEquals(rejected, reducer.reduce(rejected, P503UiEvent.SaveAndRecordAgain))
    }

    @Test
    fun noteAndIncomeFieldEventsReturnToEditingFromConflictAndRejectionWithTypingRetention() {
        val identity = ManualExpenseRequestIdentity(LedgerId("ledger-local-test"), requestId)
        val conflict =
            assertIs<P503AppState.RequestIdentityConflict>(
                reducer.reduce(
                    P503AppState.Submitting(expenseDraft(), requestId),
                    P503UiEvent.SubmissionResult(
                        ManualEntrySubmissionResult.Expense(
                            ManualExpenseSubmissionResult.Application(
                                ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.RequestIdentityConflict(identity)),
                            ),
                        ),
                    ),
                ),
            )
        val edited = assertIs<P503AppState.Editing>(reducer.reduce(conflict, P503UiEvent.UpdateNote("edited")))
        assertEquals("edited", edited.draft.note)
        assertEquals(requestId, edited.requestId)
        assertEquals(EntryType.EXPENSE, edited.draft.entryType)

        val rejected =
            P503AppState.DomainRejected(incomeDraft(), requestId, emptyState, P503Tab.ANALYSIS)
        val incomeEdited = assertIs<P503AppState.Editing>(reducer.reduce(rejected, P503UiEvent.UpdateNote("edited-income")))
        assertEquals("edited-income", incomeEdited.draft.note)
        assertEquals(EntryType.INCOME, incomeEdited.draft.entryType)
        assertEquals(P503Tab.ANALYSIS, incomeEdited.originTab)
    }

    @Test
    fun draftTypeIsPreservedAcrossConfirmCancelAndBack() {
        val income = incomeDraft()
        val awaiting = assertIs<P503AppState.AwaitingConfirmation>(reducer.reduce(P503AppState.Editing(income, requestId), P503UiEvent.Continue(requestId)))
        assertIs<IncomeDraft>(awaiting.draft)
        val submitting = assertIs<P503AppState.Submitting>(reducer.reduce(awaiting, P503UiEvent.Confirm))
        assertIs<IncomeDraft>(submitting.draft)
        val cancelled = assertIs<P503AppState.Editing>(reducer.reduce(awaiting, P503UiEvent.Cancel))
        assertIs<IncomeDraft>(cancelled.draft)
        val backClosed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(awaiting.copy(overview = emptyState), P503UiEvent.Back))
        assertEquals(emptyState, backClosed.state)
    }

    @Test
    fun incomeSubmissionCreatedMapsToCreated() {
        val submitting = P503AppState.Submitting(incomeDraft(), requestId)
        val result =
            reducer.reduce(
                submitting,
                P503UiEvent.SubmissionResult(
                    ManualEntrySubmissionResult.Income(
                        ManualIncomeSubmissionResult.Application(
                            ManualIncomeSaveResult.Executed(ConfirmedManualIncomeResult.Created(com.unifiedledger.application.ConfirmedIncomeReceipt(ConfirmationId("c"), TransactionId("t")))),
                        ),
                    ),
                ),
            )
        assertEquals(P503AppState.Created, result)
    }

    @Test
    fun incomeResolutionDrivesUnknownCommitOutcomes() {
        val unknown = P503AppState.UnknownCommit(incomeDraft(), requestId, emptyState, P503Tab.HOME)
        val recovered =
            reducer.reduce(
                unknown,
                P503UiEvent.CommitStatusResolved(
                    ManualEntryCommitResolution.Income(
                        ManualIncomeCommitResolution.MatchingReceipt(com.unifiedledger.application.ConfirmedIncomeReceipt(ConfirmationId("c"), TransactionId("t"))),
                    ),
                ),
            )
        assertEquals(P503AppState.Recovered, recovered)

        val stay =
            reducer.reduce(
                unknown,
                P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Income(ManualIncomeCommitResolution.Absent)),
            )
        assertEquals(UnknownCommitCheckOutcome.ABSENT, assertIs<P503AppState.UnknownCommit>(stay).lastCheckOutcome)
        assertIs<IncomeDraft>(assertIs<P503AppState.UnknownCommit>(stay).draft)
    }

    @Test
    fun lockedExpenseIsesRemain() {
        // P503ReducerTest locks these; re-assert here that the typed change did not open them.
        assertFailsWith<IllegalStateException> {
            reducer.reduce(P503AppState.Editing(expenseDraft(), requestId), P503UiEvent.SelectTab(P503Tab.HOME))
        }
        assertFailsWith<IllegalStateException> {
            reducer.reduce(P503AppState.Submitting(expenseDraft(), requestId), P503UiEvent.Back)
        }
        assertFailsWith<IllegalStateException> {
            reducer.reduce(P503AppState.Editing(expenseDraft(), requestId), P503UiEvent.Cancel)
        }
        assertFailsWith<IllegalStateException> {
            reducer.reduce(
                P503AppState.Recovered,
                P503UiEvent.CommitStatusResolved(ManualEntryCommitResolution.Expense(com.unifiedledger.application.ManualExpenseCommitResolution.Absent)),
            )
        }
    }

    @Test
    fun retainedIntentFlowsIntoOverviewOnlyViaDeterminateSuccessRefresh() {
        val intent = RetainedEntryIntent(EntryType.EXPENSE, "35.80", paymentAccountId, expenseCategoryId, "lunch", occurredAt, P503Tab.ACCOUNTS)
        val overview =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    P503AppState.Created,
                    P503UiEvent.RefreshResult(emptyState, intent),
                ),
            )
        assertEquals(intent, overview.retainedIntent)
        assertEquals(P503Tab.HOME, overview.selectedTab)

        // An ordinary overview refresh preserves the existing intent.
        val preserved = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.RefreshResult(emptyState)))
        assertEquals(intent, preserved.retainedIntent)
    }

    @Test
    fun recordAgainStartsAFreshEditingWithNewClockInstantAndNoConfirmation() {
        val intent = RetainedEntryIntent(EntryType.EXPENSE, "35.80", paymentAccountId, expenseCategoryId, "lunch", occurredAt, P503Tab.ANALYSIS)
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, retainedIntent = intent)
        val editing = assertIs<P503AppState.Editing>(reducer.reduce(overview, P503UiEvent.SaveAndRecordAgain))
        val draft = assertIs<ExpenseDraft>(editing.draft)
        assertEquals("", draft.amountText)
        assertEquals("", draft.note)
        assertEquals(ledgerClock.now(), draft.occurredAt)
        assertEquals(paymentAccountId, draft.paymentAccountId)
        assertEquals(expenseCategoryId, draft.categoryId)
        assertNull(editing.requestId)
        assertEquals(P503Tab.ANALYSIS, editing.originTab)

        // Without a retained intent the event is absorbed.
        val plain = P503AppState.OverviewEmpty(emptyState)
        assertEquals(plain, reducer.reduce(plain, P503UiEvent.SaveAndRecordAgain))
    }

    @Test
    fun readFailureRefreshForwardsRetainedIntent() {
        val intent = RetainedEntryIntent(EntryType.INCOME, "300.00", incomeAccountId, incomeCategoryId, "salary", occurredAt, P503Tab.HOME)
        val readFailure = P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)
        val overview = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(readFailure, P503UiEvent.RefreshResult(emptyState, intent)))
        assertEquals(intent, overview.retainedIntent)
    }

    @Test
    fun domainRejectedViolationStillMapsFromEitherType() {
        val rejected =
            reducer.reduce(
                P503AppState.Submitting(expenseDraft(), requestId),
                P503UiEvent.SubmissionResult(
                    ManualEntrySubmissionResult.Expense(
                        ManualExpenseSubmissionResult.Application(
                            ManualExpenseSaveResult.Executed(ConfirmedManualExpenseResult.Rejected(OrdinaryExpenseViolation.AmountMustBePositive)),
                        ),
                    ),
                ),
            )
        assertIs<P503AppState.DomainRejected>(rejected)
    }
}

/** Shared empty authoritative state for the typed-entry reducer tests. */
internal object LedgerCurrentStateForTests {
    fun empty(ledgerId: LedgerId) = com.unifiedledger.application.LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())
}
