package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg08ExecutionResult
import com.unifiedledger.application.Rg08FixtureCase
import com.unifiedledger.application.Rg08Operation
import com.unifiedledger.application.Rg08Snapshot
import com.unifiedledger.application.Rg09ExecutionResult
import com.unifiedledger.application.Rg09FixtureCase
import com.unifiedledger.application.Rg09Operation
import com.unifiedledger.application.Rg09Snapshot
import com.unifiedledger.application.Rg10ExecutionResult
import com.unifiedledger.application.Rg10FixtureCase
import com.unifiedledger.application.Rg10Operation
import com.unifiedledger.application.Rg10Snapshot
import com.unifiedledger.application.Rg11CreateIds
import com.unifiedledger.application.Rg11CreateInput
import com.unifiedledger.application.Rg11ExecutionResult
import com.unifiedledger.application.Rg11Operation
import com.unifiedledger.application.Rg11Snapshot
import com.unifiedledger.application.Rg12ExecutionResult
import com.unifiedledger.application.Rg12FixtureCase
import com.unifiedledger.application.Rg12Snapshot
import com.unifiedledger.application.adaptRg08Fixture
import com.unifiedledger.application.adaptRg09Fixture
import com.unifiedledger.application.adaptRg10Fixture
import com.unifiedledger.application.adaptRg12Fixture
import com.unifiedledger.application.parseRg08FixtureInputs
import com.unifiedledger.application.parseRg09FixtureInputs
import com.unifiedledger.application.parseRg10FixtureInputs
import com.unifiedledger.application.parseRg12FixtureInputs
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
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
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * DATA-001 (D-091) coexistence acceptance: five RG stores (08/09/10/11/12) share one
 * `LedgerDatabase` instance and the unified `formal_transaction_metadata` table. Each
 * store commits its typical formal-transaction path (RG-08 lend / RG-09 main path /
 * RG-10 main path / RG-11 create / RG-12 correction), then every snapshot is captured
 * after all commits and must round-trip byte-equal across a plain reopen.
 *
 * The unified schema makes every store load the whole shared ledger per `ledger_id`
 * (the D-091 full-load design; the per-RG select queries carry no kind filter), so the
 * formal-transaction sets legitimately include every RG's transactions on the same
 * ledger. The assertions are therefore: (a) the shared table holds a metadata row for
 * every loaded transaction; (b) each store's snapshot is exactly stable across reopen;
 * (c) each store's own committed metadata keeps its exact statistics time text.
 * RG-12 stays on its frozen fixture ledger (`ledger-rg-12`).
 */
