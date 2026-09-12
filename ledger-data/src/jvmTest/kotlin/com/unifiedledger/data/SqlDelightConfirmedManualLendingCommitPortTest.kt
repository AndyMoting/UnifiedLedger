package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualLendingCommit
import com.unifiedledger.application.ConfirmedManualLendingResult
import com.unifiedledger.application.CounterpartyCommandResult
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LendingPositionReadPort
import com.unifiedledger.application.ManualLendingBehavior
import com.unifiedledger.application.ManualLendingCommitResolution
import com.unifiedledger.application.ManualLendingFailureCode
import com.unifiedledger.application.ManualLendingRequestIdentity
import com.unifiedledger.application.ManualLendingRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualLendingCommitStatus
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingBehaviorCode
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.LendingPositionHistoryEntry
import com.unifiedledger.domain.ManualLendingViolation
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
import com.unifiedledger.domain.createLendingPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P7-02.C L-1..L-5 persistence evidence: the B02 synthetic example (100.00 + 20.00 lent, 40.00
 * principal + 5.00 interest collected -> remaining 80.00; a second object untouched), the B03
 * rejection family with zero writes rolling the claim back, B04 equivalent replay / identity
 * conflict / unknown recovery, and the frozen `(occurred_at, entry_id)` read order.
 */
