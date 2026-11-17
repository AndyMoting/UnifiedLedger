package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.time.Instant

/*
 * P8-06 batch a (D-343; spec sections 3.4/3.5 and Appendix A): request, payload and view types
 * for the four composite read snapshots.
 *
 * The port methods return the RAW aggregate payload of exactly one read transaction; all
 * ordinary-income/expense classification, category roll-up and daily averaging happens in
 * [P806Folds] on top of that payload. Nothing in this file reads persistence.
 */

/** Detail-list page size (spec section 3.4: 50 transactions per page). */
const val P806_DETAIL_PAGE_SIZE: Int = 50

/** Account postings page size (spec section 3.4: 100 postings per page). */
const val P806_ACCOUNT_PAGE_SIZE: Int = 100

/** Upper bound of the local day buckets of one natural month (spec section 3.10.2). */
const val P806_MAX_DAY_BUCKETS: Int = 31

/** A half-open instant window `[startEpochNanos, endEpochNanos)`. */
data class P806Window(
    val startEpochNanos: Long,
    val endEpochNanos: Long,
) {
    init {
        require(endEpochNanos > startEpochNanos) { "a P806 window must be a positive half-open range" }
    }
}

/** The ledger's effective statistics span: earliest and latest current-version statistics instant. */
data class P806Span(
    val earliest: Instant,
    val latest: Instant,
)

/** One local day of a calendar month: its date and its half-open local window. */
data class P806DayBucket(
    val date: LocalDate,
    val window: P806Window,
)

/** Keyset cursor of the detail list: statistics instant plus transaction id (descending). */
data class P806TransactionCursor(
    val statisticsAtEpochNanos: Long,
    val transactionId: String,
)

/** Keyset cursor of the account posting list: parsed occurred_at plus posting id (descending). */
data class P806AccountCursor(
    val occurredAtEpochNanos: Long,
    val postingId: String,
)

/** One page of the ordinary detail list, with the cursor of the following page (`null` = last). */
data class P806TransactionPage(
    val rows: List<LedgerEntryRow>,
    val nextCursor: P806TransactionCursor?,
)

/** One account posting row: the account's own leg, dated by its real fund time (occurred_at). */
data class P806AccountPostingRow(
    val postingId: PostingId,
    val transactionId: TransactionId,
    val occurredAt: Instant,
    val note: String?,
    val kind: TransactionKind,
    val amount: Money,
)

/** One page of account postings, with the cursor of the following page (`null` = last). */
data class P806AccountPostingsPage(
    val rows: List<P806AccountPostingRow>,
    val nextCursor: P806AccountCursor?,
)

/**
 * One (account, currency) group read from the ledger (D-343 3.4（同组正/负/净三列）): the same grouped row carries the
 * gross positive sum, the gross refund sum and the net sum, so the fold never infers the split from
 * a single net value.
 */
data class P806AccountCurrencyTotal(
    val accountId: AccountId,
    val currency: CurrencyUnit,
    val positiveMinorUnits: Long,
    val refundMinorUnits: Long,
    val totalMinorUnits: Long,
) {
    init {
        // The raw row keeps the ledger signs: the gross part is non-negative and the refund part is
        // non-positive, so their sum cannot overflow and the exactness check below is safe.
        require(positiveMinorUnits >= 0L) { "a grouped row's gross part is non-negative" }
        require(refundMinorUnits <= 0L) { "a grouped row's refund part is non-positive" }
        require(totalMinorUnits == positiveMinorUnits + refundMinorUnits) {
            "a grouped row must keep gross + refund == net"
        }
    }
}

/** Distinct contributing transaction count of one currency. */
data class P806CurrencyTransactionCount(
    val currency: CurrencyUnit,
    val transactionCount: Int,
)

