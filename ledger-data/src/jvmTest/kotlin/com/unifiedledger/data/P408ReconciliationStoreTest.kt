package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.data.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class P408ReconciliationStoreTest {
    @Test
    fun confirmationIsReplayableAndAppearsInTheReadProjection() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val request = request()

            val accepted = assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(request))
            assertEquals("link-a", accepted.receipt.linkId)
            assertEquals("reconciliation-posting-a", accepted.receipt.reconciliationId)
            assertEquals(2L, accepted.receipt.historySequence)

            val replay = assertIs<P408ReconciliationResult.NoChange>(store.confirmLink(request))
            assertEquals(accepted.receipt, replay.receipt)

            val outputIdsRetry =
                request.copy(
                    linkId = "link-a-retry",
                    reconciliationId = "reconciliation-output-retry",
                    createdAt = "2026-08-10T14:00:00+08:00",
                )
            val identityReplay = assertIs<P408ReconciliationResult.NoChange>(store.confirmLink(outputIdsRetry))
            assertEquals(accepted.receipt, identityReplay.receipt)

            assertEquals(
                listOf("posting-a" to listOf("link-a")),
                store.readReconciliationReport("ledger-a").map { it.postingId to it.activeLinkIds },
            )
            assertEquals(
                P408ReconciliationStatus.CHECKED,
                store.readReconciliationReport("ledger-a").single().status,
            )
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
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(direction = "in")),
                )
            assertEquals("P408_REQUEST_IDENTITY_CONFLICT", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun transactionIdMismatchRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(transactionId = "tx-other")),
                )
            assertEquals("P408_TRANSACTION_ID_MISMATCH", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun responsibilityMismatchRejectsWithoutWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(responsibility = P408EvidenceResponsibility.DESTINATION_ASSET_POSTING)),
                )
            assertEquals("P408_RESPONSIBILITY_POSTING_MISMATCH", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun secondMirrorLinkToOtherPostingOfSameTransferIsAllowedWithoutNewFinancialRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedTransfer(driver, includeSecondPosting = true)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            assertIs<P408ReconciliationResult.Accepted>(
                store.confirmLink(request(linkId = "link-a", reconciliationId = "reconciliation-posting-a")),
            )
            val mirror =
                assertIs<P408ReconciliationResult.Accepted>(
                    store.confirmLink(
                        request(
                            requestId = "request-b",
                            evidenceId = "evidence-b",
                            candidateId = "candidate-transient-b",
                            postingId = "posting-b",
                            transactionId = "tx-a",
                            direction = "in",
                            accountId = "account-platform-b",
                            responsibility = P408EvidenceResponsibility.DESTINATION_ASSET_POSTING,
                            linkId = "link-b",
                            reconciliationId = "reconciliation-posting-b",
                            createdAt = "2026-08-10T14:00:00+08:00",
                        ),
                    ),
                )
            assertEquals("reconciliation-posting-b", mirror.receipt.reconciliationId)

            val report = store.readReconciliationReport("ledger-a")
            assertEquals(
                listOf(
                    "posting-a" to listOf("link-a"),
                    "posting-b" to listOf("link-b"),
                ),
                report.map { it.postingId to it.activeLinkIds },
            )
            assertEquals(2L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM ledger_transaction WHERE kind = 'ACCOUNT_TRANSFER'"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM transaction_version WHERE transaction_id = 'tx-a'"))
            assertEquals(2L, count(driver, "SELECT count(*) FROM posting WHERE posting_set_id = 'posting-set-a'"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun sameEvidenceDifferentTransactionRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedTransfer(driver, includeSecondPosting = true)
            seedSecondTransaction(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            assertIs<P408ReconciliationResult.Accepted>(
                store.confirmLink(request(linkId = "link-a", reconciliationId = "reconciliation-posting-a")),
            )
            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(
                        request(
                            requestId = "request-other-tx",
                            postingId = "posting-other-tx",
                            transactionId = "tx-other",
                            direction = "out",
                            accountId = "account-bank-other",
                            naturalDayDistance = 1,
                            linkId = "link-other-tx",
                            reconciliationId = "reconciliation-posting-other-tx",
                        ),
                    ),
                )
            assertEquals("P408_EVIDENCE_ALREADY_LINKED", rejected.code)
            assertEquals(1L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM reconciliation_request"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun changedRequestRetryIsTypedConflictWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(request()))
            val linksBefore = count(driver, "SELECT count(*) FROM evidence_link")
            val requestsBefore = count(driver, "SELECT count(*) FROM reconciliation_request")
            val changed =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(amountMinor = 2_000)),
                )
            assertEquals("P408_REQUEST_IDENTITY_CONFLICT", changed.code)
            assertEquals(linksBefore, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(requestsBefore, count(driver, "SELECT count(*) FROM reconciliation_request"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun unapprovedWindowDaysRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(windowDays = 365)),
                )
            assertEquals("P408_WINDOW_DAYS_NOT_APPROVED", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun wrongReconciliationIdOnPendingRowRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            driver.execute(
                null,
                "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) VALUES ('ledger-a','reconciliation-seeded','posting-a','PENDING',1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO posting_reconciliation_history(ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at) VALUES ('ledger-a','reconciliation-seeded',1,'PENDING',NULL,'seed','2026-08-10T12:00:00+08:00')",
                0,
            )
            val store = SqlDelightP408ReconciliationStore(database, driver)

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(reconciliationId = "reconciliation-wrong")),
                )
            assertEquals("P408_RECONCILIATION_ID_MISMATCH", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(1L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun negativeAmountRequestConstructionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            request(amountMinor = -1)
        }
    }

    @Test
    fun crossLedgerStaleVersionAndIneligiblePostingAreRejectedWithoutWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            driver.execute(
                null,
                "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-stale','ledger-a','ACCOUNT_TRANSFER',NULL)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO posting_set VALUES ('posting-set-stale','ledger-a')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-stale','tx-stale','ledger-a',1,'posting-set-stale','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO posting VALUES ('posting-stale','posting-set-stale','ledger-a',0,'account-bank-stale',-1000,'CNY',2)",
                0,
            )
            val store = SqlDelightP408ReconciliationStore(database, driver)

            val crossLedger =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(ledgerId = "ledger-other", requestId = "request-cross")),
                )
            assertEquals("P408_EVIDENCE_NOT_FOUND", crossLedger.code)

            val stale =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(requestId = "request-stale", postingId = "posting-stale")),
                )
            assertEquals("P408_POSTING_NOT_ELIGIBLE", stale.code)

            val ineligible =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(requestId = "request-ineligible", postingId = "posting-expense")),
                )
            assertEquals("P408_POSTING_NOT_ELIGIBLE", ineligible.code)

            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun confirmationLeavesFinancialStateUnchangedAndUpdatesReconciliationDimension() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)
            val financialBefore = financialRows(driver)
            val reportBefore = store.readReconciliationReport("ledger-a")
            assertEquals(P408ReconciliationStatus.PENDING, reportBefore.single().status)
            assertEquals(emptyList(), reportBefore.single().activeLinkIds)

            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(request()))

            val financialAfter = financialRows(driver)
            assertEquals(financialBefore, financialAfter)
            val reportAfter = store.readReconciliationReport("ledger-a")
            assertEquals(P408ReconciliationStatus.CHECKED, reportAfter.single().status)
            assertEquals(listOf("link-a"), reportAfter.single().activeLinkIds)
        } finally {
            driver.close()
        }
    }

    @Test
    fun duplicateLinkIdConstraintFailureIsTypedRejectedWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedTransfer(driver, includeSecondPosting = true)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            assertIs<P408ReconciliationResult.Accepted>(
                store.confirmLink(request(linkId = "link-a", reconciliationId = "reconciliation-posting-a")),
            )
            val linksBefore = count(driver, "SELECT count(*) FROM evidence_link")
            val requestsBefore = count(driver, "SELECT count(*) FROM reconciliation_request")
            val receiptsBefore = count(driver, "SELECT count(*) FROM reconciliation_receipt")
            val reconciliationsBefore = count(driver, "SELECT count(*) FROM posting_reconciliation")

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(
                        request(
                            requestId = "request-b",
                            evidenceId = "evidence-b",
                            candidateId = "candidate-transient-b",
                            postingId = "posting-b",
                            direction = "in",
                            accountId = "account-platform-b",
                            responsibility = P408EvidenceResponsibility.DESTINATION_ASSET_POSTING,
                            linkId = "link-a",
                            reconciliationId = "reconciliation-posting-b",
                            createdAt = "2026-08-10T14:00:00+08:00",
                        ),
                    ),
                )
            assertEquals("P408_RECONCILIATION_CONSTRAINT_VIOLATION", rejected.code)
            assertEquals(linksBefore, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(requestsBefore, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(receiptsBefore, count(driver, "SELECT count(*) FROM reconciliation_receipt"))
            assertEquals(reconciliationsBefore, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun postingFactMismatchRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(accountId = "account-other")),
                )
            assertEquals("P408_POSTING_FACT_MISMATCH", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun postingTimeWindowMismatchRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(request(naturalDayDistance = 1)),
                )
            assertEquals("P408_POSTING_TIME_WINDOW_MISMATCH", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun postingResponsibilityAlreadyLinkedRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            val store = SqlDelightP408ReconciliationStore(database, driver)

            assertIs<P408ReconciliationResult.Accepted>(store.confirmLink(request()))
            driver.execute(null, "INSERT INTO import_request VALUES ('ledger-a','import-c','intake')", 0)
            driver.execute(
                null,
                "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-a','source-c','import-c','batch-c',0,'ordinary_flow_source','hash-c',1,'valid_complete',1000,'CNY',2,'2026-08-10T12:00:00+08:00','out','settled')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO import_evidence VALUES ('ledger-a','evidence-c','source-c','source_observation','2026-08-10T12:00:01+08:00')",
                0,
            )
            val linksBefore = count(driver, "SELECT count(*) FROM evidence_link")
            val requestsBefore = count(driver, "SELECT count(*) FROM reconciliation_request")
            val reconciliationsBefore = count(driver, "SELECT count(*) FROM posting_reconciliation")

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(
                        request(
                            requestId = "request-c",
                            evidenceId = "evidence-c",
                            candidateId = "candidate-c",
                            postingId = "posting-a",
                            linkId = "link-c",
                            reconciliationId = "reconciliation-posting-a",
                            createdAt = "2026-08-10T14:00:00+08:00",
                        ),
                    ),
                )
            assertEquals("P408_POSTING_RESPONSIBILITY_ALREADY_LINKED", rejected.code)
            assertEquals(linksBefore, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(requestsBefore, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(reconciliationsBefore, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun sourceFactUnresolvedRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            driver.execute(null, "INSERT INTO import_request VALUES ('ledger-a','import-unresolved','intake')", 0)
            driver.execute(
                null,
                "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-a','source-unresolved','import-unresolved','batch-unresolved',0,'ordinary_flow_source','hash-unresolved',1,'valid_incomplete',NULL,NULL,NULL,NULL,NULL,NULL)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO import_evidence VALUES ('ledger-a','evidence-unresolved','source-unresolved','source_observation','2026-08-10T12:00:01+08:00')",
                0,
            )
            val store = SqlDelightP408ReconciliationStore(database, driver)

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(
                        request(
                            requestId = "request-unresolved-source",
                            evidenceId = "evidence-unresolved",
                            candidateId = "candidate-unresolved-source",
                        ),
                    ),
                )
            assertEquals("P408_SOURCE_FACT_UNRESOLVED", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun postingTimeUnresolvedRejectsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            seedSingleOutTransfer(driver)
            driver.execute(null, "INSERT INTO import_request VALUES ('ledger-a','import-time','intake')", 0)
            driver.execute(
                null,
                "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-a','source-time','import-time','batch-time',0,'ordinary_flow_source','hash-time',1,'valid_complete',1000,'CNY',2,'2026-08-10 12:00:00','out','settled')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO import_evidence VALUES ('ledger-a','evidence-time','source-time','source_observation','2026-08-10 12:00:01')",
                0,
            )
            val store = SqlDelightP408ReconciliationStore(database, driver)

            val rejected =
                assertIs<P408ReconciliationResult.Rejected>(
                    store.confirmLink(
                        request(
                            requestId = "request-unresolved-time",
                            evidenceId = "evidence-time",
                            candidateId = "candidate-time",
                            sourceOccurredAt = "2026-08-10 12:00:00",
                        ),
                    ),
                )
            assertEquals("P408_POSTING_TIME_UNRESOLVED", rejected.code)
            assertEquals(0L, count(driver, "SELECT count(*) FROM evidence_link"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM reconciliation_request"))
            assertEquals(0L, count(driver, "SELECT count(*) FROM posting_reconciliation"))
        } finally {
            driver.close()
        }
    }

    private fun request(
        ledgerId: String = "ledger-a",
        requestId: String = "request-a",
        evidenceId: String = "evidence-a",
        candidateId: String = "candidate-transient-a",
        postingId: String = "posting-a",
        transactionId: String = "tx-a",
        amountMinor: Long = 1000,
        currencyCode: String = "CNY",
        currencyPrecision: Int = 2,
        direction: String = "out",
        accountId: String = "account-bank-a",
        responsibility: P408EvidenceResponsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
        basisVersion: Int = 1,
        matchBasis: Set<String> = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
        windowDays: Int = 2,
        naturalDayDistance: Int = 0,
        sourceOccurredAt: String = "2026-08-10T12:00:00+08:00",
        confirmedAt: String = "2026-08-10T13:00:00+08:00",
        linkId: String = "link-a",
        reconciliationId: String = "reconciliation-posting-a",
        createdAt: String = "2026-08-10T13:00:00+08:00",
    ) = P408ConfirmLinkRequest(
        ledgerId = ledgerId,
        requestId = requestId,
        evidenceId = evidenceId,
        candidateId = candidateId,
        postingId = postingId,
        transactionId = transactionId,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        currencyPrecision = currencyPrecision,
        direction = direction,
        accountId = accountId,
        responsibility = responsibility,
        basisVersion = basisVersion,
        matchBasis = matchBasis,
        windowDays = windowDays,
        naturalDayDistance = naturalDayDistance,
        sourceOccurredAt = sourceOccurredAt,
        confirmedAt = confirmedAt,
        linkId = linkId,
        reconciliationId = reconciliationId,
        createdAt = createdAt,
    ).let { base ->
        // Write-always-v2 (D-112 UQ-4): scale-equal fixtures carry raw == normalized.
        if (base.basisVersion == 1) {
            base.copy(
                basisVersion = 2,
                projectionId = "proj-$evidenceId",
                projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
                projectionRuleVersion = 1,
                normalizedAmountMinor = amountMinor,
                rawAmountMinor = amountMinor,
                rawCurrencyPrecision = currencyPrecision,
            )
        } else {
            base
        }
    }

    private fun seedSingleOutTransfer(driver: JdbcSqliteDriver) = seedTransfer(driver, includeSecondPosting = false)

    private fun seedTransfer(
        driver: JdbcSqliteDriver,
        includeSecondPosting: Boolean,
    ) {
        val statements =
            buildList {
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
                add("INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-expense','ledger-a','EXPENSE',NULL)")
                add("INSERT INTO posting_set VALUES ('posting-set-expense','ledger-a')")
                add("INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-expense','tx-expense','ledger-a',1,'posting-set-expense','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00','2026-08-10T12:00:00+08:00',NULL)")
                add("INSERT INTO ledger_transaction_current_version VALUES ('tx-expense','ledger-a','version-expense')")
                add("INSERT INTO posting VALUES ('posting-expense','posting-set-expense','ledger-a',0,'expense-account',1000,'CNY',2)")
            }
        statements.forEach { driver.execute(null, it, 0) }
    }

    private fun seedSecondTransaction(driver: JdbcSqliteDriver) {
        val statements =
            listOf(
                "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-other','ledger-a','ACCOUNT_TRANSFER',NULL)",
                "INSERT INTO posting_set VALUES ('posting-set-other','ledger-a')",
                "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-other','tx-other','ledger-a',1,'posting-set-other','2026-08-11T12:00:00+08:00','2026-08-11T12:00:00+08:00','2026-08-11T12:00:00+08:00',NULL)",
                "INSERT INTO ledger_transaction_current_version VALUES ('tx-other','ledger-a','version-other')",
                "INSERT INTO posting VALUES ('posting-other-tx','posting-set-other','ledger-a',0,'account-bank-other',-1000,'CNY',2)",
            )
        statements.forEach { driver.execute(null, it, 0) }
    }

    private fun financialRows(driver: JdbcSqliteDriver): List<List<Any?>> {
        val ledgerTransactions =
            selectRows(
                driver,
                "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction",
                listOf(false, false, false, false),
            )
        val versions =
            selectRows(
                driver,
                "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, confirmation_id FROM transaction_version",
                listOf(false, false, false, true, false, false, false, false, false, false),
            )
        val postings =
            selectRows(
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
    ): List<List<Any?>> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val rows = mutableListOf<List<Any?>>()
                    while (cursor.next().value) {
                        rows +=
                            longColumns.mapIndexed { index, isLong ->
                                if (isLong) cursor.getLong(index) else cursor.getString(index)
                            }
                    }
                    app.cash.sqldelight.db.QueryResult
                        .Value(rows.toList())
                },
                0,
            ).value

    private fun count(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getLong(0)!!)
                },
                0,
            ).value
}
