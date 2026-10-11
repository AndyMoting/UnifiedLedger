package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.AnnotationRequestId
import com.unifiedledger.application.AnnotationRequestIdSource
import com.unifiedledger.application.CatalogItemIdSource
import com.unifiedledger.application.CatalogItemReferenceProbe
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualLendingCommit
import com.unifiedledger.application.ConfirmedManualLendingResult
import com.unifiedledger.application.ExecuteTagMerchantCommand
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualLendingBehavior
import com.unifiedledger.application.ManualLendingCommitResolution
import com.unifiedledger.application.ManualLendingRequestIdentity
import com.unifiedledger.application.ManualLendingRequestSnapshot
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualLendingCommitStatus
import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantRequestId
import com.unifiedledger.application.TagMerchantRequestIdSource
import com.unifiedledger.application.TransactionAnnotationResult
import com.unifiedledger.application.UpdateTransactionAnnotation
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingBehaviorCode
import com.unifiedledger.domain.LendingPosition
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
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-08 08.D A04 evidence (spec section 4.3 :176 "Unknown 后重开只 replay 原请求"; plan :184; the
 * acceptance registration belongs to the main agent — nothing here records a vector PASS).
 *
 * The Q1 gap this class closes: the existing B04 harness snapshots were annotation-blind (empty
 * tagIds, null merchant), so the unknown-recovery legs never proved that a NON-EMPTY association
 * rides the same replay identity. Both vectors below carry a real tag and merchant:
 *
 *  - the manual lending commit chain (the representative kind; the other four manual kinds share
 *    the same claim-first/replay shape, the coverage is registered honestly in the batch report):
 *    commit (the caller "crashes" before seeing the result) -> resolve -> the SAME request replay
 *    returns the original receipt with ZERO new writes, and the annotation rows stay in the same
 *    transaction as the financial ones (observed == the created version id);
 *  - the annotation command's own unknown-replay: a replay of the identical request returns the
 *    original receipt with zero writes.
 *
 * All data synthetic and anonymous with fixed instants.
 */
class P708UnknownReopenAnnotationReplayTest {

    @Test
    fun theUnknownLendingCommitReplaysTheOriginalReceiptAndKeepsTheAnnotationConsistent() {
        UnknownHarness().use { harness ->
            harness.createTag("tag-1")
            harness.createMerchant("merchant-1")
            val snapshot =
                harness.lendSnapshot(
                    counterpartyId = harness.createCounterparty("Alice"),
                    principalMinor = 5_000L,
                    tagIds = setOf(TagId("tag-1")),
                    merchantId = MerchantId("merchant-1"),
                )
            val identity = ManualLendingRequestIdentity(harness.ledgerId, RequestId("req-unknown-1"))

            // The commit lands (the caller "crashes" before seeing the outcome — the Unknown state).
            val created =
                assertIs<ConfirmedManualLendingResult.Created>(
                    harness.commit(identity, snapshot) { DomainResult.Success(harness.lendCommit("tx-unknown-1", 5_000L)) },
                )
            assertEquals(1L, harness.annotationRevisionCount())
            assertEquals(1L, harness.transactionCount())

            // Reopen resolves the SAME request against the stored snapshot: matching receipt.
            val resolver = ResolveManualLendingCommitStatus(harness.readPort)
            val resolution = resolver.resolve(harness.ledgerId, identity.requestId, snapshot)
            val matched = assertIs<ManualLendingCommitResolution.MatchingReceipt>(resolution)
            assertEquals(created.receipt, matched.receipt)

            // A snapshot differing ONLY in the annotation fields is a conflict, never a match:
            // the annotation columns participate in the unknown-resolution identity.
            assertIs<ManualLendingCommitResolution.SnapshotConflict>(
                resolver.resolve(harness.ledgerId, identity.requestId, snapshot.copy(tagIds = emptySet())),
            )
            assertIs<ManualLendingCommitResolution.SnapshotConflict>(
                resolver.resolve(harness.ledgerId, identity.requestId, snapshot.copy(merchantId = null)),
            )

            // The same-request replay (the only legal action after reopen, spec 4.3) returns the
            // original receipt with ZERO new writes.
            var callback = 0
            val replay =
                assertIs<ConfirmedManualLendingResult.NoChange>(
                    harness.commit(identity, snapshot) {
                        callback++
                        error("must not create")
                    },
                )
            assertEquals(0, callback)
            assertEquals(created.receipt, replay.receipt)
            assertEquals(1L, harness.annotationRevisionCount())
            assertEquals(1L, harness.annotationTagCount())
            assertEquals(1L, harness.transactionCount())
            assertEquals(1L, harness.versionCount())

            // The annotation is consistent with the financial write it was committed with: the
            // single revision observed EXACTLY the version the same transaction created.
            assertEquals(
                1L,
                harness.queryLong(
                    "SELECT count(*) FROM transaction_annotation_revision WHERE observed_transaction_version_id = 'tx-unknown-1-v1' " +
                        "AND merchant_id = 'merchant-1'",
                ),
            )
            assertEquals(
                1L,
                harness.queryLong("SELECT count(*) FROM transaction_annotation_tag WHERE tag_id = 'tag-1'"),
            )
            assertEquals(
                1L,
                harness.queryLong("SELECT count(*) FROM transaction_annotation_current WHERE transaction_id = 'tx-unknown-1' AND annotation_revision = 1"),
            )
        }
    }

