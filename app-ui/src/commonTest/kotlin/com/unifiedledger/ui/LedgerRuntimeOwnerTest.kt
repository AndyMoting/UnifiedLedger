package com.unifiedledger.ui

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
            val h =
                Harness(
                    open = { Graph(1) },
                ).also { it.owner.startup() }
            val blocked =
                LedgerRuntimeOwner(
                    openGeneration = { Graph(1) },
                    closeGraph = { h.closeCount += 1 },
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
            var openCount = 0
            val h =
                harness {
                    openCount += 1
                    Graph(openCount)
                }
            h.owner.startup()

            val workers =
                (1..8).map {
                    launch {
                        repeat(20) {
                            val lease = h.owner.acquireLease()
                            if (lease is LeaseAcquireResult.Acquired) {
                                // Simulate business work and release promptly.
                                delay(1)
                                lease.lease.close()
                            }
                        }
                    }
                }
            val reopen = async { h.owner.reopen() }
            workers.forEach { it.join() }
            val reopenResult = reopen.await()

            // The reopen is either rejected while leases were in flight (typed) or succeeds once the
            // workers drained — never a second concurrent graph. The close count proves no graph was
            // closed while a lease was in flight.
            when (reopenResult) {
                is ReopenResult.QuiesceBlocked -> assertEquals(0, h.closeCount)
                is ReopenResult.Reopened -> assertTrue(h.closeCount >= 1)
                is ReopenResult.Failed -> error("reopen must not fail here: $reopenResult")
            }
            assertTrue(h.owner.state == LedgerRuntimeState.Ready)
            assertEquals(0, h.owner.inFlightLeaseCount)
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
    }
}
