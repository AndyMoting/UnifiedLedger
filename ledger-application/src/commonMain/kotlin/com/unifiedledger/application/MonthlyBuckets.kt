package com.unifiedledger.application

import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlin.time.Instant

/**
 * P7-03.B frozen month bucketing (R-Q06-2, spec section 4.2.1). The reporting time zone
 * is the application-layer frozen constant `Asia/Shanghai` (P703SPEC-10) — never a caller
 * parameter. The bucket key is the transaction's statistics time, and month bounds are
 * `[local 1st 00:00, next month 1st 00:00)` in that zone (C01 vectors). All arithmetic
 * goes through kotlinx-datetime; no hand-rolled 86400-second accumulation (R-3).
 */
object MonthlyBuckets {
    /** R-Q06-2: frozen reporting time zone; Asia/Shanghai has no DST (spec R-3). */
    val REPORT_TIME_ZONE: TimeZone = TimeZone.of("Asia/Shanghai")

    /** R-Q07-1: the frozen trend window length in months, including the current month. */
    const val TREND_MONTH_COUNT = 12

    /**
     * Bucket key of a transaction: the calendar month of its statistics time in the
     * frozen reporting zone. An instant at the exact local-month boundary belongs to the
     * new month (the boundary instant is the new month's local 00:00).
     */
    fun bucketKey(statisticsAt: Instant): YearMonth = statisticsAt.toLocalDateTime(REPORT_TIME_ZONE).date.yearMonth

    /**
     * "This month" resolved from the injected clock's current instant in the frozen zone
     * (R-Q06-2). Clock failures propagate to the caller, which maps them to
     * [MonthlyActivityResult.Unavailable] / [SelectableMonthsResult.Unavailable] (R-Q06-4).
     */
    fun currentMonth(clock: LedgerClock): YearMonth = bucketKey(clock.now())

    /**
     * The trend window ending at [endingMonth], old to new, [size] months long
     * (R-Q07-1: twelve natural months including the current one).
     */
    fun monthWindow(
        endingMonth: YearMonth,
        size: Int = TREND_MONTH_COUNT,
    ): List<YearMonth> {
        require(size >= 1) { "month window size must be positive" }
        val months = ArrayList<YearMonth>(size)
        var cursor = endingMonth
        repeat(size) {
            months.add(cursor)
            cursor = cursor.previousMonth()
        }
        months.reverse()
        return months
    }

    /**
     * SelectMonth optional domain (P703SPEC-10): `[first transaction statistics month,
     * current month]`, both ends inclusive. `null` when the ledger has no transaction to
     * derive a first statistics month from (no selectable month — SelectMonth stays
     * absorbed), or when every statistics month lies after the current month (spec
     * section 6.2 residual boundary (c)).
     */
    fun selectableMonthRange(
        rows: List<LedgerEntryRow>,
        currentMonth: YearMonth,
    ): ClosedRange<YearMonth>? {
        val firstStatisticsMonth = rows.minOfOrNull { bucketKey(it.statisticsAt) } ?: return null
        if (firstStatisticsMonth > currentMonth) return null
        return firstStatisticsMonth..currentMonth
    }

    /** Materializes an inclusive month range old to new (SelectMonth domain listing). */
    fun monthsInRange(range: ClosedRange<YearMonth>): List<YearMonth> {
        val months = ArrayList<YearMonth>()
        var cursor = range.start
        while (cursor <= range.endInclusive) {
            months.add(cursor)
            cursor = cursor.nextMonth()
        }
        return months
    }

    /**
     * Instant bounds of a month in the frozen zone: `[start, nextMonthStart)`. Kept for
     * boundary reasoning and tests; bucketing compares calendar months derived with the
     * same zone, which is equivalent because Asia/Shanghai has no DST.
     */
    fun monthStart(statisticsMonth: YearMonth): Instant = statisticsMonth.firstDay.atStartOfDayIn(REPORT_TIME_ZONE)

    fun YearMonth.nextMonth(): YearMonth = plus(1, DateTimeUnit.MONTH)

    fun YearMonth.previousMonth(): YearMonth = minus(1, DateTimeUnit.MONTH)

