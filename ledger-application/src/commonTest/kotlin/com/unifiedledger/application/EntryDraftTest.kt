package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.EntryFoundationViolation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P7-02.A S-2/E-1/P1-4: typed drafts, the frozen type-switch retention matrix and the
 * cross-state draft-type retention implementations.
 */
class EntryDraftTest {
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    private fun expense() =
        ExpenseDraft(
            paymentAccountId = AccountId("asset"),
            categoryId = CategoryId("expense-leaf"),
            amountText = "35.80",
            occurredAt = occurredAt,
            note = "lunch",
        )

    private fun income() =
        IncomeDraft(
            receivingAccountId = AccountId("asset"),
            categoryId = CategoryId("income-leaf"),
            amountText = "35.80",
            occurredAt = occurredAt,
            note = "salary",
        )

    @Test
    fun `entry type set is frozen`() {
        assertEquals(
            listOf("EXPENSE", "INCOME", "TRANSFER", "LEND", "COLLECT"),
            EntryType.entries.map { it.name },
        )
    }

    @Test
    fun `expense draft keeps its type and value-compatible fields`() {
        val draft = expense()
        assertEquals(EntryType.EXPENSE, draft.entryType)
        assertEquals(AccountId("asset"), draft.primaryAccountId)
        assertEquals("35.80", draft.amountText)
        assertEquals("lunch", draft.note)
        assertEquals(occurredAt, draft.occurredAt)
    }

    @Test
    fun `switch to income retains amount occurredAt and note but clears the account and category`() {
        val switched = assertIs<IncomeDraft>(EntryFieldRetention.switchType(expense(), EntryType.INCOME))
        assertEquals("35.80", switched.amountText)
        assertEquals(occurredAt, switched.occurredAt)
        assertEquals("lunch", switched.note)
        assertNull(switched.receivingAccountId)
        assertNull(switched.categoryId)
    }

    @Test
    fun `switch back to expense does not restore the cleared account or category`() {
        val income = assertIs<IncomeDraft>(EntryFieldRetention.switchType(expense(), EntryType.INCOME))
        val back = assertIs<ExpenseDraft>(EntryFieldRetention.switchType(income, EntryType.EXPENSE))
        assertEquals("35.80", back.amountText)
        assertEquals(occurredAt, back.occurredAt)
        assertEquals("lunch", back.note)
        assertNull(back.paymentAccountId)
        assertNull(back.categoryId)
    }

    @Test
    fun `same type switch is a no-op preserving the draft value`() {
        assertEquals(expense(), EntryFieldRetention.switchType(expense(), EntryType.EXPENSE))
        assertEquals(income(), EntryFieldRetention.switchType(income(), EntryType.INCOME))
    }

    @Test
    fun `unimplemented target types have no draft in this batch`() {
        for (type in listOf(EntryType.TRANSFER, EntryType.LEND, EntryType.COLLECT)) {
            assertNull(EntryFieldRetention.switchType(expense(), type), "for $type")
            assertNull(EntryFieldRetention.switchType(income(), type), "for $type")
        }
    }

    @Test
    fun `unsupported type gate maps only transfer lend collect`() {
        assertNull(validateEntryTypeSupported(EntryType.EXPENSE))
        assertNull(validateEntryTypeSupported(EntryType.INCOME))
        for (type in listOf(EntryType.TRANSFER, EntryType.LEND, EntryType.COLLECT)) {
            assertEquals(EntryFoundationViolation.EntryTypeNotSupported, validateEntryTypeSupported(type))
        }
    }
}
