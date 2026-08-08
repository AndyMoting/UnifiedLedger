package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.StoredValueActivationBalancePostingRole
import com.unifiedledger.domain.StoredValueExpiryLossPostingRole
import com.unifiedledger.domain.StoredValueLot
import com.unifiedledger.domain.StoredValueLotHistory
import com.unifiedledger.domain.StoredValueLotId
import com.unifiedledger.domain.StoredValueRechargePostingRole
import com.unifiedledger.domain.StoredValueReconstruction
import com.unifiedledger.domain.StoredValueSpendPostingRole
import com.unifiedledger.domain.StoredValueViolation
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.createStoredValueRecharge
import com.unifiedledger.domain.createStoredValueSpend
import com.unifiedledger.domain.createStoredValueExpiryLoss
import com.unifiedledger.domain.createStoredValueActivationBalance
import com.unifiedledger.domain.createStoredValueReconstruction
import com.unifiedledger.domain.defaultLotOrder
import kotlin.time.Instant

data class Rg10CandidateId(val value: String)
data class Rg10SourceRecordId(val value: String)
data class Rg10EvidenceId(val value: String)
data class Rg10EvidenceLinkId(val value: String)
data class Rg10ConfirmationId(val value: String)
data class Rg10AllocationId(val value: String)
data class Rg10ConsumptionId(val value: String)
data class Rg10ActivationAdjustmentId(val value: String)
data class Rg10ReconstructionId(val value: String)
data class Rg10AuditLinkId(val value: String)

data class Rg10OperationIdentity(
    val ledgerId: LedgerId,
    val value: String,
)

enum class Rg10Action(val code: String) {
    CONFIRM_STORED_VALUE_RECHARGE("confirm_stored_value_recharge"),
    CONFIRM_STORED_VALUE_SPEND("confirm_stored_value_spend"),
    INGEST_STORED_VALUE_RECHARGE_CANDIDATE("ingest_stored_value_recharge_candidate"),
    INGEST_STORED_VALUE_SPEND_CANDIDATE("ingest_stored_value_spend_candidate"),
    CONFIRM_IMPORTED_STORED_VALUE_RECHARGE("confirm_imported_stored_value_recharge"),
    CONFIRM_IMPORTED_STORED_VALUE_SPEND("confirm_imported_stored_value_spend"),
    RECORD_EXPIRY_REMINDER("record_expiry_reminder"),
    CONFIRM_STORED_VALUE_EXPIRY_LOSS("confirm_stored_value_expiry_loss"),
    RECONCILE_MERCHANT_CREDIT("reconcile_merchant_credit"),
    RECONCILE_BANK_PAYMENT("reconcile_bank_payment"),
    APPLY_MERCHANT_LOT_ALLOCATION("apply_merchant_lot_allocation"),
    CONFIRM_STORED_VALUE_ACTIVATION_BALANCE("confirm_stored_value_activation_balance"),
    RENAME_STORED_VALUE_LABELS("rename_stored_value_labels"),
}

enum class Rg10InvalidPredicate {
    EXACT_DECIMAL_PAID,
    EXACT_DECIMAL_CREDITED,
    EXACT_DECIMAL_BONUS,
    PAID_POSITIVE,
    CREDITED_POSITIVE,
    BONUS_NON_NEGATIVE,
    CREDITED_EQUALS_PAID_PLUS_BONUS,
    COMPONENT_SUM_MATCH,
    STORED_ACCOUNT_ENABLED,
    STORED_MODEL_ISOLATION,
    EFFECTIVE_BALANCE_CAP,
    LOT_ALLOCATION_CAP,
    EXPIRY_EXPLICIT_CONFIRMATION,
    COMPOSITION_EVIDENCED,
    ACTIVE_SECONDARY_CATEGORY,
    KNOWN_PAYMENT_ACCOUNT,
    OWNED_PAYMENT_ASSET,
    ENABLED_STORED_VALUE_ASSET,
    SAME_CNY_CURRENCY,
    IMPORT_RECHARGE_CONFIRMATION,
    IMPORT_SPEND_CONFIRMATION,
}

data class Rg10InvalidInput(
    val requestId: RequestId,
    val action: Rg10Action,
    val predicate: Rg10InvalidPredicate,
    val attemptedInput: Map<String, String?>,
)

data class Rg10ConfirmRechargeInput(
    val requestId: RequestId,
    val model: String,
    val paymentAccountId: AccountId,
    val storedValueAccountId: AccountId,
    val paidAmount: Money,
    val creditedAmount: Money,
    val bonusAmount: Money,
    val currency: CurrencyUnit,
    val occurredAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val createdAt: Instant,
    val createdAtText: String = createdAt.toString(),
    val explicitConfirmation: Boolean,
    val confirmsModel: Boolean,
    val confirmsPaymentAccount: Boolean,
    val confirmsStoredValueAccount: Boolean,
    val confirmsPaidAmount: Boolean,
    val confirmsCreditedAmount: Boolean,
    val confirmsBonusAmount: Boolean,
    val confirmsActualTime: Boolean,
    val confirmsLotFacts: Boolean,
    val expiresAt: Instant,
    val expiresAtText: String = expiresAt.toString(),
    val merchantId: String? = null,
    val merchantCreditObservedAtText: String? = null,
)

data class Rg10RechargeCommitIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val storedValuePostingId: PostingId,
    val paymentPostingId: PostingId,
    val bonusIncomePostingId: PostingId,
    val lotId: StoredValueLotId,
    val lotHistoryId: String,
    val confirmationId: Rg10ConfirmationId,
    val bankSourceId: Rg10SourceRecordId,
    val merchantSourceId: Rg10SourceRecordId,
    val bankEvidenceId: Rg10EvidenceId,
    val merchantEvidenceId: Rg10EvidenceId,
    val bankLinkId: Rg10EvidenceLinkId,
    val merchantPostingLinkId: Rg10EvidenceLinkId,
    val merchantLotLinkId: Rg10EvidenceLinkId,
    val bonusLinkId: Rg10EvidenceLinkId,
)

data class Rg10ConfirmSpendInput(
    val requestId: RequestId,
    val model: String,
    val behavior: String,
    val storedValueAccountId: AccountId,
    val categoryId: com.unifiedledger.domain.CategoryId,
    val amount: Money,
    val currency: CurrencyUnit,
    val occurredAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val createdAt: Instant,
    val createdAtText: String = createdAt.toString(),
    val explicitConfirmation: Boolean,
    val confirmsModel: Boolean,
    val confirmsBehavior: Boolean,
    val confirmsStoredValueAccount: Boolean,
    val confirmsAmount: Boolean,
    val confirmsActualTime: Boolean,
    val confirmsCategory: Boolean,
    val merchantAllocationProvided: Boolean,
    val confirmsLotAllocation: Boolean,
    val allocations: List<Rg10LotAllocationInput> = emptyList(),
)

data class Rg10LotAllocationInput(
    val lotId: StoredValueLotId,
    val amount: Money,
)

data class Rg10SpendCommitIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expensePostingId: PostingId,
    val storedValuePostingId: PostingId,
    val confirmationId: Rg10ConfirmationId,
    val consumptions: List<Rg10ConsumptionId>,
    val lotHistoryIds: List<String>,
)

data class Rg10ExpiryReminderInput(
    val requestId: RequestId,
    val lotId: StoredValueLotId,
    val reminderStatus: String,
    val explicitConfirmation: Boolean,
)

data class Rg10ConfirmExpiryLossInput(
    val requestId: RequestId,
    val lotId: StoredValueLotId,
    val amount: Money,
    val currency: CurrencyUnit,
    val occurredAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val confirmedAt: Instant = occurredAt,
    val confirmedAtText: String = confirmedAt.toString(),
    val explicitConfirmation: Boolean,
    val confirmsActualExpiry: Boolean,
    val confirmsLot: Boolean,
    val confirmsAmount: Boolean,
)

data class Rg10ExpiryCommitIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expiryLossPostingId: PostingId,
    val storedValuePostingId: PostingId,
    val confirmationId: Rg10ConfirmationId,
    val sourceId: Rg10SourceRecordId,
    val evidenceId: Rg10EvidenceId,
    val linkId: Rg10EvidenceLinkId,
    val lotHistoryId: String,
)

data class Rg10IngestRechargeCandidateInput(
    val requestId: RequestId,
    val model: String,
    val paymentAccountId: AccountId,
    val storedValueAccountId: AccountId,
    val paidAmount: Money,
    val creditedAmount: Money,
    val bonusAmount: Money,
    val currency: CurrencyUnit,
    val occurredAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val lotId: StoredValueLotId?,
    val allFactsComplete: Boolean,
    val explicitConfirmation: Boolean,
)

data class Rg10IngestSpendCandidateInput(
    val requestId: RequestId,
    val model: String,
    val behavior: String,
    val storedValueAccountId: AccountId,
    val categoryId: com.unifiedledger.domain.CategoryId,
    val amount: Money,
    val currency: CurrencyUnit,
    val occurredAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val lotAllocations: List<Rg10LotAllocationInput> = emptyList(),
    val allFactsComplete: Boolean,
    val explicitConfirmation: Boolean,
)

data class Rg10IngestIds(
    val candidateId: Rg10CandidateId,
    val sourceId: Rg10SourceRecordId,
    val evidenceId: Rg10EvidenceId,
)

data class Rg10ConfirmImportedRechargeInput(
    val requestId: RequestId,
    val merchantCreditAmount: Money? = null,
    val merchantSourceId: Rg10SourceRecordId? = null,
    val bankPaymentConfirmed: Boolean? = null,
    val modelConfirmed: Boolean? = null,
    val explicitConfirmation: Boolean? = null,
)

data class Rg10ConfirmImportedSpendInput(
    val requestId: RequestId,
    val storedValueAccountId: AccountId? = null,
    val amount: Money? = null,
    val actualTime: Instant? = null,
    val actualTimeText: String? = null,
    val categoryConfirmed: Boolean? = null,
    val lotAllocationConfirmed: Boolean? = null,
    val explicitConfirmation: Boolean? = null,
)

data class Rg10ReconcileInput(
    val sourceId: Rg10SourceRecordId,
    val evidenceId: Rg10EvidenceId,
    val role: String,
    val targetPostingId: PostingId,
    val explicitConfirmation: Boolean,
)

data class Rg10MerchantAllocationInput(
    val requestId: RequestId,
    val amount: Money,
    val merchantAllocationProvided: Boolean,
    val merchantEvidenceId: Rg10EvidenceId,
    val allocations: List<Rg10LotAllocationInput>,
    val explicitConfirmation: Boolean,
)

data class Rg10AllocationCommitIds(
    val allocationId: Rg10AllocationId,
    val consumptionId: Rg10ConsumptionId,
)

data class Rg10ActivationBalanceInput(
    val requestId: RequestId,
    val storedValueAccountId: AccountId,
    val existingBalance: Money,
    val currency: CurrencyUnit,
    val activationAt: Instant,
    val activationAtText: String = activationAt.toString(),
    val createdAt: Instant,
    val createdAtText: String = createdAt.toString(),
    val explicitConfirmation: Boolean,
    val compositionConfirmed: Boolean,
)

data class Rg10ActivationCommitIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val storedValuePostingId: PostingId,
    val equityPostingId: PostingId,
    val confirmationId: Rg10ConfirmationId,
    val adjustmentId: Rg10ActivationAdjustmentId,
    val adjustmentHistoryId: String,
    val reconstructionId: Rg10ReconstructionId,
    val replacementGroupId: String,
    val sourceId: Rg10SourceRecordId,
    val evidenceId: Rg10EvidenceId,
    val linkId: Rg10EvidenceLinkId,
    val auditLinkId: Rg10AuditLinkId,
)

data class Rg10RenameLabelsInput(
    val accountId: AccountId,
    val newAccountName: String,
    val lotId: StoredValueLotId,
    val newLotLabel: String,
)

sealed interface Rg10Operation {
    val ledgerId: LedgerId
    val action: Rg10Action
    val identity: Rg10OperationIdentity

