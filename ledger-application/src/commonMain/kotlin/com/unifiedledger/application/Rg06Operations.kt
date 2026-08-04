package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.InstallmentPaymentId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.StagedPaymentCreationIds
import com.unifiedledger.domain.StagedPaymentFulfillment
import com.unifiedledger.domain.StagedPaymentHistoryId
import com.unifiedledger.domain.StagedPaymentInstallmentIds
import com.unifiedledger.domain.StagedPaymentLifecycleId
import com.unifiedledger.domain.StagedPaymentRelationId
import com.unifiedledger.domain.StagedPaymentRole
import com.unifiedledger.domain.StagedPaymentResult
import com.unifiedledger.domain.StagedPaymentSourceTime
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

data class Rg06SourceId(val value: String)
data class Rg06EvidenceId(val value: String)
data class Rg06CandidateId(val value: String)
data class Rg06CandidateStatusId(val value: String)
data class Rg06EvidenceLinkId(val value: String)
data class Rg06ConfirmationId(val value: String)
data class Rg06ReconciliationId(val value: String)

data class Rg06OperationIdentity(
    val ledgerId: LedgerId,
    val value: String,
)

enum class Rg06Action(val code: String) {
    CREATE_STAGED_PAYMENT("create_staged_payment"),
    RECORD_STAGED_PAYMENT_INSTALLMENT("record_staged_payment_installment"),
    CHANGE_STAGED_PAYMENT_FULFILLMENT("change_staged_payment_fulfillment"),
    CONFIRM_STAGED_PAYMENT_COMPLETION("confirm_staged_payment_completion"),
    LINK_STAGED_PAYMENT_EVIDENCE("link_staged_payment_evidence"),
    INGEST_STAGED_PAYMENT_BANK_FACT("ingest_staged_payment_bank_fact"),
    CONFIRM_STAGED_PAYMENT_CANDIDATE("confirm_staged_payment_candidate"),
    MERGE_STAGED_PAYMENT_MIRROR_EVIDENCE("merge_staged_payment_mirror_evidence"),
}

data class Rg06CreateStagedPaymentInput(
    val requestId: RequestId,
    val totalAmount: Money,
    val categoryId: CategoryId?,
    val createdAt: Instant,
)

data class Rg06RecordStagedPaymentInstallmentInput(
    val requestId: RequestId,
    val relationId: StagedPaymentRelationId,
    val paymentRole: StagedPaymentRole,
    val paymentAmount: Money,
    val fundingAccountId: AccountId,
    val actualPaymentAt: Instant,
)

data class Rg06ChangeStagedPaymentFulfillmentInput(
    val requestId: RequestId,
    val relationId: StagedPaymentRelationId,
    val fulfillmentStatus: StagedPaymentFulfillment,
    val occurredAt: Instant,
)

data class Rg06ConfirmStagedPaymentCompletionInput(
    val requestId: RequestId,
    val relationId: StagedPaymentRelationId,
    val confirmed: Boolean,
    val occurredAt: Instant,
)

data class Rg06LinkStagedPaymentEvidenceInput(
    val sourceId: Rg06SourceId,
    val evidenceId: Rg06EvidenceId,
    val paymentId: InstallmentPaymentId,
    val postingId: PostingId,
)

/** Raw signed bank fact input. Generated candidate/status identities are deliberately absent. */
data class Rg06IngestStagedPaymentBankFactInput(
    val sourceId: Rg06SourceId,
    val evidenceId: Rg06EvidenceId,
    val sourcePaymentAt: Instant,
    val sourcePaymentAtText: String,
    val amount: Money,
    val suggestedPaymentRole: StagedPaymentRole?,
)

/** Confirmation binds stored candidate facts; provenance time is supplied only by the v1 adapter. */
data class Rg06ConfirmStagedPaymentCandidateInput(
    val requestId: RequestId,
    val candidateId: Rg06CandidateId,
    val relationId: StagedPaymentRelationId,
    val paymentRole: StagedPaymentRole,
    val categoryId: CategoryId,
    val fundingAccountId: AccountId,
    val exactBindingConfirmed: Boolean,
    /** Frozen v1 confirmation provenance; never derive this from payment/source time. */
    val confirmedAt: Instant? = null,
)

data class Rg06MergeStagedPaymentMirrorEvidenceInput(
    val sourceId: Rg06SourceId,
    val evidenceId: Rg06EvidenceId,
    val paymentId: InstallmentPaymentId,
    val postingId: PostingId,
    val amount: Money,
    val sourcePaymentAt: Instant,
    val sourcePaymentAtText: String,
)

