package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.AnnotationFailureCode
import com.unifiedledger.application.CatalogItemIdSource
import com.unifiedledger.application.CatalogItemReferenceProbe
import com.unifiedledger.application.ExecuteTagMerchantCommand
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantRequestId
import com.unifiedledger.application.TagMerchantRequestIdSource
import com.unifiedledger.application.TransactionAnnotationResult
import com.unifiedledger.application.UpdateTransactionAnnotation
import com.unifiedledger.application.AnnotationRequestId
import com.unifiedledger.application.AnnotationRequestIdSource
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-08 08.A transaction annotation store evidence (D-187; spec sections 3.1/3.2).
 *
 * Covers the sentinel-0 first annotation, revision increments, clear-as-empty-revision, both CAS
 * failures (annotation revision and current version) with zero writes, the 20-tag guard, the
 * conservative "current not voided" gate, catalog selectability, replay equivalence and the
 * immutability guards.
 */
class SqlDelightTransactionAnnotationStoreTest {
    private val ledgerId = LedgerId("ledger-ann")
    private val transactionId = TransactionId("tx-1")
    private val currentVersionId = TransactionVersionId("version-1")

    @Test
    fun sentinelZeroCreatesTheFirstRevisionWithPointerAndReceipt() {
        withStore { ctx ->
            val accepted = assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", tagIds = listOf(TagId("tag-1")), merchantId = MerchantId("merchant-1"), expectedRevision = 0L))
            assertEquals(1L, accepted.receipt.newAnnotationRevision)
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision WHERE annotation_revision = 1 AND observed_transaction_version_id = 'version-1' AND merchant_id = 'merchant-1'"))
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_tag WHERE annotation_revision = 1 AND tag_id = 'tag-1'"))
            assertEquals(1L, queryLong(ctx.driver, "SELECT annotation_revision FROM transaction_annotation_current WHERE transaction_id = 'tx-1'"))
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_command_receipt"))
        }
    }

