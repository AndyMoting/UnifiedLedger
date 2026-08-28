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
        db.execSQL("PRAGMA busy_timeout = 5000")
    }
}
