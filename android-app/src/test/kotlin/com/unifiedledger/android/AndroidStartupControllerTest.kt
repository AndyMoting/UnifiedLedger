package com.unifiedledger.android

import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryManualExpenseOptions
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.P503StartupState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * P5-04.4 S2/S3: JVM fail-closed evidence for [AndroidStartupController]. No Robolectric: the
 * controller's `openDatabase` (returns a [CloseableLedgerGraph]) and `logFailure` are both
 * injected, so the state machine and resource-safety can be exercised without an Android
 * framework. Symmetric to the desktop controller tests plus the retry-close assertions (S3).
 *
 * P0 hotfix (defect 2): [AndroidStartupController.start] now launches the blocking open off the
 * caller thread. Most tests inject an unconfined scope/dispatcher so the launch runs eagerly on
 * the test thread and the state assertions stay deterministic without sleeps; the three dedicated
 * tests below use real single-thread dispatchers to prove the thread hop, the synchronous
 * reentrancy guard, and the dispose/teardown orphan guard under a genuinely asynchronous start.
 *
 * The class-level JUnit [Timeout] rule turns a would-be hang into a failure: if a regression
 * makes an injected open block forever (e.g. the latched reentrancy test below), the test FAILS
 * at the rule deadline instead of hanging the suite.
 */
class AndroidStartupControllerTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    // Thread-safe and safe to iterate while a background/state thread appends (the async tests below
// record failures off the test thread while it polls).
    private var logged: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun controller(open: () -> CloseableLedgerGraph): AndroidStartupController = AndroidStartupController(openDatabase = open, logFailure = { failure -> logged += failure.toString() }, startScope = unconfinedScope(), backgroundDispatcher = Dispatchers.Unconfined)

    /**
     * An eagerly-executing scope/dispatcher pair (the unconfined dispatcher runs the launch body
     * on the calling thread until a real suspension), so a test's assertions after `start()`
     * observe the completed transition deterministically.
     */
    private fun unconfinedScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun injectedOpenFailureGoesToErrorThenRetryReachesReady() {
        var shouldFail = true
        var closeCount = 0
        val controller =
            controller {
                if (shouldFail) {
                    throw IllegalStateException("injected open failure")
                }
                CloseableLedgerGraph(facade = fakeFacade(), close = { closeCount += 1 })
            }

        controller.start()
        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.ledger)

        shouldFail = false
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.ledger != null)
        assertTrue(logged.any { it.contains("injected open failure") })
    }

    @Test
    fun repeatedInjectedFailureStaysFailClosedWithNoGraphExposed() {
        var closeCount = 0
        val controller =
            controller {
                throw IllegalStateException("injected open failure")
            }

        controller.start()
        controller.start()

        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.ledger)
        assertEquals(0, closeCount)
    }

    @Test
    fun corruptionShapedOpenFailureMapsToStartupErrorAndRetryReachesReady() {
        // P5-04.5-FOUND-001 T-B: the real fixed corruption type is internal to ledger-data and
        // android framework SQLite exceptions are android.jar stubs without Robolectric, so the
        // corruption shape is injected as a plain RuntimeException subclass; D-5 freezes the
        // StartupError mapping as type-independent.
        val failure = SimulatedCorruptionOpenFailure("injected corruption open failure")
        var shouldFail = true
        var closeCount = 0
        val loggedFailures = mutableListOf<Exception>()
        val controller =
            AndroidStartupController(
                openDatabase = {
                    if (shouldFail) {
                        throw failure
                    }
                    CloseableLedgerGraph(facade = fakeFacade(), close = { closeCount += 1 })
                },
                logFailure = { loggedFailures += it },
                startScope = unconfinedScope(),
                backgroundDispatcher = Dispatchers.Unconfined,
            )

        controller.start()
        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.ledger)
        assertSame(failure, loggedFailures.single())
        assertEquals(0, closeCount)

        // T-D retry path: once the "file is manually restored" (the injected failure stops),
        // the retry re-invokes openDatabase and reaches Ready.
        shouldFail = false
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.ledger != null)
    }

    @Test
    fun repeatedCorruptionShapedFailureStaysFailClosedAndRetriesOpen() {
        val failure = SimulatedCorruptionOpenFailure("corruption persists across retries")
        var openCount = 0
        val loggedFailures = mutableListOf<Exception>()
        val controller =
            AndroidStartupController(
                openDatabase = {
                    openCount += 1
                    throw failure
                },
                logFailure = { loggedFailures += it },
                startScope = unconfinedScope(),
                backgroundDispatcher = Dispatchers.Unconfined,
            )

        controller.start()
        controller.start()

        // T-D retry path: while the file is still corrupted, every retry surfaces StartupError
        // again, keeps the facade unexposed and re-invokes openDatabase (manual retry only).
        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.ledger)
        assertEquals(2, openCount)
        assertEquals(listOf<Exception>(failure, failure), loggedFailures)
    }

    @Test
    fun corruptionShapedAndGenericOpenFailuresMapToTheSameStartupErrorState() {
        // D-5: corruption, schema/migration and IO/permission-shaped failures all ride the same
        // "openDatabase threw" path, so the mapping must not branch on the exception type.
        val shapes =
            listOf(
                SimulatedCorruptionOpenFailure("corruption shape"),
                IllegalStateException("schema/migration shape"),
                IllegalArgumentException("io/permission shape"),
            )
        for (failure in shapes) {
            val loggedFailures = mutableListOf<Exception>()
            val controller =
                AndroidStartupController(
                    openDatabase = { throw failure },
                    logFailure = { loggedFailures += it },
                    startScope = unconfinedScope(),
                    backgroundDispatcher = Dispatchers.Unconfined,
                )

            controller.start()

            assertEquals(P503StartupState.StartupError, controller.state)
            assertNull(controller.ledger)
            assertSame(failure, loggedFailures.single())
        }
    }

    @Test
    fun retryClosesPriorConnectionAndKeepsSingleActive() {
        var openCount = 0
        var closeCount = 0
        val controller =
            controller {
                openCount += 1
                CloseableLedgerGraph(facade = fakeFacade(), close = { closeCount += 1 })
            }

        // First success holds graph #1 as the single active connection.
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.ledger != null)
        assertEquals(1, openCount)
        assertEquals(0, closeCount)

        // Retry closes the previous connection before rebuilding; still exactly one active.
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.ledger != null)
        assertEquals(2, openCount)
        assertEquals(1, closeCount)
    }

    @Test
    fun startWhileStartingIsIgnoredWithoutRebuildOrClose() {
        var openCount = 0
        var closeCount = 0
        lateinit var controller: AndroidStartupController
        controller =
            AndroidStartupController(
                openDatabase = {
                    openCount += 1
                    // A reentrant start while the outer start() is still in the Starting
                    // transition is dropped by the in-flight guard: it neither rebuilds nor
                    // double-closes a connection.
                    controller.start()
                    CloseableLedgerGraph(facade = fakeFacade(), close = { closeCount += 1 })
                },
                logFailure = { failure -> logged += failure.toString() },
                startScope = unconfinedScope(),
                backgroundDispatcher = Dispatchers.Unconfined,
            )

        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertEquals(1, openCount)
        assertEquals(0, closeCount)
    }

    // ---------------------------------------------------------------- P0 hotfix defect 2

    @Test
    fun theBlockingOpenRunsOnTheInjectedBackgroundDispatcherNotTheCallerThread() {
        // P0 hotfix (defect 2): the legacy-upgrade copy must not run on the main thread. Record
        // the thread that actually executes openDatabase and assert it is the injected background
        // thread, never the caller/main thread. Deterministic: a real single-thread background
        // dispatcher and a bounded await on the observed state (no sleeps).
        val backgroundExecutor = newNamedExecutor("test-background-open")
        val stateExecutor = newNamedExecutor("test-state-writer")
        val callerThread = Thread.currentThread()
        val openThreads = mutableListOf<Thread>()
        try {
            val controller =
                AndroidStartupController(
                    openDatabase = {
                        openThreads += Thread.currentThread()
                        CloseableLedgerGraph(facade = fakeFacade(), close = {})
                    },
                    logFailure = { failure -> logged += failure.toString() },
                    startScope = CoroutineScope(stateExecutor.asCoroutineDispatcher()),
                    backgroundDispatcher = backgroundExecutor.asCoroutineDispatcher(),
                )

            controller.start()
            awaitState(controller, P503StartupState.Ready)

            assertEquals(1, openThreads.size)
            assertNotEquals(callerThread, openThreads.single(), "the blocking open must not run on the caller/main thread")
            assertEquals("test-background-open", openThreads.single().name)
        } finally {
            backgroundExecutor.shutdownNow()
            stateExecutor.shutdownNow()
        }
    }

    @Test
    fun startWhileStartingIsDroppedSynchronouslyWhenTheOpenIsStillInFlight() {
        // P0 hotfix (defect 2): the reentrancy guard must remain a SYNCHRONOUS caller-thread
        // decision even though start() is now asynchronous. The open blocks on a latch, so the
        // second start() provably runs while the first open is in flight; it must neither
        // rebuild nor double-close.
        val backgroundExecutor = newNamedExecutor("test-background-open")
        val stateExecutor = newNamedExecutor("test-state-writer")
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        var openCount = 0
        var closeCount = 0
        try {
            val controller =
                AndroidStartupController(
                    openDatabase = {
                        openCount += 1
                        openEntered.countDown()
                        // Bounded so a regression that never releases (or a reverted guard that
                        // blocks the releasing thread) FAILS at the class Timeout rule instead of
                        // hanging the suite.
                        releaseOpen.await(30, TimeUnit.SECONDS)
                        CloseableLedgerGraph(facade = fakeFacade(), close = { closeCount += 1 })
                    },
                    logFailure = { failure -> logged += failure.toString() },
                    startScope = CoroutineScope(stateExecutor.asCoroutineDispatcher()),
                    backgroundDispatcher = backgroundExecutor.asCoroutineDispatcher(),
                )

            controller.start()
            assertTrue(openEntered.await(30, TimeUnit.SECONDS), "the background open did not start")
            // The first open is provably in flight and the state is Starting: this second tap is
            // the synchronous-guard case and must be dropped without touching the owner.
            assertEquals(P503StartupState.Starting, controller.state)
            controller.start()

            releaseOpen.countDown()
            awaitState(controller, P503StartupState.Ready)

            assertEquals(1, openCount, "the dropped retry must not rebuild the graph")
            assertEquals(0, closeCount, "the dropped retry must not double-close")
        } finally {
            releaseOpen.countDown()
            backgroundExecutor.shutdownNow()
            stateExecutor.shutdownNow()
        }
    }

    @Test
    fun retryAfterBackgroundFailureReachesReady() {
        // P0 hotfix (defect 2): a failed background start must reach StartupError, and a later
        // Retry (the only retry path) must reach Ready — the real "retry after failure" coverage
        // under the asynchronous start.
        var shouldFail = true
        val backgroundExecutor = newNamedExecutor("test-background-open")
        val stateExecutor = newNamedExecutor("test-state-writer")
        try {
            val controller =
                AndroidStartupController(
                    openDatabase = {
                        if (shouldFail) throw IllegalStateException("injected background open failure")
                        CloseableLedgerGraph(facade = fakeFacade(), close = {})
                    },
                    logFailure = { failure -> logged += failure.toString() },
                    startScope = CoroutineScope(stateExecutor.asCoroutineDispatcher()),
                    backgroundDispatcher = backgroundExecutor.asCoroutineDispatcher(),
                )

            controller.start()
            awaitState(controller, P503StartupState.StartupError)
            assertNull(controller.ledger)
            assertTrue(logged.any { it.contains("injected background open failure") })

            shouldFail = false
            controller.start()
            awaitState(controller, P503StartupState.Ready)
            assertTrue(controller.ledger != null)
        } finally {
            backgroundExecutor.shutdownNow()
            stateExecutor.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- MUST FIX 2 lifecycle

    @Test
    fun aFailedOpenAfterDisposeStillLogsTheCause() {
        // MUST FIX D1: the disposed (teardown-race) branch must not swallow the failure cause. The
        // open blocks on a latch, dispose runs while it is in flight, and the open then fails; the
        // cause must still be logged even though no state is published.
        val backgroundExecutor = newNamedExecutor("test-background-open")
        val stateExecutor = newNamedExecutor("test-state-writer")
        val startScope = CoroutineScope(stateExecutor.asCoroutineDispatcher())
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        try {
            val controller =
                AndroidStartupController(
                    openDatabase = {
                        openEntered.countDown()
                        releaseOpen.await(30, TimeUnit.SECONDS)
                        throw IllegalStateException("failure after teardown")
                    },
                    startScope = startScope,
                    logFailure = { failure -> logged += failure.toString() },
                    backgroundDispatcher = backgroundExecutor.asCoroutineDispatcher(),
                )

            controller.start()
            assertTrue(openEntered.await(30, TimeUnit.SECONDS), "the background open did not start")
            controller.dispose()
            releaseOpen.countDown()

            val deadline = System.nanoTime() + 30_000_000_000L
            while (logged.none { it.contains("failure after teardown") } && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertTrue(
                logged.any { it.contains("failure after teardown") },
                "a failure during a teardown race must still be logged, got: $logged",
            )
        } finally {
            releaseOpen.countDown()
            startScope.cancel()
            backgroundExecutor.shutdownNow()
            stateExecutor.shutdownNow()
        }
    }

    @Test
    fun cancelScopeAndDisposeWhileOpenInFlightClosesTheGraphInsteadOfOrphaningIt() {
        // MUST FIX 2: the composition can be torn down mid-open (rotation during the multi-second
        // legacy copy). Compose does both things on teardown: it cancels rememberCoroutineScope()
        // AND runs the DisposableEffect's onDispose. This test mirrors both. The open blocks on a
        // latch, so teardown provably happens while the open is in flight; the open then completes
        // and the controller must close it (NonCancellable decision + dispose) rather than orphan
        // the driver.
        val backgroundExecutor = newNamedExecutor("test-background-open")
        val stateExecutor = newNamedExecutor("test-state-writer")
        val startScope = CoroutineScope(stateExecutor.asCoroutineDispatcher())
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        var openCount = 0
        var closeCount = 0
        try {
            val controller =
                AndroidStartupController(
                    openDatabase = {
                        openCount += 1
                        openEntered.countDown()
                        releaseOpen.await(30, TimeUnit.SECONDS)
                        CloseableLedgerGraph(facade = fakeFacade(), close = { closeCount += 1 })
                    },
                    startScope = startScope,
                    logFailure = { failure -> logged += failure.toString() },
                    backgroundDispatcher = backgroundExecutor.asCoroutineDispatcher(),
                )

            controller.start()
            assertTrue(openEntered.await(30, TimeUnit.SECONDS), "the background open did not start")
            // Compose teardown order: cancel the scope, then run onDispose.
            startScope.cancel()
            controller.dispose()
            releaseOpen.countDown()

            // The completed open must be closed, not orphaned: wait until the close is observed.
            val deadline = System.nanoTime() + 30_000_000_000L
            while (closeCount == 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(1, openCount)
            assertEquals(1, closeCount, "a graph opened after teardown must be closed, not orphaned")
            assertNull(controller.ledger)
        } finally {
            releaseOpen.countDown()
            startScope.cancel()
            backgroundExecutor.shutdownNow()
            stateExecutor.shutdownNow()
        }
    }

    @Test
    fun disposeAfterReadyClosesTheActiveGraphAndIsIdempotent() {
        // MUST FIX 2: the ordinary teardown path (composition leaves after Ready) must close the
        // active graph; a second dispose is a no-op.
        var closeCount = 0
        val controller =
            controller {
                CloseableLedgerGraph(facade = fakeFacade(), close = { closeCount += 1 })
            }

        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertEquals(0, closeCount)

        controller.dispose()
        assertEquals(1, closeCount)

        controller.dispose()
        assertEquals(1, closeCount, "dispose must be idempotent")
    }

    private fun newNamedExecutor(threadName: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            // MUST FIX D2: daemon threads, so a stuck test cannot pin the JVM after the JUnit
            // Timeout rule abandons it.
            Thread(runnable, threadName).apply { isDaemon = true }
        }

    /**
     * Awaits [expected] on the controller deterministically: the state is written on the injected
     * state executor, so this blocks the test thread with a bounded [withTimeout] poll (no
     * sleeps) and fails with a clear message if the state never lands.
     */
    private fun awaitState(
        controller: AndroidStartupController,
        expected: P503StartupState,
    ) {
        runBlocking {
            withTimeout(30_000) {
                while (controller.state != expected) {
                    delay(1)
                }
            }
        }
    }

    /**
     * A minimal but valid [P503LedgerFacade] for the controller tests. The controller only
     * stores/returns the facade; none of its methods are exercised here, so the collaborator
     * stubs never run.
     */
    private fun fakeFacade(): P503LedgerFacade {
        val ledgerId = LedgerId("ledger-local-test")
        val currency = CurrencyUnit("CNY", 2)
        val catalog =
            (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success)
                .value
        val readPort =
            object : LedgerCurrentStateReadPort {
                override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

                override fun findManualExpenseByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): ManualExpenseCommitRecord? = null

                override fun findManualExpenseByReceipt(
                    ledgerId: LedgerId,
                    receipt: com.unifiedledger.application.ConfirmedExpenseReceipt,
                ): ManualExpenseCommitRecord? = null

                override fun findManualIncomeByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): com.unifiedledger.application.ManualIncomeCommitRecord? = null

                override fun findManualIncomeByReceipt(
                    ledgerId: LedgerId,
                    receipt: com.unifiedledger.application.ConfirmedIncomeReceipt,
                ): com.unifiedledger.application.ManualIncomeCommitRecord? = null

                override fun findManualTransferByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): com.unifiedledger.application.ManualTransferCommitRecord? = null

                override fun findManualTransferByReceipt(
                    ledgerId: LedgerId,
                    receipt: com.unifiedledger.application.ConfirmedTransferReceipt,
                ): com.unifiedledger.application.ManualTransferCommitRecord? = null
            }
        val resolver = ResolveManualExpenseCommitStatus(readPort)
        val commitPort =
            ConfirmedManualExpenseCommitPort { _, _, _ ->
                ConfirmedManualExpenseResult.Rejected(DomainViolation.InvalidOrdinaryExpense)
            }
        val tracker = CommitOnceInvocationTracker(commitPort)
        val idSource =
            ConfirmedManualExpenseIdSource {
                error("commit id source must not be used in startup tests")
            }
        val transactionFactory =
            ConfirmedExpenseTransactionFactory { _, _ ->
                error("transaction factory must not be used in startup tests")
            }
        val executeConfirmed = ExecuteConfirmedManualExpense(tracker, idSource, transactionFactory)
        val executeSave = ExecuteManualExpenseSave(executeConfirmed)
        val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)

        return P503LedgerFacade(
            ledgerId = ledgerId,
            currency = currency,
            catalog = catalog,
            parseAmount = ParseManualExpenseAmount(),
            baseOptionsProvider = QueryManualExpenseOptions(ledgerId, catalog),
            baseQueryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, catalog),
            resolveCommitStatus = resolver,
            submitExpense = submission,
            requestIdSource = ManualExpenseRequestIdSource { RequestId("request-startup-test") },
            ledgerClock = LedgerClock { Clock.System.now() },
            baseSummarizeActivity = SummarizeLedgerActivity(catalog),
        )
    }
}

/**
 * P5-04.5-FOUND-001 T-B: the real fixed corruption type (ledger-data's internal
 * LedgerDatabaseCorruptionException) is deliberately invisible across modules, and Android
 * SQLite exception types are android.jar stubs without Robolectric; a plain RuntimeException
 * subclass carries the corruption shape, because D-5 freezes the StartupError mapping as
 * type-independent.
 */
private class SimulatedCorruptionOpenFailure(
    message: String,
) : RuntimeException(message)
