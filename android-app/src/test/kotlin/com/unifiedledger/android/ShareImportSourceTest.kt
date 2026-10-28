package com.unifiedledger.android

import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.ExternalNavigationRequest
import com.unifiedledger.ui.IMPORT_FILE_PICK_MAX_READ_BYTES
import com.unifiedledger.ui.ImportPickReadFailure
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-09 09.A share-import closed loop (spec sections 2.3/4; D-225): the deliveryId-keyed retained
 * source registry and its Android port. No Robolectric — the registry and the port are driven with a
 * plain `String` handle (the `AndroidImportFilePickPortTest` injection pattern); the product root
 * instantiates them with `android.net.Uri`.
 *
 * The registry stores the retained `Uri` IN-PROCESS only (spec section 2.3 line 127 / section 9 line
 * 271 / D-146: never persisted, never disk/env, never `takePersistableUriPermission`), and RELEASES
 * the entry on take (consumption) / read / failure / cancel so no retained grant outlives its use.
 */
class ShareImportSourceTest {
    // ---- registry lifecycle (pure; the release-after-read/failure invariant) ----

    @Test
    fun theRegistryRetainsPerDeliveryAndReleasesOnTake() {
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://synthetic-provider/bill.csv")
        assertEquals("content://synthetic-provider/bill.csv", registry.take("delivery-1"))
        // Take REMOVES the entry: it is not reusable (a second take is the typed missing-source case).
        assertNull(registry.take("delivery-1"))
        assertEquals(0, registry.size())
    }

    @Test
    fun theRegistryReleasesAnUnconsumedEntryOnCancel() {
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://synthetic-provider/bill.csv")
        registry.release("delivery-1")
        assertNull(registry.take("delivery-1"))
        assertEquals(0, registry.size())
        // Releasing an absent entry is a harmless no-op.
        registry.release("delivery-1")
    }

    @Test
    fun theRegistryKeepsIndependentDeliveries() {
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://p/a.csv")
        registry.retain("delivery-2", "content://p/b.csv")
        assertEquals("content://p/a.csv", registry.take("delivery-1"))
        // Taking one delivery does not disturb the other (no cross-delivery clearing).
        assertEquals("content://p/b.csv", registry.take("delivery-2"))
    }

    // ---- the port over plain values ----

    private fun port(
        registry: ShareImportSourceRegistry<String>,
        metadata: (String) -> PickedSafFileMetadata = { PickedSafFileMetadata("shared-statement.csv", null) },
        open: (String) -> InputStream? = { ByteArrayInputStream(ByteArray(0)) },
    ): AndroidShareImportSourcePort<String> = AndroidShareImportSourcePort(registry = registry, resolveMetadata = metadata, openInputStream = open)

    @Test
    fun aRetainedDeliveryYieldsAPickedFileWhoseReadIsBounded() {
        val payload = ByteArray(50_000) { index -> (index % 251).toByte() }
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://synthetic-provider/bill.csv")
        val port =
            port(
                registry,
                metadata = { PickedSafFileMetadata("shared-statement.csv", payload.size.toLong()) },
                open = { ByteArrayInputStream(payload) },
            )

        val file = requireNotNull(port.take("delivery-1"))
        assertEquals("shared-statement.csv", file.displayName)
        assertEquals(payload.size.toLong(), file.sizeBytes)
        assertContentEquals(payload, assertIs<BoundedFileRead.Bytes>(file.readBoundedBytes()).bytes)
        // The entry was released by the take (it cannot be read a second time).
        assertNull(port.take("delivery-1"))
    }

    @Test
    fun aMissingDeliveryIsTheTypedUnavailableNull() {
        val registry = ShareImportSourceRegistry<String>()
        val port = port(registry)
        assertNull(port.take("never-received"))
    }

