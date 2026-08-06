package com.unifiedledger.domain

data class BalanceAdjustmentCommand(
    val ledgerId: LedgerId,
    val targetAccountId: AccountId,
    val adjustmentEquityAccountId: AccountId,
    val delta: Money,
    val times: TransactionTimes,
    val kind: TransactionKind = TransactionKind.BALANCE_ADJUSTMENT,
)

data class BalanceAdjustmentIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val targetPostingId: PostingId,
    val equityPostingId: PostingId,
)

enum class BalanceAdjustmentPostingRole {
    TARGET,
    ADJUSTMENT_EQUITY,
}

data class BalanceAdjustmentPosting(
    val posting: Posting,
    val role: BalanceAdjustmentPostingRole,
)

data class BalanceAdjustmentReportEffects(
    val balanceAdjustmentNetWorthChangeMinor: Long,
    val netWorthChangeMinor: Long,
)

data class BalanceAdjustment(
    val formalTransaction: FormalTransaction,
    val postings: List<BalanceAdjustmentPosting>,
    val reportEffects: BalanceAdjustmentReportEffects,
)

fun createBalanceAdjustment(
    catalog: LedgerCatalog,
    command: BalanceAdjustmentCommand,
    ids: BalanceAdjustmentIds,
): DomainResult<BalanceAdjustment> {
    val target = catalog.account(command.targetAccountId)
        ?: return DomainResult.Failure(
            BalanceAdjustmentViolation.KnownAccountRequired(BalanceAdjustmentField.TARGET_ACCOUNT),
        )
    val equity = catalog.account(command.adjustmentEquityAccountId)
        ?: return DomainResult.Failure(
            BalanceAdjustmentViolation.KnownAccountRequired(BalanceAdjustmentField.ADJUSTMENT_EQUITY_ACCOUNT),
        )
    if (
        command.kind != TransactionKind.BALANCE_ADJUSTMENT &&
        command.kind != TransactionKind.BALANCE_ADJUSTMENT_REVERSAL
    ) {
        return DomainResult.Failure(BalanceAdjustmentViolation.SupportedKindRequired)
    }
    if (
        target.ledgerId != command.ledgerId ||
        equity.ledgerId != command.ledgerId
    ) {
        return DomainResult.Failure(DomainViolation.InvalidCatalog)
    }
    if (!target.ownedByUser || !target.realAccount || target.kind != AccountKind.ASSET) {
        return DomainResult.Failure(
            BalanceAdjustmentViolation.OwnedRealAssetRequired(BalanceAdjustmentField.TARGET_ACCOUNT),
        )
    }
    if (
        equity.kind != AccountKind.EQUITY ||
        equity.ownedByUser ||
        equity.realAccount ||
        equity.systemRole != BALANCE_ADJUSTMENT_EQUITY_ROLE
    ) {
        return DomainResult.Failure(BalanceAdjustmentViolation.DedicatedAdjustmentEquityRequired)
    }
    if (command.delta.minorUnits == 0L) {
        return DomainResult.Failure(BalanceAdjustmentViolation.NonZeroAmountRequired)
    }
    if (
        target.currency != command.delta.currency ||
        equity.currency != command.delta.currency
    ) {
        return DomainResult.Failure(BalanceAdjustmentViolation.SameCurrencyRequired)
    }

    val equityAmount = checkedNegate(command.delta.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typedPostings = listOf(
        BalanceAdjustmentPosting(
            Posting(ids.targetPostingId, target.id, command.delta),
            BalanceAdjustmentPostingRole.TARGET,
        ),
        BalanceAdjustmentPosting(
            Posting(ids.equityPostingId, equity.id, Money.ofMinor(equityAmount, command.delta.currency)),
            BalanceAdjustmentPostingRole.ADJUSTMENT_EQUITY,
        ),
    )
    val postingSet = when (
        val created = PostingSet.create(ids.postingSetId, typedPostings.map(BalanceAdjustmentPosting::posting))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    val transaction = Transaction(
        id = ids.transactionId,
        ledgerId = command.ledgerId,
        kind = command.kind,
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
        BalanceAdjustment(
            formalTransaction = formal,
            postings = typedPostings,
            reportEffects = BalanceAdjustmentReportEffects(
                balanceAdjustmentNetWorthChangeMinor = command.delta.minorUnits,
                netWorthChangeMinor = command.delta.minorUnits,
            ),
        ),
    )
}

const val BALANCE_ADJUSTMENT_EQUITY_ROLE = "balance_adjustments"
