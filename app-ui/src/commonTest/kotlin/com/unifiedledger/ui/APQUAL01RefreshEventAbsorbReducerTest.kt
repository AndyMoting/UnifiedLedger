package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * A-PERF (APQUAL-01, spec section 2.3 rework 2): the layer-2 background current-state read made
 * RefreshResult/RefreshFailed (and the initial-load pair) able to land in transient states the
 * synchronous baseline could never reach them in. The reducer must ABSORB the four completion
 * events in every affected non-overview state (the reduceImportCandidateDetail absorb precedent)
 * instead of hitting the `unhandled` tail's ISE — the user-reachable crash of the review finding
 * (management command -> async refresh window -> navigate into a transient -> completion lands).
 *
 * Each affected state pins the full four-event family: the state instance is returned UNCHANGED
 * (absorbed, no partial application). The pre-fix reducer threw IllegalStateException from
 * these (state, event) pairs; the tail's fail-fast contract is separately re-pinned with an
 * event that stays unlisted (SelectTab inside Editing, the existing precedent).
 */
class APQUAL01RefreshEventAbsorbReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())
    private val requestId = RequestId("request-absorb-1")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    /** A validation-complete draft so Continue actually reaches AwaitingConfirmation. */
    private fun fullDraft() =
        ExpenseDraft(
            paymentAccountId = AccountId("asset-payment-local"),
            categoryId = CategoryId("expense-category-breakfast"),
            amountText = "35.80",
            occurredAt = occurredAt,
        )

    private fun assertAllFourCompletionEventsAbsorbed(state: P503AppState) {
        assertSame(state, reducer.reduce(state, P503UiEvent.RefreshResult(emptyState)), "RefreshResult must be absorbed with the state instance unchanged")
        assertSame(state, reducer.reduce(state, P503UiEvent.RefreshFailed), "RefreshFailed must be absorbed with the state instance unchanged")
        assertSame(state, reducer.reduce(state, P503UiEvent.InitialLoadResult(emptyState)), "InitialLoadResult must be absorbed with the state instance unchanged")
        assertSame(state, reducer.reduce(state, P503UiEvent.InitialLoadFailed), "InitialLoadFailed must be absorbed with the state instance unchanged")
    }

    private fun overview(): P503AppState.OverviewEmpty = assertIs(reducer.reduce(P503AppState.Ready, P503UiEvent.InitialLoadResult(emptyState)))

    private fun editing(): P503AppState.Editing = P503AppState.Editing(fullDraft(), requestId, emptyState, P503Tab.HOME)

    private fun awaiting(): P503AppState.AwaitingConfirmation = P503AppState.AwaitingConfirmation(fullDraft(), requestId, emptyState, P503Tab.HOME)

    private fun submitting(): P503AppState.Submitting = P503AppState.Submitting(fullDraft(), requestId, emptyState, P503Tab.HOME)

    @Test
    fun transactionDetailAbsorbsAllFourCompletionEvents() {
        val detail =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(overview(), P503UiEvent.SelectTransaction(TransactionId("tx-absorb-1"), detailResult())),
            )
        assertAllFourCompletionEventsAbsorbed(detail)
    }

    @Test
    fun editingAbsorbsAllFourCompletionEvents() {
        assertAllFourCompletionEventsAbsorbed(editing())
    }

    @Test
    fun awaitingConfirmationAbsorbsAllFourCompletionEvents() {
        assertAllFourCompletionEventsAbsorbed(awaiting())
    }

    @Test
    fun submittingAbsorbsAllFourCompletionEvents() {
        assertAllFourCompletionEventsAbsorbed(submitting())
    }

    @Test
    fun requestIdentityConflictAbsorbsAllFourCompletionEvents() {
        val conflict =
            assertIs<P503AppState.RequestIdentityConflict>(
                reducer.reduce(
                    submitting(),
                    P503UiEvent.SubmissionResult(
                        ManualExpenseSubmissionResult.Application(
                            ManualExpenseSaveResult.Executed(
                                ConfirmedManualExpenseResult.RequestIdentityConflict(
                                    ManualExpenseRequestIdentity(ledgerId, requestId),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        assertAllFourCompletionEventsAbsorbed(conflict)
    }

    @Test
    fun domainRejectedAbsorbsAllFourCompletionEvents() {
        val rejected =
            assertIs<P503AppState.DomainRejected>(
                reducer.reduce(
                    submitting(),
                    P503UiEvent.SubmissionResult(
                        ManualExpenseSubmissionResult.Application(
                            ManualExpenseSaveResult.Executed(
                                ConfirmedManualExpenseResult.Rejected(DomainViolation.InvalidCatalog),
                            ),
                        ),
                    ),
                ),
            )
        assertAllFourCompletionEventsAbsorbed(rejected)
    }

    @Test
    fun submissionInfrastructureFailureAbsorbsAllFourCompletionEvents() {
        val failure =
            assertIs<P503AppState.InfrastructureFailure>(
                reducer.reduce(
                    submitting(),
                    P503UiEvent.SubmissionResult(ManualExpenseSubmissionResult.InfrastructureFailure),
                ),
            )
        assertEquals(InfrastructureFailureContext.SUBMISSION, failure.context)
        assertAllFourCompletionEventsAbsorbed(failure)
    }

    /**
     * The `unhandled` tail's fail-fast contract is unchanged: an event that stays unlisted in
     * the transient states still throws (SelectTab inside Editing, the existing precedent), so
     * the absorb extension did not silently widen the transition table.
     */
    @Test
    fun anUnrelatedEventStillFailsFastInTheTransientStates() {
        assertFailsWith<IllegalStateException> {
            reducer.reduce(editing(), P503UiEvent.SelectTab(P503Tab.HOME))
        }
    }

    /** A minimal valid detail payload (all values anonymous synthetic). */
    private fun detailResult(): TransactionDetailResult.Success =
        TransactionDetailResult.Success(
            TransactionDetail(
                ledgerId = ledgerId,
                transactionId = TransactionId("tx-absorb-1"),
                currentVersionId = TransactionVersionId("version-absorb-1"),
                kind = TransactionKind.EXPENSE,
                occurredAt = occurredAt,
                statisticsAt = occurredAt,
                note = "",
                legs = emptyList(),
                creationEntry = com.unifiedledger.application.CreationEntry.MANUAL_CREATED,
                reconciliation = com.unifiedledger.application.TransactionReconciliationProjection(emptyList(), null),
            ),
        )
}
