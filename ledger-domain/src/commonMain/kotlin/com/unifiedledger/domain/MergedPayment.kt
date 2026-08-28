package com.unifiedledger.domain

import kotlin.time.Instant

data class MergedPaymentItem(
    val itemId: String,
    val amount: Money,
    val categoryId: CategoryId,
    val details: String,
    val sourceObservedAt: Instant,
)

data class MergedPaymentExpenseCommand(
    val ledgerId: LedgerId,
    val total: Money,
    val fundingAccountId: AccountId,
    val times: TransactionTimes,
    val items: List<MergedPaymentItem>,
)

data class MergedPaymentExpenseIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expensePostingIds: List<PostingId>,
    val paymentAssetPostingId: PostingId,
)

enum class MergedPaymentPostingRole { EXPENSE, PAYMENT_ASSET }

data class MergedPaymentPosting(
    val posting: Posting,
    val role: MergedPaymentPostingRole,
    val itemId: String? = null,
    val categoryId: CategoryId? = null,
)

data class MergedPaymentConsumption(
    val itemId: String,
    val amount: Money,
    val categoryId: CategoryId,
    val details: String,
    val sourceObservedAt: Instant,
    val expensePostingId: PostingId,
)

data class MergedPaymentAllocation(
    val itemId: String,
    val amount: Money,
    val categoryId: CategoryId,
    val consumptionItemId: String,
    val expensePostingId: PostingId,
)

data class MergedPaymentReportEffects(
    val consumptionMinor: Long,
    val ordinaryExpenseMinor: Long,
    val cashOutflowMinor: Long,
    val ordinaryIncomeMinor: Long,
    val netWorthChangeMinor: Long,
)

data class MergedPaymentExpense(
    val formalTransaction: FormalTransaction,
    val postings: List<MergedPaymentPosting>,
    val consumptions: List<MergedPaymentConsumption>,
    val allocations: List<MergedPaymentAllocation>,
    val reportEffects: MergedPaymentReportEffects,
)

fun createMergedPaymentExpense(
    catalog: LedgerCatalog,
    command: MergedPaymentExpenseCommand,
    ids: MergedPaymentExpenseIds,
): DomainResult<MergedPaymentExpense> {
    if (command.total.minorUnits <= 0) return DomainResult.Failure(MergedPaymentViolation.AmountMustBePositive)
    if (command.items.size != 2) return DomainResult.Failure(MergedPaymentViolation.AllocationTotalMustEqualPayment)
    if (command.items.any { it.amount.minorUnits <= 0 }) return DomainResult.Failure(MergedPaymentViolation.ItemAmountMustBePositive)
    if (command.items
            .map { it.itemId }
            .toSet()
            .size != command.items.size
    ) {
        return DomainResult.Failure(MergedPaymentViolation.DuplicateItemId)
    }
    if (command.items.any { it.amount.currency != command.total.currency }) return DomainResult.Failure(MergedPaymentViolation.SingleCurrencyRequired)
    val itemTotal = command.items.fold(0L) { sum, item -> checkedAdd(sum, item.amount.minorUnits) ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow) }
    if (itemTotal != command.total.minorUnits) return DomainResult.Failure(MergedPaymentViolation.AllocationTotalMustEqualPayment)

    val funding =
        catalog.account(command.fundingAccountId)
            ?: return DomainResult.Failure(MergedPaymentViolation.UnknownRealAccount)
    if (!funding.realAccount) return DomainResult.Failure(MergedPaymentViolation.RealFinancialAccountRequired)
    if (funding.kind != AccountKind.ASSET) return DomainResult.Failure(MergedPaymentViolation.AssetAccountRequired)
    if (!funding.ownedByUser) return DomainResult.Failure(MergedPaymentViolation.OwnedAccountRequired)
    if (funding.ledgerId != command.ledgerId || funding.currency != command.total.currency) return DomainResult.Failure(MergedPaymentViolation.SingleCurrencyRequired)

    val expenseAccounts =
        command.items.map { item ->
            val category =
                catalog.category(item.categoryId)
                    ?: return DomainResult.Failure(MergedPaymentViolation.SecondaryCategoryRequired)
            val parentId = category.parentId ?: return DomainResult.Failure(MergedPaymentViolation.SecondaryCategoryRequired)
            if (category.ledgerId != command.ledgerId) return DomainResult.Failure(DomainViolation.InvalidMergedPayment)
            val parent = catalog.category(parentId) ?: return DomainResult.Failure(DomainViolation.InvalidMergedPayment)
            if (parent.ledgerId != command.ledgerId || parent.parentId != null || parent.kind != CategoryKind.EXPENSE) {
                return DomainResult.Failure(DomainViolation.InvalidMergedPayment)
            }
            if (!category.active) return DomainResult.Failure(MergedPaymentViolation.CategoryInactive)
            if (category.kind != CategoryKind.EXPENSE) return DomainResult.Failure(MergedPaymentViolation.ExpenseCategoryRequired)
            val account =
                category.postingAccountId?.let(catalog::account)
                    ?: return DomainResult.Failure(MergedPaymentViolation.ExpenseCategoryRequired)
            if (account.kind != AccountKind.EXPENSE || account.realAccount || account.ledgerId != command.ledgerId) return DomainResult.Failure(MergedPaymentViolation.ExpenseCategoryRequired)
            if (account.currency != command.total.currency) return DomainResult.Failure(MergedPaymentViolation.SingleCurrencyRequired)
            account
        }
    if (ids.expensePostingIds.size != 2) return DomainResult.Failure(DomainViolation.InvalidPostingSet)
    val negative = checkedNegate(command.total.minorUnits) ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typed =
        command.items.zip(expenseAccounts).zip(ids.expensePostingIds).map { (pair, postingId) ->
            val (item, account) = pair
            MergedPaymentPosting(Posting(postingId, account.id, item.amount), MergedPaymentPostingRole.EXPENSE, item.itemId, item.categoryId)
        } + MergedPaymentPosting(Posting(ids.paymentAssetPostingId, funding.id, Money.ofMinor(negative, command.total.currency)), MergedPaymentPostingRole.PAYMENT_ASSET)
    val postingSet =
        when (val result = PostingSet.create(ids.postingSetId, typed.map { it.posting })) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
    val formal =
        when (
            val result =
                FormalTransaction.create(
                    Transaction(ids.transactionId, command.ledgerId, TransactionKind.EXPENSE, ids.versionId),
                    listOf(TransactionVersion(ids.versionId, ids.transactionId, 1, ids.postingSetId, command.times)),
                    listOf(postingSet),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
    val consumptions = command.items.zip(ids.expensePostingIds).map { (item, postingId) -> MergedPaymentConsumption(item.itemId, item.amount, item.categoryId, item.details, item.sourceObservedAt, postingId) }
    val allocations = command.items.zip(ids.expensePostingIds).map { (item, postingId) -> MergedPaymentAllocation(item.itemId, item.amount, item.categoryId, item.itemId, postingId) }
    return DomainResult.Success(MergedPaymentExpense(formal, typed, consumptions, allocations, MergedPaymentReportEffects(command.total.minorUnits, command.total.minorUnits, command.total.minorUnits, 0, negative)))
}
