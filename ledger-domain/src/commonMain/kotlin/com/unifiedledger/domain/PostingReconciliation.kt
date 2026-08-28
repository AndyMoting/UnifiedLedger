package com.unifiedledger.domain

/**
 * D-085 RG-12 `posting_reconciliations` status of a single posting: `matched` or `pending`
 * (frozen rg-12.json). Facts are append-only per posting: a correction never changes the
 * reconciliation fact of an old posting; the replacement posting receives a fresh fact
 * (`matched` when preserved, `pending` when invalidated) and non-real postings carry no fact.
 */
enum class PostingReconciliationStatus(
    val jsonName: String,
) {
    MATCHED("matched"),
    PENDING("pending"),
}

/**
 * D-085 RG-12 derived `reconciliation_summary` of a transaction
 * (`derived_statuses` in rg-12.json): `matched`, `pending` or `partial`.
 */
enum class ReconciliationSummary(
    val jsonName: String,
) {
    MATCHED("matched"),
    PENDING("pending"),
    PARTIAL("partial"),
}

/** D-085 RG-12 `posting_reconciliation` fact: one posting to its current status. */
data class PostingReconciliation(
    val id: String,
    val postingId: PostingId,
    val status: PostingReconciliationStatus,
)

/**
 * Constructs a posting reconciliation fact. Identities must be non-blank; the status is one of
 * the two frozen values. State-level coverage (every eligible posting has exactly one fact)
 * is enforced by the application layer over the full posting set.
 */
fun createPostingReconciliation(
    id: String,
    postingId: PostingId,
    status: PostingReconciliationStatus,
): DomainResult<PostingReconciliation> {
    if (id.isBlank() || postingId.value.isBlank()) {
        return DomainResult.Failure(PostingReconciliationViolation.IdentityRequired)
    }
    return DomainResult.Success(PostingReconciliation(id, postingId, status))
}

/**
 * Derived `reconciliation_summary` over the eligible postings of a transaction, mirroring
 * `_transaction_reconciliation_status` of the golden validator: `matched` when every posting
 * is matched, `pending` when every posting is pending, `partial` otherwise. An empty list is
 * vacuously `matched` (Python `all()` semantics); callers only pass non-empty eligible sets.
 */
fun deriveReconciliationSummary(statuses: List<PostingReconciliationStatus>): ReconciliationSummary =
    when {
        statuses.all { it == PostingReconciliationStatus.MATCHED } -> ReconciliationSummary.MATCHED
        statuses.all { it == PostingReconciliationStatus.PENDING } -> ReconciliationSummary.PENDING
        else -> ReconciliationSummary.PARTIAL
    }

/**
 * Expected reconciliation fact of a replacement posting for [effect] (accepted-path lineage
 * rules of the golden validator, v2.py `correct_transaction_version`): `matched` when the
 * predecessor match is preserved, `pending` when the old match is invalidated and rematching
 * is open, `null` for non-real postings which carry no reconciliation fact at all.
 */
fun replacementPostingReconciliationStatus(effect: ReconciliationEffect): PostingReconciliationStatus? =
    when (effect) {
        ReconciliationEffect.PRESERVED -> PostingReconciliationStatus.MATCHED
        ReconciliationEffect.INVALIDATED -> PostingReconciliationStatus.PENDING
        ReconciliationEffect.NOT_APPLICABLE -> null
    }

/** D-085 RG-12 entity-internal posting reconciliation violations; none is a frozen rejection reason. */
sealed interface PostingReconciliationViolation : DomainViolation {
    data object IdentityRequired : PostingReconciliationViolation
}
