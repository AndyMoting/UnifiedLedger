package com.unifiedledger.android

import android.content.Intent
import android.net.Uri
import com.unifiedledger.ui.ExternalNavigationRequest
import com.unifiedledger.ui.IMPORT_FILE_PICK_MAX_READ_BYTES

/**
 * P7-09 09.A (spec sections 2.1/2.3; D-225 Q19): the typed outcome of validating one platform
 * entry Intent into a neutral [ExternalNavigationRequest]. The rejection-code family is spec
 * section 8 open item 1; the members below are this batch's implementation of the frozen
 * "typed rejection, stream closed, zero formal effect" rule (registered in the batch report).
 */
sealed interface ExternalNavigationIntakeResult {
    /** The Intent validated into one neutral request; nothing has been read or written yet. */
    data class Accepted(
        val request: ExternalNavigationRequest,
    ) : ExternalNavigationIntakeResult

    /**
     * The Intent is not a share we accept. [code] is a stable, display-safe literal (no URI, no
     * file name, no exception text). Nothing was opened, read or written.
     */
    data class Rejected(
        val code: ShareIntakeRejection,
    ) : ExternalNavigationIntakeResult

    /**
     * The Intent is not an inbound entry at all (an ordinary `MAIN`/`LAUNCHER` launch, or an
     * unrelated action): there is nothing to route and nothing to reject.
     */
    data object NotAnInboundEntry : ExternalNavigationIntakeResult
}

/**
 * P7-09 09.A (spec section 2.3 table): the typed rejection vectors of the share validation face.
 * MIME/extension/declared length are only hints and never decide acceptance; the action, the
 * single `content://` stream, the `EXTRA_STREAM`/`ClipData` agreement and the actual read grant
 * do.
 */
enum class ShareIntakeRejection {
    /** The action is not exactly `ACTION_SEND` (includes `ACTION_SEND_MULTIPLE`). */
    UNSUPPORTED_ACTION,

    /** No stream extra and no ClipData item at all. */
    MISSING_STREAM,

    /** More than one stream (EXTRA_STREAM list, or multiple ClipData items). */
    MULTIPLE_STREAMS,

    /** `EXTRA_STREAM` and `ClipData` disagree about which stream is being shared. */
    CONFLICTING_STREAM,

    /** The stream is not a `content://` URI (`file://`, `http(s)://`, opaque schemes). */
    NON_CONTENT_URI,

    /** The stream URI is not actually readable (no grant, or a grant revoked before the read). */
    STREAM_NOT_READABLE,

    /** The extras are malformed (wrong type, an unexpected key carrying a stream, a null URI). */
    MALFORMED_EXTRAS,

    /** The declared size (a hint) already exceeds the frozen 16 MiB L0 bound. */
    DECLARED_SIZE_EXCEEDS_LIMIT,

    /** The Intent carried no usable share surface at all. */
    NOT_A_SHARE,
}

/**
 * P7-09 09.A (spec sections 2.1/2.3; D-225 Q19): the platform-neutral snapshot of one inbound
 * share Intent. Every field is a plain value (no `Uri`, no `Intent`, no `ContentResolver`), so
 * [validateShareIntake] is a pure function testable on a plain JVM without Robolectric. The
 * Android adapter [snapshotShareIntent] fills it from the real Intent; the platform objects stay
 * in this composition root (spec section 2.1: `common` never sees them).
 *
 * All URI fields are the STRING forms, used only for the cardinality/consistency/scheme
 * decisions — they are never persisted, never used as identity and never leave this module
 * (D-146/D06).
 */
internal class ShareIntentSnapshot(
    val action: String?,
    val mimeType: String?,
    /** The `EXTRA_STREAM` URI string, or null when the extra is absent/not a URI. */
    val streamUri: String?,
    /** The scheme of [streamUri] (null when there is no stream extra). */
    val streamScheme: String?,
    /** The ClipData item URI strings, in item order. */
    val clipDataUris: List<String>,
    /** The scheme of the single ClipData item's URI (null when there is no ClipData item). */
    val clipDataScheme: String?,
    /** The candidate URI strings the platform reports as actually readable. */
    val readableUris: List<String>,
    /** A declared size hint, when the Intent already carries one; null otherwise. */
    val declaredSizeBytes: Long?,
    /** True when the extras carried an unexpected key that also looks like a stream. */
    val hasUnexpectedStreamExtra: Boolean,
)

/**
 * P7-09 09.A (spec section 2.3): THE share validation face. Pure: it decides from the snapshot
 * only and never opens a stream, so a rejection has zero formal effect by construction.
 *
 * [deliveryId] is minted by the caller (the composition root's id ledger) and travels only into
 * the accepted request; the validator itself neither mints nor persists anything. The check order
 * is the frozen table order (action, extras, cardinality, consistency, scheme, readability, size
 * hint); a rejection never falls through to acceptance.
 */
