package com.unifiedledger.ui

import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.ExplicitManualSave
import com.unifiedledger.application.ImportCandidateCommitPort
import com.unifiedledger.application.ImportCandidateDecisionResult
import com.unifiedledger.application.ImportCandidateDecisionSnapshot
import com.unifiedledger.application.ImportCandidateFormalFactory
import com.unifiedledger.application.ImportCandidateFormalizationInput
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCommitIds
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportConfirmationId
import com.unifiedledger.application.ImportFormalCommit
import com.unifiedledger.application.ImportFormalIds
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIdSource
import com.unifiedledger.application.ImportReceipt
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportResolvedSourceFacts
import com.unifiedledger.application.ImportReviewReadPort
import com.unifiedledger.application.ImportReviewRow
import com.unifiedledger.application.ImportReviewRowsResult
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualExpenseSaveInput
import com.unifiedledger.application.ManualExpenseSaveResult
import com.unifiedledger.application.ManualExpenseSubmissionResult
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryManualExpenseOptions
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.SPINE_NO_CHANGE_REASON_CODE
import com.unifiedledger.application.SpineDiagnostics
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
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
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * P706-A07 (D-239 item 4) lease-concurrency coverage with REAL job types: instead of the generic
 * placeholder lease of [LedgerRuntimeOwnerTest], the restore-confirm quiesce/drain window here is
 * pressed by the three production job families, each wrapped in [LedgerLeaseScope.leased] exactly
 * like the composition roots — the import batch dispatch loop ([ImportBatchDispatchLoop] with the
 * real [ConfirmImportCandidate] core chain), the real manual-expense submission chain
 * ([ExecuteManualExpenseSubmission] through the [CommitOnceInvocationTracker] handoff), and the
 * real ledger-state read ([QueryLedgerCurrentState]). The asserted semantics are the frozen
 * ones: the drain waits for the in-flight real job, drain-period admission is typed-refused
 * without running anything, a timeout yields the typed [QuiesceResult.QuiesceBlocked] without
 * interrupting the job, and a result captured under a generation superseded by the switch reopen
 * is discarded through [shouldDiscardLandingResult]/[LedgerLeaseScope.isCurrentGeneration].
 *
 * Realism boundary, declared per job family:
 * - Import: the dispatch-loop shape (pre-phase read, admission, sequential loop, per-item landing
 *   guard) and the [ConfirmImportCandidate.execute] core chain are production code paths. The
 *   [ImportCandidateCommitPort] is an IN-MEMORY TEST FAKE whose spine claim/replay semantics are
 *   re-implemented in memory ([InMemoryImportCandidateCommitPort]); it is NOT the real spine
 *   store (ledger-data), so nothing here claims import-spine persistence coverage.
 * - Expense: the submission chain above the port (submission orchestration, save input gate,
 *   confirmed-expense use case, the commitOnce invocation tracker) is production code; the
 *   [ConfirmedManualExpenseCommitPort] is an in-memory recording fake re-implementing the frozen
 *   commitOnce contract ([InMemoryExpenseCommitPort], the ConfirmedManualExpenseIdempotencyTest
 *   precedent), NOT the real storage port.
 * - Read: [QueryLedgerCurrentState] and its read-port contract are production code; the port
 *   itself is an in-memory row fixture ([RecordingCurrentStateReadPort]).
 *
 * Accounting invariants: all amounts are exact integer minor units ([Long] through
 * [Money.ofMinor], never floating point); every query is ledger-scoped. Concurrency follows the
 * real-parallelism precedent (runBlocking + Dispatchers.Default + [CompletableDeferred] parks +
 * conservative [withTimeoutOrNull] budgets), never a virtual test scheduler.
 */
class P706RealJobLeaseConcurrencyTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val assetAccountId = AccountId("asset-payment-local")
    private val expenseAccountId = AccountId("expense-category-food")
    private val categoryId = CategoryId("category-food")
    private val confirmedAt = "2026-10-24T08:00:00Z"
    private val occurredAt = Instant.parse("2026-03-15T02:00:00Z")

    private val importItems =
        listOf(
            ImportBatchItem(ImportCandidateId("candidate-1"), ImportRequestId("request-import-1")),
            ImportBatchItem(ImportCandidateId("candidate-2"), ImportRequestId("request-import-2")),
        )

    private val importDrafts =
        importItems.associate { it.candidateId to ImportDecisionDraft(categoryId = categoryId, fundingAccountId = assetAccountId) }

    // ---------------------------------------------------------------- import job (dispatch loop + real confirm chain)

    @Test
    fun anImportDispatchRunHeldAcrossTheConfirmWindowDrainsFullyAndCommitsEveryItemExactlyOnce() =
        runBlocking {
            val h = fixture()
            val jobEntered = CompletableDeferred<Unit>()
            val releaseJob = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<LeaseOutcome<ImportBatchDispatchRun>>()
            val landed = mutableListOf<ImportBatchDispatchResult>()
            val job =
                launch(Dispatchers.Default) {
                    outcome.complete(
                        h.scope.leased { facade, _ ->
                            jobEntered.complete(Unit)
                            // Park while HOLDING the lease. The leased block is non-suspending
                            // (the production shape), so the park is a blocking one on the worker
                            // thread — the ConfirmBackupRestoreUseCaseTest OwnerHandle precedent.
                            runBlocking { releaseJob.await() }
                            runImportDispatch(facade, landed)
                        },
                    )
                }
            withTimeoutOrNull(10_000) { jobEntered.await() } ?: error("the import job never entered its lease")

            // The confirm-period quiesce must WAIT for the in-flight real import job.
            val quiesce = async { h.owner.quiesce() }
            withTimeoutOrNull(10_000) {
                while (h.owner.state != LedgerRuntimeState.Quiescing) delay(5)
            } ?: error("the quiesce never started draining")
            assertNull(withTimeoutOrNull(200) { quiesce.await() }, "the drain must wait for the in-flight import job")

            // Drain-period admission: both the raw acquire and the scope entry are typed-refused,
            // and the refused scope entry runs nothing.
            assertEquals(LeaseAcquireResult.RuntimeNotReady, h.owner.acquireLease())
            var probeRan = false
            assertEquals(LeaseOutcome.NotReady, h.scope.leased { _, _ -> probeRan = true })
            assertFalse(probeRan, "a drain-period leased entry must not run its block")

            // Releasing the job lets the drain finish; the job itself ran to completion.
            releaseJob.complete(Unit)
            assertEquals(QuiesceResult.Quiesced, withTimeoutOrNull(10_000) { quiesce.await() })
            job.join()
            val leaseOutcome = assertIs<LeaseOutcome.Completed<ImportBatchDispatchRun>>(outcome.await())
            assertIs<ImportBatchDispatchRun.Completed>(leaseOutcome.value)
            assertEquals(2, landed.size)
            landed.forEach { result -> assertIs<ImportBatchItemOutcome.Confirmed>(result.outcome) }

            // Exactly-once on the declared in-memory spine fake: one claim, one id mint and one
            // formal commit per item, with two distinct receipts (no duplicate or partial commit).
            assertEquals(2, h.importCommitPort.commitOnceInvocations)
            assertEquals(2, h.importCommitPort.formalFactoryInvocations)
            assertEquals(2, h.importIdSource.invocations)
            assertEquals(2, h.importCommitPort.receipts.size)
            assertEquals(
                2,
                h.importCommitPort.receipts
                    .map { it.confirmationId }
                    .toSet()
                    .size,
            )
            assertEquals(0, h.owner.inFlightLeaseCount)
        }

    // ---------------------------------------------------------------- expense job (real submission chain)

    @Test
    fun anExpenseSubmissionHeldAcrossTheConfirmWindowDrainsFullyAndReplaysTheOriginalReceiptWithoutASecondCommit() =
        runBlocking {
            val h = fixture()
            val input = expenseInput("request-expense-1")
            val jobEntered = CompletableDeferred<Unit>()
            val releaseJob = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<LeaseOutcome<ManualExpenseSubmissionResult>>()
            val job =
                launch(Dispatchers.Default) {
                    outcome.complete(
                        h.scope.leased { facade, _ ->
                            jobEntered.complete(Unit)
                            // Park while HOLDING the lease. The leased block is non-suspending
                            // (the production shape), so the park is a blocking one on the worker
                            // thread — the ConfirmBackupRestoreUseCaseTest OwnerHandle precedent.
                            runBlocking { releaseJob.await() }
                            facade.submitExpense.submit(input)
                        },
                    )
                }
            withTimeoutOrNull(10_000) { jobEntered.await() } ?: error("the expense job never entered its lease")

            val quiesce = async { h.owner.quiesce() }
            withTimeoutOrNull(10_000) {
                while (h.owner.state != LedgerRuntimeState.Quiescing) delay(5)
            } ?: error("the quiesce never started draining")
            assertNull(withTimeoutOrNull(200) { quiesce.await() }, "the drain must wait for the in-flight expense job")
            assertEquals(LeaseAcquireResult.RuntimeNotReady, h.owner.acquireLease())

            releaseJob.complete(Unit)
            assertEquals(QuiesceResult.Quiesced, withTimeoutOrNull(10_000) { quiesce.await() })
            job.join()
            val leaseOutcome = assertIs<LeaseOutcome.Completed<ManualExpenseSubmissionResult>>(outcome.await())
            val application = assertIs<ManualExpenseSubmissionResult.Application>(leaseOutcome.value)
            val executed = assertIs<ManualExpenseSaveResult.Executed>(application.result)
            val receipt = assertIs<ConfirmedManualExpenseResult.Created>(executed.result).receipt

            // Exactly-once through the real chain: one commitOnce handoff, one id mint, one formal
            // commit, one receipt.
            assertEquals(1, h.expenseCommitPort.commitOnceInvocations)
            assertEquals(1, h.expenseCommitPort.formalFactoryInvocations)
            assertEquals(1, h.expenseIdSource.invocations)
            assertEquals(listOf(receipt), h.expenseCommitPort.receipts)

            // After the runtime restoration reopen, the replay of the SAME request through the real
            // submission chain returns the original receipt and adds no second formal commit.
            assertEquals(ReopenResult.Reopened(2), h.owner.reopen())
            val replayApplication = assertIs<ManualExpenseSubmissionResult.Application>(h.scope.probe { it.submitExpense.submit(input) })
            val replayExecuted = assertIs<ManualExpenseSaveResult.Executed>(replayApplication.result)
            val replayResult = assertIs<ConfirmedManualExpenseResult.NoChange>(replayExecuted.result)
            assertEquals(receipt, replayResult.receipt)
            assertEquals(2, h.expenseCommitPort.commitOnceInvocations, "the replay still passes through the commitOnce boundary")
            assertEquals(1, h.expenseCommitPort.formalFactoryInvocations, "the replay must not re-run the formal commit")
            assertEquals(listOf(receipt), h.expenseCommitPort.receipts)
        }

    // ---------------------------------------------------------------- read job (real ledger-state read)

    @Test
    fun aLedgerStateReadHeldAcrossTheConfirmWindowDrainsFullyAndReturnsOnlyTheScopedRowsUninterrupted() =
        runBlocking {
            val h = fixture()
            val jobEntered = CompletableDeferred<Unit>()
            val releaseJob = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<LeaseOutcome<LedgerCurrentStateResult>>()
            val job =
                launch(Dispatchers.Default) {
                    outcome.complete(
                        h.scope.leased { facade, _ ->
                            jobEntered.complete(Unit)
                            // Park while HOLDING the lease. The leased block is non-suspending
                            // (the production shape), so the park is a blocking one on the worker
                            // thread — the ConfirmBackupRestoreUseCaseTest OwnerHandle precedent.
                            runBlocking { releaseJob.await() }
                            facade.queryCurrentState.query()
                        },
                    )
                }
            withTimeoutOrNull(10_000) { jobEntered.await() } ?: error("the read job never entered its lease")

            val quiesce = async { h.owner.quiesce() }
            withTimeoutOrNull(10_000) {
                while (h.owner.state != LedgerRuntimeState.Quiescing) delay(5)
            } ?: error("the quiesce never started draining")
            assertNull(withTimeoutOrNull(200) { quiesce.await() }, "the drain must wait for the in-flight read job")
            assertEquals(LeaseAcquireResult.RuntimeNotReady, h.owner.acquireLease())

            releaseJob.complete(Unit)
            assertEquals(QuiesceResult.Quiesced, withTimeoutOrNull(10_000) { quiesce.await() })
            job.join()
            val leaseOutcome = assertIs<LeaseOutcome.Completed<LedgerCurrentStateResult>>(outcome.await())
            val state = assertIs<LedgerCurrentStateResult.Success>(leaseOutcome.value).state

            // The read is ledger-scoped and complete: exactly the seeded row, exact integer minor units.
            assertEquals(ledgerId, state.ledgerId)
            assertEquals(1, state.transactions.size)
            val asset = state.balances.single { it.accountId == assetAccountId }
            assertEquals(-3_580L, asset.ledgerSignedMinorUnits)
            assertEquals(-3_580L, asset.displayMinorUnits)
            val expense = state.balances.single { it.accountId == expenseAccountId }
            assertEquals(3_580L, expense.ledgerSignedMinorUnits)
            assertEquals(3_580L, expense.displayMinorUnits)
            assertTrue(h.readPort.loadCurrentRowsLedgerIds.all { it == ledgerId }, "every read the job drove hit its own ledger")
        }

    // ---------------------------------------------------------------- bounded timeout with a real job

    @Test
    fun aRealJobLongerThanTheQuiesceTimeoutYieldsQuiesceBlockedAndStillRunsToCompletion() =
        runBlocking {
            val h = fixture(quiesceTimeoutMillis = 150L)
            val jobEntered = CompletableDeferred<Unit>()
            val releaseJob = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<LeaseOutcome<ManualExpenseSubmissionResult>>()
            val job =
                launch(Dispatchers.Default) {
                    outcome.complete(
                        h.scope.leased { facade, _ ->
                            jobEntered.complete(Unit)
                            // Park while HOLDING the lease. The leased block is non-suspending
                            // (the production shape), so the park is a blocking one on the worker
                            // thread — the ConfirmBackupRestoreUseCaseTest OwnerHandle precedent.
                            runBlocking { releaseJob.await() }
                            facade.submitExpense.submit(expenseInput("request-expense-timeout"))
                        },
                    )
                }
            withTimeoutOrNull(10_000) { jobEntered.await() } ?: error("the expense job never entered its lease")

            // The bounded timeout turns the undrainable window into the typed result — never an
            // infinite wait — and the runtime honestly stays Quiescing.
            val quiesce = async { h.owner.quiesce() }
            val blocked = assertIs<QuiesceResult.QuiesceBlocked>(withTimeoutOrNull(10_000) { quiesce.await() } ?: error("the quiesce never returned"))
            assertEquals(1, blocked.inFlightLeases)
            assertEquals(LedgerRuntimeState.Quiescing, h.owner.state)

            // The timed-out real job is NOT interrupted: after the release it runs to completion
            // with exactly one full commit.
            releaseJob.complete(Unit)
            job.join()
            val leaseOutcome = assertIs<LeaseOutcome.Completed<ManualExpenseSubmissionResult>>(outcome.await())
            val application = assertIs<ManualExpenseSubmissionResult.Application>(leaseOutcome.value)
            val executed = assertIs<ManualExpenseSaveResult.Executed>(application.result)
            assertIs<ConfirmedManualExpenseResult.Created>(executed.result)
            assertEquals(1, h.expenseCommitPort.commitOnceInvocations)
            assertEquals(1, h.expenseCommitPort.formalFactoryInvocations)
            assertEquals(1, h.expenseCommitPort.receipts.size)
        }

    // ---------------------------------------------------------------- superseded-generation landing discard

    @Test
    fun aResultCapturedUnderAGenerationSupersededByTheSwitchReopenIsDiscardedAndDoesNotPolluteTheNewGraph() =
        runBlocking {
            val h = fixture()
            val outcome = CompletableDeferred<LeaseOutcome<List<ImportBatchDispatchResult>>>()
            val job =
                launch(Dispatchers.Default) {
                    val landed = mutableListOf<ImportBatchDispatchResult>()
                    outcome.complete(
                        h.scope.leased { facade, _ ->
                            assertIs<ImportBatchDispatchRun.Completed>(runImportDispatch(facade, landed))
                            landed.toList()
                        },
                    )
                }
            job.join()
            val leaseOutcome = assertIs<LeaseOutcome.Completed<List<ImportBatchDispatchResult>>>(outcome.await())
            val capturedGeneration = leaseOutcome.generation
            assertEquals(1, capturedGeneration)
            val gen1Results = leaseOutcome.value
            assertEquals(2, gen1Results.size)
            val gen1Receipts = gen1Results.map { assertIs<ImportBatchItemOutcome.Confirmed>(it.outcome).receipt }

            // The confirm switch's runtime sequence: quiesce (zero in flight here) -> close ->
            // reopen advances the generation.
            assertEquals(QuiesceResult.Quiesced, h.owner.quiesce())
            assertEquals(CloseResult.Closed, h.owner.closeActiveGraph())
            assertEquals(ReopenResult.Reopened(2), h.owner.reopen())

            // The production landing guard: the superseded per-item results must NOT land.
            val landed = mutableListOf<ImportBatchDispatchResult>()
            gen1Results.forEach { result ->
                if (h.scope.isCurrentGeneration(capturedGeneration)) landed += result
            }
            assertEquals(0, landed.size, "a result captured under a superseded generation must be discarded")
            assertTrue(shouldDiscardLandingResult(capturedGeneration, h.owner.activeGeneration))
            assertFalse(h.scope.isCurrentGeneration(capturedGeneration))
            assertTrue(h.scope.isCurrentGeneration(2))

            // Re-running the same batch on the new graph: equivalent replays return the ORIGINAL
            // receipts and add no formal commit — the old generation's economic effect does not
            // duplicate into the new graph.
            val rerunLanded = mutableListOf<ImportBatchDispatchResult>()
            val rerunOutcome = h.scope.leased { facade, _ -> runImportDispatch(facade, rerunLanded) }
            val rerun = assertIs<LeaseOutcome.Completed<ImportBatchDispatchRun>>(rerunOutcome)
            assertIs<ImportBatchDispatchRun.Completed>(rerun.value)
            assertEquals(2, rerunLanded.size)
            assertEquals(gen1Receipts, rerunLanded.map { assertIs<ImportBatchItemOutcome.Confirmed>(it.outcome).receipt })
            assertEquals(2, h.importCommitPort.formalFactoryInvocations, "the new graph must not re-commit the old generation's items")
            assertEquals(2, h.importCommitPort.receipts.size)
            rerunLanded.forEach { result ->
                if (h.scope.isCurrentGeneration(rerun.generation)) landed += result
            }
            assertEquals(2, landed.size, "current-generation results land through the same guard")
        }

    // ---------------------------------------------------------------- fixture

    /**
     * One owner + its three real job chains, assembled exactly like the owner-test precedent
     * (a self-built owner whose facadeOf returns a REAL [P503LedgerFacade] with the real
     * submission/dispatch/read chains wired in this file).
     */
    private fun fixture(quiesceTimeoutMillis: Long = LEDGER_QUIESCE_TIMEOUT_MILLIS): JobFixture {
        val readPort = RecordingCurrentStateReadPort(ledgerId, listOf(seededCurrentRow()))
        val expenseCommitPort = InMemoryExpenseCommitPort()
        val expenseTracker = CommitOnceInvocationTracker(expenseCommitPort)
        val expenseIdSource = SequentialExpenseIdSource()
        val expenseFactory =
            ConfirmedExpenseTransactionFactory { request, ids ->
                expenseFormalCommit(request, ids)
            }
        val executeConfirmed = ExecuteConfirmedManualExpense(expenseTracker, expenseIdSource, expenseFactory)
        val submission =
            ExecuteManualExpenseSubmission(
                executeSave = ExecuteManualExpenseSave(executeConfirmed),
                tracker = expenseTracker,
                resolver = ResolveManualExpenseCommitStatus(readPort),
            )
        val importCommitPort = InMemoryImportCandidateCommitPort(ImportResolvedSourceFacts(3_580L, "CNY", 2, "2026-03-15T02:00:00Z", "expense", "trade_success"))
        val importIdSource = SequentialImportIdSource()
        val importFactory =
            ImportCandidateFormalFactory { input, ids ->
                importFormalCommit(input, ids)
            }
        val catalog = fixtureCatalog()
        val confirmImport = ConfirmImportCandidate(importCommitPort, importIdSource, importFactory, catalog)
        val useCaseSet =
            ImportConfirmUseCaseSet(
                ordinaryFlow = confirmImport,
                transferFlow = null,
                creditExpense = null,
                creditRepayment = null,
                mixedPayment = null,
            )
        val facade =
            P503LedgerFacade(
                ledgerId = ledgerId,
                currency = cny,
                catalog = catalog,
                parseAmount = ParseManualExpenseAmount(),
                baseOptionsProvider = QueryManualExpenseOptions(ledgerId, catalog),
                baseQueryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, catalog),
                resolveCommitStatus = ResolveManualExpenseCommitStatus(readPort),
                submitExpense = submission,
                requestIdSource = ManualExpenseRequestIdSource { RequestId("request-owner-test") },
                ledgerClock = LedgerClock { Clock.System.now() },
                baseSummarizeActivity = SummarizeLedgerActivity(catalog),
                baseQueryImportReviewRows = QueryImportReviewRows(FixedImportReviewReadPort(importReviewRows())),
                importConfirmUseCases = { useCaseSet },
            )
        val owner =
            LedgerRuntimeOwner<Any>(
                openGeneration = { facade },
                closeGraph = {},
                facadeOf = { facade },
                quiesceTimeoutMillis = quiesceTimeoutMillis,
            )
        owner.startup()
        return JobFixture(owner, LedgerLeaseScope(owner), readPort, expenseCommitPort, expenseIdSource, importCommitPort, importIdSource)
    }

    private fun fixtureCatalog(): LedgerCatalog =
        when (
            val created =
                LedgerCatalog.create(
                    accounts =
                        listOf(
                            Account(assetAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
                            Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
                        ),
                    categories = emptyList(),
                )
        ) {
            is DomainResult.Success -> created.value
            is DomainResult.Failure -> error("the fixture catalog must be valid")
        }

    private fun seededCurrentRow(): CurrentVersionRow =
        CurrentVersionRow(
            transactionId = TransactionId("tx-read-1"),
            currentVersionId = TransactionVersionId("version-read-1"),
            kind = TransactionKind.EXPENSE,
            occurredAt = occurredAt,
            postings =
                listOf(
                    Posting(PostingId("posting-read-asset"), assetAccountId, Money.ofMinor(-3_580L, cny)),
                    Posting(PostingId("posting-read-expense"), expenseAccountId, Money.ofMinor(3_580L, cny)),
                ),
        )

    private fun expenseInput(requestId: String): ManualExpenseSaveInput =
        ManualExpenseSaveInput(
            ledgerId = ledgerId,
            requestId = RequestId(requestId),
            amount = Money.ofMinor(3_580L, cny),
            categoryId = categoryId,
            paymentAccountId = assetAccountId,
            occurredAt = occurredAt,
            note = "",
            createdAt = Instant.parse("2026-10-24T08:00:00Z"),
            confirmation = ExplicitManualSave,
        )

    /** The dispatch-time rows (the P503ImportBatchPresentationTest row fixture shape, ordinary flow). */
    private fun importReviewRows(): List<ImportReviewRow> =
        listOf(
            importRow("candidate-1"),
            importRow("candidate-2"),
        )

    private fun importRow(candidateId: String): ImportReviewRow =
        ImportReviewRow(
            candidateId = ImportCandidateId(candidateId),
            candidateKind = "ordinary_flow",
            sourceInputRef = "pick-handle-1",
            amountMinor = 3_580L,
            currencyCode = "CNY",
            currencyPrecision = 2,
            occurredAt = "2026-03-15T02:00:00Z",
            directionToken = "expense",
            statusToken = "trade_success",
            fundingState = ImportFundingState.SETTLED,
            completeness = ImportCompleteness.VALID_COMPLETE,
            contentHash = "sha256:fixed-$candidateId",
            candidateStatus = "pending_confirmation",
            requiresConfirmation = true,
            confidence = "high",
            duplicateStatus = null,
            paymentProfileVariant = null,
            paymentProfileAssetLegKindToken = null,
            paymentProfileCreditLegKindToken = null,
        )

    /**
     * The composition-root dispatch-run shape (P503App runImportBatchDispatch): ONE lease covers
     * the pre-phase read, the use-case factory and the sequential per-item loop, and the execute
     * callback runs the real [ConfirmImportCandidate] core chain.
     */
    private fun runImportDispatch(
        facade: P503LedgerFacade,
        landed: MutableList<ImportBatchDispatchResult>,
    ): ImportBatchDispatchRun {
        val prePhase =
            importBatchDispatchPrePhase(
                loadRows = { facade.queryImportReviewRows?.query(facade.ledgerId) ?: ImportReviewRowsResult.Unavailable },
                loadUseCases = { facade.importConfirmUseCases?.invoke() },
            )
        return when (prePhase) {
            is ImportBatchDispatchPrePhase.Failed -> error("the wired rows and use cases must ready the pre-phase: ${prePhase.code}")
            is ImportBatchDispatchPrePhase.Ready ->
                ImportBatchDispatchLoop(
                    items = importItems,
                    rows = prePhase.rows,
                    drafts = importDrafts,
                    confirmedAt = confirmedAt,
                    useCases = prePhase.useCases,
                    ledgerId = facade.ledgerId,
                    parseAmount = facade.parseAmount,
                    defaultCurrency = facade.currency,
                ).run(
                    dispatch = { result -> landed += result },
                    execute = { useCase, request -> useCase.execute(request) },
                )
        }
    }

    private fun expenseFormalCommit(
        request: ManualExpenseRequestSnapshot,
        ids: ConfirmedManualExpenseCommitIds,
    ): DomainResult<ConfirmedManualExpenseCommit> =
        when (
            val created =
                balancedExpenseFormalTransaction(
                    ledgerId = request.ledgerId,
                    amountMinor = request.amount.minorUnits,
                    currency = request.amount.currency,
                    transactionId = ids.expenseIds.transactionId,
                    versionId = ids.expenseIds.versionId,
                    postingSetId = ids.expenseIds.postingSetId,
                    expensePostingId = ids.expenseIds.expensePostingId,
                    paymentPostingId = ids.expenseIds.paymentPostingId,
                    expenseAccountId = expenseAccountId,
                    paymentAccountId = request.paymentAccountId,
                )
        ) {
            is DomainResult.Success -> DomainResult.Success(ConfirmedManualExpenseCommit(ids.confirmationId, created.value))
            is DomainResult.Failure -> created
        }

    private fun importFormalCommit(
        input: ImportCandidateFormalizationInput,
        ids: ImportCommitIds,
    ): DomainResult<ImportFormalCommit> =
        when (
            val created =
                balancedExpenseFormalTransaction(
                    ledgerId = input.ledgerId,
                    amountMinor = input.resolved.amountMinor,
                    currency = CurrencyUnit(input.resolved.currencyCode, input.resolved.currencyPrecision),
                    transactionId = ids.formalIds.transactionId,
                    versionId = ids.formalIds.versionId,
                    postingSetId = ids.formalIds.postingSetId,
                    expensePostingId = ids.formalIds.postingIds[0],
                    paymentPostingId = ids.formalIds.postingIds[1],
                    expenseAccountId = expenseAccountId,
                    paymentAccountId = assetAccountId,
                )
        ) {
            is DomainResult.Success -> DomainResult.Success(ImportFormalCommit(ids.confirmationId, ids.statusHistoryId, created.value))
            is DomainResult.Failure -> created
        }

    /** A balanced two-posting expense formal transaction in exact integer minor units. */
    private fun balancedExpenseFormalTransaction(
        ledgerId: LedgerId,
        amountMinor: Long,
        currency: CurrencyUnit,
        transactionId: TransactionId,
        versionId: TransactionVersionId,
        postingSetId: PostingSetId,
        expensePostingId: PostingId,
        paymentPostingId: PostingId,
        expenseAccountId: AccountId,
        paymentAccountId: AccountId,
    ): DomainResult<FormalTransaction> {
        val postingSet =
            when (
                val created =
                    PostingSet.create(
                        postingSetId,
                        listOf(
                            Posting(expensePostingId, expenseAccountId, Money.ofMinor(amountMinor, currency)),
                            Posting(paymentPostingId, paymentAccountId, Money.ofMinor(-amountMinor, currency)),
                        ),
                    )
            ) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> return DomainResult.Failure(created.violation)
            }
        return FormalTransaction.create(
            transaction = Transaction(transactionId, ledgerId, TransactionKind.EXPENSE, versionId),
            versions = listOf(TransactionVersion(versionId, transactionId, 1, postingSetId, TransactionTimes.collapsed(occurredAt))),
            postingSets = listOf(postingSet),
        )
    }
}

