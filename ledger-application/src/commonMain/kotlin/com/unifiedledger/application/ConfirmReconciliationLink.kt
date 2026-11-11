package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId
import kotlin.time.Instant

/**
 * P8-01a i2 confirm (spec section 2.5; D-308 item 3).
 *
 * The application layer owns the [P408ConfirmLinkRequest] construction and nothing else: the
 * responsibility is decided by the leg's direction alone (`out` → [P408EvidenceResponsibility.REAL_ACCOUNT_POSTING],
 * `in` → [P408EvidenceResponsibility.DESTINATION_ASSET_POSTING] — O-5), the five-field match basis
 * and the natural-day distance are copied from the frozen matcher's own [P408MatchBasis], the window
 * is the matcher's window, the projection identity is the `READY` authority row, and the timestamps
 * come from the injected [LedgerClock]. This use case never invents a basis, a distance, a window
 * or a responsibility, and it never chooses among candidates: the caller hands it the one candidate
 * the user confirmed (for an `AMBIGUOUS` result that choice is the O-3 human decision).
 *
 * NO NEW WRITE PATH: the only write is the existing `P408ReconciliationCommitPort.confirmLink`,
 * which owns every refusal the spec pins — `P408_EVIDENCE_ALREADY_LINKED`,
 * `P408_POSTING_RESPONSIBILITY_ALREADY_LINKED`, `P408_REQUEST_IDENTITY_CONFLICT`,
 * `P408_POSTING_TIME_WINDOW_MISMATCH` and the rest — so those codes reach the caller verbatim
 * instead of being pre-empted here by a second opinion.
 *
 * REQUEST IDENTITY (spec section 2.5 result-unknown rule; V-5): the store's replay path needs the
 * SAME requestId and fingerprint, so [P801ConfirmLinkInput.identity] may carry a previously issued
 * [P801ConfirmRequestIdentity] and this use case then uses it VERBATIM — same requestId, linkId,
 * reconciliationId and confirmedAt. A second call with that identity hits the store's `NoChange`
 * branch instead of minting a second link, which is what an i3 retry needs. When the identity is
 * absent the behaviour is unchanged: a fresh id triple and a fresh `clock.now()`.
 */
class ConfirmReconciliationLink(
    private val projections: P408EvidenceProjectionPort,
    private val commitPort: P408ReconciliationCommitPort,
    private val clock: LedgerClock,
    private val idSource: P801ReconciliationIdSource,
) {
    fun confirm(input: P801ConfirmLinkInput): P801ConfirmLinkResult {
        val evidence = input.evidence
        val candidate = input.candidate
        val candidateId = evidence.candidateId ?: return P801ConfirmLinkResult.Refused(P801ConfirmLinkRefusal.CANDIDATE_ID_UNAVAILABLE)
        // The store compares this text with the live source row, so it must be the persisted text:
        // a re-serialised instant would be a different string and a false identity conflict.
        val sourceOccurredAt = evidence.occurredAt.rawText
        if (sourceOccurredAt.isBlank()) {
            return P801ConfirmLinkResult.Refused(P801ConfirmLinkRefusal.SOURCE_TIME_UNRESOLVED)
        }
        val projection =
            projections.readProjection(input.ledgerId.value, evidence.evidenceId)
                ?: return P801ConfirmLinkResult.Refused(P801ConfirmLinkRefusal.EVIDENCE_NOT_PROJECTED)
        // The rejection CODE lives on the projection row (attached to the evidence read row as
        // [P801ReconciliationCandidateEvidence.projection] -> [P408EvidenceProjection.rejectionCode]);
        // this refusal only names the state so a rejected bind is never reported as "not projected".
        if (projection.state != P408ProjectionState.READY) {
            return P801ConfirmLinkResult.Refused(P801ConfirmLinkRefusal.EVIDENCE_PROJECTION_REJECTED)
        }
        // The ONE direction→duty rule (O-5); an unknown direction decides nothing.
        val responsibility =
            P408EvidenceResponsibility.forDirectionOrNull(candidate.posting.direction)
                ?: return P801ConfirmLinkResult.Refused(P801ConfirmLinkRefusal.RESPONSIBILITY_UNRESOLVED)
        val identity = input.identity
        val confirmedAt = identity?.confirmedAt ?: clock.now()
        val ids =
            identity?.let { P801ReconciliationIds(requestId = it.requestId, linkId = it.linkId, reconciliationId = it.reconciliationId) }
                ?: idSource.next()
        val request =
            P408ConfirmLinkRequest(
                ledgerId = input.ledgerId.value,
                requestId = ids.requestId,
                evidenceId = evidence.evidenceId,
                candidateId = candidateId,
                postingId = candidate.posting.postingId,
                transactionId = candidate.posting.transactionId,
                amountMinor = projection.normalizedAmountMinor,
                currencyCode = projection.currencyCode,
                currencyPrecision = projection.currencyPrecision,
                direction = projection.directionToken,
                accountId = projection.targetAccountId,
                responsibility = responsibility,
                basisVersion = WRITE_ALWAYS_BASIS_VERSION,
                matchBasis = candidate.basis.fields,
                windowDays = candidate.basis.windowDays,
                naturalDayDistance = candidate.basis.naturalDayDistance.toInt(),
                sourceOccurredAt = sourceOccurredAt,
                confirmedAt = confirmedAt.toString(),
                linkId = ids.linkId,
                reconciliationId = ids.reconciliationId,
                createdAt = confirmedAt.toString(),
                projectionId = projection.projectionId,
                projectionRuleId = projection.ruleId,
                projectionRuleVersion = projection.ruleVersion,
                normalizedAmountMinor = projection.normalizedAmountMinor,
                rawAmountMinor = projection.rawAmountMinor,
                rawCurrencyPrecision = projection.rawCurrencyPrecision,
            )
        return when (val result = commitPort.confirmLink(request)) {
            is P408ReconciliationResult.Accepted -> P801ConfirmLinkResult.Linked(result.receipt)
            is P408ReconciliationResult.NoChange -> P801ConfirmLinkResult.NoChange(result.receipt)
            is P408ReconciliationResult.Rejected -> P801ConfirmLinkResult.Rejected(result.code)
        }
    }

    private companion object {
        /** Every NEW write is v2; v1 rows stay readable and replayable only (spec section 2.5). */
        const val WRITE_ALWAYS_BASIS_VERSION: Int = 2
    }
}