    @Test
    fun theAnnotationCommandReplaysItsOwnUnknownCommitWithZeroWrites() {
        UnknownHarness().use { harness ->
            harness.createTag("tag-1")
            harness.createMerchant("merchant-1")
            harness.seedTransaction("tx-annot-1")
            val store = SqlDelightTransactionAnnotationStore.forPlatformConfiguredDatabase(harness.database)
            val useCase =
                UpdateTransactionAnnotation(
                    commitPort = store,
                    requestIdSource = AnnotationRequestIdSource { AnnotationRequestId("annot-unknown-1") },
                    clock = LedgerClock { Instant.parse("2026-03-05T02:00:00Z") },
                )
            val currentVersion = TransactionVersionId("version-tx-annot-1")
            val accepted =
                assertIs<TransactionAnnotationResult.Accepted>(
                    useCase.update(harness.ledgerId, TransactionId("tx-annot-1"), listOf(TagId("tag-1")), MerchantId("merchant-1"), 0L, currentVersion),
                )
            // The "crashed" caller re-sends the IDENTICAL request: the original receipt, zero writes.
            val replay =
                assertIs<TransactionAnnotationResult.NoChange>(
                    useCase.update(harness.ledgerId, TransactionId("tx-annot-1"), listOf(TagId("tag-1")), MerchantId("merchant-1"), 0L, currentVersion),
                )
            assertEquals(accepted.receipt, replay.receipt)
            assertEquals(1L, harness.queryLong("SELECT count(*) FROM transaction_annotation_revision"))
            assertEquals(1L, harness.queryLong("SELECT count(*) FROM transaction_annotation_command_receipt"))
            // A same-id request with a DIFFERENT association stays the typed identity conflict.
            assertIs<TransactionAnnotationResult.Conflict>(
                useCase.update(harness.ledgerId, TransactionId("tx-annot-1"), emptyList(), null, 1L, currentVersion),
            )
            assertEquals(1L, harness.queryLong("SELECT count(*) FROM transaction_annotation_revision"))
        }
    }
}

/**
 * Compact in-memory harness: the REAL manual-lending commit port (claim/receipt/annotation append)
 * over a real catalog, so the unknown-recovery replay rides the production paths.
 */
