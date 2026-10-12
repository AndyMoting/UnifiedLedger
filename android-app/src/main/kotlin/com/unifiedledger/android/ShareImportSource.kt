package com.unifiedledger.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.ImportPickRawRead
import com.unifiedledger.ui.PickedImportFile
import com.unifiedledger.ui.ShareImportSourcePort
import com.unifiedledger.ui.readImportPickBounded
import java.io.IOException
import java.io.InputStream

/**
 * P7-09 09.A share-import closed loop (spec sections 2.3/4; D-225; batch invariants 2/3):
 * the deliveryId-keyed registry of the ACCEPTED share's platform handle, retained IN-PROCESS only.
 *
 * Invariant 2 (spec section 2.3 line 127 / section 9 line 271; D-146): the platform `Uri` NEVER
 * leaves the Android composition root. It is retained here keyed by the neutral `deliveryId`, is
 * NEVER persisted, NEVER written to disk or env, and this code path NEVER calls
 * `takePersistableUriPermission`. [take] REMOVES the entry (consumption is one-shot), so no retained
 * grant outlives its use; [release] drops an unconsumed entry when the user abandons the choice.
 *
 * Fix round (B1; spec section 2.2 line 106 禁止静默丢弃): the access is synchronized (the receipt
 * runs on the Activity thread, the release runs from the host dispatch path) and BOUNDED — at most
 * [MAX_RETAINED] handles, eldest evicted. With the frozen single pending slot (plus at most one
 * displaced request) at most two handles are ever meaningfully live; the bound is a leak backstop,
 * and the host additionally releases on EVERY abandon path.
 *
 * The registry is generic over the handle type so it is JVM-assertable with a plain `String` (the
 * `AndroidImportFilePickPort` injection pattern); the product root instantiates it with `Uri`.
 */
internal class ShareImportSourceRegistry<T> {
    private val retained = LinkedHashMap<String, T>()
    private val lock = Any()

    /** Retains one platform handle for [deliveryId] until it is taken or released. */
    fun retain(deliveryId: String, handle: T) {
        synchronized(lock) {
            retained[deliveryId] = handle
            // Bounded: evict the eldest unconsumed handle beyond the cap (a leak backstop only; the
            // single pending slot means at most ~2 are ever meaningfully live).
            while (retained.size > MAX_RETAINED) {
                val eldest = retained.keys.firstOrNull() ?: break
                retained.remove(eldest)
            }
        }
    }

    /** Removes and returns the handle for [deliveryId], or null when none is retained (one-shot). */
    fun take(deliveryId: String): T? = synchronized(lock) { retained.remove(deliveryId) }

    /** Drops an unconsumed handle (the choice was abandoned); a no-op when none is retained. */
    fun release(deliveryId: String) {
        synchronized(lock) {
            retained.remove(deliveryId)
            Unit
        }
    }

    /** Whether an unconsumed handle is currently retained for [deliveryId] (B3 re-delivery probe). */
    fun contains(deliveryId: String): Boolean = synchronized(lock) { retained.containsKey(deliveryId) }

    /** The number of retained-but-unconsumed handles (test/observability only). */
    fun size(): Int = synchronized(lock) { retained.size }

    companion object {
        /** The bounded capacity of the retained-handle backstop (see the class KDoc). */
        const val MAX_RETAINED: Int = 4
    }
}

/**
 * P7-09 09.A (spec sections 2.3/4; D-225): the Android implementation of the app-ui
 * [ShareImportSourcePort]. Given the opaque [deliveryId] of the held share it takes the retained
 * platform handle from the registry and resolves it into the neutral [PickedImportFile] whose lazy
 * `readBoundedBytes` closure reuses the EXISTING [readImportPickBounded] L0 driver — the SAME
 * bounded reader the SAF pick port uses (invariant 3: no second reader, no second parser, no second
 * intake).
 *
 * The handle type is generic and the metadata/stream openers are injected lambdas, so the port is
 * JVM-testable with a plain `String` handle; the product root wires `android.net.Uri`. A missing
 * entry returns null (spec section 4: the typed re-share case — grant gone / process death), never a
 * silent drop.
 */
