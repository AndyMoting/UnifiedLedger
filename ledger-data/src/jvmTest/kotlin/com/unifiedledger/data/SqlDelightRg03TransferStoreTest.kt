package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.*
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.*
import java.nio.file.Files
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightRg03TransferStoreTest {
    @Test
    fun `replay conflict and rejection never consume another identity`() {
        val identities = CountingIdentitySource(testIdentitySource())
        Harness(identitySource = identities).use { harness ->
            val manual = manualSnapshot()
            assertIs<Rg03ExecutionResult.Rejected>(
                harness.store.commit(Rg03PreparedOperation.CreateManual(manual.copy(destinationAccountId = manual.sourceAccountId))),
            )
            assertEquals(0, identities.transfers)

            assertIs<Rg03ExecutionResult.Accepted>(harness.store.commit(Rg03PreparedOperation.CreateManual(manual)))
            assertEquals(1, identities.transfers)
            assertIs<Rg03ExecutionResult.NoChange>(harness.store.commit(Rg03PreparedOperation.CreateManual(manual)))
            assertIs<Rg03ExecutionResult.RequestIdentityConflict>(
                harness.store.commit(
                    Rg03PreparedOperation.CreateManual(
                        manual.copy(destinationCredit = money(5_899), fee = money(101)),
                    ),
                ),
            )
            assertEquals(1, identities.transfers)

            assertIs<Rg03ExecutionResult.Rejected>(
                harness.store.commit(Rg03PreparedOperation.StoreSource(completeSource().copy(sourceAccountId = AccountId("unknown")))),
            )
            assertEquals(0, identities.sources)
            assertIs<Rg03ExecutionResult.Rejected>(
                harness.store.commit(Rg03PreparedOperation.MergeMirror(mirrorSnapshot(), null)),
            )
            assertEquals(0, identities.mirrors)
        }
    }
    @Test
    fun `manual transfer claims typed snapshot atomically and replays equivalent or conflicts`() =
        Harness().use { harness ->
            val snapshot = manualSnapshot()
            val first = harness.store.commit(Rg03PreparedOperation.CreateManual(snapshot))

            assertEquals(
                Rg03ExecutionResult.Accepted(
                    listOf(
                        ReturnedId(ReturnedIdKind.CONFIRMATION, "confirmation-manual"),
                        ReturnedId(ReturnedIdKind.TRANSACTION, "tx-transfer-rg03-manual"),
                    ),
                ),
                first,
            )
            assertEquals(1L, harness.queries.countRg03OperationRequests().executeAsOne())
            assertEquals(1L, harness.queries.countRg03OperationReceipts().executeAsOne())
            assertEquals(1L, harness.queries.countTransactions().executeAsOne())
            assertEquals(3L, harness.queries.countPostings().executeAsOne())
            assertEquals(2L, harness.queries.countRg03PostingReconciliations().executeAsOne())
            assertEquals(
                listOf(
                    "posting-destination-rg03-manual|TRANSFER_PRINCIPAL_IN|null|1",
                    "posting-fee-rg03-manual|TRANSFER_FEE|expense-category-transfer-fee|0",
                    "posting-source-rg03-manual|TRANSFER_PRINCIPAL_OUT|null|1",
                ),
                harness.queries.selectRg03TransferPostingSemantics().executeAsList().map {
                    "${it.posting_id}|${it.role}|${it.category_id}|${it.reconciliation_eligible}"
                },
            )
            assertIs<Rg03ExecutionResult.NoChange>(
                harness.store.commit(Rg03PreparedOperation.CreateManual(snapshot)),
            )
            listOf(
                snapshot.copy(destinationCredit = money(5_899), fee = money(101)),
                snapshot.copy(destinationAccountId = snapshot.sourceAccountId),
                snapshot.copy(sourceDebit = money(6_001)),
                snapshot.copy(feeCategoryId = CategoryId("expense-category-financial")),
                snapshot.copy(destinationAccountId = AccountId("liability-credit-c")),
            ).forEach { conflict ->
                assertIs<Rg03ExecutionResult.RequestIdentityConflict>(
                    harness.store.commit(Rg03PreparedOperation.CreateManual(conflict)),
                )
            }
            assertEquals(1L, harness.queries.countTransactions().executeAsOne())
        }

    @Test
    fun `complete source persists immutable facts and equivalent replay while every changed fact conflicts`() =
        Harness().use { harness ->
            val snapshot = completeSource()
            val accepted = harness.store.commit(Rg03PreparedOperation.StoreSource(snapshot))

            assertEquals(
                Rg03ExecutionResult.Accepted(
                    listOf(
                        ReturnedId(ReturnedIdKind.SOURCE, snapshot.sourceId.value),
                        ReturnedId(ReturnedIdKind.EVIDENCE, snapshot.evidenceId.value),
                        ReturnedId(ReturnedIdKind.CANDIDATE, "candidate-transfer-rg03"),
                    ),
                ),
                accepted,
            )
            assertEquals(Rg03DataCounts.sourceIntake(completeDetails = 1), harness.counts())
            assertEquals(persistedCandidate(), harness.store.load(LEDGER, CandidateId("candidate-transfer-rg03")))
            assertIs<Rg03ExecutionResult.NoChange>(
                harness.store.commit(Rg03PreparedOperation.StoreSource(snapshot)),
            )

            val conflicts = listOf(
                snapshot.copy(sourceId = SourceRecordId("source-other")),
                snapshot.copy(evidenceId = EvidenceId("evidence-other")),
                snapshot.copy(observedAt = Instant.parse("2026-01-21T03:00:01Z")),
                snapshot.copy(sourceAccountId = AccountId("asset-wallet-b")),
                snapshot.copy(destinationAccountId = AccountId("asset-bank-a")),
                snapshot.copy(sourceDebit = money(6_001)),
                snapshot.copy(destinationCredit = money(5_901)),
                snapshot.copy(fee = money(99)),
                snapshot.copy(feeCategoryId = CategoryId("expense-category-financial")),
                snapshot.copy(completeness = SourceCompleteness.MISSING_DESTINATION),
            )
            conflicts.forEach { conflict ->
                assertIs<Rg03ExecutionResult.RequestIdentityConflict>(
                    harness.store.commit(Rg03PreparedOperation.StoreSource(conflict)),
                )
            }
            assertEquals(Rg03DataCounts.sourceIntake(completeDetails = 1), harness.counts())
        }

    @Test
    fun `brand new invalid manual and source release claims without allocating identity`() {
        val identities = CountingIdentitySource(testIdentitySource())
        Harness(identitySource = identities).use { harness ->
            val manual = manualSnapshot()
            assertIs<Rg03ExecutionResult.Rejected>(
                harness.store.commit(
                    Rg03PreparedOperation.CreateManual(
                        manual.copy(destinationAccountId = manual.sourceAccountId),
                    ),
                ),
            )
            assertEquals(Rg03DataCounts.EMPTY, harness.counts())
            assertEquals(0, identities.transfers)

            assertIs<Rg03ExecutionResult.Rejected>(
                harness.store.commit(
                    Rg03PreparedOperation.StoreSource(
                        completeSource().copy(sourceAccountId = AccountId("unknown-account")),
                    ),
                ),
            )
            assertEquals(Rg03DataCounts.EMPTY, harness.counts())
            assertEquals(0, identities.sources)
        }
    }

    @Test
    fun `incomplete source omits all destination detail and recovers explicit null candidate facts`() {
        Harness().use { harness ->
            val snapshot = incompleteSource()

            assertIs<Rg03ExecutionResult.Accepted>(
                harness.store.commit(Rg03PreparedOperation.StoreSource(snapshot)),
            )

            assertEquals(Rg03DataCounts.sourceIntake(completeDetails = 0), harness.counts())
            assertEquals(
                persistedCandidate(
                    candidateId = "candidate-transfer-rg03-unknown-debit",
                    source = snapshot,
                    destinationAccountId = null,
                    destinationCredit = null,
                    fee = null,
                ),
                harness.store.load(LEDGER, CandidateId("candidate-transfer-rg03-unknown-debit")),
            )
            assertIs<Rg03ExecutionResult.NoChange>(
                harness.store.commit(Rg03PreparedOperation.StoreSource(snapshot)),
            )
        }
    }

    @Test
    fun `invalid complete and incomplete sources reject before identity allocation or persistence`() {
        Harness().use { harness ->
            val complete = completeSource()
            val completeCases = listOf(
                complete.copy(sourceAccountId = AccountId("unknown-account")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.KNOWN_ACCOUNT_REQUIRED, "source_account_id"),
                complete.copy(destinationAccountId = AccountId("asset-external-x")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.OWN_ACCOUNT_REQUIRED, "destination_account_id"),
                complete.copy(sourceAccountId = AccountId("expense-account-transfer-fee")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.REAL_FINANCIAL_ACCOUNT_REQUIRED, "source_account_id"),
                complete.copy(destinationAccountId = AccountId("asset-bank-a")) to
                    Rg03ExecutionResult.Rejected(
                        Rg03ExecutionError.DISTINCT_OWN_REAL_FINANCIAL_ACCOUNTS_REQUIRED,
                        "destination_account_id",
                    ),
                complete.copy(destinationCredit = money(0), sourceDebit = money(100), fee = money(100)) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.MUST_BE_POSITIVE, "destination_credit_amount"),
                complete.copy(fee = money(-1), sourceDebit = money(5_899)) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.FEE_MUST_NOT_BE_NEGATIVE, "fee_amount"),
                complete.copy(fee = money(99)) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.AMOUNTS_MUST_BALANCE, "fee_amount"),
                complete.copy(destinationAccountId = AccountId("asset-usd")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.SAME_CURRENCY_REQUIRED, "destination_currency"),
                complete.copy(feeCategoryId = CategoryId("expense-category-financial")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.INVALID_FEE_CATEGORY, "fee_category_id"),
                complete.copy(destinationAccountId = AccountId("liability-credit-c")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.ASSET_ACCOUNT_REQUIRED, "destination_account_id"),
            )
            completeCases.forEachIndexed { index, (snapshot, expected) ->
                assertEquals(
                    expected,
                    harness.store.commit(
                        Rg03PreparedOperation.StoreSource(
                            snapshot.copy(requestId = RequestId("invalid-complete-$index")),
                        ),
                    ),
                )
                assertEquals(Rg03DataCounts.EMPTY, harness.counts())
            }

            val incomplete = incompleteSource()
            listOf(
                incomplete.copy(sourceAccountId = AccountId("unknown-account")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.KNOWN_ACCOUNT_REQUIRED, "source_account_id"),
                incomplete.copy(sourceDebit = money(0)) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.MUST_BE_POSITIVE, "source_debit_amount"),
                incomplete.copy(sourceAccountId = AccountId("asset-usd")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.SAME_CURRENCY_REQUIRED, "source_currency"),
                incomplete.copy(feeCategoryId = CategoryId("expense-category-financial")) to
                    Rg03ExecutionResult.Rejected(Rg03ExecutionError.INVALID_FEE_CATEGORY, "fee_category_id"),
            ).forEachIndexed { index, (snapshot, expected) ->
                assertEquals(
                    expected,
                    harness.store.commit(
                        Rg03PreparedOperation.StoreSource(
                            snapshot.copy(requestId = RequestId("invalid-incomplete-$index")),
                        ),
                    ),
                )
                assertEquals(Rg03DataCounts.EMPTY, harness.counts())
            }
        }
    }

    @Test
    fun `candidate confirmation reloads immutable SQL facts and appends status with formal rows atomically`() =
        Harness().use { harness ->
            val source = completeSource()
            harness.store.commit(Rg03PreparedOperation.StoreSource(source))
            val recovered = checkNotNull(harness.store.load(LEDGER, CandidateId("candidate-transfer-rg03")))

            assertEquals(
                Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_FOUND),
                harness.store.commit(
                    Rg03PreparedOperation.ConfirmCandidate(
                        RequestId("request-tampered-confirm"),
                        recovered.copy(destinationCredit = money(5_899)),
                    ),
                ),
            )
            assertEquals(Rg03DataCounts.sourceIntake(completeDetails = 1), harness.counts())

            val executor = ExecuteRg03Operation(harness.store, harness.store, harness.store)
            val result = executor.execute(
                Rg03Command.ConfirmCandidate(
                    requestId = RequestId("request-rg03-confirm-candidate"),
                    candidateId = recovered.candidateId,
                    confirmed = true,
                    ledgerId = LEDGER,
                ),
            )
            assertIs<Rg03ExecutionResult.Accepted>(result)
            assertEquals(CandidateStatus.CONFIRMED, harness.store.load(LEDGER, recovered.candidateId)?.status)
            assertEquals(2L, harness.queries.countRg03CandidateStatuses().executeAsOne())
            assertEquals(1L, harness.queries.countTransactions().executeAsOne())
            assertEquals(3L, harness.queries.countPostings().executeAsOne())
            assertEquals(
                listOf("MATCHED", "PENDING"),
                harness.queries.selectRg03PostingReconciliations().executeAsList().map { it.status },
            )
            assertEquals(1L, harness.queries.countRg03EvidenceLinks().executeAsOne())
            assertEquals(
                listOf("match-rg03-debit|POSTING|REAL_ACCOUNT_POSTING|posting-source-rg03-imported"),
                harness.queries.selectRg03EvidenceLinkTargets().executeAsList().map {
                    "${it.link_id}|${it.target_kind}|${it.target_role}|${it.posting_id}"
                },
            )

            assertIs<Rg03ExecutionResult.NoChange>(
                harness.store.commit(
                    Rg03PreparedOperation.ConfirmCandidate(
                        RequestId("request-rg03-confirm-candidate"),
                        recovered,
                    ),
                ),
            )
            assertEquals(1L, harness.queries.countTransactions().executeAsOne())
            assertEquals(2L, harness.queries.countRg03CandidateStatuses().executeAsOne())
        }

    @Test
    fun `mirror binding is missing before confirmation then uniquely merges evidence and replays exactly`() {
        Harness().use { harness ->
            assertEquals(
                Rg03MirrorBindingResult.Missing,
                harness.store.resolve(LEDGER, Rg03MirrorScope(CandidateId("candidate-transfer-rg03"))),
            )
            harness.store.commit(Rg03PreparedOperation.StoreSource(completeSource()))
            val executor = ExecuteRg03Operation(harness.store, harness.store, harness.store)
            executor.execute(
                Rg03Command.ConfirmCandidate(
                    RequestId("request-rg03-confirm-candidate"),
                    CandidateId("candidate-transfer-rg03"),
                    true,
                    LEDGER,
                ),
            )

            assertIs<Rg03MirrorBindingResult.Unique>(
                harness.store.resolve(LEDGER, Rg03MirrorScope(CandidateId("candidate-transfer-rg03"))),
            )
            val mirror = mirrorSnapshot()
            assertEquals(
                Rg03ExecutionResult.Accepted(
                    listOf(
                        ReturnedId(ReturnedIdKind.SOURCE, mirror.sourceId.value),
                        ReturnedId(ReturnedIdKind.EVIDENCE, mirror.evidenceId.value),
                        ReturnedId(ReturnedIdKind.EVIDENCE_LINK, "match-rg03-credit-mirror"),
                    ),
                ),
                executor.execute(Rg03Command.ImportMirror(mirror)),
            )
            assertEquals(2L, harness.queries.countRg03SourceRecords().executeAsOne())
            assertEquals(2L, harness.queries.countRg03Evidence().executeAsOne())
            assertEquals(2L, harness.queries.countRg03EvidenceLinks().executeAsOne())
            assertEquals(
                listOf("MATCHED", "MATCHED"),
                harness.queries.selectRg03PostingReconciliations().executeAsList().map { it.status },
            )
            assertEquals(1L, harness.queries.countTransactions().executeAsOne())
            assertEquals(3L, harness.queries.countPostings().executeAsOne())
            assertEquals(
                Rg03MirrorBindingResult.Missing,
                harness.store.resolve(LEDGER, Rg03MirrorScope(CandidateId("candidate-transfer-rg03"))),
            )

            assertIs<Rg03ExecutionResult.NoChange>(
                harness.store.commit(
                    Rg03PreparedOperation.MergeMirror(
                        mirror,
                        null,
                    ),
                ),
            )
            assertIs<Rg03ExecutionResult.RequestIdentityConflict>(
                harness.store.commit(
                    Rg03PreparedOperation.MergeMirror(
                        mirror.copy(credit = money(5_899)),
                        null,
                    ),
                ),
            )
        }
    }

    @Test
    fun `mirror scope binds only its confirmed lifecycle when another destination is pending`() {
        val identities = object : Rg03IdentitySource by testIdentitySource() {
            override fun source(requestId: RequestId) = if (requestId.value == "request-source-two") {
                Rg03SourceCommitIds(CandidateId("candidate-two"), "status-pending-two")
            } else testIdentitySource().source(requestId)

            override fun transfer(requestId: RequestId) = if (requestId.value == "request-confirm-two") {
                transferIds("imported-two", "confirmation-two", "status-confirmed-two")
                    .copy(sourceEvidenceLinkId = "match-two")
            } else testIdentitySource().transfer(requestId)
        }
        Harness(identitySource = identities).use { harness ->
            val firstExecutor = ExecuteRg03Operation(harness.store, harness.store, harness.store)
            harness.store.commit(Rg03PreparedOperation.StoreSource(completeSource()))
            assertIs<Rg03ExecutionResult.Accepted>(
                firstExecutor.execute(Rg03Command.ConfirmCandidate(RequestId("request-rg03-confirm-candidate"), CandidateId("candidate-transfer-rg03"), true, LEDGER)),
            )

            val second = completeSource().copy(
                requestId = RequestId("request-source-two"), sourceId = SourceRecordId("source-two"),
                evidenceId = EvidenceId("evidence-two"), sourceDebit = money(7_000),
                destinationCredit = money(6_900),
            )
            harness.store.commit(Rg03PreparedOperation.StoreSource(second))
            val secondExecutor = ExecuteRg03Operation(harness.store, harness.store, harness.store)
            assertIs<Rg03ExecutionResult.Accepted>(
                secondExecutor.execute(Rg03Command.ConfirmCandidate(RequestId("request-confirm-two"), CandidateId("candidate-two"), true, LEDGER)),
            )

            assertIs<Rg03ExecutionResult.Accepted>(firstExecutor.execute(Rg03Command.ImportMirror(mirrorSnapshot())))
            assertEquals(
                "posting-destination-rg03-imported",
                harness.queries.selectRg03EvidenceLinkTargets().executeAsList()
                    .single { it.link_id == "match-rg03-credit-mirror" }.posting_id,
            )
            val statuses = harness.queries.selectRg03PostingReconciliations().executeAsList().associate { it.posting_id to it.status }
            assertEquals("MATCHED", statuses.getValue("posting-destination-rg03-imported"))
            assertEquals("PENDING", statuses.getValue("posting-destination-rg03-imported-two"))
        }
    }

    @Test
    fun `accepted source on same executor replaces confirmed lifecycle before mirror`() {
        val identities = object : Rg03IdentitySource by testIdentitySource() {
            override fun source(requestId: RequestId) = if (requestId.value == "request-source-two") {
                Rg03SourceCommitIds(CandidateId("candidate-two"), "status-pending-two")
            } else {
                testIdentitySource().source(requestId)
            }
        }
        Harness(identitySource = identities).use { harness ->
            val executor = ExecuteRg03Operation(harness.store, harness.store, harness.store)
            assertIs<Rg03ExecutionResult.Accepted>(
                executor.execute(Rg03Command.ImportSource(completeSource())),
            )
            assertIs<Rg03ExecutionResult.Accepted>(
                executor.execute(
                    Rg03Command.ConfirmCandidate(
                        RequestId("request-rg03-confirm-candidate"),
                        CandidateId("candidate-transfer-rg03"),
                        true,
                        LEDGER,
                    ),
                ),
            )
            val second = completeSource().copy(
                requestId = RequestId("request-source-two"),
                sourceId = SourceRecordId("source-two"),
                evidenceId = EvidenceId("evidence-two"),
            )
            assertIs<Rg03ExecutionResult.Accepted>(
                executor.execute(Rg03Command.ImportSource(second)),
            )
            val before = harness.counts()

            assertEquals(
                Rg03ExecutionResult.Rejected(Rg03ExecutionError.MIRROR_TARGET_NOT_FOUND),
                executor.execute(Rg03Command.ImportMirror(mirrorSnapshot())),
            )

            assertEquals(before, harness.counts())
            assertEquals(2L, harness.queries.countRg03SourceRecords().executeAsOne())
            assertEquals(2L, harness.queries.countRg03Evidence().executeAsOne())
            assertEquals(1L, harness.queries.countRg03EvidenceLinks().executeAsOne())
            assertEquals(
                "PENDING",
                harness.queries.selectRg03PostingReconciliations().executeAsList()
                    .single { it.posting_id == "posting-destination-rg03-imported" }.status,
            )
        }
    }

    @Test
    fun `source SQL failure rolls back every source intake row`() {
        val failures = ThrowingFailureInjector(Rg03FailurePoint.SOURCE_AFTER_CANDIDATE)
        Harness(failureInjector = failures).use { harness ->
            assertFailsWith<SQLException> {
                harness.store.commit(Rg03PreparedOperation.StoreSource(completeSource()))
            }

            assertEquals(Rg03DataCounts.EMPTY, harness.counts())
        }
    }

    @Test
    fun `candidate confirmation SQL failure rolls back to the pending source state`() {
        val failures = ThrowingFailureInjector()
        Harness(failureInjector = failures).use { harness ->
            assertIs<Rg03ExecutionResult.Accepted>(
                harness.store.commit(Rg03PreparedOperation.StoreSource(completeSource())),
            )
            val candidate = checkNotNull(harness.store.load(LEDGER, CandidateId("candidate-transfer-rg03")))
            val before = harness.counts()
            failures.point = Rg03FailurePoint.CONFIRMATION_AFTER_FORMAL

            assertFailsWith<SQLException> {
                harness.store.commit(
                    Rg03PreparedOperation.ConfirmCandidate(
                        RequestId("request-rg03-confirm-candidate"),
                        candidate,
                    ),
                )
            }

            assertEquals(before, harness.counts())
            assertEquals(CandidateStatus.PENDING_CONFIRMATION, harness.store.load(LEDGER, candidate.candidateId)?.status)
        }
    }

    @Test
    fun `mirror SQL failure rolls back evidence and leaves destination pending`() {
        val failures = ThrowingFailureInjector()
        Harness(failureInjector = failures).use { harness ->
            val executor = ExecuteRg03Operation(harness.store, harness.store, harness.store)
            assertIs<Rg03ExecutionResult.Accepted>(
                harness.store.commit(Rg03PreparedOperation.StoreSource(completeSource())),
            )
            assertIs<Rg03ExecutionResult.Accepted>(
                executor.execute(
                    Rg03Command.ConfirmCandidate(
                        RequestId("request-rg03-confirm-candidate"),
                        CandidateId("candidate-transfer-rg03"),
                        true,
                        LEDGER,
                    ),
                ),
            )
            val target = assertIs<Rg03MirrorBindingResult.Unique>(
                harness.store.resolve(LEDGER, Rg03MirrorScope(CandidateId("candidate-transfer-rg03"))),
            ).target
            val before = harness.counts()
            val reconciliationsBefore = harness.queries.selectRg03PostingReconciliations().executeAsList()
            failures.point = Rg03FailurePoint.MIRROR_AFTER_LINK

            assertFailsWith<SQLException> {
                harness.store.commit(Rg03PreparedOperation.MergeMirror(mirrorSnapshot(), target))
            }

            assertEquals(before, harness.counts())
            assertEquals(reconciliationsBefore, harness.queries.selectRg03PostingReconciliations().executeAsList())
            assertEquals(
                "PENDING",
                harness.queries.selectRg03PostingReconciliations().executeAsList()
                    .single { it.posting_id == target.destinationPostingId.value }.status,
            )
        }
    }

    @Test
    fun `concurrent equivalent source requests persist once and replay`() {
        FileRg03Database.create().use { file ->
            file.initialize()
            val snapshot = completeSource()

            val results = runConcurrently(
                { file.open().use { it.store.commit(Rg03PreparedOperation.StoreSource(snapshot)) } },
                { file.open().use { it.store.commit(Rg03PreparedOperation.StoreSource(snapshot)) } },
            )

            assertEquals(1, results.count { it is Rg03ExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg03ExecutionResult.NoChange })
            file.open().use { harness ->
                assertEquals(Rg03DataCounts.sourceIntake(completeDetails = 1), harness.counts())
            }
        }
    }

    @Test
    fun `concurrent conflicting source requests persist one winner and reject the loser`() {
        FileRg03Database.create().use { file ->
            file.initialize()
            val snapshot = completeSource()
            val conflict = snapshot.copy(destinationCredit = money(5_899), fee = money(101))

            val results = runConcurrently(
                { file.open().use { it.store.commit(Rg03PreparedOperation.StoreSource(snapshot)) } },
                { file.open().use { it.store.commit(Rg03PreparedOperation.StoreSource(conflict)) } },
            )

            assertEquals(1, results.count { it is Rg03ExecutionResult.Accepted })
            assertEquals(1, results.count { it is Rg03ExecutionResult.RequestIdentityConflict })
            file.open().use { harness ->
                assertEquals(Rg03DataCounts.sourceIntake(completeDetails = 1), harness.counts())
            }
        }
    }

    @Test
    fun `concurrent candidate confirmations accept once and leave no losing request`() {
        FileRg03Database.create().use { file ->
            file.initialize()
            val source = completeSource()
            val candidateId = concurrentCandidateId(source.requestId)
            file.open().use { harness ->
                assertIs<Rg03ExecutionResult.Accepted>(
                    harness.store.commit(Rg03PreparedOperation.StoreSource(source)),
                )
            }
            val candidate = file.open().use { checkNotNull(it.store.load(LEDGER, candidateId)) }

            val results = runConcurrently(
                {
                    file.open().use {
                        it.store.commit(
                            Rg03PreparedOperation.ConfirmCandidate(RequestId("request-confirm-concurrent-a"), candidate),
                        )
                    }
                },
                {
                    file.open().use {
                        it.store.commit(
                            Rg03PreparedOperation.ConfirmCandidate(RequestId("request-confirm-concurrent-b"), candidate),
                        )
                    }
                },
            )

            assertEquals(1, results.count { it is Rg03ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it == Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_PENDING)
                },
            )
            file.open().use { harness ->
                assertEquals(Rg03DataCounts.CONFIRMED_SOURCE, harness.counts())
                assertEquals(CandidateStatus.CONFIRMED, harness.store.load(LEDGER, candidateId)?.status)
            }
        }
    }

    @Test
    fun `concurrent mirrors accept once and leave no losing evidence`() {
        FileRg03Database.create().use { file ->
            file.initialize()
            val source = completeSource()
            val candidateId = concurrentCandidateId(source.requestId)
            file.open().use { harness ->
                val executor = ExecuteRg03Operation(harness.store, harness.store, harness.store)
                assertIs<Rg03ExecutionResult.Accepted>(
                    harness.store.commit(Rg03PreparedOperation.StoreSource(source)),
                )
                assertIs<Rg03ExecutionResult.Accepted>(
                    executor.execute(
                        Rg03Command.ConfirmCandidate(
                            RequestId("request-confirm-before-concurrent-mirror"),
                            candidateId,
                            true,
                            LEDGER,
                        ),
                    ),
                )
            }
            val target = file.open().use { harness ->
                assertIs<Rg03MirrorBindingResult.Unique>(
                    harness.store.resolve(LEDGER, Rg03MirrorScope(candidateId)),
                ).target
            }
            val first = mirrorSnapshot().copy(
                requestId = RequestId("request-mirror-concurrent-a"),
                sourceId = SourceRecordId("source-mirror-concurrent-a"),
                evidenceId = EvidenceId("evidence-mirror-concurrent-a"),
            )
            val second = mirrorSnapshot().copy(
                requestId = RequestId("request-mirror-concurrent-b"),
                sourceId = SourceRecordId("source-mirror-concurrent-b"),
                evidenceId = EvidenceId("evidence-mirror-concurrent-b"),
            )

            val results = runConcurrently(
                { file.open().use { it.store.commit(Rg03PreparedOperation.MergeMirror(first, target)) } },
                { file.open().use { it.store.commit(Rg03PreparedOperation.MergeMirror(second, target)) } },
            )

            assertEquals(1, results.count { it is Rg03ExecutionResult.Accepted })
            assertEquals(
                1,
                results.count {
                    it == Rg03ExecutionResult.Rejected(Rg03ExecutionError.MIRROR_TARGET_NOT_FOUND)
                },
            )
            file.open().use { harness ->
                assertEquals(Rg03DataCounts.MIRRORED_SOURCE, harness.counts())
                assertEquals(
                    "MATCHED",
                    harness.queries.selectRg03PostingReconciliations().executeAsList()
                        .single { it.posting_id == target.destinationPostingId.value }.status,
                )
            }
        }
    }

    @Test
    fun `formal SQL identity collision rolls back the entire second manual operation`() =
        Harness(identitySource = collisionIdentitySource()).use { harness ->
            assertIs<Rg03ExecutionResult.Accepted>(
                harness.store.commit(Rg03PreparedOperation.CreateManual(manualSnapshot())),
            )
            assertFailsWith<SQLException> {
                harness.store.commit(
                    Rg03PreparedOperation.CreateManual(
                        manualSnapshot().copy(requestId = RequestId("request-second")),
                    ),
                )
            }

            assertEquals(1L, harness.queries.countRg03OperationRequests().executeAsOne())
            assertEquals(1L, harness.queries.countRg03OperationReceipts().executeAsOne())
            assertEquals(1L, harness.queries.countTransactions().executeAsOne())
            assertEquals(3L, harness.queries.countPostings().executeAsOne())
        }

    @Test
    fun `foreign keys reject cross ledger transfer owner references`() = Harness().use { harness ->
        assertEquals("1", harness.queries.foreignKeysEnabled().executeAsOne())
        assertFailsWith<SQLException> {
            harness.driver.execute(
                null,
                """
                    INSERT INTO rg03_candidate_status_history(
                      ledger_id, candidate_id, status_sequence, status, request_id
                    ) VALUES ('ledger-other', 'candidate-missing', 1, 'PENDING_CONFIRMATION', 'request-missing')
                """.trimIndent(),
                0,
            )
        }
        assertEquals(Rg03DataCounts.EMPTY, harness.counts())
    }
}

