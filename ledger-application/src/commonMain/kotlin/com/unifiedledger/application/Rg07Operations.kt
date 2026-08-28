package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

data class Rg07OperationIdentity(
    val ledgerId: LedgerId,
    val value: String,
)

enum class Rg07Action(
    val code: String,
) {
    MANUAL_EXPENSE("manual_expense"),

    RECORD_REFUND_REQUEST_STATUS("record_refund_request_status"),

    INGEST_REFUND_STATUS_SOURCE("ingest_refund_status_source"),

    CONFIRM_MANUAL_REFUND_RECEIPT("confirm_manual_refund_receipt"),

    ATTACH_ORIGINAL_PAYMENT_EVIDENCE("attach_original_payment_evidence"),

    ATTACH_REFUND_DESTINATION_EVIDENCE("attach_refund_destination_evidence"),

    ATTACH_REFUND_DUAL_ROLE_EVIDENCE("attach_refund_dual_role_evidence"),

    CONFIRM_REFUND_RECEIPT("confirm_refund_receipt"),

    ALLOCATE_REFUND_RECEIPT("allocate_refund_receipt"),

    INGEST_REFUND_CREDIT_SOURCE("ingest_refund_credit_source"),

    CONFIRM_IMPORTED_REFUND("confirm_imported_refund"),

    MERGE_REFUND_MIRROR_EVIDENCE("merge_refund_mirror_evidence"),

    VALIDATE_REFUND_RECEIPT("validate_refund_receipt"),
}

enum class Rg07OperationClass { STATUS_TRANSITION, CREATION, RECONCILIATION, REJECTION }

enum class Rg07RefundStatus { REQUESTED, APPROVED, PROCESSING, RECEIVED }

enum class Rg07StatusSourceState { REQUESTED, APPROVED, PROCESSING }

/**

 * Strict closed input payloads. Every field maps to a registered expected input

 * key; nullable fields are absent in the approved sparse attempted_input and are

 * never synthesized by the adapters.

 */

data class Rg07ManualExpenseInput(
    val requestId: RequestId,
    val amount: Money,
    val categoryId: CategoryId,
    val paymentAccountId: AccountId,
    val occurredAt: Instant,
    val note: String,
    val explicitConfirmation: Boolean,
)

data class Rg07StatusInput(
    val requestId: RequestId,
    val originalTransactionId: TransactionId,
    val requestedAmount: Money,
    val requestedAt: Instant?,
    val approvedAt: Instant?,
    val processorReportedAt: Instant?,
)

data class Rg07StatusSourceInput(
    val sourceId: String,
    val refundRelationId: String,
    val observedAt: Instant,
    val reportedState: Rg07StatusSourceState,
    val provesArrival: Boolean,
)

data class Rg07ManualReceiptInput(
    val requestId: RequestId,
    val refundRelationId: String?,
    val originalTransactionId: TransactionId?,
    val amount: Money?,
    val categoryId: CategoryId?,
    val destinationAccountId: AccountId?,
    val sourceObservedAt: Instant?,
    val bookingAt: Instant?,
    val valueAt: Instant?,
    val arrivedAt: Instant?,
    val confirmedAt: Instant?,
    val confirmationMode: String?,
    val observationMode: String?,
    val arrivalConfirmed: Boolean,
)

data class Rg07OriginalPaymentEvidenceInput(
    val sourceId: String,
    val evidenceId: String,
    val paymentAssetPostingId: String,
    val amount: Money,
    val observedAt: Instant,
    val bookingAt: Instant,
    val valueAt: Instant,
    val immutablePayloadHash: String,
)

data class Rg07DestinationEvidenceInput(
    val sourceId: String,
    val evidenceId: String,
    val refundRelationId: String,
    val destinationAssetPostingId: String,
    val accountId: AccountId,
    val amount: Money,
    val bookingAt: Instant,
    val valueAt: Instant,
)

