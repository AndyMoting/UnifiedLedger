package com.unifiedledger.ui

import com.unifiedledger.application.AccountCurrencyBalance
import com.unifiedledger.application.ManageableAccountView
import com.unifiedledger.application.P806AccountCursor
import com.unifiedledger.application.P806AccountView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId

/*
 * P8-06 batch b (D-343 sections 1.3/3.2.2/3.7): the account-flow read glue.
 *
 * One account page is one P806 account snapshot read under an acquire-first lease. The capture
 * binds the request identity (token, ledger, account, cursor) to the acquire-time generation and
 * to the QUERY INSTANCE's construction catalog version — never to the catalog's current value.
 * The landing decision compares that capture with the active generation and the catalog's current
 * version (comparison only) and requires the request to still be the surface's ACTIVE request.
 * Nothing here reads persistence outside the lease.
 */

/**
 * The identity of one account-postings request (D-343 section 3.7: request identity).
 *
 * [token] is the host's monotonic request token; [cursor] is the P806 account cursor the read
 * continues from (`null` = the first page, which is also the retry after a catalog or generation
 * change). [pageSetGeneration] and [pageSetCatalogVersion] are the provenance of the page set a
 * continuation appends to (`null` for a first-page request): a continuation may only run, and may
 * only land, while the held graph and the query instance carry exactly that provenance.
 */
data class P806AccountRequestIdentity(
    val token: Long,
    val ledgerId: LedgerId,
    val accountId: AccountId,
    val cursor: P806AccountCursor?,
    val pageSetGeneration: Generation? = null,
    val pageSetCatalogVersion: Long? = null,
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

/** What one account-page read produced (D-343 section 3.7: typed states, never a fake zero). */
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

    /** The runtime refused the lease (not Ready); nothing ran, so no generation is claimed. */
    data class Refused(
        override val request: P806AccountRequestIdentity,
    ) : P806AccountPageRead

    /** This graph wires no P806 account query; the lease WAS acquired, so the generation is real. */
    data class Unwired(
        override val request: P806AccountRequestIdentity,
        val generation: Generation,
    ) : P806AccountPageRead

    /**
     * The continuation's page-set provenance no longer matches the held graph / query instance, so
     * the old cursor was never sent to the read port. [failure] is the typed reason the surface
     * reports while it retries from the first page (which replaces the page set whole).
     */
    data class StaleProvenance(
        override val request: P806AccountRequestIdentity,
        val failure: P806SnapshotFailure,
    ) : P806AccountPageRead
}

/**
 * The landing verdict of one account-page read (D-343 section 3.7).
 *
 * [STALE_REQUEST] covers a read that is no longer the surface's ACTIVE request, a superseded
 * token, a different account/ledger and a cursor that is no longer the continuation point: such a
 * landing changes nothing (a newer request keeps its own loading state, and a read-only return
 * never re-admits an old completion). [STALE_GENERATION] and [CATALOG_ADVANCED] end only the
 * matching request's loading: the loaded rows/count stay (labelled as old context) and the cursor
 * resets so the retry reads the first page of the new generation/catalog. [PROVENANCE_ADVANCED]
 * is the same reset for a continuation page set read under another catalog/generation.
 * [PAYLOAD_REJECTED] is a matching active request whose success payload carries another ledger or
 * account: the payload is dropped and only that request ends, as a typed failure.
 */
internal enum class P806AccountLandingDecision {
    ACCEPTED,
    STALE_REQUEST,
    STALE_GENERATION,
    CATALOG_ADVANCED,
    PROVENANCE_ADVANCED,
    PAYLOAD_REJECTED,
}

/**
 * The lease seam the account reads run under: the production adapter
 * [LeaseScopeAccountReadSeam] delegates to the owner's acquire-first path, so a graph swap between
 * "find the facade" and "acquire the lease" cannot pair an old facade with a new generation.
 */
internal interface P806AccountLeaseSeam {
    fun <T> leasedAccountRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T>
}

