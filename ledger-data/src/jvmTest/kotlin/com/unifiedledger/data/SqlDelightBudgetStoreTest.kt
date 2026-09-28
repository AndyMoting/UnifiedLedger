package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetFailureCode
import com.unifiedledger.application.BudgetIdSource
import com.unifiedledger.application.BudgetRequestId
import com.unifiedledger.application.BudgetRequestIdSource
import com.unifiedledger.application.BudgetTarget
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogEntityIdSource
import com.unifiedledger.application.CatalogEntityIds
import com.unifiedledger.application.CatalogFailureCode
import com.unifiedledger.application.CatalogManagementRequestIdSource
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.SaveBudgetConfiguration
import com.unifiedledger.application.budgetMonthKey
import com.unifiedledger.application.budgetScopeKey
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlinx.datetime.YearMonth
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 07.B budget-configuration store evidence (D-184 item 3; spec sections 3.3/3.4/3.5).
 *
 * Mirrors the P7-01 catalog claim-first protocol: equivalent-snapshot replay returns the
 * original receipt with zero writes, a same-id different snapshot is a `RequestIdentityConflict`,
 * a stale `expectedRevision` is a `BudgetRevisionConflict` with zero writes, and every accepted
 * add/modify/close appends exactly one immutable history row. The shared catalog
 * delete-reference probe blocks deleting a category that any current OR historical budget names.
 */
class SqlDelightBudgetStoreTest {
    private val ledgerId = LedgerId("ledger-budget")
    private val cny = CurrencyUnit("CNY", 2)
    private val march = YearMonth(2026, 3)

