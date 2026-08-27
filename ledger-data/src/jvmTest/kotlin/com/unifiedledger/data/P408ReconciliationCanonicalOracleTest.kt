package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Canonical-oracle coverage for the P4-08 confirmation surface. The assertions here
 * deliberately inspect every shared reconciliation table and the financial spine so
 * confirmation/replay/reopen are value-for-value stable.
 */
class P408ReconciliationCanonicalOracleTest {

    @Test
    fun confirmationWritesExactRowsAndEquivalentReplayAppendsNothing() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedTransfer(driver, includeSecondPosting = false)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val financialBefore = financialRows(driver)
            val reportBefore = store.readReconciliationReport("ledger-a").single()
            assertEquals(P408ReconciliationStatus.PENDING, reportBefore.status)
            assertEquals(emptyList(), reportBefore.activeLinkIds)

            val request = request()
            val accepted = assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(request))
            assertEquals("link-a", accepted.receipt.linkId)
            assertEquals("reconciliation-posting-a", accepted.receipt.reconciliationId)
            assertEquals(2L, accepted.receipt.historySequence)

            assertEquals(
                listOf(
                    listOf(
                        "ledger-a", "request-a", "confirm_link", request.fingerprint(), "ACCEPTED", null,
                    ),
                ),
                selectRows(
                    driver,
                    "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM reconciliation_request ORDER BY request_id",
                    listOf(false, false, false, false, false, false),
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "ledger-a", "request-a", "evidence-a", "candidate-transient-a", "posting-a", "tx-a",
                        1000L, "CNY", 2L, "out", "account-bank-a", "real_account_posting",
                        2L, "account,amount,currency,direction,occurred_at_window", 2L, 0L,
                        "2026-08-10T12:00:00+08:00", "2026-08-10T13:00:00+08:00", "confirm_match",
                    ),
                ),
                selectRows(
                    driver,
                    "SELECT ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision FROM reconciliation_request_snapshot ORDER BY request_id",
                    listOf(false, false, false, false, false, false, true, false, true, false, false, false, true, false, true, true, false, false, false),
                ),
            )
            assertEquals(
                listOf(
                    listOf(
                        "ledger-a", "link-a", "evidence-a", "posting-a", "tx-a", "real_account_posting",
                        2L, "account,amount,currency,direction,occurred_at_window", "candidate-transient-a",
                        "request-a", "2026-08-10T13:00:00+08:00",
                    ),
                ),
                selectRows(
                    driver,
                    "SELECT ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at FROM evidence_link ORDER BY link_id",
                    listOf(false, false, false, false, false, false, true, false, false, false, false),
                ),
            )
            assertEquals(
                listOf(
                    listOf("ledger-a", "link-a", 1L, "active", "confirmed", "request-a", "2026-08-10T13:00:00+08:00"),
                ),
                selectRows(
                    driver,
                    "SELECT ledger_id, link_id, sequence, state, reason, request_id, occurred_at FROM evidence_link_history ORDER BY sequence",
                    listOf(false, false, true, false, false, false, false),
                ),
            )
            assertEquals(
                listOf(
                    listOf("ledger-a", "reconciliation-posting-a", "posting-a", "CHECKED", 2L),
                ),
                selectRows(
                    driver,
                    "SELECT ledger_id, reconciliation_id, posting_id, status, latest_sequence FROM posting_reconciliation ORDER BY reconciliation_id",
                    listOf(false, false, false, false, true),
                ),
            )
            assertEquals(
                listOf(
                    listOf("ledger-a", "reconciliation-posting-a", 1L, "PENDING", null, "request-a", "2026-08-10T12:00:00+08:00"),
                    listOf("ledger-a", "reconciliation-posting-a", 2L, "CHECKED", "link-a", "request-a", "2026-08-10T13:00:00+08:00"),
                ),
                selectRows(
                    driver,
                    "SELECT ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at FROM posting_reconciliation_history ORDER BY sequence",
                    listOf(false, false, true, false, false, false, false),
                ),
            )
            assertEquals(
                listOf(
                    listOf("ledger-a", "request-a", "ACCEPTED", "link-a", "reconciliation-posting-a", 2L),
                ),
                selectRows(
                    driver,
                    "SELECT ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence FROM reconciliation_receipt ORDER BY request_id",
                    listOf(false, false, false, false, false, true),
                ),
            )

            val reportAfter = store.readReconciliationReport("ledger-a").single()
            assertEquals(P408ReconciliationStatus.CHECKED, reportAfter.status)
            assertEquals(listOf("link-a"), reportAfter.activeLinkIds)
            assertEquals(financialBefore, financialRows(driver))

            val stateBeforeReplay = canonicalState(driver)
            val replay = assertIs<P408ReconciliationResult.NoChange>(
                store.confirmLink(
                    request.copy(
                        linkId = "link-a-replay",
                        reconciliationId = "reconciliation-replay",
                        createdAt = "2026-08-10T15:00:00+08:00",
                    ),
                ),
            )
            assertEquals(accepted.receipt, replay.receipt)
            assertEquals(stateBeforeReplay, canonicalState(driver))
            assertEquals(financialBefore, financialRows(driver))
        } finally {
            driver.close()
        }
    }

    @Test
    fun closeReopenPreservesCanonicalRowsAndReconciliationDimension() {
        val path = Files.createTempFile("p408-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            val preReopen = JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                seedTransfer(driver, includeSecondPosting = false)
                val store = SqlDelightP408ReconciliationStore(database, driver)
                assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(request()))
                Triple(
                    store.readReconciliationReport("ledger-a").map { it.postingId to it.activeLinkIds },
                    store.readReconciliationReport("ledger-a").map { it.status },
                    canonicalState(driver),
                )
            }

            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightP408ReconciliationStore(database, driver)

                assertEquals(
                    preReopen.first,
                    store.readReconciliationReport("ledger-a").map { it.postingId to it.activeLinkIds },
                )
                assertEquals(
                    preReopen.second,
                    store.readReconciliationReport("ledger-a").map { it.status },
                )
                assertEquals(preReopen.third, canonicalState(driver))

                val replay = assertIs<P408ReconciliationResult.NoChange>(store.confirmLink(request()))
                assertEquals(preReopen.third, canonicalState(driver))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun request(): P408ConfirmLinkRequest = P408ConfirmLinkRequest(
        ledgerId = "ledger-a",
        requestId = "request-a",
        evidenceId = "evidence-a",
        candidateId = "candidate-transient-a",
        postingId = "posting-a",
        transactionId = "tx-a",
        amountMinor = 1000,
        currencyCode = "CNY",
        currencyPrecision = 2,
        direction = "out",
        accountId = "account-bank-a",
        responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
        basisVersion = 2,
        projectionId = "proj-evidence-a",
        projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
        projectionRuleVersion = 1,
        normalizedAmountMinor = 1000,
        rawAmountMinor = 1000,
        rawCurrencyPrecision = 2,
        matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
        windowDays = 2,
        naturalDayDistance = 0,
        sourceOccurredAt = "2026-08-10T12:00:00+08:00",
        confirmedAt = "2026-08-10T13:00:00+08:00",
        linkId = "link-a",
        reconciliationId = "reconciliation-posting-a",
        createdAt = "2026-08-10T13:00:00+08:00",
    )

    private fun seedTransfer(driver: JdbcSqliteDriver, includeSecondPosting: Boolean) {
        val statements = buildList {
            add("INSERT INTO import_request VALUES ('ledger-a','import-a','intake')")
            add("INSERT INTO import_source_record VALUES ('ledger-a','source-a','import-a','batch-a',0,'ordinary_flow_source','hash-a',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled','SETTLED','legacy-settled-v1',1,'2026-08-10T12:00:00+08:00')")
            add("INSERT INTO import_evidence VALUES ('ledger-a','evidence-a','source-a','source_observation','2026-08-10T12:00:01+08:00')")
            add("INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-a','ledger-a','ACCOUNT_TRANSFER',NULL)")
            add("INSERT INTO posting_set VALUES ('posting-set-a','ledger-a')")
            add("INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-a','tx-a','ledger-a',1,'posting-set-a','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)")
            add("INSERT INTO ledger_transaction_current_version VALUES ('tx-a','ledger-a','version-a')")
            add("INSERT INTO posting VALUES ('posting-a','posting-set-a','ledger-a',0,'account-bank-a',-1000,'CNY',2)")
            if (includeSecondPosting) {
                add("INSERT INTO import_request VALUES ('ledger-a','import-b','intake')")
                add("INSERT INTO import_source_record VALUES ('ledger-a','source-b','import-b','batch-b',0,'ordinary_flow_source','hash-b',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','in','settled','SETTLED','legacy-settled-v1',1,'2026-08-10T12:00:00+08:00')")
                add("INSERT INTO import_evidence VALUES ('ledger-a','evidence-b','source-b','source_observation','2026-08-10T12:00:01+08:00')")
                add("INSERT INTO posting VALUES ('posting-b','posting-set-a','ledger-a',1,'account-platform-b',1000,'CNY',2)")
            }
        }
        statements.forEach { driver.execute(null, it, 0) }
    }

    private fun canonicalState(driver: JdbcSqliteDriver): Map<String, List<List<Any?>>> = linkedMapOf(
        "reconciliation_request" to selectRows(
            driver,
            "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM reconciliation_request ORDER BY request_id",
            listOf(false, false, false, false, false, false),
        ),
        "reconciliation_request_snapshot" to selectRows(
            driver,
            "SELECT ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision FROM reconciliation_request_snapshot ORDER BY request_id",
            listOf(false, false, false, false, false, false, true, false, true, false, false, false, true, false, true, true, false, false, false),
        ),
        "evidence_link" to selectRows(
            driver,
            "SELECT ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at FROM evidence_link ORDER BY link_id",
            listOf(false, false, false, false, false, false, true, false, false, false, false),
        ),
        "evidence_link_history" to selectRows(
            driver,
            "SELECT ledger_id, link_id, sequence, state, reason, request_id, occurred_at FROM evidence_link_history ORDER BY sequence",
            listOf(false, false, true, false, false, false, false),
        ),
        "posting_reconciliation" to selectRows(
            driver,
            "SELECT ledger_id, reconciliation_id, posting_id, status, latest_sequence FROM posting_reconciliation ORDER BY reconciliation_id",
            listOf(false, false, false, false, true),
        ),
        "posting_reconciliation_history" to selectRows(
            driver,
            "SELECT ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at FROM posting_reconciliation_history ORDER BY sequence",
            listOf(false, false, true, false, false, false, false),
        ),
        "reconciliation_receipt" to selectRows(
            driver,
            "SELECT ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence FROM reconciliation_receipt ORDER BY request_id",
            listOf(false, false, false, false, false, true),
        ),
    )

    private fun financialRows(driver: JdbcSqliteDriver): List<List<Any?>> {
        val ledgerTransactions = selectRows(
            driver,
            "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction",
            listOf(false, false, false, false),
        )
        val versions = selectRows(
            driver,
            "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, confirmation_id FROM transaction_version",
            listOf(false, false, false, true, false, false, false, false, false, false),
        )
        val postings = selectRows(
            driver,
            "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting",
            listOf(false, false, false, true, false, true, false, true),
        )
        return (ledgerTransactions + versions + postings).sortedBy { row ->
            row.joinToString("\u0000") { it?.toString() ?: "" }
        }
    }

    private fun selectRows(
        driver: JdbcSqliteDriver,
        sql: String,
        longColumns: List<Boolean>,
    ): List<List<Any?>> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            val rows = mutableListOf<List<Any?>>()
            while (cursor.next().value) {
                rows += longColumns.mapIndexed { index, isLong ->
                    if (isLong) cursor.getLong(index) else cursor.getString(index)
                }
            }
            app.cash.sqldelight.db.QueryResult.Value(rows.toList())
        },
        0,
    ).value
}
