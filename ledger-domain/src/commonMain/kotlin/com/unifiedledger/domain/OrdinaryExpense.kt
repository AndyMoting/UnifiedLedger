package com.unifiedledger.domain

data class AssetPaidOrdinaryExpenseCommand(
    val ledgerId: LedgerId,
    val amount: Money,
    val categoryId: CategoryId,
    val paymentAccountId: AccountId,
    val times: TransactionTimes,
)

data class AssetPaidOrdinaryExpenseIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expensePostingId: PostingId,
    val paymentPostingId: PostingId,
)

fun createAssetPaidOrdinaryExpense(
    catalog: LedgerCatalog,
    command: AssetPaidOrdinaryExpenseCommand,
    ids: AssetPaidOrdinaryExpenseIds,
): DomainResult<FormalTransaction> {
    if (command.amount.minorUnits <= 0L) {
        return DomainResult.Failure(OrdinaryExpenseViolation.AmountMustBePositive)
    }

    val category = catalog.category(command.categoryId)
        ?: return DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)
    if (category.ledgerId != command.ledgerId) {
        return DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)
    }
    val parentCategoryId = category.parentId
        ?: return DomainResult.Failure(OrdinaryExpenseViolation.SecondaryCategoryRequired)
    val parentCategory = catalog.category(parentCategoryId)
        ?: return DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)
    if (
        parentCategory.ledgerId != command.ledgerId ||
        parentCategory.parentId != null
    ) {
        return DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)
    }
    if (!category.active) {
        return DomainResult.Failure(OrdinaryExpenseViolation.CategoryInactive)
    }

    val expenseAccount = category.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)
    val paymentAccount = catalog.account(command.paymentAccountId)
        ?: return DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)

    val validExpenseAccount = expenseAccount.ledgerId == command.ledgerId &&
        expenseAccount.kind == AccountKind.EXPENSE &&
        expenseAccount.currency == command.amount.currency &&
        !expenseAccount.realAccount
    val validPaymentAccount = paymentAccount.ledgerId == command.ledgerId &&
        paymentAccount.kind == AccountKind.ASSET &&
        paymentAccount.currency == command.amount.currency &&
        paymentAccount.ownedByUser &&
        paymentAccount.realAccount

    if (!validExpenseAccount || !validPaymentAccount) {
        return DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)
    }

    val paymentMinorUnits = checkedNegate(command.amount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val postingSet = when (
        val result = PostingSet.create(
            id = ids.postingSetId,
            postings = listOf(
                Posting(
                    id = ids.expensePostingId,
                    accountId = expenseAccount.id,
                    amount = command.amount,
                ),
                Posting(
                    id = ids.paymentPostingId,
                    accountId = paymentAccount.id,
                    amount = Money.ofMinor(paymentMinorUnits, command.amount.currency),
                ),
            ),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> return result
    }

    val transaction = Transaction(
        id = ids.transactionId,
        ledgerId = command.ledgerId,
        kind = TransactionKind.EXPENSE,
        currentVersionId = ids.versionId,
    )
    val version = TransactionVersion(
        id = ids.versionId,
        transactionId = ids.transactionId,
        versionNumber = 1,
        postingSetId = ids.postingSetId,
        times = command.times,
        note = "",
    )
    return FormalTransaction.create(
        transaction = transaction,
        versions = listOf(version),
        postingSets = listOf(postingSet),
    )
}
