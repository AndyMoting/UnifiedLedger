package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.Rg06CandidateConfirmationCommitIds
import com.unifiedledger.application.Rg06CandidateId
import com.unifiedledger.application.Rg06CandidateStatusId
import com.unifiedledger.application.Rg06ConfirmationId
import com.unifiedledger.application.Rg06EvidenceId
import com.unifiedledger.application.Rg06EvidenceLinkId
import com.unifiedledger.application.Rg06ExecutionResult
import com.unifiedledger.application.Rg06FieldPath
import com.unifiedledger.application.Rg06IngestCommitIds
import com.unifiedledger.application.Rg06LinkStagedPaymentEvidenceInput
import com.unifiedledger.application.Rg06ManualBankObservation
import com.unifiedledger.application.Rg06MergeStagedPaymentMirrorEvidenceInput
import com.unifiedledger.application.Rg06Operation
import com.unifiedledger.application.Rg06ReconciliationId
import com.unifiedledger.application.Rg06RejectionReason
import com.unifiedledger.application.Rg06ReturnedId
import com.unifiedledger.application.Rg06SourceId
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.StagedPaymentLifecycleId
import com.unifiedledger.domain.StagedPaymentRelationId
import com.unifiedledger.domain.StagedPaymentRole
import com.unifiedledger.domain.TransactionId
import java.nio.file.Files
import java.sql.SQLException
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightRg06LifecycleSnapshotTest : Rg06StoreTestSupport() {
    @Test
    fun createPersistsNormalizedStateAndReplaysOriginalOrderedIds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            val operation = createOperation()

            val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
            assertEquals(
                listOf(
                    Rg06ReturnedId.Relation(operation.ids.relationId),
                    Rg06ReturnedId.Lifecycle(operation.ids.lifecycleId),
                ),
                accepted.returnedIds,
            )
            assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(operation))
            assertIs<Rg06ExecutionResult.RequestIdentityConflict>(
                store.commit(operation.copy(input = operation.input.copy(totalAmount = Money.ofMinor(30_001, CNY)))),
            )
            assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06Relations().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06Lifecycles().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06History().executeAsOne())
            assertEquals(listOf("RELATION", "LIFECYCLE"), database.ledgerQueries.selectRg06ReceiptKinds("ledger-a", "request-create").executeAsList())
        } finally {
            driver.close()
        }
    }

    @Test
    fun manualEightActionLifecyclePersistsFormalGraphAndExactEvidence() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val observation = manualObservation()
            val store =
                SqlDelightRg06Store(database, driver, catalog(), "+08:00") { source, evidence ->
                    observation.takeIf { source == Rg06SourceId("manual-source") && evidence == Rg06EvidenceId("manual-evidence") }
                }

            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            val deposit = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            val final = record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))
            assertRetryAndConflict(store, deposit, deposit.copy(input = deposit.input.copy(paymentAmount = Money.ofMinor(8_001, CNY))))
            val fulfillment = fulfill()
            assertRetryAndConflict(store, fulfillment, fulfillment.copy(input = fulfillment.input.copy(occurredAt = Instant.parse("2026-04-29T10:00:01+08:00"))))
            assertRetryAndConflict(store, final, final.copy(input = final.input.copy(actualPaymentAt = Instant.parse("2026-05-03T16:31:00+08:00"))))
            val completion = complete()
            assertRetryAndConflict(store, completion, completion.copy(input = completion.input.copy(confirmed = false)))
            val link =
                Rg06Operation.LinkStagedPaymentEvidence(
                    LedgerId("ledger-a"),
                    Rg06LinkStagedPaymentEvidenceInput(Rg06SourceId("manual-source"), Rg06EvidenceId("manual-evidence"), deposit.ids.paymentIds.paymentId, deposit.ids.paymentIds.expenseIds.paymentPostingId),
                    Rg06EvidenceLinkId("manual-link"),
                )
            assertRetryAndConflict(store, link, link.copy(input = link.input.copy(postingId = PostingId("other"))))
            assertEquals(2L, database.ledgerQueries.countRg06Installments().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countPostings().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
            assertEquals(
                "MATCHED",
                database.ledgerQueries
                    .selectRg06ReconciliationForPosting("ledger-a", deposit.ids.paymentIds.expenseIds.paymentPostingId.value)
                    .executeAsOne()
                    .status,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun manualEvidenceUsesOneImmutableObservationForValidationAndPersistence() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            var calls = 0
            val store =
                SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ ->
                    calls += 1
                    if (calls == 1) manualObservation() else Rg06ManualBankObservation(Money.ofMinor(-7_999, CNY), manualObservation().observedAt)
                }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            val deposit = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(deposit))
            val link =
                Rg06Operation.LinkStagedPaymentEvidence(
                    LedgerId("ledger-a"),
                    Rg06LinkStagedPaymentEvidenceInput(Rg06SourceId("manual-source"), Rg06EvidenceId("manual-evidence"), deposit.ids.paymentIds.paymentId, deposit.ids.paymentIds.expenseIds.paymentPostingId),
                    Rg06EvidenceLinkId("manual-link"),
                )

            assertIs<Rg06ExecutionResult.Accepted>(store.commit(link))

            assertEquals(1, calls)
            assertEquals(
                -8_000L,
                database.ledgerQueries
                    .selectRg06Source("manual-source")
                    .executeAsOne()
                    .amount_minor,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun sequentialSecondManualLinkReturnsTypedRejectionAndIdentityCanBeReused() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store =
                SqlDelightRg06Store(database, driver, catalog(), "+08:00") { sourceId, _ ->
                    if (sourceId.value == "manual-source-second") {
                        Rg06ManualBankObservation(Money.ofMinor(-22_000, CNY), manualObservation().observedAt)
                    } else {
                        manualObservation()
                    }
                }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            val deposit = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
            val final = record("final", StagedPaymentRole.FINAL, 22_000, Instant.parse("2026-05-03T16:30:00+08:00"))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(deposit))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(final))
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(linkDepositEvidence()))
            val duplicate =
                linkDepositEvidence().copy(
                    input =
                        linkDepositEvidence().input.copy(
                            sourceId = Rg06SourceId("manual-source-second"),
                            evidenceId = Rg06EvidenceId("manual-evidence-second"),
                        ),
                    evidenceLinkId = Rg06EvidenceLinkId("manual-link-second"),
                )
            val before = storeSnapshot(database)

            assertEquals(
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.EVIDENCE_ALREADY_BOUND,
                    Rg06FieldPath.INPUT_POSTING_ID,
                ),
                store.commit(duplicate),
            )
            assertEquals(before, storeSnapshot(database))

            val corrected =
                duplicate.copy(
                    input =
                        duplicate.input.copy(
                            paymentId = final.ids.paymentIds.paymentId,
                            postingId = final.ids.paymentIds.expenseIds.paymentPostingId,
                        ),
                )
            val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(corrected))
            assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(corrected))
            assertEquals(2L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
            assertEquals(4L, database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun unrelatedFailureAfterDuplicateManualLinkClaimEscapes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> manualObservation() }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            assertIs<Rg06ExecutionResult.Accepted>(
                store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))),
            )
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(linkDepositEvidence()))
            val duplicate =
                linkDepositEvidence().copy(
                    input =
                        linkDepositEvidence().input.copy(
                            sourceId = Rg06SourceId("manual-source-failing"),
                            evidenceId = Rg06EvidenceId("manual-evidence-failing"),
                        ),
                    evidenceLinkId = Rg06EvidenceLinkId("manual-link-failing"),
                )
            val failingStore =
                SqlDelightRg06Store(
                    database,
                    driver,
                    catalog(),
                    "+08:00",
                    Rg06ManualObservationSource { _, _ -> manualObservation() },
                    Rg06FailureInjector { point ->
                        if (point == Rg06FailurePoint.AFTER_CLAIM) error("injected-link-failure")
                    },
                )
            val before = storeSnapshot(database)

            val thrown = assertFails { failingStore.commit(duplicate) }

            assertEquals("injected-link-failure", thrown.message)
            assertEquals(before, storeSnapshot(database))
        } finally {
            driver.close()
        }
    }

    @Test
    fun importConfirmAndMirrorUseStoredFactsAndMatchedReconciliation() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            store.commit(createOperation())
            val ingest = ingest()
            assertRetryAndConflict(store, ingest, ingest.copy(input = ingest.input.copy(amount = Money.ofMinor(-8_001, CNY))))
            assertEquals(0L, database.ledgerQueries.countTransactions().executeAsOne())
            val confirmation = confirmCandidate()
            assertRetryAndConflict(store, confirmation, confirmation.copy(input = confirmation.input.copy(categoryId = CategoryId("other"))))
            assertEquals(
                "MATCHED",
                database.ledgerQueries
                    .selectRg06ReconciliationForPosting("ledger-a", confirmation.ids.paymentIds.expenseIds.paymentPostingId.value)
                    .executeAsOne()
                    .status,
            )
            val mirror =
                Rg06Operation.MergeStagedPaymentMirrorEvidence(
                    LedgerId("ledger-a"),
                    Rg06MergeStagedPaymentMirrorEvidenceInput(Rg06SourceId("mirror-source"), Rg06EvidenceId("mirror-evidence"), confirmation.ids.paymentIds.paymentId, confirmation.ids.paymentIds.expenseIds.paymentPostingId, Money.ofMinor(8_000, CNY), Instant.parse("2026-04-28T10:00:00+08:00"), "2026-04-28T10:00:00+08:00"),
                )
            assertRetryAndConflict(store, mirror, mirror.copy(input = mirror.input.copy(amount = Money.ofMinor(8_001, CNY))))
            assertEquals(2L, database.ledgerQueries.countRg06Sources().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg06Evidence().executeAsOne())
            assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
            assertEquals(2L, database.ledgerQueries.countRg06CandidateStatuses().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun rejectionAndInjectedFailureReserveNothing() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val failed = SqlDelightRg06Store(database, driver, catalog(), "+08:00", Rg06ManualObservationSource { _, _ -> null }, Rg06FailureInjector { if (it == Rg06FailurePoint.AFTER_CLAIM) error("injected") })
            assertFails { failed.commit(createOperation()) }
            assertEquals(0L, database.ledgerQueries.countRg06Operations().executeAsOne())
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            val invalid = createOperation().copy(input = createOperation().input.copy(totalAmount = Money.ofMinor(0, CNY)))
            assertEquals(Rg06ExecutionResult.Rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT), store.commit(invalid))
            assertEquals(0L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
        } finally {
            driver.close()
        }
    }

    @Test
    fun injectedFailureAfterFormalRollsBackPersistentStateAndReopensForIdempotentRetry() {
        val path = Files.createTempFile("rg06-failure-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val deposit =
            record(
                "deposit",
                StagedPaymentRole.DEPOSIT,
                8_000,
                Instant.parse("2026-04-28T10:00:00+08:00"),
            )
        try {
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
                val before = storeSnapshot(database)
                val failingStore =
                    SqlDelightRg06Store(
                        database,
                        driver,
                        catalog(),
                        "+08:00",
                        Rg06ManualObservationSource { _, _ -> null },
                        Rg06FailureInjector { point ->
                            if (point == Rg06FailurePoint.AFTER_FORMAL) {
                                throw SQLException("injected-after-formal")
                            }
                        },
                    )

                assertFailsWith<SQLException> { failingStore.commit(deposit) }
                assertEquals(before, storeSnapshot(database))
                assertEquals(
                    null,
                    database.ledgerQueries
                        .selectRg06Operation("ledger-a", deposit.identity.value)
                        .executeAsOneOrNull(),
                )
            }

            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(deposit))
                assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(deposit))
                assertEquals(1L, database.ledgerQueries.countRg06Installments().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun explicitCandidateConfirmationTimeSurvivesPersistentReopenAndReplay() {
        val path = Files.createTempFile("rg06-confirmed-at-reopen-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val confirmedAt = Instant.parse("2026-04-28T10:05:00Z")
        val operation =
            confirmCandidate().copy(
                input = confirmCandidate().input.copy(confirmedAt = confirmedAt),
            )
        try {
            val returnedIds =
                JdbcSqliteDriver(url, properties).use { driver ->
                    LedgerDatabase.Schema.create(driver)
                    val database = LedgerDatabase(driver)
                    val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                    assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
                    assertIs<Rg06ExecutionResult.Accepted>(store.commit(ingest()))
                    val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
                    assertEquals(
                        confirmedAt.toString(),
                        database.ledgerQueries
                            .selectRg06ConfirmationForIdentity("ledger-a", operation.identity.value)
                            .executeAsOne()
                            .confirmed_at,
                    )
                    accepted.returnedIds
                }

            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                assertEquals(Rg06ExecutionResult.NoChange(returnedIds), store.commit(operation))
                assertEquals(
                    confirmedAt.toString(),
                    database.ledgerQueries
                        .selectRg06ConfirmationForIdentity("ledger-a", operation.identity.value)
                        .executeAsOne()
                        .confirmed_at,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun semanticFailurePrecedesSharedFormalCollisionAndCollisionRollsBackClaim() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            store.commit(createOperation())
            driver.execute(null, "INSERT INTO posting_set VALUES ('shared-set','ledger-a')", 0)
            driver.execute(null, "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('shared-transaction','ledger-a','EXPENSE')", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('shared-version','shared-transaction','ledger-a',1,'shared-set','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',NULL)", 0)
            driver.execute(null, "INSERT INTO ledger_transaction_current_version VALUES ('shared-transaction','ledger-a','shared-version')", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('shared-expense','shared-set','ledger-a',0,'expense-service-account',100,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('shared-asset','shared-set','ledger-a',1,'asset-bank',-100,'CNY',2)", 0)
            val collision =
                record("collision", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).let { operation ->
                    operation.copy(
                        ids =
                            operation.ids.copy(
                                paymentIds =
                                    operation.ids.paymentIds.copy(
                                        expenseIds =
                                            operation.ids.paymentIds.expenseIds
                                                .copy(transactionId = TransactionId("shared-transaction")),
                                    ),
                            ),
                    )
                }
            val invalid = collision.copy(input = collision.input.copy(paymentAmount = Money.ofMinor(0, CNY)))
            assertEquals(Rg06ExecutionResult.Rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT), store.commit(invalid))
            assertEquals(Rg06ExecutionResult.Rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY), store.commit(collision))
            assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg06Installments().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun everyLegacyConfirmationOwnerParticipatesInGeneratedIdentityCollisionChecks() {
        listOf("expense", "note-update", "income").forEach { owner ->
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                LedgerDatabase.Schema.create(driver)
                val database = LedgerDatabase(driver)
                seedLegacyConfirmation(driver, owner, "confirmation-deposit")
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))

                assertEquals(
                    Rg06ExecutionResult.Rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY),
                    store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))),
                    owner,
                )
                assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne(), owner)
                assertEquals(0L, database.ledgerQueries.countRg06Installments().executeAsOne(), owner)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun candidateConfirmationRejectsForeignLedgerOwnerAtCandidatePath() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            val foreignIngest =
                ingest().copy(
                    ledgerId = LedgerId("ledger-b"),
                    ids = Rg06IngestCommitIds(Rg06CandidateId("foreign-candidate"), Rg06CandidateStatusId("foreign-status-pending")),
                )
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(foreignIngest))
            val confirmation =
                confirmCandidate().copy(
                    input = confirmCandidate().input.copy(candidateId = Rg06CandidateId("foreign-candidate")),
                )

            assertEquals(
                Rg06ExecutionResult.Rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_CANDIDATE_ID),
                store.commit(confirmation),
            )
            assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
            assertEquals(0L, database.ledgerQueries.countRg06Installments().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun normalizedSnapshotRehydratesAfterCatalogDriftAndReplaysLifecycleIds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }.apply {
                commit(createOperation())
                commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")))
            }
            val driftedCatalog =
                assertIs<DomainResult.Success<LedgerCatalog>>(
                    LedgerCatalog.create(
                        listOf(Account(AccountId("asset-bank"), LedgerId("ledger-a"), AccountKind.ASSET, CNY, true, true)),
                        emptyList(),
                    ),
                ).value
            val store = SqlDelightRg06Store(database, driver, driftedCatalog, "+08:00") { _, _ -> null }
            val operation = fulfill()
            val accepted = assertIs<Rg06ExecutionResult.Accepted>(store.commit(operation))
            assertEquals(listOf(Rg06ReturnedId.Lifecycle(StagedPaymentLifecycleId("lifecycle"))), accepted.returnedIds)
            assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(operation))
        } finally {
            driver.close()
        }
    }

    @Test
    fun invalidOwnedSnapshotRaisesPersistenceIntegrityFailureAndRollsBackClaim() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
            assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
            assertIs<Rg06ExecutionResult.Accepted>(
                store.commit(
                    record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")),
                ),
            )

            driver.execute(null, "INSERT INTO posting_set VALUES ('posting-set-corrupt','ledger-a')", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('posting-corrupt-expense','posting-set-corrupt','ledger-a',0,'expense-service-account',8000,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO posting VALUES ('posting-corrupt-asset','posting-set-corrupt','ledger-a',1,'asset-bank',-8000,'CNY',2)", 0)
            driver.execute(null, "INSERT INTO transaction_version(version_id, transaction_id, ledger_id, version_number, posting_set_id, occurred_at, statistics_at, effective_at, note) VALUES ('version-corrupt','transaction-deposit','ledger-a',2,'posting-set-corrupt','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z','2026-04-28T02:00:00Z',NULL)", 0)
            driver.execute(null, "DROP TRIGGER rg06_guard_current_version_update", 0)
            driver.execute(null, "UPDATE ledger_transaction_current_version SET current_version_id = 'version-corrupt' WHERE transaction_id = 'transaction-deposit'", 0)
            val before = storeSnapshot(database)

            val failure = assertFailsWith<Rg06PersistenceIntegrityException> { store.commit(fulfill()) }

            assertTrue(failure.message.orEmpty().contains("invalid persisted RG06 snapshot"))
            assertEquals(before, storeSnapshot(database))
            assertEquals(
                null,
                database.ledgerQueries.selectRg06Operation("ledger-a", fulfill().identity.value).executeAsOneOrNull(),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun malformedOwnedSnapshotIsNotReportedAsMissingOrCrossLedgerRelation() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }

            assertEquals(
                Rg06ExecutionResult.Rejected(Rg06RejectionReason.RELATION_NOT_FOUND, Rg06FieldPath.INPUT_RELATION_ID),
                store.commit(record("missing", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).copy(input = record("missing", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(relationId = StagedPaymentRelationId("relation-missing")))),
            )
            driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-b','relation-cross','staged_payment','{}')", 0)
            assertEquals(
                Rg06ExecutionResult.Rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_RELATION_ID),
                store.commit(record("cross", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).copy(input = record("cross", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(relationId = StagedPaymentRelationId("relation-cross")))),
            )

            driver.execute(null, "INSERT INTO rg06_relation VALUES ('ledger-a','relation-malformed','staged_payment','{}')", 0)
            val malformed =
                record("malformed", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).copy(
                    input =
                        record("malformed", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00")).input.copy(
                            relationId = StagedPaymentRelationId("relation-malformed"),
                        ),
                )
            val before = storeSnapshot(database)

            val failure = assertFailsWith<Rg06PersistenceIntegrityException> { store.commit(malformed) }

            assertTrue(failure.message.orEmpty().contains("invalid persisted RG06 snapshot for relation-malformed"))
            assertTrue(failure.message.orEmpty().contains("missing lifecycle"))
            assertEquals(before, storeSnapshot(database))
            assertEquals(
                null,
                database.ledgerQueries.selectRg06Operation("ledger-a", malformed.identity.value).executeAsOneOrNull(),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun concurrentIdenticalCreateCommitsOnceAndReplaysOneReceipt() {
        val path = Files.createTempFile("rg06-concurrent-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        try {
            JdbcSqliteDriver(url, properties).use { LedgerDatabase.Schema.create(it) }
            val results = mutableListOf<Rg06ExecutionResult>()
            val workers =
                List(2) {
                    thread {
                        JdbcSqliteDriver(url, properties).use { driver ->
                            val result = SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> null }.commit(createOperation())
                            synchronized(results) { results += result }
                        }
                    }
                }
            workers.forEach { it.join() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg06ExecutionResult.NoChange })
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Relations().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentChangedInputWithSameIdentityCommitsOneWinnerAndOneConflict() {
        val path = Files.createTempFile("rg06-concurrent-changed-input-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val original = createOperation()
        val changed = original.copy(input = original.input.copy(totalAmount = Money.ofMinor(30_001, CNY)))
        val operations = listOf(original, changed)
        try {
            JdbcSqliteDriver(url, properties).use { LedgerDatabase.Schema.create(it) }
            val results = concurrentCommits(url, properties, operations).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg06ExecutionResult.RequestIdentityConflict })
            val winner = operations[results.indexOfFirst { it is Rg06ExecutionResult.Accepted }]
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                val store = SqlDelightRg06Store(database, driver, catalog(), "+08:00") { _, _ -> null }
                val accepted = results.single { it is Rg06ExecutionResult.Accepted } as Rg06ExecutionResult.Accepted
                assertEquals(Rg06ExecutionResult.NoChange(accepted.returnedIds), store.commit(winner))
                assertEquals(1L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06OperationReceipts().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Relations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Lifecycles().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06History().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctDepositsSerializeLifecycleAndRollBackLoserClaim() {
        val path = Files.createTempFile("rg06-concurrent-lifecycle-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val first = record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))
        val second = record("deposit-b", StagedPaymentRole.DEPOSIT, 9_000, Instant.parse("2026-04-28T10:00:01+08:00"))
        val operations = listOf(first, second)
        try {
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.create(driver)
                assertIs<Rg06ExecutionResult.Accepted>(
                    SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> null }
                        .commit(createOperation()),
                )
            }
            val results = concurrentCommits(url, properties, operations).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it == Rg06ExecutionResult.Rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
                },
            )
            val winner = operations[results.indexOfFirst { it is Rg06ExecutionResult.Accepted }]
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                val lifecycle = database.ledgerQueries.selectRg06AggregateLifecycle("ledger-a", "relation").executeAsOne()
                assertEquals(winner.input.paymentAmount.minorUnits, lifecycle.paid_minor)
                assertEquals(30_000L - winner.input.paymentAmount.minorUnits, lifecycle.due_minor)
                assertEquals(2L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Installments().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06History().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Reconciliations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctCandidateConfirmationsCommitOneMatchedReconciliation() {
        val path = Files.createTempFile("rg06-concurrent-candidate-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val first = confirmCandidate()
        val second =
            first.copy(
                input = first.input.copy(requestId = RequestId("request-confirm-b")),
                ids =
                    Rg06CandidateConfirmationCommitIds(
                        Rg06ConfirmationId("confirmation-import-b"),
                        paymentIds("import-b"),
                        Rg06EvidenceLinkId("import-link-b"),
                        Rg06CandidateStatusId("status-confirmed-b"),
                        Rg06ReconciliationId("reconciliation-import-b"),
                    ),
            )
        val operations = listOf(first, second)
        try {
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val store = SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> null }
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(ingest()))
            }
            val results = concurrentCommits(url, properties, operations).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it == Rg06ExecutionResult.Rejected(Rg06RejectionReason.CANDIDATE_NOT_PENDING, Rg06FieldPath.INPUT_CANDIDATE_ID)
                },
            )
            val winner = operations[results.indexOfFirst { it is Rg06ExecutionResult.Accepted }]
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(3L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Installments().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06CandidateStatuses().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Reconciliations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne())
                assertEquals(
                    "MATCHED",
                    database.ledgerQueries
                        .selectRg06ReconciliationForPosting(
                            "ledger-a",
                            winner.ids.paymentIds.expenseIds.paymentPostingId.value,
                        ).executeAsOne()
                        .status,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun concurrentDistinctManualLinksTransitionReconciliationOnceAndRollBackConstraintLoser() {
        val path = Files.createTempFile("rg06-concurrent-reconciliation-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        val properties = Properties().apply { setProperty("busy_timeout", "5000") }
        val first =
            linkDepositEvidence().copy(
                input = linkDepositEvidence().input.copy(sourceId = Rg06SourceId("manual-source-a"), evidenceId = Rg06EvidenceId("manual-evidence-a")),
                evidenceLinkId = Rg06EvidenceLinkId("manual-link-a"),
            )
        val second =
            linkDepositEvidence().copy(
                input = linkDepositEvidence().input.copy(sourceId = Rg06SourceId("manual-source-b"), evidenceId = Rg06EvidenceId("manual-evidence-b")),
                evidenceLinkId = Rg06EvidenceLinkId("manual-link-b"),
            )
        try {
            JdbcSqliteDriver(url, properties).use { driver ->
                LedgerDatabase.Schema.create(driver)
                val store = SqlDelightRg06Store(LedgerDatabase(driver), driver, catalog(), "+08:00") { _, _ -> manualObservation() }
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(createOperation()))
                assertIs<Rg06ExecutionResult.Accepted>(store.commit(record("deposit", StagedPaymentRole.DEPOSIT, 8_000, Instant.parse("2026-04-28T10:00:00+08:00"))))
            }
            val results = concurrentCommits(url, properties, listOf(first, second)).map { it.getOrThrow() }
            assertEquals(1, results.count { it is Rg06ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it ==
                        Rg06ExecutionResult.Rejected(
                            Rg06RejectionReason.EVIDENCE_ALREADY_BOUND,
                            Rg06FieldPath.INPUT_POSTING_ID,
                        )
                },
            )
            JdbcSqliteDriver(url, properties).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(3L, database.ledgerQueries.countRg06Operations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Sources().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Evidence().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06EvidenceLinks().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg06Reconciliations().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countRg06ReconciliationHistory().executeAsOne())
                assertEquals(
                    "MATCHED",
                    database.ledgerQueries
                        .selectRg06ReconciliationForPosting("ledger-a", "posting-deposit-asset")
                        .executeAsOne()
                        .status,
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
