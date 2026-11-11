package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlin.time.Instant

/*
 * P8-01a account-scoped reconciliation read face (D-308 items 6/8; spec section 2.3).
 *
 * This port is the READ side only: it creates no reconciliation row, writes no evidence link and
 * changes no balance or report. It answers what the account queue needs — one keyset page of the
 * account's reconcilable legs, the per-(currency, status) totals over exactly the same legs, and
 * (P8-01a i2) the two facts the frozen matcher consumes: the candidate evidence around a leg and
 * the posting facts of the legs themselves.
 *
 * WHAT MAKES A LEG A QUEUE MEMBER. Exactly the spec section 2.1 composite mark
 * (`owned_by_user = 1 AND real_account = 1 AND kind IN ('ASSET','LIABILITY')`) on the leg's own
 * account, over the current version of an effective transaction. The mark is evaluated by the
 * data query against `catalog_account`, so page and totals share one membership definition; this
 * port never re-derives it from a partially loaded row set.
 *
 * NO NEUTRAL DEFAULT (R-Q06-4 / D-089). Every answer is a typed result: an absent payload is
 * either an empty [P801AccountReconciliationQueuePage] / an empty totals list (a genuine "no such
 * leg") or a [P801AccountReconciliationReadFailure] — never a zero amount, a zero count or an
 * empty result standing in for a read that failed. The page size is frozen by D-308 item 8.
 */

/** D-308 item 8: the frozen queue page size (100 legs per page). */
const val P801_ACCOUNT_RECONCILIATION_PAGE_SIZE: Int = 100

/**
 * One keyset position of the account reconciliation queue: the frozen page key
 * `(occurred_at DESC, posting_id ASC)` of D-308 item 8.
 *
 * [occurredAtStorageValue] is the PERSISTED `occurred_at` text of the last row of the previous
 * page (the same `*StorageValue` shape [TransactionReconciliationLegRow.statusStorageValue] uses),
 * never a re-serialised `Instant`: `occurred_at` is TEXT in mixed shapes (`...Z` and `...+08:00`),
 * so the implementation parses this text with the same epoch-nanosecond projection it parses the
 * column with, and passing back a re-serialised instant could skip or repeat a row at a page
 * boundary. The value is opaque to the application layer — take it from the page and hand it back
 * unchanged.
 */
data class P801AccountReconciliationCursor(
    val occurredAtStorageValue: String,
    val postingId: PostingId,
)

/**
 * One reconcilable leg of the account reconciliation queue (spec section 2.3). The amount is the
 * leg's exact ledger-signed minor units with its own [currency]; [status] is the stored
 * reconciliation status, where a leg with no `posting_reconciliation` row reads
 * [P408ReconciliationStatus.PENDING]; [transactionKind] is the transaction's effective kind
 * (`COALESCE(canonical_kind, kind)`), so the queue can label a leg by date, note or kind.
 */
data class P801AccountReconciliationQueueRow(
    val postingId: PostingId,
    val transactionId: TransactionId,
    val occurredAt: Instant,
    val note: String?,
    val accountId: AccountId,
    val amountMinor: Long,
    val currency: CurrencyUnit,
    val status: P408ReconciliationStatus,
    val hasActiveEvidenceLink: Boolean,
    val transactionKind: TransactionKind,
)

/**
 * One page of the account reconciliation queue. An empty [rows] list is a genuine "this account
 * has no such leg in this page position", never a failure; [nextCursor] is `null` exactly when
 * the page is the last one.
 */
data class P801AccountReconciliationQueuePage(
    val rows: List<P801AccountReconciliationQueueRow>,
    val nextCursor: P801AccountReconciliationCursor?,
)

/**
 * One `(currency, status)` group of the account reconciliation summary (spec section 2.3): the
 * exact integer-minor-unit sum and the leg count of that group.
 *
 * Only groups the ledger actually has are returned — an absent group stays absent instead of
 * being emitted as a zero row (D-089). A real `0` [totalMinorUnits] is therefore the sum of real
 * rows, which [rowCount] proves.
 */
data class P801AccountReconciliationStatusTotal(
    val currency: CurrencyUnit,
    val status: P408ReconciliationStatus,
    val totalMinorUnits: Long,
    val rowCount: Int,
) {
    init {
        require(rowCount >= 1) { "a reconciliation status total must cover at least one leg, never a fabricated zero row" }
    }
}

