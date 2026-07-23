package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.sql.SQLException

class Rg03SchemaV5Test {
    @Test
    fun `fresh schema retains v5 transfer kind while accepting v6 credit repayment`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            assertEquals(6, LedgerDatabase.Schema.version)
            database.ledgerQueries.insertTransaction("tx-transfer-schema", "ledger-a", "ACCOUNT_TRANSFER")
            database.ledgerQueries.insertTransaction("tx-repayment-schema", "ledger-a", "CREDIT_REPAYMENT")
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `evidence identity is unique within a ledger and reusable across ledgers`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            listOf("ledger-a", "ledger-b").forEach { ledger ->
                driver.execute(null, "INSERT INTO rg03_source_record VALUES (?, 'source', 'evidence', 'INCOMPLETE_TRANSFER_SOURCE', '2026-01-01T00:00:00Z', 'asset', 1, 'CNY', 2)", 1) {
                    bindString(0, ledger)
                }
                driver.execute(null, "INSERT INTO rg03_evidence VALUES (?, 'evidence', 'source', '2026-01-01T00:00:00Z', 'SOURCE_DEBIT')", 1) {
                    bindString(0, ledger)
                }
            }
            assertFailsWith<SQLException> {
                driver.execute(null, "INSERT INTO rg03_evidence VALUES ('ledger-a', 'evidence', 'source', '2026-01-01T00:00:00Z', 'SOURCE_DEBIT')", 0)
            }
        } finally {
            driver.close()
        }
    }
}
