package com.unifiedledger.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P2-E/P2-G fix (06.B review): JVM tests for [runBackupExport], the host's only product path that
 * runs the export. Before this extraction the composable's inline coroutine had no test, so
 * removing `Dispatchers.Default`, removing the landing hop, or removing the generation discard all
 * left the suite green.
 *
 * The tests drive the real function with an injected export lambda (the function seam) and recording
 * collaborators, asserting each of its four decisions: the export runs off the caller thread, the
 * result lands on the scope's own dispatcher, a superseded generation discards the result, and a
 * throwing export still lands a typed Failed.
 */
class P503BackupExportHostPipelineTest {
    private fun request(): BackupExportRequest = BackupExportRequest("password123", "/host/ledger.db")

    @Test
    fun aThrowingExportStillLandsATypedFailureInsteadOfStrandingTheSurface() {
        // P2-G: a fail-loud LedgerFileSystem adapter (or any throw) must not kill the coroutine
        // before the landing dispatch. The catch maps it to the typed generic failure.
        val landed = CompletableDeferred<BackupExportResult>()

        runBlocking {
            runBackupExport(
                scope = this,
                generation = 1,
                request = request(),
                export = { throw IllegalStateException("injected filesystem failure") },
                isCurrentGeneration = { true },
                land = { landed.complete(it) },
            )
            val result = withTimeoutOrNull(30_000) { landed.await() }
            val failure = assertIs<BackupExportResult.Failed>(result, "a throwing export must land a typed Failed")
            assertEquals(BackupExportFailure.CONTAINER_WRITE_FAILED, failure.reason)
        }
    }

    @Test
    fun aSuccessfulExportLandsItsResultWhenTheGenerationIsCurrent() {
        val landed = CompletableDeferred<BackupExportResult>()

        runBlocking {
            runBackupExport(
                scope = this,
                generation = 1,
                request = request(),
                export = { BackupExportResult.Succeeded(4096) },
                isCurrentGeneration = { true },
                land = { landed.complete(it) },
            )
            assertEquals(BackupExportResult.Succeeded(4096), withTimeoutOrNull(30_000) { landed.await() })
        }
    }

    @Test
    fun aSupersededGenerationDiscardsTheResult() {
        // P2-E: the generation discard is load-bearing (BackupExportResult carries no generation).
        var landedCount = 0

        runBlocking {
            runBackupExport(
                scope = this,
                generation = 1,
                request = request(),
                export = { BackupExportResult.Succeeded(4096) },
                // The captured generation is no longer current.
                isCurrentGeneration = { false },
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
        // P2-E: pins both the off-thread export and the main-thread landing hop. The export records
        // the thread it ran on; the landing records the thread it landed on. The scope is a single
        // dedicated dispatcher, so the landing must be on that dispatcher's thread, never the
        // caller's and never the export's Default-pool thread.
        val exportThreads = mutableListOf<String>()
        val landingThreads = mutableListOf<String>()
        val scopeDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main-scope").apply { isDaemon = true } }
        val callerName = Thread.currentThread().name

        try {
            runBlocking {
                val scope = CoroutineScope(scopeDispatcher.asCoroutineDispatcher())
                val landed = CompletableDeferred<Unit>()
                runBackupExport(
                    scope = scope,
                    generation = 1,
                    request = request(),
                    export = {
                        exportThreads += Thread.currentThread().name
                        BackupExportResult.Cancelled
                    },
                    isCurrentGeneration = { true },
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
        assertTrue(exportThreads.single() != callerName, "the export must not run on the caller thread")
        assertEquals(1, landingThreads.size)
        assertTrue(
            landingThreads.single().startsWith("test-main-scope"),
            "the landing must hop onto the scope dispatcher, was ${landingThreads.single()}",
        )
    }
}