data class Rg07DualRoleEvidenceInput(
    val sourceId: String,
    val evidenceId: String,
    val refundRelationId: String,
    val destinationAssetPostingId: String,
    val observedAt: Instant,
    val roles: List<String>,
)

data class Rg07ConfirmReceiptInput(
    val requestId: RequestId,
    val originalTransactionId: TransactionId?,
    val amount: Money?,
    val categoryId: CategoryId?,
    val destinationAccountId: AccountId?,
    val arrivedAt: Instant?,
    val confirmedAt: Instant?,
    val arrivalConfirmed: Boolean,
)

data class Rg07AllocateInput(
    val candidateId: String,
    val requestedAllocation: Money,
    val availableAllocation: Money,
)

data class Rg07ImportCreditInput(
    val sourceId: String,
    val sourceRecordId: String,
    val accountId: AccountId,
    val amount: Money,
    val processorReportedAt: Instant,
    val sourceObservedAt: Instant,
    val bookingAt: Instant,
    val valueAt: Instant,
    val originalSourcePayloadHash: String,
)

data class Rg07ImportConfirmationInput(
    val requestId: String?,
    val candidateId: String,
    val originalTransactionId: TransactionId?,
    val categoryId: CategoryId?,
    val allocatedAmount: Money?,
    val destinationAccountId: AccountId?,
    val arrivedAt: Instant?,
    val confirmedAt: Instant?,
    val arrivalConfirmed: Boolean,
)

data class Rg07MirrorInput(
    val sourceId: String,
    val evidenceId: String,
    val requestId: RequestId,
    val observedAt: Instant,
    val amount: Money,
)

data class Rg07ValidateInput(
    val attemptId: String,
    val originalTransactionId: TransactionId?,
    val amount: Money,
    val categoryId: CategoryId?,
    val destinationAccountId: AccountId?,
    val destinationConfirmed: Boolean,
    val remainingRefundable: Money,
)

sealed interface Rg07Operation {
    val ledgerId: LedgerId

    val identity: Rg07OperationIdentity

    val action: Rg07Action

    val operationClass: Rg07OperationClass

    data class ManualExpense(
        val ledgerId0: LedgerId,
        val input: Rg07ManualExpenseInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.requestId.value),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.MANUAL_EXPENSE

