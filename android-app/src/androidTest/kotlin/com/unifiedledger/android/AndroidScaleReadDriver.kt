package com.unifiedledger.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** Test-only bridge; caller owns one read-only snapshot and all writes are forbidden. */
internal class AndroidScaleReadDriver(
    private val database: SQLiteDatabase,
) : SqlDriver {
    private var transaction: Transacter.Transaction? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        check(database.isReadOnly && database.inTransaction())
        check(sql.trimStart().startsWith("SELECT", ignoreCase = true))
        val bindings = Bindings(parameters)
        binders?.invoke(bindings)
        return database.rawQuery(sql, bindings.values).use { mapper(ReadCursor(it)) }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = error("scale read bridge forbids writes")

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        check(transaction == null && database.inTransaction())
        val created =
            object : Transacter.Transaction() {
                override val enclosingTransaction: Transacter.Transaction? = null

                override fun endTransaction(successful: Boolean): QueryResult<Unit> {
                    transaction = null
                    return QueryResult.Unit
                }
            }
        transaction = created
        return QueryResult.Value(created)
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    override fun addListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) = error("not a live subscription")

    override fun removeListener(
        queryKeys: Array<out String>,
        listener: Query.Listener,
    ) = error("not a live subscription")

    override fun notifyListeners(queryKeys: Array<out String>) = error("read-only bridge")

    override fun close() = Unit // Snapshot owner closes the handle.

    private class Bindings(
        size: Int,
    ) : SqlPreparedStatement {
        val values = arrayOfNulls<String>(size)

        override fun bindString(
            index: Int,
            string: String?,
        ) {
            values[index] = string
        }

        override fun bindLong(
            index: Int,
            long: Long?,
        ) {
            values[index] = long?.toString()
        }

        override fun bindBoolean(
            index: Int,
            boolean: Boolean?,
        ) {
            values[index] = boolean?.let { if (it) "1" else "0" }
        }

        override fun bindDouble(
            index: Int,
            double: Double?,
        ) = error("no floating-point query parameters")

        override fun bindBytes(
            index: Int,
            bytes: ByteArray?,
        ) = error("no blob query parameters")
    }

    private class ReadCursor(
        private val cursor: Cursor,
    ) : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(cursor.moveToNext())

        override fun getString(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)

        override fun getLong(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)

        override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }

        override fun getDouble(index: Int): Double? = error("no floating-point ledger oracle")

        override fun getBytes(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBlob(index)
    }
}
