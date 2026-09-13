package com.unifiedledger.application

import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth

/**
 * P7-03.B unified monthly projection use case (spec section 4.2.1). One result feeds the
 * home month card, the category totals and the trend so the three presentations agree by
 * construction. The reporting time zone and bucket key are frozen in [MonthlyBuckets]
 * (R-Q06-2); "this month" is resolved from the injected [LedgerClock] (R-Q06-2, section
 * 4.2.3). Read-only: no accounting write path is touched. Failures never masquerade as
 * zeros or empty months (R-Q06-4): read-port and clock failures yield
 * [MonthlyActivityResult.Unavailable], catalog/posting inconsistency and checked overflow
 * yield [MonthlyActivityResult.InvalidState].
 */
class QueryMonthlyActivity(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
    private val clock: LedgerClock,
) {
    private val accountsById = catalog.accounts.associateBy { it.id }

    /**
     * The month projection for [month]. Any month can be requested; months without
     * transactions project explicit zero rows (R-Q07-1), while month selection in the UI
     * is bounded by [selectableMonths] (P703SPEC-10).
     */
    fun query(month: YearMonth): MonthlyActivityResult {
        val rows = loadRows() ?: return MonthlyActivityResult.Unavailable
        if (!isCatalogConsistent(rows)) return MonthlyActivityResult.InvalidState
        val activity =
            try {
                MonthlyBuckets.aggregate(rows, ledgerId, catalog, listOf(month)).getValue(month)
            } catch (failure: ArithmeticException) {
                return MonthlyActivityResult.InvalidState
            }
        return MonthlyActivityResult.Success(activity)
    }

    /**
     * The frozen twelve-month trend window ending at the current month, old to new
     * (R-Q07-1). Statistics months later than the current month stay outside the window
     * (spec section 6.2 residual boundary (c)).
     */
    fun trend(): MonthlyTrendResult {
        val rows = loadRows() ?: return MonthlyTrendResult.Unavailable
        val currentMonth =
            try {
                MonthlyBuckets.currentMonth(clock)
            } catch (failure: Exception) {
                return MonthlyTrendResult.Unavailable
            }
        if (!isCatalogConsistent(rows)) return MonthlyTrendResult.InvalidState
        val window = MonthlyBuckets.monthWindow(currentMonth)
        val byMonth =
            try {
                MonthlyBuckets.aggregate(rows, ledgerId, catalog, window)
            } catch (failure: ArithmeticException) {
                return MonthlyTrendResult.InvalidState
            }
        return MonthlyTrendResult.Success(
            MonthlyTrend(
                ledgerId = ledgerId,
                window = window,
                months = window.map { month -> byMonth.getValue(month) },
            ),
        )
    }

    /**
     * The SelectMonth optional domain `[first transaction statistics month, this month]`
     * (P703SPEC-10), old to new. Empty when the ledger has no transaction (SelectMonth is
     * absorbed, spec section 6.2) or when every statistics month lies after the current
     * month. Clock failures yield [SelectableMonthsResult.Unavailable].
     */
    fun selectableMonths(): SelectableMonthsResult {
        val rows = loadRows() ?: return SelectableMonthsResult.Unavailable
        val currentMonth =
            try {
                MonthlyBuckets.currentMonth(clock)
            } catch (failure: Exception) {
                return SelectableMonthsResult.Unavailable
            }
        val range = MonthlyBuckets.selectableMonthRange(rows, currentMonth)
        return SelectableMonthsResult.Success(months = range?.let(MonthlyBuckets::monthsInRange) ?: emptyList())
    }

    private fun loadRows(): List<LedgerEntryRow>? =
        try {
            readPort.loadLedgerEntryRows(ledgerId)
        } catch (failure: Exception) {
            null
        }

    private fun isCatalogConsistent(rows: List<LedgerEntryRow>): Boolean =
        rows.all { row ->
            row.postings.all { posting ->
                val account = accountsById[posting.accountId] ?: return false
                if (account.ledgerId != ledgerId) return false
                if (account.currency != posting.amount.currency) return false
                true
            }
        }
}
