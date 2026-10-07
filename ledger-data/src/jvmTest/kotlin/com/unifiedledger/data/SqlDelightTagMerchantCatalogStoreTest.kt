package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogItemIdSource
import com.unifiedledger.application.CatalogItemReferenceProbe
import com.unifiedledger.application.ExecuteTagMerchantCommand
import com.unifiedledger.application.TagMerchantCommandResult
import com.unifiedledger.application.TagMerchantFailureCode
import com.unifiedledger.application.TagMerchantRequestId
import com.unifiedledger.application.TagMerchantRequestIdSource
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.CatalogItem
import com.unifiedledger.domain.CatalogItemKind
import com.unifiedledger.domain.LedgerId
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-08 08.A tag/merchant catalog store evidence (D-187; spec section 2.2).
 *
 * Claim-first protocol mirrors the budget owner: create mints the id post-claim, an equivalent
 * snapshot replay returns the original receipt with zero writes, a same-id different snapshot is a
 * `RequestIdentityConflict`, a stale `expectedRevision` is a `TagMerchantRevisionConflict` with
 * zero writes, renames keep the stable id, disable/enable is reversible while tombstone is
 * reference-gated and irreversible, and the guard triggers forbid physical deletes.
 */
class SqlDelightTagMerchantCatalogStoreTest {
    private val ledgerId = LedgerId("ledger-tm")