class MultiRgStoreCoexistenceTest {
    @Test
    fun testMultiRgCoexistence_commitReopenSnapshotEquality() {
        val path = Files.createTempFile("ledger-data-multirg-coexistence-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        lateinit var snapshot08: Rg08Snapshot
        lateinit var snapshot09: Rg09Snapshot
        lateinit var snapshot10: Rg10Snapshot
        lateinit var snapshot11: Rg11Snapshot
        lateinit var snapshot12: Rg12Snapshot
        try {
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)

                // Expected statistics time texts captured from in-memory records and
                // operation inputs BEFORE any commit (non-circular: the shared table is
                // later compared against these memory-derived expectations, never against
                // itself). The RG-08 opening record carries no statisticsAtText, so its
                // expected value follows the store's documented write rule
                // (statisticsAtText ?: last version statisticsAt).
                val expectedStatistics = mutableMapOf<LedgerId, MutableMap<String, String?>>()

                // RG-09: the main path must run first. Its confirm_candidate gate
                // (D-065) compares against the frozen fixture fingerprint, which covers
                // the opening-only ledger, so no other RG's transaction may land on the
                // ledger before preview and confirm complete. The transfer and allocation
                // ops carry no fingerprint gate and follow in the same uninterrupted run.
                val fixture09 = loadFixture09()
                val store09 = store09(database, driver, fixture09)
                val preview09 =
                    fixture09.operations
                        .first { it.operation is Rg09Operation.PreviewTargetBalance }
                        .operation as Rg09Operation.PreviewTargetBalance
                val confirm09 =
                    fixture09.operations
                        .first { it.operation is Rg09Operation.ConfirmBalanceAdjustment }
                        .operation as Rg09Operation.ConfirmBalanceAdjustment
                val transfer09 =
                    fixture09.operations
                        .first { it.operation is Rg09Operation.ConfirmRealTransfer }
                        .operation as Rg09Operation.ConfirmRealTransfer
                val allocation09 =
                    fixture09.operations
                        .first { it.operation is Rg09Operation.ConfirmExplanationAllocation }
                        .operation as Rg09Operation.ConfirmExplanationAllocation
                val opening09 = fixture09.openingTransactions.single()
                expectedStatistics.getOrPut(fixture09.ledgerId) { mutableMapOf() }.apply {
                    put(opening09.formalTransaction.transaction.id.value, opening09.statisticsAtText)
                    put(confirm09.ids.transactionId.value, preview09.input.targetObservedAtText)
                    put(transfer09.ids.transactionId.value, transfer09.input.actualOccurredAtText)
                    // The allocation reversal record folds the confirmed adjustment's
                    // target observed time, which is the preview input's text.
                    put(allocation09.ids.reversalTransactionId.value, preview09.input.targetObservedAtText)
                }
                fixture09.operations.take(4).forEach { item ->
                    assertIs<Rg09ExecutionResult.Accepted>(store09.commit(item.operation), item.id)
                }

                // RG-08: the lend event is the first formal-transaction commit.
                val fixture08 = loadFixture08()
                val store08 = store08(database, driver, fixture08)
                val lend = fixture08.operations.first { it.id == "lend" }
                val lendOperation = lend.operation as Rg08Operation.ValidateLendingEvent
                val opening08 = fixture08.openingTransactions.single()
                expectedStatistics.getOrPut(fixture08.ledgerId) { mutableMapOf() }.apply {
                    put(
                        opening08.formalTransaction.transaction.id.value,
                        opening08.statisticsAtText
                            ?: opening08.formalTransaction.versions
                                .last()
                                .times.statisticsAt
                                .toString(),
                    )
                    put(lendOperation.ids.transactionId.value, lendOperation.input.actualAtText)
                }
                assertIs<Rg08ExecutionResult.Accepted>(store08.commit(lend.operation), "rg08 lend")

                // RG-10: the main path (recharge, spend, expiry reminder, expiry).
                val fixture10 = loadFixture10()
                val store10 = store10(database, driver, fixture10)
                val recharge10 =
                    fixture10.operations
                        .first { it.operation is Rg10Operation.ConfirmStoredValueRecharge }
                        .operation as Rg10Operation.ConfirmStoredValueRecharge
                val spend10 =
                    fixture10.operations
                        .first { it.operation is Rg10Operation.ConfirmStoredValueSpend }
                        .operation as Rg10Operation.ConfirmStoredValueSpend
                val expiryLoss10 =
                    fixture10.operations
                        .first { it.operation is Rg10Operation.ConfirmStoredValueExpiryLoss }
                        .operation as Rg10Operation.ConfirmStoredValueExpiryLoss
                val opening10 = fixture10.openingTransactions.single()
                expectedStatistics.getOrPut(fixture10.ledgerId) { mutableMapOf() }.apply {
                    put(opening10.formalTransaction.transaction.id.value, opening10.statisticsAtText)
                    put(recharge10.ids.transactionId.value, recharge10.input.occurredAtText)
                    put(spend10.ids.transactionId.value, spend10.input.occurredAtText)
                    put(expiryLoss10.ids.transactionId.value, expiryLoss10.input.occurredAtText)
                }
                fixture10.operations.forEach { item ->
                    assertIs<Rg10ExecutionResult.Accepted>(store10.commit(item.operation), item.id)
                }

                // RG-11: create the periodic allocation.
                val store11 = SqlDelightRg11Store(database, driver, rg11Catalog())
                val create11 = createOperation11()
                expectedStatistics
                    .getOrPut(LEDGER_A) { mutableMapOf() }
                    .put(TX_1_11.value, create11.input.occurredAtText)
                assertIs<Rg11ExecutionResult.Accepted>(
                    store11.commit(create11),
                    "rg11 create_periodic_allocation",
                )

                // RG-12: the correction on its frozen fixture ledger. The corrected
                // record preserves the baseline statistics time text.
                val fixture12 = loadFixture12()
                val store12 = store12(database, driver, fixture12, "root-correction")
                val correct = fixture12.operations.single { it.id == "root-correction-correct" }
                val opening12 =
                    fixture12.baselines
                        .getValue("root-correction")
                        .formalTransactions
                        .single()
                expectedStatistics
                    .getOrPut(fixture12.ledgerId) { mutableMapOf() }
                    .put("root-correction-transaction", opening12.statisticsAtText)
                assertIs<Rg12ExecutionResult.Accepted>(
                    store12.commit(correct.operation),
                    "rg12 correct_transaction_version",
                )

                // Every snapshot is captured after all commits: the captured set is then
                // exactly what a reopen loads.
                snapshot08 = store08.snapshot(fixture08.ledgerId)
                snapshot09 = store09.snapshot(fixture09.ledgerId)
                snapshot10 = store10.snapshot(fixture10.ledgerId)
                snapshot11 = store11.snapshot(LEDGER_A)
                snapshot12 = store12.snapshot(fixture12.ledgerId)

                // (a) The shared table holds a metadata row for every loaded transaction
                // and all five RGs contributed at least one row.
                val sharedLedgerA =
                    database.ledgerQueries
                        .selectFormalTransactionMetadata(LEDGER_A.value)
                        .executeAsList()
                        .map { it.transaction_id }
                        .toSet()
                val sharedLedger12 =
                    database.ledgerQueries
                        .selectFormalTransactionMetadata(LEDGER_12.value)
                        .executeAsList()
                        .map { it.transaction_id }
                        .toSet()
                assertTrue(
                    sharedLedgerA.containsAll(snapshot08.formalTransactions.map { it.formalTransaction.transaction.id.value }),
                )
                assertTrue(
                    sharedLedgerA.containsAll(snapshot09.formalTransactions.map { it.formalTransaction.transaction.id.value }),
                )
                assertTrue(
                    sharedLedgerA.containsAll(snapshot10.formalTransactions.map { it.formalTransaction.transaction.id.value }),
                )
                assertTrue(
                    sharedLedgerA.containsAll(snapshot11.formalTransactions.map { it.formalTransaction.transaction.id.value }),
                )
                assertTrue(
                    sharedLedger12.containsAll(snapshot12.formalTransactions.map { it.formalTransaction.transaction.id.value }),
                )
                assertTrue(sharedLedgerA.size + sharedLedger12.size >= 5, "shared rows >= 5")

                // (c) Each store's own committed metadata keeps the exact statistics time
                // text that was captured from memory before the commits: the shared table
                // is compared against the pre-commit in-memory expectations, so a
                // write-side folding or column bug cannot hide behind a read-back.
                expectedStatistics.forEach { (ledger, expected) ->
                    assertStatisticsAtText(database, ledger, expected)
                }
            }

            // Reopen without Schema.create: every store must reconstruct its snapshot
            // exactly from the shared tables.
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                val fixture08 = loadFixture08()
                val store08 = store08(database, driver, fixture08)
                assertPersistedSnapshotEquals08(snapshot08, store08.snapshot(fixture08.ledgerId))

                val fixture09 = loadFixture09()
                val store09 = store09(database, driver, fixture09)
                assertPersistedSnapshotEquals09(snapshot09, store09.snapshot(fixture09.ledgerId))

                val fixture10 = loadFixture10()
                val store10 = store10(database, driver, fixture10)
                assertPersistedSnapshotEquals10(snapshot10, store10.snapshot(fixture10.ledgerId))

                val store11 = SqlDelightRg11Store(database, driver, rg11Catalog())
                assertPersistedSnapshotEquals11(snapshot11, store11.snapshot(LEDGER_A))

                val fixture12 = loadFixture12()
                val store12 = store12(database, driver, fixture12, "root-correction")
                assertPersistedSnapshotEquals12(snapshot12, store12.snapshot(fixture12.ledgerId))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun assertStatisticsAtText(
        database: LedgerDatabase,
        ledger: LedgerId,
        expected: Map<String, String?>,
    ) {
        val shared =
            database.ledgerQueries
                .selectFormalTransactionMetadata(ledger.value)
                .executeAsList()
                .associate { it.transaction_id to it.statistics_at_text }
        expected.forEach { (transactionId, statisticsAtText) ->
            assertEquals(statisticsAtText, shared[transactionId], "shared statistics_at_text for $transactionId")
        }
    }

    private fun assertPersistedSnapshotEquals08(
        expected: Rg08Snapshot,
        actual: Rg08Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection08), actual.formalTransactions.map(::formalProjection08))
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

