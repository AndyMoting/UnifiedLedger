package com.unifiedledger.ui

import com.unifiedledger.application.EntryType

/**
 * P7-09 09.0 (spec sections 2.1/3; D-225): the NEUTRAL inbound navigation request family.
 *
 * The Android composition root validates a platform entry (a share Intent, a launcher
 * shortcut) into exactly one of these three requests; commonMain never sees a `Uri`, a file
 * name or any platform object (the [ImportFilePickPort] boundary discipline, spec section
 * 2.1). The requests only express ROUTING — they never carry a formal-ledger write, a
 * snapshot or an authorization time.
 *
 * The exact sealed shape and the rejection-code family are spec section 8 open item 1; the
 * shape below is this batch's implementation of the frozen semantics (registered in the
 * batch report).
 */
sealed interface ExternalNavigationRequest {
    /** 记支出/记收入 直达既有录入流 (spec section 2.1 `OpenNewEntry(type)`). */
    data class OpenNewEntry(
        val type: EntryType,
    ) : ExternalNavigationRequest

    /**
     * 受控文件分享的导入 (spec section 2.1 `OpenImport(deliveryId)`). [deliveryId] is the
     * locally minted opaque inbound identity (spec section 2.2/4) — it is never a URI, a file
     * name, a content hash or any personal identifier, and it is the ONLY key the idempotent
     * re-consumption guarantee uses.
     */
    data class OpenImport(
        val deliveryId: String,
    ) : ExternalNavigationRequest

    /** 回到应用当前月份/看账 (spec section 2.1 `OpenCurrentMonth`). */
    data object OpenCurrentMonth : ExternalNavigationRequest
}

/**
 * P7-09 09.0 (spec section 3): why an inbound request is being held instead of routed. The
 * value names the ROUTING TABLE row that produced the hold, so the visible Busy surface can
 * say what the app is waiting for without leaking any inbound payload.
 */
enum class ExternalNavigationHoldReason {
    /** 启动 / 失败重试: waiting for `Ready`; a startup failure does not consume the request. */
    STARTUP_NOT_READY,

    /** Editing: the user has an unsaved draft; retain or discard-and-switch is the user's call. */
    DRAFT_IN_PROGRESS,

    /** AwaitingConfirmation: the confirmation snapshot is pending the user's explicit decision. */
    CONFIRMATION_PENDING,

    /** Submitting / UnknownCommit: a commit is in flight or unresolved (不自动续批). */
    COMMIT_IN_FLIGHT,

    /** ImportBatchSubmitting / 未决批次: a batch run is in flight (不自动续批). */
    IMPORT_BATCH_IN_FLIGHT,

    /** A second platform file pick arrived while one intake pipeline is already running. */
    IMPORT_INTAKE_IN_FLIGHT,

    /** P7-06 quiesce / 换库: navigation is postponed until the new generation is verified. */
    RUNTIME_QUIESCED,

    /**
     * A share import is held until the user picks a format from the Android capability matrix
     * (spec section 2.3: the inbound file must NOT be delivered into the old channel that has no
     * `pendingImportFormat`).
     */
    IMPORT_FORMAT_REQUIRED,

    /**
     * P7-09 09.A closed loop (spec section 4; invariant 5): the retained share source could not be
     * read — the temporary content grant expired/was revoked before the read, a read failed, the
     * file exceeded the 16 MiB bound, or the process died and the source is gone. This is the
     * typed re-share prompt's reason: the pending slot is cleared, zero formal effect, and the user
     * is told to share the file again (never a silent drop).
     */
    IMPORT_SOURCE_UNAVAILABLE,

    /** The request's target is not routable from the current state in this slice. */
    UNROUTABLE_TARGET,
}

/**
 * P7-09 09.0 / T4 (spec sections 1.7/2.2): the VISIBLE Busy/待处理 result of a host-side
 * blocked operation that is not itself a held navigation request — today the second platform
 * file pick that arrived while one intake pipeline was already running (the former
 * `-> Unit` silent drop). [reason] names why the operation could not start; the strip carries
 * no URI, file name or amount.
 */
data class ExternalNavigationBusy(
    val reason: ExternalNavigationHoldReason,
)

