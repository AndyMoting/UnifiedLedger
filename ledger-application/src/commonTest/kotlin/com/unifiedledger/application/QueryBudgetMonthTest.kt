package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 07.D-1 `QueryBudgetMonth` evidence (D-184 item 3 residual; spec sections 4/5/5.1): ONE
 * bounded read is folded ONCE into every configured scope's observation (TOTAL includes 无分类,
 * a level-1 scope rolls up its level-2 children, scopes are independent and never summed), and
 * the failure family is exactly the typed one (catalog-version mismatch, missing projection,
 * unavailable, invalid state) — never a fabricated zero. All data synthetic and anonymous with
 * fixed instants.
 *
 * The `P707-A0x` tags in comments are intent labels only (D-184 section 7 records no P707
 * vector as PASS).
 */
class QueryBudgetMonthTest {
    private val ledgerId = LedgerId("ledger-budget-query")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetId = AccountId("account-asset")
    private val breakfastExpenseId = AccountId("account-expense-breakfast")
    private val dinnerExpenseId = AccountId("account-expense-dinner")
    private val uncategorizedExpenseId = AccountId("account-expense-uncategorized")
    private val foodParentId = CategoryId("category-food")
    private val breakfastId = CategoryId("category-breakfast")
    private val dinnerId = CategoryId("category-dinner")
    private val march = YearMonth(2026, 3)

    @Test
    fun oneFoldYieldsTotalAndEveryConfiguredCategoryScopeWithIndependentObservations() {
        // P707-A03 intent: breakfast 30 + dinner 40 + uncategorized 5; TOTAL = 75 (includes
        // 无分类)， food parent = 70 (its two children), breakfast = 30. All three scopes come
        // from ONE aggregate of the same rows, and the read runs exactly once.
        val rows =
            listOf(
                expenseRow("tx-breakfast", breakfastExpenseId, 3_000L),
                expenseRow("tx-dinner", dinnerExpenseId, 4_000L),
                expenseRow("tx-uncategorized", uncategorizedExpenseId, 500L),
            )
        val configs =
            listOf(
                config("budget-total", BudgetScope.Total, limit = 75_00L),
                config("budget-food", BudgetScope.Category(foodParentId), limit = 70_00L),
                config("budget-breakfast", BudgetScope.Category(breakfastId), limit = 30_00L),
            )
        val port = RecordingPort(rows)
        val result = useCase(port, configs).queryView(ledgerId, march, expectedCatalogVersion = 7L)
        val view = assertIs<BudgetMonthViewResult.Success>(result).view
        assertEquals(1, port.readCount)
        assertEquals(7L, view.catalogVersion)
        assertEquals(75_00L, view.total?.budgetMonth?.netExpenseMinorUnits)
        assertEquals(0L, view.total?.budgetMonth?.remainingMinorUnits)
        assertEquals(0L, view.total?.budgetMonth?.overspentMinorUnits)
        assertEquals(
            70_00L,
            view.categories
                .first { it.budgetMonth.scope == BudgetScope.Category(foodParentId) }
                .budgetMonth.netExpenseMinorUnits,
        )
        assertEquals(
            30_00L,
            view.categories
                .first { it.budgetMonth.scope == BudgetScope.Category(breakfastId) }
                .budgetMonth.netExpenseMinorUnits,
        )
        // Parent and child are independent observations, never summed.
        assertTrue(view.observations.map { it.budgetMonth.netExpenseMinorUnits }.none { it == 70_00L + 30_00L })
    }

    @Test
    fun theBoundedReadIsIssuedOnceForTheMonthWindow() {
        // Spec section 5.1: the read must be bounded to `[monthStart, nextMonthStart)` and must
        // run once per call, not once per configured scope.
        val port = RecordingPort(listOf(expenseRow("tx-breakfast", breakfastExpenseId, 1_000L)))
        val configs =
            listOf(
                config("budget-total", BudgetScope.Total, 10_00L),
                config("budget-food", BudgetScope.Category(foodParentId), 10_00L),
                config("budget-breakfast", BudgetScope.Category(breakfastId), 10_00L),
            )
        useCase(port, configs).queryView(ledgerId, march, expectedCatalogVersion = 1L)
        assertEquals(1, port.readCount)
        assertEquals(MonthlyBuckets.monthStart(march), port.lastStart)
        assertEquals(MonthlyBuckets.monthStart(YearMonth(2026, 4)), port.lastEnd)
    }

