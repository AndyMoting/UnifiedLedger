package com.unifiedledger.ui

import com.unifiedledger.application.P806AccountCursor
import com.unifiedledger.application.P806AccountView
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.LedgerId

/*
 * P8-06 batch b (taskbook items 2/3; D-343 sections 1.3/3.2.2/3.7): the account-flow read glue.
 *
 * One account page is one P806 account snapshot read under an acquire-first lease. The capture
 * binds the request identity (token, ledger, account, cursor) to the acquire-time generation and
 * to the QUERY INSTANCE's construction catalog version — never to the catalog's current value.
 * The landing decision compares that capture with the active generation and the catalog's current
 * version (comparison only); a mismatch drops the rows/count and leaves the retry at the first
 * page. Nothing here reads persistence outside the lease.
 */

/**
 * The identity of one account-postings request (taskbook items 2/3).
 *
 * [token] is the surface's monotonic request token; [cursor] is the P806 account cursor the read
 * continues from (`null` = the first page, which is also the retry after a catalog or generation
 * change). [ledgerId] and [accountId] bind the read to the surface that asked for it.
 */
data class P806AccountRequestIdentity(
    val token: Long,
    val ledgerId: LedgerId,
    val accountId: AccountId,
    val cursor: P806AccountCursor?,
)

/**
 * One completed account read: the request, the lease generation it ran under and the catalog
 * version its query instance was constructed with (the version travels out of the query, never
 * from the catalog's current value).
 */
data class P806AccountReadCapture(
    val request: P806AccountRequestIdentity,
    val generation: Generation,
    val catalogVersion: Long,
)

/** What one account-page read produced (taskbook item 4: refused/unwired stay distinguishable). */
sealed interface P806AccountPageRead {
    /** The request this read was issued for; the landing decision matches it first. */
    val request: P806AccountRequestIdentity

    /** The read ran under a lease and produced a typed snapshot result. */
    data class Landed(
        val capture: P806AccountReadCapture,
        val result: P806SnapshotResult<P806AccountView>,
    ) : P806AccountPageRead {
        override val request: P806AccountRequestIdentity get() = capture.request
    }

    /** The runtime refused the lease (not Ready); nothing ran. */
    data class Refused(
        override val request: P806AccountRequestIdentity,
    ) : P806AccountPageRead

    /** This graph wires no P806 account query. */
    data class Unwired(
        override val request: P806AccountRequestIdentity,
    ) : P806AccountPageRead
}

/**
 * The landing verdict of one account-page read (taskbook items 2/3).
 *
 * [STALE_REQUEST] covers a superseded token, a different account/ledger and a cursor that is no
 * longer the surface's continuation point: such a landing changes nothing (a newer request keeps
 * its own loading state). [STALE_GENERATION] and [CATALOG_ADVANCED] end only the matching
 * request's loading: the loaded rows/count stay (labelled as old context) and the cursor resets so
 * the retry reads the first page of the new generation/catalog.
 */
enum class P806AccountLandingDecision {
    ACCEPTED,
    STALE_REQUEST,
    STALE_GENERATION,
    CATALOG_ADVANCED,
}

/**
 * The lease seam the account reads run under (taskbook item 2): the production adapter
 * [LeaseScopeAccountReadSeam] delegates to the owner's acquire-first path, so a graph swap between
 * "find the facade" and "acquire the lease" cannot pair an old facade with a new generation.
 */
interface P806AccountLeaseSeam {
    fun <T> leasedAccountRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T>
}

/** The production seam: every account page read runs through the owner's acquire-first order. */
internal class LeaseScopeAccountReadSeam(
    private val scope: LedgerLeaseScope,
) : P806AccountLeaseSeam {
    override fun <T> leasedAccountRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T> = scope.leasedAccountRead(block)
}

/**
 * P8-06 batch b (taskbook item 3): the host-owned request-token sequence of the account reads. It
 * lives with the composition rather than with the surface state, so closing and reopening the same
 * account keeps handing out NEWER tokens — a read still in flight from the previous open can never
 * match the reopened surface. Called from the main thread only.
 */
internal class P806AccountRequestSequence {
    private var last = 0L

    /** The next monotonic token (never reused, never decreasing). */
    fun next(): Long {
        last += 1
        return last
    }
}

/**
 * Reads one account page: acquire the lease, resolve THAT graph's facade, capture the query
 * instance's catalog version and run the snapshot read — one snapshot per call (page + count come
 * from the same read). A refused lease and an unwired query stay distinguishable from a typed
 * read failure, so the surface never presents them as an empty account.
 */
fun readP806AccountPage(
    seam: P806AccountLeaseSeam,
    request: P806AccountRequestIdentity,
): P806AccountPageRead {
    val outcome =
        seam.leasedAccountRead { facade, generation ->
            facade.p806AccountPostingsQuery?.let { query ->
                P806AccountReadCapture(request, generation, query.catalogVersion) to query.query(request.accountId, cursor = request.cursor)
            }
        }
    return when (outcome) {
        LeaseOutcome.NotReady -> P806AccountPageRead.Refused(request)
        is LeaseOutcome.Completed -> {
            val captured = outcome.value
            if (captured == null) {
                P806AccountPageRead.Unwired(request)
            } else {
                P806AccountPageRead.Landed(captured.first, captured.second)
            }
        }
    }
}

/**
 * The landing verdict, evaluated on the UI thread against the CURRENT generation and catalog
 * version. The capture's generation and catalog version are compared as captured; the current
 * catalog version is only compared, never used to stamp a payload. A success payload's ledger and
 * account must match the request's (taskbook item 3).
 */
fun p806AccountLandingDecision(
    state: P503AppState.AccountFlow,
    read: P806AccountPageRead,
    currentGeneration: Generation?,
    currentCatalogVersion: Long?,
): P806AccountLandingDecision {
    val request = read.request
    if (request.token != state.pageRequestToken) return P806AccountLandingDecision.STALE_REQUEST
    if (request.accountId != state.accountId) return P806AccountLandingDecision.STALE_REQUEST
    if (request.ledgerId != state.overview.state.ledgerId) return P806AccountLandingDecision.STALE_REQUEST
    if (request.cursor != state.nextCursor) return P806AccountLandingDecision.STALE_REQUEST
    if (read !is P806AccountPageRead.Landed) return P806AccountLandingDecision.ACCEPTED
    val capture = read.capture
    if (capture.request != request) return P806AccountLandingDecision.STALE_REQUEST
    if (currentGeneration == null || capture.generation != currentGeneration) return P806AccountLandingDecision.STALE_GENERATION
    if (currentCatalogVersion == null || capture.catalogVersion != currentCatalogVersion) return P806AccountLandingDecision.CATALOG_ADVANCED
    return when (val result = read.result) {
        is P806SnapshotResult.Success ->
            if (result.snapshot.ledgerId != request.ledgerId || result.snapshot.accountId != request.accountId) {
                P806AccountLandingDecision.STALE_REQUEST
            } else {
                P806AccountLandingDecision.ACCEPTED
            }
        is P806SnapshotResult.Failed -> P806AccountLandingDecision.ACCEPTED
    }
}
