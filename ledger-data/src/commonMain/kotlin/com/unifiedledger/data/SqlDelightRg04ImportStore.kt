package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.Rg04ImportCommitPort
import com.unifiedledger.application.Rg04ImportCompleteness
import com.unifiedledger.application.Rg04ImportConfirmationSnapshot
import com.unifiedledger.application.Rg04ImportExecutionError
import com.unifiedledger.application.Rg04ImportExecutionResult
import com.unifiedledger.application.Rg04ImportMirrorSnapshot
import com.unifiedledger.application.Rg04ImportReturnedId
import com.unifiedledger.application.Rg04ImportReturnedIdKind
import com.unifiedledger.application.Rg04ImportSourceSnapshot
import com.unifiedledger.application.Rg04PreparedImportOperation
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.FundingComponent
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MixedPaymentExpenseCommand
import com.unifiedledger.domain.MixedPaymentPosting
import com.unifiedledger.domain.MixedPaymentPostingRole
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createMixedPaymentExpense
import kotlin.time.Instant

internal enum class Rg04ImportFailurePoint { SOURCE_AFTER_CANDIDATE, CONFIRMATION_AFTER_FORMAL, MIRROR_AFTER_MATCH }

internal fun interface Rg04ImportFailureInjector {
    fun failAt(point: Rg04ImportFailurePoint)
}

private val NO_RG04_IMPORT_FAILURE = Rg04ImportFailureInjector { }

