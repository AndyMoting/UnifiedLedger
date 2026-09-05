package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.CreditFlowFormalFactory
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportPaymentProfile
import com.unifiedledger.application.ImportPaymentVariant
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.MixedPaymentFlowFormalFactory
import com.unifiedledger.application.P408MaterializationRequest
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CreditRefundOriginalExpense
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Second-batch contract closure (D-112) — six-kind spine success-path
 * materialization suite (TP-08), mixed three-posting precision (TP-09),
 * projection-injection rollback/retry (TP-10), the R3 positive authority
 * triggers for P408_PROJECTION_ABSENT / P408_PROJECTION_NOT_READY, and the
 * combined 99@0 -> 9900@2 confirmLink success path (TP-01). Values are
 * deterministic and tie to the frozen O-2/D-111 normalized totals.
 */
class P408ProjectionSixKindMaterializationTest {
    private var path: Path? = null
    private var driver: JdbcSqliteDriver? = null

    @AfterTest
    fun tearDown() {
        driver?.close()
        path?.let { Files.deleteIfExists(it) }
    }

    private fun newWorld(): Triple<LedgerDatabase, JdbcSqliteDriver, LedgerCatalog> {
        val p = Files.createTempFile("ledger-data-p408-sixkind-", ".db")
        path = p
        val d = JdbcSqliteDriver("jdbc:sqlite:${p.absolutePathString()}", projectionSqliteProperties())
        driver = d
        LedgerDatabase.Schema.create(d)
        return Triple(LedgerDatabase(d), d, catalog())
    }

    private fun ledgerId(): LedgerId = LedgerId("ledger-k")

    private fun cny(): CurrencyUnit = CurrencyUnit("CNY", 2)

    private fun catalog(): LedgerCatalog =
        when (
            val result =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(AccountId("account-asset-a"), ledgerId(), AccountKind.ASSET, cny(), ownedByUser = true, realAccount = true),
                            Account(AccountId("account-credit-lia"), ledgerId(), AccountKind.LIABILITY, cny(), ownedByUser = true, realAccount = true),
                            Account(AccountId("expense-account-food"), ledgerId(), AccountKind.EXPENSE, cny(), ownedByUser = false, realAccount = false),
                            Account(AccountId("account-bank-b"), ledgerId(), AccountKind.ASSET, cny(), ownedByUser = true, realAccount = true),
                        ),
                    categories =
                        listOf(
                            Category(CategoryId("category-primary-food"), ledgerId(), parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                            Category(CategoryId("category-food"), ledgerId(), parentId = CategoryId("category-primary-food"), postingAccountId = AccountId("expense-account-food"), active = true, kind = CategoryKind.EXPENSE),
                        ),
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("sixkind catalog failure: ${result.violation}")
        }

    private fun fingerprint(): ImportContentFingerprint = ImportContentFingerprint()

    private class CountingIntakeIds(
        private val prefix: String,
    ) : ImportIntakeIdSource {
        private var n = 0

        override fun next(): ImportIntakeIds {
            n += 1
            return ImportIntakeIds(
                sourceId = com.unifiedledger.application.ImportSourceId("$prefix-source-$n"),
                evidenceId = com.unifiedledger.application.ImportEvidenceId("$prefix-evidence-$n"),
                candidateId = com.unifiedledger.application.ImportCandidateId("$prefix-candidate-$n"),
                statusHistoryId = com.unifiedledger.application.ImportStatusHistoryId("$prefix-status-$n"),
            )
        }
    }

