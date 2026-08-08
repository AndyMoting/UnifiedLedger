package com.unifiedledger.domain

import kotlin.time.Instant

enum class LendingCandidateStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
}

/**
 * D-084 RG08-GAP-03 six-field confirmation gate. An imported collection stays pending until
 * every one of these six economic facts is explicitly confirmed; proposed values or name/bank
 * evidence never auto-confirm a gate. [reasonCode] is the frozen operation rejection reason.
 */
enum class LendingConfirmationGateField(val reasonCode: String) {
    BEHAVIOR_CODE("behavior_confirmation_required"),
    COUNTERPARTY_ID("counterparty_confirmation_required"),
    DESTINATION_ACCOUNT_ID("destination_confirmation_required"),
    PRINCIPAL_AMOUNT("principal_confirmation_required"),
    INTEREST_AND_FEE_AMOUNTS("interest_and_fee_confirmation_required"),
    ACTUAL_RECEIPT_TIME("actual_receipt_time_confirmation_required"),
    ;

    companion object {
        val ALL: List<LendingConfirmationGateField> = entries.toList()
    }
}

/** Append-only candidate lifecycle entry; `formalEffectCount` is zero while pending. */
data class LendingCandidateStatusHistoryEntry(
    val id: String,
    val status: LendingCandidateStatus,
    val occurredAt: Instant,
    val formalEffectCount: Int,
)

/**
 * D-084 RG08-GAP-03/04 imported lending collection candidate. It owns proposed economic facts,
 * the no-auto-confirm flags (always false in v1), the remaining explicit confirmation gates,
 * its sources and an append-only status history. `proposed_actual_receipt_at` is an independent
 * proposed economic time and is never derived from any created time.
 */
data class LendingCandidate(
    val id: String,
    val type: String,
    val status: LendingCandidateStatus,
    val proposedTotalReceivedMinor: Long,
    val proposedPrincipalAmountMinor: Long?,
    val proposedInterestAmountMinor: Long?,
    val proposedFeeAmountMinor: Long?,
    val currency: CurrencyUnit,
    val proposedDestinationAccountId: AccountId?,
    val proposedActualReceiptAt: Instant?,
    val proposedBehaviorCode: LendingBehaviorCode?,
    val proposedCounterpartyId: String?,
    val bankEvidenceProvesComponentSplit: Boolean,
    val expectedInterestMayConfirmSplit: Boolean,
    val nameMatchMayConfirmCounterparty: Boolean,
    val requiresConfirmation: List<LendingConfirmationGateField>,
    val sourceIds: List<String>,
    val ruleVersion: Int,
    val confidence: String,
    val statusHistory: List<LendingCandidateStatusHistoryEntry>,
)

/**
 * The pending lifecycle owns all six confirmation gates; the confirmed lifecycle owns none.
 * The gates are explicit confirmation requirements and are not recomputed from field null-ness.
 */
fun confirmationGatesFor(status: LendingCandidateStatus): List<LendingConfirmationGateField> =
    if (status == LendingCandidateStatus.PENDING_CONFIRMATION) {
        LendingConfirmationGateField.ALL
    } else {
        emptyList()
    }

/**
 * Constructs an imported collection candidate. Invariants (D-084 RG08-GAP-03/04, frozen fixture):
 * - the three no-auto-confirm flags are fixed to false (imported never auto-confirms);
 * - a component split is all-or-nothing and must match the proposed total when present;
 * - proposed amounts are non-negative; sources are non-empty and unique;
 * - status history starts pending, is append-only and ends with the candidate status.
 */
