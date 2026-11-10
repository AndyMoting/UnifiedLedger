package com.unifiedledger.data

import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408TemporalComponents
import com.unifiedledger.application.P408TemporalEvidence
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
    fun signedAmount(
        amountMinor: Long,
        direction: String,
    ): Long? {
        if (amountMinor == Long.MIN_VALUE) return null
        val absolute = abs(amountMinor)
        return when (direction) {
            "out" -> -absolute
            "in" -> absolute
            else -> null
        }
    }

    fun naturalDayDistance(
        source: String,
        posting: String,
    ): Int? {
        if (!temporalComparableRaw(source, posting)) return null
        val sourceInstant = runCatching { Instant.parse(source) }.getOrNull() ?: return null
        val postingInstant = runCatching { Instant.parse(posting) }.getOrNull() ?: return null
        val localOffsetSeconds = P408Matcher.DEFAULT_LOCAL_OFFSET_SECONDS
        val sourceDay = floorDivEpochSeconds(sourceInstant.epochSeconds + localOffsetSeconds)
        val postingDay = floorDivEpochSeconds(postingInstant.epochSeconds + localOffsetSeconds)
        val distance = abs(sourceDay - postingDay)
        return distance.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
    }

    fun temporalComparableRaw(
        source: String,
        posting: String,
    ): Boolean {
        val sourceHasOffset = hasExplicitOffset(source)
        val postingHasOffset = hasExplicitOffset(posting)
        if (!sourceHasOffset || !postingHasOffset) return false
        return temporalShape(source) == temporalShape(posting)
    }

    fun hasExplicitOffset(value: String): Boolean =
        value.endsWith('Z') ||
            (value.length >= 6 && value[value.length - 6] in setOf('+', '-') && value[value.length - 3] == ':')

    fun temporalShape(value: String): String =
        buildString(value.length) {
            value.forEach { append(if (it in '0'..'9') '#' else it) }
        }

    fun floorDivEpochSeconds(value: Long): Long {
        val quotient = value / SECONDS_PER_DAY
        return if (value % SECONDS_PER_DAY < 0) quotient - 1 else quotient
    }

    /**
     * P8-01a: the frozen matcher's temporal evidence for one PERSISTED time text (D-103).
     *
     * The matcher decides comparability from the shape, never from a re-serialised instant, so the
     * raw text is carried verbatim and the shape facts are derived from it here, once, for both the
     * evidence and the posting side. Three mutually exclusive kinds:
     *
     * - `offset_datetime` — an explicit `Z`/`±hh:mm` offset whose text also parses as an instant;
     *   the only comparable kind.
     * - `local_datetime` — no explicit offset (including an absent/empty text). A wall-clock text
     *   carries no instant, so it is never comparable (the matcher reports
     *   `source_time_unresolved`).
     * - `unparsable_datetime` — an offset-shaped text the instant parser rejects; equally never
     *   comparable, kept as its own token so a corrupt value is not described as offset-free.
     *
     * The numeric components are only offered when the text really has the frozen
     * `YYYY-MM-DDTHH:MM:SS` prefix; the matcher re-checks every one of them against the text.
     */
    fun temporalEvidence(rawText: String?): P408TemporalEvidence {
        val text = rawText.orEmpty()
        val offsetPresent = text.isNotEmpty() && hasExplicitOffset(text)
        val instant = if (offsetPresent) runCatching { Instant.parse(text) }.getOrNull() else null
        val kind =
            when {
                instant != null -> OFFSET_DATETIME
                offsetPresent -> UNPARSABLE_DATETIME
                else -> LOCAL_DATETIME
            }
        return P408TemporalEvidence(
            rawText = text,
            kind = kind,
            offsetPresent = offsetPresent,
            components = componentsOf(text),
            instant = instant,
        )
    }

    /** The frozen `YYYY-MM-DDTHH:MM:SS` prefix as components, or `null` when the text differs. */
    private fun componentsOf(text: String): P408TemporalComponents? {
        if (text.length < FROZEN_PREFIX_LENGTH ||
            text[4] != '-' ||
            text[7] != '-' ||
            text[10] != 'T' ||
            text[13] != ':' ||
            text[16] != ':'
        ) {
            return null
        }
        return P408TemporalComponents(
            year = component(text, 0, 4) ?: return null,
            month = component(text, 5, 7) ?: return null,
            day = component(text, 8, 10) ?: return null,
            hour = component(text, 11, 13) ?: return null,
            minute = component(text, 14, 16) ?: return null,
            second = component(text, 17, 19) ?: return null,
        )
    }

    private fun component(
        text: String,
        start: Int,
        end: Int,
    ): Int? {
        val value = text.substring(start, end)
        return if (value.all { it in '0'..'9' }) value.toIntOrNull() else null
    }

    private const val OFFSET_DATETIME = "offset_datetime"
    private const val LOCAL_DATETIME = "local_datetime"
    private const val UNPARSABLE_DATETIME = "unparsable_datetime"
    private const val FROZEN_PREFIX_LENGTH = 19
}
