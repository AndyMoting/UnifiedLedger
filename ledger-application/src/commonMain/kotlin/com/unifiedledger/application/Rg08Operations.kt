package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingAuditLink
import com.unifiedledger.domain.LendingBehaviorCode
import com.unifiedledger.domain.LendingCandidate
import com.unifiedledger.domain.LendingCandidateStatus
import com.unifiedledger.domain.LendingCandidateStatusHistoryEntry
import com.unifiedledger.domain.LendingComponentKind
import com.unifiedledger.domain.LendingConfirmationGateField
import com.unifiedledger.domain.LendingConfirmationProvenance
import com.unifiedledger.domain.LendingConfirmationRole
import com.unifiedledger.domain.LendingEvidence
import com.unifiedledger.domain.LendingEvidenceLink
import com.unifiedledger.domain.LendingEvidenceLinkRole
import com.unifiedledger.domain.LendingEvidenceLinkStatus
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.LendingPositionHistoryEntry
import com.unifiedledger.domain.LendingSettlement
import com.unifiedledger.domain.LendingSettlementComponent
import com.unifiedledger.domain.LendingSettlementHistoryEntry
import com.unifiedledger.domain.LendingSettlementStatus
import com.unifiedledger.domain.LendingSourceKind
import com.unifiedledger.domain.LendingSourceRecord
import com.unifiedledger.domain.LendingViolation
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
import com.unifiedledger.domain.createLendingCandidate
import com.unifiedledger.domain.createLendingConfirmationProvenance
import com.unifiedledger.domain.createLendingEvidence
import com.unifiedledger.domain.createLendingEvidenceLink
import com.unifiedledger.domain.createLendingPosition
import com.unifiedledger.domain.createLendingSettlement
import com.unifiedledger.domain.createLendingSourceRecord
import kotlin.time.Instant

data class Rg08CandidateId(
    val value: String,
)

data class Rg08SourceRecordId(
    val value: String,
)

data class Rg08EvidenceId(
    val value: String,
)

data class Rg08EvidenceLinkId(
    val value: String,
)

data class Rg08SettlementId(
    val value: String,
)

data class Rg08ConfirmationId(
    val value: String,
)

data class Rg08AuditLinkId(
    val value: String,
)

data class Rg08OperationIdentity(
    val ledgerId: LedgerId,
    val value: String,
)

/**
 * D-084 RG08-GAP-01 closed action registry. The five operation classes of the approved
 * lending registry; the frozen fixture additionally drives rename and mirror/merge through
 * these classes (rename: [Rg08Action.VALIDATE_LENDING_EVENT] with zero formal/intake effect,
 * mirror merge: [Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION] with typed audit links).
 */
enum class Rg08Action(
    val code: String,
) {
    VALIDATE_LENDING_EVENT("validate_lending_event"),
    VALIDATE_LENDING_SETTLEMENT("validate_lending_settlement"),
    CONFIRM_IMPORTED_LENDING_COLLECTION("confirm_imported_lending_collection"),
    ALLOCATE_LENDING_COLLECTION("allocate_lending_collection"),
    RETRY_IDEMPOTENT_INPUT("retry_idempotent_input"),
}

enum class Rg08InvalidPredicate {
    EXACT_DECIMAL_TOTAL,
    TOTAL_POSITIVE,
    COMPONENTS_EQUAL_TOTAL,
    COMPONENT_NONNEGATIVE,
    FEE_ZERO,
    FEE_OUT_OF_SCOPE,
    PRINCIPAL_EXCEEDS_OUTSTANDING,
    UNKNOWN_DESTINATION,
    UNOWNED_DESTINATION,
    NONFINANCIAL_DESTINATION,
    UNKNOWN_FUNDING_ACCOUNT,
    UNKNOWN_COUNTERPARTY,
    INVALID_BEHAVIOR,
    EXPLICIT_COMPONENT_SPLIT,
    SAME_CURRENCY,
    ACTIVE_EXACT_INTEREST_CATEGORY,
}

data class Rg08InvalidInput(
    val requestId: RequestId,
    val action: Rg08Action,
    val predicate: Rg08InvalidPredicate,
    val attemptedInput: Map<String, String?>,
)

/**
 * D-084 RG08-GAP-02/04 lend input. `actual_at` is the explicit economic time of the principal
 * cash outflow and `confirmed_at` the explicit confirmation time; neither is derived from the
 * other (fail-closed, RG08-GAP-04).
 */
data class Rg08LendInput(
    val requestId: RequestId,
    val behaviorCode: String,
    val counterpartyId: String,
    val fundingAccountId: AccountId,
    val principalAmount: Money,
    val currency: CurrencyUnit,
    val actualAt: Instant,
    val actualAtText: String = actualAt.toString(),
    val confirmedAt: Instant,
    val confirmedAtText: String = confirmedAt.toString(),
    val explicitConfirmation: Boolean,
)

data class Rg08LendIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val receivablePostingId: PostingId,
    val fundingPostingId: PostingId,
    val positionId: String,
    val positionHistoryId: String,
    val confirmationId: Rg08ConfirmationId,
    val sourceId: Rg08SourceRecordId,
    val sourceRecordId: String,
    val sourceObservedAt: Instant,
    val sourceObservedAtText: String,
    val sourceAmountMinor: Long,
    val evidenceId: Rg08EvidenceId,
    val evidenceLinkId: Rg08EvidenceLinkId,
)

/**
 * D-084 RG08-GAP-02/03/04 collection input. `allocated_lend_transaction_id` is null in v1;
 * fee is fixed to `0.00`; `actual_receipt_at` and `confirmed_at` are independent explicit
 * economic/confirmation times.
 */
data class Rg08SettlementInput(
    val requestId: RequestId,
    val behaviorCode: String,
    val counterpartyId: String,
    val linkedPositionId: String?,
    val allocatedLendTransactionId: TransactionId? = null,
    val destinationAccountId: AccountId,
    val totalReceived: Money,
    val principalAmount: Money,
    val interestAmount: Money,
    val feeAmount: Money,
    val interestCategoryId: CategoryId,
    val currency: CurrencyUnit,
    val actualReceiptAt: Instant,
    val actualReceiptAtText: String = actualReceiptAt.toString(),
    val confirmedAt: Instant,
    val confirmedAtText: String = confirmedAt.toString(),
    val explicitConfirmation: Boolean,
)

/**
 * Collection commit ids. The optional destination-evidence ids are present on the manual path
 * (bank credit + explicit manual confirmation source/evidence/link) and null on the cap path,
 * which stays evidence-pending.
 */
data class Rg08SettlementIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val destinationPostingId: PostingId,
    val principalPostingId: PostingId,
    val interestPostingId: PostingId,
    val settlementId: String,
    val settlementHistoryId: String,
    val positionHistoryId: String,
    val confirmationId: Rg08ConfirmationId,
    val principalComponentId: String,
    val interestComponentId: String,
    val feeComponentId: String,
    val confirmationSourceId: Rg08SourceRecordId? = null,
    val confirmationSourceRecordId: String? = null,
    val confirmationSourceObservedAt: Instant? = null,
    val confirmationSourceObservedAtText: String? = null,
    val creditSourceId: Rg08SourceRecordId? = null,
    val creditSourceRecordId: String? = null,
    val creditSourceObservedAt: Instant? = null,
    val creditSourceObservedAtText: String? = null,
    val creditSourceBookingAt: Instant? = null,
    val creditSourceBookingAtText: String? = null,
    val creditSourceValueAt: Instant? = null,
    val creditSourceValueAtText: String? = null,
    val creditEvidenceId: Rg08EvidenceId? = null,
    val creditEvidenceLinkId: Rg08EvidenceLinkId? = null,
)

/**
 * D-084 RG08-GAP-03/04 imported candidate intake. Bank credit and agreement sources, their
 * evidence and the counterparty relationship link are ingested together with the pending
 * candidate; nothing auto-confirms (the three flags stay false).
 */
data class Rg08ImportIntakeInput(
    val creditSourceId: Rg08SourceRecordId,
    val creditSourceRecordId: String,
    val creditObservedAt: Instant,
    val creditObservedAtText: String,
    val creditBookingAt: Instant,
    val creditBookingAtText: String,
    val creditValueAt: Instant,
    val creditValueAtText: String,
    val creditAccountId: AccountId,
    val creditAmountMinor: Long,
    val creditCurrency: CurrencyUnit,
    val creditOriginalSourcePayloadHash: String,
    val creditImmutablePayloadHash: String,
    val agreementSourceId: Rg08SourceRecordId,
    val agreementSourceRecordId: String,
    val agreementObservedAt: Instant,
    val agreementObservedAtText: String,
    val agreementCounterpartyId: String,
    val agreementCurrency: CurrencyUnit,
    val agreementImmutablePayloadHash: String,
    val candidateId: Rg08CandidateId,
    val candidateType: String,
    val proposedTotalReceivedMinor: Long,
    val proposedDestinationAccountId: AccountId,
    val proposedActualReceiptAt: Instant,
    val proposedActualReceiptAtText: String,
    val ruleVersion: Int,
    val confidence: String,
)

data class Rg08ImportIntakeIds(
    val creditEvidenceId: Rg08EvidenceId,
    val agreementEvidenceId: Rg08EvidenceId,
    val agreementEvidenceLinkId: Rg08EvidenceLinkId,
    val candidateHistoryId: String,
)

/** Per-gate rejection of an incomplete imported confirmation; zero formal effect. */
data class Rg08IncompleteConfirmationInput(
    val requestId: RequestId,
    val candidateId: Rg08CandidateId,
    val missingField: LendingConfirmationGateField,
)

/**
 * D-084 RG08-GAP-03 full imported confirmation. All six gates must be explicitly confirmed;
 * the confirmed candidate is rebuilt with the confirmed proposed facts and append-only history.
 */
data class Rg08ConfirmImportedInput(
    val requestId: RequestId,
    val candidateId: Rg08CandidateId,
    val behaviorCode: String,
    val counterpartyId: String,
    val destinationAccountId: AccountId,
    val principalAmount: Money,
    val interestAmount: Money,
    val feeAmount: Money,
    val interestCategoryId: CategoryId,
    val currency: CurrencyUnit,
    val actualReceiptAt: Instant,
    val actualReceiptAtText: String = actualReceiptAt.toString(),
    val confirmedAt: Instant,
    val confirmedAtText: String = confirmedAt.toString(),
    val explicitConfirmation: Boolean,
    val explicitlyConfirmedFields: Set<String>,
)

data class Rg08ConfirmImportedIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val destinationPostingId: PostingId,
    val principalPostingId: PostingId,
    val interestPostingId: PostingId,
    val settlementId: String,
    val settlementHistoryId: String,
    val positionHistoryId: String,
    val confirmationId: Rg08ConfirmationId,
    val principalComponentId: String,
    val interestComponentId: String,
    val feeComponentId: String,
    val candidateConfirmedHistoryId: String,
    val destinationEvidenceLinkId: Rg08EvidenceLinkId,
)

/**
 * D-062/D-084 principal-cap allocation. A collection whose principal exceeds the outstanding
 * receivable is rejected atomically (`principal_exceeds_outstanding_position`) and stays
 * pending explicit reallocation; no auto cap, no cross-zero, no guessed income.
 */
data class Rg08AllocateInput(
    val requestId: RequestId,
    val counterpartyId: String,
    val destinationAccountId: AccountId,
    val totalReceived: Money,
    val principalAmount: Money,
    val interestAmount: Money,
    val feeAmount: Money,
    val currency: CurrencyUnit,
    val interestCategoryId: CategoryId? = null,
    val actualReceiptAt: Instant? = null,
    val actualReceiptAtText: String? = null,
    val confirmedAt: Instant? = null,
    val confirmedAtText: String? = null,
    val ids: Rg08SettlementIds,
)

/**
 * D-084 RG08-GAP-03 mirror/merge. The bank credit mirror source and evidence merge into the
 * exact destination posting evidence link through typed audit links; zero formal effect.
 */
data class Rg08MergeInput(
    val requestId: RequestId,
    val sourceId: Rg08SourceRecordId,
    val sourceRecordId: String,
    val observedAt: Instant,
    val observedAtText: String,
    val amountMinor: Long,
    val currency: CurrencyUnit,
    val mirrorOfSourceId: String,
    val immutablePayloadHash: String,
    val evidenceId: Rg08EvidenceId,
    val evidenceLinkId: Rg08EvidenceLinkId,
    val targetPostingId: PostingId,
    val mirrorOfEvidenceId: String,
    val mergedIntoEvidenceLinkId: String,
)

/** D-062 counterparty rename: display name only, zero balance/history effect. */
data class Rg08RenameInput(
    val requestId: RequestId,
    val counterpartyId: String,
    val oldDisplayName: String,
    val newDisplayName: String,
    val nameHistoryId: String,
)

/** Generic retry (D-084 explicit deviation from D-083): replays by input anchor id. */
data class Rg08RetryInput(
    val inputId: String,
)

sealed interface Rg08Operation {
    val ledgerId: LedgerId
    val action: Rg08Action
    val identity: Rg08OperationIdentity

