package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * D-084 RG08-GAP-03 typed evidence subtype. The subtype is derived from the source record kind,
 * so bank, mirror and agreement evidence keep their typed provenance.
 */
enum class LendingEvidenceType {
    ASSET_DEBIT,
    ASSET_CREDIT,
    ASSET_CREDIT_MIRROR,
    LENDING_AGREEMENT,
}

data class LendingEvidence(
    val id: String,
    val sourceId: String,
    val type: LendingEvidenceType,
    val observedAt: Instant,
)

/**
 * Derives the typed evidence subtype from the source record kind. Manual confirmation sources
 * are not bank/agreement evidence and cannot produce an evidence entity.
 */
fun createLendingEvidence(
    id: String,
    source: LendingSourceRecord,
    observedAt: Instant = source.observedAt,
): DomainResult<LendingEvidence> {
    val type =
        when (source.kind) {
            LendingSourceKind.BANK_DEBIT -> LendingEvidenceType.ASSET_DEBIT
            LendingSourceKind.BANK_CREDIT -> LendingEvidenceType.ASSET_CREDIT
            LendingSourceKind.BANK_CREDIT_MIRROR -> LendingEvidenceType.ASSET_CREDIT_MIRROR
            LendingSourceKind.LENDING_AGREEMENT -> LendingEvidenceType.LENDING_AGREEMENT
            LendingSourceKind.EXPLICIT_MANUAL_LENDING_CONFIRMATION ->
                return DomainResult.Failure(LendingViolation.InvalidEvidenceSourceType)
        }
    return DomainResult.Success(
        LendingEvidence(
            id = id,
            sourceId = source.id,
            type = type,
            observedAt = observedAt,
        ),
    )
}

/**
 * D-084 RG08-GAP-03 typed evidence-link role. Posting roles target a posting; the relationship
 * role targets a lending position.
 */
enum class LendingEvidenceLinkRole {
    DESTINATION_ASSET_POSTING,
    FUNDING_ASSET_POSTING,
    COUNTERPARTY_LENDING_RELATIONSHIP,
    ;

    val targetsPosting: Boolean
        get() = this != COUNTERPARTY_LENDING_RELATIONSHIP
}

enum class LendingEvidenceLinkStatus {
    MATCHED,
    SUPPORTED,
    MERGED,
}

/**
 * A typed evidence link. `targetId` is a posting id for posting roles and a position id for the
 * relationship role; the endpoint kind is enforced at construction. Mirror/merge references are
 * NOT fields here — they are typed audit links (RG08-GAP-03).
 */
data class LendingEvidenceLink(
    val id: String,
    val sourceId: String,
    val evidenceId: String,
    val role: LendingEvidenceLinkRole,
    val targetId: String,
    val status: LendingEvidenceLinkStatus,
)

/**
 * D-084 RG08-GAP-03 typed audit-link kind. `mirror_of_evidence_id` and
 * `merged_into_evidence_link_id` are expressed as from/to audit references, never as
 * evidence-link fields.
 */
enum class LendingAuditLinkKind {
    /** from = mirror evidence id, to = the mirrored (original) evidence id. */
    MIRROR_OF_EVIDENCE,

    /** from = mirror evidence-link id, to = the evidence-link it merged into. */
    MERGED_INTO_EVIDENCE_LINK,
}

data class LendingAuditLink(
    val id: String,
    val kind: LendingAuditLinkKind,
    val fromId: String,
    val toId: String,
)

data class LendingEvidenceLinkResult(
    val link: LendingEvidenceLink,
    val auditLinks: List<LendingAuditLink>,
)

/**
 * Constructs a typed evidence link and its audit links. Invariants (D-084 RG08-GAP-03, frozen
 * fixture, `test_nested_reference_mutations_are_rejected`):
 * - posting roles require a posting target and forbid a position target; the relationship role
 *   requires a position target and forbids a posting target;
 * - a merged link owns exactly one merged-into evidence-link audit reference and vice versa;
 * - a mirror reference points at a different evidence and only exists in merged form;
 * - the audit endpoints are typed by construction (evidence ids vs evidence-link ids) and can
 *   never reference the wrong entity kind.
 */
fun createLendingEvidenceLink(
    id: String,
    sourceId: String,
    evidenceId: String,
    role: LendingEvidenceLinkRole,
    targetPostingId: PostingId? = null,
    targetPositionId: String? = null,
    status: LendingEvidenceLinkStatus = LendingEvidenceLinkStatus.MATCHED,
    mirrorOfEvidenceId: String? = null,
    mergedIntoEvidenceLinkId: String? = null,
): DomainResult<LendingEvidenceLinkResult> {
    if (id.isBlank() || sourceId.isBlank() || evidenceId.isBlank()) {
        return DomainResult.Failure(LendingViolation.InvalidEvidenceLink)
    }
    if (role.targetsPosting) {
        if (targetPostingId == null || targetPositionId != null) {
            return DomainResult.Failure(LendingViolation.InvalidEvidenceLink)
        }
    } else {
        if (targetPositionId == null || targetPostingId != null) {
            return DomainResult.Failure(LendingViolation.InvalidEvidenceLink)
        }
    }
    val isMerged = status == LendingEvidenceLinkStatus.MERGED
    if (isMerged != (mergedIntoEvidenceLinkId != null)) {
        return DomainResult.Failure(LendingViolation.InvalidEvidenceLink)
    }
    if (mergedIntoEvidenceLinkId != null) {
        if (mergedIntoEvidenceLinkId == id) {
            return DomainResult.Failure(LendingViolation.InvalidAuditLink)
        }
    }
    if (mirrorOfEvidenceId != null) {
        if (mirrorOfEvidenceId == evidenceId) {
            return DomainResult.Failure(LendingViolation.InvalidAuditLink)
        }
        if (!isMerged) {
            return DomainResult.Failure(LendingViolation.InvalidEvidenceLink)
        }
    }
    val targetId = targetPostingId?.value ?: targetPositionId!!
    val auditLinks =
        buildList {
            if (mirrorOfEvidenceId != null) {
                add(
                    LendingAuditLink(
                        id = "$id-mirror-of-evidence",
                        kind = LendingAuditLinkKind.MIRROR_OF_EVIDENCE,
                        fromId = evidenceId,
                        toId = mirrorOfEvidenceId,
                    ),
                )
            }
            if (mergedIntoEvidenceLinkId != null) {
                add(
                    LendingAuditLink(
                        id = "$id-merged-into-evidence-link",
                        kind = LendingAuditLinkKind.MERGED_INTO_EVIDENCE_LINK,
                        fromId = id,
                        toId = mergedIntoEvidenceLinkId,
                    ),
                )
            }
        }
    return DomainResult.Success(
        LendingEvidenceLinkResult(
            link =
                LendingEvidenceLink(
                    id = id,
                    sourceId = sourceId,
                    evidenceId = evidenceId,
                    role = role,
                    targetId = targetId,
                    status = status,
                ),
            auditLinks = auditLinks,
        ),
    )
}
