package com.unifiedledger.ui

import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/*
 * P8-06 batch b (D-343 sections 3.1/3.5/3.7): the analysis-page report region's read glue.
 *
 * One report read is one P806 report snapshot (the composite typed read: period amounts, counts,
 * the currency axis, the detail head) under ONE acquire-first lease. The capture binds the request
 * identity (token, ledger, period definition) to the acquire-time generation and to the QUERY
 * INSTANCE's construction catalog version — never to the catalog's current value — plus the
 * application read instant the surface shows as its as-of. The landing decision compares that
 * capture with the active generation and the catalog's current version (comparison only) and
 * requires the request to still be the slot's ACTIVE request; the payload itself must belong to one
 * snapshot.
 *
 * Nothing here labels a read as a database commit time, and nothing here performs Shanghai time
 * arithmetic: the period geometry belongs to the application layer.
 */

/**
 * The identity of one report request (D-343 section 3.7: request identity).
 *
 * [token] is the host's monotonic request token; [period] is the requested period definition
 * (kind plus its explicit anchor, or the custom dates). The parsed window is NOT part of the
 * identity: it is resolved by the application layer from the period and the ledger span, and a
 * stale resolution never outlives the request it was read for.
 */
data class P806ReportRequestIdentity(
    val token: Long,
    val ledgerId: LedgerId,
    val period: P806Period,
)

/**
 * One completed report read: the request, the lease generation it ran under, the catalog version
 * its query instance was constructed with, and the application read instant of THIS call
 * ([asOf], `null` when the clock was unusable — such a read is the typed `Unavailable`).
 */
data class P806ReportReadCapture(
    val request: P806ReportRequestIdentity,
    val generation: Generation,
    val catalogVersion: Long,
    val asOf: Instant?,
)

/** What one report read produced (D-343 section 3.7: typed states, never a fake zero). */
sealed interface P806ReportRead {
    /** The request this read was issued for; the landing decision matches it first. */
    val request: P806ReportRequestIdentity

    /** The read ran under a lease and produced a typed snapshot result. */
    data class Landed(
        val capture: P806ReportReadCapture,
        val result: P806SnapshotResult<P806ReportView>,
    ) : P806ReportRead {
        override val request: P806ReportRequestIdentity get() = capture.request
    }

    /** The runtime refused the lease (not Ready): nothing ran, so no generation is claimed. */
    data class Refused(
        override val request: P806ReportRequestIdentity,
    ) : P806ReportRead

    /** This graph wires no P806 report query; the lease WAS acquired, so the generation is real. */
    data class Unwired(
        override val request: P806ReportRequestIdentity,
        val generation: Generation,
    ) : P806ReportRead
}

/**
 * The landing verdict of one report read (D-343 section 3.7).
 *
 * [STALE_REQUEST] covers a read that is no longer the slot's ACTIVE request, a superseded token, a
 * different requested period and another ledger: such a landing changes nothing, so a newer request
 * keeps its own loading state. [STALE_GENERATION] and [CATALOG_ADVANCED] end ONLY the matching
 * request's loading (the last success stays visible, labelled as old context) and leave the retry to
 * the user. [PAYLOAD_REJECTED] is a matching active request whose success payload does not belong to
 * one snapshot (another ledger, or a duplicated currency axis): the payload is dropped whole and the
 * request ends as a typed invalid state.
 */
internal enum class P806ReportLandingDecision {
    ACCEPTED,
    STALE_REQUEST,
    STALE_GENERATION,
    CATALOG_ADVANCED,
    PAYLOAD_REJECTED,
}

/**
 * The lease seam the report reads run under: the production adapter
 * [LeaseScopeReportReadSeam] delegates to the owner's acquire-first path, so a graph swap between
 * "find the facade" and "acquire the lease" cannot pair an old facade with a new generation.
 */
internal interface P806ReportLeaseSeam {
    fun <T> leasedReportRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T>
}

/** The production seam: every report read runs through the owner's acquire-first order. */
internal class LeaseScopeReportReadSeam(
    private val scope: LedgerLeaseScope,
) : P806ReportLeaseSeam {
    override fun <T> leasedReportRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T> = scope.leasedReportRead(block)
}

/**
 * The host-owned request-token sequence of the report reads. It lives with the composition rather
 * than with the page state, so a refreshed overview (which seeds a fresh slot) still hands out
 * tokens NEWER than any read the previous slot issued — an old read can never match the new slot.
 * Called from the main thread only.
 */
internal class P806ReportRequestSequence {
    private var last = 0L

