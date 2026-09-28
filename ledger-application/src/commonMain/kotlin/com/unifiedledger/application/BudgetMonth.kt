package com.unifiedledger.application

import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth

/*
 * P7-07 budget slice 07.A (measurement matrix) and 07.C (calculation contract), approved by
 * D-184 / `docs/specs/2026-09-28-p7-07-budget-design.md` sections 2 and 4. Pure projection
 * only: no schema, no SQL, no persistence, no UI, and no Posting/balance/reconciliation
 * side effect. Amounts are exact integer minor units (CNY precision 2 first version);
 * binary floating point is never used.
 *
 * 07.A reuses the frozen ordinary classification exactly: the six-kind gate
 * [OrdinaryFlowClassification.ORDINARY_KINDS] and the per-account-kind dispatch inside
 * [MonthlyBuckets.aggregate]. This file is the single place a budget's "ordinary net
 * expense" is derived, and it derives it by delegating to that frozen projection rather
 * than re-implementing a kind table or guessing from account types (spec section 2.2
 * behavior-equivalence hard constraint).
 */

/**
 * Budget scope identity (spec section 3.1). [Total] covers every ordinary net expense of
 * the month including 无分类; [Category] observes one stable `CategoryKind.EXPENSE` category
 * (a level-1 scope covers all of its level-2 children). Total and category scopes are
 * independent observations and are never summed (spec section 3.2).
 */
sealed interface BudgetScope {
    data object Total : BudgetScope

    data class Category(
        val categoryId: CategoryId,
    ) : BudgetScope
}

/**
 * One month's ordinary net expense observation for a scope (spec section 2.3). Kept
 * per-currency with no cross-currency summation (D-120). A currency absent from the map has
 * no ordinary expense in that scope and month; callers read `map[currency] ?: 0L`.
 */
data class BudgetMonthContribution(
    val month: YearMonth,
    val netExpenseByCurrency: Map<CurrencyUnit, Long>,
)

/**
 * 07.A classifier: the per-month ordinary net expense contribution of a budget scope.
 *
 * Delegates to [MonthlyBuckets.aggregate] so the six ordinary kinds, the ten excluded kinds,
 * the fee-leg handling by account kind, the refund sign, the Asia/Shanghai bucket key and
 * the checked Long arithmetic are exactly the frozen P7-03 semantics. Ordinary income is
 * never counted against a budget (spec section 2.3), and the excluded kinds
 * (`OPENING_BALANCE`, `CREDIT_REPAYMENT`, `BALANCE_ADJUSTMENT`,
 * `BALANCE_ADJUSTMENT_REVERSAL`, the four `STORED_VALUE_*` kinds, `PREPAID_PURCHASE`,
 * `PREPAID_RECOGNITION`) contribute zero.
 *
 * A posting account missing from [catalog] fails with [IllegalStateException] and checked
 * overflow fails with [ArithmeticException], both exactly as [MonthlyBuckets.aggregate]; the
 * use-case boundary maps them to [BudgetMonthResult.InvalidState] (fail closed, never a
 * silent zero). A scope category with no expense this month contributes an empty map, which
 * is the observationally correct zero for a stale or childless scope.
 */
object BudgetOrdinaryNetExpense {
    fun contributions(
        rows: List<LedgerEntryRow>,
        ledgerId: LedgerId,
        catalog: LedgerCatalog,
        months: Collection<YearMonth>,
        scope: BudgetScope,
    ): Map<YearMonth, BudgetMonthContribution> {
        val activity = MonthlyBuckets.aggregate(rows, ledgerId, catalog, months)
        return activity.mapValues { (month, monthActivity) ->
            BudgetMonthContribution(
                month = month,
                netExpenseByCurrency = scopeNetExpense(monthActivity, scope),
            )
        }
    }

    private fun scopeNetExpense(
        activity: MonthlyActivity,
        scope: BudgetScope,
    ): Map<CurrencyUnit, Long> =
        when (scope) {
            // TOTAL includes 无分类: MonthlyCurrencyActivity.netExpenseMinorUnits already
            // folds the uncategorized EXPENSE-account postings (P703SPEC-09/F10).
            BudgetScope.Total -> activity.currencies.associate { it.currency to it.netExpenseMinorUnits }

            is BudgetScope.Category -> {
                val node = findExpenseNode(activity.expenseCategories, scope.categoryId)
                node?.let(::signedNetByCurrency) ?: emptyMap()
            }
        }

    private fun findExpenseNode(
        nodes: List<MonthlyCategoryTotal>,
        id: CategoryId,
    ): MonthlyCategoryTotal? {
        for (node in nodes) {
            if (node.categoryId == id) return node
            findExpenseNode(node.children, id)?.let { return it }
        }
        return null
    }

    /**
     * A category node's totals are the positive/refund split per currency; the signed net is
     * their checked sum. A level-1 node's totals already equal the sum of its level-2
     * children (reused [MonthlyBuckets.buildCategoryNodes] rollup, spec section 3.2).
     */
    private fun signedNetByCurrency(node: MonthlyCategoryTotal): Map<CurrencyUnit, Long> {
        val net = mutableMapOf<CurrencyUnit, Long>()
        for (entry in node.totals) {
            val contribution =
                checkedAdd(entry.positiveMinorUnits, entry.refundMinorUnits)
                    ?: throw ArithmeticException("budget category net expense overflow for ${entry.currency.code}")
            addTo(net, entry.currency, contribution, "budget category net expense overflow for ${entry.currency.code}")
        }
        return net
    }
}