        override val operationClass = Rg07OperationClass.CREATION
    }

    data class Status(
        val ledgerId0: LedgerId,
        val input: Rg07StatusInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.requestId.value),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.RECORD_REFUND_REQUEST_STATUS

        override val operationClass = Rg07OperationClass.STATUS_TRANSITION
    }

    data class StatusSource(
        val ledgerId0: LedgerId,
        val input: Rg07StatusSourceInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.sourceId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.INGEST_REFUND_STATUS_SOURCE

        override val operationClass = Rg07OperationClass.STATUS_TRANSITION
    }

    data class ManualReceipt(
        val ledgerId0: LedgerId,
        val input: Rg07ManualReceiptInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.requestId.value),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.CONFIRM_MANUAL_REFUND_RECEIPT

        override val operationClass = if (input.isCompleteAcceptedInput()) Rg07OperationClass.CREATION else Rg07OperationClass.REJECTION
    }

    data class OriginalPaymentEvidence(
        val ledgerId0: LedgerId,
        val input: Rg07OriginalPaymentEvidenceInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.sourceId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.ATTACH_ORIGINAL_PAYMENT_EVIDENCE

        override val operationClass = Rg07OperationClass.RECONCILIATION
    }

    data class DestinationEvidence(
        val ledgerId0: LedgerId,
        val input: Rg07DestinationEvidenceInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.sourceId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.ATTACH_REFUND_DESTINATION_EVIDENCE

        override val operationClass = Rg07OperationClass.RECONCILIATION
    }

    data class DualRoleEvidence(
        val ledgerId0: LedgerId,
        val input: Rg07DualRoleEvidenceInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.sourceId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.ATTACH_REFUND_DUAL_ROLE_EVIDENCE

        override val operationClass = Rg07OperationClass.RECONCILIATION
    }

    data class ConfirmReceipt(
        val ledgerId0: LedgerId,
        val input: Rg07ConfirmReceiptInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.requestId.value),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.CONFIRM_REFUND_RECEIPT

        override val operationClass = if (input.arrivalConfirmed) Rg07OperationClass.CREATION else Rg07OperationClass.REJECTION
    }

    data class Allocate(
        val ledgerId0: LedgerId,
        val input: Rg07AllocateInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.candidateId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.ALLOCATE_REFUND_RECEIPT

        override val operationClass = Rg07OperationClass.REJECTION
    }

    data class ImportCredit(
        val ledgerId0: LedgerId,
        val input: Rg07ImportCreditInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.sourceId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.INGEST_REFUND_CREDIT_SOURCE

        override val operationClass = Rg07OperationClass.CREATION
    }

    data class ImportConfirm(
        val ledgerId0: LedgerId,
        val input: Rg07ImportConfirmationInput,
        override val identity: Rg07OperationIdentity,
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.CONFIRM_IMPORTED_REFUND

        override val operationClass = if (isComplete()) Rg07OperationClass.CREATION else Rg07OperationClass.REJECTION

        /** The four registered imported-confirmation requirements. */

        fun isComplete(): Boolean =

            input.requestId != null &&

                input.arrivalConfirmed &&

                input.originalTransactionId != null &&

                input.categoryId != null &&

                input.allocatedAmount != null &&

                input.destinationAccountId != null &&

                input.arrivedAt != null &&

                input.confirmedAt != null
    }

    data class Mirror(
        val ledgerId0: LedgerId,
        val input: Rg07MirrorInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.sourceId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.MERGE_REFUND_MIRROR_EVIDENCE

        override val operationClass = Rg07OperationClass.RECONCILIATION
    }

    data class Validate(
        val ledgerId0: LedgerId,
        val input: Rg07ValidateInput,
        override val identity: Rg07OperationIdentity = Rg07OperationIdentity(ledgerId0, input.attemptId),
    ) : Rg07Operation {
        override val ledgerId get() = ledgerId0

        override val action = Rg07Action.VALIDATE_REFUND_RECEIPT

        override val operationClass = Rg07OperationClass.REJECTION
    }
}

enum class Rg07RejectionReason(
    val code: String,
) {
    ARRIVAL_CONFIRMATION_REQUIRED("arrival_confirmation_required"),

    KNOWN_DESTINATION_ACCOUNT_REQUIRED("known_destination_account_required"),

    EFFECTIVE_CONFIRMED_ORIGINAL_EXPENSE_REQUIRED("effective_confirmed_original_expense_required"),

    REFUND_AMOUNT_EXCEEDS_REMAINING("refund_amount_exceeds_remaining_refundable"),

    OWNED_REAL_ASSET_DESTINATION_REQUIRED("owned_real_asset_destination_required"),

    DESTINATION_CONFIRMATION_REQUIRED("destination_confirmation_required"),

    CATEGORY_ALLOCATION_CONFIRMATION_REQUIRED("category_allocation_confirmation_required"),

    ORIGINAL_TRANSACTION_CONFIRMATION_REQUIRED("original_transaction_confirmation_required"),

    MUST_BE_POSITIVE("must_be_positive"),

    EXACT_ORIGINAL_SECONDARY_CATEGORY_REQUIRED("exact_original_secondary_category_required"),

    SAME_CURRENCY_REQUIRED("same_currency_required"),

    /** Defensive guards for inputs the approved expected artifact never exercises. */

    CANDIDATE_NOT_FOUND("candidate_not_found"),

    DESTINATION_POSTING_NOT_FOUND("destination_posting_not_found"),

    REFUND_RELATION_NOT_FOUND("refund_relation_not_found"),

    MUST_BE_NEGATIVE("must_be_negative"),

    EVIDENCE_TARGET_MISMATCH("evidence_target_mismatch"),

    INVALID_TIMESTAMP("invalid_timestamp"),

    REQUEST_ID_REQUIRED("request_id_required"),

    INVALID_RECEIPT_MODE("invalid_receipt_mode"),
}

