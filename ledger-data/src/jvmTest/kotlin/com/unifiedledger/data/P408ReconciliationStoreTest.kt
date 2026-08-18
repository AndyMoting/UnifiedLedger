package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.data.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class P408ReconciliationStoreTest {
    @Test
    fun confirmationIsReplayableAndAppearsInTheReadProjection() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val request = request()

            val accepted = assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(request))
            assertEquals("link-a", accepted.receipt.linkId)
            assertEquals("reconciliation-posting-a", accepted.receipt.reconciliationId)
            assertEquals(2L, accepted.receipt.historySequence)

            val replay = assertIs<P408ReconciliationResult.NoChange>(store.confirmLink(request))
            assertEquals(accepted.receipt, replay.receipt)
            assertEquals(
                listOf("posting-a" to listOf("link-a")),
                store.readReconciliationReport("ledger-a").map { it.postingId to it.activeLinkIds },
            )
            assertEquals("CHECKED", store.readReconciliationReport("ledger-a").single().status)
        } finally {
            driver.close()
        }
    }

    @Test
    fun sourceDirectionMismatchRejectsWithoutLeavingAnyReconciliationRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seed(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val rejected = assertIs<P408ReconciliationResult.Rejected>(
                store.confirmLink(request().copy(direction = "in")),
            )
            assertEquals("P408_SOURCE_FACT_MISMATCH", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    private fun request() = P408ConfirmLinkRequest(
        ledgerId = "ledger-a",
        requestId = "request-a",
        evidenceId = "evidence-a",
        candidateId = "candidate-transient-a",
        postingId = "posting-a",
        amountMinor = 1000,
        currencyCode = "CNY",
        currencyPrecision = 2,
        direction = "out",
        accountId = "account-bank-a",
        responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
        basisVersion = 1,
        matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
        windowDays = 2,
        naturalDayDistance = 0,
        sourceOccurredAt = "2026-08-10T12:00:00+08:00",
        confirmedAt = "2026-08-10T13:00:00+08:00",
        linkId = "link-a",
        reconciliationId = "reconciliation-posting-a",
        createdAt = "2026-08-10T13:00:00+08:00",
    )

    private fun seed(driver: JdbcSqliteDriver) {
        val statements = listOf(
            "INSERT INTO import_request VALUES ('ledger-a','import-a','intake')",
            "INSERT INTO import_source_record VALUES ('ledger-a','source-a','import-a','batch-a',0,'ordinary_flow_source','hash-a',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled')",
            "INSERT INTO import_evidence VALUES ('ledger-a','evidence-a','source-a','source_observation','2026-08-10T12:00:01+08:00')",
            "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-a','ledger-a','ACCOUNT_TRANSFER',NULL)",
            "INSERT INTO posting_set VALUES ('posting-set-a','ledger-a')",
            "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-a','tx-a','ledger-a',1,'posting-set-a','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
            "INSERT INTO ledger_transaction_current_version VALUES ('tx-a','ledger-a','version-a')",
            "INSERT INTO posting VALUES ('posting-a','posting-set-a','ledger-a',0,'account-bank-a',-1000,'CNY',2)",
            "INSERT INTO rg03_transfer_posting_semantic VALUES ('ledger-a','posting-a','TRANSFER_PRINCIPAL_OUT',NULL,1)",
        )
        statements.forEach { driver.execute(null, it, 0) }
    }

    private fun count(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
        },
        0,
    ).value
}
