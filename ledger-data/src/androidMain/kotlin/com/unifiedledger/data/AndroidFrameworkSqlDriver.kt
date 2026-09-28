package com.unifiedledger.data

import android.database.Cursor
import android.database.DatabaseErrorHandler
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
 * It opens the file itself with `SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE, <handler>)`
 * (no `CREATE_IF_NECESSARY`, so a missing file throws instead of being created, and an explicit
 * non-deleting corruption handler — see below) and runs NO schema or version callback, so it never
 * creates or auto-migrates. `LedgerDatabase.Schema.migrate` is then run explicitly by the caller
 * through the strict commonMain helper.
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
 * migrate through the adapter); this class stays device-UNVERIFIED until that suite runs green. The
 * corruption policy below is additionally pinned on device by
 * `AndroidRestoreRecoveryInstrumentedTest.aStructurallyCorruptCandidateIsNeverAdopted`, which
 * asserts a corrupt candidate survives the probe byte-identically, and on the host by
 * `IsolatedDatabaseCorruptionHandlerTest` (the handler body makes no framework call, so the policy
 * override itself is host-testable — unlike the adapter, whose open takes a concrete
 * `SQLiteDatabase`).
 *
 * NON-DESTRUCTIVE CORRUPTION POLICY (P7-06 06.D device-gate defect 1, D-183):
 * `SQLiteDatabase.openDatabase(path, null, flags)` forwards a `null` error handler and the
 * `SQLiteDatabase` constructor then substitutes `new DefaultDatabaseErrorHandler()` (AOSP
 * `android/database/sqlite/SQLiteDatabase.java:493`, the 3-arg overload `:1005-1008`). That default
 * handler's `onCorruption` CLOSES and DELETES the database file (AOSP
 * `android/database/DefaultDatabaseErrorHandler.java:53-108`, `deleteDatabaseFile` `:97-108`), so
 * merely PROBING a corrupt candidate DESTROYED it on device: the 06.D recovery probe was deleting
 * the very content it is contractually forbidden to touch (spec section 5.3; the probe "performs
 * NO RECOVERY ACTION of its own"). Both open functions below therefore pass the explicit
 * [PreservingIsolatedDatabaseErrorHandler], which never touches the file and surfaces corruption as
 * the typed [LedgerIsolatedDatabaseCorruptionException] — the fail-closed verdict the callers
 * already produce, instead of a silent deletion.
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

/**
 * Opens [path] read-write WITHOUT create-on-open and returns a driver over it. This is the MIGRATION
 * leg (`migrateIsolatedSnapshotStrictlyOn` runs a real transaction), so it must be read-write.
 */
fun openAndroidReadWriteDriver(path: String): SqlDriver = openAndroidDriver(path, SQLiteDatabase.OPEN_READWRITE)

/**
 * Opens [path] strictly read-only WITHOUT create-on-open and returns a driver over it (P7-06 06.D
 * defect 1, D-183). This is the INSPECTION leg: every probe that only reads
 * (`PRAGMA user_version`, `PRAGMA integrity_check`, the identity sweep, the owner counts, the
 * domain validation). A read-only framework open skips `setLocaleFromConfiguration`'s write path
 * entirely (AOSP `SQLiteConnection.java:490-493` returns before `CREATE TABLE android_metadata` /
 * the `BEGIN ... REINDEX ... COMMIT` block), so merely INSPECTING a candidate leaves it — and its
 * directory — byte-identical. That is the spec section 5.3 discipline the probe must keep: it
 * "never adopts, never discards", so it must also never write. The read-only shape has device
 * precedent: `verifyAndroidSnapshotFile` (`AndroidLedgerDatabaseHandle.kt:177-203`) is the same
 * read-only framework open and is pinned non-mutating by
 * `AndroidBackupSnapshotVerificationInstrumentedTest`.
 */
fun openAndroidReadOnlyDriver(path: String): SqlDriver = openAndroidDriver(path, SQLiteDatabase.OPEN_READONLY)

/**
 * The one open used by both legs. The explicit [PreservingIsolatedDatabaseErrorHandler] is passed
 * for BOTH flags: read-only does not by itself stop the platform default from deleting a corrupt
 * file (AOSP `SQLiteDatabase.open()` calls `onCorruption()` regardless of the open flags), so the
 * handler is the load-bearing deletion guard and the flags are the incidental-write guard.
 */
private fun openAndroidDriver(
    path: String,
    flags: Int,
): SqlDriver {
    val database = SQLiteDatabase.openDatabase(path, null, flags, PreservingIsolatedDatabaseErrorHandler)
    return AndroidFrameworkSqlDriver(database)
}

/**
 * The corruption policy for every isolated-database open in this file: NEVER delete, always surface.
 *
 * `SQLiteDatabase` substitutes `DefaultDatabaseErrorHandler` when the caller passes a null handler
 * (AOSP `SQLiteDatabase.java:493`), and that default CLOSES then DELETES the database file
 * (`DefaultDatabaseErrorHandler.java:53-108`). For a normal app database that is a reasonable
 * recovery; for the restore preflight and the 06.D recovery probe it is DATA LOSS: those opens are
 * inspections of a snapshot / candidate that the flow must leave byte-identical unless the user
 * explicitly confirms a destructive action (spec section 5.3 — a candidate that cannot be verified
 * stays `Unusable` and UNTOUCHED).
 *
 * This handler therefore performs ZERO file operations and ZERO calls on the database handle — it
 * does not even read `dbObj.path`, so the "no side effect" claim is literal and not merely "no
 * delete" — and throws [LedgerIsolatedDatabaseCorruptionException]. Every caller already converts a
 * throwing probe to a fail-closed verdict: the adapter's `executeQuery`/`execute` paths let it out,
 * and the use cases map a throwing probe to "cannot verify" (never an optimistic adoption). It is
 * the same shape as the product ledger's own `ForeignKeysCallback.onCorruption` override
 * (`AndroidLedgerDatabaseHandle.kt:231-242`), which exists for exactly this reason (D-132 D-2) and
 * is pinned by a host test asserting zero recorded calls.
 *
 * The `dbObj` parameter is declared NULLABLE on purpose: the Java interface parameter is a platform
 * type, and because the body never touches it, a host test can drive this policy with `null` and
 * assert the typed throw — which is exactly how `IsolatedDatabaseCorruptionHandlerTest` (ledger-data
 * androidHostTest) pins it without a device. Reading the handle would make that test impossible and
 * would also risk turning a probe into a state change.
 */
internal object PreservingIsolatedDatabaseErrorHandler : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase?): Unit = throw LedgerIsolatedDatabaseCorruptionException()
}

/**
 * The typed fail-closed corruption signal of an isolated-database open (P7-06 06.D defect 1, D-183).
 * It replaces the platform default's silent file deletion; callers surface it as an unusable
 * candidate / a typed read failure and never delete anything. Zero file operations happen on the
 * path that produces it.
 */
class LedgerIsolatedDatabaseCorruptionException : RuntimeException("isolated database corruption detected; the original file is preserved; fail-closed")

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
