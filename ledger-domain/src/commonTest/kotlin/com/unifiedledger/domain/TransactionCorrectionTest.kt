package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-05.B domain evidence (spec sections 3.2/4.1): the correction validation derives the
 * replacement posting set from the current catalog, the append form copies
 * `occurred_at`/`effective_at` verbatim and changes only `statistics_at`/`note`/postings, and
 * the affected-funding-leg derivation is the frozen DP-10 definition. Values are the spec's
 * exact expectations (V-05/V-06 amount 100 -> 80; V-08 unchanged leg).
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

    @Test
    fun correctionAppendCopiesOccurredAndEffectiveAtVerbatimAndChangesOnlyTheTargetState() {
        val catalog = catalog()
        val occurredAt = Instant.parse("2026-03-05T02:00:00Z")
        val statisticsAt = Instant.parse("2026-03-05T02:00:00Z")
        val created =
            assertIs<DomainResult.Success<FormalTransaction>>(
                createAssetPaidOrdinaryExpense(
                    catalog = catalog,
                    command =
                        AssetPaidOrdinaryExpenseCommand(
                            ledgerId = ledgerId,
                            amount = money(10_000),
                            categoryId = categoryId,
                            paymentAccountId = bankId,
                            times = TransactionTimes.collapsed(occurredAt),
                            note = "original note",
                        ),
                    ids =
                        AssetPaidOrdinaryExpenseIds(
                            transactionId = TransactionId("tx-correction-domain"),
                            versionId = TransactionVersionId("version-correction-1"),
                            postingSetId = PostingSetId("posting-set-correction-1"),
                            expensePostingId = PostingId("posting-correction-expense-1"),
                            paymentPostingId = PostingId("posting-correction-payment-1"),
                        ),
                ),
            ).value

        val newStatisticsAt = Instant.parse("2026-04-05T02:00:00Z")
        val corrected =
            assertIs<DomainResult.Success<FormalTransaction>>(
                created.appendVersion(
                    change =
                        TransactionVersionChange.Correction(
                            note = "corrected note",
                            statisticsAt = newStatisticsAt,
                            postings =
                                listOf(
                                    Posting(PostingId("posting-correction-expense-2"), expenseAccountId, money(8_000)),
                                    Posting(PostingId("posting-correction-payment-2"), bankId, money(-8_000)),
                                ),
                        ),
                    ids = TransactionVersionAppendIds(TransactionVersionId("version-correction-2")),
                    newPostingSetId = PostingSetId("posting-set-correction-2"),
                ),
            ).value

        val appended = corrected.versions.single { it.id == TransactionVersionId("version-correction-2") }
        assertEquals(2, appended.versionNumber)
        // The reused primitive copies occurred_at/effective_at verbatim (DP-7 keeps occurredAt OPEN).
        assertEquals(occurredAt, appended.times.occurredAt)
        assertEquals(occurredAt, appended.times.effectiveAt)
        assertEquals(newStatisticsAt, appended.times.statisticsAt)
        assertEquals("corrected note", appended.note)
        assertEquals(PostingSetId("posting-set-correction-2"), appended.postingSetId)
        // The old version, its posting set and its postings are untouched.
        val original = corrected.versions.single { it.id == TransactionVersionId("version-correction-1") }
        assertEquals(1, original.versionNumber)
        assertEquals(statisticsAt, original.times.statisticsAt)
        assertEquals("original note", original.note)
        assertEquals(PostingSetId("posting-set-correction-1"), original.postingSetId)
        assertEquals(
            listOf(10_000L, -10_000L),
            corrected.postingSets
                .single { it.id == PostingSetId("posting-set-correction-1") }
                .postings
                .map { it.amount.minorUnits },
        )
        assertEquals(
            listOf(8_000L, -8_000L),
            corrected.currentPostings().map { it.amount.minorUnits },
        )
        assertEquals(TransactionVersionId("version-correction-2"), corrected.transaction.currentVersionId)
        assertFalse(corrected.versions.size == 1)
    }
}