enum class Rg06TypedValueFailure {
    INVALID_TIME,
    INVALID_TIME_VARIANT,
    INVALID_MIRROR_SOURCE_ID,
    INVALID_MIRROR_EVIDENCE_LINEAGE,
    AMOUNT_HAS_NO_POSITIVE_MAGNITUDE,
    CANDIDATE_STATUS_IDENTITY_COLLISION,
    INVALID_CANDIDATE_TRANSITION,
}

sealed interface Rg06TypedValueResult<out T> {
    data class Success<T>(val value: T) : Rg06TypedValueResult<T>
    data class Failure(val reason: Rg06TypedValueFailure) : Rg06TypedValueResult<Nothing>
}

sealed interface Rg06EvidenceTime {
    val value: StagedPaymentSourceTime
    val instant: Instant get() = value.instant
    val text: String get() = value.text
}

/** Exact intake observation time; it is not a formal payment or confirmation timestamp. */
class Rg06ObservedAt private constructor(
    override val value: StagedPaymentSourceTime,
) : Rg06EvidenceTime {
    override fun equals(other: Any?) = other is Rg06ObservedAt && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "Rg06ObservedAt(value=$value)"

    companion object {
        fun create(instant: Instant, text: String, expectedOffsetText: String): Rg06TypedValueResult<Rg06ObservedAt> =
            createEvidenceTime(instant, text, expectedOffsetText, ::Rg06ObservedAt)
    }
}

/** Exact imported payment-source time, validated against the case's offset policy. */
class Rg06SourcePaymentAt private constructor(
    override val value: StagedPaymentSourceTime,
) : Rg06EvidenceTime {
    override fun equals(other: Any?) = other is Rg06SourcePaymentAt && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "Rg06SourcePaymentAt(value=$value)"

    companion object {
        fun create(instant: Instant, text: String, expectedOffsetText: String): Rg06TypedValueResult<Rg06SourcePaymentAt> =
            createEvidenceTime(instant, text, expectedOffsetText, ::Rg06SourcePaymentAt)
    }
}

private fun <T> createEvidenceTime(
    instant: Instant,
    text: String,
    expectedOffsetText: String,
    create: (StagedPaymentSourceTime) -> T,
): Rg06TypedValueResult<T> = when (
    val result = StagedPaymentSourceTime.create(instant, text, expectedOffsetText)
) {
    is StagedPaymentResult.Success -> Rg06TypedValueResult.Success(create(result.value))
    is StagedPaymentResult.Failure -> Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME)
}

/** A pre-staged, immutable manual bank observation resolved by source/evidence identity. */
data class Rg06ManualBankObservation(
    val amount: Money,
    val observedAt: Rg06ObservedAt,
)

data class Rg06ManualObservationKey(
    val sourceId: Rg06SourceId,
    val evidenceId: Rg06EvidenceId,
)

class Rg06ImmutableBankFactPayload private constructor(
    val amount: Money,
    val observedTime: Rg06EvidenceTime,
) {
    override fun equals(other: Any?) =
        other is Rg06ImmutableBankFactPayload && amount == other.amount && observedTime == other.observedTime

    override fun hashCode() = 31 * amount.hashCode() + observedTime.hashCode()
    override fun toString() = "Rg06ImmutableBankFactPayload(amount=$amount, observedTime=$observedTime)"

    companion object {
        fun manual(
            amount: Money,
            observedTime: Rg06EvidenceTime,
        ): Rg06TypedValueResult<Rg06ImmutableBankFactPayload> =
            if (observedTime is Rg06ObservedAt) {
                Rg06TypedValueResult.Success(Rg06ImmutableBankFactPayload(amount, observedTime))
            } else {
                Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME_VARIANT)
            }

        fun imported(
            amount: Money,
            observedTime: Rg06EvidenceTime,
        ): Rg06TypedValueResult<Rg06ImmutableBankFactPayload> =
            if (observedTime is Rg06SourcePaymentAt) {
                Rg06TypedValueResult.Success(Rg06ImmutableBankFactPayload(amount, observedTime))
            } else {
                Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME_VARIANT)
            }
    }
}

