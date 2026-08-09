package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * D-085 RG-11/12 shared `explicit_operation_confirmation` entity. The frozen rg-11.json shape
 * is `{"type": "explicit_operation_confirmation", "operation_id": ..., "subject":
 * {"kind": "operation", "id": ...}, "payload": {}}` (fixture `main-correct-confirmation`).
 * The domain adds `created_at` as the confirmation time; the subject is always the operation
 * itself and the payload is the empty object.
 */
data class OperationSubjectRef(
    val kind: String,
    val id: String,
)

data class ExplicitOperationConfirmation(
    val id: String,
    val operationId: String,
    val subject: OperationSubjectRef,
    val createdAt: Instant,
    val payload: Map<String, String> = emptyMap(),
)

/**
 * D-085 RG-11/12 shared confirmation violations. Kept separate from
 * [PeriodicAllocationViolation] because the confirmation entity is shared with the RG-12
 * `correct_transaction_version` runtime.
 */
sealed interface ExplicitOperationConfirmationViolation : DomainViolation {
    data object IdentityRequired : ExplicitOperationConfirmationViolation

    data object SubjectOperationRequired : ExplicitOperationConfirmationViolation

    data object PayloadMustBeEmpty : ExplicitOperationConfirmationViolation
}

/**
 * Constructs an explicit operation confirmation for [operationId]. The subject is fixed to the
 * operation itself (`kind == "operation"`, `id == operationId`) and the payload stays the
 * empty object, matching the frozen contract shape.
 */
fun createExplicitOperationConfirmation(
    id: String,
    operationId: String,
    createdAt: Instant,
    payload: Map<String, String> = emptyMap(),
): DomainResult<ExplicitOperationConfirmation> {
    if (id.isBlank() || operationId.isBlank()) {
        return DomainResult.Failure(ExplicitOperationConfirmationViolation.IdentityRequired)
    }
    if (payload.isNotEmpty()) {
        return DomainResult.Failure(ExplicitOperationConfirmationViolation.PayloadMustBeEmpty)
    }
    return DomainResult.Success(
        ExplicitOperationConfirmation(
            id = id,
            operationId = operationId,
            subject = OperationSubjectRef(kind = "operation", id = operationId),
            createdAt = createdAt,
            payload = payload,
        ),
    )
}
