package com.unifiedledger.application

import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth

/*
 * P7-07 07.D-1 budget-month enumeration (spec section 5.1 / D-184 item 3). The month list and
 * the TOTAL + category scope set of a month need every configured budget of
 * `(ledgerId, month)`; the 07.B SQL surface only had the single-target
 * `selectBudgetConfigByScope`, so this read port backs the added
 * `selectBudgetConfigsForMonth` query.
 *
 * Read-only: configuration is never written here, and this never creates a Posting or changes
 * a balance/reconciliation. A missing budget for the month is simply an absent row.
 */

/**
 * One configured budget scope of a month with its CURRENT settings (spec sections 3.3/3.4):
 * the stable [budgetId], the decoded [scope], the CAS [revision], whether the current revision
 * is [closed], and the current [limitMinorUnits] (`null` when closed or not yet set — not
 * monitored; a monitored zero limit is a real `0`, distinct from unset).
 */
data class BudgetMonthConfigRow(
    val budgetId: BudgetId,
    val scope: BudgetScope,
    val revision: Long,
    val closed: Boolean,
    val limitMinorUnits: Long?,
)

/**
 * The configured budgets of one `(ledgerId, month)`, current settings only (spec section 5.1).
 * Implementations MUST return one row per configured scope and MUST NOT invent a row for an
 * unconfigured scope; a read failure must propagate rather than masquerade as an empty month.
 */
fun interface BudgetMonthConfigReader {
    fun configsFor(
        ledgerId: LedgerId,
        month: YearMonth,
    ): List<BudgetMonthConfigRow>
}

/**
 * The canonical `YYYY-MM` month key of the read-path config enumeration (spec section 3.1).
 * Kept as a named alias of [budgetMonthKey] rather than inlining the latter at the
 * composition-root call sites that adapt [BudgetMonthConfigReader] to the store's string-keyed
 * `configsForMonth`: it names the read-path contract explicitly and keeps the write-path
 * ([budgetMonthKey] in 07.B) and read-path key forms visibly the same canonical value. It is a
 * deliberate alias, not an independent implementation.
 */
fun budgetMonthConfigKey(month: YearMonth): String = budgetMonthKey(month)

/** Decodes a stored scope row back into the domain [BudgetScope] (spec section 3.1). */
fun budgetScopeFromStored(
    scopeKind: String,
    scopeCategoryId: String?,
): BudgetScope =
    if (scopeKind == "TOTAL") {
        BudgetScope.Total
    } else {
        BudgetScope.Category(CategoryId(requireNotNull(scopeCategoryId) { "a CATEGORY budget scope must carry a category id" }))
    }
