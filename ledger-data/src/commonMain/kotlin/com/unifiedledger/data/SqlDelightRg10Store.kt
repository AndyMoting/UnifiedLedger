package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.Rg10Action
import com.unifiedledger.application.Rg10ActivationAdjustment
import com.unifiedledger.application.Rg10ActivationAdjustmentHistory
import com.unifiedledger.application.Rg10ActivationAdjustmentId
import com.unifiedledger.application.Rg10ActivationCommitIds
import com.unifiedledger.application.Rg10AllocationCommitIds
import com.unifiedledger.application.Rg10AllocationId
import com.unifiedledger.application.Rg10AuditLink
import com.unifiedledger.application.Rg10AuditLinkId
import com.unifiedledger.application.Rg10Candidate
import com.unifiedledger.application.Rg10CandidateId
import com.unifiedledger.application.Rg10Confirmation
import com.unifiedledger.application.Rg10ConfirmationId
import com.unifiedledger.application.Rg10ConsumptionId
import com.unifiedledger.application.Rg10Evidence
import com.unifiedledger.application.Rg10EvidenceId
import com.unifiedledger.application.Rg10EvidenceLink
import com.unifiedledger.application.Rg10EvidenceLinkId
import com.unifiedledger.application.Rg10ExecutionResult
import com.unifiedledger.application.Rg10ExpiryCommitIds
import com.unifiedledger.application.Rg10FieldPath
import com.unifiedledger.application.Rg10FormalTransactionRecord
import com.unifiedledger.application.Rg10IngestIds
import com.unifiedledger.application.Rg10LotConsumption
import com.unifiedledger.application.Rg10MerchantAllocation
import com.unifiedledger.application.Rg10Operation
import com.unifiedledger.application.Rg10PostingSemantic
import com.unifiedledger.application.Rg10RechargeCommitIds
import com.unifiedledger.application.Rg10ReconstructionId
import com.unifiedledger.application.Rg10RejectionReason
import com.unifiedledger.application.Rg10ReturnedId
import com.unifiedledger.application.Rg10Runtime
import com.unifiedledger.application.Rg10Snapshot
import com.unifiedledger.application.Rg10SourceRecord
import com.unifiedledger.application.Rg10SourceRecordId
import com.unifiedledger.application.Rg10SpendCommitIds
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.StoredValueActiveMode
import com.unifiedledger.domain.StoredValueLot
import com.unifiedledger.domain.StoredValueLotHistory
import com.unifiedledger.domain.StoredValueLotId
import com.unifiedledger.domain.StoredValueReconstruction
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

internal enum class Rg10FailurePoint {
    AFTER_CLAIM,
    AFTER_DELTA,
}

internal fun interface Rg10FailureInjector {
    fun failAt(point: Rg10FailurePoint)
}

private val NO_RG10_FAILURE = Rg10FailureInjector { }

