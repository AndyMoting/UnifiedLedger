package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.P806AccountRequest
import com.unifiedledger.application.P806AccountSnapshot
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806DrilldownSnapshot
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportRequest
import com.unifiedledger.application.P806ReportSnapshot
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.P806TransactionCursor
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
import java.nio.file.Files
import java.nio.file.Path
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

    @Test
    fun theContributionSetIsIndependentCountedOncePerTransactionAndPagesToNull() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts() + usdAccount(), version)
            // 60 plain expenses inside ONE fixture transaction.
            inFixtureTransaction(driver) {
                repeat(60) { index ->
                    insertTransaction(
                        driver,
                        "tx-exp-$index",
                        "EXPENSE",
                        null,
                        "2026-11-02T0${index % 9}:00:00Z",
                        "2026-11-02T0${index % 9}:00:00Z",
                        listOf(breakfast to 1_000L + index, cash to -(1_000L + index)),
                    )
                }
            }
            inFixtureTransaction(driver) {
                // A special kind and the two principal-only shapes never contribute.
                insertTransaction(driver, "tx-stored", "EXPENSE", "STORED_VALUE_SPEND", "2026-11-03T01:00:00Z", "2026-11-03T01:00:00Z", listOf(breakfast to 5_000L, cash to -5_000L))
                insertTransaction(driver, "tx-transfer", "ACCOUNT_TRANSFER", null, "2026-11-03T02:00:00Z", "2026-11-03T02:00:00Z", listOf(savings to 2_000L, cash to -2_000L))
                insertTransaction(driver, "tx-lend", "EXPENSE", "LEND", "2026-11-03T03:00:00Z", "2026-11-03T03:00:00Z", listOf(savings to 3_000L, cash to -3_000L))
                // One multi-leg expense: counted ONCE.
                insertTransaction(driver, "tx-split", "EXPENSE", null, "2026-11-04T01:00:00Z", "2026-11-04T01:00:00Z", listOf(breakfast to 400L, breakfast to 600L, cash to -1_000L))
                // A refund receipt: a negative ordinary expense.
                insertTransaction(driver, "tx-refund", "EXPENSE", "REFUND_RECEIPT", "2026-11-05T01:00:00Z", "2026-11-05T01:00:00Z", listOf(breakfast to -300L, cash to 300L))
            }

            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val expectedIds = (0 until 60).map { "tx-exp-$it" }.toSet() + setOf("tx-split", "tx-refund")
            val ids = mutableListOf<String>()
            var cursor: P806TransactionCursor? = null
            var pages = 0
            do {
                val snapshot =
                    (adapter.readDrilldownSnapshot(drilldownRequest(novemberWindow(), cursor, version))
                        as P806SnapshotResult.Success<P806DrilldownSnapshot>).snapshot
                assertEquals(62, snapshot.totalCount)
                snapshot.page.rows.forEach { ids += it.transactionId.value }
                cursor = snapshot.page.nextCursor
                pages += 1
                assertTrue(pages <= 5)
            } while (cursor != null)

            assertEquals(62, ids.size)
            assertEquals(62, ids.toSet().size)
            assertEquals(expectedIds, ids.toSet())
            // D-343 3.4（键集页 look-ahead）: two pages (50 + 12), the first page exactly 50 and the continuation real.
            assertEquals(2, pages)

            // D-343 3.4（同组正/负/净三列）: gross/refund/net come from the same grouped rows.
            val report = (adapter.readReportSnapshot(reportRequest(version)) as P806SnapshotResult.Success<P806ReportSnapshot>).snapshot
            assertEquals(62, report.transactionCount)
            assertEquals(50, report.firstPage.rows.size)
            assertEquals(62, report.detailCount)
            val cnyTotals = report.postingTotals.filter { it.currency == cny }
            val breakfastGross = cnyTotals.first { it.accountId == breakfast }
            assertEquals(-300L, breakfastGross.refundMinorUnits)
            assertTrue(breakfastGross.positiveMinorUnits > 0L)
            assertEquals(breakfastGross.positiveMinorUnits + breakfastGross.refundMinorUnits, breakfastGross.totalMinorUnits)
        }
    }

    @Test
    fun aFiftyThousandRowMonthStaysBoundedAndThePrincipalFrontPageStillReturnsFiftyContributions() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts(), version)
            // One fixture transaction for the whole bulk: 50,000 ordinary expenses plus 50 principal
            // transfers whose statistics instants are the NEWEST, so a page that ignored the
            // contribution predicate would show only principal rows.
            inFixtureTransaction(driver) {
                repeat(50_000) { index ->
                    val id = "tx-bulk-$index"
                    val stamp = "2026-11-10T%02d:%02d:%02dZ".format(index % 24, index % 60, index % 60)
                    insertTransaction(driver, id, "EXPENSE", null, stamp, stamp, listOf(breakfast to 100L, cash to -100L))
                }
                repeat(50) { index ->
                    val id = "tx-principal-$index"
                    val stamp = "2026-11-30T23:59:%02dZ".format(index % 60)
                    insertTransaction(driver, id, "ACCOUNT_TRANSFER", null, stamp, stamp, listOf(savings to 100L, cash to -100L))
                }
            }

            val counting = CountingSqlDriver(driver)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(LedgerDatabase(counting))

            counting.resetCounts()
            val report = (adapter.readReportSnapshot(reportRequest(version)) as P806SnapshotResult.Success<P806ReportSnapshot>).snapshot
            assertEquals(50_000, report.transactionCount)
            assertEquals(50, report.firstPage.rows.size)
            assertEquals(50_000, report.detailCount)
            // The first page must be ordinary contributions, never the 50 newest principal rows.
            assertTrue(report.firstPage.rows.none { it.transactionId.value.startsWith("tx-principal-") })
            // The frozen report C0 is 12 SELECT statements, independent of the row count.
            assertEquals(12, counting.executeQueryCount)

            counting.resetCounts()
            val second =
                (adapter.readDrilldownSnapshot(
                    drilldownRequest(novemberWindow(), report.firstPage.nextCursor, version),
                ) as P806SnapshotResult.Success<P806DrilldownSnapshot>).snapshot
            assertEquals(50, second.page.rows.size)
            assertNotNull(second.page.nextCursor)
            assertEquals(50_000, second.totalCount)
            // Drilldown C0 is 5 SELECT statements per page.
            assertEquals(5, counting.executeQueryCount)
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
            val upToHalf = (adapter.readAccountPostingsSnapshot(
                P806AccountRequest(
                    ledgerId,
                    cash,
                    window = windowOf("2026-11-01T00:00:00Z", "2026-11-04T00:00:00.500Z"),
                    expectedCatalogVersion = version,
                ),
            ) as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot
            assertEquals(listOf("tx-z", "tx-offset"), upToHalf.page.rows.map { it.transactionId.value })

            // Mixed offset/Z ordering is chronological, and the non-member account stays out.
            val internal = adapter.readAccountPostingsSnapshot(P806AccountRequest(ledgerId, usdExpense, expectedCatalogVersion = version))
            assertEquals(0, (internal as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot.totalCount)

            // D-343 3.4（键集页 look-ahead） style look-ahead for the account page: page size 1 leaves a real cursor.
            val firstPage = (adapter.readAccountPostingsSnapshot(
                P806AccountRequest(ledgerId, cash, pageSize = 1, expectedCatalogVersion = version),
            ) as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot
            assertEquals(1, firstPage.page.rows.size)
            val cursor = firstPage.page.nextCursor
            assertNotNull(cursor)
            val secondPage = (adapter.readAccountPostingsSnapshot(
                P806AccountRequest(ledgerId, cash, cursor = cursor, pageSize = 1, expectedCatalogVersion = version),
            ) as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot
            assertEquals(listOf("tx-z"), secondPage.page.rows.map { it.transactionId.value })
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
                    val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
                    val result = adapter.readReportSnapshot(reportRequest(version))
                    val failure = assertIs<P806SnapshotResult.Failed>(result)
                    assertIs<P806SnapshotFailure.CatalogReference>(failure.failure, "class $label ($variant) must be a typed reference failure")
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
                val result = adapter.readAccountPostingsSnapshot(
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
            }
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(adapter.readReportSnapshot(reportRequest(version)))

            val stale = adapter.readReportSnapshot(reportRequest(version + 1))
            assertEquals(P806SnapshotFailure.CatalogVersionMismatch, (assertIs<P806SnapshotResult.Failed>(stale)).failure)
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
            val result = adapter.readAccountPostingsSnapshot(
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
            val firstPage = adapter.readAccountPostingsSnapshot(
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
            val result = adapter.readAccountPostingsSnapshot(
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
            val result = adapter.readAccountPostingsSnapshot(
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
            val snapshot = (adapter.readAccountPostingsSnapshot(
                P806AccountRequest(ledgerId, cash, window = novemberWindow(), expectedCatalogVersion = version),
            ) as P806SnapshotResult.Success<P806AccountSnapshot>).snapshot
            assertEquals(listOf("tx-offset-boundary"), snapshot.page.rows.map { it.transactionId.value })
            assertEquals(1, snapshot.totalCount)
        }
    }
    // ---------------------------------------------------------------- fixtures

    private fun reportRequest(version: Long): P806ReportRequest =
        P806ReportRequest(
            ledgerId = ledgerId,
            catalog = catalogForCurrentAccounts(),
            period = P806Period(
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
    ): P806DrilldownRequest =
        P806DrilldownRequest(ledgerId = ledgerId, window = window, cursor = cursor, expectedCatalogVersion = version)

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
        val canonical = canonicalKind?.let { "'$it'" } ?: "NULL"
        exec(driver, "INSERT INTO ledger_transaction (transaction_id, ledger_id, kind, canonical_kind) VALUES ('$id', '${ledgerId.value}', '$kind', $canonical)")
        exec(driver, "INSERT INTO posting_set (posting_set_id, ledger_id) VALUES ('ps-$id', '${ledgerId.value}')")
        exec(
            driver,
            "INSERT INTO transaction_version (version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) " +
                "VALUES ('v-$id', '$id', '${ledgerId.value}', 1, 'ps-$id', '$occurredAt', '$statisticsAt', '$statisticsAt', '', ${Instant.parse(statisticsAt).toEpochMilliseconds() * 1_000_000L})",
        )
        postings.forEachIndexed { index, (accountId, amount) ->
            exec(
                driver,
                "INSERT INTO posting (posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                    "VALUES ('p$index-$id', 'ps-$id', '${ledgerId.value}', $index, '${accountId.value}', $amount, '${currency.code}', ${currency.precision})",
            )
        }
        exec(driver, "INSERT INTO ledger_transaction_current_version (transaction_id, ledger_id, current_version_id) VALUES ('$id', '${ledgerId.value}', 'v-$id')")
    }

    /**
     * Runs [block] inside ONE explicit fixture transaction so bulk preparation never pays one
     * autocommit per row. The read path is deliberately NOT called inside this transaction.
     */
    private inline fun inFixtureTransaction(
        driver: SqlDriver,
        block: () -> Unit,
    ) {
        exec(driver, "BEGIN")
        try {
            block()
            exec(driver, "COMMIT")
        } catch (failure: Throwable) {
            exec(driver, "ROLLBACK")
            throw failure
        }
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
}