private class UnknownHarness : AutoCloseable {
    val ledgerId = LedgerId("ledger-p708-a04")
    val cny = CurrencyUnit("CNY", 2)
    val driver: JdbcSqliteDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, java.util.Properties().apply { setProperty("foreign_keys", "true") }).also {
            LedgerDatabase.Schema.create(it)
        }
    val database = LedgerDatabase(driver)
    val readPort: LedgerCurrentStateReadPort = SqlDelightLedgerCurrentStateReadAdapter(database)
    private val lendingPort = SqlDelightConfirmedManualLendingCommitPort(database, driver)
    private val directory = SqlDelightCounterpartyStore(database, driver)

    fun commit(
        identity: ManualLendingRequestIdentity,
        snapshot: ManualLendingRequestSnapshot,
        create: () -> DomainResult<ConfirmedManualLendingCommit>,
    ): ConfirmedManualLendingResult = lendingPort.commitOnce(identity, snapshot, Instant.parse("2026-03-01T00:05:00Z"), create)

    fun createCounterparty(name: String): CounterpartyId {
        val id = CounterpartyId("cp-${name.lowercase()}")
        assertIs<com.unifiedledger.application.CounterpartyCommandResult.Created>(
            directory.create(
                ledgerId = ledgerId,
                counterpartyId = id,
                name = name,
                receivableAccountId = AccountId("recv-${name.lowercase()}"),
                currency = cny,
            ),
        )
        return id
    }

    fun createTag(tagId: String) {
        val executor =
            ExecuteTagMerchantCommand(
                commitPort = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database),
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId("catalog-$tagId") },
                idSource = CatalogItemIdSource { tagId },
                referenceProbe = CatalogItemReferenceProbe { _, _, _ -> false },
            )
        assertIs<TagMerchantCommandResult.Accepted>(executor.createItem(ledgerId, CatalogItemKind.TAG, "标签-$tagId", 0L))
    }

    fun createMerchant(merchantId: String) {
        val executor =
            ExecuteTagMerchantCommand(
                commitPort = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database),
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId("catalog-$merchantId") },
                idSource = CatalogItemIdSource { merchantId },
                referenceProbe = CatalogItemReferenceProbe { _, _, _ -> false },
            )
        assertIs<TagMerchantCommandResult.Accepted>(executor.createItem(ledgerId, CatalogItemKind.MERCHANT, "商家-$merchantId", 0L))
    }

    fun lendSnapshot(
        counterpartyId: CounterpartyId,
        principalMinor: Long,
        tagIds: Set<TagId>,
        merchantId: MerchantId?,
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
            occurredAt = Instant.parse("2026-03-01T00:00:00Z"),
            note = "",
            tagIds = tagIds,
            merchantId = merchantId,
        )

    fun lendCommit(
        transactionId: String,
        principalMinor: Long,
    ): ConfirmedManualLendingCommit {
        val tx = TransactionId(transactionId)
        val versionId = TransactionVersionId("$transactionId-v1")
        val setId = PostingSetId("$transactionId-set")
        val set =
            assertIs<DomainResult.Success<PostingSet>>(
                PostingSet.create(
                    setId,
                    listOf(
                        Posting(PostingId("$transactionId-recv"), AccountId("recv-alice"), Money.ofMinor(principalMinor, cny)),
                        Posting(PostingId("$transactionId-fund"), AccountId("asset-bank"), Money.ofMinor(-principalMinor, cny)),
                    ),
                ),
            ).value
        val formal =
            assertIs<DomainResult.Success<FormalTransaction>>(
                FormalTransaction.create(
                    Transaction(tx, ledgerId, TransactionKind.LEND, versionId),
                    listOf(TransactionVersion(versionId, tx, 1, setId, TransactionTimes.collapsed(Instant.parse("2026-03-01T00:00:00Z")), "")),
                    listOf(set),
                ),
            ).value
        val prior =
            database.ledgerQueries.selectLendingPositionHistory(ledgerId.value, "cp-alice").executeAsList().map {
                com.unifiedledger.domain.LendingPositionHistoryEntry(
                    id = it.entry_id,
                    behaviorCode = LendingBehaviorCode.LEND,
                    amountMinor = it.amount_minor,
                    principalBalanceAfterMinor = it.principal_balance_after_minor,
                    transactionId = TransactionId(it.transaction_id),
                    occurredAt = Instant.parse(it.occurred_at),
                )
            }
        val entry =
            com.unifiedledger.domain.LendingPositionHistoryEntry(
                id = "entry-$transactionId",
                behaviorCode = LendingBehaviorCode.LEND,
                amountMinor = principalMinor,
                principalBalanceAfterMinor = principalMinor,
                transactionId = tx,
                occurredAt = Instant.parse("2026-03-01T00:00:00Z"),
            )
        val position =
            assertIs<DomainResult.Success<LendingPosition>>(
                com.unifiedledger.domain.createLendingPosition(
                    id = "position-cp-alice",
                    counterpartyId = "cp-alice",
                    receivableAccountId = AccountId("recv-alice"),
                    currency = cny,
                    principalBalanceMinor = principalMinor,
                    history = prior + entry,
                ),
            ).value
        return ConfirmedManualLendingCommit(
            confirmationId = ConfirmationId("confirmation-$transactionId"),
            transaction = formal,
            position = position,
        )
    }

    /** A minimal effective transaction chain for the annotation-command vector. */
    fun seedTransaction(transactionId: String) {
        database.ledgerQueries.insertTransaction(transactionId, ledgerId.value, "EXPENSE")
        database.ledgerQueries.insertPostingSet("posting-set-$transactionId", ledgerId.value)
        database.ledgerQueries.insertTransactionVersion(
            "version-$transactionId",
            transactionId,
            ledgerId.value,
            1L,
            "posting-set-$transactionId",
            "2026-03-05T02:00:00Z",
            "2026-03-05T02:00:00Z",
            "2026-03-05T02:00:00Z",
            null,
        )
        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId.value, "version-$transactionId")
    }

    fun annotationRevisionCount(): Long = queryLong("SELECT count(*) FROM transaction_annotation_revision")

    fun annotationTagCount(): Long = queryLong("SELECT count(*) FROM transaction_annotation_tag")

    fun transactionCount(): Long = queryLong("SELECT count(*) FROM ledger_transaction")

    fun versionCount(): Long = queryLong("SELECT count(*) FROM transaction_version")

    fun queryLong(sql: String): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    override fun close() = driver.close()
}
