package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806CalendarSnapshot
import com.unifiedledger.application.P806DrilldownRequest
import com.unifiedledger.application.P806DrilldownSnapshot
import com.unifiedledger.application.P806Period
import com.unifiedledger.application.P806PeriodKind
import com.unifiedledger.application.P806PeriodResolution
import com.unifiedledger.application.P806Periods
import com.unifiedledger.application.P806ReportRequest
import com.unifiedledger.application.P806ReportSnapshot
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * P8-06 batch 6 V-12 seed writer: the anonymous prepared ledger the `p806` profile of
 * `android-fontscale-sample.yml` pushes before launch, so the report region, the calendar day cell
 * and a day's detail rows are photographed at 1.3x.
 *
 * WHY A TEST. The output is a real file-backed SQLite database built through the REAL product
 * entry points, and `:ledger-data:jvmTest` is the only Gradle surface that owns
 * `ledger-data/src/jvmTest`. It writes ONLY when `-Dul.p806.fontscale.seed=<path>` names the
 * output; without the property it is a no-op, so the ordinary shard is unaffected.
 *
 * WHAT THE FILE CONTAINS (spec section 4 V-12): the real default catalog seed, then exactly TWO
 * confirmed manual EXPENSE transactions on ONE clock-relative local day (Asia/Shanghai), written
 * through the real claim-first manual-expense commit port and the real product factory. Both legs
 * are the default catalog's own accounts and the default leaf category. The amounts are the fixed
 * pair 12.34 and 5.00 CNY, so the day (and the current-month report) carries the exact signed
 * facts the sampler's gate re-derives from this file:
 *
 *  - net expense 17.34 CNY, ordinary income 0.00 CNY;
 *  - two contributing transactions, one day bucket with two transactions, every other day empty.
 *
 * Anonymity: no account is created, no note, merchant, tag or evidence row is written; the sample
 * carries only the default catalog's own names and the fixed amounts.
 *
 * THE OPEN/MIGRATION GATE. `LedgerDatabase.Schema.create` does not stamp `PRAGMA user_version`, and
 * the app's androidx open gates on it, so this writer stamps `Schema.version` (38) explicitly
 * (the [P801FontscaleSeedWriterTest] precedent).
 *
 * The post-write read-back reopens the FILE and drives the REAL P8-06 composite reads
 * (`readReportSnapshot`, `readCalendarSnapshot`, `readDrilldownSnapshot`), so the numbers the
 * device renders are proven here instead of assumed.
 */
class P806FontscaleSeedWriterTest {
    private val ledgerId = LedgerId(LEDGER_ID)

    @Test
    fun writesTheP806FontscaleSeedDatabase() {
        val target = System.getProperty(SEED_PATH_PROPERTY) ?: return
        val file = File(target).absoluteFile
        file.parentFile?.mkdirs()
        check(!file.exists() || file.delete()) { "cannot replace the previous seed at ${file.path}" }
        seed(file)
        assertSeedReadsBack(file)
    }

    /** Builds the file through the real product entry points (see the class note). */
    private fun seed(file: File) {
        JdbcSqliteDriver(url(file), foreignKeyProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = ${LedgerDatabase.Schema.version}", 0)
            val database = LedgerDatabase(driver)
            val catalogStore = SqlDelightCatalogStore(database, driver)
            assertIs<CatalogBootstrapResult.Seeded>(catalogStore.bootstrap(ledgerId, defaultCatalogSeed()))
            val catalog = assertNotNull(catalogStore.loadCurrent(ledgerId))
            val port = SqlDelightConfirmedManualExpenseCommitPort(database, driver)
            EXPENSES.forEachIndexed { index, minorUnits ->
                val amount = Money.ofMinor(minorUnits, CATALOG_MANAGED_CURRENCY)
                val snapshot =
                    ManualExpenseRequestSnapshot(
                        ledgerId = ledgerId,
                        amount = amount,
                        categoryId = CategoryId(DEFAULT_EXPENSE_LEAF_ID),
                        paymentAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                        occurredAt = SEED_INSTANT,
                        note = "",
                    )
                val ids =
                    ConfirmedManualExpenseCommitIds(
                        confirmationId = ConfirmationId("$TRANSACTION_ID_PREFIX-$index-confirmation"),
                        expenseIds =
                            AssetPaidOrdinaryExpenseIds(
                                transactionId = TransactionId(transactionId(index)),
                                versionId = TransactionVersionId("${transactionId(index)}-version"),
                                postingSetId = PostingSetId("${transactionId(index)}-posting-set"),
                                expensePostingId = PostingId("${transactionId(index)}-expense"),
                                paymentPostingId = PostingId("${transactionId(index)}-payment"),
                            ),
                    )
                val result =
                    port.commitOnce(
                        identity = ManualExpenseRequestIdentity(ledgerId, RequestId("$TRANSACTION_ID_PREFIX-$index-request")),
                        requestSnapshot = snapshot,
                        createdAt = SEED_INSTANT,
                    ) {
                        when (
                            val created =
                                createAssetPaidOrdinaryExpense(
                                    catalog = catalog,
                                    command =
                                        AssetPaidOrdinaryExpenseCommand(
                                            ledgerId = ledgerId,
                                            amount = amount,
                                            categoryId = snapshot.categoryId,
                                            paymentAccountId = snapshot.paymentAccountId,
                                            times = TransactionTimes.collapsed(snapshot.occurredAt),
                                            note = snapshot.note,
                                        ),
                                    ids = ids.expenseIds,
                                )
                        ) {
                            is DomainResult.Success ->
                                DomainResult.Success(
                                    ConfirmedManualExpenseCommit(
                                        confirmationId = ids.confirmationId,
                                        transaction = created.value,
                                    ),
                                )
                            is DomainResult.Failure -> created
                        }
                    }
                assertIs<ConfirmedManualExpenseResult.Created>(result)
            }
        }
    }