class Rg06StagedPaymentBankSource private constructor(
    val ledgerId: LedgerId,
    val id: Rg06SourceId,
    val payload: Rg06ImmutableBankFactPayload,
    val mirrorOfSourceId: Rg06SourceId?,
) {
    override fun equals(other: Any?) =
        other is Rg06StagedPaymentBankSource && ledgerId == other.ledgerId && id == other.id &&
            payload == other.payload && mirrorOfSourceId == other.mirrorOfSourceId

    override fun hashCode() = arrayOf(ledgerId, id, payload, mirrorOfSourceId).contentHashCode()
    override fun toString() =
        "Rg06StagedPaymentBankSource(ledgerId=$ledgerId, id=$id, payload=$payload, mirrorOfSourceId=$mirrorOfSourceId)"

    companion object {
        fun manual(
            ledgerId: LedgerId,
            id: Rg06SourceId,
            amount: Money,
            observedTime: Rg06EvidenceTime,
        ): Rg06TypedValueResult<Rg06StagedPaymentBankSource> = when (
            val payload = Rg06ImmutableBankFactPayload.manual(amount, observedTime)
        ) {
            is Rg06TypedValueResult.Success -> Rg06TypedValueResult.Success(
                Rg06StagedPaymentBankSource(ledgerId, id, payload.value, null),
            )
            is Rg06TypedValueResult.Failure -> payload
        }

        fun importedOriginal(
            ledgerId: LedgerId,
            id: Rg06SourceId,
            amount: Money,
            observedTime: Rg06EvidenceTime,
        ): Rg06TypedValueResult<Rg06StagedPaymentBankSource> = when (
            val payload = Rg06ImmutableBankFactPayload.imported(amount, observedTime)
        ) {
            is Rg06TypedValueResult.Success -> Rg06TypedValueResult.Success(
                Rg06StagedPaymentBankSource(ledgerId, id, payload.value, null),
            )
            is Rg06TypedValueResult.Failure -> payload
        }

        fun mirror(
            ledgerId: LedgerId,
            id: Rg06SourceId,
            amount: Money,
            observedTime: Rg06EvidenceTime,
            originalSourceId: Rg06SourceId,
        ): Rg06TypedValueResult<Rg06StagedPaymentBankSource> {
            if (originalSourceId.value.isBlank() || originalSourceId == id) {
                return Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_MIRROR_SOURCE_ID)
            }
            return when (val payload = Rg06ImmutableBankFactPayload.imported(amount, observedTime)) {
                is Rg06TypedValueResult.Success -> Rg06TypedValueResult.Success(
                    Rg06StagedPaymentBankSource(ledgerId, id, payload.value, originalSourceId),
                )
                is Rg06TypedValueResult.Failure -> payload
            }
        }
    }
}

sealed interface Rg06StagedPaymentEvidence {
    val ledgerId: LedgerId
    val id: Rg06EvidenceId
    val sourceId: Rg06SourceId
    val observedTime: Rg06EvidenceTime
}

/** Pending evidence has no formal payment field by construction. */
data class Rg06PendingStagedPaymentEvidence(
    override val ledgerId: LedgerId,
    override val id: Rg06EvidenceId,
    override val sourceId: Rg06SourceId,
    override val observedTime: Rg06SourcePaymentAt,
) : Rg06StagedPaymentEvidence {
    fun bind(paymentId: InstallmentPaymentId): Rg06BoundStagedPaymentEvidence =
        Rg06BoundStagedPaymentEvidence.imported(
            ledgerId = ledgerId,
            id = id,
            sourceId = sourceId,
            sourcePaymentAt = observedTime,
            paymentId = paymentId,
        )
}

