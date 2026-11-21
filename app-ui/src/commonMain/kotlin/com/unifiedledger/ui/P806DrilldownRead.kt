package com.unifiedledger.ui

import com.unifiedledger.application.P806DrilldownView
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806TransactionCursor
import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/*
 * P8-06 batch 3 (D-362 1/2; spec sections 3.7/3.9/3.10.4): the report drilldown's read glue.
 *
 * One drilldown page is one P806 drilldown snapshot read (one page plus the exact count under the
 * same predicate) under an acquire-first lease. The capture binds the request identity (token,
 * ledger, typed target, cursor) to the acquire-time generation, to the QUERY INSTANCE's construction
 * catalog version — never to the catalog's current value — and to the read instant the header may
 * label. The landing decision compares that capture with the active generation and the catalog's
 * current version (comparison only) and requires the request to still be the surface's ACTIVE
 * request. A continuation first checks its page-set provenance under the SAME lease, so an old
 * cursor is never sent into another catalog/generation world. Nothing here reads persistence
 * outside the lease.
 */

/**
 * The identity of one drilldown-page request (D-343 section 3.7 / D-362 1: request identity).
 *
 * [token] is the host's monotonic request token; [target] is the typed condition (its window always,
 * its optional category subtree) and a target change must mint a new token. [cursor] is the P806
 * detail cursor the read continues from (`null` = the first page, which is also the retry after a
 * catalog or generation change). [pageSetGeneration] and [pageSetCatalogVersion] are the provenance
 * of the page set a continuation appends to (`null` for a first-page request): a continuation may
 * only run, and only land, while the held graph and the query instance carry exactly that
 * provenance.
 */
data class P806DrilldownRequestIdentity(
    val token: Long,
    val ledgerId: LedgerId,
    val target: ReportDrilldownTarget,
    val cursor: P806TransactionCursor?,
    val pageSetGeneration: Generation?,
    val pageSetCatalogVersion: Long?,
)

/**
 * One completed drilldown read: the lease generation it ran under, the catalog version its query
 * instance was constructed with (the version travels out of the query, never from the catalog's
 * current value) and the application read instant ([asOf]). [asOf] may be `null` when the clock is
 * unusable: this query has no clock dependency, so the read still runs and the header simply carries
 * no read time (unlike the report read, whose daily-average/future threshold makes the clock
 * required).
 */
data class P806DrilldownReadCapture(
    val generation: Generation,
    val catalogVersion: Long,
    val asOf: Instant?,
)

/** What one drilldown-page read produced (D-343 section 3.7: typed states, never a fake zero). */
sealed interface P806DrilldownPageRead {
    /** The request this read was issued for; the landing decision matches it first. */
    val request: P806DrilldownRequestIdentity

    /** The read ran under a lease and produced a typed snapshot result. */
    data class Landed(
        override val request: P806DrilldownRequestIdentity,
        val capture: P806DrilldownReadCapture,
        val result: P806SnapshotResult<P806DrilldownView>,
    ) : P806DrilldownPageRead

    /**
     * The continuation's page-set provenance no longer matches the held graph / query instance, so
     * the old cursor was never sent to the read port. The surface keeps its old rows and the host
     * re-reads the first page (which replaces the set whole).
     */
    data class StaleProvenance(
        override val request: P806DrilldownRequestIdentity,
    ) : P806DrilldownPageRead

    /** The runtime refused the lease (not Ready); nothing ran, so no generation is claimed. */
    data class Refused(
        override val request: P806DrilldownRequestIdentity,
    ) : P806DrilldownPageRead

    /** This graph wires no P806 drilldown query; the lease WAS acquired, so the generation is real. */
    data class Unwired(
        override val request: P806DrilldownRequestIdentity,
    ) : P806DrilldownPageRead
}

/**
 * The lease seam the drilldown reads run under: the production adapter
 * [LeaseScopeDrilldownSeam] delegates to the owner's acquire-first path, so a graph swap between
 * "find the facade" and "acquire the lease" cannot pair an old facade with a new generation.
 */
fun interface P806DrilldownLeaseSeam {
    fun read(request: P806DrilldownRequestIdentity): P806DrilldownPageRead
}

/** The production seam: every drilldown page read runs through the owner's acquire-first order. */
internal class LeaseScopeDrilldownSeam(
    private val scope: LedgerLeaseScope,
) : P806DrilldownLeaseSeam {
    override fun read(request: P806DrilldownRequestIdentity): P806DrilldownPageRead =
        when (val outcome = scope.leasedReportRead { facade, generation -> readP806DrilldownPage(facade, generation, request) }) {
            LeaseOutcome.NotReady -> P806DrilldownPageRead.Refused(request)
            is LeaseOutcome.Completed -> outcome.value
        }
}

/**
 * The host-owned request-token sequence of the drilldown reads. It lives with the composition rather
 * than with the surface state, so closing and reopening (even the same target) hands out NEWER
 * tokens — a read still in flight from the previous open can never match the reopened surface.
 * Called from the main thread only.
 */
internal class P806DrilldownRequestSequence {
    private var last = 0L

    /** The next monotonic token (never reused, never decreasing). */
    fun next(): Long {
        last += 1
        return last
    }
}

