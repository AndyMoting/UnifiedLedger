package com.unifiedledger.domain

data class OwnAssetAccountTransferCommand(
    val ledgerId: LedgerId,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val sourceDebit: Money,
    val destinationCredit: Money,
    val fee: Money,
    val feeCategoryId: CategoryId,
    val times: TransactionTimes,
)

data class AccountTransferIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val sourcePostingId: PostingId,
    val destinationPostingId: PostingId,
    val feePostingId: PostingId,
)

enum class TransferPostingRole {
    PRINCIPAL_OUT,
    PRINCIPAL_IN,
    FEE,
}

data class AccountTransferPosting(
    val posting: Posting,
    val role: TransferPostingRole,
    val categoryId: CategoryId? = null,
)

data class AccountTransferReportEffects(
    val consumptionMinor: Long,
    val ordinaryExpenseMinor: Long,
    val cashOutflowMinor: Long,
    val ordinaryIncomeMinor: Long,
    val cashInflowMinor: Long,
    val principalConsumptionMinor: Long,
    val principalExternalCashFlowMinor: Long,
    val internalTransferMinor: Long,
    val netWorthChangeMinor: Long,
)

data class AccountTransfer(
    val formalTransaction: FormalTransaction,
    val postings: List<AccountTransferPosting>,
    val reportEffects: AccountTransferReportEffects,
)

