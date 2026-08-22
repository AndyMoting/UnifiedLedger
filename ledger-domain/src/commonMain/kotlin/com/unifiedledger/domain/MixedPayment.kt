package com.unifiedledger.domain

data class FundingComponent(val accountId: AccountId, val amount: Money)

data class MixedPaymentExpenseCommand(
    val ledgerId: LedgerId,
    val total: Money,
    val categoryId: CategoryId,
    val funding: List<FundingComponent>,
    val times: TransactionTimes,
)

data class MixedPaymentExpenseIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expensePostingId: PostingId,
    val fundingPostingIds: List<PostingId>,
)

data class CreditPrincipalRepaymentCommand(
    val ledgerId: LedgerId,
    val assetAccountId: AccountId,
    val liabilityAccountId: AccountId,
    val principal: Money,
    val times: TransactionTimes,
)

data class CreditPrincipalRepaymentIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val assetPostingId: PostingId,
    val liabilityPostingId: PostingId,
)

// P4-06 slice 1 (RL-05 credit, D-107): single credit-leg expense degeneration of the
// D-072 mixed-entry contract. createMixedPaymentExpense stays frozen at exactly two
// funding legs, so a single-credit-leg consumption gets its own additive command.
data class CreditExpenseCommand(
    val ledgerId: LedgerId,
    val total: Money,
    val categoryId: CategoryId,
    val creditLiabilityAccountId: AccountId,
    val times: TransactionTimes,
)

data class CreditExpenseIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val expensePostingId: PostingId,
    val liabilityPostingId: PostingId,
)

enum class MixedPaymentPostingRole {
    EXPENSE,
    MIXED_EXPENSE_ASSET_FUNDING,
    MIXED_EXPENSE_CREDIT_FUNDING,
    CREDIT_REPAYMENT_ASSET_OUTFLOW,
    CREDIT_REPAYMENT_LIABILITY_PRINCIPAL,
    CREDIT_EXPENSE_LIABILITY_FUNDING,
}

data class MixedPaymentPosting(val posting: Posting, val role: MixedPaymentPostingRole, val categoryId: CategoryId? = null)

data class MixedPaymentReportEffects(
    val consumptionMinor: Long,
    val ordinaryExpenseMinor: Long,
    val cashOutflowMinor: Long,
    val ordinaryIncomeMinor: Long,
    val netWorthChangeMinor: Long,
)

data class MixedPaymentExpense(
    val formalTransaction: FormalTransaction,
    val postings: List<MixedPaymentPosting>,
    val reportEffects: MixedPaymentReportEffects,
)

data class CreditPrincipalRepayment(
    val formalTransaction: FormalTransaction,
    val postings: List<MixedPaymentPosting>,
    val reportEffects: MixedPaymentReportEffects,
)

data class CreditExpense(
    val formalTransaction: FormalTransaction,
    val postings: List<MixedPaymentPosting>,
    val reportEffects: MixedPaymentReportEffects,
)

