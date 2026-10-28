package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P7-09 09.A delivery-order contract (spec section 2.2; ACC-D-SHARE-01): a neutral inbound request
 * reaches only the RESUMED host, matched by IDENTITY against each host's own binding. A
 * subscribed-but-paused host must never absorb one — that is how an accepted share became invisible on
 * the create path (device evidence `local/artifacts/p709-share-delivery/`: `entry.inboundIntake
 * kind=OpenImport` with an empty screen, while the previous instance showed the same request once it
 * was brought forward).
 */
class ExternalNavigationChannelHostTest {
    private val share = ExternalNavigationRequest.OpenImport("delivery-share-1")
    private val other = ExternalNavigationRequest.OpenCurrentMonth

    @Test
    fun aDeliveryWhileOnlyAPausedHostIsBoundWaitsForTheNextResumedHost() {
        val channel = ExternalNavigationChannel()
        val previous = Any()
        val gathered = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(previous) { gathered += it }
        channel.onHostResumed(previous)
        channel.onHostPaused(previous)

        // The create path: the next instance has not bound yet, so the previous (paused) instance is
        // the only binding when the request arrives.
        channel.deliver(share)
        assertEquals(emptyList<ExternalNavigationRequest>(), gathered, "a paused host must never absorb a delivery")

        // The next instance binds while not resumed, then resumes: the request lands there.
        val next = Any()
        val landed = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(next) { landed += it }
        assertEquals(emptyList<ExternalNavigationRequest>(), landed, "binding is not resuming")
        channel.onHostResumed(next)
        assertEquals(listOf<ExternalNavigationRequest>(share), landed)
        assertEquals(emptyList<ExternalNavigationRequest>(), gathered, "the request lands exactly once, on the visible host")
    }

    @Test
    fun aSupersededInstancesUnbindLeavesTheLiveHostsBindingIntact() {
        // P709SD-01: dispose of a non-visible instance must not disarm the visible one.
        val channel = ExternalNavigationChannel()
        val stale = Any()
        val live = Any()
        val toLive = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(stale) { }
        channel.subscribe(live) { toLive += it }
        channel.onHostResumed(live)
        channel.subscribe(stale, null)
        channel.deliver(share)
        assertEquals(listOf<ExternalNavigationRequest>(share), toLive)
    }

    @Test
    fun everyBoundHostKeepsItsOwnEntrySoReturningToAnOlderInstanceStillReceives() {
        // P709SD-02: more than one instance may be bound; returning to the older one must work.
        val channel = ExternalNavigationChannel()
        val first = Any()
        val second = Any()
        val toFirst = mutableListOf<ExternalNavigationRequest>()
        val toSecond = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(first) { toFirst += it }
        channel.subscribe(second) { toSecond += it }
        channel.onHostResumed(second)
        channel.deliver(share)
        assertEquals(listOf<ExternalNavigationRequest>(share), toSecond)
        channel.onHostPaused(second)
        channel.onHostResumed(first)
        channel.deliver(other)
        assertEquals(listOf<ExternalNavigationRequest>(other), toFirst)
        assertEquals(listOf<ExternalNavigationRequest>(share), toSecond)
    }

    @Test
    fun aHostThatTookARequestIsNotGivenItAgainOnAResume() {
        val channel = ExternalNavigationChannel()
        val host = Any()
        val received = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(host) { received += it }
        channel.onHostResumed(host)
        channel.deliver(share)
        channel.onHostPaused(host)
        channel.onHostResumed(host)
        assertEquals(listOf<ExternalNavigationRequest>(share), received)
    }

    @Test
    fun theStagingSlotKeepsDepthOneWhileEveryHostIsPaused() {
        val channel = ExternalNavigationChannel()
        val host = Any()
        val received = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(host) { received += it }
        channel.onHostPaused(host)
        channel.deliver(share)
        channel.deliver(other)
        channel.onHostResumed(host)
        assertEquals(listOf<ExternalNavigationRequest>(other), received, "depth one: the newest survives")
    }

    @Test
    fun aColdStartRequestWaitsForTheVeryFirstResume() {
        val channel = ExternalNavigationChannel()
        channel.deliver(share)
        val host = Any()
        val received = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(host) { received += it }
        assertEquals(emptyList<ExternalNavigationRequest>(), received)
        channel.onHostResumed(host)
        assertEquals(listOf<ExternalNavigationRequest>(share), received)
        // A repeated resume with nothing waiting must not replay anything.
        channel.onHostResumed(host)
        assertEquals(1, received.size)
    }

    @Test
    fun anUnbindKeepsTheRequestParkedForTheNextBoundAndResumedHost() {
        val channel = ExternalNavigationChannel()
        val host = Any()
        val received = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(host) { received += it }
        channel.onHostResumed(host)
        channel.subscribe(host, null)
        channel.deliver(share)
        assertEquals(emptyList<ExternalNavigationRequest>(), received)
        // The resumed host has no binding yet, so the request stays parked; the next bound+resumed
        // host drains it exactly once.
        val nextHost = Any()
        val landed = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(nextHost) { landed += it }
        assertEquals(emptyList<ExternalNavigationRequest>(), landed, "a resumed host without a binding cannot take it")
        channel.onHostResumed(nextHost)
        assertEquals(listOf<ExternalNavigationRequest>(share), landed)
    }
}
