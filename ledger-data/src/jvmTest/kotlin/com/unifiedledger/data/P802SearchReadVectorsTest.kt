package com.unifiedledger.data

import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.P802_SEARCH_PAGE_SIZE
import com.unifiedledger.application.QuerySearchPage
import com.unifiedledger.application.ResolvedSearchCondition
import com.unifiedledger.application.SearchCondition
import com.unifiedledger.application.SearchFailureCode
import com.unifiedledger.application.SearchPage
import com.unifiedledger.application.SearchReadPort
import com.unifiedledger.application.SearchReadResult
import com.unifiedledger.application.SearchTimeWindow
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.application.escapeSearchLikeKeyword
import com.unifiedledger.application.resolveSearchTimeWindow
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * P8-02 i1 acceptance vectors V-2..V-7 (spec docs/specs/2026-11-11-p8-02-search-filter-design.md
 * section 5, task book steps 3-5) on a small, hand-built ledger: the read surface only, no UI.
 *
 * The fixture is deliberately one ledger covering every vector, so each vector asserts on the SAME
 * construction:
 *
 *  - three keyword arms that never share a marker: the CJK note marker `咖啡拿铁` (in window, before
 *    the window, after the window), an ASCII-case note marker `LATTE Macchiato`, the merchant names
 *    `商家甲` / the RENAMED merchant whose current name is `新商家名` (old name `旧商家名` is
 *    SUPERSEDED), the category names `测试分类` / `三臂分类`, and the shared prefix `三臂` that all
 *    three arms answer to;
 *  - one posting-semantic row per table in each of the SEVEN `rgXX_posting_semantic` tables, all
 *    carrying the same `测试分类` category (V-4), including the rg06 installment chain and the
 *    `PREPAID_RECOGNITION` kind the rg11 ownership guard demands;
 *  - a tag/merchant annotation set (V-5) whose OR, AND, and AND-with-merchant combinations are read
 *    BOTH through the search port and through the P7-08 `TagMerchantFilterReadPort`, and compared
 *    row for row — the search surface must not become a second filter semantics.
 *
 * Amounts, ids, names and instants are synthetic. Nothing here asserts a timing figure.
 */
class P802SearchReadVectorsTest {
    // ---------------------------------------------------------------- V-2 keyword hits the note

