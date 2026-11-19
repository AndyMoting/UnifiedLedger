package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * P8-06 foundation (D-343; spec sections 3.5.1/3.8): the four read queries bind the construction-time
 * catalog generation and self-check the raw Success snapshot against it before any fold. A fresh
 * session refresh rebuilds the query fields; an already-obtained query keeps its own version
 * (single-field publication only, never claimed as atomic).
 */
class P806QueryCatalogVersionIdentityTest {
    private val ledgerId = LedgerId("ledger-p806-version")
    private val clock = LedgerClock { Instant.parse("2026-11-17T00:00:00Z") }
    private val catalog: LedgerCatalog =
        (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success).value

    private fun authority(version: Long): CatalogAuthority = CatalogAuthority(ledgerId, catalog, version)

    @Test
    fun everyQueryBindsItsConstructionVersionAndRejectsAnotherRawVersion() {
        val port =
            P806IdentityPort(
                report = P806SnapshotResult.Success(reportSnapshot(8L)),
                calendar = P806SnapshotResult.Success(calendarSnapshot(8L)),
                drilldown = P806SnapshotResult.Success(drilldownSnapshot(8L)),
                account = P806SnapshotResult.Success(accountSnapshot(8L)),
            )
        val bound = authority(7L)
        val reportQuery = P806ReportQuery(port, ledgerId, bound, clock)
        val calendarQuery = P806CalendarQuery(port, ledgerId, bound, clock)
        val drilldownQuery = P806DrilldownQuery(port, ledgerId, bound)
        val accountQuery = P806AccountPostingsQuery(port, ledgerId, bound)

        // A: each query exposes the same immutable authority version it was constructed with.
        assertEquals(7L, reportQuery.catalogVersion)
        assertEquals(7L, calendarQuery.catalogVersion)
        assertEquals(7L, drilldownQuery.catalogVersion)
        assertEquals(7L, accountQuery.catalogVersion)

        // B: a raw Success snapshot from another generation is a typed mismatch on all four faces.
        assertEquals(
            P806SnapshotFailure.CatalogVersionMismatch,
            failureOf(reportQuery.query(P806Period(P806PeriodKind.MONTH))),
        )
        assertEquals(
            P806SnapshotFailure.CatalogVersionMismatch,
            failureOf(calendarQuery.query(YearMonth(2026, Month.NOVEMBER))),
        )
        assertEquals(
            P806SnapshotFailure.CatalogVersionMismatch,
            failureOf(drilldownQuery.query(P806Window(1L, 2L))),
        )
        assertEquals(
            P806SnapshotFailure.CatalogVersionMismatch,
            failureOf(accountQuery.query(AccountId("account-version"))),
        )

        // Echoing the expected version keeps all four faces successful.
        port.report = P806SnapshotResult.Success(reportSnapshot(7L))
        port.calendar = P806SnapshotResult.Success(calendarSnapshot(7L))
        port.drilldown = P806SnapshotResult.Success(drilldownSnapshot(7L))
        port.account = P806SnapshotResult.Success(accountSnapshot(7L))
        assertIs<P806SnapshotResult.Success<P806ReportView>>(reportQuery.query(P806Period(P806PeriodKind.MONTH)))
        assertIs<P806SnapshotResult.Success<P806CalendarView>>(calendarQuery.query(YearMonth(2026, Month.NOVEMBER)))
        assertIs<P806SnapshotResult.Success<P806DrilldownView>>(drilldownQuery.query(P806Window(1L, 2L)))
        assertIs<P806SnapshotResult.Success<P806AccountView>>(accountQuery.query(AccountId("account-version")))
    }