/**
 * Reads one drilldown page: resolve THAT graph's facade and run the snapshot read. A continuation
 * (a non-null cursor) first checks its page-set provenance under the SAME lease — the held
 * generation and the query instance's catalog version must equal the ones the loaded page set was
 * read under; on a mismatch the old cursor is NEVER sent to the read port ([StaleProvenance]), so
 * pages from two catalog/generation worlds are never spliced. A null query is the honest
 * [Unwired]; an unusable clock only drops the as-of label, never the read.
 *
 * Kept separate from [LeaseScopeDrilldownSeam] so the production logic is testable against the REAL
 * query without constructing a runtime owner.
 */
internal fun readP806DrilldownPage(
    facade: P503LedgerFacade,
    generation: Generation,
    request: P806DrilldownRequestIdentity,
): P806DrilldownPageRead {
    val query = facade.p806DrilldownQuery ?: return P806DrilldownPageRead.Unwired(request)
    if (request.cursor != null) {
        if (request.pageSetGeneration != generation) return P806DrilldownPageRead.StaleProvenance(request)
        if (request.pageSetCatalogVersion != query.catalogVersion) return P806DrilldownPageRead.StaleProvenance(request)
    }
    val asOf =
        try {
            facade.ledgerClock.now()
        } catch (clockFailure: Exception) {
            null
        }
    val categoryId =
        when (val target = request.target) {
            is ReportDrilldownTarget.Period -> null
            is ReportDrilldownTarget.Category -> target.categoryId
        }
    val result = query.query(request.target.window, categoryId = categoryId, cursor = request.cursor)
    return P806DrilldownPageRead.Landed(
        request = request,
        capture = P806DrilldownReadCapture(generation = generation, catalogVersion = query.catalogVersion, asOf = asOf),
        result = result,
    )
}

/**
 * The landing verdict of one drilldown-page read (D-362 1; the frozen nine-value roster).
 *
 * Every read variant first passes the ACTIVE-request gate: a superseded token ([STALE_TOKEN]) or a
 * different target ([STALE_TARGET]) drops the landing whole and triggers no re-read. For a landed
 * read the continuation point ([STALE_CURSOR]), the ledger ([STALE_LEDGER]), the current generation
 * ([STALE_GENERATION]), the current catalog version ([CATALOG_ADVANCED]) and the payload's own
 * ledger/window identity AND count-vs-page consistency — a `totalCount == 0` success must carry no
 * rows — ([PAYLOAD_REJECTED]) are checked in that order; only a fully matching read is
 * [ACCEPTED]. [STALE_PROVENANCE] is the continuation read whose page set belongs to another world:
 * the reducer keeps the old rows and the host re-reads the first page. [Refused]/[Unwired] pass the
 * gate ([ACCEPTED]) so the reducer can land their distinguishable faces. The current catalog version
 * is only compared, never used to stamp a payload.
 */
internal enum class P806DrilldownLandingDecision {
    ACCEPTED,
    STALE_TOKEN,
    STALE_TARGET,
    STALE_CURSOR,
    STALE_LEDGER,
    STALE_GENERATION,
    CATALOG_ADVANCED,
    PAYLOAD_REJECTED,
    STALE_PROVENANCE,
}

/** The landing verdict, evaluated on the UI thread against the CURRENT generation and catalog version. */
internal fun p806DrilldownLandingDecision(
    state: P503AppState.ReportDrilldown,
    read: P806DrilldownPageRead,
    currentGeneration: Generation?,
    currentCatalogVersion: Long?,
): P806DrilldownLandingDecision {
    val request = read.request
    if (request.token != state.pageRequestToken) return P806DrilldownLandingDecision.STALE_TOKEN
    if (request.target != state.target) return P806DrilldownLandingDecision.STALE_TARGET
    return when (read) {
        is P806DrilldownPageRead.Refused -> P806DrilldownLandingDecision.ACCEPTED
        is P806DrilldownPageRead.Unwired -> P806DrilldownLandingDecision.ACCEPTED
        is P806DrilldownPageRead.StaleProvenance -> P806DrilldownLandingDecision.STALE_PROVENANCE
        is P806DrilldownPageRead.Landed -> {
            if (request.cursor != state.nextCursor) return P806DrilldownLandingDecision.STALE_CURSOR
            if (request.ledgerId != state.overview.state.ledgerId) return P806DrilldownLandingDecision.STALE_LEDGER
            val capture = read.capture
            when {
                currentGeneration == null || capture.generation != currentGeneration ->
                    P806DrilldownLandingDecision.STALE_GENERATION
                currentCatalogVersion == null || capture.catalogVersion != currentCatalogVersion ->
                    P806DrilldownLandingDecision.CATALOG_ADVANCED
                else ->
                    when (val result = read.result) {
                        is P806SnapshotResult.Success -> {
                            val view = result.snapshot
                            val malformedCountWithRows = view.totalCount == 0 && view.page.rows.isNotEmpty()
                            if (view.ledgerId != request.ledgerId || view.window != request.target.window || malformedCountWithRows) {
                                P806DrilldownLandingDecision.PAYLOAD_REJECTED
                            } else {
                                P806DrilldownLandingDecision.ACCEPTED
                            }
                        }
                        is P806SnapshotResult.Failed -> P806DrilldownLandingDecision.ACCEPTED
                    }
            }
        }
    }
}
