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
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.RequestId
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
 * reconciliation read adapter, so the queue the device will render is proven here rather than
 * assumed: one PENDING leg per account, both from the same transaction, no active link.
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

    /** Reopens the produced FILE and proves the exact queue the device sample will render. */
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
        val SEED_TRANSFER_INSTANT: Instant = Instant.parse("2026-11-10T02:00:00Z")
        val SEED_CREATED_AT: Instant = Instant.parse("2026-11-10T02:05:00Z")
    }
}
