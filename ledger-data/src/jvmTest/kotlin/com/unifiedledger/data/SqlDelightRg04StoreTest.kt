package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg04ExecutionError
import com.unifiedledger.application.Rg04ExecutionResult
import com.unifiedledger.application.Rg04FundingSnapshot
import com.unifiedledger.application.Rg04ManualSnapshot
import com.unifiedledger.application.Rg04PreparedOperation
import com.unifiedledger.application.Rg04RepaymentSnapshot
import com.unifiedledger.application.Rg04SettlementSnapshot
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CreditPrincipalRepaymentIds
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MixedPaymentExpenseIds
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class SqlDelightRg04StoreTest {
    @Test fun storeRejectsUnconfirmedOperationsBeforeClaimOrReplay() {
        val fixture = fixture(null)
        val manualRejected =
            assertIs<Rg04ExecutionResult.Rejected>(
                fixture.store.commit(fixture.operation(fixture.snapshot.copy(confirmed = false))),
            )
        assertEquals(Rg04ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, manualRejected.error)
        assertEquals("explicit_confirmation", manualRejected.field)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), fixture.counts())

        assertIs<Rg04ExecutionResult.Accepted>(fixture.store.commit(fixture.operation()))
        val replayRejected =
            assertIs<Rg04ExecutionResult.Rejected>(
                fixture.store.commit(fixture.operation(fixture.snapshot.copy(confirmed = false))),
            )
        assertEquals(Rg04ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, replayRejected.error)
        assertEquals(listOf(1L, 1L, 1L, 3L, 2L), fixture.counts())
        fixture.driver.close()

        val repaymentFixture = fixture(null) { error("manual identity must not be requested") }
        val source =
            object : Rg04IdentitySource {
                override fun manual(requestId: RequestId): Rg04ManualCommitIds = error("unused")

                override fun repayment(requestId: RequestId) = Rg04RepaymentCommitIds("repayment-confirmation", listOf("repayment-rec-asset", "repayment-rec-liability"))
            }
        val repaymentStore = SqlDelightRg04Store(repaymentFixture.database, repaymentFixture.driver, catalog(), source)
        val repayment =
            Rg04PreparedOperation.Repayment(
                Rg04RepaymentSnapshot(
                    ledgerId,
                    RequestId("repayment-request"),
                    Instant.DISTANT_PAST,
                    "1970-01-01T00:00:00Z",
                    AccountId("asset"),
                    AccountId("liability"),
                    Money.ofMinor(5_000, currency),
                    false,
                ),
                CreditPrincipalRepaymentIds(
                    TransactionId("repayment-tx"),
                    TransactionVersionId("repayment-version"),
                    PostingSetId("repayment-set"),
                    PostingId("repayment-asset-post"),
                    PostingId("repayment-liability-post"),
                ),
            )
        val repaymentRejected = assertIs<Rg04ExecutionResult.Rejected>(repaymentStore.commit(repayment))
        assertEquals(Rg04ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, repaymentRejected.error)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), repaymentFixture.counts())
        repaymentFixture.driver.close()
    }

    @Test fun concurrentEquivalentRequestCreatesExactlyOnce() {
        val path = Files.createTempFile("rg04-concurrency-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"

        fun open(): Fixture {
            val driver =
                JdbcSqliteDriver(
                    url,
                    Properties().apply {
                        setProperty("foreign_keys", "true")
                        setProperty("busy_timeout", "5000")
                    },
                )
            val database = LedgerDatabase(driver)
            val catalog = assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(listOf(Account(AccountId("asset"), ledgerId, AccountKind.ASSET, currency, true, true), Account(AccountId("liability"), ledgerId, AccountKind.LIABILITY, currency, true, true), Account(AccountId("expense"), ledgerId, AccountKind.EXPENSE, currency, false, false)), listOf(Category(CategoryId("root"), ledgerId, null, null, true), Category(CategoryId("daily"), ledgerId, CategoryId("root"), AccountId("expense"), true)))).value
            val ids = MixedPaymentExpenseIds(TransactionId("tx"), TransactionVersionId("v"), PostingSetId("set"), PostingId("expense-post"), listOf(PostingId("asset-post"), PostingId("liability-post")))
            val source =
                object : Rg04IdentitySource {
                    override fun manual(requestId: RequestId) = Rg04ManualCommitIds("confirmation", listOf("rec-a", "rec-l"))

                    override fun repayment(requestId: RequestId): Rg04RepaymentCommitIds = error("unused")
                }
            val snapshot = Rg04ManualSnapshot(ledgerId, RequestId("request"), Instant.DISTANT_PAST, "1970-01-01T00:00:00Z", Money.ofMinor(12_000, currency), CategoryId("daily"), listOf(Rg04FundingSnapshot(AccountId("asset"), Money.ofMinor(7_000, currency)), Rg04FundingSnapshot(AccountId("liability"), Money.ofMinor(5_000, currency))), Rg04SettlementSnapshot(Money.ofMinor(13_500, currency), Money.ofMinor(1_500, currency), Money.ofMinor(12_000, currency)), true)
            return Fixture(driver, database, SqlDelightRg04Store(database, driver, catalog, source), snapshot, ids)
        }
        open().also {
            LedgerDatabase.Schema.create(it.driver)
            it.driver.close()
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                List(2) {
                    pool.submit<Rg04ExecutionResult> {
                        open().let { f ->
                            try {
                                f.store.commit(f.operation())
                            } finally {
                                f.driver.close()
                            }
                        }
                    }
                }
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is Rg04ExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg04ExecutionResult.NoChange })
            open().let {
                assertEquals(listOf(1L, 1L, 1L, 3L, 2L), it.counts())
                it.driver.close()
            }
        } finally {
            pool.shutdownNow()
            Files.deleteIfExists(path)
        }
    }

    @Test fun replayConflictAndEveryFailurePointAreAtomic() {
        Rg04FailurePoint.entries.forEach { point ->
            val fixture = fixture(point)
            assertFails { fixture.store.commit(fixture.operation()) }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), fixture.counts())
            fixture.driver.close()
        }
        val fixture = fixture(null)
        assertIs<Rg04ExecutionResult.Accepted>(fixture.store.commit(fixture.operation()))
        assertIs<Rg04ExecutionResult.NoChange>(fixture.store.commit(fixture.operation()))
        assertIs<Rg04ExecutionResult.RequestIdentityConflict>(
            fixture.store.commit(fixture.operation(fixture.snapshot.copy(currency = CurrencyUnit("USD", 2)))),
        )
        assertIs<Rg04ExecutionResult.RequestIdentityConflict>(fixture.store.commit(fixture.operation(fixture.snapshot.copy(total = Money.ofMinor(12_001, currency)))))
        val repaymentIds = CreditPrincipalRepaymentIds(TransactionId("rt"), TransactionVersionId("rv"), PostingSetId("rs"), PostingId("ra"), PostingId("rl"))
        assertIs<Rg04ExecutionResult.RequestIdentityConflict>(fixture.store.commit(Rg04PreparedOperation.Repayment(Rg04RepaymentSnapshot(ledgerId, fixture.snapshot.requestId, Instant.DISTANT_PAST, "x", AccountId("asset"), AccountId("liability"), Money.ofMinor(5_000, currency), true), repaymentIds)))
        assertEquals(listOf(1L, 1L, 1L, 3L, 2L), fixture.counts())
        assertEquals(
            mapOf(
                "expense-post" to "expense",
                "asset-post" to "mixed_expense_asset_funding",
                "liability-post" to "mixed_expense_credit_funding",
            ),
            fixture.database.ledgerQueries
                .selectRg04PostingSemantics()
                .executeAsList()
                .associate { it.posting_id to it.role },
        )
        fixture.driver.close()
    }

    @Test fun incompleteAndExcessReconciliationIdentitiesFailBeforePersistence() {
        listOf(1, 3).forEach { count ->
            val fixture =
                fixture(null) { requestId ->
                    Rg04ManualCommitIds("confirmation-${requestId.value}", List(count) { "rec-$it" })
                }
            assertFailsWith<IllegalArgumentException> { fixture.store.commit(fixture.operation()) }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), fixture.counts())
            fixture.driver.close()
        }

        listOf(1, 3).forEach { count ->
            val fixture = fixture(null) { error("manual identity must not be requested") }
            val source =
                object : Rg04IdentitySource {
                    override fun manual(requestId: RequestId): Rg04ManualCommitIds = error("unused")

                    override fun repayment(requestId: RequestId) = Rg04RepaymentCommitIds("repayment-confirmation", List(count) { "repayment-rec-$it" })
                }
            val store = SqlDelightRg04Store(fixture.database, fixture.driver, catalog(), source)
            val operation =
                Rg04PreparedOperation.Repayment(
                    Rg04RepaymentSnapshot(
                        ledgerId,
                        RequestId("repayment-request"),
                        Instant.DISTANT_PAST,
                        "1970-01-01T00:00:00Z",
                        AccountId("asset"),
                        AccountId("liability"),
                        Money.ofMinor(5_000, currency),
                        true,
                    ),
                    CreditPrincipalRepaymentIds(
                        TransactionId("repayment-tx"),
                        TransactionVersionId("repayment-version"),
                        PostingSetId("repayment-set"),
                        PostingId("repayment-asset-post"),
                        PostingId("repayment-liability-post"),
                    ),
                )
            assertFailsWith<IllegalArgumentException> { store.commit(operation) }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), fixture.counts())
            fixture.driver.close()
        }
    }

    private fun fixture(
        failure: Rg04FailurePoint?,
        manualIdentity: (RequestId) -> Rg04ManualCommitIds = {
            Rg04ManualCommitIds("confirmation", listOf("rec-a", "rec-l"))
        },
    ): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
        LedgerDatabase.Schema.create(driver)
        val database = LedgerDatabase(driver)
        val catalog = catalog()
        val ids = MixedPaymentExpenseIds(TransactionId("tx"), TransactionVersionId("v"), PostingSetId("set"), PostingId("expense-post"), listOf(PostingId("asset-post"), PostingId("liability-post")))
        val source =
            object : Rg04IdentitySource {
                override fun manual(requestId: RequestId) = manualIdentity(requestId)

                override fun repayment(requestId: RequestId): Rg04RepaymentCommitIds = error("unused")
            }
        val injector = Rg04FailureInjector { if (it == failure) error("injected") }
        val snapshot = Rg04ManualSnapshot(ledgerId, RequestId("request"), Instant.DISTANT_PAST, "1970-01-01T00:00:00Z", Money.ofMinor(12_000, currency), CategoryId("daily"), listOf(Rg04FundingSnapshot(AccountId("asset"), Money.ofMinor(7_000, currency)), Rg04FundingSnapshot(AccountId("liability"), Money.ofMinor(5_000, currency))), Rg04SettlementSnapshot(Money.ofMinor(13_500, currency), Money.ofMinor(1_500, currency), Money.ofMinor(12_000, currency)), true)
        return Fixture(driver, database, SqlDelightRg04Store(database, driver, catalog, source, injector), snapshot, ids)
    }

    private fun catalog() =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("asset"), ledgerId, AccountKind.ASSET, currency, true, true),
                    Account(AccountId("liability"), ledgerId, AccountKind.LIABILITY, currency, true, true),
                    Account(AccountId("expense"), ledgerId, AccountKind.EXPENSE, currency, false, false),
                ),
                listOf(
                    Category(CategoryId("root"), ledgerId, null, null, true),
                    Category(CategoryId("daily"), ledgerId, CategoryId("root"), AccountId("expense"), true),
                ),
            ),
        ).value

    private data class Fixture(
        val driver: JdbcSqliteDriver,
        val database: LedgerDatabase,
        val store: SqlDelightRg04Store,
        val snapshot: Rg04ManualSnapshot,
        val ids: MixedPaymentExpenseIds,
    ) {
        fun operation(value: Rg04ManualSnapshot = snapshot) = Rg04PreparedOperation.Manual(value, ids, "relation", "Mixed payment")

        fun counts() = listOf(database.ledgerQueries.countRg04OperationRequests().executeAsOne(), database.ledgerQueries.countRg04Receipts().executeAsOne(), database.ledgerQueries.countTransactions().executeAsOne(), database.ledgerQueries.countPostings().executeAsOne(), database.ledgerQueries.countRg04PostingReconciliations().executeAsOne())
    }

    companion object {
        val currency = CurrencyUnit("CNY", 2)
        val ledgerId = LedgerId("ledger-a")
    }
}
