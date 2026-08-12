package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

class Rg09SchemaV12Test {
    @Test
    fun canonicalRg09TransactionKindsSurviveWriteAndReopen() {
        val path = Files.createTempFile("ledger-data-rg09-kind-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                assertEquals(20, LedgerDatabase.Schema.version)
                val database = LedgerDatabase(driver)
                seedFormal(database, "adjustment", "BALANCE_ADJUSTMENT", 3_000L)
                seedFormal(database, "reversal", "BALANCE_ADJUSTMENT_REVERSAL", -2_000L)

                assertEquals(
                    listOf(
                        StoredKind("adjustment-tx", "EXPENSE", "BALANCE_ADJUSTMENT"),
                        StoredKind("reversal-tx", "EXPENSE", "BALANCE_ADJUSTMENT_REVERSAL"),
                    ),
                    storedKinds(driver),
                )
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(
                    listOf(
                        "BALANCE_ADJUSTMENT",
                        "BALANCE_ADJUSTMENT_REVERSAL",
                    ),
                    database.ledgerQueries.selectPersistedTransaction().executeAsList().map { it.kind },
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun seedFormal(database: LedgerDatabase, prefix: String, kind: String, amountMinor: Long) {
        val ledgerId = "ledger-rg09"
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
            "2026-01-31T15:59:59Z",
            "2026-01-31T15:59:59Z",
            "2026-01-31T15:59:59Z",
            null,
        )
        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId, versionId)
        database.ledgerQueries.insertPosting(
            "$prefix-target-posting",
            postingSetId,
            ledgerId,
            0,
            "asset-a",
            amountMinor,
            "CNY",
            2,
        )
        database.ledgerQueries.insertPosting(
            "$prefix-equity-posting",
            postingSetId,
            ledgerId,
            1,
            "equity-balance-adjustments",
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