    @Test
    fun subsequentEditsAppendNewRevisionsAndAdvanceThePointer() {
        withStore { ctx ->
            assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", listOf(TagId("tag-1")), null, 0L))
            val second = assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r2", listOf(TagId("tag-1"), TagId("tag-2")), MerchantId("merchant-1"), expectedRevision = 1L, expectedVersion = currentVersionId))
            assertEquals(2L, second.receipt.newAnnotationRevision)
            assertEquals(2L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
            assertEquals(2L, queryLong(ctx.driver, "SELECT annotation_revision FROM transaction_annotation_current"))
            // Every revision keeps its own immutable association set.
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_tag WHERE annotation_revision = 1"))
            assertEquals(2L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_tag WHERE annotation_revision = 2"))
        }
    }

    @Test
    fun clearingAllAssociationsAppendsAnEmptyRevisionAndKeepsThePointer() {
        withStore { ctx ->
            assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", listOf(TagId("tag-1")), MerchantId("merchant-1"), 0L))
            val cleared = assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r2", emptyList(), null, expectedRevision = 1L, expectedVersion = currentVersionId))
            assertEquals(2L, cleared.receipt.newAnnotationRevision)
            // "Never annotated" is not re-entered: the pointer row stays and points at the empty revision.
            assertEquals(2L, queryLong(ctx.driver, "SELECT annotation_revision FROM transaction_annotation_current"))
            assertEquals(0L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_tag WHERE annotation_revision = 2"))
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision WHERE annotation_revision = 2 AND merchant_id IS NULL"))
        }
    }

    @Test
    fun staleAnnotationRevisionIsAConflictWithZeroWritesForBothSentinelAndNumberedForms() {
        withStore { ctx ->
            // Expecting "no annotation" (0) when none exists but a pointer already does (after a create).
            assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", listOf(TagId("tag-1")), null, 0L))
            val sentinelMismatch = assertIs<TransactionAnnotationResult.Conflict>(ctx.update("r2", listOf(TagId("tag-2")), null, expectedRevision = 0L))
            assertEquals(AnnotationFailureCode.ANNOTATION_REVISION_CONFLICT, sentinelMismatch.failureCode)
            val numberedMismatch = assertIs<TransactionAnnotationResult.Conflict>(ctx.update("r3", listOf(TagId("tag-2")), null, expectedRevision = 99L))
            assertEquals(AnnotationFailureCode.ANNOTATION_REVISION_CONFLICT, numberedMismatch.failureCode)
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
            // The rejected claims did not linger as terminal rows.
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_command_request"))
        }
    }

    @Test
    fun staleExpectedCurrentVersionIsAConflictWithZeroWrites() {
        withStore { ctx ->
            val conflict = assertIs<TransactionAnnotationResult.Conflict>(
                ctx.update("r1", listOf(TagId("tag-1")), null, expectedRevision = 0L, expectedVersion = TransactionVersionId("version-other")),
            )
            assertEquals(AnnotationFailureCode.ANNOTATION_CURRENT_VERSION_CONFLICT, conflict.failureCode)
            assertEquals(0L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
        }
    }

    @Test
    fun moreThanTwentyTagsIsATypedRejectionWithZeroWrites() {
        withStore(tagCount = 25) { ctx ->
            val rejected = assertIs<TransactionAnnotationResult.Rejected>(
                ctx.update("r1", (1..21).map { TagId("tag-%02d".format(it)) }, null, 0L),
            )
            assertEquals(AnnotationFailureCode.ANNOTATION_TOO_MANY_TAGS, rejected.failureCode)
            assertEquals(0L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
        }
    }

    @Test
    fun theTwentyTagBoundIsAcceptedAtExactlyTwenty() {
        withStore(tagCount = 25) { ctx ->
            val accepted = assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", (1..20).map { TagId("tag-%02d".format(it)) }, null, 0L))
            assertEquals(1L, accepted.receipt.newAnnotationRevision)
            assertEquals(20L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_tag"))
        }
    }

    @Test
    fun editingAVoidedTransactionIsRejectedByTheConservativeGate() {
        withStore(voided = true) { ctx ->
            val rejected = assertIs<TransactionAnnotationResult.Rejected>(ctx.update("r1", listOf(TagId("tag-1")), null, 0L))
            assertEquals(AnnotationFailureCode.ANNOTATION_VOIDED_TRANSACTION, rejected.failureCode)
            assertEquals(0L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
        }
    }

    @Test
    fun unknownInactiveTombstonedTagsAndMerchantsAreTypedRejections() {
        withStore { ctx ->
            ctx.deactivateTag("tag-2")
            assertIs<TransactionAnnotationResult.Rejected>(ctx.update("r1", listOf(TagId("absent")), null, 0L))
            val inactive = assertIs<TransactionAnnotationResult.Rejected>(ctx.update("r2", listOf(TagId("tag-2")), null, 0L))
            assertEquals(AnnotationFailureCode.ANNOTATION_TAG_NOT_SELECTABLE, inactive.failureCode)
            val unknownMerchant = assertIs<TransactionAnnotationResult.Rejected>(ctx.update("r3", emptyList(), MerchantId("absent"), 0L))
            assertEquals(AnnotationFailureCode.ANNOTATION_UNKNOWN_MERCHANT, unknownMerchant.failureCode)
            assertEquals(0L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
        }
    }

    @Test
    fun equivalentReplayReturnsTheOriginalReceiptWithZeroWrites() {
        withStore { ctx ->
            val first = assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", listOf(TagId("tag-1"), TagId("tag-2")), MerchantId("merchant-1"), 0L))
            // Tag order in the request differs but the canonical snapshot (sorted) is equal: replay.
            val replay = assertIs<TransactionAnnotationResult.NoChange>(ctx.update("r1", listOf(TagId("tag-2"), TagId("tag-1")), MerchantId("merchant-1"), 0L))
            assertEquals(first.receipt, replay.receipt)
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
        }
    }

    @Test
    fun sameRequestIdWithADifferentSnapshotIsAnIdentityConflict() {
        withStore { ctx ->
            assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", listOf(TagId("tag-1")), null, 0L))
            val conflict = assertIs<TransactionAnnotationResult.Conflict>(ctx.update("r1", listOf(TagId("tag-2")), null, expectedRevision = 1L))
            assertEquals(AnnotationFailureCode.REQUEST_IDENTITY_CONFLICT, conflict.failureCode)
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision"))
        }
    }

    @Test
    fun theMerchantIsZeroOrOneAcrossRevisions() {
        withStore { ctx ->
            assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", emptyList(), null, 0L))
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision WHERE annotation_revision = 1 AND merchant_id IS NULL"))
            assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r2", emptyList(), MerchantId("merchant-1"), expectedRevision = 1L))
            assertEquals(1L, queryLong(ctx.driver, "SELECT count(*) FROM transaction_annotation_revision WHERE annotation_revision = 2 AND merchant_id = 'merchant-1'"))
        }
    }

    @Test
    fun guardTriggersForbidDeletesAndUnsafePointerMoves() {
        withStore { ctx ->
            assertIs<TransactionAnnotationResult.Accepted>(ctx.update("r1", listOf(TagId("tag-1")), null, 0L))
            assertFailsSql { ctx.driver.execute(null, "DELETE FROM transaction_annotation_revision", 0) }
            assertFailsSql { ctx.driver.execute(null, "DELETE FROM transaction_annotation_tag", 0) }
            assertFailsSql { ctx.driver.execute(null, "DELETE FROM transaction_annotation_current", 0) }
            assertFailsSql { ctx.driver.execute(null, "DELETE FROM transaction_annotation_command_receipt", 0) }
            assertFailsSql { ctx.driver.execute(null, "UPDATE transaction_annotation_revision SET merchant_id = 'x'", 0) }
            // The pointer may only advance, never go backwards or sideways.
            assertFailsSql { ctx.driver.execute(null, "UPDATE transaction_annotation_current SET annotation_revision = 1", 0) }
        }
    }

    private inner class Context(
        val driver: JdbcSqliteDriver,
        private val transaction: TransactionId,
    ) {
        val catalogStore: SqlDelightTagMerchantCatalogStore = SqlDelightTagMerchantCatalogStore(LedgerDatabase(driver), driver)

        fun update(
            requestId: String,
            tagIds: List<TagId>,
            merchantId: MerchantId?,
            expectedRevision: Long,
            expectedVersion: TransactionVersionId = currentVersionId,
        ): TransactionAnnotationResult {
            val useCase =
                UpdateTransactionAnnotation(
                    commitPort = SqlDelightTransactionAnnotationStore(LedgerDatabase(driver), driver),
                    requestIdSource = AnnotationRequestIdSource { AnnotationRequestId(requestId) },
                    clock = LedgerClock { Instant.parse("2026-03-05T02:00:00Z") },
                )
            return useCase.update(ledgerId, transaction, tagIds, merchantId, expectedRevision, expectedVersion)
        }

        fun deactivateTag(tagId: String) {
            val executor =
                ExecuteTagMerchantCommand(
                    commitPort = catalogStore,
                    requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId("deactivate-$tagId") },
                    idSource = CatalogItemIdSource { "unused" },
                    referenceProbe = CatalogItemReferenceProbe { _, _, _ -> false },
                )
            assertIs<TagMerchantCommandResult.Accepted>(
                executor.setItemActive(ledgerId, CatalogItemKind.TAG, tagId, false, expectedRevision = 1L),
            )
        }
    }

    private fun withStore(
        tagCount: Int = 2,
        voided: Boolean = false,
        block: (Context) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val context = Context(driver, transactionId)
            createCatalog(context.catalogStore, tagCount)
            seedTransaction(driver, voided)
            block(context)
        } finally {
            driver.close()
        }
    }

    private fun createCatalog(
        store: SqlDelightTagMerchantCatalogStore,
        tagCount: Int,
    ) {
        var tagIndex = 0
        val executor =
            ExecuteTagMerchantCommand(
                commitPort = store,
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId("catalog-tag-${tagIndex++}") },
                idSource = CatalogItemIdSource { "tag-%02d".format(++tagIndex) },
                referenceProbe = CatalogItemReferenceProbe { _, _, _ -> false },
            )
        (1..tagCount).forEach { index ->
            assertIs<TagMerchantCommandResult.Accepted>(executor.createItem(ledgerId, CatalogItemKind.TAG, "tag-name-$index", 0L))
        }
        // A single merchant for the 0..1 tests.
        val merchantExecutor =
            ExecuteTagMerchantCommand(
                commitPort = store,
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId("catalog-merchant") },
                idSource = CatalogItemIdSource { "merchant-1" },
                referenceProbe = CatalogItemReferenceProbe { _, _, _ -> false },
            )
        assertIs<TagMerchantCommandResult.Accepted>(merchantExecutor.createItem(ledgerId, CatalogItemKind.MERCHANT, "shop", 0L))
    }

    private fun seedTransaction(
        driver: JdbcSqliteDriver,
        voided: Boolean,
    ) {
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-1', 'ledger-ann', 'EXPENSE')", 0)
        driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-1', 'ledger-ann')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                "VALUES ('version-1', 'tx-1', 'ledger-ann', 1, 'posting-set-1', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z', 'lunch')",
            0,
        )
        driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('tx-1', 'ledger-ann', 'version-1')", 0)
        if (voided) {
            driver.execute(
                null,
                "INSERT INTO transaction_void_request(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) " +
                    "VALUES ('ledger-ann', 'void-request-1', 'tx-1', 'void', 'mis_entered', NULL, 'explicit_manual_save')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                    "VALUES ('ledger-ann', 'tx-1', 1, 'void-fact-1', 'void', 'mis_entered', NULL, 'void-request-1', 'void-confirmation-1', '2026-03-06T02:00:00Z')",
                0,
            )
        }
    }

    private fun migrationProperties(): Properties =
        Properties().apply { setProperty("foreign_keys", "true") }

    private fun queryLong(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
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

    private fun assertFailsSql(block: () -> Unit) {
        try {
            block()
            error("expected the guard trigger to reject the statement")
        } catch (expected: java.sql.SQLException) {
            // expected
        }
    }
}
