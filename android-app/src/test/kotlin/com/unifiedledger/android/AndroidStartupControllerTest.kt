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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * P5-04.4 S2/S3: JVM fail-closed evidence for [AndroidStartupController]. No Robolectric: the
 * controller's `openDatabase` (returns a [CloseableLedgerGraph]) and `logFailure` are both
 * injected, so the state machine and resource-safety can be exercised without an Android
 * framework. Symmetric to the desktop controller tests plus the retry-close assertions (S3).
 */
class AndroidStartupControllerTest {
    private var logged: MutableList<String> = mutableListOf()

    private fun controller(open: () -> CloseableLedgerGraph): AndroidStartupController = AndroidStartupController(openDatabase = open, logFailure = { failure -> logged += failure.toString() })

    @Test
    fun injectedOpenFailureGoesToErrorThenRetryReachesReady() {
        var shouldFail = true
        var closeCount = 0
        val controller =
            controller {
                if (shouldFail) {
                    throw IllegalStateException("injected open failure")
                }
                CloseableLedgerGraph(fakeFacade()) { closeCount += 1 }
            }

        controller.start()
        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.facade)

        shouldFail = false
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.facade != null)
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
        assertNull(controller.facade)
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
                    CloseableLedgerGraph(fakeFacade()) { closeCount += 1 }
                },
                logFailure = { loggedFailures += it },
            )

        controller.start()
        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.facade)
        assertSame(failure, loggedFailures.single())
        assertEquals(0, closeCount)

        // T-D retry path: once the "file is manually restored" (the injected failure stops),
        // the retry re-invokes openDatabase and reaches Ready.
        shouldFail = false
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.facade != null)
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
            )

        controller.start()
        controller.start()

        // T-D retry path: while the file is still corrupted, every retry surfaces StartupError
        // again, keeps the facade unexposed and re-invokes openDatabase (manual retry only).
        assertEquals(P503StartupState.StartupError, controller.state)
        assertNull(controller.facade)
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
                )

            controller.start()

            assertEquals(P503StartupState.StartupError, controller.state)
            assertNull(controller.facade)
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
                CloseableLedgerGraph(fakeFacade()) { closeCount += 1 }
            }

        // First success holds graph #1 as the single active connection.
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.facade != null)
        assertEquals(1, openCount)
        assertEquals(0, closeCount)

        // Retry closes the previous connection before rebuilding; still exactly one active.
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertTrue(controller.facade != null)
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
                    CloseableLedgerGraph(fakeFacade()) { closeCount += 1 }
                },
                logFailure = { failure -> logged += failure.toString() },
            )

        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        assertEquals(1, openCount)
        assertEquals(0, closeCount)
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
            optionsProvider = QueryManualExpenseOptions(ledgerId, catalog),
            queryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, catalog),
            resolveCommitStatus = resolver,
            submitExpense = submission,
            requestIdSource = ManualExpenseRequestIdSource { RequestId("request-startup-test") },
            ledgerClock = LedgerClock { Clock.System.now() },
            summarizeActivity = SummarizeLedgerActivity(catalog),
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
