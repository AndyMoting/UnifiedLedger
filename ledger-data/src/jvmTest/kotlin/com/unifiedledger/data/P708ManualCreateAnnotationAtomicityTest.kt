package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedManualIncomeResult
import com.unifiedledger.application.ConfirmedManualLendingCommit
import com.unifiedledger.application.ConfirmedManualLendingResult
import com.unifiedledger.application.ConfirmedManualTransferCommit
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
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
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.Money
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
import com.unifiedledger.domain.TransferPostingRole
import com.unifiedledger.domain.createLendingPosition
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * P7-08 08.B-1 (D-221) manual-create annotation atomicity and command-face evidence.
 *
 * P708-A05 (spec sections 3.2/4.3): the five manual-create kinds (EXPENSE/INCOME/TRANSFER/LEND/
 * COLLECT) each write their `annotation_revision = 1` aggregate UNCONDITIONALLY in the SAME
 * transaction as the financial write (ruling R1); forcing that annotation write to fail rolls back
 * the WHOLE transaction (no formal transaction, no request receipt, no annotation rows =
 * all-or-nothing); and the legacy four-point compatibility holds, plus the R-5 fifth point (an
 * empty-association create replays equivalently with the same receipt and zero writes).
 *
 * P708-A04 (spec sections 3.1/3.2/4.3, command/plumbing face): same-request replay returns the
 * original receipt with zero writes (a later `createdAt` must not conflict, ruling R-4); the same
 * requestId with a DIFFERENT annotation is a `RequestIdentityConflict` with zero writes.
 */
class P708ManualCreateAnnotationAtomicityTest {
    private val ledgerId = LedgerId("ledger-a05")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-05-01T03:00:00Z")
    private val createdAt = Instant.parse("2026-05-01T03:05:00Z")
    private val tagA = TagId("tag-a")
    private val tagB = TagId("tag-b")
    private val merchantId = MerchantId("merchant-1")

    // ---------------------------------------------------------------------------------------------
    // P708-A05: the five-kind support matrix
    // ---------------------------------------------------------------------------------------------

