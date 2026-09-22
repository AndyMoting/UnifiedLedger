package com.unifiedledger.data

import com.unifiedledger.application.ImportCandidateDetailRow
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicatePossibleExistingSourceFacts
import com.unifiedledger.application.ImportDuplicateReviewRow
import com.unifiedledger.application.ImportDuplicateReviewsForSessionRow
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportReviewReadPort
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId

/**
 * P7-04 OOM fix (design spec 2026-09-21 v0.2, D-168): candidates read per batch. 10,000 keeps
 * the 61k-candidate ledger at 7 batches; injectable through the adapter constructor so tests
 * can force the multi-batch path with a tiny value.
 */
private const val IMPORT_REVIEW_ROWS_PAGE_SIZE = 10_000L

/**
 * P7-04.B import review read adapter (D-146; implementation spec section 4.5, Appendix A).
 *
 * Implements [ImportReviewReadPort] against the single [LedgerDatabase] using the P7-04
 * read-only named queries (`importReviewRowsForLedger`, `importDuplicateReviewsForSource`,
 * plus the P7-05 session-level batch `importDuplicateReviewsForSession` behind the new
 * v30 covering index) and the existing `selectImportCandidateLatestSequence`. Every query is
 * ledger-filtered; the only DDL change of the batch is the additive v30 index (schema v30).
 * The candidate list query returns one row per
 * (candidate, duplicate candidate) pair, so duplicate rows are folded here with the blocking
 * verdict first (spec section 4.5.2). Exceptions propagate to the use-case boundary, which
 * maps them to `Unavailable` (G6: a read failure never degrades to an empty verdict).
 *
 * A-PERF (P7-04 read-governance batch, spec section 2.2): the single-candidate paths no longer
 * read the whole ledger. The detail projection uses the primary-key-targeted
 * `importReviewRowForCandidate` (byte-identical JOIN shape and retained ORDER BY, so the folded
 * row is equivalent to the previous whole-ledger read + filter); the two existence probes use
 * the lightweight `importCandidateExistsByCandidateId` / `importCandidateExistsByInputRef`
 * queries.
 *
 * P7-04 OOM fix (design spec 2026-09-21 v0.2, D-168): the list read is now a candidate-boundary
 * keyset-paged read inside one transaction (`importReviewRowsForLedgerPage`). The previous
 * whole-ledger materialization held four large structures at once (generated rows, column
 * projections, the per-candidate groupBy map and the folded result) and OOM'd at 61k candidates
 * (D-166). Batching by candidate id releases the per-batch structures before the next batch; the
 * output (complete, never truncated) is byte-identical to the whole-ledger fold.
 */
