package com.unifiedledger.ui

import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
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
 * A-02 FIX-MONTH-1 (D-152): trigger (e) arms a pending flag when the refresh starts and completes
 * through the post-landing consumption (`consumeMonthlyReRequestAfterRefresh`/
 * `dropMonthlyReRequestAfterFailedRefresh`) — never as a synchronous request beside the refresh.
 * P7-03 FIX-STALE-1 (D-153): trigger (f) — a completed import batch confirmation dispatch run —
 * arms the SAME pending flag via `onImportBatchConfirmed` and is consumed by the same landing hop.
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

    // (e) every determinate success re-requests the monthly payload AFTER the refreshed overview
    // lands (A-02 FIX-MONTH-1, D-152): arming the pending flag fires no synchronous request (the
    // async refresh's result state would absorb the payload), the post-landing consumption fires
    // exactly one unconditional request stamped on the LANDED month, and the (d) guard then stays
    // quiet on the fresh overview.

    @Test
    fun determinateSuccessRefreshReRequestsTheMonthlyPayloadOnlyAfterItLands() {
        val (host, probe) = coordinator()
        val action = host.decide(P503AppState.Created)
        assertIs<HostAction.RefreshAfterResult>(action)
        assertEquals(1, probe.refreshes.size)
        assertEquals(0, probe.monthlyRequests.size)
        // The refreshed overview lands (selectedMonth = null = 本月) and the host consumes: exactly
        // one unconditional request, and the (d) guard sees the stamped month with no duplicate.
        host.consumeMonthlyReRequestAfterRefresh(overview(month = null))
        assertEquals(1, probe.monthlyRequests.size)
        assertFalse(host.decideMonthly(overview(month = null)))
        assertEquals(1, probe.monthlyRequests.size)
    }

    @Test
    fun noChangeAndRecoveredRefreshesReRequestTheMonthlyPayloadAfterLanding() {
        val (host, probe) = coordinator()
        // Each (e) event: arm on decide, exactly one unconditional request on its landing. The
        // arms never overlap in the real host (an (e) landing rebuilding the overview is the
        // precondition for the next flow's (e) event), so the pending boolean coalesces them.
        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.NoChange))
        assertEquals(1, probe.refreshes.size)
        assertEquals(0, probe.monthlyRequests.size)
        host.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(1, probe.monthlyRequests.size)

        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.Recovered))
        assertEquals(2, probe.refreshes.size)
        host.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(2, probe.monthlyRequests.size)
    }

    // (e) failure: a failed authoritative refresh clears the pending re-request without firing —
    // recovery stays the residual boundary (a) path (a user re-select or the READ retry).

    @Test
    fun failedRefreshLandingClearsThePendingMonthlyReRequestWithoutRequesting() {
        val (host, probe) = coordinator()
        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.Created))
        assertEquals(0, probe.monthlyRequests.size)
        host.dropMonthlyReRequestAfterFailedRefresh()
        // A later consumption (the coalesced re-run landing) must stay a no-op after the drop.
        host.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(0, probe.monthlyRequests.size)
    }

    // Landings without an armed (e) trigger (startup load, ordinary/READ-retry refreshes)
    // consume nothing.

    @Test
    fun consumptionWithoutAnArmedTriggerNeverRequestsTheMonthlyPayload() {
        val (host, probe) = coordinator()
        host.consumeMonthlyReRequestAfterRefresh(overview())
        host.dropMonthlyReRequestAfterFailedRefresh()
        assertEquals(0, probe.monthlyRequests.size)
    }

    // The consumption stamps the LANDED month, so the (d) guard compares against it and a second
    // consumption stays unconditional (each (e) event re-requests exactly once).

    @Test
    fun consumptionStampsTheLandedMonthAndStaysUnconditional() {
        val (host, probe) = coordinator()
        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.Created))
        host.consumeMonthlyReRequestAfterRefresh(overview(month = YearMonth(2026, 3)))
        assertEquals(1, probe.monthlyRequests.size)
        assertFalse(host.decideMonthly(overview(month = YearMonth(2026, 3))))
        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.NoChange))
        host.consumeMonthlyReRequestAfterRefresh(overview(month = YearMonth(2026, 4)))
        assertEquals(2, probe.monthlyRequests.size)
    }

    // (f) a completed import batch confirmation dispatch run (P7-03 FIX-STALE-1, D-153) fires the
    // authoritative refresh and arms the SAME pending flag as (e): no synchronous request beside
    // the refresh, exactly one unconditional post-landing request stamped on the landed month,
    // and the (d) guard then stays quiet on the fresh overview.

    @Test
    fun importBatchConfirmedFiresTheRefreshAndReRequestsTheMonthlyPayloadOnlyAfterItLands() {
        val (host, probe) = coordinator()
        host.onImportBatchConfirmed()
        assertEquals(1, probe.refreshes.size)
        assertEquals(0, probe.monthlyRequests.size)
        // The refreshed overview lands (selectedMonth = null = 本月) and the host consumes: exactly
        // one unconditional request, and the (d) guard sees the stamped month with no duplicate.
        host.consumeMonthlyReRequestAfterRefresh(overview(month = null))
        assertEquals(1, probe.monthlyRequests.size)
        assertFalse(host.decideMonthly(overview(month = null)))
        assertEquals(1, probe.monthlyRequests.size)
    }

    // (f) failure: a failed authoritative refresh clears the (f) arm without firing — recovery
    // stays the residual boundary (a) path, and a later unrelated landing consumes nothing.

    @Test
    fun failedRefreshLandingDropsTheImportBatchConfirmedArmWithoutRequesting() {
        val (host, probe) = coordinator()
        host.onImportBatchConfirmed()
        assertEquals(1, probe.refreshes.size)
        host.dropMonthlyReRequestAfterFailedRefresh()
        host.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(0, probe.monthlyRequests.size)
    }

    // (f)/(e) share one armed marker, so they cannot cross-interfere: an (e) arm followed by an
    // (f) trigger before the landing coalesces into ONE consumption — exactly one request.

    @Test
    fun importBatchConfirmedSharesTheArmedMarkerWithTheDeterminateSuccessTrigger() {
        val (host, probe) = coordinator()
        assertIs<HostAction.RefreshAfterResult>(host.decide(P503AppState.Created))
        host.onImportBatchConfirmed()
        assertEquals(2, probe.refreshes.size)
        assertEquals(0, probe.monthlyRequests.size)
        host.consumeMonthlyReRequestAfterRefresh(overview())
        assertEquals(1, probe.monthlyRequests.size)
    }

    // (f) the ONE shared arm-gate for both host call sites (dispatch-loop completed branch and
    // Unknown 核对 resolution hop): arm exactly when the landed state is the overview carrying a
    // retained summary with no Unknown left. The pure truth table is covered here; the hop wiring
    // itself is Composable host code and is covered by the device window (registered in D-153).

    private fun overviewWithBatchResult(vararg outcomes: ImportBatchItemOutcome): P503AppState.OverviewEmpty {
        val summaryItems =
            outcomes.mapIndexed { index, outcome ->
                ImportBatchResultItem(
                    ImportBatchItem(ImportCandidateId("cand-$index"), ImportRequestId("req-$index")),
                    outcome,
                )
            }
        val summary = ImportBatchResultSummary(confirmedAt = "2026-09-19T08:00:00", items = summaryItems)
        return overview().copy(importReview = ImportReviewView(batchResult = summary))
    }

    private fun batchSubmitting(vararg outcomes: ImportBatchItemOutcome?): P503AppState.ImportBatchSubmitting =
        P503AppState.ImportBatchSubmitting(
            overview = overview(),
            confirmedAt = "2026-09-19T08:00:00",
            items =
                outcomes.mapIndexed { index, outcome ->
                    ImportBatchSubmittingItem(ImportBatchItem(ImportCandidateId("cand-$index"), ImportRequestId("req-$index")), outcome)
                },
        )

    @Test
    fun importBatchConfirmedArmsOnlyWhenTheLandedSummaryIsUnknownFree() {
        // Completed run, every item terminal (the reducer auto-left): the completed branch's hop
        // observes the overview with the Unknown-free summary → arm.
        assertTrue(
            shouldArmImportBatchConfirmed(
                overviewWithBatchResult(ImportBatchItemOutcome.CheckConflict("E_TEST"), ImportBatchItemOutcome.Skipped("SKIP_TEST")),
            ),
        )
        assertTrue(shouldArmImportBatchConfirmed(overviewWithBatchResult(ImportBatchItemOutcome.Rejected("E_TEST"))))

        // FALSIFIED-ORDERING REGRESSION (verifier): A confirmed, B Unknown → paused → Resume
        // dispatches C → run completes while B still blocks the auto-leave (state stays
        // ImportBatchSubmitting) → the completed branch must NOT arm; the later 核对 resolution
        // of B (all-terminal auto-leave) arms exactly once.
        assertFalse(
            shouldArmImportBatchConfirmed(
                batchSubmitting(ImportBatchItemOutcome.Confirmed(ImportReceipt(ImportRequestId("req-0"), null, null, ImportCandidateId("cand-0"), null, null)), ImportBatchItemOutcome.Unknown, null),
            ),
        )
        // ...and the same resolution's landing (auto-left, summary Unknown-free) arms.
        assertTrue(
            shouldArmImportBatchConfirmed(
                overviewWithBatchResult(
                    ImportBatchItemOutcome.Confirmed(ImportReceipt(ImportRequestId("req-0"), null, null, ImportCandidateId("cand-0"), null, null)),
                    ImportBatchItemOutcome.Confirmed(ImportReceipt(ImportRequestId("req-1"), null, null, ImportCandidateId("cand-1"), null, null)),
                    ImportBatchItemOutcome.Skipped("SKIP_TEST"),
                ),
            ),
        )

        // A mid-batch 核对 resolution: the state is still dispatching — the later completed
        // branch arms.
        assertFalse(shouldArmImportBatchConfirmed(batchSubmitting(ImportBatchItemOutcome.Confirmed(ImportReceipt(ImportRequestId("req-0"), null, null, ImportCandidateId("cand-0"), null, null)), null)))

        // StillUnknown (or an absorbed verdict): the checked item keeps its check entry — the run
        // stays incomplete, the user may retry or resolve it later.
        assertFalse(shouldArmImportBatchConfirmed(overviewWithBatchResult(ImportBatchItemOutcome.Unknown)))

        // Another Unknown still remains after this resolution: not all-terminal yet — the LATER
        // resolution completes the run and arms exactly once.
        assertFalse(
            shouldArmImportBatchConfirmed(
                overviewWithBatchResult(
                    ImportBatchItemOutcome.Confirmed(ImportReceipt(ImportRequestId("req-0"), null, null, ImportCandidateId("cand-0"), null, null)),
                    ImportBatchItemOutcome.Unknown,
                ),
            ),
        )

        // An absorbed landing without the retained summary never arms.
        assertFalse(shouldArmImportBatchConfirmed(overview()))
        assertFalse(shouldArmImportBatchConfirmed(P503AppState.Ready))
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
