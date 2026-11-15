package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.export.readable.ReadableLedgerExportFailure
import com.unifiedledger.application.export.readable.ReadableLedgerExportResult
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P8-05 批 b (D-331 spec sections 4 批 b / D-E8-D-E10; V-7): state-machine tests for the
 * readable-export surface. Every new event has its designed effect only in its designed state and
 * is absorbed in every other state (no new event throws anywhere); `Exit` stays unlisted (ISE) on
 * the new state (the G-B discipline); the V-7 legs — the wired/unwired drawer-row probe family,
 * the D-E9 counting pair, the cooperative cancel, the typed outcome family (含空账本 已导出 0 笔)
 * and 提交中不得离开 — are each pinned at the reducer level.
 */
class P503ReadableExportReducerTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
    private val emptyState = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    private fun overview(): P503AppState.OverviewEmpty = P503AppState.OverviewEmpty(emptyState)

    private fun exportState(
        running: Boolean = false,
        exportedRows: Long = 0L,
        totalRows: Int = 0,
        cancelRequested: Boolean = false,
        outcome: ReadableLedgerExportResult? = null,
    ): P503AppState.ReadableExport = P503AppState.ReadableExport(overview(), running, exportedRows, totalRows, cancelRequested, outcome)

    // ---- the one designed open transition (D-E8) ----

    @Test
    fun theOverviewEntryOpensTheSurfaceCarryingTheExactOverview() {
        val overview = overview()
        val opened = assertIs<P503AppState.ReadableExport>(reducer.reduce(overview, P503UiEvent.OpenReadableExport))
        assertSame(overview, opened.overview)
        assertEquals(false, opened.running)
        assertEquals(0L, opened.exportedRows)
        assertEquals(0, opened.totalRows)
        assertFalse(opened.cancelRequested)
        assertNull(opened.outcome)
    }

    // ---- the surface's own six events ----

    @Test
    fun confirmSetsTheRunningMarkerAndClearsAnyPreviousOutcome() {
        val state = exportState(outcome = ReadableLedgerExportResult.Failed(ReadableLedgerExportFailure.WRITE_FAILED))
        val confirmed = assertIs<P503AppState.ReadableExport>(reducer.reduce(state, P503UiEvent.ConfirmReadableExport))
        assertTrue(confirmed.running)
        assertNull(confirmed.outcome)
        assertEquals(0L, confirmed.exportedRows)
        assertEquals(0, confirmed.totalRows)
    }

    @Test
    fun aSecondConfirmWhileRunningIsAbsorbed() {
        val running = exportState(running = true)
        assertSame(running, reducer.reduce(running, P503UiEvent.ConfirmReadableExport))
    }

    @Test
    fun aProgressLandingRefreshesTheCountingPair() {
        // D-E9: the per-page counting landing — exported so far, the exact denominator.
        val running = exportState(running = true)
        val progressed =
            assertIs<P503AppState.ReadableExport>(
                reducer.reduce(running, P503UiEvent.ReadableExportProgressLanded(exportedRows = 200L, totalRows = 500)),
            )
        assertEquals(200L, progressed.exportedRows)
        assertEquals(500, progressed.totalRows)
        assertTrue(progressed.running)
    }

    @Test
    fun theCancelRequestIsRecordedWhileRunningAndAbsorbedOtherwise() {
        val running = exportState(running = true)
        val requested = assertIs<P503AppState.ReadableExport>(reducer.reduce(running, P503UiEvent.RequestReadableExportCancel))
        assertTrue(requested.cancelRequested)
        assertTrue(requested.running)
        // A second request is absorbed (the honest-affordance single record).
        assertSame(requested, reducer.reduce(requested, P503UiEvent.RequestReadableExportCancel))
        // Without a run in flight the request is absorbed (the affordance gate).
        val idle = exportState(running = false)
        assertSame(idle, reducer.reduce(idle, P503UiEvent.RequestReadableExportCancel))
    }

    @Test
    fun aLandedResultClearsTheMarkerTheCancelRequestAndTheProgressPair() {
        val running = exportState(running = true, cancelRequested = true)
        val landed =
            assertIs<P503AppState.ReadableExport>(
                reducer.reduce(running, P503UiEvent.ReadableExportResultLanded(ReadableLedgerExportResult.Succeeded(exportedRows = 500L, totalRows = 500, suggestedFileName = "unifiedledger-export-20261115.xlsx"))),
            )
        assertFalse(landed.running)
        assertFalse(landed.cancelRequested)
        assertEquals(0L, landed.exportedRows)
        assertEquals(0, landed.totalRows)
        assertEquals(
            ReadableLedgerExportResult.Succeeded(exportedRows = 500L, totalRows = 500, suggestedFileName = "unifiedledger-export-20261115.xlsx"),
            landed.outcome,
        )
    }

    @Test
    fun theEmptyLedgerLandsTheTypedZeroSuccess() {
        // D-E10: the empty ledger is a legal export — the honest 0-row success banner.
        val running = exportState(running = true)
        val landed =
            assertIs<P503AppState.ReadableExport>(
                reducer.reduce(
                    running,
                    P503UiEvent.ReadableExportResultLanded(
                        ReadableLedgerExportResult.Succeeded(exportedRows = 0L, totalRows = 0, suggestedFileName = "unifiedledger-export-20261115.xlsx"),
                    ),
                ),
            )
        assertEquals(
            ReadableLedgerExportResult.Succeeded(exportedRows = 0L, totalRows = 0, suggestedFileName = "unifiedledger-export-20261115.xlsx"),
            landed.outcome,
        )
        assertFalse(landed.running)
    }

    @Test
    fun closeLeavesForThePreservedOverview() {
        val state = exportState()
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.CloseReadableExport))
        assertSame(state.overview, reducer.reduce(state, P503UiEvent.Back))
    }

    @Test
    fun aRunningExportAbsorbsCloseAndBack() {
        // 提交中不得离开: the running export keeps its marker until the walk lands or cancels.
        val running = exportState(running = true)
        assertSame(running, reducer.reduce(running, P503UiEvent.CloseReadableExport))
        assertSame(running, reducer.reduce(running, P503UiEvent.Back))
    }

    @Test
    fun aBudgetLandingIsAppliedToThePreservedOverview() {
        // The P1-2 fix-round discipline: carried-overview surfaces APPLY, never absorb.
        val state = exportState()
        val payload =
            com.unifiedledger.application.BudgetMonthViewResult.Success(
                com.unifiedledger.application.BudgetMonthView(
                    ledgerId,
                    kotlinx.datetime.YearMonth(2026, 3),
                    catalogVersion = 1L,
                    total =
                        com.unifiedledger.application.BudgetMonthResult.Success(
                            com.unifiedledger.application.BudgetMonth(
                                ledgerId = ledgerId,
                                month = kotlinx.datetime.YearMonth(2026, 3),
                                currency = cny,
                                scope = com.unifiedledger.domain.BudgetScope.Total,
                                limitMinorUnits = 100L,
                                netExpenseMinorUnits = 50L,
                                remainingMinorUnits = 50L,
                                overspentMinorUnits = 0L,
                            ),
                        ),
                    categories = emptyList(),
                ),
            )
        val applied = assertIs<P503AppState.ReadableExport>(reducer.reduce(state, P503UiEvent.BudgetMonthLanded(payload)))
        assertSame(payload, applied.overview.budgetView)
    }

    // ---- absorption columns (the family guard + the pre-existing tail) ----

    @Test
    fun everyReadableExportEventIsAbsorbedOnEveryPreExistingState() {
        val overview = overview()
        val events: List<P503UiEvent> =
            listOf(
                P503UiEvent.ConfirmReadableExport,
                P503UiEvent.ReadableExportProgressLanded(1L, 1),
                P503UiEvent.RequestReadableExportCancel,
                P503UiEvent.ReadableExportResultLanded(ReadableLedgerExportResult.Cancelled),
                P503UiEvent.CloseReadableExport,
            )
        events.forEach { event -> assertSame(overview, reducer.reduce(overview, event), "event $event must be absorbed on the overview") }
        // OpenReadableExport is NOT in the absorb list on OverviewEmpty: the drawer row is its
        // DESIGNED route there (D-E8), it transitions instead of absorbing. The transition carries
        // the exact overview instance back out (every exit restores it).
        val opened = reducer.reduce(overview, P503UiEvent.OpenReadableExport)
        assertTrue(opened is P503AppState.ReadableExport, "open on the overview transitions to the readable-export surface")
        assertSame(overview, opened.overview, "the transition must carry the exact overview instance")
    }

    @Test
    fun openReadableExportIsAbsorbedOnTheReadableExportSurfaceItself() {
        val state = exportState()
        assertSame(state, reducer.reduce(state, P503UiEvent.OpenReadableExport))
    }

    @Test
    fun exitStaysUnlistedAndThrowsOnTheReadableExportSurface() {
        // The G-B discipline: Exit is not listed — it throws (the frozen unhandled behavior).
        assertFailsWith<IllegalStateException> { reducer.reduce(exportState(), P503UiEvent.Exit) }
    }
}
