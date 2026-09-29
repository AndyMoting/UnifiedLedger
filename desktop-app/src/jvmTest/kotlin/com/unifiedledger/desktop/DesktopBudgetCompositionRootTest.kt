package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.BudgetMonthViewResult
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.BudgetScope
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.ui.P503LedgerFacade
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-07 07.D composition-root wiring evidence (D-184; spec sections 4/5/5.1): the budget month
 * read ([P503LedgerFacade.queryBudgetMonth]) and the configuration write
 * ([P503LedgerFacade.saveBudgetConfiguration]) are wired on the real SqlDelight graph, the
 * month view reflects the product write chain over ONE bounded read, an unconfigured month is
 * a successful empty view, and a read asked with a stale catalog generation fails typed instead
 * of rendering a zero execution amount (composition ruling B). All data synthetic and anonymous.
 */
class DesktopBudgetCompositionRootTest {
    private val cny = CurrencyUnit("CNY", 2)

    private fun submitExpense(
        graph: DesktopLedgerGraph,
        requestId: String,
        minorUnits: Long,
    ) {
        val result =
            graph.facade.submitExpense.submit(
                ManualExpenseSaveInput(
                    ledgerId = graph.ledgerId,
                    requestId = RequestId(requestId),
                    amount = Money.ofMinor(minorUnits, cny),
                    categoryId = graph.categoryId,
                    paymentAccountId = graph.paymentAccountId,
                    occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
                    note = "",
                    confirmation = ExplicitManualSave,
                ),
            )
        val application = assertIs<ManualExpenseSubmissionResult.Application>(result)
        assertIs<ManualExpenseSaveResult.Executed>(application.result)
    }

    @Test
    fun facadeExposesTheBudgetSurfaceOnTheRealGraph() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            assertNotNull(graph.facade.queryBudgetMonth)
            assertNotNull(graph.facade.saveBudgetConfiguration)
            assertNotNull(graph.facade.budgetAuthorityReader)
            assertNotNull(graph.facade.budgetExpectedCatalogVersion())
        } finally {
            driver.close()
        }
    }

    @Test
    fun budgetMonthViewReflectsTheProductWriteChainAndPreservesTheMonth() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            submitExpense(graph, "request-budget-1", 20_500L)
            // Add the monitored TOTAL budget (30.00) for January through the wired 07.B write.
            val january = YearMonth(2026, 1)
            assertIs<com.unifiedledger.application.BudgetCommandResult.Accepted>(
                graph.facade.saveBudgetConfiguration!!.setLimit(graph.ledgerId, january, BudgetScope.Total, 30_000L, expectedRevision = 0L),
            )

            val expectedVersion = graph.facade.budgetExpectedCatalogVersion()!!
            val januaryResult = graph.facade.queryBudgetMonth!!.queryView(graph.ledgerId, january, expectedVersion)
            val view = assertIs<BudgetMonthViewResult.Success>(januaryResult).view
            val total = assertNotNull(view.total)
            assertEquals(20_500L, total.budgetMonth.netExpenseMinorUnits)
            assertEquals(9_500L, total.budgetMonth.remainingMinorUnits)
            assertEquals(0L, total.budgetMonth.overspentMinorUnits)
            // February carries no transactions and no configuration: a successful EMPTY view,
            // never a fabricated zero or a failure (the month cursor is preserved by the caller).
            val februaryResult = graph.facade.queryBudgetMonth!!.queryView(graph.ledgerId, YearMonth(2026, 2), expectedVersion)
            val february = assertIs<BudgetMonthViewResult.Success>(februaryResult).view
            assertNull(february.total)
            assertTrue(february.categories.isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun aStaleCatalogGenerationFailsTypedInsteadOfRenderingZeros() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            submitExpense(graph, "request-budget-2", 5_000L)
            // A caller holding a stale generation (e.g. the session refreshed after it loaded)
            // gets the typed failure, never a zero execution amount (composition ruling B).
            val staleResult = graph.facade.queryBudgetMonth!!.queryView(graph.ledgerId, YearMonth(2026, 1), expectedCatalogVersion = 99L)
            assertEquals(BudgetMonthViewResult.InvalidState, assertIs(staleResult))
        } finally {
            driver.close()
        }
    }
}
