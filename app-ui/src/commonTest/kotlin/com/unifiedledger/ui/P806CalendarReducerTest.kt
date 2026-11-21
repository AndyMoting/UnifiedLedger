package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.P806CalendarCurrencyView
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch 4 (D-362 1; spec sections 3.7/3.8/3.10): the monthly calendar reducer.
 *
 * The surface is one full-screen read-only page over one explicit-month snapshot read. These
 * assertions cover the frozen acceptance shape without a Compose runtime: open, initial cursor,
 * month isolation (no overview write), the host token gate (single flight), the landing axes, the
 * six distinguishable faces with a retained previous payload, close/back to the same overview, the
 * day-detail round trip and its one-shot scroll restore. Anonymous synthetic data only; zero IO.
 */
class P806CalendarReducerTest {
    private val ledgerId = LedgerId("ledger-calendar-reducer")
    private val otherLedgerId = LedgerId("ledger-calendar-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val month = YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)
    private val otherMonth = YearMonth(2026, kotlinx.datetime.Month.DECEMBER)
    private val asOf = Instant.parse("2026-11-21T02:00:00Z")
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private fun overview(ledger: LedgerId = ledgerId): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(ledgerId = ledger, transactions = emptyList(), balances = emptyList()),
            selectedTab = P503Tab.HOME,
        )

    private fun day(
        dayOfMonth: Int,
        month: YearMonth = this.month,
        count: Int = 0,
        axis: List<CurrencyUnit> = listOf(cny),
        netExpense: Long = 0L,
    ): P806CalendarDayView =
        P806CalendarDayView(
            date = LocalDate(month.year, month.month, dayOfMonth),
            window = P806Window(1_000L * dayOfMonth, 1_000L * dayOfMonth + 500L),
            currencies = axis.map { currency -> P806CalendarCurrencyView(currency, 0L, netExpense) },
            transactionCount = count,
        )

    private fun view(
        month: YearMonth = this.month,
        ledger: LedgerId = ledgerId,
        days: List<P806CalendarDayView>? = null,
        axis: List<CurrencyUnit> = listOf(cny),
        totalCount: Int = 0,
    ): P806CalendarView {
        val allDays =
            days
                ?: (1..p806CalendarDayCount(month)).map { dayOfMonth -> day(dayOfMonth, month, axis = axis) }
        // A non-zero total gives at least one contributing day so the success face is non-empty.
        val withContribution =
            if (totalCount == 0) {
                allDays
            } else {
                allDays.mapIndexed { index, value -> if (index == 0) value.copy(transactionCount = totalCount) else value }
            }
        return P806CalendarView(ledgerId = ledger, month = month, axis = axis, days = withContribution)
    }

    private fun identity(
        token: Long,
        month: YearMonth = this.month,
        ledger: LedgerId = ledgerId,
    ): P806CalendarRequestIdentity = P806CalendarRequestIdentity(token, ledger, month)

    private fun landed(
        request: P806CalendarRequestIdentity,
        result: P806SnapshotResult<P806CalendarView>,
        generation: Generation = 3,
        catalogVersion: Long = 7L,
        readAsOf: Instant? = asOf,
    ): P806CalendarRead.Landed =
        P806CalendarRead.Landed(
            capture = P806CalendarReadCapture(request = request, generation = generation, catalogVersion = catalogVersion, asOf = readAsOf),
            result = result,
        )

    private fun calendar(
        month: YearMonth = this.month,
        loaded: P806CalendarView? = null,
        loading: Boolean = false,
        failure: P806SnapshotFailure? = null,
        unwired: Boolean = false,
        token: Long = 0L,
        loadedGeneration: Generation? = null,
        loadedCatalogVersion: Long? = null,
        asOf: Instant? = null,
        listScroll: ListScrollPosition? = null,
    ): P503AppState.Calendar =
        P503AppState.Calendar(
            overview = overview(),
            month = month,
            loaded = loaded,
            loading = loading,
            failure = failure,
            unwired = unwired,
            requestToken = token,
            loadedGeneration = loadedGeneration,
            loadedCatalogVersion = loadedCatalogVersion,
            landedAsOf = asOf,
            listScroll = listScroll,
        )