    data class ValidateLendingEvent(
        override val ledgerId: LedgerId,
        val input: Rg08LendInput,
        val ids: Rg08LendIds,
    ) : Rg08Operation {
        override val action = Rg08Action.VALIDATE_LENDING_EVENT
        override val identity = Rg08OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ValidateLendingSettlement(
        override val ledgerId: LedgerId,
        val input: Rg08SettlementInput,
        val ids: Rg08SettlementIds,
    ) : Rg08Operation {
        override val action = Rg08Action.VALIDATE_LENDING_SETTLEMENT
        override val identity = Rg08OperationIdentity(ledgerId, input.requestId.value)
    }

    data class IngestImportedCollectionCandidate(
        override val ledgerId: LedgerId,
        val input: Rg08ImportIntakeInput,
        val ids: Rg08ImportIntakeIds,
    ) : Rg08Operation {
        override val action = Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION
        override val identity = Rg08OperationIdentity(ledgerId, input.creditSourceId.value)
    }

    data class RejectIncompleteImportedConfirmation(
        override val ledgerId: LedgerId,
        val input: Rg08IncompleteConfirmationInput,
    ) : Rg08Operation {
        override val action = Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION
        override val identity = Rg08OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmImportedCollection(
        override val ledgerId: LedgerId,
        val input: Rg08ConfirmImportedInput,
        val ids: Rg08ConfirmImportedIds,
    ) : Rg08Operation {
        override val action = Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION
        override val identity = Rg08OperationIdentity(ledgerId, input.requestId.value)
    }

    data class AllocateLendingCollection(
        override val ledgerId: LedgerId,
        val input: Rg08AllocateInput,
    ) : Rg08Operation {
        override val action = Rg08Action.ALLOCATE_LENDING_COLLECTION
        override val identity = Rg08OperationIdentity(ledgerId, input.requestId.value)
    }

    data class MergeImportedEvidence(
        override val ledgerId: LedgerId,
        val input: Rg08MergeInput,
    ) : Rg08Operation {
        override val action = Rg08Action.CONFIRM_IMPORTED_LENDING_COLLECTION
        override val identity = Rg08OperationIdentity(ledgerId, input.sourceId.value)
    }

    data class RenameCounterparty(
        override val ledgerId: LedgerId,
        val input: Rg08RenameInput,
    ) : Rg08Operation {
        override val action = Rg08Action.VALIDATE_LENDING_EVENT
        override val identity = Rg08OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RetryIdempotentInput(
        override val ledgerId: LedgerId,
        val input: Rg08RetryInput,
    ) : Rg08Operation {
        override val action = Rg08Action.RETRY_IDEMPOTENT_INPUT
        override val identity = Rg08OperationIdentity(ledgerId, input.inputId)
    }

    data class InvalidInput(
        override val ledgerId: LedgerId,
        val input: Rg08InvalidInput,
    ) : Rg08Operation {
        override val action = input.action
        override val identity = Rg08OperationIdentity(ledgerId, input.requestId.value)
    }
}

sealed interface Rg08ReturnedId {
    data class Transaction(
        val id: TransactionId,
    ) : Rg08ReturnedId

    data class Version(
        val id: TransactionVersionId,
    ) : Rg08ReturnedId

    data class Position(
        val id: String,
    ) : Rg08ReturnedId

    data class Settlement(
        val id: String,
    ) : Rg08ReturnedId

    data class Component(
        val id: String,
    ) : Rg08ReturnedId

    data class Candidate(
        val id: Rg08CandidateId,
    ) : Rg08ReturnedId

    data class SourceRecord(
        val id: Rg08SourceRecordId,
    ) : Rg08ReturnedId

    data class Evidence(
        val id: Rg08EvidenceId,
    ) : Rg08ReturnedId

    data class EvidenceLink(
        val id: Rg08EvidenceLinkId,
    ) : Rg08ReturnedId

    data class TargetPosting(
        val id: PostingId,
    ) : Rg08ReturnedId

    data class Counterparty(
        val id: String,
    ) : Rg08ReturnedId

    data class NameHistory(
        val id: String,
    ) : Rg08ReturnedId

    data class Request(
        val id: String,
    ) : Rg08ReturnedId
}

enum class Rg08RejectionReason(
    val code: String,
) {
    EXACT_DECIMAL_STRING_REQUIRED("exact_decimal_string_required"),
    TOTAL_MUST_BE_POSITIVE("total_must_be_positive"),
    COMPONENTS_MUST_EQUAL_TOTAL("components_must_equal_total"),
    COMPONENT_MUST_BE_NONNEGATIVE("component_must_be_nonnegative"),
    FEE_MUST_BE_ZERO_IN_RG08_V1("fee_must_be_zero_in_rg08_v1"),
    NONZERO_FEE_ACCOUNTING_OUT_OF_SCOPE("nonzero_fee_accounting_out_of_scope"),
    PRINCIPAL_EXCEEDS_OUTSTANDING_POSITION("principal_exceeds_outstanding_position"),
    UNKNOWN_ACCOUNT("unknown_account"),
    OWNED_ACCOUNT_REQUIRED("owned_account_required"),
    FINANCIAL_ASSET_ACCOUNT_REQUIRED("financial_asset_account_required"),
    UNKNOWN_COUNTERPARTY("unknown_counterparty"),
    INVALID_LENDING_BEHAVIOR("invalid_lending_behavior"),
    EXPLICIT_COMPONENT_SPLIT_REQUIRED("explicit_component_split_required"),
    SAME_CURRENCY_REQUIRED("same_currency_required"),
    ACTIVE_EXACT_INTEREST_CATEGORY_REQUIRED("active_exact_interest_category_required"),
    BEHAVIOR_CONFIRMATION_REQUIRED("behavior_confirmation_required"),
    COUNTERPARTY_CONFIRMATION_REQUIRED("counterparty_confirmation_required"),
    DESTINATION_CONFIRMATION_REQUIRED("destination_confirmation_required"),
    PRINCIPAL_CONFIRMATION_REQUIRED("principal_confirmation_required"),
    INTEREST_AND_FEE_CONFIRMATION_REQUIRED("interest_and_fee_confirmation_required"),
    ACTUAL_RECEIPT_TIME_CONFIRMATION_REQUIRED("actual_receipt_time_confirmation_required"),
    EXPLICIT_CONFIRMATION_REQUIRED("explicit_confirmation_required"),
    INVALID_RG08_INPUT("invalid_rg08_input"),
    DOMAIN_REJECTED("domain_rejected"),
    PRINCIPAL_MUST_BE_POSITIVE("principal_must_be_positive"),
}

enum class Rg08FieldPath(
    val value: String,
) {
    INPUT_REQUEST_ID("$.input.request_id"),
    INPUT_BEHAVIOR_CODE("$.input.behavior_code"),
    INPUT_COUNTERPARTY_ID("$.input.counterparty_id"),
    INPUT_DESTINATION_ACCOUNT_ID("$.input.destination_account_id"),
    INPUT_FUNDING_ACCOUNT_ID("$.input.funding_account_id"),
    INPUT_TOTAL_RECEIVED("$.input.total_received"),
    INPUT_PRINCIPAL_AMOUNT("$.input.principal_amount"),
    INPUT_INTEREST_AMOUNT("$.input.interest_amount"),
    INPUT_FEE_AMOUNT("$.input.fee_amount"),
    INPUT_CURRENCY("$.input.currency"),
    INPUT_INTEREST_CATEGORY_ID("$.input.interest_category_id"),
    INPUT_ACTUAL_RECEIPT_AT("$.input.actual_receipt_at"),
    INPUT_CONFIRMED_AT("$.input.confirmed_at"),
    INPUT_COMPONENTS("$.input.components"),
    INPUT_SPLIT_SOURCE("$.input.split_source"),
    INPUT_CONFIRMATION("$.input.explicit_confirmation"),
    INPUT_CANDIDATE("$.input.candidate_id"),
    INPUT_SOURCE("$.input.source_id"),
    INPUT_EVIDENCE("$.input.evidence_id"),
    INPUT_TARGET_POSTING("$.input.target_posting_id"),
    INPUT_INTEREST_AND_FEE_AMOUNTS("$.input.interest_and_fee_amounts"),
    INPUT_ACTUAL_RECEIPT_TIME("$.input.actual_receipt_time"),
    ATTEMPTED_TOTAL_RECEIVED("$.attempted_input.total_received"),
    ATTEMPTED_PRINCIPAL_AMOUNT("$.attempted_input.principal_amount"),
    ATTEMPTED_INTEREST_AMOUNT("$.attempted_input.interest_amount"),
    ATTEMPTED_FEE_AMOUNT("$.attempted_input.fee_amount"),
    ATTEMPTED_COMPONENTS("$.attempted_input.components"),
    ATTEMPTED_SPLIT_SOURCE("$.attempted_input.split_source"),
    ATTEMPTED_CURRENCY("$.attempted_input.currency"),
    ATTEMPTED_COUNTERPARTY_ID("$.attempted_input.counterparty_id"),
    ATTEMPTED_DESTINATION_ACCOUNT_ID("$.attempted_input.destination_account_id"),
    ATTEMPTED_FUNDING_ACCOUNT_ID("$.attempted_input.funding_account_id"),
    ATTEMPTED_BEHAVIOR_CODE("$.attempted_input.behavior_code"),
    ATTEMPTED_INTEREST_CATEGORY_ID("$.attempted_input.interest_category_id"),
    ATTEMPTED_REQUEST_ID("$.attempted_input.request_id"),
}

sealed interface Rg08ExecutionResult {
    class Accepted(
        returnedIds: List<Rg08ReturnedId>,
    ) : Rg08ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg08ReturnedId> get() = snapshot.toList()

        override fun equals(other: Any?) = other is Accepted && snapshot == other.snapshot

        override fun hashCode(): Int = snapshot.hashCode()

        override fun toString(): String = "Accepted(returnedIds=$snapshot)"
    }

    class NoChange(
        returnedIds: List<Rg08ReturnedId>,
    ) : Rg08ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg08ReturnedId> get() = snapshot.toList()

        override fun equals(other: Any?) = other is NoChange && snapshot == other.snapshot

        override fun hashCode(): Int = snapshot.hashCode()

        override fun toString(): String = "NoChange(returnedIds=$snapshot)"
    }

    data class Rejected(
        val reason: Rg08RejectionReason,
        val fieldPath: Rg08FieldPath,
    ) : Rg08ExecutionResult

    data object RequestIdentityConflict : Rg08ExecutionResult
}

fun interface Rg08CommitPort {
    fun commit(operation: Rg08Operation): Rg08ExecutionResult
}

data class Rg08FormalTransactionRecord(
    val formalTransaction: FormalTransaction,
    val createdAt: Instant,
    val createdAtText: String? = null,
    val statisticsAtText: String? = null,
)

data class Rg08Counterparty(
    val id: String,
    val displayName: String,
    val active: Boolean,
    val identityKind: String,
)

data class Rg08ReceivableAccount(
    val accountId: AccountId,
    val counterpartyId: String,
)

/** Lending-specific catalog facts: stable counterparty identity and receivable accounts. */
data class Rg08LendingCatalog(
    val counterparties: List<Rg08Counterparty>,
    val receivableAccounts: List<Rg08ReceivableAccount>,
) {
    fun counterparty(id: String): Rg08Counterparty? = counterparties.firstOrNull { it.id == id }

    fun receivableAccountFor(counterpartyId: String): AccountId? = receivableAccounts.firstOrNull { it.counterpartyId == counterpartyId }?.accountId
}

data class Rg08PostingSemantic(
    val role: String,
    val reconciliationEligible: Boolean,
)

data class Rg08Report(
    val consumptionMinor: Long = 0L,
    val expenseMinor: Long = 0L,
    val lendingPrincipalCashOutflowMinor: Long = 0L,
    val cashOutflowMinor: Long = 0L,
    val lendingPrincipalCashInflowMinor: Long = 0L,
    val interestCashInflowMinor: Long = 0L,
    val totalCashInflowMinor: Long = 0L,
    val ordinaryInterestIncomeMinor: Long = 0L,
    val ordinaryIncomeMinor: Long = 0L,
    val netWorthChangeMinor: Long = 0L,
)

data class Rg08Intake(
    val sourceRecords: List<LendingSourceRecord> = emptyList(),
    val evidence: List<LendingEvidence> = emptyList(),
)

data class Rg08Snapshot(
    val formalTransactions: List<Rg08FormalTransactionRecord>,
    val positions: List<LendingPosition>,
    val settlements: List<LendingSettlement>,
    val candidates: List<LendingCandidate>,
    val confirmations: List<LendingConfirmationProvenance>,
    val sourceRecords: List<LendingSourceRecord>,
    val evidence: List<LendingEvidence>,
    val evidenceLinks: List<LendingEvidenceLink>,
    val auditLinks: List<LendingAuditLink>,
    val postingSemantics: Map<String, Rg08PostingSemantic>,
    val balances: Map<AccountId, Money>,
    val reports: Map<String, Rg08Report>,
    val reconciliation: Map<String, String>,
    val counterpartyNames: Map<String, String>,
)

/**
 * Deterministic runtime for the approved RG-08 lending action registry (D-084, D-062).
 * Business transitions stay independent from a database driver; persistence integration
 * preserves this typed operation boundary. Rejected/incomplete/no-change paths have zero
 * formal effect and keep the baseline state field by field; the principal-cap rejection is
 * atomic (`pending_explicit_reallocation`, no auto cap, no guesses).
 */
class Rg08Runtime(
    private val catalog: LedgerCatalog,
    private val lendingCatalog: Rg08LendingCatalog,
    openingTransactions: List<Rg08FormalTransactionRecord>,
    intake: Rg08Intake = Rg08Intake(),
) : Rg08CommitPort {
    constructor(catalog: LedgerCatalog, lendingCatalog: Rg08LendingCatalog, snapshot: Rg08Snapshot) : this(
        catalog,
        lendingCatalog,
        snapshot.formalTransactions,
        Rg08Intake(snapshot.sourceRecords, snapshot.evidence),
    ) {
        positions += snapshot.positions.map { it.copy(history = it.history.toList()) }
        settlements += snapshot.settlements.map { it.copy(components = it.components.toList(), history = it.history.toList()) }
        candidates += snapshot.candidates.map { it.copy(statusHistory = it.statusHistory.toList(), sourceIds = it.sourceIds.toList()) }
        confirmations += snapshot.confirmations
        evidenceLinks += snapshot.evidenceLinks
        auditLinks += snapshot.auditLinks
        postingSemantics.putAll(snapshot.postingSemantics)
        counterpartyNames.putAll(snapshot.counterpartyNames)
    }

    private val formalTransactions = openingTransactions.toMutableList()
    private val positions = mutableListOf<LendingPosition>()
    private val settlements = mutableListOf<LendingSettlement>()
    private val candidates = mutableListOf<LendingCandidate>()
    private val confirmations = mutableListOf<LendingConfirmationProvenance>()
    private val sourceRecords = intake.sourceRecords.toMutableList()
    private val evidence = intake.evidence.toMutableList()
    private val evidenceLinks = mutableListOf<LendingEvidenceLink>()
    private val auditLinks = mutableListOf<LendingAuditLink>()
    private val postingSemantics = mutableMapOf<String, Rg08PostingSemantic>()
    private val counterpartyNames = lendingCatalog.counterparties.associateTo(mutableMapOf()) { it.id to it.displayName }
    private val receipts = mutableMapOf<Rg08OperationIdentity, Receipt>()

    private data class Receipt(
        val fingerprint: String,
        val result: Rg08ExecutionResult,
    )

    override fun commit(operation: Rg08Operation): Rg08ExecutionResult {
        if (operation is Rg08Operation.RetryIdempotentInput) {
            return replayRetry(operation)
        }
        val fingerprint = canonicalInput(operation)
        receipts[operation.identity]?.let { receipt ->
            return if (receipt.fingerprint == fingerprint) {
                when (val result = receipt.result) {
                    is Rg08ExecutionResult.Accepted -> Rg08ExecutionResult.NoChange(result.returnedIds)
                    else -> result
                }
            } else {
                Rg08ExecutionResult.RequestIdentityConflict
            }
        }
        val result =
            when (operation) {
                is Rg08Operation.ValidateLendingEvent -> validateLendingEvent(operation)
                is Rg08Operation.ValidateLendingSettlement -> validateLendingSettlement(operation)
                is Rg08Operation.IngestImportedCollectionCandidate -> ingestImportedCollectionCandidate(operation)
                is Rg08Operation.RejectIncompleteImportedConfirmation -> rejectIncompleteImportedConfirmation(operation)
                is Rg08Operation.ConfirmImportedCollection -> confirmImportedCollection(operation)
                is Rg08Operation.AllocateLendingCollection -> allocateLendingCollection(operation)
                is Rg08Operation.MergeImportedEvidence -> mergeImportedEvidence(operation)
                is Rg08Operation.RenameCounterparty -> renameCounterparty(operation)
                is Rg08Operation.RetryIdempotentInput -> replayRetry(operation)
                is Rg08Operation.InvalidInput -> rejectInvalidInput(operation)
            }
        if (result is Rg08ExecutionResult.Accepted || result is Rg08ExecutionResult.Rejected) {
            receipts[operation.identity] = Receipt(fingerprint, result)
            registerAnchorReceipts(operation, fingerprint, result)
        }
        return result
    }

    fun snapshot(): Rg08Snapshot =
        Rg08Snapshot(
            formalTransactions = formalTransactions.toList(),
            positions = positions.map { it.copy(history = it.history.toList()) },
            settlements = settlements.map { it.copy(components = it.components.toList(), history = it.history.toList()) },
            candidates =
                candidates.map {
                    it.copy(
                        statusHistory = it.statusHistory.toList(),
                        sourceIds = it.sourceIds.toList(),
                        requiresConfirmation = it.requiresConfirmation.toList(),
                    )
                },
            confirmations = confirmations.toList(),
            sourceRecords = sourceRecords.toList(),
            evidence = evidence.toList(),
            evidenceLinks = evidenceLinks.toList(),
            auditLinks = auditLinks.toList(),
            postingSemantics = postingSemantics.toMap(),
            balances = replayBalances(),
            reports = reports(),
            reconciliation = reconciliation(),
            counterpartyNames = counterpartyNames.toMap(),
        )

    fun operationFingerprint(operation: Rg08Operation): String = canonicalInput(operation)

    // ------------------------------------------------------------------ lend

    private fun validateLendingEvent(operation: Rg08Operation.ValidateLendingEvent): Rg08ExecutionResult {
        val input = operation.input
        val ids = operation.ids
        if (!input.explicitConfirmation) {
            return rejected(Rg08RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg08FieldPath.INPUT_CONFIRMATION)
        }
        if (input.behaviorCode != "lend") {
            return rejected(Rg08RejectionReason.INVALID_LENDING_BEHAVIOR, Rg08FieldPath.INPUT_BEHAVIOR_CODE)
        }
        if (lendingCatalog.counterparty(input.counterpartyId) == null) {
            return rejected(Rg08RejectionReason.UNKNOWN_COUNTERPARTY, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        }
        if (input.principalAmount.minorUnits <= 0L) {
            return rejected(Rg08RejectionReason.TOTAL_MUST_BE_POSITIVE, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
        }
        if (input.principalAmount.currency != input.currency) {
            return rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_CURRENCY)
        }
        val funding =
            catalogAccount(input.fundingAccountId)
                ?: return rejected(Rg08RejectionReason.UNKNOWN_ACCOUNT, Rg08FieldPath.INPUT_FUNDING_ACCOUNT_ID)
        if (funding.kind != AccountKind.ASSET || !funding.ownedByUser || !funding.realAccount) {
            return rejected(Rg08RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg08FieldPath.INPUT_FUNDING_ACCOUNT_ID)
        }
        if (funding.currency != input.currency) {
            return rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_CURRENCY)
        }
        val receivableAccountId =
            lendingCatalog.receivableAccountFor(input.counterpartyId)
                ?: return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        val receivable =
            catalogAccount(receivableAccountId)
                ?: return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        if (receivable.kind != AccountKind.ASSET || !receivable.ownedByUser || !receivable.realAccount) {
            return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        }
        if (receivable.currency != input.currency) {
            return rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_CURRENCY)
        }
        val principal = input.principalAmount.minorUnits
        val position =
            when (
                val result =
                    createLendingPosition(
                        id = ids.positionId,
                        counterpartyId = input.counterpartyId,
                        receivableAccountId = receivableAccountId,
                        currency = input.currency,
                        principalBalanceMinor = principal,
                        history =
                            listOf(
                                LendingPositionHistoryEntry(
                                    id = ids.positionHistoryId,
                                    behaviorCode = LendingBehaviorCode.LEND,
                                    amountMinor = principal,
                                    principalBalanceAfterMinor = principal,
                                    transactionId = ids.transactionId,
                                    occurredAt = input.actualAt,
                                ),
                            ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            }
        val formal =
            when (val result = buildLendTransaction(operation.ledgerId, input, ids, receivableAccountId, principal)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(DomainResultFailureViolation, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            }
        val record =
            Rg08FormalTransactionRecord(
                formal,
                input.confirmedAt,
                createdAtText = input.confirmedAtText,
                statisticsAtText = input.actualAtText,
            )
        if (
            !canAppendFormalTransaction(record) ||
            positions.any { it.id == ids.positionId } ||
            confirmations.any { it.id == ids.confirmationId.value } ||
            sourceRecords.any { it.id == ids.sourceId.value } ||
            evidence.any { it.id == ids.evidenceId.value } ||
            evidenceLinks.any { it.id == ids.evidenceLinkId.value }
        ) {
            return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
        }
        val source =
            when (
                val result =
                    createLendingSourceRecord(
                        id = ids.sourceId.value,
                        sourceRecordId = ids.sourceRecordId,
                        kind = LendingSourceKind.BANK_DEBIT,
                        observedAt = ids.sourceObservedAt,
                        bookingAt = input.actualAt,
                        valueAt = input.actualAt,
                        amountMinor = ids.sourceAmountMinor,
                        currency = input.currency,
                        immutablePayloadHash = LEND_DEBIT_PAYLOAD_HASH,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            }
        val evidenceItem =
            when (val result = createLendingEvidence(ids.evidenceId.value, source)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            }
        val linkResult =
            when (
                val result =
                    createLendingEvidenceLink(
                        id = ids.evidenceLinkId.value,
                        sourceId = source.id,
                        evidenceId = evidenceItem.id,
                        role = LendingEvidenceLinkRole.FUNDING_ASSET_POSTING,
                        targetPostingId = ids.fundingPostingId,
                        status = LendingEvidenceLinkStatus.MATCHED,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            }
        val provenance =
            when (
                val result =
                    createLendingConfirmationProvenance(
                        id = ids.confirmationId.value,
                        confirmationRequestId = input.requestId.value,
                        role = LendingConfirmationRole.LENDING_EVENT_CONFIRMATION,
                        transactionKind = TransactionKind.LEND,
                        transactionId = ids.transactionId,
                        counterpartyId = input.counterpartyId,
                        confirmedAt = input.confirmedAt,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_CONFIRMED_AT)
            }
        formalTransactions += record
        positions += position
        sourceRecords += source
        evidence += evidenceItem
        evidenceLinks += linkResult.link
        auditLinks += linkResult.auditLinks
        confirmations += provenance
        postingSemantics[ids.receivablePostingId.value] = Rg08PostingSemantic("RECEIVABLE", false)
        postingSemantics[ids.fundingPostingId.value] = Rg08PostingSemantic("FUNDING_OUT", true)
        return accepted(
            listOf(
                Rg08ReturnedId.Transaction(ids.transactionId),
                Rg08ReturnedId.Version(ids.versionId),
                Rg08ReturnedId.Position(ids.positionId),
            ),
        )
    }

    // ------------------------------------------------------------------ collection

    private fun validateLendingSettlement(operation: Rg08Operation.ValidateLendingSettlement): Rg08ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg08RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg08FieldPath.INPUT_CONFIRMATION)
        }
        val position =
            resolvePosition(input.linkedPositionId, input.counterpartyId)
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        return bookCollection(
            ledgerId = operation.ledgerId,
            confirmationRequestId = input.requestId.value,
            behaviorCode = input.behaviorCode,
            counterpartyId = input.counterpartyId,
            position = position,
            allocatedLendTransactionId = input.allocatedLendTransactionId,
            destinationAccountId = input.destinationAccountId,
            totalReceivedMinor = input.totalReceived.minorUnits,
            principalMinor = input.principalAmount.minorUnits,
            interestMinor = input.interestAmount.minorUnits,
            feeMinor = input.feeAmount.minorUnits,
            interestCategoryId = input.interestCategoryId,
            currency = input.currency,
            actualReceiptAt = input.actualReceiptAt,
            actualReceiptAtText = input.actualReceiptAtText,
            confirmedAt = input.confirmedAt,
            confirmedAtText = input.confirmedAtText,
            ids = operation.ids,
            candidateId = null,
            destinationEvidenceLinkId = operation.ids.creditEvidenceLinkId,
            destinationSourceId = operation.ids.creditSourceId,
            destinationEvidenceId = operation.ids.creditEvidenceId,
            returnedIds =
                listOf(
                    Rg08ReturnedId.Transaction(operation.ids.transactionId),
                    Rg08ReturnedId.Version(operation.ids.versionId),
                    Rg08ReturnedId.Settlement(operation.ids.settlementId),
                    Rg08ReturnedId.Component(operation.ids.principalComponentId),
                    Rg08ReturnedId.Component(operation.ids.interestComponentId),
                    Rg08ReturnedId.Component(operation.ids.feeComponentId),
                ),
        )
    }

    private fun confirmImportedCollection(operation: Rg08Operation.ConfirmImportedCollection): Rg08ExecutionResult {
        val input = operation.input
        if (!input.explicitConfirmation) {
            return rejected(Rg08RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg08FieldPath.INPUT_CONFIRMATION)
        }
        val candidateIndex = candidates.indexOfFirst { it.id == input.candidateId.value }
        if (candidateIndex < 0) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_CANDIDATE)
        }
        val pending = candidates[candidateIndex]
        if (pending.status != LendingCandidateStatus.PENDING_CONFIRMATION) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_CANDIDATE)
        }
        val missing = LendingConfirmationGateField.ALL.firstOrNull { it.name.lowercase() !in input.explicitlyConfirmedFields }
        if (missing != null) {
            return rejected(gateReason(missing), gateFieldPath(missing))
        }
        val position =
            resolvePosition(null, input.counterpartyId)
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        val confirmedCandidate =
            buildConfirmedCandidate(pending, input, operation.ids)
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_CANDIDATE)
        val destinationSource =
            sourceRecords.firstOrNull { it.id in pending.sourceIds && it.kind == LendingSourceKind.BANK_CREDIT }
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_SOURCE)
        val destinationEvidence =
            evidence.firstOrNull { it.sourceId == destinationSource.id }
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_EVIDENCE)
        val principalMinor = input.principalAmount.minorUnits
        val interestMinor = input.interestAmount.minorUnits
        val feeMinor = input.feeAmount.minorUnits
        val totalReceivedMinor =
            checkedAdd(
                checkedAdd(principalMinor, interestMinor) ?: return rejected(
                    Rg08RejectionReason.DOMAIN_REJECTED,
                    Rg08FieldPath.INPUT_COMPONENTS,
                ),
                feeMinor,
            ) ?: return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_COMPONENTS)
        if (totalReceivedMinor != pending.proposedTotalReceivedMinor) {
            return rejected(Rg08RejectionReason.COMPONENTS_MUST_EQUAL_TOTAL, Rg08FieldPath.INPUT_COMPONENTS)
        }
        val result =
            bookCollection(
                ledgerId = operation.ledgerId,
                confirmationRequestId = input.requestId.value,
                behaviorCode = input.behaviorCode,
                counterpartyId = input.counterpartyId,
                position = position,
                allocatedLendTransactionId = null,
                destinationAccountId = input.destinationAccountId,
                totalReceivedMinor = totalReceivedMinor,
                principalMinor = principalMinor,
                interestMinor = interestMinor,
                feeMinor = feeMinor,
                interestCategoryId = input.interestCategoryId,
                currency = input.currency,
                actualReceiptAt = input.actualReceiptAt,
                actualReceiptAtText = input.actualReceiptAtText,
                confirmedAt = input.confirmedAt,
                confirmedAtText = input.confirmedAtText,
                ids = toSettlementIds(operation.ids),
                candidateId = pending.id,
                destinationEvidenceLinkId = operation.ids.destinationEvidenceLinkId,
                destinationSourceId = Rg08SourceRecordId(destinationSource.id),
                destinationEvidenceId = Rg08EvidenceId(destinationEvidence.id),
                returnedIds =
                    listOf(
                        Rg08ReturnedId.Candidate(input.candidateId),
                        Rg08ReturnedId.Transaction(operation.ids.transactionId),
                        Rg08ReturnedId.Version(operation.ids.versionId),
                        Rg08ReturnedId.Settlement(operation.ids.settlementId),
                    ),
                candidateConfirmedHistoryId = operation.ids.candidateConfirmedHistoryId,
            )
        if (result is Rg08ExecutionResult.Accepted) {
            candidates[candidateIndex] = confirmedCandidate
        }
        return result
    }

    private fun allocateLendingCollection(operation: Rg08Operation.AllocateLendingCollection): Rg08ExecutionResult {
        val input = operation.input
        if (lendingCatalog.counterparty(input.counterpartyId) == null) {
            return rejected(Rg08RejectionReason.UNKNOWN_COUNTERPARTY, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        }
        val position =
            resolvePosition(null, input.counterpartyId)
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        if (input.totalReceived.minorUnits <= 0L) {
            return rejected(Rg08RejectionReason.TOTAL_MUST_BE_POSITIVE, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
        }
        if (input.principalAmount.minorUnits < 0L || input.interestAmount.minorUnits < 0L) {
            return rejected(Rg08RejectionReason.COMPONENT_MUST_BE_NONNEGATIVE, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
        }
        if (input.feeAmount.minorUnits < 0L) {
            return rejected(Rg08RejectionReason.FEE_MUST_BE_ZERO_IN_RG08_V1, Rg08FieldPath.INPUT_FEE_AMOUNT)
        }
        if (input.feeAmount.minorUnits > 0L) {
            return rejected(Rg08RejectionReason.NONZERO_FEE_ACCOUNTING_OUT_OF_SCOPE, Rg08FieldPath.INPUT_FEE_AMOUNT)
        }
        val composed =
            checkedAdd(input.principalAmount.minorUnits, input.interestAmount.minorUnits)
                ?: return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
        if (checkedAdd(composed, input.feeAmount.minorUnits) != input.totalReceived.minorUnits) {
            return rejected(Rg08RejectionReason.COMPONENTS_MUST_EQUAL_TOTAL, Rg08FieldPath.INPUT_COMPONENTS)
        }
        if (input.currency != position.currency) {
            return rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_CURRENCY)
        }
        // D-062 atomic principal cap: over-balance is rejected whole, pending explicit
        // reallocation; never truncated, never guessed into income/borrow/clearing.
        if (input.principalAmount.minorUnits > position.principalBalanceMinor) {
            return rejected(
                Rg08RejectionReason.PRINCIPAL_EXCEEDS_OUTSTANDING_POSITION,
                Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT,
            )
        }
        val actualReceiptAt =
            input.actualReceiptAt
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_ACTUAL_RECEIPT_AT)
        val confirmedAt =
            input.confirmedAt
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_CONFIRMED_AT)
        val interestCategoryId =
            input.interestCategoryId
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_INTEREST_CATEGORY_ID)
        return bookCollection(
            ledgerId = operation.ledgerId,
            confirmationRequestId = input.requestId.value,
            behaviorCode = "collect",
            counterpartyId = input.counterpartyId,
            position = position,
            allocatedLendTransactionId = null,
            destinationAccountId = input.destinationAccountId,
            totalReceivedMinor = input.totalReceived.minorUnits,
            principalMinor = input.principalAmount.minorUnits,
            interestMinor = input.interestAmount.minorUnits,
            feeMinor = input.feeAmount.minorUnits,
            interestCategoryId = interestCategoryId,
            currency = input.currency,
            actualReceiptAt = actualReceiptAt,
            actualReceiptAtText = input.actualReceiptAtText ?: actualReceiptAt.toString(),
            confirmedAt = confirmedAt,
            confirmedAtText = input.confirmedAtText ?: confirmedAt.toString(),
            ids = input.ids,
            candidateId = null,
            destinationEvidenceLinkId = null,
            destinationSourceId = null,
            destinationEvidenceId = null,
            // D-084 frozen contract (RG08-DEV-03): allocate returns the stable transaction,
            // version, settlement and position ids (frozen retry-rg08-request-cap-maximum
            // returned_stable_ids), not the request id.
            returnedIds =
                listOf(
                    Rg08ReturnedId.Transaction(input.ids.transactionId),
                    Rg08ReturnedId.Version(input.ids.versionId),
                    Rg08ReturnedId.Settlement(input.ids.settlementId),
                    Rg08ReturnedId.Position(position.id),
                ),
        )
    }

    /**
     * Shared confirmed-collection booking. All validation happens before any state mutation;
     * rejected/incomplete paths keep the baseline field by field.
     */
    private fun bookCollection(
        ledgerId: LedgerId,
        confirmationRequestId: String,
        behaviorCode: String,
        counterpartyId: String,
        position: LendingPosition,
        allocatedLendTransactionId: TransactionId?,
        destinationAccountId: AccountId,
        totalReceivedMinor: Long,
        principalMinor: Long,
        interestMinor: Long,
        feeMinor: Long,
        interestCategoryId: CategoryId,
        currency: CurrencyUnit,
        actualReceiptAt: Instant,
        actualReceiptAtText: String,
        confirmedAt: Instant,
        confirmedAtText: String,
        ids: Rg08SettlementIds,
        candidateId: String?,
        destinationEvidenceLinkId: Rg08EvidenceLinkId?,
        destinationSourceId: Rg08SourceRecordId?,
        destinationEvidenceId: Rg08EvidenceId?,
        returnedIds: List<Rg08ReturnedId>,
        candidateConfirmedHistoryId: String? = null,
    ): Rg08ExecutionResult {
        if (behaviorCode != "collect") {
            return rejected(Rg08RejectionReason.INVALID_LENDING_BEHAVIOR, Rg08FieldPath.INPUT_BEHAVIOR_CODE)
        }
        if (lendingCatalog.counterparty(counterpartyId) == null) {
            return rejected(Rg08RejectionReason.UNKNOWN_COUNTERPARTY, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        }
        if (position.counterpartyId != counterpartyId) {
            return rejected(Rg08RejectionReason.UNKNOWN_COUNTERPARTY, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        }
        if (allocatedLendTransactionId != null) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_REQUEST_ID)
        }
        if (currency != position.currency) {
            return rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_CURRENCY)
        }
        if (totalReceivedMinor <= 0L) {
            return rejected(Rg08RejectionReason.TOTAL_MUST_BE_POSITIVE, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
        }
        if (principalMinor < 0L || interestMinor < 0L) {
            return rejected(Rg08RejectionReason.COMPONENT_MUST_BE_NONNEGATIVE, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
        }
        // RG08-QA-02: an interest-only collection (principal == 0) passes the domain direction
        // rule but violates the persisted COLLECT direction guard
        // (`rg08_position_history_direction` in 14.sqm rejects COLLECT amount >= 0), so the
        // operation layer rejects it explicitly; the DB guard stays strict and untouched.
        if (principalMinor == 0L) {
            return rejected(Rg08RejectionReason.PRINCIPAL_MUST_BE_POSITIVE, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
        }
        if (feeMinor < 0L) {
            return rejected(Rg08RejectionReason.FEE_MUST_BE_ZERO_IN_RG08_V1, Rg08FieldPath.INPUT_FEE_AMOUNT)
        }
        if (feeMinor > 0L) {
            return rejected(Rg08RejectionReason.NONZERO_FEE_ACCOUNTING_OUT_OF_SCOPE, Rg08FieldPath.INPUT_FEE_AMOUNT)
        }
        val interestAccountId =
            exactInterestAccountId(interestCategoryId)
                ?: return rejected(
                    Rg08RejectionReason.ACTIVE_EXACT_INTEREST_CATEGORY_REQUIRED,
                    Rg08FieldPath.INPUT_INTEREST_CATEGORY_ID,
                )
        val destination =
            catalogAccount(destinationAccountId)
                ?: return rejected(Rg08RejectionReason.UNKNOWN_ACCOUNT, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
        if (destination.kind != AccountKind.ASSET || !destination.ownedByUser) {
            return rejected(Rg08RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
        }
        if (!destination.realAccount) {
            return rejected(Rg08RejectionReason.FINANCIAL_ASSET_ACCOUNT_REQUIRED, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
        }
        if (destination.currency != currency) {
            return rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_CURRENCY)
        }
        if (interestAccountId.currency != currency) {
            return rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_INTEREST_CATEGORY_ID)
        }
        val settlement =
            when (
                val result =
                    createLendingSettlement(
                        id = ids.settlementId,
                        catalog = catalog,
                        position = position,
                        transactionId = ids.transactionId,
                        destinationAccountId = destinationAccountId,
                        interestCategoryId = interestCategoryId,
                        totalReceivedMinor = totalReceivedMinor,
                        currency = currency,
                        actualReceiptAt = actualReceiptAt,
                        confirmedAt = confirmedAt,
                        components =
                            listOf(
                                LendingSettlementComponent(ids.principalComponentId, LendingComponentKind.PRINCIPAL, principalMinor, ids.principalPostingId),
                                LendingSettlementComponent(ids.interestComponentId, LendingComponentKind.INTEREST, interestMinor, ids.interestPostingId),
                                LendingSettlementComponent(ids.feeComponentId, LendingComponentKind.FEE, 0L, null),
                            ),
                        history =
                            listOf(
                                LendingSettlementHistoryEntry(
                                    id = ids.settlementHistoryId,
                                    status = LendingSettlementStatus.CONFIRMED,
                                    occurredAt = confirmedAt,
                                    transactionId = ids.transactionId,
                                    formalEffectCount = 1,
                                ),
                            ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
            }
        val remaining =
            checkedSubtract(position.principalBalanceMinor, principalMinor)
                ?: return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
        val updatedPosition =
            when (
                val result =
                    createLendingPosition(
                        id = position.id,
                        counterpartyId = position.counterpartyId,
                        receivableAccountId = position.receivableAccountId,
                        currency = position.currency,
                        principalBalanceMinor = remaining,
                        history =
                            position.history +
                                LendingPositionHistoryEntry(
                                    id = ids.positionHistoryId,
                                    behaviorCode = LendingBehaviorCode.COLLECT,
                                    amountMinor =
                                        checkedNegate(principalMinor)
                                            ?: return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT),
                                    principalBalanceAfterMinor = remaining,
                                    transactionId = ids.transactionId,
                                    occurredAt = actualReceiptAt,
                                ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            }
        val formal =
            when (
                val result =
                    buildCollectTransaction(
                        ledgerId,
                        ids,
                        destinationAccountId,
                        position.receivableAccountId,
                        interestAccountId,
                        totalReceivedMinor,
                        principalMinor,
                        interestMinor,
                        currency,
                        actualReceiptAt,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
            }
        val record =
            Rg08FormalTransactionRecord(
                formal,
                confirmedAt,
                createdAtText = confirmedAtText,
                statisticsAtText = actualReceiptAtText,
            )
        if (
            !canAppendFormalTransaction(record) ||
            settlements.any { it.id == ids.settlementId } ||
            confirmations.any { it.id == ids.confirmationId.value } ||
            positions.any { it.history.any { history -> history.id == ids.positionHistoryId } } ||
            sourceRecords.any { source -> ids.confirmationSourceId?.value == source.id || ids.creditSourceId?.value == source.id } ||
            evidence.any { item -> ids.creditEvidenceId?.value == item.id } ||
            evidenceLinks.any { link -> ids.creditEvidenceLinkId?.value == link.id || destinationEvidenceLinkId?.value == link.id }
        ) {
            return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
        }
        // Optional destination evidence: manual path books bank credit + explicit manual
        // confirmation sources; null ids (cap path) keep the collection evidence-pending.
        val newSources = mutableListOf<LendingSourceRecord>()
        val newEvidence = mutableListOf<LendingEvidence>()
        val newLinks = mutableListOf<LendingEvidenceLink>()
        val newAuditLinks = mutableListOf<LendingAuditLink>()
        if (ids.confirmationSourceId != null) {
            val confirmationSource =
                when (
                    val result =
                        createLendingSourceRecord(
                            id = ids.confirmationSourceId.value,
                            sourceRecordId = checkNotNull(ids.confirmationSourceRecordId),
                            kind = LendingSourceKind.EXPLICIT_MANUAL_LENDING_CONFIRMATION,
                            observedAt = checkNotNull(ids.confirmationSourceObservedAt),
                            amountMinor = totalReceivedMinor,
                            currency = currency,
                            immutablePayloadHash = MANUAL_CONFIRMATION_PAYLOAD_HASH,
                        )
                ) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_CONFIRMED_AT)
                }
            newSources += confirmationSource
        }
        if (ids.creditSourceId != null) {
            val creditSource =
                when (
                    val result =
                        createLendingSourceRecord(
                            id = ids.creditSourceId.value,
                            sourceRecordId = checkNotNull(ids.creditSourceRecordId),
                            kind = LendingSourceKind.BANK_CREDIT,
                            observedAt = checkNotNull(ids.creditSourceObservedAt),
                            bookingAt = checkNotNull(ids.creditSourceBookingAt),
                            valueAt = checkNotNull(ids.creditSourceValueAt),
                            accountId = destinationAccountId,
                            amountMinor = totalReceivedMinor,
                            currency = currency,
                            immutablePayloadHash = MANUAL_CREDIT_PAYLOAD_HASH,
                        )
                ) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
                }
            newSources += creditSource
            val creditEvidence =
                when (val result = createLendingEvidence(checkNotNull(ids.creditEvidenceId).value, creditSource)) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
                }
            newEvidence += creditEvidence
            if (ids.creditEvidenceLinkId != null) {
                val linkResult =
                    when (
                        val result =
                            createLendingEvidenceLink(
                                id = ids.creditEvidenceLinkId.value,
                                sourceId = creditSource.id,
                                evidenceId = creditEvidence.id,
                                role = LendingEvidenceLinkRole.DESTINATION_ASSET_POSTING,
                                targetPostingId = ids.destinationPostingId,
                                status = LendingEvidenceLinkStatus.MATCHED,
                            )
                    ) {
                        is DomainResult.Success -> result.value
                        is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
                    }
                newLinks += linkResult.link
                newAuditLinks += linkResult.auditLinks
            }
        }
        // D-084 frozen contract (RG08-DEV-02): the destination-evidence link is built only on
        // the imported path, where the credit branch did not run and the link joins the pending
        // candidate's bank credit source/evidence to the destination posting. On the manual path
        // the credit branch already created the identical destination link (creditEvidenceLinkId),
        // so this branch must not fire a duplicate with the same id.
        if (ids.creditSourceId == null && destinationEvidenceLinkId != null && destinationSourceId != null && destinationEvidenceId != null) {
            val linkResult =
                when (
                    val result =
                        createLendingEvidenceLink(
                            id = destinationEvidenceLinkId.value,
                            sourceId = destinationSourceId.value,
                            evidenceId = destinationEvidenceId.value,
                            role = LendingEvidenceLinkRole.DESTINATION_ASSET_POSTING,
                            targetPostingId = ids.destinationPostingId,
                            status = LendingEvidenceLinkStatus.MATCHED,
                        )
                ) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
                }
            newLinks += linkResult.link
            newAuditLinks += linkResult.auditLinks
        }
        val provenance =
            when (
                val result =
                    createLendingConfirmationProvenance(
                        id = ids.confirmationId.value,
                        confirmationRequestId = confirmationRequestId,
                        role = LendingConfirmationRole.LENDING_SETTLEMENT_CONFIRMATION,
                        transactionKind = TransactionKind.COLLECT,
                        transactionId = ids.transactionId,
                        counterpartyId = counterpartyId,
                        confirmedAt = confirmedAt,
                        candidateId = candidateId,
                        settlementId = ids.settlementId,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_CONFIRMED_AT)
            }
        formalTransactions += record
        positions[positions.indexOfFirst { it.id == position.id }] = updatedPosition
        settlements += settlement
        sourceRecords += newSources
        evidence += newEvidence
        evidenceLinks += newLinks
        auditLinks += newAuditLinks
        confirmations += provenance
        postingSemantics[ids.destinationPostingId.value] = Rg08PostingSemantic("DESTINATION_IN", true)
        postingSemantics[ids.principalPostingId.value] = Rg08PostingSemantic("PRINCIPAL_OUT", false)
        postingSemantics[ids.interestPostingId.value] = Rg08PostingSemantic("INTEREST_OUT", false)
        return accepted(returnedIds)
    }

    // ------------------------------------------------------------------ intake

    private fun ingestImportedCollectionCandidate(
        operation: Rg08Operation.IngestImportedCollectionCandidate,
    ): Rg08ExecutionResult {
        val input = operation.input
        val ids = operation.ids
        if (input.creditSourceId == input.agreementSourceId) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_SOURCE)
        }
        if (lendingCatalog.counterparty(input.agreementCounterpartyId) == null) {
            return rejected(Rg08RejectionReason.UNKNOWN_COUNTERPARTY, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        }
        val proposedDestination =
            catalogAccount(input.proposedDestinationAccountId)
                ?: return rejected(Rg08RejectionReason.UNKNOWN_ACCOUNT, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
        if (proposedDestination.kind != AccountKind.ASSET || !proposedDestination.ownedByUser) {
            return rejected(Rg08RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
        }
        if (!proposedDestination.realAccount) {
            return rejected(Rg08RejectionReason.FINANCIAL_ASSET_ACCOUNT_REQUIRED, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
        }
        val relationshipPosition =
            positions.firstOrNull { it.counterpartyId == input.agreementCounterpartyId }
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        if (input.proposedTotalReceivedMinor <= 0L) {
            return rejected(Rg08RejectionReason.TOTAL_MUST_BE_POSITIVE, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
        }
        if (input.creditAmountMinor <= 0L) {
            return rejected(Rg08RejectionReason.TOTAL_MUST_BE_POSITIVE, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
        }
        if (input.creditOriginalSourcePayloadHash != input.creditImmutablePayloadHash) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_SOURCE)
        }
        val creditSource =
            when (
                val result =
                    createLendingSourceRecord(
                        id = input.creditSourceId.value,
                        sourceRecordId = input.creditSourceRecordId,
                        kind = LendingSourceKind.BANK_CREDIT,
                        observedAt = input.creditObservedAt,
                        bookingAt = input.creditBookingAt,
                        valueAt = input.creditValueAt,
                        accountId = input.creditAccountId,
                        amountMinor = input.creditAmountMinor,
                        currency = input.creditCurrency,
                        originalSourcePayloadHash = input.creditOriginalSourcePayloadHash,
                        immutablePayloadHash = input.creditImmutablePayloadHash,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        val agreementSource =
            when (
                val result =
                    createLendingSourceRecord(
                        id = input.agreementSourceId.value,
                        sourceRecordId = input.agreementSourceRecordId,
                        kind = LendingSourceKind.LENDING_AGREEMENT,
                        observedAt = input.agreementObservedAt,
                        counterpartyId = input.agreementCounterpartyId,
                        currency = input.agreementCurrency,
                        immutablePayloadHash = input.agreementImmutablePayloadHash,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        val creditEvidence =
            when (val result = createLendingEvidence(ids.creditEvidenceId.value, creditSource)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        val agreementEvidence =
            when (val result = createLendingEvidence(ids.agreementEvidenceId.value, agreementSource)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        val linkResult =
            when (
                val result =
                    createLendingEvidenceLink(
                        id = ids.agreementEvidenceLinkId.value,
                        sourceId = agreementSource.id,
                        evidenceId = agreementEvidence.id,
                        role = LendingEvidenceLinkRole.COUNTERPARTY_LENDING_RELATIONSHIP,
                        targetPositionId = relationshipPosition.id,
                        status = LendingEvidenceLinkStatus.SUPPORTED,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        val candidate =
            when (
                val result =
                    createLendingCandidate(
                        id = input.candidateId.value,
                        type = input.candidateType,
                        status = LendingCandidateStatus.PENDING_CONFIRMATION,
                        proposedTotalReceivedMinor = input.proposedTotalReceivedMinor,
                        currency = input.creditCurrency,
                        proposedDestinationAccountId = input.proposedDestinationAccountId,
                        proposedActualReceiptAt = input.proposedActualReceiptAt,
                        sourceIds = listOf(input.creditSourceId.value, input.agreementSourceId.value),
                        ruleVersion = input.ruleVersion,
                        confidence = input.confidence,
                        statusHistory =
                            listOf(
                                LendingCandidateStatusHistoryEntry(
                                    id = ids.candidateHistoryId,
                                    status = LendingCandidateStatus.PENDING_CONFIRMATION,
                                    occurredAt = input.agreementObservedAt,
                                    formalEffectCount = 0,
                                ),
                            ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
            }
        if (
            candidates.any { it.id == input.candidateId.value } ||
            sourceRecords.any { it.id == input.creditSourceId.value || it.id == input.agreementSourceId.value } ||
            evidence.any { it.id == ids.creditEvidenceId.value || it.id == ids.agreementEvidenceId.value } ||
            evidenceLinks.any { it.id == ids.agreementEvidenceLinkId.value }
        ) {
            return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_SOURCE)
        }
        sourceRecords += creditSource
        sourceRecords += agreementSource
        evidence += creditEvidence
        evidence += agreementEvidence
        evidenceLinks += linkResult.link
        auditLinks += linkResult.auditLinks
        candidates += candidate
        return accepted(
            listOf(
                Rg08ReturnedId.SourceRecord(input.creditSourceId),
                Rg08ReturnedId.Candidate(input.candidateId),
            ),
        )
    }

    private fun rejectIncompleteImportedConfirmation(
        operation: Rg08Operation.RejectIncompleteImportedConfirmation,
    ): Rg08ExecutionResult {
        val input = operation.input
        val candidate =
            candidates.firstOrNull { it.id == input.candidateId.value }
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_CANDIDATE)
        if (candidate.status != LendingCandidateStatus.PENDING_CONFIRMATION) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_CANDIDATE)
        }
        return rejected(gateReason(input.missingField), gateFieldPath(input.missingField))
    }

    // ------------------------------------------------------------------ mirror/merge, rename, allocate, retry

    private fun mergeImportedEvidence(operation: Rg08Operation.MergeImportedEvidence): Rg08ExecutionResult {
        val input = operation.input
        if (input.mirrorOfSourceId == input.sourceId.value) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_SOURCE)
        }
        val originSource =
            sourceRecords.firstOrNull { it.id == input.mirrorOfSourceId }
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_SOURCE)
        val mirrorEvidence =
            evidence.firstOrNull { it.id == input.mirrorOfEvidenceId }
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_EVIDENCE)
        if (mirrorEvidence.sourceId != originSource.id) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_EVIDENCE)
        }
        val mergedInto =
            evidenceLinks.firstOrNull { it.id == input.mergedIntoEvidenceLinkId }
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_TARGET_POSTING)
        if (mergedInto.targetId != input.targetPostingId.value) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_TARGET_POSTING)
        }
        if (postingExists(input.targetPostingId).not()) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_TARGET_POSTING)
        }
        if (
            sourceRecords.any { it.id == input.sourceId.value } ||
            evidence.any { it.id == input.evidenceId.value } ||
            evidenceLinks.any { it.id == input.evidenceLinkId.value }
        ) {
            return rejected(Rg08RejectionReason.DOMAIN_REJECTED, Rg08FieldPath.INPUT_SOURCE)
        }
        val source =
            when (
                val result =
                    createLendingSourceRecord(
                        id = input.sourceId.value,
                        sourceRecordId = input.sourceRecordId,
                        kind = LendingSourceKind.BANK_CREDIT_MIRROR,
                        observedAt = input.observedAt,
                        amountMinor = input.amountMinor,
                        currency = input.currency,
                        immutablePayloadHash = input.immutablePayloadHash,
                        mirrorOfSourceId = input.mirrorOfSourceId,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        val evidenceItem =
            when (val result = createLendingEvidence(input.evidenceId.value, source)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        val linkResult =
            when (
                val result =
                    createLendingEvidenceLink(
                        id = input.evidenceLinkId.value,
                        sourceId = source.id,
                        evidenceId = evidenceItem.id,
                        role = LendingEvidenceLinkRole.DESTINATION_ASSET_POSTING,
                        targetPostingId = input.targetPostingId,
                        status = LendingEvidenceLinkStatus.MERGED,
                        mirrorOfEvidenceId = input.mirrorOfEvidenceId,
                        mergedIntoEvidenceLinkId = input.mergedIntoEvidenceLinkId,
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return domainRejected(result.violation, Rg08FieldPath.INPUT_SOURCE)
            }
        sourceRecords += source
        evidence += evidenceItem
        evidenceLinks += linkResult.link
        auditLinks += linkResult.auditLinks
        return accepted(
            listOf(
                Rg08ReturnedId.SourceRecord(input.sourceId),
                Rg08ReturnedId.Evidence(input.evidenceId),
                Rg08ReturnedId.EvidenceLink(input.evidenceLinkId),
                Rg08ReturnedId.TargetPosting(input.targetPostingId),
            ),
        )
    }

    private fun renameCounterparty(operation: Rg08Operation.RenameCounterparty): Rg08ExecutionResult {
        val input = operation.input
        val current =
            counterpartyNames[input.counterpartyId]
                ?: return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        if (current != input.oldDisplayName) {
            return rejected(Rg08RejectionReason.INVALID_RG08_INPUT, Rg08FieldPath.INPUT_COUNTERPARTY_ID)
        }
        counterpartyNames[input.counterpartyId] = input.newDisplayName
        return accepted(
            listOf(
                Rg08ReturnedId.Counterparty(input.counterpartyId),
                Rg08ReturnedId.NameHistory(input.nameHistoryId),
            ),
        )
    }

    private fun replayRetry(operation: Rg08Operation.RetryIdempotentInput): Rg08ExecutionResult {
        val receipt =
            receipts[operation.identity]
                ?: return Rg08ExecutionResult.RequestIdentityConflict
        return when (val result = receipt.result) {
            is Rg08ExecutionResult.Accepted -> Rg08ExecutionResult.NoChange(result.returnedIds)
            else -> result
        }
    }

    private fun rejectInvalidInput(operation: Rg08Operation.InvalidInput): Rg08ExecutionResult {
        val (reason, fieldPath) =
            when (operation.input.predicate) {
                Rg08InvalidPredicate.EXACT_DECIMAL_TOTAL ->
                    Rg08RejectionReason.EXACT_DECIMAL_STRING_REQUIRED to Rg08FieldPath.ATTEMPTED_TOTAL_RECEIVED
                Rg08InvalidPredicate.TOTAL_POSITIVE ->
                    Rg08RejectionReason.TOTAL_MUST_BE_POSITIVE to Rg08FieldPath.ATTEMPTED_TOTAL_RECEIVED
                Rg08InvalidPredicate.COMPONENTS_EQUAL_TOTAL ->
                    Rg08RejectionReason.COMPONENTS_MUST_EQUAL_TOTAL to Rg08FieldPath.ATTEMPTED_COMPONENTS
                Rg08InvalidPredicate.COMPONENT_NONNEGATIVE ->
                    Rg08RejectionReason.COMPONENT_MUST_BE_NONNEGATIVE to Rg08FieldPath.ATTEMPTED_PRINCIPAL_AMOUNT
                Rg08InvalidPredicate.FEE_ZERO ->
                    Rg08RejectionReason.FEE_MUST_BE_ZERO_IN_RG08_V1 to Rg08FieldPath.ATTEMPTED_FEE_AMOUNT
                Rg08InvalidPredicate.FEE_OUT_OF_SCOPE ->
                    Rg08RejectionReason.NONZERO_FEE_ACCOUNTING_OUT_OF_SCOPE to Rg08FieldPath.ATTEMPTED_FEE_AMOUNT
                Rg08InvalidPredicate.PRINCIPAL_EXCEEDS_OUTSTANDING ->
                    Rg08RejectionReason.PRINCIPAL_EXCEEDS_OUTSTANDING_POSITION to Rg08FieldPath.ATTEMPTED_PRINCIPAL_AMOUNT
                Rg08InvalidPredicate.UNKNOWN_DESTINATION ->
                    Rg08RejectionReason.UNKNOWN_ACCOUNT to Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID
                Rg08InvalidPredicate.UNOWNED_DESTINATION ->
                    Rg08RejectionReason.OWNED_ACCOUNT_REQUIRED to Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID
                Rg08InvalidPredicate.NONFINANCIAL_DESTINATION ->
                    Rg08RejectionReason.FINANCIAL_ASSET_ACCOUNT_REQUIRED to Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID
                Rg08InvalidPredicate.UNKNOWN_FUNDING_ACCOUNT ->
                    Rg08RejectionReason.UNKNOWN_ACCOUNT to Rg08FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID
                Rg08InvalidPredicate.UNKNOWN_COUNTERPARTY ->
                    Rg08RejectionReason.UNKNOWN_COUNTERPARTY to Rg08FieldPath.ATTEMPTED_COUNTERPARTY_ID
                Rg08InvalidPredicate.INVALID_BEHAVIOR ->
                    Rg08RejectionReason.INVALID_LENDING_BEHAVIOR to Rg08FieldPath.ATTEMPTED_BEHAVIOR_CODE
                Rg08InvalidPredicate.EXPLICIT_COMPONENT_SPLIT ->
                    Rg08RejectionReason.EXPLICIT_COMPONENT_SPLIT_REQUIRED to Rg08FieldPath.ATTEMPTED_SPLIT_SOURCE
                Rg08InvalidPredicate.SAME_CURRENCY ->
                    Rg08RejectionReason.SAME_CURRENCY_REQUIRED to Rg08FieldPath.ATTEMPTED_CURRENCY
                Rg08InvalidPredicate.ACTIVE_EXACT_INTEREST_CATEGORY ->
                    Rg08RejectionReason.ACTIVE_EXACT_INTEREST_CATEGORY_REQUIRED to Rg08FieldPath.ATTEMPTED_INTEREST_CATEGORY_ID
            }
        return rejected(reason, fieldPath)
    }

    // ------------------------------------------------------------------ helpers

    private fun registerAnchorReceipts(
        operation: Rg08Operation,
        fingerprint: String,
        result: Rg08ExecutionResult,
    ) {
        if (result !is Rg08ExecutionResult.Accepted) return
        anchorReturnedIds(operation).forEach { (anchor, ids) ->
            if (anchor != operation.identity.value) {
                receipts[Rg08OperationIdentity(operation.ledgerId, anchor)] =
                    Receipt(fingerprint, Rg08ExecutionResult.Accepted(ids))
            }
        }
    }

    private fun anchorReturnedIds(operation: Rg08Operation): Map<String, List<Rg08ReturnedId>> =
        when (operation) {
            is Rg08Operation.ValidateLendingEvent ->
                mapOf(
                    operation.ids.sourceId.value to
                        listOf(
                            Rg08ReturnedId.SourceRecord(operation.ids.sourceId),
                            Rg08ReturnedId.Evidence(operation.ids.evidenceId),
                            Rg08ReturnedId.EvidenceLink(operation.ids.evidenceLinkId),
                            Rg08ReturnedId.TargetPosting(operation.ids.fundingPostingId),
                        ),
                )
            is Rg08Operation.ValidateLendingSettlement ->
                buildMap {
                    operation.ids.confirmationSourceId?.let { sourceId ->
                        put(
                            sourceId.value,
                            listOf(
                                Rg08ReturnedId.SourceRecord(sourceId),
                                Rg08ReturnedId.Transaction(operation.ids.transactionId),
                                Rg08ReturnedId.Settlement(operation.ids.settlementId),
                            ),
                        )
                    }
                    operation.ids.creditSourceId?.let { sourceId ->
                        put(
                            sourceId.value,
                            listOf(
                                Rg08ReturnedId.SourceRecord(sourceId),
                                Rg08ReturnedId.Evidence(operation.ids.creditEvidenceId!!),
                                Rg08ReturnedId.EvidenceLink(operation.ids.creditEvidenceLinkId!!),
                                Rg08ReturnedId.TargetPosting(operation.ids.destinationPostingId),
                            ),
                        )
                    }
                }
            is Rg08Operation.IngestImportedCollectionCandidate ->
                mapOf(
                    operation.input.agreementSourceId.value to
                        listOf(
                            Rg08ReturnedId.SourceRecord(operation.input.agreementSourceId),
                            Rg08ReturnedId.Evidence(operation.ids.agreementEvidenceId),
                            Rg08ReturnedId.EvidenceLink(operation.ids.agreementEvidenceLinkId),
                            Rg08ReturnedId.Position(relationshipPositionId(operation.input.agreementCounterpartyId)),
                        ),
                )
            is Rg08Operation.MergeImportedEvidence ->
                mapOf(
                    operation.input.requestId.value to
                        listOf(
                            Rg08ReturnedId.SourceRecord(operation.input.sourceId),
                            Rg08ReturnedId.Evidence(operation.input.evidenceId),
                            Rg08ReturnedId.EvidenceLink(operation.input.evidenceLinkId),
                            Rg08ReturnedId.TargetPosting(operation.input.targetPostingId),
                        ),
                )
            else -> emptyMap()
        }

    private fun relationshipPositionId(counterpartyId: String): String =
        positions.firstOrNull { it.counterpartyId == counterpartyId }?.id
            ?: error("RG-08 relationship anchor requires an existing position")

    private fun buildConfirmedCandidate(
        pending: LendingCandidate,
        input: Rg08ConfirmImportedInput,
        ids: Rg08ConfirmImportedIds,
    ): LendingCandidate? {
        val result =
            createLendingCandidate(
                id = pending.id,
                type = pending.type,
                status = LendingCandidateStatus.CONFIRMED,
                proposedTotalReceivedMinor = pending.proposedTotalReceivedMinor,
                proposedPrincipalAmountMinor = input.principalAmount.minorUnits,
                proposedInterestAmountMinor = input.interestAmount.minorUnits,
                proposedFeeAmountMinor = input.feeAmount.minorUnits,
                currency = pending.currency,
                proposedDestinationAccountId = input.destinationAccountId,
                proposedActualReceiptAt = input.actualReceiptAt,
                proposedBehaviorCode = LendingBehaviorCode.COLLECT,
                proposedCounterpartyId = input.counterpartyId,
                sourceIds = pending.sourceIds,
                ruleVersion = pending.ruleVersion,
                confidence = pending.confidence,
                statusHistory =
                    pending.statusHistory +
                        LendingCandidateStatusHistoryEntry(
                            id = ids.candidateConfirmedHistoryId,
                            status = LendingCandidateStatus.CONFIRMED,
                            occurredAt = input.confirmedAt,
                            formalEffectCount = 1,
                        ),
            )
        return when (result) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> null
        }
    }

    private fun resolvePosition(
        linkedPositionId: String?,
        counterpartyId: String,
    ): LendingPosition? {
        if (linkedPositionId != null) {
            return positions.firstOrNull { it.id == linkedPositionId }
        }
        return positions.firstOrNull { it.counterpartyId == counterpartyId }
    }

    private fun exactInterestAccountId(categoryId: CategoryId): com.unifiedledger.domain.Account? {
        val category =
            catalog.categories.firstOrNull { it.id == categoryId }
                ?: return null
        if (category.kind != CategoryKind.INCOME || !category.active) return null
        val account = category.postingAccountId?.let(::catalogAccount) ?: return null
        if (account.kind != AccountKind.INCOME) return null
        return account
    }

    private fun buildLendTransaction(
        ledgerId: LedgerId,
        input: Rg08LendInput,
        ids: Rg08LendIds,
        receivableAccountId: AccountId,
        principalMinor: Long,
    ): DomainResult<FormalTransaction> {
        val postingSet =
            when (
                val result =
                    PostingSet.create(
                        ids.postingSetId,
                        listOf(
                            Posting(ids.receivablePostingId, receivableAccountId, Money.ofMinor(principalMinor, input.currency)),
                            Posting(ids.fundingPostingId, input.fundingAccountId, Money.ofMinor(checkedNegate(principalMinor) ?: return DomainResult.Failure(DomainResultFailureViolation), input.currency)),
                        ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return DomainResult.Failure(DomainResultFailureViolation)
            }
        return FormalTransaction.create(
            Transaction(ids.transactionId, ledgerId, TransactionKind.LEND, ids.versionId),
            versions =
                listOf(
                    TransactionVersion(
                        ids.versionId,
                        ids.transactionId,
                        versionNumber = 1,
                        postingSetId = ids.postingSetId,
                        times = TransactionTimes(input.actualAt, input.actualAt, input.actualAt),
                    ),
                ),
            postingSets = listOf(postingSet),
        )
    }

    private fun buildCollectTransaction(
        ledgerId: LedgerId,
        ids: Rg08SettlementIds,
        destinationAccountId: AccountId,
        receivableAccountId: AccountId,
        interestAccountId: com.unifiedledger.domain.Account,
        totalReceivedMinor: Long,
        principalMinor: Long,
        interestMinor: Long,
        currency: CurrencyUnit,
        actualReceiptAt: Instant,
    ): DomainResult<FormalTransaction> {
        val postingSet =
            when (
                val result =
                    PostingSet.create(
                        ids.postingSetId,
                        listOf(
                            Posting(ids.destinationPostingId, destinationAccountId, Money.ofMinor(totalReceivedMinor, currency)),
                            Posting(ids.principalPostingId, receivableAccountId, Money.ofMinor(checkedNegate(principalMinor) ?: return DomainResult.Failure(DomainResultFailureViolation), currency)),
                            Posting(ids.interestPostingId, interestAccountId.id, Money.ofMinor(checkedNegate(interestMinor) ?: return DomainResult.Failure(DomainResultFailureViolation), currency)),
                        ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return DomainResult.Failure(DomainResultFailureViolation)
            }
        return FormalTransaction.create(
            Transaction(ids.transactionId, ledgerId, TransactionKind.COLLECT, ids.versionId),
            versions =
                listOf(
                    TransactionVersion(
                        ids.versionId,
                        ids.transactionId,
                        versionNumber = 1,
                        postingSetId = ids.postingSetId,
                        times = TransactionTimes(actualReceiptAt, actualReceiptAt, actualReceiptAt),
                    ),
                ),
            postingSets = listOf(postingSet),
        )
    }

    private fun toSettlementIds(ids: Rg08ConfirmImportedIds): Rg08SettlementIds =
        Rg08SettlementIds(
            transactionId = ids.transactionId,
            versionId = ids.versionId,
            postingSetId = ids.postingSetId,
            destinationPostingId = ids.destinationPostingId,
            principalPostingId = ids.principalPostingId,
            interestPostingId = ids.interestPostingId,
            settlementId = ids.settlementId,
            settlementHistoryId = ids.settlementHistoryId,
            positionHistoryId = ids.positionHistoryId,
            confirmationId = ids.confirmationId,
            principalComponentId = ids.principalComponentId,
            interestComponentId = ids.interestComponentId,
            feeComponentId = ids.feeComponentId,
        )

    private fun gateReason(field: LendingConfirmationGateField): Rg08RejectionReason =
        when (field) {
            LendingConfirmationGateField.BEHAVIOR_CODE -> Rg08RejectionReason.BEHAVIOR_CONFIRMATION_REQUIRED
            LendingConfirmationGateField.COUNTERPARTY_ID -> Rg08RejectionReason.COUNTERPARTY_CONFIRMATION_REQUIRED
            LendingConfirmationGateField.DESTINATION_ACCOUNT_ID -> Rg08RejectionReason.DESTINATION_CONFIRMATION_REQUIRED
            LendingConfirmationGateField.PRINCIPAL_AMOUNT -> Rg08RejectionReason.PRINCIPAL_CONFIRMATION_REQUIRED
            LendingConfirmationGateField.INTEREST_AND_FEE_AMOUNTS -> Rg08RejectionReason.INTEREST_AND_FEE_CONFIRMATION_REQUIRED
            LendingConfirmationGateField.ACTUAL_RECEIPT_TIME -> Rg08RejectionReason.ACTUAL_RECEIPT_TIME_CONFIRMATION_REQUIRED
        }

    private fun gateFieldPath(field: LendingConfirmationGateField): Rg08FieldPath =
        when (field) {
            LendingConfirmationGateField.BEHAVIOR_CODE -> Rg08FieldPath.INPUT_BEHAVIOR_CODE
            LendingConfirmationGateField.COUNTERPARTY_ID -> Rg08FieldPath.INPUT_COUNTERPARTY_ID
            LendingConfirmationGateField.DESTINATION_ACCOUNT_ID -> Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID
            LendingConfirmationGateField.PRINCIPAL_AMOUNT -> Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT
            LendingConfirmationGateField.INTEREST_AND_FEE_AMOUNTS -> Rg08FieldPath.INPUT_INTEREST_AND_FEE_AMOUNTS
            LendingConfirmationGateField.ACTUAL_RECEIPT_TIME -> Rg08FieldPath.INPUT_ACTUAL_RECEIPT_TIME
        }

    private fun domainRejected(
        violation: com.unifiedledger.domain.DomainViolation,
        fieldPath: Rg08FieldPath,
    ): Rg08ExecutionResult =
        when (violation) {
            is LendingViolation.InvalidLendingBehavior ->
                rejected(Rg08RejectionReason.INVALID_LENDING_BEHAVIOR, Rg08FieldPath.INPUT_BEHAVIOR_CODE)
            is LendingViolation.UnknownAccount ->
                rejected(Rg08RejectionReason.UNKNOWN_ACCOUNT, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
            is LendingViolation.OwnedAccountRequired ->
                rejected(Rg08RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
            is LendingViolation.FinancialAssetAccountRequired ->
                rejected(Rg08RejectionReason.FINANCIAL_ASSET_ACCOUNT_REQUIRED, Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID)
            is LendingViolation.ActiveExactInterestCategoryRequired ->
                rejected(Rg08RejectionReason.ACTIVE_EXACT_INTEREST_CATEGORY_REQUIRED, Rg08FieldPath.INPUT_INTEREST_CATEGORY_ID)
            is LendingViolation.SameCurrencyRequired ->
                rejected(Rg08RejectionReason.SAME_CURRENCY_REQUIRED, Rg08FieldPath.INPUT_CURRENCY)
            is LendingViolation.TotalMustBePositive ->
                rejected(Rg08RejectionReason.TOTAL_MUST_BE_POSITIVE, Rg08FieldPath.INPUT_TOTAL_RECEIVED)
            is LendingViolation.InvalidComponentSet ->
                rejected(Rg08RejectionReason.COMPONENTS_MUST_EQUAL_TOTAL, Rg08FieldPath.INPUT_COMPONENTS)
            is LendingViolation.ComponentMustBeNonnegative ->
                rejected(Rg08RejectionReason.COMPONENT_MUST_BE_NONNEGATIVE, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            is LendingViolation.FeeMustBeZeroInRg08V1 ->
                rejected(Rg08RejectionReason.FEE_MUST_BE_ZERO_IN_RG08_V1, Rg08FieldPath.INPUT_FEE_AMOUNT)
            is LendingViolation.NonzeroFeeAccountingOutOfScope ->
                rejected(Rg08RejectionReason.NONZERO_FEE_ACCOUNTING_OUT_OF_SCOPE, Rg08FieldPath.INPUT_FEE_AMOUNT)
            is LendingViolation.ComponentPostingIdInvalid ->
                rejected(Rg08RejectionReason.DOMAIN_REJECTED, fieldPath)
            is LendingViolation.ComponentsMustEqualTotal ->
                rejected(Rg08RejectionReason.COMPONENTS_MUST_EQUAL_TOTAL, Rg08FieldPath.INPUT_COMPONENTS)
            is LendingViolation.PrincipalExceedsOutstandingPosition ->
                rejected(Rg08RejectionReason.PRINCIPAL_EXCEEDS_OUTSTANDING_POSITION, Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT)
            is LendingViolation.ConfirmationRequired -> {
                val field = violation.field
                rejected(gateReason(field), gateFieldPath(field))
            }
            is LendingViolation.ExplicitComponentSplitRequired ->
                rejected(Rg08RejectionReason.EXPLICIT_COMPONENT_SPLIT_REQUIRED, Rg08FieldPath.INPUT_SPLIT_SOURCE)
            is LendingViolation.AutoConfirmationNotPermitted,
            is LendingViolation.AllocatedLendNotSupportedInV1,
            is LendingViolation.InvalidCandidateLifecycle,
            is LendingViolation.CandidateSourceRequired,
            is LendingViolation.InvalidSourceRecord,
            is LendingViolation.PayloadHashRequired,
            is LendingViolation.PayloadHashMismatch,
            is LendingViolation.BankEconomicTimesRequired,
            is LendingViolation.BankAmountRequired,
            is LendingViolation.CurrencyRequiredWithAmount,
            is LendingViolation.AgreementCounterpartyRequired,
            is LendingViolation.InvalidMirrorReference,
            is LendingViolation.InvalidEvidenceSourceType,
            is LendingViolation.InvalidEvidenceLink,
            is LendingViolation.InvalidAuditLink,
            is LendingViolation.ConfirmationRoleMismatch,
            is LendingViolation.InvalidConfirmationProvenance,
            is LendingViolation.UnsupportedAllocationScope,
            is LendingViolation.ContractAllocationNotSupported,
            is LendingViolation.PrincipalBalanceMustNotBeNegative,
            is LendingViolation.HistoryMustBeAppendOnly,
            is LendingViolation.InvalidPositionHistoryDirection,
            is LendingViolation.InvalidSettlementLifecycle,
            is LendingViolation.InvalidFormalEffectCount,
            ->
                rejected(Rg08RejectionReason.INVALID_RG08_INPUT, fieldPath)
            else -> rejected(Rg08RejectionReason.DOMAIN_REJECTED, fieldPath)
        }

    private fun catalogAccount(id: AccountId): Account? = catalog.accounts.firstOrNull { it.id == id }

    private fun postingExists(id: PostingId): Boolean =
        formalTransactions
            .asSequence()
            .flatMap { it.formalTransaction.currentPostings().asSequence() }
            .any { it.id == id }

    private fun replayBalances(): Map<AccountId, Money> =
        buildMap {
            catalog.accounts.forEach { account ->
                var total = 0L
                formalTransactions
                    .filter { it.formalTransaction.transaction.ledgerId == account.ledgerId }
                    .forEach { record ->
                        record.formalTransaction
                            .currentPostings()
                            .filter { it.accountId == account.id }
                            .forEach { posting ->
                                check(posting.amount.currency == account.currency) { "RG-08 posting currency mismatch" }
                                total = checkedAdd(total, posting.amount.minorUnits) ?: error("RG-08 balance overflow")
                            }
                    }
                put(account.id, Money.ofMinor(total, account.currency))
            }
        }

    private fun reports(): Map<String, Rg08Report> {
        val periods = linkedMapOf<String, Rg08Report>()
        formalTransactions.forEach { record ->
            if (record.formalTransaction.transaction.kind !in LENDING_TRANSACTION_KINDS) return@forEach
            val report =
                when (record.formalTransaction.transaction.kind) {
                    TransactionKind.LEND -> {
                        val receivablePosting =
                            record.formalTransaction
                                .currentPostings()
                                .first { postingSemantics[it.id.value]?.role == "RECEIVABLE" }
                        Rg08Report(
                            lendingPrincipalCashOutflowMinor = receivablePosting.amount.minorUnits,
                            cashOutflowMinor = receivablePosting.amount.minorUnits,
                        )
                    }
                    TransactionKind.COLLECT -> {
                        val settlement =
                            settlements.firstOrNull { it.transactionId == record.formalTransaction.transaction.id }
                                ?: return@forEach
                        val principal = settlement.components.first { it.kind == LendingComponentKind.PRINCIPAL }.amountMinor
                        val interest = settlement.components.first { it.kind == LendingComponentKind.INTEREST }.amountMinor
                        val total = settlement.totalReceivedMinor
                        Rg08Report(
                            lendingPrincipalCashInflowMinor = principal,
                            interestCashInflowMinor = interest,
                            totalCashInflowMinor = total,
                            ordinaryInterestIncomeMinor = interest,
                            ordinaryIncomeMinor = interest,
                            netWorthChangeMinor = interest,
                        )
                    }
                    else -> Rg08Report()
                }
            val period =
                (record.statisticsAtText ?: record.formalTransaction.currentStatisticsAtText())
                    .substring(0, 7)
            val current = periods[period] ?: Rg08Report()
            periods[period] = mergeReports(current, report)
        }
        val cumulative = periods.values.fold(Rg08Report()) { acc, report -> mergeReports(acc, report) }
        return buildMap {
            periods.forEach { (period, report) -> put(period, report) }
            put("cumulative", cumulative)
        }
    }

    private fun mergeReports(
        left: Rg08Report,
        right: Rg08Report,
    ): Rg08Report =
        Rg08Report(
            consumptionMinor = checkedAdd(left.consumptionMinor, right.consumptionMinor)!!,
            expenseMinor = checkedAdd(left.expenseMinor, right.expenseMinor)!!,
            lendingPrincipalCashOutflowMinor = checkedAdd(left.lendingPrincipalCashOutflowMinor, right.lendingPrincipalCashOutflowMinor)!!,
            cashOutflowMinor = checkedAdd(left.cashOutflowMinor, right.cashOutflowMinor)!!,
            lendingPrincipalCashInflowMinor = checkedAdd(left.lendingPrincipalCashInflowMinor, right.lendingPrincipalCashInflowMinor)!!,
            interestCashInflowMinor = checkedAdd(left.interestCashInflowMinor, right.interestCashInflowMinor)!!,
            totalCashInflowMinor = checkedAdd(left.totalCashInflowMinor, right.totalCashInflowMinor)!!,
            ordinaryInterestIncomeMinor = checkedAdd(left.ordinaryInterestIncomeMinor, right.ordinaryInterestIncomeMinor)!!,
            ordinaryIncomeMinor = checkedAdd(left.ordinaryIncomeMinor, right.ordinaryIncomeMinor)!!,
            netWorthChangeMinor = checkedAdd(left.netWorthChangeMinor, right.netWorthChangeMinor)!!,
        )

    private fun reconciliation(): Map<String, String> =
        buildMap {
            formalTransactions
                .filter { it.formalTransaction.transaction.kind in LENDING_TRANSACTION_KINDS }
                .forEach { record ->
                    val transaction = record.formalTransaction.transaction
                    val postings = record.formalTransaction.currentPostings()
                    var allEligibleMatched = true
                    postings.forEach { posting ->
                        val semantic = postingSemantics[posting.id.value] ?: return@forEach
                        val matched =
                            evidenceLinks.any {
                                it.targetId == posting.id.value &&
                                    it.role in RECONCILIATION_POSTING_ROLES &&
                                    it.status == LendingEvidenceLinkStatus.MATCHED
                            }
                        val status =
                            when {
                                !semantic.reconciliationEligible -> "not_applicable"
                                matched -> "matched"
                                else -> "pending"
                            }
                        put(posting.id.value, status)
                        if (semantic.reconciliationEligible && status != "matched") {
                            allEligibleMatched = false
                        }
                    }
                    put(transaction.id.value, if (allEligibleMatched) "complete" else "pending")
                }
        }

    private fun canAppendFormalTransaction(record: Rg08FormalTransactionRecord): Boolean {
        if (!catalogCompatible(record.formalTransaction) || formalIdCollision(record.formalTransaction)) {
            return false
        }
        val currentBalances = replayBalances()
        record.formalTransaction
            .currentPostings()
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
        val versionIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.versions.map { it.id }
            }
        val postingSetIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.map { it.id }
            }
        val postingIds =
            formalTransactions.flatMapTo(mutableSetOf()) { record ->
                record.formalTransaction.postingSets.flatMap { postingSet -> postingSet.postings.map { it.id } }
            }
        return formal.transaction.id in transactionIds ||
            formal.versions.any { it.id in versionIds } ||
            formal.postingSets.any { it.id in postingSetIds } ||
            formal.currentPostings().any { it.id in postingIds }
    }

    private fun FormalTransaction.currentStatisticsAtText(): String =
        versions
            .first { it.id == transaction.currentVersionId }
            .times.statisticsAt
            .toString()

    private fun canonicalInput(operation: Rg08Operation): String =
        when (operation) {
            is Rg08Operation.ValidateLendingEvent ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.behaviorCode,
                    operation.input.counterpartyId,
                    operation.input.fundingAccountId.value,
                    canonicalMoney(operation.input.principalAmount),
                    canonicalCurrency(operation.input.currency),
                    operation.input.actualAt.toString(),
                    operation.input.actualAtText,
                    operation.input.confirmedAt.toString(),
                    operation.input.confirmedAtText,
                    operation.input.explicitConfirmation.toString(),
                    operation.ids.transactionId.value,
                    operation.ids.versionId.value,
                    operation.ids.postingSetId.value,
                    operation.ids.receivablePostingId.value,
                    operation.ids.fundingPostingId.value,
                    operation.ids.positionId,
                    operation.ids.positionHistoryId,
                    operation.ids.confirmationId.value,
                    operation.ids.sourceId.value,
                    operation.ids.sourceRecordId,
                    operation.ids.sourceObservedAt.toString(),
                    operation.ids.sourceObservedAtText,
                    operation.ids.sourceAmountMinor.toString(),
                    operation.ids.evidenceId.value,
                    operation.ids.evidenceLinkId.value,
                )
            is Rg08Operation.ValidateLendingSettlement ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.behaviorCode,
                    operation.input.counterpartyId,
                    operation.input.linkedPositionId,
                    operation.input.allocatedLendTransactionId?.value,
                    operation.input.destinationAccountId.value,
                    canonicalMoney(operation.input.totalReceived),
                    canonicalMoney(operation.input.principalAmount),
                    canonicalMoney(operation.input.interestAmount),
                    canonicalMoney(operation.input.feeAmount),
                    operation.input.interestCategoryId.value,
                    canonicalCurrency(operation.input.currency),
                    operation.input.actualReceiptAt.toString(),
                    operation.input.actualReceiptAtText,
                    operation.input.confirmedAt.toString(),
                    operation.input.confirmedAtText,
                    operation.input.explicitConfirmation.toString(),
                    canonicalSettlementIds(operation.ids),
                )
            is Rg08Operation.IngestImportedCollectionCandidate ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.creditSourceId.value,
                    operation.input.creditSourceRecordId,
                    operation.input.creditObservedAt.toString(),
                    operation.input.creditObservedAtText,
                    operation.input.creditBookingAt.toString(),
                    operation.input.creditBookingAtText,
                    operation.input.creditValueAt.toString(),
                    operation.input.creditValueAtText,
                    operation.input.creditAccountId.value,
                    operation.input.creditAmountMinor.toString(),
                    canonicalCurrency(operation.input.creditCurrency),
                    operation.input.creditOriginalSourcePayloadHash,
                    operation.input.creditImmutablePayloadHash,
                    operation.input.agreementSourceId.value,
                    operation.input.agreementSourceRecordId,
                    operation.input.agreementObservedAt.toString(),
                    operation.input.agreementObservedAtText,
                    operation.input.agreementCounterpartyId,
                    canonicalCurrency(operation.input.agreementCurrency),
                    operation.input.agreementImmutablePayloadHash,
                    operation.input.candidateId.value,
                    operation.input.candidateType,
                    operation.input.proposedTotalReceivedMinor.toString(),
                    operation.input.proposedDestinationAccountId.value,
                    operation.input.proposedActualReceiptAt.toString(),
                    operation.input.proposedActualReceiptAtText,
                    operation.input.ruleVersion.toString(),
                    operation.input.confidence,
                    operation.ids.creditEvidenceId.value,
                    operation.ids.agreementEvidenceId.value,
                    operation.ids.agreementEvidenceLinkId.value,
                    operation.ids.candidateHistoryId,
                )
            is Rg08Operation.RejectIncompleteImportedConfirmation ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.candidateId.value,
                    operation.input.missingField.name,
                )
            is Rg08Operation.ConfirmImportedCollection ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.candidateId.value,
                    operation.input.behaviorCode,
                    operation.input.counterpartyId,
                    operation.input.destinationAccountId.value,
                    canonicalMoney(operation.input.principalAmount),
                    canonicalMoney(operation.input.interestAmount),
                    canonicalMoney(operation.input.feeAmount),
                    operation.input.interestCategoryId.value,
                    canonicalCurrency(operation.input.currency),
                    operation.input.actualReceiptAt.toString(),
                    operation.input.actualReceiptAtText,
                    operation.input.confirmedAt.toString(),
                    operation.input.confirmedAtText,
                    operation.input.explicitConfirmation.toString(),
                    operation.input.explicitlyConfirmedFields
                        .sorted()
                        .joinToString("|"),
                    canonicalSettlementIds(toSettlementIds(operation.ids)),
                    operation.ids.candidateConfirmedHistoryId,
                    operation.ids.destinationEvidenceLinkId.value,
                )
            is Rg08Operation.AllocateLendingCollection ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.counterpartyId,
                    operation.input.destinationAccountId.value,
                    canonicalMoney(operation.input.totalReceived),
                    canonicalMoney(operation.input.principalAmount),
                    canonicalMoney(operation.input.interestAmount),
                    canonicalMoney(operation.input.feeAmount),
                    canonicalCurrency(operation.input.currency),
                    operation.input.actualReceiptAt?.toString(),
                    operation.input.actualReceiptAtText,
                    operation.input.confirmedAt?.toString(),
                    operation.input.confirmedAtText,
                    canonicalSettlementIds(operation.input.ids),
                )
            is Rg08Operation.MergeImportedEvidence ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.sourceId.value,
                    operation.input.sourceRecordId,
                    operation.input.observedAt.toString(),
                    operation.input.observedAtText,
                    operation.input.amountMinor.toString(),
                    canonicalCurrency(operation.input.currency),
                    operation.input.mirrorOfSourceId,
                    operation.input.immutablePayloadHash,
                    operation.input.evidenceId.value,
                    operation.input.evidenceLinkId.value,
                    operation.input.targetPostingId.value,
                    operation.input.mirrorOfEvidenceId,
                    operation.input.mergedIntoEvidenceLinkId,
                )
            is Rg08Operation.RenameCounterparty ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.counterpartyId,
                    operation.input.oldDisplayName,
                    operation.input.newDisplayName,
                    operation.input.nameHistoryId,
                )
            is Rg08Operation.RetryIdempotentInput ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.inputId,
                )
            is Rg08Operation.InvalidInput ->
                canonicalFields(
                    operation.ledgerId.value,
                    operation.action.code,
                    operation.input.requestId.value,
                    operation.input.predicate.name,
                    operation.input.attemptedInput.entries.sortedBy { it.key }.joinToString("|") { (key, value) ->
                        "$key=${value ?: "<null>"}"
                    },
                )
        }

    private fun canonicalSettlementIds(ids: Rg08SettlementIds): String =
        canonicalFields(
            ids.transactionId.value,
            ids.versionId.value,
            ids.postingSetId.value,
            ids.destinationPostingId.value,
            ids.principalPostingId.value,
            ids.interestPostingId.value,
            ids.settlementId,
            ids.settlementHistoryId,
            ids.positionHistoryId,
            ids.confirmationId.value,
            ids.principalComponentId,
            ids.interestComponentId,
            ids.feeComponentId,
            ids.confirmationSourceId?.value,
            ids.confirmationSourceRecordId,
            ids.confirmationSourceObservedAt?.toString(),
            ids.confirmationSourceObservedAtText,
            ids.creditSourceId?.value,
            ids.creditSourceRecordId,
            ids.creditSourceObservedAt?.toString(),
            ids.creditSourceObservedAtText,
            ids.creditSourceBookingAt?.toString(),
            ids.creditSourceBookingAtText,
            ids.creditSourceValueAt?.toString(),
            ids.creditSourceValueAtText,
            ids.creditEvidenceId?.value,
            ids.creditEvidenceLinkId?.value,
        )

    private fun canonicalMoney(money: Money): String = "${money.minorUnits}:${canonicalCurrency(money.currency)}"

    private fun canonicalCurrency(currency: CurrencyUnit): String = "${currency.code}:${currency.precision}"

    private fun canonicalFields(vararg values: String?): String =
        buildString {
            values.forEach { value ->
                if (value == null) {
                    append("N;")
                } else {
                    append("V")
                        .append(value.length)
                        .append(':')
                        .append(value)
                        .append(';')
                }
            }
        }

    private fun accepted(ids: List<Rg08ReturnedId>) = Rg08ExecutionResult.Accepted(ids)

    private fun rejected(
        reason: Rg08RejectionReason,
        fieldPath: Rg08FieldPath,
    ) = Rg08ExecutionResult.Rejected(reason, fieldPath)

    private companion object {
        val LENDING_TRANSACTION_KINDS = setOf(TransactionKind.LEND, TransactionKind.COLLECT)
        val RECONCILIATION_POSTING_ROLES =
            setOf(
                LendingEvidenceLinkRole.DESTINATION_ASSET_POSTING,
                LendingEvidenceLinkRole.FUNDING_ASSET_POSTING,
            )
        const val LEND_DEBIT_PAYLOAD_HASH = "sha256:rg08-synthetic-lend-debit"
        const val MANUAL_CONFIRMATION_PAYLOAD_HASH = "sha256:rg08-synthetic-manual-confirmation"
        const val MANUAL_CREDIT_PAYLOAD_HASH = "sha256:rg08-synthetic-manual-credit"
    }
}

private val DomainResultFailureViolation = com.unifiedledger.domain.DomainViolation.InvalidFormalTransaction

private fun checkedAdd(
    left: Long,
    right: Long,
): Long? {
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedSubtract(
    left: Long,
    right: Long,
): Long? = checkedAdd(left, checkedNegate(right) ?: return null)

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value
