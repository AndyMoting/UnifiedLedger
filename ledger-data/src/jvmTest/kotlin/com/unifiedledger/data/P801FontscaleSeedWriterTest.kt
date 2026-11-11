package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogEntityIdSource
import com.unifiedledger.application.CatalogEntityIds
import com.unifiedledger.application.CatalogManagementRequestIdSource
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualTransferCommitIds
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.LedgerFlowPageResult
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.QueryCatalogSnapshot
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryLedgerFlowPage
import com.unifiedledger.application.QueryTransactionDetail
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.SearchTimeWindow
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.resolveSearchTimeWindow
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * P8-01a i3b font-scale seed writer: the prepared v36 ledger database the `seeded` profile of
 * `android-fontscale-sample.yml` pushes into the app's stable storage before launch.
 *
 * WHY A TEST. The output is a real file-backed SQLite database built from the REAL product entry
 * points, and the only Gradle test surface that owns `ledger-data/src/jvmTest` is `:ledger-data:
 * jvmTest` — so the writer rides that task instead of a second build script. It writes ONLY when
 * `-Dul.fontscale.seed=<path>` names the output; without the property it is a no-op, so the
 * ordinary CI shard is unaffected. The workflow runs
 * `:ledger-data:jvmTest --tests 'com.unifiedledger.data.P801FontscaleSeedWriterTest'
 * -Dul.fontscale.seed=<path>` and then pushes the produced file.
 *
 * WHAT THE FILE CONTAINS (spec section 6's device read needs a non-empty ledger):
 * - the real default catalog seed through [SqlDelightCatalogStore.bootstrap];
 * - a second user-owned ASSET account through the real catalog management command
 *   ([ExecuteCatalogCommand.createAccount], `createManagedAccount` gives it owned_by_user=1 and
 *   real_account=1);
 * - one `ACCOUNT_TRANSFER` transaction with exactly two legs (one out of 默认支付账户, one into
 *   the seeded account), written through the real claim-first manual-transfer commit port and the
 *   real product transaction factory (fee = 0, so the domain's pure-principal two-leg factory is
 *   the one that runs). No `posting_reconciliation` row, no evidence, no link: every leg is
 *   `PENDING` by the read face's COALESCE default (V-7), which is exactly the queue the device
 *   sample must show.
 *
 * THE OPEN/MIGRATION GATE. The app opens a generation main file through
 * `AndroidSqliteDriver(schema = LedgerDatabase.Schema, …)`, i.e. androidx `SQLiteOpenHelper`, whose
 * version gate is `PRAGMA user_version`. `LedgerDatabase.Schema.create` does NOT write that stamp
 * (the same reason [P706ConfirmedReceiptsRoundtripTest] stamps it), so the writer stamps
 * `user_version = Schema.version` (36) explicitly; an unstamped file would be treated as version 0
 * and the app would run the whole migration chain over an already-current schema.
 *
 * The post-write read-back reopens the FILE through a second connection and drives the REAL
 * read adapters, so what the device will render is proven here rather than assumed: the
 * reconciliation queue (one PENDING leg per account, both from the same transaction, no active
 * link) AND the four reads the seeded tap path depends on — the home flow row's note, the
 * account-flow balance row, the manageable/asset projection, and the transaction detail read — plus
 * the seeded statistics instant's containment in the search surface's default window (D7-9).
 */
class P801FontscaleSeedWriterTest {
    private val ledgerId = LedgerId(LEDGER_ID)

    @Test
    fun writesTheFontscaleSeedDatabase() {
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

            val commands =
                ExecuteCatalogCommand(
                    commitPort = catalogStore,
                    requestIdSource = CatalogManagementRequestIdSource { CatalogRequestId("fontscale-seed-create-account") },
                    entityIdSource =
                        CatalogEntityIdSource {
                            CatalogEntityIds(
                                manageableAccountId = AccountId(SEED_ACCOUNT_ID),
                                postingAccountId = AccountId("fontscale-seed-expense-account"),
                                parentCategoryId = CategoryId("fontscale-seed-expense-group"),
                                childCategoryId = CategoryId("fontscale-seed-expense-child"),
                            )
                        },
                    categoryReferenceProbe = catalogStore,
                )
            val accountReceipt =
                assertIs<CatalogCommandResult.Accepted>(
                    commands.createAccount(
                        ledgerId = ledgerId,
                        name = SEED_ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        expectedCatalogVersion = 1L,
                    ),
                ).receipt
            assertEquals(AccountId(SEED_ACCOUNT_ID), accountReceipt.createdManageableAccountId)

            val catalog = assertNotNull(catalogStore.loadCurrent(ledgerId))
            val snapshot =
                ManualTransferRequestSnapshot(
                    ledgerId = ledgerId,
                    sourceAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                    destinationAccountId = AccountId(SEED_ACCOUNT_ID),
                    destinationCredit = Money.ofMinor(SEED_TRANSFER_MINOR, CATALOG_MANAGED_CURRENCY),
                    fee = Money.ofMinor(0L, CATALOG_MANAGED_CURRENCY),
                    feeCategoryId = null,
                    occurredAt = SEED_TRANSFER_INSTANT,
                    note = SEED_TRANSFER_NOTE,
                )
            val transferIds =
                AccountTransferIds(
                    transactionId = TransactionId(SEED_TRANSACTION_ID),
                    versionId = TransactionVersionId("$SEED_TRANSACTION_ID-version"),
                    postingSetId = PostingSetId("$SEED_TRANSACTION_ID-posting-set"),
                    sourcePostingId = PostingId("$SEED_TRANSACTION_ID-out"),
                    destinationPostingId = PostingId("$SEED_TRANSACTION_ID-in"),
                    // Unused by the fee=0 (two-leg) factory; the shape still requires one id.
                    feePostingId = PostingId("$SEED_TRANSACTION_ID-fee"),
                )
            val port = SqlDelightConfirmedManualTransferCommitPort(database, driver)
            val factory = ManualTransferTransactionFactory(catalog)
            val result =
                port.commitOnce(
                    ManualTransferRequestIdentity(ledgerId, RequestId("fontscale-seed-transfer-request")),
                    snapshot,
                    SEED_CREATED_AT,
                ) {
                    factory.create(
                        snapshot,
                        ConfirmedManualTransferCommitIds(
                            confirmationId = ConfirmationId("fontscale-seed-transfer-confirmation"),
                            transferIds = transferIds,
                        ),
                    )
                }
            assertIs<ConfirmedManualTransferResult.Created>(result)
        }
    }

    /** Reopens the produced FILE and proves the exact queue and tap-path reads the device renders. */
    private fun assertSeedReadsBack(file: File) {
        JdbcSqliteDriver(url(file), foreignKeyProperties()).use { driver ->
            assertEquals(LedgerDatabase.Schema.version, userVersion(driver))
            val database = LedgerDatabase(driver)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
            // PENDING means no reconciliation row at all, and no evidence/link may exist.
            assertEquals(0L, database.ledgerQueries.countP408PostingReconciliationRows().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countEvidenceLinkRows().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countImportEvidence().executeAsOne())
            // The default seed's two accounts (默认支付账户 + the hidden expense account) plus the
            // one created above.
            assertEquals(3L, database.ledgerQueries.countCatalogAccounts(ledgerId.value).executeAsOne())

            val adapter =
                SqlDelightP801AccountReconciliationReadAdapter(
                    database,
                    SqlDelightEvidenceProjectionStore(database, driver),
                )
            val destinationPage =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, AccountId(SEED_ACCOUNT_ID), after = null, pageSize = 10),
                ).page
            assertEquals(1, destinationPage.rows.size)
            val destinationLeg = destinationPage.rows.single()
            assertEquals(SEED_TRANSFER_MINOR, destinationLeg.amountMinor)
            assertEquals(SEED_TRANSFER_NOTE, destinationLeg.note)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, destinationLeg.transactionKind)
            assertEquals(P408ReconciliationStatus.PENDING, destinationLeg.status)
            assertEquals(false, destinationLeg.hasActiveEvidenceLink)

            val sourcePage =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(ledgerId, AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID), after = null, pageSize = 10),
                ).page
            assertEquals(1, sourcePage.rows.size)
            val sourceLeg = sourcePage.rows.single()
            assertEquals(-SEED_TRANSFER_MINOR, sourceLeg.amountMinor)
            assertEquals(destinationLeg.transactionId, sourceLeg.transactionId)
            assertEquals(P408ReconciliationStatus.PENDING, sourceLeg.status)

            val summary =
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, AccountId(SEED_ACCOUNT_ID)),
                ).totals
            assertEquals(1, summary.size)
            assertEquals(P408ReconciliationStatus.PENDING, summary.single().status)
            assertEquals(SEED_TRANSFER_MINOR, summary.single().totalMinorUnits)
            assertEquals(1, summary.single().rowCount)

            // F2: the four reads the seeded tap path depends on, asserted offline here so a drift
            // is a red test instead of a device run that photographs the wrong screen.
            val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val catalogStore = SqlDelightCatalogStore(database, driver)
            val catalog = assertNotNull(catalogStore.loadCurrent(ledgerId))

            // (a) The home flow row the detail step taps: the home screen's first flow page
            // (P503App.kt `queryLedgerFlowPage.firstPage()`) composes its content-description from
            // this note (P503LedgerViewPresentation.kt `，备注 $note`), so a missing note means the
            // seeded tap target never renders.
            val homePage =
                assertIs<LedgerFlowPageResult.Success>(
                    QueryLedgerFlowPage(readAdapter, ledgerId).firstPage(),
                ).page
            assertEquals(1, homePage.rows.size)
            val flowRow = homePage.rows.single()
            assertEquals(TransactionId(SEED_TRANSACTION_ID), flowRow.transactionId)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, flowRow.kind)
            assertEquals(SEED_TRANSFER_NOTE, flowRow.note)

            // (b) The account-flow header's balance row: selectAccountFlow returns silently when
            // the account has none (P503App.kt `balances.firstOrNull … ?: return`).
            val currentState =
                assertIs<LedgerCurrentStateResult.Success>(
                    QueryLedgerCurrentState(readAdapter, ledgerId, catalog).query(),
                ).state
            val seededBalance =
                assertNotNull(
                    currentState.balances.firstOrNull { balance -> balance.accountId == AccountId(SEED_ACCOUNT_ID) },
                )
            assertEquals(SEED_TRANSFER_MINOR, seededBalance.displayMinorUnits)
            assertEquals(CATALOG_MANAGED_CURRENCY, seededBalance.currency)

            // (c) The assets screen renders snapshot.manageableAccounts (P503AssetsScreen.kt), so
            // the seeded account must be in that projection — ASSET and active — or no row opens.
            val snapshot = assertNotNull(QueryCatalogSnapshot(catalogStore).query(ledgerId))
            val seededView =
                assertNotNull(
                    snapshot.manageableAccounts.firstOrNull { account -> account.accountId == AccountId(SEED_ACCOUNT_ID) },
                )
            assertEquals(SEED_ACCOUNT_NAME, seededView.name)
            assertEquals(AccountKind.ASSET, seededView.kind)
            assertTrue(seededView.active)

            // (d) The 交易详情 read itself: Success (never a typed failure) with the seed note and
            // both legs, so the detail screen renders the F1 marker `备注：对账样本转账`.
            val detail =
                assertIs<TransactionDetailResult.Success>(
                    QueryTransactionDetail(readAdapter, ledgerId, catalog).query(TransactionId(SEED_TRANSACTION_ID)),
                ).detail
            assertEquals(SEED_TRANSFER_NOTE, detail.note)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, detail.kind)
            assertEquals(
                setOf(AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID), AccountId(SEED_ACCOUNT_ID)),
                detail.legs.map { leg -> leg.accountId }.toSet(),
            )

            // (e) D7-9: the search result face reads the default 近 90 天 window through the SAME
            // function the use case calls, and the row's statistics instant is what that window
            // filters on. A seed instant outside it renders the guide/empty face while every capture
            // marker still passes — the silent degradation this assertion exists to prevent.
            val searchWindow =
                assertNotNull(
                    resolveSearchTimeWindow(
                        SearchTimeWindow.LastNinetyDays,
                        LedgerClock { Clock.System.now() },
                    ),
                )
            assertTrue(
                flowRow.statisticsAt >= searchWindow.first && flowRow.statisticsAt < searchWindow.second,
                "the seeded statistics instant ${flowRow.statisticsAt} is outside the search default window",
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

    private companion object {
        /** The system property the workflow sets; absent in every ordinary test run. */
        const val SEED_PATH_PROPERTY = "ul.fontscale.seed"

        /** `android-app/.../App.kt` `PRODUCT_LEDGER_ID` — the app bootstraps this one identity. */
        const val LEDGER_ID = "ledger-local-test"

        const val SEED_ACCOUNT_ID = "fontscale-seed-second-asset"
        const val SEED_ACCOUNT_NAME = "对账样本账户"
        const val SEED_TRANSFER_NOTE = "对账样本转账"
        const val SEED_TRANSACTION_ID = "fontscale-seed-transfer"
        const val SEED_TRANSFER_MINOR = 1_234L

        /**
         * D7-9: clock-relative, never a fixed date. The search result face reads the default 近 90 天
         * window, so a pinned instant would one day fall outside it and the seeded sample would
         * silently degrade to a face without the resulting row.
         */
        val SEED_TRANSFER_INSTANT: Instant = Clock.System.now() - 1.days

        /** After the transfer's instant, exactly as every other confirmation time is. */
        val SEED_CREATED_AT: Instant = SEED_TRANSFER_INSTANT + 5.minutes
    }
}
