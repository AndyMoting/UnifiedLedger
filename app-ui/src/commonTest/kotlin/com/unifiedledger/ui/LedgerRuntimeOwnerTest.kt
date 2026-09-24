package com.unifiedledger.ui

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.1 (D-176; spec sections 4.2-4.4 and 6): the [LedgerRuntimeOwner] contract — the single
 * active graph, the generation capture, the operation lease, quiesce/close/reopen, the typed
 * `QuiesceBlocked`/`RuntimeNotReady` failures and the concurrent-open guarantee. Every test maps
 * to a spec section 6 failure vector or a section 7 acceptance contribution (P706-A06/A07/A10).
 */
class LedgerRuntimeOwnerTest {
    private class Graph(
        val id: Int,
    ) {
        val facade: P503LedgerFacade = minimalP503LedgerFacade()
    }

    private class Harness(
        val open: () -> Graph,
    ) {
        var closeCount = 0
        val owner =
            LedgerRuntimeOwner(
                openGeneration = { open() },
                closeGraph = { closeCount += 1 },
                facadeOf = { it.facade },
            )
    }

    private fun harness(open: () -> Graph = { Graph(1) }): Harness = Harness(open)

    // ---------------------------------------------------------------- startup / single active graph

    @Test
    fun startupOpensOnceAndReachesReadyWithGenerationOne() {
        var openCount = 0
        val h =
            harness {
                openCount += 1
                Graph(openCount)
            }

        val result = h.owner.startup()

        assertEquals(LedgerStartupResult.Started(1), result)
        assertEquals(LedgerRuntimeState.Ready, h.owner.state)
        assertEquals(1, h.owner.activeGeneration)
        assertEquals(1, openCount)
        assertNotNull(h.owner.facade)
    }

    @Test
    fun aFailingStartupFailsClosedWithoutExposingAGraph() {
        val h = harness { throw IllegalStateException("injected open failure") }

        val result = h.owner.startup()

        assertIs<LedgerStartupResult.Failed>(result)
        assertEquals(LedgerRuntimeState.StartupError, h.owner.state)
        assertNull(h.owner.activeGeneration)
        assertNull(h.owner.facade)
    }

    @Test
    fun aRestartClosesThePreviousGraphAndKeepsExactlyOneActive() {
        var openCount = 0
        val h =
            harness {
                openCount += 1
                Graph(openCount)
            }

        h.owner.startup()
        h.owner.startup()

        assertEquals(2, openCount)
        assertEquals(1, h.closeCount)
        assertEquals(LedgerRuntimeState.Ready, h.owner.state)
    }

    @Test
    fun reopenIncrementsTheGenerationAndReleasesThePreviousGraph() {
        var openCount = 0
        val h =
            harness {
                openCount += 1
                Graph(openCount)
            }

        h.owner.startup()
        val reopened = h.owner.reopen()

        assertEquals(ReopenResult.Reopened(2), reopened)
        assertEquals(2, h.owner.activeGeneration)
        assertEquals(1, h.closeCount)
    }

    @Test
    fun reopenPassesTheRequestedGenerationSelectionToTheOpener() {
        val selections = mutableListOf<GenerationSelection>()
        val owner =
            LedgerRuntimeOwner(
                openGeneration = { selection ->
                    selections += selection
                    Graph(selections.size)
                },
                closeGraph = {},
                facadeOf = { it.facade },
            )
        owner.startup()

        owner.reopen(GenerationSelection.Explicit(3))

        assertEquals(
            listOf(GenerationSelection.ActivePointer, GenerationSelection.Explicit(3)),
            selections,
        )
    }

    @Test
    fun closeActiveGraphIsIdempotentAndReopenStillWorks() {
        val h = harness()
        h.owner.startup()

        assertEquals(CloseResult.Closed, h.owner.closeActiveGraph())
        assertEquals(LedgerRuntimeState.Closed, h.owner.state)
        assertNull(h.owner.activeGeneration)
        // Idempotent: a second close with no graph held is still a success and closes nothing new.
        assertEquals(CloseResult.Closed, h.owner.closeActiveGraph())
        assertEquals(1, h.closeCount)

        assertEquals(ReopenResult.Reopened(2), h.owner.reopen())
        assertEquals(2, h.owner.activeGeneration)
    }

