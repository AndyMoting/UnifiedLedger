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
    /**
     * P8-01a i3a (spec section 2.7; D-308 item 4): the candidate's subject evidence id — the anchor
     * of the 「用于核对既有分录」 entry (spec section 2.2 entry B). `null` when the subject source
     * has no evidence row (never a fabricated id).
     *
     * Both fields default because eight existing test fixtures construct this row with the
     * pre-i3a field set; the one production constructor (the ledger-data read adapter) always
     * passes both explicitly from the query's own columns.
     */
    val evidenceId: String? = null,
    /**
     * P8-01a i3a derived flag (spec section 2.7): an ACTIVE `evidence_link` exists for the
     * candidate's evidence, so the import candidate is already used for reconciling an existing
     * entry and must not produce a second formal transaction. Derived from `evidence_link` — no new
     * status row is persisted anywhere (D-308 item 4).
     */
    val usedForReconciliation: Boolean = false,
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
 * B6 group-card paging batch (D-280, spec `docs/specs/2026-11-04-b6-group-card-paged-
 * enumeration-design.md`): one page of the session-level duplicate-review read plus the
 * cursor the next page resumes from. The page rows are the same
 * [ImportDuplicateReviewsForSessionRow] shape as the whole-session read, restricted to
 * `duplicate.candidate_id > afterCandidateId` in ascending keyset order (the same ORDER BY
 * the whole-session query carries), so concatenating the pages reproduces the whole-session
 * list byte-identically. [nextAfterCandidateId] is the largest `duplicate.candidate_id` on
 * the page (the keyset cursor); a shorter-than-requested page means the keyset is exhausted.
 */
data class ImportDuplicateReviewsForSessionPage(
    val rows: List<ImportDuplicateReviewsForSessionRow>,
    val nextAfterCandidateId: ImportDuplicateCandidateId,
)

/**
 * A-PERF (D-293): one page of the ledger-scoped review list plus the cursor for the next page.
 *
 * [nextAfterCandidateId] is the LAST candidate id of this page (`null` when this page exhausted the
 * list). It is a keyset cursor, not an offset: the whole-list read already walks candidates by
 * `candidate_id > after`, so a page cut with the same key reproduces the same order and cannot skip
 * or duplicate a candidate while writes land between pages.
 */
data class ImportReviewRowsPage(
    val rows: List<ImportReviewRow>,
    val nextAfterCandidateId: String?,
)

/**
 * Import review read port. The port expresses lookups by candidateId; the duplicate-review
 * granularity is the subject source, and implementations resolve candidate -> source via
 * `import_candidate.source_id` (UNIQUE `(ledger_id, source_id)`, Appendix A join note).
 */
interface ImportReviewReadPort {
    /**
     * A-PERF (D-293): reads ONE page of the review list. `pageSize` counts CANDIDATES, not rows —
     * one candidate contributes its base row plus one row per duplicate, so the returned [rows]
     * may exceed `pageSize` while never splitting a candidate's run.
     *
     * The default throws: a read port that cannot page must fail loud rather than silently fall
     * back to the whole-ledger read this change exists to avoid.
     */
    fun loadImportReviewPage(
        ledgerId: LedgerId,
        pageSize: Long,
        afterCandidateId: String?,
    ): ImportReviewRowsPage =
        throw UnsupportedOperationException(
            "loadImportReviewPage is not implemented by this read port; the paged list must fail " +
                "loud rather than fall back to a whole-ledger read (D-293)",
        )

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

    /**
     * B6 group-card paging batch (D-280): one keyset page of [loadImportDuplicateReviewsForSession].
     * [afterCandidateId] is the previous page's [ImportDuplicateReviewsForSessionPage.
     * nextAfterCandidateId] (the FIRST page passes [ImportDuplicateCandidateId]("") — the
     * empty string is the frozen sentinel: real duplicate ids are non-empty UUIDv7 text, and
     * the implementation fails loud on any empty id row, so the keyset can never skip rows).
     * Returns null when the session has no candidate row on this ledger (the same explicit
     * absent verdict as the whole-session read); a present session whose page predicate
     * matches nothing returns an empty page (exhausted keyset), never null. The page size is
     * fixed by the implementation (spec §3.1: 200); the count companion
     * [countImportDuplicateCandidatesForSession] feeds the group card's 共 N 条 header with
     * the WHOLE-group total, never the landed-page count.
     */
    fun loadImportDuplicateReviewsForSessionPage(
        ledgerId: LedgerId,
        sessionInputRef: String,
        afterCandidateId: ImportDuplicateCandidateId,
    ): ImportDuplicateReviewsForSessionPage? =
        throw UnsupportedOperationException(
            "loadImportDuplicateReviewsForSessionPage is not implemented by this read port; a " +
                "missing paged session duplicate-review read must surface as a typed read " +
                "failure, never as a silently partial group (D-280)",
        )

    /**
     * B6 group-card paging batch (D-280): the WHOLE-GROUP member count of one pick session —
     * the rows the 整组确认页 enumerates (the P704SPEC-12 member predicate: the session's
     * EXACT_BUSINESS_TUPLE duplicates whose LATEST status is DEFERRED), NOT the session's raw
     * duplicate-candidate total (a duplicate disposed through the detail path is CONFIRMED
     * and must drop out of both the card and the header, or 共 N 条 overcounts and the
     * confirm set disagrees with the header). The header's 共 N 条 and the eventual per-item
     * confirm loop both bind to this total, so it must equal the concatenated page rows that
     * survive the pure function's member filter (JVM pin: count == the final landed
     * items.size).
     */
    fun countImportDuplicateCandidatesForSession(
        ledgerId: LedgerId,
        sessionInputRef: String,
    ): Long =
        throw UnsupportedOperationException(
            "countImportDuplicateCandidatesForSession is not implemented by this read port; a " +
                "missing group total must surface as a typed read failure, never as a zero " +
                "header (D-280)",
        )

