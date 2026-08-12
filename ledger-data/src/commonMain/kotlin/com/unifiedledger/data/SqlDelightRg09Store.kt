package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.Rg09Action
import com.unifiedledger.application.Rg09ExecutionResult
import com.unifiedledger.application.Rg09Operation
import com.unifiedledger.application.Rg09ReturnedId
import com.unifiedledger.application.Rg09Runtime
import com.unifiedledger.application.Rg09Snapshot
import com.unifiedledger.application.Rg09StaleDiagnostics
import com.unifiedledger.application.Rg09RejectionReason
import com.unifiedledger.application.Rg09FieldPath
import com.unifiedledger.application.Rg09FormalTransactionRecord
import com.unifiedledger.application.Rg09Candidate
import com.unifiedledger.application.Rg09Observation
import com.unifiedledger.application.Rg09SourceRecord
import com.unifiedledger.application.Rg09Evidence
import com.unifiedledger.application.Rg09EvidenceLink
import com.unifiedledger.application.Rg09Confirmation
import com.unifiedledger.application.Rg09Adjustment
import com.unifiedledger.application.Rg09Allocation
import com.unifiedledger.application.Rg09AuditLink
import com.unifiedledger.application.Rg09AdjustmentHistory
import com.unifiedledger.application.Rg09CandidateId
import com.unifiedledger.application.Rg09ObservationId
import com.unifiedledger.application.Rg09SourceRecordId
import com.unifiedledger.application.Rg09EvidenceId
import com.unifiedledger.application.Rg09EvidenceLinkId
import com.unifiedledger.application.Rg09ConfirmationId
import com.unifiedledger.application.Rg09AdjustmentId
import com.unifiedledger.application.Rg09AllocationId
import com.unifiedledger.application.Rg09AuditLinkId
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.DomainResult
import kotlin.time.Instant

internal enum class Rg09FailurePoint {
    AFTER_CLAIM,
    AFTER_DELTA,
}

internal fun interface Rg09FailureInjector {
    fun failAt(point: Rg09FailurePoint)
}

private val NO_RG09_FAILURE = Rg09FailureInjector { }

