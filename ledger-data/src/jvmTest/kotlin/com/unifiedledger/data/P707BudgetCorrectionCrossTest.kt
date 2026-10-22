package com.unifiedledger.data

import com.unifiedledger.application.BudgetAuthority
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetIdSource
import com.unifiedledger.application.BudgetMonth
import com.unifiedledger.application.BudgetMonthConfigReader
import com.unifiedledger.application.BudgetMonthResult
import com.unifiedledger.application.BudgetRequestId
import com.unifiedledger.application.BudgetRequestIdSource
import com.unifiedledger.application.BudgetSettingsVersion
import com.unifiedledger.application.BudgetTarget
import com.unifiedledger.application.CorrectTransactionVersionResult
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ExplicitlyConfirmedTransactionCorrection
import com.unifiedledger.application.LedgerEntryRow
import com.unifiedledger.application.MonthlyBuckets
import com.unifiedledger.application.MonthlyContributionReadResult
import com.unifiedledger.application.QueryBudgetMonth
import com.unifiedledger.application.SaveBudgetConfiguration
import com.unifiedledger.application.VoidTransactionRequest
import com.unifiedledger.application.VoidTransactionResult
import com.unifiedledger.application.budgetMonthConfigKey
import com.unifiedledger.application.budgetMonthKey
import com.unifiedledger.application.budgetScopeKey
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.VoidReason
import com.unifiedledger.domain.VoidReasonCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 acceptance vector A04 (plan section 7.3 line 249; D-237 item 4; budget design spec
 * sections 2.1/6.4 items 4 and 6): an amount, category or statistics-month correction, a void
 * and a restore are each observed exactly once — through the CURRENT effective version only —
 * and the source times plus the reconciliation/evidence owners stay unchanged.
 *
 * Every correction/void/restore vector drives the real P7-05 ports; every vector reads through
 * the real budget read (bounded contribution adapter + [QueryBudgetMonth]) over one file-backed
 * database, so the assertions pin the frozen SQL mechanics rather than a Kotlin re-statement of
 * them:
 *  - `monthlyContributionRowsInWindow` joins `ledger_transaction_current_version` and filters
 *    `transaction_effective_state.is_effective = 1` (Ledger.sq projection query). Reverting the
 *    current-version join to "any version" would count the superseded 10 000 version as well
 *    and make vector 1 read 18 000 instead of 8 000; dropping the effective predicate would
 *    make a voided transaction count in vector 3.
 *  - The read window rides `statistics_at_epoch_nanos`, so a statistics-month correction moves
 *    the whole current version out of its old month and into the new one with no residual row
 *    in either month (vector 2).
 *  - `SqlDelightTransactionCorrectionCommitPort` copies `occurred_at`/`effective_at` verbatim
 *    and lets only `statistics_at` follow the request; vector 1 asserts all three columns.
 *
 * All ids, amounts and instants are anonymous synthetic values.
 */
class P707BudgetCorrectionCrossTest {
    private val march = YearMonth(2026, 3)
    private val april = YearMonth(2026, 4)

    @Test
    fun sameMonthAmountCorrectionCountsTheCurrentVersionOnce() {
        withBudgetLedger { surface ->
            surface.harness.insertOrdinaryExpense("tx-budget-cross", amountMinor = 10_000L)
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Total, 50_000L, expectedRevision = 0L))
            val before = surface.month(march, BudgetScope.Total)
            assertEquals(50_000L, before.limitMinorUnits)
            assertEquals(10_000L, before.netExpenseMinorUnits)
            assertEquals(40_000L, before.remainingMinorUnits)

            val ownersBefore = surface.ownerCounts()
            assertTrue(ownersBefore.isNotEmpty(), "the reconciliation/evidence tables must exist to be counted")
            assertEquals(0L, ownersBefore.values.sum())