/** The typed failure family of the account reconciliation read face (spec section 2.3). */
sealed interface P801AccountReconciliationReadFailure {
    /**
     * The read failed, or a persisted value could not be read as its domain type, or the integer
     * minor-unit sum overflowed (SQLite refuses an overflowing INTEGER `sum()` instead of
     * returning a float). Every one of them is a typed failure: the caller keeps the rows it
     * already has and shows the failure rather than a zero (spec section 2.3, V-9).
     */
    data object Unavailable : P801AccountReconciliationReadFailure
}

/**
 * One import evidence row that can be proposed for, or bound to, one account (spec sections
 * 2.4/3; D-308 item 10).
 *
 * RAW FACTS STAY RAW (D-111/D-112): [rawAmountMinor], [rawCurrencyCode], [rawCurrencyPrecision]
 * and [rawDirectionToken] are the `import_source_record` values verbatim — `null` when the source
 * row genuinely has none, never a zero or a default — and [occurredAt] carries the persisted
 * `occurred_at` text with its temporal shape. Of the matcher's five-field funding identity, four
 * (amount, currency, direction, account) come from [projection] only, and only when that row is
 * `READY`; the fifth (`occurred_at_window`) is decided by the matcher from the raw [occurredAt]
 * text. Nothing on this type is normalized.
 *
 * [projection] is the one CURRENT `evidence_projection` row (`null` when the evidence has none).
 * A `REJECTED` row keeps its [P408EvidenceProjection.rejectionCode] so the reason can be shown
 * as-is. [hasActiveEvidenceLink] is the same active-last-history test the queue uses: an evidence
 * already linked keeps being listed so the caller can present it as no longer confirmable
 * (spec section 2.4), rather than having it disappear.
 */
data class P801ReconciliationCandidateEvidence(
    val evidenceId: String,
    val sourceId: String,
    /** The importing candidate that owns this evidence's source; `null` when the source has none. */
    val candidateId: String?,
    val contentHash: String,
    val rawAmountMinor: Long?,
    val rawCurrencyCode: String?,
    val rawCurrencyPrecision: Int?,
    val rawDirectionToken: String?,
    val occurredAt: P408TemporalEvidence,
    val projection: P408EvidenceProjection?,
    val hasActiveEvidenceLink: Boolean,
)

/** The result of one candidate-evidence read; an empty [P801CandidateEvidenceResult.Success] row
 * list is a genuine "no such evidence in this window", never a failure (D-089). */
sealed interface P801CandidateEvidenceResult {
    data class Success(
        val rows: List<P801ReconciliationCandidateEvidence>,
    ) : P801CandidateEvidenceResult

    data class Failed(
        val failure: P801AccountReconciliationReadFailure,
    ) : P801CandidateEvidenceResult
}

/** The result of one posting-facts read; an empty [P801AccountPostingFactsResult.Success] fact
 * list is a genuine "no leg of this account in this window", never a zero-filled stand-in (D-089). */
sealed interface P801AccountPostingFactsResult {
    data class Success(
        val facts: List<P408PostingFacts>,
    ) : P801AccountPostingFactsResult

    data class Failed(
        val failure: P801AccountReconciliationReadFailure,
    ) : P801AccountPostingFactsResult
}

/** The result of one queue page read. */
sealed interface P801AccountReconciliationPageResult {
    data class Success(
        val page: P801AccountReconciliationQueuePage,
    ) : P801AccountReconciliationPageResult

    data class Failed(
        val failure: P801AccountReconciliationReadFailure,
    ) : P801AccountReconciliationPageResult
}

/** The result of one summary read. */
sealed interface P801AccountReconciliationSummaryResult {
    data class Success(
        val totals: List<P801AccountReconciliationStatusTotal>,
    ) : P801AccountReconciliationSummaryResult

    data class Failed(
        val failure: P801AccountReconciliationReadFailure,
    ) : P801AccountReconciliationSummaryResult
}

/**
 * One `evidence_link_history` entry of a reconciled leg (spec section 2.6): the link's state, the
 * frozen reason token (`confirmed` / `posting_replaced` / `corrected`) and its own sequence.
 */
data class P801ReconciledLegLinkHistoryEntry(
    val sequence: Long,
    val state: String,
    val reason: String,
    val occurredAt: String,
)

/**
 * One `posting_reconciliation_history` entry of a reconciled leg (spec section 2.6): the five-state
 * status at that sequence, plus the link it was reached through (`null` on the seeded `PENDING` row).
 */
data class P801ReconciledLegStatusEntry(
    val sequence: Long,
    val status: P408ReconciliationStatus,
    val evidenceLinkId: String?,
    val occurredAt: String,
)

/**
 * The frozen decision basis of one link, read from `reconciliation_request_snapshot` (spec section
 * 2.6). [matchBasis] carries the persisted tokens verbatim in their stored (sorted) order — the
 * application layer never re-derives or re-orders them; [humanDecision] is the persisted token
 * (`confirm_match`), never a translated label.
 */
