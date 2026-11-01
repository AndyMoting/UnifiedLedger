package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.MerchantAccumulationReadResult
import com.unifiedledger.application.MerchantMonthlyAccumulationResult
import com.unifiedledger.application.QueryMerchantMonthlyAccumulation
import com.unifiedledger.application.TagMerchantFilterCondition
import com.unifiedledger.application.TagMerchantFilterCursor
import com.unifiedledger.application.TagMerchantFilterEntry
import com.unifiedledger.application.TagMerchantFilterReadResult
import com.unifiedledger.application.TagMerchantTagMatchMode
import com.unifiedledger.application.foldTagMerchantGroups
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-08 08.C scale legs (P708-A09; plan §8.3:308 names the 20,000 and 50,000 sizes): ordinary
 * two-leg transactions (the P705 bulk fixture) with multi-tag high annotation density (two
 * distinct tags per annotated transaction over a 40-tag catalog) and a five-merchant catalog. The
 * bounded paged read and the month accumulation read must be equivalent, entry for entry and
 * group for group, to the construction manifest's expectations — with stable first/last items,
 * exact totals and no deduplication of the many duplicate amounts (the amount formula repeats
 * every 97 transactions). The 50k and 61k legs additionally run on a counting connection and
 * record their SQL row surface, statement count and per-page statement set.
 *
 * The 61,000 leg is the follow-up JVM leg that D-241 item 2 and D-238 item 4 list for this
 * residual. D-243 item 1 rules that 61k is NOT part of the plan's original measurement set
 * (§2:42 and §10.3:414 name single 10k imports, 30k accumulated candidates and 20k duplicate
 * groups), so this leg extends the required 20k/50k sizes and satisfies no plan-mandated one.
 *
 * Recorded rather than thresholded (D-241 item 3): every timing figure (printed under the stable
 * `P708-A09 ` prefix and retained in the JUnit XML's system-out), the environment identity lines
 * (the D-158 section 5 convention) and the peak heap. What IS pinned is structural and derived
 * from the fixture: the row surface equals the fixture's own table counts, the paged read costs a
 * constant statement set per page, and the page shape is the one the manifest implies. Read those
 * pins narrowly — constant per-page statements prove that the statement set does not grow with the
 * page's row count or with the page position; they do NOT prove that no statement scans the whole
 * ledger, because nothing here measures rows examined inside a statement. The peak heap is sampled
 * every 10 ms and only ever checked for being non-empty, which is evidence that the sampler ran
 * and never a memory bound. Deliberately not asserted: any `EXPLAIN QUERY PLAN` text (the P7-05
 * design froze plan assertions out; plan text is fragile across SQLite versions), any wall-clock
 * or memory threshold, and any device claim — 61k is measured here as the JVM leg only, so the
 * device/ANR legs of D-241 stay open.
 */
class TagMerchantFilterScaleTest {
    @Test
    fun boundedReadsAreEquivalentToTheConstructionManifestOnTwentyThousandTransactions() {
        P705Database.create("p708-08c-scale-").use { harness ->
            val buildStart = System.nanoTime()
            val annotated = buildFixture(harness, TRANSACTION_COUNT)
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
            val overlapTotal =
                overlapEntries.sumOf { entry ->
                    entry.row.postings
                        .first()
                        .amount.minorUnits
                }
            assertEquals(overlapTotal, overlapGroups.getValue(TagId("tag-00")).values.single())
            assertEquals(overlapTotal, overlapGroups.getValue(TagId("tag-07")).values.single())
            assertTrue(
                overlapGroups.getValue(TagId("tag-00")).values.single() + overlapGroups.getValue(TagId("tag-07")).values.single() != overlapTotal,
                "two overlapping groups must not add up to the matched set's total (A01)",
            )

            // Stable first/last items: a second read's endpoints equal the manifest's.
            val secondFirst =
                (adapter.readPage(ledgerId, orCondition, GENERATION, PAGE_SIZE, null) as TagMerchantFilterReadResult.Success)
                    .page.entries
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

    /**
     * The 50k leg: the same fixture shape and the same manifest-equivalence assertions as the
     * 20k leg, read through the real bounded filter adapter over a counting second connection.
     * Every figure printed under `P708-A09 ` is recorded; this leg compares no timing or memory
     * figure to a limit.
     */
    @Test
    fun boundedReadsAreEquivalentToTheConstructionManifestOnFiftyThousandTransactions() {
        runMeasuredLeg("p708-a09-50k-", FIFTY_THOUSAND_TRANSACTIONS)
    }

    /**
     * The 61k leg: the follow-up JVM size that D-241 item 2 and D-238 item 4 list for the
     * P708-A09 residual. D-243 item 1 rules 61k outside the plan's original measurement set
     * (§2:42 and §10.3:414 name single 10k imports, 30k accumulated candidates and 20k duplicate
     * groups), so this leg satisfies no plan-mandated size and revises no such ruling. It shares
     * the 50k leg's whole manifest equivalence through [runMeasuredLeg] and adds the structural
     * facts that size the bounded read at this volume: the page count and the page fullness the
     * manifest implies, and a statement set that stays constant per page.
     */
    @Test
    fun boundedReadsAreEquivalentToTheConstructionManifestOnSixtyOneThousandTransactions() {
        val reading = runMeasuredLeg("p708-a09-61k-", SIXTY_ONE_THOUSAND_TRANSACTIONS)

        // The manifest decides the page shape: ceil(matched / page size) pages, every page full
        // but the last, and every manifest row returned exactly once. The fullness bound caps the
        // rows a page HANDS BACK to its caller; it says nothing about what one statement examines.
        assertEquals(
            (reading.matchedCount + PAGE_SIZE - 1) / PAGE_SIZE,
            reading.pages,
            "the page count is the manifest's ceil(matched / page size)",
        )
        assertEquals(reading.matchedCount, reading.returnedRows, "every matched transaction must come back exactly once")
        assertEquals(
            scanSurfaceRows(SIXTY_ONE_THOUSAND_TRANSACTIONS),
            reading.scannedRows,
            "the recorded row surface is the 61k fixture's table counts",
        )
        reading.pageSizes.dropLast(1).forEach { assertEquals(PAGE_SIZE, it, "every page but the last must be full") }
        assertEquals(
            reading.matchedCount - (reading.pages - 1) * PAGE_SIZE,
            reading.pageSizes.last(),
            "the last page carries exactly the manifest's remaining rows",
        )

        // Constant per-page statement cost: one page costs `pageStatements`, so the whole read must
        // cost that set times the page count. This pins the statement COUNT only — a statement
        // issued per returned row, or a per-page catalog re-read that grows with the page count,
        // breaks the equality. It bounds nothing inside a single statement, so it is not evidence
        // that no statement reads the whole ledger.
        assertTrue(reading.pageStatements > 0, "a filter page must issue SQL statements")
        assertEquals(
            reading.pages * reading.pageStatements,
            reading.sqlQueries,
            "the paged read costs a constant statement set per page (no N+1)",
        )

        // D-241 item 3 keeps memory recorded, never thresholded: this checks only that the sampler
        // observed a non-empty peak, i.e. that the measurement ran. It bounds no memory figure, and
        // this leg makes no device/ANR/OOM claim.
        assertTrue(reading.peakHeapBytes > 0, "the heap sampler must observe a non-empty peak")
    }

    /**
     * The measured leg shared by the 50k and 61k cases: [transactionCount] ordinary two-leg
     * transactions built from the same formula, read page by page through the real bounded filter
     * adapter over a counting second connection. Its assertions are the 50k leg's own set (D-241
     * item 2), expressed over the parameterised count. Nothing here compares a timing or a memory
     * figure to a limit; the recorded figures travel back in [MeasuredLeg] and are printed under
     * the `P708-A09 ` prefix for the record.
     */
    private fun runMeasuredLeg(
        filePrefix: String,
        transactionCount: Int,
    ): MeasuredLeg =
        P705Database.create(filePrefix).use { harness ->
            val buildStart = System.nanoTime()
            val annotated = buildFixture(harness, transactionCount)
            val buildNanos = System.nanoTime() - buildStart

            assertEquals(transactionCount.toLong(), harness.ledgerQueryCount("SELECT count(*) FROM ledger_transaction"))
            assertEquals(
                (transactionCount / 2).toLong(),
                harness.ledgerQueryCount("SELECT count(*) FROM transaction_annotation_current"),
            )
            assertEquals(0L, harness.ledgerQueryCount("SELECT count(*) FROM transaction_version WHERE statistics_at_epoch_nanos IS NULL"))
            assertEquals(
                (transactionCount * 2).toLong(),
                harness.ledgerQueryCount("SELECT count(*) FROM posting"),
            )

            // The P705 bulk fixture writes no P7-01 catalog; bootstrap one whose accounts are
            // the fixture's own posting accounts, so the frozen classifier's account-kind
            // dispatch resolves every leg (the 20k leg does the same on its own harness).
            val fixtureCatalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)
            assertTrue(fixtureCatalogStore.bootstrap(P705Fixture.ledgerId, scaleCatalogSeed()) is com.unifiedledger.data.CatalogBootstrapResult.Seeded)

            val rawDriver = JdbcSqliteDriver("jdbc:sqlite:${harness.filePath}")
            try {
                val countingDriver = CountingSqlDriver(rawDriver)
                val countingDatabase = LedgerDatabase(countingDriver)
                val catalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(countingDatabase)
                val adapter =
                    SqlDelightTagMerchantFilterReadAdapter(
                        database = countingDatabase,
                        accountCatalogReader = catalogStore,
                        tagMerchantCatalogReader = SqlDelightTagMerchantCatalogStore.forPlatformConfiguredDatabase(countingDatabase),
                    )
                val ledgerId = P705Fixture.ledgerId
                val catalog = requireNotNull(catalogStore.load(ledgerId)).catalog

                // The base-table row surface the paged statement joins, counted before the
                // measured window is armed (it is a record, not part of `sql-queries`).
                val scannedRows =
                    SCAN_SURFACE_TABLES.sumOf { table ->
                        queryLong(countingDriver, "SELECT count(*) FROM $table WHERE ledger_id = '${ledgerId.value}'")
                    }
                countingDriver.resetCounts()
                assertEquals(scanSurfaceRows(transactionCount), scannedRows, "the recorded row surface is the fixture's table counts")

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

                // The per-page statement set, measured on a warm head page before the timed
                // window: the first read absorbs any one-time connection work and is then re-read
                // to record what one page costs. Neither read is part of `paged-read-ms` or
                // `sql-queries`; the head page is the manifest's first page either way.
                val warmHeadPage =
                    (adapter.readPage(ledgerId, orCondition, GENERATION, PAGE_SIZE, null) as TagMerchantFilterReadResult.Success)
                        .page.entries
                        .map { it.row.transactionId.value }
                countingDriver.resetCounts()
                val probeFirstItem =
                    (adapter.readPage(ledgerId, orCondition, GENERATION, PAGE_SIZE, null) as TagMerchantFilterReadResult.Success)
                        .page.entries
                        .first()
                        .row.transactionId.value
                val pageStatements = countingDriver.queryCount
                countingDriver.resetCounts()
                assertEquals(expectedOrOrder.take(PAGE_SIZE), warmHeadPage, "the head page is the manifest's first page")
                assertEquals(expectedOrOrder.first(), probeFirstItem, "a repeated head read starts at the same first item")

                // ---- measured bounded paged read ----
                val sampler = JvmHeapPeakSampler.startSampling()
                val readStart = System.nanoTime()
                val visited = mutableListOf<String>()
                val entries = mutableListOf<TagMerchantFilterEntry>()
                val pageSizes = mutableListOf<Int>()
                var pages = 0
                var cursor: TagMerchantFilterCursor? = null
                var readNanos = 0L
                var peakHeapBytes = 0L
                try {
                    do {
                        val page =
                            (adapter.readPage(ledgerId, orCondition, GENERATION, PAGE_SIZE, cursor) as TagMerchantFilterReadResult.Success).page
                        pages += 1
                        pageSizes += page.entries.size
                        visited += page.entries.map { it.row.transactionId.value }
                        entries += page.entries
                        cursor = page.nextCursor
                    } while (cursor != null)
                } finally {
                    readNanos = System.nanoTime() - readStart
                    peakHeapBytes = sampler.stop()
                }
                val sqlQueries = countingDriver.queryCount
                assertTrue(sqlQueries > 0, "the counting connection must observe the paged read's SQL statements")

                assertEquals(expectedOrOrder, visited, "the paged read must reproduce the manifest's total order exactly")
                assertEquals(visited.size, visited.toSet().size, "no transaction may appear twice across pages")

                // Group equivalence: the fold over the read entries equals the manifest totals; the
                // two overlapping groups are FULL amounts and are never additive.
                val groups = foldTagMerchantGroups(entries, orCondition.tagIds, ledgerId, catalog)
                val expectedTag00 = annotated.filter { it % TAG_COUNT == 0 }.sumOf(::amountMinor)
                val expectedTag01 = annotated.filter { (it * 3 + 7) % TAG_COUNT == 1 }.sumOf(::amountMinor)
                assertEquals(expectedTag00, groups.getValue(TagId("tag-00")).values.single())
                assertEquals(expectedTag01, groups.getValue(TagId("tag-01")).values.single())

                // The A01 overlap property on the genuinely overlapping pair {tag-00, tag-07}.
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
                val overlapTotal =
                    overlapEntries.sumOf { entry ->
                        entry.row.postings
                            .first()
                            .amount.minorUnits
                    }
                assertEquals(overlapTotal, overlapGroups.getValue(TagId("tag-00")).values.single())
                assertEquals(overlapTotal, overlapGroups.getValue(TagId("tag-07")).values.single())
                assertTrue(
                    overlapGroups.getValue(TagId("tag-00")).values.single() + overlapGroups.getValue(TagId("tag-07")).values.single() != overlapTotal,
                    "two overlapping groups must not add up to the matched set's total (A01)",
                )

                // Stable first/last items: a second read's endpoints equal the manifest's.
                val secondFirst =
                    (adapter.readPage(ledgerId, orCondition, GENERATION, PAGE_SIZE, null) as TagMerchantFilterReadResult.Success)
                        .page.entries
                        .first()
                assertEquals(expectedOrOrder.first(), secondFirst.row.transactionId.value)
                assertEquals(expectedOrOrder.last(), visited.last())

                // AND semantics at scale: the two tags' intersection is exactly the manifest's.
                val andCondition =
                    TagMerchantFilterCondition(
                        tagIds = setOf(TagId("tag-00"), TagId("tag-07")),
                        tagMatch = TagMerchantTagMatchMode.All,
                    )
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

                // Merchant accumulation equivalence (the same March window as the 20k leg).
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

                // Recorded only: timings, the row surface and environment identity (no threshold).
                println(
                    "P708-A09 fixture transactions=$transactionCount annotated=${annotated.size} " +
                        "pages=$pages page-size=$PAGE_SIZE scanned-rows=$scannedRows returned-rows=${visited.size} " +
                        "sql-queries=$sqlQueries page-statements=$pageStatements peak-heap-bytes=$peakHeapBytes (jvm-heap-only) " +
                        "build-ms=${millis(buildNanos)} paged-read-ms=${millis(readNanos)}",
                )
                harness.environmentIdentity().forEach { println("P708-A09 environment $it") }

                MeasuredLeg(
                    matchedCount = expectedMatched.size,
                    returnedRows = visited.size,
                    pages = pages,
                    pageSizes = pageSizes,
                    pageStatements = pageStatements,
                    sqlQueries = sqlQueries,
                    scannedRows = scannedRows,
                    peakHeapBytes = peakHeapBytes,
                )
            } finally {
                rawDriver.getConnection().close()
                rawDriver.close()
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
    private fun buildFixture(
        harness: P705Database,
        transactionCount: Int,
    ): List<Int> {
        harness.insertOrdinaryBulk(
            count = transactionCount,
            statisticsAt = { index -> statisticsInstant(index) },
        )
        val annotated = (0 until transactionCount).filter { it % 2 == 0 }
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

    private fun statisticsInstant(index: Int): Instant = if (index % 3 == 0) P705Fixture.aprilStatistics else P705Fixture.marchStatistics

    /**
     * What a measured leg hands back to its caller: the manifest-derived structural facts the
     * caller may assert on, plus the recorded figures (returned rows, row surface, statement
     * counts, page sizes, peak heap). No timing is ever asserted. The peak heap is recorded only;
     * the 61k case checks nothing beyond its sampler having observed a non-empty peak (D-241
     * item 3).
     */
    private data class MeasuredLeg(
        val matchedCount: Int,
        val returnedRows: Int,
        val pages: Int,
        val pageSizes: List<Int>,
        val pageStatements: Int,
        val sqlQueries: Int,
        val scannedRows: Long,
        val peakHeapBytes: Long,
    )

    private companion object {
        /** The fixed catalog generation the scale fixture's tag/merchant catalog sits at. */
        const val GENERATION = 1L
        const val TRANSACTION_COUNT = 20_000
        const val FIFTY_THOUSAND_TRANSACTIONS = 50_000
        const val SIXTY_ONE_THOUSAND_TRANSACTIONS = 61_000

        /**
         * The exact per-ledger total of [SCAN_SURFACE_TABLES] for a fixture of [count]
         * transactions: one transaction, version and current-version pointer row per transaction,
         * one posting set per transaction, two postings per transaction, and — for the annotated
         * half — one annotation revision, one current pointer and two tag rows. Derived from the
         * fixture formulas rather than typed as a literal, so a change to those formulas cannot
         * leave a stale constant passing.
         */
        fun scanSurfaceRows(count: Int): Long = count.toLong() * 6L + (count / 2).toLong() * 4L

        const val TAG_COUNT = 40
        const val MERCHANT_COUNT = 5
        const val PAGE_SIZE = 500
        val MARCH_START = Instant.parse("2026-03-01T00:00:00Z")
        val APRIL_START = Instant.parse("2026-04-01T00:00:00Z")

        /**
         * The base tables the paged filter statement joins (its CTE, its two EXISTS semi-joins
         * and the posting projection). Their per-ledger row total is the recorded `scanned-rows`
         * surface; it is not an engine-instrumented scan count and is never asserted.
         */
        val SCAN_SURFACE_TABLES =
            listOf(
                "ledger_transaction",
                "ledger_transaction_current_version",
                "transaction_version",
                "posting_set",
                "posting",
                "transaction_annotation_current",
                "transaction_annotation_tag",
                "transaction_annotation_revision",
                "transaction_void_fact",
            )
    }
}