private class CountingIdentitySource(private val delegate: Rg03IdentitySource) : Rg03IdentitySource {
    var sources = 0
    var transfers = 0
    var mirrors = 0
    override fun source(requestId: RequestId) = delegate.source(requestId).also { sources++ }
    override fun transfer(requestId: RequestId) = delegate.transfer(requestId).also { transfers++ }
    override fun mirror(requestId: RequestId) = delegate.mirror(requestId).also { mirrors++ }
}

private class ThrowingFailureInjector(
    var point: Rg03FailurePoint? = null,
) : Rg03FailureInjector {
    override fun failAt(point: Rg03FailurePoint) {
        if (point == this.point) throw SQLException("injected $point")
    }
}

private class Harness(
    identitySource: Rg03IdentitySource = testIdentitySource(),
    failureInjector: Rg03FailureInjector = Rg03FailureInjector { },
    val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, rg03SqliteProperties()),
    createSchema: Boolean = true,
) : AutoCloseable {
    private val database = LedgerDatabase(driver)
    val queries = database.ledgerQueries
    val store: SqlDelightRg03TransferStore

    init {
        if (createSchema) LedgerDatabase.Schema.create(driver)
        store = SqlDelightRg03TransferStore(database, driver, catalog(), identitySource, failureInjector)
    }

    fun counts() = Rg03DataCounts(
        requests = queries.countRg03OperationRequests().executeAsOne(),
        receipts = queries.countRg03OperationReceipts().executeAsOne(),
        confirmations = queries.countRg03Confirmations().executeAsOne(),
        sources = queries.countRg03SourceRecords().executeAsOne(),
        completeDetails = queries.countRg03CompleteSourceDetails().executeAsOne(),
        candidates = queries.countRg03Candidates().executeAsOne(),
        candidateStatuses = queries.countRg03CandidateStatuses().executeAsOne(),
        evidence = queries.countRg03Evidence().executeAsOne(),
        evidenceLinks = queries.countRg03EvidenceLinks().executeAsOne(),
        reconciliations = queries.countRg03PostingReconciliations().executeAsOne(),
        transactions = queries.countTransactions().executeAsOne(),
        versions = queries.countVersions().executeAsOne(),
        postingSets = queries.countPostingSets().executeAsOne(),
        postings = queries.countPostings().executeAsOne(),
    )

    override fun close() = driver.close()
}