fun validateIncompleteOwnAssetTransferSource(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    sourceAccountId: AccountId,
    sourceDebit: Money,
    feeCategoryId: CategoryId,
): DomainResult<Unit> {
    val source = catalog.account(sourceAccountId)
        ?: return DomainResult.Failure(
            AccountTransferViolation.KnownAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    if (!source.realAccount) {
        return DomainResult.Failure(
            AccountTransferViolation.RealFinancialAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    }
    if (!source.ownedByUser) {
        return DomainResult.Failure(
            AccountTransferViolation.OwnAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    }
    if (source.kind != AccountKind.ASSET) {
        return DomainResult.Failure(
            AccountTransferViolation.AssetAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    }
    if (sourceDebit.minorUnits <= 0L) {
        return DomainResult.Failure(
            AccountTransferViolation.AmountMustBePositive(AccountTransferField.SOURCE_DEBIT),
        )
    }
    if (source.ledgerId != ledgerId) return DomainResult.Failure(DomainViolation.InvalidCatalog)
    if (source.currency != sourceDebit.currency) {
        return DomainResult.Failure(AccountTransferViolation.SameCurrencyRequired)
    }
    return validateTransferFeeCategory(catalog, ledgerId, feeCategoryId, sourceDebit.currency)
}

fun createOwnAssetAccountTransfer(
    catalog: LedgerCatalog,
    command: OwnAssetAccountTransferCommand,
    ids: AccountTransferIds,
): DomainResult<AccountTransfer> {
    val source = catalog.account(command.sourceAccountId)
        ?: return DomainResult.Failure(
            AccountTransferViolation.KnownAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    val destination = catalog.account(command.destinationAccountId)
        ?: return DomainResult.Failure(
            AccountTransferViolation.KnownAccountRequired(AccountTransferField.DESTINATION_ACCOUNT),
        )
    if (source.id == destination.id) {
        return DomainResult.Failure(AccountTransferViolation.DistinctAccountsRequired)
    }
    if (!source.realAccount) {
        return DomainResult.Failure(
            AccountTransferViolation.RealFinancialAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    }
    if (!destination.realAccount) {
        return DomainResult.Failure(
            AccountTransferViolation.RealFinancialAccountRequired(AccountTransferField.DESTINATION_ACCOUNT),
        )
    }
    if (!source.ownedByUser) {
        return DomainResult.Failure(
            AccountTransferViolation.OwnAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    }
    if (!destination.ownedByUser) {
        return DomainResult.Failure(
            AccountTransferViolation.OwnAccountRequired(AccountTransferField.DESTINATION_ACCOUNT),
        )
    }
    if (source.kind != AccountKind.ASSET) {
        return DomainResult.Failure(
            AccountTransferViolation.AssetAccountRequired(AccountTransferField.SOURCE_ACCOUNT),
        )
    }
    if (destination.kind != AccountKind.ASSET) {
        return DomainResult.Failure(
            AccountTransferViolation.AssetAccountRequired(AccountTransferField.DESTINATION_ACCOUNT),
        )
    }
    if (command.sourceDebit.minorUnits <= 0L) {
        return DomainResult.Failure(
            AccountTransferViolation.AmountMustBePositive(AccountTransferField.SOURCE_DEBIT),
        )
    }
    if (command.destinationCredit.minorUnits <= 0L) {
        return DomainResult.Failure(
            AccountTransferViolation.AmountMustBePositive(AccountTransferField.DESTINATION_CREDIT),
        )
    }
    if (command.fee.minorUnits < 0L) {
        return DomainResult.Failure(AccountTransferViolation.FeeMustNotBeNegative)
    }
    val creditedWithFee = checkedAdd(command.destinationCredit.minorUnits, command.fee.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    if (command.sourceDebit.minorUnits != creditedWithFee) {
        return DomainResult.Failure(AccountTransferViolation.AmountsMustBalance)
    }
    val currency = command.sourceDebit.currency
    if (
        command.destinationCredit.currency != currency ||
        command.fee.currency != currency ||
        source.currency != currency ||
        destination.currency != currency
    ) {
        return DomainResult.Failure(AccountTransferViolation.SameCurrencyRequired)
    }
    if (source.ledgerId != command.ledgerId || destination.ledgerId != command.ledgerId) {
        return DomainResult.Failure(DomainViolation.InvalidCatalog)
    }

    when (val validated = validateTransferFeeCategory(catalog, command.ledgerId, command.feeCategoryId, currency)) {
        is DomainResult.Success -> Unit
        is DomainResult.Failure -> return validated
    }
    val feeCategory = checkNotNull(catalog.category(command.feeCategoryId))
    val feeAccount = checkNotNull(feeCategory.postingAccountId?.let(catalog::account))

    val sourceMinor = checkedNegate(command.sourceDebit.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typedPostings = listOf(
        AccountTransferPosting(
            Posting(ids.sourcePostingId, source.id, Money.ofMinor(sourceMinor, currency)),
            TransferPostingRole.PRINCIPAL_OUT,
        ),
        AccountTransferPosting(
            Posting(ids.destinationPostingId, destination.id, command.destinationCredit),
            TransferPostingRole.PRINCIPAL_IN,
        ),
        AccountTransferPosting(
            Posting(ids.feePostingId, feeAccount.id, command.fee),
            TransferPostingRole.FEE,
            command.feeCategoryId,
        ),
    )
    val postingSet = when (
        val created = PostingSet.create(ids.postingSetId, typedPostings.map(AccountTransferPosting::posting))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    val transaction = Transaction(
        id = ids.transactionId,
        ledgerId = command.ledgerId,
        kind = TransactionKind.ACCOUNT_TRANSFER,
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
    val formal = when (
        val created = FormalTransaction.create(transaction, listOf(version), listOf(postingSet))
    ) {
        is DomainResult.Success -> created.value
        is DomainResult.Failure -> return created
    }
    val netWorthChange = checkedNegate(command.fee.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    return DomainResult.Success(
        AccountTransfer(
            formalTransaction = formal,
            postings = typedPostings,
            reportEffects = AccountTransferReportEffects(
                consumptionMinor = command.fee.minorUnits,
                ordinaryExpenseMinor = command.fee.minorUnits,
                cashOutflowMinor = command.fee.minorUnits,
                ordinaryIncomeMinor = 0L,
                cashInflowMinor = 0L,
                principalConsumptionMinor = 0L,
                principalExternalCashFlowMinor = 0L,
                internalTransferMinor = command.destinationCredit.minorUnits,
                netWorthChangeMinor = netWorthChange,
            ),
        ),
    )
}

private fun validateTransferFeeCategory(
    catalog: LedgerCatalog,
    ledgerId: LedgerId,
    feeCategoryId: CategoryId,
    currency: CurrencyUnit,
): DomainResult<Unit> {
    val feeCategory = catalog.category(feeCategoryId)
        ?: return DomainResult.Failure(AccountTransferViolation.InvalidFeeCategory)
    val feeParent = feeCategory.parentId?.let(catalog::category)
        ?: return DomainResult.Failure(AccountTransferViolation.InvalidFeeCategory)
    val feeAccount = feeCategory.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(AccountTransferViolation.InvalidFeeCategory)
    if (
        feeCategory.ledgerId != ledgerId ||
        feeCategory.kind != CategoryKind.EXPENSE ||
        !feeCategory.active ||
        feeParent.ledgerId != ledgerId ||
        feeParent.parentId != null ||
        feeParent.kind != CategoryKind.EXPENSE ||
        feeAccount.ledgerId != ledgerId ||
        feeAccount.kind != AccountKind.EXPENSE ||
        feeAccount.realAccount ||
        feeAccount.currency != currency
    ) {
        return DomainResult.Failure(AccountTransferViolation.InvalidFeeCategory)
    }
    return DomainResult.Success(Unit)
}
