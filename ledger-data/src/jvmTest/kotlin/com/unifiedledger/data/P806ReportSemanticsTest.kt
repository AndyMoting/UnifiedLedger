package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogAuthority
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.P806CalendarQuery
import com.unifiedledger.application.P806CalendarSnapshot
import com.unifiedledger.application.P806CalendarView
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806ReportQuery
import com.unifiedledger.application.P806ReportSnapshot
import com.unifiedledger.application.P806ReportView
import com.unifiedledger.application.P806SnapshotFailure
import com.unifiedledger.application.P806SnapshotResult
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P8-06 batch a (D-343; spec section 4, D-343 3.1.2（跨度只读数值投影）/3.5.4/4 and 无期间（NoPeriod）/类型化读失败 read-face vectors):
 * report period semantics against the frozen monthly read, calendar zero/global-count rules, the
 * five-kind NoPeriod answer on an empty ledger, the future marker counts and the two
 * window-independent consistency probes.
 *
 * Executed by cloud CI only; the fixture is anonymous and synthetic.
 */
class P806ReportSemanticsTest {
    private val ledgerId = LedgerId("ledger-p806-semantics")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val cash = AccountId("asset-cash")
    private val savings = AccountId("asset-savings")
    private val breakfast = AccountId("expense-breakfast")
    private val foodParent = CategoryId("cat-food")
    private val breakfastLeaf = CategoryId("cat-breakfast")
    private val salary = AccountId("income-salary")
    private val salaryParent = CategoryId("cat-salary")
    private val salaryLeaf = CategoryId("cat-salary-leaf")
    private val usdExpense = AccountId("expense-usd")
    private val usdCash = AccountId("asset-usd-cash")