/**
 * P7-09 09.0 (spec section 2.2): THE single pending slot. At most one inbound request may be
 * held; there is no unbounded queue and no silent drop (D-225 Q19). A second request arriving
 * while one is held does NOT overwrite it — [awaitingUserDecision] turns true and [displaced]
 * carries the incoming request until the user explicitly retains or replaces (the replace is
 * an explicit confirmation; spec sections 2.2/3).
 *
 * [request] is the held request, [holdReason] names the routing-table row that held it, and
 * the whole value is display-safe: no URI, no file name, no amount.
 */
data class PendingExternalNavigation(
    val request: ExternalNavigationRequest,
    val holdReason: ExternalNavigationHoldReason,
    /** True once a second request arrived and the user must explicitly retain or replace. */
    val awaitingUserDecision: Boolean = false,
    /** The incoming request displaced by [request] while [awaitingUserDecision] is true. */
    val displaced: ExternalNavigationRequest? = null,
)

/**
 * P7-09 09.0 (spec section 3): the pure routing decision for one inbound request against the
 * current application state. Extracted from the host so the frozen state table is
 * JVM-assertable without a Compose harness (the `P503HostCoordinator` precedent).
 */
sealed interface ExternalNavigationDecision {
    /** Route now, without a formal-ledger write (Idle → existing editor / import / 看账). */
    data class Routed(
        val request: ExternalNavigationRequest,
    ) : ExternalNavigationDecision

    /**
     * Hold the request in the single pending slot with a visible Busy result. [pending] is the
     * slot value the host lands; the state is otherwise untouched (no requestId, no
     * confirmation snapshot and no authorization time is changed).
     */
    data class Held(
        val pending: PendingExternalNavigation,
    ) : ExternalNavigationDecision

    /**
     * A request is already held: the original is RETAINED (never overwritten) and the incoming
     * one is carried as [PendingExternalNavigation.displaced] until the user explicitly
     * retains or replaces (spec section 2.2).
     */
    data class RetainOrReplace(
        val existing: PendingExternalNavigation,
        val incoming: ExternalNavigationRequest,
    ) : ExternalNavigationDecision

    /** Startup has not concluded: the host keeps the request until the runtime is Ready. */
    data class DeferredUntilReady(
        val request: ExternalNavigationRequest,
    ) : ExternalNavigationDecision
}

/**
 * P7-09 09.0 (spec section 3): the routing table, applied to the LIVE state plus the two
 * runtime facts the reducer cannot see ([runtimeReady] and [runtimeQuiesced], both host
 * observations of the P7-06 lease surface).
 *
 * Semantics (frozen by D-225 / spec section 3):
 * - not `Ready`: defer — a startup failure never consumes the request and a successful retry
 *   re-enters this gate;
 * - quiesce / 换库: postpone; after recovery the caller re-validates against the new
 *   generation;
 * - `OverviewEmpty` (Idle): route without a formal-ledger write;
 * - `Ready` (the startup-concluded ENTRY surface, before the initial load lands): HOLD with the
 *   visible Busy/待处理 result. This is the P1-A fix: the entry surface is an Idle-class entry, but
 *   it has no overview to route INTO yet, so a request must land a VISIBLE slot here rather than
 *   fall through to the `else ->` absorb (which silently dropped a cold-start share);
 * - `Editing` / `AwaitingConfirmation`: hold for the explicit retain-or-discard decision;
 * - `Submitting` / `UnknownCommit` / `ImportBatchSubmitting`: hold; the original requestId,
 *   confirmation snapshot and authorization time are untouched and no batch is auto-continued;
 * - every other surface: hold as [ExternalNavigationHoldReason.UNROUTABLE_TARGET].
 *
 * [existingPending] is the slot's current value: a non-null slot always wins over routing, so
 * a held request is never silently replaced.
 */