    @Test
    fun everyManualCreateKindWritesRevisionOneInTheSameTransaction() {
        fiveKinds().forEach { kind ->
            val requestId = "request-${kind.name.lowercase()}"
            withHarness { harness ->
                harness.seedCatalog()
                val result = kind.commit(harness, requestId, setOf(tagA, tagB), merchantId, createdAt)
                val transactionId = kind.transactionIdOf(result)
                // The annotation aggregate landed in the SAME transaction: revision 1, the two
                // tags, the merchant and the current pointer all exist for the new transaction.
                assertEquals(
                    1L,
                    harness.queryLong(
                        "SELECT count(*) FROM transaction_annotation_revision " +
                            "WHERE ledger_id = '${ledgerId.value}' AND transaction_id = '$transactionId' " +
                            "AND annotation_revision = 1 AND merchant_id = '${merchantId.value}' " +
                            "AND observed_transaction_version_id = '$transactionId-v1' AND created_at = '$createdAt'",
                    ),
                    kind.name,
                )
                assertEquals(
                    2L,
                    harness.queryLong("SELECT count(*) FROM transaction_annotation_tag WHERE transaction_id = '$transactionId' AND annotation_revision = 1"),
                    kind.name,
                )
                assertEquals(
                    1L,
                    harness.queryLong("SELECT annotation_revision FROM transaction_annotation_current WHERE transaction_id = '$transactionId'"),
                    kind.name,
                )
                // The request row carries the canonical claim encoding (R-5/R2).
                assertEquals(
                    "tag-a\u001Ftag-b",
                    harness.queryText("SELECT annotation_tag_ids FROM ${kind.requestTable} WHERE request_id = '$requestId'"),
                    kind.name,
                )
                assertEquals(
                    merchantId.value,
                    harness.queryText("SELECT annotation_merchant_id FROM ${kind.requestTable} WHERE request_id = '$requestId'"),
                    kind.name,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // P708-A05: fault injection -> the WHOLE transaction rolls back (all-or-nothing)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aForcedAnnotationWriteFailureRollsBackTheWholeTransactionForEveryKind() {
        fiveKinds().forEach { kind ->
            val requestId = "request-fault-${kind.name.lowercase()}"
            withHarness(annotationWriteFails = true) { harness ->
                harness.seedCatalog()
                assertFailsWith<java.sql.SQLException> {
                    kind.commit(harness, requestId, setOf(tagA), merchantId, createdAt)
                }
                // No half-success: the claim row, the request receipt, the formal rows and the
                // annotation rows are all gone, so the identity stays retryable.
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM ${kind.requestTable} WHERE request_id = '$requestId'"), kind.name)
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM ${kind.receiptTable} WHERE request_id = '$requestId'"), kind.name)
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM ledger_transaction"), kind.name)
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM transaction_version"), kind.name)
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM posting"), kind.name)
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM transaction_annotation_revision"), kind.name)
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM transaction_annotation_current"), kind.name)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // P708-A04: command face (replay / conflict / empty-association replay)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun sameRequestReplayReturnsTheOriginalReceiptWithZeroWrites() {
        fiveKinds().forEach { kind ->
            val requestId = "request-replay-${kind.name.lowercase()}"
            withHarness { harness ->
                harness.seedCatalog()
                val tags = setOf(tagA, tagB)
                val created = kind.commit(harness, requestId, tags, merchantId, createdAt)
                val receipt = kind.receiptOf(created)
                val before = harness.allCounts()
                val replay = kind.commit(harness, requestId, tags, merchantId, createdAt)
                assertEquals(receipt, kind.noChangeReceipt(replay), kind.name)
                assertEquals(before, harness.allCounts(), "${kind.name} replay must not write")
                // R-4: replay equivalence ignores createdAt (a later clock sample must not conflict).
                val replayLaterClock = kind.commit(harness, requestId, tags, merchantId, createdAt + 1.hours)
                assertEquals(receipt, kind.noChangeReceipt(replayLaterClock), kind.name)
                assertEquals(before, harness.allCounts(), "${kind.name} later-clock replay must not write")
            }
        }
    }

    @Test
    fun sameRequestWithADifferentAnnotationIsAnIdentityConflictWithZeroWrites() {
        fiveKinds().forEach { kind ->
            val requestId = "request-conflict-${kind.name.lowercase()}"
            withHarness { harness ->
                harness.seedCatalog()
                kind.commit(harness, requestId, setOf(tagA), merchantId, createdAt)
                val before = harness.allCounts()
                kind.assertConflict(kind.commit(harness, requestId, setOf(tagA, tagB), merchantId, createdAt))
                // The merchant change alone is also a conflict (structured per-column match, R-2).
                kind.assertConflict(kind.commit(harness, requestId, setOf(tagA), null, createdAt))
                assertEquals(before, harness.allCounts(), "${kind.name} conflict must not write")
            }
        }
    }

    @Test
    fun emptyAssociationCreateReplaysEquivalentlyWithTheSameReceiptAndZeroWrites() {
        fiveKinds().forEach { kind ->
            val requestId = "request-empty-${kind.name.lowercase()}"
            withHarness { harness ->
                // No catalog needed: an empty association never consults the catalog.
                val created = kind.commit(harness, requestId, emptySet(), null, createdAt)
                val receipt = kind.receiptOf(created)
                val transactionId = kind.transactionIdOf(created)
                // R1: the revision-1 aggregate is written UNCONDITIONALLY, even with an empty set.
                assertEquals(
                    1L,
                    harness.queryLong("SELECT count(*) FROM transaction_annotation_revision WHERE transaction_id = '$transactionId' AND annotation_revision = 1 AND merchant_id IS NULL"),
                    kind.name,
                )
                assertEquals(0L, harness.queryLong("SELECT count(*) FROM transaction_annotation_tag WHERE transaction_id = '$transactionId'"), kind.name)
                // R-5: the empty set encodes to NULL in the claim row.
                assertEquals(null, harness.queryText("SELECT annotation_tag_ids FROM ${kind.requestTable} WHERE request_id = '$requestId'"), kind.name)
                assertEquals(null, harness.queryText("SELECT annotation_merchant_id FROM ${kind.requestTable} WHERE request_id = '$requestId'"), kind.name)
                val before = harness.allCounts()
                val replay = kind.commit(harness, requestId, emptySet(), null, createdAt)
                assertEquals(receipt, kind.noChangeReceipt(replay), kind.name)
                assertEquals(before, harness.allCounts(), "${kind.name} empty replay must not write")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // P708-A05: legacy compatibility (four points) + the R-5 fifth point
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aLegacyPreV35RowReplaysEquivalentlyWithZeroWrites() {
        // Point 1: a legacy request row (NULL annotation columns, no annotation aggregate) replays
        // equivalently against an empty-association snapshot and writes nothing.
        withHarness { harness ->
            val requestId = "request-legacy"
            harness.seedLegacyExpenseRequest(requestId)
            val before = harness.allCounts()
            val replay =
                harness.expensePort.commitOnce(
                    ManualExpenseRequestIdentity(ledgerId, RequestId(requestId)),
                    expenseSnapshot(note = "lunch"),
                    createdAt,
                ) { error("legacy replay must not create a formal transaction") }
            val noChange = assertIs<ConfirmedManualExpenseResult.NoChange>(replay)
            assertEquals(
                ConfirmedExpenseReceipt(ConfirmationId("conf-legacy"), TransactionId("tx-legacy")),
                noChange.receipt,
            )
            assertEquals(before, harness.allCounts())
        }
    }

    @Test
    fun aLegacyRowWithADifferentAnnotationIsAnIdentityConflictAndWritesNothing() {
        // Point 2: the same requestId but a different (non-empty) annotation on a legacy row is an
        // identity conflict with zero writes.
        withHarness { harness ->
            val requestId = "request-legacy-conflict"
            harness.seedLegacyExpenseRequest(requestId)
            harness.seedCatalog()
            val before = harness.allCounts()
            val conflict =
                harness.expensePort.commitOnce(
                    ManualExpenseRequestIdentity(ledgerId, RequestId(requestId)),
                    expenseSnapshot(note = "lunch", tagIds = setOf(tagA)),
                    createdAt,
                ) { error("identity conflict must not create") }
            assertIs<ConfirmedManualExpenseResult.RequestIdentityConflict>(conflict)
            assertEquals(before, harness.allCounts())
        }
    }

    @Test
    fun aNewClaimWritesTheCompleteSnapshotAndReadingLegacyRowsNeedsNoMigration() {
        // Point 3: a brand-new claim writes the complete row (financial columns + canonical
        // annotation encoding). Point 4: the same schema serves legacy NULL rows and new rows, so
        // reading either needs zero migration or backfill.
        withHarness { harness ->
            harness.seedCatalog()
            harness.seedLegacyExpenseRequest("request-legacy-shape")
            assertEquals(
                1L,
                harness.queryLong("SELECT count(*) FROM manual_expense_request WHERE request_id = 'request-legacy-shape' AND annotation_tag_ids IS NULL AND annotation_merchant_id IS NULL"),
            )
            val requestId = "request-new"
            val created =
                harness.expensePort.commitOnce(
                    ManualExpenseRequestIdentity(ledgerId, RequestId(requestId)),
                    expenseSnapshot(note = "coffee", tagIds = setOf(tagB, tagA), merchantId = merchantId),
                    createdAt,
                ) { DomainResult.Success(expenseCommit("tx-new")) }
            assertIs<ConfirmedManualExpenseResult.Created>(created)
            assertEquals(
                1L,
                harness.queryLong(
                    "SELECT count(*) FROM manual_expense_request WHERE request_id = '$requestId' " +
                        "AND amount_minor = 3580 AND currency_code = 'CNY' AND currency_precision = 2 " +
                        "AND category_id = 'expense-category' AND payment_account_id = 'asset-bank' " +
                        "AND note = 'coffee' AND confirmation_marker = 'explicit_manual_save' " +
                        // A non-empty set is the deduplicated, stable-id-sorted join (A before B).
                        "AND annotation_tag_ids = 'tag-a\u001Ftag-b' AND annotation_merchant_id = 'merchant-1'",
                ),
            )
        }
    }

    @Test
    fun anEmptyAssociationCreateReplaysEquivalentlyAsTheFifthPoint() {
        // R-5 fifth point on the expense port: an empty-association create replays equivalently with
        // the same receipt and zero writes, even when the replay carries a different clock sample.
        withHarness { harness ->
            val requestId = "request-empty-fifth"
            val created =
                harness.expensePort.commitOnce(
                    ManualExpenseRequestIdentity(ledgerId, RequestId(requestId)),
                    expenseSnapshot(note = "water"),
                    createdAt,
                ) { DomainResult.Success(expenseCommit("tx-fifth")) }
            assertIs<ConfirmedManualExpenseResult.Created>(created)
            val before = harness.allCounts()
            val replay =
                harness.expensePort.commitOnce(
                    ManualExpenseRequestIdentity(ledgerId, RequestId(requestId)),
                    expenseSnapshot(note = "water"),
                    createdAt + 2.hours,
                ) { error("empty-association replay must not create") }
            assertIs<ConfirmedManualExpenseResult.NoChange>(replay)
            assertEquals(before, harness.allCounts())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------------

    private fun fiveKinds(): List<Kind> =
        listOf(
            Kind(
                name = "EXPENSE",
                requestTable = "manual_expense_request",
                receiptTable = "confirmed_expense_receipt",
                commit = { h, id, tags, merchant, created ->
                    h.expensePort.commitOnce(
                        ManualExpenseRequestIdentity(ledgerId, RequestId(id)),
                        expenseSnapshot(note = "expense", tagIds = tags, merchantId = merchant),
                        created,
                    ) { DomainResult.Success(expenseCommit("tx-$id")) }
                },
                receiptOf = { assertIs<ConfirmedManualExpenseResult.Created>(it).receipt },
                transactionIdOf = { assertIs<ConfirmedManualExpenseResult.Created>(it).receipt.transactionId.value },
                noChangeReceipt = { (assertIs<ConfirmedManualExpenseResult.NoChange>(it)).receipt },
                assertConflict = { assertIs<ConfirmedManualExpenseResult.RequestIdentityConflict>(it) },
            ),
            Kind(
                name = "INCOME",
                requestTable = "manual_income_request",
                receiptTable = "confirmed_income_receipt",
                commit = { h, id, tags, merchant, created ->
                    h.incomePort.commitOnce(
                        ManualIncomeRequestIdentity(ledgerId, RequestId(id)),
                        incomeSnapshot(tagIds = tags, merchantId = merchant),
                        created,
                    ) { DomainResult.Success(incomeCommit("tx-$id")) }
                },
                receiptOf = { assertIs<ConfirmedManualIncomeResult.Created>(it).receipt },
                transactionIdOf = { assertIs<ConfirmedManualIncomeResult.Created>(it).receipt.transactionId.value },
                noChangeReceipt = { assertIs<ConfirmedManualIncomeResult.NoChange>(it).receipt },
                assertConflict = { assertIs<ConfirmedManualIncomeResult.RequestIdentityConflict>(it) },
            ),
            Kind(
                name = "TRANSFER",
                requestTable = "manual_transfer_request",
                receiptTable = "confirmed_transfer_receipt",
                commit = { h, id, tags, merchant, created ->
                    h.transferPort.commitOnce(
                        ManualTransferRequestIdentity(ledgerId, RequestId(id)),
                        transferSnapshot(tagIds = tags, merchantId = merchant),
                        created,
                    ) { DomainResult.Success(transferCommit("tx-$id")) }
                },
                receiptOf = { assertIs<ConfirmedManualTransferResult.Created>(it).receipt },
                transactionIdOf = { assertIs<ConfirmedManualTransferResult.Created>(it).receipt.transactionId.value },
                noChangeReceipt = { assertIs<ConfirmedManualTransferResult.NoChange>(it).receipt },
                assertConflict = { assertIs<ConfirmedManualTransferResult.RequestIdentityConflict>(it) },
            ),
            Kind(
                name = "LEND",
                requestTable = "manual_lending_request",
                receiptTable = "confirmed_lending_receipt",
                commit = { h, id, tags, merchant, created ->
                    h.lendingPort.commitOnce(
                        ManualLendingRequestIdentity(ledgerId, RequestId(id)),
                        lendSnapshot(tagIds = tags, merchantId = merchant),
                        created,
                    ) { DomainResult.Success(lendCommit("tx-$id")) }
                },
                receiptOf = { assertIs<ConfirmedManualLendingResult.Created>(it).receipt },
                transactionIdOf = { assertIs<ConfirmedManualLendingResult.Created>(it).receipt.transactionId.value },
                noChangeReceipt = { assertIs<ConfirmedManualLendingResult.NoChange>(it).receipt },
                assertConflict = { assertIs<ConfirmedManualLendingResult.RequestIdentityConflict>(it) },
            ),
            Kind(
                name = "COLLECT",
                requestTable = "manual_lending_request",
                receiptTable = "confirmed_lending_receipt",
                commit = { h, id, tags, merchant, created ->
                    h.lendingPort.commitOnce(
                        ManualLendingRequestIdentity(ledgerId, RequestId(id)),
                        collectSnapshot(tagIds = tags, merchantId = merchant),
                        created,
                    ) { DomainResult.Success(collectCommit("tx-$id")) }
                },
                receiptOf = { assertIs<ConfirmedManualLendingResult.Created>(it).receipt },
                transactionIdOf = { assertIs<ConfirmedManualLendingResult.Created>(it).receipt.transactionId.value },
                noChangeReceipt = { assertIs<ConfirmedManualLendingResult.NoChange>(it).receipt },
                assertConflict = { assertIs<ConfirmedManualLendingResult.RequestIdentityConflict>(it) },
            ),
        )

    private fun expenseSnapshot(
        note: String,
        tagIds: Set<TagId> = emptySet(),
        merchantId: MerchantId? = null,
    ) = ManualExpenseRequestSnapshot(
        ledgerId = ledgerId,
        amount = Money.ofMinor(3_580, cny),
        categoryId = CategoryId("expense-category"),
        paymentAccountId = AccountId("asset-bank"),
        occurredAt = occurredAt,
        note = note,
        tagIds = tagIds,
        merchantId = merchantId,
    )

    private fun incomeSnapshot(
        tagIds: Set<TagId> = emptySet(),
        merchantId: MerchantId? = null,
    ) = ManualIncomeRequestSnapshot(
        ledgerId = ledgerId,
        amount = Money.ofMinor(12_345, cny),
        categoryId = CategoryId("income-category"),
        receivingAccountId = AccountId("asset-bank"),
        occurredAt = occurredAt,
        note = "salary",
        tagIds = tagIds,
        merchantId = merchantId,
    )

    private fun transferSnapshot(
        tagIds: Set<TagId> = emptySet(),
        merchantId: MerchantId? = null,
    ) = ManualTransferRequestSnapshot(
        ledgerId = ledgerId,
        sourceAccountId = AccountId("asset-a"),
        destinationAccountId = AccountId("asset-b"),
        destinationCredit = Money.ofMinor(10_000, cny),
        fee = Money.ofMinor(200, cny),
        feeCategoryId = CategoryId("fee-category"),
        occurredAt = occurredAt,
        note = "move",
        tagIds = tagIds,
        merchantId = merchantId,
    )

    private fun lendSnapshot(
        tagIds: Set<TagId> = emptySet(),
        merchantId: MerchantId? = null,
    ) = ManualLendingRequestSnapshot(
        ledgerId = ledgerId,
        behavior = ManualLendingBehavior.LEND,
        counterpartyId = CounterpartyId("cp-alice"),
        principalAccountId = AccountId("asset-bank"),
        amount = Money.ofMinor(10_000, cny),
        interest = Money.ofMinor(0, cny),
        fee = Money.ofMinor(0, cny),
        totalReceived = null,
        interestCategoryId = null,
        occurredAt = occurredAt,
        note = "lend",
        tagIds = tagIds,
        merchantId = merchantId,
    )

    private fun collectSnapshot(
        tagIds: Set<TagId> = emptySet(),
        merchantId: MerchantId? = null,
    ) = ManualLendingRequestSnapshot(
        ledgerId = ledgerId,
        behavior = ManualLendingBehavior.COLLECT,
        counterpartyId = CounterpartyId("cp-alice"),
        principalAccountId = AccountId("asset-wallet"),
        amount = Money.ofMinor(4_000, cny),
        interest = Money.ofMinor(500, cny),
        fee = Money.ofMinor(0, cny),
        totalReceived = Money.ofMinor(4_500, cny),
        interestCategoryId = CategoryId("income-interest"),
        occurredAt = occurredAt,
        note = "collect",
        tagIds = tagIds,
        merchantId = merchantId,
    )

    private fun expenseCommit(transactionId: String): ConfirmedManualExpenseCommit {
        val set =
            success(
                PostingSet.create(
                    PostingSetId("$transactionId-set"),
                    listOf(
                        Posting(PostingId("$transactionId-expense"), AccountId("expense-account"), Money.ofMinor(3_580, cny)),
                        Posting(PostingId("$transactionId-bank"), AccountId("asset-bank"), Money.ofMinor(-3_580, cny)),
                    ),
                ),
            )
        return ConfirmedManualExpenseCommit(ConfirmationId("conf-$transactionId"), formal(transactionId, TransactionKind.EXPENSE, set, "expense"))
    }

    private fun incomeCommit(transactionId: String): ConfirmedManualIncomeCommit {
        val set =
            success(
                PostingSet.create(
                    PostingSetId("$transactionId-set"),
                    listOf(
                        Posting(PostingId("$transactionId-bank"), AccountId("asset-bank"), Money.ofMinor(12_345, cny)),
                        Posting(PostingId("$transactionId-income"), AccountId("income-account"), Money.ofMinor(-12_345, cny)),
                    ),
                ),
            )
        return ConfirmedManualIncomeCommit(ConfirmationId("conf-$transactionId"), formal(transactionId, TransactionKind.INCOME, set, "salary"))
    }

    private fun transferCommit(transactionId: String): ConfirmedManualTransferCommit {
        val set =
            success(
                PostingSet.create(
                    PostingSetId("$transactionId-set"),
                    listOf(
                        Posting(PostingId("$transactionId-out"), AccountId("asset-a"), Money.ofMinor(-10_200, cny)),
                        Posting(PostingId("$transactionId-in"), AccountId("asset-b"), Money.ofMinor(10_000, cny)),
                        Posting(PostingId("$transactionId-fee"), AccountId("expense-account"), Money.ofMinor(200, cny)),
                    ),
                ),
            )
        val transfer =
            AccountTransfer(
                formalTransaction = formal(transactionId, TransactionKind.ACCOUNT_TRANSFER, set, "move"),
                postings =
                    listOf(
                        AccountTransferPosting(Posting(PostingId("$transactionId-out"), AccountId("asset-a"), Money.ofMinor(-10_200, cny)), TransferPostingRole.PRINCIPAL_OUT),
                        AccountTransferPosting(Posting(PostingId("$transactionId-in"), AccountId("asset-b"), Money.ofMinor(10_000, cny)), TransferPostingRole.PRINCIPAL_IN),
                        AccountTransferPosting(Posting(PostingId("$transactionId-fee"), AccountId("expense-account"), Money.ofMinor(200, cny)), TransferPostingRole.FEE, CategoryId("fee-category")),
                    ),
                reportEffects =
                    AccountTransferReportEffects(
                        consumptionMinor = 200,
                        ordinaryExpenseMinor = 200,
                        cashOutflowMinor = 200,
                        ordinaryIncomeMinor = 0,
                        cashInflowMinor = 0,
                        principalConsumptionMinor = 0,
                        principalExternalCashFlowMinor = 0,
                        internalTransferMinor = 10_000,
                        netWorthChangeMinor = -200,
                    ),
            )
        return ConfirmedManualTransferCommit(ConfirmationId("conf-$transactionId"), transfer)
    }

    private fun lendCommit(transactionId: String): ConfirmedManualLendingCommit {
        val set =
            success(
                PostingSet.create(
                    PostingSetId("$transactionId-set"),
                    listOf(
                        Posting(PostingId("$transactionId-recv"), AccountId("recv-alice"), Money.ofMinor(10_000, cny)),
                        Posting(PostingId("$transactionId-fund"), AccountId("asset-bank"), Money.ofMinor(-10_000, cny)),
                    ),
                ),
            )
        return ConfirmedManualLendingCommit(
            confirmationId = ConfirmationId("conf-$transactionId"),
            transaction = formal(transactionId, TransactionKind.LEND, set, "lend"),
            position =
                position(
                    balanceAfter = 10_000,
                    history =
                        listOf(
                            LendingPositionHistoryEntry("entry-$transactionId", LendingBehaviorCode.LEND, 10_000, 10_000, TransactionId(transactionId), occurredAt),
                        ),
                ),
        )
    }

    private fun collectCommit(transactionId: String): ConfirmedManualLendingCommit {
        val set =
            success(
                PostingSet.create(
                    PostingSetId("$transactionId-set"),
                    listOf(
                        Posting(PostingId("$transactionId-dest"), AccountId("asset-wallet"), Money.ofMinor(4_500, cny)),
                        Posting(PostingId("$transactionId-principal"), AccountId("recv-alice"), Money.ofMinor(-4_000, cny)),
                        Posting(PostingId("$transactionId-interest"), AccountId("income-account"), Money.ofMinor(-500, cny)),
                    ),
                ),
            )
        return ConfirmedManualLendingCommit(
            confirmationId = ConfirmationId("conf-$transactionId"),
            transaction = formal(transactionId, TransactionKind.COLLECT, set, "collect"),
            // A standalone collect needs a non-negative running balance, so the position carries a
            // synthetic prior lend entry (only the last history row is persisted, with an FK to the
            // just-committed collect transaction).
            position =
                position(
                    balanceAfter = 6_000,
                    history =
                        listOf(
                            LendingPositionHistoryEntry("entry-prior-lend", LendingBehaviorCode.LEND, 10_000, 10_000, TransactionId("tx-prior-lend"), occurredAt),
                            LendingPositionHistoryEntry("entry-$transactionId", LendingBehaviorCode.COLLECT, -4_000, 6_000, TransactionId(transactionId), occurredAt),
                        ),
                ),
        )
    }

    private fun position(
        balanceAfter: Long,
        history: List<LendingPositionHistoryEntry>,
    ): LendingPosition =
        success(
            createLendingPosition(
                id = "position-cp-alice",
                counterpartyId = "cp-alice",
                receivableAccountId = AccountId("recv-alice"),
                currency = cny,
                principalBalanceMinor = balanceAfter,
                history = history,
            ),
        )

    private fun formal(
        transactionId: String,
        kind: TransactionKind,
        set: PostingSet,
        note: String,
    ): FormalTransaction {
        val tx = TransactionId(transactionId)
        val versionId = TransactionVersionId("$transactionId-v1")
        return success(
            FormalTransaction.create(
                Transaction(tx, ledgerId, kind, versionId),
                listOf(TransactionVersion(versionId, tx, 1, set.id, TransactionTimes.collapsed(occurredAt), note)),
                listOf(set),
            ),
        )
    }

    private fun <T> success(result: DomainResult<T>): T = assertIs<DomainResult.Success<T>>(result).value

    private fun withHarness(
        annotationWriteFails: Boolean = false,
        block: (Harness) -> Unit,
    ) {
        val base = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteProperties())
        val driver: SqlDriver = if (annotationWriteFails) FailingAnnotationDriver(base) else base
        try {
            LedgerDatabase.Schema.create(driver)
            val harness = Harness(driver, LedgerDatabase(driver))
            // The LEND/COLLECT position rows carry FKs to the counterparty and its receivable
            // catalog account, so every kind needs that minimal baseline regardless of association.
            harness.seedLendingBaseline()
            block(harness)
        } finally {
            base.close()
        }
    }

    private fun sqliteProperties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

    private class Harness(
        val driver: SqlDriver,
        val database: LedgerDatabase,
    ) {
        val expensePort = SqlDelightConfirmedManualExpenseCommitPort(database, driver)
        val incomePort = SqlDelightConfirmedManualIncomeCommitPort(database, driver)
        val transferPort = SqlDelightConfirmedManualTransferCommitPort(database, driver)
        val lendingPort = SqlDelightConfirmedManualLendingCommitPort(database, driver)

        fun seedCatalog() {
            execute("INSERT INTO catalog_item_version(ledger_id, version) VALUES ('ledger-a05', 1)")
            execute("INSERT INTO catalog_tag(ledger_id, tag_id, active, tombstoned, revision) VALUES ('ledger-a05', 'tag-a', 1, 0, 1)")
            execute("INSERT INTO catalog_tag(ledger_id, tag_id, active, tombstoned, revision) VALUES ('ledger-a05', 'tag-b', 1, 0, 1)")
            execute("INSERT INTO catalog_merchant(ledger_id, merchant_id, active, tombstoned, revision) VALUES ('ledger-a05', 'merchant-1', 1, 0, 1)")
        }

        fun seedLendingBaseline() {
            execute("INSERT INTO counterparty(ledger_id, counterparty_id, current_name, receivable_account_id, active) VALUES ('ledger-a05', 'cp-alice', 'Alice', 'recv-alice', 1)")
            execute(
                "INSERT INTO catalog_account(ledger_id, account_id, name, kind, currency_code, currency_precision, owned_by_user, real_account, system_role, hidden, active) " +
                    "VALUES ('ledger-a05', 'recv-alice', 'Alice receivable', 'ASSET', 'CNY', 2, 0, 0, NULL, 0, 1)",
            )
        }

        /** A pre-v35 committed manual expense request + receipt with NULL annotation columns. */
        fun seedLegacyExpenseRequest(requestId: String) {
            execute("INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-legacy', 'ledger-a05', 'EXPENSE')")
            execute("INSERT INTO posting_set VALUES ('ps-legacy', 'ledger-a05')")
            execute(
                "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                    "VALUES ('tx-legacy-v1', 'tx-legacy', 'ledger-a05', 1, 'ps-legacy', '2026-05-01T03:00:00Z', '2026-05-01T03:00:00Z', '2026-05-01T03:00:00Z', 'lunch')",
            )
            execute("INSERT INTO ledger_transaction_current_version VALUES ('tx-legacy', 'ledger-a05', 'tx-legacy-v1')")
            execute(
                "INSERT INTO manual_expense_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker) " +
                    "VALUES ('ledger-a05', '$requestId', 3580, 'CNY', 2, 'expense-category', 'asset-bank', '2026-05-01T03:00:00Z', 'lunch', 'explicit_manual_save')",
            )
            execute("INSERT INTO confirmed_expense_receipt(ledger_id, request_id, confirmation_id, transaction_id) VALUES ('ledger-a05', '$requestId', 'conf-legacy', 'tx-legacy')")
        }

        fun allCounts(): List<Long> =
            listOf(
                queryLong("SELECT count(*) FROM manual_expense_request"),
                queryLong("SELECT count(*) FROM confirmed_expense_receipt"),
                queryLong("SELECT count(*) FROM manual_income_request"),
                queryLong("SELECT count(*) FROM confirmed_income_receipt"),
                queryLong("SELECT count(*) FROM manual_transfer_request"),
                queryLong("SELECT count(*) FROM confirmed_transfer_receipt"),
                queryLong("SELECT count(*) FROM manual_lending_request"),
                queryLong("SELECT count(*) FROM confirmed_lending_receipt"),
                queryLong("SELECT count(*) FROM ledger_transaction"),
                queryLong("SELECT count(*) FROM transaction_version"),
                queryLong("SELECT count(*) FROM posting"),
                queryLong("SELECT count(*) FROM transaction_annotation_revision"),
                queryLong("SELECT count(*) FROM transaction_annotation_tag"),
                queryLong("SELECT count(*) FROM transaction_annotation_current"),
            )

        fun execute(sql: String) {
            driver.execute(null, sql, 0)
        }

        fun queryLong(sql: String): Long =
            driver
                .executeQuery(
                    null,
                    sql,
                    { cursor ->
                        check(cursor.next().value)
                        QueryResult.Value(requireNotNull(cursor.getLong(0)))
                    },
                    0,
                ).value

        fun queryText(sql: String): String? =
            driver
                .executeQuery(
                    null,
                    sql,
                    { cursor ->
                        check(cursor.next().value)
                        QueryResult.Value(cursor.getString(0))
                    },
                    0,
                ).value
    }

    /** Wraps a driver and fails the annotation-revision INSERT, injecting a mid-transaction fault. */
    private class FailingAnnotationDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (sql.contains("INSERT INTO transaction_annotation_revision")) {
                throw java.sql.SQLException("injected annotation write failure")
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    private class Kind(
        val name: String,
        val requestTable: String,
        val receiptTable: String,
        val commit: (Harness, String, Set<TagId>, MerchantId?, Instant) -> Any,
        val receiptOf: (Any) -> Any,
        val transactionIdOf: (Any) -> String,
        val noChangeReceipt: (Any) -> Any,
        val assertConflict: (Any) -> Unit,
    )
}
