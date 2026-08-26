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
import com.unifiedledger.application.ImportDuplicateReviewFingerprint
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportDuplicateReviewRequest
import com.unifiedledger.application.ImportDuplicateReviewResult
import com.unifiedledger.application.ImportDuplicateStatus
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
import com.unifiedledger.application.MixedPaymentFlowFormalFactory
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.ReviewImportDuplicateCandidate
import com.unifiedledger.application.TransferFlowFormalFactory
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
import com.unifiedledger.domain.CreditRefundOriginalExpense
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P4-09 phase closure full-state oracle (D-110 implementation spec section 3): one
 * acceptance face for the RL-01..RL-08 matrix rows. Every checkpoint compares the
 * complete persisted state — the 9 spine tables, the payment profile, the 5 P4-07
 * duplicate tables, the 7 P4-08 reconciliation tables, the 5 formal tables, the 2
 * mixed group tables, the ten-dimension report projection (D-100 round A + balances)
 * and the P4-08 reconciliation dimension — against a test-side expected builder fed
 * only by the frozen fixtures of spec section 3.2.
 *
 * Parser-level platform routing stays with the parser tests (WechatBillParserJvmTest,
 * AlipayCsvParserJvmTest, AlipayCsvParserYuebaoTransferJvmTest); this oracle drives
 * the spine with the parser output shape directly. The D4 failure-matrix anchors
 * contributed here are the new F4/F5 legs (tests 7/11/12) and the combo anchors
 * a (test 3) and b (test 9). All fixtures are synthetic; amounts are anonymous
 * representative minor units on fixed +08:00 timestamps.
 */
class P409PhaseClosureFullStateOracleTest {
    private val ledgerId = LedgerId("ledger-p409-oracle")
    private val cny = CurrencyUnit("CNY", 2)
    private val fingerprint = ImportContentFingerprint()
    private val comparisonFingerprint = ImportDuplicateComparisonFingerprint()
    private val generatedAt = "2026-08-22T08:00:00Z"
    private val inputRef = "batch-p409-oracle"
    private val confirmedAt = "2026-08-22T10:00:00+08:00"
    private val reviewedAt = "2026-08-22T11:00:00+08:00"