/** SQLDelight owner for the approved RG-10 operation boundary (D-083). */
class SqlDelightRg10Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val openingTransactions: List<Rg10FormalTransactionRecord>,
    private val failureInjector: Rg10FailureInjector,
) : com.unifiedledger.application.Rg10CommitPort {
    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingTransactions: List<Rg10FormalTransactionRecord> = emptyList(),
    ) : this(database, catalog, openingTransactions, NO_RG10_FAILURE) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingTransactions: List<Rg10FormalTransactionRecord>,
        failureInjector: Rg10FailureInjector,
    ) : this(database, catalog, openingTransactions, failureInjector) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    override fun commit(operation: Rg10Operation): Rg10ExecutionResult = database.transactionWithResult {
        val fingerprint = operationFingerprint(operation)
        database.ledgerQueries.claimRg10Operation(
            operation.ledgerId.value,
            operation.identity.value,
            operation.action.code,
            operationClass(operation),
            fingerprint,
        )
        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
            return@transactionWithResult replay(operation, fingerprint)
        }
        failureInjector.failAt(Rg10FailurePoint.AFTER_CLAIM)

        val before = loadPersistedSnapshot(operation.ledgerId)
        val runtime = Rg10Runtime(catalog, before)
        val result = runtime.commit(operation)
        check(result !is Rg10ExecutionResult.RequestIdentityConflict) {
            "newly claimed RG-10 operation returned an identity conflict"
        }
        if (result is Rg10ExecutionResult.Accepted) {
            persistDelta(operation, before, runtime.snapshot())
        }
        failureInjector.failAt(Rg10FailurePoint.AFTER_DELTA)
        finalizeOperation(operation, fingerprint, result)
        result
    }

    fun snapshot(ledgerId: LedgerId): Rg10Snapshot =
        Rg10Runtime(catalog, loadPersistedSnapshot(ledgerId)).snapshot()

    private fun operationFingerprint(operation: Rg10Operation): String =
        Rg10Runtime(catalog, emptyList()).operationFingerprint(operation)

    private fun operationClass(operation: Rg10Operation): String = when (operation) {
        is Rg10Operation.ConfirmStoredValueRecharge -> "confirm_stored_value_recharge"
        is Rg10Operation.ConfirmStoredValueSpend -> "confirm_stored_value_spend"
        is Rg10Operation.IngestStoredValueRechargeCandidate -> "ingest_stored_value_recharge_candidate"
        is Rg10Operation.IngestStoredValueSpendCandidate -> "ingest_stored_value_spend_candidate"
        is Rg10Operation.ConfirmImportedStoredValueRecharge -> "confirm_imported_stored_value_recharge"
        is Rg10Operation.ConfirmImportedStoredValueSpend -> "confirm_imported_stored_value_spend"
        is Rg10Operation.RecordExpiryReminder -> "record_expiry_reminder"
        is Rg10Operation.ConfirmStoredValueExpiryLoss -> "confirm_stored_value_expiry_loss"
        is Rg10Operation.ReconcileMerchantCredit -> "reconcile_merchant_credit"
        is Rg10Operation.ReconcileBankPayment -> "reconcile_bank_payment"
        is Rg10Operation.ApplyMerchantLotAllocation -> "apply_merchant_lot_allocation"
        is Rg10Operation.ConfirmStoredValueActivationBalance -> "confirm_stored_value_activation_balance"
        is Rg10Operation.RenameStoredValueLabels -> "rename_stored_value_labels"
        is Rg10Operation.InvalidInput -> "reject_invalid_rg10_input"
    }

    private fun replay(operation: Rg10Operation, fingerprint: String): Rg10ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg10Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg10ExecutionResult.RequestIdentityConflict
        if (
            saved.action != operation.action.code ||
            saved.operation_class != operationClass(operation) ||
            saved.input_fingerprint != fingerprint
        ) {
            return Rg10ExecutionResult.RequestIdentityConflict
        }
        if (saved.outcome == "PENDING") {
            error("persisted RG-10 operation is still pending")
        }
        val returned = database.ledgerQueries
            .selectRg10ReturnedIds(operation.ledgerId.value, operation.identity.value)
            .executeAsList()
            .map(::restoreReturnedId)
        return when (saved.outcome) {
            "ACCEPTED", "NO_CHANGE" -> Rg10ExecutionResult.NoChange(returned)
            "REJECTED" -> Rg10ExecutionResult.Rejected(
                reason = Rg10RejectionReason.values().single { it.code == saved.reason_code },
                fieldPath = Rg10FieldPath.values().single { it.value == saved.field_path },
            )
            else -> error("unknown RG-10 operation outcome ${saved.outcome}")
        }
    }

    private fun restoreReturnedId(row: com.unifiedledger.data.db.SelectRg10ReturnedIds): Rg10ReturnedId =
        when (row.id_kind) {
            "TRANSACTION" -> Rg10ReturnedId.Transaction(TransactionId(row.id_value))
            "VERSION" -> Rg10ReturnedId.Version(TransactionVersionId(row.id_value))
            "LOT" -> Rg10ReturnedId.Lot(StoredValueLotId(row.id_value))
            "CONFIRMATION" -> Rg10ReturnedId.Confirmation(Rg10ConfirmationId(row.id_value))
            "CANDIDATE" -> Rg10ReturnedId.Candidate(Rg10CandidateId(row.id_value))
            "EVIDENCE_LINK" -> Rg10ReturnedId.EvidenceLink(Rg10EvidenceLinkId(row.id_value))
            "ALLOCATION" -> Rg10ReturnedId.Allocation(Rg10AllocationId(row.id_value))
            "CONSUMPTION" -> Rg10ReturnedId.Consumption(Rg10ConsumptionId(row.id_value))
            "ADJUSTMENT" -> Rg10ReturnedId.Adjustment(Rg10ActivationAdjustmentId(row.id_value))
            "REQUEST" -> Rg10ReturnedId.Request(row.id_value)
            else -> error("unknown persisted RG-10 returned id kind ${row.id_kind}")
        }

    private fun finalizeOperation(
        operation: Rg10Operation,
        fingerprint: String,
        result: Rg10ExecutionResult,
    ) {
        val outcome = when (result) {
            is Rg10ExecutionResult.Accepted -> "ACCEPTED"
            is Rg10ExecutionResult.NoChange -> "NO_CHANGE"
            is Rg10ExecutionResult.Rejected -> "REJECTED"
            Rg10ExecutionResult.RequestIdentityConflict -> error("cannot finalize identity conflict")
        }
        val rejected = result as? Rg10ExecutionResult.Rejected
        val changed = database.ledgerQueries.updateRg10OperationResult(
            outcome,
            rejected?.reason?.code,
            rejected?.fieldPath?.value,
            operation.ledgerId.value,
            operation.identity.value,
        ).value
        check(changed == 1L) { "RG-10 operation final result did not update" }
        val returned = when (result) {
            is Rg10ExecutionResult.Accepted -> result.returnedIds
            is Rg10ExecutionResult.NoChange -> result.returnedIds
            else -> emptyList()
        }
        returned.forEachIndexed { index, id ->
            val stored = id.stored()
            database.ledgerQueries.insertRg10ReturnedId(
                operation.ledgerId.value,
                operation.identity.value,
                index.toLong(),
                stored.first,
                stored.second,
            )
        }
        check(fingerprint == database.ledgerQueries
            .selectRg10Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOne().input_fingerprint)
    }

    private fun Rg10ReturnedId.stored(): Pair<String, String> = when (this) {
        is Rg10ReturnedId.Transaction -> "TRANSACTION" to id.value
        is Rg10ReturnedId.Version -> "VERSION" to id.value
        is Rg10ReturnedId.Lot -> "LOT" to id.value
        is Rg10ReturnedId.Confirmation -> "CONFIRMATION" to id.value
        is Rg10ReturnedId.Candidate -> "CANDIDATE" to id.value
        is Rg10ReturnedId.EvidenceLink -> "EVIDENCE_LINK" to id.value
        is Rg10ReturnedId.Allocation -> "ALLOCATION" to id.value
        is Rg10ReturnedId.Consumption -> "CONSUMPTION" to id.value
        is Rg10ReturnedId.Adjustment -> "ADJUSTMENT" to id.value
        is Rg10ReturnedId.Request -> "REQUEST" to id
    }

    private fun ensureOpeningTransactions() {
        if (openingTransactions.isEmpty()) return
        database.transaction {
            val existing = database.ledgerQueries
                .selectRg10FormalTransactions(openingTransactions.first().formalTransaction.transaction.ledgerId.value)
                .executeAsList()
                .mapTo(mutableSetOf()) { it.transaction_id }
            openingTransactions.forEach { record ->
                if (record.formalTransaction.transaction.id.value !in existing) {
                    persistFormalRecord(record)
                }
            }
        }
    }

    private fun persistFormalRecord(record: Rg10FormalTransactionRecord) {
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
        // former effective_at_text column held (folded construction + migration
        // copy); any new construction point that does not fold would break the
        // byte identity.
        database.ledgerQueries.insertFormalTransactionMetadata(
            formal.transaction.ledgerId.value,
            formal.transaction.id.value,
            record.createdAtText ?: record.createdAt.toString(),
            record.statisticsAtText ?: formal.versions.last().times.statisticsAt.toString(),
        )
        // Step 2: the slimmed private table keeps the source link when present.
        record.sourceRecordId?.let { sourceRecordId ->
            database.ledgerQueries.insertRg10FormalTransactionSource(
                formal.transaction.ledgerId.value,
                formal.transaction.id.value,
                sourceRecordId.value,
            )
        }
    }

    private fun loadPersistedSnapshot(ledgerId: LedgerId): Rg10Snapshot {
        val ledger = ledgerId.value
        val q = database.ledgerQueries
        val sources = q.selectRg10AllSources(ledger).executeAsList().map { row ->
            Rg10SourceRecord(
                id = Rg10SourceRecordId(row.source_id),
                sourceType = row.source_type,
                observedAt = Instant.parse(row.observed_at),
                observedAtText = row.observed_at_text,
                accountId = row.account_id?.let(::AccountId),
                amount = moneyOrNull(row.amount_minor, row.currency_code, row.currency_precision),
                lotId = row.lot_id?.let(::StoredValueLotId),
                immutablePayloadDigest = row.immutable_payload_digest,
            )
        }
        val evidence = q.selectRg10AllEvidence(ledger).executeAsList().map { row ->
            Rg10Evidence(
                id = Rg10EvidenceId(row.evidence_id),
                sourceId = Rg10SourceRecordId(row.source_id),
                evidenceType = row.evidence_type,
                observedAt = Instant.parse(row.observed_at),
                observedAtText = row.observed_at_text,
            )
        }
        val evidenceLinks = q.selectRg10AllEvidenceLinks(ledger).executeAsList().map { row ->
            Rg10EvidenceLink(
                id = Rg10EvidenceLinkId(row.link_id),
                sourceId = Rg10SourceRecordId(row.source_id),
                evidenceId = Rg10EvidenceId(row.evidence_id),
                role = row.role.lowercase(),
                targetKind = row.target_kind,
                targetId = row.target_id,
                status = row.status.lowercase(),
                lotId = row.lot_id?.let(::StoredValueLotId),
            )
        }
        val lotHistory = q.selectRg10AllLotHistory(ledger).executeAsList().groupBy { it.lot_id }
        val lots = q.selectRg10AllLots(ledger).executeAsList().map { row ->
            val currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt())
            StoredValueLot(
                id = StoredValueLotId(row.lot_id),
                rechargeTransactionId = row.recharge_transaction_id?.let(::TransactionId),
                loadedAt = Instant.parse(row.loaded_at),
                expiresAt = Instant.parse(row.expires_at),
                faceValue = Money.ofMinor(row.face_value_minor, currency),
                remainingFaceValue = Money.ofMinor(row.remaining_face_value_minor, currency),
                paidAmount = moneyOrNull(row.paid_amount_minor, row.currency_code, row.currency_precision),
                bonusAmount = moneyOrNull(row.bonus_amount_minor, row.currency_code, row.currency_precision),
                remainingPaidAmount = moneyOrNull(row.remaining_paid_amount_minor, row.currency_code, row.currency_precision),
                remainingBonusAmount = moneyOrNull(row.remaining_bonus_amount_minor, row.currency_code, row.currency_precision),
                compositionStatus = row.composition_status.lowercase(),
                history = lotHistory[row.lot_id].orEmpty().sortedBy { it.history_sequence }.map { historyRow ->
                    StoredValueLotHistory(
                        id = historyRow.history_id,
                        event = historyRow.event.lowercase(),
                        transactionId = TransactionId(historyRow.transaction_id),
                        amount = Money.ofMinor(historyRow.amount_minor, currency),
                        remainingFaceValue = Money.ofMinor(historyRow.remaining_face_value_minor, currency),
                        occurredAt = Instant.parse(historyRow.occurred_at),
                        createdAt = Instant.parse(historyRow.created_at),
                        occurredAtText = historyRow.occurred_at_text,
                        createdAtText = historyRow.created_at_text,
                        compositionStatus = historyRow.composition_status?.lowercase(),
                    )
                },
                merchantId = row.merchant_id,
                loadedAtText = row.loaded_at_text,
                expiresAtText = row.expires_at_text,
            )
        }
        val consumptions = q.selectRg10AllConsumptions(ledger).executeAsList().map { row ->
            Rg10LotConsumption(
                id = Rg10ConsumptionId(row.consumption_id),
                allocationId = row.allocation_id?.let(::Rg10AllocationId),
                sourceId = row.source_id?.let(::Rg10SourceRecordId),
                evidenceId = row.evidence_id?.let(::Rg10EvidenceId),
                lotId = StoredValueLotId(row.lot_id),
                amount = Money.ofMinor(row.amount_minor, CurrencyUnit(row.currency_code, row.currency_precision.toInt())),
                paidBonusComposition = row.paid_bonus_composition.lowercase(),
            )
        }
        val allocations = q.selectRg10AllAllocations(ledger).executeAsList().map { row ->
            Rg10MerchantAllocation(
                id = Rg10AllocationId(row.allocation_id),
                requestId = RequestId(row.request_id),
                sourceId = Rg10SourceRecordId(row.source_id),
                evidenceId = Rg10EvidenceId(row.evidence_id),
                lotId = StoredValueLotId(row.lot_id),
                consumptionId = Rg10ConsumptionId(row.consumption_id),
                amount = Money.ofMinor(row.amount_minor, CurrencyUnit(row.currency_code, row.currency_precision.toInt())),
                allocationSource = row.allocation_source.lowercase(),
            )
        }
        val activationHistory = q.selectRg10AllActivationAdjustmentHistory(ledger)
            .executeAsList().groupBy { it.adjustment_id }
        val adjustments = q.selectRg10AllActivationAdjustments(ledger).executeAsList().map { row ->
            Rg10ActivationAdjustment(
                id = Rg10ActivationAdjustmentId(row.adjustment_id),
                transactionId = TransactionId(row.transaction_id),
                activationAt = Instant.parse(row.activation_at),
                activationAtText = row.activation_at_text,
                existingBalance = Money.ofMinor(
                    row.existing_balance_minor,
                    CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                ),
                compositionStatus = row.composition_status.lowercase(),
                replacementStatus = row.replacement_status.lowercase(),
                history = activationHistory[row.adjustment_id].orEmpty()
                    .sortedBy { it.history_sequence }
                    .map { historyRow ->
                        Rg10ActivationAdjustmentHistory(
                            id = historyRow.history_id,
                            event = historyRow.event.lowercase(),
                            transactionId = TransactionId(historyRow.transaction_id),
                            occurredAt = Instant.parse(historyRow.occurred_at),
                            occurredAtText = historyRow.occurred_at_text,
                            createdAt = Instant.parse(historyRow.created_at),
                            createdAtText = historyRow.created_at_text,
                        )
                    },
            )
        }
        val reconstructionHistory = q.selectRg10AllReconstructionHistory(ledger)
            .executeAsList().groupBy { it.reconstruction_id }
        val reconstructions = q.selectRg10AllReconstructions(ledger).executeAsList().map { row ->
            val historyRows = reconstructionHistory[row.reconstruction_id].orEmpty()
                .sortedBy { it.history_sequence }
            StoredValueReconstruction(
                id = row.reconstruction_id,
                replacementGroupId = row.replacement_group_id,
                adjustmentTransactionId = TransactionId(row.adjustment_transaction_id),
                reconstructedTransactionIds = emptyList(),
                activeMode = StoredValueActiveMode.valueOf(row.active_mode),
                history = historyRows.map { historyRow ->
                    com.unifiedledger.domain.StoredValueReconstructionHistory(
                        id = historyRow.history_id,
                        event = historyRow.event.lowercase(),
                        activeMode = StoredValueActiveMode.valueOf(historyRow.active_mode),
                        occurredAt = Instant.parse(historyRow.occurred_at),
                        createdAt = Instant.parse(historyRow.created_at),
                        occurredAtText = historyRow.occurred_at_text,
                        createdAtText = historyRow.created_at_text,
                    )
                },
            )
        }
        val candidates = q.selectRg10AllCandidates(ledger).executeAsList().map { row ->
            Rg10Candidate(
                id = Rg10CandidateId(row.candidate_id),
                requestId = RequestId(row.request_id),
                candidateType = row.candidate_type.lowercase(),
                status = row.status.lowercase(),
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                paidAmount = moneyOrNull(row.paid_amount_minor, row.currency_code, row.currency_precision),
                creditedAmount = moneyOrNull(row.credited_amount_minor, row.currency_code, row.currency_precision),
                bonusAmount = moneyOrNull(row.bonus_amount_minor, row.currency_code, row.currency_precision),
                amount = moneyOrNull(row.amount_minor, row.currency_code, row.currency_precision),
                occurredAt = row.occurred_at?.let(Instant::parse),
                occurredAtText = row.occurred_at_text,
            )
        }
        val confirmations = q.selectRg10AllConfirmations(ledger).executeAsList().map { row ->
            Rg10Confirmation(
                id = Rg10ConfirmationId(row.confirmation_id),
                requestId = RequestId(row.request_id),
                role = row.role.lowercase(),
                transactionId = TransactionId(row.transaction_id),
                sourceId = row.source_id?.let(::Rg10SourceRecordId),
                evidenceId = row.evidence_id?.let(::Rg10EvidenceId),
                auditLinkId = row.audit_link_id?.let(::Rg10AuditLinkId),
                confirmedAt = Instant.parse(row.confirmed_at),
                confirmedAtText = row.confirmed_at_text,
                explicitConfirmation = row.explicit_confirmation?.let { it == 1L },
                confirmsActualExpiry = row.confirms_actual_expiry?.let { it == 1L },
            )
        }
        val auditLinks = q.selectRg10AllAuditLinks(ledger).executeAsList().map { row ->
            Rg10AuditLink(
                id = Rg10AuditLinkId(row.audit_link_id),
                role = row.role.lowercase(),
                sourceId = row.source_id?.let(::Rg10SourceRecordId),
                evidenceId = row.evidence_id?.let(::Rg10EvidenceId),
                confirmationId = row.confirmation_id?.let(::Rg10ConfirmationId),
                transactionId = row.transaction_id?.let(::TransactionId),
            )
        }
        val postingSemantics = q.selectRg10AllPostingSemantics(ledger).executeAsList()
            .associate { row ->
                row.posting_id to Rg10PostingSemantic(row.role, row.reconciliation_eligible == 1L)
            }
        val reconciliation = q.selectRg10AllPostingReconciliations(ledger).executeAsList()
            .associate { row -> row.posting_id to row.status.lowercase() }
        return Rg10Snapshot(
            formalTransactions = loadFormalTransactions(ledgerId),
            lots = lots,
            consumptions = consumptions,
            allocations = allocations,
            adjustments = adjustments,
            reconstructions = reconstructions,
            candidates = candidates,
            confirmations = confirmations,
            sourceRecords = sources,
            evidence = evidence,
            evidenceLinks = evidenceLinks,
            auditLinks = auditLinks,
            postingSemantics = postingSemantics,
            balances = emptyMap(),
            reports = emptyMap(),
            reconciliation = reconciliation,
        )
    }

    private fun moneyOrNull(minor: Long?, code: String?, precision: Long?): Money? {
        if (minor == null || code == null || precision == null) return null
        return Money.ofMinor(minor, CurrencyUnit(code, precision.toInt()))
    }

    private fun loadFormalTransactions(ledgerId: LedgerId): List<Rg10FormalTransactionRecord> {
        val ledger = ledgerId.value
        val metadata = database.ledgerQueries.selectFormalTransactionMetadata(ledger)
            .executeAsList().associateBy { it.transaction_id }
        val sourceMap = database.ledgerQueries.selectRg10FormalTransactionSources(ledger)
            .executeAsList()
            .associate { it.transaction_id to it.source_record_id }
        return database.ledgerQueries.selectRg10FormalTransactions(ledger).executeAsList().map { row ->
            val versions = database.ledgerQueries.selectRg10FormalVersions(ledger, row.transaction_id)
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
                val postings = database.ledgerQueries.selectRg10FormalPostings(ledger, postingSetId.value)
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
                    is DomainResult.Failure -> error("invalid persisted RG-10 posting set $postingSetId")
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
                is DomainResult.Failure -> error("invalid persisted RG-10 formal transaction ${row.transaction_id}")
            }
            val savedMetadata = metadata[row.transaction_id]
                ?: error("missing persisted RG-10 formal transaction metadata for ${row.transaction_id}")
            Rg10FormalTransactionRecord(
                formalTransaction = formal,
                createdAt = Instant.parse(savedMetadata.created_at),
                sourceRecordId = sourceMap[row.transaction_id]?.let(::Rg10SourceRecordId),
                createdAtText = savedMetadata.created_at,
                // RG-10 has no fingerprint consumer: effectiveAtText stays null on the
                // read side and effective time is derived from version.times when needed.
                effectiveAtText = null,
                statisticsAtText = savedMetadata.statistics_at_text,
            )
        }
    }

    private fun persistDelta(
        operation: Rg10Operation,
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ) {
        val beforeTransactionIds = before.formalTransactions
            .mapTo(mutableSetOf()) { it.formalTransaction.transaction.id.value }
        val newFormal = after.formalTransactions.filter {
            it.formalTransaction.transaction.id.value !in beforeTransactionIds
        }
        newFormal.forEach(::persistFormalRecord)
        persistNewFormalSemantics(operation, newFormal, after)

        persistLotDelta(operation.ledgerId, before, after)

        val beforeSourceIds = before.sourceRecords.mapTo(mutableSetOf()) { it.id.value }
        after.sourceRecords.filter { it.id.value !in beforeSourceIds }.forEach { source ->
            database.ledgerQueries.insertRg10Source(
                operation.ledgerId.value,
                source.id.value,
                source.sourceType,
                source.observedAt.toString(),
                source.observedAtText,
                source.accountId?.value,
                source.amount?.minorUnits,
                source.lotId?.value,
                source.amount?.currency?.code,
                source.amount?.currency?.precision?.toLong(),
                source.immutablePayloadDigest,
            )
        }

        val beforeEvidenceIds = before.evidence.mapTo(mutableSetOf()) { it.id.value }
        after.evidence.filter { it.id.value !in beforeEvidenceIds }.forEach { item ->
            database.ledgerQueries.insertRg10Evidence(
                operation.ledgerId.value,
                item.id.value,
                item.sourceId.value,
                item.evidenceType,
                item.observedAt.toString(),
                item.observedAtText,
            )
        }

        val beforeAdjustmentIds = before.adjustments.mapTo(mutableSetOf()) { it.id.value }
        after.adjustments.filter { it.id.value !in beforeAdjustmentIds }.forEach { adjustment ->
            database.ledgerQueries.insertRg10ActivationAdjustment(
                operation.ledgerId.value,
                adjustment.id.value,
                adjustment.transactionId.value,
                adjustment.activationAt.toString(),
                adjustment.activationAtText,
                adjustment.existingBalance.minorUnits,
                adjustment.compositionStatus.uppercase(),
                adjustment.replacementStatus.uppercase(),
                adjustment.existingBalance.currency.code,
                adjustment.existingBalance.currency.precision.toLong(),
            )
            adjustment.history.forEachIndexed { index, history ->
                database.ledgerQueries.insertRg10ActivationAdjustmentHistory(
                    operation.ledgerId.value,
                    adjustment.id.value,
                    (index + 1).toLong(),
                    history.id,
                    history.event.uppercase(),
                    history.transactionId.value,
                    history.occurredAt.toString(),
                    history.occurredAtText,
                    history.createdAt.toString(),
                    history.createdAtText,
                )
            }
        }

        val beforeReconstructionIds = before.reconstructions.mapTo(mutableSetOf()) { it.id }
        after.reconstructions.filter { it.id !in beforeReconstructionIds }.forEach { reconstruction ->
            database.ledgerQueries.insertRg10Reconstruction(
                operation.ledgerId.value,
                reconstruction.id,
                reconstruction.replacementGroupId,
                reconstruction.adjustmentTransactionId.value,
                reconstruction.activeMode.name,
            )
            reconstruction.history.forEachIndexed { index, history ->
                database.ledgerQueries.insertRg10ReconstructionHistory(
                    operation.ledgerId.value,
                    reconstruction.id,
                    (index + 1).toLong(),
                    history.id,
                    history.event.uppercase(),
                    history.activeMode.name,
                    history.occurredAt.toString(),
                    history.occurredAtText,
                    history.createdAt.toString(),
                    history.createdAtText,
                )
            }
        }

        val beforeLinkIds = before.evidenceLinks.mapTo(mutableSetOf()) { it.id.value }
        after.evidenceLinks.filter { it.id.value !in beforeLinkIds }.forEach { link ->
            database.ledgerQueries.insertRg10EvidenceLink(
                operation.ledgerId.value,
                link.id.value,
                link.sourceId.value,
                link.evidenceId.value,
                link.targetKind,
                link.targetId,
                link.role.uppercase(),
                link.status.uppercase(),
                link.lotId?.value,
            )
        }

        val beforeConfirmationIds = before.confirmations.mapTo(mutableSetOf()) { it.id.value }
        after.confirmations.filter { it.id.value !in beforeConfirmationIds }.forEach { confirmation ->
            database.ledgerQueries.insertRg10Confirmation(
                operation.ledgerId.value,
                confirmation.id.value,
                confirmation.requestId.value,
                confirmation.role.uppercase(),
                checkNotNull(confirmation.transactionId).value,
                confirmation.sourceId?.value,
                confirmation.evidenceId?.value,
                confirmation.auditLinkId?.value,
                confirmation.confirmedAt.toString(),
                confirmation.confirmedAtText,
                confirmation.explicitConfirmation?.let { if (it) 1L else 0L },
                confirmation.confirmsActualExpiry?.let { if (it) 1L else 0L },
            )
        }

        val beforeAuditIds = before.auditLinks.mapTo(mutableSetOf()) { it.id.value }
        after.auditLinks.filter { it.id.value !in beforeAuditIds }.forEach { audit ->
            database.ledgerQueries.insertRg10AuditLink(
                operation.ledgerId.value,
                audit.id.value,
                audit.role.uppercase(),
                audit.sourceId?.value,
                audit.evidenceId?.value,
                audit.confirmationId?.value,
                audit.transactionId?.value,
            )
        }

        val beforeCandidateIds = before.candidates.mapTo(mutableSetOf()) { it.id.value }
        after.candidates.filter { it.id.value !in beforeCandidateIds }.forEach { candidate ->
            database.ledgerQueries.insertRg10Candidate(
                operation.ledgerId.value,
                candidate.id.value,
                candidate.requestId.value,
                candidate.candidateType.uppercase(),
                candidate.status.uppercase(),
                candidate.currency.code,
                candidate.currency.precision.toLong(),
                candidate.paidAmount?.minorUnits,
                candidate.creditedAmount?.minorUnits,
                candidate.bonusAmount?.minorUnits,
                candidate.amount?.minorUnits,
                candidate.occurredAt?.toString(),
                candidate.occurredAtText,
            )
        }

        val beforeAllocationIds = before.allocations.mapTo(mutableSetOf()) { it.id.value }
        after.allocations.filter { it.id.value !in beforeAllocationIds }.forEach { allocation ->
            database.ledgerQueries.insertRg10Allocation(
                operation.ledgerId.value,
                allocation.id.value,
                allocation.requestId.value,
                allocation.sourceId.value,
                allocation.evidenceId.value,
                allocation.lotId.value,
                allocation.consumptionId.value,
                allocation.amount.minorUnits,
                allocation.allocationSource.uppercase(),
                allocation.amount.currency.code,
                allocation.amount.currency.precision.toLong(),
            )
        }

        val beforeConsumptionIds = before.consumptions.mapTo(mutableSetOf()) { it.id.value }
        val lotRemainingAtInsert = before.lots
            .associateTo(mutableMapOf()) { it.id.value to it.remainingFaceValue.minorUnits }
        after.consumptions.filter { it.id.value !in beforeConsumptionIds }.forEach { consumption ->
            val priorRemaining = lotRemainingAtInsert[consumption.lotId.value]
                ?: error("RG-10 consumption references a lot outside the persisted baseline")
            val resulting = priorRemaining - consumption.amount.minorUnits
            check(resulting >= 0L) { "RG-10 consumption would leave a negative lot remaining" }
            lotRemainingAtInsert[consumption.lotId.value] = resulting
            database.ledgerQueries.insertRg10Consumption(
                operation.ledgerId.value,
                consumption.id.value,
                consumption.allocationId?.value,
                consumption.sourceId?.value,
                consumption.evidenceId?.value,
                consumption.lotId.value,
                consumption.amount.minorUnits,
                resulting,
                consumption.paidBonusComposition.uppercase(),
                consumption.amount.currency.code,
                consumption.amount.currency.precision.toLong(),
            )
        }

        persistLotRemainingUpdates(operation.ledgerId, before, after)
        persistLinkStatusUpdates(operation.ledgerId, before, after)
        persistReconciliationTransitions(operation.ledgerId, before, after, newFormal)
    }

    private fun persistLotDelta(
        ledgerId: LedgerId,
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ) {
        val beforeLots = before.lots.associateBy { it.id.value }
        val existingHistory = database.ledgerQueries.selectRg10AllLotHistory(ledgerId.value)
            .executeAsList().groupBy { it.lot_id }
        after.lots.forEach { lot ->
            val old = beforeLots[lot.id.value]
            if (old == null) {
                database.ledgerQueries.insertRg10Lot(
                    ledgerId.value,
                    lot.id.value,
                    lot.rechargeTransactionId?.value,
                    lot.loadedAt.toString(),
                    lot.loadedAtText,
                    lot.expiresAt.toString(),
                    lot.expiresAtText,
                    lot.faceValue.minorUnits,
                    lot.remainingFaceValue.minorUnits,
                    lot.paidAmount?.minorUnits,
                    lot.bonusAmount?.minorUnits,
                    lot.remainingPaidAmount?.minorUnits,
                    lot.remainingBonusAmount?.minorUnits,
                    lot.compositionStatus.uppercase(),
                    lot.faceValue.currency.code,
                    lot.faceValue.currency.precision.toLong(),
                    lot.merchantId,
                )
                lot.history.forEachIndexed { index, history ->
                    database.ledgerQueries.insertRg10LotHistory(
                        ledgerId.value,
                        lot.id.value,
                        (index + 1).toLong(),
                        history.id,
                        history.event.uppercase(),
                        history.transactionId.value,
                        history.amount.minorUnits,
                        history.remainingFaceValue.minorUnits,
                        history.occurredAt.toString(),
                        history.occurredAtText,
                        history.createdAt.toString(),
                        history.createdAtText,
                        history.compositionStatus?.uppercase(),
                    )
                }
            } else {
                val oldHistoryIds = old.history.mapTo(mutableSetOf()) { it.id }
                lot.history.forEachIndexed { index, history ->
                    if (history.id !in oldHistoryIds) {
                        database.ledgerQueries.insertRg10LotHistory(
                            ledgerId.value,
                            lot.id.value,
                            (index + 1).toLong(),
                            history.id,
                            history.event.uppercase(),
                            history.transactionId.value,
                            history.amount.minorUnits,
                            history.remainingFaceValue.minorUnits,
                            history.occurredAt.toString(),
                            history.occurredAtText,
                            history.createdAt.toString(),
                            history.createdAtText,
                            history.compositionStatus?.uppercase(),
                        )
                    }
                }
            }
        }
    }

    private fun persistLotRemainingUpdates(
        ledgerId: LedgerId,
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ) {
        val beforeLots = before.lots.associateBy { it.id.value }
        after.lots.forEach { lot ->
            val old = beforeLots[lot.id.value] ?: return@forEach
            if (
                old.remainingFaceValue != lot.remainingFaceValue ||
                old.compositionStatus != lot.compositionStatus ||
                old.remainingPaidAmount != lot.remainingPaidAmount ||
                old.remainingBonusAmount != lot.remainingBonusAmount
            ) {
                database.ledgerQueries.updateRg10LotRemaining(
                    lot.remainingFaceValue.minorUnits,
                    lot.remainingPaidAmount?.minorUnits,
                    lot.remainingBonusAmount?.minorUnits,
                    lot.compositionStatus.uppercase(),
                    ledgerId.value,
                    lot.id.value,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                    "RG-10 lot current projection was not advanced"
                }
            }
        }
    }

    private fun persistLinkStatusUpdates(
        ledgerId: LedgerId,
        before: Rg10Snapshot,
        after: Rg10Snapshot,
    ) {
        val beforeLinks = before.evidenceLinks.associateBy { it.id.value }
        after.evidenceLinks.forEach { link ->
            val old = beforeLinks[link.id.value] ?: return@forEach
            if (old.status != link.status) {
                database.ledgerQueries.updateRg10EvidenceLinkStatus(
                    link.status.uppercase(),
                    ledgerId.value,
                    link.id.value,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                    "RG-10 evidence link status was not advanced"
                }
            }
        }
    }

    private fun persistNewFormalSemantics(
        operation: Rg10Operation,
        newFormal: List<Rg10FormalTransactionRecord>,
        after: Rg10Snapshot,
    ) {
        newFormal.forEach { record ->
            record.formalTransaction.currentPostings().forEach { posting ->
                val semantic = after.postingSemantics[posting.id.value] ?: return@forEach
                database.ledgerQueries.insertRg10PostingSemantic(
                    operation.ledgerId.value,
                    posting.id.value,
                    semantic.role,
                    if (semantic.reconciliationEligible) 1L else 0L,
                )
                if (semantic.reconciliationEligible) {
                    database.ledgerQueries.insertRg10PostingReconciliation(
                        operation.ledgerId.value,
                        posting.id.value,
                        "PENDING",
                        1,
                    )
                    database.ledgerQueries.insertRg10ReconciliationHistory(
                        operation.ledgerId.value,
                        posting.id.value,
                        1,
                        "PENDING",
                        null,
                    )
                }
            }
        }
    }

    private fun persistReconciliationTransitions(
        ledgerId: LedgerId,
        before: Rg10Snapshot,
        after: Rg10Snapshot,
        newFormal: List<Rg10FormalTransactionRecord>,
    ) {
        val newPostingIds = newFormal.flatMap { it.formalTransaction.currentPostings() }
            .mapTo(mutableSetOf()) { it.id.value }
        val histories = database.ledgerQueries.selectRg10AllReconciliationHistory(ledgerId.value)
            .executeAsList().groupBy { it.posting_id }
        after.reconciliation.forEach { (postingId, currentStatus) ->
            if (postingId in newPostingIds) return@forEach
            val previousStatus = before.reconciliation[postingId] ?: return@forEach
            if (previousStatus == currentStatus) return@forEach
            val link = after.evidenceLinks.firstOrNull {
                it.targetKind == "POSTING" &&
                    it.targetId == postingId &&
                    it.status == "matched"
            } ?: error("RG-10 matched reconciliation transition lacks evidence link")
            val sequence = (histories[postingId].orEmpty().maxOfOrNull { it.status_sequence } ?: 0L) + 1L
            database.ledgerQueries.insertRg10ReconciliationHistory(
                ledgerId.value,
                postingId,
                sequence,
                currentStatus.uppercase(),
                link.id.value,
            )
            database.ledgerQueries.updateRg10PostingReconciliation(
                currentStatus.uppercase(),
                sequence,
                ledgerId.value,
                postingId,
            )
            check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                "RG-10 reconciliation current row was not advanced"
            }
        }
    }
}
