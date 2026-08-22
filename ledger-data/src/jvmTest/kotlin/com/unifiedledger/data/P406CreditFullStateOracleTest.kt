package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CreditFlowFormalFactory
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateCandidateId
import com.unifiedledger.application.ImportDuplicateComparisonFingerprint
import com.unifiedledger.application.ImportDuplicateComparisonSnapshot
import com.unifiedledger.application.ImportDuplicateIntakeIds
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportPaymentProfile
import com.unifiedledger.application.ImportPaymentVariant
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CreditRefundOriginalExpense
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P4-06 slice 1 (RL-05 credit) canonical full-state oracle (D-107 implementation spec
 * section 6), modeled on P407DuplicateClosedFullStateOracleTest: every scenario compares
 * the complete persisted state against a test-side expected builder constructed only
 * from the frozen fixtures — all import source/evidence/candidate/profile/status/
 * confirmation/receipt owners, the P4-07 duplicate tables, the formal graph, balances,
 * the report projection and the P4-08 reconciliation surface.
 *
 * Parser-level routing (whitelist, bracket stripping, judgment order, diagnostics) is
 * owned by AlipayCsvParserCreditJvmTest; this oracle drives the spine with the parser
 * output shape directly. Coverage: the RL-05 three anchors (matrix 1), status-gate
 * negative paths (2), duplicates and replay (7), confirmation negative paths (8), and
 * the evidence cardinality + fingerprint contract registrations (sections 3.2/5).
 * All fixtures are synthetic; amounts are anonymous representative values.
 */
class P406CreditFullStateOracleTest {
    private val ledgerId = LedgerId("ledger-p406-oracle")
    private val cny = CurrencyUnit("CNY", 2)
    private val usd = CurrencyUnit("USD", 2)
    private val fingerprint = ImportContentFingerprint()
    private val comparisonFingerprint = ImportDuplicateComparisonFingerprint()
    private val generatedAt = "2026-08-22T08:00:00Z"
    private val inputRef = "batch-p406-oracle"
    private val confirmedAt = "2026-08-22T10:00:00+08:00"

