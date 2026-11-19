package com.unifiedledger.ui

import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.CatalogAuthorityReader
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.P806AccountCurrencyTotal
import com.unifiedledger.application.P806AccountRequest
import com.unifiedledger.application.P806AccountSnapshot
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806CalendarSnapshot
import com.unifiedledger.application.P806CurrencyTransactionCount
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806DrilldownSnapshot
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806PeriodResolution
import com.unifiedledger.application.P806Periods
import com.unifiedledger.application.P806ReportRequest
import com.unifiedledger.application.P806ReportSnapshot
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806Span
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch b (D-343 sections 3.1/3.5/3.7): the report read glue over the REAL P806 report query,
 * the REAL catalog session and the REAL facade wiring, plus the REAL owner lease.
 *
 * The fake read port ANSWERS FROM THE REQUEST the way the production reader does (its window is the
 * window the application resolves), so the assertions prove (a) one request is one background
 * snapshot read through the acquire-first lease, (b) the capture binds the request identity, the
 * lease generation, the query INSTANCE's construction catalog version and the application read
 * instant, (c) the landing decision compares those captures against the CURRENT values and rejects a
 * payload that does not belong to one snapshot, and (d) the same-snapshot window/shape verification
 * rejects a divergent or half-shaped reader while the honest no-period payload still answers.
 *
 * Every expectation is computed from the application-layer geometry ([P806Periods]) — the test never
 * re-implements the Shanghai window conversion. No Compose runtime, no timing, anonymous synthetic
 * amounts.
 */
