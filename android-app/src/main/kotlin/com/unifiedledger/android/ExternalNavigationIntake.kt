package com.unifiedledger.android

import android.content.Intent
import android.net.Uri
import com.unifiedledger.application.EntryType
import com.unifiedledger.ui.ExternalNavigationRequest
import com.unifiedledger.ui.IMPORT_FILE_PICK_MAX_READ_BYTES
import com.unifiedledger.ui.ShareRejectionReason

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

    /**
     * The Intent carried no usable share surface at all. **Reserved: no producer today** — the frozen
     * validation table rejects a stream-less Intent as [MISSING_STREAM] before this code is reachable,
     * so it is kept (and mapped) only so the display mapping stays total; removing it would change an
     * implemented enum for no behaviour. Registered as a residual in the ACC-D-SHARE-02 batch.
     */
    NOT_A_SHARE;

    /**
     * P7-09 09.A (spec section 2.3; ACC-D-SHARE-02): the display-safe common identity of this refusal,
     * so the shared refusal strip can say WHY without the platform type crossing into common code. The
     * mapping is total by construction (one branch per member) and carries no payload.
     */
    fun toShareRejectionReason(): ShareRejectionReason =
        when (this) {
            UNSUPPORTED_ACTION -> ShareRejectionReason.UNSUPPORTED_ACTION
            MISSING_STREAM -> ShareRejectionReason.MISSING_STREAM
            MULTIPLE_STREAMS -> ShareRejectionReason.MULTIPLE_STREAMS
            CONFLICTING_STREAM -> ShareRejectionReason.CONFLICTING_STREAM
            NON_CONTENT_URI -> ShareRejectionReason.NON_CONTENT_URI
            STREAM_NOT_READABLE -> ShareRejectionReason.STREAM_NOT_READABLE
            MALFORMED_EXTRAS -> ShareRejectionReason.MALFORMED_EXTRAS
            DECLARED_SIZE_EXCEEDS_LIMIT -> ShareRejectionReason.DECLARED_SIZE_EXCEEDS_LIMIT
            NOT_A_SHARE -> ShareRejectionReason.NOT_A_SHARE
        }
}

/**
 * P7-09 09.A (spec section 2.3; ACC-D-SHARE-02): the placeholder identity the pure refusal probe hands
 * to the validator. It is never delivered, never consumed and never persisted; an accepted snapshot
 * simply discards the request built with it.
 */
private const val REJECTION_PROBE_DELIVERY_ID: String = "rejection-probe"

/**
 * P7-09 09.B (D-226; spec sections 2.1/5): the launcher shortcut action family. These are this
 * app's OWN explicit actions (the `LedgerWidgetProvider` action precedent), never
 * `Intent.ACTION_MAIN`: a shortcut Intent is recognized by an explicit, checkable action string and
 * mapped to one neutral request, while `ACTION_MAIN` remains a non-entry (an ordinary launch).
 *
 * The actions carry NO account name, category name or amount — only which existing surface to open
 * (spec section 5: the launcher can read shortcut metadata, so nothing sensitive may live here).
 * `ShortcutManager` also requires an explicit target Activity; the extras express ROUTING only and
 * are never a formal submission snapshot (spec section 5).
 */
object ShortcutNavigationAction {
    /** 记支出 shortcut → `OpenNewEntry(EXPENSE)`. */
    const val NEW_EXPENSE: String = "com.unifiedledger.android.action.NEW_EXPENSE"

    /** 记收入 shortcut → `OpenNewEntry(INCOME)`. */
    const val NEW_INCOME: String = "com.unifiedledger.android.action.NEW_INCOME"

    /** 导入 shortcut → `OpenImportTab` (the existing IMPORT surface; no file, no deliveryId). */
    const val OPEN_IMPORT: String = "com.unifiedledger.android.action.OPEN_IMPORT"

    /** Every recognized shortcut action, in declaration order. */
    val ALL: List<String> = listOf(NEW_EXPENSE, NEW_INCOME, OPEN_IMPORT)

