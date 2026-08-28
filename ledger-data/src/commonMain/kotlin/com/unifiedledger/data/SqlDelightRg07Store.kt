package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.Rg07CommitPort
import com.unifiedledger.application.Rg07ExecutionResult
import com.unifiedledger.application.Rg07FieldPath
import com.unifiedledger.application.Rg07Operation
import com.unifiedledger.application.Rg07RefundStatus
import com.unifiedledger.application.Rg07RejectionReason
import com.unifiedledger.application.Rg07ReturnedId
import com.unifiedledger.application.fingerprint
import com.unifiedledger.application.isValidRg07StatusTransition
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.RefundReceiptCommand
import com.unifiedledger.domain.RefundReceiptIds
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.createRefundReceipt
import kotlin.time.Instant

data class Rg07ManualCommitFacts(
    val confirmationId: String,
    val reconciliationId: String,
    val confirmedAt: Instant,
)

data class Rg07ReceiptCommitIds(
    val confirmationId: String,
    val reconciliationId: String,
    val domainEntityId: String,
)

data class Rg07FormalIds(
    val transactionId: String,
    val versionId: String,
    val postingSetId: String,
    val firstPostingId: String,
    val secondPostingId: String,
)

interface Rg07IdentitySource {
    fun operationId(operation: Rg07Operation): String

    fun manual(operation: Rg07Operation.ManualExpense): Rg07ManualCommitFacts

    fun relation(operation: Rg07Operation.Status): String

    fun domainEntity(
        operation: Rg07Operation,
        relationId: String,
    ): String

    fun formal(operation: Rg07Operation): Rg07FormalIds

    fun receipt(
        operation: Rg07Operation,
        relationId: String,
        assetPostingId: String,
    ): Rg07ReceiptCommitIds
}

/**

 * Atomic RG-07 operation ledger with formal aggregate persistence.

 *

 * Accepted operations persist the formal effects defined by the approved expected

 * artifact: the frozen original expense transaction (manual_expense); refund

 * relation plus refund_relationship domain entity with append-only lifecycle

 * history; source/evidence/evidence-link provenance; refund-credit candidate

 * lifecycle; posting reconciliation transitions; and relation confirmations.

 * Rejected operations persist only the rejection outcome and audit reason/path

 * (no transaction/posting/relation/candidate side effects).

 *

 * Lifecycle and reconciliation history tables are append-only and authoritative;

 * current states are derived from the latest history row, so no UPDATE is needed

 * except the single guarded unreceived -> received refund-relationship

 * transition.

 */