    @Test
    fun refundsReduceNetExpenseAndRemainingIsNeverClamped() {
        // P707-A01/A05 intent (spec section 4: "净退款可使 netExpense 为负、remaining 大于 limit；
        // 金额不得钳到零"). Expense 30, refund 80 -> net -50; a REFUND_RECEIPT's EXPENSE leg is
        // negative per the frozen account-kind dispatch, so the net is genuinely negative. The
        // remaining must then EXCEED the 50 limit (not clamp to 0) and overspent stays 0. If any
        // clamp-to-zero were introduced, the remaining assertion below would go RED.
        val rows =
            listOf(
                expenseRow("tx-expense", breakfastExpenseId, 3_000L),
                expenseRow("tx-refund", breakfastExpenseId, -8_000L, kind = TransactionKind.REFUND_RECEIPT),
            )
        val result =
            useCase(RecordingPort(rows), listOf(config("budget-total", BudgetScope.Total, 50_00L)))
                .query(ledgerId, march, BudgetScope.Total, expectedCatalogVersion = 1L)
        val observation = assertIs<BudgetMonthResult.Success>(result).budgetMonth
        assertEquals(-50_00L, observation.netExpenseMinorUnits)
        assertEquals(0L, observation.overspentMinorUnits)
        assertEquals(10_000L, observation.remainingMinorUnits)
        assertTrue(
            observation.remainingMinorUnits!! > observation.limitMinorUnits!!,
            "a negative net expense must make remaining exceed the limit, never clamp to 0",
        )
    }

    @Test
    fun queryViewPresentsAConfiguredClosedScopeWithANullLimitAndNoRemaining() {
        // Spec sections 3.2/3.3: a CLOSED scope is still a CONFIGURED scope. The month list must
        // show it (present as Success) with limit/remaining/overspent null — configured but not
        // monitored — while a scope with no configuration row is absent from the view entirely.
        val rows = listOf(expenseRow("tx-breakfast", breakfastExpenseId, 1_000L))
        val configs =
            listOf(
                BudgetMonthConfigRow(BudgetId("budget-total"), BudgetScope.Total, revision = 1L, closed = false, limitMinorUnits = 5_00L),
                BudgetMonthConfigRow(BudgetId("budget-closed"), BudgetScope.Category(breakfastId), revision = 2L, closed = true, limitMinorUnits = null),
            )
        val view = assertIs<BudgetMonthViewResult.Success>(useCase(RecordingPort(rows), configs).queryView(ledgerId, march, 1L)).view
        // The closed scope is PRESENT (its configuration exists), not dropped.
        val closed = view.categories.single { it.budgetMonth.scope == BudgetScope.Category(breakfastId) }.budgetMonth
        assertNull(closed.limitMinorUnits)
        assertNull(closed.remainingMinorUnits)
        assertNull(closed.overspentMinorUnits)
        assertEquals(1_000L, closed.netExpenseMinorUnits)
        // A configured TOTAL scope is still present and monitored.
        assertEquals(5_00L, view.total?.budgetMonth?.limitMinorUnits)
        // A scope with no configuration row is absent (never invented).
        assertTrue(view.observations.none { it.budgetMonth.scope == BudgetScope.Category(dinnerId) })
    }

