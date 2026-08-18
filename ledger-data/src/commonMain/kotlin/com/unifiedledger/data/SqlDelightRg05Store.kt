package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import kotlin.time.Instant

data class Rg05ManualCommitIds(
    val confirmationId: String,
    val reconciliationId: String,
)

interface Rg05IdentitySource {
    fun manual(requestId: RequestId): Rg05ManualCommitIds
}

internal enum class Rg05FailurePoint { INGEST_AFTER_SOURCES, CONFIRM_AFTER_FORMAL, CONFIRM_AFTER_RELATION, RECEIPT_AFTER_EVIDENCE }
internal fun interface Rg05FailureInjector { fun failAt(point: Rg05FailurePoint) }
private val NO_RG05_FAILURE = Rg05FailureInjector { }

class SqlDelightRg05Store private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val identity: Rg05IdentitySource,
    private val failure: Rg05FailureInjector,
) : Rg05CommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver, catalog: LedgerCatalog, identity: Rg05IdentitySource) :
        this(database, catalog, identity, NO_RG05_FAILURE) {
        configureSqliteConnection(driver)
    }
    internal constructor(database: LedgerDatabase, driver: SqlDriver, catalog: LedgerCatalog, identity: Rg05IdentitySource, failure: Rg05FailureInjector) :
        this(database, catalog, identity, failure) { configureSqliteConnection(driver) }

    override fun commit(operation: Rg05PreparedOperation): Rg05ExecutionResult {
        val confirmed = when (operation) {
            is Rg05PreparedOperation.Manual -> operation.snapshot.confirmed
            is Rg05PreparedOperation.Confirm -> operation.snapshot.confirmed
            is Rg05PreparedOperation.Ingest, is Rg05PreparedOperation.Receipt -> true
        }
        if (!confirmed) {
            return Rg05ExecutionResult.Rejected(
                Rg05ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED,
                "explicit_confirmation",
            )
        }
        return when (operation) {
            is Rg05PreparedOperation.Manual -> commitManual(operation)
            is Rg05PreparedOperation.Ingest -> commitIngest(operation.snapshot)
            is Rg05PreparedOperation.Confirm -> commitConfirm(operation)
            is Rg05PreparedOperation.Receipt -> commitReceipt(operation.snapshot)
        }
    }

    private fun commitManual(operation: Rg05PreparedOperation.Manual): Rg05ExecutionResult {
        val snapshot = operation.snapshot
        database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()?.let {
            return resolveManual(snapshot)
        }
        val confirmationId = operation.confirmationId.ifBlank { identity.manual(snapshot.requestId).confirmationId }
        val reconciliationId = operation.reconciliationId.ifBlank { identity.manual(snapshot.requestId).reconciliationId }
        return database.transactionWithResult {
            database.ledgerQueries.claimRg05Request(snapshot.ledgerId.value, snapshot.requestId.value, Rg05Action.MANUAL_MERGED_PAYMENT.name)
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) return@transactionWithResult resolveManual(snapshot)

            val created = createMergedPaymentExpense(
                catalog,
                MergedPaymentExpenseCommand(
                    snapshot.ledgerId,
                    snapshot.total,
                    snapshot.fundingAccountId,
                    TransactionTimes(snapshot.paymentAt, snapshot.statisticsAt, snapshot.paymentAt),
                    snapshot.items,
                ),
                operation.formalIds,
            )
            val aggregate = when (created) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteRg05Request(snapshot.ledgerId.value, snapshot.requestId.value)
                    return@transactionWithResult created.violation.toRg05Rejected()
                }
            }
            persistFormal(aggregate.formalTransaction, snapshot.paymentAtText, snapshot.statisticsAtText, snapshot.paymentAtText)
            aggregate.postings.forEach { typed ->
                database.ledgerQueries.insertRg05PostingSemantic(
                    snapshot.ledgerId.value,
                    typed.posting.id.value,
                    if (typed.role == MergedPaymentPostingRole.EXPENSE) "expense" else "payment_asset",
                    typed.itemId,
                    typed.categoryId?.value,
                    if (typed.role == MergedPaymentPostingRole.EXPENSE) 0 else 1,
                )
            }
            database.ledgerQueries.insertRg05MergedPaymentSnapshot(
                snapshot.ledgerId.value,
                snapshot.requestId.value,
                snapshot.paymentAtText,
                snapshot.statisticsAtText,
                snapshot.total.minorUnits,
                snapshot.total.currency.code,
                snapshot.total.currency.precision.toLong(),
                snapshot.fundingAccountId.value,
                operation.formalIds.transactionId.value,
                operation.relationId,
                confirmationId,
            )
            aggregate.consumptions.forEachIndexed { index, item ->
                val postingId = aggregate.postings.first { it.itemId == item.itemId }.posting.id.value
                database.ledgerQueries.insertRg05ItemSnapshot(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    index.toLong(),
                    item.itemId,
                    item.amount.minorUnits,
                    item.amount.currency.code,
                    item.amount.currency.precision.toLong(),
                    item.categoryId.value,
                    item.details,
                    item.sourceObservedAt.toString(),
                    operation.consumptionIds.getValue(item.itemId),
                    operation.allocationIds.getValue(item.itemId),
                    null,
                    null,
                    postingId,
                )
            }
            database.ledgerQueries.insertRg05Relation(
                snapshot.ledgerId.value,
                operation.relationId,
                operation.formalIds.transactionId.value,
                operation.formalIds.paymentAssetPostingId.value,
                "合并付款",
                snapshot.total.minorUnits,
                snapshot.total.currency.code,
                snapshot.total.currency.precision.toLong(),
            )
            aggregate.allocations.forEachIndexed { index, allocation ->
                database.ledgerQueries.insertRg05RelationItem(
                    snapshot.ledgerId.value,
                    operation.relationId,
                    index.toLong(),
                    allocation.itemId,
                    allocation.expensePostingId.value,
                    allocation.amount.minorUnits,
                    allocation.categoryId.value,
                    "NONE",
                )
            }
            database.ledgerQueries.insertRg05OperationReceipt(
                snapshot.ledgerId.value,
                snapshot.requestId.value,
                "ACCEPTED",
                confirmationId,
                operation.formalIds.transactionId.value,
                operation.relationId,
            )
            database.ledgerQueries.insertRg05Confirmation(
                snapshot.ledgerId.value,
                confirmationId,
                snapshot.requestId.value,
                null,
                operation.formalIds.transactionId.value,
                "EXPLICIT_MANUAL_SAVE",
            )
            database.ledgerQueries.insertRg05PostingReconciliation(
                snapshot.ledgerId.value,
                reconciliationId,
                operation.formalIds.paymentAssetPostingId.value,
                "PENDING",
            )
            Rg05ExecutionResult.Accepted(confirmationId, operation.formalIds.transactionId, operation.relationId)
        }
    }

    private fun resolveManual(snapshot: Rg05ManualSnapshot): Rg05ExecutionResult {
        val action = database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()
            ?: return Rg05ExecutionResult.RequestIdentityConflict
        if (action != Rg05Action.MANUAL_MERGED_PAYMENT.name) return Rg05ExecutionResult.RequestIdentityConflict
        val stored = database.ledgerQueries.selectRg05Commit(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()
            ?: return Rg05ExecutionResult.RequestIdentityConflict
        val items = database.ledgerQueries.selectRg05Items(snapshot.ledgerId.value, snapshot.requestId.value).executeAsList()
        val matches = stored.occurred_at == snapshot.paymentAtText &&
            stored.statistics_at == snapshot.statisticsAtText &&
            stored.total_minor == snapshot.total.minorUnits &&
            stored.currency_code == snapshot.total.currency.code &&
            stored.currency_precision == snapshot.total.currency.precision.toLong() &&
            stored.funding_account_id == snapshot.fundingAccountId.value &&
            items.size == snapshot.items.size &&
            items.zip(snapshot.items).all { (saved, item) ->
                saved.item_id == item.itemId &&
                    saved.amount_minor == item.amount.minorUnits &&
                    saved.currency_code == item.amount.currency.code &&
                    saved.currency_precision == item.amount.currency.precision.toLong() &&
                    saved.category_id == item.categoryId.value &&
                    saved.details == item.details &&
                    saved.source_observed_at == item.sourceObservedAt.toString()
            }
        return if (matches) {
            Rg05ExecutionResult.NoChange(stored.confirmation_id, TransactionId(stored.transaction_id), stored.relation_id)
        } else {
            Rg05ExecutionResult.RequestIdentityConflict
        }
    }

    private fun commitIngest(snapshot: Rg05IngestSnapshot): Rg05ExecutionResult {
        database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()?.let {
            return resolveIngest(snapshot)
        }
        val invalid = validateIngest(snapshot)
        if (invalid != null) return invalid
        return database.transactionWithResult {
            database.ledgerQueries.claimRg05Request(snapshot.ledgerId.value, snapshot.requestId.value, Rg05Action.INGEST_MERGED_PAYMENT_FACTS.name)
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) return@transactionWithResult resolveIngest(snapshot)
            val bank = snapshot.bankFact
            database.ledgerQueries.insertRg05Source(
                snapshot.ledgerId.value, bank.sourceId, "BANK_FACT", bank.evidenceId, null,
                Rg05EvidenceKind.BANK_PAYMENT.name, bank.observedAtText, bank.details, bank.amount.minorUnits,
                bank.amount.currency.code, bank.amount.currency.precision.toLong(), null, "COMPLETE",
            )
            database.ledgerQueries.insertRg05Evidence(snapshot.ledgerId.value, bank.evidenceId, bank.sourceId, Rg05EvidenceKind.BANK_PAYMENT.name, bank.observedAtText)
            snapshot.itemFacts.forEach { item ->
                database.ledgerQueries.insertRg05Source(
                    snapshot.ledgerId.value, item.sourceId, "ITEM_FACT", item.evidenceId, item.itemId,
                    item.evidenceKind.name, item.observedAtText, item.details, item.amount.minorUnits,
                    item.amount.currency.code, item.amount.currency.precision.toLong(), item.suggestedCategoryId.value,
                    if (item.evidenceKind == Rg05EvidenceKind.ITEM_RECEIPT) "COMPLETE" else "SUMMARY_ONLY",
                )
                database.ledgerQueries.insertRg05Evidence(snapshot.ledgerId.value, item.evidenceId, item.sourceId, item.evidenceKind.name, item.observedAtText)
            }
            failure.failAt(Rg05FailurePoint.INGEST_AFTER_SOURCES)
            database.ledgerQueries.insertRg05Candidate(
                snapshot.ledgerId.value, snapshot.candidateId, bank.sourceId, -bank.amount.minorUnits,
                bank.amount.currency.code, bank.amount.currency.precision.toLong(),
            )
            snapshot.itemFacts.forEachIndexed { index, item ->
                database.ledgerQueries.insertRg05CandidateItem(
                    snapshot.ledgerId.value, snapshot.candidateId, index.toLong(), item.itemId, item.sourceId, item.evidenceId,
                    item.amount.minorUnits, item.amount.currency.code, item.amount.currency.precision.toLong(), item.suggestedCategoryId.value,
                )
            }
            database.ledgerQueries.insertRg05CandidateStatus(snapshot.ledgerId.value, snapshot.candidateId, 1, snapshot.pendingStatusId, "PENDING_CONFIRMATION", snapshot.requestId.value)
            database.ledgerQueries.insertRg05OperationReceipt(snapshot.ledgerId.value, snapshot.requestId.value, "ACCEPTED", null, null, null)
            Rg05ExecutionResult.IngestAccepted(snapshot.candidateId, listOf(bank.sourceId) + snapshot.itemFacts.map { it.sourceId }, listOf(bank.evidenceId) + snapshot.itemFacts.map { it.evidenceId })
        }
    }

    private fun validateIngest(snapshot: Rg05IngestSnapshot): Rg05ExecutionResult.Rejected? {
        if (snapshot.itemFacts.size != 2) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_TOTAL_MUST_EQUAL_PAYMENT, "item_facts")
        val bank = snapshot.bankFact
        if (bank.amount.minorUnits >= 0) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.MUST_BE_POSITIVE, "bank_fact.amount")
        if (snapshot.itemFacts.any { it.amount.minorUnits <= 0 }) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ITEM_AMOUNT_MUST_BE_POSITIVE, "item_facts")
        if (snapshot.itemFacts.map { it.itemId }.toSet().size != 2 || snapshot.itemFacts.map { it.sourceId }.toSet().size != 2 || snapshot.itemFacts.map { it.evidenceId }.toSet().size != 2) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.DUPLICATE_ITEM_ID, "item_facts")
        if (snapshot.itemFacts.any { it.amount.currency != bank.amount.currency }) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.SINGLE_CURRENCY_REQUIRED, "item_facts.currency")
        if (snapshot.itemFacts.sumOf { it.amount.minorUnits } != -bank.amount.minorUnits) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_TOTAL_MUST_EQUAL_PAYMENT, "item_facts.amount")
        return null
    }

    private fun resolveIngest(snapshot: Rg05IngestSnapshot): Rg05ExecutionResult {
        val action = database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()
            ?: return Rg05ExecutionResult.RequestIdentityConflict
        if (action != Rg05Action.INGEST_MERGED_PAYMENT_FACTS.name) return Rg05ExecutionResult.RequestIdentityConflict
        val candidate = database.ledgerQueries.selectRg05Candidate(snapshot.ledgerId.value, snapshot.candidateId).executeAsOneOrNull()
            ?: return Rg05ExecutionResult.RequestIdentityConflict
        val candidateItems = database.ledgerQueries.selectRg05CandidateItems(snapshot.ledgerId.value, snapshot.candidateId).executeAsList()
        val bank = database.ledgerQueries.selectRg05Source(snapshot.ledgerId.value, snapshot.bankFact.sourceId).executeAsOneOrNull()
        val items = snapshot.itemFacts.map { database.ledgerQueries.selectRg05Source(snapshot.ledgerId.value, it.sourceId).executeAsOneOrNull() }
        val matches = bank?.source_type == "BANK_FACT" && bank.evidence_id == snapshot.bankFact.evidenceId &&
            bank.evidence_kind == Rg05EvidenceKind.BANK_PAYMENT.name && bank.observed_at == snapshot.bankFact.observedAtText &&
            bank.details == snapshot.bankFact.details && bank.amount_minor == snapshot.bankFact.amount.minorUnits &&
            bank.currency_code == snapshot.bankFact.amount.currency.code && bank.currency_precision == snapshot.bankFact.amount.currency.precision.toLong() &&
            bank.completeness == "COMPLETE" && candidate.bank_source_id == snapshot.bankFact.sourceId &&
            candidate.payment_total_minor == -snapshot.bankFact.amount.minorUnits && candidate.currency_code == snapshot.bankFact.amount.currency.code &&
            candidate.currency_precision == snapshot.bankFact.amount.currency.precision.toLong() && items.size == 2 &&
            items.zip(snapshot.itemFacts).all { (saved, expected) ->
                saved?.source_id == expected.sourceId && saved.source_type == "ITEM_FACT" && saved.evidence_id == expected.evidenceId &&
                    saved.item_id == expected.itemId && saved.evidence_kind == expected.evidenceKind.name && saved.observed_at == expected.observedAtText &&
                    saved.details == expected.details && saved.amount_minor == expected.amount.minorUnits && saved.currency_code == expected.amount.currency.code &&
                    saved.currency_precision == expected.amount.currency.precision.toLong() && saved.suggested_category_id == expected.suggestedCategoryId.value &&
                    saved.completeness == if (expected.evidenceKind == Rg05EvidenceKind.ITEM_RECEIPT) "COMPLETE" else "SUMMARY_ONLY"
            } && candidateItems.size == snapshot.itemFacts.size && candidateItems.zip(snapshot.itemFacts).all { (saved, expected) ->
                saved.item_id == expected.itemId && saved.source_id == expected.sourceId && saved.evidence_id == expected.evidenceId &&
                    saved.amount_minor == expected.amount.minorUnits && saved.currency_code == expected.amount.currency.code &&
                    saved.currency_precision == expected.amount.currency.precision.toLong() && saved.suggested_category_id == expected.suggestedCategoryId.value
            }
        return if (matches) Rg05ExecutionResult.IngestNoChange(snapshot.candidateId, listOf(snapshot.bankFact.sourceId) + snapshot.itemFacts.map { it.sourceId }, listOf(snapshot.bankFact.evidenceId) + snapshot.itemFacts.map { it.evidenceId }) else Rg05ExecutionResult.RequestIdentityConflict
    }

    private fun commitConfirm(operation: Rg05PreparedOperation.Confirm): Rg05ExecutionResult {
        val snapshot = operation.snapshot
        database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()?.let { return resolveConfirm(operation) }
        val candidate = database.ledgerQueries.selectRg05Candidate(snapshot.ledgerId.value, snapshot.candidateId).executeAsOneOrNull()
            ?: return Rg05ExecutionResult.Rejected(Rg05ExecutionError.CANDIDATE_NOT_FOUND, "candidate_id")
        if (candidate.status != "PENDING_CONFIRMATION") return Rg05ExecutionResult.Rejected(Rg05ExecutionError.CANDIDATE_NOT_PENDING, "candidate_id")
        val candidateItems = database.ledgerQueries.selectRg05CandidateItems(snapshot.ledgerId.value, snapshot.candidateId).executeAsList()
        if (snapshot.allocations.size != 2 || snapshot.allocations.map { it.itemId }.toSet().size != 2) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.DUPLICATE_ITEM_ID, "items")
        if (snapshot.allocations.any { it.amount.minorUnits <= 0 }) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ITEM_AMOUNT_MUST_BE_POSITIVE, "items")
        if (snapshot.allocations.any { it.amount.currency.code != candidate.currency_code || it.amount.currency.precision.toLong() != candidate.currency_precision }) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.SINGLE_CURRENCY_REQUIRED, "items")
        val total = snapshot.allocations.sumOf { it.amount.minorUnits }
        if (total < candidate.payment_total_minor) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_INCOMPLETE, "allocation_total")
        if (total > candidate.payment_total_minor) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_CONFLICT, "allocation_total")
        if (candidateItems.size != 2 || snapshot.allocations.any { allocation -> candidateItems.none { it.item_id == allocation.itemId } }) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_TARGET_MISMATCH, "items")
        return database.transactionWithResult {
            database.ledgerQueries.claimRg05Request(snapshot.ledgerId.value, snapshot.requestId.value, Rg05Action.CONFIRM_MERGED_PAYMENT_CANDIDATE.name)
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) return@transactionWithResult resolveConfirm(operation)
            val sourceItems = candidateItems.map { row ->
                val source = database.ledgerQueries.selectRg05Source(snapshot.ledgerId.value, row.source_id).executeAsOne()
                val allocation = snapshot.allocations.first { it.itemId == row.item_id }
                MergedPaymentItem(row.item_id, allocation.amount, allocation.categoryId, source.details, Instant.parse(source.observed_at))
            }
            database.ledgerQueries.insertRg05ConfirmationSnapshot(snapshot.ledgerId.value, snapshot.requestId.value, snapshot.candidateId, snapshot.fundingAccountId.value, snapshot.paymentAtText, snapshot.statisticsAtText)
            snapshot.allocations.forEachIndexed { index, allocation -> database.ledgerQueries.insertRg05ConfirmationAllocation(snapshot.ledgerId.value, snapshot.requestId.value, index.toLong(), allocation.itemId, allocation.categoryId.value, allocation.amount.minorUnits, allocation.amount.currency.code, allocation.amount.currency.precision.toLong()) }
            val created = createMergedPaymentExpense(catalog, MergedPaymentExpenseCommand(snapshot.ledgerId, Money.ofMinor(candidate.payment_total_minor, CurrencyUnit(candidate.currency_code, candidate.currency_precision.toInt())), snapshot.fundingAccountId, TransactionTimes(snapshot.paymentAt, snapshot.statisticsAt, snapshot.paymentAt), sourceItems), operation.formalIds)
            val aggregate = when (created) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> {
                    database.ledgerQueries.deleteRg05Request(snapshot.ledgerId.value, snapshot.requestId.value)
                    return@transactionWithResult created.violation.toRg05Rejected()
                }
            }
            persistFormal(aggregate.formalTransaction, snapshot.paymentAtText, snapshot.statisticsAtText, snapshot.paymentAtText)
            failure.failAt(Rg05FailurePoint.CONFIRM_AFTER_FORMAL)
            aggregate.postings.forEach { typed -> database.ledgerQueries.insertRg05PostingSemantic(snapshot.ledgerId.value, typed.posting.id.value, if (typed.role == MergedPaymentPostingRole.EXPENSE) "expense" else "payment_asset", typed.itemId, typed.categoryId?.value, if (typed.role == MergedPaymentPostingRole.EXPENSE) 0 else 1) }
            database.ledgerQueries.insertRg05MergedPaymentSnapshot(snapshot.ledgerId.value, snapshot.requestId.value, snapshot.paymentAtText, snapshot.statisticsAtText, candidate.payment_total_minor, candidate.currency_code, candidate.currency_precision, snapshot.fundingAccountId.value, operation.formalIds.transactionId.value, operation.relationId, operation.confirmationId)
            aggregate.consumptions.forEachIndexed { index, item -> val row = candidateItems.first { it.item_id == item.itemId }; database.ledgerQueries.insertRg05ItemSnapshot(snapshot.ledgerId.value, snapshot.requestId.value, index.toLong(), item.itemId, item.amount.minorUnits, item.amount.currency.code, item.amount.currency.precision.toLong(), item.categoryId.value, item.details, item.sourceObservedAt.toString(), operation.consumptionIds.getValue(item.itemId), operation.allocationIds.getValue(item.itemId), row.source_id, row.evidence_id, item.expensePostingId.value) }
            database.ledgerQueries.insertRg05Relation(snapshot.ledgerId.value, operation.relationId, operation.formalIds.transactionId.value, operation.formalIds.paymentAssetPostingId.value, "合并付款", candidate.payment_total_minor, candidate.currency_code, candidate.currency_precision)
            aggregate.allocations.forEachIndexed { index, allocation -> val candidateItem = candidateItems.first { it.item_id == allocation.itemId }; val kind = database.ledgerQueries.selectRg05Source(snapshot.ledgerId.value, candidateItem.source_id).executeAsOne().evidence_kind; database.ledgerQueries.insertRg05RelationItem(snapshot.ledgerId.value, operation.relationId, index.toLong(), allocation.itemId, allocation.expensePostingId.value, allocation.amount.minorUnits, allocation.categoryId.value, if (kind == "ITEM_RECEIPT") "COMPLETE" else "NONE") }
            failure.failAt(Rg05FailurePoint.CONFIRM_AFTER_RELATION)
            database.ledgerQueries.insertRg05CandidateStatus(snapshot.ledgerId.value, snapshot.candidateId, 2, snapshot.confirmedStatusId, "CONFIRMED", snapshot.requestId.value)
            database.ledgerQueries.insertRg05Confirmation(snapshot.ledgerId.value, operation.confirmationId, snapshot.requestId.value, snapshot.candidateId, operation.formalIds.transactionId.value, "CANDIDATE_CONFIRMATION")
            database.ledgerQueries.insertRg05EvidenceLink(snapshot.ledgerId.value, operation.bankEvidenceLinkId, database.ledgerQueries.selectRg05Source(snapshot.ledgerId.value, candidate.bank_source_id).executeAsOne().evidence_id, "POSTING", operation.formalIds.paymentAssetPostingId.value, "PAYMENT_ASSET_POSTING")
            candidateItems.forEach { item -> val source = database.ledgerQueries.selectRg05Source(snapshot.ledgerId.value, item.source_id).executeAsOne(); if (source.evidence_kind == "ITEM_RECEIPT") database.ledgerQueries.insertRg05EvidenceLink(snapshot.ledgerId.value, operation.itemEvidenceLinkIds.getValue(item.item_id), source.evidence_id, "ITEM_ALLOCATION", operation.allocationIds.getValue(item.item_id), "ITEM_ALLOCATION_FACT") }
            database.ledgerQueries.insertRg05PostingReconciliation(snapshot.ledgerId.value, operation.reconciliationId, operation.formalIds.paymentAssetPostingId.value, "MATCHED")
            database.ledgerQueries.insertRg05OperationReceipt(snapshot.ledgerId.value, snapshot.requestId.value, "ACCEPTED", operation.confirmationId, operation.formalIds.transactionId.value, operation.relationId)
            Rg05ExecutionResult.Accepted(operation.confirmationId, operation.formalIds.transactionId, operation.relationId)
        }
    }

    private fun resolveConfirm(operation: Rg05PreparedOperation.Confirm): Rg05ExecutionResult {
        val snapshot = operation.snapshot
        val action = database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() ?: return Rg05ExecutionResult.RequestIdentityConflict
        if (action != Rg05Action.CONFIRM_MERGED_PAYMENT_CANDIDATE.name) return Rg05ExecutionResult.RequestIdentityConflict
        val stored = database.ledgerQueries.selectRg05ConfirmationSnapshot(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() ?: return Rg05ExecutionResult.RequestIdentityConflict
        val allocations = database.ledgerQueries.selectRg05ConfirmationAllocations(snapshot.ledgerId.value, snapshot.requestId.value).executeAsList()
        val matches = stored.candidate_id == snapshot.candidateId && stored.funding_account_id == snapshot.fundingAccountId.value && stored.payment_at == snapshot.paymentAtText && stored.statistics_at == snapshot.statisticsAtText && allocations.size == snapshot.allocations.size && allocations.zip(snapshot.allocations).all { (a, b) -> a.item_id == b.itemId && a.category_id == b.categoryId.value && a.amount_minor == b.amount.minorUnits && a.currency_code == b.amount.currency.code }
        return if (matches) Rg05ExecutionResult.NoChange(operation.confirmationId, operation.formalIds.transactionId, operation.relationId) else Rg05ExecutionResult.RequestIdentityConflict
    }

    private fun commitReceipt(snapshot: Rg05ReceiptSnapshot): Rg05ExecutionResult {
        database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()?.let { return resolveReceipt(snapshot) }
        val target = database.ledgerQueries.selectRg05AllocationTarget(snapshot.ledgerId.value, snapshot.allocationId).executeAsOneOrNull()
            ?: return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_TARGET_NOT_FOUND, "item_allocation_id")
        if (snapshot.amount.minorUnits <= 0 || snapshot.amount.minorUnits != target.amount_minor || snapshot.amount.currency.code != target.currency_code || snapshot.amount.currency.precision.toLong() != target.currency_precision) return Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_TARGET_MISMATCH, "amount")
        return database.transactionWithResult {
            database.ledgerQueries.claimRg05Request(snapshot.ledgerId.value, snapshot.requestId.value, Rg05Action.MERGE_ITEM_RECEIPT_EVIDENCE.name)
            if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) return@transactionWithResult resolveReceipt(snapshot)
            database.ledgerQueries.insertRg05Source(snapshot.ledgerId.value, snapshot.sourceId, "ITEM_FACT", snapshot.evidenceId, target.item_id, "ITEM_RECEIPT", snapshot.observedAtText, snapshot.details, snapshot.amount.minorUnits, snapshot.amount.currency.code, snapshot.amount.currency.precision.toLong(), target.category_id, "COMPLETE")
            database.ledgerQueries.insertRg05Evidence(snapshot.ledgerId.value, snapshot.evidenceId, snapshot.sourceId, "ITEM_RECEIPT", snapshot.observedAtText)
            failure.failAt(Rg05FailurePoint.RECEIPT_AFTER_EVIDENCE)
            database.ledgerQueries.insertRg05EvidenceLink(snapshot.ledgerId.value, snapshot.evidenceLinkId, snapshot.evidenceId, "ITEM_ALLOCATION", snapshot.allocationId, "ITEM_ALLOCATION_FACT")
            database.ledgerQueries.insertRg05ReceiptEvidence(snapshot.ledgerId.value, snapshot.requestId.value, target.relation_id, snapshot.sourceId, snapshot.evidenceId, target.item_id, snapshot.allocationId, snapshot.evidenceLinkId, target.item_index, snapshot.observedAtText, snapshot.details, snapshot.amount.minorUnits, snapshot.amount.currency.code, snapshot.amount.currency.precision.toLong())
            database.ledgerQueries.updateRg05RelationCompleteness("COMPLETE", snapshot.ledgerId.value, target.relation_id, target.item_id)
            database.ledgerQueries.insertRg05OperationReceipt(snapshot.ledgerId.value, snapshot.requestId.value, "ACCEPTED", null, null, target.relation_id)
            Rg05ExecutionResult.ReceiptAccepted(snapshot.sourceId, snapshot.evidenceId, snapshot.evidenceLinkId)
        }
    }

    private fun resolveReceipt(snapshot: Rg05ReceiptSnapshot): Rg05ExecutionResult {
        val action = database.ledgerQueries.selectRg05Action(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() ?: return Rg05ExecutionResult.RequestIdentityConflict
        if (action != Rg05Action.MERGE_ITEM_RECEIPT_EVIDENCE.name) return Rg05ExecutionResult.RequestIdentityConflict
        val stored = database.ledgerQueries.selectRg05ReceiptCommit(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull() ?: return Rg05ExecutionResult.RequestIdentityConflict
        val matches = stored.source_id == snapshot.sourceId && stored.evidence_id == snapshot.evidenceId && stored.allocation_id == snapshot.allocationId && stored.observed_at == snapshot.observedAtText && stored.details == snapshot.details && stored.amount_minor == snapshot.amount.minorUnits && stored.currency_code == snapshot.amount.currency.code
        return if (matches) Rg05ExecutionResult.ReceiptNoChange(snapshot.sourceId, snapshot.evidenceId, stored.evidence_link_id) else Rg05ExecutionResult.RequestIdentityConflict
    }

    private fun persistFormal(value: FormalTransaction, occurredAtText: String, statisticsAtText: String, effectiveAtText: String) {
        value.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, value.transaction.ledgerId.value) }
        database.ledgerQueries.insertTransaction(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.kind.name)
        value.versions.forEach { version ->
            database.ledgerQueries.insertTransactionVersion(
                version.id.value,
                version.transactionId.value,
                value.transaction.ledgerId.value,
                version.versionNumber.toLong(),
                version.postingSetId.value,
                occurredAtText,
                statisticsAtText,
                effectiveAtText,
                version.note,
            )
        }
        database.ledgerQueries.insertTransactionCurrentVersion(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.currentVersionId.value)
        value.postingSets.forEach { set ->
            set.postings.forEachIndexed { index, posting ->
                database.ledgerQueries.insertPosting(
                    posting.id.value,
                    set.id.value,
                    value.transaction.ledgerId.value,
                    index.toLong(),
                    posting.accountId.value,
                    posting.amount.minorUnits,
                    posting.amount.currency.code,
                    posting.amount.currency.precision.toLong(),
                )
            }
        }
    }
}

