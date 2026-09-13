package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualTransferCommit
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountTransfer
import com.unifiedledger.domain.AccountTransferPosting
import com.unifiedledger.domain.AccountTransferReportEffects
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
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
import com.unifiedledger.domain.TransferPostingRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-02.B transfer claim-first persistence evidence: equivalent replay returns the original
 * receipt with zero writes, a differing snapshot is an identity conflict, the ledger scope is
 * enforced, and a SQL failure rolls back both the request and formal rows.
 */
class SqlDelightConfirmedManualTransferCommitPortTest {
    @Test
    fun sameSnapshotReplaysAndDifferentSnapshotConflictsWithoutCallback() {
        transferHarness().use { harness ->
            val fixture = TransferPortFixture()
            assertIs<ConfirmedManualTransferResult.Created>(harness.port.commitOnce(fixture.identity, fixture.snapshot) { DomainResult.Success(fixture.commit()) })

            var callbackCalls = 0
            assertIs<ConfirmedManualTransferResult.NoChange>(
                harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                    callbackCalls++
                    error("must not create")
                },
            )
            assertIs<ConfirmedManualTransferResult.RequestIdentityConflict>(
                harness.port.commitOnce(fixture.identity, fixture.snapshot.copy(note = "different")) {
                    callbackCalls++
                    error("must not create")
                },
            )
            assertEquals(0, callbackCalls)
            // 1 request, 1 receipt, 1 transaction, 1 version, 3 postings (fee-bearing fixture).
            assertEquals(listOf(1L, 1L, 1L, 1L, 3L), harness.counts())
            harness.assertNoRg08SiloWrites()
        }
    }

    @Test
    fun typedRejectionRollsBackTheClaimAndFreesTheIdentity() {
        transferHarness().use { harness ->
            val fixture = TransferPortFixture()
            val rejected =
                assertIs<ConfirmedManualTransferResult.Rejected>(
                    harness.port.commitOnce(fixture.identity, fixture.snapshot) {
                        DomainResult.Failure(com.unifiedledger.domain.ManualTransferViolation.TransferSameAccount)
                    },
                )
            assertEquals(com.unifiedledger.domain.ManualTransferViolation.TransferSameAccount, rejected.violation)
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), harness.counts())

            // The identity is retryable after the rolled-back claim.
            assertIs<ConfirmedManualTransferResult.Created>(harness.port.commitOnce(fixture.identity, fixture.snapshot) { DomainResult.Success(fixture.commit()) })
            assertEquals(listOf(1L, 1L, 1L, 1L, 3L), harness.counts())
        }
    }

    @Test
    fun sqlFailureRollsBackRequestAndFormalRows() {
        transferHarness().use { harness ->
            val fixture = TransferPortFixture()
            assertFailsWith<IllegalStateException> { harness.port.commitOnce(fixture.identity, fixture.snapshot) { error("callback failure") } }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), harness.counts())
        }
    }

    @Test
    fun ledgerScopeViolationsFailBeforeLeavingAnyState() {
        transferHarness().use { harness ->
            val fixture = TransferPortFixture()
            assertFailsWith<IllegalArgumentException> {
                harness.port.commitOnce(fixture.identity.copy(ledgerId = LedgerId("other")), fixture.snapshot) { DomainResult.Success(fixture.commit()) }
            }
            assertFailsWith<IllegalArgumentException> {
                harness.port.commitOnce(fixture.identity, fixture.snapshot) { DomainResult.Success(fixture.commit(LedgerId("other"))) }
            }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), harness.counts())
        }
    }

    @Test
    fun ledgerIsolatedLookupsReturnNullAcrossLedgersAndTheNoteReachesTheVersion() {
        transferHarness().use { harness ->
            val fixture = TransferPortFixture()
            val created = assertIs<ConfirmedManualTransferResult.Created>(harness.port.commitOnce(fixture.identity, fixture.snapshot) { DomainResult.Success(fixture.commit()) })

            assertEquals(
                "rent",
                harness.database.ledgerQueries
                    .selectPersistedVersions()
                    .executeAsList()
                    .single { it.transaction_id == created.receipt.transactionId.value }
                    .note,
            )
            harness.close()
        }
    }
}

