package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg06CandidateConfirmationCommitIds
import com.unifiedledger.application.Rg06CandidateId
import com.unifiedledger.application.Rg06CandidateStatusId
import com.unifiedledger.application.Rg06ChangeStagedPaymentFulfillmentInput
import com.unifiedledger.application.Rg06ConfirmStagedPaymentCandidateInput
import com.unifiedledger.application.Rg06ConfirmStagedPaymentCompletionInput
import com.unifiedledger.application.Rg06ConfirmationId
import com.unifiedledger.application.Rg06CreateStagedPaymentInput
import com.unifiedledger.application.Rg06EvidenceId
import com.unifiedledger.application.Rg06EvidenceLinkId
import com.unifiedledger.application.Rg06ExecutionResult
import com.unifiedledger.application.Rg06IngestCommitIds
import com.unifiedledger.application.Rg06IngestStagedPaymentBankFactInput
import com.unifiedledger.application.Rg06LinkStagedPaymentEvidenceInput
import com.unifiedledger.application.Rg06ManualBankObservation
import com.unifiedledger.application.Rg06ManualInstallmentCommitIds
import com.unifiedledger.application.Rg06MergeStagedPaymentMirrorEvidenceInput
import com.unifiedledger.application.Rg06ObservedAt
import com.unifiedledger.application.Rg06Operation
import com.unifiedledger.application.Rg06ReconciliationId
import com.unifiedledger.application.Rg06RecordStagedPaymentInstallmentInput
import com.unifiedledger.application.Rg06SourceId
import com.unifiedledger.application.Rg06TypedValueResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.InstallmentPaymentId
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.StagedPaymentCreationIds
import com.unifiedledger.domain.StagedPaymentFulfillment
import com.unifiedledger.domain.StagedPaymentHistoryId
import com.unifiedledger.domain.StagedPaymentInstallmentIds
import com.unifiedledger.domain.StagedPaymentLifecycleId
import com.unifiedledger.domain.StagedPaymentRelationId
import com.unifiedledger.domain.StagedPaymentRole
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

abstract class Rg06StoreTestSupport {
    protected fun createOperation() =
        Rg06Operation.CreateStagedPayment(
            ledgerId = LedgerId("ledger-a"),
            input =
                Rg06CreateStagedPaymentInput(
                    requestId = RequestId("request-create"),
                    totalAmount = Money.ofMinor(30_000, CNY),
                    categoryId = CategoryId("expense-service"),
                    createdAt = Instant.parse("2026-04-20T09:00:00+08:00"),
                ),
            ids =
                StagedPaymentCreationIds(
                    StagedPaymentRelationId("relation"),
                    StagedPaymentLifecycleId("lifecycle"),
                    StagedPaymentHistoryId("history-created"),
                ),
        )

    protected fun record(
        label: String,
        role: StagedPaymentRole,
        minor: Long,
        at: Instant,
    ) = Rg06Operation.RecordStagedPaymentInstallment(
        LedgerId("ledger-a"),
        Rg06RecordStagedPaymentInstallmentInput(RequestId("request-$label"), StagedPaymentRelationId("relation"), role, Money.ofMinor(minor, CNY), AccountId("asset-bank"), at),
        Rg06ManualInstallmentCommitIds(Rg06ConfirmationId("confirmation-$label"), paymentIds(label), Rg06ReconciliationId("reconciliation-$label")),
    )

    protected fun fulfill() =
        Rg06Operation.ChangeStagedPaymentFulfillment(
            LedgerId("ledger-a"),
            Rg06ChangeStagedPaymentFulfillmentInput(RequestId("request-fulfill"), StagedPaymentRelationId("relation"), StagedPaymentFulfillment.FULFILLED, Instant.parse("2026-04-29T10:00:00+08:00")),
            StagedPaymentHistoryId("history-fulfilled"),
        )

    protected fun complete() =
        Rg06Operation.ConfirmStagedPaymentCompletion(
            LedgerId("ledger-a"),
            Rg06ConfirmStagedPaymentCompletionInput(RequestId("request-complete"), StagedPaymentRelationId("relation"), true, Instant.parse("2026-05-04T10:00:00+08:00")),
            StagedPaymentHistoryId("history-completed"),
        )

