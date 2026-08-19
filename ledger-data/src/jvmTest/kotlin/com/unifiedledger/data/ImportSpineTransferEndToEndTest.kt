package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateDecisionSnapshot
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCandidateRejectRequest
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportIntakeSnapshot
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportResolvedSourceFacts
import com.unifiedledger.application.ImportReturnedId
import com.unifiedledger.application.ImportReturnedIdKind
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ImportStatusIdSource
import com.unifiedledger.application.RejectImportCandidate
import com.unifiedledger.application.TransferFlowFormalFactory
import com.unifiedledger.application.validateImportFormalBinding
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
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
 * P4-04 transfer formalization spine end-to-end oracle (frozen spec sections 1.3, 1.4
 * and 8: T-13..T-54 covering E-01..E-37, E-40..E-41 plus the application-layer,
 * hash/version and regression-manifest obligations).
 *
 * Every E operation freezes an independent pre-state and a complete expected post-state
 * built test-side from the section 1 fixtures ([P404ExpectedState]); the actual database
 * is projected read-only into [P404CanonicalState] (nine spine tables + five formal chain
 * tables + the independent report projection) and compared row-by-row, column-by-column.
 * Count/delta assertions only ever supplement the complete canonical comparison.
 */
class ImportSpineTransferEndToEndTest {

    // ---------- Frozen fixtures (spec section 1.1/1.2) ----------

    private val ledgerId = LedgerId("ledger-p404")
    private val otherLedgerId = LedgerId("ledger-p404-other")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val walletAccountId = AccountId("account-wallet-wechat")
    private val bankAccountId = AccountId("account-bank-a")
    private val bankUsdAccountId = AccountId("account-bank-usd")
    private val assetAccountId = AccountId("account-asset-a")
    private val fingerprint = ImportContentFingerprint()

    // Source records T1..T10 (synthetic facts; occurred_at pinned for T1, deterministic
    // distinct instants for the rest).
    private val t1Facts = ImportSourceFacts(10000, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "提现已到账")
    private val t2Facts = ImportSourceFacts(20000, "CNY", 2, "2026-08-02T09:00:00+08:00", "in", "支付成功")
    private val t3Facts = ImportSourceFacts(5000, "CNY", 2, "2026-08-03T10:15:00+08:00", "out", "支付成功")
    private val t4Facts = ImportSourceFacts(6600, "CNY", 2, "2026-08-04T11:30:00+08:00", "in", "已存入零钱")
    private val t6Facts = ImportSourceFacts(1000, "CNY", 2, "2026-08-06T12:00:00+08:00", "/", "提现已到账")
    private val t10Facts = ImportSourceFacts(1200, "CNY", 2, "2026-08-10T13:45:00+08:00", "out", "支付成功")
    private val t1PrimeFacts = t1Facts.copy(amountMinor = 10001)

