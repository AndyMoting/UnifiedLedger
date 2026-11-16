package com.unifiedledger.application

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch a (D-343; spec sections 3.1/3.3/3.10, vectors V-1/V-15/V-16/V-17): pure period,
 * day-bucket and signed-rounding vectors. No database, no clock, no Android.
 */
class P806PeriodsTest {
    private val today = LocalDate(2026, 11, 17)

    @Test
    fun weekStartsOnMondayAndSpansSevenLocalDays() {
        val resolution = P806Periods.resolve(P806Period(P806PeriodKind.WEEK), today, span = null)
        val period = (resolution as P806PeriodResolution.Resolved).period
        // 2026-11-17 is a Tuesday, so the week starts on Monday 2026-11-16.
        assertEquals(LocalDate(2026, 11, 16), period.startDate)
        assertEquals(LocalDate(2026, 11, 22), period.endDateInclusive)
        assertEquals(7, period.dayCount)
        assertEquals(Instant.parse("2026-11-15T16:00:00Z"), instantOf(period.window.startEpochNanos))
        assertEquals(Instant.parse("2026-11-22T16:00:00Z"), instantOf(period.window.endEpochNanos))
    }

    @Test
    fun monthYearAndCustomWindowsAreInclusiveHalfOpenShanghaiRanges() {
        val month = (P806Periods.resolve(P806Period(P806PeriodKind.MONTH, month = YearMonth(2026, Month.FEBRUARY)), today, null) as P806PeriodResolution.Resolved).period
        assertEquals(LocalDate(2026, 2, 1), month.startDate)
        assertEquals(28, month.dayCount)
        assertEquals(Instant.parse("2026-01-31T16:00:00Z"), instantOf(month.window.startEpochNanos))
        assertEquals(Instant.parse("2026-02-28T16:00:00Z"), instantOf(month.window.endEpochNanos))

        val leap = (P806Periods.resolve(P806Period(P806PeriodKind.MONTH, month = YearMonth(2028, Month.FEBRUARY)), today, null) as P806PeriodResolution.Resolved).period
        assertEquals(29, leap.dayCount)

        val year = (P806Periods.resolve(P806Period(P806PeriodKind.YEAR, year = 2026), today, null) as P806PeriodResolution.Resolved).period
        assertEquals(365, year.dayCount)
        val leapYear = (P806Periods.resolve(P806Period(P806PeriodKind.YEAR, year = 2028), today, null) as P806PeriodResolution.Resolved).period
        assertEquals(366, leapYear.dayCount)

        val custom =
            (P806Periods.resolve(
                P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 3, 1), customEnd = LocalDate(2026, 3, 3)),
                today,
                null,
            ) as P806PeriodResolution.Resolved).period
        assertEquals(3, custom.dayCount)
        assertEquals(LocalDate(2026, 3, 3), custom.endDateInclusive)
    }

    @Test
    fun customPeriodThatEndsBeforeItStartsIsRejected() {
        val resolution =
            P806Periods.resolve(
                P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 3, 5), customEnd = LocalDate(2026, 3, 1)),
                today,
                null,
            )
        assertEquals(P806PeriodResolution.InvalidWindow, resolution)
    }

    @Test
    fun allPeriodUsesTheSpanInclusivelyAndAnEmptyLedgerIsNotApplicable() {
        assertEquals(P806PeriodResolution.NotApplicable, P806Periods.resolve(P806Period(P806PeriodKind.ALL), today, null))

        val span =
            P806Span(
                earliest = Instant.parse("2026-01-15T02:00:00Z"),
                latest = Instant.parse("2026-12-31T16:00:00Z"),
            )
        val period = (P806Periods.resolve(P806Period(P806PeriodKind.ALL), today, span) as P806PeriodResolution.Resolved).period
        assertEquals(LocalDate(2026, 1, 15), period.startDate)
        // 2026-12-31T16:00Z is 2027-01-01 local, so the inclusive end date is 2027-01-01.
        assertEquals(LocalDate(2027, 1, 1), period.endDateInclusive)
        assertEquals(352, period.dayCount)
    }

    @Test
    fun monthDayBucketsUseTheZonesRealZeroPoints() {
        val buckets = requireNotNull(P806Periods.dayBuckets(YearMonth(2026, Month.NOVEMBER)))
        assertEquals(31, buckets.size)
        assertEquals(LocalDate(2026, 11, 1), buckets.first().date)
        assertEquals(LocalDate(2026, 11, 30), buckets.last().date)
        assertEquals(Instant.parse("2026-10-31T16:00:00Z"), instantOf(buckets.first().window.startEpochNanos))
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"), instantOf(buckets.last().window.endEpochNanos))
    }

    /**
     * V-15: the day containing China's 1986 daylight-saving change is 23 hours long under the real
     * zone rules. A fixed +08:00 implementation would call it 24 hours and start the next day an
     * hour late, so this vector fails on that substitution.
     */
    @Test
    fun historicalDaylightSavingDayHasTwentyThreeHours() {
        val buckets = requireNotNull(P806Periods.dayBuckets(YearMonth(1986, Month.MAY)))
        val dstDay = buckets.first { it.date == LocalDate(1986, 5, 4) }
        val lengthNanos = dstDay.window.endEpochNanos - dstDay.window.startEpochNanos
        assertEquals(23L * 60L * 60L * 1_000_000_000L, lengthNanos)
        assertEquals(Instant.parse("1986-05-03T16:00:00Z"), instantOf(dstDay.window.startEpochNanos))
    }

    @Test
    fun signedDailyAverageIsNearestWithTiesAwayFromZero() {
        assertEquals(3L, P806Periods.dailyAverageMinorUnits(5, 2))
        assertEquals(-3L, P806Periods.dailyAverageMinorUnits(-5, 2))
        assertEquals(1L, P806Periods.dailyAverageMinorUnits(1, 2))
        assertEquals(-1L, P806Periods.dailyAverageMinorUnits(-1, 2))
        assertEquals(1L, P806Periods.dailyAverageMinorUnits(4, 3))
        assertEquals(-1L, P806Periods.dailyAverageMinorUnits(-4, 3))
        assertEquals(2L, P806Periods.dailyAverageMinorUnits(5, 3))
        assertEquals(-2L, P806Periods.dailyAverageMinorUnits(-5, 3))
        assertEquals(0L, P806Periods.dailyAverageMinorUnits(0, 30))
        assertNull(P806Periods.dailyAverageMinorUnits(100, 0))
    }

    /** V-17: the extreme dividend is rounded without ever forming `abs(dividend)` or `2 * remainder`. */
    @Test
    fun extremeDividendsDoNotOverflow() {
        assertEquals(Long.MIN_VALUE, P806Periods.dailyAverageMinorUnits(Long.MIN_VALUE, 1))
        assertEquals(Long.MAX_VALUE, P806Periods.dailyAverageMinorUnits(Long.MAX_VALUE, 1))
        val halfOfMin = Long.MIN_VALUE / 2
        assertEquals(halfOfMin, P806Periods.dailyAverageMinorUnits(Long.MIN_VALUE, 2))
        assertTrue(P806Periods.dailyAverageMinorUnits(Long.MIN_VALUE + 1, 3) != null)
    }

    @Test
    fun futureThresholdIsTheNextLocalMidnight() {
        // Local midnight of 2026-11-18 in Shanghai is 2026-11-17T16:00:00Z.
        assertEquals(
            Instant.parse("2026-11-17T16:00:00Z").toEpochMilliseconds() * 1_000_000L,
            P806Periods.nextLocalMidnightEpochNanos(today),
        )
    }

    @Test
    fun geometryValidationRejectsBadCustomWindowsWithoutAnyLedgerSpan() {
        val emptyLedger = P806Periods.validateGeometry(
            P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 3, 5), customEnd = LocalDate(2026, 3, 1)),
            today,
        )
        assertEquals(P806PeriodResolution.InvalidWindow, emptyLedger)

        val unrepresentable = P806Periods.validateGeometry(
            P806Period(P806PeriodKind.CUSTOM, customStart = LocalDate(2026, 3, 1), customEnd = LocalDate(999999, 12, 31)),
            today,
        )
        assertEquals(P806PeriodResolution.InvalidWindow, unrepresentable)

        assertTrue(P806Periods.validateGeometry(P806Period(P806PeriodKind.MONTH), today) is P806PeriodResolution.Resolved)
        assertTrue(P806Periods.validateGeometry(P806Period(P806PeriodKind.WEEK), today) is P806PeriodResolution.Resolved)
        assertEquals(P806PeriodResolution.NotApplicable, P806Periods.validateGeometry(P806Period(P806PeriodKind.ALL), today))
    }
    @Test
    fun anUnrepresentableMonthIsAnInvalidWindowNotACrash() {
        // 999999-12 is the documented LocalDate upper boundary: the next month cannot be built.
        assertEquals(null, P806Periods.dayBuckets(YearMonth(999999, Month.DECEMBER)))
        assertEquals(
            P806PeriodResolution.InvalidWindow,
            P806Periods.resolve(P806Period(P806PeriodKind.MONTH, month = YearMonth(999999, Month.DECEMBER)), today, null),
        )
        assertEquals(
            P806PeriodResolution.InvalidWindow,
            P806Periods.validateGeometry(P806Period(P806PeriodKind.MONTH, month = YearMonth(999999, Month.DECEMBER)), today),
        )
    }

    @Test
    fun epochNanosIsCheckedWithoutLosingPrecisionOrRejectingRepresentableNegatives() {
        assertEquals(500_000_000L, p806EpochNanos(Instant.fromEpochSeconds(0L, 500_000_000)))
        assertEquals(-500_000_000L, p806EpochNanos(Instant.fromEpochSeconds(-1L, 500_000_000)))
        // Exact Long boundaries: the largest representable nanosecond instant and the smallest.
        assertEquals(Long.MAX_VALUE, p806EpochNanos(Instant.fromEpochSeconds(9_223_372_036L, 854_775_807)))
        assertEquals(Long.MIN_VALUE, p806EpochNanos(Instant.fromEpochSeconds(-9_223_372_037L, 145_224_192)))
        // One second beyond either end cannot be represented and must throw, never wrap.
        assertFailsWith<ArithmeticException> { p806EpochNanos(Instant.fromEpochSeconds(9_223_372_037L, 0)) }
        assertFailsWith<ArithmeticException> { p806EpochNanos(Instant.fromEpochSeconds(-9_223_372_038L, 0)) }
        // A pre-1970 instant keeps every nanosecond.
        assertEquals(-1_000_000_001L, p806EpochNanos(Instant.fromEpochSeconds(-2L, 999_999_999)))
    }
    private fun instantOf(epochNanos: Long): Instant = Instant.fromEpochMilliseconds(epochNanos / 1_000_000L)
}
