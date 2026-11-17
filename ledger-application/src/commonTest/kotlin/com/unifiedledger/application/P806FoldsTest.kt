package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-06 batch a (D-343; spec sections 3.2.3/3.4/3.10, vectors V-2/V-5/V-9/V-19): the pure folds
 * — ordinary gate, category roll-up, per-currency balance, calendar zero rows and zero-day
 * global counting. No database.
 */
class P806FoldsTest {
    private val ledgerId = LedgerId("ledger-p806-folds")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val cashId = AccountId("asset-cash")
    private val foodParentId = CategoryId("cat-food")
    private val breakfastId = CategoryId("cat-breakfast")
    private val salaryParentId = CategoryId("cat-salary")
    private val salaryId = CategoryId("cat-salary-leaf")
    private val expenseAccountId = AccountId("expense-breakfast")
    private val incomeAccountId = AccountId("income-salary")
    private val unmappedExpenseAccountId = AccountId("expense-unmapped")
    private val usdExpenseAccountId = AccountId("expense-usd")
    private val inactiveExpenseAccountId = AccountId("expense-inactive")
    private val inactiveLeafId = CategoryId("cat-inactive-leaf")
    private val emptyExpenseAccountId = AccountId("expense-empty")
    private val emptyLeafId = CategoryId("cat-empty-leaf")

    private val catalog: LedgerCatalog = catalog()

    @Test
    fun ordinaryGateFoldsExpenseIncomeAndBalancePerCurrency() {
        val snapshot =
            snapshot(
                window = window(30),
                totals =
                    listOf(
                        total(expenseAccountId, cny, 300L, -100L),

                        total(incomeAccountId, cny, 0L, -500L),
                    ),
            )
        val view = P806Folds.report(snapshot, ledgerId, catalog, periodOf(30))
        val cnyView = view.currencies.first { it.currency == cny }
        assertEquals(300L, cnyView.positiveExpenseMinorUnits)
        assertEquals(-100L, cnyView.refundMinorUnits)
        assertEquals(200L, cnyView.netExpenseMinorUnits)
        assertEquals(500L, cnyView.ordinaryIncomeMinorUnits)
        assertEquals(300L, cnyView.balanceMinorUnits)
        // 200 minor units over 30 natural days: 200 / 30 = 6.67 -> nearest is 7.
        assertEquals(7L, cnyView.dailyAverageMinorUnits)
    }

    @Test
    fun assetAndLiabilityPostingsContributeNothing() {
        val snapshot =
            snapshot(
                window = window(30),
                totals = listOf(total(cashId, cny, -400L), total(expenseAccountId, cny, 400L)),
            )
        val view = P806Folds.report(snapshot, ledgerId, catalog, periodOf(30))
        val cnyView = view.currencies.first { it.currency == cny }
        assertEquals(400L, cnyView.netExpenseMinorUnits)
        assertEquals(0L, cnyView.ordinaryIncomeMinorUnits)
        assertEquals(-400L, cnyView.balanceMinorUnits)
    }

    @Test
    fun levelOneCategoryEqualsTheSumOfItsChildrenAndUnmappedPostingsStayExplicit() {
        val snapshot =
            snapshot(
                window = window(2),
                totals =
                    listOf(
                        total(expenseAccountId, cny, 300L, -50L),
                        total(unmappedExpenseAccountId, cny, 70L),
                    ),
            )
        val view = P806Folds.report(snapshot, ledgerId, catalog, periodOf(2))
        val parent = view.expenseCategories.first { it.categoryId == foodParentId }
        val child = parent.children.first { it.categoryId == breakfastId }
        assertEquals(300L, child.totals.first().positiveMinorUnits)
        assertEquals(-50L, child.totals.first().refundMinorUnits)
        assertEquals(300L, parent.totals.first().positiveMinorUnits)
        assertEquals(-50L, parent.totals.first().refundMinorUnits)
        assertEquals(70L, view.uncategorizedExpenseTotals.first().positiveMinorUnits)
        // The category region always reconciles with the card: children sum plus 无分类 equals net expense.
        assertEquals(320L, view.currencies.first().netExpenseMinorUnits)
    }

