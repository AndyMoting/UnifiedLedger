package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch a (D-343; spec sections 3.5.1/3.5.4/3.8, D-343 3.5.4/3.7 and 类型化读失败): the default-UOE
 * contract of the four composite port methods, the shared axis rule, and the typed mapping every
 * consumer owes an escaping read failure or clock failure.
 */
class P806PortDefaultsAndAxisTest {
    private val ledgerId = LedgerId("ledger-p806-defaults")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val cashId = AccountId("asset-cash")
    private val catalog: LedgerCatalog = catalog(listOf(account(cashId, cny)))

    @Test
    fun aPortWithoutTheCompositeMethodsFailsLoudInsteadOfAnsweringAnEmptySurface() {
        val port = MinimalPort()
        assertFailsWith<UnsupportedOperationException> {
            port.readReportSnapshot(
                P806ReportRequest(ledgerId, catalog, P806Period(P806PeriodKind.MONTH), LocalDate(2026, 11, 17), 0L, 1L),
            )
        }
        assertFailsWith<UnsupportedOperationException> {
            port.readCalendarSnapshot(P806CalendarRequest(ledgerId, catalog, YearMonth(2026, Month.NOVEMBER), 1L))
        }
        assertFailsWith<UnsupportedOperationException> {
            port.readDrilldownSnapshot(P806DrilldownRequest(ledgerId, P806Window(1L, 2L), expectedCatalogVersion = 1L))
        }
        assertFailsWith<UnsupportedOperationException> {
            port.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cashId, expectedCatalogVersion = 1L))
        }
        assertEquals(emptyList(), port.loadCurrentRows(ledgerId))
    }

    @Test
    fun everyUseCaseMapsAnEscapingReadFailureToTypedUnavailable() {
        val authority = CatalogAuthority(ledgerId, catalog, 1L)
        val clock = FixedClock("2026-11-17T00:00:00Z")
        val throwing = ThrowingPort()

        assertIs<P806SnapshotResult.Failed>(
            P806ReportQuery(throwing, ledgerId, authority, clock).query(P806Period(P806PeriodKind.MONTH)).also {
                assertEquals(P806SnapshotFailure.Unavailable, (it as P806SnapshotResult.Failed).failure)
            },
        )
        assertIs<P806SnapshotResult.Failed>(
            P806CalendarQuery(throwing, ledgerId, authority, clock).query(YearMonth(2026, Month.NOVEMBER)).also {
                assertEquals(P806SnapshotFailure.Unavailable, (it as P806SnapshotResult.Failed).failure)
            },
        )
        assertIs<P806SnapshotResult.Failed>(
            P806DrilldownQuery(throwing, ledgerId, authority).query(P806Window(1L, 2L)).also {
                assertEquals(P806SnapshotFailure.Unavailable, (it as P806SnapshotResult.Failed).failure)
            },
        )
        assertIs<P806SnapshotResult.Failed>(
            P806AccountPostingsQuery(throwing, ledgerId, authority).query(cashId).also {
                assertEquals(P806SnapshotFailure.Unavailable, (it as P806SnapshotResult.Failed).failure)
            },
        )
    }

    @Test
    fun aClockFailureIsTypedOnlyOnThePathsThatNeedTheClock() {
        val authority = CatalogAuthority(ledgerId, catalog, 1L)
        val port = EmptySuccessPort()
        val throwingClock = ThrowingClock()

        val report = P806ReportQuery(port, ledgerId, authority, throwingClock).query(P806Period(P806PeriodKind.MONTH))
        assertEquals(P806SnapshotFailure.Unavailable, (assertIs<P806SnapshotResult.Failed>(report)).failure)

        val defaultMonth = P806CalendarQuery(port, ledgerId, authority, throwingClock).query()
        assertEquals(P806SnapshotFailure.Unavailable, (assertIs<P806SnapshotResult.Failed>(defaultMonth)).failure)

        // An explicit month does not read the clock at all.
        val explicitMonth = P806CalendarQuery(port, ledgerId, authority, throwingClock).query(YearMonth(2026, Month.NOVEMBER))
        assertIs<P806SnapshotResult.Success<P806CalendarView>>(explicitMonth)
    }

    @Test
    fun anUnrepresentableCustomPeriodIsInvalidWindowNotUnavailable() {
        val authority = CatalogAuthority(ledgerId, catalog, 1L)
        val query = P806ReportQuery(SpanPort(), ledgerId, authority, FixedClock("2026-11-17T00:00:00Z"))
        val result =
            query.query(
                P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 3, 5), customEnd = LocalDate(2026, 3, 1)),
            )
        assertEquals(P806SnapshotFailure.InvalidWindow, (assertIs<P806SnapshotResult.Failed>(result)).failure)
    }

    @Test
    fun anEmptyLedgerAnswersNoPeriodForAllFiveKinds() {
        val authority = CatalogAuthority(ledgerId, catalog, 1L)
        val query = P806ReportQuery(EmptySuccessPort(), ledgerId, authority, FixedClock("2026-11-17T00:00:00Z"))
        val kinds =
            listOf(
                P806Period(P806PeriodKind.WEEK),
                P806Period(P806PeriodKind.MONTH),
                P806Period(P806PeriodKind.YEAR),
                P806Period(P806PeriodKind.ALL),
                P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 1, 1), customEnd = LocalDate(2026, 1, 31)),
            )
        kinds.forEach { period ->
            val view = (query.query(period) as P806SnapshotResult.Success<P806ReportView>).snapshot
            assertEquals(null, view.period, "empty ledger must answer NoPeriod for ${period.kind}")
            assertTrue(view.dailyAverageNotApplicable)
            assertTrue(view.currencies.isEmpty())
            assertEquals(0, view.transactionCount)
        }
    }

    @Test
    fun theSharedAxisRuleAcceptsOnlyCurrenciesThatNameAnAccountOfThisLedger() {
        assertTrue(isAxisCatalogConsistent(listOf(cny), catalog, ledgerId))
        assertFalse(isAxisCatalogConsistent(listOf(cny, usd), catalog, ledgerId))
        assertFalse(isAxisCatalogConsistent(listOf(cny), catalog, LedgerId("other-ledger")))
        assertTrue(isAxisCatalogConsistent(emptyList(), catalog, ledgerId))
    }

    @Test
    fun theMonthlyReadStillRejectsAnAxisCurrencyWithoutACatalogAccount() {
        val clock = FixedClock("2026-11-17T00:00:00Z")
        val query = QueryMonthlyActivity(AxisPort(axis = listOf(cny, usd)), ledgerId, catalog, clock)
        assertEquals(MonthlyActivityResult.InvalidState, query.query(YearMonth(2026, Month.NOVEMBER)))
        val healthy = QueryMonthlyActivity(AxisPort(axis = listOf(cny)), ledgerId, catalog, clock)
        assertTrue(healthy.query(YearMonth(2026, Month.NOVEMBER)) is MonthlyActivityResult.Success)
    }

    // ---------------------------------------------------------------- fakes

    /**
     * The six `findManual*` request/receipt lookups no vector in this file reads: every fake
     * extends this base and overrides only the surface its own vector exercises.
     */
    private abstract class P806NullLookupPort : LedgerCurrentStateReadPort {
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

    private class MinimalPort : P806NullLookupPort() {
        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()
    }

    private class ThrowingPort : P806NullLookupPort() {
        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

        override fun readReportSnapshot(request: P806ReportRequest): P806SnapshotResult<P806ReportSnapshot> = throw UnsupportedOperationException("unwired")

        override fun readCalendarSnapshot(request: P806CalendarRequest): P806SnapshotResult<P806CalendarSnapshot> = throw IllegalStateException("unwired")

        override fun readDrilldownSnapshot(request: P806DrilldownRequest): P806SnapshotResult<P806DrilldownSnapshot> = throw UnsupportedOperationException("unwired")

        override fun readAccountPostingsSnapshot(request: P806AccountRequest): P806SnapshotResult<P806AccountSnapshot> = throw IllegalStateException("unwired")
    }

    /** A port that answers a successful but empty report/calendar snapshot (no effective period). */
    private class EmptySuccessPort : P806NullLookupPort() {
        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

        override fun readReportSnapshot(request: P806ReportRequest): P806SnapshotResult<P806ReportSnapshot> =
            P806SnapshotResult.Success(
                P806ReportSnapshot(
                    catalogVersion = request.expectedCatalogVersion,
                    window = null,
                    span = null,
                    currencyAxis = emptyList(),
                    postingTotals = emptyList(),
                    transactionCount = 0,
                    currencyCounts = emptyList(),
                    futureTransactionCount = 0,
                    firstPage = P806TransactionPage(emptyList(), null),
                    detailCount = 0,
                ),
            )

        override fun readCalendarSnapshot(request: P806CalendarRequest): P806SnapshotResult<P806CalendarSnapshot> =
            P806SnapshotResult.Success(
                P806CalendarSnapshot(
                    catalogVersion = request.expectedCatalogVersion,
                    month = request.month,
                    currencyAxis = emptyList(),
                    days =
                        (P806Periods.dayBuckets(request.month) ?: emptyList()).map {
                            P806CalendarDaySnapshot(it.date, it.window, emptyList(), 0)
                        },
                ),
            )
    }

    /**
     * A port with a live span and the window the REQUEST resolves to — the same shape the production
     * reader answers (P8-06 batch b: a fixed toy window is no longer a legal snapshot, so this fake
     * must resolve like the real adapter).
     */
    private class SpanPort : P806NullLookupPort() {
        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

        override fun readReportSnapshot(request: P806ReportRequest): P806SnapshotResult<P806ReportSnapshot> {
            val span = P806Span(Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-12-31T16:00:00Z"))
            val resolved =
                when (val resolution = P806Periods.resolve(request.period, request.today, span)) {
                    is P806PeriodResolution.Resolved -> resolution.period
                    P806PeriodResolution.InvalidWindow -> return P806SnapshotResult.Failed(P806SnapshotFailure.InvalidWindow)
                    P806PeriodResolution.NotApplicable -> return P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
                }
            return P806SnapshotResult.Success(
                P806ReportSnapshot(
                    catalogVersion = request.expectedCatalogVersion,
                    window = resolved.window,
                    span = span,
                    currencyAxis = emptyList(),
                    postingTotals = emptyList(),
                    transactionCount = 0,
                    currencyCounts = emptyList(),
                    futureTransactionCount = 0,
                    firstPage = P806TransactionPage(emptyList(), null),
                    detailCount = 0,
                ),
            )
        }
    }

    private class AxisPort(
        private val axis: List<CurrencyUnit>,
    ) : P806NullLookupPort() {
        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

        override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> = emptyList()

        override fun loadLedgerActivityCurrencies(ledgerId: LedgerId): List<CurrencyUnit> = axis
    }

    @Test
    fun aBadCustomWindowIsInvalidWindowEvenOnAnEmptyLedgerAndOnALiveLedger() {
        val authority = CatalogAuthority(ledgerId, catalog, 1L)
        val badCustom = P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 3, 5), customEnd = LocalDate(2026, 3, 1))
        val emptyLedger = P806ReportQuery(EmptySuccessPort(), ledgerId, authority, FixedClock("2026-11-17T00:00:00Z")).query(badCustom)
        assertEquals(P806SnapshotFailure.InvalidWindow, (assertIs<P806SnapshotResult.Failed>(emptyLedger)).failure)

        val liveLedger = P806ReportQuery(SpanPort(), ledgerId, authority, FixedClock("2026-11-17T00:00:00Z")).query(badCustom)
        assertEquals(P806SnapshotFailure.InvalidWindow, (assertIs<P806SnapshotResult.Failed>(liveLedger)).failure)

        val unrepresentable =
            P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 3, 1), customEnd = LocalDate(999999, 12, 31))
        val emptyUnrepresentable = P806ReportQuery(EmptySuccessPort(), ledgerId, authority, FixedClock("2026-11-17T00:00:00Z")).query(unrepresentable)
        assertEquals(P806SnapshotFailure.InvalidWindow, (assertIs<P806SnapshotResult.Failed>(emptyUnrepresentable)).failure)
    }

    @Test
    fun aClockAtTheNanosCeilingConvertsTodayButFailsAtTheNextMidnightThreshold() {
        // 2262-04-11T17:00:00Z is 2262-04-12 01:00 in Shanghai: the today conversion MUST succeed,
        // and only the next local midnight (2262-04-13 00:00+08) leaves the Long nanosecond range.
        val instant = Instant.parse("2262-04-11T17:00:00Z")
        assertEquals(
            LocalDate(2262, 4, 12),
            instant.toLocalDateTime(com.unifiedledger.application.MonthlyBuckets.REPORT_TIME_ZONE).date,
        )
        val authority = CatalogAuthority(ledgerId, catalog, 1L)
        val report =
            P806ReportQuery(EmptySuccessPort(), ledgerId, authority, FixedClock("2262-04-11T17:00:00Z"))
                .query(P806Period(P806PeriodKind.MONTH))
        assertEquals(P806SnapshotFailure.Unavailable, (assertIs<P806SnapshotResult.Failed>(report)).failure)
    }

    @Test
    fun aCustomPeriodWithoutBoundsIsInvalidWindowInsteadOfAConstructorFailure() {
        val authority = CatalogAuthority(ledgerId, catalog, 1L)
        // The constructor stays total; the shared resolver classifies the missing bounds.
        val missingBounds = P806Period(P806PeriodKind.CUSTOM)
        val result = P806ReportQuery(EmptySuccessPort(), ledgerId, authority, FixedClock("2026-11-17T00:00:00Z")).query(missingBounds)
        assertEquals(P806SnapshotFailure.InvalidWindow, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        assertFailsWith<IllegalArgumentException> {
            // The grouped-row contract is still enforced on illegal raw values.
            com.unifiedledger.application.P806AccountCurrencyTotal(com.unifiedledger.domain.AccountId("a"), cny, -1L, 0L, -1L)
        }
    }

    @Test
    fun theOrdinaryKindSetIsTheFrozenSixNamesAndNeverShrinksSilently() {
        // The SQL binds exactly six kind slots; a change here without a query change would drop or
        // misbind a parameter, so the cardinality AND the exact frozen names are both locked.
        assertEquals(6, OrdinaryFlowClassification.ORDINARY_KINDS.size, "the frozen ordinary kind set has exactly six names")
        assertEquals(
            setOf(
                TransactionKind.EXPENSE,
                TransactionKind.INCOME,
                TransactionKind.ACCOUNT_TRANSFER,
                TransactionKind.LEND,
                TransactionKind.COLLECT,
                TransactionKind.REFUND_RECEIPT,
            ),
            OrdinaryFlowClassification.ORDINARY_KINDS,
            "the frozen ordinary kind names are exact",
        )
    }

    private class FixedClock(
        private val value: String,
    ) : LedgerClock {
        override fun now(): Instant = Instant.parse(value)
    }

    private class ThrowingClock : LedgerClock {
        override fun now(): Instant = throw IllegalStateException("no clock")
    }

    private fun account(
        id: AccountId,
        currency: CurrencyUnit,
    ): Account = Account(id, ledgerId, AccountKind.ASSET, currency, ownedByUser = true, realAccount = true, name = id.value)

    private fun catalog(accounts: List<Account>): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts = accounts, categories = emptyList())) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("fixture catalog must be valid")
        }
}