class Rg06BoundStagedPaymentEvidence private constructor(
    override val ledgerId: LedgerId,
    override val id: Rg06EvidenceId,
    override val sourceId: Rg06SourceId,
    override val observedTime: Rg06EvidenceTime,
    val paymentId: InstallmentPaymentId,
    val mirrorOfEvidenceId: Rg06EvidenceId?,
    val mergedIntoEvidenceLinkId: Rg06EvidenceLinkId?,
) : Rg06StagedPaymentEvidence {
    override fun equals(other: Any?): Boolean =
        other is Rg06BoundStagedPaymentEvidence &&
            ledgerId == other.ledgerId && id == other.id && sourceId == other.sourceId &&
            observedTime == other.observedTime && paymentId == other.paymentId &&
            mirrorOfEvidenceId == other.mirrorOfEvidenceId &&
            mergedIntoEvidenceLinkId == other.mergedIntoEvidenceLinkId

    override fun hashCode() = arrayOf(
        ledgerId, id, sourceId, observedTime, paymentId, mirrorOfEvidenceId, mergedIntoEvidenceLinkId,
    ).contentHashCode()

    companion object {
        fun manual(
            ledgerId: LedgerId,
            id: Rg06EvidenceId,
            sourceId: Rg06SourceId,
            observedAt: Rg06ObservedAt,
            paymentId: InstallmentPaymentId,
        ) = Rg06BoundStagedPaymentEvidence(
            ledgerId, id, sourceId, observedAt, paymentId, null, null,
        )

        fun imported(
            ledgerId: LedgerId,
            id: Rg06EvidenceId,
            sourceId: Rg06SourceId,
            sourcePaymentAt: Rg06SourcePaymentAt,
            paymentId: InstallmentPaymentId,
        ) = Rg06BoundStagedPaymentEvidence(
            ledgerId, id, sourceId, sourcePaymentAt, paymentId, null, null,
        )

        fun mirror(
            ledgerId: LedgerId,
            id: Rg06EvidenceId,
            sourceId: Rg06SourceId,
            sourcePaymentAt: Rg06SourcePaymentAt,
            paymentId: InstallmentPaymentId,
            mirrorOfEvidenceId: Rg06EvidenceId,
            mergedIntoEvidenceLinkId: Rg06EvidenceLinkId,
        ): Rg06TypedValueResult<Rg06BoundStagedPaymentEvidence> {
            if (
                ledgerId.value.isBlank() ||
                id.value.isBlank() ||
                sourceId.value.isBlank() ||
                paymentId.value.isBlank() ||
                mirrorOfEvidenceId.value.isBlank() ||
                mergedIntoEvidenceLinkId.value.isBlank() ||
                mirrorOfEvidenceId == id
            ) {
                return Rg06TypedValueResult.Failure(
                    Rg06TypedValueFailure.INVALID_MIRROR_EVIDENCE_LINEAGE,
                )
            }
            return Rg06TypedValueResult.Success(
                Rg06BoundStagedPaymentEvidence(
                    ledgerId, id, sourceId, sourcePaymentAt, paymentId,
                    mirrorOfEvidenceId, mergedIntoEvidenceLinkId,
                ),
            )
        }
    }
}

sealed interface Rg06CandidateRoleFact {
    data class Known(val role: StagedPaymentRole) : Rg06CandidateRoleFact
    data object ExplicitAmbiguous : Rg06CandidateRoleFact
}

enum class Rg06CandidateConfidence(val exactText: String) {
    CERTAIN("1.00"),
    AMBIGUOUS("0.50"),
}

enum class Rg06ConfirmationRequirement {
    RELATION_ID,
    PAYMENT_ROLE,
    CATEGORY_ID,
    FUNDING_ACCOUNT_ID,
}

val RG06_CONFIRMATION_REQUIREMENTS: List<Rg06ConfirmationRequirement>
    get() = listOf(
        Rg06ConfirmationRequirement.RELATION_ID,
        Rg06ConfirmationRequirement.PAYMENT_ROLE,
        Rg06ConfirmationRequirement.CATEGORY_ID,
        Rg06ConfirmationRequirement.FUNDING_ACCOUNT_ID,
    )