    /**
     * Unified monthly aggregation over the given months (plan section 5.1: the home card,
     * the category totals and the trend share one result). Pure projection: rows are
     * bucketed by their statistics time, classified per the frozen section 3.1.1 table and
     * accumulated with checked Long arithmetic. Overflow fails with [ArithmeticException]
     * and a posting account missing from the injected catalog fails with
     * [IllegalStateException]; the use case boundary maps both to typed failures (R-Q06-4).
     */
    fun aggregate(
        rows: List<LedgerEntryRow>,
        ledgerId: LedgerId,
        catalog: LedgerCatalog,
        months: Collection<YearMonth>,
    ): Map<YearMonth, MonthlyActivity> {
        val accountsById = catalog.accounts.associateBy { it.id }
        val categoriesById = catalog.categories.associateBy { it.id }
        val leafCategoryByPostingAccount =
            catalog.categories
                .filter { it.postingAccountId != null }
                .sortedBy { it.id.value }
                .associateBy { requireNotNull(it.postingAccountId) }
        val activityCurrencies =
            rows
                .flatMap { row -> row.postings.map { it.amount.currency } }
                .distinct()
                .sortedWith(compareBy({ it.code }, { it.precision }))

        val byMonth = months.associateWith { MonthAccumulator() }

        for (row in rows) {
            val bucket = byMonth[bucketKey(row.statisticsAt)] ?: continue
            bucket.transactionCount += 1
            bucket.countByKind[row.kind] = (bucket.countByKind[row.kind] ?: 0) + 1
            if (!OrdinaryFlowClassification.isOrdinary(row.kind)) continue
            for (posting in row.postings) {
                val account =
                    accountsById[posting.accountId]
                        ?: throw IllegalStateException("Posting account ${posting.accountId.value} is not in the catalog")
                val currency = posting.amount.currency
                when (account.kind) {
                    AccountKind.EXPENSE -> {
                        val amount = posting.amount.minorUnits
                        addTo(
                            bucket.positiveExpense,
                            currency,
                            if (amount > 0L) amount else 0L,
                            "ordinary expense positive overflow for ${currency.code}",
                        )
                        addTo(
                            bucket.refundExpense,
                            currency,
                            if (amount < 0L) amount else 0L,
                            "ordinary expense refund overflow for ${currency.code}",
                        )
                        // P703SPEC-09: a real-account leg without a
                        // catalog_category.posting_account_id mapping contributes to net
                        // expense and is accumulated as the explicit 无分类 row, so the
                        // category region always reconciles with the month card (F10).
                        val leafTotals =
                            leafCategoryByPostingAccount[posting.accountId]?.let { leaf ->
                                bucket.expenseLeaves.getOrPut(leaf.id) { CategoryAccumulator() }
                            } ?: bucket.uncategorizedExpense
                        addTo(leafTotals.positive, currency, if (amount > 0L) amount else 0L, "expense category positive overflow for ${currency.code}")
                        addTo(leafTotals.refund, currency, if (amount < 0L) amount else 0L, "expense category refund overflow for ${currency.code}")
                    }
                    AccountKind.INCOME -> {
                        val negated =
                            checkedNegate(posting.amount.minorUnits)
                                ?: throw ArithmeticException("income posting negation overflow for ${currency.code}")
                        addTo(bucket.income, currency, negated, "ordinary income overflow for ${currency.code}")
                        val leafTotals =
                            leafCategoryByPostingAccount[posting.accountId]?.let { leaf ->
                                bucket.incomeLeaves.getOrPut(leaf.id) { CategoryAccumulator() }
                            } ?: bucket.uncategorizedIncome
                        addTo(leafTotals.positive, currency, if (negated > 0L) negated else 0L, "income category positive overflow for ${currency.code}")
                        addTo(leafTotals.refund, currency, if (negated < 0L) negated else 0L, "income category refund overflow for ${currency.code}")
                    }
                    AccountKind.ASSET,
                    AccountKind.LIABILITY,
                    AccountKind.EQUITY,
                    -> Unit
                }
            }
        }

        return byMonth.mapValues { (month, bucket) ->
            val currencies =
                activityCurrencies.map { currency ->
                    val income = bucket.income[currency] ?: 0L
                    val positive = bucket.positiveExpense[currency] ?: 0L
                    val refund = bucket.refundExpense[currency] ?: 0L
                    val netExpense = checkedAdd(positive, refund) ?: throw ArithmeticException("net expense overflow for ${currency.code}")
                    val balance = checkedSubtract(income, netExpense) ?: throw ArithmeticException("balance overflow for ${currency.code}")
                    MonthlyCurrencyActivity(
                        currency = currency,
                        ordinaryIncomeMinorUnits = income,
                        netExpenseMinorUnits = netExpense,
                        balanceMinorUnits = balance,
                        positiveExpenseMinorUnits = positive,
                        refundMinorUnits = refund,
                        transactionCount = bucket.transactionCount,
                        countByKind = bucket.countByKind.toMap(),
                    )
                }
            MonthlyActivity(
                ledgerId = ledgerId,
                month = month,
                currencies = currencies,
                expenseCategories = buildCategoryNodes(bucket.expenseLeaves, categoriesById),
                incomeCategories = buildCategoryNodes(bucket.incomeLeaves, categoriesById),
                uncategorizedExpenseTotals = bucket.uncategorizedExpense.toTotals(),
                uncategorizedIncomeTotals = bucket.uncategorizedIncome.toTotals(),
            )
        }
    }