/**
 * A previously issued confirmation identity, carried verbatim by a retry (spec section 2.5 /
 * V-5). Every field is the value the FIRST call used: [confirmedAt] is part of the request
 * fingerprint, so a retry that re-mints it would be a different request instead of a replay.
 */
data class P801ConfirmRequestIdentity(
    val requestId: String,
    val linkId: String,
    val reconciliationId: String,
    val confirmedAt: Instant,
)

/** One explicit confirmation: the evidence row and the matcher candidate the user confirmed. */
data class P801ConfirmLinkInput(
    val ledgerId: LedgerId,
    val evidence: P801ReconciliationCandidateEvidence,
    val candidate: P408MatchCandidate,
    /** Optional replay identity; `null` mints a fresh id triple and `clock.now()` (i1 behaviour). */
    val identity: P801ConfirmRequestIdentity? = null,
)

/** The result of one confirmation, in the port's own three branches plus the pre-write refusals. */
sealed interface P801ConfirmLinkResult {
    /** The link and its `CHECKED` reconciliation rows were written. */
    data class Linked(
        val receipt: P408ReconciliationReceipt,
    ) : P801ConfirmLinkResult

    /** Identical replay of the same request; treated as success and nothing is written twice. */
    data class NoChange(
        val receipt: P408ReconciliationReceipt,
    ) : P801ConfirmLinkResult

    /** The store refused; the code is rendered as-is (spec section 2.5). */
    data class Rejected(
        val code: String,
    ) : P801ConfirmLinkResult

    /** The use case refused before any write. */
    data class Refused(
        val reason: P801ConfirmLinkRefusal,
    ) : P801ConfirmLinkResult
}

/** Why a confirmation was refused before reaching the commit port (each writes nothing). */
enum class P801ConfirmLinkRefusal {
    /** The evidence has no importing candidate to name in the request. */
    CANDIDATE_ID_UNAVAILABLE,

    /** The evidence's persisted source time is absent, so there is no confirming text. */
    SOURCE_TIME_UNRESOLVED,

    /** The evidence has no current projection; candidates come from that authority only. */
    EVIDENCE_NOT_PROJECTED,

    /**
     * The current projection is `REJECTED`. The rejection code is on the projection row
     * ([P408EvidenceProjection.rejectionCode], attached to the evidence read row), so the caller
     * renders that code; a rejected projection is never described as "not projected".
     */
    EVIDENCE_PROJECTION_REJECTED,

    /** The candidate leg carries no usable direction, so the duty cannot be decided by direction. */
    RESPONSIBILITY_UNRESOLVED,
}
