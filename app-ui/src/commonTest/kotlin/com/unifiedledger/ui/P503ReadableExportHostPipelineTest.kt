package com.unifiedledger.ui

import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.QueryLedgerFlowPage
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.export.readable.LedgerRuntimeLeasePort
import com.unifiedledger.application.export.readable.ReadableLedgerExport
import com.unifiedledger.application.export.readable.ReadableLedgerExportFailure
import com.unifiedledger.application.export.readable.ReadableLedgerExportResult
import com.unifiedledger.domain.LedgerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P8-05 批 b (D-331 spec sections 4 批 b / D-E9-D-E11): JVM tests for [runReadableExport], the
 * host's only product path that runs the readable export (the [runBackupExport] pipeline tests'
 * shape). The tests drive the real function with an injected export factory (the function seam)
 * and recording collaborators, asserting each of its load-bearing decisions: the export runs off
 * the caller thread, the result lands on the scope's own dispatcher, a superseded generation
 * discards the result, a throwing export still lands a typed Failed, and the cancel signal reads
 * the host's live state at run start.
 */
class P503ReadableExportHostPipelineTest {
    @Test
    fun aThrowingExportStillLandsATypedFailureInsteadOfStrandingTheSurface() {
        // A fail-loud sink (or any escape the use case itself does not convert) must not kill the
        // coroutine before the landing dispatch; the catch maps it to the typed WRITE_FAILED.
        val landed = CompletableDeferred<ReadableLedgerExportResult>()

        runBlocking {
            runReadableExport(
                scope = this,
                generation = 1,
                request = request(),
                newExport = { _, _ ->
                    throw IllegalStateException("injected sink failure")
                },
                cancelRequested = { false },
                isCurrentGeneration = { true },
                onProgress = { _, _ -> },
                land = { landed.complete(it) },
            )
            val result = withTimeoutOrNull(30_000) { landed.await() }
            val failure = assertIs<ReadableLedgerExportResult.Failed>(result, "a throwing export must land a typed Failed")
            assertEquals(ReadableLedgerExportFailure.WRITE_FAILED, failure.reason)
        }
    }

    @Test
    fun aSuccessfulExportLandsItsResultWhenTheGenerationIsCurrent() {
        val landed = CompletableDeferred<ReadableLedgerExportResult>()
        val succeeded = ReadableLedgerExportResult.Succeeded(exportedRows = 7L, totalRows = 7, suggestedFileName = "unifiedledger-export-20261115.xlsx")

        runBlocking {
            runReadableExport(
                scope = this,
                generation = 1,
                request = request(),
                newExport = { _, _ -> exportReturning(succeeded) },
                cancelRequested = { false },
                isCurrentGeneration = { true },
                onProgress = { _, _ -> },
                land = { landed.complete(it) },
            )
            assertEquals(succeeded, withTimeoutOrNull(30_000) { landed.await() })
        }
    }

    @Test
    fun aSupersededGenerationDiscardsTheResult() {
        // The generation discard is load-bearing (ReadableLedgerExportResult carries no generation).
        var landedCount = 0

        runBlocking {
            runReadableExport(
                scope = this,
                generation = 1,
                request = request(),
                newExport = { _, _ -> exportReturning(ReadableLedgerExportResult.Cancelled) },
                cancelRequested = { false },
                // The captured generation is no longer current.
                isCurrentGeneration = { false },
                onProgress = { _, _ -> },
                land = { landedCount += 1 },
            )
            // Let the launched work settle; nothing must land.
            withTimeoutOrNull(500) {
                while (true) {
                    delay(10)
                }
            }
        }
        assertEquals(0, landedCount, "a result captured under a superseded generation must be discarded")
    }

