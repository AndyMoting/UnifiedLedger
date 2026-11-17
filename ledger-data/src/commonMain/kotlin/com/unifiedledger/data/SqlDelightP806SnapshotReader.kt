package com.unifiedledger.data

import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.OrdinaryFlowClassification
import com.unifiedledger.application.P806AccountCurrencyTotal
import com.unifiedledger.application.P806AccountCursor
import com.unifiedledger.application.P806AccountPostingRow
import com.unifiedledger.application.P806AccountPostingsPage
import com.unifiedledger.application.P806AccountRequest
import com.unifiedledger.application.P806AccountSnapshot
import com.unifiedledger.application.P806CalendarDaySnapshot
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806CalendarSnapshot
import com.unifiedledger.application.P806CurrencyTransactionCount
import com.unifiedledger.application.P806_DETAIL_PAGE_SIZE
import com.unifiedledger.application.P806_MAX_DAY_BUCKETS
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806DrilldownSnapshot
import com.unifiedledger.application.P806Periods
import com.unifiedledger.application.P806PeriodResolution
import com.unifiedledger.application.P806ReportRequest
import com.unifiedledger.application.P806ReportSnapshot
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806Span
import com.unifiedledger.application.P806TransactionCursor
import com.unifiedledger.application.P806TransactionPage
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.isAxisCatalogConsistent
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P8-06 batch a (D-343; spec sections 3.4/3.5, Appendix A): the data-layer implementation of the
 * four composite read snapshots.
 *
 * Internal helper, not a second public port: [SqlDelightLedgerCurrentStateReadAdapter] delegates
 * to it so its constructor stays `LedgerDatabase`-only and every snapshot runs on ONE connection
 * inside ONE `transactionWithResult`.
 *
 * Ordering and failure mapping follow the frozen contracts:
 * - report: version -> ledger-wide projection probe -> span (the `ALL` window depends on it; the
 *   span read is registered in the C0 list) -> catalog-reference probe -> activity axis plus the
 *   shared axis rule over the caller's catalog -> aggregates/page/count;
 * - calendar: version -> projection probe -> reference probe over the month range -> axis plus the
 *   shared axis rule -> one amount bucket and one GLOBAL distinct count per local day;
 * - drilldown: version -> projection probe -> reference probe -> page (+1 look-ahead) -> count;
 * - account: version -> ledger/window reference probe -> `occurred_at` parse probe -> page -> count.
 *
 * No effective period at all (empty ledger) answers the explicit NoPeriod payload instead of a
 * zero-valued period (D-343 3.1.3（无期间即 NoPeriod）).
 *
 * A SQLite integer-`SUM` overflow is `InvalidState`; every other read failure is `Unavailable`
 * (D-343 3.8（整数溢出为 InvalidState）). An `occurred_at` that the frozen parser rejects is `OccurredAtUnparseable`
 * (D-343 3.2.2（发生时间数值键）), whether the probe or the page mapper meets it.
 */
