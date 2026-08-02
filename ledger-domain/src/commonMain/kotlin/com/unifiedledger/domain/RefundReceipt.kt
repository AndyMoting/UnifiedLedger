package com.unifiedledger.domain

data class RefundReceiptCommand(
    val ledgerId: LedgerId,
    val originalTransactionId: TransactionId,
    val amount: Money,
    val categoryId: CategoryId,
    val destinationAccountId: AccountId,
    val times: TransactionTimes,
)

data class RefundReceiptIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val destinationPostingId: PostingId,
    val expensePostingId: PostingId,
)

data class RefundReceipt(
    val formalTransaction: FormalTransaction,
    val originalTransactionId: TransactionId,
    val categoryId: CategoryId,
    val destinationPostingId: PostingId,
    val expensePostingId: PostingId,
)

fun createRefundReceipt(
    catalog: LedgerCatalog,
    command: RefundReceiptCommand,
    ids: RefundReceiptIds,
): DomainResult<RefundReceipt> {
    if (command.amount.minorUnits <= 0L) {
        return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    }
    val category = catalog.category(command.categoryId)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    val parent = category.parentId?.let(catalog::category)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    val expenseAccount = category.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    val destination = catalog.account(command.destinationAccountId)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    if (
        category.ledgerId != command.ledgerId || category.kind != CategoryKind.EXPENSE ||
        !category.active || parent.ledgerId != command.ledgerId || parent.parentId != null ||
        parent.kind != CategoryKind.EXPENSE || expenseAccount.ledgerId != command.ledgerId ||
        expenseAccount.kind != AccountKind.EXPENSE || expenseAccount.realAccount ||
        expenseAccount.currency != command.amount.currency || destination.ledgerId != command.ledgerId ||
        destination.kind != AccountKind.ASSET || !destination.ownedByUser || !destination.realAccount ||
        destination.currency != command.amount.currency
    ) {
        return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    }
    val expenseMinor = checkedNegate(command.amount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val postingSet = when (val result = PostingSet.create(
        ids.postingSetId,
        listOf(
            Posting(ids.destinationPostingId, destination.id, command.amount),
            Posting(ids.expensePostingId, expenseAccount.id, Money.ofMinor(expenseMinor, command.amount.currency)),
        ),
    )) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> return result
    }
    val formal = when (val result = FormalTransaction.create(
        Transaction(ids.transactionId, command.ledgerId, TransactionKind.REFUND_RECEIPT, ids.versionId),
        listOf(TransactionVersion(ids.versionId, ids.transactionId, 1, ids.postingSetId, command.times)),
        listOf(postingSet),
    )) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> return result
    }
    return DomainResult.Success(
        RefundReceipt(formal, command.originalTransactionId, command.categoryId, ids.destinationPostingId, ids.expensePostingId),
    )
}
