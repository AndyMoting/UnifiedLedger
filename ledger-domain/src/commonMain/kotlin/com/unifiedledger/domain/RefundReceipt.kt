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

// P4-06 slice 1 (RL-05 credit, D-107 section 3.4): the liability-destination variant of
// the D-078 refund receipt. createRefundReceipt stays frozen at an ASSET destination, so
// a credit-expense refund (debt paydown) gets its own additive command. The command
// carries the original expense transaction as an immutable input; the domain never
// reads a store.
data class CreditRefundOriginalExpense(
    val transactionId: TransactionId,
    val ledgerId: LedgerId,
    val kind: TransactionKind,
    val currencyCode: String,
    val currentExpensePostingAccountId: AccountId,
)

data class CreditRefundReceiptCommand(
    val ledgerId: LedgerId,
    val originalTransactionId: TransactionId,
    val originalExpense: CreditRefundOriginalExpense,
    val amount: Money,
    val categoryId: CategoryId,
    val creditLiabilityAccountId: AccountId,
    val times: TransactionTimes,
)

data class CreditRefundReceiptIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val creditLiabilityPostingId: PostingId,
    val expensePostingId: PostingId,
)

data class CreditRefundReceipt(
    val formalTransaction: FormalTransaction,
    val originalTransactionId: TransactionId,
    val categoryId: CategoryId,
    val creditLiabilityPostingId: PostingId,
    val expensePostingId: PostingId,
)

/**
 * Original-transaction validation (i): the referenced transaction must be an
 * EXPENSE-family transaction of the same ledger and the same currency as the refund
 * (a mixed-payment confirmation product counts; cross-ledger references are rejected
 * by the persistence composite FK before the domain runs). Category-inheritance
 * validation (ii): the command category must be the secondary expense category whose
 * posting account is the original transaction's current-version expense posting
 * account (ACCOUNTING_RULES.md:146 + D-078); a wrong original category must be
 * corrected on the original transaction first, never bypassed here.
 */
fun createCreditRefundReceipt(
    catalog: LedgerCatalog,
    command: CreditRefundReceiptCommand,
    ids: CreditRefundReceiptIds,
): DomainResult<CreditRefundReceipt> {
    if (command.amount.minorUnits <= 0L) {
        return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    }
    val original = command.originalExpense
    if (
        original.transactionId != command.originalTransactionId ||
        original.ledgerId != command.ledgerId ||
        original.kind != TransactionKind.EXPENSE ||
        original.currencyCode != command.amount.currency.code
    ) {
        return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    }
    val category = catalog.category(command.categoryId)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    val parent = category.parentId?.let(catalog::category)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    val expenseAccount = category.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    val liability = catalog.account(command.creditLiabilityAccountId)
        ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    if (
        category.ledgerId != command.ledgerId || category.kind != CategoryKind.EXPENSE ||
        !category.active || parent.ledgerId != command.ledgerId || parent.parentId != null ||
        parent.kind != CategoryKind.EXPENSE || expenseAccount.ledgerId != command.ledgerId ||
        expenseAccount.kind != AccountKind.EXPENSE || expenseAccount.realAccount ||
        expenseAccount.currency != command.amount.currency || liability.ledgerId != command.ledgerId ||
        liability.kind != AccountKind.LIABILITY || !liability.ownedByUser || !liability.realAccount ||
        liability.currency != command.amount.currency
    ) {
        return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    }
    // Category-inheritance rule (ii): the refund category must be the secondary
    // expense category currently backing the original transaction's expense leg.
    val originalCategory = catalog.categories.firstOrNull {
        it.kind == CategoryKind.EXPENSE && it.parentId != null && it.postingAccountId == original.currentExpensePostingAccountId
    } ?: return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    if (originalCategory.id != command.categoryId) {
        return DomainResult.Failure(DomainViolation.InvalidRefundReceipt)
    }
    val expenseMinor = checkedNegate(command.amount.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val postingSet = when (val result = PostingSet.create(
        ids.postingSetId,
        listOf(
            Posting(ids.creditLiabilityPostingId, liability.id, command.amount),
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
        CreditRefundReceipt(formal, command.originalTransactionId, command.categoryId, ids.creditLiabilityPostingId, ids.expensePostingId),
    )
}