    @Test
    fun emptyAllHasNoPeriodAndNoDailyAverage() {
        val snapshot = snapshot(window = null, totals = emptyList())
        val view = P806Folds.report(snapshot, ledgerId, catalog, period = null)
        assertTrue(view.dailyAverageNotApplicable)
        assertNull(view.currencies.first().dailyAverageMinorUnits)
    }

    @Test
    fun calendarZeroDayCarriesOneZeroRowPerAxisCurrency() {
        val zeroDay =
            P806CalendarDaySnapshot(
                date = LocalDate(2026, 11, 5),
                window = P806Window(1L, 2L),
                postingTotals = emptyList(),
                transactionCount = 0,
            )
        val snapshot =
            P806CalendarSnapshot(
                catalogVersion = 7L,
                month = YearMonth(2026, Month.NOVEMBER),
                currencyAxis = listOf(cny, usd),
                days = listOf(zeroDay),
            )
        val view = P806Folds.calendar(snapshot, ledgerId, catalog)
        val day = view.days.single()
        assertEquals(2, day.currencies.size)
        assertTrue(day.currencies.all { it.ordinaryIncomeMinorUnits == 0L && it.netExpenseMinorUnits == 0L })
        assertEquals(0, day.transactionCount)
    }

    @Test
    fun calendarDayCountIsTheGlobalNumberAndNotPerCurrency() {
        val day =
            P806CalendarDaySnapshot(
                date = LocalDate(2026, 11, 6),
                window = P806Window(1L, 2L),
                postingTotals =
                    listOf(
                        total(expenseAccountId, cny, 100L),
                        total(usdExpenseAccountId, usd, 50L),
                    ),
                transactionCount = 2,
            )
        val view =
            P806Folds.calendar(
                P806CalendarSnapshot(1L, YearMonth(2026, Month.NOVEMBER), listOf(cny, usd), listOf(day)),
                ledgerId,
                catalog,
            )
        val single = view.days.single()
        assertEquals(2, single.currencies.size)
        assertEquals(100L, single.currencies.first { it.currency == cny }.netExpenseMinorUnits)
        assertEquals(50L, single.currencies.first { it.currency == usd }.netExpenseMinorUnits)
        assertEquals(2, single.transactionCount)
    }

    @Test
    fun aPostingAccountOutsideTheCatalogIsAnInvalidState() {
        val snapshot = snapshot(window = window(30), totals = listOf(total(AccountId("absent"), cny, 1L)))
        assertFailsWith<IllegalStateException> { P806Folds.report(snapshot, ledgerId, catalog, periodOf(30)) }
    }

    @Test
    fun theSameGroupKeepsGrossAndRefundApartInsteadOfSplittingTheNet() {
        // +100 and -30 inside ONE (account, currency) group: the fold must read the gross and refund
        // columns, not the net, so the category shows 100 / -30 (D-343 3.4（同组正/负/净三列）).
        val snapshot = snapshot(window = window(30), totals = listOf(total(expenseAccountId, cny, 100L, -30L)))
        val view = P806Folds.report(snapshot, ledgerId, catalog, periodOf(30))
        val category = view.expenseCategories.first { it.categoryId == foodParentId }.children.first { it.categoryId == breakfastId }
        assertEquals(100L, category.totals.first().positiveMinorUnits)
        assertEquals(-30L, category.totals.first().refundMinorUnits)
        assertEquals(70L, view.currencies.first().netExpenseMinorUnits)
    }

    @Test
    fun aNetZeroCategoryWithRealGrossActivityStaysInTheRanking() {
        val snapshot = snapshot(window = window(30), totals = listOf(total(expenseAccountId, cny, 100L, -100L)))
        val view = P806Folds.report(snapshot, ledgerId, catalog, periodOf(30))
        val parent = view.expenseCategories.first { it.categoryId == foodParentId }
        val child = parent.children.first { it.categoryId == breakfastId }
        assertEquals(100L, child.totals.first().positiveMinorUnits)
        assertEquals(-100L, child.totals.first().refundMinorUnits)
        assertEquals(0L, view.currencies.first().netExpenseMinorUnits)
    }