    // Section 3.2 frozen fixture values (synthetic, exact minor units).
    private val rl01Facts = ImportSourceFacts(4580, "CNY", 2, "2026-08-01T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl02Facts = ImportSourceFacts(7250, "CNY", 2, "2026-08-02T12:00:00+08:00", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl03CompleteFacts = ImportSourceFacts(3000, "CNY", 2, "2026-08-03T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl03MissingFacts = ImportSourceFacts(1500, "CNY", 2, "2026-08-04T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl04CompleteFacts = ImportSourceFacts(2000, "CNY", 2, "2026-08-05T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl04IncompleteFacts = ImportSourceFacts(2000, "CNY", 2, "2026-08-05T12:00:00+08:00", "in", null, ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl05ExpenseFacts = ImportSourceFacts(10000, "CNY", 2, "2026-08-06T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl05RepayFacts = ImportSourceFacts(5620, "CNY", 2, "2026-08-07T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl05RefundFacts = ImportSourceFacts(1535, "CNY", 2, "2026-08-08T12:00:00+08:00", "in", "refund_settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl06Facts = ImportSourceFacts(1240, "CNY", 2, "2026-08-09T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val rl08ClosedFacts = ImportSourceFacts(900, "CNY", 2, "2026-08-10T12:00:00+08:00", "out", "closed", ImportFundingState.NO_FUNDS, "source-contract-closed-v1", 1)
    private val rl08TransferClosedFacts = ImportSourceFacts(1200, "CNY", 2, "2026-08-11T12:00:00+08:00", "out", "closed", ImportFundingState.NO_FUNDS, "source-contract-closed-v1", 1)
    private val rl08CreditClosedFacts = ImportSourceFacts(2400, "CNY", 2, "2026-08-12T12:00:00+08:00", "out", "closed", ImportFundingState.NO_FUNDS, "source-contract-closed-v1", 1)
    private val rl08MixedClosedFacts = ImportSourceFacts(1860, "CNY", 2, "2026-08-13T12:00:00+08:00", "out", "closed", ImportFundingState.NO_FUNDS, "source-contract-closed-v1", 1)

    private val directProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗")
    private val repaymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, "余额宝", null)
    private val refundProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "花呗")
    private val mixedProfile = ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, "余额宝", "花呗")

    /** The five original kinds of the legacy ledger_transaction.kind CHECK. */
    private val legacyTransactionKinds = setOf("OPENING_BALANCE", "EXPENSE", "INCOME", "ACCOUNT_TRANSFER", "CREDIT_REPAYMENT")

    private fun catalog(): LedgerCatalog = when (
        val result = LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("account-asset-a"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("account-asset-b"), ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("account-credit-huabei"), ledgerId, AccountKind.LIABILITY, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("expense-account-food"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("expense-account-clothes"), ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("income-account-salary"), ledgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("account-asset-other-ledger"), LedgerId("ledger-p409-other"), AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
            ),
            categories = listOf(
                Category(CategoryId("category-primary-food"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-food"), ledgerId, parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-primary-clothes"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-clothes"), ledgerId, parentId = CategoryId("category-primary-clothes"), postingAccountId = AccountId("expense-account-clothes"), active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("category-primary-salary"), ledgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                Category(CategoryId("category-salary"), ledgerId, parentId = CategoryId("category-primary-salary"), postingAccountId = AccountId("income-account-salary"), active = true, kind = CategoryKind.INCOME),
            ),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("p409 oracle catalog failure: ${result.violation}")
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

    private fun commitIds3(prefix: String) = ImportCommitIds(
        confirmationId = ImportConfirmationId("confirmation-$prefix"),
        statusHistoryId = ImportStatusHistoryId("status-$prefix-2"),
        formalIds = ImportFormalIds(
            transactionId = TransactionId("tx-$prefix"),
            versionId = TransactionVersionId("version-$prefix-v1"),
            postingSetId = PostingSetId("posting-set-$prefix"),
            postingIds = listOf(PostingId("posting-$prefix-0"), PostingId("posting-$prefix-1"), PostingId("posting-$prefix-2")),
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

    /** Ordinary v1 factory: "out" formalizes the expense, "in" the income (lifecycle precedent). */
    private class OrdinaryFlowFormalFactory(private val catalog: LedgerCatalog) : ImportCandidateFormalFactory {
        private val delegate = com.unifiedledger.application.OrdinaryFlowFormalFactory(catalog)

        override fun create(input: ImportCandidateFormalizationInput, ids: ImportCommitIds): DomainResult<ImportFormalCommit> =
            delegate.create(input, ids)
    }

    /** Store-backed original-expense reader for the refund variant (P406 precedent). */
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
            return CreditRefundOriginalExpense(
                transactionId, LedgerId(rows[0][1] as String),
                com.unifiedledger.domain.TransactionKind.valueOf(rows[0][0] as String),
                rows[0][3] as String, AccountId(rows[0][2] as String),
            )
        }
    }

    private class Executor(
        val database: LedgerDatabase,
        val driver: JdbcSqliteDriver,
        val store: SqlDelightImportSpineStore,
        private val catalog: LedgerCatalog,
        private val ordinaryFactory: ImportCandidateFormalFactory,
        private val transferFactory: ImportCandidateFormalFactory,
        val creditFactory: ImportCandidateFormalFactory,
        val mixedFactory: ImportCandidateFormalFactory,
        val intakeIds: ImportIntakeIdSource,
        val commitIds: ImportIdSource,
    ) {
        fun intake(request: ImportIntakeRequest): ImportIntakeResult =
            ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirm(request: ImportCandidateConfirmRequest, factory: ImportCandidateFormalFactory): ImportCandidateDecisionResult =
            ConfirmImportCandidate(store, commitIds, factory, catalog).execute(request)

        fun confirmOrdinary(request: ImportCandidateConfirmRequest) = confirm(request, ordinaryFactory)

        fun confirmTransfer(request: ImportCandidateConfirmRequest) = confirm(request, transferFactory)

        fun confirmCredit(request: ImportCandidateConfirmRequest) = confirm(request, creditFactory)

        fun confirmMixed(request: ImportCandidateConfirmRequest) = confirm(request, mixedFactory)
    }

    private fun executor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
    ): Executor {
        val store = SqlDelightImportSpineStore(database, driver)
        val cat = catalog()
        return Executor(
            database, driver, store,
            cat,
            OrdinaryFlowFormalFactory(cat),
            TransferFlowFormalFactory(cat, AccountId("account-asset-a")),
            CreditFlowFormalFactory(cat, originalExpenseReader(driver)),
            MixedPaymentFlowFormalFactory(cat),
            intakeIds, commitIds,
        )
    }

    private fun executor(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
        intakeIds: ImportIntakeIdSource,
        commitIds: ImportIdSource,
        failure: ImportSpineFailureInjector,
    ): Executor {
        val store = SqlDelightImportSpineStore(database, driver, failure)
        val cat = catalog()
        return Executor(
            database, driver, store,
            cat,
            OrdinaryFlowFormalFactory(cat),
            TransferFlowFormalFactory(cat, AccountId("account-asset-a")),
            CreditFlowFormalFactory(cat, originalExpenseReader(driver)),
            MixedPaymentFlowFormalFactory(cat),
            intakeIds, commitIds,
        )
    }

    private fun ordinaryConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        category: String = "category-food",
        funding: String = "account-asset-a",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId(category), AccountId(funding)),
    )

    private fun transferConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        from: String = "account-asset-a",
        to: String = "account-asset-b",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.TransferFlow(AccountId(from), AccountId(to)),
    )

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
        original: String,
        category: String = "category-food",
        liability: String = "account-credit-huabei",
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.CreditExpenseRefundFlow(CategoryId(category), AccountId(liability), TransactionId(original)),
    )

    private fun mixedConfirmRequest(
        requestId: String,
        candidate: String,
        hash: String,
        category: String = "category-food",
        asset: String = "account-asset-a",
        liability: String = "account-credit-huabei",
        assetLegMinor: Long? = 360L,
        creditLegMinor: Long? = 880L,
    ) = ImportCandidateConfirmRequest(
        identity = ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        candidateId = ImportCandidateId(candidate),
        expectedContentHash = hash,
        explicitConfirmedAt = confirmedAt,
        decisionFields = ImportConfirmDecisionFields.MixedPaymentFlow(CategoryId(category), AccountId(asset), AccountId(liability), assetLegMinor, creditLegMinor),
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
        reviewedAt = reviewedAt,
        reviewerReference = "reviewer-p409",
        generatedAt = reviewedAt,
        reviewId = ImportDuplicateReviewId("review-$requestId"),
        historyId = ImportStatusHistoryId("review-history-$requestId"),
    )

    private fun confirmLinkRequest(
        requestId: String,
        evidenceId: String,
        postingId: String,
        transactionId: String,
        amountMinor: Long,
        direction: String,
        accountId: String,
        responsibility: P408EvidenceResponsibility,
        sourceOccurredAt: String,
        linkId: String,
        reconciliationId: String,
    ) = P408ConfirmLinkRequest(
        ledgerId = ledgerId.value,
        requestId = requestId,
        evidenceId = evidenceId,
        candidateId = "candidate-rl07",
        postingId = postingId,
        transactionId = transactionId,
        amountMinor = amountMinor,
        currencyCode = "CNY",
        currencyPrecision = 2,
        direction = direction,
        accountId = accountId,
        responsibility = responsibility,
        basisVersion = 1,
        matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
        windowDays = P408Matcher.DEFAULT_WINDOW_DAYS,
        naturalDayDistance = 0,
        sourceOccurredAt = sourceOccurredAt,
        confirmedAt = confirmedAt,
        linkId = linkId,
        reconciliationId = reconciliationId,
        createdAt = confirmedAt,
    )

    // ---------- canonical capture (29 row lists + 2 projections) ----------

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

    private data class ReportTx(val kind: String, val postings: List<Triple<String, Long, String>>)

    private data class ReconciliationDimensionRow(
        val postingId: String,
        val transactionId: String,
        val accountId: String,
        val status: String,
        val activeLinkIds: List<String>,
    )

    private data class P409FullState(
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
        val duplicateReviewRequest: List<List<Any?>>,
        val duplicateReviewSnapshot: List<List<Any?>>,
        val duplicateReviewReceipt: List<List<Any?>>,
        val reconciliationRequest: List<List<Any?>>,
        val reconciliationRequestSnapshot: List<List<Any?>>,
        val evidenceLink: List<List<Any?>>,
        val evidenceLinkHistory: List<List<Any?>>,
        val postingReconciliation: List<List<Any?>>,
        val postingReconciliationHistory: List<List<Any?>>,
        val reconciliationReceipt: List<List<Any?>>,
        val ledgerTransaction: List<List<Any?>>,
        val postingSet: List<List<Any?>>,
        val transactionVersion: List<List<Any?>>,
        val ledgerTransactionCurrentVersion: List<List<Any?>>,
        val posting: List<List<Any?>>,
        val mixedPaymentGroup: List<List<Any?>>,
        val mixedPaymentGroupLeg: List<List<Any?>>,
        val report: Map<String, P404ReportProjection>,
        val reconciliationDimension: List<ReconciliationDimensionRow>,
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

    private fun captureFullState(driver: JdbcSqliteDriver, accounts: List<Account>): P409FullState {
        val formalJoin = "FROM posting AS p " +
            "JOIN transaction_version AS v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
            "JOIN ledger_transaction AS t ON t.transaction_id = v.transaction_id AND t.ledger_id = p.ledger_id"
        val formalRows = selectRows(
            driver,
            "SELECT t.transaction_id, t.ledger_id, t.kind, p.account_id, p.amount_minor, p.currency_code $formalJoin",
            listOf(false, false, false, false, true, false),
        )
        val reportTxs = formalRows.groupBy { it[0] as String }.map { (_, rows) ->
            ReportTx(rows.first()[2] as String, rows.map { Triple(it[3] as String, it[4] as Long, it[5] as String) })
        }
        val ledgerAccounts = accounts.filter { it.ledgerId == ledgerId }
        val accountKindById = ledgerAccounts.associate { it.id.value to it.kind }
        val report = reduceReport(reportTxs, ledgerAccounts, accountKindById)
        val reconciliationStore = SqlDelightP408ReconciliationStore(LedgerDatabase(driver), driver)
        val reconciliationDimension = reconciliationStore.readReconciliationReport(ledgerId.value)
            .map { ReconciliationDimensionRow(it.postingId, it.transactionId, it.accountId, it.status.name, it.activeLinkIds) }
            .sortedBy { it.postingId }
        return P409FullState(
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
            duplicateReviewRequest = selectRows(driver, "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM import_duplicate_review_request", listOf(false, false, false, false, false, false)).sortedWith(rowComparator),
            duplicateReviewSnapshot = selectRows(driver, "SELECT ledger_id, request_id, candidate_id, expected_comparison_fingerprint, decision, reason_token, reviewed_at, reviewer_reference, generated_at, review_id FROM import_duplicate_review_snapshot", listOf(false, false, false, false, false, false, false, false, false, false)).sortedWith(rowComparator),
            duplicateReviewReceipt = selectRows(driver, "SELECT ledger_id, request_id, candidate_id, review_id, history_id, outcome FROM import_duplicate_review_receipt", listOf(false, false, false, false, false, false)).sortedWith(rowComparator),
            reconciliationRequest = selectRows(driver, "SELECT ledger_id, request_id, operation, input_fingerprint, outcome, reason_code FROM reconciliation_request", listOf(false, false, false, false, false, false)).sortedWith(rowComparator),
            reconciliationRequestSnapshot = selectRows(
                driver,
                "SELECT ledger_id, request_id, evidence_id, candidate_id, posting_id, transaction_id, amount_minor, currency_code, currency_precision, direction, account_id, responsibility, basis_version, match_basis, window_days, natural_day_distance, source_occurred_at, confirmed_at, human_decision FROM reconciliation_request_snapshot",
                listOf(false, false, false, false, false, false, true, false, true, false, false, false, true, false, true, true, false, false, false),
            ).sortedWith(rowComparator),
            evidenceLink = selectRows(
                driver,
                "SELECT ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at FROM evidence_link",
                listOf(false, false, false, false, false, false, true, false, false, false, false),
            ).sortedWith(rowComparator),
            evidenceLinkHistory = selectRows(driver, "SELECT ledger_id, link_id, sequence, state, reason, request_id, occurred_at FROM evidence_link_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
            postingReconciliation = selectRows(driver, "SELECT ledger_id, reconciliation_id, posting_id, status, latest_sequence FROM posting_reconciliation", listOf(false, false, false, false, true)).sortedWith(rowComparator),
            postingReconciliationHistory = selectRows(driver, "SELECT ledger_id, reconciliation_id, sequence, status, evidence_link_id, request_id, occurred_at FROM posting_reconciliation_history", listOf(false, false, true, false, false, false, false)).sortedWith(rowComparator),
            reconciliationReceipt = selectRows(driver, "SELECT ledger_id, request_id, outcome, link_id, reconciliation_id, history_sequence FROM reconciliation_receipt", listOf(false, false, false, false, false, true)).sortedWith(rowComparator),
            ledgerTransaction = selectRows(driver, "SELECT transaction_id, ledger_id, kind, canonical_kind FROM ledger_transaction", listOf(false, false, false, false)).sortedWith(rowComparator),
            postingSet = selectRows(driver, "SELECT posting_set_id, ledger_id FROM posting_set", listOf(false, false)).sortedWith(rowComparator),
            transactionVersion = selectRows(driver, "SELECT version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note FROM transaction_version", listOf(false, false, false, true, false, false, false, false, false)).sortedWith(rowComparator),
            ledgerTransactionCurrentVersion = selectRows(driver, "SELECT transaction_id, ledger_id, current_version_id FROM ledger_transaction_current_version", listOf(false, false, false)).sortedWith(rowComparator),
            posting = selectRows(driver, "SELECT posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision FROM posting", listOf(false, false, false, true, false, true, false, true)).sortedWith(rowComparator),
            mixedPaymentGroup = selectRows(
                driver,
                "SELECT ledger_id, group_id, candidate_id, transaction_id, request_id, total_minor, generated_at FROM mixed_payment_group",
                listOf(false, false, false, false, false, true, false),
            ).sortedWith(rowComparator),
            mixedPaymentGroupLeg = selectRows(
                driver,
                "SELECT ledger_id, group_id, leg_index, leg_class, account_id, amount_minor FROM mixed_payment_group_leg",
                listOf(false, false, true, false, false, true),
            ).sortedWith(rowComparator),
            report = mapOf(ledgerId.value to report),
            reconciliationDimension = reconciliationDimension,
        )
    }

    /**
     * Ten-dimension closure reducer (ruling 2): external dims by account kind (credit
     * expense keeps its full externalExpense/consumption with zero purchase-day cash
     * flow; refunds subtract), internal transfer by ACCOUNT_TRANSFER principal, cash
     * dims from the real-ASSET legs of external-kind transactions only, net worth over
     * real financial accounts.
     */
    private fun reduceReport(
        transactions: List<ReportTx>,
        ledgerAccounts: List<Account>,
        accountKindById: Map<String, AccountKind>,
    ): P404ReportProjection {
        val balances = LinkedHashMap<String, Long>()
        ledgerAccounts.forEach { balances[it.id.value] = 0L }
        var internalTransfer = 0L
        var externalIncome = 0L
        var externalExpense = 0L
        var cashInflow = 0L
        var cashOutflow = 0L
        var netWorth = 0L
        transactions.forEach { tx ->
            tx.postings.forEach { (accountId, amount, _) ->
                balances[accountId] = (balances[accountId] ?: 0L) + amount
                when (accountKindById[accountId]) {
                    AccountKind.EXPENSE -> externalExpense += amount
                    AccountKind.INCOME -> externalIncome -= amount
                    else -> Unit
                }
                when (accountKindById[accountId]) {
                    AccountKind.ASSET -> netWorth += amount
                    AccountKind.LIABILITY -> netWorth += amount
                    else -> Unit
                }
            }
            val positiveTotal = tx.postings.sumOf { if (it.second > 0L) it.second else 0L }
            when (tx.kind) {
                "ACCOUNT_TRANSFER" -> internalTransfer += positiveTotal
                "EXPENSE" -> tx.postings.forEach { (accountId, amount, _) ->
                    if (accountKindById[accountId] == AccountKind.ASSET && amount < 0L) cashOutflow += -amount
                }
                "INCOME" -> tx.postings.forEach { (accountId, amount, _) ->
                    if (accountKindById[accountId] == AccountKind.ASSET && amount > 0L) cashInflow += amount
                }
                // Repayment moves real funds out of an asset account (P406 precedent:
                // purchase-day credit cash flow is zero, repayment cash flow is the
                // asset leg).
                "CREDIT_REPAYMENT" -> tx.postings.forEach { (accountId, amount, _) ->
                    if (accountKindById[accountId] == AccountKind.ASSET && amount < 0L) cashOutflow += -amount
                }
                else -> Unit
            }
        }
        val consumption = externalExpense
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

    private fun assertFullState(expected: P409FullState, actual: P409FullState, checkpoint: String) {
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
        assertEquals(expected.duplicateReviewRequest, actual.duplicateReviewRequest, "$checkpoint: import_duplicate_review_request")
        assertEquals(expected.duplicateReviewSnapshot, actual.duplicateReviewSnapshot, "$checkpoint: import_duplicate_review_snapshot")
        assertEquals(expected.duplicateReviewReceipt, actual.duplicateReviewReceipt, "$checkpoint: import_duplicate_review_receipt")
        assertEquals(expected.reconciliationRequest, actual.reconciliationRequest, "$checkpoint: reconciliation_request")
        assertEquals(expected.reconciliationRequestSnapshot, actual.reconciliationRequestSnapshot, "$checkpoint: reconciliation_request_snapshot")
        assertEquals(expected.evidenceLink, actual.evidenceLink, "$checkpoint: evidence_link")
        assertEquals(expected.evidenceLinkHistory, actual.evidenceLinkHistory, "$checkpoint: evidence_link_history")
        assertEquals(expected.postingReconciliation, actual.postingReconciliation, "$checkpoint: posting_reconciliation")
        assertEquals(expected.postingReconciliationHistory, actual.postingReconciliationHistory, "$checkpoint: posting_reconciliation_history")
        assertEquals(expected.reconciliationReceipt, actual.reconciliationReceipt, "$checkpoint: reconciliation_receipt")
        assertEquals(expected.ledgerTransaction, actual.ledgerTransaction, "$checkpoint: ledger_transaction")
        assertEquals(expected.postingSet, actual.postingSet, "$checkpoint: posting_set")
        assertEquals(expected.transactionVersion, actual.transactionVersion, "$checkpoint: transaction_version")
        assertEquals(expected.ledgerTransactionCurrentVersion, actual.ledgerTransactionCurrentVersion, "$checkpoint: ledger_transaction_current_version")
        assertEquals(expected.posting, actual.posting, "$checkpoint: posting")
        assertEquals(expected.mixedPaymentGroup, actual.mixedPaymentGroup, "$checkpoint: mixed_payment_group")
        assertEquals(expected.mixedPaymentGroupLeg, actual.mixedPaymentGroupLeg, "$checkpoint: mixed_payment_group_leg")
        assertEquals(expected.report, actual.report, "$checkpoint: report projection")
        assertEquals(expected.reconciliationDimension, actual.reconciliationDimension, "$checkpoint: reconciliation dimension")
    }

    // ---------- test-side expected builder (never reads the database under test) ----------

    private fun comparisonDigest(kind: ImportRecordKind, facts: ImportSourceFacts, subjectSourceId: String, existingSourceId: String?): String {
        val projection = ImportDuplicateComparisonSnapshot(
            ImportSourceId(subjectSourceId), existingSourceId?.let(::ImportSourceId), kind, kind.contractVersion,
            facts.amountMinor, facts.currencyCode, facts.currencyPrecision, facts.occurredAt,
            facts.directionToken, facts.statusToken,
        )
        return comparisonFingerprint.digest(projection)
    }

    private fun comparisonJson(kind: ImportRecordKind, facts: ImportSourceFacts, subjectSourceId: String, existingSourceId: String?): String {
        val projection = ImportDuplicateComparisonSnapshot(
            ImportSourceId(subjectSourceId), existingSourceId?.let(::ImportSourceId), kind, kind.contractVersion,
            facts.amountMinor, facts.currencyCode, facts.currencyPrecision, facts.occurredAt,
            facts.directionToken, facts.statusToken,
        )
        val target = if (existingSourceId == null) "null" else "\"$existingSourceId\""
        return "{\"possible_existing_source_id\":$target,\"subject_source_id\":\"$subjectSourceId\",\"tuple\":${comparisonFingerprint.canonicalJson(projection)}}"
    }

    private fun candidateKindFor(kind: ImportRecordKind) = when (kind) {
        ImportRecordKind.ORDINARY_FLOW_SOURCE -> "ordinary_flow"
        ImportRecordKind.TRANSFER_FLOW_SOURCE -> "transfer_flow"
        ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG -> "transfer_flow_missing_leg"
        ImportRecordKind.CREDIT_EXPENSE_SOURCE -> "credit_expense"
        ImportRecordKind.CREDIT_REPAYMENT_SOURCE -> "credit_repayment"
        ImportRecordKind.MIXED_PAYMENT_SOURCE -> "mixed_payment"
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
        val reviewRequests = mutableListOf<List<Any?>>()
        val reviewSnapshots = mutableListOf<List<Any?>>()
        val reviewReceipts = mutableListOf<List<Any?>>()
        val reconciliationRequests = mutableListOf<List<Any?>>()
        val reconciliationSnapshots = mutableListOf<List<Any?>>()
        val evidenceLinks = mutableListOf<List<Any?>>()
        val evidenceLinkHistory = mutableListOf<List<Any?>>()
        val postingReconciliations = mutableListOf<List<Any?>>()
        val postingReconciliationHistory = mutableListOf<List<Any?>>()
        val reconciliationReceipts = mutableListOf<List<Any?>>()
        val transactions = mutableListOf<List<Any?>>()
        val postingSets = mutableListOf<List<Any?>>()
        val versions = mutableListOf<List<Any?>>()
        val currentVersions = mutableListOf<List<Any?>>()
        val postings = mutableListOf<List<Any?>>()
        val groups = mutableListOf<List<Any?>>()
        val groupLegs = mutableListOf<List<Any?>>()
        val reportTxs = mutableListOf<ReportTx>()
        val reconciliationDimension = mutableListOf<ReconciliationDimensionRow>()

        fun intake(
            requestId: String,
            ordinal: Int,
            kind: ImportRecordKind,
            facts: ImportSourceFacts,
            completeness: ImportCompleteness,
            profile: ImportPaymentProfile?,
            prefix: String,
            duplicates: List<Triple<String, String, String?>> = emptyList(),
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
            candidates += row(ledgerId.value, "candidate-$prefix", "source-$prefix", candidateKindFor(kind), if (complete) "1.00" else "0.50", kind.storageValue, 1L)
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
                if (existingSourceId == null) {
                    duplicateCandidates += row(
                        ledgerId.value, duplicateCandidateId, "source-$prefix", null, "CLOSED_OR_FAILED_NO_FUNDS",
                        "sha256:no-funds-source-$prefix", comparisonJson(kind, facts, "source-$prefix", null),
                        "source_declared + mechanical_decode", "exact",
                        "p407_exact_business_tuple_v1", 1L, generatedAt, requestId,
                    )
                } else {
                    duplicateCandidates += row(
                        ledgerId.value, duplicateCandidateId, "source-$prefix", existingSourceId, "EXACT_BUSINESS_TUPLE",
                        comparisonDigest(kind, facts, "source-$prefix", existingSourceId),
                        comparisonJson(kind, facts, "source-$prefix", existingSourceId),
                        "source_declared + mechanical_decode + p407_exact_business_tuple_v1", "exact",
                        "p407_exact_business_tuple_v1", 1L, generatedAt, requestId,
                    )
                }
                duplicateHistory += row(ledgerId.value, duplicateCandidateId, 1L, historyId, "DEFERRED", requestId, "creation")
            }
        }

        /** A 2-posting formal transaction (posting 0 = firstLeg, posting 1 = secondLeg). */
        fun formal(
            prefix: String,
            kind: String,
            occurredAt: String,
            firstLeg: Triple<String, Long, String>,
            secondLeg: Triple<String, Long, String>,
        ) {
            val timeText = Instant.parse(occurredAt).toString()
            val persistedKind = if (kind in legacyTransactionKinds) kind else "EXPENSE"
            val canonicalKind = if (kind in legacyTransactionKinds) null else kind
            val note = when (kind) {
                "REFUND_RECEIPT", "ACCOUNT_TRANSFER" -> null
                else -> ""
            }
            transactions += row("tx-$prefix", ledgerId.value, persistedKind, canonicalKind)
            postingSets += row("posting-set-$prefix", ledgerId.value)
            versions += row("version-$prefix-v1", "tx-$prefix", ledgerId.value, 1L, "posting-set-$prefix", timeText, timeText, timeText, note)
            currentVersions += row("tx-$prefix", ledgerId.value, "version-$prefix-v1")
            postings += row("posting-$prefix-0", "posting-set-$prefix", ledgerId.value, 0L, firstLeg.first, firstLeg.second, firstLeg.third, 2L)
            postings += row("posting-$prefix-1", "posting-set-$prefix", ledgerId.value, 1L, secondLeg.first, secondLeg.second, secondLeg.third, 2L)
            reportTxs += ReportTx(kind, listOf(firstLeg, secondLeg))
            // The reconciliation report virtualizes every ACCOUNT_TRANSFER posting as a
            // PENDING row until a posting_reconciliation row exists (COALESCE discipline).
            if (kind == "ACCOUNT_TRANSFER") {
                reconciliationDimension += ReconciliationDimensionRow("posting-$prefix-0", "tx-$prefix", firstLeg.first, "PENDING", emptyList())
                reconciliationDimension += ReconciliationDimensionRow("posting-$prefix-1", "tx-$prefix", secondLeg.first, "PENDING", emptyList())
            }
        }

        /** A 3-posting mixed EXPENSE (posting 0 = expense +, 1 = asset -, 2 = credit -). */
        fun formal3(
            prefix: String,
            occurredAt: String,
            expense: Triple<String, Long, String>,
            assetFunding: Triple<String, Long, String>,
            creditFunding: Triple<String, Long, String>,
        ) {
            val timeText = Instant.parse(occurredAt).toString()
            transactions += row("tx-$prefix", ledgerId.value, "EXPENSE", null)
            postingSets += row("posting-set-$prefix", ledgerId.value)
            versions += row("version-$prefix-v1", "tx-$prefix", ledgerId.value, 1L, "posting-set-$prefix", timeText, timeText, timeText, "")
            currentVersions += row("tx-$prefix", ledgerId.value, "version-$prefix-v1")
            postings += row("posting-$prefix-0", "posting-set-$prefix", ledgerId.value, 0L, expense.first, expense.second, expense.third, 2L)
            postings += row("posting-$prefix-1", "posting-set-$prefix", ledgerId.value, 1L, assetFunding.first, assetFunding.second, assetFunding.third, 2L)
            postings += row("posting-$prefix-2", "posting-set-$prefix", ledgerId.value, 2L, creditFunding.first, creditFunding.second, creditFunding.third, 2L)
            reportTxs += ReportTx("EXPENSE", listOf(expense, assetFunding, creditFunding))
        }

        fun group(
            requestId: String,
            requestPrefix: String,
            candidatePrefix: String = requestPrefix,
            totalMinor: Long,
            assetAccount: String,
            assetLegMinor: Long,
            creditAccount: String,
            creditLegMinor: Long,
        ) {
            groups += row(ledgerId.value, "group-tx-$requestPrefix", "candidate-$candidatePrefix", "tx-$requestPrefix", requestId, totalMinor, confirmedAt)
            groupLegs += row(ledgerId.value, "group-tx-$requestPrefix", 1L, "asset", assetAccount, assetLegMinor)
            groupLegs += row(ledgerId.value, "group-tx-$requestPrefix", 2L, "liability", creditAccount, creditLegMinor)
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

        fun review(request: ImportDuplicateReviewRequest) {
            val digest = ImportDuplicateReviewFingerprint().digest(request)
            reviewRequests += row(ledgerId.value, request.identity.requestId.value, "review_duplicate", digest, "ACCEPTED", null)
            reviewSnapshots += row(
                ledgerId.value, request.identity.requestId.value, request.candidateId.value, request.expectedComparisonFingerprint,
                request.decision.name, request.reasonToken, request.reviewedAt, request.reviewerReference,
                request.generatedAt, request.reviewId.value,
            )
            duplicateHistory += row(
                ledgerId.value, request.candidateId.value, 2L, request.historyId.value, request.decision.name,
                request.identity.requestId.value, "status_transition",
            )
            reviewReceipts += row(
                ledgerId.value, request.identity.requestId.value, request.candidateId.value, request.reviewId.value,
                request.historyId.value, request.decision.name,
            )
        }

        fun confirmLink(request: P408ConfirmLinkRequest) {
            reconciliationRequests += row(ledgerId.value, request.requestId, "confirm_link", request.fingerprint(), "ACCEPTED", null)
            reconciliationSnapshots += row(
                ledgerId.value, request.requestId, request.evidenceId, request.candidateId, request.postingId,
                request.transactionId, request.amountMinor, request.currencyCode, request.currencyPrecision.toLong(),
                request.direction, request.accountId, request.responsibility.storageValue, request.basisVersion.toLong(),
                request.matchBasis.toSortedSet().joinToString(","), request.windowDays.toLong(), request.naturalDayDistance.toLong(),
                request.sourceOccurredAt, request.confirmedAt, "confirm_match",
            )
            evidenceLinks += row(
                ledgerId.value, request.linkId, request.evidenceId, request.postingId, request.transactionId,
                request.responsibility.storageValue, request.basisVersion.toLong(),
                request.matchBasis.toSortedSet().joinToString(","), request.candidateId, request.requestId, request.createdAt,
            )
            evidenceLinkHistory += row(ledgerId.value, request.linkId, 1L, "active", "confirmed", request.requestId, request.confirmedAt)
            postingReconciliations += row(ledgerId.value, request.reconciliationId, request.postingId, "CHECKED", 2L)
            postingReconciliationHistory += row(ledgerId.value, request.reconciliationId, 1L, "PENDING", null, request.requestId, request.sourceOccurredAt)
            postingReconciliationHistory += row(ledgerId.value, request.reconciliationId, 2L, "CHECKED", request.linkId, request.requestId, request.confirmedAt)
            reconciliationReceipts += row(ledgerId.value, request.requestId, "ACCEPTED", request.linkId, request.reconciliationId, 2L)
            // The linked posting's dimension row flips to CHECKED (the unlinked leg of
            // the same transfer keeps its virtual PENDING row).
            val checkedRow = ReconciliationDimensionRow(
                request.postingId, request.transactionId, request.accountId, P408ReconciliationStatus.CHECKED.name, listOf(request.linkId),
            )
            val rowIndex = reconciliationDimension.indexOfFirst { it.postingId == request.postingId }
            if (rowIndex >= 0) reconciliationDimension[rowIndex] = checkedRow else reconciliationDimension += checkedRow
        }

        /** A raw pre-seeded evidence_link row plus its owning request (ruling 8 leg 2 host). */
        fun rawLink(
            requestId: String,
            linkId: String,
            evidenceId: String,
            postingId: String,
            transactionId: String,
            responsibility: String,
        ) {
            reconciliationRequests += row(ledgerId.value, requestId, "confirm_link", "fp-$requestId", "ACCEPTED", null)
            evidenceLinks += row(
                ledgerId.value, linkId, evidenceId, postingId, transactionId, responsibility, 1L,
                "account,amount,currency,direction,occurred_at_window", "candidate-rl07-preseed", requestId, confirmedAt,
            )
        }

        fun state(accounts: List<Account>): P409FullState {
            val ledgerAccounts = accounts.filter { it.ledgerId == ledgerId }
            val accountKindById = ledgerAccounts.associate { it.id.value to it.kind }
            return P409FullState(
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
                duplicateReviewRequest = reviewRequests.sortedWith(rowComparator),
                duplicateReviewSnapshot = reviewSnapshots.sortedWith(rowComparator),
                duplicateReviewReceipt = reviewReceipts.sortedWith(rowComparator),
                reconciliationRequest = reconciliationRequests.sortedWith(rowComparator),
                reconciliationRequestSnapshot = reconciliationSnapshots.sortedWith(rowComparator),
                evidenceLink = evidenceLinks.sortedWith(rowComparator),
                evidenceLinkHistory = evidenceLinkHistory.sortedWith(rowComparator),
                postingReconciliation = postingReconciliations.sortedWith(rowComparator),
                postingReconciliationHistory = postingReconciliationHistory.sortedWith(rowComparator),
                reconciliationReceipt = reconciliationReceipts.sortedWith(rowComparator),
                ledgerTransaction = transactions.sortedWith(rowComparator),
                postingSet = postingSets.sortedWith(rowComparator),
                transactionVersion = versions.sortedWith(rowComparator),
                ledgerTransactionCurrentVersion = currentVersions.sortedWith(rowComparator),
                posting = postings.sortedWith(rowComparator),
                mixedPaymentGroup = groups.sortedWith(rowComparator),
                mixedPaymentGroupLeg = groupLegs.sortedWith(rowComparator),
                report = mapOf(ledgerId.value to reduceReport(reportTxs, ledgerAccounts, accountKindById)),
                reconciliationDimension = reconciliationDimension.sortedBy { it.postingId },
            )
        }

        private fun row(vararg values: Any?): List<Any?> = values.toList()
    }

    private fun row(vararg values: Any?): List<Any?> = values.toList()

    private fun hashOf(kind: ImportRecordKind, facts: ImportSourceFacts, profile: ImportPaymentProfile?) =
        fingerprint.digest(kind, facts, profile)

    // ---------- Test 1: RL-01 ordinary expense full chain ----------

    @Test
    fun rl01OrdinaryExpenseFullChainDuplicateIntakeAndReviewDisposition() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashRl01 = hashOf(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, null)

            // Checkpoint 1: intake + confirm of the first 45.80 row.
            val first = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rl01"))), BatchCommitIdSource(listOf(commitIds("rl01"))))
            assertIs<ImportIntakeResult.Accepted>(first.intake(intakeRequest("req-rl01", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)))
            val confirmed = assertIs<ImportCandidateDecisionResult.Accepted>(
                first.confirmOrdinary(ordinaryConfirmRequest("req-rl01-confirm", "candidate-rl01", hashRl01)),
            )
            assertEquals("tx-rl01", confirmed.receipt.transactionId?.value)
            expected.intake("req-rl01", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null, "rl01")
            expected.confirm(
                "req-rl01-confirm", "rl01",
                decisionRow = row(ledgerId.value, "req-rl01-confirm", "confirm", "candidate-rl01", hashRl01, "category-food", "account-asset-a", null, null, null, null, null, null, null, confirmedAt),
            )
            expected.formal("rl01", "EXPENSE", rl01Facts.occurredAt, Triple("expense-account-food", 4580L, "CNY"), Triple("account-asset-a", -4580L, "CNY"))
            var state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl01-first-chain")

            // Checkpoint 2: an equal row at another ordinal is an EXACT_BUSINESS_TUPLE
            // duplicate candidate deferred at creation.
            val second = executor(
                database, driver,
                BatchIntakeIdSource(listOf(intakeIds("rl01-dup", duplicates = listOf("duplicate-rl01" to "history-duplicate-rl01")))),
                BatchCommitIdSource(emptyList()),
            )
            assertIs<ImportIntakeResult.Accepted>(
                second.intake(intakeRequest("req-rl01-dup", 1, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)),
            )
            expected.intake(
                "req-rl01-dup", 1, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null, "rl01-dup",
                duplicates = listOf(Triple("duplicate-rl01", "history-duplicate-rl01", "source-rl01")),
            )
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl01-duplicate-intake")

            // Checkpoint 3: review CONFIRMED_DUPLICATE terminal — review tables populated,
            // the original formal graph untouched, zero second transaction.
            val review = reviewRequest(
                "review-rl01", "duplicate-rl01",
                comparisonDigest(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, "source-rl01-dup", "source-rl01"),
                ImportDuplicateStatus.CONFIRMED_DUPLICATE, "exact-duplicate",
            )
            assertIs<ImportDuplicateReviewResult.Accepted>(ReviewImportDuplicateCandidate(second.store).execute(review))
            expected.review(review)
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl01-review-terminal")
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())

            val projection = captureFullState(driver, accounts()).report.getValue(ledgerId.value)
            assertEquals(4580L, projection.externalExpenseMinor)
            assertEquals(4580L, projection.consumptionMinor)
            assertEquals(4580L, projection.externalCashOutflowMinor)
            assertEquals(-4580L, projection.netWorthChangeMinor)
        } finally {
            driver.close()
        }
    }

    // ---------- Test 2: RL-02 ordinary income full chain ----------

    @Test
    fun rl02OrdinaryIncomeFullChainWithIncomeReportDimension() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashRl02 = hashOf(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, null)

            val run = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rl02"))), BatchCommitIdSource(listOf(commitIds("rl02"))))
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl02", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, ImportCompleteness.VALID_COMPLETE, null)))
            val confirmed = assertIs<ImportCandidateDecisionResult.Accepted>(
                run.confirmOrdinary(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-rl02-confirm")),
                        candidateId = ImportCandidateId("candidate-rl02"),
                        expectedContentHash = hashRl02,
                        explicitConfirmedAt = confirmedAt,
                        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-salary"), AccountId("account-asset-a")),
                    ),
                ),
            )
            assertEquals("tx-rl02", confirmed.receipt.transactionId?.value)
            expected.intake("req-rl02", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, ImportCompleteness.VALID_COMPLETE, null, "rl02")
            expected.confirm(
                "req-rl02-confirm", "rl02",
                decisionRow = row(ledgerId.value, "req-rl02-confirm", "confirm", "candidate-rl02", hashRl02, "category-salary", "account-asset-a", null, null, null, null, null, null, null, confirmedAt),
            )
            expected.formal("rl02", "INCOME", rl02Facts.occurredAt, Triple("account-asset-a", 7250L, "CNY"), Triple("income-account-salary", -7250L, "CNY"))
            var state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl02-chain")

