package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId

/**
 * P8-06 batch a (D-343; spec sections 3.2.3/3.4/3.10): the pure folds that turn the raw snapshot
 * payload into the report and calendar views.
 *
 * The SQL side groups the ledger's own facts by (account, currency) and returns the gross positive
 * sum, the gross refund sum and the net sum of that same group (D-343 3.4（同组正/负/净三列）); this fold never
 * derives the split from a single net value, so `+100` and `-30` stay `positive 100 / refund -30 /
 * net 70`. Every ordinary-income/expense decision lives here at the single frozen definition
 * point: EXPENSE-account legs are ordinary expense with the ledger sign, INCOME-account legs are
 * ordinary income with the sign negated, every other account kind contributes nothing.
 *
 * Category ranking shows ONLY categories with a real contribution (D-343 3.2.2（发生时间数值键）0): a level-1 row
 * appears when at least one child carries a gross amount, and a net-zero category with non-zero
 * gross still appears. Deactivated categories keep their history; active categories without
 * history never appear.
 *
 * All additions are checked; an overflow throws and the use-case boundary maps it to
 * `P806SnapshotFailure.InvalidState`.
 */
object P806Folds {
    /** Folds the report payload for [period] (which is `null` when the ledger has no period). */
    fun report(
        snapshot: P806ReportSnapshot,
        ledgerId: LedgerId,
        catalog: LedgerCatalog,
        period: P806ResolvedPeriod?,
    ): P806ReportView {
        val accumulator = Accumulator(ledgerId, catalog)
        snapshot.postingTotals.forEach(accumulator::add)
        val countsByCurrency = snapshot.currencyCounts.associate { it.currency to it.transactionCount }
        val currencies =
            snapshot.currencyAxis.map { currency ->
                val income = accumulator.income[currency] ?: 0L
                val positive = accumulator.positiveExpense[currency] ?: 0L
                val refund = accumulator.refundExpense[currency] ?: 0L
                val netExpense = checkedAdd(positive, refund)
                P806CurrencyView(
                    currency = currency,
                    ordinaryIncomeMinorUnits = income,
                    netExpenseMinorUnits = netExpense,
                    balanceMinorUnits = checkedSubtract(income, netExpense),
                    positiveExpenseMinorUnits = positive,
                    refundMinorUnits = refund,
                    dailyAverageMinorUnits =
                        P806Periods.dailyAverageMinorUnits(netExpense, period?.dayCount ?: 0),
                    // Straight from the snapshot's per-currency DISTINCT count; never recomputed here
                    // and never the global count (one transaction can contribute to two currencies).
                    transactionCount = countsByCurrency[currency] ?: 0,
                )
            }
        return P806ReportView(
            ledgerId = ledgerId,
            period = period,
            currencies = currencies,
            expenseCategories = accumulator.categoryViews(CategoryKind.EXPENSE),
            incomeCategories = accumulator.categoryViews(CategoryKind.INCOME),
            uncategorizedExpenseTotals = accumulator.uncategorizedExpense.toCurrencyTotals(),
            uncategorizedIncomeTotals = accumulator.uncategorizedIncome.toCurrencyTotals(),
            transactionCount = snapshot.transactionCount,
            futureTransactionCount = snapshot.futureTransactionCount,
            detailPage = snapshot.firstPage,
            detailCount = snapshot.detailCount,
        )
    }

    /** Folds the calendar payload into one day view per local day, including zero days. */
    fun calendar(
        snapshot: P806CalendarSnapshot,
        ledgerId: LedgerId,
        catalog: LedgerCatalog,
    ): P806CalendarView {
        val days =
            snapshot.days.map { day ->
                val accumulator = Accumulator(ledgerId, catalog)
                day.postingTotals.forEach(accumulator::add)
                P806CalendarDayView(
                    date = day.date,
                    currencies =
                        snapshot.currencyAxis.map { currency ->
                            val positive = accumulator.positiveExpense[currency] ?: 0L
                            val refund = accumulator.refundExpense[currency] ?: 0L
                            P806CalendarCurrencyView(
                                currency = currency,
                                ordinaryIncomeMinorUnits = accumulator.income[currency] ?: 0L,
                                netExpenseMinorUnits = checkedAdd(positive, refund),
                            )
                        },
                    transactionCount = day.transactionCount,
                )
            }
        return P806CalendarView(
            ledgerId = ledgerId,
            month = snapshot.month,
            axis = snapshot.currencyAxis,
            days = days,
        )
    }

