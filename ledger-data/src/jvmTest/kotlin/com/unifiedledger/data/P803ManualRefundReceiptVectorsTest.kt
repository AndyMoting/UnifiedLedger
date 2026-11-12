package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ManualRefundReceiptCommitPort
import com.unifiedledger.application.ManualRefundReceiptIdSource
import com.unifiedledger.application.ManualRefundReceiptIds
import com.unifiedledger.application.ManualRefundReceiptReadPort
import com.unifiedledger.application.ManualRefundReceiptReadRecord
import com.unifiedledger.application.ManualRefundReceiptRequest
import com.unifiedledger.application.ManualRefundReceiptSubmissionResult
import com.unifiedledger.application.ManualRefundRejectionCode
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualRefundReceiptCommitStatus
import com.unifiedledger.application.SubmitManualRefundReceipt
import com.unifiedledger.application.TransactionVoidPlan
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.TransactionVoidRequestSnapshot
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.P705FailureCode
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.TransactionVoidFactKind
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import java.nio.file.Files
import java.sql.SQLException
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P8-03 i1 (D-312; spec sections 2.3-2.6/5) data+application vectors of the manual refund entry,
 * exercised against the real JVM SQLite driver. Every test name carries its acceptance vector
 * number from the approved spec.
 */
class P803ManualRefundReceiptVectorsTest {
    @Test
    fun v2CandidateQueryAppliesTheSevenConditionsAndExcludesLegacyExpenseKinds() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)

            // Once the valid original exists, add one row per excluded shape: the five-value
            // legacy kind always says 'EXPENSE' for all of these, so only the canonical kind and
            // the single-positive-expense-leg rule may exclude them.
            seedTransaction(harness.driver, "tx-refund-like", "REFUND_RECEIPT", listOf(DAILY to 5_000L, ASSET to 5_000L))
            seedTransaction(harness.driver, "tx-lend-like", "LEND", listOf(DAILY to 6_000L, ASSET to 6_000L))
            seedTransaction(harness.driver, "tx-collect-like", "COLLECT", listOf(DAILY to 7_000L, ASSET to 7_000L))
            seedTransaction(harness.driver, "tx-adjustment-like", "BALANCE_ADJUSTMENT", listOf(DAILY to 8_000L, ASSET to 8_000L))
            seedTransaction(harness.driver, "tx-multi-leg", null, listOf(DAILY to 1_000L, EXPENSE_OTHER to 2_000L))
            seedTransaction(harness.driver, "tx-usd", null, listOf(DAILY to 9_000L, ASSET to 9_000L), currency = "USD")
            seedTransaction(harness.driver, "tx-voided", null, listOf(DAILY to 10_000L, ASSET to 10_000L))
            insertVoidFact(harness.driver, "tx-voided", "request-voided", "void", sequence = 1L)

            val candidates =
                harness.database.ledgerQueries
                    .selectManualRefundExpenseCandidates(ledger_id = LEDGER, page_size = 20L, page_offset = 0L) {
                            transactionId,
                            occurredAt,
                            categoryId,
                            structuralCapMinor,
                            currencyCode,
                            currencyPrecision,
                            remainingMinor,
                            ->
                        Candidate(transactionId, occurredAt, categoryId, structuralCapMinor, currencyCode, currencyPrecision, remainingMinor)
                    }.executeAsList()

            assertEquals(listOf("tx-original"), candidates.map { it.transactionId })
            assertEquals(12_000L, candidates.single().structuralCapMinor)
            assertEquals(12_000L, candidates.single().remainingMinor)
            assertEquals(CATEGORY_DAILY, candidates.single().categoryId)
        }
    }

    @Test
    fun v4CapIsTheExpenseLegAmountAndAVoidedRefundReleasesIt() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val submit = harness.useCase

            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                submit.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )
            assertEquals(9_000L, remainingOf(harness))

            // 100.00 is below the 120.00 structural cap but above the 90.00 remaining capacity.
            assertEquals(
                ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING,
                rejectionOf(submit.submit(request(amountMinor = 10_000L, requestId = "request-refund-2"))),
            )
            // 130.00 exceeds the structural cap itself.
            assertEquals(
                ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP,
                rejectionOf(submit.submit(request(amountMinor = 13_000L, requestId = "request-refund-3"))),
            )
            assertEquals(9_000L, remainingOf(harness))

            // Voiding the created refund releases its amount from the cap again (M4).
            insertVoidFact(harness.driver, "tx-refund-1", "request-void-refund-1", "void", sequence = 1L)
            assertEquals(12_000L, remainingOf(harness))
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                submit.submit(request(amountMinor = 11_000L, requestId = "request-refund-4")),
            )
            assertEquals(1_000L, remainingOf(harness))
        }
    }

    @Test
    fun v5EveryRejectionPathLeavesZeroRelationshipRowsAndZeroFormalWrites() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val before = countsOf(harness)

            assertEquals(
                ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP,
                rejectionOf(harness.useCase.submit(request(amountMinor = 13_000L, requestId = "request-rejected"))),
            )
            assertEquals(before, countsOf(harness))
            assertEquals(0L, queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"))
        }
    }

    @Test
    fun v6TheRefundTransactionIsSeparateAndLeavesTheOriginalTransactionIntact() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )

            assertEquals(
                "REFUND_RECEIPT",
                scalarString(harness.driver, "SELECT COALESCE(canonical_kind, kind) FROM ledger_transaction WHERE ledger_id='$LEDGER' AND transaction_id='tx-refund-1'"),
            )
            assertEquals("EXPENSE", scalarString(harness.driver, "SELECT kind FROM ledger_transaction WHERE transaction_id='tx-refund-1'"))
            assertEquals(
                "version-refund-1",
                scalarString(harness.driver, "SELECT current_version_id FROM ledger_transaction_current_version WHERE transaction_id='tx-refund-1'"),
            )
            assertEquals(
                listOf(
                    "posting-refund-dest-1|$ASSET|3000",
                    "posting-refund-expense-1|$DAILY|-3000",
                ),
                postingRows(harness.driver, "ps-refund-1"),
            )

            // The original transaction's row, version, date and postings are untouched.
            assertEquals(
                "2026-01-10T02:00:00Z",
                scalarString(
                    harness.driver,
                    "SELECT version.occurred_at FROM transaction_version AS version JOIN ledger_transaction_current_version AS current_version ON current_version.current_version_id = version.version_id WHERE version.transaction_id = 'tx-original'",
                ),
            )
            assertEquals(
                listOf("posting-original-expense|$DAILY|12000", "posting-original-asset|$ASSET|-12000"),
                postingRows(harness.driver, "ps-original"),
            )
            assertEquals(0L, queryCount(harness.driver, "SELECT count(*) FROM transaction_void_fact WHERE transaction_id = 'tx-original'"))
            // Spec 2.4: the server re-resolved the inherited secondary expense category.
            assertEquals(
                "$CATEGORY_DAILY|3000|CNY|2",
                scalarString(
                    harness.driver,
                    "SELECT category_id || '|' || amount_minor || '|' || currency_code || '|' || currency_precision FROM manual_refund_receipt WHERE ledger_id='$LEDGER' AND request_id='request-refund-1'",
                ),
            )
        }
    }

    @Test
    fun v16TheDestinationLegStartsPendingAndTheOriginalPaymentLegKeepsItsReconciliationState() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            // The original payment leg is already fully reconciled before the refund arrives.
            execute(
                harness.driver,
                "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) " +
                    "VALUES ('$LEDGER','reconciliation-original-asset','posting-original-asset','CHECKED',1)",
            )
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )
            // The refund's destination leg carries its own pending reconciliation row, so the
            // existing evidence-link flow can bind evidence to that exact posting independently.
            assertEquals(
                "posting-refund-dest-1|PENDING|1",
                scalarString(
                    harness.driver,
                    "SELECT posting_id || '|' || status || '|' || latest_sequence FROM posting_reconciliation WHERE ledger_id='$LEDGER' AND posting_id='posting-refund-dest-1'",
                ),
            )
            // The original payment leg's reconciliation state is never reset or rewritten.
            assertEquals(
                "CHECKED|1",
                scalarString(
                    harness.driver,
                    "SELECT status || '|' || latest_sequence FROM posting_reconciliation WHERE ledger_id='$LEDGER' AND posting_id='posting-original-asset'",
                ),
            )
            // The refund's expense leg is not a real-account posting and gets no reconciliation.
            assertEquals(
                0L,
                queryCount(
                    harness.driver,
                    "SELECT count(*) FROM posting_reconciliation WHERE posting_id IN ('posting-refund-expense-1', 'posting-original-expense')",
                ),
            )
        }
    }

    @Test
    fun v7EquivalentReplayIsFreeAndAFingerprintConflictWritesNothing() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val created = assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )
            val afterFirst = countsOf(harness)

            val replay = harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1"))
            assertEquals(ManualRefundReceiptSubmissionResult.Replayed(created.receipt), replay)
            assertEquals(afterFirst, countsOf(harness))

            val conflict = harness.useCase.submit(request(amountMinor = 3_500L, requestId = "request-refund-1"))
            assertEquals(ManualRefundRejectionCode.REFUND_REQUEST_CONFLICT, rejectionOf(conflict))
            assertEquals(afterFirst, countsOf(harness))

            // UNIQUE (ledger_id, refund_transaction_id) is the second safety net.
            assertFailsWith<SQLException> {
                execute(
                    harness.driver,
                    "INSERT INTO manual_refund_receipt(ledger_id, relationship_id, request_id, request_fingerprint, original_transaction_id, refund_transaction_id, amount_minor, currency_code, currency_precision, destination_account_id, category_id, arrived_at, confirmed_at, created_at, confirmation_marker) " +
                        "VALUES ('$LEDGER','relationship-dup','request-dup','fp','tx-original','tx-refund-1',100,'CNY',2,'$ASSET','$CATEGORY_DAILY','2026-02-02T02:00:00Z','2026-02-02T02:00:05Z','2026-02-02T02:00:05Z','explicit_manual_confirm')",
                )
            }
        }
    }

    @Test
    fun v8ReportArithmeticFollowsTheRulesExampleAndNeverBooksOrdinaryIncome() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(
                    request(
                        amountMinor = 3_000L,
                        requestId = "request-refund-1",
                        arrivedAt = Instant.parse("2026-02-02T02:00:00Z"),
                    ),
                ),
            )

            // Cumulative consumption through the expense accounts: +120.00 then -30.00 = 90.00.
            assertEquals(
                9_000L,
                queryCount(
                    harness.driver,
                    "SELECT COALESCE(sum(posting.amount_minor), 0) FROM posting JOIN transaction_version AS version ON version.ledger_id = posting.ledger_id AND version.posting_set_id = posting.posting_set_id WHERE posting.account_id = '$DAILY'",
                ),
            )
            // The refund's own contribution to the expense account is -30.00.
            assertEquals(
                -3_000L,
                queryCount(
                    harness.driver,
                    "SELECT COALESCE(sum(posting.amount_minor), 0) FROM posting WHERE posting_id = 'posting-refund-expense-1'",
                ),
            )
            // Cash inflow of the refund period is +30.00 on the destination asset account.
            assertEquals(
                3_000L,
                queryCount(
                    harness.driver,
                    "SELECT COALESCE(sum(posting.amount_minor), 0) FROM posting WHERE posting_id = 'posting-refund-dest-1'",
                ),
            )
            // `:144`: a refund never becomes ordinary income (no INCOME-kind account is posted).
            assertEquals(
                0L,
                queryCount(
                    harness.driver,
                    "SELECT count(*) FROM posting JOIN catalog_account ON catalog_account.ledger_id = posting.ledger_id AND catalog_account.account_id = posting.account_id WHERE catalog_account.kind = 'INCOME'",
                ),
            )
        }
    }

    @Test
    fun v9ReopenReadsTheRelationshipBackAndTheImmutabilityTriggerRejectsUpdates() {
        val path = Files.createTempFile("p803-refund-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                seedRefundWorld(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightManualRefundReceiptStore(database, driver)
                val useCase =
                    SubmitManualRefundReceipt(store, SequentialIdSource(), ResolveManualRefundReceiptCommitStatus(store))
                assertIs<ManualRefundReceiptSubmissionResult.Created>(
                    useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
                )
            }
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightManualRefundReceiptStore(database, driver)
                val record = store.findByRequest(LedgerId(LEDGER), RequestId("request-refund-1"))
                assertEquals("relationship-refund-1", record?.receipt?.relationshipId)
                assertEquals("tx-refund-1", record?.receipt?.refundTransactionId?.value)
                assertFailsWith<SQLException> {
                    execute(
                        driver,
                        "UPDATE manual_refund_receipt SET amount_minor = 1 WHERE ledger_id = '$LEDGER' AND request_id = 'request-refund-1'",
                    )
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun v12SubmissionRechecksTheOriginalInsideTheWriteTransaction() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            insertVoidFact(harness.driver, "tx-original", "request-void-original", "void", sequence = 1L)
            assertEquals(
                ManualRefundRejectionCode.ORIGINAL_NO_LONGER_REFUNDABLE,
                rejectionOf(harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1"))),
            )
            assertEquals(0L, queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"))

            assertEquals(
                ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE,
                rejectionOf(harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-2", original = "tx-missing"))),
            )
            assertEquals(0L, queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"))
        }
    }

    @Test
    fun v12VersionReplacementThatBreaksTheSingleExpenseLegRejectsWithZeroWrites() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            // The picked original gets a second current version with two positive expense legs.
            execute(harness.driver, "INSERT INTO posting_set VALUES ('ps-original-v2', '$LEDGER')")
            execute(
                harness.driver,
                "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-original-v2','tx-original','$LEDGER',2,'ps-original-v2','2026-01-10T02:00:00Z','2026-01-10T02:00:00Z','2026-01-10T02:00:00Z','replaced')",
            )
            execute(
                harness.driver,
                "INSERT INTO posting VALUES ('posting-original-v2-expense-1','ps-original-v2','$LEDGER',0,'$DAILY',6000,'CNY',2)",
            )
            execute(
                harness.driver,
                "INSERT INTO posting VALUES ('posting-original-v2-expense-2','ps-original-v2','$LEDGER',1,'$EXPENSE_OTHER',6000,'CNY',2)",
            )
            execute(
                harness.driver,
                "UPDATE ledger_transaction_current_version SET current_version_id = 'version-original-v2' WHERE transaction_id = 'tx-original'",
            )
            assertEquals(
                ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE,
                rejectionOf(harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1"))),
            )
            assertEquals(0L, queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"))
        }
    }

    @Test
    fun v17AfterCommitReadFailureRecoversOnlyOnAnExactFingerprintAndRetriesTheSameId() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val failingAfterCommit =
                ManualRefundReceiptCommitPort { request, fingerprint, mintIds ->
                    harness.store.commitOnce(request, fingerprint, mintIds)
                    error("simulated failure after the commit boundary")
                }
            val useCase =
                SubmitManualRefundReceipt(
                    failingAfterCommit,
                    SequentialIdSource(),
                    ResolveManualRefundReceiptCommitStatus(harness.store),
                )

            val recovered = useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1"))
            val receipt = assertIs<ManualRefundReceiptSubmissionResult.Recovered>(recovered).receipt
            assertEquals("tx-refund-1", receipt.refundTransactionId.value)
            assertEquals(1L, queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"))

            // The same request id retried through the normal path is an equivalent replay.
            val replay = harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1"))
            assertEquals(ManualRefundReceiptSubmissionResult.Replayed(receipt), replay)
            assertEquals(1L, queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"))
        }
    }

    @Test
    fun v17AThrowingReadBackAfterAnUncertainCommitIsUnknownCommit() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val failingAfterCommit =
                ManualRefundReceiptCommitPort { request, fingerprint, mintIds ->
                    harness.store.commitOnce(request, fingerprint, mintIds)
                    error("simulated uncertain commit")
                }
            val failingRead =
                object : ManualRefundReceiptReadPort {
                    override fun findByRequest(
                        ledgerId: LedgerId,
                        requestId: RequestId,
                    ): ManualRefundReceiptReadRecord = error("simulated post-commit read failure")
                }
            val useCase =
                SubmitManualRefundReceipt(
                    failingAfterCommit,
                    SequentialIdSource(),
                    ResolveManualRefundReceiptCommitStatus(failingRead),
                )
            assertEquals(
                ManualRefundReceiptSubmissionResult.UnknownCommit,
                useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )
            // The write is not retried: exactly one row survives the unknown outcome.
            assertEquals(1L, queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"))
        }
    }

    @Test
    fun v19TheVoidGuardRejectsAnOriginalWithAnEffectiveManualRefund() {
        DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val created = assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )
            val voidPort = SqlDelightTransactionVoidCommitPort(harness.database, harness.driver)
            var planCalls = 0
            val blocked =
                voidPort.commitOnce(
                    TransactionVoidRequestIdentity(LedgerId(LEDGER), RequestId("request-void-original")),
                    TransactionVoidRequestSnapshot(
                        ledgerId = LedgerId(LEDGER),
                        transactionId = TransactionId("tx-original"),
                        factKind = TransactionVoidFactKind.VOID,
                        reason = VoidReason(VoidReasonCode.MIS_ENTERED),
                    ),
                ) {
                    planCalls += 1
                    TransactionVoidPlan.Rejected(P705FailureCode.P705_CONSTRAINT_VIOLATION)
                }
            assertEquals(P705FailureCode.P705_REFUND_LINKED_VOID_NOT_SUPPORTED, assertIs<VoidTransactionResult.Rejected>(blocked).code)
            assertEquals(0, planCalls)

            // Once the refund itself is voided, the effective-linked-refund probe releases the
            // original again (DP-13 asks for an *effective* linked refund).
            insertVoidFact(harness.driver, created.receipt.refundTransactionId.value, "request-void-refund", "void", sequence = 1L)
            assertEquals(0L, queryCount(harness.driver, effectiveLinkedRefundProbe()))
        }
    }
}