    @Test
    fun closedAndZeroAndUnsetScopesAreDistinguishable() {
        // Spec section 3.3: a monitored zero is a real 0; a CLOSED scope (null limit) is not
        // monitored; an unconfigured scope has no authority row and is not monitored either.
        val rows = listOf(expenseRow("tx-breakfast", breakfastExpenseId, 1_000L))
        val configs =
            listOf(
                BudgetMonthConfigRow(BudgetId("budget-zero"), BudgetScope.Total, revision = 1L, closed = false, limitMinorUnits = 0L),
                BudgetMonthConfigRow(BudgetId("budget-closed"), BudgetScope.Category(breakfastId), revision = 2L, closed = true, limitMinorUnits = null),
            )
        val authorities =
            mapOf(
                budgetScopeKey(BudgetScope.Total) to authority(BudgetId("budget-zero"), BudgetScope.Total, limit = 0L),
                budgetScopeKey(BudgetScope.Category(breakfastId)) to
                    authority(BudgetId("budget-closed"), BudgetScope.Category(breakfastId), limit = null),
            )
        val useCase = useCase(RecordingPort(rows), configs, authorities)
        val zero = assertIs<BudgetMonthResult.Success>(useCase.query(ledgerId, march, BudgetScope.Total, 1L)).budgetMonth
        assertEquals(0L, zero.limitMinorUnits)
        assertEquals(-1_000L, zero.remainingMinorUnits)
        assertEquals(1_000L, zero.overspentMinorUnits)
        val closed =
            assertIs<BudgetMonthResult.Success>(useCase.query(ledgerId, march, BudgetScope.Category(breakfastId), 1L))
                .budgetMonth
        assertNull(closed.limitMinorUnits)
        assertNull(closed.remainingMinorUnits)
        assertNull(closed.overspentMinorUnits)
        assertEquals(1_000L, closed.netExpenseMinorUnits)
        // A scope with no authority row is unconfigured (not monitored), distinct from zero.
        val unset =
            assertIs<BudgetMonthResult.Success>(useCase.query(ledgerId, march, BudgetScope.Category(dinnerId), 1L))
                .budgetMonth
        assertNull(unset.limitMinorUnits)
        assertNull(unset.remainingMinorUnits)
    }

    @Test
    fun anEmptyMonthWithAConfiguredScopeIsAGenuineZeroNotAFailure() {
        val result =
            useCase(RecordingPort(emptyList()), listOf(config("budget-total", BudgetScope.Total, 5_00L)))
                .query(ledgerId, march, BudgetScope.Total, expectedCatalogVersion = 1L)
        val observation = assertIs<BudgetMonthResult.Success>(result).budgetMonth
        assertEquals(0L, observation.netExpenseMinorUnits)
        assertEquals(5_00L, observation.remainingMinorUnits)
        assertEquals(0L, observation.overspentMinorUnits)
    }

    @Test
    fun aCatalogVersionMismatchFromThePortIsTheTypedInvalidStateNotAZero() {
        // Spec section 5.1 / open item 9: a catalog/tx generation mismatch is a typed failure.
        val port =
            MonthlyContributionReadPort { _, _, _, _ ->
                MonthlyContributionReadResult.Failed(MonthlyContributionReadFailure.CatalogVersionMismatch)
            }
        val useCase = QueryBudgetMonth(port, AuthorityMap(emptyMap()), ConfigList(emptyList()))
        assertIs<BudgetMonthResult.InvalidState>(useCase.query(ledgerId, march, BudgetScope.Total, 1L))
        assertIs<BudgetMonthViewResult.InvalidState>(useCase.queryView(ledgerId, march, 1L))
    }

    @Test
    fun aMissingProjectionOrReadFailureIsUnavailableAndNeverZeros() {
        for (failure in listOf(MonthlyContributionReadFailure.MissingProjection, MonthlyContributionReadFailure.Unavailable)) {
            val port = MonthlyContributionReadPort { _, _, _, _ -> MonthlyContributionReadResult.Failed(failure) }
            val useCase = QueryBudgetMonth(port, AuthorityMap(emptyMap()), ConfigList(emptyList()))
            assertIs<BudgetMonthResult.Unavailable>(useCase.query(ledgerId, march, BudgetScope.Total, 1L))
            assertIs<BudgetMonthViewResult.Unavailable>(useCase.queryView(ledgerId, march, 1L))
        }
    }

