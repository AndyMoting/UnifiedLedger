package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.Rg12Action
import com.unifiedledger.application.Rg12CommitPort
import com.unifiedledger.application.Rg12ConsumptionRecord
import com.unifiedledger.application.Rg12ExecutionResult
import com.unifiedledger.application.Rg12FieldPath
import com.unifiedledger.application.Rg12FormalTransactionRecord
import com.unifiedledger.application.Rg12Operation
import com.unifiedledger.application.Rg12PostingSemantic
import com.unifiedledger.application.Rg12RejectionReason
import com.unifiedledger.application.Rg12ReturnedId
import com.unifiedledger.application.Rg12Runtime
import com.unifiedledger.application.Rg12Snapshot
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.ExplicitOperationConfirmation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OperationSubjectRef
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingReconciliation
import com.unifiedledger.domain.PostingReconciliationStatus
import com.unifiedledger.domain.PostingReplacement
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.ReconciliationEffect
import com.unifiedledger.domain.ReconciliationMatch
import com.unifiedledger.domain.ReconciliationMatchReason
import com.unifiedledger.domain.ReconciliationMatchStatus
import com.unifiedledger.domain.ReconciliationMatchStatusEntry
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createPostingReconciliation
import com.unifiedledger.domain.createReconciliationMatch
import kotlin.time.Instant

internal enum class Rg12FailurePoint {
    AFTER_CLAIM,
    AFTER_DELTA,
}

internal fun interface Rg12FailureInjector {
    fun failAt(point: Rg12FailurePoint)
}

private val NO_RG12_FAILURE = Rg12FailureInjector { }

/**
 * SQLDelight owner for the approved RG-12 reconciliation correction operation boundary
 * (D-085). The store follows the RG-11 owner pattern: every commit claims the operation
 * identity with its fingerprint, replays an already-finalized identity, and persists the
 * runtime delta inside one transaction. `RetryIdempotentInput` replays by identity lookup
 * only, exactly like the runtime receipt map; a retry of an unknown identity is a conflict.
 *
 * The formal chain is shared: the corrected v2 version goes through the shared
 * `posting_set` / `posting` / `transaction_version` inserts with a fresh posting set, its
 * write-once `confirmation_id` is written into the shared column (v16 additive column,
 * guard extended by the v16 -> v17 migration to also own EXPENSE transactions) and the
 * per-version creation times live in the RG-12 exclusive version metadata table. The
 * RG-12 exclusive tables hold the operation boundary, semantics, reconciliation matches
 * (append-only status history), posting reconciliation facts (write-once, match-consistent),
 * posting replacement audit links, confirmations, consumption records and the day-period
 * report registry. Balances, reports, domain entities and the reconciliation summary are
 * derived by the runtime and never stored.
 */