class SqlDelightConfirmedManualLendingCommitPortTest {
    @Test
    fun b02SyntheticLendCollectExampleIsolatesObjectsByPrincipal() {
        LendingHarness().use { harness ->
            val alice = harness.createCounterparty("Alice")
            val bob = harness.createCounterparty("Bob")

            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(harness.lendRequest("req-1", alice), harness.lendSnapshot(alice, 10_000L, "2026-03-01T00:00:00Z")) {
                    DomainResult.Success(harness.lendCommit("tx-1", alice, 10_000L, 10_000L, "2026-03-01T00:00:00Z"))
                },
            )
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(harness.lendRequest("req-2", alice), harness.lendSnapshot(alice, 2_000L, "2026-03-02T00:00:00Z")) {
                    DomainResult.Success(harness.lendCommit("tx-2", alice, 2_000L, 12_000L, "2026-03-02T00:00:00Z"))
                },
            )
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(
                    harness.collectRequest("req-3", alice),
                    harness.collectSnapshot(alice, totalReceived = 4_500L, principal = 4_000L, interest = 500L, occurredAt = "2026-03-03T00:00:00Z"),
                ) { DomainResult.Success(harness.collectCommit("tx-3", alice, 4_500L, 4_000L, 500L, 8_000L, "2026-03-03T00:00:00Z")) },
            )
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(harness.lendRequest("req-4", bob), harness.lendSnapshot(bob, 7_000L, "2026-03-04T00:00:00Z")) {
                    DomainResult.Success(harness.lendCommit("tx-4", bob, 7_000L, 7_000L, "2026-03-04T00:00:00Z"))
                },
            )

            val alicePosition = checkNotNull(harness.positions.findPosition(harness.ledgerId, alice))
            val bobPosition = checkNotNull(harness.positions.findPosition(harness.ledgerId, bob))
            assertEquals(8_000L, alicePosition.principalBalanceMinor)
            assertEquals(7_000L, bobPosition.principalBalanceMinor)
            assertEquals(listOf(10_000L, 12_000L, 8_000L), alicePosition.history.map { it.principalBalanceAfterMinor })
            assertEquals(
                listOf(ManualLendingBehavior.LEND, ManualLendingBehavior.LEND, ManualLendingBehavior.COLLECT),
                alicePosition.history.map { it.behavior },
            )
            // Real received = 45.00 (40.00 principal + 5.00 interest) split, not stored as a balance.
            assertEquals(4_500L, harness.receivedTotalForRequest("req-3"))
        }
    }

    @Test
    fun b03RejectionFamilyRollsBackTheClaimAndWritesNothing() {
        LendingHarness().use { harness ->
            val alice = harness.createCounterparty("Alice")
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(harness.lendRequest("req-1", alice), harness.lendSnapshot(alice, 10_000L, "2026-03-01T00:00:00Z")) {
                    DomainResult.Success(harness.lendCommit("tx-1", alice, 10_000L, 10_000L, "2026-03-01T00:00:00Z"))
                },
            )
            val before = harness.counts()

            val over =
                harness.commit(harness.collectRequest("req-over", alice), harness.collectSnapshot(alice, 10_001L, 10_001L, 0L, "2026-03-02T00:00:00Z")) {
                    DomainResult.Failure(ManualLendingViolation.LendingPrincipalExceedsBalance)
                }
            assertEquals(ManualLendingFailureCode.LENDING_PRINCIPAL_EXCEEDS_BALANCE, failureCode(over))

            val fee =
                harness.commit(harness.collectRequest("req-fee", alice), harness.collectSnapshot(alice, 4_100L, 3_000L, 1_000L, "2026-03-02T00:00:00Z")) {
                    DomainResult.Failure(ManualLendingViolation.LendingFeeMustBeZero)
                }
            assertEquals(ManualLendingFailureCode.LENDING_FEE_MUST_BE_ZERO, failureCode(fee))

            val backdated =
                harness.commit(harness.lendRequest("req-backdated", alice), harness.lendSnapshot(alice, 1_000L, "2026-02-28T00:00:00Z")) {
                    DomainResult.Failure(ManualLendingViolation.LendingBackdatedNotAllowed)
                }
            assertEquals(ManualLendingFailureCode.LENDING_BACKDATED_NOT_ALLOWED, failureCode(backdated))

            assertEquals(before, harness.counts())

            // The rolled-back identity is retryable and the retry succeeds.
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(harness.lendRequest("req-backdated", alice), harness.lendSnapshot(alice, 1_000L, "2026-03-05T00:00:00Z")) {
                    DomainResult.Success(harness.lendCommit("tx-retry", alice, 1_000L, 11_000L, "2026-03-05T00:00:00Z"))
                },
            )
        }
    }

    @Test
    fun b04EquivalentReplayIdentityConflictAndUnknownRecovery() {
        LendingHarness().use { harness ->
            val alice = harness.createCounterparty("Alice")
            val snapshot = harness.lendSnapshot(alice, 5_000L, "2026-03-01T00:00:00Z")
            val identity = harness.lendRequest("req-1", alice)
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(identity, snapshot) { DomainResult.Success(harness.lendCommit("tx-1", alice, 5_000L, 5_000L, "2026-03-01T00:00:00Z")) },
            )
            var callback = 0
            assertIs<ConfirmedManualLendingResult.NoChange>(
                harness.commit(identity, snapshot) {
                    callback++
                    error("must not create")
                },
            )
            assertIs<ConfirmedManualLendingResult.RequestIdentityConflict>(
                harness.commit(identity, snapshot.copy(note = "different")) {
                    callback++
                    error("must not create")
                },
            )
            assertEquals(0, callback)

            val resolver = ResolveManualLendingCommitStatus(harness.readPort)
            assertIs<ManualLendingCommitResolution.MatchingReceipt>(resolver.resolve(harness.ledgerId, RequestId("req-1"), snapshot))
            assertIs<ManualLendingCommitResolution.SnapshotConflict>(
                resolver.resolve(harness.ledgerId, RequestId("req-1"), snapshot.copy(note = "different")),
            )
            assertIs<ManualLendingCommitResolution.Absent>(resolver.resolve(harness.ledgerId, RequestId("req-absent"), snapshot))
        }
    }

    @Test
    fun sameInstantHistoryReadsInOccurredAtThenEntryIdOrder() {
        LendingHarness().use { harness ->
            val alice = harness.createCounterparty("Alice")
            val instant = "2026-03-05T00:00:00Z"
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(harness.lendRequest("req-a", alice), harness.lendSnapshot(alice, 1_000L, instant)) {
                    DomainResult.Success(harness.lendCommit("tx-a", alice, 1_000L, 1_000L, instant, entryId = "entry-a"))
                },
            )
            assertIs<ConfirmedManualLendingResult.Created>(
                harness.commit(harness.lendRequest("req-b", alice), harness.lendSnapshot(alice, 2_000L, instant)) {
                    DomainResult.Success(harness.lendCommit("tx-b", alice, 2_000L, 3_000L, instant, entryId = "entry-b"))
                },
            )
            val position = checkNotNull(harness.positions.findPosition(harness.ledgerId, alice))
            assertEquals(listOf("entry-a", "entry-b"), position.history.map { it.entryId })
            assertEquals(listOf(1_000L, 3_000L), position.history.map { it.principalBalanceAfterMinor })
            assertNull(harness.readPort.findManualLendingByRequest(LedgerId("other"), RequestId("req-a")))
        }
    }

    private fun failureCode(result: ConfirmedManualLendingResult): ManualLendingFailureCode? = ManualLendingFailureCode.of(assertIs<ConfirmedManualLendingResult.Rejected>(result).violation)
}

