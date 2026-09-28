package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.BudgetCalculation
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 budget slice 07.A (measurement matrix) and 07.C (calculation contract) vectors,
 * approved by D-184 / `docs/specs/2026-09-28-p7-07-budget-design.md` sections 2 and 4.
 * Covers the full 16-kind inclusion/exclusion matrix, parent/child and uncategorized
 * handling, refund/limit/boundary/overflow semantics, fail-closed scope validation, and
 * equivalence tests against the frozen [MonthlyBuckets] projection.
 *
 * The `P707-A0x` tags in individual test comments are intent labels only. D-184 section 7
 * records no P707 vector as PASS; these tests exercise this slice, they do not close an
 * acceptance vector. All data synthetic and anonymous with fixed instants.
 */
class BudgetMonthTest {
    private val ledgerId = LedgerId("ledger-budget-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val assetId = AccountId("account-asset")
    private val savingsId = AccountId("account-savings")
    private val receivableId = AccountId("account-receivable")
    private val breakfastExpenseId = AccountId("account-expense-breakfast")
    private val dinnerExpenseId = AccountId("account-expense-dinner")
    private val uncategorizedExpenseId = AccountId("account-expense-uncategorized")
    private val incomeId = AccountId("account-income-salary")
    private val usdExpenseId = AccountId("account-usd-expense")
    private val usdAssetId = AccountId("account-usd-asset")
    private val foodParentId = CategoryId("category-food")
    private val breakfastId = CategoryId("category-breakfast")
    private val dinnerId = CategoryId("category-dinner")
    private val incomeParentId = CategoryId("category-income-parent")
    private val usdExpenseCategoryId = CategoryId("category-usd-expense")
    private val march = YearMonth(2026, 3)

    // ------------------------------------------------------------------ 07.A 16-kind matrix

    @Test
    fun budgetOrdinaryMatrixPinsAllSixteenKindsInclusionAndSign() {
        // Intent label P707-A02 (not a PASS claim): the six ordinary kinds contribute per
        // the frozen account-kind dispatch (fee legs on EXPENSE accounts included, all
        // principal legs removed, refund legs negative); the ten special/RG-10-frozen/prepaid
        // kinds contribute exactly zero even when they carry an EXPENSE-account leg that would
        // otherwise count.
        val expected =
            mapOf(
                TransactionKind.OPENING_BALANCE to 0L,
                TransactionKind.EXPENSE to 12_000L,
                TransactionKind.INCOME to 0L,
                TransactionKind.ACCOUNT_TRANSFER to 2L,
                TransactionKind.CREDIT_REPAYMENT to 0L,
                TransactionKind.REFUND_RECEIPT to -300L,
                TransactionKind.BALANCE_ADJUSTMENT to 0L,
                TransactionKind.BALANCE_ADJUSTMENT_REVERSAL to 0L,
                TransactionKind.STORED_VALUE_RECHARGE to 0L,
                TransactionKind.STORED_VALUE_SPEND to 0L,
                TransactionKind.STORED_VALUE_EXPIRY_LOSS to 0L,
                TransactionKind.STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT to 0L,
                TransactionKind.LEND to 0L,
                TransactionKind.COLLECT to 0L,
                TransactionKind.PREPAID_PURCHASE to 0L,
                TransactionKind.PREPAID_RECOGNITION to 0L,
            )
        // Guard against kind-table drift: every enum entry must be explicitly classified.
        assertEquals(TransactionKind.entries.toSet(), expected.keys)
        for ((kind, contribution) in expected) {
            assertEquals(contribution, totalNetExpenseForSingleRow(kind), "kind $kind contribution")
        }
    }

    @Test
    fun budgetIncomeNeverOffsetsExpenseLimit() {
        // Intent label P707-A02 (not a PASS claim): an ordinary income row contributes zero
        // ordinary net expense (its income leg is not counted against the limit), while an
        // expense row contributes positively.
        val rows =
            listOf(
                rowForKind(TransactionKind.EXPENSE),
                rowForKind(TransactionKind.INCOME),
            )
        val contribution = totalContribution(rows)
        assertEquals(12_000L, contribution.netExpenseByCurrency[cny])
    }

    // ------------------------------------------------------ 07.A parent/child + 无分类

    @Test
    fun parentScopeRollsUpChildrenAndTotalIncludesUncategorized() {
        // Intent label P707-A03 (not a PASS claim): parent scope = its two level-2 children
        // (30 + 40 = 70); a child scope observes only itself (30); TOTAL includes the
        // uncategorized expense (70 + 5 = 75).
        val rows =
            listOf(
                expenseRow("tx-breakfast", breakfastExpenseId, 3_000L),
                expenseRow("tx-dinner", dinnerExpenseId, 4_000L),
                expenseRow("tx-uncategorized", uncategorizedExpenseId, 500L),
            )
        val parent = BudgetScope.Category(foodParentId)
        val child = BudgetScope.Category(breakfastId)

        assertEquals(7_000L, contributionFor(rows, parent).netExpenseByCurrency[cny])
        assertEquals(3_000L, contributionFor(rows, child).netExpenseByCurrency[cny])
        assertEquals(7_500L, contributionFor(rows, BudgetScope.Total).netExpenseByCurrency[cny])
        // Parent and child are independent observations, never summed into 100.
        assertTrue(contributionFor(rows, parent).netExpenseByCurrency[cny] != 3_000L + 7_000L)
    }

    @Test
    fun categoryScopeWithNoExpenseObservesZeroWithoutFailing() {
        // A valid EXPENSE category with no posting this month yields an empty map
        // (observational zero), never a fabricated category row or a failure. This is the
        // genuine-zero case, distinct from the invalid-catalog cases below.
        val rows = listOf(expenseRow("tx-breakfast", breakfastExpenseId, 3_000L))
        val contribution = contributionFor(rows, BudgetScope.Category(dinnerId))
        assertTrue(contribution.netExpenseByCurrency.isEmpty())
        assertEquals(0L, contribution.netExpenseByCurrency[cny] ?: 0L)
    }

    // ------------------------------------------------------------ 07.A fail-loud discipline

    @Test
    fun unknownScopeCategoryFailsLoudInsteadOfObservingZero() {
        // Spec sections 3.1/5.1: a category scope absent from the catalog is an invalid
        // state, never a zero execution amount.
        val rows = listOf(expenseRow("tx-breakfast", breakfastExpenseId, 3_000L))
        assertFailsWith<IllegalStateException> {
            contributionFor(rows, BudgetScope.Category(CategoryId("category-missing")))
        }
    }

    @Test
    fun incomeKindScopeCategoryFailsLoud() {
        // Spec section 3.1: a category scope must be a CategoryKind.EXPENSE category; an
        // INCOME-kind category is an invalid state even though it exists in the catalog.
        val rows = listOf(expenseRow("tx-breakfast", breakfastExpenseId, 3_000L))
        assertFailsWith<IllegalStateException> {
            contributionFor(rows, BudgetScope.Category(incomeParentId))
        }
    }

    @Test
    fun missingPostingAccountFailsLoudInsteadOfContributingZero() {
        // Same fail-closed discipline as MonthlyBuckets.aggregate: an account absent from
        // the catalog is an invalid state, not a silent zero execution amount.
        val rows = listOf(expenseRow("tx-unknown-account", AccountId("account-not-in-catalog"), 3_000L))
        assertFailsWith<IllegalStateException> { totalContribution(rows) }
    }

    // ------------------------------------------------------------------ 07.C calculation

    @Test
    fun remainingAndOverspentFollowTheFrozenContract() {
        // Intent label P707-A01 (not a PASS claim): limit 100.00, expense 120.00, refund
        // 30.00 -> net 90.00, remaining 10.00.
        assertEquals(1_000L, BudgetCalculation.remainingMinorUnits(10_000L, 9_000L))
        assertEquals(0L, BudgetCalculation.overspentMinorUnits(10_000L, 9_000L))
        // Expense 80.00 refund 30.00 -> net 50.00, remaining 50.00.
        assertEquals(5_000L, BudgetCalculation.remainingMinorUnits(10_000L, 5_000L))
        assertEquals(0L, BudgetCalculation.overspentMinorUnits(10_000L, 5_000L))
        // Overspent case: net 120.00 vs limit 100.00.
        assertEquals(-2_000L, BudgetCalculation.remainingMinorUnits(10_000L, 12_000L))
        assertEquals(2_000L, BudgetCalculation.overspentMinorUnits(10_000L, 12_000L))
    }

    @Test
    fun netRefundMakesRemainingExceedLimitWithoutClamping() {
        // Spec section 4: a net refund may make netExpense negative; remaining exceeds the
        // limit and neither amount is clamped to zero.
        assertEquals(13_000L, BudgetCalculation.remainingMinorUnits(10_000L, -3_000L))
        assertEquals(0L, BudgetCalculation.overspentMinorUnits(10_000L, -3_000L))
    }

    @Test
    fun exactlyEqualLimitIsNotOverspent() {
        // Spec section 4 boundary: netExpense == limit -> overspent 0, remaining 0.
        assertEquals(0L, BudgetCalculation.remainingMinorUnits(10_000L, 10_000L))
        assertEquals(0L, BudgetCalculation.overspentMinorUnits(10_000L, 10_000L))
    }

    @Test
    fun checkedArithmeticFailsLoudOnOverflow() {
        // remaining = limit - netExpense overflows when a negative net expense exceeds the
        // remaining headroom.
        assertFailsWith<ArithmeticException> {
            BudgetCalculation.remainingMinorUnits(Long.MAX_VALUE, -1L)
        }
        // overspent = netExpense - limit overflows at the Long minimum.
        assertFailsWith<ArithmeticException> {
            BudgetCalculation.overspentMinorUnits(1L, Long.MIN_VALUE)
        }
    }

    // -------------------------------------------------------- 07.C month result projection

    @Test
    fun zeroLimitIsAMonitoredBudgetAndUnsetLimitIsNotMonitored() {
        // Spec section 3.3: zero is a valid monitored budget; null (unset/closed) is not
        // monitored and must not be confused with zero.
        val zeroLimit =
            assertIs<BudgetMonthResult.Success>(
                BudgetMonthProjection.compute(ledgerId, march, cny, BudgetScope.Total, 0L, 0L),
            ).budgetMonth
        assertEquals(0L, zeroLimit.limitMinorUnits)
        assertEquals(0L, zeroLimit.remainingMinorUnits)
        assertEquals(0L, zeroLimit.overspentMinorUnits)

        val zeroLimitOverspent =
            assertIs<BudgetMonthResult.Success>(
                BudgetMonthProjection.compute(ledgerId, march, cny, BudgetScope.Total, 0L, 5L),
            ).budgetMonth
        assertEquals(-5L, zeroLimitOverspent.remainingMinorUnits)
        assertEquals(5L, zeroLimitOverspent.overspentMinorUnits)

        val unset =
            assertIs<BudgetMonthResult.Success>(
                BudgetMonthProjection.compute(ledgerId, march, cny, BudgetScope.Total, null, 5L),
            ).budgetMonth
        assertNull(unset.limitMinorUnits)
        assertNull(unset.remainingMinorUnits)
        assertNull(unset.overspentMinorUnits)
        assertEquals(5L, unset.netExpenseMinorUnits)
    }

    @Test
    fun budgetMonthProjectionIsInvalidStateOnOverflowNotASilentWrap() {
        val overflow =
            BudgetMonthProjection.compute(
                ledgerId,
                march,
                cny,
                BudgetScope.Total,
                Long.MAX_VALUE,
                -1L,
            )
        assertIs<BudgetMonthResult.InvalidState>(overflow)
    }

    @Test
    fun negativeLimitIsATypedRejection() {
        // Spec section 3.3: limits are non-negative minor units; a negative input is a typed
        // rejection, not a stored/observable budget.
        assertIs<BudgetMonthResult.InvalidState>(
            BudgetMonthProjection.compute(ledgerId, march, cny, BudgetScope.Total, -1L, 0L),
        )
        assertIs<BudgetMonthResult.InvalidState>(
            BudgetMonthProjection.compute(ledgerId, march, cny, BudgetScope.Total, Long.MIN_VALUE, 0L),
        )
    }

    // ------------------------------------------------------------------ 07.A equivalence

    @Test
    fun budgetTotalEqualsTheFrozenMonthlyBucketsOrdinaryNetExpense() {
        // Equivalence with the existing projection (spec section 2.2 hard constraint): the
        // TOTAL scope's net expense must equal MonthlyBuckets' own netExpenseMinorUnits for
        // the same rows, currencies, month and catalog. This compares against the frozen
        // implementation, not a re-implementation of it.
        val rows =
            listOf(
                rowForKind(TransactionKind.EXPENSE),
                rowForKind(TransactionKind.INCOME),
                rowForKind(TransactionKind.ACCOUNT_TRANSFER),
                rowForKind(TransactionKind.REFUND_RECEIPT),
                rowForKind(TransactionKind.LEND),
                rowForKind(TransactionKind.COLLECT),
                rowForKind(TransactionKind.STORED_VALUE_RECHARGE),
                rowForKind(TransactionKind.BALANCE_ADJUSTMENT),
                expenseRow("tx-uncategorized", uncategorizedExpenseId, 500L),
            )
        val frozenActivity = MonthlyBuckets.aggregate(rows, ledgerId, catalog(), listOf(march)).getValue(march)
        val frozen = frozenActivity.currencies.associate { it.currency to it.netExpenseMinorUnits }
        val budget = totalContribution(rows).netExpenseByCurrency
        assertEquals(frozen, budget)
        // A non-trivial vector so the equality is not vacuously 0 == 0. The two excluded-kind
        // rows are present in the frozen aggregate's count, so the zero they contribute is
        // genuine exclusion rather than missing input.
        assertEquals(12_202L, budget[cny])
        assertEquals(9, frozenActivity.currencies.single { it.currency == cny }.transactionCount)
        assertEquals(1, frozenActivity.currencies.single { it.currency == cny }.countByKind[TransactionKind.STORED_VALUE_RECHARGE])
        assertEquals(1, frozenActivity.currencies.single { it.currency == cny }.countByKind[TransactionKind.BALANCE_ADJUSTMENT])
    }

    @Test
    fun budgetIncomeIsExcludedWhileTheFrozenProjectionStillCarriesIt() {
        // The budget observes only the expense side: MonthlyBuckets still reports ordinary
        // income, but the budget net expense ignores it. Pins the 07.A/07.C scope decision.
        val rows = listOf(rowForKind(TransactionKind.INCOME))
        val activity = MonthlyBuckets.aggregate(rows, ledgerId, catalog(), listOf(march)).getValue(march)
        assertEquals(10_000L, activity.currencies.single { it.currency == cny }.ordinaryIncomeMinorUnits)
        assertEquals(0L, totalContribution(rows).netExpenseByCurrency[cny])
    }

    @Test
    fun budgetCategoryScopeEqualsTheFrozenMonthlyBucketsCategoryNode() {
        // Equivalence for a category scope: the budget contribution must equal the signed net
        // of the real MonthlyBuckets category node (positive + refund), not a hard-coded
        // number. Compares against the frozen implementation, not a re-implementation.
        val rows =
            listOf(
                expenseRow("tx-breakfast", breakfastExpenseId, 3_000L),
                expenseRow("tx-dinner", dinnerExpenseId, 4_000L),
                row("tx-refund", TransactionKind.REFUND_RECEIPT, "2026-03-11T02:00:00Z") {
                    posting("posting-refund-expense", breakfastExpenseId, -1_000L)
                    posting("posting-refund-payment", assetId, 1_000L)
                },
            )
        val frozenActivity = MonthlyBuckets.aggregate(rows, ledgerId, catalog(), listOf(march)).getValue(march)
        val frozenParent = frozenCategoryNet(frozenActivity, foodParentId)
        val frozenChild = frozenCategoryNet(frozenActivity, breakfastId)

        val parent = contributionFor(rows, BudgetScope.Category(foodParentId)).netExpenseByCurrency
        val child = contributionFor(rows, BudgetScope.Category(breakfastId)).netExpenseByCurrency
        assertEquals(frozenParent, parent)
        assertEquals(frozenChild, child)
        // Non-trivial vectors: parent = 30 - 10 + 40 = 60; child = 30 - 10 = 20.
        assertEquals(6_000L, parent[cny])
        assertEquals(2_000L, child[cny])
    }

    @Test
    fun budgetCategoryScopeIsPerCurrencyWithNoCrossCurrencySummation() {
        // Multi-currency: the USD expense category contributes only USD (no CNY total), and
        // the value equals the frozen MonthlyBuckets node for that category.
        val rows =
            listOf(
                expenseRow("tx-breakfast", breakfastExpenseId, 3_000L),
                row("tx-usd-expense", TransactionKind.EXPENSE, "2026-03-12T02:00:00Z") {
                    posting("posting-usd-expense", usdExpenseId, 700L, usd)
                    posting("posting-usd-payment", usdAssetId, -700L, usd)
                },
            )
        val frozenActivity = MonthlyBuckets.aggregate(rows, ledgerId, catalog(), listOf(march)).getValue(march)
        val frozenUsd = frozenCategoryNet(frozenActivity, usdExpenseCategoryId)
        val usdContribution = contributionFor(rows, BudgetScope.Category(usdExpenseCategoryId)).netExpenseByCurrency

        assertEquals(frozenUsd, usdContribution)
        assertEquals(700L, usdContribution[usd])
        assertNull(usdContribution[cny])
        // TOTAL sees both currencies, still per-currency and never summed across them.
        val total = totalContribution(rows).netExpenseByCurrency
        assertEquals(3_000L, total[cny])
        assertEquals(700L, total[usd])
    }

    // ------------------------------------------------------------------------------ helpers

    private fun totalContribution(rows: List<LedgerEntryRow>): BudgetMonthContribution = contributionFor(rows, BudgetScope.Total)

    private fun contributionFor(
        rows: List<LedgerEntryRow>,
        scope: BudgetScope,
    ): BudgetMonthContribution =
        BudgetOrdinaryNetExpense
            .contributions(rows, ledgerId, catalog(), listOf(march), scope)
            .getValue(march)

    private fun totalNetExpenseForSingleRow(kind: TransactionKind): Long {
        val row = rowForKind(kind)
        return totalContribution(listOf(row)).netExpenseByCurrency[cny] ?: 0L
    }

    /**
     * Signed net per currency of a frozen MonthlyBuckets category node (positive + refund),
     * searched recursively so a child node can be compared directly. This reads the real
     * frozen output rather than a re-implementation.
     */
    private fun frozenCategoryNet(
        activity: MonthlyActivity,
        categoryId: CategoryId,
    ): Map<CurrencyUnit, Long> {
        val node = findNode(activity.expenseCategories, categoryId) ?: return emptyMap()
        return node.totals.associate { it.currency to (it.positiveMinorUnits + it.refundMinorUnits) }
    }

    private fun findNode(
        nodes: List<MonthlyCategoryTotal>,
        id: CategoryId,
    ): MonthlyCategoryTotal? {
        for (node in nodes) {
            if (node.categoryId == id) return node
            findNode(node.children, id)?.let { return it }
        }
        return null
    }

    private fun expenseRow(
        transactionId: String,
        expenseAccountId: AccountId,
        amountMinor: Long,
    ) = row(transactionId, TransactionKind.EXPENSE, "2026-03-10T02:00:00Z") {
        posting("posting-$transactionId-expense", expenseAccountId, amountMinor)
        posting("posting-$transactionId-payment", assetId, -amountMinor)
    }

    /**
     * One synthetic row per kind. Ordinary kinds carry the legs that exercise the frozen
     * account-kind dispatch; excluded kinds deliberately carry a positive EXPENSE-account
     * leg that WOULD contribute if the kind gate were dropped.
     */
    private fun rowForKind(kind: TransactionKind): LedgerEntryRow =
        when (kind) {
            TransactionKind.EXPENSE ->
                row("tx-expense", kind, "2026-03-10T02:00:00Z") {
                    posting("posting-expense", breakfastExpenseId, 12_000L)
                    posting("posting-expense-payment", assetId, -12_000L)
                }

            TransactionKind.INCOME ->
                row("tx-income", kind, "2026-03-10T02:00:00Z") {
                    posting("posting-income-received", assetId, 10_000L)
                    posting("posting-income", incomeId, -10_000L)
                }

            TransactionKind.ACCOUNT_TRANSFER ->
                row("tx-transfer", kind, "2026-03-10T02:00:00Z") {
                    posting("posting-transfer-out", assetId, -1_000L)
                    posting("posting-transfer-in", savingsId, 1_000L)
                    posting("posting-transfer-fee", breakfastExpenseId, 2L)
                }

            TransactionKind.REFUND_RECEIPT ->
                row("tx-refund", kind, "2026-03-10T02:00:00Z") {
                    posting("posting-refund-expense", breakfastExpenseId, -300L)
                    posting("posting-refund-payment", assetId, 300L)
                }

            TransactionKind.LEND ->
                row("tx-lend", kind, "2026-03-10T02:00:00Z") {
                    posting("posting-lend-out", assetId, -10_000L)
                    posting("posting-lend-in", receivableId, 10_000L)
                }

            TransactionKind.COLLECT ->
                row("tx-collect", kind, "2026-03-10T02:00:00Z") {
                    posting("posting-collect-principal-in", assetId, 4_000L)
                    posting("posting-collect-principal-out", receivableId, -4_000L)
                    posting("posting-collect-interest", incomeId, -500L)
                }

            // Ten excluded kinds: each carries an EXPENSE-account leg that must be ignored.
            else ->
                row("tx-excluded-${kind.name.lowercase()}", kind, "2026-03-10T02:00:00Z") {
                    posting("posting-excluded-expense", breakfastExpenseId, 500L)
                    posting("posting-excluded-payment", assetId, -500L)
                }
        }

    private fun row(
        transactionId: String,
        kind: TransactionKind,
        statisticsAt: String,
        postings: PostingBuilder.() -> Unit,
    ): LedgerEntryRow {
        val builder = PostingBuilder()
        builder.postings()
        return LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = kind,
            occurredAt = Instant.parse(statisticsAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = null,
            postings = builder.toList(),
        )
    }

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(savingsId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(receivableId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(breakfastExpenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(dinnerExpenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(uncategorizedExpenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(incomeId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                            Account(usdExpenseId, ledgerId, AccountKind.EXPENSE, usd, ownedByUser = false, realAccount = false),
                            Account(usdAssetId, ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true),
                        ),
                    categories =
                        listOf(
                            Category(foodParentId, ledgerId, parentId = null, postingAccountId = null, active = true),
                            Category(breakfastId, ledgerId, parentId = foodParentId, postingAccountId = breakfastExpenseId, active = true),
                            Category(dinnerId, ledgerId, parentId = foodParentId, postingAccountId = dinnerExpenseId, active = true),
                            Category(incomeParentId, ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                            Category(usdExpenseCategoryId, ledgerId, parentId = null, postingAccountId = usdExpenseId, active = true),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }

    private class PostingBuilder {
        private val postings = mutableListOf<Posting>()

        fun posting(
            id: String,
            accountId: AccountId,
            amountMinor: Long,
            currency: CurrencyUnit = CurrencyUnit("CNY", 2),
        ) {
            postings.add(Posting(PostingId(id), accountId, Money.ofMinor(amountMinor, currency)))
        }

        fun toList(): List<Posting> = postings.toList()
    }
}
