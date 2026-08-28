package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408MaterializationRequest
import com.unifiedledger.application.P408ReconciliationCommitPort
import com.unifiedledger.application.P408ReconciliationReadPort
import com.unifiedledger.application.P408ReconciliationReceipt
import com.unifiedledger.application.P408ReconciliationReportRow
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.data.SqlDelightEvidenceProjectionStore.EnsureReadyResult
import com.unifiedledger.data.db.LedgerDatabase

/**
 * RL-07 confirmation owner. The matcher remains pure; this port is the only
 * product writer for the shared P4-08 link/reconciliation surface.
 */
class SqlDelightP408ReconciliationStore private constructor(
    private val database: LedgerDatabase,
) : P408ReconciliationCommitPort,
    P408ReconciliationReadPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    /** Projection authority used by the READY gate; shares this store's database. */
    private val projections = SqlDelightEvidenceProjectionStore.createShared(database)

    override fun confirmLink(request: P408ConfirmLinkRequest): P408ReconciliationResult {
        if (request.windowDays != P408Matcher.DEFAULT_WINDOW_DAYS) {
            return P408ReconciliationResult.Rejected("P408_WINDOW_DAYS_NOT_APPROVED")
        }
        val fingerprint = request.fingerprint()
        return try {
            rollbackP408 {
                database.transactionWithResult {
                    database.ledgerQueries.claimP408ReconciliationRequest(
                        request.ledgerId,
                        request.requestId,
                        "confirm_link",
                        fingerprint,
                    )
                    if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                        return@transactionWithResult resolveReplay(request, fingerprint)
                    }

                    val evidenceLinks =
                        database.ledgerQueries
                            .selectP408ActiveLinksForEvidence(request.ledgerId, request.evidenceId)
                            .executeAsList()
                    if (evidenceLinks.isNotEmpty()) {
                        abortP408(P408ReconciliationResult.Rejected("P408_EVIDENCE_ALREADY_LINKED"))
                    }

                    // Write-always-v2 gate: existing v1 requests short-circuit into
                    // resolveReplay above (their claim rowcount is 0), so any claim that
                    // reaches this line with basisVersion == 1 is a brand-new v1 shape,
                    // which the approved contract retires (V-4 / 010-R1).
                    if (request.basisVersion != 2) {
                        abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_BASIS_VERSION_RETIRED"))
                    }
                    // v2 payload is contractually total; bind non-null locals once (the
                    // constructor guarantees these for basisVersion == 2).
                    val v2ProjectionId = requireNotNull(request.projectionId)
                    val v2RuleId = requireNotNull(request.projectionRuleId)
                    val v2RuleVersion = requireNotNull(request.projectionRuleVersion)
                    val v2NormalizedAmount = requireNotNull(request.normalizedAmountMinor)
                    val v2RawAmount = requireNotNull(request.rawAmountMinor)
                    val v2RawPrecision = requireNotNull(request.rawCurrencyPrecision)

                    // Raw presence/unresolved gates precede the projection authority so
                    // immutable-source discipline (D-111) still yields the D-103-era typed
                    // outcomes for absent or half-seeded import rows.
                    val sourceFacts =
                        database.ledgerQueries
                            .selectP408EvidenceSourceFacts(request.ledgerId, request.evidenceId)
                            .executeAsOneOrNull()
                            ?: abortP408(P408ReconciliationResult.Rejected("P408_EVIDENCE_NOT_FOUND"))
                    val sourceOccurredAt =
                        sourceFacts.occurred_at
                            ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    // QUAL-004 (dual-review correction): half-seeded import rows keep the
                    // D-103-era typed P408_SOURCE_FACT_UNRESOLVED outcome, so the same
                    // raw-presence/unresolved discipline the projection gate previously
                    // bypassed is restored BEFORE the authority lookup. P408_PROJECTION_ABSENT
                    // therefore stays reserved for the authority layer itself (no source rows
                    // AND no materialization authority — R3/ensureReady level).
                    sourceFacts.amount_minor
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    sourceFacts.currency_code
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    sourceFacts.currency_precision
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                    sourceFacts.direction_token
                        ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))

                    // READY-projection authority gate (spec V-3): identity facts come from
                    // a READY projection; absence lazily materializes inside THIS same
                    // transaction using the explicit target riding on the request, and a
                    // REJECTED/failed authority keeps everything unresolved with zero writes.
                    val projection =
                        when (
                            val ensured =
                                projections.ensureReadyWithinTransaction(
                                    P408MaterializationRequest(
                                        ledgerId = request.ledgerId,
                                        requestId = request.requestId,
                                        evidenceId = request.evidenceId,
                                        targetAccountId = request.accountId,
                                        targetCurrencyCode = request.currencyCode,
                                        targetCurrencyPrecision = request.currencyPrecision,
                                        materializedAt = request.confirmedAt,
                                    ),
                                )
                        ) {
                            is EnsureReadyResult.Ready -> ensured.projection
                            is EnsureReadyResult.NotReady ->
                                abortP408(P408ReconciliationResult.Rejected(ensured.code))
                        }

                    // Raw echo drift check: projection.raw twins and hash must equal the
                    // live source row before anything else is trusted.
                    if (
                        sourceFacts.content_hash != projection.sourceHash ||
                        sourceFacts.amount_minor != projection.rawAmountMinor ||
                        sourceFacts.currency_precision != projection.rawCurrencyPrecision.toLong() ||
                        sourceFacts.direction_token != projection.directionToken
                    ) {
                        abortP408(P408ReconciliationResult.Rejected("P408_PROJECTION_SOURCE_DRIFT"))
                    }

                    // Request ↔ authority exact equality: normalized domain identity plus
                    // the raw twins and rule provenance carried by every v2 request.
                    if (
                        v2ProjectionId != projection.projectionId ||
                        v2RuleId != projection.ruleId ||
                        v2RuleVersion.toLong() != projection.ruleVersion.toLong() ||
                        v2NormalizedAmount != projection.normalizedAmountMinor ||
                        v2RawAmount != projection.rawAmountMinor ||
                        v2RawPrecision != projection.rawCurrencyPrecision.toInt() ||
                        request.amountMinor != projection.normalizedAmountMinor ||
                        request.currencyCode != projection.currencyCode ||
                        request.currencyPrecision != projection.currencyPrecision.toInt() ||
                        request.direction != projection.directionToken ||
                        request.accountId != projection.targetAccountId ||
                        sourceOccurredAt != request.sourceOccurredAt
                    ) {
                        abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_IDENTITY_CONFLICT"))
                    }

                    val posting =
                        database.ledgerQueries
                            .selectP408PostingIntegrity(request.ledgerId, request.postingId)
                            .executeAsOneOrNull()
                            ?: abortP408(P408ReconciliationResult.Rejected("P408_POSTING_NOT_ELIGIBLE"))
                    if (posting.ledger_id != request.ledgerId ||
                        posting.amount_minor != P408Computation.signedAmount(request.amountMinor, request.direction) ||
                        posting.currency_code != request.currencyCode ||
                        posting.currency_precision != request.currencyPrecision.toLong() ||
                        posting.account_id != request.accountId
                    ) {
                        abortP408(P408ReconciliationResult.Rejected("P408_POSTING_FACT_MISMATCH"))
                    }
                    if (request.transactionId != posting.transaction_id) {
                        abortP408(P408ReconciliationResult.Rejected("P408_TRANSACTION_ID_MISMATCH"))
                    }
                    val responsibilityMatchesPostingSide =
                        (
                            request.responsibility == P408EvidenceResponsibility.REAL_ACCOUNT_POSTING &&
                                request.direction == "out" &&
                                posting.amount_minor < 0
                        ) ||
                            (
                                request.responsibility == P408EvidenceResponsibility.DESTINATION_ASSET_POSTING &&
                                    request.direction == "in" &&
                                    posting.amount_minor > 0
                            )
                    if (!responsibilityMatchesPostingSide) {
                        abortP408(P408ReconciliationResult.Rejected("P408_RESPONSIBILITY_POSTING_MISMATCH"))
                    }
                    val actualDistance =
                        P408Computation.naturalDayDistance(request.sourceOccurredAt, posting.occurred_at)
                            ?: abortP408(P408ReconciliationResult.Rejected("P408_POSTING_TIME_UNRESOLVED"))
                    if (actualDistance != request.naturalDayDistance || actualDistance > request.windowDays) {
                        abortP408(P408ReconciliationResult.Rejected("P408_POSTING_TIME_WINDOW_MISMATCH"))
                    }

                    val responsibilityLinks =
                        database.ledgerQueries
                            .selectP408ActiveLinksForPostingResponsibility(
                                request.ledgerId,
                                request.postingId,
                                request.responsibility.storageValue,
                            ).executeAsList()
                    if (responsibilityLinks.isNotEmpty()) {
                        abortP408(P408ReconciliationResult.Rejected("P408_POSTING_RESPONSIBILITY_ALREADY_LINKED"))
                    }

                    database.ledgerQueries.insertP408ReconciliationSnapshot(
                        ledger_id = request.ledgerId,
                        request_id = request.requestId,
                        evidence_id = request.evidenceId,
                        candidate_id = request.candidateId,
                        posting_id = request.postingId,
                        transaction_id = request.transactionId,
                        amount_minor = request.amountMinor,
                        currency_code = request.currencyCode,
                        currency_precision = request.currencyPrecision.toLong(),
                        direction = request.direction,
                        account_id = request.accountId,
                        responsibility = request.responsibility.storageValue,
                        basis_version = request.basisVersion.toLong(),
                        match_basis = request.matchBasis.toSortedSet().joinToString(","),
                        window_days = request.windowDays.toLong(),
                        natural_day_distance = request.naturalDayDistance.toLong(),
                        source_occurred_at = request.sourceOccurredAt,
                        confirmed_at = request.confirmedAt,
                        human_decision = "confirm_match",
                        projection_id = v2ProjectionId,
                        projection_rule_id = v2RuleId,
                        projection_rule_version = v2RuleVersion.toLong(),
                        normalized_amount_minor = v2NormalizedAmount,
                        raw_amount_minor = v2RawAmount,
                        raw_currency_precision = v2RawPrecision.toLong(),
                    )
                    database.ledgerQueries.insertP408EvidenceLink(
                        ledger_id = request.ledgerId,
                        link_id = request.linkId,
                        evidence_id = request.evidenceId,
                        posting_id = request.postingId,
                        transaction_id = request.transactionId,
                        responsibility = request.responsibility.storageValue,
                        basis_version = request.basisVersion.toLong(),
                        match_basis = request.matchBasis.toSortedSet().joinToString(","),
                        candidate_id = request.candidateId,
                        request_id = request.requestId,
                        created_at = request.createdAt,
                    )
                    database.ledgerQueries.insertP408EvidenceLinkHistory(
                        ledger_id = request.ledgerId,
                        link_id = request.linkId,
                        sequence = 1,
                        state = "active",
                        reason = "confirmed",
                        request_id = request.requestId,
                        occurred_at = request.confirmedAt,
                    )

                    database.ledgerQueries.insertP408PostingReconciliation(
                        ledger_id = request.ledgerId,
                        reconciliation_id = request.reconciliationId,
                        posting_id = request.postingId,
                    )
                    val reconciliation =
                        database.ledgerQueries
                            .selectP408PostingReconciliation(request.ledgerId, request.postingId)
                            .executeAsOneOrNull()
                            ?: abortP408(P408ReconciliationResult.Rejected("P408_RECONCILIATION_MISSING"))
                    if (request.reconciliationId != reconciliation.reconciliation_id) {
                        abortP408(P408ReconciliationResult.Rejected("P408_RECONCILIATION_ID_MISMATCH"))
                    }
                    if (database.ledgerQueries
                            .selectP408PostingReconciliationHistory(
                                request.ledgerId,
                                reconciliation.reconciliation_id,
                            ).executeAsList()
                            .isEmpty()
                    ) {
                        database.ledgerQueries.insertP408PostingReconciliationHistory(
                            ledger_id = request.ledgerId,
                            reconciliation_id = reconciliation.reconciliation_id,
                            sequence = 1,
                            status = "PENDING",
                            evidence_link_id = null,
                            request_id = request.requestId,
                            occurred_at = request.sourceOccurredAt,
                        )
                    }
                    val nextSequence = reconciliation.latest_sequence + 1L
                    database.ledgerQueries.insertP408PostingReconciliationHistory(
                        ledger_id = request.ledgerId,
                        reconciliation_id = reconciliation.reconciliation_id,
                        sequence = nextSequence,
                        status = "CHECKED",
                        evidence_link_id = request.linkId,
                        request_id = request.requestId,
                        occurred_at = request.confirmedAt,
                    )
                    database.ledgerQueries.updateP408PostingReconciliation(
                        status = "CHECKED",
                        latest_sequence = nextSequence,
                        ledger_id = request.ledgerId,
                        reconciliation_id = reconciliation.reconciliation_id,
                    )
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
                        link_id = request.linkId,
                        reconciliation_id = reconciliation.reconciliation_id,
                        history_sequence = nextSequence,
                    )
                    P408ReconciliationResult.Accepted(
                        P408ReconciliationReceipt(
                            requestId = request.requestId,
                            outcome = "ACCEPTED",
                            linkId = request.linkId,
                            reconciliationId = reconciliation.reconciliation_id,
                            historySequence = nextSequence,
                        ),
                    )
                }
            }
        } catch (failure: Throwable) {
            if (isSqliteConstraintFailure(failure)) {
                P408ReconciliationResult.Rejected("P408_RECONCILIATION_CONSTRAINT_VIOLATION")
            } else {
                throw failure
            }
        }
    }

    override fun readReconciliationReport(ledgerId: String): List<P408ReconciliationReportRow> {
        val rows = database.ledgerQueries.selectP408ReconciliationReport(ledgerId).executeAsList()
        return rows
            .groupBy { Triple(it.posting_id, it.transaction_id, it.account_id) }
            .map { (key, grouped) ->
                P408ReconciliationReportRow(
                    postingId = key.first,
                    transactionId = key.second,
                    accountId = key.third,
                    status = P408ReconciliationStatus.fromStorage(grouped.first().status),
                    activeLinkIds = grouped.mapNotNull { it.active_link_id }.sorted(),
                )
            }.sortedWith(compareBy<P408ReconciliationReportRow> { it.postingId })
    }

    private fun resolveReplay(
        request: P408ConfirmLinkRequest,
        fingerprint: String,
    ): P408ReconciliationResult {
        val stored =
            database.ledgerQueries
                .selectP408ReconciliationRequest(request.ledgerId, request.requestId)
                .executeAsOneOrNull()
                ?: abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_NOT_FOUND"))
        if (stored.input_fingerprint != fingerprint) {
            abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_IDENTITY_CONFLICT"))
        }
        val receipt =
            database.ledgerQueries
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
}

internal class P408TypedRollback(
    val result: Any,
) : RuntimeException()

internal fun abortP408(result: Any): Nothing = throw P408TypedRollback(result)

internal inline fun <T> rollbackP408(block: () -> T): T =
    try {
        block()
    } catch (failure: P408TypedRollback) {
        @Suppress("UNCHECKED_CAST")
        failure.result as T
    }

internal fun isSqliteConstraintFailure(failure: Throwable): Boolean {
    var current: Throwable? = failure
    while (current != null) {
        val message = current.message ?: ""
        if (
            message.contains("constraint failed") ||
            message.contains("cannot update") ||
            message.contains("cannot delete") ||
            message.contains("evidence link history sequence") ||
            message.contains("evidence link history transition") ||
            message.contains("posting reconciliation sequence") ||
            message.contains("posting reconciliation evidence link target") ||
            message.contains("posting reconciliation current projection")
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
