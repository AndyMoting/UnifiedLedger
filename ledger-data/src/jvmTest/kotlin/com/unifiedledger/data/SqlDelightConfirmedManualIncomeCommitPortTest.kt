package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import java.sql.SQLException
import kotlin.test.*
import kotlin.time.Instant

class SqlDelightConfirmedManualIncomeCommitPortTest {
    @Test
    fun `same snapshot replays while different snapshot conflicts without invoking callback`() {
        incomeHarness().use { harness ->
            val fixture = IncomePortFixture()
            assertIs<ConfirmedManualIncomeResult.Created>(harness.port.commitOnce(fixture.identity, fixture.snapshot) { DomainResult.Success(fixture.commit()) })
            var callbackCalls = 0
            assertIs<ConfirmedManualIncomeResult.NoChange>(harness.port.commitOnce(fixture.identity, fixture.snapshot) { callbackCalls++; error("must not create") })
            assertIs<ConfirmedManualIncomeResult.RequestIdentityConflict>(harness.port.commitOnce(fixture.identity, fixture.snapshot.copy(note = "different")) { callbackCalls++; error("must not create") })
            assertEquals(0, callbackCalls)
            assertEquals(listOf(1L, 1L, 1L, 1L, 2L), harness.counts())
        }
    }

    @Test
    fun `throwing callback and SQL failure roll back request and formal rows`() {
        incomeHarness().use { harness ->
            val fixture = IncomePortFixture()
            assertFailsWith<IllegalStateException> { harness.port.commitOnce(fixture.identity, fixture.snapshot) { error("callback failure") } }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), harness.counts())

            assertIs<ConfirmedManualIncomeResult.Created>(harness.port.commitOnce(fixture.identity, fixture.snapshot) { DomainResult.Success(fixture.commit()) })
            val colliding = IncomePortFixture(request = "request-b", suffix = "b", postingSuffix = "a")
            assertFailsWith<SQLException> { harness.port.commitOnce(colliding.identity, colliding.snapshot) { DomainResult.Success(colliding.commit()) } }
            assertEquals(listOf(1L, 1L, 1L, 1L, 2L), harness.counts())
        }
    }

    @Test
    fun `ledger scope violations fail before leaving any state`() {
        incomeHarness().use { harness ->
            val fixture = IncomePortFixture()
            assertFailsWith<IllegalArgumentException> { harness.port.commitOnce(fixture.identity.copy(ledgerId = LedgerId("other")), fixture.snapshot) { DomainResult.Success(fixture.commit()) } }
            assertFailsWith<IllegalArgumentException> { harness.port.commitOnce(fixture.identity, fixture.snapshot) { DomainResult.Success(fixture.commit(LedgerId("other"))) } }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), harness.counts())
        }
    }
}

private class IncomeHarness(val driver: JdbcSqliteDriver) : AutoCloseable {
    private val database = LedgerDatabase(driver)
    val port = SqlDelightConfirmedManualIncomeCommitPort(database, driver)
    fun counts() = listOf(database.ledgerQueries.countManualIncomeRequests().executeAsOne(), database.ledgerQueries.countIncomeReceipts().executeAsOne(), database.ledgerQueries.countTransactions().executeAsOne(), database.ledgerQueries.countVersions().executeAsOne(), database.ledgerQueries.countPostings().executeAsOne())
    override fun close() = driver.close()
}

private fun incomeHarness(): IncomeHarness {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, PropertiesForIncomeTest())
    LedgerDatabase.Schema.create(driver)
    return IncomeHarness(driver)
}

private fun PropertiesForIncomeTest() = java.util.Properties().apply { setProperty("foreign_keys", "true") }

private class IncomePortFixture(private val request: String = "request-a", private val suffix: String = "a", private val postingSuffix: String = suffix) {
    private val ledger = LedgerId("ledger-a")
    private val currency = CurrencyUnit("CNY", 2)
    val identity = ManualIncomeRequestIdentity(ledger, RequestId(request))
    val snapshot = ManualIncomeRequestSnapshot(ledger, Money.ofMinor(12_345, currency), CategoryId("income-category"), AccountId("asset"), Instant.parse("2026-01-01T00:00:00Z"), "")

    fun commit(commitLedger: LedgerId = ledger): ConfirmedManualIncomeCommit {
        val transactionId = TransactionId("income-$suffix"); val versionId = TransactionVersionId("income-$suffix-v1"); val setId = PostingSetId("income-set-$suffix")
        val set = assertIs<DomainResult.Success<PostingSet>>(PostingSet.create(setId, listOf(Posting(PostingId("asset-$postingSuffix"), AccountId("asset"), snapshot.amount), Posting(PostingId("income-$postingSuffix"), AccountId("income"), Money.ofMinor(-snapshot.amount.minorUnits, currency))))).value
        val formal = assertIs<DomainResult.Success<FormalTransaction>>(FormalTransaction.create(Transaction(transactionId, commitLedger, TransactionKind.INCOME, versionId), listOf(TransactionVersion(versionId, transactionId, 1, setId, TransactionTimes.collapsed(snapshot.occurredAt), snapshot.note)), listOf(set))).value
        return ConfirmedManualIncomeCommit(ConfirmationId("confirmation-$suffix"), formal)
    }
}
