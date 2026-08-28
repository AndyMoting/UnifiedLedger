package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightConfirmedManualExpenseCommitPortTest {
    @Test
    fun firstSaveAtomicallyPersistsTheCompleteRequestReceiptAndFormalTransaction() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()
            var callbackCount = 0

            val result =
                harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                    callbackCount += 1
                    DomainResult.Success(fixture.commit())
                }

            assertEquals(1, callbackCount)
            assertEquals(
                ConfirmedManualExpenseResult.Created(fixture.receipt()),
                result,
            )
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), harness.counts())
            assertEquals(fixture.persistedRequest(), harness.persistedRequest())
            assertEquals(fixture.persistedTransaction(), harness.persistedTransaction())
            assertEquals(fixture.persistedVersion(), harness.persistedVersion())
            assertEquals(fixture.persistedPostings(), harness.persistedPostings())
        }
    }

    @Test
    fun equivalentReplayAfterReopeningReturnsTheOriginalReceiptWithoutCreationOrRewind() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).use { first ->
                assertIs<ConfirmedManualExpenseResult.Created>(
                    first.port.commitOnce(fixture.identity, fixture.snapshot) {
                        DomainResult.Success(fixture.commit())
                    },
                )
                first.appendNoteReplacement(fixture)
            }

            var callbackCount = 0
            file.open().use { reopened ->
                val result =
                    reopened.port.commitOnce(fixture.identity, fixture.snapshot) {
                        callbackCount += 1
                        DomainResult.Success(fixture.commit())
                    }

                assertEquals(0, callbackCount)
                assertEquals(
                    ConfirmedManualExpenseResult.NoChange(fixture.receipt()),
                    result,
                )
                assertEquals(2, reopened.versionCount())
                assertEquals("version-expense-a-v2", reopened.currentVersionId())
                assertEquals("replacement note", reopened.currentNote())
            }
        }
    }

    @Test
    fun conflictingReplayAfterReopeningDoesNotInvokeCreationOrWrite() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).use { first ->
                first.port.commitOnce(fixture.identity, fixture.snapshot) {
                    DomainResult.Success(fixture.commit())
                }
            }

            val conflicts =
                listOf(
                    fixture.snapshot.copy(amount = fixture.money(3_581)),
                    fixture.snapshot.copy(
                        amount = Money.ofMinor(3_580, CurrencyUnit("USD", 2)),
                    ),
                    fixture.snapshot.copy(categoryId = CategoryId("expense-category-other")),
                    fixture.snapshot.copy(paymentAccountId = AccountId("asset-other")),
                    fixture.snapshot.copy(occurredAt = Instant.parse("2026-01-15T00:31:00Z")),
                    fixture.snapshot.copy(note = "changed note"),
                )
            var callbackCount = 0

            file.open().use { reopened ->
                conflicts.forEach { conflict ->
                    assertEquals(
                        ConfirmedManualExpenseResult.RequestIdentityConflict(fixture.identity),
                        reopened.port.commitOnce(fixture.identity, conflict) {
                            callbackCount += 1
                            DomainResult.Success(fixture.commit())
                        },
                    )
                }

                assertEquals(0, callbackCount)
                assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), reopened.counts())
            }
        }
    }

    @Test
    fun rejectedCreationLeavesNoResidueAndTheIdentityCanBeRetried() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            val rejected =
                harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                    DomainResult.Failure(OrdinaryExpenseViolation.AmountMustBePositive)
                }

            assertEquals(
                ConfirmedManualExpenseResult.Rejected(
                    OrdinaryExpenseViolation.AmountMustBePositive,
                ),
                rejected,
            )
            assertEquals(StorageCounts.EMPTY, harness.counts())

            val created =
                harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                    DomainResult.Success(fixture.commit())
                }
            assertIs<ConfirmedManualExpenseResult.Created>(created)
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), harness.counts())
        }
    }

    @Test
    fun aDistinctRequestIdentityPersistsAnIndependentTransaction() {
        DatabaseHarness.inMemory().use { harness ->
            val first = ExpenseFixture()
            val second =
                ExpenseFixture(
                    requestId = "request-b",
                    suffix = "b",
                    confirmationId = "confirmation-b",
                )

            assertIs<ConfirmedManualExpenseResult.Created>(
                harness.port.commitOnce(first.identity, first.snapshot) {
                    DomainResult.Success(first.commit())
                },
            )
            assertIs<ConfirmedManualExpenseResult.Created>(
                harness.port.commitOnce(second.identity, second.snapshot) {
                    DomainResult.Success(second.commit())
                },
            )

            assertEquals(first.snapshot, second.snapshot)
            assertEquals(StorageCounts(2, 2, 2, 2, 2, 4), harness.counts())
        }
    }

    @Test
    fun aPersistenceFailureRollsBackTheEntireSecondCommit() {
        DatabaseHarness.inMemory().use { harness ->
            val first = ExpenseFixture()
            val colliding =
                ExpenseFixture(
                    requestId = "request-b",
                    suffix = "b",
                    confirmationId = "confirmation-b",
                    postingIdSuffix = "a",
                )
            harness.port.commitOnce(first.identity, first.snapshot) {
                DomainResult.Success(first.commit())
            }

            assertFailsWith<SQLException> {
                harness.port.commitOnce(colliding.identity, colliding.snapshot) {
                    DomainResult.Success(colliding.commit())
                }
            }

            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), harness.counts())
        }
    }

    @Test
    fun identityAndSnapshotMustBelongToTheSameLedger() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            assertFailsWith<IllegalArgumentException> {
                harness.port.commitOnce(
                    fixture.identity.copy(ledgerId = LedgerId("ledger-other")),
                    fixture.snapshot,
                ) {
                    DomainResult.Success(fixture.commit())
                }
            }

            assertEquals(StorageCounts.EMPTY, harness.counts())
        }
    }

    @Test
    fun concurrentEquivalentRequestsClaimOnceAndReturnCreatedAndNoChange() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).close()
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val callbackCount = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (0 until 2).map {
                        executor.submit<ConfirmedManualExpenseResult> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            file.open().use { harness ->
                                harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                                    callbackCount.incrementAndGet()
                                    callbackEntered.countDown()
                                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                                    DomainResult.Success(fixture.commit())
                                }
                            }
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()

                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, callbackCount.get())
                assertEquals(
                    setOf(
                        ConfirmedManualExpenseResult.Created(fixture.receipt()),
                        ConfirmedManualExpenseResult.NoChange(fixture.receipt()),
                    ),
                    results.toSet(),
                )
            } finally {
                executor.shutdownNow()
            }
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), file.open().use { it.counts() })
        }
    }

    @Test
    fun concurrentChangedRequestsClaimOnceAndReturnConflictWithoutSecondCallback() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).close()
            val changed = fixture.snapshot.copy(note = "changed")
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val callbackCount = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf(fixture.snapshot, changed).map { snapshot ->
                        executor.submit<ConfirmedManualExpenseResult> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            file.open().use { harness ->
                                harness.port.commitOnce(fixture.identity, snapshot) {
                                    callbackCount.incrementAndGet()
                                    callbackEntered.countDown()
                                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                                    DomainResult.Success(fixture.commit())
                                }
                            }
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()

                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, callbackCount.get())
                assertEquals(1, results.count { it is ConfirmedManualExpenseResult.Created })
                assertEquals(1, results.count { it is ConfirmedManualExpenseResult.RequestIdentityConflict })
            } finally {
                executor.shutdownNow()
            }
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), file.open().use { it.counts() })
        }
    }

    @Test
    fun aThrowingCreationCallbackRollsBackTheClaimAndEveryFormalRow() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            assertFailsWith<IllegalStateException> {
                harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                    error("synthetic callback failure")
                }
            }

            assertEquals(StorageCounts.EMPTY, harness.counts())
        }
    }

    @Test
    fun aCommitFromAnotherLedgerIsRejectedBeforeAnyWrite() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            assertFailsWith<IllegalArgumentException> {
                harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                    DomainResult.Success(fixture.commitForLedger(LedgerId("ledger-other")))
                }
            }

            assertEquals(StorageCounts.EMPTY, harness.counts())
        }
    }

    @Test
    fun everyJvmDriverEnablesForeignKeysAndRejectsInvalidFormalTopology() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()
            assertEquals("1", harness.foreignKeysEnabled())

            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO ledger_transaction_current_version(
                      transaction_id, ledger_id, current_version_id
                    ) VALUES ('tx-expense-a', 'ledger-a', 'version-missing')
                    """.trimIndent(),
                    0,
                )
            }

            harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                DomainResult.Success(fixture.commit())
            }
            harness.driver.execute(
                null,
                """
                INSERT INTO manual_expense_request(
                  ledger_id, request_id, amount_minor, currency_code, currency_precision,
                  category_id, payment_account_id, occurred_at, note, confirmation_marker
                ) VALUES ('ledger-other', 'request-other', 1, 'CNY', 2,
                  'category', 'asset', '2026-01-15T00:30:00Z', '', 'explicit_manual_save')
                """.trimIndent(),
                0,
            )
            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO confirmed_expense_receipt(
                      ledger_id, request_id, confirmation_id, transaction_id
                    ) VALUES ('ledger-other', 'request-other', 'confirmation-other', 'tx-expense-a')
                    """.trimIndent(),
                    0,
                )
            }

            harness.driver.execute(
                null,
                "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('posting-set-other', 'ledger-other')",
                0,
            )
            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO posting(
                      posting_id, posting_set_id, ledger_id, posting_index,
                      account_id, amount_minor, currency_code, currency_precision
                    ) VALUES ('posting-cross-ledger', 'posting-set-other', 'ledger-a', 0,
                      'asset-bank-a', 1, 'CNY', 2)
                    """.trimIndent(),
                    0,
                )
            }

            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    "UPDATE ledger_transaction SET kind = 'UNKNOWN' WHERE transaction_id = 'tx-expense-a'",
                    0,
                )
            }

            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO posting(
                      posting_id, posting_set_id, ledger_id, posting_index,
                      account_id, amount_minor, currency_code, currency_precision
                    ) VALUES ('posting-negative-precision', 'posting-set-expense-a', 'ledger-a', 9,
                      'asset-bank-a', 1, 'CNY', -1)
                    """.trimIndent(),
                    0,
                )
            }
        }
    }
}

