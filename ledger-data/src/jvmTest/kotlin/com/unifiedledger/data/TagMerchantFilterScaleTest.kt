package com.unifiedledger.data

import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.MerchantAccumulationReadResult
import com.unifiedledger.application.MerchantMonthlyAccumulationResult
import com.unifiedledger.application.QueryMerchantMonthlyAccumulation
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.application.foldTagMerchantGroups
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.YearMonth

/**
 * P7-08 08.C scale leg (P708-A09 intent; the A09 acceptance registration belongs to the main
 * agent and the 50k/61k and device legs are explicitly NOT claimed here — this file is the
 * 20k programmatic leg only): 20,000 ordinary two-leg transactions (the P705 bulk fixture),
 * multi-tag high annotation density (two distinct tags per annotated transaction over a 40-tag
 * catalog) and a five-merchant catalog. The bounded paged read and the month accumulation read
 * must be equivalent, entry for entry and group for group, to the construction manifest's
 * expectations — with stable first/last items, exact totals and no deduplication of the many
 * duplicate amounts (the amount formula repeats every 97 transactions).
 *
 * Recorded only: every timing figure (printed under the stable `P708-A09 ` prefix and retained
 * in the JUnit XML's system-out) and the environment identity lines (the D-158 section 5
 * convention). Deliberately NOT asserted: any `EXPLAIN QUERY PLAN` text (the P7-05 design froze
 * plan assertions out; plan text is fragile across SQLite versions) and any wall-clock
 * threshold. No 50k/61k or device claim is made by this class.
 */
