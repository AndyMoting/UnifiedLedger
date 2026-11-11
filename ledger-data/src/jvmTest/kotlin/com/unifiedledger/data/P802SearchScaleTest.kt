package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P802_SEARCH_PAGE_SIZE
import com.unifiedledger.application.QuerySearchPage
import com.unifiedledger.application.SearchCondition
import com.unifiedledger.application.SearchReadResult
import com.unifiedledger.application.SearchTimeWindow
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-02 i1 V-8, the JVM leg of the first performance calibration (spec section 5 V-8; D2=A as ruled
 * in section 3: no threshold is set here, the first readings BECOME the baseline).
 *
 * The sample is a 61,000 TRANSACTION ledger — the vector's sample premise, because the existing 61k
 * fixture is an import-candidate library holding two transactions and cannot stand in for this one.
 * It reuses the P7-05 harness (`P705Database`, the chunked bulk writer) instead of a second fixture
 * rig; nothing in that harness is modified.
 *
 * Deterministic coverage of all three keyword arms over the same ledger, each asserted against the
 * construction manifest row by row (never merely "non-empty"):
 *
 *  - note: every 7th transaction carries the note marker;
 *  - merchant name: every 70th transaction names merchant-0 or merchant-1 through its CURRENT
 *    annotation, so the merchant arm (`transaction_annotation_current` -> `_revision.merchant_id`)
 *    has a manifest of its own;
 *  - category name: seven transactions carry one posting-semantic category row each, one row in
 *    every one of the seven `rgXX_posting_semantic` tables.
 *
 * Recorded (never asserted against a bound): the port's first-page duration for the fixed condition
 * group on the default 90-day window, that page's row count, the fixture build duration, and the
 * fixture's transaction count. The last one IS asserted, because it is a fact of the sample rather
 * than a reading. A second first-page duration is recorded for the default window plus a category
 * keyword only — labelled `category-arm-window-only`, NOT the fixed group — because the fixed
 * group's keyword switches the seven-table category arm off.
 * Device/instrumented timing stays with the i2 device leg (D-289 residual 1).
 */