    private fun assertPersistedSnapshotEquals09(
        expected: Rg09Snapshot,
        actual: Rg09Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection09), actual.formalTransactions.map(::formalProjection09))
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

    private fun assertPersistedSnapshotEquals10(
        expected: Rg10Snapshot,
        actual: Rg10Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection10), actual.formalTransactions.map(::formalProjection10))
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

    private fun assertPersistedSnapshotEquals11(
        expected: Rg11Snapshot,
        actual: Rg11Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection11), actual.formalTransactions.map(::formalProjection11))
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

    private fun assertPersistedSnapshotEquals12(
        expected: Rg12Snapshot,
        actual: Rg12Snapshot,
    ) {
        assertEquals(expected.formalTransactions.map(::formalProjection12), actual.formalTransactions.map(::formalProjection12))
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

    private fun formalProjection08(record: com.unifiedledger.application.Rg08FormalTransactionRecord) =
        FormalRecordProjection(
            transaction = record.formalTransaction.transaction,
            versions = record.formalTransaction.versions,
            postingSets = record.formalTransaction.postingSets.map { PostingSetProjection(it.id, it.postings) },
            createdAt = record.createdAt,
            createdAtText = record.createdAtText,
            statisticsAtText = record.statisticsAtText,
        )

    private fun formalProjection09(record: com.unifiedledger.application.Rg09FormalTransactionRecord) =
        FormalRecordProjection(
            transaction = record.formalTransaction.transaction,
            versions = record.formalTransaction.versions,
            postingSets = record.formalTransaction.postingSets.map { PostingSetProjection(it.id, it.postings) },
            createdAt = record.createdAt,
            createdAtText = record.createdAtText,
            statisticsAtText = record.statisticsAtText,
        )

    private fun formalProjection10(record: com.unifiedledger.application.Rg10FormalTransactionRecord) =
        FormalRecordProjection(
            transaction = record.formalTransaction.transaction,
            versions = record.formalTransaction.versions,
            postingSets = record.formalTransaction.postingSets.map { PostingSetProjection(it.id, it.postings) },
            createdAt = record.createdAt,
            createdAtText = record.createdAtText,
            statisticsAtText = record.statisticsAtText,
        )

    private fun formalProjection11(record: com.unifiedledger.application.Rg11FormalTransactionRecord) =
        FormalRecordProjection11(
            transaction = record.formalTransaction.transaction,
            versions = record.formalTransaction.versions,
            postingSets = record.formalTransaction.postingSets.map { PostingSetProjection(it.id, it.postings) },
            createdAt = record.createdAt,
            createdAtText = record.createdAtText,
            statisticsAtText = record.statisticsAtText,
            versionConfirmationIds = record.versionConfirmationIds,
        )

    private fun formalProjection12(record: com.unifiedledger.application.Rg12FormalTransactionRecord) =
        FormalRecordProjection12(
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
    )

    private data class FormalRecordProjection11(
        val transaction: Transaction,
        val versions: List<TransactionVersion>,
        val postingSets: List<PostingSetProjection>,
        val createdAt: Instant,
        val createdAtText: String?,
        val statisticsAtText: String?,
        val versionConfirmationIds: Map<TransactionVersionId, String>,
    )

    private data class FormalRecordProjection12(
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

    private fun store08(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg08FixtureCase,
    ): SqlDelightRg08Store =
        SqlDelightRg08Store(
            database,
            driver,
            fixture.catalog,
            fixture.lendingCatalog,
            fixture.openingTransactions,
        )

    private fun store09(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg09FixtureCase,
    ): SqlDelightRg09Store =
        SqlDelightRg09Store(
            database,
            driver,
            fixture.catalog,
            fixture.openingTransactions,
        )

    private fun store10(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg10FixtureCase,
    ): SqlDelightRg10Store =
        SqlDelightRg10Store(
            database,
            driver,
            fixture.catalog,
            fixture.openingTransactions,
        )

    private fun store12(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        fixture: Rg12FixtureCase,
        rootId: String,
    ): SqlDelightRg12Store =
        SqlDelightRg12Store(
            database,
            driver,
            fixture.catalogs.getValue(rootId),
            fixture.baselines.getValue(rootId),
        )

    private fun rg11Catalog(): LedgerCatalog {
        val accounts =
            listOf(
                Account(PAYMENT_ACCOUNT, LEDGER_A, AccountKind.ASSET, CNY, ownedByUser = true, realAccount = true),
                Account(PREPAID_ACCOUNT, LEDGER_A, AccountKind.ASSET, CNY, ownedByUser = true, realAccount = false),
                Account(EXPENSE_ACCOUNT, LEDGER_A, AccountKind.EXPENSE, CNY, ownedByUser = false, realAccount = false),
            )
        val categories =
            listOf(
                Category(CATEGORY, LEDGER_A, parentId = CategoryId("root"), postingAccountId = EXPENSE_ACCOUNT, active = true),
            )
        return when (val created = LedgerCatalog.create(accounts, categories)) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> error("invalid test catalog")
        }
    }

    private fun createOperation11(): Rg11Operation.CreatePeriodicAllocation =
        Rg11Operation.CreatePeriodicAllocation(
            ledgerId = LEDGER_A,
            input =
                Rg11CreateInput(
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
            ids =
                Rg11CreateIds(
                    transactionId = TX_1_11,
                    versionId = TransactionVersionId("version-purchase-1"),
                    postingSetId = PostingSetId("posting-set-1"),
                    paymentPostingId = PostingId("payment-posting-1"),
                    prepaidPostingId = PostingId("prepaid-posting-1"),
                    scheduleId = "schedule-1",
                    revisionId = "revision-1",
                    installmentIds = listOf("installment-1", "installment-2", "installment-3"),
                ),
        )

    private fun loadFixture08(): Rg08FixtureCase =
        adaptRg08Fixture(
            Files.readString(repositoryFile("golden/rules/rg-08.json")),
            parseRg08FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg08-runtime-input.json"))),
        )

    private fun loadFixture09(): Rg09FixtureCase =
        adaptRg09Fixture(
            Files.readString(repositoryFile("golden/rules/rg-09.json")),
            parseRg09FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg09-runtime-input.json"))),
        )

    private fun loadFixture10(): Rg10FixtureCase =
        adaptRg10Fixture(
            Files.readString(repositoryFile("golden/rules/rg-10.json")),
            parseRg10FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg10-runtime-input.json"))),
        )

    private fun loadFixture12(): Rg12FixtureCase =
        adaptRg12Fixture(
            Files.readString(repositoryFile("golden/rules/rg-12.json")),
            parseRg12FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg12-runtime-input.json"))),
        )

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private fun sqliteProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
            setProperty("busy_timeout", "5000")
        }

    private companion object {
        val CNY = CurrencyUnit("CNY", 2)
        val LEDGER_A = LedgerId("ledger-a")
        val LEDGER_12 = LedgerId("ledger-rg-12")
        val PAYMENT_ACCOUNT = AccountId("asset-bank-a")
        val PREPAID_ACCOUNT = AccountId("prepaid-account-a")
        val EXPENSE_ACCOUNT = AccountId("expense-account-a")
        val CATEGORY = CategoryId("category-a")

        // 2026-01-31T00:00:00+08:00 is the month-end anchor of January 2026.
        val START_AT = Instant.parse("2026-01-30T16:00:00Z")
        val REQUEST_CREATE = RequestId("request-create-1")
        val TX_1_11 = TransactionId("transaction-purchase-1")
    }
}