fun createLendingCandidate(
    id: String,
    type: String,
    status: LendingCandidateStatus = LendingCandidateStatus.PENDING_CONFIRMATION,
    proposedTotalReceivedMinor: Long,
    proposedPrincipalAmountMinor: Long? = null,
    proposedInterestAmountMinor: Long? = null,
    proposedFeeAmountMinor: Long? = null,
    currency: CurrencyUnit,
    proposedDestinationAccountId: AccountId? = null,
    proposedActualReceiptAt: Instant? = null,
    proposedBehaviorCode: LendingBehaviorCode? = null,
    proposedCounterpartyId: String? = null,
    bankEvidenceProvesComponentSplit: Boolean = false,
    expectedInterestMayConfirmSplit: Boolean = false,
    nameMatchMayConfirmCounterparty: Boolean = false,
    sourceIds: List<String>,
    ruleVersion: Int,
    confidence: String,
    statusHistory: List<LendingCandidateStatusHistoryEntry>,
): DomainResult<LendingCandidate> {
    if (
        bankEvidenceProvesComponentSplit ||
        expectedInterestMayConfirmSplit ||
        nameMatchMayConfirmCounterparty
    ) {
        return DomainResult.Failure(LendingViolation.AutoConfirmationNotPermitted)
    }
    if (id.isBlank() || type.isBlank() || confidence.isBlank()) {
        return DomainResult.Failure(LendingViolation.InvalidSourceRecord)
    }
    if (proposedTotalReceivedMinor <= 0L) {
        return DomainResult.Failure(LendingViolation.TotalMustBePositive)
    }
    val proposedSplit = listOf(proposedPrincipalAmountMinor, proposedInterestAmountMinor, proposedFeeAmountMinor)
    if (proposedSplit.any { it != null } && proposedSplit.any { it == null }) {
        return DomainResult.Failure(LendingViolation.ExplicitComponentSplitRequired)
    }
    if (proposedSplit.any { it != null }) {
        if (proposedSplit.any { it!! < 0L }) {
            return DomainResult.Failure(LendingViolation.ComponentMustBeNonnegative)
        }
        val principalPlusInterest = checkedAdd(proposedPrincipalAmountMinor!!, proposedInterestAmountMinor!!)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
        val composed = checkedAdd(principalPlusInterest, proposedFeeAmountMinor!!)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
        if (composed != proposedTotalReceivedMinor) {
            return DomainResult.Failure(LendingViolation.ComponentsMustEqualTotal)
        }
    }
    val sourceSnapshot = sourceIds.toList()
    if (sourceSnapshot.isEmpty() || sourceSnapshot.toSet().size != sourceSnapshot.size) {
        return DomainResult.Failure(LendingViolation.CandidateSourceRequired)
    }
    val historySnapshot = statusHistory.toList()
    if (
        historySnapshot.isEmpty() ||
        historySnapshot.map { it.id }.toSet().size != historySnapshot.size ||
        historySnapshot.first().status != LendingCandidateStatus.PENDING_CONFIRMATION ||
        historySnapshot.last().status != status ||
        historySnapshot.any { it.formalEffectCount < 0 }
    ) {
        return DomainResult.Failure(LendingViolation.InvalidCandidateLifecycle)
    }
    return DomainResult.Success(
        LendingCandidate(
            id = id,
            type = type,
            status = status,
            proposedTotalReceivedMinor = proposedTotalReceivedMinor,
            proposedPrincipalAmountMinor = proposedPrincipalAmountMinor,
            proposedInterestAmountMinor = proposedInterestAmountMinor,
            proposedFeeAmountMinor = proposedFeeAmountMinor,
            currency = currency,
            proposedDestinationAccountId = proposedDestinationAccountId,
            proposedActualReceiptAt = proposedActualReceiptAt,
            proposedBehaviorCode = proposedBehaviorCode,
            proposedCounterpartyId = proposedCounterpartyId,
            bankEvidenceProvesComponentSplit = bankEvidenceProvesComponentSplit,
            expectedInterestMayConfirmSplit = expectedInterestMayConfirmSplit,
            nameMatchMayConfirmCounterparty = nameMatchMayConfirmCounterparty,
            requiresConfirmation = confirmationGatesFor(status),
            sourceIds = sourceSnapshot,
            ruleVersion = ruleVersion,
            confidence = confidence,
            statusHistory = historySnapshot,
        ),
    )
}

/**
 * Atomic explicit confirmation transition. Every one of the six gates must be explicitly
 * confirmed or the transition is rejected fail-closed with the first missing gate
 * ([LendingViolation.ConfirmationRequired]); the status history is appended and nothing is
 * rewritten. Mirror/merge or any other operation never auto-confirms a candidate.
 */
