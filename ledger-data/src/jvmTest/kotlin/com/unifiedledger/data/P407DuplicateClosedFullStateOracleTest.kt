package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import com.unifiedledger.application.ImportDuplicateReviewFingerprint
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateStatus
import com.unifiedledger.application.ImportFormalCommit
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
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.ReviewImportDuplicateCandidate
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
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * P4-07 canonical full-state oracle (D-105 section 6).
 *
 * Every scenario compares the complete persisted state against a test-side expected
 * builder constructed only from the frozen fixtures and contracts: all import
 * source/evidence/candidate/status/confirmation/receipt owners, all duplicate
 * candidate/status/review snapshot/receipt owners, the formal graph, account balances,
 * the report projection, and the P4-08 reconciliation state. The covered matrix gaps:
 *
 * - 1a: same-request and same-raw-identity replays add zero duplicate rows; identity
 *   collision stays a hard reject with zero writes.
 * - 3b: multiple lookalikes select no winner; after CONFIRMED_DISTINCT and
 *   DISMISSED_LOOKALIKE every source still formalizes independently.
 * - 5c: stale fingerprint, non-deferred review, concurrent claim loser and injected
 *   review failure all leave zero residue and a retryable identity.
 * - 5d: exact-tuple and NULL-target NO_FUNDS candidates keep retry/concurrency
 *   uniqueness (one row per directed pair / per subject).
 * - 6: NO_FUNDS disposal leaves zero formal, balance, report and reconciliation effect.
 * - 7: unknown/refund/non-settled ambiguity stays UNRESOLVED, never NO_FUNDS.
 */
class P407DuplicateClosedFullStateOracleTest {
    private val ledgerId = LedgerId("ledger-p407-oracle")
    private val cny = CurrencyUnit("CNY", 2)
    private val fingerprint = ImportContentFingerprint()
    private val comparisonFingerprint = ImportDuplicateComparisonFingerprint()