class TagMerchantFilterScaleTest {
    @Test
    fun boundedReadsAreEquivalentToTheConstructionManifestOnTwentyThousandTransactions() {
        P705Database.create("p708-08c-scale-").use { harness ->
            val buildStart = System.nanoTime()
            val annotated = buildFixture(harness)
            val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0

            // The fixture premise: 20k transactions, 10k annotated expenses (two tags each),
            // 1k merchant links, and the projection filled for every version row.
            assertEquals(TRANSACTION_COUNT.toLong(), harness.ledgerQueryCount("SELECT count(*) FROM ledger_transaction"))
            assertEquals((TRANSACTION_COUNT / 2).toLong(), harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_current"))
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_version WHERE statistics_at_epoch_nanos IS NULL"))

            val catalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)
            // The P705 bulk fixture writes no P7-01 catalog; bootstrap one whose accounts are
            // the fixture's own posting accounts, so the frozen classifier's account-kind
            // dispatch resolves every leg.
            assertTrue(catalogStore.bootstrap(P705Fixture.ledgerId, scaleCatalogSeed()) is com.unifiedledger.data.CatalogBootstrapResult.Seeded)
            val adapter =
                SqlDelightTagMerchantFilterReadAdapter(
                    database = harness.database,
                    accountCatalogReader = catalogStore,
                    tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(harness.database),
                )
            val ledgerId = P705Fixture.ledgerId
            val catalog = (catalogStore.load(ledgerId) as CatalogAuthority).catalog

            // ---- construction manifest (the OR condition over tags 00 and 01) ----
            val orCondition =
                TagMerchantFilterCondition(
                    tagIds = setOf(TagId("tag-00"), TagId("tag-01")),
                    tagMatch = TagMerchantTagMatchMode.Any,
                )
            val matchesOr = { index: Int -> index % TAG_COUNT == 0 || (index * 3 + 7) % TAG_COUNT == 1 }
            val expectedMatched = annotated.filter(matchesOr)
            val expectedOrOrder =
                expectedMatched
                    .sortedWith(compareByDescending<Int> { index -> statisticsInstant(index) }.thenByDescending { index -> transactionIdOf(index) })
                    .map(::transactionIdOf)

            // ---- bounded paged read, equivalent to the manifest ----
            val readStart = System.nanoTime()
            var cursor: TagMerchantFilterCursor? = null
            val visited = mutableListOf<String>()
            val entries =
                mutableListOf<com.unifiedledger.application.TagMerchantFilterEntry>()
            var pages = 0
            do {
                val page =
                    (adapter.readPage(ledgerId, orCondition, GENERATION, PAGE_SIZE, cursor) as TagMerchantFilterReadResult.Success).page
                pages += 1
                visited += page.entries.map { it.row.transactionId.value }
                entries += page.entries
                cursor = page.nextCursor
            } while (cursor != null)
            val readMs = (System.nanoTime() - readStart) / 1_000_000.0
            assertEquals(expectedOrOrder, visited, "the paged read must reproduce the manifest's total order exactly")
            assertEquals(visited.size, visited.toSet().size, "no transaction may appear twice across pages")

            // Group equivalence: the fold over the read entries equals the manifest totals; the
            // two overlapping groups are FULL amounts and are never additive.
            val groups = foldTagMerchantGroups(entries, orCondition.tagIds, ledgerId, catalog)
            val expectedTag00 = annotated.filter { it % TAG_COUNT == 0 }.sumOf(::amountMinor)
            val expectedTag01 = annotated.filter { (it * 3 + 7) % TAG_COUNT == 1 }.sumOf(::amountMinor)
            assertEquals(expectedTag00, groups.getValue(TagId("tag-00")).values.single())
            assertEquals(expectedTag01, groups.getValue(TagId("tag-01")).values.single())

            // The A01 overlap property on a GENUINELY overlapping pair: {tag-00, tag-07} share
            // exactly the multiples-of-40 transactions, so each group shows the pair's FULL
            // total and the two groups never add up to the matched set's total.
            val overlapCondition =
                TagMerchantFilterCondition(
                    tagIds = setOf(TagId("tag-00"), TagId("tag-07")),
                    tagMatch = TagMerchantTagMatchMode.Any,
                )
            val overlapEntries =
                buildList {
                    var c: TagMerchantFilterCursor? = null
                    do {
                        val page =
                            (adapter.readPage(ledgerId, overlapCondition, GENERATION, PAGE_SIZE, c) as TagMerchantFilterReadResult.Success).page
                        addAll(page.entries)
                        c = page.nextCursor
                    } while (c != null)
                }
            assertEquals(annotated.filter { it % TAG_COUNT == 0 }.size, overlapEntries.size)
            val overlapGroups = foldTagMerchantGroups(overlapEntries, overlapCondition.tagIds, ledgerId, catalog)
            val overlapTotal = overlapEntries.sumOf { entry -> entry.row.postings.first().amount.minorUnits }
            assertEquals(overlapTotal, overlapGroups.getValue(TagId("tag-00")).values.single())
            assertEquals(overlapTotal, overlapGroups.getValue(TagId("tag-07")).values.single())
            assertTrue(
                overlapGroups.getValue(TagId("tag-00")).values.single() + overlapGroups.getValue(TagId("tag-07")).values.single() != overlapTotal,
                "two overlapping groups must not add up to the matched set's total (A01)",
            )

            // Stable first/last items: a second read's endpoints equal the manifest's.
            val secondFirst =
                (adapter.readPage(ledgerId, orCondition, GENERATION, PAGE_SIZE, null) as TagMerchantFilterReadResult.Success).page.entries
                    .first()
            assertEquals(expectedOrOrder.first(), secondFirst.row.transactionId.value)
            assertEquals(expectedOrOrder.last(), visited.last())

            // AND semantics at scale: the two tags' intersection is exactly the manifest's.
            val andCondition =
                TagMerchantFilterCondition(
                    tagIds = setOf(TagId("tag-00"), TagId("tag-07")),
                    tagMatch = TagMerchantTagMatchMode.All,
                )
            // tag-00 ∩ tag-07: index ≡ 0 (mod 40) AND (3*index+7) ≡ 7 (mod 40) → index ≡ 0.
            val expectedAnd = annotated.filter { it % TAG_COUNT == 0 }
            var andCount = 0
            var andCursor: TagMerchantFilterCursor? = null
            do {
                val page =
                    (adapter.readPage(ledgerId, andCondition, GENERATION, PAGE_SIZE, andCursor) as TagMerchantFilterReadResult.Success).page
                andCount += page.entries.size
                andCursor = page.nextCursor
            } while (andCursor != null)
            assertEquals(expectedAnd.size, andCount)

            // Merchant accumulation equivalence (option (b) row source): the March window rows
            // of merchant-0 equal the manifest's March expenses annotated to it, and the frozen
            // classifier fold returns the manifest's exact net expense.
            val merchantZero = MerchantId("merchant-0")
            val expectedMerchantMarch =
                annotated.filter { (it / 10) % MERCHANT_COUNT == 0 && statisticsInstant(it) == P705Fixture.marchStatistics }
            val accumulation =
                QueryMerchantMonthlyAccumulation(adapter).query(ledgerId, merchantZero, YearMonth(2026, 3), GENERATION)
            val success = accumulation as MerchantMonthlyAccumulationResult.Success
            assertEquals(expectedMerchantMarch.size, success.transactionCount)
            assertEquals(expectedMerchantMarch.sumOf(::amountMinor), success.netExpenseByCurrency.values.single())
            val rows =
                (adapter.readMerchantRows(ledgerId, merchantZero, MARCH_START, APRIL_START, GENERATION) as MerchantAccumulationReadResult.Success).rows
            assertEquals(expectedMerchantMarch.map(::transactionIdOf).sorted(), rows.map { it.transactionId.value }.sorted())

            // Recorded only: timings and environment identity (no threshold is asserted).
            println("P708-A09 fixture transactions=$TRANSACTION_COUNT annotated=${annotated.size} pages=$pages page-size=$PAGE_SIZE")
            println("P708-A09 build-ms=$buildMs or-read-ms=$readMs")
            harness.environmentIdentity().forEach { println("P708-A09 environment $it") }
        }
    }

