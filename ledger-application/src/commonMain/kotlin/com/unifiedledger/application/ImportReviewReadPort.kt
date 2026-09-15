package com.unifiedledger.application

import com.unifiedledger.domain.LedgerId

/**
 * P7-04.B import review read model (D-146, spec section 4.5, Appendix A).
 *
 * Pure read-only projections over the v29 import spine tables (zero DDL): the
 * ledger-scoped candidate list, the single-candidate detail, and the duplicate-review
 * comparison set for a candidate's subject source. Every method fails loudly by default
 * (G6, [LedgerCurrentStateReadPort] precedent): an unimplemented port must never present an
 * empty list or a missing detail as a definitive verdict. Implementations (the ledger-data
 * adapter) return genuinely empty or absent results explicitly.
 *
 * One ledger-scoped import-review list row (spec section 4.5.2; all fields are read-only).
 */
data class ImportReviewRow(
    val candidateId: ImportCandidateId,
    /** Candidate-layer kind token (`ordinary_flow`, `transfer_flow`, ..., frozen vocabulary). */
    val candidateKind: String,
    /**
     * The subject source's opaque pick-session handle (R-Q09-1: random UUIDv7, no personal
     * identifier, D06-safe). Batch-duplicate disposition grouping is parameterized by this
     * handle (P704SPEC-12); it is never a content fingerprint.
     */
    val sourceInputRef: String,
    /** Source-fact summary; nullable columns mirror the `import_source_record` shape. */
    val amountMinor: Long?,
    val currencyCode: String?,
    val currencyPrecision: Int?,
    val occurredAt: String?,
    val directionToken: String?,
    val statusToken: String?,
    val fundingState: ImportFundingState,
    val completeness: ImportCompleteness,
    /** Expected-content-hash source for confirm/reject requests. */
    val contentHash: String,
    /** Latest candidate status token (`pending_confirmation`/`confirmed`/`rejected`/`incomplete`). */
    val candidateStatus: String,
    val requiresConfirmation: Boolean,
    val confidence: String,
    /**
     * Latest duplicate disposition for the candidate's subject source, or null when the
     * source has no duplicate candidate. Folded across multiple duplicate candidates with
     * the blocking verdict first (any latest `CONFIRMED_DUPLICATE`, else any `DEFERRED`,
     * else any reviewed retain/dismiss verdict).
     */
    val duplicateStatus: ImportDuplicateStatus?,
    /** v3 payment profile summary (null for v1/v2 candidates). */
    val paymentProfileVariant: String?,
    val paymentProfileAssetLegKindToken: String?,
    val paymentProfileCreditLegKindToken: String?,
)

/** Single-candidate detail (spec section 4.5.4): every list-row field plus the history count. */
data class ImportCandidateDetailRow(
    val row: ImportReviewRow,
    val statusHistoryCount: Long,
)

/**
 * Facts of a possibly-existing source matched by a duplicate candidate. Null for
 * `CLOSED_OR_FAILED_NO_FUNDS` rows (D-105: no directed target).
 */
data class ImportDuplicatePossibleExistingSourceFacts(
    val sourceId: ImportSourceId,
    val amountMinor: Long?,
    val currencyCode: String?,
    val currencyPrecision: Int?,
    val occurredAt: String?,
    val directionToken: String?,
    val statusToken: String?,
)

/** One duplicate candidate of a subject source, with its latest state and review (Appendix A). */
data class ImportDuplicateReviewRow(
    val duplicateCandidateId: ImportDuplicateCandidateId,
    val kind: String,
    /** Privacy-safe frozen comparison snapshot (P4-07 projection), read as persisted. */
    val comparisonFingerprint: String,
    val comparisonSnapshot: String,
    val latestStatus: ImportDuplicateStatus,
    /** Latest review disposition + reason token, or null before any review. */
    val reviewDecision: String?,
    val reviewReasonToken: String?,
    val reviewedAt: String?,
    val possibleExistingSource: ImportDuplicatePossibleExistingSourceFacts?,
)

/**
 * Import review read port. The port expresses lookups by candidateId; the duplicate-review
 * granularity is the subject source, and implementations resolve candidate -> source via
 * `import_candidate.source_id` (UNIQUE `(ledger_id, source_id)`, Appendix A join note).
 */
interface ImportReviewReadPort {
    fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow> =
        throw UnsupportedOperationException(
            "loadImportReviewRows is not implemented by this read port; a missing import-review " +
                "list read must surface as a typed read failure, never as an empty list (D-146, G6)",
        )

