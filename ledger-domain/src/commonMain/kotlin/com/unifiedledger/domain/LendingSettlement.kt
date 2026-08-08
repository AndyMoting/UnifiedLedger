package com.unifiedledger.domain

import kotlin.time.Instant

enum class LendingComponentKind {
    PRINCIPAL,
    INTEREST,
    FEE,
}

/**
 * One settlement component. Principal and interest components always point at their owning
 * posting; the fee component is fixed to `0.00` with no posting in RG-08 v1.
 */
data class LendingSettlementComponent(
    val id: String,
    val kind: LendingComponentKind,
    val amountMinor: Long,
    val postingId: PostingId?,
)

enum class LendingSettlementStatus {
    CONFIRMED,
}

/** Append-only settlement lifecycle entry; `formalEffectCount` counts confirmed formal effects. */
data class LendingSettlementHistoryEntry(
    val id: String,
    val status: LendingSettlementStatus,
    val occurredAt: Instant,
    val transactionId: TransactionId,
    val formalEffectCount: Int,
)

/**
 * D-084 RG08-GAP-02 lending settlement payload. `allocated_lend_transaction_id` stays null in
 * v1; components are exactly principal/interest/fee with fee fixed to zero; `actual_receipt_at`
 * and `confirmed_at` are independent explicit economic/confirmation times (RG08-GAP-04) and
 * history is append-only.
 */
data class LendingSettlement(
    val id: String,
    val behaviorCode: LendingBehaviorCode,
    val counterpartyId: String,
    val linkedPositionId: String,
    val allocatedLendTransactionId: TransactionId?,
    val transactionId: TransactionId,
    val destinationAccountId: AccountId,
    val interestCategoryId: CategoryId,
    val totalReceivedMinor: Long,
    val currency: CurrencyUnit,
    val actualReceiptAt: Instant,
    val confirmedAt: Instant,
    val components: List<LendingSettlementComponent>,
    val history: List<LendingSettlementHistoryEntry>,
)

/**
 * Constructs a confirmed lending settlement against an existing [position]. Invariants
 * (D-084 RG08-GAP-02/03/04, D-062, frozen fixture reasons):
 * - behavior code is collect and `allocated_lend_transaction_id` is null in v1;
 * - destination account is an owned real financial asset in the settlement currency;
 * - interest category is active and maps to an income account in the settlement currency;
 * - total is positive, components are exactly [principal, interest, fee] with unique ids,
 *   non-negative principal/interest, fee exactly zero, principal/interest owning their postings
 *   and the fee owning none, and components summing exactly to the total;
 * - collected principal never exceeds the outstanding receivable principal
 *   (`principal_exceeds_outstanding_position`, atomic rejection, no auto cap);
 * - `actual_receipt_at` is an explicit field never derived from `confirmed_at` (fail-closed);
 * - settlement history is append-only with confirmed entries only.
 */