    @Test
    fun aThrowingReadPortIsUnavailable() {
        val port = MonthlyContributionReadPort { _, _, _, _ -> throw IllegalStateException("database gone") }
        val useCase = QueryBudgetMonth(port, AuthorityMap(emptyMap()), ConfigList(emptyList()))
        assertIs<BudgetMonthResult.Unavailable>(useCase.query(ledgerId, march, BudgetScope.Total, 1L))
        assertIs<BudgetMonthViewResult.Unavailable>(useCase.queryView(ledgerId, march, 1L))
    }

    @Test
    fun aConfiguredScopeAbsentFromTheCatalogIsInvalidStateNotZero() {
        val result =
            useCase(RecordingPort(emptyList()), listOf(config("budget-ghost", BudgetScope.Category(CategoryId("category-ghost")), 10_00L)))
                .query(ledgerId, march, BudgetScope.Category(CategoryId("category-ghost")), expectedCatalogVersion = 1L)
        assertIs<BudgetMonthResult.InvalidState>(result)
    }

    @Test
    fun aCheckedOverflowIsInvalidStateNotAWraparound() {
        val rows =
            listOf(
                expenseRow("tx-max", breakfastExpenseId, Long.MAX_VALUE),
                expenseRow("tx-more", breakfastExpenseId, 5L),
            )
        val result =
            useCase(RecordingPort(rows), listOf(config("budget-total", BudgetScope.Total, 10_00L)))
                .query(ledgerId, march, BudgetScope.Total, expectedCatalogVersion = 1L)
        assertIs<BudgetMonthResult.InvalidState>(result)
    }

    @Test
    fun aMonthWithNoConfiguredScopeYieldsAnEmptySuccessfulView() {
        val result = useCase(RecordingPort(emptyList()), emptyList()).queryView(ledgerId, march, expectedCatalogVersion = 1L)
        val view = assertIs<BudgetMonthViewResult.Success>(result).view
        assertTrue(view.observations.isEmpty())
        assertNull(view.total)
    }

    @Test
    fun aConfigReaderFailureIsUnavailableNotAnEmptyMonth() {
        val useCase =
            QueryBudgetMonth(
                RecordingPort(emptyList()),
                AuthorityMap(emptyMap()),
                BudgetMonthConfigReader { _, _ -> throw IllegalStateException("config read failed") },
            )
        assertIs<BudgetMonthViewResult.Unavailable>(useCase.queryView(ledgerId, march, 1L))
    }

    @Test
    fun anAuthorityReaderFailureIsUnavailableNotAZero() {
        val useCase =
            QueryBudgetMonth(
                RecordingPort(listOf(expenseRow("tx-breakfast", breakfastExpenseId, 1_000L))),
                BudgetAuthorityReader { throw IllegalStateException("authority read failed") },
                ConfigList(emptyList()),
            )
        assertIs<BudgetMonthResult.Unavailable>(useCase.query(ledgerId, march, BudgetScope.Total, 1L))
    }

    @Test
    fun everyConfiguredScopeSharesTheFrozenClassifierResult() {
        // The classifier is reused (spec section 2.2): the use case's per-scope net expense must
        // equal the frozen BudgetOrdinaryNetExpense contribution for the same rows and catalog.
        val rows = listOf(expenseRow("tx-breakfast", breakfastExpenseId, 3_000L))
        val expected =
            BudgetOrdinaryNetExpense
                .contributions(rows, ledgerId, catalog(), listOf(march), BudgetScope.Category(breakfastId))
                .getValue(march)
                .netExpenseByCurrency[cny]
        val result =
            useCase(RecordingPort(rows), listOf(config("budget-breakfast", BudgetScope.Category(breakfastId), 10_00L)))
                .query(ledgerId, march, BudgetScope.Category(breakfastId), 1L)
        assertEquals(expected, assertIs<BudgetMonthResult.Success>(result).budgetMonth.netExpenseMinorUnits)
    }

