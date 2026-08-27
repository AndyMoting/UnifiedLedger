package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.P408CorrectLinkRequest
import com.unifiedledger.application.P408CorrectionCommitPort
import com.unifiedledger.application.P408CorrectionResultState
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408MaterializationRequest
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408ReconciliationReceipt
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.P408SuccessorLinkFacts
import com.unifiedledger.data.SqlDelightEvidenceProjectionStore.EnsureReadyResult
import com.unifiedledger.data.db.LedgerDatabase

/**
 * D-113 correction/successor invalidation owner (approved spec sections 4-9).
 * The only product writer for the `invalidate_link` request family: inside one
 * transaction it appends the invalidation event to the predecessor link,
 * optionally creates the successor link re-proving the same evidence against the
 * corrected posting (with the controlled projection supersede when the
 * authority must be re-expressed), and advances the affected posting
 * reconciliation to CHECKED / MISSING / DIFFERENCE. It never mutates or deletes
 * the predecessor; financial balances, transaction versions and report
 * financial dimensions never change.
 */
class SqlDelightCorrectionStore private constructor(
    private val database: LedgerDatabase,
) : P408CorrectionCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    /** Projection authority used by the re-expression gate; shares the database. */
    private val projections = SqlDelightEvidenceProjectionStore.createShared(database)

    override fun correct(request: P408CorrectLinkRequest): P408ReconciliationResult {
        val fingerprint = request.fingerprint()
        return try {
            rollbackP408 { database.transactionWithResult {
                database.ledgerQueries.claimP408ReconciliationRequest(
                    request.ledgerId,
                    request.requestId,
                    "invalidate_link",
                    fingerprint,
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveReplay(request, fingerprint)
                }

                // (1) Predecessor must exist, belong to the evidence and be the
                // current ACTIVE link (spec V-A/V-F).
                val predecessor = database.ledgerQueries
                    .selectP408CorrectionPredecessor(request.ledgerId, request.previousLinkId)
                    .executeAsOneOrNull()
                    ?: abortP408(P408ReconciliationResult.Rejected(CODE_INVALIDATE_LINK_NOT_ACTIVE))
                if (predecessor.evidence_id != request.evidenceId || predecessor.latest_state != "active") {
                    abortP408(P408ReconciliationResult.Rejected(CODE_INVALIDATE_LINK_NOT_ACTIVE))
                }

                // (2) CHECKED: projection authority (with controlled re-expression)
                // then the mirror confirmLink authority checks on the successor.
                if (request.resultState == P408CorrectionResultState.CHECKED) {
                    val successor = requireNotNull(request.successor)
                    // Raw presence/unresolved gates precede the projection authority,
                    // mirroring the confirmLink ordering (QUAL-004): half-seeded
                    // import rows keep the D-103-era typed outcomes instead of leaking
                    // a projection-family code.
                    val sourceFacts = database.ledgerQueries
                        .selectP408EvidenceSourceFacts(request.ledgerId, request.evidenceId)
                        .executeAsOneOrNull()
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_EVIDENCE_NOT_FOUND"))
                    val sourceOccurredAt = sourceFacts.occurred_at
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    sourceFacts.amount_minor
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    sourceFacts.currency_code
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    sourceFacts.currency_precision
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    sourceFacts.direction_token
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))

                    val projection = when (val ensured = projections.ensureCurrentForCorrection(
                        P408MaterializationRequest(
                            ledgerId = request.ledgerId,
                            requestId = request.requestId,
                            evidenceId = request.evidenceId,
                            targetAccountId = successor.accountId,
                            targetCurrencyCode = successor.currencyCode,
                            targetCurrencyPrecision = successor.currencyPrecision,
                            materializedAt = request.confirmedAt,
                        ),
                    )) {
                        is EnsureReadyResult.Ready -> ensured.projection
                        is EnsureReadyResult.NotReady ->
                            abortP408(P408ReconciliationResult.Rejected(ensured.code))
                    }

                    // Raw echo drift (D-111 immutable-source discipline).
                    if (sourceFacts.content_hash != projection.sourceHash ||
                        sourceFacts.amount_minor != projection.rawAmountMinor ||
                        sourceFacts.currency_precision != projection.rawCurrencyPrecision.toLong() ||
                        sourceFacts.direction_token != projection.directionToken
                    ) {
                        abortP408(P408ReconciliationResult.Rejected("P408_PROJECTION_SOURCE_DRIFT"))
                    }
                    // Request <-> authority exact equality (mirrors the confirmLink
                    // echo gate, SqlDelightP408ReconciliationStore.kt:128-145).
                    if (request.projectionId != projection.projectionId ||
                        request.projectionRuleId != projection.ruleId ||
                        requireNotNull(request.projectionRuleVersion).toLong() != projection.ruleVersion.toLong() ||
                        request.normalizedAmountMinor != projection.normalizedAmountMinor ||
                        request.rawAmountMinor != projection.rawAmountMinor ||
                        request.rawCurrencyPrecision != projection.rawCurrencyPrecision.toInt() ||
                        successor.amountMinor != projection.normalizedAmountMinor ||
                        successor.currencyCode != projection.currencyCode ||
                        successor.currencyPrecision != projection.currencyPrecision.toInt() ||
                        successor.direction != projection.directionToken ||
                        successor.accountId != projection.targetAccountId ||
                        sourceOccurredAt != successor.sourceOccurredAt
                    ) {
                        abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_IDENTITY_CONFLICT"))
                    }

                    val posting = database.ledgerQueries
                        .selectP408PostingIntegrity(request.ledgerId, successor.postingId)
                        .executeAsOneOrNull()
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_POSTING_NOT_ELIGIBLE"))
                    if (posting.ledger_id != request.ledgerId ||
                        posting.amount_minor != P408Computation.signedAmount(successor.amountMinor, successor.direction) ||
                        posting.currency_code != successor.currencyCode ||
                        posting.currency_precision != successor.currencyPrecision.toLong() ||
                        posting.account_id != successor.accountId
                    ) {
                        abortP408(P408ReconciliationResult.Rejected("P408_POSTING_FACT_MISMATCH"))
                    }
                    if (successor.transactionId != posting.transaction_id) {
                        abortP408(P408ReconciliationResult.Rejected("P408_TRANSACTION_ID_MISMATCH"))
                    }
                    if (!responsibilityMatchesPostingSide(successor, posting.amount_minor < 0L)) {
                        abortP408(P408ReconciliationResult.Rejected("P408_RESPONSIBILITY_POSTING_MISMATCH"))
                    }
                    val actualDistance = P408Computation.naturalDayDistance(successor.sourceOccurredAt, posting.occurred_at)
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_POSTING_TIME_UNRESOLVED"))
                    if (actualDistance != successor.naturalDayDistance || actualDistance > successor.windowDays) {
                        abortP408(P408ReconciliationResult.Rejected("P408_POSTING_TIME_WINDOW_MISMATCH"))
                    }
                    if (request.affectedPostingId != successor.postingId) {
                        abortP408(P408ReconciliationResult.Rejected(CODE_AFFECTED_POSTING_MISMATCH))
                    }
                    val responsibilityLinks = database.ledgerQueries
                        .selectP408ActiveLinksForPostingResponsibility(
                            request.ledgerId,
                            successor.postingId,
                            successor.responsibility.storageValue,
                        ).executeAsList()
                    // The predecessor link is being invalidated inside this same
                    // transaction, so it is exempted from the responsibility-side
                    // exclusivity check (spec §9 timing note / SPEC-008).
                    if (responsibilityLinks.any { it.link_id != request.previousLinkId }) {
                        abortP408(P408ReconciliationResult.Rejected("P408_POSTING_RESPONSIBILITY_ALREADY_LINKED"))
                    }
                } else {
                    // Invalidation-only: the affected posting must resolve as a
                    // current eligible ACCOUNT_TRANSFER posting (spec V-C).
                    database.ledgerQueries
                        .selectP408PostingIntegrity(request.ledgerId, request.affectedPostingId)
                        .executeAsOneOrNull()
                        ?: abortP408(P408ReconciliationResult.Rejected(CODE_AFFECTED_POSTING_MISMATCH))
                }

                // (3) Append the invalidation event on the predecessor link.
                database.ledgerQueries.insertP408EvidenceLinkHistory(
                    ledger_id = request.ledgerId,
                    link_id = request.previousLinkId,
                    sequence = predecessor.latest_sequence + 1L,
                    state = "invalidated",
                    reason = request.reason.storageValue,
                    request_id = request.requestId,
                    occurred_at = request.confirmedAt,
                )

                // (4) Successor link + birth history (only when CHECKED; born
                // active/confirmed per spec V-B/SPEC-004).
                val successorLinkId = request.successorLinkId
                if (request.resultState == P408CorrectionResultState.CHECKED) {
                    val successor = requireNotNull(request.successor)
                    database.ledgerQueries.insertP408EvidenceLink(
                        ledger_id = request.ledgerId,
                        link_id = requireNotNull(successorLinkId),
                        evidence_id = request.evidenceId,
                        posting_id = successor.postingId,
                        transaction_id = successor.transactionId,
                        responsibility = successor.responsibility.storageValue,
                        basis_version = 2L,
                        match_basis = successor.matchBasis.toSortedSet().joinToString(","),
                        candidate_id = successor.candidateId,
                        request_id = request.requestId,
                        created_at = requireNotNull(request.successorCreatedAt),
                    )
                    database.ledgerQueries.insertP408EvidenceLinkHistory(
                        ledger_id = request.ledgerId,
                        link_id = requireNotNull(successorLinkId),
                        sequence = 1L,
                        state = "active",
                        reason = "confirmed",
                        request_id = request.requestId,
                        occurred_at = request.confirmedAt,
                    )
                }

                // (5) Affected posting reconciliation advance (spec V-D-A: every
                // advance is history-paired and sequence-increasing, satisfying the
                // posting_reconciliation_update_guard).
                database.ledgerQueries.insertP408PostingReconciliation(
                    ledger_id = request.ledgerId,
                    reconciliation_id = requireNotNull(request.reconciliationId),
                    posting_id = request.affectedPostingId,
                )
                val reconciliation = database.ledgerQueries
                    .selectP408PostingReconciliation(request.ledgerId, request.affectedPostingId)
                    .executeAsOneOrNull()
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_RECONCILIATION_MISSING"))
                if (request.reconciliationId != reconciliation.reconciliation_id) {
                    abortP408(P408ReconciliationResult.Rejected("P408_RECONCILIATION_ID_MISMATCH"))
                }
                if (database.ledgerQueries
                        .selectP408PostingReconciliationHistory(
                            request.ledgerId,
                            reconciliation.reconciliation_id,
                        ).executeAsList().isEmpty()
                ) {
                    // confirmLink-conditional seed so the sequence machinery never
                    // observes a missing sequence=1 (spec V-D-A(i)).
                    database.ledgerQueries.insertP408PostingReconciliationHistory(
                        ledger_id = request.ledgerId,
                        reconciliation_id = reconciliation.reconciliation_id,
                        sequence = 1L,
                        status = "PENDING",
                        evidence_link_id = null,
                        request_id = request.requestId,
                        occurred_at = request.confirmedAt,
                    )
                }
                val nextSequence = reconciliation.latest_sequence + 1L
                database.ledgerQueries.insertP408PostingReconciliationHistory(
                    ledger_id = request.ledgerId,
                    reconciliation_id = reconciliation.reconciliation_id,
                    sequence = nextSequence,
                    status = request.resultState.storageValue,
                    evidence_link_id =
                        if (request.resultState == P408CorrectionResultState.CHECKED) successorLinkId else null,
                    request_id = request.requestId,
                    occurred_at = request.confirmedAt,
                )
                database.ledgerQueries.updateP408PostingReconciliation(
                    status = request.resultState.storageValue,
                    latest_sequence = nextSequence,
                    ledger_id = request.ledgerId,
                    reconciliation_id = reconciliation.reconciliation_id,
                )

                // (6) Correction snapshot (spec Appendix B).
                insertCorrectionSnapshot(request, successorLinkId)

                // (7) Request outcome + receipt.
                database.ledgerQueries.updateP408ReconciliationRequest(
                    outcome = "ACCEPTED",
                    reason_code = null,
                    ledger_id = request.ledgerId,
                    request_id = request.requestId,
                )
                database.ledgerQueries.insertP408ReconciliationReceipt(
                    ledger_id = request.ledgerId,
                    request_id = request.requestId,
                    outcome = "ACCEPTED",
                    link_id = successorLinkId,
                    reconciliation_id = reconciliation.reconciliation_id,
                    history_sequence = nextSequence,
                )
                P408ReconciliationResult.Accepted(
                    P408ReconciliationReceipt(
                        requestId = request.requestId,
                        outcome = "ACCEPTED",
                        linkId = successorLinkId,
                        reconciliationId = reconciliation.reconciliation_id,
                        historySequence = nextSequence,
                    ),
                )
            } }
        } catch (failure: Throwable) {
            when {
                isCorrectionProjectionConflict(failure) ->
                    P408ReconciliationResult.Rejected(CODE_PROJECTION_CONFLICT)
                isSqliteConstraintFailure(failure) ->
                    P408ReconciliationResult.Rejected("P408_RECONCILIATION_CONSTRAINT_VIOLATION")
                else -> throw failure
            }
        }
    }

    private fun insertCorrectionSnapshot(request: P408CorrectLinkRequest, successorLinkId: String?) {
        val successor = request.successor
        database.ledgerQueries.insertP408CorrectionSnapshot(
            ledger_id = request.ledgerId,
            request_id = request.requestId,
            evidence_id = request.evidenceId,
            previous_link_id = request.previousLinkId,
            reason = request.reason.storageValue,
            affected_posting_id = request.affectedPostingId,
            result_state = request.resultState.storageValue,
            successor_link_id = successorLinkId,
            successor_posting_id = successor?.postingId,
            successor_transaction_id = successor?.transactionId,
            successor_responsibility = successor?.responsibility?.storageValue,
            successor_candidate_id = successor?.candidateId,
            successor_match_basis = successor?.matchBasis?.toSortedSet()?.joinToString(","),
            successor_window_days = successor?.windowDays?.toLong(),
            successor_natural_day_distance = successor?.naturalDayDistance?.toLong(),
            successor_source_occurred_at = successor?.sourceOccurredAt,
            successor_confirmed_at =
                if (request.resultState == P408CorrectionResultState.CHECKED) request.confirmedAt else null,
            successor_created_at = request.successorCreatedAt,
            projection_id = request.projectionId,
            projection_rule_id = request.projectionRuleId,
            projection_rule_version = request.projectionRuleVersion?.toLong(),
            normalized_amount_minor = request.normalizedAmountMinor,
            raw_amount_minor = request.rawAmountMinor,
            raw_currency_precision = request.rawCurrencyPrecision?.toLong(),
            basis_version = 2L,
        )
    }

    private fun resolveReplay(
        request: P408CorrectLinkRequest,
        fingerprint: String,
    ): P408ReconciliationResult {
        val stored = database.ledgerQueries
            .selectP408ReconciliationRequest(request.ledgerId, request.requestId)
            .executeAsOneOrNull()
            ?: abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_NOT_FOUND"))
        if (stored.input_fingerprint != fingerprint) {
            abortP408(P408ReconciliationResult.Rejected(CODE_SNAPSHOT_MISMATCH))
        }
        val receipt = database.ledgerQueries
            .selectP408ReconciliationReceipt(request.ledgerId, request.requestId)
            .executeAsOneOrNull()
            ?: abortP408(P408ReconciliationResult.Rejected("P408_RECEIPT_NOT_FOUND"))
        return P408ReconciliationResult.NoChange(
            P408ReconciliationReceipt(
                requestId = receipt.request_id,
                outcome = receipt.outcome,
                linkId = receipt.link_id,
                reconciliationId = receipt.reconciliation_id,
                historySequence = receipt.history_sequence,
            ),
        )
    }

    private fun responsibilityMatchesPostingSide(
        successor: P408SuccessorLinkFacts,
        postingNegative: Boolean,
    ): Boolean =
        (successor.responsibility == P408EvidenceResponsibility.REAL_ACCOUNT_POSTING &&
            successor.direction == "out" && postingNegative) ||
            (successor.responsibility == P408EvidenceResponsibility.DESTINATION_ASSET_POSTING &&
                successor.direction == "in" && !postingNegative)

    /** Frozen D-113 correction-family codes (spec V-F / SPEC-006): inline literals
     * are banned so the string values stay pinned in one place. */
    companion object {
        const val CODE_INVALIDATE_LINK_NOT_ACTIVE = "P408_INVALIDATE_LINK_NOT_ACTIVE"
        // Shape-contiguity guard lives in P408CorrectLinkRequest (constructor-level
        // require); the code is registered for the family but has no store trigger
        // path (same precedent as P408_PROJECTION_TARGET_ACCOUNT_MISSING).
        const val CODE_RESULT_INVALID = "P408_CORRECTION_RESULT_INVALID"
        const val CODE_AFFECTED_POSTING_MISMATCH = "P408_CORRECTION_AFFECTED_POSTING_MISMATCH"
        const val CODE_PROJECTION_CONFLICT = "P408_CORRECTION_PROJECTION_CONFLICT"
        const val CODE_SNAPSHOT_MISMATCH = "P408_CORRECTION_SNAPSHOT_MISMATCH"
    }
}

/**
 * Concurrent-loser projection detection: the controlled-supersede guard
 * RAISE(ABORT, 'cannot update evidence projection') and the partial unique index
 * are the DB backstop when two genuinely different corrections race on the same
 * evidence (spec V-F / SPEC-005).
 */
private fun isCorrectionProjectionConflict(failure: Throwable): Boolean {
    var current: Throwable? = failure
    while (current != null) {
        val message = current.message ?: ""
        if (message.contains("cannot update evidence projection") ||
            message.contains("evidence_projection_current_by_evidence") ||
            message.contains("evidence_projection.projection_id") ||
            message.contains("UNIQUE constraint failed: evidence_projection")
        ) {
            return true
        }
        current = current.cause
    }
    return false
}