    /**
     * B6 group-card paging batch (D-280): the implementation's fixed keyset page size (spec
     * §3.1: 200), so the HOST's exhaustion check (`pageRows.size < pageSize`) reads the SAME
     * value the pages are cut with instead of mirroring a constant (D-280 review R2-7 — a
     * smaller injected page size would otherwise break the host loop after page one).
     */
    fun importDuplicateGroupPageSize(): Long =
        throw UnsupportedOperationException(
            "importDuplicateGroupPageSize is not implemented by this read port; the host loop " +
                "needs the real page bound to detect keyset exhaustion (D-280)",
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

    /**
     * A-PERF (D-293): one page of the review list. Same fail-loud mapping as [query] — a failing or
     * unimplemented paged read is [ImportReviewRowsResult.Unavailable], never an empty page (an empty
     * page means "exhausted" and would silently truncate the list).
     */
    fun queryPage(
        ledgerId: LedgerId,
        pageSize: Long,
        afterCandidateId: String?,
    ): ImportReviewRowsPageResult =
        try {
            val page = readPort.loadImportReviewPage(ledgerId, pageSize, afterCandidateId)
            ImportReviewRowsPageResult.Page(page.rows, page.nextAfterCandidateId)
        } catch (failure: Exception) {
            ImportReviewRowsPageResult.Unavailable
        }
}

/** Typed outcome of [QueryImportReviewRows.queryPage] (P7-03 typed-read-failure precedent). */
sealed interface ImportReviewRowsPageResult {
    data class Page(
        val rows: List<ImportReviewRow>,
        val nextAfterCandidateId: String?,
    ) : ImportReviewRowsPageResult

    data object Unavailable : ImportReviewRowsPageResult
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

/**
 * Frozen result family of the paged session-level duplicate-review query (D-280). `Page`
 * carries the keyset page rows AND the resume cursor in one read (the caller never re-reads
 * a page to learn its cursor); `Absent` keeps the whole-session read's explicit absent
 * verdict; `Unavailable` is the typed read failure (G6 — never an empty page).
 */
sealed interface ImportDuplicateReviewsForSessionPageResult {
    data class Page(
        val page: ImportDuplicateReviewsForSessionPage,
    ) : ImportDuplicateReviewsForSessionPageResult

    /** The session handle matches no candidate's subject source on this ledger. */
    data object Absent : ImportDuplicateReviewsForSessionPageResult

    data object Unavailable : ImportDuplicateReviewsForSessionPageResult
}

/**
 * B6 group-card paging batch (D-280): the paged session-level duplicate-review query use
 * case. One read returns one keyset page of the whole-session batch — the rows plus the
 * resume cursor in the same result, so driving the pages costs exactly one query per page.
 * The caller drives the pages inside ONE lease so the whole paging round shares the
 * snapshot-consistency window of the unpaginated read (spec §2.5). Read-port exceptions map
 * to [ImportDuplicateReviewsForSessionPageResult.Unavailable] — never to an empty page, so
 * a failing page read can never present a silently partial group (G6, the same discipline
 * as [QueryImportDuplicateReviewsForSession]).
 */
class QueryImportDuplicateReviewsForSessionPage(
    private val readPort: ImportReviewReadPort,
) {
    /**
     * The next keyset page of the session's duplicate-review rows ([afterCandidateId] =
     * the previous page's [ImportDuplicateReviewsForSessionPage.nextAfterCandidateId]; the
     * FIRST page passes the smallest possible [ImportDuplicateCandidateId] value). An absent
     * session is [ImportDuplicateReviewsForSessionPageResult.Absent]; a page whose predicate
     * matches nothing is a `Page` with empty rows (the caller's exhaustion signal), never a
     * typed failure.
     */
    fun query(
        ledgerId: LedgerId,
        sessionInputRef: String,
        afterCandidateId: ImportDuplicateCandidateId,
    ): ImportDuplicateReviewsForSessionPageResult =
        try {
            val page = readPort.loadImportDuplicateReviewsForSessionPage(ledgerId, sessionInputRef, afterCandidateId)
            if (page == null) {
                ImportDuplicateReviewsForSessionPageResult.Absent
            } else {
                ImportDuplicateReviewsForSessionPageResult.Page(page)
            }
        } catch (failure: Exception) {
            ImportDuplicateReviewsForSessionPageResult.Unavailable
        }

    /** The implementation's fixed keyset page bound, for the host's exhaustion check (R2-7). */
    fun pageSize(): Long? =
        try {
            readPort.importDuplicateGroupPageSize()
        } catch (failure: UnsupportedOperationException) {
            null
        }
}

/**
 * B6 group-card paging batch (D-280): the whole-group duplicate-candidate count of one pick
 * session. The 整组确认页 header's 共 N 条 binds to this total (never the landed-page
 * count), and the confirm loop's completion gate compares the landed rows against it.
 * Read-port exceptions map to null (the caller surfaces the typed read failure) — a failing
 * count must never read as a zero-item group.
 */
class CountImportDuplicateCandidatesForSession(
    private val readPort: ImportReviewReadPort,
) {
    fun query(
        ledgerId: LedgerId,
        sessionInputRef: String,
    ): Long? =
        try {
            readPort.countImportDuplicateCandidatesForSession(ledgerId, sessionInputRef)
        } catch (failure: Exception) {
            null
        }
}
