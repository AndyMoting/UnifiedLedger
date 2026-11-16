package com.unifiedledger.application

import kotlinx.datetime.DateTimeArithmeticException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * P8-06 batch a (D-343; spec sections 3.1/3.3): the report period model and its Shanghai
 * half-open window, natural-day denominators and signed nearest rounding.
 *
 * Every period resolves to `[start, end)` in the frozen reporting zone
 * ([MonthlyBuckets.REPORT_TIME_ZONE]). Nothing here reads the database; the only external input
 * is the ledger span (earliest/latest effective statistics instant) that the snapshot read
 * returns for `ALL`.
 */

/** The five frozen period kinds (spec section 3.1.1). */
enum class P806PeriodKind {
    WEEK,
    MONTH,
    YEAR,
    ALL,
    CUSTOM,
}

/**
 * One requested period. Fields are used per [kind]; a field that the kind does not use stays
 * `null` and is ignored. [weekStart] is any date inside the wanted ISO week (Monday-based).
 */
data class P806Period(
    val kind: P806PeriodKind,
    val month: YearMonth? = null,
    val year: Int? = null,
    val weekStart: LocalDate? = null,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
) {
    // A CUSTOM period may arrive without one or both bounds: the shared resolver answers
    // InvalidWindow for that (a typed user-input outcome), so the constructor stays total.
}

/** A resolved period: the half-open window, the inclusive local dates and the natural-day count. */
data class P806ResolvedPeriod(
    val window: P806Window,
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val dayCount: Int,
) {
    init {
        require(dayCount > 0) { "a resolved period always spans at least one natural day" }
    }
}

/** The outcome of resolving a period: usable, not applicable (empty `ALL`), or rejected. */
sealed interface P806PeriodResolution {
    data class Resolved(
        val period: P806ResolvedPeriod,
    ) : P806PeriodResolution

    /** `ALL` on a ledger without a span has no period at all; the daily average is not applicable. */
    data object NotApplicable : P806PeriodResolution

    /** A custom window that ends before it starts, or that cannot be represented. */
    data object InvalidWindow : P806PeriodResolution
}

/** The natural-day denominator of a resolved window, in whole days (never shortened). */
object P806Periods {
    /** Monday-based ISO week start containing [date]. */
    fun weekStartOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)

    /** The first local midnight strictly after [today] — the future-marker threshold. */
    fun nextLocalMidnightEpochNanos(today: LocalDate): Long {
        val nextDay = today.plus(1, DateTimeUnit.DAY)
        return p806EpochNanos(nextDay.atStartOfDayIn(MonthlyBuckets.REPORT_TIME_ZONE))
    }

    /** Local day start of [date] in the reporting zone, in epoch nanoseconds. */
    fun localDayStartEpochNanos(date: LocalDate): Long =
        p806EpochNanos(date.atStartOfDayIn(MonthlyBuckets.REPORT_TIME_ZONE))

    /**
     * Resolves [period] against [today] (clock-resolved local date) and the ledger [span]
     * (earliest/latest effective statistics instant; `null` only for an empty ledger).
     *
     * Boundaries are inclusive local dates converted to half-open instants: a custom period's
     * end date is included, and `ALL` spans the earliest through the latest effective date.
     */
    fun resolve(
        period: P806Period,
        today: LocalDate,
        span: P806Span?,
    ): P806PeriodResolution =
        try {
            resolveUnchecked(period, today, span)
        } catch (dateArithmetic: DateTimeArithmeticException) {
            // kotlinx-datetime's own out-of-range arithmetic (RuntimeException family). The dates came
            // from the caller, so this is an invalid window, never a read failure.
            P806PeriodResolution.InvalidWindow
        } catch (dateArithmetic: ArithmeticException) {
            // Our checked epoch-nanosecond conversion refuses a value outside the Long range.
            P806PeriodResolution.InvalidWindow
        } catch (illegalDate: IllegalArgumentException) {
            P806PeriodResolution.InvalidWindow
        }

    /**
     * The span-INDEPENDENT geometry check: validates every kind whose window comes from dates alone
     * (WEEK/MONTH/YEAR/CUSTOM). `ALL` answers [P806PeriodResolution.NotApplicable] because its window
     * needs the ledger span. Callers run this BEFORE deciding that an empty ledger has no period, so
     * a bad custom window is `InvalidWindow` even on an empty ledger.
     */
    fun validateGeometry(
        period: P806Period,
        today: LocalDate,
    ): P806PeriodResolution = resolve(period, today, span = null)

    private fun resolveUnchecked(
        period: P806Period,
        today: LocalDate,
        span: P806Span?,
    ): P806PeriodResolution =
        when (period.kind) {
            P806PeriodKind.WEEK -> {
                val start = weekStartOf(period.weekStart ?: today)
                resolved(start, start.plus(7, DateTimeUnit.DAY))
            }
            P806PeriodKind.MONTH -> {
                val month = period.month ?: YearMonth(today.year, today.month)
                val start = month.firstDay
                val endExclusive = start.plus(1, DateTimeUnit.MONTH)
                resolved(start, endExclusive)
            }
            P806PeriodKind.YEAR -> {
                val year = period.year ?: today.year
                val start = LocalDate(year, 1, 1)
                resolved(start, LocalDate(year + 1, 1, 1))
            }
            P806PeriodKind.ALL -> {
                if (span == null) {
                    P806PeriodResolution.NotApplicable
                } else {
                    val start = span.earliest.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date
                    val last = span.latest.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date
                    resolved(start, last.plus(1, DateTimeUnit.DAY))
                }
            }
            P806PeriodKind.CUSTOM -> {
                val start = period.customStart
                val end = period.customEnd
                if (start == null || end == null || end < start) {
                    P806PeriodResolution.InvalidWindow
                } else {
                    resolved(start, end.plus(1, DateTimeUnit.DAY))
                }
            }
        }

    /**
     * The local day buckets of [month] (spec section 3.10.2): `<= 31` buckets built from the
     * zone's real zero-point boundaries, so historical daylight-saving offsets are handled by the
     * time-zone rules rather than by a fixed offset.
     */
    fun dayBuckets(month: YearMonth): List<P806DayBucket>? =
        try {
            dayBucketsUnchecked(month)
        } catch (dateArithmetic: DateTimeArithmeticException) {
            null
        } catch (dateArithmetic: ArithmeticException) {
            null
        } catch (illegalDate: IllegalArgumentException) {
            null
        }

    private fun dayBucketsUnchecked(month: YearMonth): List<P806DayBucket> {
        val first = month.firstDay
        val nextMonth = first.plus(1, DateTimeUnit.MONTH)
        val buckets = mutableListOf<P806DayBucket>()
        var date = first
        while (date < nextMonth) {
            buckets +=
                P806DayBucket(
                    date = date,
                    window =
                        P806Window(
                            startEpochNanos = localDayStartEpochNanos(date),
                            endEpochNanos = localDayStartEpochNanos(date.plus(1, DateTimeUnit.DAY)),
                        ),
                )
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return buckets
    }

    /**
     * The signed daily average of [netExpenseMinorUnits] over [dayCount] natural days
     * (spec section 3.3.2): nearest, with an exact half going away from zero.
     *
     * The quotient is truncated toward zero and the remainder keeps the dividend's sign, so
     * neither `abs(dividend)` nor `2 * remainder` is ever formed (both overflow at the extremes).
     * A `dayCount` of zero is not applicable and answers `null`.
     */
    fun dailyAverageMinorUnits(
        netExpenseMinorUnits: Long,
        dayCount: Int,
    ): Long? {
        if (dayCount <= 0) return null
        val divisor = dayCount.toLong()
        val quotient = netExpenseMinorUnits / divisor
        val remainder = netExpenseMinorUnits - quotient * divisor
        val magnitude = if (remainder < 0L) -remainder else remainder
        val towardZero = divisor - magnitude
        return if (magnitude < towardZero) quotient else quotient + if (netExpenseMinorUnits < 0L) -1L else 1L
    }

    private fun resolved(
        startDate: LocalDate,
        endExclusiveDate: LocalDate,
    ): P806PeriodResolution {
        val dayCount = startDate.daysUntil(endExclusiveDate)
        if (dayCount <= 0) return P806PeriodResolution.InvalidWindow
        val window =
            P806Window(
                startEpochNanos = localDayStartEpochNanos(startDate),
                endEpochNanos = localDayStartEpochNanos(endExclusiveDate),
            )
        if (window.endEpochNanos <= window.startEpochNanos) return P806PeriodResolution.InvalidWindow
        return P806PeriodResolution.Resolved(
            P806ResolvedPeriod(
                window = window,
                startDate = startDate,
                endDateInclusive = endExclusiveDate.minus(1, DateTimeUnit.DAY),
                dayCount = dayCount,
            ),
        )
    }
}

