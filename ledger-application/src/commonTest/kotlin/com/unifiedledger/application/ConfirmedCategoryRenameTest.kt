package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CategoryNameVersion
import com.unifiedledger.domain.CategoryNameVersionStatus
import com.unifiedledger.domain.CategoryRenameChange
import com.unifiedledger.domain.CategoryRenameViolation
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * D-087 RG-02 `category_rename` minimal closed loop: the application executor
 * forwards the identity and new name to the commit port and maps the domain
 * outcome without inventing lifecycle rules.
 */
class ConfirmedCategoryRenameTest {
    private val ledgerId = LedgerId("ledger-a")
    private val categoryId = CategoryId("income-category-salary")

    @Test
    fun `execute forwards the identity and new name and returns the accepted change`() {
        var observedIdentity: CategoryRenameIdentity? = null
        var observedName: String? = null
        val port = ConfirmedCategoryRenameCommitPort { identity, newName, applyRename ->
            observedIdentity = identity
            observedName = newName
            val change = assertIs<DomainResult.Success<CategoryRenameChange>>(
                applyRename(CategoryNameVersion(categoryId, 1, "工资", CategoryNameVersionStatus.CURRENT)),
            ).value
            ConfirmedCategoryRenameResult.Accepted(change)
        }
        val executor = ExecuteConfirmedCategoryRename(port, RenameTestCatalog.catalog)

        val result = executor.execute(
            ExplicitlyConfirmedCategoryRename(ledgerId, categoryId, "薪资", ExplicitManualSave),
        )

        assertEquals(CategoryRenameIdentity(ledgerId, categoryId), observedIdentity)
        assertEquals("薪资", observedName)
        val accepted = assertIs<ConfirmedCategoryRenameResult.Accepted>(result)
        assertEquals(CategoryNameVersion(categoryId, 1, "工资", CategoryNameVersionStatus.SUPERSEDED), accepted.change.superseded)
        assertEquals(CategoryNameVersion(categoryId, 2, "薪资", CategoryNameVersionStatus.CURRENT), accepted.change.current)
    }

    @Test
    fun `domain rejection is mapped to a rejected result`() {
        val port = ConfirmedCategoryRenameCommitPort { _, _, applyRename ->
            val failure = assertIs<DomainResult.Failure>(applyRename(null))
            ConfirmedCategoryRenameResult.Rejected(failure.violation as CategoryRenameViolation)
        }
        val executor = ExecuteConfirmedCategoryRename(port, RenameTestCatalog.catalog)

        val result = executor.execute(
            ExplicitlyConfirmedCategoryRename(ledgerId, categoryId, "薪资", ExplicitManualSave),
        )

        assertEquals(
            CategoryRenameViolation.CurrentNameVersionMissing,
            assertIs<ConfirmedCategoryRenameResult.Rejected>(result).violation,
        )
    }
}

private object RenameTestCatalog {
    val ledgerId = LedgerId("ledger-a")
    val catalog: LedgerCatalog = LedgerCatalog.create(
        accounts = listOf(
            Account(
                AccountId("income-account-salary"), ledgerId,
                AccountKind.INCOME, CurrencyUnit("CNY", 2),
                ownedByUser = false, realAccount = false,
            ),
            Account(
                AccountId("asset-bank-a"), ledgerId,
                AccountKind.ASSET, CurrencyUnit("CNY", 2),
                ownedByUser = true, realAccount = true,
            ),
        ),
        categories = listOf(
            Category(
                CategoryId("income-category-work"), ledgerId,
                parentId = null, postingAccountId = null, active = true,
                kind = CategoryKind.INCOME,
            ),
            Category(
                CategoryId("income-category-salary"), ledgerId,
                parentId = CategoryId("income-category-work"),
                postingAccountId = AccountId("income-account-salary"),
                active = true, kind = CategoryKind.INCOME,
            ),
        ),
    ).let { assertIs<DomainResult.Success<LedgerCatalog>>(it).value }
}