    protected fun ingest() =
        Rg06Operation.IngestStagedPaymentBankFact(
            LedgerId("ledger-a"),
            Rg06IngestStagedPaymentBankFactInput(Rg06SourceId("import-source"), Rg06EvidenceId("import-evidence"), Instant.parse("2026-04-28T10:00:00+08:00"), "2026-04-28T10:00:00+08:00", Money.ofMinor(-8_000, CNY), StagedPaymentRole.DEPOSIT),
            Rg06IngestCommitIds(Rg06CandidateId("candidate"), Rg06CandidateStatusId("status-pending")),
        )

    protected fun confirmCandidate() =
        Rg06Operation.ConfirmStagedPaymentCandidate(
            LedgerId("ledger-a"),
            Rg06ConfirmStagedPaymentCandidateInput(RequestId("request-confirm"), Rg06CandidateId("candidate"), StagedPaymentRelationId("relation"), StagedPaymentRole.DEPOSIT, CategoryId("expense-service"), AccountId("asset-bank"), true),
            Rg06CandidateConfirmationCommitIds(Rg06ConfirmationId("confirmation-import"), paymentIds("import"), Rg06EvidenceLinkId("import-link"), Rg06CandidateStatusId("status-confirmed"), Rg06ReconciliationId("reconciliation-import")),
        )

    protected fun linkDepositEvidence() =
        Rg06Operation.LinkStagedPaymentEvidence(
            LedgerId("ledger-a"),
            Rg06LinkStagedPaymentEvidenceInput(
                Rg06SourceId("manual-source"),
                Rg06EvidenceId("manual-evidence"),
                InstallmentPaymentId("payment-deposit"),
                PostingId("posting-deposit-asset"),
            ),
            Rg06EvidenceLinkId("manual-link"),
        )

    protected fun mergeDepositMirror() =
        Rg06Operation.MergeStagedPaymentMirrorEvidence(
            LedgerId("ledger-a"),
            Rg06MergeStagedPaymentMirrorEvidenceInput(
                Rg06SourceId("mirror-source"),
                Rg06EvidenceId("mirror-evidence"),
                InstallmentPaymentId("payment-import"),
                PostingId("posting-import-asset"),
                Money.ofMinor(8_000, CNY),
                Instant.parse("2026-04-28T10:00:00+08:00"),
                "2026-04-28T10:00:00+08:00",
            ),
        )