/** Epoch nanoseconds of an instant at full precision (never milliseconds). */
internal fun p806EpochNanos(instant: Instant): Long {
    // Full-precision epoch nanoseconds with a checked, KMP-only decomposition. Splitting the seconds
    // into a 10^3 high part and a low part keeps every intermediate product inside Long, so a value
    // that is representable (including negative epochs with a positive fraction) is never rejected,
    // and a value outside the Long range throws instead of wrapping silently.
    val seconds = instant.epochSeconds
    val fraction = instant.nanosecondsOfSecond.toLong()
    val high = seconds / P806_SECONDS_SPLIT
    val low = seconds % P806_SECONDS_SPLIT
    val highNanos = p806CheckedMultiply(high, P806_NANOS_PER_SPLIT)
    val lowNanos = p806CheckedMultiply(low, P806_NANOS_PER_SECOND)
    // Grouping matters at the Long.MIN end: for seconds = -9223372037 and fraction = 145224192 the
    // high term alone is -9223372000000000000 and the low term -37000000000, whose premature sum is
    // below Long.MIN; adding the (small, |.| <= 1e12) low term to the fraction first stays in range
    // and the final high addition lands exactly on Long.MIN. Out-of-range values still throw.
    val lowWithFraction = p806CheckedAdd(lowNanos, fraction)
    return p806CheckedAdd(highNanos, lowWithFraction)
}

private const val P806_SECONDS_SPLIT: Long = 1_000L
private const val P806_NANOS_PER_SECOND: Long = 1_000_000_000L
private const val P806_NANOS_PER_SPLIT: Long = 1_000_000_000_000L

private fun p806CheckedMultiply(
    left: Long,
    right: Long,
): Long {
    if (left == 0L || right == 0L) return 0L
    val product = left * right
    if (product / right != left) throw ArithmeticException("P806 epoch-nanosecond multiplication overflow")
    return product
}

private fun p806CheckedAdd(
    left: Long,
    right: Long,
): Long {
    val sum = left + right
    if (((left xor sum) and (right xor sum)) < 0L) throw ArithmeticException("P806 epoch-nanosecond addition overflow")
    return sum
}
