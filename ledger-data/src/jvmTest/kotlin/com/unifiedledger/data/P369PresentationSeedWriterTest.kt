package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CategoryShareInput
import com.unifiedledger.application.CategoryShareProjection
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedManualTransferCommitIds
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerFlowPageResult
import com.unifiedledger.application.LedgerFlowTotalResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P806CalendarRequest
import com.unifiedledger.application.P806CalendarSnapshot
import com.unifiedledger.application.P806SnapshotResult
import com.unifiedledger.application.QueryLedgerFlowPage
import com.unifiedledger.application.QueryMonthlyActivity
import com.unifiedledger.application.QueryTransactionDetail
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.projectCategoryShares
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * D-369 E1 presentation seed writer: the prepared ledger database the `android-presentation-
 * evidence.yml` sweep pushes into the app's stable storage before launch, and that the
 * `presentation` profile of `android-fontscale-sample.yml` can push for a single combination.
 *
 * WHY A TEST. The output is a real file-backed SQLite database built through the REAL product
 * entry points, and `:ledger-data:jvmTest` is the only Gradle surface that owns
 * `ledger-data/src/jvmTest`. It writes ONLY when `-Dul.presentation.fontscale.seed=<path>` names
 * the output; without the property it is a no-op, so the ordinary CI shard is unaffected.
 *
 * WHY A DEDICATED SEED (plan decision D1). The P8-01 seed carries one CNY transfer plus one CNY
 * expense over the default single-leaf catalog, so multi-category badges, a second currency and a
 * cross-day reconciliation queue are physically unreachable on it. This writer is independent and
 * leaves P8-01/P806 byte-for-byte alone; the evidence registry records it as the fixture source.
 *
 * WHAT THE FILE CONTAINS (plan section 2.1). One catalog seed (D3) with four user-owned ASSET
 * accounts — the default payment account (`asset-payment-local`, CNY), `p369-recon-asset` (对账样本
 * 账户, CNY), `p369-usd-asset` (美元样本账户, USD) and `p369-window-asset` (跨日界样本账户, CNY) —
 * one hidden EXPENSE posting account per active leaf, and the four-group category tree
 * 餐饮{早餐,午餐} / 交通{早餐} / 日用品{零食,咖啡} / 差旅{机票}. The two same-named 早餐 leaves live
 * under different parents, which is exactly the shape the Android home badge's path-aware
 * disambiguation addresses.
 *
 * Six transactions (15 postings) then exercise the presentation scenarios:
 *  - 1 ordinary CNY expense (8.88, 午餐样本支出) through the real claim-first manual-expense port
 *    and the real product factory;
 *  - 2 a 12.34 CNY transfer into `p369-recon-asset` (对账样本转账) through the real claim-first
 *    transfer port and [ManualTransferTransactionFactory];
 *  - 3 the ONE synthetic accounting row this plan allows (D2): a four-leg 40.00 CNY expense
 *    (多分类样本支出) written straight to PostingSet/Transaction/TransactionVersion/
 *    TransactionCurrentVersion/Posting because the product factory never writes a multi-category
 *    posting set. Its legs 12.34/10.00/8.00/9.66 are balanced by one -40.00 payment leg, and no
 *    request/receipt row is written. It is the ONLY row in this seed that does not ride a real
 *    product command;
 *  - 4 a 100.00 USD expense (美元样本支出) through the same real expense port, which admits it only
 *    because both sides are USD;
 *  - 5 and 6 two fixed-instant 2020 transfers (5.00/6.00 CNY, 跨日界样本A/B) into `p369-window-
 *    asset`, at 2020-01-15T15:30:00Z and 2020-01-15T16:30:00Z. They only enter the reconciliation
 *    queue, which has no date window, so a fixed instant keeps the seed reusable across months.
 *
 * THE OPEN/MIGRATION GATE. `LedgerDatabase.Schema.create` does not stamp `PRAGMA user_version`,
 * and the app's androidx open gates on it, so the writer stamps `LedgerDatabase.Schema.version`
 * explicitly (the [P801FontscaleSeedWriterTest] precedent).
 *
 * The post-write read-back reopens the FILE and drives the REAL reads the presentation screens
 * depend on (plan section 2.1's seven assertions), so what the device will render is proven here
 * instead of assumed.
 */
class P369PresentationSeedWriterTest {
    private val ledgerId = LedgerId(LEDGER_ID)

    @Test
    fun writesThePresentationSeedDatabase() {
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
            // D3: the catalog is a seed, not a command sequence, because no product command can
            // create a non-CNY account (PRD fixes the managed currency to CNY) and the USD scenario
            // needs one. The hidden EXPENSE posting accounts ride the same seed; every one is a
            // same-ledger, non-owned, non-real account of the leaf's kind.
            assertIs<CatalogBootstrapResult.Seeded>(catalogStore.bootstrap(ledgerId, presentationCatalogSeed()))
            val catalog = assertNotNull(catalogStore.loadCurrent(ledgerId))

            writeOrdinaryExpense(database, driver, catalog, ORDINARY_EXPENSE)
            writeTransfer(
                database = database,
                driver = driver,
                catalog = catalog,
                transactionId = TRANSFER_TRANSACTION_ID,
                sourceAccountId = DEFAULT_MANAGEABLE_ACCOUNT_ID,
                destinationAccountId = RECON_ACCOUNT_ID,
                amountMinor = TRANSFER_MINOR,
                note = TRANSFER_NOTE,
                occurredAt = SEED_NOW - 1.days,
                requestSuffix = "transfer",
            )
            writeMultiCategoryExpense(database)
            writeOrdinaryExpense(database, driver, catalog, USD_EXPENSE)
            writeTransfer(
                database = database,
                driver = driver,
                catalog = catalog,
                transactionId = CROSS_DAY_A_TRANSACTION_ID,
                sourceAccountId = DEFAULT_MANAGEABLE_ACCOUNT_ID,
                destinationAccountId = WINDOW_ACCOUNT_ID,
                amountMinor = CROSS_DAY_A_MINOR,
                note = CROSS_DAY_A_NOTE,
                occurredAt = CROSS_DAY_A_INSTANT,
                requestSuffix = "cross-day-a",
            )
            writeTransfer(
                database = database,
                driver = driver,
                catalog = catalog,
                transactionId = CROSS_DAY_B_TRANSACTION_ID,
                sourceAccountId = DEFAULT_MANAGEABLE_ACCOUNT_ID,
                destinationAccountId = WINDOW_ACCOUNT_ID,
                amountMinor = CROSS_DAY_B_MINOR,
                note = CROSS_DAY_B_NOTE,
                occurredAt = CROSS_DAY_B_INSTANT,
                requestSuffix = "cross-day-b",
            )
        }
    }

    /** One ordinary expense through the real claim-first manual-expense port and product factory. */
    private fun writeOrdinaryExpense(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        catalog: LedgerCatalog,
        spec: OrdinaryExpenseSpec,
    ) {
        val ids =
            ConfirmedManualExpenseCommitIds(
                confirmationId = ConfirmationId("$SEED_PREFIX-${spec.transactionId}-confirmation"),
                expenseIds =
                    AssetPaidOrdinaryExpenseIds(
                        transactionId = TransactionId(spec.transactionId),
                        versionId = TransactionVersionId("${spec.transactionId}-version"),
                        postingSetId = PostingSetId("${spec.transactionId}-posting-set"),
                        expensePostingId = PostingId("${spec.transactionId}-expense"),
                        paymentPostingId = PostingId("${spec.transactionId}-payment"),
                    ),
            )
        val snapshot =
            ManualExpenseRequestSnapshot(
                ledgerId = ledgerId,
                amount = Money.ofMinor(spec.amountMinor, spec.currency),
                categoryId = CategoryId(spec.categoryId),
                paymentAccountId = AccountId(spec.paymentAccountId),
                occurredAt = spec.occurredAt,
                note = spec.note,
            )
        val result =
            SqlDelightConfirmedManualExpenseCommitPort(database, driver).commitOnce(
                identity =
                    ManualExpenseRequestIdentity(
                        ledgerId,
                        RequestId("$SEED_PREFIX-${spec.transactionId}-request"),
                    ),
                requestSnapshot = snapshot,
                createdAt = spec.occurredAt + 5.minutes,
            ) {
                when (
                    val created =
                        createAssetPaidOrdinaryExpense(
                            catalog = catalog,
                            command =
                                AssetPaidOrdinaryExpenseCommand(
                                    ledgerId = ledgerId,
                                    amount = snapshot.amount,
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

    /** One transfer through the real claim-first manual-transfer port and product factory. */
    private fun writeTransfer(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        catalog: LedgerCatalog,
        transactionId: String,
        sourceAccountId: String,
        destinationAccountId: String,
        amountMinor: Long,
        note: String,
        occurredAt: Instant,
        requestSuffix: String,
    ) {
        val currency = CATALOG_MANAGED_CURRENCY
        val snapshot =
            ManualTransferRequestSnapshot(
                ledgerId = ledgerId,
                sourceAccountId = AccountId(sourceAccountId),
                destinationAccountId = AccountId(destinationAccountId),
                destinationCredit = Money.ofMinor(amountMinor, currency),
                fee = Money.ofMinor(0L, currency),
                feeCategoryId = null,
                occurredAt = occurredAt,
                note = note,
            )
        val ids =
            AccountTransferIds(
                transactionId = TransactionId(transactionId),
                versionId = TransactionVersionId("$transactionId-version"),
                postingSetId = PostingSetId("$transactionId-posting-set"),
                sourcePostingId = PostingId("$transactionId-out"),
                destinationPostingId = PostingId("$transactionId-in"),
                // Unused by the fee=0 (two-leg) factory; the shape still requires one id.
                feePostingId = PostingId("$transactionId-fee"),
            )
        val result =
            SqlDelightConfirmedManualTransferCommitPort(database, driver).commitOnce(
                ManualTransferRequestIdentity(
                    ledgerId,
                    RequestId("$SEED_PREFIX-$requestSuffix-request"),
                ),
                snapshot,
                occurredAt + 5.minutes,
            ) {
                ManualTransferTransactionFactory(catalog).create(
                    snapshot,
                    ConfirmedManualTransferCommitIds(
                        confirmationId = ConfirmationId("$SEED_PREFIX-$requestSuffix-confirmation"),
                        transferIds = ids,
                    ),
                )
            }
        assertIs<ConfirmedManualTransferResult.Created>(result)
    }

    /**
     * D2's one synthetic accounting row: a four-category EXPENSE with a balanced five-leg posting
     * set. The generated INSERT statements are the same ones the real commit ports use, so the
     * numeric statistics projection and the effective-state view apply unchanged; only the
     * product's single-category factory is bypassed, and no request/receipt is written.
     */
    private fun writeMultiCategoryExpense(database: LedgerDatabase) {
        val transactionId = MULTI_CATEGORY_TRANSACTION_ID
        val postingSetId = "$transactionId-posting-set"
        val versionId = "$transactionId-version"
        val legs =
            listOf(
                SyntheticLeg("$transactionId-food-breakfast", HIDDEN_FOOD_BREAKFAST_ACCOUNT_ID, MULTI_CATEGORY_LEG_1_MINOR),
                SyntheticLeg("$transactionId-food-lunch", HIDDEN_FOOD_LUNCH_ACCOUNT_ID, MULTI_CATEGORY_LEG_2_MINOR),
                SyntheticLeg("$transactionId-transport-breakfast", HIDDEN_TRANSPORT_BREAKFAST_ACCOUNT_ID, MULTI_CATEGORY_LEG_3_MINOR),
                SyntheticLeg("$transactionId-daily-snack", HIDDEN_DAILY_SNACK_ACCOUNT_ID, MULTI_CATEGORY_LEG_4_MINOR),
                SyntheticLeg("$transactionId-payment", DEFAULT_MANAGEABLE_ACCOUNT_ID, -MULTI_CATEGORY_TOTAL_MINOR),
            )
        check(legs.sumOf { it.amountMinor } == 0L) { "the synthetic posting set must balance" }
        database.transaction {
            database.ledgerQueries.insertPostingSet(postingSetId, ledgerId.value)
            database.ledgerQueries.insertTransaction(
                transactionId,
                ledgerId.value,
                TransactionKind.EXPENSE.name,
            )
            database.ledgerQueries.insertTransactionVersion(
                version_id = versionId,
                transaction_id = transactionId,
                ledger_id = ledgerId.value,
                version_number = 1L,
                posting_set_id = postingSetId,
                occurred_at = MULTI_CATEGORY_INSTANT.toString(),
                statistics_at = MULTI_CATEGORY_INSTANT.toString(),
                effective_at = MULTI_CATEGORY_INSTANT.toString(),
                note = MULTI_CATEGORY_NOTE,
            )
            database.ledgerQueries.insertTransactionCurrentVersion(
                transactionId,
                ledgerId.value,
                versionId,
            )
            legs.forEachIndexed { index, leg ->
                database.ledgerQueries.insertPosting(
                    posting_id = leg.postingId,
                    posting_set_id = postingSetId,
                    ledger_id = ledgerId.value,
                    posting_index = index.toLong(),
                    account_id = leg.accountId,
                    amount_minor = leg.amountMinor,
                    currency_code = CATALOG_MANAGED_CURRENCY.code,
                    currency_precision = CATALOG_MANAGED_CURRENCY.precision.toLong(),
                )
            }
        }
    }

    /** Reopens the produced FILE and proves the seven reads plan section 2.1 requires. */
    private fun assertSeedReadsBack(file: File) {
        JdbcSqliteDriver(url(file), foreignKeyProperties()).use { driver ->
            assertEquals(LedgerDatabase.Schema.version, userVersion(driver))
            val database = LedgerDatabase(driver)
            val authority = assertNotNull(SqlDelightCatalogStore(database, driver).load(ledgerId))
            val catalog = authority.catalog
            val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)

            // (1) Six transactions. (2) Their fifteen postings.
            assertEquals(6L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(15L, database.ledgerQueries.countPostings().executeAsOne())
            // The two ordinary expenses went through the real claim-first manual-expense port:
            // exactly their two claim/receipt pairs exist, and the synthetic row wrote none. (The
            // transfers have their own request tables; their port results are asserted in seed().)
            assertEquals(2L, database.ledgerQueries.countRequests().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countReceipts().executeAsOne())

            // (3) The home flow is the six-row list over the SAME real page read the device uses.
            val homeFlow = QueryLedgerFlowPage(readAdapter, ledgerId)
            val homePage = assertIs<LedgerFlowPageResult.Success>(homeFlow.firstPage()).page
            assertEquals(6, homePage.rows.size)
            assertEquals(LedgerFlowTotalResult.Success(6), homeFlow.total())

            // (4) The current month's expense ranking is exactly one level-1 row at 100.0%: only the
            // 8.88 ordinary expense is in this month (the transfers are not ordinary expenses and the
            // other two rows are last month's).
            val currentMonth = MonthlyBuckets.currentMonth(LedgerClock { Clock.System.now() })
            val activity =
                assertIs<MonthlyActivityResult.Success>(
                    QueryMonthlyActivity(
                        readAdapter,
                        ledgerId,
                        catalog,
                        LedgerClock { Clock.System.now() },
                    ).query(currentMonth),
                ).activity
            assertEquals(1, activity.expenseCategories.size)
            val rankingNode = activity.expenseCategories.single()
            assertEquals("餐饮", rankingNode.categoryName)
            val rankingTotal = rankingNode.totals.single()
            assertEquals(CATALOG_MANAGED_CURRENCY, rankingTotal.currency)
            assertEquals(ORDINARY_EXPENSE.amountMinor, rankingTotal.positiveMinorUnits)
            assertEquals(0L, rankingTotal.refundMinorUnits)
            val ranking =
                assertIs<CategoryShareProjection.Chart>(
                    projectCategoryShares(
                        listOf(
                            CategoryShareInput(
                                rankingNode.categoryId,
                                rankingNode.categoryName,
                                rankingTotal.positiveMinorUnits + rankingTotal.refundMinorUnits,
                            ),
                        ),
                    ),
                )
            assertEquals("100.0%", ranking.rows.single().percentText())

            // (5) The cross-day reconciliation queue holds exactly the two 2020 transfers, whose
            // local (Shanghai) dates straddle the day boundary: 01-15 23:30 and 01-16 00:30.
            val reconciliation =
                SqlDelightP801AccountReconciliationReadAdapter(
                    database,
                    SqlDelightEvidenceProjectionStore(database, driver),
                )
            val windowQueue =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    reconciliation.loadAccountReconciliationPage(
                        ledgerId,
                        AccountId(WINDOW_ACCOUNT_ID),
                        after = null,
                        pageSize = 10,
                    ),
                ).page
            assertEquals(2, windowQueue.rows.size)
            assertEquals(
                setOf(LocalDate(2020, 1, 15), LocalDate(2020, 1, 16)),
                windowQueue.rows.map { row -> row.occurredAt.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date }.toSet(),
            )
            assertEquals(
                setOf(CROSS_DAY_A_NOTE, CROSS_DAY_B_NOTE),
                windowQueue.rows.map { row -> row.note }.toSet(),
            )
            assertEquals(
                setOf(P408ReconciliationStatus.PENDING),
                windowQueue.rows.map { row -> row.status }.toSet(),
            )

            // (6) The current month's calendar carries exactly one active day: the 8.88 expense's
            // local statistics day. The transfers are not ordinary contributions, so the 12.34
            // transfer's own day never counts.
            val calendar =
                assertIs<P806SnapshotResult.Success<P806CalendarSnapshot>>(
                    readAdapter.readCalendarSnapshot(
                        P806CalendarRequest(
                            ledgerId = ledgerId,
                            catalog = catalog,
                            month = currentMonth,
                            expectedCatalogVersion = authority.catalogVersion,
                        ),
                    ),
                ).snapshot
            val activeDays = calendar.days.filter { day -> day.transactionCount > 0 }
            assertEquals(1, activeDays.size)
            assertEquals(
                ORDINARY_EXPENSE.occurredAt.toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE).date,
                activeDays.single().date,
            )

            // (7) The multi-category detail is readable: five legs and four DISTINCT categories. The
            // duplicate 早餐 name means names alone are not a category identity, so the distinctness
            // is proven through the catalog's posting-account association.
            val detail =
                assertIs<TransactionDetailResult.Success>(
                    QueryTransactionDetail(readAdapter, ledgerId, catalog).query(TransactionId(MULTI_CATEGORY_TRANSACTION_ID)),
                ).detail
            assertEquals(5, detail.legs.size)
            val categoryByPostingAccount =
                catalog.categories
                    .mapNotNull { category -> category.postingAccountId?.let { account -> account to category.id } }
                    .toMap()
            val legCategories = detail.legs.mapNotNull { leg -> categoryByPostingAccount[leg.accountId] }.toSet()
            assertEquals(
                setOf(
                    CategoryId(FOOD_BREAKFAST_CATEGORY_ID),
                    CategoryId(FOOD_LUNCH_CATEGORY_ID),
                    CategoryId(TRANSPORT_BREAKFAST_CATEGORY_ID),
                    CategoryId(DAILY_SNACK_CATEGORY_ID),
                ),
                legCategories,
            )
            assertEquals(
                MULTI_CATEGORY_TOTAL_MINOR,
                detail.legs.sumOf { leg -> leg.amount.minorUnits },
            )
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

    /**
     * The one catalog seed (D3): every asset account and every leaf's hidden EXPENSE posting
     * account. The tree is the plan's four groups; both 早餐 leaves intentionally share the name.
     */
    private fun presentationCatalogSeed(): CatalogSeed {
        val hiddenAccounts =
            listOf(
                hiddenLeafAccount(HIDDEN_FOOD_BREAKFAST_ACCOUNT_ID, "早餐", CATALOG_MANAGED_CURRENCY),
                hiddenLeafAccount(HIDDEN_FOOD_LUNCH_ACCOUNT_ID, "午餐", CATALOG_MANAGED_CURRENCY),
                hiddenLeafAccount(HIDDEN_TRANSPORT_BREAKFAST_ACCOUNT_ID, "早餐", CATALOG_MANAGED_CURRENCY),
                hiddenLeafAccount(HIDDEN_DAILY_SNACK_ACCOUNT_ID, "零食", CATALOG_MANAGED_CURRENCY),
                hiddenLeafAccount(HIDDEN_DAILY_COFFEE_ACCOUNT_ID, "咖啡", CATALOG_MANAGED_CURRENCY),
                hiddenLeafAccount(HIDDEN_TRAVEL_FLIGHT_ACCOUNT_ID, "机票", USD_CURRENCY),
            )
        return CatalogSeed(
            accounts =
                listOf(
                    CatalogSeedAccount(
                        accountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                        name = DEFAULT_PAYMENT_ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        currency = CATALOG_MANAGED_CURRENCY,
                        ownedByUser = true,
                        realAccount = true,
                        hidden = false,
                    ),
                    CatalogSeedAccount(
                        accountId = AccountId(RECON_ACCOUNT_ID),
                        name = RECON_ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        currency = CATALOG_MANAGED_CURRENCY,
                        ownedByUser = true,
                        realAccount = true,
                        hidden = false,
                    ),
                    CatalogSeedAccount(
                        accountId = AccountId(USD_ACCOUNT_ID),
                        name = USD_ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        currency = USD_CURRENCY,
                        ownedByUser = true,
                        realAccount = true,
                        hidden = false,
                    ),
                    CatalogSeedAccount(
                        accountId = AccountId(WINDOW_ACCOUNT_ID),
                        name = WINDOW_ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        currency = CATALOG_MANAGED_CURRENCY,
                        ownedByUser = true,
                        realAccount = true,
                        hidden = false,
                    ),
                ) + hiddenAccounts,
            categories =
                listOf(
                    seedGroup(FOOD_GROUP_CATEGORY_ID, "餐饮"),
                    seedLeaf(FOOD_BREAKFAST_CATEGORY_ID, "早餐", FOOD_GROUP_CATEGORY_ID, HIDDEN_FOOD_BREAKFAST_ACCOUNT_ID),
                    seedLeaf(FOOD_LUNCH_CATEGORY_ID, "午餐", FOOD_GROUP_CATEGORY_ID, HIDDEN_FOOD_LUNCH_ACCOUNT_ID),
                    seedGroup(TRANSPORT_GROUP_CATEGORY_ID, "交通"),
                    seedLeaf(TRANSPORT_BREAKFAST_CATEGORY_ID, "早餐", TRANSPORT_GROUP_CATEGORY_ID, HIDDEN_TRANSPORT_BREAKFAST_ACCOUNT_ID),
                    seedGroup(DAILY_GROUP_CATEGORY_ID, "日用品"),
                    seedLeaf(DAILY_SNACK_CATEGORY_ID, "零食", DAILY_GROUP_CATEGORY_ID, HIDDEN_DAILY_SNACK_ACCOUNT_ID),
                    seedLeaf(DAILY_COFFEE_CATEGORY_ID, "咖啡", DAILY_GROUP_CATEGORY_ID, HIDDEN_DAILY_COFFEE_ACCOUNT_ID),
                    seedGroup(TRAVEL_GROUP_CATEGORY_ID, "差旅"),
                    seedLeaf(TRAVEL_FLIGHT_CATEGORY_ID, "机票", TRAVEL_GROUP_CATEGORY_ID, HIDDEN_TRAVEL_FLIGHT_ACCOUNT_ID),
                ),
        )
    }

    private fun hiddenLeafAccount(
        accountId: String,
        name: String,
        currency: CurrencyUnit,
    ): CatalogSeedAccount =
        CatalogSeedAccount(
            accountId = AccountId(accountId),
            name = name,
            kind = AccountKind.EXPENSE,
            currency = currency,
            ownedByUser = false,
            realAccount = false,
            hidden = true,
        )

    private fun seedGroup(
        categoryId: String,
        name: String,
    ): CatalogSeedCategory =
        CatalogSeedCategory(
            categoryId = CategoryId(categoryId),
            name = name,
            kind = CategoryKind.EXPENSE,
            parentId = null,
            postingAccountId = null,
        )

    private fun seedLeaf(
        categoryId: String,
        name: String,
        parentId: String,
        postingAccountId: String,
    ): CatalogSeedCategory =
        CatalogSeedCategory(
            categoryId = CategoryId(categoryId),
            name = name,
            kind = CategoryKind.EXPENSE,
            parentId = CategoryId(parentId),
            postingAccountId = AccountId(postingAccountId),
        )

    private data class OrdinaryExpenseSpec(
        val transactionId: String,
        val note: String,
        val amountMinor: Long,
        val currency: CurrencyUnit,
        val categoryId: String,
        val paymentAccountId: String,
        val occurredAt: Instant,
    )

    private data class SyntheticLeg(
        val postingId: String,
        val accountId: String,
        val amountMinor: Long,
    )

    private companion object {
        /** The system property the workflow sets; absent in every ordinary test run. */
        const val SEED_PATH_PROPERTY = "ul.presentation.fontscale.seed"

        /** `android-app/.../App.kt` `PRODUCT_LEDGER_ID` — the app bootstraps this one identity. */
        const val LEDGER_ID = "ledger-local-test"

        const val SEED_PREFIX = "p369-presentation-seed"

        const val DEFAULT_PAYMENT_ACCOUNT_NAME = "默认支付账户"
        const val RECON_ACCOUNT_ID = "p369-recon-asset"
        const val RECON_ACCOUNT_NAME = "对账样本账户"
        const val USD_ACCOUNT_ID = "p369-usd-asset"
        const val USD_ACCOUNT_NAME = "美元样本账户"
        const val WINDOW_ACCOUNT_ID = "p369-window-asset"
        const val WINDOW_ACCOUNT_NAME = "跨日界样本账户"

        const val FOOD_GROUP_CATEGORY_ID = "p369-food"
        const val FOOD_BREAKFAST_CATEGORY_ID = "p369-food-breakfast"
        const val FOOD_LUNCH_CATEGORY_ID = "p369-food-lunch"
        const val TRANSPORT_GROUP_CATEGORY_ID = "p369-transport"
        const val TRANSPORT_BREAKFAST_CATEGORY_ID = "p369-transport-breakfast"
        const val DAILY_GROUP_CATEGORY_ID = "p369-daily"
        const val DAILY_SNACK_CATEGORY_ID = "p369-daily-snack"
        const val DAILY_COFFEE_CATEGORY_ID = "p369-daily-coffee"
        const val TRAVEL_GROUP_CATEGORY_ID = "p369-travel"
        const val TRAVEL_FLIGHT_CATEGORY_ID = "p369-travel-flight"

        const val HIDDEN_FOOD_BREAKFAST_ACCOUNT_ID = "p369-hidden-food-breakfast"
        const val HIDDEN_FOOD_LUNCH_ACCOUNT_ID = "p369-hidden-food-lunch"
        const val HIDDEN_TRANSPORT_BREAKFAST_ACCOUNT_ID = "p369-hidden-transport-breakfast"
        const val HIDDEN_DAILY_SNACK_ACCOUNT_ID = "p369-hidden-daily-snack"
        const val HIDDEN_DAILY_COFFEE_ACCOUNT_ID = "p369-hidden-daily-coffee"
        const val HIDDEN_TRAVEL_FLIGHT_ACCOUNT_ID = "p369-hidden-travel-flight"

        const val ORDINARY_EXPENSE_TRANSACTION_ID = "p369-ordinary-expense"
        const val ORDINARY_EXPENSE_NOTE = "午餐样本支出"
        const val ORDINARY_EXPENSE_MINOR = 888L

        const val TRANSFER_TRANSACTION_ID = "p369-transfer"
        const val TRANSFER_NOTE = "对账样本转账"
        const val TRANSFER_MINOR = 1_234L

        const val MULTI_CATEGORY_TRANSACTION_ID = "p369-multi-category"
        const val MULTI_CATEGORY_NOTE = "多分类样本支出"
        const val MULTI_CATEGORY_LEG_1_MINOR = 1_234L
        const val MULTI_CATEGORY_LEG_2_MINOR = 1_000L
        const val MULTI_CATEGORY_LEG_3_MINOR = 800L
        const val MULTI_CATEGORY_LEG_4_MINOR = 966L
        const val MULTI_CATEGORY_TOTAL_MINOR =
            MULTI_CATEGORY_LEG_1_MINOR + MULTI_CATEGORY_LEG_2_MINOR +
                MULTI_CATEGORY_LEG_3_MINOR + MULTI_CATEGORY_LEG_4_MINOR

        const val USD_EXPENSE_TRANSACTION_ID = "p369-usd-expense"
        const val USD_EXPENSE_NOTE = "美元样本支出"
        const val USD_EXPENSE_MINOR = 10_000L

        const val CROSS_DAY_A_TRANSACTION_ID = "p369-cross-day-a"
        const val CROSS_DAY_A_NOTE = "跨日界样本A"
        const val CROSS_DAY_A_MINOR = 500L
        val CROSS_DAY_A_INSTANT: Instant = Instant.parse("2020-01-15T15:30:00Z")

        const val CROSS_DAY_B_TRANSACTION_ID = "p369-cross-day-b"
        const val CROSS_DAY_B_NOTE = "跨日界样本B"
        const val CROSS_DAY_B_MINOR = 600L
        val CROSS_DAY_B_INSTANT: Instant = Instant.parse("2020-01-15T16:30:00Z")

        val USD_CURRENCY: CurrencyUnit = CurrencyUnit("USD", 2)

        /**
         * The clock-relative "now" the ordinary expense uses; the third-month boundaries below are
         * derived from it, so the seed never rots out of the calendar's current month.
         */
        val SEED_NOW: Instant = Clock.System.now()

        /**
         * Last month's 15th, 09:00/10:00 Asia/Shanghai. Both rows live in the same month as each
         * other but outside the current month, so the current month keeps its single active day and
         * its single ranking row while the multi-category and USD detail screens still read.
         */
        val PREVIOUS_MONTH_15: LocalDate =
            run {
                val currentMonthStart =
                    MonthlyBuckets
                        .monthStart(MonthlyBuckets.currentMonth(LedgerClock { SEED_NOW }))
                        .toLocalDateTime(MonthlyBuckets.REPORT_TIME_ZONE)
                        .date
                // kotlinx.datetime's LocalDate has no java.time-style withDayOfMonth; it is built
                // from its own year/month/day fields (the LocalDate(year, Month, day) constructor
                // precedent is P806CalendarRead.kt:193).
                val lastDayOfPreviousMonth = currentMonthStart.minus(1, DateTimeUnit.DAY)
                LocalDate(lastDayOfPreviousMonth.year, lastDayOfPreviousMonth.month, 15)
            }

        val USD_EXPENSE_OCCURRED_AT: Instant =
            LocalDateTime(PREVIOUS_MONTH_15.year, PREVIOUS_MONTH_15.month.ordinal + 1, 15, 9, 0)
                .toInstant(MonthlyBuckets.REPORT_TIME_ZONE)

        val MULTI_CATEGORY_INSTANT: Instant =
            LocalDateTime(PREVIOUS_MONTH_15.year, PREVIOUS_MONTH_15.month.ordinal + 1, 15, 10, 0)
                .toInstant(MonthlyBuckets.REPORT_TIME_ZONE)

        val ORDINARY_EXPENSE =
            OrdinaryExpenseSpec(
                transactionId = ORDINARY_EXPENSE_TRANSACTION_ID,
                note = ORDINARY_EXPENSE_NOTE,
                amountMinor = ORDINARY_EXPENSE_MINOR,
                currency = CATALOG_MANAGED_CURRENCY,
                categoryId = FOOD_BREAKFAST_CATEGORY_ID,
                paymentAccountId = DEFAULT_MANAGEABLE_ACCOUNT_ID,
                occurredAt = SEED_NOW,
            )

        val USD_EXPENSE =
            OrdinaryExpenseSpec(
                transactionId = USD_EXPENSE_TRANSACTION_ID,
                note = USD_EXPENSE_NOTE,
                amountMinor = USD_EXPENSE_MINOR,
                currency = USD_CURRENCY,
                categoryId = TRAVEL_FLIGHT_CATEGORY_ID,
                paymentAccountId = USD_ACCOUNT_ID,
                occurredAt = USD_EXPENSE_OCCURRED_AT,
            )
    }
}