    private class CountingCommitIds(
        private val prefix: String,
        private val postingCount: Int = 2,
    ) : ImportIdSource {
        private var n = 0

        override fun next(): com.unifiedledger.application.ImportCommitIds {
            n += 1
            val k = n - 1
            return com.unifiedledger.application.ImportCommitIds(
                confirmationId = com.unifiedledger.application.ImportConfirmationId("$prefix-confirmation-$k"),
                statusHistoryId = com.unifiedledger.application.ImportStatusHistoryId("$prefix-confirm-status-$k"),
                formalIds =
                    com.unifiedledger.application.ImportFormalIds(
                        transactionId = TransactionId("$prefix-tx-$k"),
                        versionId = TransactionVersionId("$prefix-version-$k"),
                        postingSetId = PostingSetId("$prefix-posting-set-$k"),
                        postingIds = (1..postingCount).map { i -> PostingId("$prefix-posting-$i-$k") },
                    ),
            )
        }
    }

    private fun intake(
        db: LedgerDatabase,
        d: JdbcSqliteDriver,
        requestId: String,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        profile: ImportPaymentProfile?,
        prefix: String,
        ordinal: Int = 0,
    ): ImportCandidateId {
        val store = SqlDelightImportSpineStore(db, d)
        val result =
            ExecuteImportIntake(store, CountingIntakeIds(prefix), fingerprint()).execute(
                ImportIntakeRequest(
                    identity = com.unifiedledger.application.ImportRequestIdentity(ledgerId(), com.unifiedledger.application.ImportRequestId(requestId)),
                    inputRef = "batch-$prefix",
                    recordOrdinal = ordinal,
                    recordKind = kind,
                    facts = facts,
                    completeness = ImportCompleteness.VALID_COMPLETE,
                    candidateGeneratedAt = "2026-08-20T13:00:00+08:00",
                    paymentProfile = profile,
                ),
            )
        val accepted = assertIs<com.unifiedledger.application.ImportIntakeResult.Accepted>(result)
        return com.unifiedledger.application.ImportCandidateId(
            accepted.returnedIds.first { it.kind == com.unifiedledger.application.ImportReturnedIdKind.CANDIDATE }.id,
        )
    }

    private fun confirm(
        db: LedgerDatabase,
        d: JdbcSqliteDriver,
        requestId: String,
        candidateId: ImportCandidateId,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        fields: ImportConfirmDecisionFields,
        factory: ImportCandidateFormalFactory,
        prefix: String,
        failure: ImportSpineFailureInjector = ImportSpineFailureInjector { },
        paymentProfile: ImportPaymentProfile? = null,
        postingCount: Int = 2,
    ): ImportCandidateDecisionResult {
        val store = SqlDelightImportSpineStore(db, d, failure)
        val result =
            ConfirmImportCandidate(store, CountingCommitIds(prefix, postingCount), factory, catalog()).execute(
                ImportCandidateConfirmRequest(
                    identity = com.unifiedledger.application.ImportRequestIdentity(ledgerId(), com.unifiedledger.application.ImportRequestId(requestId)),
                    candidateId = candidateId,
                    expectedContentHash = fingerprint().digest(kind, facts, paymentProfile),
                    explicitConfirmedAt = facts.occurredAt,
                    decisionFields = fields,
                ),
            )
        return result
    }

    // ---------- TP-08: four credit/mixed flows each materialize in-transaction ----------

    private fun creditFacts(
        amount: Long,
        direction: String,
    ): ImportSourceFacts =
        ImportSourceFacts(
            amount,
            "CNY",
            2,
            "2026-08-20T12:00:00+08:00",
            direction,
            "settled",
            ImportFundingState.SETTLED,
            IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            1,
        )

