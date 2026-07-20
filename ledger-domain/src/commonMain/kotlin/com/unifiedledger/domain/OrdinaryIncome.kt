package com.unifiedledger.domain

data class AssetReceivedOrdinaryIncomeCommand(
    val ledgerId: LedgerId,
    val amount: Money,
    val categoryId: CategoryId,
    val receivingAccountId: AccountId,
    val times: TransactionTimes,
)

data class AssetReceivedOrdinaryIncomeIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val receivingPostingId: PostingId,
    val incomePostingId: PostingId,
)

fun createAssetReceivedOrdinaryIncome(
    catalog: LedgerCatalog,
    command: AssetReceivedOrdinaryIncomeCommand,
    ids: AssetReceivedOrdinaryIncomeIds,
): DomainResult<FormalTransaction> {
    if (command.amount.minorUnits <= 0L) return DomainResult.Failure(OrdinaryIncomeViolation.AmountMustBePositive)
    val category = catalog.category(command.categoryId)
        ?: return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
    if (category.ledgerId != command.ledgerId || category.kind != CategoryKind.INCOME) {
        return DomainResult.Failure(OrdinaryIncomeViolation.IncomeCategoryRequired)
    }
    val parent = category.parentId?.let(catalog::category)
        ?: return DomainResult.Failure(OrdinaryIncomeViolation.SecondaryCategoryRequired)
    if (parent.ledgerId != command.ledgerId || parent.parentId != null || parent.kind != CategoryKind.INCOME) {
        return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
    }
    if (!category.active) return DomainResult.Failure(OrdinaryIncomeViolation.CategoryInactive)
    val incomeAccount = category.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
    val receivingAccount = catalog.account(command.receivingAccountId)
        ?: return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
    if (incomeAccount.ledgerId != command.ledgerId || incomeAccount.kind != AccountKind.INCOME ||
        incomeAccount.realAccount || incomeAccount.currency != command.amount.currency ||
        receivingAccount.ledgerId != command.ledgerId || receivingAccount.kind != AccountKind.ASSET ||
        !receivingAccount.realAccount || !receivingAccount.ownedByUser || receivingAccount.currency != command.amount.currency) {
        return DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
    }
    val negative = checkedNegate(command.amount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val postings = PostingSet.create(ids.postingSetId, listOf(
        Posting(ids.receivingPostingId, receivingAccount.id, command.amount),
        Posting(ids.incomePostingId, incomeAccount.id, Money.ofMinor(negative, command.amount.currency)),
    ))
    val postingSet = when (postings) { is DomainResult.Success -> postings.value; is DomainResult.Failure -> return postings }
    val transaction = Transaction(ids.transactionId, command.ledgerId, TransactionKind.INCOME, ids.versionId)
    val version = TransactionVersion(ids.versionId, ids.transactionId, 1, ids.postingSetId, command.times, "")
    return FormalTransaction.create(transaction, listOf(version), listOf(postingSet))
}
