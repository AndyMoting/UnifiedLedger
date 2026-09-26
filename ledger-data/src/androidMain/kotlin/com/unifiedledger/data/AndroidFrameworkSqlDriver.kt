package com.unifiedledger.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/*
 * P7-06 06.C (D-179; spec `docs/specs/2026-09-25-p7-06-restore-preflight-design.md` section 8.2,
 * candidate 2): a minimal, NON-create-on-open `SqlDriver` over an Android framework `SQLiteDatabase`.
 *
 * WHY THIS EXISTS (the implementation-batch confirmation the 06.C spec section 8.2/10 item 8
 * registers): the spec's PREFERRED candidate 1 is
 * `SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)` -> `FrameworkSQLiteDatabase` ->
 * `AndroidSqliteDriver(SupportSQLiteDatabase)`. `FrameworkSQLiteDatabase` lives in
 * `androidx.sqlite:sqlite-framework-android`, which this module's androidMain COMPILE classpath does
 * NOT contain: `app.cash.sqldelight:android-driver:2.3.2` publishes `androidx.sqlite:sqlite` on its
 * api (compile) variant and `androidx.sqlite:sqlite-framework` only on its runtime variant (read
 * from the cached Gradle module metadata), and `ledger-data` declares only `android-driver`. Adding
 * that dependency is a dependency change, which needs separate approval (the spec forbids it in this
 * batch), so this adapter is the spec's designated fallback: NO new dependency.
 *
 * It opens the file itself with `SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)` (no
 * `CREATE_IF_NECESSARY`, so a missing file throws instead of being created) and runs NO schema or
 * version callback, so it never creates or auto-migrates. `LedgerDatabase.Schema.migrate` is then
 * run explicitly by the caller through the strict commonMain helper.
 *
 * SCOPE: this adapter implements only the surface the strict migration and validation helpers use
 * (`execute`, `executeQuery`, transactions, and the no-op listener methods). It is NOT a general
 * purpose product driver.
 *
 * UNVERIFIED (registered): this adapter has NOT been device-verified; the main agent owns device
 * verification. It compiles in `ledger-data` androidMain; NO JVM OR HOST TEST EXERCISES THIS CLASS
 * (the commonMain helper contracts are tested on the JVM path, not through this adapter) — the
 * earlier claim of JVM coverage was wrong and is corrected here. The device-verification instrument
 * is the instrumented suite `AndroidFrameworkSqlDriverInstrumentedTest` (android-app androidTest:
 * commit/rollback through a transaction, the identity-sweep probe shapes, and a strict v1->current
 * migrate through the adapter); this class stays device-UNVERIFIED until that suite runs green.
 *
 * KNOWN LIMITATION (P3-9): [executeQuery] binds parameters through `SQLiteDatabase.rawQuery(String,
 * Array<String>)`, which accepts ONLY string arguments and applies them as TEXT. The helpers reached
 * through this adapter (`PRAGMA user_version`, `PRAGMA integrity_check`, `PRAGMA foreign_key_check`,
 * the `sqlite_master` scans and the `SELECT DISTINCT ledger_id` probes) use either no bound
 * parameters or string parameters, so this adapter is sufficient for the restore preflight. It is NOT
 * a general driver: a caller that binds a Long/Double/ByteArray through `executeQuery` would get
 * string-typed binding. `execute` (non-query) binds typed values correctly through a compiled
 * framework statement.
 */

/** Opens [path] read-write WITHOUT create-on-open and returns a driver over it. */
fun openAndroidReadWriteDriver(path: String): SqlDriver {
    val database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
    return AndroidFrameworkSqlDriver(database)
}

/**
 * The minimal `SqlDriver` over a framework `SQLiteDatabase`. All statements run on the framework
 * handle directly; transactions map to `beginTransaction`/`setTransactionSuccessful`/`endTransaction`.
 */
