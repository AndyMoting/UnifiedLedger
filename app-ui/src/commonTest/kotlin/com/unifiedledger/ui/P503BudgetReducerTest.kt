package com.unifiedledger.ui

import com.unifiedledger.application.BudgetCommandReceipt
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetFailureCode
import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.BudgetReceiptOutcome
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        // P1-2 fix round: the config commit's re-request lands WHILE the surface is open —
        // the fresh payload is applied to the carried overview, so closing returns the NEW
        // budget view, never the pre-commit one.
        val fresh =
            BudgetMonthViewResult.Success(
                com.unifiedledger.application.BudgetMonthView(
                    ledgerId,
                    march,
                    catalogVersion = 2L,
                    total = totalObservation(limit = 250L, net = 50L),
                    categories = emptyList(),
                ),
            )
        val refreshed = assertIs<P503AppState.BudgetConfig>(reducer.reduce(config, P503UiEvent.BudgetMonthLanded(fresh)))
        assertEquals(fresh, refreshed.overview.budgetView)
        // The carried overview is the preserved one with ONLY the budget view updated.
        assertEquals(overviewWithMonth.copy(budgetView = fresh), refreshed.overview)
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

    @Test
    fun theRefreshFamilyIsAbsorbedOnTheConfigSurfaceWithoutLosingTheCarriedOverview() {
        // P1-1 fix round: a background refresh landing behind the open config surface must not
        // ISE (the pre-fix unhandled path) — the read-only-detail absorb precedent keeps the
        // surface and its preserved overview; the fresh snapshot is rebuilt on close.
        var config = openConfig()
        val otherState = overview.state
        config = assertIs(reducer.reduce(config, P503UiEvent.RefreshResult(otherState)))
        assertSame(overviewWithMonth, config.overview)
        config = assertIs(reducer.reduce(config, P503UiEvent.RefreshFailed))
        config = assertIs(reducer.reduce(config, P503UiEvent.InitialLoadResult(otherState)))
        config = assertIs(reducer.reduce(config, P503UiEvent.InitialLoadFailed))
        assertSame(overviewWithMonth, config.overview)
        assertFalse(config.submitting)
    }

    @Test
    fun aBudgetMonthLandingBehindACarriedOverviewSurfaceIsAppliedToIt() {
        // P1-2 fix round (secondary variant): the consume-after-refresh landing may hit a
        // carried-overview surface; the payload must reach its overview, never drop silently.
        val payload = BudgetMonthViewResult.Success(com.unifiedledger.application.BudgetMonthView(ledgerId, march, catalogVersion = 1L, total = totalObservation(100L, 50L), categories = emptyList()))
        val detail = P503AppState.TransactionDetail(overview = overviewWithMonth, originTab = P503Tab.HOME, transactionId = com.unifiedledger.domain.TransactionId("tx-1"), detail = com.unifiedledger.application.TransactionDetailResult.NotFound)
        val appliedToDetail = assertIs<P503AppState.TransactionDetail>(reducer.reduce(detail, P503UiEvent.BudgetMonthLanded(payload)))
        assertEquals(payload, appliedToDetail.overview.budgetView)
        val export = P503AppState.BackupExport(overview = overviewWithMonth)
        val appliedToExport = assertIs<P503AppState.BackupExport>(reducer.reduce(export, P503UiEvent.BudgetMonthLanded(payload)))
        assertEquals(payload, appliedToExport.overview.budgetView)
    }

    @Test
    fun anUnknownCommitLandingClearsTheMarkerAndShowsTheUnknownBanner() {
        // P3 fix round: an escaped commit exception claims neither success nor a typed
        // rejection — the explicit outcomeUnknown surface, re-attemptable.
        var config = submittingConfig()
        config = assertIs(reducer.reduce(config, P503UiEvent.BudgetCommitUnknownLanded))
        assertFalse(config.submitting)
        assertNull(config.outcome)
        assertTrue(config.outcomeUnknown)
        // A subsequent confirm clears the unknown marker and re-enters the submitting state.
        config = assertIs(reducer.reduce(config, P503UiEvent.ConfirmBudgetLimit))
        assertTrue(config.submitting)
        assertFalse(config.outcomeUnknown)
    }

    @Test
    fun aClosedBudgetSurfaceKeepsItsReSetEntryFlow() {
        // P2-2 fix round: a CLOSED budget's surface still runs the setLimit flow (07.B allows
        // modifying an existing budget; the commit resumes monitoring with history kept).
        var config =
            assertIs(
                reducer.reduce(
                    overviewWithMonth,
                    P503UiEvent.OpenBudgetConfig(BudgetScope.Total, march, revision = 3L, closed = true, limitMinorUnits = null),
                ),
            ) as P503AppState.BudgetConfig
        assertTrue(config.closed)
        config = assertIs(reducer.reduce(config, P503UiEvent.UpdateBudgetLimitText("80")))
        config = assertIs(reducer.reduce(config, P503UiEvent.ConfirmBudgetLimit))
        assertTrue(config.submitting)
        config =
            assertIs(
                reducer.reduce(config, P503UiEvent.BudgetConfigResultLanded(BudgetCommandResult.Accepted(receipt))),
            )
        assertFalse(config.submitting)
        assertTrue(config.outcome is BudgetCommandResult.Accepted)
    }

    @Test
    fun theMonthlyCycleLandingBehindTheConfigSurfaceUpdatesTheCarriedOverview() {
        // Fix round 2 (P2): the landing hop that consumes the armed MONTHLY re-request can run
        // while the config surface is open (the pre-fix path was a reachable ISE). A success
        // updates the carried overview's monthly fields in place; a typed failure follows the
        // detail precedent into the READ failure with the overview preserved.
        var config = openConfig()
        val activity =
            com.unifiedledger.application.MonthlyActivity(
                ledgerId,
                march,
                currencies = emptyList(),
                expenseCategories = emptyList(),
                incomeCategories = emptyList(),
            )
        val landedSuccess = MonthlyActivityResult.Success(activity)
        val landed = P503UiEvent.MonthlyActivityResult(landedSuccess, listOf(march))
        config =
            assertIs(
                reducer.reduce(config, landed),
            )
        assertEquals(activity, config.overview.monthlyActivity)
        assertEquals(listOf(march), config.overview.selectableMonths)
        assertFalse(config.overview.monthlyReloadRequired)
        val failed =
            assertIs<P503AppState.InfrastructureFailure>(
                reducer.reduce(config, P503UiEvent.MonthlyActivityResult(MonthlyActivityResult.Unavailable, emptyList())),
            )
        assertEquals(InfrastructureFailureContext.READ, failed.context)
        assertEquals(config.overview, failed.monthlyOverview)
    }

    @Test
    fun theAsyncLandingFamiliesAreAbsorbedOnTheConfigSurfaceNotUnhandled() {
        // Fix round 2 (exhaustive enumeration): state-ungated async landing hops behind the
        // open config surface are absorbed (representative members of each family; the full
        // disposition table lives in the fix round report).
        var config: P503AppState = openConfig()
        for (event in listOf<P503UiEvent>(
            P503UiEvent.ImportFilePickCancelled,
            P503UiEvent.RefreshImportReview,
            P503UiEvent.ImportGroupEnumerationStarted,
            P503UiEvent.ImportGroupEnumerationCompleted,
            P503UiEvent.RequestImportBatchConfirm,
            P503UiEvent.CancelImportBatchConfirm,
            P503UiEvent.ResumeImportBatchDispatch,
            P503UiEvent.AbandonImportBatch,
            P503UiEvent.CloseImportCandidateDetail,
            // Fix round 3: the pin toggle's async landing joins the absorbed family (its
            // affordance is unreachable here today, and the exhaustiveness claim now holds).
            P503UiEvent.TogglePin(
                target = pinTarget("category-x"),
                pinned = true,
            ),
        )) {
            config = assertIs<P503AppState.BudgetConfig>(reducer.reduce(config, event))
        }
        val landed = assertIs<P503AppState.BudgetConfig>(config)
        assertSame(overviewWithMonth, landed.overview)
    }

    private fun pinTarget(categoryId: String): com.unifiedledger.application.EntryPinTarget =
        com.unifiedledger.application.EntryPinTarget.CategoryTarget(
            ledgerId = ledgerId,
            categoryId = com.unifiedledger.domain.CategoryId(categoryId),
        )

    private fun openConfig(): P503AppState.BudgetConfig =
        P503AppState.BudgetConfig(
            overview = overviewWithMonth,
            scope = BudgetScope.Total,
            month = march,
            revision = 1L,
            closed = false,
            limitMinorUnits = 100L,
        )

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
