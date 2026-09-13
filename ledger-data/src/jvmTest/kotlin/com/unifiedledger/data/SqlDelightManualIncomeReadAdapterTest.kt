package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedIncomeTransactionFactory
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedManualIncomeCommitIds
import com.unifiedledger.application.ConfirmedManualIncomeIdSource
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ExecuteConfirmedManualIncome
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedManualIncome
import com.unifiedledger.application.ManualIncomeCommitResolution
import com.unifiedledger.application.ManualIncomeRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualIncomeCommitStatus
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
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
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-02.A income read-boundary integration evidence: current-pointer projection, ledger
 * isolation, request/receipt lookup, snapshot-aware resolution (including note), and the note
 * riding into the formal `transaction_version.note`.
 */
class SqlDelightManualIncomeReadAdapterTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val otherLedgerId = LedgerId("ledger-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val receivingAccountId = AccountId("asset-payment-local")
    private val incomeAccountId = AccountId("income-account-local")
    private val parentCategoryId = CategoryId("income-category-salary")
    private val categoryId = CategoryId("income-category-monthly")
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")
    private val catalog =
        incomeCatalog(
            ledgerId = ledgerId,
            cny = cny,
            receivingAccountId = receivingAccountId,
            incomeAccountId = incomeAccountId,
            parentCategoryId = parentCategoryId,
            categoryId = categoryId,
        )

    @Test
    fun commitRoundTripReadsBackSnapshotReceiptAndNote() {
        val path = Files.createTempFile("p7-02-income-read-", ".db")
        try {
            val harness = IncomeReadHarness(path, ledgerId, catalog)
            val created = assertIs<ConfirmedManualIncomeResult.Created>(harness.commit(fixtureRequest()))

            val rows = harness.adapter.loadCurrentRows(ledgerId)
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(created.receipt.transactionId, row.transactionId)
            assertEquals(2, row.postings.size)

            val record = harness.adapter.findManualIncomeByRequest(ledgerId, RequestId("request-income"))
            assertEquals(created.receipt, record?.receipt)
            assertEquals("monthly salary", record?.snapshot?.note)
            assertEquals(fixtureSnapshot(), record?.snapshot)
            assertEquals(
                created.receipt,
                harness.adapter.findManualIncomeByReceipt(ledgerId, created.receipt)?.receipt,
            )

            // The note really reached the formal transaction version.
            assertEquals(
                "monthly salary",
                harness.database.ledgerQueries
                    .selectPersistedVersions()
                    .executeAsList()
                    .single { it.transaction_id == created.receipt.transactionId.value }
                    .note,
            )
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun incomeReadsAreLedgerIsolated() {
        val path = Files.createTempFile("p7-02-income-isolation-", ".db")
        try {
            val harness = IncomeReadHarness(path, ledgerId, catalog)
            harness.commit(fixtureRequest())

            assertTrue(harness.adapter.loadCurrentRows(otherLedgerId).isEmpty())
            assertNull(harness.adapter.findManualIncomeByRequest(otherLedgerId, RequestId("request-income")))
            assertNull(harness.adapter.findManualIncomeByReceipt(otherLedgerId, ConfirmedIncomeReceipt(ConfirmationId("confirmation-seq-1"), TransactionId("tx-seq-1"))))
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun incomeResolverReturnsMatchingConflictAndAbsent() {
        val path = Files.createTempFile("p7-02-income-resolve-", ".db")
        try {
            val harness = IncomeReadHarness(path, ledgerId, catalog)
            val created = assertIs<ConfirmedManualIncomeResult.Created>(harness.commit(fixtureRequest()))
            val resolver = ResolveManualIncomeCommitStatus(harness.adapter)

            val matching = resolver.resolve(ledgerId, RequestId("request-income"), fixtureSnapshot())
            assertEquals(created.receipt, assertIs<ManualIncomeCommitResolution.MatchingReceipt>(matching).receipt)
            assertEquals(
                ManualIncomeCommitResolution.SnapshotConflict,
                resolver.resolve(ledgerId, RequestId("request-income"), fixtureSnapshot().copy(note = "different")),
            )
            assertEquals(
                ManualIncomeCommitResolution.Absent,
                resolver.resolve(ledgerId, RequestId("request-never"), fixtureSnapshot()),
            )
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun incomeReplayWritesZeroDuplicateRows() {
        val path = Files.createTempFile("p7-02-income-replay-", ".db")
        try {
            val harness = IncomeReadHarness(path, ledgerId, catalog)
            val request = fixtureRequest()
            val created = assertIs<ConfirmedManualIncomeResult.Created>(harness.commit(request))
            val replayed = assertIs<ConfirmedManualIncomeResult.NoChange>(harness.commit(request))
            assertEquals(created.receipt, replayed.receipt)

            assertEquals(
                1L,
                harness.database.ledgerQueries
                    .countManualIncomeRequests()
                    .executeAsOne(),
            )
            assertEquals(
                1L,
                harness.database.ledgerQueries
                    .countIncomeReceipts()
                    .executeAsOne(),
            )
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
                2L,
                harness.database.ledgerQueries
                    .countPostings()
                    .executeAsOne(),
            )
            harness.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun fixtureRequest() =
        ExplicitlyConfirmedManualIncome(
            ledgerId = ledgerId,
            requestId = RequestId("request-income"),
            amount = Money.ofMinor(300_000L, cny),
            categoryId = categoryId,
            receivingAccountId = receivingAccountId,
            occurredAt = occurredAt,
            note = "monthly salary",
            confirmation = ExplicitManualSave,
        )

    private fun fixtureSnapshot() =
        ManualIncomeRequestSnapshot(
            ledgerId = ledgerId,
            amount = Money.ofMinor(300_000L, cny),
            categoryId = categoryId,
            receivingAccountId = receivingAccountId,
            occurredAt = occurredAt,
            note = "monthly salary",
        )
}

private class IncomeReadHarness(
    path: java.nio.file.Path,
    private val ledgerId: LedgerId,
    catalog: LedgerCatalog,
) {
    val url = "jdbc:sqlite:${path.absolutePathString()}"
    private val driver = JdbcSqliteDriver(url)
    val database: LedgerDatabase
    val adapter: SqlDelightLedgerCurrentStateReadAdapter
    private val useCase: ExecuteConfirmedManualIncome
    private val idSource = SequentialIncomeIdSource()

    init {
        LedgerDatabase.Schema.create(driver)
        database = LedgerDatabase(driver)
        adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
        useCase =
            ExecuteConfirmedManualIncome(
                commitPort = SqlDelightConfirmedManualIncomeCommitPort(database, driver),
                idSource = idSource,
                createFormalTransaction = incomeFactory(catalog),
            )
    }

    fun commit(request: ExplicitlyConfirmedManualIncome): ConfirmedManualIncomeResult = useCase.execute(request)

    fun close() = driver.close()
}

private fun incomeFactory(catalog: LedgerCatalog): ConfirmedIncomeTransactionFactory =
    ConfirmedIncomeTransactionFactory { request, ids ->
        when (
            val result =
                createAssetReceivedOrdinaryIncome(
                    catalog = catalog,
                    command =
                        AssetReceivedOrdinaryIncomeCommand(
                            ledgerId = request.ledgerId,
                            amount = request.amount,
                            categoryId = request.categoryId,
                            receivingAccountId = request.receivingAccountId,
                            times = TransactionTimes.collapsed(request.occurredAt),
                            note = request.note,
                        ),
                    ids = ids.incomeIds,
                )
        ) {
            is DomainResult.Success ->
                DomainResult.Success(
                    ConfirmedManualIncomeCommit(
                        confirmationId = ids.confirmationId,
                        transaction = result.value,
                    ),
                )
            is DomainResult.Failure -> result
        }
    }

private class SequentialIncomeIdSource : ConfirmedManualIncomeIdSource {
    var count = 0
        private set

    override fun next(): ConfirmedManualIncomeCommitIds {
        count += 1
        val suffix = "seq-$count"
        return ConfirmedManualIncomeCommitIds(
            confirmationId = ConfirmationId("confirmation-$suffix"),
            incomeIds =
                AssetReceivedOrdinaryIncomeIds(
                    transactionId = TransactionId("tx-$suffix"),
                    versionId = TransactionVersionId("version-$suffix"),
                    postingSetId = PostingSetId("posting-set-$suffix"),
                    receivingPostingId = PostingId("posting-receiving-$suffix"),
                    incomePostingId = PostingId("posting-income-$suffix"),
                ),
        )
    }
}

private fun incomeCatalog(
    ledgerId: LedgerId,
    cny: CurrencyUnit,
    receivingAccountId: AccountId,
    incomeAccountId: AccountId,
    parentCategoryId: CategoryId,
    categoryId: CategoryId,
): LedgerCatalog =
    when (
        val result =
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(
                            id = receivingAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.ASSET,
                            currency = cny,
                            ownedByUser = true,
                            realAccount = true,
                        ),
                        Account(
                            id = incomeAccountId,
                            ledgerId = ledgerId,
                            kind = AccountKind.INCOME,
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
                            kind = CategoryKind.INCOME,
                        ),
                        Category(
                            id = categoryId,
                            ledgerId = ledgerId,
                            parentId = parentCategoryId,
                            postingAccountId = incomeAccountId,
                            active = true,
                            kind = CategoryKind.INCOME,
                        ),
                    ),
            )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("synthetic local-test catalog must be valid")
    }
