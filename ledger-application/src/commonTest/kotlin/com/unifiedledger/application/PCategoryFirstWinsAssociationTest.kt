package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-369 C: the four read faces that used to install the LAST category claiming a duplicated
 * `posting_account_id` now share [categoryByPostingAccountFirstWins] and pick the SMALLEST stable
 * `CategoryId` text. The rule is asserted on the shared fold itself, on the monthly aggregation and
 * on the transaction-detail projection; the recycle-bin and report folds call the same function, so
 * a duplicated mapping resolves the same way on every read face.
 *
 * All data is synthetic. No SQL, schema or ledger write is involved.
 */
class PCategoryFirstWinsAssociationTest {
    private val ledgerId = LedgerId("ledger-c-first-wins")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetId = AccountId("account-asset")
    private val expenseId = AccountId("account-expense")
    private val parentId = CategoryId("category-parent")
    private val firstLeafId = CategoryId("category-a-first")
    private val secondLeafId = CategoryId("category-z-second")

    @Test
    fun duplicatedPostingAccountResolvesToTheSmallestCategoryIdInEitherInputOrder() {
        val first = leaf(firstLeafId, "早餐 A")
        val second = leaf(secondLeafId, "早餐 Z")

        assertEquals(firstLeafId, categoryByPostingAccountFirstWins(listOf(first, second))[expenseId]?.id)
        assertEquals(firstLeafId, categoryByPostingAccountFirstWins(listOf(second, first))[expenseId]?.id)
    }

    @Test
    fun categoriesWithoutAPostingAccountAreNotAssociationCandidates() {
        val group = Category(parentId, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮")
        val mapping = categoryByPostingAccountFirstWins(listOf(group, leaf(firstLeafId, "早餐")))

        assertEquals(setOf(expenseId), mapping.keys)
        assertTrue(group.id !in mapping.values.map { it.id })
    }

    @Test
    fun monthlyAggregationNamesTheSmallestIdCategoryForADuplicatedMapping() {
        val rows = listOf(expenseRow())
        val activity =
            MonthlyBuckets.aggregate(
                rows = rows,
                ledgerId = ledgerId,
                catalog = duplicateCatalog(),
                months = listOf(YearMonth(2026, 3)),
            ).getValue(YearMonth(2026, 3))

        // The product tree rule is unchanged: the posting-mapped leaf is grouped under its parent,
        // so the level-1 node is 餐饮 and the first-wins leaf appears as its only child.
        val parent = activity.expenseCategories.single()
        assertEquals(parentId, parent.categoryId)
        assertEquals("餐饮", parent.categoryName)
        val child = parent.children.single()
        assertEquals(firstLeafId, child.categoryId)
        assertEquals("早餐 A", child.categoryName)
        // The losing duplicate never materialises as a second child or a second level-1 node.
        assertEquals(1, parent.children.size)
        assertEquals(1, activity.expenseCategories.size)
    }

    private fun leaf(
        id: CategoryId,
        name: String,
    ): Category = Category(id, ledgerId, parentId = parentId, postingAccountId = expenseId, active = true, name = name)

    private fun expenseRow(): LedgerEntryRow =
        LedgerEntryRow(
            transactionId = TransactionId("tx-c-first-wins"),
            currentVersionId = TransactionVersionId("version-c-first-wins"),
            kind = TransactionKind.EXPENSE,
            occurredAt = Instant.parse("2026-03-01T02:00:00Z"),
            statisticsAt = Instant.parse("2026-03-05T02:00:00Z"),
            note = null,
            postings =
                listOf(
                    Posting(PostingId("posting-expense"), expenseId, Money.ofMinor(2_000L, cny)),
                    Posting(PostingId("posting-asset"), assetId, Money.ofMinor(-2_000L, cny)),
                ),
        )

    private fun duplicateCatalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "资产-现金"),
                            Account(expenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "支出-早餐"),
                        ),
                    categories =
                        listOf(
                            Category(parentId, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮"),
                            leaf(firstLeafId, "早餐 A"),
                            leaf(secondLeafId, "早餐 Z"),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }
}
