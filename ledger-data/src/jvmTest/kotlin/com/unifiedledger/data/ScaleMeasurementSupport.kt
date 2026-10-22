package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.Locale

/*
 * Shared measurement helpers for the P7-07/P7-08 scale legs (plan sections 7.3 and 8.3,
 * section 10.3). They live in their own file so a scale test never has to import another
 * test class: deleting or renaming one scale test cannot break the compilation of the other.
 *
 * Every figure these helpers produce is RECORDED. Nothing here asserts a wall-clock or a
 * memory threshold; the tests that use them pin only structural properties (a non-zero
 * statement count, a row count that the fixture construction derives).
 */

/**
 * A second file connection to an existing fixture file that counts the SQL statements issued
 * while armed. `JdbcSqliteDriver` is wrapped by delegation, so every generated query still runs
 * on the real driver; [resetCounts] starts a measured window and [queryCount] reports its
 * `execute` + `executeQuery` total (the transaction-control statements the transacter issues are
 * counted the same way in every window, so a phase comparison isolates added reads).
 */
internal class CountingSqlDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var executeCount: Int = 0
        private set

    var executeQueryCount: Int = 0
        private set

    val queryCount: Int get() = executeCount + executeQueryCount

    fun resetCounts() {
        executeCount = 0
        executeQueryCount = 0
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        executeCount += 1
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        executeQueryCount += 1
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

/** Samples `totalMemory - freeMemory` every 10ms and keeps the maximum (JVM heap only). */
internal class JvmHeapPeakSampler private constructor() : AutoCloseable {
    private val runtime = Runtime.getRuntime()
    private var peakBytes: Long = 0L

    @Volatile
    private var running: Boolean = true

    private val thread =
        Thread {
            while (running) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peakBytes) peakBytes = used
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    running = false
                }
            }
        }

    fun stop(): Long {
        running = false
        thread.join()
        return peakBytes
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val SAMPLE_INTERVAL_MILLIS = 10L

        /** Starts a daemon sampling thread; [stop] joins it and returns the peak. */
        fun startSampling(): JvmHeapPeakSampler {
            val sampler = JvmHeapPeakSampler()
            sampler.thread.isDaemon = true
            sampler.thread.start()
            return sampler
        }
    }
}

/** A single-column `executeQuery` on whichever driver is handed in. */
internal fun queryLong(
    driver: SqlDriver,
    sql: String,
): Long =
    driver
        .executeQuery(
            null,
            sql,
            { cursor ->
                check(cursor.next().value)
                QueryResult.Value(requireNotNull(cursor.getLong(0)))
            },
            0,
        ).value

/** Locale-independent millisecond rendering so a printed reading is greppable on any host. */
internal fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
