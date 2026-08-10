package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.Rg08Action
import com.unifiedledger.application.Rg08CandidateId
import com.unifiedledger.application.Rg08ConfirmationId
import com.unifiedledger.application.Rg08EvidenceId
import com.unifiedledger.application.Rg08EvidenceLinkId
import com.unifiedledger.application.Rg08ExecutionResult
import com.unifiedledger.application.Rg08FieldPath
import com.unifiedledger.application.Rg08FormalTransactionRecord
import com.unifiedledger.application.Rg08LendingCatalog
import com.unifiedledger.application.Rg08Operation
import com.unifiedledger.application.Rg08PostingSemantic
import com.unifiedledger.application.Rg08RejectionReason
import com.unifiedledger.application.Rg08ReturnedId
import com.unifiedledger.application.Rg08Runtime
import com.unifiedledger.application.Rg08Snapshot
import com.unifiedledger.application.Rg08SourceRecordId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingAllocationScope
import com.unifiedledger.domain.LendingAuditLink
import com.unifiedledger.domain.LendingAuditLinkKind
import com.unifiedledger.domain.LendingBehaviorCode
import com.unifiedledger.domain.LendingCandidate
import com.unifiedledger.domain.LendingCandidateStatus
import com.unifiedledger.domain.LendingCandidateStatusHistoryEntry
import com.unifiedledger.domain.LendingComponentKind
import com.unifiedledger.domain.LendingConfirmationProvenance
import com.unifiedledger.domain.LendingConfirmationRole
import com.unifiedledger.domain.LendingEvidence
import com.unifiedledger.domain.LendingEvidenceLink
import com.unifiedledger.domain.LendingEvidenceLinkRole
import com.unifiedledger.domain.LendingEvidenceLinkStatus
import com.unifiedledger.domain.LendingEvidenceType
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.LendingPositionHistoryEntry
import com.unifiedledger.domain.LendingSettlement
import com.unifiedledger.domain.LendingSettlementComponent
import com.unifiedledger.domain.LendingSettlementHistoryEntry
import com.unifiedledger.domain.LendingSettlementStatus
import com.unifiedledger.domain.LendingSourceKind
import com.unifiedledger.domain.LendingSourceRecord
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
import com.unifiedledger.domain.confirmationGatesFor
import kotlin.time.Instant

internal enum class Rg08FailurePoint {
    AFTER_CLAIM,
    AFTER_DELTA,
}

internal fun interface Rg08FailureInjector {
    fun failAt(point: Rg08FailurePoint)
}

private val NO_RG08_FAILURE = Rg08FailureInjector { }

/**
 * SQLDelight owner for the approved RG-08 lending operation boundary (D-084).
 *
 * The store follows the RG-10 owner pattern: every commit claims the operation
 * identity with its fingerprint, replays an already-finalized identity, and
 * persists the runtime delta inside one transaction. `retry_idempotent_input`
 * (D-084 explicit deviation from D-083) replays by identity lookup only, exactly
 * like the runtime receipt map; a retry of an unknown identity is a conflict.
 */
