package com.unifiedledger.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * P5-04.5-FOUND-001 T-A (D-132 section 5.2): JVM evidence that [ForeignKeysCallback.onCorruption]
 * throws the fixed exception type instead of the androidx default "delete the database file"
 * behaviour. No Robolectric and no Android framework calls: the callback body must not touch the
 * database at all, so a recording dynamic proxy stands in for [SupportSQLiteDatabase] (the
 * interface has around forty members, which would make a hand-written fake larger than the
 * behaviour under test, and only [ForeignKeysCallback.onConfigure] invokes one member).
 */
class ForeignKeysCallbackCorruptionTest {
    @Test
    fun onCorruptionThrowsFixedTypeWithoutTouchingTheDatabase() {
        val fake = RecordingSupportSqliteDatabase()
        val callback = ForeignKeysCallback()

        val thrown =
            assertFailsWith<LedgerDatabaseCorruptionException> {
                callback.onCorruption(fake.proxy)
            }

        assertEquals(
            "UnifiedLedger ledger database corruption detected; original file preserved; fail-closed",
            thrown.message,
        )
        // T-A acceptance criterion (b): zero recorded calls means zero delete calls and zero file
        // operations through the SupportSQLiteDatabase surface. It also proves the androidx
        // default onCorruption (which logs and deletes via db.isOpen()/db.getPath()) never ran.
        assertEquals(emptyList(), fake.calls)
    }

    @Test
    fun onConfigureKeepsForeignKeyEnforcement() {
        val fake = RecordingSupportSqliteDatabase()
        val callback = ForeignKeysCallback()

        callback.onConfigure(fake.proxy)

        // The corruption override must not change the pre-existing D-129 onConfigure behaviour.
        assertEquals(listOf("setForeignKeyConstraintsEnabled(true)"), fake.calls)
    }
}

/**
 * SupportSQLiteDatabase stand-in that records every invocation instead of performing it. The
 * androidx interface references android framework types only in member signatures, so a proxy
 * never loads or calls framework code - which matches the callback under test exactly.
 */
private class RecordingSupportSqliteDatabase {
    val calls = mutableListOf<String>()

    val proxy: SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            calls += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "()")
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
}
