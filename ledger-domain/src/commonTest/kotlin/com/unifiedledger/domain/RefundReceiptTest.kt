package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class RefundReceiptTest {
    private val ledgerId = LedgerId("ledger-a")
    private val currency = CurrencyUnit("CNY", 2)
    private val parentId = CategoryId("expense-parent")
    private val categoryId = CategoryId("expense-daily")
    private val destinationId = AccountId("asset-wallet")
    private val expenseAccountId = AccountId("expense-daily-account")
    private val catalog = assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(
        listOf(
            Account(destinationId, ledgerId, AccountKind.ASSET, currency, ownedByUser = true, realAccount = true),
            Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, currency, ownedByUser = false, realAccount = false),
        ),
        listOf(
            Category(parentId, ledgerId, null, null, active = true, kind = CategoryKind.EXPENSE),
            Category(categoryId, ledgerId, parentId, expenseAccountId, active = true, kind = CategoryKind.EXPENSE),
        ),
    )).value

    @Test
    fun createsIndependentRefundReceiptFormalTransaction() {
        val result = assertIs<DomainResult.Success<RefundReceipt>>(createRefundReceipt(
            catalog,
            RefundReceiptCommand(
                ledgerId,
                TransactionId("original-expense"),
                Money.ofMinor(3_000, currency),
                categoryId,
                destinationId,
                TransactionTimes.collapsed(Instant.parse("2026-02-02T07:20:00Z")),
            ),
            RefundReceiptIds(
                TransactionId("refund"), TransactionVersionId("refund-v1"), PostingSetId("refund-set"),
                PostingId("refund-asset"), PostingId("refund-expense"),
            ),
        )).value

        assertEquals(TransactionKind.REFUND_RECEIPT, result.formalTransaction.transaction.kind)
        assertEquals("original-expense", result.originalTransactionId.value)
        assertEquals(listOf(3_000L, -3_000L), result.formalTransaction.postingSets.single().postings.map { it.amount.minorUnits })
    }
}
