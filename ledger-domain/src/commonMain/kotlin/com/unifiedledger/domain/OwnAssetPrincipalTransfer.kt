package com.unifiedledger.domain

data class OwnAssetPrincipalTransferCommand(
    val ledgerId: LedgerId,
    val sourceAccountId: AccountId,
    val destinationAccountId: AccountId,
    val amount: Money,
    val times: TransactionTimes,
)

data class OwnAssetPrincipalTransferIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val sourcePostingId: PostingId,
    val destinationPostingId: PostingId,
)

enum class PrincipalTransferPostingRole {
    PRINCIPAL_OUT,
    PRINCIPAL_IN,
}

data class PrincipalTransferPosting(
    val posting: Posting,
    val role: PrincipalTransferPostingRole,
)

data class OwnAssetPrincipalTransferReportEffects(
    val internalTransferMinor: Long,
    val netWorthChangeMinor: Long,
)

data class OwnAssetPrincipalTransfer(
    val formalTransaction: FormalTransaction,
    val postings: List<PrincipalTransferPosting>,
    val reportEffects: OwnAssetPrincipalTransferReportEffects,
)

fun createOwnAssetPrincipalTransfer(
    catalog: LedgerCatalog,
    command: OwnAssetPrincipalTransferCommand,
    ids: OwnAssetPrincipalTransferIds,
): DomainResult<OwnAssetPrincipalTransfer> {
    val source =
        catalog.account(command.sourceAccountId)
            ?: return DomainResult.Failure(
                PrincipalTransferViolation.KnownAccountRequired(PrincipalTransferField.SOURCE_ACCOUNT),
            )
    val destination =
        catalog.account(command.destinationAccountId)
            ?: return DomainResult.Failure(
                PrincipalTransferViolation.KnownAccountRequired(PrincipalTransferField.DESTINATION_ACCOUNT),
            )
    if (source.id == destination.id) {
        return DomainResult.Failure(PrincipalTransferViolation.DistinctAccountsRequired)
    }
    if (
        source.ledgerId != command.ledgerId ||
        destination.ledgerId != command.ledgerId
    ) {
        return DomainResult.Failure(DomainViolation.InvalidCatalog)
    }
    if (!source.ownedByUser || !source.realAccount || source.kind != AccountKind.ASSET) {
        return DomainResult.Failure(
            PrincipalTransferViolation.OwnedRealAssetRequired(PrincipalTransferField.SOURCE_ACCOUNT),
        )
    }
    if (!destination.ownedByUser || !destination.realAccount || destination.kind != AccountKind.ASSET) {
        return DomainResult.Failure(
            PrincipalTransferViolation.OwnedRealAssetRequired(PrincipalTransferField.DESTINATION_ACCOUNT),
        )
    }
    if (command.amount.minorUnits <= 0L) {
        return DomainResult.Failure(PrincipalTransferViolation.AmountMustBePositive)
    }
    if (
        source.currency != command.amount.currency ||
        destination.currency != command.amount.currency
    ) {
        return DomainResult.Failure(PrincipalTransferViolation.SameCurrencyRequired)
    }
    val sourceAmount =
        checkedNegate(command.amount.minorUnits)
            ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typedPostings =
        listOf(
            PrincipalTransferPosting(
                Posting(ids.sourcePostingId, source.id, Money.ofMinor(sourceAmount, command.amount.currency)),
                PrincipalTransferPostingRole.PRINCIPAL_OUT,
            ),
            PrincipalTransferPosting(
                Posting(ids.destinationPostingId, destination.id, command.amount),
                PrincipalTransferPostingRole.PRINCIPAL_IN,
            ),
        )
    val postingSet =
        when (
            val created = PostingSet.create(ids.postingSetId, typedPostings.map(PrincipalTransferPosting::posting))
        ) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> return created
        }
    val transaction =
        Transaction(
            id = ids.transactionId,
            ledgerId = command.ledgerId,
            kind = TransactionKind.ACCOUNT_TRANSFER,
            currentVersionId = ids.versionId,
        )
    val version =
        TransactionVersion(
            id = ids.versionId,
            transactionId = ids.transactionId,
            versionNumber = 1,
            postingSetId = ids.postingSetId,
            times = command.times,
        )
    val formal =
        when (
            val created = FormalTransaction.create(transaction, listOf(version), listOf(postingSet))
        ) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> return created
        }
    return DomainResult.Success(
        OwnAssetPrincipalTransfer(
            formalTransaction = formal,
            postings = typedPostings,
            reportEffects =
                OwnAssetPrincipalTransferReportEffects(
                    internalTransferMinor = command.amount.minorUnits,
                    netWorthChangeMinor = 0L,
                ),
        ),
    )
}
