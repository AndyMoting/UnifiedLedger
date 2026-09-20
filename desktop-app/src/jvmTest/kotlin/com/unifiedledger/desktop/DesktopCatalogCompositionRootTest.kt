package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogBootstrapFailedException
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedManualExpense
import com.unifiedledger.application.RequestId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.ui.P503StartupState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-01.C production composition-root evidence (spec sections 5.3/6.2/6.3, D-143):
 *
 * - [buildLedgerGraph] bootstraps the persisted catalog with the frozen legacy demo ids;
 * - a ledger whose existing references do not resolve fails closed through [openDesktopLedger]
 *   and maps to [P503StartupState.StartupError] via [DesktopStartupController];
 * - the production manual-expense factory revalidates the current catalog inside the write
 *   transaction (V-2) and rejects a draft whose category was deactivated with zero formal writes;
 * - a management command plus [com.unifiedledger.application.CatalogConsumerSession.refresh]
 *   makes the new account visible to options without a restart.
 */
class DesktopCatalogCompositionRootTest {
    private val ledgerId = com.unifiedledger.domain.LedgerId("ledger-local-test")

    @Test
    fun bootstrapSeedsDefaultCatalogWithLegacyDemoIdsOnAFreshLedger() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val queries = graph.database.ledgerQueries
            assertEquals(1L, queries.countCatalogVersions(ledgerId.value).executeAsOne())
            val accountIds = queries.selectCatalogAccounts(ledgerId.value).executeAsList().map { it.account_id }
            assertEquals(setOf("asset-payment-local", "expense-account-local"), accountIds.toSet())
            val categoryIds = queries.selectCatalogCategories(ledgerId.value).executeAsList().map { it.category_id }
            assertEquals(setOf("expense-category-food", "expense-category-breakfast"), categoryIds.toSet())
            assertEquals(1L, graph.catalogSession.authority.catalogVersion)
        } finally {
            driver.close()
        }
    }

    @Test
    fun unresolvedExistingReferenceFailsClosedAndMapsToStartupError() {
        val path: Path = Files.createTempFile("p7-01-desktop-unknown-ref-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                // A real current-version file is stamped by the production open path; stamp it
                // here so the probe opens directly instead of re-running Schema.create.
                driver.execute(null, "PRAGMA user_version = 31", 0)
                driver.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind, canonical_kind) VALUES ('tx-x','ledger-local-test','EXPENSE',NULL)",
                    0,
                )
                driver.execute(null, "INSERT INTO posting_set VALUES ('set-x','ledger-local-test')", 0)
                driver.execute(
                    null,
                    "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('v-x','tx-x','ledger-local-test',1,'set-x','2026-01-01T00:00:00+08:00','2026-01-01T00:00:00+08:00','2026-01-01T00:00:00+08:00',NULL)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO posting VALUES ('p-x','set-x','ledger-local-test',0,'account-not-seeded',-100,'CNY',2)",
                    0,
                )
            }

            var thrown: Exception? = null
            try {
                openDesktopLedger(url).close()
            } catch (failure: Exception) {
                thrown = failure
            }
            assertIs<CatalogBootstrapFailedException>(thrown)

            val controller = DesktopStartupController(openDatabase = { openDesktopLedger(url) })
            controller.start()
            assertEquals(P503StartupState.StartupError, controller.state)
            assertEquals(null, controller.facade)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun productionCommitRevalidatesCatalogInsideTheWriteTransactionWithZeroFormalWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            // The draft was prepared while the category was active; deactivate it behind it.
            driver.execute(
                null,
                "UPDATE catalog_category SET active = 0 WHERE ledger_id = 'ledger-local-test' AND category_id = 'expense-category-breakfast'",
                0,
            )

            val result =
                graph.useCase.execute(
                    ExplicitlyConfirmedManualExpense(
                        ledgerId = graph.ledgerId,
                        requestId = RequestId("request-desktop-v2"),
                        amount = Money.ofMinor(1_200L, CurrencyUnit("CNY", 2)),
                        categoryId = graph.categoryId,
                        paymentAccountId = graph.paymentAccountId,
                        occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
                        note = "",
                        confirmation = ExplicitManualSave,
                    ),
                )
            assertIs<ConfirmedManualExpenseResult.Rejected>(result)
            val queries = graph.database.ledgerQueries
            assertEquals(0L, queries.countTransactions().executeAsOne())
            assertEquals(0L, queries.countPostings().executeAsOne())
            assertEquals(0L, queries.countRequests().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun managementCommandThenRefreshMakesTheNewAccountVisibleWithoutRestart() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val accepted =
                assertIs<CatalogCommandResult.Accepted>(
                    graph.catalogCommands.createAccount(
                        ledgerId = graph.ledgerId,
                        name = "现金账户",
                        kind = AccountKind.ASSET,
                        expectedCatalogVersion = 1L,
                    ),
                )
            assertEquals(2L, accepted.receipt.newCatalogVersion)
            val newAccountId = requireNotNull(accepted.receipt.createdManageableAccountId)

            // Before refresh the session still serves the bootstrapped authority.
            assertEquals(1L, graph.catalogSession.authority.catalogVersion)

            val refreshed = graph.catalogSession.refresh()
            assertEquals(2L, refreshed.catalogVersion)
            val options = graph.catalogSession.optionsProvider.queryOptions()
            val labels = options.paymentAccounts.map { it.label }
            assertTrue("现金账户" in labels)
            assertTrue(newAccountId in options.paymentAccounts.map { it.accountId })
        } finally {
            driver.close()
        }
    }

    @Test
    fun facadeReadModelsFollowTheRefreshedCatalogSession() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val staleQuery = graph.facade.queryCurrentState
            val staleSummary = graph.facade.summarizeActivity
            val staleOptions = graph.facade.optionsProvider

            graph.catalogSession.refresh()

            // D1/D4 (spec 6.2/7.4): the facade must expose the session's current models, not the
            // ones captured at construction, so a management refresh reaches reads/summaries too.
            assertSame(graph.catalogSession.optionsProvider, graph.facade.optionsProvider)
            assertSame(graph.catalogSession.queryCurrentState, graph.facade.queryCurrentState)
            assertSame(graph.catalogSession.summarizeActivity, graph.facade.summarizeActivity)
            assertNotSame(staleOptions, graph.facade.optionsProvider)
            assertNotSame(staleQuery, graph.facade.queryCurrentState)
            assertNotSame(staleSummary, graph.facade.summarizeActivity)
        } finally {
            driver.close()
        }
    }

    @Test
    fun enableCategoryGroupReactivatesTheWholeGroupThroughTheProductionCommandPort() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val created =
                assertIs<CatalogCommandResult.Accepted>(
                    graph.catalogCommands.createCategoryGroup(
                        ledgerId = graph.ledgerId,
                        kind = com.unifiedledger.domain.CategoryKind.EXPENSE,
                        groupName = "交通",
                        firstChildName = "地铁",
                        expectedCatalogVersion = 1L,
                    ),
                )
            val parentId = requireNotNull(created.receipt.createdParentCategoryId)
            val childId = requireNotNull(created.receipt.createdChildCategoryId)

            // C-5: deactivating a group cascades to its children.
            val off =
                assertIs<CatalogCommandResult.Accepted>(
                    graph.catalogCommands.setCategoryActive(
                        ledgerId = graph.ledgerId,
                        categoryId = parentId,
                        active = false,
                        expectedCatalogVersion = 2L,
                    ),
                )
            val afterOff = requireNotNull(graph.facade.catalogSnapshot())
            assertFalse(afterOff.categories.first { it.categoryId == parentId }.active)
            assertFalse(afterOff.categories.first { it.categoryId == childId }.active)

            // C-8: the whole-group re-enable must reactivate the group AND its children.
            assertIs<CatalogCommandResult.Accepted>(
                graph.catalogCommands.enableCategoryGroup(
                    ledgerId = graph.ledgerId,
                    parentId = parentId,
                    expectedCatalogVersion = off.receipt.newCatalogVersion,
                ),
            )
            val afterOn = requireNotNull(graph.facade.catalogSnapshot())
            assertTrue(afterOn.categories.first { it.categoryId == parentId }.active)
            assertTrue(afterOn.categories.first { it.categoryId == childId }.active)
        } finally {
            driver.close()
        }
    }

    @Test
    fun reopeningAnInitializedLedgerKeepsTheSameCatalogAndVersion() {
        val path: Path = Files.createTempFile("p7-01-desktop-reopen-catalog-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            val first = openDesktopLedger(url)
            val firstAuthority = first.catalogSession?.authority
            first.close()

            val second = openDesktopLedger(url)
            val secondAuthority = second.catalogSession?.authority
            assertEquals(firstAuthority?.catalogVersion, secondAuthority?.catalogVersion)
            assertEquals(
                firstAuthority?.catalog?.accounts?.map { it.id.value },
                secondAuthority?.catalog?.accounts?.map { it.id.value },
            )
            assertEquals(1L, secondAuthority?.catalogVersion)
            second.close()

            val driver = JdbcSqliteDriver(url)
            try {
                val queries = LedgerDatabase(driver).ledgerQueries
                assertEquals(2L, queries.countCatalogAccounts("ledger-local-test").executeAsOne())
                assertEquals(2L, queries.countCatalogCategories("ledger-local-test").executeAsOne())
            } finally {
                driver.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