private class FileRg03Database private constructor(
    private val path: java.nio.file.Path,
    private val identitySource: Rg03IdentitySource,
) : AutoCloseable {
    fun initialize() = open(createSchema = true).close()

    fun open(createSchema: Boolean = false): Harness = Harness(
        identitySource = identitySource,
        driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}", rg03SqliteProperties()),
        createSchema = createSchema,
    )

    override fun close() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun create() = FileRg03Database(Files.createTempFile("ledger-data-rg03-", ".db"), concurrentIdentitySource())
    }
}

private fun <T> runConcurrently(first: () -> T, second: () -> T): List<T> {
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    return try {
        val futures = listOf(first, second).map { operation ->
            executor.submit<T> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                operation()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        futures.map { it.get(15, TimeUnit.SECONDS) }
    } finally {
        executor.shutdownNow()
    }
}

private fun rg03SqliteProperties() = Properties().apply {
    setProperty("foreign_keys", "true")
    setProperty("busy_timeout", "5000")
}

private data class Rg03DataCounts(
    val requests: Long,
    val receipts: Long,
    val confirmations: Long,
    val sources: Long,
    val completeDetails: Long,
    val candidates: Long,
    val candidateStatuses: Long,
    val evidence: Long,
    val evidenceLinks: Long,
    val reconciliations: Long,
    val transactions: Long,
    val versions: Long,
    val postingSets: Long,
    val postings: Long,
) {
    companion object {
        val EMPTY = Rg03DataCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val CONFIRMED_SOURCE = Rg03DataCounts(2, 2, 1, 1, 1, 1, 2, 1, 1, 2, 1, 1, 1, 3)
        val MIRRORED_SOURCE = Rg03DataCounts(3, 3, 1, 2, 1, 1, 2, 2, 2, 2, 1, 1, 1, 3)

        fun sourceIntake(completeDetails: Long) = Rg03DataCounts(
            requests = 1,
            receipts = 1,
            confirmations = 0,
            sources = 1,
            completeDetails = completeDetails,
            candidates = 1,
            candidateStatuses = 1,
            evidence = 1,
            evidenceLinks = 0,
            reconciliations = 0,
            transactions = 0,
            versions = 0,
            postingSets = 0,
            postings = 0,
        )
    }
}

private val LEDGER = LedgerId("ledger-a")
private val CURRENCY = CurrencyUnit("CNY", 2)

private fun money(minor: Long) = Money.ofMinor(minor, CURRENCY)

private fun manualSnapshot() = Rg03ManualTransferSnapshot(
    LEDGER,
    RequestId("request-rg03-manual-create"),
    Instant.parse("2026-01-20T02:00:00Z"),
    AccountId("asset-bank-a"),
    AccountId("asset-wallet-b"),
    money(6_000),
    money(5_900),
    money(100),
    CategoryId("expense-category-transfer-fee"),
)

private fun completeSource() = Rg03SourceSnapshot(
    LEDGER,
    RequestId("request-rg03-import-source"),
    SourceRecordId("source-record-rg03-debit"),
    EvidenceId("evidence-rg03-debit"),
    Instant.parse("2026-01-21T03:00:00Z"),
    AccountId("asset-bank-a"),
    AccountId("asset-wallet-b"),
    money(6_000),
    money(5_900),
    money(100),
    CategoryId("expense-category-transfer-fee"),
    SourceCompleteness.COMPLETE,
)

private fun incompleteSource() = Rg03SourceSnapshot(
    LEDGER,
    RequestId("request-rg03-unknown-debit"),
    SourceRecordId("source-record-rg03-unknown-debit"),
    EvidenceId("evidence-rg03-unknown-debit"),
    Instant.parse("2026-01-22T03:00:00Z"),
    AccountId("asset-bank-a"),
    null,
    money(6_000),
    null,
    null,
    CategoryId("expense-category-transfer-fee"),
    SourceCompleteness.MISSING_DESTINATION,
)

private fun mirrorSnapshot() = Rg03MirrorSnapshot(
    LEDGER,
    RequestId("request-rg03-import-mirror"),
    SourceRecordId("source-record-rg03-credit-mirror"),
    EvidenceId("evidence-rg03-credit-mirror"),
    Instant.parse("2026-01-21T03:01:00Z"),
    AccountId("asset-wallet-b"),
    money(5_900),
)

private fun persistedCandidate(
    candidateId: String = "candidate-transfer-rg03",
    source: Rg03SourceSnapshot = completeSource(),
    destinationAccountId: AccountId? = source.destinationAccountId,
    destinationCredit: Money? = source.destinationCredit,
    fee: Money? = source.fee,
) = Rg03PersistedTransferCandidate(
    ledgerId = LEDGER,
    candidateId = CandidateId(candidateId),
    status = CandidateStatus.PENDING_CONFIRMATION,
    sourceId = source.sourceId,
    evidenceId = source.evidenceId,
    sourceAccountId = source.sourceAccountId,
    destinationAccountId = destinationAccountId,
    sourceDebit = source.sourceDebit,
    destinationCredit = destinationCredit,
    fee = fee,
    feeCategoryId = source.feeCategoryId,
    observedAt = source.observedAt,
)

private fun catalog() = assertIs<DomainResult.Success<LedgerCatalog>>(
    LedgerCatalog.create(
        listOf(
            Account(AccountId("asset-bank-a"), LEDGER, AccountKind.ASSET, CURRENCY, true, true),
            Account(AccountId("asset-wallet-b"), LEDGER, AccountKind.ASSET, CURRENCY, true, true),
            Account(AccountId("asset-external-x"), LEDGER, AccountKind.ASSET, CURRENCY, false, true),
            Account(AccountId("liability-credit-c"), LEDGER, AccountKind.LIABILITY, CURRENCY, true, true),
            Account(
                AccountId("asset-usd"), LEDGER, AccountKind.ASSET, CurrencyUnit("USD", 2), true, true,
            ),
            Account(
                AccountId("expense-account-transfer-fee"),
                LEDGER,
                AccountKind.EXPENSE,
                CURRENCY,
                false,
                false,
            ),
        ),
        listOf(
            Category(CategoryId("expense-category-financial"), LEDGER, null, null, true),
            Category(
                CategoryId("expense-category-transfer-fee"),
                LEDGER,
                CategoryId("expense-category-financial"),
                AccountId("expense-account-transfer-fee"),
                true,
            ),
        ),
    ),
).value

private fun testIdentitySource(): Rg03IdentitySource = object : Rg03IdentitySource {
    override fun source(requestId: RequestId): Rg03SourceCommitIds = when (requestId.value) {
        "request-rg03-import-source" -> Rg03SourceCommitIds(CandidateId("candidate-transfer-rg03"), "status-pending-import")
        "request-rg03-unknown-debit" -> Rg03SourceCommitIds(CandidateId("candidate-transfer-rg03-unknown-debit"), "status-pending-incomplete")
        else -> error("No source identities for ${requestId.value}")
    }

    override fun transfer(requestId: RequestId): Rg03TransferCommitIds = when (requestId.value) {
        "request-rg03-manual-create" -> transferIds("manual", "confirmation-manual")
        "request-rg03-confirm-candidate" -> transferIds("imported", "confirmation-imported", "status-confirmed-import")
        "request-tampered-confirm" -> transferIds("tampered", "confirmation-tampered", "status-confirmed-tampered")
        "request-second" -> transferIds("second", "confirmation-second")
        else -> error("No transfer identities for ${requestId.value}")
    }

    override fun mirror(requestId: RequestId): Rg03MirrorCommitIds = when (requestId.value) {
        "request-rg03-import-mirror" -> Rg03MirrorCommitIds("match-rg03-credit-mirror")
        else -> error("No mirror identities for ${requestId.value}")
    }
}

private fun concurrentIdentitySource(): Rg03IdentitySource = object : Rg03IdentitySource {
    override fun source(requestId: RequestId) = Rg03SourceCommitIds(
        concurrentCandidateId(requestId),
        "status-pending-${requestId.value}",
    )

    override fun transfer(requestId: RequestId) = transferIds(
        suffix = requestId.value,
        confirmation = "confirmation-${requestId.value}",
        candidateStatus = "status-confirmed-${requestId.value}",
    ).copy(sourceEvidenceLinkId = "match-source-${requestId.value}")

    override fun mirror(requestId: RequestId) = Rg03MirrorCommitIds("match-mirror-${requestId.value}")
}

private fun concurrentCandidateId(requestId: RequestId) = CandidateId("candidate-${requestId.value}")

private fun transferIds(
    suffix: String,
    confirmation: String,
    candidateStatus: String? = null,
) = Rg03TransferCommitIds(
    ConfirmationId(confirmation),
    AccountTransferIds(
        TransactionId("tx-transfer-rg03-$suffix"),
        TransactionVersionId("version-transfer-rg03-$suffix-v1"),
        PostingSetId("posting-set-transfer-rg03-$suffix"),
        PostingId("posting-source-rg03-$suffix"),
        PostingId("posting-destination-rg03-$suffix"),
        PostingId("posting-fee-rg03-$suffix"),
    ),
    "reconciliation-source-$suffix",
    "reconciliation-destination-$suffix",
    candidateStatus,
    if (candidateStatus == null) null else "match-rg03-debit",
)

private fun collisionIdentitySource(): Rg03IdentitySource = object : Rg03IdentitySource by testIdentitySource() {
    override fun transfer(requestId: RequestId): Rg03TransferCommitIds = Rg03TransferCommitIds(
        ConfirmationId("confirmation-collision-${requestId.value}"),
        AccountTransferIds(
            TransactionId("tx-collision"),
            TransactionVersionId("version-collision"),
            PostingSetId("posting-set-collision"),
            PostingId("posting-source-collision"),
            PostingId("posting-destination-collision"),
            PostingId("posting-fee-collision"),
        ),
        "reconciliation-source-collision-${requestId.value}",
        "reconciliation-destination-collision-${requestId.value}",
    )
}
