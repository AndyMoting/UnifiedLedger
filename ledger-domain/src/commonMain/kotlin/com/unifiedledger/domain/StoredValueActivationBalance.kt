package com.unifiedledger.domain

data class StoredValueActivationBalanceCommand(
    val ledgerId: LedgerId,
    val storedValueAccountId: AccountId,
    val existingBalance: Money,
    val times: TransactionTimes,
)

data class StoredValueActivationBalanceIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val storedValuePostingId: PostingId,
    val adjustmentEquityPostingId: PostingId,
)

enum class StoredValueActivationBalancePostingRole {
    STORED_VALUE_CREDIT,
    PRE_ACTIVATION_ADJUSTMENT_EQUITY,
}

data class StoredValueActivationBalancePosting(
    val posting: Posting,
    val role: StoredValueActivationBalancePostingRole,
)

data class StoredValueActivationBalanceReportEffects(
    val netWorthChangeMinor: Long,
)

data class StoredValueActivationBalance(
    val formalTransaction: FormalTransaction,
    val postings: List<StoredValueActivationBalancePosting>,
    val reportEffects: StoredValueActivationBalanceReportEffects,
)

/**
 * D-050/D-083 activation boundary factory. Existing balance at account enablement enters the
 * stored-value asset against the dedicated pre-activation adjustment equity account. It is
 * never a recharge, never ordinary income, and never guesses paid/bonus composition.
 */
fun createStoredValueActivationBalance(
    catalog: LedgerCatalog,
    command: StoredValueActivationBalanceCommand,
    ids: StoredValueActivationBalanceIds,
): DomainResult<StoredValueActivationBalance> {
    val stored = catalog.account(command.storedValueAccountId)
        ?: return DomainResult.Failure(
            StoredValueViolation.KnownAccountRequired(StoredValueField.STORED_VALUE_ACCOUNT),
        )
    if (stored.ledgerId != command.ledgerId) {
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
    if (command.existingBalance.minorUnits <= 0L) {
        return DomainResult.Failure(StoredValueViolation.AmountMustBePositive)
    }
    if (stored.currency != command.existingBalance.currency) {
        return DomainResult.Failure(StoredValueViolation.SameCurrencyRequired)
    }
    val equity = catalog.accounts.firstOrNull { it.systemRole == STORED_VALUE_PRE_ACTIVATION_ADJUSTMENT_ROLE }
        ?: return DomainResult.Failure(StoredValueViolation.PreActivationAdjustmentEquityRequired)
    if (
        equity.kind != AccountKind.EQUITY ||
        equity.realAccount ||
        equity.ownedByUser ||
        equity.currency != command.existingBalance.currency
    ) {
        return DomainResult.Failure(StoredValueViolation.PreActivationAdjustmentEquityRequired)
    }
    val equityAmount = checkedNegate(command.existingBalance.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typedPostings = listOf(
        StoredValueActivationBalancePosting(
            Posting(ids.storedValuePostingId, stored.id, command.existingBalance),
            StoredValueActivationBalancePostingRole.STORED_VALUE_CREDIT,
        ),
        StoredValueActivationBalancePosting(
            Posting(ids.adjustmentEquityPostingId, equity.id, Money.ofMinor(equityAmount, command.existingBalance.currency)),
            StoredValueActivationBalancePostingRole.PRE_ACTIVATION_ADJUSTMENT_EQUITY,
        ),
    )
    val postingSet = when (
        val created = PostingSet.create(ids.postingSetId, typedPostings.map(StoredValueActivationBalancePosting::posting))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    val transaction = Transaction(
        id = ids.transactionId,
        ledgerId = command.ledgerId,
        kind = TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT,
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
        StoredValueActivationBalance(
            formalTransaction = formal,
            postings = typedPostings,
            reportEffects = StoredValueActivationBalanceReportEffects(
                netWorthChangeMinor = command.existingBalance.minorUnits,
            ),
        ),
    )
}