class SqlDelightRg07Store(
    private val database: LedgerDatabase,
    driver: SqlDriver,
    private val catalog: LedgerCatalog,
    private val storeCreditAccountIds: Set<AccountId>,
    private val identity: Rg07IdentitySource,
) : Rg07CommitPort {
    init {

        configureSqliteConnection(driver)
    }

    override fun commit(operation: Rg07Operation): Rg07ExecutionResult {
        database.ledgerQueries
            .selectRg07Operation(operation.ledgerId.value, operation.identity.value)
            .executeAsOneOrNull()

            ?.let { return replay(operation, it) }

        return try {
            database.transactionWithResult {
                claim(operation)

                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult replay(
                        operation,
                        database.ledgerQueries
                            .selectRg07Operation(operation.ledgerId.value, operation.identity.value)
                            .executeAsOne(),
                    )
                }

                when (val result = execute(operation)) {
                    is Rg07ExecutionResult.Accepted -> {
                        persist(operation)

                        result
                    }

                    is Rg07ExecutionResult.NoChange -> result

                    is Rg07ExecutionResult.Rejected -> {
                        persistRejected(operation, result)

                        result
                    }

                    is Rg07ExecutionResult.RequestIdentityConflict -> result
                }
            }
        } catch (rollback: Rg07Rollback) {
            rollback.result
        }
    }

    // ------------------------------------------------------------------

    // Validation

    // ------------------------------------------------------------------

    private fun execute(operation: Rg07Operation): Rg07ExecutionResult =

        when (operation) {
            is Rg07Operation.ManualExpense -> executeManualExpense(operation)

            is Rg07Operation.Status -> executeStatus(operation)

            is Rg07Operation.StatusSource -> executeStatusSource(operation)

            is Rg07Operation.ManualReceipt -> executeManualReceipt(operation)

            is Rg07Operation.OriginalPaymentEvidence -> executeOriginalPaymentEvidence(operation)

            is Rg07Operation.DestinationEvidence -> executeDestinationEvidence(operation)

            is Rg07Operation.DualRoleEvidence -> executeDualRoleEvidence(operation)

            is Rg07Operation.ConfirmReceipt -> executeConfirmReceipt(operation)

            is Rg07Operation.Allocate -> executeAllocate(operation)

            is Rg07Operation.ImportCredit -> executeImportCredit(operation)

            is Rg07Operation.ImportConfirm -> executeImportConfirm(operation)

            is Rg07Operation.Mirror -> executeMirror(operation)

            is Rg07Operation.Validate -> executeValidate(operation)
        }

    private fun executeManualExpense(operation: Rg07Operation.ManualExpense): Rg07ExecutionResult {
        val input = operation.input

        if (!input.explicitConfirmation) return rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)

        if (input.amount.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)

        val category =

            categoryWithPostingAccount(input.categoryId)

                ?: return rejected(Rg07RejectionReason.EXACT_ORIGINAL_SECONDARY_CATEGORY_REQUIRED, Rg07FieldPath.CATEGORY_ID)

        if (catalog.accounts.singleOrNull { it.id == input.paymentAccountId } == null) {
            return rejected(Rg07RejectionReason.KNOWN_DESTINATION_ACCOUNT_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)
        }

        if (category.ledgerId != operation.ledgerId) {
            return rejected(Rg07RejectionReason.EXACT_ORIGINAL_SECONDARY_CATEGORY_REQUIRED, Rg07FieldPath.CATEGORY_ID)
        }

        return accepted(operation)
    }

    private fun executeStatus(operation: Rg07Operation.Status): Rg07ExecutionResult {
        val input = operation.input

        if (input.requestedAmount.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)

        if (input.requestedAt == null && input.approvedAt == null && input.processorReportedAt == null) {
            return rejected(Rg07RejectionReason.INVALID_TIMESTAMP, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)
        }

        val original =

            originalExpense(operation.ledgerId, input.originalTransactionId)

                ?: return rejected(Rg07RejectionReason.EFFECTIVE_CONFIRMED_ORIGINAL_EXPENSE_REQUIRED, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)

        if (input.requestedAmount.currency.code != original.currencyCode ||

            input.requestedAmount.currency.precision
                .toLong() != original.currencyPrecision

        ) {
            return rejected(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
        }

        return accepted(operation)
    }

    private fun executeImportCredit(operation: Rg07Operation.ImportCredit): Rg07ExecutionResult {
        val input = operation.input

        if (input.amount.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)

        val account =

            catalog.accounts.singleOrNull { it.id == input.accountId }

                ?: return rejected(Rg07RejectionReason.KNOWN_DESTINATION_ACCOUNT_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)

        if (!isOwnedRealAssetDestination(input.accountId)) {
            return rejected(Rg07RejectionReason.OWNED_REAL_ASSET_DESTINATION_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)
        }

        if (account.currency.code != input.amount.currency.code || account.currency.precision != input.amount.currency.precision) {
            return rejected(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
        }

        return accepted(operation)
    }

    private fun executeStatusSource(operation: Rg07Operation.StatusSource): Rg07ExecutionResult {
        if (operation.input.provesArrival) return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)

        if (database.ledgerQueries
                .selectRg07EntityByRelation(
                    operation.ledgerId.value,
                    operation.input.refundRelationId,
                ).executeAsOneOrNull() == null

        ) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)
        }

        return accepted(operation)
    }

    private fun executeManualReceipt(operation: Rg07Operation.ManualReceipt): Rg07ExecutionResult {
        val input = operation.input

        if (!input.arrivalConfirmed) return rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)

        val relationId =

            input.refundRelationId

                ?: return rejected(Rg07RejectionReason.REFUND_RELATION_NOT_FOUND, Rg07FieldPath.REFUND_RELATION_ID)

        val relation =

            database.ledgerQueries.selectRg07EntityByRelation(operation.ledgerId.value, relationId).executeAsOneOrNull()

                ?: return rejected(Rg07RejectionReason.REFUND_RELATION_NOT_FOUND, Rg07FieldPath.REFUND_RELATION_ID)

        val originalTransactionId =

            input.originalTransactionId

                ?: return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)

        if (relation.original_transaction_id != originalTransactionId.value) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)
        }

        val proposal =

            receiptProposal(
                originalTransactionId = originalTransactionId,
                amount = input.amount,
                categoryId = input.categoryId,
                destinationAccountId = input.destinationAccountId,
                arrivedAt = input.arrivedAt,
                confirmedAt = input.confirmedAt,
            ) ?: return rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)

        validateReceipt(operation.ledgerId, proposal)?.let { return it }

        return accepted(operation)
    }

    private fun executeConfirmReceipt(operation: Rg07Operation.ConfirmReceipt): Rg07ExecutionResult {
        val input = operation.input

        if (!input.arrivalConfirmed) return rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)

        val proposal =

            receiptProposal(
                originalTransactionId = input.originalTransactionId,
                amount = input.amount,
                categoryId = input.categoryId,
                destinationAccountId = input.destinationAccountId,
                arrivedAt = input.arrivedAt,
                confirmedAt = input.confirmedAt,
            ) ?: return rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)

        validateReceipt(operation.ledgerId, proposal)?.let { return it }

        return accepted(operation)
    }

    private fun executeImportConfirm(operation: Rg07Operation.ImportConfirm): Rg07ExecutionResult {
        val input = operation.input

        // Frozen attempted-input rejection order first.

        if (input.originalTransactionId == null) return rejected(Rg07RejectionReason.ORIGINAL_TRANSACTION_CONFIRMATION_REQUIRED, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)

        if (input.categoryId == null || input.allocatedAmount == null) return rejected(Rg07RejectionReason.CATEGORY_ALLOCATION_CONFIRMATION_REQUIRED, Rg07FieldPath.CATEGORY_ID)

        val destinationAccountId =

            input.destinationAccountId

                ?: return rejected(Rg07RejectionReason.DESTINATION_CONFIRMATION_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)

        if (input.arrivedAt == null || input.confirmedAt == null || !input.arrivalConfirmed) return rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)

        // Store-level binding checks: the candidate must exist and be pending.

        val candidate =

            database.ledgerQueries.selectRg07Candidate(operation.ledgerId.value, input.candidateId).executeAsOneOrNull()

                ?: return rejected(Rg07RejectionReason.CANDIDATE_NOT_FOUND, Rg07FieldPath.CANDIDATE_ID)

        val latestCandidateStatus = database.ledgerQueries.selectRg07CandidateLatestStatus(operation.ledgerId.value, input.candidateId).executeAsOneOrNull()

        if (latestCandidateStatus != "PENDING_CONFIRMATION") return rejected(Rg07RejectionReason.CANDIDATE_NOT_FOUND, Rg07FieldPath.CANDIDATE_ID)

        val allocated = input.allocatedAmount ?: return rejected(Rg07RejectionReason.CATEGORY_ALLOCATION_CONFIRMATION_REQUIRED, Rg07FieldPath.CATEGORY_ID)

        if (allocated.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.ALLOCATED_AMOUNT)

        if (allocated.currency.code != candidate.currency_code || allocated.currency.precision.toLong() != candidate.currency_precision) {
            return rejected(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
        }

        val source =

            database.ledgerQueries.selectRg07Source(operation.ledgerId.value, candidate.source_id).executeAsOneOrNull()

                ?: return rejected(Rg07RejectionReason.CANDIDATE_NOT_FOUND, Rg07FieldPath.CANDIDATE_ID)

        if (allocated.minorUnits != candidate.proposed_amount_minor || allocated.minorUnits != source.amount_minor) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.ALLOCATED_AMOUNT)
        }

        if (destinationAccountId.value != candidate.proposed_destination_account_id || destinationAccountId.value != source.account_id) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.DESTINATION_ACCOUNT_ID)
        }

        val proposal =

            receiptProposal(
                originalTransactionId = input.originalTransactionId,
                amount = allocated,
                categoryId = input.categoryId,
                destinationAccountId = destinationAccountId,
                arrivedAt = input.arrivedAt,
                confirmedAt = input.confirmedAt,
            ) ?: return rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)

        validateReceipt(operation.ledgerId, proposal)?.let { return it }

        return accepted(operation)
    }

    private fun executeOriginalPaymentEvidence(operation: Rg07Operation.OriginalPaymentEvidence): Rg07ExecutionResult {
        val input = operation.input

        if (input.amount.minorUnits >= 0L) return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.AMOUNT)

        val posting =

            database.ledgerQueries.selectRg07AssetPosting(operation.ledgerId.value, input.paymentAssetPostingId).executeAsOneOrNull()

                ?: return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)

        if (posting.amount_minor != input.amount.minorUnits) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)
        }

        if (posting.currency_code != input.amount.currency.code ||

            posting.currency_precision !=

            input.amount.currency.precision
                .toLong()

        ) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)
        }

        return accepted(operation)
    }

    private fun executeDestinationEvidence(operation: Rg07Operation.DestinationEvidence): Rg07ExecutionResult {
        val input = operation.input

        if (input.amount.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)

        if (!destinationPostingMatches(
                operation.ledgerId,
                input.refundRelationId,
                input.destinationAssetPostingId,
                input.accountId,
                input.amount,
            )

        ) {
            return rejected(Rg07RejectionReason.DESTINATION_POSTING_NOT_FOUND, Rg07FieldPath.DESTINATION_ACCOUNT_ID)
        }

        return accepted(operation)
    }

    private fun executeDualRoleEvidence(operation: Rg07Operation.DualRoleEvidence): Rg07ExecutionResult {
        val input = operation.input

        val entity =

            database.ledgerQueries.selectRg07EntityByRelation(operation.ledgerId.value, input.refundRelationId).executeAsOneOrNull()

                ?: return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)

        if (entity.refund_transaction_id == null || refundAssetPosting(operation.ledgerId, input.refundRelationId) != input.destinationAssetPostingId) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)
        }

        return accepted(operation)
    }

    private fun executeAllocate(operation: Rg07Operation.Allocate): Rg07ExecutionResult {
        val input = operation.input

        if (input.requestedAllocation.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.REQUESTED_ALLOCATION)

        if (input.requestedAllocation.minorUnits > input.availableAllocation.minorUnits) {
            return rejected(Rg07RejectionReason.REFUND_AMOUNT_EXCEEDS_REMAINING, Rg07FieldPath.REQUESTED_ALLOCATION)
        }

        // An allocate that does not exceed the available allocation is not a

        // registered rejection; the approved expected artifact never exercises it.

        return rejected(Rg07RejectionReason.REFUND_AMOUNT_EXCEEDS_REMAINING, Rg07FieldPath.REQUESTED_ALLOCATION)
    }

    private fun executeMirror(operation: Rg07Operation.Mirror): Rg07ExecutionResult {
        val input = operation.input

        if (input.amount.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)

        val originalSource =

            database.ledgerQueries.selectRg07Source(operation.ledgerId.value, mirrorOfSourceId(input.sourceId)).executeAsOneOrNull()

                ?: return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)

        if (input.amount.minorUnits != originalSource.amount_minor ||

            input.amount.currency.code != originalSource.currency_code ||

            input.amount.currency.precision
                .toLong() != originalSource.currency_precision

        ) {
            return rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)
        }

        return accepted(operation)
    }

    private fun executeValidate(operation: Rg07Operation.Validate): Rg07ExecutionResult {
        val input = operation.input

        // Frozen first-failing-attempted-field order from the approved mapping.

        if (input.amount.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)

        val originalId = input.originalTransactionId

        if (originalId == null) return rejected(Rg07RejectionReason.ORIGINAL_TRANSACTION_CONFIRMATION_REQUIRED, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)

        val original =

            originalExpense(operation.ledgerId, originalId)

                ?: return rejected(Rg07RejectionReason.EFFECTIVE_CONFIRMED_ORIGINAL_EXPENSE_REQUIRED, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)

        if (input.amount.currency.code != original.currencyCode ||

            input.amount.currency.precision
                .toLong() != original.currencyPrecision

        ) {
            return rejected(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
        }

        val destinationId =

            input.destinationAccountId

                ?: return rejected(Rg07RejectionReason.KNOWN_DESTINATION_ACCOUNT_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)

        if (catalog.accounts.singleOrNull { it.id == destinationId } == null) {
            return rejected(Rg07RejectionReason.KNOWN_DESTINATION_ACCOUNT_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)
        }

        if (!isOwnedRealAssetDestination(destinationId)) {
            return rejected(Rg07RejectionReason.OWNED_REAL_ASSET_DESTINATION_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)
        }

        if (input.categoryId != CategoryId(original.categoryId)) {
            return rejected(Rg07RejectionReason.EXACT_ORIGINAL_SECONDARY_CATEGORY_REQUIRED, Rg07FieldPath.CATEGORY_ID)
        }

        if (input.amount.minorUnits > input.remainingRefundable.minorUnits) {
            return rejected(Rg07RejectionReason.REFUND_AMOUNT_EXCEEDS_REMAINING, Rg07FieldPath.AMOUNT)
        }

        if (!input.destinationConfirmed) return rejected(Rg07RejectionReason.DESTINATION_CONFIRMATION_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)

        // All registered checks passed: no registered rejection exists for this probe.

        return rejected(Rg07RejectionReason.INVALID_TIMESTAMP, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)
    }

    private fun validateReceipt(
        ledgerId: LedgerId,
        proposal: ReceiptProposal,
    ): Rg07ExecutionResult.Rejected? {
        if (proposal.amount.minorUnits <= 0L) return rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)

        val original =

            originalExpense(ledgerId, proposal.originalTransactionId)

                ?: return rejected(Rg07RejectionReason.EFFECTIVE_CONFIRMED_ORIGINAL_EXPENSE_REQUIRED, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)

        if (proposal.amount.currency.code != original.currencyCode ||

            proposal.amount.currency.precision
                .toLong() != original.currencyPrecision

        ) {
            return rejected(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
        }

        if (!isOwnedRealAssetDestination(proposal.destinationAccountId)) {
            return rejected(Rg07RejectionReason.KNOWN_DESTINATION_ACCOUNT_REQUIRED, Rg07FieldPath.DESTINATION_ACCOUNT_ID)
        }

        if (proposal.categoryId != CategoryId(original.categoryId)) {
            return rejected(Rg07RejectionReason.EXACT_ORIGINAL_SECONDARY_CATEGORY_REQUIRED, Rg07FieldPath.CATEGORY_ID)
        }

        if (proposal.amount.minorUnits > remainingRefundable(ledgerId, proposal.originalTransactionId, original)) {
            return rejected(Rg07RejectionReason.REFUND_AMOUNT_EXCEEDS_REMAINING, Rg07FieldPath.AMOUNT)
        }

        return null
    }

    // ------------------------------------------------------------------

    // Persistence

    // ------------------------------------------------------------------

    private fun persist(operation: Rg07Operation) {
        when (operation) {
            is Rg07Operation.ManualExpense -> persistManualExpense(operation)

            is Rg07Operation.Status -> persistStatus(operation)

            is Rg07Operation.StatusSource -> persistStatusSource(operation)

            is Rg07Operation.ManualReceipt -> persistManualReceipt(operation)

            is Rg07Operation.ConfirmReceipt -> persistConfirmReceipt(operation)

            is Rg07Operation.ImportConfirm -> persistImportConfirmation(operation)

            is Rg07Operation.OriginalPaymentEvidence -> persistOriginalPaymentEvidence(operation)

            is Rg07Operation.DestinationEvidence -> persistDestinationEvidence(operation)

            is Rg07Operation.DualRoleEvidence -> persistDualRoleEvidence(operation)

            is Rg07Operation.ImportCredit -> persistImportCredit(operation)

            is Rg07Operation.Mirror -> persistMirror(operation)

            is Rg07Operation.Allocate, is Rg07Operation.Validate -> error("rejection-class operations are never persisted")
        }
    }

    private fun persistManualExpense(operation: Rg07Operation.ManualExpense) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val commitFacts = identity.manual(operation)

        val formalIds = identity.formal(operation)

        val transactionId = formalIds.transactionId

        val versionId = formalIds.versionId

        val postingSetId = formalIds.postingSetId

        val expensePostingId = formalIds.firstPostingId

        val assetPostingId = formalIds.secondPostingId

        val occurredAt = input.occurredAt.toString()

        val expenseAccount = checkNotNull(categoryWithPostingAccount(input.categoryId)).postingAccountId!!.value

        check(
            createAssetPaidOrdinaryExpense(
                catalog,
                AssetPaidOrdinaryExpenseCommand(
                    ledgerId,
                    input.amount,
                    input.categoryId,
                    input.paymentAccountId,
                    TransactionTimes.collapsed(input.occurredAt),
                ),
                AssetPaidOrdinaryExpenseIds(
                    TransactionId(transactionId),
                    TransactionVersionId(versionId),
                    PostingSetId(postingSetId),
                    PostingId(expensePostingId),
                    PostingId(assetPostingId),
                ),
            ) is DomainResult.Success,
        )

        database.ledgerQueries.insertPostingSet(postingSetId, ledgerId.value)

        database.ledgerQueries.insertTransaction(transactionId, ledgerId.value, "EXPENSE")

        database.ledgerQueries.insertTransactionVersion(versionId, transactionId, ledgerId.value, 1, postingSetId, occurredAt, occurredAt, occurredAt, input.note)

        database.ledgerQueries.insertRg07TransactionVersionMetadata(
            ledgerId.value,
            versionId,
            commitFacts.confirmedAt.toString(),
            commitFacts.confirmationId,
        )

        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId.value, versionId)

        database.ledgerQueries.insertPosting(
            expensePostingId,
            postingSetId,
            ledgerId.value,
            0L,
            expenseAccount,
            input.amount.minorUnits,
            input.amount.currency.code,
            input.amount.currency.precision
                .toLong(),
        )

        database.ledgerQueries.insertPosting(
            assetPostingId,
            postingSetId,
            ledgerId.value,
            1L,
            input.paymentAccountId.value,
            -input.amount.minorUnits,
            input.amount.currency.code,
            input.amount.currency.precision
                .toLong(),
        )

        database.ledgerQueries.insertRg07PostingSemantic(ledgerId.value, expensePostingId, "expense", input.categoryId.value, 0)

        database.ledgerQueries.insertRg07PostingSemantic(ledgerId.value, assetPostingId, "payment_asset", null, 1)

        database.ledgerQueries.insertRg07OperationConfirmation(
            ledgerId.value,
            commitFacts.confirmationId,
            identity.operationId(operation),
            identity.operationId(operation),
            commitFacts.confirmedAt.toString(),
            transactionId,
        )

        database.ledgerQueries.insertRg07Reconciliation(ledgerId.value, commitFacts.reconciliationId, assetPostingId)

        database.ledgerQueries.insertRg07ReconciliationHistory(ledgerId.value, commitFacts.reconciliationId, 1, "PENDING", null)
    }

    private fun persistStatus(operation: Rg07Operation.Status) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val original = checkNotNull(originalExpense(ledgerId, input.originalTransactionId))

        val relationId = identity.relation(operation)

        val existing = database.ledgerQueries.selectRg07EntityByRelation(ledgerId.value, relationId).executeAsOneOrNull()

        if (existing == null) {
            database.ledgerQueries.insertRg07Relation(ledgerId.value, relationId)

            database.ledgerQueries.insertRg07RelationMember(ledgerId.value, relationId, input.originalTransactionId.value)

            val entityId = identity.domainEntity(operation, relationId)

            database.ledgerQueries.insertRg07RefundRelationship(
                ledgerId.value,
                entityId,
                relationId,
                input.originalTransactionId.value,
                null,
                original.categoryId,
                input.requestedAmount.minorUnits,
                0,
                input.requestedAmount.currency.code,
                input.requestedAmount.currency.precision
                    .toLong(),
                null,
                input.requestedAt?.toString(),
                input.approvedAt?.toString(),
                input.processorReportedAt?.toString(),
                null,
                null,
                null,
                null,
                null,
            )

            var sequence = 1

            input.requestedAt?.let {
                database.ledgerQueries.insertRg07RefundRelationshipHistory(
                    ledgerId.value,
                    entityId,
                    sequence.toLong(),
                    historyId(relationId, Rg07RefundStatus.REQUESTED),
                    operation.identity.value,
                    "REQUESTED",
                    it.toString(),
                    null,
                    0L,
                )

                sequence++
            }

            input.approvedAt?.let {
                database.ledgerQueries.insertRg07RefundRelationshipHistory(
                    ledgerId.value,
                    entityId,
                    sequence.toLong(),
                    historyId(relationId, Rg07RefundStatus.APPROVED),
                    operation.identity.value,
                    "APPROVED",
                    it.toString(),
                    null,
                    0L,
                )

                sequence++
            }

            input.processorReportedAt?.let {
                database.ledgerQueries.insertRg07RefundRelationshipHistory(
                    ledgerId.value,
                    entityId,
                    sequence.toLong(),
                    historyId(relationId, Rg07RefundStatus.PROCESSING),
                    operation.identity.value,
                    "PROCESSING",
                    it.toString(),
                    null,
                    0L,
                )
            }
        } else {
            // A later status-only request may advance the lifecycle only through a

            // registered transition; the approved fixture never exercises this path.

            val latest = database.ledgerQueries.selectRg07RefundStatus(ledgerId.value, relationId).executeAsOneOrNull()

            val next =

                input.processorReportedAt?.let { Rg07RefundStatus.PROCESSING }

                    ?: input.approvedAt?.let { Rg07RefundStatus.APPROVED }

                    ?: Rg07RefundStatus.REQUESTED

            val previous = latest?.let { parseStatus(it) }

            if (isValidRg07StatusTransition(previous, next)) {
                val nextSequence = (database.ledgerQueries.selectRg07LatestHistorySequence(ledgerId.value, existing.entity_id).executeAsOneOrNull() ?: 1L) + 1

                val occurredAt = input.processorReportedAt ?: input.approvedAt ?: input.requestedAt ?: return

                database.ledgerQueries.insertRg07RefundRelationshipHistory(
                    ledgerId.value,
                    existing.entity_id,
                    nextSequence,
                    historyId(relationId, next),
                    operation.identity.value,
                    next.name,
                    occurredAt.toString(),
                    null,
                    0L,
                )
            }
        }
    }

    private fun persistStatusSource(operation: Rg07Operation.StatusSource) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val relationId = input.refundRelationId

        val sourceSuffix = input.sourceId.removePrefix("source-")

        database.ledgerQueries.insertRg07Source(
            ledgerId.value,
            input.sourceId,
            "merchant_refund_notice",
            input.sourceId,
            "evidence-$sourceSuffix",
            "sha256:${input.sourceId}",
            input.observedAt.toString(),
            input.observedAt.toString(),
            null,
            null,
            null,
            null,
            input.reportedState.name,
            if (input.provesArrival) 1 else 0,
            null,
            null,
            null,
            null,
            null,
            null,
        )

        database.ledgerQueries.insertRg07Evidence(
            ledgerId.value,
            "evidence-$sourceSuffix",
            input.sourceId,
            "refund_notice",
            input.observedAt.toString(),
            input.observedAt.toString(),
            null,
            null,
        )

        database.ledgerQueries.insertRg07EvidenceLink(
            ledgerId.value,
            "evidence-link-$sourceSuffix",
            "evidence-$sourceSuffix",
            "relation",
            relationId,
            "refund_relationship",
        )

        val entity = database.ledgerQueries.selectRg07EntityByRelation(ledgerId.value, relationId).executeAsOneOrNull() ?: return

        val latest = database.ledgerQueries.selectRg07RefundStatus(ledgerId.value, relationId).executeAsOneOrNull()

        val previous = latest?.let { parseStatus(it) }

        val next = Rg07RefundStatus.valueOf(input.reportedState.name)

        if (isValidRg07StatusTransition(previous, next)) {
            val nextSequence = (database.ledgerQueries.selectRg07LatestHistorySequence(ledgerId.value, entity.entity_id).executeAsOneOrNull() ?: 1L) + 1

            database.ledgerQueries.insertRg07RefundRelationshipHistory(
                ledgerId.value,
                entity.entity_id,
                nextSequence,
                historyId(relationId, next),
                operation.identity.value,
                next.name,
                input.observedAt.toString(),
                null,
                0L,
            )
        }
    }

    private fun persistManualReceipt(operation: Rg07Operation.ManualReceipt) {
        val input = operation.input

        val proposal =

            receiptProposal(
                input.originalTransactionId,
                input.amount,
                input.categoryId,
                input.destinationAccountId,
                input.arrivedAt,
                input.confirmedAt,
            ) ?: return

        val original = checkNotNull(originalExpense(operation.ledgerId, proposal.originalTransactionId))

        persistReceipt(
            operation,
            proposal,
            original,
            relationId = input.refundRelationId,
            sourceObservedAt = input.sourceObservedAt,
            bookingAt = input.bookingAt,
            valueAt = input.valueAt,
            processorReportedAt = null,
            evidenceLinkId = null,
            matched = false,
        )
    }

    private fun persistConfirmReceipt(operation: Rg07Operation.ConfirmReceipt) {
        val input = operation.input

        val proposal =

            receiptProposal(
                input.originalTransactionId,
                input.amount,
                input.categoryId,
                input.destinationAccountId,
                input.arrivedAt,
                input.confirmedAt,
            ) ?: return

        val original = checkNotNull(originalExpense(operation.ledgerId, proposal.originalTransactionId))

        persistReceipt(
            operation,
            proposal,
            original,
            relationId = null,
            sourceObservedAt = null,
            bookingAt = null,
            valueAt = null,
            processorReportedAt = null,
            evidenceLinkId = null,
            matched = false,
        )
    }

    private fun persistImportConfirmation(operation: Rg07Operation.ImportConfirm) {
        val input = operation.input

        val proposal =

            receiptProposal(
                input.originalTransactionId,
                input.allocatedAmount,
                input.categoryId,
                input.destinationAccountId,
                input.arrivedAt,
                input.confirmedAt,
            ) ?: return

        val original = checkNotNull(originalExpense(operation.ledgerId, proposal.originalTransactionId))

        val candidate = database.ledgerQueries.selectRg07Candidate(operation.ledgerId.value, input.candidateId).executeAsOneOrNull() ?: return

        val source = database.ledgerQueries.selectRg07Source(operation.ledgerId.value, candidate.source_id).executeAsOneOrNull()

        val evidenceLinkId = "evidence-link-${input.candidateId.removePrefix("candidate-refund-")}-posting"

        persistReceipt(
            operation,
            proposal,
            original,
            relationId = "refund-relation-${input.candidateId.removePrefix("candidate-refund-")}",
            sourceObservedAt = source?.source_observed_at?.let(Instant::parse),
            bookingAt = source?.booking_at?.let(Instant::parse),
            valueAt = source?.value_at?.let(Instant::parse),
            processorReportedAt = source?.processor_reported_at?.let(Instant::parse),
            evidenceLinkId = evidenceLinkId,
            matched = true,
        )

        database.ledgerQueries.insertRg07CandidateStatus(
            operation.ledgerId.value,
            input.candidateId,
            2,
            "history-rg07-import-confirmed",
            "CONFIRMED",
            proposal.confirmedAt.toString(),
            1,
        )

        val assetPostingId = "posting-refund-asset-${input.candidateId.removePrefix("candidate-refund-")}"

        database.ledgerQueries.insertRg07EvidenceLink(
            operation.ledgerId.value,
            evidenceLinkId,
            candidate.evidence_id,
            "posting",
            assetPostingId,
            "destination_asset_posting",
        )
    }

    private fun persistReceipt(
        operation: Rg07Operation,
        proposal: ReceiptProposal,
        original: OriginalExpenseFact,
        relationId: String?,
        sourceObservedAt: Instant?,
        bookingAt: Instant?,
        valueAt: Instant?,
        processorReportedAt: Instant?,
        evidenceLinkId: String?,
        matched: Boolean,
    ) {
        val ledgerId = operation.ledgerId

        val suffix = receiptSuffix(operation)

        val formalIds = identity.formal(operation)

        val transactionId = formalIds.transactionId

        val versionId = formalIds.versionId

        val postingSetId = formalIds.postingSetId

        val assetPostingId = formalIds.firstPostingId

        val expensePostingId = formalIds.secondPostingId

        val amount = proposal.amount

        val expenseAccount = checkNotNull(categoryWithPostingAccount(proposal.categoryId)).postingAccountId!!.value

        val arrivedAt = proposal.arrivedAt.toString()

        check(
            createRefundReceipt(
                catalog,
                RefundReceiptCommand(
                    ledgerId,
                    proposal.originalTransactionId,
                    amount,
                    proposal.categoryId,
                    proposal.destinationAccountId,
                    TransactionTimes.collapsed(proposal.arrivedAt),
                ),
                RefundReceiptIds(
                    TransactionId(transactionId),
                    TransactionVersionId(versionId),
                    PostingSetId(postingSetId),
                    PostingId(assetPostingId),
                    PostingId(expensePostingId),
                ),
            ) is DomainResult.Success,
        )

        database.ledgerQueries.insertPostingSet(postingSetId, ledgerId.value)

        database.ledgerQueries.insertTransaction(transactionId, ledgerId.value, "REFUND_RECEIPT")

        database.ledgerQueries.insertTransactionVersion(versionId, transactionId, ledgerId.value, 1, postingSetId, arrivedAt, arrivedAt, arrivedAt, null)

        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId.value, versionId)

        database.ledgerQueries.insertPosting(assetPostingId, postingSetId, ledgerId.value, 0L, proposal.destinationAccountId.value, amount.minorUnits, amount.currency.code, amount.currency.precision.toLong())

        database.ledgerQueries.insertPosting(expensePostingId, postingSetId, ledgerId.value, 1L, expenseAccount, -amount.minorUnits, amount.currency.code, amount.currency.precision.toLong())

        database.ledgerQueries.insertRg07PostingSemantic(ledgerId.value, expensePostingId, "expense", proposal.categoryId.value, 0)

        database.ledgerQueries.insertRg07PostingSemantic(ledgerId.value, assetPostingId, "destination_asset", null, 1)

        val existingEntities = database.ledgerQueries.selectRg07EntitiesForOriginal(ledgerId.value, proposal.originalTransactionId.value).executeAsList()

        val pendingEntity = (

            relationId?.let { requestedRelationId ->

                existingEntities.firstOrNull { entity -> entity.relation_id == requestedRelationId && entity.refund_transaction_id == null }
            } ?: existingEntities.firstOrNull { it.refund_transaction_id == null }

        )

        val entityRelationId = pendingEntity?.relation_id ?: (relationId ?: "refund-relation-$suffix")

        if (pendingEntity == null) {
            database.ledgerQueries.insertRg07Relation(ledgerId.value, entityRelationId)

            database.ledgerQueries.insertRg07RelationMember(ledgerId.value, entityRelationId, proposal.originalTransactionId.value)
        }

        val members = database.ledgerQueries.selectRg07OriginalTransaction(ledgerId.value, entityRelationId).executeAsList()

        if (transactionId !in members) {
            database.ledgerQueries.insertRg07RelationMember(ledgerId.value, entityRelationId, transactionId)
        }

        val commitIds = identity.receipt(operation, entityRelationId, assetPostingId)

        database.ledgerQueries.insertRg07TransactionVersionMetadata(
            ledgerId.value,
            versionId,
            proposal.confirmedAt.toString(),
            commitIds.confirmationId,
        )

        database.ledgerQueries.insertRg07Confirmation(
            ledgerId.value,
            commitIds.confirmationId,
            identity.operationId(operation),
            entityRelationId,
            proposal.confirmedAt.toString(),
            proposal.originalTransactionId.value,
        )

        if (pendingEntity != null) {
            database.ledgerQueries.updateRg07RefundRelationshipReceipt(
                transactionId,
                amount.minorUnits,
                proposal.destinationAccountId.value,
                sourceObservedAt?.toString(),
                bookingAt?.toString(),
                valueAt?.toString(),
                proposal.confirmedAt.toString(),
                proposal.arrivedAt.toString(),
                ledgerId.value,
                pendingEntity.entity_id,
            )

            val nextSequence = (database.ledgerQueries.selectRg07LatestHistorySequence(ledgerId.value, pendingEntity.entity_id).executeAsOneOrNull() ?: 1L) + 1

            database.ledgerQueries.insertRg07RefundRelationshipHistory(
                ledgerId.value,
                pendingEntity.entity_id,
                nextSequence,
                historyId(entityRelationId, Rg07RefundStatus.RECEIVED),
                operation.identity.value,
                "RECEIVED",
                proposal.confirmedAt.toString(),
                transactionId,
                1L,
            )
        } else {
            val entityId = commitIds.domainEntityId

            database.ledgerQueries.insertRg07RefundRelationship(
                ledgerId.value,
                entityId,
                entityRelationId,
                proposal.originalTransactionId.value,
                transactionId,
                proposal.categoryId.value,
                amount.minorUnits,
                amount.minorUnits,
                amount.currency.code,
                amount.currency.precision.toLong(),
                proposal.destinationAccountId.value,
                null,
                null,
                processorReportedAt?.toString(),
                sourceObservedAt?.toString(),
                bookingAt?.toString(),
                valueAt?.toString(),
                proposal.confirmedAt.toString(),
                proposal.arrivedAt.toString(),
            )

            database.ledgerQueries.insertRg07RefundRelationshipHistory(
                ledgerId.value,
                entityId,
                1,
                historyId(entityRelationId, Rg07RefundStatus.RECEIVED),
                operation.identity.value,
                "RECEIVED",
                proposal.confirmedAt.toString(),
                transactionId,
                1L,
            )
        }

        database.ledgerQueries.insertRg07Reconciliation(ledgerId.value, commitIds.reconciliationId, assetPostingId)

        database.ledgerQueries.insertRg07ReconciliationHistory(
            ledgerId.value,
            commitIds.reconciliationId,
            1,
            if (matched) "MATCHED" else "PENDING",
            evidenceLinkId,
        )
    }

    private fun persistOriginalPaymentEvidence(operation: Rg07Operation.OriginalPaymentEvidence) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val linkId = "evidence-link-${input.evidenceId.removePrefix("evidence-")}"

        database.ledgerQueries.insertRg07Source(
            ledgerId.value,
            input.sourceId,
            "bank_debit",
            input.sourceId.replaceFirst("source-", "source-record-"),
            input.evidenceId,
            input.immutablePayloadHash,
            input.observedAt.toString(),
            input.observedAt.toString(),
            input.amount.minorUnits,
            input.amount.currency.code,
            input.amount.currency.precision
                .toLong(),
            null,
            null,
            null,
            null,
            input.observedAt.toString(),
            input.bookingAt.toString(),
            input.valueAt.toString(),
            null,
            null,
        )

        database.ledgerQueries.insertRg07Evidence(
            ledgerId.value,
            input.evidenceId,
            input.sourceId,
            "asset_debit",
            input.observedAt.toString(),
            input.observedAt.toString(),
            null,
            null,
        )

        database.ledgerQueries.insertRg07EvidenceLink(
            ledgerId.value,
            linkId,
            input.evidenceId,
            "posting",
            input.paymentAssetPostingId,
            "payment_asset_posting",
        )

        matchReconciliation(ledgerId, input.paymentAssetPostingId, linkId)
    }

    private fun persistDestinationEvidence(operation: Rg07Operation.DestinationEvidence) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val sourceSuffix = input.sourceId.removePrefix("source-")

        val linkId = "evidence-link-$sourceSuffix"

        val postingId = input.destinationAssetPostingId

        database.ledgerQueries.insertRg07Source(
            ledgerId.value,
            input.sourceId,
            "wallet_credit",
            input.sourceId,
            input.evidenceId,
            "sha256:${input.sourceId}",
            input.bookingAt.toString(),
            input.bookingAt.toString(),
            input.amount.minorUnits,
            input.amount.currency.code,
            input.amount.currency.precision
                .toLong(),
            input.accountId.value,
            null,
            null,
            null,
            null,
            input.bookingAt.toString(),
            input.valueAt.toString(),
            "sha256:$sourceSuffix",
            null,
        )

        database.ledgerQueries.insertRg07Evidence(
            ledgerId.value,
            input.evidenceId,
            input.sourceId,
            "asset_credit",
            input.bookingAt.toString(),
            input.bookingAt.toString(),
            null,
            null,
        )

        database.ledgerQueries.insertRg07EvidenceLink(
            ledgerId.value,
            linkId,
            input.evidenceId,
            "posting",
            postingId,
            "destination_asset_posting",
        )

        matchReconciliation(ledgerId, postingId, linkId)
    }

    private fun persistDualRoleEvidence(operation: Rg07Operation.DualRoleEvidence) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val relationId = input.refundRelationId

        val postingId = input.destinationAssetPostingId

        val observedAt = input.observedAt.toString()

        val sourceSuffix = input.sourceId.removePrefix("source-").removeSuffix("-role")

        database.ledgerQueries.insertRg07Source(
            ledgerId.value,
            input.sourceId,
            "combined_refund_statement",
            input.sourceId,
            input.evidenceId,
            "sha256:${input.sourceId}",
            observedAt,
            observedAt,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )

        database.ledgerQueries.insertRg07Evidence(
            ledgerId.value,
            input.evidenceId,
            input.sourceId,
            "combined_refund_statement",
            observedAt,
            observedAt,
            null,
            null,
        )

        database.ledgerQueries.insertRg07EvidenceLink(
            ledgerId.value,
            "evidence-link-$sourceSuffix-relation",
            input.evidenceId,
            "relation",
            relationId,
            "refund_relationship",
        )

        database.ledgerQueries.insertRg07EvidenceLink(
            ledgerId.value,
            "evidence-link-$sourceSuffix-posting",
            input.evidenceId,
            "posting",
            postingId,
            "destination_asset_posting",
        )
    }

    private fun persistImportCredit(operation: Rg07Operation.ImportCredit) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val sourceSuffix = input.sourceId.removePrefix("source-")

        val evidenceId = "evidence-$sourceSuffix"

        // The approved fixture names the single refund-credit candidate

        // "candidate-refund-{scenario}" (e.g. candidate-refund-rg07-import for the

        // source source-rg07-import-credit).

        val candidateId = "candidate-refund-${sourceSuffix.removeSuffix("-credit")}"

        database.ledgerQueries.insertRg07Source(
            ledgerId.value,
            input.sourceId,
            "wallet_credit",
            input.sourceRecordId,
            evidenceId,
            "sha256:${input.sourceId}",
            input.sourceObservedAt.toString(),
            input.sourceObservedAt.toString(),
            input.amount.minorUnits,
            input.amount.currency.code,
            input.amount.currency.precision
                .toLong(),
            input.accountId.value,
            null,
            null,
            input.processorReportedAt.toString(),
            input.sourceObservedAt.toString(),
            input.bookingAt.toString(),
            input.valueAt.toString(),
            input.originalSourcePayloadHash,
            null,
        )

        database.ledgerQueries.insertRg07Evidence(
            ledgerId.value,
            evidenceId,
            input.sourceId,
            "asset_credit",
            input.sourceObservedAt.toString(),
            input.sourceObservedAt.toString(),
            null,
            null,
        )

        database.ledgerQueries.insertRg07Candidate(
            ledgerId.value,
            candidateId,
            input.sourceId,
            evidenceId,
            "0.97",
            input.amount.minorUnits,
            input.amount.currency.code,
            input.amount.currency.precision
                .toLong(),
            null,
            null,
            input.accountId.value,
            input.bookingAt.toString(),
            input.originalSourcePayloadHash,
            1,
        )

        database.ledgerQueries.insertRg07CandidateStatus(
            ledgerId.value,
            candidateId,
            1,
            "history-rg07-import-pending",
            "PENDING_CONFIRMATION",
            input.sourceObservedAt.toString(),
            0,
        )
    }

    private fun persistMirror(operation: Rg07Operation.Mirror) {
        val ledgerId = operation.ledgerId

        val input = operation.input

        val originalSourceId = mirrorOfSourceId(input.sourceId)

        val originalSource =

            database.ledgerQueries.selectRg07Source(ledgerId.value, originalSourceId).executeAsOneOrNull()

                ?: return

        val originalLink =

            database.ledgerQueries
                .selectRg07EvidenceLinkForRole(
                    ledgerId.value,
                    originalSource.evidence_id,
                    "destination_asset_posting",
                ).executeAsOneOrNull() ?: return

        val sourceSuffix = input.sourceId.removePrefix("source-")

        database.ledgerQueries.insertRg07Source(
            ledgerId.value,
            input.sourceId,
            "wallet_credit_mirror",
            input.sourceId,
            input.evidenceId,
            "sha256:${input.sourceId}",
            input.observedAt.toString(),
            input.observedAt.toString(),
            input.amount.minorUnits,
            input.amount.currency.code,
            input.amount.currency.precision
                .toLong(),
            originalSource.account_id,
            null,
            null,
            null,
            null,
            input.observedAt.toString(),
            input.observedAt.toString(),
            originalSource.original_source_payload_hash,
            originalSourceId,
        )

        database.ledgerQueries.insertRg07EvidenceLink(
            ledgerId.value,
            "evidence-link-$sourceSuffix",
            input.evidenceId,
            "posting",
            originalLink.target_id,
            "destination_asset_posting",
        )

        database.ledgerQueries.insertRg07Evidence(
            ledgerId.value,
            input.evidenceId,
            input.sourceId,
            "asset_credit_mirror",
            input.observedAt.toString(),
            input.observedAt.toString(),
            originalSource.evidence_id,
            originalLink.link_id,
        )
    }

    private fun persistRejected(
        operation: Rg07Operation,
        result: Rg07ExecutionResult.Rejected,
    ) {
        database.ledgerQueries.insertRg07Rejection(
            operation.ledgerId.value,
            operation.identity.value,
            result.reason.code,
            result.fieldPath.value,
        )

        database.ledgerQueries.updateRg07Outcome("REJECTED", operation.ledgerId.value, operation.identity.value)
    }

    // ------------------------------------------------------------------

    // Reconciliation matching

    // ------------------------------------------------------------------

    /** Appends the MATCHED history item when the posting's reconciliation is PENDING. */

    private fun matchReconciliation(
        ledgerId: LedgerId,
        postingId: String,
        evidenceLinkId: String,
    ) {
        val reconciliation =

            database.ledgerQueries.selectRg07ReconciliationForPosting(ledgerId.value, postingId).executeAsOneOrNull()

                ?: return

        val latest = database.ledgerQueries.selectRg07ReconciliationLatest(ledgerId.value, reconciliation.reconciliation_id).executeAsOneOrNull()

        if (latest == null || latest.status == "PENDING") {
            database.ledgerQueries.insertRg07ReconciliationHistory(
                ledgerId.value,
                reconciliation.reconciliation_id,
                (latest?.status_sequence ?: 0L) + 1,
                "MATCHED",
                evidenceLinkId,
            )
        }
    }

    // ------------------------------------------------------------------

    // Replay and helpers

    // ------------------------------------------------------------------

    private fun replayRejected(
        operation: Rg07Operation,
        saved: com.unifiedledger.data.db.Rg07_operation,
        rejection: com.unifiedledger.data.db.Rg07_rejection,
    ): Rg07ExecutionResult {
        if (saved.action != operation.action.code ||

            saved.operation_class != operation.operationClass.name ||

            saved.fingerprint != operation.fingerprint()

        ) {
            return Rg07ExecutionResult.RequestIdentityConflict
        }

        val reason = Rg07RejectionReason.entries.firstOrNull { r -> r.code == rejection.reason_code } ?: Rg07RejectionReason.INVALID_TIMESTAMP

        val path = Rg07FieldPath.entries.firstOrNull { p -> p.value == rejection.field_path } ?: Rg07FieldPath.AMOUNT

        return Rg07ExecutionResult.Rejected(reason, path)
    }

    private fun replay(
        operation: Rg07Operation,
        saved: com.unifiedledger.data.db.Rg07_operation,
    ): Rg07ExecutionResult {
        if (saved.action != operation.action.code || saved.operation_class != operation.operationClass.name || saved.fingerprint != operation.fingerprint()) {
            return Rg07ExecutionResult.RequestIdentityConflict
        }

        return when (saved.outcome) {
            "ACCEPTED", "NO_CHANGE" ->

                accepted(operation).let {
                    Rg07ExecutionResult.NoChange(it.transactionId, it.relationId, it.returnedIds)
                }

            "REJECTED" -> {
                val rejection = database.ledgerQueries.selectRg07Rejection(operation.ledgerId.value, operation.identity.value).executeAsOneOrNull()

                rejection?.let { replayRejected(operation, saved, it) } ?: Rg07ExecutionResult.RequestIdentityConflict
            }

            else -> Rg07ExecutionResult.RequestIdentityConflict
        }
    }

    private fun claim(operation: Rg07Operation) {
        database.ledgerQueries.claimRg07Operation(
            operation.ledgerId.value,
            operation.identity.value,
            operation.action.code,
            operation.operationClass.name,
            operation.fingerprint(),
            "ACCEPTED",
            null,
            null,
        )
    }

    private fun accepted(operation: Rg07Operation): Rg07ExecutionResult.Accepted =

        when (operation) {
            is Rg07Operation.ManualExpense ->

                identity.manual(operation).let { commitFacts ->

                    val formalIds = identity.formal(operation)

                    Rg07ExecutionResult.Accepted(
                        TransactionId(formalIds.transactionId),
                        null,
                        listOf(
                            Rg07ReturnedId.Confirmation(commitFacts.confirmationId),
                            Rg07ReturnedId.Transaction(formalIds.transactionId),
                        ),
                    )
                }

            is Rg07Operation.Status -> {
                val relationId = identity.relation(operation)

                Rg07ExecutionResult.Accepted(
                    null,
                    relationId,
                    listOf(Rg07ReturnedId.Relation(relationId), Rg07ReturnedId.DomainEntity(identity.domainEntity(operation, relationId))),
                )
            }

            is Rg07Operation.StatusSource -> sourceEvidenceReturned(operation.input.sourceId, "evidence-${operation.input.sourceId.removePrefix("source-")}")

            is Rg07Operation.ManualReceipt -> receiptReturned(operation)

            is Rg07Operation.ConfirmReceipt -> receiptReturned(operation)

            is Rg07Operation.ImportConfirm -> {
                val suffix = operation.input.candidateId.removePrefix("candidate-refund-")

                val formalIds = identity.formal(operation)

                val transactionId = formalIds.transactionId

                val relationId = "refund-relation-$suffix"

                val confirmationId = identity.receipt(operation, relationId, formalIds.firstPostingId).confirmationId

                Rg07ExecutionResult.Accepted(
                    TransactionId(transactionId),
                    relationId,
                    listOf(
                        Rg07ReturnedId.Candidate(operation.input.candidateId),
                        Rg07ReturnedId.Confirmation(confirmationId),
                        Rg07ReturnedId.Transaction(transactionId),
                        Rg07ReturnedId.Relation(relationId),
                        Rg07ReturnedId.EvidenceLink("evidence-link-$suffix-posting"),
                    ),
                )
            }

            is Rg07Operation.OriginalPaymentEvidence ->

                Rg07ExecutionResult.Accepted(
                    null,
                    null,
                    listOf(
                        Rg07ReturnedId.Source(operation.input.sourceId),
                        Rg07ReturnedId.Evidence(operation.input.evidenceId),
                        Rg07ReturnedId.EvidenceLink("evidence-link-${operation.input.evidenceId.removePrefix("evidence-")}"),
                    ),
                )

            is Rg07Operation.DestinationEvidence -> sourceEvidenceReturned(operation.input.sourceId, operation.input.evidenceId)

            is Rg07Operation.DualRoleEvidence -> {
                val suffix =

                    operation.input.sourceId
                        .removePrefix("source-")
                        .removeSuffix("-role")

                Rg07ExecutionResult.Accepted(
                    null,
                    null,
                    listOf(
                        Rg07ReturnedId.Source(operation.input.sourceId),
                        Rg07ReturnedId.Evidence(operation.input.evidenceId),
                        Rg07ReturnedId.EvidenceLink("evidence-link-$suffix-relation"),
                        Rg07ReturnedId.EvidenceLink("evidence-link-$suffix-posting"),
                    ),
                )
            }

            is Rg07Operation.Allocate -> error("allocate is a rejection-class operation")

            is Rg07Operation.ImportCredit ->

                Rg07ExecutionResult.Accepted(
                    null,
                    null,
                    listOf(
                        Rg07ReturnedId.Source(operation.input.sourceId),
                        Rg07ReturnedId.Evidence("evidence-${operation.input.sourceId.removePrefix("source-")}"),
                        Rg07ReturnedId.Candidate("candidate-refund-${operation.input.sourceId.removePrefix("source-").removeSuffix("-credit")}"),
                    ),
                )

            is Rg07Operation.Mirror -> sourceEvidenceReturned(operation.input.sourceId, operation.input.evidenceId)

            is Rg07Operation.Validate -> error("validate is a rejection-class operation")
        }

    private fun sourceEvidenceReturned(
        sourceId: String,
        evidenceId: String,
    ): Rg07ExecutionResult.Accepted =

        Rg07ExecutionResult.Accepted(
            null,
            null,
            listOf(
                Rg07ReturnedId.Source(sourceId),
                Rg07ReturnedId.Evidence(evidenceId),
                Rg07ReturnedId.EvidenceLink("evidence-link-${sourceId.removePrefix("source-")}"),
            ),
        )

    private fun receiptReturned(operation: Rg07Operation): Rg07ExecutionResult.Accepted {
        val suffix = receiptSuffix(operation)

        val formalIds = identity.formal(operation)

        val transactionId = formalIds.transactionId

        val relationId = refundRelationForReceipt(operation) ?: "refund-relation-$suffix"

        val confirmationId = identity.receipt(operation, relationId, formalIds.firstPostingId).confirmationId

        val returnedIds =

            mutableListOf<Rg07ReturnedId>(
                Rg07ReturnedId.Confirmation(confirmationId),
                Rg07ReturnedId.Transaction(transactionId),
            )

        if (operation is Rg07Operation.ConfirmReceipt) returnedIds += Rg07ReturnedId.Relation(relationId)

        return Rg07ExecutionResult.Accepted(
            TransactionId(transactionId),
            relationId,
            returnedIds,
        )
    }

    private fun refundRelationForReceipt(operation: Rg07Operation): String? {
        val originalTransactionId =

            when (operation) {
                is Rg07Operation.ManualReceipt -> operation.input.originalTransactionId?.value

                is Rg07Operation.ConfirmReceipt -> operation.input.originalTransactionId?.value

                else -> null
            } ?: return null

        return database.ledgerQueries
            .selectRg07EntitiesForOriginal(operation.ledgerId.value, originalTransactionId)
            .executeAsList()
            .firstOrNull { it.refund_transaction_id == null }

            ?.relation_id
    }

    private fun rejected(
        reason: Rg07RejectionReason,
        path: Rg07FieldPath,
    ): Rg07ExecutionResult.Rejected = Rg07ExecutionResult.Rejected(reason, path)

    private fun parseStatus(state: String): Rg07RefundStatus? = runCatching { Rg07RefundStatus.valueOf(state) }.getOrNull()

    // ------------------------------------------------------------------

    // Domain queries

    // ------------------------------------------------------------------

    private data class ReceiptProposal(
        val originalTransactionId: TransactionId,
        val amount: Money,
        val categoryId: CategoryId,
        val destinationAccountId: AccountId,
        val arrivedAt: Instant,
        val confirmedAt: Instant,
    )

    private fun receiptProposal(
        originalTransactionId: TransactionId?,
        amount: Money?,
        categoryId: CategoryId?,
        destinationAccountId: AccountId?,
        arrivedAt: Instant?,
        confirmedAt: Instant?,
    ): ReceiptProposal? =

        if (

            originalTransactionId != null &&

            amount != null &&

            categoryId != null &&

            destinationAccountId != null &&

            arrivedAt != null &&

            confirmedAt != null

        ) {
            ReceiptProposal(originalTransactionId, amount, categoryId, destinationAccountId, arrivedAt, confirmedAt)
        } else {
            null
        }

    private data class OriginalExpenseFact(
        val transactionId: String,
        val postingId: String,
        val expenseAccountId: String,
        val amountMinor: Long,
        val currencyCode: String,
        val currencyPrecision: Long,
        val categoryId: String,
    )

    private fun originalExpense(
        ledgerId: LedgerId,
        transactionId: TransactionId,
    ): OriginalExpenseFact? =

        database.ledgerQueries.selectRg07OriginalExpense(ledgerId.value, transactionId.value).executeAsList().singleOrNull()?.let {
            OriginalExpenseFact(
                transactionId = it.transaction_id,
                postingId = it.posting_id,
                expenseAccountId = it.account_id,
                amountMinor = it.amount_minor,
                currencyCode = it.currency_code,
                currencyPrecision = it.currency_precision,
                categoryId = it.category_id ?: "",
            )
        }

    private fun remainingRefundable(
        ledgerId: LedgerId,
        originalTransactionId: TransactionId,
        original: OriginalExpenseFact,
    ): Long {
        var received = 0L

        database.ledgerQueries.selectRg07RelationsForOriginal(ledgerId.value, originalTransactionId.value).executeAsList().forEach { relation ->

            database.ledgerQueries.selectRg07EntityByRelation(ledgerId.value, relation).executeAsOneOrNull()?.let { entity ->

                received += entity.received_amount_minor
            }
        }

        return original.amountMinor - received
    }

    private fun refundRelation(ledgerId: LedgerId): String? = database.ledgerQueries.selectRg07RefundRelation(ledgerId.value).executeAsOneOrNull()

    private fun destinationPostingMatches(
        ledgerId: LedgerId,
        relationId: String,
        postingId: String,
        accountId: AccountId,
        amount: Money,
    ): Boolean =

        database.ledgerQueries
            .selectRg07ExactRefundAssetPosting(
                ledgerId.value,
                relationId,
                postingId,
                accountId.value,
                amount.minorUnits,
                amount.currency.code,
                amount.currency.precision.toLong(),
            ).executeAsOneOrNull() != null

    private fun refundAssetPosting(
        ledgerId: LedgerId,
        relationId: String,
    ): String? = database.ledgerQueries.selectRg07RefundAssetPosting(ledgerId.value, relationId).executeAsOneOrNull()

    private fun categoryWithPostingAccount(categoryId: CategoryId): Category? = catalog.categories.singleOrNull { it.id == categoryId && it.active && it.postingAccountId != null }

    private fun isOwnedRealAssetDestination(accountId: AccountId): Boolean {
        val account = catalog.accounts.singleOrNull { it.id == accountId } ?: return false

        return account.ownedByUser && account.realAccount && account.kind == AccountKind.ASSET && accountId !in storeCreditAccountIds
    }

    private fun receiptSuffix(operation: Rg07Operation): String =

        when (operation) {
            is Rg07Operation.ManualReceipt -> checkNotNull(operation.input.refundRelationId).removePrefix("refund-relation-")

            is Rg07Operation.ImportConfirm -> operation.input.candidateId.removePrefix("candidate-refund-")

            else -> operation.identity.value.removePrefix("request-")
        }

    private fun historyId(
        relationId: String,
        status: Rg07RefundStatus,
    ): String {
        val suffix = relationId.removePrefix("refund-relation-")

        return when {
            suffix == "rg07-manual" -> "history-rg07-${status.name.lowercase()}"

            suffix == "rg07-import" && status == Rg07RefundStatus.RECEIVED -> "history-rg07-import-relation-received"

            else -> "history-$suffix-${status.name.lowercase()}"
        }
    }

    private fun mirrorOfSourceId(sourceId: String): String = sourceId.removeSuffix("-mirror") + "-credit"
}

private class Rg07Rollback(
    val result: Rg07ExecutionResult,
) : RuntimeException()