    @Test
    fun tp08CreditExpenseMaterializesSameTransaction() {
        val (db, d, _) = newWorld()
        val kind = ImportRecordKind.CREDIT_EXPENSE_SOURCE
        val facts = creditFacts(10000, "out")
        val candidate = intake(db, d, "req-k-ce", kind, facts, ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗"), "k-ce")
        val accepted =
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    db,
                    d,
                    "req-k-ce-confirm",
                    candidate,
                    kind,
                    facts,
                    ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("account-credit-lia")),
                    CreditFlowFormalFactory(catalog(), noOriginalExpense()),
                    "k-ce",
                    paymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗"),
                ),
            )
        assertProjectionRow(db, "k-ce-evidence-1", "account-credit-lia", 10000L)
        assertEquals("k-ce-confirmation-0", accepted.returnedIds.first().id)
    }

    @Test
    fun tp08CreditRepaymentMaterializesOnLiabilityAccount() {
        val (db, d, _) = newWorld()
        val kind = ImportRecordKind.CREDIT_REPAYMENT_SOURCE
        val facts = creditFacts(5620, "out")
        val candidate = intake(db, d, "req-k-rp", kind, facts, ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, "余额宝", null), "k-rp")
        assertIs<ImportCandidateDecisionResult.Accepted>(
            confirm(
                db,
                d,
                "req-k-rp-confirm",
                candidate,
                kind,
                facts,
                ImportConfirmDecisionFields.CreditRepaymentFlow(AccountId("account-asset-a"), AccountId("account-credit-lia")),
                CreditFlowFormalFactory(catalog(), noOriginalExpense()),
                "k-rp",
                paymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, "余额宝", null),
            ),
        )
        assertProjectionRow(db, "k-rp-evidence-1", "account-credit-lia", 5620L)
    }

    @Test
    fun tp08CreditRefundMaterializesOnLiabilityAccountAfterOriginal() {
        val (db, d, _) = newWorld()
        val kind = ImportRecordKind.CREDIT_EXPENSE_SOURCE
        val facts = creditFacts(3000, "out")
        val candidate = intake(db, d, "req-k-re", kind, facts, ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗"), "k-re")
        val accepted =
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    db,
                    d,
                    "req-k-re-confirm",
                    candidate,
                    kind,
                    facts,
                    ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("account-credit-lia")),
                    CreditFlowFormalFactory(catalog(), noOriginalExpense()),
                    "k-re",
                    paymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗"),
                ),
            )
        val originalTx = accepted.returnedIds.first { it.kind == com.unifiedledger.application.ImportReturnedIdKind.TRANSACTION }.id
        val refundKind = ImportRecordKind.CREDIT_EXPENSE_SOURCE
        val refundFacts = creditFacts(3000, "in")
        val refundCandidate = intake(db, d, "req-k-rf", refundKind, refundFacts, ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "花呗"), "k-rf")
        assertIs<ImportCandidateDecisionResult.Accepted>(
            confirm(
                db,
                d,
                "req-k-rf-confirm",
                refundCandidate,
                refundKind,
                refundFacts,
                ImportConfirmDecisionFields.CreditExpenseRefundFlow(CategoryId("category-food"), AccountId("account-credit-lia"), TransactionId(originalTx)),
                CreditFlowFormalFactory(catalog(), refundProvider(db, d, originalTx)),
                "k-rf",
                paymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "花呗"),
            ),
        )
        assertProjectionRow(db, "k-rf-evidence-1", "account-credit-lia", 3000L)
    }

    // ---------- TP-09: mixed three postings, leg-sum validated total projection ----------

    @Test
    fun tp09MixedThreePostingsPrecisionAndLegSumProjection() {
        val (db, d, _) = newWorld()
        val kind = ImportRecordKind.MIXED_PAYMENT_SOURCE
        val facts = creditFacts(1240, "out")
        val candidate = intake(db, d, "req-k-mx", kind, facts, ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, "余额宝", "花呗"), "k-mx")
        assertIs<ImportCandidateDecisionResult.Accepted>(
            confirm(
                db,
                d,
                "req-k-mx-confirm",
                candidate,
                kind,
                facts,
                ImportConfirmDecisionFields.MixedPaymentFlow(
                    CategoryId("category-food"),
                    AccountId("account-asset-a"),
                    AccountId("account-credit-lia"),
                    assetLegMinor = 360,
                    creditLegMinor = 880,
                ),
                MixedPaymentFlowFormalFactory(catalog()),
                "k-mx",
                paymentProfile = ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, "余额宝", "花呗"),
                postingCount = 3,
            ),
        )
        // Three postings, all expressed at target precision 2, balanced legs.
        val rows =
            db.ledgerQueries
                .selectP408EvidenceSourceFacts("ledger-k", "k-mx-evidence-1")
                .executeAsOne()
        assertEquals(1240L, rows.amount_minor)
        assertEquals(2L, rows.currency_precision)
        val mixedTotal = db.ledgerQueries.selectPostingsForSet("ledger-k", "k-mx-posting-set-0").executeAsList()
        assertEquals(3, mixedTotal.size)
        mixedTotal.forEach { assertEquals(2L, it.currency_precision) }
        val positive = mixedTotal.first { it.amount_minor > 0 }
        val negatives = mixedTotal.filter { it.amount_minor < 0 }
        assertEquals(positive.amount_minor, negatives.sumOf { -it.amount_minor }, "legs balance the total")
        // Leg sum 360 + 880 = 1240 must equal the persisted mixed total and the projection.
        val totalMin = mixedTotal.sumOf { it.amount_minor }
        assertEquals(0L, totalMin, "fully balanced set")
        assertProjectionRow(db, "k-mx-evidence-1", "account-asset-a", 1240L)
    }

    // ---------- TP-08 (closure 2): ordinary + transfer spine projections ----------

    @Test
    fun tp08OrdinaryIncomeSpineMaterializesSameTransaction() {
        val (db, d, _) = newWorld()
        val kind = ImportRecordKind.ORDINARY_FLOW_SOURCE
        val facts = creditFacts(4500, "out")
        val candidate = intake(db, d, "req-k-oi", kind, facts, null, "k-oi")
        assertIs<ImportCandidateDecisionResult.Accepted>(
            confirm(
                db,
                d,
                "req-k-oi-confirm",
                candidate,
                kind,
                facts,
                ImportConfirmDecisionFields.OrdinaryFlow(
                    categoryId = CategoryId("category-food"),
                    fundingAccountId = AccountId("account-asset-a"),
                ),
                com.unifiedledger.application.OrdinaryFlowFormalFactory(catalog()),
                "k-oi",
            ),
        )
        assertProjectionRow(db, "k-oi-evidence-1", "account-asset-a", 4500L)
    }

    @Test
    fun tp08TransferSpineMaterializesDirectionMatchedLeg() {
        val (db, d, _) = newWorld()
        val kind = ImportRecordKind.TRANSFER_FLOW_SOURCE
        // Z-shaped instants end to end: spine persistence renders formal times in
        // UTC instant form, so the source facts must share the exact temporal shape
        // for the D-103 time gate to resolve (temporalShape equality discipline).
        val facts = creditFacts(1000, "out").copy(occurredAt = "2026-08-14T02:00:00Z")
        val candidate = intake(db, d, "req-k-tf", kind, facts, null, "k-tf")
        assertIs<ImportCandidateDecisionResult.Accepted>(
            confirm(
                db,
                d,
                "req-k-tf-confirm",
                candidate,
                kind,
                facts,
                ImportConfirmDecisionFields.TransferFlow(
                    fromAccountId = AccountId("account-asset-a"),
                    toAccountId = AccountId("account-bank-b"),
                ),
                com.unifiedledger.application.TransferFlowFormalFactory(catalog(), AccountId("account-asset-a")),
                "k-tf",
            ),
        )
        // out-direction source selects the from-leg:
        assertProjectionRow(db, "k-tf-evidence-1", "account-asset-a", 1000L)
    }

    // ---------- QUAL-001: spine materialization then same-evidence confirmLink ----------

    @Test
    fun tpQual001SpineMaterializeThenConfirmLinkAcceptsThroughAuditDivergence() {
        val (db, d, _) = newWorld()
        // Eligibility discipline: confirmLink only ACCEPTS ACCOUNT_TRANSFER postings,
        // so the QUAL-001 chain uses the transfer flow (spine-safe).
        val kind = ImportRecordKind.TRANSFER_FLOW_SOURCE
        // Z-shaped instants end to end: spine persistence renders formal times in
        // UTC instant form, so the source facts must share the exact temporal shape
        // for the D-103 time gate to resolve (temporalShape equality discipline).
        val facts = creditFacts(1000, "out").copy(occurredAt = "2026-08-14T02:00:00Z")
        val candidate = intake(db, d, "req-k-q1", kind, facts, null, "k-q1")
        assertIs<ImportCandidateDecisionResult.Accepted>(
            confirm(
                db,
                d,
                "req-k-q1-confirm",
                candidate,
                kind,
                facts,
                ImportConfirmDecisionFields.TransferFlow(
                    fromAccountId = AccountId("account-asset-a"),
                    toAccountId = AccountId("account-bank-b"),
                ),
                com.unifiedledger.application.TransferFlowFormalFactory(catalog(), AccountId("account-asset-a")),
                "k-q1",
            ),
        )
        // The confirm-link verification carries different provenance by design; the
        // row already exists, so economic equality (QUAL-001) must accept it.
        val postings =
            db.ledgerQueries
                .selectPostingsForSet("ledger-k", "k-q1-posting-set-0")
                .executeAsList()
        val liabilityPosting = postings.first { it.account_id == "account-asset-a" && it.amount_minor < 0 }
        val integrity =
            db.ledgerQueries
                .selectP408PostingIntegrity("ledger-k", liabilityPosting.posting_id)
                .executeAsOne()
        val linkOutcome =
            SqlDelightP408ReconciliationStore(db, d).confirmLink(
                com.unifiedledger.application.P408ConfirmLinkRequest(
                    ledgerId = "ledger-k",
                    requestId = "req-q1-link",
                    evidenceId = "k-q1-evidence-1",
                    candidateId = "k-q1-candidate-1",
                    postingId = liabilityPosting.posting_id,
                    transactionId = "k-q1-tx-0",
                    amountMinor = 1000L,
                    currencyCode = "CNY",
                    currencyPrecision = 2,
                    direction = "out",
                    accountId = "account-asset-a",
                    responsibility = com.unifiedledger.application.P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                    basisVersion = 2,
                    matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
                    windowDays = com.unifiedledger.application.P408Matcher.DEFAULT_WINDOW_DAYS,
                    naturalDayDistance = 0,
                    sourceOccurredAt = integrity.occurred_at,
                    confirmedAt = facts.occurredAt,
                    linkId = "link-q1",
                    reconciliationId = "reconciliation-q1",
                    createdAt = facts.occurredAt,
                    projectionId = "proj-k-q1-evidence-1",
                    projectionRuleId = com.unifiedledger.application.P408EvidenceProjectionPort.RULE_ID,
                    projectionRuleVersion = 1,
                    normalizedAmountMinor = 1000L,
                    rawAmountMinor = 1000L,
                    rawCurrencyPrecision = 2,
                ),
            )
        val accepted = assertIs<com.unifiedledger.application.P408ReconciliationResult.Accepted>(linkOutcome)
        assertEquals("ACCEPTED", accepted.receipt.outcome)
    }

    // ---------- TP-10: CONFIRM_AFTER_PROJECTION_MATERIALIZE rollback + corrected retry ----------

    @Test
    fun tp10ProjectionMaterializeInjectionRollsBackAndCorrectedRetryAccepts() {
        val (db, d, _) = newWorld()
        val kind = ImportRecordKind.CREDIT_EXPENSE_SOURCE
        val facts = creditFacts(2000, "out")
        val candidate = intake(db, d, "req-k-t10", kind, facts, ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗"), "k-t10")
        assertFailsWith<ImportSpineUnreachable> {
            confirm(
                db,
                d,
                "req-k-t10-confirm",
                candidate,
                kind,
                facts,
                ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("account-credit-lia")),
                CreditFlowFormalFactory(catalog(), noOriginalExpense()),
                "k-t10",
                paymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗"),
                failure =
                    ImportSpineFailureInjector { point ->
                        if (point == ImportSpineFailurePoint.CONFIRM_AFTER_PROJECTION_MATERIALIZE) throw ImportSpineUnreachable()
                    },
            )
        }
        // Zero residue for the confirm: no formal rows, no projection, no status advance.
        assertEquals(0L, db.ledgerQueries.countPostings().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
        assertEquals(0L, db.ledgerQueries.countP408ReconciliationRequestRows().executeAsOne())
        // Corrected retry through a non-failing store succeeds and seeds the projection.
        val retried =
            confirm(
                db,
                d,
                "req-k-t10-confirm",
                candidate,
                kind,
                facts,
                ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("account-credit-lia")),
                CreditFlowFormalFactory(catalog(), noOriginalExpense()),
                "k-t10",
                paymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗"),
            )
        assertIs<ImportCandidateDecisionResult.Accepted>(retried)
        assertProjectionRow(db, "k-t10-evidence-1", "account-credit-lia", 2000L)
    }

    // ---------- R3: positive authority triggers ----------

    @Test
    fun r3AuthorityAbsentWhenNothingToMaterialize() {
        val (db, d, _) = newWorld()
        val outcome =
            SqlDelightEvidenceProjectionStore.createShared(db).ensureReadyWithinTransaction(
                P408MaterializationRequest(
                    ledgerId = "ledger-k",
                    requestId = "req-absent",
                    evidenceId = "evidence-absent",
                    targetAccountId = "account-credit-lia",
                    targetCurrencyCode = "CNY",
                    targetCurrencyPrecision = 2,
                    materializedAt = "2026-08-21T09:00:00+08:00",
                ),
            )
        assertIs<SqlDelightEvidenceProjectionStore.EnsureReadyResult.NotReady>(outcome)
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_PROJECTION_ABSENT, outcome.code)
        assertEquals(0L, db.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
    }

    @Test
    fun r3RejectedAuthorityBlocksMatchingWithNotReady() {
        val (db, d, _) = newWorld()
        // Standalone explicit path refuses 55@2 -> precision 1 and persists REJECTED.
        seedScaleTwoSource(db, "req-k-r3", "source-r3", "evidence-r3", 55L)
        val store = SqlDelightEvidenceProjectionStore.createShared(db)
        val refused =
            assertIs<com.unifiedledger.application.P408MaterializeResult.Rejected>(
                store.materialize(
                    P408MaterializationRequest(
                        ledgerId = "ledger-k",
                        requestId = "req-r3",
                        evidenceId = "evidence-r3",
                        targetAccountId = "account-credit-lia",
                        targetCurrencyCode = "CNY",
                        targetCurrencyPrecision = 1,
                        materializedAt = "2026-08-21T09:00:00+08:00",
                    ),
                ),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_AMOUNT_NOT_REPRESENTABLE, refused.code)
        // Confirm-link must positively report NOT_READY (not a secondary code).
        val request =
            com.unifiedledger.application.P408ConfirmLinkRequest(
                ledgerId = "ledger-k",
                requestId = "req-r3-confirm",
                evidenceId = "evidence-r3",
                candidateId = "candidate-r3",
                postingId = "posting-r3",
                transactionId = "tx-r3",
                amountMinor = 55L,
                currencyCode = "CNY",
                currencyPrecision = 2,
                direction = "out",
                accountId = "account-credit-lia",
                responsibility = com.unifiedledger.application.P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                basisVersion = 2,
                matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
                windowDays = com.unifiedledger.application.P408Matcher.DEFAULT_WINDOW_DAYS,
                naturalDayDistance = 0,
                sourceOccurredAt = "2026-08-21T09:00:00+08:00",
                confirmedAt = "2026-08-21T09:30:00+08:00",
                linkId = "link-r3",
                reconciliationId = "reconciliation-r3",
                createdAt = "2026-08-21T09:30:00+08:00",
                projectionId = "proj-evidence-r3",
                projectionRuleId = com.unifiedledger.application.P408EvidenceProjectionPort.RULE_ID,
                projectionRuleVersion = 1,
                normalizedAmountMinor = 55L,
                rawAmountMinor = 55L,
                rawCurrencyPrecision = 2,
            )
        val rejected =
            assertIs<com.unifiedledger.application.P408ReconciliationResult.Rejected>(
                SqlDelightP408ReconciliationStore(db, d).confirmLink(request),
            )
        assertEquals(SqlDelightEvidenceProjectionStore.CODE_PROJECTION_NOT_READY, rejected.code)
        assertEquals(0L, db.ledgerQueries.countEvidenceLinkRows().executeAsOne())
    }

    // ---------- TP-01 combined: 99@0 source -> 9900@2 confirmLink ACCEPTED ----------

    @Test
    fun tp01Combined99Scale0To9900Scale2ConfirmLinkAccepted() {
        val (db, d, _) = newWorld()
        seedScaleZeroSource(db, 99L)
        seedPostingForMatch(db, -9900L)
        val request =
            com.unifiedledger.application.P408ConfirmLinkRequest(
                ledgerId = "ledger-k",
                requestId = "req-99",
                evidenceId = "evidence-99",
                candidateId = "candidate-99",
                postingId = "posting-99",
                transactionId = "tx-99",
                amountMinor = 9900L,
                currencyCode = "CNY",
                currencyPrecision = 2,
                direction = "out",
                accountId = "account-asset-a",
                responsibility = com.unifiedledger.application.P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                basisVersion = 2,
                matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
                windowDays = com.unifiedledger.application.P408Matcher.DEFAULT_WINDOW_DAYS,
                naturalDayDistance = 0,
                sourceOccurredAt = "2026-08-21T09:00:00+08:00",
                confirmedAt = "2026-08-21T09:30:00+08:00",
                linkId = "link-99",
                reconciliationId = "reconciliation-99",
                createdAt = "2026-08-21T09:30:00+08:00",
                projectionId = "proj-evidence-99",
                projectionRuleId = com.unifiedledger.application.P408EvidenceProjectionPort.RULE_ID,
                projectionRuleVersion = 1,
                normalizedAmountMinor = 9900L,
                rawAmountMinor = 99L,
                rawCurrencyPrecision = 0,
            )
        val accepted =
            assertIs<com.unifiedledger.application.P408ReconciliationResult.Accepted>(
                SqlDelightP408ReconciliationStore(db, d).confirmLink(request),
            )
        assertEquals("ACCEPTED", accepted.receipt.outcome)
        assertProjectionRow(db, "evidence-99", "account-asset-a", 9900L)
    }

    private fun noOriginalExpense(): (TransactionId) -> CreditRefundOriginalExpense? = { null }

    private fun refundProvider(
        db: LedgerDatabase,
        d: JdbcSqliteDriver,
        originalTx: String,
    ): (TransactionId) -> CreditRefundOriginalExpense? =
        provider@{ requested ->
            if (requested.value != originalTx) return@provider null
            val rows = db.ledgerQueries.selectPostingsForSet("ledger-k", "k-re-posting-set-0").executeAsList()
            val positive = rows.firstOrNull { it.amount_minor > 0 } ?: return@provider null
            CreditRefundOriginalExpense(
                transactionId = requested,
                ledgerId = ledgerId(),
                kind = TransactionKind.EXPENSE,
                currencyCode = positive.currency_code,
                currentExpensePostingAccountId = com.unifiedledger.domain.AccountId(positive.account_id),
            )
        }

    // ---------- fixtures ----------

    private fun seedScaleZeroSource(
        db: LedgerDatabase,
        amount: Long,
    ) {
        db.ledgerQueries.claimImportRequest("ledger-k", "req-99", "intake")
        db.ledgerQueries.insertImportSourceRecord(
            ledger_id = "ledger-k",
            source_id = "source-99",
            owner_request_id = "req-99",
            input_ref = "batch-99",
            record_ordinal = 0,
            record_kind = "transfer_flow_source",
            content_hash = "hash-99",
            contract_version = 2,
            completeness = "valid_complete",
            amount_minor = amount,
            currency_code = "CNY",
            currency_precision = 0,
            occurred_at = "2026-08-21T09:00:00+08:00",
            direction_token = "out",
            status_token = "settled",
            funding_state = "SETTLED",
            funding_rule_id = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            funding_rule_version = 1L,
            candidate_generated_at = "generated-k",
        )
        db.ledgerQueries.insertImportEvidence(
            ledger_id = "ledger-k",
            evidence_id = "evidence-99",
            source_id = "source-99",
            evidence_kind = "source_observation",
            observed_at = "2026-08-21T09:00:01+08:00",
        )
    }

    private fun seedScaleTwoSource(
        db: LedgerDatabase,
        requestId: String,
        sourceId: String,
        evidenceId: String,
        amount: Long,
    ) {
        db.ledgerQueries.claimImportRequest("ledger-k", requestId, "intake")
        db.ledgerQueries.insertImportSourceRecord(
            ledger_id = "ledger-k",
            source_id = sourceId,
            owner_request_id = requestId,
            input_ref = "batch-$sourceId",
            record_ordinal = 0,
            record_kind = "transfer_flow_source",
            content_hash = "hash-$sourceId",
            contract_version = 2,
            completeness = "valid_complete",
            amount_minor = amount,
            currency_code = "CNY",
            currency_precision = 2,
            occurred_at = "2026-08-21T09:00:00+08:00",
            direction_token = "out",
            status_token = "settled",
            funding_state = "SETTLED",
            funding_rule_id = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
            funding_rule_version = 1L,
            candidate_generated_at = "generated-k",
        )
        db.ledgerQueries.insertImportEvidence(
            ledger_id = "ledger-k",
            evidence_id = evidenceId,
            source_id = sourceId,
            evidence_kind = "source_observation",
            observed_at = "2026-08-21T09:00:01+08:00",
        )
    }

    private fun seedPostingForMatch(
        db: LedgerDatabase,
        amountMinor: Long,
    ) {
        db.transaction {
            driver!!.execute(
                null,
                "INSERT INTO ledger_transaction(transaction_id,ledger_id,kind,canonical_kind) VALUES ('tx-99','ledger-k','ACCOUNT_TRANSFER',NULL)",
                0,
            )
            driver!!.execute(null, "INSERT INTO posting_set VALUES ('posting-set-99','ledger-k')", 0)
            driver!!.execute(
                null,
                "INSERT INTO transaction_version(version_id,transaction_id,ledger_id,version_number,posting_set_id,occurred_at,statistics_at,effective_at,note) VALUES ('version-99','tx-99','ledger-k',1,'posting-set-99','2026-08-21T09:00:00+08:00','2026-08-21T09:00:00+08:00','2026-08-21T09:00:00+08:00',NULL)",
                0,
            )
            driver!!.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('tx-99','ledger-k','version-99')", 0)
            driver!!.execute(
                null,
                "INSERT INTO posting VALUES ('posting-99','posting-set-99','ledger-k',0,'account-asset-a',$amountMinor,'CNY',2)",
                0,
            )
        }
    }

    private fun assertProjectionRow(
        db: LedgerDatabase,
        evidenceId: String,
        targetAccountId: String,
        normalized: Long,
    ) {
        val row = db.ledgerQueries.selectP408EvidenceProjection("ledger-k", evidenceId).executeAsOneOrNull()
        assertEquals(true, row != null, "projection row missing for $evidenceId")
        assertEquals(targetAccountId, row!!.target_account_id)
        assertEquals(normalized, row.normalized_amount_minor)
        assertEquals(2L, row.currency_precision)
        assertEquals("READY", row.state)
        assertEquals("p408_evidence_projection_v1", row.rule_id)
    }
}

private class ImportSpineUnreachable : RuntimeException("CONFIRM_AFTER_PROJECTION_MATERIALIZE injection")

private fun projectionSqliteProperties(): java.util.Properties =
    java.util.Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

private fun Long.sign(): Int =
    when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