internal fun decideExternalNavigation(
    state: P503AppState,
    request: ExternalNavigationRequest,
    runtimeReady: Boolean,
    runtimeQuiesced: Boolean,
    existingPending: PendingExternalNavigation?,
): ExternalNavigationDecision {
    if (!runtimeReady) return ExternalNavigationDecision.DeferredUntilReady(request)
    if (existingPending != null) return ExternalNavigationDecision.RetainOrReplace(existingPending, request)
    if (runtimeQuiesced) {
        return ExternalNavigationDecision.Held(
            PendingExternalNavigation(request, ExternalNavigationHoldReason.RUNTIME_QUIESCED),
        )
    }
    // A share import can never route into the existing channel: the frozen pick channel needs a
    // format slot the user must choose first (spec section 2.3). It is therefore always HELD with
    // the visible Busy/待处理 result — never delivered format-less and never silently dropped.
    if (request is ExternalNavigationRequest.OpenImport) {
        return held(request, ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED)
    }
    // A new-entry request for a type this slice does not implement is held, never routed into a
    // dead editor (spec section 3: no silent drop, no wrong route).
    if (request is ExternalNavigationRequest.OpenNewEntry && !isRoutableNewEntryType(request.type)) {
        return held(request, ExternalNavigationHoldReason.UNROUTABLE_TARGET)
    }
    return when (state) {
        is P503AppState.OverviewEmpty -> ExternalNavigationDecision.Routed(request)
        // P1-A: the startup-concluded ENTRY surface is Idle-class but has no overview to route
        // INTO yet, so the request lands a VISIBLE held slot here (never the `else ->` absorb). The
        // reason names "still opening" — the surface advances to OverviewEmpty once the initial
        // authoritative load lands, which carries the slot forward (reduceReady).
        is P503AppState.Ready -> held(request, ExternalNavigationHoldReason.STARTUP_NOT_READY)
        is P503AppState.Editing -> held(request, ExternalNavigationHoldReason.DRAFT_IN_PROGRESS)
        is P503AppState.AwaitingConfirmation -> held(request, ExternalNavigationHoldReason.CONFIRMATION_PENDING)
        is P503AppState.Submitting,
        is P503AppState.UnknownCommit,
        -> held(request, ExternalNavigationHoldReason.COMMIT_IN_FLIGHT)
        is P503AppState.ImportBatchSubmitting -> held(request, ExternalNavigationHoldReason.IMPORT_BATCH_IN_FLIGHT)
        else -> held(request, ExternalNavigationHoldReason.UNROUTABLE_TARGET)
    }
}

private fun held(
    request: ExternalNavigationRequest,
    reason: ExternalNavigationHoldReason,
): ExternalNavigationDecision.Held = ExternalNavigationDecision.Held(PendingExternalNavigation(request, reason))

/**
 * P7-09 09.0 (spec section 4): whether a request is routable into the existing editor without a
 * formal-ledger write. Only EXPENSE and INCOME are implemented entry flows in this slice; the
 * remaining entry types are held as [ExternalNavigationHoldReason.UNROUTABLE_TARGET] rather
 * than silently dropped or routed into a dead editor.
 */
internal fun isRoutableNewEntryType(type: EntryType): Boolean = type == EntryType.EXPENSE || type == EntryType.INCOME

/**
 * P7-09 09.0 (spec sections 2.1/2.2; D-225 Q19): the composition-root channel for inbound neutral
 * navigation requests (the [ImportFilePickResultChannel] precedent). The platform adapter validates
 * a received Intent into a request and delivers it here; the shared host subscribes via
 * [subscribe] and forwards it to the routing gate.
 *
 * The channel BUFFERS one undelivered request: a cold-start share is received in
 * `Activity.onCreate`, before the composition has subscribed, and must not be lost (spec section
 * 2.2: no silent drop). The buffer is depth ONE — a second request arriving before a subscriber
 * exists replaces the first (the transport staging area drains into the reducer's authoritative
 * single pending slot, which is where the retain-or-replace decision lives). No unbounded queue
 * exists here either.
 */
class ExternalNavigationChannel {
    private var forward: ((ExternalNavigationRequest) -> Unit)? = null
    private var buffered: ExternalNavigationRequest? = null

    /** Delivers one neutral request; buffered (depth one) until a subscriber arrives. */
    fun deliver(request: ExternalNavigationRequest) {
        val listener = forward
        if (listener == null) {
            buffered = request
        } else {
            listener(request)
        }
    }

    /**
     * The shared host's subscription point (last subscription wins, the pick-channel precedent).
     * A buffered cold-start request drains exactly once on subscribe; unsubscribing (`null`)
     * leaves the buffer empty.
     */
    fun subscribe(listener: ((ExternalNavigationRequest) -> Unit)?) {
        forward = listener
        if (listener != null) {
            val pending = buffered ?: return
            buffered = null
            listener(pending)
        }
    }
}

/**
 * P7-09 09.0 (spec section 2.2): the single pending slot carried by the routing-table surfaces, or
 * `null` on a surface that carries none (the host's routing decision reads this without knowing
 * which surface is live).
 *
 * P1-A fix: the startup-concluded [P503AppState.Ready] ENTRY surface now carries the slot too (a
 * cold-start share drains from the channel while the host is still on it), so the host can see and
 * preserve it instead of absorbing the decision.
 */