private class DatabaseHarness(
    val driver: JdbcSqliteDriver,
) : AutoCloseable {
    val database = LedgerDatabase(driver)
    val store = SqlDelightManualRefundReceiptStore(database, driver)
    val useCase = SubmitManualRefundReceipt(store, SequentialIdSource(), ResolveManualRefundReceiptCommitStatus(store))

    override fun close() {
        driver.close()
    }

    companion object {
        fun inMemory(): DatabaseHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            return DatabaseHarness(driver)
        }
    }
}

private class SequentialIdSource : ManualRefundReceiptIdSource {
    private var sequence = 0

    override fun next(): ManualRefundReceiptIds {
        sequence += 1
        return ManualRefundReceiptIds(
            relationshipId = "relationship-refund-$sequence",
            transactionId = TransactionId("tx-refund-$sequence"),
            versionId = TransactionVersionId("version-refund-$sequence"),
            postingSetId = PostingSetId("ps-refund-$sequence"),
            destinationPostingId = PostingId("posting-refund-dest-$sequence"),
            expensePostingId = PostingId("posting-refund-expense-$sequence"),
        )
    }
}

private data class Candidate(
    val transactionId: String,
    val occurredAt: String,
    val categoryId: String,
    val structuralCapMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val remainingMinor: Long,
)