internal fun validateShareIntake(
    snapshot: ShareIntentSnapshot,
    deliveryId: String,
): ExternalNavigationIntakeResult {
    if (snapshot.action != Intent.ACTION_SEND) {
        return if (snapshot.action == Intent.ACTION_SEND_MULTIPLE) {
            ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.MULTIPLE_STREAMS)
        } else {
            ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.UNSUPPORTED_ACTION)
        }
    }
    if (snapshot.hasUnexpectedStreamExtra) {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.MALFORMED_EXTRAS)
    }
    val stream = snapshot.streamUri
    if (stream == null && snapshot.clipDataUris.isEmpty()) {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.MISSING_STREAM)
    }
    // Cardinality first: more than one stream anywhere (several ClipData items, with or without a
    // stream extra) is a multi-file share this slice does not accept.
    if (snapshot.clipDataUris.size > 1) {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.MULTIPLE_STREAMS)
    }
    if (stream != null && snapshot.clipDataUris.size == 1 && snapshot.clipDataUris.single() != stream) {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.CONFLICTING_STREAM)
    }
    // The scheme of whichever single stream is actually being shared (the extra, or the single
    // ClipData item). A non-content:// stream is rejected either way.
    val effectiveScheme = if (stream != null) snapshot.streamScheme else snapshot.clipDataScheme
    if (effectiveScheme != "content") {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.NON_CONTENT_URI)
    }
    val effective = stream ?: snapshot.clipDataUris.singleOrNull() ?: return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.MISSING_STREAM)
    if (effective !in snapshot.readableUris) {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.STREAM_NOT_READABLE)
    }
    val declared = snapshot.declaredSizeBytes
    if (declared != null && declared > IMPORT_FILE_PICK_MAX_READ_BYTES) {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.DECLARED_SIZE_EXCEEDS_LIMIT)
    }
    return ExternalNavigationIntakeResult.Accepted(ExternalNavigationRequest.OpenImport(deliveryId = deliveryId))
}

/**
 * P7-09 09.A (spec section 2.3): the Android adapter — a read-only inspection of the received
 * Intent. It performs NO stream open and NO provider query, so a rejected share leaves zero
 * trace. [declaredSizeBytes] stays null here: resolving it needs a provider query the read path
 * performs later under the user's explicit format choice (spec section 2.3 read timing).
 */
internal fun snapshotShareIntent(
    intent: Intent,
    isUriReadable: (Uri) -> Boolean,
): ShareIntentSnapshot {
    val rawStream: Uri? =
        try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } catch (failure: Exception) {
            null
        }
    val clipItems = intent.clipData?.let { clip -> (0 until clip.itemCount).map { index -> clip.getItemAt(index).uri } } ?: emptyList()
    val clipDataUris = clipItems.mapNotNull { uri -> uri?.toString() }
    // The candidate the share actually delivers: the EXTRA_STREAM extra, or the single ClipData
    // item when there is no extra (the official receive path accepts either). Readability is
    // probed for THAT URI only; a rejection short-circuits before any read.
    val effectiveUri = rawStream ?: clipItems.singleOrNull()
    val readable = if (effectiveUri != null && isUriReadable(effectiveUri)) listOfNotNull(effectiveUri.toString()) else emptyList()
    return ShareIntentSnapshot(
        action = intent.action,
        mimeType = intent.type,
        streamUri = rawStream?.toString(),
        streamScheme = rawStream?.scheme,
        clipDataUris = clipDataUris,
        clipDataScheme = clipItems.singleOrNull()?.scheme,
        readableUris = readable,
        declaredSizeBytes = null,
        hasUnexpectedStreamExtra = false,
    )
}

/**
 * P7-09 09.0 (spec section 4): the locally minted, opaque inbound identity ledger. `ACTION_SEND`
 * has no universal "same share" stable id, so the ONLY guarantee is that a deliveryId already
 * received is not consumed twice (idempotent consumption). A new user share / a new
 * `onNewIntent` receipt mints a NEW deliveryId even when the URI or bytes are identical (D-146:
 * a new import, subject to the human suspected-duplicate review).
 *
 * Pure and JVM-testable (no Android types); the composition root owns one instance per process.
 */
internal class DeliveryIdLedger {
    private val consumed = HashSet<String>()

    /** True exactly once per [deliveryId]: the first consumption. */
    fun consumeOnce(deliveryId: String): Boolean = consumed.add(deliveryId)
}

/**
 * P7-09 09.A (spec sections 2.2/2.3/4): the receipt→request decision one Activity makes for an
 * inbound Intent (a cold-start launch or an `onNewIntent` delivery). Extracted from [MainActivity]
 * so the receipt semantics are JVM-assertable without Robolectric (the intake-injection pattern):
 * a `MAIN` launch is not an inbound entry, a valid share mints a fresh deliveryId and yields the
 * neutral request, and a repeated internal delivery of the SAME deliveryId is consumed at most
 * once (idempotent consumption — the only guarantee `ACTION_SEND` permits).
 *
 * [mintDeliveryId] is injected so the product mints an opaque UUID and the test pins a fixed id;
 * the class never inspects a URI beyond the snapshot it is handed.
 */
internal class InboundNavigationIntake(
    private val mintDeliveryId: () -> String,
) {
    private val delivered = DeliveryIdLedger()

    /**
     * The neutral request for one received Intent, or null when there is nothing to deliver (an
     * ordinary launch, a typed rejection, or a repeated delivery of an already-consumed id).
     * Zero formal effect on every null return: nothing is read, written or queued.
     */
    fun requestFor(snapshot: ShareIntentSnapshot): ExternalNavigationRequest? {
        if (snapshot.action == Intent.ACTION_MAIN) return null
        val deliveryId = mintDeliveryId()
        return when (val result = validateShareIntake(snapshot, deliveryId)) {
            is ExternalNavigationIntakeResult.Accepted -> if (delivered.consumeOnce(deliveryId)) result.request else null
            is ExternalNavigationIntakeResult.Rejected -> null
            ExternalNavigationIntakeResult.NotAnInboundEntry -> null
        }
    }
}
