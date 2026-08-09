package com.unifiedledger.domain

/**
 * D-085 RG-12 closed `reconciliation_effect` of a `posting_replacement` audit link (frozen
 * rg-12.json): `preserved` (facts unchanged, reconciliation match inherited), `invalidated`
 * (reconciliation-relevant real facts changed, predecessor match history invalidated) or
 * `not_applicable` (non-real posting such as the expense leg).
 */
enum class ReconciliationEffect(val jsonName: String) {
    PRESERVED("preserved"),
    INVALIDATED("invalidated"),
    NOT_APPLICABLE("not_applicable"),
}

/**
 * D-085 RG-12 `posting_replacement` audit link: from an old posting to its replacement
 * posting, carrying the closed [ReconciliationEffect]. The audit link is a neutral fact; old
 * evidence, old version and old postings are never touched (design doc, lines 9-11).
 */
data class PostingReplacement(
    val id: String,
    val fromPostingId: PostingId,
    val toPostingId: PostingId,
    val reconciliationEffect: ReconciliationEffect,
)

/**
 * Derives the expected closed effect of a replacement: `not_applicable` for non-real
 * postings, `preserved` when the real posting facts are unchanged, `invalidated` when they
 * changed (golden audit rule: invalidated requires a changed reconciliation-relevant real
 * posting). Asset and liability legs are fully symmetric (design doc, line 22).
 */
fun derivePostingReplacementEffect(
    fromFacts: PostingFacts,
    toFacts: PostingFacts,
    fromAccount: Account,
): ReconciliationEffect {
    val isReal = fromAccount.ownedByUser && fromAccount.realAccount
    return when {
        !isReal -> ReconciliationEffect.NOT_APPLICABLE
        fromFacts.sameAs(toFacts) -> ReconciliationEffect.PRESERVED
        else -> ReconciliationEffect.INVALIDATED
    }
}

/**
 * Constructs a posting replacement audit link, validating the golden audit rules of
 * `tools/python/golden_cases/v2.py` (`posting_replacement` state validation):
 * - the endpoints are consecutive versions of one transaction;
 * - `not_applicable` is limited to non-real postings;
 * - `preserved` requires unchanged posting facts and an active predecessor match with an
 *   inherited successor match for the same evidence;
 * - `invalidated` requires a changed reconciliation-relevant real posting.
 *
 * [activeMatchesByPosting] must contain only active matches (current status `matched`),
 * keyed by posting id; the application layer supplies it from the persistence view.
 */
fun createPostingReplacement(
    id: String,
    fromPostingId: PostingId,
    toPostingId: PostingId,
    fromVersion: TransactionVersion,
    toVersion: TransactionVersion,
    fromFacts: PostingFacts,
    toFacts: PostingFacts,
    fromAccount: Account,
    activeMatchesByPosting: Map<PostingId, ReconciliationMatch>,
    reconciliationEffect: ReconciliationEffect,
): DomainResult<PostingReplacement> {
    if (id.isBlank() || fromPostingId.value.isBlank() || toPostingId.value.isBlank()) {
        return DomainResult.Failure(PostingReplacementViolation.IdentityRequired)
    }
    if (
        fromVersion.transactionId != toVersion.transactionId ||
        toVersion.versionNumber != fromVersion.versionNumber + 1
    ) {
        return DomainResult.Failure(PostingReplacementViolation.NonConsecutiveVersions)
    }
    val isReal = fromAccount.ownedByUser && fromAccount.realAccount
    val sameFacts = fromFacts.sameAs(toFacts)
    return when (reconciliationEffect) {
        ReconciliationEffect.NOT_APPLICABLE ->
            if (isReal) {
                DomainResult.Failure(PostingReplacementViolation.NotApplicableOnRealPosting)
            } else {
                DomainResult.Success(PostingReplacement(id, fromPostingId, toPostingId, reconciliationEffect))
            }
        ReconciliationEffect.PRESERVED -> {
            if (!sameFacts) {
                return DomainResult.Failure(PostingReplacementViolation.PreservedRequiresUnchangedFacts)
            }
            val predecessor = activeMatchesByPosting[fromPostingId]
            val successor = activeMatchesByPosting[toPostingId]
            if (
                predecessor == null ||
                successor == null ||
                predecessor.evidenceId != successor.evidenceId
            ) {
                return DomainResult.Failure(PostingReplacementViolation.PreservedRequiresInheritedEvidenceMatch)
            }
            DomainResult.Success(PostingReplacement(id, fromPostingId, toPostingId, reconciliationEffect))
        }
        ReconciliationEffect.INVALIDATED ->
            if (!isReal || sameFacts) {
                DomainResult.Failure(PostingReplacementViolation.InvalidatedRequiresChangedRealFacts)
            } else {
                DomainResult.Success(PostingReplacement(id, fromPostingId, toPostingId, reconciliationEffect))
            }
    }
}

/** D-085 RG-12 entity-internal posting replacement violations; none is a frozen rejection reason. */
sealed interface PostingReplacementViolation : DomainViolation {
    data object IdentityRequired : PostingReplacementViolation

    data object NonConsecutiveVersions : PostingReplacementViolation

    data object NotApplicableOnRealPosting : PostingReplacementViolation

    data object PreservedRequiresUnchangedFacts : PostingReplacementViolation

    data object PreservedRequiresInheritedEvidenceMatch : PostingReplacementViolation

    data object InvalidatedRequiresChangedRealFacts : PostingReplacementViolation
}
