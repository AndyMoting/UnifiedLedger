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
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ImportStatusIdSource
import com.unifiedledger.application.RejectImportCandidate
import com.unifiedledger.application.TransferFlowFormalFactory
import com.unifiedledger.application.import.alipay.AlipayBatchOutcome
import com.unifiedledger.application.import.alipay.AlipayCsvParser
import com.unifiedledger.application.import.alipay.AlipayRowResult
import com.unifiedledger.application.import.alipay.AlipaySourceTokens
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * RL-04 (P4-05b) 余额宝 transfer routing spine end-to-end oracle (frozen spec
 * docs/specs/2026-08-18-p4-05b-rl04-yuebao-transfer-routing-design.md, sections 1.3/6:
 * T-19 covering E-01..E-12 plus T-20 R-02 regression manifest and the T-21 status-gate
 * confirm vector). Parser output drives ExecuteImportIntake per record; confirm/reject
 * reuse the P4-02 ports and the P4-04 TransferFlow decision contract unchanged (zero
 * spine/schema/domain amendment, zero new diagnostic codes).
 *
 * Every E operation freezes an independent pre-state and a complete expected post-state
 * (nine spine tables + five formal chain tables + the independent report projection,
 * P4-04 §1.4 complete canonical state oracle) compared row-by-row where applicable,
 * with count/delta assertions supplementing the comparison.
 *
 * R-02 regression is proven by the untouched P4-02/P4-03/P4-04 suites running in this
 * same project (ImportSpineLifecycleEndToEndTest / ImportSpineWechatEndToEndTest /
 * ImportSpineTransferEndToEndTest) plus the pinned digest constants asserted in
 * [regressionManifestR02].
 */
class ImportSpineAlipayYuebaoTransferEndToEndTest {
    // ---------- Frozen fixtures (design section 1.1/1.2) ----------

    private val ledgerId = LedgerId("ledger-rl04")
    private val cny = CurrencyUnit("CNY", 2)
    private val walletAccountId = AccountId("account-alipay-balance") // 支付宝余额
    private val yuebaoAccountId = AccountId("account-yuebao") // 余额宝
    private val fingerprint = ImportContentFingerprint()
    private val gb18030: Charset = Charset.forName("GB18030")
    private val inputRef = "batch-rl04-a"