class Rg06StagedPaymentCandidatePayload private constructor(
    val roleFact: Rg06CandidateRoleFact,
    val amount: Money,
    val sourcePaymentAt: Rg06SourcePaymentAt,
    val evidenceId: Rg06EvidenceId,
) {
    val confidence: Rg06CandidateConfidence = when (roleFact) {
        is Rg06CandidateRoleFact.Known -> Rg06CandidateConfidence.CERTAIN
        Rg06CandidateRoleFact.ExplicitAmbiguous -> Rg06CandidateConfidence.AMBIGUOUS
    }
    val ruleVersion: Int = 1
    private val requirementSnapshot = RG06_CONFIRMATION_REQUIREMENTS.toList()
    val confirmationRequirements: List<Rg06ConfirmationRequirement>
        get() = requirementSnapshot.toMutableList()

    override fun equals(other: Any?): Boolean =
        other is Rg06StagedPaymentCandidatePayload &&
            roleFact == other.roleFact &&
            amount == other.amount &&
            sourcePaymentAt == other.sourcePaymentAt &&
            evidenceId == other.evidenceId &&
            ruleVersion == other.ruleVersion &&
            requirementSnapshot == other.requirementSnapshot

    override fun hashCode(): Int =
        arrayOf(roleFact, amount, sourcePaymentAt, evidenceId, ruleVersion, requirementSnapshot).contentHashCode()

    override fun toString(): String =
        "Rg06StagedPaymentCandidatePayload(roleFact=$roleFact, amount=$amount, sourcePaymentAt=$sourcePaymentAt, evidenceId=$evidenceId, ruleVersion=$ruleVersion, confirmationRequirements=$requirementSnapshot)"

    companion object {
        fun known(
            role: StagedPaymentRole,
            signedAmount: Money,
            sourcePaymentAt: Rg06SourcePaymentAt,
            evidenceId: Rg06EvidenceId,
        ): Rg06TypedValueResult<Rg06StagedPaymentCandidatePayload> =
            create(Rg06CandidateRoleFact.Known(role), signedAmount, sourcePaymentAt, evidenceId)

        fun ambiguous(
            signedAmount: Money,
            sourcePaymentAt: Rg06SourcePaymentAt,
            evidenceId: Rg06EvidenceId,
        ): Rg06TypedValueResult<Rg06StagedPaymentCandidatePayload> =
            create(Rg06CandidateRoleFact.ExplicitAmbiguous, signedAmount, sourcePaymentAt, evidenceId)

        private fun create(
            roleFact: Rg06CandidateRoleFact,
            signedAmount: Money,
            sourcePaymentAt: Rg06SourcePaymentAt,
            evidenceId: Rg06EvidenceId,
        ): Rg06TypedValueResult<Rg06StagedPaymentCandidatePayload> {
            val minor = signedAmount.minorUnits
            if (minor == 0L || minor == Long.MIN_VALUE) {
                return Rg06TypedValueResult.Failure(Rg06TypedValueFailure.AMOUNT_HAS_NO_POSITIVE_MAGNITUDE)
            }
            val magnitude = Money.ofMinor(if (minor < 0L) -minor else minor, signedAmount.currency)
            return Rg06TypedValueResult.Success(
                Rg06StagedPaymentCandidatePayload(roleFact, magnitude, sourcePaymentAt, evidenceId),
            )
        }
    }
}

enum class Rg06CandidateStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
}

data class Rg06CandidateStatusEntry(
    val id: Rg06CandidateStatusId,
    val sequence: Int,
    val status: Rg06CandidateStatus,
)