internal class SqlDelightP806SnapshotReader(
    private val database: LedgerDatabase,
) {
    /** The six frozen ordinary kinds as bound parameters (single definition point upstream). */
    private val ordinaryKindNames: List<String> =
        OrdinaryFlowClassification.ORDINARY_KINDS.map { it.name }.sorted()

    /** The two contributing account kinds as bound parameters (never a financial-account filter). */
    private val contributingAccountKindNames: List<String> =
        listOf(AccountKind.EXPENSE.name, AccountKind.INCOME.name)

    fun report(request: P806ReportRequest): P806SnapshotResult<P806ReportSnapshot> =
        read {
            val version = catalogVersion(request.ledgerId) ?: return@read failedUnavailable()
            if (version != request.expectedCatalogVersion) return@read failed(P806SnapshotFailure.CatalogVersionMismatch)
            if (statisticsProjectionMissing(request.ledgerId)) return@read failed(P806SnapshotFailure.MissingProjection)
            // Geometry is validated before anything span-dependent: a bad or unrepresentable window
            // is InvalidWindow even when the ledger has no period at all (D-343 3.1.3).
            if (P806Periods.validateGeometry(request.period, request.today) == P806PeriodResolution.InvalidWindow) {
                return@read failed(P806SnapshotFailure.InvalidWindow)
            }
            val span = span(request.ledgerId)
            if (span == null) {
                // 无期间（NoPeriod）: no effective period at all. Every period kind answers NoPeriod, not zeros.
                return@read P806SnapshotResult.Success(
                    P806ReportSnapshot(
                        catalogVersion = version,
                        window = null,
                        span = null,
                        currencyAxis = emptyList(),
                        postingTotals = emptyList(),
                        transactionCount = 0,
                        currencyCounts = emptyList(),
                        futureTransactionCount = 0,
                        firstPage = P806TransactionPage(emptyList(), null),
                        detailCount = 0,
                    ),
                )
            }
            val resolved =
                when (val resolution = P806Periods.resolve(request.period, request.today, span)) {
                    is P806PeriodResolution.Resolved -> resolution.period
                    P806PeriodResolution.InvalidWindow -> return@read failed(P806SnapshotFailure.InvalidWindow)
                    // A non-null span always resolves ALL; treat any other outcome as no period.
                    P806PeriodResolution.NotApplicable -> return@read failed(P806SnapshotFailure.InvalidState)
                }
            val reference = statisticsCatalogReference(request.ledgerId, resolved.window)
            if (reference != null) return@read failed(reference)
            val axis = axis(request.ledgerId)
            if (!isAxisCatalogConsistent(axis, request.catalog, request.ledgerId)) {
                return@read failed(P806SnapshotFailure.InvalidState)
            }
            val window = resolved.window
            P806SnapshotResult.Success(
                P806ReportSnapshot(
                    catalogVersion = version,
                    window = window,
                    span = span,
                    currencyAxis = axis,
                    postingTotals = postingTotals(request.ledgerId, window),
                    transactionCount = ordinaryTransactionCount(request.ledgerId, window),
                    currencyCounts = currencyTransactionCounts(request.ledgerId, window),
                    futureTransactionCount = ordinaryFutureCount(request.ledgerId, window, request.futureFromEpochNanos),
                    firstPage = drilldownPage(request.ledgerId, window, categoryFilter = "", cursor = null, pageSize = P806_DETAIL_PAGE_SIZE),
                    detailCount = drilldownCount(request.ledgerId, window, categoryFilter = ""),
                ),
            )
        }

    fun calendar(request: P806CalendarRequest): P806SnapshotResult<P806CalendarSnapshot> =
        read {
            val version = catalogVersion(request.ledgerId) ?: return@read failedUnavailable()
            if (version != request.expectedCatalogVersion) return@read failed(P806SnapshotFailure.CatalogVersionMismatch)
            if (statisticsProjectionMissing(request.ledgerId)) return@read failed(P806SnapshotFailure.MissingProjection)
            val buckets =
                P806Periods.dayBuckets(request.month)
                    ?: return@read failed(P806SnapshotFailure.InvalidWindow)
            if (buckets.isEmpty() || buckets.size > P806_MAX_DAY_BUCKETS) {
                return@read failed(P806SnapshotFailure.InvalidWindow)
            }
            val monthWindow =
                P806Window(
                    startEpochNanos = buckets.first().window.startEpochNanos,
                    endEpochNanos = buckets.last().window.endEpochNanos,
                )
            val reference = statisticsCatalogReference(request.ledgerId, monthWindow)
            if (reference != null) return@read failed(reference)
            val axis = axis(request.ledgerId)
            if (!isAxisCatalogConsistent(axis, request.catalog, request.ledgerId)) {
                return@read failed(P806SnapshotFailure.InvalidState)
            }
            val days =
                buckets.map { bucket ->
                    P806CalendarDaySnapshot(
                        date = bucket.date,
                        window = bucket.window,
                        postingTotals = postingTotals(request.ledgerId, bucket.window),
                        transactionCount = ordinaryTransactionCount(request.ledgerId, bucket.window),
                    )
                }
            P806SnapshotResult.Success(
                P806CalendarSnapshot(
                    catalogVersion = version,
                    month = request.month,
                    currencyAxis = axis,
                    days = days,
                ),
            )
        }

    fun drilldown(request: P806DrilldownRequest): P806SnapshotResult<P806DrilldownSnapshot> =
        read {
            val version = catalogVersion(request.ledgerId) ?: return@read failedUnavailable()
            if (version != request.expectedCatalogVersion) return@read failed(P806SnapshotFailure.CatalogVersionMismatch)
            if (statisticsProjectionMissing(request.ledgerId)) return@read failed(P806SnapshotFailure.MissingProjection)
            val reference = statisticsCatalogReference(request.ledgerId, request.window)
            if (reference != null) return@read failed(reference)
            val filter = request.categoryId?.value ?: ""
            P806SnapshotResult.Success(
                P806DrilldownSnapshot(
                    catalogVersion = version,
                    window = request.window,
                    page = drilldownPage(request.ledgerId, request.window, filter, request.cursor, request.pageSize),
                    totalCount = drilldownCount(request.ledgerId, request.window, filter),
                ),
            )
        }

    fun account(request: P806AccountRequest): P806SnapshotResult<P806AccountSnapshot> =
        read {
            val version = catalogVersion(request.ledgerId) ?: return@read failedUnavailable()
            if (version != request.expectedCatalogVersion) return@read failed(P806SnapshotFailure.CatalogVersionMismatch)
            // The frozen reference probe keeps the ledger + window scope over ALL postings (never
            // narrowed to the requested account); the parse probe is the account's whole history.
            val reference = occurredCatalogReference(request.ledgerId, request.window)
            if (reference != null) return@read failed(reference)
            val unparseable = unparseableOccurredAt(request.ledgerId, request.accountId)
            if (unparseable > 0L) return@read failed(P806SnapshotFailure.OccurredAtUnparseable)
            P806SnapshotResult.Success(
                P806AccountSnapshot(
                    catalogVersion = version,
                    page = accountPage(request.ledgerId, request),
                    totalCount = accountCount(request.ledgerId, request),
                ),
            )
        }

    // ---------------------------------------------------------------- probes

    private fun catalogVersion(ledgerId: LedgerId): Long? =
        database.ledgerQueries.selectCatalogVersion(ledgerId.value).executeAsOneOrNull()

    private fun statisticsProjectionMissing(ledgerId: LedgerId): Boolean =
        database.ledgerQueries.statisticsAtProjectionMissingForLedger(ledgerId.value).executeAsOne() != 0L

    /**
     * The ledger span from the numeric projections only (D-343 3.1.2（跨度只读数值投影）). The ledger-wide projection
     * probe already ran, so a NULL MIN/MAX here is the genuine "no effective period" answer; the
     * legacy TEXT column is never read.
     */
    private fun span(ledgerId: LedgerId): P806Span? {
        val earliest =
            database.ledgerQueries.earliestEffectiveStatisticsAtNanos(ledgerId.value).executeAsOneOrNull()
                ?: return null
        val latest =
            database.ledgerQueries.p806LatestEffectiveStatisticsAtNanos(ledgerId.value).executeAsOneOrNull()
                ?: return null
        val earliestNanos = earliest.earliest_nanos ?: return null
        val latestNanos = latest.latest_nanos ?: return null
        if (latestNanos < earliestNanos) return null
        return P806Span(earliest = instantOf(earliestNanos), latest = instantOf(latestNanos))
    }

    private fun statisticsCatalogReference(
        ledgerId: LedgerId,
        window: P806Window,
    ): P806SnapshotFailure.CatalogReference? {
        val row =
            database.ledgerQueries
                .p806StatisticsCatalogConsistencyProbe(
                    ledger_id = ledgerId.value,
                    window_start_nanos = window.startEpochNanos,
                    window_end_nanos = window.endEpochNanos,
                ).executeAsOne()
        return referenceFailure(row.unresolved_account ?: 0L, row.cross_ledger_account ?: 0L, row.currency_mismatch ?: 0L)
    }

    private fun occurredCatalogReference(
        ledgerId: LedgerId,
        window: P806Window?,
    ): P806SnapshotFailure.CatalogReference? {
        val row =
            database.ledgerQueries
                .p806OccurredAtCatalogConsistencyProbe(
                    ledger_id = ledgerId.value,
                    window_start_nanos = window?.startEpochNanos ?: Long.MIN_VALUE,
                    window_end_nanos = window?.endEpochNanos ?: Long.MAX_VALUE,
                ).executeAsOne()
        return referenceFailure(row.unresolved_account ?: 0L, row.cross_ledger_account ?: 0L, row.currency_mismatch ?: 0L)
    }

    private fun referenceFailure(
        unresolved: Long,
        crossLedger: Long,
        currencyMismatch: Long,
    ): P806SnapshotFailure.CatalogReference? =
        if (unresolved == 0L && crossLedger == 0L && currencyMismatch == 0L) {
            null
        } else {
            P806SnapshotFailure.CatalogReference(
                unresolvedAccount = unresolved,
                crossLedgerAccount = crossLedger,
                currencyMismatch = currencyMismatch,
            )
        }

    private fun unparseableOccurredAt(
        ledgerId: LedgerId,
        accountId: AccountId,
    ): Long =
        database.ledgerQueries
            .p806OccurredAtParseFailureProbe(ledger_id = ledgerId.value, account_id = accountId.value)
            .executeAsOne() ?: 0L

    // ---------------------------------------------------------------- aggregates

    private fun axis(ledgerId: LedgerId): List<CurrencyUnit> =
        database.ledgerQueries
            .ledgerActivityCurrencies(ledgerId.value)
            .executeAsList()
            .map { CurrencyUnit(it.currency_code, it.currency_precision.toInt()) }

    private fun postingTotals(
        ledgerId: LedgerId,
        window: P806Window,
    ): List<P806AccountCurrencyTotal> =
        database.ledgerQueries
            .p806OrdinaryPostingTotals(
                ledger_id = ledgerId.value,
                window_start_nanos = window.startEpochNanos,
                window_end_nanos = window.endEpochNanos,
                kind1 = ordinaryKindNames[0],
                kind2 = ordinaryKindNames[1],
                kind3 = ordinaryKindNames[2],
                kind4 = ordinaryKindNames[3],
                kind5 = ordinaryKindNames[4],
                kind6 = ordinaryKindNames[5],
                contrib_kind1 = contributingAccountKindNames[0],
                contrib_kind2 = contributingAccountKindNames[1],
            ).executeAsList()
            .map { row ->
                P806AccountCurrencyTotal(
                    accountId = AccountId(row.account_id),
                    currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                    positiveMinorUnits = row.positive_minor ?: 0L,
                    refundMinorUnits = row.refund_minor ?: 0L,
                    totalMinorUnits = row.total_minor ?: 0L,
                )
            }

    private fun ordinaryTransactionCount(
        ledgerId: LedgerId,
        window: P806Window,
    ): Int =
        database.ledgerQueries
            .p806OrdinaryTransactionCount(
                ledger_id = ledgerId.value,
                window_start_nanos = window.startEpochNanos,
                window_end_nanos = window.endEpochNanos,
                kind1 = ordinaryKindNames[0],
                kind2 = ordinaryKindNames[1],
                kind3 = ordinaryKindNames[2],
                kind4 = ordinaryKindNames[3],
                kind5 = ordinaryKindNames[4],
                kind6 = ordinaryKindNames[5],
                contrib_kind1 = contributingAccountKindNames[0],
                contrib_kind2 = contributingAccountKindNames[1],
            ).executeAsOne().countOrFail()

    private fun currencyTransactionCounts(
        ledgerId: LedgerId,
        window: P806Window,
    ): List<P806CurrencyTransactionCount> =
        database.ledgerQueries
            .p806OrdinaryCurrencyTransactionCounts(
                ledger_id = ledgerId.value,
                window_start_nanos = window.startEpochNanos,
                window_end_nanos = window.endEpochNanos,
                kind1 = ordinaryKindNames[0],
                kind2 = ordinaryKindNames[1],
                kind3 = ordinaryKindNames[2],
                kind4 = ordinaryKindNames[3],
                kind5 = ordinaryKindNames[4],
                kind6 = ordinaryKindNames[5],
                contrib_kind1 = contributingAccountKindNames[0],
                contrib_kind2 = contributingAccountKindNames[1],
            ).executeAsList()
            .map { row ->
                P806CurrencyTransactionCount(
                    currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                    transactionCount = row.transaction_count.countOrFail(),
                )
            }

    private fun ordinaryFutureCount(
        ledgerId: LedgerId,
        window: P806Window,
        futureFromEpochNanos: Long,
    ): Int =
        database.ledgerQueries
            .p806OrdinaryFutureTransactionCount(
                ledger_id = ledgerId.value,
                window_start_nanos = window.startEpochNanos,
                window_end_nanos = window.endEpochNanos,
                future_from_nanos = futureFromEpochNanos,
                kind1 = ordinaryKindNames[0],
                kind2 = ordinaryKindNames[1],
                kind3 = ordinaryKindNames[2],
                kind4 = ordinaryKindNames[3],
                kind5 = ordinaryKindNames[4],
                kind6 = ordinaryKindNames[5],
                contrib_kind1 = contributingAccountKindNames[0],
                contrib_kind2 = contributingAccountKindNames[1],
            ).executeAsOne().countOrFail()

    // ---------------------------------------------------------------- pages

    private fun drilldownPage(
        ledgerId: LedgerId,
        window: P806Window,
        categoryFilter: String,
        cursor: P806TransactionCursor?,
        pageSize: Int,
    ): P806TransactionPage {
        require(pageSize > 0) { "page size must be positive" }
        // D-343 3.4（键集页 look-ahead）: read one extra transaction so the next cursor is the real continuation.
        val raw =
            database.ledgerQueries
                .p806OrdinaryDrilldownPage(
                    ledger_id = ledgerId.value,
                    window_start_nanos = window.startEpochNanos,
                    window_end_nanos = window.endEpochNanos,
                    kind1 = ordinaryKindNames[0],
                    kind2 = ordinaryKindNames[1],
                    kind3 = ordinaryKindNames[2],
                    kind4 = ordinaryKindNames[3],
                    kind5 = ordinaryKindNames[4],
                    kind6 = ordinaryKindNames[5],
                    contrib_kind1 = contributingAccountKindNames[0],
                    contrib_kind2 = contributingAccountKindNames[1],
                    category_filter = categoryFilter,
                    cursor_nanos = cursor?.statisticsAtEpochNanos,
                    cursor_transaction_id = cursor?.transactionId ?: "",
                    page_size = pageSize.toLong() + 1L,
                ).executeAsList()
        val grouped = raw.groupBy { it.transaction_id }
        val pageGroups = grouped.entries.take(pageSize)
        val nextCursor =
            if (grouped.size > pageSize) {
                pageGroups.lastOrNull()?.value?.lastOrNull()?.let { row ->
                    P806TransactionCursor(
                        statisticsAtEpochNanos =
                            row.statistics_at_epoch_nanos
                                ?: throw P806InvalidProjection("page row without the numeric statistics projection"),
                        transactionId = row.transaction_id,
                    )
                }
            } else {
                null
            }
        val rows =
            pageGroups
                .flatMap { it.value }
                .map { row ->
                    RawEntryRow(
                        transactionId = row.transaction_id,
                        currentVersionId = row.current_version_id,
                        kind = row.kind,
                        occurredAt = row.occurred_at,
                        statisticsAt = row.statistics_at,
                        note = row.note,
                        postingId = row.posting_id,
                        postingIndex = row.posting_index,
                        accountId = row.account_id,
                        amountMinor = row.amount_minor,
                        currencyCode = row.currency_code,
                        currencyPrecision = row.currency_precision,
                    )
                }.toEntryRows()
        return P806TransactionPage(rows, nextCursor)
    }

    private fun drilldownCount(
        ledgerId: LedgerId,
        window: P806Window,
        categoryFilter: String,
    ): Int =
        database.ledgerQueries
            .p806OrdinaryDrilldownCount(
                ledger_id = ledgerId.value,
                window_start_nanos = window.startEpochNanos,
                window_end_nanos = window.endEpochNanos,
                kind1 = ordinaryKindNames[0],
                kind2 = ordinaryKindNames[1],
                kind3 = ordinaryKindNames[2],
                kind4 = ordinaryKindNames[3],
                kind5 = ordinaryKindNames[4],
                kind6 = ordinaryKindNames[5],
                contrib_kind1 = contributingAccountKindNames[0],
                contrib_kind2 = contributingAccountKindNames[1],
                category_filter = categoryFilter,
            ).executeAsOne().countOrFail()

    private fun accountPage(
        ledgerId: LedgerId,
        request: P806AccountRequest,
    ): P806AccountPostingsPage {
        val rows =
            database.ledgerQueries
                .p806AccountPostingsPage(
                    ledger_id = ledgerId.value,
                    account_id = request.accountId.value,
                    window_start_nanos = request.window?.startEpochNanos ?: Long.MIN_VALUE,
                    window_end_nanos = request.window?.endEpochNanos ?: Long.MAX_VALUE,
                    cursor_nanos = request.cursor?.occurredAtEpochNanos,
                    cursor_posting_id = request.cursor?.postingId ?: "",
                    page_size = request.pageSize.toLong() + 1L,
                ).executeAsList()
        val pageRows = rows.take(request.pageSize)
        val nextCursor =
            if (rows.size > request.pageSize) {
                pageRows.lastOrNull()?.let { row ->
                    P806AccountCursor(
                        // The CASE projection is nullable in the generated type; the WHERE clause
                        // filters NULL rows, but that is a query predicate, not a Kotlin guarantee.
                        // Reject an absent key explicitly instead of inventing 0 (a legal epoch 0
                        // parses to a non-null 0 and still passes).
                        occurredAtEpochNanos =
                            row.occurred_at_nanos
                                ?: throw P806InvalidProjection("page row without the parsed occurred_at key"),
                        postingId = row.posting_id,
                    )
                }
            } else {
                null
            }
        val mapped =
            pageRows.map { row ->
                P806AccountPostingRow(
                    postingId = PostingId(row.posting_id),
                    transactionId = TransactionId(row.transaction_id),
                    occurredAt = parseOccurredAt(row.occurred_at),
                    note = row.note,
                    kind = TransactionKind.valueOf(row.kind),
                    amount =
                        Money.ofMinor(
                            row.amount_minor,
                            CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                        ),
                )
            }
        return P806AccountPostingsPage(mapped, nextCursor)
    }

    private fun accountCount(
        ledgerId: LedgerId,
        request: P806AccountRequest,
    ): Int =
        database.ledgerQueries
            .p806AccountPostingsCount(
                ledger_id = ledgerId.value,
                account_id = request.accountId.value,
                window_start_nanos = request.window?.startEpochNanos ?: Long.MIN_VALUE,
                window_end_nanos = request.window?.endEpochNanos ?: Long.MAX_VALUE,
            ).executeAsOne().countOrFail()

    // ---------------------------------------------------------------- plumbing

    private fun <T> read(block: () -> P806SnapshotResult<T>): P806SnapshotResult<T> =
        try {
            database.transactionWithResult(noEnclosing = true) { block() }
        } catch (parsed: P806OccurredAtUnparseable) {
            P806SnapshotResult.Failed(P806SnapshotFailure.OccurredAtUnparseable)
        } catch (projection: P806InvalidProjection) {
            // This batch's own projection/mapping integrity failure (missing or out-of-range count,
            // missing numeric key, unparseable statistics text): InvalidState, never a zero.
            P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
        } catch (failure: Exception) {
            // D-343 3.8（整数溢出为 InvalidState）: an INTEGER SUM overflow is a state failure, not a retryable read failure.
            if (failure.message?.contains("integer overflow", ignoreCase = true) == true) {
                P806SnapshotResult.Failed(P806SnapshotFailure.InvalidState)
            } else {
                P806SnapshotResult.Failed(P806SnapshotFailure.Unavailable)
            }
        }

    private fun <T> failed(failure: P806SnapshotFailure): P806SnapshotResult<T> = P806SnapshotResult.Failed(failure)

    private fun <T> failedUnavailable(): P806SnapshotResult<T> = failed(P806SnapshotFailure.Unavailable)

    /**
     * A COUNT projection is data, not a default: a null/negative/over-Int value is a broken
     * projection (InvalidState), while SQL's zero for an empty set stays a real zero.
     */
    private fun Long?.countOrFail(): Int {
        val value = this ?: throw P806InvalidProjection("count projection is absent")
        if (value < 0L || value > Int.MAX_VALUE.toLong()) {
            throw P806InvalidProjection("count projection is outside the representable range")
        }
        return value.toInt()
    }

    /** Exact instant from the numeric projection (never milliseconds, never the TEXT fallback). */
    private fun instantOf(epochNanos: Long): Instant {
        val seconds = epochNanos / 1_000_000_000L
        val adjustment = epochNanos % 1_000_000_000L
        return if (adjustment < 0L) {
            Instant.fromEpochSeconds(seconds - 1L, (adjustment + 1_000_000_000L).toInt())
        } else {
            Instant.fromEpochSeconds(seconds, adjustment.toInt())
        }
    }

    /** A page row's persisted time text that cannot be parsed is a projection break, not a read failure. */
    private fun parseProjectionInstant(
        text: String,
        column: String,
    ): Instant =
        try {
            Instant.parse(text)
        } catch (rejected: Exception) {
            throw P806InvalidProjection("page row $column does not parse")
        }

    private fun parseOccurredAt(text: String): Instant =
        try {
            Instant.parse(text)
        } catch (rejected: Exception) {
            // The probe should have refused this request already; the mapper stays typed anyway.
            throw P806OccurredAtUnparseable()
        }

    private data class RawEntryRow(
        val transactionId: String,
        val currentVersionId: String,
        val kind: String,
        val occurredAt: String,
        val statisticsAt: String,
        val note: String?,
        val postingId: String,
        val postingIndex: Long,
        val accountId: String,
        val amountMinor: Long,
        val currencyCode: String,
        val currencyPrecision: Long,
    )

    private fun List<RawEntryRow>.toEntryRows(): List<LedgerEntryRow> =
        groupBy { it.transactionId }
            .map { (_, groupedRows) ->
                val first = groupedRows.first()
                LedgerEntryRow(
                    transactionId = TransactionId(first.transactionId),
                    currentVersionId = TransactionVersionId(first.currentVersionId),
                    kind = TransactionKind.valueOf(first.kind),
                    occurredAt = parseProjectionInstant(first.occurredAt, "occurred_at"),
                    statisticsAt = parseProjectionInstant(first.statisticsAt, "statistics_at"),
                    note = first.note,
                    postings =
                        groupedRows
                            .sortedBy { it.postingIndex }
                            .map { row ->
                                Posting(
                                    id = PostingId(row.postingId),
                                    accountId = AccountId(row.accountId),
                                    amount =
                                        Money.ofMinor(
                                            row.amountMinor,
                                            CurrencyUnit(row.currencyCode, row.currencyPrecision.toInt()),
                                        ),
                                )
                            },
                )
            }
}

/** Internal marker: a page row's `occurred_at` failed the frozen parser. */
private class P806OccurredAtUnparseable : Exception("P8-06 occurred_at failed the frozen parser")

/** Internal marker: this batch's own projection/mapping integrity failure. */
private class P806InvalidProjection(message: String) : Exception(message)