private class LendingHarness : AutoCloseable {
    val driver: JdbcSqliteDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, java.util.Properties().apply { setProperty("foreign_keys", "true") }).also {
            LedgerDatabase.Schema.create(it)
        }
    val database = LedgerDatabase(driver)
    val ledgerId = LedgerId("ledger-c")
    val cny = CurrencyUnit("CNY", 2)
    val port = SqlDelightConfirmedManualLendingCommitPort(database, driver)
    private val directory = SqlDelightCounterpartyStore(database, driver)
    val positions: LendingPositionReadPort get() = directory
    val readPort: LedgerCurrentStateReadPort = SqlDelightLedgerCurrentStateReadAdapter(database)

    fun createCounterparty(name: String): CounterpartyId {
        val id = CounterpartyId("cp-${name.lowercase()}")
        val result =
            directory.create(
                ledgerId = ledgerId,
                counterpartyId = id,
                name = name,
                receivableAccountId = AccountId("recv-${name.lowercase()}"),
                currency = cny,
            )
        assertIs<CounterpartyCommandResult.Created>(result)
        return id
    }

    fun lendRequest(
        requestId: String,
        counterpartyId: CounterpartyId,
    ): ManualLendingRequestIdentity = ManualLendingRequestIdentity(ledgerId, RequestId(requestId))

    fun collectRequest(
        requestId: String,
        counterpartyId: CounterpartyId,
    ): ManualLendingRequestIdentity = ManualLendingRequestIdentity(ledgerId, RequestId(requestId))

    fun commit(
        identity: ManualLendingRequestIdentity,
        snapshot: ManualLendingRequestSnapshot,
        create: () -> DomainResult<ConfirmedManualLendingCommit>,
    ): ConfirmedManualLendingResult = port.commitOnce(identity, snapshot, create)

    fun lendSnapshot(
        counterpartyId: CounterpartyId,
        principalMinor: Long,
        occurredAt: String,
    ): ManualLendingRequestSnapshot =
        ManualLendingRequestSnapshot(
            ledgerId = ledgerId,
            behavior = ManualLendingBehavior.LEND,
            counterpartyId = counterpartyId,
            principalAccountId = AccountId("asset-bank"),
            amount = Money.ofMinor(principalMinor, cny),
            interest = Money.ofMinor(0L, cny),
            fee = Money.ofMinor(0L, cny),
            totalReceived = null,
            interestCategoryId = null,
            occurredAt = Instant.parse(occurredAt),
            note = "",
        )

    fun collectSnapshot(
        counterpartyId: CounterpartyId,
        totalReceived: Long,
        principal: Long,
        interest: Long,
        occurredAt: String,
    ): ManualLendingRequestSnapshot =
        ManualLendingRequestSnapshot(
            ledgerId = ledgerId,
            behavior = ManualLendingBehavior.COLLECT,
            counterpartyId = counterpartyId,
            principalAccountId = AccountId("asset-wallet"),
            amount = Money.ofMinor(principal, cny),
            interest = Money.ofMinor(interest, cny),
            fee = Money.ofMinor(0L, cny),
            totalReceived = Money.ofMinor(totalReceived, cny),
            interestCategoryId = CategoryId("income-interest"),
            occurredAt = Instant.parse(occurredAt),
            note = "",
        )

    fun lendCommit(
        transactionId: String,
        counterpartyId: CounterpartyId,
        principalMinor: Long,
        balanceAfterMinor: Long,
        occurredAt: String,
        entryId: String = "entry-$transactionId",
    ): ConfirmedManualLendingCommit =
        ConfirmedManualLendingCommit(
            confirmationId = ConfirmationId("confirmation-$transactionId"),
            transaction = lendFormal(transactionId, counterpartyId, principalMinor, occurredAt),
            position = rebuildPosition(counterpartyId, balanceAfterMinor, entryId, transactionId, principalMinor, LendingBehaviorCode.LEND, occurredAt),
        )

    fun collectCommit(
        transactionId: String,
        counterpartyId: CounterpartyId,
        totalReceived: Long,
        principalMinor: Long,
        interestMinor: Long,
        balanceAfterMinor: Long,
        occurredAt: String,
    ): ConfirmedManualLendingCommit {
        val tx = TransactionId(transactionId)
        val versionId = TransactionVersionId("$transactionId-v1")
        val setId = PostingSetId("$transactionId-set")
        val set =
            assertIs<DomainResult.Success<PostingSet>>(
                PostingSet.create(
                    setId,
                    listOf(
                        Posting(PostingId("$transactionId-dest"), AccountId("asset-wallet"), Money.ofMinor(totalReceived, cny)),
                        Posting(PostingId("$transactionId-principal"), receivableAccount(counterpartyId), Money.ofMinor(-principalMinor, cny)),
                        Posting(PostingId("$transactionId-interest"), AccountId("income-account"), Money.ofMinor(-interestMinor, cny)),
                    ),
                ),
            ).value
        val formal =
            assertIs<DomainResult.Success<FormalTransaction>>(
                FormalTransaction.create(
                    Transaction(tx, ledgerId, TransactionKind.COLLECT, versionId),
                    listOf(TransactionVersion(versionId, tx, 1, setId, TransactionTimes.collapsed(Instant.parse(occurredAt)), "")),
                    listOf(set),
                ),
            ).value
        return ConfirmedManualLendingCommit(
            confirmationId = ConfirmationId("confirmation-$transactionId"),
            transaction = formal,
            position = rebuildPosition(counterpartyId, balanceAfterMinor, "entry-$transactionId", transactionId, principalMinor, LendingBehaviorCode.COLLECT, occurredAt),
        )
    }

    private fun lendFormal(
        transactionId: String,
        counterpartyId: CounterpartyId,
        principal: Long,
        occurredAt: String,
    ): FormalTransaction {
        val tx = TransactionId(transactionId)
        val versionId = TransactionVersionId("$transactionId-v1")
        val setId = PostingSetId("$transactionId-set")
        val set =
            assertIs<DomainResult.Success<PostingSet>>(
                PostingSet.create(
                    setId,
                    listOf(
                        Posting(PostingId("$transactionId-recv"), receivableAccount(counterpartyId), Money.ofMinor(principal, cny)),
                        Posting(PostingId("$transactionId-fund"), AccountId("asset-bank"), Money.ofMinor(-principal, cny)),
                    ),
                ),
            ).value
        return assertIs<DomainResult.Success<FormalTransaction>>(
            FormalTransaction.create(
                Transaction(tx, ledgerId, TransactionKind.LEND, versionId),
                listOf(TransactionVersion(versionId, tx, 1, setId, TransactionTimes.collapsed(Instant.parse(occurredAt)), "")),
                listOf(set),
            ),
        ).value
    }

    private fun rebuildPosition(
        counterpartyId: CounterpartyId,
        balanceAfterMinor: Long,
        entryId: String,
        transactionId: String,
        principalMinor: Long,
        behavior: LendingBehaviorCode,
        occurredAt: String,
    ): LendingPosition {
        val prior =
            database.ledgerQueries.selectLendingPositionHistory(ledgerId.value, counterpartyId.value).executeAsList().map {
                LendingPositionHistoryEntry(
                    id = it.entry_id,
                    behaviorCode = if (it.behavior_code == "LEND") LendingBehaviorCode.LEND else LendingBehaviorCode.COLLECT,
                    amountMinor = if (it.behavior_code == "LEND") it.amount_minor else -it.amount_minor,
                    principalBalanceAfterMinor = it.principal_balance_after_minor,
                    transactionId = TransactionId(it.transaction_id),
                    occurredAt = Instant.parse(it.occurred_at),
                )
            }
        val entry =
            LendingPositionHistoryEntry(
                id = entryId,
                behaviorCode = behavior,
                amountMinor = if (behavior == LendingBehaviorCode.LEND) principalMinor else -principalMinor,
                principalBalanceAfterMinor = balanceAfterMinor,
                transactionId = TransactionId(transactionId),
                occurredAt = Instant.parse(occurredAt),
            )
        return assertIs<DomainResult.Success<LendingPosition>>(
            createLendingPosition(
                id = "position-${counterpartyId.value}",
                counterpartyId = counterpartyId.value,
                receivableAccountId = receivableAccount(counterpartyId),
                currency = cny,
                principalBalanceMinor = balanceAfterMinor,
                history = prior + entry,
            ),
        ).value
    }

    private fun receivableAccount(counterpartyId: CounterpartyId): AccountId = AccountId("recv-${counterpartyId.value.removePrefix("cp-")}")

    fun receivedTotalForRequest(requestId: String): Long? =
        database.ledgerQueries
            .manualLendingCommitByRequest(ledgerId.value, requestId)
            .executeAsOne()
            .total_received_minor

    fun counts(): List<Long> =
        listOf(
            database.ledgerQueries.countManualLendingRequests().executeAsOne(),
            database.ledgerQueries.countLendingReceipts().executeAsOne(),
            database.ledgerQueries.countTransactions().executeAsOne(),
            database.ledgerQueries.countVersions().executeAsOne(),
            database.ledgerQueries.countPostings().executeAsOne(),
        )

    override fun close() = driver.close()
}