    // ------------------------------------------------------------------ fixture

    /**
     * Seeds the tag/merchant catalogs and the annotation aggregate with the frozen density:
     * every EXPENSE transaction (even index) carries tags `index mod 40` AND `(3*index+7) mod 40`
     * — two DISTINCT tags for every annotated transaction (2·index ≡ −7 (mod 40) has no solution:
     * its left side is even, its right side odd) — and every tenth annotated
     * transaction names merchant `(index/10) mod 5`. The numeric statistics projection is filled
     * for the fixture's two whole-second instants, where the epoch-nanosecond value is exact
     * (the raw bulk fixture writes the text column only).
     */
    private fun buildFixture(harness: P705Database): List<Int> {
        harness.insertOrdinaryBulk(
            count = TRANSACTION_COUNT,
            statisticsAt = { index -> statisticsInstant(index) },
        )
        val annotated = (0 until TRANSACTION_COUNT).filter { it % 2 == 0 }
        val ledger = P705Fixture.ledgerId.value
        harness.database.transaction {
            listOf(P705Fixture.marchStatistics, P705Fixture.aprilStatistics).forEach { instant ->
                val nanos = instant.toEpochMilliseconds() * 1_000_000L
                harness.driverExecute(
                    "UPDATE transaction_version SET statistics_at_epoch_nanos = $nanos " +
                        "WHERE statistics_at = '$instant' AND statistics_at_epoch_nanos IS NULL",
                )
            }
            insertChunked(harness, TAG_COUNT, "INSERT INTO catalog_tag(ledger_id, tag_id, active, tombstoned, revision) VALUES ") { index ->
                "('$ledger', 'tag-${tagLabel(index)}', 1, 0, 1)"
            }
            insertChunked(harness, TAG_COUNT, "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) VALUES ") { index ->
                "('$ledger', 'tag', 'tag-${tagLabel(index)}', 1, '标签${tagLabel(index)}', 'CURRENT')"
            }
            insertChunked(harness, MERCHANT_COUNT, "INSERT INTO catalog_merchant(ledger_id, merchant_id, active, tombstoned, revision) VALUES ") { index ->
                "('$ledger', 'merchant-$index', 1, 0, 1)"
            }
            insertChunked(harness, MERCHANT_COUNT, "INSERT INTO catalog_item_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) VALUES ") { index ->
                "('$ledger', 'merchant', 'merchant-$index', 1, '商家$index', 'CURRENT')"
            }
            harness.driverExecute("INSERT INTO catalog_item_version(ledger_id, version) VALUES ('$ledger', 0)")
            harness.driverExecute("UPDATE catalog_item_version SET version = version + 1 WHERE ledger_id = '$ledger'")
            insertChunked(
                harness,
                annotated.size,
                "INSERT INTO transaction_annotation_revision(ledger_id, transaction_id, annotation_revision, observed_transaction_version_id, merchant_id, request_id, created_at) VALUES ",
            ) { ordinal ->
                val index = annotated[ordinal]
                val merchant =
                    if ((index / 10) % MERCHANT_COUNT == 0) "'merchant-${(index / 10) % MERCHANT_COUNT}'" else "NULL"
                "('$ledger', '${transactionIdOf(index)}', 1, '${transactionIdOf(index)}-version-1', $merchant, 'request-${transactionIdOf(index)}', '2026-03-06T02:00:00Z')"
            }
            insertChunked(
                harness,
                annotated.size * 2,
                "INSERT INTO transaction_annotation_tag(ledger_id, transaction_id, annotation_revision, tag_id) VALUES ",
            ) { tagOrdinal ->
                val index = annotated[tagOrdinal / 2]
                val tag = if (tagOrdinal % 2 == 0) index % TAG_COUNT else (index * 3 + 7) % TAG_COUNT
                "('$ledger', '${transactionIdOf(index)}', 1, 'tag-${tagLabel(tag)}')"
            }
            insertChunked(
                harness,
                annotated.size,
                "INSERT INTO transaction_annotation_current(ledger_id, transaction_id, annotation_revision) VALUES ",
            ) { ordinal ->
                val index = annotated[ordinal]
                "('$ledger', '${transactionIdOf(index)}', 1)"
            }
        }
        return annotated
    }

