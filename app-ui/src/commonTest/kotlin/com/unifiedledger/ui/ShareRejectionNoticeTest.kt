package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7-09 09.A refused-share evidence (spec section 2.3; ACC-D-SHARE-02). A share the validation face
 * refuses has NO request to route, so before this batch its typed code was discarded and the screen
 * simply stayed as it was — the `-> Unit` silent drop the frozen contract forbids.
 *
 * These assertions cover the transport (the refusal rides the channel on the same resumed-host and
 * identity discipline as a request, on its own depth-one slot) and the copy (one distinct,
 * payload-free string per vector). The RENDERING is host-level by design — a refusal has no slot to
 * land in and must show on surfaces whose state class carries no Busy field — so the render seam and
 * its subscription are pinned on the source by `ShareDeliveryHostWiringTest` in `android-app`
 * (the `theCompositionBindsAndUnbindsTheSameHostToken` precedent).
 */
class ShareRejectionNoticeTest {
    /** A host token whose value equality comes from a data class: two instances are equal but
     *  distinct, which is exactly the trap the channel's identity rule must survive (D-260 P709SD2-01). */
    private data class HostToken(
        val id: Int,
    )

    @Test
    fun aRefusalParksUntilTheResumedHostCanShowIt() {
        val channel = ExternalNavigationChannel()
        val seen = mutableListOf<ShareRejectionReason>()
        val host = HostToken(1)
        channel.subscribeShareRejections(host) { seen += it }
        channel.notifyShareRejection(ShareRejectionReason.NON_CONTENT_URI)
        assertTrue(seen.isEmpty(), "a subscription alone must never receive: only the RESUMED host may")
        channel.onHostResumed(host)
        assertEquals(listOf(ShareRejectionReason.NON_CONTENT_URI), seen)
    }

    @Test
    fun aRefusalNeverDisplacesAParkedRequestAndBothArriveInOrder() {
        val channel = ExternalNavigationChannel()
        val requests = mutableListOf<ExternalNavigationRequest>()
        val refusals = mutableListOf<ShareRejectionReason>()
        val host = HostToken(1)
        // A cold-start share is parked first; the refusal must not overwrite that staging slot.
        channel.deliver(ExternalNavigationRequest.OpenImport("delivery-1"))
        channel.notifyShareRejection(ShareRejectionReason.MULTIPLE_STREAMS)
        channel.subscribe(host) { requests += it }
        channel.subscribeShareRejections(host) { refusals += it }
        channel.onHostResumed(host)
        assertEquals(listOf<ExternalNavigationRequest>(ExternalNavigationRequest.OpenImport("delivery-1")), requests)
        assertEquals(listOf(ShareRejectionReason.MULTIPLE_STREAMS), refusals)
    }

    @Test
    fun aRefusalIsDepthOneAndOnlyItsLatestValueSurvives() {
        val channel = ExternalNavigationChannel()
        val seen = mutableListOf<ShareRejectionReason>()
        val host = HostToken(1)
        channel.notifyShareRejection(ShareRejectionReason.MISSING_STREAM)
        channel.notifyShareRejection(ShareRejectionReason.MALFORMED_EXTRAS)
        channel.subscribeShareRejections(host) { seen += it }
        channel.onHostResumed(host)
        assertEquals(listOf(ShareRejectionReason.MALFORMED_EXTRAS), seen, "the transport stages exactly ONE refusal")
    }

    @Test
    fun onlyTheResumedHostsIdentityReceivesTheRefusal() {
        val channel = ExternalNavigationChannel()
        val first = HostToken(1)
        val second = HostToken(1) // value-equal, distinct instance
        val firstSeen = mutableListOf<ShareRejectionReason>()
        val secondSeen = mutableListOf<ShareRejectionReason>()
        channel.subscribeShareRejections(first) { firstSeen += it }
        channel.subscribeShareRejections(second) { secondSeen += it }
        channel.onHostResumed(second)
        channel.notifyShareRejection(ShareRejectionReason.STREAM_NOT_READABLE)
        assertEquals(listOf(ShareRejectionReason.STREAM_NOT_READABLE), secondSeen)
        assertTrue(firstSeen.isEmpty(), "a value-equal but distinct token must not take another host's refusal")
    }