class SqlDelightRg04ImportStore private constructor(
    private val database: LedgerDatabase,
    private val catalog: LedgerCatalog,
    private val failure: Rg04ImportFailureInjector,
) : Rg04ImportCommitPort {
    constructor(database: LedgerDatabase, driver: SqlDriver, catalog: LedgerCatalog) :

        this(database, catalog, NO_RG04_IMPORT_FAILURE) {

        configureSqliteConnection(driver)
    }

    internal constructor(
        database: LedgerDatabase,
        driver: SqlDriver,
        catalog: LedgerCatalog,
        failure: Rg04ImportFailureInjector,
    ) : this(database, catalog, failure) {

        configureSqliteConnection(driver)
    }

    override fun commit(operation: Rg04PreparedImportOperation): Rg04ImportExecutionResult =

        when (operation) {
            is Rg04PreparedImportOperation.StoreSource -> source(operation.snapshot)

            is Rg04PreparedImportOperation.ConfirmCandidate ->

                if (!operation.snapshot.confirmed) {
                    Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "confirmed")
                } else {
                    confirmation(operation.snapshot)
                }

            is Rg04PreparedImportOperation.MergeMirror -> mirror(operation.snapshot)
        }

    private fun source(snapshot: Rg04ImportSourceSnapshot): Rg04ImportExecutionResult =

        rollbackTypedRejection {
            database.transactionWithResult {
                database.ledgerQueries.claimRg04ImportRequest(snapshot.ledgerId.value, snapshot.requestId.value, "IMPORT_SOURCE")

                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveSource(snapshot)
                }

                val collision = ownerCollision(snapshot)

                if (!validSource(snapshot) || collision) {
                    abortRg04Import(
                        if (collision) {
                            Rg04ImportExecutionResult.RequestIdentityConflict
                        } else {
                            Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_INCOMPLETE)
                        },
                    )
                }

                database.ledgerQueries.insertRg04ImportSourceSnapshot(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    snapshot.sourceId.value,
                    snapshot.evidenceId.value,
                    snapshot.observedAtText,
                    snapshot.total.minorUnits,
                    snapshot.total.currency.code,
                    snapshot.total.currency.precision
                        .toLong(),
                    snapshot.suggestedCategoryId?.value,
                    snapshot.completeness.name,
                    snapshot.confidence,
                    snapshot.candidateKind,
                    snapshot.candidateId.value,
                    snapshot.candidateStatusId,
                )

                snapshot.funding.forEachIndexed { index, component ->

                    database.ledgerQueries.insertRg04ImportSourceComponentSnapshot(
                        snapshot.ledgerId.value,
                        snapshot.requestId.value,
                        index.toLong(),
                        component.accountId?.value,
                        component.amount.minorUnits,
                        component.amount.currency.code,
                        component.amount.currency.precision
                            .toLong(),
                        if (component.evidenceAvailable) 1 else 0,
                    )
                }

                database.ledgerQueries.insertRg04ImportSource(
                    snapshot.ledgerId.value,
                    snapshot.sourceId.value,
                    snapshot.requestId.value,
                    snapshot.evidenceId.value,
                    snapshot.observedAtText,
                    if (snapshot.completeness == Rg04ImportCompleteness.COMPLETE) "COMPLETE_MIXED_PAYMENT" else "INCOMPLETE_MIXED_PAYMENT",
                )

                database.ledgerQueries.insertRg04ImportEvidence(
                    snapshot.ledgerId.value,
                    snapshot.evidenceId.value,
                    snapshot.sourceId.value,
                    if (snapshot.completeness == Rg04ImportCompleteness.COMPLETE) "ASSET_FUNDING_DEBIT" else "KNOWN_ASSET_FUNDING_DEBIT",
                    snapshot.observedAtText,
                )

                database.ledgerQueries.insertRg04ImportCandidate(
                    snapshot.ledgerId.value,
                    snapshot.candidateId.value,
                    snapshot.sourceId.value,
                    snapshot.candidateKind,
                    snapshot.confidence,
                    if (snapshot.completeness == Rg04ImportCompleteness.COMPLETE) "complete_mixed_payment_source" else "missing_funding_leg_source",
                    1,
                )

                database.ledgerQueries.insertRg04ImportCandidateStatus(
                    snapshot.ledgerId.value,
                    snapshot.candidateId.value,
                    1,
                    snapshot.candidateStatusId,
                    "PENDING_CONFIRMATION",
                    snapshot.requestId.value,
                )

                failure.failAt(Rg04ImportFailurePoint.SOURCE_AFTER_CANDIDATE)

                database.ledgerQueries.insertRg04ImportReceipt(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    snapshot.sourceId.value,
                    snapshot.evidenceId.value,
                    snapshot.candidateId.value,
                    null,
                    null,
                    null,
                )

                Rg04ImportExecutionResult.Accepted(sourceIds(snapshot))
            }
        }

    private fun confirmation(snapshot: Rg04ImportConfirmationSnapshot): Rg04ImportExecutionResult =

        if (!snapshot.confirmed) {
            Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "confirmed")
        } else {
            rollbackTypedRejection {
                database.transactionWithResult {
                    database.ledgerQueries.claimRg04ImportRequest(snapshot.ledgerId.value, snapshot.requestId.value, "CONFIRM_CANDIDATE")

                    if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                        return@transactionWithResult resolveConfirmation(snapshot)
                    }

                    val state = database.ledgerQueries.selectRg04ImportCandidateState(snapshot.ledgerId.value, snapshot.candidateId.value).executeAsOneOrNull()

                    if (state == null) {
                        abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_NOT_FOUND))
                    }

                    if (state.status != "PENDING_CONFIRMATION") {
                        abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_NOT_PENDING))
                    }

                    if (confirmationOwnerCollision(snapshot)) {
                        abortRg04Import(Rg04ImportExecutionResult.RequestIdentityConflict)
                    }

                    val source = database.ledgerQueries.selectRg04ImportCandidateSourceSnapshot(snapshot.ledgerId.value, snapshot.candidateId.value).executeAsOne()

                    val sourceComponents = database.ledgerQueries.selectRg04ImportSourceComponents(snapshot.ledgerId.value, source.request_id).executeAsList()

                    val matchesSource =

                        source.completeness == "COMPLETE" &&

                            source.suggested_category_id == snapshot.categoryId.value &&

                            snapshot.funding.size == 2 &&

                            sourceComponents.size == 2 &&

                            sourceComponents.zip(snapshot.funding).all { (stored, confirmed) ->

                                stored.account_id == confirmed.accountId.value &&

                                    stored.amount_minor == confirmed.amount.minorUnits &&

                                    stored.currency_code == confirmed.amount.currency.code &&

                                    stored.currency_precision ==

                                    confirmed.amount.currency.precision
                                        .toLong()
                            }

                    if (!matchesSource) {
                        abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.CANDIDATE_INCOMPLETE))
                    }

                    val currency = CurrencyUnit(source.currency_code, source.currency_precision.toInt())

                    val total = Money.ofMinor(source.total_minor, currency)

                    val created =

                        createMixedPaymentExpense(
                            catalog,
                            MixedPaymentExpenseCommand(
                                snapshot.ledgerId,
                                total,
                                snapshot.categoryId,
                                snapshot.funding.map { FundingComponent(it.accountId, it.amount) },
                                TransactionTimes.collapsed(Instant.parse(source.observed_at)),
                            ),
                            snapshot.formalIds,
                        )

                    val aggregate =

                        when (created) {
                            is DomainResult.Success -> created.value

                            is DomainResult.Failure -> {
                                abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.DOMAIN_VALIDATION_FAILED))
                            }
                        }

                    database.ledgerQueries.insertRg04ImportConfirmationSnapshot(
                        snapshot.ledgerId.value,
                        snapshot.requestId.value,
                        snapshot.candidateId.value,
                        snapshot.categoryId.value,
                        1,
                        snapshot.relationId,
                        snapshot.formalIds.versionId.value,
                        snapshot.formalIds.postingSetId.value,
                        snapshot.formalIds.expensePostingId.value,
                        snapshot.formalIds.fundingPostingIds[0].value,
                        snapshot.formalIds.fundingPostingIds[1].value,
                        snapshot.confirmedStatusId,
                        snapshot.relationDisplayName,
                        snapshot.assetEvidenceLinkId,
                        snapshot.assetReconciliationId,
                        snapshot.liabilityReconciliationId,
                    )

                    snapshot.funding.forEachIndexed { index, item ->

                        database.ledgerQueries.insertRg04ImportConfirmationComponentSnapshot(
                            snapshot.ledgerId.value,
                            snapshot.requestId.value,
                            index.toLong(),
                            item.accountId.value,
                            item.amount.minorUnits,
                            item.amount.currency.code,
                            item.amount.currency.precision
                                .toLong(),
                        )
                    }

                    persistFormal(aggregate.formalTransaction, source.observed_at)

                    persistSemantics(snapshot.ledgerId, aggregate.postings)

                    persistComposition(snapshot, aggregate.postings, total)

                    failure.failAt(Rg04ImportFailurePoint.CONFIRMATION_AFTER_FORMAL)

                    database.ledgerQueries.insertRg04ImportCandidateStatus(
                        snapshot.ledgerId.value,
                        snapshot.candidateId.value,
                        2,
                        snapshot.confirmedStatusId,
                        "CONFIRMED",
                        snapshot.requestId.value,
                    )

                    database.ledgerQueries.insertRg04ImportConfirmation(
                        snapshot.ledgerId.value,
                        snapshot.confirmationId,
                        snapshot.requestId.value,
                        snapshot.candidateId.value,
                        snapshot.formalIds.transactionId.value,
                    )

                    val assetPosting =

                        aggregate.postings
                            .single { it.role == MixedPaymentPostingRole.MIXED_EXPENSE_ASSET_FUNDING }
                            .posting.id

                    val liabilityPosting =

                        aggregate.postings
                            .single { it.role == MixedPaymentPostingRole.MIXED_EXPENSE_CREDIT_FUNDING }
                            .posting.id

                    database.ledgerQueries.insertRg04PostingReconciliation(snapshot.ledgerId.value, snapshot.assetReconciliationId, assetPosting.value)

                    database.ledgerQueries.insertRg04PostingReconciliation(snapshot.ledgerId.value, snapshot.liabilityReconciliationId, liabilityPosting.value)

                    database.ledgerQueries.insertRg04ImportEvidenceMatch(
                        snapshot.ledgerId.value,
                        snapshot.assetEvidenceLinkId,
                        source.evidence_id,
                        assetPosting.value,
                        "ASSET_SOURCE",
                    )

                    database.ledgerQueries.insertRg04ReconciliationTransition(
                        snapshot.ledgerId.value,
                        snapshot.requestId.value,
                        snapshot.assetReconciliationId,
                        assetPosting.value,
                    )

                    database.ledgerQueries.markRg04PostingReconciliationMatched(snapshot.ledgerId.value, snapshot.assetReconciliationId)

                    if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                        abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.RECONCILIATION_PRECONDITION_FAILED))
                    }

                    database.ledgerQueries.deleteRg04ReconciliationTransition(
                        snapshot.ledgerId.value,
                        snapshot.requestId.value,
                        snapshot.assetReconciliationId,
                    )

                    database.ledgerQueries.insertRg04ImportReceipt(
                        snapshot.ledgerId.value,
                        snapshot.requestId.value,
                        null,
                        source.evidence_id,
                        snapshot.candidateId.value,
                        snapshot.confirmationId,
                        snapshot.formalIds.transactionId.value,
                        snapshot.assetEvidenceLinkId,
                    )

                    Rg04ImportExecutionResult.Accepted(confirmationIds(snapshot))
                }
            }
        }

    private fun mirror(snapshot: Rg04ImportMirrorSnapshot): Rg04ImportExecutionResult =

        rollbackTypedRejection {
            database.transactionWithResult {
                database.ledgerQueries.claimRg04ImportRequest(snapshot.ledgerId.value, snapshot.requestId.value, "MERGE_MIRROR")

                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveMirror(snapshot)
                }

                if (mirrorOwnerCollision(snapshot)) {
                    abortRg04Import(Rg04ImportExecutionResult.RequestIdentityConflict)
                }

                val allTargets = database.ledgerQueries.selectRg04ImportMirrorTargets(snapshot.ledgerId.value).executeAsList()

                if (allTargets.isEmpty()) {
                    abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.MIRROR_TARGET_NOT_FOUND))
                }

                val matches =

                    allTargets.filter {
                        it.account_id == snapshot.accountId.value &&

                            it.amount_minor == snapshot.amount.minorUnits &&

                            it.currency_code == snapshot.amount.currency.code &&

                            it.currency_precision ==

                            snapshot.amount.currency.precision
                                .toLong() &&

                            it.occurred_at == snapshot.observedAtText
                    }

                if (matches.isEmpty()) {
                    abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.MIRROR_TARGET_MISMATCH))
                }

                if (matches.size != 1) {
                    abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.AMBIGUOUS_MIRROR_TARGET))
                }

                val target = matches.single()

                val liabilityStatus = database.ledgerQueries.selectRg04PostingReconciliationStatus(snapshot.ledgerId.value, target.posting_id).executeAsOneOrNull()

                val assetPosting = database.ledgerQueries.selectRg04ImportAssetPosting(snapshot.ledgerId.value, target.candidate_id).executeAsOneOrNull()

                val assetStatus =

                    assetPosting?.let {
                        database.ledgerQueries.selectRg04PostingReconciliationStatus(snapshot.ledgerId.value, it).executeAsOneOrNull()
                    }

                if (liabilityStatus != "PENDING" ||

                    assetStatus != "MATCHED" ||

                    database.ledgerQueries.countRg04ImportMatchesForPosting(snapshot.ledgerId.value, target.posting_id).executeAsOne() != 0L

                ) {
                    abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.RECONCILIATION_PRECONDITION_FAILED))
                }

                database.ledgerQueries.insertRg04ImportMirrorSnapshot(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    snapshot.sourceId.value,
                    snapshot.evidenceId.value,
                    snapshot.observedAtText,
                    snapshot.accountId.value,
                    snapshot.amount.minorUnits,
                    snapshot.amount.currency.code,
                    snapshot.amount.currency.precision
                        .toLong(),
                    snapshot.evidenceLinkId,
                )

                database.ledgerQueries.insertRg04ImportSource(
                    snapshot.ledgerId.value,
                    snapshot.sourceId.value,
                    snapshot.requestId.value,
                    snapshot.evidenceId.value,
                    snapshot.observedAtText,
                    "LIABILITY_MIRROR",
                )

                database.ledgerQueries.insertRg04ImportEvidence(
                    snapshot.ledgerId.value,
                    snapshot.evidenceId.value,
                    snapshot.sourceId.value,
                    "LIABILITY_MIRROR",
                    snapshot.observedAtText,
                )

                database.ledgerQueries.insertRg04ImportEvidenceMatch(
                    snapshot.ledgerId.value,
                    snapshot.evidenceLinkId,
                    snapshot.evidenceId.value,
                    target.posting_id,
                    "LIABILITY_MIRROR",
                )

                val liabilityReconciliation =

                    database.ledgerQueries
                        .selectRg04ImportConfirmationByCandidate(snapshot.ledgerId.value, target.candidate_id)
                        .executeAsOneOrNull()

                if (liabilityReconciliation == null) {
                    abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.RECONCILIATION_PRECONDITION_FAILED))
                }

                database.ledgerQueries.insertRg04ReconciliationTransition(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    liabilityReconciliation,
                    target.posting_id,
                )

                database.ledgerQueries.markRg04PostingReconciliationMatched(snapshot.ledgerId.value, liabilityReconciliation)

                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    abortRg04Import(Rg04ImportExecutionResult.Rejected(Rg04ImportExecutionError.RECONCILIATION_PRECONDITION_FAILED))
                }

                database.ledgerQueries.deleteRg04ReconciliationTransition(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    liabilityReconciliation,
                )

                failure.failAt(Rg04ImportFailurePoint.MIRROR_AFTER_MATCH)

                database.ledgerQueries.insertRg04ImportReceipt(
                    snapshot.ledgerId.value,
                    snapshot.requestId.value,
                    snapshot.sourceId.value,
                    snapshot.evidenceId.value,
                    target.candidate_id,
                    null,
                    target.transaction_id,
                    snapshot.evidenceLinkId,
                )

                Rg04ImportExecutionResult.Accepted(mirrorIds(snapshot))
            }
        }

    private fun validSource(snapshot: Rg04ImportSourceSnapshot): Boolean =

        snapshot.funding.size == 2 &&

            snapshot.funding.all { it.amount.minorUnits > 0 && it.amount.currency == snapshot.total.currency } &&

            snapshot.funding.sumOf { it.amount.minorUnits } == snapshot.total.minorUnits &&

            when (snapshot.completeness) {
                Rg04ImportCompleteness.COMPLETE -> snapshot.funding.all { it.accountId != null } && snapshot.suggestedCategoryId != null

                Rg04ImportCompleteness.MISSING_FUNDING_LEG -> snapshot.funding.count { it.accountId == null } == 1 && snapshot.suggestedCategoryId == null
            }

    private fun ownerCollision(snapshot: Rg04ImportSourceSnapshot): Boolean {
        val sourceOwner = database.ledgerQueries.selectRg04ImportSourceOwner(snapshot.ledgerId.value, snapshot.sourceId.value).executeAsOneOrNull()

        val evidenceOwner = database.ledgerQueries.selectRg04ImportEvidenceOwner(snapshot.ledgerId.value, snapshot.evidenceId.value).executeAsOneOrNull()

        val candidateOwner = database.ledgerQueries.selectRg04ImportCandidateOwner(snapshot.ledgerId.value, snapshot.candidateId.value).executeAsOneOrNull()

        val statusOwner = database.ledgerQueries.selectRg04ImportStatusOwner(snapshot.ledgerId.value, snapshot.candidateStatusId).executeAsOneOrNull()

        return sourceOwner != null || evidenceOwner != null || candidateOwner != null || statusOwner != null
    }

    private fun mirrorOwnerCollision(snapshot: Rg04ImportMirrorSnapshot): Boolean =

        database.ledgerQueries.selectRg04ImportSourceOwner(snapshot.ledgerId.value, snapshot.sourceId.value).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportEvidenceOwner(snapshot.ledgerId.value, snapshot.evidenceId.value).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportMatchOwner(snapshot.ledgerId.value, snapshot.evidenceLinkId).executeAsOneOrNull() != null

    private fun confirmationOwnerCollision(snapshot: Rg04ImportConfirmationSnapshot): Boolean =

        database.ledgerQueries.selectRg04ImportStatusOwner(snapshot.ledgerId.value, snapshot.confirmedStatusId).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportConfirmationOwner(snapshot.ledgerId.value, snapshot.confirmationId).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportRelationOwner(snapshot.ledgerId.value, snapshot.relationId).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportTransactionOwner(snapshot.ledgerId.value, snapshot.formalIds.transactionId.value).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportVersionOwner(snapshot.ledgerId.value, snapshot.formalIds.versionId.value).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportPostingSetOwner(snapshot.ledgerId.value, snapshot.formalIds.postingSetId.value).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportPostingOwner(snapshot.ledgerId.value, snapshot.formalIds.expensePostingId.value).executeAsOneOrNull() != null ||

            snapshot.formalIds.fundingPostingIds.any { database.ledgerQueries.selectRg04ImportPostingOwner(snapshot.ledgerId.value, it.value).executeAsOneOrNull() != null } ||

            database.ledgerQueries.selectRg04ImportMatchOwner(snapshot.ledgerId.value, snapshot.assetEvidenceLinkId).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportReconciliationOwner(snapshot.ledgerId.value, snapshot.assetReconciliationId).executeAsOneOrNull() != null ||

            database.ledgerQueries.selectRg04ImportReconciliationOwner(snapshot.ledgerId.value, snapshot.liabilityReconciliationId).executeAsOneOrNull() != null

    private fun resolveSource(snapshot: Rg04ImportSourceSnapshot): Rg04ImportExecutionResult {
        val stored =

            database.ledgerQueries.selectRg04ImportSourceCommit(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()

                ?: return Rg04ImportExecutionResult.RequestIdentityConflict

        val components = database.ledgerQueries.selectRg04ImportSourceComponents(snapshot.ledgerId.value, snapshot.requestId.value).executeAsList()

        val matches =

            stored.action_type == "IMPORT_SOURCE" &&

                stored.source_id == snapshot.sourceId.value &&

                stored.evidence_id == snapshot.evidenceId.value &&

                stored.observed_at == snapshot.observedAtText &&

                stored.total_minor == snapshot.total.minorUnits &&

                stored.currency_code == snapshot.total.currency.code &&

                stored.currency_precision ==

                snapshot.total.currency.precision
                    .toLong() &&

                stored.suggested_category_id == snapshot.suggestedCategoryId?.value &&

                stored.completeness == snapshot.completeness.name &&

                stored.confidence == snapshot.confidence &&

                stored.candidate_kind == snapshot.candidateKind &&

                stored.candidate_id == snapshot.candidateId.value &&

                stored.candidate_status_id == snapshot.candidateStatusId &&

                components.size == snapshot.funding.size &&

                components.zip(snapshot.funding).all { (a, b) ->

                    a.account_id == b.accountId?.value &&

                        a.amount_minor == b.amount.minorUnits &&

                        a.currency_code == b.amount.currency.code &&

                        a.currency_precision ==

                        b.amount.currency.precision
                            .toLong() &&

                        a.evidence_available == if (b.evidenceAvailable) 1L else 0L
                }

        return if (matches) Rg04ImportExecutionResult.NoChange(sourceIds(snapshot)) else Rg04ImportExecutionResult.RequestIdentityConflict
    }

    private fun resolveConfirmation(snapshot: Rg04ImportConfirmationSnapshot): Rg04ImportExecutionResult {
        val stored =

            database.ledgerQueries.selectRg04ImportConfirmationCommit(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()

                ?: return Rg04ImportExecutionResult.RequestIdentityConflict

        val components = database.ledgerQueries.selectRg04ImportConfirmationComponents(snapshot.ledgerId.value, snapshot.requestId.value).executeAsList()

        val matches =

            stored.action_type == "CONFIRM_CANDIDATE" &&

                stored.candidate_id == snapshot.candidateId.value &&

                stored.category_id == snapshot.categoryId.value &&

                stored.confirmed == 1L &&

                stored.relation_id == snapshot.relationId &&

                stored.version_id == snapshot.formalIds.versionId.value &&

                stored.posting_set_id == snapshot.formalIds.postingSetId.value &&

                stored.expense_posting_id == snapshot.formalIds.expensePostingId.value &&

                stored.asset_posting_id == snapshot.formalIds.fundingPostingIds[0].value &&

                stored.liability_posting_id == snapshot.formalIds.fundingPostingIds[1].value &&

                stored.confirmed_status_id == snapshot.confirmedStatusId &&

                stored.relation_display_name == snapshot.relationDisplayName &&

                stored.asset_evidence_link_id == snapshot.assetEvidenceLinkId &&

                stored.asset_reconciliation_id == snapshot.assetReconciliationId &&

                stored.liability_reconciliation_id == snapshot.liabilityReconciliationId &&

                stored.confirmation_id == snapshot.confirmationId &&

                stored.transaction_id == snapshot.formalIds.transactionId.value &&

                components.size == snapshot.funding.size &&

                components.zip(snapshot.funding).all { (a, b) ->

                    a.account_id == b.accountId.value &&

                        a.amount_minor == b.amount.minorUnits &&

                        a.currency_code == b.amount.currency.code &&

                        a.currency_precision ==

                        b.amount.currency.precision
                            .toLong()
                }

        return if (matches) Rg04ImportExecutionResult.NoChange(confirmationIds(snapshot)) else Rg04ImportExecutionResult.RequestIdentityConflict
    }

    private fun resolveMirror(snapshot: Rg04ImportMirrorSnapshot): Rg04ImportExecutionResult {
        val stored =

            database.ledgerQueries.selectRg04ImportMirrorCommit(snapshot.ledgerId.value, snapshot.requestId.value).executeAsOneOrNull()

                ?: return Rg04ImportExecutionResult.RequestIdentityConflict

        val matches =

            stored.action_type == "MERGE_MIRROR" &&

                stored.source_id == snapshot.sourceId.value &&

                stored.evidence_id == snapshot.evidenceId.value &&

                stored.observed_at == snapshot.observedAtText &&

                stored.account_id == snapshot.accountId.value &&

                stored.amount_minor == snapshot.amount.minorUnits &&

                stored.currency_code == snapshot.amount.currency.code &&

                stored.currency_precision ==

                snapshot.amount.currency.precision
                    .toLong() &&

                stored.evidence_link_id == snapshot.evidenceLinkId

        return if (matches) Rg04ImportExecutionResult.NoChange(mirrorIds(snapshot)) else Rg04ImportExecutionResult.RequestIdentityConflict
    }

    private fun persistFormal(
        value: FormalTransaction,
        time: String,
    ) {
        value.postingSets.forEach { database.ledgerQueries.insertPostingSet(it.id.value, value.transaction.ledgerId.value) }

        database.ledgerQueries.insertTransaction(value.transaction.id.value, value.transaction.ledgerId.value, value.transaction.kind.name)

        value.versions.forEach { database.ledgerQueries.insertTransactionVersion(it.id.value, it.transactionId.value, value.transaction.ledgerId.value, it.versionNumber.toLong(), it.postingSetId.value, time, time, time, it.note) }

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
                    posting.amount.currency.precision
                        .toLong(),
                )
            }
        }
    }

    private fun persistSemantics(
        ledgerId: LedgerId,
        postings: List<MixedPaymentPosting>,
    ) = postings.forEach {
        database.ledgerQueries.insertRg04PostingSemantic(ledgerId.value, it.posting.id.value, it.role.name.lowercase(), it.categoryId?.value, if (it.role == MixedPaymentPostingRole.EXPENSE) 0 else 1)
    }

    private fun persistComposition(
        snapshot: Rg04ImportConfirmationSnapshot,
        postings: List<MixedPaymentPosting>,
        total: Money,
    ) {
        database.ledgerQueries.insertFormalRelation(snapshot.ledgerId.value, snapshot.relationId)

        database.ledgerQueries.insertFormalRelationMember(snapshot.ledgerId.value, snapshot.relationId, 0, "TRANSACTION", snapshot.formalIds.transactionId.value, null)

        postings.filter { it.role != MixedPaymentPostingRole.EXPENSE }.forEachIndexed { index, typed ->

            database.ledgerQueries.insertFormalRelationMember(snapshot.ledgerId.value, snapshot.relationId, (index + 1).toLong(), "POSTING", null, typed.posting.id.value)
        }

        database.ledgerQueries.insertRg04MixedComposition(snapshot.ledgerId.value, snapshot.relationId, snapshot.formalIds.transactionId.value, snapshot.relationDisplayName, total.minorUnits, total.currency.code, total.currency.precision.toLong())

        postings.filter { it.role != MixedPaymentPostingRole.EXPENSE }.forEachIndexed { index, typed ->

            val funding = snapshot.funding.single { it.accountId == typed.posting.accountId }

            database.ledgerQueries.insertRg04MixedCompositionComponent(
                snapshot.ledgerId.value,
                snapshot.relationId,
                index.toLong(),
                typed.posting.id.value,
                typed.posting.accountId.value,
                funding.amount.minorUnits,
                funding.amount.currency.code,
                funding.amount.currency.precision
                    .toLong(),
            )
        }
    }
}