internal class AndroidFrameworkSqlDriver(
    private val database: SQLiteDatabase,
) : SqlDriver {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val statement = AndroidPreparedStatement(sql, parameters)
        binders?.invoke(statement)
        val cursor = database.rawQuery(sql, statement.stringArgs)
        return try {
            mapper(AndroidSqlCursor(cursor))
        } finally {
            cursor.close()
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val statement = AndroidPreparedStatement(sql, parameters)
        binders?.invoke(statement)
        if (parameters == 0) {
            database.execSQL(sql)
        } else {
            val compiled = database.compileStatement(sql)
            try {
                statement.bindInto(compiled)
                compiled.execute()
            } finally {
                compiled.close()
            }
        }
        val changes = database.rawQuery("SELECT changes()", null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        return QueryResult.Value(changes)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val transaction = AndroidTransaction(this)
        transaction.begin()
        return QueryResult.Value(transaction)
    }

    override fun currentTransaction(): Transacter.Transaction? = activeTransaction

    override fun addListener(
        queryKeys: Array<out String>,
        listener: app.cash.sqldelight.Query.Listener,
    ) {
        // No query-notification support: this adapter is used only for the isolated migration and
        // validation, which never subscribe to queries.
    }

    override fun removeListener(
        queryKeys: Array<out String>,
        listener: app.cash.sqldelight.Query.Listener,
    ) {
        // See addListener.
    }

    override fun notifyListeners(queryKeys: Array<out String>) {
        // See addListener.
    }

    override fun close() {
        database.close()
    }

    private var activeTransaction: AndroidTransaction? = null

    private class AndroidTransaction(
        private val driver: AndroidFrameworkSqlDriver,
    ) : Transacter.Transaction() {
        override val enclosingTransaction: Transacter.Transaction?
            get() = driver.currentTransaction()

        fun begin() {
            driver.beginTransaction(this)
        }

        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            driver.endTransaction(this, successful)
            return QueryResult.Unit
        }
    }

    private fun beginTransaction(transaction: AndroidTransaction) {
        database.beginTransaction()
        activeTransaction = transaction
    }

    private fun endTransaction(
        transaction: AndroidTransaction,
        successful: Boolean,
    ) {
        if (successful) database.setTransactionSuccessful()
        database.endTransaction()
        activeTransaction = null
    }
}

/** Accumulates bound arguments for the framework statement APIs. */
private class AndroidPreparedStatement(
    private val sql: String,
    private val parameters: Int,
) : SqlPreparedStatement {
    private val args = arrayOfNulls<Any?>(parameters)
    private val allStrings = BooleanArray(parameters) { true }

    /** The raw-query string arguments when every bound value is a string; else null. */
    val stringArgs: Array<String>?
        get() = if (allStrings.all { it }) args.map { it as String }.toTypedArray() else null

    /** Binds the accumulated arguments into a compiled framework statement, in index order. */
    fun bindInto(statement: android.database.sqlite.SQLiteStatement) {
        for (index in 0 until parameters) {
            when (val value = args[index]) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindString(index + 1, value)
                is Long -> statement.bindLong(index + 1, value)
                is Double -> statement.bindDouble(index + 1, value)
                is ByteArray -> statement.bindBlob(index + 1, value)
                else -> statement.bindString(index + 1, value.toString())
            }
        }
    }

    override fun bindString(
        index: Int,
        string: String?,
    ) {
        args[index] = string
    }

    override fun bindLong(
        index: Int,
        long: Long?,
    ) {
        args[index] = long
        allStrings[index] = false
    }

    override fun bindDouble(
        index: Int,
        double: Double?,
    ) {
        args[index] = double
        allStrings[index] = false
    }

    override fun bindBoolean(
        index: Int,
        boolean: Boolean?,
    ) {
        args[index] = boolean?.let { if (it) 1L else 0L }
        allStrings[index] = false
    }

    override fun bindBytes(
        index: Int,
        bytes: ByteArray?,
    ) {
        args[index] = bytes
        allStrings[index] = false
    }
}

/** The `SqlCursor` over an Android framework `Cursor`. */
private class AndroidSqlCursor(
    private val cursor: Cursor,
) : SqlCursor {
    override fun next(): QueryResult<Boolean> = QueryResult.Value(cursor.moveToNext())

    override fun getString(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)

    override fun getLong(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)

    override fun getDouble(index: Int): Double? = if (cursor.isNull(index)) null else cursor.getDouble(index)

    override fun getBytes(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBlob(index)

    override fun getBoolean(index: Int): Boolean? = if (cursor.isNull(index)) null else cursor.getLong(index) != 0L
}
