package com.unifiedledger.domain

data class StoredValueSpendCommand(
    val ledgerId: LedgerId,
    val storedValueAccountId: AccountId,
    val categoryId: CategoryId,
    val amount: Money,
    val times: TransactionTimes,
)

data class StoredValueSpendIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expensePostingId: PostingId,
    val storedValuePostingId: PostingId,
)

enum class StoredValueSpendPostingRole {
    EXPENSE_OUT,
    STORED_VALUE_DEBIT,
}

data class StoredValueSpendPosting(
    val posting: Posting,
    val role: StoredValueSpendPostingRole,
)

data class StoredValueSpendReportEffects(
    val ordinaryExpenseMinor: Long,
    val consumptionMinor: Long,
    val categoryEffectMinor: Long,
    val netWorthChangeMinor: Long,
)

data class StoredValueSpend(
    val formalTransaction: FormalTransaction,
    val postings: List<StoredValueSpendPosting>,
    val reportEffects: StoredValueSpendReportEffects,
)

/**
 * D-064/D-083 stored-value spend factory. Consumption increases the selected secondary
 * expense category by the full amount and decreases the stored-value asset by the same
 * amount; no second cash flow is created.
 */
fun createStoredValueSpend(
    catalog: LedgerCatalog,
    command: StoredValueSpendCommand,
    ids: StoredValueSpendIds,
): DomainResult<StoredValueSpend> {
    val stored = catalog.account(command.storedValueAccountId)
        ?: return DomainResult.Failure(
            StoredValueViolation.KnownAccountRequired(StoredValueField.STORED_VALUE_ACCOUNT),
        )
    val category = catalog.category(command.categoryId)
        ?: return DomainResult.Failure(
            StoredValueViolation.ActiveSecondaryCategoryRequired(StoredValueField.CATEGORY),
        )
    if (stored.ledgerId != command.ledgerId || category.ledgerId != command.ledgerId) {
        return DomainResult.Failure(DomainViolation.InvalidCatalog)
    }
    val storedConfig = stored.storedValue
    if (
        stored.kind != AccountKind.ASSET ||
        !stored.ownedByUser ||
        !stored.realAccount ||
        storedConfig == null ||
        !storedConfig.enabled ||
        !storedConfig.merchantRestricted
    ) {
        return DomainResult.Failure(
            StoredValueViolation.EnabledRestrictedStoredValueAssetRequired(StoredValueField.STORED_VALUE_ACCOUNT),
        )
    }
    if (!category.active || category.parentId == null || category.postingAccountId == null) {
        return DomainResult.Failure(
            StoredValueViolation.ActiveSecondaryCategoryRequired(StoredValueField.CATEGORY),
        )
    }
    val expenseAccount = catalog.account(category.postingAccountId)
        ?: return DomainResult.Failure(
            StoredValueViolation.ActiveSecondaryCategoryRequired(StoredValueField.CATEGORY),
        )
    if (expenseAccount.kind != AccountKind.EXPENSE) {
        return DomainResult.Failure(
            StoredValueViolation.ActiveSecondaryCategoryRequired(StoredValueField.CATEGORY),
        )
    }
    if (command.amount.minorUnits <= 0L) {
        return DomainResult.Failure(StoredValueViolation.AmountMustBePositive)
    }
    if (stored.currency != command.amount.currency || expenseAccount.currency != command.amount.currency) {
        return DomainResult.Failure(StoredValueViolation.SameCurrencyRequired)
    }
    val storedAmount = checkedNegate(command.amount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typedPostings = listOf(
        StoredValueSpendPosting(
            Posting(ids.expensePostingId, expenseAccount.id, command.amount),
            StoredValueSpendPostingRole.EXPENSE_OUT,
        ),
        StoredValueSpendPosting(
            Posting(ids.storedValuePostingId, stored.id, Money.ofMinor(storedAmount, command.amount.currency)),
            StoredValueSpendPostingRole.STORED_VALUE_DEBIT,
        ),
    )
    val postingSet = when (
        val created = PostingSet.create(ids.postingSetId, typedPostings.map(StoredValueSpendPosting::posting))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    val transaction = Transaction(
        id = ids.transactionId,
        ledgerId = command.ledgerId,
        kind = TransactionKind.STORED_VALUE_SPEND,
        currentVersionId = ids.versionId,
    )
    val version = TransactionVersion(
        id = ids.versionId,
        transactionId = ids.transactionId,
        versionNumber = 1,
        postingSetId = ids.postingSetId,
        times = command.times,
    )
    val formal = when (
        val created = FormalTransaction.create(transaction, listOf(version), listOf(postingSet))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    return DomainResult.Success(
        StoredValueSpend(
            formalTransaction = formal,
            postings = typedPostings,
            reportEffects = StoredValueSpendReportEffects(
                ordinaryExpenseMinor = command.amount.minorUnits,
                consumptionMinor = command.amount.minorUnits,
                categoryEffectMinor = command.amount.minorUnits,
                netWorthChangeMinor = checkedNegate(command.amount.minorUnits)
                    ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow),
            ),
        ),
    )
}