    private class Accumulator(
        private val ledgerId: LedgerId,
        catalog: LedgerCatalog,
    ) {
        private val accountsById = catalog.accounts.associateBy { it.id }
        private val leafByPostingAccount: Map<AccountId, Category> =
            catalog.categories
                .filter { it.postingAccountId != null }
                .sortedBy { it.id.value }
                .associateBy { requireNotNull(it.postingAccountId) }
        private val categoriesById = catalog.categories.associateBy { it.id }

        val income = mutableMapOf<CurrencyUnit, Long>()
        val positiveExpense = mutableMapOf<CurrencyUnit, Long>()
        val refundExpense = mutableMapOf<CurrencyUnit, Long>()
        val expenseLeafTotals = mutableMapOf<CategoryId, Totals>()
        val incomeLeafTotals = mutableMapOf<CategoryId, Totals>()
        val uncategorizedExpense = Totals()
        val uncategorizedIncome = Totals()

        fun add(total: P806AccountCurrencyTotal) {
            val account = accountsById[total.accountId] ?: throw IllegalStateException("posting account is not in the catalog")
            if (account.ledgerId != ledgerId) throw IllegalStateException("posting account belongs to another ledger")
            val currency = total.currency
            when (account.kind) {
                AccountKind.EXPENSE -> {
                    addTo(positiveExpense, currency, total.positiveMinorUnits)
                    addTo(refundExpense, currency, total.refundMinorUnits)
                    val leaf = leafByPostingAccount[account.id]
                    val target = if (leaf != null) expenseLeafTotals.getOrPut(leaf.id) { Totals() } else uncategorizedExpense
                    target.add(currency, total.positiveMinorUnits, total.refundMinorUnits)
                }
                AccountKind.INCOME -> {
                    val incomePart = negate(total.totalMinorUnits)
                    addTo(income, currency, incomePart)
                    val leaf = leafByPostingAccount[account.id]
                    val target = if (leaf != null) incomeLeafTotals.getOrPut(leaf.id) { Totals() } else uncategorizedIncome
                    // Income legs are posted negative: the display-positive part is the negated
                    // refund column, and the display-negative part is the negated gross column.
                    target.add(currency, negate(total.refundMinorUnits), negate(total.positiveMinorUnits))
                }
                AccountKind.ASSET,
                AccountKind.LIABILITY,
                AccountKind.EQUITY,
                -> Unit
            }
        }

        fun categoryViews(kind: CategoryKind): List<P806CategoryView> {
            val leafTotals = if (kind == CategoryKind.EXPENSE) expenseLeafTotals else incomeLeafTotals
            val levelOnes =
                categoriesById.values
                    .filter { it.kind == kind && it.parentId == null }
                    .sortedBy { it.id.value }
            return levelOnes.mapNotNull { levelOne ->
                val children =
                    categoriesById.values
                        .filter { it.parentId == levelOne.id }
                        .sortedBy { it.id.value }
                        .mapNotNull { child ->
                            val totals = leafTotals[child.id] ?: return@mapNotNull null
                            val currencyTotals = totals.toCurrencyTotals()
                            if (currencyTotals.isEmpty()) return@mapNotNull null
                            P806CategoryView(
                                categoryId = child.id,
                                categoryName = child.name,
                                totals = currencyTotals,
                                children = emptyList(),
                            )
                        }
                if (children.isEmpty()) return@mapNotNull null
                P806CategoryView(
                    categoryId = levelOne.id,
                    categoryName = levelOne.name,
                    totals = sumTotals(children.map { it.totals }),
                    children = children,
                )
            }
        }
    }

    private class Totals {
        val positive = mutableMapOf<CurrencyUnit, Long>()
        val refund = mutableMapOf<CurrencyUnit, Long>()

        fun add(
            currency: CurrencyUnit,
            positiveAmount: Long,
            refundAmount: Long,
        ) {
            addTo(positive, currency, positiveAmount)
            addTo(refund, currency, refundAmount)
        }

        fun toCurrencyTotals(): List<P806CategoryCurrencyTotal> {
            val currencies = (positive.keys + refund.keys).distinct().sortedWith(compareBy({ it.code }, { it.precision }))
            return currencies
                .map { currency ->
                    P806CategoryCurrencyTotal(
                        currency = currency,
                        positiveMinorUnits = positive[currency] ?: 0L,
                        refundMinorUnits = refund[currency] ?: 0L,
                    )
                }.filter { it.positiveMinorUnits != 0L || it.refundMinorUnits != 0L }
        }
    }

    private fun sumTotals(parts: List<List<P806CategoryCurrencyTotal>>): List<P806CategoryCurrencyTotal> {
        val merged = linkedMapOf<CurrencyUnit, Pair<Long, Long>>()
        for (part in parts) {
            for (total in part) {
                val existing = merged[total.currency] ?: (0L to 0L)
                merged[total.currency] =
                    checkedAdd(existing.first, total.positiveMinorUnits) to
                    checkedAdd(existing.second, total.refundMinorUnits)
            }
        }
        return merged.entries
            .sortedWith(compareBy({ it.key.code }, { it.key.precision }))
            .map { (currency, sums) ->
                P806CategoryCurrencyTotal(
                    currency = currency,
                    positiveMinorUnits = sums.first,
                    refundMinorUnits = sums.second,
                )
            }.filter { it.positiveMinorUnits != 0L || it.refundMinorUnits != 0L }
    }

    private fun negate(value: Long): Long {
        if (value == Long.MIN_VALUE) throw ArithmeticException("P806 sign overflow")
        return -value
    }

    private fun addTo(
        target: MutableMap<CurrencyUnit, Long>,
        currency: CurrencyUnit,
        amount: Long,
    ) {
        if (amount == 0L) return
        target[currency] = checkedAdd(target[currency] ?: 0L, amount)
    }

    private fun checkedAdd(
        left: Long,
        right: Long,
    ): Long {
        val sum = left + right
        if (((left xor sum) and (right xor sum)) < 0L) throw ArithmeticException("P806 amount overflow")
        return sum
    }

    private fun checkedSubtract(
        left: Long,
        right: Long,
    ): Long {
        if (right == Long.MIN_VALUE) throw ArithmeticException("P806 amount overflow")
        return checkedAdd(left, -right)
    }
}