/** One assembled owner plus the handles its tests observe. */
private class JobFixture(
    val owner: LedgerRuntimeOwner<Any>,
    val scope: LedgerLeaseScope,
    val readPort: RecordingCurrentStateReadPort,
    val expenseCommitPort: InMemoryExpenseCommitPort,
    val expenseIdSource: SequentialExpenseIdSource,
    val importCommitPort: InMemoryImportCandidateCommitPort,
    val importIdSource: SequentialImportIdSource,
)

/**
 * The read-port half of the read job: an in-memory row fixture scoped by ledger id (the data
 * layer's ledger-scoped read contract), recording every [loadCurrentRows] ledger id so a test can
 * prove the job's queries never cross ledgers.
 */
private class RecordingCurrentStateReadPort(
    private val scopedLedgerId: LedgerId,
    private val rows: List<CurrentVersionRow>,
) : LedgerCurrentStateReadPort {
    val loadCurrentRowsLedgerIds = mutableListOf<LedgerId>()

    override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> {
        loadCurrentRowsLedgerIds += ledgerId
        return if (ledgerId == scopedLedgerId) rows else emptyList()
    }

    override fun findManualExpenseByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualExpenseCommitRecord? = null

    override fun findManualExpenseByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedExpenseReceipt,
    ): ManualExpenseCommitRecord? = null

    override fun findManualIncomeByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualIncomeCommitRecord? = null

    override fun findManualIncomeByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedIncomeReceipt,
    ): ManualIncomeCommitRecord? = null

    override fun findManualTransferByRequest(
        ledgerId: LedgerId,
        requestId: RequestId,
    ): ManualTransferCommitRecord? = null

    override fun findManualTransferByReceipt(
        ledgerId: LedgerId,
        receipt: ConfirmedTransferReceipt,
    ): ManualTransferCommitRecord? = null
}