    /** Chunked multi-row INSERT (the P705 bulk-fixture batching shape, 500 rows per statement). */
    private fun insertChunked(
        harness: P705Database,
        count: Int,
        statementPrefix: String,
        row: (Int) -> String,
    ) {
        var start = 0
        while (start < count) {
            val end = minOf(start + 500, count)
            harness.driverExecute(statementPrefix + (start until end).joinToString(", ") { row(it) })
            start = end
        }
    }

    private fun tagLabel(index: Int): String = index.toString().padStart(2, '0')

    /** A catalog seed over the bulk fixture's own posting accounts (the fold's kind dispatch). */
    private fun scaleCatalogSeed(): CatalogSeed =
        CatalogSeed(
            accounts =
                listOf(
                    CatalogSeedAccount(P705Fixture.bankA, "银行A", com.unifiedledger.domain.AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                    CatalogSeedAccount(P705Fixture.expenseAccount, "支出", com.unifiedledger.domain.AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                    CatalogSeedAccount(P705Fixture.incomeAccount, "收入", com.unifiedledger.domain.AccountKind.INCOME, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                ),
            categories =
                listOf(
                    CatalogSeedCategory(P705Fixture.foodParent, "餐饮", com.unifiedledger.domain.CategoryKind.EXPENSE, parentId = null, postingAccountId = null),
                    CatalogSeedCategory(P705Fixture.food, "食品", com.unifiedledger.domain.CategoryKind.EXPENSE, parentId = P705Fixture.foodParent, postingAccountId = P705Fixture.expenseAccount),
                ),
        )

    private fun transactionIdOf(index: Int): String = "tx-${(index + 1).toString().padStart(5, '0')}"

    private fun amountMinor(index: Int): Long = 1_000L + index % 97 * 137L

    private fun statisticsInstant(index: Int): Instant =
        if (index % 3 == 0) P705Fixture.aprilStatistics else P705Fixture.marchStatistics

    private companion object {
        /** The fixed catalog generation the scale fixture's tag/merchant catalog sits at. */
        const val GENERATION = 1L
        const val TRANSACTION_COUNT = 20_000
        const val TAG_COUNT = 40
        const val MERCHANT_COUNT = 5
        const val PAGE_SIZE = 500
        val MARCH_START = Instant.parse("2026-03-01T00:00:00Z")
        val APRIL_START = Instant.parse("2026-04-01T00:00:00Z")
    }
}
