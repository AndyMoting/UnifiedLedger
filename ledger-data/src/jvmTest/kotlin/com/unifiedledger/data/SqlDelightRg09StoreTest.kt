package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.Rg09ExecutionResult
import com.unifiedledger.application.Rg09FixtureCase
import com.unifiedledger.application.Rg09FormalTransactionRecord
import com.unifiedledger.application.Rg09Operation
import com.unifiedledger.application.Rg09Snapshot
import com.unifiedledger.application.adaptRg09Fixture
import com.unifiedledger.application.parseRg09FixtureInputs
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class SqlDelightRg09StoreTest {
    @Test
    fun mainPathPersistsFormalOwnershipAndReplaysAfterReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg09-store-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: com.unifiedledger.application.Rg09Snapshot
        lateinit var accepted: Rg09ExecutionResult.Accepted
        val allocation = fixture.operations[3].operation
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                fixture.operations.take(4).forEach { item ->
                    val result = assertIs<Rg09ExecutionResult.Accepted>(store.commit(item.operation), item.id)
                    if (item.operation == allocation) accepted = result
                }
                expectedSnapshot = store.snapshot(fixture.ledgerId)
                assertEquals(4L, database.ledgerQueries.countRg09FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(4L, database.ledgerQueries.countRg09Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(
                    2L,
                    database.ledgerQueries
                        .selectRg09AllPostingReconciliations(fixture.ledgerId.value)
                        .executeAsList()
                        .size
                        .toLong(),
                )
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedSnapshot, store.snapshot(fixture.ledgerId))
                assertEquals(Rg09ExecutionResult.NoChange(accepted.returnedIds), store.commit(allocation))
                assertEquals(4L, database.ledgerQueries.countRg09Operations(fixture.ledgerId.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun importedCandidateLifecycleOwnerAndConfirmationSurviveReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg09-import-lifecycle-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: Rg09Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                fixture.operations.take(2).forEach { item ->
                    assertIs<Rg09ExecutionResult.Accepted>(store.commit(item.operation), item.id)
                }

                val pending = fixture.allOperations.single { it.id == "pending-import-rg09" }
                assertIs<Rg09ExecutionResult.Accepted>(store.commit(pending.operation), pending.id)
                assertEquals(
                    "pending_confirmation",
                    store
                        .snapshot(fixture.ledgerId)
                        .candidates
                        .single { it.id.value == "candidate-import-transfer-rg09" }
                        .status,
                )
                assertEquals(
                    listOf("PENDING_CONFIRMATION"),
                    database.ledgerQueries
                        .selectRg09AllCandidateHistory(fixture.ledgerId.value)
                        .executeAsList()
                        .filter { it.candidate_id == "candidate-import-transfer-rg09" }
                        .map { it.status },
                )

                val transfer = fixture.allOperations.single { it.id == "import-transfer-confirmation-rg09" }
                assertIs<Rg09ExecutionResult.Accepted>(store.commit(transfer.operation), transfer.id)
                assertEquals(
                    "pending_confirmation",
                    store
                        .snapshot(fixture.ledgerId)
                        .candidates
                        .single { it.id.value == "candidate-import-transfer-rg09" }
                        .status,
                )

                val explanation = fixture.allOperations.single { it.id == "import-explanation-confirmation-rg09" }
                assertIs<Rg09ExecutionResult.Accepted>(store.commit(explanation.operation), explanation.id)
                val confirmed =
                    store.snapshot(fixture.ledgerId).candidates.single {
                        it.id.value == "candidate-import-transfer-rg09"
                    }
                assertEquals("confirmed", confirmed.status)
                assertEquals("adjustment-rg09", confirmed.adjustmentId?.value)
                assertEquals("request-import-allocation-confirm-rg09", confirmed.confirmationRequestId?.value)
                assertEquals(
                    listOf("PENDING_CONFIRMATION", "CONFIRMED"),
                    database.ledgerQueries
                        .selectRg09AllCandidateHistory(fixture.ledgerId.value)
                        .executeAsList()
                        .filter { it.candidate_id == "candidate-import-transfer-rg09" }
                        .map { it.status },
                )
                val current =
                    database.ledgerQueries
                        .selectRg09Candidate(
                            fixture.ledgerId.value,
                            "candidate-import-transfer-rg09",
                        ).executeAsOne()
                assertEquals("CONFIRMED", current.status)
                assertEquals("adjustment-rg09", current.adjustment_id)
                assertEquals("request-import-allocation-confirm-rg09", current.confirmation_request_id)
                expectedSnapshot = store.snapshot(fixture.ledgerId)
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedSnapshot, store.snapshot(fixture.ledgerId))
                val current =
                    database.ledgerQueries
                        .selectRg09Candidate(
                            fixture.ledgerId.value,
                            "candidate-import-transfer-rg09",
                        ).executeAsOne()
                assertEquals("CONFIRMED", current.status)
                assertEquals("adjustment-rg09", current.adjustment_id)
                assertEquals("request-import-allocation-confirm-rg09", current.confirmation_request_id)
                assertEquals(
                    listOf("PENDING_CONFIRMATION", "CONFIRMED"),
                    database.ledgerQueries
                        .selectRg09AllCandidateHistory(fixture.ledgerId.value)
                        .executeAsList()
                        .filter { it.candidate_id == "candidate-import-transfer-rg09" }
                        .map { it.status },
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun reconciliationCurrentRowAdvancesWithHistoryAndSurvivesReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg09-reconciliation-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: Rg09Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                fixture.operations.forEach { item ->
                    assertIs<Rg09ExecutionResult.Accepted>(store.commit(item.operation), item.id)
                }
                val evidence = fixture.allOperations.single { it.id == "link-first_transfer_asset_a-rg09" }
                assertIs<Rg09ExecutionResult.Accepted>(store.commit(evidence.operation), evidence.id)

                val current =
                    database.ledgerQueries
                        .selectRg09AllPostingReconciliations(fixture.ledgerId.value)
                        .executeAsList()
                        .single { it.posting_id == "posting-transfer-a-rg09" }
                assertEquals("MATCHED", current.status)
                assertEquals(2L, current.latest_sequence)
                assertEquals(
                    listOf("PENDING_EVIDENCE", "MATCHED"),
                    database.ledgerQueries
                        .selectRg09AllReconciliationHistory(fixture.ledgerId.value)
                        .executeAsList()
                        .filter { it.posting_id == "posting-transfer-a-rg09" }
                        .map { it.status },
                )
                expectedSnapshot = store.snapshot(fixture.ledgerId)
                assertEquals("matched", expectedSnapshot.reconciliation["posting-transfer-a-rg09"])
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedSnapshot, store.snapshot(fixture.ledgerId))
                val current =
                    database.ledgerQueries
                        .selectRg09AllPostingReconciliations(fixture.ledgerId.value)
                        .executeAsList()
                        .single { it.posting_id == "posting-transfer-a-rg09" }
                assertEquals("MATCHED", current.status)
                assertEquals(2L, current.latest_sequence)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun distinctVersionTimesAndStatisticsMetadataSurviveReopen() {
        // R1 (DATA-001): the three version times stay distinct and the persisted
        // metadata is the statistics time text. The record's effective metadata is no
        // longer stored in its own column: on read-back the store rehydrates
        // effectiveAtText from the shared statistics_at_text column (the folded value),
        // which is by design, so this test asserts the statisticsAtText round trip.
        val fixture = loadFixture()
        val original = fixture.openingTransactions.single()
        val originalFormal = original.formalTransaction
        val originalVersion = originalFormal.versions.single()
        val times =
            TransactionTimes(
                occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                statisticsAt = Instant.parse("2026-01-02T00:00:00Z"),
                effectiveAt = Instant.parse("2026-01-03T00:00:00Z"),
            )
        val version = originalVersion.copy(times = times)
        val formal =
            assertIs<DomainResult.Success<FormalTransaction>>(
                FormalTransaction.create(originalFormal.transaction, listOf(version), originalFormal.postingSets),
            ).value
        val record =
            original.copy(
                formalTransaction = formal,
                createdAt = Instant.parse("2026-01-04T00:00:00Z"),
                createdAtText = "2026-01-04T08:00:00+08:00",
                statisticsAtText = "2026-01-02T08:00:00+08:00",
            )
        val path = Files.createTempFile("ledger-data-rg09-times-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg09Store(database, driver, fixture.catalog, listOf(record))
                assertFormalTimes(record, store.snapshot(fixture.ledgerId).formalTransactions.single())
            }
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg09Store(database, driver, fixture.catalog, listOf(record))
                assertFormalTimes(record, store.snapshot(fixture.ledgerId).formalTransactions.single())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun missingFormalMetadataFailsWithoutEconomicTimeFallback() {
        val fixture = loadFixture()
        val record = fixture.openingTransactions.single()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            insertFormalWithoutRg09Metadata(database, record)
            val store = SqlDelightRg09Store(database, driver, fixture.catalog)

            val failure =
                assertFailsWith<IllegalStateException> {
                    store.snapshot(fixture.ledgerId)
                }
            assertEquals(
                "missing persisted RG-09 formal transaction metadata for ${record.formalTransaction.transaction.id.value}",
                failure.message,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun rejectedAndNoChangeOperationsHaveNoFormalEffect() {
        val fixture = loadFixture()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = store(database, driver, fixture)
            val preview = assertIs<Rg09Operation.PreviewTargetBalance>(fixture.operations.first().operation)
            val beforeRejected = store.snapshot(fixture.ledgerId)

            assertIs<Rg09ExecutionResult.Rejected>(store.commit(preview.copy(ids = preview.ids.copy(candidateId = null))))
            assertPersistedSnapshotEquals(beforeRejected, store.snapshot(fixture.ledgerId))
            assertEquals(1L, database.ledgerQueries.countRg09FormalTransactions(fixture.ledgerId.value).executeAsOne())

            val retryDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(retryDriver)
                val retryDatabase = LedgerDatabase(retryDriver)
                val retryStore = store(retryDatabase, retryDriver, fixture)
                val accepted = assertIs<Rg09ExecutionResult.Accepted>(retryStore.commit(preview))
                val beforeRetry = retryStore.snapshot(fixture.ledgerId)

                assertEquals(Rg09ExecutionResult.NoChange(accepted.returnedIds), retryStore.commit(preview))
                assertPersistedSnapshotEquals(beforeRetry, retryStore.snapshot(fixture.ledgerId))
                assertEquals(1L, retryDatabase.ledgerQueries.countRg09FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, retryDatabase.ledgerQueries.countRg09Operations(fixture.ledgerId.value).executeAsOne())
            } finally {
                retryDriver.close()
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun failureAfterDeltaRollsBackClaimAndEveryDependentWrite() {
        val fixture = loadFixture()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val failingStore =
                SqlDelightRg09Store(
                    database,
                    driver,
                    fixture.catalog,
                    fixture.openingTransactions,
                    Rg09FailureInjector { point ->
                        if (point == Rg09FailurePoint.AFTER_DELTA) error("injected RG-09 rollback")
                    },
                )
            val preview = fixture.operations.first().operation
            val baseline = failingStore.snapshot(fixture.ledgerId)

            assertFailsWith<IllegalStateException> { failingStore.commit(preview) }
            assertPersistedSnapshotEquals(baseline, failingStore.snapshot(fixture.ledgerId))
            assertEquals(0L, database.ledgerQueries.countRg09Operations(fixture.ledgerId.value).executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg09FormalTransactions(fixture.ledgerId.value).executeAsOne())

            assertIs<Rg09ExecutionResult.Accepted>(store(database, driver, fixture).commit(preview))
        } finally {
            driver.close()
        }
    }

    @Test
    fun schemaGuardsRejectImmutableSequenceAndWrongEvidenceTargetWrites() {
        val fixture = loadFixture()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = store(database, driver, fixture)
            fixture.operations.take(4).forEach { item ->
                assertIs<Rg09ExecutionResult.Accepted>(store.commit(item.operation), item.id)
            }
            val snapshot = store.snapshot(fixture.ledgerId)

            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "UPDATE rg09_source SET source_type = 'tampered' WHERE source_id = 'source-target-observation-rg09'",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                    INSERT INTO rg09_candidate_status_history(
                      ledger_id, candidate_id, status_sequence, status_id, status, occurred_at,
                      adjustment_id, confirmation_request_id, operation_identity
                    ) VALUES (
                      'ledger-rg09', 'candidate-adjustment-rg09', 4,
                      'candidate-status-invalid-rg09', 'CONFIRMED', '2026-02-02T09:05:00+08:00',
                      'adjustment-rg09', 'request-confirm-adjustment-rg09', 'request-confirm-adjustment-rg09'
                    )
                    """.trimIndent(),
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                    INSERT INTO rg09_evidence_link(
                      ledger_id, link_id, source_id, evidence_id, target_kind, target_id, role, status
                    ) VALUES (
                      'ledger-rg09', 'evidence-link-invalid-target-rg09',
                      'source-target-observation-rg09', 'evidence-target-rg09',
                      'POSTING', 'posting-adjustment-asset-rg09', 'REAL_ACCOUNT_POSTING', 'MATCHED'
                    )
                    """.trimIndent(),
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                    INSERT INTO rg09_balance_adjustment(
                      ledger_id, adjustment_id, transaction_id, observation_id,
                      target_account_id, equity_account_id, currency_code, currency_precision,
                      target_observed_at, target_observed_at_text, replayed_amount_minor,
                      target_amount_minor, original_delta_minor
                    ) VALUES (
                      'ledger-rg09', 'adjustment-invalid-owner-rg09', 'transaction-transfer-rg09',
                      'observation-target-rg09', 'asset-a', 'equity-balance-adjustments', 'CNY', 2,
                      '2026-01-31T15:59:59Z', '2026-01-31T23:59:59+08:00', 10000,
                      13000, 3000
                    )
                    """.trimIndent(),
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                    INSERT INTO rg09_posting_semantic(
                      ledger_id, posting_id, role, reconciliation_eligible
                    ) VALUES (
                      'ledger-rg09', 'posting-opening-a-rg09', 'TRANSFER_SOURCE', 1
                    )
                    """.trimIndent(),
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    """
                    INSERT INTO rg09_confirmation(
                      ledger_id, confirmation_id, request_id, role, confirmed_at,
                      confirmed_at_text, created_at, created_at_text, target_id
                    ) VALUES (
                      'ledger-rg09', 'confirmation-invalid-owner-rg09',
                      'request-invalid-owner-rg09', 'REAL_TRANSFER_CONFIRMATION',
                      '2026-02-12T02:00:00Z', '2026-02-12T10:00:00+08:00',
                      '2026-02-12T02:00:00Z', '2026-02-12T10:00:00+08:00',
                      'transaction-adjustment-rg09'
                    )
                    """.trimIndent(),
                    0,
                )
            }
            assertPersistedSnapshotEquals(snapshot, store.snapshot(fixture.ledgerId))
        } finally {
            driver.close()
        }
    }

    private fun store(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg09FixtureCase,
    ) = SqlDelightRg09Store(database, driver, fixture.catalog, fixture.openingTransactions)

    private fun insertFormalWithoutRg09Metadata(
        database: LedgerDatabase,
        record: Rg09FormalTransactionRecord,
    ) {
        val formal = record.formalTransaction
        formal.postingSets.forEach { postingSet ->
            database.ledgerQueries.insertPostingSet(
                postingSet.id.value,
                formal.transaction.ledgerId.value,
            )
        }
        database.ledgerQueries.insertTransaction(
            formal.transaction.id.value,
            formal.transaction.ledgerId.value,
            formal.transaction.kind.name,
        )
        formal.versions.forEach { version ->
            database.ledgerQueries.insertTransactionVersion(
                version.id.value,
                version.transactionId.value,
                formal.transaction.ledgerId.value,
                version.versionNumber.toLong(),
                version.postingSetId.value,
                version.times.occurredAt.toString(),
                version.times.statisticsAt.toString(),
                version.times.effectiveAt.toString(),
                version.note,
            )
        }
        database.ledgerQueries.insertTransactionCurrentVersion(
            formal.transaction.id.value,
            formal.transaction.ledgerId.value,
            formal.transaction.currentVersionId.value,
        )
        formal.postingSets.forEach { postingSet ->
            postingSet.postings.forEachIndexed { index, posting ->
                database.ledgerQueries.insertPosting(
                    posting.id.value,
                    postingSet.id.value,
                    formal.transaction.ledgerId.value,
                    index.toLong(),
                    posting.accountId.value,
                    posting.amount.minorUnits,
                    posting.amount.currency.code,
                    posting.amount.currency.precision
                        .toLong(),
                )
            }
        }
    }

    private fun loadFixture(): Rg09FixtureCase =
        adaptRg09Fixture(
            Files.readString(repositoryFile("golden/rules/rg-09.json")),
            loadRuntimeInputs(),
        )

    private fun loadRuntimeInputs() = parseRg09FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg09-runtime-input.json")))

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private fun sqliteProperties() =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }

    private fun assertPersistedSnapshotEquals(
        expected: Rg09Snapshot,
        actual: Rg09Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection), actual.formalTransactions.map(::formalProjection))
        assertEquals(expected.observations, actual.observations)
        assertEquals(expected.candidates, actual.candidates)
        assertEquals(expected.sourceRecords, actual.sourceRecords)
        assertEquals(expected.evidence, actual.evidence)
        assertEquals(expected.evidenceLinks, actual.evidenceLinks)
        assertEquals(expected.confirmations, actual.confirmations)
        assertEquals(expected.adjustments, actual.adjustments)
        assertEquals(expected.allocations, actual.allocations)
        assertEquals(expected.auditLinks, actual.auditLinks)
        assertEquals(expected.balances, actual.balances)
        assertEquals(expected.reports, actual.reports)
        assertEquals(expected.reconciliation, actual.reconciliation)
    }

    private fun assertFormalTimes(
        expected: Rg09FormalTransactionRecord,
        actual: Rg09FormalTransactionRecord,
    ) {
        assertEquals(
            expected.formalTransaction.versions
                .single()
                .times,
            actual.formalTransaction.versions
                .single()
                .times,
        )
        assertEquals(expected.createdAt, actual.createdAt)
        assertEquals(expected.createdAtText, actual.createdAtText)
        assertEquals(expected.statisticsAtText, actual.statisticsAtText)
    }

    private fun formalProjection(record: Rg09FormalTransactionRecord) =
        FormalRecordProjection(
            transaction = record.formalTransaction.transaction,
            versions = record.formalTransaction.versions,
            postingSets =
                record.formalTransaction.postingSets.map {
                    PostingSetProjection(it.id, it.postings)
                },
            createdAt = record.createdAt,
            sourceRecordId = record.sourceRecordId?.value,
            createdAtText = record.createdAtText,
            statisticsAtText = record.statisticsAtText,
        )

    private data class FormalRecordProjection(
        val transaction: Transaction,
        val versions: List<TransactionVersion>,
        val postingSets: List<PostingSetProjection>,
        val createdAt: Instant,
        val sourceRecordId: String?,
        val createdAtText: String?,
        val statisticsAtText: String?,
    )

    private data class PostingSetProjection(
        val id: PostingSetId,
        val postings: List<Posting>,
    )
}