internal fun P503AppState.pendingExternalNavigationSlot(): PendingExternalNavigation? =
    when (this) {
        is P503AppState.Ready -> pendingExternalNavigation
        is P503AppState.OverviewEmpty -> pendingExternalNavigation
        is P503AppState.Editing -> pendingExternalNavigation
        is P503AppState.AwaitingConfirmation -> pendingExternalNavigation
        is P503AppState.Submitting -> pendingExternalNavigation
        is P503AppState.UnknownCommit -> pendingExternalNavigation
        is P503AppState.ImportBatchSubmitting -> pendingExternalNavigation
        else -> null
    }

/**
 * P7-09 09.A fix round (B2; spec section 2.2 line 106): whether this surface CLASS can carry the
 * single pending slot at all (independent of the current value). The host uses it to decide when a
 * request it held in its fallback (a non-slot surface, where the reducer cannot represent the slot)
 * can be handed back to the reducer once a routable surface becomes live.
 */
internal fun P503AppState.carriesExternalNavigationSlot(): Boolean =
    when (this) {
        is P503AppState.Ready,
        is P503AppState.OverviewEmpty,
        is P503AppState.Editing,
        is P503AppState.AwaitingConfirmation,
        is P503AppState.Submitting,
        is P503AppState.UnknownCommit,
        is P503AppState.ImportBatchSubmitting,
        -> true
        else -> false
    }

/**
 * P7-09 09.0 / T4 (spec sections 1.7/2.2; P2-2 fix): the visible Busy/待处理 result carried by any
 * slot surface, or `null` on a surface that carries none. Before the P2-2 fix only the [OverviewEmpty]
 * carried (and rendered) it, so a refused second pick on Editing / AwaitingConfirmation / Submitting
 * / ImportBatchSubmitting / Ready was equivalent to the silent drop this batch removes. The accessor
 * is the render seam [P503App] uses so every slot surface presents the Busy strip.
 */
internal fun P503AppState.externalNavigationBusyOrNull(): ExternalNavigationBusy? =
    when (this) {
        is P503AppState.Ready -> externalNavigationBusy
        is P503AppState.OverviewEmpty -> externalNavigationBusy
        is P503AppState.Editing -> externalNavigationBusy
        is P503AppState.AwaitingConfirmation -> externalNavigationBusy
        is P503AppState.Submitting -> externalNavigationBusy
        is P503AppState.UnknownCommit -> externalNavigationBusy
        is P503AppState.ImportBatchSubmitting -> externalNavigationBusy
        else -> null
    }

/**
 * P7-09 09.0 (spec section 2.2): the RETAIN action — the original request stays held and the
 * displaced incoming request is discarded; the slot's [PendingExternalNavigation.request] and
 * its requestId/snapshot/authorization semantics are untouched.
 */
internal fun retainPendingExternalNavigation(pending: PendingExternalNavigation): PendingExternalNavigation = pending.copy(awaitingUserDecision = false, displaced = null)

/**
 * P7-09 09.0 (spec section 2.2): the explicit REPLACE confirmation. The slot's held request
 * becomes the incoming one (carried in [PendingExternalNavigation.displaced] while awaiting the
 * decision); nothing else about the current surface is touched — a replace never rewrites the
 * in-flight commit's requestId, its confirmation snapshot or its authorization time.
 */
internal fun replacePendingExternalNavigation(pending: PendingExternalNavigation): PendingExternalNavigation {
    val incoming = pending.displaced ?: return pending.copy(awaitingUserDecision = false)
    return PendingExternalNavigation(request = incoming, holdReason = pending.holdReason)
}

/**
 * P7-09 09.A (spec sections 2.3/4; invariant 3): the closed loop's SOURCE step maps the taken
 * file (or its absence) to the ONE event the reducer consumes. A non-null take consumes the held
 * share ([P503UiEvent.ShareImportConsumed]); a null take is the typed re-share case
 * ([P503UiEvent.ShareImportReShareLanded]). The pipeline is started by the host in BOTH branches
 * only for a non-null take — this mapping never fabricates a read result.
 */
internal fun shareImportTakeEvent(decision: ShareImportTakeDecision): P503UiEvent =
    when (decision) {
        is ShareImportTakeDecision.StartIntake -> P503UiEvent.ShareImportConsumed
        ShareImportTakeDecision.ReShareRequired -> P503UiEvent.ShareImportReShareLanded
    }