fun createLendingSettlement(
    id: String,
    catalog: LedgerCatalog,
    position: LendingPosition,
    transactionId: TransactionId,
    destinationAccountId: AccountId,
    interestCategoryId: CategoryId,
    totalReceivedMinor: Long,
    currency: CurrencyUnit,
    actualReceiptAt: Instant,
    confirmedAt: Instant,
    components: List<LendingSettlementComponent>,
    history: List<LendingSettlementHistoryEntry>,
    behaviorCode: LendingBehaviorCode = LendingBehaviorCode.COLLECT,
    allocatedLendTransactionId: TransactionId? = null,
): DomainResult<LendingSettlement> {
    if (behaviorCode != LendingBehaviorCode.COLLECT) {
        return DomainResult.Failure(LendingViolation.InvalidLendingBehavior)
    }
    if (allocatedLendTransactionId != null) {
        return DomainResult.Failure(LendingViolation.AllocatedLendNotSupportedInV1)
    }
    if (currency != position.currency) {
        return DomainResult.Failure(LendingViolation.SameCurrencyRequired)
    }
    val destination = catalog.account(destinationAccountId)
        ?: return DomainResult.Failure(LendingViolation.UnknownAccount)
    if (destination.kind != AccountKind.ASSET || !destination.ownedByUser) {
        return DomainResult.Failure(LendingViolation.OwnedAccountRequired)
    }
    if (!destination.realAccount) {
        return DomainResult.Failure(LendingViolation.FinancialAssetAccountRequired)
    }
    if (destination.currency != currency) {
        return DomainResult.Failure(LendingViolation.SameCurrencyRequired)
    }
    val interestCategory = catalog.category(interestCategoryId)
        ?: return DomainResult.Failure(LendingViolation.ActiveExactInterestCategoryRequired)
    val interestAccount = interestCategory.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(LendingViolation.ActiveExactInterestCategoryRequired)
    if (
        interestCategory.kind != CategoryKind.INCOME ||
        !interestCategory.active ||
        interestAccount.kind != AccountKind.INCOME ||
        interestAccount.currency != currency
    ) {
        return DomainResult.Failure(LendingViolation.ActiveExactInterestCategoryRequired)
    }
    if (totalReceivedMinor <= 0L) {
        return DomainResult.Failure(LendingViolation.TotalMustBePositive)
    }
    val componentSnapshot = components.toList()
    val expectedKinds = listOf(
        LendingComponentKind.PRINCIPAL,
        LendingComponentKind.INTEREST,
        LendingComponentKind.FEE,
    )
    if (
        componentSnapshot.size != expectedKinds.size ||
        componentSnapshot.map { it.id }.toSet().size != componentSnapshot.size ||
        componentSnapshot.map { it.kind } != expectedKinds
    ) {
        return DomainResult.Failure(LendingViolation.InvalidComponentSet)
    }
    val principal = componentSnapshot[0]
    val interest = componentSnapshot[1]
    val fee = componentSnapshot[2]
    if (principal.amountMinor < 0L || interest.amountMinor < 0L) {
        return DomainResult.Failure(LendingViolation.ComponentMustBeNonnegative)
    }
    if (fee.amountMinor < 0L) {
        return DomainResult.Failure(LendingViolation.FeeMustBeZeroInRg08V1)
    }
    if (fee.amountMinor > 0L) {
        return DomainResult.Failure(LendingViolation.NonzeroFeeAccountingOutOfScope)
    }
    if (principal.postingId == null || interest.postingId == null || fee.postingId != null) {
        return DomainResult.Failure(LendingViolation.ComponentPostingIdInvalid)
    }
    val principalPlusInterest = checkedAdd(principal.amountMinor, interest.amountMinor)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val composed = checkedAdd(principalPlusInterest, fee.amountMinor)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    if (composed != totalReceivedMinor) {
        return DomainResult.Failure(LendingViolation.ComponentsMustEqualTotal)
    }
    if (principal.amountMinor > position.principalBalanceMinor) {
        return DomainResult.Failure(LendingViolation.PrincipalExceedsOutstandingPosition)
    }
    val historySnapshot = history.toList()
    if (
        historySnapshot.isEmpty() ||
        historySnapshot.map { it.id }.toSet().size != historySnapshot.size ||
        historySnapshot.any { it.status != LendingSettlementStatus.CONFIRMED }
    ) {
        return DomainResult.Failure(LendingViolation.InvalidSettlementLifecycle)
    }
    if (historySnapshot.any { it.formalEffectCount < 0 }) {
        return DomainResult.Failure(LendingViolation.InvalidFormalEffectCount)
    }
    return DomainResult.Success(
        LendingSettlement(
            id = id,
            behaviorCode = behaviorCode,
            counterpartyId = position.counterpartyId,
            linkedPositionId = position.id,
            allocatedLendTransactionId = null,
            transactionId = transactionId,
            destinationAccountId = destinationAccountId,
            interestCategoryId = interestCategoryId,
            totalReceivedMinor = totalReceivedMinor,
            currency = currency,
            actualReceiptAt = actualReceiptAt,
            confirmedAt = confirmedAt,
            components = componentSnapshot,
            history = historySnapshot,
        ),
    )
}
