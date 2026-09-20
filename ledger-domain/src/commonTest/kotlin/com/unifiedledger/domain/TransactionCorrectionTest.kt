package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-05.B domain evidence (spec sections 3.2/4.1): the correction validation derives the
 * replacement posting set from the current catalog, and the affected-funding-leg derivation is
 * the frozen DP-10 definition. Values are the spec's exact expectations (V-05/V-06 amount
 * 100 -> 80; V-08 unchanged leg). The version append itself belongs to the product correction
 * port (its CAS-guarded SQL copy statements pick the frozen write form), so the shared
 * [FormalTransaction.appendVersion] primitive stays at the three forms the design froze and is
 * exercised by its RG-01/11/12 callers.
 */
class TransactionCorrectionTest {
    private val ledgerId = LedgerId("ledger-p705-domain")
    private val cny = CurrencyUnit("CNY", 2)
    private val bankId = AccountId("asset-bank-a")
    private val otherBankId = AccountId("asset-bank-b")
    private val expenseAccountId = AccountId("expense-food-account")
    private val incomeAccountId = AccountId("income-salary-account")
    private val categoryId = CategoryId("category-food")
    private val incomeCategoryId = CategoryId("category-salary")

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(bankId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(otherBankId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(incomeAccountId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(CategoryId("category-food-parent"), ledgerId, null, null, active = true),
                            Category(categoryId, ledgerId, CategoryId("category-food-parent"), expenseAccountId, active = true),
                            Category(CategoryId("category-salary-parent"), ledgerId, null, null, active = true, kind = CategoryKind.INCOME),
                            Category(incomeCategoryId, ledgerId, CategoryId("category-salary-parent"), incomeAccountId, active = true, kind = CategoryKind.INCOME),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("fixture catalog must be valid")
        }

    private fun money(minor: Long) = Money.ofMinor(minor, cny)

    @Test
    fun expenseCorrectionKeepsTheCreationLegOrderAndBalancesPerCurrency() {
        val planned =
            planOrdinaryCorrectionPostings(
                catalog = catalog(),
                command =
                    OrdinaryCorrectionCommand(
                        ledgerId = ledgerId,
                        kind = TransactionKind.EXPENSE,
                        amount = money(8_000),
                        categoryId = categoryId,
                        fundingAccountId = bankId,
                    ),
                ids = OrdinaryCorrectionPostingIds(PostingId("posting-new-expense"), PostingId("posting-new-payment")),
            )
        val postings = assertIs<OrdinaryCorrectionPlan.Postings>(planned).postings
        assertEquals(listOf("posting-new-expense", "posting-new-payment"), postings.map { it.id.value })
        assertEquals(listOf(expenseAccountId, bankId), postings.map { it.accountId })
        assertEquals(listOf(8_000L, -8_000L), postings.map { it.amount.minorUnits })
        assertIs<DomainResult.Success<PostingSet>>(
            PostingSet.create(PostingSetId("posting-set-new"), postings),
        )
    }

    @Test
    fun incomeCorrectionKeepsTheFundingLegFirst() {
        val planned =
            planOrdinaryCorrectionPostings(
                catalog = catalog(),
                command =
                    OrdinaryCorrectionCommand(
                        ledgerId = ledgerId,
                        kind = TransactionKind.INCOME,
                        amount = money(50_000),
                        categoryId = incomeCategoryId,
                        fundingAccountId = bankId,
                    ),
                ids = OrdinaryCorrectionPostingIds(PostingId("posting-new-income"), PostingId("posting-new-receipt")),
            )
        val postings = assertIs<OrdinaryCorrectionPlan.Postings>(planned).postings
        assertEquals(listOf("posting-new-receipt", "posting-new-income"), postings.map { it.id.value })
        assertEquals(listOf(bankId, incomeAccountId), postings.map { it.accountId })
        assertEquals(listOf(50_000L, -50_000L), postings.map { it.amount.minorUnits })
    }

    @Test
    fun unsupportedKindsAndInadmissibleReferencesAreTypedRejections() {
        fun plan(
            kind: TransactionKind,
            category: CategoryId = categoryId,
            account: AccountId = bankId,
            minor: Long = 8_000,
        ) = planOrdinaryCorrectionPostings(
            catalog = catalog(),
            command = OrdinaryCorrectionCommand(ledgerId, kind, money(minor), category, account),
            ids = OrdinaryCorrectionPostingIds(PostingId("p1"), PostingId("p2")),
        )

        assertEquals(
            P705FailureCode.P705_KIND_NOT_SUPPORTED,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.ACCOUNT_TRANSFER)).code,
        )
        assertEquals(
            P705FailureCode.P705_KIND_NOT_SUPPORTED,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.LEND)).code,
        )
        // Category/kind mismatch: an EXPENSE transaction may not be pointed at an INCOME category.
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.EXPENSE, incomeCategoryId)).code,
        )
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.EXPENSE, CategoryId("category-missing"))).code,
        )
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.EXPENSE, categoryId, AccountId("asset-missing"))).code,
        )
        assertEquals(
            P705FailureCode.P705_FIELD_NOT_SUPPORTED,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.EXPENSE, categoryId, bankId, 0L)).code,
        )
    }

    @Test
    fun inactiveCategoryIsRejectedWhileAnActiveRenameIsStillAdmissible() {
        val inactive =
            when (
                val result =
                    LedgerCatalog.create(
                        accounts =
                            listOf(
                                Account(bankId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                                Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            ),
                        categories =
                            listOf(
                                Category(CategoryId("category-food-parent"), ledgerId, null, null, active = true, name = "餐饮"),
                                Category(categoryId, ledgerId, CategoryId("category-food-parent"), expenseAccountId, active = false, name = "餐饮-旧名"),
                            ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> error("fixture catalog must be valid")
            }
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<OrdinaryCorrectionPlan.Rejected>(
                planOrdinaryCorrectionPostings(
                    catalog = inactive,
                    command =
                        OrdinaryCorrectionCommand(
                            ledgerId,
                            TransactionKind.EXPENSE,
                            money(8_000),
                            categoryId,
                            bankId,
                        ),
                    ids = OrdinaryCorrectionPostingIds(PostingId("p1"), PostingId("p2")),
                ),
            ).code,
        )
    }

    @Test
    fun affectedFundingLegsExcludeUnchangedLegsAndNonFundingAccounts() {
        val oldExpense = Posting(PostingId("old-expense"), expenseAccountId, money(10_000))
        val oldFunding = Posting(PostingId("old-funding"), bankId, money(-10_000))
        val realAccounts = setOf(bankId, otherBankId)

        // V-05 100 -> 80: the funding leg changed, the expense leg never participates.
        assertEquals(
            listOf(-8_000L),
            affectedFundingLegs(
                oldPostings = listOf(oldExpense, oldFunding),
                newPostings = listOf(Posting(PostingId("new-expense"), expenseAccountId, money(8_000)), Posting(PostingId("new-funding"), bankId, money(-8_000))),
                realAccountIds = realAccounts,
            ).map { it.amount.minorUnits },
        )
        // V-08 unchanged funding leg: same (accountId, amount, currency) is not affected.
        assertTrue(
            affectedFundingLegs(
                oldPostings = listOf(oldExpense, oldFunding),
                newPostings = listOf(Posting(PostingId("new-expense"), expenseAccountId, money(12_000)), Posting(PostingId("new-funding"), bankId, money(-10_000))),
                realAccountIds = realAccounts,
            ).isEmpty(),
        )
        // A funding-account switch is affected, and the old leg is not "preserved" as new.
        assertEquals(
            listOf(otherBankId),
            affectedFundingLegs(
                oldPostings = listOf(oldExpense, oldFunding),
                newPostings = listOf(Posting(PostingId("new-expense"), expenseAccountId, money(10_000)), Posting(PostingId("new-funding"), otherBankId, money(-10_000))),
                realAccountIds = realAccounts,
            ).map { it.accountId },
        )
    }
}