    private fun buildCategoryNodes(
        leaves: Map<CategoryId, CategoryAccumulator>,
        categoriesById: Map<CategoryId, Category>,
    ): List<MonthlyCategoryTotal> {
        if (leaves.isEmpty()) return emptyList()
        val leafNodes =
            leaves.entries
                .map { (leafId, accumulator) ->
                    MonthlyCategoryTotal(
                        categoryId = leafId,
                        categoryName = categoriesById[leafId]?.name ?: "",
                        totals = accumulator.toTotals(),
                        children = emptyList(),
                    )
                }.sortedBy { it.categoryId.value }
        return leafNodes
            .groupBy { node -> categoriesById[node.categoryId]?.parentId }
            .flatMap { (parentId, nodes) ->
                val parent = parentId?.let(categoriesById::get)
                if (parent == null) {
                    // Degenerate catalog state (posting-mapped leaf without a resolvable
                    // parent): promote the leaf to level 1 so no amount is dropped. P7-01
                    // catalog guards prevent this state in product data.
                    nodes
                } else {
                    listOf(
                        MonthlyCategoryTotal(
                            categoryId = parent.id,
                            categoryName = parent.name,
                            totals = sumCategoryTotals(nodes.map { it.totals }),
                            children = nodes,
                        ),
                    )
                }
            }.sortedBy { it.categoryId.value }
    }

    private fun CategoryAccumulator.toTotals(): List<MonthlyCategoryCurrencyTotal> =
        (positive.keys + refund.keys)
            .distinct()
            .sortedWith(compareBy({ it.code }, { it.precision }))
            .map { currency ->
                MonthlyCategoryCurrencyTotal(
                    currency = currency,
                    positiveMinorUnits = positive[currency] ?: 0L,
                    refundMinorUnits = refund[currency] ?: 0L,
                )
            }

    private fun sumCategoryTotals(totals: List<List<MonthlyCategoryCurrencyTotal>>): List<MonthlyCategoryCurrencyTotal> {
        val positive = mutableMapOf<CurrencyUnit, Long>()
        val refund = mutableMapOf<CurrencyUnit, Long>()
        for (entry in totals.flatten()) {
            addTo(positive, entry.currency, entry.positiveMinorUnits, "category level-1 positive overflow for ${entry.currency.code}")
            addTo(refund, entry.currency, entry.refundMinorUnits, "category level-1 refund overflow for ${entry.currency.code}")
        }
        return (positive.keys + refund.keys)
            .distinct()
            .sortedWith(compareBy({ it.code }, { it.precision }))
            .map { MonthlyCategoryCurrencyTotal(it, positive[it] ?: 0L, refund[it] ?: 0L) }
    }

    private fun addTo(
        totals: MutableMap<CurrencyUnit, Long>,
        currency: CurrencyUnit,
        amount: Long,
        overflowMessage: String,
    ) {
        val next = checkedAdd(totals[currency] ?: 0L, amount) ?: throw ArithmeticException(overflowMessage)
        totals[currency] = next
    }
}

