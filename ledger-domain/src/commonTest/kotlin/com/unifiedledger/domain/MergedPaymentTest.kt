package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MergedPaymentTest {
    private val currency = CurrencyUnit("CNY", 2)
    private val ledger = LedgerId("ledger-a")
    private val catalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("asset"), ledger, AccountKind.ASSET, currency, true, true),
                    Account(AccountId("expense-a"), ledger, AccountKind.EXPENSE, currency, false, false),
                    Account(AccountId("expense-b"), ledger, AccountKind.EXPENSE, currency, false, false),
                ),
                listOf(
                    Category(CategoryId("root"), ledger, null, null, true),
                    Category(CategoryId("cat-a"), ledger, CategoryId("root"), AccountId("expense-a"), true),
                    Category(CategoryId("cat-b"), ledger, CategoryId("root"), AccountId("expense-b"), true),
                ),
            ),
        ).value

    @Test
    fun createsExactlyTwoExpenseLegsAndOnePaymentAssetLeg() {
        val result = assertIs<DomainResult.Success<MergedPaymentExpense>>(createMergedPaymentExpense(catalog, command(), ids()))
        assertEquals(listOf(4_000L, 6_000L, -10_000L), result.value.postings.map { it.posting.amount.minorUnits })
        assertEquals(listOf("a", "b"), result.value.consumptions.map { it.itemId })
        assertEquals(10_000L, result.value.allocations.sumOf { it.amount.minorUnits })
        assertEquals(-10_000L, result.value.reportEffects.netWorthChangeMinor)
    }

    @Test
    fun rejectsAllocationMismatchWithoutCreatingFormalAggregate() {
        val bad = command().copy(items = command().items.map { it.copy(amount = Money.ofMinor(4_000, currency)) })
        val result = assertIs<DomainResult.Failure>(createMergedPaymentExpense(catalog, bad, ids()))
        assertEquals(MergedPaymentViolation.AllocationTotalMustEqualPayment, result.violation)
    }

    @Test
    fun rejectsCategoryFromAnotherLedgerOrWithoutAValidRoot() {
        val otherLedger = LedgerId("ledger-b")
        val wrongLedgerCatalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    catalog.accounts,
                    listOf(
                        Category(CategoryId("root"), ledger, null, null, true),
                        Category(CategoryId("cat-a"), otherLedger, CategoryId("root"), AccountId("expense-a"), true),
                        Category(CategoryId("cat-b"), ledger, CategoryId("root"), AccountId("expense-b"), true),
                    ),
                ),
            ).value
        assertEquals(DomainViolation.InvalidMergedPayment, assertIs<DomainResult.Failure>(createMergedPaymentExpense(wrongLedgerCatalog, command(), ids())).violation)

        val nestedRootCatalog =
            assertIs<DomainResult.Success<LedgerCatalog>>(
                LedgerCatalog.create(
                    catalog.accounts,
                    listOf(
                        Category(CategoryId("top"), ledger, null, null, true),
                        Category(CategoryId("root"), ledger, CategoryId("top"), null, true),
                        Category(CategoryId("cat-a"), ledger, CategoryId("root"), AccountId("expense-a"), true),
                        Category(CategoryId("cat-b"), ledger, CategoryId("root"), AccountId("expense-b"), true),
                    ),
                ),
            ).value
        assertEquals(DomainViolation.InvalidMergedPayment, assertIs<DomainResult.Failure>(createMergedPaymentExpense(nestedRootCatalog, command(), ids())).violation)
    }

    private fun command() =
        MergedPaymentExpenseCommand(
            ledger,
            Money.ofMinor(10_000, currency),
            AccountId("asset"),
            TransactionTimes.collapsed(Instant.parse("2026-04-10T10:30:00Z")),
            listOf(
                MergedPaymentItem("a", Money.ofMinor(4_000, currency), CategoryId("cat-a"), "daily", Instant.parse("2026-04-10T09:00:00Z")),
                MergedPaymentItem("b", Money.ofMinor(6_000, currency), CategoryId("cat-b"), "service", Instant.parse("2026-04-10T09:05:00Z")),
            ),
        )

    private fun ids() = MergedPaymentExpenseIds(TransactionId("tx"), TransactionVersionId("v"), PostingSetId("set"), listOf(PostingId("expense-a-post"), PostingId("expense-b-post")), PostingId("asset-post"))
}
