package com.unifiedledger.application

import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

/**
 * One selectable original transaction of the manual refund entry (spec section 2.3). The user never
 * picks a category: [categoryId] and [categoryName] are the secondary expense category the server
 * inherits and re-resolves on submit (spec section 2.4), and [categoryName] is the catalog's CURRENT
 * display name for it.
 *
 * [structuralCap] is the EXPENSE LEG amount of the original's current version — the spec's cap base,
 * never the "original transaction amount". The already-refunded sum is [structuralCap] minus
 * [remaining] (spec section 2.5: the cap minus the effective refunds of that original).
 */
data class ManualRefundCandidate(
    val transactionId: TransactionId,
    val occurredAt: Instant,
    val categoryId: CategoryId,
    val categoryName: String,
    val structuralCap: Money,
    val remaining: Money,
)

/**
 * One bounded page of the candidate list (spec sections 2.3/5, V-15). [Loaded.hasMore] reports that
 * at least one further candidate exists beyond this page, so a full page is never read as the last
 * one.
 *
 * [Unavailable] is the typed read failure: a failed read is never rendered as an empty page (V-13).
 * A genuinely empty candidate set is [Loaded] with no candidates and `hasMore = false`, and those two
 * states are never interchangeable.
 */
sealed interface ManualRefundCandidatesResult {
    data class Loaded(
        val candidates: List<ManualRefundCandidate>,
        val hasMore: Boolean,
    ) : ManualRefundCandidatesResult

    data object Unavailable : ManualRefundCandidatesResult
}

/**
 * The bounded candidate read port (spec sections 2.3/4). An implementation MUST read the whole page —
 * including the one-row lookahead that decides [ManualRefundCandidatesResult.Loaded.hasMore] — inside
 * ONE read transaction, so the page and its lookahead come from one snapshot, and MUST map every
 * failure to [ManualRefundCandidatesResult.Unavailable]. [pageSize] bounds the page and [pageOffset]
 * is its row offset into the frozen `occurred_at` DESC, transaction id order, so the list is read in
 * bounded pages and never as one unbounded result (V-15).
 */
interface ManualRefundCandidatesReadPort {
    fun readPage(
        ledgerId: LedgerId,
        pageSize: Int,
        pageOffset: Int,
    ): ManualRefundCandidatesResult
}