private const val LEDGER = "ledger-a"
private const val ORIGINAL = "tx-original"
private const val DAILY = "account-expense-daily"
private const val CATEGORY_DAILY = "category-daily"
private const val EXPENSE_OTHER = "account-expense-other"
private const val ASSET = "account-asset-a"

private fun seedRefundWorld(driver: JdbcSqliteDriver) {
    execute(
        driver,
        "INSERT INTO catalog_version VALUES ('$LEDGER', 1)",
    )
    execute(driver, "INSERT INTO catalog_account VALUES ('$LEDGER','$DAILY','daily expense','EXPENSE','CNY',2,0,0,NULL,1,1)")
    execute(driver, "INSERT INTO catalog_account VALUES ('$LEDGER','$EXPENSE_OTHER','other expense','EXPENSE','CNY',2,0,0,NULL,1,1)")
    execute(driver, "INSERT INTO catalog_account VALUES ('$LEDGER','$ASSET','asset a','ASSET','CNY',2,1,1,NULL,0,1)")
    execute(driver, "INSERT INTO catalog_category VALUES ('$LEDGER','category-root','EXPENSE',NULL,NULL,1)")
    execute(driver, "INSERT INTO catalog_category VALUES ('$LEDGER','$CATEGORY_DAILY','EXPENSE','category-root','$DAILY',1)")
    execute(driver, "INSERT INTO catalog_category VALUES ('$LEDGER','category-other','EXPENSE','category-root','$EXPENSE_OTHER',1)")
    seedTransaction(driver, ORIGINAL, null, listOf(DAILY to 12_000L, ASSET to -12_000L), occurredAt = "2026-01-10T02:00:00Z")
}