/** SQLDelight owner for the approved RG-09 operation boundary. */
class SqlDelightRg09Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val openingTransactions: List<Rg09FormalTransactionRecord>,
    private val failureInjector: Rg09FailureInjector,
) : com.unifiedledger.application.Rg09CommitPort {
    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingTransactions: List<Rg09FormalTransactionRecord> = emptyList(),
    ) : this(database, catalog, openingTransactions, NO_RG09_FAILURE) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingTransactions: List<Rg09FormalTransactionRecord>,
        failureInjector: Rg09FailureInjector,
    ) : this(database, catalog, openingTransactions, failureInjector) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    override fun commit(operation: Rg09Operation): Rg09ExecutionResult = database.transactionWithResult {
        val fingerprint = operationFingerprint(operation)
        database.ledgerQueries.claimRg09Operation(
            operation.ledgerId.value,
            operation.identity.value,
            operation.action.code,
            operationClass(operation),
            fingerprint,
        )
        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
            return@transactionWithResult replay(operation, fingerprint)
        }
        failureInjector.failAt(Rg09FailurePoint.AFTER_CLAIM)

        val before = loadPersistedSnapshot(operation.ledgerId)
        val runtime = Rg09Runtime(catalog, before)
        val result = runtime.commit(operation)
        check(result !is Rg09ExecutionResult.RequestIdentityConflict) {
            "newly claimed RG-09 operation returned an identity conflict"
        }
        if (result is Rg09ExecutionResult.Accepted) {
            persistDelta(operation, before, runtime.snapshot())
        }
        failureInjector.failAt(Rg09FailurePoint.AFTER_DELTA)
        finalizeOperation(operation, fingerprint, result)
        result
    }

    fun snapshot(ledgerId: LedgerId): Rg09Snapshot =
        Rg09Runtime(catalog, loadPersistedSnapshot(ledgerId)).snapshot()

    private fun operationFingerprint(operation: Rg09Operation): String =
        Rg09Runtime(catalog, emptyList()).operationFingerprint(operation)

    private fun operationClass(operation: Rg09Operation): String = when (operation) {
        is Rg09Operation.PreviewTargetBalance -> "preview_target_balance"
        is Rg09Operation.ConfirmBalanceAdjustment -> "confirm_balance_adjustment"
        is Rg09Operation.ConfirmRealTransfer -> "confirm_real_transfer"
        is Rg09Operation.IngestImportedTransfer -> "ingest_imported_transfer"
        is Rg09Operation.ConfirmImportedTransfer -> "confirm_imported_transfer"
        is Rg09Operation.IncompleteImportedTransferConfirmation -> "incomplete_imported_transfer_confirmation"
        is Rg09Operation.ConfirmExplanationAllocation -> "confirm_explanation_allocation"
        is Rg09Operation.LinkRealPostingEvidence -> "link_real_posting_evidence"
        is Rg09Operation.InvalidInput -> "reject_invalid_rg09_input"
    }

    private fun replay(operation: Rg09Operation, fingerprint: String): Rg09ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg09Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg09ExecutionResult.RequestIdentityConflict
        if (
            saved.action != operation.action.code ||
            saved.operation_class != operationClass(operation) ||
            saved.input_fingerprint != fingerprint
        ) {
            return Rg09ExecutionResult.RequestIdentityConflict
        }
        if (saved.outcome == "PENDING") {
            error("persisted RG-09 operation is still pending")
        }
        val returned = database.ledgerQueries
            .selectRg09ReturnedIds(operation.ledgerId.value, operation.identity.value)
            .executeAsList()
            .map(::restoreReturnedId)
        return when (saved.outcome) {
            "ACCEPTED", "NO_CHANGE" -> Rg09ExecutionResult.NoChange(returned)
            "REJECTED" -> Rg09ExecutionResult.Rejected(
                reason = Rg09RejectionReason.values().single { it.code == saved.reason_code },
                fieldPath = Rg09FieldPath.values().single { it.value == saved.field_path },
                diagnostics = restoreDiagnostics(saved),
            )
            else -> error("unknown RG-09 operation outcome ${saved.outcome}")
        }
    }

    private fun restoreReturnedId(row: com.unifiedledger.data.db.SelectRg09ReturnedIds): Rg09ReturnedId = when (row.id_kind) {
        "OBSERVATION" -> Rg09ReturnedId.Observation(Rg09ObservationId(row.id_value))
        "CANDIDATE" -> Rg09ReturnedId.Candidate(Rg09CandidateId(row.id_value))
        "SOURCE" -> Rg09ReturnedId.SourceRecord(Rg09SourceRecordId(row.id_value))
        "EVIDENCE" -> Rg09ReturnedId.Evidence(Rg09EvidenceId(row.id_value))
        "EVIDENCE_LINK" -> Rg09ReturnedId.EvidenceLink(Rg09EvidenceLinkId(row.id_value))
        "CONFIRMATION" -> Rg09ReturnedId.Confirmation(Rg09ConfirmationId(row.id_value))
        "ADJUSTMENT" -> Rg09ReturnedId.Adjustment(Rg09AdjustmentId(row.id_value))
        "ALLOCATION" -> Rg09ReturnedId.Allocation(Rg09AllocationId(row.id_value))
        "AUDIT_LINK" -> Rg09ReturnedId.AuditLink(Rg09AuditLinkId(row.id_value))
        "TRANSACTION" -> Rg09ReturnedId.Transaction(TransactionId(row.id_value))
        "VERSION" -> Rg09ReturnedId.Version(TransactionVersionId(row.id_value))
        else -> error("unknown persisted RG-09 returned id kind ${row.id_kind}")
    }

    private fun restoreDiagnostics(row: com.unifiedledger.data.db.Rg09_operation): Rg09StaleDiagnostics? {
        if (row.preview_fingerprint == null || row.current_fingerprint == null ||
            row.recomputed_replay_minor == null || row.recomputed_delta_minor == null ||
            row.currency_code == null || row.currency_precision == null
        ) return null
        val currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt())
        return Rg09StaleDiagnostics(
            previewLedgerFingerprint = row.preview_fingerprint,
            currentLedgerFingerprint = row.current_fingerprint,
            recomputedReplayAmount = Money.ofMinor(row.recomputed_replay_minor, currency),
            recomputedDelta = Money.ofMinor(row.recomputed_delta_minor, currency),
        )
    }

    private fun finalizeOperation(
        operation: Rg09Operation,
        fingerprint: String,
        result: Rg09ExecutionResult,
    ) {
        val outcome = when (result) {
            is Rg09ExecutionResult.Accepted -> "ACCEPTED"
            is Rg09ExecutionResult.NoChange -> "NO_CHANGE"
            is Rg09ExecutionResult.Rejected -> "REJECTED"
            Rg09ExecutionResult.RequestIdentityConflict -> error("cannot finalize identity conflict")
        }
        val rejected = result as? Rg09ExecutionResult.Rejected
        val diagnostics = rejected?.diagnostics
        val changed = database.ledgerQueries.updateRg09OperationResult(
            outcome,
            rejected?.reason?.code,
            rejected?.fieldPath?.value,
            diagnostics?.previewLedgerFingerprint,
            diagnostics?.currentLedgerFingerprint,
            diagnostics?.recomputedReplayAmount?.minorUnits,
            diagnostics?.recomputedDelta?.minorUnits,
            diagnostics?.recomputedReplayAmount?.currency?.code,
            diagnostics?.recomputedReplayAmount?.currency?.precision?.toLong(),
            operation.ledgerId.value,
            operation.identity.value,
        ).value
        check(changed == 1L) { "RG-09 operation final result did not update" }
        val returned = when (result) {
            is Rg09ExecutionResult.Accepted -> result.returnedIds
            is Rg09ExecutionResult.NoChange -> result.returnedIds
            else -> emptyList()
        }
        returned.forEachIndexed { index, id ->
            val stored = id.stored()
            database.ledgerQueries.insertRg09ReturnedId(
                operation.ledgerId.value,
                operation.identity.value,
                index.toLong(),
                stored.first,
                stored.second,
            )
        }
        check(fingerprint == database.ledgerQueries
            .selectRg09Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOne().input_fingerprint)
    }

    private fun Rg09ReturnedId.stored(): Pair<String, String> = when (this) {
        is Rg09ReturnedId.Observation -> "OBSERVATION" to id.value
        is Rg09ReturnedId.Candidate -> "CANDIDATE" to id.value
        is Rg09ReturnedId.SourceRecord -> "SOURCE" to id.value
        is Rg09ReturnedId.Evidence -> "EVIDENCE" to id.value
        is Rg09ReturnedId.EvidenceLink -> "EVIDENCE_LINK" to id.value
        is Rg09ReturnedId.Confirmation -> "CONFIRMATION" to id.value
        is Rg09ReturnedId.Adjustment -> "ADJUSTMENT" to id.value
        is Rg09ReturnedId.Allocation -> "ALLOCATION" to id.value
        is Rg09ReturnedId.AuditLink -> "AUDIT_LINK" to id.value
        is Rg09ReturnedId.Transaction -> "TRANSACTION" to id.value
        is Rg09ReturnedId.Version -> "VERSION" to id.value
    }

    private fun ensureOpeningTransactions() {
        if (openingTransactions.isEmpty()) return
        database.transaction {
            val existing = database.ledgerQueries
                .selectRg09FormalTransactions(openingTransactions.first().formalTransaction.transaction.ledgerId.value)
                .executeAsList()
                .mapTo(mutableSetOf()) { it.transaction_id }
            openingTransactions.forEach { record ->
                if (record.formalTransaction.transaction.id.value !in existing) {
                    persistFormalRecord(record)
                }
            }
        }
    }

    private fun persistFormalRecord(record: Rg09FormalTransactionRecord) {
        val formal = record.formalTransaction
        formal.postingSets.forEach { postingSet ->
            database.ledgerQueries.insertPostingSet(
                postingSet.id.value,
                formal.transaction.ledgerId.value,
            )
        }
        database.ledgerQueries.insertTransaction(
            formal.transaction.id.value,
            formal.transaction.ledgerId.value,
            formal.transaction.kind.name,
        )
        formal.versions.forEach { version ->
            database.ledgerQueries.insertTransactionVersion(
                version.id.value,
                version.transactionId.value,
                formal.transaction.ledgerId.value,
                version.versionNumber.toLong(),
                version.postingSetId.value,
                version.times.occurredAt.toString(),
                version.times.statisticsAt.toString(),
                version.times.effectiveAt.toString(),
                version.note,
            )
        }
        database.ledgerQueries.insertTransactionCurrentVersion(
            formal.transaction.id.value,
            formal.transaction.ledgerId.value,
            formal.transaction.currentVersionId.value,
        )
        formal.postingSets.forEach { postingSet ->
            postingSet.postings.forEachIndexed { index, posting ->
                database.ledgerQueries.insertPosting(
                    posting.id.value,
                    postingSet.id.value,
                    formal.transaction.ledgerId.value,
                    index.toLong(),
                    posting.accountId.value,
                    posting.amount.minorUnits,
                    posting.amount.currency.code,
                    posting.amount.currency.precision.toLong(),
                )
            }
        }
        // Step 1: the shared metadata table carries the statistics time text
        // (D-091; the three-time collapse makes statistics equal effective).
        // Byte-equality premise: statistics_at_text must hold the exact bytes the
        // former effective_at_text column held, because the read side rehydrates
        // effectiveAtText from it and the D-065 fingerprint projects those bytes.
        // Every construction point folds statisticsAtText = effectiveAtText (and the
        // v19->v20 migration copied effective_at_text into statistics_at_text); any
        // new construction point that does not fold would break the fingerprint bytes.
        database.ledgerQueries.insertFormalTransactionMetadata(
            formal.transaction.ledgerId.value,
            formal.transaction.id.value,
            record.createdAtText ?: record.createdAt.toString(),
            record.statisticsAtText ?: formal.versions.last().times.statisticsAt.toString(),
        )
        // Step 2: the slimmed private table keeps the source link when present.
        record.sourceRecordId?.let { sourceRecordId ->
            database.ledgerQueries.insertRg09FormalTransactionSource(
                formal.transaction.ledgerId.value,
                formal.transaction.id.value,
                sourceRecordId.value,
            )
        }
    }

    private fun loadPersistedSnapshot(ledgerId: LedgerId): Rg09Snapshot {
        val ledger = ledgerId.value
        val q = database.ledgerQueries
        val sources = q.selectRg09AllSources(ledger).executeAsList().map { row ->
            val currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt())
            Rg09SourceRecord(
                id = Rg09SourceRecordId(row.source_id),
                sourceType = row.source_type,
                observedAt = Instant.parse(row.observed_at),
                accountId = AccountId(row.account_id),
                amount = Money.ofMinor(row.amount_minor, currency),
                counterAccountId = row.counter_account_id?.let(::AccountId),
                actualAt = row.actual_at?.let(Instant::parse),
                bookingAt = row.booking_at?.let(Instant::parse),
                immutablePayloadDigest = row.immutable_payload_digest,
                observedAtText = row.observed_at_text,
                actualAtText = row.actual_at_text,
                bookingAtText = row.booking_at_text,
            )
        }
        val observations = q.selectRg09AllObservations(ledger).executeAsList().map { row ->
            val currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt())
            Rg09Observation(
                id = Rg09ObservationId(row.observation_id),
                sourceRecordId = Rg09SourceRecordId(row.source_id),
                accountId = AccountId(row.account_id),
                targetAmount = Money.ofMinor(row.target_amount_minor, currency),
                targetObservedAt = Instant.parse(row.target_observed_at),
                savedAt = Instant.parse(row.saved_at),
                targetObservedAtText = row.target_observed_at_text,
                savedAtText = row.saved_at_text,
            )
        }
        val evidence = q.selectRg09AllEvidence(ledger).executeAsList().map { row ->
            Rg09Evidence(
                id = Rg09EvidenceId(row.evidence_id),
                sourceRecordId = Rg09SourceRecordId(row.source_id),
                evidenceType = row.evidence_type,
                observedAt = Instant.parse(row.observed_at),
                observedAtText = row.observed_at_text,
            )
        }
        val evidenceLinks = q.selectRg09AllEvidenceLinks(ledger).executeAsList().map { row ->
            Rg09EvidenceLink(
                id = Rg09EvidenceLinkId(row.link_id),
                sourceRecordId = Rg09SourceRecordId(row.source_id),
                evidenceId = Rg09EvidenceId(row.evidence_id),
                role = row.role.lowercase(),
                targetId = row.target_id,
                status = row.status.lowercase(),
            )
        }
        val candidates = q.selectRg09AllCandidates(ledger).executeAsList().map { row ->
            val currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt())
            Rg09Candidate(
                id = Rg09CandidateId(row.candidate_id),
                observationId = row.observation_id?.let(::Rg09ObservationId),
                accountId = AccountId(row.account_id),
                replayedAmount = Money.ofMinor(row.replayed_amount_minor, currency),
                targetAmount = Money.ofMinor(row.target_amount_minor, currency),
                delta = Money.ofMinor(row.delta_minor, currency),
                targetObservedAt = Instant.parse(row.target_observed_at),
                ledgerFingerprint = row.ledger_fingerprint,
                status = row.status.lowercase(),
                adjustmentId = row.adjustment_id?.let(::Rg09AdjustmentId),
                confirmationRequestId = row.confirmation_request_id?.let(::RequestId),
                targetObservedAtText = row.target_observed_at_text,
                sourceRecordId = Rg09SourceRecordId(row.source_id),
                candidateType = row.candidate_type,
                confidence = row.confidence,
            )
        }
        val confirmations = q.selectRg09AllConfirmations(ledger).executeAsList().map { row ->
            Rg09Confirmation(
                id = Rg09ConfirmationId(row.confirmation_id),
                requestId = RequestId(row.request_id),
                role = row.role.lowercase(),
                confirmedAt = Instant.parse(row.confirmed_at),
                targetId = row.target_id,
                confirmedAtText = row.confirmed_at_text,
                createdAt = Instant.parse(row.created_at),
                createdAtText = row.created_at_text,
            )
        }
        val adjustmentHistory = q.selectRg09AllAdjustmentHistory(ledger)
            .executeAsList().groupBy { it.adjustment_id }
        val allocationRows = q.selectRg09AllAllocations(ledger).executeAsList()
        val allocationsByAdjustment = allocationRows.groupBy { it.adjustment_id }
        val adjustments = q.selectRg09AllAdjustments(ledger).executeAsList().map { row ->
            val currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt())
            val historyRows = adjustmentHistory[row.adjustment_id].orEmpty().sortedBy { it.history_sequence }
            check(historyRows.isNotEmpty()) { "persisted RG-09 adjustment has no history: ${row.adjustment_id}" }
            val latest = historyRows.last()
            val allocated = allocationsByAdjustment[row.adjustment_id].orEmpty()
                .fold(0L) { total, allocation -> addExact(total, allocation.amount_minor) }
            val remaining = safeSubtract(absMinor(row.original_delta_minor), allocated)
            check(remaining >= 0L) { "persisted RG-09 allocations exceed original delta: ${row.adjustment_id}" }
            check(latest.remaining_amount_minor == remaining) {
                "persisted RG-09 history disagrees with original delta and allocations: ${row.adjustment_id}"
            }
            val explained = safeSubtract(absMinor(row.original_delta_minor), remaining)
            val derivedState = when {
                explained == 0L -> "OPEN"
                remaining == 0L -> "FULLY_EXPLAINED"
                else -> "PARTIALLY_EXPLAINED"
            }
            check(latest.state == derivedState) {
                "persisted RG-09 history state disagrees with original delta and allocations: ${row.adjustment_id}"
            }
            Rg09Adjustment(
                id = Rg09AdjustmentId(row.adjustment_id),
                transactionId = TransactionId(row.transaction_id),
                observationId = Rg09ObservationId(row.observation_id),
                targetAccountId = AccountId(row.target_account_id),
                equityAccountId = AccountId(row.equity_account_id),
                currency = currency,
                targetObservedAt = Instant.parse(row.target_observed_at),
                replayedAmountAtConfirmation = Money.ofMinor(row.replayed_amount_minor, currency),
                targetAmount = Money.ofMinor(row.target_amount_minor, currency),
                originalDelta = Money.ofMinor(row.original_delta_minor, currency),
                explainedAmount = Money.ofMinor(explained, currency),
                remainingAmount = Money.ofMinor(remaining, currency),
                state = derivedState.lowercase(),
                history = historyRows.map { historyRow ->
                    Rg09AdjustmentHistory(
                        id = historyRow.history_id,
                        state = historyRow.state.lowercase(),
                        occurredAt = Instant.parse(historyRow.occurred_at),
                        allocationId = historyRow.allocation_id?.let(::Rg09AllocationId),
                        remainingAmount = Money.ofMinor(historyRow.remaining_amount_minor, currency),
                        occurredAtText = historyRow.occurred_at_text,
                        createdAt = Instant.parse(historyRow.created_at),
                        createdAtText = historyRow.created_at_text,
                    )
                },
                targetObservedAtText = row.target_observed_at_text,
            )
        }
        val allocations = allocationRows.map { row ->
            val currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt())
            Rg09Allocation(
                id = Rg09AllocationId(row.allocation_id),
                adjustmentId = Rg09AdjustmentId(row.adjustment_id),
                targetAccountId = AccountId(row.target_account_id),
                amount = Money.ofMinor(row.amount_minor, currency),
                realTransactionId = TransactionId(row.real_transaction_id),
                reversalTransactionId = TransactionId(row.reversal_transaction_id),
                confirmedAt = Instant.parse(row.confirmed_at),
                discoveredAt = Instant.parse(row.discovered_at),
                discoveredAtText = row.discovered_at_text,
                confirmedAtText = row.confirmed_at_text,
                createdAt = Instant.parse(row.created_at),
                createdAtText = row.created_at_text,
            )
        }
        val auditLinks = q.selectRg09AllAuditLinks(ledger).executeAsList().map { row ->
            Rg09AuditLink(
                id = Rg09AuditLinkId(row.audit_link_id),
                allocationId = Rg09AllocationId(row.allocation_id),
                role = row.role.lowercase(),
                targetId = row.target_id,
                createdAt = Instant.parse(row.created_at),
                createdAtText = row.created_at_text,
            )
        }
        val reconciliation = q.selectRg09AllPostingReconciliations(ledger).executeAsList()
            .associate { row -> row.posting_id to row.status.lowercase() }
        return Rg09Snapshot(
            formalTransactions = loadFormalTransactions(ledgerId),
            observations = observations,
            candidates = candidates,
            sourceRecords = sources,
            evidence = evidence,
            evidenceLinks = evidenceLinks,
            confirmations = confirmations,
            adjustments = adjustments,
            allocations = allocations,
            auditLinks = auditLinks,
            balances = emptyMap(),
            reports = emptyMap(),
            reconciliation = reconciliation,
        )
    }

    private fun loadFormalTransactions(ledgerId: LedgerId): List<Rg09FormalTransactionRecord> {
        val ledger = ledgerId.value
        val metadata = database.ledgerQueries.selectFormalTransactionMetadata(ledger)
            .executeAsList().associateBy { it.transaction_id }
        val sourceMap = database.ledgerQueries.selectRg09FormalTransactionSources(ledger)
            .executeAsList()
            .associate { it.transaction_id to it.source_record_id }
        return database.ledgerQueries.selectRg09FormalTransactions(ledger).executeAsList().map { row ->
            val versions = database.ledgerQueries.selectRg09FormalVersions(ledger, row.transaction_id)
                .executeAsList().map { version ->
                    TransactionVersion(
                        id = TransactionVersionId(version.version_id),
                        transactionId = TransactionId(version.transaction_id),
                        versionNumber = version.version_number.toInt(),
                        postingSetId = PostingSetId(version.posting_set_id),
                        times = TransactionTimes(
                            occurredAt = Instant.parse(version.occurred_at),
                            statisticsAt = Instant.parse(version.statistics_at),
                            effectiveAt = Instant.parse(version.effective_at),
                        ),
                        note = version.note,
                    )
                }
            val postingSets = versions.map { it.postingSetId }.distinct().map { postingSetId ->
                val postings = database.ledgerQueries.selectRg09FormalPostings(ledger, postingSetId.value)
                    .executeAsList().sortedBy { it.posting_index }.map { posting ->
                        Posting(
                            id = PostingId(posting.posting_id),
                            accountId = AccountId(posting.account_id),
                            amount = Money.ofMinor(
                                posting.amount_minor,
                                CurrencyUnit(posting.currency_code, posting.currency_precision.toInt()),
                            ),
                        )
                    }
                when (val created = PostingSet.create(postingSetId, postings)) {
                    is DomainResult.Success -> created.value
                    is DomainResult.Failure -> error("invalid persisted RG-09 posting set $postingSetId")
                }
            }
            val transaction = Transaction(
                id = TransactionId(row.transaction_id),
                ledgerId = LedgerId(row.ledger_id),
                kind = TransactionKind.valueOf(row.kind),
                currentVersionId = TransactionVersionId(row.current_version_id),
            )
            val formal = when (val created = FormalTransaction.create(transaction, versions, postingSets)) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> error("invalid persisted RG-09 formal transaction ${row.transaction_id}")
            }
            val current = versions.single { it.id == transaction.currentVersionId }
            val savedMetadata = metadata[row.transaction_id]
                ?: error("missing persisted RG-09 formal transaction metadata for ${row.transaction_id}")
            Rg09FormalTransactionRecord(
                formalTransaction = formal,
                createdAt = Instant.parse(savedMetadata.created_at),
                sourceRecordId = sourceMap[row.transaction_id]?.let(::Rg09SourceRecordId),
                createdAtText = savedMetadata.created_at,
                // effectiveAtText is rehydrated from the shared statistics_at_text column:
                // it always holds the exact bytes the former effective_at_text column
                // held (folded construction + migration copy), so the D-065 fingerprint
                // projection (Rg09Fingerprint.kt) keeps producing the same bytes.
                effectiveAtText = savedMetadata.statistics_at_text,
                statisticsAtText = savedMetadata.statistics_at_text,
            )
        }
    }

    private fun absMinor(value: Long): Long {
        check(value != Long.MIN_VALUE) { "persisted RG-09 amount cannot be Long.MIN_VALUE" }
        return if (value < 0L) -value else value
    }

    private fun safeSubtract(left: Long, right: Long): Long {
        check(!(right > 0L && left < Long.MIN_VALUE + right)) { "persisted RG-09 amount underflow" }
        check(!(right < 0L && left > Long.MAX_VALUE + right)) { "persisted RG-09 amount overflow" }
        return left - right
    }

    private fun addExact(left: Long, right: Long): Long =
        Math.addExact(left, right)

    private fun persistDelta(
        operation: Rg09Operation,
        before: Rg09Snapshot,
        after: Rg09Snapshot,
    ) {
        val beforeTransactionIds = before.formalTransactions
            .mapTo(mutableSetOf()) { it.formalTransaction.transaction.id.value }
        val newFormal = after.formalTransactions.filter {
            it.formalTransaction.transaction.id.value !in beforeTransactionIds
        }
        newFormal.forEach(::persistFormalRecord)
        persistNewFormalSemantics(operation, newFormal, after)

        val beforeSourceIds = before.sourceRecords.mapTo(mutableSetOf()) { it.id.value }
        after.sourceRecords.filter { it.id.value !in beforeSourceIds }.forEach { source ->
            database.ledgerQueries.insertRg09Source(
                operation.ledgerId.value,
                source.id.value,
                source.sourceType,
                source.observedAt.toString(),
                source.observedAtText,
                source.accountId.value,
                source.amount.minorUnits,
                source.amount.currency.code,
                source.amount.currency.precision.toLong(),
                source.counterAccountId?.value,
                source.actualAt?.toString(),
                source.actualAtText,
                source.bookingAt?.toString(),
                source.bookingAtText,
                source.immutablePayloadDigest,
            )
        }

        val beforeObservationIds = before.observations.mapTo(mutableSetOf()) { it.id.value }
        after.observations.filter { it.id.value !in beforeObservationIds }.forEach { observation ->
            database.ledgerQueries.insertRg09Observation(
                operation.ledgerId.value,
                observation.id.value,
                observation.sourceRecordId.value,
                observation.accountId.value,
                observation.targetAmount.minorUnits,
                observation.targetAmount.currency.code,
                observation.targetAmount.currency.precision.toLong(),
                observation.targetObservedAt.toString(),
                observation.targetObservedAtText,
                observation.savedAt.toString(),
                observation.savedAtText,
            )
        }

        val beforeEvidenceIds = before.evidence.mapTo(mutableSetOf()) { it.id.value }
        after.evidence.filter { it.id.value !in beforeEvidenceIds }.forEach { item ->
            database.ledgerQueries.insertRg09Evidence(
                operation.ledgerId.value,
                item.id.value,
                item.sourceRecordId.value,
                item.evidenceType,
                item.observedAt.toString(),
                item.observedAtText,
            )
        }

        val beforeLinkIds = before.evidenceLinks.mapTo(mutableSetOf()) { it.id.value }
        after.evidenceLinks.filter { it.id.value !in beforeLinkIds }.forEach { link ->
            database.ledgerQueries.insertRg09EvidenceLink(
                operation.ledgerId.value,
                link.id.value,
                link.sourceRecordId.value,
                link.evidenceId.value,
                if (link.role == "target_balance_observation") "OBSERVATION" else "POSTING",
                link.targetId,
                link.role.uppercase(),
                link.status.uppercase(),
            )
        }

        persistAdjustmentDelta(operation.ledgerId, before, after)
        persistCandidateDelta(operation, before, after)

        val beforeAllocationIds = before.allocations.mapTo(mutableSetOf()) { it.id.value }
        after.allocations.filter { it.id.value !in beforeAllocationIds }.forEach { allocation ->
            database.ledgerQueries.insertRg09Allocation(
                operation.ledgerId.value,
                allocation.id.value,
                allocation.adjustmentId.value,
                allocation.targetAccountId.value,
                allocation.amount.minorUnits,
                allocation.amount.currency.code,
                allocation.amount.currency.precision.toLong(),
                allocation.realTransactionId.value,
                allocation.reversalTransactionId.value,
                allocation.confirmedAt.toString(),
                allocation.discoveredAt.toString(),
                allocation.discoveredAtText,
                allocation.confirmedAtText,
                allocation.createdAt.toString(),
                allocation.createdAtText,
            )
        }

        val beforeAuditIds = before.auditLinks.mapTo(mutableSetOf()) { it.id.value }
        after.auditLinks.filter { it.id.value !in beforeAuditIds }.forEach { audit ->
            database.ledgerQueries.insertRg09AuditLink(
                operation.ledgerId.value,
                audit.id.value,
                audit.allocationId.value,
                audit.role.uppercase(),
                audit.targetId,
                audit.createdAt.toString(),
                audit.createdAtText,
            )
        }

        val beforeConfirmationIds = before.confirmations.mapTo(mutableSetOf()) { it.id.value }
        after.confirmations.filter { it.id.value !in beforeConfirmationIds }.forEach { confirmation ->
            database.ledgerQueries.insertRg09Confirmation(
                operation.ledgerId.value,
                confirmation.id.value,
                confirmation.requestId.value,
                confirmation.role.uppercase(),
                confirmation.confirmedAt.toString(),
                confirmation.confirmedAtText,
                confirmation.createdAt.toString(),
                confirmation.createdAtText,
                confirmation.targetId,
            )
        }
        persistReconciliationTransitions(operation.ledgerId, before, after, newFormal, beforeLinkIds)
    }

    private fun persistCandidateDelta(
        operation: Rg09Operation,
        before: Rg09Snapshot,
        after: Rg09Snapshot,
    ) {
        val beforeCandidates = before.candidates.associateBy { it.id.value }
        val existingHistory = database.ledgerQueries.selectRg09AllCandidateHistory(operation.ledgerId.value)
            .executeAsList().groupBy { it.candidate_id }
        after.candidates.forEach { candidate ->
            val old = beforeCandidates[candidate.id.value]
            if (old == null) {
                database.ledgerQueries.insertRg09Candidate(
                    operation.ledgerId.value,
                    candidate.id.value,
                    candidate.observationId?.value,
                    checkNotNull(candidate.sourceRecordId).value,
                    candidate.accountId.value,
                    candidate.replayedAmount.minorUnits,
                    candidate.targetAmount.minorUnits,
                    candidate.delta.minorUnits,
                    candidate.delta.currency.code,
                    candidate.delta.currency.precision.toLong(),
                    candidate.targetObservedAt.toString(),
                    candidate.targetObservedAtText,
                    candidate.ledgerFingerprint,
                    candidate.candidateType,
                    candidate.confidence,
                    candidate.status.uppercase(),
                    candidate.adjustmentId?.value,
                    candidate.confirmationRequestId?.value,
                )
            }
            val history = existingHistory[candidate.id.value].orEmpty()
            val latest = history.maxByOrNull { it.status_sequence }
            val lifecycleChanged =
                old == null ||
                    old.status != candidate.status ||
                    old.adjustmentId != candidate.adjustmentId ||
                    old.confirmationRequestId != candidate.confirmationRequestId
            if (lifecycleChanged) {
                val sequence = (latest?.status_sequence ?: 0L) + 1L
                database.ledgerQueries.insertRg09CandidateStatus(
                    operation.ledgerId.value,
                    candidate.id.value,
                    sequence,
                    "candidate-status-${candidate.id.value}-$sequence",
                    candidate.status.uppercase(),
                    operationOccurredAt(operation).toString(),
                    candidate.adjustmentId?.value,
                    candidate.confirmationRequestId?.value,
                    operation.identity.value,
                )
                if (old != null) {
                    database.ledgerQueries.updateRg09CandidateCurrent(
                        candidate.status.uppercase(),
                        candidate.adjustmentId?.value,
                        candidate.confirmationRequestId?.value,
                        operation.ledgerId.value,
                        candidate.id.value,
                    )
                    check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                        "RG-09 candidate current projection was not advanced"
                    }
                }
            }
        }
    }

    private fun persistAdjustmentDelta(
        ledgerId: LedgerId,
        before: Rg09Snapshot,
        after: Rg09Snapshot,
    ) {
        val beforeAdjustments = before.adjustments.associateBy { it.id.value }
        after.adjustments.forEach { adjustment ->
            val old = beforeAdjustments[adjustment.id.value]
            if (old == null) {
                database.ledgerQueries.insertRg09BalanceAdjustment(
                    ledgerId.value,
                    adjustment.id.value,
                    adjustment.transactionId.value,
                    adjustment.observationId.value,
                    adjustment.targetAccountId.value,
                    adjustment.equityAccountId.value,
                    adjustment.currency.code,
                    adjustment.currency.precision.toLong(),
                    adjustment.targetObservedAt.toString(),
                    adjustment.targetObservedAtText,
                    adjustment.replayedAmountAtConfirmation.minorUnits,
                    adjustment.targetAmount.minorUnits,
                    adjustment.originalDelta.minorUnits,
                )
            }
            val oldHistoryIds = old?.history.orEmpty().mapTo(mutableSetOf()) { it.id }
            adjustment.history.forEachIndexed { index, history ->
                if (history.id !in oldHistoryIds) {
                    database.ledgerQueries.insertRg09AdjustmentHistory(
                        ledgerId.value,
                        adjustment.id.value,
                        (index + 1).toLong(),
                        history.id,
                        history.state.uppercase(),
                        history.occurredAt.toString(),
                        history.occurredAtText,
                        history.createdAt.toString(),
                        history.createdAtText,
                        history.allocationId?.value,
                        history.remainingAmount.minorUnits,
                    )
                }
            }
        }
    }

    private fun persistNewFormalSemantics(
        operation: Rg09Operation,
        newFormal: List<Rg09FormalTransactionRecord>,
        after: Rg09Snapshot,
    ) {
        newFormal.forEach { record ->
            val formal = record.formalTransaction
            val postings = formal.currentPostings()
            when (formal.transaction.kind) {
                TransactionKind.BALANCE_ADJUSTMENT,
                TransactionKind.BALANCE_ADJUSTMENT_REVERSAL -> {
                    val targetRole = if (formal.transaction.kind == TransactionKind.BALANCE_ADJUSTMENT) {
                        "ADJUSTMENT_TARGET"
                    } else {
                        "REVERSAL_TARGET"
                    }
                    val equityRole = if (formal.transaction.kind == TransactionKind.BALANCE_ADJUSTMENT) {
                        "ADJUSTMENT_EQUITY"
                    } else {
                        "REVERSAL_EQUITY"
                    }
                    check(postings.size == 2) { "RG-09 adjustment requires two postings" }
                    database.ledgerQueries.insertRg09PostingSemantic(
                        operation.ledgerId.value, postings[0].id.value, targetRole, 0,
                    )
                    database.ledgerQueries.insertRg09PostingSemantic(
                        operation.ledgerId.value, postings[1].id.value, equityRole, 0,
                    )
                }
                TransactionKind.ACCOUNT_TRANSFER -> {
                    check(postings.size == 2) { "RG-09 transfer requires two postings" }
                    postings.forEachIndexed { index, posting ->
                        database.ledgerQueries.insertRg09PostingSemantic(
                            operation.ledgerId.value,
                            posting.id.value,
                            if (index == 0) "TRANSFER_SOURCE" else "TRANSFER_DESTINATION",
                            1,
                        )
                        val status = after.reconciliation[posting.id.value] ?: "pending_evidence"
                        database.ledgerQueries.insertRg09PostingReconciliation(
                            operation.ledgerId.value,
                            posting.id.value,
                            status.uppercase(),
                            1,
                        )
                        database.ledgerQueries.insertRg09ReconciliationHistory(
                            operation.ledgerId.value,
                            posting.id.value,
                            1,
                            status.uppercase(),
                            null,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun persistReconciliationTransitions(
        ledgerId: LedgerId,
        before: Rg09Snapshot,
        after: Rg09Snapshot,
        newFormal: List<Rg09FormalTransactionRecord>,
        beforeLinkIds: Set<String>,
    ) {
        val newPostingIds = newFormal.flatMap { it.formalTransaction.currentPostings() }
            .mapTo(mutableSetOf()) { it.id.value }
        val newLinks = after.evidenceLinks.filter { it.id.value !in beforeLinkIds }
        val histories = database.ledgerQueries.selectRg09AllReconciliationHistory(ledgerId.value)
            .executeAsList().groupBy { it.posting_id }
        after.reconciliation.forEach { (postingId, currentStatus) ->
            if (postingId in newPostingIds) return@forEach
            val previousStatus = before.reconciliation[postingId] ?: return@forEach
            if (previousStatus == currentStatus) return@forEach
            val link = newLinks.firstOrNull { it.targetId == postingId }
                ?: error("RG-09 matched reconciliation transition lacks evidence link")
            val sequence = (histories[postingId].orEmpty().maxOfOrNull { it.status_sequence } ?: 0L) + 1L
            database.ledgerQueries.insertRg09ReconciliationHistory(
                ledgerId.value,
                postingId,
                sequence,
                currentStatus.uppercase(),
                link.id.value,
            )
            database.ledgerQueries.updateRg09PostingReconciliation(
                currentStatus.uppercase(),
                sequence,
                ledgerId.value,
                postingId,
            )
            check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                "RG-09 reconciliation current row was not advanced"
            }
        }
    }

    private fun operationOccurredAt(operation: Rg09Operation): Instant = when (operation) {
        is Rg09Operation.PreviewTargetBalance -> operation.input.savedAt
        is Rg09Operation.ConfirmBalanceAdjustment -> operation.input.confirmedAt
        is Rg09Operation.ConfirmRealTransfer -> operation.input.confirmedAt
        is Rg09Operation.ConfirmImportedTransfer -> operation.input.confirmedAt
        is Rg09Operation.IngestImportedTransfer -> operation.input.observedAt
        is Rg09Operation.ConfirmExplanationAllocation -> operation.input.confirmedAt
        is Rg09Operation.LinkRealPostingEvidence -> operation.input.observedAt
        is Rg09Operation.IncompleteImportedTransferConfirmation -> error("incomplete confirmation has no status transition")
        is Rg09Operation.InvalidInput -> error("invalid input has no status transition")
    }
}