    @Test
    fun theExportRunsOffTheCallerThreadAndTheResultLandsOnTheScopeDispatcher() {
        // Pins BOTH the off-thread export and the main-thread landing hop (the runBackupExport
        // test's decisive-assertion shape): comparing the export thread against the LANDING thread
        // is what makes a removed Dispatchers.Default go red.
        val exportThreads = mutableListOf<String>()
        val landingThreads = mutableListOf<String>()
        val scopeDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main-scope").apply { isDaemon = true } }
        val callerName = Thread.currentThread().name

        try {
            runBlocking {
                val scope = CoroutineScope(scopeDispatcher.asCoroutineDispatcher())
                val landed = CompletableDeferred<Unit>()
                runReadableExport(
                    scope = scope,
                    generation = 1,
                    request = request(),
                    newExport = { _, _ ->
                        exportRecordingThread(exportThreads, ReadableLedgerExportResult.Cancelled)
                    },
                    cancelRequested = { false },
                    isCurrentGeneration = { true },
                    onProgress = { _, _ -> },
                    land = {
                        landingThreads += Thread.currentThread().name
                        landed.complete(Unit)
                    },
                )
                assertTrue(withTimeoutOrNull(30_000) { landed.await() } != null, "the result did not land")
            }
        } finally {
            scopeDispatcher.shutdownNow()
        }

        assertEquals(1, exportThreads.size)
        assertEquals(1, landingThreads.size)
        val exportThread = exportThreads.single()
        val landingThread = landingThreads.single()

        // The landing must be on the scope (composition main) dispatcher.
        assertTrue(
            landingThread.startsWith("test-main-scope"),
            "the landing must hop onto the scope dispatcher, was $landingThread",
        )
        // The export must NOT be on the caller thread ...
        assertTrue(exportThread != callerName, "the export must not run on the caller thread")
        // ... and decisively NOT on the scope (composition main) dispatcher. This is the assertion
        // that fails if Dispatchers.Default is removed from runReadableExport.
        assertFalse(
            exportThread.startsWith("test-main-scope"),
            "the export must not run on the scope/composition dispatcher, was $exportThread",
        )
        assertTrue(
            exportThread != landingThread,
            "the export thread and the landing thread must differ (the export is off the scope dispatcher)",
        )
    }

    @Test
    fun theCancelSignalReadsTheHostsLiveStateAtRunStart() {
        // D-E10's signal-triggered Cancelled: the run pipeline hands the use case the host's
        // live-state lambda (read per page by the walk), not a frozen snapshot.
        val seen = mutableListOf<Boolean>()
        val landed = CompletableDeferred<ReadableLedgerExportResult>()
        var cancelRequested = false

        runBlocking {
            runReadableExport(
                scope = this,
                generation = 1,
                request = request(),
                newExport = { cancellation, _ ->
                    seen += cancellation.isCancelled()
                    cancelRequested = true
                    exportRecordingThread(mutableListOf(), ReadableLedgerExportResult.Cancelled)
                },
                cancelRequested = { cancelRequested },
                isCurrentGeneration = { true },
                onProgress = { _, _ -> },
                land = { landed.complete(it) },
            )
            assertTrue(withTimeoutOrNull(30_000) { landed.await() } != null, "the result did not land")
        }
        assertEquals(listOf(false), seen, "the signal must be captured BEFORE the host records the request")
        // The same lambda observes the flipped state (the per-page read shape).
        assertTrue(cancelRequested)
    }

    @Test
    fun theProgressLandingsCarryPureCountersOnly() {
        // D-E9: the progress listener bridges the use case's counters onto the host's dispatch.
        val landed = CompletableDeferred<ReadableLedgerExportResult>()
        var reportedExportedRows = -1L
        var reportedTotalRows = -1

        runBlocking {
            runReadableExport(
                scope = this,
                generation = 1,
                request = request(),
                newExport = { _, progressListener ->
                    progressListener?.onProgress(exportedRows = 42L, totalRows = 99)
                    exportReturning(ReadableLedgerExportResult.Cancelled)
                },
                cancelRequested = { false },
                isCurrentGeneration = { true },
                onProgress = { exportedRows, totalRows ->
                    reportedExportedRows = exportedRows
                    reportedTotalRows = totalRows
                },
                land = { landed.complete(it) },
            )
            assertTrue(withTimeoutOrNull(30_000) { landed.await() } != null, "the result did not land")
        }
        assertEquals(42L, reportedExportedRows)
        assertEquals(99, reportedTotalRows)
    }

    @Test
    fun theProgressDispatchHopsOntoTheScopeDispatcherNotTheExportWorker() {
        // F-1 fix (the KDoc's decision 2): the use case invokes the listener on the export
        // worker, but the progress dispatch (a multi-field state write) must run on the scope
        // (composition main) dispatcher. The decisive shape mirrors the result-hop assertion:
        // if the scope.launch hop is removed, the recorded thread reverts to the export worker
        // (a Default worker) and this goes red.
        val exportThreads = mutableListOf<String>()
        val progressThreads = mutableListOf<String>()
        val landed = CompletableDeferred<ReadableLedgerExportResult>()
        val scopeDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main-scope").apply { isDaemon = true } }

        try {
            runBlocking {
                val scope = CoroutineScope(scopeDispatcher.asCoroutineDispatcher())
                runReadableExport(
                    scope = scope,
                    generation = 1,
                    request = request(),
                    newExport = { _, progressListener ->
                        progressListener?.onProgress(exportedRows = 7L, totalRows = 9)
                        exportRecordingThread(exportThreads, ReadableLedgerExportResult.Cancelled)
                    },
                    cancelRequested = { false },
                    isCurrentGeneration = { true },
                    onProgress = { _, _ ->
                        progressThreads += Thread.currentThread().name
                    },
                    land = { landed.complete(it) },
                )
                assertTrue(withTimeoutOrNull(30_000) { landed.await() } != null, "the result did not land")
            }
        } finally {
            scopeDispatcher.shutdownNow()
        }

        assertEquals(1, progressThreads.size)
        val progressThread = progressThreads.single()
        val exportThread = exportThreads.single()
        assertTrue(
            progressThread.startsWith("test-main-scope"),
            "the progress dispatch must hop onto the scope dispatcher, was $progressThread",
        )
        assertFalse(
            progressThread == exportThread,
            "the progress dispatch must not run on the export worker thread, was $progressThread",
        )
        assertFalse(
            progressThread.startsWith("Default"),
            "the progress dispatch must never run on a Default worker, was $progressThread",
        )
    }

