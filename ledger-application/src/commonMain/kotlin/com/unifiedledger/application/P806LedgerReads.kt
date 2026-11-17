package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime

/**
 * P8-06 batch a (D-343; spec sections 3.5/3.7/3.8): the four read use cases over the composite
 * snapshots.
 *
 * Contracts held here (D-343 3.5.4/3.7, 无期间（NoPeriod）/类型化读失败):
 * - the caller's immutable catalog of the same generation travels in the new report/calendar
 *   request, and the reader applies the shared axis rule inside its own transaction;
 * - a clock failure is a typed `Unavailable` **only** on the paths that actually need the clock
 *   (report "today", the calendar's default month);
 * - a read-port throw — including the default `UnsupportedOperationException` — is a typed
 *   `Unavailable`, never an escaped exception;
 * - a report on a ledger without any effective period answers the explicit NoPeriod payload for
 *   all five period kinds (the daily average is then not applicable, not zero);
 * - a period that cannot be represented is `InvalidWindow`, and checked amount overflow is
 *   `InvalidState`.
 */
class P806ReportQuery(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val authority: CatalogAuthority,
    private val clock: LedgerClock,
) {
    /**
     * The catalog generation this query binds at construction: the same immutable [authority] the
     * request's expected version comes from (D-343; spec sections 3.5.1/3.8). A successful raw
     * snapshot is checked against it before any fold, so a payload from another generation never
     * becomes a zero period.
     */
    val catalogVersion: Long = authority.catalogVersion

    /** The folded report for [period] (spec section 3.1: the caller picks the period). */
    fun query(period: P806Period): P806SnapshotResult<P806ReportView> {
        // The whole clock INPUT stage is one typed try: today, the next local midnight and the
        // report-zone projection. Any exception there is a typed Unavailable, never an escaped one.
        val today: LocalDate
        val futureFromEpochNanos: Long
        try {
            today = clock.now().toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date
            futureFromEpochNanos = P806Periods.nextLocalMidnightEpochNanos(today)
        } catch (clockFailure: Exception) {
            return P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
        }
        // Geometry first (no ledger data involved): a bad or unrepresentable custom window is an
        // InvalidWindow even on an empty ledger, never a NoPeriod and never a read failure.
        if (P806Periods.validateGeometry(period, today) == P806PeriodResolution.InvalidWindow) {
            return P806SnapshotResult.Failed(P806SnapshotFailure.InvalidWindow)
        }
        val request =
            P806ReportRequest(
                ledgerId = ledgerId,
                catalog = authority.catalog,
                period = period,
                today = today,
                futureFromEpochNanos = futureFromEpochNanos,
                expectedCatalogVersion = authority.catalogVersion,
            )
        val result =
            try {
                readPort.readReportSnapshot(request)
            } catch (readFailure: Exception) {
                return P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
            }
        return when (result) {
            is P806SnapshotResult.Success -> {
                // Version first (D-343; spec sections 3.5.1/3.8): the success-path InvalidWindow
                // below may only be judged on a snapshot of this query's generation.
                if (result.snapshot.catalogVersion != catalogVersion) {
                    return P806SnapshotResult.Failed(P806SnapshotFailure.CatalogVersionMismatch)
                }
                val snapshot = result.snapshot
                // 无期间（NoPeriod）: no effective period at all (empty ledger) — every period kind answers
                // NoPeriod, never a zero-valued period.
                val resolved =
                    if (snapshot.window == null) {
                        null
                    } else {
                        when (val resolution = P806Periods.resolve(period, today, snapshot.span)) {
                            is P806PeriodResolution.Resolved -> resolution.period
                            P806PeriodResolution.NotApplicable -> null
                            P806PeriodResolution.InvalidWindow -> return P806SnapshotResult.Failed(P806SnapshotFailure.InvalidWindow)
                        }
                    }
                fold { P806Folds.report(snapshot, ledgerId, authority.catalog, resolved) }
            }
            is P806SnapshotResult.Failed -> P806SnapshotResult.Failed(result.failure)
        }
    }

    private fun <T> fold(block: () -> T): P806SnapshotResult<T> =
        try {
            P806SnapshotResult.Success(block())
        } catch (overflow: ArithmeticException) {
            P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
        } catch (inconsistent: IllegalStateException) {
            P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
        } catch (invalidRaw: IllegalArgumentException) {
            // A raw grouped row that violates the gross/refund/net contract is a data-integrity
            // failure, not a read failure.
            P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
        }
}