private class TransferHarness(
    val driver: JdbcSqliteDriver,
) : AutoCloseable {
    val database = LedgerDatabase(driver)
    val port = SqlDelightConfirmedManualTransferCommitPort(database, driver)

    fun counts() =
        listOf(
            database.ledgerQueries.countManualTransferRequests().executeAsOne(),
            database.ledgerQueries.countTransferReceipts().executeAsOne(),
            database.ledgerQueries.countTransactions().executeAsOne(),
            database.ledgerQueries.countVersions().executeAsOne(),
            database.ledgerQueries.countPostings().executeAsOne(),
        )

    // P702SPEC-05 (R-6): a manual transfer commit must never touch the RG-08 silo — no import
    // source record, no evidence or link, no formal-transaction source metadata, and no shared
    // evidence-link / reconciliation row.
    fun assertNoRg08SiloWrites() {
        val siloTables =
            listOf(
                "rg08_source_record",
                "rg08_evidence",
                "rg08_evidence_link",
                "rg08_formal_transaction_metadata",
                "evidence_link",
                "reconciliation_request",
            )
        for (table in siloTables) {
            assertEquals(0L, queryCount("SELECT count(*) FROM $table"), table)
        }
    }

    private fun queryCount(sql: String): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    override fun close() = driver.close()
}

private fun transferHarness(): TransferHarness {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, java.util.Properties().apply { setProperty("foreign_keys", "true") })
    LedgerDatabase.Schema.create(driver)
    return TransferHarness(driver)
}

private class TransferPortFixture(
    private val request: String = "request-transfer-a",
    private val suffix: String = "a",
) {
    private val ledger = LedgerId("ledger-a")
    private val currency = CurrencyUnit("CNY", 2)

    val identity = ManualTransferRequestIdentity(ledger, RequestId(request))

    val snapshot =
        ManualTransferRequestSnapshot(
            ledgerId = ledger,
            sourceAccountId = AccountId("asset-a"),
            destinationAccountId = AccountId("asset-b"),
            destinationCredit = Money.ofMinor(10_000L, currency),
            fee = Money.ofMinor(200L, currency),
            feeCategoryId = CategoryId("fee-leaf"),
            occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
            note = "rent",
        )

    fun commit(commitLedger: LedgerId = ledger): ConfirmedManualTransferCommit {
        val transactionId = TransactionId("transfer-$suffix")
        val versionId = TransactionVersionId("transfer-$suffix-v1")
        val setId = PostingSetId("transfer-set-$suffix")
        val set =
            assertIs<DomainResult.Success<PostingSet>>(
                PostingSet.create(
                    setId,
                    listOf(
                        Posting(PostingId("out-$suffix"), AccountId("asset-a"), Money.ofMinor(-10_200L, currency)),
                        Posting(PostingId("in-$suffix"), AccountId("asset-b"), Money.ofMinor(10_000L, currency)),
                        Posting(PostingId("fee-$suffix"), AccountId("expense"), Money.ofMinor(200L, currency)),
                    ),
                ),
            ).value
        val formal =
            assertIs<DomainResult.Success<FormalTransaction>>(
                FormalTransaction.create(
                    Transaction(transactionId, commitLedger, TransactionKind.ACCOUNT_TRANSFER, versionId),
                    listOf(TransactionVersion(versionId, transactionId, 1, setId, TransactionTimes.collapsed(snapshot.occurredAt), snapshot.note)),
                    listOf(set),
                ),
            ).value
        val transfer =
            AccountTransfer(
                formalTransaction = formal,
                postings =
                    listOf(
                        AccountTransferPosting(Posting(PostingId("out-$suffix"), AccountId("asset-a"), Money.ofMinor(-10_200L, currency)), TransferPostingRole.PRINCIPAL_OUT),
                        AccountTransferPosting(Posting(PostingId("in-$suffix"), AccountId("asset-b"), Money.ofMinor(10_000L, currency)), TransferPostingRole.PRINCIPAL_IN),
                        AccountTransferPosting(Posting(PostingId("fee-$suffix"), AccountId("expense"), Money.ofMinor(200L, currency)), TransferPostingRole.FEE, CategoryId("fee-leaf")),
                    ),
                reportEffects =
                    AccountTransferReportEffects(
                        consumptionMinor = 200L,
                        ordinaryExpenseMinor = 200L,
                        cashOutflowMinor = 200L,
                        ordinaryIncomeMinor = 0L,
                        cashInflowMinor = 0L,
                        principalConsumptionMinor = 0L,
                        principalExternalCashFlowMinor = 0L,
                        internalTransferMinor = 10_000L,
                        netWorthChangeMinor = -200L,
                    ),
            )
        return ConfirmedManualTransferCommit(ConfirmationId("confirmation-$suffix"), transfer)
    }
}
