package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class SqlDelightRg07IdentityTest {
    @Test
    fun stableOperationOwnershipSeparatesMultipleExpensesAndLedgers() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val currency = CurrencyUnit("CNY", 2)
            val ledgers = listOf(LedgerId("ledger-a"), LedgerId("ledger-b"))
            val accounts = ledgers.flatMap { ledger ->
                listOf(
                    Account(AccountId("${ledger.value}-asset"), ledger, AccountKind.ASSET, currency, true, true),
                    Account(AccountId("${ledger.value}-expense"), ledger, AccountKind.EXPENSE, currency, false, false),
                )
            }
            val categories = ledgers.flatMap { ledger ->
                listOf(
                    Category(CategoryId("${ledger.value}-parent"), ledger, null, null, true),
                    Category(CategoryId("${ledger.value}-daily"), ledger, CategoryId("${ledger.value}-parent"), AccountId("${ledger.value}-expense"), true),
                )
            }
            val catalog = assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(accounts, categories)).value
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg07Store(database, driver, catalog, emptySet(), StableRg07IdentitySource)

            fun expense(ledger: LedgerId, request: String) = Rg07Operation.ManualExpense(
                ledger,
                Rg07ManualExpenseInput(
                    RequestId(request), Money.ofMinor(1_000, currency), CategoryId("${ledger.value}-daily"),
                    AccountId("${ledger.value}-asset"), Instant.parse("2026-01-10T04:00:00Z"), "", true,
                ),
            )

            val first = assertIs<Rg07ExecutionResult.Accepted>(store.commit(expense(ledgers[0], "request-1")))
            val second = assertIs<Rg07ExecutionResult.Accepted>(store.commit(expense(ledgers[0], "request-2")))
            val otherLedger = assertIs<Rg07ExecutionResult.Accepted>(store.commit(expense(ledgers[1], "request-1")))

            assertNotEquals(first.transactionId, second.transactionId)
            assertNotEquals(first.transactionId, otherLedger.transactionId)
            assertEquals(3L, database.ledgerQueries.countTransactions().executeAsOne())
        } finally {
            driver.close()
        }
    }
}

private object StableRg07IdentitySource : Rg07IdentitySource {
    private fun owner(operation: Rg07Operation): String = "${operation.ledgerId.value}-${operation.identity.value}"
    override fun operationId(operation: Rg07Operation): String = "operation-${owner(operation)}"
    override fun manual(operation: Rg07Operation.ManualExpense): Rg07ManualCommitFacts = Rg07ManualCommitFacts(
        "confirmation-${owner(operation)}", "reconciliation-${owner(operation)}", Instant.parse("2026-01-10T04:01:00Z"),
    )
    override fun relation(operation: Rg07Operation.Status): String = "relation-${owner(operation)}"
    override fun domainEntity(operation: Rg07Operation, relationId: String): String = "entity-${owner(operation)}"
    override fun formal(operation: Rg07Operation): Rg07FormalIds {
        val owner = owner(operation)
        return Rg07FormalIds("transaction-$owner", "version-$owner", "posting-set-$owner", "posting-first-$owner", "posting-second-$owner")
    }
    override fun receipt(operation: Rg07Operation, relationId: String, assetPostingId: String): Rg07ReceiptCommitIds =
        Rg07ReceiptCommitIds("confirmation-${owner(operation)}", "reconciliation-${owner(operation)}", "entity-${owner(operation)}")
}
