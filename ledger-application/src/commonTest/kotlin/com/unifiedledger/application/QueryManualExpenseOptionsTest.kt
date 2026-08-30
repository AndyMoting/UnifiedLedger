package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryManualExpenseOptionsTest {
    private val ledgerId = LedgerId("ledger-target")
    private val otherLedgerId = LedgerId("ledger-other")
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun onlyOwnedRealAssetAccountsOfTheTargetLedgerAppear() {
        val paymentId = AccountId("asset-payment-ok")
        val wrongLedgerId = AccountId("asset-payment-wrong-ledger")
        val notOwnedId = AccountId("asset-payment-not-owned")
        val notRealId = AccountId("asset-payment-not-real")
        val wrongKindId = AccountId("liability-payment")

        val options =
            optionsFor(
                catalog(
                    accounts =
                        listOf(
                            ownedRealAsset(paymentId, ledgerId, cny),
                            ownedRealAsset(wrongLedgerId, otherLedgerId, cny),
                            ownedRealAsset(notOwnedId, ledgerId, cny).copy(ownedByUser = false),
                            ownedRealAsset(notRealId, ledgerId, cny).copy(realAccount = false),
                            ownedRealAsset(wrongKindId, ledgerId, cny).copy(kind = AccountKind.LIABILITY),
                        ),
                    categories = emptyList(),
                ),
            )

        val accounts = options.paymentAccounts.map { it.accountId }.toSet()
        assertEquals(setOf(paymentId), accounts)
        val paymentOption = options.paymentAccounts.single()
        assertEquals(cny, paymentOption.currency)
        assertEquals(paymentId.value, paymentOption.label)
    }

    @Test
    fun onlyActiveLeafExpenseCategoriesWithAValidSameLedgerExpensePostingAccountAppear() {
        val expenseAccountId = AccountId("expense-account-ok")
        val otherLedgerExpenseAccountId = AccountId("expense-account-other-ledger")
        val liabilityAccountId = AccountId("liability-account")
        val categoryId = CategoryId("expense-category-leaf-ok")
        val parentId = CategoryId("expense-category-parent")
        val inactiveId = CategoryId("expense-category-inactive")
        val incomeId = CategoryId("income-category-leaf")
        val crossLedgerPostingId = CategoryId("expense-category-cross-ledger-posting")
        val wrongKindPostingId = CategoryId("expense-category-liability-posting")
        val noPostingId = CategoryId("expense-category-no-posting")

        val options =
            optionsFor(
                catalog(
                    accounts =
                        listOf(
                            Account(
                                id = expenseAccountId,
                                ledgerId = ledgerId,
                                kind = AccountKind.EXPENSE,
                                currency = cny,
                                ownedByUser = false,
                                realAccount = false,
                            ),
                            Account(
                                id = otherLedgerExpenseAccountId,
                                ledgerId = otherLedgerId,
                                kind = AccountKind.EXPENSE,
                                currency = cny,
                                ownedByUser = false,
                                realAccount = false,
                            ),
                            Account(
                                id = liabilityAccountId,
                                ledgerId = ledgerId,
                                kind = AccountKind.LIABILITY,
                                currency = cny,
                                ownedByUser = false,
                                realAccount = true,
                            ),
                        ),
                    categories =
                        listOf(
                            Category(id = categoryId, ledgerId = ledgerId, parentId = parentId, postingAccountId = expenseAccountId, active = true),
                            Category(id = parentId, ledgerId = ledgerId, parentId = null, postingAccountId = null, active = true),
                            Category(id = inactiveId, ledgerId = ledgerId, parentId = parentId, postingAccountId = expenseAccountId, active = false),
                            Category(id = incomeId, ledgerId = ledgerId, parentId = parentId, postingAccountId = expenseAccountId, active = true, kind = CategoryKind.INCOME),
                            Category(id = crossLedgerPostingId, ledgerId = ledgerId, parentId = parentId, postingAccountId = otherLedgerExpenseAccountId, active = true),
                            Category(id = wrongKindPostingId, ledgerId = ledgerId, parentId = parentId, postingAccountId = liabilityAccountId, active = true),
                            Category(id = noPostingId, ledgerId = ledgerId, parentId = parentId, postingAccountId = null, active = true),
                        ),
                ),
            )

        val categories = options.expenseCategories.map { it.categoryId }.toSet()
        assertEquals(setOf(categoryId), categories)
        val option = options.expenseCategories.single()
        assertEquals(parentId, option.parentCategoryId)
        assertEquals(expenseAccountId, option.postingAccountId)
        assertEquals(categoryId.value, option.label)
    }

    private fun optionsFor(catalog: LedgerCatalog): ManualExpenseOptions = QueryManualExpenseOptions(ledgerId, catalog).queryOptions()

    private fun ownedRealAsset(
        id: AccountId,
        ledger: LedgerId,
        currency: CurrencyUnit,
    ): Account =
        Account(
            id = id,
            ledgerId = ledger,
            kind = AccountKind.ASSET,
            currency = currency,
            ownedByUser = true,
            realAccount = true,
        )

    private fun catalog(
        accounts: List<Account>,
        categories: List<Category>,
    ): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts = accounts, categories = categories)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }
}