    // Frozen tuple X: three distinct raw identities share it exactly.
    private val tupleXFacts =
        ImportSourceFacts(
            12850,
            "CNY",
            2,
            "2026-08-01T12:30:00+08:00",
            "out",
            "settled",
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )
    private val hashTupleX = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, tupleXFacts)
    private val generatedAt = "2026-08-19T08:00:00Z"

    // Matrix 7 fixture: a refund-pending row whose funding state is explicitly UNRESOLVED.
    private val unresolvedFacts =
        ImportSourceFacts(
            7700,
            "CNY",
            2,
            "2026-08-02T09:00:00+08:00",
            "out",
            "refund-pending",
            ImportFundingState.UNRESOLVED,
            "source-contract-unresolved-v1",
            1,
        )
    private val hashUnresolved = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, unresolvedFacts)

    // Matrix 6/5d fixture: a source-contract-proved closed record with no funds movement.
    private val noFundsFacts =
        ImportSourceFacts(
            9900,
            "CNY",
            2,
            "2026-08-03T10:00:00+08:00",
            "out",
            "closed",
            ImportFundingState.NO_FUNDS,
            "source-contract-closed-v1",
            1,
        )
    private val hashNoFunds = fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, noFundsFacts)

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(AccountId("account-asset-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(AccountId("expense-account-food"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories =
                        listOf(
                            Category(CategoryId("category-primary-food"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                            Category(CategoryId("category-food"), ledgerId, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("p407 oracle catalog failure: ${result.violation}")
        }

    private fun accounts(): List<Account> = catalog().accounts

    private fun intakeRequest(
        requestId: String,
        inputRef: String,
        facts: ImportSourceFacts,
        completeness: ImportCompleteness = ImportCompleteness.VALID_COMPLETE,
        candidateGeneratedAt: String = generatedAt,
    ) = ImportIntakeRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef = inputRef,
        recordOrdinal = 0,
        recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
        facts = facts,
        completeness = completeness,
        candidateGeneratedAt = candidateGeneratedAt,
    )

    private fun intakeIds(
        prefix: String,
        statusId: String,
        duplicates: List<Pair<String, String>> = emptyList(),
    ) = ImportIntakeIds(
        sourceId = ImportSourceId("source-$prefix"),
        evidenceId = com.unifiedledger.application.ImportEvidenceId("evidence-$prefix"),
        candidateId = ImportCandidateId("candidate-$prefix"),
        statusHistoryId = ImportStatusHistoryId(statusId),
        duplicateIds =
            duplicates.map { (candidateId, historyId) ->
                ImportDuplicateIntakeIds(ImportDuplicateCandidateId(candidateId), ImportStatusHistoryId(historyId))
            },
    )

    private fun commitIds(prefix: String) =
        ImportCommitIds(
            confirmationId = ImportConfirmationId("confirmation-$prefix"),
            statusHistoryId = ImportStatusHistoryId("status-$prefix-2"),
            formalIds =
                ImportFormalIds(
                    transactionId = TransactionId("tx-$prefix"),
                    versionId = TransactionVersionId("version-$prefix-v1"),
                    postingSetId = PostingSetId("posting-set-$prefix"),
                    postingIds = listOf(PostingId("posting-$prefix-expense"), PostingId("posting-$prefix-asset")),
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

    private class OrdinaryFlowFormalFactory(
        private val catalog: LedgerCatalog,
        private val categoryId: CategoryId,
        private val fundingAccountId: AccountId,
    ) : ImportCandidateFormalFactory {
        private val delegate = com.unifiedledger.application.OrdinaryFlowFormalFactory(catalog)

        override fun create(
            input: ImportCandidateFormalizationInput,
            ids: ImportCommitIds,
        ): DomainResult<ImportFormalCommit> = delegate.create(input, ids)
    }

    private fun confirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        confirmedAt: String = "2026-08-20T10:00:00+08:00",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), AccountId("account-asset-a")),
    )

    private fun reviewRequest(
        requestId: String,
        candidate: String,
        expectedFingerprint: String,
        decision: ImportDuplicateStatus,
        reasonToken: String,
    ) = ImportDuplicateReviewRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportDuplicateCandidateId(candidate),
        expectedComparisonFingerprint = expectedFingerprint,
        decision = decision,
        reasonToken = reasonToken,
        reviewedAt = "2026-08-20T11:00:00+08:00",
        reviewerReference = "reviewer-p407",
        generatedAt = "2026-08-20T11:00:00+08:00",
        reviewId = ImportDuplicateReviewId("review-$requestId"),
        historyId = ImportStatusHistoryId("review-history-$requestId"),
    )

    // ---------- canonical capture (every P4-07/P4-02/P4-08 owner) ----------

    private data class P407ReportProjection(
        val balancesByAccount: Map<String, Long>,
        val internalTransferMinor: Long,
        val externalIncomeMinor: Long,
        val externalExpenseMinor: Long,
        val consumptionMinor: Long,
        val netWorthChangeMinor: Long,
    )

    private data class ReportTx(
        val ledgerId: String,
        val kind: String,
        val postings: List<Pair<String, Long>>,
    )

    private data class P407FullState(
        val importRequest: List<List<Any?>>,
        val importSourceRecord: List<List<Any?>>,
        val importEvidence: List<List<Any?>>,
        val importCandidate: List<List<Any?>>,
        val importCandidateRequiresConfirmation: List<List<Any?>>,
        val importCandidateStatusHistory: List<List<Any?>>,
        val importCandidateDecisionSnapshot: List<List<Any?>>,
        val importConfirmation: List<List<Any?>>,
        val importReceipt: List<List<Any?>>,
        val duplicateCandidate: List<List<Any?>>,
        val duplicateStatusHistory: List<List<Any?>>,
        val duplicateReviewRequest: List<List<Any?>>,
        val duplicateReviewSnapshot: List<List<Any?>>,
        val duplicateReviewReceipt: List<List<Any?>>,
        val ledgerTransaction: List<List<Any?>>,
        val postingSet: List<List<Any?>>,
        val transactionVersion: List<List<Any?>>,
        val ledgerTransactionCurrentVersion: List<List<Any?>>,
        val posting: List<List<Any?>>,
        val report: Map<String, P407ReportProjection>,
        val reconciliation: Map<String, List<List<Any?>>>,
    )

    private val rowComparator =
        Comparator<List<Any?>> { left, right ->
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val l = left.getOrNull(index)
                val r = right.getOrNull(index)
                val compare =
                    when {
                        l == null && r == null -> 0
                        l == null -> -1
                        r == null -> 1
                        else -> l.toString().compareTo(r.toString())
                    }
                if (compare != 0) return@Comparator compare
            }
            0
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

    private fun captureFullState(
        driver: JdbcSqliteDriver,
        accounts: List<Account>,
    ): P407FullState {
        val formalJoin =
            "FROM posting AS p " +
                "JOIN transaction_version AS v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
                "JOIN ledger_transaction AS t ON t.transaction_id = v.transaction_id AND t.ledger_id = p.ledger_id"
        val formalRows =
            selectRows(
                driver,
                "SELECT t.transaction_id, t.ledger_id, t.kind, p.account_id, p.amount_minor $formalJoin ORDER BY t.transaction_id, p.posting_index",
                listOf(false, false, false, false, true),
            )
        val formalTxs =
            formalRows
                .groupBy { it[0] as String }
                .map { (_, rows) ->
                    ReportTx(
                        ledgerId = rows.first()[1] as String,
                        kind = rows.first()[2] as String,
                        postings = rows.map { (it[3] as String) to (it[4] as Long) },
                    )
                }
        val report =
            (accounts.map { it.ledgerId.value }.toSortedSet() + formalTxs.map { it.ledgerId })
                .associateWith { ledger -> reduceReport(formalTxs, ledger, accounts) }
        return P407FullState(
            importRequest = selectRows(driver, "SELECT ledger_id, request_id, operation FROM import_request", listOf(false, false, false)).sortedWith(rowComparator),
            importSourceRecord =
                selectRows(
                    driver,
                    "SELECT ledger_id, source_id, owner_request_id, input_ref, record_ordinal, record_kind, content_hash, contract_version, completeness, amount_minor, currency_code, currency_precision, occurred_at, direction_token, status_token, funding_state, funding_rule_id, funding_rule_version, candidate_generated_at FROM import_source_record",
                    listOf(false, false, false, false, true, false, false, true, false, true, false, true, false, false, false, false, false, true, false),
                ).sortedWith(rowComparator),
            importEvidence = selectRows(driver, "SELECT ledger_id, evidence_id, source_id, evidence_kind, observed_at FROM import_evidence", listOf(false, false, false, false, false)).sortedWith(rowComparator),
            importCandidate = selectRows(driver, "SELECT ledger_id, candidate_id, source_id, candidate_kind, confidence, rule, rule_version FROM import_candidate", listOf(false, false, false, false, false, false, true)).sortedWith(rowComparator),
            importCandidateRequiresConfirmation = selectRows(driver, "SELECT ledger_id, candidate_id, requirement_index, requirement FROM import_candidate_requires_confirmation", listOf(false, false, true, false)).sortedWith(rowComparator),
            importCandidateStatusHistory = selectRows(driver, "SELECT ledger_id, candidate_id, sequence, status_id, status, request_id, operation_class FROM import_candidate_status_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
            importCandidateDecisionSnapshot = selectRows(driver, "SELECT ledger_id, request_id, decision, candidate_id, expected_content_hash, category_id, funding_account_id, from_account_id, to_account_id, explicit_confirmed_at FROM import_candidate_decision_snapshot", listOf(false, false, false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            importConfirmation = selectRows(driver, "SELECT ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class, confirmed_at FROM import_confirmation", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            importReceipt = selectRows(driver, "SELECT ledger_id, request_id, outcome, source_id, evidence_id, candidate_id, confirmation_id, transaction_id FROM import_receipt", listOf(false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            duplicateCandidate =
                selectRows(
                    driver,
                    "SELECT ledger_id, candidate_id, subject_source_id, possible_existing_source_id, kind, comparison_fingerprint, comparison_snapshot, provenance, confidence, rule_id, rule_version, generated_at, creation_request_id FROM import_duplicate_candidate",
                    listOf(false, false, false, false, false, false, false, false, false, false, true, false, false),
                ).sortedWith(rowComparator),
            duplicateStatusHistory = selectRows(driver, "SELECT ledger_id, candidate_id, sequence, history_id, status, request_id, operation_class FROM import_duplicate_status_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
            duplicateReviewRequest = selectRows(driver, "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM import_duplicate_review_request", listOf(false, false, false, false, false, false)).sortedWith(rowComparator),
            duplicateReviewSnapshot = selectRows(driver, "SELECT ledger_id, request_id, candidate_id, expected_comparison_fingerprint, decision, reason_token, reviewed_at, reviewer_reference, generated_at, review_id FROM import_duplicate_review_snapshot", listOf(false, false, false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            duplicateReviewReceipt = selectRows(driver, "SELECT ledger_id, request_id, candidate_id, review_id, history_id, outcome FROM import_duplicate_review_receipt", listOf(false, false, false, false, false, false)).sortedWith(rowComparator),
            ledgerTransaction = selectRows(driver, "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction", listOf(false, false, false, false)).sortedWith(rowComparator),
            postingSet = selectRows(driver, "SELECT posting_set_id, ledger_id FROM posting_set", listOf(false, false)).sortedWith(rowComparator),
            transactionVersion = selectRows(driver, "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note FROM transaction_version", listOf(false, false, false, true, false, false, false, false, false)).sortedWith(rowComparator),
            ledgerTransactionCurrentVersion = selectRows(driver, "SELECT transaction_id, ledger_id, current_version_id FROM ledger_transaction_current_version", listOf(false, false, false)).sortedWith(rowComparator),
            posting = selectRows(driver, "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting", listOf(false, false, false, true, false, true, false, true)).sortedWith(rowComparator),
            report = report,
            reconciliation =
                mapOf(
                    "reconciliation_request" to selectRows(driver, "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM reconciliation_request", listOf(false, false, false, false, false, false)),
                    "reconciliation_request_snapshot" to selectRows(driver, "SELECT ledger_id, request_id FROM reconciliation_request_snapshot", listOf(false, false)),
                    "reconciliation_receipt" to selectRows(driver, "SELECT ledger_id, request_id, outcome FROM reconciliation_receipt", listOf(false, false, false)),
                    "evidence_link" to selectRows(driver, "SELECT ledger_id, link_id FROM evidence_link", listOf(false, false)),
                    "evidence_link_history" to selectRows(driver, "SELECT ledger_id, link_id, sequence FROM evidence_link_history", listOf(false, false, true)),
                    "posting_reconciliation" to selectRows(driver, "SELECT ledger_id, reconciliation_id, posting_id, status, latest_sequence FROM posting_reconciliation", listOf(false, false, false, false, true)),
                    "posting_reconciliation_history" to selectRows(driver, "SELECT ledger_id, reconciliation_id, sequence FROM posting_reconciliation_history", listOf(false, false, true)),
                ),
        )
    }

    private fun reduceReport(
        transactions: List<ReportTx>,
        ledgerId: String,
        accounts: List<Account>,
    ): P407ReportProjection {
        val balances = LinkedHashMap<String, Long>()
        accounts.filter { it.ledgerId.value == ledgerId }.forEach { balances[it.id.value] = 0L }
        var internalTransfer = 0L
        var externalIncome = 0L
        var externalExpense = 0L
        var netWorth = 0L
        transactions.filter { it.ledgerId == ledgerId }.forEach { tx ->
            tx.postings.forEach { (accountId, amount) -> balances[accountId] = (balances[accountId] ?: 0L) + amount }
            val positiveTotal = tx.postings.sumOf { if (it.second > 0L) it.second else 0L }
            when (tx.kind) {
                "ACCOUNT_TRANSFER" -> internalTransfer += positiveTotal
                "EXPENSE" -> {
                    externalExpense += positiveTotal
                    netWorth -= positiveTotal
                }
                "INCOME" -> {
                    externalIncome += positiveTotal
                    netWorth += positiveTotal
                }
                else -> Unit
            }
        }
        return P407ReportProjection(balances, internalTransfer, externalIncome, externalExpense, externalExpense, netWorth)
    }

    private fun assertFullState(
        expected: P407FullState,
        actual: P407FullState,
        checkpoint: String,
    ) {
        assertEquals(expected.importRequest, actual.importRequest, "$checkpoint: import_request")
        assertEquals(expected.importSourceRecord, actual.importSourceRecord, "$checkpoint: import_source_record")
        assertEquals(expected.importEvidence, actual.importEvidence, "$checkpoint: import_evidence")
        assertEquals(expected.importCandidate, actual.importCandidate, "$checkpoint: import_candidate")
        assertEquals(expected.importCandidateRequiresConfirmation, actual.importCandidateRequiresConfirmation, "$checkpoint: import_candidate_requires_confirmation")
        assertEquals(expected.importCandidateStatusHistory, actual.importCandidateStatusHistory, "$checkpoint: import_candidate_status_history")
        assertEquals(expected.importCandidateDecisionSnapshot, actual.importCandidateDecisionSnapshot, "$checkpoint: import_candidate_decision_snapshot")
        assertEquals(expected.importConfirmation, actual.importConfirmation, "$checkpoint: import_confirmation")
        assertEquals(expected.importReceipt, actual.importReceipt, "$checkpoint: import_receipt")
        assertEquals(expected.duplicateCandidate, actual.duplicateCandidate, "$checkpoint: import_duplicate_candidate")
        assertEquals(expected.duplicateStatusHistory, actual.duplicateStatusHistory, "$checkpoint: import_duplicate_status_history")
        assertEquals(expected.duplicateReviewRequest, actual.duplicateReviewRequest, "$checkpoint: import_duplicate_review_request")
        assertEquals(expected.duplicateReviewSnapshot, actual.duplicateReviewSnapshot, "$checkpoint: import_duplicate_review_snapshot")
        assertEquals(expected.duplicateReviewReceipt, actual.duplicateReviewReceipt, "$checkpoint: import_duplicate_review_receipt")
        assertEquals(expected.ledgerTransaction, actual.ledgerTransaction, "$checkpoint: ledger_transaction")
        assertEquals(expected.postingSet, actual.postingSet, "$checkpoint: posting_set")
        assertEquals(expected.transactionVersion, actual.transactionVersion, "$checkpoint: transaction_version")
        assertEquals(expected.ledgerTransactionCurrentVersion, actual.ledgerTransactionCurrentVersion, "$checkpoint: ledger_transaction_current_version")
        assertEquals(expected.posting, actual.posting, "$checkpoint: posting")
        assertEquals(expected.report, actual.report, "$checkpoint: report projection")
        assertEquals(expected.reconciliation, actual.reconciliation, "$checkpoint: P4-08 reconciliation state")
    }

    // ---------- test-side expected builder (never reads the database under test) ----------

    private fun tupleComparisonJson(
        subjectSourceId: String,
        existingSourceId: String?,
    ): String {
        val projection =
            ImportDuplicateComparisonSnapshot(
                subjectSourceId = ImportSourceId(subjectSourceId),
                possibleExistingSourceId = existingSourceId?.let(::ImportSourceId),
                recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                contractVersion = ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion,
                amountMinor = tupleXFacts.amountMinor,
                currencyCode = tupleXFacts.currencyCode,
                currencyPrecision = tupleXFacts.currencyPrecision,
                occurredAt = tupleXFacts.occurredAt,
                directionToken = tupleXFacts.directionToken,
                statusToken = tupleXFacts.statusToken,
            )
        val target = if (existingSourceId == null) "null" else "\"$existingSourceId\""
        return "{\"possible_existing_source_id\":$target,\"subject_source_id\":\"$subjectSourceId\",\"tuple\":${comparisonFingerprint.canonicalJson(projection)}}"
    }

    private fun tupleComparisonDigest(
        subjectSourceId: String,
        existingSourceId: String,
    ): String {
        val projection =
            ImportDuplicateComparisonSnapshot(
                subjectSourceId = ImportSourceId(subjectSourceId),
                possibleExistingSourceId = ImportSourceId(existingSourceId),
                recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                contractVersion = ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion,
                amountMinor = tupleXFacts.amountMinor,
                currencyCode = tupleXFacts.currencyCode,
                currencyPrecision = tupleXFacts.currencyPrecision,
                occurredAt = tupleXFacts.occurredAt,
                directionToken = tupleXFacts.directionToken,
                statusToken = tupleXFacts.statusToken,
            )
        return comparisonFingerprint.digest(projection)
    }

    private fun noFundsComparisonDigest(subjectSourceId: String) = "sha256:no-funds-$subjectSourceId"

    private fun noFundsComparisonJson(
        facts: ImportSourceFacts,
        subjectSourceId: String,
    ): String {
        val projection =
            ImportDuplicateComparisonSnapshot(
                subjectSourceId = ImportSourceId(subjectSourceId),
                possibleExistingSourceId = null,
                recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                contractVersion = ImportRecordKind.ORDINARY_FLOW_SOURCE.contractVersion,
                amountMinor = facts.amountMinor,
                currencyCode = facts.currencyCode,
                currencyPrecision = facts.currencyPrecision,
                occurredAt = facts.occurredAt,
                directionToken = facts.directionToken,
                statusToken = facts.statusToken,
            )
        return "{\"possible_existing_source_id\":null,\"subject_source_id\":\"$subjectSourceId\",\"tuple\":${comparisonFingerprint.canonicalJson(projection)}}"
    }

    private inner class Expected {
        val requests = mutableListOf<List<Any?>>()
        val sources = mutableListOf<List<Any?>>()
        val evidence = mutableListOf<List<Any?>>()
        val candidates = mutableListOf<List<Any?>>()
        val requirements = mutableListOf<List<Any?>>()
        val statusHistory = mutableListOf<List<Any?>>()
        val decisions = mutableListOf<List<Any?>>()
        val confirmations = mutableListOf<List<Any?>>()
        val receipts = mutableListOf<List<Any?>>()
        val duplicateCandidates = mutableListOf<List<Any?>>()
        val duplicateHistory = mutableListOf<List<Any?>>()
        val reviewRequests = mutableListOf<List<Any?>>()
        val reviewSnapshots = mutableListOf<List<Any?>>()
        val reviewReceipts = mutableListOf<List<Any?>>()
        val transactions = mutableListOf<List<Any?>>()
        val postingSets = mutableListOf<List<Any?>>()
        val versions = mutableListOf<List<Any?>>()
        val currentVersions = mutableListOf<List<Any?>>()
        val postings = mutableListOf<List<Any?>>()
        val formalTxs = mutableListOf<ReportTx>()

        fun intake(
            ledgerId: String,
            requestId: String,
            inputRef: String,
            hash: String,
            facts: ImportSourceFacts,
            completeness: ImportCompleteness,
            prefix: String,
            generatedAt: String,
            duplicates: List<Triple<String, String, String?>> = emptyList(),
        ) {
            val complete = completeness == ImportCompleteness.VALID_COMPLETE
            val settled = facts.fundingState == ImportFundingState.SETTLED
            requests += row(ledgerId, requestId, "intake")
            sources +=
                row(
                    ledgerId,
                    "source-$prefix",
                    requestId,
                    inputRef,
                    0L,
                    ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue,
                    hash,
                    1L,
                    if (complete) "valid_complete" else "valid_incomplete",
                    facts.amountMinor,
                    facts.currencyCode,
                    facts.currencyPrecision.toLong(),
                    facts.occurredAt,
                    facts.directionToken,
                    facts.statusToken,
                    facts.fundingState.name,
                    facts.fundingRuleId,
                    facts.fundingRuleVersion.toLong(),
                    generatedAt,
                )
            evidence += row(ledgerId, "evidence-$prefix", "source-$prefix", "source_observation", facts.occurredAt)
            candidates += row(ledgerId, "candidate-$prefix", "source-$prefix", "ordinary_flow", if (complete) "1.00" else "0.50", ImportRecordKind.ORDINARY_FLOW_SOURCE.storageValue, 1L)
            requirements += row(ledgerId, "candidate-$prefix", 0L, "formal_transaction_creation")
            statusHistory +=
                row(
                    ledgerId,
                    "candidate-$prefix",
                    1L,
                    "status-$prefix-1",
                    if (complete && settled) "pending_confirmation" else "incomplete",
                    requestId,
                    "creation",
                )
            receipts += row(ledgerId, requestId, "accepted", "source-$prefix", "evidence-$prefix", "candidate-$prefix", null, null)
            duplicates.forEach { (candidateId, historyId, existingSourceId) ->
                if (existingSourceId == null) {
                    duplicateCandidates +=
                        row(
                            ledgerId,
                            candidateId,
                            "source-$prefix",
                            null,
                            "CLOSED_OR_FAILED_NO_FUNDS",
                            noFundsComparisonDigest("source-$prefix"),
                            noFundsComparisonJson(facts, "source-$prefix"),
                            "source_declared + mechanical_decode",
                            "exact",
                            "p407_exact_business_tuple_v1",
                            1L,
                            generatedAt,
                            requestId,
                        )
                } else {
                    duplicateCandidates +=
                        row(
                            ledgerId,
                            candidateId,
                            "source-$prefix",
                            existingSourceId,
                            "EXACT_BUSINESS_TUPLE",
                            tupleComparisonDigest("source-$prefix", existingSourceId),
                            tupleComparisonJson("source-$prefix", existingSourceId),
                            "source_declared + mechanical_decode + p407_exact_business_tuple_v1",
                            "exact",
                            "p407_exact_business_tuple_v1",
                            1L,
                            generatedAt,
                            requestId,
                        )
                }
                duplicateHistory += row(ledgerId, candidateId, 1L, historyId, "DEFERRED", requestId, "creation")
            }
        }

        fun confirm(
            ledgerId: String,
            requestId: String,
            candidatePrefix: String,
            hash: String,
            facts: ImportSourceFacts,
            confirmedAt: String?,
        ) {
            val timeText = Instant.parse(facts.occurredAt).toString()
            requests += row(ledgerId, requestId, "confirm_candidate")
            decisions += row(ledgerId, requestId, "confirm", "candidate-$candidatePrefix", hash, "category-food", "account-asset-a", null, null, confirmedAt)
            statusHistory += row(ledgerId, "candidate-$candidatePrefix", 2L, "status-$candidatePrefix-2", "confirmed", requestId, "creation")
            confirmations += row(ledgerId, "confirmation-$candidatePrefix", requestId, "candidate-$candidatePrefix", "status-$candidatePrefix-2", "tx-$candidatePrefix", "creation", confirmedAt)
            receipts += row(ledgerId, requestId, "accepted", null, null, "candidate-$candidatePrefix", "confirmation-$candidatePrefix", "tx-$candidatePrefix")
            transactions += row("tx-$candidatePrefix", ledgerId, "EXPENSE", null)
            postingSets += row("posting-set-$candidatePrefix", ledgerId)
            versions += row("version-$candidatePrefix-v1", "tx-$candidatePrefix", ledgerId, 1L, "posting-set-$candidatePrefix", timeText, timeText, timeText, "")
            currentVersions += row("tx-$candidatePrefix", ledgerId, "version-$candidatePrefix-v1")
            postings += row("posting-$candidatePrefix-expense", "posting-set-$candidatePrefix", ledgerId, 0L, "expense-account-food", facts.amountMinor, facts.currencyCode, facts.currencyPrecision.toLong())
            postings += row("posting-$candidatePrefix-asset", "posting-set-$candidatePrefix", ledgerId, 1L, "account-asset-a", -facts.amountMinor, facts.currencyCode, facts.currencyPrecision.toLong())
            formalTxs += ReportTx(ledgerId, "EXPENSE", listOf("expense-account-food" to facts.amountMinor, "account-asset-a" to -facts.amountMinor))
        }

        fun review(
            ledgerId: String,
            request: ImportDuplicateReviewRequest,
            digest: String,
        ) {
            reviewRequests += row(ledgerId, request.identity.requestId.value, "review_duplicate", digest, "ACCEPTED", null)
            reviewSnapshots +=
                row(
                    ledgerId,
                    request.identity.requestId.value,
                    request.candidateId.value,
                    request.expectedComparisonFingerprint,
                    request.decision.name,
                    request.reasonToken,
                    request.reviewedAt,
                    request.reviewerReference,
                    request.generatedAt,
                    request.reviewId.value,
                )
            duplicateHistory +=
                row(
                    ledgerId,
                    request.candidateId.value,
                    2L,
                    request.historyId.value,
                    request.decision.name,
                    request.identity.requestId.value,
                    "status_transition",
                )
            reviewReceipts +=
                row(
                    ledgerId,
                    request.identity.requestId.value,
                    request.candidateId.value,
                    request.reviewId.value,
                    request.historyId.value,
                    request.decision.name,
                )
        }

        fun state(accounts: List<Account>): P407FullState {
            val report =
                (accounts.map { it.ledgerId.value }.toSortedSet() + formalTxs.map { it.ledgerId })
                    .associateWith { ledger -> reduceReport(formalTxs, ledger, accounts) }
            return P407FullState(
                importRequest = requests.sortedWith(rowComparator),
                importSourceRecord = sources.sortedWith(rowComparator),
                importEvidence = evidence.sortedWith(rowComparator),
                importCandidate = candidates.sortedWith(rowComparator),
                importCandidateRequiresConfirmation = requirements.sortedWith(rowComparator),
                importCandidateStatusHistory = statusHistory.sortedWith(rowComparator),
                importCandidateDecisionSnapshot = decisions.sortedWith(rowComparator),
                importConfirmation = confirmations.sortedWith(rowComparator),
                importReceipt = receipts.sortedWith(rowComparator),
                duplicateCandidate = duplicateCandidates.sortedWith(rowComparator),
                duplicateStatusHistory = duplicateHistory.sortedWith(rowComparator),
                duplicateReviewRequest = reviewRequests.sortedWith(rowComparator),
                duplicateReviewSnapshot = reviewSnapshots.sortedWith(rowComparator),
                duplicateReviewReceipt = reviewReceipts.sortedWith(rowComparator),
                ledgerTransaction = transactions.sortedWith(rowComparator),
                postingSet = postingSets.sortedWith(rowComparator),
                transactionVersion = versions.sortedWith(rowComparator),
                ledgerTransactionCurrentVersion = currentVersions.sortedWith(rowComparator),
                posting = postings.sortedWith(rowComparator),
                report = report,
                // P4-07 operations never write P4-08 state: every reconciliation owner
                // stays empty in the expected projection.
                reconciliation =
                    listOf(
                        "reconciliation_request",
                        "reconciliation_request_snapshot",
                        "reconciliation_receipt",
                        "evidence_link",
                        "evidence_link_history",
                        "posting_reconciliation",
                        "posting_reconciliation_history",
                    ).associateWith { emptyList<List<Any?>>() },
            )
        }

        private fun row(vararg values: Any?): List<Any?> = values.toList()
    }

    private fun reviewFingerprint(request: ImportDuplicateReviewRequest): String = ImportDuplicateReviewFingerprint().digest(request)

    // ---------- Matrix 1a ----------

    @Test
    fun matrix1aReplaysAddZeroDuplicateRowsAndCollisionStaysHardReject() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val accounts = accounts()
            val store = SqlDelightImportSpineStore(database, driver)
            val expected = Expected()

            // S1: first tuple-X source, no duplicates yet.
            val intakeIds = BatchIntakeIdSource(listOf(intakeIds("s1", "status-s1-1")))
            val intake = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o1", "batch-oracle-1", tupleXFacts)))
            expected.intake(ledgerId.value, "req-o1", "batch-oracle-1", hashTupleX, tupleXFacts, ImportCompleteness.VALID_COMPLETE, "s1", generatedAt)
            var state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "1a after S1")

            // Same-request replay: zero new rows of any kind (also zero duplicate rows).
            assertIs<ImportIntakeResult.NoChange>(intake.execute(intakeRequest("req-o1", "batch-oracle-1", tupleXFacts)))
            assertFullState(state, captureFullState(driver, accounts), "1a same-request replay")

            // Same raw identity, different request, different audit timestamp (SPEC-003):
            // still a zero-write NoChange; candidate_generated_at is not a source fact.
            assertIs<ImportIntakeResult.NoChange>(
                intake.execute(intakeRequest("req-o1-replay", "batch-oracle-1", tupleXFacts, candidateGeneratedAt = "2026-08-21T00:00:00Z")),
            )
            assertFullState(state, captureFullState(driver, accounts), "1a raw-identity replay")

            // Same raw identity, different content: hard collision, zero writes.
            val collision =
                assertIs<ImportIntakeResult.Rejected>(
                    intake.execute(
                        intakeRequest("req-o1-collision", "batch-oracle-1", tupleXFacts.copy(amountMinor = 12851), candidateGeneratedAt = "2026-08-21T00:00:00Z"),
                    ),
                )
            assertEquals("SPINE_IDENTITY_COLLISION", collision.diagnostic.code)
            assertFullState(state, captureFullState(driver, accounts), "1a identity collision")

            // S2: same exact tuple, new raw identity -> exactly one directed duplicate row.
            val intake2 =
                BatchIntakeIdSource(
                    listOf(intakeIds("s2", "status-s2-1", duplicates = listOf("duplicate-s2-s1" to "duplicate-status-s2-s1"))),
                )
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(store, intake2, ImportContentFingerprint()).execute(intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)),
            )
            expected.intake(
                ledgerId.value,
                "req-o2",
                "batch-oracle-2",
                hashTupleX,
                tupleXFacts,
                ImportCompleteness.VALID_COMPLETE,
                "s2",
                generatedAt,
                duplicates = listOf(Triple("duplicate-s2-s1", "duplicate-status-s2-s1", "source-s1")),
            )
            state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "1a after S2")

            // Replays of S2 (same request and raw identity) add zero duplicate rows.
            assertIs<ImportIntakeResult.NoChange>(
                ExecuteImportIntake(store, BatchIntakeIdSource(emptyList()), ImportContentFingerprint()).execute(intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)),
            )
            assertIs<ImportIntakeResult.NoChange>(
                ExecuteImportIntake(store, BatchIntakeIdSource(emptyList()), ImportContentFingerprint()).execute(
                    intakeRequest("req-o2-replay", "batch-oracle-2", tupleXFacts, candidateGeneratedAt = "2026-08-21T00:00:00Z"),
                ),
            )
            assertFullState(state, captureFullState(driver, accounts), "1a S2 replays")
        } finally {
            driver.close()
        }
    }

    // ---------- Matrix 3b ----------

    @Test
    fun matrix3bNoWinnerAndDistinctOrDismissedSourcesStillFormalize() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val accounts = accounts()
            val cat = catalog()
            val store = SqlDelightImportSpineStore(database, driver)
            val expected = Expected()

            // S1, S2, S3 share tuple X: S3 gets one directed candidate per existing
            // source (no winner, both s1 and s2 stay listed as possible existing).
            val intakeIds =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("s1", "status-s1-1"),
                        intakeIds("s2", "status-s2-1", duplicates = listOf("duplicate-s2-s1" to "duplicate-status-s2-s1")),
                        intakeIds(
                            "s3",
                            "status-s3-1",
                            duplicates = listOf("duplicate-s3-s1" to "duplicate-status-s3-s1", "duplicate-s3-s2" to "duplicate-status-s3-s2"),
                        ),
                    ),
                )
            val intake = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o1", "batch-oracle-1", tupleXFacts)))
            expected.intake(ledgerId.value, "req-o1", "batch-oracle-1", hashTupleX, tupleXFacts, ImportCompleteness.VALID_COMPLETE, "s1", generatedAt)
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)))
            expected.intake(
                ledgerId.value,
                "req-o2",
                "batch-oracle-2",
                hashTupleX,
                tupleXFacts,
                ImportCompleteness.VALID_COMPLETE,
                "s2",
                generatedAt,
                duplicates = listOf(Triple("duplicate-s2-s1", "duplicate-status-s2-s1", "source-s1")),
            )
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o3", "batch-oracle-3", tupleXFacts)))
            expected.intake(
                ledgerId.value,
                "req-o3",
                "batch-oracle-3",
                hashTupleX,
                tupleXFacts,
                ImportCompleteness.VALID_COMPLETE,
                "s3",
                generatedAt,
                duplicates =
                    listOf(
                        Triple("duplicate-s3-s1", "duplicate-status-s3-s1", "source-s1"),
                        Triple("duplicate-s3-s2", "duplicate-status-s3-s2", "source-s2"),
                    ),
            )
            var state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "3b three sources, no winner")

            // CONFIRMED_DISTINCT ends only the s2->s1 candidate; both sources formalize.
            val distinct = reviewRequest("review-distinct", "duplicate-s2-s1", tupleComparisonDigest("source-s2", "source-s1"), ImportDuplicateStatus.CONFIRMED_DISTINCT, "manual-distinct")
            assertIs<ImportDuplicateReviewResult.Accepted>(ReviewImportDuplicateCandidate(store).execute(distinct))
            expected.review(ledgerId.value, distinct, reviewFingerprint(distinct))
            state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "3b confirmed distinct")

            val confirms = BatchCommitIdSource(listOf(commitIds("s1"), commitIds("s2"), commitIds("s3")))
            val confirmUseCase = ConfirmImportCandidate(store, confirms, OrdinaryFlowFormalFactory(cat, CategoryId("category-food"), AccountId("account-asset-a")), cat)
            assertIs<ImportCandidateDecisionResult.Accepted>(confirmUseCase.execute(confirmRequest("req-o1-confirm", "candidate-s1", hashTupleX)))
            expected.confirm(ledgerId.value, "req-o1-confirm", "s1", hashTupleX, tupleXFacts, "2026-08-20T10:00:00+08:00")
            assertIs<ImportCandidateDecisionResult.Accepted>(confirmUseCase.execute(confirmRequest("req-o2-confirm", "candidate-s2", hashTupleX)))
            expected.confirm(ledgerId.value, "req-o2-confirm", "s2", hashTupleX, tupleXFacts, "2026-08-20T10:00:00+08:00")
            state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "3b both sources formalized")

            // DISMISSED_LOOKALIKE on one of s3's candidates: s3 still formalizes even
            // though its second candidate remains open (only CONFIRMED_DUPLICATE blocks).
            val dismissed = reviewRequest("review-dismiss", "duplicate-s3-s1", tupleComparisonDigest("source-s3", "source-s1"), ImportDuplicateStatus.DISMISSED_LOOKALIKE, "manual-dismissal")
            assertIs<ImportDuplicateReviewResult.Accepted>(ReviewImportDuplicateCandidate(store).execute(dismissed))
            expected.review(ledgerId.value, dismissed, reviewFingerprint(dismissed))
            assertIs<ImportCandidateDecisionResult.Accepted>(confirmUseCase.execute(confirmRequest("req-o3-confirm", "candidate-s3", hashTupleX)))
            expected.confirm(ledgerId.value, "req-o3-confirm", "s3", hashTupleX, tupleXFacts, "2026-08-20T10:00:00+08:00")
            assertEquals(3, confirms.calls.get())
            assertFullState(expected.state(accounts), captureFullState(driver, accounts), "3b dismissed lookalike then formalized")

            // The report projection proves three independent economic events: the shared
            // tuple never collapsed the multiplicity.
            assertEquals(
                mapOf("account-asset-a" to -38550L, "expense-account-food" to 38550L),
                captureFullState(driver, accounts).report.getValue(ledgerId.value).balancesByAccount,
            )
        } finally {
            driver.close()
        }
    }

    // ---------- Matrix 5c ----------

    @Test
    fun matrix5cReviewFailuresLeaveZeroResidueAndRetryableIdentities() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val accounts = accounts()
            val store = SqlDelightImportSpineStore(database, driver)
            val expected = Expected()
            val review = ReviewImportDuplicateCandidate(store)

            // Setup: s1 + s2 with one deferred exact-tuple candidate.
            val intakeIds =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("s1", "status-s1-1"),
                        intakeIds("s2", "status-s2-1", duplicates = listOf("duplicate-s2-s1" to "duplicate-status-s2-s1")),
                    ),
                )
            val intake = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o1", "batch-oracle-1", tupleXFacts)))
            expected.intake(ledgerId.value, "req-o1", "batch-oracle-1", hashTupleX, tupleXFacts, ImportCompleteness.VALID_COMPLETE, "s1", generatedAt)
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)))
            expected.intake(
                ledgerId.value,
                "req-o2",
                "batch-oracle-2",
                hashTupleX,
                tupleXFacts,
                ImportCompleteness.VALID_COMPLETE,
                "s2",
                generatedAt,
                duplicates = listOf(Triple("duplicate-s2-s1", "duplicate-status-s2-s1", "source-s1")),
            )
            val state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "5c setup")

            // Stale expected fingerprint: typed rejection, zero review rows.
            val stale =
                assertIs<ImportDuplicateReviewResult.Rejected>(
                    review.execute(reviewRequest("review-stale", "duplicate-s2-s1", "sha256:not-the-persisted-fingerprint", ImportDuplicateStatus.CONFIRMED_DUPLICATE, "stale")),
                )
            assertEquals("SPINE_STALE_FINGERPRINT", stale.diagnostic.code)
            assertFullState(state, captureFullState(driver, accounts), "5c stale fingerprint")

            // Injected failure after the snapshot insert: full rollback, corrected retry
            // on the same request identity accepts (the claim is not burned).
            val failingStore =
                SqlDelightImportSpineStore(
                    database,
                    driver,
                    ImportSpineFailureInjector { if (it == ImportSpineFailurePoint.REVIEW_AFTER_SNAPSHOT) error("injected") },
                )
            val injected = reviewRequest("review-injected", "duplicate-s2-s1", tupleComparisonDigest("source-s2", "source-s1"), ImportDuplicateStatus.CONFIRMED_DUPLICATE, "duplicate")
            assertFailsWith<IllegalStateException> { ReviewImportDuplicateCandidate(failingStore).execute(injected) }
            assertFullState(state, captureFullState(driver, accounts), "5c injected failure rollback")
            val retried = reviewRequest("review-injected", "duplicate-s2-s1", tupleComparisonDigest("source-s2", "source-s1"), ImportDuplicateStatus.CONFIRMED_DISTINCT, "manual-distinct")
            assertIs<ImportDuplicateReviewResult.Accepted>(review.execute(retried))
            expected.review(ledgerId.value, retried, reviewFingerprint(retried))
            val afterRetry = expected.state(accounts)
            assertFullState(afterRetry, captureFullState(driver, accounts), "5c corrected retry")

            // Non-deferred review (candidate already terminal): typed rejection, zero rows.
            val nonDeferred =
                assertIs<ImportDuplicateReviewResult.Rejected>(
                    review.execute(reviewRequest("review-again", "duplicate-s2-s1", tupleComparisonDigest("source-s2", "source-s1"), ImportDuplicateStatus.DISMISSED_LOOKALIKE, "double-review")),
                )
            assertEquals("SPINE_CANDIDATE_NOT_PENDING", nonDeferred.diagnostic.code)
            assertFullState(afterRetry, captureFullState(driver, accounts), "5c non-deferred review")

            // Same request different content: claim conflict, zero new rows.
            val conflict =
                assertIs<ImportDuplicateReviewResult.Rejected>(
                    review.execute(retried.copy(reasonToken = "different-reason")),
                )
            assertEquals("SPINE_REQUEST_IDENTITY_CONFLICT", conflict.diagnostic.code)
            assertFullState(afterRetry, captureFullState(driver, accounts), "5c request conflict")
        } finally {
            driver.close()
        }
    }

    @Test
    fun matrix5cConcurrentReviewClaimsHaveSingleWinnerAndLoserReplay() {
        val path = Files.createTempFile("p407-review-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightImportSpineStore(database, driver)
                val intakeIds =
                    BatchIntakeIdSource(
                        listOf(
                            intakeIds("s1", "status-s1-1"),
                            intakeIds("s2", "status-s2-1", duplicates = listOf("duplicate-s2-s1" to "duplicate-status-s2-s1")),
                        ),
                    )
                val intake = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint())
                assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o1", "batch-oracle-1", tupleXFacts)))
                assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)))
            }
            val request = reviewRequest("review-concurrent", "duplicate-s2-s1", tupleComparisonDigest("source-s2", "source-s1"), ImportDuplicateStatus.CONFIRMED_DUPLICATE, "duplicate")
            val results =
                concurrentExecute(url) { connection ->
                    ReviewImportDuplicateCandidate(connection).execute(request)
                }
            assertEquals(1, results.count { it is ImportDuplicateReviewResult.Accepted })
            assertEquals(1, results.count { it is ImportDuplicateReviewResult.NoChange })
            JdbcSqliteDriver(url).use { driver ->
                assertEquals(1L, countRows(driver, "import_duplicate_review_request"))
                assertEquals(1L, countRows(driver, "import_duplicate_review_snapshot"))
                assertEquals(1L, countRows(driver, "import_duplicate_review_receipt"))
                assertEquals(2L, countRows(driver, "import_duplicate_status_history"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---------- Matrix 5d + 6 ----------

    @Test
    fun matrix5dExactTupleAndNoFundsUniquenessSurviveRetryAndConcurrency() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val accounts = accounts()
            val store = SqlDelightImportSpineStore(database, driver)
            val expected = Expected()

            val intakeIds =
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("s1", "status-s1-1"),
                        intakeIds("s2", "status-s2-1", duplicates = listOf("duplicate-s2-s1" to "duplicate-status-s2-s1")),
                        intakeIds("nf", "status-nf-1", duplicates = listOf("duplicate-nf" to "duplicate-status-nf")),
                    ),
                )
            val intake = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o1", "batch-oracle-1", tupleXFacts)))
            expected.intake(ledgerId.value, "req-o1", "batch-oracle-1", hashTupleX, tupleXFacts, ImportCompleteness.VALID_COMPLETE, "s1", generatedAt)
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)))
            expected.intake(
                ledgerId.value,
                "req-o2",
                "batch-oracle-2",
                hashTupleX,
                tupleXFacts,
                ImportCompleteness.VALID_COMPLETE,
                "s2",
                generatedAt,
                duplicates = listOf(Triple("duplicate-s2-s1", "duplicate-status-s2-s1", "source-s1")),
            )
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-nf", "batch-oracle-nf", noFundsFacts)))
            expected.intake(
                ledgerId.value,
                "req-nf",
                "batch-oracle-nf",
                hashNoFunds,
                noFundsFacts,
                ImportCompleteness.VALID_COMPLETE,
                "nf",
                generatedAt,
                duplicates = listOf(Triple("duplicate-nf", "duplicate-status-nf", null)),
            )
            var state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "5d setup")

            // Retries of both intake identities are zero-write replays: the exact-tuple
            // pair and the NULL-target NO_FUNDS subject keep exactly one candidate row.
            assertIs<ImportIntakeResult.NoChange>(
                ExecuteImportIntake(store, BatchIntakeIdSource(emptyList()), ImportContentFingerprint()).execute(intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)),
            )
            assertIs<ImportIntakeResult.NoChange>(
                ExecuteImportIntake(store, BatchIntakeIdSource(emptyList()), ImportContentFingerprint()).execute(intakeRequest("req-nf", "batch-oracle-nf", noFundsFacts)),
            )
            assertFullState(state, captureFullState(driver, accounts), "5d intake retries")

            // The partial unique indexes reject a second row for the same directed pair
            // and for the same NO_FUNDS subject (DDL guard proof).
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_duplicate_candidate VALUES ('${ledgerId.value}','duplicate-direct','source-s2','source-s1','EXACT_BUSINESS_TUPLE','${tupleComparisonDigest("source-s2", "source-s1")}','{}','p','exact','p407_exact_business_tuple_v1',1,'$generatedAt','req-o2')",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_duplicate_candidate VALUES ('${ledgerId.value}','duplicate-nf-2','source-nf',NULL,'CLOSED_OR_FAILED_NO_FUNDS','sha256:no-funds-source-nf','{}','p','exact','p407_exact_business_tuple_v1',1,'$generatedAt','req-nf')",
                    0,
                )
            }
            assertFailsWith<SQLException> {
                driver.execute(
                    null,
                    "INSERT INTO import_duplicate_candidate VALUES ('${ledgerId.value}','duplicate-exact-null','source-s2',NULL,'EXACT_BUSINESS_TUPLE','sha256:x','{}','p','exact','p407_exact_business_tuple_v1',1,'$generatedAt','req-o2')",
                    0,
                )
            }
            assertFullState(state, captureFullState(driver, accounts), "5d uniqueness guards")

            // NO_FUNDS disposal (matrix 6): the only legal review outcomes leave zero
            // formal/balance/report/reconciliation effect. CONFIRMED_DUPLICATE is a typed
            // rejection (SPEC-001); DISMISSED_LOOKALIKE is the closing disposition.
            val review = ReviewImportDuplicateCandidate(store)
            val rejectedDuplicate =
                assertIs<ImportDuplicateReviewResult.Rejected>(
                    review.execute(reviewRequest("review-nf-duplicate", "duplicate-nf", noFundsComparisonDigest("source-nf"), ImportDuplicateStatus.CONFIRMED_DUPLICATE, "closed-no-funds")),
                )
            assertEquals("SPINE_DECISION_KIND_MISMATCH", rejectedDuplicate.diagnostic.code)
            assertFullState(state, captureFullState(driver, accounts), "5d no-funds confirmed-duplicate rejected")
            val dismissal = reviewRequest("review-nf-dismiss", "duplicate-nf", noFundsComparisonDigest("source-nf"), ImportDuplicateStatus.DISMISSED_LOOKALIKE, "manual-dismissal")
            assertIs<ImportDuplicateReviewResult.Accepted>(review.execute(dismissal))
            expected.review(ledgerId.value, dismissal, reviewFingerprint(dismissal))
            state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "5d no-funds dismissed")

            // Matrix 6 residue proof: zero formal graph, zero balances, zero report
            // movement, zero evidence links and reconciliation rows.
            assertEquals(0L, countRows(driver, "ledger_transaction"))
            assertEquals(0L, countRows(driver, "posting"))
            assertEquals(0L, countRows(driver, "evidence_link"))
            assertEquals(0L, countRows(driver, "posting_reconciliation"))
            assertEquals(
                mapOf("account-asset-a" to 0L, "expense-account-food" to 0L),
                state.report.getValue(ledgerId.value).balancesByAccount,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun matrix5dConcurrentSameRequestIntakesKeepOneDuplicateRow() {
        val path = Files.createTempFile("p407-intake-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use(LedgerDatabase.Schema::create)
            JdbcSqliteDriver(url).use { driver ->
                val store = SqlDelightImportSpineStore(LedgerDatabase(driver), driver)
                val intake = ExecuteImportIntake(store, BatchIntakeIdSource(listOf(intakeIds("s1", "status-s1-1"))), ImportContentFingerprint())
                assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-o1", "batch-oracle-1", tupleXFacts)))
            }
            val request = intakeRequest("req-o2", "batch-oracle-2", tupleXFacts)
            val ids =
                BatchIntakeIdSource(
                    listOf(intakeIds("s2", "status-s2-1", duplicates = listOf("duplicate-s2-s1" to "duplicate-status-s2-s1"))),
                )
            val results =
                concurrentExecute(url) { connection ->
                    ExecuteImportIntake(connection, ids, ImportContentFingerprint()).execute(request)
                }
            assertEquals(1, results.count { it is ImportIntakeResult.Accepted })
            assertEquals(1, ids.calls.get())
            JdbcSqliteDriver(url).use { driver ->
                assertEquals(1L, countRows(driver, "import_duplicate_candidate"))
                assertEquals(1L, countRows(driver, "import_duplicate_status_history"))
                assertEquals(2L, countRows(driver, "import_source_record"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---------- Matrix 7 ----------

    @Test
    fun matrix7UnknownRefundAndNonSettledAmbiguityStayUnresolved() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val accounts = accounts()
            val store = SqlDelightImportSpineStore(database, driver)
            val expected = Expected()

            // A refund-pending row carries funding_state UNRESOLVED: it never reaches the
            // tuple rule and never borrows NO_FUNDS, and its candidate stays incomplete.
            val intake = ExecuteImportIntake(store, BatchIntakeIdSource(listOf(intakeIds("u", "status-u-1"))), ImportContentFingerprint())
            assertIs<ImportIntakeResult.Accepted>(intake.execute(intakeRequest("req-ou", "batch-oracle-u", unresolvedFacts)))
            expected.intake(ledgerId.value, "req-ou", "batch-oracle-u", hashUnresolved, unresolvedFacts, ImportCompleteness.VALID_COMPLETE, "u", generatedAt)
            var state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "7 unresolved intake")
            assertEquals(0L, countRows(driver, "import_duplicate_candidate"))
            assertEquals(
                "UNRESOLVED",
                driver
                    .executeQuery(null, "SELECT funding_state FROM import_source_record WHERE source_id = 'source-u'", { c ->
                        c.next()
                        app.cash.sqldelight.db.QueryResult
                            .Value(c.getString(0)!!)
                    }, 0)
                    .value,
            )

            // A valid_incomplete row (missing status) also stays duplicate-free.
            val missingStatusFacts =
                ImportSourceFacts(
                    4500,
                    "CNY",
                    2,
                    "2026-08-04T08:00:00+08:00",
                    "out",
                    null,
                    ImportFundingState.UNRESOLVED,
                    "source-contract-unresolved-v1",
                    1,
                )
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(
                    store,
                    BatchIntakeIdSource(listOf(intakeIds("i", "status-i-1"))),
                    ImportContentFingerprint(),
                ).execute(intakeRequest("req-oi", "batch-oracle-i", missingStatusFacts, completeness = ImportCompleteness.VALID_INCOMPLETE)),
            )
            expected.intake(
                ledgerId.value,
                "req-oi",
                "batch-oracle-i",
                fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, missingStatusFacts),
                missingStatusFacts,
                ImportCompleteness.VALID_INCOMPLETE,
                "i",
                generatedAt,
            )
            state = expected.state(accounts)
            assertFullState(state, captureFullState(driver, accounts), "7 incomplete intake")
            assertEquals(0L, countRows(driver, "import_duplicate_candidate"))

            // The UNRESOLVED source cannot reach NO_FUNDS semantics either: its confirm is
            // rejected as incomplete with zero formal residue (D-105 section 3).
            val confirmIds = BatchCommitIdSource(listOf(commitIds("u")))
            val rejected =
                assertIs<ImportCandidateDecisionResult.Rejected>(
                    ConfirmImportCandidate(store, confirmIds, OrdinaryFlowFormalFactory(catalog(), CategoryId("category-food"), AccountId("account-asset-a")), catalog())
                        .execute(confirmRequest("req-ou-confirm", "candidate-u", hashUnresolved)),
                )
            assertEquals("SPINE_CANDIDATE_INCOMPLETE", rejected.diagnostic.code)
            assertEquals(0, confirmIds.calls.get())
            assertFullState(state, captureFullState(driver, accounts), "7 unresolved not formalizable")
        } finally {
            driver.close()
        }
    }

    // ---------- helpers ----------

    private fun countRows(
        driver: JdbcSqliteDriver,
        table: String,
    ): Long =
        driver
            .executeQuery(
                null,
                "SELECT count(*) FROM $table",
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getLong(0)!!)
                },
                0,
            ).value

    private fun concurrentExecute(
        url: String,
        operations: List<(SqlDelightImportSpineStore) -> Any>,
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
                        JdbcSqliteDriver(url).use { driver ->
                            operation(SqlDelightImportSpineStore(LedgerDatabase(driver), driver))
                        }
                    }
                }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun concurrentExecute(
        url: String,
        operation: (SqlDelightImportSpineStore) -> Any,
    ): List<Any> = concurrentExecute(url, listOf(operation, operation))
}