class SqlDelightImportReviewReadAdapter(
    private val database: LedgerDatabase,
    // P7-04 OOM fix: candidate-per-batch bound, injectable so tests exercise the multi-batch
    // path with a tiny page size. Long because SQLDelight types a LIMIT bind as INTEGER=Long.
    private val pageSize: Long = IMPORT_REVIEW_ROWS_PAGE_SIZE,
) : ImportReviewReadPort {
    override fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow> {
        // pageSize <= 0 is rejected up front. 0 would make `batch.size < pageSize` false on an
        // empty batch and send `batch.maxOf {}` into NoSuchElementException; a negative value
        // makes SQLite read LIMIT as unlimited (LIMIT -1 == no limit), silently losing paging.
        // Both are programming errors, not user input, so they fail loudly before any query.
        require(pageSize >= 1) { "import review page size must be >= 1, was $pageSize" }
        // One read-only transaction covers every batch (snapshot consistency, freeze item
        // ACC-SNAP-01). Each batch is folded INSIDE the transaction body so the raw per-batch
        // lists are released before the next batch is read — that release IS the bounded-memory
        // property this fix exists for. The cost is stated honestly in the design: the single
        // Android connection stays held across the folds too. noEnclosing = true does not change
        // the BEGIN mode on this driver version; it makes the nesting contract fail loud instead
        // of silently nesting. (SQLDelight 2.3.2's Transacter.transactionWithResult names this
        // parameter noEnclosing — there is no `readOnly` parameter.)
        return database.transactionWithResult(noEnclosing = true) {
            // Empty-string candidate id contract (design spec 2.2 point 4 / 6 item 4a,
            // ACC-T-EMPTYID-01). The keyset cursor starts at "" and advances with
            // `candidate_id > afterCandidateId`; a candidate whose id is exactly "" would be
            // permanently skipped (candidate_id > '' is never true) — silently absent, which
            // ACC-COUNT-01 forbids ("never silently drop a candidate"). The schema only requires
            // NOT NULL, so "" is representable; the current generator (UUIDv7, 36-char ASCII)
            // never emits it, but the contract is enforced here rather than assumed. This is a
            // single primary-key probe reusing the existing existence query — the chosen
            // behaviour is fail-loud, not silent loss.
            if (
                database.ledgerQueries
                    .importCandidateExistsByCandidateId(ledgerId.value, "")
                    .executeAsList()
                    .isNotEmpty()
            ) {
                throw IllegalStateException(
                    "import review list cannot page a candidate whose id is the empty string (ledger ${ledgerId.value})",
                )
            }
            val result = mutableListOf<ImportReviewRow>()
            var afterCandidateId = ""
            while (true) {
                val batch =
                    database.ledgerQueries
                        .importReviewRowsForLedgerPage(
                            ledgerId.value,
                            ledgerId.value, // :page_ledger_id — same scope, bound a second time
                            afterCandidateId,
                            pageSize,
                        ).executeAsList()
                        .map { it.toColumns() }
                result +=
                    batch
                        .groupBy { it.candidate_id }
                        .map { (_, groupedRows) -> groupedRows.first().toImportReviewRow(groupedRows) }
                if (batch.size.toLong() < pageSize) break
                afterCandidateId = batch.maxOf { it.candidate_id }
            }
            result
        }
    }

    /**
     * Detail projection (spec section 4.5.4): the list row plus the candidate's status-history
     * sequence high-water mark. Existence is decided by the list projection itself, so a
     * candidate without a status row is never reported absent (and vice versa).
     *
     * Derivation premise (AB-BE-QUAL-05): `statusHistoryCount` is
     * `selectImportCandidateLatestSequence` (`COALESCE(MAX(sequence), 0)`, Ledger.sq), so it
     * is a *count* only because the spine's write path assigns each candidate's status rows
     * contiguous 1-based sequences (a store invariant). Under that invariant MAX(sequence)
     * equals the row count; if a gap ever existed, the value would remain a sequence
     * high-water mark, not a row count.
     */
    override fun loadImportCandidateDetail(
        ledgerId: LedgerId,
        candidateId: ImportCandidateId,
    ): ImportCandidateDetailRow? {
        // A-PERF (P7-04 read-governance batch): the primary-key-targeted projection replaces
        // the whole-ledger list read + client filter. Same JOIN shape, same retained ORDER BY
        // and the same folding input as the list read, so the row set (and therefore the folded
        // detail row) is byte-equivalent to the previous filter; an absent candidate still reads
        // as exactly zero rows -> null (the explicit absent verdict is unchanged).
        val rows =
            database.ledgerQueries
                .importReviewRowForCandidate(ledgerId.value, candidateId.value)
                .executeAsList()
                .map { it.toColumns() }
        if (rows.isEmpty()) return null
        // Sequence high-water mark == row count under the contiguous 1-based premise above.
        val statusHistoryCount =
            database.ledgerQueries
                .selectImportCandidateLatestSequence(ledgerId.value, candidateId.value)
                .executeAsOne()
        return ImportCandidateDetailRow(
            row = rows.first().toImportReviewRow(rows),
            statusHistoryCount = statusHistoryCount,
        )
    }

    /**
     * Duplicate-review comparison set of the candidate's subject source. `null` means the
     * candidate id has no row on this ledger (an explicit absent verdict); a genuinely empty
     * list means the present candidate's source has no duplicate candidate.
     */
    override fun loadImportDuplicateReviews(
        ledgerId: LedgerId,
        candidateId: ImportCandidateId,
    ): List<ImportDuplicateReviewRow>? {
        // A-PERF: the existence probe reads the import_candidate primary key directly instead
        // of executing the whole 4-JOIN list query to answer "does this candidate exist". The
        // absent-vs-empty verdict is unchanged: no PK row -> null (explicit absent), a present
        // candidate's duplicate set follows the per-source read below.
        val candidateExists =
            database.ledgerQueries
                .importCandidateExistsByCandidateId(ledgerId.value, candidateId.value)
                .executeAsList()
                .isNotEmpty()
        if (!candidateExists) return null
        return database.ledgerQueries
            .importDuplicateReviewsForSource(ledgerId.value, candidateId.value)
            .executeAsList()
            .map { row -> row.toReviewRow() }
    }

    /**
     * P7-05 enumeration performance batch: the session-level batch read behind the 整组确认页
     * enumeration. One `importDuplicateReviewsForSession` query (Ledger.sq) replaces the
     * per-candidate read loop plus its full-list existence probe: the subject join resolves
     * the session handle directly, so a session with no candidate row yields an empty result
     * without any per-candidate probe (`null`/empty carry the port's absent/no-duplicates
     * semantics; the absent-vs-no-duplicates probe reuses the candidate list query once for
     * the whole session). Each row maps to an [ImportDuplicateReviewsForSessionRow] carrying
     * its subject candidate for the caller's client-side fold.
     */
    override fun loadImportDuplicateReviewsForSession(
        ledgerId: LedgerId,
        sessionInputRef: String,
    ): List<ImportDuplicateReviewsForSessionRow>? {
        val rows =
            database.ledgerQueries
                .importDuplicateReviewsForSession(ledgerId.value, sessionInputRef)
                .executeAsList()
        if (rows.isEmpty()) {
            // Absent (no candidate of this session) vs NoDuplicates (candidates exist but none
            // has a duplicate): the batch query cannot distinguish them alone, so a candidate
            // existence probe decides the null-vs-empty verdict. A-PERF: the probe is the
            // lightweight input_ref-targeted existence query (one index probe through the
            // source UNIQUE (ledger_id, input_ref, record_ordinal) and the candidate UNIQUE
            // (ledger_id, source_id)) — never the whole 4-JOIN list query. The probe runs once
            // for the whole session — never per candidate.
            val sessionHasCandidate =
                database.ledgerQueries
                    .importCandidateExistsByInputRef(ledgerId.value, sessionInputRef)
                    .executeAsList()
                    .isNotEmpty()
            return if (sessionHasCandidate) emptyList() else null
        }
        return rows.map { row -> row.toSessionReviewRow() }
    }
}