/** The raw payload of one report read (spec section 3.5, report surface). */
data class P806ReportSnapshot(
    val catalogVersion: Long,
    /** `null` only for `ALL` on an empty ledger (no span, daily average not applicable). */
    val window: P806Window?,
    val span: P806Span?,
    val currencyAxis: List<CurrencyUnit>,
    val postingTotals: List<P806AccountCurrencyTotal>,
    val transactionCount: Int,
    val currencyCounts: List<P806CurrencyTransactionCount>,
    val futureTransactionCount: Int,
    val firstPage: P806TransactionPage,
    val detailCount: Int,
)

/** One day's raw aggregates (calendar surface): per-(account, currency) totals plus the global count. */
data class P806CalendarDaySnapshot(
    val date: LocalDate,
    val window: P806Window,
    val postingTotals: List<P806AccountCurrencyTotal>,
    /** The day's GLOBAL distinct contributing transaction count (one number per day). */
    val transactionCount: Int,
)

/** The raw payload of one calendar read: one entry per local day of the month, in date order. */
data class P806CalendarSnapshot(
    val catalogVersion: Long,
    val month: YearMonth,
    val currencyAxis: List<CurrencyUnit>,
    val days: List<P806CalendarDaySnapshot>,
)

/** The raw payload of one detail-list read. */
data class P806DrilldownSnapshot(
    val catalogVersion: Long,
    val window: P806Window,
    val page: P806TransactionPage,
    val totalCount: Int,
)

/** The raw payload of one account-postings read. */
data class P806AccountSnapshot(
    val catalogVersion: Long,
    val page: P806AccountPostingsPage,
    val totalCount: Int,
)

/** Typed failures shared by the four snapshots (spec section 3.8). */
sealed interface P806SnapshotFailure {
    /** A read failure, an unusable clock, or a failed lease. */
    data object Unavailable : P806SnapshotFailure

    /** The snapshot's catalog generation differs from the caller's expected version. */
    data object CatalogVersionMismatch : P806SnapshotFailure

    /** A version row in the ledger has no numeric statistics projection. */
    data object MissingProjection : P806SnapshotFailure

    /** An account leg's `occurred_at` does not parse under the frozen shape rules. */
    data object OccurredAtUnparseable : P806SnapshotFailure

    /** A custom period ends before it starts, or a window cannot be represented. */
    data object InvalidWindow : P806SnapshotFailure

    /** Checked arithmetic overflow, or another structural inconsistency. */
    data object InvalidState : P806SnapshotFailure

    /** Missing, cross-ledger or currency-mismatched catalog references (any count non-zero). */
    data class CatalogReference(
        val unresolvedAccount: Long,
        val crossLedgerAccount: Long,
        val currencyMismatch: Long,
    ) : P806SnapshotFailure
}

/** Success/failure envelope of every P806 read. */
sealed interface P806SnapshotResult<out T> {
    data class Success<T>(
        val snapshot: T,
    ) : P806SnapshotResult<T>

    data class Failed(
        val failure: P806SnapshotFailure,
    ) : P806SnapshotResult<Nothing>
}

/** One request of the report surface. */
data class P806ReportRequest(
    val ledgerId: LedgerId,
    /** The caller's immutable catalog of the same generation as [expectedCatalogVersion]. */
    val catalog: LedgerCatalog,
    val period: P806Period,
    /** Clock-resolved local date used to resolve relative periods and the future threshold. */
    val today: LocalDate,
    /** First instant whose statistics date counts as a future date (next local midnight). */
    val futureFromEpochNanos: Long,
    val expectedCatalogVersion: Long,
)

/** One request of the calendar surface. */
data class P806CalendarRequest(
    val ledgerId: LedgerId,
    /** The caller's immutable catalog of the same generation as [expectedCatalogVersion]. */
    val catalog: LedgerCatalog,
    val month: YearMonth,
    val expectedCatalogVersion: Long,
)

