package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * A-PERF (P7-04 read-governance batch, spec section 2.1): the SQLite statistics-refresh entry
 * points shared by both composition roots. The pre-fix baseline measured the 20k-lib list query
 * at 255.98s with no statistics (the planner picked the wrong autoindex prefix and degraded to
 * O(candidates x duplicates)) and 0.24s after `ANALYZE`/`PRAGMA optimize` — the v30 covering
 * index already exists, only the planner statistics were missing (D-147 shipped CREATE INDEX
 * without ever running statistics; SQLite's official semantics say statistics do not update as
 * content changes and a ~10x row change triggers a re-analysis through `PRAGMA optimize`).
 *
 * The two entry points divide the trigger semantics (rework 3, device evidence on API 36 system
 * SQLite 3.44.3, 20k-lib):
 * - [runQueryStatisticsOptimizeOn] — the BOOTSTRAP-completion safety net (`PRAGMA optimize`).
 *   Near-zero cost; effective for tables the planner has already planned through stat1 (the
 *   catalog family) but, on the measured 3.44.3, it does NOT grant first-time analysis to
 *   tables with no statistics history (the import family read no stat1 plan at first start, so
 *   `PRAGMA optimize` never analyzed them — the device-reproduced stuck list; the 0x10000
 *   full-scan bit is a newer-version behavior). It stays as the cheap open-time safety net.
 * - [runFullAnalyzeOn] — the INTAKE-completion strong guarantee (`ANALYZE;`, full schema).
 *   One intake can multiply the duplicate-candidate table ~10x, and the device evidence shows
 *   only an explicit ANALYZE reliably produces import-table statistics; the hook runs in
 *   `runImportIntakePipeline`'s Default thread BEFORE the list re-read, so the refreshed
 *   statistics always land before the read in the product path.
 *
 * EXECUTION-SURFACE DISCLOSURE (rework 3, measured deviation from the batch's uniform
 * executeQuery rule): each statement rides the consuming surface its own result shape requires —
 * one surface per STATEMENT FORM, pinned by the trigger tests.
 * - `PRAGMA optimize` HAS a result column (column name 'optimize', even when no row is
 *   returned) → it MUST ride `driver.executeQuery` on Android: SQLDelight classifies it as an
 *   EXECUTE statement chained to androidx `executeUpdateDelete`/
 *   `SQLiteSession.executeForChangedRowCount`, which rejects result-BEARING statements with
 *   "Queries can be performed using SQLiteDatabase query or rawQuery methods only" (the
 *   busy_timeout lesson path; the rawQuery-equivalent executeQuery surface is the safe form,
 *   the `SELECT 1` eager-open probe precedent).
 * - `ANALYZE;` has NO result rows → two-sided evidence:
 *   - JDBC (measured): the sqlite-jdbc driver REJECTS result-less statements on its
 *     executeQuery path ("Query does not return results" / executeQuery cannot issue
 *     statements that do not produce result sets — the DesktopQueryStatisticsOptimizeTriggerTest
 *     failure that pinned this), so on desktop it rides `driver.execute(...)` — the correct
 *     surface for a row-less statement (the JDBC busy_timeout precedent uses the same face).
 *   - Android (device-verified 2026-09-17, two device re-test sessions): the same androidx
 *     rejection applies only to statements that RETURN result rows; a row-less ANALYZE is
 *     acceptable through the executeForChangedRowCount path (execSQL-shaped). The device
 *     re-tests confirmed the intake hook actually produces import-table statistics
 *     (sqlite_stat1 gained the import-table rows after intake completion; the fresh-start
 *     stuck list was eliminated).
 *
 * Zero DDL, zero schema change, zero query rewrite, zero product semantics: these only help
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

/**
 * A-PERF rework 3: the explicit full-schema statistics rebuild behind the intake-completion
 * trigger (see the file header for the trigger-semantics split and the execution-surface
 * disclosure). `ANALYZE;` re-analyzes every table of the schema, which on the measured device
 * SQLite (3.44.3) is the only form that produces statistics for tables with no stat1 planning
 * history — the import family at first start. Row-less statement: rides `driver.execute`
 * (see the disclosure above; executeQuery would be rejected by the JDBC driver).
 */
fun runFullAnalyzeOn(driver: SqlDriver) {
    driver.execute(
        null,
        "ANALYZE;",
        0,
    )
}
