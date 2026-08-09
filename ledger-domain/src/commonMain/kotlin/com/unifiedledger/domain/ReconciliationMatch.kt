package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * D-085 RG-12 `reconciliation_match` status values of the frozen direct-v2 contract. Only
 * `matched` and `invalidated` exist; the contract never re-matches a posting through an
 * existing match — rematching of a replacement posting is expressed by a fresh match entity
 * for the new posting (design doc, line 26).
 */
enum class ReconciliationMatchStatus {
    MATCHED,
    INVALIDATED,
}

/**
 * D-085 RG-12 frozen `status_history` reason values of `reconciliation_match` payloads
 * (`golden/rules/rg-12.json`): `exact_evidence` for the initial match and `posting_replaced`
 * for an invalidation caused by a `correct_transaction_version` `posting_facts` correction.
 */
enum class ReconciliationMatchReason(val jsonName: String) {
    EXACT_EVIDENCE("exact_evidence"),
    POSTING_REPLACED("posting_replaced"),
}

/** One append-only `status_history` entry of a reconciliation match. */
data class ReconciliationMatchStatusEntry(
    val id: String,
    val sequence: Int,
    val status: ReconciliationMatchStatus,
    val at: Instant,
    val reason: ReconciliationMatchReason,
)

/**
 * D-085 RG-12 `reconciliation_match` domain entity: `posting_id` + `evidence_id` plus the
 * append-only `status_history`. Invalidation never deletes the match, its evidence or its
 * history; the old version, evidence and postings are all preserved (design doc, line 26).
 */
data class ReconciliationMatch(
    val id: String,
    val postingId: PostingId,
    val evidenceId: String,
    val statusHistory: List<ReconciliationMatchStatusEntry>,
) {
    /** Current derived status: the status of the last history entry. */
    val currentStatus: ReconciliationMatchStatus
        get() = statusHistory.last().status
}

/**
 * Constructs a reconciliation match. Invariants (D-085 RG-12, frozen rg-12.json):
 * - identities are non-blank;
 * - the history is non-empty with consecutive sequences starting at 1;
 * - the first entry is `matched` with reason `exact_evidence`;
 * - a `matched` entry may only be followed by `invalidated` entries with reason
 *   `posting_replaced` (the only transition); an `invalidated` entry is terminal.
 */
fun createReconciliationMatch(
    id: String,
    postingId: PostingId,
    evidenceId: String,
    statusHistory: List<ReconciliationMatchStatusEntry>,
): DomainResult<ReconciliationMatch> {
    if (id.isBlank() || postingId.value.isBlank() || evidenceId.isBlank()) {
        return DomainResult.Failure(ReconciliationMatchViolation.IdentityRequired)
    }
    if (statusHistory.isEmpty()) {
        return DomainResult.Failure(ReconciliationMatchViolation.HistoryRequired)
    }
    if (statusHistory.map { it.sequence } != (1..statusHistory.size).toList()) {
        return DomainResult.Failure(ReconciliationMatchViolation.InvalidHistorySequence)
    }
    statusHistory.forEachIndexed { index, entry ->
        if (entry.id.isBlank()) {
            return DomainResult.Failure(ReconciliationMatchViolation.IdentityRequired)
        }
        if (index == 0) {
            if (
                entry.status != ReconciliationMatchStatus.MATCHED ||
                entry.reason != ReconciliationMatchReason.EXACT_EVIDENCE
            ) {
                return DomainResult.Failure(ReconciliationMatchViolation.InvalidInitialStatus)
            }
        } else {
            val previous = statusHistory[index - 1]
            if (
                previous.status != ReconciliationMatchStatus.MATCHED ||
                entry.status != ReconciliationMatchStatus.INVALIDATED ||
                entry.reason != ReconciliationMatchReason.POSTING_REPLACED
            ) {
                return DomainResult.Failure(ReconciliationMatchViolation.InvalidStatusTransition)
            }
        }
    }
    return DomainResult.Success(ReconciliationMatch(id, postingId, evidenceId, statusHistory))
}

/**
 * Appends exactly one `invalidated` entry (reason `posting_replaced`) to [match] at [at],
 * continuing the sequence by one. Only a currently `matched` match can be invalidated; an
 * already invalidated match stays terminal (design doc: invalidation is append-only).
 */
fun invalidateReconciliationMatch(
    match: ReconciliationMatch,
    entryId: String,
    at: Instant,
): DomainResult<ReconciliationMatch> {
    if (match.currentStatus != ReconciliationMatchStatus.MATCHED) {
        return DomainResult.Failure(ReconciliationMatchViolation.InvalidStatusTransition)
    }
    return createReconciliationMatch(
        id = match.id,
        postingId = match.postingId,
        evidenceId = match.evidenceId,
        statusHistory = match.statusHistory + ReconciliationMatchStatusEntry(
            id = entryId,
            sequence = match.statusHistory.size + 1,
            status = ReconciliationMatchStatus.INVALIDATED,
            at = at,
            reason = ReconciliationMatchReason.POSTING_REPLACED,
        ),
    )
}

/** D-085 RG-12 entity-internal reconciliation match violations; none is a frozen rejection reason. */
sealed interface ReconciliationMatchViolation : DomainViolation {
    data object IdentityRequired : ReconciliationMatchViolation

    data object HistoryRequired : ReconciliationMatchViolation

    data object InvalidHistorySequence : ReconciliationMatchViolation

    data object InvalidInitialStatus : ReconciliationMatchViolation

    data object InvalidStatusTransition : ReconciliationMatchViolation
}
