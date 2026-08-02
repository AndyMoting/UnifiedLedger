package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class Rg07SchemaV10Test {
    @Test
    fun operationNonOutcomeColumnsAndRejectionsAreImmutable() = fresh { driver ->
        driver.execute(null, "INSERT INTO rg07_operation VALUES ('ledger-a','identity-a','validate_refund_receipt','REJECTION','fingerprint','ACCEPTED',NULL,NULL)", 0)
        driver.execute(null, "INSERT INTO rg07_rejection VALUES ('ledger-a','identity-a','must_be_positive','$.attempted_input.amount')", 0)

        assertFailsWith<SQLException> { driver.execute(null, "UPDATE rg07_operation SET action='other' WHERE identity_value='identity-a'", 0) }
        assertFailsWith<SQLException> { driver.execute(null, "UPDATE rg07_rejection SET reason_code='other' WHERE identity_value='identity-a'", 0) }
        assertFailsWith<SQLException> { driver.execute(null, "DELETE FROM rg07_rejection WHERE identity_value='identity-a'", 0) }
    }

    @Test
    fun receiptTransitionChangesOnlyOwnedReceiptColumns() = fresh { driver ->
        driver.execute(null, "INSERT INTO rg07_relation VALUES ('ledger-a','relation-a','refund','{}')", 0)
        driver.execute(null, "INSERT INTO rg07_refund_relationship VALUES ('ledger-a','entity-a','relation-a','expense-a',NULL,'category-a',3000,0,'CNY',2,NULL,'2026-01-20T01:00:00Z','2026-01-21T02:00:00Z','2026-01-23T03:00:00Z',NULL,NULL,NULL,NULL,NULL)", 0)

        assertFailsWith<SQLException> {
            driver.execute(null, "UPDATE rg07_refund_relationship SET refund_transaction_id='refund-a',received_amount_minor=3000,destination_account_id='asset-a',source_observed_at='2026-02-02T07:25:00Z',booking_at='2026-02-02T07:20:00Z',value_at='2026-02-02T07:20:00Z',confirmed_at='2026-02-02T10:00:00Z',arrived_at='2026-02-02T07:20:00Z',processor_reported_at='2026-01-24T03:00:00Z' WHERE entity_id='entity-a'", 0)
        }
        driver.execute(null, "UPDATE rg07_refund_relationship SET refund_transaction_id='refund-a',received_amount_minor=3000,destination_account_id='asset-a',source_observed_at='2026-02-02T07:25:00Z',booking_at='2026-02-02T07:20:00Z',value_at='2026-02-02T07:20:00Z',confirmed_at='2026-02-02T10:00:00Z',arrived_at='2026-02-02T07:20:00Z' WHERE entity_id='entity-a'", 0)
    }

    private fun fresh(block: (JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            block(driver)
        } finally {
            driver.close()
        }
    }
}
