package com.unifiedledger.application

import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlin.time.Instant

/*
 * P7-09 09.C shared monthly snapshot (D-225 Q20; spec section 6.2).
 *
 * Spec section 6.2 freezes ONE shared read-only snapshot behind the monthly widget: `ledgerId`,
 * `month`, `currency`, the three exact amounts (ordinary income / net expense / balance), `asOf`,
 * `generation`, `catalogVersion` and an available / invalid / unavailable state. Its computation
 * MUST reuse the bounded month read ([MonthlyContributionReadPort]) and the P7-03 classification
 * ([MonthlyBuckets.aggregate]) — never a second money formula, never the whole-ledger
 * `QueryMonthlyActivity` / `loadLedgerEntryRows` path, never a floating-point SQL SUM.
 *
 * Fail-closed (spec sections 6.2/6.4): a read failure, a missing time projection, a catalog-version
 * mismatch, a catalog/posting inconsistency or a checked overflow is a TYPED failure — never a
 * fabricated zero. A month with no posting in the window IS a genuine zero, not a failure
 * (P709-A05: "失败不得伪装零值" cuts one way only).
 */

/**
 * The display state of one widget snapshot (spec section 6.2's "可用/失效/失败" triple).
 *
 * [AVAILABLE] carries the three exact minor-unit amounts; [INVALID] is the 失效 state (the read
 * generation and the caller's catalog are not one coherent snapshot, or a catalog/posting/overflow
 * inconsistency made the fold untrustworthy); [UNAVAILABLE] is the 失败 state (no usable read at
 * all). The last two carry NO amount and no currency, so no consumer can render them as a zero.
 */
enum class WidgetMonthlySnapshotStatus {
    AVAILABLE,
    INVALID,
    UNAVAILABLE,
}

/**
 * The three exact month amounts of ONE currency in integer minor units (spec section 6.2).
 * Semantics are exactly [MonthlyCurrencyActivity]'s frozen ones: [balanceMinorUnits] is ordinary
 * income minus net expense, never an account balance or a cash-flow figure (P7-03).
 */
data class WidgetMonthlyAmounts(
    val currency: CurrencyUnit,
    val ordinaryIncomeMinorUnits: Long,
    val netExpenseMinorUnits: Long,
    val balanceMinorUnits: Long,
)

/**
 * The shared monthly snapshot one widget render consumes (spec section 6.2).
 *
 * [generation] is the runtime owner's generation captured by the lease-scoped caller; it is a plain
 * `Int` here because the application layer owns no runtime-owner type (the app-ui owner's
 * `Generation` is a transparent `Int` typealias, so the composition root passes it unchanged). The
 * landing hop compares it against the currently active generation and DISCARDS a mismatch
 * (spec section 6.4).
 *
 * The `init` block is the structural half of "failure never masquerades as a zero": the three
 * amounts, the currency, the read timestamp and the catalog version are present EXACTLY when the
 * status is [WidgetMonthlySnapshotStatus.AVAILABLE], so an invalid/unavailable snapshot cannot be
 * rendered with 0/0/0 by accident — it has no amount to render at all.
 */
