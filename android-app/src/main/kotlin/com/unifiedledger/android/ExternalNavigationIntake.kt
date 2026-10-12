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
    /** The `EXTRA_STREAM` URI string, or null when the extra is absent/not a single URI. */
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
    /**
     * True when `EXTRA_STREAM` was present but unusable — neither a single Uri nor a Uri list (a
     * wrong type, an opaque value, or an explicit null). The spec table's 异常 extras vector.
     */
    val hasUnexpectedStreamExtra: Boolean,
    /**
     * P7-09 09.A fix (P2-4; spec section 2.3 table): true when `EXTRA_STREAM` carried a LIST of
     * URIs rather than one URI. The frozen first slice accepts a single stream only, so a list is
     * a typed [ShareIntakeRejection.MULTIPLE_STREAMS] rejection — never silently coerced to its
     * first element nor allowed to fall through to the ClipData path as if it were a single
     * stream. Additive with a `false` default so the pre-fix construction sites keep compiling.
     */
    val streamExtraIsList: Boolean = false,
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
    // P2-4: an EXTRA_STREAM that carried a LIST of URIs is a multi-file share. It is rejected here,
    // BEFORE the cardinality/consistency checks below, so it can never be coerced to its first
    // element nor fall through to the single-ClipData path as if it were one stream.
    if (snapshot.streamExtraIsList) {
        return ExternalNavigationIntakeResult.Rejected(ShareIntakeRejection.MULTIPLE_STREAMS)
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
 * trace and nothing blocks the main thread on a provider (spec section 2.4: the bounded read
 * happens later, off the UI thread, under the user's explicit format choice).
 *
 * Both data-dependent fields are now genuinely populated from the received Intent (P2-3 fix):
 * [ShareIntentSnapshot.hasUnexpectedStreamExtra] detects a present-but-unusable `EXTRA_STREAM`
 * (wrong type / null / not a Uri), and [ShareIntentSnapshot.declaredSizeBytes] reads the sender's
 * declared-size HINT from the Intent extras. The hint is untrusted and normally absent — Android
 * defines no standard share-size extra, so most senders leave [ShareIntentSnapshot.declaredSizeBytes]
 * null (unknown size → accepted); only a sender that sets the recognized key produces a known size.
 *
 * Readability of the single candidate URI is decided by the injected [isUriReadable] probe, which
 * is a NON-opening permission check (no stream open, no provider query).
 */
internal fun snapshotShareIntent(
    intent: Intent,
    isUriReadable: (Uri) -> Boolean,
): ShareIntentSnapshot {
    // The raw EXTRA_STREAM value. Android documents a single Uri for ACTION_SEND, but an
    // untrusted sender can put anything there (a list, a String path, null). Each shape is
    // classified EXPLICITLY (the pure [classifyShareStreamExtra] seam) instead of a blind `as? Uri`
    // that would silently null a list (the P2-4 defect: a multi-stream extra must be recognized,
    // never coerced to a single stream).
    val rawStreamExtra: Any? =
        try {
            intent.extras?.get(Intent.EXTRA_STREAM)
        } catch (failure: Exception) {
            null
        }
    val streamExtraPresent = intent.hasExtra(Intent.EXTRA_STREAM)
    val streamExtraShape = classifyShareStreamExtra(present = streamExtraPresent, rawValue = rawStreamExtra)
    val streamExtraIsList = streamExtraShape == ShareStreamExtraShape.LIST
    val rawStream: Uri? = rawStreamExtra as? Uri
    // A present EXTRA_STREAM that is neither a Uri nor a Uri list (wrong type, an opaque value or an
    // explicit null) is the "异常 extras（类型错/null）" vector of the spec table.
    val streamExtraMalformed = streamExtraShape == ShareStreamExtraShape.MALFORMED
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
        declaredSizeBytes = parseDeclaredShareSize(declaredSizeHintExtra(intent)),
        hasUnexpectedStreamExtra = streamExtraMalformed,
        streamExtraIsList = streamExtraIsList,
    )
}

/**
 * P7-09 09.A (spec section 2.3; P2-4 fix): the SHAPE of a received `EXTRA_STREAM` extra. The raw
 * value is an untrusted `Any?`, so it is classified rather than blindly cast. Extracted as a pure
 * function (no `Intent`/`Uri` construction) so the P2-4 vector is JVM-testable at the adapter layer.
 */
internal enum class ShareStreamExtraShape {
    /** Neither a stream extra nor a value (a share that only carries ClipData, or none at all). */
    ABSENT,

    /** Exactly one `content://`-style `Uri` — the accepted share shape. */
    SINGLE_URI,

    /** A list/array of URIs (multi-stream); the first slice rejects it (P2-4). */
    LIST,

    /** Present but neither a Uri nor a Uri list (wrong type, opaque value, or an explicit null). */
    MALFORMED,
}

/**
 * P7-09 09.A (spec section 2.3; P2-4 fix): classifies the raw `EXTRA_STREAM` value. [present] is
 * `Intent.hasExtra(EXTRA_STREAM)` (distinguishing an absent extra from an explicit null), [rawValue]
 * is the extra's raw value. Pure; `is Uri`/`is Iterable`/`is Array` only, so the JVM test drives it
 * with plain values (a `List`, a `String`, null).
 */
internal fun classifyShareStreamExtra(
    present: Boolean,
    rawValue: Any?,
): ShareStreamExtraShape =
    when {
        rawValue is Uri -> ShareStreamExtraShape.SINGLE_URI
        rawValue is Iterable<*> || rawValue is Array<*> -> ShareStreamExtraShape.LIST
        !present && rawValue == null -> ShareStreamExtraShape.ABSENT
        else -> ShareStreamExtraShape.MALFORMED
    }

/**
 * P7-09 09.A (spec section 2.3; P2-3 fix): parses the sender's declared-size hint value (a Long,
 * any Number, or a numeric String), or null when absent/malformed. Pure and JVM-testable.
 */
internal fun parseDeclaredShareSize(rawValue: Any?): Long? =
    when (rawValue) {
        is Long -> rawValue
        is Number -> rawValue.toLong()
        is String -> rawValue.toLongOrNull()
        else -> null
    }

/**
 * P7-09 09.A (spec section 2.3/2.4; P2-3 fix): the recognized key for a sender's declared-size hint.
 * Android defines NO standard size extra for `ACTION_SEND` (`Intent` exposes no `EXTRA_SIZE`), so
 * this receiver's only "known size" source that needs no provider query is a hint an intentional
 * sender places here. Untrusted, normally absent (→ null = unknown size, which is accepted).
 */
internal const val SHARE_DECLARED_SIZE_HINT_EXTRA: String = "android.intent.extra.STREAM_SIZE"

/**
 * P7-09 09.A (spec section 2.3; P2-5 fix): the read-grant probe seam. Evaluating readability of a
 * granted share URI is a NON-opening, non-blocking permission check (`ContentResolver
 * .checkUriPermission` semantics) — it MUST NOT `openInputStream`, which touches the provider on
 * the calling thread. The spec section 2.4 thread contract keeps the bounded READ off the UI
 * thread; this probe performs no read at all. The generic shape keeps the seam JVM-testable
 * without an Android `Uri` (the test drives it with a plain `String` key).
 */
internal fun <T> shareUriReadGranted(
    uri: T,
    checkPermission: (T) -> Int,
    permissionGranted: Int,
): Boolean = checkPermission(uri) == permissionGranted

/**
 * P7-09 09.A (spec section 2.3; P2-3 fix): the raw declared-size hint from the Intent extras, or
 * null when absent/unreadable. Reads only the Intent's own extras (no provider query, no stream
 * open); a malformed value degrades to null via [parseDeclaredShareSize].
 */
private fun declaredSizeHintExtra(intent: Intent): Any? =
    try {
        intent.extras?.get(SHARE_DECLARED_SIZE_HINT_EXTRA)
    } catch (failure: Exception) {
        null
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

    /** Whether [deliveryId] was already consumed (the repeated-delivery probe). */
    fun contains(deliveryId: String): Boolean = deliveryId in consumed
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
    fun requestFor(snapshot: ShareIntentSnapshot): ExternalNavigationRequest? = requestFor(snapshot, mintDeliveryId())

    /**
     * P7-09 09.0 fix (P1-B; spec sections 2.2/4): like [requestFor], but the caller supplies the
     * [deliveryId]. The process-scoped receipt ledger uses this so a COLD-LAUNCH receipt carries ONE
     * stable id across an Activity recreation (and therefore can never be re-consumed), while a HOT
     * `onNewIntent` receipt still mints a fresh id.
     */
    fun requestFor(
        snapshot: ShareIntentSnapshot,
        deliveryId: String,
    ): ExternalNavigationRequest? {
        if (snapshot.action == Intent.ACTION_MAIN) return null
        return when (val result = validateShareIntake(snapshot, deliveryId)) {
            is ExternalNavigationIntakeResult.Accepted -> if (delivered.consumeOnce(deliveryId)) result.request else null
            is ExternalNavigationIntakeResult.Rejected -> null
            ExternalNavigationIntakeResult.NotAnInboundEntry -> null
        }
    }

    /** Whether [deliveryId] has already been consumed by this intake (the repeated-delivery probe). */
    fun alreadyConsumed(deliveryId: String): Boolean = delivered.contains(deliveryId)
}

/**
 * P7-09 09.0/09.A fix (P1-B; spec sections 2.2/4): the PROCESS-SCOPED inbound receipt ledger.
 *
 * The defect this class closes: `MainActivity`'s intake (and its [DeliveryIdLedger]) used to be a
 * per-Activity field and `onCreate` re-ran `handleInboundIntent(intent)` on the SAME launch Intent
 * after every configuration recreation. A fresh ledger plus a fresh mint meant the same share was
 * consumed a SECOND time (a new deliveryId) and the visible Busy slot was lost.
 *
 * This ledger lives on the process-scoped [LedgerProcessHolder] (the spec section 6.3 process owner
 * holder), so:
 * - a COLD-LAUNCH receipt mints its deliveryId EXACTLY ONCE for the process launch and reuses it on
 *   every recreation; the repeated internal delivery of that same id is refused by the idempotent
 *   [InboundNavigationIntake], so the same launch Intent is never admitted a second time (the
 *   frozen 不重投/不重复接治 semantics of option (a));
 * - a HOT receipt (`onNewIntent`) is a genuinely new user share and mints a FRESH deliveryId (spec
 *   section 4: a new share is a new import, subject to the human suspected-duplicate review).
 *
 * Honest residual (disclosed, not silently reduced): this closes the double intake and the id
 * identity. The reducer-side pending slot is NOT persisted across an Activity recreation or process
 * death — the full "slot survives config change" fix is device-gated (spec section 8 item 7). The
 * Activity therefore makes a refused repeat read of the same launch Intent NON-SILENT by re-holding
 * the request under its existing id while its retained source is present ([ColdLaunchReceipt]); a
 * share with no retained source stays the typed re-share case, never a silent re-consume.
 */
internal class ProcessInboundNavigationIntake(
    private val mintDeliveryId: () -> String,
) {
    private val intake = InboundNavigationIntake(mintDeliveryId)

    /** The stable deliveryId of the current process launch's cold-start receipt, or null before one. */
    private var coldLaunchDeliveryId: String? = null

    /**
     * The request for the process launch's cold-start receipt. The FIRST call mints the id; every
     * later call (an Activity recreation re-reading the same launch Intent) reuses it and, because
     * the id was already consumed, yields null — no second delivery, no new deliveryId.
     */
    fun coldLaunchRequest(snapshot: ShareIntentSnapshot): ExternalNavigationRequest? {
        if (snapshot.action == Intent.ACTION_MAIN) return null
        val deliveryId = coldLaunchDeliveryId ?: mintDeliveryId().also { coldLaunchDeliveryId = it }
        return intake.requestFor(snapshot, deliveryId)
    }

    /** The request for a HOT `onNewIntent` receipt: always a new share, so always a fresh id. */
    fun hotReceiptRequest(snapshot: ShareIntentSnapshot): ExternalNavigationRequest? {
        if (snapshot.action == Intent.ACTION_MAIN) return null
        return intake.requestFor(snapshot, mintDeliveryId())
    }

    /**
     * P7-09 09.A fix round (B3; spec section 2.2 line 105 / section 4): the state of the PROCESS
     * launch's cold-start receipt for the caller to make its loss NON-SILENT. The stable cold-launch
     * [deliveryId] (null before any cold share) lets the Activity recognize a repeat read of the same
     * launch Intent — a repeated delivery the idempotent intake REFUSES (no re-consume). The Activity
     * then re-holds that request by its EXISTING id when its retained source is still present, so a
     * config recreation never leaves the share in an un-signalled void (the full slot-survives-config
     * -change fix is device-gated, spec section 8 item 7).
     */
    fun coldLaunchReceipt(): ColdLaunchReceipt? {
        val deliveryId = coldLaunchDeliveryId ?: return null
        return ColdLaunchReceipt(deliveryId)
    }

    /** Whether a repeated read of [deliveryId] is a refused (already-consumed) delivery. */
    fun isRefusedRepeat(deliveryId: String): Boolean = intake.alreadyConsumed(deliveryId)
}

/** P7-09 09.A fix round (B3): the current cold-launch receipt identity (see [ProcessInboundNavigationIntake.coldLaunchReceipt]). */
internal class ColdLaunchReceipt(
    val deliveryId: String,
)
