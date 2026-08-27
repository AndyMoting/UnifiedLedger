package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CreditFlowFormalFactory
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ExecuteManualIncomeSave
import com.unifiedledger.application.ExecuteRg04ImportOperation
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
import com.unifiedledger.application.CandidateId
import com.unifiedledger.application.MixedPaymentFlowFormalFactory
import com.unifiedledger.application.P408ConfirmLinkRequest
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408EvidenceResponsibility
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408ReconciliationResult
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ReviewImportDuplicateCandidate
import com.unifiedledger.application.Rg02DecodedManualIncomeInput
import com.unifiedledger.application.Rg02JsonField
import com.unifiedledger.application.Rg02ManualIncomeContext
import com.unifiedledger.application.Rg03ManualTransferSnapshot
import com.unifiedledger.application.Rg03ExecutionResult
import com.unifiedledger.application.Rg03PreparedOperation
import com.unifiedledger.application.Rg05ManualSnapshot
import com.unifiedledger.application.Rg05PreparedOperation
import com.unifiedledger.application.Rg06CreateStagedPaymentInput
import com.unifiedledger.application.Rg06Operation
import com.unifiedledger.application.Rg07ManualExpenseInput
import com.unifiedledger.application.Rg07Operation
import com.unifiedledger.application.Rg07ExecutionResult
import com.unifiedledger.application.Rg08ExecutionResult
import com.unifiedledger.application.Rg08Operation
import com.unifiedledger.application.Rg09ExecutionResult
import com.unifiedledger.application.Rg09Operation
import com.unifiedledger.application.Rg10ExecutionResult
import com.unifiedledger.application.Rg10Operation
import com.unifiedledger.application.Rg11CreateIds
import com.unifiedledger.application.Rg11CreateInput
import com.unifiedledger.application.Rg11ExecutionResult
import com.unifiedledger.application.Rg12ExecutionResult
import com.unifiedledger.application.adaptRg02ManualIncomeInput
import com.unifiedledger.application.adaptRg08Fixture
import com.unifiedledger.application.adaptRg09Fixture
import com.unifiedledger.application.adaptRg10Fixture
import com.unifiedledger.application.adaptRg12Fixture
import com.unifiedledger.application.parseRg08FixtureInputs
import com.unifiedledger.application.parseRg09FixtureInputs
import com.unifiedledger.application.parseRg10FixtureInputs
import com.unifiedledger.application.parseRg12FixtureInputs
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.MergedPaymentExpenseIds
import com.unifiedledger.domain.MergedPaymentItem
import com.unifiedledger.domain.PeriodicAllocationAnchor
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.StagedPaymentCreationIds
import com.unifiedledger.domain.StagedPaymentHistoryId
import com.unifiedledger.domain.StagedPaymentLifecycleId
import com.unifiedledger.domain.StagedPaymentRelationId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import com.unifiedledger.domain.CreditRefundOriginalExpense
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * P4-09 D3 (D-110 implementation spec section 5): the 12 RG silos, the complete spine
 * form (section 5.2 row set) and the P4-08 tables in ONE database. Layer-(i) access per
 * silo (ruling 5): every store or adapter is constructed directly on the shared
 * (LedgerDatabase, driver) pair; each silo commits one frozen main-path accepted
 * operation and replays it. The spine runs on its own ledger id with the section 3.2
 * fixture family (six kinds + the four closed variants + one confirm per confirmable
 * kind + the RL-01 duplicate review + the RL-07 confirmLink + the minimal combo a/b
 * re-enactments). Assertions: zero cross-talk (every silo table's rows identical before
 * and after all spine writes), the reverse direction (no rg-prefixed id in any spine
 * owner), the shared formal chain growing by exactly the spine confirmations, a plain
 * reopen keeping every projection byte-equal, and every silo replaying its
 * representative operation after the reopen.
 */
class P409SiloSpineCoexistenceTest {
    private val coexistLedgerId = LedgerId("ledger-p409-coexist")
    private val cny = CurrencyUnit("CNY", 2)
    private val fingerprint = ImportContentFingerprint()
    private val comparisonFingerprint = ImportDuplicateComparisonFingerprint()
    private val generatedAt = "2026-08-22T08:00:00Z"
    private val inputRef = "batch-p409-coexist"
    private val confirmedAt = "2026-08-22T10:00:00+08:00"
    private val reviewedAt = "2026-08-22T11:00:00+08:00"