            // Replay of the original intake and confirm receipts: zero new rows.
            assertIs<ImportIntakeResult.NoChange>(run.intake(intakeRequest("req-rl02", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, ImportCompleteness.VALID_COMPLETE, null)))
            val replay = assertIs<ImportCandidateDecisionResult.NoChange>(
                run.confirmOrdinary(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-rl02-confirm")),
                        candidateId = ImportCandidateId("candidate-rl02"),
                        expectedContentHash = hashRl02,
                        explicitConfirmedAt = confirmedAt,
                        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-salary"), AccountId("account-asset-a")),
                    ),
                ),
            )
            assertEquals(confirmed.receipt, replay.receipt)
            assertFullState(state, captureFullState(driver, accounts()), "rl02-replay")

            val projection = captureFullState(driver, accounts()).report.getValue(ledgerId.value)
            assertEquals(7250L, projection.externalIncomeMinor)
            assertEquals(7250L, projection.externalCashInflowMinor)
            assertEquals(7250L, projection.netWorthChangeMinor)
            assertEquals(0L, projection.externalExpenseMinor)
        } finally {
            driver.close()
        }
    }

    // ---------- Test 3 (combo anchor a): RL-03 complete + missing leg + evidence link ----------

    @Test
    fun rl03TransferCompleteMissingLegAndEvidenceLinkCombo() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashComplete = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, null)
            val hashMissing = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, rl03MissingFacts, null)

            // Checkpoint 1: complete leg intake + ACCOUNT_TRANSFER confirm.
            val run = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rl03c"))), BatchCommitIdSource(listOf(commitIds("rl03c"))))
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl03c", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, ImportCompleteness.VALID_COMPLETE, null)))
            assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmTransfer(transferConfirmRequest("req-rl03c-confirm", "candidate-rl03c", hashComplete)))
            expected.intake("req-rl03c", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, ImportCompleteness.VALID_COMPLETE, null, "rl03c")
            expected.confirm(
                "req-rl03c-confirm", "rl03c",
                decisionRow = row(ledgerId.value, "req-rl03c-confirm", "confirm", "candidate-rl03c", hashComplete, null, null, "account-asset-a", "account-asset-b", null, null, null, null, null, confirmedAt),
            )
            expected.formal("rl03c", "ACCOUNT_TRANSFER", rl03CompleteFacts.occurredAt, Triple("account-asset-a", -3000L, "CNY"), Triple("account-asset-b", 3000L, "CNY"))
            var state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl03-complete-confirmed")

            // Checkpoint 2: confirmLink on the out posting advances PENDING -> CHECKED with
            // the full link/snapshot/receipt row set. The linking evidence is a same-instant
            // mirror of the RL-03 source carried in the posting's own Z-shaped temporal text:
            // the P4-08 store only links source and posting texts of equal temporal shape
            // (P408Matcher/store shared discipline), and the spine persists formal times as
            // UTC Z-form instants.
            val mirror = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rl03x"))), BatchCommitIdSource(emptyList()))
            val rl03MirrorFacts = ImportSourceFacts(3000, "CNY", 2, "2026-08-03T04:00:00Z", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
            assertIs<ImportIntakeResult.Accepted>(mirror.intake(intakeRequest("req-rl03x", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl03MirrorFacts, ImportCompleteness.VALID_COMPLETE, null)))
            expected.intake("req-rl03x", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl03MirrorFacts, ImportCompleteness.VALID_COMPLETE, null, "rl03x")
            val p408Store = SqlDelightP408ReconciliationStore(database, driver)
            val linkRequest = confirmLinkRequest(
                "req-rl03-link", "evidence-rl03x", "posting-rl03c-0", "tx-rl03c", 3000,
                "out", "account-asset-a", P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                rl03MirrorFacts.occurredAt, "link-rl03", "reconciliation-posting-rl03c-0",
            )
            val linked = assertIs<P408ReconciliationResult.Accepted>(p408Store.confirmLink(linkRequest))
            assertEquals(2L, linked.receipt.historySequence)
            expected.confirmLink(linkRequest)
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl03-link-checked")

            // Checkpoint 3: replay with fresh link ids returns the original receipt and
            // writes nothing.
            val replay = assertIs<P408ReconciliationResult.NoChange>(
                p408Store.confirmLink(linkRequest.copy(linkId = "link-rl03-replay", reconciliationId = "reconciliation-replay")),
            )
            assertEquals(linked.receipt, replay.receipt)
            assertFullState(state, captureFullState(driver, accounts()), "rl03-link-replay")

            // Checkpoint 4: the missing-leg row keeps its pending state through the same
            // sequence — intake accepted, confirm gate closed, zero formal writes.
            val missing = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rl03m"))), BatchCommitIdSource(emptyList()))
            assertIs<ImportIntakeResult.Accepted>(missing.intake(intakeRequest("req-rl03m", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, rl03MissingFacts, ImportCompleteness.VALID_COMPLETE, null)))
            expected.intake("req-rl03m", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, rl03MissingFacts, ImportCompleteness.VALID_COMPLETE, null, "rl03m")
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl03-missing-intake")
            val gateClosed = assertIs<ImportCandidateDecisionResult.Rejected>(
                missing.confirmTransfer(transferConfirmRequest("req-rl03m-confirm", "candidate-rl03m", hashMissing)),
            )
            assertEquals("SPINE_TRANSFER_NOT_CONFIRMABLE", gateClosed.diagnostic.code)
            assertFullState(state, captureFullState(driver, accounts()), "rl03-missing-stays-pending")
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-rl03m").executeAsOne().status)

            val projection = captureFullState(driver, accounts()).report.getValue(ledgerId.value)
            assertEquals(3000L, projection.internalTransferMinor)
            assertEquals(0L, projection.externalCashInflowMinor)
            assertEquals(0L, projection.externalCashOutflowMinor)
            assertEquals(0L, projection.netWorthChangeMinor)
        } finally {
            driver.close()
        }
    }

    // ---------- Test 4: RL-04 second-source routing, both ends + reconciliation advance ----------

    @Test
    fun rl04SecondSourceRoutingBothEndsAndReconciliationAdvance() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashComplete = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04CompleteFacts, null)
            val hashIncomplete = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04IncompleteFacts, null)

            // The yuebao-routing output shape as two variants: VALID_COMPLETE (route
            // resolved, confirmable) and VALID_INCOMPLETE (insufficient data, stays put).
            val run = executor(
                database, driver,
                BatchIntakeIdSource(listOf(intakeIds("rl04c"), intakeIds("rl04i"))),
                BatchCommitIdSource(listOf(commitIds("rl04c"))),
            )
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl04c", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04CompleteFacts, ImportCompleteness.VALID_COMPLETE, null)))
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl04i", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04IncompleteFacts, ImportCompleteness.VALID_INCOMPLETE, null)))
            expected.intake("req-rl04c", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04CompleteFacts, ImportCompleteness.VALID_COMPLETE, null, "rl04c")
            expected.intake("req-rl04i", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04IncompleteFacts, ImportCompleteness.VALID_INCOMPLETE, null, "rl04i")
            var state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl04-two-variants")
            assertEquals("incomplete", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-rl04i").executeAsOne().status)

            assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmTransfer(transferConfirmRequest("req-rl04c-confirm", "candidate-rl04c", hashComplete)))
            expected.confirm(
                "req-rl04c-confirm", "rl04c",
                decisionRow = row(ledgerId.value, "req-rl04c-confirm", "confirm", "candidate-rl04c", hashComplete, null, null, "account-asset-a", "account-asset-b", null, null, null, null, null, confirmedAt),
            )
            expected.formal("rl04c", "ACCOUNT_TRANSFER", rl04CompleteFacts.occurredAt, Triple("account-asset-a", -2000L, "CNY"), Triple("account-asset-b", 2000L, "CNY"))
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl04-complete-confirmed")

            // Both ends carry balances; confirmLink advances the yuebao-anchored to leg.
            // The linking evidence mirrors the source at the posting's Z-shaped instant
            // (equal temporal shape is the P4-08 linking discipline).
            val mirror = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rl04x"))), BatchCommitIdSource(emptyList()))
            val rl04MirrorFacts = ImportSourceFacts(2000, "CNY", 2, "2026-08-05T04:00:00Z", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
            assertIs<ImportIntakeResult.Accepted>(mirror.intake(intakeRequest("req-rl04x", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl04MirrorFacts, ImportCompleteness.VALID_COMPLETE, null)))
            expected.intake("req-rl04x", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl04MirrorFacts, ImportCompleteness.VALID_COMPLETE, null, "rl04x")
            val p408Store = SqlDelightP408ReconciliationStore(database, driver)
            val linkRequest = confirmLinkRequest(
                "req-rl04-link", "evidence-rl04x", "posting-rl04c-1", "tx-rl04c", 2000,
                "in", "account-asset-b", P408EvidenceResponsibility.DESTINATION_ASSET_POSTING,
                rl04MirrorFacts.occurredAt, "link-rl04", "reconciliation-posting-rl04c-1",
            )
            assertIs<P408ReconciliationResult.Accepted>(p408Store.confirmLink(linkRequest))
            expected.confirmLink(linkRequest)
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl04-link-advanced")

            val projection = captureFullState(driver, accounts()).report.getValue(ledgerId.value)
            assertEquals(2000L, projection.internalTransferMinor)
            assertEquals(-2000L, projection.balancesByAccount.getValue("account-asset-a"))
            assertEquals(2000L, projection.balancesByAccount.getValue("account-asset-b"))
            assertEquals(0L, projection.netWorthChangeMinor)
        } finally {
            driver.close()
        }
    }

    // ---------- Test 5: RL-05 credit three anchors restated ----------

    @Test
    fun rl05CreditThreeAnchorsLifecycleRestated() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashExpense = hashOf(ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, directProfile)
            val hashRepay = hashOf(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, rl05RepayFacts, repaymentProfile)
            val hashRefund = hashOf(ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05RefundFacts, refundProfile)

            // Anchor 1: credit expense — full externalExpense, zero purchase-day cash flow.
            val run = executor(
                database, driver,
                BatchIntakeIdSource(listOf(intakeIds("rl05e"), intakeIds("rl05r"), intakeIds("rl05f"))),
                BatchCommitIdSource(listOf(commitIds("rl05e"), commitIds("rl05r"), commitIds("rl05f"))),
            )
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl05e", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
            assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmCredit(creditExpenseConfirmRequest("req-rl05e-confirm", "candidate-rl05e", hashExpense)))
            expected.intake("req-rl05e", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "rl05e")
            expected.confirm(
                "req-rl05e-confirm", "rl05e",
                decisionRow = row(ledgerId.value, "req-rl05e-confirm", "confirm", "candidate-rl05e", hashExpense, "category-food", null, null, null, "account-credit-huabei", null, null, null, null, confirmedAt),
            )
            expected.formal("rl05e", "EXPENSE", rl05ExpenseFacts.occurredAt, Triple("expense-account-food", 10000L, "CNY"), Triple("account-credit-huabei", -10000L, "CNY"))
            var state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl05-expense")
            assertEquals(0L, captureFullState(driver, accounts()).report.getValue(ledgerId.value).externalCashOutflowMinor)

            // Anchor 2: repayment — an independent CREDIT_REPAYMENT, net worth unchanged.
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl05r", 1, ImportRecordKind.CREDIT_REPAYMENT_SOURCE, rl05RepayFacts, ImportCompleteness.VALID_COMPLETE, repaymentProfile)))
            assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmCredit(creditRepaymentConfirmRequest("req-rl05r-confirm", "candidate-rl05r", hashRepay)))
            expected.intake("req-rl05r", 1, ImportRecordKind.CREDIT_REPAYMENT_SOURCE, rl05RepayFacts, ImportCompleteness.VALID_COMPLETE, repaymentProfile, "rl05r")
            expected.confirm(
                "req-rl05r-confirm", "rl05r",
                decisionRow = row(ledgerId.value, "req-rl05r-confirm", "confirm", "candidate-rl05r", hashRepay, null, null, null, null, "account-credit-huabei", "account-asset-a", null, null, null, confirmedAt),
            )
            expected.formal("rl05r", "CREDIT_REPAYMENT", rl05RepayFacts.occurredAt, Triple("account-asset-a", -5620L, "CNY"), Triple("account-credit-huabei", 5620L, "CNY"))
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl05-repayment")

            // Anchor 3: refund — independent REFUND_RECEIPT, original transaction linked by
            // the decision snapshot and its version untouched.
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl05f", 2, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05RefundFacts, ImportCompleteness.VALID_COMPLETE, refundProfile)))
            assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmCredit(creditRefundConfirmRequest("req-rl05f-confirm", "candidate-rl05f", hashRefund, original = "tx-rl05e")))
            expected.intake("req-rl05f", 2, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05RefundFacts, ImportCompleteness.VALID_COMPLETE, refundProfile, "rl05f")
            expected.confirm(
                "req-rl05f-confirm", "rl05f",
                decisionRow = row(ledgerId.value, "req-rl05f-confirm", "confirm", "candidate-rl05f", hashRefund, "category-food", null, null, null, "account-credit-huabei", null, "tx-rl05e", null, null, confirmedAt),
            )
            expected.formal("rl05f", "REFUND_RECEIPT", rl05RefundFacts.occurredAt, Triple("account-credit-huabei", 1535L, "CNY"), Triple("expense-account-food", -1535L, "CNY"))
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl05-refund")

            assertEquals(
                1L,
                selectRows(driver, "SELECT count(*) FROM transaction_version WHERE transaction_id = 'tx-rl05e'", listOf(true)).single().single(),
            )
            val projection = captureFullState(driver, accounts()).report.getValue(ledgerId.value)
            assertEquals(8465L, projection.consumptionMinor)
            assertEquals(8465L, projection.externalExpenseMinor)
            assertEquals(5620L, projection.externalCashOutflowMinor)
            assertEquals(-8465L, projection.netWorthChangeMinor)
            assertEquals(-5620L, projection.balancesByAccount.getValue("account-asset-a"))
            assertEquals(-2845L, projection.balancesByAccount.getValue("account-credit-huabei"))
            assertEquals(8465L, projection.balancesByAccount.getValue("expense-account-food"))
        } finally {
            driver.close()
        }
    }

    // ---------- Test 6: RL-06 mixed three postings and group restated ----------

    @Test
    fun rl06MixedThreePostingsAndGroupRestated() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashMixed = hashOf(ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, mixedProfile)

            val run = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rl06"))), BatchCommitIdSource(listOf(commitIds3("rl06"))))
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl06", 0, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile)))
            assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmMixed(mixedConfirmRequest("req-rl06-confirm", "candidate-rl06", hashMixed)))
            expected.intake("req-rl06", 0, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile, "rl06")
            expected.confirm(
                "req-rl06-confirm", "rl06",
                decisionRow = row(ledgerId.value, "req-rl06-confirm", "confirm", "candidate-rl06", hashMixed, "category-food", null, null, null, "account-credit-huabei", "account-asset-a", null, 360L, 880L, confirmedAt),
            )
            expected.formal3(
                "rl06", rl06Facts.occurredAt,
                Triple("expense-account-food", 1240L, "CNY"),
                Triple("account-asset-a", -360L, "CNY"),
                Triple("account-credit-huabei", -880L, "CNY"),
            )
            expected.group(
                "req-rl06-confirm", "rl06", totalMinor = 1240L,
                assetAccount = "account-asset-a", assetLegMinor = 360L,
                creditAccount = "account-credit-huabei", creditLegMinor = 880L,
            )
            val state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl06-mixed")

            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(3L, database.ledgerQueries.countPostings().executeAsOne())
            val projection = captureFullState(driver, accounts()).report.getValue(ledgerId.value)
            assertEquals(1240L, projection.consumptionMinor)
            assertEquals(360L, projection.externalCashOutflowMinor)
            assertEquals(-1240L, projection.netWorthChangeMinor)
            assertEquals(-360L, projection.balancesByAccount.getValue("account-asset-a"))
            assertEquals(-880L, projection.balancesByAccount.getValue("account-credit-huabei"))
        } finally {
            driver.close()
        }
    }

    // ---------- Test 7: RL-07 platform-side mirror subset + F3 constraint leg + F5 concurrent leg ----------

    @Test
    fun rl07PlatformSideMirrorSubsetZeroSecondTransaction() {
        val path = Files.createTempFile("p409-rl07-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            lateinit var preReopen: P409FullState
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val expected = Expected()
                val hashRl03 = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, null)
                val hashRl04 = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04CompleteFacts, null)
                val rl04SecondFacts = rl04CompleteFacts.copy(amountMinor = 2500, occurredAt = "2026-08-05T14:00:00+08:00")
                val hashRl04Second = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04SecondFacts, null)

                // Fixtures: two confirmed transfers (A/B and C/D postings) plus two
                // evidence-host rows for the failure and concurrency legs.
                val run = executor(
                    database, driver,
                    BatchIntakeIdSource(
                        listOf(
                            intakeIds("rl07a"), intakeIds("rl07b"),
                            intakeIds("rl07i"), intakeIds("rl07pre"),
                            intakeIds("rl07x1"), intakeIds("rl07x2"), intakeIds("rl07x3"),
                        ),
                    ),
                    BatchCommitIdSource(listOf(commitIds("rl07a"), commitIds("rl07b"))),
                )
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl07a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, ImportCompleteness.VALID_COMPLETE, null)))
                assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmTransfer(transferConfirmRequest("req-rl07a-confirm", "candidate-rl07a", hashRl03)))
                expected.intake("req-rl07a", 0, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, ImportCompleteness.VALID_COMPLETE, null, "rl07a")
                expected.confirm(
                    "req-rl07a-confirm", "rl07a",
                    decisionRow = row(ledgerId.value, "req-rl07a-confirm", "confirm", "candidate-rl07a", hashRl03, null, null, "account-asset-a", "account-asset-b", null, null, null, null, null, confirmedAt),
                )
                expected.formal("rl07a", "ACCOUNT_TRANSFER", rl03CompleteFacts.occurredAt, Triple("account-asset-a", -3000L, "CNY"), Triple("account-asset-b", 3000L, "CNY"))
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl07b", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04SecondFacts, ImportCompleteness.VALID_COMPLETE, null)))
                assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmTransfer(transferConfirmRequest("req-rl07b-confirm", "candidate-rl07b", hashRl04Second)))
                expected.intake("req-rl07b", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04SecondFacts, ImportCompleteness.VALID_COMPLETE, null, "rl07b")
                expected.confirm(
                    "req-rl07b-confirm", "rl07b",
                    decisionRow = row(ledgerId.value, "req-rl07b-confirm", "confirm", "candidate-rl07b", hashRl04Second, null, null, "account-asset-a", "account-asset-b", null, null, null, null, null, confirmedAt),
                )
                expected.formal("rl07b", "ACCOUNT_TRANSFER", rl04SecondFacts.occurredAt, Triple("account-asset-a", -2500L, "CNY"), Triple("account-asset-b", 2500L, "CNY"))
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl07i", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04IncompleteFacts, ImportCompleteness.VALID_INCOMPLETE, null)))
                expected.intake("req-rl07i", 2, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04IncompleteFacts, ImportCompleteness.VALID_INCOMPLETE, null, "rl07i")
                val preHostFacts = ImportSourceFacts(500, "CNY", 2, "2026-08-05T14:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl07pre", 3, ImportRecordKind.ORDINARY_FLOW_SOURCE, preHostFacts, ImportCompleteness.VALID_COMPLETE, null)))
                expected.intake("req-rl07pre", 3, ImportRecordKind.ORDINARY_FLOW_SOURCE, preHostFacts, ImportCompleteness.VALID_COMPLETE, null, "rl07pre")
                // Mirror evidences in the postings' Z-shaped temporal text (equal temporal
                // shape is the P4-08 linking discipline; spine formal times are UTC
                // Z-form instants): x1 feeds leg 1 (out 3000 on tx-rl07a), x2 feeds leg 2
                // (in 3000 on the tx-rl07a to leg), x3 feeds leg 3 (out 2500 on tx-rl07b).
                val rl07x1Facts = ImportSourceFacts(3000, "CNY", 2, "2026-08-03T04:00:00Z", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
                val rl07x2Facts = ImportSourceFacts(3000, "CNY", 2, "2026-08-03T04:00:00Z", "in", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
                val rl07x3Facts = ImportSourceFacts(2500, "CNY", 2, "2026-08-05T06:00:00Z", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl07x1", 4, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl07x1Facts, ImportCompleteness.VALID_COMPLETE, null)))
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl07x2", 5, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl07x2Facts, ImportCompleteness.VALID_COMPLETE, null)))
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rl07x3", 6, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl07x3Facts, ImportCompleteness.VALID_COMPLETE, null)))
                expected.intake("req-rl07x1", 4, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl07x1Facts, ImportCompleteness.VALID_COMPLETE, null, "rl07x1")
                expected.intake("req-rl07x2", 5, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl07x2Facts, ImportCompleteness.VALID_COMPLETE, null, "rl07x2")
                expected.intake("req-rl07x3", 6, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl07x3Facts, ImportCompleteness.VALID_COMPLETE, null, "rl07x3")
                var state = expected.state(accounts())
                assertFullState(state, captureFullState(driver, accounts()), "rl07-fixtures")

                val p408Store = SqlDelightP408ReconciliationStore(database, driver)

                // Leg 1 (main path): synthetic mirror evidence -> exact posting decision ->
                // link/status change and zero second transaction.
                val leg1 = confirmLinkRequest(
                    "req-rl07-leg1", "evidence-rl07x1", "posting-rl07a-0", "tx-rl07a", 3000,
                    "out", "account-asset-a", P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                    rl07x1Facts.occurredAt, "link-rl07-leg1", "reconciliation-posting-rl07a-0",
                )
                val accepted = assertIs<P408ReconciliationResult.Accepted>(p408Store.confirmLink(leg1))
                expected.confirmLink(leg1)
                state = expected.state(accounts())
                assertFullState(state, captureFullState(driver, accounts()), "rl07-leg1")
                val leg1Replay = assertIs<P408ReconciliationResult.NoChange>(
                    p408Store.confirmLink(leg1.copy(linkId = "link-rl07-leg1-replay", reconciliationId = "reconciliation-replay")),
                )
                assertEquals(accepted.receipt, leg1Replay.receipt)
                assertFullState(state, captureFullState(driver, accounts()), "rl07-leg1-replay")
                assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())

                // Leg 2 (F3, ruling 8): constraint-driven confirm failure. A pre-seeded
                // link occupies the target link_id; the confirm fails on the write-phase
                // PK constraint, is typed-rejected with zero residue, the identity stays
                // retryable and the corrected retry accepts.
                driver.execute(null, "INSERT INTO reconciliation_request(ledger_id, request_id, operation, input_fingerprint, outcome) VALUES ('${ledgerId.value}','req-rl07-preseed','confirm_link','fp-req-rl07-preseed','ACCEPTED')", 0)
                driver.execute(
                    null,
                    "INSERT INTO evidence_link(ledger_id, link_id, evidence_id, posting_id, transaction_id, responsibility, basis_version, match_basis, candidate_id, request_id, created_at) VALUES " +
                        "('${ledgerId.value}','link-rl07-b','evidence-rl07pre','posting-rl07a-1','tx-rl07a','real_account_posting',1,'account,amount,currency,direction,occurred_at_window','candidate-rl07-preseed','req-rl07-preseed','$confirmedAt')",
                    0,
                )
                expected.rawLink("req-rl07-preseed", "link-rl07-b", "evidence-rl07pre", "posting-rl07a-1", "tx-rl07a", "real_account_posting")
                state = expected.state(accounts())
                assertFullState(state, captureFullState(driver, accounts()), "rl07-leg2-preseed")
                val failingRequest = confirmLinkRequest(
                    "req-rl07-leg2", "evidence-rl07x2", "posting-rl07a-1", "tx-rl07a", 3000,
                    "in", "account-asset-b", P408EvidenceResponsibility.DESTINATION_ASSET_POSTING,
                    rl07x2Facts.occurredAt, "link-rl07-b", "reconciliation-posting-rl07a-1",
                )
                val constraintRejected = assertIs<P408ReconciliationResult.Rejected>(p408Store.confirmLink(failingRequest))
                assertEquals("P408_RECONCILIATION_CONSTRAINT_VIOLATION", constraintRejected.code)
                assertFullState(state, captureFullState(driver, accounts()), "rl07-leg2-constraint-zero-residue")
                val corrected = confirmLinkRequest(
                    "req-rl07-leg2", "evidence-rl07x2", "posting-rl07a-1", "tx-rl07a", 3000,
                    "in", "account-asset-b", P408EvidenceResponsibility.DESTINATION_ASSET_POSTING,
                    rl07x2Facts.occurredAt, "link-rl07-leg2", "reconciliation-posting-rl07a-1",
                )
                assertIs<P408ReconciliationResult.Accepted>(p408Store.confirmLink(corrected))
                expected.confirmLink(corrected)
                state = expected.state(accounts())
                assertFullState(state, captureFullState(driver, accounts()), "rl07-leg2-corrected")

                // Leg 3 (F5): concurrent confirmLink on the same posting — single winner,
                // loser typed conflict with zero residue.
                val leg3a = confirmLinkRequest(
                    "req-rl07-leg3a", "evidence-rl07x3", "posting-rl07b-0", "tx-rl07b", 2500,
                    "out", "account-asset-a", P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                    rl07x3Facts.occurredAt, "link-rl07-leg3a", "reconciliation-posting-rl07b-0",
                )
                val leg3b = confirmLinkRequest(
                    "req-rl07-leg3b", "evidence-rl07x3", "posting-rl07b-0", "tx-rl07b", 2500,
                    "out", "account-asset-a", P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                    rl07x3Facts.occurredAt, "link-rl07-leg3b", "reconciliation-posting-rl07b-0",
                )
                val results = concurrentP408(url, listOf(leg3a, leg3b))
                val winner: P408ConfirmLinkRequest
                if (results[0] is P408ReconciliationResult.Accepted) {
                    winner = leg3a
                    assertIs<P408ReconciliationResult.Rejected>(results[1])
                } else {
                    winner = leg3b
                    assertIs<P408ReconciliationResult.Rejected>(results[0])
                    assertIs<P408ReconciliationResult.Accepted>(results[1])
                }
                expected.confirmLink(winner)
                state = expected.state(accounts())
                assertFullState(state, captureFullState(driver, accounts()), "rl07-leg3-single-winner")
                // Zero second transaction: the concurrent links create no formal rows,
                // the two fixture transfers remain the whole formal chain.
                assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
                preReopen = state
            }

            // Reopen keeps every leg's rows value-for-value.
            JdbcSqliteDriver(url).use { driver ->
                assertFullState(preReopen, captureFullState(driver, accounts()), "rl07-reopen")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---------- Test 8: RL-08 closed rows across kinds ----------

    @Test
    fun rl08ClosedRowsAcrossKindsStayZeroFunds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val closedRows = listOf(
                Quintuple("rl08o", ImportRecordKind.ORDINARY_FLOW_SOURCE, rl08ClosedFacts, null, 4),
                Quintuple("rl08t", ImportRecordKind.TRANSFER_FLOW_SOURCE, rl08TransferClosedFacts, null, 5),
                Quintuple("rl08c", ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl08CreditClosedFacts, directProfile, 6),
                Quintuple("rl08m", ImportRecordKind.MIXED_PAYMENT_SOURCE, rl08MixedClosedFacts, mixedProfile, 7),
            )
            val intakeBatches = closedRows.map { (prefix, _, _, _, _) -> intakeIds(prefix, duplicates = listOf("duplicate-$prefix" to "history-duplicate-$prefix")) }
            val run = executor(database, driver, BatchIntakeIdSource(intakeBatches), BatchCommitIdSource(emptyList()))
            closedRows.forEachIndexed { index, (prefix, kind, facts, profile, ordinal) ->
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-$prefix", ordinal, kind, facts, ImportCompleteness.VALID_COMPLETE, profile)))
                expected.intake(
                    "req-$prefix", ordinal, kind, facts, ImportCompleteness.VALID_COMPLETE, profile, prefix,
                    duplicates = listOf(Triple("duplicate-$prefix", "history-duplicate-$prefix", null)),
                )
            }
            var state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "rl08-four-kinds")

            // Zero funds movement: no formal rows, zero balances, zero report change.
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            val projection = state.report.getValue(ledgerId.value)
            assertEquals(0L, projection.netWorthChangeMinor)
            assertTrue(projection.balancesByAccount.values.all { it == 0L })
            closedRows.forEach { (prefix, _, _, _, _) ->
                assertEquals("incomplete", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-$prefix").executeAsOne().status)
                assertEquals("CLOSED_OR_FAILED_NO_FUNDS", selectRows(driver, "SELECT kind FROM import_duplicate_candidate WHERE candidate_id = 'duplicate-$prefix'", listOf(false)).single().single())
            }

            // Re-import idempotence: every closed row replays as a zero-write NoChange.
            closedRows.forEachIndexed { index, (prefix, kind, facts, profile, ordinal) ->
                assertIs<ImportIntakeResult.NoChange>(run.intake(intakeRequest("req-$prefix-replay", ordinal, kind, facts, ImportCompleteness.VALID_COMPLETE, profile)))
            }
            assertFullState(state, captureFullState(driver, accounts()), "rl08-reimport-idempotent")
        } finally {
            driver.close()
        }
    }

    // ---------- Test 9 (combo anchor b): credit + mixed duplicate review dispositions ----------

    @Test
    fun creditAndMixedDuplicateReviewClaimDispositionFinalStates() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val expected = Expected()
            val hashExpense = hashOf(ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, directProfile)
            val hashMixed = hashOf(ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, mixedProfile)

            // Credit leg: two equal rows -> exact-tuple duplicate -> CONFIRMED_DUPLICATE
            // blocks the second candidate's formalization.
            val creditRun = executor(
                database, driver,
                BatchIntakeIdSource(
                    listOf(
                        intakeIds("crd-a"),
                        intakeIds("crd-b", duplicates = listOf("duplicate-crd" to "history-duplicate-crd")),
                        intakeIds("mix-a"),
                    ),
                ),
                BatchCommitIdSource(listOf(commitIds("crd-a"), commitIds3("mix-b"))),
            )
            assertIs<ImportIntakeResult.Accepted>(creditRun.intake(intakeRequest("req-crd-a", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
            expected.intake("req-crd-a", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "crd-a")
            assertIs<ImportCandidateDecisionResult.Accepted>(creditRun.confirmCredit(creditExpenseConfirmRequest("req-crd-a-confirm", "candidate-crd-a", hashExpense)))
            expected.confirm(
                "req-crd-a-confirm", "crd-a",
                decisionRow = row(ledgerId.value, "req-crd-a-confirm", "confirm", "candidate-crd-a", hashExpense, "category-food", null, null, null, "account-credit-huabei", null, null, null, null, confirmedAt),
            )
            expected.formal("crd-a", "EXPENSE", rl05ExpenseFacts.occurredAt, Triple("expense-account-food", 10000L, "CNY"), Triple("account-credit-huabei", -10000L, "CNY"))
            assertIs<ImportIntakeResult.Accepted>(creditRun.intake(intakeRequest("req-crd-b", 1, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
            expected.intake(
                "req-crd-b", 1, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile, "crd-b",
                duplicates = listOf(Triple("duplicate-crd", "history-duplicate-crd", "source-crd-a")),
            )
            var state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "b-credit-duplicate")

            val confirmedDuplicate = reviewRequest(
                "review-crd", "duplicate-crd",
                comparisonDigest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, "source-crd-b", "source-crd-a"),
                ImportDuplicateStatus.CONFIRMED_DUPLICATE, "exact",
            )
            assertIs<ImportDuplicateReviewResult.Accepted>(ReviewImportDuplicateCandidate(creditRun.store).execute(confirmedDuplicate))
            expected.review(confirmedDuplicate)
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "b-credit-review")
            val blocked = assertIs<ImportCandidateDecisionResult.Rejected>(
                creditRun.confirmCredit(creditExpenseConfirmRequest("req-crd-b-confirm", "candidate-crd-b", hashExpense)),
            )
            assertEquals("SPINE_DUPLICATE_NOT_CONFIRMABLE", blocked.diagnostic.code)
            assertFullState(state, captureFullState(driver, accounts()), "b-credit-blocked")

            // Mixed leg: two equal rows -> exact-tuple duplicate -> DISMISSED_LOOKALIKE ->
            // the distinct re-entry still formalizes.
            assertIs<ImportIntakeResult.Accepted>(creditRun.intake(intakeRequest("req-mix-a", 2, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile)))
            expected.intake("req-mix-a", 2, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile, "mix-a")
            val mixedDuplicateRun = executor(
                database, driver,
                BatchIntakeIdSource(listOf(intakeIds("mix-b", duplicates = listOf("duplicate-mix" to "history-duplicate-mix")))),
                BatchCommitIdSource(emptyList()),
            )
            assertIs<ImportIntakeResult.Accepted>(mixedDuplicateRun.intake(intakeRequest("req-mix-b", 3, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile)))
            expected.intake(
                "req-mix-b", 3, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile, "mix-b",
                duplicates = listOf(Triple("duplicate-mix", "history-duplicate-mix", "source-mix-a")),
            )
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "b-mixed-duplicate")

            val dismissed = reviewRequest(
                "review-mix", "duplicate-mix",
                comparisonDigest(ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, "source-mix-b", "source-mix-a"),
                ImportDuplicateStatus.DISMISSED_LOOKALIKE, "manual-dismissal",
            )
            assertIs<ImportDuplicateReviewResult.Accepted>(ReviewImportDuplicateCandidate(mixedDuplicateRun.store).execute(dismissed))
            expected.review(dismissed)
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "b-mixed-dismissed")

            assertIs<ImportCandidateDecisionResult.Accepted>(creditRun.confirmMixed(mixedConfirmRequest("req-mix-b-confirm", "candidate-mix-b", hashMixed)))
            expected.confirm(
                "req-mix-b-confirm", "mix-b",
                decisionRow = row(ledgerId.value, "req-mix-b-confirm", "confirm", "candidate-mix-b", hashMixed, "category-food", null, null, null, "account-credit-huabei", "account-asset-a", null, 360L, 880L, confirmedAt),
            )
            expected.formal3(
                "mix-b", rl06Facts.occurredAt,
                Triple("expense-account-food", 1240L, "CNY"),
                Triple("account-asset-a", -360L, "CNY"),
                Triple("account-credit-huabei", -880L, "CNY"),
            )
            expected.group(
                "req-mix-b-confirm", "mix-b", totalMinor = 1240L,
                assetAccount = "account-asset-a", assetLegMinor = 360L,
                creditAccount = "account-credit-huabei", creditLegMinor = 880L,
            )
            state = expected.state(accounts())
            assertFullState(state, captureFullState(driver, accounts()), "b-mixed-formalized")

            // Exactly two formal transactions: the confirmed-duplicate credit row never
            // formalized, the dismissed mixed row did.
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(5L, database.ledgerQueries.countPostings().executeAsOne())
        } finally {
            driver.close()
        }
    }

    // ---------- Test 10: closure form survives reopen ----------

    @Test
    fun closureFormSurvivesReopenAndReplaysOriginalReceipts() {
        val path = Files.createTempFile("p409-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            lateinit var preReopen: P409FullState
            JdbcSqliteDriver(url).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val expected = Expected()
                val hashRl01 = hashOf(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, null)
                val hashRl03 = hashOf(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, null)

                val run = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rz1"), intakeIds("rz3"))), BatchCommitIdSource(listOf(commitIds("rz1"), commitIds("rz3"))))
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rz1", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)))
                assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmOrdinary(ordinaryConfirmRequest("req-rz1-confirm", "candidate-rz1", hashRl01)))
                expected.intake("req-rz1", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null, "rz1")
                expected.confirm(
                    "req-rz1-confirm", "rz1",
                    decisionRow = row(ledgerId.value, "req-rz1-confirm", "confirm", "candidate-rz1", hashRl01, "category-food", "account-asset-a", null, null, null, null, null, null, null, confirmedAt),
                )
                expected.formal("rz1", "EXPENSE", rl01Facts.occurredAt, Triple("expense-account-food", 4580L, "CNY"), Triple("account-asset-a", -4580L, "CNY"))
                assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-rz3", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, ImportCompleteness.VALID_COMPLETE, null)))
                assertIs<ImportCandidateDecisionResult.Accepted>(run.confirmTransfer(transferConfirmRequest("req-rz3-confirm", "candidate-rz3", hashRl03)))
                expected.intake("req-rz3", 1, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, ImportCompleteness.VALID_COMPLETE, null, "rz3")
                expected.confirm(
                    "req-rz3-confirm", "rz3",
                    decisionRow = row(ledgerId.value, "req-rz3-confirm", "confirm", "candidate-rz3", hashRl03, null, null, "account-asset-a", "account-asset-b", null, null, null, null, null, confirmedAt),
                )
                expected.formal("rz3", "ACCOUNT_TRANSFER", rl03CompleteFacts.occurredAt, Triple("account-asset-a", -3000L, "CNY"), Triple("account-asset-b", 3000L, "CNY"))
                val p408Store = SqlDelightP408ReconciliationStore(database, driver)
                // Mirror evidence in the posting's Z-shaped temporal text (the P4-08
                // store only links source and posting texts of equal temporal shape).
                val mirror = executor(database, driver, BatchIntakeIdSource(listOf(intakeIds("rzx"))), BatchCommitIdSource(emptyList()))
                val rzxFacts = ImportSourceFacts(3000, "CNY", 2, "2026-08-03T04:00:00Z", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
                assertIs<ImportIntakeResult.Accepted>(mirror.intake(intakeRequest("req-rzx", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, rzxFacts, ImportCompleteness.VALID_COMPLETE, null)))
                expected.intake("req-rzx", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, rzxFacts, ImportCompleteness.VALID_COMPLETE, null, "rzx")
                val linkRequest = confirmLinkRequest(
                    "req-rz3-link", "evidence-rzx", "posting-rz3-0", "tx-rz3", 3000,
                    "out", "account-asset-a", P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                    rzxFacts.occurredAt, "link-rz3", "reconciliation-posting-rz3-0",
                )
                val linkResult = p408Store.confirmLink(linkRequest)
                assertIs<P408ReconciliationResult.Accepted>(linkResult)
                expected.confirmLink(linkRequest)
                preReopen = expected.state(accounts())
                assertFullState(preReopen, captureFullState(driver, accounts()), "reopen-preclose")
            }

            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertFullState(preReopen, captureFullState(driver, accounts()), "reopen-compare")

                // Replays after reopen return the original receipts with zero writes.
                val replayRun = executor(database, driver, BatchIntakeIdSource(emptyList()), BatchCommitIdSource(emptyList()))
                assertIs<ImportIntakeResult.NoChange>(replayRun.intake(intakeRequest("req-rz1", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)))
                assertIs<ImportCandidateDecisionResult.NoChange>(replayRun.confirmOrdinary(ordinaryConfirmRequest("req-rz1-confirm", "candidate-rz1", hashOf(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, null))))
                assertFullState(preReopen, captureFullState(driver, accounts()), "reopen-replay")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---------- Test 11 (F5): concurrent credit + mixed confirms ----------

    @Test
    fun concurrentCreditAndMixedConfirmsHaveSingleWinnerAndLoserReplay() {
        val path = Files.createTempFile("p409-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            JdbcSqliteDriver(url).use { LedgerDatabase.Schema.create(it) }
            val hashExpense = hashOf(ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, directProfile)
            val hashMixed = hashOf(ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, mixedProfile)
            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                val setup = executor(
                    database, driver,
                    BatchIntakeIdSource(listOf(intakeIds("cc"), intakeIds("cm"))),
                    BatchCommitIdSource(emptyList()),
                )
                assertIs<ImportIntakeResult.Accepted>(setup.intake(intakeRequest("req-cc", 0, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
                assertIs<ImportIntakeResult.Accepted>(setup.intake(intakeRequest("req-cm", 1, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile)))
            }

            // Same confirm request from two threads per candidate: the shared commit id
            // source is consumed exactly once and the loser replays the winner receipt.
            val creditIds = BatchCommitIdSource(listOf(commitIds("cc")))
            val creditRequest = creditExpenseConfirmRequest("req-cc-confirm", "candidate-cc", hashExpense)
            val creditResults = concurrentConfirms(url, creditIds, creditRequest, credit = true)
            assertEquals(1, creditResults.count { it is ImportCandidateDecisionResult.Accepted })
            assertEquals(1, creditResults.count { it is ImportCandidateDecisionResult.NoChange })
            assertEquals(1, creditIds.calls.get())

            val mixedIds = BatchCommitIdSource(listOf(commitIds3("cm")))
            val mixedRequest = mixedConfirmRequest("req-cm-confirm", "candidate-cm", hashMixed)
            val mixedResults = concurrentConfirms(url, mixedIds, mixedRequest, credit = false)
            assertEquals(1, mixedResults.count { it is ImportCandidateDecisionResult.Accepted })
            assertEquals(1, mixedResults.count { it is ImportCandidateDecisionResult.NoChange })
            assertEquals(1, mixedIds.calls.get())

            JdbcSqliteDriver(url).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(5L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countImportConfirmations().executeAsOne())
                assertEquals(1L, selectRows(driver, "SELECT count(*) FROM mixed_payment_group", listOf(true)).single().single())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    // ---------- Test 12 (F4): ordinary/income confirm domain failures ----------

    @Test
    fun ordinaryAndIncomeConfirmDomainFailuresStayPendingZeroWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val hashRl01 = hashOf(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, null)
            val hashRl02 = hashOf(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, null)

            val run = executor(
                database, driver,
                BatchIntakeIdSource(listOf(intakeIds("df-o"), intakeIds("df-i"))),
                BatchCommitIdSource((1..6).map { commitIds("df-attempt-$it") }),
            )
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-df-o", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)))
            assertIs<ImportIntakeResult.Accepted>(run.intake(intakeRequest("req-df-i", 1, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, ImportCompleteness.VALID_COMPLETE, null)))

            fun assertDomainFailure(result: ImportCandidateDecisionResult) {
                val rejected = assertIs<ImportCandidateDecisionResult.Rejected>(result)
                assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", rejected.diagnostic.code)
            }

            // Ordinary leg: unknown category; funding account not held (cross-ledger);
            // funding account of the wrong kind (expense account).
            assertDomainFailure(run.confirmOrdinary(ordinaryConfirmRequest("req-df-1", "candidate-df-o", hashRl01, category = "category-unknown")))
            assertDomainFailure(run.confirmOrdinary(ordinaryConfirmRequest("req-df-2", "candidate-df-o", hashRl01, funding = "account-asset-other-ledger")))
            assertDomainFailure(run.confirmOrdinary(ordinaryConfirmRequest("req-df-3", "candidate-df-o", hashRl01, funding = "expense-account-food")))
            // Income leg: expense-kind category on an income confirm; non-asset funding.
            assertDomainFailure(
                run.confirmOrdinary(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-df-4")),
                        candidateId = ImportCandidateId("candidate-df-i"),
                        expectedContentHash = hashRl02,
                        explicitConfirmedAt = confirmedAt,
                        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-food"), AccountId("account-asset-a")),
                    ),
                ),
            )
            assertDomainFailure(
                run.confirmOrdinary(
                    ImportCandidateConfirmRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-df-5")),
                        candidateId = ImportCandidateId("candidate-df-i"),
                        expectedContentHash = hashRl02,
                        explicitConfirmedAt = confirmedAt,
                        decisionFields = ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("category-salary"), AccountId("expense-account-food")),
                    ),
                ),
            )

            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-df-o").executeAsOne().status)
            assertEquals("pending_confirmation", database.ledgerQueries.selectImportCandidateCurrentStatus(ledgerId.value, "candidate-df-i").executeAsOne().status)
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countImportConfirmations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countImportDecisionSnapshots().executeAsOne())

            // The failed claims stay retryable: a corrected decision on the same identity
            // now accepts.
            val corrected = assertIs<ImportCandidateDecisionResult.Accepted>(
                run.confirmOrdinary(ordinaryConfirmRequest("req-df-1", "candidate-df-o", hashRl01)),
            )
            assertEquals("tx-df-attempt-6", corrected.receipt.transactionId?.value)
        } finally {
            driver.close()
        }
    }

    // ---------- helpers ----------

    private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    private fun concurrentP408(url: String, requests: List<P408ConfirmLinkRequest>): List<P408ReconciliationResult> {
        val pool = Executors.newFixedThreadPool(requests.size)
        val ready = CountDownLatch(requests.size)
        val start = CountDownLatch(1)
        return try {
            val futures = requests.map { request ->
                pool.submit<P408ReconciliationResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    JdbcSqliteDriver(url).use { driver ->
                        SqlDelightP408ReconciliationStore(LedgerDatabase(driver), driver).confirmLink(request)
                    }
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun concurrentConfirms(
        url: String,
        commitIds: BatchCommitIdSource,
        request: ImportCandidateConfirmRequest,
        credit: Boolean,
    ): List<ImportCandidateDecisionResult> {
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        return try {
            val futures = (1..2).map {
                pool.submit<ImportCandidateDecisionResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    JdbcSqliteDriver(url).use { driver ->
                        val database = LedgerDatabase(driver)
                        val store = SqlDelightImportSpineStore(database, driver)
                        val factory = if (credit) {
                            CreditFlowFormalFactory(catalog(), originalExpenseReader(driver))
                        } else {
                            MixedPaymentFlowFormalFactory(catalog())
                        }
                        ConfirmImportCandidate(store, commitIds, factory, catalog()).execute(request)
                    }
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