/**
 * The expense job's commit port: an IN-MEMORY recording fake that re-implements the frozen
 * commitOnce contract (first request commits once through the formal-creation callback; an
 * equivalent replay returns the original receipt without re-running the callback; a conflicting
 * snapshot is out of scope for this fake). It is NOT the real storage port — the asserted
 * commit records prove the chain above the port, not storage persistence.
 */
private class InMemoryExpenseCommitPort : ConfirmedManualExpenseCommitPort {
    private data class Committed(
        val snapshot: ManualExpenseRequestSnapshot,
        val receipt: ConfirmedExpenseReceipt,
    )

    private val requests = mutableMapOf<ManualExpenseRequestIdentity, Committed>()
    val receipts = mutableListOf<ConfirmedExpenseReceipt>()
    var commitOnceInvocations = 0
        private set
    var formalFactoryInvocations = 0
        private set

    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createdAt: Instant,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult {
        commitOnceInvocations += 1
        val existing = requests[identity]
        if (existing != null) {
            return if (existing.snapshot == requestSnapshot) {
                ConfirmedManualExpenseResult.NoChange(existing.receipt)
            } else {
                ConfirmedManualExpenseResult.RequestIdentityConflict(identity)
            }
        }
        formalFactoryInvocations += 1
        val commit =
            when (val created = createFormalTransaction()) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> return ConfirmedManualExpenseResult.Rejected(created.violation)
            }
        val receipt = ConfirmedExpenseReceipt(commit.confirmationId, commit.transaction.transaction.id)
        requests[identity] = Committed(requestSnapshot, receipt)
        receipts += receipt
        return ConfirmedManualExpenseResult.Created(receipt)
    }
}

