package com.unifiedledger.ui

import com.unifiedledger.application.BudgetCommandReceipt
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetFailureCode
import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.BudgetReceiptOutcome
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-07 07.D budget event evidence (D-184; spec sections 3.4/4/5): the budget events follow
 * the P7-06 surface-family discipline — each event has ONE designed surface, is absorbed in
 * every other state (never an ISE), the config surface carries the exact preserved overview,
 * 提交中不重入 and 提交中不得离开， and a landed budget failure is carried explicitly (never a
 * fabricated zero, never a displaced overview).
 */
class P503BudgetReducerTest {
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), CurrencyUnit("CNY", 2))
    private val ledgerId = LedgerId("ledger-budget-ui")
    private val cny = CurrencyUnit("CNY", 2)
    private val march = YearMonth(2026, 3)
    private val overview =
        P503AppState.OverviewEmpty(LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList()))
    private val overviewWithMonth = overview.copy(selectedMonth = march)
    private val receipt =
        BudgetCommandReceipt(
            requestId = com.unifiedledger.application.BudgetRequestId("request-1"),
            outcome = BudgetReceiptOutcome.ACCEPTED,
            budgetId = BudgetId("budget-1"),
            newRevision = 1L,
        )

    private fun totalObservation(
        limit: Long?,
        net: Long,
    ): BudgetMonthResult.Success =
        BudgetMonthResult.Success(
            com.unifiedledger.application.BudgetMonth(
                ledgerId = ledgerId,
                month = march,
                currency = cny,
                scope = BudgetScope.Total,
                limitMinorUnits = limit,
                netExpenseMinorUnits = net,
                remainingMinorUnits = limit?.let { BudgetLimitStub.remaining(it, net) },
                overspentMinorUnits = limit?.let { BudgetLimitStub.overspent(it, net) },
            ),
        )

    @Test
    fun budgetMonthLandedSuccessIsCarriedAndPreservesTheMonthCursor() {
        val payload = BudgetMonthViewResult.Success(com.unifiedledger.application.BudgetMonthView(ledgerId, march, catalogVersion = 1L, total = totalObservation(100L, 50L), categories = emptyList()))
        val landed = reducer.reduce(overviewWithMonth, P503UiEvent.BudgetMonthLanded(payload))
        val result = assertIs<P503AppState.OverviewEmpty>(landed)
        assertEquals(payload, result.budgetView)
        assertEquals(march, result.selectedMonth)
        assertSame(overviewWithMonth.state, result.state)
    }

    @Test
    fun aBudgetFailureLandsExplicitlyWithoutDisplacingTheOverview() {
        val landed = reducer.reduce(overviewWithMonth, P503UiEvent.BudgetMonthLanded(BudgetMonthViewResult.Unavailable))
        val result = assertIs<P503AppState.OverviewEmpty>(landed)
        assertEquals(BudgetMonthViewResult.Unavailable, result.budgetView)
        assertEquals(march, result.selectedMonth)
    }

    @Test
    fun budgetEventsAreAbsorbedOutsideTheirDesignedSurface() {
        assertSame(P503AppState.Ready, reducer.reduce(P503AppState.Ready, P503UiEvent.BudgetMonthLanded(BudgetMonthViewResult.Unavailable)))
        assertSame(
            P503AppState.Ready,
            reducer.reduce(
                P503AppState.Ready,
                P503UiEvent.OpenBudgetConfig(BudgetScope.Total, march, revision = 0L, closed = false, limitMinorUnits = null),
            ),
        )
        assertSame(P503AppState.Ready, reducer.reduce(P503AppState.Ready, P503UiEvent.UpdateBudgetLimitText("50")))
        assertSame(P503AppState.Ready, reducer.reduce(P503AppState.Ready, P503UiEvent.ConfirmBudgetLimit))
        assertSame(P503AppState.Ready, reducer.reduce(P503AppState.Ready, P503UiEvent.ConfirmBudgetClose))
        assertSame(
            P503AppState.Ready,
            reducer.reduce(P503AppState.Ready, P503UiEvent.BudgetConfigResultLanded(BudgetCommandResult.Accepted(receipt))),
        )
        assertSame(P503AppState.Ready, reducer.reduce(P503AppState.Ready, P503UiEvent.CloseBudgetConfig))
        // On the overview the dialog-only events are absorbed too (the dialog is not open).
        assertSame(overview, reducer.reduce(overview, P503UiEvent.UpdateBudgetLimitText("50")))
        assertSame(overview, reducer.reduce(overview, P503UiEvent.CloseBudgetConfig))
    }

    @Test
    fun openBudgetConfigCarriesTheResolvedAuthorityAndTheExactOverview() {
        val landed =
            reducer.reduce(
                overviewWithMonth,
                P503UiEvent.OpenBudgetConfig(BudgetScope.Total, march, revision = 2L, closed = false, limitMinorUnits = 100L),
            )
        val config = assertIs<P503AppState.BudgetConfig>(landed)
        assertSame(overviewWithMonth, config.overview)
        assertEquals(BudgetScope.Total, config.scope)
        assertEquals(march, config.month)
        assertEquals(2L, config.revision)
        assertFalse(config.closed)
        assertEquals(100L, config.limitMinorUnits)
        assertEquals("", config.limitText)
        assertFalse(config.submitting)
        assertNull(config.outcome)
    }

    @Test
    fun limitTextUpdatesFlowAndConfirmMarksSubmittingWithoutReentry() {
        var config =
            reducer.reduce(
                overviewWithMonth,
                P503UiEvent.OpenBudgetConfig(BudgetScope.Total, march, revision = 1L, closed = false, limitMinorUnits = 100L),
            ) as P503AppState.BudgetConfig
        config = assertIs(reducer.reduce(config, P503UiEvent.UpdateBudgetLimitText("50")))
        assertEquals("50", config.limitText)
        config = assertIs(reducer.reduce(config, P503UiEvent.ConfirmBudgetLimit))
        assertTrue(config.submitting)
        // 提交中不重入：field updates and a duplicate confirm are absorbed while submitting.
        config = assertIs(reducer.reduce(config, P503UiEvent.UpdateBudgetLimitText("60")))
        assertEquals("50", config.limitText)
        assertTrue(config.submitting)
        config = assertIs(reducer.reduce(config, P503UiEvent.ConfirmBudgetLimit))
        assertTrue(config.submitting)
    }

    @Test
    fun aLandedResultClearsTheMarkerKeepsTheSurfaceAndShowsTheOutcome() {
        var config = submittingConfig()
        config =
            assertIs(
                reducer.reduce(
                    config,
                    P503UiEvent.BudgetConfigResultLanded(BudgetCommandResult.Accepted(receipt)),
                ),
            )
        assertFalse(config.submitting)
        assertEquals(BudgetCommandResult.Accepted(receipt), config.outcome)
        // The surface stays until the explicit close (the outcome banner is visible).
        assertTrue(reducer.reduce(config, P503UiEvent.BudgetMonthLanded(BudgetMonthViewResult.Unavailable)) is P503AppState.BudgetConfig)
    }

    @Test
    fun closeReturnsToTheExactPreservedOverviewAndIsAbsorbedWhileSubmitting() {
        var config = submittingConfig()
        // 提交中不得离开：a close while submitting is absorbed.
        config = assertIs(reducer.reduce(config, P503UiEvent.CloseBudgetConfig))
        assertTrue(config.submitting)
        val landed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(config.copy(submitting = false), P503UiEvent.CloseBudgetConfig))
        assertSame(overviewWithMonth, landed)
    }

    @Test
    fun backOnANonSubmittingConfigSurfaceReturnsToTheOverview() {
        val config = submittingConfig().copy(submitting = false)
        val landed = assertIs<P503AppState.OverviewEmpty>(reducer.reduce(config, P503UiEvent.Back))
        assertSame(overviewWithMonth, landed)
        // While submitting, Back is swallowed (提交中不得离开).
        val submitting = submittingConfig()
        assertIs<P503AppState.BudgetConfig>(reducer.reduce(submitting, P503UiEvent.Back))
    }

    @Test
    fun confirmCloseMarksSubmittingAndItsResultLands() {
        var config =
            assertIs(
                reducer.reduce(
                    overviewWithMonth.copy(budgetView = BudgetMonthViewResult.Unavailable),
                    P503UiEvent.OpenBudgetConfig(BudgetScope.Total, march, revision = 3L, closed = false, limitMinorUnits = 100L),
                ),
            ) as P503AppState.BudgetConfig
        config = assertIs(reducer.reduce(config, P503UiEvent.ConfirmBudgetClose))
        assertTrue(config.submitting)
        config =
            assertIs(
                reducer.reduce(
                    config,
                    P503UiEvent.BudgetConfigResultLanded(BudgetCommandResult.Rejected(BudgetFailureCode.BUDGET_REVISION_CONFLICT)),
                ),
            )
        assertFalse(config.submitting)
        assertEquals(BudgetFailureCode.BUDGET_REVISION_CONFLICT, (config.outcome as BudgetCommandResult.Rejected).failureCode)
    }

    private fun submittingConfig(): P503AppState.BudgetConfig =
        P503AppState.BudgetConfig(
            overview = overviewWithMonth,
            scope = BudgetScope.Total,
            month = march,
            revision = 1L,
            closed = false,
            limitMinorUnits = 100L,
            limitText = "50",
            submitting = true,
        )
}

/** Exact 07.C arithmetic stand-in for the assertions (the domain twin is exercised there). */
private object BudgetLimitStub {
    fun remaining(
        limit: Long,
        net: Long,
    ): Long = limit - net

    fun overspent(
        limit: Long,
        net: Long,
    ): Long = if (net - limit > 0L) net - limit else 0L
}