    @Test
    fun aRevokedGrantIsTheTypedLifecycleReadFailure() {
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://synthetic-provider/bill.csv")
        val port = port(registry, open = { null })
        val file = requireNotNull(port.take("delivery-1"))
        val failed = assertIs<BoundedFileRead.ReadFailed>(file.readBoundedBytes())
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, failed.reason)
    }

    @Test
    fun aMidReadFailureIsTheTypedReadFailure() {
        val broken =
            object : InputStream() {
                override fun read(): Int = throw IOException("synthetic read failure")

                override fun read(buffer: ByteArray): Int = throw IOException("synthetic read failure")
            }
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://synthetic-provider/bill.csv")
        val port = port(registry, open = { broken })
        val file = requireNotNull(port.take("delivery-1"))
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, assertIs<BoundedFileRead.ReadFailed>(file.readBoundedBytes()).reason)
    }

    @Test
    fun aKnownOverLimitSizeShortCircuitsWithoutOpening() {
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://synthetic-provider/big.bin")
        var opens = 0
        val port =
            port(
                registry,
                metadata = { PickedSafFileMetadata("shared-big.bin", IMPORT_FILE_PICK_MAX_READ_BYTES + 5) },
                open = {
                    opens++
                    ByteArrayInputStream(ByteArray(0))
                },
            )
        val file = requireNotNull(port.take("delivery-1"))
        assertIs<BoundedFileRead.ExceedsLimit>(file.readBoundedBytes())
        assertEquals(0, opens, "the known over-limit size must short-circuit before any stream open")
    }

    @Test
    fun thePortReleaseRemovesAnUnconsumedRetainedDelivery() {
        val registry = ShareImportSourceRegistry<String>()
        registry.retain("delivery-1", "content://synthetic-provider/bill.csv")
        val port = port(registry)
        port.release("delivery-1")
        assertNull(port.take("delivery-1"))
    }

    // ---- the effective-URI selection the Activity retains (mirrors the snapshot's choice) ----

    @Test
    fun theEffectiveShareUriSelectionPrefersTheSingleStreamExtra() {
        // The pure shape used by MainActivity to decide what to retain: the single EXTRA_STREAM Uri
        // when it is the shared stream; null when there is nothing retainable.
        assertEquals(
            "extra",
            classifyShareEffectiveUriChoice(streamExtraSingle = true, clipDataSingle = true),
        )
        assertEquals("clip", classifyShareEffectiveUriChoice(streamExtraSingle = false, clipDataSingle = true))
        assertNull(classifyShareEffectiveUriChoice(streamExtraSingle = false, clipDataSingle = false))
        // A malformed/list extra never silently coerces to the ClipData item as the retained stream.
        assertNull(classifyShareEffectiveUriChoice(streamExtraSingle = false, clipDataSingle = true, streamExtraPresentButUnusable = true))
    }

    // ---- fix round B1: the registry is BOUNDED and reports containment (never an unbounded leak) ----

    @Test
    fun theRegistryIsBoundedAndEvictsTheEldestRetainedHandle() {
        // B1: the leak backstop — the registry never grows without bound. With the single pending slot
        // at most ~2 handles are meaningfully live; beyond MAX_RETAINED the eldest is evicted.
        val registry = ShareImportSourceRegistry<String>()
        repeat(ShareImportSourceRegistry.MAX_RETAINED + 3) { index -> registry.retain("delivery-$index", "content://p/$index.csv") }
        assertEquals(ShareImportSourceRegistry.MAX_RETAINED, registry.size())
        // The eldest entries were evicted; only the newest MAX_RETAINED survive.
        assertNull(registry.take("delivery-0"))
        val newest = ShareImportSourceRegistry.MAX_RETAINED + 2
        assertEquals("content://p/$newest.csv", registry.take("delivery-$newest"))
    }

    @Test
    fun theRegistryReportsContainmentForTheNonSilentRepeatProbe() {
        // B1/B3: contains is the read-only probe MainActivity uses to decide whether a refused repeat
        // delivery still has a retained source to re-hold (non-silent), and take/release clear it.
        val registry = ShareImportSourceRegistry<String>()
        assertEquals(false, registry.contains("delivery-1"))
        registry.retain("delivery-1", "content://p/a.csv")
        assertTrue(registry.contains("delivery-1"))
        registry.release("delivery-1")
        assertEquals(false, registry.contains("delivery-1"))
        registry.retain("delivery-1", "content://p/a.csv")
        registry.take("delivery-1")
        assertEquals(false, registry.contains("delivery-1"))
    }

    // ---- fix round B3: a refused repeat delivery is recognized (non-silent re-hold) ----

    @Test
    fun aRefusedRepeatColdLaunchDeliveryIsRecognizedForNonSilentReHold() {
        // B3: a config recreation re-reads the SAME launch Intent. The first read admits it; the
        // repeat is REFUSED (no re-consume) but is recognizable by its stable id, so the Activity can
        // re-hold it (non-silent) while its retained source is present.
        val intake = ProcessInboundNavigationIntake { "cold-delivery" }
        val first = assertIs<ExternalNavigationRequest.OpenImport>(intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = false))
        assertEquals("cold-delivery", first.deliveryId)
        val receipt = intake.coldLaunchReceipt()
        assertEquals("cold-delivery", receipt?.deliveryId)
        assertTrue(intake.isRefusedRepeat("cold-delivery"))
        // The repeated request is refused (already consumed) — but the id is stable for the re-hold.
        assertNull(intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = true))
        assertEquals("cold-delivery", intake.coldLaunchReceipt()?.deliveryId)
        // No cold receipt exists before any cold share.
        assertEquals(false, ProcessInboundNavigationIntake { "x" }.coldLaunchReceipt() != null)
    }

    @Test
    fun aHotReceiptIsNeverARepeatedColdDelivery() {
        // B3: only the cold-launch receipt is re-holdable; a hot onNewIntent share mints a FRESH id
        // distinct from the cold id, and a repeat of the cold Intent is still recognized (unchanged).
        val intake = ProcessInboundNavigationIntake { "id-${mintCounter++}" }
        assertIs<ExternalNavigationRequest.OpenImport>(intake.coldLaunchRequest(snapshot(), reuseHeldReceipt = false))
        val hot = assertIs<ExternalNavigationRequest.OpenImport>(intake.hotReceiptRequest(snapshot()))
        assertEquals("id-1", hot.deliveryId)
        assertEquals("id-0", intake.coldLaunchReceipt()?.deliveryId)
        // The hot id is its own receipt, not the cold id; the cold id remains the re-holdable one.
        assertTrue(intake.isRefusedRepeat("id-0"))
    }

    private var mintCounter = 0

    private fun snapshot() =
        ShareIntentSnapshot(
            action = android.content.Intent.ACTION_SEND,
            mimeType = "text/csv",
            streamUri = "content://synthetic-provider/bill.csv",
            streamScheme = "content",
            clipDataUris = emptyList(),
            clipDataScheme = null,
            readableUris = listOf("content://synthetic-provider/bill.csv"),
            declaredSizeBytes = null,
            hasUnexpectedStreamExtra = false,
        )
}
