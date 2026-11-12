package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-03 i2a candidate-read vectors (D-312; spec sections 2.3/2.4/5), against the real JVM SQLite
 * driver. Each test name carries its acceptance vector number from the approved spec.
 *
 * - V-13 (JVM half): a candidate set with no rows is `Loaded(emptyList(), hasMore = false)`, a failed
 *   read is `Unavailable` and is never rendered as that empty page, the `pageSize + 1` lookahead is
 *   what turns a full page into `hasMore`, and the displayed category name is the catalog's CURRENT
 *   name of the inherited category. The separate fallback test is not a vector: it pins the
 *   implementation reading registered in spec section 8 (no CURRENT name row -> the category id).
 * - V-3 (JVM half): the candidate row equals `selectManualRefundableOriginal` (the submit side's
 *   re-resolution) field for field, and both read the replacement version once the current version's
 *   expense leg moves to another account (hence another category).
 * - V-15: the 20k and 50k first-page and refunded-total readings are PRINTED as a baseline, one line
 *   per size. No duration is asserted and no threshold is set: only structural facts are pinned (the
 *   page bound, the lookahead verdict, the currency, a positive remaining amount, the candidate count
 *   through deep-offset probes), and five seeded effective refunds pin the refunded total (500) and a
 *   candidate's remaining amount (500) against the aggregate read and its effectiveness join.
 */
class P803ManualRefundCandidateReadTest {
    @Test
    fun v13AnEmptyCandidateSetIsALoadedEmptyPage() {
        P803CandidateDatabase.inMemory().use { world ->
            seedCandidateCatalog(world.driver)
            assertEquals(
                ManualRefundCandidatesResult.Loaded(emptyList(), hasMore = false),
                world.adapter.readPage(LedgerId(CANDIDATE_LEDGER), pageSize = 50, pageOffset = 0),
            )
        }
    }

    @Test
    fun v13AFailedReadIsUnavailableAndNeverTheEmptyPage() {
        P803CandidateDatabase.inMemory().use { world ->
            seedCandidateCatalog(world.driver)
            seedCandidateTransaction(world.driver, "tx-original", CANDIDATE_DAILY_ACCOUNT, 12_000L, "2026-01-10T02:00:00Z")
            // Deterministic failure injection: the candidate statement reads the refund relationship
            // table for the remaining amount, so dropping that table makes the page statement fail
            // to prepare. This throwaway harness is never read again.
            executeSql(world.driver, "DROP TABLE manual_refund_receipt")

            val failed: ManualRefundCandidatesResult = world.adapter.readPage(LedgerId(CANDIDATE_LEDGER), pageSize = 50, pageOffset = 0)
            val emptyPage: ManualRefundCandidatesResult = ManualRefundCandidatesResult.Loaded(emptyList(), hasMore = false)
            assertIs<ManualRefundCandidatesResult.Unavailable>(failed)
            assertNotEquals(emptyPage, failed)
        }
    }

    @Test
    fun v13TheLookaheadTurnsExactlyPageSizePlusOneIntoHasMore() {
        P803CandidateDatabase.inMemory().use { world ->
            seedCandidateCatalog(world.driver)
            seedCandidateTransaction(world.driver, "tx-a", CANDIDATE_DAILY_ACCOUNT, 10_000L, "2026-01-10T02:00:00Z")
            seedCandidateTransaction(world.driver, "tx-b", CANDIDATE_DAILY_ACCOUNT, 11_000L, "2026-01-11T02:00:00Z")
            seedCandidateTransaction(world.driver, "tx-c", CANDIDATE_DAILY_ACCOUNT, 12_000L, "2026-01-12T02:00:00Z")
            val ledger = LedgerId(CANDIDATE_LEDGER)

            val first = assertIs<ManualRefundCandidatesResult.Loaded>(world.adapter.readPage(ledger, pageSize = 2, pageOffset = 0))
            assertEquals(listOf("tx-c", "tx-b"), first.candidates.map { it.transactionId.value })
            assertTrue(first.hasMore, "exactly pageSize + 1 candidates means one more page")
            assertEquals(Instant.parse("2026-01-12T02:00:00Z"), first.candidates.first().occurredAt)
            assertEquals(CANDIDATE_DAILY_NAME, first.candidates.first().categoryName)

            val second = assertIs<ManualRefundCandidatesResult.Loaded>(world.adapter.readPage(ledger, pageSize = 2, pageOffset = 2))
            assertEquals(listOf("tx-a"), second.candidates.map { it.transactionId.value })
            assertFalse(second.hasMore)

            // Exactly pageSize rows is the last page: the lookahead decides, not the row count.
            val exact = assertIs<ManualRefundCandidatesResult.Loaded>(world.adapter.readPage(ledger, pageSize = 3, pageOffset = 0))
            assertEquals(3, exact.candidates.size)
            assertFalse(exact.hasMore)
        }
    }

