package com.unifiedledger.data

import com.unifiedledger.application.P408Matcher
import kotlin.math.abs
import kotlin.time.Instant

/**
 * Shared pure-computation helpers for the P4-08 store family (D-113 QUAL-005).
 * The confirmation and correction stores must apply identical signed-amount,
 * natural-day-distance and temporal-shape semantics, so these live here once
 * instead of being duplicated per store file.
 */
internal object P408Computation {
    const val SECONDS_PER_DAY: Long = 24 * 60 * 60

    /** Absolute-to-signed: out = negative, in = positive; null on invalid direction. */
    fun signedAmount(amountMinor: Long, direction: String): Long? {
        if (amountMinor == Long.MIN_VALUE) return null
        val absolute = abs(amountMinor)
        return when (direction) {
            "out" -> -absolute
            "in" -> absolute
            else -> null
        }
    }

    fun naturalDayDistance(source: String, posting: String): Int? {
        if (!temporalComparableRaw(source, posting)) return null
        val sourceInstant = runCatching { Instant.parse(source) }.getOrNull() ?: return null
        val postingInstant = runCatching { Instant.parse(posting) }.getOrNull() ?: return null
        val localOffsetSeconds = P408Matcher.DEFAULT_LOCAL_OFFSET_SECONDS
        val sourceDay = floorDivEpochSeconds(sourceInstant.epochSeconds + localOffsetSeconds)
        val postingDay = floorDivEpochSeconds(postingInstant.epochSeconds + localOffsetSeconds)
        val distance = abs(sourceDay - postingDay)
        return distance.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
    }

    fun temporalComparableRaw(source: String, posting: String): Boolean {
        val sourceHasOffset = hasExplicitOffset(source)
        val postingHasOffset = hasExplicitOffset(posting)
        if (!sourceHasOffset || !postingHasOffset) return false
        return temporalShape(source) == temporalShape(posting)
    }

    fun hasExplicitOffset(value: String): Boolean = value.endsWith('Z') ||
        (value.length >= 6 && value[value.length - 6] in setOf('+', '-') && value[value.length - 3] == ':')

    fun temporalShape(value: String): String = buildString(value.length) {
        value.forEach { append(if (it in '0'..'9') '#' else it) }
    }

    fun floorDivEpochSeconds(value: Long): Long {
        val quotient = value / SECONDS_PER_DAY
        return if (value % SECONDS_PER_DAY < 0) quotient - 1 else quotient
    }
}