package com.unifiedledger.domain

enum class AccountKind {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE,
}

enum class CategoryKind {
    EXPENSE,
    INCOME,
}

/**
 * D-066 closed stored-value account configuration. Object presence owns the account's
 * stored-value capability; the three fixed fields cannot be expressed by separate booleans.
 */
data class StoredValueConfig(
    val enabled: Boolean,
    val merchantRestricted: Boolean,
    val merchantId: String? = null,
)

data class Account(
    val id: AccountId,
    val ledgerId: LedgerId,
    val kind: AccountKind,
    val currency: CurrencyUnit,
    val ownedByUser: Boolean,
    val realAccount: Boolean,
    val systemRole: String? = null,
    val storedValue: StoredValueConfig? = null,
)

const val STORED_VALUE_BONUS_RIGHT_INCOME_ROLE = "stored_value_bonus_right_income"
const val STORED_VALUE_EXPIRY_LOSS_ROLE = "stored_value_expiry_loss"
const val STORED_VALUE_PRE_ACTIVATION_ADJUSTMENT_ROLE = "stored_value_pre_activation_adjustment"

data class Category(
    val id: CategoryId,
    val ledgerId: LedgerId,
    val parentId: CategoryId?,
    val postingAccountId: AccountId?,
    val active: Boolean,
    val kind: CategoryKind = CategoryKind.EXPENSE,
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

    internal fun account(id: AccountId): Account? = accountsById[id]

    internal fun category(id: CategoryId): Category? = categoriesById[id]
}
