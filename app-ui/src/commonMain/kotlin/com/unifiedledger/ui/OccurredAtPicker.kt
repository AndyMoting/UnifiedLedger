package com.unifiedledger.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * D-131 R2: fixed-zone conversion and display formatting for the occurred-at picker
 * (spec 3.3/3.4). Asia/Shanghai is frozen (+08:00, the golden-fixture timezone lock) and
 * never follows the device time zone. Conversion is fail-closed: a local date-time that
 * falls into a historical DST gap round-trips to a different local value and is rejected
 * (null) instead of being guessed or silently shifted.
 */
internal val occurredAtTimeZone: TimeZone = TimeZone.of("Asia/Shanghai")

/**
 * Converts a picker-selected local date-time to a [kotlin.time.Instant] under
 * [occurredAtTimeZone], or `null` when the local time does not exist (DST gap): the
 * instant must convert back to exactly the selected local value, otherwise the selection
 * is invalid and must never be dispatched.
 */
internal fun occurredAtFromLocalDateTime(local: LocalDateTime): Instant? {
    val instant = local.toInstant(occurredAtTimeZone)
    return if (instant.toLocalDateTime(occurredAtTimeZone) == local) instant else null
}

/**
 * Local date for the DatePicker's UTC millis (DatePickerState.selectedDateMillis is a UTC
 * epoch value; converting through [TimeZone.UTC] keeps the picked calendar date exact).
 */
internal fun occurredAtPickerLocalDate(epochMilliseconds: Long): LocalDate = Instant.fromEpochMilliseconds(epochMilliseconds).toLocalDateTime(TimeZone.UTC).date

/**
 * Frozen confirmation format (spec 3.4): `YYYY-MM-DD HH:mm` Asia/Shanghai wall clock, the
 * `（UTC+8）` annotation and the UTC ISO string — the annotation is exact only for dates
 * after 1991 (no DST since then), so earlier dates render the local wall clock only.
 */
internal fun occurredAtDisplayText(instant: Instant): String {
    val local = instant.toLocalDateTime(occurredAtTimeZone)
    val wallClock = occurredAtWallClockText(local)
    return if (local.date.year > 1991) "$wallClock（UTC+8）＝ $instant" else wallClock
}

private fun occurredAtWallClockText(local: LocalDateTime): String = "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