    // Section 3.2 fixture family (same values as the D1 closure oracle).
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
    private val comboBCreditFacts = ImportSourceFacts(2000, "CNY", 2, "2026-08-14T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)
    private val comboBMixedFacts = ImportSourceFacts(1500, "CNY", 2, "2026-08-15T12:00:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)

    /**
     * The formal-writing early silos (rg01/02/03/05/07) run on their own ledger id.
     * RG-08's D-091 full-load design loads EVERY transaction on its ledger and demands
     * a formal_transaction_metadata row for each, and only the RG-08..RG-12 stores
     * write those rows (MultiRgStoreCoexistenceTest precedent keeps ledger-a to the
     * metadata-writing stores; rg04's representative op is an intake with no formal
     * writes, rg06 stages without formalizing). Different ledger ids sharing the
     * formal chain are legal (rg04 precedent).
     */
    private val earlySiloLedger = LedgerId("ledger-p409-early-silos")

    /** Mirror evidence for the spine RL-03 confirmLink: the posting's own Z-shaped instant. */
    private val rl03MirrorFacts = ImportSourceFacts(3000, "CNY", 2, "2026-08-03T04:00:00Z", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1)

    private val directProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "花呗")
    private val repaymentProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, "余额宝", null)
    private val refundProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "花呗")
    private val mixedProfile = ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, "余额宝", "花呗")

    @Test
    fun twelveSilosAndFullSpineCoexistWithZeroCrossTalkAndStableReopen() {
        val path = Files.createTempFile("p409-d3-coexistence-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            lateinit var siloSnapshotBeforeSpine: Map<String, List<List<String?>>>
            lateinit var fullSnapshotBeforeClose: Map<String, List<List<String?>>>
            var siloTransactionCount = 0L
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)

                // ---------- phase 1: the 12 silos, one accepted op + replay each ----------

                // RG-09 first: its confirm gate fingerprints the opening-only ledger.
                val fixture09 = loadFixture09()
                val store09 = SqlDelightRg09Store(database, driver, fixture09.catalog, fixture09.openingTransactions)
                fixture09.operations.take(4).forEach { item ->
                    assertIs<Rg09ExecutionResult.Accepted>(store09.commit(item.operation), "rg09 ${item.id}")
                }

                // RG-01 (i): the manual expense commit port, create + replay.
                val rg01Expense = rg01Commit()
                val expensePort = SqlDelightConfirmedManualExpenseCommitPort(database, driver)
                assertIs<com.unifiedledger.application.ConfirmedManualExpenseResult.Created>(
                    expensePort.commitOnce(rg01Expense.first, rg01Expense.second) { DomainResult.Success(rg01Expense.third) },
                )

                // RG-02 (i): the raw-json adapter feeding the income save, create + replay.
                val rg02First = incomeSaveOn(driver).execute(rg02SaveInputOn())
                assertIs<com.unifiedledger.application.ConfirmedManualIncomeResult.Created>(
                    assertIs<com.unifiedledger.application.ManualIncomeSaveResult.Executed>(rg02First).result,
                )

                // RG-03 (i): the transfer store, manual create + replay.
                val store03 = SqlDelightRg03TransferStore(database, driver, rg03Catalog(), rg03IdentitySource())
                assertIs<Rg03ExecutionResult.Accepted>(store03.commit(Rg03PreparedOperation.CreateManual(rg03ManualSnapshot())))

                // RG-04 (i): the decoded frozen case, intake op + replay.
                val rg04Case = decodedCase()
                val rg04Executor = ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, rg04Case.catalog))
                assertIs<com.unifiedledger.application.Rg04ImportExecutionResult.Accepted>(rg04Executor.execute(rg04Case.importOperations[0]))

                // RG-05 (i): the merged-payment store, manual path + replay.
                val store05 = SqlDelightRg05Store(database, driver, rg05Catalog(), object : Rg05IdentitySource {
                    override fun manual(requestId: RequestId) = Rg05ManualCommitIds("confirmation-p409-rg05", "reconciliation-p409-rg05")
                })
                val rg05Operation = rg05ManualOperation()
                assertIs<com.unifiedledger.application.Rg05ExecutionResult.Accepted>(store05.commit(rg05Operation))

                // RG-06 (i): the staged-payment store, create + replay.
                val store06 = SqlDelightRg06Store(database, driver, rg06Catalog(), "+08:00") { _, _ -> null }
                assertIs<com.unifiedledger.application.Rg06ExecutionResult.Accepted>(store06.commit(rg06CreateOperation()))

                // RG-07 (i): the original-form store, manual expense + replay.
                val store07 = SqlDelightRg07Store(database, driver, rg07Catalog(), emptySet(), rg07IdentitySource())
                assertIs<Rg07ExecutionResult.Accepted>(store07.commit(rg07ExpenseOperation()))

                // RG-08..RG-12 (i): the MultiRgStoreCoexistenceTest blueprint.
                val fixture08 = loadFixture08()
                val store08 = SqlDelightRg08Store(database, driver, fixture08.catalog, fixture08.lendingCatalog, fixture08.openingTransactions)
                assertIs<Rg08ExecutionResult.Accepted>(store08.commit((fixture08.operations.first { it.id == "lend" }.operation as Rg08Operation.ValidateLendingEvent)), "rg08 lend")

                val fixture10 = loadFixture10()
                val store10 = SqlDelightRg10Store(database, driver, fixture10.catalog, fixture10.openingTransactions)
                fixture10.operations.forEach { item ->
                    assertIs<Rg10ExecutionResult.Accepted>(store10.commit(item.operation), "rg10 ${item.id}")
                }

                val store11 = SqlDelightRg11Store(database, driver, rg11Catalog())
                assertIs<Rg11ExecutionResult.Accepted>(store11.commit(rg11CreateOperation()), "rg11 create")

                val fixture12 = loadFixture12()
                val store12 = SqlDelightRg12Store(database, driver, fixture12.catalogs.getValue("root-correction"), fixture12.baselines.getValue("root-correction"))
                assertIs<Rg12ExecutionResult.Accepted>(
                    store12.commit(fixture12.operations.single { it.id == "root-correction-correct" }.operation),
                    "rg12 correct",
                )

                siloTransactionCount = database.ledgerQueries.countTransactions().executeAsOne()
                assertTrue(siloTransactionCount > 0L, "silos committed formal transactions")

                // ---------- phase 2: the full spine form on its own ledger ----------

                siloSnapshotBeforeSpine = siloSnapshot(driver)
                runFullSpineForm(database, driver)
                assertEquals(siloSnapshotBeforeSpine, siloSnapshot(driver), "zero cross-talk: silo rows identical after all spine writes")

                // Reverse direction: no rg-prefixed id in any spine owner.
                spineIdFilters().forEach { (table, column) ->
                    assertEquals(
                        0L,
                        queryCount(driver, "SELECT count(*) FROM $table WHERE $column LIKE 'rg%'"),
                        "$table.$column must carry no rg-prefixed id",
                    )
                }

                // The shared formal chain grew by exactly the spine confirmations.
                assertEquals(
                    siloTransactionCount + spineConfirmedTransactions,
                    database.ledgerQueries.countTransactions().executeAsOne(),
                    "shared formal chain = silo transactions + spine confirmations",
                )

                fullSnapshotBeforeClose = allTableSnapshot(driver)
            }

            // ---------- phase 3: plain reopen, byte-equal projections ----------
            JdbcSqliteDriver(url, sqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(fullSnapshotBeforeClose, allTableSnapshot(driver), "reopen keeps every projection")

                // Every silo replays its representative operation after the reopen.
                val fixture09 = loadFixture09()
                val store09 = SqlDelightRg09Store(database, driver, fixture09.catalog, fixture09.openingTransactions)
                assertIs<Rg09ExecutionResult.NoChange>(
                    store09.commit(fixture09.operations.first { it.operation is Rg09Operation.PreviewTargetBalance }.operation),
                )

                val rg01 = rg01Commit()
                var rg01Callback = 0
                assertIs<com.unifiedledger.application.ConfirmedManualExpenseResult.NoChange>(
                    SqlDelightConfirmedManualExpenseCommitPort(database, driver).commitOnce(rg01.first, rg01.second) {
                        rg01Callback += 1
                        DomainResult.Success(rg01.third)
                    },
                )
                assertEquals(0, rg01Callback)

                val rg02Replay = incomeSaveOn(driver).execute(rg02SaveInputOn())
                assertIs<com.unifiedledger.application.ManualIncomeSaveResult.Executed>(rg02Replay)
                assertIs<com.unifiedledger.application.ConfirmedManualIncomeResult.NoChange>(rg02Replay.result)

                val store03 = SqlDelightRg03TransferStore(database, driver, rg03Catalog(), rg03IdentitySource())
                assertIs<Rg03ExecutionResult.NoChange>(store03.commit(Rg03PreparedOperation.CreateManual(rg03ManualSnapshot())))

                val rg04Case = decodedCase()
                assertIs<com.unifiedledger.application.Rg04ImportExecutionResult.NoChange>(
                    ExecuteRg04ImportOperation(SqlDelightRg04ImportStore(database, driver, rg04Case.catalog)).execute(rg04Case.importOperations[0]),
                )

                val store05 = SqlDelightRg05Store(database, driver, rg05Catalog(), object : Rg05IdentitySource {
                    override fun manual(requestId: RequestId) = Rg05ManualCommitIds("confirmation-p409-rg05", "reconciliation-p409-rg05")
                })
                assertIs<com.unifiedledger.application.Rg05ExecutionResult.NoChange>(store05.commit(rg05ManualOperation()))

                val store06 = SqlDelightRg06Store(database, driver, rg06Catalog(), "+08:00") { _, _ -> null }
                assertIs<com.unifiedledger.application.Rg06ExecutionResult.NoChange>(store06.commit(rg06CreateOperation()))

                val store07 = SqlDelightRg07Store(database, driver, rg07Catalog(), emptySet(), rg07IdentitySource())
                assertIs<Rg07ExecutionResult.NoChange>(store07.commit(rg07ExpenseOperation()))

                val fixture08 = loadFixture08()
                val store08 = SqlDelightRg08Store(database, driver, fixture08.catalog, fixture08.lendingCatalog, fixture08.openingTransactions)
                assertIs<Rg08ExecutionResult.NoChange>(store08.commit(fixture08.operations.first { it.id == "lend" }.operation))

                val fixture10 = loadFixture10()
                val store10 = SqlDelightRg10Store(database, driver, fixture10.catalog, fixture10.openingTransactions)
                assertIs<Rg10ExecutionResult.NoChange>(store10.commit(fixture10.operations.first().operation))

                assertIs<Rg11ExecutionResult.NoChange>(SqlDelightRg11Store(database, driver, rg11Catalog()).commit(rg11CreateOperation()))

                val fixture12 = loadFixture12()
                val store12 = SqlDelightRg12Store(database, driver, fixture12.catalogs.getValue("root-correction"), fixture12.baselines.getValue("root-correction"))
                assertIs<Rg12ExecutionResult.NoChange>(store12.commit(fixture12.operations.single { it.id == "root-correction-correct" }.operation))

                // Spine replay: the intake and confirm identities stay zero-write replays.
                spineReplay(database, driver)
                assertEquals(fullSnapshotBeforeClose, allTableSnapshot(driver), "replays keep every projection")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /** Ten confirmed formal transactions across the spine form. */
    private val spineConfirmedTransactions = 10L

    // ---------- the section 5.2 spine form ----------

    private fun coexistCatalog(): LedgerCatalog = when (
        val result = LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("coexist-asset-a"), coexistLedgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("coexist-asset-b"), coexistLedgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("coexist-credit"), coexistLedgerId, AccountKind.LIABILITY, cny, ownedByUser = true, realAccount = true),
                Account(AccountId("coexist-expense-food"), coexistLedgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                Account(AccountId("coexist-income-salary"), coexistLedgerId, AccountKind.INCOME, cny, ownedByUser = false, realAccount = false),
            ),
            categories = listOf(
                Category(CategoryId("coexist-category-primary-food"), coexistLedgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("coexist-category-food"), coexistLedgerId, parentId = CategoryId("coexist-category-primary-food"), postingAccountId = AccountId("coexist-expense-food"), active = true, kind = CategoryKind.EXPENSE),
                Category(CategoryId("coexist-category-primary-salary"), coexistLedgerId, parentId = null, postingAccountId = null, active = true, kind = CategoryKind.INCOME),
                Category(CategoryId("coexist-category-salary"), coexistLedgerId, parentId = CategoryId("coexist-category-primary-salary"), postingAccountId = AccountId("coexist-income-salary"), active = true, kind = CategoryKind.INCOME),
            ),
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("coexist catalog failure: ${result.violation}")
    }

    private fun originalExpenseReader(driver: JdbcSqliteDriver): (TransactionId) -> CreditRefundOriginalExpense? {
        return fun(transactionId: TransactionId): CreditRefundOriginalExpense? {
            val rows = selectTypedRows(
                driver,
                "SELECT t.kind, t.ledger_id, p.account_id, p.currency_code FROM posting AS p " +
                    "JOIN transaction_version AS v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id " +
                    "JOIN ledger_transaction AS t ON t.transaction_id = v.transaction_id AND t.ledger_id = p.ledger_id " +
                    "JOIN ledger_transaction_current_version AS c ON c.transaction_id = t.transaction_id " +
                    "AND c.ledger_id = t.ledger_id AND c.current_version_id = v.version_id " +
                    "WHERE t.ledger_id = '${coexistLedgerId.value}' AND t.transaction_id = '${transactionId.value}' AND p.amount_minor > 0",
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

    private class SpineExecutor(
        val database: LedgerDatabase,
        val store: SqlDelightImportSpineStore,
        private val catalog: LedgerCatalog,
        val driver: JdbcSqliteDriver,
        val intakeIds: ImportIntakeIdSource,
        val commitIds: ImportIdSource,
    ) {
        fun intake(request: ImportIntakeRequest) = ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(request)

        fun confirm(request: ImportCandidateConfirmRequest, factory: ImportCandidateFormalFactory) =
            ConfirmImportCandidate(store, commitIds, factory, catalog).execute(request)
    }

    private fun spineExecutor(database: LedgerDatabase, driver: JdbcSqliteDriver, intakeIds: ImportIntakeIdSource, commitIds: ImportIdSource) =
        SpineExecutor(database, SqlDelightImportSpineStore(database, driver), coexistCatalog(), driver, intakeIds, commitIds)

    private fun ordinaryFactory() = object : ImportCandidateFormalFactory {
        private val catalog = coexistCatalog()
        private val delegate = com.unifiedledger.application.OrdinaryFlowFormalFactory(catalog)

        override fun create(input: ImportCandidateFormalizationInput, ids: ImportCommitIds): DomainResult<ImportFormalCommit> =
            delegate.create(input, ids)
    }

    private fun transferFactory() = com.unifiedledger.application.TransferFlowFormalFactory(coexistCatalog(), AccountId("coexist-asset-a"))

    private fun creditFactory(driver: JdbcSqliteDriver) = CreditFlowFormalFactory(coexistCatalog(), originalExpenseReader(driver))

    private fun mixedFactory() = MixedPaymentFlowFormalFactory(coexistCatalog())

    private fun spineIntakeRequest(requestId: String, ordinal: Int, kind: ImportRecordKind, facts: ImportSourceFacts, completeness: ImportCompleteness, profile: ImportPaymentProfile?) =
        ImportIntakeRequest(
            identity = ImportRequestIdentity(coexistLedgerId, ImportRequestId(requestId)),
            inputRef = inputRef,
            recordOrdinal = ordinal,
            recordKind = kind,
            facts = facts,
            completeness = completeness,
            candidateGeneratedAt = generatedAt,
            paymentProfile = profile,
        )

    private fun spineIntakeIds(prefix: String, duplicates: List<Pair<String, String>> = emptyList()) = ImportIntakeIds(
        ImportSourceId("source-$prefix"), ImportEvidenceId("evidence-$prefix"),
        ImportCandidateId("candidate-$prefix"), ImportStatusHistoryId("status-$prefix-1"),
        duplicates.map { ImportDuplicateIntakeIds(ImportDuplicateCandidateId(it.first), ImportStatusHistoryId(it.second)) },
    )

    private fun spineCommitIds(prefix: String, threePostings: Boolean = false) = ImportCommitIds(
        ImportConfirmationId("confirmation-$prefix"),
        ImportStatusHistoryId("status-$prefix-2"),
        ImportFormalIds(
            TransactionId("tx-$prefix"), TransactionVersionId("version-$prefix-v1"), PostingSetId("posting-set-$prefix"),
            (0..if (threePostings) 2 else 1).map { PostingId("posting-$prefix-$it") },
        ),
    )

    private fun ordinaryConfirm(requestId: String, candidate: String, hash: String, category: String, funding: String) =
        ImportCandidateConfirmRequest(
            ImportRequestIdentity(coexistLedgerId, ImportRequestId(requestId)), ImportCandidateId(candidate), hash, confirmedAt,
            ImportConfirmDecisionFields.OrdinaryFlow(CategoryId(category), AccountId(funding)),
        )

    private fun runFullSpineForm(database: LedgerDatabase, driver: JdbcSqliteDriver) {
        val hash = { kind: ImportRecordKind, facts: ImportSourceFacts, profile: ImportPaymentProfile? -> fingerprint.digest(kind, facts, profile) }

        // RL-01: ordinary expense confirmed + equal re-intake as duplicate + review.
        run1(database, driver, spineIntakeIds("sp-rl01"), spineCommitIds("sp-rl01")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl01", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)))
            assertAccepted(confirm(ordinaryConfirm("req-sp-rl01-confirm", "candidate-sp-rl01", hash(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, null), "coexist-category-food", "coexist-asset-a"), ordinaryFactory()))
        }
        run1(database, driver, spineIntakeIds("sp-rl01d", listOf("duplicate-sp-rl01" to "history-duplicate-sp-rl01")), spineCommitIds("unused")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl01d", 1, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)))
        }
        assertIs<ImportDuplicateReviewResult.Accepted>(
            ReviewImportDuplicateCandidate(SqlDelightImportSpineStore(database, driver)).execute(spineReviewRequest("duplicate-sp-rl01", ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, "source-sp-rl01d", "source-sp-rl01")),
        )

        // RL-02: ordinary income confirmed.
        run1(database, driver, spineIntakeIds("sp-rl02"), spineCommitIds("sp-rl02")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl02", 2, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, ImportCompleteness.VALID_COMPLETE, null)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-rl02-confirm")), ImportCandidateId("candidate-sp-rl02"),
                    hash(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl02Facts, null), confirmedAt,
                    ImportConfirmDecisionFields.OrdinaryFlow(CategoryId("coexist-category-salary"), AccountId("coexist-asset-a")),
                ),
                ordinaryFactory(),
            ))
        }

        // RL-03: complete transfer confirmed + confirmLink (combo anchor a); missing leg pending.
        run1(database, driver, spineIntakeIds("sp-rl03c"), spineCommitIds("sp-rl03c")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl03c", 3, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, ImportCompleteness.VALID_COMPLETE, null)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-rl03c-confirm")), ImportCandidateId("candidate-sp-rl03c"),
                    hash(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl03CompleteFacts, null), confirmedAt,
                    ImportConfirmDecisionFields.TransferFlow(AccountId("coexist-asset-a"), AccountId("coexist-asset-b")),
                ),
                transferFactory(),
            ))
        }
        run1(database, driver, spineIntakeIds("sp-rl03m"), spineCommitIds("unused")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl03m", 4, ImportRecordKind.TRANSFER_FLOW_SOURCE_MISSING_LEG, rl03MissingFacts, ImportCompleteness.VALID_COMPLETE, null)))
        }
        // The linking evidence mirrors the RL-03 source at the posting's Z-shaped
        // instant (equal temporal shape is the P4-08 linking discipline; spine formal
        // times persist as UTC Z-form instants).
        run1(database, driver, spineIntakeIds("sp-rl03x"), spineCommitIds("unused")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl03x", 19, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl03MirrorFacts, ImportCompleteness.VALID_COMPLETE, null)))
        }
        assertIs<P408ReconciliationResult.Accepted>(
            SqlDelightP408ReconciliationStore(database, driver).confirmLink(
                P408ConfirmLinkRequest(
                    ledgerId = coexistLedgerId.value,
                    requestId = "req-sp-rl03-link",
                    evidenceId = "evidence-sp-rl03x",
                    candidateId = "candidate-sp-rl03x",
                    postingId = "posting-sp-rl03c-0",
                    transactionId = "tx-sp-rl03c",
                    amountMinor = 3000,
                    currencyCode = "CNY",
                    currencyPrecision = 2,
                    direction = "out",
                    accountId = "coexist-asset-a",
                    responsibility = P408EvidenceResponsibility.REAL_ACCOUNT_POSTING,
                    basisVersion = 2,
                            projectionId = "proj-evidence-sp-rl03x",
                            projectionRuleId = P408EvidenceProjectionPort.RULE_ID,
                            projectionRuleVersion = 1,
                            normalizedAmountMinor = 3000,
                            rawAmountMinor = 3000,
                            rawCurrencyPrecision = 2,
                    matchBasis = setOf("amount", "currency", "direction", "occurred_at_window", "account"),
                    windowDays = P408Matcher.DEFAULT_WINDOW_DAYS,
                    naturalDayDistance = 0,
                    sourceOccurredAt = rl03MirrorFacts.occurredAt,
                    confirmedAt = confirmedAt,
                    linkId = "link-sp-rl03",
                    reconciliationId = "reconciliation-sp-rl03c-0",
                    createdAt = confirmedAt,
                ),
            ),
        )

        // RL-04: second-source routing — complete variant confirmed, incomplete stays put.
        run1(database, driver, spineIntakeIds("sp-rl04c"), spineCommitIds("sp-rl04c")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl04c", 5, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04CompleteFacts, ImportCompleteness.VALID_COMPLETE, null)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-rl04c-confirm")), ImportCandidateId("candidate-sp-rl04c"),
                    hash(ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04CompleteFacts, null), confirmedAt,
                    ImportConfirmDecisionFields.TransferFlow(AccountId("coexist-asset-a"), AccountId("coexist-asset-b")),
                ),
                transferFactory(),
            ))
        }
        run1(database, driver, spineIntakeIds("sp-rl04i"), spineCommitIds("unused")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl04i", 6, ImportRecordKind.TRANSFER_FLOW_SOURCE, rl04IncompleteFacts, ImportCompleteness.VALID_INCOMPLETE, null)))
        }

        // RL-05: the three credit anchors confirmed in sequence.
        run1(database, driver, spineIntakeIds("sp-rl05e"), spineCommitIds("sp-rl05e")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl05e", 7, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-rl05e-confirm")), ImportCandidateId("candidate-sp-rl05e"),
                    hash(ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05ExpenseFacts, directProfile), confirmedAt,
                    ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("coexist-category-food"), AccountId("coexist-credit")),
                ),
                creditFactory(driver),
            ))
        }
        run1(database, driver, spineIntakeIds("sp-rl05r"), spineCommitIds("sp-rl05r")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl05r", 8, ImportRecordKind.CREDIT_REPAYMENT_SOURCE, rl05RepayFacts, ImportCompleteness.VALID_COMPLETE, repaymentProfile)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-rl05r-confirm")), ImportCandidateId("candidate-sp-rl05r"),
                    hash(ImportRecordKind.CREDIT_REPAYMENT_SOURCE, rl05RepayFacts, repaymentProfile), confirmedAt,
                    ImportConfirmDecisionFields.CreditRepaymentFlow(AccountId("coexist-asset-a"), AccountId("coexist-credit")),
                ),
                creditFactory(driver),
            ))
        }
        run1(database, driver, spineIntakeIds("sp-rl05f"), spineCommitIds("sp-rl05f")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl05f", 9, ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05RefundFacts, ImportCompleteness.VALID_COMPLETE, refundProfile)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-rl05f-confirm")), ImportCandidateId("candidate-sp-rl05f"),
                    hash(ImportRecordKind.CREDIT_EXPENSE_SOURCE, rl05RefundFacts, refundProfile), confirmedAt,
                    ImportConfirmDecisionFields.CreditExpenseRefundFlow(CategoryId("coexist-category-food"), AccountId("coexist-credit"), TransactionId("tx-sp-rl05e")),
                ),
                creditFactory(driver),
            ))
        }

        // RL-06: mixed payment confirmed.
        run1(database, driver, spineIntakeIds("sp-rl06"), spineCommitIds("sp-rl06", threePostings = true)).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-rl06", 10, ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, ImportCompleteness.VALID_COMPLETE, mixedProfile)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-rl06-confirm")), ImportCandidateId("candidate-sp-rl06"),
                    hash(ImportRecordKind.MIXED_PAYMENT_SOURCE, rl06Facts, mixedProfile), confirmedAt,
                    ImportConfirmDecisionFields.MixedPaymentFlow(CategoryId("coexist-category-food"), AccountId("coexist-asset-a"), AccountId("coexist-credit"), 360L, 880L),
                ),
                mixedFactory(),
            ))
        }

        // RL-08: the four closed variants stay zero-funds.
        listOf(
            Triple("sp-rl08o", 11, ImportRecordKind.ORDINARY_FLOW_SOURCE to rl08ClosedFacts to null),
            Triple("sp-rl08t", 12, ImportRecordKind.TRANSFER_FLOW_SOURCE to rl08TransferClosedFacts to null),
            Triple("sp-rl08c", 13, ImportRecordKind.CREDIT_EXPENSE_SOURCE to rl08CreditClosedFacts to directProfile),
            Triple("sp-rl08m", 14, ImportRecordKind.MIXED_PAYMENT_SOURCE to rl08MixedClosedFacts to mixedProfile),
        ).forEach { (prefix, ordinal, payload) ->
            val (kindFacts, profile) = payload
            val (kind, facts) = kindFacts
            run1(database, driver, spineIntakeIds(prefix, listOf("duplicate-$prefix" to "history-duplicate-$prefix")), spineCommitIds("unused")).apply {
                assertAccepted(intake(spineIntakeRequest("req-$prefix", ordinal, kind, facts, ImportCompleteness.VALID_COMPLETE, profile)))
            }
        }

        // Combo anchor b (minimal): credit pair confirmed-duplicate blocks the second;
        // mixed pair dismissed-lookalike still formalizes.
        run1(database, driver, spineIntakeIds("sp-cb-crda"), spineCommitIds("sp-cb-crda")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-cb-crda", 15, ImportRecordKind.CREDIT_EXPENSE_SOURCE, comboBCreditFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-cb-crda-confirm")), ImportCandidateId("candidate-sp-cb-crda"),
                    hash(ImportRecordKind.CREDIT_EXPENSE_SOURCE, comboBCreditFacts, directProfile), confirmedAt,
                    ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("coexist-category-food"), AccountId("coexist-credit")),
                ),
                creditFactory(driver),
            ))
        }
        run1(database, driver, spineIntakeIds("sp-cb-crdb", listOf("duplicate-sp-cb-crd" to "history-duplicate-sp-cb-crd")), spineCommitIds("unused")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-cb-crdb", 16, ImportRecordKind.CREDIT_EXPENSE_SOURCE, comboBCreditFacts, ImportCompleteness.VALID_COMPLETE, directProfile)))
        }
        assertIs<ImportDuplicateReviewResult.Accepted>(
            ReviewImportDuplicateCandidate(SqlDelightImportSpineStore(database, driver)).execute(
                spineReviewRequest("duplicate-sp-cb-crd", ImportRecordKind.CREDIT_EXPENSE_SOURCE, comboBCreditFacts, "source-sp-cb-crdb", "source-sp-cb-crda", decision = ImportDuplicateStatus.CONFIRMED_DUPLICATE, requestId = "review-sp-cb-crd"),
            ),
        )
        assertIs<ImportCandidateDecisionResult.Rejected>(
            run1(database, driver, spineIntakeIds("unused"), spineCommitIds("sp-cb-crdb")).confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-cb-crdb-confirm")), ImportCandidateId("candidate-sp-cb-crdb"),
                    hash(ImportRecordKind.CREDIT_EXPENSE_SOURCE, comboBCreditFacts, directProfile), confirmedAt,
                    ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("coexist-category-food"), AccountId("coexist-credit")),
                ),
                creditFactory(driver),
            ),
        )
        run1(database, driver, spineIntakeIds("sp-cb-mixa"), spineCommitIds("sp-cb-mixa", threePostings = true)).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-cb-mixa", 17, ImportRecordKind.MIXED_PAYMENT_SOURCE, comboBMixedFacts, ImportCompleteness.VALID_COMPLETE, mixedProfile)))
        }
        run1(database, driver, spineIntakeIds("sp-cb-mixb", listOf("duplicate-sp-cb-mix" to "history-duplicate-sp-cb-mix")), spineCommitIds("unused")).apply {
            assertAccepted(intake(spineIntakeRequest("req-sp-cb-mixb", 18, ImportRecordKind.MIXED_PAYMENT_SOURCE, comboBMixedFacts, ImportCompleteness.VALID_COMPLETE, mixedProfile)))
        }
        assertIs<ImportDuplicateReviewResult.Accepted>(
            ReviewImportDuplicateCandidate(SqlDelightImportSpineStore(database, driver)).execute(
                spineReviewRequest("duplicate-sp-cb-mix", ImportRecordKind.MIXED_PAYMENT_SOURCE, comboBMixedFacts, "source-sp-cb-mixb", "source-sp-cb-mixa", decision = ImportDuplicateStatus.DISMISSED_LOOKALIKE, requestId = "review-sp-cb-mix"),
            ),
        )
        run1(database, driver, spineIntakeIds("unused"), spineCommitIds("sp-cb-mixb", threePostings = true)).apply {
            assertAccepted(confirm(
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(coexistLedgerId, ImportRequestId("req-sp-cb-mixb-confirm")), ImportCandidateId("candidate-sp-cb-mixb"),
                    hash(ImportRecordKind.MIXED_PAYMENT_SOURCE, comboBMixedFacts, mixedProfile), confirmedAt,
                    ImportConfirmDecisionFields.MixedPaymentFlow(CategoryId("coexist-category-food"), AccountId("coexist-asset-a"), AccountId("coexist-credit"), 500L, 1000L),
                ),
                mixedFactory(),
            ))
        }
    }

    private fun spineReplay(database: LedgerDatabase, driver: JdbcSqliteDriver) {
        val run = run1(database, driver, spineIntakeIds("unused"), spineCommitIds("unused"))
        assertIs<ImportIntakeResult.NoChange>(run.intake(spineIntakeRequest("req-sp-rl01", 0, ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, ImportCompleteness.VALID_COMPLETE, null)))
        assertIs<ImportCandidateDecisionResult.NoChange>(
            run.confirm(ordinaryConfirm("req-sp-rl01-confirm", "candidate-sp-rl01", fingerprint.digest(ImportRecordKind.ORDINARY_FLOW_SOURCE, rl01Facts, null), "coexist-category-food", "coexist-asset-a"), ordinaryFactory()),
        )
    }

    private fun spineReviewRequest(
        candidateId: String,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        subjectSourceId: String,
        existingSourceId: String,
        decision: ImportDuplicateStatus = ImportDuplicateStatus.CONFIRMED_DUPLICATE,
        requestId: String = "review-$candidateId",
    ): ImportDuplicateReviewRequest {
        val digest = comparisonFingerprint.digest(
            ImportDuplicateComparisonSnapshot(
                ImportSourceId(subjectSourceId), ImportSourceId(existingSourceId), kind, kind.contractVersion,
                facts.amountMinor, facts.currencyCode, facts.currencyPrecision, facts.occurredAt, facts.directionToken, facts.statusToken,
            ),
        )
        return ImportDuplicateReviewRequest(
            ImportRequestIdentity(coexistLedgerId, ImportRequestId(requestId)),
            ImportDuplicateCandidateId(candidateId), digest, decision, "exact",
            reviewedAt, "reviewer-p409", reviewedAt,
            ImportDuplicateReviewId("$requestId-review"), ImportStatusHistoryId("$requestId-history"),
        )
    }

    private fun run1(database: LedgerDatabase, driver: JdbcSqliteDriver, intakeIds: ImportIntakeIds, commitIds: ImportCommitIds): SpineRun =
        SpineRun(
            spineExecutor(
                database, driver,
                object : ImportIntakeIdSource { override fun next() = intakeIds },
                object : ImportIdSource { override fun next() = commitIds },
            ),
        )

    private class SpineRun(private val executor: SpineExecutor) {
        fun intake(request: ImportIntakeRequest) = executor.intake(request)
        fun confirm(request: ImportCandidateConfirmRequest, factory: ImportCandidateFormalFactory) = executor.confirm(request, factory)
        fun assertAccepted(result: ImportIntakeResult) = assertIs<ImportIntakeResult.Accepted>(result)
        fun assertAccepted(result: ImportCandidateDecisionResult) = assertIs<ImportCandidateDecisionResult.Accepted>(result)
    }

    // ---------- silo fixtures (i)-layer wiring ----------

    private fun rg01Commit(): Triple<com.unifiedledger.application.ManualExpenseRequestIdentity, com.unifiedledger.application.ManualExpenseRequestSnapshot, com.unifiedledger.application.ConfirmedManualExpenseCommit> {
        val ledger = earlySiloLedger
        val identity = com.unifiedledger.application.ManualExpenseRequestIdentity(ledger, RequestId("request-p409-rg01"))
        val snapshot = com.unifiedledger.application.ManualExpenseRequestSnapshot(
            ledgerId = ledger,
            amount = Money.ofMinor(3_580, cny),
            categoryId = CategoryId("expense-category-breakfast"),
            paymentAccountId = AccountId("asset-bank-a"),
            occurredAt = Instant.parse("2026-01-15T00:30:00Z"),
            note = "",
        )
        val transactionId = TransactionId("tx-p409-rg01")
        val postingSetId = PostingSetId("posting-set-p409-rg01")
        val versionId = TransactionVersionId("version-p409-rg01-v1")
        val postingSet = assertIs<DomainResult.Success<PostingSet>>(
            PostingSet.create(
                postingSetId,
                listOf(
                    Posting(PostingId("posting-p409-rg01-expense"), AccountId("expense-account-breakfast"), Money.ofMinor(3_580, cny)),
                    Posting(PostingId("posting-p409-rg01-asset"), AccountId("asset-bank-a"), Money.ofMinor(-3_580, cny)),
                ),
            ),
        ).value
        val formal = assertIs<DomainResult.Success<FormalTransaction>>(
            FormalTransaction.create(
                Transaction(transactionId, ledger, TransactionKind.EXPENSE, versionId),
                listOf(TransactionVersion(versionId, transactionId, 1, postingSetId, TransactionTimes.collapsed(snapshot.occurredAt), snapshot.note)),
                listOf(postingSet),
            ),
        ).value
        return Triple(identity, snapshot, com.unifiedledger.application.ConfirmedManualExpenseCommit(com.unifiedledger.application.ConfirmationId("confirmation-p409-rg01"), formal))
    }

    private fun rg02IncomeFactory() = com.unifiedledger.application.ConfirmedIncomeTransactionFactory { request, ids ->
        val incomeAccount = AccountId("income-account-salary")
        val postingSet = assertIs<DomainResult.Success<PostingSet>>(
            PostingSet.create(
                ids.incomeIds.postingSetId,
                listOf(
                    Posting(ids.incomeIds.receivingPostingId, request.receivingAccountId, request.amount),
                    Posting(ids.incomeIds.incomePostingId, incomeAccount, Money.ofMinor(-request.amount.minorUnits, request.amount.currency)),
                ),
            ),
        ).value
        val formal = assertIs<DomainResult.Success<FormalTransaction>>(
            FormalTransaction.create(
                Transaction(ids.incomeIds.transactionId, request.ledgerId, TransactionKind.INCOME, ids.incomeIds.versionId),
                listOf(
                    TransactionVersion(
                        ids.incomeIds.versionId, ids.incomeIds.transactionId, 1,
                        ids.incomeIds.postingSetId, TransactionTimes.collapsed(request.occurredAt), request.note,
                    ),
                ),
                listOf(postingSet),
            ),
        ).value
        DomainResult.Success(com.unifiedledger.application.ConfirmedManualIncomeCommit(ids.confirmationId, formal))
    }

    private fun rg02SaveInputOn() = assertIs<com.unifiedledger.application.Rg02ManualIncomeAdaptResult.Success>(
        adaptRg02ManualIncomeInput(
            Rg02ManualIncomeContext(ledgerId = earlySiloLedger, currency = cny, caseTimeZone = "Asia/Shanghai"),
            Rg02DecodedManualIncomeInput(
                requestId = Rg02JsonField.Value("request-p409-rg02"),
                occurredAt = Rg02JsonField.Value("2026-01-16T09:00:00+08:00"),
                amount = Rg02JsonField.Value("3000.00"),
                currency = Rg02JsonField.Value("CNY"),
                categoryId = Rg02JsonField.Value("income-category-salary"),
                receivingAccountId = Rg02JsonField.Value("asset-bank-a"),
                note = Rg02JsonField.Value(""),
                explicitConfirmation = Rg02JsonField.Value(true),
            ),
        ),
    ).value.saveInput

    private fun incomeSaveOn(driver: JdbcSqliteDriver) = ExecuteManualIncomeSave(
        com.unifiedledger.application.ExecuteConfirmedManualIncome(
            SqlDelightConfirmedManualIncomeCommitPort(LedgerDatabase(driver), driver),
            {
                com.unifiedledger.application.ConfirmedManualIncomeCommitIds(
                    com.unifiedledger.application.ConfirmationId("confirmation-p409-rg02"),
                    AssetReceivedOrdinaryIncomeIds(
                        TransactionId("tx-p409-rg02"), TransactionVersionId("version-p409-rg02-v1"), PostingSetId("posting-set-p409-rg02"),
                        PostingId("posting-p409-rg02-asset"), PostingId("posting-p409-rg02-income"),
                    ),
                )
            },
            rg02IncomeFactory(),
        ),
    )

    private fun rg03ManualSnapshot() = Rg03ManualTransferSnapshot(
        earlySiloLedger, RequestId("request-p409-rg03"), Instant.parse("2026-01-20T02:00:00Z"),
        AccountId("asset-bank-a"), AccountId("asset-wallet-b"),
        Money.ofMinor(6_000, cny), Money.ofMinor(5_900, cny), Money.ofMinor(100, cny),
        CategoryId("expense-category-transfer-fee"),
    )

    private fun rg06CreateOperation() = Rg06Operation.CreateStagedPayment(
        ledgerId = LedgerId("ledger-a"),
        input = Rg06CreateStagedPaymentInput(
            requestId = RequestId("request-p409-rg06"),
            totalAmount = Money.ofMinor(30_000, cny),
            categoryId = CategoryId("expense-service"),
            createdAt = Instant.parse("2026-04-20T09:00:00+08:00"),
        ),
        ids = StagedPaymentCreationIds(
            StagedPaymentRelationId("relation-p409-rg06"),
            StagedPaymentLifecycleId("lifecycle-p409-rg06"),
            StagedPaymentHistoryId("history-p409-rg06"),
        ),
    )

    private fun rg07ExpenseOperation() = Rg07Operation.ManualExpense(
        earlySiloLedger,
        Rg07ManualExpenseInput(
            RequestId("request-p409-rg07"), Money.ofMinor(1_000, cny), CategoryId("ledger-a-daily"),
            AccountId("ledger-a-asset"), Instant.parse("2026-01-10T04:00:00Z"), "", true,
        ),
    )

    private fun rg03Catalog(): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(
        LedgerCatalog.create(
            listOf(
                Account(AccountId("asset-bank-a"), earlySiloLedger, AccountKind.ASSET, cny, true, true),
                Account(AccountId("asset-wallet-b"), earlySiloLedger, AccountKind.ASSET, cny, true, true),
                Account(AccountId("liability-credit-c"), earlySiloLedger, AccountKind.LIABILITY, cny, true, true),
                Account(AccountId("expense-account-transfer-fee"), earlySiloLedger, AccountKind.EXPENSE, cny, false, false),
            ),
            listOf(
                Category(CategoryId("expense-category-financial"), earlySiloLedger, null, null, true),
                Category(CategoryId("expense-category-transfer-fee"), earlySiloLedger, CategoryId("expense-category-financial"), AccountId("expense-account-transfer-fee"), true),
            ),
        ),
    ).value

    private fun rg03IdentitySource(): Rg03IdentitySource = object : Rg03IdentitySource {
        override fun source(requestId: RequestId) = Rg03SourceCommitIds(CandidateId("candidate-p409-rg03"), "status-p409-rg03")
        override fun transfer(requestId: RequestId) = Rg03TransferCommitIds(
            com.unifiedledger.application.ConfirmationId("confirmation-p409-rg03"),
            AccountTransferIds(
                TransactionId("tx-p409-rg03"), TransactionVersionId("version-p409-rg03-v1"), PostingSetId("posting-set-p409-rg03"),
                PostingId("posting-p409-rg03-source"), PostingId("posting-p409-rg03-destination"), PostingId("posting-p409-rg03-fee"),
            ),
            "reconciliation-p409-rg03-source",
            "reconciliation-p409-rg03-destination",
            null,
            null,
        )
        override fun mirror(requestId: RequestId) = Rg03MirrorCommitIds("match-p409-rg03")
    }

    private fun rg05Catalog(): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(
        LedgerCatalog.create(
            listOf(
                Account(AccountId("asset"), earlySiloLedger, AccountKind.ASSET, cny, true, true),
                Account(AccountId("expense-a-account"), earlySiloLedger, AccountKind.EXPENSE, cny, false, false),
                Account(AccountId("expense-b-account"), earlySiloLedger, AccountKind.EXPENSE, cny, false, false),
            ),
            listOf(
                Category(CategoryId("root"), earlySiloLedger, null, null, true),
                Category(CategoryId("daily"), earlySiloLedger, CategoryId("root"), AccountId("expense-a-account"), true),
                Category(CategoryId("service"), earlySiloLedger, CategoryId("root"), AccountId("expense-b-account"), true),
            ),
        ),
    ).value

    private fun rg05ManualOperation(): Rg05PreparedOperation.Manual {
        val snapshot = Rg05ManualSnapshot(
            earlySiloLedger, RequestId("request-p409-rg05"), Instant.parse("2026-04-10T10:30:00Z"), "2026-04-10T10:30:00Z",
            Money.ofMinor(10_000, cny), AccountId("asset"),
            listOf(
                MergedPaymentItem("a", Money.ofMinor(4_000, cny), CategoryId("daily"), "daily", Instant.parse("2026-04-10T09:00:00Z")),
                MergedPaymentItem("b", Money.ofMinor(6_000, cny), CategoryId("service"), "service", Instant.parse("2026-04-10T09:05:00Z")),
            ), true,
        )
        return Rg05PreparedOperation.Manual(
            snapshot,
            MergedPaymentExpenseIds(
                TransactionId("tx-p409-rg05"), TransactionVersionId("version-p409-rg05"), PostingSetId("posting-set-p409-rg05"),
                listOf(PostingId("posting-p409-rg05-a"), PostingId("posting-p409-rg05-b")), PostingId("posting-p409-rg05-asset"),
            ),
            "relation-p409-rg05", "", "", mapOf("a" to "consumption-p409-rg05-a", "b" to "consumption-p409-rg05-b"),
            mapOf("a" to "allocation-p409-rg05-a", "b" to "allocation-p409-rg05-b"),
        )
    }

    private fun rg06Catalog(): LedgerCatalog = assertIs<DomainResult.Success<LedgerCatalog>>(
        LedgerCatalog.create(
            accounts = listOf(
                Account(AccountId("asset-bank"), LedgerId("ledger-a"), AccountKind.ASSET, cny, true, true),
                Account(AccountId("expense-service-account"), LedgerId("ledger-a"), AccountKind.EXPENSE, cny, false, false),
                Account(AccountId("income-account"), LedgerId("ledger-a"), AccountKind.INCOME, cny, false, false),
                Account(AccountId("asset-nonfinancial"), LedgerId("ledger-a"), AccountKind.ASSET, cny, true, false),
                Account(AccountId("asset-external"), LedgerId("ledger-a"), AccountKind.ASSET, cny, false, true),
                Account(AccountId("liability"), LedgerId("ledger-a"), AccountKind.LIABILITY, cny, true, true),
            ),
            categories = listOf(
                Category(CategoryId("expense-root"), LedgerId("ledger-a"), null, null, true),
                Category(CategoryId("expense-service"), LedgerId("ledger-a"), CategoryId("expense-root"), AccountId("expense-service-account"), true),
                Category(CategoryId("income-root"), LedgerId("ledger-a"), null, null, true, CategoryKind.INCOME),
                Category(CategoryId("income-child"), LedgerId("ledger-a"), CategoryId("income-root"), AccountId("income-account"), true, CategoryKind.INCOME),
            ),
        ),
    ).value

    private fun rg07Catalog(): LedgerCatalog {
        val ledger = earlySiloLedger
        return assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("ledger-a-asset"), ledger, AccountKind.ASSET, cny, true, true),
                    Account(AccountId("ledger-a-expense"), ledger, AccountKind.EXPENSE, cny, false, false),
                ),
                listOf(
                    Category(CategoryId("ledger-a-parent"), ledger, null, null, true),
                    Category(CategoryId("ledger-a-daily"), ledger, CategoryId("ledger-a-parent"), AccountId("ledger-a-expense"), true),
                ),
            ),
        ).value
    }

    private fun rg07IdentitySource(): Rg07IdentitySource = object : Rg07IdentitySource {
        private fun owner(operation: Rg07Operation) = "${operation.ledgerId.value}-${operation.identity.value}"
        override fun operationId(operation: Rg07Operation) = "operation-${owner(operation)}"
        override fun manual(operation: Rg07Operation.ManualExpense) = Rg07ManualCommitFacts(
            "confirmation-${owner(operation)}", "reconciliation-${owner(operation)}", Instant.parse("2026-01-10T04:01:00Z"),
        )
        override fun relation(operation: Rg07Operation.Status) = "relation-${owner(operation)}"
        override fun domainEntity(operation: Rg07Operation, relationId: String) = "entity-${owner(operation)}"
        override fun formal(operation: Rg07Operation) = Rg07FormalIds(
            "transaction-${owner(operation)}", "version-${owner(operation)}", "posting-set-${owner(operation)}",
            "posting-first-${owner(operation)}", "posting-second-${owner(operation)}",
        )
        override fun receipt(operation: Rg07Operation, relationId: String, assetPostingId: String) =
            Rg07ReceiptCommitIds(
                "confirmation-${owner(operation)}", "reconciliation-${owner(operation)}", "entity-${owner(operation)}",
            )
    }

    private fun rg11Catalog(): LedgerCatalog {
        val ledgerA = LedgerId("ledger-a")
        val accounts = listOf(
            Account(AccountId("asset-bank-a"), ledgerA, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
            Account(AccountId("prepaid-account-a"), ledgerA, AccountKind.ASSET, cny, ownedByUser = true, realAccount = false),
            Account(AccountId("expense-account-a"), ledgerA, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
        )
        val categories = listOf(Category(CategoryId("category-a"), ledgerA, parentId = CategoryId("root"), postingAccountId = AccountId("expense-account-a"), active = true))
        return when (val created = LedgerCatalog.create(accounts, categories)) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> error("invalid rg11 catalog")
        }
    }

    private fun rg11CreateOperation(): com.unifiedledger.application.Rg11Operation.CreatePeriodicAllocation =
        com.unifiedledger.application.Rg11Operation.CreatePeriodicAllocation(
            ledgerId = LedgerId("ledger-a"),
            input = Rg11CreateInput(
                requestId = RequestId("request-p409-rg11"),
                paymentAccountId = AccountId("asset-bank-a"),
                prepaidAccountId = AccountId("prepaid-account-a"),
                categoryId = CategoryId("category-a"),
                amount = Money.ofMinor(10_000L, cny),
                currency = cny,
                startAt = Instant.parse("2026-01-30T16:00:00Z"),
                anchor = PeriodicAllocationAnchor.MonthEnd,
                explicitConfirmation = true,
                occurredAt = Instant.parse("2026-01-30T16:00:00Z"),
                installmentCount = 3,
            ),
            ids = Rg11CreateIds(
                transactionId = TransactionId("transaction-p409-rg11"),
                versionId = TransactionVersionId("version-p409-rg11"),
                postingSetId = PostingSetId("posting-set-p409-rg11"),
                paymentPostingId = PostingId("payment-p409-rg11"),
                prepaidPostingId = PostingId("prepaid-p409-rg11"),
                scheduleId = "schedule-p409-rg11",
                revisionId = "revision-p409-rg11",
                installmentIds = listOf("installment-p409-rg11-1", "installment-p409-rg11-2", "installment-p409-rg11-3"),
            ),
        )

    private fun loadFixture08() = adaptRg08Fixture(
        Files.readString(repositoryFile("golden/rules/rg-08.json")),
        parseRg08FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg08-runtime-input.json"))),
    )

    private fun loadFixture09() = adaptRg09Fixture(
        Files.readString(repositoryFile("golden/rules/rg-09.json")),
        parseRg09FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg09-runtime-input.json"))),
    )

    private fun loadFixture10() = adaptRg10Fixture(
        Files.readString(repositoryFile("golden/rules/rg-10.json")),
        parseRg10FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg10-runtime-input.json"))),
    )

    private fun loadFixture12() = adaptRg12Fixture(
        Files.readString(repositoryFile("golden/rules/rg-12.json")),
        parseRg12FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg12-runtime-input.json"))),
    )

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    // ---------- snapshots and filters ----------

    private fun sqliteProperties(): Properties = Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("busy_timeout", "5000")
    }

    private fun tableNames(driver: JdbcSqliteDriver, filter: String): List<String> = driver.executeQuery(
        null,
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND ($filter) ORDER BY name",
        { cursor ->
            val names = buildList { while (cursor.next().value) add(requireNotNull(cursor.getString(0))) }
            app.cash.sqldelight.db.QueryResult.Value(names)
        },
        0,
    ).value

    private fun siloTableFilter() =
        ("name LIKE 'rg\\_%' ESCAPE '\\' OR name IN (" +
            "'manual_expense_request','confirmed_expense_request','manual_income_request','confirmed_income_receipt')")

    /** Every silo-owned table's full rows, stringified (capture-compare projection). */
    private fun siloSnapshot(driver: JdbcSqliteDriver): Map<String, List<List<String?>>> =
        tableNames(driver, siloTableFilter()).associateWith { table -> stringRows(driver, table) }

    /** Every user table's full rows (reopen-stability projection). */
    private fun allTableSnapshot(driver: JdbcSqliteDriver): Map<String, List<List<String?>>> =
        tableNames(driver, "1=1").associateWith { table -> stringRows(driver, table) }

    private fun stringRows(driver: JdbcSqliteDriver, table: String): List<List<String?>> {
        val columns = driver.executeQuery(
            null,
            "SELECT count(*) FROM pragma_table_info('$table')",
            { cursor -> cursor.next(); app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)).toInt()) },
            0,
        ).value
        return driver.executeQuery(
            null,
            "SELECT * FROM $table",
            { cursor ->
                val rows = mutableListOf<List<String?>>()
                while (cursor.next().value) {
                    rows += (0 until columns).map { index -> cursor.getString(index) }
                }
                app.cash.sqldelight.db.QueryResult.Value(rows.sortedBy { row -> row.joinToString("\u0000") { it ?: "" } })
            },
            0,
        ).value
    }

    /** (table, id column) pairs for the reverse cross-talk filter over the spine owners. */
    private fun spineIdFilters(): List<Pair<String, String>> = listOf(
        "import_request" to "request_id",
        "import_source_record" to "source_id",
        "import_evidence" to "evidence_id",
        "import_candidate" to "candidate_id",
        "import_candidate_payment_profile" to "candidate_id",
        "import_candidate_requires_confirmation" to "candidate_id",
        "import_candidate_status_history" to "candidate_id",
        "import_candidate_decision_snapshot" to "candidate_id",
        "import_confirmation" to "candidate_id",
        "import_receipt" to "candidate_id",
        "import_duplicate_candidate" to "candidate_id",
        "import_duplicate_status_history" to "candidate_id",
        "import_duplicate_review_request" to "request_id",
        "import_duplicate_review_snapshot" to "candidate_id",
        "import_duplicate_review_receipt" to "candidate_id",
        "reconciliation_request" to "request_id",
        "reconciliation_request_snapshot" to "candidate_id",
        "evidence_link" to "link_id",
        "evidence_link_history" to "link_id",
        "posting_reconciliation" to "reconciliation_id",
        "posting_reconciliation_history" to "reconciliation_id",
        "reconciliation_receipt" to "request_id",
        "ledger_transaction" to "transaction_id",
        "posting_set" to "posting_set_id",
        "transaction_version" to "version_id",
        "ledger_transaction_current_version" to "transaction_id",
        "posting" to "posting_id",
        "mixed_payment_group" to "group_id",
        "mixed_payment_group_leg" to "group_id",
    )

    private fun queryCount(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(
        null,
        sql,
        { cursor ->
            check(cursor.next().value)
            app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        0,
    ).value

    private fun selectTypedRows(driver: JdbcSqliteDriver, sql: String, longColumns: List<Boolean>): List<List<Any?>> = driver.executeQuery(
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
}