    fun loadImportCandidateDetail(
        ledgerId: LedgerId,
        candidateId: ImportCandidateId,
    ): ImportCandidateDetailRow? =
        throw UnsupportedOperationException(
            "loadImportCandidateDetail is not implemented by this read port; a missing candidate " +
                "detail read must surface as a typed read failure, never as a definitive " +
                "no-such-candidate verdict (D-146, G6)",
        )

    /**
     * The duplicate-review rows of the candidate's subject source, or null when the
     * candidate id has no row on the ledger (an explicit absent verdict, distinct from a
     * genuine empty comparison set). Implementations resolve candidate -> source via
     * `import_candidate.source_id` (UNIQUE `(ledger_id, source_id)`).
     */
    fun loadImportDuplicateReviews(
        ledgerId: LedgerId,
        candidateId: ImportCandidateId,
    ): List<ImportDuplicateReviewRow>? =
        throw UnsupportedOperationException(
            "loadImportDuplicateReviews is not implemented by this read port; a missing duplicate " +
                "review read must surface as a typed read failure, never as an empty comparison " +
                "(D-146, G6)",
        )
}

/** Frozen result family of the review-row list query (P7-03 typed-read-failure precedent). */
sealed interface ImportReviewRowsResult {
    data class Rows(
        val rows: List<ImportReviewRow>,
    ) : ImportReviewRowsResult

    data object Unavailable : ImportReviewRowsResult
}

/** Frozen result family of the candidate-detail query. */
sealed interface ImportCandidateDetailResult {
    data class Found(
        val detail: ImportCandidateDetailRow,
    ) : ImportCandidateDetailResult

    /** The candidate id genuinely has no row on this ledger. */
    data object Absent : ImportCandidateDetailResult

    data object Unavailable : ImportCandidateDetailResult
}

/** Frozen result family of the duplicate-review query. */
sealed interface ImportDuplicateReviewsResult {
    data class Reviews(
        val reviews: List<ImportDuplicateReviewRow>,
    ) : ImportDuplicateReviewsResult

    /** The candidate id genuinely has no row on this ledger (so no duplicate reviews either). */
    data object Absent : ImportDuplicateReviewsResult

    /** A present candidate whose source genuinely has no duplicate candidate. */
    data object NoDuplicates : ImportDuplicateReviewsResult

    data object Unavailable : ImportDuplicateReviewsResult
}

/**
 * P7-04.B application use cases over [ImportReviewReadPort] (fail-loud default). Read-port
 * exceptions map to [ImportReviewRowsResult.Unavailable] /
 * [ImportCandidateDetailResult.Unavailable] / [ImportDuplicateReviewsResult.Unavailable] —
 * never to empty lists, so an unimplemented or failing read can never clear an already
 * rendered review list (P7-03 F1 discipline).
 */
class QueryImportReviewRows(
    private val readPort: ImportReviewReadPort,
) {
    fun query(ledgerId: LedgerId): ImportReviewRowsResult =
        try {
            ImportReviewRowsResult.Rows(readPort.loadImportReviewRows(ledgerId))
        } catch (failure: Exception) {
            ImportReviewRowsResult.Unavailable
        }
}

class QueryImportCandidateDetail(
    private val readPort: ImportReviewReadPort,
) {
    fun query(
        ledgerId: LedgerId,
        candidateId: ImportCandidateId,
    ): ImportCandidateDetailResult =
        try {
            readPort
                .loadImportCandidateDetail(ledgerId, candidateId)
                ?.let(ImportCandidateDetailResult::Found)
                ?: ImportCandidateDetailResult.Absent
        } catch (failure: Exception) {
            ImportCandidateDetailResult.Unavailable
        }
}

class QueryImportDuplicateReviews(
    private val readPort: ImportReviewReadPort,
) {
    /**
     * The duplicate-review comparison set of the candidate's subject source. The candidate
     * itself must exist on the ledger: an absent candidate is [ImportDuplicateReviewsResult.Absent],
     * a present candidate without duplicates is [ImportDuplicateReviewsResult.NoDuplicates]
     * (both explicit verdicts, distinct from a read failure).
     */
    fun query(
        ledgerId: LedgerId,
        candidateId: ImportCandidateId,
    ): ImportDuplicateReviewsResult =
        try {
            when (val reviews = readPort.loadImportDuplicateReviews(ledgerId, candidateId)) {
                null -> ImportDuplicateReviewsResult.Absent
                emptyList<ImportDuplicateReviewRow>() -> ImportDuplicateReviewsResult.NoDuplicates
                else -> ImportDuplicateReviewsResult.Reviews(reviews)
            }
        } catch (failure: Exception) {
            ImportDuplicateReviewsResult.Unavailable
        }
}
