package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

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

        // Transfer slice's new behavior (D-228 J1): ACCOUNT_TRANSFER now sits on the shared
        // support-matrix gate and is planned by the transfer planner (its vectors live below and
        // in the transfer commit-port suite); the ordinary planner itself stays ordinary-only,
        // so a transfer kind reaching it directly is still a typed rejection - never a silently
        // mis-planned INCOME shape.
        assertTrue(isP705CorrectionSupportedKind(TransactionKind.ACCOUNT_TRANSFER))
        assertEquals(
            P705FailureCode.P705_KIND_NOT_SUPPORTED,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.ACCOUNT_TRANSFER)).code,
        )
        assertEquals(
            P705FailureCode.P705_KIND_NOT_SUPPORTED,
            assertIs<OrdinaryCorrectionPlan.Rejected>(plan(TransactionKind.LEND)).code,
        )
        // D-158 section 4: the full later-slice matrix rows — REFUND_RECEIPT (DP-13),
        // CREDIT_REPAYMENT (import credit lineage, DP-5), COLLECT (the LEND pair) and the
        // canonical-only kinds — are all outside the first-slice matrix.
        listOf(
            TransactionKind.REFUND_RECEIPT,
            TransactionKind.CREDIT_REPAYMENT,
            TransactionKind.COLLECT,
            TransactionKind.OPENING_BALANCE,
            TransactionKind.STORED_VALUE_RECHARGE,
            TransactionKind.PREPAID_PURCHASE,
        ).forEach { kind ->
            assertEquals(
                P705FailureCode.P705_KIND_NOT_SUPPORTED,
                assertIs<OrdinaryCorrectionPlan.Rejected>(plan(kind)).code,
                "kind $kind must stay outside the first-slice support matrix",
            )
        }
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

    // ---- transfer-slice planner (spec sections 3/8.1; D-228 J1) ----------------------------

    private val transferStatisticsAt: Instant = Instant.parse("2026-03-05T02:00:00Z")

    private fun transferPlan(
        destinationCreditMinor: Long = 10_000L,
        feeMinor: Long = 200L,
        feeCategoryId: CategoryId? = categoryId,
        source: AccountId = bankId,
        destination: AccountId = otherBankId,
        feePostingId: PostingId? = PostingId("p-fee"),
        catalog: LedgerCatalog = catalog(),
    ) = planTransferCorrectionPostings(
        catalog = catalog,
        command =
            TransferCorrectionCommand(
                ledgerId = ledgerId,
                sourceAccountId = source,
                destinationAccountId = destination,
                destinationCredit = money(destinationCreditMinor),
                fee = money(feeMinor),
                feeCategoryId = feeCategoryId,
                statisticsAt = transferStatisticsAt,
            ),
        ids =
            TransferCorrectionPostingIds(
                sourcePostingId = PostingId("p-out"),
                destinationPostingId = PostingId("p-in"),
                feePostingId = feePostingId,
            ),
    )

    @Test
    fun transferCorrectionPlansTheThreeLegShapeWhenTheFeeIsPositive() {
        val postings = assertIs<TransferCorrectionPlan.Postings>(transferPlan()).postings
        // The creation factory's frozen leg order: PRINCIPAL_OUT, PRINCIPAL_IN and the FEE leg
        // on the fee category's posting account, with the derived source debit 10_000 + 200.
        assertEquals(listOf(bankId, otherBankId, expenseAccountId), postings.map { it.accountId })
        assertEquals(listOf(-10_200L, 10_000L, 200L), postings.map { it.amount.minorUnits })
        assertIs<DomainResult.Success<PostingSet>>(
            PostingSet.create(PostingSetId("posting-set-new"), postings),
        )
    }

    @Test
    fun transferCorrectionPlansTheTwoLegShapeWhenTheFeeIsZero() {
        val postings =
            assertIs<TransferCorrectionPlan.Postings>(transferPlan(feeMinor = 0L, feeCategoryId = null)).postings
        // fee == 0: only the two principal legs, no category anywhere (the factory's T-4 rule).
        assertEquals(listOf(bankId, otherBankId), postings.map { it.accountId })
        assertEquals(listOf(-10_000L, 10_000L), postings.map { it.amount.minorUnits })
    }

    @Test
    fun transferCorrectionFieldShapeViolationsAreTypedRejections() {
        // A zero fee must not carry a category; a positive fee requires one; a negative fee and a
        // non-positive destination credit are inadmissible values (spec section 9's reuse ruling).
        assertEquals(P705FailureCode.P705_FIELD_NOT_SUPPORTED, assertIs<TransferCorrectionPlan.Rejected>(transferPlan(feeMinor = 0L, feeCategoryId = categoryId)).code)
        assertEquals(P705FailureCode.P705_FIELD_NOT_SUPPORTED, assertIs<TransferCorrectionPlan.Rejected>(transferPlan(feeMinor = 200L, feeCategoryId = null)).code)
        assertEquals(P705FailureCode.P705_FIELD_NOT_SUPPORTED, assertIs<TransferCorrectionPlan.Rejected>(transferPlan(feeMinor = -1L, feeCategoryId = null)).code)
        assertEquals(P705FailureCode.P705_FIELD_NOT_SUPPORTED, assertIs<TransferCorrectionPlan.Rejected>(transferPlan(destinationCreditMinor = 0L, feeMinor = 0L, feeCategoryId = null)).code)
        // The stored shape carries one currency triple, and the derived debit must be
        // representable: a fee in another currency and an overflowing `credit + fee` sum are
        // field rejections before any admission runs.
        assertIs<TransferCorrectionPlan.Rejected>(
            planTransferCorrectionPostings(
                catalog = catalog(),
                command =
                    TransferCorrectionCommand(
                        ledgerId = ledgerId,
                        sourceAccountId = bankId,
                        destinationAccountId = otherBankId,
                        destinationCredit = money(10_000),
                        fee = Money.ofMinor(200L, CurrencyUnit("USD", 2)),
                        feeCategoryId = categoryId,
                        statisticsAt = transferStatisticsAt,
                    ),
                ids = TransferCorrectionPostingIds(PostingId("p-out"), PostingId("p-in"), PostingId("p-fee")),
            ),
        ).let { assertEquals(P705FailureCode.P705_FIELD_NOT_SUPPORTED, it.code) }
        assertEquals(
            P705FailureCode.P705_FIELD_NOT_SUPPORTED,
            assertIs<TransferCorrectionPlan.Rejected>(transferPlan(destinationCreditMinor = Long.MAX_VALUE, feeMinor = 300L)).code,
        )
        // A positive fee whose id source mints no fee-leg id fails closed on the wiring
        // invariant, never planning a two-legged mis-shape.
        assertEquals(
            P705FailureCode.P705_CONSTRAINT_VIOLATION,
            assertIs<TransferCorrectionPlan.Rejected>(transferPlan(feePostingId = null)).code,
        )
    }

    @Test
    fun transferCorrectionAdmissionFailuresReuseTheFactoryRules() {
        // Same endpoint twice, an unknown endpoint and a non-real endpoint are the creation
        // factories' own admission rejections mapped onto the catalog code (T-04).
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<TransferCorrectionPlan.Rejected>(transferPlan(destination = bankId)).code,
        )
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<TransferCorrectionPlan.Rejected>(transferPlan(source = AccountId("asset-missing"))).code,
        )
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<TransferCorrectionPlan.Rejected>(transferPlan(source = expenseAccountId)).code,
        )
        // A deactivated fee category is the factory's fee-category admission failure (T-05);
        // the transfer planner owns no second copy of that rule.
        val inactive =
            when (
                val result =
                    LedgerCatalog.create(
                        accounts =
                            listOf(
                                Account(bankId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                                Account(otherBankId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                                Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            ),
                        categories =
                            listOf(
                                Category(CategoryId("category-food-parent"), ledgerId, null, null, active = true),
                                Category(categoryId, ledgerId, CategoryId("category-food-parent"), expenseAccountId, active = false),
                            ),
                    )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> error("fixture catalog must be valid")
            }
        assertEquals(
            P705FailureCode.P705_CATALOG_REFERENCE_NOT_ADMISSIBLE,
            assertIs<TransferCorrectionPlan.Rejected>(transferPlan(catalog = inactive)).code,
        )
    }
}