/**
 * 07.C calculation contract (spec section 4). Exact checked Long arithmetic; every add or
 * subtract fails loud with [ArithmeticException] instead of silently wrapping. Amounts are
 * never clamped to zero: a net refund can make the net expense negative and the remaining
 * exceed the limit. Exactly equal is not overspent.
 */
object BudgetCalculation {
    /** `remaining = limit - netExpense`; throws [ArithmeticException] on Long overflow. */
    fun remainingMinorUnits(
        limitMinorUnits: Long,
        netExpenseMinorUnits: Long,
    ): Long =
        checkedSubtract(limitMinorUnits, netExpenseMinorUnits)
            ?: throw ArithmeticException("budget remaining overflow")

    /**
     * `overspent = max(netExpense - limit, 0)`; throws [ArithmeticException] on Long
     * overflow. `netExpense == limit` yields `0` (not overspent).
     */
    fun overspentMinorUnits(
        limitMinorUnits: Long,
        netExpenseMinorUnits: Long,
    ): Long {
        val delta =
            checkedSubtract(netExpenseMinorUnits, limitMinorUnits)
                ?: throw ArithmeticException("budget overspent overflow")
        return if (delta > 0L) delta else 0L
    }
}

/**
 * One month/scope budget observation (spec sections 3.3 and 4). [limitMinorUnits] is `null`
 * for an unset/closed budget (not monitored) and distinct from a monitored zero limit
 * (zero is a valid budget). [remainingMinorUnits]/[overspentMinorUnits] are `null` exactly
 * when the budget is not monitored; they are never a fabricated zero.
 */
data class BudgetMonth(
    val ledgerId: LedgerId,
    val month: YearMonth,
    val currency: CurrencyUnit,
    val scope: BudgetScope,
    val limitMinorUnits: Long?,
    val netExpenseMinorUnits: Long,
    val remainingMinorUnits: Long?,
    val overspentMinorUnits: Long?,
)

/**
 * Fail-closed budget month result (mirrors [MonthlyActivityResult], spec section 5.1).
 * [InvalidState] is a catalog/posting inconsistency or checked overflow; [Unavailable] is
 * reserved for the 07.D read-port failure (not produced by the pure projection of this
 * slice). Neither is ever rendered as a zero execution amount.
 */
sealed interface BudgetMonthResult {
    data class Success(
        val budgetMonth: BudgetMonth,
    ) : BudgetMonthResult

    data object InvalidState : BudgetMonthResult

    data object Unavailable : BudgetMonthResult
}

/**
 * Pure combination of an 07.A contribution and a configured limit into a [BudgetMonth].
 * A `null` limit (unset/closed) yields a not-monitored result with null remaining/overspent;
 * a set limit — including zero — is evaluated with the checked [BudgetCalculation] and any
 * overflow becomes [BudgetMonthResult.InvalidState].
 */
object BudgetMonthProjection {
    fun compute(
        ledgerId: LedgerId,
        month: YearMonth,
        currency: CurrencyUnit,
        scope: BudgetScope,
        limitMinorUnits: Long?,
        netExpenseMinorUnits: Long,
    ): BudgetMonthResult {
        if (limitMinorUnits == null) {
            return BudgetMonthResult.Success(
                BudgetMonth(
                    ledgerId = ledgerId,
                    month = month,
                    currency = currency,
                    scope = scope,
                    limitMinorUnits = null,
                    netExpenseMinorUnits = netExpenseMinorUnits,
                    remainingMinorUnits = null,
                    overspentMinorUnits = null,
                ),
            )
        }
        return try {
            BudgetMonthResult.Success(
                BudgetMonth(
                    ledgerId = ledgerId,
                    month = month,
                    currency = currency,
                    scope = scope,
                    limitMinorUnits = limitMinorUnits,
                    netExpenseMinorUnits = netExpenseMinorUnits,
                    remainingMinorUnits = BudgetCalculation.remainingMinorUnits(limitMinorUnits, netExpenseMinorUnits),
                    overspentMinorUnits = BudgetCalculation.overspentMinorUnits(limitMinorUnits, netExpenseMinorUnits),
                ),
            )
        } catch (failure: ArithmeticException) {
            BudgetMonthResult.InvalidState
        }
    }
}

/** Checked addition; `null` on overflow (same idiom as [MonthlyBuckets]' private helpers). */
private fun checkedAdd(
    left: Long,
    right: Long,
): Long? {
    if (right > 0 && left > Long.MAX_VALUE - right) return null
    if (right < 0 && left < Long.MIN_VALUE - right) return null
    return left + right
}

/** Checked subtraction; `null` on overflow (same idiom as [MonthlyBuckets]' private helper). */
private fun checkedSubtract(
    left: Long,
    right: Long,
): Long? {
    if (right < 0 && left > Long.MAX_VALUE + right) return null
    if (right > 0 && left < Long.MIN_VALUE + right) return null
    return left - right
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
