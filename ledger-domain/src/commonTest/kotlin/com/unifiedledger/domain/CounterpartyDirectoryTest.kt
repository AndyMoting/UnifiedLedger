package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-02.C L-1 minimal stable counterparty directory: stable id, append-only name history,
 * rename never touches the receivable account or balance, active toggle, and the deliberately
 * non-manageable hidden receivable account shape (D-143 disclosure; the opposite of the RG-08
 * replay `ownedByUser && realAccount` predicate).
 */
class CounterpartyDirectoryTest {
    private val ledgerId = LedgerId("ledger-c")
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun createStartsCurrentNameHistoryAndIsActive() {
        val created =
            success(
                createCounterparty(
                    id = CounterpartyId("cp-1"),
                    ledgerId = ledgerId,
                    name = "  Alice\u3000Smith ",
                    receivableAccountId = AccountId("recv-cp-1"),
                ),
            )
        assertEquals("Alice Smith", created.name)
        assertEquals(listOf(CounterpartyNameVersion(1, "Alice Smith", true)), created.nameHistory)
        assertTrue(created.active)
    }

    @Test
    fun renameAppendsHistoryAndDoesNotMoveTheReceivable() {
        val created = success(createCounterparty(CounterpartyId("cp-1"), ledgerId, "Alice", AccountId("recv-cp-1")))
        val renamed = success(renameCounterparty(created, "Alice Cooper"))
        assertEquals("Alice Cooper", renamed.name)
        assertEquals(AccountId("recv-cp-1"), renamed.receivableAccountId)
        assertEquals(2, renamed.nameHistory.size)
        assertFalse(renamed.nameHistory.first().current)
        assertTrue(renamed.nameHistory.last().current)
        assertEquals(listOf("Alice", "Alice Cooper"), renamed.nameHistory.map { it.name })
    }

    @Test
    fun renameToCurrentNameIsANoOp() {
        val created = success(createCounterparty(CounterpartyId("cp-1"), ledgerId, "Alice", AccountId("recv-cp-1")))
        val renamed = success(renameCounterparty(created, "  Alice "))
        assertEquals(1, renamed.nameHistory.size)
    }

    @Test
    fun emptyAndControlCharacterNamesAreRejected() {
        assertIs<CatalogViolation.CatalogNameEmpty>(failure(createCounterparty(CounterpartyId("cp-1"), ledgerId, "\u3000 ", AccountId("recv-cp-1"))))
        assertIs<CatalogViolation.CatalogNameInvalid>(failure(createCounterparty(CounterpartyId("cp-1"), ledgerId, "a\u0009b", AccountId("recv-cp-1"))))
    }

    @Test
    fun activeToggleKeepsHistory() {
        val created = success(createCounterparty(CounterpartyId("cp-1"), ledgerId, "Alice", AccountId("recv-cp-1")))
        val deactivated = success(setCounterpartyActive(created, false))
        assertFalse(deactivated.active)
        assertEquals(created.nameHistory, deactivated.nameHistory)
    }

    @Test
    fun receivableAccountIsHiddenNonOwnedNonRealAsset() {
        val account = counterpartyReceivableAccount(AccountId("recv-cp-1"), ledgerId, cny, "Alice")
        assertEquals(AccountKind.ASSET, account.kind)
        assertFalse(account.ownedByUser)
        assertFalse(account.realAccount)
        assertEquals(null, account.systemRole)
        // Opposite of the RG-08 replay predicate and outside the A-2 manageable set.
        assertFalse(isManageableAccount(account, ledgerId))
    }
}