    /** The next monotonic token (never reused, never decreasing). */
    fun next(): Long {
        last += 1
        return last
    }
}

/**
 * Reads one report period: acquire the lease, resolve THAT graph's facade, capture the query
 * instance's catalog version and this call's read instant, and run the one snapshot read.
 *
 * A null query is the honest `Unwired`; a refused lease is `Refused`; a clock that cannot answer is
 * the typed `Unavailable` with no read at all. A read-port throw is already mapped to a typed
 * failure by the application use case, so a `Failed` here is a landed, displayed result.
 */
internal fun readP806Report(
    seam: P806ReportLeaseSeam,
    request: P806ReportRequestIdentity,
    clock: LedgerClock,
): P806ReportRead {
    val outcome =
        seam.leasedReportRead { facade, generation ->
            val query = facade.p806ReportQuery
            if (query == null) {
                P806ReportRead.Unwired(request, generation)
            } else {
                val asOf =
                    try {
                        clock.now()
                    } catch (clockFailure: Exception) {
                        null
                    }
                val result =
                    if (asOf == null) {
                        P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
                    } else {
                        query.query(request.period)
                    }
                P806ReportRead.Landed(P806ReportReadCapture(request, generation, query.catalogVersion, asOf), result)
            }
        }
    return when (outcome) {
        LeaseOutcome.NotReady -> P806ReportRead.Refused(request)
        is LeaseOutcome.Completed -> outcome.value
    }
}

/**
 * Whether one success payload belongs to the request's ledger and to ONE snapshot (D-343 section
 * 3.7: the ordered currency axis and the per-currency payloads bind the same snapshot).
 *
 * The axis must be free of duplicates: one currency listed twice would mean two payloads claiming
 * one axis slot, and the surface must not guess which one is the snapshot's. A currency that the
 * previous payload did not carry is NOT a mismatch — the new snapshot's axis is its own truth.
 */
internal fun p806ReportPayloadAccepts(
    view: P806ReportView,
    request: P806ReportRequestIdentity,
): Boolean {
    if (view.ledgerId != request.ledgerId) return false
    val axis = view.currencies.map { row -> row.currency }
    return axis.size == axis.distinct().size
}

/**
 * The host's ONE last-ready report slot (D-343 section 3.7).
 *
 * A page rebuilt by an authoritative refresh or a write round trip starts without a slot, so the
 * loaded period and its payload would vanish. This memory re-seeds only the DISPLAY fields of the
 * last landed slot (see [P503UiEvent.OpenReportRegion.previous]) — the reducer state stays the one
 * live source and this copy is never rendered: it is overwritten by every landing and answers
 * `null` whenever the captured generation or catalog is not the CURRENT one, so a graph switch, a
 * catalog advance or a new ledger can never mix two worlds. No request identity, loading marker or
 * failure travel through it.
 *
 * A DETERMINATE WRITE does not delete the payload: it turns it into HISTORY ([markWriteStale]) — the
 * same graph's last success stays a labelled-old re-seed source (the taskbook's 回程闭合: a rebuilt
 * page keeps the previous success beside the failure or the re-read, never as READY), and [record]
 * only ever stores what the reducer ACCEPTED (it reads the landed state, never a read's own payload),
 * so a late landing of the superseded request cannot restore its current-result eligibility.
 * [clear] remains the graph-switch (换库/换代) drop.
 */
internal class P806ReportSlotMemory {
    private var lastReady: P806ReportRegionState? = null

    /**
     * Whether [lastReady] is HISTORY: it was the current result once, but a determinate write (or a
     * failed read) ended that. History is re-seeded ONLY as labelled old data ([P503UiEvent.OpenReportRegion]
     * imports it write-stale), never as READY.
     */
    private var lastReadyIsHistory = false

    /**
     * Records the slot of a concluded landing — including a landing applied to a carried overview.
     * An ACCEPTED success ([p806ReportSlotIsEligible]) is remembered as the current result; a landing
     * that kept an older payload after a write fence or a typed failure is remembered as HISTORY (so a
     * rebuilt page still shows the same graph's last success as labelled old data instead of
     * forgetting it); an unwired landing drops the memory whole. A superseded request (another period
     * in flight) or a payload-less marker changes nothing here.
     */
    fun record(state: P503AppState) {
        val slot = state.carriedReportCarrier()?.overview?.reportRegion ?: return
        when {
            // The shared eligibility seam: only a concluded success is the current result.
            p806ReportSlotIsEligible(slot) -> {
                lastReady = slot
                lastReadyIsHistory = false
            }
            // A CONCLUDED landing over an older payload (a write fence or a typed failure): the
            // payload stays as the same graph's labelled history. A read still in flight is not a
            // conclusion — a superseded landing must not demote the last accepted success.
            slot.landed != null && !slot.loading -> {
                lastReady = slot.copy(loading = false, failure = null, writeStale = false)
                lastReadyIsHistory = true
            }
            slot.unwired -> {
                lastReady = null
                lastReadyIsHistory = false
            }
        }
    }

