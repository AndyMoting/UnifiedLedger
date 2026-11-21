package com.unifiedledger.ui

import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Instant

/*
 * P8-06 batch 4 (D-362 1; spec sections 3.7/3.8/3.10): the monthly calendar's read glue.
 *
 * One calendar read is one P806 calendar snapshot (one entry per local day of the month, each with
 * its own half-open window, per-currency aggregates and one GLOBAL distinct transaction count) under
 * ONE acquire-first lease. The capture binds the request identity (token, ledger, YearMonth) to the
 * acquire-time generation, to the QUERY INSTANCE's construction catalog version — never to the
 * catalog's current value — and to the read instant the surface may label. The landing decision
 * compares that capture with the active generation and the catalog's current version (comparison
 * only) and requires the request to still be the surface's ACTIVE request for the SAME month.
 *
 * A month is passed EXPLICITLY to the query, so the UI never resolves the current month and the
 * read never touches the clock for geometry; the clock only supplies an optional as-of label from
 * the existing source. All Shanghai day-boundary arithmetic stays in the application layer.
 */

/**
 * The identity of one calendar-month request (D-343 section 3.7 / D-362 1: request identity).
 *
 * [token] is the host's monotonic request token; [month] is the calendar's own cursor. A month
 * switch mints a new token, so a payload of the previous month can never land on the new one.
 */
data class P806CalendarRequestIdentity(
    val token: Long,
    val ledgerId: LedgerId,
    val month: YearMonth,
)

/**
 * One completed calendar read: the request, the lease generation it ran under, the catalog version
 * its query instance was constructed with, and the application read instant of THIS call ([asOf],
 * `null` when the existing source has no usable time — the surface then shows no as-of label and
 * still displays the payload; unlike the report read, the calendar's month is explicit, so no
 * clock is required for the read itself).
 */
data class P806CalendarReadCapture(
    val request: P806CalendarRequestIdentity,
    val generation: Generation,
    val catalogVersion: Long,
    val asOf: Instant?,
)

/** What one calendar read produced (D-343 section 3.7: typed states, never a fake zero month). */
sealed interface P806CalendarRead {
    /** The request this read was issued for; the landing decision matches it first. */
    val request: P806CalendarRequestIdentity

    /** The read ran under a lease and produced a typed snapshot result. */
    data class Landed(
        val capture: P806CalendarReadCapture,
        val result: P806SnapshotResult<P806CalendarView>,
    ) : P806CalendarRead {
        override val request: P806CalendarRequestIdentity get() = capture.request
    }

    /** The runtime refused the lease (not Ready): nothing ran, so no generation is claimed. */
    data class Refused(
        override val request: P806CalendarRequestIdentity,
    ) : P806CalendarRead

    /** This graph wires no P806 calendar query; the lease WAS acquired, so the generation is real. */
    data class Unwired(
        override val request: P806CalendarRequestIdentity,
        val generation: Generation,
    ) : P806CalendarRead
}

/**
 * The landing verdict of one calendar read (D-362 1).
 *
 * Every read variant first passes the ACTIVE-request gate: a superseded token ([STALE_TOKEN]), a
 * different ledger ([STALE_LEDGER]) or a request for a month the cursor has left ([STALE_MONTH])
 * drops the landing whole and triggers no re-read. For a landed read the current generation
 * ([STALE_GENERATION]), the current catalog version ([CATALOG_ADVANCED]) and the payload's own
 * ledger/month identity and one-snapshot shape ([PAYLOAD_REJECTED]) are checked in that order; only
 * a fully matching read is [ACCEPTED]. [Refused] and [Unwired] pass the gate ([ACCEPTED]) so the
 * reducer can land their distinguishable faces. The current catalog version is only compared, never
 * used to stamp a payload.
 */
internal enum class P806CalendarLandingDecision {
    ACCEPTED,
    STALE_TOKEN,
    STALE_LEDGER,
    STALE_MONTH,
    STALE_GENERATION,
    CATALOG_ADVANCED,
    PAYLOAD_REJECTED,
}

/**
 * The lease seam the calendar reads run under: the production adapter
 * [LeaseScopeCalendarSeam] delegates to the owner's acquire-first path, so a graph swap between
 * "find the facade" and "acquire the lease" cannot pair an old facade with a new generation.
 */
fun interface P806CalendarLeaseSeam {
    fun read(request: P806CalendarRequestIdentity): P806CalendarRead
}

/** The production seam: every calendar read runs through the owner's acquire-first order. */
internal class LeaseScopeCalendarSeam(
    private val scope: LedgerLeaseScope,
) : P806CalendarLeaseSeam {
    override fun read(request: P806CalendarRequestIdentity): P806CalendarRead =
        when (val outcome = scope.leasedReportRead { facade, generation -> readP806Calendar(facade, generation, request) }) {
            LeaseOutcome.NotReady -> P806CalendarRead.Refused(request)
            is LeaseOutcome.Completed -> outcome.value
        }
}

