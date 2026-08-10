package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.Rg11CreateIds
import com.unifiedledger.application.Rg11CreateInput
import com.unifiedledger.application.Rg11CorrectIds
import com.unifiedledger.application.Rg11CorrectInput
import com.unifiedledger.application.Rg11ExecutionResult
import com.unifiedledger.application.Rg11Operation
import com.unifiedledger.application.Rg11RecognizeIds
import com.unifiedledger.application.Rg11RecognizeInput
import com.unifiedledger.application.Rg11RetryInput
import com.unifiedledger.application.Rg11ReturnedId
import com.unifiedledger.application.Rg11ReviseIds
import com.unifiedledger.application.Rg11ReviseInput
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PeriodicAllocationAnchor
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * D-085 RG-11 formal SQLDelight persistence tests (aligned with SqlDelightRg08StoreTest):
 * the store runtime path beyond the migration-level coverage — commit with returned ids,
 * reopen snapshot equality, same-fingerprint replay, rejected replay, identity conflict on a
 * changed fingerprint, retry-by-identity replay, the `correct_transaction_version` round trip
 * (v2 version append, write-once confirmation id, current-version and statistics-time advance
 * surviving reopen) and failure injection at both registered points (AFTER_CLAIM / AFTER_DELTA).
 * Inputs are constructed directly (the frozen fixture replay adapter ships with the oracle shard).
 *
 * The `correct_transaction_version` store path is exercised end to end: after RG11-DEV-01 the
 * runtime appends the corrected v2 version of a `PREPAID_RECOGNITION` transaction (shared
 * posting set, write-once `confirmation_id` on the version row, `statistics_at_text` advance)
 * and `persistAppendedVersions` writes exactly that delta; the round-trip test asserts the v2
 * version row, the write-once confirmation id, the current-version and statistics-time advance
 * and the survival of `versionConfirmationIds` across reopen.
 */
