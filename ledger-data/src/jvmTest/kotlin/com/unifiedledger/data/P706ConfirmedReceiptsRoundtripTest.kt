package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ConfirmedManualLendingCommit
import com.unifiedledger.application.ConfirmedManualLendingResult
import com.unifiedledger.application.ConfirmedManualTransferCommit
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.CounterpartyCommandResult
import com.unifiedledger.application.ManualIncomeRequestIdentity
import com.unifiedledger.application.ManualIncomeRequestSnapshot
import com.unifiedledger.application.ManualLendingBehavior
import com.unifiedledger.application.ManualLendingRequestIdentity
import com.unifiedledger.application.ManualLendingRequestSnapshot
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountTransfer
import com.unifiedledger.domain.AccountTransferPosting
import com.unifiedledger.domain.AccountTransferReportEffects
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingBehaviorCode
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.LendingPositionHistoryEntry
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
import com.unifiedledger.domain.createLendingPosition
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Restore round-trip evidence for the three manual-create confirmation receipts —
 * `confirmed_income_receipt` (Ledger.sq:170), `confirmed_transfer_receipt` (Ledger.sq:196) and
 * `confirmed_lending_receipt` (Ledger.sq:290) — written ONLY through the real claim-first commit
 * ports ([SqlDelightConfirmedManualIncomeCommitPort.commitOnce],
 * [SqlDelightConfirmedManualTransferCommitPort.commitOnce] and
 * [SqlDelightConfirmedManualLendingCommitPort.commitOnce]; the call shapes mirror each port's own
 * test). No receipt, request or formal row is hand-inserted.
 *
 * Registered scope limit (D-242 item 3, D-248 item 3): the three manual-create ports
 * UNCONDITIONALLY call `updateManual*RequestAnnotation` and `appendManualCreateAnnotation`, which
 * write the two `manual_*_request` annotation columns `34.sqm` adds and the 11 annotation tables
 * `33.sqm` creates. Those ports therefore cannot run against a v34/v33 source in the cross-leg
 * fixture ([P708RestoreRoundtripEquivalenceTest]), and this class deliberately seeds ONLY a fresh
 * current-schema (v35) library. What it provides is the CURRENT-SCHEMA restore round trip's
 * value-level evidence for the three receipt tables — snapshot the seeded library with
 * `VACUUM INTO`, reopen the snapshot, and read the rows back field by field (P708RestoreRoundtrip
 * EquivalenceTest.aV35SnapshotReadsBackValueIdenticalWithoutMigration's mechanism). It does NOT
 * provide cross-leg (v34/v33) evidence for these tables, and that dimension gap is disclosed here
 * rather than closed or papered over.
 *
 * The fixture stamps `PRAGMA user_version` after `LedgerDatabase.Schema.create` because create
 * does not write it (the desktop app stamps the version itself); without the stamp a snapshot's
 * authoritative version would read 0.
 */
class P706ConfirmedReceiptsRoundtripTest {
    private val ledgerId = "ledger-receipt-roundtrip"

    private val currency = CurrencyUnit("CNY", 2)

    /**
     * The three receipt tables this class covers, each with the identity chain its seeded port
     * commit wrote: request id, confirmation id and formal transaction id. Every column of every
     * table is a business column (the CREATE TABLEs declare exactly `ledger_id`, `request_id`,
     * `confirmation_id`, `transaction_id`), so the field-by-field read back below covers the whole
     * row shape.
     */
    private val seededReceipts =
        mapOf(
            "confirmed_income_receipt" to listOf("request-receipt-income", "confirmation-receipt-income", "tx-receipt-income"),
            "confirmed_transfer_receipt" to listOf("request-receipt-transfer", "confirmation-receipt-transfer", "tx-receipt-transfer"),
            "confirmed_lending_receipt" to listOf("request-receipt-lending", "confirmation-receipt-lending", "tx-receipt-lending"),
        )

    @Test
    fun aV35SnapshotCarriesTheThreeManualCreateReceiptsValueForValue() {
        val dir = tempDir()
        val source = File(dir, "source-receipts.db")
        try {
            seedCurrentLibrary(source.path)
            val snapshot = File(dir, "snapshot-receipts")
            JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceDriver ->
                runSnapshotIntoOn(sourceDriver, snapshot.path)
            }
            JdbcSqliteDriver("jdbc:sqlite:${snapshot.path}").use { snapshotDriver ->
                assertEquals(LedgerDatabase.Schema.version, authoritativeUserVersionOn(snapshotDriver))
                JdbcSqliteDriver("jdbc:sqlite:${source.path}").use { sourceRead ->
                    assertReceiptsReadBack(sourceRead)
                }
                assertReceiptsReadBack(snapshotDriver)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ seeding

    /**
     * Seeds one fresh v35 library with one confirmed commit per manual-create port: an ordinary
     * income, a fee-bearing transfer and a first lend against a real counterparty. Every commit
     * rides the port's own claim-first transaction, so each `confirmed_*_receipt` row is written
     * by the same transaction that claims the request and persists the formal rows.
     */
    private fun seedCurrentLibrary(path: String) {
        JdbcSqliteDriver("jdbc:sqlite:$path", foreignKeyProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = ${LedgerDatabase.Schema.version}", 0)

            val database = LedgerDatabase(driver)
            seedIncomeReceipt(database, driver)
            seedTransferReceipt(database, driver)
            seedLendingReceipt(database, driver)
        }
    }

    /** D-221 R3 precedent: a deterministic write-path annotation audit instant per commit. */
    private fun seedIncomeReceipt(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
    ) {
        val identity = ManualIncomeRequestIdentity(LedgerId(ledgerId), RequestId("request-receipt-income"))
        val snapshot =
            ManualIncomeRequestSnapshot(
                LedgerId(ledgerId),
                Money.ofMinor(6_800L, currency),
                CategoryId("income-leaf-roundtrip"),
                AccountId("asset-received-roundtrip"),
                Instant.parse("2026-03-01T00:00:00Z"),
                "",
            )
        assertIs<ConfirmedManualIncomeResult.Created>(
            SqlDelightConfirmedManualIncomeCommitPort(database, driver).commitOnce(
                identity,
                snapshot,
                Instant.parse("2026-03-01T00:05:00Z"),
            ) {
                val versionId = TransactionVersionId("tx-receipt-income-v1")
                val setId = PostingSetId("tx-receipt-income-set-1")
                val set =
                    assertIs<DomainResult.Success<PostingSet>>(
                        PostingSet.create(
                            setId,
                            listOf(
                                Posting(PostingId("tx-receipt-income-posting-received"), snapshot.receivingAccountId, snapshot.amount),
                                Posting(PostingId("tx-receipt-income-posting-income"), AccountId("income-received-roundtrip"), Money.ofMinor(-snapshot.amount.minorUnits, currency)),
                            ),
                        ),
                    ).value
                val formal =
                    assertIs<DomainResult.Success<FormalTransaction>>(
                        FormalTransaction.create(
                            Transaction(TransactionId("tx-receipt-income"), LedgerId(ledgerId), TransactionKind.INCOME, versionId),
                            listOf(TransactionVersion(versionId, TransactionId("tx-receipt-income"), 1, setId, TransactionTimes.collapsed(snapshot.occurredAt), snapshot.note)),
                            listOf(set),
                        ),
                    ).value
                DomainResult.Success(ConfirmedManualIncomeCommit(ConfirmationId("confirmation-receipt-income"), formal))
            },
        )
    }

    private fun seedTransferReceipt(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
    ) {
        val identity = ManualTransferRequestIdentity(LedgerId(ledgerId), RequestId("request-receipt-transfer"))
        val snapshot =
            ManualTransferRequestSnapshot(
                ledgerId = LedgerId(ledgerId),
                sourceAccountId = AccountId("asset-source-roundtrip"),
                destinationAccountId = AccountId("asset-destination-roundtrip"),
                destinationCredit = Money.ofMinor(10_000L, currency),
                fee = Money.ofMinor(200L, currency),
                feeCategoryId = CategoryId("fee-leaf-roundtrip"),
                occurredAt = Instant.parse("2026-03-02T00:30:00Z"),
                note = "roundtrip transfer",
            )
        assertIs<ConfirmedManualTransferResult.Created>(
            SqlDelightConfirmedManualTransferCommitPort(database, driver).commitOnce(
                identity,
                snapshot,
                Instant.parse("2026-03-02T00:35:00Z"),
            ) {
                val versionId = TransactionVersionId("tx-receipt-transfer-v1")
                val setId = PostingSetId("tx-receipt-transfer-set-1")
                val set =
                    assertIs<DomainResult.Success<PostingSet>>(
                        PostingSet.create(
                            setId,
                            listOf(
                                Posting(PostingId("tx-receipt-transfer-posting-out"), snapshot.sourceAccountId, Money.ofMinor(-10_200L, currency)),
                                Posting(PostingId("tx-receipt-transfer-posting-in"), snapshot.destinationAccountId, Money.ofMinor(10_000L, currency)),
                                Posting(PostingId("tx-receipt-transfer-posting-fee"), AccountId("expense-fee-roundtrip"), Money.ofMinor(200L, currency)),
                            ),
                        ),
                    ).value
                val formal =
                    assertIs<DomainResult.Success<FormalTransaction>>(
                        FormalTransaction.create(
                            Transaction(TransactionId("tx-receipt-transfer"), LedgerId(ledgerId), TransactionKind.ACCOUNT_TRANSFER, versionId),
                            listOf(TransactionVersion(versionId, TransactionId("tx-receipt-transfer"), 1, setId, TransactionTimes.collapsed(snapshot.occurredAt), snapshot.note)),
                            listOf(set),
                        ),
                    ).value
                val transfer =
                    AccountTransfer(
                        formalTransaction = formal,
                        postings =
                            listOf(
                                AccountTransferPosting(Posting(PostingId("tx-receipt-transfer-posting-out"), snapshot.sourceAccountId, Money.ofMinor(-10_200L, currency)), TransferPostingRole.PRINCIPAL_OUT),
                                AccountTransferPosting(Posting(PostingId("tx-receipt-transfer-posting-in"), snapshot.destinationAccountId, Money.ofMinor(10_000L, currency)), TransferPostingRole.PRINCIPAL_IN),
                                AccountTransferPosting(Posting(PostingId("tx-receipt-transfer-posting-fee"), AccountId("expense-fee-roundtrip"), Money.ofMinor(200L, currency)), TransferPostingRole.FEE, snapshot.feeCategoryId),
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
                DomainResult.Success(ConfirmedManualTransferCommit(ConfirmationId("confirmation-receipt-transfer"), transfer))
            },
        )
    }

    private fun seedLendingReceipt(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
    ) {
        val counterpartyId = CounterpartyId("cp-receipt-roundtrip")
        assertIs<CounterpartyCommandResult.Created>(
            SqlDelightCounterpartyStore(database, driver).create(
                ledgerId = LedgerId(ledgerId),
                counterpartyId = counterpartyId,
                name = "Receipt Roundtrip Counterparty",
                receivableAccountId = AccountId("receivable-receipt-roundtrip"),
                currency = currency,
            ),
        )
        val identity = ManualLendingRequestIdentity(LedgerId(ledgerId), RequestId("request-receipt-lending"))
        val snapshot =
            ManualLendingRequestSnapshot(
                ledgerId = LedgerId(ledgerId),
                behavior = ManualLendingBehavior.LEND,
                counterpartyId = counterpartyId,
                principalAccountId = AccountId("asset-bank-roundtrip"),
                amount = Money.ofMinor(10_000L, currency),
                interest = Money.ofMinor(0L, currency),
                fee = Money.ofMinor(0L, currency),
                totalReceived = null,
                interestCategoryId = null,
                occurredAt = Instant.parse("2026-03-03T00:00:00Z"),
                note = "",
            )
        assertIs<ConfirmedManualLendingResult.Created>(
            SqlDelightConfirmedManualLendingCommitPort(database, driver).commitOnce(
                identity,
                snapshot,
                Instant.parse("2026-03-03T00:05:00Z"),
            ) {
                val transactionId = TransactionId("tx-receipt-lending")
                val versionId = TransactionVersionId("tx-receipt-lending-v1")
                val setId = PostingSetId("tx-receipt-lending-set-1")
                val receivableAccountId = AccountId("receivable-receipt-roundtrip")
                val set =
                    assertIs<DomainResult.Success<PostingSet>>(
                        PostingSet.create(
                            setId,
                            listOf(
                                Posting(PostingId("tx-receipt-lending-posting-receivable"), receivableAccountId, snapshot.amount),
                                Posting(PostingId("tx-receipt-lending-posting-funding"), snapshot.principalAccountId, Money.ofMinor(-snapshot.amount.minorUnits, currency)),
                            ),
                        ),
                    ).value
                val formal =
                    assertIs<DomainResult.Success<FormalTransaction>>(
                        FormalTransaction.create(
                            Transaction(transactionId, LedgerId(ledgerId), TransactionKind.LEND, versionId),
                            listOf(TransactionVersion(versionId, transactionId, 1, setId, TransactionTimes.collapsed(snapshot.occurredAt), snapshot.note)),
                            listOf(set),
                        ),
                    ).value
                val position =
                    assertIs<DomainResult.Success<LendingPosition>>(
                        createLendingPosition(
                            id = "position-receipt-roundtrip",
                            counterpartyId = counterpartyId.value,
                            receivableAccountId = receivableAccountId,
                            currency = currency,
                            principalBalanceMinor = snapshot.amount.minorUnits,
                            history =
                                listOf(
                                    LendingPositionHistoryEntry(
                                        id = "tx-receipt-lending-entry",
                                        behaviorCode = LendingBehaviorCode.LEND,
                                        amountMinor = snapshot.amount.minorUnits,
                                        principalBalanceAfterMinor = snapshot.amount.minorUnits,
                                        transactionId = transactionId,
                                        occurredAt = snapshot.occurredAt,
                                    ),
                                ),
                        ),
                    ).value
                DomainResult.Success(ConfirmedManualLendingCommit(ConfirmationId("confirmation-receipt-lending"), formal, position))
            },
        )
    }

    // ------------------------------------------------------------------ read-back probes

    /**
     * Non-vacuity plus value guard for the three receipt tables on one file: each holds exactly
     * one row for the seeded ledger, and that row reads back FIELD BY FIELD the identity chain
     * its port commit minted (ledger, request, confirmation, formal transaction).
     */
    private fun assertReceiptsReadBack(driver: SqlDriver) {
        seededReceipts.forEach { (table, identity) ->
            val (requestId, confirmationId, transactionId) = identity
            assertEquals(
                1L,
                countOn(driver, "SELECT count(*) FROM $table WHERE ledger_id = '$ledgerId'"),
                "$table must hold exactly its seeded receipt row",
            )
            assertEquals(
                listOf(ledgerId, requestId, confirmationId, transactionId),
                singleRowOn(
                    driver,
                    4,
                    "SELECT ledger_id, request_id, confirmation_id, transaction_id FROM $table " +
                        "WHERE ledger_id = '$ledgerId' AND request_id = '$requestId'",
                ),
                "$table must read back the seeded receipt values field by field",
            )
        }
    }

    private fun authoritativeUserVersionOn(driver: SqlDriver): Long =
        driver
            .executeQuery(
                null,
                "PRAGMA user_version",
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    private fun singleRowOn(
        driver: SqlDriver,
        columns: Int,
        sql: String,
    ): List<String?> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(
                            buildList {
                                repeat(columns) { index -> add(cursor.getString(index)) }
                            },
                        )
                },
                0,
            ).value

    private fun countOn(
        driver: SqlDriver,
        sql: String,
    ): Long =
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

    private fun tempDir(): File {
        val directory = Files.createTempDirectory("p706-receipts-roundtrip").toFile()
        directory.deleteOnExit()
        return directory
    }

    private fun foreignKeyProperties(): java.util.Properties = java.util.Properties().apply { setProperty("foreign_keys", "true") }
}