    @Test
    fun setLimitAddsABudgetWithAFirstHistoryRowAndAdvancesTheRevision() {
        withStore { store, driver ->
            val accepted = assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100_000L, expectedRevision = 0L))
            assertEquals(BudgetId("budget-1"), accepted.receipt.budgetId)
            assertEquals(1L, accepted.receipt.newRevision)
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_config WHERE ledger_id = 'ledger-budget'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history WHERE ledger_id = 'ledger-budget'"))
            assertEquals(100_000L, queryLong(driver, "SELECT limit_minor FROM budget_settings_history"))
            assertEquals("MONITORED", queryText(driver, "SELECT status FROM budget_settings_history"))
            assertEquals(1L, queryLong(driver, "SELECT current_revision FROM budget_config"))
        }
    }

    @Test
    fun zeroLimitIsAValidMonitoredBudgetDistinctFromUnsetAndClosed() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-zero", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 0L, expectedRevision = 0L))
            // Zero is monitored and stored as a real 0, not NULL.
            assertEquals("MONITORED", queryText(driver, "SELECT status FROM budget_settings_history"))
            assertEquals(0L, queryLong(driver, "SELECT limit_minor FROM budget_settings_history"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_settings_history WHERE limit_minor IS NULL"))
            // Closing appends a CLOSED row (NULL limit) while keeping the zero row: the two are distinct.
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-close", "budget-1").close(ledgerId, march, BudgetScope.Total, expectedRevision = 1L))
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history WHERE status = 'MONITORED' AND limit_minor = 0"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history WHERE status = 'CLOSED' AND limit_minor IS NULL"))
        }
    }

    @Test
    fun modifyAppendsASecondHistoryRowAndKeepsTheFirst() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            val modify = assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-modify", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 250L, expectedRevision = 1L))
            assertEquals(2L, modify.receipt.newRevision)
            // History is append-only: both revisions survive with their own limit.
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(100L, queryLong(driver, "SELECT limit_minor FROM budget_settings_history WHERE revision_number = 1"))
            assertEquals(250L, queryLong(driver, "SELECT limit_minor FROM budget_settings_history WHERE revision_number = 2"))
            assertEquals(2L, queryLong(driver, "SELECT current_revision FROM budget_config"))
        }
    }

    @Test
    fun closeKeepsTheConfigurationAndTheWholeHistory() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-close", "budget-1").close(ledgerId, march, BudgetScope.Total, expectedRevision = 1L))
            // Close never deletes the config row or the history: it appends a CLOSED revision.
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history WHERE status = 'MONITORED'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history WHERE status = 'CLOSED'"))
        }
    }

    @Test
    fun equivalentReplayReturnsTheOriginalReceiptWithZeroNewWrites() {
        withStore { store, driver ->
            val first = assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-replay", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            // A different minted id proves the replay returns the ORIGINAL receipt, not a new one.
            val replay = assertIs<BudgetCommandResult.NoChange>(saveCommand(store, driver, "request-replay", "budget-2").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            assertEquals(first.receipt, replay.receipt)
            assertEquals(BudgetId("budget-1"), replay.receipt.budgetId)
            // Zero new writes: still one config, one history row, one request, one receipt.
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_command_request"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_command_receipt"))
        }
    }

    @Test
    fun sameRequestIdWithADifferentSnapshotIsAnIdentityConflictWithZeroWrites() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-conflict", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            // Same request id, different payload (limit changed): an identity conflict, no new row.
            val conflict = assertIs<BudgetCommandResult.Conflict>(saveCommand(store, driver, "request-conflict", "budget-2").setLimit(ledgerId, march, BudgetScope.Total, 999L, expectedRevision = 1L))
            assertEquals(BudgetFailureCode.REQUEST_IDENTITY_CONFLICT, conflict.failureCode)
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_config"))
        }
    }

    @Test
    fun staleExpectedRevisionIsAConflictWithZeroWrites() {
        withStore { store, driver ->
            val conflict = assertIs<BudgetCommandResult.Conflict>(saveCommand(store, driver, "request-stale", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 99L))
            assertEquals(BudgetFailureCode.BUDGET_REVISION_CONFLICT, conflict.failureCode)
            // Zero writes: no config, no history, and the claim rolled back so the id stays retryable.
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_command_request"))
        }
    }

    @Test
    fun twoConflictingCommitsLeaveOneAcceptedAndOneConflict() {
        withStore { store, driver ->
            // Two writers both hold revision 0 and race the same identity.
            val first = saveCommand(store, driver, "request-race-a", "budget-a")
            val second = saveCommand(store, driver, "request-race-b", "budget-b")
            assertIs<BudgetCommandResult.Accepted>(first.setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            val loser = assertIs<BudgetCommandResult.Conflict>(second.setLimit(ledgerId, march, BudgetScope.Total, 200L, expectedRevision = 0L))
            assertEquals(BudgetFailureCode.BUDGET_REVISION_CONFLICT, loser.failureCode)
            // Exactly one budget for the identity, one history row, and the winner's limit.
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(100L, queryLong(driver, "SELECT limit_minor FROM budget_settings_history"))
        }
    }

    @Test
    fun aNegativeLimitIsATypedRejectionWithZeroWrites() {
        withStore { store, driver ->
            val rejected = assertIs<BudgetCommandResult.Rejected>(saveCommand(store, driver, "request-negative", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, -1L, expectedRevision = 0L))
            assertEquals(BudgetFailureCode.BUDGET_LIMIT_NEGATIVE, rejected.failureCode)
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_command_request"))
        }
    }

    @Test
    fun distinctScopesAndMonthsGetDistinctStableIdentities() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-total", "budget-total").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            val april = YearMonth(2026, 4)
            val aprilResult = assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-april", "budget-april").setLimit(ledgerId, april, BudgetScope.Total, 100L, expectedRevision = 0L))
            // Different month -> a different stable identity, and a fresh revision 1.
            assertEquals(BudgetId("budget-april"), aprilResult.receipt.budgetId)
            assertEquals(2L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            assertEquals("2026-03", queryText(driver, "SELECT month_key FROM budget_config WHERE budget_id = 'budget-total'"))
            assertEquals("2026-04", queryText(driver, "SELECT month_key FROM budget_config WHERE budget_id = 'budget-april'"))
        }
    }

    @Test
    fun deletingACategoryReferencedByACurrentBudgetIsRejectedAndLeavesEverything() {
        withStore { store, driver ->
            bootstrapCatalog(store, driver)
            val breakfast = CategoryId("expense-category-breakfast")
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-cat", "budget-cat").setLimit(ledgerId, march, BudgetScope.Category(breakfast), 100L, expectedRevision = 0L))
            val rejected = assertIs<CatalogCommandResult.Rejected>(catalogExecutor(driver, "request-delete").deleteCategory(ledgerId, breakfast, expectedCatalogVersion = 1L))
            assertEquals(CatalogFailureCode.CATEGORY_HAS_REFERENCES, rejected.failureCode)
            // The category, the budget and its history all survive.
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE category_id = 'expense-category-breakfast'"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_config WHERE scope_category_id = 'expense-category-breakfast'"))
        }
    }

    @Test
    fun deletingACategoryReferencedOnlyByClosedHistoryIsStillRejected() {
        withStore { store, driver ->
            bootstrapCatalog(store, driver)
            val breakfast = CategoryId("expense-category-breakfast")
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-cat", "budget-cat").setLimit(ledgerId, march, BudgetScope.Category(breakfast), 100L, expectedRevision = 0L))
            // Close the budget: the current revision is CLOSED, but the history still names the category.
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-cat-close", "budget-cat").close(ledgerId, march, BudgetScope.Category(breakfast), expectedRevision = 1L))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history WHERE scope_category_id = 'expense-category-breakfast' AND status = 'CLOSED'"))
            val rejected = assertIs<CatalogCommandResult.Rejected>(catalogExecutor(driver, "request-delete").deleteCategory(ledgerId, breakfast, expectedCatalogVersion = 1L))
            assertEquals(CatalogFailureCode.CATEGORY_HAS_REFERENCES, rejected.failureCode)
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM catalog_category WHERE category_id = 'expense-category-breakfast'"))
        }
    }

    @Test
    fun theSharedCatalogReferenceProbeSeesBudgetCurrentAndHistoricalReferences() {
        withStore { store, driver ->
            bootstrapCatalog(store, driver)
            val probe = SqlDelightCatalogStore(LedgerDatabase(driver), driver)
            val breakfast = CategoryId("expense-category-breakfast")
            val dinner = CategoryId("expense-category-food")
            assertTrue(!probe.hasReferences(ledgerId, breakfast))
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-cat", "budget-cat").setLimit(ledgerId, march, BudgetScope.Category(breakfast), 100L, expectedRevision = 0L))
            // The shared catalog probe now sees the budget reference surface (spec section 3.5).
            assertTrue(probe.hasReferences(ledgerId, breakfast))
            // Close: the current revision is CLOSED but the immutable history still references it.
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-cat-close", "budget-cat").close(ledgerId, march, BudgetScope.Category(breakfast), expectedRevision = 1L))
            assertTrue(probe.hasReferences(ledgerId, breakfast))
            // A category with no budget reference is still deletable.
            assertTrue(!probe.hasReferences(ledgerId, dinner))
        }
    }

    @Test
    fun settingsHistoryReturnsEveryRevisionOldestFirstIncludingClose() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-modify", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 250L, expectedRevision = 1L))
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-close", "budget-1").close(ledgerId, march, BudgetScope.Total, expectedRevision = 2L))
            val history = store.settingsHistory(ledgerId, BudgetId("budget-1"))
            // Three immutable revisions, oldest first, with close as a CLOSED NULL-limit row.
            assertEquals(listOf(1L, 2L, 3L), history.map { it.revisionNumber })
            assertEquals(listOf(false, false, true), history.map { it.closed })
            assertEquals(listOf(100L, 250L, null), history.map { it.limitMinorUnits })
            assertEquals(listOf("request-add", "request-modify", "request-close"), history.map { it.requestId.value })
        }
    }

    @Test
    fun aCategoryScopeOutsideTheCatalogIsATypedRejectionWithZeroWrites() {
        withStore { store, driver ->
            bootstrapCatalog(store, driver)
            val rejected = assertIs<BudgetCommandResult.Rejected>(saveCommand(store, driver, "request-missing", "budget-1").setLimit(ledgerId, march, BudgetScope.Category(CategoryId("category-absent")), 100L, expectedRevision = 0L))
            assertEquals(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID, rejected.failureCode)
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_config"))
        }
    }

    @Test
    fun closingAnUnresolvableCategoryScopeIsRejectedWithZeroWrites() {
        withStore { store, driver ->
            bootstrapCatalog(store, driver)
            // Close shares the same scope resolution as setLimit, so an absent category is a
            // typed rejection that never reaches the store's claim/history write.
            val rejected = assertIs<BudgetCommandResult.Rejected>(saveCommand(store, driver, "request-close-missing", "budget-1").close(ledgerId, march, BudgetScope.Category(CategoryId("category-absent")), expectedRevision = 0L))
            assertEquals(BudgetFailureCode.BUDGET_SCOPE_CATEGORY_INVALID, rejected.failureCode)
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            assertEquals(0L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
        }
    }

    @Test
    fun theRequestRowKeepsTheStableBudgetIdForReplayLookup() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            assertEquals("budget-1", queryText(driver, "SELECT budget_id FROM budget_command_request WHERE request_id = 'request-add'"))
            assertEquals("budget-1", queryText(driver, "SELECT budget_id FROM budget_command_receipt WHERE request_id = 'request-add'"))
        }
    }

    @Test
    fun receiptAndHistoryAndConfigAreImmutable() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            // A receipt cannot be rewritten or deleted.
            assertFailsSql { driver.execute(null, "UPDATE budget_command_receipt SET new_revision = 9", 0) }
            assertFailsSql { driver.execute(null, "DELETE FROM budget_command_receipt", 0) }
            // History cannot be updated or deleted (close keeps history, it never deletes it).
            assertFailsSql { driver.execute(null, "UPDATE budget_settings_history SET limit_minor = 9", 0) }
            assertFailsSql { driver.execute(null, "DELETE FROM budget_settings_history", 0) }
            // The config cannot be deleted and the CAS pointer can only advance by one.
            assertFailsSql { driver.execute(null, "DELETE FROM budget_config", 0) }
            assertFailsSql { driver.execute(null, "UPDATE budget_config SET current_revision = 5", 0) }
            // The guard's IDENTITY-FREEZE clause: even with a valid +1 revision bump, the stable
            // identity columns (month/currency/scope) are immutable. Each statement below keeps
            // current_revision = 2 (the legal successor of 1) so ONLY the identity clause can reject
            // it; dropping that clause would let these updates through and this test go red.
            assertFailsSql { driver.execute(null, "UPDATE budget_config SET month_key = '2026-04', current_revision = 2", 0) }
            assertFailsSql { driver.execute(null, "UPDATE budget_config SET currency_code = 'USD', current_revision = 2", 0) }
            assertFailsSql { driver.execute(null, "UPDATE budget_config SET scope_key = 'TOTAL-OTHER', current_revision = 2", 0) }
            assertFailsSql { driver.execute(null, "UPDATE budget_config SET scope_kind = 'CATEGORY', scope_category_id = 'category-food', current_revision = 2", 0) }
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_config"))
            // The identity is untouched by the rejected attempts.
            assertEquals("2026-03", queryText(driver, "SELECT month_key FROM budget_config"))
            assertEquals("TOTAL", queryText(driver, "SELECT scope_key FROM budget_config"))
        }
    }

    @Test
    fun theHistoryScopeConsistencyCheckRejectsAMismatchedCategoryRow() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            // F4: budget_settings_history now carries the same scope_kind<->scope_category_id CHECK
            // as budget_config. The guard_update trigger blocks UPDATE, so the CHECK is exercised by
            // a direct INSERT (the trigger is only BEFORE UPDATE/DELETE). A CATEGORY row with a NULL
            // category and a TOTAL row that names a category must both be rejected; dropping the
            // CHECK would let these inserts through and this test would go red.
            assertFailsSql {
                driver.execute(
                    null,
                    "INSERT INTO budget_settings_history(ledger_id, budget_id, revision_number, status, limit_minor, currency_code, currency_precision, scope_kind, scope_category_id, request_id, created_at) " +
                        "VALUES ('ledger-budget','budget-1',2,'MONITORED',5,'CNY',2,'CATEGORY',NULL,'request-add','2026-03-05T02:00:00Z')",
                    0,
                )
            }
            assertFailsSql {
                driver.execute(
                    null,
                    "INSERT INTO budget_settings_history(ledger_id, budget_id, revision_number, status, limit_minor, currency_code, currency_precision, scope_kind, scope_category_id, request_id, created_at) " +
                        "VALUES ('ledger-budget','budget-1',3,'MONITORED',5,'CNY',2,'TOTAL','category-food','request-add','2026-03-05T02:00:00Z')",
                    0,
                )
            }
            assertEquals(1L, queryLong(driver, "SELECT count(*) FROM budget_settings_history"))
        }
    }

    @Test
    fun loadingTheAuthorityReturnsTheCurrentLimitAndRevision() {
        withStore { store, driver ->
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-add", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 100L, expectedRevision = 0L))
            val target = BudgetTarget(ledgerId, budgetMonthKey(march), cny, budgetScopeKey(BudgetScope.Total), null)
            val authority = requireNotNull(store.load(target))
            assertEquals(BudgetId("budget-1"), authority.budgetId)
            assertEquals(1L, authority.revision)
            assertEquals(100L, authority.limitMinorUnits)
            // A modify advances the CAS pointer and the loaded limit follows the newest history row.
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-modify", "budget-1").setLimit(ledgerId, march, BudgetScope.Total, 250L, expectedRevision = 1L))
            val modified = requireNotNull(store.load(target))
            assertEquals(2L, modified.revision)
            assertEquals(250L, modified.limitMinorUnits)
            // A close advances again but the current limit is null, distinct from a zero budget.
            assertIs<BudgetCommandResult.Accepted>(saveCommand(store, driver, "request-close", "budget-1").close(ledgerId, march, BudgetScope.Total, expectedRevision = 2L))
            val closed = requireNotNull(store.load(target))
            assertEquals(3L, closed.revision)
            assertNull(closed.limitMinorUnits)
        }
    }

    @Test
    fun loadingAnUnknownIdentityReturnsNull() {
        withStore { store, driver ->
            val target = BudgetTarget(ledgerId, budgetMonthKey(march), cny, budgetScopeKey(BudgetScope.Total), null)
            assertNull(store.load(target))
        }
    }

    private fun withStore(block: (SqlDelightBudgetStore, JdbcSqliteDriver) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            block(SqlDelightBudgetStore(LedgerDatabase(driver), driver), driver)
        } finally {
            driver.close()
        }
    }

    private fun bootstrapCatalog(
        store: SqlDelightBudgetStore,
        driver: JdbcSqliteDriver,
    ) {
        // The catalog store owns the bootstrap; the budget store reads the catalog for a
        // category scope and shares the delete-reference probe.
        SqlDelightCatalogStore(LedgerDatabase(driver), driver).bootstrap(ledgerId, defaultCatalogSeed())
    }

    private fun saveCommand(
        store: SqlDelightBudgetStore,
        driver: JdbcSqliteDriver,
        requestId: String,
        budgetId: String,
    ): SaveBudgetConfiguration =
        SaveBudgetConfiguration(
            commitPort = store,
            requestIdSource = BudgetRequestIdSource { BudgetRequestId(requestId) },
            budgetIdSource = BudgetIdSource { BudgetId(budgetId) },
            catalogReader = SqlDelightCatalogStore(LedgerDatabase(driver), driver),
            clock = LedgerClock { Instant.parse("2026-03-05T02:00:00Z") },
        )

    private fun catalogExecutor(
        driver: JdbcSqliteDriver,
        requestId: String,
    ): ExecuteCatalogCommand {
        // The budget test drives the catalog delete through a real catalog store over the same
        // driver; its hasReferences now includes the budget reference surface (spec section 3.5).
        val catalogStore = SqlDelightCatalogStore(LedgerDatabase(driver), driver)
        return ExecuteCatalogCommand(
            commitPort = catalogStore,
            requestIdSource = CatalogManagementRequestIdSource { CatalogRequestId(requestId) },
            entityIdSource =
                CatalogEntityIdSource {
                    CatalogEntityIds(
                        manageableAccountId = AccountId("asset-managed"),
                        postingAccountId = AccountId("expense-account"),
                        parentCategoryId = CategoryId("category-new-parent"),
                        childCategoryId = CategoryId("category-new-child"),
                    )
                },
            categoryReferenceProbe = catalogStore,
        )
    }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }

    private fun queryLong(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    private fun queryText(
        driver: JdbcSqliteDriver,
        sql: String,
    ): String =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(requireNotNull(cursor.getString(0)))
                },
                0,
            ).value

    private fun assertFailsSql(block: () -> Unit) {
        try {
            block()
            error("expected the guard trigger to reject the statement")
        } catch (expected: java.sql.SQLException) {
            // The guard trigger fired as required.
        }
    }
}
