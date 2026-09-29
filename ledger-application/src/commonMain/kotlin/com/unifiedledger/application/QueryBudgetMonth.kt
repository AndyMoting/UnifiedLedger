package com.unifiedledger.application

import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus

/*
 * P7-07 07.D-1 budget-month read use case (D-184 item 3 residual; spec sections 4/5/5.1).
 * Composes the shared bounded read ([MonthlyContributionReadPort]), the frozen classifier
 * ([BudgetOrdinaryNetExpense] over ONE [MonthlyBuckets.aggregate]), the current configuration
 * ([BudgetAuthorityReader] for a single scope; [BudgetMonthConfigReader] for the month list)
 * and the pure projection ([BudgetMonthProjection]) into a [BudgetMonthResult] per scope.
 *
 * The bounded read runs ONCE per call and is folded ONCE into every scope's net expense (TOTAL
 * plus every parent/child category); the per-scope loop never re-reads or re-aggregates the
 * ledger (spec section 5.1's "not per budget" hard constraint). Ordinary income is never
 * counted against a limit, and no scope observation is ever summed with another (spec
 * sections 2.3/3.2).
 *
 * Fail-closed (spec section 5.1; R-Q06-4 discipline): a read failure or a missing statistics-at
 * projection is [BudgetMonthResult.Unavailable] / [BudgetMonthViewResult.Unavailable]; a
 * catalog version mismatch (the port's typed
 * [MonthlyContributionReadFailure.CatalogVersionMismatch]) and a catalog/posting inconsistency,
 * an unknown budget scope category or a checked overflow all fold to the typed INVALID state at
 * this use-case granularity — the use case presents every "the snapshot you asked for is not
 * coherent" outcome as one invalid-state family, while the PORT keeps the mismatch distinct for
 * callers that need the finer grain (fix round P2-3: this paragraph states the code's actual
 * mapping). None is ever rendered as a zero execution amount, and an empty month with a
 * configured scope is a genuine zero, not a failure.
 */

/**
 * The whole-month view (spec sections 3.2/3.3/5.1): the TOTAL observation (present exactly when
 * the month has a TOTAL configuration) plus one observation per CONFIGURED category scope, all
 * folded from one bounded read. A configured scope is present regardless of state — a monitored
 * scope (including a zero limit) carries its limit/remaining/overspent, while a CLOSED or
 * not-yet-set scope is present as a [BudgetMonthResult.Success] with `limitMinorUnits == null`
 * and null remaining/overspent (spec section 3.3: configured-but-not-monitored is distinct from
 * an unconfigured scope). A scope with NO configuration row at all is absent from the view.
 */
data class BudgetMonthView(
    val ledgerId: LedgerId,
    val month: YearMonth,
    val catalogVersion: Long,
    val total: BudgetMonthResult.Success?,
    val categories: List<BudgetMonthResult.Success>,
) {
    /** Every present observation, TOTAL first then categories ordered by category id. */
    val observations: List<BudgetMonthResult.Success>
        get() = listOfNotNull(total) + categories
}

sealed interface BudgetMonthViewResult {
    data class Success(
        val view: BudgetMonthView,
    ) : BudgetMonthViewResult

    /** Catalog/posting inconsistency, an unknown budget scope category, or checked overflow. */
    data object InvalidState : BudgetMonthViewResult

    /** Read failure, missing projection or catalog version mismatch; never zeros. */
    data object Unavailable : BudgetMonthViewResult
}