    @Test
    fun v2KeywordMatchesTheNoteInWindowAndNeverOutsideIt() {
        withSearchFixture { _, search ->
            // 3 transactions carry the note marker; only the in-window one is reachable.
            val inWindow = success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = NOTE_MARKER)))
            assertEquals(listOf("tx-p802-0001"), pageIds(inWindow), "the in-window note hit is the only hit")
            assertEquals(1L, inWindow.totalCount, "the count agrees with the page")

            // ASCII case folding: SQLite LIKE is case-insensitive for ASCII, CJK matches byte-wise.
            assertEquals(
                listOf("tx-p802-0002"),
                pageIds(success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "latte")))),
            )

            // Half-open bounds: an exclusive end landing exactly ON the old note's instant drops it,
            // and an inclusive start landing exactly ON the late note's instant admits it.
            assertEquals(
                emptyList(),
                pageIds(
                    success(
                        search.query(
                            P705Fixture.ledgerId,
                            SearchCondition(keyword = NOTE_MARKER, window = SearchTimeWindow.Custom(BEFORE_ALL, BEFORE_ALL_NOTE)),
                        ),
                    ),
                ),
                "the window end is exclusive, so the note on the boundary is outside it",
            )
            assertEquals(
                listOf("tx-p802-0004"),
                pageIds(
                    success(
                        search.query(
                            P705Fixture.ledgerId,
                            SearchCondition(keyword = NOTE_MARKER, window = SearchTimeWindow.Custom(AFTER_ALL_NOTE, AFTER_ALL)),
                        ),
                    ),
                ),
                "the window start is inclusive, so the note on the boundary is inside it",
            )

            // F3: a LIKE wildcard in the user's keyword is literal, never a prefix match.
            assertEquals(
                emptyList(),
                pageIds(success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "%")))),
                "an escaped % matches the literal character only",
            )
            assertEquals(
                emptyList(),
                pageIds(success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "_")))),
                "an escaped _ matches the literal character only",
            )
        }
    }

    // ------------------------------------------------------------ V-3 keyword hits a merchant name

    @Test
    fun v3KeywordMatchesTheCurrentMerchantNameAndNotTheSupersededOne() {
        withSearchFixture { _, search ->
            // The transaction's CURRENT annotation names merchant-a, whose current name is 商家甲;
            // tx-p802-0020's CURRENT revision also names it while its revision 1 (superseded) names
            // the renamed merchant, so only the current annotation may answer.
            val hit = success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "商家甲")))
            assertEquals(listOf("tx-p802-0020", "tx-p802-0019", "tx-p802-0005"), pageIds(hit), "the merchant arm reads the current annotation")
            assertEquals(3L, hit.totalCount)

            // A rename only ever answers its CURRENT name (the registered consequence). tx-p802-0020's
            // superseded revision 1 names the renamed merchant; the search still must not see it.
            val renamed = success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "新商家名")))
            assertEquals(listOf("tx-p802-0006"), pageIds(renamed), "only the current revision's merchant matches")
            assertEquals(1L, renamed.totalCount)
            val superseded = success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "旧商家名")))
            assertEquals(emptyList(), pageIds(superseded), "a superseded name resolves to no merchant")
            assertEquals(0L, superseded.totalCount, "no hit is an empty SUCCESS, not a failure")
        }
    }

    // ----------------------------------------------------------- V-4 keyword hits a category name

    @Test
    fun v4KeywordMatchesTheCategoryNameInEveryOneOfTheSevenSemanticTables() {
        withSearchFixture { _, search ->
            val result = success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "测试分类")))
            val ids = pageIds(result)
            assertEquals((16 downTo 10).map(::transactionId), ids, "all seven semantic-table rows are reachable, in keyset order")
            assertEquals(7L, result.totalCount)
            // One assertion per table, so a table dropped from the union fails on its own name.
            SEMANTIC_TABLE_TRANSACTIONS.forEach { (table, transaction) ->
                assertTrue(transaction in ids, "the $table fixture row must be reachable through the category arm")
            }
        }
    }

    // -------------------------------------------------- V-5 three arms OR, filter semantics equal

    @Test
    fun v5KeywordArmsDoNotSwallowEachOtherAndTheTagMerchantFilterStaysEquivalent() {
        withSearchFixture { harness, search ->
            // Each single-arm keyword reaches exactly its own transaction: no arm owns another's rows.
            assertEquals(
                listOf("tx-p802-0007"),
                pageIds(success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "三臂备注")))),
                "the note arm alone",
            )
            assertEquals(
                listOf("tx-p802-0008"),
                pageIds(success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "三臂商家")))),
                "the merchant name arm alone",
            )
            assertEquals(
                listOf("tx-p802-0009"),
                pageIds(success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "三臂分类")))),
                "the category name arm alone",
            )
            // The shared prefix answers all three arms at once: OR, never one arm swallowing another.
            val allArms = success(search.query(P705Fixture.ledgerId, SearchCondition(keyword = "三臂")))
            assertEquals(listOf("tx-p802-0009", "tx-p802-0008", "tx-p802-0007"), pageIds(allArms))
            assertEquals(3L, allArms.totalCount, "the count is the union, not the sum of the arms")

            // Tag OR/AND and the merchant condition AND together, and must match the P7-08 read.
            val filterRead =
                SqlDelightTagMerchantFilterReadAdapter(
                    database = harness.database,
                    accountCatalogReader = accountCatalog(harness),
                    tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
                )
            val conditions =
                listOf(
                    TagMerchantFilterCondition(tagIds = setOf(TagId(TAG_A)), tagMatch = TagMerchantTagMatchMode.Any),
                    TagMerchantFilterCondition(tagIds = setOf(TagId(TAG_A), TagId(TAG_B)), tagMatch = TagMerchantTagMatchMode.All),
                    TagMerchantFilterCondition(
                        tagIds = setOf(TagId(TAG_A), TagId(TAG_B)),
                        tagMatch = TagMerchantTagMatchMode.Any,
                        merchantId = MerchantId(MERCHANT_A),
                    ),
                    TagMerchantFilterCondition(merchantId = MerchantId(MERCHANT_A)),
                )
            val expected =
                listOf(
                    listOf("tx-p802-0019", "tx-p802-0017"),
                    listOf("tx-p802-0019"),
                    listOf("tx-p802-0019"),
                    listOf("tx-p802-0020", "tx-p802-0019", "tx-p802-0005"),
                )
            conditions.forEachIndexed { index, condition ->
                val page =
                    success(
                        search.query(
                            P705Fixture.ledgerId,
                            SearchCondition(
                                window = WIDE_WINDOW,
                                tagIds = condition.tagIds,
                                tagMatch = condition.tagMatch,
                                merchantFilterId = condition.merchantId,
                            ),
                        ),
                    )
                val searched = pageIds(page)
                val filtered = filterIds(filterRead, condition)
                assertEquals(expected[index], searched, "search result for condition #$index")
                assertEquals(
                    expected[index].size.toLong(),
                    page.totalCount,
                    "the count for condition #$index is the matched set, tag predicate included",
                )
                assertEquals(filtered, searched, "the search read and the P7-08 filter read must agree for condition #$index")
            }
        }
    }

    // ------------------------------------------------------------- V-6 keyset pages and the count

    @Test
    fun v6KeysetPagesDoNotSkipOrRepeatAndTheTotalEqualsTheExhaustiveWalk() {
        withSearchFixture { _, search ->
            // The frozen page size, pinned: the traversal below and the V-8 scale reading both size
            // pages by this constant, so a change must redden a test instead of resizing both sides.
            assertEquals(50, P802_SEARCH_PAGE_SIZE, "the search page size is frozen at 50 (spec section 2.3)")
            val expected = (16 downTo 10).map(::transactionId)
            val visited = mutableListOf<String>()
            val pages = mutableListOf<SearchPage>()
            var cursor: TagMerchantFilterCursor? = null
            do {
                val page =
                    success(
                        search.query(
                            P705Fixture.ledgerId,
                            SearchCondition(keyword = "测试分类"),
                            pageSize = 3,
                            cursor = cursor,
                        ),
                    )
                pages += page
                visited += page.entries.map { it.transactionId.value }
                cursor = page.nextCursor
            } while (cursor != null)

            assertEquals(expected, visited, "page-by-page traversal reproduces the whole result exactly once")
            assertEquals(visited.size, visited.toSet().size, "no transaction may appear on two pages")
            assertEquals(listOf(3, 3, 1), pages.map { it.entries.size }, "two full pages and the remainder")
            assertTrue(pages[0].hasMore && pages[1].hasMore, "the first two pages report more")
            assertTrue(!pages[2].hasMore, "the last page reports no more")
            assertTrue(pages[0].nextCursor != null && pages[1].nextCursor != null, "a page with more carries a cursor")
            assertNull(pages[2].nextCursor, "the last page's cursor is null")
            pages.forEach { page -> assertEquals(7L, page.totalCount, "the count is the whole condition group, not the page") }
            assertEquals(expected.size.toLong(), pages.last().totalCount, "the count equals the exhaustive walk")
        }
    }

    // ---------------------------------------------------------- V-7 a failure is never an empty page
    @Test
    fun v7AFailedReadIsTypedAndNeverAnEmptyPage() {
        val harness = P705Database.create("p802-v7-")
        try {
            seedFixture(harness)
            val adapter = SqlDelightSearchReadAdapter(harness.database)
            val resolved =
                ResolvedSearchCondition(
                    keyword = "",
                    windowStartInclusive = BEFORE_ALL,
                    windowEndExclusive = AFTER_ALL,
                )
            // A live read first, so the failure below is provably the closed connection.
            assertIs<SearchReadResult.Success>(adapter.readPage(P705Fixture.ledgerId, resolved, P802_SEARCH_PAGE_SIZE, null))

            // The PORT itself refuses a reversed or zero-length caller window, not only the use case.
            val reversed =
                adapter.readPage(
                    P705Fixture.ledgerId,
                    resolved.copy(windowStartInclusive = AFTER_ALL, windowEndExclusive = BEFORE_ALL),
                    P802_SEARCH_PAGE_SIZE,
                    null,
                )
            assertEquals(
                SearchFailureCode.SEARCH_INVALID_WINDOW,
                assertIs<SearchReadResult.Failed>(reversed).failureCode,
                "a reversed window is the caller's input, never an empty page",
            )
            val zeroLength =
                adapter.readPage(
                    P705Fixture.ledgerId,
                    resolved.copy(windowEndExclusive = resolved.windowStartInclusive),
                    P802_SEARCH_PAGE_SIZE,
                    null,
                )
            assertEquals(
                SearchFailureCode.SEARCH_INVALID_WINDOW,
                assertIs<SearchReadResult.Failed>(zeroLength).failureCode,
                "a zero-length window is the caller's input, never an empty page",
            )

            // A caller bound the projection cannot represent is the caller's input too: an invalid
            // window, not a read failure (that stays SEARCH_UNAVAILABLE).
            val unrepresentable =
                adapter.readPage(
                    P705Fixture.ledgerId,
                    resolved.copy(windowEndExclusive = UNREPRESENTABLE),
                    P802_SEARCH_PAGE_SIZE,
                    null,
                )
            assertEquals(
                SearchFailureCode.SEARCH_INVALID_WINDOW,
                assertIs<SearchReadResult.Failed>(unrepresentable).failureCode,
                "an unrepresentable window bound is refused as the caller's input",
            )

            // The use case maps its own boundaries the same way: an unreadable catalog authority
            // and a throwing port are typed failures, and neither becomes an empty page. Both run
            // on the STILL-OPEN harness, because their catalog readers really read it: only then is
            // the throwing-port case provably the port throwing rather than a read on a closed
            // handle (both surface as SEARCH_UNAVAILABLE).
            val badCatalog =
                QuerySearchPage(
                    readPort = adapter,
                    catalogReader = { null },
                    tagMerchantCatalogReader = { null },
                    clock = { CLOCK_NOW },
                ).query(P705Fixture.ledgerId, SearchCondition(keyword = NOTE_MARKER))
            assertEquals(SearchFailureCode.SEARCH_BAD_CATALOG, assertIs<SearchReadResult.Failed>(badCatalog).failureCode)

            val throwingPort =
                object : SearchReadPort {
                    override fun readPage(
                        ledgerId: LedgerId,
                        condition: ResolvedSearchCondition,
                        pageSize: Int,
                        cursor: TagMerchantFilterCursor?,
                    ): SearchReadResult = throw IllegalStateException("driver gone")
                }
            val relayed =
                QuerySearchPage(
                    readPort = throwingPort,
                    catalogReader = accountCatalog(harness),
                    tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
                    clock = { CLOCK_NOW },
                ).query(P705Fixture.ledgerId, SearchCondition())
            assertEquals(SearchFailureCode.SEARCH_UNAVAILABLE, assertIs<SearchReadResult.Failed>(relayed).failureCode)

            // An unusable custom window is refused typed, not silently widened or emptied.
            val invalidWindow =
                QuerySearchPage(
                    readPort = adapter,
                    catalogReader = accountCatalog(harness),
                    tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
                    clock = { CLOCK_NOW },
                ).query(
                    P705Fixture.ledgerId,
                    SearchCondition(window = SearchTimeWindow.Custom(AFTER_ALL, BEFORE_ALL)),
                )
            assertEquals(SearchFailureCode.SEARCH_INVALID_WINDOW, assertIs<SearchReadResult.Failed>(invalidWindow).failureCode)

            // One version row loses its projection: a fresh read must fail loudly, never drop the row.
            harness.driverExecute(
                "UPDATE transaction_version SET statistics_at_epoch_nanos = NULL " +
                    "WHERE ledger_id = '${P705Fixture.ledgerId.value}' AND transaction_id = 'tx-p802-0001'",
            )
            val missingProjection = adapter.readPage(P705Fixture.ledgerId, resolved, P802_SEARCH_PAGE_SIZE, null)
            assertEquals(
                SearchFailureCode.SEARCH_MISSING_PROJECTION,
                assertIs<SearchReadResult.Failed>(missingProjection).failureCode,
                "a NULL statistics projection is a typed failure, never a quiet omission",
            )

            // Closing the connection comes LAST: the first read on a closed handle fails, but its
            // cleanup clears the driver's thread-local, so a second read silently reconnects on a
            // fresh connection (P705Database.closePreservingFile). Leaving that to the end keeps
            // this the only read after the close, and leaves no open handle for the owner's file
            // deletion in the finally block.
            harness.closePreservingFile()
            val failed = adapter.readPage(P705Fixture.ledgerId, resolved, P802_SEARCH_PAGE_SIZE, null)
            assertEquals(
                SearchFailureCode.SEARCH_UNAVAILABLE,
                assertIs<SearchReadResult.Failed>(failed).failureCode,
                "a closed connection is a typed failure, never Success(empty page)",
            )
        } finally {
            harness.close()
        }
    }

    // ---------------------------------------- the frozen window resolution: CurrentMonth

    @Test
    fun currentMonthWindowResolvesTheFrozenReportingZoneOnAMonthEdge() {
        // 2026-04-30T16:00:00Z is exactly 2026-05-01T00:00 in Asia/Shanghai (no DST): the clock sits ON
        // the month edge, and the boundary instant belongs to the new month (R-Q06-2).
        val bounds = resolveSearchTimeWindow(SearchTimeWindow.CurrentMonth, clock = { MONTH_EDGE })
        assertEquals(MONTH_EDGE, bounds?.first, "the window starts at the frozen zone's local first day 00:00")
        assertEquals(JUNE_START, bounds?.second, "the window ends at the next local first day 00:00")
    }

    // ------------------------------------------------------ F3: the backslash escape is covered

    @Test
    fun escapeSearchLikeKeywordEscapesTheBackslashBeforeTheWildcards() {
        assertEquals("\\\\", escapeSearchLikeKeyword("\\"), "a lone backslash is doubled under ESCAPE '\\'")
        assertEquals("a\\\\b", escapeSearchLikeKeyword("a\\b"), "an embedded backslash is doubled")
        assertEquals(
            "\\\\\\%",
            escapeSearchLikeKeyword("\\%"),
            "doubling runs first, so a literal backslash never disarms the wildcard escape",
        )
    }

    // ------------------------------------------------------------------------------ fixture

    private fun withSearchFixture(block: (P705Database, QuerySearchPage) -> Unit) {
        P705Database.create("p802-vectors-").use { harness ->
            seedFixture(harness)
            block(harness, searchUseCase(harness))
        }
    }

    private fun searchUseCase(harness: P705Database): QuerySearchPage =
        QuerySearchPage(
            readPort = SqlDelightSearchReadAdapter(harness.database),
            catalogReader = accountCatalog(harness),
            tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
            clock = { CLOCK_NOW },
        )

    private fun accountCatalog(harness: P705Database): SqlDelightCatalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)

    /** Builds the one shared ledger: chains, catalog, tag/merchant items, annotations, semantics. */
    private fun seedFixture(harness: P705Database) {
        // 1. Chains. The in-window instants ascend with the index, so the keyset order is the
        //    descending transaction id; the two off-window notes sit before and after the window.
        IN_WINDOW_INDEXES.forEach { index ->
            val transactionId = transactionId(index)
            harness.insertOrdinaryExpense(
                transactionId = transactionId,
                amountMinor = 1_000L + index,
                statisticsAt = inWindow(index),
                note = NOTE_BY_ID.getValue(transactionId),
            )
        }
        harness.insertOrdinaryExpense("tx-p802-0003", 1_200L, BEFORE_ALL_NOTE, note = NOTE_BY_ID.getValue("tx-p802-0003"))
        harness.insertOrdinaryExpense("tx-p802-0004", 1_300L, AFTER_ALL_NOTE, note = NOTE_BY_ID.getValue("tx-p802-0004"))

        // 2. The account/category catalog: the search reads category names from its loader, and the
        //    P7-08 filter adapter needs the same authority for its generation gate.
        val catalogStore = accountCatalog(harness)
        assertTrue(
            catalogStore.bootstrap(P705Fixture.ledgerId, searchCatalogSeed()) is CatalogBootstrapResult.Seeded,
            "the fixture catalog must seed",
        )
        assertIs<CatalogAuthority>(catalogStore.load(P705Fixture.ledgerId))

        // 3. Raw catalog items, posting semantics and annotations, in one transaction (the scale
        //    fixture's batching shape; the tag/merchant command port is not what this batch tests).
        val ledger = P705Fixture.ledgerId.value
        harness.database.transaction {
            listOf(TAG_A, TAG_B).forEach { tag ->
                harness.driverExecute("INSERT INTO catalog_tag(ledger_id, tag_id, active, tombstoned, revision) VALUES ('$ledger', '$tag', 1, 0, 1)")
                harness.driverExecute(
                    "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                        "VALUES ('$ledger', 'tag', '$tag', 1, '标签${tag.takeLast(1)}', 'CURRENT')",
                )
            }
            MERCHANT_NAMES.forEach { (merchantId, currentName) ->
                harness.driverExecute(
                    "INSERT INTO catalog_merchant(ledger_id, merchant_id, active, tombstoned, revision) VALUES ('$ledger', '$merchantId', 1, 0, 1)",
                )
                harness.driverExecute(
                    "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                        "VALUES ('$ledger', 'merchant', '$merchantId', 1, '$currentName', 'CURRENT')",
                )
            }
            // The rename (V-3): one SUPERSEDED old name and the CURRENT new one, so a search answers
            // the current name only.
            harness.driverExecute(
                "INSERT INTO catalog_merchant(ledger_id, merchant_id, active, tombstoned, revision) VALUES ('$ledger', '$MERCHANT_RENAMED', 1, 0, 2)",
            )
            harness.driverExecute(
                "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                    "VALUES ('$ledger', 'merchant', '$MERCHANT_RENAMED', 1, '旧商家名', 'SUPERSEDED')",
            )
            harness.driverExecute(
                "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                    "VALUES ('$ledger', 'merchant', '$MERCHANT_RENAMED', 2, '新商家名', 'CURRENT')",
            )
            harness.driverExecute("INSERT INTO catalog_item_version(ledger_id, version) VALUES ('$ledger', 1)")

            // V-4: one posting-semantic row per table, all carrying 测试分类.
            harness.driverExecute(
                "INSERT INTO rg03_transfer_posting_semantic(ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', 'tx-p802-0010-posting-0', 'TRANSFER_FEE', '$CATEGORY_TEST', 0)",
            )
            harness.driverExecute(
                "INSERT INTO rg04_posting_semantic(ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', 'tx-p802-0011-posting-0', 'expense', '$CATEGORY_TEST', 0)",
            )
            harness.driverExecute(
                "INSERT INTO rg05_posting_semantic(ledger_id, posting_id, role, item_id, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', 'tx-p802-0012-posting-0', 'expense', 'item-p802', '$CATEGORY_TEST', 0)",
            )
            // rg06 owns its installment chain: relation -> installment -> posting semantic.
            harness.driverExecute(
                "INSERT INTO rg06_relation(ledger_id, relation_id, relation_type, payload_marker) " +
                    "VALUES ('$ledger', 'relation-p802', 'staged_payment', '{}')",
            )
            harness.driverExecute(
                "INSERT INTO rg06_installment(ledger_id, relation_id, installment_index, payment_id, payment_role, amount_minor, " +
                    "currency_code, currency_precision, funding_account_id, transaction_id, transaction_version_id, posting_set_id, " +
                    "expense_posting_id, asset_posting_id, actual_payment_at, statistics_at) VALUES " +
                    "('$ledger', 'relation-p802', 0, 'payment-p802', 'DEPOSIT', 1300, '${P705Fixture.cny.code}', " +
                    "${P705Fixture.cny.precision}, '${P705Fixture.bankA.value}', 'tx-p802-0013', 'tx-p802-0013-version-1', " +
                    "'tx-p802-0013-posting-set-1', 'tx-p802-0013-posting-0', 'tx-p802-0013-posting-1', " +
                    "'${inWindow(13)}', '${inWindow(13)}')",
            )
            harness.driverExecute(
                "INSERT INTO rg06_posting_semantic(ledger_id, posting_id, payment_id, role, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', 'tx-p802-0013-posting-0', 'payment-p802', 'expense', '$CATEGORY_TEST', 0)",
            )
            harness.driverExecute(
                "INSERT INTO rg07_posting_semantic(ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', 'tx-p802-0014-posting-0', 'expense', '$CATEGORY_TEST', 0)",
            )
            // rg11 admits an `expense` semantic only on a PREPAID_RECOGNITION transaction.
            harness.driverExecute(
                "UPDATE ledger_transaction SET canonical_kind = 'PREPAID_RECOGNITION' " +
                    "WHERE ledger_id = '$ledger' AND transaction_id = 'tx-p802-0015'",
            )
            harness.driverExecute(
                "INSERT INTO rg11_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible, category_id) " +
                    "VALUES ('$ledger', 'tx-p802-0015-posting-0', 'expense', 0, '$CATEGORY_TEST')",
            )
            harness.driverExecute(
                "INSERT INTO rg12_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible, category_id) " +
                    "VALUES ('$ledger', 'tx-p802-0016-posting-0', 'expense', 0, '$CATEGORY_TEST')",
            )
            // V-5: the category arm's own row, on its own transaction.
            harness.driverExecute(
                "INSERT INTO rg07_posting_semantic(ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', 'tx-p802-0009-posting-0', 'expense', '$CATEGORY_ARM', 0)",
            )

            // Annotations: the merchant annotations of V-3/V-5 and the tag sets of V-5.
            ANNOTATIONS.forEach { (transactionId, annotation) ->
                harness.driverExecute(
                    "INSERT INTO transaction_annotation_revision(ledger_id, transaction_id, annotation_revision, " +
                        "observed_transaction_version_id, merchant_id, request_id, created_at) VALUES " +
                        "('$ledger', '$transactionId', 1, '$transactionId-version-1', " +
                        "${annotation.merchantId?.let { "'$it'" } ?: "NULL"}, 'request-$transactionId', '2026-02-01T00:00:00Z')",
                )
                annotation.tagIds.forEach { tag ->
                    harness.driverExecute(
                        "INSERT INTO transaction_annotation_tag(ledger_id, transaction_id, annotation_revision, tag_id) " +
                            "VALUES ('$ledger', '$transactionId', 1, '$tag')",
                    )
                }
                harness.driverExecute(
                    "INSERT INTO transaction_annotation_current(ledger_id, transaction_id, annotation_revision) " +
                        "VALUES ('$ledger', '$transactionId', 1)",
                )
            }
            // V-3 revision join: tx-p802-0020's CURRENT revision names merchant-a while revision 1
            // (superseded, unpointed) still names the renamed merchant. Only the current one answers.
            val multiRevisionTx = transactionId(20)
            harness.driverExecute(
                "INSERT INTO transaction_annotation_revision(ledger_id, transaction_id, annotation_revision, " +
                    "observed_transaction_version_id, merchant_id, request_id, created_at) VALUES " +
                    "('$ledger', '$multiRevisionTx', 1, '$multiRevisionTx-version-1', '$MERCHANT_RENAMED', " +
                    "'request-$multiRevisionTx-1', '2026-02-01T00:00:00Z')",
            )
            harness.driverExecute(
                "INSERT INTO transaction_annotation_revision(ledger_id, transaction_id, annotation_revision, " +
                    "observed_transaction_version_id, merchant_id, request_id, created_at) VALUES " +
                    "('$ledger', '$multiRevisionTx', 2, '$multiRevisionTx-version-1', '$MERCHANT_A', " +
                    "'request-$multiRevisionTx-2', '2026-02-02T00:00:00Z')",
            )
            harness.driverExecute(
                "INSERT INTO transaction_annotation_current(ledger_id, transaction_id, annotation_revision) " +
                    "VALUES ('$ledger', '$multiRevisionTx', 2)",
            )
        }
    }

    private fun filterIds(
        readAdapter: SqlDelightTagMerchantFilterReadAdapter,
        condition: TagMerchantFilterCondition,
    ): List<String> {
        val entries = mutableListOf<String>()
        var cursor: TagMerchantFilterCursor? = null
        do {
            val page =
                assertIs<TagMerchantFilterReadResult.Success>(
                    readAdapter.readPage(P705Fixture.ledgerId, condition, CATALOG_ITEM_VERSION, 500, cursor),
                ).page
            entries += page.entries.map { it.row.transactionId.value }
            cursor = page.nextCursor
        } while (cursor != null)
        return entries
    }

    private fun success(result: SearchReadResult): SearchPage = assertIs<SearchReadResult.Success>(result).page

    private fun pageIds(page: SearchPage): List<String> = page.entries.map { it.transactionId.value }

    /** The account/category catalog seed: the bulk fixture's own posting accounts plus two leaves. */
    private fun searchCatalogSeed(): CatalogSeed =
        CatalogSeed(
            accounts =
                listOf(
                    CatalogSeedAccount(P705Fixture.bankA, "银行A", AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                    CatalogSeedAccount(P705Fixture.expenseAccount, "支出", AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                    CatalogSeedAccount(P705Fixture.incomeAccount, "收入", AccountKind.INCOME, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                ),
            categories =
                listOf(
                    CatalogSeedCategory(P705Fixture.foodParent, "餐饮", CategoryKind.EXPENSE, parentId = null, postingAccountId = null),
                    CatalogSeedCategory(CategoryId(CATEGORY_TEST), "测试分类", CategoryKind.EXPENSE, parentId = P705Fixture.foodParent, postingAccountId = P705Fixture.expenseAccount),
                    CatalogSeedCategory(CategoryId(CATEGORY_ARM), "三臂分类", CategoryKind.EXPENSE, parentId = P705Fixture.foodParent, postingAccountId = P705Fixture.expenseAccount),
                ),
        )

    private data class Annotation(
        val merchantId: String?,
        val tagIds: List<String>,
    )

    private companion object {
        fun transactionId(index: Int): String = "tx-p802-${index.toString().padStart(4, '0')}"

        fun inWindow(index: Int): Instant = IN_WINDOW_BASE + index.minutes

        val CLOCK_NOW: Instant = Instant.parse("2026-04-20T00:00:00Z")

        /** Exactly 2026-05-01T00:00 in Asia/Shanghai: the CurrentMonth clock sits on the month edge. */
        val MONTH_EDGE: Instant = Instant.parse("2026-04-30T16:00:00Z")
        val JUNE_START: Instant = Instant.parse("2026-05-31T16:00:00Z")

        /** Outside the projection's 64-bit nanosecond range (after 2262-04-11T23:47:15Z). */
        val UNREPRESENTABLE: Instant = Instant.parse("9999-01-01T00:00:00Z")
        val IN_WINDOW_BASE: Instant = Instant.parse("2026-02-01T00:00:00Z")
        val BEFORE_ALL: Instant = Instant.parse("2024-01-01T00:00:00Z")
        val AFTER_ALL: Instant = Instant.parse("2028-01-01T00:00:00Z")
        val BEFORE_ALL_NOTE: Instant = Instant.parse("2025-12-01T02:00:00Z")
        val AFTER_ALL_NOTE: Instant = Instant.parse("2026-05-01T02:00:00Z")
        val WIDE_WINDOW: SearchTimeWindow = SearchTimeWindow.Custom(BEFORE_ALL, AFTER_ALL)
        const val CATALOG_ITEM_VERSION: Long = 1L

        const val NOTE_MARKER = "咖啡拿铁"
        const val CATEGORY_TEST = "category-p802-test"
        const val CATEGORY_ARM = "category-p802-arm"
        const val MERCHANT_A = "merchant-p802-a"
        const val MERCHANT_RENAMED = "merchant-p802-renamed"
        const val MERCHANT_ARM = "merchant-p802-arm"
        const val TAG_A = "tag-p802-a"
        const val TAG_B = "tag-p802-b"

        val MERCHANT_NAMES =
            listOf(
                MERCHANT_A to "商家甲",
                MERCHANT_ARM to "三臂商家",
            )

        /** The seven semantic tables and the transaction whose posting carries the category in each. */
        val SEMANTIC_TABLE_TRANSACTIONS =
            listOf(
                "rg03_transfer_posting_semantic" to "tx-p802-0010",
                "rg04_posting_semantic" to "tx-p802-0011",
                "rg05_posting_semantic" to "tx-p802-0012",
                "rg06_posting_semantic" to "tx-p802-0013",
                "rg07_posting_semantic" to "tx-p802-0014",
                "rg11_posting_semantic" to "tx-p802-0015",
                "rg12_posting_semantic" to "tx-p802-0016",
            )

        /** Every in-window index, ascending: the keyset order is this list reversed. */
        val IN_WINDOW_INDEXES: List<Int> = (1..20).filter { it != 3 && it != 4 }

        val NOTE_BY_ID: Map<String, String> =
            mapOf(
                "tx-p802-0001" to "午餐 $NOTE_MARKER 25元",
                "tx-p802-0002" to "LATTE Macchiato",
                "tx-p802-0003" to "$NOTE_MARKER 旧窗",
                "tx-p802-0004" to "$NOTE_MARKER 新窗",
                "tx-p802-0005" to "普通备注一",
                "tx-p802-0006" to "普通备注二",
                "tx-p802-0007" to "三臂备注",
                "tx-p802-0008" to "中立备注八",
                "tx-p802-0009" to "中立备注九",
                "tx-p802-0010" to "分类夹具一",
                "tx-p802-0011" to "分类夹具二",
                "tx-p802-0012" to "分类夹具三",
                "tx-p802-0013" to "分类夹具四",
                "tx-p802-0014" to "分类夹具五",
                "tx-p802-0015" to "分类夹具六",
                "tx-p802-0016" to "分类夹具七",
                "tx-p802-0017" to "标签备注一",
                "tx-p802-0018" to "标签备注二",
                "tx-p802-0019" to "标签备注三",
                "tx-p802-0020" to "多版本备注",
            )

        val ANNOTATIONS: Map<String, Annotation> =
            mapOf(
                "tx-p802-0005" to Annotation(MERCHANT_A, emptyList()),
                "tx-p802-0006" to Annotation(MERCHANT_RENAMED, emptyList()),
                "tx-p802-0008" to Annotation(MERCHANT_ARM, emptyList()),
                "tx-p802-0017" to Annotation(null, listOf(TAG_A)),
                "tx-p802-0018" to Annotation(null, listOf(TAG_B)),
                "tx-p802-0019" to Annotation(MERCHANT_A, listOf(TAG_A, TAG_B)),
            )
    }
}
