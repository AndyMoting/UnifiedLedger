package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import kotlin.time.Instant

fun interface Rg06ManualObservationSource {
    fun find(sourceId: Rg06SourceId, evidenceId: Rg06EvidenceId): Rg06ManualBankObservation?
}

internal enum class Rg06FailurePoint { AFTER_CLAIM, AFTER_FORMAL, BEFORE_RECEIPT }
internal fun interface Rg06FailureInjector { fun failAt(point: Rg06FailurePoint) }
private val NO_RG06_FAILURE = Rg06FailureInjector { }
internal class Rg06PersistenceIntegrityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class SqlDelightRg06Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val expectedSourceOffsetText: String,
    private val manualObservations: Rg06ManualObservationSource,
    private val failure: Rg06FailureInjector,
) : Rg06CommitPort {
    constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        expectedSourceOffsetText: String,
        manualObservations: Rg06ManualObservationSource,
    ) : this(database, catalog, expectedSourceOffsetText, manualObservations, NO_RG06_FAILURE) {
        configureSqliteConnection(driver)
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        expectedSourceOffsetText: String,
        manualObservations: Rg06ManualObservationSource,
        failure: Rg06FailureInjector,
    ) : this(database, catalog, expectedSourceOffsetText, manualObservations, failure) {
        configureSqliteConnection(driver)
    }

    override fun commit(operation: Rg06Operation): Rg06ExecutionResult {
        database.ledgerQueries.selectRg06Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()?.let { return replay(operation, it) }
        return try {
            database.transactionWithResult {
                claim(operation)
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult database.ledgerQueries
                        .selectRg06Operation(operation.ledgerId.value, operation.identity.value)
                        .executeAsOneOrNull()?.let { replay(operation, it) }
                        ?: Rg06ExecutionResult.RequestIdentityConflict
                }
                failure.failAt(Rg06FailurePoint.AFTER_CLAIM)
                val validated = execute(operation)
                val result = validated.result
                if (result !is Rg06ExecutionResult.Accepted) throw Rg06Rollback(result)
                generatedKeys(operation).firstOrNull { (kind, value) ->
                    database.ledgerQueries.rg06IdentityCount(kind, value).executeAsOne() != 0L
                }?.let {
                    throw Rg06Rollback(rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY))
                }
                persist(operation, validated.manualObservation)
                failure.failAt(Rg06FailurePoint.BEFORE_RECEIPT)
                result.returnedIds.forEachIndexed { index, id ->
                    val (kind, value) = id.persisted()
                    database.ledgerQueries.insertRg06Receipt(
                        operation.ledgerId.value, operation.identity.value, index.toLong(), kind, value,
                    )
                }
                result
            }
        } catch (rollback: Rg06Rollback) {
            rollback.result
        }
    }

    private fun execute(operation: Rg06Operation): ValidatedOperation = when (operation) {
        is Rg06Operation.CreateStagedPayment -> ValidatedOperation(validateCreate(operation))
        is Rg06Operation.RecordStagedPaymentInstallment -> ValidatedOperation(validateRecord(operation))
        is Rg06Operation.ChangeStagedPaymentFulfillment -> ValidatedOperation(validateFulfillment(operation))
        is Rg06Operation.ConfirmStagedPaymentCompletion -> ValidatedOperation(validateCompletion(operation))
        is Rg06Operation.LinkStagedPaymentEvidence -> validateLink(operation)
        is Rg06Operation.IngestStagedPaymentBankFact -> ValidatedOperation(validateIngest(operation))
        is Rg06Operation.ConfirmStagedPaymentCandidate -> ValidatedOperation(validateCandidateConfirmation(operation))
        is Rg06Operation.MergeStagedPaymentMirrorEvidence -> ValidatedOperation(validateMirror(operation))
    }

    private fun validateCreate(op: Rg06Operation.CreateStagedPayment): Rg06ExecutionResult {
        val input = op.input
        if (input.totalAmount.minorUnits <= 0L) return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT)
        val categoryId = input.categoryId ?: return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        val category = catalog.categories.singleOrNull { it.id == categoryId }
            ?: return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        val parent = category.parentId?.let { id -> catalog.categories.singleOrNull { it.id == id } }
            ?: return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        if (parent.parentId != null || category.ledgerId != op.ledgerId || parent.ledgerId != op.ledgerId) {
            return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        }
        if (!category.active) return rejected(Rg06RejectionReason.CATEGORY_INACTIVE, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        if (category.kind != CategoryKind.EXPENSE || parent.kind != CategoryKind.EXPENSE) {
            return rejected(Rg06RejectionReason.EXPENSE_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        }
        val result = createStagedPayment(
            catalog, CreateStagedPaymentCommand(op.ledgerId, input.totalAmount, categoryId, input.createdAt), op.ids,
        )
        if (result !is StagedPaymentResult.Success) return rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
        return accepted(op)
    }

    private fun validateRecord(op: Rg06Operation.RecordStagedPaymentInstallment): Rg06ExecutionResult {
        val aggregate = loadOwnedAggregate(op.ledgerId, op.input.relationId) ?: return relationFailure(op.ledgerId, op.input.relationId)
        validateInstallment(aggregate, op.input)?.let { return it }
        return when (aggregate.recordInstallment(
            catalog,
            RecordStagedPaymentInstallmentCommand(
                op.input.paymentRole, op.input.paymentAmount, op.input.fundingAccountId, op.input.actualPaymentAt,
            ),
            op.ids.paymentIds,
        )) {
            is StagedPaymentResult.Success -> accepted(op)
            is StagedPaymentResult.Failure -> rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
        }
    }

    private fun validateFulfillment(op: Rg06Operation.ChangeStagedPaymentFulfillment): Rg06ExecutionResult {
        val aggregate = loadOwnedAggregate(op.ledgerId, op.input.relationId) ?: return relationFailure(op.ledgerId, op.input.relationId)
        return when (aggregate.changeFulfillment(op.historyId, op.input.fulfillmentStatus, op.input.occurredAt)) {
            is StagedPaymentResult.Success -> Rg06ExecutionResult.Accepted(listOf(Rg06ReturnedId.Lifecycle(aggregate.lifecycle.id)))
            is StagedPaymentResult.Failure -> rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.INPUT_RELATION_ID)
        }
    }

    private fun validateCompletion(op: Rg06Operation.ConfirmStagedPaymentCompletion): Rg06ExecutionResult {
        if (!op.input.confirmed) return rejected(Rg06RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg06FieldPath.INPUT_CONFIRMED)
        val aggregate = loadOwnedAggregate(op.ledgerId, op.input.relationId) ?: return relationFailure(op.ledgerId, op.input.relationId)
        if (aggregate.lifecycle.dueAmount.minorUnits != 0L) return rejected(Rg06RejectionReason.DUE_MUST_BE_ZERO, Rg06FieldPath.ATTEMPTED_PAYMENT_PROGRESS)
        return when (aggregate.confirmCompletion(op.historyId, op.input.occurredAt)) {
            is StagedPaymentResult.Success -> Rg06ExecutionResult.Accepted(listOf(Rg06ReturnedId.Lifecycle(aggregate.lifecycle.id)))
            is StagedPaymentResult.Failure -> rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.INPUT_RELATION_ID)
        }
    }

    private fun validateLink(op: Rg06Operation.LinkStagedPaymentEvidence): ValidatedOperation {
        fun rejectedValidation(reason: Rg06RejectionReason, path: Rg06FieldPath) =
            ValidatedOperation(rejected(reason, path))
        val payment = database.ledgerQueries.selectRg06Payment(op.input.paymentId.value).executeAsOneOrNull()
            ?: return rejectedValidation(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_PAYMENT_ID)
        if (payment.ledger_id != op.ledgerId.value) return rejectedValidation(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_PAYMENT_ID)
        if (payment.asset_posting_id != op.input.postingId.value) return rejectedValidation(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_POSTING_ID)
        if (evidenceTargetAlreadyBound(op)) {
            return rejectedValidation(Rg06RejectionReason.EVIDENCE_ALREADY_BOUND, Rg06FieldPath.INPUT_POSTING_ID)
        }
        val observation = manualObservations.find(op.input.sourceId, op.input.evidenceId)
            ?: return rejectedValidation(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_EVIDENCE_ID)
        val magnitude = positiveMagnitude(observation.amount)
            ?: return rejectedValidation(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.INPUT_AMOUNT)
        if (magnitude.minorUnits != payment.amount_minor || magnitude.currency.code != payment.currency_code || magnitude.currency.precision.toLong() != payment.currency_precision) {
            return rejectedValidation(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_PAYMENT_ID)
        }
        return ValidatedOperation(accepted(op), observation)
    }

    private fun evidenceTargetAlreadyBound(op: Rg06Operation.LinkStagedPaymentEvidence): Boolean =
        database.ledgerQueries.selectRg06EvidenceLinkForPayment(
            op.ledgerId.value, op.input.paymentId.value, op.input.postingId.value,
        ).executeAsOneOrNull() != null

    private fun validateIngest(op: Rg06Operation.IngestStagedPaymentBankFact): Rg06ExecutionResult {
        val time = sourceTime(op.input.sourcePaymentAt, op.input.sourcePaymentAtText)
            ?: return rejected(Rg06RejectionReason.INVALID_SOURCE_TIME, Rg06FieldPath.INPUT_SOURCE_PAYMENT_AT)
        val payload = op.input.suggestedPaymentRole?.let {
            Rg06StagedPaymentCandidatePayload.known(it, op.input.amount, time, op.input.evidenceId)
        } ?: Rg06StagedPaymentCandidatePayload.ambiguous(op.input.amount, time, op.input.evidenceId)
        if (payload !is Rg06TypedValueResult.Success) return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.INPUT_AMOUNT)
        return accepted(op)
    }

    private fun validateCandidateConfirmation(op: Rg06Operation.ConfirmStagedPaymentCandidate): Rg06ExecutionResult {
        if (!op.input.exactBindingConfirmed) return rejected(Rg06RejectionReason.EXACT_BINDING_CONFIRMATION_REQUIRED, Rg06FieldPath.INPUT_EXACT_BINDING_CONFIRMED)
        val candidate = database.ledgerQueries.selectRg06Candidate(op.ledgerId.value, op.input.candidateId.value).executeAsOneOrNull()
            ?: return if (database.ledgerQueries.selectRg06CandidateOwner(op.input.candidateId.value).executeAsOneOrNull() == null) {
                rejected(Rg06RejectionReason.CANDIDATE_NOT_FOUND, Rg06FieldPath.INPUT_CANDIDATE_ID)
            } else {
                rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_CANDIDATE_ID)
            }
        val statuses = database.ledgerQueries.selectRg06CandidateStatuses(op.ledgerId.value, op.input.candidateId.value).executeAsList()
        if (statuses.lastOrNull()?.status != "PENDING_CONFIRMATION") return rejected(Rg06RejectionReason.CANDIDATE_NOT_PENDING, Rg06FieldPath.INPUT_CANDIDATE_ID)
        if (candidate.role_fact != null && candidate.role_fact != op.input.paymentRole.name) return rejected(Rg06RejectionReason.CANDIDATE_ROLE_MISMATCH, Rg06FieldPath.INPUT_PAYMENT_ROLE)
        val aggregate = loadOwnedAggregate(op.ledgerId, op.input.relationId) ?: return relationFailure(op.ledgerId, op.input.relationId)
        if (aggregate.lifecycle.categoryId != op.input.categoryId) return rejected(Rg06RejectionReason.CANDIDATE_TARGET_MISMATCH, Rg06FieldPath.INPUT_CATEGORY_ID)
        val evidence = database.ledgerQueries.selectRg06Evidence(candidate.evidence_id).executeAsOneOrNull()
            ?: return rejected(Rg06RejectionReason.CANDIDATE_TARGET_MISMATCH, Rg06FieldPath.INPUT_CANDIDATE_ID)
        if (evidence.evidence_kind != "PENDING") return rejected(Rg06RejectionReason.EVIDENCE_ALREADY_BOUND, Rg06FieldPath.INPUT_CANDIDATE_ID)
        val input = Rg06RecordStagedPaymentInstallmentInput(
            op.input.requestId, op.input.relationId, op.input.paymentRole,
            Money.ofMinor(candidate.amount_minor, CurrencyUnit(candidate.currency_code, candidate.currency_precision.toInt())),
            op.input.fundingAccountId, Instant.parse(candidate.source_payment_at),
        )
        validateInstallment(aggregate, input)?.let { return it }
        val restoredTime = StagedPaymentSourceTime.create(Instant.parse(candidate.source_payment_at), candidate.source_payment_at_text, expectedSourceOffsetText)
        val sourceTime = (restoredTime as? StagedPaymentResult.Success)?.value
            ?: return rejected(Rg06RejectionReason.INVALID_SOURCE_TIME, Rg06FieldPath.INPUT_SOURCE_PAYMENT_AT)
        return when (aggregate.recordInstallment(
            catalog,
            RecordStagedPaymentInstallmentCommand(input.paymentRole, input.paymentAmount, input.fundingAccountId, input.actualPaymentAt, sourceTime),
            op.ids.paymentIds,
        )) {
            is StagedPaymentResult.Success -> accepted(op)
            is StagedPaymentResult.Failure -> rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
        }
    }

    private fun validateMirror(op: Rg06Operation.MergeStagedPaymentMirrorEvidence): Rg06ExecutionResult {
        sourceTime(op.input.sourcePaymentAt, op.input.sourcePaymentAtText)
            ?: return rejected(Rg06RejectionReason.INVALID_SOURCE_TIME, Rg06FieldPath.INPUT_SOURCE_PAYMENT_AT)
        if (positiveMagnitude(op.input.amount) == null) return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.INPUT_AMOUNT)
        val payment = database.ledgerQueries.selectRg06Payment(op.input.paymentId.value).executeAsOneOrNull()
            ?: return rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_PAYMENT_ID)
        if (payment.ledger_id != op.ledgerId.value) return rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_PAYMENT_ID)
        if (payment.asset_posting_id != op.input.postingId.value) return rejected(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_POSTING_ID)
        val link = database.ledgerQueries.selectRg06EvidenceLinkForPayment(op.ledgerId.value, payment.payment_id, payment.asset_posting_id).executeAsOneOrNull()
            ?: return rejected(Rg06RejectionReason.MIRROR_TARGET_NOT_FOUND, Rg06FieldPath.INPUT_POSTING_ID)
        val originalEvidence = database.ledgerQueries.selectRg06Evidence(link.evidence_id).executeAsOneOrNull()
            ?: return rejected(Rg06RejectionReason.MIRROR_TARGET_NOT_FOUND, Rg06FieldPath.INPUT_POSTING_ID)
        val original = database.ledgerQueries.selectRg06Source(originalEvidence.source_id).executeAsOneOrNull()
            ?: return rejected(Rg06RejectionReason.MIRROR_TARGET_NOT_FOUND, Rg06FieldPath.INPUT_POSTING_ID)
        if (original.amount_minor == Long.MIN_VALUE || op.input.amount.minorUnits != -original.amount_minor || op.input.amount.currency.code != original.currency_code || op.input.amount.currency.precision.toLong() != original.currency_precision) {
            return rejected(Rg06RejectionReason.MIRROR_SOURCE_MISMATCH, Rg06FieldPath.INPUT_AMOUNT)
        }
        return accepted(op)
    }

    private fun persist(operation: Rg06Operation, manualObservation: Rg06ManualBankObservation?) {
        when (operation) {
            is Rg06Operation.CreateStagedPayment -> persistCreate(operation)
            is Rg06Operation.RecordStagedPaymentInstallment -> persistRecord(operation, null, false, "MANUAL_INSTALLMENT", null, null)
            is Rg06Operation.ChangeStagedPaymentFulfillment -> persistHistoryChange(operation)
            is Rg06Operation.ConfirmStagedPaymentCompletion -> persistCompletion(operation)
            is Rg06Operation.LinkStagedPaymentEvidence -> persistLink(operation, checkNotNull(manualObservation))
            is Rg06Operation.IngestStagedPaymentBankFact -> persistIngest(operation)
            is Rg06Operation.ConfirmStagedPaymentCandidate -> persistCandidateConfirmation(operation)
            is Rg06Operation.MergeStagedPaymentMirrorEvidence -> persistMirror(operation)
        }
    }

    private fun persistCreate(op: Rg06Operation.CreateStagedPayment) {
        val categoryId = checkNotNull(op.input.categoryId)
        database.ledgerQueries.insertRg06Relation(op.ledgerId.value, op.ids.relationId.value)
        database.ledgerQueries.insertRg06Lifecycle(op.ledgerId.value, op.ids.lifecycleId.value, op.ids.relationId.value, op.input.totalAmount.minorUnits, 0, op.input.totalAmount.minorUnits, op.input.totalAmount.currency.code, op.input.totalAmount.currency.precision.toLong(), categoryId.value, 1)
        database.ledgerQueries.insertRg06RelationMember(op.ledgerId.value, op.ids.relationId.value, 0, "LIFECYCLE", op.ids.lifecycleId.value)
        database.ledgerQueries.insertRg06History(op.ledgerId.value, op.ids.lifecycleId.value, 1, op.ids.historyId.value, op.identity.value, "GROUP_CREATED", op.input.createdAt.toString(), op.input.totalAmount.minorUnits, 0, op.input.totalAmount.minorUnits, null, "UNPAID", "IN_PROGRESS", 0)
    }

    private fun persistRecord(
        op: Rg06Operation.RecordStagedPaymentInstallment,
        sourceTime: StagedPaymentSourceTime?,
        matched: Boolean,
        confirmationKind: String,
        candidateId: String?,
        reconciliationEvidenceLinkId: String?,
        confirmedAt: Instant? = null,
    ) {
        val aggregate = checkNotNull(loadOwnedAggregate(op.ledgerId, op.input.relationId))
        val updated = (aggregate.recordInstallment(
            catalog,
            RecordStagedPaymentInstallmentCommand(op.input.paymentRole, op.input.paymentAmount, op.input.fundingAccountId, op.input.actualPaymentAt, sourceTime),
            op.ids.paymentIds,
        ) as StagedPaymentResult.Success).value
        val payment = updated.installments.last()
        val formal = updated.formalTransactions.last()
        val currentVersion = formal.versions.single { it.id == formal.transaction.currentVersionId }
        persistFormal(formal)
        failure.failAt(Rg06FailurePoint.AFTER_FORMAL)
        database.ledgerQueries.insertRg06Installment(op.ledgerId.value, op.input.relationId.value, aggregate.installments.size.toLong(), payment.id.value, payment.role.name, payment.amount.minorUnits, payment.currency.code, payment.currency.precision.toLong(), payment.fundingAccountId.value, payment.transactionId.value, formal.transaction.currentVersionId.value, currentVersion.postingSetId.value, payment.expensePostingId.value, payment.assetPostingId.value, payment.actualPaymentAt.toString(), payment.statisticsAt.toString(), payment.sourcePaymentAt?.toString(), payment.sourcePaymentAtText)
        database.ledgerQueries.insertRg06PostingSemantic(op.ledgerId.value, payment.expensePostingId.value, payment.id.value, "expense", updated.lifecycle.categoryId.value, 0)
        database.ledgerQueries.insertRg06PostingSemantic(op.ledgerId.value, payment.assetPostingId.value, payment.id.value, "payment_asset", null, 1)
        database.ledgerQueries.insertRg06RelationMember(op.ledgerId.value, op.input.relationId.value, (aggregate.installments.size + 1).toLong(), "INSTALLMENT", payment.id.value)
        val history = updated.lifecycle.stateHistory.last()
        database.ledgerQueries.insertRg06History(op.ledgerId.value, updated.lifecycle.id.value, history.sequence.toLong(), history.id.value, op.identity.value, history.event.name, history.occurredAt.toString(), history.totalAmount.minorUnits, history.paidAmount.minorUnits, history.dueAmount.minorUnits, history.paymentId?.value, history.paymentProgress.name, history.fulfillmentStatus.name, history.stateTransitionEffectCount.toLong())
        database.ledgerQueries.updateRg06Lifecycle(updated.lifecycle.paidAmount.minorUnits, updated.lifecycle.dueAmount.minorUnits, history.sequence.toLong(), op.ledgerId.value, updated.lifecycle.id.value, aggregate.lifecycle.stateHistory.size.toLong())
        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L)
        database.ledgerQueries.insertRg06Confirmation(
            op.ledgerId.value,
            op.ids.confirmationId.value,
            op.identity.value,
            confirmationKind,
            candidateId,
            op.input.relationId.value,
            payment.id.value,
            payment.role.name,
            updated.lifecycle.categoryId.value,
            payment.fundingAccountId.value,
            confirmedAt?.toString(),
        )
        val status = if (matched) "MATCHED" else "PENDING"
        database.ledgerQueries.insertRg06Reconciliation(op.ledgerId.value, op.ids.reconciliationId.value, payment.assetPostingId.value, status, 1)
        database.ledgerQueries.insertRg06ReconciliationHistory(
            op.ledgerId.value, op.ids.reconciliationId.value, 1, status, reconciliationEvidenceLinkId,
        )
    }

    private fun persistHistoryChange(op: Rg06Operation.ChangeStagedPaymentFulfillment) {
        val aggregate = checkNotNull(loadOwnedAggregate(op.ledgerId, op.input.relationId))
        val updated = (aggregate.changeFulfillment(op.historyId, op.input.fulfillmentStatus, op.input.occurredAt) as StagedPaymentResult.Success).value
        persistLatestHistory(op.ledgerId, op.identity.value, aggregate, updated)
    }

    private fun persistCompletion(op: Rg06Operation.ConfirmStagedPaymentCompletion) {
        val aggregate = checkNotNull(loadOwnedAggregate(op.ledgerId, op.input.relationId))
        val updated = (aggregate.confirmCompletion(op.historyId, op.input.occurredAt) as StagedPaymentResult.Success).value
        persistLatestHistory(op.ledgerId, op.identity.value, aggregate, updated)
    }

    private fun persistLatestHistory(ledgerId: LedgerId, operationIdentity: String, previous: StagedPayment, updated: StagedPayment) {
        val history = updated.lifecycle.stateHistory.last()
        database.ledgerQueries.insertRg06History(ledgerId.value, updated.lifecycle.id.value, history.sequence.toLong(), history.id.value, operationIdentity, history.event.name, history.occurredAt.toString(), history.totalAmount.minorUnits, history.paidAmount.minorUnits, history.dueAmount.minorUnits, history.paymentId?.value, history.paymentProgress.name, history.fulfillmentStatus.name, 0)
        database.ledgerQueries.updateRg06Lifecycle(updated.lifecycle.paidAmount.minorUnits, updated.lifecycle.dueAmount.minorUnits, history.sequence.toLong(), ledgerId.value, updated.lifecycle.id.value, previous.lifecycle.stateHistory.size.toLong())
        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L)
    }

    private fun persistLink(op: Rg06Operation.LinkStagedPaymentEvidence, observation: Rg06ManualBankObservation) {
        val payment = database.ledgerQueries.selectRg06Payment(op.input.paymentId.value).executeAsOne()
        database.ledgerQueries.insertRg06Source(op.ledgerId.value, op.input.sourceId.value, "MANUAL", observation.amount.minorUnits, observation.amount.currency.code, observation.amount.currency.precision.toLong(), observation.observedAt.instant.toString(), observation.observedAt.text, "OBSERVED_AT", null)
        database.ledgerQueries.insertRg06Evidence(op.ledgerId.value, op.input.evidenceId.value, op.input.sourceId.value, "BOUND", observation.observedAt.instant.toString(), observation.observedAt.text, "OBSERVED_AT", payment.payment_id, null, null)
        database.ledgerQueries.insertRg06EvidenceLink(op.ledgerId.value, op.evidenceLinkId.value, op.input.evidenceId.value, payment.payment_id, payment.asset_posting_id, "MANUAL")
        val reconciliation = database.ledgerQueries.selectRg06ReconciliationForPosting(op.ledgerId.value, payment.asset_posting_id).executeAsOneOrNull()
        if (reconciliation != null && reconciliation.status == "PENDING") {
            database.ledgerQueries.matchRg06Reconciliation(op.ledgerId.value, payment.asset_posting_id)
            database.ledgerQueries.insertRg06ReconciliationHistory(op.ledgerId.value, reconciliation.reconciliation_id, reconciliation.latest_sequence + 1, "MATCHED", op.evidenceLinkId.value)
        }
    }

    private fun persistIngest(op: Rg06Operation.IngestStagedPaymentBankFact) {
        val time = checkNotNull(sourceTime(op.input.sourcePaymentAt, op.input.sourcePaymentAtText))
        val amount = positiveMagnitude(op.input.amount)!!
        database.ledgerQueries.insertRg06Source(op.ledgerId.value, op.input.sourceId.value, "IMPORTED", op.input.amount.minorUnits, op.input.amount.currency.code, op.input.amount.currency.precision.toLong(), time.instant.toString(), time.text, "SOURCE_PAYMENT_AT", null)
        database.ledgerQueries.insertRg06Evidence(op.ledgerId.value, op.input.evidenceId.value, op.input.sourceId.value, "PENDING", time.instant.toString(), time.text, "SOURCE_PAYMENT_AT", null, null, null)
        database.ledgerQueries.insertRg06Candidate(op.ledgerId.value, op.ids.candidateId.value, op.input.sourceId.value, op.input.suggestedPaymentRole?.name, amount.minorUnits, amount.currency.code, amount.currency.precision.toLong(), time.instant.toString(), time.text, op.input.evidenceId.value, if (op.input.suggestedPaymentRole == null) "0.50" else "1.00", 1)
        RG06_CONFIRMATION_REQUIREMENTS.forEachIndexed { index, requirement ->
            database.ledgerQueries.insertRg06CandidateRequirement(op.ledgerId.value, op.ids.candidateId.value, index.toLong(), requirement.name)
        }
        database.ledgerQueries.insertRg06CandidateStatus(op.ledgerId.value, op.ids.candidateId.value, 1, op.ids.pendingStatusId.value, "PENDING_CONFIRMATION")
    }

    private fun persistCandidateConfirmation(op: Rg06Operation.ConfirmStagedPaymentCandidate) {
        val candidate = database.ledgerQueries.selectRg06Candidate(op.ledgerId.value, op.input.candidateId.value).executeAsOne()
        val source = (StagedPaymentSourceTime.create(Instant.parse(candidate.source_payment_at), candidate.source_payment_at_text, expectedSourceOffsetText) as StagedPaymentResult.Success).value
        val record = Rg06Operation.RecordStagedPaymentInstallment(
            op.ledgerId,
            Rg06RecordStagedPaymentInstallmentInput(op.input.requestId, op.input.relationId, op.input.paymentRole, Money.ofMinor(candidate.amount_minor, CurrencyUnit(candidate.currency_code, candidate.currency_precision.toInt())), op.input.fundingAccountId, source.instant),
            Rg06ManualInstallmentCommitIds(op.ids.confirmationId, op.ids.paymentIds, op.ids.reconciliationId),
        )
        persistRecord(
            record,
            source,
            true,
            "CANDIDATE_CONFIRMATION",
            candidate.candidate_id,
            op.ids.evidenceLinkId.value,
            op.input.confirmedAt,
        )
        val payment = database.ledgerQueries.selectRg06Payment(op.ids.paymentIds.paymentId.value).executeAsOne()
        database.ledgerQueries.updateRg06EvidenceBinding(payment.payment_id, op.ledgerId.value, candidate.evidence_id)
        check(database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() == 1L)
        database.ledgerQueries.insertRg06EvidenceLink(op.ledgerId.value, op.ids.evidenceLinkId.value, candidate.evidence_id, payment.payment_id, payment.asset_posting_id, "IMPORTED")
        database.ledgerQueries.insertRg06CandidateStatus(op.ledgerId.value, candidate.candidate_id, 2, op.ids.confirmedStatusId.value, "CONFIRMED")
    }

    private fun persistMirror(op: Rg06Operation.MergeStagedPaymentMirrorEvidence) {
        val time = checkNotNull(sourceTime(op.input.sourcePaymentAt, op.input.sourcePaymentAtText))
        val link = database.ledgerQueries.selectRg06EvidenceLinkForPayment(op.ledgerId.value, op.input.paymentId.value, op.input.postingId.value).executeAsOne()
        val originalEvidence = database.ledgerQueries.selectRg06Evidence(link.evidence_id).executeAsOne()
        database.ledgerQueries.insertRg06Source(op.ledgerId.value, op.input.sourceId.value, "MIRROR", op.input.amount.minorUnits, op.input.amount.currency.code, op.input.amount.currency.precision.toLong(), time.instant.toString(), time.text, "SOURCE_PAYMENT_AT", originalEvidence.source_id)
        database.ledgerQueries.insertRg06Evidence(op.ledgerId.value, op.input.evidenceId.value, op.input.sourceId.value, "MIRROR", time.instant.toString(), time.text, "SOURCE_PAYMENT_AT", op.input.paymentId.value, originalEvidence.evidence_id, link.link_id)
    }

    private fun persistFormal(formal: FormalTransaction) {
        formal.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, formal.transaction.ledgerId.value) }
        database.ledgerQueries.insertTransaction(formal.transaction.id.value, formal.transaction.ledgerId.value, formal.transaction.kind.name)
        formal.versions.forEach { version ->
            database.ledgerQueries.insertTransactionVersion(version.id.value, version.transactionId.value, formal.transaction.ledgerId.value, version.versionNumber.toLong(), version.postingSetId.value, version.times.occurredAt.toString(), version.times.statisticsAt.toString(), version.times.effectiveAt.toString(), version.note)
        }
        database.ledgerQueries.insertTransactionCurrentVersion(formal.transaction.id.value, formal.transaction.ledgerId.value, formal.transaction.currentVersionId.value)
        formal.postingSets.forEach { set -> set.postings.forEachIndexed { index, posting ->
            database.ledgerQueries.insertPosting(posting.id.value, set.id.value, formal.transaction.ledgerId.value, index.toLong(), posting.accountId.value, posting.amount.minorUnits, posting.amount.currency.code, posting.amount.currency.precision.toLong())
        } }
    }

    private fun loadOwnedAggregate(ledgerId: LedgerId, relationId: StagedPaymentRelationId): StagedPayment? {
        val owner = database.ledgerQueries.selectRg06RelationOwner(relationId.value).executeAsOneOrNull() ?: return null
        if (owner != ledgerId.value) return null
        return try {
            loadPersistedAggregate(ledgerId, relationId)
        } catch (failure: Rg06PersistenceIntegrityException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw Rg06PersistenceIntegrityException(
                "invalid persisted RG06 snapshot for ${relationId.value}",
                failure,
            )
        }
    }

    private fun loadPersistedAggregate(
        ledgerId: LedgerId,
        relationId: StagedPaymentRelationId,
    ): StagedPayment {
        val lifecycle = database.ledgerQueries.selectRg06AggregateLifecycle(ledgerId.value, relationId.value)
            .executeAsOneOrNull()
            ?: throw Rg06PersistenceIntegrityException(
                "invalid persisted RG06 snapshot for ${relationId.value}: missing lifecycle",
            )
        val members = database.ledgerQueries.selectRg06RelationMembers(ledgerId.value, relationId.value).executeAsList().map {
            if (it.member_kind == "LIFECYCLE") StagedPaymentMemberRef.Lifecycle(StagedPaymentLifecycleId(it.member_id)) else StagedPaymentMemberRef.Installment(InstallmentPaymentId(it.member_id))
        }
        val currency = CurrencyUnit(lifecycle.currency_code, lifecycle.currency_precision.toInt())
        val history = database.ledgerQueries.selectRg06History(ledgerId.value, lifecycle.lifecycle_id).executeAsList().map {
            StagedPaymentHistoryEntry(StagedPaymentHistoryId(it.history_id), it.history_sequence.toInt(), StagedPaymentEvent.valueOf(it.event_type), Instant.parse(it.occurred_at), Money.ofMinor(it.total_minor, currency), Money.ofMinor(it.paid_minor, currency), Money.ofMinor(it.due_minor, currency), it.payment_id?.let(::InstallmentPaymentId), StagedPaymentProgress.valueOf(it.payment_progress), StagedPaymentFulfillment.valueOf(it.fulfillment_status), it.state_transition_effect_count.toInt())
        }
        val paymentRows = database.ledgerQueries.selectRg06Installments(ledgerId.value, relationId.value).executeAsList()
        val installments = paymentRows.map {
            val paymentCurrency = CurrencyUnit(it.currency_code, it.currency_precision.toInt())
            StagedPaymentInstallmentSnapshot(InstallmentPaymentId(it.payment_id), StagedPaymentRole.valueOf(it.payment_role), Money.ofMinor(it.amount_minor, paymentCurrency), paymentCurrency, AccountId(it.funding_account_id), TransactionId(it.transaction_id), PostingId(it.expense_posting_id), PostingId(it.asset_posting_id), Instant.parse(it.actual_payment_at), Instant.parse(it.statistics_at), it.source_payment_at?.let(Instant::parse), it.source_payment_at_text)
        }
        val formals = paymentRows.map {
            loadFormal(
                ledgerId,
                TransactionId(it.transaction_id),
                TransactionVersionId(it.transaction_version_id),
                PostingSetId(it.posting_set_id),
            )
        }
        val snapshot = StagedPaymentSnapshot(
            ledgerId,
            StagedPaymentRelationSnapshot(relationId, members),
            StagedPaymentLifecycleSnapshot(StagedPaymentLifecycleId(lifecycle.lifecycle_id), Money.ofMinor(lifecycle.total_minor, currency), Money.ofMinor(lifecycle.paid_minor, currency), Money.ofMinor(lifecycle.due_minor, currency), currency, CategoryId(lifecycle.category_id), history),
            installments,
            formals,
        )
        return when (val restored = rehydrateStagedPayment(snapshot)) {
            is StagedPaymentResult.Success -> restored.value
            is StagedPaymentResult.Failure -> throw Rg06PersistenceIntegrityException(
                "invalid persisted RG06 snapshot for ${relationId.value}: ${restored.violation}",
            )
        }
    }

    private fun loadFormal(
        ledgerId: LedgerId,
        transactionId: TransactionId,
        expectedCurrentVersionId: TransactionVersionId,
        expectedCurrentPostingSetId: PostingSetId,
    ): StagedPaymentFormalTransactionSnapshot {
        val tx = database.ledgerQueries.selectRg06FormalTransaction(ledgerId.value, transactionId.value)
            .executeAsOneOrNull()
            ?: throw Rg06PersistenceIntegrityException(
                "invalid persisted RG06 snapshot for formal transaction ${transactionId.value}: missing transaction",
            )
        if (tx.current_version_id != expectedCurrentVersionId.value) {
            throw Rg06PersistenceIntegrityException(
                "invalid persisted RG06 snapshot for formal transaction ${transactionId.value}: current version drift",
            )
        }
        val versions = database.ledgerQueries.selectRg06FormalVersions(ledgerId.value, transactionId.value).executeAsList().map {
            StagedPaymentTransactionVersionSnapshot(TransactionVersionId(it.version_id), TransactionId(it.transaction_id), it.version_number.toInt(), PostingSetId(it.posting_set_id), TransactionTimes(Instant.parse(it.occurred_at), Instant.parse(it.statistics_at), Instant.parse(it.effective_at)), it.note)
        }
        val currentVersion = versions.singleOrNull { it.id == expectedCurrentVersionId }
            ?: throw Rg06PersistenceIntegrityException(
                "invalid persisted RG06 snapshot for formal transaction ${transactionId.value}: missing current version",
            )
        if (currentVersion.postingSetId != expectedCurrentPostingSetId) {
            throw Rg06PersistenceIntegrityException(
                "invalid persisted RG06 snapshot for formal transaction ${transactionId.value}: current posting set drift",
            )
        }
        val postingSets = database.ledgerQueries.selectRg06FormalPostingSets(ledgerId.value, transactionId.value).executeAsList().map { row ->
            StagedPaymentPostingSetSnapshot(PostingSetId(row), database.ledgerQueries.selectRg06FormalPostings(ledgerId.value, row).executeAsList().map {
                StagedPaymentPostingSnapshot(PostingId(it.posting_id), AccountId(it.account_id), Money.ofMinor(it.amount_minor, CurrencyUnit(it.currency_code, it.currency_precision.toInt())))
            })
        }
        return StagedPaymentFormalTransactionSnapshot(StagedPaymentTransactionSnapshot(TransactionId(tx.transaction_id), LedgerId(tx.ledger_id), TransactionKind.valueOf(tx.kind), TransactionVersionId(tx.current_version_id)), versions, postingSets)
    }

    private fun validateInstallment(aggregate: StagedPayment, input: Rg06RecordStagedPaymentInstallmentInput): Rg06ExecutionResult.Rejected? {
        val minor = input.paymentAmount.minorUnits
        if (minor <= 0L) return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentRole == StagedPaymentRole.DEPOSIT && minor >= aggregate.lifecycle.totalAmount.minorUnits) return rejected(Rg06RejectionReason.DEPOSIT_MUST_BE_LESS_THAN_TOTAL, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentRole == StagedPaymentRole.FINAL && minor > aggregate.lifecycle.dueAmount.minorUnits) return rejected(Rg06RejectionReason.PAYMENT_EXCEEDS_DUE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentRole == StagedPaymentRole.FINAL && minor != aggregate.lifecycle.dueAmount.minorUnits) return rejected(Rg06RejectionReason.FINAL_MUST_EQUAL_REMAINING_DUE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentAmount.currency != aggregate.lifecycle.currency) return rejected(Rg06RejectionReason.SINGLE_CURRENCY_REQUIRED, Rg06FieldPath.ATTEMPTED_CURRENCY)
        val account = catalog.accounts.singleOrNull { it.id == input.fundingAccountId }
            ?: return rejected(Rg06RejectionReason.UNKNOWN_REAL_ACCOUNT, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        if (!account.realAccount) return rejected(Rg06RejectionReason.REAL_FINANCIAL_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        if (!account.ownedByUser || account.ledgerId != aggregate.ledgerId) return rejected(Rg06RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        if (account.kind != AccountKind.ASSET) return rejected(Rg06RejectionReason.ASSET_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        return null
    }

    private fun claim(op: Rg06Operation) {
        val fields = canonicalFields(op)
        database.ledgerQueries.claimRg06Operation(op.ledgerId.value, op.identity.value, op.action.code, fields.requestId, fields.sourceId, fields.evidenceId, fields.candidateId, fields.relationId, fields.paymentId, fields.postingId, fields.categoryId, fields.fundingAccountId, fields.paymentRole, fields.fulfillment, fields.confirmed, fields.exactBinding, fields.amountMinor, fields.currencyCode, fields.currencyPrecision, fields.occurredAt, fields.occurredAtText, fields.suggestedRole)
    }

    private fun replay(op: Rg06Operation, saved: com.unifiedledger.data.db.Rg06_operation): Rg06ExecutionResult {
        val fields = canonicalFields(op)
        if (saved.action_type != op.action.code || saved.request_id != fields.requestId || saved.source_id != fields.sourceId || saved.evidence_id != fields.evidenceId || saved.candidate_id != fields.candidateId || saved.relation_id != fields.relationId || saved.payment_id != fields.paymentId || saved.posting_id != fields.postingId || saved.category_id != fields.categoryId || saved.funding_account_id != fields.fundingAccountId || saved.payment_role != fields.paymentRole || saved.fulfillment_status != fields.fulfillment || saved.confirmed != fields.confirmed || saved.exact_binding_confirmed != fields.exactBinding || saved.amount_minor != fields.amountMinor || saved.currency_code != fields.currencyCode || saved.currency_precision != fields.currencyPrecision || saved.occurred_at != fields.occurredAt || saved.occurred_at_text != fields.occurredAtText || saved.suggested_payment_role != fields.suggestedRole) return Rg06ExecutionResult.RequestIdentityConflict
        if (op is Rg06Operation.ConfirmStagedPaymentCandidate) {
            val savedConfirmation = database.ledgerQueries
                .selectRg06ConfirmationForIdentity(op.ledgerId.value, op.identity.value)
                .executeAsOneOrNull()
            if (savedConfirmation?.confirmed_at != op.input.confirmedAt?.toString()) {
                return Rg06ExecutionResult.RequestIdentityConflict
            }
        }
        val ids = database.ledgerQueries.selectRg06Receipts(op.ledgerId.value, op.identity.value).executeAsList().map { it.restored() }
        return Rg06ExecutionResult.NoChange(ids)
    }

    private fun canonicalFields(op: Rg06Operation): CanonicalFields = when (op) {
        is Rg06Operation.CreateStagedPayment -> CanonicalFields(requestId = op.input.requestId.value, categoryId = op.input.categoryId?.value, amountMinor = op.input.totalAmount.minorUnits, currencyCode = op.input.totalAmount.currency.code, currencyPrecision = op.input.totalAmount.currency.precision.toLong(), occurredAt = op.input.createdAt.toString())
        is Rg06Operation.RecordStagedPaymentInstallment -> CanonicalFields(requestId = op.input.requestId.value, relationId = op.input.relationId.value, paymentRole = op.input.paymentRole.name, fundingAccountId = op.input.fundingAccountId.value, amountMinor = op.input.paymentAmount.minorUnits, currencyCode = op.input.paymentAmount.currency.code, currencyPrecision = op.input.paymentAmount.currency.precision.toLong(), occurredAt = op.input.actualPaymentAt.toString())
        is Rg06Operation.ChangeStagedPaymentFulfillment -> CanonicalFields(requestId = op.input.requestId.value, relationId = op.input.relationId.value, fulfillment = op.input.fulfillmentStatus.name, occurredAt = op.input.occurredAt.toString())
        is Rg06Operation.ConfirmStagedPaymentCompletion -> CanonicalFields(requestId = op.input.requestId.value, relationId = op.input.relationId.value, confirmed = if (op.input.confirmed) 1 else 0, occurredAt = op.input.occurredAt.toString())
        is Rg06Operation.LinkStagedPaymentEvidence -> CanonicalFields(sourceId = op.input.sourceId.value, evidenceId = op.input.evidenceId.value, paymentId = op.input.paymentId.value, postingId = op.input.postingId.value)
        is Rg06Operation.IngestStagedPaymentBankFact -> CanonicalFields(sourceId = op.input.sourceId.value, evidenceId = op.input.evidenceId.value, amountMinor = op.input.amount.minorUnits, currencyCode = op.input.amount.currency.code, currencyPrecision = op.input.amount.currency.precision.toLong(), occurredAt = op.input.sourcePaymentAt.toString(), occurredAtText = op.input.sourcePaymentAtText, suggestedRole = op.input.suggestedPaymentRole?.name)
        is Rg06Operation.ConfirmStagedPaymentCandidate -> CanonicalFields(requestId = op.input.requestId.value, candidateId = op.input.candidateId.value, relationId = op.input.relationId.value, categoryId = op.input.categoryId.value, fundingAccountId = op.input.fundingAccountId.value, paymentRole = op.input.paymentRole.name, exactBinding = if (op.input.exactBindingConfirmed) 1 else 0)
        is Rg06Operation.MergeStagedPaymentMirrorEvidence -> CanonicalFields(sourceId = op.input.sourceId.value, evidenceId = op.input.evidenceId.value, paymentId = op.input.paymentId.value, postingId = op.input.postingId.value, amountMinor = op.input.amount.minorUnits, currencyCode = op.input.amount.currency.code, currencyPrecision = op.input.amount.currency.precision.toLong(), occurredAt = op.input.sourcePaymentAt.toString(), occurredAtText = op.input.sourcePaymentAtText)
    }

    private fun accepted(op: Rg06Operation) = Rg06ExecutionResult.Accepted(returnedIds(op))
    private fun sourceTime(instant: Instant, text: String) = (Rg06SourcePaymentAt.create(instant, text, expectedSourceOffsetText) as? Rg06TypedValueResult.Success)?.value
    private fun positiveMagnitude(money: Money): Money? = when (money.minorUnits) { 0L, Long.MIN_VALUE -> null; else -> Money.ofMinor(if (money.minorUnits < 0) -money.minorUnits else money.minorUnits, money.currency) }
    private fun relationFailure(ledgerId: LedgerId, relationId: StagedPaymentRelationId) = if (database.ledgerQueries.selectRg06RelationOwner(relationId.value).executeAsOneOrNull() == null) rejected(Rg06RejectionReason.RELATION_NOT_FOUND, Rg06FieldPath.INPUT_RELATION_ID) else rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_RELATION_ID)

    private fun generatedKeys(op: Rg06Operation): Set<Pair<String, String>> {
        fun payment(ids: StagedPaymentInstallmentIds) = setOf("payment" to ids.paymentId.value, "history" to ids.historyId.value, "transaction" to ids.expenseIds.transactionId.value, "version" to ids.expenseIds.versionId.value, "posting_set" to ids.expenseIds.postingSetId.value, "posting" to ids.expenseIds.expensePostingId.value, "posting" to ids.expenseIds.paymentPostingId.value)
        return when (op) {
            is Rg06Operation.CreateStagedPayment -> setOf("relation" to op.ids.relationId.value, "lifecycle" to op.ids.lifecycleId.value, "history" to op.ids.historyId.value)
            is Rg06Operation.RecordStagedPaymentInstallment -> payment(op.ids.paymentIds) + setOf("confirmation" to op.ids.confirmationId.value, "reconciliation" to op.ids.reconciliationId.value)
            is Rg06Operation.ChangeStagedPaymentFulfillment -> setOf("history" to op.historyId.value)
            is Rg06Operation.ConfirmStagedPaymentCompletion -> setOf("history" to op.historyId.value)
            is Rg06Operation.LinkStagedPaymentEvidence -> setOf("source" to op.input.sourceId.value, "evidence" to op.input.evidenceId.value, "link" to op.evidenceLinkId.value)
            is Rg06Operation.IngestStagedPaymentBankFact -> setOf("source" to op.input.sourceId.value, "evidence" to op.input.evidenceId.value, "candidate" to op.ids.candidateId.value, "candidate_status" to op.ids.pendingStatusId.value)
            is Rg06Operation.ConfirmStagedPaymentCandidate -> payment(op.ids.paymentIds) + setOf("confirmation" to op.ids.confirmationId.value, "link" to op.ids.evidenceLinkId.value, "candidate_status" to op.ids.confirmedStatusId.value, "reconciliation" to op.ids.reconciliationId.value)
            is Rg06Operation.MergeStagedPaymentMirrorEvidence -> setOf("source" to op.input.sourceId.value, "evidence" to op.input.evidenceId.value)
        }
    }
}

private data class CanonicalFields(
    val requestId: String? = null, val sourceId: String? = null, val evidenceId: String? = null,
    val candidateId: String? = null, val relationId: String? = null, val paymentId: String? = null,
    val postingId: String? = null, val categoryId: String? = null, val fundingAccountId: String? = null,
    val paymentRole: String? = null, val fulfillment: String? = null, val confirmed: Long? = null,
    val exactBinding: Long? = null, val amountMinor: Long? = null, val currencyCode: String? = null,
    val currencyPrecision: Long? = null, val occurredAt: String? = null, val occurredAtText: String? = null,
    val suggestedRole: String? = null,
)

private data class ValidatedOperation(
    val result: Rg06ExecutionResult,
    val manualObservation: Rg06ManualBankObservation? = null,
)

private class Rg06Rollback(val result: Rg06ExecutionResult) : RuntimeException()
private fun rejected(reason: Rg06RejectionReason, path: Rg06FieldPath) = Rg06ExecutionResult.Rejected(reason, path)

private fun Rg06ReturnedId.persisted(): Pair<String, String> = when (this) {
    is Rg06ReturnedId.Relation -> "RELATION" to id.value
    is Rg06ReturnedId.Lifecycle -> "LIFECYCLE" to id.value
    is Rg06ReturnedId.Payment -> "PAYMENT" to id.value
    is Rg06ReturnedId.Transaction -> "TRANSACTION" to id.value
    is Rg06ReturnedId.Source -> "SOURCE" to id.value
    is Rg06ReturnedId.Evidence -> "EVIDENCE" to id.value
    is Rg06ReturnedId.Candidate -> "CANDIDATE" to id.value
    is Rg06ReturnedId.Confirmation -> "CONFIRMATION" to id.value
    is Rg06ReturnedId.EvidenceLink -> "EVIDENCE_LINK" to id.value
}

private fun com.unifiedledger.data.db.SelectRg06Receipts.restored(): Rg06ReturnedId = when (id_kind) {
    "RELATION" -> Rg06ReturnedId.Relation(StagedPaymentRelationId(id_value))
    "LIFECYCLE" -> Rg06ReturnedId.Lifecycle(StagedPaymentLifecycleId(id_value))
    "PAYMENT" -> Rg06ReturnedId.Payment(InstallmentPaymentId(id_value))
    "TRANSACTION" -> Rg06ReturnedId.Transaction(TransactionId(id_value))
    "SOURCE" -> Rg06ReturnedId.Source(Rg06SourceId(id_value))
    "EVIDENCE" -> Rg06ReturnedId.Evidence(Rg06EvidenceId(id_value))
    "CANDIDATE" -> Rg06ReturnedId.Candidate(Rg06CandidateId(id_value))
    "CONFIRMATION" -> Rg06ReturnedId.Confirmation(Rg06ConfirmationId(id_value))
    else -> Rg06ReturnedId.EvidenceLink(Rg06EvidenceLinkId(id_value))
}

private fun returnedIds(op: Rg06Operation): List<Rg06ReturnedId> = when (op) {
    is Rg06Operation.CreateStagedPayment -> listOf(Rg06ReturnedId.Relation(op.ids.relationId), Rg06ReturnedId.Lifecycle(op.ids.lifecycleId))
    is Rg06Operation.RecordStagedPaymentInstallment -> listOf(Rg06ReturnedId.Confirmation(op.ids.confirmationId), Rg06ReturnedId.Transaction(op.ids.paymentIds.expenseIds.transactionId), Rg06ReturnedId.Payment(op.ids.paymentIds.paymentId))
    is Rg06Operation.ChangeStagedPaymentFulfillment,
    is Rg06Operation.ConfirmStagedPaymentCompletion -> error("Lifecycle-only returned IDs are resolved from persisted state")
    is Rg06Operation.LinkStagedPaymentEvidence -> listOf(Rg06ReturnedId.Source(op.input.sourceId), Rg06ReturnedId.Evidence(op.input.evidenceId), Rg06ReturnedId.EvidenceLink(op.evidenceLinkId))
    is Rg06Operation.IngestStagedPaymentBankFact -> listOf(Rg06ReturnedId.Source(op.input.sourceId), Rg06ReturnedId.Evidence(op.input.evidenceId), Rg06ReturnedId.Candidate(op.ids.candidateId))
    is Rg06Operation.ConfirmStagedPaymentCandidate -> listOf(Rg06ReturnedId.Confirmation(op.ids.confirmationId), Rg06ReturnedId.Transaction(op.ids.paymentIds.expenseIds.transactionId), Rg06ReturnedId.Payment(op.ids.paymentIds.paymentId), Rg06ReturnedId.EvidenceLink(op.ids.evidenceLinkId))
    is Rg06Operation.MergeStagedPaymentMirrorEvidence -> listOf(Rg06ReturnedId.Source(op.input.sourceId), Rg06ReturnedId.Evidence(op.input.evidenceId))
}