private fun seedTransaction(
    driver: JdbcSqliteDriver,
    transactionId: String,
    canonicalKind: String?,
    legs: List<Pair<String, Long>>,
    currency: String = "CNY",
    occurredAt: String = "2026-01-20T02:00:00Z",
) {
    execute(
        driver,
        "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('$transactionId', '$LEDGER', 'EXPENSE', ${canonicalKind?.let { "'$it'" } ?: "NULL"})",
    )
    execute(driver, "INSERT INTO posting_set VALUES ('ps-$transactionId', '$LEDGER')")
    execute(
        driver,
        "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-$transactionId', '$transactionId', '$LEDGER', 1, 'ps-$transactionId', '$occurredAt', '$occurredAt', '$occurredAt', NULL)",
    )
    execute(driver, "INSERT INTO ledger_transaction_current_version VALUES ('$transactionId', '$LEDGER', 'version-$transactionId')")
    legs.forEachIndexed { index, (account, amount) ->
        execute(
            driver,
            "INSERT INTO posting VALUES ('posting-$transactionId-$index', 'ps-$transactionId', '$LEDGER', $index, '$account', $amount, '$currency', 2)",
        )
    }
}

private fun insertVoidFact(
    driver: JdbcSqliteDriver,
    transactionId: String,
    requestId: String,
    factKind: String,
    sequence: Long,
) {
    if (sequence == 1L) {
        execute(
            driver,
            "INSERT INTO transaction_void_request(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) VALUES ('$LEDGER','$requestId','$transactionId','void','mis_entered',NULL,'explicit_manual_save')",
        )
    }
    execute(
        driver,
        "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) VALUES ('$LEDGER','$transactionId',$sequence,'fact-$requestId','$factKind','mis_entered',NULL,'$requestId','confirmation-$requestId','2026-02-03T02:00:00Z')",
    )
    execute(
        driver,
        "INSERT INTO transaction_void_receipt(ledger_id, request_id, confirmation_id, transaction_id, fact_id, fact_kind) VALUES ('$LEDGER','$requestId','confirmation-$requestId','$transactionId','fact-$requestId','$factKind')",
    )
}

