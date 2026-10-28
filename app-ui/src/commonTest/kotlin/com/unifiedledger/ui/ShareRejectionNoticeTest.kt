package com.unifiedledger.ui

import com.unifiedledger.application.LedgerCurrentState
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P7-09 09.A refused-share evidence (spec section 2.3; ACC-D-SHARE-02). A share the validation face
 * refuses has NO request to route, so before this batch its typed code was discarded and the screen
 * simply stayed as it was — the `-> Unit` silent drop the frozen contract forbids. These assertions pin
 * the replacement: the refusal rides the channel on the same resumed-host discipline as a request,
 * lands the visible strip with its typed vector, and never touches the single pending slot.
 */
class ShareRejectionNoticeTest {
    private val ledgerId = LedgerId("ledger-local-test")
    private val cny = CurrencyUnit("CNY", 2)
    private val reducer = P503ReducerImpl(ParseManualExpenseAmount(), cny)

    private fun emptyState() = LedgerCurrentState(ledgerId, transactions = emptyList(), balances = emptyList())

    private fun overview() = P503AppState.OverviewEmpty(emptyState())

    /** A host token whose value equality is inherited from a data class: two instances are equal but
     *  distinct, which is exactly the trap the channel's identity rule must survive (D-260 P709SD2-01). */
    private data class HostToken(
        val id: Int,
    )

    // ---------------------------------------------------------------- channel transport

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

    // ---------------------------------------------------------------- reducer

    @Test
    fun aRefusalLandsTheVisibleStripWithoutTouchingThePendingSlot() {
        val held =
            PendingExternalNavigation(
                ExternalNavigationRequest.OpenImport("delivery-1"),
                ExternalNavigationHoldReason.DRAFT_IN_PROGRESS,
            )
        val state = P503AppState.OverviewEmpty(emptyState(), pendingExternalNavigation = held)
        val landed = reducer.reduce(state, P503UiEvent.ShareRejectionLanded(ShareRejectionReason.CONFLICTING_STREAM))
        assertEquals(ShareRejectionReason.CONFLICTING_STREAM, landed.externalNavigationBusyOrNull()?.shareRejection)
        assertEquals(ExternalNavigationHoldReason.IMPORT_REJECTED, landed.externalNavigationBusyOrNull()?.reason)
        assertSame(held, landed.pendingExternalNavigationSlot(), "a refusal must never rewrite the held request")
        val dismissed = reducer.reduce(landed, P503UiEvent.DismissExternalNavigationBusy)
        assertNull(dismissed.externalNavigationBusyOrNull())
        assertSame(held, dismissed.pendingExternalNavigationSlot())
    }

    @Test
    fun aRefusalIsVisibleOnEverySlotSurface() {
        // The refusal must reach the user wherever they are: the strip's field exists on every surface
        // class that carries a pending slot, and the reducer lands it on each of them.
        val surfaces =
            listOf(
                overview(),
                P503AppState.Ready(),
            )
        surfaces.forEach { surface ->
            val landed = reducer.reduce(surface, P503UiEvent.ShareRejectionLanded(ShareRejectionReason.MISSING_STREAM))
            assertEquals(
                ShareRejectionReason.MISSING_STREAM,
                landed.externalNavigationBusyOrNull()?.shareRejection,
                "the refusal strip must render on ${surface::class.simpleName}",
            )
        }
    }

    // ---------------------------------------------------------------- copy

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
