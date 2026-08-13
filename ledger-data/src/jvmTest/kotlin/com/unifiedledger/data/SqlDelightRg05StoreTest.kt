package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.application.*
import com.unifiedledger.domain.*
import kotlin.test.*
import kotlin.time.Instant

class SqlDelightRg05StoreTest {
    @Test
    fun ingestFailureRollsBackRequestSourcesEvidenceAndCandidate() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg05Store(database, driver, catalog(), object : Rg05IdentitySource { override fun manual(requestId: RequestId) = Rg05ManualCommitIds("unused", "unused") }, Rg05FailureInjector { if (it == Rg05FailurePoint.INGEST_AFTER_SOURCES) error("injected") })
            val cny = CurrencyUnit("CNY", 2)
            val operation = Rg05PreparedOperation.Ingest(Rg05IngestSnapshot(
                LedgerId("ledger-a"), RequestId("request"),
                Rg05BankFact("bank", "bank-evidence", Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00Z", "bank", Money.ofMinor(-10_000, cny)),
                listOf(
                    Rg05ItemFact("a", "source-a", "evidence-a", Rg05EvidenceKind.ITEM_RECEIPT, Instant.parse("2026-04-08T02:00:00Z"), "2026-04-08T02:00:00Z", "A", Money.ofMinor(4_000, cny), CategoryId("daily")),
                    Rg05ItemFact("b", "source-b", "evidence-b", Rg05EvidenceKind.ITEM_SUMMARY, Instant.parse("2026-04-09T07:00:00Z"), "2026-04-09T07:00:00Z", "B", Money.ofMinor(6_000, cny), CategoryId("service")),
                ), "candidate", "pending",
            ))
            assertFails { store.commit(operation) }
            assertEquals(0L, database.ledgerQueries.countRg05OperationRequests().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05Sources().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05Evidence().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg05Candidates().executeAsOne())
        } finally { driver.close() }
    }
    @Test
    fun importConfirmAndReceiptLifecycleKeepsBusinessCompletenessSeparate() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val ledger = LedgerId("ledger-a")
            val store = SqlDelightRg05Store(database, driver, catalog(), object : Rg05IdentitySource { override fun manual(requestId: RequestId) = Rg05ManualCommitIds("unused", "unused") })
            val cny = CurrencyUnit("CNY", 2)
            val bank = Rg05BankFact("source-bank", "evidence-bank", Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00Z", "bank", Money.ofMinor(-10_000, cny))
            val items = listOf(
                Rg05ItemFact("item-a", "source-a", "evidence-a", Rg05EvidenceKind.ITEM_RECEIPT, Instant.parse("2026-04-08T02:00:00Z"), "2026-04-08T10:00:00+08:00", "A", Money.ofMinor(4_000, cny), CategoryId("daily")),
                Rg05ItemFact("item-b", "source-b", "evidence-b-summary", Rg05EvidenceKind.ITEM_SUMMARY, Instant.parse("2026-04-09T07:00:00Z"), "2026-04-09T15:00:00+08:00", "B", Money.ofMinor(6_000, cny), CategoryId("service")),
            )
            val ingest = Rg05PreparedOperation.Ingest(Rg05IngestSnapshot(ledger, RequestId("source-bank"), bank, items, "candidate", "candidate-status-pending"))
            assertIs<Rg05ExecutionResult.IngestAccepted>(store.commit(ingest))
            assertIs<Rg05ExecutionResult.IngestNoChange>(store.commit(ingest))
            assertIs<Rg05ExecutionResult.RequestIdentityConflict>(store.commit(ingest.copy(snapshot = ingest.snapshot.copy(bankFact = bank.copy(details = "changed")))))
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countRg05Sources().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countRg05Evidence().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05Candidates().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05CandidateStatuses().executeAsOne())

            fun confirm(amounts: List<Long>) = Rg05PreparedOperation.Confirm(
                Rg05ConfirmSnapshot(ledger, RequestId("confirm-${amounts.sum()}"), "candidate", AccountId("asset"), Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00+08:00", Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00+08:00", listOf(Rg05ConfirmAllocation("item-a", CategoryId("daily"), Money.ofMinor(amounts[0], cny)), Rg05ConfirmAllocation("item-b", CategoryId("service"), Money.ofMinor(amounts[1], cny))), true, "candidate-status-confirmed"),
                MergedPaymentExpenseIds(TransactionId("tx-imported"), TransactionVersionId("v-imported"), PostingSetId("set-imported"), listOf(PostingId("expense-a-imported"), PostingId("expense-b-imported")), PostingId("asset-imported")), "relation-imported", "confirmation-imported", "reconciliation-imported", "match-bank", mapOf("item-a" to "match-item-a"), mapOf("item-a" to "consumption-a", "item-b" to "consumption-b"), mapOf("item-a" to "allocation-a", "item-b" to "allocation-b"),
            )
            assertEquals(Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_INCOMPLETE, "allocation_total"), store.commit(confirm(listOf(4_000, 5_000))))
            assertEquals(Rg05ExecutionResult.Rejected(Rg05ExecutionError.ALLOCATION_CONFLICT, "allocation_total"), store.commit(confirm(listOf(4_000, 7_000))))
            val invalidCategory = confirm(listOf(4_000, 6_000)).let { operation ->
                operation.copy(snapshot = operation.snapshot.copy(requestId = RequestId("confirm-invalid-category"), allocations = operation.snapshot.allocations.mapIndexed { index, allocation -> if (index == 0) allocation.copy(categoryId = CategoryId("missing")) else allocation }))
            }
            assertEquals(Rg05ExecutionResult.Rejected(Rg05ExecutionError.SECONDARY_CATEGORY_REQUIRED, "items"), store.commit(invalidCategory))
            assertEquals(1L, database.ledgerQueries.countRg05OperationRequests().executeAsOne())
            val confirmation = confirm(listOf(4_000, 6_000))
            assertIs<Rg05ExecutionResult.Accepted>(store.commit(confirmation))
            assertIs<Rg05ExecutionResult.NoChange>(store.commit(confirmation))
            assertIs<Rg05ExecutionResult.RequestIdentityConflict>(store.commit(confirmation.copy(snapshot = confirmation.snapshot.copy(allocations = confirmation.snapshot.allocations.mapIndexed { index, allocation -> if (index == 0) allocation.copy(categoryId = CategoryId("service")) else allocation }))))
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg05RelationItems().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg05CandidateStatuses().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg05EvidenceLinks().executeAsOne())

            val receipt = Rg05PreparedOperation.Receipt(Rg05ReceiptSnapshot(ledger, RequestId("receipt"), "source-b-receipt", "evidence-b-receipt", "allocation-b", "match-item-b", Instant.parse("2026-04-09T07:00:00Z"), "2026-04-09T15:00:00+08:00", "B", Money.ofMinor(6_000, cny)))
            assertIs<Rg05ExecutionResult.ReceiptAccepted>(store.commit(receipt))
            assertIs<Rg05ExecutionResult.ReceiptNoChange>(store.commit(receipt))
            assertIs<Rg05ExecutionResult.RequestIdentityConflict>(store.commit(receipt.copy(snapshot = receipt.snapshot.copy(details = "changed"))))
            assertEquals(4L, database.ledgerQueries.countRg05Evidence().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countRg05EvidenceLinks().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05PostingReconciliations().executeAsOne())
            assertEquals(listOf("COMPLETE", "COMPLETE"), database.ledgerQueries.selectRg05RelationCompleteness("ledger-a", "relation-imported").executeAsList())
        } finally {
            driver.close()
        }
    }
    @Test
    fun schemaIsVersionTenAndManualCommitIsIdempotent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            assertEquals(21, LedgerDatabase.Schema.version)
            val catalog = catalog()
            val store = SqlDelightRg05Store(database, driver, catalog, object : Rg05IdentitySource {
                override fun manual(requestId: RequestId) = Rg05ManualCommitIds("confirmation", "reconciliation")
            })
            val snapshot = Rg05ManualSnapshot(
                LedgerId("ledger-a"), RequestId("request"), Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00Z",
                Money.ofMinor(10_000, currency), AccountId("asset"),
                listOf(
                    MergedPaymentItem("a", Money.ofMinor(4_000, currency), CategoryId("daily"), "daily", Instant.parse("2026-04-10T09:00:00Z")),
                    MergedPaymentItem("b", Money.ofMinor(6_000, currency), CategoryId("service"), "service", Instant.parse("2026-04-10T09:05:00Z")),
                ), true,
            )
            val operation = Rg05PreparedOperation.Manual(snapshot, MergedPaymentExpenseIds(TransactionId("tx"), TransactionVersionId("v"), PostingSetId("set"), listOf(PostingId("expense-a"), PostingId("expense-b")), PostingId("asset-posting")), "relation", "", "", mapOf("a" to "consumption-a", "b" to "consumption-b"), mapOf("a" to "allocation-a", "b" to "allocation-b"))
            assertEquals(
                Rg05ExecutionResult.Rejected(Rg05ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED, "explicit_confirmation"),
                store.commit(operation.copy(snapshot = snapshot.copy(confirmed = false))),
            )
            assertIs<Rg05ExecutionResult.Accepted>(store.commit(operation))
            assertIs<Rg05ExecutionResult.NoChange>(store.commit(operation))
            assertIs<Rg05ExecutionResult.RequestIdentityConflict>(store.commit(operation.copy(snapshot = snapshot.copy(total = Money.ofMinor(10_001, currency)))))
            assertEquals(1L, database.ledgerQueries.countRg05OperationRequests().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05MergedPayments().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg05Items().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg05Relations().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg05RelationItems().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
        } finally {
            driver.close()
        }
    }

    private fun catalog() = assertIs<DomainResult.Success<LedgerCatalog>>(
        LedgerCatalog.create(
            listOf(
                Account(AccountId("asset"), LedgerId("ledger-a"), AccountKind.ASSET, currency, true, true),
                Account(AccountId("expense-a-account"), LedgerId("ledger-a"), AccountKind.EXPENSE, currency, false, false),
                Account(AccountId("expense-b-account"), LedgerId("ledger-a"), AccountKind.EXPENSE, currency, false, false),
            ),
            listOf(
                Category(CategoryId("root"), LedgerId("ledger-a"), null, null, true),
                Category(CategoryId("daily"), LedgerId("ledger-a"), CategoryId("root"), AccountId("expense-a-account"), true),
                Category(CategoryId("service"), LedgerId("ledger-a"), CategoryId("root"), AccountId("expense-b-account"), true),
            ),
        ),
    ).value

    companion object { val currency = CurrencyUnit("CNY", 2) }
}