data class P801ReconciledLegDecision(
    val basisVersion: Int,
    val matchBasis: List<String>,
    val windowDays: Int,
    val naturalDayDistance: Int,
    val humanDecision: String,
    val decidedAt: String,
)

/**
 * Where the linked evidence came from (spec section 2.6: 哪一次导入、哪条来源行). [importInputRef]
 * is the import pick-session handle, [recordOrdinal] the row inside that import, [recordKind] the
 * frozen source record kind, and [occurredAt] the persisted source time text (null when the source
 * genuinely has none — never a re-serialised instant).
 */
data class P801ReconciledEvidenceSource(
    val evidenceId: String,
    val sourceId: String,
    val importInputRef: String,
    val recordOrdinal: Long,
    val recordKind: String,
    val occurredAt: String?,
)

/**
 * One evidence link of a reconciled leg (spec section 2.6): the evidence, its responsibility, the
 * link's current state with its full history, and — when the request snapshot is readable — the
 * frozen decision basis. [decision] is `null` when the link's request has no snapshot row: a typed
 * absence, never a zero-filled basis (D-089).
 */
data class P801ReconciledLegEvidenceLink(
    val linkId: String,
    val evidenceId: String,
    val candidateId: String,
    val requestId: String,
    val responsibility: P408EvidenceResponsibility,
    val createdAt: String,
    val state: String,
    val stateSequence: Long,
    val decision: P801ReconciledLegDecision?,
    val source: P801ReconciledEvidenceSource,
    val history: List<P801ReconciledLegLinkHistoryEntry>,
)

/**
 * The `posting_reconciliation` fact of one leg (spec section 2.6): the current status and the whole
 * history with sequence. Null on [P801ReconciledLegAudit.reconciliation] means the leg has no
 * reconciliation row at all — a typed absence, never a `PENDING` stand-in.
 */
data class P801ReconciledLegReconciliation(
    val reconciliationId: String,
    val status: P408ReconciliationStatus,
    val history: List<P801ReconciledLegStatusEntry>,
)

/**
 * The audit read-back of ONE reconciled leg (spec section 2.6; D-308 item 7). Read-only: nothing on
 * this type can rewrite or delete an existing link. [evidence] is empty for a leg without links;
 * [reconciliation] is null for a leg without a reconciliation row.
 */
data class P801ReconciledLegAudit(
    val postingId: PostingId,
    val transactionId: TransactionId,
    val reconciliation: P801ReconciledLegReconciliation?,
    val evidence: List<P801ReconciledLegEvidenceLink>,
)

/** The result of one audit read-back; [NotFound] is the explicit "no such leg on this ledger" verdict. */
sealed interface P801ReconciledLegAuditResult {
    data class Success(
        val audit: P801ReconciledLegAudit,
    ) : P801ReconciledLegAuditResult

    data object NotFound : P801ReconciledLegAuditResult

    data class Failed(
        val failure: P801AccountReconciliationReadFailure,
    ) : P801ReconciledLegAuditResult
}

/**
 * P8-01a account reconciliation read port (D-308 terms 6/8; spec section 2.3).
 *
 * Implementations MUST:
 *
 * - restrict every read to the ledger, to the CURRENT version of each EFFECTIVE transaction
 *   through the single effective-predicate definition point (`transaction_effective_state`), and
 *   to the requested account. The ACCOUNT SCOPE itself differs by read and is stated on each
 *   method: the queue/summary fold the section 2.1 composite mark over `catalog_account`, the
 *   windowed candidate read narrows through the CURRENT projection (an unprojected evidence has no
 *   account relation yet and stays) while the by-identity read is scoped by the ledger alone, and
 *   [loadAccountPostingFacts] reports every `ACCOUNT_TRANSFER` leg of the account in the window with
 *   `current` / `eligible_real_account` flags so the matcher excludes non-current or non-mark legs by
 *   rule rather than the read hiding them (non-transfer legs are not reported at all: the confirm
 *   write path refuses them, spec section 2.1);
 * - page by the frozen key `(occurred_at DESC, posting_id ASC)` with the caller's
 *   [P801_ACCOUNT_RECONCILIATION_PAGE_SIZE] default, so concatenating the pages reproduces the
 *   whole queue exactly once;
 * - keep every amount in exact integer minor units — no floating point, no `total()`, and no
 *   sum folded by the caller from a differently-filtered read;
 * - answer with a typed failure instead of a zero, an empty page or an empty totals list when the
 *   read fails (R-Q06-4). There is deliberately no neutral default on this interface: a port that
 *   cannot answer must fail loudly rather than look like a reconciled-empty account.
 */