    @Test
    fun reportMonthTotalsMatchTheFrozenMonthlyReadAndPartialPeriodsUseTheirOwnWindow() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, standardAccounts() + usdAccount() + usdAssetAccount(), version)
            val authority = CatalogAuthority(ledgerId, catalog, version)
            insertTransaction(driver, "tx-1", "EXPENSE", null, "2026-11-02T02:00:00Z", "2026-11-02T02:00:00Z", listOf(breakfast to 1_000L, cash to -1_000L))
            insertTransaction(driver, "tx-2", "EXPENSE", "REFUND_RECEIPT", "2026-11-06T02:00:00Z", "2026-11-06T02:00:00Z", listOf(breakfast to -200L, cash to 200L))
            insertTransaction(driver, "tx-3", "INCOME", null, "2026-11-07T02:00:00Z", "2026-11-07T02:00:00Z", listOf(salary to -5_000L, cash to 5_000L))
            insertTransaction(driver, "tx-4", "EXPENSE", null, "2026-11-20T02:00:00Z", "2026-11-20T02:00:00Z", listOf(breakfast to 300L, cash to -300L))

            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val clock = FixedClock("2026-11-17T00:00:00Z")
            val report = P806ReportQuery(adapter, ledgerId, authority, clock)
            val monthView = (report.query(P806Period(P806PeriodKind.MONTH)) as P806SnapshotResult.Success<P806ReportView>).snapshot
            val cnyView = monthView.currencies.first { it.currency == cny }
            assertEquals(1_100L, cnyView.netExpenseMinorUnits)
            assertEquals(5_000L, cnyView.ordinaryIncomeMinorUnits)
            assertEquals(3_900L, cnyView.balanceMinorUnits)
            assertEquals(1_300L, cnyView.positiveExpenseMinorUnits)
            assertEquals(-200L, cnyView.refundMinorUnits)

            val monthly =
                (com.unifiedledger.application.QueryMonthlyActivity(adapter, ledgerId, catalog, clock)
                    .query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)) as
                    com.unifiedledger.application.MonthlyActivityResult.Success).activity
            val frozen = monthly.currencies.first { it.currency == cny }
            assertEquals(frozen.netExpenseMinorUnits, cnyView.netExpenseMinorUnits)
            assertEquals(frozen.ordinaryIncomeMinorUnits, cnyView.ordinaryIncomeMinorUnits)

            // V-3: a partial period uses its own half-open window, never the month card minus a month.
            val firstHalf =
                (report.query(
                    P806Period(P806PeriodKind.CUSTOM, customStart = kotlinx.datetime.LocalDate(2026, 11, 1), customEnd = kotlinx.datetime.LocalDate(2026, 11, 15)),
                ) as P806SnapshotResult.Success<P806ReportView>).snapshot
            assertEquals(800L, firstHalf.currencies.first { it.currency == cny }.netExpenseMinorUnits)

            // V-2: the independent category set equals the ranked categories, level-1 = sum of children.
            val parent = monthView.expenseCategories.first { it.categoryId == foodParent }
            val child = parent.children.first { it.categoryId == breakfastLeaf }
            assertEquals(1_300L, child.totals.first().positiveMinorUnits)
            assertEquals(-200L, child.totals.first().refundMinorUnits)
            assertEquals(1_300L, parent.totals.first().positiveMinorUnits)
            assertEquals(-200L, parent.totals.first().refundMinorUnits)
        }
    }

    @Test
    fun calendarCountsAreGlobalAndZeroDaysCarryOneZeroRowPerAxisCurrency() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts() + usdAccount() + usdAssetAccount(), version)
            insertTransaction(driver, "tx-cny", "EXPENSE", null, "2026-11-10T02:00:00Z", "2026-11-10T02:00:00Z", listOf(breakfast to 1_000L, cash to -1_000L))
            insertTransaction(driver, "tx-usd", "EXPENSE", null, "2026-11-10T03:00:00Z", "2026-11-10T03:00:00Z", listOf(usdExpense to 500L, usdCash to -500L), currency = usd)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val catalog = catalogOf(standardAccounts() + usdAccount() + usdAssetAccount())
            val query = P806CalendarQuery(adapter, ledgerId, CatalogAuthority(ledgerId, catalog, version), FixedClock("2026-11-17T00:00:00Z"))
            val view = (query.query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)) as P806SnapshotResult.Success<P806CalendarView>).snapshot
            assertEquals(30, view.days.size)
            val busy = view.days.first { it.date == kotlinx.datetime.LocalDate(2026, 11, 10) }
            assertEquals(2, busy.transactionCount)
            assertEquals(2, busy.currencies.size)
            assertEquals(1_000L, busy.currencies.first { it.currency == cny }.netExpenseMinorUnits)
            assertEquals(500L, busy.currencies.first { it.currency == usd }.netExpenseMinorUnits)
            val zero = view.days.first { it.date == kotlinx.datetime.LocalDate(2026, 11, 11) }
            assertEquals(0, zero.transactionCount)
            assertEquals(2, zero.currencies.size)
            assertTrue(zero.currencies.all { it.netExpenseMinorUnits == 0L && it.ordinaryIncomeMinorUnits == 0L })
            assertEquals(cny, view.axis.first())
            assertEquals(usd, view.axis.last())
            // The raw snapshot carries the same global count (one number per day).
            val raw = (adapter.readCalendarSnapshot(
                com.unifiedledger.application.P806CalendarRequest(ledgerId, catalog, kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER), version),
            ) as P806SnapshotResult.Success<P806CalendarSnapshot>).snapshot
            assertEquals(2, raw.days.first { it.date == kotlinx.datetime.LocalDate(2026, 11, 10) }.transactionCount)
        }
    }

    @Test
    fun anUnrepresentableCalendarMonthIsInvalidWindowNotUnavailable() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, standardAccounts(), version)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val result = adapter.readCalendarSnapshot(
                P806CalendarRequest(ledgerId, catalog, kotlinx.datetime.YearMonth(999999, kotlinx.datetime.Month.DECEMBER), version),
            )
            assertEquals(P806SnapshotFailure.InvalidWindow, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
    }
    @Test
    fun anEmptyLedgerAnswersNoPeriodForAllFiveKindsAndTheCalendarStillHasBuckets() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, standardAccounts(), version)
            val authority = CatalogAuthority(ledgerId, catalog, version)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val clock = FixedClock("2026-11-17T00:00:00Z")
            val report = P806ReportQuery(adapter, ledgerId, authority, clock)
            val kinds =
                listOf(
                    P806Period(P806PeriodKind.WEEK),
                    P806Period(P806PeriodKind.MONTH),
                    P806Period(P806PeriodKind.YEAR),
                    P806Period(P806PeriodKind.ALL),
                    P806Period(P806PeriodKind.CUSTOM, customStart = kotlinx.datetime.LocalDate(2026, 1, 1), customEnd = kotlinx.datetime.LocalDate(2026, 1, 31)),
                )
            kinds.forEach { period ->
                val view = (report.query(period) as P806SnapshotResult.Success<P806ReportView>).snapshot
                assertNull(view.period)
                assertTrue(view.dailyAverageNotApplicable)
                assertEquals(0, view.transactionCount)
                assertEquals(0, view.detailCount)
                assertTrue(view.currencies.isEmpty())
            }
            // Geometry stays available: the calendar still renders every local day bucket.
            val calendar = P806CalendarQuery(adapter, ledgerId, authority, clock)
            val view = (calendar.query(kotlinx.datetime.YearMonth(2026, kotlinx.datetime.Month.NOVEMBER)) as P806SnapshotResult.Success<P806CalendarView>).snapshot
            assertEquals(30, view.days.size)
            assertTrue(view.days.all { it.transactionCount == 0 && it.currencies.isEmpty() })
        }
    }

    @Test
    fun theFutureMarkerCountsOnlyOrdinaryContributionsAndCanBeZero() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, standardAccounts(), version)
            val authority = CatalogAuthority(ledgerId, catalog, version)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val clock = FixedClock("2026-11-17T00:00:00Z")
            val report = P806ReportQuery(adapter, ledgerId, authority, clock)

            val noneYet = (adapter.readReportSnapshot(reportRequest(version, catalog)) as P806SnapshotResult.Success<P806ReportSnapshot>).snapshot
            assertEquals(0, noneYet.futureTransactionCount)

            insertTransaction(driver, "tx-future-expense", "EXPENSE", null, "2026-11-20T02:00:00Z", "2026-11-20T02:00:00Z", listOf(breakfast to 100L, cash to -100L))
            insertTransaction(driver, "tx-future-special", "EXPENSE", "STORED_VALUE_SPEND", "2026-11-21T02:00:00Z", "2026-11-21T02:00:00Z", listOf(breakfast to 100L, cash to -100L))
            insertTransaction(driver, "tx-future-transfer", "ACCOUNT_TRANSFER", null, "2026-11-22T02:00:00Z", "2026-11-22T02:00:00Z", listOf(savings to 100L, cash to -100L))
            val view = (report.query(P806Period(P806PeriodKind.MONTH)) as P806SnapshotResult.Success<P806ReportView>).snapshot
            // Only the ordinary contribution counts; the special kind and the principal do not.
            assertEquals(1, view.futureTransactionCount)
        }
    }

    @Test
    fun aStatisticsProjectionMissingOutsideTheWindowStillFailsTheRead() {
        withDatabase { driver, database, version ->
            val catalog = seedCatalog(driver, standardAccounts(), version)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            insertTransaction(driver, "tx-in-window", "EXPENSE", null, "2026-11-02T02:00:00Z", "2026-11-02T02:00:00Z", listOf(breakfast to 100L, cash to -100L))
            // A version outside the requested window whose numeric projection is missing.
            insertTransaction(driver, "tx-outside", "EXPENSE", null, "2026-01-05T02:00:00Z", "2026-01-05T02:00:00Z", listOf(breakfast to 100L, cash to -100L))
            exec(driver, "UPDATE transaction_version SET statistics_at_epoch_nanos = NULL WHERE transaction_id = 'tx-outside'")
            val result = adapter.readReportSnapshot(reportRequest(version, catalog))
            assertEquals(P806SnapshotFailure.MissingProjection, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
    }

    @Test
    fun anAxisCurrencyUsedOnlyOutsideTheWindowFailsTheSharedRule() {
        withDatabase { driver, database, version ->
            seedCatalog(driver, standardAccounts() + usdAccount() + usdAssetAccount(), version)
            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            // The USD account rows are removed from the catalog while a USD posting stays in the
            // ledger (an earlier month). The request carries the catalog of the SAME generation as
            // the surviving database — without USD — so the shared axis rule must fail.
            insertTransaction(driver, "tx-usd-outside", "EXPENSE", null, "2026-10-02T02:00:00Z", "2026-10-02T02:00:00Z", listOf(usdExpense to 500L, usdCash to -500L), currency = usd)
            exec(driver, "DELETE FROM catalog_account WHERE ledger_id = '${ledgerId.value}' AND account_id IN ('${usdExpense.value}', '${usdCash.value}')")
            val catalogWithoutUsd = catalogOf(standardAccounts())
            val result = adapter.readReportSnapshot(reportRequest(version, catalogWithoutUsd))
            assertEquals(P806SnapshotFailure.InvalidState, (assertIs<P806SnapshotResult.Failed>(result)).failure)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun reportRequest(
        version: Long,
        catalog: LedgerCatalog,
    ): com.unifiedledger.application.P806ReportRequest =
        com.unifiedledger.application.P806ReportRequest(
            ledgerId = ledgerId,
            catalog = catalog,
            period = P806Period(P806PeriodKind.CUSTOM, customStart = kotlinx.datetime.LocalDate(2026, 11, 1), customEnd = kotlinx.datetime.LocalDate(2026, 11, 30)),
            today = kotlinx.datetime.LocalDate(2026, 11, 17),
            futureFromEpochNanos = Instant.parse("2026-11-18T00:00:00+08:00").toEpochMilliseconds() * 1_000_000L,
            expectedCatalogVersion = version,
        )

    private fun usdAccount(): Account = Account(usdExpense, ledgerId, AccountKind.EXPENSE, usd, false, false, name = "USD 支出")

    private fun usdAssetAccount(): Account = Account(usdCash, ledgerId, AccountKind.ASSET, usd, true, true, name = "USD 现金")

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
            Category(salaryParent, ledgerId, parentId = null, postingAccountId = null, active = true, name = "收入"),
            Category(salaryLeaf, ledgerId, parentId = salaryParent, postingAccountId = salary, active = true, name = "工资"),
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
    ): LedgerCatalog {
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

    private inline fun withDatabase(block: (SqlDriver, LedgerDatabase, Long) -> Unit) {
        val path: Path = Files.createTempFile("p806-semantics-", ".db")
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