    @Test
    fun aFailedReopenFailsClosedAndDoesNotExposeAHalfOpenGraph() {
        var shouldFail = false
        val h =
            harness {
                if (shouldFail) throw IllegalStateException("injected reopen failure")
                Graph(1)
            }
        h.owner.startup()
        shouldFail = true

        val result = h.owner.reopen()

        assertIs<ReopenResult.Failed>(result)
        assertEquals(LedgerRuntimeState.StartupError, h.owner.state)
        assertNull(h.owner.activeGeneration)
        assertNull(h.owner.facade)
    }

    // ---------------------------------------------------------------- leases (section 4.2/4.3)

    @Test
    fun acquireLeaseCapturesTheCurrentGeneration() {
        val h = harness()
        h.owner.startup()

        val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

        assertEquals(1, lease.generation)
        assertEquals(1, h.owner.inFlightLeaseCount)
        lease.close()
        assertEquals(0, h.owner.inFlightLeaseCount)
    }

    @Test
    fun leaseReleaseIsIdempotent() {
        val h = harness()
        h.owner.startup()
        val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

        lease.close()
        lease.close()

        assertEquals(0, h.owner.inFlightLeaseCount)
    }

    @Test
    fun acquireLeaseBeforeReadyReturnsRuntimeNotReady() {
        val h = harness()

        assertEquals(LeaseAcquireResult.RuntimeNotReady, h.owner.acquireLease())
    }

    @Test
    fun acquireLeaseAfterFailClosedReturnsRuntimeNotReady() {
        val h = harness { throw IllegalStateException("injected open failure") }
        h.owner.startup()

        assertEquals(LeaseAcquireResult.RuntimeNotReady, h.owner.acquireLease())
    }

    // ---------------------------------------------------------------- startup in-flight precondition (fix 5)

    @Test
    fun startupWithAnInFlightLeaseIsBlockedAndDoesNotCloseTheGraph() {
        // Spec section 4.2: no close/switch may run while leases are in flight. `startup()`
        // performs the "close before open", so it must honour the same precondition.
        val h = harness()
        h.owner.startup()
        val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

        val result = h.owner.startup()

        assertEquals(LedgerStartupResult.Blocked(1), result)
        assertEquals(0, h.closeCount)
        assertEquals(LedgerRuntimeState.Ready, h.owner.state)
        assertNotNull(h.owner.facade)
        lease.close()
    }

    // ---------------------------------------------------------------- quiesce (section 4.2/6)

    @Test
    fun quiesceReturnsQuiescedWithNoInFlightLeases() =
        runBlocking {
            val h = harness()
            h.owner.startup()

            assertEquals(QuiesceResult.Quiesced, h.owner.quiesce())
        }

    @Test
    fun quiesceWaitsForAnInFlightLeaseToRelease() =
        runBlocking {
            val h = harness()
            h.owner.startup()
            val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

            val quiesce = async { h.owner.quiesce() }
            // The quiesce must not return while the lease is held.
            assertNull(withTimeoutOrNull(200) { quiesce.await() })

            lease.close()
            assertEquals(QuiesceResult.Quiesced, withTimeoutOrNull(2_000) { quiesce.await() })
        }

    @Test
    fun quiesceRejectsNewLeasesWhileDraining() =
        runBlocking {
            val h = harness()
            h.owner.startup()
            val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

            val quiesce = async { h.owner.quiesce() }
            // Wait until the state has flipped to Quiescing.
            withTimeoutOrNull(2_000) {
                while (h.owner.state != LedgerRuntimeState.Quiescing) delay(5)
            }
            assertEquals(LeaseAcquireResult.RuntimeNotReady, h.owner.acquireLease())

            lease.close()
            assertEquals(QuiesceResult.Quiesced, withTimeoutOrNull(2_000) { quiesce.await() })
        }

    @Test
    fun aQuiesceThatCannotDrainReturnsQuiesceBlocked() =
        runBlocking {
            // The bounded timeout turns the P3-3 self-deadlock shape (a caller holding its own lease)
            // into a typed result instead of an infinite wait.
            val blocked =
                LedgerRuntimeOwner(
                    openGeneration = { Graph(1) },
                    closeGraph = {},
                    facadeOf = { it.facade },
                    quiesceTimeoutMillis = 50,
                )
            blocked.startup()
            val lease = assertIs<LeaseAcquireResult.Acquired>(blocked.acquireLease()).lease

            val result = blocked.quiesce()

            assertEquals(QuiesceResult.QuiesceBlocked(1), result)
            lease.close()
        }

    // ---------------------------------------------------------------- close/reopen in-flight rejection

