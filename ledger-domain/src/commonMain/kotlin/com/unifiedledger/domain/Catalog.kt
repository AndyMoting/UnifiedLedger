package com.unifiedledger.domain

enum class AccountKind {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE,
}

data class Account(
    val id: AccountId,
    val ledgerId: LedgerId,
    val kind: AccountKind,
    val currency: CurrencyUnit,
    val ownedByUser: Boolean,
    val realAccount: Boolean,
)

data class Category(
    val id: CategoryId,
    val ledgerId: LedgerId,
    val parentId: CategoryId?,
    val postingAccountId: AccountId?,
    val active: Boolean,
)

class LedgerCatalog private constructor(
    val accounts: List<Account>,
    val categories: List<Category>,
    private val accountsById: Map<AccountId, Account>,
    private val categoriesById: Map<CategoryId, Category>,
) {
    companion object {
        fun create(
            accounts: List<Account>,
            categories: List<Category>,
        ): DomainResult<LedgerCatalog> {
            val accountSnapshot = accounts.toList()
            val categorySnapshot = categories.toList()
            if (
                accountSnapshot.map { it.id }.toSet().size != accountSnapshot.size ||
                categorySnapshot.map { it.id }.toSet().size != categorySnapshot.size
            ) {
                return DomainResult.Failure(DomainViolation.InvalidCatalog)
            }

            return DomainResult.Success(
                LedgerCatalog(
                    accounts = accountSnapshot,
                    categories = categorySnapshot,
                    accountsById = accountSnapshot.associateBy { it.id },
                    categoriesById = categorySnapshot.associateBy { it.id },
                ),
            )
        }
    }

    internal fun account(id: AccountId): Account? =
        accountsById[id]

    internal fun category(id: CategoryId): Category? =
        categoriesById[id]
}