fun createMixedPaymentExpense(
    catalog: LedgerCatalog,
    command: MixedPaymentExpenseCommand,
    ids: MixedPaymentExpenseIds,
): DomainResult<MixedPaymentExpense> {
    val category = catalog.category(command.categoryId)
        ?: return DomainResult.Failure(MixedPaymentViolation.SecondaryCategoryRequired)
    val parentId = category.parentId ?: return DomainResult.Failure(MixedPaymentViolation.SecondaryCategoryRequired)
    if (!category.active) return DomainResult.Failure(MixedPaymentViolation.CategoryInactive)
    if (category.kind != CategoryKind.EXPENSE) return DomainResult.Failure(MixedPaymentViolation.ExpenseCategoryRequired)
    val expenseAccount = category.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(MixedPaymentViolation.ExpenseCategoryRequired)
    if (expenseAccount.kind != AccountKind.EXPENSE) return DomainResult.Failure(MixedPaymentViolation.ExpenseCategoryRequired)

    if (command.total.minorUnits <= 0) return DomainResult.Failure(MixedPaymentViolation.AmountMustBePositive)
    if (command.funding.size != 2 || command.funding.any { it.amount.minorUnits <= 0 }) {
        return DomainResult.Failure(MixedPaymentViolation.FundingLegMustBePositive)
    }
    val fundingTotal = command.funding.fold(0L) { sum, item ->
        checkedAdd(sum, item.amount.minorUnits) ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    }
    if (fundingTotal != command.total.minorUnits) return DomainResult.Failure(MixedPaymentViolation.FundingTotalMustEqualExpense)

    val seenAccounts = mutableSetOf<AccountId>()
    val accounts = command.funding.map { funding ->
        val account = catalog.account(funding.accountId)
            ?: return DomainResult.Failure(MixedPaymentViolation.UnknownRealAccount)
        if (!account.realAccount || account.kind !in setOf(AccountKind.ASSET, AccountKind.LIABILITY)) {
            return DomainResult.Failure(MixedPaymentViolation.RealFinancialAccountRequired)
        }
        if (!account.ownedByUser) return DomainResult.Failure(MixedPaymentViolation.OwnedAccountRequired)
        if (!seenAccounts.add(account.id)) return DomainResult.Failure(MixedPaymentViolation.DuplicateFundingAccount)
        account
    }

    if (command.funding.map { it.amount.currency }.toSet().size > 1 ||
        command.funding.any { it.amount.currency != command.total.currency }
    ) {
        return DomainResult.Failure(MixedPaymentViolation.SingleCurrencyRequired)
    }

    val parent = catalog.category(parentId) ?: return DomainResult.Failure(DomainViolation.InvalidMixedPayment)
    if (parent.ledgerId != command.ledgerId || parent.parentId != null || parent.kind != CategoryKind.EXPENSE) {
        return DomainResult.Failure(DomainViolation.InvalidMixedPayment)
    }
    if (expenseAccount.ledgerId != command.ledgerId || expenseAccount.realAccount || expenseAccount.currency != command.total.currency) {
        return DomainResult.Failure(DomainViolation.InvalidMixedPayment)
    }
    if (accounts.any { it.ledgerId != command.ledgerId } || accounts.any { it.currency != command.total.currency }) {
        return DomainResult.Failure(MixedPaymentViolation.SingleCurrencyRequired)
    }
    if (accounts.map { it.kind }.toSet() != setOf(AccountKind.ASSET, AccountKind.LIABILITY)) {
        return DomainResult.Failure(MixedPaymentViolation.AssetAndCreditLiabilityRequired)
    }
    if (ids.fundingPostingIds.size != 2) return DomainResult.Failure(DomainViolation.InvalidPostingSet)
    val typed = buildList {
        add(MixedPaymentPosting(Posting(ids.expensePostingId, expenseAccount.id, command.total), MixedPaymentPostingRole.EXPENSE, command.categoryId))
        command.funding.zip(accounts).zip(ids.fundingPostingIds).forEach { (pair, postingId) ->
            val (funding, account) = pair
            val minor = checkedNegate(funding.amount.minorUnits) ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
            val role = if (account.kind == AccountKind.ASSET) {
                MixedPaymentPostingRole.MIXED_EXPENSE_ASSET_FUNDING
            } else {
                MixedPaymentPostingRole.MIXED_EXPENSE_CREDIT_FUNDING
            }
            add(MixedPaymentPosting(Posting(postingId, account.id, Money.ofMinor(minor, funding.amount.currency)), role))
        }
    }
    val formal = formal(command.ledgerId, TransactionKind.EXPENSE, command.times, ids.transactionId, ids.versionId, ids.postingSetId, typed.map { it.posting })
    if (formal is DomainResult.Failure) return formal
    val cashOut = command.funding.zip(accounts).filter { it.second.kind == AccountKind.ASSET }.sumOf { it.first.amount.minorUnits }
    return DomainResult.Success(MixedPaymentExpense((formal as DomainResult.Success).value, typed, MixedPaymentReportEffects(command.total.minorUnits, command.total.minorUnits, cashOut, 0, -command.total.minorUnits)))
}

fun createCreditPrincipalRepayment(
    catalog: LedgerCatalog,
    command: CreditPrincipalRepaymentCommand,
    ids: CreditPrincipalRepaymentIds,
): DomainResult<CreditPrincipalRepayment> {
    if (command.principal.minorUnits <= 0) return DomainResult.Failure(MixedPaymentViolation.AmountMustBePositive)
    val asset = catalog.account(command.assetAccountId) ?: return DomainResult.Failure(MixedPaymentViolation.UnknownRealAccount)
    val liability = catalog.account(command.liabilityAccountId) ?: return DomainResult.Failure(MixedPaymentViolation.UnknownRealAccount)
    if (!asset.realAccount || !liability.realAccount) return DomainResult.Failure(MixedPaymentViolation.RealFinancialAccountRequired)
    if (!asset.ownedByUser || !liability.ownedByUser) return DomainResult.Failure(MixedPaymentViolation.OwnedAccountRequired)
    if (asset.kind != AccountKind.ASSET || liability.kind != AccountKind.LIABILITY) return DomainResult.Failure(MixedPaymentViolation.AssetAndCreditLiabilityRequired)
    if (asset.ledgerId != command.ledgerId || liability.ledgerId != command.ledgerId || asset.currency != command.principal.currency || liability.currency != command.principal.currency) return DomainResult.Failure(MixedPaymentViolation.SingleCurrencyRequired)
    val negative = checkedNegate(command.principal.minorUnits) ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typed = listOf(
        MixedPaymentPosting(Posting(ids.assetPostingId, asset.id, Money.ofMinor(negative, command.principal.currency)), MixedPaymentPostingRole.CREDIT_REPAYMENT_ASSET_OUTFLOW),
        MixedPaymentPosting(Posting(ids.liabilityPostingId, liability.id, command.principal), MixedPaymentPostingRole.CREDIT_REPAYMENT_LIABILITY_PRINCIPAL),
    )
    val formal = formal(command.ledgerId, TransactionKind.CREDIT_REPAYMENT, command.times, ids.transactionId, ids.versionId, ids.postingSetId, typed.map { it.posting })
    if (formal is DomainResult.Failure) return formal
    return DomainResult.Success(CreditPrincipalRepayment((formal as DomainResult.Success).value, typed, MixedPaymentReportEffects(0, 0, command.principal.minorUnits, 0, 0)))
}