    /**
     * Marks the remembered payload as pre-write HISTORY (a determinate write invalidated it): the
     * payload itself stays a legal labelled-old re-seed source, its current-result eligibility does not.
     */
    fun markWriteStale() {
        if (lastReady != null) lastReadyIsHistory = true
    }

    /** Drops the memory whole (a graph switch or a ledger switch invalidates it). */
    fun clear() {
        lastReady = null
        lastReadyIsHistory = false
    }

    /** The slot to re-seed for [currentGeneration]; `null` when it does not exist or is another graph's. */
    fun previousFor(
        currentGeneration: Generation?,
        currentCatalogVersion: Long?,
    ): P806ReportRegionState? {
        val ready = lastReady ?: return null
        if (ready.landed == null) return null
        // The take-out guard: the remembered payload must belong to THIS graph — a graph switch, a
        // generation advance or a catalog advance never re-seeds it, as current or as history.
        val capturedGeneration = ready.landedGeneration
        if (capturedGeneration == null || currentGeneration == null || capturedGeneration != currentGeneration) return null
        if (ready.landedCatalogVersion == null || currentCatalogVersion == null || ready.landedCatalogVersion != currentCatalogVersion) return null
        return if (lastReadyIsHistory) {
            ready.copy(loading = false, failure = null, writeStale = true)
        } else {
            ready.copy(loading = false, failure = null, writeStale = false)
        }
    }
}

/**
 * The landing verdict, evaluated on the UI thread against the CURRENT generation and catalog
 * version. A landing is considered only while the slot still marks this request in flight (a
 * duplicated completion, or one that arrived after a newer request took over, changes nothing) and
 * only for the request's own ledger and period. The captures are compared as captured; the current
 * catalog version is only compared, never used to stamp a payload.
 */
internal fun p806ReportLandingDecision(
    state: P503AppState.OverviewEmpty,
    read: P806ReportRead,
    currentGeneration: Generation?,
    currentCatalogVersion: Long?,
): P806ReportLandingDecision {
    val slot = state.reportRegion ?: return P806ReportLandingDecision.STALE_REQUEST
    if (!slot.loading) return P806ReportLandingDecision.STALE_REQUEST
    val request = read.request
    // The monotonic pre-write fence: a determinate write ended every request at or below this token,
    // so such a result is ineligible even if it arrives after the invalidation and before the
    // authoritative refresh lands.
    if (request.token <= slot.fenceToken) return P806ReportLandingDecision.STALE_REQUEST
    if (request.token != slot.requestToken) return P806ReportLandingDecision.STALE_REQUEST
    if (request.ledgerId != state.state.ledgerId) return P806ReportLandingDecision.STALE_REQUEST
    if (request.period != slot.period) return P806ReportLandingDecision.STALE_REQUEST
    return when (read) {
        is P806ReportRead.Refused -> P806ReportLandingDecision.ACCEPTED
        is P806ReportRead.Unwired ->
            if (currentGeneration == null || read.generation != currentGeneration) {
                P806ReportLandingDecision.STALE_GENERATION
            } else {
                P806ReportLandingDecision.ACCEPTED
            }
        is P806ReportRead.Landed -> {
            val capture = read.capture
            val payloadAccepted =
                when (val result = read.result) {
                    is P806SnapshotResult.Success -> p806ReportPayloadAccepts(result.snapshot, request)
                    is P806SnapshotResult.Failed -> true
                }
            when {
                capture.request != request -> P806ReportLandingDecision.STALE_REQUEST
                currentGeneration == null || capture.generation != currentGeneration -> P806ReportLandingDecision.STALE_GENERATION
                currentCatalogVersion == null || capture.catalogVersion != currentCatalogVersion -> P806ReportLandingDecision.CATALOG_ADVANCED
                !payloadAccepted -> P806ReportLandingDecision.PAYLOAD_REJECTED
                else -> P806ReportLandingDecision.ACCEPTED
            }
        }
    }
}
