package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.BudgetTarget
import com.unifiedledger.application.MonthlyContributionReadFailure
import com.unifiedledger.application.MonthlyContributionReadResult
import com.unifiedledger.application.budgetMonthConfigKey
import com.unifiedledger.application.budgetScopeKey
import com.unifiedledger.data.db.LedgerDatabase
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
 * P7-07 07.D-1 bounded contribution read adapter evidence (D-184 item 3 residual; spec section
 * 5.1). Drives the real SQL over a real SQLite database so removing the window predicate, the
 * effective/current-version joins, the fail-loud probe or the catalog-version gate turns a
 * specific assertion red:
 *
 *  - `[start, end)` boundary: a transaction exactly AT `start` is INCLUDED (start-inclusive
 *    `>=`), a transaction exactly at `end` is excluded (`<`); reverting `>=` to `>` drops the
 *    start row and turns the assertion red;
 *  - a corrected transaction whose SUPERSEDED version is in-window but whose CURRENT version is
 *    out-of-window is excluded (the window applies to the current version only);
 *  - void/corrected transactions count once (effective + current version only);
 *  - a missing projection is the typed [MonthlyContributionReadFailure.MissingProjection];
 *  - a catalog version different from the caller's is
 *    [MonthlyContributionReadFailure.CatalogVersionMismatch];
 *  - an out-of-window bound is [MonthlyContributionReadFailure.Unavailable], never an empty zero;
 *  - `configsForMonth` distinguishes a monitored zero from a closed (null) budget and reads the
 *    current revision in one query.
 *
 * All data synthetic and anonymous with fixed instants.
 */
