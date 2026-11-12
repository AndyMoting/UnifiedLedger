package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.ManualRefundReceiptCommitPort
import com.unifiedledger.application.ManualRefundReceiptCommitResult
import com.unifiedledger.application.ManualRefundReceiptIdSource
import com.unifiedledger.application.ManualRefundReceiptIds
import com.unifiedledger.application.ManualRefundReceiptReadPort
import com.unifiedledger.application.ManualRefundReceiptReadRecord
import com.unifiedledger.application.ManualRefundReceiptRequest
import com.unifiedledger.application.ManualRefundReceiptSubmissionResult
import com.unifiedledger.application.ManualRefundRejectionCode
import com.unifiedledger.application.MonthlyActivity
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.MonthlyContributionReadResult
import com.unifiedledger.application.MonthlyCurrencyActivity
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualRefundReceiptCommitStatus
import com.unifiedledger.application.SubmitManualRefundReceipt
import com.unifiedledger.application.TransactionVoidPlan
import com.unifiedledger.application.TransactionVoidRequestIdentity
import com.unifiedledger.application.TransactionVoidRequestSnapshot
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.application.manualRefundRequestFingerprint
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
import kotlinx.datetime.YearMonth
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
        P803DatabaseHarness.inMemory().use { harness ->
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

            // Condition 1 (same ledger): another ledger's otherwise identical candidate, carrying
            // a complete catalog of its own, must never leak into this ledger's list.
            execute(harness.driver, "INSERT INTO catalog_account VALUES ('$LEDGER_B','$DAILY','daily expense','EXPENSE','CNY',2,0,0,NULL,1,1)")
            execute(harness.driver, "INSERT INTO catalog_account VALUES ('$LEDGER_B','$ASSET','asset a','ASSET','CNY',2,1,1,NULL,0,1)")
            execute(harness.driver, "INSERT INTO catalog_category VALUES ('$LEDGER_B','category-root','EXPENSE',NULL,NULL,1)")
            execute(harness.driver, "INSERT INTO catalog_category VALUES ('$LEDGER_B','$CATEGORY_DAILY','EXPENSE','category-root','$DAILY',1)")
            seedTransaction(harness.driver, "tx-other-ledger", null, listOf(DAILY to 11_000L, ASSET to -11_000L), ledger = LEDGER_B)

            // Condition 5 (unique active secondary category): one expense account mapped to two
            // active leaves has no single inheritable category, so it is not a candidate.
            execute(harness.driver, "INSERT INTO catalog_account VALUES ('$LEDGER','$EXPENSE_SHARED','shared expense','EXPENSE','CNY',2,0,0,NULL,1,1)")
            execute(harness.driver, "INSERT INTO catalog_category VALUES ('$LEDGER','category-shared-1','EXPENSE','category-root','$EXPENSE_SHARED',1)")
            execute(harness.driver, "INSERT INTO catalog_category VALUES ('$LEDGER','category-shared-2','EXPENSE','category-root','$EXPENSE_SHARED',1)")
            seedTransaction(harness.driver, "tx-two-active-categories", null, listOf(EXPENSE_SHARED to 4_000L, ASSET to -4_000L))

            val candidates = candidatesOf(harness)
            assertEquals(listOf("tx-original"), candidates.map { it.transactionId })
            assertEquals(12_000L, candidates.single().structuralCapMinor)
            assertEquals(12_000L, candidates.single().remainingMinor)
            assertEquals(CATEGORY_DAILY, candidates.single().categoryId)

            // Condition 7 (remaining > 0) with the product store in the loop: a refund the store
            // just created never re-enters the candidate list, and once it consumes the whole
            // structural cap the original itself drops out.
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )
            assertEquals(listOf("tx-original"), candidatesOf(harness).map { it.transactionId })
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(request(amountMinor = 9_000L, requestId = "request-refund-2")),
            )
            assertEquals(emptyList(), candidatesOf(harness).map { it.transactionId })
        }
    }

    @Test
    fun v4CapIsTheExpenseLegAmountAndAVoidedRefundReleasesIt() {
        P803DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val submit = harness.useCase

            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                submit.submit(request(amountMinor = 3_000L, requestId = "request-refund-1")),
            )
            assertEquals(9_000L, STRUCTURAL_CAP_MINOR - refundedOf(harness))

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
            assertEquals(9_000L, STRUCTURAL_CAP_MINOR - refundedOf(harness))

            // Voiding the created refund releases its amount from the cap again (M4).
            insertVoidFact(harness.driver, "tx-refund-1", "request-void-refund-1", "void", sequence = 1L)
            assertEquals(12_000L, STRUCTURAL_CAP_MINOR - refundedOf(harness))
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                submit.submit(request(amountMinor = 11_000L, requestId = "request-refund-4")),
            )
            assertEquals(1_000L, STRUCTURAL_CAP_MINOR - refundedOf(harness))
        }
    }

    @Test
    fun v5EveryReachableRejectionPathLeavesTheRowCountsUnchanged() {
        // One case per rejection code the seeded fixture can reach; each case runs on a fresh
        // database because its setup is exactly the state that makes that code reachable.
        val cases =
            listOf(
                // Amount above the structural cap (the expense-leg amount).
                RejectionCase(
                    ManualRefundRejectionCode.REFUND_EXCEEDS_STRUCTURAL_CAP,
                    request(amountMinor = 13_000L, requestId = "request-structural-cap"),
                ),
                // Amount below the cap but above the remaining capacity (30.00 is already refunded).
                RejectionCase(
                    ManualRefundRejectionCode.REFUND_EXCEEDS_REMAINING,
                    request(amountMinor = 10_000L, requestId = "request-exceeds-remaining"),
                ) { harness ->
                    assertIs<ManualRefundReceiptSubmissionResult.Created>(
                        harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-consumed-cap")),
                    )
                },
                // The original transaction does not exist.
                RejectionCase(
                    ManualRefundRejectionCode.ORIGINAL_NOT_REFUNDABLE,
                    request(amountMinor = 3_000L, requestId = "request-missing-original", original = "tx-missing"),
                ),
                // The picked original was voided after selection.
                RejectionCase(
                    ManualRefundRejectionCode.ORIGINAL_NO_LONGER_REFUNDABLE,
                    request(amountMinor = 3_000L, requestId = "request-voided-original"),
                ) { harness ->
                    insertVoidFact(harness.driver, ORIGINAL, "request-void-original", "void", sequence = 1L)
                },
                // The inherited category's parent is not a level-1 category. The candidate query
                // and the catalog load both accept this shape (depth is a command rule), so the
                // domain factory is what refuses it: REFUND_CATEGORY_INELIGIBLE.
                RejectionCase(
                    ManualRefundRejectionCode.REFUND_CATEGORY_INELIGIBLE,
                    request(amountMinor = 3_000L, requestId = "request-nested-category"),
                ) { harness ->
                    execute(
                        harness.driver,
                        "UPDATE catalog_category SET parent_id = 'category-other' WHERE ledger_id = '$LEDGER' AND category_id = '$CATEGORY_DAILY'",
                    )
                },
                // The destination is not an owned real ASSET account.
                RejectionCase(
                    ManualRefundRejectionCode.REFUND_ACCOUNT_INELIGIBLE,
                    request(amountMinor = 3_000L, requestId = "request-bad-destination", destination = DAILY),
                ),
                // The same request id arrives with a different payload.
                RejectionCase(
                    ManualRefundRejectionCode.REFUND_REQUEST_CONFLICT,
                    request(amountMinor = 3_500L, requestId = "request-conflict"),
                ) { harness ->
                    assertIs<ManualRefundReceiptSubmissionResult.Created>(
                        harness.useCase.submit(request(amountMinor = 3_000L, requestId = "request-conflict")),
                    )
                },
                // The catalog authority is gone, so the store cannot decide the request.
                RejectionCase(
                    ManualRefundRejectionCode.REFUND_STORE_UNAVAILABLE,
                    request(amountMinor = 3_000L, requestId = "request-store-unavailable"),
                ) { harness ->
                    execute(harness.driver, "DELETE FROM catalog_version WHERE ledger_id = '$LEDGER'")
                },
            )

        for (case in cases) {
            P803DatabaseHarness.inMemory().use { harness ->
                seedRefundWorld(harness.driver)
                case.setup(harness)
                val before = countsOf(harness)
                assertEquals(case.expected, rejectionOf(harness.useCase.submit(case.request)), "rejection code")
                assertEquals(before, countsOf(harness), "rejection ${case.expected} wrote rows")
            }
        }
    }

    @Test
    fun v6TheRefundTransactionIsSeparateAndLeavesTheOriginalTransactionIntact() {
        P803DatabaseHarness.inMemory().use { harness ->
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
                listOf("posting-tx-original-0|$DAILY|12000", "posting-tx-original-1|$ASSET|-12000"),
                postingRows(harness.driver, "ps-tx-original"),
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
        P803DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            // The original payment leg is already fully reconciled before the refund arrives.
            execute(
                harness.driver,
                "INSERT INTO posting_reconciliation(ledger_id, reconciliation_id, posting_id, status, latest_sequence) " +
                    "VALUES ('$LEDGER','reconciliation-original-asset','posting-tx-original-1','CHECKED',1)",
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
                    "SELECT status || '|' || latest_sequence FROM posting_reconciliation WHERE ledger_id='$LEDGER' AND posting_id='posting-tx-original-1'",
                ),
            )
            // The refund's expense leg is not a real-account posting and gets no reconciliation.
            assertEquals(
                0L,
                queryCount(
                    harness.driver,
                    "SELECT count(*) FROM posting_reconciliation WHERE posting_id IN ('posting-refund-expense-1', 'posting-tx-original-0')",
                ),
            )
        }
    }

    @Test
    fun v7EquivalentReplayIsFreeAndAFingerprintConflictWritesNothing() {
        P803DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val created =
                assertIs<ManualRefundReceiptSubmissionResult.Created>(
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
        P803DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            // A real INCOME account with one booked income in March keeps the income assertion
            // load-bearing: the same read surface does report income when a posting hits one.
            execute(harness.driver, "INSERT INTO catalog_account VALUES ('$LEDGER','$INCOME','salary','INCOME','CNY',2,0,0,NULL,0,1)")
            seedTransaction(
                harness.driver,
                "tx-income-control",
                "INCOME",
                listOf(ASSET to 2_000L, INCOME to -2_000L),
                occurredAt = "2026-03-05T02:00:00Z",
            )

            // Spec section 5 V-8 reads the shipped monthly surface (the bounded contribution read
            // port plus the frozen MonthlyBuckets projection), never a hand-rolled sum.
            val januaryBefore = monthActivity(harness, YearMonth(2026, 1))
            assertIs<ManualRefundReceiptSubmissionResult.Created>(
                harness.useCase.submit(
                    request(
                        amountMinor = 3_000L,
                        requestId = "request-refund-1",
                        arrivedAt = Instant.parse("2026-02-02T02:00:00Z"),
                    ),
                ),
            )

            // The original period is unchanged by the arrival: 120.00 consumption, 0 income.
            assertEquals(januaryBefore, monthActivity(harness, YearMonth(2026, 1)))
            val january = januaryBefore.currencyActivity()
            assertEquals(12_000L, january.positiveExpenseMinorUnits)
            assertEquals(12_000L, january.netExpenseMinorUnits)
            assertEquals(0L, january.ordinaryIncomeMinorUnits)

            // The arrival period consumes -30.00 (the refund posting on the expense account) and
            // books no ordinary income. The cash inflow is the +30.00 destination asset leg of the
            // same shipped row surface: MonthlyBuckets never counts an asset leg as income.
            val february = monthActivity(harness, YearMonth(2026, 2)).currencyActivity()
            assertEquals(-3_000L, february.refundMinorUnits)
            assertEquals(-3_000L, february.netExpenseMinorUnits)
            assertEquals(0L, february.positiveExpenseMinorUnits)
            assertEquals(0L, february.ordinaryIncomeMinorUnits)
            assertEquals(
                3_000L,
                monthRows(harness, YearMonth(2026, 2))
                    .single { it.transactionId.value == "tx-refund-1" }
                    .postings
                    .single { it.accountId.value == ASSET }
                    .amount
                    .minorUnits,
            )

            // The control month proves the same surface reports a real income posting.
            assertEquals(
                2_000L,
                monthActivity(harness, YearMonth(2026, 3)).currencyActivity().ordinaryIncomeMinorUnits,
            )
            // `:144`'s arithmetic example over the shipped per-period numbers: 120.00 - 30.00 = 90.00.
            assertEquals(9_000L, january.netExpenseMinorUnits + february.netExpenseMinorUnits)
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
                    SubmitManualRefundReceipt(store, P803SequentialIdSource(), ResolveManualRefundReceiptCommitStatus(store))
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
        P803DatabaseHarness.inMemory().use { harness ->
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
        P803DatabaseHarness.inMemory().use { harness ->
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
        P803DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val failingAfterCommit =
                ManualRefundReceiptCommitPort { request, fingerprint, mintIds ->
                    harness.store.commitOnce(request, fingerprint, mintIds)
                    error("simulated failure after the commit boundary")
                }
            val useCase =
                SubmitManualRefundReceipt(
                    failingAfterCommit,
                    P803SequentialIdSource(),
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
        P803DatabaseHarness.inMemory().use { harness ->
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
                    P803SequentialIdSource(),
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

    /**
     * Spec section 2.6 at the store boundary: after a thrown attempt the relationship row is the
     * authority, so the store must answer from it instead of reporting a definite failure (a UI
     * retry under a new request id would otherwise double-book a committed refund). The throwing
     * fake in [v17AfterCommitReadFailureRecoversOnlyOnAnExactFingerprintAndRetriesTheSameId]
     * covers "committed, then the port threw"; this test is the production store's own re-read.
     */
    @Test
    fun v17TheStoreBoundaryAnswersAThrownAttemptFromTheRelationshipRow() {
        // (a) The attempt dies before its replay check can see the row, while the row of exactly
        // this payload is already committed: the catch path must answer with the replay.
        val matchingBase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(matchingBase)
            seedRefundWorld(matchingBase)
            val submitted = request(amountMinor = 3_000L, requestId = "request-refund-1")
            SqlDelightManualRefundReceiptStore(LedgerDatabase(matchingBase), matchingBase)
                .commitOnce(submitted, manualRefundRequestFingerprint(submitted)) { P803SequentialIdSource().next() }

            val driver = FaultingRefundDriver(matchingBase, FaultingRefundDriver.Fault.FIRST_READ)
            val store = SqlDelightManualRefundReceiptStore(LedgerDatabase(driver), driver)
            val replayed =
                assertIs<ManualRefundReceiptCommitResult.Replayed>(
                    store.commitOnce(submitted, manualRefundRequestFingerprint(submitted)) { P803SequentialIdSource().next() },
                )
            assertEquals("relationship-refund-1", replayed.receipt.relationshipId)
            assertEquals("tx-refund-1", replayed.receipt.refundTransactionId.value)
            assertEquals(1L, queryCount(matchingBase, "SELECT count(*) FROM manual_refund_receipt"))
        } finally {
            matchingBase.close()
        }

        // (b) A write that rolled back leaves no row to replay, so it stays the typed Unavailable
        // and neither the relationship row nor the refund transaction survives.
        val rolledBackBase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(rolledBackBase)
            seedRefundWorld(rolledBackBase)
            val driver = FaultingRefundDriver(rolledBackBase, FaultingRefundDriver.Fault.LAST_WRITE)
            val store = SqlDelightManualRefundReceiptStore(LedgerDatabase(driver), driver)
            val submitted = request(amountMinor = 3_000L, requestId = "request-refund-1")

            assertEquals(
                ManualRefundReceiptCommitResult.Unavailable,
                store.commitOnce(submitted, manualRefundRequestFingerprint(submitted)) { P803SequentialIdSource().next() },
            )
            assertEquals(0L, queryCount(rolledBackBase, "SELECT count(*) FROM manual_refund_receipt"))
            assertEquals(
                0L,
                queryCount(rolledBackBase, "SELECT count(*) FROM ledger_transaction WHERE transaction_id = 'tx-refund-1'"),
            )
        } finally {
            rolledBackBase.close()
        }

        // (c) The row belongs to another payload of the same request id: the in-transaction replay
        // check never ran, so the catch path only sees a fingerprint mismatch and stays
        // Unavailable. The conflict itself is decided inside the transaction, not here.
        val mismatchedBase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(mismatchedBase)
            seedRefundWorld(mismatchedBase)
            val committed = request(amountMinor = 3_000L, requestId = "request-refund-1")
            SqlDelightManualRefundReceiptStore(LedgerDatabase(mismatchedBase), mismatchedBase)
                .commitOnce(committed, manualRefundRequestFingerprint(committed)) { P803SequentialIdSource().next() }

            val driver = FaultingRefundDriver(mismatchedBase, FaultingRefundDriver.Fault.FIRST_READ)
            val store = SqlDelightManualRefundReceiptStore(LedgerDatabase(driver), driver)
            val repriced = request(amountMinor = 3_500L, requestId = "request-refund-1")
            assertEquals(
                ManualRefundReceiptCommitResult.Unavailable,
                store.commitOnce(repriced, manualRefundRequestFingerprint(repriced)) { P803SequentialIdSource().next() },
            )
            assertEquals(1L, queryCount(mismatchedBase, "SELECT count(*) FROM manual_refund_receipt"))
        } finally {
            mismatchedBase.close()
        }
    }

    @Test
    fun v19TheVoidGuardRejectsAnOriginalWithAnEffectiveManualRefund() {
        P803DatabaseHarness.inMemory().use { harness ->
            seedRefundWorld(harness.driver)
            val created =
                assertIs<ManualRefundReceiptSubmissionResult.Created>(
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
            assertEquals(
                0L,
                harness.database.ledgerQueries
                    .productLinkedRefundCountForTransaction(LEDGER, ORIGINAL, LEDGER, ORIGINAL)
                    .executeAsOne(),
            )
        }
    }
}

private class P803DatabaseHarness(
    val driver: JdbcSqliteDriver,
) : AutoCloseable {
    val database = LedgerDatabase(driver)
    val store = SqlDelightManualRefundReceiptStore(database, driver)
    val useCase = SubmitManualRefundReceipt(store, P803SequentialIdSource(), ResolveManualRefundReceiptCommitStatus(store))

    override fun close() {
        driver.close()
    }

    companion object {
        fun inMemory(): P803DatabaseHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            return P803DatabaseHarness(driver)
        }
    }
}

private class P803SequentialIdSource : ManualRefundReceiptIdSource {
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

/** One reachable rejection vector: the state that reaches [expected], then the request. */
private class RejectionCase(
    val expected: ManualRefundRejectionCode,
    val request: ManualRefundReceiptRequest,
    val setup: (P803DatabaseHarness) -> Unit = {},
)

/**
 * Test-only store-boundary faults. [Fault.FIRST_READ] fails the attempt's first statement, so the
 * in-transaction replay check never sees a stored row; [Fault.LAST_WRITE] fails the relationship
 * insert after the formal rows were written (a write that rolled back before it was visible).
 */
private class FaultingRefundDriver(
    private val delegate: SqlDriver,
    private val fault: Fault,
) : SqlDriver by delegate {
    enum class Fault { FIRST_READ, LAST_WRITE }

    private var firstReadFaultsRemaining = if (fault == Fault.FIRST_READ) 1 else 0

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (fault == Fault.LAST_WRITE && sql.contains("INSERT INTO manual_refund_receipt")) {
            throw SQLException("simulated failure before the relationship row is visible")
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (firstReadFaultsRemaining > 0) {
            firstReadFaultsRemaining -= 1
            throw SQLException("simulated read failure inside the write attempt")
        }
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

private const val STRUCTURAL_CAP_MINOR = 12_000L
private const val LEDGER = "ledger-a"
private const val LEDGER_B = "ledger-b"
private const val ORIGINAL = "tx-original"
private const val DAILY = "account-expense-daily"
private const val CATEGORY_DAILY = "category-daily"
private const val EXPENSE_OTHER = "account-expense-other"
private const val EXPENSE_SHARED = "account-expense-shared"
private const val ASSET = "account-asset-a"
private const val INCOME = "account-income-salary"

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
    ledger: String = LEDGER,
) {
    execute(
        driver,
        "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('$transactionId', '$ledger', 'EXPENSE', ${canonicalKind?.let { "'$it'" } ?: "NULL"})",
    )
    execute(driver, "INSERT INTO posting_set VALUES ('ps-$transactionId', '$ledger')")
    // The numeric projection comes from the shipped Kotlin half of the SQL projection, so the
    // month-window read (V-8) can place these rows without a missing-projection failure.
    val statisticsEpochNanos = requireNotNull(StatisticsAtProjection.project(Instant.parse(occurredAt)))
    execute(
        driver,
        "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) VALUES ('version-$transactionId', '$transactionId', '$ledger', 1, 'ps-$transactionId', '$occurredAt', '$occurredAt', '$occurredAt', NULL, $statisticsEpochNanos)",
    )
    execute(driver, "INSERT INTO ledger_transaction_current_version VALUES ('$transactionId', '$ledger', 'version-$transactionId')")
    legs.forEachIndexed { index, (account, amount) ->
        execute(
            driver,
            "INSERT INTO posting VALUES ('posting-$transactionId-$index', 'ps-$transactionId', '$ledger', $index, '$account', $amount, '$currency', 2)",
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

private fun rejectionOf(result: ManualRefundReceiptSubmissionResult): ManualRefundRejectionCode = assertIs<ManualRefundReceiptSubmissionResult.Rejected>(result).code

private fun refundedOf(harness: P803DatabaseHarness): Long = harness.database.ledgerQueries
    .selectManualRefundRefundedTotal(LEDGER, ORIGINAL, "CNY")
    .executeAsOne()

private fun candidatesOf(harness: P803DatabaseHarness): List<Candidate> =
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

/** V-8's shipped read surface: the bounded contribution port, then the frozen month projection. */
private fun monthRead(
    harness: P803DatabaseHarness,
    month: YearMonth,
): MonthlyContributionReadResult.Success {
    val database = harness.database
    val adapter =
        SqlDelightMonthlyContributionReadAdapter(
            database,
            SqlDelightCatalogStore(database, harness.driver),
        )
    val catalogVersion = database.ledgerQueries.selectCatalogVersion(LEDGER).executeAsOne()
    return assertIs<MonthlyContributionReadResult.Success>(
        adapter.readContributions(
            LedgerId(LEDGER),
            MonthlyBuckets.monthStart(month),
            MonthlyBuckets.monthEndExclusive(month),
            catalogVersion,
        ),
    )
}

private fun monthRows(
    harness: P803DatabaseHarness,
    month: YearMonth,
): List<LedgerEntryRow> = monthRead(harness, month).rows

private fun monthActivity(
    harness: P803DatabaseHarness,
    month: YearMonth,
): MonthlyActivity {
    val read = monthRead(harness, month)
    return MonthlyBuckets
        .aggregate(read.rows, LedgerId(LEDGER), read.catalog, listOf(month))
        .getValue(month)
}

private fun MonthlyActivity.currencyActivity(): MonthlyCurrencyActivity = currencies.single { it.currency.code == "CNY" }

private fun countsOf(harness: P803DatabaseHarness): List<Long> =
    listOf(
        queryCount(harness.driver, "SELECT count(*) FROM manual_refund_receipt"),
        queryCount(harness.driver, "SELECT count(*) FROM ledger_transaction"),
        queryCount(harness.driver, "SELECT count(*) FROM posting_set"),
        queryCount(harness.driver, "SELECT count(*) FROM transaction_version"),
        queryCount(harness.driver, "SELECT count(*) FROM posting"),
        queryCount(harness.driver, "SELECT count(*) FROM posting_reconciliation"),
    )

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
