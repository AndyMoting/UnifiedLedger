package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.BalanceAdjustmentCommand
import com.unifiedledger.domain.BalanceAdjustmentIds
import com.unifiedledger.domain.BalanceAdjustmentViolation
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OwnAssetPrincipalTransferCommand
import com.unifiedledger.domain.OwnAssetPrincipalTransferIds
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.createBalanceAdjustment
import com.unifiedledger.domain.createOwnAssetPrincipalTransfer
import kotlin.time.Instant

data class Rg09ObservationId(val value: String)
data class Rg09CandidateId(val value: String)
data class Rg09SourceRecordId(val value: String)
data class Rg09EvidenceId(val value: String)
data class Rg09EvidenceLinkId(val value: String)
data class Rg09ConfirmationId(val value: String)
data class Rg09AdjustmentId(val value: String)
data class Rg09AllocationId(val value: String)
data class Rg09AuditLinkId(val value: String)

data class Rg09OperationIdentity(
    val ledgerId: LedgerId,
    val value: String,
)

enum class Rg09Action(val code: String) {
    PREVIEW_TARGET_BALANCE("preview_target_balance"),
    CONFIRM_BALANCE_ADJUSTMENT("confirm_balance_adjustment"),
    CONFIRM_REAL_TRANSFER("confirm_real_transfer"),
    INGEST_IMPORTED_TRANSFER("ingest_imported_transfer"),
    CONFIRM_IMPORTED_TRANSFER("confirm_imported_transfer"),
    CONFIRM_EXPLANATION_ALLOCATION("confirm_explanation_allocation"),
    LINK_REAL_POSTING_EVIDENCE("link_real_posting_evidence"),
    REJECT_INVALID_INPUT("reject_invalid_rg09_input"),
}

enum class Rg09InvalidPredicate {
    EXACT_DECIMAL,
    TIMEZONE_AWARE,
    LEDGER_TIMEZONE,
    KNOWN_ACCOUNT,
    OWNED_REAL_ASSET,
    CURRENCY_CNY,
    DEDICATED_EQUITY,
    SAME_DIRECTION,
    SAME_TARGET_ACCOUNT,
    BEFORE_TARGET,
    REMAINING_CAP,
    EXPLICIT_LINK,
    IDEMPOTENCY_CONFLICT,
}

data class Rg09InvalidInput(
    val requestId: RequestId,
    val predicate: Rg09InvalidPredicate,
    val attemptedInput: Map<String, String?>,
)

data class Rg09PreviewTargetBalanceInput(
    val requestId: RequestId,
    val accountId: AccountId,
    val targetAmount: Money,
    val targetObservedAt: Instant,
    val savedAt: Instant,
    val currency: CurrencyUnit,
    val explicitConfirmation: Boolean,
    val immutablePayloadDigest: String,
    val ledgerFingerprint: String,
    val targetObservedAtText: String = targetObservedAt.toString(),
    val savedAtText: String = savedAt.toString(),
)

data class Rg09PreviewIds(
    val observationId: Rg09ObservationId,
    val sourceRecordId: Rg09SourceRecordId,
    val evidenceId: Rg09EvidenceId,
    val evidenceLinkId: Rg09EvidenceLinkId,
    val candidateId: Rg09CandidateId?,
)

data class Rg09ConfirmBalanceAdjustmentInput(
    val requestId: RequestId,
    val candidateId: Rg09CandidateId,
    val ledgerFingerprint: String,
    val explicitConfirmation: Boolean,
    val confirmedAt: Instant,
    val confirmedAtText: String = confirmedAt.toString(),
)

data class Rg09AdjustmentCommitIds(
    val confirmationId: Rg09ConfirmationId,
    val adjustmentId: Rg09AdjustmentId,
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val targetPostingId: PostingId,
    val equityPostingId: PostingId,
    val historyId: String,
)

data class Rg09ConfirmRealTransferInput(
    val requestId: RequestId,
    val targetAccountId: AccountId,
    val counterAccountId: AccountId,
    val amount: Money,
    val actualOccurredAt: Instant,
    val discoveredAt: Instant,
    val confirmedAt: Instant,
    val immutablePayloadDigest: String,
    val explicitConfirmation: Boolean,
    val confirmsTargetAccount: Boolean,
    val confirmsCounterAccount: Boolean,
    val confirmsActualOccurredAt: Boolean,
    val confirmsCurrency: Boolean,
    val confirmsAmount: Boolean,
    val confirmsExplanationAllocation: Boolean,
    val targetAccountDirection: String = "increase",
    val actualOccurredAtText: String = actualOccurredAt.toString(),
    val discoveredAtText: String = discoveredAt.toString(),
    val confirmedAtText: String = confirmedAt.toString(),
    val sourceId: Rg09SourceRecordId? = null,
    val candidateId: Rg09CandidateId? = null,
)

data class Rg09TransferCommitIds(
    val confirmationId: Rg09ConfirmationId,
    val sourceRecordId: Rg09SourceRecordId,
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val sourcePostingId: PostingId,
    val destinationPostingId: PostingId,
)

data class Rg09ConfirmExplanationAllocationInput(
    val requestId: RequestId,
    val adjustmentId: Rg09AdjustmentId,
    val transactionId: TransactionId,
    val targetAccountId: AccountId,
    val actualOccurredAt: Instant,
    val realTransactionAmount: Money,
    val targetObservedAt: Instant,
    val explanationAmount: Money,
    val confirmedAt: Instant,
    val explicitConfirmation: Boolean,
    val confirmsTargetAccount: Boolean,
    val confirmsActualOccurredAt: Boolean,
    val confirmsRealTransactionAmount: Boolean,
    val confirmsCurrency: Boolean,
    val confirmsTargetObservedAt: Boolean,
    val confirmsAllocationDirection: Boolean,
    val confirmsExplanationAmount: Boolean,
    val actualOccurredAtText: String = actualOccurredAt.toString(),
    val targetObservedAtText: String = targetObservedAt.toString(),
    val confirmedAtText: String = confirmedAt.toString(),
    val discoveredAt: Instant? = null,
    val discoveredAtText: String? = null,
)

data class Rg09AllocationCommitIds(
    val confirmationId: Rg09ConfirmationId,
    val allocationId: Rg09AllocationId,
    val reversalTransactionId: TransactionId,
    val reversalVersionId: TransactionVersionId,
    val reversalPostingSetId: PostingSetId,
    val reversalTargetPostingId: PostingId,
    val reversalEquityPostingId: PostingId,
    val adjustmentAuditLinkId: Rg09AuditLinkId,
    val explanationAuditLinkId: Rg09AuditLinkId,
    val reversalAuditLinkId: Rg09AuditLinkId,
    val historyId: String,
)

data class Rg09LinkRealPostingEvidenceInput(
    val requestId: RequestId,
    val sourceId: Rg09SourceRecordId,
    val evidenceId: Rg09EvidenceId,
    val targetPostingId: PostingId,
    val accountId: AccountId,
    val amount: Money,
    val postingSide: String,
    val observedAt: Instant,
    val bookingAt: Instant,
    val immutablePayloadDigest: String,
    val explicitConfirmation: Boolean,
    val observedAtText: String = observedAt.toString(),
    val bookingAtText: String = bookingAt.toString(),
)

data class Rg09IngestImportedTransferInput(
    val requestId: RequestId,
    val sourceId: Rg09SourceRecordId,
    val evidenceId: Rg09EvidenceId,
    val candidateId: Rg09CandidateId,
    val targetAccountId: AccountId,
    val counterAccountId: AccountId,
    val amount: Money,
    val actualOccurredAt: Instant,
    val observedAt: Instant,
    val immutablePayloadDigest: String,
    val confidence: String,
    val explicitConfirmation: Boolean,
    val actualOccurredAtText: String = actualOccurredAt.toString(),
    val observedAtText: String = observedAt.toString(),
)

data class Rg09ImportedTransferIds(
    val sourceId: Rg09SourceRecordId,
    val evidenceId: Rg09EvidenceId,
    val candidateId: Rg09CandidateId,
)

data class Rg09IncompleteImportedTransferConfirmationInput(
    val requestId: RequestId,
    val candidateId: Rg09CandidateId,
    val transactionId: TransactionId?,
    val targetAccountId: AccountId?,
    val actualOccurredAt: Instant?,
    val currency: CurrencyUnit?,
    val explanationAmount: Money?,
    val explicitConfirmation: Boolean,
)

data class Rg09PostingEvidenceIds(
    val evidenceLinkId: Rg09EvidenceLinkId,
)

sealed interface Rg09Operation {
    val ledgerId: LedgerId
    val action: Rg09Action
    val identity: Rg09OperationIdentity

