package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.MerchantAccumulationReadResult
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterFailureCode
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.application.foldTagMerchantGroups
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-08 08.C bounded filter/accumulation read evidence (P708-A01/A04/A06 intent labels; the
 * acceptance registration belongs to the main agent — nothing here records a vector PASS).
 * Drives the real SQL over a real SQLite database so each frozen discipline turns a specific
 * assertion red when reverted:
 *
 *  - A01: three transactions of the SAME 100 amount (甲乙 / 仅甲 / 仅乙) — OR returns all three
 *    (equal amounts are never DISTINCT-deduplicated), AND returns only the both-tags one; the
 *    per-tag groups fold to 200/200 and are never additive;
 *  - the empty tag condition matches EVERY transaction, including never-annotated ones;
 *  - a DISABLED tag still matches its historical annotations (spec 5.1);
 *  - A06: the merchant accumulation counts a refund only through its OWN explicit annotation
 *    (no source-string/name guessing) and excludes unassociated transactions;
 *  - A04 read leg: a catalog generation advanced after the caller's snapshot is the typed
 *    mismatch, and an unreadable authority is the typed bad-catalog failure — both fail closed;
 *  - §5.3: a missing statistics projection is the typed failure, never a silent empty page;
 *  - keyset pagination is disjoint, total and stable across pages.
 *
 * All data synthetic and anonymous with fixed instants.
 */
class TagMerchantFilterReadTest {
    private val ledgerId = LedgerId("ledger-08c-filter")
    private val tagA = TagId("tag-a")
    private val tagB = TagId("tag-b")
    private val merchantOne = MerchantId("merchant-1")

