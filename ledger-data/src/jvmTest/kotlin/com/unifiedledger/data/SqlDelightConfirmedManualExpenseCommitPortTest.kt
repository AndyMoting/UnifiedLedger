package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.OrdinaryExpenseViolation
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightConfirmedManualExpenseCommitPortTest {
    @Test
    fun firstSaveAtomicallyPersistsTheCompleteRequestReceiptAndFormalTransaction() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()
            var callbackCount = 0

            val result =
                harness.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                    callbackCount += 1
                    DomainResult.Success(fixture.commit())
                }

            assertEquals(1, callbackCount)
            assertEquals(
                ConfirmedManualExpenseResult.Created(fixture.receipt()),
                result,
            )
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), harness.counts())
            assertEquals(fixture.persistedRequest(), harness.persistedRequest())
            assertEquals(fixture.persistedTransaction(), harness.persistedTransaction())
            assertEquals(fixture.persistedVersion(), harness.persistedVersion())
            assertEquals(fixture.persistedPostings(), harness.persistedPostings())
        }
    }

    @Test
    fun equivalentReplayAfterReopeningReturnsTheOriginalReceiptWithoutCreationOrRewind() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).use { first ->
                assertIs<ConfirmedManualExpenseResult.Created>(
                    first.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                        DomainResult.Success(fixture.commit())
                    },
                )
                first.appendNoteReplacement(fixture)
            }

            var callbackCount = 0
            file.open().use { reopened ->
                val result =
                    reopened.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                        callbackCount += 1
                        DomainResult.Success(fixture.commit())
                    }

                assertEquals(0, callbackCount)
                assertEquals(
                    ConfirmedManualExpenseResult.NoChange(fixture.receipt()),
                    result,
                )
                assertEquals(2, reopened.versionCount())
                assertEquals("version-expense-a-v2", reopened.currentVersionId())
                assertEquals("replacement note", reopened.currentNote())
            }
        }
    }

    @Test
    fun conflictingReplayAfterReopeningDoesNotInvokeCreationOrWrite() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).use { first ->
                first.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                    DomainResult.Success(fixture.commit())
                }
            }

            val conflicts =
                listOf(
                    fixture.snapshot.copy(amount = fixture.money(3_581)),
                    fixture.snapshot.copy(
                        amount = Money.ofMinor(3_580, CurrencyUnit("USD", 2)),
                    ),
                    fixture.snapshot.copy(categoryId = CategoryId("expense-category-other")),
                    fixture.snapshot.copy(paymentAccountId = AccountId("asset-other")),
                    fixture.snapshot.copy(occurredAt = Instant.parse("2026-01-15T00:31:00Z")),
                    fixture.snapshot.copy(note = "changed note"),
                )
            var callbackCount = 0

            file.open().use { reopened ->
                conflicts.forEach { conflict ->
                    assertEquals(
                        ConfirmedManualExpenseResult.RequestIdentityConflict(fixture.identity),
                        reopened.port.commitOnce(fixture.identity, conflict, fixture.annotationCreatedAt) {
                            callbackCount += 1
                            DomainResult.Success(fixture.commit())
                        },
                    )
                }

                assertEquals(0, callbackCount)
                assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), reopened.counts())
            }
        }
    }

    /*
     * P706-A08 (docs/specs/2026-09-24-p7-06-backup-container-format-design.md:312, "恢复库已有提交回执，
     * 重放同请求返回原结果、零新增经济效果"; the replay semantics themselves are 06.4) needs the
     * RESTORE leg, not only the reopen leg: a restore REPLACES the database file with a `VACUUM INTO`
     * snapshot (spec section 4.2), so the idempotency receipt has to survive a new file identity,
     * not merely a new connection to the same bytes. The supply chain is the product's own
     * `runSnapshotIntoOn` (BackupSnapshotDriver.kt:29), and the snapshot is then opened as-is.
     *
     * SCOPE (do not overclaim): this is the restore's DATABASE-IDENTITY leg, NOT the product restore
     * pipeline. The container decrypt -> preflight -> isolated-migration copy -> generation staging
     * -> pointer publish -> journal -> explicit reopen chain is A05/A06/A07's subject and is not
     * exercised here. What is proven is narrower and still real: a genuinely new database file
     * identity answers the replay from the receipt the first commit produced.
     *
     * FAILURE MODES (what the zero-callback assertion actually catches): losing only the receipt row
     * makes `selectCommittedRequest`'s inner JOIN (Ledger.sq:1990-1993) return null, so the port's
     * `checkNotNull` (SqlDelightConfirmedManualExpenseCommitPort.kt:153) THROWS — it does not silently
     * re-run the effect. Only losing the whole request row lets the claim INSERT
     * (Ledger.sq:2410-2423) succeed again and re-invoke the creation callback for a second economic
     * effect. Both are failures; they are different failures, and the assertions below cover both.
     */
    @Test
    fun equivalentReplayAfterRestoringASnapshotReturnsTheOriginalReceiptWithoutCreationOrRewind() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            val (created, source) =
                file.open(createSchema = true).use { first ->
                    val created =
                        assertIs<ConfirmedManualExpenseResult.Created>(
                            first.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                                DomainResult.Success(fixture.commit())
                            },
                        )
                    // The restored file must carry a non-trivial CURRENT version, otherwise "no
                    // rewind" would be indistinguishable from "the version pointer happened to be
                    // the same".
                    first.appendNoteReplacement(fixture)
                    created to first.sourceState(fixture)
                }
            // "返回原结果" is about the PRODUCED result: the replay below is compared against
            // `created.receipt`, never against a re-derived fixture value. This fixture check is
            // kept only as a sanity link between the fixture and the commit under test.
            assertEquals(fixture.receipt(), created.receipt)

            val snapshot = Files.createTempFile("ledger-a08-restore-", ".snapshot")
            // `VACUUM INTO` refuses an existing target (BackupSnapshotDriver.kt:17), and the temp-file
            // API always creates one, so the placeholder is removed before the snapshot runs.
            Files.delete(snapshot)
            try {
                snapshotOf(file, snapshot)
                // A08 is replay-after-restore, NOT the migration edge: this is a same-version v35
                // snapshot, so the restored file opens directly. The v34/v33 strict-migration legs
                // belong to A04/A11 and are covered by P708RestoreRoundtripEquivalenceTest.
                // Because the source was stamped like a real product file, this assertion is a
                // real check that the restore path carries the authoritative version across.
                // Plain driver with no tuning properties: that is the desktop product's own
                // verification posture (DesktopBackupSnapshotPort.kt:33). Android opens the same
                // read through a genuinely read-only driver (AndroidRestorePreflightPorts.kt:175);
                // read-write vs read-only does not change a `PRAGMA user_version` read of an
                // existing file, and the probe never writes.
                JdbcSqliteDriver("jdbc:sqlite:${snapshot.absolutePathString()}").use { probe ->
                    assertEquals(LedgerDatabase.Schema.version, readAuthoritativeUserVersionOn(probe))
                }

                var callbackCount = 0
                openRestored(snapshot).use { restored ->
                    val result =
                        restored.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                            callbackCount += 1
                            DomainResult.Success(fixture.commit())
                        }

                    assertEquals(0, callbackCount)
                    assertEquals(
                        ConfirmedManualExpenseResult.NoChange(created.receipt),
                        result,
                    )
                    // "零新增经济效果" has to cover the annotation aggregate too: an extra manual-create
                    // append would add a revision (and a tag row) without touching any of the six
                    // formal tables counted by StorageCounts.
                    assertEquals(StorageCounts(1, 1, 1, 2, 1, 2), restored.counts())
                    assertEquals(source.counts, restored.counts())
                    // The base fixture carries empty tagIds and a null merchant, so the D-221 append
                    // (SqlDelightManualAnnotationAppend.kt:36-48) writes exactly ONE revision, ZERO
                    // tag association rows (an empty set encodes to NULL and the loop never runs),
                    // and one current pointer. The two command tables stay empty because they belong
                    // to the annotation UPDATE command, whose only writers are
                    // SqlDelightTransactionAnnotationStore.kt:151 and :244 — manual create never
                    // reaches them.
                    //
                    // A non-empty-association variant would be stronger evidence for the tag-row
                    // count, but it needs a seeded tag/merchant catalog (catalog_item_version,
                    // catalog_tag, catalog_item_name_history plus the claim-first command rows) for
                    // `requireActiveCatalogItems` to pass, which is a new fixture, not a cheap edit.
                    // It is registered as a residual rather than built here.
                    assertEquals(AnnotationCounts(1, 0, 1, 0, 0), restored.annotationCounts())
                    assertEquals(source.annotations, restored.annotationCounts())
                    // The request row's canonical annotation columns are the replay comparison
                    // surface (SqlDelightConfirmedManualExpenseCommitPort.kt:309-310); NULL/NULL is
                    // the frozen "no annotation" encoding (AnnotationEncoding.kt:22-28) and must not
                    // be rewritten by a replayed claim.
                    assertEquals(RequestAnnotationColumns(null, null), restored.requestAnnotationColumns(fixture))
                    assertEquals(source.requestAnnotation, restored.requestAnnotationColumns(fixture))
                    assertEquals(2, restored.versionCount())
                    assertEquals("version-expense-a-v2", restored.currentVersionId())
                    assertEquals("replacement note", restored.currentNote())
                }
            } finally {
                Files.deleteIfExists(snapshot)
            }
        }
    }

    /*
     * The conflict twin of the restore leg above: the restored snapshot must still distinguish
     * "same request, same snapshot" from "same request id, different snapshot" (06.4 replay
     * semantics; the request row's canonical claim columns are the comparison surface).
     *
     * SCOPE: again the DATABASE-IDENTITY leg only, not the container/preflight/staging pipeline
     * (A05/A06/A07). A conflicting replay never reaches the creation callback in any database, so
     * here the receipt is not "replayed"; the property asserted is that it survives untouched and
     * still drives the conflict branch out of the restored file.
     *
     * FAILURE MODES: if the restored file lost a claim column, a differing snapshot would compare
     * equal and the port would return NoChange (accepting the changed request); if it lost the
     * receipt, the JOIN would return null and `checkNotNull` would throw. Neither may happen.
     */
    @Test
    fun conflictingReplayAfterRestoringASnapshotDoesNotInvokeCreationOrWrite() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            val source =
                file.open(createSchema = true).use { first ->
                    first.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                        DomainResult.Success(fixture.commit())
                    }
                    first.appendNoteReplacement(fixture)
                    first.sourceState(fixture)
                }

            val snapshot = Files.createTempFile("ledger-a08-restore-", ".snapshot")
            Files.delete(snapshot)
            try {
                snapshotOf(file, snapshot)
                val conflicts =
                    listOf(
                        fixture.snapshot.copy(amount = fixture.money(3_581)),
                        fixture.snapshot.copy(
                            amount = Money.ofMinor(3_580, CurrencyUnit("USD", 2)),
                        ),
                        fixture.snapshot.copy(categoryId = CategoryId("expense-category-other")),
                        fixture.snapshot.copy(paymentAccountId = AccountId("asset-other")),
                        fixture.snapshot.copy(occurredAt = Instant.parse("2026-01-15T00:31:00Z")),
                        fixture.snapshot.copy(note = "changed note"),
                        // This schema version DOES carry the D-221 R-5 annotation columns
                        // (ManualExpenseRequestSnapshot.tagIds/merchantId,
                        // ConfirmedManualExpense.kt:51-60), and the commit path stamps their canonical
                        // encoding onto the claim row, so both are identity-bearing for replay.
                        fixture.snapshot.copy(tagIds = setOf(TagId("tag-changed"))),
                        fixture.snapshot.copy(merchantId = MerchantId("merchant-changed")),
                    )
                var callbackCount = 0

                openRestored(snapshot).use { restored ->
                    conflicts.forEach { conflict ->
                        assertEquals(
                            ConfirmedManualExpenseResult.RequestIdentityConflict(fixture.identity),
                            restored.port.commitOnce(fixture.identity, conflict, fixture.annotationCreatedAt) {
                                callbackCount += 1
                                DomainResult.Success(fixture.commit())
                            },
                        )
                    }

                    assertEquals(0, callbackCount)
                    assertEquals(StorageCounts(1, 1, 1, 2, 1, 2), restored.counts())
                    assertEquals(source.counts, restored.counts())
                    // The receipt-bearing request row is returned unchanged by all eight conflicts,
                    // including the two annotation-bearing ones: the original confirmation and
                    // transaction ids (and the NULL/NULL annotation encoding) survive.
                    assertEquals(source.request, restored.persistedRequest())
                    assertEquals(AnnotationCounts(1, 0, 1, 0, 0), restored.annotationCounts())
                    assertEquals(source.annotations, restored.annotationCounts())
                    assertEquals(RequestAnnotationColumns(null, null), restored.requestAnnotationColumns(fixture))
                    assertEquals(source.requestAnnotation, restored.requestAnnotationColumns(fixture))
                }
            } finally {
                Files.deleteIfExists(snapshot)
            }
        }
    }

    @Test
    fun rejectedCreationLeavesNoResidueAndTheIdentityCanBeRetried() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            val rejected =
                harness.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                    DomainResult.Failure(OrdinaryExpenseViolation.AmountMustBePositive)
                }

            assertEquals(
                ConfirmedManualExpenseResult.Rejected(
                    OrdinaryExpenseViolation.AmountMustBePositive,
                ),
                rejected,
            )
            assertEquals(StorageCounts.EMPTY, harness.counts())

            val created =
                harness.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                    DomainResult.Success(fixture.commit())
                }
            assertIs<ConfirmedManualExpenseResult.Created>(created)
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), harness.counts())
        }
    }

    @Test
    fun aDistinctRequestIdentityPersistsAnIndependentTransaction() {
        DatabaseHarness.inMemory().use { harness ->
            val first = ExpenseFixture()
            val second =
                ExpenseFixture(
                    requestId = "request-b",
                    suffix = "b",
                    confirmationId = "confirmation-b",
                )

            assertIs<ConfirmedManualExpenseResult.Created>(
                harness.port.commitOnce(first.identity, first.snapshot, first.annotationCreatedAt) {
                    DomainResult.Success(first.commit())
                },
            )
            assertIs<ConfirmedManualExpenseResult.Created>(
                harness.port.commitOnce(second.identity, second.snapshot, second.annotationCreatedAt) {
                    DomainResult.Success(second.commit())
                },
            )

            assertEquals(first.snapshot, second.snapshot)
            assertEquals(StorageCounts(2, 2, 2, 2, 2, 4), harness.counts())
        }
    }

    @Test
    fun aPersistenceFailureRollsBackTheEntireSecondCommit() {
        DatabaseHarness.inMemory().use { harness ->
            val first = ExpenseFixture()
            val colliding =
                ExpenseFixture(
                    requestId = "request-b",
                    suffix = "b",
                    confirmationId = "confirmation-b",
                    postingIdSuffix = "a",
                )
            harness.port.commitOnce(first.identity, first.snapshot, first.annotationCreatedAt) {
                DomainResult.Success(first.commit())
            }

            assertFailsWith<SQLException> {
                harness.port.commitOnce(colliding.identity, colliding.snapshot, colliding.annotationCreatedAt) {
                    DomainResult.Success(colliding.commit())
                }
            }

            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), harness.counts())
        }
    }

    @Test
    fun identityAndSnapshotMustBelongToTheSameLedger() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            assertFailsWith<IllegalArgumentException> {
                harness.port.commitOnce(
                    fixture.identity.copy(ledgerId = LedgerId("ledger-other")),
                    fixture.snapshot,
                    fixture.annotationCreatedAt,
                ) {
                    DomainResult.Success(fixture.commit())
                }
            }

            assertEquals(StorageCounts.EMPTY, harness.counts())
        }
    }

    @Test
    fun concurrentEquivalentRequestsClaimOnceAndReturnCreatedAndNoChange() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).close()
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val callbackCount = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (0 until 2).map {
                        executor.submit<ConfirmedManualExpenseResult> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            file.open().use { harness ->
                                harness.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                                    callbackCount.incrementAndGet()
                                    callbackEntered.countDown()
                                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                                    DomainResult.Success(fixture.commit())
                                }
                            }
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()

                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, callbackCount.get())
                assertEquals(
                    setOf(
                        ConfirmedManualExpenseResult.Created(fixture.receipt()),
                        ConfirmedManualExpenseResult.NoChange(fixture.receipt()),
                    ),
                    results.toSet(),
                )
            } finally {
                executor.shutdownNow()
            }
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), file.open().use { it.counts() })
        }
    }

    @Test
    fun concurrentChangedRequestsClaimOnceAndReturnConflictWithoutSecondCallback() {
        DatabaseHarness.fileBacked().use { file ->
            val fixture = ExpenseFixture()
            file.open(createSchema = true).close()
            val changed = fixture.snapshot.copy(note = "changed")
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val callbackCount = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf(fixture.snapshot, changed).map { snapshot ->
                        executor.submit<ConfirmedManualExpenseResult> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            file.open().use { harness ->
                                harness.port.commitOnce(fixture.identity, snapshot, fixture.annotationCreatedAt) {
                                    callbackCount.incrementAndGet()
                                    callbackEntered.countDown()
                                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                                    DomainResult.Success(fixture.commit())
                                }
                            }
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                releaseCallback.countDown()

                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, callbackCount.get())
                assertEquals(1, results.count { it is ConfirmedManualExpenseResult.Created })
                assertEquals(1, results.count { it is ConfirmedManualExpenseResult.RequestIdentityConflict })
            } finally {
                executor.shutdownNow()
            }
            assertEquals(StorageCounts(1, 1, 1, 1, 1, 2), file.open().use { it.counts() })
        }
    }

    @Test
    fun aThrowingCreationCallbackRollsBackTheClaimAndEveryFormalRow() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            assertFailsWith<IllegalStateException> {
                harness.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                    error("synthetic callback failure")
                }
            }

            assertEquals(StorageCounts.EMPTY, harness.counts())
        }
    }

    @Test
    fun aCommitFromAnotherLedgerIsRejectedBeforeAnyWrite() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()

            assertFailsWith<IllegalArgumentException> {
                harness.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                    DomainResult.Success(fixture.commitForLedger(LedgerId("ledger-other")))
                }
            }

            assertEquals(StorageCounts.EMPTY, harness.counts())
        }
    }

    @Test
    fun everyJvmDriverEnablesForeignKeysAndRejectsInvalidFormalTopology() {
        DatabaseHarness.inMemory().use { harness ->
            val fixture = ExpenseFixture()
            assertEquals("1", harness.foreignKeysEnabled())

            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO ledger_transaction_current_version(
                      transaction_id, ledger_id, current_version_id
                    ) VALUES ('tx-expense-a', 'ledger-a', 'version-missing')
                    """.trimIndent(),
                    0,
                )
            }

            harness.port.commitOnce(fixture.identity, fixture.snapshot, fixture.annotationCreatedAt) {
                DomainResult.Success(fixture.commit())
            }
            harness.driver.execute(
                null,
                """
                INSERT INTO manual_expense_request(
                  ledger_id, request_id, amount_minor, currency_code, currency_precision,
                  category_id, payment_account_id, occurred_at, note, confirmation_marker
                ) VALUES ('ledger-other', 'request-other', 1, 'CNY', 2,
                  'category', 'asset', '2026-01-15T00:30:00Z', '', 'explicit_manual_save')
                """.trimIndent(),
                0,
            )
            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO confirmed_expense_receipt(
                      ledger_id, request_id, confirmation_id, transaction_id
                    ) VALUES ('ledger-other', 'request-other', 'confirmation-other', 'tx-expense-a')
                    """.trimIndent(),
                    0,
                )
            }

            harness.driver.execute(
                null,
                "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('posting-set-other', 'ledger-other')",
                0,
            )
            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO posting(
                      posting_id, posting_set_id, ledger_id, posting_index,
                      account_id, amount_minor, currency_code, currency_precision
                    ) VALUES ('posting-cross-ledger', 'posting-set-other', 'ledger-a', 0,
                      'asset-bank-a', 1, 'CNY', 2)
                    """.trimIndent(),
                    0,
                )
            }

            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    "UPDATE ledger_transaction SET kind = 'UNKNOWN' WHERE transaction_id = 'tx-expense-a'",
                    0,
                )
            }

            assertFailsWith<SQLException> {
                harness.driver.execute(
                    null,
                    """
                    INSERT INTO posting(
                      posting_id, posting_set_id, ledger_id, posting_index,
                      account_id, amount_minor, currency_code, currency_precision
                    ) VALUES ('posting-negative-precision', 'posting-set-expense-a', 'ledger-a', 9,
                      'asset-bank-a', 1, 'CNY', -1)
                    """.trimIndent(),
                    0,
                )
            }
        }
    }
}

private data class StorageCounts(
    val requests: Long,
    val receipts: Long,
    val transactions: Long,
    val versions: Long,
    val postingSets: Long,
    val postings: Long,
) {
    companion object {
        val EMPTY = StorageCounts(0, 0, 0, 0, 0, 0)
    }
}

/**
 * The D-221 annotation aggregate counts, the second half of "零新增经济效果" (P706-A08). The formal
 * [StorageCounts] six tables cannot see an extra manual-create annotation append, so a buggy replay
 * that re-ran the annotation leg would add a revision and a current pointer without moving any of
 * them. Counted per ledger through the generated count queries (Ledger.sq:10908-10921).
 */
private data class AnnotationCounts(
    val revisions: Long,
    val tags: Long,
    val currents: Long,
    val commandRequests: Long,
    val commandReceipts: Long,
)

/** The request row's canonical claim columns, the replay comparison surface (D-221 R-2/R-5). */
private data class RequestAnnotationColumns(
    val tagIds: String?,
    val merchantId: String?,
)

/** The pre-replay state of the SOURCE database, so "unchanged" is asserted against real values. */
private data class SourceState(
    val counts: StorageCounts,
    val annotations: AnnotationCounts,
    val request: PersistedRequest,
    val requestAnnotation: RequestAnnotationColumns,
)

private data class PersistedRequest(
    val ledgerId: String,
    val requestId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
    val categoryId: String,
    val paymentAccountId: String,
    val occurredAt: String,
    val note: String,
    val confirmationMarker: String,
    val confirmationId: String,
    val transactionId: String,
)

private data class PersistedTransaction(
    val transactionId: String,
    val ledgerId: String,
    val kind: String,
    val currentVersionId: String,
)

private data class PersistedVersion(
    val versionId: String,
    val transactionId: String,
    val versionNumber: Long,
    val postingSetId: String,
    val occurredAt: String,
    val statisticsAt: String,
    val effectiveAt: String,
    val note: String?,
)

private data class PersistedPosting(
    val postingId: String,
    val postingIndex: Long,
    val accountId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
)

private class DatabaseHarness(
    val driver: JdbcSqliteDriver,
) : AutoCloseable {
    private val database = LedgerDatabase(driver)
    val port = SqlDelightConfirmedManualExpenseCommitPort(database, driver)

    fun counts() =
        StorageCounts(
            requests = database.ledgerQueries.countRequests().executeAsOne(),
            receipts = database.ledgerQueries.countReceipts().executeAsOne(),
            transactions = database.ledgerQueries.countTransactions().executeAsOne(),
            versions = database.ledgerQueries.countVersions().executeAsOne(),
            postingSets = database.ledgerQueries.countPostingSets().executeAsOne(),
            postings = database.ledgerQueries.countPostings().executeAsOne(),
        )

    fun annotationCounts() =
        AnnotationCounts(
            revisions = database.ledgerQueries.countTransactionAnnotationRevisions("ledger-a").executeAsOne(),
            tags = database.ledgerQueries.countTransactionAnnotationTags("ledger-a").executeAsOne(),
            currents = database.ledgerQueries.countTransactionAnnotationCurrents("ledger-a").executeAsOne(),
            commandRequests =
                database.ledgerQueries.countTransactionAnnotationCommandRequests("ledger-a").executeAsOne(),
            commandReceipts =
                database.ledgerQueries.countTransactionAnnotationCommandReceipts("ledger-a").executeAsOne(),
        )

    /**
     * The request row's canonical annotation claim columns. Read straight from the table (not through
     * the receipt JOIN) so the columns can be compared even if a future mutation drops the receipt.
     * The identity is bound, never interpolated, so the SQL text stays fixed.
     */
    fun requestAnnotationColumns(fixture: ExpenseFixture): RequestAnnotationColumns {
        var columns: RequestAnnotationColumns? = null
        driver
            .executeQuery(
                null,
                "SELECT annotation_tag_ids, annotation_merchant_id FROM manual_expense_request " +
                    "WHERE ledger_id = ? AND request_id = ?",
                { cursor ->
                    check(cursor.next().value) { "the committed request row must exist" }
                    columns = RequestAnnotationColumns(cursor.getString(0), cursor.getString(1))
                    app.cash.sqldelight.db.QueryResult.Unit
                },
                2,
            ) {
                bindString(0, fixture.identity.ledgerId.value)
                bindString(1, fixture.identity.requestId.value)
            }.value
        return checkNotNull(columns)
    }

    /** Captures everything the replay must leave untouched, read from the SOURCE before snapshotting. */
    fun sourceState(fixture: ExpenseFixture) =
        SourceState(
            counts = counts(),
            annotations = annotationCounts(),
            request = persistedRequest(),
            requestAnnotation = requestAnnotationColumns(fixture),
        )

    fun persistedRequest(): PersistedRequest =
        database.ledgerQueries
            .selectPersistedRequest {
                ledgerId,
                requestId,
                amountMinor,
                currencyCode,
                currencyPrecision,
                categoryId,
                paymentAccountId,
                occurredAt,
                note,
                confirmationMarker,
                confirmationId,
                transactionId,
                ->
                PersistedRequest(
                    ledgerId,
                    requestId,
                    amountMinor,
                    currencyCode,
                    currencyPrecision,
                    categoryId,
                    paymentAccountId,
                    occurredAt,
                    note,
                    confirmationMarker,
                    confirmationId,
                    transactionId,
                )
            }.executeAsOne()

    fun persistedTransaction(): PersistedTransaction =
        database.ledgerQueries
            .selectPersistedTransaction {
                transactionId,
                ledgerId,
                kind,
                currentVersionId,
                ->
                PersistedTransaction(transactionId, ledgerId, kind, currentVersionId)
            }.executeAsOne()

    fun persistedVersion(): PersistedVersion =
        database.ledgerQueries
            .selectPersistedVersions {
                versionId,
                transactionId,
                versionNumber,
                postingSetId,
                occurredAt,
                statisticsAt,
                effectiveAt,
                note,
                ->
                PersistedVersion(
                    versionId,
                    transactionId,
                    versionNumber,
                    postingSetId,
                    occurredAt,
                    statisticsAt,
                    effectiveAt,
                    note,
                )
            }.executeAsOne()

    fun persistedPostings(): List<PersistedPosting> =
        database.ledgerQueries
            .selectPersistedPostings {
                postingId,
                postingIndex,
                accountId,
                amountMinor,
                currencyCode,
                currencyPrecision,
                ->
                PersistedPosting(
                    postingId,
                    postingIndex,
                    accountId,
                    amountMinor,
                    currencyCode,
                    currencyPrecision,
                )
            }.executeAsList()

    fun appendNoteReplacement(fixture: ExpenseFixture) {
        database.transaction {
            database.ledgerQueries.insertTransactionVersion(
                version_id = "version-expense-a-v2",
                transaction_id = fixture.transactionId.value,
                ledger_id = "ledger-a",
                version_number = 2,
                posting_set_id = fixture.postingSetId.value,
                occurred_at = fixture.occurredAt.toString(),
                statistics_at = fixture.occurredAt.toString(),
                effective_at = fixture.occurredAt.toString(),
                note = "replacement note",
            )
            database.ledgerQueries.updateCurrentVersion(
                current_version_id = "version-expense-a-v2",
                transaction_id = fixture.transactionId.value,
            )
        }
    }

    fun versionCount(): Long = database.ledgerQueries.countVersions().executeAsOne()

    fun currentVersionId(): String = database.ledgerQueries.selectCurrentVersionId().executeAsOne()

    fun currentNote(): String? =
        database.ledgerQueries
            .selectCurrentNote()
            .executeAsOne()
            .note

    fun foreignKeysEnabled(): String = database.ledgerQueries.foreignKeysEnabled().executeAsOne()

    override fun close() {
        driver.close()
    }

    companion object {
        fun inMemory(): DatabaseHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
            LedgerDatabase.Schema.create(driver)
            // Deliberately NOT stamped, unlike FileDatabase.open(createSchema = true): this harness is
            // never snapshotted, so no restore leg ever reads its `user_version`, and the file-backed
            // stamp exists solely so the A08 snapshot carries a realistic product version.
            return DatabaseHarness(driver)
        }
    }
}

private class FileDatabase private constructor(
    // P706-A08 restore leg: `VACUUM INTO` must run against the SOURCE file and needs the path to do
    // it, so the path is visible inside this file instead of being re-derived or copied by hand.
    val path: java.nio.file.Path,
) : AutoCloseable {
    fun open(createSchema: Boolean = false): DatabaseHarness {
        val driver =
            JdbcSqliteDriver(
                "jdbc:sqlite:${path.absolutePathString()}",
                sqliteProperties(),
            )
        if (createSchema) {
            LedgerDatabase.Schema.create(driver)
            stampCurrentSchemaVersion(driver)
        }
        return DatabaseHarness(driver)
    }

    override fun close() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun create(): FileDatabase = FileDatabase(Files.createTempFile("ledger-data-", ".db"))
    }
}

private fun sqliteProperties(): Properties =
    Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

/**
 * Stamps `PRAGMA user_version` on a freshly created schema, exactly as the product does
 * (`Main.kt:1298`: SQLDelight's generated `Schema.create`/`Schema.migrate` perform NO
 * `user_version` bookkeeping, so `migrateToCurrentSchema` stamps it itself at `Main.kt:1330-1331`).
 *
 * A08 is about replay after restoring a REAL product database, so the fixture must carry the same
 * authoritative version a real file does. Without this stamp `Schema.create` leaves `user_version` at
 * 0, `VACUUM INTO` faithfully copies that 0, and the restored file would be a schema-only artifact
 * rather than a restorable product database — a different (and unrealistic) case. The stamp goes on
 * the SOURCE before the snapshot runs; stamping the snapshot afterwards would fake the very
 * property the restore leg is supposed to prove.
 */
private fun stampCurrentSchemaVersion(driver: SqlDriver) {
    driver.execute(null, "PRAGMA user_version = ${LedgerDatabase.Schema.version}", 0)
}

/**
 * Writes a `VACUUM INTO` snapshot of [file]'s database to [target] through the product's real
 * snapshot primitive (BackupSnapshotDriver.kt:29). The source must be closed first: the harness
 * opens its own connection per [FileDatabase.open], and `runSnapshotIntoOn` needs a live driver on
 * the SOURCE, so this opens one, snapshots, and closes it.
 */
private fun snapshotOf(
    file: FileDatabase,
    target: java.nio.file.Path,
) {
    JdbcSqliteDriver("jdbc:sqlite:${file.path.absolutePathString()}", sqliteProperties()).use { source ->
        runSnapshotIntoOn(source, target.absolutePathString())
    }
}

/**
 * Opens [snapshot] as the restored database, through the same `sqliteProperties()` (foreign_keys ON)
 * path [FileDatabase.open] uses, so the restored harness is configured exactly like a live one.
 */
private fun openRestored(snapshot: java.nio.file.Path): DatabaseHarness =
    DatabaseHarness(JdbcSqliteDriver("jdbc:sqlite:${snapshot.absolutePathString()}", sqliteProperties()))

private fun DatabaseHarness.Companion.fileBacked(): FileDatabase = FileDatabase.create()

private class ExpenseFixture(
    requestId: String = "request-a",
    private val suffix: String = "a",
    private val confirmationId: String = "confirmation-a",
    private val postingIdSuffix: String = suffix,
) {
    private val ledgerId = LedgerId("ledger-a")
    val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    /** P7-08 08.B-1 (D-221; R3): deterministic write-path annotation audit instant. */
    val annotationCreatedAt = Instant.parse("2026-01-15T00:35:00Z")
    val transactionId = TransactionId("tx-expense-$suffix")
    val postingSetId = PostingSetId("posting-set-expense-$suffix")

    val identity = ManualExpenseRequestIdentity(ledgerId, RequestId(requestId))
    val snapshot =
        ManualExpenseRequestSnapshot(
            ledgerId = ledgerId,
            amount = money(3_580),
            categoryId = CategoryId("expense-category-breakfast"),
            paymentAccountId = AccountId("asset-bank-a"),
            occurredAt = occurredAt,
            note = "",
        )

    fun money(minorUnits: Long): Money = Money.ofMinor(minorUnits, CurrencyUnit("CNY", 2))

    fun receipt() =
        ConfirmedExpenseReceipt(
            confirmationId = ConfirmationId(confirmationId),
            transactionId = transactionId,
        )

    fun commit() = commitForLedger(LedgerId("ledger-a"))

    fun commitForLedger(commitLedgerId: LedgerId) =
        ConfirmedManualExpenseCommit(
            confirmationId = ConfirmationId(confirmationId),
            transaction = formalTransaction(commitLedgerId),
        )

    fun persistedRequest() =
        PersistedRequest(
            ledgerId = "ledger-a",
            requestId = identity.requestId.value,
            amountMinor = 3_580,
            currencyCode = "CNY",
            currencyPrecision = 2,
            categoryId = "expense-category-breakfast",
            paymentAccountId = "asset-bank-a",
            occurredAt = occurredAt.toString(),
            note = "",
            confirmationMarker = "explicit_manual_save",
            confirmationId = confirmationId,
            transactionId = transactionId.value,
        )

    fun persistedTransaction() =
        PersistedTransaction(
            transactionId.value,
            "ledger-a",
            TransactionKind.EXPENSE.name,
            "version-expense-$suffix-v1",
        )

    fun persistedVersion() =
        PersistedVersion(
            "version-expense-$suffix-v1",
            transactionId.value,
            1,
            postingSetId.value,
            occurredAt.toString(),
            occurredAt.toString(),
            occurredAt.toString(),
            null,
        )

    fun persistedPostings() =
        listOf(
            PersistedPosting(
                "posting-expense-$postingIdSuffix",
                0,
                "expense-account-breakfast",
                3_580,
                "CNY",
                2,
            ),
            PersistedPosting(
                "posting-bank-$postingIdSuffix",
                1,
                "asset-bank-a",
                -3_580,
                "CNY",
                2,
            ),
        )

    private fun formalTransaction(commitLedgerId: LedgerId): FormalTransaction {
        val versionId = TransactionVersionId("version-expense-$suffix-v1")
        val postingSet =
            assertSuccess(
                PostingSet.create(
                    postingSetId,
                    listOf(
                        Posting(
                            PostingId("posting-expense-$postingIdSuffix"),
                            AccountId("expense-account-breakfast"),
                            money(3_580),
                        ),
                        Posting(
                            PostingId("posting-bank-$postingIdSuffix"),
                            AccountId("asset-bank-a"),
                            money(-3_580),
                        ),
                    ),
                ),
            )
        return assertSuccess(
            FormalTransaction.create(
                transaction =
                    Transaction(
                        id = transactionId,
                        ledgerId = commitLedgerId,
                        kind = TransactionKind.EXPENSE,
                        currentVersionId = versionId,
                    ),
                versions =
                    listOf(
                        TransactionVersion(
                            id = versionId,
                            transactionId = transactionId,
                            versionNumber = 1,
                            postingSetId = postingSetId,
                            times = TransactionTimes.collapsed(occurredAt),
                        ),
                    ),
                postingSets = listOf(postingSet),
            ),
        )
    }
}

private fun <T> assertSuccess(result: DomainResult<T>): T = assertIs<DomainResult.Success<T>>(result).value