    protected fun occupyGeneratedIdentity(
        database: LedgerDatabase,
        store: SqlDelightRg06Store,
        kind: String,
        value: String,
    ) {
        fun commit(operation: Rg06Operation) {
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation), "occupy.$kind")
        }

        fun createOccupiedAggregate(historyId: StagedPaymentHistoryId = StagedPaymentHistoryId("occupied-history")) {
            commit(
                createOperation().copy(
                    input = createOperation().input.copy(requestId = RequestId("request-occupied-create")),
                    ids =
                        StagedPaymentCreationIds(
                            StagedPaymentRelationId("occupied-relation"),
                            StagedPaymentLifecycleId("occupied-lifecycle"),
                            historyId,
                        ),
                ),
            )
        }

        fun occupiedRecord(ids: Rg06ManualInstallmentCommitIds) {
            val base = record("occupied", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            commit(
                base.copy(
                    input =
                        base.input.copy(
                            requestId = RequestId("request-occupied-record"),
                            relationId = StagedPaymentRelationId("occupied-relation"),
                        ),
                    ids = ids,
                ),
            )
        }

        when (kind) {
            "source" ->
                database.ledgerQueries.insertRg06Source(
                    "ledger-a",
                    value,
                    "IMPORTED",
                    -100,
                    "CNY",
                    2,
                    "2026-04-01T00:00:00Z",
                    "2026-04-01T00:00:00Z",
                    "SOURCE_PAYMENT_AT",
                    null,
                )
            "relation", "lifecycle", "history" -> {
                val ids =
                    StagedPaymentCreationIds(
                        if (kind == "relation") StagedPaymentRelationId(value) else StagedPaymentRelationId("occupied-relation"),
                        if (kind == "lifecycle") StagedPaymentLifecycleId(value) else StagedPaymentLifecycleId("occupied-lifecycle"),
                        if (kind == "history") StagedPaymentHistoryId(value) else StagedPaymentHistoryId("occupied-history"),
                    )
                commit(
                    createOperation().copy(
                        input = createOperation().input.copy(requestId = RequestId("request-occupied-create")),
                        ids = ids,
                    ),
                )
            }
            "payment", "transaction", "version", "posting_set", "posting", "confirmation", "reconciliation" -> {
                createOccupiedAggregate()
                var installmentIds = paymentIds("occupied")
                installmentIds =
                    when (kind) {
                        "payment" -> installmentIds.copy(paymentId = InstallmentPaymentId(value))
                        "transaction" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(transactionId = TransactionId(value)))
                        "version" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(versionId = TransactionVersionId(value)))
                        "posting_set" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(postingSetId = PostingSetId(value)))
                        "posting" -> installmentIds.copy(expenseIds = installmentIds.expenseIds.copy(expensePostingId = PostingId(value)))
                        else -> installmentIds
                    }
                occupiedRecord(
                    Rg06ManualInstallmentCommitIds(
                        if (kind == "confirmation") Rg06ConfirmationId(value) else Rg06ConfirmationId("occupied-confirmation"),
                        installmentIds,
                        if (kind == "reconciliation") Rg06ReconciliationId(value) else Rg06ReconciliationId("occupied-reconciliation"),
                    ),
                )
            }
            "evidence", "candidate", "candidate_status" -> {
                val base = ingest()
                commit(
                    base.copy(
                        input =
                            base.input.copy(
                                sourceId = Rg06SourceId("occupied-source"),
                                evidenceId = if (kind == "evidence") Rg06EvidenceId(value) else Rg06EvidenceId("occupied-evidence"),
                            ),
                        ids =
                            Rg06IngestCommitIds(
                                if (kind == "candidate") Rg06CandidateId(value) else Rg06CandidateId("occupied-candidate"),
                                if (kind == "candidate_status") Rg06CandidateStatusId(value) else Rg06CandidateStatusId("occupied-status"),
                            ),
                    ),
                )
            }
            "link" -> {
                createOccupiedAggregate()
                val ids =
                    Rg06ManualInstallmentCommitIds(
                        Rg06ConfirmationId("occupied-confirmation"),
                        paymentIds("occupied"),
                        Rg06ReconciliationId("occupied-reconciliation"),
                    )
                occupiedRecord(ids)
                commit(
                    Rg06Operation.LinkStagedPaymentEvidence(
                        LedgerId("ledger-a"),
                        Rg06LinkStagedPaymentEvidenceInput(
                            Rg06SourceId("occupied-link-source"),
                            Rg06EvidenceId("occupied-link-evidence"),
                            ids.paymentIds.paymentId,
                            ids.paymentIds.expenseIds.paymentPostingId,
                        ),
                        Rg06EvidenceLinkId(value),
                    ),
                )
            }
            else -> error("unsupported generated identity kind: $kind")
        }
    }

    protected fun concurrentCommits(
        url: String,
        properties: Properties,
        operations: List<Rg06Operation>,
    ): List<Result<Rg06ExecutionResult>> {
        val start = CountDownLatch(1)
        val results = MutableList<Result<Rg06ExecutionResult>?>(operations.size) { null }
        val workers =
            operations.mapIndexed { index, operation ->
                thread {
                    start.await()
                    val result =
                        runCatching {
                            JdbcSqliteDriver(url, properties).use { driver ->
                                SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                                    .commit(operation)
                            }
                        }
                    synchronized(results) { results[index] = result }
                }
            }
        start.countDown()
        workers.forEach { it.join() }
        return results.map { checkNotNull(it) }
    }

    protected fun preparedDatabaseFile(prepare: (SqlDelightRg06Store) -> Unit): Path {
        val path = Files.createTempFile("rg06-prepared-", ".db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}").use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                prepare(store)
            }
        } catch (failure: Throwable) {
            path.toFile().deleteOnExit()
            Files.deleteIfExists(path)
            throw failure
        }
        return path
    }

    // Copies the main file only, so the base must be closed and in rollback-journal mode.
    // If a fixture ever runs with `journal_mode=WAL`, the `-wal`/`-shm` sidecars must be copied too.
    protected fun withCopiedDatabase(
        base: Path,
        block: (LedgerDatabase, JdbcSqliteDriver, SqlDelightRg06Store) -> Unit,
    ) {
        val copy = Files.createTempFile("rg06-case-", ".db")
        try {
            Files.copy(base, copy, StandardCopyOption.REPLACE_EXISTING)
            JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePathString()}").use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                block(database, driver, store)
            }
        } finally {
            Files.deleteIfExists(copy)
        }
    }

    protected fun paymentIds(label: String) =
        StagedPaymentInstallmentIds(
            InstallmentPaymentId("payment-$label"),
            StagedPaymentHistoryId("history-$label"),
            AssetPaidOrdinaryExpenseIds(TransactionId("transaction-$label"), TransactionVersionId("version-$label"), PostingSetId("posting-set-$label"), PostingId("posting-$label-expense"), PostingId("posting-$label-asset")),
        )

    protected fun manualObservation(): Rg06ManualBankObservation {
        val observed =
            assertIs<Rg06TypedValueResult.Success<Rg06ObservedAt>>(
                Rg06ObservedAt.create(Instant.parse("2026-04-28T10:01:00+08:00"), "2026-04-28T10:01:00+08:00", "+08:00"),
            ).value
        return Rg06ManualBankObservation(Money.ofMinor(-8_000, CNY), observed)
    }

    protected fun seedLegacyConfirmation(
        driver: JdbcSqliteDriver,
        owner: String,
        confirmationId: String,
    ) {
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('legacy-tx','ledger-a','EXPENSE')", 0)
        when (owner) {
            "expense" -> {
                driver.execute(null, "INSERT INTO manual_expense_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, payment_account_id, occurred_at, note, confirmation_marker) VALUES ('ledger-a','legacy-request',100,'CNY',2,'expense-service','asset-bank','2026-01-01T00:00:00Z','','explicit_manual_save')", 0)
                driver.execute(null, "INSERT INTO confirmed_expense_receipt VALUES ('ledger-a','legacy-request','$confirmationId','legacy-tx')", 0)
            }
            "note-update" -> {
                driver.execute(null, "INSERT INTO posting_set VALUES ('legacy-set','ledger-a')", 0)
                driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('legacy-version','legacy-tx','ledger-a',1,'legacy-set','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',NULL)", 0)
                driver.execute(null, "INSERT INTO transaction_note_update_request VALUES ('ledger-a','legacy-request','legacy-tx','','explicit_manual_save')", 0)
                driver.execute(null, "INSERT INTO confirmed_transaction_note_update_receipt VALUES ('ledger-a','legacy-request','$confirmationId','legacy-tx','legacy-version','legacy-version')", 0)
            }
            "income" -> {
                driver.execute(null, "INSERT INTO manual_income_request(ledger_id, request_id, amount_minor, currency_code, currency_precision, category_id, receiving_account_id, occurred_at, note, confirmation_marker) VALUES ('ledger-a','legacy-request',100,'CNY',2,'income-category','asset-bank','2026-01-01T00:00:00Z','','explicit_manual_save')", 0)
                driver.execute(null, "INSERT INTO confirmed_income_receipt VALUES ('ledger-a','legacy-request','$confirmationId','legacy-tx')", 0)
            }
            else -> error("unknown owner")
        }
    }

    protected fun assertRetryAndConflict(
        store: SqlDelightRg06Store,
        operation: Rg06Operation,
        changed: Rg06Operation,
    ) {
        val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
        assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(operation))
        assertIs<Rg06ExecutionResult.RequestIdentityConflict>(store.commit(changed))
    }

    protected fun catalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                accounts =
                    listOf(
                        Account(AccountId("asset-bank"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, true, true),
                        Account(AccountId("expense-service-account"), LedgerId("ledger-a"), AccountKind.EXPENSE, CNY, false, false),
                        Account(AccountId("income-account"), LedgerId("ledger-a"), AccountKind.INCOME, CNY, false, false),
                        Account(AccountId("asset-nonfinancial"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, true, false),
                        Account(AccountId("asset-external"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, false, true),
                        Account(AccountId("liability"), LedgerId("ledger-a"), AccountKind.LIABILITY, CNY, true, true),
                    ),
                categories =
                    listOf(
                        Category(CategoryId("expense-root"), LedgerId("ledger-a"), null, null, true),
                        Category(CategoryId("expense-service"), LedgerId("ledger-a"), CategoryId("expense-root"), AccountId("expense-service-account"), true),
                        Category(CategoryId("expense-inactive"), LedgerId("ledger-a"), CategoryId("expense-root"), AccountId("expense-service-account"), false),
                        Category(CategoryId("income-root"), LedgerId("ledger-a"), null, null, true, CategoryKind.INCOME),
                        Category(CategoryId("income-child"), LedgerId("ledger-a"), CategoryId("income-root"), AccountId("income-account"), true, CategoryKind.INCOME),
                    ),
            ),
        ).value
}

