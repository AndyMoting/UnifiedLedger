package com.unifiedledger.ui

/**
 * P7-09 09.A share-import closed loop (spec section 2.3 line 126/127; section 4; D-225).
 *
 * The defect this batch closes: a validated share is held as
 * [ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED], but [P503App.startImportFilePick] launched a
 * fresh SAF picker and never read the shared stream, so a shared file could never actually be
 * imported. This port is the missing consumer: given the opaque [deliveryId] of the held request it
 * returns the RETAINED source as a [PickedImportFile], whose lazy `readBoundedBytes` closure is the
 * EXISTING [readImportPickBounded] L0 reader — there is no second reader, no second parser and no
 * second intake (spec section 2.3; invariant 3 of the batch).
 *
 * The platform `Uri` NEVER crosses this boundary: the port yields only the neutral [PickedImportFile]
 * (display name + size metadata + the bounded-read closure — the [ImportFilePickPort] discipline,
 * spec section 2.1). The Android composition root implements it from a process-scoped
 * `deliveryId -> Uri` registry; the desktop root leaves it null.
 *
 * A `null` return is the typed "source unavailable" case (spec section 4; invariants 2/5): the
 * temporary content grant expired/was revoked before the read, or the process died and the retained
 * source is gone. It is NEVER a silent drop — the host presents the re-share prompt and clears the
 * pending slot.
 */
interface ShareImportSourcePort {
    /**
     * Returns the retained source for [deliveryId] (no read yet — the read stays lazy on the
     * returned file's closure and runs in-session off the UI thread inside the existing intake
     * pipeline), or `null` when nothing is retained for that delivery (grant gone / process death).
     */
    fun take(deliveryId: String): PickedImportFile?

    /**
     * Releases a retained-but-unconsumed source (the user abandoned the format choice). Default
     * no-op so a desktop/test port keeps compiling; the Android port removes the registry entry.
     */
    fun release(deliveryId: String) {}
}

/**
 * The JVM-assertable decision of the share closed loop's SOURCE step (spec sections 2.3/4): a
 * non-null source starts the EXISTING single-flight intake pipeline, a null source is the typed
 * re-share case. Extracted from [P503App] (a `@Composable` with no JVM harness) so the branch is
 * pinned without a Compose run.
 */
sealed interface ShareImportTakeDecision {
    /** The retained source was obtained; the host feeds it to [P503App.runImportIntakePipeline]. */
    data class StartIntake(
        val file: PickedImportFile,
    ) : ShareImportTakeDecision

    /** Nothing is retained for the delivery: present the typed re-share prompt, zero formal effect. */
    data object ReShareRequired : ShareImportTakeDecision
}

/** The pure mapping of a port take onto the closed loop's decision. */
internal fun shareImportTakeDecision(file: PickedImportFile?): ShareImportTakeDecision =
    if (file == null) {
        ShareImportTakeDecision.ReShareRequired
    } else {
        ShareImportTakeDecision.StartIntake(file)
    }

/**
 * P7-09 09.A fix round (B5; spec section 2.2 lines 107-108): the single-flight ADMISSION for the
 * share closed loop, evaluated BEFORE the retained source is consumed. When the import intake single
 * flight is already occupied ([intakeBusy]) the selection must NOT `take` the retained handle — the
 * source and the format slot stay valid for a retry, and the refused attempt surfaces the visible
 * Busy. Consuming first (the pre-fix order) removed the handle and left only a Busy, making the
 * share unrecoverable without re-sharing.
 */
internal fun shareImportRelayAllowed(intakeBusy: Boolean): Boolean = !intakeBusy

/**
 * P7-09 09.A (spec section 2.3 line 128; section 4; invariant 5): whether an intake-pipeline outcome
 * on the SHARE path must additionally present the typed re-share prompt. The four frozen vectors are
 * grant expiry/revocation before the read, a mid-read failure, an over-16 MiB source and a missing
 * source; the last is the `null` take handled by [shareImportTakeDecision], so this predicate covers
 * the pipeline's own read outcomes ([ImportIntakePipelineOutcome.ReadFailed] /
 * [ImportIntakePipelineOutcome.ReadExceedsLimit]). A successful/typed-intake outcome re-shared
 * nothing — a format rejection is not a re-share case.
 */
internal fun shareImportReShareRequired(outcome: ImportIntakePipelineOutcome): Boolean =
    outcome is ImportIntakePipelineOutcome.ReadFailed || outcome is ImportIntakePipelineOutcome.ReadExceedsLimit

/**
 * P7-09 09.A (spec sections 2.2/2.3): whether the single pending slot currently holds a share import
 * that is waiting for the user to pick a format from the capability matrix. The host renders the
 * matrix choices and runs the closed loop only while this is true (a non-share held request, or one
 * past its format step, is untouched).
 */
internal fun PendingExternalNavigation?.awaitsShareImportFormat(): Boolean =
    this != null &&
        request is ExternalNavigationRequest.OpenImport &&
        holdReason == ExternalNavigationHoldReason.IMPORT_FORMAT_REQUIRED

