package com.unifiedledger.domain

class BalanceSnapshot internal constructor(
    balances: Map<AccountId, Money>,
) {
    val balances: Map<AccountId, Money> = balances.toMap()
}

fun replayBalances(
    catalog: LedgerCatalog,
    transactions: List<FormalTransaction>,
): DomainResult<BalanceSnapshot> {
    if (transactions.map { it.transaction.id }.toSet().size != transactions.size) {
        return DomainResult.Failure(DomainViolation.InvalidBalanceReplay)
    }

    val totals = mutableMapOf<AccountId, ExactLongAccumulator>()

    for (formal in transactions) {
        for (posting in formal.currentPostingSet().postings) {
            val account =
                catalog.account(posting.accountId)
                    ?: return DomainResult.Failure(DomainViolation.InvalidBalanceReplay)
            if (
                account.ledgerId != formal.transaction.ledgerId ||
                account.currency != posting.amount.currency
            ) {
                return DomainResult.Failure(DomainViolation.InvalidBalanceReplay)
            }

            totals
                .getOrPut(account.id, ::ExactLongAccumulator)
                .add(posting.amount.minorUnits)
        }
    }

    val balances = mutableMapOf<AccountId, Money>()
    for ((accountId, accumulator) in totals) {
        val minorUnits =
            accumulator.exactLongOrNull()
                ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
        val account =
            catalog.account(accountId)
                ?: return DomainResult.Failure(DomainViolation.InvalidBalanceReplay)
        balances[accountId] = Money.ofMinor(minorUnits, account.currency)
    }
    return DomainResult.Success(BalanceSnapshot(balances))
}
