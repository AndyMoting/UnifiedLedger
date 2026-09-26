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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 06.D (D-182; spec sections 3/6): JVM tests for [runRestorePreflight] and
 * [runRestoreConfirm], the host's only product paths that run the restore flow (the
 * [P503BackupExportHostPipelineTest] precedent). Each test pins one load-bearing decision:
 * the work runs off the caller thread, the result lands on the scope's own dispatcher, a result
 * captured under a superseded generation is discarded, a throwing preflight lands a typed
 * rejection, and the CONFIRM landing guard binds the result's OWN generation (a switch advances
 * the generation — guarding a Committed result against the pre-confirm capture would discard the
 * success). F-1: every post-quiesce abort also ADVANCES the generation through the section 3.10
 * restoration reopen, so the abort results bind their post-restoration generation too, and a
 * fail-closed result with no active generation lands unguarded.
 */
class P503RestoreHostPipelineTest {
    private fun request(): RestorePreflightRequest = RestorePreflightRequest("password123", "ledger-local-test", setOf(1L, 31L), 31L)

    private fun token(): RestorePreflightToken = RestorePreflightToken("tok", 1, "ledger-local-test", ByteArray(32), null)

    // ---------------------------------------------------------------- runRestorePreflight

    @Test
    fun aThrowingPreflightStillLandsATypedRejectionInsteadOfStrandingTheSurface() {
        val landed = CompletableDeferred<RestorePreflightResult>()
        runBlocking {
            runRestorePreflight(
                scope = this,
                generation = 1,
                request = request(),
                preflight = { throw IllegalStateException("injected filesystem failure") },
                isCurrentGeneration = { true },
                land = { landed.complete(it) },
            )
            val result = withTimeoutOrNull(30_000) { landed.await() }
            val rejected = assertIs<RestorePreflightResult.Rejected>(result, "a throwing preflight must land a typed rejection")
            assertEquals(
                com.unifiedledger.application.backup.BackupPreflightRejection.P706_SOURCE_READ_FAILED,
                rejected.code,
            )
        }
    }

    @Test
    fun aSuccessfulPreflightLandsItsResultWhenTheGenerationIsCurrent() {
        val landed = CompletableDeferred<RestorePreflightResult>()
        runBlocking {
            runRestorePreflight(
                scope = this,
                generation = 1,
                request = request(),
                preflight = { RestorePreflightResult.Cancelled },
                isCurrentGeneration = { true },
                land = { landed.complete(it) },
            )
            assertEquals(RestorePreflightResult.Cancelled, withTimeoutOrNull(30_000) { landed.await() })
        }
    }

    @Test
    fun aSupersededGenerationDiscardsThePreflightResult() {
        var landedCount = 0
        runBlocking {
            runRestorePreflight(
                scope = this,
                generation = 1,
                request = request(),
                preflight = { RestorePreflightResult.Cancelled },
                isCurrentGeneration = { false },
                land = { landedCount += 1 },
            )
            withTimeoutOrNull(500) {
                while (true) delay(10)
            }
        }
        assertEquals(0, landedCount, "a result captured under a superseded generation must be discarded")
    }