    // RL-05 anchors (anonymous representative values, D-106 section 7.1).
    private val expenseFacts = ImportSourceFacts(
        10000, "CNY", 2, "2026-08-01T12:00:00+08:00", "out", "settled",
        ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1,
    )
    private val repaymentFacts = ImportSourceFacts(
        5620, "CNY", 2, "2026-08-05T12:00:00+08:00", "out", "settled",
        ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1,
    )
    private val refundFacts = ImportSourceFacts(
        1535, "CNY", 2, "2026-08-08T12:00:00+08:00", "in", "refund_settled",
        ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1,
    )
    private val directProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗")
    private val repaymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, "余额宝", null)
    private val refundProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "花呗")

    /** The five original kinds of the legacy ledger_transaction.kind CHECK (insertTransaction mapping). */
    private val legacyTransactionKinds = setOf("OPENING_BALANCE", "EXPENSE", "INCOME", "ACCOUNT_TRANSFER", "CREDIT_REPAYMENT")

    private fun catalog(): LedgerCatalog = when (
        val result = LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("account-asset-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("account-credit-huabei"), ledgerId, AccountKind.LIABILITY, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("expense-account-food"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("expense-account-clothes"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("account-credit-usd"), ledgerId, AccountKind.LIABILITY, usd, ownedByUser = true, realAccount = true),
                Account(AccountId("account-credit-other-ledger"), LedgerId("ledger-p406-other"), AccountKind.LIABILITY, cny, ownedByUser = true, realAccount = true),
            ),
            categories = listOf(
                Category(CategoryId("category-primary-food"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-food"), ledgerId, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-primary-clothes"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-clothes"), ledgerId, parentId = CategoryId("category-primary-clothes"), postingAccountId = AccountId("expense-account-clothes"), active = true, kind = CategoryKind.EXPENSE),
            ),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("p406 oracle catalog failure: ${result.violation}")
    }

    private fun accounts(): List<Account> = catalog().accounts

    private fun intakeRequest(
        requestId: String,
        ordinal: Int,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        completeness: ImportCompleteness,
        profile: ImportPaymentProfile?,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = ordinal,
        recordKind = kind,
        facts = facts,
        completeness = completeness,
        candidateGeneratedAt = generatedAt,
        paymentProfile = profile,
    )

    private fun intakeIds(prefix: String, duplicates: List<Pair<String, String>> = emptyList()) = ImportIntakeIds(
        sourceId = ImportSourceId("source-$prefix"),
        evidenceId = ImportEvidenceId("evidence-$prefix"),
        candidateId = ImportCandidateId("candidate-$prefix"),
        statusHistoryId = ImportStatusHistoryId("status-$prefix-1"),
        duplicateIds = duplicates.map { (candidateId, historyId) ->
            ImportDuplicateIntakeIds(ImportDuplicateCandidateId(candidateId), ImportStatusHistoryId(historyId))
        },
    )

    private fun commitIds(prefix: String) = ImportCommitIds(
        confirmationId = ImportConfirmationId("confirmation-$prefix"),
        statusHistoryId = ImportStatusHistoryId("status-$prefix-2"),
        formalIds = ImportFormalIds(
            transactionId = TransactionId("tx-$prefix"),
            versionId = TransactionVersionId("version-$prefix-v1"),
            postingSetId = PostingSetId("posting-set-$prefix"),
            postingIds = listOf(PostingId("posting-$prefix-0"), PostingId("posting-$prefix-1")),
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

    private fun spineCounts(database: LedgerDatabase) = listOf(
        database.ledgerQueries.countImportRequests().executeAsOne(),
        database.ledgerQueries.countImportSourceRecords().executeAsOne(),
        database.ledgerQueries.countImportEvidence().executeAsOne(),
        database.ledgerQueries.countImportCandidates().executeAsOne(),
        database.ledgerQueries.countImportCandidatePaymentProfiles().executeAsOne(),
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

    /** Ordinary v1 factory for the regression fixtures (USD original expense, §6.8). */
    private class OrdinaryOutFactory(private val catalog: LedgerCatalog) : ImportCandidateFormalFactory {
        override fun create(input: ImportCandidateFormalizationInput, ids: ImportCommitIds): DomainResult<ImportFormalCommit> {
            val fields = input.decisionFields as ImportConfirmDecisionFields.OrdinaryFlow
            val resolved = input.resolved
            val money = Money.ofMinor(resolved.amountMinor, CurrencyUnit(resolved.currencyCode, resolved.currencyPrecision))
            return when (
                val result = createAssetPaidOrdinaryExpense(
                    catalog,
                    AssetPaidOrdinaryExpenseCommand(input.ledgerId, money, fields.categoryId, fields.fundingAccountId, TransactionTimes.collapsed(Instant.parse(resolved.occurredAt))),
                    AssetPaidOrdinaryExpenseIds(
                        transactionId = ids.formalIds.transactionId,
                        versionId = ids.formalIds.versionId,
                        postingSetId = ids.formalIds.postingSetId,
                        expensePostingId = ids.formalIds.postingIds[0],
                        paymentPostingId = ids.formalIds.postingIds[1],
                    ),
                )
            ) {
                is DomainResult.Success -> DomainResult.Success(ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, result.value))
                is DomainResult.Failure -> DomainResult.Failure(result.violation)
            }
        }
    }

    /**
     * Store-backed original-expense reader for the refund variant: resolves the original
     * transaction's kind/ledger/currency and the current version's positive (expense)
     * posting account. Returns null when the id does not exist in this ledger (the
     * composite FK would reject the write anyway; the domain fails closed first).
     */
    private fun originalExpenseReader(driver: JdbcSqliteDriver): (TransactionId) -> CreditRefundOriginalExpense? {
        return fun(transactionId: TransactionId): CreditRefundOriginalExpense? {
            val rows = selectRows(
                driver,
                "SELECT t.kind, t.ledger_id, p.account_id, p.currency_code FROM posting AS p " +
                    "JOIN transaction_version AS v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
                    "JOIN ledger_transaction AS t ON t.transaction_id = v.transaction_id AND t.ledger_id = p.ledger_id " +
                    "JOIN ledger_transaction_current_version AS c ON c.transaction_id = t.transaction_id " +
                    "AND c.ledger_id = t.ledger_id AND c.current_version_id = v.version_id " +
                    "WHERE t.ledger_id = '${ledgerId.value}' AND t.transaction_id = '${transactionId.value}' AND p.amount_minor > 0",
                listOf(false, false, false, false),
            )
            if (rows.size != 1) return null
            val kind = rows[0][0] as String
            val rowLedger = rows[0][1] as String
            val account = rows[0][2] as String
            val currency = rows[0][3] as String
            return CreditRefundOriginalExpense(
                transactionId, LedgerId(rowLedger), TransactionKind.valueOf(kind), currency, AccountId(account),
            )
        }
    }

    private class Executor(
        val database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        val store: SqlDelightImportSpineStore,
        val creditFactory: ImportCandidateFormalFactory,
        val intakeIds: ImportIntakeIdSource,
        val commitIds: ImportIdSource,
    ) {
        fun intake(request: ImportIntakeRequest): ImportIntakeResult =
            ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirmCredit(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult =
            ConfirmImportCandidate(store, commitIds, creditFactory).execute(request)

        fun confirmOrdinary(request: ImportCandidateConfirmRequest, factory: ImportCandidateFormalFactory): ImportCandidateDecisionResult =
            ConfirmImportCandidate(store, commitIds, factory).execute(request)
    }

    private fun executor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
    ): Executor {
        val store = SqlDelightImportSpineStore(database, driver)
        return Executor(database, driver, store, CreditFlowFormalFactory(catalog(), originalExpenseReader(driver)), intakeIds, commitIds)
    }

    private fun executor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
        failure: ImportSpineFailureInjector,
    ): Executor {
        val store = SqlDelightImportSpineStore(database, driver, failure)
        return Executor(database, driver, store, CreditFlowFormalFactory(catalog(), originalExpenseReader(driver)), intakeIds, commitIds)
    }

    private fun creditExpenseConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        category: String = "category-food",
        liability: String = "account-credit-huabei",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId(category), AccountId(liability)),
    )

    private fun creditRepaymentConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        asset: String = "account-asset-a",
        liability: String = "account-credit-huabei",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.CreditRepaymentFlow(AccountId(asset), AccountId(liability)),
    )

    private fun creditRefundConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        original: String = "tx-expense",
        category: String = "category-food",
        liability: String = "account-credit-huabei",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.CreditExpenseRefundFlow(CategoryId(category), AccountId(liability), TransactionId(original)),
    )

    // ---------- canonical capture (every P4-06/P4-07/P4-02/P4-08 owner) ----------

    private data class CreditReportProjection(
        val balancesByAccount: Map<String, Long>,
        val consumptionMinor: Long,
        val cashOutflowMinor: Long,
        val netWorthChangeMinor: Long,
    )

    private data class ReportTx(val kind: String, val postings: List<Triple<String, Long, String>>)

    private data class P406FullState(
        val importRequest: List<List<Any?>>,
        val importSourceRecord: List<List<Any?>>,
        val importEvidence: List<List<Any?>>,
        val importCandidate: List<List<Any?>>,
        val importCandidatePaymentProfile: List<List<Any?>>,
        val importCandidateRequiresConfirmation: List<List<Any?>>,
        val importCandidateStatusHistory: List<List<Any?>>,
        val importCandidateDecisionSnapshot: List<List<Any?>>,
        val importConfirmation: List<List<Any?>>,
        val importReceipt: List<List<Any?>>,
        val duplicateCandidate: List<List<Any?>>,
        val duplicateStatusHistory: List<List<Any?>>,
        val ledgerTransaction: List<List<Any?>>,
        val postingSet: List<List<Any?>>,
        val transactionVersion: List<List<Any?>>,
        val ledgerTransactionCurrentVersion: List<List<Any?>>,
        val posting: List<List<Any?>>,
        val mixedPaymentGroup: List<List<Any?>>,
        val mixedPaymentGroupLeg: List<List<Any?>>,
        val report: Map<String, CreditReportProjection>,
        val reconciliation: Map<String, List<List<Any?>>>,
    )

    private val rowComparator = Comparator<List<Any?>> { left, right ->
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrNull(index)
            val r = right.getOrNull(index)
            val compare = when {
                l == null && r == null -> 0
                l == null -> -1
                r == null -> 1
                else -> l.toString().compareTo(r.toString())
            }
            if (compare != 0) return@Comparator compare
        }
        0
    }

    private fun selectRows(driver: JdbcSqliteDriver, sql: String, longColumns: List<Boolean>): List<List<Any?>> = driver.executeQuery(
        null, sql,
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

    private fun captureFullState(driver: JdbcSqliteDriver, accounts: List<Account>): P406FullState {
        val formalJoin = "FROM posting AS p " +
            "JOIN transaction_version AS v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
            "JOIN ledger_transaction AS t ON t.transaction_id = v.transaction_id AND t.ledger_id = p.ledger_id"
        val formalRows = selectRows(
            driver,
            "SELECT t.transaction_id, t.ledger_id, t.kind, p.account_id, p.amount_minor, p.currency_code $formalJoin ORDER BY t.transaction_id, p.posting_index",
            listOf(false, false, false, false, true, false),
        )
        val reportTxs = formalRows.groupBy { it[0] as String }.map { (_, rows) ->
            ReportTx(rows.first()[2] as String, rows.map { Triple(it[3] as String, it[4] as Long, it[5] as String) })
        }
        val ledgerAccounts = accounts.filter { it.ledgerId == ledgerId }
        val accountKindById = ledgerAccounts.associate { it.id.value to it.kind }
        val report = reduceReport(reportTxs, ledgerAccounts, accountKindById)
        return P406FullState(
            importRequest = selectRows(driver, "SELECT ledger_id, request_id, operation FROM import_request", listOf(false, false, false)).sortedWith(rowComparator),
            importSourceRecord = selectRows(
                driver,
                "SELECT ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at FROM import_source_record",
                listOf(false, false, false, false, true, false, false, true, false, true, false, true, false, false, false, false, false, true, false),
            ).sortedWith(rowComparator),
            importEvidence = selectRows(driver, "SELECT ledger_id, evidence_id, source_id, evidence_kind, observed_at FROM import_evidence", listOf(false, false, false, false, false)).sortedWith(rowComparator),
            importCandidate = selectRows(driver, "SELECT ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version FROM import_candidate", listOf(false, false, false, false, false, false, true)).sortedWith(rowComparator),
            importCandidatePaymentProfile = selectRows(
                driver,
                "SELECT ledger_id, candidate_id, variant, asset_leg_kind_token, credit_leg_kind_token FROM import_candidate_payment_profile",
                listOf(false, false, false, false, false),
            ).sortedWith(rowComparator),
            importCandidateRequiresConfirmation = selectRows(driver, "SELECT ledger_id, candidate_id, requirement_index, requirement FROM import_candidate_requires_confirmation", listOf(false, false, true, false)).sortedWith(rowComparator),
            importCandidateStatusHistory = selectRows(driver, "SELECT ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class FROM import_candidate_status_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
            importCandidateDecisionSnapshot = selectRows(
                driver,
                "SELECT ledger_id, request_id, decision, candidate_id, expected_content_hash, category_id, funding_account_id, from_account_id, to_account_id, credit_liability_account_id, asset_account_id, original_transaction_id, asset_leg_minor, credit_leg_minor, explicit_confirmed_at FROM import_candidate_decision_snapshot",
                listOf(false, false, false, false, false, false, false, false, false, false, false, false, true, true, false),
            ).sortedWith(rowComparator),
            importConfirmation = selectRows(driver, "SELECT ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class, confirmed_at FROM import_confirmation", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            importReceipt = selectRows(driver, "SELECT ledger_id, request_id, outcome, source_id, evidence_id, candidate_id, confirmation_id, transaction_id FROM import_receipt", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            duplicateCandidate = selectRows(
                driver,
                "SELECT ledger_id, candidate_id, subject_source_id, possible_existing_source_id, kind, comparison_fingerprint, comparison_snapshot, provenance, confidence, rule_id, rule_version, generated_at, creation_request_id FROM import_duplicate_candidate",
                listOf(false, false, false, false, false, false, false, false, false, false, true, false, false),
            ).sortedWith(rowComparator),
            duplicateStatusHistory = selectRows(driver, "SELECT ledger_id, candidate_id, sequence, history_id, status, request_id, operation_class FROM import_duplicate_status_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
            ledgerTransaction = selectRows(driver, "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction", listOf(false, false, false, false)).sortedWith(rowComparator),
            postingSet = selectRows(driver, "SELECT posting_set_id, ledger_id FROM posting_set", listOf(false, false)).sortedWith(rowComparator),
            transactionVersion = selectRows(driver, "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note FROM transaction_version", listOf(false, false, false, true, false, false, false, false, false)).sortedWith(rowComparator),
            ledgerTransactionCurrentVersion = selectRows(driver, "SELECT transaction_id, ledger_id, current_version_id FROM ledger_transaction_current_version", listOf(false, false, false)).sortedWith(rowComparator),
            posting = selectRows(driver, "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting", listOf(false, false, false, true, false, true, false, true)).sortedWith(rowComparator),
            mixedPaymentGroup = selectRows(driver, "SELECT ledger_id, group_id FROM mixed_payment_group", listOf(false, false)).sortedWith(rowComparator),
            mixedPaymentGroupLeg = selectRows(driver, "SELECT ledger_id, group_id, leg_index FROM mixed_payment_group_leg", listOf(false, false, true)).sortedWith(rowComparator),
            report = mapOf(ledgerId.value to report),
            reconciliation = mapOf(
                "reconciliation_request" to selectRows(driver, "SELECT ledger_id, request_id FROM reconciliation_request", listOf(false, false)),
                "evidence_link" to selectRows(driver, "SELECT ledger_id, link_id FROM evidence_link", listOf(false, false)),
                "posting_reconciliation" to selectRows(driver, "SELECT ledger_id, reconciliation_id, posting_id, status, latest_sequence FROM posting_reconciliation", listOf(false, false, false, false, true)),
            ),
        )
    }

    /**
     * D-058/D-107 report projection: consumption = signed sum of hidden EXPENSE-account
     * postings (credit expense +total, credit refund -refund); cash outflow = signed sum
     * of real ASSET-account postings (credit expense contributes 0 — the purchase-day
     * cash outflow is zero); net-worth change = signed sum over real financial accounts
     * (asset + liability: the liability funding leg carries the credit expense).
     */
    private fun reduceReport(
        transactions: List<ReportTx>,
        ledgerAccounts: List<Account>,
        accountKindById: Map<String, AccountKind>,
    ): CreditReportProjection {
        val balances = LinkedHashMap<String, Long>()
        ledgerAccounts.forEach { balances[it.id.value] = 0L }
        var consumption = 0L
        var cash = 0L
        var netWorth = 0L
        transactions.forEach { tx ->
            tx.postings.forEach { (accountId, amount, _) ->
                balances[accountId] = (balances[accountId] ?: 0L) + amount
                when (accountKindById[accountId]) {
                    AccountKind.EXPENSE -> consumption += amount
                    AccountKind.ASSET -> {
                        cash += amount
                        netWorth += amount
                    }
                    AccountKind.LIABILITY -> netWorth += amount
                    else -> Unit
                }
            }
        }
        return CreditReportProjection(balances, consumption, cash, netWorth)
    }

    private fun assertFullState(expected: P406FullState, actual: P406FullState, checkpoint: String) {
        assertEquals(expected.importRequest, actual.importRequest, "$checkpoint: import_request")
        assertEquals(expected.importSourceRecord, actual.importSourceRecord, "$checkpoint: import_source_record")
        assertEquals(expected.importEvidence, actual.importEvidence, "$checkpoint: import_evidence")
        assertEquals(expected.importCandidate, actual.importCandidate, "$checkpoint: import_candidate")
        assertEquals(expected.importCandidatePaymentProfile, actual.importCandidatePaymentProfile, "$checkpoint: import_candidate_payment_profile")
        assertEquals(expected.importCandidateRequiresConfirmation, actual.importCandidateRequiresConfirmation, "$checkpoint: import_candidate_requires_confirmation")
        assertEquals(expected.importCandidateStatusHistory, actual.importCandidateStatusHistory, "$checkpoint: import_candidate_status_history")
        assertEquals(expected.importCandidateDecisionSnapshot, actual.importCandidateDecisionSnapshot, "$checkpoint: import_candidate_decision_snapshot")
        assertEquals(expected.importConfirmation, actual.importConfirmation, "$checkpoint: import_confirmation")
        assertEquals(expected.importReceipt, actual.importReceipt, "$checkpoint: import_receipt")
        assertEquals(expected.duplicateCandidate, actual.duplicateCandidate, "$checkpoint: import_duplicate_candidate")
        assertEquals(expected.duplicateStatusHistory, actual.duplicateStatusHistory, "$checkpoint: import_duplicate_status_history")
        assertEquals(expected.ledgerTransaction, actual.ledgerTransaction, "$checkpoint: ledger_transaction")
        assertEquals(expected.postingSet, actual.postingSet, "$checkpoint: posting_set")
        assertEquals(expected.transactionVersion, actual.transactionVersion, "$checkpoint: transaction_version")
        assertEquals(expected.ledgerTransactionCurrentVersion, actual.ledgerTransactionCurrentVersion, "$checkpoint: ledger_transaction_current_version")
        assertEquals(expected.posting, actual.posting, "$checkpoint: posting")
        assertEquals(expected.mixedPaymentGroup, actual.mixedPaymentGroup, "$checkpoint: mixed_payment_group")
        assertEquals(expected.mixedPaymentGroupLeg, actual.mixedPaymentGroupLeg, "$checkpoint: mixed_payment_group_leg")
        assertEquals(expected.report, actual.report, "$checkpoint: report projection")
        // Slice 1 (D-107) writes no P4-08 reconciliation state: the two real tables
        // below are spec-boundary assertions, not a P4-08 regression surface. P4-08
        // end-to-end coverage is owned by P408ReconciliationStoreTest and
        // P408ReconciliationCanonicalOracleTest.
        assertEquals(emptyList(), actual.reconciliation.getValue("evidence_link"), "$checkpoint: evidence_link must be empty (slice 1)")
        assertEquals(emptyList(), actual.reconciliation.getValue("posting_reconciliation"), "$checkpoint: posting_reconciliation must be empty (slice 1)")
        assertEquals(expected.reconciliation, actual.reconciliation, "$checkpoint: P4-08 reconciliation state")
    }

    // ---------- test-side expected builder (never reads the database under test) ----------

    private fun tupleComparisonJson(subjectSourceId: String, existingSourceId: String, kind: ImportRecordKind, facts: ImportSourceFacts): String {
        val projection = ImportDuplicateComparisonSnapshot(
            ImportSourceId(subjectSourceId), ImportSourceId(existingSourceId), kind, kind.contractVersion,
            facts.amountMinor, facts.currencyCode, facts.currencyPrecision, facts.occurredAt,
            facts.directionToken, facts.statusToken,
        )
        return "{\"possible_existing_source_id\":\"$existingSourceId\",\"subject_source_id\":\"$subjectSourceId\",\"tuple\":${comparisonFingerprint.canonicalJson(projection)}}"
    }

    private inner class Expected {
        val requests = mutableListOf<List<Any?>>()
        val sources = mutableListOf<List<Any?>>()
        val evidence = mutableListOf<List<Any?>>()
        val candidates = mutableListOf<List<Any?>>()
        val profiles = mutableListOf<List<Any?>>()
        val requirements = mutableListOf<List<Any?>>()
        val statusHistory = mutableListOf<List<Any?>>()
        val decisions = mutableListOf<List<Any?>>()
        val confirmations = mutableListOf<List<Any?>>()
        val receipts = mutableListOf<List<Any?>>()
        val duplicateCandidates = mutableListOf<List<Any?>>()
        val duplicateHistory = mutableListOf<List<Any?>>()
        val transactions = mutableListOf<List<Any?>>()
        val postingSets = mutableListOf<List<Any?>>()
        val versions = mutableListOf<List<Any?>>()
        val currentVersions = mutableListOf<List<Any?>>()
        val postings = mutableListOf<List<Any?>>()
        val reportTxs = mutableListOf<ReportTx>()

        fun intake(
            requestId: String,
            ordinal: Int,
            kind: ImportRecordKind,
            facts: ImportSourceFacts,
            completeness: ImportCompleteness,
            profile: ImportPaymentProfile?,
            prefix: String,
            duplicates: List<Triple<String, String, String>> = emptyList(),
        ) {
            val complete = completeness == ImportCompleteness.VALID_COMPLETE
            val settled = facts.fundingState == ImportFundingState.SETTLED
            val hash = fingerprint.digest(kind, facts, profile)
            requests += row(ledgerId.value, requestId, "intake")
            sources += row(
                ledgerId.value, "source-$prefix", requestId, inputRef, ordinal.toLong(), kind.storageValue,
                hash, kind.contractVersion.toLong(), if (complete) "valid_complete" else "valid_incomplete",
                facts.amountMinor, facts.currencyCode, facts.currencyPrecision.toLong(), facts.occurredAt,
                facts.directionToken, facts.statusToken, facts.fundingState.name, facts.fundingRuleId,
                facts.fundingRuleVersion.toLong(), generatedAt,
            )
            evidence += row(ledgerId.value, "evidence-$prefix", "source-$prefix", "source_observation", facts.occurredAt)
            val candidateKind = when (kind) {
                ImportRecordKind.ORDINARY_FLOW_SOURCE -> "ordinary_flow"
                ImportRecordKind.TRANSFER_FLOW_SOURCE -> "transfer_flow"
                ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG -> "transfer_flow_missing_leg"
                ImportRecordKind.CREDIT_EXPENSE_SOURCE -> "credit_expense"
                ImportRecordKind.CREDIT_REPAYMENT_SOURCE -> "credit_repayment"
                ImportRecordKind.MIXED_PAYMENT_SOURCE -> "mixed_payment"
            }
            candidates += row(ledgerId.value, "candidate-$prefix", "source-$prefix", candidateKind, if (complete) "1.00" else "0.50", kind.storageValue, 1L)
            if (profile != null) {
                profiles += row(ledgerId.value, "candidate-$prefix", profile.variant.storageValue, profile.assetLegKindToken, profile.creditLegKindToken)
            }
            requirements += row(ledgerId.value, "candidate-$prefix", 0L, "formal_transaction_creation")
            statusHistory += row(
                ledgerId.value, "candidate-$prefix", 1L, "status-$prefix-1",
                if (complete && settled) "pending_confirmation" else "incomplete", requestId, "creation",
            )
            receipts += row(ledgerId.value, requestId, "accepted", "source-$prefix", "evidence-$prefix", "candidate-$prefix", null, null)
            duplicates.forEach { (duplicateCandidateId, historyId, existingSourceId) ->
                duplicateCandidates += row(
                    ledgerId.value, duplicateCandidateId, "source-$prefix", existingSourceId, "EXACT_BUSINESS_TUPLE",
                    comparisonFingerprintDigest(kind, facts, "source-$prefix", existingSourceId),
                    tupleComparisonJson("source-$prefix", existingSourceId, kind, facts),
                    "source_declared + mechanical_decode + p407_exact_business_tuple_v1", "exact",
                    "p407_exact_business_tuple_v1", 1L, generatedAt, requestId,
                )
                duplicateHistory += row(ledgerId.value, duplicateCandidateId, 1L, historyId, "DEFERRED", requestId, "creation")
            }
        }

        private fun comparisonFingerprintDigest(kind: ImportRecordKind, facts: ImportSourceFacts, subjectSourceId: String, existingSourceId: String): String {
            val projection = ImportDuplicateComparisonSnapshot(
                ImportSourceId(subjectSourceId), ImportSourceId(existingSourceId), kind, kind.contractVersion,
                facts.amountMinor, facts.currencyCode, facts.currencyPrecision, facts.occurredAt,
                facts.directionToken, facts.statusToken,
            )
            return comparisonFingerprint.digest(projection)
        }

        /** A 2-posting formal transaction (posting 0 = firstLeg, posting 1 = secondLeg). */
        fun formal(
            prefix: String,
            kind: String,
            occurredAt: String,
            firstLeg: Triple<String, Long, String>,
            secondLeg: Triple<String, Long, String>,
        ) {
            // Persisted times are the UTC ISO form of the source offset instant.
            val timeText = Instant.parse(occurredAt).toString()
            // The legacy kind column is constrained to the original five kinds; newer
            // formal kinds persist as kind=EXPENSE + canonical_kind (insertTransaction
            // mapping in Ledger.sq, D-078 precedent).
            val persistedKind = if (kind in legacyTransactionKinds) kind else "EXPENSE"
            val canonicalKind = if (kind in legacyTransactionKinds) null else kind
            transactions += row("tx-$prefix", ledgerId.value, persistedKind, canonicalKind)
            postingSets += row("posting-set-$prefix", ledgerId.value)
            // Refund-receipt versions carry a null note (D-078 precedent); the
            // MixedPayment formal() helper persists an empty string note otherwise.
            val note = if (kind == "REFUND_RECEIPT") null else ""
            versions += row("version-$prefix-v1", "tx-$prefix", ledgerId.value, 1L, "posting-set-$prefix", timeText, timeText, timeText, note)
            currentVersions += row("tx-$prefix", ledgerId.value, "version-$prefix-v1")
            postings += row("posting-$prefix-0", "posting-set-$prefix", ledgerId.value, 0L, firstLeg.first, firstLeg.second, firstLeg.third, 2L)
            postings += row("posting-$prefix-1", "posting-set-$prefix", ledgerId.value, 1L, secondLeg.first, secondLeg.second, secondLeg.third, 2L)
            reportTxs += ReportTx(kind, listOf(firstLeg, secondLeg))
        }

        fun confirm(
            requestId: String,
            requestPrefix: String,
            candidatePrefix: String = requestPrefix,
            decisionRow: List<Any?>,
        ) {
            requests += row(ledgerId.value, requestId, "confirm_candidate")
            decisions += decisionRow
            statusHistory += row(ledgerId.value, "candidate-$candidatePrefix", 2L, "status-$requestPrefix-2", "confirmed", requestId, "creation")
            confirmations += row(ledgerId.value, "confirmation-$requestPrefix", requestId, "candidate-$candidatePrefix", "status-$requestPrefix-2", "tx-$requestPrefix", "creation", confirmedAt)
            receipts += row(ledgerId.value, requestId, "accepted", null, null, "candidate-$candidatePrefix", "confirmation-$requestPrefix", "tx-$requestPrefix")
        }

        fun state(accounts: List<Account>): P406FullState {
            val ledgerAccounts = accounts.filter { it.ledgerId == ledgerId }
            val accountKindById = ledgerAccounts.associate { it.id.value to it.kind }
            return P406FullState(
                importRequest = requests.sortedWith(rowComparator),
                importSourceRecord = sources.sortedWith(rowComparator),
                importEvidence = evidence.sortedWith(rowComparator),
                importCandidate = candidates.sortedWith(rowComparator),
                importCandidatePaymentProfile = profiles.sortedWith(rowComparator),
                importCandidateRequiresConfirmation = requirements.sortedWith(rowComparator),
                importCandidateStatusHistory = statusHistory.sortedWith(rowComparator),
                importCandidateDecisionSnapshot = decisions.sortedWith(rowComparator),
                importConfirmation = confirmations.sortedWith(rowComparator),
                importReceipt = receipts.sortedWith(rowComparator),
                duplicateCandidate = duplicateCandidates.sortedWith(rowComparator),
                duplicateStatusHistory = duplicateHistory.sortedWith(rowComparator),
                ledgerTransaction = transactions.sortedWith(rowComparator),
                postingSet = postingSets.sortedWith(rowComparator),
                transactionVersion = versions.sortedWith(rowComparator),
                ledgerTransactionCurrentVersion = currentVersions.sortedWith(rowComparator),
                posting = postings.sortedWith(rowComparator),
                mixedPaymentGroup = emptyList(),
                mixedPaymentGroupLeg = emptyList(),
                report = mapOf(ledgerId.value to reduceReport(reportTxs, ledgerAccounts, accountKindById)),
                // Slice 1 writes no P4-08 reconciliation state (D-107 specification
                // boundary): every reconciliation owner stays empty. This is not a P4-08
                // regression surface — the empty-table assertions below are a contract
                // assertion that slice 1 keeps its scope, not a substitute for P4-08
                // end-to-end oracle coverage (owned by P408ReconciliationStoreTest /
                // P408ReconciliationCanonicalOracleTest).
                reconciliation = mapOf(
                    "reconciliation_request" to emptyList(),
                    "evidence_link" to emptyList(),
                    "posting_reconciliation" to emptyList(),
                ),
            )
        }

        private fun row(vararg values: Any?): List<Any?> = values.toList()
    }

    // ---------- Matrix 1: the RL-05 three anchors ----------

    @Test
    fun rl05ThreeAnchorsConfirmWithExactFormalGraphAndReportEffects() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashExpense = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, directProfile)
            val hashRepay = fingerprint.digest(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repaymentFacts, repaymentProfile)
            val hashRefund = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, refundFacts, refundProfile)

            // Anchor 1: credit expense (installment-form method folded onto 花呗).
            val e1 = assertIs<ImportIntakeResult.Accepted>(
                executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("expense"))), BatchCommitIdSource(listOf(commitIds("expense")))).intake(
                    intakeRequest("req-expense", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile),
                ),
            )
            assertEquals(listOf("source-expense", "evidence-expense", "candidate-expense"), e1.returnedIds.map { it.id })
            val confirm1 = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("expense"))), BatchCommitIdSource(listOf(commitIds("expense"))))
            val c1 = assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm1.confirmCredit(creditExpenseConfirmRequest("req-expense-confirm", "candidate-expense", hashExpense)),
            )
            assertEquals("tx-expense", c1.receipt.transactionId?.value)
            expected.intake("req-expense", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "expense")
            expected.confirm(
                "req-expense-confirm", "expense",
                decisionRow = row(
                    ledgerId.value, "req-expense-confirm", "confirm", "candidate-expense", hashExpense,
                    "category-food", null, null, null, "account-credit-huabei", null, null, null, null, confirmedAt,
                ),
            )
            // EXPENSE: expense +10000 / credit liability -10000; zero purchase-day cash outflow.
            expected.formal("expense", "EXPENSE", expenseFacts.occurredAt, Triple("expense-account-food", 10000L, "CNY"), Triple("account-credit-huabei", -10000L, "CNY"))

            // Anchor 2: credit repayment 56.20 — independent CREDIT_REPAYMENT, never ACCOUNT_TRANSFER.
            executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("repay"))), BatchCommitIdSource(listOf(commitIds("repay")))).apply {
                assertIs<ImportIntakeResult.Accepted>(
                    intake(intakeRequest("req-repay", 1, ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repaymentFacts, ImportCompleteness.VALID_COMPLETE, repaymentProfile)),
                )
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    confirmCredit(creditRepaymentConfirmRequest("req-repay-confirm", "candidate-repay", hashRepay)),
                )
            }
            expected.intake("req-repay", 1, ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repaymentFacts, ImportCompleteness.VALID_COMPLETE, repaymentProfile, "repay")
            expected.confirm(
                "req-repay-confirm", "repay",
                decisionRow = row(
                    ledgerId.value, "req-repay-confirm", "confirm", "candidate-repay", hashRepay,
                    null, null, null, null, "account-credit-huabei", "account-asset-a", null, null, null, confirmedAt,
                ),
            )
            expected.formal("repay", "CREDIT_REPAYMENT", repaymentFacts.occurredAt, Triple("account-asset-a", -5620L, "CNY"), Triple("account-credit-huabei", 5620L, "CNY"))

            // Anchor 3: credit refund 15.35 — independent REFUND_RECEIPT linked by decision
            // snapshot original_transaction_id; the original consumption version is untouched.
            executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("refund"))), BatchCommitIdSource(listOf(commitIds("refund")))).apply {
                assertIs<ImportIntakeResult.Accepted>(
                    intake(intakeRequest("req-refund", 2, ImportRecordKind.CREDIT_EXPENSE_SOURCE, refundFacts, ImportCompleteness.VALID_COMPLETE, refundProfile)),
                )
                assertIs<ImportCandidateDecisionResult.Accepted>(
                    confirmCredit(creditRefundConfirmRequest("req-refund-confirm", "candidate-refund", hashRefund, original = "tx-expense")),
                )
            }
            expected.intake("req-refund", 2, ImportRecordKind.CREDIT_EXPENSE_SOURCE, refundFacts, ImportCompleteness.VALID_COMPLETE, refundProfile, "refund")
            expected.confirm(
                "req-refund-confirm", "refund",
                decisionRow = row(
                    ledgerId.value, "req-refund-confirm", "confirm", "candidate-refund", hashRefund,
                    "category-food", null, null, null, "account-credit-huabei", null, "tx-expense", null, null, confirmedAt,
                ),
            )
            expected.formal("refund", "REFUND_RECEIPT", refundFacts.occurredAt, Triple("account-credit-huabei", 1535L, "CNY"), Triple("expense-account-food", -1535L, "CNY"))

            val actual = captureFullState(driver, accounts())
            assertFullState(expected.state(accounts()), actual, "rl05-anchors")

            // Frozen D-058 report effects: consumption 10000-1535=8465, purchase-day cash
            // outflow only the repayment 5620, net-worth change -(net consumption).
            val projection = actual.report.getValue(ledgerId.value)
            assertEquals(8465L, projection.consumptionMinor)
            assertEquals(-5620L, projection.cashOutflowMinor)
            assertEquals(-8465L, projection.netWorthChangeMinor)
            assertEquals(8465L, projection.balancesByAccount.getValue("expense-account-food"))
            assertEquals(-2845L, projection.balancesByAccount.getValue("account-credit-huabei"))
            assertEquals(-5620L, projection.balancesByAccount.getValue("account-asset-a"))

            // The original consumption period is unchanged: the anchor expense keeps
            // exactly one version across its independent refund.
            assertEquals(
                1L,
                selectRows(driver, "SELECT count(*) FROM transaction_version WHERE transaction_id = 'tx-expense'", listOf(true)).single().single(),
            )

            // Evidence cardinality registration (D-107 section 5): each confirmed credit
            // lifecycle carries exactly one linkable real-account posting (1:1).
            assertEquals(3L, database.ledgerQueries.countImportEvidence().executeAsOne())
        } finally {
            driver.close()
        }
    }

    // ---------- Matrix 2: status-gate negative paths stay confirmable-blocked, never rejected ----------

    @Test
    fun familyStatusGatesStayValidIncompleteRawAndUnconfirmable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val pending = listOf(
                Triple("expense", ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts.copy(statusToken = "交易关闭", occurredAt = "2026-08-02T09:00:00+08:00")),
                Triple("repay", ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repaymentFacts.copy(statusToken = "交易成功", occurredAt = "2026-08-02T09:01:00+08:00")),
                Triple("refund", ImportRecordKind.CREDIT_EXPENSE_SOURCE, refundFacts.copy(statusToken = "退款关闭", occurredAt = "2026-08-02T09:02:00+08:00")),
            )
            val profilesByPrefix = mapOf(
                "expense" to directProfile,
                "repay" to repaymentProfile,
                "refund" to refundProfile,
            )
            val intakeBatches = pending.map { (prefix, _, _) -> intakeIds(prefix) }
            val run = executor(database, driver, BatchIntakeIdSource(intakeBatches), BatchCommitIdSource(emptyList()))
            pending.forEachIndexed { index, (prefix, kind, facts) ->
                val result = assertIs<ImportIntakeResult.Accepted>(
                    run.intake(intakeRequest("req-$prefix", index, kind, facts, ImportCompleteness.VALID_INCOMPLETE, profilesByPrefix.getValue(prefix))),
                )
                assertEquals("candidate-$prefix", result.receipt.candidateId.value)
            }
            // Zero rejection upgrade: all three stay incomplete, statuses raw, kinds kept.
            pending.forEachIndexed { _, (prefix, kind, _) ->
                val source = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-$prefix").executeAsOne()
                assertEquals("valid_incomplete", source.completeness)
                assertEquals(kind.storageValue, source.record_kind)
                assertEquals("incomplete", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-$prefix").executeAsOne().status)
            }
            // Confirming an incomplete candidate is the frozen typed rejection.
            val hash = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, pending[0].third, directProfile)
            val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(
                run.confirmCredit(creditExpenseConfirmRequest("req-expense-confirm", "candidate-expense", hash)),
            )
            assertEquals("SPINE_CANDIDATE_INCOMPLETE", rejected.diagnostic.code)
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
        } finally {
            driver.close()
        }
    }

    // ---------- Failure-injection rollback (QUAL-001: v3 rollback covers the profile row) ----------

    @Test
    fun injectedIntakeFailureAfterCandidateRollsBackAllV3RowsAndAcceptsOnRetry() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val hashExpense = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, directProfile)

            // Intake failure after the candidate (and its payment profile) insert: the
            // whole transaction — source, evidence, candidate, profile, requirement,
            // status, duplicate state, request claim and receipt — rolls back together.
            val attempt1 = BatchIntakeIdSource(listOf(intakeIds("inj-expense")))
            val failingRun = executor(
                database, driver, attempt1, BatchCommitIdSource(emptyList()),
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.INTAKE_AFTER_CANDIDATE) error("injected") },
            )
            assertFailsWith<IllegalStateException> {
                failingRun.intake(intakeRequest("req-inj-intake", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile))
            }
            assertEquals(1, attempt1.calls.get())
            // Zero rows in every import owner, including the P4-06 v3 profile table and
            // the P4-02 requirement table that does not expose a generated count query.
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), spineCounts(database))
            assertEquals(listOf(0L), selectRows(driver, "SELECT count(*) FROM import_candidate_requires_confirmation WHERE ledger_id = '${ledgerId.value}'", listOf(true)).single())

            // The claim was rolled back with everything else: the same request identity
            // is retryable and now accepts with a fresh id batch.
            val attempt2 = BatchIntakeIdSource(listOf(intakeIds("req-ok")))
            val retryRun = executor(database, driver, attempt2, BatchCommitIdSource(emptyList()))
            val accepted = assertIs<ImportIntakeResult.Accepted>(
                retryRun.intake(intakeRequest("req-inj-intake", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)),
            )
            assertEquals(listOf("source-req-ok", "evidence-req-ok", "candidate-req-ok"), accepted.returnedIds.map { it.id })
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))

            // Full-state expected for the retried intake (the only committed operation).
            val expected = Expected()
            expected.intake("req-inj-intake", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "req-ok")
            assertFullState(expected.state(accounts()), captureFullState(driver, accounts()), "injected-intake-rollback")
        } finally {
            driver.close()
        }
    }

    @Test
    fun injectedConfirmFailureAfterFormalRollsBackDecisionAndAcceptsOnRetry() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val hashExpense = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, directProfile)

            // Setup: one credit-expense candidate pending.
            val setup = executor(
                database, driver,
                BatchIntakeIdSource(listOf(intakeIds("inj-conf"))), BatchCommitIdSource(emptyList()),
            )
            assertIs<ImportIntakeResult.Accepted>(
                setup.intake(intakeRequest("req-conf-intake", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)),
            )

            // Confirm failure after the formal graph persists: the whole transaction —
            // decision snapshot, status sequence 2, confirmation, receipt, the formal
            // graph (transaction/version/posting set/postings) and the request claim —
            // rolls back together.
            val attempt1 = BatchCommitIdSource(listOf(commitIds("conf-injected")))
            val failingRun = executor(
                database, driver, BatchIntakeIdSource(listOf(intakeIds("inj-conf"))), attempt1,
                ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL) error("injected") },
            )
            assertFailsWith<IllegalStateException> {
                failingRun.confirmCredit(creditExpenseConfirmRequest("req-conf-injected", "candidate-inj-conf", hashExpense))
            }
            assertEquals(1, attempt1.calls.get())
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 1L, 0L, 0L, 1L), spineCounts(database))
            assertEquals(listOf(0L, 0L, 0L), formalCounts(database))
            // No decision snapshot and no second status history row survive the rollback.
            assertEquals(0L, database.ledgerQueries.countImportDecisionSnapshots().executeAsOne())
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-inj-conf").executeAsOne().status)

            // The claim was rolled back: the same request identity retries and accepts
            // with a fresh commit id batch.
            val attempt2 = BatchCommitIdSource(listOf(commitIds("conf-ok")))
            val retryRun = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("inj-conf"))), attempt2)
            val confirmed = assertIs<ImportCandidateDecisionResult.Accepted>(
                retryRun.confirmCredit(creditExpenseConfirmRequest("req-conf-injected", "candidate-inj-conf", hashExpense)),
            )
            assertEquals("tx-conf-ok", confirmed.receipt.transactionId?.value)
            // import_request carries both the intake claim and the retried confirm claim.
            assertEquals(listOf(2L, 1L, 1L, 1L, 1L, 2L, 1L, 1L, 2L), spineCounts(database))
            assertEquals(listOf(1L, 1L, 2L), formalCounts(database))

            val expected = Expected()
            expected.intake("req-conf-intake", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "inj-conf")
            expected.confirm(
                "req-conf-injected", "conf-ok", candidatePrefix = "inj-conf",
                decisionRow = row(
                    ledgerId.value, "req-conf-injected", "confirm", "candidate-inj-conf", hashExpense,
                    "category-food", null, null, null, "account-credit-huabei", null, null, null, null, confirmedAt,
                ),
            )
            expected.formal("conf-ok", "EXPENSE", expenseFacts.occurredAt, Triple("expense-account-food", 10000L, "CNY"), Triple("account-credit-huabei", -10000L, "CNY"))
            assertFullState(expected.state(accounts()), captureFullState(driver, accounts()), "injected-confirm-rollback")
        } finally {
            driver.close()
        }
    }

    // ---------- Matrix 7: duplicates, replay and collision ----------

    @Test
    fun replayDuplicateAndCollisionSemanticsHoldForV3Rows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val hashExpense = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, directProfile)

            // First intake + same-request replay + raw-identity replay: zero new rows.
            val first = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("dup1"))), BatchCommitIdSource(emptyList()))
            val accepted1 = assertIs<ImportIntakeResult.Accepted>(
                first.intake(intakeRequest("req-dup-1", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)),
            )
            val replay = assertIs<ImportIntakeResult.NoChange>(
                first.intake(intakeRequest("req-dup-1", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)),
            )
            assertEquals(accepted1.receipt, replay.receipt)
            assertEquals("equivalent_replay", replay.reasonCode)
            val rawReplay = assertIs<ImportIntakeResult.NoChange>(
                first.intake(intakeRequest("req-dup-1b", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)),
            )
            assertEquals("equivalent_replay", rawReplay.reasonCode)

            // Second equal credit row at another ordinal: P4-07 exact-tuple duplicate
            // candidate (kind-agnostic persisted-facts tuple; refund_settled participates).
            assertIs<ImportIntakeResult.Accepted>(
                executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("dup2", duplicates = listOf("duplicate-dup-2" to "history-duplicate-dup-2")))), BatchCommitIdSource(emptyList())).intake(
                    intakeRequest("req-dup-2", 1, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile),
                ),
            )
            val duplicateRow = database.ledgerQueries.selectDuplicateCandidate(ledgerId.value, "duplicate-dup-2").executeAsOne()
            assertEquals("EXACT_BUSINESS_TUPLE", duplicateRow.kind)
            assertEquals("DEFERRED", database.ledgerQueries.selectDuplicateCurrentStatus(ledgerId.value, "duplicate-dup-2").executeAsOne())

            // Non-equivalent same raw identity: hard collision, zero writes.
            val collision = assertIs<ImportIntakeResult.Rejected>(
                first.intake(intakeRequest("req-dup-3", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts.copy(amountMinor = 10001), ImportCompleteness.VALID_COMPLETE, directProfile)),
            )
            assertEquals("SPINE_IDENTITY_COLLISION", collision.diagnostic.code)

            // Confirm + replay: the original receipt returns, the ID source runs once.
            val commitIds = BatchCommitIdSource(listOf(commitIds("dup1")))
            val confirmRun = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("dup1"))), commitIds)
            val confirmed = assertIs<ImportCandidateDecisionResult.Accepted>(
                confirmRun.confirmCredit(creditExpenseConfirmRequest("req-dup-1-confirm", "candidate-dup1", hashExpense)),
            )
            val confirmReplay = assertIs<ImportCandidateDecisionResult.NoChange>(
                confirmRun.confirmCredit(creditExpenseConfirmRequest("req-dup-1-confirm", "candidate-dup1", hashExpense)),
            )
            assertEquals(confirmed.receipt, confirmReplay.receipt)
            assertEquals(1, commitIds.calls.get())
            // No second transaction and no second posting set for the duplicate row.
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())

            // Full-state check of the whole scenario.
            val expected = Expected()
            expected.intake("req-dup-1", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "dup1")
            expected.intake(
                "req-dup-2", 1, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "dup2",
                duplicates = listOf(Triple("duplicate-dup-2", "history-duplicate-dup-2", "source-dup1")),
            )
            expected.confirm(
                "req-dup-1-confirm", "dup1",
                decisionRow = row(
                    ledgerId.value, "req-dup-1-confirm", "confirm", "candidate-dup1", hashExpense,
                    "category-food", null, null, null, "account-credit-huabei", null, null, null, null, confirmedAt,
                ),
            )
            expected.formal("dup1", "EXPENSE", expenseFacts.occurredAt, Triple("expense-account-food", 10000L, "CNY"), Triple("account-credit-huabei", -10000L, "CNY"))
            assertFullState(expected.state(accounts()), captureFullState(driver, accounts()), "replay-duplicate")
        } finally {
            driver.close()
        }
    }

    // ---------- Matrix 8: confirmation negative paths ----------

    @Test
    fun creditConfirmationNegativePathsStayPendingWithZeroWritesAndClaimRetry() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val hashExpense = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, directProfile)
            val hashRepay = fingerprint.digest(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repaymentFacts, repaymentProfile)
            val hashRefund = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, refundFacts, refundProfile)

            // Setup: direct expense + repayment + refund candidates pending.
            val run = executor(
                database, driver,
                BatchIntakeIdSource(listOf(intakeIds("neg-expense"), intakeIds("neg-repay"), intakeIds("neg-refund"))),
                BatchCommitIdSource((1..9).map { commitIds("neg-attempt-$it") }),
            )
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-neg-expense", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-neg-repay", 1, ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repaymentFacts, ImportCompleteness.VALID_COMPLETE, repaymentProfile)))
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-neg-refund", 2, ImportRecordKind.CREDIT_EXPENSE_SOURCE, refundFacts, ImportCompleteness.VALID_COMPLETE, refundProfile)))

            fun assertDomainFailure(result: ImportCandidateDecisionResult) {
                val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(result)
                assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", rejected.diagnostic.code)
            }

            // Liability account not held / not LIABILITY / cross-ledger / wrong currency.
            assertDomainFailure(run.confirmCredit(creditExpenseConfirmRequest("req-neg-1", "candidate-neg-expense", hashExpense, liability = "account-credit-unknown")))
            assertDomainFailure(run.confirmCredit(creditExpenseConfirmRequest("req-neg-2", "candidate-neg-expense", hashExpense, liability = "account-asset-a")))
            assertDomainFailure(run.confirmCredit(creditExpenseConfirmRequest("req-neg-3", "candidate-neg-expense", hashExpense, liability = "account-credit-other-ledger")))
            assertDomainFailure(run.confirmCredit(creditExpenseConfirmRequest("req-neg-4", "candidate-neg-expense", hashExpense, liability = "account-credit-usd")))
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-neg-expense").executeAsOne().status)

            // Refund variant: missing original, non-EXPENSE original (needs the repayment
            // confirmed first), and category != the original's current secondary category.
            assertDomainFailure(run.confirmCredit(creditRefundConfirmRequest("req-neg-5", "candidate-neg-refund", hashRefund, original = "tx-does-not-exist")))
            assertIs<ImportCandidateDecisionResult.Accepted>(
                executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("neg-repay"))), BatchCommitIdSource(listOf(commitIds("neg-repay")))).confirmCredit(
                    creditRepaymentConfirmRequest("req-neg-repay-confirm", "candidate-neg-repay", hashRepay),
                ),
            )
            assertDomainFailure(run.confirmCredit(creditRefundConfirmRequest("req-neg-6", "candidate-neg-refund", hashRefund, original = "tx-neg-repay")))

            // A USD original expense: the refund currency check fails (validation (i)).
            val usdFacts = ImportSourceFacts(2000, "USD", 2, "2026-08-09T09:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
            val usdHash = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, usdFacts, null)
            val usdCatalog = when (
                val result = LedgerCatalog.create(
                    accounts = listOf(
                        Account(AccountId("usd-asset"), ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true),
                        Account(AccountId("usd-expense-account"), ledgerId, AccountKind.EXPENSE, usd, ownedByUser = false, realAccount = false),
                    ),
                    categories = listOf(
                        Category(CategoryId("category-primary-usd"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                        Category(CategoryId("category-usd"), ledgerId, parentId = CategoryId("category-primary-usd"), postingAccountId = AccountId("usd-expense-account"), active = true, kind = CategoryKind.EXPENSE),
                    ),
                )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> error("usd catalog failure")
            }
            assertIs<ImportIntakeResult.Accepted>(
                executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("neg-usd"))), BatchCommitIdSource(listOf(commitIds("neg-usd")))).intake(
                    intakeRequest("req-neg-usd", 3, ImportRecordKind.ORDINARY_FLOW_SOURCE, usdFacts, ImportCompleteness.VALID_COMPLETE, null),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("neg-usd"))), BatchCommitIdSource(listOf(commitIds("neg-usd")))).confirmOrdinary(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-neg-usd-confirm")),
                        candidateId = ImportCandidateId("candidate-neg-usd"),
                        expectedContentHash = usdHash,
                        explicitConfirmedAt = confirmedAt,
                        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-usd"), AccountId("usd-asset")),
                    ),
                    OrdinaryOutFactory(usdCatalog),
                ),
            )
            assertDomainFailure(run.confirmCredit(creditRefundConfirmRequest("req-neg-7", "candidate-neg-refund", hashRefund, original = "tx-neg-usd")))

            // Category-inheritance rule (ii): original expense confirmed under
            // category-clothes, refund decided with category-food -> domain failure.
            val clothesFacts = expenseFacts.copy(occurredAt = "2026-08-10T09:00:00+08:00")
            val clothesHash = fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, clothesFacts, directProfile)
            assertIs<ImportIntakeResult.Accepted>(
                executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("neg-clothes"))), BatchCommitIdSource(listOf(commitIds("neg-clothes")))).intake(
                    intakeRequest("req-neg-clothes", 4, ImportRecordKind.CREDIT_EXPENSE_SOURCE, clothesFacts, ImportCompleteness.VALID_COMPLETE, directProfile),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("neg-clothes"))), BatchCommitIdSource(listOf(commitIds("neg-clothes")))).confirmCredit(
                    creditExpenseConfirmRequest("req-neg-clothes-confirm", "candidate-neg-clothes", clothesHash, category = "category-clothes"),
                ),
            )
            assertDomainFailure(run.confirmCredit(creditRefundConfirmRequest("req-neg-8", "candidate-neg-refund", hashRefund, original = "tx-neg-clothes")))

            // Kind/variant/decision-field mismatches.
            val kindMismatch1 = assertIs<ImportCandidateDecisionResult.Rejected>(
                run.confirmCredit(creditRepaymentConfirmRequest("req-neg-9", "candidate-neg-expense", hashExpense)),
            )
            assertEquals("SPINE_DECISION_KIND_MISMATCH", kindMismatch1.diagnostic.code)
            val kindMismatch2 = assertIs<ImportCandidateDecisionResult.Rejected>(
                run.confirmCredit(creditRefundConfirmRequest("req-neg-10", "candidate-neg-expense", hashExpense)),
            )
            assertEquals("SPINE_DECISION_KIND_MISMATCH", kindMismatch2.diagnostic.code)

            // Every negative path left the credit candidates pending and zero residue:
            // only the two successful confirmations produced formal rows.
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-neg-expense").executeAsOne().status)
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-neg-refund").executeAsOne().status)
            assertEquals(3L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(6L, database.ledgerQueries.countPostings().executeAsOne())

            // The rolled-back claim stays retryable on the same identity: a corrected
            // decision now succeeds (claim rollback + zero-residue proof).
            val corrected = assertIs<ImportCandidateDecisionResult.Accepted>(
                run.confirmCredit(creditExpenseConfirmRequest("req-neg-1", "candidate-neg-expense", hashExpense)),
            )
            assertEquals("tx-neg-attempt-9", corrected.receipt.transactionId?.value)
        } finally {
            driver.close()
        }
    }

    // ---------- Section 3.2/5 registrations: fingerprint contract + intake profile gate ----------

    @Test
    fun fingerprintContractExtendsAdditivelyAndIntakeGateRejectsBadProfiles() {
        // v1/v2 bytes are unchanged when the profile is null (P406S1 equivalence).
        val v1Json = fingerprint.canonicalJson(ImportRecordKind.ORDINARY_FLOW_SOURCE, expenseFacts)
        assertFalse(v1Json.contains("payment_variant"))
        assertFalse(v1Json.contains("credit_leg_kind_token"))
        assertEquals(
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, expenseFacts),
            fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, expenseFacts, null),
        )

        // v3 members are inserted in ascending name order inside the closed object.
        val v3Json = fingerprint.canonicalJson(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, directProfile)
        val names = Regex("\"([a-z_]+)\":").findAll(v3Json).map { it.groupValues[1] }.toList()
        assertEquals(names, names.sorted())
        assertTrue(v3Json.contains("\"payment_variant\":\"credit_expense_direct\""))
        assertTrue(v3Json.contains("\"credit_leg_kind_token\":\"花呗\""))

        // The profile participates in the digest: different variant -> different digest.
        assertFalse(
            fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, directProfile) ==
                fingerprint.digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, refundProfile),
        )

        // Intake validation gate: v3 without a profile, v1 with a profile, and malformed
        // variant shapes are all SPINE_INTAKE_INVALID with zero writes.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val run = executor(database, driver, BatchIntakeIdSource(emptyList()), BatchCommitIdSource(emptyList()))
            val noProfile = assertIs<ImportIntakeResult.Rejected>(
                run.intake(intakeRequest("req-gate-1", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, null)),
            )
            assertEquals("SPINE_INTAKE_INVALID", noProfile.diagnostic.code)
            val withProfile = assertIs<ImportIntakeResult.Rejected>(
                run.intake(intakeRequest("req-gate-2", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)),
            )
            assertEquals("SPINE_INTAKE_INVALID", withProfile.diagnostic.code)
            val badShape = assertIs<ImportIntakeResult.Rejected>(
                run.intake(intakeRequest("req-gate-3", 0, ImportRecordKind.CREDIT_REPAYMENT_SOURCE, repaymentFacts, ImportCompleteness.VALID_COMPLETE, repaymentProfile.copy(creditLegKindToken = "花呗"))),
            )
            assertEquals("SPINE_INTAKE_INVALID", badShape.diagnostic.code)
            val mixedShape = assertIs<ImportIntakeResult.Rejected>(
                run.intake(
                    intakeRequest(
                        "req-gate-4", 0, ImportRecordKind.MIXED_PAYMENT_SOURCE, expenseFacts, ImportCompleteness.VALID_COMPLETE,
                        // A well-formed mixed profile (both legs non-null) is a frozen-valid
                        // shape (D-107 section 3.2); the gate rejects only malformed ones.
                        ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, null, "花呗"),
                    ),
                ),
            )
            assertEquals("SPINE_INTAKE_INVALID", mixedShape.diagnostic.code)
            assertEquals(0L, database.ledgerQueries.countImportSourceRecords().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countImportCandidates().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countImportCandidatePaymentProfiles().executeAsOne())
        } finally {
            driver.close()
        }
    }

    private fun row(vararg values: Any?): List<Any?> = values.toList()
}
