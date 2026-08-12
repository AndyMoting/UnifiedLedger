package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.Rg10AllocationCommitIds
import com.unifiedledger.application.Rg10AllocationId
import com.unifiedledger.application.Rg10ConsumptionId
import com.unifiedledger.application.Rg10EvidenceId
import com.unifiedledger.application.Rg10ExecutionResult
import com.unifiedledger.application.Rg10FixtureCase
import com.unifiedledger.application.Rg10FormalTransactionRecord
import com.unifiedledger.application.Rg10LotAllocationInput
import com.unifiedledger.application.Rg10MerchantAllocationInput
import com.unifiedledger.application.Rg10Operation
import com.unifiedledger.application.Rg10Snapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.adaptRg10Fixture
import com.unifiedledger.application.parseRg10FixtureInputs
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.StoredValueLotId
import com.unifiedledger.domain.Transaction
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

class SqlDelightRg10StoreTest {
    @Test
    fun mainPathPersistsFormalOwnershipAndReplaysAfterReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-store-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: Rg10Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                fixture.operations.forEach { item ->
                    assertIs<Rg10ExecutionResult.Accepted>(store.commit(item.operation), item.id)
                }
                expectedSnapshot = store.snapshot(fixture.ledgerId)
                assertEquals(4L, database.ledgerQueries.countRg10FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(4L, database.ledgerQueries.countRg10Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.selectRg10AllLots(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(3L, database.ledgerQueries.selectRg10AllLotHistory(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(4L, database.ledgerQueries.selectRg10AllPostingReconciliations(fixture.ledgerId.value).executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedSnapshot, store.snapshot(fixture.ledgerId))
                val retry = fixture.operations.first().operation
                val noChange = assertIs<Rg10ExecutionResult.NoChange>(store.commit(retry))
                assertEquals(
                    listOf("transaction-recharge-rg10", "lot-rg10-20260110-a"),
                    noChange.returnedIds.map(::stableIdValue),
                )
                assertEquals(4L, database.ledgerQueries.countRg10Operations(fixture.ledgerId.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun reconciliationTransitionPersistsAcrossReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-reconcile-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: Rg10Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                fixture.operations.forEach { item ->
                    assertIs<Rg10ExecutionResult.Accepted>(store.commit(item.operation), item.id)
                }
                val merchant = fixture.allOperations.first {
                    it.operation is Rg10Operation.ReconcileMerchantCredit
                }
                assertIs<Rg10ExecutionResult.Accepted>(store.commit(merchant.operation))
                val current = database.ledgerQueries.selectRg10AllPostingReconciliations(fixture.ledgerId.value)
                    .executeAsList().single { it.posting_id == "posting-stored-recharge-rg10" }
                assertEquals("MATCHED", current.status)
                assertEquals(2L, current.latest_sequence)
                assertEquals(
                    listOf("PENDING", "MATCHED"),
                    database.ledgerQueries.selectRg10AllReconciliationHistory(fixture.ledgerId.value)
                        .executeAsList()
                        .filter { it.posting_id == "posting-stored-recharge-rg10" }
                        .map { it.status },
                )
                val link = database.ledgerQueries.selectRg10AllEvidenceLinks(fixture.ledgerId.value)
                    .executeAsList().single { it.link_id == "evidence-link-merchant-recharge-rg10" }
                assertEquals("MATCHED", link.status)
                expectedSnapshot = store.snapshot(fixture.ledgerId)
                assertEquals("matched", expectedSnapshot.reconciliation["posting-stored-recharge-rg10"])
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedSnapshot, store.snapshot(fixture.ledgerId))
                val current = database.ledgerQueries.selectRg10AllPostingReconciliations(fixture.ledgerId.value)
                    .executeAsList().single { it.posting_id == "posting-stored-recharge-rg10" }
                assertEquals("MATCHED", current.status)
                assertEquals(2L, current.latest_sequence)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun importedCandidateAndActivationReconstructionSurviveReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-import-activation-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: Rg10Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val ingestRecharge = fixture.allOperations.first {
                    it.operation is Rg10Operation.IngestStoredValueRechargeCandidate
                }
                assertIs<Rg10ExecutionResult.Accepted>(store.commit(ingestRecharge.operation))
                val activation = fixture.allOperations.first {
                    it.operation is Rg10Operation.ConfirmStoredValueActivationBalance
                }
                assertIs<Rg10ExecutionResult.Accepted>(store.commit(activation.operation))
                expectedSnapshot = store.snapshot(fixture.ledgerId)
                assertEquals(1, expectedSnapshot.candidates.size)
                assertEquals("pending_confirmation", expectedSnapshot.candidates.single().status)
                assertEquals(1, expectedSnapshot.reconstructions.size)
                assertEquals(1, expectedSnapshot.auditLinks.size)
                assertEquals(1L, database.ledgerQueries.selectRg10AllReconstructions(fixture.ledgerId.value)
                    .executeAsList().size.toLong())
                assertEquals(1L, database.ledgerQueries.selectRg10AllReconstructionHistory(fixture.ledgerId.value)
                    .executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedSnapshot, store.snapshot(fixture.ledgerId))
                assertEquals(
                    "PENDING_CONFIRMATION",
                    database.ledgerQueries.selectRg10AllCandidates(fixture.ledgerId.value)
                        .executeAsList().single().status,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectedOperationPersistsReceiptWithZeroDependentRows() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-rejected-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val invalidItem = fixture.allOperations.first {
                    it.operation is Rg10Operation.InvalidInput && it.id == "spend-over-balance"
                }
                val invalid = invalidItem.operation as Rg10Operation.InvalidInput
                val result = store.commit(invalidItem.operation)
                val rejected = assertIs<Rg10ExecutionResult.Rejected>(result)
                assertEquals("insufficient_effective_stored_balance", rejected.reason.code)
                val saved = database.ledgerQueries.selectRg10Operation(
                    fixture.ledgerId.value,
                    invalidItem.operation.identity.value,
                ).executeAsOne()
                assertEquals("REJECTED", saved.outcome)
                assertEquals("insufficient_effective_stored_balance", saved.reason_code)
                assertEquals(1L, database.ledgerQueries.countRg10FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg10AllLots(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg10AllSources(fixture.ledgerId.value).executeAsList().size.toLong())

                // Replay of the same rejected input is stable.
                assertEquals(rejected, store.commit(invalidItem.operation))
                // A changed fingerprint on the same identity is a conflict.
                val changed = invalid.copy(
                    input = invalid.input.copy(
                        attemptedInput = invalid.input.attemptedInput +
                            (com.unifiedledger.application.Rg10FieldPath.ATTEMPTED_AMOUNT.value to "1.00"),
                    ),
                )
                assertEquals(Rg10ExecutionResult.RequestIdentityConflict, store.commit(changed))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun failureAfterDeltaRollsBackClaimAndEveryDependentWrite() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val failing = store(
                    database,
                    driver,
                    fixture,
                    Rg10FailureInjector { point ->
                        if (point == Rg10FailurePoint.AFTER_DELTA) error("injected RG-10 rollback")
                    },
                )
                val recharge = fixture.operations.first().operation
                assertFailsWith<IllegalStateException> {
                    failing.commit(recharge)
                }
                assertEquals(0L, database.ledgerQueries.countRg10Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg10FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg10AllLots(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg10AllSources(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg10AllEvidenceLinks(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg10AllPostingSemantics(fixture.ledgerId.value).executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val baseline = store.snapshot(fixture.ledgerId)
                assertEquals(1, baseline.formalTransactions.size)
                assertEquals(0, baseline.lots.size)
                val recharge = fixture.operations.first().operation
                assertIs<Rg10ExecutionResult.Accepted>(store.commit(recharge))
                assertEquals(2, store.snapshot(fixture.ledgerId).formalTransactions.size)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun schemaGuardsRejectImmutableSequenceAndWrongEvidenceTargetWrites() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-guards-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                fixture.operations.forEach { item ->
                    assertIs<Rg10ExecutionResult.Accepted>(store.commit(item.operation), item.id)
                }
                val ledger = fixture.ledgerId.value

                assertFailsWith<SQLException> {
                    driver.execute(null, "UPDATE rg10_source SET source_type = 'x' WHERE source_id = 'source-bank-payment-rg10'", 0)
                }
                assertFailsWith<SQLException> {
                    driver.execute(null, "DELETE FROM rg10_operation WHERE ledger_id = '$ledger' AND identity_value = 'request-recharge-rg10'", 0)
                }
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg10_lot_history(ledger_id, lot_id, history_sequence, history_id, event, transaction_id, amount_minor, remaining_face_value_minor, occurred_at, occurred_at_text, created_at, created_at_text) VALUES ('$ledger', 'lot-rg10-20260110-a', 5, 'history-gap-rg10', 'SPENT', 'transaction-spend-rg10', -100, 800, '2026-02-01T09:00:00+08:00', '2026-02-01T09:00:00+08:00', '2026-02-01T09:05:00+08:00', '2026-02-01T09:05:00+08:00')",
                        0,
                    )
                }
                // Evidence link targeting the wrong posting role is rejected by the target guard.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg10_evidence_link(ledger_id, link_id, source_id, evidence_id, target_kind, target_id, role, status) VALUES ('$ledger', 'link-wrong-target-rg10', 'source-merchant-credit-rg10', 'evidence-merchant-credit-rg10', 'POSTING', 'posting-bank-recharge-rg10', 'STORED_VALUE_ASSET_POSTING', 'PENDING')",
                        0,
                    )
                }
                // A posting semantic must own its formal posting kind.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg10_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible) VALUES ('$ledger', 'posting-bank-recharge-rg10', 'EXPENSE_OUT', 0)",
                        0,
                    )
                }
                // Reconciliation current rows only transition through a matched evidence link.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "UPDATE rg10_posting_reconciliation SET status = 'MATCHED', latest_sequence = 2 WHERE ledger_id = '$ledger' AND posting_id = 'posting-bank-recharge-rg10'",
                        0,
                    )
                }
                // Lot remaining updates must agree with the newest history or consumption row.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "UPDATE rg10_lot SET remaining_face_value_minor = 700 WHERE ledger_id = '$ledger' AND lot_id = 'lot-rg10-20260110-a'",
                        0,
                    )
                }
                assertEquals(3L, database.ledgerQueries.selectRg10AllLotHistory(ledger).executeAsList().size.toLong())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun merchantAllocationPersistsThroughTheSyntheticBaseline() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-allocation-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: Rg10Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                // Seed the frozen synthetic baseline (four lots plus merchant allocation
                // source/evidence) so the persisted owner matches the fixture baseline state.
                val baseline = fixture.baselines.getValue("state-rg10-merchant-allocation-baseline")
                val baselineSnapshot = baseline.snapshot()
                baselineSnapshot.lots.forEach { lot ->
                    database.ledgerQueries.insertRg10Lot(
                        fixture.ledgerId.value,
                        lot.id.value,
                        null,
                        lot.loadedAt.toString(),
                        lot.loadedAtText,
                        lot.expiresAt.toString(),
                        lot.expiresAtText,
                        lot.faceValue.minorUnits,
                        lot.remainingFaceValue.minorUnits,
                        null,
                        null,
                        null,
                        null,
                        lot.compositionStatus.uppercase(),
                        lot.faceValue.currency.code,
                        lot.faceValue.currency.precision.toLong(),
                        null,
                    )
                }
                baselineSnapshot.sourceRecords.forEach { source ->
                    database.ledgerQueries.insertRg10Source(
                        fixture.ledgerId.value,
                        source.id.value,
                        source.sourceType,
                        source.observedAt.toString(),
                        source.observedAtText,
                        source.accountId?.value,
                        source.amount?.minorUnits,
                        null,
                        source.amount?.currency?.code,
                        source.amount?.currency?.precision?.toLong(),
                        source.immutablePayloadDigest,
                    )
                }
                baselineSnapshot.evidence.forEach { item ->
                    database.ledgerQueries.insertRg10Evidence(
                        fixture.ledgerId.value,
                        item.id.value,
                        item.sourceId.value,
                        item.evidenceType,
                        item.observedAt.toString(),
                        item.observedAtText,
                    )
                }
                val allocation = fixture.allOperations.first {
                    it.operation is Rg10Operation.ApplyMerchantLotAllocation
                }
                assertIs<Rg10ExecutionResult.Accepted>(store.commit(allocation.operation))
                expectedSnapshot = store.snapshot(fixture.ledgerId)
                assertEquals(1, expectedSnapshot.allocations.size)
                assertEquals(1, expectedSnapshot.consumptions.size)
                assertEquals(4L, database.ledgerQueries.selectRg10AllLots(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(1L, database.ledgerQueries.selectRg10AllAllocations(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(1L, database.ledgerQueries.selectRg10AllConsumptions(fixture.ledgerId.value).executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedSnapshot, store.snapshot(fixture.ledgerId))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun lotGuardRejectsRepeatedDeductionOfTheSameConsumptionRow() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg10-dup-deduction-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val cny = CurrencyUnit("CNY", 2)
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val ledger = fixture.ledgerId.value
                database.ledgerQueries.insertRg10Lot(
                    ledger,
                    "lot-rg10-expiring-first",
                    null,
                    "2026-01-01T09:00:00+08:00",
                    "2026-01-01T09:00:00+08:00",
                    "2026-02-01T23:59:59+08:00",
                    "2026-02-01T23:59:59+08:00",
                    50_000L,
                    50_000L,
                    null,
                    null,
                    null,
                    null,
                    "UNKNOWN",
                    "CNY",
                    2L,
                    null,
                )
                database.ledgerQueries.insertRg10Source(
                    ledger,
                    "source-merchant-allocation-rg10",
                    "merchant_lot_allocation",
                    "2026-01-20T12:01:00+08:00",
                    "2026-01-20T12:01:00+08:00",
                    "asset-stored-value-x",
                    10_000L,
                    null,
                    "CNY",
                    2L,
                    "sha256:rg10-merchant-allocation",
                )
                database.ledgerQueries.insertRg10Evidence(
                    ledger,
                    "evidence-merchant-allocation-rg10",
                    "source-merchant-allocation-rg10",
                    "merchant_lot_allocation",
                    "2026-01-20T12:01:00+08:00",
                    "2026-01-20T12:01:00+08:00",
                )
                fun allocationOp(requestId: String, allocationId: String, consumptionId: String) =
                    Rg10Operation.ApplyMerchantLotAllocation(
                        ledgerId = fixture.ledgerId,
                        input = Rg10MerchantAllocationInput(
                            requestId = RequestId(requestId),
                            amount = Money.ofMinor(10_000L, cny),
                            merchantAllocationProvided = true,
                            merchantEvidenceId = Rg10EvidenceId("evidence-merchant-allocation-rg10"),
                            allocations = listOf(
                                Rg10LotAllocationInput(
                                    StoredValueLotId("lot-rg10-expiring-first"),
                                    Money.ofMinor(10_000L, cny),
                                ),
                            ),
                            explicitConfirmation = true,
                        ),
                        ids = Rg10AllocationCommitIds(
                            allocationId = Rg10AllocationId(allocationId),
                            consumptionId = Rg10ConsumptionId(consumptionId),
                        ),
                    )

                assertIs<Rg10ExecutionResult.Accepted>(
                    store.commit(allocationOp("request-dup-a-rg10", "allocation-dup-a-rg10", "consumption-dup-a-rg10")),
                )
                assertEquals(40_000L, store.snapshot(fixture.ledgerId).lots.single().remainingFaceValue.minorUnits)

                // Deducting the same 10_000 again with no new consumption row must abort:
                // the recorded resulting remaining never matches a repeated deduction.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "UPDATE rg10_lot SET remaining_face_value_minor = 30000 WHERE ledger_id = '$ledger' AND lot_id = 'lot-rg10-expiring-first'",
                        0,
                    )
                }
                assertEquals(40_000L, store.snapshot(fixture.ledgerId).lots.single().remainingFaceValue.minorUnits)

                // A fresh consumption row still supports the same deduction amount exactly once.
                assertIs<Rg10ExecutionResult.Accepted>(
                    store.commit(allocationOp("request-dup-b-rg10", "allocation-dup-b-rg10", "consumption-dup-b-rg10")),
                )
                assertEquals(30_000L, store.snapshot(fixture.ledgerId).lots.single().remainingFaceValue.minorUnits)
                assertEquals(
                    listOf(40_000L, 30_000L),
                    database.ledgerQueries.selectRg10AllConsumptions(ledger).executeAsList()
                        .map { it.resulting_remaining_face_value_minor },
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun stableIdValue(id: com.unifiedledger.application.Rg10ReturnedId): String = when (id) {
        is com.unifiedledger.application.Rg10ReturnedId.Transaction -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Version -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Lot -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Confirmation -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Candidate -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.EvidenceLink -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Allocation -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Consumption -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Adjustment -> id.id.value
        is com.unifiedledger.application.Rg10ReturnedId.Request -> id.id
    }

    private fun store(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg10FixtureCase,
        failureInjector: Rg10FailureInjector = Rg10FailureInjector { },
    ): SqlDelightRg10Store = SqlDelightRg10Store(
        database,
        driver,
        fixture.catalog,
        fixture.openingTransactions,
        failureInjector,
    )

    /**
     * Structural comparison of a snapshot taken before and after a database reopen.
     * Rg10Snapshot carries FormalTransaction instances (a plain class with reference
     * equality), so the persisted reconstruction can never satisfy `equals` directly.
     * Following the RG-09 precedent (SqlDelightRg09StoreTest.assertPersistedSnapshotEquals),
     * formal transactions are projected onto stable fields; every other member is a data
     * class (or a plain map of data classes) and compares structurally as-is.
     */
    private fun assertPersistedSnapshotEquals(
        expected: Rg10Snapshot,
        actual: Rg10Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection), actual.formalTransactions.map(::formalProjection))
        assertEquals(expected.lots, actual.lots)
        assertEquals(expected.consumptions, actual.consumptions)
        assertEquals(expected.allocations, actual.allocations)
        assertEquals(expected.adjustments, actual.adjustments)
        assertEquals(expected.reconstructions, actual.reconstructions)
        assertEquals(expected.candidates, actual.candidates)
        assertEquals(expected.confirmations, actual.confirmations)
        assertEquals(expected.sourceRecords, actual.sourceRecords)
        assertEquals(expected.evidence, actual.evidence)
        assertEquals(expected.evidenceLinks, actual.evidenceLinks)
        assertEquals(expected.auditLinks, actual.auditLinks)
        assertEquals(expected.postingSemantics, actual.postingSemantics)
        assertEquals(expected.balances, actual.balances)
        assertEquals(expected.reports, actual.reports)
        assertEquals(expected.reconciliation, actual.reconciliation)
    }

    private fun formalProjection(record: Rg10FormalTransactionRecord) = FormalRecordProjection(
        transaction = record.formalTransaction.transaction,
        versions = record.formalTransaction.versions,
        postingSets = record.formalTransaction.postingSets.map {
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

    private fun loadFixture(): Rg10FixtureCase =
        adaptRg10Fixture(
            Files.readString(repositoryFile("golden/rules/rg-10.json")),
            loadRuntimeInputs(),
        )

    private fun loadRuntimeInputs() =
        parseRg10FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg10-runtime-input.json")))

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private fun sqliteProperties() = Properties().apply {
        setProperty("foreign_keys", "true")
    }
}