    data class ConfirmStoredValueRecharge(
        override val ledgerId: LedgerId,
        val input: Rg10ConfirmRechargeInput,
        val ids: Rg10RechargeCommitIds,
    ) : Rg10Operation {
        override val action = Rg10Action.CONFIRM_STORED_VALUE_RECHARGE
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmStoredValueSpend(
        override val ledgerId: LedgerId,
        val input: Rg10ConfirmSpendInput,
        val ids: Rg10SpendCommitIds,
    ) : Rg10Operation {
        override val action = Rg10Action.CONFIRM_STORED_VALUE_SPEND
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class IngestStoredValueRechargeCandidate(
        override val ledgerId: LedgerId,
        val input: Rg10IngestRechargeCandidateInput,
        val ids: Rg10IngestIds,
    ) : Rg10Operation {
        override val action = Rg10Action.INGEST_STORED_VALUE_RECHARGE_CANDIDATE
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class IngestStoredValueSpendCandidate(
        override val ledgerId: LedgerId,
        val input: Rg10IngestSpendCandidateInput,
        val ids: Rg10IngestIds,
    ) : Rg10Operation {
        override val action = Rg10Action.INGEST_STORED_VALUE_SPEND_CANDIDATE
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmImportedStoredValueRecharge(
        override val ledgerId: LedgerId,
        val input: Rg10ConfirmImportedRechargeInput,
    ) : Rg10Operation {
        override val action = Rg10Action.CONFIRM_IMPORTED_STORED_VALUE_RECHARGE
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmImportedStoredValueSpend(
        override val ledgerId: LedgerId,
        val input: Rg10ConfirmImportedSpendInput,
    ) : Rg10Operation {
        override val action = Rg10Action.CONFIRM_IMPORTED_STORED_VALUE_SPEND
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RecordExpiryReminder(
        override val ledgerId: LedgerId,
        val input: Rg10ExpiryReminderInput,
    ) : Rg10Operation {
        override val action = Rg10Action.RECORD_EXPIRY_REMINDER
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmStoredValueExpiryLoss(
        override val ledgerId: LedgerId,
        val input: Rg10ConfirmExpiryLossInput,
        val ids: Rg10ExpiryCommitIds,
    ) : Rg10Operation {
        override val action = Rg10Action.CONFIRM_STORED_VALUE_EXPIRY_LOSS
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ReconcileMerchantCredit(
        override val ledgerId: LedgerId,
        val input: Rg10ReconcileInput,
    ) : Rg10Operation {
        override val action = Rg10Action.RECONCILE_MERCHANT_CREDIT
        override val identity = Rg10OperationIdentity(ledgerId, input.sourceId.value)
    }

    data class ReconcileBankPayment(
        override val ledgerId: LedgerId,
        val input: Rg10ReconcileInput,
    ) : Rg10Operation {
        override val action = Rg10Action.RECONCILE_BANK_PAYMENT
        override val identity = Rg10OperationIdentity(ledgerId, input.sourceId.value)
    }

    data class ApplyMerchantLotAllocation(
        override val ledgerId: LedgerId,
        val input: Rg10MerchantAllocationInput,
        val ids: Rg10AllocationCommitIds,
    ) : Rg10Operation {
        override val action = Rg10Action.APPLY_MERCHANT_LOT_ALLOCATION
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmStoredValueActivationBalance(
        override val ledgerId: LedgerId,
        val input: Rg10ActivationBalanceInput,
        val ids: Rg10ActivationCommitIds,
    ) : Rg10Operation {
        override val action = Rg10Action.CONFIRM_STORED_VALUE_ACTIVATION_BALANCE
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RenameStoredValueLabels(
        override val ledgerId: LedgerId,
        val input: Rg10RenameLabelsInput,
    ) : Rg10Operation {
        override val action = Rg10Action.RENAME_STORED_VALUE_LABELS
        override val identity = Rg10OperationIdentity(ledgerId, input.accountId.value)
    }

    data class InvalidInput(
        override val ledgerId: LedgerId,
        val input: Rg10InvalidInput,
    ) : Rg10Operation {
        override val action = input.action
        override val identity = Rg10OperationIdentity(ledgerId, input.requestId.value)
    }
}

sealed interface Rg10ReturnedId {
    data class Transaction(val id: TransactionId) : Rg10ReturnedId
    data class Version(val id: TransactionVersionId) : Rg10ReturnedId
    data class Lot(val id: StoredValueLotId) : Rg10ReturnedId
    data class Confirmation(val id: Rg10ConfirmationId) : Rg10ReturnedId
    data class Candidate(val id: Rg10CandidateId) : Rg10ReturnedId
    data class EvidenceLink(val id: Rg10EvidenceLinkId) : Rg10ReturnedId
    data class Allocation(val id: Rg10AllocationId) : Rg10ReturnedId
    data class Consumption(val id: Rg10ConsumptionId) : Rg10ReturnedId
    data class Adjustment(val id: Rg10ActivationAdjustmentId) : Rg10ReturnedId
    data class Request(val id: String) : Rg10ReturnedId
}

enum class Rg10RejectionReason(val code: String) {
    EXACT_DECIMAL_STRING_REQUIRED("exact_decimal_string_required"),
    MUST_BE_POSITIVE("must_be_positive"),
    CREDITED_AMOUNT_MUST_BE_POSITIVE("credited_amount_must_be_positive"),
    BONUS_AMOUNT_MUST_BE_ZERO_OR_POSITIVE("bonus_amount_must_be_zero_or_positive"),
    CREDITED_MUST_EQUAL_PAID_PLUS_BONUS("credited_must_equal_paid_plus_bonus"),
    COMPONENT_SUM_MISMATCH("component_sum_mismatch"),
    STORED_VALUE_ACCOUNT_NOT_ENABLED("stored_value_account_not_enabled"),
    STORED_VALUE_MODELS_MUST_NOT_OVERLAP("stored_value_models_must_not_overlap"),
    INSUFFICIENT_EFFECTIVE_STORED_BALANCE("insufficient_effective_stored_balance"),
    LOT_ALLOCATION_EXCEEDS_REMAINING_FACE_VALUE("lot_allocation_exceeds_remaining_face_value"),
    ACTUAL_EXPIRY_REQUIRES_EXPLICIT_CONFIRMATION("actual_expiry_requires_explicit_confirmation"),
    PAID_BONUS_COMPOSITION_MUST_BE_EVIDENCED("paid_bonus_composition_must_be_evidenced"),
    ACTIVE_SECONDARY_CATEGORY_REQUIRED("active_secondary_category_required"),
    UNKNOWN_PAYMENT_ACCOUNT("unknown_payment_account"),
    OWNED_PAYMENT_ASSET_REQUIRED("owned_payment_asset_required"),
    ENABLED_RESTRICTED_STORED_VALUE_ASSET_REQUIRED("enabled_restricted_stored_value_asset_required"),
    SAME_CNY_CURRENCY_REQUIRED("same_cny_currency_required"),
    BANK_PAYMENT_MODEL_AND_ALL_RECHARGE_FACTS_REQUIRED("bank_payment_model_and_all_recharge_facts_required"),
    SPEND_CATEGORY_AND_BEHAVIOR_CONFIRMATION_REQUIRED("spend_category_and_behavior_confirmation_required"),
    EXPLICIT_CONFIRMATION_REQUIRED("explicit_confirmation_required"),
    EVIDENCE_ROLE_TARGET_MISMATCH("evidence_role_target_mismatch"),
    INVALID_RG10_INPUT("invalid_rg10_input"),
    DOMAIN_REJECTED("domain_rejected"),
}

enum class Rg10FieldPath(val value: String) {
    INPUT_REQUEST_ID("$.input.request_id"),
    INPUT_MODEL("$.input.model"),
    INPUT_PAYMENT_ACCOUNT("$.input.payment_account_id"),
    INPUT_STORED_VALUE_ACCOUNT("$.input.stored_value_account_id"),
    INPUT_PAID_AMOUNT("$.input.paid_amount"),
    INPUT_CREDITED_AMOUNT("$.input.credited_amount"),
    INPUT_BONUS_AMOUNT("$.input.bonus_amount"),
    INPUT_CURRENCY("$.input.currency"),
    INPUT_AMOUNT("$.input.amount"),
    INPUT_CATEGORY("$.input.category_id"),
    INPUT_LOT("$.input.lot_id"),
    INPUT_CONFIRMATION("$.input.explicit_confirmation"),
    INPUT_SOURCE("$.input.source_id"),
    INPUT_EVIDENCE("$.input.evidence_id"),
    INPUT_TARGET_POSTING("$.input.target_posting_id"),
    INPUT_MERCHANT_EVIDENCE("$.input.merchant_evidence_id"),
    ATTEMPTED_PAID_AMOUNT("$.attempted_input.paid_amount"),
    ATTEMPTED_CREDITED_AMOUNT("$.attempted_input.credited_amount"),
    ATTEMPTED_BONUS_AMOUNT("$.attempted_input.bonus_amount"),
    ATTEMPTED_STORED_VALUE_ACCOUNT("$.attempted_input.stored_value_account_id"),
    ATTEMPTED_MODEL("$.attempted_input.model"),
    ATTEMPTED_AMOUNT("$.attempted_input.amount"),
    ATTEMPTED_EXPLICIT_CONFIRMATION("$.attempted_input.explicit_confirmation"),
    ATTEMPTED_COMPOSITION("$.attempted_input.paid_bonus_composition"),
    ATTEMPTED_CATEGORY("$.attempted_input.category_id"),
    ATTEMPTED_PAYMENT_ACCOUNT("$.attempted_input.payment_account_id"),
    ATTEMPTED_CURRENCY("$.attempted_input.currency"),
    ATTEMPTED_LOT("$.attempted_input.lot_id"),
    ATTEMPTED_REQUEST_ID("$.attempted_input.request_id"),
}

sealed interface Rg10ExecutionResult {
    class Accepted(returnedIds: List<Rg10ReturnedId>) : Rg10ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg10ReturnedId> get() = snapshot.toList()
        override fun equals(other: Any?) = other is Accepted && snapshot == other.snapshot
        override fun hashCode(): Int = snapshot.hashCode()
        override fun toString(): String = "Accepted(returnedIds=$snapshot)"
    }

    class NoChange(returnedIds: List<Rg10ReturnedId>) : Rg10ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg10ReturnedId> get() = snapshot.toList()
        override fun equals(other: Any?) = other is NoChange && snapshot == other.snapshot
        override fun hashCode(): Int = snapshot.hashCode()
        override fun toString(): String = "NoChange(returnedIds=$snapshot)"
    }

    data class Rejected(
        val reason: Rg10RejectionReason,
        val fieldPath: Rg10FieldPath,
    ) : Rg10ExecutionResult

    data object RequestIdentityConflict : Rg10ExecutionResult
}

fun interface Rg10CommitPort {
    fun commit(operation: Rg10Operation): Rg10ExecutionResult
}

data class Rg10FormalTransactionRecord(
    val formalTransaction: FormalTransaction,
    val createdAt: Instant,
    val sourceRecordId: Rg10SourceRecordId? = null,
    val createdAtText: String? = null,
    val effectiveAtText: String? = null,
)

data class Rg10SourceRecord(
    val id: Rg10SourceRecordId,
    val sourceType: String,
    val observedAt: Instant,
    val observedAtText: String = observedAt.toString(),
    val accountId: AccountId? = null,
    val amount: Money? = null,
    val lotId: StoredValueLotId? = null,
    val immutablePayloadDigest: String,
)

data class Rg10Evidence(
    val id: Rg10EvidenceId,
    val sourceId: Rg10SourceRecordId,
    val evidenceType: String,
    val observedAt: Instant,
    val observedAtText: String = observedAt.toString(),
)

data class Rg10EvidenceLink(
    val id: Rg10EvidenceLinkId,
    val sourceId: Rg10SourceRecordId,
    val evidenceId: Rg10EvidenceId,
    val role: String,
    val targetKind: String,
    val targetId: String,
    val status: String,
    val lotId: StoredValueLotId? = null,
)

data class Rg10Confirmation(
    val id: Rg10ConfirmationId,
    val requestId: RequestId,
    val role: String,
    val transactionId: TransactionId? = null,
    val sourceId: Rg10SourceRecordId? = null,
    val evidenceId: Rg10EvidenceId? = null,
    val auditLinkId: Rg10AuditLinkId? = null,
    val confirmedAt: Instant,
    val confirmedAtText: String = confirmedAt.toString(),
    val explicitConfirmation: Boolean? = null,
    val confirmsActualExpiry: Boolean? = null,
)

data class Rg10Candidate(
    val id: Rg10CandidateId,
    val requestId: RequestId,
    val candidateType: String,
    val status: String,
    val currency: CurrencyUnit,
    val paidAmount: Money? = null,
    val creditedAmount: Money? = null,
    val bonusAmount: Money? = null,
    val amount: Money? = null,
    val occurredAt: Instant? = null,
    val occurredAtText: String? = null,
)

data class Rg10LotConsumption(
    val id: Rg10ConsumptionId,
    val allocationId: Rg10AllocationId? = null,
    val sourceId: Rg10SourceRecordId? = null,
    val evidenceId: Rg10EvidenceId? = null,
    val lotId: StoredValueLotId,
    val amount: Money,
    val paidBonusComposition: String,
)

data class Rg10MerchantAllocation(
    val id: Rg10AllocationId,
    val requestId: RequestId,
    val sourceId: Rg10SourceRecordId,
    val evidenceId: Rg10EvidenceId,
    val lotId: StoredValueLotId,
    val consumptionId: Rg10ConsumptionId,
    val amount: Money,
    val allocationSource: String,
)

data class Rg10ActivationAdjustmentHistory(
    val id: String,
    val event: String,
    val transactionId: TransactionId,
    val occurredAt: Instant,
    val occurredAtText: String = occurredAt.toString(),
    val createdAt: Instant = occurredAt,
    val createdAtText: String = createdAt.toString(),
)

data class Rg10ActivationAdjustment(
    val id: Rg10ActivationAdjustmentId,
    val transactionId: TransactionId,
    val activationAt: Instant,
    val activationAtText: String = activationAt.toString(),
    val existingBalance: Money,
    val compositionStatus: String,
    val replacementStatus: String,
    val history: List<Rg10ActivationAdjustmentHistory>,
)

data class Rg10AuditLink(
    val id: Rg10AuditLinkId,
    val role: String,
    val sourceId: Rg10SourceRecordId? = null,
    val evidenceId: Rg10EvidenceId? = null,
    val confirmationId: Rg10ConfirmationId? = null,
    val transactionId: TransactionId? = null,
)

data class Rg10PostingSemantic(
    val role: String,
    val reconciliationEligible: Boolean,
)

data class Rg10Report(
    val ordinaryIncomeMinor: Long = 0L,
    val specialNonCashBonusIncomeMinor: Long = 0L,
    val ordinaryExpenseMinor: Long = 0L,
    val expiryLossMinor: Long = 0L,
    val consumptionMinor: Long = 0L,
    val budgetEffectMinor: Long = 0L,
    val categoryEffectMinor: Long = 0L,
    val cashInflowMinor: Long = 0L,
    val cashOutflowMinor: Long = 0L,
    val netWorthChangeMinor: Long = 0L,
)

data class Rg10Intake(
    val sourceRecords: List<Rg10SourceRecord> = emptyList(),
    val evidence: List<Rg10Evidence> = emptyList(),
)

data class Rg10Snapshot(
    val formalTransactions: List<Rg10FormalTransactionRecord>,
    val lots: List<StoredValueLot>,
    val consumptions: List<Rg10LotConsumption>,
    val allocations: List<Rg10MerchantAllocation>,
    val adjustments: List<Rg10ActivationAdjustment>,
    val reconstructions: List<StoredValueReconstruction>,
    val candidates: List<Rg10Candidate>,
    val confirmations: List<Rg10Confirmation>,
    val sourceRecords: List<Rg10SourceRecord>,
    val evidence: List<Rg10Evidence>,
    val evidenceLinks: List<Rg10EvidenceLink>,
    val auditLinks: List<Rg10AuditLink>,
    val postingSemantics: Map<String, Rg10PostingSemantic>,
    val balances: Map<AccountId, Money>,
    val reports: Map<String, Rg10Report>,
    val reconciliation: Map<String, String>,
)

/**
 * Deterministic runtime for the approved RG-10 action registry (D-083). Business transitions
 * stay independent from a database driver; persistence integration preserves this typed
 * operation boundary. Rejected/stale/incomplete/no-change paths have zero formal effect and
 * keep the baseline state field by field.
 */
class Rg10Runtime(
    private val catalog: LedgerCatalog,
    openingTransactions: List<Rg10FormalTransactionRecord>,
    intake: Rg10Intake = Rg10Intake(),
) : Rg10CommitPort {
    constructor(catalog: LedgerCatalog, snapshot: Rg10Snapshot) : this(
        catalog,
        snapshot.formalTransactions,
        Rg10Intake(snapshot.sourceRecords, snapshot.evidence),
    ) {
        lots += snapshot.lots
        consumptions += snapshot.consumptions
        allocations += snapshot.allocations
        adjustments += snapshot.adjustments.map { it.copy(history = it.history.toList()) }
        reconstructions += snapshot.reconstructions
        candidates += snapshot.candidates
        confirmations += snapshot.confirmations
        evidenceLinks += snapshot.evidenceLinks
        auditLinks += snapshot.auditLinks
        postingSemantics.putAll(snapshot.postingSemantics)
        postingReconciliation.putAll(snapshot.reconciliation)
    }

    private val formalTransactions = openingTransactions.toMutableList()
    private val lots = mutableListOf<StoredValueLot>()
    private val consumptions = mutableListOf<Rg10LotConsumption>()
    private val allocations = mutableListOf<Rg10MerchantAllocation>()
    private val adjustments = mutableListOf<Rg10ActivationAdjustment>()
    private val reconstructions = mutableListOf<StoredValueReconstruction>()
    private val candidates = mutableListOf<Rg10Candidate>()
    private val confirmations = mutableListOf<Rg10Confirmation>()
    private val sourceRecords = intake.sourceRecords.toMutableList()
    private val evidence = intake.evidence.toMutableList()
    private val evidenceLinks = mutableListOf<Rg10EvidenceLink>()
    private val auditLinks = mutableListOf<Rg10AuditLink>()
    private val postingSemantics = mutableMapOf<String, Rg10PostingSemantic>()
    private val postingReconciliation = mutableMapOf<String, String>()
    private val receipts = mutableMapOf<Rg10OperationIdentity, Receipt>()

    private data class Receipt(
        val fingerprint: String,
        val result: Rg10ExecutionResult,
    )

    override fun commit(operation: Rg10Operation): Rg10ExecutionResult {
        val fingerprint = canonicalInput(operation)
        receipts[operation.identity]?.let { receipt ->
            return if (receipt.fingerprint == fingerprint) {
                when (val result = receipt.result) {
                    is Rg10ExecutionResult.Accepted -> Rg10ExecutionResult.NoChange(result.returnedIds)
                    else -> result
                }
            } else {
                Rg10ExecutionResult.RequestIdentityConflict
            }
        }
        val result = when (operation) {
            is Rg10Operation.ConfirmStoredValueRecharge -> confirmRecharge(operation)
            is Rg10Operation.ConfirmStoredValueSpend -> confirmSpend(operation)
            is Rg10Operation.IngestStoredValueRechargeCandidate -> ingestRechargeCandidate(operation)
            is Rg10Operation.IngestStoredValueSpendCandidate -> ingestSpendCandidate(operation)
            is Rg10Operation.ConfirmImportedStoredValueRecharge -> rejectIncompleteImportedRecharge(operation)
            is Rg10Operation.ConfirmImportedStoredValueSpend -> rejectIncompleteImportedSpend(operation)
            is Rg10Operation.RecordExpiryReminder -> recordExpiryReminder(operation)
            is Rg10Operation.ConfirmStoredValueExpiryLoss -> confirmExpiryLoss(operation)
            is Rg10Operation.ReconcileMerchantCredit -> reconcileMerchantCredit(operation)
            is Rg10Operation.ReconcileBankPayment -> reconcileBankPayment(operation)
            is Rg10Operation.ApplyMerchantLotAllocation -> applyMerchantLotAllocation(operation)
            is Rg10Operation.ConfirmStoredValueActivationBalance -> confirmActivationBalance(operation)
            is Rg10Operation.RenameStoredValueLabels -> renameStoredValueLabels(operation)
            is Rg10Operation.InvalidInput -> rejectInvalidInput(operation)
        }
        if (result is Rg10ExecutionResult.Accepted || result is Rg10ExecutionResult.Rejected) {
            receipts[operation.identity] = Receipt(fingerprint, result)
        }
        return result
    }

    fun snapshot(): Rg10Snapshot = Rg10Snapshot(
        formalTransactions = formalTransactions.toList(),
        lots = lots.map { it.copy(history = it.history.toList()) },
        consumptions = consumptions.toList(),
        allocations = allocations.toList(),
        adjustments = adjustments.map { it.copy(history = it.history.toList()) },
        reconstructions = reconstructions.map { it.copy(history = it.history.toList()) },
        candidates = candidates.toList(),
        confirmations = confirmations.toList(),
        sourceRecords = sourceRecords.toList(),
        evidence = evidence.toList(),
        evidenceLinks = evidenceLinks.toList(),
        auditLinks = auditLinks.toList(),
        postingSemantics = postingSemantics.toMap(),
        balances = replayBalances(),
        reports = reports(),
        reconciliation = postingReconciliation.toMap(),
    )

    fun operationFingerprint(operation: Rg10Operation): String = canonicalInput(operation)

    private fun confirmRecharge(operation: Rg10Operation.ConfirmStoredValueRecharge): Rg10ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.model != "stored_value_asset") {
            return rejected(Rg10RejectionReason.STORED_VALUE_MODELS_MUST_NOT_OVERLAP, Rg10FieldPath.INPUT_MODEL)
        }
        if (
            !input.confirmsModel ||
            !input.confirmsPaymentAccount ||
            !input.confirmsStoredValueAccount ||
            !input.confirmsPaidAmount ||
            !input.confirmsCreditedAmount ||
            !input.confirmsBonusAmount ||
            !input.confirmsActualTime ||
            !input.confirmsLotFacts
        ) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.paidAmount.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_PAID_AMOUNT)
        }
        if (input.creditedAmount.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.CREDITED_AMOUNT_MUST_BE_POSITIVE, Rg10FieldPath.INPUT_CREDITED_AMOUNT)
        }
        if (input.bonusAmount.minorUnits < 0L) {
            return rejected(Rg10RejectionReason.BONUS_AMOUNT_MUST_BE_ZERO_OR_POSITIVE, Rg10FieldPath.INPUT_BONUS_AMOUNT)
        }
        val composed = checkedAdd(input.paidAmount.minorUnits, input.bonusAmount.minorUnits)
            ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_CREDITED_AMOUNT)
        if (input.creditedAmount.minorUnits < input.paidAmount.minorUnits) {
            return rejected(Rg10RejectionReason.CREDITED_MUST_EQUAL_PAID_PLUS_BONUS, Rg10FieldPath.INPUT_CREDITED_AMOUNT)
        }
        if (composed != input.creditedAmount.minorUnits) {
            return rejected(Rg10RejectionReason.COMPONENT_SUM_MISMATCH, Rg10FieldPath.INPUT_CREDITED_AMOUNT)
        }
        if (
            input.currency.code != "CNY" ||
            input.paidAmount.currency != input.currency ||
            input.creditedAmount.currency != input.currency ||
            input.bonusAmount.currency != input.currency
        ) {
            return rejected(Rg10RejectionReason.SAME_CNY_CURRENCY_REQUIRED, Rg10FieldPath.INPUT_CURRENCY)
        }
        val stored = catalogAccount(input.storedValueAccountId)
            ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        val payment = catalogAccount(input.paymentAccountId)
            ?: return rejected(Rg10RejectionReason.UNKNOWN_PAYMENT_ACCOUNT, Rg10FieldPath.INPUT_PAYMENT_ACCOUNT)
        if (payment.kind != AccountKind.ASSET || !payment.ownedByUser || !payment.realAccount) {
            return rejected(Rg10RejectionReason.OWNED_PAYMENT_ASSET_REQUIRED, Rg10FieldPath.INPUT_PAYMENT_ACCOUNT)
        }
        storedAccountGuard(stored)?.let { return it }
        val bonusIncome = catalog.accounts.firstOrNull { it.systemRole == "stored_value_bonus_right_income" }
            ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_PAID_AMOUNT)
        if (bonusIncome.currency != input.currency) {
            return rejected(Rg10RejectionReason.SAME_CNY_CURRENCY_REQUIRED, Rg10FieldPath.INPUT_CURRENCY)
        }
        val recharge = when (
            val result = createStoredValueRecharge(
                catalog,
                com.unifiedledger.domain.StoredValueRechargeCommand(
                    ledgerId = operation.ledgerId,
                    storedValueAccountId = input.storedValueAccountId,
                    paymentAccountId = input.paymentAccountId,
                    paidAmount = input.paidAmount,
                    creditedAmount = input.creditedAmount,
                    bonusAmount = input.bonusAmount,
                    times = TransactionTimes(
                        occurredAt = input.occurredAt,
                        statisticsAt = input.occurredAt,
                        effectiveAt = input.occurredAt,
                    ),
                ),
                com.unifiedledger.domain.StoredValueRechargeIds(
                    transactionId = operation.ids.transactionId,
                    versionId = operation.ids.versionId,
                    postingSetId = operation.ids.postingSetId,
                    storedValuePostingId = operation.ids.storedValuePostingId,
                    paymentPostingId = operation.ids.paymentPostingId,
                    bonusIncomePostingId = operation.ids.bonusIncomePostingId,
                ),
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return domainRejected(result.violation, Rg10FieldPath.INPUT_PAID_AMOUNT)
        }
        val record = Rg10FormalTransactionRecord(
            recharge.formalTransaction,
            input.createdAt,
            createdAtText = input.createdAtText,
            effectiveAtText = input.occurredAtText,
        )
        if (
            confirmations.any { it.id == operation.ids.confirmationId } ||
            lots.any { it.id == operation.ids.lotId } ||
            sourceRecords.any { it.id == operation.ids.bankSourceId || it.id == operation.ids.merchantSourceId } ||
            evidence.any { it.id == operation.ids.bankEvidenceId || it.id == operation.ids.merchantEvidenceId } ||
            evidenceLinks.any {
                it.id == operation.ids.bankLinkId ||
                    it.id == operation.ids.merchantPostingLinkId ||
                    it.id == operation.ids.merchantLotLinkId ||
                    it.id == operation.ids.bonusLinkId
            } ||
            !canAppendFormalTransaction(record)
        ) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_PAID_AMOUNT)
        }
        val bankObservedAtText = input.occurredAtText
        val merchantObservedAtText = input.merchantCreditObservedAtText ?: input.occurredAtText
        formalTransactions += record
        lots += StoredValueLot(
            id = operation.ids.lotId,
            rechargeTransactionId = operation.ids.transactionId,
            loadedAt = input.occurredAt,
            expiresAt = input.expiresAt,
            faceValue = input.creditedAmount,
            remainingFaceValue = input.creditedAmount,
            paidAmount = input.paidAmount,
            bonusAmount = input.bonusAmount,
            remainingPaidAmount = input.paidAmount,
            remainingBonusAmount = input.bonusAmount,
            compositionStatus = "known",
            history = listOf(
                StoredValueLotHistory(
                    id = operation.ids.lotHistoryId,
                    event = "loaded",
                    transactionId = operation.ids.transactionId,
                    amount = input.creditedAmount,
                    remainingFaceValue = input.creditedAmount,
                    occurredAt = input.occurredAt,
                    createdAt = input.createdAt,
                    occurredAtText = input.occurredAtText,
                    createdAtText = input.createdAtText,
                ),
            ),
            merchantId = input.merchantId,
            loadedAtText = input.occurredAtText,
            expiresAtText = input.expiresAtText,
        )
        sourceRecords += Rg10SourceRecord(
            id = operation.ids.bankSourceId,
            sourceType = "bank_payment",
            observedAt = Instant.parse(bankObservedAtText),
            observedAtText = bankObservedAtText,
            accountId = input.paymentAccountId,
            amount = input.paidAmount,
            immutablePayloadDigest = "sha256:rg10-bank-payment",
        )
        sourceRecords += Rg10SourceRecord(
            id = operation.ids.merchantSourceId,
            sourceType = "merchant_stored_value_credit",
            observedAt = Instant.parse(merchantObservedAtText),
            observedAtText = merchantObservedAtText,
            accountId = input.storedValueAccountId,
            amount = input.creditedAmount,
            lotId = operation.ids.lotId,
            immutablePayloadDigest = "sha256:rg10-merchant-credit",
        )
        evidence += Rg10Evidence(
            id = operation.ids.bankEvidenceId,
            sourceId = operation.ids.bankSourceId,
            evidenceType = "bank_payment",
            observedAt = Instant.parse(bankObservedAtText),
            observedAtText = bankObservedAtText,
        )
        evidence += Rg10Evidence(
            id = operation.ids.merchantEvidenceId,
            sourceId = operation.ids.merchantSourceId,
            evidenceType = "merchant_credit_and_lot",
            observedAt = Instant.parse(merchantObservedAtText),
            observedAtText = merchantObservedAtText,
        )
        evidenceLinks += Rg10EvidenceLink(
            id = operation.ids.bankLinkId,
            sourceId = operation.ids.bankSourceId,
            evidenceId = operation.ids.bankEvidenceId,
            role = "bank_payment_posting",
            targetKind = "POSTING",
            targetId = operation.ids.paymentPostingId.value,
            status = "pending",
        )
        evidenceLinks += Rg10EvidenceLink(
            id = operation.ids.merchantPostingLinkId,
            sourceId = operation.ids.merchantSourceId,
            evidenceId = operation.ids.merchantEvidenceId,
            role = "stored_value_asset_posting",
            targetKind = "POSTING",
            targetId = operation.ids.storedValuePostingId.value,
            status = "pending",
        )
        evidenceLinks += Rg10EvidenceLink(
            id = operation.ids.merchantLotLinkId,
            sourceId = operation.ids.merchantSourceId,
            evidenceId = operation.ids.merchantEvidenceId,
            role = "stored_value_lot_fact",
            targetKind = "DOMAIN_ENTITY",
            targetId = operation.ids.lotId.value,
            status = "pending",
            lotId = operation.ids.lotId,
        )
        evidenceLinks += Rg10EvidenceLink(
            id = operation.ids.bonusLinkId,
            sourceId = operation.ids.merchantSourceId,
            evidenceId = operation.ids.merchantEvidenceId,
            role = "stored_value_bonus_component",
            targetKind = "DOMAIN_ENTITY",
            targetId = operation.ids.lotId.value,
            status = "pending",
            lotId = operation.ids.lotId,
        )
        confirmations += Rg10Confirmation(
            id = operation.ids.confirmationId,
            requestId = input.requestId,
            role = "stored_value_recharge_confirmation",
            transactionId = operation.ids.transactionId,
            confirmedAt = input.createdAt,
            confirmedAtText = input.createdAtText,
            explicitConfirmation = true,
        )
        recharge.postings.forEach { typed ->
            val role = when (typed.role) {
                StoredValueRechargePostingRole.STORED_VALUE_CREDIT -> "STORED_VALUE_ASSET"
                StoredValueRechargePostingRole.PAYMENT_OUT -> "PAYMENT_OUT"
                StoredValueRechargePostingRole.BONUS_INCOME -> "BONUS_INCOME"
            }
            val eligible = typed.role != StoredValueRechargePostingRole.BONUS_INCOME
            postingSemantics[typed.posting.id.value] = Rg10PostingSemantic(role, eligible)
            if (eligible) {
                postingReconciliation[typed.posting.id.value] = "pending"
            }
        }
        return accepted(
            listOf(
                Rg10ReturnedId.Transaction(operation.ids.transactionId),
                Rg10ReturnedId.Lot(operation.ids.lotId),
            ),
        )
    }