    /**
     * The neutral request one shortcut action routes to, or null when [action] is not a recognized
     * shortcut action — an unknown or foreign action is never coerced into a route (typed rejection
     * discipline). Pure, so the mapping is JVM-assertable without an `Intent` (the widget-route
     * precedent).
     */
    fun requestFor(action: String?): ExternalNavigationRequest? =
        when (action) {
            NEW_EXPENSE -> ExternalNavigationRequest.OpenNewEntry(EntryType.EXPENSE)
            NEW_INCOME -> ExternalNavigationRequest.OpenNewEntry(EntryType.INCOME)
            OPEN_IMPORT -> ExternalNavigationRequest.OpenImportTab
            else -> null
        }

    /**
     * The `DeliveryIdLedger` key for one shortcut action. A shortcut carries no delivery identity
     * (`ACTION_SEND` has none either, spec section 4), so the action string itself keys the
     * idempotent re-consumption of a COLD-LAUNCH receipt; the `shortcut:` prefix keeps the key
     * space disjoint from the share's opaque UUIDs so the two can never collide.
     */
    fun ledgerKey(action: String): String = "shortcut:$action"
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

    /**
     * P7-09 09.A (spec section 2.3; ACC-D-SHARE-02): the TYPED refusal of one received Intent, or null
     * when the Intent validates as an accepted share (or is not an inbound entry at all).
     *
     * Pure and side-effect free: it runs the SAME validator as [requestFor] with a probe deliveryId, but
     * mints nothing and consumes nothing, so the caller can surface the refusal AFTER the request path
     * declined to deliver anything. The probe id never leaves this method (an accepted snapshot's
     * request is discarded here).
     */
    fun rejectionFor(snapshot: ShareIntentSnapshot): ShareIntakeRejection? {
        if (snapshot.action == Intent.ACTION_MAIN) return null
        return when (val result = validateShareIntake(snapshot, REJECTION_PROBE_DELIVERY_ID)) {
            is ExternalNavigationIntakeResult.Rejected -> result.code
            else -> null
        }
    }

