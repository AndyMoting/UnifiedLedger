package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.Rg12Action
import com.unifiedledger.application.Rg12ExecutionResult
import com.unifiedledger.application.Rg12FixtureCase
import com.unifiedledger.application.Rg12Operation
import com.unifiedledger.application.Rg12RejectionReason
import com.unifiedledger.application.Rg12RetryInput
import com.unifiedledger.application.Rg12ReturnedId
import com.unifiedledger.application.Rg12Runtime
import com.unifiedledger.application.Rg12Snapshot
import com.unifiedledger.application.adaptRg12Fixture
import com.unifiedledger.application.parseRg12FixtureInputs
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingReconciliationStatus
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.ReconciliationEffect
import com.unifiedledger.domain.ReconciliationMatchStatus
import com.unifiedledger.domain.ReconciliationSummary
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * D-085 RG-12 formal SQLDelight persistence tests (aligned with SqlDelightRg11StoreTest /
 * SqlDelightRg08StoreTest): the store runtime path beyond the migration-level coverage —
 * commit of the accepted `root-correction-correct` with the v2 posting set shared-table
 * inserts, the replacement chain, match invalidation/inheritance, facts, consumption,
 * confirmation and report periods, snapshot equality across reopen, same-fingerprint
 * replay and retry-by-identity with the first-time ids, identity conflict on a changed
 * fingerprint, rejected receipt with zero dependent rows, failure injection at both
 * registered points (AFTER_CLAIM / AFTER_DELTA), a guard-trigger abort inside the commit
 * transaction, a persisted PENDING claim that is never silently finalized, and raw
 * guard-bypass rejections.
 *
 * Inputs are constructed through the fixture replay adapter (Rg12FixtureReplay):
 * [adaptRg12Fixture] derives the 12-operation plan and the per-root initial-state
 * baselines from the frozen contract `golden/rules/rg-12.json` and the runtime anchors
 * of `tests/fixtures/rg12-runtime-input.json`; the store seeds the opening baseline from
 * the fixture snapshot and commits the fixture operations.
 *
 * The changed-matched-asset accepted path (RG12-QA-002, `rejectChangedMatchedAsset =
 * false`) is exercised end to end through the store with the symmetric double-invalidation
 * lineage: the predecessor match appends its invalidation entry at `corrected_at`, a fresh
 * inherited match is created at the predecessor's last matched time and immediately
 * invalidated at `corrected_at` (the `new_match_invalidation_entry_ids` anchor), both real
 * legs carry `[MATCHED, INVALIDATED]` histories and the summary moves `matched` -> `pending`
 * (mirrors `changed_asset_case` of tests/python/test_rg12_golden_v2.py).
 */
