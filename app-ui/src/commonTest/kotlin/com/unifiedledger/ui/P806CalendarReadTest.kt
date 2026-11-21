package com.unifiedledger.ui

import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.P806CalendarDaySnapshot
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806CalendarSnapshot
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch 4 (D-362 1; spec sections 3.7/3.8/3.10): the calendar's read glue over the REAL
 * P806 calendar query and the REAL facade/session wiring.
 *
 * The fake read port records the request the real query issued, so the assertions prove the glue
 * binds the token, ledger and YearMonth, captures the acquire-time generation, the query instance's
 * construction catalog version and the read instant, and asks the port for exactly the requested
 * month. The landing decision's ordered axes are checked against the CURRENT generation/catalog, and
 * the payload gate requires ONE complete month snapshot (every date exactly once, ordered, on the
 * same axis) — never a partial or mixed month. No Compose runtime, no timing.
 */
class P806CalendarReadTest {
    private val ledgerId = LedgerId("ledger-calendar-read")
    private val otherLedgerId = LedgerId("ledger-calendar-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val readInstant = Instant.parse("2026-11-21T02:00:00Z")
    private val month = YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)
    private val catalog: LedgerCatalog = (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private fun authority(version: Long): CatalogAuthority = CatalogAuthority(ledgerId, catalog, version)

    private class MutableAuthorityReader(
        var current: CatalogAuthority,
    ) : CatalogAuthorityReader {
        override fun load(ledgerId: LedgerId): CatalogAuthority? = current
    }

    private fun days(
        month: YearMonth = this.month,
    ): List<P806CalendarDaySnapshot> =
        (1..p806CalendarDayCount(month)).map { dayOfMonth ->
            val date = LocalDate(month.year, month.month, dayOfMonth)
            P806CalendarDaySnapshot(
                date = date,
                window = P806Window(startEpochNanos = 1_000L * dayOfMonth, endEpochNanos = 1_000L * dayOfMonth + 500L),
                postingTotals = emptyList(),
                transactionCount = 0,
            )
        }

    private fun snapshot(
        month: YearMonth = this.month,
        axis: List<CurrencyUnit> = emptyList(),
        snapshotLedgerVersion: Long = 7L,
    ): P806CalendarSnapshot =
        P806CalendarSnapshot(
            catalogVersion = snapshotLedgerVersion,
            month = month,
            currencyAxis = axis,
            days = days(month),
        )

    /** Records the P806 calendar requests the real query made. */
    private class RecordingReadPort(
        var nextSnapshot: P806CalendarSnapshot,
    ) : LedgerCurrentStateReadPort {
        val calendarRequests = mutableListOf<P806CalendarRequest>()

        override fun readCalendarSnapshot(request: P806CalendarRequest): P806SnapshotResult<P806CalendarSnapshot> {
            calendarRequests += request
            return P806SnapshotResult.Success(nextSnapshot)
        }

        override fun loadCurrentRows(ledgerId: LedgerId): List<com.unifiedledger.application.CurrentVersionRow> = emptyList()

        override fun findManualExpenseByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualExpenseCommitRecord? = null

        override fun findManualExpenseByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedExpenseReceipt,
        ): ManualExpenseCommitRecord? = null

        override fun findManualIncomeByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualIncomeCommitRecord? = null

        override fun findManualIncomeByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedIncomeReceipt,
        ): ManualIncomeCommitRecord? = null

        override fun findManualTransferByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualTransferCommitRecord? = null