    @Test
    fun closeActiveGraphWithAnInFlightLeaseReturnsQuiesceBlockedAndDoesNotClose() {
        val h = harness()
        h.owner.startup()
        val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

        val result = h.owner.closeActiveGraph()

        assertEquals(CloseResult.QuiesceBlocked(1), result)
        assertEquals(0, h.closeCount)
        assertEquals(LedgerRuntimeState.Ready, h.owner.state)
        lease.close()
    }

    @Test
    fun reopenWithAnInFlightLeaseReturnsQuiesceBlockedAndLeavesTheOldGraph() {
        val h = harness()
        h.owner.startup()
        val oldFacade = h.owner.facade
        val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

        val result = h.owner.reopen()

        assertEquals(ReopenResult.QuiesceBlocked(1), result)
        assertEquals(0, h.closeCount)
        assertEquals(LedgerRuntimeState.Ready, h.owner.state)
        assertEquals(oldFacade, h.owner.facade)
        lease.close()
    }

    @Test
    fun closeActiveGraphUnderMereTransitionContentionReportsTransitionInProgress() =
        runBlocking {
            // Fix 4: a mutex-contention rejection must NOT be reported as QuiesceBlocked(0), which
            // would misreport the documented meaning (leases in flight). Deterministically hold the
            // owner mutex: `startup` runs `openGeneration` under the mutex, so a second close that
            // arrives while the open is parked fails `tryLock` with ZERO leases in flight.
            val openEntered = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            val owner =
                LedgerRuntimeOwner(
                    openGeneration = {
                        openEntered.complete(Unit)
                        // Park inside the mutex until the test's contended call has returned.
                        runBlocking { releaseOpen.await() }
                        Graph(1)
                    },
                    closeGraph = {},
                    facadeOf = { it.facade },
                )

            val startup = async(Dispatchers.Default) { owner.startup() }
            openEntered.await()

            try {
                // The owner mutex is held by the in-flight startup; there are no leases, so the
                // rejection must be the distinct contention result.
                assertEquals(CloseResult.TransitionInProgress, owner.closeActiveGraph())
                assertEquals(ReopenResult.TransitionInProgress, owner.reopen())
                assertEquals(0, owner.inFlightLeaseCount)
            } finally {
                // Always release the parked open, so a failed assertion above cannot hang the test.
                releaseOpen.complete(Unit)
            }
            assertEquals(LedgerStartupResult.Started(1), startup.await())
        }

    @Test
    fun theQuiesceThenCloseThenReopenSequenceDrainsAndSwapsTheGraph() =
        runBlocking {
            var openCount = 0
            val h =
                harness {
                    openCount += 1
                    Graph(openCount)
                }
            h.owner.startup()
            val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease

            val quiesce = async { h.owner.quiesce() }
            lease.close()
            assertEquals(QuiesceResult.Quiesced, withTimeoutOrNull(2_000) { quiesce.await() })

            assertEquals(CloseResult.Closed, h.owner.closeActiveGraph())
            assertEquals(ReopenResult.Reopened(2), h.owner.reopen())
            assertEquals(2, h.owner.activeGeneration)
        }

    // ---------------------------------------------------------------- concurrent open (section 6)

    @Test
    fun concurrentAcquisitionsAndReopenProduceAtMostOneActiveGraph() =
        runBlocking {
            // Fix 8: this test must be able to FAIL if the single-active-graph invariant breaks. The
            // harness tracks live (opened but not closed) graphs; a double-open would push it above
            // 1, and the assertion below goes red.
            var openCount = 0
            var liveGraphs = 0
            var maxLiveGraphs = 0
            val lock = Any()
            val owner =
                LedgerRuntimeOwner(
                    openGeneration = {
                        synchronized(lock) {
                            openCount += 1
                            liveGraphs += 1
                            maxLiveGraphs = maxOf(maxLiveGraphs, liveGraphs)
                        }
                        Graph(openCount)
                    },
                    closeGraph = { synchronized(lock) { liveGraphs -= 1 } },
                    facadeOf = { it.facade },
                )
            owner.startup()

            // Real parallelism: many worker threads acquire/release leases while a reopener races
            // them. The owner's mutex + atomic state must keep at most one live graph at any moment.
            val workers =
                (1..8).map {
                    launch(Dispatchers.Default) {
                        repeat(50) {
                            val lease = owner.acquireLease()
                            if (lease is LeaseAcquireResult.Acquired) {
                                delay(1)
                                lease.lease.close()
                            }
                        }
                    }
                }
            val reopen = async(Dispatchers.Default) { owner.reopen() }
            workers.forEach { it.join() }
            val reopenResult = reopen.await()

            when (reopenResult) {
                is ReopenResult.QuiesceBlocked -> assertEquals(0, openCount - 1, "no reopen happened, so only the initial graph was opened")
                is ReopenResult.Reopened -> assertTrue(openCount >= 2)
                is ReopenResult.Failed -> error("reopen must not fail here: $reopenResult")
                ReopenResult.TransitionInProgress -> Unit
            }
            // The invariant: never more than one graph open at a time.
            assertEquals(1, maxLiveGraphs, "the owner must never hold more than one live graph")
            assertTrue(owner.state == LedgerRuntimeState.Ready)
            assertEquals(0, owner.inFlightLeaseCount)
        }