private fun request(
    amountMinor: Long,
    requestId: String,
    original: String = ORIGINAL,
    destination: String = ASSET,
    arrivedAt: Instant = Instant.parse("2026-02-02T02:00:00Z"),
): ManualRefundReceiptRequest =
    ManualRefundReceiptRequest(
        ledgerId = LedgerId(LEDGER),
        requestId = RequestId(requestId),
        originalTransactionId = TransactionId(original),
        amount = Money.ofMinor(amountMinor, CurrencyUnit("CNY", 2)),
        destinationAccountId = AccountId(destination),
        arrivedAt = arrivedAt,
        confirmedAt = Instant.parse("2026-02-02T02:00:05Z"),
    )

private fun rejectionOf(result: ManualRefundReceiptSubmissionResult): ManualRefundRejectionCode =
    assertIs<ManualRefundReceiptSubmissionResult.Rejected>(result).code

private fun remainingOf(harness: DatabaseHarness): Long =
    harness.database.ledgerQueries.selectManualRefundRemaining(LEDGER, ORIGINAL, "CNY").executeAsOne()

private fun countsOf(harness: DatabaseHarness): List<Long> =
    listOf(
        queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"),
        queryCount(harness.driver, "SELECT count(*) FROM ledger_transaction"),
        queryCount(harness.driver, "SELECT count(*) FROM posting_set"),
        queryCount(harness.driver, "SELECT count(*) FROM transaction_version"),
        queryCount(harness.driver, "SELECT count(*) FROM posting"),
        queryCount(harness.driver, "SELECT count(*) FROM posting_reconciliation"),
    )