class P806ReportReadTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val otherLedgerId = LedgerId("ledger-other")
    private val expenseAccountId = AccountId("expense-food")
    private val cny = CurrencyUnit("CNY", 2)

    /** 2026-11-18 10:00 Asia/Shanghai: the caller's "today" for every relative period. */
    private val readInstant = Instant.parse("2026-11-18T02:00:00Z")
    private val today = LocalDate(2026, 11, 18)
    private val clock = LedgerClock { readInstant }

    private val catalog: LedgerCatalog =
        (
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(
                            id = expenseAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.EXPENSE,
                            currency = cny,
                            ownedByUser = true,
                            realAccount = false,
                            name = "餐饮",
                        ),
                    ),
                categories = emptyList(),
            ) as DomainResult.Success
        ).value

    private fun authority(version: Long): CatalogAuthority = CatalogAuthority(ledgerId, catalog, version)

    private class MutableAuthorityReader(
        var current: CatalogAuthority,
    ) : CatalogAuthorityReader {
        override fun load(ledgerId: LedgerId): CatalogAuthority? = current
    }

    /** One ordinary expense of 30.00 CNY on the EXPENSE account (a positive debit leg). */
    private fun expenseTotal(
        positive: Long = 3_000L,
        refund: Long = 0L,
    ): P806AccountCurrencyTotal =
        P806AccountCurrencyTotal(
            accountId = expenseAccountId,
            currency = cny,
            positiveMinorUnits = positive,
            refundMinorUnits = refund,
            totalMinorUnits = positive + refund,
        )

    /** The ledger span every fixture reads BESIDE its payload (2026-11-01 through 2026-11-03). */
    private fun span(
        earliest: String = "2026-11-01T00:00:00Z",
        latest: String = "2026-11-03T02:00:00Z",
    ): P806Span = P806Span(earliest = Instant.parse(earliest), latest = Instant.parse(latest))

    /** The window the application resolves for [period] — what a production reader answers with. */
    private fun windowFor(
        period: P806Period,
        span: P806Span?,
    ): P806Window? =
        when (val resolution = P806Periods.resolve(period, today, span)) {
            is P806PeriodResolution.Resolved -> resolution.period.window
            P806PeriodResolution.InvalidWindow,
            P806PeriodResolution.NotApplicable,
            -> null
        }

    private fun reportSnapshot(
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        span: P806Span? = span(),
        axis: List<CurrencyUnit> = listOf(cny),
        totals: List<P806AccountCurrencyTotal> = listOf(expenseTotal()),
        transactionCount: Int = 4,
        currencyCounts: List<P806CurrencyTransactionCount> = listOf(P806CurrencyTransactionCount(cny, 4)),
        window: P806Window? = windowFor(period, span),
    ): P806ReportSnapshot =
        P806ReportSnapshot(
            catalogVersion = 7L,
            window = window,
            span = span,
            currencyAxis = axis,
            postingTotals = totals,
            transactionCount = transactionCount,
            currencyCounts = currencyCounts,
            futureTransactionCount = 0,
            firstPage = P806TransactionPage(rows = emptyList(), nextCursor = null),
            detailCount = transactionCount,
        )

    /** Records EVERY report request the real query made and answers from the request itself. */
    private class RecordingReadPort(
        var answer: (P806ReportRequest) -> P806SnapshotResult<P806ReportSnapshot>,
    ) : LedgerCurrentStateReadPort {
        val reportRequests = mutableListOf<P806ReportRequest>()

        override fun readReportSnapshot(request: P806ReportRequest): P806SnapshotResult<P806ReportSnapshot> {
            reportRequests += request
            return answer(request)
        }

        override fun readCalendarSnapshot(request: P806CalendarRequest): P806SnapshotResult<P806CalendarSnapshot> = error("the report surface never reads a calendar snapshot")

        override fun readDrilldownSnapshot(request: P806DrilldownRequest): P806SnapshotResult<P806DrilldownSnapshot> = error("the report surface never reads a drilldown snapshot")

        override fun readAccountPostingsSnapshot(request: P806AccountRequest): P806SnapshotResult<P806AccountSnapshot> = error("the report surface never reads an account snapshot")

        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

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

    /** The lease seam stand-in: the fake supplies the generation the owner would capture. */
    private class Seam(
        var generation: Generation,
        var facade: P503LedgerFacade?,
    ) : P806ReportLeaseSeam {
        override fun <T> leasedReportRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T> {
            val current = facade ?: return LeaseOutcome.NotReady
            return LeaseOutcome.Completed(generation, block(current, generation))
        }
    }

    private class SessionSetup(
        val reader: MutableAuthorityReader,
        val session: CatalogConsumerSession,
        val port: RecordingReadPort,
        val facade: P503LedgerFacade,
    )

    private fun sessionSetup(
        authorityVersion: Long = 7L,
        withClock: Boolean = true,
        answer: (P806ReportRequest) -> P806SnapshotResult<P806ReportSnapshot> = { request ->
            P806SnapshotResult.Success(reportSnapshot(period = request.period))
        },
    ): SessionSetup {
        val reader = MutableAuthorityReader(authority(authorityVersion))
        val port = RecordingReadPort(answer)
        val session =
            CatalogConsumerSession(
                reader = reader,
                ledgerId = ledgerId,
                initialAuthority = reader.current,
                readPort = port,
                clock = if (withClock) clock else null,
            )
        return SessionSetup(reader, session, port, minimalP503LedgerFacade(catalogSession = session))
    }

    private fun identity(
        token: Long,
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        ledger: LedgerId = ledgerId,
    ): P806ReportRequestIdentity =
        P806ReportRequestIdentity(
            token = token,
            ledgerId = ledger,
            period = period,
        )

    private fun overview(
        slot: P806ReportRegionState?,
        ledger: LedgerId = ledgerId,
    ): P503AppState.OverviewEmpty =
        P503AppState.OverviewEmpty(
            state = LedgerCurrentState(ledgerId = ledger, transactions = emptyList(), balances = emptyList()),
            selectedTab = P503Tab.ANALYSIS,
            reportRegion = slot,
        )

    private fun loadingSlot(
        token: Long = 5L,
        period: P806Period = P806Period(P806PeriodKind.MONTH),
        landed: P806ReportView? = null,
    ): P806ReportRegionState = P806ReportRegionState(period = period, kind = period.kind, requestToken = token, loading = true, landed = landed)

    // ---------------------------------------------------------------- the read itself (acceptance A)

    @Test
    fun oneRequestIsOneBackgroundSnapshotReadThatCapturesIdentityGenerationCatalogVersionAndAsOf() {
        val setup = sessionSetup()
        val read = readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock)
        val landed = assertIs<P806ReportRead.Landed>(read)
        assertEquals(identity(token = 5), landed.capture.request)
        assertEquals(3, landed.capture.generation)
        // The version travels out of the QUERY INSTANCE the session built, never from a current read.
        assertEquals(7L, landed.capture.catalogVersion)
        // The as-of is this call's own application read instant, not a database commit time.
        assertEquals(readInstant, landed.capture.asOf)
        val success = assertIs<P806SnapshotResult.Success<P806ReportView>>(landed.result)
        assertEquals(ledgerId, success.snapshot.ledgerId)
        // Exactly ONE read for the whole surface: one request, one snapshot (totals + count + axis
        // + span in the same read).
        assertEquals(1, setup.port.reportRequests.size)
        val asked = setup.port.reportRequests.single()
        assertEquals(ledgerId, asked.ledgerId)
        assertEquals(P806Period(P806PeriodKind.MONTH), asked.period)
        assertEquals(7L, asked.expectedCatalogVersion)
        // The folded payload: 30.00 CNY ordinary expense, 0 income, -30.00 balance, 1.00 a day
        // over the 30 natural days of the month, plus the span read beside it.
        val view = success.snapshot
        val period = assertNotNull(view.period, "a MONTH read on a non-empty ledger resolves a period")
        assertEquals(LocalDate(2026, 11, 1), period.startDate)
        assertEquals(30, period.dayCount)
        assertEquals(span(), view.span)
        val row = view.currencies.single()
        assertEquals(cny, row.currency)
        assertEquals(3_000L, row.netExpenseMinorUnits)
        assertEquals(0L, row.ordinaryIncomeMinorUnits)
        assertEquals(-3_000L, row.balanceMinorUnits)
        assertEquals(100L, row.dailyAverageMinorUnits)
        assertEquals(4, row.transactionCount)
    }

    @Test
    fun everyOneOfTheFivePeriodKindsReachesTheQueryAndTheApplicationOwnsTheGeometry() {
        val setup = sessionSetup()
        val seam = Seam(generation = 3, facade = setup.facade)
        val custom = P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 1), customEnd = LocalDate(2026, 11, 16))
        val kinds =
            listOf(
                P806Period(P806PeriodKind.WEEK) to LocalDate(2026, 11, 16),
                P806Period(P806PeriodKind.MONTH) to LocalDate(2026, 11, 1),
                P806Period(P806PeriodKind.YEAR) to LocalDate(2026, 1, 1),
                custom to LocalDate(2026, 11, 1),
            )
        // A past year and a future month are both reachable: nothing here is clamped to "today".
        val pastYear = P806Period(P806PeriodKind.YEAR, year = 2019)
        val futureMonth = P806Period(P806PeriodKind.MONTH, month = YearMonth(2027, 3))
        for ((period, startDate) in kinds) {
            val landed = assertIs<P806ReportRead.Landed>(readP806Report(seam, identity(token = 5, period = period), clock))
            val lastRequest = setup.port.reportRequests.last()
            assertEquals(period, lastRequest.period)
            val view = assertIs<P806SnapshotResult.Success<P806ReportView>>(landed.result).snapshot
            assertEquals(startDate, assertNotNull(view.period).startDate)
            // The raw window is the window the request resolves to — the same-snapshot evidence.
            assertEquals(windowFor(period, span()), view.period?.window)
        }
        readP806Report(seam, identity(token = 5, period = pastYear), clock)
        val pastYearRequest = setup.port.reportRequests.last()
        assertEquals(pastYear, pastYearRequest.period)
        readP806Report(seam, identity(token = 5, period = futureMonth), clock)
        val futureMonthRequest = setup.port.reportRequests.last()
        assertEquals(futureMonth, futureMonthRequest.period)
        // ALL takes its window from the ledger span the snapshot returns (first through last
        // effective statistics date, future included), never from a host-side span of our own.
        val futureSpan = span(earliest = "2026-11-01T00:00:00Z", latest = "2027-02-03T02:00:00Z")
        val withSpan =
            sessionSetup(
                answer = { request ->
                    P806SnapshotResult.Success(reportSnapshot(period = request.period, span = futureSpan))
                },
            )
        val all =
            assertIs<P806ReportRead.Landed>(
                readP806Report(Seam(generation = 3, facade = withSpan.facade), identity(token = 5, period = P806Period(P806PeriodKind.ALL)), clock),
            )
        val allView = assertIs<P806SnapshotResult.Success<P806ReportView>>(all.result).snapshot
        val allPeriod = assertNotNull(allView.period, "ALL resolves the span the snapshot returned")
        assertEquals(LocalDate(2026, 11, 1), allPeriod.startDate)
        // The future statistics date is INCLUDED: the span's latest is 2027-02-03 in Shanghai terms.
        assertEquals(LocalDate(2027, 2, 3), allPeriod.endDateInclusive)
        assertEquals(futureSpan, allView.span)
    }

    @Test
    fun aBadCustomRangeIsATypedInvalidWindowThatNeverReachesTheReadPort() {
        val setup = sessionSetup()
        val bad = P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 11, 16), customEnd = LocalDate(2026, 11, 1))
        val landed = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5, period = bad), clock))
        assertEquals(P806SnapshotFailure.InvalidWindow, assertIs<P806SnapshotResult.Failed>(landed.result).failure)
        assertTrue(setup.port.reportRequests.isEmpty())
    }

    @Test
    fun anEmptyLedgerHasNoPeriodButStillReadsTheSnapshot() {
        // No window and no span: the ledger has no effective period at all. The read still runs,
        // and the fold answers the explicit no-period payload (never a zero-valued period, never an
        // infinite range).
        val setup =
            sessionSetup(
                answer = {
                    P806SnapshotResult.Success(
                        reportSnapshot(
                            totals = emptyList(),
                            window = null,
                            span = null,
                            transactionCount = 0,
                            currencyCounts = emptyList(),
                        ),
                    )
                },
            )
        val landed = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock))
        val view = assertIs<P806SnapshotResult.Success<P806ReportView>>(landed.result).snapshot
        assertNull(view.period)
        assertNull(view.span)
        assertTrue(view.dailyAverageNotApplicable)
        assertEquals(1, setup.port.reportRequests.size)
    }

    // ---------------------------------------------------------------- same-snapshot evidence (R-3)

    @Test
    fun aDivergentWindowIsATypedInvalidStateAndNeverASuccess() {
        // The reader answers a window that is not the window this request resolves to: one payload
        // must never be split across two periods.
        val setup =
            sessionSetup(
                answer = { request ->
                    P806SnapshotResult.Success(
                        reportSnapshot(period = request.period, window = P806Window(1L, 2L)),
                    )
                },
            )
        val landed = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock))
        assertEquals(P806SnapshotFailure.InvalidState, assertIs<P806SnapshotResult.Failed>(landed.result).failure)
    }

    @Test
    fun aHalfShapeIsBadDataAndNeverAPeriodLessLedger() {
        // A window without a span (the reader answered a period it cannot back with its own span).
        val windowOnly =
            sessionSetup(
                answer = {
                    P806SnapshotResult.Success(reportSnapshot(span = null, window = windowFor(P806Period(P806PeriodKind.MONTH), span())))
                },
            )
        val windowOnlyRead =
            assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = windowOnly.facade), identity(token = 5), clock))
        assertEquals(P806SnapshotFailure.InvalidState, assertIs<P806SnapshotResult.Failed>(windowOnlyRead.result).failure)
        // A span without a window must not become a no-period success either.
        val spanOnly =
            sessionSetup(
                answer = {
                    P806SnapshotResult.Success(reportSnapshot(window = null, span = span()))
                },
            )
        val spanOnlyRead =
            assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = spanOnly.facade), identity(token = 5), clock))
        assertEquals(P806SnapshotFailure.InvalidState, assertIs<P806SnapshotResult.Failed>(spanOnlyRead.result).failure)
    }

    @Test
    fun aNoPeriodPayloadThatCarriesAmountsIsInconsistentAndNotAnEmptyPeriod() {
        val setup =
            sessionSetup(
                answer = {
                    P806SnapshotResult.Success(
                        reportSnapshot(window = null, span = null, transactionCount = 1),
                    )
                },
            )
        val landed = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock))
        assertEquals(P806SnapshotFailure.InvalidState, assertIs<P806SnapshotResult.Failed>(landed.result).failure)
    }

    // ---------------------------------------------------------------- capability and lease boundaries

    @Test
    fun anUnwiredGraphAndARefusedLeaseStayDistinctAndReadNothing() {
        val request = identity(token = 5)
        val refused = readP806Report(Seam(generation = 3, facade = null), request, clock)
        assertIs<P806ReportRead.Refused>(refused)
        assertEquals(request, refused.request)
        // No catalog session -> the facade exposes no P806 report query; the lease WAS acquired, so
        // the acquire generation travels with the unwired result.
        val unwired = readP806Report(Seam(generation = 3, facade = minimalP503LedgerFacade()), request, clock)
        assertIs<P806ReportRead.Unwired>(unwired)
        assertEquals(request, unwired.request)
        assertEquals(3, unwired.generation)
        // A session without a reporting clock cannot build the report query at all.
        val noClock = sessionSetup(withClock = false)
        val clockless = readP806Report(Seam(generation = 3, facade = noClock.facade), request, clock)
        assertIs<P806ReportRead.Unwired>(clockless)
        assertTrue(noClock.port.reportRequests.isEmpty())
    }

    @Test
    fun aClockFailureIsATypedUnavailableAndReadsNothing() {
        val setup = sessionSetup()
        val failing = LedgerClock { throw IllegalStateException("clock unavailable") }
        val read = readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), failing)
        val landed = assertIs<P806ReportRead.Landed>(read)
        assertNull(landed.capture.asOf, "an unusable clock never stamps an as-of")
        assertEquals(P806SnapshotFailure.Unavailable, assertIs<P806SnapshotResult.Failed>(landed.result).failure)
        assertTrue(setup.port.reportRequests.isEmpty())
    }

    /**
     * R-7/R-10: the REAL owner lease behind [LeaseScopeReportReadSeam] — acquire-first, NotReady
     * before the graph exists, one report read on the active graph, and the lease released in
     * `finally` on BOTH the successful and the throwing path. The port is asserted to have read
     * exactly the completed calls' requests and nothing at all before Ready.
     */
    @Test
    fun theOwnerReportLeaseIsAcquireFirstNotReadyAndAlwaysReleased() {
        class Graph(
            val facade: P503LedgerFacade,
        )
        val setup = sessionSetup()
        val owner =
            LedgerRuntimeOwner(
                openGeneration = { Graph(setup.facade) },
                closeGraph = {},
                facadeOf = { graph -> graph.facade },
            )
        val scope = LedgerLeaseScope(owner)
        // Not Ready: the block never runs, no lease, no read.
        assertEquals(
            LeaseOutcome.NotReady,
            scope.leasedReportRead<String> { _, _ -> error("must not run before Ready") },
        )
        assertEquals(0, owner.inFlightLeaseCount)
        assertTrue(setup.port.reportRequests.isEmpty())
        // Ready: one acquire-first read on the active generation, released in `finally`.
        owner.startup()
        val landed = assertIs<P806ReportRead.Landed>(readP806Report(LeaseScopeReportReadSeam(scope), identity(token = 8), clock))
        assertEquals(owner.activeGeneration, landed.capture.generation)
        assertEquals(1, setup.port.reportRequests.size)
        assertEquals(0, owner.inFlightLeaseCount)
        // A throwing block still releases the lease (the `finally`) and propagates.
        assertFailsWith<IllegalStateException> {
            scope.leasedReportRead<String> { _, _ -> error("injected read failure") }
        }
        assertEquals(0, owner.inFlightLeaseCount)
        // The same lease with a graph that wires NO report read: Unwired, and the port stays untouched.
        val unwiredOwner =
            LedgerRuntimeOwner(
                openGeneration = { Graph(minimalP503LedgerFacade()) },
                closeGraph = {},
                facadeOf = { graph -> graph.facade },
            )
        unwiredOwner.startup()
        val unwiredScope = LedgerLeaseScope(unwiredOwner)
        val unwiredRead =
            readP806Report(LeaseScopeReportReadSeam(unwiredScope), identity(token = 9), clock)
        assertIs<P806ReportRead.Unwired>(unwiredRead)
        assertEquals(1, setup.port.reportRequests.size)
        assertEquals(0, unwiredOwner.inFlightLeaseCount)
    }

    // ---------------------------------------------------------------- the landing decision (acceptance B)

    @Test
    fun theLandingDecisionAcceptsTheMatchingCaptureAndRejectsAnotherTokenPeriodOrLedger() {
        val setup = sessionSetup()
        val read = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock))
        assertEquals(P806ReportLandingDecision.ACCEPTED, p806ReportLandingDecision(overview(loadingSlot(token = 5)), read, 3, 7L))
        // A newer request owns the surface: the old completion changes nothing and, above all,
        // never clears the newer request's loading.
        val newer = overview(loadingSlot(token = 6))
        assertEquals(P806ReportLandingDecision.STALE_REQUEST, p806ReportLandingDecision(newer, read, 3, 7L))
        // A different requested period never lands on this surface.
        val otherPeriod = overview(loadingSlot(token = 5, period = P806Period(P806PeriodKind.YEAR, year = 2025)))
        assertEquals(P806ReportLandingDecision.STALE_REQUEST, p806ReportLandingDecision(otherPeriod, read, 3, 7L))
        // Another ledger's capture is not this surface's read.
        val otherLedger = overview(loadingSlot(token = 5), ledger = otherLedgerId)
        assertEquals(P806ReportLandingDecision.STALE_REQUEST, p806ReportLandingDecision(otherLedger, read, 3, 7L))
        // No slot at all (unwired): nothing to land on.
        assertEquals(P806ReportLandingDecision.STALE_REQUEST, p806ReportLandingDecision(overview(null), read, 3, 7L))
    }

    @Test
    fun theLandingDecisionRejectsAGraphSwapAndACatalogAdvanceAndFailsClosedOnNullCurrents() {
        val setup = sessionSetup()
        val read = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock))
        val slot = overview(loadingSlot(token = 5))
        // The owner reopened the ledger: the capture's generation is no longer active.
        assertEquals(P806ReportLandingDecision.STALE_GENERATION, p806ReportLandingDecision(slot, read, 4, 7L))
        assertEquals(P806ReportLandingDecision.STALE_GENERATION, p806ReportLandingDecision(slot, read, null, 7L))
        // The catalog advanced while the read was in flight: the old capture keeps its own
        // construction-time version and the decision compares it against the CURRENT version.
        setup.reader.current = authority(8L)
        setup.session.refresh()
        assertEquals(7L, read.capture.catalogVersion)
        assertEquals(8L, setup.facade.currentCatalogVersion)
        assertEquals(P806ReportLandingDecision.CATALOG_ADVANCED, p806ReportLandingDecision(slot, read, 3, setup.facade.currentCatalogVersion))
        assertEquals(P806ReportLandingDecision.CATALOG_ADVANCED, p806ReportLandingDecision(slot, read, 3, null))
    }

    @Test
    fun aPayloadWithADuplicatedCurrencyAxisIsRejectedWholeButALegitimateNewAxisIsAccepted() {
        // One snapshot cannot carry the same currency twice: the axis and the per-currency
        // payloads must bind ONE read.
        val duplicated =
            sessionSetup(
                answer = { request ->
                    P806SnapshotResult.Success(reportSnapshot(period = request.period, axis = listOf(cny, cny)))
                },
            )
        val duplicatedRead =
            assertIs<P806ReportRead.Landed>(
                readP806Report(Seam(generation = 3, facade = duplicated.facade), identity(token = 5), clock),
            )
        assertEquals(
            P806ReportLandingDecision.PAYLOAD_REJECTED,
            p806ReportLandingDecision(overview(loadingSlot(token = 5)), duplicatedRead, 3, 7L),
        )
        // A legitimate NEW axis is not rejected because the previously landed payload had another
        // axis: the old payload's axis is never treated as the truth of the new snapshot.
        val usd = CurrencyUnit("USD", 2)
        val previous = P806ReportRegionState(requestToken = 5L, loading = true, landed = reportViewWithoutAxis())
        val usdTotal =
            P806AccountCurrencyTotal(
                accountId = expenseAccountId,
                currency = usd,
                positiveMinorUnits = 100L,
                refundMinorUnits = 0L,
                totalMinorUnits = 100L,
            )
        val newAxis =
            sessionSetup(
                answer = { request ->
                    P806SnapshotResult.Success(
                        reportSnapshot(
                            period = request.period,
                            axis = listOf(usd),
                            totals = listOf(usdTotal),
                            currencyCounts = listOf(P806CurrencyTransactionCount(usd, 1)),
                        ),
                    )
                },
            )
        val newRead = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = newAxis.facade), identity(token = 5), clock))
        assertEquals(P806ReportLandingDecision.ACCEPTED, p806ReportLandingDecision(overview(previous), newRead, 3, 7L))
        val view = assertIs<P806SnapshotResult.Success<P806ReportView>>(newRead.result).snapshot
        assertEquals(listOf(usd), view.currencies.map { row -> row.currency })
    }

    @Test
    fun theReducerAppliesTheRealCaptureAndALateReadCannotClearANewerLoading() {
        val setup = sessionSetup()
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val read = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock))
        val landed =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    overview(loadingSlot(token = 5)),
                    P503UiEvent.ReportPeriodResult(read = read, currentGeneration = 3, currentCatalogVersion = 7L),
                ),
            )
        val slot = assertNotNull(landed.reportRegion)
        assertTrue(!slot.loading)
        assertNull(slot.failure)
        assertEquals(readInstant, slot.landedAsOf)
        assertEquals(3, slot.landedGeneration)
        assertEquals(7L, slot.landedCatalogVersion)
        assertEquals(P806PeriodKind.MONTH, slot.landedKind)
        assertEquals(3_000L, assertNotNull(slot.landed).currencies.single().netExpenseMinorUnits)
        // A late read of the superseded token leaves a NEWER request's loading untouched.
        val newer =
            assertIs<P503AppState.OverviewEmpty>(
                reducer.reduce(
                    overview(loadingSlot(token = 6)),
                    P503UiEvent.ReportPeriodResult(read = read, currentGeneration = 3, currentCatalogVersion = 7L),
                ),
            )
        assertTrue(assertNotNull(newer.reportRegion).loading)
        assertNull(assertNotNull(newer.reportRegion).landed)
    }

    /**
     * N-1: a landing that arrives while the user is on a surface that CARRIES the overview is applied
     * to that carried overview (the shared carrier table), so the host's last-ready memory reads the
     * same place and remembers it — not only a landing that happened to see a bare OverviewEmpty.
     */
    @Test
    fun aLandingAppliedToACarriedOverviewStillFeedsTheHostMemory() {
        val setup = sessionSetup()
        val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)
        val memory = P806ReportSlotMemory()
        // The read is in flight when the user opens the account flow (a carried-overview surface).
        val detour =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(
                    overview(loadingSlot(token = 5L)),
                    P503UiEvent.SelectAccountFlow(
                        accountId = expenseAccountId,
                        balanceLine = "餐饮 · 支出 · CNY",
                        balanceMinorUnits = 0L,
                        balanceCurrency = cny,
                    ),
                ),
            )
        val read = assertIs<P806ReportRead.Landed>(readP806Report(Seam(generation = 3, facade = setup.facade), identity(token = 5), clock))
        val landed =
            assertIs<P503AppState.AccountFlow>(
                reducer.reduce(detour, P503UiEvent.ReportPeriodResult(read = read, currentGeneration = 3, currentCatalogVersion = 7L)),
            )
        assertNotNull(assertNotNull(landed.overview.reportRegion).landed)
        // The memory reads the carrier, so the carried landing is remembered for the next rebuild.
        memory.record(landed)
        assertNotNull(memory.previousFor(3, 7L))
        // A state that carries no overview records nothing (a late landing cannot fill the memory).
        memory.clear()
        memory.record(P503AppState.Created)
        assertNull(memory.previousFor(3, 7L))
    }

    private fun reportViewWithoutAxis(): P806ReportView =
        P806ReportView(
            ledgerId = ledgerId,
            period = null,
            currencies = emptyList(),
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
            uncategorizedExpenseTotals = emptyList(),
            uncategorizedIncomeTotals = emptyList(),
            transactionCount = 0,
            futureTransactionCount = 0,
            detailPage = P806TransactionPage(rows = emptyList(), nextCursor = null),
            detailCount = 0,
        )
}
