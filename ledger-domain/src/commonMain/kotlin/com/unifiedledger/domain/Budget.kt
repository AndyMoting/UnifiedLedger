package com.unifiedledger.domain

/*
 * P7-07 budget pure value objects and arithmetic, approved by D-184 /
 * `docs/specs/2026-09-28-p7-07-budget-design.md` sections 3 and 4. Spec section 5 assigns
 * the month/scope/limit value objects and the pure `remaining`/`overspent` functions to
 * `ledger-domain` (no persistence, no platform API, no UI state). Only the pieces that need
 * no `kotlinx-datetime` live here: this module deliberately declares no datetime dependency,
 * so `YearMonth`-bearing observations stay in `ledger-application` (see `BudgetMonth.kt`).
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

/** Checked subtraction; `null` on overflow (same idiom as the domain checked helpers). */
private fun checkedSubtract(
    left: Long,
    right: Long,
): Long? {
    if (right < 0 && left > Long.MAX_VALUE + right) return null
    if (right > 0 && left < Long.MIN_VALUE + right) return null
    return left - right
}