data class WidgetMonthlySnapshot(
    val ledgerId: LedgerId,
    val month: YearMonth,
    val status: WidgetMonthlySnapshotStatus,
    val generation: Int,
    val currency: CurrencyUnit? = null,
    val amounts: WidgetMonthlyAmounts? = null,
    val asOf: Instant? = null,
    val catalogVersion: Long? = null,
) {
    init {
        val available = status == WidgetMonthlySnapshotStatus.AVAILABLE
        require(available == (amounts != null)) {
            "a widget snapshot carries amounts exactly when it is AVAILABLE (status=$status)"
        }
        require(available == (currency != null)) { "a widget snapshot carries a currency exactly when it is AVAILABLE" }
        require(available == (asOf != null)) { "a widget snapshot carries asOf exactly when it is AVAILABLE" }
        require(available == (catalogVersion != null)) {
            "a widget snapshot carries catalogVersion exactly when it is AVAILABLE"
        }
    }

    companion object {
        /** The successful read: the three exact amounts of [amounts] plus their provenance. */
        fun available(
            ledgerId: LedgerId,
            month: YearMonth,
            generation: Int,
            catalogVersion: Long,
            amounts: WidgetMonthlyAmounts,
            asOf: Instant,
        ): WidgetMonthlySnapshot =
            WidgetMonthlySnapshot(
                ledgerId = ledgerId,
                month = month,
                status = WidgetMonthlySnapshotStatus.AVAILABLE,
                generation = generation,
                currency = amounts.currency,
                amounts = amounts,
                asOf = asOf,
                catalogVersion = catalogVersion,
            )

        /** The 失效 state: a coherent snapshot could not be established; no amount travels. */
        fun invalid(
            ledgerId: LedgerId,
            month: YearMonth,
            generation: Int,
        ): WidgetMonthlySnapshot = WidgetMonthlySnapshot(ledgerId, month, WidgetMonthlySnapshotStatus.INVALID, generation)

        /** The 失败 state: no usable read; the widget renders 不可用 and guides the user to the app. */
        fun unavailable(
            ledgerId: LedgerId,
            month: YearMonth,
            generation: Int,
        ): WidgetMonthlySnapshot = WidgetMonthlySnapshot(ledgerId, month, WidgetMonthlySnapshotStatus.UNAVAILABLE, generation)
    }
}

/**
 * P7-09 09.C (D-225 Q20; spec sections 6.2/6.3): the bounded, lease-scoped widget month read.
 *
 * It performs EXACTLY one bounded read ([MonthlyContributionReadPort.readContributions] over
 * `[monthStart(month), monthStart(nextMonth))`) and EXACTLY one fold ([MonthlyBuckets.aggregate]),
 * then projects the frozen three amounts. It never calls the whole-ledger read path, never invokes
 * the bootstrap/migrate graph builder (the caller reaches it through the runtime owner's operation
 * lease; spec section 6.3) and never sums amounts in SQL.
 *
 * MULTI-CURRENCY MONTHS ARE NOT COLLAPSED (spec section 6.2 freezes a SINGLE `currency` on the
 * snapshot, while the P7-03 projection yields one row per activity currency — spec section 8 open
 * item 4 leaves the mapping unfrozen). This implementation therefore fails CLOSED: a month whose
 * read window carries postings in more than one currency is the 失效 state, never a silently picked
 * (or silently summed) single-currency number. A month with no posting currency at all is the
 * genuine zero in the configured product currency.
 *
 * [expectedCatalogVersion] is the caller's `CatalogAuthority.catalogVersion`; the port already
 * fails closed when the read snapshot's catalog version differs, and that typed
 * [MonthlyContributionReadFailure.CatalogVersionMismatch] is the 失效 state here (mirroring the
 * `QueryBudgetMonth` mapping), while a missing projection or a database failure is 失败.
 */
