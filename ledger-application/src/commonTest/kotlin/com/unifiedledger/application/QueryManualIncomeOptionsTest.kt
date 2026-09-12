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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-02.A S-1 income option projection: receiving accounts are same-ledger owned real ASSET
 * accounts and income categories are active leaf INCOME categories with a same-ledger INCOME
 * posting account; labels come from the authoritative name.
 */
class QueryManualIncomeOptionsTest {
    private val ledgerId = LedgerId("ledger-a")
    private val otherLedger = LedgerId("ledger-b")
    private val cny = CurrencyUnit("CNY", 2)

    private fun catalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(AccountId("asset-owned"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "工资卡"),
                        Account(AccountId("asset-not-owned"), ledgerId, AccountKind.ASSET, cny, ownedByUser = false, realAccount = true),
                        Account(AccountId("asset-cross-ledger"), otherLedger, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                        Account(AccountId("income-posting"), ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                        Account(AccountId("expense-posting"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                    ),
                categories =
                    listOf(
                        Category(CategoryId("income-group"), ledgerId, null, null, active = true, kind = CategoryKind.INCOME, name = "收入"),
                        Category(CategoryId("income-leaf"), ledgerId, CategoryId("income-group"), AccountId("income-posting"), active = true, kind = CategoryKind.INCOME, name = "工资"),
                        Category(CategoryId("income-inactive"), ledgerId, CategoryId("income-group"), AccountId("income-posting"), active = false, kind = CategoryKind.INCOME),
                        Category(CategoryId("expense-leaf"), ledgerId, null, AccountId("expense-posting"), active = true, kind = CategoryKind.EXPENSE),
                    ),
            ),
        ).value

    @Test
    fun `income options project only eligible receiving accounts and active income leaves`() {
        val options = QueryManualIncomeOptions(ledgerId, catalog()).queryOptions()
        assertEquals(listOf(AccountId("asset-owned")), options.receivingAccounts.map { it.accountId })
        assertEquals("工资卡", options.receivingAccounts.single().label)
        assertEquals(listOf(CategoryId("income-leaf")), options.incomeCategories.map { it.categoryId })
        assertEquals("工资", options.incomeCategories.single().label)
        assertEquals(AccountId("income-posting"), options.incomeCategories.single().postingAccountId)
    }

    @Test
    fun `inactive and wrong-kind categories are excluded`() {
        val options = QueryManualIncomeOptions(ledgerId, catalog()).queryOptions()
        assertTrue(options.incomeCategories.none { it.categoryId == CategoryId("income-inactive") })
        assertTrue(options.incomeCategories.none { it.categoryId == CategoryId("expense-leaf") })
    }

    @Test
    fun `label falls back to the stable id when the catalog row has no name`() {
        val catalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(AccountId("asset"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(AccountId("income-posting"), ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(CategoryId("parent"), ledgerId, null, null, true, CategoryKind.INCOME),
                            Category(CategoryId("leaf"), ledgerId, CategoryId("parent"), AccountId("income-posting"), true, CategoryKind.INCOME),
                        ),
                ),
            ).value
        val options = QueryManualIncomeOptions(ledgerId, catalog).queryOptions()
        assertEquals("asset", options.receivingAccounts.single().label)
        assertEquals("leaf", options.incomeCategories.single().label)
    }

    @Test
    fun `income admission accepts an eligible pair and rejects each failure shape`() {
        val catalog = catalog()
        assertNull(validateManualIncomeAdmission(catalog, ledgerId, AccountId("asset-owned"), CategoryId("income-leaf")))

        assertEquals(
            CatalogAdmissionViolation.PaymentAccountNotFound,
            validateManualIncomeAdmission(catalog, ledgerId, AccountId("missing"), CategoryId("income-leaf")),
        )
        assertEquals(
            CatalogAdmissionViolation.PaymentAccountNotManageableFinancial,
            validateManualIncomeAdmission(catalog, ledgerId, AccountId("asset-not-owned"), CategoryId("income-leaf")),
        )
        assertEquals(
            CatalogAdmissionViolation.PaymentAccountNotFound,
            validateManualIncomeAdmission(catalog, ledgerId, AccountId("asset-cross-ledger"), CategoryId("income-leaf")),
        )
        assertEquals(
            CatalogAdmissionViolation.CategoryNotFound,
            validateManualIncomeAdmission(catalog, ledgerId, AccountId("asset-owned"), CategoryId("missing")),
        )
        assertEquals(
            CatalogAdmissionViolation.CategoryKindMismatch,
            validateManualIncomeAdmission(catalog, ledgerId, AccountId("asset-owned"), CategoryId("expense-leaf")),
        )
        assertEquals(
            CatalogAdmissionViolation.CategoryInactive,
            validateManualIncomeAdmission(catalog, ledgerId, AccountId("asset-owned"), CategoryId("income-inactive")),
        )
        // A level-1 group is not a leaf.
        assertEquals(
            CatalogAdmissionViolation.CategoryNotLeaf,
            validateManualIncomeAdmission(catalog, ledgerId, AccountId("asset-owned"), CategoryId("income-group")),
        )
    }

    @Test
    fun `income admission wrapper maps failures to the real token family`() {
        val catalog = catalog()
        val wrapper =
            CatalogAdmissionIncomeTransactionFactory(
                admissionReader = { ledgerId -> catalog },
                delegate = ConfirmedIncomeTransactionFactory { _, _ -> error("delegate must not run") },
            )
        val snapshot =
            ManualIncomeRequestSnapshot(
                ledgerId = ledgerId,
                amount =
                    com.unifiedledger.domain.Money
                        .ofMinor(100, cny),
                categoryId = CategoryId("income-inactive"),
                receivingAccountId = AccountId("asset-owned"),
                occurredAt = kotlin.time.Instant.parse("2026-01-15T00:30:00Z"),
                note = "",
            )
        val result = wrapper.create(snapshot, ConfirmedManualIncomeCommitIds(ConfirmationId("c"), com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds(com.unifiedledger.domain.TransactionId("t"), com.unifiedledger.domain.TransactionVersionId("v"), com.unifiedledger.domain.PostingSetId("s"), com.unifiedledger.domain.PostingId("p1"), com.unifiedledger.domain.PostingId("p2"))))
        assertEquals(
            com.unifiedledger.domain.CatalogAdmissionRejection.CategoryInactive,
            assertIs<DomainResult.Failure>(result).violation,
        )
    }
}
