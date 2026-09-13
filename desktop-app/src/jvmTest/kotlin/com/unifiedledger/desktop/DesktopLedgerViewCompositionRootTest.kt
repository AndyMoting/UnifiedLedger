package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CreationEntry
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.MonthlyActivityResult
import com.unifiedledger.application.MonthlyTrendResult
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.ui.P503AppState
import com.unifiedledger.ui.P503Reducer
import com.unifiedledger.ui.P503ReducerImpl
import com.unifiedledger.ui.P503Tab
import com.unifiedledger.ui.P503UiEvent
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-03.C/D composition-root wiring (D-145 batches C/D; spec sections 4.2/6.1/7): the ledger
 * view read surface (entry rows, unified monthly projection, read-only detail) is reachable
 * through the wired facade, the monthly card values come from the product write chain, a
 * manual expense resolves as 手工创建 with 无对账资格 legs (C03, spec 3.2.1) and an absent
 * category on the real-account leg (P703SPEC-09), unknown detail requests fail typed (C04),
 * and the reducer preserves the selected month across detail open/close and tab switches.
 */
class DesktopLedgerViewCompositionRootTest {
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
    fun facadeExposesTheLedgerViewReadSurface() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            assertTrue(graph.facade.queryLedgerEntryRows != null)
            assertTrue(graph.facade.queryMonthlyActivity != null)
            assertTrue(graph.facade.queryTransactionDetail != null)
        } finally {
            driver.close()
        }
    }

    @Test
    fun monthlyCardReadsOrdinaryTotalsThroughTheWiredChain() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            submitExpense(graph, "request-ledger-view-1", 20_500L)
            submitExpense(graph, "request-ledger-view-2", 10_000L)

            val january = YearMonth(2026, 1)
            val activity = assertIs<MonthlyActivityResult.Success>(graph.facade.queryMonthlyActivity!!.query(january)).activity
            val cnyRow = activity.currencies.single()
            assertEquals(0L, cnyRow.ordinaryIncomeMinorUnits)
            assertEquals(30_500L, cnyRow.netExpenseMinorUnits)
            // 结余 = ordinary income - net expense (frozen formula; never an account balance).
            assertEquals(-30_500L, cnyRow.balanceMinorUnits)
            assertEquals(30_500L, cnyRow.positiveExpenseMinorUnits)
            assertEquals(0L, cnyRow.refundMinorUnits)
            assertEquals(2, cnyRow.transactionCount)

            // Level-1 total equals the sum of its level-2 children (R-Q07-2); the seeded
            // catalog maps the expense leaf 餐饮/早餐 onto the hidden expense posting account.
            val group = activity.expenseCategories.single()
            assertEquals("餐饮", group.categoryName)
            assertEquals(30_500L, group.totals.single().positiveMinorUnits)
            val leaf = group.children.single()
            assertEquals("早餐", leaf.categoryName)
            assertEquals(30_500L, leaf.totals.single().positiveMinorUnits)
        } finally {
            driver.close()
        }
    }

    @Test
    fun trendWindowIsTwelveContiguousMonthsOldToNew() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val trend = assertIs<MonthlyTrendResult.Success>(graph.facade.queryMonthlyActivity!!.trend()).trend
            assertEquals(12, trend.window.size)
            assertEquals(trend.window, trend.months.map { it.month })
            // Old to new: the window equals its own ascending sort (R-Q07-1).
            assertEquals(trend.window, trend.window.sorted())
        } finally {
            driver.close()
        }
    }

    @Test
    fun detailResolvesCreationEntryLegsAndTheIneligibleRollup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            submitExpense(graph, "request-ledger-view-detail", 20_500L)

            val rows = graph.facade.queryLedgerEntryRows!!.query()
            assertEquals(1, rows.size)
            val transactionId = rows.single().transactionId

            val detail =
                assertIs<TransactionDetailResult.Success>(graph.facade.queryTransactionDetail!!.query(transactionId)).detail
            assertEquals(TransactionKind.EXPENSE, detail.kind)
            assertEquals(Instant.parse("2026-01-15T00:30:00Z"), detail.occurredAt)
            assertEquals(Instant.parse("2026-01-15T00:30:00Z"), detail.statisticsAt)
            // The manual four-chain receipt resolves the creation entry (C03); evidence links
            // are never consulted.
            assertEquals(CreationEntry.MANUAL_CREATED, detail.creationEntry)
            assertEquals(2, detail.legs.size)

            // A manual P7-02 chain writes no rg03 row, no evidence link and no reconciliation
            // row: every leg is ineligible and the rollup is 无对账资格 (null, spec 3.2.1).
            assertTrue(detail.reconciliation.legs.all { !it.eligible })
            assertTrue(detail.reconciliation.legs.all { it.status == null })
            assertNull(detail.reconciliation.rollup)
        } finally {
            driver.close()
        }
    }

    @Test
    fun detailReturnsTypedNotFoundForUnknownTransactions() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            submitExpense(graph, "request-ledger-view-notfound", 20_500L)
            assertEquals(
                TransactionDetailResult.NotFound,
                graph.facade.queryTransactionDetail!!.query(TransactionId("tx-does-not-exist")),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun monthSelectionSurvivesDetailOpenCloseAndTabSwitches() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            submitExpense(graph, "request-ledger-view-month", 20_500L)
            val overviewState = assertIs<LedgerCurrentStateResult.Success>(graph.facade.queryCurrentState.query()).state
            val reducer: P503Reducer = P503ReducerImpl(ParseManualExpenseAmount(), graph.facade.currency)
            val january = YearMonth(2026, 1)
            val source =
                P503AppState.OverviewEmpty(
                    state = overviewState,
                    selectedTab = P503Tab.HOME,
                    selectedMonth = january,
                    selectableMonths = listOf(january, YearMonth(2026, 2)),
                )

            var state: P503AppState =
                reducer.reduce(source, P503UiEvent.SelectTransaction(TransactionId("tx-1"), TransactionDetailResult.NotFound))
            val detail = assertIs<P503AppState.TransactionDetail>(state)
            assertSame(source, detail.overview)

            // CloseTransactionDetail returns to the original overview (same tab and month, C03).
            state = reducer.reduce(state, P503UiEvent.CloseTransactionDetail)
            assertSame(source, state)
            assertEquals(january, assertIs<P503AppState.OverviewEmpty>(state).selectedMonth)

            // Tab switches preserve the month selection and the payload slot.
            state = reducer.reduce(state, P503UiEvent.SelectTab(P503Tab.ANALYSIS))
            assertEquals(january, assertIs<P503AppState.OverviewEmpty>(state).selectedMonth)
            assertEquals(P503Tab.ANALYSIS, assertIs<P503AppState.OverviewEmpty>(state).selectedTab)
            state = reducer.reduce(state, P503UiEvent.SelectTab(P503Tab.HOME))
            assertEquals(january, assertIs<P503AppState.OverviewEmpty>(state).selectedMonth)
        } finally {
            driver.close()
        }
    }
}
