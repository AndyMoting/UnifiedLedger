package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.Rg08ExecutionResult
import com.unifiedledger.application.Rg08FieldPath
import com.unifiedledger.application.Rg08FixtureCase
import com.unifiedledger.application.Rg08FormalTransactionRecord
import com.unifiedledger.application.Rg08Operation
import com.unifiedledger.application.Rg08RejectionReason
import com.unifiedledger.application.Rg08ReturnedId
import com.unifiedledger.application.Rg08Runtime
import com.unifiedledger.application.Rg08Snapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.adaptRg08Fixture
import com.unifiedledger.application.parseRg08FixtureInputs
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LendingAuditLinkKind
import com.unifiedledger.domain.LendingCandidateStatus
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingSetId
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-084 RG-08 formal SQLDelight persistence tests (RG08-QA-01 / RG08-SPEC-002): the store
 * runtime path beyond the migration-level and oracle coverage — commit with returned ids,
 * reopen snapshot equality, same-fingerprint replay, rejected replay, failure injection at
 * both registered points (AFTER_CLAIM / AFTER_DELTA), trigger-violation rollback, pending
 * claim safety and the operation-layer zero-principal rejection (RG08-QA-02). Inputs are
 * constructed from the frozen fixture replay (Rg08FixtureReplay adapter), following the
 * RG-10 precedent (SqlDelightRg10StoreTest).
 *
 * The frozen fixture is a multi-root story: every operation family replays against its own
 * baseline (lend-confirmed, import-pending, ...), so the 32 v1 operations cannot all commit
 * serially against the store's single persisted ledger state (e.g. cap-maximum needs the
 * lend-confirmed balance while manual-collection consumes it). The store tests therefore
 * drive the compatible linear main branch (lend -> manual -> rename, with the
 * state-independent rejected families) and the frozen import branch (lend -> import-candidate
 * -> confirm-import -> mirror-evidence), which exercises the candidate/source-record foreign
 * key ordering in `SqlDelightRg08Store.persistDelta` (sources must be persisted before the
 * candidate's `rg08_candidate_source` links).
 */
class SqlDelightRg08StoreTest {
    @Test
    fun mainPathPersistsFormalOwnershipAndReplaysAfterReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-store-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedSnapshot: Rg08Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val mainBranch = fixture.operations.filter {
                    it.id in MAIN_BRANCH_IDS
                }.sortedBy { MAIN_BRANCH_IDS.indexOf(it.id) }
                assertEquals(22, mainBranch.size, "main branch operation count")
                val lend = mainBranch.first { it.id == "lend" }
                val lendIds = (lend.operation as Rg08Operation.ValidateLendingEvent).ids
                val lendAccepted = assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation), lend.id)
                assertEquals(
                    listOf(
                        Rg08ReturnedId.Transaction(lendIds.transactionId),
                        Rg08ReturnedId.Version(lendIds.versionId),
                        Rg08ReturnedId.Position(lendIds.positionId),
                    ),
                    lendAccepted.returnedIds,
                    "lend returned ids",
                )
                mainBranch.drop(1).forEach { item ->
                    val result = store.commit(item.operation)
                    when (item.expectedStatus) {
                        "accepted", "no_change" ->
                            assertIs<Rg08ExecutionResult.Accepted>(result, "${item.id}: expected accepted")
                        "rejected" -> assertIs<Rg08ExecutionResult.Rejected>(result, "${item.id}: expected rejected")
                        else -> error("unsupported expected status ${item.expectedStatus} for ${item.id}")
                    }
                }
                expectedSnapshot = store.snapshot(fixture.ledgerId)
                assertEquals(22L, database.ledgerQueries.countRg08Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(3L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(1, expectedSnapshot.positions.size)
                assertEquals(6_000L, expectedSnapshot.positions.single().principalBalanceMinor)
                assertEquals(1, expectedSnapshot.settlements.size)
                assertEquals(
                    1L,
                    database.ledgerQueries.selectRg08AllNameHistory(fixture.ledgerId.value)
                        .executeAsList().size.toLong(),
                )
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
    fun idempotentReplayReturnsNoChangeWithFirstTimeIdsAfterReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-replay-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                fixture.operations
                    .filter { it.id in ACCEPTED_MAIN_BRANCH_IDS }
                    .sortedBy { ACCEPTED_MAIN_BRANCH_IDS.indexOf(it.id) }
                    .forEach { item ->
                        assertIs<Rg08ExecutionResult.Accepted>(store.commit(item.operation), item.id)
                    }
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val manual = fixture.operations.first { it.id == "manual-collection" }
                    .operation as Rg08Operation.ValidateLendingSettlement
                val noChange = assertIs<Rg08ExecutionResult.NoChange>(store.commit(manual))
                assertEquals(
                    listOf(
                        Rg08ReturnedId.Transaction(manual.ids.transactionId),
                        Rg08ReturnedId.Version(manual.ids.versionId),
                        Rg08ReturnedId.Settlement(manual.ids.settlementId),
                        Rg08ReturnedId.Component(manual.ids.principalComponentId),
                        Rg08ReturnedId.Component(manual.ids.interestComponentId),
                        Rg08ReturnedId.Component(manual.ids.feeComponentId),
                    ),
                    noChange.returnedIds,
                    "no-change ids equal first-time ids",
                )
                assertEquals(3L, database.ledgerQueries.countRg08Operations(fixture.ledgerId.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectedOperationPersistsReceiptWithZeroDependentRows() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-rejected-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val cny = CurrencyUnit("CNY", 2)
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation), lend.id)
                val overBalance = fixture.operations.first { it.id == "over-balance-attempt" }
                val rejected = assertIs<Rg08ExecutionResult.Rejected>(store.commit(overBalance.operation))
                assertEquals(Rg08RejectionReason.PRINCIPAL_EXCEEDS_OUTSTANDING_POSITION, rejected.reason)
                val saved = database.ledgerQueries
                    .selectRg08Operation(fixture.ledgerId.value, overBalance.operation.identity.value)
                    .executeAsOne()
                assertEquals("REJECTED", saved.outcome)
                assertEquals("principal_exceeds_outstanding_position", saved.reason_code)
                assertEquals(2L, database.ledgerQueries.countRg08Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
                val snapshot = store.snapshot(fixture.ledgerId)
                assertEquals(1, snapshot.positions.size)
                assertEquals(10_000L, snapshot.positions.single().principalBalanceMinor)
                assertEquals(0, snapshot.settlements.size)

                // Replay of the same rejected input is stable.
                assertEquals(rejected, store.commit(overBalance.operation))
                // A changed fingerprint on the same identity is a conflict.
                val allocate = overBalance.operation as Rg08Operation.AllocateLendingCollection
                val changed = allocate.copy(
                    input = allocate.input.copy(
                        interestAmount = Money.ofMinor(501L, cny),
                    ),
                )
                assertEquals(Rg08ExecutionResult.RequestIdentityConflict, store.commit(changed))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun zeroPrincipalCollectionIsRejectedAtTheOperationLayer() {
        // RG08-QA-02: an interest-only collection (principal == 0) is accepted by the domain
        // direction rule but would violate the persisted COLLECT direction guard
        // (`rg08_position_history_direction` rejects COLLECT amount >= 0), so the operation
        // layer rejects it explicitly before any write; the DB trigger stays strict and
        // untouched. The rejection persists as a normal REJECTED receipt with zero delta.
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-zero-principal-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation), lend.id)
                val manual = fixture.operations.first { it.id == "manual-collection" }
                    .operation as Rg08Operation.ValidateLendingSettlement
                val cny = CurrencyUnit("CNY", 2)
                val zeroPrincipal = manual.copy(
                    input = manual.input.copy(
                        requestId = RequestId("request-rg08-zero-principal"),
                        totalReceived = Money.ofMinor(500L, cny),
                        principalAmount = Money.ofMinor(0L, cny),
                        interestAmount = Money.ofMinor(500L, cny),
                    ),
                )
                val rejected = assertIs<Rg08ExecutionResult.Rejected>(store.commit(zeroPrincipal))
                assertEquals(Rg08RejectionReason.PRINCIPAL_MUST_BE_POSITIVE, rejected.reason)
                assertEquals(Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT, rejected.fieldPath)
                val saved = database.ledgerQueries
                    .selectRg08Operation(fixture.ledgerId.value, zeroPrincipal.identity.value)
                    .executeAsOne()
                assertEquals("REJECTED", saved.outcome)
                assertEquals("principal_must_be_positive", saved.reason_code)
                assertEquals(2L, database.ledgerQueries.countRg08Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
                val snapshot = store.snapshot(fixture.ledgerId)
                assertEquals(1, snapshot.positions.size)
                assertEquals(10_000L, snapshot.positions.single().principalBalanceMinor)
                assertEquals(0, snapshot.settlements.size)
                // Stable replay of the same rejected input.
                assertEquals(rejected, store.commit(zeroPrincipal))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun failureAfterClaimRollsBackClaimAndEveryDependentWrite() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-claim-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val failing = store(
                    database,
                    driver,
                    fixture,
                    Rg08FailureInjector { point ->
                        if (point == Rg08FailurePoint.AFTER_CLAIM) error("injected RG-08 claim failure")
                    },
                )
                val lend = fixture.operations.first { it.id == "lend" }
                assertFailsWith<IllegalStateException> { failing.commit(lend.operation) }
                assertEquals(0L, database.ledgerQueries.countRg08Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg08AllPositions(fixture.ledgerId.value).executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }
                assertEquals(1, store.snapshot(fixture.ledgerId).formalTransactions.size)
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation))
                assertEquals(2, store.snapshot(fixture.ledgerId).formalTransactions.size)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun failureAfterDeltaRollsBackClaimAndEveryDependentWrite() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-delta-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val failing = store(
                    database,
                    driver,
                    fixture,
                    Rg08FailureInjector { point ->
                        if (point == Rg08FailurePoint.AFTER_DELTA) error("injected RG-08 delta failure")
                    },
                )
                val lend = fixture.operations.first { it.id == "lend" }
                assertFailsWith<IllegalStateException> { failing.commit(lend.operation) }
                assertEquals(0L, database.ledgerQueries.countRg08Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg08AllPositions(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg08AllSettlements(fixture.ledgerId.value).executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }
                val baseline = store.snapshot(fixture.ledgerId)
                assertEquals(1, baseline.formalTransactions.size)
                assertEquals(0, baseline.positions.size)
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation))
                assertEquals(2, store.snapshot(fixture.ledgerId).formalTransactions.size)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun triggerViolationRollsBackClaimAndEveryDependentWrite() {
        // RG08-QA-01: a guard-trigger abort inside the commit transaction (here: the lend
        // position current-projection guard) must roll back the claim and every dependent
        // write; the operation is never left in a half-finalized state.
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-trigger-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val failing = store(
                    database,
                    driver,
                    fixture,
                    Rg08FailureInjector { point ->
                        if (point == Rg08FailurePoint.AFTER_DELTA) {
                            // The lend delta is already persisted inside the commit
                            // transaction; this UPDATE violates rg08_position_guard_update
                            // (new principal balance must be <= old and match the newest
                            // COLLECT history row), aborting the whole transaction. The
                            // generated query executes eagerly, so the trigger abort
                            // propagates from this call.
                            database.ledgerQueries
                                .updateRg08PositionBalance(9_999L, fixture.ledgerId.value, "lending-position-rg08")
                        }
                    },
                )
                val lend = fixture.operations.first { it.id == "lend" }
                assertFailsWith<SQLException> { failing.commit(lend.operation) }
                assertEquals(0L, database.ledgerQueries.countRg08Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg08AllPositions(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg08AllSettlements(fixture.ledgerId.value).executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation))
                assertEquals(2, store.snapshot(fixture.ledgerId).formalTransactions.size)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun pendingClaimIsNeverSilentlyFinalized() {
        // A claim persisted by an interrupted attempt stays PENDING; the store refuses to
        // finalize it (Known Records: `cannot finalize`/`still pending` hard errors) instead
        // of guessing an outcome, and the PENDING row is left untouched.
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-pending-claim-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }.operation as Rg08Operation.ValidateLendingEvent
                val fingerprint = Rg08Runtime(fixture.catalog, fixture.lendingCatalog, emptyList())
                    .operationFingerprint(lend)
                database.ledgerQueries.insertRg08Operation(
                    fixture.ledgerId.value,
                    lend.identity.value,
                    lend.action.code,
                    "validate_lending_event",
                    fingerprint,
                    "PENDING",
                    null,
                    null,
                )
                val error = assertFailsWith<IllegalStateException> { store.commit(lend) }
                assertTrue(error.message!!.contains("still pending"), "error names the pending state")
                val saved = database.ledgerQueries
                    .selectRg08Operation(fixture.ledgerId.value, lend.identity.value)
                    .executeAsOne()
                assertEquals("PENDING", saved.outcome)
                assertEquals(0, database.ledgerQueries
                    .selectRg08ReturnedIds(fixture.ledgerId.value, lend.identity.value)
                    .executeAsList().size)
                assertEquals(1L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun importBranchIntakePersistsCandidateSourcesAndReloadsAfterReopen() {
        // The intake commit inserts rg08_candidate_source rows whose rg08_source_record
        // parents must already exist (immediate foreign key). `persistDelta` must persist
        // the source records before the candidate delta, otherwise every
        // IngestImportedCollectionCandidate commit aborts with SQLITE_CONSTRAINT_FOREIGNKEY.
        // This test drives the frozen import root (lend -> import-candidate) against the
        // persisted store and verifies the candidate, its source links, the source records,
        // evidence and links all survive a reopen.
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-intake-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedAfterIntake: Rg08Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation), lend.id)
                val intake = fixture.operations.first { it.id == "import-candidate" }
                assertEquals("accepted", intake.expectedStatus, intake.id)
                val intakeInput = (intake.operation as Rg08Operation.IngestImportedCollectionCandidate).input
                val accepted = assertIs<Rg08ExecutionResult.Accepted>(store.commit(intake.operation), intake.id)
                assertEquals(
                    listOf(
                        Rg08ReturnedId.SourceRecord(intakeInput.creditSourceId),
                        Rg08ReturnedId.Candidate(intakeInput.candidateId),
                    ),
                    accepted.returnedIds,
                    "import-candidate returned ids",
                )
                expectedAfterIntake = store.snapshot(fixture.ledgerId)
                assertEquals(1, expectedAfterIntake.candidates.size)
                val pending = expectedAfterIntake.candidates.single()
                assertEquals(LendingCandidateStatus.PENDING_CONFIRMATION, pending.status)
                assertEquals(
                    listOf(intakeInput.creditSourceId.value, intakeInput.agreementSourceId.value),
                    pending.sourceIds,
                    "candidate source links",
                )
                assertEquals(3, expectedAfterIntake.sourceRecords.size)
                assertEquals(3, expectedAfterIntake.evidence.size)
                assertEquals(2, expectedAfterIntake.evidenceLinks.size)
                assertEquals(2L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedAfterIntake, store.snapshot(fixture.ledgerId))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun importBranchConfirmsAndMirrorsAfterReopen() {
        // ConfirmImportedCollection and MergeImportedEvidence commit against the reopened
        // store: the confirm books the settlement and advances the candidate to CONFIRMED
        // (with the MATCHED destination evidence link driving the reconciliation
        // transition), the mirror adds the BANK_CREDIT_MIRROR source (its origin was
        // persisted by the intake) plus the typed audit links. All dependent rows persist
        // without FK or trigger violations and the final snapshot reloads identically.
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg08-confirm-mirror-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expectedFinal: Rg08Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val lend = fixture.operations.first { it.id == "lend" }
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(lend.operation), lend.id)
                val intake = fixture.operations.first { it.id == "import-candidate" }
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(intake.operation), intake.id)
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                val confirm = fixture.operations.first { it.id == "confirm-import" }
                assertEquals("accepted", confirm.expectedStatus, confirm.id)
                val confirmInput = (confirm.operation as Rg08Operation.ConfirmImportedCollection).input
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(confirm.operation), confirm.id)
                val confirmed = store.snapshot(fixture.ledgerId)
                assertEquals(LendingCandidateStatus.CONFIRMED, confirmed.candidates.single().status)
                val provenance = confirmed.confirmations.single { it.confirmationRequestId == confirmInput.requestId.value }
                assertEquals(confirmInput.candidateId.value, provenance.candidateId)
                assertEquals("settlement-rg08-import", provenance.settlementId)
                val settlement = confirmed.settlements.single()
                assertEquals("settlement-rg08-import", settlement.id)
                assertEquals(4_500L, settlement.totalReceivedMinor)
                assertEquals(6_000L, confirmed.positions.single().principalBalanceMinor)
                assertEquals(3L, database.ledgerQueries.countRg08FormalTransactions(fixture.ledgerId.value).executeAsOne())

                val mirror = fixture.operations.first { it.id == "mirror-evidence" }
                assertEquals("accepted", mirror.expectedStatus, mirror.id)
                assertIs<Rg08ExecutionResult.Accepted>(store.commit(mirror.operation), mirror.id)
                expectedFinal = store.snapshot(fixture.ledgerId)
                assertEquals(
                    "source-rg08-import-credit",
                    expectedFinal.sourceRecords.single { it.id == "source-rg08-import-mirror" }.mirrorOfSourceId,
                    "mirror source origin",
                )
                assertEquals(4, expectedFinal.sourceRecords.size)
                assertEquals(4, expectedFinal.evidence.size)
                assertEquals(4, expectedFinal.evidenceLinks.size)
                assertEquals(
                    setOf("evidence-rg08-import-credit"),
                    expectedFinal.auditLinks.filter { it.kind == LendingAuditLinkKind.MIRROR_OF_EVIDENCE }
                        .map { it.toId }.toSet(),
                    "mirror-of-evidence audit links",
                )
                assertEquals(
                    setOf("evidence-link-rg08-import-posting"),
                    expectedFinal.auditLinks.filter { it.kind == LendingAuditLinkKind.MERGED_INTO_EVIDENCE_LINK }
                        .map { it.toId }.toSet(),
                    "merged-into-evidence-link audit links",
                )
                assertEquals(6_000L, expectedFinal.positions.single().principalBalanceMinor)
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture)
                assertPersistedSnapshotEquals(expectedFinal, store.snapshot(fixture.ledgerId))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun store(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg08FixtureCase,
        failureInjector: Rg08FailureInjector = Rg08FailureInjector { },
    ): SqlDelightRg08Store = SqlDelightRg08Store(
        database,
        driver,
        fixture.catalog,
        fixture.lendingCatalog,
        fixture.openingTransactions,
        failureInjector,
    )

    /**
     * Structural comparison of a snapshot taken before and after a database reopen.
     * Rg08Snapshot carries FormalTransaction instances (a plain class with reference
     * equality), so the persisted reconstruction can never satisfy `equals` directly.
     * Following the RG-09/RG-10 precedent, formal transactions are projected onto stable
     * fields; every other member is a data class (or a plain map of data classes) and
     * compares structurally as-is.
     */
    private fun assertPersistedSnapshotEquals(
        expected: Rg08Snapshot,
        actual: Rg08Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection), actual.formalTransactions.map(::formalProjection))
        assertEquals(expected.positions, actual.positions)
        assertEquals(expected.settlements, actual.settlements)
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
        assertEquals(expected.counterpartyNames, actual.counterpartyNames)
    }

    private fun formalProjection(record: Rg08FormalTransactionRecord) = FormalRecordProjection(
        transaction = record.formalTransaction.transaction,
        versions = record.formalTransaction.versions,
        postingSets = record.formalTransaction.postingSets.map {
            PostingSetProjection(it.id, it.postings)
        },
        createdAt = record.createdAt,
        createdAtText = record.createdAtText,
        statisticsAtText = record.statisticsAtText,
    )

    private data class FormalRecordProjection(
        val transaction: Transaction,
        val versions: List<TransactionVersion>,
        val postingSets: List<PostingSetProjection>,
        val createdAt: Instant,
        val createdAtText: String?,
        val statisticsAtText: String?,
    )

    private data class PostingSetProjection(
        val id: PostingSetId,
        val postings: List<Posting>,
    )

    private fun loadFixture(): Rg08FixtureCase =
        adaptRg08Fixture(
            Files.readString(repositoryFile("golden/rules/rg-08.json")),
            parseRg08FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg08-runtime-input.json"))),
        )

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

    private companion object {
        /**
         * Compatible linear main branch of the frozen multi-root story: lend and the manual
         * collection (frozen principal 40.00, leaving 60.00 outstanding), the over-balance
         * attempt and the 18 invalid inputs reject on their inputs (state-independent), and
         * the rename accepts with zero formal effect.
         */
        val MAIN_BRANCH_IDS = listOf(
            "lend",
            "manual-collection",
            "over-balance-attempt",
            "rename-counterparty",
            "floating-total",
            "zero-total",
            "negative-total",
            "component-sum-mismatch",
            "negative-principal",
            "negative-interest",
            "negative-fee",
            "positive-fee",
            "principal-over-balance",
            "unknown-destination",
            "unowned-destination",
            "nonfinancial-destination",
            "unknown-funding-account",
            "unknown-counterparty",
            "invalid-behavior",
            "guessed-split",
            "cross-currency",
            "inactive-interest-category",
        )

        /** The main branch operations that accept (used for the idempotent replay test). */
        val ACCEPTED_MAIN_BRANCH_IDS = listOf(
            "lend",
            "manual-collection",
            "rename-counterparty",
        )
    }
}
