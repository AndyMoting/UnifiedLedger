package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
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
        // P8-03 i2b (D-312; spec section 2.2/D-I): the frozen five-type set is explicitly expanded
        // by REFUND_RECEIPT — the one type this gate opens; nothing else is added.
        assertEquals(
            listOf("EXPENSE", "INCOME", "TRANSFER", "LEND", "COLLECT", "REFUND_RECEIPT"),
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
    fun `switching expense to lend retains amount occurredAt and note and clears the counterparty`() {
        val lend = assertIs<LendDraft>(EntryFieldRetention.switchType(expense(), EntryType.LEND))
        assertEquals("35.80", lend.amount)
        assertEquals(occurredAt, lend.occurredAt)
        assertEquals("lunch", lend.note)
        assertEquals(AccountId("asset"), lend.fundingAccountId)
        assertNull(lend.counterpartyId)
    }

    @Test
    fun `switching transfer to collect retains the amount text and clears the counterparty and components`() {
        val transfer = assertIs<TransferDraft>(EntryFieldRetention.switchType(expense(), EntryType.TRANSFER))
        val collect = assertIs<CollectDraft>(EntryFieldRetention.switchType(transfer, EntryType.COLLECT))
        assertEquals("35.80", collect.totalReceived)
        assertEquals("", collect.principal)
        assertEquals("", collect.interest)
        assertEquals("0.00", collect.fee)
        assertEquals(occurredAt, collect.occurredAt)
        assertEquals("lunch", collect.note)
        assertNull(collect.counterpartyId)
        assertNull(collect.interestCategoryId)
        assertNull(collect.destinationAccountId)
    }

    @Test
    fun `switch back to expense does not restore the cleared counterparty`() {
        val lend = assertIs<LendDraft>(EntryFieldRetention.switchType(expense(), EntryType.LEND))
        val back = assertIs<ExpenseDraft>(EntryFieldRetention.switchType(lend, EntryType.EXPENSE))
        assertEquals("35.80", back.amountText)
        assertEquals(AccountId("asset"), back.paymentAccountId)
        assertNull(back.categoryId)
    }

    @Test
    fun `switching expense to transfer retains the asset account class and clears type-specific fields`() {
        val transfer = assertIs<TransferDraft>(EntryFieldRetention.switchType(expense(), EntryType.TRANSFER))
        assertEquals(AccountId("asset"), transfer.sourceAccountId)
        assertEquals("35.80", transfer.destinationCredit)
        assertEquals(occurredAt, transfer.occurredAt)
        assertEquals("lunch", transfer.note)
        assertNull(transfer.destinationAccountId)
        assertNull(transfer.feeCategoryId)

        val back = assertIs<ExpenseDraft>(EntryFieldRetention.switchType(transfer, EntryType.EXPENSE))
        assertEquals(AccountId("asset"), back.paymentAccountId)
        assertEquals("35.80", back.amountText)
        assertNull(back.categoryId)
    }

    @Test
    fun `all five frozen entry types are supported after batch C`() {
        assertNull(validateEntryTypeSupported(EntryType.EXPENSE))
        assertNull(validateEntryTypeSupported(EntryType.INCOME))
        assertNull(validateEntryTypeSupported(EntryType.TRANSFER))
        assertNull(validateEntryTypeSupported(EntryType.LEND))
        assertNull(validateEntryTypeSupported(EntryType.COLLECT))
        // P8-03 i2b (D-312; spec section 2.2): the gate accepts the expanded refund type too.
        assertNull(validateEntryTypeSupported(EntryType.REFUND_RECEIPT))
    }

    /**
     * P8-03 i2b (D-312; spec section 2.2 保留矩阵) — the full 6×6 switch matrix (V-1/V-3 at the
     * draft layer). Every direction asserts the SHARED retention rule (amount text, occurred-at,
     * note, tags, merchant kept; category always null) and the refund type's FROZEN EXCEPTION: a
     * refund never inherits an account and never donates one, in either direction. The other five
     * types keep their frozen asset-account-class migration, pinned by the per-direction tests
     * above; this matrix adds the exhaustive cross-check without weakening any of them.
     */
    @Test
    fun `the full six-by-six switch matrix keeps the shared fields clears the category and never migrates a refund account`() {
        for (source in EntryType.entries) {
            val draft = matrixDraft(source)
            for (target in EntryType.entries) {
                val switched = EntryFieldRetention.switchType(draft, target)!!
                assertEquals(target, switched.entryType, "switch $source -> $target must yield $target")
                assertEquals(draft.amountText, switched.amountText, "amount must survive $source -> $target")
                assertEquals(occurredAt, switched.occurredAt, "occurred-at must survive $source -> $target")
                assertEquals(draft.note, switched.note, "note must survive $source -> $target")
                assertEquals(setOf(TagId("tag-1")), switched.tagIds, "tags must survive $source -> $target")
                assertEquals(MerchantId("merchant-1"), switched.merchantId, "merchant must survive $source -> $target")
                if (source == target) {
                    // A same-type switch returns the current instance unchanged (category included).
                    assertEquals(draft, switched, "a same-type switch returns the current instance for $source")
                } else {
                    assertNull(switched.categoryId, "category is never retained across $source -> $target")
                }
                // D-312 frozen exception: the refund type never participates in the asset-account
                // class migration — neither INTO it nor OUT of it (an off-diagonal switch).
                if (source != target && (source == EntryType.REFUND_RECEIPT || target == EntryType.REFUND_RECEIPT)) {
                    assertNull(
                        switched.primaryAccountId,
                        "no account may migrate across the refund direction $source -> $target",
                    )
                }
            }
        }
    }

    private fun matrixDraft(type: EntryType): TypedEntryDraft =
        when (type) {
            EntryType.EXPENSE -> ExpenseDraft(AccountId("asset"), CategoryId("expense-leaf"), "35.80", occurredAt, "lunch", setOf(TagId("tag-1")), MerchantId("merchant-1"))
            EntryType.INCOME -> IncomeDraft(AccountId("asset"), CategoryId("income-leaf"), "35.80", occurredAt, "lunch", setOf(TagId("tag-1")), MerchantId("merchant-1"))
            EntryType.TRANSFER ->
                TransferDraft(
                    sourceAccountId = AccountId("asset"),
                    destinationAccountId = AccountId("asset-2"),
                    destinationCredit = "35.80",
                    occurredAt = occurredAt,
                    note = "lunch",
                    tagIds = setOf(TagId("tag-1")),
                    merchantId = MerchantId("merchant-1"),
                )
            EntryType.LEND -> LendDraft(CounterpartyId("cp-1"), "35.80", AccountId("asset"), occurredAt, "lunch", setOf(TagId("tag-1")), MerchantId("merchant-1"))
            EntryType.COLLECT ->
                CollectDraft(
                    counterpartyId = CounterpartyId("cp-1"),
                    totalReceived = "35.80",
                    principal = "30.00",
                    interest = "5.80",
                    interestCategoryId = CategoryId("income-leaf"),
                    destinationAccountId = AccountId("asset"),
                    occurredAt = occurredAt,
                    note = "lunch",
                    tagIds = setOf(TagId("tag-1")),
                    merchantId = MerchantId("merchant-1"),
                )
            EntryType.REFUND_RECEIPT ->
                RefundReceiptDraft(
                    originalTransactionId = TransactionId("tx-original"),
                    destinationAccountId = AccountId("asset"),
                    amountText = "35.80",
                    occurredAt = occurredAt,
                    note = "lunch",
                    tagIds = setOf(TagId("tag-1")),
                    merchantId = MerchantId("merchant-1"),
                )
        }

    @Test
    fun `switch type carries the kind-independent tag and merchant association across every target`() {
        // P7-08 08.B-1 (D-221; spec section 4.2): the annotation lives on the transaction root, so a
        // type switch retains the optional tag/merchant association while clearing the type-specific
        // fields. Every target must carry both fields through unchanged (the matrix is a single
        // implementation, so this pins it against a per-branch drift).
        val associated =
            ExpenseDraft(
                paymentAccountId = AccountId("asset"),
                categoryId = CategoryId("expense-leaf"),
                amountText = "35.80",
                occurredAt = occurredAt,
                note = "lunch",
                tagIds = setOf(TagId("tag-1"), TagId("tag-2")),
                merchantId = MerchantId("merchant-1"),
            )
        for (target in EntryType.entries) {
            val switched = EntryFieldRetention.switchType(associated, target)!!
            assertEquals(setOf(TagId("tag-1"), TagId("tag-2")), switched.tagIds, "tags must survive the switch to $target")
            assertEquals(MerchantId("merchant-1"), switched.merchantId, "merchant must survive the switch to $target")
        }
        // An unassociated draft stays unassociated across a switch.
        val switchedPlain = EntryFieldRetention.switchType(expense(), EntryType.INCOME)!!
        assertEquals(emptySet(), switchedPlain.tagIds)
        assertNull(switchedPlain.merchantId)
    }
}