    private fun request(): ReadableExportRequest = ReadableExportRequest(com.unifiedledger.domain.LedgerId("ledger-local-test"))
}

/**
 * Builds a use-case double whose `export` returns [result] (the factory seam's canned path). The
 * doubles subclass the real use case with a fail-loud lease port and unused collaborator stubs —
 * the run pipeline's seam must invoke `export` and nothing else.
 */
private fun exportReturning(result: ReadableLedgerExportResult): ReadableLedgerExport =
    object : ReadableLedgerExport(
        leasePort = failLoudLeasePort(),
        flowPage = QueryLedgerFlowPage(failLoudReadPort(), LedgerId("ledger-run-pipeline-seam")),
        creationEntries = null,
        catalogProvider = { error("the catalog must not be touched by the run-pipeline seam") },
        writerProvider = { error("the writer must not be touched by the run-pipeline seam") },
        clock = LedgerClock { error("the clock must not be touched by the run-pipeline seam") },
    ) {
        override fun export(ledgerId: com.unifiedledger.domain.LedgerId): ReadableLedgerExportResult = result
    }

/** Builds a use-case double that records its export thread before returning [result]. */
private fun exportRecordingThread(
    into: MutableList<String>,
    result: ReadableLedgerExportResult,
): ReadableLedgerExport =
    object : ReadableLedgerExport(
        leasePort = failLoudLeasePort(),
        flowPage = QueryLedgerFlowPage(failLoudReadPort(), LedgerId("ledger-run-pipeline-seam")),
        creationEntries = null,
        catalogProvider = { error("the catalog must not be touched by the run-pipeline seam") },
        writerProvider = { error("the writer must not be touched by the run-pipeline seam") },
        clock = LedgerClock { error("the clock must not be touched by the run-pipeline seam") },
    ) {
        override fun export(ledgerId: com.unifiedledger.domain.LedgerId): ReadableLedgerExportResult {
            into += Thread.currentThread().name
            return result
        }
    }

private fun failLoudLeasePort(): LedgerRuntimeLeasePort = LedgerRuntimeLeasePort { error("the lease port must not be touched by the run-pipeline seam") }

private fun failLoudReadPort(): LedgerCurrentStateReadPort =
    object : LedgerCurrentStateReadPort {
        override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = error("the page source must not be touched by the run-pipeline seam")

        override fun findManualExpenseByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualExpenseCommitRecord? = error("the page source must not be touched by the run-pipeline seam")

        override fun findManualExpenseByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedExpenseReceipt,
        ): ManualExpenseCommitRecord? = error("the page source must not be touched by the run-pipeline seam")

        override fun findManualIncomeByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualIncomeCommitRecord? = error("the page source must not be touched by the run-pipeline seam")

        override fun findManualIncomeByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedIncomeReceipt,
        ): ManualIncomeCommitRecord? = error("the page source must not be touched by the run-pipeline seam")

        override fun findManualTransferByRequest(
            ledgerId: LedgerId,
            requestId: RequestId,
        ): ManualTransferCommitRecord? = error("the page source must not be touched by the run-pipeline seam")

        override fun findManualTransferByReceipt(
            ledgerId: LedgerId,
            receipt: ConfirmedTransferReceipt,
        ): ManualTransferCommitRecord? = error("the page source must not be touched by the run-pipeline seam")

        override fun loadLedgerFlowPage(
            ledgerId: LedgerId,
            after: LedgerCurrentStateReadPort.LedgerFlowCursor?,
            pageSize: Int,
        ): LedgerCurrentStateReadPort.LedgerFlowPage = error("the page source must not be touched by the run-pipeline seam")

        override fun effectiveTransactionCount(ledgerId: LedgerId): Int = error("the page source must not be touched by the run-pipeline seam")
    }
