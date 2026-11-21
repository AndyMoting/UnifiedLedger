package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.P806CalendarCurrencyView
import com.unifiedledger.application.P806CalendarDayView
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806Window
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch 4 (D-362 1; spec sections 3.7/3.8/3.10): the calendar's pure presentation decisions —
 * the six faces, the initial month, the month label/step, the COMPLETE grid geometry (leading
 * blanks, month ends and leap years) and the per-cell accessibility text. No Compose runtime,
 * anonymous synthetic data only.
 */
class P806CalendarPresentationTest {
    private val ledgerId = LedgerId("ledger-calendar-presentation")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val nov = YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)
    private val dec = YearMonth(2026, kotlinx.datetime.Month.DECEMBER)
    private val asOf = Instant.parse("2026-11-21T02:00:00Z")

    private fun overview(): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(ledgerId = ledgerId, transactions = emptyList(), balances = emptyList()),
            selectedTab = P503Tab.HOME,
        )

    private fun day(
        dayOfMonth: Int,
        month: YearMonth = nov,
        count: Int = 0,
        axis: List<CurrencyUnit> = listOf(cny),
        income: Long = 0L,
        expense: Long = 0L,
    ): P806CalendarDayView =
        P806CalendarDayView(
            date = LocalDate(month.year, month.month, dayOfMonth),
            window = P806Window(1_000L, 2_000L),
            currencies = axis.map { currency -> P806CalendarCurrencyView(currency, income, expense) },
            transactionCount = count,
        )

    private fun view(
        month: YearMonth = nov,
        days: List<P806CalendarDayView>? = null,
        axis: List<CurrencyUnit> = listOf(cny),
    ): P806CalendarView =
        P806CalendarView(
            ledgerId = ledgerId,
            month = month,
            axis = axis,
            days = days ?: (1..p806CalendarDayCount(month)).map { dayOfMonth -> day(dayOfMonth, month, axis = axis) },
        )

    private fun state(
        loaded: P806CalendarView? = null,
        loading: Boolean = false,
        failure: P806SnapshotFailure? = null,
        unwired: Boolean = false,
        month: YearMonth = nov,
        asOf: Instant? = null,
    ): P503AppState.Calendar =
        P503AppState.Calendar(
            overview = overview(),
            month = month,
            loaded = loaded,
            loading = loading,
            failure = failure,
            unwired = unwired,
            landedAsOf = asOf,
        )

    @Test
    fun theSixFacesAreDistinctAndKeepThePreviousPayloadBesideAFailure() {
        assertEquals(P806CalendarFace.UNWIRED, p806CalendarFace(state(unwired = true, loaded = view())))
        assertEquals(P806CalendarFace.NOT_LOADED, p806CalendarFace(state()))
        assertEquals(P806CalendarFace.LOADING, p806CalendarFace(state(loading = true)))
        assertEquals(
            P806CalendarFace.FAILED,
            p806CalendarFace(state(loaded = view(), failure = P806SnapshotFailure.Unavailable)),
        )
        assertEquals(P806CalendarFace.READY_EMPTY, p806CalendarFace(state(loaded = view())))
        assertEquals(
            P806CalendarFace.READY_NON_EMPTY,
            p806CalendarFace(state(loaded = view(days = (1..30).map { day(it, count = if (it == 3) 2 else 0) }))),
        )
    }

    @Test
    fun theInitialMonthIsTheSelectedMonthElseTheResolvedCurrentMonth() {
        val selected = YearMonth(2026, kotlinx.datetime.Month.AUGUST)
        assertEquals(selected, p806CalendarInitialMonth(selected, dec))
        assertEquals(dec, p806CalendarInitialMonth(null, dec))
        assertNull(p806CalendarInitialMonth(null, null))
    }

    @Test
    fun theEntryNeedsBothAWiredReadAndAResolvableInitialMonth() {
        val selected = YearMonth(2026, kotlinx.datetime.Month.AUGUST)
        // Wired + either source: the entry may render (the selected month wins, so a missing clock
        // does not hide a selected month).
        assertTrue(p806CalendarEntryAvailable(wired = true, selectedMonth = selected, resolvedCurrentMonth = null))
        assertTrue(p806CalendarEntryAvailable(wired = true, selectedMonth = null, resolvedCurrentMonth = dec))
        assertTrue(p806CalendarEntryAvailable(wired = true, selectedMonth = selected, resolvedCurrentMonth = dec))
        // Wired but no month at all: no entry (never a guessed month).
        assertTrue(!p806CalendarEntryAvailable(wired = true, selectedMonth = null, resolvedCurrentMonth = null))
        // Unwired: no entry even with a month.
        assertTrue(!p806CalendarEntryAvailable(wired = false, selectedMonth = selected, resolvedCurrentMonth = dec))
    }

    @Test
    fun theMonthLabelAndStepAreStableAndPure() {
        assertEquals("2026年11月", p806CalendarMonthLabel(nov))
        assertEquals(dec, p806CalendarShiftedMonth(nov, 1))
        assertEquals(YearMonth(2026, kotlinx.datetime.Month.OCTOBER), p806CalendarShiftedMonth(nov, -1))
        assertEquals(YearMonth(2027, kotlinx.datetime.Month.JANUARY), p806CalendarShiftedMonth(dec, 1))
        assertEquals(YearMonth(2026, kotlinx.datetime.Month.JANUARY), p806CalendarShiftedMonth(YearMonth(2025, kotlinx.datetime.Month.DECEMBER), 1))
    }

    @Test
    fun theGridCoversEveryDateOfNovember2026WithMondayFirstAndSixLeadingBlanks() {
        val grid = p806CalendarGrid(nov, view().days)
        // 2026-11-01 is a Sunday, so a Monday-first grid starts with six blanks.
        assertEquals(6, grid.leadingBlanks)
        assertEquals(6, grid.weeks.size)
        assertTrue(grid.weeks.first().take(6).all { it == null })
        assertEquals(LocalDate(2026, 11, 1), grid.weeks.first()[6]?.date)
        // 2026-11-30 is a Monday, so it opens the final Monday-first row (column 0), and the rest of
        // that row is padding.
        assertEquals(LocalDate(2026, 11, 30), grid.weeks.last()[0]?.date)
        assertNull(grid.weeks.last()[1])
        assertNull(grid.weeks.last()[6])
        assertEquals(30, grid.weeks.flatten().count { it != null })
    }

    @Test
    fun aLeapFebruaryAndAThirtyOneDayMonthKeepEveryCellInBounds() {
        val leap = YearMonth(2028, kotlinx.datetime.Month.FEBRUARY)
        val leapGrid = p806CalendarGrid(leap, view(month = leap).days)
        assertEquals(1, leapGrid.leadingBlanks) // 2028-02-01 is a Tuesday.
        assertEquals(5, leapGrid.weeks.size)
        assertEquals(29, leapGrid.weeks.flatten().count { it != null })
        assertEquals(LocalDate(2028, 2, 29), leapGrid.weeks[4][1]?.date)
        assertEquals(7, leapGrid.weeks[4].size)

        val january = YearMonth(2026, kotlinx.datetime.Month.JANUARY)
        val januaryGrid = p806CalendarGrid(january, view(month = january).days)
        assertEquals(31, januaryGrid.weeks.flatten().count { it != null })
        assertEquals(LocalDate(2026, 1, 31), januaryGrid.weeks.flatten().filterNotNull().last().date)
        assertTrue(januaryGrid.weeks.all { it.size == 7 })
    }

    @Test
    fun aZeroDayStillHasEveryAxisCurrencyZeroAndAZeroCount() {
        val zero = day(dayOfMonth = 5, axis = listOf(cny, usd))
        val text = p806CalendarCellContentDescription(zero)
        assertTrue(text.startsWith("2026-11-05"))
        assertTrue(text.contains("CNY 支出 0.00、收入 0.00"))
        assertTrue(text.contains("USD 支出 0.00、收入 0.00"))
        assertTrue(text.contains("共 0 笔"))
    }

    @Test
    fun theCellTextKeepsCurrenciesSplitAndShowsExactSignedAmountsAndTheGlobalCount() {
        val multi =
            day(
                dayOfMonth = 6,
                count = 2,
                axis = listOf(cny, usd),
                income = 500L,
                expense = -1_250L,
            )
        val text = p806CalendarCellContentDescription(multi)
        assertTrue(text.contains("CNY 支出 -12.50、收入 5.00"))
        assertTrue(text.contains("USD 支出 -12.50、收入 5.00"))
        assertTrue(text.contains("共 2 笔"))
        // No merged total and no conversion: each currency carries its own exact pair.
        assertTrue(!text.contains("合并") && !text.contains("折算"))
    }

    @Test
    fun theIntensityIsAuxiliaryDeterministicAndZeroForNonPositiveOrZeroMax() {
        assertEquals(0, p806CalendarIntensityLevel(0L, 1_000L))
        assertEquals(0, p806CalendarIntensityLevel(-500L, 1_000L))
        assertEquals(0, p806CalendarIntensityLevel(500L, 0L))
        assertEquals(1, p806CalendarIntensityLevel(1L, 1_000L))
        assertEquals(1, p806CalendarIntensityLevel(250L, 1_000L))
        assertEquals(2, p806CalendarIntensityLevel(251L, 1_000L))
        assertEquals(4, p806CalendarIntensityLevel(1_000L, 1_000L))
        assertTrue((1..4).all { level -> p806CalendarIntensityLevel(level * 100L, 500L) in 1..4 })
    }

    @Test
    fun theOldContextLabelNamesTheRetainedPayloadsOwnMonthOnAFailure() {
        val previous = view(month = dec)
        val failed = state(loaded = previous, failure = P806SnapshotFailure.Unavailable, month = nov, asOf = asOf)
        val label = p806CalendarOldContextText(failed) ?: error("a retained payload must be labelled")
        assertTrue(label.contains("2026年12月"))
        assertTrue(label.contains("可能已过期"))
        assertTrue(label.contains(asOf.toString()))
        // A READY success is not old context.
        assertNull(p806CalendarOldContextText(state(loaded = view())))
    }

    @Test
    fun aRetainedPayloadOfAnotherMonthIsLabelledEvenWithNoReadInFlightOrFailure() {
        // A month switch keeps the previous month's success visible until the new landing; the label
        // must appear from the month mismatch ALONE (not only from loading/failure).
        val label = p806CalendarOldContextText(state(loaded = view(month = dec), month = nov)) ?: error("a mismatched month must be labelled")
        assertTrue(label.contains("2026年12月"))
        assertTrue(label.contains("可能已过期"))
        // The same payload month as the cursor, with no request and no failure, stays unlabelled.
        assertNull(p806CalendarOldContextText(state(loaded = view(month = nov), month = nov)))
    }

    @Test
    fun everyTypedFailureHasItsOwnCalendarCopy() {
        val failures =
            listOf(
                P806SnapshotFailure.Unavailable,
                P806SnapshotFailure.CatalogVersionMismatch,
                P806SnapshotFailure.MissingProjection,
                P806SnapshotFailure.OccurredAtUnparseable,
                P806SnapshotFailure.InvalidWindow,
                P806SnapshotFailure.InvalidState,
                P806SnapshotFailure.CatalogReference(unresolvedAccount = 1L, crossLedgerAccount = 0L, currencyMismatch = 0L),
            )
        val texts = failures.map(::p806CalendarFailureText)
        assertEquals(texts.size, texts.distinct().size)
        assertTrue(texts.none { it.isBlank() })
    }
}