    @Test
    fun openingCarriesTheExactOverviewAndSeedsOnlyTheCalendarCursor() {
        val overview = overview()
        val opened = reducer.reduce(overview, P503UiEvent.OpenCalendar(month))
        val calendar = assertIs<P503AppState.Calendar>(opened)
        assertEquals(month, calendar.month)
        assertTrue(overview === calendar.overview)
        assertNull(calendar.loaded)
        assertNull(calendar.failure)
        assertEquals(0L, calendar.requestToken)
        // The calendar state never writes the overview's shared month cursor.
        assertNull(calendar.overview.selectedMonth)
    }

    @Test
    fun aMonthSelectionMovesOnlyTheCalendarCursor() {
        val state = calendar(month = month)
        val shifted = assertIs<P503AppState.Calendar>(reducer.reduce(state, P503UiEvent.SelectCalendarMonth(otherMonth)))
        assertEquals(otherMonth, shifted.month)
        assertTrue(state.overview === shifted.overview)
        assertNull(shifted.overview.selectedMonth)
        // The same month changes nothing.
        assertTrue(shifted === reducer.reduce(shifted, P503UiEvent.SelectCalendarMonth(otherMonth)))
    }

    @Test
    fun theHostTokenGateKeepsOneReadInFlightAndAcceptsOnlyNewerTokens() {
        val state = calendar(month = month, token = 5L, loading = false)
        val stale = assertIs<P503AppState.Calendar>(reducer.reduce(state, P503UiEvent.RequestCalendarMonth(4L)))
        assertEquals(5L, stale.requestToken)
        assertEquals(false, stale.loading)
        val started = assertIs<P503AppState.Calendar>(reducer.reduce(state, P503UiEvent.RequestCalendarMonth(6L)))
        assertEquals(6L, started.requestToken)
        assertTrue(started.loading)
        assertNull(started.failure)
    }

    @Test
    fun aLandingOfAnOldTokenAMonthTheCursorLeftOrAnotherLedgerChangesNothing() {
        val state = calendar(month = month, token = 5L, loading = true)
        val wrongToken = landed(identity(token = 4), P806SnapshotResult.Success(view()))
        assertTrue(state === reducer.reduce(state, P503UiEvent.CalendarMonthLanded(wrongToken, 3, 7L)))

        val wrongMonth = landed(identity(token = 5, month = otherMonth), P806SnapshotResult.Success(view(otherMonth)))
        assertTrue(state === reducer.reduce(state, P503UiEvent.CalendarMonthLanded(wrongMonth, 3, 7L)))

        val wrongLedger = landed(identity(token = 5, ledger = otherLedgerId), P806SnapshotResult.Success(view(ledger = otherLedgerId)))
        assertTrue(state === reducer.reduce(state, P503UiEvent.CalendarMonthLanded(wrongLedger, 3, 7L)))
    }

    @Test
    fun anAcceptedLandingReplacesTheMonthPayloadAndRecordsItsProvenanceAndAsOf() {
        val state = calendar(month = month, token = 5L, loading = true)
        val read = landed(identity(token = 5), P806SnapshotResult.Success(view(totalCount = 2)))
        val applied = assertIs<P503AppState.Calendar>(reducer.reduce(state, P503UiEvent.CalendarMonthLanded(read, 3, 7L)))
        assertEquals(month, applied.month)
        assertEquals(2, applied.loaded?.days?.first()?.transactionCount)
        assertEquals(false, applied.loading)
        assertNull(applied.failure)
        assertEquals(3, applied.loadedGeneration)
        assertEquals(7L, applied.loadedCatalogVersion)
        assertEquals(asOf, applied.landedAsOf)
    }

