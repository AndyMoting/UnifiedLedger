package com.unifiedledger.ui

import com.unifiedledger.application.ExpenseDraft
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-00 shell (D-301; spec section 1.2): the `OpenImportSurface` event family — the drawer's
 * 导入账单 entry and the launcher shortcut's `OpenImportTab` route. Table 6.2a discipline: the
 * designed effect only on the OverviewEmpty (sets [P503AppState.OverviewEmpty.importSurface],
 * tab cursor untouched), absorbed in every other state, never an ISE.
 */
class P8ImportSurfaceReducerTest {
    private val emptyState = LedgerCurrentState(LedgerId("ledger-test"), emptyList(), emptyList())
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), CurrencyUnit("CNY", 2))

    @Test
    fun openImportSurfaceSetsTheRouteWithoutMovingTheTabCursor() {
        val overview = P503AppState.OverviewEmpty(emptyState, P503Tab.ACCOUNTS)
        val opened = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(overview, P503UiEvent.OpenImportSurface))
        assertTrue(opened.importSurface)
        // The tab cursor stays so a bottom-bar tap returns to the origin tab (V-1).
        assertEquals(P503Tab.ACCOUNTS, opened.selectedTab)
        // Zero formal effect: the authoritative state reference is untouched.
        assertEquals(overview.state, opened.state)
    }

    @Test
    fun aTabTapLeavesTheImportSurfaceBackToTheChosenTab() {
        val surface = P503AppState.OverviewEmpty(emptyState, P503Tab.HOME, importSurface = true)
        val left = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(surface, P503UiEvent.SelectTab(P503Tab.ANALYSIS)))
        assertFalse(left.importSurface)
        assertEquals(P503Tab.ANALYSIS, left.selectedTab)
    }

    @Test
    fun backOnTheImportSurfaceClosesTheRouteBeforeTheAccountsRule() {
        // importSurface outranks the ACCOUNTS→HOME rule: the surface was entered from wherever
        // the cursor sits, so Back closes the surface first.
        val surface = P503AppState.OverviewEmpty(emptyState, P503Tab.ACCOUNTS, importSurface = true)
        val closed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(surface, P503UiEvent.Back))
        assertFalse(closed.importSurface)
        assertEquals(P503Tab.ACCOUNTS, closed.selectedTab)
    }

    @Test
    fun openImportSurfaceIsAbsorbedInEveryOtherState() {
        // The absorb family: a sample of every state class outside the designed effect (the
        // reducer's 15 absorb lists). None may throw (table 6.2a).
        val draft = ExpenseDraft(paymentAccountId = null, categoryId = null, amountText = "", occurredAt = null)
        val requestId = RequestId("request-1")
        val states =
            listOf(
                P503AppState.Ready(),
                P503AppState.Editing(draft, requestId, emptyState, P503Tab.HOME),
                P503AppState.AwaitingConfirmation(draft, requestId, emptyState, P503Tab.HOME),
                P503AppState.Submitting(draft, requestId, emptyState, P503Tab.HOME),
                P503AppState.TransactionDetail(P503AppState.OverviewEmpty(emptyState), P503Tab.HOME, TransactionId("tx-1"), com.unifiedledger.application.TransactionDetailResult.NotFound),
                P503AppState.BackupExport(P503AppState.OverviewEmpty(emptyState)),
                P503AppState.BudgetConfig(P503AppState.OverviewEmpty(emptyState), BudgetScope.Total, kotlinx.datetime.YearMonth(2026, 11), 0L, false, null),
            )
        states.forEach { state ->
            assertEquals(state, reducer.reduce(state, P503UiEvent.OpenImportSurface), "absorbed on ${state::class.simpleName}")
        }
    }

    @Test
    fun theImportTabRouteKeepsTheShortcutReachableWithTheSameShape() {
        // The OpenImportTab neutral request routes into the same importSurface state with the
        // slot consumed (zero formal write; the launcher shortcut's 导入 leg stays reachable).
        val event = P503UiEvent.ExternalNavigationDecisionLanded(ExternalNavigationDecision.Routed(ExternalNavigationRequest.OpenImportTab))
        val routed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(P503AppState.OverviewEmpty(emptyState), event))
        assertTrue(routed.importSurface)
        assertNull(routed.pendingExternalNavigation)
    }
}