    @Test
    fun theRawVersionCheckPrecedesTheSuccessWindowResolveWhileThePreReadGeometryStaysInPlace() {
        val port =
            P806IdentityPort(
                report = P806SnapshotResult.Success(reportSnapshot(8L)),
                calendar = P806SnapshotResult.Success(calendarSnapshot(8L)),
                drilldown = P806SnapshotResult.Success(drilldownSnapshot(8L)),
                account = P806SnapshotResult.Success(accountSnapshot(8L)),
            )
        val query = P806ReportQuery(port, ledgerId, authority(7L), clock)
        // A representable window plus a span whose local-day conversion overflows: the Success-path
        // resolve would answer InvalidWindow, but the version check runs first.
        val unrepresentableSpan =
            P806Span(
                earliest = Instant.parse("9999-01-01T00:00:00Z"),
                latest = Instant.parse("9999-01-01T00:00:00Z"),
            )
        port.report =
            P806SnapshotResult.Success(
                reportSnapshot(8L, window = P806Window(1L, 2L), span = unrepresentableSpan),
            )
        assertEquals(
            P806SnapshotFailure.CatalogVersionMismatch,
            failureOf(query.query(P806Period(P806PeriodKind.ALL))),
        )

        // The same bad span with the expected version proves the window really would have failed.
        port.report =
            P806SnapshotResult.Success(
                reportSnapshot(7L, window = P806Window(1L, 2L), span = unrepresentableSpan),
            )
        assertEquals(
            P806SnapshotFailure.InvalidWindow,
            failureOf(query.query(P806Period(P806PeriodKind.ALL))),
        )

        // The pre-read geometry rejection keeps its place and never reaches the port.
        val readsBefore = port.reportReads
        val reversedCustom =
            P806Period(
                P806PeriodKind.CUSTOM,
                customStart = LocalDate(2026, 3, 5),
                customEnd = LocalDate(2026, 3, 1),
            )
        assertEquals(P806SnapshotFailure.InvalidWindow, failureOf(query.query(reversedCustom)))
        assertEquals(readsBefore, port.reportReads)
    }

    @Test
    fun aReentrantRefreshRebuildsTheSessionQueriesWhileOldInstancesKeepTheirVersion() {
        val reader = MutableAuthorityReader(authority(7L))
        val port =
            P806IdentityPort(
                report = P806SnapshotResult.Success(reportSnapshot(7L)),
                calendar = P806SnapshotResult.Success(calendarSnapshot(7L)),
                drilldown = P806SnapshotResult.Success(drilldownSnapshot(7L)),
                account = P806SnapshotResult.Success(accountSnapshot(7L)),
            )
        val session = CatalogConsumerSession(reader, ledgerId, reader.current, port, clock = clock)
        val oldReport = assertNotNull(session.p806ReportQuery)
        val oldCalendar = assertNotNull(session.p806CalendarQuery)
        val oldDrilldown = session.p806DrilldownQuery
        val oldAccount = session.p806AccountPostingsQuery
        assertEquals(7L, oldReport.catalogVersion)

        // Refresh re-enters from inside the old query's own read call.
        reader.current = authority(8L)
        port.onReportRead = { session.refresh() }
        val result = oldReport.query(P806Period(P806PeriodKind.MONTH))
        assertIs<P806SnapshotResult.Success<P806ReportView>>(result)

        // The old call still requested (and checked) its own bound version.
        assertEquals(7L, port.lastReportExpectedVersion)
        assertEquals(7L, oldReport.catalogVersion)
        assertEquals(7L, oldCalendar.catalogVersion)
        assertEquals(7L, oldDrilldown.catalogVersion)
        assertEquals(7L, oldAccount.catalogVersion)

        // The session published new query instances at the reloaded version.
        val newReport = assertNotNull(session.p806ReportQuery)
        assertEquals(8L, newReport.catalogVersion)
        assertSame(newReport, session.p806ReportQuery)
        assertNotSame(oldReport, newReport)
        assertEquals(8L, assertNotNull(session.p806CalendarQuery).catalogVersion)
        assertEquals(8L, session.p806DrilldownQuery.catalogVersion)
        assertEquals(8L, session.p806AccountPostingsQuery.catalogVersion)
        assertNotSame(oldCalendar, session.p806CalendarQuery)
        assertNotSame(oldDrilldown, session.p806DrilldownQuery)
        assertNotSame(oldAccount, session.p806AccountPostingsQuery)
    }