enum class Rg07FieldPath(
    val value: String,
) {
    AMOUNT("$.attempted_input.amount"),

    CURRENCY("$.attempted_input.currency"),

    ORIGINAL_TRANSACTION_ID("$.attempted_input.original_transaction_id"),

    DESTINATION_ACCOUNT_ID("$.attempted_input.destination_account_id"),

    CATEGORY_ID("$.attempted_input.category_id"),

    ARRIVAL_CONFIRMED("$.attempted_input.arrival_confirmed"),

    REQUESTED_ALLOCATION("$.attempted_input.requested_allocation"),

    ALLOCATED_AMOUNT("$.attempted_input.allocated_amount"),

    CANDIDATE_ID("$.attempted_input.candidate_id"),

    EVIDENCE_ID("$.attempted_input.evidence_id"),

    REFUND_RELATION_ID("$.attempted_input.refund_relation_id"),

    REQUEST_ID("$.attempted_input.request_id"),
}

sealed interface Rg07ExecutionResult {
    data class Accepted(
        val transactionId: TransactionId? = null,
        val relationId: String? = null,
        val returnedIds: List<Rg07ReturnedId> = emptyList(),
    ) : Rg07ExecutionResult

    data class NoChange(
        val transactionId: TransactionId? = null,
        val relationId: String? = null,
        val returnedIds: List<Rg07ReturnedId> = emptyList(),
    ) : Rg07ExecutionResult

    data class Rejected(
        val reason: Rg07RejectionReason,
        val fieldPath: Rg07FieldPath,
    ) : Rg07ExecutionResult

    data object RequestIdentityConflict : Rg07ExecutionResult
}

sealed interface Rg07ReturnedId {
    data class Confirmation(
        val id: String,
    ) : Rg07ReturnedId

    data class Transaction(
        val id: String,
    ) : Rg07ReturnedId

    data class Source(
        val id: String,
    ) : Rg07ReturnedId

    data class Evidence(
        val id: String,
    ) : Rg07ReturnedId

    data class EvidenceLink(
        val id: String,
    ) : Rg07ReturnedId

    data class Candidate(
        val id: String,
    ) : Rg07ReturnedId

    data class Relation(
        val id: String,
    ) : Rg07ReturnedId

    data class DomainEntity(
        val id: String,
    ) : Rg07ReturnedId
}

fun interface Rg07CommitPort {
    fun commit(operation: Rg07Operation): Rg07ExecutionResult
}

/**

 * Pure input validation before the commit port. Mirrors the frozen attempted-input

 * rejection order; database-backed checks stay in the commit port.

 */