class P802SearchScaleTest {
    @Test
    fun v8SixtyOneThousandTransactionSearchPageIsRecordedAsTheFirstBaseline() {
        P705Database.create("p802-v8-").use { harness ->
            val buildStart = System.nanoTime()
            buildFixture(harness)
            val buildNanos = System.nanoTime() - buildStart

            // The sample fact, asserted: 61k TRANSACTIONS, and every version row projected (a NULL
            // projection would fail the read loudly by design).
            assertEquals(TRANSACTION_COUNT.toLong(), harness.ledgerQueryCount("SELECT count(*) FROM ledger_transaction"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_version WHERE statistics_at_epoch_nanos IS NULL"))

            val fixtureCatalog = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)
            assertTrue(fixtureCatalog.bootstrap(P705Fixture.ledgerId, scaleCatalogSeed()) is CatalogBootstrapResult.Seeded)

            val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${harness.filePath}")
            try {
                val countingDriver = CountingSqlDriver(rawDriver)
                val database = LedgerDatabase(countingDriver)
                val search =
                    QuerySearchPage(
                        readPort = SqlDelightSearchReadAdapter(database),
                        catalogReader = SqlDelightCatalogStore.forPlatformConfiguredDatabase(database),
                        tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(database),
                        clock = { CLOCK_NOW },
                    )

                // The recorded reading: the fixed condition group (all four items) on the default
                // 90-day window, first page only.
                countingDriver.resetCounts()
                val firstPageStart = System.nanoTime()
                val firstPage = assertIs<SearchReadResult.Success>(search.query(P705Fixture.ledgerId, FIXED_CONDITION)).page
                val firstPageNanos = System.nanoTime() - firstPageStart
                // The whole use-case call (both catalog name loads, the projection probe, the page and the count): recorded, never thresholded.
                val firstPageStatements = countingDriver.queryCount

                val noteMatches = noteManifest()
                val merchantMatches = merchantManifest()
                // The fixed group is keyword AND tags AND merchant, so its matched set is the
                // merchant manifest's (every multiple of 70 is also a multiple of 7).
                assertEquals(merchantMatches.size.toLong(), firstPage.totalCount, "the fixed group's total is its manifest")
                // The frozen page size as a literal, never the constant compared against itself.
                assertEquals(
                    50,
                    firstPage.entries.size,
                    "the fixed group fills a whole first page of the frozen page size",
                )
                assertTrue(firstPage.hasMore, "the fixed group needs more than one page")

                // ---- note arm: the whole matched set equals the construction manifest, row by row
                val noteIds = traverse(search, SearchCondition(keyword = NOTE_MARKER))
                assertEquals(expectedOrder(noteMatches), noteIds, "the note arm's matched set is the manifest, in keyset order")

                // ---- merchant name arm
                val merchantIds = traverse(search, SearchCondition(keyword = MERCHANT_ZERO_NAME))
                assertEquals(expectedOrder(merchantMatches), merchantIds, "the merchant name arm's matched set is the manifest")

                // ---- category name arm: one row per semantic table
                val categoryMatches = SEMANTIC_INDEXES.values.toList()
                val categoryIds = traverse(search, SearchCondition(keyword = CATEGORY_NAME))
                assertEquals(expectedOrder(categoryMatches), categoryIds, "all seven semantic-table rows are reachable")

                // The category-arm reading: this condition is the default window plus this keyword
                // ONLY — three of its four items differ from the fixed group timed above — so the
                // two numbers measure different selectivity and are labelled apart. The keyword
                // resolves a real category name, so `categoryArm` is 1 and the seven-table union is
                // actually paid; the fixed group's note keyword resolves no category name, so that
                // reading has the cost switched off. Recorded, never bounded, and pinned to the
                // manifest below so the printed number cannot drift from what the fixture holds.
                val categoryPageStart = System.nanoTime()
                val categoryPage = assertIs<SearchReadResult.Success>(search.query(P705Fixture.ledgerId, SearchCondition(keyword = CATEGORY_NAME))).page
                val categoryPageNanos = System.nanoTime() - categoryPageStart
                assertEquals(
                    categoryMatches.size.toLong(),
                    categoryPage.totalCount,
                    "the category page's total is the seven-row semantic manifest",
                )
                println(
                    "P802-V8 category-arm-window-only-first-page-ms=${millis(categoryPageNanos)} " +
                        "category-arm-window-only-first-page-rows=${categoryPage.entries.size} " +
                        "category-arm-window-only-first-page-total=${categoryPage.totalCount}",
                )

                // Recorded only: no timing or statement threshold is asserted (D2=A).
                println(
                    "P802-V8 sample=61k-transactions first-page-ms=${millis(firstPageNanos)} " +
                        "first-page-rows=${firstPage.entries.size} first-page-total=${firstPage.totalCount} " +
                        "call-statements=$firstPageStatements " +
                        "build-ms=${millis(buildNanos)} " +
                        "transactions=$TRANSACTION_COUNT note-matches=${noteMatches.size} " +
                        "merchant-matches=${merchantMatches.size} category-matches=${categoryIds.size}",
                )
                harness.environmentIdentity().forEach { println("P802-V8 environment $it") }
            } finally {
                rawDriver.getConnection().close()
                rawDriver.close()
            }
        }
    }

    // ------------------------------------------------------------------------------ fixture

    /**
     * The 61k ledger: note markers on every [NOTE_STRIDE]-th transaction, a current annotation
     * (tag-00 plus merchant-0/1) on every [ANNOTATION_STRIDE]-th one, and the seven semantic rows.
     */
    private fun buildFixture(harness: P705Database) {
        harness.insertOrdinaryBulk(
            count = TRANSACTION_COUNT,
            note = { index -> if (index % NOTE_STRIDE == 0) NOTE_MARKER else null },
            statisticsAt = ::statisticsInstant,
        )
        val ledger = P705Fixture.ledgerId.value
        harness.database.transaction {
            // The bulk writer writes the statistics TEXT only; fill the numeric projection exactly
            // as the product statement would, per distinct instant (whole seconds, so exact).
            listOf(P705Fixture.marchStatistics, P705Fixture.aprilStatistics).forEach { instant ->
                val nanos = instant.toEpochMilliseconds() * 1_000_000L
                harness.driverExecute(
                    "UPDATE transaction_version SET statistics_at_epoch_nanos = $nanos " +
                        "WHERE statistics_at = '$instant' AND statistics_at_epoch_nanos IS NULL",
                )
            }
            harness.driverExecute("INSERT INTO catalog_tag(ledger_id, tag_id, active, tombstoned, revision) VALUES ('$ledger', '$TAG_ZERO', 1, 0, 1)")
            harness.driverExecute(
                "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                    "VALUES ('$ledger', 'tag', '$TAG_ZERO', 1, '标签零', 'CURRENT')",
            )
            MERCHANT_NAMES.forEachIndexed { index, name ->
                harness.driverExecute(
                    "INSERT INTO catalog_merchant(ledger_id, merchant_id, active, tombstoned, revision) " +
                        "VALUES ('$ledger', 'merchant-$index', 1, 0, 1)",
                )
                harness.driverExecute(
                    "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) " +
                        "VALUES ('$ledger', 'merchant', 'merchant-$index', 1, '$name', 'CURRENT')",
                )
            }
            harness.driverExecute("INSERT INTO catalog_item_version(ledger_id, version) VALUES ('$ledger', 0)")
            harness.driverExecute("UPDATE catalog_item_version SET version = version + 1 WHERE ledger_id = '$ledger'")

            val annotated = annotatedIndexes()
            insertChunked(
                harness,
                annotated.size,
                "INSERT INTO transaction_annotation_revision(ledger_id, transaction_id, annotation_revision, " +
                    "observed_transaction_version_id, merchant_id, request_id, created_at) VALUES ",
            ) { ordinal ->
                val index = annotated[ordinal]
                val merchant = "'merchant-${(index / ANNOTATION_STRIDE) % MERCHANT_NAMES.size}'"
                "('$ledger', '${transactionIdOf(index)}', 1, '${transactionIdOf(index)}-version-1', $merchant, " +
                    "'request-${transactionIdOf(index)}', '2026-03-06T02:00:00Z')"
            }
            insertChunked(
                harness,
                annotated.size,
                "INSERT INTO transaction_annotation_tag(ledger_id, transaction_id, annotation_revision, tag_id) VALUES ",
            ) { ordinal ->
                "('$ledger', '${transactionIdOf(annotated[ordinal])}', 1, '$TAG_ZERO')"
            }
            insertChunked(
                harness,
                annotated.size,
                "INSERT INTO transaction_annotation_current(ledger_id, transaction_id, annotation_revision) VALUES ",
            ) { ordinal ->
                "('$ledger', '${transactionIdOf(annotated[ordinal])}', 1)"
            }

            SEMANTIC_INDEXES.forEach { (table, index) ->
                val posting = "${transactionIdOf(index)}-posting-0"
                when (table) {
                    "rg06_posting_semantic" -> {
                        harness.driverExecute(
                            "INSERT INTO rg06_relation(ledger_id, relation_id, relation_type, payload_marker) " +
                                "VALUES ('$ledger', 'relation-v8', 'staged_payment', '{}')",
                        )
                        harness.driverExecute(
                            "INSERT INTO rg06_installment(ledger_id, relation_id, installment_index, payment_id, payment_role, " +
                                "amount_minor, currency_code, currency_precision, funding_account_id, transaction_id, " +
                                "transaction_version_id, posting_set_id, expense_posting_id, asset_posting_id, " +
                                "actual_payment_at, statistics_at) VALUES ('$ledger', 'relation-v8', 0, 'payment-v8', " +
                                "'DEPOSIT', 1000, '${P705Fixture.cny.code}', ${P705Fixture.cny.precision}, " +
                                "'${P705Fixture.bankA.value}', '${transactionIdOf(index)}', '${transactionIdOf(index)}-version-1', " +
                                "'${transactionIdOf(index)}-posting-set-1', '$posting', '${transactionIdOf(index)}-posting-1', " +
                                "'2026-03-05T02:00:00Z', '2026-03-05T02:00:00Z')",
                        )
                        harness.driverExecute(
                            "INSERT INTO rg06_posting_semantic(ledger_id, posting_id, payment_id, role, category_id, " +
                                "reconciliation_eligible) VALUES ('$ledger', '$posting', 'payment-v8', 'expense', '$CATEGORY_ID', 0)",
                        )
                    }
                    "rg11_posting_semantic" -> {
                        // The rg11 ownership guard admits an `expense` row only on a PREPAID_RECOGNITION
                        // transaction.
                        harness.driverExecute(
                            "UPDATE ledger_transaction SET canonical_kind = 'PREPAID_RECOGNITION' " +
                                "WHERE ledger_id = '$ledger' AND transaction_id = '${transactionIdOf(index)}'",
                        )
                        harness.driverExecute(
                            "INSERT INTO rg11_posting_semantic(ledger_id, posting_id, role, reconciliation_eligible, category_id) " +
                                "VALUES ('$ledger', '$posting', 'expense', 0, '$CATEGORY_ID')",
                        )
                    }
                    else -> harness.driverExecute(semanticInsert(table, ledger, posting))
                }
            }
        }
    }

    /** The per-table INSERT for the six tables whose row shape needs nothing but the category. */
    private fun semanticInsert(
        table: String,
        ledger: String,
        posting: String,
    ): String =
        when (table) {
            "rg03_transfer_posting_semantic" ->
                "INSERT INTO rg03_transfer_posting_semantic(ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', '$posting', 'TRANSFER_FEE', '$CATEGORY_ID', 0)"
            "rg05_posting_semantic" ->
                "INSERT INTO rg05_posting_semantic(ledger_id, posting_id, role, item_id, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', '$posting', 'expense', 'item-v8', '$CATEGORY_ID', 0)"
            else ->
                "INSERT INTO $table(ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                    "VALUES ('$ledger', '$posting', 'expense', '$CATEGORY_ID', 0)"
        }

    private fun insertChunked(
        harness: P705Database,
        count: Int,
        statementPrefix: String,
        row: (Int) -> String,
    ) {
        var start = 0
        while (start < count) {
            val end = minOf(start + CHUNK, count)
            harness.driverExecute(statementPrefix + (start until end).joinToString(", ") { row(it) })
            start = end
        }
    }

    // ------------------------------------------------------------------------------ manifests

    /** Every transaction carrying the note marker, ascending by construction index. */
    private fun noteManifest(): List<Int> = (0 until TRANSACTION_COUNT).filter { it % NOTE_STRIDE == 0 }

    /** Every transaction whose current annotation names merchant-0, ascending by index. */
    private fun merchantManifest(): List<Int> = annotatedIndexes().filter { (it / ANNOTATION_STRIDE) % MERCHANT_NAMES.size == 0 }

    private fun annotatedIndexes(): List<Int> = (0 until TRANSACTION_COUNT).filter { it % ANNOTATION_STRIDE == 0 }

    private fun traverse(
        search: QuerySearchPage,
        condition: SearchCondition,
    ): List<String> {
        val visited = mutableListOf<String>()
        var cursor: TagMerchantFilterCursor? = null
        do {
            val page = assertIs<SearchReadResult.Success>(search.query(P705Fixture.ledgerId, condition, P802_SEARCH_PAGE_SIZE, cursor)).page
            assertTrue(page.entries.size <= P802_SEARCH_PAGE_SIZE, "a page never exceeds the page size")
            visited += page.entries.map { it.transactionId.value }
            cursor = page.nextCursor
        } while (cursor != null)
        return visited
    }

    private fun expectedOrder(indices: List<Int>): List<String> =
        indices
            .sortedWith(compareByDescending<Int> { index -> statisticsInstant(index) }.thenByDescending { index -> transactionIdOf(index) })
            .map(::transactionIdOf)

    private fun transactionIdOf(index: Int): String = "tx-${(index + 1).toString().padStart(5, '0')}"

    /** Two whole-second instants, both inside the default 90-day window of [CLOCK_NOW]. */
    private fun statisticsInstant(index: Int): Instant = if (index % 3 == 0) P705Fixture.aprilStatistics else P705Fixture.marchStatistics

    /** A catalog seed over the bulk fixture's own posting accounts (the read only needs names). */
    private fun scaleCatalogSeed(): CatalogSeed =
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
                    CatalogSeedCategory(CategoryId(CATEGORY_ID), CATEGORY_NAME, CategoryKind.EXPENSE, parentId = P705Fixture.foodParent, postingAccountId = P705Fixture.expenseAccount),
                ),
        )

    private companion object {
        val CLOCK_NOW: Instant = Instant.parse("2026-04-20T00:00:00Z")
        const val TRANSACTION_COUNT = 61_000
        const val NOTE_STRIDE = 7
        const val ANNOTATION_STRIDE = 70
        const val CHUNK = 500
        const val NOTE_MARKER = "咖啡标记"
        const val CATEGORY_ID = "category-p802-seven"
        const val CATEGORY_NAME = "分类七表"
        const val TAG_ZERO = "tag-00"
        const val MERCHANT_ZERO_NAME = "商家零"
        val MERCHANT_NAMES = listOf(MERCHANT_ZERO_NAME, "商家一")

        /** The fixed condition group of the recorded reading: keyword + tags + merchant filter. */
        val FIXED_CONDITION: SearchCondition =
            SearchCondition(
                keyword = NOTE_MARKER,
                window = SearchTimeWindow.LastNinetyDays,
                tagIds = setOf(TagId(TAG_ZERO)),
                merchantFilterId = MerchantId("merchant-0"),
            )

        /** One transaction per semantic table, so every table has a row the category arm can reach. */
        val SEMANTIC_INDEXES: Map<String, Int> =
            mapOf(
                "rg03_transfer_posting_semantic" to 11,
                "rg04_posting_semantic" to 12,
                "rg05_posting_semantic" to 13,
                "rg06_posting_semantic" to 14,
                "rg07_posting_semantic" to 15,
                "rg11_posting_semantic" to 16,
                "rg12_posting_semantic" to 10,
            )
    }
}