class QueryWidgetMonthlySnapshot(
    private val readPort: MonthlyContributionReadPort,
    private val clock: LedgerClock,
    /** The product currency a genuine empty month is expressed in (the single managed currency). */
    private val configuredCurrency: CurrencyUnit = CATALOG_MANAGED_CURRENCY,
) {
    /**
     * The snapshot of `(ledgerId, month)` under the caller's catalog generation. [generation] is the
     * runtime owner's generation the caller read under; it travels into the snapshot unchanged so a
     * late landing can be discarded (spec section 6.4).
     */
    fun read(
        ledgerId: LedgerId,
        month: YearMonth,
        expectedCatalogVersion: Long,
        generation: Int,
    ): WidgetMonthlySnapshot {
        val read =
            try {
                readPort.readContributions(
                    ledgerId,
                    MonthlyBuckets.monthStart(month),
                    MonthlyBuckets.monthStart(month.plus(1, DateTimeUnit.MONTH)),
                    expectedCatalogVersion,
                )
            } catch (failure: Exception) {
                return WidgetMonthlySnapshot.unavailable(ledgerId, month, generation)
            }
        val success =
            when (read) {
                is MonthlyContributionReadResult.Success -> read
                is MonthlyContributionReadResult.Failed ->
                    when (read.failure) {
                        MonthlyContributionReadFailure.MissingProjection,
                        MonthlyContributionReadFailure.Unavailable,
                        -> return WidgetMonthlySnapshot.unavailable(ledgerId, month, generation)

                        MonthlyContributionReadFailure.CatalogVersionMismatch ->
                            return WidgetMonthlySnapshot.invalid(ledgerId, month, generation)
                    }
            }
        // The read timestamp is its own failure leg (the `QueryBudgetMonth` precedent): a clock that
        // cannot report a time makes the snapshot's provenance unverifiable, which is 失败 — never a
        // silently plausible "now".
        val asOf =
            try {
                clock.now()
            } catch (failure: Exception) {
                return WidgetMonthlySnapshot.unavailable(ledgerId, month, generation)
            }
        if (!isCatalogConsistent(success.rows, success.catalog, ledgerId)) {
            return WidgetMonthlySnapshot.invalid(ledgerId, month, generation)
        }
        return try {
            val activity = MonthlyBuckets.aggregate(success.rows, ledgerId, success.catalog, listOf(month)).getValue(month)
            val amounts =
                widgetAmounts(activity, configuredCurrency)
                    ?: return WidgetMonthlySnapshot.invalid(ledgerId, month, generation)
            WidgetMonthlySnapshot.available(ledgerId, month, generation, success.catalogVersion, amounts, asOf)
        } catch (failure: ArithmeticException) {
            // A checked overflow in the fold is the 失效 state, never a wraparound.
            WidgetMonthlySnapshot.invalid(ledgerId, month, generation)
        } catch (failure: IllegalStateException) {
            // A posting account absent from the read catalog is the 失效 state (P7-03 family).
            WidgetMonthlySnapshot.invalid(ledgerId, month, generation)
        } catch (failure: Exception) {
            WidgetMonthlySnapshot.unavailable(ledgerId, month, generation)
        }
    }

    /**
     * The three frozen amounts of the read month, or null when the month cannot be expressed as ONE
     * currency (spec section 8 open item 4; the multi-currency fail-closed rule documented above).
     */
    private fun widgetAmounts(
        activity: MonthlyActivity,
        configuredCurrency: CurrencyUnit,
    ): WidgetMonthlyAmounts? {
        val currencies = activity.currencies
        val currency =
            when {
                // No posting currency in the window: a genuine empty month expressed in the product
                // currency as a REAL zero (P709-A05: an empty month is not a failure).
                currencies.isEmpty() -> configuredCurrency
                currencies.size == 1 -> currencies.single().currency
                // More than one activity currency: never collapsed, never summed.
                else -> return null
            }
        val row = currencies.firstOrNull { it.currency == currency }
        return WidgetMonthlyAmounts(
            currency = currency,
            ordinaryIncomeMinorUnits = row?.ordinaryIncomeMinorUnits ?: 0L,
            netExpenseMinorUnits = row?.netExpenseMinorUnits ?: 0L,
            balanceMinorUnits = row?.balanceMinorUnits ?: 0L,
        )
    }

    /**
     * The read-consistency predicate, mirroring the shape the P7-03 read path applies
     * ([QueryMonthlyActivity.isCatalogConsistent]): every posting's account must exist in the read
     * catalog, belong to this ledger and carry the posting's own currency. A violation means the
     * catalog and the postings are not one coherent snapshot, which is the typed 失效 state — never
     * a silently folded amount. The predicate is deliberately re-stated rather than shared, because
     * [QueryMonthlyActivity] is not part of the widget's frozen reuse surface (spec section 6.2
     * freezes the bounded port and the classifier, not this guard).
     */
    private fun isCatalogConsistent(
        rows: List<LedgerEntryRow>,
        catalog: com.unifiedledger.domain.LedgerCatalog,
        ledgerId: LedgerId,
    ): Boolean {
        val accountsById = catalog.accounts.associateBy { it.id }
        return rows.all { row ->
            row.postings.all { posting ->
                val account = accountsById[posting.accountId] ?: return false
                if (account.ledgerId != ledgerId) return false
                if (account.currency != posting.amount.currency) return false
                true
            }
        }
    }
}
