package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.OrdinaryFlowClassification
import com.unifiedledger.application.P806AccountRequest
import com.unifiedledger.application.P806AccountSnapshot
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806CalendarSnapshot
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806DrilldownSnapshot
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportQuery
import com.unifiedledger.application.P806ReportRequest
import com.unifiedledger.application.P806ReportSnapshot
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806TransactionCursor
import com.unifiedledger.application.P806_DETAIL_PAGE_SIZE
import com.unifiedledger.application.P806_MAX_DAY_BUCKETS
import com.unifiedledger.application.QueryMonthlyActivity
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch a (D-343; spec section 4 and D-343 3.2.2（发生时间数值键）/3.4/3.8/4): real-database
 * evidence for the four composite snapshots.
 *
 * Prepared and executed by cloud CI only. Bulk fixtures are written inside ONE explicit fixture
 * transaction and the read counters are reset AFTER that transaction commits, so the per-surface
 * statement assertions measure the snapshot itself (never fixture preparation).
 */
class P806SnapshotReadTest {
    private val ledgerId = LedgerId("ledger-p806-read")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)

    private val cash = AccountId("asset-cash")
    private val savings = AccountId("asset-savings")
    private val breakfast = AccountId("expense-breakfast")
    private val foodParent = CategoryId("cat-food")
    private val breakfastLeaf = CategoryId("cat-breakfast")
    private val salary = AccountId("income-salary")
    private val usdExpense = AccountId("expense-usd")
    private val usdCash = AccountId("asset-usd-cash")
    private val usdSalary = AccountId("income-usd-salary")

    @Test
    fun theContributionSetIsIndependentCountedOncePerTransactionAndPagesToNull() {
        withDatabase { driver, database, version ->
            val accounts = standardAccounts() + usdAccount() + usdAssetAccount() + usdIncomeAccount()
            val catalog = catalogOf(accounts)
            seedCatalog(driver, accounts, version)
            val accountKinds = accounts.associate { it.id to it.kind }
            // 60 plain expenses inside ONE fixture transaction, plus a mixed tail: special kinds,
            // both principal-only shapes, a zero-only expense, one multi-leg expense, a refund and
            // a cross-month refund, and both currencies (CNY and USD) so the frozen monthly card can
            // be compared per currency. All values are anonymous.
            val fixtures =
                buildList {
                    repeat(60) { index ->
                        val stamp = "2026-11-02T0${index % 9}:00:00Z"
                        add(
                            FixtureTransaction(
                                "tx-exp-$index",
                                "EXPENSE",
                                null,
                                stamp,
                                stamp,
                                listOf(breakfast to 1_000L + index, cash to -(1_000L + index)),
                                cny,
                            ),
                        )
                    }
                    // A special kind and the two principal-only shapes never contribute.
                    add(FixtureTransaction("tx-stored", "EXPENSE", "STORED_VALUE_SPEND", "2026-11-03T01:00:00Z", "2026-11-03T01:00:00Z", listOf(breakfast to 5_000L, cash to -5_000L), cny))
                    add(FixtureTransaction("tx-transfer", "ACCOUNT_TRANSFER", null, "2026-11-03T02:00:00Z", "2026-11-03T02:00:00Z", listOf(savings to 2_000L, cash to -2_000L), cny))
                    add(FixtureTransaction("tx-lend", "EXPENSE", "LEND", "2026-11-03T03:00:00Z", "2026-11-03T03:00:00Z", listOf(savings to 3_000L, cash to -3_000L), cny))
                    // An ordinary kind whose only EXPENSE leg is zero: the nonzero part of the
                    // contribution predicate must keep it out.
                    add(FixtureTransaction("tx-zero", "EXPENSE", null, "2026-11-03T04:00:00Z", "2026-11-03T04:00:00Z", listOf(breakfast to 0L, cash to 0L), cny))
                    // One multi-leg expense: counted ONCE.
                    add(FixtureTransaction("tx-split", "EXPENSE", null, "2026-11-04T01:00:00Z", "2026-11-04T01:00:00Z", listOf(breakfast to 400L, breakfast to 600L, cash to -1_000L), cny))
                    // A refund receipt: a negative ordinary expense.
                    add(FixtureTransaction("tx-refund", "EXPENSE", "REFUND_RECEIPT", "2026-11-05T01:00:00Z", "2026-11-05T01:00:00Z", listOf(breakfast to -300L, cash to 300L), cny))
                    // A refund spent in October but attributed to November by its statistics time.
                    add(FixtureTransaction("tx-refund-cross", "EXPENSE", "REFUND_RECEIPT", "2026-11-06T01:00:00Z", "2026-10-15T01:00:00Z", listOf(breakfast to -150L, cash to 150L), cny))
                    // CNY ordinary income.
                    add(FixtureTransaction("tx-cny-income", "INCOME", null, "2026-11-10T01:00:00Z", "2026-11-10T01:00:00Z", listOf(salary to -5_000L, cash to 5_000L), cny))
                    // USD ordinary expense, income and a cross-month refund.
                    add(FixtureTransaction("tx-usd-exp", "EXPENSE", null, "2026-11-07T01:00:00Z", "2026-11-07T01:00:00Z", listOf(usdExpense to 250L, usdCash to -250L), usd))
                    add(FixtureTransaction("tx-usd-income", "INCOME", null, "2026-11-08T01:00:00Z", "2026-11-08T01:00:00Z", listOf(usdSalary to -900L, usdCash to 900L), usd))
                    add(FixtureTransaction("tx-usd-refund-cross", "EXPENSE", "REFUND_RECEIPT", "2026-11-09T01:00:00Z", "2026-10-20T01:00:00Z", listOf(usdExpense to -100L, usdCash to 100L), usd))
                }
            inFixtureTransaction(driver) {
                fixtures.forEach { fixture ->
                    insertTransaction(driver, fixture.id, fixture.kind, fixture.canonicalKind, fixture.statisticsAt, fixture.occurredAt, fixture.postings, fixture.currency)
                }
            }

            // V-2 (1): an INDEPENDENT oracle. It re-derives the contribution set from the fixture's
            // own descriptors: effective kind via OrdinaryFlowClassification, at least one posting
            // whose catalog account kind is EXPENSE/INCOME, and a non-zero amount. It never reads the
            // production predicate or the page.
            val contributing =
                fixtures.filter { fixture ->
                    OrdinaryFlowClassification.isOrdinary(TransactionKind.valueOf(fixture.canonicalKind ?: fixture.kind)) &&
                        fixture.postings.any { (accountId, amount) ->
                            amount != 0L && accountKinds[accountId].let { it == AccountKind.EXPENSE || it == AccountKind.INCOME }
                        }
                }
            val expectedIds = contributing.map { it.id }.toSet()
            assertEquals(67, expectedIds.size)
            val expectedOrder =
                contributing
                    .sortedWith(
                        compareByDescending<FixtureTransaction> { Instant.parse(it.statisticsAt).toEpochMilliseconds() }
                            .thenByDescending { it.id },
                    ).map { it.id }
            val expectedFirstPage = expectedOrder.take(P806_DETAIL_PAGE_SIZE)
            assertEquals(50, expectedFirstPage.size)

            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            // V-2 (2)+(3): walk EVERY keyset page to cursor == null and compare the union with both
            // the oracle set and the global count; no omission and no repetition across pages.
            val ids = mutableListOf<String>()
            var firstPageIds: List<String>? = null
            var cursor: P806TransactionCursor? = null
            var pages = 0
            do {
                val snapshot =
                    (
                        adapter.readDrilldownSnapshot(drilldownRequest(novemberWindow(), cursor, version))
                            as P806SnapshotResult.Success<P806DrilldownSnapshot>
                    ).snapshot
                if (firstPageIds == null) firstPageIds = snapshot.page.rows.map { it.transactionId.value }
                assertEquals(contributing.size, snapshot.totalCount)
                snapshot.page.rows.forEach { ids += it.transactionId.value }
                cursor = snapshot.page.nextCursor
                pages += 1
                assertTrue(pages <= 5, "a bad cursor must not spin the walk")
            } while (cursor != null)

            assertEquals(contributing.size, ids.size)
            assertEquals(ids.size, ids.toSet().size, "pages must not repeat a transaction")
            assertEquals(expectedIds, ids.toSet(), "the page union equals the independent oracle")
            // V-2 (4): the first page is EXACTLY the independently ordered first 50, never the whole
            // set and never a different order.
            assertEquals(expectedFirstPage, firstPageIds)
            assertEquals(2, pages)

            val report =
                (adapter.readReportSnapshot(reportRequest(version, catalog)) as P806SnapshotResult.Success<P806ReportSnapshot>).snapshot
            assertEquals(contributing.size, report.transactionCount)
            assertEquals(contributing.size, report.detailCount)
            assertEquals(P806_DETAIL_PAGE_SIZE, report.firstPage.rows.size)
            assertNotNull(report.firstPage.nextCursor)
            val expectedCnyCount = contributing.count { it.currency == cny }
            val expectedUsdCount = contributing.count { it.currency == usd }
            assertEquals(expectedCnyCount, report.currencyCounts.first { it.currency == cny }.transactionCount)
            assertEquals(expectedUsdCount, report.currencyCounts.first { it.currency == usd }.transactionCount)

            // V-2 (5): the folded report and the frozen monthly card agree per currency on income,
            // net expense and balance. October has no statistics row, so the cross-month refunds
            // stay in November (they never rewrite October).
            val clock = FixedClock("2026-11-17T00:00:00Z")
            val authority = CatalogAuthority(ledgerId, catalog, version)
            val folded =
                assertIs<P806SnapshotResult.Success<P806ReportView>>(
                    P806ReportQuery(adapter, ledgerId, authority, clock).query(
                        P806Period(
                            P806PeriodKind.CUSTOM,
                            customStart = kotlinx.datetime.LocalDate(2026, 11, 1),
                            customEnd = kotlinx.datetime.LocalDate(2026, 11, 30),
                        ),
                    ),
                ).snapshot
            val monthly =
                assertIs<MonthlyActivityResult.Success>(
                    QueryMonthlyActivity(adapter, ledgerId, catalog, clock).query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)),
                ).activity
            listOf(cny, usd).forEach { currency ->
                val card = monthly.currencies.first { it.currency == currency }
                val reportCurrency = folded.currencies.first { it.currency == currency }
                assertEquals(card.ordinaryIncomeMinorUnits, reportCurrency.ordinaryIncomeMinorUnits, "ordinary income of $currency")
                assertEquals(card.netExpenseMinorUnits, reportCurrency.netExpenseMinorUnits, "net expense of $currency")
                assertEquals(card.balanceMinorUnits, reportCurrency.balanceMinorUnits, "balance of $currency")
                assertEquals(card.positiveExpenseMinorUnits, reportCurrency.positiveExpenseMinorUnits, "gross expense of $currency")
                assertEquals(card.refundMinorUnits, reportCurrency.refundMinorUnits, "refund column of $currency")
            }
            val cnyView = folded.currencies.first { it.currency == cny }
            // 60 expenses (sum 61,770) + split 1,000 = 62,770 gross; refunds -300 and -150.
            assertEquals(62_770L, cnyView.positiveExpenseMinorUnits)
            assertEquals(-450L, cnyView.refundMinorUnits)
            assertEquals(62_320L, cnyView.netExpenseMinorUnits)
            assertEquals(5_000L, cnyView.ordinaryIncomeMinorUnits)
            assertEquals(-57_320L, cnyView.balanceMinorUnits)
            val usdView = folded.currencies.first { it.currency == usd }
            assertEquals(250L, usdView.positiveExpenseMinorUnits)
            assertEquals(-100L, usdView.refundMinorUnits)
            assertEquals(150L, usdView.netExpenseMinorUnits)
            assertEquals(900L, usdView.ordinaryIncomeMinorUnits)
            assertEquals(750L, usdView.balanceMinorUnits)

            val october =
                assertIs<MonthlyActivityResult.Success>(
                    QueryMonthlyActivity(adapter, ledgerId, catalog, clock).query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.OCTOBER)),
                ).activity
            val octoberCny = october.currencies.first { it.currency == cny }
            val octoberUsd = october.currencies.first { it.currency == usd }
            assertEquals(0L, octoberCny.netExpenseMinorUnits, "a November-attributed refund never lands in October")
            assertEquals(0L, octoberCny.ordinaryIncomeMinorUnits)
            assertEquals(0L, octoberUsd.netExpenseMinorUnits)
        }
    }

    @Test
    fun aFiftyThousandRowMonthStaysBoundedAndThePrincipalFrontPageStillReturnsFiftyContributions() {
        withDatabase { driver, database, version ->
            val progress = SeedProgress(Path.of("build", "p806-seed-progress.txt"))
            progress.start("seed")
            seedCatalog(driver, standardAccounts(), version)
            // One fixture transaction for the whole bulk: 50,000 ordinary expenses plus 50 principal
            // transfers whose statistics instants are the NEWEST, so a page that ignored the
            // contribution predicate would show only principal rows. Both sets are written with
            // multi-row INSERTs inside the one managed transaction; the rows are the ones
            // insertTransaction writes, only batched.
            inFixtureTransaction(driver) {
                insertTransactionsBulk(driver, 50_000, progress) { index ->
                    val stamp = "2026-11-10T%02d:%02d:%02dZ".format(index % 24, index % 60, index % 60)
                    FixtureTransaction("tx-bulk-$index", "EXPENSE", null, stamp, stamp, listOf(breakfast to 100L, cash to -100L), cny)
                }
                insertTransactionsBulk(driver, 50) { index ->
                    // 2026-11-30T12:00Z is inside the November +08:00 window and newer than every
                    // bulk row, so a page that dropped the contributing-account predicate would put
                    // these 50 principal transfers on the first screen.
                    val stamp = "2026-11-30T12:00:%02dZ".format(index % 60)
                    FixtureTransaction("tx-principal-$index", "ACCOUNT_TRANSFER", null, stamp, stamp, listOf(savings to 100L, cash to -100L), cny)
                }
            }
            progress.finish()

            val counting = CountingSqlDriver(driver)
            // V-4: a TEST-LOCAL observer counts the real cursor rows each statement returns. It does
            // not touch the shared scale support: it delegates to the counting driver and wraps only
            // the mapper so every `next() == true` is a counted row.
            val observing = RowCountingSqlDriver(counting)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(observing))
            // Page 50 plus the one-transaction look-ahead, two postings per transaction.
            val pageCursorRowBound = 2 * (P806_DETAIL_PAGE_SIZE + 1)

            progress.start("report")
            counting.resetCounts()
            observing.reset()
            val report = (adapter.readReportSnapshot(reportRequest(version)) as P806SnapshotResult.Success<P806ReportSnapshot>).snapshot
            assertEquals(50_000, report.transactionCount)
            assertEquals(50, report.firstPage.rows.size)
            assertEquals(50_000, report.detailCount)
            // The first page must be ordinary contributions, never the 50 newest principal rows.
            assertTrue(report.firstPage.rows.none { it.transactionId.value.startsWith("tx-principal-") })
            // V-4: the (account × currency) aggregation is bounded by the catalog, not by the rows:
            // one grouped row (breakfast, CNY) describes all 50,000 transactions.
            assertEquals(1, report.postingTotals.size)
            assertTrue(report.postingTotals.size <= standardAccounts().size, "aggregation rows exceed the account × currency bound")
            assertEquals(breakfast, report.postingTotals.single().accountId)
            // V-4: one per-currency DISTINCT count row, and the global count is the same single value.
            assertEquals(1, report.currencyCounts.size)
            assertEquals(50_000, report.currencyCounts.single().transactionCount)
            // The frozen report C0 is 12 SELECT statements, independent of the row count.
            assertEquals(12, counting.executeQueryCount)
            // V-4: no statement materialised the window. The widest returned cursor is the keyset
            // page's own (page + look-ahead) × two-posting rows, never a row-count-sized list.
            assertTrue(observing.queryRows.isNotEmpty())
            assertTrue(
                observing.queryRows.all { it.second <= pageCursorRowBound },
                "a statement returned an unbounded cursor: ${observing.queryRows.filter { it.second > pageCursorRowBound }}",
            )
            assertEquals(pageCursorRowBound, observing.maxRows, "only the keyset page may return the look-ahead rows")
            progress.finish()

            progress.start("drilldown")
            counting.resetCounts()
            observing.reset()
            val second =
                (
                    adapter.readDrilldownSnapshot(
                        drilldownRequest(novemberWindow(), report.firstPage.nextCursor, version),
                    ) as P806SnapshotResult.Success<P806DrilldownSnapshot>
                ).snapshot
            assertEquals(50, second.page.rows.size)
            assertNotNull(second.page.nextCursor)
            assertEquals(50_000, second.totalCount)
            // Drilldown C0 is 5 SELECT statements per page.
            assertEquals(5, counting.executeQueryCount)
            assertTrue(observing.queryRows.all { it.second <= pageCursorRowBound })
            assertEquals(pageCursorRowBound, observing.maxRows)
            // V-4: the count is ONE `COUNT(DISTINCT transaction_id)` statement, not a sum over groups.
            assertEquals(1, observing.countMatching("count(DISTINCT"))
            progress.finish()

            progress.start("calendar")
            counting.resetCounts()
            observing.reset()
            val calendar =
                (
                    adapter.readCalendarSnapshot(
                        P806CalendarRequest(ledgerId, catalogForCurrentAccounts(), kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER), version),
                    ) as P806SnapshotResult.Success<P806CalendarSnapshot>
                ).snapshot
            // V-4: at most 31 local day buckets, each with a bounded (account × currency) amount row
            // and one global count row; the request is C0 + 2D = 4 + 60 statements.
            assertEquals(30, calendar.days.size)
            assertTrue(calendar.days.size <= P806_MAX_DAY_BUCKETS)
            assertEquals(4 + 2 * 30, counting.executeQueryCount)
            assertTrue(calendar.days.all { it.postingTotals.size <= standardAccounts().size }, "a day bucket exceeded the account × currency bound")
            val busyDay = calendar.days.first { it.date == kotlinx.datetime.LocalDate(2026, 11, 10) }
            assertEquals(1, busyDay.postingTotals.size)
            // The fixture stamps are UTC hours 00..23 of 2026-11-10. A local day bucket in the
            // +08:00 reporting zone covers UTC [prev 16:00, this 16:00), so local 2026-11-10 holds
            // the 16 UTC hours 00..15 (8 hours x 2,084 rows + 8 x 2,083 = 33,336) and local
            // 2026-11-11 holds the remaining 16,664. Each bucket is ONE COUNT(DISTINCT) row, never
            // a 50k-row list.
            assertEquals(33_336, busyDay.transactionCount)
            val nextDay = calendar.days.first { it.date == kotlinx.datetime.LocalDate(2026, 11, 11) }
            assertEquals(16_664, nextDay.transactionCount)
            assertEquals(50_000, busyDay.transactionCount + nextDay.transactionCount)
            assertTrue(observing.queryRows.all { it.second <= 1 }, "a day bucket statement returned more than its bounded group/count row")
            progress.finish()
        }
    }

    @Test
    fun accountPagesOrderByTheParsedOccurredAtWithSubSecondBoundariesAndTheMemberMark() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts() + usdAccount(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-offset", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T08:00:00+08:00", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-z", "EXPENSE", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(breakfast to 200L, cash to -200L))
                insertTransaction(driver, "tx-subs", "EXPENSE", null, "2026-11-04T00:00:00Z", "2026-11-04T00:00:00.500Z", listOf(breakfast to 300L, cash to -300L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)

            val all = (adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cash, expectedCatalogVersion = version)) as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot
            assertEquals(listOf("tx-subs", "tx-z", "tx-offset"), all.page.rows.map { it.transactionId.value })
            assertEquals(3, all.totalCount)

            // Half-open sub-second boundary: ending exactly at .500 excludes the .500 row.
            val upToHalf =
                (
                    adapter.readAccountPostingsSnapshot(
                        P806AccountRequest(
                            ledgerId,
                            cash,
                            window = windowOf("2026-11-01T00:00:00Z", "2026-11-04T00:00:00.500Z"),
                            expectedCatalogVersion = version,
                        ),
                    ) as P806SnapshotResult.Success<P806AccountSnapshot>
                ).snapshot
            assertEquals(listOf("tx-z", "tx-offset"), upToHalf.page.rows.map { it.transactionId.value })

            // Mixed offset/Z ordering is chronological, and the non-member account stays out.
            val internal = adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, usdExpense, expectedCatalogVersion = version))
            assertEquals(0, (internal as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot.totalCount)

            // D-343 3.4（键集页 look-ahead） style look-ahead for the account page: page size 1 leaves a real cursor.
            val firstPageResult =
                adapter.readAccountPostingsSnapshot(
                    P806AccountRequest(ledgerId, cash, pageSize = 1, expectedCatalogVersion = version),
                )
            assertTrue(firstPageResult is P806SnapshotResult.Success, "expected Success, got $firstPageResult")
            val firstPage = (firstPageResult as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot
            assertEquals(1, firstPage.page.rows.size)
            val cursor = firstPage.page.nextCursor
            assertNotNull(cursor)
            val secondPage =
                (
                    adapter.readAccountPostingsSnapshot(
                        P806AccountRequest(ledgerId, cash, cursor = cursor, pageSize = 1, expectedCatalogVersion = version),
                    ) as P806SnapshotResult.Success<P806AccountSnapshot>
                ).snapshot
            assertEquals(listOf("tx-z"), secondPage.page.rows.map { it.transactionId.value })
        }
    }

    @Test
    fun accountMembershipRequiresOwnedRealAssetOrLiabilityAndIgnoresRg03AndReconciliation() {
        withDatabase { driver, database, version ->
            val ownedAsset = AccountId("member-owned-asset")
            val ownedLiability = AccountId("member-owned-liability")
            val unownedAsset = AccountId("nonmember-unowned-asset")
            val nonRealAsset = AccountId("nonmember-nonreal-asset")
            val equity = AccountId("nonmember-equity")
            // Every candidate below carries a real posting, so an empty page is an exclusion by the
            // membership mark and never the absence of data.
            val accounts =
                standardAccounts() +
                    listOf(
                        Account(ownedAsset, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true, name = "自有资产"),
                        Account(ownedLiability, ledgerId, AccountKind.LIABILITY, cny, ownedByUser = true, realAccount = true, name = "自有负债"),
                        Account(unownedAsset, ledgerId, AccountKind.ASSET, cny, ownedByUser = false, realAccount = true, name = "非自有资产"),
                        Account(nonRealAsset, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = false, name = "非实账户"),
                        Account(equity, ledgerId, AccountKind.EQUITY, cny, ownedByUser = true, realAccount = true, name = "权益"),
                    )
            seedCatalog(driver, accounts, version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-owned-asset", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(ownedAsset to 10L, breakfast to -10L))
                insertTransaction(driver, "tx-owned-liability", "EXPENSE", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(ownedLiability to 20L, breakfast to -20L))
                insertTransaction(driver, "tx-unowned", "EXPENSE", null, "2026-11-04T00:00:00Z", "2026-11-04T00:00:00Z", listOf(unownedAsset to 30L, breakfast to -30L))
                insertTransaction(driver, "tx-nonreal", "EXPENSE", null, "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", listOf(nonRealAsset to 40L, breakfast to -40L))
                insertTransaction(driver, "tx-equity", "EXPENSE", null, "2026-11-06T00:00:00Z", "2026-11-06T00:00:00Z", listOf(equity to 50L, breakfast to -50L))
                insertTransaction(driver, "tx-expense-kind", "EXPENSE", null, "2026-11-07T00:00:00Z", "2026-11-07T00:00:00Z", listOf(breakfast to 60L, cash to -60L))
                insertTransaction(driver, "tx-income-kind", "INCOME", null, "2026-11-08T00:00:00Z", "2026-11-08T00:00:00Z", listOf(salary to -5_000L, cash to 5_000L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)

            fun snapshot(accountId: AccountId): P806AccountSnapshot =
                assertIs<P806SnapshotResult.Success<P806AccountSnapshot>>(
                    adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, accountId, expectedCatalogVersion = version)),
                ).snapshot

            // V-8 positives: owned + real + ASSET/LIABILITY are members, each with its own posting.
            val asset = snapshot(ownedAsset)
            assertEquals(1, asset.totalCount)
            assertEquals(listOf(10L), asset.page.rows.map { it.amount.minorUnits })
            assertEquals(listOf("p0-tx-owned-asset"), asset.page.rows.map { it.postingId.value })
            val liability = snapshot(ownedLiability)
            assertEquals(1, liability.totalCount)
            assertEquals(listOf(20L), liability.page.rows.map { it.amount.minorUnits })

            // V-8 negatives: each single miss of the three-part mark, plus the kind rules, excludes
            // the account even though its posting exists.
            listOf(unownedAsset, nonRealAsset, equity, breakfast, salary).forEach { accountId ->
                val excluded = snapshot(accountId)
                assertEquals(0, excluded.totalCount, "$accountId must not be an account member")
                assertTrue(excluded.page.rows.isEmpty(), "$accountId must return no page row")
            }

            // V-8 independence: an rg03 eligibility row (fee role, ineligible) on the asset posting
            // and a reconciliation row on the liability posting do not change membership at all.
            inFixtureTransaction(driver) {
                exec(
                    driver,
                    "INSERT INTO rg03_transfer_posting_semantic (ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                        "VALUES ('${ledgerId.value}', 'p0-tx-owned-asset', 'TRANSFER_FEE', '${breakfastLeaf.value}', 0)",
                )
                exec(
                    driver,
                    "INSERT INTO rg03_transfer_posting_semantic (ledger_id, posting_id, role, category_id, reconciliation_eligible) " +
                        "VALUES ('${ledgerId.value}', 'p0-tx-owned-liability', 'TRANSFER_PRINCIPAL_OUT', NULL, 1)",
                )
                exec(
                    driver,
                    "INSERT INTO posting_reconciliation (ledger_id, reconciliation_id, posting_id, status, latest_sequence) " +
                        "VALUES ('${ledgerId.value}', 'recon-ledger', 'p0-tx-owned-liability', 'MISSING', 1)",
                )
            }
            val assetAfter = snapshot(ownedAsset)
            assertEquals(asset.totalCount, assetAfter.totalCount)
            assertEquals(
                asset.page.rows.map { listOf(it.postingId.value, it.amount.minorUnits) },
                assetAfter.page.rows.map { listOf(it.postingId.value, it.amount.minorUnits) },
                "an rg03 fee row never removes a member posting",
            )
            val liabilityAfter = snapshot(ownedLiability)
            assertEquals(liability.totalCount, liabilityAfter.totalCount)
            assertEquals(
                liability.page.rows.map { listOf(it.postingId.value, it.amount.minorUnits) },
                liabilityAfter.page.rows.map { listOf(it.postingId.value, it.amount.minorUnits) },
                "a reconciliation row never removes a member posting",
            )
        }
    }

    @Test
    fun everyCatalogReferenceClassFailsWithAndWithoutAnotherValidContribution() {
        val classes =
            listOf(
                "missing" to { driver: SqlDriver, id: String -> insertBadPosting(driver, id, AccountId("absent-$id"), 100L) },
                "cross-ledger" to { driver: SqlDriver, id: String ->
                    exec(driver, "INSERT OR IGNORE INTO catalog_account (ledger_id, account_id, name, kind, currency_code, currency_precision, owned_by_user, real_account, system_role, hidden, active) VALUES ('other-ledger', 'cross-$id', 'x', 'ASSET', 'CNY', 2, 1, 1, NULL, 0, 1)")
                    insertBadPosting(driver, id, AccountId("cross-$id"), 100L)
                },
                "code" to { driver: SqlDriver, id: String -> insertCurrencyMismatch(driver, id, "USD", 2) },
                "precision" to { driver: SqlDriver, id: String -> insertCurrencyMismatch(driver, id, "CNY", 3) },
            )
        // Each bad fixture writes exactly one offending posting per class. The cross-ledger den is a
        // same-id row in another ledger, so it is ALSO unresolved: cross is a non-empty subset of
        // unresolved, never a separate population.
        val expectedCounters =
            mapOf(
                "missing" to Triple(1L, 0L, 0L),
                "cross-ledger" to Triple(1L, 1L, 0L),
                "code" to Triple(0L, 0L, 1L),
                "precision" to Triple(0L, 0L, 1L),
            )
        classes.forEach { (label, misuse) ->
            listOf("with-valid" to true, "only-bad" to false).forEach { (variant, alsoValid) ->
                withDatabase { driver, database, version ->
                    seedCatalog(driver, standardAccounts(), version)
                    inFixtureTransaction(driver) {
                        if (alsoValid) {
                            insertTransaction(driver, "tx-valid-$label-$variant", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 50L, cash to -50L))
                        }
                        misuse(driver, "tx-bad-$label-$variant")
                    }
                    // V-11: the probe row itself carries the non-zero counter for THIS class only;
                    // the other two counters stay at zero, in BOTH fixture scenarios.
                    val probe =
                        database.ledgerQueries
                            .p806StatisticsCatalogConsistencyProbe(
                                ledger_id = ledgerId.value,
                                window_start_nanos = novemberWindow().startEpochNanos,
                                window_end_nanos = novemberWindow().endEpochNanos,
                            ).executeAsOne()
                    val (unresolved, cross, mismatch) = expectedCounters.getValue(label)
                    assertEquals(unresolved, probe.unresolved_account ?: 0L, "unresolved counter of $label ($variant)")
                    assertEquals(cross, probe.cross_ledger_account ?: 0L, "cross-ledger counter of $label ($variant)")
                    assertEquals(mismatch, probe.currency_mismatch ?: 0L, "currency-mismatch counter of $label ($variant)")
                    assertTrue(
                        (probe.cross_ledger_account ?: 0L) <= (probe.unresolved_account ?: 0L),
                        "cross-ledger is always a subset of unresolved",
                    )
                    if (label == "cross-ledger") {
                        assertTrue((probe.cross_ledger_account ?: 0L) > 0L, "cross-ledger class must name a foreign row")
                    }

                    val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
                    val result = adapter.readReportSnapshot(reportRequest(version))
                    val failure = assertIs<P806SnapshotResult.Failed>(result)
                    // V-11: the typed failure is the existing domain mapping of the probe row, field
                    // for field, never a generic Unavailable.
                    val reference =
                        assertIs<P806SnapshotFailure.CatalogReference>(
                            failure.failure,
                            "class $label ($variant) must be a typed reference failure",
                        )
                    assertEquals(probe.unresolved_account ?: 0L, reference.unresolvedAccount, "typed unresolved of $label ($variant)")
                    assertEquals(probe.cross_ledger_account ?: 0L, reference.crossLedgerAccount, "typed cross-ledger of $label ($variant)")
                    assertEquals(probe.currency_mismatch ?: 0L, reference.currencyMismatch, "typed mismatch of $label ($variant)")
                }
            }
        }
    }

    @Test
    fun anUnparseableOccurredAtIsRefusedByTheProbeBeforeTheWindowWhileLegalShapesPass() {
        val badShapes =
            listOf(
                "2026-11-10T00:00:00", // no zone
                "2026-11-10 00:00:00Z", // space instead of T
                "2026-11-10T00:00:00.1234567890123Z", // too many fraction digits
                "2026-13-40T00:00:00Z", // illegal calendar date
                "not-a-timestamp",
            )
        badShapes.forEach { bad ->
            withDatabase { driver, database, version ->
                seedCatalog(driver, standardAccounts(), version)
                inFixtureTransaction(driver) {
                    insertTransaction(driver, "tx-bad", "EXPENSE", null, "2026-11-02T00:00:00Z", bad, listOf(breakfast to 100L, cash to -100L))
                }
                val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
                val result =
                    adapter.readAccountPostingsSnapshot(
                        P806AccountRequest(ledgerId, cash, window = novemberWindow(), expectedCatalogVersion = version),
                    )
                assertEquals(
                    P806SnapshotFailure.OccurredAtUnparseable,
                    (assertIs<P806SnapshotResult.Failed>(result)).failure,
                    "bad shape must be refused before the window: $bad",
                )
            }
        }
        val legalShapes = listOf("1970-01-01T00:00:00Z", "2026-11-10T08:00:00+08:00", "2026-11-10T00:00:00.123Z")
        legalShapes.forEach { good ->
            withDatabase { driver, database, version ->
                seedCatalog(driver, standardAccounts(), version)
                inFixtureTransaction(driver) {
                    insertTransaction(driver, "tx-good", "EXPENSE", null, "2026-11-02T00:00:00Z", good, listOf(breakfast to 100L, cash to -100L))
                }
                val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
                val result = adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cash, expectedCatalogVersion = version))
                assertEquals(
                    1,
                    (assertIs<P806SnapshotResult.Success<P806AccountSnapshot>>(result)).snapshot.totalCount,
                    "legal shape must pass: $good",
                )
            }
        }
    }

    @Test
    fun aSupersededVersionIsIgnoredAndAStaleCallerVersionIsTyped() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-current", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                exec(driver, "INSERT INTO posting_set (posting_set_id, ledger_id) VALUES ('ps-old-tx-current', '${ledgerId.value}')")
                exec(driver, "INSERT INTO transaction_version (version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) VALUES ('v-old', 'tx-current', '${ledgerId.value}', 2, 'ps-old-tx-current', '2026-11-02T00:00:00Z', '2026-11-02T00:00:00Z', '2026-11-02T00:00:00Z', '', ${Instant.parse("2026-11-02T00:00:00Z").toEpochMilliseconds() * 1_000_000L})")
                exec(driver, "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('p0-old', 'ps-old-tx-current', '${ledgerId.value}', 0, 'absent-in-superseded', 100, 'CNY', 2)")
                exec(driver, "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('p1-old', 'ps-old-tx-current', '${ledgerId.value}', 1, '${cash.value}', -100, 'CNY', 2)")
                // A superseded posting carrying a currency mismatch against the current catalog: the
                // current-version join must ignore it exactly like the superseded missing account.
                exec(driver, "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('p2-old', 'ps-old-tx-current', '${ledgerId.value}', 2, '${cash.value}', 5, 'USD', 2)")
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val report =
                assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(adapter.readReportSnapshot(reportRequest(version))).snapshot
            assertEquals(1, report.transactionCount)
            assertEquals(1, report.detailCount)
            assertEquals(listOf(cny), report.currencyCounts.map { it.currency }, "superseded rows never add a currency")
            assertEquals(listOf(cny), report.postingTotals.map { it.currency }, "superseded rows never add an aggregation row")

            val stale = adapter.readReportSnapshot(reportRequest(version + 1))
            assertEquals(P806SnapshotFailure.CatalogVersionMismatch, (assertIs<P806SnapshotResult.Failed>(stale)).failure)
        }
    }

    @Test
    fun aCatalogVersionAdvancedBetweenRequestsMakesTheOldExpectationTypedAndTheCurrentOneSucceed() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-version", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            // V-13: read the REAL catalog authority from the stored catalog tables, then advance the
            // stored version through the production catalog write. The snapshot compares its own
            // stored version with the caller's expectation, so the old expectation must fail typed.
            val store = SqlDelightCatalogStore(database, driver)
            val before = assertNotNull(store.load(ledgerId), "the fixture catalog must load as an authority")
            assertEquals(version, before.catalogVersion)
            inFixtureTransaction(driver) {
                database.ledgerQueries.advanceCatalogVersion(ledgerId.value)
            }
            val after = assertNotNull(store.load(ledgerId))
            assertEquals(version + 1L, after.catalogVersion)

            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val stale = adapter.readReportSnapshot(reportRequest(before.catalogVersion, before.catalog))
            assertTrue(stale !is P806SnapshotResult.Success, "a stale expectation must never return a snapshot")
            assertEquals(P806SnapshotFailure.CatalogVersionMismatch, (assertIs<P806SnapshotResult.Failed>(stale)).failure)

            val current =
                assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(
                    adapter.readReportSnapshot(reportRequest(after.catalogVersion, after.catalog)),
                ).snapshot
            assertEquals(after.catalogVersion, current.catalogVersion)
            assertEquals(1, current.transactionCount)
            assertEquals(1, current.detailCount)
        }
    }

    @Test
    fun anIntegerSumOverflowIsInvalidStateNotUnavailable() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            val half = Long.MAX_VALUE / 2L + 10L
            inFixtureTransaction(driver) {
                // Two postings on the same account and currency cross SQLite's INTEGER range.
                insertTransaction(driver, "tx-overflow-a", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to half, cash to -half))
                insertTransaction(driver, "tx-overflow-b", "EXPENSE", null, "2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", listOf(breakfast to half, cash to -half))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val result = adapter.readReportSnapshot(reportRequest(version))
            assertEquals(P806SnapshotFailure.InvalidState, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
    }

    @Test
    fun perSurfaceStatementCountsMatchTheFrozenC0() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-1", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val counting = CountingSqlDriver(driver)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(counting))

            counting.resetCounts()
            adapter.readReportSnapshot(reportRequest(version))
            assertEquals(12, counting.executeQueryCount)

            counting.resetCounts()
            adapter.readDrilldownSnapshot(drilldownRequest(novemberWindow(), null, version))
            assertEquals(5, counting.executeQueryCount)

            counting.resetCounts()
            adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cash, expectedCatalogVersion = version))
            assertEquals(5, counting.executeQueryCount)

            counting.resetCounts()
            adapter.readCalendarSnapshot(P806CalendarRequest(ledgerId, catalogForCurrentAccounts(), kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER), version))
            assertEquals(4 + 2 * 30, counting.executeQueryCount)
        }
    }

    @Test
    fun aSpaceSeparatedOccurredAtOutsideTheWindowIsRefusedBeforeTheWindowFilter() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-good", "EXPENSE", null, "2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                // Outside the requested November window, and shaped with a space separator: the
                // parser must answer NULL, so the account probe refuses it before any window filter.
                insertTransaction(driver, "tx-space", "EXPENSE", null, "2026-01-05T00:00:00Z", "2026-01-05 00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val result =
                adapter.readAccountPostingsSnapshot(
                    P806AccountRequest(ledgerId, cash, window = novemberWindow(), expectedCatalogVersion = version),
                )
            assertEquals(P806SnapshotFailure.OccurredAtUnparseable, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
    }

    @Test
    fun aDeepPageTailWhoseOccurredAtUsesTheSpaceShapeIsRefusedBeforeThePageIsFetched() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-newest", "EXPENSE", null, "2026-11-20T00:00:00Z", "2026-11-20T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-middle", "EXPENSE", null, "2026-11-19T00:00:00Z", "2026-11-19T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-tail-space", "EXPENSE", null, "2026-11-18T00:00:00Z", "2026-11-18 00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            // The first (deep) page request with a one-row page size must already be refused: the
            // refused row sits at the tail of the walk, never inside the page being fetched.
            val firstPage =
                adapter.readAccountPostingsSnapshot(
                    P806AccountRequest(ledgerId, cash, pageSize = 1, expectedCatalogVersion = version),
                )
            assertEquals(P806SnapshotFailure.OccurredAtUnparseable, (assertIs<P806SnapshotResult.Failed>(firstPage)).failure)
        }
    }

    @Test
    fun aNonExistentCivilDayIsRefusedBeforeTheWindowAndAPlusEightBoundaryIsAccepted() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                // Feb 30 normalises to March in SQLite but is not a real civil day; the shape rule
                // must reject it, and the row's statistics time is deliberately outside November so
                // only the probe (not a window) can catch it.
                insertTransaction(driver, "tx-feb30", "EXPENSE", null, "2026-01-05T00:00:00Z", "2026-02-30T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val result =
                adapter.readAccountPostingsSnapshot(
                    P806AccountRequest(ledgerId, cash, window = novemberWindow(), expectedCatalogVersion = version),
                )
            assertEquals(P806SnapshotFailure.OccurredAtUnparseable, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                insertTransaction(driver, "tx-newest", "EXPENSE", null, "2026-11-20T00:00:00Z", "2026-11-20T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
                insertTransaction(driver, "tx-tail-feb30", "EXPENSE", null, "2026-11-18T00:00:00Z", "2026-02-30T00:00:00Z", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            // pageSize = 1: the bad row would sit at the tail of the walk, yet the first request
            // must already be refused by the probe.
            val result =
                adapter.readAccountPostingsSnapshot(
                    P806AccountRequest(ledgerId, cash, pageSize = 1, expectedCatalogVersion = version),
                )
            assertEquals(P806SnapshotFailure.OccurredAtUnparseable, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                // A legal +08:00 timestamp just after the local day boundary: its civil date is
                // 2026-11-01 while the UTC instant is 2026-10-31T16:30Z. The civil-day rule must
                // accept it (no UTC normalisation) and the November window must include it.
                insertTransaction(driver, "tx-offset-boundary", "EXPENSE", null, "2026-11-01T00:30:00+08:00", "2026-11-01T00:30:00+08:00", listOf(breakfast to 100L, cash to -100L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val snapshot =
                (
                    adapter.readAccountPostingsSnapshot(
                        P806AccountRequest(ledgerId, cash, window = novemberWindow(), expectedCatalogVersion = version),
                    ) as P806SnapshotResult.Success<P806AccountSnapshot>
                ).snapshot
            assertEquals(listOf("tx-offset-boundary"), snapshot.page.rows.map { it.transactionId.value })
            assertEquals(1, snapshot.totalCount)
        }
    }

    @Test
    fun accountRowsCarryTheirOwnPostingsAndSurviveAStatisticsAxisCorrection() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            inFixtureTransaction(driver) {
                // Two postings on the SAME member account: the account page is posting-granular, so
                // it holds two rows whose amounts are 400 and 600, never the transaction's 1000 total.
                insertTransaction(driver, "tx-multi", "EXPENSE", null, "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", listOf(breakfast to -1_000L, cash to 400L, cash to 600L))
                // Statistics in October, real funds movement in November.
                insertTransaction(driver, "tx-stat-move", "EXPENSE", null, "2026-10-05T00:00:00Z", "2026-11-06T00:00:00Z", listOf(breakfast to -200L, cash to 200L))
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)

            val before =
                assertIs<P806SnapshotResult.Success<P806AccountSnapshot>>(
                    adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cash, expectedCatalogVersion = version)),
                ).snapshot
            assertEquals(3, before.totalCount, "two postings of one transaction count as two rows")
            assertEquals(listOf("tx-stat-move", "tx-multi", "tx-multi"), before.page.rows.map { it.transactionId.value })
            assertEquals(listOf(200L, 400L, 600L), before.page.rows.map { it.amount.minorUnits })
            assertTrue(before.page.rows.none { it.amount.minorUnits == 1_000L }, "a row is its own posting, never the transaction total")
            val multiRows = before.page.rows.filter { it.transactionId.value == "tx-multi" }
            assertEquals(1_000L, multiRows.sumOf { it.amount.minorUnits })

            val reportBefore =
                assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(adapter.readReportSnapshot(reportRequest(version))).snapshot
            assertEquals(1, reportBefore.transactionCount, "the October statistics row is outside the November period")
            assertEquals(1, reportBefore.detailCount)

            // Correct ONLY the statistics axis of the second transaction (inside this new method).
            exec(driver, "UPDATE transaction_version SET statistics_at = '2026-11-25T00:00:00Z', statistics_at_epoch_nanos = ${Instant.parse("2026-11-25T00:00:00Z").toEpochMilliseconds() * 1_000_000L} WHERE transaction_id = 'tx-stat-move'")

            val reportAfter =
                assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(adapter.readReportSnapshot(reportRequest(version))).snapshot
            assertEquals(2, reportAfter.transactionCount, "the report follows the corrected statistics axis")
            assertEquals(2, reportAfter.detailCount)

            val after =
                assertIs<P806SnapshotResult.Success<P806AccountSnapshot>>(
                    adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, cash, expectedCatalogVersion = version)),
                ).snapshot
            assertEquals(before.totalCount, after.totalCount)
            assertEquals(
                before.page.rows.map { listOf(it.transactionId.value, it.postingId.value, it.occurredAt.toString(), it.amount.minorUnits) },
                after.page.rows.map { listOf(it.transactionId.value, it.postingId.value, it.occurredAt.toString(), it.amount.minorUnits) },
                "the occurred_at axis keeps its order, amounts and set",
            )
        }
    }
    // ---------------------------------------------------------------- fixtures

    private fun reportRequest(
        version: Long,
        catalog: LedgerCatalog = catalogForCurrentAccounts(),
    ): P806ReportRequest =
        P806ReportRequest(
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

    private fun drilldownRequest(
        window: com.unifiedledger.application.P806Window,
        cursor: P806TransactionCursor?,
        version: Long,
    ): P806DrilldownRequest = P806DrilldownRequest(ledgerId = ledgerId, window = window, cursor = cursor, expectedCatalogVersion = version)

    private fun novemberWindow(): com.unifiedledger.application.P806Window =
        com.unifiedledger.application.P806Window(
            Instant.parse("2026-11-01T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
            Instant.parse("2026-12-01T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
        )

    /** Builds a window from two instants, preserving sub-millisecond precision. */
    private fun windowOf(
        start: String,
        end: String,
    ): com.unifiedledger.application.P806Window =
        com.unifiedledger.application.P806Window(
            epochNanos(Instant.parse(start)),
            epochNanos(Instant.parse(end)),
        )

    private fun epochNanos(instant: Instant): Long = instant.toEpochMilliseconds() * 1_000_000L + (instant.nanosecondsOfSecond % 1_000_000L).toLong()

    private fun catalogForCurrentAccounts(): LedgerCatalog = catalogOf(standardAccounts() + usdAccount())

    private fun usdAccount(): Account = Account(usdExpense, ledgerId, AccountKind.EXPENSE, usd, false, false, name = "USD 支出")

    private fun usdAssetAccount(): Account = Account(usdCash, ledgerId, AccountKind.ASSET, usd, true, true, name = "USD 现金")

    private fun usdIncomeAccount(): Account = Account(usdSalary, ledgerId, AccountKind.INCOME, usd, false, false, name = "USD 工资")

    private fun standardAccounts(): List<Account> =
        listOf(
            Account(cash, ledgerId, AccountKind.ASSET, cny, true, true, name = "现金"),
            Account(savings, ledgerId, AccountKind.ASSET, cny, true, true, name = "储蓄"),
            Account(breakfast, ledgerId, AccountKind.EXPENSE, cny, false, false, name = "早餐账户"),
            Account(salary, ledgerId, AccountKind.INCOME, cny, false, false, name = "工资账户"),
        )

    private fun standardCategories(): List<Category> =
        listOf(
            Category(foodParent, ledgerId, parentId = null, postingAccountId = null, active = true, name = "餐饮"),
            Category(breakfastLeaf, ledgerId, parentId = foodParent, postingAccountId = breakfast, active = true, name = "早餐"),
        )

    private fun catalogOf(accounts: List<Account>): LedgerCatalog =
        when (val result = LedgerCatalog.create(accounts, standardCategories())) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("fixture catalog must be valid")
        }

    private fun seedCatalog(
        driver: SqlDriver,
        accounts: List<Account>,
        version: Long,
    ) {
        exec(driver, "INSERT INTO catalog_version (ledger_id, version) VALUES ('${ledgerId.value}', $version)")
        accounts.forEach { account ->
            exec(
                driver,
                "INSERT INTO catalog_account (ledger_id, account_id, name, kind, currency_code, currency_precision, owned_by_user, real_account, system_role, hidden, active) " +
                    "VALUES ('${account.ledgerId.value}', '${account.id.value}', '${account.name}', '${account.kind.name}', '${account.currency.code}', ${account.currency.precision}, ${if (account.ownedByUser) 1 else 0}, ${if (account.realAccount) 1 else 0}, NULL, 0, 1)",
            )
        }
        standardCategories().forEach { category ->
            exec(
                driver,
                "INSERT INTO catalog_category (ledger_id, category_id, kind, parent_id, posting_account_id, active) VALUES " +
                    "('${category.ledgerId.value}', '${category.id.value}', '${category.kind.name}', ${category.parentId?.let { "'${it.value}'" } ?: "NULL"}, ${category.postingAccountId?.let { "'${it.value}'" } ?: "NULL"}, 1)",
            )
        }
    }

    private fun insertBadPosting(
        driver: SqlDriver,
        id: String,
        accountId: AccountId,
        amount: Long,
    ) {
        insertTransaction(driver, id, "EXPENSE", null, "2026-11-06T00:00:00Z", "2026-11-06T00:00:00Z", listOf(accountId to amount, cash to -amount))
    }

    private fun insertCurrencyMismatch(
        driver: SqlDriver,
        id: String,
        code: String,
        precision: Long,
    ) {
        exec(driver, "INSERT INTO ledger_transaction (transaction_id, ledger_id, kind, canonical_kind) VALUES ('$id', '${ledgerId.value}', 'EXPENSE', NULL)")
        exec(driver, "INSERT INTO posting_set (posting_set_id, ledger_id) VALUES ('ps-$id', '${ledgerId.value}')")
        exec(driver, "INSERT INTO transaction_version (version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) VALUES ('v-$id', '$id', '${ledgerId.value}', 1, 'ps-$id', '2026-11-06T00:00:00Z', '2026-11-06T00:00:00Z', '2026-11-06T00:00:00Z', '', ${Instant.parse("2026-11-06T00:00:00Z").toEpochMilliseconds() * 1_000_000L})")
        exec(driver, "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('p0-$id', 'ps-$id', '${ledgerId.value}', 0, '${breakfast.value}', 100, '$code', $precision)")
        exec(driver, "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('p1-$id', 'ps-$id', '${ledgerId.value}', 1, '${cash.value}', -100, 'CNY', 2)")
        exec(driver, "INSERT INTO ledger_transaction_current_version (transaction_id, ledger_id, current_version_id) VALUES ('$id', '${ledgerId.value}', 'v-$id')")
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
    ) {
        fixtureInserts(FixtureTransaction(id, kind, canonicalKind, statisticsAt, occurredAt, postings, currency))
            .forEach { insert ->
                insert.rows.forEach { row -> exec(driver, "INSERT INTO ${insert.table} ${insert.columns} VALUES $row") }
            }
    }

    /** One fixture transaction's row values, shared by the single-row and the bulk writers. */
    private data class FixtureTransaction(
        val id: String,
        val kind: String,
        val canonicalKind: String?,
        val statisticsAt: String,
        val occurredAt: String,
        val postings: List<Pair<AccountId, Long>>,
        val currency: CurrencyUnit,
    )

    /** One table's INSERT target plus the row literals to write for it. */
    private class TableInsert(
        val table: String,
        val columns: String,
        val rows: List<String>,
    )

    /** The rows [insertTransaction] writes for one transaction, table by table. */
    private fun fixtureInserts(transaction: FixtureTransaction): List<TableInsert> {
        val id = transaction.id
        val ledger = ledgerId.value
        val canonical = transaction.canonicalKind?.let { "'$it'" } ?: "NULL"
        return listOf(
            TableInsert(
                table = "ledger_transaction",
                columns = "(transaction_id, ledger_id, kind, canonical_kind)",
                rows = listOf("('$id', '$ledger', '${transaction.kind}', $canonical)"),
            ),
            TableInsert(
                table = "posting_set",
                columns = "(posting_set_id, ledger_id)",
                rows = listOf("('ps-$id', '$ledger')"),
            ),
            TableInsert(
                table = "transaction_version",
                columns = "(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos)",
                rows =
                    listOf(
                        "('v-$id', '$id', '$ledger', 1, 'ps-$id', '${transaction.occurredAt}', '${transaction.statisticsAt}', " +
                            "'${transaction.statisticsAt}', '', ${Instant.parse(transaction.statisticsAt).toEpochMilliseconds() * 1_000_000L})",
                    ),
            ),
            TableInsert(
                table = "posting",
                columns = "(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision)",
                rows =
                    transaction.postings.mapIndexed { index, (accountId, amount) ->
                        "('p$index-$id', 'ps-$id', '$ledger', $index, '${accountId.value}', $amount, " +
                            "'${transaction.currency.code}', ${transaction.currency.precision})"
                    },
            ),
            TableInsert(
                table = "ledger_transaction_current_version",
                columns = "(transaction_id, ledger_id, current_version_id)",
                rows = listOf("('$id', '$ledger', 'v-$id')"),
            ),
        )
    }

    /**
     * The 50k writer: the rows [insertTransaction] writes, batched into multi-row INSERTs of
     * [BULK_ROWS] rows (the P705Harness.kt:389-473 shape) so the fixture pays one statement prepare
     * per chunk instead of one per row. The caller keeps its own managed fixture transaction; only
     * the statement count per row changes. [progress] receives one bounded line per
     * [BULK_PROGRESS_ROWS] rows written.
     */
    private fun insertTransactionsBulk(
        driver: SqlDriver,
        count: Int,
        progress: SeedProgress? = null,
        row: (Int) -> FixtureTransaction,
    ) {
        var start = 0
        while (start < count) {
            val end = minOf(start + BULK_ROWS, count)
            writeBulk(driver, (start until end).map(row))
            start = end
            if (progress != null && start % BULK_PROGRESS_ROWS == 0) progress.bulkRows(start)
        }
    }

    private fun writeBulk(
        driver: SqlDriver,
        transactions: List<FixtureTransaction>,
    ) {
        if (transactions.isEmpty()) return
        val tables = transactions.map(::fixtureInserts)
        tables.first().forEachIndexed { index, insert ->
            tables
                .flatMap { it[index].rows }
                .chunked(BULK_ROWS)
                .forEach { chunk ->
                    exec(driver, "INSERT INTO ${insert.table} ${insert.columns} VALUES ${chunk.joinToString(", ")}")
                }
        }
    }

    /**
     * Runs [block] inside ONE SQLDelight-managed fixture transaction so bulk preparation never pays
     * one autocommit per row. The transaction machinery owns BEGIN/COMMIT/ROLLBACK: a bare `BEGIN`
     * sent through an untransacted `execute` sits outside it, and the fixture then dies with
     * SQLiteException. The read path is deliberately NOT called inside this transaction.
     */
    private fun inFixtureTransaction(
        driver: SqlDriver,
        block: () -> Unit,
    ) {
        LedgerDatabase(driver).transaction { block() }
    }

    private inline fun withDatabase(block: (SqlDriver, LedgerDatabase, Long) -> Unit) {
        val path: Path = Files.createTempFile("p806-read-", ".db")
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

    /**
     * The V-4 phase progress that survives a cancelled job: a job killed before the test finishes
     * writes no JUnit XML, so every phase start and finish is flushed into the module build
     * directory as it happens.
     *
     * Gradle runs this module's test task with `ledger-data` as the working directory, so the
     * relative path resolves inside `ledger-data/build/` -- a build artifact, never committed, and
     * never an absolute host path. Each line carries the `P806-V4 ` prefix that
     * `tools/ci/scale-readings-report.py` reads when no XML exists; a line holds a phase name, a
     * row count or an elapsed millisecond figure, nothing else.
     */
    private class SeedProgress(
        private val path: Path,
    ) {
        private var phase = ""
        private var phaseStartNanos = 0L
        private var replaceFile = true

        /** Starts [name]; the test's first phase replaces the previous run's file. */
        fun start(name: String) {
            if (replaceFile) {
                Files.deleteIfExists(path)
                replaceFile = false
            }
            phase = name
            phaseStartNanos = System.nanoTime()
            write("$name start")
        }

        /** Closes the current phase with its System.nanoTime() duration in whole milliseconds. */
        fun finish() {
            write("$phase done elapsed_ms=${(System.nanoTime() - phaseStartNanos) / 1_000_000L}")
        }

        /** Bounded seed progress: one line per 10,000 rows, never one per chunk. */
        fun bulkRows(rows: Int) {
            write("seed rows=$rows")
        }

        private fun write(line: String) {
            path.parent?.let { Files.createDirectories(it) }
            Files.write(
                path,
                listOf("P806-V4 $line"),
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
                StandardOpenOption.SYNC,
            )
        }
    }

    /**
     * P8-06 V-4: a TEST-LOCAL cursor-row observer. It is deliberately not part of the shared scale
     * support: it wraps only `executeQuery`, records the SQL text and the number of rows its mapper
     * really consumed, and delegates every other driver operation. The count is the number of
     * `next() == true` answers, so a statement that returns a 50,000-row list is visible as such.
     */
    private class RowCountingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        private val observations = mutableListOf<Pair<String, Int>>()

        val queryRows: List<Pair<String, Int>> get() = observations.toList()

        val maxRows: Int get() = observations.maxOfOrNull { it.second } ?: 0

        fun reset() {
            observations.clear()
        }

        fun countMatching(fragment: String): Int = observations.count { it.first.contains(fragment, ignoreCase = true) }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            var rows = 0
            val result =
                delegate.executeQuery(
                    identifier,
                    sql,
                    { cursor -> mapper(CountingCursor(cursor) { rows += 1 }) },
                    parameters,
                    binders,
                )
            observations += sql to rows
            return result
        }
    }

    /** Delegates the cursor and invokes [onRow] once per `next()` that reports a row. */
    private class CountingCursor(
        private val delegate: SqlCursor,
        private val onRow: () -> Unit,
    ) : SqlCursor by delegate {
        override fun next(): QueryResult<Boolean> {
            val result = delegate.next()
            if (result.value) onRow()
            return result
        }
    }

    private class FixedClock(
        private val value: String,
    ) : LedgerClock {
        override fun now(): Instant = Instant.parse(value)
    }

    private companion object {
        /** Rows per multi-row INSERT: SQLite bounds one compound VALUES list at 500 rows. */
        const val BULK_ROWS = 500

        /** Upper bound on seed-progress lines: one per 10,000 rows written. */
        const val BULK_PROGRESS_ROWS = 10_000
    }
}
