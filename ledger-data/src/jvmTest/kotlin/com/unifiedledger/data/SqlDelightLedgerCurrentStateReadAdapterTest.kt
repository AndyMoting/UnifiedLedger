package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedManualExpense
import com.unifiedledger.application.ManualExpenseCommitResolution
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P5-03 read-boundary integration evidence on an anonymous temp-file database
 * (spec section 12.2): current-pointer projection, ledger isolation, receipt lookup,
 * snapshot matching/conflict, reopen consistency and zero duplicate transaction/posting
 * on replay. Schema/migration zero-change is proven by the migration verifier task.
 */
class SqlDelightLedgerCurrentStateReadAdapterTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val otherLedgerId = LedgerId("ledger-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val paymentAccountId = AccountId("asset-payment-local")
    private val expenseAccountId = AccountId("expense-account-local")
    private val parentCategoryId = CategoryId("expense-category-food")
    private val categoryId = CategoryId("expense-category-breakfast")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val catalog =
        fixedCatalog(
            ledgerId = ledgerId,
            cny = cny,
            paymentAccountId = paymentAccountId,
            expenseAccountId = expenseAccountId,
            parentCategoryId = parentCategoryId,
            categoryId = categoryId,
        )

    @Test
    fun currentPointerReturnsOnlyTheCurrentVersion() {
        val path = Files.createTempFile("p5-03-current-pointer-", ".db")
        try {
            val harness = P503ReadHarness(path, ledgerId, catalog)
            val created = assertIs<ConfirmedManualExpenseResult.Created>(harness.commit(fixtureRequest()))

            val rows = harness.adapter.loadCurrentRows(ledgerId)
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(TransactionId("tx-seq-1"), row.transactionId)
            assertEquals(TransactionVersionId("version-seq-1"), row.currentVersionId)
            assertEquals(2, row.postings.size)
            assertEquals(listOf("posting-expense-seq-1", "posting-payment-seq-1"), row.postings.map { it.id.value })

            // Move the current pointer to a second version; only the pointed version is projected.
            harness.database.ledgerQueries.copyCurrentVersionWithNewNote(
                version_id = "version-seq-2",
                transaction_id = created.receipt.transactionId.value,
                ledger_id = ledgerId.value,
                expected_current_version_id = "version-seq-1",
                note = "replacement note",
            )
            harness.database.ledgerQueries.updateCurrentVersion(
                transaction_id = created.receipt.transactionId.value,
                current_version_id = "version-seq-2",
            )

            val currentRows = harness.adapter.loadCurrentRows(ledgerId)
            assertEquals(1, currentRows.size)
            assertEquals(TransactionVersionId("version-seq-2"), currentRows.single().currentVersionId)
            assertEquals(2, currentRows.single().postings.size)
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun ledgerIsolationPreventsCrossLedgerReads() {
        val path = Files.createTempFile("p5-03-ledger-isolation-", ".db")
        try {
            val harness = P503ReadHarness(path, ledgerId, catalog)
            val created = assertIs<ConfirmedManualExpenseResult.Created>(harness.commit(fixtureRequest()))
            val receipt = created.receipt

            assertTrue(harness.adapter.loadCurrentRows(otherLedgerId).isEmpty())
            assertNull(harness.adapter.findManualExpenseByRequest(otherLedgerId, RequestId("request-p5-03")))
            assertNull(harness.adapter.findManualExpenseByReceipt(otherLedgerId, receipt))

            assertEquals(1, harness.adapter.loadCurrentRows(ledgerId).size)
            assertEquals(
                RequestId("request-p5-03"),
                harness.adapter.findManualExpenseByRequest(ledgerId, RequestId("request-p5-03"))?.requestId,
            )
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun receiptLookupValidatesTheTransactionIdRelationship() {
        val path = Files.createTempFile("p5-03-receipt-lookup-", ".db")
        try {
            val harness = P503ReadHarness(path, ledgerId, catalog)
            val created = assertIs<ConfirmedManualExpenseResult.Created>(harness.commit(fixtureRequest()))

            val record = harness.adapter.findManualExpenseByReceipt(ledgerId, created.receipt)
            assertEquals(created.receipt, record?.receipt)
            assertEquals(TransactionVersionId("version-seq-1"), record?.currentVersionId)
            assertEquals(RequestId("request-p5-03"), record?.requestId)

            val wrongTransactionReceipt = ConfirmedExpenseReceipt(created.receipt.confirmationId, TransactionId("wrong-tx"))
            assertNull(harness.adapter.findManualExpenseByReceipt(ledgerId, wrongTransactionReceipt))
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun resolverReturnsMatchingAndConflictFromPersistedSnapshots() {
        val path = Files.createTempFile("p5-03-snapshot-resolution-", ".db")
        try {
            val harness = P503ReadHarness(path, ledgerId, catalog)
            val created = assertIs<ConfirmedManualExpenseResult.Created>(harness.commit(fixtureRequest()))
            val resolver = ResolveManualExpenseCommitStatus(harness.adapter)
            val attempted = fixtureSnapshot()

            val matching = resolver.resolve(ledgerId, RequestId("request-p5-03"), attempted)
            val matchingReceipt = assertIs<ManualExpenseCommitResolution.MatchingReceipt>(matching)
            assertEquals(created.receipt, matchingReceipt.receipt)

            val conflicting = attempted.copy(amount = Money.ofMinor(3_581L, cny))
            assertEquals(
                ManualExpenseCommitResolution.SnapshotConflict,
                resolver.resolve(ledgerId, RequestId("request-p5-03"), conflicting),
            )
            assertEquals(
                ManualExpenseCommitResolution.Absent,
                resolver.resolve(ledgerId, RequestId("request-never-committed"), attempted),
            )
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun reopenOfTheSameCurrentSchemaFileReadsBackTheCommit() {
        val path = Files.createTempFile("p5-03-reopen-", ".db")
        try {
            val harness = P503ReadHarness(path, ledgerId, catalog)
            val created = assertIs<ConfirmedManualExpenseResult.Created>(harness.commit(fixtureRequest()))
            harness.close()

            val reopened = P503ReopenedDatabase(path)
            val rows = reopened.adapter.loadCurrentRows(ledgerId)
            assertEquals(1, rows.size)
            assertEquals(created.receipt.transactionId, rows.single().transactionId)
            assertEquals(2, rows.single().postings.size)
            assertEquals(
                created.receipt,
                reopened.adapter.findManualExpenseByReceipt(ledgerId, created.receipt)?.receipt,
            )
            reopened.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun replayWritesZeroDuplicateTransactionsAndPostings() {
        val path = Files.createTempFile("p5-03-replay-", ".db")
        try {
            val harness = P503ReadHarness(path, ledgerId, catalog)
            val request = fixtureRequest()
            val created = assertIs<ConfirmedManualExpenseResult.Created>(harness.commit(request))

            val replayed = assertIs<ConfirmedManualExpenseResult.NoChange>(harness.commit(request))
            assertEquals(created.receipt, replayed.receipt)

            assertEquals(
                1L,
                harness.database.ledgerQueries
                    .countTransactions()
                    .executeAsOne(),
            )
            assertEquals(
                1L,
                harness.database.ledgerQueries
                    .countVersions()
                    .executeAsOne(),
            )
            assertEquals(
                1L,
                harness.database.ledgerQueries
                    .countPostingSets()
                    .executeAsOne(),
            )
            assertEquals(
                2L,
                harness.database.ledgerQueries
                    .countPostings()
                    .executeAsOne(),
            )
            assertEquals(
                1L,
                harness.database.ledgerQueries
                    .countRequests()
                    .executeAsOne(),
            )
            assertEquals(
                1L,
                harness.database.ledgerQueries
                    .countReceipts()
                    .executeAsOne(),
            )
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun fixtureRequest(
        requestId: String = "request-p5-03",
    ) = ExplicitlyConfirmedManualExpense(
        ledgerId = ledgerId,
        requestId = RequestId(requestId),
        amount = Money.ofMinor(3_580L, cny),
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        occurredAt = occurredAt,
        note = "",
        confirmation = ExplicitManualSave,
    )

    private fun fixtureSnapshot() =
        ManualExpenseRequestSnapshot(
            ledgerId = ledgerId,
            amount = Money.ofMinor(3_580L, cny),
            categoryId = categoryId,
            paymentAccountId = paymentAccountId,
            occurredAt = occurredAt,
            note = "",
        )
}

private class P503ReadHarness(
    path: Path,
    private val ledgerId: LedgerId,
    catalog: LedgerCatalog,
) {
    val url = "jdbc:sqlite:${path.absolutePathString()}"
    private val driver = JdbcSqliteDriver(url)
    val database: LedgerDatabase
    val adapter: SqlDelightLedgerCurrentStateReadAdapter
    private val useCase: ExecuteConfirmedManualExpense
    private val idSource = SequentialIdSource()

    init {
        LedgerDatabase.Schema.create(driver)
        database = LedgerDatabase(driver)
        val commitPort = SqlDelightConfirmedManualExpenseCommitPort(database, driver)
        adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
        useCase =
            ExecuteConfirmedManualExpense(
                commitPort = commitPort,
                idSource = idSource,
                createFormalTransaction = expenseFactory(catalog),
            )
    }

    fun commit(request: ExplicitlyConfirmedManualExpense): ConfirmedManualExpenseResult = useCase.execute(request)

    fun close() = driver.close()
}

private class P503ReopenedDatabase(
    path: Path,
) {
    val url = "jdbc:sqlite:${path.absolutePathString()}"
    private val driver = JdbcSqliteDriver(url)
    val adapter: SqlDelightLedgerCurrentStateReadAdapter

    init {
        val database = LedgerDatabase(driver)
        adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
    }

    fun close() = driver.close()
}

private fun expenseFactory(catalog: LedgerCatalog): ConfirmedExpenseTransactionFactory =
    ConfirmedExpenseTransactionFactory { request, ids ->
        when (
            val result =
                createAssetPaidOrdinaryExpense(
                    catalog = catalog,
                    command =
                        AssetPaidOrdinaryExpenseCommand(
                            ledgerId = request.ledgerId,
                            amount = request.amount,
                            categoryId = request.categoryId,
                            paymentAccountId = request.paymentAccountId,
                            times = TransactionTimes.collapsed(request.occurredAt),
                        ),
                    ids = ids.expenseIds,
                )
        ) {
            is DomainResult.Success ->
                DomainResult.Success(
                    ConfirmedManualExpenseCommit(
                        confirmationId = ids.confirmationId,
                        transaction = result.value,
                    ),
                )
            is DomainResult.Failure -> result
        }
    }

private class SequentialIdSource : ConfirmedManualExpenseIdSource {
    var count = 0
        private set

    override fun next(): ConfirmedManualExpenseCommitIds {
        count += 1
        val suffix = "seq-$count"
        return ConfirmedManualExpenseCommitIds(
            confirmationId = ConfirmationId("confirmation-$suffix"),
            expenseIds =
                AssetPaidOrdinaryExpenseIds(
                    transactionId = TransactionId("tx-$suffix"),
                    versionId = TransactionVersionId("version-$suffix"),
                    postingSetId = PostingSetId("posting-set-$suffix"),
                    expensePostingId = PostingId("posting-expense-$suffix"),
                    paymentPostingId = PostingId("posting-payment-$suffix"),
                ),
        )
    }
}

private fun fixedCatalog(
    ledgerId: LedgerId,
    cny: CurrencyUnit,
    paymentAccountId: AccountId,
    expenseAccountId: AccountId,
    parentCategoryId: CategoryId,
    categoryId: CategoryId,
): LedgerCatalog =
    when (
        val result =
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(
                            id = paymentAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.ASSET,
                            currency = cny,
                            ownedByUser = true,
                            realAccount = true,
                        ),
                        Account(
                            id = expenseAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.EXPENSE,
                            currency = cny,
                            ownedByUser = false,
                            realAccount = false,
                        ),
                    ),
                categories =
                    listOf(
                        Category(
                            id = parentCategoryId,
                            ledgerId = ledgerId,
                            parentId = null,
                            postingAccountId = null,
                            active = true,
                        ),
                        Category(
                            id = categoryId,
                            ledgerId = ledgerId,
                            parentId = parentCategoryId,
                            postingAccountId = expenseAccountId,
                            active = true,
                        ),
                    ),
            )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("synthetic local-test catalog must be valid")
    }