fun confirmLendingCandidate(
    candidate: LendingCandidate,
    confirmedGates: Set<LendingConfirmationGateField>,
    historyId: String,
    confirmedAt: Instant,
    formalEffectCount: Int,
): DomainResult<LendingCandidate> {
    if (candidate.status != LendingCandidateStatus.PENDING_CONFIRMATION) {
        return DomainResult.Failure(LendingViolation.InvalidCandidateLifecycle)
    }
    if (formalEffectCount < 0) {
        return DomainResult.Failure(LendingViolation.InvalidFormalEffectCount)
    }
    val missing = LendingConfirmationGateField.ALL.firstOrNull { it !in confirmedGates }
    if (missing != null) {
        return DomainResult.Failure(LendingViolation.ConfirmationRequired(missing))
    }
    return DomainResult.Success(
        candidate.copy(
            status = LendingCandidateStatus.CONFIRMED,
            requiresConfirmation = emptyList(),
            statusHistory = candidate.statusHistory + LendingCandidateStatusHistoryEntry(
                id = historyId,
                status = LendingCandidateStatus.CONFIRMED,
                occurredAt = confirmedAt,
                formalEffectCount = formalEffectCount,
            ),
        ),
    )
}

enum class LendingSourceKind(val code: String) {
    BANK_DEBIT("bank_debit"),
    BANK_CREDIT("bank_credit"),
    BANK_CREDIT_MIRROR("bank_credit_mirror"),
    LENDING_AGREEMENT("lending_agreement"),
    EXPLICIT_MANUAL_LENDING_CONFIRMATION("explicit_manual_lending_confirmation"),
    ;

    companion object {
        fun fromCode(code: String): LendingSourceKind? =
            entries.firstOrNull { it.code == code }
    }
}

/**
 * D-084 RG08-GAP-03/04 source record. Bank records carry their own booking/value economic times
 * and immutable payload hash; imported bank credits additionally carry the original source
 * payload hash; mirrors reference their origin source; agreements carry the counterparty.
 */
data class LendingSourceRecord(
    val id: String,
    val sourceRecordId: String,
    val kind: LendingSourceKind,
    val observedAt: Instant,
    val bookingAt: Instant? = null,
    val valueAt: Instant? = null,
    val accountId: AccountId? = null,
    val counterpartyId: String? = null,
    val amountMinor: Long? = null,
    val currency: CurrencyUnit? = null,
    val originalSourcePayloadHash: String? = null,
    val immutablePayloadHash: String,
    val mirrorOfSourceId: String? = null,
)

/**
 * Constructs a source record. Invariants (D-084 RG08-GAP-03/04):
 * - bank debit/credit records require their own booking/value times (never derived from
 *   `observed_at` or any created time) plus amount and currency;
 * - an amount always carries its currency; an original payload hash, when present, must equal
 *   the immutable hash; the immutable hash is always required;
 * - only a bank credit mirror references an origin source, and a mirror always does;
 * - an agreement always carries its stable counterparty.
 */