    // ---------------------------------------------------------------- lease scope accessor (section 4.3)

    @Test
    fun theLeaseScopeRunsTheBlockUnderALeaseAndReleasesIt() {
        val h = harness()
        h.owner.startup()
        val scope = LedgerLeaseScope(h.owner)

        val observed =
            scope.withFacade { facade, generation ->
                assertEquals(1, h.owner.inFlightLeaseCount)
                assertNotNull(facade)
                generation
            }
        assertEquals(1, observed)
        assertEquals(0, h.owner.inFlightLeaseCount)
    }

    @Test
    fun theLeaseScopeRunsNothingWhenTheOwnerIsNotReady() {
        val h = harness()
        val scope = LedgerLeaseScope(h.owner)
        var ran = false

        val result =
            scope.withFacade { _, _ ->
                ran = true
                1
            }

        assertNull(result)
        assertFalse(ran)
        // probe() must not run either, and leased() must report the typed NotReady.
        var probed = false
        assertNull(scope.probe { probed = true })
        assertFalse(probed)
        assertEquals(LeaseOutcome.NotReady, scope.leased { _, _ -> 1 })
    }

    @Test
    fun theLeaseScopeLeasedCarriesTheAcquireTimeGeneration() {
        val h = harness()
        h.owner.startup()
        val scope = LedgerLeaseScope(h.owner)

        val outcome = scope.leased { _, generation -> generation }

        assertEquals(LeaseOutcome.Completed(1, 1), outcome)
        assertEquals(0, h.owner.inFlightLeaseCount)
    }

    @Test
    fun theLeaseScopeExposesOnlyLeaseFreePureSurfaces() {
        // The scope's direct facade-typed surface is the lease-free pure configuration; every
        // ledger-touching member is reachable only inside withFacade/probe/leased. This test pins
        // that the pure constants resolve without acquiring a lease.
        val h = harness()
        h.owner.startup()
        val scope = LedgerLeaseScope(h.owner)

        assertEquals("ledger-local-test", scope.ledgerId.value)
        assertEquals(0, h.owner.inFlightLeaseCount)
        assertNotNull(scope.parseAmount)
        assertNotNull(scope.ledgerClock)
    }

    // ---------------------------------------------------------------- generation discard (section 4.4)

    @Test
    fun aResultCapturedUnderASupersededGenerationIsDiscarded() {
        // Fix 2: the landing rule is "captured generation == current active generation". After a
        // reopen (generation 2), a result captured under generation 1 must be discarded.
        val h = harness()
        h.owner.startup()
        val scope = LedgerLeaseScope(h.owner)
        val lease = assertIs<LeaseAcquireResult.Acquired>(h.owner.acquireLease()).lease
        val captured = lease.generation
        assertEquals(1, captured)
        assertTrue(scope.isCurrentGeneration(captured))
        // Release the lease before the reopen: reopen requires zero in-flight leases.
        lease.close()

        h.owner.reopen()

        assertEquals(2, h.owner.activeGeneration)
        assertFalse(scope.isCurrentGeneration(captured), "a generation-1 result must be discarded after reopen")
        assertTrue(scope.isCurrentGeneration(2))
        assertTrue(shouldDiscardLandingResult(captured, h.owner.activeGeneration))
        assertFalse(shouldDiscardLandingResult(2, h.owner.activeGeneration))
    }

    @Test
    fun aNullCapturedGenerationIsAlwaysDiscarded() {
        assertTrue(shouldDiscardLandingResult(null, 1))
        assertTrue(shouldDiscardLandingResult(null, null))
        assertFalse(shouldDiscardLandingResult(1, 1))
        assertTrue(shouldDiscardLandingResult(1, 2))
    }
}