    /** Reopens the produced FILE and proves the exact P8-06 facts the device renders. */
    private fun assertSeedReadsBack(file: File) {
        JdbcSqliteDriver(url(file), foreignKeyProperties()).use { driver ->
            assertEquals(LedgerDatabase.Schema.version, userVersion(driver))
            val database = LedgerDatabase(driver)
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRequests().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countReceipts().executeAsOne())

            val adapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val authority = assertNotNull(SqlDelightCatalogStore(database, driver).load(ledgerId))
            val catalog = authority.catalog
            val today = SEED_INSTANT.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date
            val month = YearMonth(today.year, today.month)
            val period =
                assertIs<P806PeriodResolution.Resolved>(
                    P806Periods.resolve(P806Period(P806PeriodKind.MONTH), today, span = null),
                ).period
            assertEquals(month, YearMonth(period.startDate.year, period.startDate.month))

            // (a) The report surface: two contributing transactions whose exact expense total is
            // the fixed pair, on one day bucket of the resolved month.
            val report =
                assertIs<P806SnapshotResult.Success<P806ReportSnapshot>>(
                    adapter.readReportSnapshot(
                        P806ReportRequest(
                            ledgerId = ledgerId,
                            catalog = catalog,
                            period = P806Period(P806PeriodKind.MONTH),
                            today = today,
                            futureFromEpochNanos = P806Periods.nextLocalMidnightEpochNanos(today),
                            expectedCatalogVersion = authority.catalogVersion,
                        ),
                    ),
                ).snapshot
            assertEquals(period.window, report.window)
            assertEquals(2, report.transactionCount)
            assertEquals(0, report.futureTransactionCount)
            assertEquals(listOf(2), report.currencyCounts.map { it.transactionCount })
            assertEquals(CATALOG_MANAGED_CURRENCY, report.currencyCounts.single().currency)
            assertEquals(2, report.firstPage.rows.size)
            assertEquals(2, report.detailCount)
            val expenseTotal =
                assertNotNull(
                    report.postingTotals.firstOrNull { it.accountId == AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID) },
                )
            assertEquals(EXPECTED_TOTAL_MINOR, expenseTotal.positiveMinorUnits)
            assertEquals(0L, expenseTotal.refundMinorUnits)
            assertEquals(EXPECTED_TOTAL_MINOR, expenseTotal.totalMinorUnits)

            // (b) The calendar surface: the seeded local day is the ONE non-empty bucket.
            val calendar =
                assertIs<P806SnapshotResult.Success<P806CalendarSnapshot>>(
                    adapter.readCalendarSnapshot(
                        P806CalendarRequest(
                            ledgerId = ledgerId,
                            catalog = catalog,
                            month = month,
                            expectedCatalogVersion = authority.catalogVersion,
                        ),
                    ),
                ).snapshot
            assertEquals(month, calendar.month)
            val seededBucket = assertNotNull(calendar.days.firstOrNull { it.date == today })
            assertEquals(2, seededBucket.transactionCount)
            assertEquals(
                EXPECTED_TOTAL_MINOR,
                assertNotNull(
                    seededBucket.postingTotals.firstOrNull { it.accountId == AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID) },
                ).positiveMinorUnits,
            )
            assertEquals(1, calendar.days.count { it.transactionCount > 0 })

            // (c) The drilldown surface the day cell opens: the day's own half-open window carries
            // exactly the two rows and its exact count.
            val drilldown =
                assertIs<P806SnapshotResult.Success<P806DrilldownSnapshot>>(
                    adapter.readDrilldownSnapshot(
                        P806DrilldownRequest(
                            ledgerId = ledgerId,
                            window = seededBucket.window,
                            expectedCatalogVersion = authority.catalogVersion,
                        ),
                    ),
                ).snapshot
            assertEquals(2, drilldown.totalCount)
            assertEquals(2, drilldown.page.rows.size)
        }
    }

    private fun url(file: File): String = "jdbc:sqlite:" + file.absolutePath.replace('\\', '/')

    /** The header version androidx `SQLiteOpenHelper` gates the app's open on. */
    private fun userVersion(driver: JdbcSqliteDriver): Long? {
        val result =
            driver.executeQuery(
                null,
                "PRAGMA user_version",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                0,
                null,
            )
        return result.value
    }

    private fun foreignKeyProperties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

    private companion object {
        /** The system property the workflow sets; absent in every ordinary test run. */
        const val SEED_PATH_PROPERTY = "ul.p806.fontscale.seed"

        /** `android-app/.../App.kt` `PRODUCT_LEDGER_ID` — the app bootstraps this one identity. */
        const val LEDGER_ID = "ledger-local-test"

        const val TRANSACTION_ID_PREFIX = "p806-fontscale-expense"
        const val EXPENSES_1_MINOR = 1_234L
        const val EXPENSES_2_MINOR = 500L
        const val EXPECTED_TOTAL_MINOR = EXPENSES_1_MINOR + EXPENSES_2_MINOR

        /** The two fixed amounts of the anonymous sample, in ledger order. */
        val EXPENSES = longArrayOf(EXPENSES_1_MINOR, EXPENSES_2_MINOR)

        private fun transactionId(index: Int): String = "$TRANSACTION_ID_PREFIX-$index"

        /**
         * Clock-relative (D7-9 precedent): both rows share one instant, so the sample never rots
         * out of the report's default current month or the calendar's current month.
         */
        val SEED_INSTANT: Instant = Clock.System.now()
    }
}