class Rg06StagedPaymentCandidate private constructor(
    val ledgerId: LedgerId,
    val id: Rg06CandidateId,
    val sourceId: Rg06SourceId,
    val confidence: Rg06CandidateConfidence,
    val payload: Rg06StagedPaymentCandidatePayload,
    statusHistory: List<Rg06CandidateStatusEntry>,
) {
    private val statusSnapshot = statusHistory.toList()
    val statusHistory: List<Rg06CandidateStatusEntry>
        get() = statusSnapshot.toMutableList()

    fun confirm(statusId: Rg06CandidateStatusId): Rg06TypedValueResult<Rg06StagedPaymentCandidate> {
        if (statusSnapshot.any { it.id == statusId }) {
            return Rg06TypedValueResult.Failure(
                Rg06TypedValueFailure.CANDIDATE_STATUS_IDENTITY_COLLISION,
            )
        }
        if (statusSnapshot.size != 1 || statusSnapshot.single().status != Rg06CandidateStatus.PENDING_CONFIRMATION) {
            return Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_CANDIDATE_TRANSITION)
        }
        return Rg06TypedValueResult.Success(
            Rg06StagedPaymentCandidate(
                ledgerId,
                id,
                sourceId,
                confidence,
                payload,
                statusSnapshot + Rg06CandidateStatusEntry(statusId, 2, Rg06CandidateStatus.CONFIRMED),
            ),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is Rg06StagedPaymentCandidate &&
            ledgerId == other.ledgerId && id == other.id && sourceId == other.sourceId &&
            confidence == other.confidence && payload == other.payload && statusSnapshot == other.statusSnapshot

    override fun hashCode(): Int =
        arrayOf(ledgerId, id, sourceId, confidence, payload, statusSnapshot).contentHashCode()

    override fun toString(): String =
        "Rg06StagedPaymentCandidate(ledgerId=$ledgerId, id=$id, sourceId=$sourceId, confidence=$confidence, payload=$payload, statusHistory=$statusSnapshot)"

    companion object {
        fun pending(
            ledgerId: LedgerId,
            id: Rg06CandidateId,
            sourceId: Rg06SourceId,
            payload: Rg06StagedPaymentCandidatePayload,
            pendingStatusId: Rg06CandidateStatusId,
        ): Rg06StagedPaymentCandidate = Rg06StagedPaymentCandidate(
            ledgerId,
            id,
            sourceId,
            payload.confidence,
            payload,
            listOf(Rg06CandidateStatusEntry(pendingStatusId, 1, Rg06CandidateStatus.PENDING_CONFIRMATION)),
        )
    }
}

data class Rg06ManualInstallmentCommitIds(
    val confirmationId: Rg06ConfirmationId,
    val paymentIds: StagedPaymentInstallmentIds,
    val reconciliationId: Rg06ReconciliationId,
)

data class Rg06IngestCommitIds(
    val candidateId: Rg06CandidateId,
    val pendingStatusId: Rg06CandidateStatusId,
)

data class Rg06CandidateConfirmationCommitIds(
    val confirmationId: Rg06ConfirmationId,
    val paymentIds: StagedPaymentInstallmentIds,
    val evidenceLinkId: Rg06EvidenceLinkId,
    val confirmedStatusId: Rg06CandidateStatusId,
    val reconciliationId: Rg06ReconciliationId,
)

sealed interface Rg06Operation {
    val ledgerId: LedgerId
    val action: Rg06Action
    val identity: Rg06OperationIdentity

    data class CreateStagedPayment(
        override val ledgerId: LedgerId,
        val input: Rg06CreateStagedPaymentInput,
        val ids: StagedPaymentCreationIds,
    ) : Rg06Operation {
        override val action = Rg06Action.CREATE_STAGED_PAYMENT
        override val identity = Rg06OperationIdentity(ledgerId, input.requestId.value)
    }

    data class RecordStagedPaymentInstallment(
        override val ledgerId: LedgerId,
        val input: Rg06RecordStagedPaymentInstallmentInput,
        val ids: Rg06ManualInstallmentCommitIds,
    ) : Rg06Operation {
        override val action = Rg06Action.RECORD_STAGED_PAYMENT_INSTALLMENT
        override val identity = Rg06OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ChangeStagedPaymentFulfillment(
        override val ledgerId: LedgerId,
        val input: Rg06ChangeStagedPaymentFulfillmentInput,
        val historyId: StagedPaymentHistoryId,
    ) : Rg06Operation {
        override val action = Rg06Action.CHANGE_STAGED_PAYMENT_FULFILLMENT
        override val identity = Rg06OperationIdentity(ledgerId, input.requestId.value)
    }

    data class ConfirmStagedPaymentCompletion(
        override val ledgerId: LedgerId,
        val input: Rg06ConfirmStagedPaymentCompletionInput,
        val historyId: StagedPaymentHistoryId,
    ) : Rg06Operation {
        override val action = Rg06Action.CONFIRM_STAGED_PAYMENT_COMPLETION
        override val identity = Rg06OperationIdentity(ledgerId, input.requestId.value)
    }

    data class LinkStagedPaymentEvidence(
        override val ledgerId: LedgerId,
        val input: Rg06LinkStagedPaymentEvidenceInput,
        val evidenceLinkId: Rg06EvidenceLinkId,
    ) : Rg06Operation {
        override val action = Rg06Action.LINK_STAGED_PAYMENT_EVIDENCE
        override val identity = Rg06OperationIdentity(ledgerId, input.sourceId.value)
    }

    data class IngestStagedPaymentBankFact(
        override val ledgerId: LedgerId,
        val input: Rg06IngestStagedPaymentBankFactInput,
        val ids: Rg06IngestCommitIds,
    ) : Rg06Operation {
        override val action = Rg06Action.INGEST_STAGED_PAYMENT_BANK_FACT
        override val identity = Rg06OperationIdentity(ledgerId, input.sourceId.value)
    }

    data class ConfirmStagedPaymentCandidate(
        override val ledgerId: LedgerId,
        val input: Rg06ConfirmStagedPaymentCandidateInput,
        val ids: Rg06CandidateConfirmationCommitIds,
    ) : Rg06Operation {
        override val action = Rg06Action.CONFIRM_STAGED_PAYMENT_CANDIDATE
        override val identity = Rg06OperationIdentity(ledgerId, input.requestId.value)
    }

    data class MergeStagedPaymentMirrorEvidence(
        override val ledgerId: LedgerId,
        val input: Rg06MergeStagedPaymentMirrorEvidenceInput,
    ) : Rg06Operation {
        override val action = Rg06Action.MERGE_STAGED_PAYMENT_MIRROR_EVIDENCE
        override val identity = Rg06OperationIdentity(ledgerId, input.sourceId.value)
    }
}

sealed interface Rg06ReturnedId {
    data class Relation(val id: StagedPaymentRelationId) : Rg06ReturnedId
    data class Lifecycle(val id: StagedPaymentLifecycleId) : Rg06ReturnedId
    data class Payment(val id: InstallmentPaymentId) : Rg06ReturnedId
    data class Transaction(val id: TransactionId) : Rg06ReturnedId
    data class Source(val id: Rg06SourceId) : Rg06ReturnedId
    data class Evidence(val id: Rg06EvidenceId) : Rg06ReturnedId
    data class Candidate(val id: Rg06CandidateId) : Rg06ReturnedId
    data class Confirmation(val id: Rg06ConfirmationId) : Rg06ReturnedId
    data class EvidenceLink(val id: Rg06EvidenceLinkId) : Rg06ReturnedId
}

enum class Rg06RejectionReason(val code: String) {
    MUST_BE_POSITIVE("must_be_positive"),
    SECONDARY_CATEGORY_REQUIRED("secondary_category_required"),
    CATEGORY_INACTIVE("category_inactive"),
    EXPENSE_CATEGORY_REQUIRED("expense_category_required"),
    DEPOSIT_MUST_BE_LESS_THAN_TOTAL("deposit_must_be_less_than_total"),
    PAYMENT_EXCEEDS_DUE("payment_exceeds_due"),
    FINAL_MUST_EQUAL_REMAINING_DUE("final_must_equal_remaining_due"),
    SINGLE_CURRENCY_REQUIRED("single_currency_required"),
    UNKNOWN_REAL_ACCOUNT("unknown_real_account"),
    REAL_FINANCIAL_ACCOUNT_REQUIRED("real_financial_account_required"),
    OWNED_ACCOUNT_REQUIRED("owned_account_required"),
    ASSET_ACCOUNT_REQUIRED("asset_account_required"),
    DUE_MUST_BE_ZERO("due_must_be_zero"),
    EXPLICIT_CONFIRMATION_REQUIRED("explicit_confirmation_required"),
    EXACT_BINDING_CONFIRMATION_REQUIRED("exact_binding_confirmation_required"),
    INVALID_SOURCE_TIME("invalid_source_payment_time"),
    CANDIDATE_NOT_FOUND("candidate_not_found"),
    CANDIDATE_NOT_PENDING("candidate_not_pending"),
    CANDIDATE_ROLE_MISMATCH("candidate_role_mismatch"),
    CANDIDATE_TARGET_MISMATCH("candidate_target_mismatch"),
    EVIDENCE_ALREADY_BOUND("evidence_already_bound"),
    EVIDENCE_TARGET_MISMATCH("evidence_target_mismatch"),
    MIRROR_TARGET_NOT_FOUND("mirror_target_not_found"),
    MIRROR_SOURCE_MISMATCH("mirror_source_mismatch"),
    RELATION_NOT_FOUND("relation_not_found"),
    CROSS_LEDGER_REFERENCE("cross_ledger_reference"),
    IDENTITY_COLLISION("identity_collision"),
    DOMAIN_REJECTED("domain_rejected"),
}

enum class Rg06FieldPath(val value: String) {
    ATTEMPTED_TOTAL_AMOUNT("$.attempted_input.total_amount"),
    ATTEMPTED_PAYMENT_AMOUNT("$.attempted_input.payment_amount"),
    ATTEMPTED_CURRENCY("$.attempted_input.currency"),
    ATTEMPTED_CATEGORY_ID("$.attempted_input.category_id"),
    ATTEMPTED_FUNDING_ACCOUNT_ID("$.attempted_input.funding_account_id"),
    ATTEMPTED_PAYMENT_PROGRESS("$.attempted_input.payment_progress"),
    INPUT_CONFIRMED("$.input.confirmed"),
    INPUT_EXACT_BINDING_CONFIRMED("$.input.exact_binding_confirmed"),
    INPUT_SOURCE_PAYMENT_AT("$.input.source_payment_at"),
    INPUT_AMOUNT("$.input.amount"),
    INPUT_SOURCE_ID("$.input.source_id"),
    INPUT_EVIDENCE_ID("$.input.evidence_id"),
    INPUT_CANDIDATE_ID("$.input.candidate_id"),
    INPUT_RELATION_ID("$.input.relation_id"),
    INPUT_PAYMENT_ID("$.input.payment_id"),
    INPUT_POSTING_ID("$.input.posting_id"),
    INPUT_PAYMENT_ROLE("$.input.payment_role"),
    INPUT_CATEGORY_ID("$.input.category_id"),
    GENERATED_IDENTITY("$.generated_ids"),
}

sealed interface Rg06ExecutionResult {
    class Accepted(returnedIds: List<Rg06ReturnedId>) : Rg06ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg06ReturnedId> get() = snapshot.toMutableList()
        override fun equals(other: Any?) = other is Accepted && snapshot == other.snapshot
        override fun hashCode() = snapshot.hashCode()
        override fun toString() = "Accepted(returnedIds=$snapshot)"
    }

    class NoChange(returnedIds: List<Rg06ReturnedId>) : Rg06ExecutionResult {
        private val snapshot = returnedIds.toList()
        val returnedIds: List<Rg06ReturnedId> get() = snapshot.toMutableList()
        override fun equals(other: Any?) = other is NoChange && snapshot == other.snapshot
        override fun hashCode() = snapshot.hashCode()
        override fun toString() = "NoChange(returnedIds=$snapshot)"
    }

    data class Rejected(
        val reason: Rg06RejectionReason,
        val fieldPath: Rg06FieldPath,
    ) : Rg06ExecutionResult

    data object RequestIdentityConflict : Rg06ExecutionResult
}

/**
 * Atomic commit boundary for the closed eight-action RG-06 family.
 *
 * A real implementation must atomically validate ownership, identities, current state, and all
 * writes. `(ledgerId, requestId/sourceId text)` is the operation identity. Its canonical value is
 * exactly the action plus that action's typed input payload. Identical replay returns the original
 * defensive ID snapshot and writes nothing; changed action/input conflicts. Proposal/generated IDs
 * are outside canonical identity and cannot replace original returned IDs. Rejection writes and
 * reserves nothing, so corrected input may reuse its request/source identity. Frozen semantic
 * validation and first-failure ordering precede every proposal/generated-identity collision check.
 *
 * Source, evidence, candidate, candidate-status, confirmation, reconciliation, relation, lifecycle,
 * history, payment, transaction, version, posting-set, expense-posting, asset-posting, and evidence-
 * link identities are set once, collision checked, and ledger owned. No operation may overwrite,
 * rebind, or cross-link another ledger's owner. Ingest atomically stores the signed
 * immutable source payload, byte-exact time text, unbound evidence, pending candidate payload,
 * confidence, provenance/rule version, confirmation requirements, and pending status. Confirmation
 * reads those stored facts (it cannot restate them), uses checked positive magnitude, appends only
 * the confirmed candidate status, binds evidence payment exactly once, and preserves source time
 * as payment occurrence/statistics/source time and text. Candidate status alone is never posting-
 * reconciliation authority. The same atomic candidate-confirmation commit that validates immutable
 * evidence and the explicit exact relation/role/category/account binding creates its exact eligible
 * `payment_asset` reconciliation as matched. A manual installment instead creates that posting
 * reconciliation as pending; only a later exact manual evidence link changes it to matched.
 *
 * Manual evidence linking must resolve a pre-staged immutable [Rg06ManualBankObservation] by both
 * source and evidence ID through the intake boundary; it must never synthesize observation amount
 * or time from a formal payment. Its signed magnitude/currency and target posting must match the
 * formal payment atomically. Imported and mirror observations must use [Rg06SourcePaymentAt], while
 * manual intake observations must use [Rg06ObservedAt]. The expected offset is supplied by case
 * policy to the factories and is not fixed by this contract.
 *
 * Mirror commit must resolve the existing original source/evidence/link, require same currency,
 * equal checked magnitude and opposite sign, append only mirror source/evidence lineage, return
 * only source/evidence IDs, and leave evidence links and reconciliation state unchanged. A
 * reconciliation transition changes no posting, balance, report, or cash-flow fact. Transaction and
 * staged-payment reconciliation projections derive from the stored posting reconciliation facts.
 * A real port must provide these all-or-nothing guarantees under concurrency and establish its own
 * adapter-level tests for them. The application module's private in-memory reference model is not
 * an importable adapter suite and does not establish database concurrency; that remains a separate
 * persistence-gate obligation.
 */
fun interface Rg06CommitPort {
    fun commit(operation: Rg06Operation): Rg06ExecutionResult
}

class ExecuteRg06Operation(private val port: Rg06CommitPort) {
    fun execute(operation: Rg06Operation): Rg06ExecutionResult = when (operation) {
        is Rg06Operation.ConfirmStagedPaymentCompletion -> if (!operation.input.confirmed) {
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED,
                Rg06FieldPath.INPUT_CONFIRMED,
            )
        } else port.commit(operation)

        is Rg06Operation.ConfirmStagedPaymentCandidate -> if (!operation.input.exactBindingConfirmed) {
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.EXACT_BINDING_CONFIRMATION_REQUIRED,
                Rg06FieldPath.INPUT_EXACT_BINDING_CONFIRMED,
            )
        } else port.commit(operation)

        else -> port.commit(operation)
    }
}
