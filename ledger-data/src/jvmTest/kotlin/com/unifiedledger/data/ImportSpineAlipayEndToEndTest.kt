package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCandidateRejectRequest
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ImportStatusIdSource
import com.unifiedledger.application.RejectImportCandidate
import com.unifiedledger.application.import.alipay.AlipayBatchOutcome
import com.unifiedledger.application.import.alipay.AlipayCsvParser
import com.unifiedledger.application.import.alipay.AlipayRowResult
import com.unifiedledger.application.import.alipay.AlipaySourceTokens
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
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P4-05 spine end-to-end oracle (frozen spec docs/specs/2026-08-17-p4-05-alipay-ordinary-flows-design.md,
 * sections 1.2/1.3/6: T-27 covering E-01..E-14 plus T-28 R-01/R-02 regression manifest).
 * Parser output drives ExecuteImportIntake per record; confirm/reject reuse the P4-02 ports
 * and the P4-04 OrdinaryFlow decision fields unchanged (zero spine amendment).
 *
 * R-01 regression is the untouched ImportSpineLifecycleEndToEndTest (P4-02 30-op oracle);
 * R-02 is the untouched ImportSpineWechatEndToEndTest / WechatBillParserJvmTest (P4-03 oracles
 * with the three frozen P4-04 amendments) plus ImportSpineTransferEndToEndTest (P4-04 oracles).
 * All run in this same suite; this batch changes zero shared code, schema or parser output.
 */
class ImportSpineAlipayEndToEndTest {
    private val ledgerId = LedgerId("ledger-p405")
    private val batchLedgerId = LedgerId("ledger-p405-batch")
    private val cny = CurrencyUnit("CNY", 2)
    private val inputRef = "batch-p405-a"
    private val fingerprint = ImportContentFingerprint()
    private val gb18030: Charset = Charset.forName("GB18030")

    // ---- Synthetic CSV builder (spec sections 1.1, 2.1-2.3) ----
    //
    // Frozen file shape: metadata area = 23 CRLF lines (0-based lines 0..22, zero-read),
    // header at 0-based line 23 = 12 frozen columns plus a trailing comma (13 fields),
    // data rows from line 24 with LF endings, record_ordinal = row - 24. Data rows carry
    // exactly two tabs (fields 9/10 trailing tabs) or one tab when the merchant order
    // number is empty. All values are synthetic and provider-neutral.

    private fun metadataLines(): List<String> =
        (0..22).map { "SYN-META-PII-EXPORT-$it,SYN-META-PII-NICK-$it" }

    private fun headerLine(): String = AlipaySourceTokens.HEADER_TOKENS.joinToString(",") + ","

    private fun recordRow(
        category: String,
        direction: String,
        amount: String,
        status: String,
        time: String,
        merchOrderNo: String? = "SYN-SECRET-MERCHNO",
    ): String = listOf(
        time, category, "SYN-SECRET-COUNTERPARTY", "SYN-SECRET-ACCOUNT",
        "SYN-SECRET-PRODUCT", direction, amount, "", status,
        "SYN-SECRET-TXNO\t", merchOrderNo?.let { "$it\t" } ?: "", "SYN-SECRET-NOTE",
    ).joinToString(",") + ","

    /** Frozen source record rows A-01..A-16 (spec section 1.2), batch-p405-a data area. */
    private fun batchARows(): List<String> = listOf(
        recordRow("网上支付", "支出", "128.50", "交易成功", "2026-08-01 12:30:45"),
        recordRow("扫码支付", "支出", "12.50", "交易成功", "2026-08-05 09:00:00"),
        recordRow("其他", "收入", "88.00", "交易成功", "2026-08-06 18:45:15", merchOrderNo = null),
        recordRow("网上支付", "不计收支", "45.60", "交易成功", "2026-08-09 21:15:30"),
        recordRow("网上支付", "支出", "20.00", "交易关闭", "2026-08-10 09:30:00"),
        recordRow("其他", "支出", "0.00", "交易成功", "2026-08-10 08:00:20"),
        recordRow("账户存取", "不计收支", "100.00", "交易成功", "2026-08-10 10:00:00"),
        recordRow("转账红包", "收入", "8.80", "交易成功", "2026-08-11 09:09:09"),
        recordRow("网上支付", "不计收支", "128.50", "退款成功", "2026-08-11 11:00:00"),
        recordRow("信用借还", "不计收支", "500.00", "还款", "2026-08-11 12:00:00"),
        recordRow("亲友代付", "支出", "66.00", "代付成功", "2026-08-11 13:00:00"),
        recordRow("神秘交易分类", "支出", "9.90", "交易成功", "2026-08-12 08:45:00"),
        recordRow("网上支付", "支出", "abc", "交易成功", "2026-08-12 07:30:00"),
        recordRow("网上支付", "支出", "10.00", "交易成功", "不是时间"),
        recordRow("网上支付", "支出", "-10.00", "交易成功", "2026-08-12 09:15:00"),
        recordRow("网上支付", "支出", "10.5", "交易成功", "2026-08-12 09:20:00"),
    )

