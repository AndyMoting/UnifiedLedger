package com.unifiedledger.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.P806DayBucket
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806PeriodResolution
import com.unifiedledger.application.P806Periods
import com.unifiedledger.application.P806ResolvedPeriod
import com.unifiedledger.data.SqlDelightLedgerCurrentStateReadAdapter
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.ImportFilePickResultChannel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * P8-06 batch 6 V-14 device fixture (spec section 4 V-14): the anonymous 50,000-transaction,
 * SINGLE-MONTH ledger the composite-read timing case measures, built ON THE DEVICE.
 *
 * WHY THIS FILE CARRIES NO TEST ANNOTATION: the inventory enumerator takes the FIRST `class` keyword
 * of a file and skips files without a test annotation (the `P801ChainFixture` /
 * `P805ReadableExportDeviceFixture` precedent), so the device case lives in
 * `P806ReportDeviceTimingInstrumentedTest.kt` and this helper never enters the inventory.
 *
 * SAMPLE SHAPE (anonymous, deterministic): every transaction is a two-leg EXPENSE chain on the
 * default catalog's own accounts (leg 0 +amount on the hidden expense account, leg 1 -amount on the
 * default payment account), written with chunked multi-row INSERTs in ONE transaction (500 rows per
 * statement), the P8-02/P8-05 budget arithmetic. Amount = 1000 + (index % 97) * 137 minor units.
 * Statistics instants are the device clock's CURRENT Asia/Shanghai month, one day per index modulo
 * the month's length at 12:00 local, so the report's default MONTH period and the calendar's
 * current month both cover the whole sample on any dispatch day.
 *
 * THE MEASUREMENT DRIVER ([P806TimingSqlDriver]) wraps the REAL androidx-backed
 * [AndroidSqliteDriver] by delegation: every generated statement still runs on the product driver,
 * and each [execute]/[executeQuery] is timed with the monotonic clock. It keeps four separately
 * attributable readings — all statements, the period-amount statement (`AS positive_minor`), the
 * drilldown PAGE statement (`WITH page_ids AS`), and the drilldown COUNT statement (the distinct
 * transaction count over `catalog_category`) — so the report period amount, the calendar buckets,
 * the first page and the count are never conflated. Nothing here asserts a duration; the test
 * records the readings and pins only structural facts.
 */
internal object P806ReportDeviceTimingFacts {
    /** The product's fixed single-ledger identity (`App.kt`). */
    val ledgerId: LedgerId = LedgerId(PRODUCT_LEDGER_ID)

    /** The fixed sample size (spec section 4 V-14: 50k 级读数). */
    const val TIER_50K: Int = 50_000

    /** Rows per multi-row INSERT statement (the P8-02 fixture's chunk). */
    const val CHUNK: Int = 500

    /** The amount spread: `AMOUNT_BASE + (index % AMOUNT_STRIDE) * AMOUNT_STEP` minor units. */
    const val AMOUNT_BASE: Long = 1_000L
    const val AMOUNT_STRIDE: Int = 97
    const val AMOUNT_STEP: Long = 137L

    /** The default seed's owned asset account (leg 1) and hidden expense account (leg 0). */
    const val ASSET_ACCOUNT_ID: String = DEFAULT_MANAGEABLE_ACCOUNT_ID
    const val EXPENSE_ACCOUNT_ID: String = DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID

    /** The cached-redirect root prefix, so no other suite's root can collide. */
    const val ROOT_PREFIX: String = "p806-device-timing-"

    /** The drilldown page statement's own opening keyword (the generated query is a CTE). */
    const val PAGE_STATEMENT_MARKER: String = "WITH page_ids AS"

    /**
     * The period-amount statement's own projection alias. `p806OrdinaryPostingTotals` is the only
     * generated statement that selects `AS positive_minor`, so this marker attributes the
     * `periodAmountMs` reading to that SQL and no other.
     */
    const val POSTING_TOTALS_MARKER: String = "AS positive_minor"

    /**
     * The drilldown COUNT statement's own conjunction. `count(DISTINCT version.transaction_id)`
     * also appears in the report's count/currency-count/future statements, so the category column
     * (present only in the drilldown count's `catalog_category` CTE) is what makes the family
     * unambiguous.
     */
    const val COUNT_STATEMENT_MARKER: String = "count(DISTINCT version.transaction_id)"
    const val COUNT_CATEGORY_MARKER: String = "catalog_category"