/**
 * Folds the (candidate, duplicate candidate) rows of one candidate into a single list row.
 * The latest duplicate disposition is the strongest present verdict, blocking first
 * (spec section 4.5.2): any latest `CONFIRMED_DUPLICATE`, else any `DEFERRED`, else any
 * reviewed retain/dismiss verdict.
 */
private fun com.unifiedledger.data.db.ImportDuplicateReviewsForSource.toReviewRow(): ImportDuplicateReviewRow =
    ImportDuplicateReviewRow(
        duplicateCandidateId = ImportDuplicateCandidateId(candidate_id),
        kind = kind,
        comparisonFingerprint = comparison_fingerprint,
        comparisonSnapshot = comparison_snapshot,
        latestStatus =
            ImportDuplicateStatus.valueOf(
                requireNotNull(latest_status) { "duplicate candidate has no status row" },
            ),
        reviewDecision = review_decision,
        reviewReasonToken = review_reason_token,
        reviewedAt = reviewed_at,
        possibleExistingSource =
            existing_source_id?.let { existingSourceId ->
                ImportDuplicatePossibleExistingSourceFacts(
                    sourceId = ImportSourceId(existingSourceId),
                    amountMinor = existing_amount_minor,
                    currencyCode = existing_currency_code,
                    currencyPrecision = existing_currency_precision?.toInt(),
                    occurredAt = existing_occurred_at,
                    directionToken = existing_direction_token,
                    statusToken = existing_status_token,
                )
            },
    )