/**
 * The host-owned request-token sequence of the calendar reads. It lives with the composition
 * rather than with the surface state, so closing and reopening (even the same month) hands out
 * NEWER tokens — a read still in flight from the previous open can never match the reopened
 * surface. Called from the main thread only.
 */
internal class P806CalendarRequestSequence {
    private var last = 0L

    /** The next monotonic token (never reused, never decreasing). */
    fun next(): Long {
        last += 1
        return last
    }
}

/**
 * Reads one calendar month: resolve THAT graph's facade and run the snapshot read with the
 * request's explicit month. A null query is the honest [Unwired]; an unusable clock only drops the
 * as-of label, never the read. The read-port throw is already mapped to a typed failure by the
 * application use case, so a `Failed` here is a landed, displayed result.
 *
 * Kept separate from [LeaseScopeCalendarSeam] so the production logic is testable against the REAL
 * query without constructing a runtime owner.
 */
internal fun readP806Calendar(
    facade: P503LedgerFacade,
    generation: Generation,
    request: P806CalendarRequestIdentity,
): P806CalendarRead {
    val query = facade.p806CalendarQuery ?: return P806CalendarRead.Unwired(request, generation)
    val asOf =
        try {
            facade.ledgerClock.now()
        } catch (clockFailure: Exception) {
            null
        }
    val result = query.query(request.month)
    return P806CalendarRead.Landed(
        capture = P806CalendarReadCapture(request = request, generation = generation, catalogVersion = query.catalogVersion, asOf = asOf),
        result = result,
    )
}

/**
 * Whether one success payload is ONE consistent month snapshot for [request] (D-343 3.7/3.10): the
 * payload belongs to the request's ledger and month, its axis has no duplicate currency, and its days
 * are the month's own days in ascending order with no duplicate — every day present exactly once,
 * including zero-contribution days. Each day's per-currency rows are the same ordered axis (the axis
 * rule was applied inside the read transaction), so the UI may render every date cell without
 * inventing or merging a currency. A currency the previous payload did not carry is NOT a mismatch:
 * the new snapshot's axis is its own truth.
 */
internal fun p806CalendarPayloadAccepts(
    view: P806CalendarView,
    request: P806CalendarRequestIdentity,
): Boolean {
    if (view.ledgerId != request.ledgerId) return false
    if (view.month != request.month) return false
    val axis = view.axis
    if (axis.size != axis.distinct().size) return false
    val dates = view.days.map { day -> day.date }
    if (dates.size != dates.distinct().size) return false
    if (dates != dates.sorted()) return false
    if (!dates.all { date -> YearMonth(date.year, date.month) == request.month }) return false
    if (dates.size != p806CalendarDayCount(request.month)) return false
    return view.days.all { day -> day.currencies.map { row -> row.currency } == axis }
}

/** The number of local dates in [month] (leap years and 30-day months included). */
internal fun p806CalendarDayCount(month: YearMonth): Int {
    val first = LocalDate(month.year, month.month, 1)
    val nextMonthStart = first.plus(1, DateTimeUnit.MONTH)
    return nextMonthStart.minus(1, DateTimeUnit.DAY).dayOfMonth
}

/**
 * The landing verdict, evaluated on the UI thread against the CURRENT generation and catalog
 * version. A landing is considered only while the surface still marks THIS request in flight and
 * only for the request's own ledger and CURRENT month; the captures are compared as captured, and
 * the current catalog version is only compared, never used to stamp a payload.
 */
internal fun p806CalendarLandingDecision(
    state: P503AppState.Calendar,
    read: P806CalendarRead,
    currentGeneration: Generation?,
    currentCatalogVersion: Long?,
): P806CalendarLandingDecision {
    val request = read.request
    if (request.token != state.requestToken) return P806CalendarLandingDecision.STALE_TOKEN
    if (request.ledgerId != state.overview.state.ledgerId) return P806CalendarLandingDecision.STALE_LEDGER
    if (request.month != state.month) return P806CalendarLandingDecision.STALE_MONTH
    return when (read) {
        is P806CalendarRead.Refused -> P806CalendarLandingDecision.ACCEPTED
        is P806CalendarRead.Unwired ->
            if (currentGeneration == null || read.generation != currentGeneration) {
                P806CalendarLandingDecision.STALE_GENERATION
            } else {
                P806CalendarLandingDecision.ACCEPTED
            }
        is P806CalendarRead.Landed -> {
            val capture = read.capture
            val payloadAccepted =
                when (val result = read.result) {
                    is P806SnapshotResult.Success -> p806CalendarPayloadAccepts(result.snapshot, request)
                    is P806SnapshotResult.Failed -> true
                }
            when {
                capture.request != request -> P806CalendarLandingDecision.STALE_TOKEN
                currentGeneration == null || capture.generation != currentGeneration -> P806CalendarLandingDecision.STALE_GENERATION
                currentCatalogVersion == null || capture.catalogVersion != currentCatalogVersion -> P806CalendarLandingDecision.CATALOG_ADVANCED
                !payloadAccepted -> P806CalendarLandingDecision.PAYLOAD_REJECTED
                else -> P806CalendarLandingDecision.ACCEPTED
            }
        }
    }
}