    /**
     * The exact statement counts of each measured production read, derived from
     * `SqlDelightP806SnapshotReader`'s call graph:
     * - report: version, projection probe, span (earliest + latest), reference probe, activity
     *   axis, posting totals, ordinary count, currency counts, future count, first page, detail
     *   count = 12;
     * - calendar: version, projection probe, reference probe, axis + 2 per day bucket = 4 + 2*n;
     * - drilldown: version, projection probe, reference probe, page, count = 5.
     */
    const val REPORT_STATEMENT_COUNT: Int = 12
    const val CALENDAR_FIXED_STATEMENT_COUNT: Int = 4
    const val DRILLDOWN_STATEMENT_COUNT: Int = 5
}

/**
 * The timing driver proxy: delegates every call to the real driver and accumulates monotonic-clock
 * readings for the all-statements / page / count classes.
 */
internal class P806TimingSqlDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    /** One accumulated reading: elapsed nanoseconds and the number of statements that produced it. */
    internal class Reading {
        var nanos: Long = 0L
            private set
        var statements: Int = 0
            private set

        fun add(elapsedNanos: Long) {
            nanos += elapsedNanos
            statements += 1
        }

        fun clear() {
            nanos = 0L
            statements = 0
        }

        /** Locale-independent milliseconds, three places (the recorded unit). */
        fun millis(): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
    }

    val total: Reading = Reading()
    val postingTotals: Reading = Reading()
    val page: Reading = Reading()
    val count: Reading = Reading()

    fun reset() {
        total.clear()
        postingTotals.clear()
        page.clear()
        count.clear()
    }

    private fun record(
        sql: String,
        elapsedNanos: Long,
    ) {
        total.add(elapsedNanos)
        when {
            sql.contains(P806ReportDeviceTimingFacts.POSTING_TOTALS_MARKER) -> postingTotals.add(elapsedNanos)
            sql.contains(P806ReportDeviceTimingFacts.COUNT_STATEMENT_MARKER) &&
                sql.contains(P806ReportDeviceTimingFacts.COUNT_CATEGORY_MARKER) -> count.add(elapsedNanos)
            sql.contains(P806ReportDeviceTimingFacts.PAGE_STATEMENT_MARKER) -> page.add(elapsedNanos)
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val start = System.nanoTime()
        val result = delegate.execute(identifier, sql, parameters, binders)
        record(sql, System.nanoTime() - start)
        return result
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val start = System.nanoTime()
        // AndroidSqliteDriver executes and maps eagerly inside this call, so the elapsed span
        // covers real SQL plus the mapper's row consumption.
        val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        record(sql, System.nanoTime() - start)
        return result
    }
}