    @Test
    fun aMissingCurrentNameFallsBackToTheCategoryId() {
        P803CandidateDatabase.inMemory().use { world ->
            seedCandidateCatalog(world.driver)
            seedCandidateTransaction(world.driver, "tx-original", CANDIDATE_DAILY_ACCOUNT, 12_000L, "2026-01-10T02:00:00Z")
            executeSql(world.driver, "DELETE FROM catalog_name_history WHERE ledger_id = '$CANDIDATE_LEDGER' AND owner_kind = 'category' AND owner_id = '$CANDIDATE_DAILY'")

            val candidate =
                assertIs<ManualRefundCandidatesResult.Loaded>(world.adapter.readPage(LedgerId(CANDIDATE_LEDGER), pageSize = 50, pageOffset = 0))
                    .candidates
                    .single()
            // Not an acceptance vector: the implementation reading is registered in spec section 8 -
            // a category without a CURRENT name row stays selectable and displays its raw id.
            assertEquals(CANDIDATE_DAILY, candidate.categoryName)
        }
    }

    @Test
    fun v3TheCandidateRowEqualsTheServerResolutionAndFollowsTheCurrentVersion() {
        P803CandidateDatabase.inMemory().use { world ->
            seedCandidateCatalog(world.driver)
            seedCandidateTransaction(world.driver, "tx-original", CANDIDATE_DAILY_ACCOUNT, 12_000L, "2026-01-10T02:00:00Z")
            val ledger = LedgerId(CANDIDATE_LEDGER)

            val candidate = assertIs<ManualRefundCandidatesResult.Loaded>(world.adapter.readPage(ledger, pageSize = 50, pageOffset = 0)).candidates.single()
            val resolved = serverResolution(world, "tx-original")
            assertEquals(resolved.categoryId, candidate.categoryId.value)
            assertEquals(CANDIDATE_DAILY_NAME, candidate.categoryName)
            assertEquals(resolved.structuralCapMinor, candidate.structuralCap.minorUnits)
            assertEquals(resolved.currencyCode, candidate.structuralCap.currency.code)
            assertEquals(resolved.currencyPrecision.toInt(), candidate.structuralCap.currency.precision)
            // No refund row exists yet, so the whole structural cap is still refundable.
            assertEquals(candidate.structuralCap, candidate.remaining)

            // Replace the current version: the expense leg now posts to account B, which inherits
            // category B, and the cap follows the new leg's amount. Both reads must move together.
            replaceCurrentVersion(world.driver, "tx-original", CANDIDATE_OTHER_ACCOUNT, 9_000L)
            val replaced = assertIs<ManualRefundCandidatesResult.Loaded>(world.adapter.readPage(ledger, pageSize = 50, pageOffset = 0)).candidates.single()
            val reResolved = serverResolution(world, "tx-original")
            assertEquals(candidate.transactionId, replaced.transactionId)
            assertEquals(CANDIDATE_OTHER, replaced.categoryId.value)
            assertEquals(CANDIDATE_OTHER_NAME, replaced.categoryName)
            assertEquals(9_000L, replaced.structuralCap.minorUnits)
            assertEquals(reResolved.categoryId, replaced.categoryId.value)
            assertEquals(reResolved.structuralCapMinor, replaced.structuralCap.minorUnits)
            // The date follows the current version too, not the replaced first version.
            assertEquals(Instant.parse("2026-02-01T02:00:00Z"), replaced.occurredAt)
        }
    }

    @Test
    fun v15CandidatePageAndRefundedTotalBaselinesOnTwentyThousandAndFiftyThousandTransactions() {
        measureCandidateBaseline(transactionCount = 20_000)
        measureCandidateBaseline(transactionCount = 50_000)
    }

