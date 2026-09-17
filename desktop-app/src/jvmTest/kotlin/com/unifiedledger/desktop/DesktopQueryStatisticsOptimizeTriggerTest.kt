package com.unifiedledger.desktop

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A-PERF (P7-04 read-governance batch, spec section 2.1 / 5): JVM structural evidence that the
 * layer-0 statistics-refresh trigger points are wired exactly as the spec pins them, with the
 * minimal injection surface of this batch — a delegating [SqlDriver] wrapper that records every
 * `executeQuery` SQL (the recording-proxy precedent of ForeignKeysCallbackCorruptionTest; no
 * mock library). The `PRAGMA optimize` statements are observable through the wrapper, and the
 * real JdbcSqliteDriver keeps the semantics genuine:
 *
 * - the bootstrap-completion trigger: `buildLedgerGraph` runs `PRAGMA optimize` exactly once,
 *   right after `bootstrapAuthority` (the SQLite-recommended open-time pattern; zero DDL);
 * - the intake-completion trigger: the facade's shared `importIntakeStatisticsRefresh` hook
 *   (what the commonMain `runImportIntakePipeline` invokes off the UI thread after the intake
 *   transaction) reaches the same controlled entry — the desktop graph method the spec's
 *   CloseableLedgerGraph interface-gap clause adds.
 */
class DesktopQueryStatisticsOptimizeTriggerTest {
    /** Delegating driver recording every executeQuery SQL for the assertions. */
    private class RecordingDriver(
        private val delegate: JdbcSqliteDriver,
    ) : SqlDriver {
        val executeQuerySql = mutableListOf<String>()
        val executeSql = mutableListOf<String>()

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            executeQuerySql += sql
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            executeSql += sql
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun close() = delegate.close()

        override fun currentTransaction(): Transacter.Transaction = checkNotNull(delegate.currentTransaction())

        override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

        override fun addListener(
            vararg queryKeys: String,
            listener: Query.Listener,
        ) = delegate.addListener(*queryKeys, listener = listener)

        override fun removeListener(
            vararg queryKeys: String,
            listener: Query.Listener,
        ) = delegate.removeListener(*queryKeys, listener = listener)

        override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)
    }

    @Test
    fun bootstrapCompletionRunsQueryStatisticsOptimizeExactlyOnce() {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val driver = RecordingDriver(real)
            val graph = buildLedgerGraph(driver, createSchema = true)

            // The bootstrap-completion trigger fired exactly once (the open-time pattern; the
            // build itself performs no other optimize). The graph handle is used only to keep
            // the compiler honest about the construction completing.
            assertTrue(graph.facade != null)
            assertEquals(listOf("PRAGMA optimize"), driver.executeQuerySql.filter { it == "PRAGMA optimize" })
            assertTrue(driver.executeSql.none { it.contains("optimize") }, "PRAGMA optimize must ride the executeQuery surface, never driver.execute")
        } finally {
            real.close()
        }
    }

    @Test
    fun theSharedIntakeHookReachesTheControlledGraphEntry() {
        val real = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val driver = RecordingDriver(real)
            val graph = buildLedgerGraph(driver, createSchema = true)

            // The intake-completion trigger: the commonMain host pipeline invokes the facade
            // hook after the intake transaction; the hook and the graph member must both reach
            // the same controlled driver entry.
            driver.executeQuerySql.clear()
            graph.facade.importIntakeStatisticsRefresh()
            assertEquals(listOf("PRAGMA optimize"), driver.executeQuerySql.filter { it == "PRAGMA optimize" })

            driver.executeQuerySql.clear()
            graph.runQueryStatisticsOptimize()
            assertEquals(listOf("PRAGMA optimize"), driver.executeQuerySql.filter { it == "PRAGMA optimize" })
        } finally {
            real.close()
        }
    }
}