/** The production seam: every account page read runs through the owner's acquire-first order. */
internal class LeaseScopeAccountReadSeam(
    private val scope: LedgerLeaseScope,
) : P806AccountLeaseSeam {
    override fun <T> leasedAccountRead(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T> = scope.leasedAccountRead(block)
}

/**
 * The host-owned request-token sequence of the account reads. It lives with the composition rather
 * than with the surface state, so closing and reopening the same account keeps handing out NEWER
 * tokens — a read still in flight from the previous open can never match the reopened surface.
 * Called from the main thread only.
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
 * The host-projected account-flow header: the asset row's exact display balance and its
 * 「名称 · 类型 · 币种」 line.
 */
internal data class P806AccountFlowHeader(
    val balanceLine: String,
    val displayMinorUnits: Long,
    val currency: CurrencyUnit,
)

/**
 * The account-flow header by the SAME projection the asset row already renders
 * ([assetResolvedCurrency], [assetAccountGroups]): the balance row wins, and a never-posted
 * account falls back to its declared catalog currency with a 0 display amount — the zero the asset
 * row itself shows, never a computed balance. `null` only when the account is absent from the
 * manageable catalog projection (there is no asset row, so there is no entry to open).
 */
internal fun p806AccountFlowHeader(
    accountId: AccountId,
    accountNames: Map<AccountId, String>,
    accounts: List<ManageableAccountView>,
    balances: List<AccountCurrencyBalance>,
): P806AccountFlowHeader? {
    val account = accounts.firstOrNull { candidate -> candidate.accountId == accountId } ?: return null
    val balance = balances.firstOrNull { candidate -> candidate.accountId == accountId }
    val currency = balance?.currency ?: account.currency
    val name = accountNames[accountId] ?: accountId.value
    val balanceLine = listOfNotNull(name, accountKindText(account.kind), currency.code).joinToString(" · ")
    return P806AccountFlowHeader(balanceLine, balance?.displayMinorUnits ?: 0L, currency)
}

/**
 * Reads one account page: acquire the lease, resolve THAT graph's facade, capture the query
 * instance's catalog version and run the snapshot read — one snapshot per call (page + count come
 * from the same read).
 *
 * A continuation (a non-null cursor) first checks the page-set provenance under the SAME lease:
 * the held generation and the query instance's catalog version must equal the ones the loaded page
 * set was read under. On a mismatch the old cursor is NEVER sent to the read port (`StaleProvenance`),
 * so pages from two catalog/generation worlds are never spliced. A refused lease, an unwired query
 * and a typed read failure all stay distinguishable.
 */
internal fun readP806AccountPage(
    seam: P806AccountLeaseSeam,
    request: P806AccountRequestIdentity,
): P806AccountPageRead {
    val outcome =
        seam.leasedAccountRead { facade, generation ->
            val query = facade.p806AccountPostingsQuery
            when {
                query == null -> P806AccountPageRead.Unwired(request, generation)
                request.cursor == null -> P806AccountPageRead.Landed(P806AccountReadCapture(request, generation, query.catalogVersion), query.query(request.accountId, cursor = null))
                else -> {
                    val provenanceFailure =
                        when {
                            request.pageSetGeneration != generation -> P806SnapshotFailure.Unavailable
                            request.pageSetCatalogVersion != query.catalogVersion -> P806SnapshotFailure.CatalogVersionMismatch
                            else -> null
                        }
                    if (provenanceFailure == null) {
                        P806AccountPageRead.Landed(P806AccountReadCapture(request, generation, query.catalogVersion), query.query(request.accountId, cursor = request.cursor))
                    } else {
                        P806AccountPageRead.StaleProvenance(request, provenanceFailure)
                    }
                }
            }
        }
    return when (outcome) {
        LeaseOutcome.NotReady -> P806AccountPageRead.Refused(request)
        is LeaseOutcome.Completed -> outcome.value
    }
}

/**
 * The landing verdict, evaluated on the UI thread against the CURRENT generation and catalog
 * version. A landing is considered only while its request is still the surface's ACTIVE request
 * (an old completion after a read-only return changes nothing). The capture's generation and
 * catalog version are compared as captured; the current catalog version is only compared, never
 * used to stamp a payload. A success payload must also name the request's ledger and account.
 */
internal fun p806AccountLandingDecision(
    state: P503AppState.AccountFlow,
    read: P806AccountPageRead,
    currentGeneration: Generation?,
    currentCatalogVersion: Long?,
): P806AccountLandingDecision {
    // Only the surface's ACTIVE request may land: a duplicate completion, a completion that
    // arrived after the user returned from a read-only detour, or a request superseded by a newer
    // one never writes (D-343 section 3.7: 只结束匹配请求，非活跃一律不改状态).
    if (!state.rowsLoading) return P806AccountLandingDecision.STALE_REQUEST
    val request = read.request
    if (request.token != state.pageRequestToken) return P806AccountLandingDecision.STALE_REQUEST
    if (request.accountId != state.accountId) return P806AccountLandingDecision.STALE_REQUEST
    if (request.ledgerId != state.overview.state.ledgerId) return P806AccountLandingDecision.STALE_REQUEST
    if (request.cursor != state.nextCursor) return P806AccountLandingDecision.STALE_REQUEST
    return when (read) {
        is P806AccountPageRead.Refused -> P806AccountLandingDecision.ACCEPTED
        is P806AccountPageRead.Unwired ->
            if (currentGeneration == null || read.generation != currentGeneration) {
                P806AccountLandingDecision.STALE_GENERATION
            } else {
                P806AccountLandingDecision.ACCEPTED
            }
        is P806AccountPageRead.StaleProvenance -> P806AccountLandingDecision.PROVENANCE_ADVANCED
        is P806AccountPageRead.Landed -> {
            val capture = read.capture
            if (capture.request != request) {
                P806AccountLandingDecision.STALE_REQUEST
            } else if (currentGeneration == null || capture.generation != currentGeneration) {
                P806AccountLandingDecision.STALE_GENERATION
            } else if (currentCatalogVersion == null || capture.catalogVersion != currentCatalogVersion) {
                P806AccountLandingDecision.CATALOG_ADVANCED
            } else if (request.cursor != null && (capture.generation != state.loadedGeneration || capture.catalogVersion != state.loadedCatalogVersion)) {
                // A continuation may only append to a page set read under the same provenance.
                P806AccountLandingDecision.PROVENANCE_ADVANCED
            } else {
                when (val result = read.result) {
                    is P806SnapshotResult.Success ->
                        if (result.snapshot.ledgerId != request.ledgerId || result.snapshot.accountId != request.accountId) {
                            P806AccountLandingDecision.PAYLOAD_REJECTED
                        } else {
                            P806AccountLandingDecision.ACCEPTED
                        }
                    is P806SnapshotResult.Failed -> P806AccountLandingDecision.ACCEPTED
                }
            }
        }
    }
}
