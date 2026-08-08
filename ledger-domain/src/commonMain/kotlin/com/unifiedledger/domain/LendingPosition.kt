package com.unifiedledger.domain

import kotlin.time.Instant

/**
 * D-084 RG08-GAP-02 position allocation scope. v1 only supports the person-level net position;
 * contract-level allocation stays out of scope.
 */
enum class LendingAllocationScope {
    PERSON_LEVEL_NET_POSITION,
}

/**
 * One append-only history entry of a [LendingPosition]. The entry carries the exact principal
 * balance after the event so replay never has to guess composition or cross zero.
 */
data class LendingPositionHistoryEntry(
    val id: String,
    val behaviorCode: LendingBehaviorCode,
    val amountMinor: Long,
    val principalBalanceAfterMinor: Long,
    val transactionId: TransactionId,
    val occurredAt: Instant,
)

/**
 * D-084 RG08-GAP-02 person-level net lending receivable position. It owns the stable
 * counterparty identity, the designated receivable account, one currency and an append-only
 * principal history; `contract_allocation_enabled` is fixed to false in v1.
 */
data class LendingPosition(
    val id: String,
    val counterpartyId: String,
    val receivableAccountId: AccountId,
    val currency: CurrencyUnit,
    val principalBalanceMinor: Long,
    val allocationScope: LendingAllocationScope,
    val contractAllocationEnabled: Boolean,
    val history: List<LendingPositionHistoryEntry>,
)

/**
 * Constructs a receivable position. Invariants (D-084 RG08-GAP-02, D-062):
 * - allocation scope is fixed to person-level net position;
 * - contract allocation is not enabled in v1;
 * - principal balance is never negative;
 * - history is append-only: unique ids, exact running arithmetic, every intermediate and the
 *   final balance equal the declared values, and only lend/collect behaviors with their
 *   receivable direction appear.
 */
fun createLendingPosition(
    id: String,
    counterpartyId: String,
    receivableAccountId: AccountId,
    currency: CurrencyUnit,
    principalBalanceMinor: Long,
    history: List<LendingPositionHistoryEntry>,
    allocationScope: LendingAllocationScope = LendingAllocationScope.PERSON_LEVEL_NET_POSITION,
    contractAllocationEnabled: Boolean = false,
): DomainResult<LendingPosition> {
    if (allocationScope != LendingAllocationScope.PERSON_LEVEL_NET_POSITION) {
        return DomainResult.Failure(LendingViolation.UnsupportedAllocationScope)
    }
    if (contractAllocationEnabled) {
        return DomainResult.Failure(LendingViolation.ContractAllocationNotSupported)
    }
    if (id.isBlank() || counterpartyId.isBlank()) {
        return DomainResult.Failure(LendingViolation.InvalidLendingBehavior)
    }
    if (principalBalanceMinor < 0L) {
        return DomainResult.Failure(LendingViolation.PrincipalBalanceMustNotBeNegative)
    }
    val snapshot = history.toList()
    if (snapshot.map { it.id }.toSet().size != snapshot.size) {
        return DomainResult.Failure(LendingViolation.HistoryMustBeAppendOnly)
    }
    var running = 0L
    for (entry in snapshot) {
        if (
            entry.behaviorCode != LendingBehaviorCode.LEND &&
            entry.behaviorCode != LendingBehaviorCode.COLLECT
        ) {
            return DomainResult.Failure(LendingViolation.InvalidLendingBehavior)
        }
        val expectedDirection = entry.behaviorCode == LendingBehaviorCode.LEND
        if ((expectedDirection && entry.amountMinor < 0L) || (!expectedDirection && entry.amountMinor > 0L)) {
            return DomainResult.Failure(LendingViolation.InvalidPositionHistoryDirection)
        }
        val after = checkedAdd(running, entry.amountMinor)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
        if (after != entry.principalBalanceAfterMinor || after < 0L) {
            return DomainResult.Failure(LendingViolation.HistoryMustBeAppendOnly)
        }
        running = after
    }
    if (running != principalBalanceMinor) {
        return DomainResult.Failure(LendingViolation.HistoryMustBeAppendOnly)
    }
    return DomainResult.Success(
        LendingPosition(
            id = id,
            counterpartyId = counterpartyId,
            receivableAccountId = receivableAccountId,
            currency = currency,
            principalBalanceMinor = principalBalanceMinor,
            allocationScope = allocationScope,
            contractAllocationEnabled = contractAllocationEnabled,
            history = snapshot,
        ),
    )
}