private fun effectiveLinkedRefundProbe(): String =
    "SELECT count(*) FROM (" +
        "SELECT decision.ledger_id AS ledger_id, confirmation.transaction_id AS refund_transaction_id " +
        "FROM import_candidate_decision_snapshot AS decision " +
        "JOIN import_confirmation AS confirmation ON confirmation.ledger_id = decision.ledger_id AND confirmation.request_id = decision.request_id " +
        "WHERE decision.ledger_id = '$LEDGER' AND decision.original_transaction_id = '$ORIGINAL' " +
        "UNION ALL " +
        "SELECT receipt.ledger_id, receipt.refund_transaction_id FROM manual_refund_receipt AS receipt " +
        "WHERE receipt.ledger_id = '$LEDGER' AND receipt.original_transaction_id = '$ORIGINAL'" +
        ") AS linked " +
        "JOIN transaction_effective_state AS effective ON effective.ledger_id = linked.ledger_id " +
        "AND effective.transaction_id = linked.refund_transaction_id AND effective.is_effective = 1"

private fun postingRows(
    driver: JdbcSqliteDriver,
    postingSetId: String,
): List<String> =
    driver
        .executeQuery(
            null,
            "SELECT posting_id || '|' || account_id || '|' || amount_minor FROM posting WHERE posting_set_id = '$postingSetId' ORDER BY posting_index",
            { cursor ->
                val rows = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
                QueryResult.Value(rows)
            },
            0,
        ).value

private fun execute(
    driver: JdbcSqliteDriver,
    sql: String,
) {
    driver.execute(null, sql, 0)
}

private fun scalarString(
    driver: JdbcSqliteDriver,
    sql: String,
): String =
    driver
        .executeQuery(
            null,
            sql,
            { cursor ->
                check(cursor.next().value)
                QueryResult.Value(requireNotNull(cursor.getString(0)))
            },
            0,
        ).value