    @Test
    fun aTypedFailureEndsTheRequestAndRetainsThePreviousMonthPayload() {
        val previous = view(month = otherMonth, totalCount = 1)
        val state = calendar(month = month, loaded = previous, token = 5L, loading = true)
        val read = landed(identity(token = 5), P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable))
        val applied = assertIs<P503AppState.Calendar>(reducer.reduce(state, P503UiEvent.CalendarMonthLanded(read, 3, 7L)))
        assertEquals(false, applied.loading)
        assertEquals(P806SnapshotFailure.Unavailable, applied.failure)
        assertEquals(previous, applied.loaded)
        // The retained payload still names its OWN month; the cursor stays on the requested month.
        assertEquals(otherMonth, applied.loaded?.month)
        assertEquals(month, applied.month)
    }

    @Test
    fun aGenerationAdvanceACatalogAdvanceAndARejectedPayloadAreDistinctFailures() {
        val previous = view(totalCount = 1)
        val base = calendar(month = month, loaded = previous, token = 5L, loading = true)
        val read = landed(identity(token = 5), P806SnapshotResult.Success(view(totalCount = 1)))

        val advancedGeneration = assertIs<P503AppState.Calendar>(reducer.reduce(base, P503UiEvent.CalendarMonthLanded(read, 4, 7L)))
        assertEquals(false, advancedGeneration.loading)
        assertEquals(P806SnapshotFailure.Unavailable, advancedGeneration.failure)
        assertEquals(previous, advancedGeneration.loaded)

        val advancedCatalog = assertIs<P503AppState.Calendar>(reducer.reduce(base, P503UiEvent.CalendarMonthLanded(read, 3, 8L)))
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, advancedCatalog.failure)
        assertEquals(previous, advancedCatalog.loaded)

        val rejected = landed(identity(token = 5), P806SnapshotResult.Success(view(ledger = otherLedgerId)))
        val malformed = assertIs<P503AppState.Calendar>(reducer.reduce(base, P503UiEvent.CalendarMonthLanded(rejected, 3, 7L)))
        assertEquals(P806SnapshotFailure.InvalidState, malformed.failure)
        assertEquals(previous, malformed.loaded)
    }

    @Test
    fun refusedAndUnwiredLandTheirOwnDistinguishableFaces() {
        val base = calendar(month = month, token = 5L, loading = true)
        val refused = assertIs<P503AppState.Calendar>(reducer.reduce(base, P503UiEvent.CalendarMonthLanded(P806CalendarRead.Refused(identity(token = 5)), null, null)))
        assertEquals(false, refused.loading)
        assertEquals(P806SnapshotFailure.Unavailable, refused.failure)

        val unwired = assertIs<P503AppState.Calendar>(reducer.reduce(base, P503UiEvent.CalendarMonthLanded(P806CalendarRead.Unwired(identity(token = 5), 3), 3, null)))
        assertEquals(false, unwired.loading)
        assertTrue(unwired.unwired)
    }

    @Test
    fun closeAndBackReturnTheSamePreservedOverview() {
        val overview = overview()
        val state = P503AppState.Calendar(overview = overview, month = month)
        assertTrue(overview === reducer.reduce(state, P503UiEvent.CloseCalendar))
        assertTrue(overview === reducer.reduce(state, P503UiEvent.Back))
    }

    @Test
    fun aDayClickOpensTheSharedDetailPageWithTheDaysOwnWindowAndTheCalendarAsReturnTarget() {
        val day = day(dayOfMonth = 5, count = 2, netExpense = -1_250L)
        val scroll = ListScrollPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 640)
        val state = calendar(month = month, loaded = view(days = listOf(day)), listScroll = null)
        val opened =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(
                    state,
                    P503UiEvent.OpenReportDrilldown(
                        target = ReportDrilldownTarget.Day(window = day.window, date = day.date),
                        originCalendarScroll = scroll,
                    ),
                ),
            )
        assertEquals(ReportDrilldownTarget.Day(window = day.window, date = day.date), opened.target)
        assertTrue(state.overview === opened.overview)
        assertEquals(month, opened.returnToCalendar?.month)
        assertEquals(state.loaded, opened.returnToCalendar?.loaded)
        assertEquals(scroll, opened.returnToCalendar?.listScroll)
        // Closing the day detail (before any row detail) returns that same calendar snapshot.
        val returned = assertIs<P503AppState.Calendar>(reducer.reduce(opened, P503UiEvent.CloseReportDrilldown))
        assertEquals(month, returned.month)
        assertEquals(state.loaded, returned.loaded)
        assertEquals(scroll, returned.listScroll)
    }

    @Test
    fun aMonthlyLandingWhileADayTransactionDetailIsOpenReachesTheCalendarThroughTwoCloses() {
        // F-1 regression: the day detail carries BOTH the drilldown and the calendar; a valid
        // monthly landing must write the nested calendar overview too, so the second close returns
        // the fresh overview beside the exact month/payload/scroll.
        val day = day(dayOfMonth = 5, count = 2)
        val scroll = ListScrollPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 640)
        val state = calendar(month = month, loaded = view(days = listOf(day)), token = 5L)
        val drilled =
            assertIs<P503AppState.ReportDrilldown>(
                reducer.reduce(
                    state,
                    P503UiEvent.OpenReportDrilldown(
                        target = ReportDrilldownTarget.Day(window = day.window, date = day.date),
                        originCalendarScroll = scroll,
                    ),
                ),
            )
        val detail =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    drilled,
                    P503UiEvent.SelectTransaction(
                        transactionId = TransactionId("tx-1"),
                        result = com.unifiedledger.application.TransactionDetailResult.NotFound,
                    ),
                ),
            )
        val activity = MonthlyActivity(ledgerId, month, emptyList(), emptyList(), emptyList())
        val landed =
            assertIs<P503AppState.TransactionDetail>(
                reducer.reduce(
                    detail,
                    P503UiEvent.MonthlyActivityResult(
                        com.unifiedledger.application.MonthlyActivityResult.Success(activity),
                        emptyList(),
                    ),
                ),
            )
        val carried = assertIs<TransactionDetailReturnTo.ReportDrilldown>(landed.returnTo).carriedDrilldown
        assertEquals(activity, carried.overview.monthlyActivity)
        assertEquals(activity, carried.returnToCalendar?.overview?.monthlyActivity)
        // First close: the drilldown, with the calendar still carrying the fresh overview.
        val backToDrilldown = assertIs<P503AppState.ReportDrilldown>(reducer.reduce(landed, P503UiEvent.CloseTransactionDetail))
        assertEquals(activity, backToDrilldown.returnToCalendar?.overview?.monthlyActivity)
        // Second close: the same calendar month, payload and sampled scroll, now with the fresh overview.
        val backToCalendar = assertIs<P503AppState.Calendar>(reducer.reduce(backToDrilldown, P503UiEvent.CloseReportDrilldown))
        assertEquals(month, backToCalendar.month)
        assertEquals(state.loaded, backToCalendar.loaded)
        assertEquals(scroll, backToCalendar.listScroll)
        assertEquals(activity, backToCalendar.overview.monthlyActivity)
    }

    @Test
    fun aPeriodOrCategoryTargetIsAbsorbedOnTheCalendar() {
        val state = calendar(month = month)
        assertTrue(
            state ===
                reducer.reduce(
                    state,
                    P503UiEvent.OpenReportDrilldown(ReportDrilldownTarget.Period(P806Window(1_000L, 2_000L))),
                ),
        )
    }

    @Test
    fun theCalendarScrollSlotIsConsumedExactlyOnce() {
        val scroll = ListScrollPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 100)
        val state = calendar(month = month, listScroll = scroll)
        val cleared = assertIs<P503AppState.Calendar>(reducer.reduce(state, P503UiEvent.ListScrollRestored))
        assertNull(cleared.listScroll)
        assertTrue(cleared === reducer.reduce(cleared, P503UiEvent.ListScrollRestored))
    }

    @Test
    fun theCalendarDoesNotAbsorbAPeriodDrilldownsCloseEvent() {
        // The calendar family is state-preserving outside its own states: another surface's Close
        // cannot close the calendar by accident (it never reaches `unhandled`).
        val state = calendar(month = month)
        val closed = reducer.reduce(state, P503UiEvent.CloseReportDrilldown)
        assertTrue(state === closed)
    }
}