class P806CalendarQuery(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val authority: CatalogAuthority,
    private val clock: LedgerClock,
) {
    /**
     * The catalog generation this query binds at construction (see [P806ReportQuery.catalogVersion]);
     * a successful raw snapshot from another generation is a typed mismatch, never a zero month.
     */
    val catalogVersion: Long = authority.catalogVersion

    /**
     * The folded calendar for [month]; `null` resolves to the reporting zone's current month and is
     * the only path that touches the clock (D-343 3.7（时钟输入阶段）). The month buckets stay computable without
     * any ledger span (无期间（NoPeriod） allows the shared date geometry on an empty ledger).
     */
    fun query(month: YearMonth? = null): P806SnapshotResult<P806CalendarView> {
        val effectiveMonth =
            month ?: try {
                val today = clock.now().toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date
                YearMonth(today.year, today.month)
            } catch (clockFailure: Exception) {
                return P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
            }
        val request =
            P806CalendarRequest(
                ledgerId = ledgerId,
                catalog = authority.catalog,
                month = effectiveMonth,
                expectedCatalogVersion = authority.catalogVersion,
            )
        val result =
            try {
                readPort.readCalendarSnapshot(request)
            } catch (readFailure: Exception) {
                return P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
            }
        return when (result) {
            is P806SnapshotResult.Success -> {
                if (result.snapshot.catalogVersion != catalogVersion) {
                    return P806SnapshotResult.Failed(P806SnapshotFailure.CatalogVersionMismatch)
                }
                try {
                    P806SnapshotResult.Success(P806Folds.calendar(result.snapshot, ledgerId, authority.catalog))
                } catch (overflow: ArithmeticException) {
                    P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
                } catch (inconsistent: IllegalStateException) {
                    P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
                }
            }
            is P806SnapshotResult.Failed -> P806SnapshotResult.Failed(result.failure)
        }
    }
}

class P806DrilldownQuery(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val authority: CatalogAuthority,
) {
    /**
     * The catalog generation this query binds at construction (see [P806ReportQuery.catalogVersion]);
     * a successful raw snapshot from another generation is a typed mismatch, never a zero page.
     */
    val catalogVersion: Long = authority.catalogVersion

    /** One detail page plus the exact count under the same predicate (spec section 3.9.3). */
    fun query(
        window: P806Window,
        categoryId: CategoryId? = null,
        cursor: P806TransactionCursor? = null,
    ): P806SnapshotResult<P806DrilldownView> {
        val request =
            P806DrilldownRequest(
                ledgerId = ledgerId,
                window = window,
                categoryId = categoryId,
                cursor = cursor,
                expectedCatalogVersion = authority.catalogVersion,
            )
        val result =
            try {
                readPort.readDrilldownSnapshot(request)
            } catch (readFailure: Exception) {
                return P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
            }
        return when (result) {
            is P806SnapshotResult.Success -> {
                if (result.snapshot.catalogVersion != catalogVersion) {
                    return P806SnapshotResult.Failed(P806SnapshotFailure.CatalogVersionMismatch)
                }
                P806SnapshotResult.Success(
                    P806DrilldownView(
                        ledgerId = ledgerId,
                        window = result.snapshot.window,
                        page = result.snapshot.page,
                        totalCount = result.snapshot.totalCount,
                    ),
                )
            }
            is P806SnapshotResult.Failed -> P806SnapshotResult.Failed(result.failure)
        }
    }
}

class P806AccountPostingsQuery(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val authority: CatalogAuthority,
) {
    /**
     * The catalog generation this query binds at construction (see [P806ReportQuery.catalogVersion]);
     * a successful raw snapshot from another generation is a typed mismatch, never a zero flow.
     */
    val catalogVersion: Long = authority.catalogVersion

    /** One account-postings page plus the exact posting count (spec section 3.2.2). */
    fun query(
        accountId: AccountId,
        window: P806Window? = null,
        cursor: P806AccountCursor? = null,
    ): P806SnapshotResult<P806AccountView> {
        val request =
            P806AccountRequest(
                ledgerId = ledgerId,
                accountId = accountId,
                window = window,
                cursor = cursor,
                expectedCatalogVersion = authority.catalogVersion,
            )
        val result =
            try {
                readPort.readAccountPostingsSnapshot(request)
            } catch (readFailure: Exception) {
                return P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
            }
        return when (result) {
            is P806SnapshotResult.Success -> {
                if (result.snapshot.catalogVersion != catalogVersion) {
                    return P806SnapshotResult.Failed(P806SnapshotFailure.CatalogVersionMismatch)
                }
                P806SnapshotResult.Success(
                    P806AccountView(
                        ledgerId = ledgerId,
                        accountId = accountId,
                        page = result.snapshot.page,
                        totalCount = result.snapshot.totalCount,
                    ),
                )
            }
            is P806SnapshotResult.Failed -> P806SnapshotResult.Failed(result.failure)
        }
    }
}