    @Test
    fun categoriesWithoutHistoryAreAbsentAndDeactivatedOnesKeepTheirHistory() {
        val snapshot =
            snapshot(
                window = window(30),
                totals = listOf(total(expenseAccountId, cny, 10L), total(inactiveExpenseAccountId, cny, 20L)),
            )
        val view = P806Folds.report(snapshot, ledgerId, catalog, periodOf(30))
        val parent = view.expenseCategories.first { it.categoryId == foodParentId }
        val childIds = parent.children.map { it.categoryId }
        assertTrue(breakfastId in childIds)
        // Deactivated but with history: still ranked.
        assertTrue(inactiveLeafId in childIds)
        // Active but never used: absent.
        assertTrue(emptyLeafId !in childIds)
        assertEquals(30L, view.currencies.first().netExpenseMinorUnits)
    }
    @Test
    fun theViewCarriesTheSnapshotPerCurrencyCountAndAZeroForAnUnusedAxisCurrency() {
        val snapshot =
            P806ReportSnapshot(
                catalogVersion = 1L,
                window = window(30),
                span = null,
                currencyAxis = listOf(cny, usd),
                postingTotals = listOf(total(expenseAccountId, cny, 100L)),
                transactionCount = 2,
                currencyCounts = listOf(P806CurrencyTransactionCount(cny, 2)),
                futureTransactionCount = 0,
                firstPage = P806TransactionPage(emptyList(), null),
                detailCount = 0,
            )
        val view = P806Folds.report(snapshot, ledgerId, catalog, periodOf(30))
        assertEquals(2, view.currencies.first { it.currency == cny }.transactionCount)
        // The axis currency without any window contribution is a real zero, never inherited.
        assertEquals(0, view.currencies.first { it.currency == usd }.transactionCount)
        assertEquals(2, view.transactionCount)
    }
    // ---------------------------------------------------------------- fixtures

    private fun snapshot(
        window: P806Window?,
        totals: List<P806AccountCurrencyTotal>,
    ): P806ReportSnapshot =
        P806ReportSnapshot(
            catalogVersion = 3L,
            window = window,
            span = null,
            currencyAxis = listOf(cny, usd),
            postingTotals = totals,
            transactionCount = totals.size,
            currencyCounts = emptyList(),
            futureTransactionCount = 0,
            firstPage = P806TransactionPage(emptyList(), null),
            detailCount = 0,
        )

    /** One SQL group row shape: gross positive, gross refund and their exact net (D-343 3.4（同组正/负/净三列）). */
    private fun total(
        accountId: AccountId,
        currency: CurrencyUnit,
        positiveMinorUnits: Long,
        refundMinorUnits: Long = 0L,
    ): P806AccountCurrencyTotal =
        P806AccountCurrencyTotal(
            accountId = accountId,
            currency = currency,
            positiveMinorUnits = positiveMinorUnits,
            refundMinorUnits = refundMinorUnits,
            totalMinorUnits = positiveMinorUnits + refundMinorUnits,
        )

    private fun periodOf(days: Int): P806ResolvedPeriod =
        P806ResolvedPeriod(
            window = window(days),
            startDate = LocalDate(2026, 11, 1),
            endDateInclusive = LocalDate(2026, 11, 1),
            dayCount = days,
        )

    private fun window(days: Int): P806Window = P806Window(1_000L, 1_000L + days.toLong() * 2_000L)

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(cashId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "现金"),
                            Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "早餐账户"),
                            Account(unmappedExpenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "未映射支出"),
                            Account(incomeAccountId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false, name = "工资账户"),
                            Account(usdExpenseAccountId, ledgerId, AccountKind.EXPENSE, usd, ownedByUser = false, realAccount = false, name = "USD 支出"),
                            Account(inactiveExpenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "停用支出"),
                            Account(emptyExpenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "空支出"),
                        ),
                    categories =
                        listOf(
                            Category(foodParentId, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮"),
                            Category(breakfastId, ledgerId, parentId = foodParentId, postingAccountId = expenseAccountId, active = true, name = "早餐"),
                            Category(salaryParentId, ledgerId, parentId = null, postingAccountId = null, active = true, name = "收入"),
                            Category(salaryId, ledgerId, parentId = salaryParentId, postingAccountId = incomeAccountId, active = true, name = "工资"),
                            Category(inactiveLeafId, ledgerId, parentId = foodParentId, postingAccountId = inactiveExpenseAccountId, active = false, name = "停用子类"),
                            Category(emptyLeafId, ledgerId, parentId = foodParentId, postingAccountId = emptyExpenseAccountId, active = true, name = "空子类"),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("fixture catalog must be valid")
        }
}
