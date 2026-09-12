package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P7-02.A S-1 income option projection, mirroring [QueryManualExpenseOptions].
 *
 * Options come from the injected authoritative catalog snapshot only: owned real ASSET
 * receiving accounts (the same predicate as expense payment accounts, so the existing
 * [PaymentAccountOption] shape is reused) and active leaf INCOME categories whose posting
 * account is a same-ledger INCOME account. Options are never derived from balances, posting
 * rows or hard-coded ids; labels fall back to the stable id when a catalog row carries no name.
 */
data class IncomeCategoryOption(
    val categoryId: CategoryId,
    val parentCategoryId: CategoryId,
    val label: String,
    val postingAccountId: AccountId,
)

data class ManualIncomeOptions(
    val receivingAccounts: List<PaymentAccountOption>,
    val incomeCategories: List<IncomeCategoryOption>,
)

fun interface ManualIncomeOptionsProvider {
    fun queryOptions(): ManualIncomeOptions
}

class QueryManualIncomeOptions(
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
) : ManualIncomeOptionsProvider {
    private val accountsById: Map<AccountId, Account> = catalog.accounts.associateBy { it.id }

    override fun queryOptions(): ManualIncomeOptions {
        val receivingAccounts =
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

        val incomeCategories =
            catalog.categories
                .filter { category ->
                    category.ledgerId == ledgerId &&
                        category.active &&
                        category.parentId != null &&
                        category.kind == CategoryKind.INCOME &&
                        isSameLedgerIncomePostingAccount(category.postingAccountId) &&
                        isLeafOfActiveParent(category)
                }.map { category ->
                    IncomeCategoryOption(
                        categoryId = category.id,
                        parentCategoryId = checkNotNull(category.parentId),
                        label = category.name.ifEmpty { category.id.value },
                        postingAccountId = checkNotNull(category.postingAccountId),
                    )
                }

        return ManualIncomeOptions(
            receivingAccounts = receivingAccounts,
            incomeCategories = incomeCategories,
        )
    }

    private fun isLeafOfActiveParent(category: Category): Boolean {
        val parentId = category.parentId ?: return false
        val parent = catalog.categories.firstOrNull { it.id == parentId } ?: return true
        return parent.active
    }

    private fun isSameLedgerIncomePostingAccount(postingAccountId: AccountId?): Boolean {
        if (postingAccountId == null) return false
        val account = accountsById[postingAccountId] ?: return false
        return account.ledgerId == ledgerId && account.kind == AccountKind.INCOME
    }
}