/** Sequential id mint for the expense chain; counts every allocation. */
private class SequentialExpenseIdSource : ConfirmedManualExpenseIdSource {
    var invocations = 0
        private set

    override fun next(): ConfirmedManualExpenseCommitIds {
        invocations += 1
        val ordinal = invocations
        return ConfirmedManualExpenseCommitIds(
            confirmationId = ConfirmationId("confirmation-expense-$ordinal"),
            expenseIds =
                AssetPaidOrdinaryExpenseIds(
                    transactionId = TransactionId("tx-expense-$ordinal"),
                    versionId = TransactionVersionId("version-expense-$ordinal"),
                    postingSetId = PostingSetId("posting-set-expense-$ordinal"),
                    expensePostingId = PostingId("posting-expense-$ordinal"),
                    paymentPostingId = PostingId("posting-payment-$ordinal"),
                ),
        )
    }
}

/**
 * The import job's commit port: an IN-MEMORY TEST FAKE for the frozen [ImportCandidateCommitPort]
 * contract — the first request wins the claim (invoking the id allocation and the formal-creation
 * callback exactly once), an equivalent replay returns the original receipt without re-running the
 * callbacks, a conflicting replay is out of scope for this fake. This is NOT the real spine store
 * (ledger-data): no import-spine persistence or transactional claim behavior is claimed here.
 */
