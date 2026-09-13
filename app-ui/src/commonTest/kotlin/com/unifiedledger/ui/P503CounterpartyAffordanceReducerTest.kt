package com.unifiedledger.ui

import com.unifiedledger.application.CollectDraft
import com.unifiedledger.application.CounterpartyCommandResult
import com.unifiedledger.application.EntryExpressionEvaluator
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LendDraft
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.Counterparty
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P702SPEC-03: the minimal L-1 counterparty create/rename affordance. The dialog lives on the
 * editor (`Editing`) only; the open/update/dismiss events have their designed effect there and
 * are absorbed in every other state (§6.2a, never an ISE). The host-path decision helper keeps
 * the options refresh tied to a successful command and absorbs typed rejections safely.
 */
class P503CounterpartyAffordanceReducerTest {
    private val cny = CurrencyUnit("CNY", 2)
    private val ledgerId = LedgerId("ledger-local-test")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val requestId = RequestId("request-cp-1")
    private val counterpartyId = CounterpartyId("cp-alice")
    private val emptyState = LedgerCurrentStateForTests.empty(ledgerId)
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny, LedgerClock { occurredAt }, EntryExpressionEvaluator())

    private fun lendEditing() = P503AppState.Editing(LendDraft(null, "100.00", null, occurredAt), requestId, emptyState, P503Tab.HOME)

    private fun nonEditingStates() =
        listOf(
            P503AppState.Ready,
            P503AppState.OverviewEmpty(emptyState),
            P503AppState.AwaitingConfirmation(lendEditing().draft, requestId),
            P503AppState.Submitting(lendEditing().draft, requestId),
            P503AppState.Created,
            P503AppState.NoChange,
            P503AppState.Recovered,
            P503AppState.RequestIdentityConflict(lendEditing().draft, requestId, emptyState, P503Tab.HOME),
            P503AppState.DomainRejected(lendEditing().draft, requestId, emptyState, P503Tab.HOME),
            P503AppState.InfrastructureFailure(InfrastructureFailureContext.SUBMISSION, lendEditing().draft, requestId),
            P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ),
            P503AppState.UnknownCommit(lendEditing().draft, requestId),
        )

    private fun counterparty(name: String) = Counterparty(counterpartyId, ledgerId, name, AccountId("recv-alice"), active = true, nameHistory = emptyList())

    @Test
    fun dialogEventsHaveTheirDesignedEffectOnlyInEditing() {
        val opened = assertIs<P503AppState.Editing>(reducer.reduce(lendEditing(), P503UiEvent.OpenCounterpartyCreateDialog))
        assertEquals(CounterpartyDialog.Create(), opened.counterpartyDialog)

        val typed = assertIs<P503AppState.Editing>(reducer.reduce(opened, P503UiEvent.UpdateCounterpartyFormText("老王")))
        assertEquals(CounterpartyDialog.Create(nameText = "老王"), typed.counterpartyDialog)

        val renameOpened =
            assertIs<P503AppState.Editing>(
                reducer.reduce(lendEditing(), P503UiEvent.OpenCounterpartyRenameDialog(counterpartyId, "老王")),
            )
        assertEquals(CounterpartyDialog.Rename(counterpartyId, "老王", ""), renameOpened.counterpartyDialog)
        val renameTyped = assertIs<P503AppState.Editing>(reducer.reduce(renameOpened, P503UiEvent.UpdateCounterpartyFormText("老王新")))
        assertEquals(CounterpartyDialog.Rename(counterpartyId, "老王", "老王新"), renameTyped.counterpartyDialog)

        // The dialog state never leaks into the draft.
        assertEquals(lendEditing().draft, renameTyped.draft)

        val dismissed = assertIs<P503AppState.Editing>(reducer.reduce(typed, P503UiEvent.DismissCounterpartyDialog))
        assertNull(dismissed.counterpartyDialog)
    }

    @Test
    fun dialogEventsWorkFromTheCollectEditorToo() {
        val collectEditing =
            P503AppState.Editing(
                CollectDraft(null, "45.00", "", "", destinationAccountId = null, occurredAt = occurredAt),
                requestId,
                emptyState,
                P503Tab.HOME,
            )
        val opened = assertIs<P503AppState.Editing>(reducer.reduce(collectEditing, P503UiEvent.OpenCounterpartyCreateDialog))
        assertIs<CounterpartyDialog.Create>(opened.counterpartyDialog)
    }

    @Test
    fun dialogEventsAreAbsorbedInEveryNonEditingState() {
        val events =
            listOf<P503UiEvent>(
                P503UiEvent.OpenCounterpartyCreateDialog,
                P503UiEvent.OpenCounterpartyRenameDialog(counterpartyId, "老王"),
                P503UiEvent.UpdateCounterpartyFormText("老王"),
                P503UiEvent.DismissCounterpartyDialog,
            )
        for (state in nonEditingStates()) {
            for (event in events) {
                assertEquals(state, reducer.reduce(state, event), "absorbed $event in $state")
            }
        }
    }

    @Test
    fun optionsRefreshFollowsOnlySuccessfulCounterpartyCommands() {
        // P702SPEC-03 host path: a successful create/rename refreshes the option projections; a
        // typed rejection is absorbed safely (no refresh, no dispatch).
        assertTrue(shouldRefreshOptionsAfterCounterpartyCommand(CounterpartyCommandResult.Created(counterparty("老王"))))
        assertTrue(shouldRefreshOptionsAfterCounterpartyCommand(CounterpartyCommandResult.Renamed(counterparty("老王新"))))
        assertTrue(shouldRefreshOptionsAfterCounterpartyCommand(CounterpartyCommandResult.NoChange(counterparty("老王"))))
        assertTrue(shouldRefreshOptionsAfterCounterpartyCommand(CounterpartyCommandResult.ActiveChanged(counterparty("老王"))))
        assertFalse(shouldRefreshOptionsAfterCounterpartyCommand(CounterpartyCommandResult.Rejected(com.unifiedledger.domain.CounterpartyViolation.CounterpartyNotFound)))
    }
}
