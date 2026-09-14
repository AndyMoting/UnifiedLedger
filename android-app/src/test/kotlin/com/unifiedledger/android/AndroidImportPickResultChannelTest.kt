package com.unifiedledger.android

import com.unifiedledger.ui.ImportFilePickResult
import com.unifiedledger.ui.ImportFilePickResultChannel
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * P7-04.C android pick-channel wiring evidence (D-146; spec section 4.1.1): no Robolectric — the
 * injection pattern of [AndroidStartupControllerTest]/[AndroidImportFilePickPortTest] drives the
 * SAF port with `String` doubles. The P7-04.A fail-loud placeholder (`error("... not wired until
 * P7-04.C")`) is replaced by the shared [ImportFilePickResultChannel]: the port's `onResult`
 * delivers every typed SAF outcome into the channel, and the channel forwards to its subscribed
 * listener exactly once per delivery (the shared P503App host subscribes through the facade).
 */
class AndroidImportPickResultChannelTest {
    private fun channelPort(metadata: (String) -> PickedSafFileMetadata = { PickedSafFileMetadata("synthetic-statement.csv", null) }): Pair<AndroidImportFilePickPort<String>, ImportFilePickResultChannel> {
        val channel = ImportFilePickResultChannel()
        val port =
            AndroidImportFilePickPort(
                launchOpenDocument = { },
                resolveMetadata = metadata,
                openInputStream = { ByteArrayInputStream(ByteArray(0)) },
                onResult = channel::deliver,
            )
        return port to channel
    }

    @Test
    fun theChannelDeliversSafResultsToItsSubscriber() {
        val (port, channel) = channelPort()
        val received = mutableListOf<ImportFilePickResult>()
        channel.subscribe { received += it }

        port.onOpenDocumentResult(null)
        port.onOpenDocumentResult("synthetic-handle")

        assertEquals(2, received.size)
        assertIs<ImportFilePickResult.Cancelled>(received[0])
        val picked = assertIs<ImportFilePickResult.Picked>(received[1])
        assertEquals("synthetic-statement.csv", picked.file.displayName)
    }

    @Test
    fun deliveriesWithoutASubscriberAreAbsorbedNotDroppedLoudly() {
        val (port, channel) = channelPort()
        // A delivery before the shared host subscribes must not throw (the host subscribes at
        // composition; the pick port cannot outlive it in the product wiring).
        port.onOpenDocumentResult(null)
    }

    @Test
    fun aLateSubscriberReplacesThePreviousOne() {
        val (_, channel) = channelPort()
        val first = mutableListOf<ImportFilePickResult>()
        val second = mutableListOf<ImportFilePickResult>()
        channel.subscribe { first += it }
        channel.deliver(ImportFilePickResult.Cancelled)
        channel.subscribe { second += it }
        channel.deliver(ImportFilePickResult.Cancelled)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertSame(ImportFilePickResult.Cancelled, second[0])
    }
}