    @Test
    fun thePreflightRunsOffTheCallerThreadAndLandsOnTheScopeDispatcher() {
        val workThreads = mutableListOf<String>()
        val landingThreads = mutableListOf<String>()
        val scopeDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main-scope").apply { isDaemon = true } }
        val callerName = Thread.currentThread().name
        try {
            runBlocking {
                val scope = CoroutineScope(scopeDispatcher.asCoroutineDispatcher())
                val landed = CompletableDeferred<Unit>()
                runRestorePreflight(
                    scope = scope,
                    generation = 1,
                    request = request(),
                    preflight = {
                        workThreads += Thread.currentThread().name
                        RestorePreflightResult.Cancelled
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
        val workThread = workThreads.single()
        val landingThread = landingThreads.single()
        assertTrue(landingThread.startsWith("test-main-scope"), "the landing must hop onto the scope dispatcher")
        assertTrue(workThread != callerName, "the preflight must not run on the caller thread")
        assertFalse(
            workThread.startsWith("test-main-scope"),
            "the preflight must not run on the scope/composition dispatcher (container-format spec section 4.8)",
        )
    }

    // ---------------------------------------------------------------- runRestoreConfirm

    @Test
    fun aCommittedResultIsGuardedByItsOwnPostSwitchGenerationNotThePreConfirmCapture() {
        // Spec section 3.9: the committed result binds the POST-reopen generation. The switch has
        // advanced the owner from 1 to 2, so the pre-confirm capture (1) is NO LONGER current while
        // the result's own generation (2) IS: the guard must consult the result, or every success
        // would be discarded.
        val landed = CompletableDeferred<BackupRestoreSwitchResult>()
        runBlocking {
            runRestoreConfirm(
                scope = this,
                token = token(),
                confirm = { BackupRestoreSwitchResult.Committed(runtimeGeneration = 2) },
                isCurrentGeneration = { it == 2 },
                land = { landed.complete(it) },
            )
            val result = withTimeoutOrNull(30_000) { landed.await() }
            assertIs<BackupRestoreSwitchResult.Committed>(result, "a committed result must land under its own generation")
        }
    }

    @Test
    fun aPostRestorationAbortLandsUnderItsPostRestorationGenerationNotThePreConfirmCapture() {
        // F-1: every post-quiesce abort runs the section 3.10 restoration, whose reopen ADVANCES
        // the process generation. The abort result therefore binds the POST-restoration generation
        // (2), and the landing guard must accept it — the pre-confirm capture (1) is stale by
        // construction. Reverting the guard to the capture turns this RED (the abort is discarded
        // and the surface wedges on "正在切换账本" forever).
        val landed = CompletableDeferred<BackupRestoreSwitchResult>()
        runBlocking {
            runRestoreConfirm(
                scope = this,
                token = token(),
                confirm = {
                    BackupRestoreSwitchResult.AbortedBeforePublish(
                        BackupRestoreAbortReason.StagingFailed,
                        BackupRestoreRuntimeOutcome.RestoredReady,
                        runtimeGeneration = 2,
                    )
                },
                isCurrentGeneration = { it == 2 },
                land = { landed.complete(it) },
            )
            val result = withTimeoutOrNull(30_000) { landed.await() }
            assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(
                result,
                "a post-quiesce abort must land under its post-restoration generation",
            )
        }
    }

    @Test
    fun aPostponedResultWithItsPostRestorationGenerationLands() {
        // Same F-1 shape for the other abort family: a blocked close postpones, the restoration
        // reopen advances the generation, and the postponed result must land bound to it.
        val landed = CompletableDeferred<BackupRestoreSwitchResult>()
        runBlocking {
            runRestoreConfirm(
                scope = this,
                token = token(),
                confirm = {
                    BackupRestoreSwitchResult.Postponed(
                        BackupRestorePostponeReason.CloseBlocked,
                        BackupRestoreRuntimeOutcome.RestoredReady,
                        runtimeGeneration = 3,
                    )
                },
                isCurrentGeneration = { it == 3 },
                land = { landed.complete(it) },
            )
            assertIs<BackupRestoreSwitchResult.Postponed>(withTimeoutOrNull(30_000) { landed.await() })
        }
    }

    @Test
    fun aRecoveryRequiredResultWithNoActiveGenerationLandsUnguarded() {
        // F-1: a rollback that also failed leaves the owner Closed/StartupError, so there is NO
        // active generation to guard against. The result must land anyway (it drives the
        // session-terminal face); discarding it on a null/absent generation strands the user on a
        // dead surface forever.
        val landed = CompletableDeferred<BackupRestoreSwitchResult>()
        runBlocking {
            runRestoreConfirm(
                scope = this,
                token = token(),
                confirm = { BackupRestoreSwitchResult.RecoveryRequired(BackupRestoreRecoveryCause.RollbackReopenFailed) },
                // The owner is fail-closed: nothing is "current".
                isCurrentGeneration = { false },
                land = { landed.complete(it) },
            )
            val result = withTimeoutOrNull(30_000) { landed.await() }
            assertIs<BackupRestoreSwitchResult.RecoveryRequired>(
                result,
                "the session-terminal result must land even with no active generation",
            )
        }
    }

    @Test
    fun aCommittedResultWhoseGenerationIsAlreadySupersededIsDiscarded() {
        var landedCount = 0
        runBlocking {
            runRestoreConfirm(
                scope = this,
                token = token(),
                confirm = { BackupRestoreSwitchResult.Committed(runtimeGeneration = 2) },
                // Another reopen raced past the switch: the landing guard discards.
                isCurrentGeneration = { it == 3 },
                land = { landedCount += 1 },
            )
            withTimeoutOrNull(500) {
                while (true) delay(10)
            }
        }
        assertEquals(0, landedCount, "a superseded committed result must be discarded (spec section 3.9)")
    }

    @Test
    fun aPostRestorationAbortWhoseGenerationIsAlreadySupersededIsDiscarded() {
        // The F-1 fix must not over-land: an abort result whose own post-restoration generation is
        // ALREADY superseded by a later transition is still a stale latecomer and is discarded.
        var landedCount = 0
        runBlocking {
            runRestoreConfirm(
                scope = this,
                token = token(),
                confirm = {
                    BackupRestoreSwitchResult.AbortedBeforePublish(
                        BackupRestoreAbortReason.StagingFailed,
                        BackupRestoreRuntimeOutcome.RestoredReady,
                        runtimeGeneration = 2,
                    )
                },
                isCurrentGeneration = { it == 3 },
                land = { landedCount += 1 },
            )
            withTimeoutOrNull(500) {
                while (true) delay(10)
            }
        }
        assertEquals(0, landedCount, "an abort whose own generation was superseded is discarded")
    }

    @Test
    fun theConfirmRunsOnTheCallerLaunchedDefaultContextAndLandsOnTheScopeDispatcher() {
        val confirmThreads = mutableListOf<String>()
        val landingThreads = mutableListOf<String>()
        val scopeDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main-scope").apply { isDaemon = true } }
        try {
            runBlocking {
                val scope = CoroutineScope(scopeDispatcher.asCoroutineDispatcher())
                val landed = CompletableDeferred<Unit>()
                runRestoreConfirm(
                    scope = scope,
                    token = token(),
                    confirm = {
                        confirmThreads += Thread.currentThread().name
                        BackupRestoreSwitchResult.RolledBack(runtimeGeneration = 1)
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
        // The confirm runs in the launched coroutine (Dispatchers.Default inside the pipeline), the
        // landing hops onto the scope dispatcher: they must differ.
        assertTrue(confirmThreads.single() != landingThreads.single(), "the landing must hop off the work dispatcher")
        assertTrue(landingThreads.single().startsWith("test-main-scope"))
    }
}