private data class StorageCounts(
    val requests: Long,
    val receipts: Long,
    val transactions: Long,
    val versions: Long,
    val postingSets: Long,
    val postings: Long,
) {
    companion object {
        val EMPTY = StorageCounts(0, 0, 0, 0, 0, 0)
    }
}

private data class PersistedRequest(
    val ledgerId: String,
    val requestId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val categoryId: String,
    val paymentAccountId: String,
    val occurredAt: String,
    val note: String,
    val confirmationMarker: String,
    val confirmationId: String,
    val transactionId: String,
)

private data class PersistedTransaction(
    val transactionId: String,
    val ledgerId: String,
    val kind: String,
    val currentVersionId: String,
)

private data class PersistedVersion(
    val versionId: String,
    val transactionId: String,
    val versionNumber: Long,
    val postingSetId: String,
    val occurredAt: String,
    val statisticsAt: String,
    val effectiveAt: String,
    val note: String?,
)

private data class PersistedPosting(
    val postingId: String,
    val postingIndex: Long,
    val accountId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
)

private class DatabaseHarness(
    val driver: JdbcSqliteDriver,
) : AutoCloseable {
    private val database = LedgerDatabase(driver)
    val port = SqlDelightConfirmedManualExpenseCommitPort(database, driver)

    fun counts() =
        StorageCounts(
            requests = database.ledgerQueries.countRequests().executeAsOne(),
            receipts = database.ledgerQueries.countReceipts().executeAsOne(),
            transactions = database.ledgerQueries.countTransactions().executeAsOne(),
            versions = database.ledgerQueries.countVersions().executeAsOne(),
            postingSets = database.ledgerQueries.countPostingSets().executeAsOne(),
            postings = database.ledgerQueries.countPostings().executeAsOne(),
        )

    fun persistedRequest(): PersistedRequest =
        database.ledgerQueries
            .selectPersistedRequest {
                ledgerId,
                requestId,
                amountMinor,
                currencyCode,
                currencyPrecision,
                categoryId,
                paymentAccountId,
                occurredAt,
                note,
                confirmationMarker,
                confirmationId,
                transactionId,
                ->
                PersistedRequest(
                    ledgerId,
                    requestId,
                    amountMinor,
                    currencyCode,
                    currencyPrecision,
                    categoryId,
                    paymentAccountId,
                    occurredAt,
                    note,
                    confirmationMarker,
                    confirmationId,
                    transactionId,
                )
            }.executeAsOne()

    fun persistedTransaction(): PersistedTransaction =
        database.ledgerQueries
            .selectPersistedTransaction {
                transactionId,
                ledgerId,
                kind,
                currentVersionId,
                ->
                PersistedTransaction(transactionId, ledgerId, kind, currentVersionId)
            }.executeAsOne()

    fun persistedVersion(): PersistedVersion =
        database.ledgerQueries
            .selectPersistedVersions {
                versionId,
                transactionId,
                versionNumber,
                postingSetId,
                occurredAt,
                statisticsAt,
                effectiveAt,
                note,
                ->
                PersistedVersion(
                    versionId,
                    transactionId,
                    versionNumber,
                    postingSetId,
                    occurredAt,
                    statisticsAt,
                    effectiveAt,
                    note,
                )
            }.executeAsOne()

    fun persistedPostings(): List<PersistedPosting> =
        database.ledgerQueries
            .selectPersistedPostings {
                postingId,
                postingIndex,
                accountId,
                amountMinor,
                currencyCode,
                currencyPrecision,
                ->
                PersistedPosting(
                    postingId,
                    postingIndex,
                    accountId,
                    amountMinor,
                    currencyCode,
                    currencyPrecision,
                )
            }.executeAsList()

    fun appendNoteReplacement(fixture: ExpenseFixture) {
        database.transaction {
            database.ledgerQueries.insertTransactionVersion(
                version_id = "version-expense-a-v2",
                transaction_id = fixture.transactionId.value,
                ledger_id = "ledger-a",
                version_number = 2,
                posting_set_id = fixture.postingSetId.value,
                occurred_at = fixture.occurredAt.toString(),
                statistics_at = fixture.occurredAt.toString(),
                effective_at = fixture.occurredAt.toString(),
                note = "replacement note",
            )
            database.ledgerQueries.updateCurrentVersion(
                current_version_id = "version-expense-a-v2",
                transaction_id = fixture.transactionId.value,
            )
        }
    }

    fun versionCount(): Long = database.ledgerQueries.countVersions().executeAsOne()

    fun currentVersionId(): String = database.ledgerQueries.selectCurrentVersionId().executeAsOne()

    fun currentNote(): String? =
        database.ledgerQueries
            .selectCurrentNote()
            .executeAsOne()
            .note

    fun foreignKeysEnabled(): String = database.ledgerQueries.foreignKeysEnabled().executeAsOne()

    override fun close() {
        driver.close()
    }

    companion object {
        fun inMemory(): DatabaseHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
            LedgerDatabase.Schema.create(driver)
            return DatabaseHarness(driver)
        }
    }
}

