package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecision
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateDecisionSnapshot
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCandidateRejectRequest
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateIntakeIds
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportIntakeSnapshot
import com.unifiedledger.application.ImportRawIdentity
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportResolvedSourceFacts
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ImportStatusIdSource
import com.unifiedledger.application.RejectImportCandidate
import com.unifiedledger.application.ReviewImportDuplicateCandidate
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import java.nio.file.Files
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * P4-02 spine end-to-end oracle (spec section 9, T-01..T-30). The spec's operation
 * tables are the oracle: every assertion below pins outcome, returned ids, row-count
 * deltas, status transitions, receipt values, concurrency winners/losers, failure
 * rollback, append-only guards, reopen replay, and the income/domain-failure pair.
 */
class ImportSpineLifecycleEndToEndTest {
    private val ledgerId = LedgerId("ledger-p402")
    private val cny = CurrencyUnit("CNY", 2)

    // Pinned digest constants, cross-checked against an independent JVM SHA-256 over
    // the hand-written canonical JSON (ImportContentFingerprintJvmTest).
    private val hashR1 = "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2"
    private val hashR2 = "sha256:5a5860ec8dd13eaa03b45627e5403c4ce62cd051c57e3a5a9d5c40f871245c89"
    private val hashR3 = "sha256:911f0b27473a382752837ac1eaca05e9f7ab1d13fc944b8e5e349b30fb86fe35"
    private val hashR5 = "sha256:80b823a2a5a392a431c15e84b2ca1783337c57d53a2b940f414b53befeef1e47"

