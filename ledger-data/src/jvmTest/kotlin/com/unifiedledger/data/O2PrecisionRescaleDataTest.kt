package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.CreditFlowFormalFactory
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateConfirmRequest
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmDecisionFields
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportContentFingerprint
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
import com.unifiedledger.application.OrdinaryFlowFormalFactory
import com.unifiedledger.application.TransferFlowFormalFactory
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
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionVersionId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** O-2 persistence proof: source facts stay raw while formal postings use target precision. */
class O2PrecisionRescaleDataTest {
    private val ledgerId = LedgerId("ledger-o2")
    private val generatedAt = "2026-08-23T08:00:00Z"
    private val confirmedAt = "2026-08-23T10:00:00+08:00"

    @Test
    fun unvalidatedFactoryGraphCannotCrossFormalPersistenceBoundary() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val facts = facts(99L, 0)
            val profile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "credit")
            val intakeIds =
                object : ImportIntakeIdSource {
                    override fun next() =
                        ImportIntakeIds(
                            ImportSourceId("source-o2-boundary"),
                            com.unifiedledger.application.ImportEvidenceId("evidence-o2-boundary"),
                            com.unifiedledger.application.ImportCandidateId("candidate-o2-boundary"),
                            ImportStatusHistoryId("status-o2-boundary-1"),
                        )
                }
            ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(
                request("req-o2-boundary-intake", ImportRecordKind.CREDIT_EXPENSE_SOURCE, facts, profile),
            )
            val hash = ImportContentFingerprint().digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, facts, profile)
            val ids =
                object : ImportIdSource {
                    private val calls = AtomicInteger(0)

                    override fun next(): ImportCommitIds {
                        val suffix = calls.getAndIncrement()
                        return ImportCommitIds(
                            ImportConfirmationId("confirmation-o2-boundary-$suffix"),
                            ImportStatusHistoryId("status-o2-boundary-2-$suffix"),
                            ImportFormalIds(
                                TransactionId("tx-o2-boundary-$suffix"),
                                TransactionVersionId("version-o2-boundary-$suffix"),
                                PostingSetId("set-o2-boundary-$suffix"),
                                listOf(PostingId("posting-o2-boundary-$suffix-0"), PostingId("posting-o2-boundary-$suffix-1")),
                            ),
                        )
                    }
                }
            val catalog = catalog()
            val maliciousFactory =
                object : ImportCandidateFormalFactory {
                    override fun create(
                        input: ImportCandidateFormalizationInput,
                        allocated: ImportCommitIds,
                    ): DomainResult<ImportFormalCommit> {
                        val trusted =
                            assertIs<DomainResult.Success<ImportFormalCommit>>(
                                CreditFlowFormalFactory(catalog) { null }.create(input, allocated),
                            ).value
                        val originalPostings =
                            trusted.transaction.postingSets
                                .single()
                                .postings
                        val wrongPostingSet =
                            assertIs<DomainResult.Success<PostingSet>>(
                                PostingSet.create(
                                    trusted.transaction.postingSets
                                        .single()
                                        .id,
                                    originalPostings.mapIndexed { index, posting ->
                                        posting.copy(
                                            // Keep the graph internally balanced and the posting
                                            // currency valid, but forge a cross-ledger account id.
                                            accountId = if (index == 0) AccountId("asset-other-ledger") else posting.accountId,
                                        )
                                    },
                                ),
                            ).value
                        val wrongGraph =
                            assertIs<DomainResult.Success<FormalTransaction>>(
                                FormalTransaction.create(
                                    trusted.transaction.transaction,
                                    trusted.transaction.versions,
                                    listOf(wrongPostingSet),
                                ),
                            ).value
                        return DomainResult.Success(
                            ImportFormalCommit(
                                allocated.confirmationId,
                                allocated.statusHistoryId,
                                wrongGraph,
                            ),
                        )
                    }
                }
            val rejected =
                ConfirmImportCandidate(store, ids, maliciousFactory, catalog).execute(
                    confirm("req-o2-boundary-confirm", hash, AccountId("liability-cny"), "candidate-o2-boundary"),
                )
            assertEquals("SPINE_REFERENCE_INTEGRITY_VIOLATION", assertIs<ImportCandidateDecisionResult.Rejected>(rejected).diagnostic.code)
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            val source =
                database.ledgerQueries
                    .selectImportSourceByOwnerRequest(
                        ledgerId.value,
                        "req-o2-boundary-intake",
                    ).executeAsOne()
            assertEquals(99L, source.amount_minor)
            assertEquals(0L, source.currency_precision)
            assertEquals(hash, source.content_hash)
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-o2-boundary")
                    .executeAsOne()
                    .status,
            )

            val accepted =
                ConfirmImportCandidate(store, ids, CreditFlowFormalFactory(catalog) { null }, catalog).execute(
                    confirm("req-o2-boundary-retry", hash, AccountId("liability-cny"), "candidate-o2-boundary"),
                )
            assertIs<ImportCandidateDecisionResult.Accepted>(accepted)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun domainFailureLeavesZeroFormalRowsAndCorrectedRetrySucceeds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val facts = facts(99L, 0)
            val profile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "credit")
            val intake =
                ExecuteImportIntake(
                    store,
                    object : ImportIntakeIdSource {
                        override fun next() =
                            ImportIntakeIds(
                                ImportSourceId("source-o2"),
                                com.unifiedledger.application.ImportEvidenceId("evidence-o2"),
                                com.unifiedledger.application.ImportCandidateId("candidate-o2"),
                                ImportStatusHistoryId("status-o2-1"),
                            )
                    },
                    ImportContentFingerprint(),
                )
            val intakeResult =
                assertIs<ImportIntakeResult.Accepted>(
                    intake.execute(request("req-o2-intake", ImportRecordKind.CREDIT_EXPENSE_SOURCE, facts, profile)),
                )
            val hash = ImportContentFingerprint().digest(ImportRecordKind.CREDIT_EXPENSE_SOURCE, facts, profile)
            val commitIds =
                object : ImportIdSource {
                    private val calls = AtomicInteger(0)

                    override fun next(): ImportCommitIds {
                        val suffix = calls.getAndIncrement()
                        return ImportCommitIds(
                            ImportConfirmationId("confirmation-o2-$suffix"),
                            ImportStatusHistoryId("status-o2-2-$suffix"),
                            ImportFormalIds(
                                TransactionId("tx-o2-$suffix"),
                                TransactionVersionId("version-o2-$suffix"),
                                PostingSetId("set-o2-$suffix"),
                                listOf(PostingId("posting-o2-$suffix-0"), PostingId("posting-o2-$suffix-1")),
                            ),
                        )
                    }
                }
            val factory = CreditFlowFormalFactory(catalog()) { null }
            val first =
                ConfirmImportCandidate(
                    store,
                    commitIds,
                    factory,
                    catalog(),
                ).execute(
                    confirm(
                        requestId = "req-o2-confirm-usd",
                        hash = hash,
                        liability = AccountId("liability-usd"),
                    ),
                )
            assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", assertIs<ImportCandidateDecisionResult.Rejected>(first).diagnostic.code)
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, "candidate-o2")
                    .executeAsOne()
                    .status,
            )

            val second =
                ConfirmImportCandidate(store, commitIds, factory, catalog()).execute(
                    confirm(
                        requestId = "req-o2-confirm-cny",
                        hash = hash,
                        liability = AccountId("liability-cny"),
                    ),
                )
            assertIs<ImportCandidateDecisionResult.Accepted>(second)
            assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
            val postings = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, "set-o2-1").executeAsList()
            assertEquals(listOf(9900L, -9900L), postings.map { it.amount_minor })
            assertEquals(listOf(2L, 2L), postings.map { it.currency_precision })

            // Raw source facts and fingerprint are not rewritten by confirmation.
            val source = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-o2-intake").executeAsOne()
            assertEquals(99L, source.amount_minor)
            assertEquals(0L, source.currency_precision)
            assertEquals(hash, source.content_hash)
            assertTrue(intakeResult.receipt.sourceId != null)
        } finally {
            driver.close()
        }
    }

    @Test
    fun originalExpenseProviderRuntimeExceptionRollsBackAndSameRequestRetries() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog()
            val store = SqlDelightImportSpineStore(database, driver)

            val originalFacts = facts(99L, 0)
            val originalProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "credit")
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(
                    store,
                    ImportIntakeIdSource {
                        ImportIntakeIds(
                            ImportSourceId("source-o2-provider-original"),
                            com.unifiedledger.application.ImportEvidenceId("evidence-o2-provider-original"),
                            com.unifiedledger.application.ImportCandidateId("candidate-o2-provider-original"),
                            ImportStatusHistoryId("status-o2-provider-original-1"),
                        )
                    },
                    ImportContentFingerprint(),
                ).execute(
                    request(
                        "req-o2-provider-original-intake",
                        ImportRecordKind.CREDIT_EXPENSE_SOURCE,
                        originalFacts,
                        originalProfile,
                        inputRef = "o2-provider-original",
                    ),
                ),
            )
            val originalHash =
                ImportContentFingerprint().digest(
                    ImportRecordKind.CREDIT_EXPENSE_SOURCE,
                    originalFacts,
                    originalProfile,
                )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                ConfirmImportCandidate(
                    store,
                    ImportIdSource { commitIds("provider-original", 2) },
                    CreditFlowFormalFactory(catalog) { null },
                    catalog,
                ).execute(
                    ImportCandidateConfirmRequest(
                        ImportRequestIdentity(ledgerId, ImportRequestId("req-o2-provider-original-confirm")),
                        com.unifiedledger.application.ImportCandidateId("candidate-o2-provider-original"),
                        originalHash,
                        confirmedAt,
                        ImportConfirmDecisionFields.CreditExpenseFlow(
                            CategoryId("category-food"),
                            AccountId("liability-cny"),
                        ),
                    ),
                ),
            )

            val refundFacts = facts(99L, 0, direction = "in")
            val refundProfile = ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "credit")
            val refundCandidateId = com.unifiedledger.application.ImportCandidateId("candidate-o2-provider-refund")
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(
                    store,
                    ImportIntakeIdSource {
                        ImportIntakeIds(
                            ImportSourceId("source-o2-provider-refund"),
                            com.unifiedledger.application.ImportEvidenceId("evidence-o2-provider-refund"),
                            refundCandidateId,
                            ImportStatusHistoryId("status-o2-provider-refund-1"),
                        )
                    },
                    ImportContentFingerprint(),
                ).execute(
                    request(
                        "req-o2-provider-refund-intake",
                        ImportRecordKind.CREDIT_EXPENSE_SOURCE,
                        refundFacts,
                        refundProfile,
                        inputRef = "o2-provider-refund",
                        recordOrdinal = 1,
                    ),
                ),
            )
            val refundHash =
                ImportContentFingerprint().digest(
                    ImportRecordKind.CREDIT_EXPENSE_SOURCE,
                    refundFacts,
                    refundProfile,
                )
            val refundRequest =
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(ledgerId, ImportRequestId("req-o2-provider-refund-confirm")),
                    refundCandidateId,
                    refundHash,
                    confirmedAt,
                    ImportConfirmDecisionFields.CreditExpenseRefundFlow(
                        CategoryId("category-food"),
                        AccountId("liability-cny"),
                        TransactionId("tx-provider-original"),
                    ),
                )
            val baseline = confirmationCounts(database, driver)
            var providerCalls = 0
            val throwingFactory =
                CreditFlowFormalFactory(catalog) {
                    providerCalls += 1
                    error("original expense provider failed")
                }
            val rejected =
                ConfirmImportCandidate(
                    store,
                    ImportIdSource { commitIds("provider-refund", 2) },
                    throwingFactory,
                    catalog,
                ).execute(refundRequest)
            assertEquals(
                "SPINE_DOMAIN_VALIDATION_FAILED",
                assertIs<ImportCandidateDecisionResult.Rejected>(rejected).diagnostic.code,
            )
            assertEquals(1, providerCalls)
            assertEquals(baseline, confirmationCounts(database, driver))
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(
                        ledgerId.value,
                        refundCandidateId.value,
                    ).executeAsOne()
                    .status,
            )
            val refundSource =
                database.ledgerQueries
                    .selectImportSourceByOwnerRequest(
                        ledgerId.value,
                        "req-o2-provider-refund-intake",
                    ).executeAsOne()
            assertEquals(refundFacts.amountMinor, refundSource.amount_minor)
            assertEquals(refundFacts.currencyCode, refundSource.currency_code)
            assertEquals(refundFacts.currencyPrecision.toLong(), refundSource.currency_precision)
            assertEquals(refundFacts.occurredAt, refundSource.occurred_at)
            assertEquals(refundFacts.directionToken, refundSource.direction_token)
            assertEquals(refundFacts.statusToken, refundSource.status_token)
            assertEquals(refundFacts.fundingState.name, refundSource.funding_state)
            assertEquals(refundFacts.fundingRuleId, refundSource.funding_rule_id)
            assertEquals(refundFacts.fundingRuleVersion.toLong(), refundSource.funding_rule_version)
            assertEquals(refundHash, refundSource.content_hash)

            val accepted =
                ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver),
                    ImportIdSource { commitIds("provider-refund", 2) },
                    CreditFlowFormalFactory(catalog) { transactionId ->
                        CreditRefundOriginalExpense(
                            transactionId,
                            ledgerId,
                            TransactionKind.EXPENSE,
                            "CNY",
                            AccountId("expense"),
                        )
                    },
                    catalog,
                ).execute(refundRequest)
            assertIs<ImportCandidateDecisionResult.Accepted>(accepted)
            assertEquals(
                baseline.copy(
                    importRequests = baseline.importRequests + 1L,
                    statusHistory = baseline.statusHistory + 1L,
                    decisionSnapshots = baseline.decisionSnapshots + 1L,
                    confirmations = baseline.confirmations + 1L,
                    receipts = baseline.receipts + 1L,
                    transactions = baseline.transactions + 1L,
                    versions = baseline.versions + 1L,
                    postingSets = baseline.postingSets + 1L,
                    postings = baseline.postings + 2L,
                ),
                confirmationCounts(database, driver),
            )
            assertEquals(
                "confirmed",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(
                        ledgerId.value,
                        refundCandidateId.value,
                    ).executeAsOne()
                    .status,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun malformedOccurredAtMapsToTypedDomainFailureAndLeavesCandidateRetryable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog()
            val store = SqlDelightImportSpineStore(database, driver)
            val malformedFacts = facts(99L, 0).copy(occurredAt = "not-an-instant")
            val profile: ImportPaymentProfile? = null
            val kind = ImportRecordKind.ORDINARY_FLOW_SOURCE
            val intake =
                ExecuteImportIntake(
                    store,
                    object : ImportIntakeIdSource {
                        override fun next() =
                            ImportIntakeIds(
                                ImportSourceId("source-o2-malformed-time"),
                                com.unifiedledger.application.ImportEvidenceId("evidence-o2-malformed-time"),
                                com.unifiedledger.application.ImportCandidateId("candidate-o2-malformed-time"),
                                ImportStatusHistoryId("status-o2-malformed-time-1"),
                            )
                    },
                    ImportContentFingerprint(),
                )
            assertIs<ImportIntakeResult.Accepted>(
                intake.execute(
                    request(
                        "req-o2-malformed-time-intake",
                        kind,
                        malformedFacts,
                        profile,
                    ),
                ),
            )
            val hash = ImportContentFingerprint().digest(kind, malformedFacts, profile)
            val ids =
                object : ImportIdSource {
                    private val calls = AtomicInteger(0)

                    override fun next(): ImportCommitIds = commitIds("malformed-time-${calls.getAndIncrement()}", 2)
                }

            fun confirm(requestId: String): ImportCandidateDecisionResult =
                ConfirmImportCandidate(
                    store,
                    ids,
                    OrdinaryFlowFormalFactory(catalog),
                    catalog,
                ).execute(
                    ImportCandidateConfirmRequest(
                        ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
                        com.unifiedledger.application.ImportCandidateId("candidate-o2-malformed-time"),
                        hash,
                        confirmedAt,
                        com.unifiedledger.application.ImportConfirmDecisionFields.OrdinaryFlow(
                            CategoryId("category-food"),
                            AccountId("asset"),
                        ),
                    ),
                )

            repeat(2) { attempt ->
                val result = assertIs<ImportCandidateDecisionResult.Rejected>(confirm("req-o2-malformed-time-$attempt"))
                assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", result.diagnostic.code)
            }
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(
                        ledgerId.value,
                        "candidate-o2-malformed-time",
                    ).executeAsOne()
                    .status,
            )
            val source =
                database.ledgerQueries
                    .selectImportSourceByOwnerRequest(
                        ledgerId.value,
                        "req-o2-malformed-time-intake",
                    ).executeAsOne()
            assertEquals(99L, source.amount_minor)
            assertEquals(0L, source.currency_precision)
            assertEquals(hash, source.content_hash)
        } finally {
            driver.close()
        }
    }

    @Test
    fun highPrecisionZeroIntakeKeepsRawFactsAndAStableFingerprint() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val sourceFacts = facts(0L, Int.MAX_VALUE)
            val intake =
                ExecuteImportIntake(
                    store,
                    object : ImportIntakeIdSource {
                        override fun next() =
                            ImportIntakeIds(
                                ImportSourceId("source-o2-high-scale"),
                                com.unifiedledger.application.ImportEvidenceId("evidence-o2-high-scale"),
                                com.unifiedledger.application.ImportCandidateId("candidate-o2-high-scale"),
                                ImportStatusHistoryId("status-o2-high-scale-1"),
                            )
                    },
                    ImportContentFingerprint(),
                )
            assertIs<ImportIntakeResult.Accepted>(
                intake.execute(
                    request(
                        requestId = "req-o2-high-scale-intake",
                        kind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
                        facts = sourceFacts,
                        profile = null,
                        inputRef = "o2-high-scale",
                    ),
                ),
            )

            val expectedHash =
                ImportContentFingerprint().digest(
                    ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    sourceFacts,
                )
            val source =
                database.ledgerQueries
                    .selectImportSourceByOwnerRequest(
                        ledgerId.value,
                        "req-o2-high-scale-intake",
                    ).executeAsOne()
            assertEquals(0L, source.amount_minor)
            assertEquals(Int.MAX_VALUE.toLong(), source.currency_precision)
            assertEquals("CNY", source.currency_code)
            assertEquals(sourceFacts.occurredAt, source.occurred_at)
            assertEquals("out", source.direction_token)
            assertEquals("settled", source.status_token)
            assertEquals("SETTLED", source.funding_state)
            assertEquals(IMPORT_FUNDING_RULE_LEGACY_SETTLED, source.funding_rule_id)
            assertEquals(1L, source.funding_rule_version)
            assertEquals(expectedHash, source.content_hash)
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(
                        ledgerId.value,
                        "candidate-o2-high-scale",
                    ).executeAsOne()
                    .status,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun mixedNullConfirmationTimeRejectsBeforeIdsOrFactoryAndSameRequestRetries() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog()
            val store = SqlDelightImportSpineStore(database, driver)
            val sourceFacts = facts(99L, 0)
            val profile = ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, "asset", "credit")
            val candidateId = "candidate-o2-missing-confirmed-at"
            assertIs<ImportIntakeResult.Accepted>(
                ExecuteImportIntake(
                    store,
                    object : ImportIntakeIdSource {
                        override fun next() =
                            ImportIntakeIds(
                                ImportSourceId("source-o2-missing-confirmed-at"),
                                com.unifiedledger.application.ImportEvidenceId("evidence-o2-missing-confirmed-at"),
                                com.unifiedledger.application.ImportCandidateId(candidateId),
                                ImportStatusHistoryId("status-o2-missing-confirmed-at-1"),
                            )
                    },
                    ImportContentFingerprint(),
                ).execute(
                    request(
                        requestId = "req-o2-missing-confirmed-at-intake",
                        kind = ImportRecordKind.MIXED_PAYMENT_SOURCE,
                        facts = sourceFacts,
                        profile = profile,
                        inputRef = "o2-missing-confirmed-at",
                    ),
                ),
            )
            val hash = ImportContentFingerprint().digest(ImportRecordKind.MIXED_PAYMENT_SOURCE, sourceFacts, profile)
            val missingTime =
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(ledgerId, ImportRequestId("req-o2-missing-confirmed-at-confirm")),
                    com.unifiedledger.application.ImportCandidateId(candidateId),
                    hash,
                    null,
                    ImportConfirmDecisionFields.MixedPaymentFlow(
                        CategoryId("category-food"),
                        AccountId("asset"),
                        AccountId("liability-cny"),
                        5000L,
                        4900L,
                    ),
                )
            var idCalls = 0
            var factoryCalls = 0
            val baseline = confirmationCounts(database, driver)
            val rejected =
                ConfirmImportCandidate(
                    store,
                    ImportIdSource {
                        idCalls += 1
                        commitIds("missing-confirmed-at", 3)
                    },
                    ImportCandidateFormalFactory { _, _ ->
                        factoryCalls += 1
                        error("factory must not be invoked for missing confirmation time")
                    },
                    catalog,
                ).execute(missingTime)
            assertEquals(
                "SPINE_CANDIDATE_INCOMPLETE",
                assertIs<ImportCandidateDecisionResult.Rejected>(rejected).diagnostic.code,
            )
            assertEquals(0, idCalls)
            assertEquals(0, factoryCalls)
            assertEquals(baseline, confirmationCounts(database, driver))
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, candidateId)
                    .executeAsOne()
                    .status,
            )

            val accepted =
                ConfirmImportCandidate(
                    store,
                    ImportIdSource { commitIds("missing-confirmed-at", 3) },
                    MixedPaymentFlowFormalFactory(catalog),
                    catalog,
                ).execute(missingTime.copy(explicitConfirmedAt = confirmedAt))
            assertIs<ImportCandidateDecisionResult.Accepted>(accepted)
            assertEquals(
                baseline.copy(
                    importRequests = baseline.importRequests + 1L,
                    statusHistory = baseline.statusHistory + 1L,
                    decisionSnapshots = baseline.decisionSnapshots + 1L,
                    confirmations = baseline.confirmations + 1L,
                    receipts = baseline.receipts + 1L,
                    transactions = baseline.transactions + 1L,
                    versions = baseline.versions + 1L,
                    postingSets = baseline.postingSets + 1L,
                    postings = baseline.postings + 3L,
                    mixedGroups = baseline.mixedGroups + 1L,
                    mixedLegs = baseline.mixedLegs + 2L,
                ),
                confirmationCounts(database, driver),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun confirmFailuresAfterFormalAndMixedSegmentsRollbackAndRetry() {
        assertConfirmationFailureRollsBackAndRetries(ImportSpineFailurePoint.CONFIRM_AFTER_PERSIST_FORMAL, mixed = false)
        listOf(
            ImportSpineFailurePoint.CONFIRM_AFTER_PERSIST_FORMAL,
            ImportSpineFailurePoint.CONFIRM_AFTER_MIXED_ASSET_LEG,
            ImportSpineFailurePoint.CONFIRM_AFTER_MIXED_CREDIT_LEG,
            ImportSpineFailurePoint.CONFIRM_AFTER_MIXED_GROUP,
            ImportSpineFailurePoint.CONFIRM_AFTER_FORMAL,
            ImportSpineFailurePoint.CONFIRM_AFTER_DECISION_SNAPSHOT,
            ImportSpineFailurePoint.CONFIRM_AFTER_STATUS_HISTORY,
            ImportSpineFailurePoint.CONFIRM_AFTER_CONFIRMATION,
            ImportSpineFailurePoint.CONFIRM_AFTER_RECEIPT,
        ).forEach { point ->
            assertConfirmationFailureRollsBackAndRetries(point, mixed = true)
        }
    }

    @Test
    fun mixedGroupStoresNormalizedTotalAndTargetLegs() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val facts = facts(99L, 0)
            val profile = ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, "asset", "credit")
            val intakeIds =
                object : ImportIntakeIdSource {
                    override fun next() =
                        ImportIntakeIds(
                            ImportSourceId("source-mixed-o2"),
                            com.unifiedledger.application.ImportEvidenceId("evidence-mixed-o2"),
                            com.unifiedledger.application.ImportCandidateId("candidate-mixed-o2"),
                            ImportStatusHistoryId("status-mixed-o2-1"),
                        )
                }
            ExecuteImportIntake(store, intakeIds, ImportContentFingerprint()).execute(
                request("req-mixed-o2-intake", ImportRecordKind.MIXED_PAYMENT_SOURCE, facts, profile),
            )
            val hash = ImportContentFingerprint().digest(ImportRecordKind.MIXED_PAYMENT_SOURCE, facts, profile)
            val ids =
                object : ImportIdSource {
                    override fun next() =
                        ImportCommitIds(
                            ImportConfirmationId("confirmation-mixed-o2"),
                            ImportStatusHistoryId("status-mixed-o2-2"),
                            ImportFormalIds(
                                TransactionId("tx-mixed-o2"),
                                TransactionVersionId("version-mixed-o2"),
                                PostingSetId("set-mixed-o2"),
                                listOf(PostingId("posting-mixed-o2-0"), PostingId("posting-mixed-o2-1"), PostingId("posting-mixed-o2-2")),
                            ),
                        )
                }
            val result =
                ConfirmImportCandidate(store, ids, MixedPaymentFlowFormalFactory(catalog()), catalog()).execute(
                    ImportCandidateConfirmRequest(
                        ImportRequestIdentity(ledgerId, ImportRequestId("req-mixed-o2-confirm")),
                        com.unifiedledger.application.ImportCandidateId("candidate-mixed-o2"),
                        hash,
                        confirmedAt,
                        com.unifiedledger.application.ImportConfirmDecisionFields.MixedPaymentFlow(
                            CategoryId("category-food"),
                            AccountId("asset"),
                            AccountId("liability-cny"),
                            5000L,
                            4900L,
                        ),
                    ),
                )
            assertIs<ImportCandidateDecisionResult.Accepted>(result)
            assertEquals(9900L, scalarLong(driver, "SELECT total_minor FROM mixed_payment_group WHERE ledger_id = 'ledger-o2'"))
            assertEquals(listOf(5000L, 4900L), scalarLongs(driver, "SELECT amount_minor FROM mixed_payment_group_leg WHERE ledger_id = 'ledger-o2' ORDER BY leg_index"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun allFormalFlowsPersistNormalizedTargetPrecision() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightImportSpineStore(database, driver)
            val catalog = catalog()
            val cases =
                listOf(
                    Triple("expense", ImportRecordKind.ORDINARY_FLOW_SOURCE, facts(99L, 0, "out")),
                    Triple("expense-downscale", ImportRecordKind.ORDINARY_FLOW_SOURCE, facts(123400L, 4, "out")),
                    Triple("income", ImportRecordKind.ORDINARY_FLOW_SOURCE, facts(99L, 0, "in")),
                    Triple("transfer", ImportRecordKind.TRANSFER_FLOW_SOURCE, facts(99L, 0)),
                    Triple("credit", ImportRecordKind.CREDIT_EXPENSE_SOURCE, facts(99L, 0)),
                    // Refunds are source inflows, so the direction also keeps this tuple
                    // distinct from the preceding credit-expense outflow at the duplicate gate.
                    Triple("refund", ImportRecordKind.CREDIT_EXPENSE_SOURCE, facts(99L, 0, "in")),
                    Triple("repayment", ImportRecordKind.CREDIT_REPAYMENT_SOURCE, facts(99L, 0)),
                )
            val profiles =
                mapOf(
                    "credit" to ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "credit"),
                    "refund" to ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_REFUND, null, "credit"),
                    "repayment" to ImportPaymentProfile(ImportPaymentVariant.CREDIT_REPAYMENT, null, null),
                )
            val intakeSource =
                object : ImportIntakeIdSource {
                    private var index = 0

                    override fun next(): ImportIntakeIds {
                        val prefix = cases[index++].first
                        return ImportIntakeIds(
                            ImportSourceId("source-o2-$prefix"),
                            com.unifiedledger.application.ImportEvidenceId("evidence-o2-$prefix"),
                            com.unifiedledger.application.ImportCandidateId("candidate-o2-$prefix"),
                            ImportStatusHistoryId("status-o2-$prefix-1"),
                        )
                    }
                }
            val intake = ExecuteImportIntake(store, intakeSource, ImportContentFingerprint())
            cases.forEachIndexed { index, (prefix, kind, sourceFacts) ->
                val profile = profiles[prefix]
                assertIs<ImportIntakeResult.Accepted>(
                    intake.execute(
                        request(
                            requestId = "req-o2-$prefix-intake",
                            kind = kind,
                            facts = sourceFacts,
                            profile = profile,
                            inputRef = "o2-$prefix",
                            recordOrdinal = index,
                        ),
                    ),
                )
            }

            val commitSource =
                object : ImportIdSource {
                    private val ids =
                        listOf(
                            commitIds("expense-o2", 2),
                            commitIds("expense-downscale-o2", 2),
                            commitIds("income-o2", 2),
                            commitIds("transfer-o2", 2),
                            commitIds("credit-o2", 2),
                            commitIds("refund-o2", 2),
                            commitIds("repayment-o2", 2),
                        ).iterator()

                    override fun next(): ImportCommitIds = ids.next()
                }

            fun confirm(
                prefix: String,
                kind: ImportRecordKind,
                facts: ImportSourceFacts,
                profile: ImportPaymentProfile?,
                fields: com.unifiedledger.application.ImportConfirmDecisionFields,
                factory: ImportCandidateFormalFactory,
            ): ImportCandidateDecisionResult {
                val hash = ImportContentFingerprint().digest(kind, facts, profile)
                return ConfirmImportCandidate(store, commitSource, factory, catalog).execute(
                    ImportCandidateConfirmRequest(
                        ImportRequestIdentity(ledgerId, ImportRequestId("req-o2-$prefix-confirm")),
                        com.unifiedledger.application.ImportCandidateId("candidate-o2-$prefix"),
                        hash,
                        confirmedAt,
                        fields,
                    ),
                )
            }

            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    "expense",
                    ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    cases[0].third,
                    null,
                    com.unifiedledger.application.ImportConfirmDecisionFields.OrdinaryFlow(
                        CategoryId("category-food"),
                        AccountId("asset"),
                    ),
                    OrdinaryFlowFormalFactory(catalog),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    "expense-downscale",
                    ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    cases[1].third,
                    null,
                    com.unifiedledger.application.ImportConfirmDecisionFields.OrdinaryFlow(
                        CategoryId("category-food"),
                        AccountId("asset"),
                    ),
                    OrdinaryFlowFormalFactory(catalog),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    "income",
                    ImportRecordKind.ORDINARY_FLOW_SOURCE,
                    cases[2].third,
                    null,
                    com.unifiedledger.application.ImportConfirmDecisionFields.OrdinaryFlow(
                        CategoryId("category-income"),
                        AccountId("asset"),
                    ),
                    OrdinaryFlowFormalFactory(catalog),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    "transfer",
                    ImportRecordKind.TRANSFER_FLOW_SOURCE,
                    cases[3].third,
                    null,
                    com.unifiedledger.application.ImportConfirmDecisionFields.TransferFlow(
                        AccountId("asset"),
                        AccountId("asset-2"),
                    ),
                    TransferFlowFormalFactory(catalog, AccountId("asset")),
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    "credit",
                    ImportRecordKind.CREDIT_EXPENSE_SOURCE,
                    cases[4].third,
                    profiles["credit"],
                    com.unifiedledger.application.ImportConfirmDecisionFields.CreditExpenseFlow(
                        CategoryId("category-food"),
                        AccountId("liability-cny"),
                    ),
                    CreditFlowFormalFactory(catalog) { transactionId ->
                        if (transactionId.value == "tx-credit-o2") {
                            CreditRefundOriginalExpense(
                                transactionId,
                                ledgerId,
                                TransactionKind.EXPENSE,
                                "CNY",
                                AccountId("expense"),
                            )
                        } else {
                            null
                        }
                    },
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    "refund",
                    ImportRecordKind.CREDIT_EXPENSE_SOURCE,
                    cases[5].third,
                    profiles["refund"],
                    com.unifiedledger.application.ImportConfirmDecisionFields.CreditExpenseRefundFlow(
                        CategoryId("category-food"),
                        AccountId("liability-cny"),
                        TransactionId("tx-credit-o2"),
                    ),
                    CreditFlowFormalFactory(catalog) { transactionId ->
                        if (transactionId.value == "tx-credit-o2") {
                            CreditRefundOriginalExpense(
                                transactionId,
                                ledgerId,
                                TransactionKind.EXPENSE,
                                "CNY",
                                AccountId("expense"),
                            )
                        } else {
                            null
                        }
                    },
                ),
            )
            assertIs<ImportCandidateDecisionResult.Accepted>(
                confirm(
                    "repayment",
                    ImportRecordKind.CREDIT_REPAYMENT_SOURCE,
                    cases[6].third,
                    profiles["repayment"],
                    com.unifiedledger.application.ImportConfirmDecisionFields.CreditRepaymentFlow(
                        AccountId("asset"),
                        AccountId("liability-cny"),
                    ),
                    CreditFlowFormalFactory(catalog) { null },
                ),
            )

            assertEquals(7L, database.ledgerQueries.countTransactions().executeAsOne())
            assertNormalizedPostings(
                database,
                "tx-expense-o2",
                "set-expense-o2",
                "EXPENSE",
                listOf("expense", "asset"),
                listOf(9900L, -9900L),
            )
            assertNormalizedPostings(
                database,
                "tx-expense-downscale-o2",
                "set-expense-downscale-o2",
                "EXPENSE",
                listOf("expense", "asset"),
                listOf(1234L, -1234L),
            )
            assertNormalizedPostings(
                database,
                "tx-income-o2",
                "set-income-o2",
                "INCOME",
                listOf("asset", "income"),
                listOf(9900L, -9900L),
            )
            assertNormalizedPostings(
                database,
                "tx-transfer-o2",
                "set-transfer-o2",
                "ACCOUNT_TRANSFER",
                listOf("asset", "asset-2"),
                listOf(-9900L, 9900L),
            )
            assertNormalizedPostings(
                database,
                "tx-credit-o2",
                "set-credit-o2",
                "EXPENSE",
                listOf("expense", "liability-cny"),
                listOf(9900L, -9900L),
            )
            assertNormalizedPostings(
                database,
                "tx-refund-o2",
                "set-refund-o2",
                "REFUND_RECEIPT",
                listOf("liability-cny", "expense"),
                listOf(9900L, -9900L),
            )
            assertNormalizedPostings(
                database,
                "tx-repayment-o2",
                "set-repayment-o2",
                "CREDIT_REPAYMENT",
                listOf("asset", "liability-cny"),
                listOf(-9900L, 9900L),
            )
            val refundSnapshot =
                database.ledgerQueries
                    .selectImportDecisionSnapshotByRequest(ledgerId.value, "req-o2-refund-confirm")
                    .executeAsOne()
            assertEquals("tx-credit-o2", refundSnapshot.original_transaction_id)
            cases.forEach { (prefix, _, sourceFacts) ->
                val source = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, "req-o2-$prefix-intake").executeAsOne()
                assertEquals(sourceFacts.amountMinor, source.amount_minor)
                assertEquals(sourceFacts.currencyCode, source.currency_code)
                assertEquals(sourceFacts.currencyPrecision.toLong(), source.currency_precision)
                assertEquals(sourceFacts.occurredAt, source.occurred_at)
                assertEquals(sourceFacts.directionToken, source.direction_token)
                assertEquals(sourceFacts.statusToken, source.status_token)
                assertEquals(sourceFacts.fundingState.name, source.funding_state)
                assertEquals(sourceFacts.fundingRuleId, source.funding_rule_id)
                assertEquals(sourceFacts.fundingRuleVersion.toLong(), source.funding_rule_version)
                assertEquals(
                    ImportContentFingerprint().digest(
                        cases.first { it.first == prefix }.second,
                        sourceFacts,
                        profiles[prefix],
                    ),
                    source.content_hash,
                )
            }
        } finally {
            driver.close()
        }
    }

    private fun confirm(
        requestId: String,
        hash: String,
        liability: AccountId,
        candidateId: String = "candidate-o2",
    ) = ImportCandidateConfirmRequest(
        ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        com.unifiedledger.application.ImportCandidateId(candidateId),
        hash,
        confirmedAt,
        com.unifiedledger.application.ImportConfirmDecisionFields
            .CreditExpenseFlow(CategoryId("category-food"), liability),
    )

    private fun request(
        requestId: String,
        kind: ImportRecordKind,
        facts: ImportSourceFacts,
        profile: ImportPaymentProfile?,
        inputRef: String = "o2-input",
        recordOrdinal: Int = 0,
    ) = ImportIntakeRequest(
        ImportRequestIdentity(ledgerId, ImportRequestId(requestId)),
        inputRef,
        recordOrdinal,
        kind,
        facts,
        ImportCompleteness.VALID_COMPLETE,
        generatedAt,
        profile,
    )

    private fun facts(
        amountMinor: Long,
        precision: Int,
        direction: String = "out",
    ) = ImportSourceFacts(
        amountMinor,
        "CNY",
        precision,
        "2026-08-23T09:00:00+08:00",
        direction,
        "settled",
        ImportFundingState.SETTLED,
        IMPORT_FUNDING_RULE_LEGACY_SETTLED,
        1,
    )

    private fun catalog(): LedgerCatalog =
        assertIs<DomainResult.Success<LedgerCatalog>>(
            LedgerCatalog.create(
                listOf(
                    Account(AccountId("asset"), ledgerId, AccountKind.ASSET, CurrencyUnit("CNY", 2), true, true),
                    Account(AccountId("asset-2"), ledgerId, AccountKind.ASSET, CurrencyUnit("CNY", 2), true, true),
                    Account(AccountId("liability-cny"), ledgerId, AccountKind.LIABILITY, CurrencyUnit("CNY", 2), true, true),
                    Account(AccountId("liability-usd"), ledgerId, AccountKind.LIABILITY, CurrencyUnit("USD", 2), true, true),
                    Account(AccountId("asset-other-ledger"), LedgerId("ledger-o2-other"), AccountKind.ASSET, CurrencyUnit("CNY", 2), true, true),
                    Account(AccountId("expense"), ledgerId, AccountKind.EXPENSE, CurrencyUnit("CNY", 2), false, false),
                    Account(AccountId("income"), ledgerId, AccountKind.INCOME, CurrencyUnit("CNY", 2), false, false),
                ),
                listOf(
                    Category(CategoryId("parent-expense"), ledgerId, null, null, true, CategoryKind.EXPENSE),
                    Category(CategoryId("category-food"), ledgerId, CategoryId("parent-expense"), AccountId("expense"), true, CategoryKind.EXPENSE),
                    Category(CategoryId("parent-income"), ledgerId, null, null, true, CategoryKind.INCOME),
                    Category(CategoryId("category-income"), ledgerId, CategoryId("parent-income"), AccountId("income"), true, CategoryKind.INCOME),
                ),
            ),
        ).value

    private fun commitIds(
        prefix: String,
        postingCount: Int,
    ) = ImportCommitIds(
        ImportConfirmationId("confirmation-$prefix"),
        ImportStatusHistoryId("status-$prefix"),
        ImportFormalIds(
            TransactionId("tx-$prefix"),
            TransactionVersionId("version-$prefix"),
            PostingSetId("set-$prefix"),
            (0 until postingCount).map { PostingId("posting-$prefix-$it") },
        ),
    )

    private data class ConfirmationCounts(
        val importRequests: Long,
        val sourceRecords: Long,
        val evidence: Long,
        val candidates: Long,
        val profiles: Long,
        val statusHistory: Long,
        val decisionSnapshots: Long,
        val confirmations: Long,
        val receipts: Long,
        val transactions: Long,
        val versions: Long,
        val postingSets: Long,
        val postings: Long,
        val mixedGroups: Long,
        val mixedLegs: Long,
    )

    private fun confirmationCounts(
        database: LedgerDatabase,
        driver: JdbcSqliteDriver,
    ): ConfirmationCounts =
        ConfirmationCounts(
            importRequests = database.ledgerQueries.countImportRequests().executeAsOne(),
            sourceRecords = database.ledgerQueries.countImportSourceRecords().executeAsOne(),
            evidence = database.ledgerQueries.countImportEvidence().executeAsOne(),
            candidates = database.ledgerQueries.countImportCandidates().executeAsOne(),
            profiles = database.ledgerQueries.countImportCandidatePaymentProfiles().executeAsOne(),
            statusHistory = database.ledgerQueries.countImportCandidateStatusHistory().executeAsOne(),
            decisionSnapshots = database.ledgerQueries.countImportDecisionSnapshots().executeAsOne(),
            confirmations = database.ledgerQueries.countImportConfirmations().executeAsOne(),
            receipts = database.ledgerQueries.countImportReceipts().executeAsOne(),
            transactions = database.ledgerQueries.countTransactions().executeAsOne(),
            versions = database.ledgerQueries.countVersions().executeAsOne(),
            postingSets = database.ledgerQueries.countPostingSets().executeAsOne(),
            postings = database.ledgerQueries.countPostings().executeAsOne(),
            mixedGroups = scalarLong(driver, "SELECT count(*) FROM mixed_payment_group"),
            mixedLegs = scalarLong(driver, "SELECT count(*) FROM mixed_payment_group_leg"),
        )

    private fun assertConfirmationFailureRollsBackAndRetries(
        point: ImportSpineFailurePoint,
        mixed: Boolean,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val catalog = catalog()
            val prefix = "failure-${point.name.lowercase()}-${if (mixed) "mixed" else "formal"}"
            val candidateId = "candidate-o2-$prefix"
            val intakeRequestId = "req-o2-$prefix-intake"
            val confirmRequestId = "req-o2-$prefix-confirm"
            val sourceFacts = facts(99L, 0)
            val kind = if (mixed) ImportRecordKind.MIXED_PAYMENT_SOURCE else ImportRecordKind.CREDIT_EXPENSE_SOURCE
            val profile: ImportPaymentProfile? =
                if (mixed) {
                    ImportPaymentProfile(ImportPaymentVariant.MIXED_PAYMENT, "asset", "credit")
                } else {
                    ImportPaymentProfile(ImportPaymentVariant.CREDIT_EXPENSE_DIRECT, null, "credit")
                }
            val fields: ImportConfirmDecisionFields =
                if (mixed) {
                    ImportConfirmDecisionFields.MixedPaymentFlow(
                        CategoryId("category-food"),
                        AccountId("asset"),
                        AccountId("liability-cny"),
                        5000L,
                        4900L,
                    )
                } else {
                    ImportConfirmDecisionFields.CreditExpenseFlow(CategoryId("category-food"), AccountId("liability-cny"))
                }
            val factory: ImportCandidateFormalFactory =
                if (mixed) {
                    MixedPaymentFlowFormalFactory(catalog)
                } else {
                    CreditFlowFormalFactory(catalog) { null }
                }
            val intake =
                ExecuteImportIntake(
                    SqlDelightImportSpineStore(database, driver),
                    object : ImportIntakeIdSource {
                        override fun next() =
                            ImportIntakeIds(
                                ImportSourceId("source-o2-$prefix"),
                                com.unifiedledger.application.ImportEvidenceId("evidence-o2-$prefix"),
                                com.unifiedledger.application.ImportCandidateId(candidateId),
                                ImportStatusHistoryId("status-o2-$prefix-1"),
                            )
                    },
                    ImportContentFingerprint(),
                )
            assertIs<ImportIntakeResult.Accepted>(
                intake.execute(request(intakeRequestId, kind, sourceFacts, profile, inputRef = "o2-$prefix")),
            )
            val hash = ImportContentFingerprint().digest(kind, sourceFacts, profile)
            val commitIds = commitIds("$prefix-commit", if (mixed) 3 else 2)
            val confirmRequest =
                ImportCandidateConfirmRequest(
                    ImportRequestIdentity(ledgerId, ImportRequestId(confirmRequestId)),
                    com.unifiedledger.application.ImportCandidateId(candidateId),
                    hash,
                    confirmedAt,
                    fields,
                )
            val baseline = confirmationCounts(database, driver)
            var armed = true
            val failingStore =
                SqlDelightImportSpineStore(
                    database,
                    driver,
                    ImportSpineFailureInjector { actual ->
                        if (armed && actual == point) {
                            armed = false
                            error("injected $point")
                        }
                    },
                )
            assertFailsWith<IllegalStateException> {
                ConfirmImportCandidate(
                    failingStore,
                    ImportIdSource { commitIds },
                    factory,
                    catalog,
                ).execute(confirmRequest)
            }
            assertEquals(baseline, confirmationCounts(database, driver))
            // QUAL-007: the projection authority is rolled back with everything else.
            assertEquals(0L, database.ledgerQueries.countEvidenceProjectionRows().executeAsOne())
            assertEquals(
                "pending_confirmation",
                database.ledgerQueries
                    .selectImportCandidateCurrentStatus(ledgerId.value, candidateId)
                    .executeAsOne()
                    .status,
            )
            val source = database.ledgerQueries.selectImportSourceByOwnerRequest(ledgerId.value, intakeRequestId).executeAsOne()
            assertEquals(sourceFacts.amountMinor, source.amount_minor)
            assertEquals(sourceFacts.currencyPrecision.toLong(), source.currency_precision)
            assertEquals(sourceFacts.occurredAt, source.occurred_at)
            assertEquals(hash, source.content_hash)

            val accepted =
                ConfirmImportCandidate(
                    SqlDelightImportSpineStore(database, driver),
                    ImportIdSource { commitIds },
                    factory,
                    catalog,
                ).execute(confirmRequest)
            assertIs<ImportCandidateDecisionResult.Accepted>(accepted)
            assertEquals(
                baseline.copy(
                    importRequests = baseline.importRequests + 1L,
                    statusHistory = baseline.statusHistory + 1L,
                    decisionSnapshots = baseline.decisionSnapshots + 1L,
                    confirmations = baseline.confirmations + 1L,
                    receipts = baseline.receipts + 1L,
                    transactions = baseline.transactions + 1L,
                    versions = baseline.versions + 1L,
                    postingSets = baseline.postingSets + 1L,
                    postings = baseline.postings + if (mixed) 3L else 2L,
                    mixedGroups = baseline.mixedGroups + if (mixed) 1L else 0L,
                    mixedLegs = baseline.mixedLegs + if (mixed) 2L else 0L,
                ),
                confirmationCounts(database, driver),
            )
        } finally {
            driver.close()
        }
    }

    private fun assertNormalizedPostings(
        database: LedgerDatabase,
        transactionId: String,
        postingSetId: String,
        kind: String,
        expectedAccounts: List<String>,
        expectedAmounts: List<Long>,
    ) {
        val transaction =
            database.ledgerQueries
                .selectRg12FormalTransactions(ledgerId.value)
                .executeAsList()
                .single { it.transaction_id == transactionId }
        assertEquals(kind, transaction.kind)
        val rows = database.ledgerQueries.selectRg12FormalPostings(ledgerId.value, postingSetId).executeAsList()
        assertEquals(expectedAccounts, rows.map { it.account_id })
        assertEquals(expectedAmounts, rows.map { it.amount_minor })
        assertEquals(expectedAmounts.map { "CNY" }, rows.map { it.currency_code })
        assertEquals(expectedAmounts.map { 2L }, rows.map { it.currency_precision })
    }

    private fun scalarLong(
        driver: JdbcSqliteDriver,
        sql: String,
    ): Long =
        driver
            .executeQuery(null, sql, { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult
                    .Value(cursor.getLong(0)!!)
            }, 0)
            .value

    private fun scalarLongs(
        driver: JdbcSqliteDriver,
        sql: String,
    ): List<Long> =
        driver
            .executeQuery(null, sql, { cursor ->
                val values = mutableListOf<Long>()
                while (cursor.next().value) values += cursor.getLong(0)!!
                app.cash.sqldelight.db.QueryResult
                    .Value(values)
            }, 0)
            .value
}