private class InMemoryImportCandidateCommitPort(
    private val resolved: ImportResolvedSourceFacts,
) : ImportCandidateCommitPort {
    private data class Claim(
        val snapshot: ImportCandidateDecisionSnapshot,
        val receipt: ImportReceipt,
    )

    private val claims = mutableMapOf<ImportRequestIdentity, Claim>()
    val receipts = mutableListOf<ImportReceipt>()
    var commitOnceInvocations = 0
        private set
    var formalFactoryInvocations = 0
        private set

    override fun commitOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        allocateIds: () -> ImportCommitIds,
        catalog: LedgerCatalog,
        createFormalTransaction: (ImportCandidateFormalizationInput, ImportCommitIds) -> DomainResult<ImportFormalCommit>,
    ): ImportCandidateDecisionResult {
        commitOnceInvocations += 1
        val existing = claims[identity]
        if (existing != null) {
            if (existing.snapshot != snapshot) {
                error("a conflicting replay is out of scope for this fake")
            }
            return ImportCandidateDecisionResult.NoChange(existing.receipt, SPINE_NO_CHANGE_REASON_CODE)
        }
        val ids = allocateIds()
        formalFactoryInvocations += 1
        val input = ImportCandidateFormalizationInput(identity.ledgerId, resolved, requireNotNull(snapshot.confirmDecisionFields))
        val commit =
            when (val created = createFormalTransaction(input, ids)) {
                is DomainResult.Success -> created.value
                is DomainResult.Failure -> return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.domainValidationFailed(snapshot.candidateId))
            }
        val receipt =
            ImportReceipt(
                requestId = identity.requestId,
                sourceId = null,
                evidenceId = null,
                candidateId = snapshot.candidateId,
                confirmationId = commit.confirmationId,
                transactionId = commit.transaction.transaction.id,
            )
        claims[identity] = Claim(snapshot, receipt)
        receipts += receipt
        return ImportCandidateDecisionResult.Accepted(receipt, emptyList())
    }

    override fun commitRejectOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        allocateStatusId: () -> ImportStatusHistoryId,
    ): ImportCandidateDecisionResult = error("the batch confirm path never rejects through this fake")
}

/** Sequential id mint for the import confirm chain; counts every allocation. */
private class SequentialImportIdSource : ImportIdSource {
    var invocations = 0
        private set

    override fun next(): ImportCommitIds {
        invocations += 1
        val ordinal = invocations
        return ImportCommitIds(
            confirmationId = ImportConfirmationId("confirmation-import-$ordinal"),
            statusHistoryId = ImportStatusHistoryId("status-import-$ordinal"),
            formalIds =
                ImportFormalIds(
                    transactionId = TransactionId("tx-import-$ordinal"),
                    versionId = TransactionVersionId("version-import-$ordinal"),
                    postingSetId = PostingSetId("posting-set-import-$ordinal"),
                    postingIds =
                        listOf(
                            PostingId("posting-import-expense-$ordinal"),
                            PostingId("posting-import-payment-$ordinal"),
                        ),
                ),
        )
    }
}

/** The import pre-phase's row source: a fixed hand-built list (fail-loud defaults stay). */
private class FixedImportReviewReadPort(
    private val rows: List<ImportReviewRow>,
) : ImportReviewReadPort {
    override fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow> = rows
}