internal val CNY = CurrencyUnit("CNY", 2)
internal val USD = CurrencyUnit("USD", 2)

internal data class Rg06StoreSnapshot(
    val operations: Long,
    val receipts: Long,
    val relations: Long,
    val relationMembers: Long,
    val lifecycles: Long,
    val history: Long,
    val installments: Long,
    val postingSemantics: Long,
    val sources: Long,
    val evidence: Long,
    val candidates: Long,
    val requirements: Long,
    val candidateStatuses: Long,
    val confirmations: Long,
    val evidenceLinks: Long,
    val reconciliations: Long,
    val reconciliationHistory: Long,
    val transactions: Long,
    val versions: Long,
    val postingSets: Long,
    val postings: Long,
)

internal fun storeSnapshot(database: LedgerDatabase) =
    Rg06StoreSnapshot(
        operations = database.ledgerQueries.countRg06Operations().executeAsOne(),
        receipts = database.ledgerQueries.countRg06OperationReceipts().executeAsOne(),
        relations = database.ledgerQueries.countRg06Relations().executeAsOne(),
        relationMembers = database.ledgerQueries.countRg06RelationMembers().executeAsOne(),
        lifecycles = database.ledgerQueries.countRg06Lifecycles().executeAsOne(),
        history = database.ledgerQueries.countRg06History().executeAsOne(),
        installments = database.ledgerQueries.countRg06Installments().executeAsOne(),
        postingSemantics = database.ledgerQueries.countRg06PostingSemantics().executeAsOne(),
        sources = database.ledgerQueries.countRg06Sources().executeAsOne(),
        evidence = database.ledgerQueries.countRg06Evidence().executeAsOne(),
        candidates = database.ledgerQueries.countRg06Candidates().executeAsOne(),
        requirements = database.ledgerQueries.countRg06CandidateRequirements().executeAsOne(),
        candidateStatuses = database.ledgerQueries.countRg06CandidateStatuses().executeAsOne(),
        confirmations = database.ledgerQueries.countRg06Confirmations().executeAsOne(),
        evidenceLinks = database.ledgerQueries.countRg06EvidenceLinks().executeAsOne(),
        reconciliations = database.ledgerQueries.countRg06Reconciliations().executeAsOne(),
        reconciliationHistory = database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne(),
        transactions = database.ledgerQueries.countTransactions().executeAsOne(),
        versions = database.ledgerQueries.countVersions().executeAsOne(),
        postingSets = database.ledgerQueries.countPostingSets().executeAsOne(),
        postings = database.ledgerQueries.countPostings().executeAsOne(),
    )
