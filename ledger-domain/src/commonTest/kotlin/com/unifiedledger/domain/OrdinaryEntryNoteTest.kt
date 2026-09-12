package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-02.A S-4: the note carried by the expense/income command must reach
 * `TransactionVersion.note` verbatim, and the frozen empty-string default must keep the
 * rg-01/rg-02 import goldens byte-identical.
 */
class OrdinaryEntryNoteTest {
    private val ledger = LedgerId("ledger")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    private fun expenseCatalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("asset"), ledger, AccountKind.ASSET, cny, true, true),
                    Account(AccountId("expense"), ledger, AccountKind.EXPENSE, cny, false, false),
                ),
                listOf(
                    Category(CategoryId("parent"), ledger, null, null, true, CategoryKind.EXPENSE),
                    Category(CategoryId("child"), ledger, CategoryId("parent"), AccountId("expense"), true, CategoryKind.EXPENSE),
                ),
            ),
        ).value

    private fun incomeCatalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("asset"), ledger, AccountKind.ASSET, cny, true, true),
                    Account(AccountId("income"), ledger, AccountKind.INCOME, cny, false, false),
                ),
                listOf(
                    Category(CategoryId("parent"), ledger, null, null, true, CategoryKind.INCOME),
                    Category(CategoryId("child"), ledger, CategoryId("parent"), AccountId("income"), true, CategoryKind.INCOME),
                ),
            ),
        ).value

    @Test
    fun `expense note reaches the formal version`() {
        val result =
            createAssetPaidOrdinaryExpense(
                catalog = expenseCatalog(),
                command =
                    AssetPaidOrdinaryExpenseCommand(
                        ledgerId = ledger,
                        amount = Money.ofMinor(3_580, cny),
                        categoryId = CategoryId("child"),
                        paymentAccountId = AccountId("asset"),
                        times = TransactionTimes.collapsed(occurredAt),
                        note = "lunch with team",
                    ),
                ids = AssetPaidOrdinaryExpenseIds(TransactionId("tx"), TransactionVersionId("v1"), PostingSetId("set"), PostingId("expense-posting"), PostingId("payment-posting")),
            )
        assertEquals(
            "lunch with team",
            assertIs<DomainResult.Success<FormalTransaction>>(result)
                .value.versions
                .single()
                .note,
        )
    }

    @Test
    fun `expense note default stays empty for legacy and golden callers`() {
        val result =
            createAssetPaidOrdinaryExpense(
                catalog = expenseCatalog(),
                command =
                    AssetPaidOrdinaryExpenseCommand(
                        ledgerId = ledger,
                        amount = Money.ofMinor(3_580, cny),
                        categoryId = CategoryId("child"),
                        paymentAccountId = AccountId("asset"),
                        times = TransactionTimes.collapsed(occurredAt),
                    ),
                ids = AssetPaidOrdinaryExpenseIds(TransactionId("tx"), TransactionVersionId("v1"), PostingSetId("set"), PostingId("expense-posting"), PostingId("payment-posting")),
            )
        assertEquals(
            "",
            assertIs<DomainResult.Success<FormalTransaction>>(result)
                .value.versions
                .single()
                .note,
        )
    }

    @Test
    fun `income note reaches the formal version`() {
        val result =
            createAssetReceivedOrdinaryIncome(
                catalog = incomeCatalog(),
                command =
                    AssetReceivedOrdinaryIncomeCommand(
                        ledgerId = ledger,
                        amount = Money.ofMinor(3_000, cny),
                        categoryId = CategoryId("child"),
                        receivingAccountId = AccountId("asset"),
                        times = TransactionTimes.collapsed(occurredAt),
                        note = "salary",
                    ),
                ids = AssetReceivedOrdinaryIncomeIds(TransactionId("tx"), TransactionVersionId("v1"), PostingSetId("set"), PostingId("asset-posting"), PostingId("income-posting")),
            )
        assertEquals(
            "salary",
            assertIs<DomainResult.Success<FormalTransaction>>(result)
                .value.versions
                .single()
                .note,
        )
    }

    @Test
    fun `income note default stays empty for legacy and golden callers`() {
        val result =
            createAssetReceivedOrdinaryIncome(
                catalog = incomeCatalog(),
                command =
                    AssetReceivedOrdinaryIncomeCommand(
                        ledgerId = ledger,
                        amount = Money.ofMinor(3_000, cny),
                        categoryId = CategoryId("child"),
                        receivingAccountId = AccountId("asset"),
                        times = TransactionTimes.collapsed(occurredAt),
                    ),
                ids = AssetReceivedOrdinaryIncomeIds(TransactionId("tx"), TransactionVersionId("v1"), PostingSetId("set"), PostingId("asset-posting"), PostingId("income-posting")),
            )
        assertEquals(
            "",
            assertIs<DomainResult.Success<FormalTransaction>>(result)
                .value.versions
                .single()
                .note,
        )
    }
}
