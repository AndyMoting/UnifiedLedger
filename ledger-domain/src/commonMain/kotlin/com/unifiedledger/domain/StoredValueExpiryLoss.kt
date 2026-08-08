package com.unifiedledger.domain

data class StoredValueExpiryLossCommand(
    val ledgerId: LedgerId,
    val storedValueAccountId: AccountId,
    val confirmedExpiredAmount: Money,
    val times: TransactionTimes,
)

data class StoredValueExpiryLossIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expiryLossPostingId: PostingId,
    val storedValuePostingId: PostingId,
)

enum class StoredValueExpiryLossPostingRole {
    EXPIRY_LOSS,
    STORED_VALUE_DEBIT,
}

data class StoredValueExpiryLossPosting(
    val posting: Posting,
    val role: StoredValueExpiryLossPostingRole,
)

data class StoredValueExpiryLossReportEffects(
    val expiryLossMinor: Long,
    val netWorthChangeMinor: Long,
)

data class StoredValueExpiryLoss(
    val formalTransaction: FormalTransaction,
    val postings: List<StoredValueExpiryLossPosting>,
    val reportEffects: StoredValueExpiryLossReportEffects,
)

/**
 * D-064/D-083 stored-value expiry loss factory. Only a confirmed actual expiry creates the
 * loss transaction: the dedicated expiry-loss expense increases and the stored-value asset
 * decreases by the confirmed expired amount; cash flow and budget effect remain zero.
 */
fun createStoredValueExpiryLoss(
    catalog: LedgerCatalog,
    command: StoredValueExpiryLossCommand,
    ids: StoredValueExpiryLossIds,
): DomainResult<StoredValueExpiryLoss> {
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
    if (command.confirmedExpiredAmount.minorUnits <= 0L) {
        return DomainResult.Failure(StoredValueViolation.AmountMustBePositive)
    }
    if (stored.currency != command.confirmedExpiredAmount.currency) {
        return DomainResult.Failure(StoredValueViolation.SameCurrencyRequired)
    }
    val lossAccount = catalog.accounts.firstOrNull { it.systemRole == STORED_VALUE_EXPIRY_LOSS_ROLE }
        ?: return DomainResult.Failure(StoredValueViolation.ExpiryLossAccountRequired)
    if (
        lossAccount.kind != AccountKind.EXPENSE ||
        lossAccount.realAccount ||
        lossAccount.ownedByUser ||
        lossAccount.currency != command.confirmedExpiredAmount.currency
    ) {
        return DomainResult.Failure(StoredValueViolation.ExpiryLossAccountRequired)
    }
    val storedAmount = checkedNegate(command.confirmedExpiredAmount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typedPostings = listOf(
        StoredValueExpiryLossPosting(
            Posting(ids.expiryLossPostingId, lossAccount.id, command.confirmedExpiredAmount),
            StoredValueExpiryLossPostingRole.EXPIRY_LOSS,
        ),
        StoredValueExpiryLossPosting(
            Posting(ids.storedValuePostingId, stored.id, Money.ofMinor(storedAmount, command.confirmedExpiredAmount.currency)),
            StoredValueExpiryLossPostingRole.STORED_VALUE_DEBIT,
        ),
    )
    val postingSet = when (
        val created = PostingSet.create(ids.postingSetId, typedPostings.map(StoredValueExpiryLossPosting::posting))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    val transaction = Transaction(
        id = ids.transactionId,
        ledgerId = command.ledgerId,
        kind = TransactionKind.STORED_VALUE_EXPIRY_LOSS,
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
        StoredValueExpiryLoss(
            formalTransaction = formal,
            postings = typedPostings,
            reportEffects = StoredValueExpiryLossReportEffects(
                expiryLossMinor = command.confirmedExpiredAmount.minorUnits,
                netWorthChangeMinor = checkedNegate(command.confirmedExpiredAmount.minorUnits)
                    ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow),
            ),
        ),
    )
}