/**
 * P7-09 09.A (spec section 2.2): every OpenImport `deliveryId` a pending slot currently HOLDS —
 * its [PendingExternalNavigation.request] and, when a second request is awaiting the user's
 * retain/replace decision, its [PendingExternalNavigation.displaced]. A non-OpenImport request
 * contributes no id (nothing was retained for it). Pure.
 */
internal fun PendingExternalNavigation?.heldShareDeliveryIds(): Set<String> {
    if (this == null) return emptySet()
    val ids = mutableSetOf<String>()
    (request as? ExternalNavigationRequest.OpenImport)?.let { ids += it.deliveryId }
    (displaced as? ExternalNavigationRequest.OpenImport)?.let { ids += it.deliveryId }
    return ids
}

/**
 * P7-09 09.A fix round (B1 + NF1; spec section 2.3 line 127 及时释放 / section 9 line 271; D-146):
 * THE set of retained share sources the HOST must RELEASE when the single pending slot transitions.
 * The reducer is pure, so the release is a host action keyed on the slot transition ([previous] is
 * the effective slot before the landing, [next] after): the result is the SET DIFFERENCE of the two
 * sides' held share ids ([heldShareDeliveryIds]).
 *
 * NF1: the seam covers the DISPLACED share too, not just the slot's `request`. When `OpenImport(d1)`
 * is held awaiting its format and `OpenImport(d2)` arrives, the reducer lands `RetainOrReplace`
 * (`{request=d1, awaitingUserDecision, displaced=d2}`) and d2 was already retained by the Activity on
 * receipt; a later 保留原请求 clears `displaced`, so d2 leaves the slot and MUST be released. The
 * set-difference shape releases it (the former request-id-only comparison did not).
 *
 * Invariants preserved exactly:
 * - the SAME awaiting-format share still held → the difference is empty → nothing is released;
 * - 放弃后切换 on an idle overview RE-HELDS the share under the same id → nothing is released;
 * - a replace (保留/replace swaps `request`) releases whatever left (d1);
 * - a consumed/abandoned/re-shared slot releases every id it held.
 */
internal fun releasedShareDeliveryIds(
    previous: PendingExternalNavigation?,
    next: PendingExternalNavigation?,
): Set<String> = previous.heldShareDeliveryIds() - next.heldShareDeliveryIds()

/**
 * P7-09 09.A fix round (B2; spec section 2.2 line 106 禁止静默丢弃, section 3 line 154 暂缓): the
 * HOST-level fallback hold for surfaces the reducer CANNOT carry the single pending slot on (a
 * read-only detail, the recycle bin, a restore-confirm quiesce, ...). The reducer's
 * `setPendingExternalNavigation` deliberately returns such a surface UNCHANGED — which, without this
 * seam, made an arriving share VANISH with no visible Busy. This is the minimal host-level mechanism
 * that never widens [P503AppState] (so the ~25 unrelated construction sites stay out of scope):
 *
 * - when the landed state CARRIES the slot, the reducer already owns it — the host holds nothing;
 * - otherwise a [ExternalNavigationDecision.Held]/[ExternalNavigationDecision.DeferredUntilReady]
 *   request is surfaced by the host so the request stays VISIBLE (never silently discarded).
 *
 * Pure and JVM-assertable; [P503App] stores and renders the returned value.
 */
internal fun hostFallbackHeldNavigation(
    landed: P503AppState,
    decision: ExternalNavigationDecision,
): PendingExternalNavigation? {
    if (landed.pendingExternalNavigationSlot() != null) return null
    return when (decision) {
        is ExternalNavigationDecision.Held -> decision.pending
        is ExternalNavigationDecision.DeferredUntilReady ->
            PendingExternalNavigation(decision.request, ExternalNavigationHoldReason.STARTUP_NOT_READY)
        // Mirror the reducer's RetainOrReplace landing (spec section 2.2): keep the ORIGINAL request
        // and carry the incoming one as `displaced` for the explicit retain/replace decision.
        is ExternalNavigationDecision.RetainOrReplace ->
            PendingExternalNavigation(
                request = decision.existing.request,
                holdReason = decision.existing.holdReason,
                awaitingUserDecision = true,
                displaced = decision.incoming,
            )
        is ExternalNavigationDecision.Routed -> null
    }
}

/**
 * P7-09 09.A fix round (B1/B2): whether [event] is a navigation event that CLEARS the held request
 * (the user consumed/abandoned it). Used by the host to drop its fallback hold and release the
 * retained share source on the same transitions the reducer uses to clear a slot.
 */
internal fun clearsExternalNavigation(event: P503UiEvent): Boolean =
    event === P503UiEvent.ExternalNavigationKeptCurrent ||
        event === P503UiEvent.ExternalNavigationDiscarded ||
        event === P503UiEvent.ShareImportConsumed ||
        event === P503UiEvent.ShareImportReShareLanded