/**
 * P4-06 slice 1 (D-107 section 3.4): credit-leg-only consumption. Contract semantics =
 * the single-credit-leg degeneration of the D-072 mixed entry: a secondary active
 * expense category with its expense account (+ total) funded by one user-held real
 * LIABILITY account (- total). The liability validation is identical to
 * [createCreditPrincipalRepayment] (real / owned / LIABILITY / same ledger / same
 * currency). Report effects follow the D-058 algorithm: consumption = total,
 * ordinary expense = total, cash outflow = 0, income = 0, net-worth change = -total
 * (zero cash leaves on purchase day; the consumption is recognized once in full).
 */
fun createCreditExpense(
    catalog: LedgerCatalog,
    command: CreditExpenseCommand,
    ids: CreditExpenseIds,
): DomainResult<CreditExpense> {
    val category = catalog.category(command.categoryId)
        ?: return DomainResult.Failure(MixedPaymentViolation.SecondaryCategoryRequired)
    val parentId = category.parentId ?: return DomainResult.Failure(MixedPaymentViolation.SecondaryCategoryRequired)
    if (!category.active) return DomainResult.Failure(MixedPaymentViolation.CategoryInactive)
    if (category.kind != CategoryKind.EXPENSE) return DomainResult.Failure(MixedPaymentViolation.ExpenseCategoryRequired)
    val expenseAccount = category.postingAccountId?.let(catalog::account)
        ?: return DomainResult.Failure(MixedPaymentViolation.ExpenseCategoryRequired)
    if (expenseAccount.kind != AccountKind.EXPENSE) return DomainResult.Failure(MixedPaymentViolation.ExpenseCategoryRequired)

    if (command.total.minorUnits <= 0) return DomainResult.Failure(MixedPaymentViolation.AmountMustBePositive)

    val liability = catalog.account(command.creditLiabilityAccountId)
        ?: return DomainResult.Failure(MixedPaymentViolation.UnknownRealAccount)
    if (!liability.realAccount) return DomainResult.Failure(MixedPaymentViolation.RealFinancialAccountRequired)
    if (!liability.ownedByUser) return DomainResult.Failure(MixedPaymentViolation.OwnedAccountRequired)
    if (liability.kind != AccountKind.LIABILITY) return DomainResult.Failure(MixedPaymentViolation.AssetAndCreditLiabilityRequired)
    if (liability.ledgerId != command.ledgerId || liability.currency != command.total.currency) {
        return DomainResult.Failure(MixedPaymentViolation.SingleCurrencyRequired)
    }

    val parent = catalog.category(parentId) ?: return DomainResult.Failure(DomainViolation.InvalidMixedPayment)
    if (parent.ledgerId != command.ledgerId || parent.parentId != null || parent.kind != CategoryKind.EXPENSE) {
        return DomainResult.Failure(DomainViolation.InvalidMixedPayment)
    }
    if (expenseAccount.ledgerId != command.ledgerId || expenseAccount.realAccount || expenseAccount.currency != command.total.currency) {
        return DomainResult.Failure(DomainViolation.InvalidMixedPayment)
    }

    val liabilityMinor = checkedNegate(command.total.minorUnits)
        ?: return DomainResult.Failure(DomainViolation.ArithmeticOverflow)
    val typed = listOf(
        MixedPaymentPosting(Posting(ids.expensePostingId, expenseAccount.id, command.total), MixedPaymentPostingRole.EXPENSE, command.categoryId),
        MixedPaymentPosting(
            Posting(ids.liabilityPostingId, liability.id, Money.ofMinor(liabilityMinor, command.total.currency)),
            MixedPaymentPostingRole.CREDIT_EXPENSE_LIABILITY_FUNDING,
        ),
    )
    val formal = formal(command.ledgerId, TransactionKind.EXPENSE, command.times, ids.transactionId, ids.versionId, ids.postingSetId, typed.map { it.posting })
    if (formal is DomainResult.Failure) return formal
    return DomainResult.Success(
        CreditExpense(
            (formal as DomainResult.Success).value,
            typed,
            MixedPaymentReportEffects(command.total.minorUnits, command.total.minorUnits, 0, 0, -command.total.minorUnits),
        ),
    )
}

private fun formal(ledgerId: LedgerId, kind: TransactionKind, times: TransactionTimes, transactionId: TransactionId, versionId: TransactionVersionId, postingSetId: PostingSetId, postings: List<Posting>): DomainResult<FormalTransaction> {
    val set = when (val result = PostingSet.create(postingSetId, postings)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> return result
    }
    return FormalTransaction.create(
        Transaction(transactionId, ledgerId, kind, versionId),
        listOf(TransactionVersion(versionId, transactionId, 1, postingSetId, times, "")),
        listOf(set),
    )
}
