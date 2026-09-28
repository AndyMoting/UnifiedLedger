package com.unifiedledger.data

import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/*
 * P7-07 07.T (D-184 item 4; spec sections 6.4/6.5): the Kotlin half of the numeric time
 * projection.
 *
 * The projection is the epoch-nanosecond value the SQL expression at
 * `transaction_version_statistics_at_range_idx` (Ledger.sq) writes into
 * `transaction_version.statistics_at_epoch_nanos`. This file provides the equivalent pure
 * function so that:
 *
 *   1. a reader can turn a projected Long back into the exact `Instant` (lossless in both
 *      directions for the whole supported window), and
 *   2. a test can assert the SQL backfill and the Kotlin projection agree for every observed
 *      historical shape, so the two cannot drift.
 *
 * PRECISION BOUND (spec section 6.5 option (b)): epoch nanoseconds. `kotlin.time.Instant` is
 * exactly `epochSeconds` + `nanosecondsOfSecond`, so `toEpochNanoseconds()` is lossless for any
 * Instant and `fromEpochNanoseconds` is its exact inverse. No writer is constrained and no
 * sub-microsecond input is truncated. A microsecond projection would only be lossless under the
 * unproven "writers never emit sub-microsecond" premise, which the spec forbids asserting
 * without declaring the bound.
 *
 * WINDOW: the 64-bit Long nanosecond range covers 1678-09-21T00:12:44Z .. 2262-04-11T23:47:16Z.
 * A value outside it cannot be represented and is refused (the SQL expression projects NULL for
 * it, and the migration pre-check rejects such a database rather than bricking it).
 */
object StatisticsAtProjection {
    /**
     * The whole-second bounds inside which `epochSeconds * 1_000_000_000` stays a 64-bit Long.
     * Mirrors the SQL `NOT BETWEEN -9223372035 AND 9223372035` guard: the extra headroom to the
     * exact `Long.MAX_VALUE / 1_000_000_000` (9223372036) leaves room for the fractional
     * nanoseconds added on top, so a seconds value in this range can never overflow when the
     * nanosecond-of-second is added.
     */
    const val MIN_EPOCH_SECONDS: Long = -9_223_372_035L

    const val MAX_EPOCH_SECONDS: Long = 9_223_372_035L

    /** The projected value for [instant], or null when the instant is outside the 64-bit window. */
    fun project(instant: Instant): Long? {
        val seconds = instant.epochSeconds
        if (seconds < MIN_EPOCH_SECONDS || seconds > MAX_EPOCH_SECONDS) return null
        return seconds * NANOS_PER_SECOND + instant.nanosecondsOfSecond
    }

    /** The exact inverse of [project]; null when [epochNanos] is absent (a missing projection). */
    fun toInstant(epochNanos: Long?): Instant? {
        if (epochNanos == null) return null
        val seconds = floorDiv(epochNanos, NANOS_PER_SECOND)
        val nanos = (epochNanos - seconds * NANOS_PER_SECOND).toInt()
        return Instant.fromEpochSeconds(seconds, nanos)
    }

    private const val NANOS_PER_SECOND: Long = 1_000_000_000L

    /** Floor division by a positive divisor (Kotlin's `/` truncates toward zero). */
    private fun floorDiv(
        value: Long,
        divisor: Long,
    ): Long {
        val quotient = value / divisor
        return if (value % divisor != 0L && (value xor divisor) < 0L) quotient - 1 else quotient
    }
}

/**
 * The typed failure a projection-dependent read raises when a version row in scope has no
 * projection (spec section 6.4 item 5: fail loudly, never fall back to raw-text comparison and
 * never treat the instant as time zero).
 *
 * The message carries only counts, never raw personal time text.
 */
class MissingStatisticsAtProjectionException(
    val ledgerId: String,
    val missingCount: Long,
) : IllegalStateException(
        "statistics_at projection missing for $missingCount version row(s) in ledger $ledgerId; " +
            "refusing to read a time-bounded window (fail-loud, spec 6.4 item 5)",
    )

/** One projected version row: the transaction/version identity and its epoch-nanosecond instant. */
data class ProjectedVersionRow(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val statisticsAtEpochNanos: Long,
) {
    /** The exact instant of this row's projection. */
    val statisticsAt: Instant get() = requireNotNull(StatisticsAtProjection.toInstant(statisticsAtEpochNanos))
}

/**
 * P7-07 07.T (D-184 item 4; spec section 6.4 items 2/5): the projection-backed bounded read over
 * a statistical window, plus the fail-loud guard that makes the projection's absence a visible
 * failure instead of a silent time zero.
 *
 * This is the read surface the future budget/period reads build on (07.D consumes it); it does
 * not itself change any existing read contract (spec section 8 open item 3 keeps the existing
 * monthly card switch OPEN). The bounded query uses the range index
 * `(ledger_id, statistics_at_epoch_nanos, transaction_id)`.
 */
class SqlDelightStatisticsAtProjectionReadPort(
    private val database: LedgerDatabase,
) {
    /**
     * The projected rows whose statistics instant is in `[startInclusive, endExclusive)`.
     *
     * FAIL-LOUD (spec section 6.4 item 5): before returning any row, this counts the ledger's
     * rows with a NULL projection and raises [MissingStatisticsAtProjectionException] when the
     * count is non-zero. A windowed read must never silently omit or zero an unprojected row.
     */
    fun projectedVersionsInWindow(
        ledgerId: LedgerId,
        startInclusiveEpochNanos: Long,
        endExclusiveEpochNanos: Long,
    ): List<ProjectedVersionRow> {
        val missing = database.ledgerQueries.statisticsAtProjectionMissingForLedger(ledgerId.value).executeAsOne()
        if (missing != 0L) {
            throw MissingStatisticsAtProjectionException(ledgerId.value, missing)
        }
        return database.ledgerQueries
            .projectedVersionsInStatisticsWindow(ledgerId.value, startInclusiveEpochNanos, endExclusiveEpochNanos)
            .executeAsList()
            .map { row ->
                ProjectedVersionRow(
                    transactionId = TransactionId(row.transaction_id),
                    versionId = TransactionVersionId(row.version_id),
                    statisticsAtEpochNanos =
                        requireNotNull(row.statistics_at_epoch_nanos) {
                            "projectedVersionsInStatisticsWindow returned a NULL projection"
                        },
                )
            }
    }
}
