package com.unifiedledger.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
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

private class ForeignKeysCallback : AndroidSqliteDriver.Callback(LedgerDatabase.Schema) {
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
}