    private fun confirmSpend(operation: Rg10Operation.ConfirmStoredValueSpend): Rg10ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.model != "stored_value_asset" || input.behavior != "stored_value_spend") {
            return rejected(Rg10RejectionReason.STORED_VALUE_MODELS_MUST_NOT_OVERLAP, Rg10FieldPath.INPUT_MODEL)
        }
        if (
            !input.confirmsModel ||
            !input.confirmsBehavior ||
            !input.confirmsStoredValueAccount ||
            !input.confirmsAmount ||
            !input.confirmsActualTime ||
            !input.confirmsCategory ||
            !input.confirmsLotAllocation
        ) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.amount.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_AMOUNT)
        }
        if (input.currency.code != "CNY" || input.amount.currency != input.currency) {
            return rejected(Rg10RejectionReason.SAME_CNY_CURRENCY_REQUIRED, Rg10FieldPath.INPUT_CURRENCY)
        }
        val category = catalog.categories.firstOrNull { it.id == input.categoryId }
            ?: return rejected(Rg10RejectionReason.ACTIVE_SECONDARY_CATEGORY_REQUIRED, Rg10FieldPath.INPUT_CATEGORY)
        val categoryPostingAccountId = category.postingAccountId
        if (!category.active || category.parentId == null || categoryPostingAccountId == null) {
            return rejected(Rg10RejectionReason.ACTIVE_SECONDARY_CATEGORY_REQUIRED, Rg10FieldPath.INPUT_CATEGORY)
        }
        val expenseAccount = catalogAccount(categoryPostingAccountId)
        if (expenseAccount == null || expenseAccount.kind != AccountKind.EXPENSE) {
            return rejected(Rg10RejectionReason.ACTIVE_SECONDARY_CATEGORY_REQUIRED, Rg10FieldPath.INPUT_CATEGORY)
        }
        val stored = catalogAccount(input.storedValueAccountId)
            ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        storedAccountGuard(stored)?.let { return it }
        val consumptionPlan = if (input.merchantAllocationProvided) {
            merchantConsumptionPlan(input.allocations, input.amount)
        } else {
            defaultConsumptionPlan(input.amount)
        }
        if (consumptionPlan == null) {
            return rejected(Rg10RejectionReason.INSUFFICIENT_EFFECTIVE_STORED_BALANCE, Rg10FieldPath.INPUT_AMOUNT)
        }
        val spend = when (
            val result = createStoredValueSpend(
                catalog,
                com.unifiedledger.domain.StoredValueSpendCommand(
                    ledgerId = operation.ledgerId,
                    storedValueAccountId = input.storedValueAccountId,
                    categoryId = input.categoryId,
                    amount = input.amount,
                    times = TransactionTimes(
                        occurredAt = input.occurredAt,
                        statisticsAt = input.occurredAt,
                        effectiveAt = input.occurredAt,
                    ),
                ),
                com.unifiedledger.domain.StoredValueSpendIds(
                    transactionId = operation.ids.transactionId,
                    versionId = operation.ids.versionId,
                    postingSetId = operation.ids.postingSetId,
                    expensePostingId = operation.ids.expensePostingId,
                    storedValuePostingId = operation.ids.storedValuePostingId,
                ),
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return domainRejected(result.violation, Rg10FieldPath.INPUT_AMOUNT)
        }
        val record = Rg10FormalTransactionRecord(
            spend.formalTransaction,
            input.createdAt,
            createdAtText = input.createdAtText,
            effectiveAtText = input.occurredAtText,
        )
        val consumptionIds = operation.ids.consumptions
        // The commit ids must match the derived plan before any formal effect: a rejected
        // path keeps zero formal transactions (class contract above).
        if (consumptionIds.size != consumptionPlan.size) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT)
        }
        if (
            confirmations.any { it.id == operation.ids.confirmationId } ||
            consumptionIds.any { id -> consumptions.any { it.id == id } } ||
            !canAppendFormalTransaction(record)
        ) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT)
        }
        formalTransactions += record
        consumptionPlan.forEachIndexed { index, (lot, amount) ->
            val lotIndex = lots.indexOfFirst { it.id == lot.id }
            check(lotIndex >= 0) { "RG-10 spend plan references an unknown lot" }
            val current = lots[lotIndex]
            val remaining = checkedSubtract(current.remainingFaceValue.minorUnits, amount.minorUnits)
                ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT)
            val historyId = operation.ids.lotHistoryIds.getOrNull(index)
                ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT)
            lots[lotIndex] = current.copy(
                remainingFaceValue = Money.ofMinor(remaining, amount.currency),
                remainingPaidAmount = null,
                remainingBonusAmount = null,
                compositionStatus = if (input.merchantAllocationProvided) current.compositionStatus else "unknown_after_unallocated_consumption",
                history = current.history + StoredValueLotHistory(
                    id = historyId,
                    event = "spent",
                    transactionId = operation.ids.transactionId,
                    amount = Money.ofMinor(checkedNegate(amount.minorUnits) ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT), amount.currency),
                    remainingFaceValue = Money.ofMinor(remaining, amount.currency),
                    occurredAt = input.occurredAt,
                    createdAt = input.createdAt,
                    occurredAtText = input.occurredAtText,
                    createdAtText = input.createdAtText,
                    compositionStatus = "unknown",
                ),
            )
            consumptions += Rg10LotConsumption(
                id = consumptionIds[index],
                lotId = lot.id,
                amount = amount,
                paidBonusComposition = "unknown",
            )
        }
        confirmations += Rg10Confirmation(
            id = operation.ids.confirmationId,
            requestId = input.requestId,
            role = "stored_value_spend_confirmation",
            transactionId = operation.ids.transactionId,
            confirmedAt = input.createdAt,
            confirmedAtText = input.createdAtText,
            explicitConfirmation = true,
        )
        spend.postings.forEach { typed ->
            val role = when (typed.role) {
                StoredValueSpendPostingRole.EXPENSE_OUT -> "EXPENSE_OUT"
                StoredValueSpendPostingRole.STORED_VALUE_DEBIT -> "STORED_VALUE_DEBIT"
            }
            val eligible = typed.role == StoredValueSpendPostingRole.STORED_VALUE_DEBIT
            postingSemantics[typed.posting.id.value] = Rg10PostingSemantic(role, eligible)
            if (eligible) {
                postingReconciliation[typed.posting.id.value] = "pending"
            }
        }
        return accepted(
            listOf(
                Rg10ReturnedId.Transaction(operation.ids.transactionId),
            ),
        )
    }

    private fun recordExpiryReminder(operation: Rg10Operation.RecordExpiryReminder): Rg10ExecutionResult {
        val input = operation.input
        if (input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (lots.none { it.id == input.lotId }) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_LOT)
        }
        return accepted(listOf(Rg10ReturnedId.Request(input.requestId.value)))
    }

    private fun confirmExpiryLoss(operation: Rg10Operation.ConfirmStoredValueExpiryLoss): Rg10ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.ACTUAL_EXPIRY_REQUIRES_EXPLICIT_CONFIRMATION, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (!input.confirmsActualExpiry || !input.confirmsLot || !input.confirmsAmount) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.currency.code != "CNY" || input.amount.currency != input.currency) {
            return rejected(Rg10RejectionReason.SAME_CNY_CURRENCY_REQUIRED, Rg10FieldPath.INPUT_CURRENCY)
        }
        if (input.amount.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_AMOUNT)
        }
        val lotIndex = lots.indexOfFirst { it.id == input.lotId }
        if (lotIndex < 0) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_LOT)
        }
        val lot = lots[lotIndex]
        if (input.amount.minorUnits > lot.remainingFaceValue.minorUnits) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT)
        }
        val stored = catalogAccount(storedAccountIdForLot(lot))
            ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_LOT)
        storedAccountGuard(stored)?.let { return it }
        val loss = when (
            val result = createStoredValueExpiryLoss(
                catalog,
                com.unifiedledger.domain.StoredValueExpiryLossCommand(
                    ledgerId = operation.ledgerId,
                    storedValueAccountId = stored.id,
                    confirmedExpiredAmount = input.amount,
                    times = TransactionTimes(
                        occurredAt = input.occurredAt,
                        statisticsAt = input.occurredAt,
                        effectiveAt = input.occurredAt,
                    ),
                ),
                com.unifiedledger.domain.StoredValueExpiryLossIds(
                    transactionId = operation.ids.transactionId,
                    versionId = operation.ids.versionId,
                    postingSetId = operation.ids.postingSetId,
                    expiryLossPostingId = operation.ids.expiryLossPostingId,
                    storedValuePostingId = operation.ids.storedValuePostingId,
                ),
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return domainRejected(result.violation, Rg10FieldPath.INPUT_AMOUNT)
        }
        val record = Rg10FormalTransactionRecord(
            loss.formalTransaction,
            input.confirmedAt,
            createdAtText = input.confirmedAtText,
            effectiveAtText = input.occurredAtText,
        )
        if (
            confirmations.any { it.id == operation.ids.confirmationId } ||
            sourceRecords.any { it.id == operation.ids.sourceId } ||
            evidence.any { it.id == operation.ids.evidenceId } ||
            evidenceLinks.any { it.id == operation.ids.linkId } ||
            !canAppendFormalTransaction(record)
        ) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT)
        }
        formalTransactions += record
        val remaining = checkedSubtract(lot.remainingFaceValue.minorUnits, input.amount.minorUnits)
            ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT)
        lots[lotIndex] = lot.copy(
            remainingFaceValue = Money.ofMinor(remaining, input.amount.currency),
            history = lot.history + StoredValueLotHistory(
                id = operation.ids.lotHistoryId,
                event = "expired",
                transactionId = operation.ids.transactionId,
                amount = Money.ofMinor(checkedNegate(input.amount.minorUnits) ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_AMOUNT), input.amount.currency),
                remainingFaceValue = Money.ofMinor(remaining, input.amount.currency),
                occurredAt = input.occurredAt,
                createdAt = input.confirmedAt,
                occurredAtText = input.occurredAtText,
                createdAtText = input.confirmedAtText,
            ),
        )
        sourceRecords += Rg10SourceRecord(
            id = operation.ids.sourceId,
            sourceType = "user_expiry_confirmation",
            observedAt = input.confirmedAt,
            observedAtText = input.confirmedAtText,
            lotId = input.lotId,
            amount = input.amount,
            immutablePayloadDigest = "sha256:rg10-expiry",
        )
        evidence += Rg10Evidence(
            id = operation.ids.evidenceId,
            sourceId = operation.ids.sourceId,
            evidenceType = "confirmed_actual_expiry",
            observedAt = input.confirmedAt,
            observedAtText = input.confirmedAtText,
        )
        evidenceLinks += Rg10EvidenceLink(
            id = operation.ids.linkId,
            sourceId = operation.ids.sourceId,
            evidenceId = operation.ids.evidenceId,
            role = "stored_value_expiry_confirmation",
            targetKind = "TRANSACTION",
            targetId = operation.ids.transactionId.value,
            status = "pending",
            lotId = input.lotId,
        )
        confirmations += Rg10Confirmation(
            id = operation.ids.confirmationId,
            requestId = input.requestId,
            role = "stored_value_expiry_confirmation",
            transactionId = operation.ids.transactionId,
            confirmedAt = input.confirmedAt,
            confirmedAtText = input.confirmedAtText,
            explicitConfirmation = true,
            confirmsActualExpiry = true,
        )
        loss.postings.forEach { typed ->
            val role = when (typed.role) {
                StoredValueExpiryLossPostingRole.EXPIRY_LOSS -> "EXPIRY_LOSS"
                StoredValueExpiryLossPostingRole.STORED_VALUE_DEBIT -> "STORED_VALUE_DEBIT"
            }
            val eligible = typed.role == StoredValueExpiryLossPostingRole.STORED_VALUE_DEBIT
            postingSemantics[typed.posting.id.value] = Rg10PostingSemantic(role, eligible)
            if (eligible) {
                postingReconciliation[typed.posting.id.value] = "pending"
            }
        }
        return accepted(
            listOf(
                Rg10ReturnedId.Transaction(operation.ids.transactionId),
            ),
        )
    }

    private fun ingestRechargeCandidate(operation: Rg10Operation.IngestStoredValueRechargeCandidate): Rg10ExecutionResult {
        val input = operation.input
        if (input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.model != "stored_value_asset") {
            return rejected(Rg10RejectionReason.STORED_VALUE_MODELS_MUST_NOT_OVERLAP, Rg10FieldPath.INPUT_MODEL)
        }
        if (input.paidAmount.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_PAID_AMOUNT)
        }
        if (input.creditedAmount.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.CREDITED_AMOUNT_MUST_BE_POSITIVE, Rg10FieldPath.INPUT_CREDITED_AMOUNT)
        }
        if (input.bonusAmount.minorUnits < 0L) {
            return rejected(Rg10RejectionReason.BONUS_AMOUNT_MUST_BE_ZERO_OR_POSITIVE, Rg10FieldPath.INPUT_BONUS_AMOUNT)
        }
        if (
            candidates.any { it.id == operation.ids.candidateId } ||
            sourceRecords.any { it.id == operation.ids.sourceId } ||
            evidence.any { it.id == operation.ids.evidenceId }
        ) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_SOURCE)
        }
        sourceRecords += Rg10SourceRecord(
            id = operation.ids.sourceId,
            sourceType = "imported_stored_value_recharge",
            observedAt = input.occurredAt,
            observedAtText = input.occurredAtText,
            amount = input.creditedAmount,
            immutablePayloadDigest = "sha256:rg10-import-recharge",
        )
        evidence += Rg10Evidence(
            id = operation.ids.evidenceId,
            sourceId = operation.ids.sourceId,
            evidenceType = "imported_recharge_candidate",
            observedAt = input.occurredAt,
            observedAtText = input.occurredAtText,
        )
        candidates += Rg10Candidate(
            id = operation.ids.candidateId,
            requestId = input.requestId,
            candidateType = "stored_value_recharge",
            status = "pending_confirmation",
            currency = input.currency,
            paidAmount = input.paidAmount,
            creditedAmount = input.creditedAmount,
            bonusAmount = input.bonusAmount,
            occurredAt = input.occurredAt,
            occurredAtText = input.occurredAtText,
        )
        return accepted(
            listOf(
                Rg10ReturnedId.Candidate(operation.ids.candidateId),
            ),
        )
    }

    private fun ingestSpendCandidate(operation: Rg10Operation.IngestStoredValueSpendCandidate): Rg10ExecutionResult {
        val input = operation.input
        if (input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.model != "stored_value_asset" || input.behavior != "stored_value_spend") {
            return rejected(Rg10RejectionReason.STORED_VALUE_MODELS_MUST_NOT_OVERLAP, Rg10FieldPath.INPUT_MODEL)
        }
        if (input.amount.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_AMOUNT)
        }
        if (
            candidates.any { it.id == operation.ids.candidateId } ||
            sourceRecords.any { it.id == operation.ids.sourceId } ||
            evidence.any { it.id == operation.ids.evidenceId }
        ) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_SOURCE)
        }
        sourceRecords += Rg10SourceRecord(
            id = operation.ids.sourceId,
            sourceType = "imported_stored_value_spend",
            observedAt = input.occurredAt,
            observedAtText = input.occurredAtText,
            amount = input.amount,
            immutablePayloadDigest = "sha256:rg10-import-spend",
        )
        evidence += Rg10Evidence(
            id = operation.ids.evidenceId,
            sourceId = operation.ids.sourceId,
            evidenceType = "imported_spend_candidate",
            observedAt = input.occurredAt,
            observedAtText = input.occurredAtText,
        )
        candidates += Rg10Candidate(
            id = operation.ids.candidateId,
            requestId = input.requestId,
            candidateType = "stored_value_spend",
            status = "pending_confirmation",
            currency = input.currency,
            amount = input.amount,
            occurredAt = input.occurredAt,
            occurredAtText = input.occurredAtText,
        )
        return accepted(
            listOf(
                Rg10ReturnedId.Candidate(operation.ids.candidateId),
            ),
        )
    }

    private fun rejectIncompleteImportedRecharge(
        operation: Rg10Operation.ConfirmImportedStoredValueRecharge,
    ): Rg10ExecutionResult {
        val input = operation.input
        if (
            input.bankPaymentConfirmed != true ||
            input.modelConfirmed != true ||
            input.explicitConfirmation != true
        ) {
            return rejected(
                Rg10RejectionReason.BANK_PAYMENT_MODEL_AND_ALL_RECHARGE_FACTS_REQUIRED,
                Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION,
            )
        }
        return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.ATTEMPTED_REQUEST_ID)
    }

    private fun rejectIncompleteImportedSpend(
        operation: Rg10Operation.ConfirmImportedStoredValueSpend,
    ): Rg10ExecutionResult {
        val input = operation.input
        if (
            input.categoryConfirmed != true ||
            input.lotAllocationConfirmed != true ||
            input.explicitConfirmation != true
        ) {
            return rejected(
                Rg10RejectionReason.SPEND_CATEGORY_AND_BEHAVIOR_CONFIRMATION_REQUIRED,
                Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION,
            )
        }
        return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.ATTEMPTED_REQUEST_ID)
    }

    private fun reconcileMerchantCredit(operation: Rg10Operation.ReconcileMerchantCredit): Rg10ExecutionResult =
        reconcile(
            operation.ledgerId,
            operation.input,
            expectedRole = "stored_value_asset_posting",
            eligibleSemanticRoles = setOf("STORED_VALUE_ASSET"),
        )

    private fun reconcileBankPayment(operation: Rg10Operation.ReconcileBankPayment): Rg10ExecutionResult =
        reconcile(
            operation.ledgerId,
            operation.input,
            expectedRole = "bank_payment_posting",
            eligibleSemanticRoles = setOf("PAYMENT_OUT"),
        )

    private fun reconcile(
        ledgerId: LedgerId,
        input: Rg10ReconcileInput,
        expectedRole: String,
        eligibleSemanticRoles: Set<String>,
    ): Rg10ExecutionResult {
        // The claimed evidence role must match the operation's owning role before any
        // source/evidence lookup: a merchant op can never reconcile via a bank claim.
        if (input.role != expectedRole) {
            return rejected(Rg10RejectionReason.EVIDENCE_ROLE_TARGET_MISMATCH, Rg10FieldPath.INPUT_TARGET_POSTING)
        }
        if (!input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        val source = sourceRecords.firstOrNull { it.id == input.sourceId }
            ?: return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_SOURCE)
        val evidenceItem = evidence.firstOrNull { it.id == input.evidenceId && it.sourceId == input.sourceId }
            ?: return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_EVIDENCE)
        val posting = formalTransactions.asSequence()
            .flatMap { it.formalTransaction.currentPostings().asSequence() }
            .firstOrNull { it.id == input.targetPostingId }
            ?: return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_TARGET_POSTING)
        val semantic = postingSemantics[input.targetPostingId.value]
            ?: return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_TARGET_POSTING)
        if (semantic.role !in eligibleSemanticRoles) {
            return rejected(Rg10RejectionReason.EVIDENCE_ROLE_TARGET_MISMATCH, Rg10FieldPath.INPUT_TARGET_POSTING)
        }
        val link = evidenceLinks.firstOrNull {
            it.sourceId == input.sourceId &&
                it.evidenceId == input.evidenceId &&
                it.role == expectedRole &&
                it.targetKind == "POSTING" &&
                it.targetId == input.targetPostingId.value
        } ?: return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_EVIDENCE)
        if (link.status != "pending") {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_EVIDENCE)
        }
        evidenceLinks[evidenceLinks.indexOf(link)] = link.copy(status = "matched")
        postingReconciliation[input.targetPostingId.value] = "matched"
        return accepted(
            listOf(
                Rg10ReturnedId.EvidenceLink(link.id),
            ),
        )
    }

    private fun applyMerchantLotAllocation(operation: Rg10Operation.ApplyMerchantLotAllocation): Rg10ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (!input.merchantAllocationProvided) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_MERCHANT_EVIDENCE)
        }
        val evidenceItem = evidence.firstOrNull { it.id == input.merchantEvidenceId }
            ?: return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_MERCHANT_EVIDENCE)
        val plan = merchantConsumptionPlan(input.allocations, input.amount)
        if (plan == null) {
            return rejected(Rg10RejectionReason.LOT_ALLOCATION_EXCEEDS_REMAINING_FACE_VALUE, Rg10FieldPath.ATTEMPTED_AMOUNT)
        }
        // The op commits exactly one consumption id, so a multi-lot plan is structurally
        // unsupported; reject fail-closed before any lot state changes (D-083 GAP-05 spirit).
        if (plan.size != 1) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_MERCHANT_EVIDENCE)
        }
        if (
            allocations.any { it.id == operation.ids.allocationId } ||
            consumptions.any { it.id == operation.ids.consumptionId }
        ) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_MERCHANT_EVIDENCE)
        }
        plan.forEach { (lot, amount) ->
            val lotIndex = lots.indexOfFirst { it.id == lot.id }
            check(lotIndex >= 0) { "RG-10 merchant allocation references an unknown lot" }
            val current = lots[lotIndex]
            val remaining = checkedSubtract(current.remainingFaceValue.minorUnits, amount.minorUnits)
                ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_MERCHANT_EVIDENCE)
            lots[lotIndex] = current.copy(remainingFaceValue = Money.ofMinor(remaining, amount.currency))
        }
        val sourceId = evidenceItem.sourceId
        allocations += Rg10MerchantAllocation(
            id = operation.ids.allocationId,
            requestId = input.requestId,
            sourceId = sourceId,
            evidenceId = evidenceItem.id,
            lotId = plan.single().first.id,
            consumptionId = operation.ids.consumptionId,
            amount = input.amount,
            allocationSource = "merchant_evidence",
        )
        consumptions += Rg10LotConsumption(
            id = operation.ids.consumptionId,
            allocationId = operation.ids.allocationId,
            sourceId = sourceId,
            evidenceId = evidenceItem.id,
            lotId = plan.single().first.id,
            amount = input.amount,
            paidBonusComposition = "unknown",
        )
        return accepted(
            listOf(
                Rg10ReturnedId.Allocation(operation.ids.allocationId),
                Rg10ReturnedId.Consumption(operation.ids.consumptionId),
            ),
        )
    }

    private fun confirmActivationBalance(operation: Rg10Operation.ConfirmStoredValueActivationBalance): Rg10ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg10RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.compositionConfirmed) {
            return rejected(Rg10RejectionReason.PAID_BONUS_COMPOSITION_MUST_BE_EVIDENCED, Rg10FieldPath.INPUT_CONFIRMATION)
        }
        if (input.existingBalance.minorUnits <= 0L) {
            return rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_PAID_AMOUNT)
        }
        if (input.currency.code != "CNY" || input.existingBalance.currency != input.currency) {
            return rejected(Rg10RejectionReason.SAME_CNY_CURRENCY_REQUIRED, Rg10FieldPath.INPUT_CURRENCY)
        }
        val stored = catalogAccount(input.storedValueAccountId)
            ?: return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        storedAccountGuard(stored)?.let { return it }
        val activation = when (
            val result = createStoredValueActivationBalance(
                catalog,
                com.unifiedledger.domain.StoredValueActivationBalanceCommand(
                    ledgerId = operation.ledgerId,
                    storedValueAccountId = input.storedValueAccountId,
                    existingBalance = input.existingBalance,
                    times = TransactionTimes(
                        occurredAt = input.activationAt,
                        statisticsAt = input.activationAt,
                        effectiveAt = input.activationAt,
                    ),
                ),
                com.unifiedledger.domain.StoredValueActivationBalanceIds(
                    transactionId = operation.ids.transactionId,
                    versionId = operation.ids.versionId,
                    postingSetId = operation.ids.postingSetId,
                    storedValuePostingId = operation.ids.storedValuePostingId,
                    adjustmentEquityPostingId = operation.ids.equityPostingId,
                ),
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return domainRejected(result.violation, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        }
        val record = Rg10FormalTransactionRecord(
            activation.formalTransaction,
            input.createdAt,
            createdAtText = input.createdAtText,
            effectiveAtText = input.activationAtText,
        )
        if (
            confirmations.any { it.id == operation.ids.confirmationId } ||
            adjustments.any { it.id == operation.ids.adjustmentId } ||
            reconstructions.any { it.id == operation.ids.reconstructionId.value } ||
            sourceRecords.any { it.id == operation.ids.sourceId } ||
            evidence.any { it.id == operation.ids.evidenceId } ||
            evidenceLinks.any { it.id == operation.ids.linkId } ||
            auditLinks.any { it.id == operation.ids.auditLinkId } ||
            !canAppendFormalTransaction(record)
        ) {
            return rejected(Rg10RejectionReason.DOMAIN_REJECTED, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        }
        formalTransactions += record
        adjustments += Rg10ActivationAdjustment(
            id = operation.ids.adjustmentId,
            transactionId = operation.ids.transactionId,
            activationAt = input.activationAt,
            activationAtText = input.activationAtText,
            existingBalance = input.existingBalance,
            compositionStatus = "unknown",
            replacementStatus = "active_until_replaced",
            history = listOf(
                Rg10ActivationAdjustmentHistory(
                    id = operation.ids.adjustmentHistoryId,
                    event = "created",
                    transactionId = operation.ids.transactionId,
                    occurredAt = input.activationAt,
                    occurredAtText = input.activationAtText,
                    createdAt = input.createdAt,
                    createdAtText = input.createdAtText,
                ),
            ),
        )
        reconstructions += createStoredValueReconstruction(
            id = operation.ids.reconstructionId.value,
            replacementGroupId = operation.ids.replacementGroupId,
            adjustmentTransactionId = operation.ids.transactionId,
            reconstructedTransactionIds = emptyList(),
            createdAt = input.createdAt,
            createdAtText = input.createdAtText,
        )
        sourceRecords += Rg10SourceRecord(
            id = operation.ids.sourceId,
            sourceType = "user_confirmed_merchant_balance",
            observedAt = input.activationAt,
            observedAtText = input.activationAtText,
            accountId = input.storedValueAccountId,
            amount = input.existingBalance,
            immutablePayloadDigest = "sha256:rg10-activation-balance",
        )
        evidence += Rg10Evidence(
            id = operation.ids.evidenceId,
            sourceId = operation.ids.sourceId,
            evidenceType = "confirmed_activation_balance",
            observedAt = input.activationAt,
            observedAtText = input.activationAtText,
        )
        evidenceLinks += Rg10EvidenceLink(
            id = operation.ids.linkId,
            sourceId = operation.ids.sourceId,
            evidenceId = operation.ids.evidenceId,
            role = "stored_value_activation_balance_fact",
            targetKind = "DOMAIN_ENTITY",
            targetId = operation.ids.adjustmentId.value,
            status = "pending",
        )
        confirmations += Rg10Confirmation(
            id = operation.ids.confirmationId,
            requestId = input.requestId,
            role = "stored_value_activation_balance_confirmation",
            transactionId = operation.ids.transactionId,
            sourceId = operation.ids.sourceId,
            evidenceId = operation.ids.evidenceId,
            auditLinkId = operation.ids.auditLinkId,
            confirmedAt = input.createdAt,
            confirmedAtText = input.createdAtText,
            explicitConfirmation = true,
        )
        auditLinks += Rg10AuditLink(
            id = operation.ids.auditLinkId,
            role = "explicit_confirmation_provenance",
            sourceId = operation.ids.sourceId,
            evidenceId = operation.ids.evidenceId,
            confirmationId = operation.ids.confirmationId,
            transactionId = operation.ids.transactionId,
        )
        activation.postings.forEach { typed ->
            val role = when (typed.role) {
                StoredValueActivationBalancePostingRole.STORED_VALUE_CREDIT -> "STORED_VALUE_ASSET"
                StoredValueActivationBalancePostingRole.PRE_ACTIVATION_ADJUSTMENT_EQUITY -> "PRE_ACTIVATION_ADJUSTMENT_EQUITY"
            }
            val eligible = typed.role == StoredValueActivationBalancePostingRole.STORED_VALUE_CREDIT
            postingSemantics[typed.posting.id.value] = Rg10PostingSemantic(role, eligible)
            if (eligible) {
                postingReconciliation[typed.posting.id.value] = "pending"
            }
        }
        return accepted(
            listOf(
                Rg10ReturnedId.Transaction(operation.ids.transactionId),
                Rg10ReturnedId.Adjustment(operation.ids.adjustmentId),
                Rg10ReturnedId.Confirmation(operation.ids.confirmationId),
            ),
        )
    }

    private fun renameStoredValueLabels(operation: Rg10Operation.RenameStoredValueLabels): Rg10ExecutionResult {
        val input = operation.input
        if (catalogAccount(input.accountId) == null) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        }
        if (lots.none { it.id == input.lotId }) {
            return rejected(Rg10RejectionReason.INVALID_RG10_INPUT, Rg10FieldPath.INPUT_LOT)
        }
        return accepted(emptyList())
    }

    private fun rejectInvalidInput(operation: Rg10Operation.InvalidInput): Rg10ExecutionResult {
        val (reason, fieldPath) = when (operation.input.predicate) {
            Rg10InvalidPredicate.EXACT_DECIMAL_PAID ->
                Rg10RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg10FieldPath.ATTEMPTED_PAID_AMOUNT
            Rg10InvalidPredicate.EXACT_DECIMAL_CREDITED ->
                Rg10RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
            Rg10InvalidPredicate.EXACT_DECIMAL_BONUS ->
                Rg10RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT
            Rg10InvalidPredicate.PAID_POSITIVE ->
                Rg10RejectionReason.MUST_BE_POSITIVE to Rg10FieldPath.ATTEMPTED_PAID_AMOUNT
            Rg10InvalidPredicate.CREDITED_POSITIVE ->
                Rg10RejectionReason.CREDITED_AMOUNT_MUST_BE_POSITIVE to Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
            Rg10InvalidPredicate.BONUS_NON_NEGATIVE ->
                Rg10RejectionReason.BONUS_AMOUNT_MUST_BE_ZERO_OR_POSITIVE to Rg10FieldPath.ATTEMPTED_BONUS_AMOUNT
            Rg10InvalidPredicate.CREDITED_EQUALS_PAID_PLUS_BONUS ->
                Rg10RejectionReason.CREDITED_MUST_EQUAL_PAID_PLUS_BONUS to Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
            Rg10InvalidPredicate.COMPONENT_SUM_MATCH ->
                Rg10RejectionReason.COMPONENT_SUM_MISMATCH to Rg10FieldPath.ATTEMPTED_CREDITED_AMOUNT
            Rg10InvalidPredicate.STORED_ACCOUNT_ENABLED ->
                Rg10RejectionReason.STORED_VALUE_ACCOUNT_NOT_ENABLED to Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT
            Rg10InvalidPredicate.STORED_MODEL_ISOLATION ->
                Rg10RejectionReason.STORED_VALUE_MODELS_MUST_NOT_OVERLAP to Rg10FieldPath.ATTEMPTED_MODEL
            Rg10InvalidPredicate.EFFECTIVE_BALANCE_CAP ->
                Rg10RejectionReason.INSUFFICIENT_EFFECTIVE_STORED_BALANCE to Rg10FieldPath.ATTEMPTED_AMOUNT
            Rg10InvalidPredicate.LOT_ALLOCATION_CAP ->
                Rg10RejectionReason.LOT_ALLOCATION_EXCEEDS_REMAINING_FACE_VALUE to Rg10FieldPath.ATTEMPTED_AMOUNT
            Rg10InvalidPredicate.EXPIRY_EXPLICIT_CONFIRMATION ->
                Rg10RejectionReason.ACTUAL_EXPIRY_REQUIRES_EXPLICIT_CONFIRMATION to Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
            Rg10InvalidPredicate.COMPOSITION_EVIDENCED ->
                Rg10RejectionReason.PAID_BONUS_COMPOSITION_MUST_BE_EVIDENCED to Rg10FieldPath.ATTEMPTED_COMPOSITION
            Rg10InvalidPredicate.ACTIVE_SECONDARY_CATEGORY ->
                Rg10RejectionReason.ACTIVE_SECONDARY_CATEGORY_REQUIRED to Rg10FieldPath.ATTEMPTED_CATEGORY
            Rg10InvalidPredicate.KNOWN_PAYMENT_ACCOUNT ->
                Rg10RejectionReason.UNKNOWN_PAYMENT_ACCOUNT to Rg10FieldPath.ATTEMPTED_PAYMENT_ACCOUNT
            Rg10InvalidPredicate.OWNED_PAYMENT_ASSET ->
                Rg10RejectionReason.OWNED_PAYMENT_ASSET_REQUIRED to Rg10FieldPath.ATTEMPTED_PAYMENT_ACCOUNT
            Rg10InvalidPredicate.ENABLED_STORED_VALUE_ASSET ->
                Rg10RejectionReason.ENABLED_RESTRICTED_STORED_VALUE_ASSET_REQUIRED to Rg10FieldPath.ATTEMPTED_STORED_VALUE_ACCOUNT
            Rg10InvalidPredicate.SAME_CNY_CURRENCY ->
                Rg10RejectionReason.SAME_CNY_CURRENCY_REQUIRED to Rg10FieldPath.ATTEMPTED_CURRENCY
            Rg10InvalidPredicate.IMPORT_RECHARGE_CONFIRMATION ->
                Rg10RejectionReason.BANK_PAYMENT_MODEL_AND_ALL_RECHARGE_FACTS_REQUIRED to Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
            Rg10InvalidPredicate.IMPORT_SPEND_CONFIRMATION ->
                Rg10RejectionReason.SPEND_CATEGORY_AND_BEHAVIOR_CONFIRMATION_REQUIRED to Rg10FieldPath.ATTEMPTED_EXPLICIT_CONFIRMATION
        }
        return rejected(reason, fieldPath)
    }

    private fun storedAccountGuard(account: Account): Rg10ExecutionResult? {
        val config = account.storedValue
        if (config == null || account.kind != AccountKind.ASSET || !account.ownedByUser || !account.realAccount || !config.merchantRestricted) {
            return rejected(Rg10RejectionReason.ENABLED_RESTRICTED_STORED_VALUE_ASSET_REQUIRED, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        }
        if (!config.enabled) {
            return rejected(Rg10RejectionReason.STORED_VALUE_ACCOUNT_NOT_ENABLED, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
        }
        return null
    }

    private fun storedAccountIdForLot(lot: StoredValueLot): AccountId {
        val recharge = formalTransactions.firstOrNull {
            it.formalTransaction.transaction.id == lot.rechargeTransactionId
        } ?: error("RG-10 lot has no recharge transaction")
        val storedPosting = recharge.formalTransaction.currentPostings()
            .first { it.accountId.let { accountId -> postingSemantics[it.id.value]?.role == "STORED_VALUE_ASSET" } }
        return storedPosting.accountId
    }

    private fun defaultConsumptionPlan(amount: Money): List<Pair<StoredValueLot, Money>>? {
        var remaining = amount.minorUnits
        val plan = mutableListOf<Pair<StoredValueLot, Money>>()
        for (lot in defaultLotOrder(lots)) {
            if (remaining <= 0L) break
            val take = minOf(remaining, lot.remainingFaceValue.minorUnits)
            if (take > 0L) {
                plan += lot to Money.ofMinor(take, amount.currency)
                remaining = checkedSubtract(remaining, take) ?: return null
            }
        }
        return if (remaining == 0L && plan.isNotEmpty()) plan else null
    }

    private fun merchantConsumptionPlan(
        allocations: List<Rg10LotAllocationInput>,
        amount: Money,
    ): List<Pair<StoredValueLot, Money>>? {
        if (allocations.isEmpty()) return null
        var total = 0L
        val plan = mutableListOf<Pair<StoredValueLot, Money>>()
        val seen = mutableSetOf<StoredValueLotId>()
        for (allocation in allocations) {
            if (!seen.add(allocation.lotId)) return null
            if (allocation.amount.minorUnits <= 0L) return null
            if (allocation.amount.currency != amount.currency) return null
            val lot = lots.firstOrNull { it.id == allocation.lotId } ?: return null
            if (allocation.amount.minorUnits > lot.remainingFaceValue.minorUnits) return null
            total = checkedAdd(total, allocation.amount.minorUnits) ?: return null
            plan += lot to allocation.amount
        }
        return if (total == amount.minorUnits) plan else null
    }

    private fun domainRejected(
        violation: com.unifiedledger.domain.DomainViolation,
        fieldPath: Rg10FieldPath,
    ): Rg10ExecutionResult {
        return when (violation) {
            is StoredValueViolation.PaidAmountMustBePositive ->
                rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_PAID_AMOUNT)
            is StoredValueViolation.CreditedAmountMustBePositive ->
                rejected(Rg10RejectionReason.CREDITED_AMOUNT_MUST_BE_POSITIVE, Rg10FieldPath.INPUT_CREDITED_AMOUNT)
            is StoredValueViolation.BonusAmountMustBeZeroOrPositive ->
                rejected(Rg10RejectionReason.BONUS_AMOUNT_MUST_BE_ZERO_OR_POSITIVE, Rg10FieldPath.INPUT_BONUS_AMOUNT)
            is StoredValueViolation.CreditedMustEqualPaidPlusBonus ->
                rejected(Rg10RejectionReason.CREDITED_MUST_EQUAL_PAID_PLUS_BONUS, Rg10FieldPath.INPUT_CREDITED_AMOUNT)
            is StoredValueViolation.SameCurrencyRequired ->
                rejected(Rg10RejectionReason.SAME_CNY_CURRENCY_REQUIRED, Rg10FieldPath.INPUT_CURRENCY)
            is StoredValueViolation.KnownAccountRequired ->
                rejected(Rg10RejectionReason.DOMAIN_REJECTED, fieldPath)
            is StoredValueViolation.EnabledRestrictedStoredValueAssetRequired ->
                rejected(Rg10RejectionReason.ENABLED_RESTRICTED_STORED_VALUE_ASSET_REQUIRED, Rg10FieldPath.INPUT_STORED_VALUE_ACCOUNT)
            is StoredValueViolation.OwnedPaymentAssetRequired ->
                rejected(Rg10RejectionReason.OWNED_PAYMENT_ASSET_REQUIRED, Rg10FieldPath.INPUT_PAYMENT_ACCOUNT)
            is StoredValueViolation.ActiveSecondaryCategoryRequired ->
                rejected(Rg10RejectionReason.ACTIVE_SECONDARY_CATEGORY_REQUIRED, Rg10FieldPath.INPUT_CATEGORY)
            is StoredValueViolation.AmountMustBePositive ->
                rejected(Rg10RejectionReason.MUST_BE_POSITIVE, Rg10FieldPath.INPUT_AMOUNT)
            else -> rejected(Rg10RejectionReason.DOMAIN_REJECTED, fieldPath)
        }
    }

    private fun catalogAccount(id: AccountId) = catalog.accounts.firstOrNull { it.id == id }

    private fun replayBalances(): Map<AccountId, Money> = buildMap {
        catalog.accounts.forEach { account ->
            var total = 0L
            formalTransactions
                .filter { it.formalTransaction.transaction.ledgerId == account.ledgerId }
                .forEach { record ->
                    record.formalTransaction.currentPostings()
                        .filter { it.accountId == account.id }
                        .forEach { posting ->
                            check(posting.amount.currency == account.currency) { "RG-10 posting currency mismatch" }
                            total = checkedAdd(total, posting.amount.minorUnits) ?: error("RG-10 balance overflow")
                        }
                }
            put(account.id, Money.ofMinor(total, account.currency))
        }
    }

    private fun reports(): Map<String, Rg10Report> {
        var cumulative = Rg10Report()
        formalTransactions.forEach { record ->
            val report = when (record.formalTransaction.transaction.kind) {
                TransactionKind.STORED_VALUE_RECHARGE -> {
                    val postings = record.formalTransaction.currentPostings()
                    val paid = postings.first { postingSemantics[it.id.value]?.role == "PAYMENT_OUT" }.amount.minorUnits
                    val bonus = postings.first { postingSemantics[it.id.value]?.role == "BONUS_INCOME" }.amount.minorUnits
                    Rg10Report(
                        specialNonCashBonusIncomeMinor = -bonus,
                        cashOutflowMinor = -paid,
                        netWorthChangeMinor = -bonus,
                    )
                }
                TransactionKind.STORED_VALUE_SPEND -> {
                    val amount = record.formalTransaction.currentPostings()
                        .first { postingSemantics[it.id.value]?.role == "EXPENSE_OUT" }.amount.minorUnits
                    Rg10Report(
                        ordinaryExpenseMinor = amount,
                        consumptionMinor = amount,
                        categoryEffectMinor = amount,
                        netWorthChangeMinor = -amount,
                    )
                }
                TransactionKind.STORED_VALUE_EXPIRY_LOSS -> {
                    val amount = record.formalTransaction.currentPostings()
                        .first { postingSemantics[it.id.value]?.role == "EXPIRY_LOSS" }.amount.minorUnits
                    Rg10Report(
                        expiryLossMinor = amount,
                        netWorthChangeMinor = -amount,
                    )
                }
                TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT -> {
                    val amount = record.formalTransaction.currentPostings()
                        .first { postingSemantics[it.id.value]?.role == "STORED_VALUE_ASSET" }.amount.minorUnits
                    Rg10Report(netWorthChangeMinor = amount)
                }
                else -> null
            }
            if (report != null) {
                cumulative = Rg10Report(
                    ordinaryIncomeMinor = checkedAdd(cumulative.ordinaryIncomeMinor, report.ordinaryIncomeMinor)!!,
                    specialNonCashBonusIncomeMinor = checkedAdd(cumulative.specialNonCashBonusIncomeMinor, report.specialNonCashBonusIncomeMinor)!!,
                    ordinaryExpenseMinor = checkedAdd(cumulative.ordinaryExpenseMinor, report.ordinaryExpenseMinor)!!,
                    expiryLossMinor = checkedAdd(cumulative.expiryLossMinor, report.expiryLossMinor)!!,
                    consumptionMinor = checkedAdd(cumulative.consumptionMinor, report.consumptionMinor)!!,
                    budgetEffectMinor = checkedAdd(cumulative.budgetEffectMinor, report.budgetEffectMinor)!!,
                    categoryEffectMinor = checkedAdd(cumulative.categoryEffectMinor, report.categoryEffectMinor)!!,
                    cashInflowMinor = checkedAdd(cumulative.cashInflowMinor, report.cashInflowMinor)!!,
                    cashOutflowMinor = checkedAdd(cumulative.cashOutflowMinor, report.cashOutflowMinor)!!,
                    netWorthChangeMinor = checkedAdd(cumulative.netWorthChangeMinor, report.netWorthChangeMinor)!!,
                )
            }
        }
        return mapOf("cumulative" to cumulative)
    }

    private fun canAppendFormalTransaction(record: Rg10FormalTransactionRecord): Boolean {
        if (!catalogCompatible(record.formalTransaction) || formalIdCollision(record.formalTransaction)) {
            return false
        }
        val currentBalances = replayBalances()
        record.formalTransaction.currentPostings()
            .groupBy { it.accountId }
            .forEach { (accountId, postings) ->
                var total = currentBalances[accountId]?.minorUnits ?: return false
                postings.forEach { posting ->
                    total = checkedAdd(total, posting.amount.minorUnits) ?: return false
                }
            }
        return true
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

    private fun currentEffectiveAt(formal: FormalTransaction): Instant =
        formal.versions.first { it.id == formal.transaction.currentVersionId }.times.effectiveAt

    private fun canonicalInput(operation: Rg10Operation): String = when (operation) {
        is Rg10Operation.ConfirmStoredValueRecharge -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.model,
            operation.input.paymentAccountId.value,
            operation.input.storedValueAccountId.value,
            canonicalMoney(operation.input.paidAmount),
            canonicalMoney(operation.input.creditedAmount),
            canonicalMoney(operation.input.bonusAmount),
            canonicalCurrency(operation.input.currency),
            operation.input.occurredAt.toString(),
            operation.input.occurredAtText,
            operation.input.createdAt.toString(),
            operation.input.createdAtText,
            operation.input.explicitConfirmation.toString(),
            operation.input.confirmsModel.toString(),
            operation.input.confirmsPaymentAccount.toString(),
            operation.input.confirmsStoredValueAccount.toString(),
            operation.input.confirmsPaidAmount.toString(),
            operation.input.confirmsCreditedAmount.toString(),
            operation.input.confirmsBonusAmount.toString(),
            operation.input.confirmsActualTime.toString(),
            operation.input.confirmsLotFacts.toString(),
            operation.input.expiresAt.toString(),
            operation.input.expiresAtText,
            operation.input.merchantId,
            operation.input.merchantCreditObservedAtText,
            operation.ids.transactionId.value,
            operation.ids.versionId.value,
            operation.ids.postingSetId.value,
            operation.ids.storedValuePostingId.value,
            operation.ids.paymentPostingId.value,
            operation.ids.bonusIncomePostingId.value,
            operation.ids.lotId.value,
            operation.ids.lotHistoryId,
            operation.ids.confirmationId.value,
            operation.ids.bankSourceId.value,
            operation.ids.merchantSourceId.value,
            operation.ids.bankEvidenceId.value,
            operation.ids.merchantEvidenceId.value,
            operation.ids.bankLinkId.value,
            operation.ids.merchantPostingLinkId.value,
            operation.ids.merchantLotLinkId.value,
            operation.ids.bonusLinkId.value,
        )
        is Rg10Operation.ConfirmStoredValueSpend -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.model,
            operation.input.behavior,
            operation.input.storedValueAccountId.value,
            operation.input.categoryId.value,
            canonicalMoney(operation.input.amount),
            canonicalCurrency(operation.input.currency),
            operation.input.occurredAt.toString(),
            operation.input.occurredAtText,
            operation.input.createdAt.toString(),
            operation.input.createdAtText,
            operation.input.explicitConfirmation.toString(),
            operation.input.confirmsModel.toString(),
            operation.input.confirmsBehavior.toString(),
            operation.input.confirmsStoredValueAccount.toString(),
            operation.input.confirmsAmount.toString(),
            operation.input.confirmsActualTime.toString(),
            operation.input.confirmsCategory.toString(),
            operation.input.merchantAllocationProvided.toString(),
            operation.input.confirmsLotAllocation.toString(),
            operation.input.allocations.joinToString("|") { "${it.lotId.value}=${canonicalMoney(it.amount)}" },
            operation.ids.transactionId.value,
            operation.ids.versionId.value,
            operation.ids.postingSetId.value,
            operation.ids.expensePostingId.value,
            operation.ids.storedValuePostingId.value,
            operation.ids.confirmationId.value,
            operation.ids.consumptions.joinToString("|") { it.value },
            operation.ids.lotHistoryIds.joinToString("|"),
        )
        is Rg10Operation.IngestStoredValueRechargeCandidate -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.model,
            operation.input.paymentAccountId.value,
            operation.input.storedValueAccountId.value,
            canonicalMoney(operation.input.paidAmount),
            canonicalMoney(operation.input.creditedAmount),
            canonicalMoney(operation.input.bonusAmount),
            canonicalCurrency(operation.input.currency),
            operation.input.occurredAt.toString(),
            operation.input.occurredAtText,
            operation.input.lotId?.value,
            operation.input.allFactsComplete.toString(),
            operation.input.explicitConfirmation.toString(),
            operation.ids.candidateId.value,
            operation.ids.sourceId.value,
            operation.ids.evidenceId.value,
        )
        is Rg10Operation.IngestStoredValueSpendCandidate -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.model,
            operation.input.behavior,
            operation.input.storedValueAccountId.value,
            operation.input.categoryId.value,
            canonicalMoney(operation.input.amount),
            canonicalCurrency(operation.input.currency),
            operation.input.occurredAt.toString(),
            operation.input.occurredAtText,
            operation.input.lotAllocations.joinToString("|") { "${it.lotId.value}=${canonicalMoney(it.amount)}" },
            operation.input.allFactsComplete.toString(),
            operation.input.explicitConfirmation.toString(),
            operation.ids.candidateId.value,
            operation.ids.sourceId.value,
            operation.ids.evidenceId.value,
        )
        is Rg10Operation.ConfirmImportedStoredValueRecharge -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.merchantCreditAmount?.let(::canonicalMoney),
            operation.input.merchantSourceId?.value,
            operation.input.bankPaymentConfirmed?.toString(),
            operation.input.modelConfirmed?.toString(),
            operation.input.explicitConfirmation?.toString(),
        )
        is Rg10Operation.ConfirmImportedStoredValueSpend -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.storedValueAccountId?.value,
            operation.input.amount?.let(::canonicalMoney),
            operation.input.actualTime?.toString(),
            operation.input.actualTimeText,
            operation.input.categoryConfirmed?.toString(),
            operation.input.lotAllocationConfirmed?.toString(),
            operation.input.explicitConfirmation?.toString(),
        )
        is Rg10Operation.RecordExpiryReminder -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.lotId.value,
            operation.input.reminderStatus,
            operation.input.explicitConfirmation.toString(),
        )
        is Rg10Operation.ConfirmStoredValueExpiryLoss -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.lotId.value,
            canonicalMoney(operation.input.amount),
            canonicalCurrency(operation.input.currency),
            operation.input.occurredAt.toString(),
            operation.input.occurredAtText,
            operation.input.confirmedAt.toString(),
            operation.input.confirmedAtText,
            operation.input.explicitConfirmation.toString(),
            operation.input.confirmsActualExpiry.toString(),
            operation.input.confirmsLot.toString(),
            operation.input.confirmsAmount.toString(),
            operation.ids.transactionId.value,
            operation.ids.versionId.value,
            operation.ids.postingSetId.value,
            operation.ids.expiryLossPostingId.value,
            operation.ids.storedValuePostingId.value,
            operation.ids.confirmationId.value,
            operation.ids.sourceId.value,
            operation.ids.evidenceId.value,
            operation.ids.linkId.value,
            operation.ids.lotHistoryId,
        )
        is Rg10Operation.ReconcileMerchantCredit -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.sourceId.value,
            operation.input.evidenceId.value,
            operation.input.role,
            operation.input.targetPostingId.value,
            operation.input.explicitConfirmation.toString(),
        )
        is Rg10Operation.ReconcileBankPayment -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.sourceId.value,
            operation.input.evidenceId.value,
            operation.input.role,
            operation.input.targetPostingId.value,
            operation.input.explicitConfirmation.toString(),
        )
        is Rg10Operation.ApplyMerchantLotAllocation -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            canonicalMoney(operation.input.amount),
            operation.input.merchantAllocationProvided.toString(),
            operation.input.merchantEvidenceId.value,
            operation.input.allocations.joinToString("|") { "${it.lotId.value}=${canonicalMoney(it.amount)}" },
            operation.input.explicitConfirmation.toString(),
            operation.ids.allocationId.value,
            operation.ids.consumptionId.value,
        )
        is Rg10Operation.ConfirmStoredValueActivationBalance -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.requestId.value,
            operation.input.storedValueAccountId.value,
            canonicalMoney(operation.input.existingBalance),
            canonicalCurrency(operation.input.currency),
            operation.input.activationAt.toString(),
            operation.input.activationAtText,
            operation.input.createdAt.toString(),
            operation.input.createdAtText,
            operation.input.explicitConfirmation.toString(),
            operation.input.compositionConfirmed.toString(),
            operation.ids.transactionId.value,
            operation.ids.versionId.value,
            operation.ids.postingSetId.value,
            operation.ids.storedValuePostingId.value,
            operation.ids.equityPostingId.value,
            operation.ids.confirmationId.value,
            operation.ids.adjustmentId.value,
            operation.ids.adjustmentHistoryId,
            operation.ids.reconstructionId.value,
            operation.ids.replacementGroupId,
            operation.ids.sourceId.value,
            operation.ids.evidenceId.value,
            operation.ids.linkId.value,
            operation.ids.auditLinkId.value,
        )
        is Rg10Operation.RenameStoredValueLabels -> canonicalFields(
            operation.ledgerId.value,
            operation.action.code,
            operation.input.accountId.value,
            operation.input.newAccountName,
            operation.input.lotId.value,
            operation.input.newLotLabel,
        )
        is Rg10Operation.InvalidInput -> canonicalFields(
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

    private fun accepted(ids: List<Rg10ReturnedId>) = Rg10ExecutionResult.Accepted(ids)

    private fun rejected(reason: Rg10RejectionReason, fieldPath: Rg10FieldPath) =
        Rg10ExecutionResult.Rejected(reason, fieldPath)
}

private fun checkedAdd(left: Long, right: Long): Long? {
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedSubtract(left: Long, right: Long): Long? = checkedAdd(left, checkedNegate(right) ?: return null)

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