            val ids = P705Ids("p707-a04-correct")
            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    surface.correct(
                        ids = ids,
                        statisticsAt = Instant.parse("2026-03-06T02:00:00Z"),
                        amountMinor = 8_000L,
                    ),
                )

            // Only the current version counts: 8 000, not 8 000 + the superseded 10 000.
            val corrected = surface.month(march, BudgetScope.Total)
            assertEquals(50_000L, corrected.limitMinorUnits)
            assertEquals(8_000L, corrected.netExpenseMinorUnits)
            assertEquals(42_000L, corrected.remainingMinorUnits)
            assertEquals(2L, surface.harness.versionCount("tx-budget-cross"))
            assertEquals(created.receipt.versionId.value, surface.harness.currentVersionId("tx-budget-cross"))

            // Source times of the superseded version are untouched; the new version copies
            // occurred_at/effective_at verbatim and only statistics_at follows the request.
            assertEquals(
                listOf("2026-03-05T02:00:00Z|2026-03-05T02:00:00Z|2026-03-05T02:00:00Z"),
                surface.harness.ledgerQueryTexts(
                    "SELECT occurred_at || '|' || statistics_at || '|' || effective_at FROM transaction_version " +
                        "WHERE version_id = 'tx-budget-cross-version-1'",
                ),
            )
            assertEquals(
                listOf("2026-03-05T02:00:00Z|2026-03-06T02:00:00Z|2026-03-05T02:00:00Z"),
                surface.harness.ledgerQueryTexts(
                    "SELECT occurred_at || '|' || statistics_at || '|' || effective_at FROM transaction_version " +
                        "WHERE version_id = '${created.receipt.versionId.value}'",
                ),
            )

            // The correction touches no reconciliation/evidence row (plan section 7.3 A04;
            // D-237 item 1).
            assertEquals(ownersBefore, surface.ownerCounts())
        }
    }

    @Test
    fun statisticsMonthCorrectionMovesTheCurrentVersionAndLeavesNoOldVersionInEitherMonth() {
        withBudgetLedger { surface ->
            surface.harness.insertOrdinaryExpense("tx-budget-cross", amountMinor = 10_000L)
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Total, 50_000L, expectedRevision = 0L))
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(april, BudgetScope.Total, 50_000L, expectedRevision = 0L))

            val ids = P705Ids("p707-a04-move")
            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    surface.correct(
                        ids = ids,
                        statisticsAt = P705Fixture.aprilStatistics,
                        amountMinor = 8_000L,
                    ),
                )

            // March is a genuine zero (Success), not Unavailable/InvalidState, and its limit is
            // still monitored; April now observes the whole current version.
            val marchRead = surface.month(march, BudgetScope.Total)
            assertEquals(0L, marchRead.netExpenseMinorUnits)
            assertEquals(50_000L, marchRead.limitMinorUnits)
            assertEquals(50_000L, marchRead.remainingMinorUnits)
            val aprilRead = surface.month(april, BudgetScope.Total)
            assertEquals(8_000L, aprilRead.netExpenseMinorUnits)
            assertEquals(42_000L, aprilRead.remainingMinorUnits)

            // The bounded read moves with the projection: no row in March, exactly the new
            // version in April (the superseded version is not in either month).
            assertTrue(surface.monthRows(march).isEmpty())
            val aprilRows = surface.monthRows(april)
            assertEquals(listOf("tx-budget-cross"), aprilRows.map { it.transactionId.value })
            assertEquals(created.receipt.versionId, aprilRows.single().currentVersionId)
        }
    }

    @Test
    fun voidAndRestoreExcludeAndReturnTheCurrentVersionExactlyOnce() {
        withBudgetLedger { surface ->
            surface.harness.insertOrdinaryExpense("tx-budget-cross", amountMinor = 10_000L)
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Total, 50_000L, expectedRevision = 0L))
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(april, BudgetScope.Total, 50_000L, expectedRevision = 0L))
            val ids = P705Ids("p707-a04-void")
            assertIs<CorrectTransactionVersionResult.Created>(
                surface.correct(ids = ids, statisticsAt = P705Fixture.aprilStatistics, amountMinor = 8_000L),
            )

            val versionsBefore = surface.harness.versionCount("tx-budget-cross")
            val postingsBefore = surface.harness.ledgerQueryCount("SELECT count(*) FROM posting")

            val voided =
                assertIs<VoidTransactionResult.Created>(
                    ExecuteVoidTransaction(surface.harness.voidPort, ids.voidSource, fixedClock(P705Fixture.voidedAt))
                        .execute(
                            VoidTransactionRequest(
                                ledgerId = surface.ledgerId,
                                requestId = ids.requestId(),
                                transactionId = TransactionId("tx-budget-cross"),
                                reason = VoidReason(VoidReasonCode.MIS_ENTERED, "wrong amount"),
                                confirmation = ExplicitManualSave,
                            ),
                        ),
                )
            assertEquals("tx-budget-cross", voided.receipt.transactionId.value)
            // A void removes the transaction from both months without touching any row.
            assertEquals(0L, surface.month(march, BudgetScope.Total).netExpenseMinorUnits)
            assertEquals(0L, surface.month(april, BudgetScope.Total).netExpenseMinorUnits)
            assertEquals(50_000L, surface.month(april, BudgetScope.Total).remainingMinorUnits)
            assertEquals(versionsBefore, surface.harness.versionCount("tx-budget-cross"))
            assertEquals(postingsBefore, surface.harness.ledgerQueryCount("SELECT count(*) FROM posting"))

            val restored =
                assertIs<VoidTransactionResult.Created>(
                    ExecuteRestoreTransaction(
                        surface.harness.voidPort,
                        ids.voidSource,
                        fixedClock(Instant.parse("2026-05-01T01:00:00Z")),
                        surface.catalogStore,
                    ).execute(
                        VoidTransactionRequest(
                            ledgerId = surface.ledgerId,
                            requestId = ids.requestId(),
                            transactionId = TransactionId("tx-budget-cross"),
                            reason = VoidReason(VoidReasonCode.VOIDED_IN_ERROR, "restore"),
                            confirmation = ExplicitManualSave,
                        ),
                    ),
                )
            assertEquals("tx-budget-cross", restored.receipt.transactionId.value)
            // Restore re-surfaces exactly one current version in April and none in March.
            assertEquals(1, surface.monthRows(april).size)
            assertEquals(8_000L, surface.month(april, BudgetScope.Total).netExpenseMinorUnits)
            assertEquals(42_000L, surface.month(april, BudgetScope.Total).remainingMinorUnits)
            assertEquals(0L, surface.month(march, BudgetScope.Total).netExpenseMinorUnits)
            assertEquals(versionsBefore, surface.harness.versionCount("tx-budget-cross"))
            assertEquals(postingsBefore, surface.harness.ledgerQueryCount("SELECT count(*) FROM posting"))
        }
    }

    @Test
    fun categoryCorrectionMovesTheExpenseBetweenCategoryScopes() {
        withBudgetLedger { surface ->
            surface.harness.insertOrdinaryExpense("tx-budget-cross", amountMinor = 10_000L)
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Total, 50_000L, expectedRevision = 0L))
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Category(P705Fixture.food), 30_000L, expectedRevision = 0L))
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Category(foodOther), 30_000L, expectedRevision = 0L))

            assertEquals(10_000L, surface.month(march, BudgetScope.Category(P705Fixture.food)).netExpenseMinorUnits)
            assertEquals(0L, surface.month(march, BudgetScope.Category(foodOther)).netExpenseMinorUnits)
            assertEquals(10_000L, surface.month(march, BudgetScope.Total).netExpenseMinorUnits)

            // The two category budgets own their identity and immutable history independently of
            // the ledger: a ledger correction must leave both byte-for-byte equal (spec 3.4/3.5).
            val foodIdentity = surface.identity(march, BudgetScope.Category(P705Fixture.food))
            val otherIdentity = surface.identity(march, BudgetScope.Category(foodOther))
            val foodHistory = surface.history(march, BudgetScope.Category(P705Fixture.food))
            val otherHistory = surface.history(march, BudgetScope.Category(foodOther))
            val ownersBefore = surface.ownerCounts()

            val ids = P705Ids("p707-a04-category")
            val created =
                assertIs<CorrectTransactionVersionResult.Created>(
                    surface.correct(
                        ids = ids,
                        statisticsAt = P705Fixture.marchStatistics,
                        amountMinor = 8_000L,
                        categoryId = foodOther,
                    ),
                )

            // The expense moves whole: the old leaf is a genuine zero, the new leaf carries the
            // current version, and TOTAL follows (it is independent of the category limits).
            val food = surface.month(march, BudgetScope.Category(P705Fixture.food))
            assertEquals(0L, food.netExpenseMinorUnits)
            assertEquals(30_000L, food.remainingMinorUnits)
            val other = surface.month(march, BudgetScope.Category(foodOther))
            assertEquals(8_000L, other.netExpenseMinorUnits)
            assertEquals(22_000L, other.remainingMinorUnits)
            val total = surface.month(march, BudgetScope.Total)
            assertEquals(8_000L, total.netExpenseMinorUnits)
            assertEquals(42_000L, total.remainingMinorUnits)
            // The superseded 10 000 version is NOT counted anywhere (a second, stale fold would
            // make the old leaf 10 000 and TOTAL 18 000).
            assertEquals(2L, surface.harness.versionCount("tx-budget-cross"))
            assertEquals(created.receipt.versionId.value, surface.harness.currentVersionId("tx-budget-cross"))
            assertEquals(created.receipt.versionId, surface.monthRows(march).single().currentVersionId)
            // Both category budgets stay independently readable with the SAME identity/history.
            assertEquals(foodIdentity, surface.identity(march, BudgetScope.Category(P705Fixture.food)))
            assertEquals(otherIdentity, surface.identity(march, BudgetScope.Category(foodOther)))
            assertEquals(foodHistory, surface.history(march, BudgetScope.Category(P705Fixture.food)))
            assertEquals(otherHistory, surface.history(march, BudgetScope.Category(foodOther)))
            // A correction appends a posting set, but never a reconciliation/evidence row.
            assertEquals(ownersBefore, surface.ownerCounts())
        }
    }

    @Test
    fun budgetCommandsHaveZeroLedgerSideEffects() {
        withBudgetLedger { surface ->
            surface.harness.insertOrdinaryExpense("tx-budget-cross", amountMinor = 10_000L)
            assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Total, 50_000L, expectedRevision = 0L))

            val before = surface.sideEffectCounts()
            assertEquals(1L, before.getValue("ledger_transaction"))
            assertEquals(1L, before.getValue("posting_set"))
            assertEquals(2L, before.getValue("posting"))

            // Real modify and close commands: both append history only.
            val modified = assertIs<BudgetCommandResult.Accepted>(surface.setLimit(march, BudgetScope.Total, 120_000L, expectedRevision = 1L))
            assertEquals(2L, modified.receipt.newRevision)
            val closed = assertIs<BudgetCommandResult.Accepted>(surface.close(march, BudgetScope.Total, expectedRevision = 2L))
            assertEquals(3L, closed.receipt.newRevision)

            assertEquals(before, surface.sideEffectCounts())
            // Positive control: the settings history proves the commands actually ran.
            assertTrue(
                surface.harness.ledgerQueryCount("SELECT count(*) FROM budget_settings_history") >= 3L,
                "the three accepted commands must each append one history row",
            )
            assertEquals(1L, surface.harness.ledgerQueryCount("SELECT count(*) FROM budget_config"))
        }
    }

    private fun withBudgetLedger(block: (BudgetSurface) -> Unit) {
        P705Database.create("p707-a04-").use { harness ->
            val catalogStore = SqlDelightCatalogStore.forPlatformConfiguredDatabase(harness.database)
            assertIs<CatalogBootstrapResult.Seeded>(catalogStore.bootstrap(P705Fixture.ledgerId, budgetCatalogSeed()))
            block(BudgetSurface(harness, catalogStore))
        }
    }

    /** The real budget surface over one file-backed database. */
    private class BudgetSurface(
        val harness: P705Database,
        val catalogStore: SqlDelightCatalogStore,
    ) {
        val ledgerId: LedgerId = P705Fixture.ledgerId
        val catalogVersion: Long = requireNotNull(catalogStore.load(ledgerId)).catalogVersion
        val budgetStore: SqlDelightBudgetStore = SqlDelightBudgetStore.forPlatformConfiguredDatabase(harness.database)
        val readAdapter = SqlDelightMonthlyContributionReadAdapter(harness.database, catalogStore)
        val query =
            QueryBudgetMonth(
                readPort = readAdapter,
                authorityReader = budgetStore,
                configReader = BudgetMonthConfigReader { ledger, month -> budgetStore.configsForMonth(ledger, budgetMonthConfigKey(month)) },
            )
        private var mintCounter = 0

        fun setLimit(
            month: YearMonth,
            scope: BudgetScope,
            limitMinorUnits: Long,
            expectedRevision: Long,
        ): BudgetCommandResult = command().setLimit(ledgerId, month, scope, limitMinorUnits, expectedRevision)

        fun close(
            month: YearMonth,
            scope: BudgetScope,
            expectedRevision: Long,
        ): BudgetCommandResult = command().close(ledgerId, month, scope, expectedRevision)

        /** One observation; every vector requires a Success, never a fabricated zero. */
        fun month(
            month: YearMonth,
            scope: BudgetScope,
        ): BudgetMonth = assertIs<BudgetMonthResult.Success>(query.query(ledgerId, month, scope, catalogVersion)).budgetMonth

        /** The current effective rows the bounded read returns for one Shanghai month. */
        fun monthRows(month: YearMonth): List<LedgerEntryRow> {
            val start = MonthlyBuckets.monthStart(month)
            val end = MonthlyBuckets.monthStart(month.plus(1, DateTimeUnit.MONTH))
            return assertIs<MonthlyContributionReadResult.Success>(readAdapter.readContributions(ledgerId, start, end, catalogVersion)).rows
        }

        fun correct(
            ids: P705Ids,
            statisticsAt: Instant,
            amountMinor: Long,
            categoryId: CategoryId = P705Fixture.food,
            transactionId: String = "tx-budget-cross",
        ): CorrectTransactionVersionResult =
            ExecuteCorrectTransactionVersion(harness.correctionPort, ids.correctSource, catalogStore)
                .execute(
                    ExplicitlyConfirmedTransactionCorrection(
                        ledgerId = ledgerId,
                        requestId = ids.requestId(),
                        transactionId = TransactionId(transactionId),
                        expectedCurrentVersionId = TransactionVersionId("$transactionId-version-1"),
                        note = "corrected",
                        statisticsAt = statisticsAt,
                        amount = Money.ofMinor(amountMinor, P705Fixture.cny),
                        categoryId = categoryId,
                        fundingAccountId = P705Fixture.bankA,
                        confirmation = ExplicitManualSave,
                    ),
                )

        /** The current configuration authority of one scope (identity + CAS revision + limit). */
        fun identity(
            month: YearMonth,
            scope: BudgetScope,
        ): BudgetAuthority =
            requireNotNull(budgetStore.load(target(month, scope))) {
                "the budget identity for $scope in $month must exist"
            }

        /** The full immutable settings history of one scope, oldest revision first. */
        fun history(
            month: YearMonth,
            scope: BudgetScope,
        ): List<BudgetSettingsVersion> {
            val authority = identity(month, scope)
            return budgetStore.settingsHistory(ledgerId, authority.budgetId)
        }

        /** The 07.B configuration identity the read targets (spec section 3.1). */
        private fun target(
            month: YearMonth,
            scope: BudgetScope,
        ): BudgetTarget =
            BudgetTarget(
                ledgerId = ledgerId,
                monthKey = budgetMonthKey(month),
                currency = P705Fixture.cny,
                scopeKey = budgetScopeKey(scope),
                scopeCategoryId = (scope as? BudgetScope.Category)?.categoryId,
            )

        /** The reconciliation/evidence owners a correction must never touch. */
        fun ownerCounts(): Map<String, Long> =
            ownerTables().associateWith { table -> harness.ledgerQueryCount("SELECT count(*) FROM $table") }

        /** Ledger rows plus the reconciliation/evidence owners the budget commands must never touch. */
        fun sideEffectCounts(): Map<String, Long> =
            (
                listOf("ledger_transaction", "transaction_version", "ledger_transaction_current_version", "posting_set", "posting") +
                    ownerTables()
            ).associateWith { table -> harness.ledgerQueryCount("SELECT count(*) FROM $table") }

        /** Every reconciliation/evidence table family, including the rgXX silos. */
        private fun ownerTables(): List<String> =
            harness.ledgerQueryTexts(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND (name LIKE '%posting_reconciliation%' OR name LIKE '%evidence_link%') ORDER BY name",
            )

        private fun command(): SaveBudgetConfiguration {
            mintCounter += 1
            val sequence = mintCounter
            return SaveBudgetConfiguration(
                commitPort = budgetStore,
                requestIdSource = BudgetRequestIdSource { BudgetRequestId("p707-a04-request-$sequence") },
                budgetIdSource = BudgetIdSource { BudgetId("p707-a04-budget-$sequence") },
                catalogReader = catalogStore,
                clock = fixedClock(P705Fixture.marchStatistics),
            )
        }
    }
}

