package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

class Rg10SchemaV13Test {
    @Test
    fun canonicalRg10TransactionKindsSurviveWriteAndReopen() {
        val path = Files.createTempFile("ledger-data-rg10-kind-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                assertEquals(23, LedgerDatabase.Schema.version)
                val database = LedgerDatabase(driver)
                seedFormal(database, "recharge", "STORED_VALUE_RECHARGE", 120_000L)
                seedFormal(database, "spend", "STORED_VALUE_SPEND", -30_000L)
                seedFormal(database, "expiry", "STORED_VALUE_EXPIRY_LOSS", -10_000L)
                seedFormal(database, "activation", "STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT", 60_000L)

                assertEquals(
                    listOf(
                        StoredKind("activation-tx", "EXPENSE", "STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT"),
                        StoredKind("expiry-tx", "EXPENSE", "STORED_VALUE_EXPIRY_LOSS"),
                        StoredKind("recharge-tx", "EXPENSE", "STORED_VALUE_RECHARGE"),
                        StoredKind("spend-tx", "EXPENSE", "STORED_VALUE_SPEND"),
                    ),
                    storedKinds(driver),
                )
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(
                    listOf(
                        "STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT",
                        "STORED_VALUE_EXPIRY_LOSS",
                        "STORED_VALUE_RECHARGE",
                        "STORED_VALUE_SPEND",
                    ),
                    database.ledgerQueries.selectPersistedTransaction().executeAsList().map { it.kind },
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rg10OwnersAreCreatedEmptyOnFreshSchema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            assertEquals(23, LedgerDatabase.Schema.version)
            assertEquals(0L, database.ledgerQueries.selectRg10AllOperations("ledger-a").executeAsList().size.toLong())
            assertEquals(0L, database.ledgerQueries.selectRg10AllLots("ledger-a").executeAsList().size.toLong())
            assertEquals(0L, database.ledgerQueries.selectRg10AllCandidates("ledger-a").executeAsList().size.toLong())
            assertEquals(0L, database.ledgerQueries.selectRg10AllReconstructions("ledger-a").executeAsList().size.toLong())
        } finally {
            driver.close()
        }
    }

    private fun seedFormal(database: LedgerDatabase, prefix: String, kind: String, amountMinor: Long) {
        val ledgerId = "ledger-rg10"
        val transactionId = "$prefix-tx"
        val postingSetId = "$prefix-posting-set"
        val versionId = "$prefix-version"
        database.ledgerQueries.insertPostingSet(postingSetId, ledgerId)
        database.ledgerQueries.insertTransaction(transactionId, ledgerId, kind)
        database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            ledgerId,
            1,
            postingSetId,
            "2026-01-10T02:00:00Z",
            "2026-01-10T02:00:00Z",
            "2026-01-10T02:00:00Z",
            null,
        )
        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId, versionId)
        database.ledgerQueries.insertPosting(
            "$prefix-stored-posting",
            postingSetId,
            ledgerId,
            0,
            "asset-stored-value-x",
            amountMinor,
            "CNY",
            2,
        )
        database.ledgerQueries.insertPosting(
            "$prefix-counter-posting",
            postingSetId,
            ledgerId,
            1,
            if (prefix == "recharge" || prefix == "activation") "equity-balance-adjustments" else "expense-consumption-rg10",
            -amountMinor,
            "CNY",
            2,
        )
    }

    private fun storedKinds(driver: JdbcSqliteDriver): List<StoredKind> = driver.executeQuery(
        identifier = null,
        sql = "SELECT transaction_id, kind, canonical_kind FROM ledger_transaction ORDER BY transaction_id",
        mapper = { cursor ->
            val rows = buildList {
                while (cursor.next().value) {
                    add(
                        StoredKind(
                            transactionId = requireNotNull(cursor.getString(0)),
                            legacyKind = requireNotNull(cursor.getString(1)),
                            canonicalKind = requireNotNull(cursor.getString(2)),
                        ),
                    )
                }
            }
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

    private fun sqliteProperties() = Properties().apply {
        setProperty("foreign_keys", "true")
    }

    private data class StoredKind(
        val transactionId: String,
        val legacyKind: String,
        val canonicalKind: String,
    )
}
