package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * A-PERF (P7-04 read-governance batch, spec section 2.1): the SQLite statistics-refresh entry
 * point shared by both composition roots. The pre-fix baseline measured the 20k-lib list query
 * at 255.98s with no statistics (the planner picked the wrong autoindex prefix and degraded to
 * O(candidates x duplicates)) and 0.23s after `ANALYZE`/`PRAGMA optimize` — the v30 covering
 * index already exists, only the planner statistics were missing (D-147 shipped CREATE INDEX
 * without ever running statistics; SQLite's official semantics say statistics do not update as
 * content changes and a ~10x row change triggers a re-analysis through `PRAGMA optimize`).
 *
 * EXECUTION-SURFACE CONSTRAINT (measured and pinned; the busy_timeout lesson,
 * `AndroidLedgerDatabaseHandle.kt` onConfigure note): the `PRAGMA optimize` sqlite3_stmt HAS a
 * result column (column name 'optimize', even when no row is returned), so it must never run
 * through `driver.execute(...)` on Android — SQLDelight classifies it as an EXECUTE statement
 * chained to androidx `executeUpdateDelete`/`SQLiteSession.executeForChangedRowCount`, which
 * rejects statements that return rows with "Queries can be performed using SQLiteDatabase query
 * or rawQuery methods only". The `driver.executeQuery` path is the rawQuery-equivalent safe
 * surface on BOTH platforms (the Android `SELECT 1` eager-open probe precedent; the desktop JDBC
 * path tolerates execute() but executeQuery is the one shared safe form), and the mapper drains
 * the cursor so the statement is fully consumed. Statistics maintenance is deliberately NOT
 * folded into `configureSqliteConnection`: connection configuration and per-connection
 * statistics refresh stay separate concerns (spec section 0, D-D ruling).
 *
 * Zero DDL, zero schema change, zero query rewrite, zero product semantics: this only helps
 * the planner pick the already-shipped indexes.
 */
fun runQueryStatisticsOptimizeOn(driver: SqlDriver) {
    driver
        .executeQuery(
            null,
            "PRAGMA optimize",
            { cursor ->
                // Drain the (possibly empty) result so the statement completes; PRAGMA optimize
                // returns a result column even when it performs no ANALYZE.
                while (cursor.next().value) {
                    // No per-row payload is consumed; the statement's effect is the statistics.
                }
                QueryResult.Unit
            },
            0,
            null,
        ).value
}
