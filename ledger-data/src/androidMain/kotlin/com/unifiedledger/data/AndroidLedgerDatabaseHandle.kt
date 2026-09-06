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
        driver = driver,
    )
}

class AndroidLedgerDatabaseHandle internal constructor(
    val database: LedgerDatabase,
    val commitPort: SqlDelightConfirmedManualExpenseCommitPort,
    private val driver: AndroidSqliteDriver,
) : AutoCloseable {
    override fun close() {
        driver.close()
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