private class Rg04ImportTypedRollback(
    val result: Rg04ImportExecutionResult,
) : RuntimeException()

private fun abortRg04Import(result: Rg04ImportExecutionResult): Nothing = throw Rg04ImportTypedRollback(result)

private inline fun rollbackTypedRejection(block: () -> Rg04ImportExecutionResult): Rg04ImportExecutionResult =

    try {
        block()
    } catch (failure: Rg04ImportTypedRollback) {
        failure.result
    }

private fun sourceIds(snapshot: Rg04ImportSourceSnapshot) =

    listOf(
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.SOURCE, snapshot.sourceId.value),
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.EVIDENCE, snapshot.evidenceId.value),
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.CANDIDATE, snapshot.candidateId.value),
    )

private fun confirmationIds(snapshot: Rg04ImportConfirmationSnapshot) =

    listOf(
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.CONFIRMATION, snapshot.confirmationId),
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.TRANSACTION, snapshot.formalIds.transactionId.value),
    )

private fun mirrorIds(snapshot: Rg04ImportMirrorSnapshot) =

    listOf(
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.SOURCE, snapshot.sourceId.value),
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.EVIDENCE, snapshot.evidenceId.value),
        Rg04ImportReturnedId(Rg04ImportReturnedIdKind.EVIDENCE_LINK, snapshot.evidenceLinkId),
    )
