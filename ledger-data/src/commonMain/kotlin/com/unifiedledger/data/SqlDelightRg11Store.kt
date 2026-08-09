package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.Rg11Action
import com.unifiedledger.application.Rg11AuditLink
import com.unifiedledger.application.Rg11ExecutionResult
import com.unifiedledger.application.Rg11FieldPath
import com.unifiedledger.application.Rg11FormalTransactionRecord
import com.unifiedledger.application.Rg11Operation
import com.unifiedledger.application.Rg11PostingSemantic
import com.unifiedledger.application.Rg11RejectionReason
import com.unifiedledger.application.Rg11ReturnedId
import com.unifiedledger.application.Rg11Runtime
import com.unifiedledger.application.Rg11Snapshot
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
import com.unifiedledger.domain.PeriodicAllocationAnchor
import com.unifiedledger.domain.PeriodicAllocationCadence
import com.unifiedledger.domain.PeriodicAllocationInstallment
import com.unifiedledger.domain.PeriodicAllocationRevision
import com.unifiedledger.domain.PeriodicAllocationSchedule
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
import kotlin.time.Instant

internal enum class Rg11FailurePoint {
    AFTER_CLAIM,
    AFTER_DELTA,
}

internal fun interface Rg11FailureInjector {
    fun failAt(point: Rg11FailurePoint)
}

private val NO_RG11_FAILURE = Rg11FailureInjector { }

/**
 * SQLDelight owner for the approved RG-11 periodic allocation operation boundary (D-085).
 *
 * The store follows the RG-08 owner pattern: every commit claims the operation identity with
 * its fingerprint, replays an already-finalized identity, and persists the runtime delta inside
 * one transaction. `RetryIdempotentInput` replays by identity lookup only, exactly like the
 * runtime receipt map; a retry of an unknown identity is a conflict.
 *
 * The formal chain is shared: PREPAID_PURCHASE / PREPAID_RECOGNITION transactions go through
 * the canonical_kind mapping of `insertTransaction`; a corrected version's confirmation id is
 * written into the shared `transaction_version.confirmation_id` column (v15 -> v16 additive
 * column, write-once guarded). The RG-11 exclusive tables hold schedule / revision /
 * installment / confirmation / audit link / posting semantic / reconciliation rows.
 */