class SqlDelightRg11StoreTest {
    @Test
    fun createRecognizeReviseRoundTripEqualsAcrossReopen() {
        val path = Files.createTempFile("ledger-data-rg11-store-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expected: com.unifiedledger.application.Rg11Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(createOperation()))
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(recognizeOperation(INSTALLMENT_1, 3_333L, TX_2, "audit-link-1")))
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(reviseOperation()))
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(recognizeOperation(INSTALLMENT_4, 3_333L, TX_3, "audit-link-2")))
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(recognizeOperation(INSTALLMENT_5, 3_334L, TX_4, "audit-link-3")))
                expected = store.snapshot(LEDGER)
                assertEquals(5L, database.ledgerQueries.countRg11Operations(LEDGER.value).executeAsOne())
                assertEquals(4L, database.ledgerQueries.countRg11FormalTransactions(LEDGER.value).executeAsOne())
                assertEquals(1L, database.ledgerQueries.selectRg11AllSchedules(LEDGER.value).executeAsList().size.toLong())
                assertEquals(2L, database.ledgerQueries.selectRg11AllRevisions(LEDGER.value).executeAsList().size.toLong())
                assertEquals(5L, database.ledgerQueries.selectRg11AllInstallments(LEDGER.value).executeAsList().size.toLong())
                assertEquals(0L, database.ledgerQueries.selectRg11AllConfirmations(LEDGER.value).executeAsList().size.toLong())
                assertEquals(3L, database.ledgerQueries.selectRg11AllAuditLinks(LEDGER.value).executeAsList().size.toLong())
                assertEquals(8L, database.ledgerQueries.selectRg11AllPostingSemantics(LEDGER.value).executeAsList().size.toLong())
                assertEquals(1L, database.ledgerQueries.selectRg11AllPostingReconciliations(LEDGER.value).executeAsList().size.toLong())
                assertEquals(1, expected.schedules.size)
                assertEquals(2, expected.revisions.size)
                assertEquals(5, expected.installments.size)
                assertEquals(0, expected.confirmations.size)
                assertEquals(3, expected.auditLinks.size)
                assertEquals("pending", expected.reconciliation[PAYMENT_POSTING_1.value])
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                assertPersistedSnapshotEquals(expected, store.snapshot(LEDGER))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun correctTransactionVersionRoundTripPersistsVersionTwoConfirmationAcrossReopen() {
        val path = Files.createTempFile("ledger-data-rg11-correct-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var expected: com.unifiedledger.application.Rg11Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(createOperation()))
                assertIs<Rg11ExecutionResult.Accepted>(
                    store.commit(recognizeOperation(INSTALLMENT_1, 3_333L, TX_2, "audit-link-1")),
                )
                val corrected = assertIs<Rg11ExecutionResult.Accepted>(store.commit(correctOperation()))
                assertEquals(
                    listOf<com.unifiedledger.application.Rg11ReturnedId>(
                        com.unifiedledger.application.Rg11ReturnedId.Version(VERSION_CORRECT),
                    ),
                    corrected.returnedIds,
                )
                // The v2 version row exists with the write-once confirmation id.
                val tx2Versions = database.ledgerQueries
                    .selectRg11FormalVersions(LEDGER.value, TX_2.value)
                    .executeAsList()
                assertEquals(2L, tx2Versions.size.toLong())
                val v2 = tx2Versions.single { it.version_number == 2L }
                assertEquals(VERSION_CORRECT.value, v2.version_id)
                assertEquals(CONFIRMATION_CORRECT, v2.confirmation_id)
                // The current version and the statistics time text advanced.
                val tx2Row = database.ledgerQueries
                    .selectRg11FormalTransactions(LEDGER.value)
                    .executeAsList()
                    .single { it.transaction_id == TX_2.value }
                assertEquals(VERSION_CORRECT.value, tx2Row.current_version_id)
                val metadata = database.ledgerQueries
                    .selectRg11FormalTransactionMetadata(LEDGER.value)
                    .executeAsList()
                    .single { it.transaction_id == TX_2.value }
                assertEquals(CORRECTED_STATISTICS_AT_TEXT, metadata.statistics_at_text)
                assertEquals(1L, database.ledgerQueries.selectRg11AllConfirmations(LEDGER.value).executeAsList().size.toLong())
                // The snapshot carries the appended version, its confirmation id and the
                // operation confirmation.
                val snapshot = store.snapshot(LEDGER)
                val correctedRecord = snapshot.formalTransactions
                    .single { it.formalTransaction.transaction.id == TX_2 }
                assertEquals(2, correctedRecord.formalTransaction.versions.size)
                assertEquals(VERSION_CORRECT, correctedRecord.formalTransaction.transaction.currentVersionId)
                assertEquals(CORRECTED_STATISTICS_AT_TEXT, correctedRecord.statisticsAtText)
                assertEquals(
                    mapOf(VERSION_CORRECT to CONFIRMATION_CORRECT),
                    correctedRecord.versionConfirmationIds,
                )
                assertEquals(1, snapshot.confirmations.size)
                expected = snapshot
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                assertPersistedSnapshotEquals(expected, store.snapshot(LEDGER))
                val reopened = store.snapshot(LEDGER).formalTransactions
                    .single { it.formalTransaction.transaction.id == TX_2 }
                assertEquals(VERSION_CORRECT, reopened.formalTransaction.transaction.currentVersionId)
                assertEquals(CORRECTED_STATISTICS_AT_TEXT, reopened.statisticsAtText)
                assertEquals(
                    mapOf(VERSION_CORRECT to CONFIRMATION_CORRECT),
                    reopened.versionConfirmationIds,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun idempotentReplayReturnsNoChangeWithFirstTimeIdsAfterReopen() {
        val path = Files.createTempFile("ledger-data-rg11-replay-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(createOperation()))
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                val noChange = assertIs<Rg11ExecutionResult.NoChange>(store.commit(createOperation()))
                assertEquals(
                    listOf(
                        Rg11ReturnedId.Transaction(TX_1),
                        Rg11ReturnedId.DomainEntity(SCHEDULE),
                    ),
                    noChange.returnedIds,
                    "no-change ids equal first-time ids",
                )
                // A retry by identity replays the finalized receipt without an operation body.
                val retry = assertIs<Rg11ExecutionResult.NoChange>(
                    store.commit(
                        Rg11Operation.RetryIdempotentInput(
                            LEDGER,
                            Rg11RetryInput(inputId = REQUEST_CREATE.value, replayedAction = com.unifiedledger.application.Rg11Action.CREATE_PERIODIC_ALLOCATION),
                        ),
                    ),
                )
                assertEquals(noChange.returnedIds, retry.returnedIds)
                // A changed fingerprint on the same identity is a conflict.
                val changed = createOperation().let { op ->
                    op.copy(input = op.input.copy(installmentCount = 2))
                }
                assertEquals(Rg11ExecutionResult.RequestIdentityConflict, store.commit(changed))
                assertEquals(1L, database.ledgerQueries.countRg11Operations(LEDGER.value).executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectedOperationPersistsReceiptWithZeroDependentRows() {
        val path = Files.createTempFile("ledger-data-rg11-rejected-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var baseline: com.unifiedledger.application.Rg11Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                // The pre-rejection baseline (empty database) is the reopen oracle.
                baseline = store.snapshot(LEDGER)
                val missingConfirmation = createOperation().let { op ->
                    op.copy(input = op.input.copy(explicitConfirmation = false))
                }
                val rejected = assertIs<Rg11ExecutionResult.Rejected>(store.commit(missingConfirmation))
                assertEquals(com.unifiedledger.application.Rg11RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, rejected.reason)
                assertEquals(com.unifiedledger.application.Rg11FieldPath.INPUT_CONFIRMATION, rejected.fieldPath)
                val saved = database.ledgerQueries
                    .selectRg11Operation(LEDGER.value, missingConfirmation.identity.value)
                    .executeAsOne()
                assertEquals("REJECTED", saved.outcome)
                assertEquals("explicit_confirmation_required", saved.reason_code)
                assertEquals(1L, database.ledgerQueries.countRg11Operations(LEDGER.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg11FormalTransactions(LEDGER.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg11AllSchedules(LEDGER.value).executeAsList().size.toLong())
                // Replay of the same rejected input is stable; retry by identity is stable too.
                assertEquals(rejected, store.commit(missingConfirmation))
                val retried = assertIs<Rg11ExecutionResult.Rejected>(
                    store.commit(
                        Rg11Operation.RetryIdempotentInput(
                            LEDGER,
                            Rg11RetryInput(inputId = REQUEST_CREATE.value, replayedAction = com.unifiedledger.application.Rg11Action.CREATE_PERIODIC_ALLOCATION),
                        ),
                    ),
                )
                assertEquals(rejected.reason, retried.reason)
                assertEquals(rejected.fieldPath, retried.fieldPath)
            }

            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                // After close + reopen the rejection receipt persists and every
                // dependent row is still exactly the pre-rejection baseline.
                assertEquals(1L, database.ledgerQueries.countRg11Operations(LEDGER.value).executeAsOne())
                assertPersistedSnapshotEquals(baseline, store.snapshot(LEDGER))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun failureInjectionRollsBackClaimAndDeltaAtomically() {
        val claimPath = Files.createTempFile("ledger-data-rg11-claim-failure-", ".db")
        val deltaPath = Files.createTempFile("ledger-data-rg11-delta-failure-", ".db")
        val claimUrl = "jdbc:sqlite:${claimPath.absolutePathString()}"
        val deltaUrl = "jdbc:sqlite:${deltaPath.absolutePathString()}"
        lateinit var claimBaseline: com.unifiedledger.application.Rg11Snapshot
        lateinit var deltaBaseline: com.unifiedledger.application.Rg11Snapshot
        try {
            JdbcSqliteDriver(claimUrl, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val injector = object : Rg11FailureInjector {
                    override fun failAt(point: Rg11FailurePoint) {
                        if (point == Rg11FailurePoint.AFTER_CLAIM) error("injected RG-11 claim failure")
                    }
                }
                val store = SqlDelightRg11Store(database, driver, catalog(), emptyList(), injector)
                // The pre-failure baseline (empty database) is the reopen oracle.
                claimBaseline = store.snapshot(LEDGER)
                assertFailsWith<IllegalStateException> { store.commit(createOperation()) }
            }
            JdbcSqliteDriver(claimUrl, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                // After close + reopen the failed commit left no trace and the full
                // snapshot is still exactly the pre-failure baseline.
                assertEquals(0L, database.ledgerQueries.countRg11Operations(LEDGER.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg11FormalTransactions(LEDGER.value).executeAsOne())
                assertPersistedSnapshotEquals(claimBaseline, store.snapshot(LEDGER))
                // A clean store on the reopened database commits the same operation.
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(createOperation()))
                assertEquals(1L, database.ledgerQueries.countRg11Operations(LEDGER.value).executeAsOne())
            }
            JdbcSqliteDriver(deltaUrl, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val injector = object : Rg11FailureInjector {
                    override fun failAt(point: Rg11FailurePoint) {
                        if (point == Rg11FailurePoint.AFTER_DELTA) error("injected RG-11 delta failure")
                    }
                }
                val store = SqlDelightRg11Store(database, driver, catalog(), emptyList(), injector)
                // The pre-failure baseline (empty database) is the reopen oracle.
                deltaBaseline = store.snapshot(LEDGER)
                assertFailsWith<IllegalStateException> { store.commit(createOperation()) }
            }
            JdbcSqliteDriver(deltaUrl, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                // After close + reopen the failed commit left no trace and the full
                // snapshot is still exactly the pre-failure baseline.
                assertEquals(0L, database.ledgerQueries.countRg11Operations(LEDGER.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg11FormalTransactions(LEDGER.value).executeAsOne())
                assertEquals(0L, database.ledgerQueries.selectRg11AllSchedules(LEDGER.value).executeAsList().size.toLong())
                assertPersistedSnapshotEquals(deltaBaseline, store.snapshot(LEDGER))
            }
        } finally {
            Files.deleteIfExists(claimPath)
            Files.deleteIfExists(deltaPath)
        }
    }

    @Test
    fun guardsRejectBypassMutationsAndForeignOwnership() {
        val path = Files.createTempFile("ledger-data-rg11-guards-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg11Store(database, driver, catalog())
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(createOperation()))
                assertIs<Rg11ExecutionResult.Accepted>(store.commit(recognizeOperation(INSTALLMENT_1, 3_333L, TX_2, "audit-link-1")))
                // Immutable schedule and audit link rows reject bypass updates and deletes.
                assertFailsWith<java.sql.SQLException> {
                    driver.execute(null, "UPDATE rg11_schedule SET total_amount_minor = 1 WHERE schedule_id = '$SCHEDULE'", 0)
                }
                assertFailsWith<java.sql.SQLException> {
                    driver.execute(null, "DELETE FROM rg11_audit_link WHERE audit_link_id = 'audit-link-1'", 0)
                }
                // An audit link cannot point at a purchase transaction.
                assertFailsWith<java.sql.SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO rg11_audit_link(ledger_id, audit_link_id, link_type, from_kind, from_id, to_kind, to_id) VALUES ('$LEDGER', 'audit-link-bad', 'periodic_allocation_recognition', 'domain_entity', 'installment-1', 'transaction', '$TX_1')",
                        0,
                    )
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun assertPersistedSnapshotEquals(
        expected: com.unifiedledger.application.Rg11Snapshot,
        actual: com.unifiedledger.application.Rg11Snapshot,
    ) {
        assertEquals(
            expected.formalTransactions.map(::formalProjection),
            actual.formalTransactions.map(::formalProjection),
        )
        assertEquals(expected.schedules, actual.schedules)
        assertEquals(expected.revisions, actual.revisions)
        assertEquals(expected.installments, actual.installments)
        assertEquals(expected.confirmations, actual.confirmations)
        assertEquals(expected.auditLinks, actual.auditLinks)
        assertEquals(expected.postingSemantics, actual.postingSemantics)
        assertEquals(expected.balances, actual.balances)
        assertEquals(expected.reports, actual.reports)
        assertEquals(expected.reconciliation, actual.reconciliation)
        assertEquals(expected.derivedStatuses, actual.derivedStatuses)
    }

    private fun formalProjection(record: com.unifiedledger.application.Rg11FormalTransactionRecord) = FormalRecordProjection(
        transaction = record.formalTransaction.transaction,
        versions = record.formalTransaction.versions,
        postingSets = record.formalTransaction.postingSets.map {
            PostingSetProjection(it.id, it.postings)
        },
        createdAt = record.createdAt,
        createdAtText = record.createdAtText,
        statisticsAtText = record.statisticsAtText,
        versionConfirmationIds = record.versionConfirmationIds,
    )

    private data class FormalRecordProjection(
        val transaction: Transaction,
        val versions: List<TransactionVersion>,
        val postingSets: List<PostingSetProjection>,
        val createdAt: Instant,
        val createdAtText: String?,
        val statisticsAtText: String?,
        val versionConfirmationIds: Map<TransactionVersionId, String>,
    )

    private data class PostingSetProjection(
        val id: PostingSetId,
        val postings: List<Posting>,
    )

    private fun catalog(): LedgerCatalog {
        val accounts = listOf(
            Account(PAYMENT_ACCOUNT, LEDGER, AccountKind.ASSET, CNY, ownedByUser = true, realAccount = true),
            Account(PREPAID_ACCOUNT, LEDGER, AccountKind.ASSET, CNY, ownedByUser = true, realAccount = false),
            Account(EXPENSE_ACCOUNT, LEDGER, AccountKind.EXPENSE, CNY, ownedByUser = false, realAccount = false),
        )
        val categories = listOf(
            Category(CATEGORY, LEDGER, parentId = CategoryId("root"), postingAccountId = EXPENSE_ACCOUNT, active = true),
        )
        return when (val created = LedgerCatalog.create(accounts, categories)) {
            is com.unifiedledger.domain.DomainResult.Success -> created.value
            is com.unifiedledger.domain.DomainResult.Failure -> error("invalid test catalog")
        }
    }

    private fun createOperation(): Rg11Operation.CreatePeriodicAllocation = Rg11Operation.CreatePeriodicAllocation(
        ledgerId = LEDGER,
        input = Rg11CreateInput(
            requestId = REQUEST_CREATE,
            paymentAccountId = PAYMENT_ACCOUNT,
            prepaidAccountId = PREPAID_ACCOUNT,
            categoryId = CATEGORY,
            amount = Money.ofMinor(10_000L, CNY),
            currency = CNY,
            startAt = START_AT,
            anchor = PeriodicAllocationAnchor.MonthEnd,
            explicitConfirmation = true,
            occurredAt = START_AT,
            installmentCount = 3,
        ),
        ids = Rg11CreateIds(
            transactionId = TX_1,
            versionId = VERSION_1,
            postingSetId = PostingSetId("posting-set-1"),
            paymentPostingId = PAYMENT_POSTING_1,
            prepaidPostingId = PREPAID_POSTING_1,
            scheduleId = SCHEDULE,
            revisionId = REVISION_1,
            installmentIds = listOf(INSTALLMENT_1, INSTALLMENT_2, INSTALLMENT_3),
        ),
    )

    private fun recognizeOperation(installmentId: String, amountMinor: Long, transactionId: TransactionId, auditLinkId: String): Rg11Operation.RecognizePeriodicAllocationInstallment =
        Rg11Operation.RecognizePeriodicAllocationInstallment(
            ledgerId = LEDGER,
            input = Rg11RecognizeInput(
                requestId = RequestId("request-recognize-$installmentId"),
                scheduleId = SCHEDULE,
                installmentId = installmentId,
                amount = Money.ofMinor(amountMinor, CNY),
                currency = CNY,
                explicitConfirmation = true,
            ),
            ids = Rg11RecognizeIds(
                transactionId = transactionId,
                versionId = TransactionVersionId("version-$transactionId"),
                postingSetId = PostingSetId("posting-set-$transactionId"),
                expensePostingId = PostingId("expense-posting-$transactionId"),
                prepaidPostingId = PostingId("prepaid-posting-$transactionId"),
                auditLinkId = auditLinkId,
            ),
        )

    private fun reviseOperation(): Rg11Operation.RevisePeriodicAllocation = Rg11Operation.RevisePeriodicAllocation(
        ledgerId = LEDGER,
        input = Rg11ReviseInput(
            requestId = RequestId("request-revise-1"),
            scheduleId = SCHEDULE,
            recognizedThrough = INSTALLMENT_1,
            remainingAmount = Money.ofMinor(6_667L, CNY),
            currency = CNY,
            explicitConfirmation = true,
            remainingInstallmentCount = 2,
        ),
        ids = Rg11ReviseIds(
            revisionId = REVISION_2,
            installmentIds = listOf(INSTALLMENT_4, INSTALLMENT_5),
        ),
    )

    private fun correctOperation(): Rg11Operation.CorrectTransactionVersion = Rg11Operation.CorrectTransactionVersion(
        ledgerId = LEDGER,
        input = Rg11CorrectInput(
            requestId = RequestId("request-correct-1"),
            transactionId = TX_2,
            correctionKind = "statistics_time",
            statisticsAt = CORRECTED_STATISTICS_AT,
            statisticsAtText = CORRECTED_STATISTICS_AT_TEXT,
            explicitConfirmation = true,
        ),
        ids = Rg11CorrectIds(
            versionId = VERSION_CORRECT,
            confirmationId = CONFIRMATION_CORRECT,
            operationId = "operation-correct-1",
            confirmationCreatedAt = CONFIRMATION_CREATED_AT,
            confirmationCreatedAtText = CONFIRMATION_CREATED_AT_TEXT,
        ),
    )

    private fun sqliteProperties(): Properties = Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

    private companion object {
        val CNY = CurrencyUnit("CNY", 2)
        val LEDGER = LedgerId("ledger-a")
        val PAYMENT_ACCOUNT = AccountId("asset-bank-a")
        val PREPAID_ACCOUNT = AccountId("prepaid-account-a")
        val EXPENSE_ACCOUNT = AccountId("expense-account-a")
        val CATEGORY = CategoryId("category-a")
        // 2026-01-31T00:00:00+08:00 is the month-end anchor of January 2026.
        val START_AT = Instant.parse("2026-01-30T16:00:00Z")
        val REQUEST_CREATE = RequestId("request-create-1")
        val TX_1 = TransactionId("transaction-purchase-1")
        val TX_2 = TransactionId("transaction-recognition-1")
        val TX_3 = TransactionId("transaction-recognition-4")
        val TX_4 = TransactionId("transaction-recognition-5")
        val VERSION_1 = TransactionVersionId("version-purchase-1")
        val PAYMENT_POSTING_1 = PostingId("payment-posting-1")
        val PREPAID_POSTING_1 = PostingId("prepaid-posting-1")
        // The correction targets TX_2 (the recognition of installment-1): the frozen
        // `main-correct` corrects a PREPAID_RECOGNITION transaction's statistics time.
        val VERSION_CORRECT = TransactionVersionId("version-correct-1")
        val CONFIRMATION_CORRECT = "confirmation-correct-1"
        // 2026-02-15T00:00:00+08:00 is a later statistics time than the January recognition.
        val CORRECTED_STATISTICS_AT = Instant.parse("2026-02-14T16:00:00Z")
        val CORRECTED_STATISTICS_AT_TEXT = "2026-02-15T00:00:00+08:00"
        val CONFIRMATION_CREATED_AT = Instant.parse("2026-02-01T00:01:00+08:00")
        val CONFIRMATION_CREATED_AT_TEXT = "2026-02-01T00:01:00+08:00"
        val SCHEDULE = "schedule-1"
        val REVISION_1 = "revision-1"
        val REVISION_2 = "revision-2"
        val INSTALLMENT_1 = "installment-1"
        val INSTALLMENT_2 = "installment-2"
        val INSTALLMENT_3 = "installment-3"
        val INSTALLMENT_4 = "installment-4"
        val INSTALLMENT_5 = "installment-5"
    }
}