    data class PreviewTargetBalance(
        override val ledgerId: LedgerId,
        val input: Rg09PreviewTargetBalanceInput,
        val ids: Rg09PreviewIds,
    ) : Rg09Operation {
        override val action = Rg09Action.PREVIEW_TARGET_BALANCE
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmBalanceAdjustment(
        override val ledgerId: LedgerId,
        val input: Rg09ConfirmBalanceAdjustmentInput,
        val ids: Rg09AdjustmentCommitIds,
    ) : Rg09Operation {
        override val action = Rg09Action.CONFIRM_BALANCE_ADJUSTMENT
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmRealTransfer(
        override val ledgerId: LedgerId,
        val input: Rg09ConfirmRealTransferInput,
        val ids: Rg09TransferCommitIds,
    ) : Rg09Operation {
        override val action = Rg09Action.CONFIRM_REAL_TRANSFER
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class IngestImportedTransfer(
        override val ledgerId: LedgerId,
        val input: Rg09IngestImportedTransferInput,
        val ids: Rg09ImportedTransferIds,
    ) : Rg09Operation {
        override val action = Rg09Action.INGEST_IMPORTED_TRANSFER
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmImportedTransfer(
        override val ledgerId: LedgerId,
        val input: Rg09ConfirmRealTransferInput,
        val ids: Rg09TransferCommitIds,
    ) : Rg09Operation {
        override val action = Rg09Action.CONFIRM_IMPORTED_TRANSFER
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class IncompleteImportedTransferConfirmation(
        override val ledgerId: LedgerId,
        val input: Rg09IncompleteImportedTransferConfirmationInput,
    ) : Rg09Operation {
        override val action = Rg09Action.CONFIRM_IMPORTED_TRANSFER
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmExplanationAllocation(
        override val ledgerId: LedgerId,
        val input: Rg09ConfirmExplanationAllocationInput,
        val ids: Rg09AllocationCommitIds,
    ) : Rg09Operation {
        override val action = Rg09Action.CONFIRM_EXPLANATION_ALLOCATION
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class LinkRealPostingEvidence(
        override val ledgerId: LedgerId,
        val input: Rg09LinkRealPostingEvidenceInput,
        val ids: Rg09PostingEvidenceIds,
    ) : Rg09Operation {
        override val action = Rg09Action.LINK_REAL_POSTING_EVIDENCE
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }

    data class InvalidInput(
        override val ledgerId: LedgerId,
        val input: Rg09InvalidInput,
    ) : Rg09Operation {
        override val action = Rg09Action.REJECT_INVALID_INPUT
        override val identity = Rg09OperationIdentity(ledgerId, input.requestId.value)
    }
}

sealed interface Rg09ReturnedId {
    data class Observation(val id: Rg09ObservationId) : Rg09ReturnedId
    data class Candidate(val id: Rg09CandidateId) : Rg09ReturnedId
    data class SourceRecord(val id: Rg09SourceRecordId) : Rg09ReturnedId
    data class Evidence(val id: Rg09EvidenceId) : Rg09ReturnedId
    data class EvidenceLink(val id: Rg09EvidenceLinkId) : Rg09ReturnedId
    data class Confirmation(val id: Rg09ConfirmationId) : Rg09ReturnedId
    data class Adjustment(val id: Rg09AdjustmentId) : Rg09ReturnedId
    data class Allocation(val id: Rg09AllocationId) : Rg09ReturnedId
    data class AuditLink(val id: Rg09AuditLinkId) : Rg09ReturnedId
    data class Transaction(val id: TransactionId) : Rg09ReturnedId
    data class Version(val id: TransactionVersionId) : Rg09ReturnedId
}

enum class Rg09RejectionReason(val code: String) {
    EXPLICIT_CONFIRMATION_REQUIRED("explicit_confirmation_required"),
    EXACT_DECIMAL_STRING_REQUIRED("exact_decimal_string_required"),
    TIMEZONE_AWARE_TARGET_TIME_REQUIRED("timezone_aware_target_time_required"),
    LEDGER_TIMEZONE_REQUIRED("ledger_timezone_required"),
    UNKNOWN_ACCOUNT("unknown_account"),
    OWNED_REAL_ASSET_REQUIRED("owned_real_asset_required"),
    SAME_CURRENCY_REQUIRED("same_currency_required"),
    DEDICATED_ADJUSTMENT_EQUITY_REQUIRED("dedicated_adjustment_equity_required"),
    ZERO_DELTA_HAS_NO_ADJUSTMENT("zero_delta_has_no_adjustment"),
    CANDIDATE_NOT_FOUND("candidate_not_found"),
    CANDIDATE_NOT_PENDING("candidate_not_pending"),
    LEDGER_CHANGED_SINCE_PREVIEW("ledger_changed_since_preview"),
    ADJUSTMENT_NOT_FOUND("adjustment_not_found"),
    REAL_TRANSACTION_NOT_FOUND("real_transaction_not_found"),
    REAL_TRANSFER_REQUIRED("real_transfer_required"),
    REAL_TRANSACTION_TIME_MISMATCH("real_transaction_time_mismatch"),
    EXPLANATION_DIRECTION_MISMATCH("explanation_direction_mismatch"),
    SAME_TARGET_ACCOUNT_REQUIRED("same_target_account_required"),
    EXPLANATION_AFTER_TARGET_TIME("explanation_must_not_follow_target_time"),
    ALLOCATION_EXCEEDS_REMAINING("allocation_exceeds_remaining_adjustment"),
    EXPLANATION_EXCEEDS_REAL_TRANSACTION("explanation_exceeds_real_transaction"),
    REAL_TRANSACTION_NOT_CONFIRMED("real_transaction_confirmation_required"),
    EXPLICIT_LINK_CONFIRMATION_REQUIRED("explicit_link_confirmation_required"),
    EXACT_TRANSACTION_REQUIRED("exact_transaction_required"),
    EXACT_TARGET_ACCOUNT_REQUIRED("exact_target_account_required"),
    ACTUAL_TIME_REQUIRED("actual_time_required"),
    EXACT_CURRENCY_REQUIRED("exact_currency_required"),
    EXPLICIT_EXPLANATION_ALLOCATION_REQUIRED("explicit_explanation_allocation_required"),
    INVALID_TIMESTAMP_TEXT("invalid_timestamp_text"),
    INVALID_DECIMAL_TEXT("invalid_decimal_text"),
    POSTING_NOT_FOUND("posting_not_found"),
    POSTING_OWNERSHIP_REQUIRED("posting_ownership_required"),
    POSTING_AMOUNT_MISMATCH("posting_amount_mismatch"),
    POSTING_SIDE_MISMATCH("posting_side_mismatch"),
    SOURCE_NOT_FOUND("source_not_found"),
    EVIDENCE_NOT_FOUND("evidence_not_found"),
    IDENTITY_CONFLICT("idempotency_key_conflict"),
    DOMAIN_REJECTED("domain_rejected"),
}

enum class Rg09FieldPath(val value: String) {
    INPUT_ACCOUNT("$.input.account_id"),
    INPUT_TARGET_AMOUNT("$.input.target_amount"),
    INPUT_TARGET_TIME("$.input.target_observed_at"),
    INPUT_CANDIDATE("$.input.candidate_id"),
    INPUT_FINGERPRINT("$.input.ledger_fingerprint"),
    INPUT_CONFIRMATION("$.input.explicit_confirmation"),
    INPUT_TARGET_ACCOUNT("$.input.target_account_id"),
    INPUT_COUNTER_ACCOUNT("$.input.counter_account_id"),
    INPUT_TRANSACTION("$.input.transaction_id"),
    INPUT_AMOUNT("$.input.amount"),
    INPUT_ACTUAL_TIME("$.input.actual_occurred_at"),
    INPUT_EXPLANATION_AMOUNT("$.input.explanation_amount"),
    INPUT_ALLOCATION_DIRECTION("$.input.confirms_allocation_direction"),
    INPUT_TRANSACTION_REQUIRED("$.input.transaction_id"),
    INPUT_TARGET_ACCOUNT_REQUIRED("$.input.target_account_id"),
    INPUT_ACTUAL_TIME_REQUIRED("$.input.actual_occurred_at"),
    INPUT_CURRENCY_REQUIRED("$.input.currency"),
    INPUT_EXPLANATION_REQUIRED("$.input.explanation_allocation"),
    INPUT_SOURCE("$.input.source_id"),
    INPUT_EVIDENCE("$.input.evidence_id"),
    INPUT_TARGET_POSTING("$.input.target_posting_id"),
    INPUT_POSTING_AMOUNT("$.input.amount"),
    INPUT_POSTING_SIDE("$.input.posting_side"),
    ATTEMPTED_TARGET_AMOUNT("$.attempted_input.target_amount"),
    ATTEMPTED_TARGET_TIME("$.attempted_input.target_observed_at"),
    ATTEMPTED_ACCOUNT("$.attempted_input.account_id"),
    ATTEMPTED_CURRENCY("$.attempted_input.currency"),
    ATTEMPTED_EQUITY_ACCOUNT("$.attempted_input.equity_account_id"),
    ATTEMPTED_DIRECTION("$.attempted_input.direction"),
    ATTEMPTED_ACTUAL_TIME("$.attempted_input.actual_at"),
    ATTEMPTED_REQUEST_ID("$.attempted_input.request_id"),
    ATTEMPTED_REQUESTED_AMOUNT("$.attempted_input.requested_amount"),
    ATTEMPTED_CONFIRMATION("$.attempted_input.explicit_confirmation"),
}

sealed interface Rg09ExecutionResult {
    class Accepted(returnedIds: List<Rg09ReturnedId>) : Rg09ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg09ReturnedId> get() = snapshot.toList()
        override fun equals(other: Any?) = other is Accepted && snapshot == other.snapshot
        override fun hashCode(): Int = snapshot.hashCode()
        override fun toString(): String = "Accepted(returnedIds=$snapshot)"
    }

    class NoChange(returnedIds: List<Rg09ReturnedId>) : Rg09ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg09ReturnedId> get() = snapshot.toList()
        override fun equals(other: Any?) = other is NoChange && snapshot == other.snapshot
        override fun hashCode(): Int = snapshot.hashCode()
        override fun toString(): String = "NoChange(returnedIds=$snapshot)"
    }

    data class Rejected(
        val reason: Rg09RejectionReason,
        val fieldPath: Rg09FieldPath,
        val diagnostics: Rg09StaleDiagnostics? = null,
    ) : Rg09ExecutionResult

    data object RequestIdentityConflict : Rg09ExecutionResult
}

data class Rg09StaleDiagnostics(
    val previewLedgerFingerprint: String,
    val currentLedgerFingerprint: String,
    val recomputedReplayAmount: Money,
    val recomputedDelta: Money,
)

fun interface Rg09CommitPort {
    fun commit(operation: Rg09Operation): Rg09ExecutionResult
}

data class Rg09FormalTransactionRecord(
    val formalTransaction: FormalTransaction,
    val createdAt: Instant,
    val sourceRecordId: Rg09SourceRecordId? = null,
    val createdAtText: String? = null,
    val effectiveAtText: String? = null,
    val statisticsAtText: String? = null,
)

data class Rg09Observation(
    val id: Rg09ObservationId,
    val sourceRecordId: Rg09SourceRecordId,
    val accountId: AccountId,
    val targetAmount: Money,
    val targetObservedAt: Instant,
    val savedAt: Instant,
    val targetObservedAtText: String = targetObservedAt.toString(),
    val savedAtText: String = savedAt.toString(),
)

data class Rg09Candidate(
    val id: Rg09CandidateId,
    val observationId: Rg09ObservationId?,
    val accountId: AccountId,
    val replayedAmount: Money,
    val targetAmount: Money,
    val delta: Money,
    val targetObservedAt: Instant,
    val ledgerFingerprint: String,
    val status: String,
    val adjustmentId: Rg09AdjustmentId? = null,
    val confirmationRequestId: RequestId? = null,
    val targetObservedAtText: String = targetObservedAt.toString(),
    val sourceRecordId: Rg09SourceRecordId? = null,
    val candidateType: String = "balance_adjustment",
    val confidence: String? = null,
)

data class Rg09SourceRecord(
    val id: Rg09SourceRecordId,
    val sourceType: String,
    val observedAt: Instant,
    val accountId: AccountId,
    val amount: Money,
    val counterAccountId: AccountId? = null,
    val actualAt: Instant? = null,
    val bookingAt: Instant? = null,
    val immutablePayloadDigest: String,
    val observedAtText: String = observedAt.toString(),
    val actualAtText: String? = actualAt?.toString(),
    val bookingAtText: String? = bookingAt?.toString(),
)

data class Rg09Evidence(
    val id: Rg09EvidenceId,
    val sourceRecordId: Rg09SourceRecordId,
    val evidenceType: String,
    val observedAt: Instant,
    val observedAtText: String = observedAt.toString(),
)

data class Rg09EvidenceLink(
    val id: Rg09EvidenceLinkId,
    val sourceRecordId: Rg09SourceRecordId,
    val evidenceId: Rg09EvidenceId,
    val role: String,
    val targetId: String,
    val status: String,
)

data class Rg09Confirmation(
    val id: Rg09ConfirmationId,
    val requestId: RequestId,
    val role: String,
    val confirmedAt: Instant,
    val targetId: String,
    val confirmedAtText: String = confirmedAt.toString(),
    val createdAt: Instant = confirmedAt,
    val createdAtText: String = createdAt.toString(),
)

data class Rg09AdjustmentHistory(
    val id: String,
    val state: String,
    val occurredAt: Instant,
    val allocationId: Rg09AllocationId?,
    val remainingAmount: Money,
    val occurredAtText: String = occurredAt.toString(),
    val createdAt: Instant = occurredAt,
    val createdAtText: String = createdAt.toString(),
)

data class Rg09Adjustment(
    val id: Rg09AdjustmentId,
    val transactionId: TransactionId,
    val observationId: Rg09ObservationId,
    val targetAccountId: AccountId,
    val equityAccountId: AccountId,
    val currency: CurrencyUnit,
    val targetObservedAt: Instant,
    val replayedAmountAtConfirmation: Money,
    val targetAmount: Money,
    val originalDelta: Money,
    val explainedAmount: Money,
    val remainingAmount: Money,
    val state: String,
    val history: List<Rg09AdjustmentHistory>,
    val targetObservedAtText: String = targetObservedAt.toString(),
)

data class Rg09Allocation(
    val id: Rg09AllocationId,
    val adjustmentId: Rg09AdjustmentId,
    val targetAccountId: AccountId,
    val amount: Money,
    val realTransactionId: TransactionId,
    val reversalTransactionId: TransactionId,
    val confirmedAt: Instant,
    val discoveredAt: Instant = confirmedAt,
    val discoveredAtText: String = discoveredAt.toString(),
    val confirmedAtText: String = confirmedAt.toString(),
    val createdAt: Instant = confirmedAt,
    val createdAtText: String = createdAt.toString(),
)

data class Rg09AuditLink(
    val id: Rg09AuditLinkId,
    val allocationId: Rg09AllocationId,
    val role: String,
    val targetId: String,
    val createdAt: Instant,
    val createdAtText: String = createdAt.toString(),
)

data class Rg09Report(
    val ordinaryIncomeMinor: Long = 0L,
    val ordinaryExpenseMinor: Long = 0L,
    val consumptionMinor: Long = 0L,
    val budgetEffectMinor: Long = 0L,
    val categoryEffectMinor: Long = 0L,
    val cashInflowMinor: Long = 0L,
    val cashOutflowMinor: Long = 0L,
    val internalTransferMinor: Long = 0L,
    val balanceAdjustmentNetWorthChangeMinor: Long = 0L,
    val netWorthChangeMinor: Long = 0L,
)

data class Rg09Snapshot(
    val formalTransactions: List<Rg09FormalTransactionRecord>,
    val observations: List<Rg09Observation>,
    val candidates: List<Rg09Candidate>,
    val sourceRecords: List<Rg09SourceRecord>,
    val evidence: List<Rg09Evidence>,
    val evidenceLinks: List<Rg09EvidenceLink>,
    val confirmations: List<Rg09Confirmation>,
    val adjustments: List<Rg09Adjustment>,
    val allocations: List<Rg09Allocation>,
    val auditLinks: List<Rg09AuditLink>,
    val balances: Map<AccountId, Money>,
    val reports: Map<String, Rg09Report>,
    val reconciliation: Map<String, String>,
)

/**
 * Deterministic runtime used by the fixture adapter and application tests. Its business
 * transitions stay independent from a database driver so domain and replay proofs remain fast;
 * persistence integration must preserve this same typed operation boundary.
 */
class Rg09Runtime(
    private val catalog: LedgerCatalog,
    openingTransactions: List<Rg09FormalTransactionRecord>,
) : Rg09CommitPort {
    constructor(catalog: LedgerCatalog, snapshot: Rg09Snapshot) : this(catalog, snapshot.formalTransactions) {
        observations += snapshot.observations
        candidates += snapshot.candidates
        sourceRecords += snapshot.sourceRecords
        evidence += snapshot.evidence
        evidenceLinks += snapshot.evidenceLinks
        confirmations += snapshot.confirmations
        adjustments += snapshot.adjustments.map { it.copy(history = it.history.toList()) }
        allocations += snapshot.allocations
        auditLinks += snapshot.auditLinks
        val postingIds = snapshot.formalTransactions
            .flatMap { it.formalTransaction.currentPostings() }
            .mapTo(mutableSetOf()) { it.id.value }
        postingReconciliation += snapshot.reconciliation.filterKeys { it in postingIds }
    }

    private val formalTransactions = openingTransactions.toMutableList()
    private val observations = mutableListOf<Rg09Observation>()
    private val candidates = mutableListOf<Rg09Candidate>()
    private val sourceRecords = mutableListOf<Rg09SourceRecord>()
    private val evidence = mutableListOf<Rg09Evidence>()
    private val evidenceLinks = mutableListOf<Rg09EvidenceLink>()
    private val confirmations = mutableListOf<Rg09Confirmation>()
    private val adjustments = mutableListOf<Rg09Adjustment>()
    private val allocations = mutableListOf<Rg09Allocation>()
    private val auditLinks = mutableListOf<Rg09AuditLink>()
    private val postingReconciliation = mutableMapOf<String, String>()
    private val receipts = mutableMapOf<Rg09OperationIdentity, Receipt>()

    private data class Receipt(
        val fingerprint: String,
        val result: Rg09ExecutionResult,
    )

    override fun commit(operation: Rg09Operation): Rg09ExecutionResult {
        val fingerprint = canonicalInput(operation)
        receipts[operation.identity]?.let { receipt ->
            return if (receipt.fingerprint == fingerprint) {
                when (val result = receipt.result) {
                    is Rg09ExecutionResult.Accepted -> Rg09ExecutionResult.NoChange(result.returnedIds)
                    else -> result
                }
            } else {
                Rg09ExecutionResult.RequestIdentityConflict
            }
        }
        val result = when (operation) {
            is Rg09Operation.PreviewTargetBalance -> preview(operation)
            is Rg09Operation.ConfirmBalanceAdjustment -> confirmAdjustment(operation)
            is Rg09Operation.ConfirmRealTransfer -> confirmTransfer(operation)
            is Rg09Operation.IngestImportedTransfer -> ingestImportedTransfer(operation)
            is Rg09Operation.ConfirmImportedTransfer -> confirmTransfer(
                Rg09Operation.ConfirmRealTransfer(operation.ledgerId, operation.input, operation.ids),
                imported = true,
            )
            is Rg09Operation.IncompleteImportedTransferConfirmation -> rejectIncompleteImportedTransfer(operation)
            is Rg09Operation.ConfirmExplanationAllocation -> confirmExplanation(operation)
            is Rg09Operation.LinkRealPostingEvidence -> linkRealPostingEvidence(operation)
            is Rg09Operation.InvalidInput -> rejectInvalidInput(operation)
        }
        if (result is Rg09ExecutionResult.Accepted || result is Rg09ExecutionResult.Rejected) {
            receipts[operation.identity] = Receipt(fingerprint, result)
        }
        return result
    }

    fun snapshot(): Rg09Snapshot = Rg09Snapshot(
        formalTransactions = formalTransactions.toList(),
        observations = observations.toList(),
        candidates = candidates.toList(),
        sourceRecords = sourceRecords.toList(),
        evidence = evidence.toList(),
        evidenceLinks = evidenceLinks.toList(),
        confirmations = confirmations.toList(),
        adjustments = adjustments.map { it.copy(history = it.history.toList()) },
        allocations = allocations.toList(),
        auditLinks = auditLinks.toList(),
        balances = replayBalances(null),
        reports = reports(),
        reconciliation = reconciliation() + postingReconciliation,
    )

    fun operationFingerprint(operation: Rg09Operation): String = canonicalInput(operation)

    fun appendExternalTransaction(record: Rg09FormalTransactionRecord) {
        require(catalogCompatible(record.formalTransaction)) { "RG-09 external transaction is incompatible with the catalog" }
        require(!formalIdCollision(record.formalTransaction)) { "RG-09 external transaction reuses a formal ID" }
        formalTransactions += record
    }

    private fun preview(operation: Rg09Operation.PreviewTargetBalance): Rg09ExecutionResult {
        val input = operation.input
        if (input.explicitConfirmation) {
            return rejected(Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_CONFIRMATION)
        }
        val account = catalogAccount(input.accountId)
            ?: return rejected(Rg09RejectionReason.UNKNOWN_ACCOUNT, Rg09FieldPath.INPUT_ACCOUNT)
        if (!account.ownedByUser || !account.realAccount || account.kind.name != "ASSET") {
            return rejected(Rg09RejectionReason.OWNED_REAL_ASSET_REQUIRED, Rg09FieldPath.INPUT_ACCOUNT)
        }
        if (account.currency != input.currency || input.targetAmount.currency != input.currency) {
            return rejected(Rg09RejectionReason.SAME_CURRENCY_REQUIRED, Rg09FieldPath.INPUT_TARGET_AMOUNT)
        }
        if (!isRg09ShanghaiTimestamp(input.targetObservedAtText, input.targetObservedAt)) {
            return rejected(Rg09RejectionReason.LEDGER_TIMEZONE_REQUIRED, Rg09FieldPath.INPUT_TARGET_TIME)
        }
        val currentFingerprint = Rg09LedgerFingerprint.digest(formalTransactions, input.targetObservedAt)
        val replayed = replayAmount(input.accountId, input.targetObservedAt)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_TARGET_AMOUNT)
        val deltaMinor = subtractExact(input.targetAmount.minorUnits, replayed.minorUnits)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_TARGET_AMOUNT)
        val candidateId = operation.ids.candidateId
        if (deltaMinor != 0L && candidateId == null) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_TARGET_AMOUNT)
        }
        val observation = Rg09Observation(
            id = operation.ids.observationId,
            sourceRecordId = operation.ids.sourceRecordId,
            accountId = input.accountId,
            targetAmount = input.targetAmount,
            targetObservedAt = input.targetObservedAt,
            savedAt = input.savedAt,
            targetObservedAtText = input.targetObservedAtText,
            savedAtText = input.savedAtText,
        )
        val source = Rg09SourceRecord(
            id = operation.ids.sourceRecordId,
            sourceType = "manual_balance_observation",
            observedAt = input.targetObservedAt,
            accountId = input.accountId,
            amount = input.targetAmount,
            immutablePayloadDigest = input.immutablePayloadDigest,
            observedAtText = input.targetObservedAtText,
        )
        val evidenceItem = Rg09Evidence(
            id = operation.ids.evidenceId,
            sourceRecordId = operation.ids.sourceRecordId,
            evidenceType = "target_balance_observation",
            observedAt = input.targetObservedAt,
            observedAtText = input.targetObservedAtText,
        )
        val evidenceLink = Rg09EvidenceLink(
            id = operation.ids.evidenceLinkId,
            sourceRecordId = operation.ids.sourceRecordId,
            evidenceId = operation.ids.evidenceId,
            role = "target_balance_observation",
            targetId = operation.ids.observationId.value,
            status = "recorded",
        )
        if (
            observations.any { it.id == operation.ids.observationId } ||
            sourceRecords.any { it.id == operation.ids.sourceRecordId } ||
            evidence.any { it.id == operation.ids.evidenceId } ||
            evidenceLinks.any { it.id == operation.ids.evidenceLinkId } ||
            candidateId?.let { id -> candidates.any { it.id == id } } == true
        ) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_ACCOUNT)
        }
        observations += observation
        sourceRecords += source
        evidence += evidenceItem
        evidenceLinks += evidenceLink
        if (deltaMinor == 0L) {
            return accepted(
                listOf(
                    Rg09ReturnedId.Observation(operation.ids.observationId),
                    Rg09ReturnedId.SourceRecord(operation.ids.sourceRecordId),
                    Rg09ReturnedId.Evidence(operation.ids.evidenceId),
                    Rg09ReturnedId.EvidenceLink(operation.ids.evidenceLinkId),
                ),
            )
        }
        val nonZeroCandidateId = checkNotNull(candidateId)
        candidates += Rg09Candidate(
            id = nonZeroCandidateId,
            observationId = operation.ids.observationId,
            accountId = input.accountId,
            replayedAmount = replayed,
            targetAmount = input.targetAmount,
            delta = Money.ofMinor(deltaMinor, input.currency),
            targetObservedAt = input.targetObservedAt,
            ledgerFingerprint = currentFingerprint,
            status = "pending_confirmation",
            targetObservedAtText = input.targetObservedAtText,
            sourceRecordId = operation.ids.sourceRecordId,
        )
        return accepted(
            listOf(
                Rg09ReturnedId.Observation(operation.ids.observationId),
                Rg09ReturnedId.SourceRecord(operation.ids.sourceRecordId),
                Rg09ReturnedId.Evidence(operation.ids.evidenceId),
                Rg09ReturnedId.EvidenceLink(operation.ids.evidenceLinkId),
                Rg09ReturnedId.Candidate(candidateId),
            ),
        )
    }

    private fun confirmAdjustment(operation: Rg09Operation.ConfirmBalanceAdjustment): Rg09ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_CONFIRMATION)
        }
        val candidate = candidates.firstOrNull { it.id == input.candidateId }
            ?: return rejected(Rg09RejectionReason.CANDIDATE_NOT_FOUND, Rg09FieldPath.INPUT_CANDIDATE)
        if (candidate.status != "pending_confirmation") {
            return rejected(Rg09RejectionReason.CANDIDATE_NOT_PENDING, Rg09FieldPath.INPUT_CANDIDATE)
        }
        val replayed = replayAmount(candidate.accountId, candidate.targetObservedAt)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_FINGERPRINT)
        val currentDelta = subtractExact(candidate.targetAmount.minorUnits, replayed.minorUnits)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_FINGERPRINT)
        val currentFingerprint = Rg09LedgerFingerprint.digest(formalTransactions, candidate.targetObservedAt)
        if (candidate.ledgerFingerprint != input.ledgerFingerprint || candidate.ledgerFingerprint != currentFingerprint || replayed != candidate.replayedAmount) {
            return Rg09ExecutionResult.Rejected(
                reason = Rg09RejectionReason.LEDGER_CHANGED_SINCE_PREVIEW,
                fieldPath = Rg09FieldPath.INPUT_FINGERPRINT,
                diagnostics = Rg09StaleDiagnostics(
                    previewLedgerFingerprint = candidate.ledgerFingerprint,
                    currentLedgerFingerprint = currentFingerprint,
                    recomputedReplayAmount = replayed,
                    recomputedDelta = Money.ofMinor(currentDelta, candidate.targetAmount.currency),
                ),
            )
        }
        val observation = observations.firstOrNull { it.id == candidate.observationId }
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_CANDIDATE)
        val remaining = absMinor(candidate.delta)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_CANDIDATE)
        val result = createBalanceAdjustment(
            catalog,
            BalanceAdjustmentCommand(
                ledgerId = operation.ledgerId,
                targetAccountId = candidate.accountId,
                adjustmentEquityAccountId = adjustmentEquityAccountId(),
                delta = candidate.delta,
                times = TransactionTimes.collapsed(candidate.targetObservedAt),
            ),
            BalanceAdjustmentIds(
                transactionId = operation.ids.transactionId,
                versionId = operation.ids.versionId,
                postingSetId = operation.ids.postingSetId,
                targetPostingId = operation.ids.targetPostingId,
                equityPostingId = operation.ids.equityPostingId,
            ),
        )
        val adjustment = when (result) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_CANDIDATE)
        }
        val adjustmentRecord = Rg09FormalTransactionRecord(
            adjustment.formalTransaction,
            input.confirmedAt,
            createdAtText = input.confirmedAtText,
            effectiveAtText = candidate.targetObservedAtText,
            statisticsAtText = candidate.targetObservedAtText,
        )
        if (
            confirmations.any { it.id == operation.ids.confirmationId } ||
            adjustments.any { it.id == operation.ids.adjustmentId } ||
            adjustments.any { it.history.any { history -> history.id == operation.ids.historyId } } ||
            !canAppendFormalTransaction(adjustmentRecord)
        ) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_CANDIDATE)
        }
        formalTransactions += adjustmentRecord
        adjustments += Rg09Adjustment(
            id = operation.ids.adjustmentId,
            transactionId = operation.ids.transactionId,
            observationId = observation.id,
            targetAccountId = candidate.accountId,
            equityAccountId = adjustmentEquityAccountId(),
            currency = candidate.delta.currency,
            targetObservedAt = candidate.targetObservedAt,
            replayedAmountAtConfirmation = candidate.replayedAmount,
            targetAmount = candidate.targetAmount,
            originalDelta = candidate.delta,
            explainedAmount = Money.ofMinor(0L, candidate.delta.currency),
            remainingAmount = Money.ofMinor(remaining, candidate.delta.currency),
            state = "open",
            history = listOf(
                Rg09AdjustmentHistory(
                    id = operation.ids.historyId,
                    state = "open",
                    occurredAt = input.confirmedAt,
                    allocationId = null,
                    remainingAmount = Money.ofMinor(remaining, candidate.delta.currency),
                    occurredAtText = input.confirmedAtText,
                    createdAt = input.confirmedAt,
                    createdAtText = input.confirmedAtText,
                ),
            ),
            targetObservedAtText = candidate.targetObservedAtText,
        )
        candidates[candidates.indexOf(candidate)] = candidate.copy(
            status = "confirmed",
            adjustmentId = operation.ids.adjustmentId,
            confirmationRequestId = input.requestId,
        )
        confirmations += Rg09Confirmation(
            id = operation.ids.confirmationId,
            requestId = input.requestId,
            role = "balance_adjustment_confirmation",
            confirmedAt = input.confirmedAt,
            targetId = operation.ids.adjustmentId.value,
            confirmedAtText = input.confirmedAtText,
            createdAt = input.confirmedAt,
            createdAtText = input.confirmedAtText,
        )
        return accepted(
            listOf(
                Rg09ReturnedId.Confirmation(operation.ids.confirmationId),
                Rg09ReturnedId.Adjustment(operation.ids.adjustmentId),
                Rg09ReturnedId.Transaction(operation.ids.transactionId),
                Rg09ReturnedId.Version(operation.ids.versionId),
            ),
        )
    }

    private fun ingestImportedTransfer(operation: Rg09Operation.IngestImportedTransfer): Rg09ExecutionResult {
        val input = operation.input
        if (input.explicitConfirmation) {
            return rejected(Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_CONFIRMATION)
        }
        if (!isRg09ShanghaiTimestamp(input.actualOccurredAtText, input.actualOccurredAt) ||
            !isRg09ShanghaiTimestamp(input.observedAtText, input.observedAt)
        ) {
            return rejected(Rg09RejectionReason.INVALID_TIMESTAMP_TEXT, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        val target = catalogAccount(input.targetAccountId)
        val counter = catalogAccount(input.counterAccountId)
        if (target == null || counter == null) {
            return rejected(Rg09RejectionReason.UNKNOWN_ACCOUNT, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        }
        if (!target.ownedByUser || !target.realAccount || target.kind.name != "ASSET" ||
            !counter.ownedByUser || !counter.realAccount || counter.kind.name != "ASSET"
        ) {
            return rejected(Rg09RejectionReason.OWNED_REAL_ASSET_REQUIRED, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        }
        if (input.amount.minorUnits <= 0L || target.currency != input.amount.currency || counter.currency != input.amount.currency) {
            return rejected(Rg09RejectionReason.SAME_CURRENCY_REQUIRED, Rg09FieldPath.INPUT_AMOUNT)
        }
        if (
            sourceRecords.any { it.id == operation.ids.sourceId } ||
            evidence.any { it.id == operation.ids.evidenceId } ||
            candidates.any { it.id == operation.ids.candidateId }
        ) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_SOURCE)
        }
        sourceRecords += Rg09SourceRecord(
            id = operation.ids.sourceId,
            sourceType = "imported_transfer_candidate",
            observedAt = input.observedAt,
            accountId = input.targetAccountId,
            amount = input.amount,
            counterAccountId = input.counterAccountId,
            actualAt = input.actualOccurredAt,
            immutablePayloadDigest = input.immutablePayloadDigest,
            observedAtText = input.observedAtText,
            actualAtText = input.actualOccurredAtText,
        )
        evidence += Rg09Evidence(
            id = operation.ids.evidenceId,
            sourceRecordId = operation.ids.sourceId,
            evidenceType = "imported_real_transaction_candidate",
            observedAt = input.observedAt,
            observedAtText = input.observedAtText,
        )
        candidates += Rg09Candidate(
            id = operation.ids.candidateId,
            observationId = null,
            accountId = input.targetAccountId,
            replayedAmount = Money.ofMinor(0L, input.amount.currency),
            targetAmount = input.amount,
            delta = input.amount,
            targetObservedAt = input.actualOccurredAt,
            ledgerFingerprint = Rg09LedgerFingerprint.digest(formalTransactions, input.actualOccurredAt),
            status = "pending_confirmation",
            targetObservedAtText = input.actualOccurredAtText,
            sourceRecordId = operation.ids.sourceId,
            candidateType = "omitted_real_transaction_and_adjustment_explanation",
            confidence = input.confidence,
        )
        return accepted(
            listOf(
                Rg09ReturnedId.SourceRecord(operation.ids.sourceId),
                Rg09ReturnedId.Evidence(operation.ids.evidenceId),
                Rg09ReturnedId.Candidate(operation.ids.candidateId),
            ),
        )
    }

    private fun rejectIncompleteImportedTransfer(
        operation: Rg09Operation.IncompleteImportedTransferConfirmation,
    ): Rg09ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_CONFIRMATION)
        }
        if (candidates.none { it.id == input.candidateId }) {
            return rejected(Rg09RejectionReason.CANDIDATE_NOT_FOUND, Rg09FieldPath.INPUT_CANDIDATE)
        }
        if (input.transactionId == null) {
            return rejected(Rg09RejectionReason.EXACT_TRANSACTION_REQUIRED, Rg09FieldPath.INPUT_TRANSACTION_REQUIRED)
        }
        if (input.targetAccountId == null) {
            return rejected(Rg09RejectionReason.EXACT_TARGET_ACCOUNT_REQUIRED, Rg09FieldPath.INPUT_TARGET_ACCOUNT_REQUIRED)
        }
        if (input.actualOccurredAt == null) {
            return rejected(Rg09RejectionReason.ACTUAL_TIME_REQUIRED, Rg09FieldPath.INPUT_ACTUAL_TIME_REQUIRED)
        }
        if (input.currency == null) {
            return rejected(Rg09RejectionReason.EXACT_CURRENCY_REQUIRED, Rg09FieldPath.INPUT_CURRENCY_REQUIRED)
        }
        if (input.explanationAmount == null) {
            return rejected(
                Rg09RejectionReason.EXPLICIT_EXPLANATION_ALLOCATION_REQUIRED,
                Rg09FieldPath.INPUT_EXPLANATION_REQUIRED,
            )
        }
        return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_CANDIDATE)
    }

    private fun confirmTransfer(
        operation: Rg09Operation.ConfirmRealTransfer,
        imported: Boolean = false,
    ): Rg09ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_CONFIRMATION)
        }
        if (
            !input.confirmsTargetAccount ||
            !input.confirmsCounterAccount ||
            !input.confirmsActualOccurredAt ||
            !input.confirmsCurrency ||
            !input.confirmsAmount ||
            input.confirmsExplanationAllocation
        ) {
            return rejected(Rg09RejectionReason.EXPLICIT_LINK_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_ALLOCATION_DIRECTION)
        }
        val matchingAdjustments = adjustments.filter { it.targetAccountId == input.targetAccountId }
        val adjustment = matchingAdjustments.singleOrNull()
            ?: return rejected(
                if (matchingAdjustments.isEmpty()) {
                    Rg09RejectionReason.ADJUSTMENT_NOT_FOUND
                } else {
                    Rg09RejectionReason.DOMAIN_REJECTED
                },
                Rg09FieldPath.INPUT_TARGET_ACCOUNT,
            )
        if (input.actualOccurredAt > adjustment.targetObservedAt) {
            return rejected(Rg09RejectionReason.EXPLANATION_AFTER_TARGET_TIME, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        if (input.amount.minorUnits <= 0L) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_AMOUNT)
        }
        val requiredDirection = if (adjustment.originalDelta.minorUnits > 0L) "increase" else "decrease"
        if (input.targetAccountDirection != requiredDirection) {
            return rejected(Rg09RejectionReason.EXPLANATION_DIRECTION_MISMATCH, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        }
        if (!isRg09ShanghaiTimestamp(input.actualOccurredAtText, input.actualOccurredAt)) {
            return rejected(Rg09RejectionReason.INVALID_TIMESTAMP_TEXT, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        val result = createOwnAssetPrincipalTransfer(
            catalog,
            OwnAssetPrincipalTransferCommand(
                ledgerId = operation.ledgerId,
                sourceAccountId = if (requiredDirection == "increase") input.counterAccountId else input.targetAccountId,
                destinationAccountId = if (requiredDirection == "increase") input.targetAccountId else input.counterAccountId,
                amount = input.amount,
                times = TransactionTimes.collapsed(input.actualOccurredAt),
            ),
            OwnAssetPrincipalTransferIds(
                transactionId = operation.ids.transactionId,
                versionId = operation.ids.versionId,
                postingSetId = operation.ids.postingSetId,
                sourcePostingId = operation.ids.sourcePostingId,
                destinationPostingId = operation.ids.destinationPostingId,
            ),
        )
        val transfer = when (result) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        }
        val transferRecord = Rg09FormalTransactionRecord(
            formalTransaction = transfer.formalTransaction,
            createdAt = input.confirmedAt,
            sourceRecordId = input.sourceId ?: operation.ids.sourceRecordId,
            createdAtText = input.confirmedAtText,
            effectiveAtText = input.actualOccurredAtText,
            statisticsAtText = input.actualOccurredAtText,
        )
        val importedSource = input.sourceId?.let { sourceId ->
            sourceRecords.firstOrNull { it.id == sourceId }
        }
        if (input.sourceId != null && importedSource == null) {
            return rejected(Rg09RejectionReason.SOURCE_NOT_FOUND, Rg09FieldPath.INPUT_SOURCE)
        }
        if (importedSource != null && (
            importedSource.sourceType != "imported_transfer_candidate" ||
                importedSource.accountId != input.targetAccountId ||
                importedSource.counterAccountId != input.counterAccountId ||
                importedSource.amount != input.amount ||
                importedSource.actualAt != input.actualOccurredAt
            )
        ) {
            return rejected(Rg09RejectionReason.EXACT_TRANSACTION_REQUIRED, Rg09FieldPath.INPUT_SOURCE)
        }
        val importedCandidate = candidates.firstOrNull { candidate ->
            (input.sourceId != null && candidate.sourceRecordId == input.sourceId) ||
                (input.candidateId != null && candidate.id == input.candidateId)
        }
        if (importedCandidate != null && importedCandidate.status != "pending_confirmation") {
            return rejected(Rg09RejectionReason.CANDIDATE_NOT_PENDING, Rg09FieldPath.INPUT_SOURCE)
        }
        if (
            confirmations.any { it.id == operation.ids.confirmationId } ||
            (importedSource == null && sourceRecords.any { it.id == operation.ids.sourceRecordId }) ||
            !canAppendFormalTransaction(transferRecord)
        ) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_AMOUNT)
        }
        formalTransactions += transferRecord
        if (importedSource == null) {
            postingReconciliation[operation.ids.sourcePostingId.value] = "pending_evidence"
            postingReconciliation[operation.ids.destinationPostingId.value] = "pending_evidence"
        }
        if (importedSource == null) {
            sourceRecords += Rg09SourceRecord(
                id = operation.ids.sourceRecordId,
                sourceType = "manual_transaction_confirmation",
                observedAt = input.discoveredAt,
                accountId = input.targetAccountId,
                amount = input.amount,
                counterAccountId = input.counterAccountId,
                actualAt = input.actualOccurredAt,
                immutablePayloadDigest = input.immutablePayloadDigest,
                observedAtText = input.discoveredAtText,
                actualAtText = input.actualOccurredAtText,
            )
        }
        confirmations += Rg09Confirmation(
            id = operation.ids.confirmationId,
            requestId = input.requestId,
            role = "real_transfer_confirmation",
            confirmedAt = input.confirmedAt,
            targetId = operation.ids.transactionId.value,
            confirmedAtText = input.confirmedAtText,
            createdAt = input.confirmedAt,
            createdAtText = input.confirmedAtText,
        )
        return accepted(
            listOf(
                Rg09ReturnedId.Confirmation(operation.ids.confirmationId),
                Rg09ReturnedId.Transaction(operation.ids.transactionId),
                Rg09ReturnedId.Version(operation.ids.versionId),
            ),
        )
    }

    private fun confirmExplanation(operation: Rg09Operation.ConfirmExplanationAllocation): Rg09ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_CONFIRMATION)
        }
        if (
            !input.confirmsTargetAccount ||
            !input.confirmsActualOccurredAt ||
            !input.confirmsRealTransactionAmount ||
            !input.confirmsCurrency ||
            !input.confirmsTargetObservedAt ||
            !input.confirmsAllocationDirection ||
            !input.confirmsExplanationAmount
        ) {
            return rejected(Rg09RejectionReason.EXPLICIT_LINK_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_ALLOCATION_DIRECTION)
        }
        val adjustmentIndex = adjustments.indexOfFirst { it.id == input.adjustmentId }
        if (adjustmentIndex < 0) {
            return rejected(Rg09RejectionReason.ADJUSTMENT_NOT_FOUND, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        }
        val adjustment = adjustments[adjustmentIndex]
        val transfer = formalTransactions.firstOrNull { it.formalTransaction.transaction.id == input.transactionId }
            ?: return rejected(Rg09RejectionReason.REAL_TRANSACTION_NOT_FOUND, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        if (transfer.formalTransaction.transaction.kind != TransactionKind.ACCOUNT_TRANSFER) {
            return rejected(Rg09RejectionReason.REAL_TRANSFER_REQUIRED, Rg09FieldPath.INPUT_TRANSACTION)
        }
        if (
            confirmations.none {
                it.role == "real_transfer_confirmation" &&
                    it.targetId == input.transactionId.value
            }
        ) {
            return rejected(Rg09RejectionReason.REAL_TRANSACTION_NOT_CONFIRMED, Rg09FieldPath.INPUT_TRANSACTION)
        }
        if (currentEffectiveAt(transfer.formalTransaction) != input.actualOccurredAt) {
            return rejected(Rg09RejectionReason.REAL_TRANSACTION_TIME_MISMATCH, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        if (adjustment.targetAccountId != input.targetAccountId) {
            return rejected(Rg09RejectionReason.SAME_TARGET_ACCOUNT_REQUIRED, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        }
        if (input.targetObservedAt != adjustment.targetObservedAt || input.actualOccurredAt > adjustment.targetObservedAt) {
            return rejected(Rg09RejectionReason.EXPLANATION_AFTER_TARGET_TIME, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        val targetPosting = transfer.formalTransaction.currentPostings().firstOrNull { it.accountId == input.targetAccountId }
            ?: return rejected(Rg09RejectionReason.EXPLANATION_DIRECTION_MISMATCH, Rg09FieldPath.INPUT_TARGET_ACCOUNT)
        val targetDirection = when {
            targetPosting.amount.minorUnits > 0L -> "increase"
            targetPosting.amount.minorUnits < 0L -> "decrease"
            else -> null
        }
        val targetTransactionMinor = absMinor(targetPosting.amount)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_AMOUNT)
        val requiredDirection = if (adjustment.originalDelta.minorUnits > 0L) "increase" else "decrease"
        if (
            targetDirection != requiredDirection ||
            targetPosting.amount.currency != adjustment.currency ||
            targetTransactionMinor != input.realTransactionAmount.minorUnits ||
            input.realTransactionAmount.currency != adjustment.currency
        ) {
            return rejected(Rg09RejectionReason.EXPLANATION_DIRECTION_MISMATCH, Rg09FieldPath.INPUT_AMOUNT)
        }
        val amount = input.explanationAmount
        if (amount.currency != adjustment.currency || amount.minorUnits <= 0L) {
            return rejected(Rg09RejectionReason.EXPLANATION_DIRECTION_MISMATCH, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        }
        var alreadyExplainedMinor = 0L
        allocations
            .filter { it.realTransactionId == input.transactionId }
            .forEach { allocation ->
                alreadyExplainedMinor = addExact(alreadyExplainedMinor, allocation.amount.minorUnits)
                    ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
            }
        val remainingRealTransactionMinor = subtractExact(targetTransactionMinor, alreadyExplainedMinor)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        if (amount.minorUnits > remainingRealTransactionMinor) {
            return rejected(Rg09RejectionReason.EXPLANATION_EXCEEDS_REAL_TRANSACTION, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        }
        if (amount.minorUnits > adjustment.remainingAmount.minorUnits) {
            return rejected(Rg09RejectionReason.ALLOCATION_EXCEEDS_REMAINING, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        }
        val sourceRecord = transfer.formalTransaction.transaction.id.value.let { transactionId ->
            formalTransactions.firstOrNull {
                it.formalTransaction.transaction.id.value == transactionId
            }?.sourceRecordId?.let { sourceId -> sourceRecords.firstOrNull { it.id == sourceId } }
        }
        val discoveredAt = input.discoveredAt ?: sourceRecord?.observedAt ?: input.confirmedAt
        val discoveredAtText = input.discoveredAtText ?: sourceRecord?.observedAtText ?: input.confirmedAtText
        val explainedMinor = addExact(adjustment.explainedAmount.minorUnits, amount.minorUnits)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        val remainingMinor = subtractExact(adjustment.remainingAmount.minorUnits, amount.minorUnits)
            ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        val state = when {
            remainingMinor == 0L -> "fully_explained"
            explainedMinor == 0L -> "open"
            else -> "partially_explained"
        }
        val signedReversal = if (adjustment.originalDelta.minorUnits > 0L) {
            negateExact(amount.minorUnits)
        } else {
            amount.minorUnits
        } ?: return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        val reversal = createBalanceAdjustment(
            catalog,
            BalanceAdjustmentCommand(
                ledgerId = operation.ledgerId,
                targetAccountId = adjustment.targetAccountId,
                adjustmentEquityAccountId = adjustment.equityAccountId,
                delta = Money.ofMinor(signedReversal, adjustment.currency),
                times = TransactionTimes.collapsed(adjustment.targetObservedAt),
                kind = TransactionKind.BALANCE_ADJUSTMENT_REVERSAL,
            ),
            BalanceAdjustmentIds(
                transactionId = operation.ids.reversalTransactionId,
                versionId = operation.ids.reversalVersionId,
                postingSetId = operation.ids.reversalPostingSetId,
                targetPostingId = operation.ids.reversalTargetPostingId,
                equityPostingId = operation.ids.reversalEquityPostingId,
            ),
        )
        val reversalTransaction = when (reversal) {
            is DomainResult.Success -> reversal.value
            is DomainResult.Failure -> return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        }
        val reversalRecord = Rg09FormalTransactionRecord(
            reversalTransaction.formalTransaction,
            input.confirmedAt,
            createdAtText = input.confirmedAtText,
            effectiveAtText = adjustment.targetObservedAtText,
            statisticsAtText = adjustment.targetObservedAtText,
        )
        if (
            confirmations.any { it.id == operation.ids.confirmationId } ||
            allocations.any { it.id == operation.ids.allocationId } ||
            adjustments.any { it.history.any { history -> history.id == operation.ids.historyId } } ||
            auditLinks.any {
                it.id == operation.ids.adjustmentAuditLinkId ||
                    it.id == operation.ids.explanationAuditLinkId ||
                    it.id == operation.ids.reversalAuditLinkId
            } ||
            !canAppendFormalTransaction(reversalRecord)
        ) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EXPLANATION_AMOUNT)
        }
        val importedCandidateIndex = transfer.sourceRecordId?.let { sourceId ->
            candidates.indexOfFirst { candidate ->
                candidate.sourceRecordId == sourceId &&
                    candidate.candidateType == "omitted_real_transaction_and_adjustment_explanation"
            }
        } ?: -1
        if (importedCandidateIndex >= 0 && candidates[importedCandidateIndex].status != "pending_confirmation") {
            return rejected(Rg09RejectionReason.CANDIDATE_NOT_PENDING, Rg09FieldPath.INPUT_TRANSACTION)
        }
        if (sourceRecord?.sourceType == "manual_transaction_confirmation") {
            transfer.formalTransaction.currentPostings().forEach { posting ->
                postingReconciliation.putIfAbsent(posting.id.value, "pending_evidence")
            }
        }
        formalTransactions += reversalRecord
        val allocation = Rg09Allocation(
            id = operation.ids.allocationId,
            adjustmentId = adjustment.id,
            targetAccountId = adjustment.targetAccountId,
            amount = amount,
            realTransactionId = input.transactionId,
            reversalTransactionId = operation.ids.reversalTransactionId,
            confirmedAt = input.confirmedAt,
            discoveredAt = discoveredAt,
            discoveredAtText = discoveredAtText,
            confirmedAtText = input.confirmedAtText,
            createdAt = input.confirmedAt,
            createdAtText = input.confirmedAtText,
        )
        allocations += allocation
        adjustments[adjustmentIndex] = adjustment.copy(
            explainedAmount = Money.ofMinor(explainedMinor, adjustment.currency),
            remainingAmount = Money.ofMinor(remainingMinor, adjustment.currency),
            state = state,
            history = adjustment.history + Rg09AdjustmentHistory(
                id = operation.ids.historyId,
                state = state,
                occurredAt = input.confirmedAt,
                allocationId = operation.ids.allocationId,
                remainingAmount = Money.ofMinor(remainingMinor, adjustment.currency),
                occurredAtText = input.confirmedAtText,
                createdAt = input.confirmedAt,
                createdAtText = input.confirmedAtText,
            ),
        )
        if (importedCandidateIndex >= 0) {
            val importedCandidate = candidates[importedCandidateIndex]
            candidates[importedCandidateIndex] = importedCandidate.copy(
                status = "confirmed",
                adjustmentId = adjustment.id,
                confirmationRequestId = input.requestId,
            )
        }
        confirmations += Rg09Confirmation(
            id = operation.ids.confirmationId,
            requestId = input.requestId,
            role = "explanation_allocation_confirmation",
            confirmedAt = input.confirmedAt,
            targetId = operation.ids.allocationId.value,
            confirmedAtText = input.confirmedAtText,
            createdAt = input.confirmedAt,
            createdAtText = input.confirmedAtText,
        )
        auditLinks += listOf(
            Rg09AuditLink(operation.ids.adjustmentAuditLinkId, allocation.id, "adjustment_transaction", adjustment.transactionId.value, input.confirmedAt, input.confirmedAtText),
            Rg09AuditLink(operation.ids.explanationAuditLinkId, allocation.id, "explanation_transaction", input.transactionId.value, input.confirmedAt, input.confirmedAtText),
            Rg09AuditLink(operation.ids.reversalAuditLinkId, allocation.id, "allocation_reversal", operation.ids.reversalTransactionId.value, input.confirmedAt, input.confirmedAtText),
        )
        return accepted(
            listOf(
                Rg09ReturnedId.Confirmation(operation.ids.confirmationId),
                Rg09ReturnedId.Allocation(operation.ids.allocationId),
                Rg09ReturnedId.Transaction(operation.ids.reversalTransactionId),
                Rg09ReturnedId.Version(operation.ids.reversalVersionId),
            ),
        )
    }

    private fun linkRealPostingEvidence(operation: Rg09Operation.LinkRealPostingEvidence): Rg09ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg09RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg09FieldPath.INPUT_CONFIRMATION)
        }
        if (!isRg09ShanghaiTimestamp(input.observedAtText, input.observedAt)) {
            return rejected(Rg09RejectionReason.LEDGER_TIMEZONE_REQUIRED, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        if (sourceRecords.any { it.id == input.sourceId } || evidence.any { it.id == input.evidenceId } || evidenceLinks.any { it.id == operation.ids.evidenceLinkId }) {
            return rejected(Rg09RejectionReason.DOMAIN_REJECTED, Rg09FieldPath.INPUT_EVIDENCE)
        }
        val posting = formalTransactions.asSequence()
            .flatMap { it.formalTransaction.currentPostings().asSequence() }
            .firstOrNull { it.id == input.targetPostingId }
            ?: return rejected(Rg09RejectionReason.POSTING_NOT_FOUND, Rg09FieldPath.INPUT_TARGET_POSTING)
        val postingTransaction = formalTransactions.firstOrNull { record ->
            record.formalTransaction.currentPostings().any { it.id == input.targetPostingId }
        } ?: return rejected(Rg09RejectionReason.POSTING_NOT_FOUND, Rg09FieldPath.INPUT_TARGET_POSTING)
        if (postingReconciliation[input.targetPostingId.value] == null) {
            return rejected(Rg09RejectionReason.POSTING_OWNERSHIP_REQUIRED, Rg09FieldPath.INPUT_TARGET_POSTING)
        }
        if (posting.accountId != input.accountId || posting.amount != input.amount) {
            return rejected(Rg09RejectionReason.POSTING_AMOUNT_MISMATCH, Rg09FieldPath.INPUT_POSTING_AMOUNT)
        }
        val expectedSide = if (posting.amount.minorUnits > 0L) "increase" else "decrease"
        if (input.postingSide != expectedSide) {
            return rejected(Rg09RejectionReason.POSTING_SIDE_MISMATCH, Rg09FieldPath.INPUT_POSTING_SIDE)
        }
        val account = catalogAccount(posting.accountId)
            ?: return rejected(Rg09RejectionReason.UNKNOWN_ACCOUNT, Rg09FieldPath.INPUT_ACCOUNT)
        if (!account.ownedByUser || !account.realAccount || account.kind.name != "ASSET") {
            return rejected(Rg09RejectionReason.OWNED_REAL_ASSET_REQUIRED, Rg09FieldPath.INPUT_ACCOUNT)
        }
        if (!isRg09ShanghaiTimestamp(input.bookingAtText, input.bookingAt)) {
            return rejected(Rg09RejectionReason.INVALID_TIMESTAMP_TEXT, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        if (currentEffectiveAt(postingTransaction.formalTransaction) != input.bookingAt) {
            return rejected(Rg09RejectionReason.REAL_TRANSACTION_TIME_MISMATCH, Rg09FieldPath.INPUT_ACTUAL_TIME)
        }
        sourceRecords += Rg09SourceRecord(
            id = input.sourceId,
            sourceType = "account_statement",
            observedAt = input.observedAt,
            accountId = input.accountId,
            amount = input.amount,
            bookingAt = input.bookingAt,
            immutablePayloadDigest = input.immutablePayloadDigest,
            observedAtText = input.observedAtText,
            bookingAtText = input.bookingAtText,
        )
        evidence += Rg09Evidence(
            id = input.evidenceId,
            sourceRecordId = input.sourceId,
            evidenceType = "real_account_posting",
            observedAt = input.observedAt,
            observedAtText = input.observedAtText,
        )
        evidenceLinks += Rg09EvidenceLink(
            id = operation.ids.evidenceLinkId,
            sourceRecordId = input.sourceId,
            evidenceId = input.evidenceId,
            role = "real_account_posting",
            targetId = input.targetPostingId.value,
            status = "matched",
        )
        postingReconciliation[input.targetPostingId.value] = "matched"
        return accepted(
            listOf(
                Rg09ReturnedId.SourceRecord(input.sourceId),
                Rg09ReturnedId.Evidence(input.evidenceId),
                Rg09ReturnedId.EvidenceLink(operation.ids.evidenceLinkId),
            ),
        )
    }

    private fun rejectInvalidInput(operation: Rg09Operation.InvalidInput): Rg09ExecutionResult {
        val (reason, fieldPath) = when (operation.input.predicate) {
            Rg09InvalidPredicate.EXACT_DECIMAL ->
                Rg09RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg09FieldPath.ATTEMPTED_TARGET_AMOUNT
            Rg09InvalidPredicate.TIMEZONE_AWARE ->
                Rg09RejectionReason.TIMEZONE_AWARE_TARGET_TIME_REQUIRED to Rg09FieldPath.ATTEMPTED_TARGET_TIME
            Rg09InvalidPredicate.LEDGER_TIMEZONE ->
                Rg09RejectionReason.LEDGER_TIMEZONE_REQUIRED to Rg09FieldPath.ATTEMPTED_TARGET_TIME
            Rg09InvalidPredicate.KNOWN_ACCOUNT ->
                Rg09RejectionReason.UNKNOWN_ACCOUNT to Rg09FieldPath.ATTEMPTED_ACCOUNT
            Rg09InvalidPredicate.OWNED_REAL_ASSET ->
                Rg09RejectionReason.OWNED_REAL_ASSET_REQUIRED to Rg09FieldPath.ATTEMPTED_ACCOUNT
            Rg09InvalidPredicate.CURRENCY_CNY ->
                Rg09RejectionReason.SAME_CURRENCY_REQUIRED to Rg09FieldPath.ATTEMPTED_CURRENCY
            Rg09InvalidPredicate.DEDICATED_EQUITY ->
                Rg09RejectionReason.DEDICATED_ADJUSTMENT_EQUITY_REQUIRED to Rg09FieldPath.ATTEMPTED_EQUITY_ACCOUNT
            Rg09InvalidPredicate.SAME_DIRECTION ->
                Rg09RejectionReason.EXPLANATION_DIRECTION_MISMATCH to Rg09FieldPath.ATTEMPTED_DIRECTION
            Rg09InvalidPredicate.SAME_TARGET_ACCOUNT ->
                Rg09RejectionReason.SAME_TARGET_ACCOUNT_REQUIRED to Rg09FieldPath.ATTEMPTED_ACCOUNT
            Rg09InvalidPredicate.BEFORE_TARGET ->
                Rg09RejectionReason.EXPLANATION_AFTER_TARGET_TIME to Rg09FieldPath.ATTEMPTED_ACTUAL_TIME
            Rg09InvalidPredicate.REMAINING_CAP ->
                Rg09RejectionReason.ALLOCATION_EXCEEDS_REMAINING to Rg09FieldPath.ATTEMPTED_REQUESTED_AMOUNT
            Rg09InvalidPredicate.EXPLICIT_LINK ->
                Rg09RejectionReason.EXPLICIT_LINK_CONFIRMATION_REQUIRED to Rg09FieldPath.ATTEMPTED_CONFIRMATION
            Rg09InvalidPredicate.IDEMPOTENCY_CONFLICT ->
                Rg09RejectionReason.IDENTITY_CONFLICT to Rg09FieldPath.ATTEMPTED_REQUEST_ID
        }
        return rejected(reason, fieldPath)
    }

    private fun catalogAccount(id: AccountId) = catalog.accounts.firstOrNull { it.id == id }

    private fun adjustmentEquityAccountId(): AccountId =
        catalog.accounts.firstOrNull { it.systemRole == "balance_adjustments" }?.id
            ?: AccountId("equity-balance-adjustments")

    private fun replayAmount(accountId: AccountId, asOf: Instant?): Money? {
        val account = catalogAccount(accountId) ?: return null
        var total = 0L
        formalTransactions
            .filter { it.formalTransaction.transaction.ledgerId == account.ledgerId }
            .filter { asOf == null || currentEffectiveAt(it.formalTransaction) <= asOf }
            .forEach { record ->
                record.formalTransaction.currentPostings()
                    .filter { it.accountId == accountId }
                    .forEach { posting ->
                        if (posting.amount.currency != account.currency) return null
                        total = addExact(total, posting.amount.minorUnits) ?: return null
                    }
            }
        return Money.ofMinor(total, account.currency)
    }

    private fun canAppendFormalTransaction(record: Rg09FormalTransactionRecord): Boolean {
        if (!catalogCompatible(record.formalTransaction) || formalIdCollision(record.formalTransaction)) {
            return false
        }
        record.formalTransaction.currentPostings()
            .groupBy { it.accountId }
            .forEach { (accountId, postings) ->
                var total = replayAmount(accountId, null)?.minorUnits ?: return false
                postings.forEach { posting ->
                    total = addExact(total, posting.amount.minorUnits) ?: return false
                }
            }

        val period = currentEffectiveAt(record.formalTransaction).toString().substring(0, 7)
        val currentPeriod = reports()[period] ?: Rg09Report()
        val cumulative = reports().getValue("cumulative")
        return when (record.formalTransaction.transaction.kind) {
            TransactionKind.BALANCE_ADJUSTMENT,
            TransactionKind.BALANCE_ADJUSTMENT_REVERSAL -> {
                val amount = record.formalTransaction.currentPostings().first().amount.minorUnits
                addExact(currentPeriod.balanceAdjustmentNetWorthChangeMinor, amount) != null &&
                    addExact(currentPeriod.netWorthChangeMinor, amount) != null &&
                    addExact(cumulative.balanceAdjustmentNetWorthChangeMinor, amount) != null &&
                    addExact(cumulative.netWorthChangeMinor, amount) != null
            }
            TransactionKind.ACCOUNT_TRANSFER -> {
                val amount = record.formalTransaction.currentPostings().maxOf { it.amount.minorUnits }
                addExact(currentPeriod.internalTransferMinor, amount) != null &&
                    addExact(cumulative.internalTransferMinor, amount) != null
            }
            else -> true
        }
    }

    private fun replayBalances(asOf: Instant?): Map<AccountId, Money> = buildMap {
        catalog.accounts.forEach { account ->
            var total = 0L
            formalTransactions
                .filter { it.formalTransaction.transaction.ledgerId == account.ledgerId }
                .filter { asOf == null || currentEffectiveAt(it.formalTransaction) <= asOf }
                .forEach { record ->
                    record.formalTransaction.currentPostings()
                        .filter { it.accountId == account.id }
                        .forEach { posting ->
                            check(posting.amount.currency == account.currency) { "RG-09 posting currency mismatch" }
                            total = addExact(total, posting.amount.minorUnits) ?: error("RG-09 balance overflow")
                        }
                }
            put(account.id, Money.ofMinor(total, account.currency))
        }
    }

    private fun reports(): Map<String, Rg09Report> {
        val periods = mutableMapOf<String, Rg09Report>()
        formalTransactions.forEach { record ->
            val period = currentEffectiveAt(record.formalTransaction).toString().substring(0, 7)
            val current = periods[period] ?: Rg09Report()
            val next = when (record.formalTransaction.transaction.kind) {
                TransactionKind.BALANCE_ADJUSTMENT,
                TransactionKind.BALANCE_ADJUSTMENT_REVERSAL -> {
                    val amount = record.formalTransaction.currentPostings().first().amount.minorUnits
                    current.copy(
                        balanceAdjustmentNetWorthChangeMinor = addExact(current.balanceAdjustmentNetWorthChangeMinor, amount)!!,
                        netWorthChangeMinor = addExact(current.netWorthChangeMinor, amount)!!,
                    )
                }
                TransactionKind.ACCOUNT_TRANSFER -> {
                    val amount = record.formalTransaction.currentPostings().maxOf { it.amount.minorUnits }
                    current.copy(internalTransferMinor = addExact(current.internalTransferMinor, amount)!!)
                }
                else -> null
            }
            if (next != null) periods[period] = next
        }
        val cumulative = periods.values.fold(Rg09Report()) { total, item ->
            Rg09Report(
                ordinaryIncomeMinor = addExact(total.ordinaryIncomeMinor, item.ordinaryIncomeMinor)!!,
                ordinaryExpenseMinor = addExact(total.ordinaryExpenseMinor, item.ordinaryExpenseMinor)!!,
                consumptionMinor = addExact(total.consumptionMinor, item.consumptionMinor)!!,
                budgetEffectMinor = addExact(total.budgetEffectMinor, item.budgetEffectMinor)!!,
                categoryEffectMinor = addExact(total.categoryEffectMinor, item.categoryEffectMinor)!!,
                cashInflowMinor = addExact(total.cashInflowMinor, item.cashInflowMinor)!!,
                cashOutflowMinor = addExact(total.cashOutflowMinor, item.cashOutflowMinor)!!,
                internalTransferMinor = addExact(total.internalTransferMinor, item.internalTransferMinor)!!,
                balanceAdjustmentNetWorthChangeMinor = addExact(total.balanceAdjustmentNetWorthChangeMinor, item.balanceAdjustmentNetWorthChangeMinor)!!,
                netWorthChangeMinor = addExact(total.netWorthChangeMinor, item.netWorthChangeMinor)!!,
            )
        }
        return periods + ("cumulative" to cumulative)
    }

    private fun reconciliation(): Map<String, String> = buildMap {
        observations.forEach { observation ->
            val candidate = candidates.firstOrNull { it.observationId == observation.id }
            val adjustment = candidate?.adjustmentId?.let { id -> adjustments.firstOrNull { it.id == id } }
            val replayed = replayAmount(observation.accountId, observation.targetObservedAt)
            val remaining = when {
                adjustment != null -> adjustment.remainingAmount.minorUnits
                candidate != null && replayed != null -> absMinor(
                    Money.ofMinor(
                        subtractExact(candidate.targetAmount.minorUnits, replayed.minorUnits) ?: 0L,
                        candidate.targetAmount.currency,
                    ),
                )
                else -> 0L
            } ?: error("RG-09 remaining adjustment overflow")
            val hasUnallocatedRealTransfer = adjustment?.let { currentAdjustment ->
                val requiredDirectionPositive = currentAdjustment.originalDelta.minorUnits > 0L
                formalTransactions.any { record ->
                    val transaction = record.formalTransaction.transaction
                    transaction.kind == TransactionKind.ACCOUNT_TRANSFER &&
                        currentEffectiveAt(record.formalTransaction) <= currentAdjustment.targetObservedAt &&
                        allocations.none { it.realTransactionId == transaction.id } &&
                        record.formalTransaction.currentPostings().any { posting ->
                            posting.accountId == currentAdjustment.targetAccountId &&
                                posting.amount.currency == currentAdjustment.currency &&
                                posting.amount.minorUnits != 0L &&
                                (posting.amount.minorUnits > 0L) == requiredDirectionPositive
                        }
                }
            } ?: false
            val requiredPostingIds = adjustment?.let { currentAdjustment ->
                allocations.asSequence()
                    .filter { it.adjustmentId == currentAdjustment.id }
                    .flatMap { allocation ->
                        formalTransactions.asSequence()
                            .filter { it.formalTransaction.transaction.id == allocation.realTransactionId }
                            .flatMap { it.formalTransaction.currentPostings().asSequence() }
                    }
                    .mapTo(mutableSetOf()) { it.id.value }
            } ?: emptySet()
            val fullyReconciled = requiredPostingIds.isNotEmpty() &&
                requiredPostingIds.all { postingReconciliation[it] == "matched" }
            put(
                observation.id.value,
                when {
                    candidate == null && replayed == observation.targetAmount -> "balance_agreement_no_adjustment"
                    candidate?.status == "pending_confirmation" && replayed != candidate.replayedAmount -> "stale_preview"
                    candidate?.status == "pending_confirmation" -> "difference_pending_confirmation"
                    adjustment == null -> "difference_pending_confirmation"
                    hasUnallocatedRealTransfer ->
                        "difference_pending_explanation_confirmation"
                    adjustment.remainingAmount.minorUnits != 0L -> "balanced_with_unexplained_adjustment"
                    fullyReconciled -> "fully_reconciled"
                    else -> "evidence_incomplete"
                },
            )
            put("remaining_adjustment", formatMinor(remaining, observation.targetAmount.currency))
        }
        put("full_reconciliation_requirement", "remaining_adjustment_zero_and_actual_postings_evidenced")
    }

    private fun currentEffectiveAt(formal: FormalTransaction): Instant = formal.versions.first { it.id == formal.transaction.currentVersionId }.times.effectiveAt

    private fun canonicalInput(operation: Rg09Operation): String = when (operation) {
        is Rg09Operation.PreviewTargetBalance -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.accountId.value,
            canonicalMoney(operation.input.targetAmount),
            operation.input.targetObservedAt.toString(),
            operation.input.savedAt.toString(),
            operation.input.targetObservedAtText,
            operation.input.savedAtText,
            canonicalCurrency(operation.input.currency),
            operation.input.explicitConfirmation.toString(),
            operation.input.immutablePayloadDigest,
            operation.input.ledgerFingerprint,
            operation.ids.observationId.value,
            operation.ids.sourceRecordId.value,
            operation.ids.evidenceId.value,
            operation.ids.evidenceLinkId.value,
            operation.ids.candidateId?.value,
        )
        is Rg09Operation.ConfirmBalanceAdjustment -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.candidateId.value,
            operation.input.ledgerFingerprint,
            operation.input.explicitConfirmation.toString(),
            operation.input.confirmedAt.toString(),
            operation.input.confirmedAtText,
            operation.ids.confirmationId.value,
            operation.ids.adjustmentId.value,
            operation.ids.transactionId.value,
            operation.ids.versionId.value,
            operation.ids.postingSetId.value,
            operation.ids.targetPostingId.value,
            operation.ids.equityPostingId.value,
            operation.ids.historyId,
        )
        is Rg09Operation.ConfirmRealTransfer -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.targetAccountId.value,
            operation.input.counterAccountId.value,
            canonicalMoney(operation.input.amount),
            operation.input.actualOccurredAt.toString(),
            operation.input.discoveredAt.toString(),
            operation.input.confirmedAt.toString(),
            operation.input.immutablePayloadDigest,
            operation.input.explicitConfirmation.toString(),
            operation.input.confirmsTargetAccount.toString(),
            operation.input.confirmsCounterAccount.toString(),
            operation.input.confirmsActualOccurredAt.toString(),
            operation.input.confirmsCurrency.toString(),
            operation.input.confirmsAmount.toString(),
            operation.input.confirmsExplanationAllocation.toString(),
            operation.input.actualOccurredAtText,
            operation.input.discoveredAtText,
            operation.input.confirmedAtText,
            operation.input.sourceId?.value,
            operation.input.candidateId?.value,
            operation.ids.confirmationId.value,
            operation.ids.sourceRecordId.value,
            operation.ids.transactionId.value,
            operation.ids.versionId.value,
            operation.ids.postingSetId.value,
            operation.ids.sourcePostingId.value,
            operation.ids.destinationPostingId.value,
        )
        is Rg09Operation.IngestImportedTransfer -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.sourceId.value,
            operation.input.evidenceId.value,
            operation.input.candidateId.value,
            operation.input.targetAccountId.value,
            operation.input.counterAccountId.value,
            canonicalMoney(operation.input.amount),
            operation.input.actualOccurredAt.toString(),
            operation.input.observedAt.toString(),
            operation.input.immutablePayloadDigest,
            operation.input.confidence,
            operation.input.explicitConfirmation.toString(),
            operation.input.actualOccurredAtText,
            operation.input.observedAtText,
            operation.ids.sourceId.value,
            operation.ids.evidenceId.value,
            operation.ids.candidateId.value,
        )
        is Rg09Operation.ConfirmImportedTransfer -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.targetAccountId.value,
            operation.input.counterAccountId.value,
            operation.input.targetAccountDirection,
            canonicalMoney(operation.input.amount),
            operation.input.actualOccurredAt.toString(),
            operation.input.discoveredAt.toString(),
            operation.input.confirmedAt.toString(),
            operation.input.immutablePayloadDigest,
            operation.input.explicitConfirmation.toString(),
            operation.input.confirmsTargetAccount.toString(),
            operation.input.confirmsCounterAccount.toString(),
            operation.input.confirmsActualOccurredAt.toString(),
            operation.input.confirmsCurrency.toString(),
            operation.input.confirmsAmount.toString(),
            operation.input.confirmsExplanationAllocation.toString(),
            operation.input.actualOccurredAtText,
            operation.input.discoveredAtText,
            operation.input.confirmedAtText,
            operation.input.sourceId?.value,
            operation.input.candidateId?.value,
            operation.ids.confirmationId.value,
            operation.ids.sourceRecordId.value,
            operation.ids.transactionId.value,
            operation.ids.versionId.value,
            operation.ids.postingSetId.value,
            operation.ids.sourcePostingId.value,
            operation.ids.destinationPostingId.value,
        )
        is Rg09Operation.IncompleteImportedTransferConfirmation -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.candidateId.value,
            operation.input.transactionId?.value,
            operation.input.targetAccountId?.value,
            operation.input.actualOccurredAt?.toString(),
            operation.input.currency?.let(::canonicalCurrency),
            operation.input.explanationAmount?.let(::canonicalMoney),
            operation.input.explicitConfirmation.toString(),
        )
        is Rg09Operation.ConfirmExplanationAllocation -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.adjustmentId.value,
            operation.input.transactionId.value,
            operation.input.targetAccountId.value,
            operation.input.actualOccurredAt.toString(),
            canonicalMoney(operation.input.realTransactionAmount),
            operation.input.targetObservedAt.toString(),
            canonicalMoney(operation.input.explanationAmount),
            operation.input.confirmedAt.toString(),
            operation.input.explicitConfirmation.toString(),
            operation.input.confirmsTargetAccount.toString(),
            operation.input.confirmsActualOccurredAt.toString(),
            operation.input.confirmsRealTransactionAmount.toString(),
            operation.input.confirmsCurrency.toString(),
            operation.input.confirmsTargetObservedAt.toString(),
            operation.input.confirmsAllocationDirection.toString(),
            operation.input.confirmsExplanationAmount.toString(),
            operation.input.actualOccurredAtText,
            operation.input.targetObservedAtText,
            operation.input.confirmedAtText,
            operation.input.discoveredAt?.toString(),
            operation.input.discoveredAtText,
            operation.ids.confirmationId.value,
            operation.ids.allocationId.value,
            operation.ids.reversalTransactionId.value,
            operation.ids.reversalVersionId.value,
            operation.ids.reversalPostingSetId.value,
            operation.ids.reversalTargetPostingId.value,
            operation.ids.reversalEquityPostingId.value,
            operation.ids.adjustmentAuditLinkId.value,
            operation.ids.explanationAuditLinkId.value,
            operation.ids.reversalAuditLinkId.value,
            operation.ids.historyId,
        )
        is Rg09Operation.LinkRealPostingEvidence -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.sourceId.value,
            operation.input.evidenceId.value,
            operation.input.targetPostingId.value,
            operation.input.accountId.value,
            canonicalMoney(operation.input.amount),
            operation.input.postingSide,
            operation.input.observedAt.toString(),
            operation.input.bookingAt.toString(),
            operation.input.observedAtText,
            operation.input.bookingAtText,
            operation.input.immutablePayloadDigest,
            operation.input.explicitConfirmation.toString(),
            operation.ids.evidenceLinkId.value,
        )
        is Rg09Operation.InvalidInput -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.predicate.name,
            operation.input.attemptedInput.entries.sortedBy { it.key }.joinToString("|") { (key, value) ->
                "$key=${value ?: "<null>"}"
            },
        )
    }

    private fun canonicalMoney(money: Money): String =
        "${money.minorUnits}:${canonicalCurrency(money.currency)}"

    private fun canonicalCurrency(currency: CurrencyUnit): String =
        "${currency.code}:${currency.precision}"

    private fun canonicalFields(vararg values: String?): String = buildString {
        values.forEach { value ->
            if (value == null) {
                append("N;")
            } else {
                append("V").append(value.length).append(':').append(value).append(';')
            }
        }
    }

    private fun catalogCompatible(formal: FormalTransaction): Boolean =
        formal.transaction.ledgerId == catalog.accounts.firstOrNull()?.ledgerId &&
            formal.currentPostings().all { posting ->
                val account = catalogAccount(posting.accountId)
                account != null &&
                    account.ledgerId == formal.transaction.ledgerId &&
                    account.currency == posting.amount.currency
            }

    private fun formalIdCollision(formal: FormalTransaction): Boolean {
        val transactionIds = formalTransactions.mapTo(mutableSetOf()) { it.formalTransaction.transaction.id }
        val versionIds = formalTransactions.flatMapTo(mutableSetOf()) { record ->
            record.formalTransaction.versions.map { it.id }
        }
        val postingSetIds = formalTransactions.flatMapTo(mutableSetOf()) { record ->
            record.formalTransaction.postingSets.map { it.id }
        }
        val postingIds = formalTransactions.flatMapTo(mutableSetOf()) { record ->
            record.formalTransaction.postingSets.flatMap { postingSet -> postingSet.postings.map { it.id } }
        }
        return formal.transaction.id in transactionIds ||
            formal.versions.any { it.id in versionIds } ||
            formal.postingSets.any { it.id in postingSetIds } ||
            formal.currentPostings().any { it.id in postingIds }
    }

    private fun accepted(ids: List<Rg09ReturnedId>) = Rg09ExecutionResult.Accepted(ids)

    private fun rejected(reason: Rg09RejectionReason, fieldPath: Rg09FieldPath) =
        Rg09ExecutionResult.Rejected(reason, fieldPath)
}

private fun absMinor(money: Money): Long? = when (money.minorUnits) {
    Long.MIN_VALUE -> null
    else -> kotlin.math.abs(money.minorUnits)
}

private fun formatMinor(minorUnits: Long, currency: CurrencyUnit): String {
    val precision = currency.precision
    require(precision >= 0) { "RG-09 currency precision must not be negative" }
    if (precision == 0) return minorUnits.toString()
    val negative = minorUnits < 0L
    val magnitude = if (minorUnits == Long.MIN_VALUE) {
        "9223372036854775808"
    } else {
        kotlin.math.abs(minorUnits).toString()
    }
    val padded = magnitude.padStart(precision + 1, '0')
    val split = padded.length - precision
    return buildString {
        if (negative) append('-')
        append(padded.substring(0, split))
        append('.')
        append(padded.substring(split))
    }
}

private fun addExact(left: Long, right: Long): Long? {
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun subtractExact(left: Long, right: Long): Long? = addExact(left, negateExact(right) ?: return null)

private fun negateExact(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