/** One request of the detail-list surface. */
data class P806DrilldownRequest(
    val ledgerId: LedgerId,
    val window: P806Window,
    val categoryId: CategoryId? = null,
    val cursor: P806TransactionCursor? = null,
    val pageSize: Int = P806_DETAIL_PAGE_SIZE,
    val expectedCatalogVersion: Long,
) {
    init {
        require(pageSize > 0) { "page size must be positive" }
    }
}

/** One request of the account-postings surface. */
data class P806AccountRequest(
    val ledgerId: LedgerId,
    val accountId: AccountId,
    /** `null` = the account's whole history (no occurred_at window). */
    val window: P806Window? = null,
    val cursor: P806AccountCursor? = null,
    val pageSize: Int = P806_ACCOUNT_PAGE_SIZE,
    val expectedCatalogVersion: Long,
) {
    init {
        require(pageSize > 0) { "page size must be positive" }
    }
}

/** Per-currency amounts of one report period. */
data class P806CurrencyView(
    val currency: CurrencyUnit,
    val ordinaryIncomeMinorUnits: Long,
    val netExpenseMinorUnits: Long,
    /** Ordinary income − net expense (checked); never an account balance or cash flow. */
    val balanceMinorUnits: Long,
    val positiveExpenseMinorUnits: Long,
    val refundMinorUnits: Long,
    /** `null` when the period has no natural day (empty `ALL`). */
    val dailyAverageMinorUnits: Long?,
    /**
     * The DISTINCT contributing transaction count of THIS currency (the snapshot's own grouped
     * count, never a sum of per-kind or per-account groups). A currency on the axis with no
     * contribution in the window is a real zero here.
     */
    val transactionCount: Int,
)

/** Per-currency split of one category's signed contribution. */
data class P806CategoryCurrencyTotal(
    val currency: CurrencyUnit,
    val positiveMinorUnits: Long,
    val refundMinorUnits: Long,
)

/** One category row: level-1 rows carry their level-2 children and equal their sum. */
data class P806CategoryView(
    val categoryId: CategoryId,
    val categoryName: String,
    val totals: List<P806CategoryCurrencyTotal>,
    val children: List<P806CategoryView>,
)

/** The folded report view (one period, per-currency, with category roll-up and the detail head). */
data class P806ReportView(
    val ledgerId: LedgerId,
    val period: P806ResolvedPeriod?,
    val currencies: List<P806CurrencyView>,
    val expenseCategories: List<P806CategoryView>,
    val incomeCategories: List<P806CategoryView>,
    val uncategorizedExpenseTotals: List<P806CategoryCurrencyTotal>,
    val uncategorizedIncomeTotals: List<P806CategoryCurrencyTotal>,
    val transactionCount: Int,
    val futureTransactionCount: Int,
    val detailPage: P806TransactionPage,
    val detailCount: Int,
) {
    /** True when there is no period at all (empty `ALL`): the daily average is not applicable. */
    val dailyAverageNotApplicable: Boolean get() = period == null
}

/** One calendar day: per-currency amounts plus the day's global contributing transaction count. */
data class P806CalendarDayView(
    val date: LocalDate,
    val currencies: List<P806CalendarCurrencyView>,
    val transactionCount: Int,
)

/** One calendar day's amounts for one axis currency. */
data class P806CalendarCurrencyView(
    val currency: CurrencyUnit,
    val ordinaryIncomeMinorUnits: Long,
    val netExpenseMinorUnits: Long,
)

/** The folded calendar view of one natural month. */
data class P806CalendarView(
    val ledgerId: LedgerId,
    val month: YearMonth,
    val axis: List<CurrencyUnit>,
    val days: List<P806CalendarDayView>,
)

/** The detail-list view: one page plus the exact count under the same predicate. */
data class P806DrilldownView(
    val ledgerId: LedgerId,
    val window: P806Window,
    val page: P806TransactionPage,
    val totalCount: Int,
)

/** The account-postings view: one page plus the exact posting count. */
data class P806AccountView(
    val ledgerId: LedgerId,
    val accountId: AccountId,
    val page: P806AccountPostingsPage,
    val totalCount: Int,
)