interface P801AccountReconciliationReadPort {
    /**
     * One keyset page of [accountId]'s reconcilable legs, ordered `occurred_at DESC,
     * posting_id ASC`. [after] is the previous page's cursor, or `null` for the first page.
     */
    fun loadAccountReconciliationPage(
        ledgerId: LedgerId,
        accountId: AccountId,
        after: P801AccountReconciliationCursor?,
        pageSize: Int = P801_ACCOUNT_RECONCILIATION_PAGE_SIZE,
    ): P801AccountReconciliationPageResult

    /**
     * The exact per-(currency, status) totals over the SAME legs the page enumerates — the whole
     * account, never a window (D-308 item 8 registers the range selector as a later slice).
     */
    fun loadAccountReconciliationSummary(
        ledgerId: LedgerId,
        accountId: AccountId,
    ): P801AccountReconciliationSummaryResult

    /**
     * P8-01a entry A candidate evidence (spec sections 2.4/3; D-308 item 10): every `import_evidence`
     * row of [ledgerId] whose source time belongs to `[occurredFrom, occurredTo]`, plus EVERY row
     * whose source time the frozen matcher cannot place in any window at all — an offset-free
     * `local_datetime`, an offset-shaped but unparseable text, a NULL time. Those are reported as
     * `source_time_unresolved` by the matcher, so they must stay reachable; the window predicate
     * only applies to texts the matcher can actually compare (an explicit `Z`/`±hh:mm` suffix plus
     * the frozen `YYYY-MM-DDTHH:MM:SS` prefix), and everything else is kept unconditionally (D-089:
     * no silent absence).
     *
     * The account narrows the read through the CURRENT projection only: a row already projected to
     * a DIFFERENT account can neither be bound nor matched for [accountId] (projection content is
     * immutable), so it is excluded; an unprojected row has no account relation yet and stays
     * (binding is what creates one).
     */
    fun loadCandidateEvidence(
        ledgerId: LedgerId,
        accountId: AccountId,
        occurredFrom: Instant,
        occurredTo: Instant,
    ): P801CandidateEvidenceResult

    /**
     * P8-01a entry B candidate evidence (spec section 2.2): the same row by identity, with no window
     * and no account constraint — the evidence-centric entry anchors on one candidate's evidence
     * before either is known. Absent evidence is an empty [P801CandidateEvidenceResult.Success].
     */
    fun loadCandidateEvidenceById(
        ledgerId: LedgerId,
        evidenceId: String,
    ): P801CandidateEvidenceResult

    /**
     * P8-01a matcher posting facts (spec section 2.4): every `ACCOUNT_TRANSFER` leg of [accountId]
     * in `[occurredFrom, occurredTo]` as the frozen matcher's [P408PostingFacts], including legs
     * that are not `current` or not on a section 2.1 real account — those carry `false` so the
     * matcher excludes them by rule instead of the read hiding them.
     *
     * Non-transfer legs are NOT offered: the confirm write path accepts `ACCOUNT_TRANSFER` only
     * (spec section 2.1 / D-308 item 3), so a LEND/credit/ordinary leg must never be proposed as a
     * candidate that `confirmLink` would then refuse with `P408_POSTING_NOT_ELIGIBLE`.
     */
    fun loadAccountPostingFacts(
        ledgerId: LedgerId,
        accountId: AccountId,
        occurredFrom: Instant,
        occurredTo: Instant,
    ): P801AccountPostingFactsResult

    /**
     * P8-01a i3a audit read-back (spec section 2.6; D-308 item 7): ONE leg's evidence links with
     * their decision basis, the source row each evidence came from, the links' own state history
     * and the leg's `posting_reconciliation` status history — every one of them read-only.
     *
     * [postingId] must be a leg of [ledgerId]; an absent leg is [P801ReconciledLegAuditResult.NotFound]
     * (an explicit verdict), and a failing read is
     * [P801ReconciledLegAuditResult.Failed], never an empty audit.
     *
     * The default throws: an implementation that cannot answer must fail loud rather than present
     * a link-less, history-less audit as the truth (G6/R-Q06-4).
     */
    fun loadReconciledLegAudit(
        ledgerId: LedgerId,
        postingId: PostingId,
    ): P801ReconciledLegAuditResult =
        throw UnsupportedOperationException(
            "loadReconciledLegAudit is not implemented by this read port; a missing audit read must " +
                "surface as a typed read failure, never as an audit without links or history (D-089)",
        )
}