    // ------------------------------------------------------------------ helpers

    private fun useCase(
        port: MonthlyContributionReadPort,
        configs: List<BudgetMonthConfigRow>,
        authorities: Map<String, BudgetAuthority> = configs.associate { budgetScopeKey(it.scope) to authority(it.budgetId, it.scope, it.limitMinorUnits) },
    ): QueryBudgetMonth = QueryBudgetMonth(port, AuthorityMap(authorities), ConfigList(configs))

    private fun authority(
        budgetId: BudgetId,
        scope: BudgetScope,
        limit: Long?,
    ): BudgetAuthority =
        BudgetAuthority(
            budgetId = budgetId,
            target =
                BudgetTarget(
                    ledgerId = ledgerId,
                    monthKey = budgetMonthKey(march),
                    currency = cny,
                    scopeKey = budgetScopeKey(scope),
                    scopeCategoryId = (scope as? BudgetScope.Category)?.categoryId,
                ),
            revision = 1L,
            limitMinorUnits = limit,
        )

    private fun config(
        budgetId: String,
        scope: BudgetScope,
        limit: Long?,
    ): BudgetMonthConfigRow = BudgetMonthConfigRow(BudgetId(budgetId), scope, revision = 1L, closed = false, limitMinorUnits = limit)

    private fun expenseRow(
        transactionId: String,
        expenseAccountId: AccountId,
        amountMinor: Long,
        kind: TransactionKind = TransactionKind.EXPENSE,
    ): LedgerEntryRow {
        val statisticsAt = "2026-03-10T02:00:00Z"
        val postings =
            listOf(
                Posting(PostingId("posting-$transactionId-expense"), expenseAccountId, Money.ofMinor(amountMinor, cny)),
                Posting(PostingId("posting-$transactionId-payment"), assetId, Money.ofMinor(-amountMinor, cny)),
            )
        return LedgerEntryRow(
            transactionId = TransactionId(transactionId),
            currentVersionId = TransactionVersionId("version-$transactionId"),
            kind = kind,
            occurredAt = Instant.parse(statisticsAt),
            statisticsAt = Instant.parse(statisticsAt),
            note = null,
            postings = postings,
        )
    }

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(breakfastExpenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(dinnerExpenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(uncategorizedExpenseId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(foodParentId, ledgerId, parentId = null, postingAccountId = null, active = true),
                            Category(breakfastId, ledgerId, parentId = foodParentId, postingAccountId = breakfastExpenseId, active = true),
                            Category(dinnerId, ledgerId, parentId = foodParentId, postingAccountId = dinnerExpenseId, active = true),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("test catalog must be valid")
        }

    /** Bounded-read fake that returns the given rows and counts its invocations. */
    private inner class RecordingPort(
        private val rows: List<LedgerEntryRow>,
    ) : MonthlyContributionReadPort {
        var readCount = 0
        var lastStart: Instant? = null
        var lastEnd: Instant? = null

        override fun readContributions(
            ledgerId: LedgerId,
            startInclusive: Instant,
            endExclusive: Instant,
            expectedCatalogVersion: Long,
        ): MonthlyContributionReadResult {
            readCount += 1
            lastStart = startInclusive
            lastEnd = endExclusive
            return MonthlyContributionReadResult.Success(catalogVersion = expectedCatalogVersion, catalog = catalog(), rows = rows)
        }
    }

    private class AuthorityMap(
        private val authorities: Map<String, BudgetAuthority>,
    ) : BudgetAuthorityReader {
        override fun load(target: BudgetTarget): BudgetAuthority? = authorities[target.scopeKey]
    }

    private class ConfigList(
        private val configs: List<BudgetMonthConfigRow>,
    ) : BudgetMonthConfigReader {
        override fun configsFor(
            ledgerId: LedgerId,
            month: YearMonth,
        ): List<BudgetMonthConfigRow> = configs
    }
}