class SqlDelightRg11Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val openingTransactions: List<Rg11FormalTransactionRecord>,
    private val failureInjector: Rg11FailureInjector,
) : com.unifiedledger.application.Rg11CommitPort {
    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingTransactions: List<Rg11FormalTransactionRecord> = emptyList(),
    ) : this(database, catalog, openingTransactions, NO_RG11_FAILURE) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        openingTransactions: List<Rg11FormalTransactionRecord>,
        failureInjector: Rg11FailureInjector,
    ) : this(database, catalog, openingTransactions, failureInjector) {
        configureSqliteConnection(driver)
        ensureOpeningTransactions()
    }

    override fun commit(operation: Rg11Operation): Rg11ExecutionResult = database.transactionWithResult {
        if (operation is Rg11Operation.RetryIdempotentInput) {
            return@transactionWithResult replayRetry(operation)
        }
        val fingerprint = operationFingerprint(operation)
        database.ledgerQueries.claimRg11Operation(
            operation.ledgerId.value,
            operation.identity.value,
            operation.action.code,
            operationClass(operation),
            fingerprint,
        )
        if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
            return@transactionWithResult replay(operation, fingerprint)
        }
        failureInjector.failAt(Rg11FailurePoint.AFTER_CLAIM)

        val before = loadPersistedSnapshot(operation.ledgerId)
        val runtime = Rg11Runtime(catalog, before)
        val result = runtime.commit(operation)
        check(result !is Rg11ExecutionResult.RequestIdentityConflict) {
            "newly claimed RG-11 operation returned an identity conflict"
        }
        if (result is Rg11ExecutionResult.Accepted) {
            persistDelta(operation, before, runtime.snapshot())
        }
        failureInjector.failAt(Rg11FailurePoint.AFTER_DELTA)
        finalizeOperation(operation, fingerprint, result)
        result
    }

    fun snapshot(ledgerId: LedgerId): Rg11Snapshot =
        Rg11Runtime(catalog, loadPersistedSnapshot(ledgerId)).snapshot()

    private fun operationFingerprint(operation: Rg11Operation): String =
        Rg11Runtime(catalog, emptyList()).operationFingerprint(operation)

    private fun operationClass(operation: Rg11Operation): String = when (operation) {
        is Rg11Operation.CreatePeriodicAllocation -> Rg11Action.CREATE_PERIODIC_ALLOCATION.code
        is Rg11Operation.RecognizePeriodicAllocationInstallment -> Rg11Action.RECOGNIZE_PERIODIC_ALLOCATION_INSTALLMENT.code
        is Rg11Operation.RevisePeriodicAllocation -> Rg11Action.REVISE_PERIODIC_ALLOCATION.code
        is Rg11Operation.CorrectTransactionVersion -> Rg11Action.CORRECT_TRANSACTION_VERSION.code
        is Rg11Operation.RetryIdempotentInput -> operation.action.code
        is Rg11Operation.InvalidInput -> "reject_invalid_rg11_input"
    }

    private fun replayRetry(operation: Rg11Operation.RetryIdempotentInput): Rg11ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg11Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg11ExecutionResult.RequestIdentityConflict
        if (saved.outcome == "PENDING") {
            error("persisted RG-11 operation is still pending")
        }
        return replayOutcome(saved)
    }

    private fun replay(operation: Rg11Operation, fingerprint: String): Rg11ExecutionResult {
        val saved = database.ledgerQueries
            .selectRg11Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()
            ?: return Rg11ExecutionResult.RequestIdentityConflict
        if (
            saved.action != operation.action.code ||
            saved.operation_class != operationClass(operation) ||
            saved.input_fingerprint != fingerprint
        ) {
            return Rg11ExecutionResult.RequestIdentityConflict
        }
        if (saved.outcome == "PENDING") {
            error("persisted RG-11 operation is still pending")
        }
        return replayOutcome(saved)
    }

    private fun replayOutcome(saved: com.unifiedledger.data.db.Rg11_operation): Rg11ExecutionResult {
        val returned = database.ledgerQueries
            .selectRg11ReturnedIds(saved.ledger_id, saved.identity_value)
            .executeAsList()
            .map(::restoreReturnedId)
        return when (saved.outcome) {
            "ACCEPTED", "NO_CHANGE" -> Rg11ExecutionResult.NoChange(returned)
            "REJECTED" -> Rg11ExecutionResult.Rejected(
                reason = Rg11RejectionReason.values().single { it.code == saved.reason_code },
                fieldPath = Rg11FieldPath.values().single { it.value == saved.field_path },
            )
            else -> error("unknown RG-11 operation outcome ${saved.outcome}")
        }
    }

    private fun restoreReturnedId(row: com.unifiedledger.data.db.SelectRg11ReturnedIds): Rg11ReturnedId =
        when (row.id_kind) {
            "TRANSACTION" -> Rg11ReturnedId.Transaction(TransactionId(row.id_value))
            "VERSION" -> Rg11ReturnedId.Version(TransactionVersionId(row.id_value))
            "DOMAIN_ENTITY" -> Rg11ReturnedId.DomainEntity(row.id_value)
            "CONFIRMATION" -> Rg11ReturnedId.Confirmation(row.id_value)
            "REQUEST" -> Rg11ReturnedId.Request(row.id_value)
            else -> error("unknown persisted RG-11 returned id kind ${row.id_kind}")
        }

    private fun finalizeOperation(
        operation: Rg11Operation,
        fingerprint: String,
        result: Rg11ExecutionResult,
    ) {
        val outcome = when (result) {
            is Rg11ExecutionResult.Accepted -> "ACCEPTED"
            is Rg11ExecutionResult.NoChange -> "NO_CHANGE"
            is Rg11ExecutionResult.Rejected -> "REJECTED"
            Rg11ExecutionResult.RequestIdentityConflict -> error("cannot finalize identity conflict")
        }
        val rejected = result as? Rg11ExecutionResult.Rejected
        val changed = database.ledgerQueries.updateRg11OperationResult(
            outcome,
            rejected?.reason?.code,
            rejected?.fieldPath?.value,
            operation.ledgerId.value,
            operation.identity.value,
        ).value
        check(changed == 1L) { "RG-11 operation final result did not update" }
        val returned = when (result) {
            is Rg11ExecutionResult.Accepted -> result.returnedIds
            is Rg11ExecutionResult.NoChange -> result.returnedIds
            else -> emptyList()
        }
        returned.forEachIndexed { index, id ->
            val stored = id.stored()
            database.ledgerQueries.insertRg11ReturnedId(
                operation.ledgerId.value,
                operation.identity.value,
                index.toLong(),
                stored.first,
                stored.second,
            )
        }
        check(fingerprint == database.ledgerQueries
            .selectRg11Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOne().input_fingerprint)
    }

    private fun Rg11ReturnedId.stored(): Pair<String, String> = when (this) {
        is Rg11ReturnedId.Transaction -> "TRANSACTION" to id.value
        is Rg11ReturnedId.Version -> "VERSION" to id.value
        is Rg11ReturnedId.DomainEntity -> "DOMAIN_ENTITY" to id
        is Rg11ReturnedId.Confirmation -> "CONFIRMATION" to id
        is Rg11ReturnedId.Request -> "REQUEST" to id
    }

    private fun ensureOpeningTransactions() {
        if (openingTransactions.isEmpty()) return
        database.transaction {
            val existing = database.ledgerQueries
                .selectRg11FormalTransactions(openingTransactions.first().formalTransaction.transaction.ledgerId.value)
                .executeAsList()
                .mapTo(mutableSetOf()) { it.transaction_id }
            openingTransactions.forEach { record ->
                if (record.formalTransaction.transaction.id.value !in existing) {
                    persistFormalRecord(record)
                }
            }
        }
    }

    private fun persistFormalRecord(record: Rg11FormalTransactionRecord) {
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
            record.versionConfirmationIds[version.id]?.let { confirmationId ->
                database.ledgerQueries.updateTransactionVersionConfirmationId(
                    confirmationId,
                    formal.transaction.ledgerId.value,
                    version.id.value,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                    "RG-11 opening version confirmation was not persisted"
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
        database.ledgerQueries.insertRg11FormalTransactionMetadata(
            formal.transaction.ledgerId.value,
            formal.transaction.id.value,
            record.createdAtText ?: record.createdAt.toString(),
            record.statisticsAtText ?: formal.versions.last().times.statisticsAt.toString(),
        )
    }

    private fun loadPersistedSnapshot(ledgerId: LedgerId): Rg11Snapshot {
        val ledger = ledgerId.value
        val q = database.ledgerQueries
        val schedules = q.selectRg11AllSchedules(ledger).executeAsList().map { row ->
            PeriodicAllocationSchedule(
                id = row.schedule_id,
                paymentTransactionId = TransactionId(row.payment_transaction_id),
                prepaidAccountId = AccountId(row.prepaid_account_id),
                categoryId = CategoryId(row.category_id),
                totalAmountMinor = row.total_amount_minor,
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                cadence = PeriodicAllocationCadence.valueOf(row.cadence),
                startAt = Instant.parse(row.start_at),
                anchor = restoreAnchor(row.anchor_kind, row.anchor_day),
            )
        }
        val revisionInstallments = q.selectRg11AllRevisionInstallments(ledger)
            .executeAsList().groupBy { it.revision_id }
        val revisions = q.selectRg11AllRevisions(ledger).executeAsList().map { row ->
            PeriodicAllocationRevision(
                id = row.revision_id,
                scheduleId = row.schedule_id,
                revisionNumber = row.revision_number.toInt(),
                recognizedThrough = row.recognized_through,
                remainingAmountMinor = row.remaining_amount_minor,
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                installmentIds = revisionInstallments[row.revision_id].orEmpty()
                    .sortedBy { it.installment_index }
                    .map { it.installment_id },
            )
        }
        val installments = q.selectRg11AllInstallments(ledger).executeAsList().map { row ->
            PeriodicAllocationInstallment(
                id = row.installment_id,
                scheduleId = row.schedule_id,
                revisionId = row.revision_id,
                sequence = row.installment_sequence.toInt(),
                scheduledAt = Instant.parse(row.scheduled_at),
                amountMinor = row.amount_minor,
                currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
            )
        }
        val confirmations = q.selectRg11AllConfirmations(ledger).executeAsList().map { row ->
            ExplicitOperationConfirmation(
                id = row.confirmation_id,
                operationId = row.operation_id,
                subject = OperationSubjectRef(kind = row.subject_kind, id = row.subject_id),
                createdAt = Instant.parse(row.confirmed_at),
                payload = emptyMap(),
            )
        }
        val auditLinks = q.selectRg11AllAuditLinks(ledger).executeAsList().map { row ->
            Rg11AuditLink(
                id = row.audit_link_id,
                linkType = row.link_type,
                fromKind = row.from_kind,
                fromId = row.from_id,
                toKind = row.to_kind,
                toId = row.to_id,
            )
        }
        val postingSemantics = q.selectRg11AllPostingSemantics(ledger).executeAsList()
            .associate { row ->
                row.posting_id to Rg11PostingSemantic(
                    role = row.role,
                    reconciliationEligible = row.reconciliation_eligible == 1L,
                    categoryId = row.category_id,
                )
            }
        val reconciliation = q.selectRg11AllPostingReconciliations(ledger).executeAsList()
            .associate { it.posting_id to it.status.lowercase() }
        return Rg11Snapshot(
            formalTransactions = loadFormalTransactions(ledgerId),
            schedules = schedules,
            revisions = revisions,
            installments = installments,
            confirmations = confirmations,
            auditLinks = auditLinks,
            postingSemantics = postingSemantics,
            balances = emptyMap(),
            reports = emptyMap(),
            reconciliation = reconciliation,
            derivedStatuses = emptyList(),
        )
    }

    private fun restoreAnchor(kind: String, day: Long?): PeriodicAllocationAnchor = when (kind) {
        "MONTH_END" -> PeriodicAllocationAnchor.MonthEnd
        "DAY_OF_MONTH" -> PeriodicAllocationAnchor.DayOfMonth(
            day?.toInt() ?: error("persisted RG-11 day-of-month anchor without day"),
        )
        else -> error("unknown persisted RG-11 anchor kind $kind")
    }

    private fun loadFormalTransactions(ledgerId: LedgerId): List<Rg11FormalTransactionRecord> {
        val ledger = ledgerId.value
        val metadata = database.ledgerQueries.selectRg11FormalTransactionMetadata(ledger)
            .executeAsList().associateBy { it.transaction_id }
        return database.ledgerQueries.selectRg11FormalTransactions(ledger).executeAsList().map { row ->
            val versionRows = database.ledgerQueries.selectRg11FormalVersions(ledger, row.transaction_id)
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
            val postingSets = versions.map { it.postingSetId }.distinct().map { postingSetId ->
                val postings = database.ledgerQueries.selectRg11FormalPostings(ledger, postingSetId.value)
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
                    is DomainResult.Failure -> error("invalid persisted RG-11 posting set $postingSetId")
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
                is DomainResult.Failure -> error("invalid persisted RG-11 formal transaction ${row.transaction_id}")
            }
            val savedMetadata = metadata[row.transaction_id]
                ?: error("missing persisted RG-11 formal transaction metadata for ${row.transaction_id}")
            Rg11FormalTransactionRecord(
                formalTransaction = formal,
                createdAt = Instant.parse(savedMetadata.created_at),
                createdAtText = savedMetadata.created_at,
                statisticsAtText = savedMetadata.statistics_at_text,
                versionConfirmationIds = versionRows
                    .filter { it.confirmation_id != null }
                    .associate { TransactionVersionId(it.version_id) to requireNotNull(it.confirmation_id) },
            )
        }
    }

    private fun persistDelta(
        operation: Rg11Operation,
        before: Rg11Snapshot,
        after: Rg11Snapshot,
    ) {
        val beforeTransactionIds = before.formalTransactions
            .mapTo(mutableSetOf()) { it.formalTransaction.transaction.id.value }
        val beforeVersions = before.formalTransactions
            .flatMapTo(mutableSetOf()) { record -> record.formalTransaction.versions.map { it.id.value } }
        val newFormal = after.formalTransactions.filter {
            it.formalTransaction.transaction.id.value !in beforeTransactionIds
        }
        newFormal.forEach(::persistFormalRecord)
        persistAppendedVersions(operation, before, after, beforeVersions)
        persistNewFormalSemantics(operation, newFormal, after)

        persistAllocationDelta(operation, before, after)

        val beforeConfirmationIds = before.confirmations.mapTo(mutableSetOf()) { it.id }
        after.confirmations.filter { it.id !in beforeConfirmationIds }.forEach { confirmation ->
            database.ledgerQueries.insertRg11Confirmation(
                operation.ledgerId.value,
                confirmation.id,
                confirmation.operationId,
                confirmation.subject.kind,
                confirmation.subject.id,
                confirmation.createdAt.toString(),
                "{}",
            )
        }

        val beforeAuditIds = before.auditLinks.mapTo(mutableSetOf()) { it.id }
        after.auditLinks.filter { it.id !in beforeAuditIds }.forEach { audit ->
            database.ledgerQueries.insertRg11AuditLink(
                operation.ledgerId.value,
                audit.id,
                audit.linkType,
                audit.fromKind,
                audit.fromId,
                audit.toKind,
                audit.toId,
            )
        }
    }

    /**
     * `correct_transaction_version` appends a version to an existing formal transaction:
     * persist the new version row, its write-once confirmation id, advance the current version
     * and the record's statistics time text (the correction target of the frozen contract).
     */
    private fun persistAppendedVersions(
        operation: Rg11Operation,
        before: Rg11Snapshot,
        after: Rg11Snapshot,
        beforeVersionIds: Set<String>,
    ) {
        val beforeRecords = before.formalTransactions.associateBy { it.formalTransaction.transaction.id.value }
        after.formalTransactions.forEach { record ->
            val transactionId = record.formalTransaction.transaction.id.value
            val oldRecord = beforeRecords[transactionId] ?: return@forEach
            val formal = record.formalTransaction
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
                    record.versionConfirmationIds[version.id]?.let { confirmationId ->
                        database.ledgerQueries.updateTransactionVersionConfirmationId(
                            confirmationId,
                            formal.transaction.ledgerId.value,
                            version.id.value,
                        )
                        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                            "RG-11 corrected version confirmation was not persisted"
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
                    "RG-11 current version was not advanced"
                }
            }
            val statisticsAtText = record.statisticsAtText
                ?: record.formalTransaction.versions
                    .first { it.id == record.formalTransaction.transaction.currentVersionId }
                    .times.statisticsAt.toString()
            if (statisticsAtText != oldRecord.statisticsAtText) {
                database.ledgerQueries.updateRg11FormalTransactionStatisticsAtText(
                    statisticsAtText,
                    operation.ledgerId.value,
                    transactionId,
                )
                check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L) {
                    "RG-11 statistics time text was not advanced"
                }
            }
        }
    }

    private fun persistAllocationDelta(
        operation: Rg11Operation,
        before: Rg11Snapshot,
        after: Rg11Snapshot,
    ) {
        val beforeScheduleIds = before.schedules.mapTo(mutableSetOf()) { it.id }
        val beforeRevisionIds = before.revisions.mapTo(mutableSetOf()) { it.id }
        val beforeInstallmentIds = before.installments.mapTo(mutableSetOf()) { it.id }
        after.schedules.filter { it.id !in beforeScheduleIds }.forEach { schedule ->
            database.ledgerQueries.insertRg11Schedule(
                operation.ledgerId.value,
                schedule.id,
                schedule.paymentTransactionId.value,
                schedule.prepaidAccountId.value,
                schedule.categoryId.value,
                schedule.totalAmountMinor,
                schedule.currency.code,
                schedule.currency.precision.toLong(),
                schedule.cadence.name,
                schedule.startAt.toString(),
                anchorKind(schedule.anchor),
                anchorDay(schedule.anchor),
            )
        }
        // The revision row must exist before its installments (immediate foreign keys),
        // and the mapping rows after both; the runtime inserts one contiguous batch of
        // installments per revision so the sequence guard fires only on real gaps.
        after.revisions.filter { it.id !in beforeRevisionIds }.forEach { revision ->
            database.ledgerQueries.insertRg11Revision(
                operation.ledgerId.value,
                revision.id,
                revision.scheduleId,
                revision.revisionNumber.toLong(),
                revision.recognizedThrough,
                revision.remainingAmountMinor,
                revision.currency.code,
                revision.currency.precision.toLong(),
            )
            after.installments
                .filter { it.revisionId == revision.id && it.id !in beforeInstallmentIds }
                .sortedBy { it.sequence }
                .forEach { installment ->
                    database.ledgerQueries.insertRg11Installment(
                        operation.ledgerId.value,
                        installment.id,
                        installment.scheduleId,
                        installment.revisionId,
                        installment.sequence.toLong(),
                        installment.scheduledAt.toString(),
                        installment.amountMinor,
                        installment.currency.code,
                        installment.currency.precision.toLong(),
                    )
                }
            revision.installmentIds.forEachIndexed { index, installmentId ->
                database.ledgerQueries.insertRg11RevisionInstallment(
                    operation.ledgerId.value,
                    revision.id,
                    index.toLong(),
                    installmentId,
                )
            }
        }
    }

    private fun persistNewFormalSemantics(
        operation: Rg11Operation,
        newFormal: List<Rg11FormalTransactionRecord>,
        after: Rg11Snapshot,
    ) {
        newFormal.forEach { record ->
            record.formalTransaction.currentPostings().forEach { posting ->
                val semantic = after.postingSemantics[posting.id.value] ?: return@forEach
                database.ledgerQueries.insertRg11PostingSemantic(
                    operation.ledgerId.value,
                    posting.id.value,
                    requireNotNull(semantic.role) { "RG-11 persisted posting semantic without role" },
                    if (semantic.reconciliationEligible) 1L else 0L,
                    semantic.categoryId,
                )
                if (semantic.reconciliationEligible) {
                    database.ledgerQueries.insertRg11PostingReconciliation(
                        operation.ledgerId.value,
                        posting.id.value,
                        "PENDING",
                    )
                }
            }
        }
    }

    private fun anchorKind(anchor: PeriodicAllocationAnchor): String = when (anchor) {
        PeriodicAllocationAnchor.MonthEnd -> "MONTH_END"
        is PeriodicAllocationAnchor.DayOfMonth -> "DAY_OF_MONTH"
    }

    private fun anchorDay(anchor: PeriodicAllocationAnchor): Long? = when (anchor) {
        PeriodicAllocationAnchor.MonthEnd -> null
        is PeriodicAllocationAnchor.DayOfMonth -> anchor.day.toLong()
    }
}
