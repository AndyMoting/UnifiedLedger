package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P7-09 09.A delivery-order contract (spec section 2.2; ACC-D-SHARE-01): a neutral inbound request
 * reaches only the RESUMED host, matched by the host token the adapter binds. A subscribed-but-paused
 * host must never absorb one — that is exactly how an accepted share became invisible on the create
 * path (device evidence `local/artifacts/p709-share-delivery/`: `entry.inboundIntake
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
        // still the channel's binding when the request arrives.
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
    fun aBindingForANonResumedHostNeverStealsTheResumedHostsDelivery() {
        val channel = ExternalNavigationChannel()
        val paused = Any()
        val resumed = Any()
        val toPaused = mutableListOf<ExternalNavigationRequest>()
        val toResumed = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(resumed) { toResumed += it }
        channel.onHostResumed(resumed)
        // A second (superseded) instance binds afterwards; the resumed host must still win.
        channel.subscribe(paused) { toPaused += it }
        channel.deliver(share)
        assertEquals(emptyList<ExternalNavigationRequest>(), toPaused)
        assertEquals(emptyList<ExternalNavigationRequest>(), toResumed, "the newest binding is not the resumed one")
        // The request waits for the resumed host to bind again, then lands exactly there.
        channel.subscribe(resumed) { toResumed += it }
        assertEquals(listOf<ExternalNavigationRequest>(share), toResumed)
        assertEquals(emptyList<ExternalNavigationRequest>(), toPaused)
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
    fun anUnbindLeavesNoListenerAndDropsNothingThatWasNeverTaken() {
        val channel = ExternalNavigationChannel()
        val host = Any()
        val received = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(host) { received += it }
        channel.onHostResumed(host)
        channel.subscribe(host, null)
        channel.deliver(share)
        assertEquals(emptyList<ExternalNavigationRequest>(), received)
        // The request stayed on the staging slot and drains to the host that binds next.
        val other2 = Any()
        val landed = mutableListOf<ExternalNavigationRequest>()
        channel.subscribe(other2) { landed += it }
        channel.onHostResumed(other2)
        assertEquals(listOf<ExternalNavigationRequest>(share), landed)
    }
}
