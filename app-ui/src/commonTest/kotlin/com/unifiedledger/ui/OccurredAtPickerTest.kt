package com.unifiedledger.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * D-131 R2: tests for the fixed-zone occurred-at conversion and display formatting
 * (spec 3.3/3.4). Asia/Shanghai is frozen and never follows the device time zone;
 * historical DST gaps fail closed (null) instead of being guessed.
 */
class OccurredAtPickerTest {
    @Test
    fun convertsShanghaiLocalDateTimeToUtcInstant() {
        assertEquals(
            Instant.parse("2026-01-15T00:30:00Z"),
            occurredAtFromLocalDateTime(LocalDateTime(2026, 1, 15, 8, 30)),
        )
    }

    @Test
    fun conversionRoundTripsForValidLocalTimes() {
        val instant = Instant.parse("2026-01-15T00:30:00Z")
        val local = instant.toLocalDateTime(occurredAtTimeZone)
        assertEquals(instant, occurredAtFromLocalDateTime(local))
    }

    @Test
    fun rejectsLocalTimesInsideHistoricalDstGaps() {
        // Asia/Shanghai DST spring-forward gaps (tzdb): 1986-05-04 02:00 and 1991-04-14 02:00.
        assertNull(occurredAtFromLocalDateTime(LocalDateTime(1986, 5, 4, 2, 30)))
        assertNull(occurredAtFromLocalDateTime(LocalDateTime(1991, 4, 14, 2, 30)))
    }

    @Test
    fun acceptsLocalTimesAdjacentToDstGaps() {
        assertEquals(
            Instant.parse("1986-05-03T17:30:00Z"),
            occurredAtFromLocalDateTime(LocalDateTime(1986, 5, 4, 1, 30)),
        )
        assertEquals(
            Instant.parse("1986-05-03T18:30:00Z"),
            occurredAtFromLocalDateTime(LocalDateTime(1986, 5, 4, 3, 30)),
        )
    }

    @Test
    fun appliesTheHistoricalDstOffset() {
        assertEquals(
            Instant.parse("1990-06-15T02:00:00Z"),
            occurredAtFromLocalDateTime(LocalDateTime(1990, 6, 15, 11, 0)),
        )
    }

    @Test
    fun pickerDateFromUtcMillisUsesUtc() {
        val millis = Instant.parse("2026-01-15T00:30:00Z").toEpochMilliseconds()
        assertEquals(LocalDate(2026, 1, 15), occurredAtPickerLocalDate(millis))
    }

    @Test
    fun pickerInitialMillisUsesTheShanghaiLocalDate() {
        // 18:30Z is 02:30 on 01-16 in Shanghai: the DatePicker must open on 01-16, not on
        // the UTC calendar day 01-15 (review finding INPUTUX-IMPL-001).
        assertEquals(
            Instant.parse("2026-01-16T00:00:00Z").toEpochMilliseconds(),
            occurredAtPickerInitialMillis(Instant.parse("2026-01-15T18:30:00Z")),
        )
        // A morning UTC instant stays on the same Shanghai calendar day.
        assertEquals(
            Instant.parse("2026-01-15T00:00:00Z").toEpochMilliseconds(),
            occurredAtPickerInitialMillis(Instant.parse("2026-01-15T00:30:00Z")),
        )
    }

    @Test
    fun displayFormatShowsLocalWallClockAndUtcAfter1991() {
        assertEquals(
            "2026-01-15 08:30（UTC+8）＝ 2026-01-15T00:30:00Z",
            occurredAtDisplayText(Instant.parse("2026-01-15T00:30:00Z")),
        )
        assertEquals(
            "2026-01-05 09:05（UTC+8）＝ 2026-01-05T01:05:00Z",
            occurredAtDisplayText(Instant.parse("2026-01-05T01:05:00Z")),
        )
        assertEquals(
            "1992-01-15 08:30（UTC+8）＝ 1992-01-15T00:30:00Z",
            occurredAtDisplayText(Instant.parse("1992-01-15T00:30:00Z")),
        )
    }

    @Test
    fun displayFormatOmitsTheUtcAnnotationBefore1992() {
        // DST-era dates carry +09:00 and pre-1992 dates are not annotated (spec 3.4).
        assertEquals(
            "1990-06-15 11:00",
            occurredAtDisplayText(Instant.parse("1990-06-15T02:00:00Z")),
        )
        assertEquals(
            "1991-09-16 08:30",
            occurredAtDisplayText(Instant.parse("1991-09-16T00:30:00Z")),
        )
    }
}