class SqlDelightRg12Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val openingState: Rg12Snapshot,
    private val failureInjector: Rg12FailureInjector,
) : Rg12CommitPort {
    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingTransactions: List<Rg12FormalTransactionRecord> = emptyList(),
    ) : this(database, catalog, openingSnapshot(openingTransactions), NO_RG12_FAILURE) {
        configureSqliteConnection(driver)
        ensureOpeningState()
    }

    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingState: Rg12Snapshot,
    ) : this(database, catalog, openingState, NO_RG12_FAILURE) {
        configureSqliteConnection(driver)
        ensureOpeningState()
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingState: Rg12Snapshot,
        failureInjector: Rg12FailureInjector,
    ) : this(database, catalog, openingState, failureInjector) {
        configureSqliteConnection(driver)
        ensureOpeningState()
    }

    override fun commit(operation: Rg12Operation): Rg12ExecutionResult = database.transactionWithResult {
        if (operation is Rg12Operation.RetryIdempotentInput) {
            return@transactionWithResult replayRetry(operation)
        }
        val fingerprint = operationFingerprint(operation)
        database.ledgerQueries.claimRg12Operation(
            operation.ledgerId.value,
            operation.identity.value,
            operation.action.code,
            operationClass(operation),
            fingerprint,
        )
        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
            return@transactionWithResult replay(operation, fingerprint)
        }
        failureInjector.failAt(Rg12FailurePoint.AFTER_CLAIM)

        val before = loadPersistedSnapshot(operation.ledgerId)
        val runtime = Rg12Runtime(catalog, before)
        val result = runtime.commit(operation)
        check(result !is Rg12ExecutionResult.RequestIdentityConflict) {
            "newly claimed RG-12 operation returned an identity conflict"
        }
        if (result is Rg12ExecutionResult.Accepted) {
            persistDelta(operation, before, runtime.snapshot())
        }
        failureInjector.failAt(Rg12FailurePoint.AFTER_DELTA)
        finalizeOperation(operation, fingerprint, result)
        result
    }

    fun snapshot(ledgerId: LedgerId): Rg12Snapshot =
        Rg12Runtime(catalog, loadPersistedSnapshot(ledgerId)).snapshot()

    private fun operationFingerprint(operation: Rg12Operation): String =
        Rg12Runtime(catalog, emptyList()).operationFingerprint(operation)

    private fun operationClass(operation: Rg12Operation): String = when (operation) {
        is Rg12Operation.CorrectTransactionVersion -> Rg12Action.CORRECT_TRANSACTION_VERSION.code
        is Rg12Operation.RetryIdempotentInput -> operation.action.code
        is Rg12Operation.InvalidInput -> "reject_invalid_rg12_input"
    }

    private fun replayRetry(operation: Rg12Operation.RetryIdempotentInput): Rg12ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg12Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg12ExecutionResult.RequestIdentityConflict
        if (saved.outcome == "PENDING") {
            error("persisted RG-12 operation is still pending")
        }
        return replayOutcome(saved)
    }

    private fun replay(operation: Rg12Operation, fingerprint: String): Rg12ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg12Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg12ExecutionResult.RequestIdentityConflict
        if (
            saved.action != operation.action.code ||
            saved.operation_class != operationClass(operation) ||
            saved.input_fingerprint != fingerprint
        ) {
            return Rg12ExecutionResult.RequestIdentityConflict
        }
        if (saved.outcome == "PENDING") {
            error("persisted RG-12 operation is still pending")
        }
        return replayOutcome(saved)
    }

    private fun replayOutcome(saved: com.unifiedledger.data.db.Rg12_operation): Rg12ExecutionResult {
        val returned = database.ledgerQueries
            .selectRg12ReturnedIds(saved.ledger_id, saved.identity_value)
            .executeAsList()
            .map(::restoreReturnedId)
        return when (saved.outcome) {
            "ACCEPTED", "NO_CHANGE" -> Rg12ExecutionResult.NoChange(returned)
            "REJECTED" -> Rg12ExecutionResult.Rejected(
                reason = Rg12RejectionReason.values().single { it.code == saved.reason_code },
                fieldPath = Rg12FieldPath(
                    requireNotNull(saved.field_path) { "persisted RG-12 rejection without a field path" },
                ),
            )
            else -> error("unknown RG-12 operation outcome ${saved.outcome}")
        }
    }

    private fun restoreReturnedId(row: com.unifiedledger.data.db.SelectRg12ReturnedIds): Rg12ReturnedId =
        when (row.id_kind) {
            "TRANSACTION" -> Rg12ReturnedId.Transaction(TransactionId(row.id_value))
            "VERSION" -> Rg12ReturnedId.Version(TransactionVersionId(row.id_value))
            "POSTING_SET" -> Rg12ReturnedId.PostingSet(PostingSetId(row.id_value))
            "POSTING" -> Rg12ReturnedId.Posting(PostingId(row.id_value))
            "REPLACEMENT" -> Rg12ReturnedId.Replacement(row.id_value)
            "CONFIRMATION" -> Rg12ReturnedId.Confirmation(row.id_value)
            "MATCH" -> Rg12ReturnedId.Match(row.id_value)
            "REQUEST" -> Rg12ReturnedId.Request(row.id_value)
            else -> error("unknown persisted RG-12 returned id kind ${row.id_kind}")
        }

    private fun finalizeOperation(
        operation: Rg12Operation,
        fingerprint: String,
        result: Rg12ExecutionResult,
    ) {
        val outcome = when (result) {
            is Rg12ExecutionResult.Accepted -> "ACCEPTED"
            is Rg12ExecutionResult.NoChange -> "NO_CHANGE"
            is Rg12ExecutionResult.Rejected -> "REJECTED"
            Rg12ExecutionResult.RequestIdentityConflict -> error("cannot finalize identity conflict")
        }
        val rejected = result as? Rg12ExecutionResult.Rejected
        val changed = database.ledgerQueries.updateRg12OperationResult(
            outcome,
            rejected?.reason?.code,
            rejected?.fieldPath?.value,
            operation.ledgerId.value,
            operation.identity.value,
        ).value
        check(changed == 1L) { "RG-12 operation final result did not update" }
        val returned = when (result) {
            is Rg12ExecutionResult.Accepted -> result.returnedIds
            is Rg12ExecutionResult.NoChange -> result.returnedIds
            else -> emptyList()
        }
        returned.forEachIndexed { index, id ->
            val stored = id.stored()
            database.ledgerQueries.insertRg12ReturnedId(
                operation.ledgerId.value,
                operation.identity.value,
                index.toLong(),
                stored.first,
                stored.second,
            )
        }
        check(fingerprint == database.ledgerQueries
            .selectRg12Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOne().input_fingerprint)
    }

    private fun Rg12ReturnedId.stored(): Pair<String, String> = when (this) {
        is Rg12ReturnedId.Transaction -> "TRANSACTION" to id.value
        is Rg12ReturnedId.Version -> "VERSION" to id.value
        is Rg12ReturnedId.PostingSet -> "POSTING_SET" to id.value
        is Rg12ReturnedId.Posting -> "POSTING" to id.value
        is Rg12ReturnedId.Replacement -> "REPLACEMENT" to id
        is Rg12ReturnedId.Confirmation -> "CONFIRMATION" to id
        is Rg12ReturnedId.Match -> "MATCH" to id
        is Rg12ReturnedId.Request -> "REQUEST" to id
    }

    // ------------------------------------------------------------------ opening baseline

    /**
     * Seeds the opening baseline once: the frozen fixture baseline (transaction, semantics,
     * matches, reconciliation facts, consumption record and report periods) is persisted when
     * the store is constructed. Every insert is diffed against the persisted rows so
     * constructing a store on an existing database is a no-op. The ledger of the baseline is
     * the ledger of its formal transactions.
     */
    private fun ensureOpeningState() {
        val ledgerId = openingState.formalTransactions
            .firstOrNull()?.formalTransaction?.transaction?.ledgerId ?: return
        database.transaction {
            val existingTransactions = database.ledgerQueries
                .selectRg12FormalTransactions(ledgerId.value)
                .executeAsList()
                .mapTo(mutableSetOf()) { it.transaction_id }
            openingState.formalTransactions.forEach { record ->
                if (record.formalTransaction.transaction.id.value !in existingTransactions) {
                    persistFormalRecord(record)
                }
            }
            persistOpeningSemantics(ledgerId)
            persistOpeningMatches(ledgerId)
            persistOpeningFacts(ledgerId)
            persistOpeningLinks(ledgerId)
            persistOpeningConfirmations(ledgerId)
            persistOpeningConsumptionRecords(ledgerId)
            persistOpeningPeriods(ledgerId)
        }
    }

    private fun persistOpeningSemantics(ledgerId: LedgerId) {
        val existing = database.ledgerQueries
            .selectRg12AllPostingSemantics(ledgerId.value)
            .executeAsList().mapTo(mutableSetOf()) { it.posting_id }
        openingState.postingSemantics.filterKeys { it !in existing }.forEach { (postingId, semantic) ->
            database.ledgerQueries.insertRg12PostingSemantic(
                ledgerId.value,
                postingId,
                requireNotNull(semantic.role) { "RG-12 opening posting semantic without role" },
                if (semantic.reconciliationEligible) 1L else 0L,
                semantic.categoryId?.value,
            )
        }
    }

    private fun persistOpeningMatches(ledgerId: LedgerId) {
        val existing = database.ledgerQueries
            .selectRg12AllMatches(ledgerId.value)
            .executeAsList().mapTo(mutableSetOf()) { it.match_id }
        openingState.reconciliationMatches.filter { it.id !in existing }.forEach { match ->
            database.ledgerQueries.insertRg12ReconciliationMatch(
                ledgerId.value,
                match.id,
                match.postingId.value,
                match.evidenceId,
            )
            match.statusHistory.forEach { entry ->
                database.ledgerQueries.insertRg12ReconciliationMatchHistory(
                    ledgerId.value,
                    match.id,
                    entry.sequence.toLong(),
                    entry.id,
                    entry.status.name,
                    entry.at.toString(),
                    entry.reason.name,
                )
            }
        }
    }

    private fun persistOpeningFacts(ledgerId: LedgerId) {
        val existing = database.ledgerQueries
            .selectRg12AllPostingReconciliations(ledgerId.value)
            .executeAsList().mapTo(mutableSetOf()) { it.reconciliation_id }
        openingState.postingReconciliations.filter { it.id !in existing }.forEach { fact ->
            database.ledgerQueries.insertRg12PostingReconciliation(
                ledgerId.value,
                fact.id,
                fact.postingId.value,
                fact.status.name,
            )
        }
    }

    private fun persistOpeningLinks(ledgerId: LedgerId) {
        val existing = database.ledgerQueries
            .selectRg12AllPostingReplacements(ledgerId.value)
            .executeAsList().mapTo(mutableSetOf()) { it.replacement_id }
        openingState.postingReplacements.filter { it.id !in existing }.forEach { link ->
            database.ledgerQueries.insertRg12PostingReplacement(
                ledgerId.value,
                link.id,
                link.fromPostingId.value,
                link.toPostingId.value,
                link.reconciliationEffect.name,
            )
        }
    }

    private fun persistOpeningConfirmations(ledgerId: LedgerId) {
        val existing = database.ledgerQueries
            .selectRg12AllConfirmations(ledgerId.value)
            .executeAsList().mapTo(mutableSetOf()) { it.confirmation_id }
        openingState.confirmations.filter { it.id !in existing }.forEach { confirmation ->
            database.ledgerQueries.insertRg12Confirmation(
                ledgerId.value,
                confirmation.id,
                confirmation.operationId,
                confirmation.subject.kind,
                confirmation.subject.id,
                confirmation.createdAt.toString(),
                "{}",
            )
        }
    }

    private fun persistOpeningConsumptionRecords(ledgerId: LedgerId) {
        val existing = database.ledgerQueries
            .selectRg12AllConsumptionRecords(ledgerId.value)
            .executeAsList().mapTo(mutableSetOf()) { it.record_id }
        openingState.consumptionRecords.filter { it.id !in existing }.forEach { record ->
            persistConsumptionRecord(ledgerId.value, record)
        }
    }

    private fun persistOpeningPeriods(ledgerId: LedgerId) {
        val existing = database.ledgerQueries
            .selectRg12AllReportPeriods(ledgerId.value)
            .executeAsList().mapTo(mutableSetOf()) { it.period }
        openingState.reportPeriods.filter { it !in existing }.forEach { period ->
            database.ledgerQueries.insertRg12ReportPeriod(ledgerId.value, period)
        }
    }

    // ------------------------------------------------------------------ formal chain

    private fun persistFormalRecord(record: Rg12FormalTransactionRecord) {
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
            record.versionCreatedAtTexts[version.id]?.let { createdAt ->
                database.ledgerQueries.insertRg12TransactionVersionMetadata(
                    formal.transaction.ledgerId.value,
                    version.transactionId.value,
                    version.id.value,
                    createdAt,
                )
            }
            record.versionConfirmationIds[version.id]?.let { confirmationId ->
                database.ledgerQueries.updateTransactionVersionConfirmationId(
                    confirmationId,
                    formal.transaction.ledgerId.value,
                    version.id.value,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                    "RG-12 opening version confirmation was not persisted"
                }
            }
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
        database.ledgerQueries.insertRg12FormalTransactionMetadata(
            formal.transaction.ledgerId.value,
            formal.transaction.id.value,
            record.createdAtText ?: record.createdAt.toString(),
            record.statisticsAtText ?: formal.versions.last().times.statisticsAt.toString(),
        )
    }

    /**
     * `correct_transaction_version` appends a version to an existing formal transaction with
     * a fresh posting set and full replacement postings: persist the new posting set and its
     * postings, the new version row with its per-version creation time and write-once
     * confirmation id, then advance the current version and the record's statistics time
     * text when the runtime changed them.
     */
    private fun persistAppendedFormalDelta(
        operation: Rg12Operation,
        before: Rg12Snapshot,
        after: Rg12Snapshot,
    ) {
        val beforeVersionIds = before.formalTransactions
            .flatMapTo(mutableSetOf()) { record -> record.formalTransaction.versions.map { it.id.value } }
        val beforePostingSetIds = before.formalTransactions
            .flatMapTo(mutableSetOf()) { record -> record.formalTransaction.postingSets.map { it.id.value } }
        val beforeRecords = before.formalTransactions.associateBy { it.formalTransaction.transaction.id.value }
        after.formalTransactions.forEach { record ->
            val transactionId = record.formalTransaction.transaction.id.value
            val oldRecord = beforeRecords[transactionId] ?: return@forEach
            val formal = record.formalTransaction
            formal.postingSets.forEach { postingSet ->
                if (postingSet.id.value !in beforePostingSetIds) {
                    database.ledgerQueries.insertPostingSet(
                        postingSet.id.value,
                        formal.transaction.ledgerId.value,
                    )
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
            }
            formal.versions.forEach { version ->
                if (version.id.value !in beforeVersionIds) {
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
                    record.versionCreatedAtTexts[version.id]?.let { createdAt ->
                        database.ledgerQueries.insertRg12TransactionVersionMetadata(
                            formal.transaction.ledgerId.value,
                            version.transactionId.value,
                            version.id.value,
                            createdAt,
                        )
                    }
                    record.versionConfirmationIds[version.id]?.let { confirmationId ->
                        database.ledgerQueries.updateTransactionVersionConfirmationId(
                            confirmationId,
                            formal.transaction.ledgerId.value,
                            version.id.value,
                        )
                        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                            "RG-12 corrected version confirmation was not persisted"
                        }
                    }
                }
            }
            if (
                formal.transaction.currentVersionId != oldRecord.formalTransaction.transaction.currentVersionId
            ) {
                database.ledgerQueries.updateCurrentVersion(
                    formal.transaction.currentVersionId.value,
                    transactionId,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                    "RG-12 current version was not advanced"
                }
            }
            val statisticsAtText = record.statisticsAtText
                ?: formal.versions
                    .first { it.id == formal.transaction.currentVersionId }
                    .times.statisticsAt.toString()
            if (statisticsAtText != oldRecord.statisticsAtText) {
                database.ledgerQueries.updateRg12FormalTransactionStatisticsAtText(
                    statisticsAtText,
                    operation.ledgerId.value,
                    transactionId,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                    "RG-12 statistics time text was not advanced"
                }
            }
        }
    }

    // ------------------------------------------------------------------ delta

    private fun persistDelta(
        operation: Rg12Operation,
        before: Rg12Snapshot,
        after: Rg12Snapshot,
    ) {
        val beforeTransactionIds = before.formalTransactions
            .mapTo(mutableSetOf()) { it.formalTransaction.transaction.id.value }
        val newFormal = after.formalTransactions.filter {
            it.formalTransaction.transaction.id.value !in beforeTransactionIds
        }
        newFormal.forEach(::persistFormalRecord)
        persistAppendedFormalDelta(operation, before, after)

        val beforeSemanticIds = before.postingSemantics.keys
        after.postingSemantics.filterKeys { it !in beforeSemanticIds }.forEach { (postingId, semantic) ->
            database.ledgerQueries.insertRg12PostingSemantic(
                operation.ledgerId.value,
                postingId,
                requireNotNull(semantic.role) { "RG-12 persisted posting semantic without role" },
                if (semantic.reconciliationEligible) 1L else 0L,
                semantic.categoryId?.value,
            )
        }

        val beforeMatches = before.reconciliationMatches.associateBy { it.id }
        after.reconciliationMatches.forEach { match ->
            val old = beforeMatches[match.id]
            if (old == null) {
                database.ledgerQueries.insertRg12ReconciliationMatch(
                    operation.ledgerId.value,
                    match.id,
                    match.postingId.value,
                    match.evidenceId,
                )
            }
            val beforeEntryIds = old?.statusHistory
                ?.mapTo(mutableSetOf()) { it.id }.orEmpty()
            match.statusHistory.filter { it.id !in beforeEntryIds }.forEach { entry ->
                database.ledgerQueries.insertRg12ReconciliationMatchHistory(
                    operation.ledgerId.value,
                    match.id,
                    entry.sequence.toLong(),
                    entry.id,
                    entry.status.name,
                    entry.at.toString(),
                    entry.reason.name,
                )
            }
        }

        val beforeFactIds = before.postingReconciliations.mapTo(mutableSetOf()) { it.id }
        after.postingReconciliations.filter { it.id !in beforeFactIds }.forEach { fact ->
            database.ledgerQueries.insertRg12PostingReconciliation(
                operation.ledgerId.value,
                fact.id,
                fact.postingId.value,
                fact.status.name,
            )
        }

        val beforeLinkIds = before.postingReplacements.mapTo(mutableSetOf()) { it.id }
        after.postingReplacements.filter { it.id !in beforeLinkIds }.forEach { link ->
            database.ledgerQueries.insertRg12PostingReplacement(
                operation.ledgerId.value,
                link.id,
                link.fromPostingId.value,
                link.toPostingId.value,
                link.reconciliationEffect.name,
            )
        }

        val beforeConfirmationIds = before.confirmations.mapTo(mutableSetOf()) { it.id }
        after.confirmations.filter { it.id !in beforeConfirmationIds }.forEach { confirmation ->
            database.ledgerQueries.insertRg12Confirmation(
                operation.ledgerId.value,
                confirmation.id,
                confirmation.operationId,
                confirmation.subject.kind,
                confirmation.subject.id,
                confirmation.createdAt.toString(),
                "{}",
            )
        }

        val beforeConsumptionIds = before.consumptionRecords.mapTo(mutableSetOf()) { it.id }
        after.consumptionRecords.filter { it.id !in beforeConsumptionIds }.forEach { record ->
            persistConsumptionRecord(operation.ledgerId.value, record)
        }

        val beforePeriods = before.reportPeriods.toMutableSet()
        after.reportPeriods.filter { it !in beforePeriods }.forEach { period ->
            database.ledgerQueries.insertRg12ReportPeriod(operation.ledgerId.value, period)
        }
    }

    private fun persistConsumptionRecord(ledger: String, record: Rg12ConsumptionRecord) {
        database.ledgerQueries.insertRg12ConsumptionRecord(
            ledger,
            record.id,
            record.expensePostingId.value,
            record.categoryId?.value,
            record.amountText,
            record.currency.code,
            record.currency.precision.toLong(),
            record.statisticsAtText,
        )
    }

    // ------------------------------------------------------------------ snapshot load

    private fun loadPersistedSnapshot(ledgerId: LedgerId): Rg12Snapshot {
        val ledger = ledgerId.value
        val q = database.ledgerQueries
        val postingSemantics = q.selectRg12AllPostingSemantics(ledger).executeAsList().associate { row ->
            row.posting_id to Rg12PostingSemantic(
                role = row.role,
                reconciliationEligible = row.reconciliation_eligible == 1L,
                categoryId = row.category_id?.let { CategoryId(it) },
            )
        }
        val reconciliationMatches = q.selectRg12AllMatches(ledger).executeAsList().map { row ->
            val history = q.selectRg12MatchHistory(ledger, row.match_id).executeAsList().map { entry ->
                ReconciliationMatchStatusEntry(
                    id = entry.entry_id,
                    sequence = entry.entry_sequence.toInt(),
                    status = ReconciliationMatchStatus.valueOf(entry.status),
                    at = Instant.parse(entry.entry_at),
                    reason = ReconciliationMatchReason.valueOf(entry.reason),
                )
            }
            when (val created = createReconciliationMatch(
                row.match_id,
                PostingId(row.posting_id),
                row.evidence_id,
                history,
            )) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> error("invalid persisted RG-12 reconciliation match ${row.match_id}")
            }
        }
        val postingReconciliations = q.selectRg12AllPostingReconciliations(ledger).executeAsList().map { row ->
            when (val created = createPostingReconciliation(
                row.reconciliation_id,
                PostingId(row.posting_id),
                PostingReconciliationStatus.valueOf(row.status),
            )) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> error("invalid persisted RG-12 posting reconciliation ${row.reconciliation_id}")
            }
        }
        val postingReplacements = q.selectRg12AllPostingReplacements(ledger).executeAsList().map { row ->
            PostingReplacement(
                id = row.replacement_id,
                fromPostingId = PostingId(row.from_posting_id),
                toPostingId = PostingId(row.to_posting_id),
                reconciliationEffect = ReconciliationEffect.valueOf(row.reconciliation_effect),
            )
        }
        val confirmations = q.selectRg12AllConfirmations(ledger).executeAsList().map { row ->
            ExplicitOperationConfirmation(
                id = row.confirmation_id,
                operationId = row.operation_id,
                subject = OperationSubjectRef(kind = row.subject_kind, id = row.subject_id),
                createdAt = Instant.parse(row.confirmed_at),
                payload = emptyMap(),
            )
        }
        val consumptionRecords = q.selectRg12AllConsumptionRecords(ledger).executeAsList().map { row ->
            Rg12ConsumptionRecord(
                id = row.record_id,
                expensePostingId = PostingId(row.expense_posting_id),
                categoryId = row.category_id?.let { CategoryId(it) },
                amountText = row.amount_text,
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                statisticsAtText = row.statistics_at_text,
            )
        }
        val reportPeriods = q.selectRg12AllReportPeriods(ledger).executeAsList().map { it.period }
        return Rg12Snapshot(
            formalTransactions = loadFormalTransactions(ledgerId),
            postingSemantics = postingSemantics,
            reconciliationMatches = reconciliationMatches,
            postingReconciliations = postingReconciliations,
            postingReplacements = postingReplacements,
            confirmations = confirmations,
            consumptionRecords = consumptionRecords,
            domainEntities = emptyList(),
            reconciliationSummary = emptyMap(),
            balances = emptyMap(),
            reports = emptyMap(),
            reportPeriods = reportPeriods,
        )
    }

    private fun loadFormalTransactions(ledgerId: LedgerId): List<Rg12FormalTransactionRecord> {
        val ledger = ledgerId.value
        val metadata = database.ledgerQueries.selectRg12FormalTransactionMetadata(ledger)
            .executeAsList().associateBy { it.transaction_id }
        val versionCreatedAtTexts = database.ledgerQueries.selectRg12TransactionVersionMetadata(ledger)
            .executeAsList().associate { TransactionVersionId(it.version_id) to it.created_at }
        return database.ledgerQueries.selectRg12FormalTransactions(ledger).executeAsList().map { row ->
            val versionRows = database.ledgerQueries.selectRg12FormalVersions(ledger, row.transaction_id)
                .executeAsList()
            val versions = versionRows.map { version ->
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
            val versionIds = versions.mapTo(mutableSetOf()) { it.id }
            val postingSets = versions.map { it.postingSetId }.distinct().map { postingSetId ->
                val postings = database.ledgerQueries.selectRg12FormalPostings(ledger, postingSetId.value)
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
                    is DomainResult.Failure -> error("invalid persisted RG-12 posting set $postingSetId")
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
                is DomainResult.Failure -> error("invalid persisted RG-12 formal transaction ${row.transaction_id}")
            }
            val savedMetadata = metadata[row.transaction_id]
                ?: error("missing persisted RG-12 formal transaction metadata for ${row.transaction_id}")
            Rg12FormalTransactionRecord(
                formalTransaction = formal,
                createdAt = Instant.parse(savedMetadata.created_at),
                createdAtText = savedMetadata.created_at,
                statisticsAtText = savedMetadata.statistics_at_text,
                versionCreatedAtTexts = versionCreatedAtTexts.filterKeys { it in versionIds },
                versionConfirmationIds = versionRows
                    .filter { it.confirmation_id != null }
                    .associate { TransactionVersionId(it.version_id) to requireNotNull(it.confirmation_id) },
            )
        }
    }

    private companion object {
        fun openingSnapshot(openingTransactions: List<Rg12FormalTransactionRecord>): Rg12Snapshot =
            Rg12Snapshot(
                formalTransactions = openingTransactions,
                postingSemantics = emptyMap(),
                reconciliationMatches = emptyList(),
                postingReconciliations = emptyList(),
                postingReplacements = emptyList(),
                confirmations = emptyList(),
                consumptionRecords = emptyList(),
                domainEntities = emptyList(),
                reconciliationSummary = emptyMap(),
                balances = emptyMap(),
                reports = emptyMap(),
                reportPeriods = emptyList(),
            )
    }
}