    /**
     * P7-09 09.B (D-226; spec sections 2.1/5): the neutral request for one received SHORTCUT action,
     * or null when [action] is not a recognized shortcut action or its [deliveryId] was already
     * consumed.
     *
     * A shortcut carries no file, no URI and no share grant, so unlike [requestFor] there is nothing
     * to validate beyond the action itself: recognition IS the validation. An unrecognized action
     * returns null and consumes nothing (an unknown action is never coerced into a route). The
     * [deliveryId] is the same idempotent-consumption key the share path uses.
     */
    fun shortcutRequestFor(
        action: String?,
        deliveryId: String,
    ): ExternalNavigationRequest? {
        val request = ShortcutNavigationAction.requestFor(action) ?: return null
        return if (delivered.consumeOnce(deliveryId)) request else null
    }
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
 * - a COLD-LAUNCH receipt mints its deliveryId once per FRESH launch of the Activity (a new user
 *   share, spec section 4) and reuses it only when the SAME launch Intent is re-read by a
 *   configuration recreation, which keeps the frozen 不重投/不重复接治 semantics;
 * - a HOT receipt (`onNewIntent`) is a genuinely new user share and mints a FRESH deliveryId (spec
 *   section 4: a new share is a new import, subject to the human suspected-duplicate review).
 *
 * Honest residual (disclosed, not silently reduced): this closes the double intake and the id
 * identity. The reducer-side pending slot is NOT persisted across an Activity recreation or process
 * death — the full "slot survives config change" fix is device-gated (spec section 8 item 7). The
 * Activity makes a refused repeat read of the same launch Intent NON-SILENT ONLY while the retained
 * source is still present: it then re-holds the request under its existing id ([ColdLaunchReceipt]).
 * With NO retained source (already consumed/read, or a new process) the recreation cannot re-surface
 * any prompt — the source is GONE and the attempt returns silently; the user must SHARE AGAIN (spec
 * section 4 未接治源需重新分享). This is the honest limit: the no-source branch shows no typed prompt
 * because there is nothing to prompt about, and no `rememberSaveable` slot persistence is attempted.
 */
internal class ProcessInboundNavigationIntake(
    private val mintDeliveryId: () -> String,
) {
    private val intake = InboundNavigationIntake(mintDeliveryId)

    /** The stable deliveryId of the current process launch's cold-start receipt, or null before one. */
    private var coldLaunchDeliveryId: String? = null

    /**
     * Every FRESH launch (a null `savedInstanceState`) mints its own id, because a sender app
     * that starts this Activity again is a new user share even when the URI is identical. A
     * configuration recreation re-reads the SAME launch Intent and reuses the id, so the repeat
     * delivery stays refused.
     */
    fun coldLaunchRequest(
        snapshot: ShareIntentSnapshot,
        recreated: Boolean,
    ): ExternalNavigationRequest? {
        if (snapshot.action == Intent.ACTION_MAIN) return null
        if (!recreated) {
            // A FRESH Activity launch is a genuinely new user share even inside a live process. Whether a
            // given sender reuses the top instance (singleTop) or starts a new one is the task-stack vector
            // P709-A04 pins; both paths end up here, and this one mints.
            val fresh = mintDeliveryId()
            val request = intake.requestFor(snapshot, fresh)
            // Only an ACCEPTED share becomes the launch receipt: a typed rejection must not occupy
            // the identity, or a later recreation would re-hold a request that was never delivered.
            if (request != null) coldLaunchDeliveryId = fresh
            return request
        }
        // A configuration recreation re-reads the SAME launch Intent: reuse the launch's id so the
        // repeat delivery is refused and nothing is consumed twice.
        val existing = coldLaunchDeliveryId
        if (existing != null) return intake.requestFor(snapshot, existing)
        // A recreation whose process launch id is gone (process death + task restore) mints one,
        // but only an ACCEPTED request may become the receipt.
        val fresh = mintDeliveryId()
        val request = intake.requestFor(snapshot, fresh)
        if (request != null) coldLaunchDeliveryId = fresh
        return request
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

    /**
     * P7-09 09.A (spec section 2.3; ACC-D-SHARE-02): the typed refusal of one snapshot, or null when it
     * would be accepted. Delegates to the process-scoped intake's pure probe: no id is minted and no
     * receipt is touched, so a refusal can be shown without occupying the delivery identity.
     */
    fun rejectionFor(snapshot: ShareIntentSnapshot): ShareIntakeRejection? = intake.rejectionFor(snapshot)

    /**
     * P7-09 09.B (D-226; spec sections 2.2/5): the request for the process launch's cold-start
     * SHORTCUT receipt. A shortcut has no delivery identity of its own (`ACTION_SEND` has none
     * either, spec section 4), so the ACTION string keys the idempotent re-consumption: a recreation
     * that re-reads the SAME launch Intent reuses the same key and the repeat is refused, while a
     * genuinely new tap in a new process starts from a fresh [DeliveryIdLedger]. Null for a
     * non-shortcut action and for a refused repeat.
     */
    fun coldLaunchShortcutRequest(action: String?): ExternalNavigationRequest? {
        if (action == null || ShortcutNavigationAction.requestFor(action) == null) return null
        return intake.shortcutRequestFor(action, ShortcutNavigationAction.ledgerKey(action))
    }

    /**
     * P7-09 09.B (D-226; spec section 5): the request for a HOT shortcut receipt (a second tap while
     * the app is alive). It is a genuinely new user action, so it mints a fresh id and always routes
     * — the app is left where it was only for the surface the user chose next.
     */
    fun hotShortcutRequest(action: String?): ExternalNavigationRequest? {
        if (ShortcutNavigationAction.requestFor(action) == null) return null
        return intake.shortcutRequestFor(action, mintDeliveryId())
    }
}

/** P7-09 09.A fix round (B3): the current cold-launch receipt identity (see [ProcessInboundNavigationIntake.coldLaunchReceipt]). */
internal class ColdLaunchReceipt(
    val deliveryId: String,
)