class SqlDelightRg08Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val lendingCatalog: Rg08LendingCatalog,
    private val openingTransactions: List<Rg08FormalTransactionRecord>,
    private val failureInjector: Rg08FailureInjector,
) : com.unifiedledger.application.Rg08CommitPort {
    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        lendingCatalog: Rg08LendingCatalog,
        openingTransactions: List<Rg08FormalTransactionRecord> = emptyList(),
    ) : this(database, catalog, lendingCatalog, openingTransactions, NO_RG08_FAILURE) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        lendingCatalog: Rg08LendingCatalog,
        openingTransactions: List<Rg08FormalTransactionRecord>,
        failureInjector: Rg08FailureInjector,
    ) : this(database, catalog, lendingCatalog, openingTransactions, failureInjector) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    override fun commit(operation: Rg08Operation): Rg08ExecutionResult = database.transactionWithResult {
        if (operation is Rg08Operation.RetryIdempotentInput) {
            // D-084 generic retry: identity lookup only (the runtime receipt map
            // semantics); nothing is claimed or persisted for the retry itself.
            return@transactionWithResult replayRetry(operation)
        }
        val fingerprint = operationFingerprint(operation)
        database.ledgerQueries.claimRg08Operation(
            operation.ledgerId.value,
            operation.identity.value,
            operation.action.code,
            operationClass(operation),
            fingerprint,
        )
        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
            return@transactionWithResult replay(operation, fingerprint)
        }
        failureInjector.failAt(Rg08FailurePoint.AFTER_CLAIM)

        val before = loadPersistedSnapshot(operation.ledgerId)
        val runtime = Rg08Runtime(catalog, lendingCatalog, before)
        val result = runtime.commit(operation)
        check(result !is Rg08ExecutionResult.RequestIdentityConflict) {
            "newly claimed RG-08 operation returned an identity conflict"
        }
        if (result is Rg08ExecutionResult.Accepted) {
            persistDelta(operation, before, runtime.snapshot())
        }
        failureInjector.failAt(Rg08FailurePoint.AFTER_DELTA)
        finalizeOperation(operation, fingerprint, result)
        result
    }

    fun snapshot(ledgerId: LedgerId): Rg08Snapshot =
        Rg08Runtime(catalog, lendingCatalog, loadPersistedSnapshot(ledgerId)).snapshot()

    private fun operationFingerprint(operation: Rg08Operation): String =
        Rg08Runtime(catalog, lendingCatalog, emptyList()).operationFingerprint(operation)

    private fun operationClass(operation: Rg08Operation): String = when (operation) {
        is Rg08Operation.ValidateLendingEvent -> Rg08Action.VALIDATE_LENDING_EVENT.code
        is Rg08Operation.ValidateLendingSettlement -> Rg08Action.VALIDATE_LENDING_SETTLEMENT.code
        is Rg08Operation.IngestImportedCollectionCandidate -> Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION.code
        is Rg08Operation.RejectIncompleteImportedConfirmation -> Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION.code
        is Rg08Operation.ConfirmImportedCollection -> Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION.code
        is Rg08Operation.AllocateLendingCollection -> Rg08Action.ALLOCATE_LENDING_COLLECTION.code
        is Rg08Operation.MergeImportedEvidence -> Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION.code
        is Rg08Operation.RenameCounterparty -> Rg08Action.VALIDATE_LENDING_EVENT.code
        is Rg08Operation.RetryIdempotentInput -> Rg08Action.RETRY_IDEMPOTENT_INPUT.code
        is Rg08Operation.InvalidInput -> "reject_invalid_rg08_input"
    }

    private fun replayRetry(operation: Rg08Operation.RetryIdempotentInput): Rg08ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg08Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg08ExecutionResult.RequestIdentityConflict
        if (saved.outcome == "PENDING") {
            error("persisted RG-08 operation is still pending")
        }
        val returned = database.ledgerQueries
            .selectRg08ReturnedIds(operation.ledgerId.value, operation.identity.value)
            .executeAsList()
            .map(::restoreReturnedId)
        return when (saved.outcome) {
            "ACCEPTED", "NO_CHANGE" -> Rg08ExecutionResult.NoChange(returned)
            "REJECTED" -> Rg08ExecutionResult.Rejected(
                reason = Rg08RejectionReason.values().single { it.code == saved.reason_code },
                fieldPath = Rg08FieldPath.values().single { it.value == saved.field_path },
            )
            else -> error("unknown RG-08 operation outcome ${saved.outcome}")
        }
    }

    private fun replay(operation: Rg08Operation, fingerprint: String): Rg08ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg08Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg08ExecutionResult.RequestIdentityConflict
        if (
            saved.action != operation.action.code ||
            saved.operation_class != operationClass(operation) ||
            saved.input_fingerprint != fingerprint
        ) {
            return Rg08ExecutionResult.RequestIdentityConflict
        }
        if (saved.outcome == "PENDING") {
            error("persisted RG-08 operation is still pending")
        }
        val returned = database.ledgerQueries
            .selectRg08ReturnedIds(operation.ledgerId.value, operation.identity.value)
            .executeAsList()
            .map(::restoreReturnedId)
        return when (saved.outcome) {
            "ACCEPTED", "NO_CHANGE" -> Rg08ExecutionResult.NoChange(returned)
            "REJECTED" -> Rg08ExecutionResult.Rejected(
                reason = Rg08RejectionReason.values().single { it.code == saved.reason_code },
                fieldPath = Rg08FieldPath.values().single { it.value == saved.field_path },
            )
            else -> error("unknown RG-08 operation outcome ${saved.outcome}")
        }
    }

    private fun restoreReturnedId(row: com.unifiedledger.data.db.SelectRg08ReturnedIds): Rg08ReturnedId =
        when (row.id_kind) {
            "TRANSACTION" -> Rg08ReturnedId.Transaction(TransactionId(row.id_value))
            "VERSION" -> Rg08ReturnedId.Version(TransactionVersionId(row.id_value))
            "POSITION" -> Rg08ReturnedId.Position(row.id_value)
            "SETTLEMENT" -> Rg08ReturnedId.Settlement(row.id_value)
            "COMPONENT" -> Rg08ReturnedId.Component(row.id_value)
            "CANDIDATE" -> Rg08ReturnedId.Candidate(Rg08CandidateId(row.id_value))
            "SOURCE_RECORD" -> Rg08ReturnedId.SourceRecord(Rg08SourceRecordId(row.id_value))
            "EVIDENCE" -> Rg08ReturnedId.Evidence(Rg08EvidenceId(row.id_value))
            "EVIDENCE_LINK" -> Rg08ReturnedId.EvidenceLink(Rg08EvidenceLinkId(row.id_value))
            "TARGET_POSTING" -> Rg08ReturnedId.TargetPosting(PostingId(row.id_value))
            "COUNTERPARTY" -> Rg08ReturnedId.Counterparty(row.id_value)
            "NAME_HISTORY" -> Rg08ReturnedId.NameHistory(row.id_value)
            "REQUEST" -> Rg08ReturnedId.Request(row.id_value)
            else -> error("unknown persisted RG-08 returned id kind ${row.id_kind}")
        }

    private fun finalizeOperation(
        operation: Rg08Operation,
        fingerprint: String,
        result: Rg08ExecutionResult,
    ) {
        val outcome = when (result) {
            is Rg08ExecutionResult.Accepted -> "ACCEPTED"
            is Rg08ExecutionResult.NoChange -> "NO_CHANGE"
            is Rg08ExecutionResult.Rejected -> "REJECTED"
            Rg08ExecutionResult.RequestIdentityConflict -> error("cannot finalize identity conflict")
        }
        val rejected = result as? Rg08ExecutionResult.Rejected
        val changed = database.ledgerQueries.updateRg08OperationResult(
            outcome,
            rejected?.reason?.code,
            rejected?.fieldPath?.value,
            operation.ledgerId.value,
            operation.identity.value,
        ).value
        check(changed == 1L) { "RG-08 operation final result did not update" }
        val returned = when (result) {
            is Rg08ExecutionResult.Accepted -> result.returnedIds
            is Rg08ExecutionResult.NoChange -> result.returnedIds
            else -> emptyList()
        }
        returned.forEachIndexed { index, id ->
            val stored = id.stored()
            database.ledgerQueries.insertRg08ReturnedId(
                operation.ledgerId.value,
                operation.identity.value,
                index.toLong(),
                stored.first,
                stored.second,
            )
        }
        check(fingerprint == database.ledgerQueries
            .selectRg08Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOne().input_fingerprint)
    }

    private fun Rg08ReturnedId.stored(): Pair<String, String> = when (this) {
        is Rg08ReturnedId.Transaction -> "TRANSACTION" to id.value
        is Rg08ReturnedId.Version -> "VERSION" to id.value
        is Rg08ReturnedId.Position -> "POSITION" to id
        is Rg08ReturnedId.Settlement -> "SETTLEMENT" to id
        is Rg08ReturnedId.Component -> "COMPONENT" to id
        is Rg08ReturnedId.Candidate -> "CANDIDATE" to id.value
        is Rg08ReturnedId.SourceRecord -> "SOURCE_RECORD" to id.value
        is Rg08ReturnedId.Evidence -> "EVIDENCE" to id.value
        is Rg08ReturnedId.EvidenceLink -> "EVIDENCE_LINK" to id.value
        is Rg08ReturnedId.TargetPosting -> "TARGET_POSTING" to id.value
        is Rg08ReturnedId.Counterparty -> "COUNTERPARTY" to id
        is Rg08ReturnedId.NameHistory -> "NAME_HISTORY" to id
        is Rg08ReturnedId.Request -> "REQUEST" to id
    }

    private fun ensureOpeningTransactions() {
        if (openingTransactions.isEmpty()) return
        database.transaction {
            val existing = database.ledgerQueries
                .selectRg08FormalTransactions(openingTransactions.first().formalTransaction.transaction.ledgerId.value)
                .executeAsList()
                .mapTo(mutableSetOf()) { it.transaction_id }
            openingTransactions.forEach { record ->
                if (record.formalTransaction.transaction.id.value !in existing) {
                    persistFormalRecord(record)
                }
            }
        }
    }

    private fun persistFormalRecord(record: Rg08FormalTransactionRecord) {
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
        database.ledgerQueries.insertRg08FormalTransactionMetadata(
            formal.transaction.ledgerId.value,
            formal.transaction.id.value,
            null,
            record.createdAtText ?: record.createdAt.toString(),
            record.statisticsAtText ?: formal.versions.last().times.statisticsAt.toString(),
        )
    }

    private fun loadPersistedSnapshot(ledgerId: LedgerId): Rg08Snapshot {
        val ledger = ledgerId.value
        val q = database.ledgerQueries
        val sources = q.selectRg08AllSourceRecords(ledger).executeAsList().map { row ->
            LendingSourceRecord(
                id = row.source_id,
                sourceRecordId = row.source_record_id,
                kind = LendingSourceKind.valueOf(row.kind),
                observedAt = Instant.parse(row.observed_at),
                bookingAt = row.booking_at?.let(Instant::parse),
                valueAt = row.value_at?.let(Instant::parse),
                accountId = row.account_id?.let(::AccountId),
                counterpartyId = row.counterparty_id,
                amountMinor = row.amount_minor,
                currency = currencyOrNull(row.currency_code, row.currency_precision),
                originalSourcePayloadHash = row.original_source_payload_hash,
                immutablePayloadHash = row.immutable_payload_hash,
                mirrorOfSourceId = row.mirror_of_source_id,
            )
        }
        val evidence = q.selectRg08AllEvidence(ledger).executeAsList().map { row ->
            LendingEvidence(
                id = row.evidence_id,
                sourceId = row.source_id,
                type = LendingEvidenceType.valueOf(row.evidence_type),
                observedAt = Instant.parse(row.observed_at),
            )
        }
        val evidenceLinks = q.selectRg08AllEvidenceLinks(ledger).executeAsList().map { row ->
            LendingEvidenceLink(
                id = row.link_id,
                sourceId = row.source_id,
                evidenceId = row.evidence_id,
                role = LendingEvidenceLinkRole.valueOf(row.role),
                targetId = row.target_id,
                status = LendingEvidenceLinkStatus.valueOf(row.status),
            )
        }
        val positionHistory = q.selectRg08AllPositionHistory(ledger)
            .executeAsList().groupBy { it.position_id }
        val positions = q.selectRg08AllPositions(ledger).executeAsList().map { row ->
            LendingPosition(
                id = row.position_id,
                counterpartyId = row.counterparty_id,
                receivableAccountId = AccountId(row.receivable_account_id),
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                principalBalanceMinor = row.principal_balance_minor,
                allocationScope = LendingAllocationScope.valueOf(row.allocation_scope),
                contractAllocationEnabled = row.contract_allocation_enabled == 1L,
                history = positionHistory[row.position_id].orEmpty()
                    .sortedBy { it.history_sequence }
                    .map { historyRow ->
                        LendingPositionHistoryEntry(
                            id = historyRow.history_id,
                            behaviorCode = LendingBehaviorCode.valueOf(historyRow.behavior_code),
                            amountMinor = historyRow.amount_minor,
                            principalBalanceAfterMinor = historyRow.principal_balance_after_minor,
                            transactionId = TransactionId(historyRow.transaction_id),
                            occurredAt = Instant.parse(historyRow.occurred_at),
                        )
                    },
            )
        }
        val settlementComponents = q.selectRg08AllSettlementComponents(ledger)
            .executeAsList().groupBy { it.settlement_id }
        val settlementHistory = q.selectRg08AllSettlementHistory(ledger)
            .executeAsList().groupBy { it.settlement_id }
        val settlements = q.selectRg08AllSettlements(ledger).executeAsList().map { row ->
            LendingSettlement(
                id = row.settlement_id,
                behaviorCode = LendingBehaviorCode.valueOf(row.behavior_code),
                counterpartyId = row.counterparty_id,
                linkedPositionId = row.linked_position_id,
                allocatedLendTransactionId = row.allocated_lend_transaction_id?.let(::TransactionId),
                transactionId = TransactionId(row.transaction_id),
                destinationAccountId = AccountId(row.destination_account_id),
                interestCategoryId = CategoryId(row.interest_category_id),
                totalReceivedMinor = row.total_received_minor,
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                actualReceiptAt = Instant.parse(row.actual_receipt_at),
                confirmedAt = Instant.parse(row.confirmed_at),
                components = settlementComponents[row.settlement_id].orEmpty()
                    .sortedBy { it.component_index }
                    .map { componentRow ->
                        LendingSettlementComponent(
                            id = componentRow.component_id,
                            kind = LendingComponentKind.valueOf(componentRow.kind),
                            amountMinor = componentRow.amount_minor,
                            postingId = componentRow.posting_id?.let(::PostingId),
                        )
                    },
                history = settlementHistory[row.settlement_id].orEmpty()
                    .sortedBy { it.history_sequence }
                    .map { historyRow ->
                        LendingSettlementHistoryEntry(
                            id = historyRow.history_id,
                            status = LendingSettlementStatus.valueOf(historyRow.status),
                            occurredAt = Instant.parse(historyRow.occurred_at),
                            transactionId = TransactionId(historyRow.transaction_id),
                            formalEffectCount = historyRow.formal_effect_count.toInt(),
                        )
                    },
            )
        }
        val candidateHistory = q.selectRg08AllCandidateStatusHistory(ledger)
            .executeAsList().groupBy { it.candidate_id }
        val candidateSources = q.selectRg08AllCandidateSources(ledger)
            .executeAsList().groupBy { it.candidate_id }
        val candidates = q.selectRg08AllCandidates(ledger).executeAsList().map { row ->
            val status = LendingCandidateStatus.valueOf(row.status)
            LendingCandidate(
                id = row.candidate_id,
                type = row.type,
                status = status,
                proposedTotalReceivedMinor = row.proposed_total_received_minor,
                proposedPrincipalAmountMinor = row.proposed_principal_amount_minor,
                proposedInterestAmountMinor = row.proposed_interest_amount_minor,
                proposedFeeAmountMinor = row.proposed_fee_amount_minor,
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                proposedDestinationAccountId = row.proposed_destination_account_id?.let(::AccountId),
                proposedActualReceiptAt = row.proposed_actual_receipt_at?.let(Instant::parse),
                proposedBehaviorCode = row.proposed_behavior_code?.let(LendingBehaviorCode::valueOf),
                proposedCounterpartyId = row.proposed_counterparty_id,
                bankEvidenceProvesComponentSplit = row.bank_evidence_proves_component_split == 1L,
                expectedInterestMayConfirmSplit = row.expected_interest_may_confirm_split == 1L,
                nameMatchMayConfirmCounterparty = row.name_match_may_confirm_counterparty == 1L,
                requiresConfirmation = confirmationGatesFor(status),
                sourceIds = candidateSources[row.candidate_id].orEmpty()
                    .sortedBy { it.source_index }
                    .map { it.source_id },
                ruleVersion = row.rule_version.toInt(),
                confidence = row.confidence,
                statusHistory = candidateHistory[row.candidate_id].orEmpty()
                    .sortedBy { it.status_sequence }
                    .map { historyRow ->
                        LendingCandidateStatusHistoryEntry(
                            id = historyRow.history_id,
                            status = LendingCandidateStatus.valueOf(historyRow.status),
                            occurredAt = Instant.parse(historyRow.occurred_at),
                            formalEffectCount = historyRow.formal_effect_count.toInt(),
                        )
                    },
            )
        }
        val confirmations = q.selectRg08AllConfirmations(ledger).executeAsList().map { row ->
            LendingConfirmationProvenance(
                id = row.confirmation_id,
                confirmationRequestId = row.confirmation_request_id,
                role = LendingConfirmationRole.valueOf(row.role),
                transactionId = TransactionId(row.transaction_id),
                counterpartyId = row.counterparty_id,
                confirmedAt = Instant.parse(row.confirmed_at),
                candidateId = row.candidate_id,
                settlementId = row.settlement_id,
            )
        }
        val auditLinks = q.selectRg08AllAuditLinks(ledger).executeAsList().map { row ->
            LendingAuditLink(
                id = row.audit_link_id,
                kind = LendingAuditLinkKind.valueOf(row.kind),
                fromId = row.from_id,
                toId = row.to_id,
            )
        }
        val postingSemantics = q.selectRg08AllPostingSemantics(ledger).executeAsList()
            .associate { row ->
                row.posting_id to Rg08PostingSemantic(row.role, row.reconciliation_eligible == 1L)
            }
        val nameHistory = q.selectRg08AllNameHistory(ledger).executeAsList()
            .groupBy { it.counterparty_id }
        val counterpartyNames = buildMap {
            nameHistory.forEach { (counterpartyId, rows) ->
                put(
                    counterpartyId,
                    rows.maxBy { it.history_sequence }.display_name,
                )
            }
        }
        return Rg08Snapshot(
            formalTransactions = loadFormalTransactions(ledgerId),
            positions = positions,
            settlements = settlements,
            candidates = candidates,
            confirmations = confirmations,
            sourceRecords = sources,
            evidence = evidence,
            evidenceLinks = evidenceLinks,
            auditLinks = auditLinks,
            postingSemantics = postingSemantics,
            balances = emptyMap(),
            reports = emptyMap(),
            reconciliation = emptyMap(),
            counterpartyNames = counterpartyNames,
        )
    }

    private fun currencyOrNull(code: String?, precision: Long?): CurrencyUnit? {
        if (code == null || precision == null) return null
        return CurrencyUnit(code, precision.toInt())
    }

    private fun loadFormalTransactions(ledgerId: LedgerId): List<Rg08FormalTransactionRecord> {
        val ledger = ledgerId.value
        val metadata = database.ledgerQueries.selectRg08FormalTransactionMetadata(ledger)
            .executeAsList().associateBy { it.transaction_id }
        return database.ledgerQueries.selectRg08FormalTransactions(ledger).executeAsList().map { row ->
            val versions = database.ledgerQueries.selectRg08FormalVersions(ledger, row.transaction_id)
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
                val postings = database.ledgerQueries.selectRg08FormalPostings(ledger, postingSetId.value)
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
                    is DomainResult.Failure -> error("invalid persisted RG-08 posting set $postingSetId")
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
                is DomainResult.Failure -> error("invalid persisted RG-08 formal transaction ${row.transaction_id}")
            }
            val savedMetadata = metadata[row.transaction_id]
                ?: error("missing persisted RG-08 formal transaction metadata for ${row.transaction_id}")
            Rg08FormalTransactionRecord(
                formalTransaction = formal,
                createdAt = Instant.parse(savedMetadata.created_at),
                createdAtText = savedMetadata.created_at,
                statisticsAtText = savedMetadata.effective_at_text,
            )
        }
    }

    private fun persistDelta(
        operation: Rg08Operation,
        before: Rg08Snapshot,
        after: Rg08Snapshot,
    ) {
        val beforeTransactionIds = before.formalTransactions
            .mapTo(mutableSetOf()) { it.formalTransaction.transaction.id.value }
        val newFormal = after.formalTransactions.filter {
            it.formalTransaction.transaction.id.value !in beforeTransactionIds
        }
        newFormal.forEach(::persistFormalRecord)
        persistNewFormalSemantics(operation, newFormal, after)

        persistPositionDelta(operation.ledgerId, before, after)
        persistSettlementDelta(operation.ledgerId, before, after)
        // Source records must be persisted before the candidate delta:
        // rg08_candidate_source carries an immediate FOREIGN KEY onto
        // rg08_source_record, so inserting the candidate (and its source links)
        // first aborted every IngestImportedCollectionCandidate commit with
        // SQLITE_CONSTRAINT_FOREIGNKEY. rg08_evidence and rg08_evidence_link have
        // the same immediate FK onto rg08_source_record and are inserted after
        // this block; mirror sources always reference a pre-existing origin
        // (the runtime rejects a mirror whose origin is absent), so no further
        // ordering is required.
        val beforeSourceIds = before.sourceRecords.mapTo(mutableSetOf()) { it.id }
        after.sourceRecords.filter { it.id !in beforeSourceIds }.forEach { source ->
            database.ledgerQueries.insertRg08SourceRecord(
                operation.ledgerId.value,
                source.id,
                source.sourceRecordId,
                source.kind.name,
                source.observedAt.toString(),
                source.bookingAt?.toString(),
                source.valueAt?.toString(),
                source.accountId?.value,
                source.counterpartyId,
                source.amountMinor,
                source.currency?.code,
                source.currency?.precision?.toLong(),
                source.originalSourcePayloadHash,
                source.immutablePayloadHash,
                source.mirrorOfSourceId,
            )
        }
        persistCandidateDelta(operation.ledgerId, before, after)

        val beforeEvidenceIds = before.evidence.mapTo(mutableSetOf()) { it.id }
        after.evidence.filter { it.id !in beforeEvidenceIds }.forEach { item ->
            database.ledgerQueries.insertRg08Evidence(
                operation.ledgerId.value,
                item.id,
                item.sourceId,
                item.type.name,
                item.observedAt.toString(),
            )
        }

        val beforeLinkIds = before.evidenceLinks.mapTo(mutableSetOf()) { it.id }
        after.evidenceLinks.filter { it.id !in beforeLinkIds }.forEach { link ->
            database.ledgerQueries.insertRg08EvidenceLink(
                operation.ledgerId.value,
                link.id,
                link.sourceId,
                link.evidenceId,
                if (link.role.targetsPosting) "POSTING" else "POSITION",
                link.targetId,
                link.role.name,
                link.status.name,
            )
        }

        val beforeAuditIds = before.auditLinks.mapTo(mutableSetOf()) { it.id }
        after.auditLinks.filter { it.id !in beforeAuditIds }.forEach { audit ->
            database.ledgerQueries.insertRg08AuditLink(
                operation.ledgerId.value,
                audit.id,
                audit.kind.name,
                audit.fromId,
                audit.toId,
            )
        }

        val beforeConfirmationIds = before.confirmations.mapTo(mutableSetOf()) { it.id }
        after.confirmations.filter { it.id !in beforeConfirmationIds }.forEach { confirmation ->
            database.ledgerQueries.insertRg08Confirmation(
                operation.ledgerId.value,
                confirmation.id,
                confirmation.confirmationRequestId,
                confirmation.role.name,
                confirmation.transactionId.value,
                confirmation.counterpartyId,
                confirmation.confirmedAt.toString(),
                confirmation.candidateId,
                confirmation.settlementId,
            )
        }

        persistNameHistoryDelta(operation)

        persistReconciliationTransitions(operation.ledgerId, after)
    }

    private fun persistPositionDelta(
        ledgerId: LedgerId,
        before: Rg08Snapshot,
        after: Rg08Snapshot,
    ) {
        val beforePositions = before.positions.associateBy { it.id }
        after.positions.forEach { position ->
            val old = beforePositions[position.id]
            if (old == null) {
                database.ledgerQueries.insertRg08Position(
                    ledgerId.value,
                    position.id,
                    position.counterpartyId,
                    position.receivableAccountId.value,
                    position.currency.code,
                    position.currency.precision.toLong(),
                    position.principalBalanceMinor,
                    position.allocationScope.name,
                    if (position.contractAllocationEnabled) 1L else 0L,
                )
                position.history.forEachIndexed { index, entry ->
                    database.ledgerQueries.insertRg08PositionHistory(
                        ledgerId.value,
                        position.id,
                        (index + 1).toLong(),
                        entry.id,
                        entry.behaviorCode.name,
                        entry.amountMinor,
                        entry.principalBalanceAfterMinor,
                        entry.transactionId.value,
                        entry.occurredAt.toString(),
                    )
                }
            } else {
                val oldHistoryIds = old.history.mapTo(mutableSetOf()) { it.id }
                position.history.forEachIndexed { index, entry ->
                    if (entry.id !in oldHistoryIds) {
                        database.ledgerQueries.insertRg08PositionHistory(
                            ledgerId.value,
                            position.id,
                            (index + 1).toLong(),
                            entry.id,
                            entry.behaviorCode.name,
                            entry.amountMinor,
                            entry.principalBalanceAfterMinor,
                            entry.transactionId.value,
                            entry.occurredAt.toString(),
                        )
                    }
                }
                if (position.principalBalanceMinor != old.principalBalanceMinor) {
                    database.ledgerQueries.updateRg08PositionBalance(
                        position.principalBalanceMinor,
                        ledgerId.value,
                        position.id,
                    )
                    check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                        "RG-08 position current projection was not advanced"
                    }
                }
            }
        }
    }

    private fun persistSettlementDelta(
        ledgerId: LedgerId,
        before: Rg08Snapshot,
        after: Rg08Snapshot,
    ) {
        val beforeSettlementIds = before.settlements.mapTo(mutableSetOf()) { it.id }
        after.settlements.filter { it.id !in beforeSettlementIds }.forEach { settlement ->
            database.ledgerQueries.insertRg08Settlement(
                ledgerId.value,
                settlement.id,
                settlement.behaviorCode.name,
                settlement.counterpartyId,
                settlement.linkedPositionId,
                settlement.allocatedLendTransactionId?.value,
                settlement.transactionId.value,
                settlement.destinationAccountId.value,
                settlement.interestCategoryId.value,
                settlement.totalReceivedMinor,
                settlement.currency.code,
                settlement.currency.precision.toLong(),
                settlement.actualReceiptAt.toString(),
                settlement.confirmedAt.toString(),
            )
            settlement.components.forEachIndexed { index, component ->
                database.ledgerQueries.insertRg08SettlementComponent(
                    ledgerId.value,
                    settlement.id,
                    index.toLong(),
                    component.id,
                    component.kind.name,
                    component.amountMinor,
                    component.postingId?.value,
                )
            }
            settlement.history.forEachIndexed { index, entry ->
                database.ledgerQueries.insertRg08SettlementHistory(
                    ledgerId.value,
                    settlement.id,
                    (index + 1).toLong(),
                    entry.id,
                    entry.status.name,
                    entry.occurredAt.toString(),
                    entry.transactionId.value,
                    entry.formalEffectCount.toLong(),
                )
            }
        }
    }

    private fun persistCandidateDelta(
        ledgerId: LedgerId,
        before: Rg08Snapshot,
        after: Rg08Snapshot,
    ) {
        val beforeCandidates = before.candidates.associateBy { it.id }
        after.candidates.forEach { candidate ->
            val old = beforeCandidates[candidate.id]
            if (old == null) {
                database.ledgerQueries.insertRg08Candidate(
                    ledgerId.value,
                    candidate.id,
                    candidate.type,
                    candidate.status.name,
                    candidate.proposedTotalReceivedMinor,
                    candidate.proposedPrincipalAmountMinor,
                    candidate.proposedInterestAmountMinor,
                    candidate.proposedFeeAmountMinor,
                    candidate.currency.code,
                    candidate.currency.precision.toLong(),
                    candidate.proposedDestinationAccountId?.value,
                    candidate.proposedActualReceiptAt?.toString(),
                    candidate.proposedBehaviorCode?.name,
                    candidate.proposedCounterpartyId,
                    if (candidate.bankEvidenceProvesComponentSplit) 1L else 0L,
                    if (candidate.expectedInterestMayConfirmSplit) 1L else 0L,
                    if (candidate.nameMatchMayConfirmCounterparty) 1L else 0L,
                    candidate.ruleVersion.toLong(),
                    candidate.confidence,
                )
                candidate.statusHistory.forEachIndexed { index, entry ->
                    database.ledgerQueries.insertRg08CandidateStatusHistory(
                        ledgerId.value,
                        candidate.id,
                        (index + 1).toLong(),
                        entry.id,
                        entry.status.name,
                        entry.occurredAt.toString(),
                        entry.formalEffectCount.toLong(),
                    )
                }
                candidate.sourceIds.forEachIndexed { index, sourceId ->
                    database.ledgerQueries.insertRg08CandidateSource(
                        ledgerId.value,
                        candidate.id,
                        index.toLong(),
                        sourceId,
                    )
                }
            } else {
                val oldHistoryIds = old.statusHistory.mapTo(mutableSetOf()) { it.id }
                candidate.statusHistory.forEachIndexed { index, entry ->
                    if (entry.id !in oldHistoryIds) {
                        database.ledgerQueries.insertRg08CandidateStatusHistory(
                            ledgerId.value,
                            candidate.id,
                            (index + 1).toLong(),
                            entry.id,
                            entry.status.name,
                            entry.occurredAt.toString(),
                            entry.formalEffectCount.toLong(),
                        )
                    }
                }
                if (candidate.status != old.status) {
                    database.ledgerQueries.updateRg08CandidateConfirmed(
                        candidate.status.name,
                        candidate.proposedPrincipalAmountMinor,
                        candidate.proposedInterestAmountMinor,
                        candidate.proposedFeeAmountMinor,
                        candidate.proposedDestinationAccountId?.value,
                        candidate.proposedActualReceiptAt?.toString(),
                        candidate.proposedBehaviorCode?.name,
                        candidate.proposedCounterpartyId,
                        ledgerId.value,
                        candidate.id,
                    )
                    check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                        "RG-08 candidate lifecycle was not advanced"
                    }
                }
            }
        }
    }

    private fun persistNameHistoryDelta(operation: Rg08Operation) {
        if (operation !is Rg08Operation.RenameCounterparty) return
        val ledger = operation.ledgerId.value
        val sequence = (database.ledgerQueries.selectRg08AllNameHistory(ledger)
            .executeAsList()
            .filter { it.counterparty_id == operation.input.counterpartyId }
            .maxOfOrNull { it.history_sequence } ?: 0L) + 1L
        database.ledgerQueries.insertRg08NameHistory(
            ledger,
            operation.input.counterpartyId,
            sequence,
            operation.input.nameHistoryId,
            operation.input.newDisplayName,
        )
    }

    private fun persistNewFormalSemantics(
        operation: Rg08Operation,
        newFormal: List<Rg08FormalTransactionRecord>,
        after: Rg08Snapshot,
    ) {
        newFormal.forEach { record ->
            record.formalTransaction.currentPostings().forEach { posting ->
                val semantic = after.postingSemantics[posting.id.value] ?: return@forEach
                database.ledgerQueries.insertRg08PostingSemantic(
                    operation.ledgerId.value,
                    posting.id.value,
                    semantic.role,
                    if (semantic.reconciliationEligible) 1L else 0L,
                )
                if (semantic.reconciliationEligible) {
                    database.ledgerQueries.insertRg08PostingReconciliation(
                        operation.ledgerId.value,
                        posting.id.value,
                        "PENDING",
                        1,
                    )
                    database.ledgerQueries.insertRg08ReconciliationHistory(
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

    /**
     * RG-08 derives the reconciliation status from evidence links, so a posting
     * whose link is created MATCHED in the same operation must advance the
     * persisted current row PENDING -> MATCHED (with its evidence link) in the
     * same commit; the runtime-derived status and the stored projection then
     * agree on reopen.
     */
    private fun persistReconciliationTransitions(
        ledgerId: LedgerId,
        after: Rg08Snapshot,
    ) {
        val current = database.ledgerQueries.selectRg08AllPostingReconciliations(ledgerId.value)
            .executeAsList().associateBy { it.posting_id }
        val histories = database.ledgerQueries.selectRg08AllReconciliationHistory(ledgerId.value)
            .executeAsList().groupBy { it.posting_id }
        after.reconciliation.forEach { (postingId, currentStatus) ->
            if (currentStatus != "matched") return@forEach
            val row = current[postingId] ?: return@forEach
            if (row.status != "PENDING") return@forEach
            val link = after.evidenceLinks.firstOrNull {
                it.targetId == postingId &&
                    it.role in RECONCILIATION_POSTING_ROLES &&
                    it.status == LendingEvidenceLinkStatus.MATCHED
            } ?: error("RG-08 matched reconciliation transition lacks evidence link")
            val sequence = (histories[postingId].orEmpty().maxOfOrNull { it.status_sequence } ?: 0L) + 1L
            check(sequence == row.latest_sequence + 1L) { "RG-08 reconciliation sequence mismatch" }
            database.ledgerQueries.insertRg08ReconciliationHistory(
                ledgerId.value,
                postingId,
                sequence,
                "MATCHED",
                link.id,
            )
            database.ledgerQueries.updateRg08PostingReconciliation(
                "MATCHED",
                sequence,
                ledgerId.value,
                postingId,
            )
            check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                "RG-08 reconciliation current row was not advanced"
            }
        }
    }

    private companion object {
        val RECONCILIATION_POSTING_ROLES = setOf(
            LendingEvidenceLinkRole.DESTINATION_ASSET_POSTING,
            LendingEvidenceLinkRole.FUNDING_ASSET_POSTING,
        )
    }
}