class SqlDelightMonthlyContributionReadAdapterTest {
    private val ledgerId = LedgerId("ledger-07d1")
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun theWindowIncludesTheStartBoundAndExcludesTheEndBound() {
        withDatabase { database, driver, adapter ->
            // tx-at-start is EXACTLY at the window start instant: the `>=` bound must include it
            // (reverting `>=` to `>` drops it and turns this assertion red).
            seedExpense(database, driver, "tx-at-start", "2026-03-01T00:00:00Z")
            seedExpense(database, driver, "tx-inside", "2026-03-10T02:00:00Z")
            seedExpense(database, driver, "tx-at-end", "2026-04-01T00:00:00Z")
            val rows = adapter.readContributions(ledgerId, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), expectedCatalogVersion = 1L)
            val success = assertIs<MonthlyContributionReadResult.Success>(rows)
            assertEquals(listOf("tx-at-start", "tx-inside"), success.rows.map { it.transactionId.value })
        }
    }

    @Test
    fun aCorrectedTransactionWhoseCurrentVersionIsOutOfWindowIsExcluded() {
        withDatabase { database, driver, adapter ->
            // The superseded version is in-window but the CURRENT version moved to April: the
            // window applies to the current version, so the March read must NOT include it.
            seedExpense(database, driver, "tx-moved", "2026-03-15T02:00:00Z", versionId = "version-march")
            seedExpense(database, driver, "tx-moved", "2026-04-10T02:00:00Z", versionId = "version-april", versionNumber = 2L)
            val march = adapter.readContributions(ledgerId, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), 1L)
            assertTrue(assertIs<MonthlyContributionReadResult.Success>(march).rows.isEmpty())
            val april = adapter.readContributions(ledgerId, Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-05-01T00:00:00Z"), 1L)
            assertEquals(listOf("tx-moved"), assertIs<MonthlyContributionReadResult.Success>(april).rows.map { it.transactionId.value })
        }
    }

    @Test
    fun voidedAndCorrectedTransactionsContributeOnceThroughTheEffectiveCurrentVersion() {
        withDatabase { database, driver, adapter ->
            // Corrected: version-old superseded by version-new (current) in the same window.
            seedExpense(database, driver, "tx-corrected", "2026-03-05T02:00:00Z", versionId = "version-old")
            seedExpense(database, driver, "tx-corrected", "2026-03-06T02:00:00Z", versionId = "version-new", versionNumber = 2L)
            // Voided: one version in the window but ineffective.
            seedExpense(database, driver, "tx-voided", "2026-03-07T02:00:00Z")
            seedVoidFact(driver, "tx-voided")
            val result = adapter.readContributions(ledgerId, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), 1L)
            val success = assertIs<MonthlyContributionReadResult.Success>(result)
            assertEquals(listOf("tx-corrected"), success.rows.map { it.transactionId.value })
            assertEquals(
                "version-new",
                success.rows
                    .single()
                    .currentVersionId.value,
            )
        }
    }

    @Test
    fun aMissingProjectionIsTheTypedFailureNotAnEmptyWindow() {
        withDatabase { database, driver, adapter ->
            // A raw row whose projection is NULL (as an unmigrated/unmaintained writer would leave).
            driver.execute(
                null,
                "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-unprojected','${ledgerId.value}','EXPENSE')",
                0,
            )
            driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('ps-unprojected','${ledgerId.value}')", 0)
            driver.execute(
                null,
                "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, statistics_at_epoch_nanos) " +
                    "VALUES ('v-unprojected','tx-unprojected','${ledgerId.value}',1,'ps-unprojected','2026-03-05T02:00:00Z','2026-03-05T02:00:00Z','2026-03-05T02:00:00Z',NULL,NULL)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ('tx-unprojected','${ledgerId.value}','v-unprojected')",
                0,
            )
            val result = adapter.readContributions(ledgerId, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), 1L)
            assertEquals(MonthlyContributionReadFailure.MissingProjection, assertIs<MonthlyContributionReadResult.Failed>(result).failure)
        }
    }

    @Test
    fun aCatalogGenerationDifferentFromTheCallerIsATypedMismatch() {
        withDatabase { database, driver, adapter ->
            seedExpense(database, driver, "tx-inside", "2026-03-10T02:00:00Z")
            // The ledger's catalog is at version 1; a caller holding a different generation
            // (e.g. it loaded the catalog, then a catalog write committed) must fail loud.
            val result = adapter.readContributions(ledgerId, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), expectedCatalogVersion = 2L)
            assertEquals(MonthlyContributionReadFailure.CatalogVersionMismatch, assertIs<MonthlyContributionReadResult.Failed>(result).failure)
        }
    }

    @Test
    fun anOutOfWindowBoundIsUnavailableNotAnEmptyZero() {
        withDatabase { database, driver, adapter ->
            // Year 3000 is outside the 64-bit nanosecond window; the bound cannot be projected.
            val result = adapter.readContributions(ledgerId, Instant.parse("3000-01-01T00:00:00Z"), Instant.parse("3000-02-01T00:00:00Z"), 1L)
            assertEquals(MonthlyContributionReadFailure.Unavailable, assertIs<MonthlyContributionReadResult.Failed>(result).failure)
        }
    }

    @Test
    fun anEmptyWindowIsAGenuineEmptySuccessNotAFailure() {
        withDatabase { database, driver, adapter ->
            seedExpense(database, driver, "tx-may", "2026-05-10T02:00:00Z")
            val result = adapter.readContributions(ledgerId, Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"), 1L)
            assertTrue(assertIs<MonthlyContributionReadResult.Success>(result).rows.isEmpty())
        }
    }

    @Test
    fun configsForEnumeratesEveryScopeAndDistinguishesZeroFromClosed() {
        withDatabase { database, driver, _ ->
            val store = SqlDelightBudgetStore(LedgerDatabase(driver), driver)
            val march = YearMonth(2026, 3)
            // Zero-monitored TOTAL plus a CLOSED category budget.
            store.setLimitMonitored(ledgerId, march, BudgetScope.Total, 0L)
            store.setLimitMonitored(ledgerId, march, BudgetScope.Category(CategoryId("expense-category-breakfast")), 500L)
            store.closeBudget(ledgerId, march, BudgetScope.Category(CategoryId("expense-category-breakfast")))
            val rows = store.configsForMonth(ledgerId, budgetMonthConfigKey(march))
            assertEquals(2, rows.size)
            val total = rows.first { it.scope == BudgetScope.Total }
            assertEquals(0L, total.limitMinorUnits)
            assertTrue(!total.closed)
            val closed = rows.first { it.scope == BudgetScope.Category(CategoryId("expense-category-breakfast")) }
            assertNull(closed.limitMinorUnits)
            assertTrue(closed.closed)
            // A month with no configuration returns no rows (never an invented one).
            assertTrue(store.configsForMonth(ledgerId, budgetMonthConfigKey(YearMonth(2026, 7))).isEmpty())
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun withDatabase(block: (LedgerDatabase, JdbcSqliteDriver, SqlDelightMonthlyContributionReadAdapter) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, migrationProperties())
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalogStore = SqlDelightCatalogStore(database, driver)
            catalogStore.bootstrap(ledgerId, defaultCatalogSeed())
            val adapter = SqlDelightMonthlyContributionReadAdapter(database, catalogStore)
            block(database, driver, adapter)
        } finally {
            driver.close()
        }
    }

    private fun seedExpense(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        transactionId: String,
        statisticsAt: String,
        versionId: String = "$transactionId-version-1",
        versionNumber: Long = 1L,
    ) {
        val postingSetId = "$versionId-posting-set"
        // INSERT OR IGNORE / OR REPLACE so the corrected-transaction case can seed two versions
        // of the same transaction in order without a PK conflict.
        driver.execute(
            null,
            "INSERT OR IGNORE INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('$transactionId','${ledgerId.value}','EXPENSE')",
            0,
        )
        driver.execute(null, "INSERT OR IGNORE INTO posting_set(posting_set_id, ledger_id) VALUES ('$postingSetId','${ledgerId.value}')", 0)
        database.ledgerQueries.insertTransactionVersion(
            versionId,
            transactionId,
            ledgerId.value,
            versionNumber,
            postingSetId,
            statisticsAt,
            statisticsAt,
            statisticsAt,
            null,
        )
        database.ledgerQueries.insertPosting("$versionId-posting-0", postingSetId, ledgerId.value, 0L, "expense-account-local", 1_000L, cny.code, cny.precision.toLong())
        database.ledgerQueries.insertPosting("$versionId-posting-1", postingSetId, ledgerId.value, 1L, "asset-payment-local", -1_000L, cny.code, cny.precision.toLong())
        driver.execute(
            null,
            "INSERT OR REPLACE INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ('$transactionId','${ledgerId.value}','$versionId')",
            0,
        )
    }

    private fun seedVoidFact(
        driver: JdbcSqliteDriver,
        transactionId: String,
    ) {
        // The fact's FK references transaction_void_request, so the request row is seeded first
        // (mirrors the 07.T projection test's seedVoidFact).
        driver.execute(
            null,
            "INSERT INTO transaction_void_request(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker) " +
                "VALUES ('${ledgerId.value}','request-$transactionId','$transactionId','void','mis_entered',NULL,'explicit_manual_save')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO transaction_void_fact(ledger_id, transaction_id, sequence, fact_id, fact_kind, reason_code, reason_note, request_id, confirmation_id, created_at) " +
                "VALUES ('${ledgerId.value}','$transactionId',1,'fact-$transactionId','void','mis_entered',NULL,'request-$transactionId','confirmation-$transactionId','2026-03-08T00:00:00Z')",
            0,
        )
    }

    private fun migrationProperties(): Properties =
        Properties().apply {
            setProperty("foreign_keys", "true")
        }
}

