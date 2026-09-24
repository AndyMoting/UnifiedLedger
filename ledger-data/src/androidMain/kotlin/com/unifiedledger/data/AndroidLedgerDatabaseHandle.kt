package com.unifiedledger.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
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
        // P7-05.B/C (D-156/D-158 slice 1b): the correction and void/restore commit ports on the
        // same platform-configured connection, for the same reason as the four manual ports —
        // the driver stays private to this handle, so the composition root reaches them through
        // the platform-configured factories.
        correctionCommitPort =
            SqlDelightTransactionCorrectionCommitPort
                .forPlatformConfiguredDatabase(database),
        voidCommitPort = SqlDelightTransactionVoidCommitPort.forPlatformConfiguredDatabase(database),
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
    // P7-05.B/C (D-156/D-158 slice 1b): the correction and void/restore commit ports.
    val correctionCommitPort: SqlDelightTransactionCorrectionCommitPort,
    val voidCommitPort: SqlDelightTransactionVoidCommitPort,
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
     * only to statements that RETURN result rows, so a row-less ANALYZE passes — device-verified
     * 2026-09-17 (two device re-test sessions: the intake hook actually produces import-table
     * statistics, sqlite_stat1 gaining the import-table rows after intake completion; the JDBC
     * half of this two-sided reasoning is JVM-measured — executeQuery rejects result-less
     * statements there). Full disclosure in [runFullAnalyzeOn].
     */
    fun runFullAnalyze() {
        runFullAnalyzeOn(driver)
    }

    /**
     * P7-06 06.B (D-177; spec section 3.3): the controlled snapshot entry over the handle's
     * private driver. The driver stays private (the handle is the only controlled surface), so the
     * composition root calls THIS method to run `VACUUM INTO` on the active connection; the
     * statement rides `driver.execute` (the row-less surface, [runSnapshotIntoOn]).
     */
    fun runSnapshotInto(target: String) {
        runSnapshotIntoOn(driver, target)
    }
}

/**
 * P7-06 06.B (D-177; spec section 3.4): validates a snapshot FILE through a second, dedicated
 * Android connection. The active handle cannot stand in (it holds the active generation's main
 * file), and the driver is private to the handle, so this opens a fresh [AndroidSqliteDriver] over
 * [name] with a NULL schema — no `create`/`migrate` callback runs against the snapshot, so the
 * verification is read-only in effect and never mutates or migrates the snapshot.
 *
 * UNVERIFIED (registered, spec section 9 item 9): the concrete driver choice and open/close
 * semantics for the second open are an implementation-batch decision not yet device-verified.
 */
fun verifyAndroidSnapshotFile(
    context: Context,
    name: String,
): SnapshotVerification {
    val driver =
        AndroidSqliteDriver(
            schema = NoOpSnapshotSchema,
            context = context,
            name = name,
        )
    return try {
        verifySnapshotOn(driver)
    } finally {
        driver.close()
    }
}

/**
 * The no-op schema for the snapshot verification open (spec section 3.4): its `create`/`migrate`
 * do NOTHING, so opening the snapshot file for `PRAGMA integrity_check` never creates or migrates
 * anything. A valid snapshot already carries the product schema (its `user_version` is non-zero),
 * so `onCreate` never runs; this schema exists only to satisfy the driver constructor and to make
 * the read-only intent explicit.
 */
private object NoOpSnapshotSchema : app.cash.sqldelight.db.SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 0L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: app.cash.sqldelight.db.AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
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
