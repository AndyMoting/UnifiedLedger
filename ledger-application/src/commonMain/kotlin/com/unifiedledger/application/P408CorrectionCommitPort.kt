package com.unifiedledger.application

/**
 * P4-08 correction / successor invalidation contract (approved implementation
 * spec, sections 4-9; D-113).
 *
 * A correction is an atomic single-request operation that, inside ONE
 * transaction: (1) appends an invalidation event to the predecessor link,
 * (2) optionally creates a successor link that re-proves the same evidence
 * against the corrected posting (with the controlled projection supersede when
 * the projection authority must be re-expressed), and (3) advances the affected
 * posting reconciliation to CHECKED / MISSING / DIFFERENCE. Financial balances,
 * transaction versions and report financial dimensions never change.
 *
 * idempotency: output/generated ids (requestId, successor link/reconciliation
 * ids, created_at) never participate in the fingerprint. An equivalent replay
 * returns the original receipt (NoChange); a changed retry is a typed zero-write
 * conflict. The correction family is v2-only by construction (the snapshot
 * basis_version is frozen to 2).
 */
enum class P408CorrectionReason(
    val storageValue: String,
) {
    /** Manual correction / re-match after supplementary material / wrong-match fix. */
    CORRECTED("corrected"),

    /** Version replacement: amount/account/currency of the formal posting changed. */
    POSTING_REPLACED("posting_replaced"),
}

/** Resulting posting-reconciliation state a correction writes (V-D-A). */
enum class P408CorrectionResultState(
    val storageValue: String,
) {
    CHECKED("CHECKED"),
    MISSING("MISSING"),
    DIFFERENCE("DIFFERENCE"),
}

/**
 * Successor confirmation facts, mirroring the confirmLink authority domain
 * (P408Reconciliation.kt:23-52) so the correction store can run the same
 * posting-integrity / five-field / window / responsibility-side checks.
 */
data class P408SuccessorLinkFacts(
    val postingId: String,
    val transactionId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Int,
    val direction: String,
    val accountId: String,
    val responsibility: P408EvidenceResponsibility,
    val candidateId: String,
    val matchBasis: Set<String>,
    val windowDays: Int,
    val naturalDayDistance: Int,
    val sourceOccurredAt: String,
)

data class P408CorrectLinkRequest(
    val ledgerId: String,
    val requestId: String,
    val evidenceId: String,
    val previousLinkId: String,
    val reason: P408CorrectionReason,
    val affectedPostingId: String,
    val resultState: P408CorrectionResultState,
    /** Required iff [resultState] == CHECKED; must be null otherwise. */
    val successor: P408SuccessorLinkFacts? = null,
    /** Projection authority quartet (required iff CHECKED). */
    val projectionId: String? = null,
    val projectionRuleId: String? = null,
    val projectionRuleVersion: Int? = null,
    val normalizedAmountMinor: Long? = null,
    val rawAmountMinor: Long? = null,
    val rawCurrencyPrecision: Int? = null,
    /** Correction decision time; provenance only, never a runtime Clock value. */
    val confirmedAt: String,
    /** Output/generated ids: excluded from the fingerprint (persistence spec :33-36). */
    val successorLinkId: String? = null,
    val successorCreatedAt: String? = null,
    val reconciliationId: String? = null,
) {
    init {
        require(ledgerId.isNotBlank() && requestId.isNotBlank())
        require(evidenceId.isNotBlank() && previousLinkId.isNotBlank())
        require(affectedPostingId.isNotBlank())
        require(confirmedAt.isNotBlank())
        if (resultState == P408CorrectionResultState.CHECKED) {
            require(successor != null) { "CHECKED correction requires successor facts" }
            require(!projectionId.isNullOrBlank() && !projectionRuleId.isNullOrBlank())
            require(projectionRuleVersion != null && projectionRuleVersion >= 1)
            require(normalizedAmountMinor != null && normalizedAmountMinor >= 0)
            require(rawAmountMinor != null && rawAmountMinor >= 0)
            require(rawCurrencyPrecision != null && rawCurrencyPrecision >= 0)
            require(!successorLinkId.isNullOrBlank())
            require(!successorCreatedAt.isNullOrBlank())
            require(!reconciliationId.isNullOrBlank())
            // V-C: the affected posting IS the successor posting.
            require(affectedPostingId == successor.postingId)
        } else {
            require(successor == null) { "MISSING/DIFFERENCE correction carries no successor facts" }
            require(
                projectionId == null &&
                    projectionRuleId == null &&
                    projectionRuleVersion == null &&
                    normalizedAmountMinor == null &&
                    rawAmountMinor == null &&
                    rawCurrencyPrecision == null,
            )
            require(successorLinkId == null && successorCreatedAt == null)
            require(!reconciliationId.isNullOrBlank())
        }
        successor?.let { s ->
            require(s.postingId.isNotBlank() && s.transactionId.isNotBlank())
            require(s.amountMinor >= 0)
            require(s.currencyCode.isNotBlank() && s.currencyPrecision >= 0)
            require(s.direction == "in" || s.direction == "out")
            require(s.accountId.isNotBlank() && s.candidateId.isNotBlank() && s.sourceOccurredAt.isNotBlank())
            require(s.matchBasis == REQUIRED_MATCH_BASIS)
            require(s.windowDays >= 0 && s.naturalDayDistance >= 0 && s.naturalDayDistance <= s.windowDays)
        }
    }

    /**
     * Stable UTF-8 identity; the correction fingerprint space is disjoint from
     * the confirm family by its prefix; set-valued basis tokens are sorted and
     * deduplicated. Output/generated ids are never part of this string.
     */
    fun fingerprint(): String =
        buildString {
            append("p408-correct-v2|")
            append("ledger=").append(ledgerId).append('|')
            append("evidence=").append(evidenceId).append('|')
            append("previous_link=").append(previousLinkId).append('|')
            append("reason=").append(reason.storageValue).append('|')
            append("affected_posting=").append(affectedPostingId).append('|')
            append("result_state=").append(resultState.storageValue)
            if (resultState == P408CorrectionResultState.CHECKED) {
                val s = requireNotNull(successor)
                append("|posting=").append(s.postingId)
                append("|transaction=").append(s.transactionId)
                append("|amount_minor=").append(s.amountMinor)
                append("|currency=").append(s.currencyCode)
                append("|precision=").append(s.currencyPrecision)
                append("|direction=").append(s.direction)
                append("|account=").append(s.accountId)
                append("|responsibility=").append(s.responsibility.storageValue)
                append("|candidate=").append(s.candidateId)
                append("|basis=").append(s.matchBasis.toSortedSet().joinToString(","))
                append("|window_days=").append(s.windowDays)
                append("|natural_day_distance=").append(s.naturalDayDistance)
                append("|source_occurred_at=").append(s.sourceOccurredAt)
                append("|projection_id=").append(projectionId)
                append("|projection_rule_id=").append(projectionRuleId)
                append("|projection_rule_version=").append(projectionRuleVersion)
                append("|normalized_amount_minor=").append(normalizedAmountMinor)
                append("|raw_amount_minor=").append(rawAmountMinor)
                append("|raw_currency_precision=").append(rawCurrencyPrecision)
            }
            append("|confirmed_at=").append(confirmedAt)
        }
}

/**
 * Sole product writer for the correction family. Appends the invalidation
 * event, creates the successor link and advances the posting reconciliation
 * state in a single transaction (never mutating or deleting the predecessor).
 */
interface P408CorrectionCommitPort {
    fun correct(request: P408CorrectLinkRequest): P408ReconciliationResult
}