private fun SqlDelightBudgetStore.setLimitMonitored(
    ledgerId: LedgerId,
    month: YearMonth,
    scope: BudgetScope,
    limitMinorUnits: Long,
) {
    val request =
        com.unifiedledger.application.BudgetCommandRequest(
            target =
                BudgetTarget(
                    ledgerId = ledgerId,
                    monthKey = budgetMonthConfigKey(month),
                    currency = CurrencyUnit("CNY", 2),
                    scopeKey = budgetScopeKey(scope),
                    scopeCategoryId = (scope as? BudgetScope.Category)?.categoryId,
                ),
            requestId = com.unifiedledger.application.BudgetRequestId("request-${budgetScopeKey(scope)}-set"),
            requestSnapshot = "snapshot-${budgetScopeKey(scope)}-set",
            inputFingerprint = "fingerprint",
            expectedRevision =
                load(
                    BudgetTarget(
                        ledgerId = ledgerId,
                        monthKey = budgetMonthConfigKey(month),
                        currency = CurrencyUnit("CNY", 2),
                        scopeKey = budgetScopeKey(scope),
                        scopeCategoryId = (scope as? BudgetScope.Category)?.categoryId,
                    ),
                )?.revision ?: 0L,
            command =
                com.unifiedledger.application.BudgetCommandPayload
                    .SetLimit(limitMinorUnits),
            createdAt = Instant.parse("2026-03-05T02:00:00Z"),
        )
    commitOnce(request) { BudgetId("budget-${budgetScopeKey(scope)}") }
}

private fun SqlDelightBudgetStore.closeBudget(
    ledgerId: LedgerId,
    month: YearMonth,
    scope: BudgetScope,
) {
    val target =
        BudgetTarget(
            ledgerId = ledgerId,
            monthKey = budgetMonthConfigKey(month),
            currency = CurrencyUnit("CNY", 2),
            scopeKey = budgetScopeKey(scope),
            scopeCategoryId = (scope as? BudgetScope.Category)?.categoryId,
        )
    val request =
        com.unifiedledger.application.BudgetCommandRequest(
            target = target,
            requestId = com.unifiedledger.application.BudgetRequestId("request-${budgetScopeKey(scope)}-close"),
            requestSnapshot = "snapshot-${budgetScopeKey(scope)}-close",
            inputFingerprint = "fingerprint",
            expectedRevision = load(target)?.revision ?: 0L,
            command = com.unifiedledger.application.BudgetCommandPayload.Close,
            createdAt = Instant.parse("2026-03-05T03:00:00Z"),
        )
    commitOnce(request) { BudgetId("budget-${budgetScopeKey(scope)}") }
}