    /**
     * P8-06 batch b (D-343 section 3.1.3): the success path also verifies the snapshot's OWN
     * evidence. The raw window must be the window this request resolves to against the single
     * `today` the call already read; a reader that answers another window is a typed `InvalidState`
     * (a real adapter reads it exactly so — this is the divergence guard), and a half shape
     * (window without span, or span without window) is bad data, NEVER a period-less ledger.
     */
    @Test
    fun theSameSnapshotWindowAndShapeMustMatchTheRequestAndNeverBecomeAQuietNoPeriod() {
        val span = P806Span(Instant.parse("2026-03-02T02:00:00Z"), Instant.parse("2026-03-20T02:00:00Z"))
        val month = P806Period(P806PeriodKind.MONTH, month = YearMonth(2026, 3))
        val resolvedWindow = resolvedWindow(month, span)
        val port =
            P806IdentityPort(
                report = P806SnapshotResult.Success(reportSnapshot(7L, window = resolvedWindow, span = span)),
                calendar = P806SnapshotResult.Success(calendarSnapshot(7L)),
                drilldown = P806SnapshotResult.Success(drilldownSnapshot(7L)),
                account = P806SnapshotResult.Success(accountSnapshot(7L)),
            )
        val query = P806ReportQuery(port, ledgerId, authority(7L), clock)
        // Positive control: the matching window succeeds and the view carries the same-snapshot span.
        val view = assertIs<P806SnapshotResult.Success<P806ReportView>>(query.query(month)).snapshot
        assertEquals(resolvedWindow, assertNotNull(view.period).window)
        assertEquals(span, view.span)
        // A divergent window is a typed invalid state, never a success and never a NoPeriod.
        port.report = P806SnapshotResult.Success(reportSnapshot(7L, window = P806Window(1L, 2L), span = span))
        assertEquals(P806SnapshotFailure.InvalidState, failureOf(query.query(month)))
        // A window without a span: half shape -> InvalidState (not NoPeriod).
        port.report = P806SnapshotResult.Success(reportSnapshot(7L, window = resolvedWindow, span = null))
        assertEquals(P806SnapshotFailure.InvalidState, failureOf(query.query(month)))
        // A span without a window: the same half shape, again never a NoPeriod success.
        port.report = P806SnapshotResult.Success(reportSnapshot(7L, window = null, span = span))
        assertEquals(P806SnapshotFailure.InvalidState, failureOf(query.query(month)))
        // The honest no-period payload (both absent, nothing to report) still answers NoPeriod.
        port.report = P806SnapshotResult.Success(reportSnapshot(7L, window = null, span = null))
        val empty = assertIs<P806SnapshotResult.Success<P806ReportView>>(query.query(month)).snapshot
        assertEquals(null, empty.period)
        assertEquals(null, empty.span)
        // A no-period payload that still carries amounts is inconsistent, not an empty period.
        port.report = P806SnapshotResult.Success(reportSnapshot(7L, window = null, span = null, transactionCount = 1))
        assertEquals(P806SnapshotFailure.InvalidState, failureOf(query.query(month)))
        // The same holds for a no-period payload that still names an activity axis (N-4): a ledger
        // without any effective period has no currency population to report either.
        port.report = P806SnapshotResult.Success(reportSnapshot(7L, window = null, span = null, axis = listOf(CurrencyUnit("CNY", 2))))
        assertEquals(P806SnapshotFailure.InvalidState, failureOf(query.query(month)))
    }

    /**
     * P8-06 batch b (D-343 section 3.1.3): `ALL` must match the Shanghai span INCLUDING both ends —
     * a future statistics date extends the window — and the version check still precedes the window
     * verification (a wrong-version snapshot with a divergent window is the version mismatch).
     */
    @Test
    fun aFutureAllMatchesTheShanghaiInclusiveSpanAndTheVersionCheckStillComesFirst() {
        val futureSpan =
            P806Span(
                earliest = Instant.parse("2026-11-01T02:00:00Z"),
                latest = Instant.parse("2027-02-03T02:00:00Z"),
            )
        val all = P806Period(P806PeriodKind.ALL)
        val window = resolvedWindow(all, futureSpan)
        val port =
            P806IdentityPort(
                report = P806SnapshotResult.Success(reportSnapshot(7L, window = window, span = futureSpan)),
                calendar = P806SnapshotResult.Success(calendarSnapshot(7L)),
                drilldown = P806SnapshotResult.Success(drilldownSnapshot(7L)),
                account = P806SnapshotResult.Success(accountSnapshot(7L)),
            )
        val query = P806ReportQuery(port, ledgerId, authority(7L), clock)
        val view = assertIs<P806SnapshotResult.Success<P806ReportView>>(query.query(all)).snapshot
        val period = assertNotNull(view.period)
        // 2026-11-01 through 2027-02-03 in Shanghai terms, both ends included (the future date too).
        assertEquals(LocalDate(2026, 11, 1), period.startDate)
        assertEquals(LocalDate(2027, 2, 3), period.endDateInclusive)
        assertEquals(futureSpan, view.span)
        // The version check keeps its precedence over the (here also divergent) window verification.
        port.report = P806SnapshotResult.Success(reportSnapshot(8L, window = P806Window(1L, 2L), span = futureSpan))
        assertEquals(P806SnapshotFailure.CatalogVersionMismatch, failureOf(query.query(all)))
    }