/** The second EXPENSE leaf used by the category-correction vector. */
private val foodOther = CategoryId("category-food-other")

private val foodOtherAccount = AccountId("expense-food-other-account")

/**
 * The P705 fixture catalog expressed as the product seed: bootstrap fails closed on any posting
 * reference it does not know, so every account and category a fixture transaction uses must be
 * seeded (extra seed rows are harmless). The second EXPENSE leaf is what the category-correction
 * vector needs; the income side mirrors the fixture so the catalog shape stays the real one.
 */
private fun budgetCatalogSeed(): CatalogSeed =
    CatalogSeed(
        accounts =
            listOf(
                CatalogSeedAccount(P705Fixture.bankA, "银行A", AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                CatalogSeedAccount(P705Fixture.bankB, "银行B", AccountKind.ASSET, P705Fixture.cny, ownedByUser = true, realAccount = true, hidden = false),
                CatalogSeedAccount(P705Fixture.expenseAccount, "餐饮支出", AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = false),
                CatalogSeedAccount(foodOtherAccount, "外食支出", AccountKind.EXPENSE, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = false),
                CatalogSeedAccount(P705Fixture.incomeAccount, "工资收入", AccountKind.INCOME, P705Fixture.cny, ownedByUser = false, realAccount = false, hidden = false),
            ),
        categories =
            listOf(
                CatalogSeedCategory(P705Fixture.foodParent, "餐饮", CategoryKind.EXPENSE, parentId = null, postingAccountId = null),
                CatalogSeedCategory(P705Fixture.food, "餐饮-午餐", CategoryKind.EXPENSE, P705Fixture.foodParent, P705Fixture.expenseAccount),
                CatalogSeedCategory(foodOther, "餐饮-外食", CategoryKind.EXPENSE, P705Fixture.foodParent, foodOtherAccount),
                CatalogSeedCategory(P705Fixture.salaryParent, "收入", CategoryKind.INCOME, parentId = null, postingAccountId = null),
                CatalogSeedCategory(P705Fixture.salary, "收入-工资", CategoryKind.INCOME, P705Fixture.salaryParent, P705Fixture.incomeAccount),
            ),
    )
