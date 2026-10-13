package com.unifiedledger.android

import android.content.Context
import android.net.Uri
import com.unifiedledger.ui.ExternalNavigationChannel
import com.unifiedledger.ui.LedgerLeaseScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P7-09 09.0 (spec section 6.3; D-225 Q19 P1): the PROCESS-SCOPED holder of the ONE runtime
 * owner for this Android process.
 *
 * Spec section 1.4 records the gap this type closes: the startup controller (and therefore its
 * [com.unifiedledger.ui.LedgerRuntimeOwner]) used to be constructed inside the Compose
 * `remember(context)` block and disposed with the composition (`App.kt:239 → :288/:433 →
 * :323-324`), so any NON-composition entry (an `AppWidgetProvider.onUpdate`, a shortcut cold
 * start, an `onNewIntent` share before the first composition) could not reach the runtime at
 * all — and the only ready path to a business graph from there was the forbidden
 * `buildLedgerGraph` (which bootstraps/migrates). Spec section 6.3 freezes the requirement: a
 * non-composition entry MUST go through a process-scoped owner holder, never a composition-scoped
 * one, never `buildLedgerGraph`, and there must be AT MOST ONE owner/active graph per process
 * (the P7-06 single-active-graph/single-generation/lease invariant).
 *
 * Mechanism selected by this batch (spec section 8 open item 13: the precise mechanism is OPEN and
 * its approval/evidence belongs to the main session — nothing here is that ruling):
 * - the holder is owned by the [UnifiedLedgerApplication] subclass, so it exists from process
 *   start and lives until process death;
 * - [controller] is created LAZILY and exactly once (the first caller wins; a racing second
 *   caller gets the same instance), so a widget/shortcut/Activity entry never opens a second
 *   graph;
 * - [ensureStarted] starts that ONE controller at most once for the whole process — a
 *   re-composition, an Activity recreation or a second entry never re-runs the open;
 * - [ledger] is the same lease-scoped accessor the composition uses — every business read/write
 *   still goes through [LedgerLeaseScope]'s operation lease, so the holder adds no second lease;
 * - the holder NEVER disposes the controller: the owner's lifecycle is the PROCESS's, not a
 *   composition's (the P7-06 owner already closes the previous graph before opening the next).
 *
 * Desktop deliberately keeps its own app-ui-owned single owner (spec section 6.3: the desktop
 * root composes one owner per window); this holder exists only where a non-composition entry can
 * reach the process.
 */
internal class LedgerProcessHolder(
    private val applicationContext: Context,
    /** The SAF launcher slot shared with the Activity composition (Activity-scoped launchers). */
    val safSlot: AndroidSafLaunchSlot,
    /** The process-scoped inbound-navigation channel the composition root delivers requests into. */
    val externalNavigationChannel: ExternalNavigationChannel,
    /**
     * P7-09 09.0 fix (P1-B; spec sections 2.2/4): the PROCESS-scoped inbound receipt ledger. It lives
     * here (not on the Activity) so a cold-launch receipt keeps ONE deliveryId across an Activity
     * recreation and the same launch Intent is never admitted twice. The mint is the product's
     * opaque UUID source.
     */
    val inboundIntake: ProcessInboundNavigationIntake =
        ProcessInboundNavigationIntake {
            java.util.UUID
                .randomUUID()
                .toString()
        },
    /**
     * P7-09 09.A closed loop (spec sections 2.3/4; D-225; invariant 2): the PROCESS-scoped registry of
     * the accepted share's platform `Uri`, keyed by the neutral `deliveryId`. It lives here (not on
     * the Activity) so a cold-start share's retained handle survives an Activity recreation; the
     * composition root reads it through [AndroidShareImportSourcePort] on the later format choice. The
     * handle is never persisted (D-146).
     */
    val shareImportRegistry: ShareImportSourceRegistry<Uri> = ShareImportSourceRegistry(),
) {
    /** The process's single startup controller; built exactly once. */
    val controller: AndroidStartupController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildAndroidStartupController(applicationContext, safSlot, shareImportRegistry)
    }

    private val startedOnce = AtomicBoolean(false)

    /**
     * Starts the process's single controller at most once. Idempotent across every entry (the
     * Activity composition, a re-composition, an Activity recreation, a future non-composition
     * entry), so the single active graph is opened exactly once under the owner's own
     * single-flight guard.
     *
     * P7-09 P2-6 fix: the idempotence is delegated to the pure [ensureStartedOnce] seam so it is
     * JVM-assertable without an Android `Context` (the composition root can only build a real holder
     * on a device).
     */
    fun ensureStarted() {
        ensureStartedOnce(startedOnce) { controller.start() }
    }

    /**
     * The lease-scoped business accessor for this process's active graph, or null while the
     * startup gate has not reached Ready. This is the process-scoped entry a NON-composition
     * caller (a future widget receiver / shortcut cold start) must use instead of the forbidden
     * `buildLedgerGraph`; the composition reads the same controller directly. Every business
     * read/write still goes through [LedgerLeaseScope]'s operation lease, so there is exactly one
     * owner and no second lease.
     */
    val ledger: LedgerLeaseScope?
        get() = controller.ledger

    /**
     * P7-09 09.C (spec section 6.4; D-225 Q20): re-render EVERY host-assigned widget instance. This
     * is the process-scoped half of the first refresh leg — "已知正式写入成功/恢复成功的合并失效". The
     * Android composition root hands it to `P503App`'s platform-neutral
     * `com.unifiedledger.ui.WidgetInvalidationObserver`, so a determinate write/restore success
     * observed in the composition invalidates the widgets through the SAME process-scoped holder the
     * widget receiver reads through (spec section 6.3: one owner/active graph per process, no second
     * graph, no composition-scoped owner). It carries no amount and no month; the fan-out lives on
     * [LedgerWidgetProvider] and re-runs the existing guarded render path (no full-ledger scan).
     */
    fun invalidateAllWidgets() {
        LedgerWidgetProvider.reRenderAllWidgets(applicationContext)
    }
}

/**
 * P7-09 P2-6 (spec section 6.3; D-225 Q19 P1): the pure single-flight admission of the process's
 * controller start. Returns true (and runs [start]) exactly once per [startedOnce]; every later
 * call is a no-op. Extracted from [LedgerProcessHolder.ensureStarted] so the "started exactly once"
 * invariant is JVM-assertable without an Android `Context`.
 */
internal fun ensureStartedOnce(
    startedOnce: AtomicBoolean,
    start: () -> Unit,
): Boolean {
    if (!startedOnce.compareAndSet(false, true)) return false
    start()
    return true
}