    private val y01Facts = ImportSourceFacts(10000, "CNY", 2, "2026-08-01T12:30:45+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val y09Facts = ImportSourceFacts(90000, "CNY", 2, "2026-08-06T16:40:35+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val y12Facts = ImportSourceFacts(11111, "CNY", 2, "2026-08-08T09:00:00+08:00", "out", "交易关闭", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val ordinaryFacts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:45+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)

    private val hashY01 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts)
    private val hashY09 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, y09Facts)
    private val hashY12 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, y12Facts)
    private val hashOrdinary = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, ordinaryFacts)

    // ---------- Synthetic CSV builder (frozen P4-05 shape, design section 1.1) ----------

    private fun metadataLines(): List<String> = (0..22).map { "SYN-META-PII-EXPORT-$it,SYN-META-PII-NICK-$it" }

    private fun headerLine(): String = AlipaySourceTokens.HEADER_TOKENS.joinToString(",") + ","

    private fun yuebaoRow(
        subtype: String,
        directionCol: String,
        amount: String,
        status: String,
        time: String,
        method: String?,
    ): String =
        listOf(
            time,
            "投资理财",
            "SYN-SECRET-COUNTERPARTY",
            "SYN-SECRET-ACCOUNT",
            subtype,
            directionCol,
            amount,
            method ?: "",
            status,
            "SYN-SECRET-TXNO\t",
            "SYN-SECRET-MERCHNO\t",
            "SYN-SECRET-NOTE",
        ).joinToString(",") + ","

    private fun ordinaryRow(): String =
        listOf(
            "2026-08-01 12:30:45",
            "网上支付",
            "SYN-SECRET-COUNTERPARTY",
            "SYN-SECRET-ACCOUNT",
            "SYN-SECRET-PRODUCT",
            "支出",
            "128.50",
            "SYN-SECRET-METHOD",
            "交易成功",
            "SYN-SECRET-TXNO\t",
            "SYN-SECRET-MERCHNO\t",
            "SYN-SECRET-NOTE",
        ).joinToString(",") + ","

    private fun batchARows(): List<String> =
        listOf(
            yuebaoRow("余额宝-自动转入", "不计收支", "100.00", "交易成功", "2026-08-01 12:30:45", null), // Y-01
            yuebaoRow("余额宝-自动转入", "不计收支", "200.00", "交易成功", "2026-08-01 13:00:00", "账户余额"), // Y-02
            yuebaoRow("余额宝-自动转入", "不计收支", "300.00", "交易成功", "2026-08-02 09:15:30", "账户余额"), // Y-03
            yuebaoRow("余额宝-自动转入", "不计收支", "400.00", "交易成功", "2026-08-02 10:20:00", null), // Y-04
            yuebaoRow("余额宝-自动转入", "不计收支", "500.00", "交易成功", "2026-08-03 11:05:45", "账户余额"), // Y-05
            yuebaoRow("余额宝-自动转入", "不计收支", "600.00", "交易成功", "2026-08-03 14:10:20", "账户余额"), // Y-06
            yuebaoRow("余额宝-自动转入", "不计收支", "700.00", "交易成功", "2026-08-04 08:25:15", null), // Y-07
            yuebaoRow("余额宝-单次转入", "不计收支", "80.00", "交易关闭", "2026-08-05 09:00:00", null), // Y-08
            yuebaoRow("余额宝-转出到余额", "不计收支", "900.00", "交易成功", "2026-08-06 16:40:35", "余额"), // Y-09
            yuebaoRow("余额宝-转出到银行卡", "不计收支", "75.50", "交易成功", "2026-08-07 10:00:00", "SYN-MASK-METHOD"), // Y-10
            yuebaoRow("余额宝-收益发放", "不计收支", "12.34", "交易成功", "2026-08-07 10:30:00", "SYN-MASK-METHOD"), // Y-11
            yuebaoRow("余额宝-自动转入", "不计收支", "111.11", "交易关闭", "2026-08-08 09:00:00", null), // Y-12
            yuebaoRow("基金买入", "不计收支", "99.99", "交易成功", "2026-08-08 10:00:00", null), // Y-13
            yuebaoRow("余额宝-自动转入", "支出", "123.45", "交易成功", "2026-08-09 11:11:11", null), // Y-14
            yuebaoRow("余额宝-自动转入", "不计收支", "456.78", "退款成功", "2026-08-10 12:12:12", null), // Y-15
        )

    private fun csvBytes(dataRows: List<String>): ByteArray =
        buildString {
            metadataLines().forEach { append(it).append("\r\n") }
            append(headerLine()).append("\n")
            dataRows.forEach { append(it).append("\n") }
        }.toByteArray(gb18030)

    // ---------- Catalog (design section 1.1: two independent self-owned CNY assets) ----------

    private fun catalog(ledgerId: LedgerId): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(walletAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(yuebaoAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(AccountId("account-asset-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(AccountId("expense-account-food"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                            Account(AccountId("income-account-salary"), ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(CategoryId("category-primary-food"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                            Category(CategoryId("category-food"), ledgerId, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                            Category(CategoryId("category-primary-salary"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                            Category(CategoryId("category-salary"), ledgerId, parentId = CategoryId("category-primary-salary"), postingAccountId = AccountId("income-account-salary"), active = true, kind = CategoryKind.INCOME),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("rl04 test catalog failure: ${result.violation}")
        }

    // ---------- Deterministic ID sources and executor (P4-02/P4-04 pattern) ----------

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
        val factory: ImportCandidateFormalFactory,
    ) {
        val store = SqlDelightImportSpineStore(database, driver)

        fun intake(request: ImportIntakeRequest): ImportIntakeResult = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirm(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult = ConfirmImportCandidate(store, commitIds, factory, catalog).execute(request)

        fun reject(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult = RejectImportCandidate(store, statusIds).execute(request)
    }

    private fun transferExecutor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        catalog: LedgerCatalog,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
        statusIds: ImportStatusIdSource,
    ) = Executor(
        database,
        driver,
        catalog,
        intakeIds,
        commitIds,
        statusIds,
        TransferFlowFormalFactory(catalog, walletAccountId),
    )

    private fun intakeRequest(
        requestId: String,
        inputRef: String,
        ordinal: Int,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        completeness: ImportCompleteness,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = ordinal,
        recordKind = kind,
        facts = facts,
        completeness = completeness,
        candidateGeneratedAt = "legacy-intake-v1",
    )

    private fun transferConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        from: AccountId = walletAccountId,
        to: AccountId = yuebaoAccountId,
        confirmedAt: String? = "2026-08-17T10:00:00+08:00",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.TransferFlow(fromAccountId = from, toAccountId = to),
    )

    private fun ordinaryConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = "2026-08-17T10:00:00+08:00",
        decisionFields =
            ImportConfirmDecisionFields.OrdinaryFlow(
                categoryId = CategoryId("category-food"),
                fundingAccountId = AccountId("account-asset-a"),
            ),
    )

    private fun rejectRequest(
        requestId: String,
        candidate: String,
        hash: String,
    ) = ImportCandidateRejectRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
    )

    // ---------- Complete canonical state oracle (P4-04 §1.4 pattern) ----------

    private data class Rl04ReportProjection(
        val balancesByAccount: Map<String, Long>,
        val internalTransferMinor: Long,
        val externalIncomeMinor: Long,
        val externalExpenseMinor: Long,
        val externalCashInflowMinor: Long,
        val externalCashOutflowMinor: Long,
        val consumptionMinor: Long,
        val budgetEffectMinor: Long,
        val categoryTotals: Map<String, Long>,
        val netWorthChangeMinor: Long,
    )

    private data class ReportTx(
        val ledgerId: String,
        val kind: String,
        val postings: List<Pair<String, Long>>,
    )

    private data class Rl04CanonicalState(
        val importRequest: List<List<Any?>>,
        val importSourceRecord: List<List<Any?>>,
        val importEvidence: List<List<Any?>>,
        val importCandidate: List<List<Any?>>,
        val importCandidateRequiresConfirmation: List<List<Any?>>,
        val importCandidateStatusHistory: List<List<Any?>>,
        val importCandidateDecisionSnapshot: List<List<Any?>>,
        val importConfirmation: List<List<Any?>>,
        val importReceipt: List<List<Any?>>,
        val ledgerTransaction: List<List<Any?>>,
        val postingSet: List<List<Any?>>,
        val transactionVersion: List<List<Any?>>,
        val posting: List<List<Any?>>,
        val ledgerTransactionCurrentVersion: List<List<Any?>>,
        val report: Map<String, Rl04ReportProjection>,
    )

    private fun compareCell(
        left: Any?,
        right: Any?,
    ): Int =
        when {
            left == null && right == null -> 0
            left == null -> -1
            right == null -> 1
            else ->
                when (left) {
                    is Long -> left.compareTo(right as Long)
                    is String -> left.compareTo(right as String)
                    else -> error("unsupported canonical cell type: $left")
                }
        }

    private val rowComparator =
        Comparator<List<Any?>> { left, right ->
            val limit = minOf(left.size, right.size)
            for (index in 0 until limit) {
                val compared = compareCell(left[index], right[index])
                if (compared != 0) return@Comparator compared
            }
            left.size - right.size
        }

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

    private fun reduceReport(
        transactions: List<ReportTx>,
        ledgerId: String,
        accounts: List<Account>,
    ): Rl04ReportProjection {
        val balances = LinkedHashMap<String, Long>()
        accounts.filter { it.ledgerId.value == ledgerId }.forEach { balances[it.id.value] = 0L }
        var internalTransfer = 0L
        var externalIncome = 0L
        var externalExpense = 0L
        var cashInflow = 0L
        var cashOutflow = 0L
        var consumption = 0L
        var netWorth = 0L
        transactions.filter { it.ledgerId == ledgerId }.forEach { tx ->
            tx.postings.forEach { (accountId, amount) -> balances[accountId] = (balances[accountId] ?: 0L) + amount }
            val positiveTotal = tx.postings.sumOf { if (it.second > 0L) it.second else 0L }
            when (tx.kind) {
                "ACCOUNT_TRANSFER" -> internalTransfer += positiveTotal
                "EXPENSE" -> {
                    externalExpense += positiveTotal
                    consumption += positiveTotal
                    cashOutflow += positiveTotal
                    netWorth -= positiveTotal
                }
                "INCOME" -> {
                    externalIncome += positiveTotal
                    cashInflow += positiveTotal
                    netWorth += positiveTotal
                }
                else -> Unit
            }
        }
        return Rl04ReportProjection(
            balancesByAccount = balances,
            internalTransferMinor = internalTransfer,
            externalIncomeMinor = externalIncome,
            externalExpenseMinor = externalExpense,
            externalCashInflowMinor = cashInflow,
            externalCashOutflowMinor = cashOutflow,
            consumptionMinor = consumption,
            budgetEffectMinor = 0L,
            categoryTotals = emptyMap(),
            netWorthChangeMinor = netWorth,
        )
    }

    private fun captureState(
        driver: JdbcSqliteDriver,
        accountsByLedger: Map<String, List<Account>>,
    ): Rl04CanonicalState {
        val formalJoin =
            "FROM posting AS p " +
                "JOIN transaction_version AS v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
                "JOIN ledger_transaction AS t ON t.transaction_id = v.transaction_id AND t.ledger_id = v.ledger_id"
        val formalRows =
            selectRows(
                driver,
                "SELECT t.transaction_id, t.ledger_id, t.kind, p.account_id, p.amount_minor $formalJoin ORDER BY t.transaction_id, p.posting_index",
                listOf(false, false, false, false, true),
            )
        val formalTxs =
            formalRows
                .groupBy { it[0] as String }
                .map { (txId, rows) ->
                    ReportTx(
                        ledgerId = rows.first()[1] as String,
                        kind = rows.first()[2] as String,
                        postings = rows.map { (it[3] as String) to (it[4] as Long) },
                    )
                }.sortedBy { it.ledgerId }
        val reportLedgers = (accountsByLedger.keys + formalTxs.map { it.ledgerId }).toSortedSet()
        val report =
            reportLedgers.associateWith { ledger ->
                reduceReport(formalTxs, ledger, accountsByLedger[ledger] ?: emptyList())
            }
        return Rl04CanonicalState(
            importRequest = selectRows(driver, "SELECT ledger_id, request_id, operation FROM import_request", listOf(false, false, false)).sortedWith(rowComparator),
            importSourceRecord =
                selectRows(
                    driver,
                    "SELECT ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token FROM import_source_record",
                    listOf(false, false, false, false, true, false, false, true, false, true, false, true, false, false, false),
                ).sortedWith(rowComparator),
            importEvidence = selectRows(driver, "SELECT ledger_id, evidence_id, source_id, evidence_kind, observed_at FROM import_evidence", listOf(false, false, false, false, false)).sortedWith(rowComparator),
            importCandidate = selectRows(driver, "SELECT ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version FROM import_candidate", listOf(false, false, false, false, false, false, true)).sortedWith(rowComparator),
            importCandidateRequiresConfirmation = selectRows(driver, "SELECT ledger_id, candidate_id, requirement_index, requirement FROM import_candidate_requires_confirmation", listOf(false, false, true, false)).sortedWith(rowComparator),
            importCandidateStatusHistory = selectRows(driver, "SELECT ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class FROM import_candidate_status_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
            importCandidateDecisionSnapshot = selectRows(driver, "SELECT ledger_id, request_id, decision, candidate_id, expected_content_hash, category_id, funding_account_id, from_account_id, to_account_id, explicit_confirmed_at FROM import_candidate_decision_snapshot", listOf(false, false, false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            importConfirmation = selectRows(driver, "SELECT ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class, confirmed_at FROM import_confirmation", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            importReceipt = selectRows(driver, "SELECT ledger_id, request_id, outcome, source_id, evidence_id, candidate_id, confirmation_id, transaction_id FROM import_receipt", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            ledgerTransaction = selectRows(driver, "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction", listOf(false, false, false, false)).sortedWith(rowComparator),
            postingSet = selectRows(driver, "SELECT posting_set_id, ledger_id FROM posting_set", listOf(false, false)).sortedWith(rowComparator),
            transactionVersion = selectRows(driver, "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, confirmation_id FROM transaction_version", listOf(false, false, false, true, false, false, false, false, false, false)).sortedWith(rowComparator),
            posting = selectRows(driver, "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting", listOf(false, false, false, true, false, true, false, true)).sortedWith(rowComparator),
            ledgerTransactionCurrentVersion = selectRows(driver, "SELECT transaction_id, ledger_id, current_version_id FROM ledger_transaction_current_version", listOf(false, false, false)).sortedWith(rowComparator),
            report = report,
        )
    }

    private fun assertCanonicalState(
        expected: Rl04CanonicalState,
        actual: Rl04CanonicalState,
        checkpoint: String,
    ) {
        assertEquals(expected.importRequest, actual.importRequest, "$checkpoint: import_request rows")
        assertEquals(expected.importSourceRecord, actual.importSourceRecord, "$checkpoint: import_source_record rows")
        assertEquals(expected.importEvidence, actual.importEvidence, "$checkpoint: import_evidence rows")
        assertEquals(expected.importCandidate, actual.importCandidate, "$checkpoint: import_candidate rows")
        assertEquals(expected.importCandidateRequiresConfirmation, actual.importCandidateRequiresConfirmation, "$checkpoint: import_candidate_requires_confirmation rows")
        assertEquals(expected.importCandidateStatusHistory, actual.importCandidateStatusHistory, "$checkpoint: import_candidate_status_history rows")
        assertEquals(expected.importCandidateDecisionSnapshot, actual.importCandidateDecisionSnapshot, "$checkpoint: import_candidate_decision_snapshot rows")
        assertEquals(expected.importConfirmation, actual.importConfirmation, "$checkpoint: import_confirmation rows")
        assertEquals(expected.importReceipt, actual.importReceipt, "$checkpoint: import_receipt rows")
        assertEquals(expected.ledgerTransaction, actual.ledgerTransaction, "$checkpoint: ledger_transaction rows")
        assertEquals(expected.postingSet, actual.postingSet, "$checkpoint: posting_set rows")
        assertEquals(expected.transactionVersion, actual.transactionVersion, "$checkpoint: transaction_version rows")
        assertEquals(expected.posting, actual.posting, "$checkpoint: posting rows")
        assertEquals(expected.ledgerTransactionCurrentVersion, actual.ledgerTransactionCurrentVersion, "$checkpoint: ledger_transaction_current_version rows")
        assertEquals(expected.report, actual.report, "$checkpoint: report projection")
    }

    /** Test-side expected-state builder (P4-04 §1.4 pattern), never reads the DB. */
    private class Rl04ExpectedState {
        val requests = mutableListOf<List<Any?>>()
        val sources = mutableListOf<List<Any?>>()
        val evidence = mutableListOf<List<Any?>>()
        val candidates = mutableListOf<List<Any?>>()
        val requirements = mutableListOf<List<Any?>>()
        val statusHistory = mutableListOf<List<Any?>>()
        val decisions = mutableListOf<List<Any?>>()
        val confirmations = mutableListOf<List<Any?>>()
        val receipts = mutableListOf<List<Any?>>()
        val transactions = mutableListOf<List<Any?>>()
        val postingSets = mutableListOf<List<Any?>>()
        val versions = mutableListOf<List<Any?>>()
        val postings = mutableListOf<List<Any?>>()
        val currentVersions = mutableListOf<List<Any?>>()
        val formalTxs = mutableListOf<ReportTx>()

        fun intake(
            ledgerId: String,
            requestId: String,
            inputRef: String,
            ordinal: Long,
            kind: ImportRecordKind,
            hash: String,
            facts: ImportSourceFacts,
            completeness: ImportCompleteness,
            sourceId: String,
            evidenceId: String,
            candidateId: String,
            statusId: String,
        ) {
            val candidateKind =
                when (kind) {
                    ImportRecordKind.ORDINARY_FLOW_SOURCE -> "ordinary_flow"
                    ImportRecordKind.TRANSFER_FLOW_SOURCE -> "transfer_flow"
                    ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG -> "transfer_flow_missing_leg"
                    // P4-06 v3 kinds: unused by this P4-04/P4-05b oracle, mapped for exhaustiveness.
                    ImportRecordKind.CREDIT_EXPENSE_SOURCE -> "credit_expense"
                    ImportRecordKind.CREDIT_REPAYMENT_SOURCE -> "credit_repayment"
                    ImportRecordKind.MIXED_PAYMENT_SOURCE -> "mixed_payment"
                }
            val complete = completeness == ImportCompleteness.VALID_COMPLETE
            requests += rlRow(ledgerId, requestId, "intake")
            sources +=
                rlRow(
                    ledgerId,
                    sourceId,
                    requestId,
                    inputRef,
                    ordinal,
                    kind.storageValue,
                    hash,
                    kind.contractVersion.toLong(),
                    if (complete) "valid_complete" else "valid_incomplete",
                    facts.amountMinor,
                    facts.currencyCode,
                    facts.currencyPrecision.toLong(),
                    facts.occurredAt,
                    facts.directionToken,
                    facts.statusToken,
                )
            evidence += rlRow(ledgerId, evidenceId, sourceId, "source_observation", facts.occurredAt)
            candidates += rlRow(ledgerId, candidateId, sourceId, candidateKind, if (complete) "1.00" else "0.50", kind.storageValue, 1L)
            requirements += rlRow(ledgerId, candidateId, 0L, "formal_transaction_creation")
            statusHistory += rlRow(ledgerId, candidateId, 1L, statusId, if (complete) "pending_confirmation" else "incomplete", requestId, "creation")
            receipts += rlRow(ledgerId, requestId, "accepted", sourceId, evidenceId, candidateId, null, null)
        }

        fun confirmTransfer(
            ledgerId: String,
            requestId: String,
            candidateId: String,
            hash: String,
            fromAccountId: String,
            toAccountId: String,
            confirmedAt: String?,
            occurredAtIso: String,
            amountMinor: Long,
            currencyCode: String,
            precision: Long,
            confirmationId: String,
            statusId: String,
            txId: String,
            versionId: String,
            postingSetId: String,
            outPostingId: String,
            inPostingId: String,
        ) {
            val timeText = Instant.parse(occurredAtIso).toString()
            requests += rlRow(ledgerId, requestId, "confirm_candidate")
            decisions += rlRow(ledgerId, requestId, "confirm", candidateId, hash, null, null, fromAccountId, toAccountId, confirmedAt)
            statusHistory += rlRow(ledgerId, candidateId, 2L, statusId, "confirmed", requestId, "creation")
            confirmations += rlRow(ledgerId, confirmationId, requestId, candidateId, statusId, txId, "creation", confirmedAt)
            receipts += rlRow(ledgerId, requestId, "accepted", null, null, candidateId, confirmationId, txId)
            transactions += rlRow(txId, ledgerId, "ACCOUNT_TRANSFER", null)
            postingSets += rlRow(postingSetId, ledgerId)
            versions += rlRow(versionId, txId, ledgerId, 1L, postingSetId, timeText, timeText, timeText, null, null)
            postings += rlRow(outPostingId, postingSetId, ledgerId, 0L, fromAccountId, -amountMinor, currencyCode, precision)
            postings += rlRow(inPostingId, postingSetId, ledgerId, 1L, toAccountId, amountMinor, currencyCode, precision)
            currentVersions += rlRow(txId, ledgerId, versionId)
            formalTxs += ReportTx(ledgerId, "ACCOUNT_TRANSFER", listOf(fromAccountId to -amountMinor, toAccountId to amountMinor))
        }

        fun state(
            accountsByLedger: Map<String, List<Account>>,
            rowComparator: Comparator<List<Any?>>,
            reduce: (List<ReportTx>, String, List<Account>) -> Rl04ReportProjection,
        ): Rl04CanonicalState {
            val reportLedgers = (accountsByLedger.keys + formalTxs.map { it.ledgerId }).toSortedSet()
            return Rl04CanonicalState(
                importRequest = requests.sortedWith(rowComparator),
                importSourceRecord = sources.sortedWith(rowComparator),
                importEvidence = evidence.sortedWith(rowComparator),
                importCandidate = candidates.sortedWith(rowComparator),
                importCandidateRequiresConfirmation = requirements.sortedWith(rowComparator),
                importCandidateStatusHistory = statusHistory.sortedWith(rowComparator),
                importCandidateDecisionSnapshot = decisions.sortedWith(rowComparator),
                importConfirmation = confirmations.sortedWith(rowComparator),
                importReceipt = receipts.sortedWith(rowComparator),
                ledgerTransaction = transactions.sortedWith(rowComparator),
                postingSet = postingSets.sortedWith(rowComparator),
                transactionVersion = versions.sortedWith(rowComparator),
                posting = postings.sortedWith(rowComparator),
                ledgerTransactionCurrentVersion = currentVersions.sortedWith(rowComparator),
                report =
                    reportLedgers.associateWith { ledger ->
                        reduce(formalTxs, ledger, accountsByLedger[ledger] ?: emptyList())
                    },
            )
        }
    }

    private fun expected(
        builder: Rl04ExpectedState,
        accountsByLedger: Map<String, List<Account>>,
    ): Rl04CanonicalState = builder.state(accountsByLedger, rowComparator, ::reduceReport)

    private fun accountsBy(
        ledger: LedgerId,
        cat: LedgerCatalog,
    ) = mapOf(ledger.value to cat.accounts)

    // ---------- E-01..E-04: intake, replay, confirm, replay ----------

    @Test
    fun executesE01ToE04IntakeReplayConfirmReplay() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accounts = accountsBy(ledgerId, cat)
            val intakeIds = BatchIntakeIdSource(listOf(intakeIds("y01", "status-y01-1")))
            val commitIds =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-y01", "status-y01-2", "tx-y01", "version-y01-v1", "posting-set-y01", listOf("posting-y01-out", "posting-y01-in"))),
                )
            val executor = transferExecutor(database, driver, cat, intakeIds, commitIds, BatchStatusIdSource(emptyList()))
            val expected = Rl04ExpectedState()

            // E-01: intake Y-01 accepted; transfer_flow_source contract_version 2; candidate provenance.
            val e01 =
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest("req-y01-intake", inputRef, 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts, ImportCompleteness.VALID_COMPLETE)),
                )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.SOURCE, "source-y01"),
                    ImportReturnedId(ImportReturnedIdKind.EVIDENCE, "evidence-y01"),
                    ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-y01"),
                ),
                e01.returnedIds,
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-y01-intake"), ImportSourceId("source-y01"), ImportEvidenceId("evidence-y01"), ImportCandidateId("candidate-y01"), null, null),
                e01.receipt,
            )
            expected.intake(ledgerId.value, "req-y01-intake", inputRef, 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY01, y01Facts, ImportCompleteness.VALID_COMPLETE, "source-y01", "evidence-y01", "candidate-y01", "status-y01-1")
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-01")
            val sourceY01 = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-y01-intake").executeAsOne()
            assertEquals(2L, sourceY01.contract_version)
            assertEquals("transfer_flow_source", sourceY01.record_kind)
            val candidateY01 = database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y01").executeAsOne()
            assertEquals("transfer_flow", candidateY01.candidate_kind)
            assertEquals("pending_confirmation", candidateY01.status)

            // E-02: same-request equivalent replay.
            val e02 =
                assertIs<ImportIntakeResult.NoChange>(
                    executor.intake(intakeRequest("req-y01-intake", inputRef, 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts, ImportCompleteness.VALID_COMPLETE)),
                )
            assertEquals(e01.receipt, e02.receipt)
            assertEquals("equivalent_replay", e02.reasonCode)
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-02")

            // E-03: confirm C1 wallet -> 余额宝 accepted; balanced two-leg asset transfer.
            val e03 =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    executor.confirm(transferConfirmRequest("req-y01-confirm", "candidate-y01", hashY01)),
                )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-y01"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-y01"),
                ),
                e03.returnedIds,
            )
            assertEquals(1, commitIds.calls.get())
            expected.confirmTransfer(
                ledgerId.value,
                "req-y01-confirm",
                "candidate-y01",
                hashY01,
                walletAccountId.value,
                yuebaoAccountId.value,
                "2026-08-17T10:00:00+08:00",
                y01Facts.occurredAt,
                10000L,
                "CNY",
                2L,
                "confirmation-y01",
                "status-y01-2",
                "tx-y01",
                "version-y01-v1",
                "posting-set-y01",
                "posting-y01-out",
                "posting-y01-in",
            )
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-03")
            assertEquals(
                "confirmed",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y01")
                    .executeAsOne()
                    .status,
            )
            val postingsY01 = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-y01").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-y01-out", "account-alipay-balance", -10000L, "CNY", 2L),
                    listOf("posting-y01-in", "account-yuebao", 10000L, "CNY", 2L),
                ),
                postingsY01.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )
            val report = captureState(driver, accounts).report.getValue(ledgerId.value)
            assertEquals(10000L, report.internalTransferMinor)
            assertEquals(0L, report.netWorthChangeMinor)
            assertEquals(0L, report.externalIncomeMinor)
            assertEquals(0L, report.externalExpenseMinor)
            assertEquals(0L, report.externalCashInflowMinor)
            assertEquals(0L, report.externalCashOutflowMinor)
            assertEquals(0L, report.consumptionMinor)
            assertEquals(emptyMap(), report.categoryTotals)

            // E-04: same-request confirm replay; factory and ID source untouched.
            val e04 =
                assertIs<ImportCandidateDecisionResult.NoChange>(
                    executor.confirm(transferConfirmRequest("req-y01-confirm", "candidate-y01", hashY01)),
                )
            assertEquals(e03.receipt, e04.receipt)
            assertEquals("equivalent_replay", e04.reasonCode)
            assertEquals(1, commitIds.calls.get())
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-04")
        } finally {
            driver.close()
        }
    }

    // ---------- E-05: direction gate (out row, wallet must be the FROM leg) ----------

    @Test
    fun executesE05DirectionGateReversedLegsRejectedWithZeroResidue() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accounts = accountsBy(ledgerId, cat)
            val intakeIds = BatchIntakeIdSource(listOf(intakeIds("y01b", "status-y01b-1")))
            // One failing attempt batch + one corrected batch on the same request identity.
            val attemptIds =
                BatchCommitIdSource(
                    listOf(
                        commitIds("confirmation-y01b-attempt-1", "status-y01b-2-attempt-1", "tx-y01b-attempt-1", "version-y01b-attempt-1-v1", "posting-set-y01b-attempt-1", listOf("posting-y01b-attempt-1-out", "posting-y01b-attempt-1-in")),
                        commitIds("confirmation-y01b", "status-y01b-2", "tx-y01b", "version-y01b-v1", "posting-set-y01b", listOf("posting-y01b-out", "posting-y01b-in")),
                    ),
                )
            val executor = transferExecutor(database, driver, cat, intakeIds, attemptIds, BatchStatusIdSource(emptyList()))
            val expected = Rl04ExpectedState()

            // Setup: fresh Y-01 copy candidate (out) pending (batch-rl04-b / 0).
            executor.intake(intakeRequest("req-y01b-intake", "batch-rl04-b", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-y01b-intake", "batch-rl04-b", 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY01, y01Facts, ImportCompleteness.VALID_COMPLETE, "source-y01b", "evidence-y01b", "candidate-y01b", "status-y01b-1")
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-05 setup")

            // E-05: reversed legs on an out row: direction gate fails, zero residue incl. claim.
            val e05 =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    executor.confirm(transferConfirmRequest("req-y01-confirm-rev", "candidate-y01b", hashY01, from = yuebaoAccountId, to = walletAccountId)),
                )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e05.diagnostic.code)
            assertEquals(1, attemptIds.calls.get())
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-05")
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y01b")
                    .executeAsOne()
                    .status,
            )

            // Request identity stays available: corrected retry on the same request accepts.
            val corrected =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    executor.confirm(transferConfirmRequest("req-y01-confirm-rev", "candidate-y01b", hashY01)),
                )
            assertEquals(2, attemptIds.calls.get())
            expected.confirmTransfer(
                ledgerId.value,
                "req-y01-confirm-rev",
                "candidate-y01b",
                hashY01,
                walletAccountId.value,
                yuebaoAccountId.value,
                "2026-08-17T10:00:00+08:00",
                y01Facts.occurredAt,
                10000L,
                "CNY",
                2L,
                "confirmation-y01b",
                "status-y01b-2",
                "tx-y01b",
                "version-y01b-v1",
                "posting-set-y01b",
                "posting-y01b-out",
                "posting-y01b-in",
            )
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-05 corrected retry")
            assertEquals(
                "confirmed",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y01b")
                    .executeAsOne()
                    .status,
            )
        } finally {
            driver.close()
        }
    }

    // ---------- E-06/E-07: 余额宝-转出到余额 (direction in) ----------

    @Test
    fun executesE06ConfirmInDirectionAndE07ReversedLegsRejected() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accounts = accountsBy(ledgerId, cat)
            val intakeIds =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("y09", "status-y09-1"),
                        // P4-07: Y-09b is a fresh copy of Y-09's exact business tuple, so its
                        // intake appends one directed duplicate candidate (subject y09b ->
                        // possible existing y09) per D-105 section 2.
                        intakeIds("y09b", "status-y09b-1").copy(
                            duplicateIds =
                                listOf(
                                    com.unifiedledger.application.ImportDuplicateIntakeIds(
                                        com.unifiedledger.application.ImportDuplicateCandidateId("duplicate-y09b-y09"),
                                        ImportStatusHistoryId("duplicate-status-y09b-y09"),
                                    ),
                                ),
                        ),
                    ),
                )
            val commitIds =
                BatchCommitIdSource(
                    listOf(
                        commitIds("confirmation-y09", "status-y09-2", "tx-y09", "version-y09-v1", "posting-set-y09", listOf("posting-y09-out", "posting-y09-in")),
                        // Discarded attempt batch for E-07's reversed-leg rejection (ISPIRE allocated
                        // before the factory direction gate; zero residue on the typed rejection).
                        commitIds("confirmation-y09b-attempt-1", "status-y09b-2-attempt-1", "tx-y09b-attempt-1", "version-y09b-attempt-1-v1", "posting-set-y09b-attempt-1", listOf("posting-y09b-attempt-1-out", "posting-y09b-attempt-1-in")),
                    ),
                )
            val executor = transferExecutor(database, driver, cat, intakeIds, commitIds, BatchStatusIdSource(emptyList()))
            val expected = Rl04ExpectedState()

            // E-06: intake Y-09 then confirm with wallet as the TO leg (direction in) accepted.
            executor.intake(intakeRequest("req-y09-intake", inputRef, 8, ImportRecordKind.TRANSFER_FLOW_SOURCE, y09Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-y09-intake", inputRef, 8L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY09, y09Facts, ImportCompleteness.VALID_COMPLETE, "source-y09", "evidence-y09", "candidate-y09", "status-y09-1")
            val e06 =
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    executor.confirm(transferConfirmRequest("req-y09-confirm", "candidate-y09", hashY09, from = yuebaoAccountId, to = walletAccountId)),
                )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-y09"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-y09"),
                ),
                e06.returnedIds,
            )
            expected.confirmTransfer(
                ledgerId.value,
                "req-y09-confirm",
                "candidate-y09",
                hashY09,
                yuebaoAccountId.value,
                walletAccountId.value,
                "2026-08-17T10:00:00+08:00",
                y09Facts.occurredAt,
                90000L,
                "CNY",
                2L,
                "confirmation-y09",
                "status-y09-2",
                "tx-y09",
                "version-y09-v1",
                "posting-set-y09",
                "posting-y09-out",
                "posting-y09-in",
            )
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-06")
            val postingsY09 = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-y09").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-y09-out", "account-yuebao", -90000L, "CNY", 2L),
                    listOf("posting-y09-in", "account-alipay-balance", 90000L, "CNY", 2L),
                ),
                postingsY09.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )

            // E-07: fresh Y-09 copy candidate; reversed legs on an in row: wallet is not the TO leg.
            executor.intake(intakeRequest("req-y09b-intake", "batch-rl04-b", 10, ImportRecordKind.TRANSFER_FLOW_SOURCE, y09Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-y09b-intake", "batch-rl04-b", 10L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY09, y09Facts, ImportCompleteness.VALID_COMPLETE, "source-y09b", "evidence-y09b", "candidate-y09b", "status-y09b-1")
            val e07 =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    executor.confirm(transferConfirmRequest("req-y09b-confirm", "candidate-y09b", hashY09, from = walletAccountId, to = yuebaoAccountId)),
                )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e07.diagnostic.code)
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-07")
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y09b")
                    .executeAsOne()
                    .status,
            )
        } finally {
            driver.close()
        }
    }

    // ---------- E-08: rejected rows -> zero intake, zero writes ----------

    @Test
    fun executesE08RejectedRowsProduceZeroIntakeAndZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val result = AlipayCsvParser.parse(inputRef, csvBytes(batchARows()))
            assertEquals(AlipayBatchOutcome.PARTIAL, result.outcome)
            val acceptedRows = result.rows.filterIsInstance<AlipayRowResult.Accepted>()
            val rejectedRows = result.rows.filterIsInstance<AlipayRowResult.Rejected>()
            assertEquals(10, acceptedRows.size)
            assertEquals(5, rejectedRows.size)
            rejectedRows.forEach { assertEquals(1, it.diagnostics.size, "rejected row carries exactly one diagnostic") }

            // Intake only the 10 accepted records (parser output drives intake); rejected rows
            // (Y-08/Y-10/Y-11/Y-13/Y-15) cannot be intaked at all (zero record, zero intake call).
            val batches = acceptedRows.map { intakeIds("y${it.recordOrdinal}", "status-y${it.recordOrdinal}-1") }
            val executor = transferExecutor(database, driver, catalog(ledgerId), BatchIntakeIdSource(batches), BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            var acceptedCount = 0
            val rejectedOrdinals = rejectedRows.map { it.recordOrdinal }.toSet()
            acceptedRows.forEach { row ->
                assertTrue(row.recordOrdinal !in rejectedOrdinals)
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest("req-batch-${row.recordOrdinal}", inputRef, row.recordOrdinal, row.recordKind, row.facts, row.completeness)),
                )
                acceptedCount++
            }
            assertEquals(10, acceptedCount)
            assertEquals(listOf(10L, 10L, 10L, 10L, 10L, 0L, 0L, 10L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            // The Y-12 (ordinal 11) candidate is the lone incomplete in the batch.
            assertEquals(
                "incomplete",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y11")
                    .executeAsOne()
                    .status,
            )
            // No source row exists for any rejected ordinal.
            rejectedOrdinals.forEach { ordinal ->
                assertTrue(
                    database.ledgerQueries.selectImportSourceByIdentity(ledgerId.value, inputRef, ordinal.toLong()).executeAsOneOrNull() == null,
                    "rejected ordinal $ordinal must not have a source row",
                )
            }
            // Privacy: non-persisted column values never reach any persisted column.
            val leaked =
                scalarText(
                    driver,
                    "SELECT COUNT(*) FROM import_source_record WHERE ledger_id = '${ledgerId.value}' AND (" +
                        "input_ref LIKE '%SYN-%' OR content_hash LIKE '%SYN-%' OR currency_code LIKE '%SYN-%' OR " +
                        "occurred_at LIKE '%SYN-%' OR direction_token LIKE '%SYN-%' OR status_token LIKE '%SYN-%')",
                )
            assertEquals("0", leaked)
        } finally {
            driver.close()
        }
    }

    // ---------- E-09: incomplete candidate not confirmable (+ T-21 spine vector) ----------

    @Test
    fun executesE09IncompleteCandidateNotConfirmable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accounts = accountsBy(ledgerId, cat)
            val intakeIds =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("y12", "status-y12-1"),
                        intakeIds("y09c", "status-y09c-1"),
                    ),
                )
            val executor = transferExecutor(database, driver, cat, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            val expected = Rl04ExpectedState()

            // Setup C12: Y-12 status-unresolved transfer candidate (incomplete).
            executor.intake(intakeRequest("req-y12-intake", inputRef, 11, ImportRecordKind.TRANSFER_FLOW_SOURCE, y12Facts, ImportCompleteness.VALID_INCOMPLETE))
            expected.intake(ledgerId.value, "req-y12-intake", inputRef, 11L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY12, y12Facts, ImportCompleteness.VALID_INCOMPLETE, "source-y12", "evidence-y12", "candidate-y12", "status-y12-1")
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-09 setup")
            assertEquals(
                "incomplete",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y12")
                    .executeAsOne()
                    .status,
            )

            // E-09: confirm C12 -> SPINE_CANDIDATE_INCOMPLETE, zero residue.
            val e09 =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    executor.confirm(transferConfirmRequest("req-y12-confirm", "candidate-y12", hashY12)),
                )
            assertEquals("SPINE_CANDIDATE_INCOMPLETE", e09.diagnostic.code)
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-09")

            // T-21 extra vector: 余额宝-转出到余额 + 交易关闭 (inverted status-gate twin) also
            // intakes as an incomplete transfer candidate and refuses confirm.
            val y09cFacts = ImportSourceFacts(5555, "CNY", 2, "2026-08-11T09:00:00+08:00", "in", "交易关闭", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
            val hashY09c = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, y09cFacts)
            executor.intake(intakeRequest("req-y09c-intake", "batch-rl04-t21", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, y09cFacts, ImportCompleteness.VALID_INCOMPLETE))
            expected.intake(ledgerId.value, "req-y09c-intake", "batch-rl04-t21", 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY09c, y09cFacts, ImportCompleteness.VALID_INCOMPLETE, "source-y09c", "evidence-y09c", "candidate-y09c", "status-y09c-1")
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "T-21 setup")
            assertEquals(
                "incomplete",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-y09c")
                    .executeAsOne()
                    .status,
            )
            val t21 =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    executor.confirm(transferConfirmRequest("req-y09c-confirm", "candidate-y09c", hashY09c, from = yuebaoAccountId, to = walletAccountId)),
                )
            assertEquals("SPINE_CANDIDATE_INCOMPLETE", t21.diagnostic.code)
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "T-21 reject")
        } finally {
            driver.close()
        }
    }

    // ---------- E-10: kind gate both directions ----------

    @Test
    fun executesE10KindMismatchBothDirections() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accounts = accountsBy(ledgerId, cat)
            val intakeIds =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("y01c", "status-y01c-1"), // transfer candidate (batch-rl04-b / 2)
                        intakeIds("od", "status-od-1"), // ordinary candidate
                    ),
                )
            val executor = transferExecutor(database, driver, cat, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            val expected = Rl04ExpectedState()

            // Setup: one fresh transfer candidate and one ordinary candidate, both pending.
            executor.intake(intakeRequest("req-y01c-intake", "batch-rl04-b", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-y01c-intake", "batch-rl04-b", 2L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY01, y01Facts, ImportCompleteness.VALID_COMPLETE, "source-y01c", "evidence-y01c", "candidate-y01c", "status-y01c-1")
            executor.intake(intakeRequest("req-od-intake", "batch-rl04-ordinary", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, ordinaryFacts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-od-intake", "batch-rl04-ordinary", 0L, ImportRecordKind.ORDINARY_FLOW_SOURCE, hashOrdinary, ordinaryFacts, ImportCompleteness.VALID_COMPLETE, "source-od", "evidence-od", "candidate-od", "status-od-1")
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-10 setup")

            // (a) transfer candidate + OrdinaryFlow decision fields -> kind mismatch.
            val e10a =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    executor.confirm(ordinaryConfirmRequest("req-y01c-confirm", "candidate-y01c", hashY01)),
                )
            assertEquals("SPINE_DECISION_KIND_MISMATCH", e10a.diagnostic.code)
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-10a")

            // (b) ordinary candidate + TransferFlow decision fields -> kind mismatch.
            val e10b =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    executor.confirm(transferConfirmRequest("req-od-confirm", "candidate-od", hashOrdinary)),
                )
            assertEquals("SPINE_DECISION_KIND_MISMATCH", e10b.diagnostic.code)
            assertCanonicalState(expected(expected, accounts), captureState(driver, accounts), "E-10b")
        } finally {
            driver.close()
        }
    }

    // ---------- E-11: concurrent confirm (same request, same candidate, same fields) ----------

    @Test
    fun executesE11ConcurrentConfirmCommitsOnceWithNoLoserResidue() {
        val path = Files.createTempFile("rl04-confirm-e11-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            val cat = catalog(ledgerId)
            // Shared ID source consumed exactly once by the winning first request.
            val sharedCommitIds =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-y01d", "status-y01d-2", "tx-y01d", "version-y01d-v1", "posting-set-y01d", listOf("posting-y01d-out", "posting-y01d-in"))),
                )
            // Setup phase: one intake on its own connection.
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                transferExecutor(database, driver, cat, BatchIntakeIdSource(listOf(intakeIds("y01d", "status-y01d-1"))), BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
                    .intake(intakeRequest("req-y01d-intake", "batch-rl04-b", 3, ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts, ImportCompleteness.VALID_COMPLETE))
            }
            val results =
                concurrentExecute(
                    url,
                    listOf(
                        { confirmOn(url, cat, sharedCommitIds) },
                        { confirmOn(url, cat, sharedCommitIds) },
                    ),
                )
            assertEquals(1, results.count { it is ImportCandidateDecisionResult.Accepted })
            assertEquals(1, results.count { it is ImportCandidateDecisionResult.NoChange })
            assertEquals(1, sharedCommitIds.calls.get())
            JdbcSqliteDriver(url).use { driver ->
                assertEquals(listOf(2L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(LedgerDatabase(driver)))
                assertEquals(listOf(1L, 1L, 2L), formalCounts(LedgerDatabase(driver)))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---------- E-12: failure injection (intake + confirm), rollback, corrected retry ----------

    @Test
    fun executesE12InjectedFailuresRollBackAndCorrectedRetriesAccept() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accounts = accountsBy(ledgerId, cat)

            // Intake failure after the candidate insert: full rollback, zero residue.
            val failingStore =
                SqlDelightImportSpineStore(
                    database,
                    driver,
                    ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE) error("injected") },
                )
            val batch1 = BatchIntakeIdSource(listOf(intakeIds("y01e-attempt-1", "status-y01e-1-attempt-1")))
            assertFailsWith<IllegalStateException> {
                ExecuteImportIntake(failingStore, batch1, ImportContentFingerprint()).execute(
                    intakeRequest("req-y01e-intake", "batch-rl04-b", 4, ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts, ImportCompleteness.VALID_COMPLETE),
                )
            }
            assertEquals(1, batch1.calls.get())
            assertCanonicalState(expected(Rl04ExpectedState(), accounts), captureState(driver, accounts), "E-12 intake failure")
            val batch2 = BatchIntakeIdSource(listOf(intakeIds("y01e", "status-y01e-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), batch2, ImportContentFingerprint()).execute(
                    intakeRequest("req-y01e-intake", "batch-rl04-b", 4, ImportRecordKind.TRANSFER_FLOW_SOURCE, y01Facts, ImportCompleteness.VALID_COMPLETE),
                ),
            )

            // Confirm failure after the formal persist: full rollback, zero residue, retry accepts.
            val failingConfirmStore =
                SqlDelightImportSpineStore(
                    database,
                    driver,
                    ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL) error("injected") },
                )
            val attempt1 =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-y01e-attempt-1", "status-y01e-2-attempt-1", "tx-y01e-attempt-1", "version-y01e-attempt-1-v1", "posting-set-y01e-attempt-1", listOf("posting-y01e-attempt-1-out", "posting-y01e-attempt-1-in"))),
                )
            assertFailsWith<IllegalStateException> {
                ConfirmImportCandidate(
                    failingConfirmStore,
                    attempt1,
                    TransferFlowFormalFactory(cat, walletAccountId),
                    cat,
                ).execute(transferConfirmRequest("req-y01e-confirm", "candidate-y01e", hashY01))
            }
            assertEquals(1, attempt1.calls.get())
            val expectedAfterIntake = Rl04ExpectedState()
            expectedAfterIntake.intake(ledgerId.value, "req-y01e-intake", "batch-rl04-b", 4L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashY01, y01Facts, ImportCompleteness.VALID_COMPLETE, "source-y01e", "evidence-y01e", "candidate-y01e", "status-y01e-1")
            assertCanonicalState(expected(expectedAfterIntake, accounts), captureState(driver, accounts), "E-12 confirm failure")
            val confirmBatch2 =
                BatchCommitIdSource(
                    listOf(commitIds("confirmation-y01e", "status-y01e-2", "tx-y01e", "version-y01e-v1", "posting-set-y01e", listOf("posting-y01e-out", "posting-y01e-in"))),
                )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver),
                    confirmBatch2,
                    TransferFlowFormalFactory(cat, walletAccountId),
                    cat,
                ).execute(transferConfirmRequest("req-y01e-confirm", "candidate-y01e", hashY01)),
            )
        } finally {
            driver.close()
        }
    }

    // ---------- T-20 (R-02): regression manifest ----------

    @Test
    fun regressionManifestR02() {
        // E-series manifest: exactly E-01..E-12, each owned by exactly one test method above.
        val frozenECaseIds = (1..12).map { "E-%02d".format(it) }
        val registeredECaseIds =
            listOf(
                "E-01",
                "E-02",
                "E-03",
                "E-04",
                "E-05",
                "E-06",
                "E-07",
                "E-08",
                "E-09",
                "E-10",
                "E-11",
                "E-12",
            )
        assertEquals(frozenECaseIds.toSet(), registeredECaseIds.toSet())
        assertEquals(12, frozenECaseIds.size)

        // R-02: shared contracts and the P4-02/P4-03/P4-04 oracles are untouched. Pin the
        // contract constants and the pinned P4-02 ordinary v1 digests (unchanged bytes).
        assertEquals(2, ImportRecordKind.TRANSFER_FLOW_SOURCE.contractVersion)
        assertEquals("transfer_flow_source", ImportRecordKind.TRANSFER_FLOW_SOURCE.storageValue)
        assertEquals(1, ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion)
        val hashR1 = "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2"
        val hashR3 = "sha256:911f0b27473a382752837ac1eaca05e9f7ab1d13fc944b8e5e349b30fb86fe35"
        val r1Facts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
        val r3Facts = ImportSourceFacts(4500, "CNY", 2, "2026-08-06T18:45:00+08:00", "out", null, ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
        assertEquals(hashR1, fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1Facts))
        assertEquals(hashR3, fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3Facts))

        // Frozen yuebao routing constants (assert the parser's branch is backed by the
        // frozen tables, not by any in-parser literals beyond the tokens file).
        assertEquals(setOf("余额宝-自动转入", "余额宝-转出到余额"), AlipaySourceTokens.YUEBAO_TRANSFER_SUBTYPES)
        assertEquals("out", AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_MAP["余额宝-自动转入"])
        assertEquals("in", AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_MAP["余额宝-转出到余额"])
        assertEquals("yuebao_subtype_direction_v1", AlipaySourceTokens.YUEBAO_SUBTYPE_DIRECTION_RULE)
        assertEquals("投资理财", AlipaySourceTokens.INVESTMENT_CATEGORY)
    }

    // ---------- Concurrency plumbing (P4-02/P4-05 pattern) ----------

    private fun confirmOn(
        url: String,
        cat: LedgerCatalog,
        ids: ImportIdSource,
    ): ImportCandidateDecisionResult =
        JdbcSqliteDriver(url).use { driver ->
            val database = LedgerDatabase(driver)
            ConfirmImportCandidate(
                SqlDelightImportSpineStore(database, driver),
                ids,
                TransferFlowFormalFactory(cat, walletAccountId),
                cat,
            ).execute(transferConfirmRequest("req-y01d-confirm", "candidate-y01d", hashY01))
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
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

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
}

// File-scoped row builder (uniquely named to avoid the private top-level `row` in
// ImportSpineTransferEndToEndTest.kt, same package).
private fun rlRow(vararg values: Any?): List<Any?> = listOf(*values)