    // ---------------------------------------------------------------- fixtures

    private fun failureOf(result: P806SnapshotResult<*>): P806SnapshotFailure = assertIs<P806SnapshotResult.Failed>(result).failure

    /**
     * The window the application resolves for [period] against the fixture clock's own "today"
     * (2026-11-17 in Shanghai terms) — what a production reader answers with.
     */
    private fun resolvedWindow(
        period: P806Period,
        span: P806Span?,
    ): P806Window = assertIs<P806PeriodResolution.Resolved>(P806Periods.resolve(period, LocalDate(2026, 11, 17), span)).period.window

    private fun reportSnapshot(
        version: Long,
        window: P806Window? = null,
        span: P806Span? = null,
        transactionCount: Int = 0,
        axis: List<CurrencyUnit> = emptyList(),
    ): P806ReportSnapshot =
        P806ReportSnapshot(
            catalogVersion = version,
            window = window,
            span = span,
            currencyAxis = axis,
            postingTotals = emptyList(),
            transactionCount = transactionCount,
            currencyCounts = emptyList(),
            futureTransactionCount = 0,
            firstPage = P806TransactionPage(emptyList(), null),
            detailCount = 0,
        )

    private fun calendarSnapshot(version: Long): P806CalendarSnapshot =
        P806CalendarSnapshot(
            catalogVersion = version,
            month = YearMonth(2026, Month.NOVEMBER),
            currencyAxis = emptyList(),
            days = emptyList(),
        )

    private fun drilldownSnapshot(version: Long): P806DrilldownSnapshot =
        P806DrilldownSnapshot(
            catalogVersion = version,
            window = P806Window(1L, 2L),
            page = P806TransactionPage(emptyList(), null),
            totalCount = 0,
        )

    private fun accountSnapshot(version: Long): P806AccountSnapshot =
        P806AccountSnapshot(
            catalogVersion = version,
            page = P806AccountPostingsPage(emptyList(), null),
            totalCount = 0,
        )

    private class MutableAuthorityReader(
        var current: CatalogAuthority,
    ) : CatalogAuthorityReader {
        override fun load(ledgerId: LedgerId): CatalogAuthority? = current
    }

    /** The seven existing abstract members plus the four typed P806 surfaces this batch reads. */
    private class P806IdentityPort(
        var report: P806SnapshotResult<P806ReportSnapshot>,
        var calendar: P806SnapshotResult<P806CalendarSnapshot>,
        var drilldown: P806SnapshotResult<P806DrilldownSnapshot>,
        var account: P806SnapshotResult<P806AccountSnapshot>,
    ) : LedgerCurrentStateReadPort {
        var reportReads = 0
        var lastReportExpectedVersion: Long? = null
        var onReportRead: (() -> Unit)? = null

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

        override fun readReportSnapshot(request: P806ReportRequest): P806SnapshotResult<P806ReportSnapshot> {
            reportReads += 1
            lastReportExpectedVersion = request.expectedCatalogVersion
            onReportRead?.invoke()
            return report
        }

        override fun readCalendarSnapshot(request: P806CalendarRequest): P806SnapshotResult<P806CalendarSnapshot> = calendar

        override fun readDrilldownSnapshot(request: P806DrilldownRequest): P806SnapshotResult<P806DrilldownSnapshot> = drilldown

        override fun readAccountPostingsSnapshot(request: P806AccountRequest): P806SnapshotResult<P806AccountSnapshot> = account
    }
}