    // Content hashes computed test-side from the frozen facts via the canonical digest
    // contract (spec section 4.1); never read back from the database under test.
    private val hashT1 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts)
    private val hashT1Prime = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, t1PrimeFacts)
    private val hashT1Ordinary = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, t1Facts)
    private val hashT2 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, t2Facts)
    private val hashT3 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t3Facts)
    private val hashT4 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t4Facts)
    private val hashT6 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, t6Facts)

    // Pinned P4-02 ordinary v1 digests (ImportSpineLifecycleEndToEndTest) used by T-49 to
    // prove ordinary v1 bytes are unchanged by the transfer contract extension.
    private val hashR1 = "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2"
    private val hashR3 = "sha256:911f0b27473a382752837ac1eaca05e9f7ab1d13fc944b8e5e349b30fb86fe35"
    private val r1Facts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled")
    private val r3Facts = ImportSourceFacts(4500, "CNY", 2, "2026-08-06T18:45:00+08:00", "out", null)

    private val t1OccurredInstant = Instant.parse("2026-08-01T12:30:00+08:00")

    // ---------- Case manifests (spec sections 1.3/8) ----------

    private val frozenECaseIds: List<String> =
        (1..37).map { "E-%02d".format(it) } + listOf("E-40", "E-41")

    // Each E case registers exactly once, in frozen order, at the test method that owns it.
    private val registeredECaseIds: List<String> = listOf(
        "E-01", "E-02", "E-03", "E-04", "E-05", "E-06", "E-07", "E-08", "E-09",
        "E-10", "E-11", "E-12", "E-13", "E-14", "E-15", "E-16",
        "E-17", "E-18", "E-19", "E-20", "E-21", "E-22", "E-23", "E-24", "E-25", "E-26", "E-27", "E-28",
        "E-29", "E-30",
        "E-31", "E-32",
        "E-33",
        "E-34",
        "E-35",
        "E-36",
        "E-37",
        "E-40",
        "E-41",
    )

    // Frozen B01..B13 case table (E-34, T-44): IDs, order and names.
    private val bindingMismatchManifest = listOf(
        "B01" to "reversed_legs",
        "B02" to "wrong_ledger",
        "B03" to "wrong_kind",
        "B04" to "wrong_amount",
        "B05" to "wrong_currency",
        "B06" to "wrong_precision",
        "B07" to "extra_posting",
        "B08" to "multiple_versions_current_v2",
        "B09" to "extra_posting_set",
        "B10" to "wrong_occurred_at",
        "B11" to "wrong_statistics_at",
        "B12" to "wrong_effective_at",
        "B13" to "non_null_note",
    )

    // Frozen S01..S09 case table (E-36, T-53).
    private data class ScaleVector(
        val caseId: String,
        val name: String,
        val amountMinor: Long,
        val sourceScale: Int,
        val expectedNormalizedMinor: Long?,
    )

    private val scaleVectors = listOf(
        ScaleVector("S01", "scale_0_up", 100L, 0, 10000L),
        ScaleVector("S02", "scale_1_up", 1000L, 1, 10000L),
        ScaleVector("S03", "scale_2_equal", 10000L, 2, 10000L),
        ScaleVector("S04", "scale_3_exact_down", 100000L, 3, 10000L),
        ScaleVector("S05", "scale_3_remainder", 100001L, 3, null),
        ScaleVector("S06", "scale_19_remainder", 1L, 19, null),
        ScaleVector("S07", "scale_up_overflow", Long.MAX_VALUE, 0, null),
        ScaleVector("S08", "negative_scale", 10000L, -1, null),
        ScaleVector("S09", "scale_gap_over_18", 1L, 21, null),
    )

    // Frozen I01..I07 case table (E-37, T-54).
    private val allocatedIdManifest = listOf(
        "I01" to "wrong_confirmation_id",
        "I02" to "wrong_status_history_id",
        "I03" to "wrong_transaction_id",
        "I04" to "wrong_version_id",
        "I05" to "wrong_posting_set_id",
        "I06" to "wrong_source_posting_id",
        "I07" to "wrong_destination_posting_id",
    )

    // ---------- Deterministic ID sources (P4-02 pattern) ----------

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

    // ---------- Catalog (spec section 1.1) ----------

    private fun catalog(ledgerId: LedgerId): LedgerCatalog = when (
        val result = LedgerCatalog.create(
            accounts = listOf(
                Account(walletAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(bankAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(bankUsdAccountId, ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true),
                Account(assetAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
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
        is DomainResult.Failure -> error("p404 test catalog failure: ${result.violation}")
    }

    // ---------- Executor (P4-02/P4-03 pattern; production transfer factory) ----------

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

        fun intake(request: ImportIntakeRequest): ImportIntakeResult =
            ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirm(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult =
            ConfirmImportCandidate(store, commitIds, factory).execute(request)

        fun confirmWith(
            ids: ImportIdSource,
            factory: ImportCandidateFormalFactory,
            request: ImportCandidateConfirmRequest,
        ): ImportCandidateDecisionResult =
            ConfirmImportCandidate(store, ids, factory).execute(request)

        fun reject(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult =
            RejectImportCandidate(store, statusIds).execute(request)
    }

    private fun transferExecutor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        catalog: LedgerCatalog,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
        statusIds: ImportStatusIdSource,
    ) = Executor(
        database, driver, catalog, intakeIds, commitIds, statusIds,
        TransferFlowFormalFactory(catalog, walletAccountId),
    )

    private fun intakeRequest(
        requestId: String,
        inputRef: String,
        ordinal: Int,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        completeness: ImportCompleteness,
        identityLedger: LedgerId = ledgerId,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(identityLedger, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = ordinal,
        recordKind = kind,
        facts = facts,
        completeness = completeness,
    )

    private fun transferConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        from: AccountId = walletAccountId,
        to: AccountId = bankAccountId,
        confirmedAt: String? = "2026-08-14T10:00:00+08:00",
        identityLedger: LedgerId = ledgerId,
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(identityLedger, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.TransferFlow(fromAccountId = from, toAccountId = to),
    )

    private fun rejectRequest(requestId: String, candidate: String, hash: String) =
        ImportCandidateRejectRequest(
            identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
            candidateId = ImportCandidateId(candidate),
            expectedContentHash = hash,
        )

    // ---------- Complete canonical state oracle (spec section 1.4) ----------

    private data class P404ReportProjection(
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

    /** A formal transaction reduced to the facts the independent report reducer needs. */
    private data class ReportTx(
        val ledgerId: String,
        val kind: String,
        val postings: List<Pair<String, Long>>,
    )

    /**
     * Immutable projection of the 14 frozen tables (nine spine owners + five formal chain
     * owners) plus the per-ledger report projection. Rows are canonical database values
     * (NULL stays null; integers are Long; no count-only shortcuts), sorted by each table's
     * primary key.
     */
    private data class P404CanonicalState(
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
        val report: Map<String, P404ReportProjection>,
    )

    private fun compareCell(left: Any?, right: Any?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> when (left) {
            is Long -> left.compareTo(right as Long)
            is String -> left.compareTo(right as String)
            else -> error("unsupported canonical cell type: $left")
        }
    }

    private val rowComparator = Comparator<List<Any?>> { left, right ->
        val limit = minOf(left.size, right.size)
        for (index in 0 until limit) {
            val compared = compareCell(left[index], right[index])
            if (compared != 0) return@Comparator compared
        }
        left.size - right.size
    }

    private fun selectRows(driver: JdbcSqliteDriver, sql: String, longColumns: List<Boolean>): List<List<Any?>> =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val rows = mutableListOf<List<Any?>>()
                while (cursor.next().value) {
                    rows += longColumns.mapIndexed { index, isLong ->
                        if (isLong) cursor.getLong(index) else cursor.getString(index)
                    }
                }
                app.cash.sqldelight.db.QueryResult.Value(rows.toList())
            },
            0,
        ).value

    /**
     * Independent report reducer (spec section 1.4 item 3): computed from postings and
     * transaction kinds only; never calls a production factory or report helper. A formal
     * transfer yields internalTransfer = principal with every other dimension at zero.
     */
    private fun reduceReport(transactions: List<ReportTx>, ledgerId: String, accounts: List<Account>): P404ReportProjection {
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
            tx.postings.forEach { (accountId, amount) ->
                balances[accountId] = (balances[accountId] ?: 0L) + amount
            }
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
        return P404ReportProjection(
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

    private fun captureState(driver: JdbcSqliteDriver, accountsByLedger: Map<String, List<Account>>): P404CanonicalState {
        val formalJoin = "FROM posting AS p " +
            "JOIN transaction_version AS v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
            "JOIN ledger_transaction AS t ON t.transaction_id = v.transaction_id AND t.ledger_id = v.ledger_id"
        val formalRows = selectRows(
            driver,
            "SELECT t.transaction_id, t.ledger_id, t.kind, p.account_id, p.amount_minor $formalJoin ORDER BY t.transaction_id, p.posting_index",
            listOf(false, false, false, false, true),
        )
        val formalTxs = formalRows
            .groupBy { it[0] as String }
            .map { (txId, rows) ->
                ReportTx(
                    ledgerId = rows.first()[1] as String,
                    kind = rows.first()[2] as String,
                    postings = rows.map { (it[3] as String) to (it[4] as Long) },
                )
            }
            .sortedBy { it.ledgerId }
        val reportLedgers = (accountsByLedger.keys + formalTxs.map { it.ledgerId }).toSortedSet()
        val report = reportLedgers.associateWith { ledger ->
            reduceReport(formalTxs, ledger, accountsByLedger[ledger] ?: emptyList())
        }
        return P404CanonicalState(
            importRequest = selectRows(driver, "SELECT ledger_id, request_id, operation FROM import_request", listOf(false, false, false)).sortedWith(rowComparator),
            importSourceRecord = selectRows(
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

    private fun assertCanonicalState(expected: P404CanonicalState, actual: P404CanonicalState, checkpoint: String) {
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

    /**
     * Test-side expected-state builder (spec section 1.4): every row below is constructed
     * from the frozen section 1 fixtures and operation contracts, never read back from the
     * database under test.
     */
    private class P404ExpectedState {
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
            val candidateKind = when (kind) {
                ImportRecordKind.ORDINARY_FLOW_SOURCE -> "ordinary_flow"
                ImportRecordKind.TRANSFER_FLOW_SOURCE -> "transfer_flow"
                ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG -> "transfer_flow_missing_leg"
            }
            val complete = completeness == ImportCompleteness.VALID_COMPLETE
            requests += row(ledgerId, requestId, "intake")
            sources += row(
                ledgerId, sourceId, requestId, inputRef, ordinal, kind.storageValue, hash,
                kind.contractVersion.toLong(), if (complete) "valid_complete" else "valid_incomplete",
                facts.amountMinor, facts.currencyCode, facts.currencyPrecision.toLong(),
                facts.occurredAt, facts.directionToken, facts.statusToken,
            )
            evidence += row(ledgerId, evidenceId, sourceId, "source_observation", facts.occurredAt)
            candidates += row(ledgerId, candidateId, sourceId, candidateKind, if (complete) "1.00" else "0.50", kind.storageValue, 1L)
            requirements += row(ledgerId, candidateId, 0L, "formal_transaction_creation")
            statusHistory += row(ledgerId, candidateId, 1L, statusId, if (complete) "pending_confirmation" else "incomplete", requestId, "creation")
            receipts += row(ledgerId, requestId, "accepted", sourceId, evidenceId, candidateId, null, null)
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
            requests += row(ledgerId, requestId, "confirm_candidate")
            decisions += row(ledgerId, requestId, "confirm", candidateId, hash, null, null, fromAccountId, toAccountId, confirmedAt)
            statusHistory += row(ledgerId, candidateId, 2L, statusId, "confirmed", requestId, "creation")
            confirmations += row(ledgerId, confirmationId, requestId, candidateId, statusId, txId, "creation", confirmedAt)
            receipts += row(ledgerId, requestId, "accepted", null, null, candidateId, confirmationId, txId)
            transactions += row(txId, ledgerId, "ACCOUNT_TRANSFER", null)
            postingSets += row(postingSetId, ledgerId)
            versions += row(versionId, txId, ledgerId, 1L, postingSetId, timeText, timeText, timeText, null, null)
            postings += row(outPostingId, postingSetId, ledgerId, 0L, fromAccountId, -amountMinor, currencyCode, precision)
            postings += row(inPostingId, postingSetId, ledgerId, 1L, toAccountId, amountMinor, currencyCode, precision)
            currentVersions += row(txId, ledgerId, versionId)
            formalTxs += ReportTx(ledgerId, "ACCOUNT_TRANSFER", listOf(fromAccountId to -amountMinor, toAccountId to amountMinor))
        }

        fun reject(ledgerId: String, requestId: String, candidateId: String, hash: String, statusId: String) {
            requests += row(ledgerId, requestId, "reject_candidate")
            decisions += row(ledgerId, requestId, "reject", candidateId, hash, null, null, null, null, null)
            statusHistory += row(ledgerId, candidateId, 2L, statusId, "rejected", requestId, "status_transition")
            receipts += row(ledgerId, requestId, "accepted", null, null, candidateId, null, null)
        }

        fun canonicalState(
            accountsByLedger: Map<String, List<Account>>,
            rowComparator: Comparator<List<Any?>>,
            reduce: (List<ReportTx>, String, List<Account>) -> P404ReportProjection,
        ): P404CanonicalState {
            val reportLedgers = (accountsByLedger.keys + formalTxs.map { it.ledgerId }).toSortedSet()
            return P404CanonicalState(
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
                report = reportLedgers.associateWith { ledger ->
                    reduce(formalTxs, ledger, accountsByLedger[ledger] ?: emptyList())
                },
            )
        }
    }

    private fun P404ExpectedState.state(accountsByLedger: Map<String, List<Account>>): P404CanonicalState =
        canonicalState(accountsByLedger, rowComparator, ::reduceReport)

    // ---------- T-13..T-21 (E-01..E-09): transfer intake ----------

    @Test
    fun executesE01ToE09TransferIntakesWithFrozenShapes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
            val intakeIds = BatchIntakeIdSource(
                listOf(
                    intakeIds("t1", "status-t1-1"),
                    intakeIds("t1o", "status-t1o-1"),
                    intakeIds("t2", "status-t2-1"),
                    intakeIds("t3", "status-t3-1"),
                    intakeIds("t4", "status-t4-1"),
                    intakeIds("t6", "status-t6-1"),
                ),
            )
            val executor = transferExecutor(database, driver, cat, intakeIds, BatchCommitIdSource(emptyList()), BatchStatusIdSource(emptyList()))
            val expected = P404ExpectedState()

            // E-01: intake T1 accepted (transfer_flow_source, contract_version 2).
            val e01 = assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.SOURCE, "source-t1"),
                    ImportReturnedId(ImportReturnedIdKind.EVIDENCE, "evidence-t1"),
                    ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-t1"),
                ),
                e01.returnedIds,
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-t1-intake"), ImportSourceId("source-t1"), ImportEvidenceId("evidence-t1"), ImportCandidateId("candidate-t1"), null, null),
                e01.receipt,
            )
            expected.intake(ledgerId.value, "req-t1-intake", "batch-p404-a", 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT1, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1", "evidence-t1", "candidate-t1", "status-t1-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-01")
            val sourceT1 = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-t1-intake").executeAsOne()
            assertEquals(2L, sourceT1.contract_version)
            assertEquals("transfer_flow_source", sourceT1.record_kind)
            val candidateT1 = database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t1").executeAsOne()
            assertEquals("transfer_flow", candidateT1.candidate_kind)
            assertEquals("pending_confirmation", candidateT1.status)

            // E-02: same-request equivalent replay.
            val e02 = assertIs<ImportIntakeResult.NoChange>(
                executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            assertEquals(e01.receipt, e02.receipt)
            assertEquals("equivalent_replay", e02.reasonCode)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-02")

            // E-03: same raw identity, different amount: hard collision, zero writes.
            val e03 = assertIs<ImportIntakeResult.Rejected>(
                executor.intake(intakeRequest("req-t1-intake-2", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1PrimeFacts, ImportCompleteness.VALID_COMPLETE)),
            )
            assertEquals("SPINE_IDENTITY_COLLISION", e03.diagnostic.code)
            assertEquals("fatal", e03.diagnostic.severity)
            assertEquals("record", e03.diagnostic.scope)
            assertEquals("batch-p404-a", e03.diagnostic.location.inputRef)
            assertEquals(0, e03.diagnostic.location.recordOrdinal)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-03")

            // E-04: same raw identity and facts but a different record kind: the kind is a
            // hash member, so the digest differs and the identity collides.
            val e04 = assertIs<ImportIntakeResult.Rejected>(
                executor.intake(intakeRequest("req-t1-intake-3", "batch-p404-a", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            assertEquals("SPINE_IDENTITY_COLLISION", e04.diagnostic.code)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-04")

            // E-05: the ordinary kind on a fresh raw identity is an independent candidate
            // dimension; ordinary stays contract_version 1.
            val e05 = assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest("req-b-intake", "batch-p404-b", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-b-intake"), ImportSourceId("source-t1o"), ImportEvidenceId("evidence-t1o"), ImportCandidateId("candidate-t1o"), null, null),
                e05.receipt,
            )
            expected.intake(ledgerId.value, "req-b-intake", "batch-p404-b", 0L, ImportRecordKind.ORDINARY_FLOW_SOURCE, hashT1Ordinary, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1o", "evidence-t1o", "candidate-t1o", "status-t1o-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-05")
            val sourceT1o = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-b-intake").executeAsOne()
            assertEquals(1L, sourceT1o.contract_version)
            assertEquals("ordinary_flow", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t1o").executeAsOne().candidate_kind)

            // E-06: intake T2 accepted (transfer_flow, in direction).
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest("req-t2-intake", "batch-p404-a", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, t2Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            expected.intake(ledgerId.value, "req-t2-intake", "batch-p404-a", 1L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT2, t2Facts, ImportCompleteness.VALID_COMPLETE, "source-t2", "evidence-t2", "candidate-t2", "status-t2-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-06")

            // E-07: intake T3 accepted (missing-leg candidate kind).
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest("req-t3-intake", "batch-p404-a", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t3Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            expected.intake(ledgerId.value, "req-t3-intake", "batch-p404-a", 2L, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, hashT3, t3Facts, ImportCompleteness.VALID_COMPLETE, "source-t3", "evidence-t3", "candidate-t3", "status-t3-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-07")
            assertEquals("transfer_flow_missing_leg", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t3").executeAsOne().candidate_kind)

            // E-08: intake T4 accepted (missing-leg, in direction).
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest("req-t4-intake", "batch-p404-a", 3, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t4Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            expected.intake(ledgerId.value, "req-t4-intake", "batch-p404-a", 3L, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, hashT4, t4Facts, ImportCompleteness.VALID_COMPLETE, "source-t4", "evidence-t4", "candidate-t4", "status-t4-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-08")
            assertEquals("transfer_flow_missing_leg", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t4").executeAsOne().candidate_kind)

            // E-09: intake T6 accepted as an incomplete transfer candidate (unresolved
            // direction keeps the raw "/" token).
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest("req-t6-intake", "batch-p404-a", 5, ImportRecordKind.TRANSFER_FLOW_SOURCE, t6Facts, ImportCompleteness.VALID_INCOMPLETE)),
            )
            expected.intake(ledgerId.value, "req-t6-intake", "batch-p404-a", 5L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT6, t6Facts, ImportCompleteness.VALID_INCOMPLETE, "source-t6", "evidence-t6", "candidate-t6", "status-t6-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-09")
            val stateT6 = database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t6").executeAsOne()
            assertEquals("transfer_flow", stateT6.candidate_kind)
            assertEquals("incomplete", stateT6.status)
            assertEquals(6, intakeIds.calls.get())
        } finally {
            driver.close()
        }
    }

    // ---------- T-22..T-28 (E-10..E-16): transfer confirm ----------

    @Test
    fun executesE10ToE16TransferConfirmsWithFrozenFormalAndReportShapes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
            val intakeIds = BatchIntakeIdSource(
                listOf(
                    intakeIds("t1", "status-t1-1"),
                    intakeIds("t2", "status-t2-1"),
                    intakeIds("t3", "status-t3-1"),
                ),
            )
            val commitIds = BatchCommitIdSource(
                listOf(
                    commitIds("confirmation-t1", "status-t1-2", "tx-t1", "version-t1-v1", "posting-set-t1", listOf("posting-t1-out", "posting-t1-in")),
                    commitIds("confirmation-t2", "status-t2-2", "tx-t2", "version-t2-v1", "posting-set-t2", listOf("posting-t2-out", "posting-t2-in")),
                ),
            )
            val executor = transferExecutor(database, driver, cat, intakeIds, commitIds, BatchStatusIdSource(emptyList()))
            val expected = P404ExpectedState()

            // Setup: C1/C2/C3 pending.
            executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-t1-intake", "batch-p404-a", 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT1, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1", "evidence-t1", "candidate-t1", "status-t1-1")
            executor.intake(intakeRequest("req-t2-intake", "batch-p404-a", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, t2Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-t2-intake", "batch-p404-a", 1L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT2, t2Facts, ImportCompleteness.VALID_COMPLETE, "source-t2", "evidence-t2", "candidate-t2", "status-t2-1")
            executor.intake(intakeRequest("req-t3-intake", "batch-p404-a", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t3Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-t3-intake", "batch-p404-a", 2L, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, hashT3, t3Facts, ImportCompleteness.VALID_COMPLETE, "source-t3", "evidence-t3", "candidate-t3", "status-t3-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-10 setup")

            // E-10: confirm C1 wallet -> bank accepted; balanced two-leg asset transfer.
            val e10 = assertIs<ImportCandidateDecisionResult.Accepted>(
                executor.confirm(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1)),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-t1"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-t1"),
                ),
                e10.returnedIds,
            )
            assertEquals(
                ImportReceipt(ImportRequestId("req-t1-confirm"), null, null, ImportCandidateId("candidate-t1"), ImportConfirmationId("confirmation-t1"), TransactionId("tx-t1")),
                e10.receipt,
            )
            assertEquals(1, commitIds.calls.get())
            expected.confirmTransfer(
                ledgerId.value, "req-t1-confirm", "candidate-t1", hashT1,
                "account-wallet-wechat", "account-bank-a", "2026-08-14T10:00:00+08:00",
                t1Facts.occurredAt, 10000L, "CNY", 2L,
                "confirmation-t1", "status-t1-2", "tx-t1", "version-t1-v1", "posting-set-t1",
                "posting-t1-out", "posting-t1-in",
            )
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-10")
            // Frozen decision-snapshot four-column shape: category/funding NULL, from/to set.
            val decisionT1 = database.ledgerQueries.selectImportDecisionSnapshotByRequest(ledgerId.value, "req-t1-confirm").executeAsOne()
            assertEquals("confirm", decisionT1.decision)
            assertNull(decisionT1.category_id)
            assertNull(decisionT1.funding_account_id)
            assertEquals("account-wallet-wechat", decisionT1.from_account_id)
            assertEquals("account-bank-a", decisionT1.to_account_id)
            assertEquals(hashT1, decisionT1.expected_content_hash)
            assertEquals("2026-08-14T10:00:00+08:00", decisionT1.explicit_confirmed_at)
            // Formal postings exactly -100.00 / +100.00 per currency.
            val postingsT1 = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-t1").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-t1-out", "account-wallet-wechat", -10000L, "CNY", 2L),
                    listOf("posting-t1-in", "account-bank-a", 10000L, "CNY", 2L),
                ),
                postingsT1.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )
            // Report semantics: internal transfer = amount; every other dimension zero.
            val reportT1 = captureState(driver, accountsByLedger).report.getValue(ledgerId.value)
            assertEquals(10000L, reportT1.internalTransferMinor)
            assertEquals(0L, reportT1.netWorthChangeMinor)
            assertEquals(0L, reportT1.externalIncomeMinor)
            assertEquals(0L, reportT1.externalExpenseMinor)
            assertEquals(0L, reportT1.externalCashInflowMinor)
            assertEquals(0L, reportT1.externalCashOutflowMinor)
            assertEquals(0L, reportT1.consumptionMinor)
            assertEquals(0L, reportT1.budgetEffectMinor)
            assertEquals(emptyMap(), reportT1.categoryTotals)
            assertEquals(mapOf("account-wallet-wechat" to -10000L, "account-bank-a" to 10000L, "account-bank-usd" to 0L, "account-asset-a" to 0L, "expense-account-food" to 0L, "income-account-salary" to 0L), reportT1.balancesByAccount)

            // E-11: same-request equivalent replay; factory and ID source untouched.
            val e11 = assertIs<ImportCandidateDecisionResult.NoChange>(
                executor.confirm(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1)),
            )
            assertEquals(e10.receipt, e11.receipt)
            assertEquals("equivalent_replay", e11.reasonCode)
            assertEquals(1, commitIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-11")

            // E-12: re-confirm the confirmed candidate with a new request.
            val e12 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1-confirm-2", "candidate-t1", hashT1)),
            )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", e12.diagnostic.code)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-12")

            // E-13: same request with a stale expected hash.
            val e13 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT2)),
            )
            assertEquals("SPINE_STALE_FINGERPRINT", e13.diagnostic.code)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-13")

            // E-14: same request with different legs: request identity conflict.
            val e14 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1, from = bankAccountId, to = walletAccountId)),
            )
            assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", e14.diagnostic.code)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-14")

            // E-15: confirm C2 bank -> wallet (direction in: wallet is the TO leg).
            val e15 = assertIs<ImportCandidateDecisionResult.Accepted>(
                executor.confirm(
                    transferConfirmRequest("req-t2-confirm", "candidate-t2", hashT2, from = bankAccountId, to = walletAccountId, confirmedAt = "2026-08-14T11:00:00+08:00"),
                ),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-t2"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-t2"),
                ),
                e15.returnedIds,
            )
            assertEquals(2, commitIds.calls.get())
            expected.confirmTransfer(
                ledgerId.value, "req-t2-confirm", "candidate-t2", hashT2,
                "account-bank-a", "account-wallet-wechat", "2026-08-14T11:00:00+08:00",
                t2Facts.occurredAt, 20000L, "CNY", 2L,
                "confirmation-t2", "status-t2-2", "tx-t2", "version-t2-v1", "posting-set-t2",
                "posting-t2-out", "posting-t2-in",
            )
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-15")
            val postingsT2 = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "posting-set-t2").executeAsList()
            assertEquals(
                listOf(
                    listOf("posting-t2-out", "account-bank-a", -20000L, "CNY", 2L),
                    listOf("posting-t2-in", "account-wallet-wechat", 20000L, "CNY", 2L),
                ),
                postingsT2.map { listOf(it.posting_id, it.account_id, it.amount_minor, it.currency_code, it.currency_precision) },
            )
            val reportT2 = captureState(driver, accountsByLedger).report.getValue(ledgerId.value)
            assertEquals(30000L, reportT2.internalTransferMinor)
            assertEquals(0L, reportT2.netWorthChangeMinor)
            assertEquals(0L, reportT2.externalCashInflowMinor)
            assertEquals(0L, reportT2.externalCashOutflowMinor)

            // E-16: confirm the missing-leg candidate C3: gate closed.
            val e16 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t3-confirm", "candidate-t3", hashT3)),
            )
            assertEquals("SPINE_TRANSFER_NOT_CONFIRMABLE", e16.diagnostic.code)
            assertEquals("candidate-t3", e16.diagnostic.location.candidateId?.value)
            assertEquals(2, commitIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-16")
        } finally {
            driver.close()
        }
    }

    // ---------- T-29..T-40 (E-17..E-28): reject, gates, domain failures, kind mismatch ----------

    @Test
    fun executesE17ToE28RejectsGateFailuresDomainFailuresAndKindMismatches() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
            val intakeIds = BatchIntakeIdSource(
                listOf(
                    intakeIds("t3", "status-t3-1"),
                    intakeIds("t4", "status-t4-1"),
                    intakeIds("t6", "status-t6-1"),
                    intakeIds("t1b", "status-t1b-1"),
                    intakeIds("t1c", "status-t1c-1"),
                    intakeIds("t1o", "status-t1o-1"),
                ),
            )
            val statusIds = BatchStatusIdSource(
                listOf(ImportStatusHistoryId("status-t3-2"), ImportStatusHistoryId("status-t4-2")),
            )
            // Five failing attempt batches (E-21..E-25) plus the corrected batch 2 (E-26).
            val attemptIds = BatchCommitIdSource(
                listOf(
                    commitIds("confirmation-t1b-attempt-1", "status-t1b-2-attempt-1", "tx-t1b-attempt-1", "version-t1b-attempt-1-v1", "posting-set-t1b-attempt-1", listOf("posting-t1b-attempt-1-out", "posting-t1b-attempt-1-in")),
                    commitIds("confirmation-t1b-attempt-1", "status-t1b-2-attempt-1", "tx-t1b-attempt-1", "version-t1b-attempt-1-v1", "posting-set-t1b-attempt-1", listOf("posting-t1b-attempt-1-out", "posting-t1b-attempt-1-in")),
                    commitIds("confirmation-t1b-attempt-1", "status-t1b-2-attempt-1", "tx-t1b-attempt-1", "version-t1b-attempt-1-v1", "posting-set-t1b-attempt-1", listOf("posting-t1b-attempt-1-out", "posting-t1b-attempt-1-in")),
                    commitIds("confirmation-t1b-attempt-1", "status-t1b-2-attempt-1", "tx-t1b-attempt-1", "version-t1b-attempt-1-v1", "posting-set-t1b-attempt-1", listOf("posting-t1b-attempt-1-out", "posting-t1b-attempt-1-in")),
                    commitIds("confirmation-t1b-attempt-1", "status-t1b-2-attempt-1", "tx-t1b-attempt-1", "version-t1b-attempt-1-v1", "posting-set-t1b-attempt-1", listOf("posting-t1b-attempt-1-out", "posting-t1b-attempt-1-in")),
                    commitIds("confirmation-t1b", "status-t1b-2", "tx-t1b", "version-t1b-v1", "posting-set-t1b", listOf("posting-t1b-out", "posting-t1b-in")),
                ),
            )
            val executor = transferExecutor(database, driver, cat, intakeIds, attemptIds, statusIds)
            val expected = P404ExpectedState()

            // Setup: C3, C4, C6, C1b, C1c, C1o.
            executor.intake(intakeRequest("req-t3-intake", "batch-p404-a", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t3Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-t3-intake", "batch-p404-a", 2L, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, hashT3, t3Facts, ImportCompleteness.VALID_COMPLETE, "source-t3", "evidence-t3", "candidate-t3", "status-t3-1")
            executor.intake(intakeRequest("req-t4-intake", "batch-p404-a", 3, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t4Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-t4-intake", "batch-p404-a", 3L, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, hashT4, t4Facts, ImportCompleteness.VALID_COMPLETE, "source-t4", "evidence-t4", "candidate-t4", "status-t4-1")
            executor.intake(intakeRequest("req-t6-intake", "batch-p404-a", 5, ImportRecordKind.TRANSFER_FLOW_SOURCE, t6Facts, ImportCompleteness.VALID_INCOMPLETE))
            expected.intake(ledgerId.value, "req-t6-intake", "batch-p404-a", 5L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT6, t6Facts, ImportCompleteness.VALID_INCOMPLETE, "source-t6", "evidence-t6", "candidate-t6", "status-t6-1")
            executor.intake(intakeRequest("req-t1b-intake", "batch-p404-c", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-t1b-intake", "batch-p404-c", 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT1, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1b", "evidence-t1b", "candidate-t1b", "status-t1b-1")
            executor.intake(intakeRequest("req-t1c-intake", "batch-p404-c", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-t1c-intake", "batch-p404-c", 1L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT1, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1c", "evidence-t1c", "candidate-t1c", "status-t1c-1")
            executor.intake(intakeRequest("req-b-intake", "batch-p404-b", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE))
            expected.intake(ledgerId.value, "req-b-intake", "batch-p404-b", 0L, ImportRecordKind.ORDINARY_FLOW_SOURCE, hashT1Ordinary, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1o", "evidence-t1o", "candidate-t1o", "status-t1o-1")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-17 setup")

            // E-17: confirm C4 missing-leg: gate closed.
            val e17 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t4-confirm", "candidate-t4", hashT4, from = bankAccountId, to = walletAccountId)),
            )
            assertEquals("SPINE_TRANSFER_NOT_CONFIRMABLE", e17.diagnostic.code)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-17")

            // E-18: reject C3 (manual disposition terminal state).
            val e18 = assertIs<ImportCandidateDecisionResult.Accepted>(executor.reject(rejectRequest("req-t3-reject", "candidate-t3", hashT3)))
            assertEquals(listOf(ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-t3")), e18.returnedIds)
            assertEquals(
                ImportReceipt(ImportRequestId("req-t3-reject"), null, null, ImportCandidateId("candidate-t3"), null, null),
                e18.receipt,
            )
            expected.reject(ledgerId.value, "req-t3-reject", "candidate-t3", hashT3, "status-t3-2")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-18")

            // E-19: reject C4.
            val e19 = assertIs<ImportCandidateDecisionResult.Accepted>(executor.reject(rejectRequest("req-t4-reject", "candidate-t4", hashT4)))
            assertEquals(listOf(ImportReturnedId(ImportReturnedIdKind.CANDIDATE, "candidate-t4")), e19.returnedIds)
            expected.reject(ledgerId.value, "req-t4-reject", "candidate-t4", hashT4, "status-t4-2")
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-19")
            assertEquals(2, statusIds.calls.get())

            // E-20: confirm the incomplete C6.
            val e20 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t6-confirm", "candidate-t6", hashT6)),
            )
            assertEquals("SPINE_CANDIDATE_INCOMPLETE", e20.diagnostic.code)
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-20")

            // E-21: direction gate failure (out row but wallet is the TO leg).
            val e21 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1b-confirm", "candidate-t1b", hashT1, from = bankAccountId, to = walletAccountId, confirmedAt = "2026-08-14T10:30:00+08:00")),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e21.diagnostic.code)
            assertEquals(1, attemptIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-21")
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t1b").executeAsOne().status)

            // E-22: same account both legs.
            val e22 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1b-confirm", "candidate-t1b", hashT1, from = walletAccountId, to = walletAccountId, confirmedAt = "2026-08-14T10:30:00+08:00")),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e22.diagnostic.code)
            assertEquals(2, attemptIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-22")

            // E-23: destination leg is not a self-owned real asset.
            val e23 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1b-confirm", "candidate-t1b", hashT1, from = walletAccountId, to = AccountId("expense-account-food"), confirmedAt = "2026-08-14T10:30:00+08:00")),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e23.diagnostic.code)
            assertEquals(3, attemptIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-23")

            // E-24: unknown destination account.
            val e24 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1b-confirm", "candidate-t1b", hashT1, from = walletAccountId, to = AccountId("account-unknown"), confirmedAt = "2026-08-14T10:30:00+08:00")),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e24.diagnostic.code)
            assertEquals(4, attemptIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-24")

            // E-25: currency mismatch between the two real asset legs.
            val e25 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-t1b-confirm", "candidate-t1b", hashT1, from = walletAccountId, to = bankUsdAccountId, confirmedAt = "2026-08-14T10:30:00+08:00")),
            )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", e25.diagnostic.code)
            assertEquals(5, attemptIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-25")

            // E-26: corrected retry on the same request identity accepts (batch 2 pinned).
            val e26 = assertIs<ImportCandidateDecisionResult.Accepted>(
                executor.confirm(transferConfirmRequest("req-t1b-confirm", "candidate-t1b", hashT1, confirmedAt = "2026-08-14T10:30:00+08:00")),
            )
            assertEquals(
                listOf(
                    ImportReturnedId(ImportReturnedIdKind.CONFIRMATION, "confirmation-t1b"),
                    ImportReturnedId(ImportReturnedIdKind.TRANSACTION, "tx-t1b"),
                ),
                e26.returnedIds,
            )
            assertEquals(6, attemptIds.calls.get())
            expected.confirmTransfer(
                ledgerId.value, "req-t1b-confirm", "candidate-t1b", hashT1,
                "account-wallet-wechat", "account-bank-a", "2026-08-14T10:30:00+08:00",
                t1Facts.occurredAt, 10000L, "CNY", 2L,
                "confirmation-t1b", "status-t1b-2", "tx-t1b", "version-t1b-v1", "posting-set-t1b",
                "posting-t1b-out", "posting-t1b-in",
            )
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-26")
            assertEquals("confirmed", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t1b").executeAsOne().status)

            // E-27: transfer candidate + ordinary decision fields: kind mismatch.
            val e27 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-t1c-confirm")),
                        candidateId = ImportCandidateId("candidate-t1c"),
                        expectedContentHash = hashT1,
                        explicitConfirmedAt = "2026-08-14T10:45:00+08:00",
                        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(
                            categoryId = CategoryId("category-food"),
                            fundingAccountId = assetAccountId,
                        ),
                    ),
                ),
            )
            assertEquals("SPINE_DECISION_KIND_MISMATCH", e27.diagnostic.code)
            assertEquals(6, attemptIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-27")

            // E-28: ordinary candidate + transfer decision fields: kind mismatch.
            val e28 = assertIs<ImportCandidateDecisionResult.Rejected>(
                executor.confirm(transferConfirmRequest("req-b-confirm", "candidate-t1o", hashT1Ordinary)),
            )
            assertEquals("SPINE_DECISION_KIND_MISMATCH", e28.diagnostic.code)
            assertEquals(6, attemptIds.calls.get())
            assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-28")
        } finally {
            driver.close()
        }
    }

    // ---------- T-41 (E-29/E-30): concurrency ----------

    @Test
    fun executesE29E30ConcurrentTransferConfirmsWithSingleWinner() {
        val path = Files.createTempFile("transfer-confirm-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val cat = catalog(ledgerId)
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            // Setup: C1 and C2 pending.
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val executor = transferExecutor(
                    database, driver, cat,
                    BatchIntakeIdSource(listOf(intakeIds("t1", "status-t1-1"), intakeIds("t2", "status-t2-1"))),
                    BatchCommitIdSource(emptyList()),
                    BatchStatusIdSource(emptyList()),
                )
                assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)))
                assertIs<ImportIntakeResult.Accepted>(executor.intake(intakeRequest("req-t2-intake", "batch-p404-a", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, t2Facts, ImportCompleteness.VALID_COMPLETE)))
            }

            val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
            val expected = P404ExpectedState()
            expected.intake(ledgerId.value, "req-t1-intake", "batch-p404-a", 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT1, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1", "evidence-t1", "candidate-t1", "status-t1-1")
            expected.intake(ledgerId.value, "req-t2-intake", "batch-p404-a", 1L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT2, t2Facts, ImportCompleteness.VALID_COMPLETE, "source-t2", "evidence-t2", "candidate-t2", "status-t2-1")

            // E-29: same confirm request from two threads; the shared ID source is
            // consumed exactly once and the loser replays the original receipt.
            val sharedIds = BatchCommitIdSource(
                listOf(commitIds("confirmation-t1", "status-t1-2", "tx-t1", "version-t1-v1", "posting-set-t1", listOf("posting-t1-out", "posting-t1-in"))),
            )
            val e29 = concurrentExecute(
                url,
                listOf(
                    { confirmOn(url, cat, sharedIds, transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1)) },
                    { confirmOn(url, cat, sharedIds, transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1)) },
                ),
            )
            assertEquals(1, e29.count { it is ImportCandidateDecisionResult.Accepted })
            assertEquals(1, e29.count { it is ImportCandidateDecisionResult.NoChange })
            assertEquals(1, sharedIds.calls.get())
            expected.confirmTransfer(
                ledgerId.value, "req-t1-confirm", "candidate-t1", hashT1,
                "account-wallet-wechat", "account-bank-a", "2026-08-14T10:00:00+08:00",
                t1Facts.occurredAt, 10000L, "CNY", 2L,
                "confirmation-t1", "status-t1-2", "tx-t1", "version-t1-v1", "posting-set-t1",
                "posting-t1-out", "posting-t1-in",
            )
            JdbcSqliteDriver(url).use { driver ->
                assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-29")
            }

            // E-30: distinct confirm requests on the same candidate; exactly one winner,
            // the loser is SPINE_CANDIDATE_NOT_PENDING with zero residue and consumes no ID.
            val sharedIds2 = BatchCommitIdSource(
                listOf(commitIds("confirmation-t2", "status-t2-2", "tx-t2", "version-t2-v1", "posting-set-t2", listOf("posting-t2-out", "posting-t2-in"))),
            )
            val e30 = concurrentExecute(
                url,
                listOf(
                    {
                        confirmOn(
                            url, cat, sharedIds2,
                            transferConfirmRequest("req-t2-confirm-a", "candidate-t2", hashT2, from = bankAccountId, to = walletAccountId, confirmedAt = "2026-08-14T11:00:00+08:00"),
                        )
                    },
                    {
                        confirmOn(
                            url, cat, sharedIds2,
                            transferConfirmRequest("req-t2-confirm-b", "candidate-t2", hashT2, from = bankAccountId, to = walletAccountId, confirmedAt = "2026-08-14T11:00:00+08:00"),
                        )
                    },
                ),
            )
            assertEquals(1, e30.count { it is ImportCandidateDecisionResult.Accepted })
            val e30Rejected = e30.filterIsInstance<ImportCandidateDecisionResult.Rejected>().single()
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", e30Rejected.diagnostic.code)
            assertEquals(1, sharedIds2.calls.get())
            // The winner's request identity is the only non-frozen input of the race; the
            // checkpoint folds it into the otherwise fully frozen expected post-state.
            val e30Winner = e30.filterIsInstance<ImportCandidateDecisionResult.Accepted>().single()
            val winnerRequest = e30Winner.receipt.requestId.value
            expected.confirmTransfer(
                ledgerId.value, winnerRequest, "candidate-t2", hashT2,
                "account-bank-a", "account-wallet-wechat", "2026-08-14T11:00:00+08:00",
                t2Facts.occurredAt, 20000L, "CNY", 2L,
                "confirmation-t2", "status-t2-2", "tx-t2", "version-t2-v1", "posting-set-t2",
                "posting-t2-out", "posting-t2-in",
            )
            JdbcSqliteDriver(url).use { driver ->
                assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-30")
            }
        } finally {
            Files.deleteIfExists(path)
        }
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
            TransferFlowFormalFactory(catalog, walletAccountId),
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
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- T-42 (E-31/E-32): failure injection ----------

    @Test
    fun executesE31E32InjectedFailuresWithFullRollbackAndCorrectedRetries() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accountsByLedger = mapOf(ledgerId.value to cat.accounts)

            // E-31: intake failure after the candidate insert (T1 copy @ batch-p404-d/0).
            val failingIntakeStore = SqlDelightImportSpineStore(
                database, driver,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE) error("injected") },
            )
            val intakeBatch1 = BatchIntakeIdSource(listOf(intakeIds("d1-attempt-1", "status-d1-1-attempt-1")))
            assertFailsWith<IllegalStateException> {
                ExecuteImportIntake(failingIntakeStore, intakeBatch1, ImportContentFingerprint()).execute(
                    intakeRequest("req-d1-intake", "batch-p404-d", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE),
                )
            }
            assertEquals(1, intakeBatch1.calls.get())
            val emptyState = captureState(driver, accountsByLedger)
            assertEquals(0, emptyState.importRequest.size)
            assertEquals(0, emptyState.importSourceRecord.size)
            assertEquals(0, emptyState.importReceipt.size)
            // The identity stays usable: the corrected retry consumes batch 2.
            val intakeBatch2 = BatchIntakeIdSource(listOf(intakeIds("d1", "status-d1-1")))
            val e31Retry = assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), intakeBatch2, ImportContentFingerprint()).execute(
                    intakeRequest("req-d1-intake", "batch-p404-d", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE),
                ),
            )
            assertEquals(1, intakeBatch2.calls.get())
            assertEquals("candidate-d1", e31Retry.receipt.candidateId.value)

            // E-32: confirm failure after the formal persist (new candidate @ batch-p404-d/1).
            val intakeBatchD2 = BatchIntakeIdSource(listOf(intakeIds("d2", "status-d2-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(SqlDelightImportSpineStore(database, driver), intakeBatchD2, ImportContentFingerprint()).execute(
                    intakeRequest("req-d2-intake", "batch-p404-d", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE),
                ),
            )
            val hashD2 = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts)
            val preConfirmState = captureState(driver, accountsByLedger)
            val failingConfirmStore = SqlDelightImportSpineStore(
                database, driver,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL) error("injected") },
            )
            val attempt1 = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "confirmation-d2-attempt-1", "status-d2-2-attempt-1", "tx-d2-attempt-1",
                        "version-d2-attempt-1-v1", "posting-set-d2-attempt-1",
                        listOf("posting-d2-attempt-1-out", "posting-d2-attempt-1-in"),
                    ),
                ),
            )
            assertFailsWith<IllegalStateException> {
                ConfirmImportCandidate(
                    failingConfirmStore, attempt1,
                    TransferFlowFormalFactory(cat, walletAccountId),
                ).execute(transferConfirmRequest("req-d2-confirm", "candidate-d2", hashD2, confirmedAt = "2026-08-14T10:00:00+08:00"))
            }
            assertEquals(1, attempt1.calls.get())
            // Full rollback including the formal rows: every table matches the pre-state.
            assertCanonicalState(preConfirmState, captureState(driver, accountsByLedger), "E-32 rollback")
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-d2").executeAsOne().status)

            // The corrected retry consumes batch 2 and commits all-or-nothing.
            val batch2 = BatchCommitIdSource(
                listOf(commitIds("confirmation-d2", "status-d2-2", "tx-d2", "version-d2-v1", "posting-set-d2", listOf("posting-d2-out", "posting-d2-in"))),
            )
            val e32Retry = assertIs<ImportCandidateDecisionResult.Accepted>(
                ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver), batch2,
                    TransferFlowFormalFactory(cat, walletAccountId),
                ).execute(transferConfirmRequest("req-d2-confirm", "candidate-d2", hashD2, confirmedAt = "2026-08-14T10:00:00+08:00")),
            )
            assertEquals(1, batch2.calls.get())
            assertEquals("tx-d2", e32Retry.receipt.transactionId?.value)
            assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
        } finally {
            driver.close()
        }
    }

    // ---------- T-43 (E-33): reopen and replay ----------

    @Test
    fun executesE33ReopenReplayWithOriginalReceipts() {
        val path = Files.createTempFile("transfer-reopen-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val cat = catalog(ledgerId)
        val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
        try {
            val originalReceipts = JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val executor = transferExecutor(
                    database, driver, cat,
                    BatchIntakeIdSource(listOf(intakeIds("t1", "status-t1-1"))),
                    BatchCommitIdSource(listOf(commitIds("confirmation-t1", "status-t1-2", "tx-t1", "version-t1-v1", "posting-set-t1", listOf("posting-t1-out", "posting-t1-in")))),
                    BatchStatusIdSource(emptyList()),
                )
                val intake = assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
                )
                val confirm = assertIs<ImportCandidateDecisionResult.Accepted>(
                    executor.confirm(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1)),
                )
                intake.receipt to confirm.receipt
            }
            val preReopenState = JdbcSqliteDriver(url).use { driver -> captureState(driver, accountsByLedger) }
            // E-33: close and reopen the file database; replay E-10 and the intake.
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val executor = transferExecutor(
                    database, driver, cat,
                    BatchIntakeIdSource(emptyList()),
                    BatchCommitIdSource(emptyList()),
                    BatchStatusIdSource(emptyList()),
                )
                val replayIntake = assertIs<ImportIntakeResult.NoChange>(
                    executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
                )
                assertEquals(originalReceipts.first, replayIntake.receipt)
                val replayConfirm = assertIs<ImportCandidateDecisionResult.NoChange>(
                    executor.confirm(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1)),
                )
                assertEquals(originalReceipts.second, replayConfirm.receipt)
                assertEquals("equivalent_replay", replayConfirm.reasonCode)
                assertCanonicalState(preReopenState, captureState(driver, accountsByLedger), "E-33")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---------- Malicious formal-graph builder (shared by T-44, T-48, T-54) ----------

    /**
     * Builds a domain-legal transfer formal graph from the immutable input and the
     * allocated IDs, with exactly one named invariant mutated per call (spec section 1.3
     * E-34/E-37 sub-vectors). All economics default to the canonical T1 shape.
     */
    private fun buildTransferGraph(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
        legSwap: Boolean = false,
        ledgerOverride: LedgerId? = null,
        kindOverride: TransactionKind? = null,
        amountOverride: Long? = null,
        currencyOverride: CurrencyUnit? = null,
        extraPosting: Boolean = false,
        extraVersion: Boolean = false,
        extraPostingSet: Boolean = false,
        occurredOverride: Instant? = null,
        statisticsOverride: Instant? = null,
        effectiveOverride: Instant? = null,
        noteOverride: String? = null,
        transactionIdOverride: TransactionId? = null,
        versionIdOverride: TransactionVersionId? = null,
        postingSetIdOverride: PostingSetId? = null,
        sourcePostingIdOverride: PostingId? = null,
        destinationPostingIdOverride: PostingId? = null,
        confirmationIdOverride: ImportConfirmationId? = null,
        statusHistoryIdOverride: ImportStatusHistoryId? = null,
    ): ImportFormalCommit {
        val fields = input.decisionFields as ImportConfirmDecisionFields.TransferFlow
        val amount = amountOverride ?: input.resolved.amountMinor
        val currency = currencyOverride ?: CurrencyUnit(input.resolved.currencyCode, 2)
        val txId = transactionIdOverride ?: ids.formalIds.transactionId
        val versionId = versionIdOverride ?: ids.formalIds.versionId
        val setId = postingSetIdOverride ?: ids.formalIds.postingSetId
        val outPostingId = sourcePostingIdOverride ?: ids.formalIds.postingIds[0]
        val inPostingId = destinationPostingIdOverride ?: ids.formalIds.postingIds[1]
        val fromLeg = if (legSwap) fields.toAccountId else fields.fromAccountId
        val toLeg = if (legSwap) fields.fromAccountId else fields.toAccountId
        val postings = mutableListOf(
            Posting(outPostingId, fromLeg, Money.ofMinor(-amount, currency)),
            Posting(inPostingId, toLeg, Money.ofMinor(amount, currency)),
        )
        if (extraPosting) {
            postings += Posting(PostingId(ids.formalIds.postingIds[0].value + "-extra"), toLeg, Money.ofMinor(0L, currency))
        }
        val soleSet = when (val created = PostingSet.create(setId, postings)) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> error("malicious graph must stay domain-legal: ${created.violation}")
        }
        val postingSets = mutableListOf(soleSet)
        if (extraPostingSet) {
            val extraSet = when (val created = PostingSet.create(PostingSetId(setId.value + "-extra"), postings)) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> error("malicious graph must stay domain-legal: ${created.violation}")
            }
            postingSets += extraSet
        }
        val sourceInstant = Instant.parse(input.resolved.occurredAt)
        val times = com.unifiedledger.domain.TransactionTimes(
            occurredAt = occurredOverride ?: sourceInstant,
            statisticsAt = statisticsOverride ?: sourceInstant,
            effectiveAt = effectiveOverride ?: sourceInstant,
        )
        val versions = mutableListOf(
            TransactionVersion(
                id = versionId,
                transactionId = txId,
                versionNumber = 1,
                postingSetId = setId,
                times = times,
                note = noteOverride,
            ),
        )
        var currentVersionId = versionId
        if (extraVersion) {
            val secondId = TransactionVersionId(versionId.value + "-v2")
            versions += TransactionVersion(
                id = secondId,
                transactionId = txId,
                versionNumber = 2,
                postingSetId = setId,
                times = times,
                note = null,
            )
            currentVersionId = secondId
        }
        val transaction = Transaction(
            id = txId,
            ledgerId = ledgerOverride ?: input.ledgerId,
            kind = kindOverride ?: TransactionKind.ACCOUNT_TRANSFER,
            currentVersionId = currentVersionId,
        )
        val formal = when (val created = FormalTransaction.create(transaction, versions, postingSets)) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> error("malicious graph must stay domain-legal: ${created.violation}")
        }
        return ImportFormalCommit(
            confirmationId = confirmationIdOverride ?: ids.confirmationId,
            statusHistoryId = statusHistoryIdOverride ?: ids.statusHistoryId,
            transaction = formal,
        )
    }

    private fun bindingAttemptIds(caseId: String): ImportCommitIds = commitIds(
        "attempt-binding-$caseId-confirmation",
        "attempt-binding-$caseId-status",
        "attempt-binding-$caseId-tx",
        "attempt-binding-$caseId-version",
        "attempt-binding-$caseId-posting-set",
        listOf("attempt-binding-$caseId-posting-0", "attempt-binding-$caseId-posting-1"),
    )

    private fun setupPendingC1d(driver: JdbcSqliteDriver, database: LedgerDatabase, catalog: LedgerCatalog): P404ExpectedState {
        val executor = transferExecutor(
            database, driver, catalog,
            BatchIntakeIdSource(listOf(intakeIds("t1d", "status-t1d-1"))),
            BatchCommitIdSource(emptyList()),
            BatchStatusIdSource(emptyList()),
        )
        assertIs<ImportIntakeResult.Accepted>(
            executor.intake(intakeRequest("req-t1d-intake", "batch-p404-c", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
        )
        val expected = P404ExpectedState()
        expected.intake(ledgerId.value, "req-t1d-intake", "batch-p404-c", 2L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT1, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1d", "evidence-t1d", "candidate-t1d", "status-t1d-1")
        return expected
    }

    // ---------- T-44 (E-34): formal binding mismatch vectors B01..B13 ----------

    @Test
    fun executesE34FormalBindingMismatchVectorsB01ToB13() {
        // The case table asserts ID set, order and names against the frozen manifest first.
        assertEquals(
            listOf(
                "B01", "B02", "B03", "B04", "B05", "B06", "B07",
                "B08", "B09", "B10", "B11", "B12", "B13",
            ),
            bindingMismatchManifest.map { it.first },
        )
        assertEquals(
            listOf(
                "reversed_legs", "wrong_ledger", "wrong_kind", "wrong_amount",
                "wrong_currency", "wrong_precision", "extra_posting",
                "multiple_versions_current_v2", "extra_posting_set",
                "wrong_occurred_at", "wrong_statistics_at", "wrong_effective_at",
                "non_null_note",
            ),
            bindingMismatchManifest.map { it.second },
        )

        bindingMismatchManifest.forEach { (caseId, name) ->
            // Each vector executes from its own fresh frozen pre-state (setup intake of the
            // T1 copy as pending C1d), so no vector can observe residue from another.
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val cat = catalog(ledgerId)
                val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
                val expected = setupPendingC1d(driver, database, cat)
                val preState = expected.state(accountsByLedger)
                assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-34 $caseId pre")

                val attemptIds = BatchCommitIdSource(listOf(bindingAttemptIds(caseId.lowercase())))
                val maliciousFactory = ImportCandidateFormalFactory { input, ids ->
                    DomainResult.Success(maliciousGraph(caseId, input, ids))
                }
                val result = ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver),
                    attemptIds,
                    maliciousFactory,
                ).execute(transferConfirmRequest("req-t1d-confirm", "candidate-t1d", hashT1))

                // Frozen spec contract (sections 1.3 E-34 and 4.2): the pre-persist binding
                // validator rejects every mismatch with SPINE_REFERENCE_INTEGRITY_VIOLATION
                // and zero residue before any persistFormal call.
                val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(result, "E-34 $caseId ($name) must be rejected")
                assertEquals("SPINE_REFERENCE_INTEGRITY_VIOLATION", rejected.diagnostic.code, "E-34 $caseId ($name) diagnostic")
                assertEquals("candidate-t1d", rejected.diagnostic.location.candidateId?.value)
                assertEquals(1, attemptIds.calls.get(), "E-34 $caseId consumed the attempt batch once")
                assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-34 $caseId ($name) zero residue")
                assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t1d").executeAsOne().status)
            } finally {
                driver.close()
            }
        }
    }

    private fun maliciousGraph(caseId: String, input: ImportCandidateFormalizationInput, ids: ImportCommitIds): ImportFormalCommit = when (caseId) {
        "B01" -> buildTransferGraph(input, ids, legSwap = true)
        "B02" -> buildTransferGraph(input, ids, ledgerOverride = otherLedgerId)
        "B03" -> buildTransferGraph(input, ids, kindOverride = TransactionKind.EXPENSE)
        "B04" -> buildTransferGraph(input, ids, amountOverride = 9999L)
        "B05" -> buildTransferGraph(input, ids, currencyOverride = usd)
        "B06" -> buildTransferGraph(input, ids, currencyOverride = CurrencyUnit("CNY", 3))
        "B07" -> buildTransferGraph(input, ids, extraPosting = true)
        "B08" -> buildTransferGraph(input, ids, extraVersion = true)
        "B09" -> buildTransferGraph(input, ids, extraPostingSet = true)
        "B10" -> buildTransferGraph(input, ids, occurredOverride = t1OccurredInstant.plus(kotlin.time.Duration.parse("PT1S")))
        "B11" -> buildTransferGraph(input, ids, statisticsOverride = t1OccurredInstant.plus(kotlin.time.Duration.parse("PT1S")))
        "B12" -> buildTransferGraph(input, ids, effectiveOverride = t1OccurredInstant.plus(kotlin.time.Duration.parse("PT1S")))
        "B13" -> buildTransferGraph(input, ids, noteOverride = "malicious-note")
        else -> error("unknown binding mismatch case: $caseId")
    }

    // ---------- T-45 (E-35): cross-ledger confirm ----------

    @Test
    fun executesE35CrossLedgerConfirmWithZeroResidue() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accountsByLedger = mapOf(ledgerId.value to cat.accounts, otherLedgerId.value to emptyList<Account>())
            val expected = P404ExpectedState()
            val executor = transferExecutor(
                database, driver, cat,
                BatchIntakeIdSource(listOf(intakeIds("t1", "status-t1-1"))),
                BatchCommitIdSource(emptyList()),
                BatchStatusIdSource(emptyList()),
            )
            assertIs<ImportIntakeResult.Accepted>(
                executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
            )
            expected.intake(ledgerId.value, "req-t1-intake", "batch-p404-a", 0L, ImportRecordKind.TRANSFER_FLOW_SOURCE, hashT1, t1Facts, ImportCompleteness.VALID_COMPLETE, "source-t1", "evidence-t1", "candidate-t1", "status-t1-1")
            val preState = expected.state(accountsByLedger)
            assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-35 pre")

            // The other ledger attempts to confirm the same candidate_id text: the lookup
            // uses identity.ledgerId only, so the candidate is not found and nothing runs.
            val commitIds = BatchCommitIdSource(
                listOf(commitIds("confirmation-cross", "status-cross-2", "tx-cross", "version-cross-v1", "posting-set-cross", listOf("posting-cross-out", "posting-cross-in"))),
            )
            var factoryCalls = 0
            val spyingFactory = ImportCandidateFormalFactory { input, ids ->
                factoryCalls++
                TransferFlowFormalFactory(cat, walletAccountId).create(input, ids)
            }
            val result = ConfirmImportCandidate(SqlDelightImportSpineStore(database, driver), commitIds, spyingFactory).execute(
                transferConfirmRequest("req-cross-ledger-confirm", "candidate-t1", hashT1, identityLedger = otherLedgerId),
            )
            val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(result)
            assertEquals("SPINE_CANDIDATE_NOT_FOUND", rejected.diagnostic.code)
            assertEquals(0, factoryCalls)
            assertEquals(0, commitIds.calls.get())
            // Both ledgers' complete canonical states are unchanged (claim rolled back).
            assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-35")
        } finally {
            driver.close()
        }
    }

    // ---------- T-46/T-47 (E-40/E-41): migration v21 -> v22 ----------

    private fun migrationProps(): Properties = Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null, sql,
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
        },
        0,
    ).value

    private fun queryLongJdbc(url: String, sql: String): Long =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }

    /**
     * Seeds a complete v21 pre-state: an ordinary spine intake/confirm/formal chain plus
     * rg03/rg04/rg08 coexistence rows (spec section 1.3 E-40/E-41).
     */
    private fun seedVersionTwentyOneFixture(driver: JdbcSqliteDriver) {
        driver.execute(null, "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('ledger-p404', 'req-v21-intake', 'intake')", 0)
        driver.execute(null, "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('ledger-p404', 'req-v21-confirm', 'confirm_candidate')", 0)
        driver.execute(
            null,
            "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p404', 'source-v21', 'req-v21-intake', 'batch-p402-a', 0, 'ordinary_flow_source', '$hashR1', 1, 'valid_complete', 12850, 'CNY', 2, '2026-08-01T12:30:00+08:00', 'out', 'settled')",
            0,
        )
        driver.execute(null, "INSERT INTO import_evidence(ledger_id, evidence_id, source_id, evidence_kind, observed_at) VALUES ('ledger-p404', 'evidence-v21', 'source-v21', 'source_observation', '2026-08-01T12:30:00+08:00')", 0)
        driver.execute(null, "INSERT INTO import_candidate(ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version) VALUES ('ledger-p404', 'candidate-v21', 'source-v21', 'ordinary_flow', '1.00', 'ordinary_flow_source', 1)", 0)
        driver.execute(null, "INSERT INTO import_candidate_requires_confirmation(ledger_id, candidate_id, requirement_index, requirement) VALUES ('ledger-p404', 'candidate-v21', 0, 'formal_transaction_creation')", 0)
        driver.execute(null, "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p404', 'candidate-v21', 1, 'status-v21-1', 'pending_confirmation', 'req-v21-intake', 'creation')", 0)
        driver.execute(
            null,
            "INSERT INTO import_candidate_decision_snapshot(ledger_id, request_id, decision, candidate_id, expected_content_hash, category_id, funding_account_id, explicit_confirmed_at) VALUES ('ledger-p404', 'req-v21-confirm', 'confirm', 'candidate-v21', '$hashR1', 'category-food', 'account-asset-a', '2026-08-07T10:00:00+08:00')",
            0,
        )
        driver.execute(null, "INSERT INTO posting_set(posting_set_id, ledger_id) VALUES ('posting-set-v21', 'ledger-p404')", 0)
        driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-v21', 'ledger-p404', 'EXPENSE')", 0)
        driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-v21-v1', 'tx-v21', 'ledger-p404', 1, 'posting-set-v21', '2026-08-01T04:30:00Z', '2026-08-01T04:30:00Z', '2026-08-01T04:30:00Z', NULL)", 0)
        driver.execute(null, "INSERT INTO ledger_transaction_current_version(transaction_id, ledger_id, current_version_id) VALUES ('tx-v21', 'ledger-p404', 'version-v21-v1')", 0)
        driver.execute(null, "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('posting-v21-expense', 'posting-set-v21', 'ledger-p404', 0, 'expense-account-food', 12850, 'CNY', 2)", 0)
        driver.execute(null, "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) VALUES ('posting-v21-asset', 'posting-set-v21', 'ledger-p404', 1, 'account-asset-a', -12850, 'CNY', 2)", 0)
        driver.execute(null, "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p404', 'candidate-v21', 2, 'status-v21-2', 'confirmed', 'req-v21-confirm', 'creation')", 0)
        driver.execute(null, "INSERT INTO import_confirmation(ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class, confirmed_at) VALUES ('ledger-p404', 'confirmation-v21', 'req-v21-confirm', 'candidate-v21', 'status-v21-2', 'tx-v21', 'creation', '2026-08-07T10:00:00+08:00')", 0)
        driver.execute(null, "INSERT INTO import_receipt(ledger_id, request_id, outcome, source_id, evidence_id, candidate_id, confirmation_id, transaction_id) VALUES ('ledger-p404', 'req-v21-intake', 'accepted', 'source-v21', 'evidence-v21', 'candidate-v21', NULL, NULL)", 0)
        driver.execute(null, "INSERT INTO import_receipt(ledger_id, request_id, outcome, source_id, evidence_id, candidate_id, confirmation_id, transaction_id) VALUES ('ledger-p404', 'req-v21-confirm', 'accepted', NULL, NULL, 'candidate-v21', 'confirmation-v21', 'tx-v21')", 0)
        driver.execute(null, "INSERT INTO rg03_operation_request(ledger_id, request_id, action_type) VALUES ('ledger-a', 'rg03-existing', 'MANUAL_ACCOUNT_TRANSFER')", 0)
        driver.execute(null, "INSERT INTO rg04_import_request(ledger_id, request_id, action_type) VALUES ('ledger-a', 'rg04-existing', 'IMPORT_SOURCE')", 0)
        driver.execute(null, "INSERT INTO rg08_operation(ledger_id, identity_value, action, operation_class, input_fingerprint, outcome) VALUES ('ledger-a', 'rg08-existing', 'LEND', 'creation', 'sha256:rg08-existing', 'ACCEPTED')", 0)
    }

    private data class SchemaMetadata(
        val objects: List<String>,
        val foreignKeys: List<String>,
        val indexes: List<String>,
    )

    private fun normalizeSql(sql: String): String =
        sql.replace(Regex("\\s+"), " ").trim().replace("( ", "(").replace(" )", ")")

    /** Fresh-vs-migrated schema shape comparison (objects, FK lists, index shapes). */
    private fun schemaMetadata(url: String): SchemaMetadata =
        DriverManager.getConnection(url).use { connection ->
            val objects = buildList {
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY type, name",
                    ).use { rows ->
                        while (rows.next()) {
                            add(listOf(rows.getString("type"), rows.getString("name"), rows.getString("tbl_name"), normalizeSql(rows.getString("sql"))).joinToString("|"))
                        }
                    }
                }
            }
            val tableNames = objects.asSequence()
                .filter { it.startsWith("table|") }
                .map { it.substringAfter('|').substringBefore('|') }
                .toList()
            val foreignKeys = buildList {
                tableNames.forEach { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA foreign_key_list('$table')").use { rows ->
                            while (rows.next()) {
                                add(
                                    listOf(
                                        table, rows.getInt("id"), rows.getInt("seq"), rows.getString("table"),
                                        rows.getString("from"), rows.getString("to"),
                                        rows.getString("on_update"), rows.getString("on_delete"), rows.getString("match"),
                                    ).joinToString("|"),
                                )
                            }
                        }
                    }
                }
            }.sorted()
            val indexes = buildList {
                tableNames.forEach { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA index_list('$table')").use { rows ->
                            while (rows.next()) {
                                add(listOf(table, rows.getInt("unique"), rows.getString("origin"), rows.getInt("partial")).joinToString("|"))
                            }
                        }
                    }
                }
            }.sorted()
            SchemaMetadata(objects, foreignKeys, indexes)
        }

    /** V21-shape canonical capture (decision snapshot has eight columns at v21). */
    private data class MigrationState(
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
    )

    private fun captureMigrationState(driver: JdbcSqliteDriver): MigrationState = MigrationState(
        importRequest = selectRows(driver, "SELECT ledger_id, request_id, operation FROM import_request", listOf(false, false, false)).sortedWith(rowComparator),
        importSourceRecord = selectRows(
            driver,
            "SELECT ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token FROM import_source_record",
            listOf(false, false, false, false, true, false, false, true, false, true, false, true, false, false, false),
        ).sortedWith(rowComparator),
        importEvidence = selectRows(driver, "SELECT ledger_id, evidence_id, source_id, evidence_kind, observed_at FROM import_evidence", listOf(false, false, false, false, false)).sortedWith(rowComparator),
        importCandidate = selectRows(driver, "SELECT ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version FROM import_candidate", listOf(false, false, false, false, false, false, true)).sortedWith(rowComparator),
        importCandidateRequiresConfirmation = selectRows(driver, "SELECT ledger_id, candidate_id, requirement_index, requirement FROM import_candidate_requires_confirmation", listOf(false, false, true, false)).sortedWith(rowComparator),
        importCandidateStatusHistory = selectRows(driver, "SELECT ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class FROM import_candidate_status_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
        importCandidateDecisionSnapshot = selectRows(driver, "SELECT ledger_id, request_id, decision, candidate_id, expected_content_hash, category_id, funding_account_id, explicit_confirmed_at FROM import_candidate_decision_snapshot", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
        importConfirmation = selectRows(driver, "SELECT ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class, confirmed_at FROM import_confirmation", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
        importReceipt = selectRows(driver, "SELECT ledger_id, request_id, outcome, source_id, evidence_id, candidate_id, confirmation_id, transaction_id FROM import_receipt", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
        ledgerTransaction = selectRows(driver, "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction", listOf(false, false, false, false)).sortedWith(rowComparator),
        postingSet = selectRows(driver, "SELECT posting_set_id, ledger_id FROM posting_set", listOf(false, false)).sortedWith(rowComparator),
        transactionVersion = selectRows(driver, "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note, confirmation_id FROM transaction_version", listOf(false, false, false, true, false, false, false, false, false, false)).sortedWith(rowComparator),
        posting = selectRows(driver, "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting", listOf(false, false, false, true, false, true, false, true)).sortedWith(rowComparator),
        ledgerTransactionCurrentVersion = selectRows(driver, "SELECT transaction_id, ledger_id, current_version_id FROM ledger_transaction_current_version", listOf(false, false, false)).sortedWith(rowComparator),
    )

    @Test
    fun executesE40VersionTwentyOneToTwentyTwoMigration() {
        val path = Files.createTempFile("p404-v21-v22-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val freshPath = Files.createTempFile("p404-v22-fresh-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.toAbsolutePath()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 21)
                seedVersionTwentyOneFixture(driver)
                driver.execute(null, "PRAGMA user_version = 21", 0)
            }
            assertEquals(21L, queryLongJdbc(url, "PRAGMA user_version"))
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                LedgerDatabase(driver).transaction {
                    LedgerDatabase.Schema.migrate(driver, oldVersion = 21, newVersion = 22)
                    driver.execute(null, "PRAGMA user_version = 22", 0)
                }
            }
            assertEquals(22L, queryLongJdbc(url, "PRAGMA user_version"))
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(23, LedgerDatabase.Schema.version)
                // Existing v21 ordinary rows keep contract_version 1.
                val source = database.ledgerQueries.selectImportSourceByOwnerRequest("ledger-p404", "req-v21-intake").executeAsOne()
                assertEquals(1L, source.contract_version)
                assertEquals("ordinary_flow_source", source.record_kind)
                assertEquals(hashR1, source.content_hash)
                // Existing decision rows carry NULL from/to after the rebuild.
                val decision = database.ledgerQueries.selectImportDecisionSnapshotByRequest("ledger-p404", "req-v21-confirm").executeAsOne()
                assertNull(decision.from_account_id)
                assertNull(decision.to_account_id)
                assertEquals("category-food", decision.category_id)
                // rg03/rg04/rg08 silo rows survive.
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM rg03_operation_request"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM rg04_import_request"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM rg08_operation"))
                // The v21 ordinary row replays through the unchanged intake equivalence.
                val replay = ExecuteImportIntake(
                    SqlDelightImportSpineStore(database, driver),
                    BatchIntakeIdSource(emptyList()),
                    ImportContentFingerprint(),
                ).execute(
                    ImportIntakeRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-v21-replay")),
                        inputRef = "batch-p402-a",
                        recordOrdinal = 0,
                        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                        facts = r1Facts,
                        completeness = ImportCompleteness.VALID_COMPLETE,
                    ),
                )
                assertIs<ImportIntakeResult.NoChange>(replay)
                assertEquals("equivalent_replay", replay.reasonCode)

                // New transfer v2 operations are usable after the migration.
                val cat = catalog(ledgerId)
                val executor = transferExecutor(
                    database, driver, cat,
                    BatchIntakeIdSource(listOf(intakeIds("t1", "status-t1-1"))),
                    BatchCommitIdSource(listOf(commitIds("confirmation-t1", "status-t1-2", "tx-t1", "version-t1-v1", "posting-set-t1", listOf("posting-t1-out", "posting-t1-in")))),
                    BatchStatusIdSource(emptyList()),
                )
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE)),
                )
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    executor.confirm(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1)),
                )
                assertEquals(
                    listOf(listOf<Any?>("confirmation-t1"), listOf<Any?>("confirmation-v21")),
                    selectRows(driver, "SELECT confirmation_id FROM import_confirmation ORDER BY confirmation_id", listOf(false)),
                )
                // tx-existing belongs to the VERSION_ONE_STATEMENTS v1 seed and survives
                // every migration; tx-v21 is the v21 fixture; tx-t1 is the post-migration
                // transfer v2 confirm.
                assertEquals(
                    listOf(listOf<Any?>("tx-existing"), listOf<Any?>("tx-t1"), listOf<Any?>("tx-v21")),
                    selectRows(driver, "SELECT transaction_id FROM ledger_transaction ORDER BY transaction_id", listOf(false)),
                )

                // Guards re-armed: every rebuilt table rejects UPDATE/DELETE.
                val rebuiltTables = listOf(
                    "import_source_record", "import_evidence", "import_candidate",
                    "import_candidate_requires_confirmation", "import_candidate_status_history",
                    "import_candidate_decision_snapshot", "import_confirmation", "import_receipt",
                )
                rebuiltTables.forEach { table ->
                    val updateColumn = when (table) {
                        "import_source_record" -> "amount_minor = 1"
                        "import_evidence" -> "observed_at = 'changed'"
                        "import_candidate" -> "confidence = '9.99'"
                        "import_candidate_requires_confirmation" -> "requirement = 'formal_transaction_creation'"
                        "import_candidate_status_history" -> "status = 'incomplete'"
                        "import_candidate_decision_snapshot" -> "decision = 'reject'"
                        "import_confirmation" -> "confirmed_at = 'changed'"
                        else -> "outcome = 'accepted'"
                    }
                    assertFailsWith<SQLException>("$table UPDATE must abort") {
                        driver.execute(null, "UPDATE $table SET $updateColumn", 0)
                    }
                    assertFailsWith<SQLException>("$table DELETE must abort") {
                        driver.execute(null, "DELETE FROM $table", 0)
                    }
                    assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = '${table}_guard_update'"))
                    assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = '${table}_guard_delete'"))
                }
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = 'import_status_history_sequence_guard'"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = 'import_status_history_transition_guard'"))
                // Status-history INSERT guards: sequence gap and terminal transitions abort.
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p404', 'candidate-t1', 4, 'status-gap', 'confirmed', 'req-t1-confirm', 'creation')",
                        0,
                    )
                }
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO import_candidate_status_history(ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class) VALUES ('ledger-p404', 'candidate-t1', 3, 'status-terminal', 'rejected', 'req-t1-confirm', 'status_transition')",
                        0,
                    )
                }
                // The pair CHECK rejects transfer-v1 and ordinary-v2 mismatches.
                driver.execute(null, "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('ledger-p404', 'req-check-probe', 'intake')", 0)
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p404', 'source-check-probe', 'req-check-probe', 'batch-probe', 99, 'transfer_flow_source', 'sha256:probe', 1, 'valid_complete', 100, 'CNY', 2, '2026-08-01T12:30:00+08:00', 'out', 'settled')",
                        0,
                    )
                }
                assertFailsWith<SQLException> {
                    driver.execute(
                        null,
                        "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p404', 'source-check-probe-2', 'req-check-probe', 'batch-probe', 98, 'ordinary_flow_source', 'sha256:probe2', 2, 'valid_complete', 100, 'CNY', 2, '2026-08-01T12:30:00+08:00', 'out', 'settled')",
                        0,
                    )
                }
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
            }
            // Bring the migrated database to the current v23 schema so the fresh
            // v23 schema comparison includes the additive P4-08 shared objects.
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 22, 23)
            }
            // Fresh schema equals the migrated schema (version 23).

            JdbcSqliteDriver(freshUrl, migrationProps()).use { driver ->
                LedgerDatabase.Schema.create(driver)
            }
            assertEquals(schemaMetadata(freshUrl), schemaMetadata(url))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(freshPath)
        }
    }

    @Test
    fun executesE41LateStageMigrationFailureRollsBackCompletely() {
        val path = Files.createTempFile("p404-v21-v22-late-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 21)
                seedVersionTwentyOneFixture(driver)
                driver.execute(null, "PRAGMA user_version = 21", 0)
            }
            val preMetadata = schemaMetadata(url)
            val preState = JdbcSqliteDriver(url, migrationProps()).use { driver -> captureMigrationState(driver) }
            assertEquals(21L, queryLongJdbc(url, "PRAGMA user_version"))

            // Production-equivalent outer transaction: the real 21.sqm executes fully and
            // user_version is bumped to 22, then a test-only CHECK violation is injected
            // after Stage 6 and before commit. The injection never touches 21.sqm.
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                val database = LedgerDatabase(driver)
                assertFailsWith<SQLException> {
                    database.transaction {
                        LedgerDatabase.Schema.migrate(driver, oldVersion = 21, newVersion = 22)
                        driver.execute(null, "PRAGMA user_version = 22", 0)
                        driver.execute(null, "CREATE TABLE p404_test_inject (value INTEGER NOT NULL CHECK (value = 0))", 0)
                        driver.execute(null, "INSERT INTO p404_test_inject(value) VALUES (1)", 0)
                    }
                }
            }

            // Close and reopen: the complete v21 pre-state returns value-for-value.
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                assertEquals(preState, captureMigrationState(driver))
                assertEquals("1", database(driver).ledgerQueries.foreignKeysEnabled().executeAsOne())
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM pragma_foreign_key_check"))
                // No stage, guard or test-injection object survives.
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE '%\\_stage' ESCAPE '\\'"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'p404%'"))
                assertEquals(0L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE name = 'p404_test_inject'"))
                // All eight v21 tables keep their original shape with guards attached.
                listOf(
                    "import_source_record", "import_evidence", "import_candidate",
                    "import_candidate_requires_confirmation", "import_candidate_status_history",
                    "import_candidate_decision_snapshot", "import_confirmation", "import_receipt",
                ).forEach { table ->
                    assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"))
                    assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = '${table}_guard_update'"))
                    assertEquals(1L, queryLong(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name = '${table}_guard_delete'"))
                }
                // The v21 decision snapshot keeps its eight-column shape (no from/to).
                assertEquals(8L, queryLong(driver, "SELECT count(*) FROM pragma_table_info('import_candidate_decision_snapshot')"))
            }
            assertEquals(21L, queryLongJdbc(url, "PRAGMA user_version"))
            assertEquals(preMetadata, schemaMetadata(url))

            // The same path without the injected failure migrates to v22.
            JdbcSqliteDriver(url, migrationProps()).use { driver ->
                LedgerDatabase(driver).transaction {
                    LedgerDatabase.Schema.migrate(driver, oldVersion = 21, newVersion = 22)
                    driver.execute(null, "PRAGMA user_version = 22", 0)
                }
                assertEquals(10L, queryLong(driver, "SELECT count(*) FROM pragma_table_info('import_candidate_decision_snapshot')"))
                assertEquals(1L, queryLong(driver, "SELECT count(*) FROM import_source_record WHERE record_kind = 'ordinary_flow_source' AND contract_version = 1"))
            }
            assertEquals(22L, queryLongJdbc(url, "PRAGMA user_version"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun database(driver: JdbcSqliteDriver): LedgerDatabase = LedgerDatabase(driver)

    // ---------- T-48: application-layer obligations ----------

    @Test
    fun applicationLayerGuardsMatchFrozenContracts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val store = SqlDelightImportSpineStore(database, driver)

            // Ledger-mismatched identity and snapshot are rejected by require (P4-02 precedent).
            assertFailsWith<IllegalArgumentException> {
                store.commitIntake(
                    ImportRequestIdentity(otherLedgerId, ImportRequestId("req-x")),
                    ImportIntakeSnapshot(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-x")),
                        inputRef = "batch-p404-a",
                        recordOrdinal = 0,
                        recordKind = ImportRecordKind.TRANSFER_FLOW_SOURCE,
                        facts = t1Facts,
                        completeness = ImportCompleteness.VALID_COMPLETE,
                        contentHash = hashT1,
                    ),
                ) { error("must not allocate") }
            }

            // ImportCandidateDecisionSnapshot carries no ledgerId member: the only ledger
            // identity on the confirm path is ImportRequestIdentity.ledgerId (compile-time
            // shape; exercised end-to-end by E-35).
            val snapshot = ImportCandidateDecisionSnapshot(
                candidateId = ImportCandidateId("candidate-t1"),
                decision = com.unifiedledger.application.ImportCandidateDecision.CONFIRM,
                expectedContentHash = hashT1,
                explicitConfirmedAt = null,
                confirmDecisionFields = ImportConfirmDecisionFields.TransferFlow(walletAccountId, bankAccountId),
            )
            assertEquals("candidate-t1", snapshot.candidateId.value)

            // commitOnce invokes allocateIds exactly once on the winning path and passes the
            // very same instance to the factory callback.
            val intakeIds = BatchIntakeIdSource(listOf(intakeIds("t1", "status-t1-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(
                    intakeRequest("req-t1-intake", "batch-p404-a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts, ImportCompleteness.VALID_COMPLETE),
                ),
            )
            val allocated = commitIds("confirmation-t1", "status-t1-2", "tx-t1", "version-t1-v1", "posting-set-t1", listOf("posting-t1-out", "posting-t1-in"))
            val allocations = AtomicInteger(0)
            var factoryObservedIds: ImportCommitIds? = null
            val spyingFactory = ImportCandidateFormalFactory { input, ids ->
                factoryObservedIds = ids
                TransferFlowFormalFactory(cat, walletAccountId).create(input, ids)
            }
            val confirmResult = ConfirmImportCandidate(
                store,
                ImportIdSource { allocations.incrementAndGet(); allocated },
                spyingFactory,
            ).execute(transferConfirmRequest("req-t1-confirm", "candidate-t1", hashT1))
            assertIs<ImportCandidateDecisionResult.Accepted>(confirmResult)
            assertEquals(1, allocations.get())
            assertTrue(factoryObservedIds === allocated, "factory must receive the exact allocated instance")

            // Rejection paths never consume the ID source: an exploding source proves it.
            val explodingIds = ImportIdSource { error("allocateIds must not run before the winning path") }
            val setup2 = BatchIntakeIdSource(listOf(intakeIds("t3", "status-t3-1"), intakeIds("t6", "status-t6-1")))
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(store, setup2, ImportContentFingerprint()).execute(
                    intakeRequest("req-t3-intake", "batch-p404-a", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, t3Facts, ImportCompleteness.VALID_COMPLETE),
                ),
            )
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(store, setup2, ImportContentFingerprint()).execute(
                    intakeRequest("req-t6-intake", "batch-p404-a", 5, ImportRecordKind.TRANSFER_FLOW_SOURCE, t6Facts, ImportCompleteness.VALID_INCOMPLETE),
                ),
            )
            // NOT_CONFIRMABLE (missing leg), INCOMPLETE and KIND_MISMATCH all reject before
            // allocateIds; any consumption would throw from the exploding source.
            assertIs<ImportCandidateDecisionResult.Rejected>(
                ConfirmImportCandidate(store, explodingIds, spyingFactory).execute(
                    transferConfirmRequest("req-t3-confirm-x", "candidate-t3", hashT3),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Rejected>(
                ConfirmImportCandidate(store, explodingIds, spyingFactory).execute(
                    transferConfirmRequest("req-t6-confirm-x", "candidate-t6", hashT6),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Rejected>(
                ConfirmImportCandidate(store, explodingIds, spyingFactory).execute(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-t3-confirm-y")),
                        candidateId = ImportCandidateId("candidate-t3"),
                        expectedContentHash = hashT3,
                        explicitConfirmedAt = null,
                        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), assetAccountId),
                    ),
                ),
            )

            // TransferFlowFormalFactory direction-gate unit vectors.
            val factory = TransferFlowFormalFactory(cat, walletAccountId)
            val outInput = ImportCandidateFormalizationInput(
                ledgerId,
                ImportResolvedSourceFacts(10000, "CNY", 2, t1Facts.occurredAt, "out", "提现已到账"),
                ImportConfirmDecisionFields.TransferFlow(walletAccountId, bankAccountId),
            )
            assertIs<DomainResult.Success<ImportFormalCommit>>(
                factory.create(outInput, bindingAttemptIds("unit-out")),
            )
            val inInput = ImportCandidateFormalizationInput(
                ledgerId,
                ImportResolvedSourceFacts(20000, "CNY", 2, t2Facts.occurredAt, "in", "支付成功"),
                ImportConfirmDecisionFields.TransferFlow(bankAccountId, walletAccountId),
            )
            assertIs<DomainResult.Success<ImportFormalCommit>>(
                factory.create(inInput, bindingAttemptIds("unit-in")),
            )
            val outWrongLeg = ImportCandidateFormalizationInput(
                ledgerId,
                outInput.resolved,
                ImportConfirmDecisionFields.TransferFlow(bankAccountId, walletAccountId),
            )
            val outFailure = assertIs<DomainResult.Failure>(factory.create(outWrongLeg, bindingAttemptIds("unit-out-wrong")))
            assertEquals(DomainViolation.InvalidOrdinaryIncome, outFailure.violation)
            val unknownDirection = ImportCandidateFormalizationInput(
                ledgerId,
                outInput.resolved.copy(directionToken = "/"),
                ImportConfirmDecisionFields.TransferFlow(walletAccountId, bankAccountId),
            )
            val unknownFailure = assertIs<DomainResult.Failure>(factory.create(unknownDirection, bindingAttemptIds("unit-unknown")))
            assertEquals(DomainViolation.InvalidOrdinaryIncome, unknownFailure.violation)

            // validateImportFormalBinding table-driven unit vectors share the T-44 manifest:
            // every B mutation fails at the persistence boundary; the canonical graph passes.
            val canonicalIds = bindingAttemptIds("unit-canonical")
            val canonicalInput = outInput
            assertIs<DomainResult.Success<Unit>>(
                validateImportFormalBinding(canonicalInput, canonicalIds, buildTransferGraph(canonicalInput, canonicalIds)),
            )
            bindingMismatchManifest.forEach { (caseId, name) ->
                val ids = bindingAttemptIds("unit-" + caseId.lowercase())
                val bindingFailure = validateImportFormalBinding(canonicalInput, ids, maliciousGraph(caseId, canonicalInput, ids))
                assertIs<DomainResult.Failure>(bindingFailure, "binding validator must reject $caseId ($name)")
                assertEquals(DomainViolation.InvalidFormalTransaction, bindingFailure.violation, "binding failure type for $caseId")
            }
        } finally {
            driver.close()
        }
    }

    // ---------- T-49: hash and version contracts ----------

    @Test
    fun hashAndVersionContractsStayFrozenAcrossKindExtension() {
        // T1 and T1' digests differ; same facts under a different kind differ too.
        assertTrue(hashT1 != hashT1Prime)
        assertTrue(hashT1 != hashT1Ordinary)
        // The record_kind member value follows the kind storage value.
        assertTrue(fingerprint.canonicalJson(ImportRecordKind.TRANSFER_FLOW_SOURCE, t1Facts).contains("\"record_kind\":\"transfer_flow_source\""))
        assertTrue(fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, t1Facts).contains("\"record_kind\":\"ordinary_flow_source\""))
        // Ordinary v1 digests stay byte-identical to the pinned P4-02 values, including the
        // R3-style absent status_token member omission.
        assertEquals(hashR1, fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r1Facts))
        assertEquals(hashR3, fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3Facts))
        assertTrue(!fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, r3Facts).contains("status_token"))
        // Kind -> contract version closed mapping.
        assertEquals(1, ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion)
        assertEquals(2, ImportRecordKind.TRANSFER_FLOW_SOURCE.contractVersion)
        assertEquals(2, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG.contractVersion)

        // The DDL pair CHECK rejects transfer-v1 and ordinary-v2 on a fresh schema.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "INSERT INTO import_request(ledger_id, request_id, operation) VALUES ('ledger-p404', 'req-hash-probe', 'intake')", 0)
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p404', 'source-probe-1', 'req-hash-probe', 'batch-probe', 0, 'transfer_flow_source', 'sha256:probe', 1, 'valid_complete', 100, 'CNY', 2, '2026-08-01T12:30:00+08:00', 'out', 'settled')",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token) VALUES ('ledger-p404', 'source-probe-2', 'req-hash-probe', 'batch-probe', 1, 'ordinary_flow_source', 'sha256:probe2', 2, 'valid_complete', 100, 'CNY', 2, '2026-08-01T12:30:00+08:00', 'out', 'settled')",
                    0,
                )
            }
        } finally {
            driver.close()
        }
    }

    // ---------- T-50..T-52 (R-01..R-03): regression manifest ----------

    @Test
    fun caseManifestsCoverFrozenOperationsExactlyOnce() {
        // E series: the frozen set, registered exactly once in this file.
        assertEquals(frozenECaseIds.toSet(), registeredECaseIds.toSet())
        assertEquals(frozenECaseIds.size, registeredECaseIds.size)
        // B/S/I manifests are exact and duplicate-free (order asserted where executed).
        assertEquals(13, bindingMismatchManifest.map { it.first }.toSet().size)
        assertEquals(9, scaleVectors.map { it.caseId }.toSet().size)
        assertEquals(7, allocatedIdManifest.map { it.first }.toSet().size)
        // R-01 regression is the untouched ImportSpineLifecycleEndToEndTest 30-op oracle;
        // R-02/R-03 are ImportSpineWechatEndToEndTest plus WechatBillParserJvmTest with the
        // three frozen cross-spec amendments (W7 routing, P-14 counts, E-12 batch intake);
        // P-01..P-12 are the parser-level T-01..T-12 in WechatBillParserJvmTest. All run in
        // this same suite, so the manifest universe P-01..P-12, E-01..E-37/E-40/E-41,
        // R-01..R-03 has exactly one owner per case ID.
        assertEquals(39, frozenECaseIds.size)
    }

    // ---------- T-53 (E-36): source scale normalization vectors S01..S09 ----------

    private fun scaleIntakeIds(caseId: String) =
        intakeIds("attempt-scale-${caseId.lowercase()}", "attempt-scale-${caseId.lowercase()}-status-1")

    private fun scaleCommitIds(caseId: String) = commitIds(
        "attempt-scale-${caseId.lowercase()}-confirmation",
        "attempt-scale-${caseId.lowercase()}-status-2",
        "attempt-scale-${caseId.lowercase()}-tx",
        "attempt-scale-${caseId.lowercase()}-version",
        "attempt-scale-${caseId.lowercase()}-posting-set",
        listOf("attempt-scale-${caseId.lowercase()}-posting-out", "attempt-scale-${caseId.lowercase()}-posting-in"),
    )

    @Test
    fun executesE36SourceScaleNormalizationVectorsS01ToS09() {
        // The case manifest must be exactly S01..S09 with the frozen names.
        assertEquals(listOf("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09"), scaleVectors.map { it.caseId })
        assertEquals(
            listOf(
                "scale_0_up", "scale_1_up", "scale_2_equal", "scale_3_exact_down",
                "scale_3_remainder", "scale_19_remainder", "scale_up_overflow",
                "negative_scale", "scale_gap_over_18",
            ),
            scaleVectors.map { it.name },
        )

        scaleVectors.forEachIndexed { index, vector ->
            // Each vector executes from an independent pending transfer candidate.
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val cat = catalog(ledgerId)
                val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
                val caseId = vector.caseId

                // The target from/to CurrencyUnit always comes from the catalog (CNY/2);
                // the source scale never constructs a CurrencyUnit (S06/S09 scales 19/21
                // would throw in the CurrencyUnit init, so a typed rejection below also
                // proves no such construction happened).
                val persistedScale = if (vector.sourceScale < 0) 0 else vector.sourceScale
                val facts = ImportSourceFacts(vector.amountMinor, "CNY", persistedScale, t1Facts.occurredAt, "out", "支付成功")
                val hash = fingerprint.digest(ImportRecordKind.TRANSFER_FLOW_SOURCE, facts)
                val intakeIds = BatchIntakeIdSource(listOf(scaleIntakeIds(caseId)))
                val commitIds = BatchCommitIdSource(listOf(scaleCommitIds(caseId)))
                // S08's negative source scale cannot be persisted (intake and the DDL both
                // reject it), so its confirm callback hands the production factory the same
                // immutable input with the resolved scale set to -1: exactly branch (a) of
                // the frozen normalization algorithm.
                val factory = if (caseId == "S08") {
                    ImportCandidateFormalFactory { input, ids ->
                        TransferFlowFormalFactory(cat, walletAccountId).create(
                            input.copy(resolved = input.resolved.copy(currencyPrecision = -1)),
                            ids,
                        )
                    }
                } else {
                    TransferFlowFormalFactory(cat, walletAccountId)
                }
                val executor = Executor(database, driver, cat, intakeIds, commitIds, BatchStatusIdSource(emptyList()), factory)
                val expected = P404ExpectedState()
                assertIs<ImportIntakeResult.Accepted>(
                    executor.intake(intakeRequest("req-scale-${caseId.lowercase()}-intake", "batch-p404-s", index, ImportRecordKind.TRANSFER_FLOW_SOURCE, facts, ImportCompleteness.VALID_COMPLETE)),
                )
                expected.intake(
                    ledgerId.value, "req-scale-${caseId.lowercase()}-intake", "batch-p404-s", index.toLong(),
                    ImportRecordKind.TRANSFER_FLOW_SOURCE, hash, facts, ImportCompleteness.VALID_COMPLETE,
                    "source-attempt-scale-${caseId.lowercase()}", "evidence-attempt-scale-${caseId.lowercase()}",
                    "candidate-attempt-scale-${caseId.lowercase()}", "attempt-scale-${caseId.lowercase()}-status-1",
                )
                val preState = expected.state(accountsByLedger)
                assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-36 $caseId pre")

                val result = executor.confirm(
                    transferConfirmRequest(
                        "req-scale-${caseId.lowercase()}-confirm",
                        "candidate-attempt-scale-${caseId.lowercase()}",
                        hash,
                        confirmedAt = "2026-08-14T12:00:00+08:00",
                    ),
                )

                val normalized = vector.expectedNormalizedMinor
                if (normalized != null) {
                    // S01..S04: exact conversion accepted with the E-10 canonical shape at
                    // the normalized principal.
                    assertIs<ImportCandidateDecisionResult.Accepted>(result, "E-36 $caseId must accept")
                    assertEquals(1, commitIds.calls.get())
                    expected.confirmTransfer(
                        ledgerId.value, "req-scale-${caseId.lowercase()}-confirm",
                        "candidate-attempt-scale-${caseId.lowercase()}", hash,
                        "account-wallet-wechat", "account-bank-a", "2026-08-14T12:00:00+08:00",
                        facts.occurredAt, normalized, "CNY", 2L,
                        "attempt-scale-${caseId.lowercase()}-confirmation",
                        "attempt-scale-${caseId.lowercase()}-status-2",
                        "attempt-scale-${caseId.lowercase()}-tx",
                        "attempt-scale-${caseId.lowercase()}-version",
                        "attempt-scale-${caseId.lowercase()}-posting-set",
                        "attempt-scale-${caseId.lowercase()}-posting-out",
                        "attempt-scale-${caseId.lowercase()}-posting-in",
                    )
                    assertCanonicalState(expected.state(accountsByLedger), captureState(driver, accountsByLedger), "E-36 $caseId")
                    val report = captureState(driver, accountsByLedger).report.getValue(ledgerId.value)
                    assertEquals(normalized, report.internalTransferMinor)
                    assertEquals(0L, report.netWorthChangeMinor)
                    assertEquals(0L, report.externalIncomeMinor)
                    assertEquals(0L, report.externalExpenseMinor)
                    assertEquals(0L, report.externalCashInflowMinor)
                    assertEquals(0L, report.externalCashOutflowMinor)
                    assertEquals(0L, report.consumptionMinor)
                } else {
                    // S05..S09: typed atomic rejection with zero residue; the candidate
                    // stays pending and the identity stays usable for a corrected retry.
                    val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(result, "E-36 $caseId must reject")
                    assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", rejected.diagnostic.code, "E-36 $caseId diagnostic")
                    assertEquals(1, commitIds.calls.get(), "E-36 $caseId consumed the attempt batch once")
                    assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-36 $caseId zero residue")
                    assertEquals(
                        "pending_confirmation",
                        database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-attempt-scale-${caseId.lowercase()}").executeAsOne().status,
                    )
                }
            } finally {
                driver.close()
            }
        }
    }

    // ---------- T-54 (E-37): allocated ID binding vectors I01..I07 ----------

    private fun allocatedAttemptIds(caseId: String): ImportCommitIds = commitIds(
        "attempt-id-${caseId.lowercase()}-confirmation",
        "attempt-id-${caseId.lowercase()}-status",
        "attempt-id-${caseId.lowercase()}-tx",
        "attempt-id-${caseId.lowercase()}-version",
        "attempt-id-${caseId.lowercase()}-posting-set",
        listOf("attempt-id-${caseId.lowercase()}-posting-0", "attempt-id-${caseId.lowercase()}-posting-1"),
    )

    @Test
    fun executesE37AllocatedIdBindingVectorsI01ToI07() {
        // The case manifest must be exactly I01..I07 with the frozen names.
        assertEquals(listOf("I01", "I02", "I03", "I04", "I05", "I06", "I07"), allocatedIdManifest.map { it.first })
        assertEquals(
            listOf(
                "wrong_confirmation_id", "wrong_status_history_id", "wrong_transaction_id",
                "wrong_version_id", "wrong_posting_set_id",
                "wrong_source_posting_id", "wrong_destination_posting_id",
            ),
            allocatedIdManifest.map { it.second },
        )

        allocatedIdManifest.forEach { (caseId, name) ->
            // Same canonical input as E-34; each vector runs from its own frozen pre-state.
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val cat = catalog(ledgerId)
                val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
                val expected = setupPendingC1d(driver, database, cat)
                val preState = expected.state(accountsByLedger)
                assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-37 $caseId pre")

                val attemptIds = BatchCommitIdSource(listOf(allocatedAttemptIds(caseId.lowercase())))
                val swappedFactory = ImportCandidateFormalFactory { input, ids ->
                    DomainResult.Success(idSwappedGraph(caseId, input, ids))
                }
                val result = ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver),
                    attemptIds,
                    swappedFactory,
                ).execute(transferConfirmRequest("req-t1d-confirm", "candidate-t1d", hashT1))

                // Frozen spec contract (sections 1.3 E-37 and 4.2): every replaced ID is
                // rejected item-by-item with SPINE_REFERENCE_INTEGRITY_VIOLATION before any
                // formal/decision/status/confirmation INSERT.
                val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(result, "E-37 $caseId ($name) must be rejected")
                assertEquals("SPINE_REFERENCE_INTEGRITY_VIOLATION", rejected.diagnostic.code, "E-37 $caseId ($name) diagnostic")
                assertEquals(1, attemptIds.calls.get(), "E-37 $caseId consumed the attempt batch once")
                assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-37 $caseId ($name) zero residue")
                assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-t1d").executeAsOne().status)
            } finally {
                driver.close()
            }
        }

        // Shape gate: allocated postingIds.size != 2 must be rejected with
        // SPINE_REFERENCE_INTEGRITY_VIOLATION before the factory runs at all.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val cat = catalog(ledgerId)
            val accountsByLedger = mapOf(ledgerId.value to cat.accounts)
            val expected = setupPendingC1d(driver, database, cat)
            val preState = expected.state(accountsByLedger)
            val malformedIds = BatchCommitIdSource(
                listOf(
                    commitIds(
                        "attempt-id-shape-confirmation", "attempt-id-shape-status", "attempt-id-shape-tx",
                        "attempt-id-shape-version", "attempt-id-shape-posting-set",
                        listOf("attempt-id-shape-posting-0", "attempt-id-shape-posting-1", "attempt-id-shape-posting-2"),
                    ),
                ),
            )
            var factoryCalls = 0
            val countingFactory = ImportCandidateFormalFactory { input, ids ->
                factoryCalls++
                TransferFlowFormalFactory(cat, walletAccountId).create(input, ids)
            }
            val result = ConfirmImportCandidate(
                SqlDelightImportSpineStore(database, driver),
                malformedIds,
                countingFactory,
            ).execute(transferConfirmRequest("req-t1d-confirm", "candidate-t1d", hashT1))
            val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(result)
            assertEquals("SPINE_REFERENCE_INTEGRITY_VIOLATION", rejected.diagnostic.code)
            assertEquals(0, factoryCalls, "shape gate must run before the factory")
            assertCanonicalState(preState, captureState(driver, accountsByLedger), "E-37 shape gate zero residue")
        } finally {
            driver.close()
        }
    }

    private fun idSwappedGraph(caseId: String, input: ImportCandidateFormalizationInput, ids: ImportCommitIds): ImportFormalCommit = when (caseId) {
        "I01" -> buildTransferGraph(input, ids, confirmationIdOverride = ImportConfirmationId("attempt-id-i01-wrong-confirmation"))
        "I02" -> buildTransferGraph(input, ids, statusHistoryIdOverride = ImportStatusHistoryId("attempt-id-i02-wrong-status"))
        "I03" -> buildTransferGraph(input, ids, transactionIdOverride = TransactionId("attempt-id-i03-wrong-tx"))
        "I04" -> buildTransferGraph(input, ids, versionIdOverride = TransactionVersionId("attempt-id-i04-wrong-version"))
        "I05" -> buildTransferGraph(input, ids, postingSetIdOverride = PostingSetId("attempt-id-i05-wrong-posting-set"))
        "I06" -> buildTransferGraph(input, ids, sourcePostingIdOverride = PostingId("attempt-id-i06-wrong-posting-0"))
        "I07" -> buildTransferGraph(input, ids, destinationPostingIdOverride = PostingId("attempt-id-i07-wrong-posting-1"))
        else -> error("unknown allocated-id case: $caseId")
    }
}

private fun row(vararg values: Any?): List<Any?> = listOf(*values)