    private fun measureCandidateBaseline(transactionCount: Int) {
        P705Database.create("p803-v15-").use { harness ->
            harness.insertOrdinaryBulk(count = transactionCount)
            val catalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)
            assertTrue(
                catalogStore.bootstrap(P705Fixture.ledgerId, candidateScaleSeed()) is CatalogBootstrapResult.Seeded,
                "the bulk fixture's expense account needs exactly one active secondary EXPENSE category",
            )
            // One original carries five effective refunds, so the refunded-total reading and the
            // candidate's remaining amount exercise the relationship table and its effectiveness
            // join instead of an empty range. Bulk transaction 0's structural cap is 1_000, so it
            // stays a candidate (remaining 500).
            seedEffectiveRefunds(harness)
            val adapter = SqlDelightManualRefundCandidatesReadAdapter.forPlatformConfiguredDatabase(harness.database)
            val ledger = P705Fixture.ledgerId

            // (a) The first screen and (b) one original's refunded total, timed and recorded only.
            val pageStart = System.nanoTime()
            val page = assertIs<ManualRefundCandidatesResult.Loaded>(adapter.readPage(ledger, pageSize = V15_PAGE_SIZE, pageOffset = 0))
            val pageMs = (System.nanoTime() - pageStart) / 1_000_000.0

            val refundedStart = System.nanoTime()
            val refunded =
                harness.database.ledgerQueries
                    .selectManualRefundRefundedTotal(ledger.value, harness.bulkTransactionId(0), P705Fixture.cny.code)
                    .executeAsOne()
            val refundedTotalMs = (System.nanoTime() - refundedStart) / 1_000_000.0

            // The frozen page size as a literal (house rule: never compare a constant against itself).
            assertEquals(50, page.candidates.size)
            assertTrue(page.hasMore, "a $transactionCount-transaction ledger holds many more than one page")
            assertEquals(setOf(P705Fixture.cny), page.candidates.map { it.structuralCap.currency }.toSet())
            assertEquals(setOf(P705Fixture.cny), page.candidates.map { it.remaining.currency }.toSet())
            assertTrue(page.candidates.all { it.remaining.minorUnits > 0L }, "candidate condition 7: remaining > 0")
            assertEquals(500L, refunded, "five effective 100 refunds on bulk transaction 0")
            val refundedOriginal = page.candidates.first { it.transactionId.value == harness.bulkTransactionId(0) }
            assertEquals(500L, refundedOriginal.remaining.minorUnits, "the cap minus the five effective refunds")

            // The candidate predicate decides the last page, not the page size: deep-offset probes
            // pin the eligible row count at transactionCount / 2 (the bulk fixture alternates
            // EXPENSE and INCOME) instead of accepting "at least one page".
            val lastPage = assertIs<ManualRefundCandidatesResult.Loaded>(adapter.readPage(ledger, pageSize = V15_PAGE_SIZE, pageOffset = transactionCount / 2 - 1))
            assertEquals(1, lastPage.candidates.size, "exactly transactionCount / 2 eligible originals")
            assertFalse(lastPage.hasMore)
            val pastEnd = assertIs<ManualRefundCandidatesResult.Loaded>(adapter.readPage(ledger, pageSize = V15_PAGE_SIZE, pageOffset = transactionCount / 2))
            assertTrue(pastEnd.candidates.isEmpty())
            assertFalse(pastEnd.hasMore)

            println("P803-V15 size=$transactionCount pageMs=$pageMs refundedTotalMs=$refundedTotalMs rows=${page.candidates.size} hasMore=${page.hasMore}")
        }
    }

    /** Five effective refund rows of 100 minor units on bulk transaction 0, the V-15 read's subject. */
    private fun seedEffectiveRefunds(harness: P705Database) {
        repeat(5) { index ->
            harness.database.ledgerQueries.insertManualRefundReceipt(
                ledger_id = P705Fixture.ledgerId.value,
                relationship_id = "v15-relationship-$index",
                request_id = "v15-request-$index",
                request_fingerprint = "v15-fingerprint-$index",
                original_transaction_id = harness.bulkTransactionId(0),
                refund_transaction_id = harness.bulkTransactionId(index * 2 + 1),
                amount_minor = 100L,
                currency_code = P705Fixture.cny.code,
                currency_precision = P705Fixture.cny.precision.toLong(),
                destination_account_id = P705Fixture.bankA.value,
                category_id = P705Fixture.food.value,
                arrived_at = "2026-03-06T02:00:00Z",
                confirmed_at = "2026-03-06T02:00:00Z",
                created_at = "2026-03-06T02:00:00Z",
                confirmation_marker = "explicit_manual_confirm",
            )
        }
    }
}

