package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P5-03 manual-expense option projection (D-119 sections 3.2/4; plan section 3.2.2).
 *
 * Options come from the injected catalog snapshot only: owned real ASSET payment accounts
 * carrying their account currency, and active leaf EXPENSE categories whose posting account
 * is also a same-ledger EXPENSE account. Options are never derived from balances, posting
 * rows or hard-coded ids.
 *
 * P7-01 (D-143): labels come from the authoritative catalog display name and fall back to the
 * stable id only when a catalog row carries no name (for example a frozen golden snapshot).
 */
data class PaymentAccountOption(
    val accountId: AccountId,
    val currency: CurrencyUnit,
    val label: String,
)

data class ExpenseCategoryOption(
    val categoryId: CategoryId,
    val parentCategoryId: CategoryId,
    val label: String,
    val postingAccountId: AccountId,
)

data class ManualExpenseOptions(
    val paymentAccounts: List<PaymentAccountOption>,
    val expenseCategories: List<ExpenseCategoryOption>,
)

fun interface ManualExpenseOptionsProvider {
    fun queryOptions(): ManualExpenseOptions
}

class QueryManualExpenseOptions(
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
) : ManualExpenseOptionsProvider {
    private val accountsById: Map<AccountId, Account> = catalog.accounts.associateBy { it.id }

    override fun queryOptions(): ManualExpenseOptions {
        val paymentAccounts =
            catalog.accounts
                .filter {
                    it.ledgerId == ledgerId &&
                        it.kind == AccountKind.ASSET &&
                        it.ownedByUser &&
                        it.realAccount &&
                        it.active
                }.map { account ->
                    PaymentAccountOption(
                        accountId = account.id,
                        currency = account.currency,
                        label = account.name.ifEmpty { account.id.value },
                    )
                }

        val expenseCategories =
            catalog.categories
                .filter { category ->
                    category.ledgerId == ledgerId &&
                        category.active &&
                        category.parentId != null &&
                        category.kind == CategoryKind.EXPENSE &&
                        isSameLedgerExpensePostingAccount(category.postingAccountId) &&
                        isLeafOfActiveParent(category)
                }.map { category ->
                    ExpenseCategoryOption(
                        categoryId = category.id,
                        parentCategoryId = checkNotNull(category.parentId),
                        label = category.name.ifEmpty { category.id.value },
                        postingAccountId = checkNotNull(category.postingAccountId),
                    )
                }

        return ManualExpenseOptions(
            paymentAccounts = paymentAccounts,
            expenseCategories = expenseCategories,
        )
    }

    private fun isLeafOfActiveParent(category: com.unifiedledger.domain.Category): Boolean {
        val parentId = category.parentId ?: return false
        val parent = catalog.categories.firstOrNull { it.id == parentId } ?: return true
        return parent.active
    }

    private fun isSameLedgerExpensePostingAccount(postingAccountId: AccountId?): Boolean {
        if (postingAccountId == null) return false
        val account = accountsById[postingAccountId] ?: return false
        return account.ledgerId == ledgerId && account.kind == AccountKind.EXPENSE
    }
}