private fun checkedAdd(
    left: Long,
    right: Long,
): Long? {
    if (right > 0 && left > Long.MAX_VALUE - right) return null
    if (right < 0 && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedNegate(value: Long): Long? = if (value == Long.MIN_VALUE) null else -value

private fun checkedSubtract(
    left: Long,
    right: Long,
): Long? {
    if (right < 0 && left > Long.MAX_VALUE + right) return null
    if (right > 0 && left < Long.MIN_VALUE + right) return null
    return left - right
}

/** Per-leaf-category checked accumulator (positive / refund split per currency). */
private class CategoryAccumulator {
    val positive = mutableMapOf<CurrencyUnit, Long>()
    val refund = mutableMapOf<CurrencyUnit, Long>()
}

/** Per-month checked accumulator for the unified monthly projection. */
private class MonthAccumulator {
    var transactionCount = 0
    val countByKind = mutableMapOf<TransactionKind, Int>()
    val income = mutableMapOf<CurrencyUnit, Long>()
    val positiveExpense = mutableMapOf<CurrencyUnit, Long>()
    val refundExpense = mutableMapOf<CurrencyUnit, Long>()
    val expenseLeaves = mutableMapOf<CategoryId, CategoryAccumulator>()
    val incomeLeaves = mutableMapOf<CategoryId, CategoryAccumulator>()

    /** P703SPEC-09/F10: ordinary postings whose account has no category mapping (无分类). */
    val uncategorizedExpense = CategoryAccumulator()
    val uncategorizedIncome = CategoryAccumulator()
}

/**
 * R-Q06-3 frozen ordinary income/expense classification (spec section 3.1.1). Only
 * current versions whose effective kind is in the frozen six-kind set contribute, and
 * each posting is then gated by its account kind exactly like SummarizeLedgerActivity:
 * EXPENSE-account postings keep the ledger sign as ordinary expense (a REFUND_RECEIPT
 * negative posting is a negative expense), INCOME-account postings enter ordinary income
 * negated, and ASSET/LIABILITY/EQUITY postings contribute nothing (which removes all
 * principal legs). The ten special kinds are excluded as a whole, so e.g. stored-value
 * bonus income and stored-value spend category postings never reach ordinary totals.
 */
object OrdinaryFlowClassification {
    val ORDINARY_KINDS: Set<TransactionKind> =
        setOf(
            TransactionKind.EXPENSE,
            TransactionKind.INCOME,
            TransactionKind.ACCOUNT_TRANSFER,
            TransactionKind.LEND,
            TransactionKind.COLLECT,
            TransactionKind.REFUND_RECEIPT,
        )

    fun isOrdinary(kind: TransactionKind): Boolean = kind in ORDINARY_KINDS
}

/**
 * One month's unified projection consumed by the home month card, the category totals
 * and the trend (plan section 5.1; spec section 4.2.1). Amounts are ledger-exact minor
 * units per currency with no cross-currency summation (D-120).
 */
data class MonthlyActivity(
    val ledgerId: LedgerId,
    val month: YearMonth,
    /** One row per activity currency of the ledger (sorted by code, then precision). */
    val currencies: List<MonthlyCurrencyActivity>,
    /** Level-1 expense category totals; each level-1 total equals the sum of its level-2 children. */
    val expenseCategories: List<MonthlyCategoryTotal>,
    /** Level-1 income category totals, same rollup rule. */
    val incomeCategories: List<MonthlyCategoryTotal>,
    /**
     * P703SPEC-09 (F10): ordinary EXPENSE-account postings whose account has no
     * `catalog_category.posting_account_id` mapping. They contribute to 净支出 and are
     * presented as one explicit 无分类 row, never as an invented category, so the category
     * region always reconciles with the month card.
     */
    val uncategorizedExpenseTotals: List<MonthlyCategoryCurrencyTotal> = emptyList(),
    /** P703SPEC-09 (F10): the income-side mirror of [uncategorizedExpenseTotals]. */
    val uncategorizedIncomeTotals: List<MonthlyCategoryCurrencyTotal> = emptyList(),
)

data class MonthlyCurrencyActivity(
    val currency: CurrencyUnit,
    val ordinaryIncomeMinorUnits: Long,
    val netExpenseMinorUnits: Long,
    /** 结余 = ordinary income − net expense (checked); never an account balance or cash flow. */
    val balanceMinorUnits: Long,
    /** Non-negative part of ordinary expense; kept separate from refunds (R-Q07-3). */
    val positiveExpenseMinorUnits: Long,
    /** Non-positive refund part of ordinary expense (sign preserved, R-Q07-3). */
    val refundMinorUnits: Long,
    val transactionCount: Int,
    /** Current-month transaction count by effective kind (special kinds included). */
    val countByKind: Map<TransactionKind, Int>,
)

data class MonthlyCategoryTotal(
    val categoryId: CategoryId,
    /** Current display name from the catalog (rename/deactivation aware; R-Q07-2). */
    val categoryName: String,
    val totals: List<MonthlyCategoryCurrencyTotal>,
    val children: List<MonthlyCategoryTotal>,
)

/**
 * Per-currency split of a category's month total: the positive part and the non-positive
 * refund part sum exactly to the signed net contribution.
 */
data class MonthlyCategoryCurrencyTotal(
    val currency: CurrencyUnit,
    val positiveMinorUnits: Long,
    val refundMinorUnits: Long,
)

sealed interface MonthlyActivityResult {
    data class Success(
        val activity: MonthlyActivity,
    ) : MonthlyActivityResult

    /** Catalog/posting inconsistency or checked overflow (R-Q06-4; fail closed). */
    data object InvalidState : MonthlyActivityResult

    /** Read-port or clock failure; never rendered as zeros or an empty month (R-Q06-4). */
    data object Unavailable : MonthlyActivityResult
}

/** Twelve-month trend over the frozen window, old to new (R-Q07-1); one slice per month. */
data class MonthlyTrend(
    val ledgerId: LedgerId,
    val window: List<YearMonth>,
    val months: List<MonthlyActivity>,
)

sealed interface MonthlyTrendResult {
    data class Success(
        val trend: MonthlyTrend,
    ) : MonthlyTrendResult

    data object InvalidState : MonthlyTrendResult

    data object Unavailable : MonthlyTrendResult
}

sealed interface SelectableMonthsResult {
    /** Months of the frozen SelectMonth domain, old to new; empty when nothing is selectable. */
    data class Success(
        val months: List<YearMonth>,
    ) : SelectableMonthsResult

    data object Unavailable : SelectableMonthsResult
}
