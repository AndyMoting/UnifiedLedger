package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P7-02.A S-4 note read-back through the desktop composition root (P702IMPL-01): the expense
 * delegate must pass the draft note into `AssetPaidOrdinaryExpenseCommand` so the committed
 * `transaction_version.note` carries the user-visible note (the same assertion the income
 * composition-root test makes for the income chain).
 */
class DesktopManualExpenseCompositionRootTest {
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun manualExpenseCommitsThroughTheWiredChainAndReadsBackTheNote() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val submission = graph.facade.submitExpense

            val result =
                submission.submit(
                    ManualExpenseSaveInput(
                        ledgerId = graph.ledgerId,
                        requestId = RequestId("request-desktop-expense-1"),
                        amount = Money.ofMinor(20_500L, cny),
                        categoryId = graph.categoryId,
                        paymentAccountId = graph.paymentAccountId,
                        occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
                        note = "office lunch",
                        confirmation = ExplicitManualSave,
                    ),
                )
            val application = assertIs<ManualExpenseSubmissionResult.Application>(result)
            assertIs<ManualExpenseSaveResult.Executed>(application.result)

            val state = assertIs<com.unifiedledger.application.LedgerCurrentStateResult.Success>(graph.facade.queryCurrentState.query()).state
            assertEquals(1, state.transactions.size)
            assertEquals(TransactionKind.EXPENSE, state.transactions.single().kind)

            val versionNote =
                graph.database.ledgerQueries
                    .selectPersistedVersions()
                    .executeAsList()
                    .single()
                    .note
            assertEquals("office lunch", versionNote)
        } finally {
            driver.close()
        }
    }
}