class SqlDelightRg12StoreTest {
    @Test
    fun commitCorrectPersistsVersionTwoLineageAndRoundTripsAcrossReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg12-store-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expected: Rg12Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                val accepted = assertIs<Rg12ExecutionResult.Accepted>(store.commit(correct.operation))
                assertEquals(
                    listOf<Rg12ReturnedId>(Rg12ReturnedId.Version(TransactionVersionId("root-correction-transaction-v2"))),
                    accepted.returnedIds,
                )
                // Operation boundary: one ACCEPTED operation with the returned version id.
                assertEquals(1L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
                val operationRow = database.ledgerQueries
                    .selectRg12Operation(fixture.ledgerId.value, "root-correction-request")
                    .executeAsOne()
                assertEquals("ACCEPTED", operationRow.outcome)
                assertEquals(
                    listOf("VERSION" to "root-correction-transaction-v2"),
                    database.ledgerQueries
                        .selectRg12ReturnedIds(fixture.ledgerId.value, "root-correction-request")
                        .executeAsList()
                        .map { it.id_kind to it.id_value },
                )
                // The v2 version row exists on the shared transaction_version table with its
                // write-once confirmation id and the fresh posting set.
                val versions = database.ledgerQueries
                    .selectRg12FormalVersions(fixture.ledgerId.value, "root-correction-transaction")
                    .executeAsList()
                assertEquals(2L, versions.size.toLong())
                val v2 = versions.single { it.version_number == 2L }
                assertEquals("root-correction-transaction-v2", v2.version_id)
                assertEquals("root-correction-set-v2", v2.posting_set_id)
                assertEquals("root-correction-confirmation", v2.confirmation_id)
                // The v2 posting set and its postings are inserted into the shared tables.
                val v2Postings = database.ledgerQueries
                    .selectRg12FormalPostings(fixture.ledgerId.value, "root-correction-set-v2")
                    .executeAsList()
                assertEquals(
                    listOf("root-correction-expense-v2", "root-correction-asset-v2", "root-correction-liability-v2"),
                    v2Postings.map { it.posting_id },
                )
                // Current version advanced; per-version metadata carries the corrected_at
                // creation text; the record statistics time text is preserved.
                val txRow = database.ledgerQueries
                    .selectRg12FormalTransactions(fixture.ledgerId.value)
                    .executeAsList()
                    .single { it.transaction_id == "root-correction-transaction" }
                assertEquals("root-correction-transaction-v2", txRow.current_version_id)
                val versionMetadata = database.ledgerQueries
                    .selectRg12TransactionVersionMetadata(fixture.ledgerId.value)
                    .executeAsList()
                    .single { it.version_id == "root-correction-transaction-v2" }
                assertEquals("2026-04-20T10:00:00+08:00", versionMetadata.created_at)
                val recordMetadata = database.ledgerQueries
                    .selectRg12FormalTransactionMetadata(fixture.ledgerId.value)
                    .executeAsList()
                    .single { it.transaction_id == "root-correction-transaction" }
                assertEquals("2026-04-10T09:30:00+08:00", recordMetadata.statistics_at_text)
                // RG-12 exclusive owners: semantics for all six postings, three matches with
                // the appended liability history, three facts, three replacement links, one
                // confirmation, one consumption record and the two seeded report periods.
                assertEquals(6L, database.ledgerQueries.selectRg12AllPostingSemantics(fixture.ledgerId.value).executeAsList().size.toLong())
                val matches = database.ledgerQueries.selectRg12AllMatches(fixture.ledgerId.value).executeAsList()
                assertEquals(3L, matches.size.toLong())
                assertEquals(
                    4L,
                    matches.sumOf { match ->
                        database.ledgerQueries
                            .selectRg12MatchHistory(fixture.ledgerId.value, match.match_id)
                            .executeAsList().size
                    }.toLong(),
                )
                val liabilityHistory = database.ledgerQueries
                    .selectRg12MatchHistory(fixture.ledgerId.value, "root-correction-match-liability-v1")
                    .executeAsList()
                assertEquals(listOf("MATCHED", "INVALIDATED"), liabilityHistory.map { it.status })
                assertEquals("2026-04-20T02:00:00Z", liabilityHistory.last().entry_at)
                assertEquals(4L, database.ledgerQueries.selectRg12AllPostingReconciliations(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(3L, database.ledgerQueries.selectRg12AllPostingReplacements(fixture.ledgerId.value).executeAsList().size.toLong())
                val confirmation = database.ledgerQueries
                    .selectRg12AllConfirmations(fixture.ledgerId.value)
                    .executeAsList().single()
                assertEquals("root-correction-correct", confirmation.operation_id)
                assertEquals("operation", confirmation.subject_kind)
                assertEquals("{}", confirmation.payload)
                val consumption = database.ledgerQueries
                    .selectRg12AllConsumptionRecords(fixture.ledgerId.value)
                    .executeAsList()
                    .single { it.record_id == "root-correction-consumption-v2" }
                assertEquals("110.00", consumption.amount_text)
                assertEquals(
                    setOf("2026-04-10", "2026-04-20"),
                    database.ledgerQueries.selectRg12AllReportPeriods(fixture.ledgerId.value).executeAsList().map { it.period }.toSet(),
                )
                // Snapshot-level lineage: the three-value replacement chain, the invalidated
                // predecessor match, the inherited asset match, facts and summary.
                val snapshot = store.snapshot(fixture.ledgerId)
                val effects = snapshot.postingReplacements.associate { it.fromPostingId.value to it.reconciliationEffect }
                assertEquals(ReconciliationEffect.NOT_APPLICABLE, effects["root-correction-expense-v1"])
                assertEquals(ReconciliationEffect.PRESERVED, effects["root-correction-asset-v1"])
                assertEquals(ReconciliationEffect.INVALIDATED, effects["root-correction-liability-v1"])
                val assetV2Match = snapshot.reconciliationMatches.single { it.id == "root-correction-match-asset-v2" }
                assertEquals("root-correction-asset-v2", assetV2Match.postingId.value)
                assertEquals("2026-04-11T09:00:00+08:00", instantText(assetV2Match.statusHistory.single().at))
                val facts = snapshot.postingReconciliations.associate { it.postingId.value to it.status }
                assertEquals(PostingReconciliationStatus.MATCHED, facts["root-correction-asset-v2"])
                assertEquals(PostingReconciliationStatus.PENDING, facts["root-correction-liability-v2"])
                assertEquals(ReconciliationSummary.PARTIAL, snapshot.reconciliationSummary.getValue(TransactionId("root-correction-transaction")))
                assertEquals(11_000L, snapshot.balances.getValue(AccountId("root-correction-expense")).minorUnits)
                assertEquals(-7_000L, snapshot.balances.getValue(AccountId("root-correction-asset")).minorUnits)
                assertEquals(-4_000L, snapshot.balances.getValue(AccountId("root-correction-liability")).minorUnits)
                assertEquals(1, snapshot.confirmations.size)
                assertEquals("2026-04-20T10:00:00+08:00", instantText(snapshot.confirmations.single().createdAt))
                assertEquals(2, snapshot.consumptionRecords.size)
                assertEquals(
                    "110.00",
                    snapshot.consumptionRecords.single { it.id == "root-correction-consumption-v2" }.amountText,
                )
                assertEquals(setOf("2026-04-10", "2026-04-20"), snapshot.reportPeriods.toSet())
                expected = snapshot
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                assertPersistedSnapshotEquals(expected, store.snapshot(fixture.ledgerId))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun idempotentReplayAndRetryByIdentityReturnFirstTimeIdsAfterReopen() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg12-replay-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                assertIs<Rg12ExecutionResult.Accepted>(store.commit(correct.operation))
                // A same-fingerprint full-body replay returns NoChange with the first-time ids.
                val replay = assertIs<Rg12ExecutionResult.NoChange>(store.commit(correct.operation))
                assertEquals(
                    listOf<Rg12ReturnedId>(Rg12ReturnedId.Version(TransactionVersionId("root-correction-transaction-v2"))),
                    replay.returnedIds,
                )
                // A retry by identity replays the finalized receipt without an operation body.
                val retry = assertIs<Rg12ExecutionResult.NoChange>(
                    store.commit(
                        Rg12Operation.RetryIdempotentInput(
                            fixture.ledgerId,
                            Rg12RetryInput(
                                inputId = "root-correction-request",
                                replayedAction = Rg12Action.CORRECT_TRANSACTION_VERSION,
                            ),
                        ),
                    ),
                )
                assertEquals(replay.returnedIds, retry.returnedIds)
                assertEquals(1L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                // After reopen both the full-body replay and the identity retry stay stable.
                val replayed = assertIs<Rg12ExecutionResult.NoChange>(store.commit(correct.operation))
                assertEquals(
                    listOf<Rg12ReturnedId>(Rg12ReturnedId.Version(TransactionVersionId("root-correction-transaction-v2"))),
                    replayed.returnedIds,
                )
                val retried = assertIs<Rg12ExecutionResult.NoChange>(
                    store.commit(
                        Rg12Operation.RetryIdempotentInput(
                            fixture.ledgerId,
                            Rg12RetryInput(
                                inputId = "root-correction-request",
                                replayedAction = Rg12Action.CORRECT_TRANSACTION_VERSION,
                            ),
                        ),
                    ),
                )
                assertEquals(replayed.returnedIds, retried.returnedIds)
                // A changed fingerprint on the same identity is a conflict.
                val changed = (correct.operation as Rg12Operation.CorrectTransactionVersion).let { op ->
                    op.copy(
                        input = op.input.copy(
                            replacementPostings = op.input.replacementPostings.mapIndexed { index, item ->
                                if (index == 1) item.copy(facts = item.facts.copy(amountText = "-69.00")) else item
                            },
                        ),
                    )
                }
                assertEquals(Rg12ExecutionResult.RequestIdentityConflict, store.commit(changed))
                assertEquals(1L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectedOperationPersistsReceiptWithZeroDependentRows() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg12-rejected-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-rejections")
                val rejection = fixture.operations.single { it.id == "root-rejections-reject-8" }
                val rejected = assertIs<Rg12ExecutionResult.Rejected>(store.commit(rejection.operation))
                assertEquals(Rg12RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, rejected.reason)
                assertEquals("$.attempted_input.explicit_confirmation", rejected.fieldPath.value)
                val saved = database.ledgerQueries
                    .selectRg12Operation(fixture.ledgerId.value, "root-rejections-reject-8-request")
                    .executeAsOne()
                assertEquals("REJECTED", saved.outcome)
                assertEquals("explicit_confirmation_required", saved.reason_code)
                assertEquals("$.attempted_input.explicit_confirmation", saved.field_path)
                // The rejection persists a receipt with zero dependent rows beyond the
                // opening baseline: the formal chain stays at the baseline transaction and
                // every RG-12 exclusive owner keeps its seed counts.
                assertEquals(0L, database.ledgerQueries.selectRg12ReturnedIds(fixture.ledgerId.value, "root-rejections-reject-8-request").executeAsList().size.toLong())
                assertEquals(1L, database.ledgerQueries.countRg12FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(3L, database.ledgerQueries.selectRg12AllPostingSemantics(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg12AllMatches(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg12AllPostingReconciliations(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllPostingReplacements(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllConfirmations(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(1L, database.ledgerQueries.selectRg12AllConsumptionRecords(fixture.ledgerId.value).executeAsList().size.toLong())
                // Replay of the same rejected input is stable; retry by identity is stable too.
                assertEquals(rejected, store.commit(rejection.operation))
                val retried = assertIs<Rg12ExecutionResult.Rejected>(
                    store.commit(
                        Rg12Operation.RetryIdempotentInput(
                            fixture.ledgerId,
                            Rg12RetryInput(
                                inputId = "root-rejections-reject-8-request",
                                replayedAction = Rg12Action.CORRECT_TRANSACTION_VERSION,
                            ),
                        ),
                    ),
                )
                assertEquals(rejected.reason, retried.reason)
                assertEquals(rejected.fieldPath, retried.fieldPath)
                assertEquals(1L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun failureInjectionRollsBackClaimAndDeltaAtomically() {
        val fixture = loadFixture()
        val claimPath = Files.createTempFile("ledger-data-rg12-claim-failure-", ".db")
        val deltaPath = Files.createTempFile("ledger-data-rg12-delta-failure-", ".db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${claimPath.absolutePathString()}", sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val injector = object : Rg12FailureInjector {
                    override fun failAt(point: Rg12FailurePoint) {
                        if (point == Rg12FailurePoint.AFTER_CLAIM) error("injected RG-12 claim failure")
                    }
                }
                val failing = store(database, driver, fixture, "root-correction", injector)
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                assertFailsWith<IllegalStateException> { failing.commit(correct.operation) }
                assertEquals(0L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
                // The opening baseline survives; the v2 version was never written.
                assertEquals(1L, database.ledgerQueries.countRg12FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.selectRg12FormalVersions(fixture.ledgerId.value, "root-correction-transaction").executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg12AllMatches(fixture.ledgerId.value).executeAsList().size.toLong())
                // A clean store on the same database commits the same operation.
                val clean = store(database, driver, fixture, "root-correction")
                assertIs<Rg12ExecutionResult.Accepted>(clean.commit(correct.operation))
            }
            JdbcSqliteDriver("jdbc:sqlite:${deltaPath.absolutePathString()}", sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val injector = object : Rg12FailureInjector {
                    override fun failAt(point: Rg12FailurePoint) {
                        if (point == Rg12FailurePoint.AFTER_DELTA) error("injected RG-12 delta failure")
                    }
                }
                val failing = store(database, driver, fixture, "root-correction", injector)
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                assertFailsWith<IllegalStateException> { failing.commit(correct.operation) }
                assertEquals(0L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg12FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.selectRg12FormalVersions(fixture.ledgerId.value, "root-correction-transaction").executeAsList().size.toLong())
                assertEquals(3L, database.ledgerQueries.selectRg12AllPostingSemantics(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg12AllMatches(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg12AllPostingReconciliations(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllPostingReplacements(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllConfirmations(fixture.ledgerId.value).executeAsList().size.toLong())
            }
        } finally {
            Files.deleteIfExists(claimPath)
            Files.deleteIfExists(deltaPath)
        }
    }

    @Test
    fun triggerViolationRollsBackClaimAndEveryDependentWrite() {
        // RG12-QA-001: a guard-trigger abort inside the commit transaction (here: an
        // immutable-row UPDATE on rg12_posting_reconciliation) must roll back the claim and
        // every dependent write; the operation is never left in a half-finalized state.
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg12-trigger-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val injector = object : Rg12FailureInjector {
                    override fun failAt(point: Rg12FailurePoint) {
                        if (point == Rg12FailurePoint.AFTER_DELTA) {
                            // The delta is already persisted inside the commit transaction;
                            // this UPDATE violates rg12_reconciliation_guard_update (rows are
                            // immutable), aborting the whole transaction. The statement
                            // executes eagerly, so the trigger abort propagates from here.
                            driver.execute(
                                null,
                                """
                                    UPDATE rg12_posting_reconciliation SET status = 'MATCHED'
                                    WHERE ledger_id = '${fixture.ledgerId.value}'
                                      AND reconciliation_id = 'root-correction-reconciliation-asset-v2'
                                """.trimIndent(),
                                0,
                            )
                        }
                    }
                }
                val failing = store(database, driver, fixture, "root-correction", injector)
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                assertFailsWith<SQLException> { failing.commit(correct.operation) }
                assertEquals(0L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg12FormalTransactions(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.selectRg12FormalVersions(fixture.ledgerId.value, "root-correction-transaction").executeAsList().size.toLong())
                assertEquals(3L, database.ledgerQueries.selectRg12AllPostingSemantics(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg12AllMatches(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg12AllPostingReconciliations(fixture.ledgerId.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg12AllPostingReplacements(fixture.ledgerId.value).executeAsList().size.toLong())
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                assertIs<Rg12ExecutionResult.Accepted>(store.commit(correct.operation))
                assertEquals(
                    2L,
                    database.ledgerQueries
                        .selectRg12FormalVersions(fixture.ledgerId.value, "root-correction-transaction")
                        .executeAsList().size.toLong(),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun pendingClaimIsNeverSilentlyFinalized() {
        // A claim persisted by an interrupted attempt stays PENDING; the store refuses to
        // finalize it (Known Records: `still pending` hard error) instead of guessing an
        // outcome, and the PENDING row is left untouched.
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg12-pending-claim-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                val operation = correct.operation as Rg12Operation.CorrectTransactionVersion
                val fingerprint = Rg12Runtime(
                    fixture.catalogs.getValue("root-correction"),
                    emptyList(),
                ).operationFingerprint(operation)
                database.ledgerQueries.insertRg12Operation(
                    fixture.ledgerId.value,
                    operation.identity.value,
                    operation.action.code,
                    Rg12Action.CORRECT_TRANSACTION_VERSION.code,
                    fingerprint,
                    "PENDING",
                    null,
                    null,
                )
                val error = assertFailsWith<IllegalStateException> { store.commit(operation) }
                assertTrue(error.message!!.contains("still pending"), "error names the pending state")
                val saved = database.ledgerQueries
                    .selectRg12Operation(fixture.ledgerId.value, operation.identity.value)
                    .executeAsOne()
                assertEquals("PENDING", saved.outcome)
                assertEquals(0, database.ledgerQueries
                    .selectRg12ReturnedIds(fixture.ledgerId.value, operation.identity.value)
                    .executeAsList().size)
                // A retry by identity refuses the pending row the same way.
                val retryError = assertFailsWith<IllegalStateException> {
                    store.commit(
                        Rg12Operation.RetryIdempotentInput(
                            fixture.ledgerId,
                            Rg12RetryInput(
                                inputId = operation.identity.value,
                                replayedAction = Rg12Action.CORRECT_TRANSACTION_VERSION,
                            ),
                        ),
                    )
                }
                assertTrue(retryError.message!!.contains("still pending"), "retry error names the pending state")
                assertEquals(1L, database.ledgerQueries.countRg12Operations(fixture.ledgerId.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg12FormalTransactions(fixture.ledgerId.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun guardsRejectBypassMutationsAndForeignOwnership() {
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg12-guards-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val ledger = fixture.ledgerId.value
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                val correct = fixture.operations.single { it.id == "root-correction-correct" }
                assertIs<Rg12ExecutionResult.Accepted>(store.commit(correct.operation))
                // Exclusive rows are immutable even when callers bypass the store.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "UPDATE rg12_reconciliation_match SET evidence_id = 'evidence-bypass' WHERE match_id = 'root-correction-match-asset-v1'",
                        0,
                    )
                }
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "DELETE FROM rg12_confirmation WHERE confirmation_id = 'root-correction-confirmation'",
                        0,
                    )
                }
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "UPDATE rg12_operation SET outcome = 'PENDING' WHERE identity_value = 'root-correction-request'",
                        0,
                    )
                }
                // A semantic must own a posting of the current version of an EXPENSE
                // transaction.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible, category_id) VALUES ('$ledger', 'posting-ghost', 'expense', 0, 'category-ghost')",
                        0,
                    )
                }
                // A match must own an eligible real posting (the expense leg is not eligible).
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_reconciliation_match(ledger_id, match_id, posting_id, evidence_id) VALUES ('$ledger', 'match-bypass', 'root-correction-expense-v2', 'evidence-bypass')",
                        0,
                    )
                }
                // Match history is append-only with consecutive sequences and the frozen
                // transition (a later entry must be invalidated/posting_replaced directly
                // after a matched entry).
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_reconciliation_match_history(ledger_id, match_id, entry_sequence, entry_id, status, entry_at, reason) VALUES ('$ledger', 'root-correction-match-asset-v2', 5, 'entry-bypass', 'INVALIDATED', '2026-04-20T10:00:00+08:00', 'POSTING_REPLACED')",
                        0,
                    )
                }
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_reconciliation_match_history(ledger_id, match_id, entry_sequence, entry_id, status, entry_at, reason) VALUES ('$ledger', 'root-correction-match-asset-v2', 2, 'entry-bypass-2', 'MATCHED', '2026-04-20T10:00:00+08:00', 'EXACT_EVIDENCE')",
                        0,
                    )
                }
                // Reconciliation facts must agree with the match state: a MATCHED fact
                // requires an active match and a PENDING fact forbids one.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_posting_reconciliation(ledger_id, reconciliation_id, posting_id, status) VALUES ('$ledger', 'fact-bypass-1', 'root-correction-liability-v2', 'MATCHED')",
                        0,
                    )
                }
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_posting_reconciliation(ledger_id, reconciliation_id, posting_id, status) VALUES ('$ledger', 'fact-bypass-2', 'root-correction-asset-v2', 'PENDING')",
                        0,
                    )
                }
                // The shared write-once confirmation column stays protected.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "UPDATE transaction_version SET confirmation_id = 'confirmation-bypass' WHERE version_id = 'root-correction-transaction-v2'",
                        0,
                    )
                }
                // RG12-001 closure: the rebuilt guards reject every bypass order that
                // would end in fact='PENDING' with latest_history='MATCHED'. A fresh
                // eligible real posting (owned by the current version of an EXPENSE
                // transaction) provides the counterexample fixture.
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('transaction-guard-extra', '$ledger', 'EXPENSE')",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('posting-set-guard-extra', '$ledger')",
                    0,
                )
                driver.execute(
                    null,
                    """
                        INSERT INTO transaction_version(
                          version_id, transaction_id, ledger_id, version_number, posting_set_id,
                          occurred_at, statistics_at, effective_at, note
                        ) VALUES ('version-guard-extra', 'transaction-guard-extra', '$ledger', 1,
                          'posting-set-guard-extra', '2026-04-20T10:00:00+08:00',
                          '2026-04-20T10:00:00+08:00', '2026-04-20T10:00:00+08:00', NULL)
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction_current_version VALUES ('transaction-guard-extra', '$ledger', 'version-guard-extra')",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('posting-guard-expense', 'posting-set-guard-extra', '$ledger', 0, 'expense-guard', 1000, 'CNY', 2)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('posting-guard-asset', 'posting-set-guard-extra', '$ledger', 1, 'asset-guard', -1000, 'CNY', 2)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('posting-guard-asset-2', 'posting-set-guard-extra', '$ledger', 2, 'asset-guard-2', -1000, 'CNY', 2)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO rg12_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible, category_id) VALUES ('$ledger', 'posting-guard-asset', 'mixed_expense_asset_funding', 1, NULL)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO rg12_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible, category_id) VALUES ('$ledger', 'posting-guard-asset-2', 'mixed_expense_asset_funding', 1, NULL)",
                    0,
                )
                // 1. A posting that already owns a reconciliation fact cannot acquire a
                // match (the fact-preclusion branch of the rebuilt match guard).
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_reconciliation_match(ledger_id, match_id, posting_id, evidence_id) VALUES ('$ledger', 'match-against-fact', 'root-correction-liability-v2', 'evidence-against-fact')",
                        0,
                    )
                }
                // 2. An orphan match (no history) cannot own a fact: the match insert is
                // allowed as the documented residual, the PENDING fact insert is rejected
                // by the orphan branch of the rebuilt fact guard.
                driver.execute(
                    null,
                    "INSERT INTO rg12_reconciliation_match(ledger_id, match_id, posting_id, evidence_id) VALUES ('$ledger', 'match-orphan', 'posting-guard-asset', 'evidence-orphan')",
                    0,
                )
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_posting_reconciliation(ledger_id, reconciliation_id, posting_id, status) VALUES ('$ledger', 'fact-orphan', 'posting-guard-asset', 'PENDING')",
                        0,
                    )
                }
                // 3. PENDING fact first, then a match, then a MATCHED history entry: the
                // match insert is rejected first by the rebuilt match guard's
                // fact-preclusion branch. The rg12_match_history_fact_consistency
                // backstop (asserted structurally in LedgerDatabaseMigrationTest) would
                // catch the history insert if a future migration ever relaxed that branch.
                driver.execute(
                    null,
                    "INSERT INTO rg12_posting_reconciliation(ledger_id, reconciliation_id, posting_id, status) VALUES ('$ledger', 'fact-pending-first', 'posting-guard-asset-2', 'PENDING')",
                    0,
                )
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg12_reconciliation_match(ledger_id, match_id, posting_id, evidence_id) VALUES ('$ledger', 'match-after-pending-fact', 'posting-guard-asset-2', 'evidence-after-pending-fact')",
                        0,
                    )
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun changedMatchedAssetAppendsDoubleInvalidationLineageAndMovesSummaryToPending() {
        // RG12-QA-002: `rejectChangedMatchedAsset = false` expresses a changed matched asset
        // leg as symmetric lineage instead of a rejection: the predecessor match appends its
        // invalidation entry at corrected_at and a fresh inherited match is created at the
        // predecessor's last matched time and immediately invalidated at corrected_at
        // (`new_match_invalidation_entry_ids`); both real legs carry [MATCHED, INVALIDATED]
        // histories and the summary moves matched -> pending (mirrors `changed_asset_case`).
        val fixture = loadFixture()
        val path = Files.createTempFile("ledger-data-rg12-changed-asset-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = store(database, driver, fixture, "root-correction")
                val original = fixture.operations.single { it.id == "root-correction-correct" }
                    .operation as Rg12Operation.CorrectTransactionVersion
                val changedInput = original.input.copy(
                    replacementPostings = original.input.replacementPostings.map { item ->
                        when (item.sourcePostingId.value) {
                            "root-correction-expense-v1" -> item.copy(facts = item.facts.copy(amountText = "100.00"))
                            "root-correction-asset-v1" -> item.copy(facts = item.facts.copy(amountText = "-60.00"))
                            else -> item
                        }
                    },
                )
                val changedIds = original.ids.copy(
                    invalidationEntryIds = listOf("", "root-correction-match-asset-v1-history-2", "root-correction-match-liability-v1-history-2"),
                    newMatchInvalidationEntryIds = listOf(null, "root-correction-match-asset-v2-history-2", null),
                )
                val changed = original.copy(input = changedInput, ids = changedIds)
                assertIs<Rg12ExecutionResult.Accepted>(store.commit(changed))
                val snapshot = store.snapshot(fixture.ledgerId)
                // Both real legs changed: asset and liability links are invalidated.
                val effects = snapshot.postingReplacements.associate { it.fromPostingId.value to it.reconciliationEffect }
                assertEquals(ReconciliationEffect.NOT_APPLICABLE, effects["root-correction-expense-v1"])
                assertEquals(ReconciliationEffect.INVALIDATED, effects["root-correction-asset-v1"])
                assertEquals(ReconciliationEffect.INVALIDATED, effects["root-correction-liability-v1"])
                // The predecessor asset match appends exactly one invalidation entry at
                // corrected_at.
                val assetV1Match = snapshot.reconciliationMatches.single { it.id == "root-correction-match-asset-v1" }
                assertEquals(
                    listOf(ReconciliationMatchStatus.MATCHED, ReconciliationMatchStatus.INVALIDATED),
                    assetV1Match.statusHistory.map { it.status },
                )
                assertEquals("2026-04-20T10:00:00+08:00", instantText(assetV1Match.statusHistory.last().at))
                // The fresh inherited match is created at the predecessor's last matched
                // time and immediately invalidated at corrected_at (double invalidation).
                val assetV2Match = snapshot.reconciliationMatches.single { it.id == "root-correction-match-asset-v2" }
                assertEquals("root-correction-asset-v2", assetV2Match.postingId.value)
                assertEquals(
                    listOf("root-correction-match-asset-v2-history-1", "root-correction-match-asset-v2-history-2"),
                    assetV2Match.statusHistory.map { it.id },
                )
                assertEquals(
                    listOf(ReconciliationMatchStatus.MATCHED, ReconciliationMatchStatus.INVALIDATED),
                    assetV2Match.statusHistory.map { it.status },
                )
                assertEquals("2026-04-11T09:00:00+08:00", instantText(assetV2Match.statusHistory.first().at))
                assertEquals("2026-04-20T10:00:00+08:00", instantText(assetV2Match.statusHistory.last().at))
                // Both replacement facts are pending and the summary moves matched -> pending.
                val facts = snapshot.postingReconciliations.associate { it.postingId.value to it.status }
                assertEquals(PostingReconciliationStatus.PENDING, facts["root-correction-asset-v2"])
                assertEquals(PostingReconciliationStatus.PENDING, facts["root-correction-liability-v2"])
                assertEquals(
                    ReconciliationSummary.PENDING,
                    snapshot.reconciliationSummary.getValue(TransactionId("root-correction-transaction")),
                )
                // Balances and reports recompute from the replacement postings.
                assertEquals(10_000L, snapshot.balances.getValue(AccountId("root-correction-expense")).minorUnits)
                assertEquals(-6_000L, snapshot.balances.getValue(AccountId("root-correction-asset")).minorUnits)
                assertEquals(-4_000L, snapshot.balances.getValue(AccountId("root-correction-liability")).minorUnits)
                val day = snapshot.reports.getValue("2026-04-10")
                assertEquals(6_000L, day.cashOutflowMinor)
                assertEquals(10_000L, day.consumptionMinor)
                assertEquals(-10_000L, day.netWorthChangeMinor)
                // The double-invalidation lineage round trips across reopen.
                JdbcSqliteDriver(url, sqliteProperties()).use { reopenedDriver ->
                    val reopenedDatabase = LedgerDatabase(reopenedDriver)
                    val reopened = store(reopenedDatabase, reopenedDriver, fixture, "root-correction")
                    assertPersistedSnapshotEquals(snapshot, reopened.snapshot(fixture.ledgerId))
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Snapshot equality across reopen. [FormalTransaction] and [PostingSet] are regular
     * classes (identity equality), so the formal records are compared through a projection
     * of their comparable fields; every other snapshot collection is a data class and is
     * compared directly (RG-11 store-test pattern).
     */
    private fun assertPersistedSnapshotEquals(expected: Rg12Snapshot, actual: Rg12Snapshot) {
        assertEquals(
            expected.formalTransactions.map(::formalProjection),
            actual.formalTransactions.map(::formalProjection),
        )
        assertEquals(expected.postingSemantics, actual.postingSemantics)
        assertEquals(expected.reconciliationMatches, actual.reconciliationMatches)
        assertEquals(expected.postingReconciliations, actual.postingReconciliations)
        assertEquals(expected.postingReplacements, actual.postingReplacements)
        assertEquals(expected.confirmations, actual.confirmations)
        assertEquals(expected.consumptionRecords, actual.consumptionRecords)
        assertEquals(expected.domainEntities, actual.domainEntities)
        assertEquals(expected.reconciliationSummary, actual.reconciliationSummary)
        assertEquals(expected.balances, actual.balances)
        assertEquals(expected.reports, actual.reports)
        assertEquals(expected.reportPeriods, actual.reportPeriods)
    }

    private fun formalProjection(record: com.unifiedledger.application.Rg12FormalTransactionRecord) = FormalRecordProjection(
        transaction = record.formalTransaction.transaction,
        versions = record.formalTransaction.versions,
        postingSets = record.formalTransaction.postingSets.map { PostingSetProjection(it.id, it.postings) },
        createdAt = record.createdAt,
        createdAtText = record.createdAtText,
        statisticsAtText = record.statisticsAtText,
        versionCreatedAtTexts = record.versionCreatedAtTexts,
        versionConfirmationIds = record.versionConfirmationIds,
    )

    private data class FormalRecordProjection(
        val transaction: Transaction,
        val versions: List<TransactionVersion>,
        val postingSets: List<PostingSetProjection>,
        val createdAt: Instant,
        val createdAtText: String?,
        val statisticsAtText: String?,
        val versionCreatedAtTexts: Map<TransactionVersionId, String>,
        val versionConfirmationIds: Map<TransactionVersionId, String>,
    )

    private data class PostingSetProjection(
        val id: PostingSetId,
        val postings: List<Posting>,
    )

    private fun store(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg12FixtureCase,
        rootId: String,
        injector: Rg12FailureInjector = NO_RG12_TEST_FAILURE,
    ): SqlDelightRg12Store = SqlDelightRg12Store(
        database,
        driver,
        fixture.catalogs.getValue(rootId),
        fixture.baselines.getValue(rootId),
        injector,
    )

    private fun loadFixture(): Rg12FixtureCase {
        val raw = Files.readString(repositoryFile("golden/rules/rg-12.json"))
        val inputs = parseRg12FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg12-runtime-input.json")))
        return adaptRg12Fixture(raw, inputs)
    }

    private fun sqliteProperties(): Properties = Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private val SHANGHAI_INSTANT_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    private fun instantText(instant: Instant): String =
        SHANGHAI_INSTANT_FORMAT.format(
            OffsetDateTime.ofInstant(java.time.Instant.parse(instant.toString()), ZoneOffset.ofHours(8)),
        )

    private companion object {
        private val NO_RG12_TEST_FAILURE = Rg12FailureInjector { }
    }
}
