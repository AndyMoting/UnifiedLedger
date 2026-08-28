package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * D-087 RG-02 `category_rename` minimal closed loop: the domain append
 * semantics only — an accepted rename supersedes the current name version and
 * appends the next version with the requested name.
 */
class CategoryRenameTest {
    private val ledgerId = LedgerId("ledger-a")
    private val categoryId = CategoryId("income-category-salary")
    private val fixture = RenameFixture(ledgerId, categoryId)
    private val catalog = fixture.catalog

    @Test
    fun `accepted rename supersedes the current version and appends the next`() {
        val current = CategoryNameVersion(categoryId, 1, "工资", CategoryNameVersionStatus.CURRENT)
        val change = success(renameCategoryName(catalog, ledgerId, categoryId, "薪资", current))

        assertEquals(
            CategoryNameVersion(categoryId, 1, "工资", CategoryNameVersionStatus.SUPERSEDED),
            change.superseded,
        )
        assertEquals(
            CategoryNameVersion(categoryId, 2, "薪资", CategoryNameVersionStatus.CURRENT),
            change.current,
        )
    }

    @Test
    fun `unknown category is rejected with CategoryNotFound`() {
        assertEquals(
            CategoryRenameViolation.CategoryNotFound,
            failure(renameCategoryName(catalog, ledgerId, CategoryId("income-category-missing"), "薪资", currentVersion())),
        )
    }

    @Test
    fun `category of another ledger is rejected with CategoryNotFound`() {
        assertEquals(
            CategoryRenameViolation.CategoryNotFound,
            failure(renameCategoryName(catalog, LedgerId("ledger-b"), categoryId, "薪资", currentVersion())),
        )
    }

    @Test
    fun `blank new name is rejected with EmptyName`() {
        assertEquals(
            CategoryRenameViolation.EmptyName,
            failure(renameCategoryName(catalog, ledgerId, categoryId, "   ", currentVersion())),
        )
    }

    @Test
    fun `missing current name version is rejected with CurrentNameVersionMissing`() {
        assertEquals(
            CategoryRenameViolation.CurrentNameVersionMissing,
            failure(renameCategoryName(catalog, ledgerId, categoryId, "薪资", null)),
        )
    }

    @Test
    fun `superseded or foreign current version is rejected with CurrentNameVersionMissing`() {
        assertEquals(
            CategoryRenameViolation.CurrentNameVersionMissing,
            failure(
                renameCategoryName(
                    catalog,
                    ledgerId,
                    categoryId,
                    "薪资",
                    CategoryNameVersion(categoryId, 1, "工资", CategoryNameVersionStatus.SUPERSEDED),
                ),
            ),
        )
        assertEquals(
            CategoryRenameViolation.CurrentNameVersionMissing,
            failure(
                renameCategoryName(
                    catalog,
                    ledgerId,
                    categoryId,
                    "薪资",
                    CategoryNameVersion(CategoryId("income-category-other"), 1, "其他", CategoryNameVersionStatus.CURRENT),
                ),
            ),
        )
    }

    private fun currentVersion() = CategoryNameVersion(categoryId, 1, "工资", CategoryNameVersionStatus.CURRENT)
}

private class RenameFixture(
    ledgerId: LedgerId,
    categoryId: CategoryId,
) {
    private val cny = CurrencyUnit("CNY", 2)
    private val salaryAccountId = AccountId("income-account-salary")
    private val parentCategoryId = CategoryId("income-category-work")

    val catalog: LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(salaryAccountId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                        Account(AccountId("asset-bank-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                    ),
                categories =
                    listOf(
                        Category(parentCategoryId, ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                        Category(categoryId, ledgerId, parentId = parentCategoryId, postingAccountId = salaryAccountId, active = true, kind = CategoryKind.INCOME),
                    ),
            ),
        ).value
}