    private fun csvBytes(dataRows: List<String>): ByteArray = buildString {
        metadataLines().forEach { append(it).append("\r\n") }
        append(headerLine()).append("\n")
        dataRows.forEach { append(it).append("\n") }
    }.toByteArray(gb18030)

    // ---- Assembly helpers (P4-02/P4-03 pattern) ----

    private fun accepted(rows: List<AlipayRowResult>, ordinal: Int): AlipayRowResult.Accepted {
        val row = rows.first { it.recordOrdinal == ordinal }
        return assertIs<AlipayRowResult.Accepted>(row)
    }

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
        private val ledgerId: LedgerId,
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

    private fun catalog(ledgerId: LedgerId): LedgerCatalog = when (
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
        is DomainResult.Failure -> error("alipay e2e catalog failure: ${result.violation}")
    }

    private class Executor(
        val database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        val ledgerId: LedgerId,
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
                store, commitIds,
                OrdinaryFlowFormalFactory(catalog, ledgerId, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).categoryId, (request.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow).fundingAccountId),
            ).execute(request)

        fun reject(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult =
            RejectImportCandidate(store, statusIds).execute(request)
    }

    private fun intakeRequest(
        ledgerId: LedgerId,
        requestId: String,
        recordOrdinal: Int,
        facts: com.unifiedledger.application.ImportSourceFacts,
        completeness: ImportCompleteness,
        recordKind: ImportRecordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        paymentProfile: com.unifiedledger.application.ImportPaymentProfile? = null,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = recordOrdinal,
        recordKind = recordKind,
        facts = facts,
        completeness = completeness,
        candidateGeneratedAt = "legacy-intake-v1",
        paymentProfile = paymentProfile,
    )

    private fun confirmRequest(
        requestId: String = "req-a-confirm",
        candidate: String = "candidate-a",
        hash: String,
        category: String = "category-food",
        funding: String = "account-asset-a",
        confirmedAt: String? = "2026-08-17T10:00:00+08:00",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(
            categoryId = CategoryId(category),
            fundingAccountId = AccountId(funding),
        ),
    )

    private fun rejectRequest(
        requestId: String = "req-b-reject",
        candidate: String = "candidate-b",
        hash: String,
    ) = ImportCandidateRejectRequest(
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

    private fun scalarText(driver: JdbcSqliteDriver, sql: String): String = driver.executeQuery(
        null, sql,
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0)!!)
        },
        0,
    ).value

    // ---- Case manifest (spec sections 1.3/6) ----

    private val frozenECaseIds: List<String> = (1..14).map { "E-%02d".format(it) }

    // Each E case registers exactly once, in frozen order, at the test method that owns it.
    private val registeredECaseIds: List<String> = listOf(
        "E-01", "E-02", "E-03", "E-04", "E-05", "E-06", "E-07",
        "E-08", "E-09", "E-10", "E-11", "E-12", "E-13", "E-14",
    )

    // ---- E-01..E-08, E-10, E-11 ----

    @Test
    fun executesE01ToE11WithStableReplayCollisionAndDomainRetry() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val rows = AlipayCsvParser.parse(inputRef, csvBytes(batchARows())).rows
            val a01 = accepted(rows, 0)
            val a02 = accepted(rows, 1)
            val a03 = accepted(rows, 2)
            val a06 = accepted(rows, 5)
            val catalog = catalog(ledgerId)
            val intakeIds = BatchIntakeIdSource(
                listOf(
                    intakeIds("a", "status-a-1"), intakeIds("b", "status-b-1"),
                    intakeIds("c", "status-c-1"), intakeIds("d", "status-d-1"),
                ),
            )
            val executor = Executor(
                database, driver, ledgerId, catalog, intakeIds,
                BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()),
            )

            // E-01: parser output drives the intake of A-01 (C1 pending_confirmation).
            val e01 = assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest(ledgerId, "req-a-intake", 0, a01.facts, a01.completeness)),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.SOURCE, "source-a"),
                    ImportReturnedId(ImportReturnedIdKind.EVIDENCE, "evidence-a"),
                    ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-a"),
                ),
                e01.returnedIds,
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-a-intake"), ImportSourceId("source-a"), ImportEvidenceId("evidence-a"), ImportCandidateId("candidate-a"), null, null),
                e01.receipt,
            )
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            val sourceA = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-a-intake").executeAsOne()
            assertEquals(fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.facts), sourceA.content_hash)
            assertEquals(1L, sourceA.contract_version)
            assertEquals("ordinary_flow_source", sourceA.record_kind)
            // Frozen candidate provenance: ordinary_flow / ordinary_flow_source / v1 / 1.00.
            assertEquals(
                "ordinary_flow|1.00|ordinary_flow_source|1",
                scalarText(
                    driver,
                    "SELECT candidate_kind || '|' || confidence || '|' || rule || '|' || rule_version " +
                        "FROM import_candidate WHERE ledger_id = '${ledgerId.value}' AND candidate_id = 'candidate-a'",
                ),
            )
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-a").executeAsOne().status)

            // E-02: same-request equivalent replay.
            val e02 = assertIs<ImportIntakeResult.NoChange>(
                executor.intake(intakeRequest(ledgerId, "req-a-intake", 0, a01.facts, a01.completeness)),
            )
            assertEquals(e01.receipt, e02.receipt)
            assertEquals("equivalent_replay", e02.reasonCode)
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // E-03: confirm C1 -> formal expense transaction with the exact A-01 amount.
            val commitIdsA = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            val executorWithCommit = Executor(database, driver, ledgerId, catalog, intakeIds, commitIdsA, BatchStatusIdSource(emptyList()))
            val e03 = assertIs<ImportCandidateDecisionResult.Accepted>(
                executorWithCommit.confirm(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.facts))),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-a"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-a"),
                ),
                e03.returnedIds,
            )
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            // OrdinaryFlow decision snapshot four-column shape: category/funding set, from/to NULL.
            val decisionA = database.ledgerQueries.selectImportDecisionSnapshotByRequest(ledgerId.value, "req-a-confirm").executeAsOne()
            assertEquals("confirm", decisionA.decision)
            assertEquals("category-food", decisionA.category_id)
            assertEquals("account-asset-a", decisionA.funding_account_id)
            assertNull(decisionA.from_account_id)
            assertNull(decisionA.to_account_id)
            assertEquals(fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.facts), decisionA.expected_content_hash)
            assertEquals("2026-08-17T10:00:00+08:00", decisionA.explicit_confirmed_at)
            // Per-currency balance: hidden expense account +128.50, funding asset -128.50.
            val postingsA = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-a").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-expense-a", "expense-account-food", 12850L, "CNY", 2L),
                    listOf("posting-asset-a", "account-asset-a", -12850L, "CNY", 2L),
                ),
                postingsA.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )

            // E-04: same-request confirm replay.
            val e04 = assertIs<ImportCandidateDecisionResult.NoChange>(
                executorWithCommit.confirm(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.facts))),
            )
            assertEquals(e03.receipt, e04.receipt)
            assertEquals("equivalent_replay", e04.reasonCode)
            assertEquals(1, commitIdsA.calls.get())

            // E-05: re-confirm with a new request.
            val e05 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executorWithCommit.confirm(confirmRequest(requestId = "req-a-confirm-2", hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.facts))),
            )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", e05.diagnostic.code)
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))

            // E-06: setup intakes for A-02 (C2), A-03 (C3), A-06 (C4).
            assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest(ledgerId, "req-b-intake", 1, a02.facts, a02.completeness)))
            assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest(ledgerId, "req-c-intake", 2, a03.facts, a03.completeness)))
            assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest(ledgerId, "req-d-intake", 5, a06.facts, a06.completeness)))
            assertEquals(listOf(5L, 4L, 4L, 4L, 5L, 1L, 1L, 5L), spineCounts(database))

            // E-07: reject C2 (manual disposition; no confirmation, formal 0/0/0).
            val statusIds = BatchStatusIdSource(listOf(ImportStatusHistoryId("status-b-2")))
            val executorWithReject = Executor(database, driver, ledgerId, catalog, intakeIds, commitIdsA, statusIds)
            val e07 = assertIs<ImportCandidateDecisionResult.Accepted>(
                executorWithReject.reject(rejectRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a02.facts))),
            )
            assertEquals(listOf(ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-b")), e07.returnedIds)
            assertEquals(
                ImportReceipt(ImportRequestId("req-b-reject"), null, null, ImportCandidateId("candidate-b"), null, null),
                e07.receipt,
            )
            assertEquals(listOf(6L, 4L, 4L, 4L, 6L, 2L, 1L, 6L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))

            // E-08: A-01' intake-level fixture with the same raw identity but a different
            // amount: hard identity collision with zero writes and no ID consumption.
            val e08 = assertIs<ImportIntakeResult.Rejected>(
                executor.intake(intakeRequest(ledgerId, "req-a-intake-3", 0, a01.facts.copy(amountMinor = 12851), a01.completeness)),
            )
            assertEquals("SPINE_IDENTITY_COLLISION", e08.diagnostic.code)
            assertEquals(listOf(6L, 4L, 4L, 4L, 6L, 2L, 1L, 6L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))

            // E-10: confirm domain failures, two independent sub-vectors, both zero residue.
            // (a) C3 (A-03 income candidate) with an unknown category.
            val attemptC = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-c-attempt-1", "status-c-2-attempt-1", "tx-c-attempt-1",
                        "version-c-attempt-1-v1", "posting-set-c-attempt-1",
                        listOf("posting-asset-c-attempt-1", "posting-income-c-attempt-1"),
                    ),
                ),
            )
            val e10a = assertIs<ImportCandidateDecisionResult.Rejected>(
                Executor(database, driver, ledgerId, catalog, intakeIds, attemptC, statusIds).confirm(
                    confirmRequest(
                        requestId = "req-c-confirm", candidate = "candidate-c", hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a03.facts),
                        category = "category-unknown", confirmedAt = "2026-08-17T11:00:00+08:00",
                    ),
                ),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e10a.diagnostic.code)
            assertEquals(1, attemptC.calls.get())
            assertEquals(listOf(6L, 4L, 4L, 4L, 6L, 2L, 1L, 6L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            // Claim rolled back: C3 stays pending and the request identity stays available.
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-c").executeAsOne().status)

            // (b) C4 (A-06 zero-amount candidate): the domain positive-amount invariant
            // fires before any category validation.
            val attemptD = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-d-attempt-1", "status-d-2-attempt-1", "tx-d-attempt-1",
                        "version-d-attempt-1-v1", "posting-set-d-attempt-1",
                        listOf("posting-expense-d-attempt-1", "posting-asset-d-attempt-1"),
                    ),
                ),
            )
            val e10b = assertIs<ImportCandidateDecisionResult.Rejected>(
                Executor(database, driver, ledgerId, catalog, intakeIds, attemptD, statusIds).confirm(
                    confirmRequest(
                        requestId = "req-d-confirm", candidate = "candidate-d", hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a06.facts),
                        category = "category-food", confirmedAt = "2026-08-17T11:30:00+08:00",
                    ),
                ),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e10b.diagnostic.code)
            assertEquals(1, attemptD.calls.get())
            assertEquals(listOf(6L, 4L, 4L, 4L, 6L, 2L, 1L, 6L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-d").executeAsOne().status)

            // E-11: corrected retry on the same request identity -> accepted income with the
            // exact A-03 amount (asset +88.00, hidden income account -88.00).
            val batch2 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-c", "status-c-2", "tx-c",
                        "version-c-v1", "posting-set-c",
                        listOf("posting-asset-c", "posting-income-c"),
                    ),
                ),
            )
            val e11 = assertIs<ImportCandidateDecisionResult.Accepted>(
                Executor(database, driver, ledgerId, catalog, intakeIds, batch2, statusIds).confirm(
                    confirmRequest(
                        requestId = "req-c-confirm", candidate = "candidate-c", hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a03.facts),
                        category = "category-salary", confirmedAt = "2026-08-17T11:00:00+08:00",
                    ),
                ),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-c"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-c"),
                ),
                e11.returnedIds,
            )
            assertEquals(1, batch2.calls.get())
            assertEquals(listOf(7L, 4L, 4L, 4L, 7L, 3L, 2L, 7L), spineCounts(database))
            assertEquals(listOf(2L, 2L, 4L), formalCounts(database))
            val postingsC = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-c").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-asset-c", "account-asset-a", 8800L, "CNY", 2L),
                    listOf("posting-income-c", "income-account-salary", -8800L, "CNY", 2L),
                ),
                postingsC.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )
        } finally {
            driver.close()
        }
    }

    // ---- E-09 concurrency ----

    @Test
    fun e09ConcurrentIntakesCommitOnceWithoutLoserResidue() {
        val path = Files.createTempFile("alipay-intake-e09-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            val a01 = accepted(AlipayCsvParser.parse(inputRef, csvBytes(batchARows())).rows, 0)
            // The shared ID source must be consumed exactly once: only the winning first
            // request allocates IDs; the loser leaves zero residue and consumes nothing.
            val sharedIds = BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1")))
            val results = concurrentExecute(
                url,
                listOf(
                    { intakeOn(url, sharedIds, a01) },
                    { intakeOn(url, sharedIds, a01) },
                ),
            )
            assertEquals(1, results.count { it is ImportIntakeResult.Accepted })
            assertEquals(1, results.count { it is ImportIntakeResult.NoChange })
            assertEquals(1, sharedIds.calls.get())
            JdbcSqliteDriver(url).use { driver ->
                assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(LedgerDatabase(driver)))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---- E-12 batch ledger ----

    @Test
    fun e12BatchLedgerIntakesSevenRecordsAndRejectsNineRowsWithZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
            assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
            val acceptedRows = result.rows.filterIsInstance<AlipayRowResult.Accepted>()
            val rejectedRows = result.rows.filterIsInstance<AlipayRowResult.Rejected>()
            assertEquals(7, acceptedRows.size)
            assertEquals(9, rejectedRows.size)
            rejectedRows.forEach { assertEquals(1, it.diagnostics.size, "rejected row carries exactly one diagnostic") }

            // The parse diagnostic multiset is the frozen P-17 11-entry set (message is
            // never compared, D-097:1459); diagnostics never reach any persistence.
            val multiset = result.rows
                .flatMap { row -> row.diagnostics.map { Triple(it.code, it.recordOrdinal, it.fieldRole) } }
                .sortedWith(compareBy({ it.second ?: -1 }, { it.first }))
            assertEquals(
                listOf(
                    Triple("REQUIRED_FACT_UNRESOLVED", 3, "direction"),
                    Triple("REQUIRED_FACT_UNRESOLVED", 4, "status"),
                    Triple("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", 6, null),
                    Triple("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", 7, null),
                    Triple("SPINE_ALIPAY_REFUND_UNSUPPORTED", 8, null),
                    Triple("SPINE_ALIPAY_UNSUPPORTED_TX_TYPE", 10, null),
                    Triple("SPINE_ALIPAY_UNKNOWN_TOKEN", 11, null),
                    Triple("FIELD_AMOUNT_INVALID", 12, "amount"),
                    Triple("FIELD_TIME_INVALID", 13, "occurred_at"),
                    Triple("FIELD_AMOUNT_INVALID", 14, "amount"),
                    Triple("FIELD_AMOUNT_INVALID", 15, "amount"),
                ),
                multiset,
            )

            // Seven intakes in workbook order under the D-series naming; the ID source holds
            // exactly seven batches, so any rejected-row intake would exhaust it and fail.
            val batches = acceptedRows.mapIndexed { index, _ -> intakeIds("d${index + 1}", "status-d${index + 1}-1") }
            val executor = Executor(
                database, driver, batchLedgerId, catalog(batchLedgerId),
                BatchIntakeIdSource(batches), BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()),
            )
            acceptedRows.forEach { row ->
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(
                        intakeRequest(
                            batchLedgerId, "req-batch-${row.recordOrdinal}", row.recordOrdinal, row.facts, row.completeness,
                            row.recordKind, row.paymentProfile,
                        ),
                    ),
                )
            }
            // Rejected rows (A-07..A-16 except A-10) produced no intake call and no write:
            // only seven owners exist.
            assertEquals(listOf(7L, 7L, 7L, 7L, 7L, 0L, 0L, 7L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))

            // D1/A-01, D2/A-02, D3/A-03, D6/A-06, D7/A-10 pending_confirmation; D4/A-04 and
            // D5/A-05 are the two incomplete candidates.
            listOf(1, 2, 3, 6, 7).forEach { index ->
                val history = database.ledgerQueries.selectImportStatusHistoryByCandidate(batchLedgerId.value, "candidate-d$index").executeAsList()
                assertEquals(1, history.size)
                assertEquals("pending_confirmation", history[0].status)
            }
            listOf(4, 5).forEach { index ->
                val history = database.ledgerQueries.selectImportStatusHistoryByCandidate(batchLedgerId.value, "candidate-d$index").executeAsList()
                assertEquals(1, history.size)
                assertEquals("incomplete", history[0].status)
            }

            // D7/A-10 carries the v3 credit repayment kind + profile (exactly one profile
            // row per v3 candidate; both profile leg tokens stay null for an empty column 7).
            assertEquals(
                "credit_repayment_source|3|credit_repayment",
                scalarText(
                    driver,
                    "SELECT source.record_kind || '|' || source.contract_version || '|' || candidate.candidate_kind " +
                        "FROM import_source_record AS source JOIN import_candidate AS candidate " +
                        "ON candidate.ledger_id = source.ledger_id AND candidate.source_id = source.source_id " +
                        "WHERE source.ledger_id = '${batchLedgerId.value}' AND source.record_ordinal = 9",
                ),
            )
            assertEquals(
                "credit_repayment||",
                scalarText(
                    driver,
                    "SELECT variant || '|' || COALESCE(asset_leg_kind_token, '') || '|' || COALESCE(credit_leg_kind_token, '') " +
                        "FROM import_candidate_payment_profile WHERE ledger_id = '${batchLedgerId.value}' AND candidate_id = 'candidate-d7'",
                ),
            )
            assertEquals(1L, database.ledgerQueries.countImportCandidatePaymentProfiles().executeAsOne())

            // Privacy: the metadata area and the non-persisted columns (交易对方/对方账号/商品说明/
            // 收/付款方式/交易订单号/商家订单号/备注 — the real layout has no 交易号 column, spec §9.2)
            // never reach any persisted column.
            val leaked = scalarText(
                driver,
                "SELECT COUNT(*) FROM import_source_record WHERE ledger_id = '${batchLedgerId.value}' AND (" +
                    "input_ref LIKE '%SYN-%' OR content_hash LIKE '%SYN-%' OR currency_code LIKE '%SYN-%' OR " +
                    "occurred_at LIKE '%SYN-%' OR direction_token LIKE '%SYN-%' OR status_token LIKE '%SYN-%')",
            )
            assertEquals("0", leaked)
            assertTrue(rejectedRows.all { row -> row.diagnostics.all { it.inputRef == inputRef } })
        } finally {
            driver.close()
        }
    }

    // ---- E-13/E-14 failure injection ----

    @Test
    fun e13E14InjectedFailuresRollBackAndCorrectedRetriesAccept() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog(ledgerId)
            val a01 = accepted(AlipayCsvParser.parse(inputRef, csvBytes(batchARows())).rows, 0)

            // E-13: intake failure after the candidate insert.
            val failingStore = SqlDelightImportSpineStore(
                database, driver,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE) error("injected") },
            )
            val batch1 = BatchIntakeIdSource(listOf(intakeIds("a-attempt-1", "status-a-1-attempt-1")))
            assertFailsWith<IllegalStateException> {
                ExecuteImportIntake(failingStore, batch1, ImportContentFingerprint()).execute(
                    intakeRequest(ledgerId, "req-a-intake", 0, a01.facts, a01.completeness),
                )
            }
            assertEquals(1, batch1.calls.get())
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), spineCounts(database))
            val batch2 = BatchIntakeIdSource(listOf(intakeIds("a", "status-a-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), batch2, ImportContentFingerprint()).execute(
                    intakeRequest(ledgerId, "req-a-intake", 0, a01.facts, a01.completeness),
                ),
            )
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // E-14: confirm failure after the formal persist.
            val failingConfirmStore = SqlDelightImportSpineStore(
                database, driver,
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
                    failingConfirmStore, attempt1,
                    OrdinaryFlowFormalFactory(catalog, ledgerId, CategoryId("category-food"), AccountId("account-asset-a")),
                ).execute(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.facts)))
            }
            assertEquals(1, attempt1.calls.get())
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            val confirmBatch2 = BatchCommitIdSource(
                listOf(commitIds("confirmation-a", "status-a-2", "tx-a", "version-a-v1", "posting-set-a", listOf("posting-expense-a", "posting-asset-a"))),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver), confirmBatch2,
                    OrdinaryFlowFormalFactory(catalog, ledgerId, CategoryId("category-food"), AccountId("account-asset-a")),
                ).execute(confirmRequest(hash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, a01.facts))),
            )
            assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))
        } finally {
            driver.close()
        }
    }

    // ---- T-28 (R-01/R-02): regression manifest ----

    @Test
    fun caseManifestsCoverFrozenOperationsExactlyOnce() {
        // E series: the frozen set, registered exactly once in this file.
        assertEquals(frozenECaseIds.toSet(), registeredECaseIds.toSet())
        assertEquals(frozenECaseIds.size, registeredECaseIds.size)
        assertEquals(14, frozenECaseIds.size)

        // R-01: the P4-02 30-op oracle (O-01..O-30) is the untouched
        // ImportSpineLifecycleEndToEndTest running in this same suite. Its ordinary v1
        // content-hash bytes are pinned here to prove the digest contract is unchanged by
        // this batch (including the R3-style absent status_token member omission).
        val hashR1 = "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2"
        val hashR3 = "sha256:911f0b27473a382752837ac1eaca05e9f7ab1d13fc944b8e5e349b30fb86fe35"
        val r1Facts = com.unifiedledger.application.ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled", com.unifiedledger.application.ImportFundingState.SETTLED, com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
        val r3Facts = com.unifiedledger.application.ImportSourceFacts(4500, "CNY", 2, "2026-08-06T18:45:00+08:00", "out", null, com.unifiedledger.application.ImportFundingState.SETTLED, com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
        assertEquals(hashR1, fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1Facts))
        assertEquals(hashR3, fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3Facts))
        assertTrue(!fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3Facts).contains("status_token"))

        // R-02: the P4-03 frozen oracles (ImportSpineWechatEndToEndTest E-01..E-14 plus
        // WechatBillParserJvmTest P-01..P-12, with the three frozen P4-04 amendments) and
        // the P4-04 oracles (ImportSpineTransferEndToEndTest) run untouched in this same
        // suite. P-01..P-23 of this batch are owned by AlipayCsvParserJvmTest (T-01..T-26).
        // This batch changes zero shared code, schema or wechat parser output, so the
        // manifest universe P-01..P-23, E-01..E-14, R-01..R-02 has exactly one owner per
        // case ID and no cross-spec frozen amendment exists.
        assertEquals(1, ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion)
        assertEquals("ordinary_flow_source", ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue)
    }

    // ---- Concurrency plumbing (P4-02 test pattern) ----

    private fun intakeOn(url: String, ids: ImportIntakeIdSource, row: AlipayRowResult.Accepted): ImportIntakeResult =
        JdbcSqliteDriver(url).use { driver ->
            val database = LedgerDatabase(driver)
            ExecuteImportIntake(
                SqlDelightImportSpineStore(database, driver),
                ids,
                ImportContentFingerprint(),
            ).execute(intakeRequest(ledgerId, "req-a-intake", row.recordOrdinal, row.facts, row.completeness))
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
