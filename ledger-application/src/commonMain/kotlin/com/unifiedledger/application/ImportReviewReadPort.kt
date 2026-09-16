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
 * P7-05 enumeration performance batch: one duplicate-review row of the session-level batch
 * read. The per-row shape of [ImportDuplicateReviewRow] plus the SUBJECT candidate the row
 * folds back to — the per-candidate read implies the subject through its parameter, so the
 * batch read must carry it per row for the caller's client-side fold.
 */
data class ImportDuplicateReviewsForSessionRow(
    /** The import candidate whose subject source the duplicate belongs to (the fold key). */
    val subjectCandidateId: ImportCandidateId,
    val review: ImportDuplicateReviewRow,
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

    /**
     * The duplicate-review rows of EVERY candidate whose subject source belongs to one pick
     * session ([sessionInputRef] = the session handle, R-Q09-1), read in one batch. The
     * session-level counterpart of [loadImportDuplicateReviews]: `null` means the session has
     * no candidate row on this ledger (the session-granularity form of the explicit absent
     * verdict), a genuinely empty list means no subject source of the session has any
     * duplicate candidate — a present-session read whose rows simply contain no duplicate.
     *
     * Each row carries its subject candidate ([ImportDuplicateReviewsForSessionRow.
     * subjectCandidateId]) so the caller can fold the batch per subject candidate; a session
     * with no candidate at all is behaviorally equivalent to every candidate yielding an
     * empty comparison set.
     */
    fun loadImportDuplicateReviewsForSession(
        ledgerId: LedgerId,
        sessionInputRef: String,
    ): List<ImportDuplicateReviewsForSessionRow>? =
        throw UnsupportedOperationException(
            "loadImportDuplicateReviewsForSession is not implemented by this read port; a missing " +
                "session duplicate-review read must surface as a typed read failure, never as an " +
                "empty comparison set (D-146, G6)",
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
 * Frozen result family of the session-level duplicate-review query (the batch counterpart of
 * [ImportDuplicateReviewsResult]; the P7-05 enumeration performance batch). `Reviews` carries
 * the whole pick session's duplicate-review rows, each with its subject candidate; `Absent`
 * means the session has no candidate row on the ledger; `NoDuplicates` means a present
 * session whose subjects genuinely have no duplicate candidate. `Unavailable` is the typed
 * read failure (G6: never an empty verdict).
 */
sealed interface ImportDuplicateReviewsForSessionResult {
    data class Reviews(
        val reviews: List<ImportDuplicateReviewsForSessionRow>,
    ) : ImportDuplicateReviewsForSessionResult

    /** The session handle matches no candidate's subject source on this ledger. */
    data object Absent : ImportDuplicateReviewsForSessionResult

    /** A present session whose subject sources genuinely have no duplicate candidate. */
    data object NoDuplicates : ImportDuplicateReviewsForSessionResult

    data object Unavailable : ImportDuplicateReviewsForSessionResult
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

/**
 * P7-05 enumeration performance batch: the session-level duplicate-review query use case,
 * the batch counterpart of [QueryImportDuplicateReviews]. One read replaces the per-candidate
 * read loop behind the 整组确认页 enumeration; read-port exceptions map to
 * [ImportDuplicateReviewsForSessionResult.Unavailable] — never to an empty list (the same
 * F1/G6 discipline, so a failing batch read can never present a silently partial group).
 */
class QueryImportDuplicateReviewsForSession(
    private val readPort: ImportReviewReadPort,
) {
    /**
     * The whole pick session's duplicate-review rows ([sessionInputRef] = the session handle).
     * An absent session is [ImportDuplicateReviewsForSessionResult.Absent], a present session
     * whose subjects have no duplicate candidate is
     * [ImportDuplicateReviewsForSessionResult.NoDuplicates] (both explicit verdicts, distinct
     * from a read failure).
     */
    fun query(
        ledgerId: LedgerId,
        sessionInputRef: String,
    ): ImportDuplicateReviewsForSessionResult =
        try {
            when (val reviews = readPort.loadImportDuplicateReviewsForSession(ledgerId, sessionInputRef)) {
                null -> ImportDuplicateReviewsForSessionResult.Absent
                emptyList<ImportDuplicateReviewsForSessionRow>() -> ImportDuplicateReviewsForSessionResult.NoDuplicates
                else -> ImportDuplicateReviewsForSessionResult.Reviews(reviews)
            }
        } catch (failure: Exception) {
            ImportDuplicateReviewsForSessionResult.Unavailable
        }
}