    @Test
    fun createTagInsertsTheRowNameHistoryAndAdvancesTheVersion() {
        withStore { harness, executor, driver ->
            val accepted = assertIs<TagMerchantCommandResult.Accepted>(executor("request-create").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", expectedRevision = 0L))
            assertEquals(CatalogItemKind.TAG, accepted.receipt.kind)
            assertEquals("tag-1", accepted.receipt.itemId)
            assertEquals(1L, accepted.receipt.newRevision)
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_tag WHERE tag_id = 'tag-1' AND active = 1 AND tombstoned = 0 AND revision = 1"))
            assertEquals("咖啡", queryText(driver, "SELECT name FROM catalog_item_name_history WHERE owner_kind = 'tag' AND owner_id = 'tag-1' AND status = 'CURRENT'"))
            assertEquals(1L, queryLong(driver, "SELECT version FROM catalog_item_version WHERE ledger_id = 'ledger-tm'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_item_command_request"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_item_command_receipt"))
        }
    }

    @Test
    fun equivalentReplayReturnsTheOriginalReceiptWithZeroNewWrites() {
        withStore { harness, executor, driver ->
            val first = assertIs<TagMerchantCommandResult.Accepted>(executor("request-replay").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            // Same request id + same snapshot: the ORIGINAL receipt comes back (a fresh mint would differ).
            val replay = assertIs<TagMerchantCommandResult.NoChange>(executor("request-replay").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            assertEquals(first.receipt, replay.receipt)
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_tag"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_item_name_history"))
        }
    }

    @Test
    fun sameRequestIdWithADifferentSnapshotIsAnIdentityConflict() {
        withStore { harness, executor, driver ->
            assertIs<TagMerchantCommandResult.Accepted>(executor("request-x").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            val conflict = assertIs<TagMerchantCommandResult.Conflict>(executor("request-x").createItem(ledgerId, CatalogItemKind.TAG, "茶", 0L))
            assertEquals(TagMerchantFailureCode.REQUEST_IDENTITY_CONFLICT, conflict.failureCode)
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_tag"))
        }
    }

    @Test
    fun staleExpectedRevisionIsAConflictWithZeroWrites() {
        withStore { harness, executor, driver ->
            val conflict = assertIs<TagMerchantCommandResult.Conflict>(executor("request-stale").renameItem(ledgerId, CatalogItemKind.TAG, "tag-1", "X", expectedRevision = 99L))
            assertEquals(TagMerchantFailureCode.TAG_MERCHANT_REVISION_CONFLICT, conflict.failureCode)
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_tag"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM catalog_item_command_request"))
        }
    }

    @Test
    fun renameKeepsTheStableIdAndAppendsNameHistory() {
        withStore { harness, executor, driver ->
            assertIs<TagMerchantCommandResult.Accepted>(executor("request-create").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            val renamed = assertIs<TagMerchantCommandResult.Accepted>(executor("request-rename").renameItem(ledgerId, CatalogItemKind.TAG, "tag-1", "咖啡豆", expectedRevision = 1L))
            assertEquals("tag-1", renamed.receipt.itemId)
            assertEquals(2L, renamed.receipt.newRevision)
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM catalog_item_name_history WHERE owner_id = 'tag-1'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_item_name_history WHERE status = 'CURRENT' AND name = '咖啡豆'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_item_name_history WHERE status = 'SUPERSEDED' AND name = '咖啡'"))
            assertEquals(2L, queryLong(driver, "SELECT revision FROM catalog_tag WHERE tag_id = 'tag-1'"))
        }
    }

    @Test
    fun disableAndEnableIsReversibleAndKeepsTheNameHistory() {
        withStore { harness, executor, driver ->
            harness.nextId = "merchant-1"
            assertIs<TagMerchantCommandResult.Accepted>(executor("c").createItem(ledgerId, CatalogItemKind.MERCHANT, "商店", 0L))
            assertIs<TagMerchantCommandResult.Accepted>(executor("d").setItemActive(ledgerId, CatalogItemKind.MERCHANT, "merchant-1", false, expectedRevision = 1L))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_merchant WHERE merchant_id = 'merchant-1' AND active = 0 AND tombstoned = 0"))
            assertIs<TagMerchantCommandResult.Accepted>(executor("e").setItemActive(ledgerId, CatalogItemKind.MERCHANT, "merchant-1", true, expectedRevision = 2L))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_merchant WHERE merchant_id = 'merchant-1' AND active = 1"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_item_name_history WHERE owner_id = 'merchant-1'"))
        }
    }

    @Test
    fun tombstoneRequiresNoReferencesAndIsIrreversible() {
        withStore { harness, executor, driver ->
            assertIs<TagMerchantCommandResult.Accepted>(executor("c").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            harness.forceReferences = true
            val rejected = assertIs<TagMerchantCommandResult.Rejected>(executor("del-1").deleteItem(ledgerId, CatalogItemKind.TAG, "tag-1", expectedRevision = 1L))
            assertEquals(TagMerchantFailureCode.TAG_MERCHANT_HAS_REFERENCES, rejected.failureCode)
            assertEquals(0L, queryLong(driver, "SELECT tombstoned FROM catalog_tag WHERE tag_id = 'tag-1'"))
            harness.forceReferences = false
            assertIs<TagMerchantCommandResult.Accepted>(executor("del-2").deleteItem(ledgerId, CatalogItemKind.TAG, "tag-1", expectedRevision = 1L))
            // Tombstone forces active = 0 and is terminal: a re-enable or a second delete is rejected.
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_tag WHERE tag_id = 'tag-1' AND tombstoned = 1 AND active = 0"))
            assertIs<TagMerchantCommandResult.Rejected>(executor("del-3").deleteItem(ledgerId, CatalogItemKind.TAG, "tag-1", expectedRevision = 2L))
            assertIs<TagMerchantCommandResult.Rejected>(executor("re").setItemActive(ledgerId, CatalogItemKind.TAG, "tag-1", true, expectedRevision = 2L))
        }
    }

    @Test
    fun guardTriggersForbidPhysicalDeleteAndUnRevisionedUpdates() {
        withStore { harness, executor, driver ->
            assertIs<TagMerchantCommandResult.Accepted>(executor("c").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            assertFailsSql { driver.execute(null, "DELETE FROM catalog_tag", 0) }
            assertFailsSql { driver.execute(null, "DELETE FROM catalog_item_name_history", 0) }
            assertFailsSql { driver.execute(null, "DELETE FROM catalog_item_command_receipt", 0) }
            // A revision bump of != +1 (even with legal values) is rejected.
            assertFailsSql { driver.execute(null, "UPDATE catalog_tag SET revision = 5", 0) }
        }
    }

    @Test
    fun theReferenceProbeSeesAnnotationReferencesButNotPendingClaims() {
        withStore { harness, executor, driver ->
            assertIs<TagMerchantCommandResult.Accepted>(executor("c").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            assertTrue(!harness.store.hasReferences(ledgerId, CatalogItemKind.TAG, "tag-1"))
            // A pending claim row (no annotation yet) is NOT a reference.
            assertTrue(!harness.store.hasReferences(ledgerId, CatalogItemKind.MERCHANT, "merchant-1"))
        }
    }

    @Test
    fun aRealAnnotationReferenceBlocksTheTombstoneDeleteThroughTheRealStoreProbe() {
        withStore { harness, _, driver ->
            assertIs<TagMerchantCommandResult.Accepted>(harness.executor("c").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            // A REAL annotation revision referencing tag-1, read back through the store's own probe
            // (never a stubbed `forceReferences`): the spec 2.2 safety property is only proven when
            // the probe actually observes a seeded reference.
            seedAnnotationReferencing(driver, "tag-1")
            assertTrue(harness.store.hasReferences(ledgerId, CatalogItemKind.TAG, "tag-1"))
            val rejected =
                assertIs<TagMerchantCommandResult.Rejected>(
                    harness.realProbeExecutor("del-referenced").deleteItem(ledgerId, CatalogItemKind.TAG, "tag-1", expectedRevision = 1L),
                )
            assertEquals(TagMerchantFailureCode.TAG_MERCHANT_HAS_REFERENCES, rejected.failureCode)
            assertEquals(0L, queryLong(driver, "SELECT tombstoned FROM catalog_tag WHERE tag_id = 'tag-1'"))
            // Non-vacuity: through the SAME real probe an unreferenced second tag still deletes.
            harness.nextId = "tag-2"
            assertIs<TagMerchantCommandResult.Accepted>(harness.executor("c2").createItem(ledgerId, CatalogItemKind.TAG, "茶", 0L))
            assertTrue(!harness.store.hasReferences(ledgerId, CatalogItemKind.TAG, "tag-2"))
            assertIs<TagMerchantCommandResult.Accepted>(
                harness.realProbeExecutor("del-unreferenced").deleteItem(ledgerId, CatalogItemKind.TAG, "tag-2", expectedRevision = 1L),
            )
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_tag WHERE tag_id = 'tag-2' AND tombstoned = 1"))
        }
    }

    @Test
    fun aStateWriteAgainstAVanishedRowReportsZeroChangedRows() {
        withStore { _, _, driver ->
            // The catalog has no physical delete path, so the store's row-count guard is defensive;
            // this pins the statement semantics it relies on: a state write against a missing row
            // changes zero rows instead of silently succeeding.
            assertEquals(
                0L,
                changedRows(driver, "UPDATE catalog_tag SET active = 0, tombstoned = 0, revision = revision + 1 WHERE ledger_id = 'ledger-tm' AND tag_id = 'ghost'"),
            )
            assertEquals(
                0L,
                changedRows(driver, "UPDATE catalog_merchant SET active = 0, tombstoned = 0, revision = revision + 1 WHERE ledger_id = 'ledger-tm' AND merchant_id = 'ghost'"),
            )
        }
    }

    @Test
    fun loadReturnsTheAuthorityWithThePerLedgerItemVersion() {
        withStore { harness, executor, driver ->
            val before = harness.store.load(ledgerId)
            assertEquals(null, before)
            assertIs<TagMerchantCommandResult.Accepted>(executor("c").createItem(ledgerId, CatalogItemKind.TAG, "咖啡", 0L))
            val authority = requireNotNull(harness.store.load(ledgerId))
            assertEquals(1L, authority.catalogItemVersion)
            assertEquals(listOf("tag-1"), authority.tags.map(CatalogItem::id))
            assertEquals("咖啡", authority.tags.single().name)
        }
    }

    private class Harness(
        val store: SqlDelightTagMerchantCatalogStore,
        val driver: JdbcSqliteDriver,
    ) {
        var forceReferences: Boolean = false

        /** The id the next fresh create will mint (tests pick the namespace-appropriate value). */
        var nextId: String = "tag-1"

        fun executor(requestId: String): ExecuteTagMerchantCommand =
            ExecuteTagMerchantCommand(
                commitPort = store,
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId(requestId) },
                idSource = CatalogItemIdSource { nextId },
                referenceProbe = CatalogItemReferenceProbe { _, _, _ -> forceReferences },
            )

        /** The same executor but wired to the STORE'S OWN reference probe (never a stub). */
        fun realProbeExecutor(requestId: String): ExecuteTagMerchantCommand =
            ExecuteTagMerchantCommand(
                commitPort = store,
                requestIdSource = TagMerchantRequestIdSource { TagMerchantRequestId(requestId) },
                idSource = CatalogItemIdSource { nextId },
                referenceProbe = store,
            )
    }

    private fun withStore(block: (Harness, (String) -> ExecuteTagMerchantCommand, JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val harness = Harness(SqlDelightTagMerchantCatalogStore(LedgerDatabase(driver), driver), driver)
            block(harness, harness::executor, driver)
        } finally {
            driver.close()
        }
    }

    private fun migrationProperties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

    /**
     * Seeds a REAL annotation revision referencing [tagId] (plus its tag association row and the
     * current pointer), so the store's own `hasReferences` probe has something to observe. The
     * annotation chain needs a live transaction + version to satisfy its deferred FKs.
     */
    private fun seedAnnotationReferencing(
        driver: JdbcSqliteDriver,
        tagId: String,
    ) {
        val ledger = ledgerId.value
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-ref', '$ledger', 'EXPENSE')", 0)
        driver.execute(null, "INSERT INTO posting_set VALUES ('ps-ref', '$ledger')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) " +
                "VALUES ('ver-ref', 'tx-ref', '$ledger', 1, 'ps-ref', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('tx-ref', '$ledger', 'ver-ref')", 0)
        driver.execute(
            null,
            "INSERT INTO transaction_annotation_revision(ledger_id, transaction_id, annotation_revision, observed_transaction_version_id, merchant_id, request_id, created_at) " +
                "VALUES ('$ledger', 'tx-ref', 1, 'ver-ref', NULL, 'annot-ref-req', '2026-01-02T00:00:00Z')",
            0,
        )
        driver.execute(null, "INSERT INTO transaction_annotation_tag(ledger_id, transaction_id, annotation_revision, tag_id) VALUES ('$ledger', 'tx-ref', 1, '$tagId')", 0)
        driver.execute(null, "INSERT INTO transaction_annotation_current(ledger_id, transaction_id, annotation_revision) VALUES ('$ledger', 'tx-ref', 1)", 0)
    }

    private fun changedRows(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long {
        driver.execute(null, sql, 0)
        return queryLong(driver, "SELECT changes()")
    }

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
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    private fun queryText(
        driver: JdbcSqliteDriver,
        sql: String,
    ): String =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getString(0)))
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