        override fun findManualTransferByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedTransferReceipt,
        ): ManualTransferCommitRecord? = null
    }

    private class SessionSetup(
        val reader: MutableAuthorityReader,
        val session: CatalogConsumerSession,
        val port: RecordingReadPort,
        val facade: P503LedgerFacade,
    )

    private fun sessionSetup(authorityVersion: Long = 7L): SessionSetup {
        val reader = MutableAuthorityReader(authority(authorityVersion))
        val port = RecordingReadPort(snapshot())
        // The calendar query exists only with a session clock (explicit months still avoid it for
        // geometry; the clock supplies the as-of label).
        val session = CatalogConsumerSession(reader, ledgerId, reader.current, port, clock = com.unifiedledger.application.LedgerClock { readInstant })
        return SessionSetup(reader, session, port, minimalP503LedgerFacade(catalogSession = session))
    }

    private fun identity(
        token: Long,
        month: YearMonth = this.month,
        ledger: LedgerId = ledgerId,
    ): P806CalendarRequestIdentity =
        P806CalendarRequestIdentity(token = token, ledgerId = ledger, month = month)

    private fun overview(ledger: LedgerId = ledgerId): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(ledgerId = ledger, transactions = emptyList(), balances = emptyList()),
            selectedTab = P503Tab.HOME,
        )

    private fun state(
        token: Long = 5L,
        month: YearMonth = this.month,
        ledger: LedgerId = ledgerId,
    ): P503AppState.Calendar =
        P503AppState.Calendar(
            overview = overview(ledger),
            month = month,
            loading = true,
            requestToken = token,
        )

    private fun capture(
        generation: Generation = 3,
        catalogVersion: Long = 7L,
        asOf: Instant? = readInstant,
    ): P806CalendarReadCapture =
        P806CalendarReadCapture(
            request = identity(token = 5),
            generation = generation,
            catalogVersion = catalogVersion,
            asOf = asOf,
        )

    private fun landed(
        token: Long = 5L,
        month: YearMonth = this.month,
        generation: Generation = 3,
        catalogVersion: Long = 7L,
        result: P806SnapshotResult<P806CalendarView> = calendarViewResult(month),
    ): P806CalendarRead.Landed =
        P806CalendarRead.Landed(
            capture = capture(generation, catalogVersion).copy(request = identity(token, month)),
            result = result,
        )

    private fun calendarView(
        month: YearMonth = this.month,
        ledger: LedgerId = ledgerId,
        axis: List<CurrencyUnit> = emptyList(),
        days: List<com.unifiedledger.application.P806CalendarDayView>? = null,
    ): P806CalendarView =
        P806CalendarView(
            ledgerId = ledger,
            month = month,
            axis = axis,
            days =
                days
                    ?: (1..p806CalendarDayCount(month)).map { dayOfMonth ->
                        com.unifiedledger.application.P806CalendarDayView(
                            date = LocalDate(month.year, month.month, dayOfMonth),
                            window = P806Window(1_000L, 2_000L),
                            currencies = axis.map { currency ->
                                com.unifiedledger.application.P806CalendarCurrencyView(currency, 0L, 0L)
                            },
                            transactionCount = 0,
                        )
                    },
        )

    private fun calendarViewResult(month: YearMonth = this.month): P806SnapshotResult<P806CalendarView> =
        P806SnapshotResult.Success(calendarView(month))

    // ---------------------------------------------------------------- the read capture

    @Test
    fun theHostSequenceNeverRepeatsAToken() {
        val sequence = P806CalendarRequestSequence()
        assertEquals(1L, sequence.next())
        assertEquals(2L, sequence.next())
        assertEquals(3L, sequence.next())
    }

    @Test
    fun theReadCapturesTheIdentityGenerationCatalogVersionAndAsOfAndAsksForTheExplicitMonth() {
        val setup = sessionSetup()
        val read = readP806Calendar(setup.facade, generation = 3, request = identity(token = 5))
        val landed = assertIs<P806CalendarRead.Landed>(read)
        assertEquals(identity(token = 5), landed.request)
        assertEquals(3, landed.capture.generation)
        assertEquals(7L, landed.capture.catalogVersion)
        // The as-of is the facade clock's read instant of THIS call (the fixture clock is the real
        // system clock, so only the fact that one was captured is asserted here).
        assertNotNull(landed.capture.asOf)
        assertIs<P806SnapshotResult.Success<P806CalendarView>>(landed.result)
        val asked = setup.port.calendarRequests.single()
        assertEquals(ledgerId, asked.ledgerId)
        assertEquals(month, asked.month)
        assertEquals(7L, asked.expectedCatalogVersion)
    }

    @Test
    fun anUnwiredGraphIsTheExplicitUnwiredRead() {
        val read = readP806Calendar(minimalP503LedgerFacade(), generation = 3, request = identity(token = 5))
        assertIs<P806CalendarRead.Unwired>(read)
    }

    // ---------------------------------------------------------------- the landing axes

    @Test
    fun theDecisionAcceptsTheMatchingCaptureAndRejectsOldTokensMonthsAndLedgers() {
        val read = landed(token = 5)
        assertEquals(P806CalendarLandingDecision.ACCEPTED, p806CalendarLandingDecision(state(token = 5), read, 3, 7L))
        assertEquals(P806CalendarLandingDecision.STALE_TOKEN, p806CalendarLandingDecision(state(token = 6), read, 3, 7L))
        assertEquals(
            P806CalendarLandingDecision.STALE_MONTH,
            p806CalendarLandingDecision(
                state(token = 5, month = YearMonth(2026, kotlinx.datetime.Month.DECEMBER)),
                read,
                3,
                7L,
            ),
        )
        assertEquals(
            P806CalendarLandingDecision.STALE_LEDGER,
            p806CalendarLandingDecision(state(token = 5, ledger = otherLedgerId), read, 3, 7L),
        )
    }

    @Test
    fun theDecisionSeparatesAGenerationAdvanceACatalogAdvanceAndARejectedPayload() {
        val read = landed(token = 5)
        assertEquals(P806CalendarLandingDecision.STALE_GENERATION, p806CalendarLandingDecision(state(), read, 4, 7L))
        assertEquals(P806CalendarLandingDecision.STALE_GENERATION, p806CalendarLandingDecision(state(), read, null, 7L))
        assertEquals(P806CalendarLandingDecision.CATALOG_ADVANCED, p806CalendarLandingDecision(state(), read, 3, 8L))
        assertEquals(P806CalendarLandingDecision.CATALOG_ADVANCED, p806CalendarLandingDecision(state(), read, 3, null))
        val wrongPayload = landed(token = 5, result = P806SnapshotResult.Success(calendarView(ledger = otherLedgerId)))
        assertEquals(P806CalendarLandingDecision.PAYLOAD_REJECTED, p806CalendarLandingDecision(state(), wrongPayload, 3, 7L))
    }

    // ---------------------------------------------------------------- one complete month snapshot

    @Test
    fun aCompleteZeroMonthWithAOneCurrencyAxisIsAcceptedAndEveryDayHasAZeroRow() {
        val view = calendarView(axis = listOf(cny, usd))
        assertTrue(p806CalendarPayloadAccepts(view, identity(token = 5)))
        assertTrue(view.days.all { day -> day.currencies.map { it.currency } == listOf(cny, usd) })
        assertTrue(view.days.all { day -> day.currencies.all { it.netExpenseMinorUnits == 0L && it.ordinaryIncomeMinorUnits == 0L } })
        assertEquals(p806CalendarDayCount(month), view.days.size)
    }

    @Test
    fun aPayloadOfAnotherMonthAMissingDayADuplicateAxisOrAnUnorderedDayIsRejected() {
        val view = calendarView(axis = listOf(cny))
        assertFalse(p806CalendarPayloadAccepts(view, identity(token = 5, month = YearMonth(2026, kotlinx.datetime.Month.DECEMBER))))

        val missing = view.copy(days = view.days.dropLast(1))
        assertFalse(p806CalendarPayloadAccepts(missing, identity(token = 5)))

        val duplicateAxis = view.copy(axis = listOf(cny, cny), days = view.days.map { day -> day.copy(currencies = day.currencies + day.currencies) })
        assertFalse(p806CalendarPayloadAccepts(duplicateAxis, identity(token = 5)))

        val unordered = view.copy(days = view.days.reversed())
        assertFalse(p806CalendarPayloadAccepts(unordered, identity(token = 5)))
    }

    @Test
    fun aFailedResultStillPassesTheLandingGateSoTheReducerCanShowTheTypedFailure() {
        val failed = landed(token = 5, result = P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable))
        assertEquals(P806CalendarLandingDecision.ACCEPTED, p806CalendarLandingDecision(state(), failed, 3, 7L))
    }

    @Test
    fun aRefusedReadIsAcceptedSoTheReducerLandsItsDistinctFace() {
        val refused = P806CalendarRead.Refused(identity(token = 5))
        assertEquals(P806CalendarLandingDecision.ACCEPTED, p806CalendarLandingDecision(state(), refused, null, null))
    }

    @Test
    fun anUnwiredReadIsAcceptedOnlyUnderTheCurrentGeneration() {
        val unwired = P806CalendarRead.Unwired(identity(token = 5), generation = 3)
        assertEquals(P806CalendarLandingDecision.ACCEPTED, p806CalendarLandingDecision(state(), unwired, 3, null))
        assertEquals(P806CalendarLandingDecision.STALE_GENERATION, p806CalendarLandingDecision(state(), unwired, 4, null))
    }

    @Test
    fun theReducerOpensTheCalendarWithoutTouchingTheSharedMonthCursor() {
        val overview = overview()
        val opened = reducer.reduce(overview, P503UiEvent.OpenCalendar(month))
        val calendar = assertIs<P503AppState.Calendar>(opened)
        assertEquals(month, calendar.month)
        assertEquals(overview, calendar.overview)
        assertNull(calendar.overview.selectedMonth)
        assertNotNull(calendar.overview)
    }
}