/** One throwaway in-memory database with the shipped schema and the candidate read adapter. */
private class P803CandidateDatabase(
    val driver: JdbcSqliteDriver,
) : AutoCloseable {
    val database = LedgerDatabase(driver)
    val adapter = SqlDelightManualRefundCandidatesReadAdapter(database)

    override fun close() {
        driver.close()
    }

    companion object {
        fun inMemory(): P803CandidateDatabase {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            LedgerDatabase.Schema.create(driver)
            return P803CandidateDatabase(driver)
        }
    }
}

/** The four columns [serverResolution] compares against one candidate row. */
private data class P803ServerOriginal(
    val categoryId: String,
    val structuralCapMinor: Long,
    val currencyCode: String,
    val currencyPrecision: Long,
)

private fun serverResolution(
    world: P803CandidateDatabase,
    transactionId: String,
): P803ServerOriginal =
    world.database.ledgerQueries
        .selectManualRefundableOriginal(CANDIDATE_LEDGER, transactionId) {
            categoryId,
            structuralCapMinor,
            currencyCode,
            currencyPrecision,
            ->
            P803ServerOriginal(categoryId, structuralCapMinor, currencyCode, currencyPrecision)
        }.executeAsOne()

/**
 * The 20k/50k catalog seed: the bulk fixture's own three posting accounts, one EXPENSE parent and one
 * active secondary EXPENSE category on [P705Fixture.expenseAccount] — the single inheritable category
 * candidate condition 5 requires (the shape the P7-08 scale fixture bootstraps).
 */
