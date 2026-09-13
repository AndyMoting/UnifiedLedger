package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-03.C host-coordinator monthly decision tests (spec section 6.2, P703SPEC-04): the frozen
 * closed re-request trigger set (a) initial load, (b) SelectMonth, (c) AnalysisMonthShift,
 * (d) selected-month change including 本月 re-resolution across a clock rollover, (e) the
 * authoritative refresh after each determinate success — and nothing else. Tab switches,
 * READ-retry recoveries, detail open/close and ordinary refreshes never re-request; recovery
 * from a monthly failure is a re-dispatched SelectMonth (unconditional via trigger (b)).
 */
class P503LedgerViewHostCoordinatorTest {
    private val ledgerId = LedgerId("ledger-monthly-host-test")
    private val september = YearMonth(2026, 9)
    private val october = YearMonth(2026, 10)

    /** Counting probe the coordinator's callbacks record into. */
    private class Probe {
        val refreshes = mutableListOf<Int>()
        val monthlyRequests = mutableListOf<Int>()
    }

    private fun newCoordinator(
        probe: Probe,
        currentMonth: () -> YearMonth?,
    ): P503HostCoordinator =
        P503HostCoordinator(
            onRefresh = { probe.refreshes += 1 },
            onSubmit = { _, _ -> },
            onCheck = { _, _ -> },
            onMonthlyRequest = { probe.monthlyRequests += 1 },
            currentMonth = currentMonth,
        )

    private fun coordinator(currentMonth: () -> YearMonth? = { september }): Pair<P503HostCoordinator, Probe> {
        val probe = Probe()
        return newCoordinator(probe, currentMonth) to probe
    }

    private fun overview(month: YearMonth? = null): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList()), selectedMonth = month)

    // (a) initial load: the first overview evaluation requests the monthly payload exactly once.

    @Test
    fun firstOverviewEvaluationRequestsTheMonthlyPayloadOnce() {
        val (host, probe) = coordinator()
        assertTrue(host.decideMonthly(overview()))
        assertFalse(host.decideMonthly(overview()))
        assertFalse(host.decideMonthly(overview(month = september)))
        assertEquals(1, probe.monthlyRequests.size)
    }

    // (b)/(d) a selected-month change re-requests; staying on the month does not.

    @Test
    fun selectedMonthChangeReRequestsAndStayingDoesNot() {
        val (host, probe) = coordinator()
        assertTrue(host.decideMonthly(overview(month = null)))
        assertFalse(host.decideMonthly(overview(month = null)))
        assertTrue(host.decideMonthly(overview(month = YearMonth(2026, 3))))
        assertFalse(host.decideMonthly(overview(month = YearMonth(2026, 3))))
        // Back to 本月 (selection cleared): the effective month changed again.
        assertTrue(host.decideMonthly(overview(month = null)))
        assertFalse(host.decideMonthly(overview(month = null)))
        assertEquals(3, probe.monthlyRequests.size)
    }

    // (d) 本月跨月刷新 (C01): a clock rollover re-resolves the current month and re-requests.

    @Test
    fun clockMonthRolloverReRequestsTheMonthlyPayload() {
        var clockMonth: YearMonth? = september
        val (host, probe) = coordinator(currentMonth = { clockMonth })
        assertTrue(host.decideMonthly(overview(month = null)))
        assertFalse(host.decideMonthly(overview(month = null)))
        clockMonth = october
        assertTrue(host.decideMonthly(overview(month = null)))
        assertFalse(host.decideMonthly(overview(month = null)))
        assertEquals(2, probe.monthlyRequests.size)
    }

    // (b) recovery path: a re-dispatched SelectMonth re-requests unconditionally, even for the same month.

    @Test
    fun requestMonthlyNowIsUnconditionalForTheSameMonth() {
        val (host, probe) = coordinator()
        val state = overview(month = YearMonth(2026, 3))
        assertTrue(host.requestMonthlyNow(state))
        assertTrue(host.requestMonthlyNow(state))
        assertFalse(host.decideMonthly(state))
        assertEquals(2, probe.monthlyRequests.size)
    }

    // (c) AnalysisMonthShift re-requests through the same unconditional channel on the shifted month.

    @Test
    fun requestMonthlyNowRecordsTheShiftedEffectiveMonth() {
        val (host, probe) = coordinator()
        assertTrue(host.requestMonthlyNow(overview(month = YearMonth(2026, 5))))
        // The same month afterwards: no duplicate from the (d) check.
        assertFalse(host.decideMonthly(overview(month = YearMonth(2026, 5))))
        assertEquals(1, probe.monthlyRequests.size)
    }

    // (e) every determinate success re-requests the monthly payload alongside the authoritative refresh.

    @Test
    fun determinateSuccessRefreshReRequestsTheMonthlyPayloadWithoutDuplicates() {
        val (host, probe) = coordinator()
        val action = host.decide(P503AppState.Created)
        assertIs<HostAction.RefreshAfterResult>(action)
        assertEquals(1, probe.refreshes.size)
        assertEquals(1, probe.monthlyRequests.size)
        // The refresh lands on a fresh overview (selectedMonth = null = 本月): no duplicate request.
        assertFalse(host.decideMonthly(overview(month = null)))
        assertEquals(1, probe.monthlyRequests.size)
    }

    @Test
    fun noChangeAndRecoveredRefreshesReRequestTheMonthlyPayload() {
        val (host, probe) = coordinator()
        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.NoChange))
        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.Recovered))
        assertEquals(2, probe.refreshes.size)
        assertEquals(2, probe.monthlyRequests.size)
    }

    // Nothing outside the frozen set re-requests.

    @Test
    fun nonOverviewStatesAndOtherDecisionsNeverRequestTheMonthlyPayload() {
        val (host, probe) = coordinator()
        // (a) the initial overview request is served first; nothing below may re-request.
        assertTrue(host.decideMonthly(overview()))
        assertFalse(host.decideMonthly(P503AppState.Ready))
        assertFalse(host.decideMonthly(P503AppState.Created))
        assertFalse(host.decideMonthly(P503AppState.Editing(ManualExpenseDraft(null, null, "", null), null)))
        assertFalse(host.decideMonthly(P503AppState.InfrastructureFailure(InfrastructureFailureContext.READ)))
        // Tab switches (state copies on the same month) do not re-request.
        assertFalse(host.decideMonthly(overview().copy(selectedTab = P503Tab.ACCOUNTS)))
        assertEquals(1, probe.monthlyRequests.size)
    }

    @Test
    fun unresolvableCurrentMonthRequestsOnceThenStopsUntilTheClockRecovers() {
        var clockMonth: YearMonth? = null
        val (host, probe) = coordinator(currentMonth = { clockMonth })
        // (a) still fires once: the request handler surfaces the typed Unavailable (R-Q06-4).
        assertTrue(host.decideMonthly(overview(month = null)))
        assertFalse(host.decideMonthly(overview(month = null)))
        clockMonth = september
        assertTrue(host.decideMonthly(overview(month = null)))
        assertEquals(2, probe.monthlyRequests.size)
    }
}
