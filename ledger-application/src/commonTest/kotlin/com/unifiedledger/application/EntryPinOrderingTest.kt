package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P7-02 E-4: the sort derivation puts pinned entries first and keeps the remaining entries in
 * their existing deterministic order (stable partition); pins never reorder anything else.
 */
class EntryPinOrderingTest {
    private val ledger = LedgerId("ledger-pin-test")
    private val otherLedger = LedgerId("ledger-other")

    private fun account(id: String) = ManageableAccountView(AccountId(id), name = id, kind = AccountKind.ASSET, currency = CurrencyUnit("CNY", 2), active = true, balanceMinorUnits = null)

    private fun leaf(
        id: String,
        group: String,
    ) = CategoryTreeView(CategoryId(id), CategoryId(group), name = id, kind = com.unifiedledger.domain.CategoryKind.EXPENSE, active = true, postingAccountId = AccountId("posting"))

    @Test
    fun pinnedAccountsMoveFirstAndOthersKeepTheirOrder() {
        val accounts = listOf(account("a"), account("b"), account("c"), account("d"))
        val pinned =
            setOf(
                EntryPinTarget.AccountTarget(ledger, AccountId("c")),
                EntryPinTarget.AccountTarget(ledger, AccountId("a")),
            )
        // Stable partition: pinned entries surface first in their existing relative order
        // (a before c), then the remaining entries unchanged.
        val sorted = EntryPinOrdering.sortAccounts(accounts, pinned)
        assertEquals(listOf("a", "c", "b", "d"), sorted.map { it.name })
    }

    @Test
    fun emptyPinSetLeavesTheListUnchanged() {
        val categories = listOf(leaf("leaf-2", "g"), leaf("leaf-1", "g"))
        assertEquals(categories, EntryPinOrdering.sortCategories(categories, emptySet()))
        val accounts = listOf(account("a"), account("b"))
        assertEquals(accounts, EntryPinOrdering.sortAccounts(accounts, emptySet()))
    }

    @Test
    fun foreignLedgerPinsNeverMatch() {
        val accounts = listOf(account("a"), account("b"))
        val pinned = setOf(EntryPinTarget.AccountTarget(otherLedger, AccountId("a")))
        assertEquals(listOf("a", "b"), EntryPinOrdering.sortAccounts(accounts, pinned).map { it.name })
    }

    @Test
    fun pinnedCategoriesKeepTreeGroupingOrder() {
        val categories = listOf(leaf("g1-leaf-1", "g1"), leaf("g1-leaf-2", "g1"), leaf("g2-leaf-1", "g2"))
        val pinned = setOf(EntryPinTarget.CategoryTarget(ledger, CategoryId("g1-leaf-2")))
        // The flat stable partition is the single derivation; the management tree regroups by
        // parent when rendering, so per-parent pinned children surface first without reshuffling
        // the deterministic remainder.
        val sorted = EntryPinOrdering.sortCategories(categories, pinned)
        assertEquals(listOf("g1-leaf-2", "g1-leaf-1", "g2-leaf-1"), sorted.map { it.name })
    }

    @Test
    fun optionProjectionsSortPinnedFirst() {
        val options =
            listOf(
                PaymentAccountOption(AccountId("a"), CurrencyUnit("CNY", 2), "a"),
                PaymentAccountOption(AccountId("b"), CurrencyUnit("CNY", 2), "b"),
            )
        val pinned = setOf(EntryPinTarget.AccountTarget(ledger, AccountId("b")))
        assertEquals(listOf("b", "a"), EntryPinOrdering.sortPaymentAccounts(options, pinned).map { it.label })

        val incomeOptions =
            listOf(
                IncomeCategoryOption(CategoryId("i1"), CategoryId("g"), "i1", AccountId("posting")),
                IncomeCategoryOption(CategoryId("i2"), CategoryId("g"), "i2", AccountId("posting")),
            )
        val pinnedCategory = setOf(EntryPinTarget.CategoryTarget(ledger, CategoryId("i2")))
        assertEquals(listOf("i2", "i1"), EntryPinOrdering.sortIncomeCategories(incomeOptions, pinnedCategory).map { it.label })
    }
}