private fun candidateScaleSeed(): CatalogSeed =
    CatalogSeed(
        accounts =
            listOf(
                CatalogSeedAccount(P705Fixture.bankA, "银行A", AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                CatalogSeedAccount(P705Fixture.expenseAccount, "支出", AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
                CatalogSeedAccount(P705Fixture.incomeAccount, "收入", AccountKind.INCOME, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = true),
            ),
        categories =
            listOf(
                CatalogSeedCategory(P705Fixture.foodParent, "支出父类", CategoryKind.EXPENSE, parentId = null, postingAccountId = null),
                CatalogSeedCategory(P705Fixture.food, "支出明细", CategoryKind.EXPENSE, parentId = P705Fixture.foodParent, postingAccountId = P705Fixture.expenseAccount),
            ),
    )

/** The catalog of the in-memory vectors: two expense accounts, one asset, two inheritable leaves. */
private fun seedCandidateCatalog(driver: JdbcSqliteDriver) {
    executeSql(driver, "INSERT INTO catalog_version VALUES ('$CANDIDATE_LEDGER', 1)")
    executeSql(driver, "INSERT INTO catalog_account VALUES ('$CANDIDATE_LEDGER', '$CANDIDATE_DAILY_ACCOUNT', 'daily expense', 'EXPENSE', 'CNY', 2, 0, 0, NULL, 1, 1)")
    executeSql(driver, "INSERT INTO catalog_account VALUES ('$CANDIDATE_LEDGER', '$CANDIDATE_OTHER_ACCOUNT', 'other expense', 'EXPENSE', 'CNY', 2, 0, 0, NULL, 1, 1)")
    executeSql(driver, "INSERT INTO catalog_account VALUES ('$CANDIDATE_LEDGER', '$CANDIDATE_ASSET', 'asset', 'ASSET', 'CNY', 2, 1, 1, NULL, 0, 1)")
    executeSql(driver, "INSERT INTO catalog_category VALUES ('$CANDIDATE_LEDGER', '$CANDIDATE_ROOT', 'EXPENSE', NULL, NULL, 1)")
    executeSql(driver, "INSERT INTO catalog_category VALUES ('$CANDIDATE_LEDGER', '$CANDIDATE_DAILY', 'EXPENSE', '$CANDIDATE_ROOT', '$CANDIDATE_DAILY_ACCOUNT', 1)")
    executeSql(driver, "INSERT INTO catalog_category VALUES ('$CANDIDATE_LEDGER', '$CANDIDATE_OTHER', 'EXPENSE', '$CANDIDATE_ROOT', '$CANDIDATE_OTHER_ACCOUNT', 1)")
    // The candidate's display name is the CURRENT name history row; the raw id is only the fallback.
    seedCategoryName(driver, CANDIDATE_DAILY, CANDIDATE_DAILY_NAME)
    seedCategoryName(driver, CANDIDATE_OTHER, CANDIDATE_OTHER_NAME)
}

private fun seedCategoryName(
    driver: JdbcSqliteDriver,
    categoryId: String,
    name: String,
) {
    executeSql(
        driver,
        "INSERT INTO catalog_name_history(ledger_id, owner_kind, owner_id, version_number, name, status) VALUES ('$CANDIDATE_LEDGER', 'category', '$categoryId', 1, '$name', 'CURRENT')",
    )
}

/** One effective two-leg EXPENSE original: the expense leg on [expenseAccount], the asset leg off it. */
private fun seedCandidateTransaction(
    driver: JdbcSqliteDriver,
    transactionId: String,
    expenseAccount: String,
    amountMinor: Long,
    occurredAt: String,
) {
    executeSql(driver, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('$transactionId', '$CANDIDATE_LEDGER', 'EXPENSE', NULL)")
    executeSql(driver, "INSERT INTO posting_set VALUES ('ps-$transactionId', '$CANDIDATE_LEDGER')")
    executeSql(
        driver,
        "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-$transactionId', '$transactionId', '$CANDIDATE_LEDGER', 1, 'ps-$transactionId', '$occurredAt', '$occurredAt', '$occurredAt', NULL)",
    )
    executeSql(driver, "INSERT INTO ledger_transaction_current_version VALUES ('$transactionId', '$CANDIDATE_LEDGER', 'version-$transactionId')")
    executeSql(driver, "INSERT INTO posting VALUES ('posting-$transactionId-0', 'ps-$transactionId', '$CANDIDATE_LEDGER', 0, '$expenseAccount', $amountMinor, 'CNY', 2)")
    executeSql(driver, "INSERT INTO posting VALUES ('posting-$transactionId-1', 'ps-$transactionId', '$CANDIDATE_LEDGER', 1, '$CANDIDATE_ASSET', ${-amountMinor}, 'CNY', 2)")
}

/** A second version that moves the expense leg to [expenseAccount], then points the current version at it. */
private fun replaceCurrentVersion(
    driver: JdbcSqliteDriver,
    transactionId: String,
    expenseAccount: String,
    amountMinor: Long,
) {
    val versionId = "version-$transactionId-2"
    val postingSetId = "ps-$transactionId-2"
    executeSql(driver, "INSERT INTO posting_set VALUES ('$postingSetId', '$CANDIDATE_LEDGER')")
    executeSql(
        driver,
        "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('$versionId', '$transactionId', '$CANDIDATE_LEDGER', 2, '$postingSetId', '2026-02-01T02:00:00Z', '2026-02-01T02:00:00Z', '2026-02-01T02:00:00Z', NULL)",
    )
    executeSql(driver, "INSERT INTO posting VALUES ('posting-$transactionId-2-0', '$postingSetId', '$CANDIDATE_LEDGER', 0, '$expenseAccount', $amountMinor, 'CNY', 2)")
    executeSql(driver, "INSERT INTO posting VALUES ('posting-$transactionId-2-1', '$postingSetId', '$CANDIDATE_LEDGER', 1, '$CANDIDATE_ASSET', ${-amountMinor}, 'CNY', 2)")
    executeSql(driver, "UPDATE ledger_transaction_current_version SET current_version_id = '$versionId' WHERE ledger_id = '$CANDIDATE_LEDGER' AND transaction_id = '$transactionId'")
}

private fun executeSql(
    driver: JdbcSqliteDriver,
    sql: String,
) {
    driver.execute(null, sql, 0)
}

private const val CANDIDATE_LEDGER = "ledger-p803-candidates"
private const val CANDIDATE_DAILY_ACCOUNT = "account-p803-daily"
private const val CANDIDATE_OTHER_ACCOUNT = "account-p803-other"
private const val CANDIDATE_ASSET = "account-p803-asset"
private const val CANDIDATE_ROOT = "category-p803-root"
private const val CANDIDATE_DAILY = "category-p803-daily"
private const val CANDIDATE_OTHER = "category-p803-other"
private const val CANDIDATE_DAILY_NAME = "餐饮-午后"
private const val CANDIDATE_OTHER_NAME = "分类B-采购"
private const val V15_PAGE_SIZE = 50
