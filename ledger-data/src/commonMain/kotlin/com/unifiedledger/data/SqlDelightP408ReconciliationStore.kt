package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408ReconciliationCommitPort
import com.unifiedledger.application.P408ReconciliationReadPort
import com.unifiedledger.application.P408ReconciliationReceipt
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.P408ReconciliationReportRow
import com.unifiedledger.data.db.LedgerDatabase
import kotlin.math.abs
import kotlin.time.Instant

/**
 * RL-07 confirmation owner. The matcher remains pure; this port is the only
 * product writer for the shared P4-08 link/reconciliation surface.
 */
class SqlDelightP408ReconciliationStore private constructor(
    private val database: LedgerDatabase,
) : P408ReconciliationCommitPort, P408ReconciliationReadPort {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun confirmLink(request: P408ConfirmLinkRequest): P408ReconciliationResult {
        val fingerprint = request.fingerprint()
        return try {
            rollbackP408 { database.transactionWithResult {
                database.ledgerQueries.claimP408ReconciliationRequest(
                    request.ledgerId,
                    request.requestId,
                    "confirm_link",
                    fingerprint,
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveReplay(request, fingerprint)
                }

                val source = database.ledgerQueries
                    .selectP408EvidenceSourceFacts(request.ledgerId, request.evidenceId)
                    .executeAsOneOrNull()
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_EVIDENCE_NOT_FOUND"))
                val sourceAmount = source.amount_minor
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                val sourceCurrency = source.currency_code
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                val sourcePrecision = source.currency_precision
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                val sourceOccurredAt = source.occurred_at
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_UNRESOLVED"))
                if (sourceAmount != request.amountMinor ||
                    sourceCurrency != request.currencyCode ||
                    sourcePrecision != request.currencyPrecision.toLong() ||
                    sourceOccurredAt != request.sourceOccurredAt ||
                    source.direction_token != request.direction
                ) {
                    abortP408(P408ReconciliationResult.Rejected("P408_SOURCE_FACT_MISMATCH"))
                }

                val posting = database.ledgerQueries
                    .selectP408PostingIntegrity(request.ledgerId, request.postingId)
                    .executeAsOneOrNull()
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_POSTING_NOT_ELIGIBLE"))
                if (posting.ledger_id != request.ledgerId ||
                    posting.amount_minor != signedAmount(request.amountMinor, request.direction) ||
                    posting.currency_code != request.currencyCode ||
                    posting.currency_precision != request.currencyPrecision.toLong() ||
                    posting.account_id != request.accountId
                ) {
                    abortP408(P408ReconciliationResult.Rejected("P408_POSTING_FACT_MISMATCH"))
                }
                val actualDistance = naturalDayDistance(request.sourceOccurredAt, posting.occurred_at)
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_POSTING_TIME_UNRESOLVED"))
                if (actualDistance != request.naturalDayDistance || actualDistance > request.windowDays) {
                    abortP408(P408ReconciliationResult.Rejected("P408_POSTING_TIME_WINDOW_MISMATCH"))
                }

                val evidenceLinks = database.ledgerQueries
                    .selectP408ActiveLinksForEvidence(request.ledgerId, request.evidenceId)
                    .executeAsList()
                if (evidenceLinks.isNotEmpty()) {
                    abortP408(P408ReconciliationResult.Rejected("P408_EVIDENCE_ALREADY_LINKED"))
                }
                val responsibilityLinks = database.ledgerQueries
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
                )
                database.ledgerQueries.insertP408EvidenceLink(
                    ledger_id = request.ledgerId,
                    link_id = request.linkId,
                    evidence_id = request.evidenceId,
                    posting_id = request.postingId,
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
                val reconciliation = database.ledgerQueries
                    .selectP408PostingReconciliation(request.ledgerId, request.postingId)
                    .executeAsOneOrNull()
                    ?: abortP408(P408ReconciliationResult.Rejected("P408_RECONCILIATION_MISSING"))
                if (request.reconciliationId != reconciliation.reconciliation_id && reconciliation.status != "PENDING") {
                    abortP408(P408ReconciliationResult.Rejected("P408_RECONCILIATION_ID_MISMATCH"))
                }
                if (database.ledgerQueries
                        .selectP408PostingReconciliationHistory(
                            request.ledgerId,
                            reconciliation.reconciliation_id,
                        ).executeAsList().isEmpty()
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
            } }
        } catch (_: Throwable) {
            P408ReconciliationResult.Rejected("P408_RECONCILIATION_WRITE_FAILED")
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
                    status = grouped.first().status,
                    activeLinkIds = grouped.mapNotNull { it.active_link_id }.sorted(),
                )
            }
            .sortedWith(compareBy<P408ReconciliationReportRow> { it.postingId })
    }

    private fun resolveReplay(
        request: P408ConfirmLinkRequest,
        fingerprint: String,
    ): P408ReconciliationResult {
        val stored = database.ledgerQueries
            .selectP408ReconciliationRequest(request.ledgerId, request.requestId)
            .executeAsOneOrNull()
            ?: abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_NOT_FOUND"))
        if (stored.input_fingerprint != fingerprint) {
            abortP408(P408ReconciliationResult.Rejected("P408_REQUEST_IDENTITY_CONFLICT"))
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

    private fun signedAmount(amountMinor: Long, direction: String): Long? {
        if (amountMinor == Long.MIN_VALUE) return null
        val absolute = abs(amountMinor)
        return when (direction) {
            "out" -> -absolute
            "in" -> absolute
            else -> null
        }
    }

    private fun naturalDayDistance(source: String, posting: String): Int? {
        if (!temporalComparableRaw(source, posting)) return null
        val sourceInstant = runCatching { Instant.parse(source) }.getOrNull() ?: return null
        val postingInstant = runCatching { Instant.parse(posting) }.getOrNull() ?: return null
        val sourceDay = Math.floorDiv(sourceInstant.epochSeconds + 8 * 60 * 60, 24 * 60 * 60)
        val postingDay = Math.floorDiv(postingInstant.epochSeconds + 8 * 60 * 60, 24 * 60 * 60)
        val distance = kotlin.math.abs(sourceDay - postingDay)
        return distance.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun temporalComparableRaw(source: String, posting: String): Boolean {
        val sourceHasOffset = hasExplicitOffset(source)
        val postingHasOffset = hasExplicitOffset(posting)
        if (!sourceHasOffset || !postingHasOffset) return false
        return temporalShape(source) == temporalShape(posting)
    }

    private fun hasExplicitOffset(value: String): Boolean = value.endsWith('Z') ||
        (value.length >= 6 && value[value.length - 6] in setOf('+', '-') && value[value.length - 3] == ':')

    private fun temporalShape(value: String): String = buildString(value.length) {
        value.forEach { append(if (it in '0'..'9') '#' else it) }
    }
}

private class P408TypedRollback(val result: Any) : RuntimeException()

private fun abortP408(result: Any): Nothing = throw P408TypedRollback(result)

private inline fun <T> rollbackP408(block: () -> T): T = try {
    block()
} catch (failure: P408TypedRollback) {
    @Suppress("UNCHECKED_CAST")
    failure.result as T
}