    @Test
    fun a01OrReturnsEveryMatchWithoutAmountDeduplicationAndAndRequiresBothTags() {
        withDatabase { database, driver, adapter, catalogStore, generation ->
            seedTag(database, "tag-a", "标签甲")
            seedTag(database, "tag-b", "标签乙")
            seedExpense(database, "tx-both", "2026-03-01T02:00:00Z")
            annotate(database, "tx-both", "version-tx-both-1", tagIds = listOf("tag-a", "tag-b"))
            seedExpense(database, "tx-only-a", "2026-03-02T02:00:00Z")
            annotate(database, "tx-only-a", "version-tx-only-a-1", tagIds = listOf("tag-a"))
            seedExpense(database, "tx-only-b", "2026-03-03T02:00:00Z")
            annotate(database, "tx-only-b", "version-tx-only-b-1", tagIds = listOf("tag-b"))

            val or = assertIs<TagMerchantFilterReadResult.Success>(adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA, tagB)), generation, 10, null))
            val orPage = or.page
            // Three transactions of the SAME 1_000 minor amount: all three are returned —
            // an amount-based DISTINCT would silently drop two of them (A01 third vector).
            assertEquals(listOf("tx-only-b", "tx-only-a", "tx-both"), orPage.entries.map { it.row.transactionId.value })
            assertTrue(
                orPage.entries.all {
                    it.row.postings
                        .first()
                        .amount.minorUnits == 1_000L
                },
            )
            assertEquals(
                setOf(tagA, tagB),
                orPage.entries
                    .first { it.row.transactionId.value == "tx-both" }
                    .tagIds
                    .toSet(),
            )

            val and =
                assertIs<TagMerchantFilterReadResult.Success>(
                    adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA, tagB), tagMatch = TagMerchantTagMatchMode.All), generation, 10, null),
                )
            assertEquals(listOf("tx-both"), and.page.entries.map { it.row.transactionId.value })

            // 甲组 = tx-both + tx-only-a = 200；乙组 = tx-both + tx-only-b = 200； the two
            // groups overlap on tx-both and must never be added (A01 second vector).
            val catalog = assertIs<com.unifiedledger.application.CatalogAuthority>(catalogStore.load(ledgerId)).catalog
            val groups = foldTagMerchantGroups(orPage.entries, setOf(tagA, tagB), ledgerId, catalog)
            assertEquals(2_000L, groups.getValue(tagA).values.single())
            assertEquals(2_000L, groups.getValue(tagB).values.single())
            assertTrue(groups.getValue(tagA).values.single() + groups.getValue(tagB).values.single() != 3_000L)
        }
    }

    @Test
    fun anEmptyConditionMatchesEverythingIncludingNeverAnnotatedTransactions() {
        withDatabase { database, _, adapter, _, generation ->
            seedMerchant(database, "merchant-1", "商家一")
            seedTag(database, "tag-a", "标签甲")
            seedExpense(database, "tx-annotated", "2026-03-01T02:00:00Z")
            annotate(database, "tx-annotated", "version-tx-annotated-1", tagIds = listOf("tag-a"), merchantId = "merchant-1")
            seedExpense(database, "tx-plain", "2026-03-02T02:00:00Z")

            val page = assertIs<TagMerchantFilterReadResult.Success>(adapter.readPage(ledgerId, TagMerchantFilterCondition(), generation, 10, null)).page
            assertEquals(listOf("tx-plain", "tx-annotated"), page.entries.map { it.row.transactionId.value })
            // The never-annotated transaction carries an empty association (honest, not guessed).
            val plain = page.entries.first { it.row.transactionId.value == "tx-plain" }
            assertTrue(plain.tagIds.isEmpty())
            assertEquals(null, plain.merchantId)
            assertEquals(merchantOne, page.entries.first { it.row.transactionId.value == "tx-annotated" }.merchantId)
        }
    }

    @Test
    fun aMerchantConditionAndsWithTagsAndMatchesTheCurrentAnnotationOnly() {
        withDatabase { database, _, adapter, _, generation ->
            seedMerchant(database, "merchant-1", "商家一")
            seedMerchant(database, "merchant-2", "商家二")
            seedTag(database, "tag-a", "标签甲")
            seedExpense(database, "tx-match", "2026-03-01T02:00:00Z")
            annotate(database, "tx-match", "version-tx-match-1", tagIds = listOf("tag-a"), merchantId = "merchant-1")
            seedExpense(database, "tx-other-merchant", "2026-03-02T02:00:00Z")
            annotate(database, "tx-other-merchant", "version-tx-other-merchant-1", tagIds = listOf("tag-a"), merchantId = "merchant-2")
            seedExpense(database, "tx-name-only", "2026-03-03T02:00:00Z")
            annotate(database, "tx-name-only", "version-tx-name-only-1", tagIds = listOf("tag-a"), merchantId = null)

            val condition = TagMerchantFilterCondition(tagIds = setOf(tagA), merchantId = merchantOne)
            val page = assertIs<TagMerchantFilterReadResult.Success>(adapter.readPage(ledgerId, condition, generation, 10, null)).page
            assertEquals(listOf("tx-match"), page.entries.map { it.row.transactionId.value })
        }
    }

    @Test
    fun aDisabledTagStillMatchesItsHistoricalAnnotations() {
        withDatabase { database, _, adapter, _, generation ->
            seedTag(database, "tag-a", "标签甲")
            seedExpense(database, "tx-historic", "2026-03-01T02:00:00Z")
            annotate(database, "tx-historic", "version-tx-historic-1", tagIds = listOf("tag-a"))
            // Disable (active=false, still not tombstoned): the filter keeps matching the
            // historical annotation (spec 5.1 — 停用标签仍可用于查历史).
            database.ledgerQueries.setCatalogTagState(active = 0L, tombstoned = 0L, ledger_id = ledgerId.value, tag_id = "tag-a")
            val page = assertIs<TagMerchantFilterReadResult.Success>(adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA)), generation, 10, null)).page
            assertEquals(listOf("tx-historic"), page.entries.map { it.row.transactionId.value })
        }
    }

    @Test
    fun voidedTransactionsNeverMatchTheFilter() {
        withDatabase { database, driver, adapter, _, generation ->
            seedTag(database, "tag-a", "标签甲")
            seedExpense(database, "tx-voided", "2026-03-01T02:00:00Z")
            annotate(database, "tx-voided", "version-tx-voided-1", tagIds = listOf("tag-a"))
            seedVoidFact(driver, "tx-voided")
            val page = assertIs<TagMerchantFilterReadResult.Success>(adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA)), generation, 10, null)).page
            assertTrue(page.entries.isEmpty())
        }
    }

    @Test
    fun a06MerchantAccumulationCountsOnlyExplicitlyAssociatedRefunds() {
        withDatabase { database, _, adapter, _, generation ->
            seedMerchant(database, "merchant-1", "商家一")
            seedExpense(database, "tx-expense", "2026-03-01T02:00:00Z")
            annotate(database, "tx-expense", "version-tx-expense-1", tagIds = emptyList(), merchantId = "merchant-1")
            // An anonymous refund (REFUND_RECEIPT) NOT associated: never counted — the note's
            // merchant-name coincidence is never treated as an association (no guessing).
            seedExpense(database, "tx-unlinked-refund", "2026-03-02T02:00:00Z", amountMinor = -2_000L, kind = "REFUND_RECEIPT", note = "merchant-1 退款")
            // The same refund EXPLICITLY associated: counted by its own negative leg.
            seedExpense(database, "tx-linked-refund", "2026-03-03T02:00:00Z", amountMinor = -2_000L, kind = "REFUND_RECEIPT")
            annotate(database, "tx-linked-refund", "version-tx-linked-refund-1", tagIds = emptyList(), merchantId = "merchant-1")

            val before =
                assertIs<MerchantAccumulationReadResult.Success>(
                    adapter.readMerchantRows(ledgerId, merchantOne, MARCH_START, APRIL_START, generation),
                )
            // Before the explicit link: only the expense and the ALREADY-linked refund; the
            // note-only refund is not matched (its note names the merchant — a coincidence the
            // read never treats as an association).
            assertEquals(listOf("tx-expense", "tx-linked-refund"), before.rows.map { it.transactionId.value }.sorted())

            // The explicit link lands: the read counts the refund through its OWN annotation.
            annotate(database, "tx-unlinked-refund", "version-tx-unlinked-refund-1", tagIds = emptyList(), merchantId = "merchant-1")
            val after =
                assertIs<MerchantAccumulationReadResult.Success>(
                    adapter.readMerchantRows(ledgerId, merchantOne, MARCH_START, APRIL_START, generation),
                )
            assertEquals(
                listOf("tx-expense", "tx-linked-refund", "tx-unlinked-refund"),
                after.rows.map { it.transactionId.value }.sorted(),
            )
        }
    }

    @Test
    fun aMerchantAccumulationWindowExcludesOutOfWindowRows() {
        withDatabase { database, _, adapter, _, generation ->
            seedMerchant(database, "merchant-1", "商家一")
            seedExpense(database, "tx-march", "2026-03-01T00:00:00Z")
            annotate(database, "tx-march", "version-tx-march-1", tagIds = emptyList(), merchantId = "merchant-1")
            seedExpense(database, "tx-april", "2026-04-01T00:00:00Z")
            annotate(database, "tx-april", "version-tx-april-1", tagIds = emptyList(), merchantId = "merchant-1")
            val rows =
                assertIs<MerchantAccumulationReadResult.Success>(
                    adapter.readMerchantRows(ledgerId, merchantOne, MARCH_START, APRIL_START, generation),
                ).rows
            assertEquals(listOf("tx-march"), rows.map { it.transactionId.value })
        }
    }

    @Test
    fun a04AStaleCatalogGenerationIsTheTypedMismatchAndAnUnreadableAuthorityIsBadCatalog() {
        withDatabase { database, driver, adapter, _, generation ->
            seedTag(database, "tag-a", "标签甲")
            seedExpense(database, "tx-inside", "2026-03-01T02:00:00Z")
            annotate(database, "tx-inside", "version-tx-inside-1", tagIds = listOf("tag-a"))
            // A catalog command commits AFTER the caller loaded `generation`: the read refuses
            // with the typed mismatch (the D-186 compare-in-transaction gate on the dedicated
            // catalog_item_version counter), never a silently stale page.
            database.ledgerQueries.advanceCatalogItemVersion(ledgerId.value)
            val stale = adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA)), generation, 10, null)
            assertEquals(TagMerchantFilterFailureCode.FILTER_CATALOG_GENERATION_MISMATCH, assertIs<TagMerchantFilterReadResult.Failed>(stale).failureCode)

            // An unreadable authority (no catalog_item_version row at all) is fail-closed too.
            driver.execute(null, "DELETE FROM catalog_item_version WHERE ledger_id = '${ledgerId.value}'", 0)
            val unreadable = adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA)), generation, 10, null)
            assertEquals(TagMerchantFilterFailureCode.FILTER_BAD_CATALOG, assertIs<TagMerchantFilterReadResult.Failed>(unreadable).failureCode)
        }
    }

    @Test
    fun aMissingProjectionFailsClosedInsteadOfSilentlyOmittingRows() {
        withDatabase { database, driver, adapter, _, _ ->
            seedTag(database, "tag-a", "标签甲")
            // A raw row whose projection is NULL (an unmaintained writer): it can never satisfy
            // a keyset predicate, so the whole-ledger probe must fail the read loudly.
            driver.execute(
                null,
                "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-unprojected','${ledgerId.value}','EXPENSE')",
                0,
            )
            driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('ps-unprojected','${ledgerId.value}')", 0)
            driver.execute(
                null,
                "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) " +
                    "VALUES ('v-unprojected','tx-unprojected','${ledgerId.value}',1,'ps-unprojected','2026-03-05T02:00:00Z','2026-03-05T02:00:00Z','2026-03-05T02:00:00Z',NULL,NULL)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ('tx-unprojected','${ledgerId.value}','v-unprojected')",
                0,
            )
            val result = adapter.readPage(ledgerId, TagMerchantFilterCondition(), 1L, 10, null)
            assertEquals(TagMerchantFilterFailureCode.FILTER_MISSING_PROJECTION, assertIs<TagMerchantFilterReadResult.Failed>(result).failureCode)
        }
    }

    @Test
    fun keysetPaginationIsDisjointTotalAndStable() {
        withDatabase { database, _, adapter, _, generation ->
            val ids = (1..5).map { index -> "tx-page-0$index" }
            ids.forEachIndexed { index, id ->
                seedExpense(database, id, "2026-03-0${index + 1}T02:00:00Z")
            }
            var cursor: TagMerchantFilterCursor? = null
            val visited = mutableListOf<String>()
            var pages = 0
            do {
                val result = assertIs<TagMerchantFilterReadResult.Success>(adapter.readPage(ledgerId, TagMerchantFilterCondition(), generation, 2, cursor)).page
                pages += 1
                val pageIds = result.entries.map { it.row.transactionId.value }
                assertTrue(pageIds.size <= 2)
                // Descending statistics order is stable within and across pages.
                assertTrue(pageIds.zipWithNext().all { (a, b) -> a > b } || pageIds.size < 2, "page order broken: $pageIds")
                visited += pageIds
                cursor = result.nextCursor
                assertEquals(result.hasMore, cursor != null)
            } while (cursor != null)
            assertEquals(listOf("tx-page-05", "tx-page-04", "tx-page-03", "tx-page-02", "tx-page-01"), visited)
            assertEquals(3, pages)
        }
    }

    // ------------------------------------------------------------------ P7-08 08.D (T4/T9) 真实命令链

    /**
     * R-223-3 closure: the A04 "目录并发停用" read leg driven by the REAL
     * `ExecuteTagMerchantCommand` chain — the disable command commits (claim → guard trigger →
     * receipt → the `catalog_item_version` counter's `advance-by-one` trigger), and a filter read
     * holding the pre-command generation is typed-rejected. Ablating the compare-in-transaction
     * gate (or the counter advance) turns this test red. The raw-counter simulation test above is
     * KEPT: it alone can pin the unreadable-authority leg (the counter row deleted outright),
     * which no real command chain produces.
     */
    @Test
    fun aRealCatalogDisableCommandAdvancesTheGenerationAndAStaleFilterReadIsTypedRejected() {
        withDatabase { database, driver, adapter, _, generation ->
            // The tag is created through the REAL catalog command chain (claim/receipt/counter),
            // so the generation the caller loads is the chain's own counter value.
            val catalogStore = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database)
            // A FRESH request id per command (the claim-first identity discipline): a shared
            // fixed id would make the second command an identity conflict.
            var commandCounter = 0
            val executor =
                com.unifiedledger.application.ExecuteTagMerchantCommand(
                    commitPort = catalogStore,
                    requestIdSource =
                        com.unifiedledger.application.TagMerchantRequestIdSource {
                            commandCounter += 1
                            com.unifiedledger.application.TagMerchantRequestId("cmd-tag-a-$commandCounter")
                        },
                    idSource = com.unifiedledger.application.CatalogItemIdSource { "tag-a" },
                    referenceProbe = com.unifiedledger.application.CatalogItemReferenceProbe { _, _, _ -> false },
                )
            assertIs<com.unifiedledger.application.TagMerchantCommandResult.Accepted>(
                executor.createItem(ledgerId, com.unifiedledger.domain.CatalogItemKind.TAG, "标签甲", 0L),
            )
            seedExpense(database, "tx-inside", "2026-03-01T02:00:00Z")
            annotate(database, "tx-inside", "version-tx-inside-1", tagIds = listOf("tag-a"))
            val loadedGeneration = requireNotNull(catalogStore.load(ledgerId)).catalogItemVersion
            assertTrue(loadedGeneration > generation, "the real create command must advance the dedicated counter")

            val before = adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA)), loadedGeneration, 10, null)
            assertIs<TagMerchantFilterReadResult.Success>(before)

            // The REAL disable command (no counter simulation): the guard trigger advances the
            // counter inside the command transaction.
            assertIs<com.unifiedledger.application.TagMerchantCommandResult.Accepted>(
                executor.setItemActive(ledgerId, com.unifiedledger.domain.CatalogItemKind.TAG, "tag-a", false, expectedRevision = 1L),
            )
            val afterDisable = requireNotNull(catalogStore.load(ledgerId))
            assertTrue(afterDisable.catalogItemVersion > loadedGeneration)

            // The read still holding the pre-command generation is the typed mismatch.
            val stale = adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA)), loadedGeneration, 10, null)
            assertEquals(TagMerchantFilterFailureCode.FILTER_CATALOG_GENERATION_MISMATCH, assertIs<TagMerchantFilterReadResult.Failed>(stale).failureCode)
        }
    }

    /**
     * Q17 (:273) end-to-end display-name leg: the REAL rename command appends a new name-history
     * version, the current-name read returns the NEW name, the stable id never changes, the old
     * name is superseded (kept, auditable), and the filter still matches the historical annotation
     * by the stable id — so the renamed item's history keeps resolving under its current name.
     */
    @Test
    fun aRealCatalogRenameShowsTheNewCurrentNameWhileTheHistoricalAnnotationKeepsMatching() {
        withDatabase { database, driver, adapter, _, _ ->
            val catalogStore = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database)
            var renameCommandCounter = 0
            val executor =
                com.unifiedledger.application.ExecuteTagMerchantCommand(
                    commitPort = catalogStore,
                    requestIdSource =
                        com.unifiedledger.application.TagMerchantRequestIdSource {
                            renameCommandCounter += 1
                            com.unifiedledger.application.TagMerchantRequestId("cmd-rename-$renameCommandCounter")
                        },
                    idSource = com.unifiedledger.application.CatalogItemIdSource { "tag-a" },
                    referenceProbe = com.unifiedledger.application.CatalogItemReferenceProbe { _, _, _ -> false },
                )
            assertIs<com.unifiedledger.application.TagMerchantCommandResult.Accepted>(
                executor.createItem(ledgerId, com.unifiedledger.domain.CatalogItemKind.TAG, "标签甲", 0L),
            )
            seedExpense(database, "tx-historic", "2026-03-01T02:00:00Z")
            annotate(database, "tx-historic", "version-tx-historic-1", tagIds = listOf("tag-a"))

            // Before: the current name is the create-time name.
            assertEquals("标签甲", requireNotNull(catalogStore.load(ledgerId)).tags.single { it.id == "tag-a" }.name)

            // The REAL rename command (claim/name-history supersede+append/receipt/counter).
            assertIs<com.unifiedledger.application.TagMerchantCommandResult.Accepted>(
                executor.renameItem(ledgerId, com.unifiedledger.domain.CatalogItemKind.TAG, "tag-a", "标签甲（新）", 1L),
            )
            val renamed = requireNotNull(catalogStore.load(ledgerId)).tags.single { it.id == "tag-a" }
            // Q17: 改名后当前与历史交易默认显示当前名 — the id is stable, the name is current.
            assertEquals("tag-a", renamed.id)
            assertEquals("标签甲（新）", renamed.name)
            // The old name stays in the history as superseded (auditable, never rewritten).
            val historyRows =
                buildList {
                    driver.executeQuery(
                        null,
                        "SELECT name, status FROM catalog_item_name_history WHERE ledger_id = ? AND owner_kind = 'tag' AND owner_id = 'tag-a' ORDER BY version_number",
                        { cursor ->
                            while (cursor.next().value) {
                                add(requireNotNull(cursor.getString(0)) to requireNotNull(cursor.getString(1)))
                            }
                            app.cash.sqldelight.db.QueryResult
                                .Value(Unit)
                        },
                        1,
                    ) {
                        bindString(0, ledgerId.value)
                    }
                }
            assertEquals(listOf("标签甲" to "SUPERSEDED", "标签甲（新）" to "CURRENT"), historyRows)

            // The filter still matches the historical annotation by the STABLE id.
            val page =
                assertIs<TagMerchantFilterReadResult.Success>(
                    adapter.readPage(ledgerId, TagMerchantFilterCondition(tagIds = setOf(tagA)), requireNotNull(catalogStore.load(ledgerId)).catalogItemVersion, 10, null),
                ).page
            assertEquals(listOf("tx-historic"), page.entries.map { it.row.transactionId.value })
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun withDatabase(block: (LedgerDatabase, JdbcSqliteDriver, SqlDelightTagMerchantFilterReadAdapter, SqlDelightCatalogStore, Long) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalogStore = SqlDelightCatalogStore(database, driver)
            catalogStore.bootstrap(ledgerId, defaultCatalogSeed())
            // The tag/merchant catalog starts at its dedicated generation 1 (the bootstrap the
            // 08.A store performs on its first command; seeded directly here).
            database.ledgerQueries.insertCatalogItemVersion(ledgerId.value, 0L)
            database.ledgerQueries.advanceCatalogItemVersion(ledgerId.value)
            val adapter =
                SqlDelightTagMerchantFilterReadAdapter(
                    database = database,
                    accountCatalogReader = catalogStore,
                    tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database),
                )
            block(database, driver, adapter, catalogStore, 1L)
        } finally {
            driver.close()
        }
    }

    private fun seedTag(
        database: LedgerDatabase,
        tagId: String,
        name: String,
    ) {
        database.ledgerQueries.insertCatalogTag(ledger_id = ledgerId.value, tag_id = tagId, active = 1L, tombstoned = 0L, revision = 1L)
        database.ledgerQueries.insertCatalogItemNameHistory(ledger_id = ledgerId.value, owner_kind = "tag", owner_id = tagId, version_number = 1L, name = name, status = "CURRENT")
    }

    private fun seedMerchant(
        database: LedgerDatabase,
        merchantId: String,
        name: String,
    ) {
        database.ledgerQueries.insertCatalogMerchant(ledger_id = ledgerId.value, merchant_id = merchantId, active = 1L, tombstoned = 0L, revision = 1L)
        database.ledgerQueries.insertCatalogItemNameHistory(ledger_id = ledgerId.value, owner_kind = "merchant", owner_id = merchantId, version_number = 1L, name = name, status = "CURRENT")
    }

    private fun seedExpense(
        database: LedgerDatabase,
        transactionId: String,
        statisticsAt: String,
        amountMinor: Long = 1_000L,
        kind: String = "EXPENSE",
        note: String? = null,
    ) {
        val versionId = "version-$transactionId-1"
        val postingSetId = "$versionId-posting-set"
        database.ledgerQueries.insertTransaction(transactionId, ledgerId.value, kind)
        database.ledgerQueries.insertPostingSet(postingSetId, ledgerId.value)
        database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            ledgerId.value,
            1L,
            postingSetId,
            statisticsAt,
            statisticsAt,
            statisticsAt,
            note,
        )
        database.ledgerQueries.insertPosting("$versionId-posting-0", postingSetId, ledgerId.value, 0L, "expense-account-local", amountMinor, "CNY", 2L)
        database.ledgerQueries.insertPosting("$versionId-posting-1", postingSetId, ledgerId.value, 1L, "asset-payment-local", -amountMinor, "CNY", 2L)
        database.ledgerQueries.insertTransactionCurrentVersion(transactionId, ledgerId.value, versionId)
    }

    /** Appends one annotation revision (revision 1) with the given association and current pointer. */
    private fun annotate(
        database: LedgerDatabase,
        transactionId: String,
        observedVersionId: String,
        tagIds: List<String>,
        merchantId: String? = null,
    ) {
        database.ledgerQueries.insertTransactionAnnotationRevision(
            ledger_id = ledgerId.value,
            transaction_id = transactionId,
            annotation_revision = 1L,
            observed_transaction_version_id = observedVersionId,
            merchant_id = merchantId,
            request_id = "request-$transactionId",
            created_at = "2026-03-01T03:00:00Z",
        )
        tagIds.forEach { tagId ->
            database.ledgerQueries.insertTransactionAnnotationTag(
                ledger_id = ledgerId.value,
                transaction_id = transactionId,
                annotation_revision = 1L,
                tag_id = tagId,
            )
        }
        database.ledgerQueries.insertTransactionAnnotationCurrent(
            ledger_id = ledgerId.value,
            transaction_id = transactionId,
            annotation_revision = 1L,
        )
    }

    private fun seedVoidFact(
        driver: JdbcSqliteDriver,
        transactionId: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO transaction_void_request(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) " +
                "VALUES ('${ledgerId.value}','request-void-$transactionId','$transactionId','void','mis_entered',NULL,'explicit_manual_save')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                "VALUES ('${ledgerId.value}','$transactionId',1,'fact-$transactionId','void','mis_entered',NULL,'request-void-$transactionId','confirmation-$transactionId','2026-03-08T00:00:00Z')",
            0,
        )
    }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }

    companion object {
        private val MARCH_START = Instant.parse("2026-03-01T00:00:00Z")
        private val APRIL_START = Instant.parse("2026-04-01T00:00:00Z")
    }
}
