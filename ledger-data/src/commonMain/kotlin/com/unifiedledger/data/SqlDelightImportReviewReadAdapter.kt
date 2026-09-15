package com.unifiedledger.data

import com.unifiedledger.application.ImportCandidateDetailRow
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicatePossibleExistingSourceFacts
import com.unifiedledger.application.ImportDuplicateReviewRow
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportReviewReadPort
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId

/**
 * P7-04.B import review read adapter (D-146; implementation spec section 4.5, Appendix A).
 *
 * Implements [ImportReviewReadPort] against the single [LedgerDatabase] using the P7-04
 * read-only named queries (`importReviewRowsForLedger`, `importDuplicateReviewsForSource`)
 * plus the existing `selectImportCandidateLatestSequence`. Every query is ledger-filtered;
 * zero DDL, no index change, schema stays v29. The candidate list query returns one row per
 * (candidate, duplicate candidate) pair, so duplicate rows are folded here with the blocking
 * verdict first (spec section 4.5.2). Exceptions propagate to the use-case boundary, which
 * maps them to `Unavailable` (G6: a read failure never degrades to an empty verdict).
 */
class SqlDelightImportReviewReadAdapter(
    private val database: LedgerDatabase,
) : ImportReviewReadPort {
    override fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow> =
        database.ledgerQueries
            .importReviewRowsForLedger(ledgerId.value)
            .executeAsList()
            .groupBy { it.candidate_id }
            .map { (_, groupedRows) -> groupedRows.first().toReviewRow(groupedRows) }

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
        val rows =
            database.ledgerQueries
                .importReviewRowsForLedger(ledgerId.value)
                .executeAsList()
                .filter { it.candidate_id == candidateId.value }
        if (rows.isEmpty()) return null
        // Sequence high-water mark == row count under the contiguous 1-based premise above.
        val statusHistoryCount =
            database.ledgerQueries
                .selectImportCandidateLatestSequence(ledgerId.value, candidateId.value)
                .executeAsOne()
        return ImportCandidateDetailRow(
            row = rows.first().toReviewRow(rows),
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
        val candidateExists =
            database.ledgerQueries
                .importReviewRowsForLedger(ledgerId.value)
                .executeAsList()
                .any { it.candidate_id == candidateId.value }
        if (!candidateExists) return null
        return database.ledgerQueries
            .importDuplicateReviewsForSource(ledgerId.value, candidateId.value)
            .executeAsList()
            .map { row ->
                ImportDuplicateReviewRow(
                    duplicateCandidateId = ImportDuplicateCandidateId(row.candidate_id),
                    kind = row.kind,
                    comparisonFingerprint = row.comparison_fingerprint,
                    comparisonSnapshot = row.comparison_snapshot,
                    latestStatus =
                        ImportDuplicateStatus.valueOf(
                            requireNotNull(row.latest_status) { "duplicate candidate has no status row" },
                        ),
                    reviewDecision = row.review_decision,
                    reviewReasonToken = row.review_reason_token,
                    reviewedAt = row.reviewed_at,
                    possibleExistingSource =
                        row.existing_source_id?.let { existingSourceId ->
                            ImportDuplicatePossibleExistingSourceFacts(
                                sourceId = ImportSourceId(existingSourceId),
                                amountMinor = row.existing_amount_minor,
                                currencyCode = row.existing_currency_code,
                                currencyPrecision = row.existing_currency_precision?.toInt(),
                                occurredAt = row.existing_occurred_at,
                                directionToken = row.existing_direction_token,
                                statusToken = row.existing_status_token,
                            )
                        },
                )
            }
    }
}

/**
 * Folds the (candidate, duplicate candidate) rows of one candidate into a single list row.
 * The latest duplicate disposition is the strongest present verdict, blocking first
 * (spec section 4.5.2): any latest `CONFIRMED_DUPLICATE`, else any `DEFERRED`, else any
 * reviewed retain/dismiss verdict.
 */
private fun com.unifiedledger.data.db.ImportReviewRowsForLedger.toReviewRow(
    groupedRows: List<com.unifiedledger.data.db.ImportReviewRowsForLedger>,
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
