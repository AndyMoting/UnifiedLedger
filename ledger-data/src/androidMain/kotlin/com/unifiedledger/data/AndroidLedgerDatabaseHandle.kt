package com.unifiedledger.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase

fun createAndroidLedgerDatabase(
    context: Context,
    name: String,
): AndroidLedgerDatabaseHandle {
    val driver =
        AndroidSqliteDriver(
            schema = LedgerDatabase.Schema,
            context = context,
            name = name,
            callback = ForeignKeysCallback(),
        )
    // P5-04.5-FOUND-001 (D-132 D-1, amended A-1): one minimal read-only probe forces the real
    // open (openHelper.writableDatabase) synchronously, so onCreate/onUpgrade and corruption
    // failures surface from this factory call into the startup controller's catch instead of at
    // first use. "SELECT 1" mutates no schema and no data; identifier=null keeps the one-off
    // statement out of the driver cache, and the driver closes the cursor after the mapper.
    driver
        .executeQuery(
            null,
            "SELECT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
            null,
        ).value
    val database = LedgerDatabase(driver)
    return AndroidLedgerDatabaseHandle(
        database = database,
        commitPort =
            SqlDelightConfirmedManualExpenseCommitPort
                .forPlatformConfiguredDatabase(database),
        // P7-02.A: the symmetric income commit port on the same database connection.
        incomeCommitPort =
            SqlDelightConfirmedManualIncomeCommitPort
                .forPlatformConfiguredDatabase(database),
        // P7-02.B: the symmetric transfer commit port on the same database connection.
        transferCommitPort =
            SqlDelightConfirmedManualTransferCommitPort
                .forPlatformConfiguredDatabase(database),
        // P7-02.C: the symmetric lending commit port, counterparty directory and position reads.
        lendingCommitPort =
            SqlDelightConfirmedManualLendingCommitPort
                .forPlatformConfiguredDatabase(database),
        counterpartyStore = SqlDelightCounterpartyStore.forPlatformConfiguredDatabase(database),
        // P7-02.D: the manual pin preference store on the same database connection.
        entryPreferenceStore = SqlDelightEntryPreferenceStore(database),
        catalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(database),
        // P7-04.A/B (D-146): the import spine store on the same platform-configured
        // connection; the driver stays private to this handle, so the store comes from the
        // platform-configured factory exactly like the four manual commit ports above.
        importSpineStore = SqlDelightImportSpineStore.forPlatformConfiguredDatabase(database),
        driver = driver,
    )
}

class AndroidLedgerDatabaseHandle internal constructor(
    val database: LedgerDatabase,
    val commitPort: SqlDelightConfirmedManualExpenseCommitPort,
    val incomeCommitPort: SqlDelightConfirmedManualIncomeCommitPort,
    val transferCommitPort: SqlDelightConfirmedManualTransferCommitPort,
    val lendingCommitPort: SqlDelightConfirmedManualLendingCommitPort,
    val counterpartyStore: SqlDelightCounterpartyStore,
    val entryPreferenceStore: SqlDelightEntryPreferenceStore,
    val catalogStore: SqlDelightCatalogStore,
    val importSpineStore: SqlDelightImportSpineStore,
    private val driver: AndroidSqliteDriver,
) : AutoCloseable {
    override fun close() {
        driver.close()
    }

    /**
     * A-PERF (P7-04 read-governance batch, spec section 2.1): the controlled statistics-refresh
     * entry over the handle's private driver. The driver stays private (the handle is the only
     * controlled surface, the P7-04.A/B discipline), so the composition roots call THIS method at
     * the bootstrap-completion trigger point instead of reaching for the driver. The execution
     * surface is `driver.executeQuery` (the rawQuery-equivalent safe path; the `SELECT 1`
     * eager-open probe precedent at the top of this file) — never `driver.execute`, which on
     * Android rejects statements that return result rows ([runQueryStatisticsOptimizeOn]
     * carries the full constraint). The bootstrap point keeps `PRAGMA optimize` (the cheap
     * open-time safety net for tables with a stat1 planning history).
     */
    fun runQueryStatisticsOptimize() {
        runQueryStatisticsOptimizeOn(driver)
    }

    /**
     * A-PERF rework 3 (device evidence, API 36 system SQLite 3.44.3): the intake-completion
     * trigger's controlled entry — the explicit full-schema `ANALYZE;`. `PRAGMA optimize` does
     * not grant first-time analysis to tables with no statistics history (the import family at
     * first start; the device-reproduced stuck list), so the intake hook (which runs on the
     * Default thread BEFORE the list re-read) needs the strong guarantee. ANALYZE is a row-less
     * statement and rides `driver.execute` (the changed-row-count surface): the androidx
     * executeForChangedRowCount rejection that forced PRAGMA optimize onto executeQuery applies
     * only to statements that RETURN result rows, so a row-less ANALYZE is expected to pass —
     * a code-level expectation the device re-test must confirm (the intake hook must actually
     * produce import-table statistics; the JDBC half of this two-sided reasoning is
     * JVM-measured — executeQuery rejects result-less statements there). Full disclosure in
     * [runFullAnalyzeOn].
     */
    fun runFullAnalyze() {
        runFullAnalyzeOn(driver)
    }
}

/**
 * P5-04.5-FOUND-001 (D-132 D-2, amended A-1): the fixed corruption override exception. It carries
 * no classification role (D-5); its only job is to replace the androidx default onCorruption
 * behaviour - "delete the database file" - with a visible failure. On the corruption path this
 * fixed type is what surfaces: androidx swallows the first failed open attempt, retries once
 * after roughly 500 ms, and rethrows the second failure as-is, so this non-SQLiteException
 * reaches the startup controller and maps to the fail-closed StartupError state. Migration and
 * permission failures are not routed through onCorruption; they surface the original
 * SQLiteException unchanged.
 */
internal class LedgerDatabaseCorruptionException(
    message: String,
) : RuntimeException(message)

internal class ForeignKeysCallback : AndroidSqliteDriver.Callback(LedgerDatabase.Schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // PRAGMA busy_timeout = N must not be issued through execSQL/execute on Android: the
        // setting statement returns a result row, which SQLiteSession.executeForChangedRowCount
        // rejects with "Queries can be performed using SQLiteDatabase query or rawQuery methods
        // only" (observed on an API 36 emulator). The single-connection demo has no busy
        // contention on Android, so the busy timeout is intentionally not set here; the desktop
        // JDBC path keeps its own busy_timeout via configureSqliteConnection.
    }

    override fun onCorruption(db: SupportSQLiteDatabase) {
        // P5-04.5-FOUND-001 (D-132 D-2, amended A-1): androidx's default implementation deletes
        // the database file and the open retry then rebuilds an empty ledger silently (the D-130
        // FOUND-001 defect). onCorruption carries no original-exception parameter to rethrow, so
        // throwing this fixed type is the override shape that blocks deletion. Zero file
        // operations here: the original file (and its -wal/-shm companions) stays in place
        // untouched, and the A-1 eager probe inside createAndroidLedgerDatabase is what surfaces
        // this exception synchronously into the startup controller's catch.
        throw LedgerDatabaseCorruptionException(
            "UnifiedLedger ledger database corruption detected; original file preserved; fail-closed",
        )
    }
}
