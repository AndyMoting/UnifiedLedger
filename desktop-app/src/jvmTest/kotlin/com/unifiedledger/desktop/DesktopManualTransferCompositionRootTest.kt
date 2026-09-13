package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ManualTransferSaveInput
import com.unifiedledger.application.ManualTransferSaveResult
import com.unifiedledger.application.ManualTransferSubmissionResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-02.B B01/B04 composition-root evidence: a manual transfer commits through the wired chain,
 * the principal is not income/expense and the fee is an independent ordinary expense, the note
 * reaches the version, and an equivalent replay is idempotent.
 */
class DesktopManualTransferCompositionRootTest {
    private val ledgerId = com.unifiedledger.domain.LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val occurredAt = Instant.parse("2026-01-15T00:30:00Z")

    @Test
    fun manualTransferIsInternalAndItsFeeIsAnIndependentOrdinaryExpense() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            // A second owned real ASSET account so the two ends differ.
            val accepted =
                assertIs<com.unifiedledger.application.CatalogCommandResult.Accepted>(
                    graph.catalogCommands.createAccount(graph.ledgerId, "储蓄卡", com.unifiedledger.domain.AccountKind.ASSET, expectedCatalogVersion = 1L),
                )
            graph.catalogSession.refresh()
            val destination = checkNotNull(accepted.receipt.createdManageableAccountId)
            val source = AccountId("asset-payment-local")
            val feeCategory =
                graph.facade.optionsProvider
                    .queryOptions()
                    .expenseCategories
                    .single()
                    .categoryId
            val submission = checkNotNull(graph.facade.submitEntry)

            val result =
                submission.submit(
                    com.unifiedledger.application.ManualEntrySaveInput.Transfer(
                        ManualTransferSaveInput(
                            ledgerId = graph.ledgerId,
                            requestId = RequestId("request-transfer-desktop-1"),
                            sourceAccountId = source,
                            destinationAccountId = destination,
                            destinationCredit = Money.ofMinor(10_000L, cny),
                            fee = Money.ofMinor(200L, cny),
                            feeCategoryId = feeCategory,
                            occurredAt = occurredAt,
                            note = "rent",
                            confirmation = ExplicitManualSave,
                        ),
                    ),
                )
            val application = assertIs<ManualTransferSubmissionResult.Application>(assertIs<com.unifiedledger.application.ManualEntrySubmissionResult.Transfer>(result).result)
            assertIs<ManualTransferSaveResult.Executed>(application.result)

            val state = assertIs<com.unifiedledger.application.LedgerCurrentStateResult.Success>(graph.facade.queryCurrentState.query()).state
            assertEquals(1, state.transactions.size)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, state.transactions.single().kind)
            // B01: the principal legs are internal; only the fee is an ordinary expense.
            val summary = graph.facade.summarizeActivity.summarize(state)
            val total = summary.totalsByCurrency.single()
            assertEquals(200L, total.expenseMinorUnits)
            assertEquals(0L, total.incomeMinorUnits)

            assertEquals(
                1L,
                graph.database.ledgerQueries
                    .countManualTransferRequests()
                    .executeAsOne(),
            )
            assertEquals(
                "rent",
                graph.database.ledgerQueries
                    .selectPersistedVersions()
                    .executeAsList()
                    .single()
                    .note,
            )

            // B04: equivalent replay returns the original receipt with zero new rows.
            val replay =
                submission.submit(
                    com.unifiedledger.application.ManualEntrySaveInput.Transfer(
                        ManualTransferSaveInput(
                            ledgerId = graph.ledgerId,
                            requestId = RequestId("request-transfer-desktop-1"),
                            sourceAccountId = source,
                            destinationAccountId = destination,
                            destinationCredit = Money.ofMinor(10_000L, cny),
                            fee = Money.ofMinor(200L, cny),
                            feeCategoryId = feeCategory,
                            occurredAt = occurredAt,
                            note = "rent",
                            confirmation = ExplicitManualSave,
                        ),
                    ),
                )
            assertIs<ManualTransferSubmissionResult.Application>(assertIs<com.unifiedledger.application.ManualEntrySubmissionResult.Transfer>(replay).result)
            assertEquals(
                1L,
                graph.database.ledgerQueries
                    .countTransactions()
                    .executeAsOne(),
            )
            assertTrue(graph.facade.resolveTransferCommitStatus != null)
        } finally {
            driver.close()
        }
    }

    @Test
    fun sameAccountTransferIsRejectedWithZeroFormalWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val source = AccountId("asset-payment-local")
            val submission = checkNotNull(graph.facade.submitEntry)
            val result =
                submission.submit(
                    com.unifiedledger.application.ManualEntrySaveInput.Transfer(
                        ManualTransferSaveInput(
                            ledgerId = graph.ledgerId,
                            requestId = RequestId("request-transfer-same"),
                            sourceAccountId = source,
                            destinationAccountId = source,
                            destinationCredit = Money.ofMinor(1_000L, cny),
                            fee = Money.ofMinor(0L, cny),
                            feeCategoryId = null,
                            occurredAt = occurredAt,
                            note = "",
                            confirmation = ExplicitManualSave,
                        ),
                    ),
                )
            val application = assertIs<ManualTransferSubmissionResult.Application>(assertIs<com.unifiedledger.application.ManualEntrySubmissionResult.Transfer>(result).result)
            assertIs<ManualTransferSaveResult.Executed>(application.result)
            assertEquals(
                0L,
                graph.database.ledgerQueries
                    .countTransactions()
                    .executeAsOne(),
            )
            assertEquals(
                0L,
                graph.database.ledgerQueries
                    .countManualTransferRequests()
                    .executeAsOne(),
            )
        } finally {
            driver.close()
        }
    }
}