internal class AndroidShareImportSourcePort<T>(
    private val registry: ShareImportSourceRegistry<T>,
    private val resolveMetadata: (T) -> PickedSafFileMetadata,
    private val openInputStream: (T) -> InputStream?,
) : ShareImportSourcePort {
    /**
     * Takes the retained handle for [deliveryId] and wraps it as a [PickedImportFile]. The heavy
     * content read stays LAZY (it runs inside the existing pipeline, off the UI thread); the take
     * itself is a cheap in-process map removal with no provider query.
     */
    override fun take(deliveryId: String): PickedImportFile? {
        val handle = registry.take(deliveryId) ?: return null
        val metadata = resolveMetadata(handle)
        return PickedImportFile(
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            readBoundedBytes = { readBounded(handle, metadata.sizeBytes) },
        )
    }

    override fun release(deliveryId: String) {
        registry.release(deliveryId)
    }

    /**
     * The L0 adapter over the retained handle: feed the platform stream closures to the shared
     * [readImportPickBounded] driver (invariant 3). Thread contract: synchronous; the host dispatches
     * this closure on a non-UI thread (spec section 2.4). A revoked grant surfaces as the typed
     * [com.unifiedledger.ui.ImportPickReadFailure.STREAM_OPEN_FAILED], never raw exception text (D06).
     */
    private fun readBounded(
        handle: T,
        sizeBytes: Long?,
    ): BoundedFileRead =
        readImportPickBounded(sizeBytes) {
            val stream = openInputStream(handle) ?: throw IOException("no stream provider for the retained share")
            ImportPickRawRead(
                read = { buffer -> stream.read(buffer) },
                close = { stream.close() },
            )
        }
}

/**
 * P7-09 09.A (spec sections 2.3/4; D-225): the product wiring of the retained-share registry and
 * port with an `android.net.Uri` handle. The registry is PROCESS-scoped (owned by
 * [LedgerProcessHolder]) so a cold-start share's retained handle survives an Activity recreation,
 * while the handle itself is never persisted (D-146/invariant 2).
 */
internal fun androidShareImportSourcePort(
    registry: ShareImportSourceRegistry<Uri>,
    resolver: ContentResolver,
): AndroidShareImportSourcePort<Uri> =
    AndroidShareImportSourcePort(
        registry = registry,
        resolveMetadata = { uri -> resolveSafFileMetadata(resolver, uri) },
        openInputStream = { uri -> resolver.openInputStream(uri) },
    )

/**
 * P7-09 09.A (spec section 2.3): which raw platform URI the Activity must RETAIN for a validated
 * share. The single `EXTRA_STREAM` Uri wins when it is the shared stream; otherwise the single
 * ClipData item is retained; when there is neither (or a present-but-unusable stream extra that must
 * never be silently coerced), nothing is retained. Pure and JVM-testable; the caller passes the
 * snapshot's already-classified shapes, so no `Uri` construction is needed here.
 */
internal fun classifyShareEffectiveUriChoice(
    streamExtraSingle: Boolean,
    clipDataSingle: Boolean,
    streamExtraPresentButUnusable: Boolean = false,
): String? =
    when {
        streamExtraPresentButUnusable -> null
        streamExtraSingle -> "extra"
        clipDataSingle -> "clip"
        else -> null
    }

/**
 * P7-09 09.A (spec section 2.3): the retained platform URI of a validated share, chosen by the pure
 * [classifyShareEffectiveUriChoice] rule from the received Intent. Returns null when nothing is
 * retainable (this is only called after [validateShareIntake] accepted, so it is the ClipData-only
 * or extra-only effective stream). Does NOT open a stream or query the provider.
 */
internal fun retainedShareUri(intent: Intent): Uri? {
    // The WIP intake adapter's own idiom (ExternalNavigationIntake.snapshotShareIntent): the raw extra
    // is untrusted, so the access is guarded and the value is classified with `as?` (never a blind
    // cast). No stream is opened and no provider is queried.
    val rawStreamExtra: Any? =
        try {
            intent.extras?.get(Intent.EXTRA_STREAM)
        } catch (failure: Exception) {
            null
        }
    val streamUri = rawStreamExtra as? Uri
    val clipUri = intent.clipData?.let { clip -> if (clip.itemCount == 1) clip.getItemAt(0).uri else null }
    val choice = classifyShareEffectiveUriChoice(streamExtraSingle = streamUri != null, clipDataSingle = clipUri != null)
    return when (choice) {
        "extra" -> streamUri
        "clip" -> clipUri
        else -> null
    }
}
