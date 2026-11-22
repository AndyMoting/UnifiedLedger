package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.P806AccountRequest
import com.unifiedledger.application.P806CalendarQuery
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806CategoryCurrencyTotal
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806DrilldownSnapshot
import com.unifiedledger.application.P806DrilldownView
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportQuery
import com.unifiedledger.application.P806ReportSnapshot
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806Window
import com.unifiedledger.application.QueryMonthlyActivity
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch a (D-343; spec sections 3.5/3.9 and 4): coverage added by the quality review —
 * per-currency counts in the view, category drilldown pages/counts on a real database, the
 * ordered first page, the real cross-request axis growth, the three zero catalog counters, the
 * income-side fold signs, and the CI-only strict-parser boundary vectors.
 *
 * Executed by cloud CI only; fixtures are anonymous and synthetic.
 */
class P806CoverageTest {
    private val ledgerId = LedgerId("ledger-p806-coverage")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)

    private val cash = AccountId("asset-cash")
    private val usdCash = AccountId("asset-usd-cash")
    private val foodParent = CategoryId("cat-food")
    private val foodOther = CategoryId("cat-food-other")
    private val breakfast = AccountId("expense-breakfast")
    private val dinner = AccountId("expense-dinner")
    private val salary = AccountId("income-salary")
    private val salaryParent = CategoryId("cat-salary")
    private val salaryLeaf = CategoryId("cat-salary-leaf")
    private val bonusAccount = AccountId("income-bonus")
    private val usdExpense = AccountId("expense-usd")
    private val miscExpense = AccountId("expense-misc")

    @Test
    fun perCurrencyCountsComeFromTheSnapshotAndNeverSumToTheGlobalCount() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, allAccounts(), version)
            inFixtureTransaction(driver) {
                // One transaction spans CNY and USD; a second one is CNY only.
                insertTransaction(
                    driver,
                    "tx-both",
                    "EXPENSE",
                    null,
                    "2026-11-02T00:00:00Z",
                    "2026-11-02T00:00:00Z",
                    listOf(breakfast to 100L, cash to -100L, usdExpense to 50L, usdCash to -50L),
                    currencyByAccount = mapOf(usdExpense to usd, usdCash to usd),
                )
                insertTransaction(driver, "tx-cny", "EXPENSE", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(breakfast to 200L, cash to -200L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val view =
                (
                    P806ReportQuery(adapter, ledgerId, CatalogAuthority(ledgerId, catalog, version), FixedClock("2026-11-17T00:00:00Z"))
                        .query(P806Period(P806PeriodKind.MONTH)) as P806SnapshotResult.Success<P806ReportView>
                ).snapshot
            assertEquals(2, view.transactionCount)
            assertEquals(2, view.currencies.first { it.currency == cny }.transactionCount)
            assertEquals(1, view.currencies.first { it.currency == usd }.transactionCount)
            // The per-currency counts overlap on the shared transaction: they must NOT be summed.
            assertTrue(view.currencies.first { it.currency == cny }.transactionCount + view.currencies.first { it.currency == usd }.transactionCount > view.transactionCount)
            // The ledger activity axis carries both currencies of the shared transaction.
            val raw = (adapter.readReportSnapshot(reportRequest(version, catalog)) as P806SnapshotResult.Success<P806ReportSnapshot>).snapshot
            assertEquals(listOf(cny, usd), raw.currencyAxis)
            // Each currency balances on its own and keeps its own amounts.
            assertEquals(300L, view.currencies.first { it.currency == cny }.netExpenseMinorUnits)
            assertEquals(50L, view.currencies.first { it.currency == usd }.netExpenseMinorUnits)
            assertEquals(3, view.currencies.first { it.currency == cny }.transactionCount + view.currencies.first { it.currency == usd }.transactionCount)
        }
    }

    @Test
    fun categoryDrilldownPagesCoverLevelOneLevelTwoAndTheWholePeriodWithoutDuplicates() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, allAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-1", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-2", "EXPENSE", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(dinner to 200L, cash to -200L))
                // A transaction with no categorized leg must never appear in a category page.
                insertTransaction(driver, "tx-3", "INCOME", null, "2026-11-04T00:00:00Z", "2026-11-04T00:00:00Z", listOf(salary to -300L, cash to 300L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val window = novemberWindow()
            val levelOne = page(adapter, window, foodParent.value, version)
            assertEquals(
                setOf("tx-1", "tx-2"),
                levelOne.page.rows
                    .map { it.transactionId.value }
                    .toSet(),
            )
            assertEquals(2, levelOne.totalCount)
            val levelTwo = page(adapter, window, foodOther.value, version)
            assertEquals(listOf("tx-2"), levelTwo.page.rows.map { it.transactionId.value })
            assertEquals(1, levelTwo.totalCount)
            val wholePeriod = page(adapter, window, categoryFilter = "", version)
            // categoryId = null means ALL ordinary contributions, not the uncategorized subset.
            assertEquals(
                setOf("tx-1", "tx-2", "tx-3"),
                wholePeriod.page.rows
                    .map { it.transactionId.value }
                    .toSet(),
            )
            assertEquals(3, wholePeriod.totalCount)
            assertEquals(
                3,
                wholePeriod.page.rows
                    .map { it.transactionId.value }
                    .toSet()
                    .size,
            )
        }
    }

    @Test
    fun theFirstPageEqualsTheIndependentlyOrderedContributionSetPrefix() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, allAccounts(), version)
            val stamps = (0 until 60).map { index -> "2026-11-%02dT%02d:00:00Z".format(2 + index % 20, index % 24) }
            inFixtureTransaction(driver) {
                stamps.forEachIndexed { index, stamp ->
                    insertTransaction(driver, "tx-$index", "EXPENSE", null, stamp, stamp, listOf(breakfast to 100L, cash to -100L))
                }
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val snapshot = (adapter.readReportSnapshot(reportRequest(version, catalog)) as P806SnapshotResult.Success<P806ReportSnapshot>).snapshot
            val expectedOrder =
                stamps
                    .withIndex()
                    .sortedWith(
                        compareByDescending<IndexedValue<String>> { Instant.parse(it.value).toEpochMilliseconds() }
                            .thenByDescending { "tx-${it.index}" },
                    ).take(50)
                    .map { "tx-${it.index}" }
            assertEquals(expectedOrder, snapshot.firstPage.rows.map { it.transactionId.value })
            assertEquals(50, snapshot.firstPage.rows.size)
            assertNotNull(snapshot.firstPage.nextCursor)
            assertEquals(60, snapshot.detailCount)
        }
    }

    @Test
    fun theAxisGrowsOnALaterRequestWhileTheEarlierSnapshotKeepsItsOwnAxis() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, allAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-cny", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val authority = CatalogAuthority(ledgerId, catalog, version)
            val calendar = P806CalendarQuery(adapter, ledgerId, authority, FixedClock("2026-11-17T00:00:00Z"))
            val first = (calendar.query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)) as P806SnapshotResult.Success<P806CalendarView>).snapshot
            assertEquals(listOf(cny), first.axis)
            assertTrue(first.days.all { it.currencies.size == 1 })

            // A legal USD activity OUTSIDE the window changes only the ledger axis; the catalog
            // version and the authority stay the same generation.
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-usd-oct", "EXPENSE", null, "2026-10-02T00:00:00Z", "2026-10-02T00:00:00Z", listOf(usdExpense to 50L, usdCash to -50L), currency = usd)
            }
            val second = (calendar.query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)) as P806SnapshotResult.Success<P806CalendarView>).snapshot
            assertEquals(listOf(cny, usd), second.axis)
            // The zero day now carries one zero row per axis currency.
            val zeroDay = second.days.first { it.date == kotlinx.datetime.LocalDate(2026, 11, 5) }
            assertEquals(0, zeroDay.transactionCount)
            assertEquals(2, zeroDay.currencies.size)
            // The earlier snapshot is unchanged: no stale axis reuse in either direction.
            assertEquals(listOf(cny), first.axis)
        }
    }

    @Test
    fun theThreeCatalogCountersAreExactlyZeroOnCleanWindowsAndAnEmptyOne() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, allAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-clean", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            // V-11 clean side: the clean window is genuinely non-empty — the report and its detail
            // page carry the one contribution, so the zero counters are not a vacuous empty read.
            val clean =
                assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(
                    adapter.readReportSnapshot(reportRequest(version, catalog)),
                ).snapshot
            assertEquals(1, clean.transactionCount)
            assertEquals(1, clean.detailCount)
            assertEquals(1, clean.firstPage.rows.size)
            assertEquals(listOf(cny), clean.currencyCounts.map { it.currency })
            val emptyWindow =
                P806Window(
                    Instant.parse("2027-01-01T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
                    Instant.parse("2027-02-01T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
                )
            val empty = (adapter.readDrilldownSnapshot(P806DrilldownRequest(ledgerId, emptyWindow, expectedCatalogVersion = version)) as P806SnapshotResult.Success<P806DrilldownSnapshot>).snapshot
            assertEquals(0, empty.totalCount)
            assertTrue(empty.page.rows.isEmpty())
            assertNull(empty.page.nextCursor)
            // V-11: a REQUESTED window with no rows is a successful empty report (the ledger has a
            // span, so this is not NoPeriod): every amount, count and page is zero, never a failure.
            val emptyReport =
                assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(
                    adapter.readReportSnapshot(
                        com.unifiedledger.application.P806ReportRequest(
                            ledgerId = ledgerId,
                            catalog = catalog,
                            period =
                                P806Period(
                                    P806PeriodKind.CUSTOM,
                                    customStart = kotlinx.datetime.LocalDate(2027, 1, 1),
                                    customEnd = kotlinx.datetime.LocalDate(2027, 1, 31),
                                ),
                            today = kotlinx.datetime.LocalDate(2026, 11, 17),
                            futureFromEpochNanos = Instant.parse("2026-11-18T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
                            expectedCatalogVersion = version,
                        ),
                    ),
                ).snapshot
            assertNotNull(emptyReport.window, "a requested window stays a window, not NoPeriod")
            assertEquals(0, emptyReport.transactionCount)
            assertEquals(0, emptyReport.detailCount)
            assertEquals(0, emptyReport.futureTransactionCount)
            assertTrue(emptyReport.postingTotals.isEmpty())
            assertTrue(emptyReport.currencyCounts.isEmpty())
            assertTrue(emptyReport.firstPage.rows.isEmpty())
            assertNull(emptyReport.firstPage.nextCursor)

            // The three counters are read directly (test-only queries; they do not change the
            // production C0): a clean window and an empty window must both answer 0, never NULL.
            val cleanWindow = novemberWindow()
            val cleanProbe =
                LedgerDatabase(driver)
                    .ledgerQueries
                    .p806StatisticsCatalogConsistencyProbe(
                        ledger_id = ledgerId.value,
                        window_start_nanos = cleanWindow.startEpochNanos,
                        window_end_nanos = cleanWindow.endEpochNanos,
                    ).executeAsOne()
            assertEquals(0L, cleanProbe.unresolved_account)
            assertEquals(0L, cleanProbe.cross_ledger_account)
            assertEquals(0L, cleanProbe.currency_mismatch)
            val emptyProbe =
                LedgerDatabase(driver)
                    .ledgerQueries
                    .p806StatisticsCatalogConsistencyProbe(
                        ledger_id = ledgerId.value,
                        window_start_nanos = emptyWindow.startEpochNanos,
                        window_end_nanos = emptyWindow.endEpochNanos,
                    ).executeAsOne()
            assertEquals(0L, emptyProbe.unresolved_account)
            assertEquals(0L, emptyProbe.cross_ledger_account)
            assertEquals(0L, emptyProbe.currency_mismatch)
            val occurredProbe =
                LedgerDatabase(driver)
                    .ledgerQueries
                    .p806OccurredAtCatalogConsistencyProbe(
                        ledger_id = ledgerId.value,
                        window_start_nanos = Long.MIN_VALUE,
                        window_end_nanos = Long.MAX_VALUE,
                    ).executeAsOne()
            assertEquals(0L, occurredProbe.unresolved_account)
            assertEquals(0L, occurredProbe.cross_ledger_account)
            assertEquals(0L, occurredProbe.currency_mismatch)
        }
    }

    @Test
    fun topLevelCategorySumsIncludingUncategorizedReconcileWithTheMonthlyCard() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, allAccounts() + miscExpenseAccount(), version)
            inFixtureTransaction(driver) {
                // Categorized and uncategorized expense legs, each with a refund, plus categorized
                // and unmapped income legs. All values are anonymous.
                insertTransaction(driver, "tx-breakfast", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-dinner", "EXPENSE", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(dinner to 200L, cash to -200L))
                insertTransaction(driver, "tx-misc", "EXPENSE", null, "2026-11-04T00:00:00Z", "2026-11-04T00:00:00Z", listOf(miscExpense to 300L, cash to -300L))
                insertTransaction(driver, "tx-dinner-refund", "EXPENSE", "REFUND_RECEIPT", "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", listOf(dinner to -50L, cash to 50L))
                insertTransaction(driver, "tx-misc-refund", "EXPENSE", "REFUND_RECEIPT", "2026-11-06T00:00:00Z", "2026-11-06T00:00:00Z", listOf(miscExpense to -25L, cash to 25L))
                insertTransaction(driver, "tx-salary", "INCOME", null, "2026-11-07T00:00:00Z", "2026-11-07T00:00:00Z", listOf(salary to -5_000L, cash to 5_000L))
                insertTransaction(driver, "tx-bonus-reversal", "INCOME", null, "2026-11-08T00:00:00Z", "2026-11-08T00:00:00Z", listOf(bonusAccount to 700L, cash to -700L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val clock = FixedClock("2026-11-17T00:00:00Z")
            val view =
                (
                    P806ReportQuery(adapter, ledgerId, CatalogAuthority(ledgerId, catalog, version), clock)
                        .query(P806Period(P806PeriodKind.MONTH)) as P806SnapshotResult.Success<P806ReportView>
                ).snapshot
            val monthly =
                assertIs<MonthlyActivityResult.Success>(
                    QueryMonthlyActivity(adapter, ledgerId, catalog, clock)
                        .query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)),
                ).activity
            val card = monthly.currencies.first { it.currency == cny }

            // V-5: the top-level category rows (each one already the checked sum of its children),
            // plus the explicit uncategorized totals, must reconstruct the monthly card exactly.
            val expensePositive =
                view.expenseCategories.sumOf { it.totals.cnyValue { total -> total.positiveMinorUnits } } +
                    view.uncategorizedExpenseTotals.cnyValue { it.positiveMinorUnits }
            val expenseRefund =
                view.expenseCategories.sumOf { it.totals.cnyValue { total -> total.refundMinorUnits } } +
                    view.uncategorizedExpenseTotals.cnyValue { it.refundMinorUnits }
            val incomePositive =
                view.incomeCategories.sumOf { it.totals.cnyValue { total -> total.positiveMinorUnits } } +
                    view.uncategorizedIncomeTotals.cnyValue { it.positiveMinorUnits }
            val incomeRefund =
                view.incomeCategories.sumOf { it.totals.cnyValue { total -> total.refundMinorUnits } } +
                    view.uncategorizedIncomeTotals.cnyValue { it.refundMinorUnits }

            assertEquals(card.positiveExpenseMinorUnits, expensePositive, "top-level + uncategorized gross expense")
            assertEquals(card.refundMinorUnits, expenseRefund, "top-level + uncategorized expense refund")
            assertEquals(card.netExpenseMinorUnits, expensePositive + expenseRefund, "top-level + uncategorized net expense")
            assertEquals(card.ordinaryIncomeMinorUnits, incomePositive + incomeRefund, "top-level + uncategorized ordinary income")
            // The same totals against the folded card of the same request.
            val folded = view.currencies.first { it.currency == cny }
            assertEquals(folded.positiveExpenseMinorUnits, expensePositive)
            assertEquals(folded.refundMinorUnits, expenseRefund)
            assertEquals(folded.ordinaryIncomeMinorUnits, incomePositive + incomeRefund)

            // Explicit expected figures: 100 + 200 categorized + 300 uncategorized = 600 gross,
            // refunds -50 and -25, income 5,000 with a -700 reversal.
            assertEquals(600L, expensePositive)
            assertEquals(-75L, expenseRefund)
            assertEquals(525L, expensePositive + expenseRefund)
            assertEquals(4_300L, incomePositive + incomeRefund)
            assertEquals(5_000L, incomePositive)
            assertEquals(-700L, incomeRefund)
        }
    }

    @Test
    fun anInactiveRenamedCategoryKeepsItsHistoryUnderTheCurrentNameAndThePredicatesStayDistinct() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, allAccounts(), version)
            inFixtureTransaction(driver) {
                // Every seeded category gets a CURRENT name; cat-food-other is renamed (its old
                // CURRENT row becomes SUPERSEDED) and then deactivated, the historical shape.
                categories().forEach { category ->
                    exec(
                        driver,
                        "INSERT INTO catalog_name_history (ledger_id, owner_kind, owner_id, version_number, name, status) " +
                            "VALUES ('${ledgerId.value}', 'category', '${category.id.value}', 1, '${category.name}', 'CURRENT')",
                    )
                }
                exec(driver, "UPDATE catalog_name_history SET status = 'SUPERSEDED' WHERE owner_id = '${foodOther.value}' AND version_number = 1")
                exec(
                    driver,
                    "INSERT INTO catalog_name_history (ledger_id, owner_kind, owner_id, version_number, name, status) " +
                        "VALUES ('${ledgerId.value}', 'category', '${foodOther.value}', 2, '晚餐（归档）', 'CURRENT')",
                )
                exec(driver, "UPDATE catalog_category SET active = 0 WHERE category_id = '${foodOther.value}'")
                insertTransaction(driver, "tx-breakfast", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-dinner", "EXPENSE", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(dinner to 200L, cash to -200L))
            }
            // V-10: the authority comes from the stored catalog, so the display name is the CURRENT
            // name-history row even though the category row itself is inactive.
            val authority = assertNotNull(SqlDelightCatalogStore(database, driver).load(ledgerId))
            val renamed = authority.catalog.categories.first { it.id == foodOther }
            assertEquals(false, renamed.active, "the category is historical")
            assertEquals("晚餐（归档）", renamed.name, "the current name wins over the superseded one")

            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val view =
                (
                    P806ReportQuery(adapter, ledgerId, authority, FixedClock("2026-11-17T00:00:00Z"))
                        .query(P806Period(P806PeriodKind.MONTH)) as P806SnapshotResult.Success<P806ReportView>
                ).snapshot
            val parent = view.expenseCategories.first { it.categoryId == foodParent }
            val child = parent.children.first { it.categoryId == foodOther }
            assertEquals("晚餐（归档）", child.categoryName, "an inactive category still appears under its current name")
            assertEquals(200L, child.totals.single().positiveMinorUnits)

            // The drilldown predicates stay distinct: the parent covers both leaves, the leaf only
            // its own row, and the whole period equals the parent here (both are categorized).
            val parentPage = page(adapter, novemberWindow(), foodParent.value, version)
            val parentIds =
                parentPage.page.rows
                    .map { it.transactionId.value }
                    .toSet()
            assertEquals(setOf("tx-breakfast", "tx-dinner"), parentIds)
            assertEquals(2, parentPage.totalCount)
            val leafPage = page(adapter, novemberWindow(), foodOther.value, version)
            assertEquals(listOf("tx-dinner"), leafPage.page.rows.map { it.transactionId.value })
            assertEquals(1, leafPage.totalCount)
            val wholePeriod = page(adapter, novemberWindow(), "", version)
            assertEquals(2, wholePeriod.totalCount)
            val wholePeriodIds =
                wholePeriod.page.rows
                    .map { it.transactionId.value }
                    .toSet()
            assertEquals(2, wholePeriodIds.size)
            val leafIds =
                leafPage.page.rows
                    .map { it.transactionId.value }
                    .toSet()
            assertTrue(
                leafIds.isNotEmpty() && parentIds.containsAll(leafIds) && leafIds != parentIds,
                "the leaf predicate is a strict non-empty subset of its parent",
            )
        }
    }

    @Test
    fun incomeCategoriesAndTheUncategorizedIncomeRowKeepTheirSigns() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, allAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-salary", "INCOME", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(salary to -5_000L, cash to 5_000L))
                // A refund of income (a negative income leg) on an account WITHOUT a category mapping.
                insertTransaction(driver, "tx-bonus-reversal", "INCOME", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(bonusAccount to 700L, cash to -700L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val view =
                (
                    P806ReportQuery(adapter, ledgerId, CatalogAuthority(ledgerId, catalog, version), FixedClock("2026-11-17T00:00:00Z"))
                        .query(P806Period(P806PeriodKind.MONTH)) as P806SnapshotResult.Success<P806ReportView>
                ).snapshot
            val salaryCategory =
                view.incomeCategories
                    .first { it.categoryId == salaryParent }
                    .children
                    .first { it.categoryId == salaryLeaf }
            assertEquals(5_000L, salaryCategory.totals.first().positiveMinorUnits)
            assertEquals(0L, salaryCategory.totals.first().refundMinorUnits)
            // The unmapped income leg (a reversal) is the explicit 无分类 income row, negative side.
            assertEquals(-700L, view.uncategorizedIncomeTotals.first().refundMinorUnits)
            assertEquals(0L, view.uncategorizedIncomeTotals.first().positiveMinorUnits)
            // Net ordinary income keeps the sign of the reversal.
            assertEquals(4_300L, view.currencies.first { it.currency == cny }.ordinaryIncomeMinorUnits)
        }
    }

    @Test
    fun theStrictParserRefusesOutOfRangeClockShapesOutsideTheWindowAndAtAShallowPageTail() {
        val badShapes =
            listOf(
                "2026-11-10T24:00:00Z", // hour 24 outside the civil clock range
                "2026-11-10T00:60:00Z", // minute 60
                "2026-11-10T00:00:60Z", // second 60
            )
        badShapes.forEach { bad ->
            withDatabase { driver, database, version ->
                seedCatalog(driver, allAccounts(), version)
                inFixtureTransaction(driver) {
                    insertTransaction(driver, "tx-bad", "EXPENSE", null, "2026-01-05T00:00:00Z", bad, listOf(breakfast to 100L, cash to -100L))
                }
                val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
                val result =
                    adapter.readAccountPostingsSnapshot(
                        P806AccountRequest(ledgerId, cash, window = novemberWindow(), expectedCatalogVersion = version),
                    )
                assertEquals(
                    P806SnapshotFailure.OccurredAtUnparseable,
                    (assertIs<P806SnapshotResult.Failed>(result)).failure,
                    "out-of-range clock shape must be refused before the window: $bad",
                )
            }
        }
        // The same shape at the tail of a one-row walk is refused by the first request.
        withDatabase { driver, database, version ->
            seedCatalog(driver, allAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-newest", "EXPENSE", null, "2026-11-20T00:00:00Z", "2026-11-20T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-tail", "EXPENSE", null, "2026-11-18T00:00:00Z", "2026-11-18T24:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val result = adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cash, pageSize = 1, expectedCatalogVersion = version))
            assertEquals(P806SnapshotFailure.OccurredAtUnparseable, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
        // CI-only precision/boundary positives: four-digit fractions and pre-1970 instants stay legal.
        listOf("2026-11-10T00:00:00.9999Z", "1969-05-01T00:00:00Z", "2026-11-10T00:00:00.999999999Z").forEach { good ->
            withDatabase { driver, database, version ->
                seedCatalog(driver, allAccounts(), version)
                inFixtureTransaction(driver) {
                    insertTransaction(driver, "tx-good", "EXPENSE", null, "2026-11-02T00:00:00Z", good, listOf(breakfast to 100L, cash to -100L))
                }
                val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
                val result = adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cash, expectedCatalogVersion = version))
                assertEquals(1, (assertIs<P806SnapshotResult.Success<com.unifiedledger.application.P806AccountSnapshot>>(result)).snapshot.totalCount, "legal shape must pass: $good")
            }
        }
    }

    @Test
    fun subSecondAndPreEpochOccurredAtKeepExactParsedKeysAcrossPages() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, allAccounts(), version)
            val cases =
                listOf(
                    "tx-9995" to "2026-11-12T00:00:00.9995Z",
                    "tx-9999" to "2026-11-11T00:00:00.9999Z",
                    "tx-nines" to "2026-11-10T00:00:00.999999999Z",
                    "tx-zero" to "2026-11-09T00:00:00.0000Z",
                    "tx-pre1970" to "1969-05-01T00:00:00.9999Z",
                )
            inFixtureTransaction(driver) {
                cases.forEachIndexed { index, (id, occurred) ->
                    insertTransaction(driver, id, "EXPENSE", null, "2026-11-%02dT00:00:00Z".format(2 + index), occurred, listOf(breakfast to 100L, cash to -100L))
                }
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)

            // Independent expectation: epoch nanos from the literal's own second + fraction, never
            // from a millisecond conversion.
            fun expectedNanos(text: String): Long {
                val instant = Instant.parse(text)
                return instant.epochSeconds * 1_000_000_000L + instant.nanosecondsOfSecond.toLong()
            }
            val expectedOrder =
                cases.sortedByDescending { expectedNanos(it.second) }.map { it.first }
            val expectedFractions =
                cases.associate { (id, text) -> id to Instant.parse(text).nanosecondsOfSecond }

            val collected = mutableListOf<String>()
            val cursors = mutableListOf<Long>()
            var cursor: com.unifiedledger.application.P806AccountCursor? = null
            do {
                val request =
                    P806AccountRequest(
                        ledgerId = ledgerId,
                        accountId = cash,
                        cursor = cursor,
                        pageSize = 2,
                        expectedCatalogVersion = version,
                    )
                val pageResult = adapter.readAccountPostingsSnapshot(request)
                assertTrue(pageResult is P806SnapshotResult.Success, "expected Success, got $pageResult")
                val page = (pageResult as P806SnapshotResult.Success<com.unifiedledger.application.P806AccountSnapshot>).snapshot
                page.page.rows.forEach { row ->
                    collected += row.transactionId.value
                    // The parsed key keeps the exact fraction (no millisecond truncation).
                    assertEquals(expectedFractions[row.transactionId.value], row.occurredAt.nanosecondsOfSecond, "fraction of ${row.transactionId.value}")
                }
                cursor = page.page.nextCursor
                cursor?.let { cursors += it.occurredAtEpochNanos }
            } while (cursor != null)

            assertEquals(expectedOrder, collected)
            assertEquals(5, collected.size)
            // Two pages of two plus a final page of one; each cursor is the exact parsed key of the
            // last row of its page.
            assertEquals(2, cursors.size)
            assertEquals(expectedNanos("2026-11-11T00:00:00.9999Z"), cursors[0])
            assertEquals(expectedNanos("2026-11-09T00:00:00.0000Z"), cursors[1])
        }
    }

    @Test
    fun everyOneRowPageTailCarriesTheExactSignedNanosecondCursor() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, allAccounts(), version)
            // Newest to oldest: a nine-digit fraction and a pre-epoch negative key must each become a
            // page tail with a real cursor, so both sit before another, earlier row.
            val cases =
                listOf(
                    "tx-newest" to "2026-11-15T00:00:00Z",
                    "tx-nines" to "2026-11-10T00:00:00.999999999Z",
                    "tx-pre1970" to "1969-05-01T00:00:00.9999Z",
                    "tx-earlier" to "1960-01-01T00:00:00Z",
                )
            inFixtureTransaction(driver) {
                cases.forEachIndexed { index, (id, occurred) ->
                    insertTransaction(driver, id, "EXPENSE", null, "2026-11-%02dT00:00:00Z".format(2 + index), occurred, listOf(breakfast to 100L, cash to -100L))
                }
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)

            // Independent expectation from the literal's own whole second and fraction, never from a
            // millisecond conversion; a pre-epoch instant keeps floor seconds plus its positive fraction.
            fun nanosOf(instant: Instant): Long = instant.epochSeconds * 1_000_000_000L + instant.nanosecondsOfSecond.toLong()

            fun expectedNanos(text: String): Long = nanosOf(Instant.parse(text))
            val expectedByTransaction = cases.associate { (id, text) -> id to expectedNanos(text) }
            val expectedOrder = cases.sortedByDescending { expectedNanos(it.second) }.map { it.first }
            assertTrue(expectedNanos("1969-05-01T00:00:00.9999Z") < 0, "the pre-epoch key is negative")
            assertEquals(
                listOf("tx-newest", "tx-nines", "tx-pre1970", "tx-earlier"),
                expectedOrder,
            )

            val collected = mutableListOf<String>()
            val cursors = mutableListOf<Long>()
            var cursor: com.unifiedledger.application.P806AccountCursor? = null
            var pages = 0
            do {
                val request =
                    P806AccountRequest(
                        ledgerId = ledgerId,
                        accountId = cash,
                        cursor = cursor,
                        pageSize = 1,
                        expectedCatalogVersion = version,
                    )
                val pageResult = adapter.readAccountPostingsSnapshot(request)
                assertTrue(pageResult is P806SnapshotResult.Success, "expected Success, got $pageResult")
                val page = (pageResult as P806SnapshotResult.Success<com.unifiedledger.application.P806AccountSnapshot>).snapshot
                assertEquals(1, page.page.rows.size)
                val row = page.page.rows.single()
                collected += row.transactionId.value
                assertEquals(expectedByTransaction.getValue(row.transactionId.value), nanosOf(row.occurredAt), "exact key of ${row.transactionId.value}")
                // One row per page, so every page before the last hands back the key of its only row.
                cursor = page.page.nextCursor
                cursor?.let { cursors += it.occurredAtEpochNanos }
                pages += 1
                assertTrue(pages <= cases.size, "a bad cursor must not spin the walk past one page per row")
            } while (cursor != null)

            assertEquals(cases.size, pages, "one page per row")
            assertNull(cursor, "the last page ends the walk")
            assertEquals(expectedOrder, collected)
            assertEquals(collected.size, collected.distinct().size, "no transaction repeats across pages")
            assertEquals(
                listOf(
                    expectedNanos("2026-11-15T00:00:00Z"),
                    expectedNanos("2026-11-10T00:00:00.999999999Z"),
                    expectedNanos("1969-05-01T00:00:00.9999Z"),
                ),
                cursors,
            )
            assertEquals(999_999_999L, cursors[1] % 1_000_000_000L, "nine-digit fraction survives the cursor")
            assertTrue(cursors[2] < 0, "the pre-epoch page tail carries a negative key")
        }
    }
    // ---------------------------------------------------------------- helpers

    /** The CNY column of one category total list, or 0 when that currency is absent. */
    private fun List<P806CategoryCurrencyTotal>.cnyValue(select: (P806CategoryCurrencyTotal) -> Long): Long = firstOrNull { it.currency == cny }?.let(select) ?: 0L

    private fun page(
        adapter: SqlDelightLedgerCurrentStateReadAdapter,
        window: P806Window,
        categoryFilter: String,
        version: Long,
    ): P806DrilldownView {
        val request =
            P806DrilldownRequest(
                ledgerId = ledgerId,
                window = window,
                categoryId = categoryFilter.takeIf { it.isNotEmpty() }?.let { CategoryId(it) },
                expectedCatalogVersion = version,
            )
        return (adapter.readDrilldownSnapshot(request) as P806SnapshotResult.Success<P806DrilldownSnapshot>).snapshot.let {
            P806DrilldownView(ledgerId, it.window, it.page, it.totalCount)
        }
    }

    private fun reportRequest(
        version: Long,
        catalog: LedgerCatalog,
    ): com.unifiedledger.application.P806ReportRequest =
        com.unifiedledger.application.P806ReportRequest(
            ledgerId = ledgerId,
            catalog = catalog,
            period =
                P806Period(
                    P806PeriodKind.CUSTOM,
                    customStart = kotlinx.datetime.LocalDate(2026, 11, 1),
                    customEnd = kotlinx.datetime.LocalDate(2026, 11, 30),
                ),
            today = kotlinx.datetime.LocalDate(2026, 11, 17),
            futureFromEpochNanos = Instant.parse("2026-11-18T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
            expectedCatalogVersion = version,
        )

    private fun novemberWindow(): P806Window =
        P806Window(
            Instant.parse("2026-11-01T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
            Instant.parse("2026-12-01T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
        )

    private fun allAccounts(): List<Account> =
        listOf(
            Account(cash, ledgerId, AccountKind.ASSET, cny, true, true, name = "现金"),
            Account(usdCash, ledgerId, AccountKind.ASSET, usd, true, true, name = "USD 现金"),
            Account(breakfast, ledgerId, AccountKind.EXPENSE, cny, false, false, name = "早餐账户"),
            Account(dinner, ledgerId, AccountKind.EXPENSE, cny, false, false, name = "晚餐账户"),
            Account(salary, ledgerId, AccountKind.INCOME, cny, false, false, name = "工资账户"),
            Account(bonusAccount, ledgerId, AccountKind.INCOME, cny, false, false, name = "无分类收入账户"),
            Account(usdExpense, ledgerId, AccountKind.EXPENSE, usd, false, false, name = "USD 支出"),
        )

    /** An EXPENSE account no category maps to: its legs are the explicit 无分类 expense row. */
    private fun miscExpenseAccount(): Account = Account(miscExpense, ledgerId, AccountKind.EXPENSE, cny, false, false, name = "其他支出")

    private fun categories(): List<Category> =
        listOf(
            Category(foodParent, ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE, name = "餐饮"),
            Category(foodOther, ledgerId, parentId = foodParent, postingAccountId = dinner, active = true, kind = CategoryKind.EXPENSE, name = "晚餐"),
            Category(CategoryId("cat-breakfast"), ledgerId, parentId = foodParent, postingAccountId = breakfast, active = true, name = "早餐"),
            Category(salaryParent, ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME, name = "收入"),
            Category(salaryLeaf, ledgerId, parentId = salaryParent, postingAccountId = salary, active = true, kind = CategoryKind.INCOME, name = "工资"),
        )

    private fun catalogOf(accounts: List<Account>): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts, categories())) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("fixture catalog must be valid")
        }

    private fun seedCatalog(
        driver: SqlDriver,
        accounts: List<Account>,
        version: Long,
    ): LedgerCatalog {
        exec(driver, "INSERT INTO catalog_version (ledger_id, version) VALUES ('${ledgerId.value}', $version)")
        accounts.forEach { account ->
            exec(
                driver,
                "INSERT INTO catalog_account (ledger_id, account_id, name, kind, currency_code, currency_precision, owned_by_user, real_account, system_role, hidden, active) " +
                    "VALUES ('${account.ledgerId.value}', '${account.id.value}', '${account.name}', '${account.kind.name}', '${account.currency.code}', ${account.currency.precision}, ${if (account.ownedByUser) 1 else 0}, ${if (account.realAccount) 1 else 0}, NULL, 0, 1)",
            )
        }
        categories().forEach { category ->
            exec(
                driver,
                "INSERT INTO catalog_category (ledger_id, category_id, kind, parent_id, posting_account_id, active) VALUES " +
                    "('${category.ledgerId.value}', '${category.id.value}', '${category.kind.name}', ${category.parentId?.let { "'${it.value}'" } ?: "NULL"}, ${category.postingAccountId?.let { "'${it.value}'" } ?: "NULL"}, 1)",
            )
        }
        return catalogOf(accounts)
    }

    private fun insertTransaction(
        driver: SqlDriver,
        id: String,
        kind: String,
        canonicalKind: String?,
        statisticsAt: String,
        occurredAt: String,
        postings: List<Pair<AccountId, Long>>,
        currency: CurrencyUnit = cny,
        // Optional per-account override so ONE transaction can genuinely span currencies: each
        // posting then carries its own account's currency and each currency balances on its own.
        currencyByAccount: Map<AccountId, CurrencyUnit> = emptyMap(),
    ) {
        val canonical = canonicalKind?.let { "'$it'" } ?: "NULL"
        exec(driver, "INSERT INTO ledger_transaction (transaction_id, ledger_id, kind, canonical_kind) VALUES ('$id', '${ledgerId.value}', '$kind', $canonical)")
        exec(driver, "INSERT INTO posting_set (posting_set_id, ledger_id) VALUES ('ps-$id', '${ledgerId.value}')")
        exec(
            driver,
            "INSERT INTO transaction_version (version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) " +
                "VALUES ('v-$id', '$id', '${ledgerId.value}', 1, 'ps-$id', '$occurredAt', '$statisticsAt', '$statisticsAt', '', ${Instant.parse(statisticsAt).toEpochMilliseconds() * 1_000_000L})",
        )
        postings.forEachIndexed { index, (accountId, amount) ->
            val postingCurrency = currencyByAccount[accountId] ?: currency
            exec(
                driver,
                "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                    "VALUES ('p$index-$id', 'ps-$id', '${ledgerId.value}', $index, '${accountId.value}', $amount, '${postingCurrency.code}', ${postingCurrency.precision})",
            )
        }
        exec(driver, "INSERT INTO ledger_transaction_current_version (transaction_id, ledger_id, current_version_id) VALUES ('$id', '${ledgerId.value}', 'v-$id')")
    }

    /**
     * Runs [block] inside ONE SQLDelight-managed fixture transaction so fixtures never pay one
     * autocommit per row. The transaction machinery owns BEGIN/COMMIT/ROLLBACK: a bare `BEGIN` sent
     * through an untransacted `execute` sits outside it, and the fixture then dies with
     * SQLiteException. The read path is deliberately NOT called inside this transaction.
     */
    private fun inFixtureTransaction(
        driver: SqlDriver,
        block: () -> Unit,
    ) {
        LedgerDatabase(driver).transaction { block() }
    }

    private inline fun withDatabase(block: (SqlDriver, LedgerDatabase, Long) -> Unit) {
        val path: Path = Files.createTempFile("p806-coverage-", ".db")
        Files.deleteIfExists(path)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            LedgerDatabase.Schema.create(driver)
            block(driver, LedgerDatabase(driver), 7L)
        } finally {
            driver.close()
        }
    }

    private fun exec(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.execute(null, sql, 0)
    }

    private class FixedClock(
        private val value: String,
    ) : LedgerClock {
        override fun now(): Instant = Instant.parse(value)
    }
}
