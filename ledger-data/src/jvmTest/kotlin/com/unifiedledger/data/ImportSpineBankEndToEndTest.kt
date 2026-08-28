package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.BankStatementTransferFlowFormalFactory
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCandidateRejectRequest
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ImportStatusIdSource
import com.unifiedledger.application.OrdinaryFlowFormalFactory
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceProjection
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408ProjectionState
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.RejectImportCandidate
import com.unifiedledger.application.TransferFlowFormalFactory
import com.unifiedledger.application.import.ccb.CcbBillParser
import com.unifiedledger.application.import.ccb.CcbRowResult
import com.unifiedledger.application.import.cmb.CmbBillParser
import com.unifiedledger.application.import.cmb.CmbRowResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * BP-01 bank import spine end-to-end oracle (frozen spec
 * docs/specs/2026-08-28-bank-import-cmb-ccb-design.md, sections 5.2/5.3/5.4):
 * E-01..E-10 spine representative paths over the CMB/CCB parser output, E-11/E-12
 * RL-07 bank-side mirror representative path over the existing P4-08 confirmLink
 * chain, and the B-02/B-03 balance-mirror anchor replay with the B-04 privacy sweep.
 *
 * R-01/R-02 regression: this file changes no spine, matcher, projection or domain
 * behavior; the untouched P4-02..P4-08 suites in this module stay the frozen oracle.
 * The wallet-perspective direction gate is additionally re-proven on the E-11
 * wechat-side confirm (out -> wallet is the FROM leg).
 */
class ImportSpineBankEndToEndTest {
    private val ledgerId = LedgerId("ledger-bp01")
    private val mirrorLedgerId = LedgerId("ledger-bp01-mirror")
    private val ccbLedgerId = LedgerId("ledger-bp01-ccb")
    private val cny = CurrencyUnit("CNY", 2)
    private val bankAccountId = AccountId("account-asset-bank")
    private val walletAccountId = AccountId("account-asset-wallet")
    private val fingerprint = ImportContentFingerprint()

    // ---- Fixture loading (anonymous synthetic fixtures under tests/fixtures) ----

    private fun repositoryRoot(): java.nio.file.Path {
        var candidate =
            java.nio.file.Path
                .of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private fun fixtureBytes(name: String): ByteArray = Files.readAllBytes(repositoryRoot().resolve("tests/fixtures").resolve(name))

    private fun cmbAcceptedRows(): List<CmbRowResult.Accepted> {
        val result = CmbBillParser.parse("batch-bp01-cmb-a", fixtureBytes("batch-bp01-cmb-a.csv"))
        return result.rows.filterIsInstance<CmbRowResult.Accepted>()
    }

    private fun hBatchRow(ordinal: Int): CmbRowResult.Accepted {
        val rows =
            CmbBillParser
                .parse("batch-bp01-cmb-h", fixtureBytes("batch-bp01-cmb-h.csv"))
                .rows
        return rows.filterIsInstance<CmbRowResult.Accepted>().first { it.recordOrdinal == ordinal }
    }

    private fun ccbAcceptedRow(ordinal: Int): CcbRowResult.Accepted {
        val rows =
            CcbBillParser
                .parse("batch-bp01-ccb-a", fixtureBytes("batch-bp01-ccb-a.xls"))
                .rows
        return rows.filterIsInstance<CcbRowResult.Accepted>().first { it.recordOrdinal == ordinal }
    }

    // ---- Assembly helpers (P4-02/P4-03/P4-04 test pattern) ----

    private fun catalog(ledger: LedgerId): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(bankAccountId, ledger, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(walletAccountId, ledger, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(AccountId("expense-account-food"), ledger, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(AccountId("income-account-salary"), ledger, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(CategoryId("category-primary-food"), ledger, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                            Category(CategoryId("category-food"), ledger, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                            Category(CategoryId("category-primary-salary"), ledger, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                            Category(CategoryId("category-salary"), ledger, parentId = CategoryId("category-primary-salary"), postingAccountId = AccountId("income-account-salary"), active = true, kind = CategoryKind.INCOME),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("bank e2e catalog failure: ${result.violation}")
        }

    private fun intakeIds(
        prefix: String,
        statusId: String,
    ) = ImportIntakeIds(
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
        formalIds =
            ImportFormalIds(
                transactionId = TransactionId(tx),
                versionId = TransactionVersionId(version),
                postingSetId = PostingSetId(postingSet),
                postingIds = postingIds.map(::PostingId),
            ),
    )

    private class BatchIntakeIdSource(
        private val batches: List<ImportIntakeIds>,
    ) : ImportIntakeIdSource {
        val calls = AtomicInteger(0)

        override fun next(): ImportIntakeIds {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "intake id batch exhausted" }
            return batches[index]
        }
    }

    private class BatchCommitIdSource(
        private val batches: List<ImportCommitIds>,
    ) : ImportIdSource {
        val calls = AtomicInteger(0)

        override fun next(): ImportCommitIds {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "commit id batch exhausted" }
            return batches[index]
        }
    }

    private class BatchStatusIdSource(
        private val batches: List<ImportStatusHistoryId>,
    ) : ImportStatusIdSource {
        val calls = AtomicInteger(0)

        override fun next(): ImportStatusHistoryId {
            val index = calls.getAndIncrement()
            require(index < batches.size) { "status id batch exhausted" }
            return batches[index]
        }
    }

    private class Executor(
        val database: LedgerDatabase,
        val driver: JdbcSqliteDriver,
        val catalog: LedgerCatalog,
        val intakeIds: ImportIntakeIdSource,
        val commitIds: ImportIdSource,
        val statusIds: ImportStatusIdSource,
        val factory: com.unifiedledger.application.ImportCandidateFormalFactory,
    ) {
        val store = SqlDelightImportSpineStore(database, driver)

        fun intake(request: ImportIntakeRequest): ImportIntakeResult = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirm(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult = ConfirmImportCandidate(store, commitIds, factory, catalog).execute(request)

        fun reject(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult = RejectImportCandidate(store, statusIds).execute(request)
    }

    private fun ordinaryExecutor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        cat: LedgerCatalog,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
        statusIds: ImportStatusIdSource,
    ) = Executor(database, driver, cat, intakeIds, commitIds, statusIds, OrdinaryFlowFormalFactory(cat))

    private fun bankTransferExecutor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        cat: LedgerCatalog,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
        statusIds: ImportStatusIdSource,
    ) = Executor(database, driver, cat, intakeIds, commitIds, statusIds, BankStatementTransferFlowFormalFactory(cat, bankAccountId))

    private fun walletTransferExecutor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        cat: LedgerCatalog,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
        statusIds: ImportStatusIdSource,
    ) = Executor(database, driver, cat, intakeIds, commitIds, statusIds, TransferFlowFormalFactory(cat, walletAccountId))

    private fun intakeRequest(
        ledger: LedgerId,
        requestId: String,
        inputRef: String,
        ordinal: Int,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        completeness: ImportCompleteness,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledger, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = ordinal,
        recordKind = kind,
        facts = facts,
        completeness = completeness,
        candidateGeneratedAt = "legacy-intake-v1",
    )

    private fun ordinaryConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        category: String,
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = "2026-08-28T12:00:00+08:00",
        decisionFields =
            ImportConfirmDecisionFields.OrdinaryFlow(
                categoryId = CategoryId(category),
                fundingAccountId = bankAccountId,
            ),
    )

    private fun bankTransferConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        from: AccountId,
        to: AccountId,
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = "2026-08-28T12:00:00+08:00",
        decisionFields = ImportConfirmDecisionFields.TransferFlow(fromAccountId = from, toAccountId = to),
    )

    private fun spineCounts(database: LedgerDatabase) =
        listOf(
            database.ledgerQueries.countImportRequests().executeAsOne(),
            database.ledgerQueries.countImportSourceRecords().executeAsOne(),
            database.ledgerQueries.countImportEvidence().executeAsOne(),
            database.ledgerQueries.countImportCandidates().executeAsOne(),
            database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne(),
            database.ledgerQueries.countImportDecisionSnapshots().executeAsOne(),
            database.ledgerQueries.countImportConfirmations().executeAsOne(),
            database.ledgerQueries.countImportReceipts().executeAsOne(),
        )

    private fun formalCounts(database: LedgerDatabase) =
        listOf(
            database.ledgerQueries.countTransactions().executeAsOne(),
            database.ledgerQueries.countVersions().executeAsOne(),
            database.ledgerQueries.countPostings().executeAsOne(),
        )

    private fun candidateStatus(
        database: LedgerDatabase,
        ledger: LedgerId,
        candidate: String,
    ): String =
        database.ledgerQueries
            .selectImportCandidateCurrentStatus(ledger.value, candidate)
            .executeAsOne()
            .status

    private fun postingsOf(
        database: LedgerDatabase,
        ledger: LedgerId,
        postingSet: String,
    ): List<List<Any>> =
        database.ledgerQueries
            .selectRg12FormalPostings(ledger.value, postingSet)
            .executeAsList()
            .map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) }