private fun DomainViolation.toRg05Rejected(): Rg05ExecutionResult.Rejected = when (this) {
    DomainViolation.InvalidMergedPayment -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.EXPENSE_CATEGORY_REQUIRED, "items")
    MergedPaymentViolation.AmountMustBePositive -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.MUST_BE_POSITIVE, "total_amount")
    MergedPaymentViolation.ItemAmountMustBePositive -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.ITEM_AMOUNT_MUST_BE_POSITIVE, "items")
    MergedPaymentViolation.AllocationTotalMustEqualPayment -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_TOTAL_MUST_EQUAL_PAYMENT, "items")
    MergedPaymentViolation.DuplicateItemId -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.DUPLICATE_ITEM_ID, "items")
    MergedPaymentViolation.UnknownRealAccount -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.UNKNOWN_REAL_ACCOUNT, "funding_account_id")
    MergedPaymentViolation.RealFinancialAccountRequired -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.REAL_FINANCIAL_ACCOUNT_REQUIRED, "funding_account_id")
    MergedPaymentViolation.AssetAccountRequired -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.ASSET_ACCOUNT_REQUIRED, "funding_account_id")
    MergedPaymentViolation.OwnedAccountRequired -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.OWNED_ACCOUNT_REQUIRED, "funding_account_id")
    MergedPaymentViolation.SecondaryCategoryRequired -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.SECONDARY_CATEGORY_REQUIRED, "items")
    MergedPaymentViolation.CategoryInactive -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.CATEGORY_INACTIVE, "items")
    MergedPaymentViolation.ExpenseCategoryRequired -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.EXPENSE_CATEGORY_REQUIRED, "items")
    MergedPaymentViolation.SingleCurrencyRequired -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.SINGLE_CURRENCY_REQUIRED, "items")
    DomainViolation.ArithmeticOverflow,
    is DomainViolation.AmountNotRepresentableInCurrency,
    DomainViolation.InvalidPostingSet,
    DomainViolation.UnbalancedPostingSet,
    DomainViolation.InvalidFormalTransaction -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.INTERNAL_DOMAIN_VIOLATION, "operation")
    DomainViolation.InvalidCatalog,
    DomainViolation.InvalidOrdinaryExpense,
    DomainViolation.InvalidOrdinaryIncome,
    DomainViolation.InvalidBalanceReplay,
    DomainViolation.InvalidMixedPayment,
    DomainViolation.InvalidRefundReceipt,
    is OrdinaryExpenseViolation,
    is OrdinaryIncomeViolation,
    is AccountTransferViolation,
    is MixedPaymentViolation,
    is BalanceAdjustmentViolation,
    is PrincipalTransferViolation,
    is StoredValueViolation,
    is LendingViolation,
    is PeriodicAllocationViolation,
    is ExplicitOperationConfirmationViolation,
    is CorrectTransactionVersionViolation,
    is ReconciliationMatchViolation,
    is PostingReplacementViolation,
    is PostingReconciliationViolation,
    is CategoryRenameViolation -> Rg05ExecutionResult.Rejected(Rg05ExecutionError.INTERNAL_DOMAIN_VIOLATION, "operation")
}