private class FileDatabase private constructor(
    private val path: java.nio.file.Path,
) : AutoCloseable {
    fun open(createSchema: Boolean = false): DatabaseHarness {
        val driver =
            JdbcSqliteDriver(
                "jdbc:sqlite:${path.absolutePathString()}",
                sqliteProperties(),
            )
        if (createSchema) LedgerDatabase.Schema.create(driver)
        return DatabaseHarness(driver)
    }

    override fun close() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun create(): FileDatabase = FileDatabase(Files.createTempFile("ledger-data-", ".db"))
    }
}

private fun sqliteProperties(): Properties =
    Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

private fun DatabaseHarness.Companion.fileBacked(): FileDatabase = FileDatabase.create()

private class ExpenseFixture(
    requestId: String = "request-a",
    private val suffix: String = "a",
    private val confirmationId: String = "confirmation-a",
    private val postingIdSuffix: String = suffix,
) {
    private val ledgerId = LedgerId("ledger-a")
    val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    val transactionId = TransactionId("tx-expense-$suffix")
    val postingSetId = PostingSetId("posting-set-expense-$suffix")

    val identity = ManualExpenseRequestIdentity(ledgerId, RequestId(requestId))
    val snapshot =
        ManualExpenseRequestSnapshot(
            ledgerId = ledgerId,
            amount = money(3_580),
            categoryId = CategoryId("expense-category-breakfast"),
            paymentAccountId = AccountId("asset-bank-a"),
            occurredAt = occurredAt,
            note = "",
        )

    fun money(minorUnits: Long): Money = Money.ofMinor(minorUnits, CurrencyUnit("CNY", 2))

    fun receipt() =
        ConfirmedExpenseReceipt(
            confirmationId = ConfirmationId(confirmationId),
            transactionId = transactionId,
        )

    fun commit() = commitForLedger(LedgerId("ledger-a"))

    fun commitForLedger(commitLedgerId: LedgerId) =
        ConfirmedManualExpenseCommit(
            confirmationId = ConfirmationId(confirmationId),
            transaction = formalTransaction(commitLedgerId),
        )

    fun persistedRequest() =
        PersistedRequest(
            ledgerId = "ledger-a",
            requestId = identity.requestId.value,
            amountMinor = 3_580,
            currencyCode = "CNY",
            currencyPrecision = 2,
            categoryId = "expense-category-breakfast",
            paymentAccountId = "asset-bank-a",
            occurredAt = occurredAt.toString(),
            note = "",
            confirmationMarker = "explicit_manual_save",
            confirmationId = confirmationId,
            transactionId = transactionId.value,
        )

    fun persistedTransaction() =
        PersistedTransaction(
            transactionId.value,
            "ledger-a",
            TransactionKind.EXPENSE.name,
            "version-expense-$suffix-v1",
        )

    fun persistedVersion() =
        PersistedVersion(
            "version-expense-$suffix-v1",
            transactionId.value,
            1,
            postingSetId.value,
            occurredAt.toString(),
            occurredAt.toString(),
            occurredAt.toString(),
            null,
        )

    fun persistedPostings() =
        listOf(
            PersistedPosting(
                "posting-expense-$postingIdSuffix",
                0,
                "expense-account-breakfast",
                3_580,
                "CNY",
                2,
            ),
            PersistedPosting(
                "posting-bank-$postingIdSuffix",
                1,
                "asset-bank-a",
                -3_580,
                "CNY",
                2,
            ),
        )

    private fun formalTransaction(commitLedgerId: LedgerId): FormalTransaction {
        val versionId = TransactionVersionId("version-expense-$suffix-v1")
        val postingSet =
            assertSuccess(
                PostingSet.create(
                    postingSetId,
                    listOf(
                        Posting(
                            PostingId("posting-expense-$postingIdSuffix"),
                            AccountId("expense-account-breakfast"),
                            money(3_580),
                        ),
                        Posting(
                            PostingId("posting-bank-$postingIdSuffix"),
                            AccountId("asset-bank-a"),
                            money(-3_580),
                        ),
                    ),
                ),
            )
        return assertSuccess(
            FormalTransaction.create(
                transaction =
                    Transaction(
                        id = transactionId,
                        ledgerId = commitLedgerId,
                        kind = TransactionKind.EXPENSE,
                        currentVersionId = versionId,
                    ),
                versions =
                    listOf(
                        TransactionVersion(
                            id = versionId,
                            transactionId = transactionId,
                            versionNumber = 1,
                            postingSetId = postingSetId,
                            times = TransactionTimes.collapsed(occurredAt),
                        ),
                    ),
                postingSets = listOf(postingSet),
            ),
        )
    }
}

private fun <T> assertSuccess(result: DomainResult<T>): T = assertIs<DomainResult.Success<T>>(result).value