    private fun scalarText(
        driver: JdbcSqliteDriver,
        sql: String,
    ): String =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getString(0)!!)
                },
                0,
            ).value

    private fun selectRows(
        driver: JdbcSqliteDriver,
        sql: String,
        longColumns: List<Boolean>,
    ): List<List<Any?>> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val rows = mutableListOf<List<Any?>>()
                    while (cursor.next().value) {
                        rows +=
                            longColumns.mapIndexed { index, isLong ->
                                if (isLong) cursor.getLong(index) else cursor.getString(index)
                            }
                    }
                    app.cash.sqldelight.db.QueryResult
                        .Value(rows.toList())
                },
                0,
            ).value

    // ---- E-01..E-08: intake, replay, confirm, bank-side direction gate, reject, gates ----

    @Test
    fun executesE01ToE08SpineRepresentativePathsWithBankDirectionGateVariant() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val cmb = cmbAcceptedRows()
            val r1 = cmb.first { it.recordOrdinal == 0 } // 网联协议支付 out 128.50
            val r2 = cmb.first { it.recordOrdinal == 1 } // 银联快捷支付 out 12.50
            val r4 = cmb.first { it.recordOrdinal == 3 } // 账户结息 in 3.00
            val r5 = cmb.first { it.recordOrdinal == 4 } // 数字人民币充值 out 100.00 (transfer)
            val r6 = cmb.first { it.recordOrdinal == 5 } // 数字人民币存银行 in 200.00 (transfer)
            val r7 = cmb.first { it.recordOrdinal == 6 } // 朝朝宝购买 out 500.00 (transfer)
            val r8 = cmb.first { it.recordOrdinal == 7 } // 朝朝宝赎回 in 510.30 (transfer)
            val h1 = hBatchRow(0) // 网联付款交易 in 30.00 (E-07b)
            val h2 = hBatchRow(1) // 数字人民币存银行 in 50.00 (E-07c)

            val intakeIds =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("a", "status-a-1"),
                        intakeIds("b", "status-b-1"),
                        intakeIds("c", "status-c-1"),
                        intakeIds("d", "status-d-1"),
                        intakeIds("c2", "status-c2-1"),
                        intakeIds("c3", "status-c3-1"),
                        intakeIds("e", "status-e-1"),
                        intakeIds("f", "status-f-1"),
                        intakeIds("g", "status-g-1"),
                    ),
                )
            val executor = ordinaryExecutor(database, driver, cat, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))

            // E-01: intake R1 @ req-a-intake -> accepted (C1 pending_confirmation).
            val e01 =
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest(ledgerId, "req-a-intake", "batch-bp01-cmb-a", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, r1.facts, r1.completeness)),
                )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.SOURCE, "source-a"),
                    ImportReturnedId(ImportReturnedIdKind.EVIDENCE, "evidence-a"),
                    ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-a"),
                ),
                e01.returnedIds,
            )
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))

            // E-02: same-request equivalent replay -> no_change, delta all zero.
            val e02 =
                assertIs<ImportIntakeResult.NoChange>(
                    executor.intake(intakeRequest(ledgerId, "req-a-intake", "batch-bp01-cmb-a", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, r1.facts, r1.completeness)),
                )
            assertEquals(e01.receipt, e02.receipt)
            assertEquals("equivalent_replay", e02.reasonCode)
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // E-03: confirm C1 OrdinaryFlow(category-food, account-asset-bank) -> expense
            // transaction with the frozen two-leg shape (expense +128.50, bank -128.50).
            val commitIdsA =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-bank-a"))),
                )
            val e03 =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    ordinaryExecutor(database, driver, cat, intakeIds, commitIdsA, BatchStatusIdSource(emptyList()))
                        .confirm(ordinaryConfirmRequest("req-a-confirm", "candidate-a", fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1.facts), "category-food")),
                )
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            assertEquals(
                listOf(
                    listOf("posting-expense-a", "expense-account-food", 12850L, "CNY", 2L),
                    listOf("posting-bank-a", bankAccountId.value, -12850L, "CNY", 2L),
                ),
                postingsOf(database, ledgerId, "posting-set-a"),
            )

            // E-04: setup intake R2, R5-R8 (main batch) and the two h-batch rows
            // (网联付款交易 30.00, 数字人民币存银行 50.00) -> seven pending candidates:
            // one ordinary expense, four transfers, one ordinary income, one transfer in.
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-b-intake", "batch-bp01-cmb-a", 1, ImportRecordKind.ORDINARY_FLOW_SOURCE, r2.facts, r2.completeness)),
            )
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-c-intake", "batch-bp01-cmb-a", 4, ImportRecordKind.TRANSFER_FLOW_SOURCE, r5.facts, r5.completeness)),
            )
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-d-intake", "batch-bp01-cmb-a", 5, ImportRecordKind.TRANSFER_FLOW_SOURCE, r6.facts, r6.completeness)),
            )
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-c2-intake", "batch-bp01-cmb-a", 6, ImportRecordKind.TRANSFER_FLOW_SOURCE, r7.facts, r7.completeness)),
            )
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-c3-intake", "batch-bp01-cmb-a", 7, ImportRecordKind.TRANSFER_FLOW_SOURCE, r8.facts, r8.completeness)),
            )
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-e-intake", "batch-bp01-cmb-h", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, h1.facts, h1.completeness)),
            )
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-f-intake", "batch-bp01-cmb-h", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, h2.facts, h2.completeness)),
            )
            assertEquals(listOf(9L, 8L, 8L, 8L, 9L, 1L, 1L, 9L), spineCounts(database))
            assertEquals("pending_confirmation", candidateStatus(database, ledgerId, "candidate-c"))
            val sourceC =
                database.ledgerQueries
                    .selectImportSourceByOwnerRequest(ledgerId.value, "req-c-intake")
                    .executeAsOne()
            assertEquals("transfer_flow_source", sourceC.record_kind)
            assertEquals(2L, sourceC.contract_version)

            // E-05: confirm R5 candidate with the BANK-side direction-gate variant.
            // The reversed-leg attempt (wallet=from on an out row) fails the gate with
            // zero residue; the corrected confirm (bank=from) creates the balanced
            // internal principal transfer (bank -100.00, wallet +100.00).
            val attemptAndCorrected =
                BatchCommitIdSource(
                    listOf(
                        commitIds("confirmation-b-attempt-1", "status-c-2-attempt-1", "tx-b-attempt-1", "version-b-attempt-1-v1", "posting-set-b-attempt-1", listOf("posting-bank-b-attempt-1", "posting-wallet-b-attempt-1")),
                        commitIds("confirmation-b", "status-c-2", "tx-b", "version-b-v1", "posting-set-b", listOf("posting-bank-b", "posting-wallet-b")),
                    ),
                )
            val bankExecutor = bankTransferExecutor(database, driver, cat, intakeIds, attemptAndCorrected, BatchStatusIdSource(emptyList()))
            val e05Attempt =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    bankExecutor.confirm(bankTransferConfirmRequest("req-b-confirm", "candidate-c", fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, r5.facts), from = walletAccountId, to = bankAccountId)),
                )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e05Attempt.diagnostic.code)
            assertEquals(1, attemptAndCorrected.calls.get())
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            assertEquals("pending_confirmation", candidateStatus(database, ledgerId, "candidate-c"))
            val e05 =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    bankExecutor.confirm(bankTransferConfirmRequest("req-b-confirm", "candidate-c", fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, r5.facts), from = bankAccountId, to = walletAccountId)),
                )
            assertEquals(2, attemptAndCorrected.calls.get())
            assertEquals("confirmed", candidateStatus(database, ledgerId, "candidate-c"))
            assertEquals(listOf(10L, 8L, 8L, 8L, 10L, 2L, 2L, 10L), spineCounts(database))
            assertEquals(listOf(2L, 2L, 4L), formalCounts(database))
            assertEquals(
                listOf(
                    listOf("posting-bank-b", bankAccountId.value, -10000L, "CNY", 2L),
                    listOf("posting-wallet-b", walletAccountId.value, 10000L, "CNY", 2L),
                ),
                postingsOf(database, ledgerId, "posting-set-b"),
            )

            // E-06: reject C2 (R2 candidate) @ req-b2-reject -> manual terminal, no formal.
            val statusIds = BatchStatusIdSource(listOf(ImportStatusHistoryId("status-b-2")))
            val e06 =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    Executor(database, driver, cat, intakeIds, attemptAndCorrected, statusIds, OrdinaryFlowFormalFactory(cat))
                        .reject(
                            ImportCandidateRejectRequest(
                                identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-b2-reject")),
                                candidateId = ImportCandidateId("candidate-b"),
                                expectedContentHash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r2.facts),
                            ),
                        ),
                )
            assertEquals("rejected", candidateStatus(database, ledgerId, "candidate-b"))
            assertEquals(listOf(11L, 8L, 8L, 8L, 11L, 3L, 2L, 11L), spineCounts(database))
            assertEquals(listOf(2L, 2L, 4L), formalCounts(database))

            // E-07: intake R4 then confirm OrdinaryFlow(category-salary, bank) -> interest
            // income (bank +3.00, income account -3.00).
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-g-intake", "batch-bp01-cmb-a", 3, ImportRecordKind.ORDINARY_FLOW_SOURCE, r4.facts, r4.completeness)),
            )
            val commitIdsC =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-c", "status-g-2", "tx-c", "version-c-v1", "posting-set-c", listOf("posting-bank-c", "posting-salary-c"))),
                )
            val e07 =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    ordinaryExecutor(database, driver, cat, intakeIds, commitIdsC, statusIds)
                        .confirm(ordinaryConfirmRequest("req-c-confirm", "candidate-g", fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r4.facts), "category-salary")),
                )
            assertEquals(
                listOf(
                    listOf("posting-bank-c", bankAccountId.value, 300L, "CNY", 2L),
                    listOf("posting-salary-c", "income-account-salary", -300L, "CNY", 2L),
                ),
                postingsOf(database, ledgerId, "posting-set-c"),
            )
            assertEquals(listOf(13L, 9L, 9L, 9L, 13L, 4L, 3L, 13L), spineCounts(database))
            assertEquals(listOf(3L, 3L, 6L), formalCounts(database))

            // E-07b: confirm the h-batch 网联付款交易 candidate -> ordinary income 30.00
            // (bank +30.00, income account -30.00); the self-wallet channel remark
            // marker never reaches any persisted column.
            val commitIdsD =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-d", "status-e-2", "tx-d", "version-d-v1", "posting-set-d", listOf("posting-bank-d", "posting-salary-d"))),
                )
            val e07b =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    ordinaryExecutor(database, driver, cat, intakeIds, commitIdsD, statusIds)
                        .confirm(ordinaryConfirmRequest("req-c2-confirm", "candidate-e", fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, h1.facts), "category-salary")),
                )
            assertEquals(
                listOf(
                    listOf("posting-bank-d", bankAccountId.value, 3000L, "CNY", 2L),
                    listOf("posting-salary-d", "income-account-salary", -3000L, "CNY", 2L),
                ),
                postingsOf(database, ledgerId, "posting-set-d"),
            )
            assertEquals(listOf(14L, 9L, 9L, 9L, 14L, 5L, 4L, 14L), spineCounts(database))
            assertEquals(listOf(4L, 4L, 8L), formalCounts(database))

            // E-07c: confirm the h-batch 数字人民币存银行 candidate with the bank-side
            // direction-gate variant on an IN row (wallet=from, bank=to) -> balanced
            // principal transfer (wallet -50.00, bank +50.00).
            val commitIdsE =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-e", "status-f-2", "tx-e", "version-e-v1", "posting-set-e", listOf("posting-wallet-e", "posting-bank-e"))),
                )
            val e07c =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    bankTransferExecutor(database, driver, cat, intakeIds, commitIdsE, statusIds)
                        .confirm(bankTransferConfirmRequest("req-c3-confirm", "candidate-f", fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, h2.facts), from = walletAccountId, to = bankAccountId)),
                )
            assertEquals(
                listOf(
                    listOf("posting-wallet-e", walletAccountId.value, -5000L, "CNY", 2L),
                    listOf("posting-bank-e", bankAccountId.value, 5000L, "CNY", 2L),
                ),
                postingsOf(database, ledgerId, "posting-set-e"),
            )
            assertEquals(listOf(15L, 9L, 9L, 9L, 15L, 6L, 5L, 15L), spineCounts(database))
            assertEquals(listOf(5L, 5L, 10L), formalCounts(database))

            // E-08a: same request id with different content -> SPINE_REQUEST_IDENTITY_CONFLICT, zero writes.
            val e08a =
                assertIs<ImportIntakeResult.Rejected>(
                    executor.intake(
                        intakeRequest(ledgerId, "req-a-intake", "batch-bp01-cmb-a", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, r1.facts.copy(amountMinor = 12851), r1.completeness),
                    ),
                )
            assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", e08a.diagnostic.code)

            // E-08b: confirm R6's candidate with a stale expectedContentHash ->
            // SPINE_STALE_FINGERPRINT, zero writes, zero ID consumption.
            val staleIds =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-stale", "status-d-2", "tx-stale", "version-stale-v1", "posting-set-stale", listOf("posting-stale-0", "posting-stale-1"))),
                )
            val e08b =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    bankTransferExecutor(database, driver, cat, intakeIds, staleIds, statusIds)
                        .confirm(bankTransferConfirmRequest("req-d-confirm-stale", "candidate-d", "stale-hash", from = walletAccountId, to = bankAccountId)),
                )
            assertEquals("SPINE_STALE_FINGERPRINT", e08b.diagnostic.code)
            assertEquals(0, staleIds.calls.get())

            // E-08c: repeating the confirmation of the already-confirmed C1 ->
            // SPINE_CANDIDATE_NOT_PENDING, zero writes.
            val e08c =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    ordinaryExecutor(database, driver, cat, intakeIds, commitIdsA, statusIds)
                        .confirm(ordinaryConfirmRequest("req-a-confirm-2", "candidate-a", fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1.facts), "category-food")),
                )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", e08c.diagnostic.code)

            assertEquals(listOf(15L, 9L, 9L, 9L, 15L, 6L, 5L, 15L), spineCounts(database))
            assertEquals(listOf(5L, 5L, 10L), formalCounts(database))
        } finally {
            driver.close()
        }
    }

    // ---- E-09: failure injection -> full rollback -> corrected retry accepts ----

    @Test
    fun executesE09InjectedFailuresRollBackAndRetriesAccept() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val cmb = cmbAcceptedRows()
            val r1 = cmb.first { it.recordOrdinal == 0 }
            val r6 = cmb.first { it.recordOrdinal == 5 }

            // Intake failure after the candidate insert -> whole transaction rolls back.
            val failingStore =
                SqlDelightImportSpineStore(
                    database,
                    driver,
                    ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE) error("injected") },
                )
            val attempt1 = BatchIntakeIdSource(listOf(intakeIds("a-attempt-1", "status-a-1-attempt-1")))
            assertFailsWith<IllegalStateException> {
                ExecuteImportIntake(failingStore, attempt1, ImportContentFingerprint()).execute(
                    intakeRequest(ledgerId, "req-a-intake", "batch-bp01-cmb-a", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, r1.facts, r1.completeness),
                )
            }
            assertEquals(1, attempt1.calls.get())
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), spineCounts(database))
            val retryIds = BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), retryIds, ImportContentFingerprint()).execute(
                    intakeRequest(ledgerId, "req-a-intake", "batch-bp01-cmb-a", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, r1.facts, r1.completeness),
                ),
            )
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // Setup the R6 transfer candidate for the confirm injection.
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), BatchIntakeIdSource(listOf(intakeIds("d", "status-d-1"))), ImportContentFingerprint()).execute(
                    intakeRequest(ledgerId, "req-d-intake", "batch-bp01-cmb-a", 5, ImportRecordKind.TRANSFER_FLOW_SOURCE, r6.facts, r6.completeness),
                ),
            )

            // Confirm failure after the formal persist -> whole transaction rolls back.
            val failingConfirmStore =
                SqlDelightImportSpineStore(
                    database,
                    driver,
                    ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL) error("injected") },
                )
            val confirmAttempt =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-d-attempt-1", "status-d-2-attempt-1", "tx-d-attempt-1", "version-d-attempt-1-v1", "posting-set-d-attempt-1", listOf("posting-wallet-d-attempt-1", "posting-bank-d-attempt-1"))),
                )
            assertFailsWith<IllegalStateException> {
                ConfirmImportCandidate(
                    failingConfirmStore,
                    confirmAttempt,
                    BankStatementTransferFlowFormalFactory(cat, bankAccountId),
                    cat,
                ).execute(bankTransferConfirmRequest("req-d-confirm", "candidate-d", fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, r6.facts), from = walletAccountId, to = bankAccountId))
            }
            assertEquals(1, confirmAttempt.calls.get())
            assertEquals(listOf(2L, 2L, 2L, 2L, 2L, 0L, 0L, 2L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            assertEquals("pending_confirmation", candidateStatus(database, ledgerId, "candidate-d"))
            val confirmRetry =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-d", "status-d-2", "tx-d", "version-d-v1", "posting-set-d", listOf("posting-wallet-d", "posting-bank-d"))),
                )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver),
                    confirmRetry,
                    BankStatementTransferFlowFormalFactory(cat, bankAccountId),
                    cat,
                ).execute(bankTransferConfirmRequest("req-d-confirm", "candidate-d", fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, r6.facts), from = walletAccountId, to = bankAccountId)),
            )
            assertEquals(listOf(3L, 2L, 2L, 2L, 3L, 1L, 1L, 3L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
        } finally {
            driver.close()
        }
    }

    // ---- E-10: concurrency (same-request intake, same-candidate confirm) ----

    @Test
    fun executesE10ConcurrentIntakeAndConfirmWithSingleWinner() {
        val path = Files.createTempFile("bank-e10-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            val cat = catalog(ledgerId)
            val r1 = cmbAcceptedRows().first { it.recordOrdinal == 0 }
            val r5 = cmbAcceptedRows().first { it.recordOrdinal == 4 }
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val executor = bankTransferExecutor(database, driver, cat, BatchIntakeIdSource(listOf(intakeIds("c", "status-c-1"))), BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest(ledgerId, "req-c-intake", "batch-bp01-cmb-a", 4, ImportRecordKind.TRANSFER_FLOW_SOURCE, r5.facts, r5.completeness)),
                )
            }

            // Same request, same content, two threads -> 1 accepted + 1 no_change.
            val intakeResults =
                concurrentExecute(
                    url,
                    listOf(
                        { intakeOn(url, r1) },
                        { intakeOn(url, r1) },
                    ),
                )
            assertEquals(1, intakeResults.count { it is ImportIntakeResult.Accepted })
            assertEquals(1, intakeResults.count { it is ImportIntakeResult.NoChange })
            JdbcSqliteDriver(url).use { driver ->
                assertEquals(listOf(2L, 2L, 2L, 2L, 2L, 0L, 0L, 2L), spineCounts(LedgerDatabase(driver)))
            }

            // Same candidate, two distinct confirm requests -> single winner, the loser
            // is SPINE_CANDIDATE_NOT_PENDING, the shared ID source is consumed once.
            val sharedIds =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-c", "status-c-2", "tx-c", "version-c-v1", "posting-set-c", listOf("posting-bank-c", "posting-wallet-c"))),
                )
            val hashR5 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, r5.facts)
            val confirmResults =
                concurrentExecute(
                    url,
                    listOf(
                        { confirmOn(url, cat, sharedIds, bankTransferConfirmRequest("req-c-confirm-a", "candidate-c", hashR5, from = bankAccountId, to = walletAccountId)) },
                        { confirmOn(url, cat, sharedIds, bankTransferConfirmRequest("req-c-confirm-b", "candidate-c", hashR5, from = bankAccountId, to = walletAccountId)) },
                    ),
                )
            assertEquals(1, confirmResults.count { it is ImportCandidateDecisionResult.Accepted })
            val loser = confirmResults.filterIsInstance<ImportCandidateDecisionResult.Rejected>().single()
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", loser.diagnostic.code)
            assertEquals(1, sharedIds.calls.get())
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(listOf(3L, 2L, 2L, 2L, 3L, 1L, 1L, 3L), spineCounts(database))
                assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
                assertEquals(
                    listOf(
                        listOf("posting-bank-c", bankAccountId.value, -10000L, "CNY", 2L),
                        listOf("posting-wallet-c", walletAccountId.value, 10000L, "CNY", 2L),
                    ),
                    postingsOf(database, ledgerId, "posting-set-c"),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---- E-11/E-12: RL-07 bank-side mirror representative path ----
    //
    // Temporal-shape adjudication note (accepted dual-shape gate decision): the frozen
    // bank parser facts retain the source contract's "+08:00" form, while existing P4-08
    // confirmLink requires the raw occurred_at shape to match the formal posting (UTC "Z").
    // Therefore E-11 has two explicit stages:
    //  - the unadapted parser fact remains "+08:00" and is rejected by the existing gate
    //    (P408_POSTING_TIME_UNRESOLVED), with zero writes;
    //  - before mirror confirmation, the same instant is normalized to UTC "Z" form, then
    //    confirmLink completes the chain: one formal transfer, evidence link, bank posting
    //    CHECKED, D-112 READY projection, and zero second transaction.
    // This dual-shape behavior is accepted without changing P4-08 implementation/semantics.
    @Test
    fun executesE11E12BankMirrorPathSingleTransactionWithCheckedBankPosting() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(mirrorLedgerId)
            // Wallet side (P4-04 wallet perspective, R-01): 零钱提现 out 10.00
            // @ 2026-08-24T09:00:00+08:00 -> wallet is the FROM leg, bank the TO leg.
            val wechatFacts =
                ImportSourceFacts(1000, "CNY", 2, "2026-08-24T09:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
            val hashWechat = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, wechatFacts)
            val walletExecutor =
                walletTransferExecutor(
                    database,
                    driver,
                    cat,
                    BatchIntakeIdSource(listOf(intakeIds("wechat", "status-wechat-1"))),
                    BatchCommitIdSource(
                        listOf(commitIds("confirmation-wechat", "status-wechat-2", "tx-wechat", "version-wechat-v1", "posting-set-wechat", listOf("posting-wechat-0", "posting-wechat-1"))),
                    ),
                    BatchStatusIdSource(emptyList()),
                )
            assertIs<ImportIntakeResult.Accepted>(
                walletExecutor.intake(intakeRequest(mirrorLedgerId, "req-wechat-intake", "batch-p403-wechat", 6, ImportRecordKind.TRANSFER_FLOW_SOURCE, wechatFacts, ImportCompleteness.VALID_COMPLETE)),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                walletExecutor.confirm(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(mirrorLedgerId, ImportRequestId("req-wechat-confirm")),
                        candidateId = ImportCandidateId("candidate-wechat"),
                        expectedContentHash = hashWechat,
                        explicitConfirmedAt = "2026-08-28T12:00:00+08:00",
                        decisionFields = ImportConfirmDecisionFields.TransferFlow(fromAccountId = walletAccountId, toAccountId = bankAccountId),
                    ),
                ),
            )
            assertEquals(
                listOf(
                    listOf("posting-wechat-0", walletAccountId.value, -1000L, "CNY", 2L),
                    listOf("posting-wechat-1", bankAccountId.value, 1000L, "CNY", 2L),
                ),
                postingsOf(database, mirrorLedgerId, "posting-set-wechat"),
            )
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))

            // Bank side candidate 1: the literal CCB B3 shape (银联入账 +10.00
            // @ 2026-08-25T00:00:00+08:00, ordinary income per spec 8.1 recommendation A).
            val b3 = ccbAcceptedRow(2)
            assertIs<ImportIntakeResult.Accepted>(
                ordinaryExecutor(database, driver, cat, BatchIntakeIdSource(listOf(intakeIds("bank", "status-bank-1"))), BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
                    .intake(intakeRequest(mirrorLedgerId, "req-bank-intake", "batch-bp01-ccb-a", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, b3.facts, b3.completeness)),
            )
            assertEquals("pending_confirmation", candidateStatus(database, mirrorLedgerId, "candidate-bank"))

            // The literal-shape confirmLink: rejected by the existing P4-08 temporal-shape
            // gate (posting times are always Z), zero writes, candidate untouched.
            val p408Store = SqlDelightP408ReconciliationStore(database, driver)
            val literalShapeLink =
                confirmLinkRequest(
                    requestId = "req-bank-link",
                    evidenceId = "evidence-bank",
                    candidateId = "candidate-bank",
                    sourceOccurredAt = b3.facts.occurredAt,
                    naturalDayDistance = 1,
                )
            val literalRejected = assertIs<P408ReconciliationResult.Rejected>(p408Store.confirmLink(literalShapeLink))
            assertEquals("P408_POSTING_TIME_UNRESOLVED", literalRejected.code)
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            assertEquals(0L, scalarText(driver, "SELECT COUNT(*) FROM evidence_link WHERE ledger_id = '${mirrorLedgerId.value}'").toLong())

            // Bank side candidate 2: the same mirror row in the linking discipline's
            // temporal shape (same instant, Z form) -> the full E-11 chain.
            val bankZFacts =
                ImportSourceFacts(1000, "CNY", 2, "2026-08-24T16:00:00Z", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
            assertIs<ImportIntakeResult.Accepted>(
                ordinaryExecutor(database, driver, cat, BatchIntakeIdSource(listOf(intakeIds("bankz", "status-bankz-1"))), BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
                    .intake(intakeRequest(mirrorLedgerId, "req-bankz-intake", "batch-bp01-ccb-z", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, bankZFacts, ImportCompleteness.VALID_COMPLETE)),
            )
            val acceptedLink =
                assertIs<P408ReconciliationResult.Accepted>(
                    p408Store.confirmLink(
                        confirmLinkRequest(
                            requestId = "req-bankz-link",
                            evidenceId = "evidence-bankz",
                            candidateId = "candidate-bankz",
                            sourceOccurredAt = bankZFacts.occurredAt,
                            naturalDayDistance = 1,
                            linkId = "link-bankz",
                            reconciliationId = "reconciliation-bankz",
                        ),
                    ),
                )
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())

            // Evidence link established; the transfer's bank posting is CHECKED.
            assertEquals(
                "1",
                scalarText(
                    driver,
                    "SELECT COUNT(*) FROM evidence_link WHERE ledger_id = '${mirrorLedgerId.value}' AND link_id = 'link-bankz' " +
                        "AND evidence_id = 'evidence-bankz' AND posting_id = 'posting-wechat-1' AND transaction_id = 'tx-wechat'",
                ),
            )
            assertEquals(
                "CHECKED",
                scalarText(
                    driver,
                    "SELECT status FROM posting_reconciliation WHERE ledger_id = '${mirrorLedgerId.value}' AND posting_id = 'posting-wechat-1'",
                ),
            )

            // D-112: the confirmLink lazily materialized the READY projection in the same
            // transaction (explicit target binding = account-asset-bank).
            val projection = assertIs<P408EvidenceProjection>(SqlDelightEvidenceProjectionStore.createShared(database).readProjection(mirrorLedgerId.value, "evidence-bankz"))
            assertEquals(P408ProjectionState.READY, projection.state)
            assertEquals(bankAccountId.value, projection.targetAccountId)

            // Equivalent replay -> no_change, state value-for-value unchanged.
            val replay =
                assertIs<P408ReconciliationResult.NoChange>(
                    p408Store.confirmLink(
                        confirmLinkRequest(
                            requestId = "req-bankz-link",
                            evidenceId = "evidence-bankz",
                            candidateId = "candidate-bankz",
                            sourceOccurredAt = bankZFacts.occurredAt,
                            naturalDayDistance = 1,
                            linkId = "link-bankz-replay",
                            reconciliationId = "reconciliation-bankz-replay",
                        ),
                    ),
                )
            assertEquals(acceptedLink.receipt, replay.receipt)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())

            // E-12: the remaining bank-side mirror candidate (literal +08:00 shape) is
            // disposed by explicit user reject (spec section 6 item 4 implementation
            // decision); the formal chain still holds exactly one transaction, the bank
            // posting stays CHECKED and the evidence-link row set is unchanged.
            val e12 =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    Executor(database, driver, cat, BatchIntakeIdSource(emptyList()), BatchCommitIdSource(emptyList()), BatchStatusIdSource(listOf(ImportStatusHistoryId("status-bank-2"))), OrdinaryFlowFormalFactory(cat))
                        .reject(
                            ImportCandidateRejectRequest(
                                identity = ImportRequestIdentity(mirrorLedgerId, ImportRequestId("req-bank-reject")),
                                candidateId = ImportCandidateId("candidate-bank"),
                                expectedContentHash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, b3.facts),
                            ),
                        ),
                )
            assertEquals("rejected", candidateStatus(database, mirrorLedgerId, "candidate-bank"))
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(
                "0",
                scalarText(driver, "SELECT COUNT(*) FROM ledger_transaction WHERE ledger_id = '${mirrorLedgerId.value}' AND kind = 'INCOME'"),
            )
            assertEquals(
                "CHECKED",
                scalarText(
                    driver,
                    "SELECT status FROM posting_reconciliation WHERE ledger_id = '${mirrorLedgerId.value}' AND posting_id = 'posting-wechat-1'",
                ),
            )
            assertEquals(
                "1",
                scalarText(
                    driver,
                    "SELECT COUNT(*) FROM evidence_link WHERE ledger_id = '${mirrorLedgerId.value}' AND evidence_id = 'evidence-bankz'",
                ),
            )
        } finally {
            driver.close()
        }
    }

    private fun confirmLinkRequest(
        requestId: String,
        evidenceId: String,
        candidateId: String,
        sourceOccurredAt: String,
        naturalDayDistance: Int,
        linkId: String = "link-$requestId",
        reconciliationId: String = "reconciliation-$requestId",
    ) = P408ConfirmLinkRequest(
        ledgerId = mirrorLedgerId.value,
        requestId = requestId,
        evidenceId = evidenceId,
        candidateId = candidateId,
        postingId = "posting-wechat-1",
        transactionId = "tx-wechat",
        amountMinor = 1000L,
        currencyCode = "CNY",
        currencyPrecision = 2,
        direction = "in",
        accountId = bankAccountId.value,
        responsibility = P408EvidenceResponsibility.DESTINATION_ASSET_POSTING,
        basisVersion = 2,
        matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
        windowDays = P408Matcher.DEFAULT_WINDOW_DAYS,
        naturalDayDistance = naturalDayDistance,
        sourceOccurredAt = sourceOccurredAt,
        confirmedAt = "2026-08-28T12:00:00+08:00",
        linkId = linkId,
        reconciliationId = reconciliationId,
        createdAt = "2026-08-28T12:00:00+08:00",
        projectionId = "proj-$evidenceId",
        projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
        projectionRuleVersion = 1,
        normalizedAmountMinor = 1000L,
        rawAmountMinor = 1000L,
        rawCurrencyPrecision = 2,
    )

    // ---- B-02/B-03: balance-mirror end-point and point-in-time anchors ----

    @Test
    fun assertsBalanceMirrorAnchorsOverConfirmedSegments() {
        // CMB ledger: segment A = R1..R8 and segment B = R16 (R17 carries a zero delta).
        val driverCmb = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driverCmb)
            val database = LedgerDatabase(driverCmb)
            val cat = catalog(ledgerId)
            val cmb = cmbAcceptedRows()
            val ordinals = listOf(0, 1, 2, 3, 4, 5, 6, 7, 15)
            val intakeIds = BatchIntakeIdSource(ordinals.map { intakeIds("s$it", "status-s$it-1") })
            val executor = ordinaryExecutor(database, driverCmb, cat, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            val transferIntake = bankTransferExecutor(database, driverCmb, cat, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            val byOrdinal = cmb.associateBy { it.recordOrdinal }
            ordinals.forEach { ordinal ->
                val row = byOrdinal.getValue(ordinal)
                val kind = if (row.recordKind == ImportRecordKind.TRANSFER_FLOW_SOURCE) ImportRecordKind.TRANSFER_FLOW_SOURCE else ImportRecordKind.ORDINARY_FLOW_SOURCE
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest(ledgerId, "req-s$ordinal", "batch-bp01-cmb-a", ordinal, kind, row.facts, row.completeness)),
                )
            }

            // Confirm every segment row with the frozen decision mapping: expenses ->
            // category-food, incomes -> category-salary, transfers bank<->wallet.
            ordinals.forEach { ordinal ->
                val row = byOrdinal.getValue(ordinal)
                val ids =
                    BatchCommitIdSource(
                        listOf(
                            commitIds(
                                "confirmation-s$ordinal",
                                "status-s$ordinal-2",
                                "tx-s$ordinal",
                                "version-s$ordinal-v1",
                                "posting-set-s$ordinal",
                                listOf("posting-s$ordinal-0", "posting-s$ordinal-1"),
                            ),
                        ),
                    )
                val hash = fingerprint.digest(row.recordKind, row.facts)
                val request =
                    if (row.recordKind == ImportRecordKind.TRANSFER_FLOW_SOURCE) {
                        // File order R5..R8: out rows take bank=from, in rows bank=to
                        // (bank-side direction-gate variant; spec section 3.4).
                        val out = row.facts.directionToken == "out"
                        bankTransferConfirmRequest("req-s$ordinal-confirm", "candidate-s$ordinal", hash, from = if (out) bankAccountId else walletAccountId, to = if (out) walletAccountId else bankAccountId)
                    } else {
                        ordinaryConfirmRequest("req-s$ordinal-confirm", "candidate-s$ordinal", hash, category = if (row.facts.directionToken == "out") "category-food" else "category-salary")
                    }
                val confirmExecutor =
                    if (row.recordKind == ImportRecordKind.TRANSFER_FLOW_SOURCE) {
                        bankTransferExecutor(database, driverCmb, cat, intakeIds, ids, BatchStatusIdSource(emptyList()))
                    } else {
                        ordinaryExecutor(database, driverCmb, cat, intakeIds, ids, BatchStatusIdSource(emptyList()))
                    }
                val decision = confirmExecutor.confirm(request)
                check(decision is ImportCandidateDecisionResult.Accepted) { "ordinal $ordinal confirm: $decision" }
            }

            // Ledger-side bank deltas in chronological order (ascending occurred_at).
            val bankDeltas =
                selectRows(
                    driverCmb,
                    "SELECT v.occurred_at, p.amount_minor FROM posting p " +
                        "JOIN transaction_version v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
                        "WHERE p.ledger_id = '${ledgerId.value}' AND p.account_id = '${bankAccountId.value}' " +
                        "ORDER BY v.occurred_at",
                    listOf(false, true),
                )

            // Segment A (R8 oldest -> R1 newest): opening seed = declared[R8] - delta[R8]
            // = 950.00 - 510.30 = 439.70. Every confirmed-row boundary must replay to the
            // declared balance (B-02 end anchor + B-03 point-in-time boundaries).
            val seedSegmentA = 43970L
            val declaredA = listOf(95000L, 45000L, 65000L, 55000L, 55300L, 64100L, 62850L, 50000L) // R8..R1
            val deltasA = listOf(51030L, -50000L, 20000L, -10000L, 300L, 8800L, -1250L, -12850L)
            assertEquals(9, bankDeltas.size)
            // The re-anchored R16 posting is chronologically the oldest bank delta.
            assertEquals(9999999999L, bankDeltas[0][1] as Long)
            val segmentADeltas = bankDeltas.drop(1).map { it[1] as Long }
            assertEquals(deltasA, segmentADeltas)
            var running = seedSegmentA
            declaredA.forEachIndexed { index, declared ->
                running += segmentADeltas[index]
                assertEquals(declared, running, "segment A boundary ${index + 1} (R${8 - index}) replay mismatch")
            }

            // Segment B = R16..R17 (re-anchored after the record_error block). Opening
            // seed = declared[R17] - delta[R17] = 0.60 - 0.00. R17 (zero expense) has no
            // confirmable formal entry (domain binding requires a positive amount) and
            // contributes delta 0; R16 contributes +99999999.99. Boundaries: R17 = 0.60,
            // R16 = 100000000.59.
            val r16Delta = bankDeltas.single { (it[0] as String) == "2026-08-10T07:30:00Z" }[1] as Long
            assertEquals(9999999999L, r16Delta)
            var runningB = 60L
            runningB += 0L // R17 boundary (zero delta, declared 0.60)
            assertEquals(60L, runningB)
            runningB += r16Delta // R16 boundary (declared 100000000.59)
            assertEquals(10000000059L, runningB)

            // B-04 privacy: declared balance values never reach any persisted column.
            listOf("439.70", "419.61", "100000000.59", "628.50", "0.60", "500.00").forEach { balanceText ->
                val leaked =
                    scalarText(
                        driverCmb,
                        "SELECT " +
                            "(SELECT COUNT(*) FROM import_source_record WHERE ledger_id = '${ledgerId.value}' AND occurred_at LIKE '%$balanceText%') + " +
                            "(SELECT COUNT(*) FROM import_evidence WHERE ledger_id = '${ledgerId.value}' AND observed_at LIKE '%$balanceText%') + " +
                            "(SELECT COUNT(*) FROM posting WHERE ledger_id = '${ledgerId.value}' AND amount_minor LIKE '%$balanceText%') + " +
                            "(SELECT COUNT(*) FROM transaction_version WHERE ledger_id = '${ledgerId.value}' AND occurred_at LIKE '%$balanceText%')",
                    )
                assertEquals("0", leaked, "declared balance value leaked into a persisted column: $balanceText")
            }
        } finally {
            driverCmb.close()
        }

        // CCB ledger: segment = B4..B7, baseline = declared[B3] = 76.50, end anchor 81.98.
        val driverCcb = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driverCcb)
            val database = LedgerDatabase(driverCcb)
            val cat = catalog(ccbLedgerId)
            val ordinals = listOf(3, 4, 5, 6) // B4..B7
            val intakeIds = BatchIntakeIdSource(ordinals.map { intakeIds("c$it", "status-c$it-1") })
            val executor = ordinaryExecutor(database, driverCcb, cat, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            ordinals.forEach { ordinal ->
                val row = ccbAcceptedRow(ordinal)
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(
                        intakeRequest(ccbLedgerId, "req-c$ordinal", "batch-bp01-ccb-a", ordinal, row.recordKind, row.facts, row.completeness),
                    ),
                )
            }
            ordinals.forEach { ordinal ->
                val row = ccbAcceptedRow(ordinal)
                val ids =
                    BatchCommitIdSource(
                        listOf(
                            commitIds(
                                "confirmation-c$ordinal",
                                "status-c$ordinal-2",
                                "tx-c$ordinal",
                                "version-c$ordinal-v1",
                                "posting-set-c$ordinal",
                                listOf("posting-c$ordinal-0", "posting-c$ordinal-1"),
                            ),
                        ),
                    )
                val hash = fingerprint.digest(row.recordKind, row.facts)
                val confirmExecutor =
                    if (row.recordKind == ImportRecordKind.TRANSFER_FLOW_SOURCE) {
                        val out = row.facts.directionToken == "out"
                        bankTransferExecutor(database, driverCcb, cat, intakeIds, ids, BatchStatusIdSource(emptyList()))
                            .confirm(
                                ImportCandidateConfirmRequest(
                                    identity = ImportRequestIdentity(ccbLedgerId, ImportRequestId("req-c$ordinal-confirm")),
                                    candidateId = ImportCandidateId("candidate-c$ordinal"),
                                    expectedContentHash = hash,
                                    explicitConfirmedAt = "2026-08-28T12:00:00+08:00",
                                    decisionFields =
                                        ImportConfirmDecisionFields.TransferFlow(
                                            fromAccountId = if (out) bankAccountId else walletAccountId,
                                            toAccountId = if (out) walletAccountId else bankAccountId,
                                        ),
                                ),
                            )
                    } else {
                        ordinaryExecutor(database, driverCcb, cat, intakeIds, ids, BatchStatusIdSource(emptyList()))
                            .confirm(
                                ImportCandidateConfirmRequest(
                                    identity = ImportRequestIdentity(ccbLedgerId, ImportRequestId("req-c$ordinal-confirm")),
                                    candidateId = ImportCandidateId("candidate-c$ordinal"),
                                    expectedContentHash = hash,
                                    explicitConfirmedAt = "2026-08-28T12:00:00+08:00",
                                    decisionFields =
                                        ImportConfirmDecisionFields.OrdinaryFlow(
                                            categoryId = CategoryId("category-food"),
                                            fundingAccountId = bankAccountId,
                                        ),
                                ),
                            )
                    }
                val decision = confirmExecutor
                check(decision is ImportCandidateDecisionResult.Accepted) { "CCB ordinal $ordinal confirm: $decision" }
            }

            val bankDeltas =
                selectRows(
                    driverCcb,
                    "SELECT v.occurred_at, p.amount_minor FROM posting p " +
                        "JOIN transaction_version v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
                        "WHERE p.ledger_id = '${ccbLedgerId.value}' AND p.account_id = '${bankAccountId.value}' " +
                        "ORDER BY v.occurred_at",
                    listOf(false, true),
                )
            // Chronological B4 (-1.00), B5 (+7.50), B6 (-1.01), B7 (-0.01).
            assertEquals(listOf(-100L, 750L, -101L, -1L), bankDeltas.map { it[1] as Long })
            // B-02: baseline = declared[B3] = 76.50; end anchor B7 = 81.98.
            // B-03: each confirmed boundary replays to its declared balance.
            var running = 7650L
            listOf(7550L, 8300L, 8199L, 8198L).forEachIndexed { index, declared ->
                running += bankDeltas[index][1] as Long
                assertEquals(declared, running, "CCB boundary ${index + 1} (B${index + 4}) replay mismatch")
            }
        } finally {
            driverCcb.close()
        }
    }

    // ---- Concurrency plumbing (P4-02/P4-03 test pattern) ----

    private fun intakeOn(
        url: String,
        row: CmbRowResult.Accepted,
    ): ImportIntakeResult =
        JdbcSqliteDriver(url).use { driver ->
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val useCase = ExecuteImportIntake(store, BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1"))), ImportContentFingerprint())
            return useCase.execute(
                intakeRequest(ledgerId, "req-a-intake", "batch-bp01-cmb-a", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, row.facts, row.completeness),
            )
        }

    private fun confirmOn(
        url: String,
        cat: LedgerCatalog,
        commitIds: ImportIdSource,
        request: ImportCandidateConfirmRequest,
    ): ImportCandidateDecisionResult =
        JdbcSqliteDriver(url).use { driver ->
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val confirm = ConfirmImportCandidate(store, commitIds, BankStatementTransferFlowFormalFactory(cat, bankAccountId), cat)
            return confirm.execute(request)
        }

    private fun concurrentExecute(
        url: String,
        operations: List<() -> Any>,
    ): List<Any> {
        val pool = Executors.newFixedThreadPool(operations.size)
        val ready = CountDownLatch(operations.size)
        val start = CountDownLatch(1)
        return try {
            val futures =
                operations.map { operation ->
                    pool.submit<Any> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        operation()
                    }
                }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