    @Test
    fun anUnboundHostLeavesTheRefusalParkedForTheNextBinding() {
        val channel = ExternalNavigationChannel()
        val seen = mutableListOf<ShareRejectionReason>()
        val host = HostToken(1)
        channel.subscribeShareRejections(host) { seen += it }
        channel.onHostResumed(host)
        channel.subscribeShareRejections(host, null) // the composition disposed
        channel.notifyShareRejection(ShareRejectionReason.CONFLICTING_STREAM)
        assertTrue(seen.isEmpty(), "a disposed host must not receive anything")
        channel.subscribeShareRejections(host) { seen += it }
        assertEquals(listOf(ShareRejectionReason.CONFLICTING_STREAM), seen, "the parked refusal drains into the next binding")
    }

    @Test
    fun aRefusalOlderThanADeliveredRequestIsDroppedInsteadOfReappearingBesideIt() {
        // Device finding: the two staging slots drain request-first, so an older parked refusal would
        // otherwise surface together with the fresh share face (seen on ul_p7_d01 during the
        // startup-failure retry leg).
        val channel = ExternalNavigationChannel()
        val host = HostToken(1)
        val requests = mutableListOf<ExternalNavigationRequest>()
        val refusals = mutableListOf<ShareRejectionReason>()
        channel.notifyShareRejection(ShareRejectionReason.NON_CONTENT_URI) // older
        channel.deliver(ExternalNavigationRequest.OpenImport("delivery-1")) // newer
        channel.subscribe(host) { requests += it }
        channel.subscribeShareRejections(host) { refusals += it }
        channel.onHostResumed(host)
        assertEquals(1, requests.size)
        assertTrue(refusals.isEmpty(), "the older refusal must be dropped, not shown beside the accepted request")
    }

    @Test
    fun aRefusalNewerThanTheDeliveredRequestIsStillShown() {
        // The mirror case the clear-on-delivery must NOT swallow: a refusal that arrives after the
        // request is the newest inbound result and belongs on screen.
        val channel = ExternalNavigationChannel()
        val host = HostToken(1)
        val requests = mutableListOf<ExternalNavigationRequest>()
        val refusals = mutableListOf<ShareRejectionReason>()
        channel.subscribe(host) { requests += it }
        channel.subscribeShareRejections(host) { refusals += it }
        channel.onHostResumed(host)
        channel.deliver(ExternalNavigationRequest.OpenImport("delivery-1"))
        channel.notifyShareRejection(ShareRejectionReason.MISSING_STREAM)
        assertEquals(1, requests.size)
        assertEquals(listOf(ShareRejectionReason.MISSING_STREAM), refusals)
    }

    @Test
    fun theRefusalCopyIsDistinctPerVectorAndCarriesNoInboundPayload() {
        val copies = ShareRejectionReason.values().map { shareRejectionText(it) }
        assertEquals(ShareRejectionReason.values().size, copies.toSet().size, "each vector needs its own copy")
        copies.forEach { copy ->
            assertFalse(copy.contains("content://"), "copy must not name a provider or scheme: $copy")
            assertFalse(copy.contains("/"), "copy must not carry any path fragment: $copy")
            assertFalse(copy.contains(".csv"), "copy must not carry a file name: $copy")
            assertFalse(copy.contains("bill"), "copy must not carry the sender's sample name: $copy")
        }
    }

    @Test
    fun theDetailedVectorWinsOverTheCarrierReason() {
        ShareRejectionReason.values().forEach { reason ->
            val busy = ExternalNavigationBusy(ExternalNavigationHoldReason.IMPORT_REJECTED, reason)
            assertEquals(shareRejectionText(reason), externalNavigationBusyText(busy))
        }
        // A carrier with no vector still says something (never an empty strip).
        val carrierOnly = ExternalNavigationBusy(ExternalNavigationHoldReason.IMPORT_REJECTED)
        assertFalse(externalNavigationBusyText(carrierOnly).isBlank())
    }
}
