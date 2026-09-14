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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-03.B unified monthly projection vectors (R-Q06-2/-3/-4, spec sections 3.1.1/3.1.2/4.2.1,
 * acceptance C01/C02/C04): frozen 16-kind classification, per-currency ordinary income,
 * net expense and balance with checked overflow, refund isolation, explicit zero months,
 * category rollup and the SummarizeLedgerActivity consistency anchor. All data synthetic
 * and anonymous with fixed instants; no real time or network.
 */
class QueryMonthlyActivityTest {
    private val ledgerId = LedgerId("ledger-monthly-test")
    private val otherLedgerId = LedgerId("ledger-monthly-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val assetId = AccountId("account-asset")
    private val savingsId = AccountId("account-savings")
    private val receivableId = AccountId("account-receivable")
    private val expenseId = AccountId("account-expense-breakfast")
    private val uncategorizedExpenseId = AccountId("account-expense-uncategorized")
    private val incomeId = AccountId("account-income-salary")
    private val usdAssetId = AccountId("account-usd-asset")
    private val usdExpenseId = AccountId("account-usd-expense")
    private val foodParentId = CategoryId("category-food")
    private val breakfastId = CategoryId("category-breakfast")
    private val incomeParentId = CategoryId("category-income-parent")
    private val salaryId = CategoryId("category-salary")

    @Test
    fun ordinaryClassificationFollowsTheFrozenKindTable() {
        // C02 vector: LEND 100.00, COLLECT principal 40.00 + interest 5.00, transfer
        // principal 60.00 + fee 1.00, expense 30.00, income 100.00, refund -30.00,
        // stored-value recharge paid 1000 + bonus 200, balance adjustment.
        val rows =
            listOf(
                row("tx-lend", TransactionKind.LEND, "2026-03-10T02:00:00Z") {
                    posting("posting-lend-out", assetId, -10_000L)
                    posting("posting-lend-in", receivableId, 10_000L)
                },
                row("tx-collect", TransactionKind.COLLECT, "2026-03-11T02:00:00Z") {
                    posting("posting-collect-principal-in", assetId, 4_000L)
                    posting("posting-collect-principal-out", receivableId, -4_000L)
                    posting("posting-collect-interest", incomeId, -500L)
                },
                row("tx-transfer", TransactionKind.ACCOUNT_TRANSFER, "2026-03-12T02:00:00Z") {
                    posting("posting-transfer-out", assetId, -6_000L)
                    posting("posting-transfer-in", savingsId, 6_000L)
                    posting("posting-transfer-fee", expenseId, 100L)
                },
                row("tx-expense", TransactionKind.EXPENSE, "2026-03-13T02:00:00Z") {
                    posting("posting-expense", expenseId, 3_000L)
                    posting("posting-expense-payment", assetId, -3_000L)
                },
                row("tx-income", TransactionKind.INCOME, "2026-03-14T02:00:00Z") {
                    posting("posting-income-received", assetId, 10_000L)
                    posting("posting-income", incomeId, -10_000L)
                },
                row("tx-refund", TransactionKind.REFUND_RECEIPT, "2026-03-15T02:00:00Z") {
                    posting("posting-refund-expense", expenseId, -3_000L)
                    posting("posting-refund-payment", assetId, 3_000L)
                },
                row("tx-recharge", TransactionKind.STORED_VALUE_RECHARGE, "2026-03-16T02:00:00Z") {
                    posting("posting-recharge-paid", assetId, -100_000L)
                    posting("posting-recharge-lot", savingsId, 100_000L)
                    posting("posting-recharge-bonus", incomeId, -20_000L)
                },
                row("tx-adjustment", TransactionKind.BALANCE_ADJUSTMENT, "2026-03-17T02:00:00Z") {
                    posting("posting-adjustment-up", assetId, 5_000L)
                    posting("posting-adjustment-down", savingsId, -5_000L)
                },
            )
        val result = query(rows, clockAt = "2026-03-20T02:00:00Z").query(YearMonth(2026, 3))
        val activity = assertIs<MonthlyActivityResult.Success>(result).activity

        val cnyRow = activity.currencies.single()
        assertEquals(cny, cnyRow.currency)
        // Interest 5.00 + income 100.00; LEND principal, recharge bonus and adjustments excluded.
        assertEquals(10_500L, cnyRow.ordinaryIncomeMinorUnits)
        // Fee 1.00 + expense 30.00; refund -30.00 keeps its sign in net expense.
        assertEquals(3_100L, cnyRow.positiveExpenseMinorUnits)
        assertEquals(-3_000L, cnyRow.refundMinorUnits)
        assertEquals(100L, cnyRow.netExpenseMinorUnits)
        // Balance = ordinary income - net expense (frozen formula, never an account balance).
        assertEquals(10_400L, cnyRow.balanceMinorUnits)
        assertEquals(8, cnyRow.transactionCount)
        assertEquals(
            mapOf(
                TransactionKind.LEND to 1,
                TransactionKind.COLLECT to 1,
                TransactionKind.ACCOUNT_TRANSFER to 1,
                TransactionKind.EXPENSE to 1,
                TransactionKind.INCOME to 1,
                TransactionKind.REFUND_RECEIPT to 1,
                TransactionKind.STORED_VALUE_RECHARGE to 1,
                TransactionKind.BALANCE_ADJUSTMENT to 1,
            ),
            cnyRow.countByKind,
        )
    }

    @Test
    fun crossMonthRefundCountsInItsOwnStatisticsMonthOnly() {
        val rows =
            listOf(
                row("tx-expense", TransactionKind.EXPENSE, "2026-02-10T02:00:00Z") {
                    posting("posting-expense", expenseId, 3_000L)
                    posting("posting-expense-payment", assetId, -3_000L)
                },
                row("tx-refund", TransactionKind.REFUND_RECEIPT, "2026-03-05T02:00:00Z") {
                    posting("posting-refund-expense", expenseId, -3_000L)
                    posting("posting-refund-payment", assetId, 3_000L)
                },
            )
        val useCase = query(rows, clockAt = "2026-03-20T02:00:00Z")

        val february = assertIs<MonthlyActivityResult.Success>(useCase.query(YearMonth(2026, 2))).activity
        assertEquals(3_000L, february.currencies.single().netExpenseMinorUnits)
        assertEquals(3_000L, february.currencies.single().positiveExpenseMinorUnits)
        assertEquals(0L, february.currencies.single().refundMinorUnits)

        val march = assertIs<MonthlyActivityResult.Success>(useCase.query(YearMonth(2026, 3))).activity
        // The refund lands as a negative expense in the collection month (R-Q06-3; :146).
        assertEquals(-3_000L, march.currencies.single().netExpenseMinorUnits)
        assertEquals(0L, march.currencies.single().positiveExpenseMinorUnits)
        assertEquals(-3_000L, march.currencies.single().refundMinorUnits)
        assertEquals(3_000L, march.currencies.single().balanceMinorUnits)
    }

    @Test
    fun perCurrencyTotalsNeverSumAcrossCurrencies() {
        val rows =
            listOf(
                row("tx-cny-expense", TransactionKind.EXPENSE, "2026-03-01T02:00:00Z") {
                    posting("posting-cny-expense", expenseId, 3_000L)
                    posting("posting-cny-payment", assetId, -3_000L)
                },
                row("tx-cny-income", TransactionKind.INCOME, "2026-03-02T02:00:00Z") {
                    posting("posting-cny-received", assetId, 10_000L)
                    posting("posting-cny-income", incomeId, -10_000L)
                },
                row("tx-usd-expense", TransactionKind.EXPENSE, "2026-03-03T02:00:00Z") {
                    posting("posting-usd-expense", usdExpenseId, 250L, usd)
                    posting("posting-usd-payment", usdAssetId, -250L, usd)
                },
            )
        val activity =
            assertIs<MonthlyActivityResult.Success>(
                query(rows, clockAt = "2026-03-20T02:00:00Z").query(YearMonth(2026, 3)),
            ).activity

        assertEquals(listOf(cny, usd), activity.currencies.map { it.currency })
        assertEquals(10_000L, activity.currencies[0].ordinaryIncomeMinorUnits)
        assertEquals(3_000L, activity.currencies[0].netExpenseMinorUnits)
        assertEquals(250L, activity.currencies[1].netExpenseMinorUnits)
        assertEquals(0L, activity.currencies[1].ordinaryIncomeMinorUnits)
    }

    @Test
    fun overflowFailsClosedAsInvalidState() {
        // Income negation overflow (mirror of the SummarizeLedgerActivity anchor).
        val negateOverflow =
            listOf(
                row("tx-min", TransactionKind.INCOME, "2026-03-01T02:00:00Z") {
                    posting("posting-min", incomeId, Long.MIN_VALUE)
                    posting("posting-min-payment", assetId, Long.MAX_VALUE)
                },
            )
        assertIs<MonthlyActivityResult.InvalidState>(query(negateOverflow, "2026-03-20T02:00:00Z").query(YearMonth(2026, 3)))

        // Balance subtraction overflow: ordinary income at Long.MAX with a negative net
        // expense (refund) makes income - netExpense exceed Long.MAX (R-Q06-4, R-8).
        val balanceOverflow =
            listOf(
                row("tx-max-income", TransactionKind.INCOME, "2026-03-01T02:00:00Z") {
                    // INCOME-account posting negates: Long.MIN+1 -> ordinary income Long.MAX.
                    posting("posting-max-income", incomeId, Long.MIN_VALUE + 1)
                    posting("posting-max-payment", assetId, Long.MAX_VALUE)
                },
                row("tx-small-refund", TransactionKind.REFUND_RECEIPT, "2026-03-02T02:00:00Z") {
                    posting("posting-small-refund", expenseId, -5L)
                    posting("posting-small-refund-payment", assetId, 5L)
                },
            )
        assertIs<MonthlyActivityResult.InvalidState>(query(balanceOverflow, "2026-03-20T02:00:00Z").query(YearMonth(2026, 3)))
    }

    @Test
    fun catalogInconsistencyFailsClosedAsInvalidState() {
        val unknownAccountRows =
            listOf(
                row("tx-unknown", TransactionKind.EXPENSE, "2026-03-01T02:00:00Z") {
                    posting("posting-unknown", AccountId("account-unknown"), 100L)
                    posting("posting-unknown-payment", assetId, -100L)
                },
            )
        assertIs<MonthlyActivityResult.InvalidState>(query(unknownAccountRows, "2026-03-20T02:00:00Z").query(YearMonth(2026, 3)))

        // An account belonging to another ledger is a cross-ledger inconsistency.
        val crossLedgerCatalog = catalogWithCrossLedgerAccount()
        val crossLedgerRows =
            listOf(
                row("tx-cross", TransactionKind.EXPENSE, "2026-03-01T02:00:00Z") {
                    posting("posting-cross", AccountId("account-foreign-ledger"), 100L)
                },
            )
        val crossLedgerResult =
            QueryMonthlyActivity(EntryRowsPort(crossLedgerRows), ledgerId, crossLedgerCatalog, FixedLedgerClock(Instant.parse("2026-03-20T02:00:00Z")))
                .query(YearMonth(2026, 3))
        assertIs<MonthlyActivityResult.InvalidState>(crossLedgerResult)

        // A posting currency that differs from its catalog account currency.
        val currencyMismatchRows =
            listOf(
                row("tx-currency", TransactionKind.EXPENSE, "2026-03-01T02:00:00Z") {
                    posting("posting-currency", expenseId, 100L, usd)
                },
            )
        assertIs<MonthlyActivityResult.InvalidState>(query(currencyMismatchRows, "2026-03-20T02:00:00Z").query(YearMonth(2026, 3)))
    }

    @Test
    fun readPortAndClockFailuresYieldUnavailableNotZeros() {
        val rows =
            listOf(
                row("tx-1", TransactionKind.EXPENSE, "2026-03-01T02:00:00Z") {
                    posting("posting-1", expenseId, 100L)
                },
            )
        assertIs<MonthlyActivityResult.Unavailable>(
            QueryMonthlyActivity(ThrowingEntryPort(), ledgerId, catalog(), FixedLedgerClock(Instant.parse("2026-03-20T02:00:00Z"))).query(YearMonth(2026, 3)),
        )
        // The trend and selectable-month paths resolve "this month" from the clock, so a
        // clock failure fails them closed (section 4.2.3); query(month) takes the month
        // explicitly and never consults the clock.
        assertIs<MonthlyTrendResult.Unavailable>(
            QueryMonthlyActivity(ThrowingEntryPort(), ledgerId, catalog(), FixedLedgerClock(Instant.parse("2026-03-20T02:00:00Z"))).trend(),
        )
        assertIs<MonthlyTrendResult.Unavailable>(
            QueryMonthlyActivity(EntryRowsPort(rows), ledgerId, catalog(), ThrowingLedgerClock()).trend(),
        )
        assertIs<SelectableMonthsResult.Unavailable>(
            QueryMonthlyActivity(EntryRowsPort(rows), ledgerId, catalog(), ThrowingLedgerClock()).selectableMonths(),
        )
    }

    @Test
    fun readPortsWithoutTheEntryRowsOverrideSurfaceTypedFailuresNotAnEmptyLedger() {
        // F5/R-Q06-4: loadLedgerEntryRows has no neutral default, so a read port that does not
        // implement the P7-03 surface must fail loudly instead of being read as an empty ledger
        // (which would render zeros and empty months).
        val port = UnimplementedEntryRowsPort()
        assertFailsWith<UnsupportedOperationException> { port.loadLedgerEntryRows(ledgerId) }
        val useCase = QueryMonthlyActivity(port, ledgerId, catalog(), FixedLedgerClock(Instant.parse("2026-03-20T02:00:00Z")))
        assertIs<MonthlyActivityResult.Unavailable>(useCase.query(YearMonth(2026, 3)))
        assertIs<MonthlyTrendResult.Unavailable>(useCase.trend())
        assertIs<SelectableMonthsResult.Unavailable>(useCase.selectableMonths())
        assertIs<TransactionDetailResult.Unavailable>(QueryTransactionDetail(port, ledgerId, catalog()).query(TransactionId("tx-1")))
        // The flow-list query propagates the read failure to its caller, which maps it to the
        // typed read-failure surface instead of rendering an empty list.
        assertFailsWith<UnsupportedOperationException> { QueryLedgerEntryRows(port, ledgerId).query() }
    }

    @Test
    fun emptyMonthRendersExplicitZerosAndEmptyLedgerYieldsNoCurrencies() {
        val rows =
            listOf(
                row("tx-feb", TransactionKind.EXPENSE, "2026-02-10T02:00:00Z") {
                    posting("posting-feb", expenseId, 3_000L)
                    posting("posting-feb-payment", assetId, -3_000L)
                },
                row("tx-feb-usd", TransactionKind.EXPENSE, "2026-02-11T02:00:00Z") {
                    posting("posting-feb-usd", usdExpenseId, 250L, usd)
                    posting("posting-feb-usd-payment", usdAssetId, -250L, usd)
                },
            )
        val useCase = query(rows, clockAt = "2026-03-20T02:00:00Z")
        val march = assertIs<MonthlyActivityResult.Success>(useCase.query(YearMonth(2026, 3))).activity
        // R-Q07-1: an empty month is an explicit zero month, not silence.
        assertEquals(listOf(cny, usd), march.currencies.map { it.currency })
        assertEquals(0L, march.currencies[0].netExpenseMinorUnits)
        assertEquals(0L, march.currencies[0].ordinaryIncomeMinorUnits)
        assertEquals(0L, march.currencies[0].balanceMinorUnits)
        assertEquals(0, march.currencies[0].transactionCount)
        assertTrue(march.currencies[0].countByKind.isEmpty())
        assertTrue(march.expenseCategories.isEmpty())

        val emptyLedger = assertIs<MonthlyActivityResult.Success>(query(emptyList(), "2026-03-20T02:00:00Z").query(YearMonth(2026, 3))).activity
        assertTrue(emptyLedger.currencies.isEmpty())
    }

    @Test
    fun categoryTotalsRollUpLevel1ToLevel2WithCurrentNamesAndAbsentCategoryStaysAbsent() {
        val rows =
            listOf(
                row("tx-categorized", TransactionKind.EXPENSE, "2026-03-01T02:00:00Z") {
                    posting("posting-categorized", expenseId, 3_000L)
                    posting("posting-categorized-payment", assetId, -3_000L)
                },
                row("tx-uncategorized", TransactionKind.EXPENSE, "2026-03-02T02:00:00Z") {
                    posting("posting-uncategorized", uncategorizedExpenseId, 700L)
                    posting("posting-uncategorized-payment", assetId, -700L)
                },
                row("tx-salary", TransactionKind.INCOME, "2026-03-03T02:00:00Z") {
                    posting("posting-salary-received", assetId, 10_000L)
                    posting("posting-salary", incomeId, -10_000L)
                },
            )
        val activity =
            assertIs<MonthlyActivityResult.Success>(
                query(rows, clockAt = "2026-03-20T02:00:00Z").query(YearMonth(2026, 3)),
            ).activity

        // Expense side: one level-1 node whose total equals the sum of its level-2 children.
        val foodNode = activity.expenseCategories.single()
        assertEquals(foodParentId, foodNode.categoryId)
        assertEquals("餐饮", foodNode.categoryName)
        assertEquals(1, foodNode.children.size)
        val breakfastNode = foodNode.children.single()
        assertEquals(breakfastId, breakfastNode.categoryId)
        assertEquals("早餐（改名后）", breakfastNode.categoryName)
        assertEquals(3_000L, breakfastNode.totals.single().positiveMinorUnits)
        assertEquals(0L, breakfastNode.totals.single().refundMinorUnits)
        assertEquals(3_000L, foodNode.totals.single().positiveMinorUnits)

        // P703SPEC-09: the uncategorized leg stays absent from category totals without any
        // failure, while its amount still contributes to the month's net expense.
        assertEquals(3_700L, activity.currencies.single().netExpenseMinorUnits)
        val expenseNode = activity.expenseCategories.single()
        assertEquals(3_000L, expenseNode.totals.single().positiveMinorUnits)

        // F10: it is disclosed as the explicit 无分类 row instead of being silently dropped, so
        // Σ(category rows) + 无分类 reconciles exactly with the month card's net expense.
        val uncategorized = activity.uncategorizedExpenseTotals.single()
        assertEquals(cny, uncategorized.currency)
        assertEquals(700L, uncategorized.positiveMinorUnits)
        assertEquals(0L, uncategorized.refundMinorUnits)
        val categorizedExpense = expenseNode.totals.sumOf { it.positiveMinorUnits + it.refundMinorUnits }
        assertEquals(3_700L, categorizedExpense + uncategorized.positiveMinorUnits + uncategorized.refundMinorUnits)
        assertTrue(activity.uncategorizedIncomeTotals.isEmpty())

        // Income side mirrors the expense side; the deactivated salary category still counts
        // its history under the current name (R-Q07-2, ACCOUNTING_RULES.md :91/:257).
        val salaryCategory = catalog().categories.first { it.id == salaryId }
        assertEquals(false, salaryCategory.active)
        val incomeNode = activity.incomeCategories.single()
        assertEquals(incomeParentId, incomeNode.categoryId)
        val salaryNode = incomeNode.children.single()
        assertEquals(salaryId, salaryNode.categoryId)
        assertEquals("工资", salaryNode.categoryName)
        assertEquals(10_000L, salaryNode.totals.single().positiveMinorUnits)
        assertEquals(10_000L, activity.currencies.single().ordinaryIncomeMinorUnits)
    }

    @Test
    fun trendSharesTheMonthlyProjectionAndAnchorsToSummarizeLedgerActivity() {
        // "No special kind" ledger (spec section 3.1.2): effective kinds inside
        // O = {EXPENSE, INCOME, ACCOUNT_TRANSFER, REFUND_RECEIPT} across months.
        val rows =
            listOf(
                row("tx-feb-expense", TransactionKind.EXPENSE, "2026-02-10T02:00:00Z") {
                    posting("posting-feb-expense", expenseId, 3_000L)
                    posting("posting-feb-payment", assetId, -3_000L)
                },
                row("tx-mar-transfer", TransactionKind.ACCOUNT_TRANSFER, "2026-03-10T02:00:00Z") {
                    posting("posting-mar-out", assetId, -6_000L)
                    posting("posting-mar-in", savingsId, 6_000L)
                    posting("posting-mar-fee", expenseId, 100L)
                },
                row("tx-mar-income", TransactionKind.INCOME, "2026-03-11T02:00:00Z") {
                    posting("posting-mar-received", assetId, 10_000L)
                    posting("posting-mar-income", incomeId, -10_000L)
                },
                row("tx-apr-refund", TransactionKind.REFUND_RECEIPT, "2026-04-05T02:00:00Z") {
                    posting("posting-apr-refund", expenseId, -1_000L)
                    posting("posting-apr-payment", assetId, 1_000L)
                },
                row("tx-apr-expense", TransactionKind.EXPENSE, "2026-04-06T02:00:00Z") {
                    posting("posting-apr-expense", expenseId, 2_000L)
                    posting("posting-apr-expense-payment", assetId, -2_000L)
                },
                // Outside the twelve-month trend window (2025-05..2026-04): the section 3.1.2
                // anchor below must still count it (F7).
                row("tx-jan-expense", TransactionKind.EXPENSE, "2025-01-20T02:00:00Z") {
                    posting("posting-jan-expense", expenseId, 500L)
                    posting("posting-jan-expense-payment", assetId, -500L)
                },
            )
        val useCase = query(rows, clockAt = "2026-04-15T02:00:00Z")
        val trend = assertIs<MonthlyTrendResult.Success>(useCase.trend()).trend

        // R-Q07-1: twelve months old to new including the current month, empty months explicit.
        assertEquals(YearMonth(2025, 5), trend.months.first().month)
        assertEquals(YearMonth(2026, 4), trend.months.last().month)
        assertEquals(12, trend.months.size)
        val january = trend.months.first { it.month == YearMonth(2026, 1) }
        assertEquals(0L, january.currencies.single().netExpenseMinorUnits)
        assertEquals(0, january.currencies.single().transactionCount)

        // The trend slice equals the monthly card for the same month (unified projection).
        val trendMarch = trend.months.first { it.month == YearMonth(2026, 3) }
        val cardMarch = assertIs<MonthlyActivityResult.Success>(useCase.query(YearMonth(2026, 3))).activity
        assertEquals(cardMarch.currencies, trendMarch.currencies)
        assertEquals(cardMarch.expenseCategories, trendMarch.expenseCategories)

        // Section 3.1.2 anchor: Σ(ALL statistics months) == the full-period
        // SummarizeLedgerActivity totals per currency on a no-special-kind ledger (F7). The
        // twelve-month trend window is NOT the anchor: the January 2025 expense above lies
        // outside 2025-05..2026-04, so summing only the window would silently break the anchor.
        val fullPeriod = SummarizeLedgerActivity(catalog()).summarize(fullPeriodState(rows))
        val fullPeriodTotal = fullPeriod.totalsByCurrency.single()
        val trendExpense = trend.months.sumOf { month -> monthNetExpense(useCase, month.month) }
        assertEquals(4_100L, trendExpense)
        assertEquals(4_600L, fullPeriodTotal.expenseMinorUnits)
        assertTrue(trendExpense != fullPeriodTotal.expenseMinorUnits)

        val domain = assertIs<SelectableMonthsResult.Success>(useCase.selectableMonths()).months
        assertEquals(YearMonth(2025, 1), domain.first())
        assertEquals(YearMonth(2026, 4), domain.last())
        val allMonthsIncome = domain.sumOf { month -> monthOrdinaryIncome(useCase, month) }
        val allMonthsExpense = domain.sumOf { month -> monthNetExpense(useCase, month) }
        assertEquals(10_000L, allMonthsIncome)
        assertEquals(4_600L, allMonthsExpense)
        assertEquals(fullPeriodTotal.incomeMinorUnits, allMonthsIncome)
        assertEquals(fullPeriodTotal.expenseMinorUnits, allMonthsExpense)
    }

    @Test
    fun selectableMonthsSpanFirstStatisticsMonthToCurrentMonth() {
        val rows =
            listOf(
                row("tx-feb", TransactionKind.EXPENSE, "2026-02-10T02:00:00Z") {
                    posting("posting-feb", expenseId, 100L)
                },
                row("tx-nov", TransactionKind.EXPENSE, "2025-11-20T02:00:00Z") {
                    posting("posting-nov", expenseId, 100L)
                },
                row("tx-jun", TransactionKind.EXPENSE, "2026-06-10T02:00:00Z") {
                    posting("posting-jun", expenseId, 100L)
                },
            )
        val useCase = query(rows, clockAt = "2026-04-15T02:00:00Z")
        val months = assertIs<SelectableMonthsResult.Success>(useCase.selectableMonths()).months
        // Frozen domain [first statistics month, current month]; the later-than-current
        // statistics month stays outside (spec section 6.2 residual (c)).
        assertEquals(
            listOf(
                YearMonth(2025, 11),
                YearMonth(2025, 12),
                YearMonth(2026, 1),
                YearMonth(2026, 2),
                YearMonth(2026, 3),
                YearMonth(2026, 4),
            ),
            months,
        )

        val emptyMonths = assertIs<SelectableMonthsResult.Success>(query(emptyList(), "2026-04-15T02:00:00Z").selectableMonths()).months
        assertTrue(emptyMonths.isEmpty())
    }

    // --- fixtures -----------------------------------------------------------------------

    /** The month's single-currency ordinary income (the anchor is per currency, D-120). */
    private fun monthOrdinaryIncome(
        useCase: QueryMonthlyActivity,
        month: YearMonth,
    ): Long {
        val activity = assertIs<MonthlyActivityResult.Success>(useCase.query(month)).activity
        return activity.currencies.single().ordinaryIncomeMinorUnits
    }

    /** The month's single-currency net expense (signed; refunds stay negative). */
    private fun monthNetExpense(
        useCase: QueryMonthlyActivity,
        month: YearMonth,
    ): Long {
        val activity = assertIs<MonthlyActivityResult.Success>(useCase.query(month)).activity
        return activity.currencies.single().netExpenseMinorUnits
    }

    private fun query(
        rows: List<LedgerEntryRow>,
        clockAt: String,
    ): QueryMonthlyActivity =
        QueryMonthlyActivity(
            readPort = EntryRowsPort(rows),
            ledgerId = ledgerId,
            catalog = catalog(),
            clock = FixedLedgerClock(Instant.parse(clockAt)),
        )

    /** Adds a posting to the row under construction (receiver = the row's posting builder). */
    private fun MutableList<Posting>.posting(
        postingId: String,
        accountId: AccountId,
        minorUnits: Long,
        currency: CurrencyUnit = cny,
    ) {
        add(Posting(PostingId(postingId), accountId, Money.ofMinor(minorUnits, currency)))
    }

    private fun row(
        transactionId: String,
        kind: TransactionKind,
        statisticsAt: String,
        postings: MutableList<Posting>.() -> Unit,
    ): LedgerEntryRow {
        val builder = mutableListOf<Posting>()
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

    private fun fullPeriodState(rows: List<LedgerEntryRow>): LedgerCurrentState =
        LedgerCurrentState(
            ledgerId = ledgerId,
            transactions =
                rows.map {
                    CurrentVersionRow(
                        transactionId = it.transactionId,
                        currentVersionId = it.currentVersionId,
                        kind = it.kind,
                        occurredAt = it.occurredAt,
                        postings = it.postings,
                    )
                },
            balances = emptyList(),
        )

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "资产-现金"),
                            Account(savingsId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "资产-储蓄"),
                            Account(receivableId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "资产-应收"),
                            Account(expenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "支出-早餐账户"),
                            Account(uncategorizedExpenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false, name = "支出-未分类账户"),
                            Account(incomeId, ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false, name = "收入-工资账户"),
                            Account(usdAssetId, ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true, name = "资产-USD"),
                            Account(usdExpenseId, ledgerId, AccountKind.EXPENSE, usd, ownedByUser = false, realAccount = false, name = "支出-USD"),
                        ),
                    categories =
                        listOf(
                            Category(foodParentId, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮"),
                            Category(breakfastId, ledgerId, parentId = foodParentId, postingAccountId = expenseId, active = true, name = "早餐（改名后）"),
                            Category(incomeParentId, ledgerId, parentId = null, postingAccountId = null, active = true, name = "收入"),
                            Category(salaryId, ledgerId, parentId = incomeParentId, postingAccountId = incomeId, active = false, name = "工资"),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }

    private fun catalogWithCrossLedgerAccount(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(AccountId("account-foreign-ledger"), otherLedgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories = emptyList(),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }
}

/** Test clock that simulates an unavailable clock (R-Q06-4). */
private class ThrowingLedgerClock : LedgerClock {
    override fun now(): Instant = throw IllegalStateException("clock unavailable")
}

/** Read-port fake that only serves the P7-03 entry-row surface. */
private class EntryRowsPort(
    private val rows: List<LedgerEntryRow>,
) : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = null

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = null

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null

    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> = rows
}

/**
 * F5: a read port that only serves the pre-P7-03 surface and deliberately inherits the
 * [LedgerCurrentStateReadPort.loadLedgerEntryRows] default (no override), proving that an
 * unimplemented P7-03 read surfaces as a typed failure instead of an empty ledger.
 */
private class UnimplementedEntryRowsPort : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = null

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = null

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null
}

private class ThrowingEntryPort : LedgerCurrentStateReadPort {
    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = null

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = null

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null

    override fun loadLedgerEntryRows(ledgerId: LedgerId): List<LedgerEntryRow> = throw IllegalStateException("database unavailable")
}