/** One built sample: its reopened product read graph, derived period and construction manifest. */
internal class P806ReportDeviceTimingFixture(
    testName: String,
) : AutoCloseable {
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root: File = File(target.cacheDir, P806ReportDeviceTimingFacts.ROOT_PREFIX + testName)

    /** The P8-01a redirect guard, reused unchanged (one guard, not a second copy). */
    private val context: Context = p801RedirectedContext(root, target)

    private var driverName: String = ""

    /** The real driver wrapped by the measurement proxy. */
    lateinit var timingDriver: P806TimingSqlDriver
        private set

    /** The product composite-read adapter over the measured driver. */
    lateinit var adapter: SqlDelightLedgerCurrentStateReadAdapter
        private set

    /** The ledger's immutable catalog of the generation the sample was written under. */
    lateinit var catalog: LedgerCatalog
        private set

    /** The authoritative catalog version the composite requests name (the bootstrap's 1). */
    var catalogVersion: Long = 0L
        private set

    /** The device clock's current reporting-zone date and month. */
    lateinit var today: LocalDate
        private set

    lateinit var month: YearMonth
        private set

    /** The MONTH period resolved exactly as the production caller resolves it. */
    lateinit var period: P806ResolvedPeriod
        private set

    /** The first instant that counts as a future date (the next local midnight). */
    var futureFromEpochNanos: Long = 0L
        private set

    /**
     * The current month's natural day buckets, captured ONCE from the device clock BEFORE the
     * fixture rows are written. The writer and every expectation below reuse this list, so a
     * midnight (or month) rollover during the write cannot split the sample across two shapes.
     */
    lateinit var dayBuckets: List<P806DayBucket>
        private set

    /** The exact positive (expense-account) minor units over the whole month. */
    var expectedPeriodPositiveMinor: Long = 0L
        private set

    /** The exact contributing transaction count of each day bucket, in bucket order. */
    var expectedDayTransactionCounts: List<Int> = emptyList()
        private set

    /** The exact positive minor units contributed on each day bucket, in bucket order. */
    var expectedDayPositiveMinor: List<Long> = emptyList()
        private set

    /** Transactions whose statistics instant is at/after [futureFromEpochNanos]. */
    var expectedFutureTransactionCount: Int = 0
        private set

    /** The transactions the raw writer left behind (read back from the file). */
    var transactionCount: Long = 0L
        private set

    /** Rows whose numeric statistics projection is still NULL; must stay 0 or the read fails. */
    var missingProjectionRows: Long = 0L
        private set

    /** The generation file's `PRAGMA user_version` (the frozen schema version). */
    var schemaVersion: Long = 0L
        private set

    /**
     * D7-11's build shape: open the product composition root, close it, write the synthetic rows on
     * this fixture's own connection, then read the catalog back through the product handle and open
     * the SAME file through a measured driver.
     */
    fun build(transactionCount: Int = P806ReportDeviceTimingFacts.TIER_50K) {
        check(driverName.isEmpty()) { "the fixture builds exactly once" }
        openProductGraph().close()
        check(driverName.isNotEmpty()) { "the product open must name its generation file" }
        captureSampleShape(transactionCount)
        writeFixtureRows(transactionCount)
        openMeasuredGraph()
        check(transactionCount.toLong() == this.transactionCount) {
            "the sample must hold $transactionCount transactions, found ${this.transactionCount}"
        }
        check(missingProjectionRows == 0L) { "every version row must carry its statistics projection" }
    }

    /** Closes the measured driver and removes this case's whole ledger root. */
    override fun close() {
        if (::timingDriver.isInitialized) timingDriver.close()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ the product open

    private fun openProductGraph(): CloseableLedgerGraph =
        openAndroidStableStorageLedger(
            context = context,
            importFilePickPort = silentImportPort(),
            importPickChannel = ImportFilePickResultChannel(),
            openDriver = { name ->
                driverName = name
                createAndroidLedgerDatabase(context, name)
            },
        )

    /** The silent SAF port the open sequence requires; this fixture never launches a picker. */
    private fun silentImportPort(): AndroidImportFilePickPort<Uri> =
        AndroidImportFilePickPort<Uri>(
            launchOpenDocument = {},
            resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
            openInputStream = { null },
            onResult = {},
        )

    // ------------------------------------------------------------------ the measured reopen

    /**
     * Read the device clock ONCE and derive the month, period, day buckets and every exact
     * expectation from the SAME instant. Runs before the rows are written; the reopen only reads
     * the file through these captured values.
     */
    private fun captureSampleShape(count: Int) {
        today =
            Clock.System
                .now()
                .toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE)
                .date
        month = YearMonth(today.year, today.month)
        period =
            checkNotNull(
                (P806Periods.resolve(P806Period(P806PeriodKind.MONTH), today, span = null) as? P806PeriodResolution.Resolved),
            ) { "the current month must resolve" }.period
        futureFromEpochNanos = P806Periods.nextLocalMidnightEpochNanos(today)
        dayBuckets = P806Periods.dayBuckets(month) ?: error("the current month must have day buckets")
        val counts = IntArray(dayBuckets.size)
        val positive = LongArray(dayBuckets.size)
        var periodPositive = 0L
        var future = 0
        for (index in 0 until count) {
            val amount = amountMinor(index)
            val day = index % dayBuckets.size
            counts[day] += 1
            positive[day] += amount
            periodPositive += amount
            if (statisticsNanos(index, dayBuckets) >= futureFromEpochNanos) future += 1
        }
        expectedPeriodPositiveMinor = periodPositive
        expectedDayTransactionCounts = counts.toList()
        expectedDayPositiveMinor = positive.toList()
        expectedFutureTransactionCount = future
    }

    private fun openMeasuredGraph() {
        // The catalog load runs on the platform-configured product handle BEFORE the measured
        // connection exists: the store's `LedgerDatabase + SqlDriver` constructor issues the
        // JDBC-only `PRAGMA busy_timeout`, which Android's `execute` rejects
        // (`AndroidLedgerDatabaseHandle`'s `ForeignKeysCallback` note). This read is initialization
        // outside the timing window; the measured driver below serves only the four readings.
        val authority =
            createAndroidLedgerDatabase(context, driverName).use { handle ->
                checkNotNull(handle.catalogStore.load(P806ReportDeviceTimingFacts.ledgerId)) {
                    "the reopened file must carry the product's default catalog"
                }
            }
        catalog = authority.catalog
        catalogVersion = authority.catalogVersion
        val real = AndroidSqliteDriver(LedgerDatabase.Schema, context, driverName)
        timingDriver = P806TimingSqlDriver(real)
        val database = LedgerDatabase(timingDriver)
        adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
    }

    // ------------------------------------------------------------------ the raw write

    private fun writeFixtureRows(count: Int) {
        val mainFile = File(context.getDatabasePath(driverName).absolutePath)
        check(mainFile.isFile) { "the product open must have created the generation file: ${mainFile.path}" }
        SQLiteDatabase.openDatabase(mainFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            val preexisting = longQuery(database, "SELECT count(*) FROM ledger_transaction")
            check(preexisting == 0L) { "the product's fresh ledger must start with no transactions, found $preexisting" }
            database.beginTransaction()
            try {
                writeTransactions(database, count)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            transactionCount = longQuery(database, "SELECT count(*) FROM ledger_transaction")
            missingProjectionRows =
                longQuery(database, "SELECT count(*) FROM transaction_version WHERE statistics_at_epoch_nanos IS NULL")
            schemaVersion = longQuery(database, "PRAGMA user_version")
        }
    }

    private fun writeTransactions(
        database: SQLiteDatabase,
        count: Int,
    ) {
        val ledger = P806ReportDeviceTimingFacts.ledgerId.value
        val facts = P806ReportDeviceTimingFacts
        val days = dayBuckets
        insertChunked(
            database,
            "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ",
            count,
        ) { index -> "('${transactionId(index)}', '$ledger', 'EXPENSE', NULL)" }
        insertChunked(
            database,
            "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ",
            count,
        ) { index -> "('${postingSetId(index)}', '$ledger')" }
        insertChunked(
            database,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, " +
                "occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) VALUES ",
            count,
        ) { index ->
            val nanos = statisticsNanos(index, days)
            val text = Instant.fromEpochSeconds(nanos / 1_000_000_000L).toString()
            "('${versionId(index)}', '${transactionId(index)}', '$ledger', 1, '${postingSetId(index)}', " +
                "'$text', '$text', '$text', NULL, $nanos)"
        }
        insertChunked(
            database,
            "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, " +
                "currency_code, currency_precision) VALUES ",
            count * POSTINGS_PER_CHAIN,
        ) { ordinal ->
            val index = ordinal / POSTINGS_PER_CHAIN
            val leg = ordinal % POSTINGS_PER_CHAIN
            val amount = amountMinor(index)
            val (account, signedAmount) =
                if (leg == 0) facts.EXPENSE_ACCOUNT_ID to amount else facts.ASSET_ACCOUNT_ID to -amount
            "('${postingId(index, leg)}', '${postingSetId(index)}', '$ledger', $leg, '$account', $signedAmount, " +
                "'${CATALOG_MANAGED_CURRENCY.code}', ${CATALOG_MANAGED_CURRENCY.precision})"
        }
        insertChunked(
            database,
            "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ",
            count,
        ) { index -> "('${transactionId(index)}', '$ledger', '${versionId(index)}')" }
    }

    // ------------------------------------------------------------------ shared row helpers

    /** The `index`-th statistics instant: one day of the current month, at 12:00 reporting-zone. */
    private fun statisticsNanos(
        index: Int,
        days: List<P806DayBucket>,
    ): Long {
        val bucket = days[index % days.size]
        return bucket.window.startEpochNanos + 12.hours.inWholeNanoseconds
    }

    private fun amountMinor(index: Int): Long =
        P806ReportDeviceTimingFacts.AMOUNT_BASE +
            (index % P806ReportDeviceTimingFacts.AMOUNT_STRIDE) * P806ReportDeviceTimingFacts.AMOUNT_STEP

    private fun transactionId(index: Int): String = "p806v14-${(index + 1).toString().padStart(5, '0')}"

    private fun postingSetId(index: Int): String = "${transactionId(index)}-posting-set-1"

    private fun versionId(index: Int): String = "${transactionId(index)}-version-1"

    private fun postingId(
        index: Int,
        leg: Int,
    ): String = "${transactionId(index)}-posting-$leg"

    private fun insertChunked(
        database: SQLiteDatabase,
        statementPrefix: String,
        count: Int,
        row: (Int) -> String,
    ) {
        var start = 0
        while (start < count) {
            val end = minOf(start + P806ReportDeviceTimingFacts.CHUNK, count)
            database.execSQL(statementPrefix + (start until end).joinToString(", ") { row(it) })
            start = end
        }
    }

    private fun longQuery(
        database: SQLiteDatabase,
        sql: String,
    ): Long =
        database.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst()) { "the fixture probe returned no row: $sql" }
            cursor.getLong(0)
        }

    private companion object {
        const val POSTINGS_PER_CHAIN = 2
    }
}