class QueryBudgetMonth(
    private val readPort: MonthlyContributionReadPort,
    private val authorityReader: BudgetAuthorityReader,
    private val configReader: BudgetMonthConfigReader,
    private val configuredCurrency: CurrencyUnit = BUDGET_CONFIGURED_CURRENCY,
) {
    /**
     * The single-scope observation of `(ledgerId, month, scope)`. [expectedCatalogVersion] is
     * the consumer session's `CatalogAuthority.catalogVersion`; the read port fails with a
     * typed mismatch when its snapshot's catalog version differs (spec section 5.1 / open
     * item 9).
     *
     * An unconfigured/closed scope is `Success` with a `null` limit (not monitored, distinct
     * from a monitored zero); a monitored zero limit is a real `0`. An empty month is a
     * genuine zero execution amount, never [BudgetMonthResult.Unavailable].
     */
    fun query(
        ledgerId: LedgerId,
        month: YearMonth,
        scope: BudgetScope,
        expectedCatalogVersion: Long,
    ): BudgetMonthResult {
        val read =
            when (val result = readContributions(ledgerId, month, expectedCatalogVersion)) {
                is MonthRead.Ok -> result
                MonthRead.Unavailable -> return BudgetMonthResult.Unavailable
                MonthRead.InvalidState -> return BudgetMonthResult.InvalidState
            }
        // The configuration read fails SEPARATELY from the fold: a database failure in the
        // authority reader is the typed Unavailable (spec section 5.1), never the invalid
        // state and never a fabricated zero — even though `load` reports its failures with
        // IllegalStateException, which the fold below maps to InvalidState.
        val authority =
            try {
                authorityReader.load(budgetTargetFor(ledgerId, month, scope, configuredCurrency))
            } catch (failure: Exception) {
                return BudgetMonthResult.Unavailable
            }
        return try {
            val activity = MonthlyBuckets.aggregate(read.rows, ledgerId, read.catalog, listOf(month)).getValue(month)
            BudgetMonthProjection.compute(
                ledgerId = ledgerId,
                month = month,
                currency = configuredCurrency,
                scope = scope,
                limitMinorUnits = authority?.limitMinorUnits,
                netExpenseMinorUnits = BudgetOrdinaryNetExpense.scopeNetExpenseMinorUnits(activity, read.catalog, scope, configuredCurrency),
            )
        } catch (failure: IllegalStateException) {
            BudgetMonthResult.InvalidState
        } catch (failure: ArithmeticException) {
            BudgetMonthResult.InvalidState
        } catch (failure: Exception) {
            // A configuration-read failure (e.g. the authority reader's database error) is the
            // typed Unavailable, never a fabricated zero (spec section 5.1).
            BudgetMonthResult.Unavailable
        }
    }

    /**
     * The whole-month view: TOTAL plus every configured category scope, folded from ONE bounded
     * read. [expectedCatalogVersion] gates the read as in [query].
     *
     * A month with no configured scope yields an empty (but successful) view — a genuine zero,
     * not a failure. Any read/classification failure is the typed failure.
     */
    fun queryView(
        ledgerId: LedgerId,
        month: YearMonth,
        expectedCatalogVersion: Long,
    ): BudgetMonthViewResult {
        val configs =
            try {
                configReader.configsFor(ledgerId, month)
            } catch (failure: Exception) {
                return BudgetMonthViewResult.Unavailable
            }
        // The bounded read always runs, so an empty configuration never masks a read failure.
        val read =
            when (val result = readContributions(ledgerId, month, expectedCatalogVersion)) {
                is MonthRead.Ok -> result
                MonthRead.Unavailable -> return BudgetMonthViewResult.Unavailable
                MonthRead.InvalidState -> return BudgetMonthViewResult.InvalidState
            }
        return try {
            val activity = MonthlyBuckets.aggregate(read.rows, ledgerId, read.catalog, listOf(month)).getValue(month)
            val ordered =
                configs.sortedWith(
                    compareBy({ it.scope !is BudgetScope.Total }, { (it.scope as? BudgetScope.Category)?.categoryId?.value ?: "" }),
                )
            val observations =
                ordered.map { config ->
                    config.scope to
                        when (
                            val projection =
                                BudgetMonthProjection.compute(
                                    ledgerId,
                                    month,
                                    configuredCurrency,
                                    config.scope,
                                    config.limitMinorUnits,
                                    BudgetOrdinaryNetExpense.scopeNetExpenseMinorUnits(activity, read.catalog, config.scope, configuredCurrency),
                                )
                        ) {
                            is BudgetMonthResult.Success -> projection
                            // A negative stored limit or an overflow is the typed invalid state
                            // (spec sections 3.3/4); it is never displayed as zero.
                            BudgetMonthResult.InvalidState, BudgetMonthResult.Unavailable ->
                                throw IllegalStateException("budget month projection failed for scope ${config.scope}")
                        }
                }
            BudgetMonthViewResult.Success(
                BudgetMonthView(
                    ledgerId = ledgerId,
                    month = month,
                    catalogVersion = read.catalogVersion,
                    total = observations.firstOrNull { it.first == BudgetScope.Total }?.second,
                    categories = observations.filter { it.first != BudgetScope.Total }.map { it.second },
                ),
            )
        } catch (failure: IllegalStateException) {
            BudgetMonthViewResult.InvalidState
        } catch (failure: ArithmeticException) {
            BudgetMonthViewResult.InvalidState
        } catch (failure: Exception) {
            // Symmetric with [query]: any other unexpected failure is the typed Unavailable,
            // never a fabricated zero view (spec section 5.1).
            BudgetMonthViewResult.Unavailable
        }
    }

    /** The bounded read for one month, mapping the port's typed failure family. */
    private fun readContributions(
        ledgerId: LedgerId,
        month: YearMonth,
        expectedCatalogVersion: Long,
    ): MonthRead {
        val start = MonthlyBuckets.monthStart(month)
        val end = MonthlyBuckets.monthStart(month.plus(1, DateTimeUnit.MONTH))
        return try {
            when (val read = readPort.readContributions(ledgerId, start, end, expectedCatalogVersion)) {
                is MonthlyContributionReadResult.Success -> MonthRead.Ok(read.catalogVersion, read.catalog, read.rows)
                is MonthlyContributionReadResult.Failed ->
                    when (read.failure) {
                        MonthlyContributionReadFailure.MissingProjection,
                        MonthlyContributionReadFailure.Unavailable,
                        -> MonthRead.Unavailable
                        MonthlyContributionReadFailure.CatalogVersionMismatch -> MonthRead.InvalidState
                    }
            }
        } catch (failure: Exception) {
            MonthRead.Unavailable
        }
    }
}

/**
 * The single-scope configuration target for [BudgetAuthorityReader.load] (spec section 3.1):
 * `(ledgerId, month, currency, scope)` with the canonical keys shared with 07.B.
 */
private fun budgetTargetFor(
    ledgerId: LedgerId,
    month: YearMonth,
    scope: BudgetScope,
    currency: CurrencyUnit,
): BudgetTarget =
    BudgetTarget(
        ledgerId = ledgerId,
        monthKey = budgetMonthKey(month),
        currency = currency,
        scopeKey = budgetScopeKey(scope),
        scopeCategoryId = (scope as? BudgetScope.Category)?.categoryId,
    )

private sealed interface MonthRead {
    data class Ok(
        val catalogVersion: Long,
        val catalog: LedgerCatalog,
        val rows: List<LedgerEntryRow>,
    ) : MonthRead

    data object Unavailable : MonthRead

    data object InvalidState : MonthRead
}
