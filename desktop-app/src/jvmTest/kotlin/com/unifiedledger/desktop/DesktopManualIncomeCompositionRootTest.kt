package com.unifiedledger.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogReceiptOutcome
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ManualIncomeSaveInput
import com.unifiedledger.application.ManualIncomeSaveResult
import com.unifiedledger.application.ManualIncomeSubmissionResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P7-02.A income composition-root wiring: the desktop root must assemble the income chain so a
 * manual income commits through the claim-first port, is admitted by V-2 against the
 * authoritative INCOME catalog, writes its note into the formal version and reads back the real
 * INCOME kind through the authoritative current state.
 */
class DesktopManualIncomeCompositionRootTest {
    private val ledgerId = com.unifiedledger.domain.LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun manualIncomeCommitsThroughTheWiredChainAndReadsBackIncomeKind() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val commands = graph.catalogCommands

            // The default seed has no income category; create one through the management path.
            val created =
                assertIs<CatalogCommandResult.Accepted>(
                    commands.createCategoryGroup(graph.ledgerId, CategoryKind.INCOME, "收入", "工资", expectedCatalogVersion = 1L),
                )
            assertEquals(CatalogReceiptOutcome.ACCEPTED, created.receipt.outcome)
            graph.catalogSession.refresh()

            val incomeOptions = graph.facade.incomeOptionsProvider.queryOptions()
            val receivingAccount = incomeOptions.receivingAccounts.single()
            val incomeCategory = incomeOptions.incomeCategories.single()
            val submission = checkNotNull(graph.facade.submitIncome)

            val result =
                submission.submit(
                    ManualIncomeSaveInput(
                        ledgerId = graph.ledgerId,
                        requestId = RequestId("request-desktop-income-1"),
                        amount = Money.ofMinor(300_000L, cny),
                        categoryId = incomeCategory.categoryId,
                        receivingAccountId = receivingAccount.accountId,
                        occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
                        note = "monthly salary",
                        confirmation = ExplicitManualSave,
                    ),
                )
            val application = assertIs<ManualIncomeSubmissionResult.Application>(result)
            assertIs<ManualIncomeSaveResult.Executed>(application.result)

            val state = assertIs<com.unifiedledger.application.LedgerCurrentStateResult.Success>(graph.facade.queryCurrentState.query()).state
            assertEquals(1, state.transactions.size)
            assertEquals(TransactionKind.INCOME, state.transactions.single().kind)

            val versionNote =
                graph.database.ledgerQueries
                    .selectPersistedVersions()
                    .executeAsList()
                    .single()
                    .note
            assertEquals("monthly salary", versionNote)
            assertEquals(
                1L,
                graph.database.ledgerQueries
                    .countManualIncomeRequests()
                    .executeAsOne(),
            )

            // Equivalent replay is idempotent (zero new rows).
            val replayed =
                submission.submit(
                    ManualIncomeSaveInput(
                        ledgerId = graph.ledgerId,
                        requestId = RequestId("request-desktop-income-1"),
                        amount = Money.ofMinor(300_000L, cny),
                        categoryId = incomeCategory.categoryId,
                        receivingAccountId = receivingAccount.accountId,
                        occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
                        note = "monthly salary",
                        confirmation = ExplicitManualSave,
                    ),
                )
            val replayApplication = assertIs<ManualIncomeSubmissionResult.Application>(replayed)
            assertIs<ManualIncomeSaveResult.Executed>(replayApplication.result)
            assertEquals(
                1L,
                graph.database.ledgerQueries
                    .countTransactions()
                    .executeAsOne(),
            )
            assertTrue(graph.facade.resolveIncomeCommitStatus != null)
        } finally {
            driver.close()
        }
    }

    @Test
    fun incomeAdmissionRejectsADeactivatedCategoryWithZeroFormalWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val graph = buildLedgerGraph(driver, createSchema = true)
            val commands = graph.catalogCommands
            val accepted =
                assertIs<CatalogCommandResult.Accepted>(
                    commands.createCategoryGroup(graph.ledgerId, CategoryKind.INCOME, "收入", "工资", expectedCatalogVersion = 1L),
                )
            graph.catalogSession.refresh()
            val createdChildId = checkNotNull(accepted.receipt.createdChildCategoryId)
            // Deactivate the category after the draft was prepared.
            driver.execute(
                null,
                "UPDATE catalog_category SET active = 0 WHERE ledger_id = 'ledger-local-test' AND category_id = '${createdChildId.value}'",
                0,
            )
            graph.catalogSession.refresh()
            assertEquals(
                0,
                graph.facade.incomeOptionsProvider
                    .queryOptions()
                    .incomeCategories.size,
            )

            val submission = checkNotNull(graph.facade.submitIncome)
            val result =
                submission.submit(
                    ManualIncomeSaveInput(
                        ledgerId = graph.ledgerId,
                        requestId = RequestId("request-desktop-income-v2"),
                        amount = Money.ofMinor(1_200L, cny),
                        categoryId = createdChildId,
                        receivingAccountId = com.unifiedledger.domain.AccountId("asset-payment-local"),
                        occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
                        note = "",
                        confirmation = ExplicitManualSave,
                    ),
                )
            val application = assertIs<ManualIncomeSubmissionResult.Application>(result)
            assertIs<ManualIncomeSaveResult.Executed>(application.result)
            assertEquals(
                0L,
                graph.database.ledgerQueries
                    .countTransactions()
                    .executeAsOne(),
            )
            assertEquals(
                0L,
                graph.database.ledgerQueries
                    .countManualIncomeRequests()
                    .executeAsOne(),
            )
        } finally {
            driver.close()
        }
    }
}