    private fun r1(requestId: String = "req-a-intake") = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = "batch-p402-a",
        recordOrdinal = 0,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled"),
        completeness = ImportCompleteness.VALID_COMPLETE,
    )

    private fun r1Prime(requestId: String) = r1(requestId = requestId).copy(
        facts = ImportSourceFacts(12851, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled"),
    )

    private fun r2() = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-b-intake")),
        inputRef = "batch-p402-a",
        recordOrdinal = 1,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts = ImportSourceFacts(1000000, "CNY", 2, "2026-08-05T09:00:00+08:00", "in", "settled"),
        completeness = ImportCompleteness.VALID_COMPLETE,
    )

    private fun r3() = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-c-intake")),
        inputRef = "batch-p402-b",
        recordOrdinal = 0,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts = ImportSourceFacts(4500, "CNY", 2, "2026-08-06T18:45:00+08:00", "out", null),
        completeness = ImportCompleteness.VALID_INCOMPLETE,
    )

    private fun r5() = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-e-intake")),
        inputRef = "batch-p402-c",
        recordOrdinal = 0,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts = ImportSourceFacts(888800, "CNY", 2, "2026-08-08T10:00:00+08:00", "in", "settled"),
        completeness = ImportCompleteness.VALID_COMPLETE,
    )

    private fun intakeIds(prefix: String, statusId: String) = ImportIntakeIds(
        sourceId = ImportSourceId("source-$prefix"),
        evidenceId = ImportEvidenceId("evidence-$prefix"),
        candidateId = ImportCandidateId("candidate-$prefix"),
        statusHistoryId = ImportStatusHistoryId(statusId),
    )

    private fun commitIds(
        confirmation: String,
        statusId: String,
        tx: String,
        version: String,
        postingSet: String,
        postingIds: List<String>,
    ) = ImportCommitIds(
        confirmationId = ImportConfirmationId(confirmation),
        statusHistoryId = ImportStatusHistoryId(statusId),
        formalIds = ImportFormalIds(
            transactionId = TransactionId(tx),
            versionId = TransactionVersionId(version),
            postingSetId = PostingSetId(postingSet),
            postingIds = postingIds.map(::PostingId),
        ),
    )

    private class BatchIntakeIdSource(private val batches: List<ImportIntakeIds>) : ImportIntakeIdSource {
        val calls = AtomicInteger(0)
        override fun next(): ImportIntakeIds {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "intake id batch exhausted" }
            return batches[index]
        }
    }

    private class BatchCommitIdSource(private val batches: List<ImportCommitIds>) : ImportIdSource {
        val calls = AtomicInteger(0)
        override fun next(): ImportCommitIds {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "commit id batch exhausted" }
            return batches[index]
        }
    }

    private class BatchStatusIdSource(private val batches: List<ImportStatusHistoryId>) : ImportStatusIdSource {
        val calls = AtomicInteger(0)
        override fun next(): ImportStatusHistoryId {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "status id batch exhausted" }
            return batches[index]
        }
    }

    private class OrdinaryFlowFormalFactory(
        private val catalog: LedgerCatalog,
        private val categoryId: CategoryId,
        private val fundingAccountId: AccountId,
    ) : ImportCandidateFormalFactory {
        override fun create(
            input: ImportCandidateFormalizationInput,
            ids: ImportCommitIds,
        ): DomainResult<ImportFormalCommit> {
            val resolved = input.resolved
            val decisionFields = input.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow
            val currency = CurrencyUnit(resolved.currencyCode, resolved.currencyPrecision)
            val money = Money.ofMinor(resolved.amountMinor, currency)
            val times = TransactionTimes.collapsed(Instant.parse(resolved.occurredAt))
            return when (resolved.directionToken) {
                "out" -> createAssetPaidOrdinaryExpense(
                    catalog,
                    AssetPaidOrdinaryExpenseCommand(input.ledgerId, money, decisionFields.categoryId, decisionFields.fundingAccountId, times),
                    AssetPaidOrdinaryExpenseIds(
                        transactionId = ids.formalIds.transactionId,
                        versionId = ids.formalIds.versionId,
                        postingSetId = ids.formalIds.postingSetId,
                        expensePostingId = ids.formalIds.postingIds[0],
                        paymentPostingId = ids.formalIds.postingIds[1],
                    ),
                ).toSpineCommit(ids)
                "in" -> createAssetReceivedOrdinaryIncome(
                    catalog,
                    AssetReceivedOrdinaryIncomeCommand(input.ledgerId, money, decisionFields.categoryId, decisionFields.fundingAccountId, times),
                    AssetReceivedOrdinaryIncomeIds(
                        transactionId = ids.formalIds.transactionId,
                        versionId = ids.formalIds.versionId,
                        postingSetId = ids.formalIds.postingSetId,
                        receivingPostingId = ids.formalIds.postingIds[0],
                        incomePostingId = ids.formalIds.postingIds[1],
                    ),
                ).toSpineCommit(ids)
                else -> DomainResult.Failure(DomainViolation.InvalidOrdinaryIncome)
            }
        }

        private fun DomainResult<com.unifiedledger.domain.FormalTransaction>.toSpineCommit(
            ids: ImportCommitIds,
        ): DomainResult<ImportFormalCommit> = when (this) {
            is DomainResult.Success -> DomainResult.Success(ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, value))
            is DomainResult.Failure -> DomainResult.Failure(violation)
        }
    }

    private fun catalog(): LedgerCatalog = when (
        val result = LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("account-asset-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("expense-account-food"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("income-account-salary"), ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
            ),
            categories = listOf(
                Category(CategoryId("category-primary-food"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-food"), ledgerId, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-primary-salary"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                Category(CategoryId("category-salary"), ledgerId, parentId = CategoryId("category-primary-salary"), postingAccountId = AccountId("income-account-salary"), active = true, kind = CategoryKind.INCOME),
            ),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("spine test catalog failure: ${result.violation}")
    }

    private class Executor(
        val database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        val catalog: LedgerCatalog,
        val intakeIds: ImportIntakeIdSource,
        val commitIds: ImportIdSource,
        val statusIds: ImportStatusIdSource,
    ) {
        val store = SqlDelightImportSpineStore(database, driver)

        fun intake(request: ImportIntakeRequest): ImportIntakeResult =
            ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirm(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult =
            ConfirmImportCandidate(
                store,
                commitIds,
                OrdinaryFlowFormalFactory(catalog, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).categoryId, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).fundingAccountId),
            ).execute(request)

        fun reject(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult =
            RejectImportCandidate(store, statusIds).execute(request)
    }

    private fun confirmRequest(requestId: String = "req-a-confirm", candidate: String = "candidate-a", hash: String = hashR1, category: String = "category-food", funding: String = "account-asset-a", confirmedAt: String? = "2026-08-07T10:00:00+08:00") =
        ImportCandidateConfirmRequest(
            identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
            candidateId = ImportCandidateId(candidate),
            expectedContentHash = hash,
            explicitConfirmedAt = confirmedAt,
            decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(
                categoryId = CategoryId(category),
                fundingAccountId = AccountId(funding),
            ),
        )

    private fun rejectRequest(requestId: String = "req-b-reject", candidate: String = "candidate-b", hash: String = hashR2) =
        ImportCandidateRejectRequest(
            identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
            candidateId = ImportCandidateId(candidate),
            expectedContentHash = hash,
        )

    private fun spineCounts(database: LedgerDatabase) = listOf(
        database.ledgerQueries.countImportRequests().executeAsOne(),
        database.ledgerQueries.countImportSourceRecords().executeAsOne(),
        database.ledgerQueries.countImportEvidence().executeAsOne(),
        database.ledgerQueries.countImportCandidates().executeAsOne(),
        database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne(),
        database.ledgerQueries.countImportDecisionSnapshots().executeAsOne(),
        database.ledgerQueries.countImportConfirmations().executeAsOne(),
        database.ledgerQueries.countImportReceipts().executeAsOne(),
    )

    private fun formalCounts(database: LedgerDatabase) = listOf(
        database.ledgerQueries.countTransactions().executeAsOne(),
        database.ledgerQueries.countVersions().executeAsOne(),
        database.ledgerQueries.countPostings().executeAsOne(),
    )

    private fun receiptOf(database: LedgerDatabase, requestId: String): ImportReceipt {
        val row = database.ledgerQueries.selectImportReceiptByRequest(ledgerId.value, requestId).executeAsOne()
        return ImportReceipt(
            requestId = ImportRequestId(row.request_id),
            sourceId = row.source_id?.let(::ImportSourceId),
            evidenceId = row.evidence_id?.let(::ImportEvidenceId),
            candidateId = ImportCandidateId(row.candidate_id),
            confirmationId = row.confirmation_id?.let(::ImportConfirmationId),
            transactionId = row.transaction_id?.let(::TransactionId),
        )
    }

    private fun scalarText(driver: JdbcSqliteDriver, sql: String): String = driver.executeQuery(
        null,
        sql,
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0)!!)
        },
        0,
    ).value

    @Test
    fun executesO01ToO17WithStableReplayStatusTransitionsAndReceipts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val intakeIds = BatchIntakeIdSource(
                listOf(
                    intakeIds("a", "status-a-1"),
                    intakeIds("b", "status-b-1"),
                    intakeIds("c", "status-c-1"),
                ),
            )
            val commitIds = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            val statusIds = BatchStatusIdSource(listOf(ImportStatusHistoryId("status-b-2")))
            val executor = Executor(database, driver, catalog(), intakeIds, commitIds, statusIds)

            // O-01: intake R1 accepted.
            val o01 = assertIs<ImportIntakeResult.Accepted>(executor.intake(r1()))
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.SOURCE, "source-a"),
                    ImportReturnedId(ImportReturnedIdKind.EVIDENCE, "evidence-a"),
                    ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-a"),
                ),
                o01.returnedIds,
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-a-intake"), ImportSourceId("source-a"), ImportEvidenceId("evidence-a"), ImportCandidateId("candidate-a"), null, null),
                o01.receipt,
            )
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            val sourceA = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-a-intake").executeAsOne()
            assertEquals(hashR1, sourceA.content_hash)
            assertEquals(1L, sourceA.contract_version)
            assertEquals("valid_complete", sourceA.completeness)
            assertEquals("ordinary_flow_source", sourceA.record_kind)
            assertEquals(12850L, sourceA.amount_minor)
            assertEquals("evidence-a", database.ledgerQueries.selectImportEvidenceForSource(ledgerId.value, "source-a").executeAsOne())
            assertEquals("source_observation", scalarText(driver, "SELECT evidence_kind FROM import_evidence WHERE ledger_id = 'ledger-p402' AND evidence_id = 'evidence-a'"))
            assertEquals("2026-08-01T12:30:00+08:00", scalarText(driver, "SELECT observed_at FROM import_evidence WHERE ledger_id = 'ledger-p402' AND evidence_id = 'evidence-a'"))
            val historyA1 = database.ledgerQueries.selectImportStatusHistoryByCandidate(ledgerId.value, "candidate-a").executeAsList()
            assertEquals(1, historyA1.size)
            assertEquals(1L, historyA1[0].sequence)
            assertEquals("status-a-1", historyA1[0].status_id)
            assertEquals("pending_confirmation", historyA1[0].status)
            assertEquals("req-a-intake", historyA1[0].request_id)
            assertEquals("creation", historyA1[0].operation_class)

            // O-02: same-request equivalent replay.
            val o02 = assertIs<ImportIntakeResult.NoChange>(executor.intake(r1()))
            assertEquals(o01.receipt, o02.receipt)
            assertEquals(o01.returnedIds, o02.returnedIds)
            assertEquals("equivalent_replay", o02.reasonCode)
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // O-03: raw identity idempotent re-intake (different request, equivalent).
            val o03 = assertIs<ImportIntakeResult.NoChange>(executor.intake(r1(requestId = "req-a-intake-2")))
            assertNull(o03.receipt)
            assertEquals(o01.returnedIds, o03.returnedIds)
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // P407-Q-004: raw identity replay includes all persisted funding audit facts.
            listOf(
                r1(requestId = "req-a-funding-state").copy(facts = r1().facts.copy(fundingState = com.unifiedledger.application.ImportFundingState.NO_FUNDS)),
                r1(requestId = "req-a-funding-rule").copy(facts = r1().facts.copy(fundingRuleId = "source-contract-v2")),
                r1(requestId = "req-a-funding-version").copy(facts = r1().facts.copy(fundingRuleVersion = 2)),
                r1(requestId = "req-a-generated-at").copy(candidateGeneratedAt = "2026-08-20T00:00:00Z"),
            ).forEach { changed ->
                val rejected = assertIs<ImportIntakeResult.Rejected>(executor.intake(changed))
                assertEquals("SPINE_IDENTITY_COLLISION", rejected.diagnostic.code)
                assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            }

            // O-04: same raw identity, different content: hard collision.
            val o04 = assertIs<ImportIntakeResult.Rejected>(executor.intake(r1Prime(requestId = "req-a-intake-3")))
            assertEquals("SPINE_IDENTITY_COLLISION", o04.diagnostic.code)
            assertEquals("fatal", o04.diagnostic.severity)
            assertEquals("record", o04.diagnostic.scope)
            assertEquals("batch-p402-a", o04.diagnostic.location.inputRef)
            assertEquals(0, o04.diagnostic.location.recordOrdinal)
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // O-05: confirm C1 accepted, creating the formal transaction.
            val o05 = assertIs<ImportCandidateDecisionResult.Accepted>(executor.confirm(confirmRequest()))
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-a"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-a"),
                ),
                o05.returnedIds,
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-a-confirm"), null, null, ImportCandidateId("candidate-a"), ImportConfirmationId("confirmation-a"), TransactionId("tx-a")),
                o05.receipt,
            )
            assertEquals(1, commitIds.calls.get())
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            val historyA2 = database.ledgerQueries.selectImportStatusHistoryByCandidate(ledgerId.value, "candidate-a").executeAsList()
            assertEquals(2, historyA2.size)
            assertEquals(2L, historyA2[1].sequence)
            assertEquals("status-a-2", historyA2[1].status_id)
            assertEquals("confirmed", historyA2[1].status)
            assertEquals("req-a-confirm", historyA2[1].request_id)
            assertEquals("creation", historyA2[1].operation_class)
            val confirmationA = database.ledgerQueries.selectImportConfirmationByRequest(ledgerId.value, "req-a-confirm").executeAsOne()
            assertEquals("confirmation-a", confirmationA.confirmation_id)
            assertEquals("candidate-a", confirmationA.candidate_id)
            assertEquals("status-a-2", confirmationA.status_id)
            assertEquals("tx-a", confirmationA.transaction_id)
            assertEquals("creation", confirmationA.operation_class)
            assertEquals("2026-08-07T10:00:00+08:00", confirmationA.confirmed_at)
            val decisionA = database.ledgerQueries.selectImportDecisionSnapshotByRequest(ledgerId.value, "req-a-confirm").executeAsOne()
            assertEquals("confirm", decisionA.decision)
            assertEquals("candidate-a", decisionA.candidate_id)
            assertEquals(hashR1, decisionA.expected_content_hash)
            assertEquals("category-food", decisionA.category_id)
            assertEquals("account-asset-a", decisionA.funding_account_id)
            assertEquals("2026-08-07T10:00:00+08:00", decisionA.explicit_confirmed_at)
            val postingsA = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-a").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-expense-a", "expense-account-food", 12850L, "CNY", 2L),
                    listOf("posting-asset-a", "account-asset-a", -12850L, "CNY", 2L),
                ),
                postingsA.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )

            // O-06: same-request equivalent confirm replay.
            val o06 = assertIs<ImportCandidateDecisionResult.NoChange>(executor.confirm(confirmRequest()))
            assertEquals(o05.receipt, o06.receipt)
            assertEquals("equivalent_replay", o06.reasonCode)
            assertEquals(1, commitIds.calls.get())
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))

            // O-07: re-confirm a confirmed candidate with a new request.
            val o07 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(confirmRequest(requestId = "req-a-confirm-2")),
            )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", o07.diagnostic.code)
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))

            // O-08: same request, different category: request identity conflict.
            val o08 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(confirmRequest(category = "category-other")),
            )
            assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", o08.diagnostic.code)
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))

            // O-09: same request, stale expected hash: stale fingerprint rejection.
            val o09 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(confirmRequest(hash = hashR2)),
            )
            assertEquals("SPINE_STALE_FINGERPRINT", o09.diagnostic.code)
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))

            // O-10: intake R2 accepted (C2 pending).
            assertIs<ImportIntakeResult.Accepted>(executor.intake(r2()))
            assertEquals(listOf(3L, 2L, 2L, 2L, 3L, 1L, 1L, 3L), spineCounts(database))

            // O-11: reject C2 (manual disposition).
            val o11 = assertIs<ImportCandidateDecisionResult.Accepted>(executor.reject(rejectRequest()))
            assertEquals(listOf(ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-b")), o11.returnedIds)
            assertEquals(
                ImportReceipt(ImportRequestId("req-b-reject"), null, null, ImportCandidateId("candidate-b"), null, null),
                o11.receipt,
            )
            assertEquals(1, statusIds.calls.get())
            assertEquals(listOf(4L, 2L, 2L, 2L, 4L, 2L, 1L, 4L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            val historyB = database.ledgerQueries.selectImportStatusHistoryByCandidate(ledgerId.value, "candidate-b").executeAsList()
            assertEquals(2, historyB.size)
            assertEquals(2L, historyB[1].sequence)
            assertEquals("status-b-2", historyB[1].status_id)
            assertEquals("rejected", historyB[1].status)
            assertEquals("status_transition", historyB[1].operation_class)
            val decisionB = database.ledgerQueries.selectImportDecisionSnapshotByRequest(ledgerId.value, "req-b-reject").executeAsOne()
            assertEquals("reject", decisionB.decision)
            assertEquals(null, decisionB.category_id)
            assertEquals(null, decisionB.funding_account_id)
            assertEquals(null, decisionB.explicit_confirmed_at)

            // O-12: same-request reject replay.
            val o12 = assertIs<ImportCandidateDecisionResult.NoChange>(executor.reject(rejectRequest()))
            assertEquals(o11.receipt, o12.receipt)
            assertEquals("equivalent_replay", o12.reasonCode)
            assertEquals(listOf(4L, 2L, 2L, 2L, 4L, 2L, 1L, 4L), spineCounts(database))

            // O-13: reject C2 again with a new request.
            val o13 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.reject(rejectRequest(requestId = "req-b-reject-2")),
            )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", o13.diagnostic.code)
            assertEquals(listOf(4L, 2L, 2L, 2L, 4L, 2L, 1L, 4L), spineCounts(database))

            // O-14: confirm the rejected C2.
            val o14 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(confirmRequest(requestId = "req-b-confirm", candidate = "candidate-b", hash = hashR2, category = "category-salary")),
            )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", o14.diagnostic.code)
            assertEquals(listOf(4L, 2L, 2L, 2L, 4L, 2L, 1L, 4L), spineCounts(database))

            // O-15: intake R3 (valid_incomplete) accepted with an incomplete candidate.
            assertIs<ImportIntakeResult.Accepted>(executor.intake(r3()))
            assertEquals(listOf(5L, 3L, 3L, 3L, 5L, 2L, 1L, 5L), spineCounts(database))
            val historyC = database.ledgerQueries.selectImportStatusHistoryByCandidate(ledgerId.value, "candidate-c").executeAsList()
            assertEquals(1, historyC.size)
            assertEquals("incomplete", historyC[0].status)
            assertEquals("creation", historyC[0].operation_class)
            assertEquals("2026-08-06T18:45:00+08:00", scalarText(driver, "SELECT observed_at FROM import_evidence WHERE ledger_id = 'ledger-p402' AND evidence_id = 'evidence-c'"))

            // O-16: confirm the incomplete C3.
            val o16 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(confirmRequest(requestId = "req-c-confirm", candidate = "candidate-c", hash = hashR3)),
            )
            assertEquals("SPINE_CANDIDATE_INCOMPLETE", o16.diagnostic.code)
            assertEquals(listOf(5L, 3L, 3L, 3L, 5L, 2L, 1L, 5L), spineCounts(database))

            // O-17: reject the incomplete C3.
            val o17 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.reject(rejectRequest(requestId = "req-c-reject", candidate = "candidate-c", hash = hashR3)),
            )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", o17.diagnostic.code)
            assertEquals(listOf(5L, 3L, 3L, 3L, 5L, 2L, 1L, 5L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
        } finally {
            driver.close()
        }
    }

    @Test
    fun concurrentIntakesCommitOnceWithoutLoserResidue() {
        // O-18: concurrent identical intake (same request).
        runIntakeScenario("spine-intake-o18-") { url ->
            val results = concurrentExecute(
                url,
                listOf(
                    { intakeOn(url, r1()) },
                    { intakeOn(url, r1()) },
                ),
            )
            assertEquals(1, results.count { it is ImportIntakeResult.Accepted })
            assertEquals(1, results.count { it is ImportIntakeResult.NoChange })
            assertSpineCounts(url, listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L))
        }

        // O-19: concurrent conflicting intake (same request, different content).
        runIntakeScenario("spine-intake-o19-") { url ->
            val results = concurrentExecute(
                url,
                listOf(
                    { intakeOn(url, r1(requestId = "req-a-race")) },
                    { intakeOn(url, r1Prime(requestId = "req-a-race")) },
                ),
            )
            assertEquals(1, results.count { it is ImportIntakeResult.Accepted })
            val o19Rejected = results.filterIsInstance<ImportIntakeResult.Rejected>().single()
            assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", o19Rejected.diagnostic.code)
            assertSpineCounts(url, listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L))
        }

        // O-20: concurrent distinct requests, same raw identity, equivalent content.
        runIntakeScenario("spine-intake-o20-") { url ->
            val results = concurrentExecute(
                url,
                listOf(
                    { intakeOn(url, r1(requestId = "req-a-intake-4")) },
                    { intakeOn(url, r1(requestId = "req-a-intake-5")) },
                ),
            )
            assertEquals(1, results.count { it is ImportIntakeResult.Accepted })
            assertEquals(1, results.count { it is ImportIntakeResult.NoChange })
            // The loser wrote nothing, not even a request row.
            assertSpineCounts(url, listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L))
        }
    }

    private fun runIntakeScenario(prefix: String, body: (String) -> Unit) {
        val path = Files.createTempFile(prefix, ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            body(url)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun assertSpineCounts(url: String, expected: List<Long>) {
        JdbcSqliteDriver(url).use { driver ->
            assertEquals(expected, spineCounts(LedgerDatabase(driver)))
        }
    }

    @Test
    fun concurrentConfirmationsCommitOnceWithoutConsumingLoserIds() {
        val path = Files.createTempFile("spine-confirm-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val catalog = catalog()
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                ExecuteImportIntake(
                    SqlDelightImportSpineStore(database, driver),
                    BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1"))),
                    ImportContentFingerprint(),
                ).execute(r1(requestId = "req-a-intake-6"))
            }
            // O-21: same confirm request from two threads; the shared ID source is
            // consumed exactly once.
            val sharedIds = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            val o21 = concurrentExecute(
                url,
                listOf(
                    { confirmOn(url, catalog, sharedIds, confirmRequest(requestId = "req-a-confirm-c1")) },
                    { confirmOn(url, catalog, sharedIds, confirmRequest(requestId = "req-a-confirm-c1")) },
                ),
            )
            assertEquals(1, o21.count { it is ImportCandidateDecisionResult.Accepted })
            assertEquals(1, o21.count { it is ImportCandidateDecisionResult.NoChange })
            assertEquals(1, sharedIds.calls.get())
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countImportConfirmations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            }

            // O-22: distinct confirm requests on the same candidate; the losing claim
            // leaves zero residue and consumes no ID. A fresh income candidate keeps
            // the scenario independent from O-21's confirmed candidate.
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                ExecuteImportIntake(
                    SqlDelightImportSpineStore(database, driver),
                    BatchIntakeIdSource(listOf(intakeIds("b", "status-b-1"))),
                    ImportContentFingerprint(),
                ).execute(r2())
            }
            val sharedIds2 = BatchCommitIdSource(
                listOf(commitIds("confirmation-b", "status-b-2", "tx-b", "version-b-v1", "posting-set-b", listOf("posting-asset-b", "posting-income-b"))),
            )
            val o22 = concurrentExecute(
                url,
                listOf(
                    { confirmOn(url, catalog, sharedIds2, confirmRequest(requestId = "req-b-confirm-c2", candidate = "candidate-b", hash = hashR2, category = "category-salary", confirmedAt = "2026-08-05T10:00:00+08:00")) },
                    { confirmOn(url, catalog, sharedIds2, confirmRequest(requestId = "req-b-confirm-c3", candidate = "candidate-b", hash = hashR2, category = "category-salary", confirmedAt = "2026-08-05T10:00:00+08:00")) },
                ),
            )
            assertEquals(1, o22.count { it is ImportCandidateDecisionResult.Accepted })
            val o22Rejected = o22.filterIsInstance<ImportCandidateDecisionResult.Rejected>().single()
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", o22Rejected.diagnostic.code)
            assertEquals(1, sharedIds2.calls.get())
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(2L, database.ledgerQueries.countImportConfirmations().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun injectedFailuresRollBackEverySpineOwnerAndKeepIdentityUsable() {
        // O-23: intake failure after the candidate insert.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val failingStore = SqlDelightImportSpineStore(
                database, driver,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE) error("injected") },
            )
            val batch1 = BatchIntakeIdSource(listOf(intakeIds("a-attempt-1", "status-a-1-attempt-1")))
            assertFailsWith<IllegalStateException> {
                ExecuteImportIntake(failingStore, batch1, ImportContentFingerprint()).execute(r1())
            }
            assertEquals(1, batch1.calls.get())
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            // The identity remains available: the corrected retry uses batch 2.
            val batch2 = BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), batch2, ImportContentFingerprint()).execute(r1()),
            )
            assertEquals(1, batch2.calls.get())
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
        } finally {
            driver.close()
        }

        // O-24: confirm failure after the formal persist.
        val driver2 = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver2)
            val database2 = LedgerDatabase(driver2)
            val catalog = catalog()
            val normalStore = SqlDelightImportSpineStore(database2, driver2)
            ExecuteImportIntake(
                normalStore,
                BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1"))),
                ImportContentFingerprint(),
            ).execute(r1())
            val failingStore = SqlDelightImportSpineStore(
                database2, driver2,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL) error("injected") },
            )
            val attempt1 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-a-attempt-1", "status-a-2-attempt-1", "tx-a-attempt-1",
                        "version-a-attempt-1-v1", "posting-set-a-attempt-1",
                        listOf("posting-expense-a-attempt-1", "posting-asset-a-attempt-1"),
                    ),
                ),
            )
            assertFailsWith<IllegalStateException> {
                ConfirmImportCandidate(
                    failingStore, attempt1,
                    OrdinaryFlowFormalFactory(catalog, CategoryId("category-food"), AccountId("account-asset-a")),
                ).execute(confirmRequest())
            }
            assertEquals(1, attempt1.calls.get())
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database2))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database2))
            // The corrected retry consumes batch 2 and commits all-or-nothing.
            val batch2 = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                ConfirmImportCandidate(
                    normalStore, batch2,
                    OrdinaryFlowFormalFactory(catalog, CategoryId("category-food"), AccountId("account-asset-a")),
                ).execute(confirmRequest()),
            )
            assertEquals(1, batch2.calls.get())
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database2))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database2))
        } finally {
            driver2.close()
        }
    }

    @Test
    fun incomeDomainFailureLeavesZeroResidueAndCorrectedRetryAccepts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog()
            val intakeIds = BatchIntakeIdSource(listOf(intakeIds("e", "status-e-1")))
            val executor = Executor(database, driver, catalog, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))

            // O-28: intake R5 accepted (C5 pending).
            assertIs<ImportIntakeResult.Accepted>(executor.intake(r5()))
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // O-29: confirm with an unknown category: domain failure, zero residue.
            val attempt1 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-b-attempt-1", "status-e-2-attempt-1", "tx-b-attempt-1",
                        "version-e-attempt-1-v1", "posting-set-e-attempt-1",
                        listOf("posting-asset-e-attempt-1", "posting-income-e-attempt-1"),
                    ),
                ),
            )
            val o29 = assertIs<ImportCandidateDecisionResult.Rejected>(
                Executor(database, driver, catalog, intakeIds, attempt1, BatchStatusIdSource(emptyList())).confirm(
                    confirmRequest(
                        requestId = "req-e-confirm", candidate = "candidate-e", hash = hashR5,
                        category = "category-unknown", funding = "account-asset-a",
                        confirmedAt = "2026-08-09T09:00:00+08:00",
                    ),
                ),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", o29.diagnostic.code)
            assertEquals(1, attempt1.calls.get())
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))

            // O-30: corrected retry with the same request identity: accepted.
            val batch2 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-b", "status-e-2", "tx-b",
                        "version-e-v1", "posting-set-e",
                        listOf("posting-asset-e", "posting-income-e"),
                    ),
                ),
            )
            val o30 = assertIs<ImportCandidateDecisionResult.Accepted>(
                Executor(database, driver, catalog, intakeIds, batch2, BatchStatusIdSource(emptyList())).confirm(
                    confirmRequest(
                        requestId = "req-e-confirm", candidate = "candidate-e", hash = hashR5,
                        category = "category-salary", funding = "account-asset-a",
                        confirmedAt = "2026-08-09T09:00:00+08:00",
                    ),
                ),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-b"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-b"),
                ),
                o30.returnedIds,
            )
            assertEquals(1, batch2.calls.get())
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            val postingsE = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-e").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-asset-e", "account-asset-a", 888800L, "CNY", 2L),
                    listOf("posting-income-e", "income-account-salary", -888800L, "CNY", 2L),
                ),
                postingsE.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun spineOwnersAreAppendOnlyAndStatusTransitionsAreGuarded() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val executor = Executor(
                database, driver, catalog(),
                BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1"), intakeIds("b", "status-b-1"), intakeIds("c", "status-c-1"))),
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
                ),
                BatchStatusIdSource(emptyList()),
            )
            executor.intake(r1())
            executor.confirm(confirmRequest())
            executor.intake(r2())
            executor.intake(r3())

            val mutations = listOf(
                "UPDATE import_request SET operation = 'intake'",
                "DELETE FROM import_request",
                "UPDATE import_source_record SET amount_minor = 1",
                "DELETE FROM import_source_record",
                "UPDATE import_evidence SET observed_at = 'changed'",
                "DELETE FROM import_evidence",
                "UPDATE import_candidate SET confidence = '9.99'",
                "DELETE FROM import_candidate",
                "UPDATE import_candidate_requires_confirmation SET requirement = 'formal_transaction_creation'",
                "DELETE FROM import_candidate_requires_confirmation",
                "UPDATE import_candidate_status_history SET status = 'incomplete'",
                "DELETE FROM import_candidate_status_history",
                "UPDATE import_candidate_decision_snapshot SET decision = 'reject'",
                "DELETE FROM import_candidate_decision_snapshot",
                "UPDATE import_confirmation SET confirmed_at = 'changed'",
                "DELETE FROM import_confirmation",
                "UPDATE import_receipt SET outcome = 'accepted'",
                "DELETE FROM import_receipt",
            )
            mutations.forEach { statement ->
                assertFailsWith<SQLException>(statement) { driver.execute(null, statement, 0) }
            }

            // Transition guards: confirmed candidate has no outgoing transition.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-a', 3, 'status-a-3', 'rejected', 'req-a-confirm', 'status_transition')",
                    0,
                )
            }
            // pending -> incomplete is not a legal transition.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-b', 2, 'status-b-incomplete', 'incomplete', 'req-b-intake', 'status_transition')",
                    0,
                )
            }
            // Rejected candidate is terminal (the legal pending -> rejected first).
            driver.execute(
                null,
                "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-b', 2, 'status-b-2', 'rejected', 'req-b-intake', 'status_transition')",
                0,
            )
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-b', 3, 'status-b-3', 'confirmed', 'req-b-intake', 'creation')",
                    0,
                )
            }
            // Sequence must be continuous from the previous maximum.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-b', 5, 'status-b-5', 'confirmed', 'req-b-intake', 'creation')",
                    0,
                )
            }
            // incomplete is terminal: no outgoing transition.
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-c', 2, 'status-c-2', 'confirmed', 'req-c-intake', 'creation')",
                    0,
                )
            }
            // First row of a candidate must be pending_confirmation or incomplete:
            // a raw-inserted candidate without history cannot start confirmed/rejected.
            driver.execute(null, "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('ledger-p402', 'req-g', 'intake')", 0)
            driver.execute(
                null,
                "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p402', 'source-g', 'req-g', 'batch-p402-g', 0, 'ordinary_flow_source', 'sha256:g', 1, 'valid_complete', 100, 'CNY', 2, '2026-08-01T12:30:00+08:00', 'out', 'settled')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO import_candidate(ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) VALUES ('ledger-p402', 'candidate-g', 'source-g', 'ordinary_flow', '1.00', 'ordinary_flow_source', 1)",
                0,
            )
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-g', 1, 'status-g-1', 'confirmed', 'req-g', 'creation')",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p402', 'candidate-g', 1, 'status-g-1', 'rejected', 'req-g', 'status_transition')",
                    0,
                )
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun acceptedOwnersSurviveReopenAndReplayOriginalReceipts() {
        val path = Files.createTempFile("spine-reopen-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val catalog = catalog()
        try {
            val originalReceipts = JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val executor = Executor(
                    database, driver, catalog,
                    BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1"))),
                    BatchCommitIdSource(
                        listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
                    ),
                    BatchStatusIdSource(emptyList()),
                )
                val intake = assertIs<ImportIntakeResult.Accepted>(executor.intake(r1()))
                val confirm = assertIs<ImportCandidateDecisionResult.Accepted>(executor.confirm(confirmRequest()))
                intake.receipt to confirm.receipt
            }
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val executor = Executor(
                    database, driver, catalog,
                    BatchIntakeIdSource(emptyList()),
                    BatchCommitIdSource(emptyList()),
                    BatchStatusIdSource(emptyList()),
                )
                val replayIntake = assertIs<ImportIntakeResult.NoChange>(executor.intake(r1()))
                assertEquals(originalReceipts.first, replayIntake.receipt)
                val replayConfirm = assertIs<ImportCandidateDecisionResult.NoChange>(executor.confirm(confirmRequest()))
                assertEquals(originalReceipts.second, replayConfirm.receipt)
                assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
                assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun storeRejectsLedgerMismatchedIdentityAndSnapshot() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val otherLedger = LedgerId("ledger-other")

            assertFailsWith<IllegalArgumentException> {
                store.commitIntake(
                    ImportRequestIdentity(otherLedger, ImportRequestId("req-x")),
                    ImportIntakeSnapshot(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-x")),
                        inputRef = "batch-p402-a",
                        recordOrdinal = 0,
                        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                        facts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled"),
                        completeness = ImportCompleteness.VALID_COMPLETE,
                        contentHash = "sha256:test",
                    ),
                ) { error("must not allocate") }
            }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), spineCounts(database))
        } finally {
            driver.close()
        }
    }

    @Test
    fun winningClaimWithStaleExpectedHashRejectsWithZeroWritesAndIdentityStaysUsable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog()
            val intakeIds = BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1")))
            val commitIds = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            val executor = Executor(database, driver, catalog, intakeIds, commitIds, BatchStatusIdSource(emptyList()))
            assertIs<ImportIntakeResult.Accepted>(executor.intake(r1()))

            // Claim succeeds (new request, pending candidate), hash mismatch: stale.
            val stale = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(confirmRequest(requestId = "req-a-confirm-stale", hash = hashR2)),
            )
            assertEquals("SPINE_STALE_FINGERPRINT", stale.diagnostic.code)
            assertEquals(0, commitIds.calls.get())
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))

            // The identity stays available: the corrected retry with the same request id.
            val corrected = assertIs<ImportCandidateDecisionResult.Accepted>(
                executor.confirm(confirmRequest(requestId = "req-a-confirm-stale")),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-a"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-a"),
                ),
                corrected.returnedIds,
            )
            assertEquals(1, commitIds.calls.get())
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
        } finally {
            driver.close()
        }
    }

    private fun intakeOn(url: String, request: ImportIntakeRequest): ImportIntakeResult =
        JdbcSqliteDriver(url).use { driver ->
            val database = LedgerDatabase(driver)
            ExecuteImportIntake(
                SqlDelightImportSpineStore(database, driver),
                BatchIntakeIdSource(listOf(intakeIds(request.inputRef.substringAfterLast('-') + "-" + request.recordOrdinal, "status-" + request.inputRef.substringAfterLast('-') + "-1"))),
                ImportContentFingerprint(),
            ).execute(request)
        }

    @Test
    fun p407ExactTupleCreatesDirectedCandidateWithBothSourceIds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val ids = BatchIntakeIdSource(listOf(
                intakeIds("p407-a", "status-p407-a"),
                intakeIds("p407-b", "status-p407-b").copy(
                    duplicateIds = listOf(com.unifiedledger.application.ImportDuplicateIntakeIds(
                        com.unifiedledger.application.ImportDuplicateCandidateId("duplicate-p407-b-a"),
                        ImportStatusHistoryId("duplicate-status-p407-b-a"),
                    )),
                ),
                intakeIds("p407-c", "status-p407-c").copy(
                    duplicateIds = listOf(
                        com.unifiedledger.application.ImportDuplicateIntakeIds(com.unifiedledger.application.ImportDuplicateCandidateId("duplicate-p407-c-a"), ImportStatusHistoryId("duplicate-status-p407-c-a")),
                        com.unifiedledger.application.ImportDuplicateIntakeIds(com.unifiedledger.application.ImportDuplicateCandidateId("duplicate-p407-c-b"), ImportStatusHistoryId("duplicate-status-p407-c-b")),
                    ),
                ),
            ))
            val intake = ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), ids, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(r1("request-p407-a")))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(r1("request-p407-b").copy(inputRef = "batch-p407-b")))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(r1("request-p407-c").copy(inputRef = "batch-p407-c")))
            val candidates = driver.executeQuery(
                null,
                """
                    SELECT candidate.candidate_id, candidate.subject_source_id,
                           candidate.possible_existing_source_id, candidate.kind,
                           history.history_id, history.status
                    FROM import_duplicate_candidate AS candidate
                    JOIN import_duplicate_status_history AS history
                      ON history.ledger_id = candidate.ledger_id
                     AND history.candidate_id = candidate.candidate_id
                    ORDER BY candidate.candidate_id
                """.trimIndent(),
                { cursor ->
                    app.cash.sqldelight.db.QueryResult.Value(
                        buildList {
                            while (cursor.next().value) {
                                add(
                                    listOf(
                                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                                        cursor.getString(3), cursor.getString(4), cursor.getString(5),
                                    ),
                                )
                            }
                        },
                    )
                },
                0,
            ).value
            assertEquals(3L, driver.executeQuery(null, "SELECT count(*) FROM import_duplicate_candidate", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals(3L, driver.executeQuery(null, "SELECT count(*) FROM import_duplicate_status_history", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals(
                listOf(
                    listOf("duplicate-p407-b-a", "source-p407-b", "source-p407-a", "EXACT_BUSINESS_TUPLE", "duplicate-status-p407-b-a", "DEFERRED"),
                    listOf("duplicate-p407-c-a", "source-p407-c", "source-p407-a", "EXACT_BUSINESS_TUPLE", "duplicate-status-p407-c-a", "DEFERRED"),
                    listOf("duplicate-p407-c-b", "source-p407-c", "source-p407-b", "EXACT_BUSINESS_TUPLE", "duplicate-status-p407-c-b", "DEFERRED"),
                ),
                candidates,
            )
        } finally { driver.close() }
    }

    @Test
    fun p407DuplicateHistoryRejectsOrphanAndMismatchedOwners() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            fun execute(sql: String) = driver.execute(null, sql, 0)
            execute("INSERT INTO import_request VALUES ('ledger-p407-owner','request-create','intake')")
            execute("INSERT INTO import_request VALUES ('ledger-p407-owner','request-other','intake')")
            execute("INSERT INTO import_source_record(ledger_id,source_id,owner_request_id,input_ref,record_ordinal,record_kind,content_hash,contract_version,completeness,amount_minor,currency_code,currency_precision,occurred_at,direction_token,status_token,funding_state,funding_rule_id,funding_rule_version,candidate_generated_at) VALUES ('ledger-p407-owner','source-owner','request-create','owner-batch',0,'ordinary_flow_source','sha256:owner',1,'valid_complete',100,'CNY',2,'2026-08-20T00:00:00Z','out','settled','SETTLED','owner-rule',1,'2026-08-20T00:00:00Z')")
            execute("INSERT INTO import_duplicate_candidate VALUES ('ledger-p407-owner','duplicate-owner','source-owner',NULL,'CLOSED_OR_FAILED_NO_FUNDS','sha256:owner','{}','source_declared + mechanical_decode','exact','p407_exact_business_tuple_v1',1,'2026-08-20T00:00:00Z','request-create')")

            assertFailsWith<SQLException> {
                execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',1,'history-wrong-create','DEFERRED','request-other','creation')")
            }
            execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',1,'history-create','DEFERRED','request-create','creation')")
            assertFailsWith<SQLException> {
                execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',1,'history-duplicate-create','DEFERRED','request-create','creation')")
            }
            assertFailsWith<SQLException> {
                execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',2,'history-orphan-review','CONFIRMED_DISTINCT','review-missing','status_transition')")
            }
            assertFailsWith<SQLException> {
                execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',2,'history-second-deferred','DEFERRED','request-create','creation')")
            }
            execute("INSERT INTO import_duplicate_review_request VALUES ('ledger-p407-owner','review-other','review_duplicate','fp-owner','PENDING',NULL)")
            assertFailsWith<SQLException> {
                execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',2,'history-mismatched-review','CONFIRMED_DISTINCT','review-other','status_transition')")
            }
            execute("INSERT INTO import_duplicate_review_snapshot VALUES ('ledger-p407-owner','review-other','duplicate-owner','sha256:owner','CONFIRMED_DISTINCT','manual','2026-08-20T00:00:00Z','reviewer','2026-08-20T00:00:00Z','review-owner')")
            assertFailsWith<SQLException> {
                execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',2,'history-pending-review','CONFIRMED_DISTINCT','review-other','status_transition')")
            }
            execute("UPDATE import_duplicate_review_request SET outcome = 'ACCEPTED' WHERE ledger_id = 'ledger-p407-owner' AND request_id = 'review-other'")
            execute("INSERT INTO import_duplicate_status_history VALUES ('ledger-p407-owner','duplicate-owner',2,'history-review','CONFIRMED_DISTINCT','review-other','status_transition')")
            assertFailsWith<SQLException> {
                execute("INSERT INTO import_duplicate_review_receipt VALUES ('ledger-p407-owner','review-other','duplicate-owner','review-owner','history-wrong','CONFIRMED_DISTINCT')")
            }
            execute("INSERT INTO import_duplicate_review_receipt VALUES ('ledger-p407-owner','review-other','duplicate-owner','review-owner','history-review','CONFIRMED_DISTINCT')")
            assertEquals(2L, driver.executeQuery(null, "SELECT count(*) FROM import_duplicate_status_history", { cursor -> cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!) }, 0).value)
        } finally { driver.close() }
    }

    @Test
    fun p407ConfirmedDuplicateBlocksFormalFactoryAndLeavesFormalRowsUnchanged() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val ids = BatchIntakeIdSource(listOf(
                intakeIds("p407-block-a", "status-p407-block-a"),
                intakeIds("p407-block-b", "status-p407-block-b").copy(
                    duplicateIds = listOf(com.unifiedledger.application.ImportDuplicateIntakeIds(
                        com.unifiedledger.application.ImportDuplicateCandidateId("duplicate-p407-block"),
                        ImportStatusHistoryId("duplicate-status-p407-block"),
                    )),
                ),
            ))
            val executor = Executor(database, driver, catalog(), ids, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            executor.intake(r1("request-p407-block-a"))
            executor.intake(r1("request-p407-block-b").copy(inputRef = "batch-p407-block-b"))
            val candidateFingerprint = driver.executeQuery(null, "SELECT comparison_fingerprint FROM import_duplicate_candidate WHERE candidate_id='duplicate-p407-block'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 0).value
            val review = ReviewImportDuplicateCandidate(executor.store).execute(
                com.unifiedledger.application.ImportDuplicateReviewRequest(
                    ImportRequestIdentity(ledgerId, ImportRequestId("review-p407-block")),
                    com.unifiedledger.application.ImportDuplicateCandidateId("duplicate-p407-block"), candidateFingerprint!!,
                    com.unifiedledger.application.ImportDuplicateStatus.CONFIRMED_DUPLICATE, "exact", "2026-08-19T12:00:00+08:00", "reviewer-p407", "2026-08-19T12:00:00+08:00",
                    com.unifiedledger.application.ImportDuplicateReviewId("review-p407-block"), ImportStatusHistoryId("review-history-p407-block"),
                ),
            )
            assertIs<com.unifiedledger.application.ImportDuplicateReviewResult.Accepted>(review)
            val before = formalCounts(database)
            val candidateHistoryBefore = database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne()
            val blocked = executor.confirm(confirmRequest("confirm-p407-block", "candidate-p407-block-b", hashR1))
            assertIs<ImportCandidateDecisionResult.Rejected>(blocked)
            assertEquals("SPINE_DUPLICATE_NOT_CONFIRMABLE", (blocked as ImportCandidateDecisionResult.Rejected).diagnostic.code)
            assertEquals(before, formalCounts(database))
            assertEquals(0L, database.ledgerQueries.countImportConfirmations().executeAsOne())
            assertEquals(candidateHistoryBefore, database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne())
        } finally { driver.close() }
    }

    @Test
    fun p407NoFundsCreatesOnlyIncompleteSourceCandidateAndClosedDuplicate() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val ids = BatchIntakeIdSource(listOf(
                intakeIds("p407-no-funds", "status-p407-no-funds").copy(
                    duplicateIds = listOf(com.unifiedledger.application.ImportDuplicateIntakeIds(
                        com.unifiedledger.application.ImportDuplicateCandidateId("duplicate-p407-no-funds"),
                        ImportStatusHistoryId("duplicate-status-p407-no-funds"),
                    )),
                ),
                intakeIds("p407-settled-after-no-funds", "status-p407-settled-after-no-funds"),
            ))
            val request = r1("request-p407-no-funds").copy(
                facts = r1().facts.copy(fundingState = com.unifiedledger.application.ImportFundingState.NO_FUNDS, fundingRuleId = "source-contract-no-funds-v1"),
            )
            val result = ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), ids, ImportContentFingerprint()).execute(request)
            assertIs<ImportIntakeResult.Accepted>(result)
            assertEquals(1L, database.ledgerQueries.countImportSourceRecords().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportEvidence().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportCandidates().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne())
            assertEquals(1L, driver.executeQuery(null, "SELECT count(*) FROM import_candidate_status_history WHERE status='incomplete'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals(1L, driver.executeQuery(null, "SELECT count(*) FROM import_duplicate_candidate WHERE kind='CLOSED_OR_FAILED_NO_FUNDS'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), ids, ImportContentFingerprint()).execute(
                    r1("request-p407-settled-after-no-funds").copy(inputRef = "batch-p407-settled-after-no-funds"),
                ),
            )
            assertEquals(1L, driver.executeQuery(null, "SELECT count(*) FROM import_duplicate_candidate", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
        } finally { driver.close() }
    }

    @Test
    fun p407ConfirmedDuplicateReviewReplaysWithoutDuplicateRows() {
        // Review behavior is exercised through the same persisted spine used by intake.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val ids = BatchIntakeIdSource(listOf(
                intakeIds("p407-r-a", "status-p407-r-a"),
                intakeIds("p407-r-b", "status-p407-r-b").copy(
                    duplicateIds = listOf(ImportDuplicateIntakeIds(ImportDuplicateCandidateId("duplicate-p407-r"), ImportStatusHistoryId("duplicate-status-p407-r"))),
                ),
            ))
            val store = SqlDelightImportSpineStore(database, driver)
            val intake = ExecuteImportIntake(store, ids, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(r1("request-p407-r-a")))
            assertIs<ImportIntakeResult.Accepted>(intake.execute(r1("request-p407-r-b").copy(inputRef = "batch-p407-r-b")))
            val review = ImportDuplicateReviewRequest(
                ImportRequestIdentity(ledgerId, ImportRequestId("review-p407-r")), ImportDuplicateCandidateId("duplicate-p407-r"),
                driver.executeQuery(null, "SELECT comparison_fingerprint FROM import_duplicate_candidate WHERE candidate_id='duplicate-p407-r'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 0).value,
                ImportDuplicateStatus.CONFIRMED_DUPLICATE, "duplicate", "2026-08-19T10:00:00+08:00", "reviewer", "2026-08-19T10:01:00+08:00",
                ImportDuplicateReviewId("review-p407-r"), ImportStatusHistoryId("review-history-p407-r"),
            )
            val reviewed = ReviewImportDuplicateCandidate(store).execute(review)
            assertIs<ImportDuplicateReviewResult.Accepted>(reviewed)
            assertIs<ImportDuplicateReviewResult.NoChange>(ReviewImportDuplicateCandidate(store).execute(review))
            val conflict = assertIs<ImportDuplicateReviewResult.Rejected>(
                ReviewImportDuplicateCandidate(store).execute(review.copy(reasonToken = "different-decision")),
            )
            assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", conflict.diagnostic.code)
            assertEquals(1L, driver.executeQuery(null, "SELECT count(*) FROM import_duplicate_review_receipt", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
        } finally { driver.close() }
    }

    private fun confirmOn(
        url: String,
        catalog: LedgerCatalog,
        commitIds: ImportIdSource,
        request: ImportCandidateConfirmRequest,
    ): ImportCandidateDecisionResult = JdbcSqliteDriver(url).use { driver ->
        val database = LedgerDatabase(driver)
        ConfirmImportCandidate(
            SqlDelightImportSpineStore(database, driver),
            commitIds,
            OrdinaryFlowFormalFactory(catalog, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).categoryId, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).fundingAccountId),
        ).execute(request)
    }

    private fun concurrentExecute(url: String, operations: List<() -> Any>): List<Any> {
        val pool = Executors.newFixedThreadPool(operations.size)
        val ready = CountDownLatch(operations.size)
        val start = CountDownLatch(1)
        return try {
            val futures = operations.map { operation ->
                pool.submit<Any> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    operation()
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