/**
 * P7-05: maps one session-level batch row (the per-candidate row shape plus the leading
 * subject-candidate column) into its application read-model form. The review fields map
 * exactly like the per-candidate row; only the subject-candidate wrap is new.
 */
private fun com.unifiedledger.data.db.ImportDuplicateReviewsForSession.toSessionReviewRow(): ImportDuplicateReviewsForSessionRow =
    ImportDuplicateReviewsForSessionRow(
        subjectCandidateId = ImportCandidateId(subject_candidate_id),
        review =
            ImportDuplicateReviewRow(
                duplicateCandidateId = ImportDuplicateCandidateId(candidate_id),
                kind = kind,
                comparisonFingerprint = comparison_fingerprint,
                comparisonSnapshot = comparison_snapshot,
                latestStatus =
                    ImportDuplicateStatus.valueOf(
                        requireNotNull(latest_status) { "duplicate candidate has no status row" },
                    ),
                reviewDecision = review_decision,
                reviewReasonToken = review_reason_token,
                reviewedAt = reviewed_at,
                possibleExistingSource =
                    existing_source_id?.let { existingSourceId ->
                        ImportDuplicatePossibleExistingSourceFacts(
                            sourceId = ImportSourceId(existingSourceId),
                            amountMinor = existing_amount_minor,
                            currencyCode = existing_currency_code,
                            currencyPrecision = existing_currency_precision?.toInt(),
                            occurredAt = existing_occurred_at,
                            directionToken = existing_direction_token,
                            statusToken = existing_status_token,
                        )
                    },
            ),
    )

/**
 * The structural column projection shared byte-for-byte by the generated row types of
 * `importReviewRowsForLedger` and `importReviewRowForCandidate` (A-PERF: the targeted detail
 * projection kept the list query's exact column list, so one fold serves both reads). The
 * generated SQLDelight row classes are final and structurally unrelated at the Kotlin type
 * level, so both read paths widen their rows through these constructors into this private
 * holder — the widening is total (every column is listed), so any future column drift between
 * the two queries breaks compilation here rather than the fold.
 */
private data class ImportReviewRowColumns(
    val candidate_id: String,
    val candidate_kind: String,
    val confidence: String,
    val input_ref: String,
    val amount_minor: Long?,
    val currency_code: String?,
    val currency_precision: Long?,
    val occurred_at: String?,
    val direction_token: String?,
    val status_token: String?,
    val funding_state: String,
    val completeness: String,
    val content_hash: String,
    val candidate_status: String?,
    val requires_confirmation: Boolean,
    val duplicate_candidate_id: String?,
    val duplicate_latest_status: String?,
    val payment_profile_variant: String?,
    val payment_profile_asset_leg_kind_token: String?,
    val payment_profile_credit_leg_kind_token: String?,
)

private fun com.unifiedledger.data.db.ImportReviewRowsForLedger.toColumns(): ImportReviewRowColumns =
    ImportReviewRowColumns(
        candidate_id,
        candidate_kind,
        confidence,
        input_ref,
        amount_minor,
        currency_code,
        currency_precision,
        occurred_at,
        direction_token,
        status_token,
        funding_state,
        completeness,
        content_hash,
        candidate_status,
        requires_confirmation,
        duplicate_candidate_id,
        duplicate_latest_status,
        payment_profile_variant,
        payment_profile_asset_leg_kind_token,
        payment_profile_credit_leg_kind_token,
    )

