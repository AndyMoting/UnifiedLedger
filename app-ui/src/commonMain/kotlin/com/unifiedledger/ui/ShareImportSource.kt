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
