package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.CatalogEntityIdSource
import com.unifiedledger.application.CatalogEntityIds
import com.unifiedledger.application.CatalogManagementRequestIdSource
import com.unifiedledger.application.CatalogRequestId
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedManualTransferCommitIds
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.DEFAULT_EXPENSE_GROUP_ID
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_NAME
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFundingState
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
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualRefundCandidatesResult
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.P408EvidenceFacts
import com.unifiedledger.application.P408EvidenceProjectionPort
import com.unifiedledger.application.P408MatchDisposition
import com.unifiedledger.application.P408Matcher
import com.unifiedledger.application.P408NormalizedProjectionFacts
import com.unifiedledger.application.P408RawEvidenceFacts
import com.unifiedledger.application.P408ReconciliationStatus
import com.unifiedledger.application.P801AccountPostingFactsResult
import com.unifiedledger.application.P801AccountReconciliationPageResult
import com.unifiedledger.application.P801AccountReconciliationSummaryResult
import com.unifiedledger.application.P801CandidateEvidenceResult
import com.unifiedledger.application.QueryCatalogSnapshot
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryTransactionDetail
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * P8-07 Q2 shared anonymous starting fixture: the small v38 ledger from which each of the three
 * cross-slice device flows starts its own independent run (design section 2; no Q2 PASS is claimed).
 *
 * WHY A TEST WITH BOTH AN ALWAYS-RUN AND AN OPT-IN PATH. The output is a real file-backed SQLite
 * database built only through the REAL product entry points, and `:ledger-data:jvmTest` is the
 * Gradle surface that owns this source set. Every ordinary run builds the file under a JUnit
 * temporary directory and reads it back, so drift reddens without any extra CI wiring. Setting
 * `-Dul.p807.shared.seed=<path>` additionally copies that SAME verified file to the named path for
 * a future AVD workflow; the property path must not already exist, and this writer neither deletes
 * nor overwrites a pre-existing file. The copy is byte-compared here, so "the same emitted file"
 * is proven by copy rather than asserted as an independently byte-identical rebuild.
 *
 * WHAT THE FILE CONTAINS (the minimum all three flows need, and nothing already applied):
 * - the real default catalog seed (默认支付账户, 隐藏费用过账账户, 餐饮/早餐) plus two owned ASSET
 *   accounts created through the real catalog management command: 对账到账账户 (the transfer's
 *   destination and the reconciliation queue's subject) and 退款到账账户 (the refund's arrival
 *   account and the refundable expense's payment account);
 * - one real posted ACCOUNT_TRANSFER of exactly 12.34 CNY, written through the real claim-first
 *   manual-transfer commit port and the real product factory; its destination leg is +12.34 CNY
 *   and neither leg has a reconciliation row, projection or evidence link;
 * - one unlinked import evidence row whose raw facts (12.34 CNY, precision 2, direction in, the
 *   transfer's own occurred_at text) match that destination leg. It is written through the real
 *   import intake port as a `transfer_flow_source` candidate, so the review face can enter
 *   reconciliation by identity, bind it explicitly, and later reject a second posting of the same
 *   candidate. No projection is written: binding is the flow's explicit first step, never a
 *   browsing side effect (P8-01a section 2.4);
 * - one confirmed manual EXPENSE of exactly 21.00 CNY from 退款到账账户 into the default 早餐
 *   leaf, through the real claim-first manual-expense commit port and real factory. Its distinct
 *   transaction id and note make it traceable, and the default catalog shape (the hidden expense
 *   account carries exactly one active expense leaf) makes it the refund entry's selectable
 *   original with the full 21.00 CNY cap remaining (P8-03 section 2.3 condition 5).
 *
 * FIXED DATES. Unlike the clock-relative P801/P806 samples, this fixture pins mid-month instants so
 * the later fixed and cross-month flows can name them: the transfer and its evidence at
 * 2026-10-14T04:00:00Z (2026-10-14 12:00 Asia/Shanghai), the refundable expense at
 * 2026-10-15T02:00:00Z (2026-10-15 10:00 Asia/Shanghai). Both are far from either month boundary,
 * and their persisted text shape is the product's own `Instant.toString()` (trailing `Z`), which is
 * exactly the frozen matcher's comparable `offset_datetime` shape. P8-02 can select a newly
 * imported row carrying the 早餐 category by keyword `早餐`, or by the custom time window covering
 * the row's own date; the seed does not depend on the rolling default 90-day window.
 *
 * THE OPEN/MIGRATION GATE. `LedgerDatabase.Schema.create` does not stamp `PRAGMA user_version`; the
 * app's androidx open gates on it, so this writer stamps `Schema.version` explicitly (the
 * [P801FontscaleSeedWriterTest] precedent).
 *
 * The post-write read-back reopens the closed FILE and drives the REAL P8-01a reconciliation reads,
 * the refund candidate read, the ledger-current-state balance read and the product matcher, so the
 * starting facts the future flows rely on are proven here rather than assumed.
 */
class P807SharedAcceptanceSeedWriterTest {
    private val ledgerId = LedgerId(LEDGER_ID)

    @Test
    fun buildsTheSharedSeedAndEmitsItOnlyWhenANewPathIsNamed() {
        val tempDir = Files.createTempDirectory("p807-shared-acceptance-seed").toFile()
        try {
            val seedFile = File(tempDir, SEED_FILE_NAME)
            seed(seedFile)
            assertSeedReadsBack(seedFile)

            val emittedPath = System.getProperty(SEED_PATH_PROPERTY)
            if (emittedPath != null) {
                val target = File(emittedPath).absoluteFile
                check(!target.exists()) { "refusing to overwrite a pre-existing seed at ${target.path}" }
                target.parentFile?.mkdirs()
                Files.copy(seedFile.toPath(), target.toPath())
                assertContentEquals(seedFile.readBytes(), target.readBytes())
                assertSeedReadsBack(target)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Builds the file through the real product entry points (see the class note). */
    private fun seed(file: File) {
        check(!file.exists()) { "the seed destination must not already exist: ${file.path}" }
        JdbcSqliteDriver(url(file), foreignKeyProperties()).use { driver ->
            LedgerDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = ${LedgerDatabase.Schema.version}", 0)
            val database = LedgerDatabase(driver)
            val catalogStore = SqlDelightCatalogStore(database, driver)
            assertIs<CatalogBootstrapResult.Seeded>(catalogStore.bootstrap(ledgerId, defaultCatalogSeed()))

            // One id per accepted command: the entity-id source and the catalog request id are
            // consumed once per command (P801 precedent).
            val accountIds = listOf(SEED_RECONCILE_ACCOUNT_ID, SEED_REFUND_ACCOUNT_ID)
            val requestIds =
                listOf(
                    "p807-seed-create-reconcile-account",
                    "p807-seed-create-refund-account",
                )
            var nextAccountId = 0
            var nextRequestId = 0
            val commands =
                ExecuteCatalogCommand(
                    commitPort = catalogStore,
                    requestIdSource = CatalogManagementRequestIdSource { CatalogRequestId(requestIds[nextRequestId++]) },
                    entityIdSource =
                        CatalogEntityIdSource {
                            CatalogEntityIds(
                                manageableAccountId = AccountId(accountIds[nextAccountId++]),
                                postingAccountId = AccountId("p807-seed-unused-posting-account"),
                                parentCategoryId = CategoryId("p807-seed-unused-parent-category"),
                                childCategoryId = CategoryId("p807-seed-unused-child-category"),
                            )
                        },
                    categoryReferenceProbe = catalogStore,
                )
            assertEquals(
                AccountId(SEED_RECONCILE_ACCOUNT_ID),
                assertIs<CatalogCommandResult.Accepted>(
                    commands.createAccount(
                        ledgerId = ledgerId,
                        name = SEED_RECONCILE_ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        expectedCatalogVersion = 1L,
                    ),
                ).receipt.createdManageableAccountId,
            )
            assertEquals(
                AccountId(SEED_REFUND_ACCOUNT_ID),
                assertIs<CatalogCommandResult.Accepted>(
                    commands.createAccount(
                        ledgerId = ledgerId,
                        name = SEED_REFUND_ACCOUNT_NAME,
                        kind = AccountKind.ASSET,
                        expectedCatalogVersion = 2L,
                    ),
                ).receipt.createdManageableAccountId,
            )

            val catalog = assertNotNull(catalogStore.loadCurrent(ledgerId))

            // Real posted transfer: exactly the Q2 flow's reconciliation subject and its funding leg.
            val transferSnapshot =
                ManualTransferRequestSnapshot(
                    ledgerId = ledgerId,
                    sourceAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                    destinationAccountId = AccountId(SEED_RECONCILE_ACCOUNT_ID),
                    destinationCredit = Money.ofMinor(SEED_TRANSFER_MINOR, CATALOG_MANAGED_CURRENCY),
                    fee = Money.ofMinor(0L, CATALOG_MANAGED_CURRENCY),
                    feeCategoryId = null,
                    occurredAt = SEED_TRANSFER_INSTANT,
                    note = SEED_TRANSFER_NOTE,
                )
            val transferIds =
                AccountTransferIds(
                    transactionId = TransactionId(SEED_TRANSFER_TRANSACTION_ID),
                    versionId = TransactionVersionId("$SEED_TRANSFER_TRANSACTION_ID-version"),
                    postingSetId = PostingSetId("$SEED_TRANSFER_TRANSACTION_ID-posting-set"),
                    sourcePostingId = PostingId(SEED_TRANSFER_SOURCE_POSTING_ID),
                    destinationPostingId = PostingId(SEED_TRANSFER_DESTINATION_POSTING_ID),
                    // Unused by the fee=0 two-leg factory; the shape still requires one id.
                    feePostingId = PostingId("$SEED_TRANSFER_TRANSACTION_ID-fee"),
                )
            val transferResult =
                SqlDelightConfirmedManualTransferCommitPort(database, driver).commitOnce(
                    identity = ManualTransferRequestIdentity(ledgerId, RequestId("p807-seed-transfer-request")),
                    requestSnapshot = transferSnapshot,
                    createdAt = SEED_CREATED_AT,
                ) {
                    ManualTransferTransactionFactory(catalog).create(
                        transferSnapshot,
                        ConfirmedManualTransferCommitIds(
                            confirmationId = ConfirmationId("p807-seed-transfer-confirmation"),
                            transferIds = transferIds,
                        ),
                    )
                }
            assertIs<ConfirmedManualTransferResult.Created>(transferResult)

            // The real import intake writes source + evidence + pending candidate in one transaction.
            // No projection, no link and no decision are written: the flow's own binding step owns
            // that derived write (P8-01a section 2.4).
            val intake =
                ExecuteImportIntake(
                    commitPort = SqlDelightImportSpineStore(database, driver),
                    idSource =
                        object : ImportIntakeIdSource {
                            override fun next(requiredDuplicateIds: Int): ImportIntakeIds {
                                require(requiredDuplicateIds == 0) { "the shared seed has no prior import sources to duplicate" }
                                return ImportIntakeIds(
                                    sourceId = ImportSourceId(SEED_IMPORT_SOURCE_ID),
                                    evidenceId = ImportEvidenceId(SEED_IMPORT_EVIDENCE_ID),
                                    candidateId = ImportCandidateId(SEED_IMPORT_CANDIDATE_ID),
                                    statusHistoryId = ImportStatusHistoryId(SEED_IMPORT_STATUS_ID),
                                )
                            }
                        },
                    fingerprint = ImportContentFingerprint(),
                )
            assertIs<ImportIntakeResult.Accepted>(
                intake.execute(
                    ImportIntakeRequest(
                        identity = ImportRequestIdentity(ledgerId, ImportRequestId(SEED_IMPORT_REQUEST_ID)),
                        inputRef = SEED_IMPORT_INPUT_REF,
                        recordOrdinal = 0,
                        recordKind = ImportRecordKind.TRANSFER_FLOW_SOURCE,
                        facts =
                            ImportSourceFacts(
                                amountMinor = SEED_TRANSFER_MINOR,
                                currencyCode = CATALOG_MANAGED_CURRENCY.code,
                                currencyPrecision = CATALOG_MANAGED_CURRENCY.precision,
                                // The exact persisted text of the real transfer's occurred_at:
                                // same `offset_datetime` shape, zero natural-day distance.
                                occurredAt = SEED_TRANSFER_INSTANT.toString(),
                                directionToken = "in",
                                statusToken = "settled",
                                fundingState = ImportFundingState.SETTLED,
                                fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                                fundingRuleVersion = 1,
                            ),
                        completeness = ImportCompleteness.VALID_COMPLETE,
                        candidateGeneratedAt = SEED_IMPORT_GENERATED_AT.toString(),
                    ),
                ),
            )

            // Refundable original: real claim-first manual expense into the default leaf, so the
            // refund candidate condition 5 (one active secondary EXPENSE category on the posting
            // account) remains true. Its own id and note keep it distinct from the transfer.
            val expenseAmount = Money.ofMinor(SEED_REFUNDABLE_EXPENSE_MINOR, CATALOG_MANAGED_CURRENCY)
            val expenseSnapshot =
                ManualExpenseRequestSnapshot(
                    ledgerId = ledgerId,
                    amount = expenseAmount,
                    categoryId = CategoryId(DEFAULT_EXPENSE_LEAF_ID),
                    paymentAccountId = AccountId(SEED_REFUND_ACCOUNT_ID),
                    occurredAt = SEED_REFUNDABLE_EXPENSE_INSTANT,
                    note = SEED_REFUNDABLE_EXPENSE_NOTE,
                )
            val expenseIds =
                ConfirmedManualExpenseCommitIds(
                    confirmationId = ConfirmationId("p807-seed-refundable-expense-confirmation"),
                    expenseIds =
                        AssetPaidOrdinaryExpenseIds(
                            transactionId = TransactionId(SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID),
                            versionId = TransactionVersionId("$SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID-version"),
                            postingSetId = PostingSetId("$SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID-posting-set"),
                            expensePostingId = PostingId("$SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID-expense"),
                            paymentPostingId = PostingId("$SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID-payment"),
                        ),
                )
            assertIs<ConfirmedManualExpenseResult.Created>(
                SqlDelightConfirmedManualExpenseCommitPort(database, driver).commitOnce(
                    identity = ManualExpenseRequestIdentity(ledgerId, RequestId("p807-seed-refundable-expense-request")),
                    requestSnapshot = expenseSnapshot,
                    createdAt = SEED_REFUNDABLE_EXPENSE_CREATED_AT,
                ) {
                    when (
                        val created =
                            createAssetPaidOrdinaryExpense(
                                catalog = catalog,
                                command =
                                    AssetPaidOrdinaryExpenseCommand(
                                        ledgerId = ledgerId,
                                        amount = expenseAmount,
                                        categoryId = expenseSnapshot.categoryId,
                                        paymentAccountId = expenseSnapshot.paymentAccountId,
                                        times = TransactionTimes.collapsed(expenseSnapshot.occurredAt),
                                        note = expenseSnapshot.note,
                                    ),
                                ids = expenseIds.expenseIds,
                            )
                    ) {
                        is DomainResult.Success ->
                            DomainResult.Success(
                                ConfirmedManualExpenseCommit(
                                    confirmationId = expenseIds.confirmationId,
                                    transaction = created.value,
                                ),
                            )
                        is DomainResult.Failure -> created
                    }
                },
            )
        }
    }

    /** Reopens the closed FILE and proves the exact starting facts the future flows consume. */
    private fun assertSeedReadsBack(file: File) {
        JdbcSqliteDriver(url(file), foreignKeyProperties()).use { driver ->
            assertEquals(LedgerDatabase.Schema.version, userVersion(driver))
            val database = LedgerDatabase(driver)

            // Real starting counts: two formal transactions (four legs), the expense's own claim and
            // receipt, exactly one import source/evidence/pending candidate with its single creation
            // history row, and ZERO projection, link or reconciliation rows (no flow has run yet).
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRequests().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countReceipts().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportRequests().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportSourceRecords().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportEvidence().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportCandidates().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne())
            // That creation row is the production query's current status for the seeded candidate.
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, SEED_IMPORT_CANDIDATE_ID)
                    .executeAsOne()
                    .status,
            )
            assertEquals(0L, database.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countEvidenceLinkRows().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countP408PostingReconciliationRows().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countP408ReconciliationRequestRows().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countP408ReconciliationReceiptRows().executeAsOne())
            // The default seed's two accounts plus the two created asset accounts.
            assertEquals(4L, database.ledgerQueries.countCatalogAccounts(ledgerId.value).executeAsOne())

            val catalogStore = SqlDelightCatalogStore(database, driver)
            val catalog = assertNotNull(catalogStore.loadCurrent(ledgerId))
            // Enough catalog for the future ordinary self-entry: the managed asset and the single
            // active expense leaf/path that a 早餐 row resolves against.
            assertNotNull(catalog.accounts.firstOrNull { account -> account.id == AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID) })
            val expenseLeaf =
                assertNotNull(catalog.categories.firstOrNull { category -> category.id == CategoryId(DEFAULT_EXPENSE_LEAF_ID) })
            assertEquals(DEFAULT_EXPENSE_LEAF_NAME, expenseLeaf.name)
            assertEquals(CategoryId(DEFAULT_EXPENSE_GROUP_ID), expenseLeaf.parentId)
            assertEquals(AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID), expenseLeaf.postingAccountId)
            assertTrue(expenseLeaf.active)

            val adapter =
                SqlDelightP801AccountReconciliationReadAdapter(
                    database,
                    SqlDelightEvidenceProjectionStore(database, driver),
                )

            // The destination leg is the unlinked reconciliation subject: PENDING, exact +12.34,
            // no active evidence link.
            val destinationPage =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(
                        ledgerId,
                        AccountId(SEED_RECONCILE_ACCOUNT_ID),
                        after = null,
                        pageSize = 10,
                    ),
                ).page
            assertEquals(1, destinationPage.rows.size)
            val destinationLeg = destinationPage.rows.single()
            assertEquals(PostingId(SEED_TRANSFER_DESTINATION_POSTING_ID), destinationLeg.postingId)
            assertEquals(TransactionId(SEED_TRANSFER_TRANSACTION_ID), destinationLeg.transactionId)
            assertEquals(SEED_TRANSFER_MINOR, destinationLeg.amountMinor)
            assertEquals(SEED_TRANSFER_NOTE, destinationLeg.note)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, destinationLeg.transactionKind)
            assertEquals(P408ReconciliationStatus.PENDING, destinationLeg.status)
            assertEquals(false, destinationLeg.hasActiveEvidenceLink)

            // The source leg of the same transaction is independently PENDING (the flow reconciles
            // one leg at a time); nothing here is a transaction-level rollup.
            val sourcePage =
                assertIs<P801AccountReconciliationPageResult.Success>(
                    adapter.loadAccountReconciliationPage(
                        ledgerId,
                        AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
                        after = null,
                        pageSize = 10,
                    ),
                ).page
            assertEquals(1, sourcePage.rows.size)
            val sourceLeg = sourcePage.rows.single()
            assertEquals(-SEED_TRANSFER_MINOR, sourceLeg.amountMinor)
            assertEquals(destinationLeg.transactionId, sourceLeg.transactionId)
            assertEquals(P408ReconciliationStatus.PENDING, sourceLeg.status)
            assertEquals(false, sourceLeg.hasActiveEvidenceLink)

            val destinationSummary =
                assertIs<P801AccountReconciliationSummaryResult.Success>(
                    adapter.loadAccountReconciliationSummary(ledgerId, AccountId(SEED_RECONCILE_ACCOUNT_ID)),
                ).totals
            assertEquals(1, destinationSummary.size)
            assertEquals(P408ReconciliationStatus.PENDING, destinationSummary.single().status)
            assertEquals(SEED_TRANSFER_MINOR, destinationSummary.single().totalMinorUnits)
            assertEquals(1, destinationSummary.single().rowCount)

            // The import evidence the flow enters from: raw facts only, matching the destination
            // leg, no projection (binding is still ahead), no active link.
            val evidenceWindowFrom = SEED_TRANSFER_INSTANT - 2.days
            val evidenceWindowTo = SEED_TRANSFER_INSTANT + 2.days
            val candidateEvidence =
                assertIs<P801CandidateEvidenceResult.Success>(
                    adapter.loadCandidateEvidence(
                        ledgerId,
                        AccountId(SEED_RECONCILE_ACCOUNT_ID),
                        evidenceWindowFrom,
                        evidenceWindowTo,
                    ),
                ).rows
            assertEquals(1, candidateEvidence.size)
            val evidence = candidateEvidence.single()
            assertEquals(SEED_IMPORT_EVIDENCE_ID, evidence.evidenceId)
            assertEquals(SEED_IMPORT_SOURCE_ID, evidence.sourceId)
            assertEquals(SEED_IMPORT_CANDIDATE_ID, evidence.candidateId)
            assertEquals(SEED_TRANSFER_MINOR, assertNotNull(evidence.rawAmountMinor))
            assertEquals(CATALOG_MANAGED_CURRENCY.code, assertNotNull(evidence.rawCurrencyCode))
            assertEquals(CATALOG_MANAGED_CURRENCY.precision, assertNotNull(evidence.rawCurrencyPrecision))
            assertEquals("in", assertNotNull(evidence.rawDirectionToken))
            assertEquals(SEED_TRANSFER_INSTANT.toString(), evidence.occurredAt.rawText)
            assertEquals("offset_datetime", evidence.occurredAt.kind)
            assertTrue(evidence.occurredAt.offsetPresent)
            assertNull(evidence.projection)
            assertEquals(false, evidence.hasActiveEvidenceLink)

            val evidenceById =
                assertIs<P801CandidateEvidenceResult.Success>(
                    adapter.loadCandidateEvidenceById(ledgerId, SEED_IMPORT_EVIDENCE_ID),
                ).rows
            assertEquals(listOf(evidence), evidenceById)

            // The frozen matcher, driven with the facts binding would materialize for the target
            // account, proposes exactly this destination leg. This is a read-only proof; the seed
            // itself writes no projection and no link.
            val postingFacts =
                assertIs<P801AccountPostingFactsResult.Success>(
                    adapter.loadAccountPostingFacts(
                        ledgerId,
                        AccountId(SEED_RECONCILE_ACCOUNT_ID),
                        evidenceWindowFrom,
                        evidenceWindowTo,
                    ),
                ).facts
            assertEquals(1, postingFacts.size)
            val destinationFacts = postingFacts.single()
            assertEquals(PostingId(SEED_TRANSFER_DESTINATION_POSTING_ID), PostingId(destinationFacts.postingId))
            assertEquals(SEED_TRANSFER_MINOR, destinationFacts.amountMinor)
            assertEquals("in", destinationFacts.direction)
            assertTrue(destinationFacts.eligibleRealAccount)
            assertTrue(destinationFacts.current)
            val match =
                P408Matcher().match(
                    evidence =
                        P408EvidenceFacts(
                            ledgerId = ledgerId.value,
                            evidenceId = evidence.evidenceId,
                            raw =
                                P408RawEvidenceFacts(
                                    sourceId = evidence.sourceId,
                                    contentHash = evidence.contentHash,
                                    amountMinor = assertNotNull(evidence.rawAmountMinor),
                                    currencyCode = assertNotNull(evidence.rawCurrencyCode),
                                    currencyPrecision = assertNotNull(evidence.rawCurrencyPrecision),
                                    directionToken = assertNotNull(evidence.rawDirectionToken),
                                ),
                            normalized =
                                P408NormalizedProjectionFacts(
                                    projectionId = "p807-seed-not-materialized",
                                    targetAccountId = SEED_RECONCILE_ACCOUNT_ID,
                                    currencyCode = CATALOG_MANAGED_CURRENCY.code,
                                    currencyPrecision = CATALOG_MANAGED_CURRENCY.precision,
                                    normalizedAmountMinor = assertNotNull(evidence.rawAmountMinor),
                                    directionToken = assertNotNull(evidence.rawDirectionToken),
                                    ruleId = P408EvidenceProjectionPort.RULE_ID,
                                    ruleVersion = P408EvidenceProjectionPort.RULE_VERSION,
                                ),
                            occurredAt = evidence.occurredAt,
                        ),
                    postings = postingFacts,
                )
            assertEquals(P408MatchDisposition.PROPOSED_MATCH, match.disposition)
            val matchedCandidate = match.candidates.single()
            val matchedLeg = matchedCandidate.posting
            assertEquals(PostingId(SEED_TRANSFER_DESTINATION_POSTING_ID), PostingId(matchedLeg.postingId))
            assertEquals(0L, matchedCandidate.basis.naturalDayDistance)

            // The refundable original the manual-refund flow selects: exactly the seeded expense,
            // inheriting 早餐, with its full structural cap and remaining amount still available.
            val refundCandidates =
                assertIs<ManualRefundCandidatesResult.Loaded>(
                    SqlDelightManualRefundCandidatesReadAdapter(database).readPage(
                        ledgerId,
                        pageSize = 10,
                        pageOffset = 0,
                    ),
                )
            assertEquals(1, refundCandidates.candidates.size)
            val refundCandidate = refundCandidates.candidates.single()
            assertEquals(TransactionId(SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID), refundCandidate.transactionId)
            assertEquals(DEFAULT_EXPENSE_LEAF_NAME, refundCandidate.categoryName)
            assertEquals(SEED_REFUNDABLE_EXPENSE_MINOR, refundCandidate.structuralCap.minorUnits)
            assertEquals(SEED_REFUNDABLE_EXPENSE_MINOR, refundCandidate.remaining.minorUnits)

            // Exact starting balances: the transfer moved 12.34 between asset accounts, the
            // refundable expense left 21.00 from its payment account.
            val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
            val currentState =
                assertIs<LedgerCurrentStateResult.Success>(
                    QueryLedgerCurrentState(readAdapter, ledgerId, catalog).query(),
                ).state
            assertEquals(
                -SEED_TRANSFER_MINOR,
                assertNotNull(
                    currentState.balances.firstOrNull { balance -> balance.accountId == AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID) },
                ).displayMinorUnits,
            )
            assertEquals(
                SEED_TRANSFER_MINOR,
                assertNotNull(
                    currentState.balances.firstOrNull { balance -> balance.accountId == AccountId(SEED_RECONCILE_ACCOUNT_ID) },
                ).displayMinorUnits,
            )
            assertEquals(
                -SEED_REFUNDABLE_EXPENSE_MINOR,
                assertNotNull(
                    currentState.balances.firstOrNull { balance -> balance.accountId == AccountId(SEED_REFUND_ACCOUNT_ID) },
                ).displayMinorUnits,
            )
            assertEquals(
                CATALOG_MANAGED_CURRENCY,
                assertNotNull(
                    currentState.balances.firstOrNull { balance -> balance.accountId == AccountId(SEED_RECONCILE_ACCOUNT_ID) },
                ).currency,
            )

            // Row identity through the product's own detail reads: both transactions exist with
            // the seeded notes and exact leg accounts.
            val transferDetail =
                assertIs<TransactionDetailResult.Success>(
                    QueryTransactionDetail(readAdapter, ledgerId, catalog).query(TransactionId(SEED_TRANSFER_TRANSACTION_ID)),
                ).detail
            assertEquals(SEED_TRANSFER_NOTE, transferDetail.note)
            assertEquals(TransactionKind.ACCOUNT_TRANSFER, transferDetail.kind)
            assertEquals(
                setOf(AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID), AccountId(SEED_RECONCILE_ACCOUNT_ID)),
                transferDetail.legs.map { leg -> leg.accountId }.toSet(),
            )
            val expenseDetail =
                assertIs<TransactionDetailResult.Success>(
                    QueryTransactionDetail(readAdapter, ledgerId, catalog)
                        .query(TransactionId(SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID)),
                ).detail
            assertEquals(SEED_REFUNDABLE_EXPENSE_NOTE, expenseDetail.note)
            assertEquals(TransactionKind.EXPENSE, expenseDetail.kind)
            assertEquals(
                setOf(AccountId(DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID), AccountId(SEED_REFUND_ACCOUNT_ID)),
                expenseDetail.legs.map { leg -> leg.accountId }.toSet(),
            )

            // The two created asset accounts are part of the manageable projection the future
            // flows select from.
            val snapshot = assertNotNull(QueryCatalogSnapshot(catalogStore).query(ledgerId))
            listOf(SEED_RECONCILE_ACCOUNT_ID to SEED_RECONCILE_ACCOUNT_NAME, SEED_REFUND_ACCOUNT_ID to SEED_REFUND_ACCOUNT_NAME)
                .forEach { (id, name) ->
                    val view =
                        assertNotNull(
                            snapshot.manageableAccounts.firstOrNull { account -> account.accountId == AccountId(id) },
                        )
                    assertEquals(name, view.name)
                    assertEquals(AccountKind.ASSET, view.kind)
                    assertTrue(view.active)
                }
        }
    }

    private fun url(file: File): String = "jdbc:sqlite:" + file.absolutePath.replace('\\', '/')

    /** The header version androidx `SQLiteOpenHelper` gates the app's open on. */
    private fun userVersion(driver: JdbcSqliteDriver): Long? {
        val result =
            driver.executeQuery(
                null,
                "PRAGMA user_version",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                0,
                null,
            )
        return result.value
    }

    private fun foreignKeyProperties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

    private companion object {
        /** The opt-in system property; absent in every ordinary test run, which still executes. */
        const val SEED_PATH_PROPERTY = "ul.p807.shared.seed"

        const val SEED_FILE_NAME = "p807-shared-acceptance.sqlite"

        /** `android-app/.../App.kt` `PRODUCT_LEDGER_ID` — the app bootstraps this one identity. */
        const val LEDGER_ID = "ledger-local-test"

        const val SEED_RECONCILE_ACCOUNT_ID = "p807-seed-reconcile-asset"
        const val SEED_RECONCILE_ACCOUNT_NAME = "对账到账账户"
        const val SEED_REFUND_ACCOUNT_ID = "p807-seed-refund-asset"
        const val SEED_REFUND_ACCOUNT_NAME = "退款到账账户"

        const val SEED_TRANSFER_TRANSACTION_ID = "p807-seed-transfer"
        const val SEED_TRANSFER_SOURCE_POSTING_ID = "p807-seed-transfer-out"
        const val SEED_TRANSFER_DESTINATION_POSTING_ID = "p807-seed-transfer-in"
        const val SEED_TRANSFER_NOTE = "P807 对账样本转账"
        const val SEED_TRANSFER_MINOR = 1_234L

        const val SEED_IMPORT_INPUT_REF = "p807-shared-seed-statement"
        const val SEED_IMPORT_REQUEST_ID = "p807-seed-import-request"
        const val SEED_IMPORT_SOURCE_ID = "p807-seed-import-source"
        const val SEED_IMPORT_EVIDENCE_ID = "p807-seed-import-evidence"
        const val SEED_IMPORT_CANDIDATE_ID = "p807-seed-import-candidate"
        const val SEED_IMPORT_STATUS_ID = "p807-seed-import-status"

        const val SEED_REFUNDABLE_EXPENSE_TRANSACTION_ID = "p807-seed-refundable-expense"
        const val SEED_REFUNDABLE_EXPENSE_NOTE = "P807 可退样本支出"
        const val SEED_REFUNDABLE_EXPENSE_MINOR = 2_100L

        /** 2026-10-14 12:00 Asia/Shanghai; mid-month, persisted as `...Z` like every product write. */
        val SEED_TRANSFER_INSTANT: Instant = Instant.parse("2026-10-14T04:00:00Z")

        /** 2026-10-15 10:00 Asia/Shanghai; a distinct traceable original in the same fixed month. */
        val SEED_REFUNDABLE_EXPENSE_INSTANT: Instant = Instant.parse("2026-10-15T02:00:00Z")

        val SEED_CREATED_AT: Instant = SEED_TRANSFER_INSTANT + 5.minutes
        val SEED_REFUNDABLE_EXPENSE_CREATED_AT: Instant = SEED_REFUNDABLE_EXPENSE_INSTANT + 5.minutes
        val SEED_IMPORT_GENERATED_AT: Instant = SEED_TRANSFER_INSTANT + 1.minutes
    }
}