class ExecuteRg07Operation(
    private val port: Rg07CommitPort,
) {
    fun execute(operation: Rg07Operation): Rg07ExecutionResult =

        when (operation) {
            is Rg07Operation.ManualExpense ->

                if (!operation.input.explicitConfirmation) {
                    rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)
                } else if (operation.input.amount.minorUnits <= 0L) {
                    rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.ManualReceipt ->

                if (!operation.input.arrivalConfirmed) {
                    rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)
                } else if (operation.input.refundRelationId == null) {
                    rejected(Rg07RejectionReason.REFUND_RELATION_NOT_FOUND, Rg07FieldPath.REFUND_RELATION_ID)
                } else if (!operation.input.isCompleteAcceptedInput()) {
                    rejected(Rg07RejectionReason.INVALID_RECEIPT_MODE, Rg07FieldPath.ARRIVAL_CONFIRMED)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.ConfirmReceipt ->

                if (!operation.input.arrivalConfirmed) {
                    rejected(Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED, Rg07FieldPath.ARRIVAL_CONFIRMED)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.ImportConfirm -> importedConfirmationFailure(operation)?.let { rejected(it.first, it.second) } ?: port.commit(operation)

            is Rg07Operation.Status ->

                if (operation.input.requestedAt == null && operation.input.approvedAt == null && operation.input.processorReportedAt == null) {
                    rejected(Rg07RejectionReason.INVALID_TIMESTAMP, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.StatusSource -> port.commit(operation)

            is Rg07Operation.OriginalPaymentEvidence ->

                if (operation.input.amount.minorUnits >= 0L) {
                    rejected(Rg07RejectionReason.MUST_BE_NEGATIVE, Rg07FieldPath.AMOUNT)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.DestinationEvidence ->

                if (operation.input.amount.minorUnits <= 0L) {
                    rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.DualRoleEvidence ->

                if (!isValidRg07DualRoleSet(operation.input.roles)) {
                    rejected(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.Allocate ->

                if (operation.input.requestedAllocation.minorUnits <= 0L) {
                    rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.REQUESTED_ALLOCATION)
                } else if (operation.input.requestedAllocation.minorUnits > operation.input.availableAllocation.minorUnits) {
                    rejected(Rg07RejectionReason.REFUND_AMOUNT_EXCEEDS_REMAINING, Rg07FieldPath.REQUESTED_ALLOCATION)
                } else {
                    rejected(Rg07RejectionReason.REFUND_AMOUNT_EXCEEDS_REMAINING, Rg07FieldPath.REQUESTED_ALLOCATION)
                }

            is Rg07Operation.ImportCredit ->

                if (operation.input.amount.minorUnits <= 0L) {
                    rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.Mirror ->

                if (operation.input.amount.minorUnits <= 0L) {
                    rejected(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)
                } else {
                    port.commit(operation)
                }

            is Rg07Operation.Validate -> port.commit(operation)
        }
}

private fun importedConfirmationFailure(operation: Rg07Operation.ImportConfirm): Pair<Rg07RejectionReason, Rg07FieldPath>? {
    val input = operation.input

    return when {
        input.requestId == null -> Rg07RejectionReason.REQUEST_ID_REQUIRED to Rg07FieldPath.REQUEST_ID

        input.originalTransactionId == null -> Rg07RejectionReason.ORIGINAL_TRANSACTION_CONFIRMATION_REQUIRED to Rg07FieldPath.ORIGINAL_TRANSACTION_ID

        input.categoryId == null || input.allocatedAmount == null -> Rg07RejectionReason.CATEGORY_ALLOCATION_CONFIRMATION_REQUIRED to Rg07FieldPath.CATEGORY_ID

        input.destinationAccountId == null -> Rg07RejectionReason.DESTINATION_CONFIRMATION_REQUIRED to Rg07FieldPath.DESTINATION_ACCOUNT_ID

        input.arrivedAt == null || input.confirmedAt == null || !input.arrivalConfirmed -> Rg07RejectionReason.ARRIVAL_CONFIRMATION_REQUIRED to Rg07FieldPath.ARRIVAL_CONFIRMED

        else -> null
    }
}

private fun rejected(
    reason: Rg07RejectionReason,
    path: Rg07FieldPath,
): Rg07ExecutionResult = Rg07ExecutionResult.Rejected(reason, path)

fun isValidRg07StatusTransition(
    previous: Rg07RefundStatus?,
    next: Rg07RefundStatus,
): Boolean =

    when (previous) {
        null -> next == Rg07RefundStatus.REQUESTED

        Rg07RefundStatus.REQUESTED -> next == Rg07RefundStatus.APPROVED

        Rg07RefundStatus.APPROVED -> next == Rg07RefundStatus.PROCESSING

        Rg07RefundStatus.PROCESSING -> next == Rg07RefundStatus.RECEIVED

        Rg07RefundStatus.RECEIVED -> false
    }

/** The registered dual-role evidence link roles, set-like. */

fun isValidRg07DualRoleSet(roles: List<String>): Boolean = roles.size == 2 && roles.toSet() == setOf("refund_relationship", "destination_asset_posting")

fun Rg07ManualReceiptInput.isCompleteAcceptedInput(): Boolean =

    arrivalConfirmed &&

        refundRelationId != null &&

        originalTransactionId != null &&

        amount != null &&

        categoryId != null &&

        destinationAccountId != null &&

        sourceObservedAt != null &&

        bookingAt != null &&

        valueAt != null &&

        arrivedAt != null &&

        confirmedAt != null &&

        confirmationMode == "explicit_manual_receipt" &&

        observationMode == "manual_account_observation"

/** Stable, closed payload used by persistence and replay conflict detection. */

fun Rg07Operation.fingerprint(): String =

    buildString {
        append(action.code).append('|').append(operationClass.name).append('|')

        when (this@fingerprint) {
            is Rg07Operation.ManualExpense -> append(input)

            is Rg07Operation.Status -> append(input)

            is Rg07Operation.StatusSource ->

                append(input.sourceId)
                    .append('|')
                    .append(input.observedAt)
                    .append('|')
                    .append(input.reportedState)
                    .append('|')
                    .append(input.provesArrival)

            is Rg07Operation.ManualReceipt -> append(input)

            is Rg07Operation.OriginalPaymentEvidence -> append(input)

            is Rg07Operation.DestinationEvidence -> append(input)

            is Rg07Operation.DualRoleEvidence -> append(input.copy(roles = input.roles.sorted()))

            is Rg07Operation.ConfirmReceipt -> append(input)

            is Rg07Operation.Allocate -> append(input)

            is Rg07Operation.ImportCredit -> append(input)

            is Rg07Operation.ImportConfirm -> append(input)

            is Rg07Operation.Mirror -> append(input)

            is Rg07Operation.Validate -> append(input)
        }
    }

data class Rg07AdaptedCase(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val catalog: LedgerCatalog,
)

sealed interface Rg07AdaptResult {
    data class Success(
        val operation: Rg07Operation,
    ) : Rg07AdaptResult

    data class Invalid(
        val reason: Rg07RejectionReason,
        val fieldPath: Rg07FieldPath,
    ) : Rg07AdaptResult
}

private fun Rg07AdaptedCase.positiveAmount(amount: Money): Rg07AdaptResult.Invalid? =

    if (amount.currency != currency) {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
    } else if (amount.minorUnits <= 0L) {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)
    } else {
        null
    }

fun adaptRg07ManualExpense(
    case: Rg07AdaptedCase,
    input: Rg07ManualExpenseInput,
): Rg07AdaptResult = case.positiveAmount(input.amount) ?: Rg07AdaptResult.Success(Rg07Operation.ManualExpense(case.ledgerId, input))

fun adaptRg07Status(
    case: Rg07AdaptedCase,
    input: Rg07StatusInput,
): Rg07AdaptResult =

    if (input.requestedAmount.currency != case.currency) {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
    } else if (input.requestedAmount.minorUnits <= 0L) {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.MUST_BE_POSITIVE, Rg07FieldPath.AMOUNT)
    } else if (input.requestedAt == null && input.approvedAt == null && input.processorReportedAt == null) {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.INVALID_TIMESTAMP, Rg07FieldPath.ORIGINAL_TRANSACTION_ID)
    } else {
        Rg07AdaptResult.Success(Rg07Operation.Status(case.ledgerId, input))
    }

fun adaptRg07StatusSource(
    case: Rg07AdaptedCase,
    input: Rg07StatusSourceInput,
): Rg07AdaptResult = Rg07AdaptResult.Success(Rg07Operation.StatusSource(case.ledgerId, input))

fun adaptRg07ManualReceipt(
    case: Rg07AdaptedCase,
    input: Rg07ManualReceiptInput,
): Rg07AdaptResult = Rg07AdaptResult.Success(Rg07Operation.ManualReceipt(case.ledgerId, input))

fun adaptRg07OriginalPaymentEvidence(
    case: Rg07AdaptedCase,
    input: Rg07OriginalPaymentEvidenceInput,
): Rg07AdaptResult {
    // The original bank_debit source keeps its signed amount (negative for an asset debit).

    if (input.amount.currency != case.currency) return Rg07AdaptResult.Invalid(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)

    if (input.amount.minorUnits >= 0L) return Rg07AdaptResult.Invalid(Rg07RejectionReason.MUST_BE_NEGATIVE, Rg07FieldPath.AMOUNT)

    return Rg07AdaptResult.Success(Rg07Operation.OriginalPaymentEvidence(case.ledgerId, input))
}

fun adaptRg07DestinationEvidence(
    case: Rg07AdaptedCase,
    input: Rg07DestinationEvidenceInput,
): Rg07AdaptResult = case.positiveAmount(input.amount) ?: Rg07AdaptResult.Success(Rg07Operation.DestinationEvidence(case.ledgerId, input))

fun adaptRg07DualRoleEvidence(
    case: Rg07AdaptedCase,
    input: Rg07DualRoleEvidenceInput,
): Rg07AdaptResult =

    if (isValidRg07DualRoleSet(input.roles)) {
        Rg07AdaptResult.Success(Rg07Operation.DualRoleEvidence(case.ledgerId, input))
    } else {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg07FieldPath.EVIDENCE_ID)
    }

fun adaptRg07ConfirmReceipt(
    case: Rg07AdaptedCase,
    input: Rg07ConfirmReceiptInput,
): Rg07AdaptResult = Rg07AdaptResult.Success(Rg07Operation.ConfirmReceipt(case.ledgerId, input))

fun adaptRg07Allocate(
    case: Rg07AdaptedCase,
    input: Rg07AllocateInput,
): Rg07AdaptResult =

    if (input.requestedAllocation.currency != case.currency || input.availableAllocation.currency != case.currency) {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
    } else {
        Rg07AdaptResult.Success(Rg07Operation.Allocate(case.ledgerId, input))
    }

fun adaptRg07ImportCredit(
    case: Rg07AdaptedCase,
    input: Rg07ImportCreditInput,
): Rg07AdaptResult = case.positiveAmount(input.amount) ?: Rg07AdaptResult.Success(Rg07Operation.ImportCredit(case.ledgerId, input))

fun adaptRg07ImportConfirmation(
    case: Rg07AdaptedCase,
    input: Rg07ImportConfirmationInput,
    identity: Rg07OperationIdentity,
): Rg07AdaptResult =

    if (input.allocatedAmount?.currency != null && input.allocatedAmount.currency != case.currency) {
        Rg07AdaptResult.Invalid(Rg07RejectionReason.SAME_CURRENCY_REQUIRED, Rg07FieldPath.CURRENCY)
    } else {
        Rg07AdaptResult.Success(Rg07Operation.ImportConfirm(case.ledgerId, input, identity))
    }

fun adaptRg07Mirror(
    case: Rg07AdaptedCase,
    input: Rg07MirrorInput,
): Rg07AdaptResult = case.positiveAmount(input.amount) ?: Rg07AdaptResult.Success(Rg07Operation.Mirror(case.ledgerId, input))

fun adaptRg07Validate(
    case: Rg07AdaptedCase,
    input: Rg07ValidateInput,
): Rg07AdaptResult =

    // Structural adapter only: amount/currency/remaining-cap semantics are

    // decided by the store against the frozen expected rejection registry.

    Rg07AdaptResult.Success(Rg07Operation.Validate(case.ledgerId, input))