fun createLendingSourceRecord(
    id: String,
    sourceRecordId: String,
    kind: LendingSourceKind,
    observedAt: Instant,
    bookingAt: Instant? = null,
    valueAt: Instant? = null,
    accountId: AccountId? = null,
    counterpartyId: String? = null,
    amountMinor: Long? = null,
    currency: CurrencyUnit? = null,
    originalSourcePayloadHash: String? = null,
    immutablePayloadHash: String,
    mirrorOfSourceId: String? = null,
): DomainResult<LendingSourceRecord> {
    if (id.isBlank() || sourceRecordId.isBlank()) {
        return DomainResult.Failure(LendingViolation.InvalidSourceRecord)
    }
    if (immutablePayloadHash.isBlank()) {
        return DomainResult.Failure(LendingViolation.PayloadHashRequired)
    }
    val isBank = kind == LendingSourceKind.BANK_DEBIT || kind == LendingSourceKind.BANK_CREDIT
    if (isBank) {
        if (bookingAt == null || valueAt == null) {
            return DomainResult.Failure(LendingViolation.BankEconomicTimesRequired)
        }
        if (amountMinor == null) {
            return DomainResult.Failure(LendingViolation.BankAmountRequired)
        }
        if (currency == null) {
            return DomainResult.Failure(LendingViolation.CurrencyRequiredWithAmount)
        }
    }
    if (amountMinor != null && currency == null) {
        return DomainResult.Failure(LendingViolation.CurrencyRequiredWithAmount)
    }
    if (originalSourcePayloadHash != null && originalSourcePayloadHash != immutablePayloadHash) {
        return DomainResult.Failure(LendingViolation.PayloadHashMismatch)
    }
    if (kind == LendingSourceKind.LENDING_AGREEMENT && counterpartyId == null) {
        return DomainResult.Failure(LendingViolation.AgreementCounterpartyRequired)
    }
    if (mirrorOfSourceId != null) {
        if (kind != LendingSourceKind.BANK_CREDIT_MIRROR || mirrorOfSourceId == id) {
            return DomainResult.Failure(LendingViolation.InvalidMirrorReference)
        }
    } else if (kind == LendingSourceKind.BANK_CREDIT_MIRROR) {
        return DomainResult.Failure(LendingViolation.InvalidMirrorReference)
    }
    return DomainResult.Success(
        LendingSourceRecord(
            id = id,
            sourceRecordId = sourceRecordId,
            kind = kind,
            observedAt = observedAt,
            bookingAt = bookingAt,
            valueAt = valueAt,
            accountId = accountId,
            counterpartyId = counterpartyId,
            amountMinor = amountMinor,
            currency = currency,
            originalSourcePayloadHash = originalSourcePayloadHash,
            immutablePayloadHash = immutablePayloadHash,
            mirrorOfSourceId = mirrorOfSourceId,
        ),
    )
}

enum class LendingConfirmationRole(val code: String) {
    LENDING_EVENT_CONFIRMATION("lending_event_confirmation"),
    LENDING_SETTLEMENT_CONFIRMATION("lending_settlement_confirmation"),
    ;

    companion object {
        fun fromCode(code: String): LendingConfirmationRole? =
            entries.firstOrNull { it.code == code }
    }
}

/**
 * D-084 RG08-GAP-03 confirmation provenance. An event confirmation owns the lend transaction;
 * a settlement confirmation owns the collect transaction and its settlement (a candidate id is
 * present only on the imported path). References are typed by role and never rewritten.
 */
data class LendingConfirmationProvenance(
    val id: String,
    val confirmationRequestId: String,
    val role: LendingConfirmationRole,
    val transactionId: TransactionId,
    val counterpartyId: String,
    val confirmedAt: Instant,
    val candidateId: String? = null,
    val settlementId: String? = null,
)

/**
 * Constructs confirmation provenance with typed role invariants: the event role requires a LEND
 * transaction and forbids settlement/candidate references; the settlement role requires a
 * COLLECT transaction and its settlement.
 */
fun createLendingConfirmationProvenance(
    id: String,
    confirmationRequestId: String,
    role: LendingConfirmationRole,
    transactionKind: TransactionKind,
    transactionId: TransactionId,
    counterpartyId: String,
    confirmedAt: Instant,
    candidateId: String? = null,
    settlementId: String? = null,
): DomainResult<LendingConfirmationProvenance> {
    if (id.isBlank() || confirmationRequestId.isBlank() || counterpartyId.isBlank()) {
        return DomainResult.Failure(LendingViolation.InvalidConfirmationProvenance)
    }
    when (role) {
        LendingConfirmationRole.LENDING_EVENT_CONFIRMATION -> {
            if (transactionKind != TransactionKind.LEND || candidateId != null || settlementId != null) {
                return DomainResult.Failure(LendingViolation.ConfirmationRoleMismatch)
            }
        }
        LendingConfirmationRole.LENDING_SETTLEMENT_CONFIRMATION -> {
            if (transactionKind != TransactionKind.COLLECT || settlementId == null) {
                return DomainResult.Failure(LendingViolation.ConfirmationRoleMismatch)
            }
        }
    }
    return DomainResult.Success(
        LendingConfirmationProvenance(
            id = id,
            confirmationRequestId = confirmationRequestId,
            role = role,
            transactionId = transactionId,
            counterpartyId = counterpartyId,
            confirmedAt = confirmedAt,
            candidateId = candidateId,
            settlementId = settlementId,
        ),
    )
}