private fun com.unifiedledger.data.db.ImportReviewRowForCandidate.toColumns(): ImportReviewRowColumns =
    ImportReviewRowColumns(
        candidate_id,
        candidate_kind,
        confidence,
        input_ref,
        amount_minor,
        currency_code,
        currency_precision,
        occurred_at,
        direction_token,
        status_token,
        funding_state,
        completeness,
        content_hash,
        candidate_status,
        requires_confirmation,
        duplicate_candidate_id,
        duplicate_latest_status,
        payment_profile_variant,
        payment_profile_asset_leg_kind_token,
        payment_profile_credit_leg_kind_token,
    )

/**
 * P7-04 OOM fix (design spec 2.3 point 1, D-168): the additive third overload for the generated
 * row type of the paged list query. The paged query keeps the list query's exact 20-column
 * SELECT shape, so this widening is the same mechanical expansion as the two overloads above;
 * the fold (`toImportReviewRow` / `foldDuplicateStatus`) is untouched and shared.
 */
private fun com.unifiedledger.data.db.ImportReviewRowsForLedgerPage.toColumns(): ImportReviewRowColumns =
    ImportReviewRowColumns(
        candidate_id,
        candidate_kind,
        confidence,
        input_ref,
        amount_minor,
        currency_code,
        currency_precision,
        occurred_at,
        direction_token,
        status_token,
        funding_state,
        completeness,
        content_hash,
        candidate_status,
        requires_confirmation,
        duplicate_candidate_id,
        duplicate_latest_status,
        payment_profile_variant,
        payment_profile_asset_leg_kind_token,
        payment_profile_credit_leg_kind_token,
    )

private fun ImportReviewRowColumns.toImportReviewRow(
    groupedRows: List<ImportReviewRowColumns>,
): ImportReviewRow {
    val latestDuplicateStatuses =
        groupedRows
            .filter { it.duplicate_candidate_id != null }
            .mapNotNull { it.duplicate_latest_status }
    return ImportReviewRow(
        candidateId = ImportCandidateId(candidate_id),
        candidateKind = candidate_kind,
        sourceInputRef = input_ref,
        amountMinor = amount_minor,
        currencyCode = currency_code,
        currencyPrecision = currency_precision?.toInt(),
        occurredAt = occurred_at,
        directionToken = direction_token,
        statusToken = status_token,
        fundingState = ImportFundingState.valueOf(funding_state),
        completeness =
            when (completeness) {
                "valid_complete" -> ImportCompleteness.VALID_COMPLETE
                "valid_incomplete" -> ImportCompleteness.VALID_INCOMPLETE
                else -> throw IllegalStateException("unknown import completeness token: $completeness")
            },
        contentHash = content_hash,
        candidateStatus =
            requireNotNull(candidate_status) {
                "import candidate $candidate_id has no status history row"
            },
        requiresConfirmation = requires_confirmation,
        confidence = confidence,
        duplicateStatus = foldDuplicateStatus(latestDuplicateStatuses),
        paymentProfileVariant = payment_profile_variant,
        paymentProfileAssetLegKindToken = payment_profile_asset_leg_kind_token,
        paymentProfileCreditLegKindToken = payment_profile_credit_leg_kind_token,
    )
}

private fun foldDuplicateStatus(tokens: List<String>): ImportDuplicateStatus? =
    tokens
        .map { ImportDuplicateStatus.valueOf(it) }
        .minByOrNull { it.foldPriority() }

private fun ImportDuplicateStatus.foldPriority(): Int =
    when (this) {
        ImportDuplicateStatus.CONFIRMED_DUPLICATE -> 0
        ImportDuplicateStatus.DEFERRED -> 1
        ImportDuplicateStatus.CONFIRMED_DISTINCT -> 2
        ImportDuplicateStatus.DISMISSED_LOOKALIKE -> 3
        ImportDuplicateStatus.REJECTED -> 4
    }
