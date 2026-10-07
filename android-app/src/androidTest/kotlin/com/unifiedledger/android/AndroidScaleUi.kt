package com.unifiedledger.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Fresh semantic nodes only; gesture geometry comes from the current scroll container. */
internal class AndroidScaleUi(
    private val instrumentation: Instrumentation,
    private val tick: () -> Unit,
) {
    private val automation get() = instrumentation.uiAutomation
    private val target = "com.unifiedledger.android"

    /**
     * D-208 shared rate limiter: the one stamp every forced reset updates (the
     * init re-assert below, the await periodic reset, and the miss-triggered
     * resets in [findNode] and [scrollable]), so the 30s periodic interval and
     * the 5s miss interval bound one shared reset stream and cannot compound.
     */
    private var lastResetElapsedMs: Long

    init {
        setServiceInfo()
        lastResetElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * D-204 observer repair (test facility only; criteria, product code and the
     * 41-case instrumented list are untouched). The in-process UiAutomation
     * reads every a11y tree through its client-side AccessibilityCache, whose
     * only runtime invalidation is an event delivered to this connection
     * (IAccessibilityServiceClientWrapper.onAccessibilityEvent delegates to the
     * cache before the serviceWantsEvent gate,
     * AccessibilityService.java:2989-3000; eviction and clear paths in
     * AccessibilityCache.java:262-305, android-36.1), while node and window
     * queries are cache-first with no expiry (node lookup
     * AccessibilityInteractionClient.java:604-630; window list
     * AccessibilityInteractionClient.java:490-533, reached from
     * UiAutomation.java:880-890). A connection whose event delivery stalls
     * therefore serves the same stale startup tree indefinitely -- the D-203
     * signature. That client-side feed is unconditional, so this process cannot
     * filter its own cache stream; delivery breadth is fixed by the platform
     * registration, which sets eventTypes=TYPES_ALL_MASK
     * (UiAutomationConnection.java:674-675). The setServiceInfo call below
     * replaces the whole info in one call after clearing the client cache once
     * (UiAutomation.java:839-855), so re-asserting TYPES_ALL_MASK keeps this
     * flag edit from narrowing the registered delivery surface, preserving the
     * documented critical-event contract
     * (AccessibilityCache.CACHE_CRITICAL_EVENTS_MASK, AccessibilityCache.java:61-72).
     * It is defense in depth: whether the stall was a narrowed surface or
     * another delivery failure cannot be decided from client-side sources (the
     * enforcement point is in AMS, not in this source tree), so the absence
     * re-read in [findNode] stays as the bounded second layer.
     */
    private fun setServiceInfo() {
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            }
    }

    fun launch(
        onPoll: (() -> Unit)? = null,
        diagnostic: AndroidScaleObserverDiagWriter? = null,
    ) {
        instrumentation.targetContext.startActivity(Intent.makeMainActivity(ComponentName(target, "$target.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await(onPoll = onPoll, diagnostic = diagnostic) { has("账本：", prefix = true) }
    }

    fun root(): AccessibilityNodeInfo? = automation.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == target }

    /** Every target-package root, not just the first one: multi-window ambiguity stays observable. */
    fun targetRoots(): List<AccessibilityNodeInfo> = automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == target }

    /** Raw window snapshot; capping with a truncation flag is the caller's bound concern. */
    fun windowInfos(): List<AndroidColdstartForensics.WindowInfo> =
        automation.windows.map { window ->
            AndroidColdstartForensics.WindowInfo(
                id = window.id,
                type = window.type,
                active = window.isActive,
                focused = window.isFocused,
                rootPackage = window.root?.packageName?.toString(),
            )
        }

    fun visibleTexts(root: AccessibilityNodeInfo?): List<String> = nodes(root).filter { it.isVisibleToUser }.flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }

    fun visibleNodeCount(root: AccessibilityNodeInfo?): Int = nodes(root).count { it.isVisibleToUser }

    fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val result = ArrayList<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return result
    }

    fun labels(node: AccessibilityNodeInfo): List<String> = nodes(node).flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }

    /**
     * Semantic presence test: true when [findNode] locates a matching node, with
     * that helper's cached-hit / refresh-on-miss rule. A miss can now also
     * trigger one bounded, rate-limited forced cache reset (the D-208 third
     * layer) before the final false is returned.
     */
    fun has(
        text: String,
        prefix: Boolean = false,
    ): Boolean = findNode { candidate -> matches(candidate, text, prefix) } != null

    /**
     * Locates one node with the D-204 two-pass rule (test facility only): a hit is
     * served from the cached walk, and only a miss pays for one root re-read that
     * bypasses the cache. [AccessibilityNodeInfo.refresh] re-fetches the root with
     * `bypassCache=true` (AccessibilityNodeInfo.java:1339-1362, android-36.1) and
     * swaps in its current child ids, after which the walk is retried. The bound
     * is explicit: the re-read bypasses the cache for the root only -- each child
     * fetch still goes through the cache-first path (`getChild` ->
     * `findAccessibilityNodeInfoByAccessibilityId(..., false, ...)`,
     * AccessibilityNodeInfo.java:1452-1468) -- so a miss costs one extra
     * current-root read, not a cache-proof tree walk. Callers must not re-walk
     * [nodes] after this returns -- that walk could disagree with the refreshed
     * tree; this helper is the single source of the found node.
     *
     * D-208 adds a third, miss-triggered layer (evidence: run 37130347905 --
     * the saf_import stage failed in 825ms while the frame-buffer screenshot
     * showed the import screen fully rendered, the D-203 stale-cache signature
     * recurring outside the coldstart path): when both passes miss and the
     * shared limiter allows ([missResetDue] over the one stamp every reset
     * updates, [AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS]), one forced
     * reset clears the client cache once and one final cached walk -- over a
     * freshly fetched root, since the reset also invalidates the cached window
     * list -- returns whatever it finds. Containment is scoped precisely: the
     * reset itself (the setServiceInfo replacement) is fully contained and never
     * throws, while the post-reset walk carries the same exposure class as the
     * pre-existing pass-1/pass-2 walks -- no new failure class. A reset failure
     * is swallowed (findNode has no writer access; call sites that own a writer
     * record resets on their own paths) and the final walk's result is still
     * returned; the helper never ticks -- its callers own the deadline checks,
     * and the await predicates among them already tick.
     */
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = root()
        nodes(root).firstOrNull(predicate)?.let { return it }
        if (root == null || !root.refresh()) return null
        nodes(root).firstOrNull(predicate)?.let { return it }
        if (!missResetDue(SystemClock.elapsedRealtime(), lastResetElapsedMs)) return null
        lastResetElapsedMs = SystemClock.elapsedRealtime()
        try {
            resetAutomationCache()
        } catch (ignored: Throwable) {
            // Contained by contract: findNode has no writer access, so the
            // failed reset is swallowed and the final walk still answers.
        }
        return nodes(root()).firstOrNull(predicate)
    }

    private fun matches(
        candidate: AccessibilityNodeInfo,
        text: String,
        prefix: Boolean,
    ): Boolean = candidate.isVisibleToUser && listOfNotNull(candidate.text?.toString(), candidate.contentDescription?.toString()).any { if (prefix) it.startsWith(text) else it == text }

    /**
     * D-205 cache-reset repair of the D-204 shortfall, plus its forced-reset
     * diagnostic; D-208 promotes the bounded periodic reset to every wait.
     * Test facility only: criteria, product code, scale and the 41-case
     * instrumented list are untouched.
     *
     * D-204 answered a miss with one root-only [AccessibilityNodeInfo.refresh]
     * (children stay cache-first) and a single TYPES_ALL_MASK re-assert at init,
     * which is too early to matter. Two further maximum rounds still read the
     * 8-node loading tree for the whole 180s wait while ULStartup timing and
     * frame-buffer screenshots proved Ready in ~3s: the client cache was filled
     * with the loading tree during the 0.6-3.5s load window and a stalled event
     * feed never invalidated it. [resetAutomationCache] re-runs the same
     * setServiceInfo() whose replacement clears the client AccessibilityCache
     * once (UiAutomation.java:839-855), so while the observer stays blind a
     * bounded, periodic reset forces queries to re-read the current window
     * state. The system-side cause of the stalled delivery stays unproven; this
     * is a client-side programmable forced invalidation, not a root-cause
     * claim, and it does not change the readiness criterion (`has("账本：")`).
     *
     * Reset robustness (D-205 review): `UiAutomation.getServiceInfo` and
     * `setServiceInfo` are synchronous binder calls with no client-side
     * timeout. On a wedged accessibility connection they can block this thread
     * past the stage deadline; the client cannot bound that, and only the host
     * phase deadline (which kills the instrumentation process) ends the wait.
     * `tick()` therefore runs both immediately before and immediately after
     * the reset, so the latest runnable moment is deadline-checked and a binder
     * call that overran the deadline surfaces on the very next line; the call
     * itself cannot be interrupted. The interval is wide
     * (`AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS`, 30s) to keep the
     * exposure small. Both the reset and the observation are contained: a
     * failure is recorded as a degraded entry and never replaces the wait's own
     * failure with a new throw.
     *
     * `onPoll` is a coldstart-only forensics hook: it runs after a passed tick,
     * must never call tick(), and stays null at every other call site.
     *
     * D-208 (evidence: run 37130347905 -- the 825ms saf_import failure while
     * the frame-buffer screenshot showed the import screen fully rendered,
     * i.e. business-stage waits were unprotected): the D-205 gate that mounted
     * the periodic reset only where a writer is attached is removed from the
     * reset itself. Every wait now performs the 30s bounded reset on the same
     * terms -- `tick()` immediately before and after the synchronous binder
     * call, rate-limited by the one shared `lastResetElapsedMs` stamp every
     * reset updates. The D-205 sentence "without one this loop performs no
     * reset" is superseded by D-208: `diagnostic` is recording-only. When a
     * writer is attached (the coldstart wait path) resets and observations are
     * recorded exactly as before; without one, a reset failure is swallowed
     * silently and no observation is recorded. No predicate, timeout or
     * product behavior changes.
     */
    fun await(
        timeout: Long = 180000,
        onPoll: (() -> Unit)? = null,
        diagnostic: AndroidScaleObserverDiagWriter? = null,
        predicate: () -> Boolean,
    ) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            tick()
            onPoll?.invoke()
            if (predicate()) return
            // Bounded forced invalidation for every wait (D-208): rate-limited
            // by the shared stamp, tick-bracketed exactly as D-205.
            val now = SystemClock.elapsedRealtime()
            if (scaleCacheResetDue(now, lastResetElapsedMs)) {
                lastResetElapsedMs = now
                tick()
                val reset = resetAutomationCacheSafely(diagnostic)
                // Re-check the deadline after the synchronous binder call
                // returns: the check cannot interrupt a wedged binder (only
                // the host phase deadline can), but it is the first moment
                // the loop is runnable again.
                tick()
                if (reset && diagnostic != null) {
                    try {
                        diagnostic.record(resetAutomationCacheObservation())
                    } catch (failure: Throwable) {
                        diagnostic.recordObservationFailure(SystemClock.elapsedRealtime(), failure)
                    }
                }
            }
            SystemClock.sleep(150)
        }
        error("UI condition deadline exceeded")
    }

    /**
     * Runs the forced reset and contains any failure.
     *
     * Returns true only when the reset itself succeeded, so the caller can tell
     * "reset failed" apart from "reset worked, observation failed" without
     * nesting their handlers. With a writer attached, a reset failure is
     * recorded as a degraded entry; without one (D-208: every wait performs
     * resets, not every wait has a writer) it is swallowed silently. The write
     * path catches its own failures too, so nothing can escape into the wait
     * loop.
     */
    private fun resetAutomationCacheSafely(diagnostic: AndroidScaleObserverDiagWriter?): Boolean =
        try {
            resetAutomationCache()
            true
        } catch (failure: Throwable) {
            // A reset failure must not replace the wait's own deadline failure
            // with a new exception: it is recorded where a writer exists and
            // the loop keeps waiting, exactly as it did before the reset
            // existed on this path.
            diagnostic?.recordResetFailure(SystemClock.elapsedRealtime(), failure)
            false
        }

    /**
     * Forced client-cache invalidation; see [await]. Deliberately a second call
     * site of the D-204 [setServiceInfo] so the flag/event-mask semantics cannot
     * drift between initial setup and the bounded reset. D-208 reuses it for the
     * miss-triggered resets in [findNode] and [scrollable] under the shared
     * limiter.
     */
    private fun resetAutomationCache() {
        setServiceInfo()
    }

    /** One post-reset observation: the decisive evidence for the next attribution round. */
    private fun resetAutomationCacheObservation(): AndroidScaleObserverDiag.Entry =
        AndroidScaleObserverDiag.Entry(
            atMs = SystemClock.elapsedRealtime(),
            targetWindowIds = automation.windows.filter { it.root?.packageName?.toString() == target }.map { it.id },
            hasReadyTitle = has("账本：", prefix = true),
            visibleTexts = targetRoots().firstOrNull()?.let(::visibleTexts).orEmpty(),
        )

    fun clickNode(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(7) {
            val candidate = current ?: error("no clickable ancestor")
            if (candidate.isEnabled && candidate.isVisibleToUser && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                SystemClock.sleep(180)
                return
            }
            current = candidate.parent
        }
        // D-217 fallback: the semantic click path can be refused on this
        // stack even for a visible, enabled row (the client tree exposes the
        // node but its clickable ancestor rejects ACTION_CLICK). Tap the
        // node's screen coordinates instead — the same target the semantic
        // path aimed at.
        val rect = bounds(node)
        check(rect.width() > 0 && rect.height() > 0) { "semantic click refused and node has no on-screen bounds" }
        val down = SystemClock.uptimeMillis()
        val events =
            listOf(
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
                MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
            )
        try {
            events.forEach { event ->
                check(automation.injectInputEvent(event, true)) { "coordinate tap injection refused" }
            }
        } finally {
            // Recycle the whole batch even when an injection throws, so a failed
            // tap cannot leak the remaining MotionEvents from the pool.
            events.forEach { it.recycle() }
        }
        SystemClock.sleep(180)
    }

    fun click(
        text: String,
        prefix: Boolean = false,
    ) {
        await { has(text, prefix) }
        val node = findNode { candidate -> matches(candidate, text, prefix) } ?: error("clickable target absent: $text")
        clickNode(node)
    }

    /**
     * Clicks [clickText] until the observable postcondition [effectText]
     * surfaces (bounded). The D-216 local runs proved a click performed on a
     * stale client-cache node can fail silently (the checkbox toggled nothing
     * and the screen never changed), so the caller must be able to retry; a
     * click that did register surfaces the effect immediately, so the retry
     * loop is a no-op in the honest path. The short effect wait keeps a ghost
     * from burning the stage deadline.
     */
    fun clickUntil(
        clickText: String,
        effectText: String,
        attempts: Int = 5,
    ) {
        repeat(attempts) {
            click(clickText)
            try {
                await(10000) { has(effectText) }
                return
            } catch (ignored: IllegalStateException) {
            }
        }
        error("click effect not observed: $clickText -> $effectText")
    }

    /**
     * D-208: when the cached walk finds no visible scrollable container, one
     * rate-limited forced reset plus a single re-walk run before the error, so
     * a client cache frozen on a previous screen (run 37130347905: saf_import
     * failed in 825ms with `scroll container absent` while the frame-buffer
     * screenshot showed the import screen fully rendered) cannot fail the
     * stage while the real tree is fine. Containment is scoped precisely: the
     * reset itself (the setServiceInfo replacement) is fully contained and
     * never throws, while the re-walk carries the same exposure class as the
     * pre-existing first walk -- no new failure class. The reset is limited
     * by the shared stamp and never ticks (its callers own the deadline
     * checks), and the error text is unchanged when the re-walk still finds
     * nothing.
     */
    fun scrollable(): AccessibilityNodeInfo {
        widestVisibleScrollable()?.let { return it }
        if (missResetDue(SystemClock.elapsedRealtime(), lastResetElapsedMs)) {
            lastResetElapsedMs = SystemClock.elapsedRealtime()
            try {
                resetAutomationCache()
            } catch (ignored: Throwable) {
                // Contained by contract: scrollable has no writer access, so
                // the failed reset is swallowed and the re-walk still answers.
            }
        }
        return widestVisibleScrollable() ?: error("scroll container absent")
    }

    private fun widestVisibleScrollable(): AccessibilityNodeInfo? = nodes(root()).filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { bounds(it).height() }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    fun scroll(
        forward: Boolean = true,
        fraction: Float = 0.6f,
    ) {
        tick()
        val bounds = bounds(scrollable())
        check(bounds.width() > 0 && bounds.height() > 100)
        val distance = bounds.height() * fraction
        val center = bounds.centerY().toFloat()
        val start = center + (if (forward) distance / 2 else -distance / 2)
        val finish = center - (if (forward) distance / 2 else -distance / 2)
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val action = if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            val time = down + step * 15
            val event = MotionEvent.obtain(down, time, action, bounds.centerX().toFloat(), start + (finish - start) * step / 12, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true)) { "gesture injection refused" }
            event.recycle()
            if (step < 12) SystemClock.sleep(15)
        }
        // Stop moving before release; do not turn traversal into a fling.
        SystemClock.sleep(160)
        val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.centerX().toFloat(), finish, 0)
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        check(automation.injectInputEvent(up, true))
        up.recycle()
        settle()
    }

    fun settle() {
        var previous: String? = null
        var stable = 0
        await {
            val current = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { "${it.text}:${bounds(it)}" }
            stable = if (current == previous) stable + 1 else 0
            previous = current
            stable >= 2
        }
    }

    fun seek(
        text: String,
        forward: Boolean = true,
        maxScrolls: Int = 80,
    ) {
        repeat(maxScrolls) {
            if (has(text, prefix = true)) return
            scroll(forward)
        }
        error("UI target not reached")
    }

    fun edge(last: Boolean) {
        val container = scrollable()
        val index = if (last) (container.collectionInfo?.rowCount ?: error("collection size missing")) - 1 else 0
        if (container.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id }) {
            check(container.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id, Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, index) }))
            settle()
            return
        }
        var previous = ""
        // D-217 round 14 (evidence23/24): the client tree lags the gestures
        // by ~5 samples, so the FIRST unchanged comparison is not evidence of
        // an edge — it is a lag plateau (observed: the proof failed at the
        // TOP of the disposition card, viewport unmoved). Require eight
        // consecutive unchanged samples — each carrying a forced cache reset
        // and a marker proof — before declaring the edge unproven; a movement
        // resumption resets the count.
        var stationaryCount = 0
        repeat(30000) {
            val current = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { it.text?.toString().orEmpty() }
            if (current == previous) {
                stationaryCount++
                runCatching { resetAutomationCache() }
                var proven = false
                var proofs = 0
                while (!proven && proofs++ < 3) {
                    proven = has("进入批量确认", prefix = true) ||
                        has("确认整组标记") ||
                        has("最近批量结果", prefix = true) ||
                        has("刷新清单")
                    // D-217 round 21 (closure review): sample over time, not
                    // back-to-back — consecutive has() reads against the
                    // lagging client tree need spacing to observe movement
                    // or resolution.
                    if (!proven) SystemClock.sleep(150)
                }
                if (proven) return
                check(stationaryCount < 8) { "stationary viewport is not a proven edge" }
            } else {
                stationaryCount = 0
            }
            previous = current
            // D-217 round 14 (evidence23): 0.8 gestures freeze this list —
            // the first comparison then reads stationary at the TOP of the
            // card, where none of the edge markers is visible, and the proof
            // fails ("stationary viewport is not a proven edge"). 0.25 is the
            // only fraction that has ever moved this list on device
            // (rounds 8/9/11).
            scroll(last, 0.25f)
        }
        error("edge traversal bound exceeded")
    }

    fun selectSafFixture(fileName: String) {
        // The documentsui window can appear with a readable window list but an
        // unreadable root (node fetches stay empty for the whole default await
        // — observed intermittently on the local channel while the window was
        // focused and the app alive). Close the sheet and re-enter the picker;
        // a fresh window is readable again.
        repeat(3) { attempt ->
            try {
                selectSafFixtureOnce(fileName)
                return
            } catch (failure: IllegalStateException) {
                if (attempt == 2) throw failure
                runCatching { automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
                await(20000) { has("选择文件") }
            }
        }
    }

    private fun selectSafFixtureOnce(fileName: String) {
        // The caller clicks 导入 and returns immediately; on a fast host the
        // import screen can still be unrendered when the first seek fires, and
        // seeking on the (unscrollable, empty) home tree dies with "scroll
        // container absent" — the local-small run's saf_import failure at
        // 810ms. Await the format entry itself: await polls has() with the
        // D-208 rate-limited resets, so a stale cache cannot hide the screen.
        await { has("支付宝账单（CSV）") }
        seek("支付宝账单（CSV）", forward = false)
        val format = findNode { it.text?.toString() == "支付宝账单（CSV）" } ?: error("Alipay format entry absent")
        val picks = nodes(root()).filter { it.text?.toString() == "选择文件" && it.isVisibleToUser }
        clickNode(picks.minByOrNull { kotlin.math.abs(bounds(it).centerY() - bounds(format).centerY()) } ?: error("Alipay picker absent"))
        await {
            automation.windows.mapNotNull { it.root }.any { it.packageName?.toString()?.contains("documentsui") == true }
        }
        var navigated = false
        await {
            val picker = automation.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString()?.contains("documentsui") == true } ?: return@await false
            val entries = nodes(picker)
            val file = entries.firstOrNull { it.text?.toString() == fileName && it.isVisibleToUser }
            if (file != null) {
                clickNode(file)
                true
            } else {
                val downloads = entries.firstOrNull { it.text?.toString() in listOf("下载", "Downloads") && it.isVisibleToUser }
                if (downloads != null && !navigated) {
                    clickNode(downloads)
                    navigated = true
                }
                if (downloads == null && !navigated) {
                    entries.firstOrNull { it.contentDescription?.toString() in listOf("显示根目录", "Show roots", "打开导航抽屉", "Open navigation drawer") }?.let(::clickNode)
                }
                false
            }
        }
    }

    private fun candidateNodes(): List<AccessibilityNodeInfo> {
        val container = scrollable()
        val viewport = bounds(container)
        val labeled =
            nodes(container).filter { node ->
                node.isVisibleToUser &&
                    node.actionList.any { it.label?.toString() == "查看候选详情" } &&
                    bounds(node).let { it.height() > 0 && it.top >= viewport.top && it.bottom <= viewport.bottom }
            }
        if (labeled.isNotEmpty()) return labeled.sortedBy { bounds(it).top }
        // D-216 fallback: the labeled click action may never surface to the
        // instrumentation client, and the container-scoped walk can miss the
        // rendered rows entirely (both observed in the local-small runs where
        // this path had never executed anywhere before). Locate row roots by
        // content from the window root instead — a superset walk that does not
        // depend on which scrollable the client exposes — after one
        // rate-limited forced cache reset clears any stale client tree.
        // Ascend from a visible amount text to the nearest ancestor carrying
        // the candidate metadata line, then to the nearest clickable node —
        // the same row the labeled action would have identified. Signature
        // exactness is unchanged.
        if (missResetDue(SystemClock.elapsedRealtime(), lastResetElapsedMs)) {
            lastResetElapsedMs = SystemClock.elapsedRealtime()
            try {
                resetAutomationCache()
            } catch (ignored: Throwable) {
                // Contained by contract, same as findNode/scrollable.
            }
        }
        val amount = Regex("^[0-9]+\\.[0-9]{2} CNY$")
        val meta = Regex("类型 ordinary_flow；发生 [0-9T:+\\-]+；方向 out；状态 settled；重复 ")
        val roots = ArrayList<AccessibilityNodeInfo>()
        for (text in nodes(root())) {
            if (!text.isVisibleToUser || !amount.matches(text.text?.toString() ?: "")) continue
            if (bounds(text).height() <= 0) continue
            var node = text.parent
            var hops = 0
            while (node != null && hops++ < 8) {
                if (meta.containsMatchIn(labels(node).joinToString("\n"))) {
                    var click: AccessibilityNodeInfo? = node
                    var clickHops = 0
                    while (click != null && clickHops++ < 4 && !click.isClickable) click = click.parent
                    click?.let { roots += it }
                    break
                }
                node = node.parent
            }
        }
        return roots.distinctBy { bounds(it) }.sortedBy { bounds(it).top }
    }

    private fun signature(node: AccessibilityNodeInfo): String = signatureOrNull(node) ?: error("candidate signature absent")

    /**
     * D-217 review: the nullable signature used by the collect-only traversal.
     * A node that carries an amount but no candidate metadata (the labeled-action
     * path can surface such a row) is skipped instead of aborting the whole pass;
     * the then-missing row fails the strict multiset comparison in reopen(), so
     * the coverage mandate still lands at the acceptance boundary. Detail
     * navigation keeps the strict [signature], where a malformed target must
     * fail loudly.
     */
    private fun signatureOrNull(node: AccessibilityNodeInfo): String? {
        val text = labels(node).joinToString("\n")
        val amount = Regex("[0-9]+\\.[0-9]{2} CNY").find(text)?.value ?: return null
        val meta = Regex("类型 ordinary_flow；发生 [^\n]+?；方向 out；状态 settled；重复 (?:无|[A-Z_]+)").find(text)?.value ?: return null
        return "$amount|$meta"
    }

    fun openCandidate(
        row: ScaleRow,
        expected: List<ScaleRow>,
    ) {
        val rank = expected.indexOfFirst { it.id == row.id }
        check(rank >= 0)
        // Detail navigation, not traversal coverage: a unique live signature
        // stays mandatory. The indexed scrollToPosition jump is an accelerator
        // for deep ranks, not a precondition — the local-small run proved the
        // review LazyColumn can render without exposing ACTION_SCROLL_TO_
        // POSITION to the instrumentation client (180s await never saw it;
        // detail_decision had never executed anywhere before), while the
        // signature loop below is the actual navigation mechanism and keeps
        // its own bound. Jump when the action exists; always fall through to
        // the bounded loop.
        val container = widestVisibleScrollable()
        if (container != null && container.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id }) {
            check(
                container.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, rank + 200)
                    },
                ),
            )
            settle()
        }
        // Deterministic scan (D-216): the list renders the product's own class
        // groups in query order, which is a different domain from the
        // displayRows (group, id) rank the loop used to compare against — the
        // local-small runs showed the rank heuristic oscillating forever
        // without ever visiting the target. Scroll to the top (backward until
        // the row window is stationary), then scan forward exactly once until
        // the unique signature appears or the list bottom proves stationary.
        // The client tree flaps (D-203 family): one whole pass can miss the
        // target row, so the pass runs twice with a forced cache reset between
        // attempts before the honest failure.
        for (attempt in 0 until 2) {
            if (attempt > 0) runCatching { resetAutomationCache() }
            var previous = ""
            var topScrolls = 0
            while (topScrolls++ < 30) {
                val current = candidateNodes().joinToString("|") { signature(it) }
                // An empty window means no rows are visible at all: nothing is
                // above to reach, and waiting for rows here burned the whole
                // stage budget once already.
                if (current.isEmpty() || current == previous) break
                previous = current
                scroll(forward = false, fraction = 0.8f)
            }
            var scans = 0
            while (scans++ < 60) {
                // The client tree flaps: the same position can read empty (or
                // miss the target) on one walk and read fine on the next.
                // Re-walk each position a bounded three times before scrolling
                // on.
                var rows = candidateNodes()
                var matched = rows.firstOrNull { signature(it) == row.signature }
                var polls = 0
                while (matched == null && polls++ < 2) {
                    rows = candidateNodes()
                    matched = rows.firstOrNull { signature(it) == row.signature }
                }
                matched?.let {
                    clickNode(it)
                    // A ghost match (stale-cache node that does not open the
                    // detail) must not burn the stage deadline in the 180s
                    // default await; retry the scan instead.
                    val opened =
                        try {
                            await(20000) { has("候选详情") }
                            true
                        } catch (ignored: IllegalStateException) {
                            false
                        }
                    if (opened) return
                }
                val top = rows.firstOrNull()?.let { bounds(it).toString() + signature(it) } ?: ""
                scroll(forward = true, fraction = 0.8f)
                val after = candidateNodes().firstOrNull()?.let { bounds(it).toString() + signature(it) } ?: ""
                if (top.isNotEmpty() && top == after) break
            }
        }
        error("unique candidate not reached (rank=$rank, deterministic scan exhausted)")
    }

    /**
     * D-217: detail navigation without an oracle read. The target row is
     * identified by its unique spec-derived amount (the caller derives it from
     * the manifest as the unique session's offset-based first-row amount — see
     * `uniqueSessionFirstAmount`; rows do not render their session id, verified
     * interactively). D-217 round 5: the amount is GLOBALLY unique by fixture
     * construction — shared amounts span seed+1..seed+rowsPerSession and the
     * unique session starts at seed+rowsPerSession+1 — so NO header scoping is
     * needed: any visible row carrying the amount IS the target row, and more
     * than one is a real anomaly. The round-4 header-scoped pages loop
     * oscillated (scroll forward hunting the row, scroll back to re-find the
     * header) and never advanced past the pending group's first viewport — the
     * target renders at the BOTTOM of the ~25-row group (within-batch
     * candidate_id order, unique session imported last), about two viewports
     * below the header (evidence7 logcat: `open page=1..6 header=true
     * matches=0` on both attempts). The mechanism is instead one bounded
     * downward scan from the list top, as in [openCandidate]: each step re-reads
     * the visible rows, clicks the single row carrying the amount, and advances
     * until the list bottom proves stationary.
     *
     * D-217 round 7 (device ground truth: evidence13 local-small, evidence8
     * tree-lag samples): after the top seek the walk performs the pre-D-217
     * ACTION_SCROLL_TO_POSITION accelerator when the container exposes it
     * (CI-hosted runners do; the local emulator does not — D-216), jumping to
     * [jumpIndex] + 12. [jumpIndex] is the target's candidate index within the
     * review list (the caller passes spec.rowsPerSession: group 0 leads with
     * session-01's rowsPerSession never-subject rows and the unique session is
     * imported last within prepare). The +12 slack is deliberately
     * undershoot-biased: it covers the non-candidate render items ahead of the
     * first candidate (title bar, dividers, format section and entries, intake
     * summary lines, pending header, group 0's GroupHeader), and any shortfall
     * — up to the 200-line intake record cap on maximum — is absorbed by the
     * forward scan below, while overshooting past the target would be
     * unrecoverable because the scan never moves backward. Without the action
     * (the local channel) the 120-step scan from the top stays the whole
     * mechanism: local-small/medium keep the target ~2 viewports down. A
     * maximum profile WITHOUT the action cannot be scanned within the
     * detail_decision budget — that stays the pre-existing D-216 residual, not
     * something this walk fixes.
     */
    fun openCandidateByAmount(
        amount: Long,
        jumpIndex: Int,
    ) {
        val target = amountText(amount)
        // The review screen can be parked anywhere between its "recent
        // imports" header and the candidate groups; any of these markers (or
        // the target amount itself) proves the review surface is live.
        await { has("刷新清单") || has("待确认——缺用户决策", prefix = true) || has(target) }
        var scans = 0
        var sawTargetRow = false
        var clickAttempts = 0
        for (attempt in 0 until 2) {
            if (attempt > 0) runCatching { resetAutomationCache() }
            // Seek the list top first: the TitleBar (「刷新清单」, the first
            // render item of the review list, P503ImportReviewPresentation)
            // is visible only at the top, seekBackToTop returns immediately
            // when it is already visible, and a container that refuses to
            // move ends the seek bounded with no error. The reset above
            // (attempt 2) clears the stale client tree the first attempt's
            // interactions can leave behind, so the seek's has() reads fresh.
            seekBackToTop("刷新清单")
            // D-217 walk diagnostics (revert before PR if not wanted)
            Log.i("ULScaleWalk", "open seekDone attempt=$attempt markerVisible=${has("刷新清单")} rows=${candidateNodes().size}")
            // D-217 round 7 accelerator: jump when the container exposes
            // ACTION_SCROLL_TO_POSITION (same shape as the pre-D-217
            // [openCandidate] accelerator). The slack is undershoot-biased on
            // purpose — 12 covers the review list's non-candidate render items
            // ahead of the first candidate (title bar, dividers, format section
            // and entries, intake summary, pending header, group 0's
            // GroupHeader); a larger constant would overshoot past the target
            // on profiles with a shorter preamble, and an overshoot can never
            // be recovered because the scan below never moves backward. Any
            // undershoot (up to the 200-line intake record cap on maximum,
            // ~32 extra scan steps) is absorbed by the bounded scan.
            val jumpRow = jumpIndex + 12
            val container = widestVisibleScrollable()
            val jumpExposed = container?.actionList?.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id } == true
            if (container != null && jumpExposed) {
                check(
                    container.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                        Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, jumpRow)
                        },
                    ),
                )
                settle()
            }
            // D-217 walk diagnostics (revert before PR if not wanted)
            Log.i("ULScaleWalk", "open jump attempt=$attempt exposed=$jumpExposed index=$jumpRow")
            var steps = 0
            var stationaryCount = 0
            // D-217 round 8 set the cap at 120 for the 0.25 stride (~3
            // rows/step: the jump accelerator's <= ~201-item undershoot on
            // maximum needed ~70 steps; 120 left margin). D-217 round 21
            // (closure review) halves the advance to 0.12 (~1.5 rows/step),
            // so the arithmetic moved: the same <= ~201-item undershoot now
            // needs ~134 steps, plus up to 8 stationary-verdict samples —
            // the 120 cap can no longer cover it, so the cap is 160.
            // Local-small (no accelerator, target ~2 viewports = ~22-26 rows
            // down the list) needs only ~15-18 steps from the top.
            while (steps++ < 160) {
                scans++
                // Staleness hardening: the click/back interactions can stale
                // the client tree mid-scan; every 8th step forces one bounded
                // cache reset, rate-limited by step parity alone (no
                // time-based throttle needed).
                if (steps % 8 == 0) runCatching { resetAutomationCache() }
                val rows = candidateNodes()
                val matches = rows.filter { signatureAmount(it) == target }
                if (matches.isNotEmpty()) sawTargetRow = true
                // D-217 walk diagnostics (revert before PR if not wanted)
                Log.i("ULScaleWalk", "open scan attempt=$attempt step=$steps matches=${matches.size} rows=${rows.size} stationaryCount=$stationaryCount")
                // Global uniqueness is a fixture invariant: more than one
                // visible row carrying the amount is a real anomaly, not a
                // scoping problem to retry around.
                if (matches.size > 1) error("target amount is not globally unique on screen (count=${matches.size} amount=$target)")
                matches.singleOrNull()?.let { row ->
                    clickAttempts++
                    clickNode(row)
                    // A ghost match (stale-cache node that does not open the
                    // detail) must not burn the stage deadline in the 180s
                    // default await; retry the scan instead.
                    val opened =
                        try {
                            await(20000) { has("候选详情") }
                            true
                        } catch (ignored: IllegalStateException) {
                            false
                        }
                    // The coordinate-tap fallback can land on a stale node
                    // whose rendered neighbor is a different row, so the
                    // detail must show the target amount; a wrong row cannot
                    // be selected at all, so back out and keep looking.
                    // D-217 walk diagnostics (revert before PR if not wanted)
                    Log.i("ULScaleWalk", "open click attempt=$attempt step=$steps clicks=$clickAttempts opened=$opened targetVisible=${has(target)}")
                    if (opened && has(target)) return
                    if (opened) {
                        click("返回")
                        await { has("刷新清单") }
                    }
                }
                // Advance and detect the stationary bottom exactly like
                // collectTraversal (first-row bounds+signature identical
                // before/after). The client tree lags the gestures (round-4
                // evidence: the first visible row changes only every ~5 scroll
                // steps), so a SINGLE unchanged sample cannot distinguish the
                // list bottom from a tree that is not refreshed yet — the
                // round-5 attempts (evidence8) both died on it, steps after
                // the candidate rows first appeared. A stationary verdict
                // requires eight consecutive unchanged samples, each followed
                // by a forced refresh and another scroll attempt; eight
                // samples cannot occur in the first steps, so the old
                // minimum-scan guard is subsumed, and eight exceeds the ~5
                // gesture plateau so the verdict cannot fire inside a
                // tree-lag window where the list is actually moving
                // (round-4-era evidence6 logcat: firstRow plateaus of ~5
                // steps between advances).
                val top = rows.firstOrNull()?.let { bounds(it).toString() + signatureOrNull(it) } ?: ""
                // D-217 round 8: the advance scroll used fraction 0.25 — the
                // only fraction that had ever moved this list on device
                // (evidence6, round-4-era walk at 0.25: the first-row amount
                // advanced across all 40 steps), while the 0.6 gestures of
                // rounds 5-7 left rows frozen (evidence14: rows stay 10-11
                // across every step, three consecutive cache-reset samples
                // read an identical first row). The mechanism why 0.6 fails
                // is unknown; the evidence is what fixes the parameter.
                // D-217 round 21 (closure review): halved to 0.12, the
                // round-11 stride already proven in collectTraversal — at
                // ~3 rows/gesture the client tree's ~5-sample lag advances
                // the view ~15 rows between samples, more than the
                // ~10-11-row window, so a sample can skip rows entirely
                // (evidence17's signature loss); at ~1.5 rows/gesture the
                // lag advance is ~7-8 rows, inside the window.
                scroll(forward = true, fraction = 0.12f)
                val after = candidateNodes().firstOrNull()?.let { bounds(it).toString() + signatureOrNull(it) } ?: ""
                if (top.isNotEmpty() && top == after) {
                    stationaryCount++
                    if (stationaryCount >= 8) break
                    // The tree lags the gestures: force a fresh tree before
                    // the next sample so "not refreshed yet" is not counted
                    // as stationary.
                    runCatching { resetAutomationCache() }
                } else {
                    stationaryCount = 0
                }
            }
        }
        error("unique candidate not reached (amount=$target sawTargetRow=$sawTargetRow clickAttempts=$clickAttempts scans=$scans)")
    }

    /**
     * D-217: collect-only traversal, multiset-preserving. Walks the review list
     * once — top, then a single forward pass to the stationary bottom —
     * recording ONE entry per rendered row occurrence, and returns the collected
     * signature list (the caller stores it as observedCandidates). The list
     * renders the product's class groups in query order, a different domain from
     * the spec's row order, so no in-chain alignment is attempted; reopen()
     * compares the collected list against the oracle's quiescent displayRows as
     * an exact multiset (same size, same frequencies).
     *
     * Overlap resolution, not value dedupe: a signature is NOT injective (the
     * maximum fixture has ~11,000 distinct signatures for 61,000 rows, so a
     * value-keyed set can never reach the true row count). Because the same
     * signature repeats adjacently in the fixture (a value can appear many
     * rows in a row), the overlap length must be UNIQUE: if more than one k in
     * 1..min(window, collected) satisfies collected.takeLast(k) ==
     * window.take(k), the true boundary is unrecoverable and guessing the
     * largest k can silently drop or double-count rows. So each scroll step
     * requires exactly one matching k and appends only window.drop(k); a
     * window that matches none, or matches more than one k, is a blind/
     * skipped or boundary-ambiguous viewport and is retried with the bounded
     * backward nudge under one shared budget, erroring only when the
     * budget is exhausted. The first non-empty window seeds the collection
     * whole (there is no collected prefix to overlap against yet). This is
     * the pre-D-217 [traverse] overlap mechanism
     * (scaleWindowOffset + its retry loop) without its oracle dependency: it
     * needs no expected order and no oracle read, so it stays inside the D-212
     * wall. The unique-session first row must appear (its amount is
     * spec-derived and passed in as [anchorAmount]; the caller derives it from
     * the manifest so this facility needs no spec type), anchoring the
     * collection to the fixture.
     *
     * [expectedRows] bounds the walk by collected size (the pre-D-217
     * `traverse` did the same against the oracle size): each forward scroll
     * advances ~1.5 rows of an ~11-13-row viewport (round-11 fraction 0.12),
     * so a fixed scan cap cannot reach the 61,000-row maximum. The internal
     * safety cap (expectedRows*4 + 200) is a generous stuck-viewport guard
     * above the worst-case rows-to-scans ratio.
     */
    fun collectTraversal(
        anchorAmount: Long,
        expectedRows: Int,
    ): List<String> {
        // D-217: the previous stage may have left the app on the detail
        // screen (its back click can silently fail on a stale node); the
        // detail screen has no scrollable container, so edge() below would
        // die there. Return to the review list with a verified click, then
        // run the top walk.
        if (has("候选详情")) {
            clickUntil("返回", "刷新清单")
        }
        // D-217 round 4: the previous "do NOT rely on backward scrolling"
        // reasoning removed the top walk and left a downward-only walk
        // from an unknown parking position — exactly the fragility the round-3
        // device run then hit. Seek the list top first — the TitleBar
        // (「刷新清单」, the first render item of the review list) is
        // guaranteed on this screen at this stage and is visible only at the
        // top; seekBackToTop is bounded and silent when the container refuses
        // to move, so the worst case is the pre-fix behavior, and the
        // await/miss-reset inside the walk below still applies.
        // D-217 round 5: the old small downward header-search loop with its
        // fatal `check(found)` gate is REMOVED. The header anchor added
        // nothing the existing `check(sawAnchor)` below does not already pin
        // (the unique session's first-row amount is globally unique by fixture
        // construction), and the same mid-list/stale-tree fragility that broke
        // the detail walk would kill traversal at exactly that gate. The
        // pending group is the FIRST group of the review list, so the first
        // non-empty window after the top seek is the pending group's own rows
        // and the seeding logic below starts the collection correctly.
        seekBackToTop("刷新清单")
        // D-217 walk diagnostics (revert before PR if not wanted)
        Log.i("ULScaleWalk", "collect seekDone markerVisible=${has("刷新清单")} pendingVisible=${has("待确认——缺用户决策", prefix = true)} rows=${candidateNodes().size}")
        check(expectedRows > 0)
        val collected = ArrayList<String>()
        // Bound by collected size, not a fixed scan budget: each forward scroll
        // advances ~1.5 rows of an ~11-13-row viewport (round-11 fraction
        // 0.12), so the 61,000-row maximum needs ~40,000-45,000 scans.
        // safetyCap = expectedRows*4 + 200 is comfortably above that
        // worst-case rows-to-scans ratio while still terminating a
        // stuck/duplicated viewport (the pre-D-217 `traverse` bounded against
        // the oracle size for the same reason).
        val safetyCap = expectedRows.toLong() * 4L + 200L
        var scans = 0L
        var retries = 0
        var stationaryCount = 0
        // The unique session's first-row amount is unique to that row by
        // fixture construction (rows do not render session ids): the shared
        // sessions span seed+1..seed+rowsPerSession and the unique session
        // starts at seed+rowsPerSession+1, so the amount prefix alone is the
        // anchor. The caller derives it from the manifest (D-217 review: the
        // old seed+1 targeted the FIRST shared row, not the unique one).
        val anchor = amountText(anchorAmount) + "|"
        var sawAnchor = false
        while (collected.size < expectedRows && scans++ < safetyCap) {
            val sizeBefore = collected.size
            val window = candidateNodes().mapNotNull(::signatureOrNull)
            if (window.isEmpty() && collected.isEmpty()) {
                // Nothing rendered yet (the header just settled): one plain
                // forward scroll without an overlap claim.
                scroll(fraction = 0.3f)
                continue
            }
            if (collected.isEmpty()) {
                // Seed (round-2 review fix): the first non-empty window has
                // no collected prefix to overlap against, so the WHOLE
                // window becomes the collected prefix and the overlap
                // discipline starts with the next window. Without this the
                // first step computes minOf(window.size, 0) = 0, matches
                // nothing, and burns the retry budget on every start.
                collected.addAll(window)
            } else {
                // Require a UNIQUE contiguous overlap confined to the
                // collected prefix; a repeated signature (the fixture renders
                // shared values adjacently) makes multiple k match, and any
                // guess can drop or double-count rows. Mirrors
                // scaleWindowOffset's uniqueness requirement in the old
                // traverse.
                val maxK = minOf(window.size, collected.size)
                val matches =
                    (1..maxK).filter { k ->
                        collected.subList(collected.size - k, collected.size) == window.subList(0, k)
                    }
                // Ambiguity (multiple matching k) retries under the SAME
                // bounded budget as the no-overlap path instead of erroring
                // immediately: it arises when a viewport boundary lands
                // inside a run of identical signatures, and a different
                // scroll offset moves that boundary and usually resolves it,
                // while the fixture's real render is session-major ascending
                // (adjacent amounts differ), so ambiguity is a
                // low-probability event, not a structural one. One shared
                // counter keeps the total budget honest, and the loud error
                // remains the last resort — a k is never guessed silently.
                if (matches.size != 1) {
                    val reason = if (matches.isEmpty()) "traversal window did not overlap" else "ambiguous traversal window"
                    check(++retries <= 4) { reason }
                    // D-217 round 11: a no-overlap window means rows were
                    // SKIPPED — the missed rows lie ABOVE the window, so the
                    // old back-0.2-then-forward-0.3 nudge moved net forward,
                    // the wrong direction for a gap. A single backward 0.3
                    // scroll restores the overlap with the stale tail; the
                    // normal forward stride then re-covers the gap.
                    scroll(forward = false, fraction = 0.3f)
                    continue
                }
                collected.addAll(window.subList(matches.single(), window.size))
            }
            retries = 0
            // The container-scoped walk can blind out whole groups; the
            // targeted findNode (cache walk -> root refresh -> forced cache
            // reset) probes the anchor row through a different path every
            // iteration and reads its signature through the same walk's
            // result, so the anchor has three independent chances per step.
            if (!sawAnchor) {
                findNode { node ->
                    node.isVisibleToUser && signatureAmount(node) == anchor.removeSuffix("|")
                }?.let {
                    sawAnchor = true
                }
            }
            val top = candidateNodes().firstOrNull()?.let { bounds(it).toString() + signatureOrNull(it) } ?: ""
            // D-217 round 9: 0.25 was the only fraction that had ever moved
            // this list on device (evidence6, round-4-era walk at 0.25: the
            // first-row amount advanced across all steps), while the 0.8
            // gestures of rounds 5-7 left rows frozen (evidence14: rows stay
            // 10-11 across every step, three consecutive cache-reset samples
            // read an identical first row). The mechanism why 0.8 fails is
            // unknown; the evidence is what fixes the parameter.
            // D-217 round 11: halved to 0.12 (~1.5 rows/gesture). Evidence17
            // (round 10) collected 117 of 125: the a11y tree lags the
            // gestures by ~5 samples, so at ~3 rows/gesture the tree can
            // advance ~15 rows between samples — more than the ~10-11-row
            // window — and a sample then skips rows entirely, a loss the
            // unique-overlap merge can never recover. At ~1.5 rows/gesture
            // the ~5-gesture lag advance is ~7-8 rows, under the window, so
            // no window can skip rows.
            scroll(forward = true, fraction = 0.12f)
            val after = candidateNodes().firstOrNull()?.let { bounds(it).toString() + signatureOrNull(it) } ?: ""
            // Bottom verdict — D-217 round 10: movement is decided by
            // COLLECTION GROWTH, not the first-row comparison alone. The
            // first-row bounds+signature comparison is unreliable because
            // the client tree lags the gestures: the tree's first row can
            // read identical for many consecutive samples while the list is
            // actually moving (evidence16, round-9 run: stationaryCount
            // climbed 1->7 across scans 8-14 while collected grew 12->28
            // over the same scans — the merge kept appending new rows, so
            // the list provably moved, yet the 8-sample first-row verdict
            // was about to fire). The unique-overlap merge appends exactly
            // when new rows appear, so collected growth is the
            // authoritative movement signal: a sample counts toward the
            // stationary verdict ONLY when the first row is unchanged AND
            // the collection did not grow during the step, and any growth
            // resets the counter no matter what the first-row comparison
            // says. With growth absorbing the tree-lag plateaus, 5
            // consecutive no-growth samples — each unchanged sample still
            // followed by a forced refresh and another scroll attempt —
            // mean the viewport is truly parked at the bottom.
            val growth = collected.size - sizeBefore
            val firstRowUnchanged = top.isNotEmpty() && top == after
            // D-217 walk diagnostics (revert before PR if not wanted);
            // round 11 adds first/last window signatures (truncated) so a
            // post-mortem can see window jumps directly.
            val firstSig = window.firstOrNull()?.take(24) ?: "none"
            val lastSig = window.lastOrNull()?.take(24) ?: "none"
            Log.i(
                "ULScaleWalk",
                "collect scan scans=$scans rows=${window.size} collected=${collected.size} growth=$growth " +
                    "stationaryCount=$stationaryCount first=$firstSig last=$lastSig",
            )
            if (firstRowUnchanged) {
                // The tree lags the gestures: force a fresh tree before the
                // next sample so later reads are not stale.
                runCatching { resetAutomationCache() }
            }
            if (firstRowUnchanged && growth == 0) {
                stationaryCount++
                if (stationaryCount >= 5) break
            } else {
                stationaryCount = 0
            }
        }
        check(sawAnchor) { "traversal never reached the unique-session first row" }
        // A stationary viewport or the safety cap ends the walk; if either
        // fires before every expected row is collected, fail loudly here with
        // the real cause instead of returning a short list that only surfaces
        // later as an opaque multiset mismatch (D-217 review).
        check(collected.size == expectedRows) { "traversal incomplete (collected=${collected.size} expected=$expectedRows scans=$scans)" }
        return collected
    }

    private fun amountText(amount: Long): String = (amount / 100).toString() + "." + (amount % 100).toString().padStart(2, '0') + " CNY"

    private fun signatureAmount(node: AccessibilityNodeInfo): String? = Regex("[0-9]+\\.[0-9]{2} CNY").find(labels(node).joinToString("\n"))?.value

    /**
     * D-217: scrolls backward in small steps until the top of the list is
     * stationary or the given marker text becomes visible; the small fraction
     * keeps each step inside the client tree's refresh window (a full-viewport
     * fling outruns it — the D-203 family blindness).
     *
     * D-217 round 15 (evidence27): the client tree lags the gestures by ~5
     * samples, so a single unchanged before/after comparison is not evidence
     * of the top — it is a lag plateau (observed: this walk exited after one
     * step with stationary=true while the list was still mid-scroll, and the
     * caller's marker await timed out 180s later). Ported the edge() round-14
     * verdict: require eight consecutive unchanged samples — each carrying a
     * forced cache reset and a bounded marker proof — before declaring the
     * walk stationary; a movement resumption resets the count.
     */
    fun seekBackToTop(marker: String = "最近批量结果") {
        var previous = ""
        var steps = 0
        // D-217 walk diagnostics (revert before PR if not wanted)
        var exitedStationary = false
        var stationaryCount = 0
        while (steps++ < 40) {
            if (has(marker, prefix = true)) {
                // D-217 walk diagnostics (revert before PR if not wanted)
                Log.i("ULScaleWalk", "seekReturn marker=${has(marker, prefix = true)} steps=$steps stationary=$exitedStationary")
                return
            }
            val current = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { it.text?.toString().orEmpty() }
            // D-217 walk diagnostics (revert before PR if not wanted)
            if (steps % 10 == 0) Log.i("ULScaleWalk", "seek step=$steps texts=${current.take(80)}")
            scroll(forward = false, fraction = 0.25f)
            val after = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { it.text?.toString().orEmpty() }
            if (current.isNotEmpty() && current == after) {
                stationaryCount++
                runCatching { resetAutomationCache() }
                // Stationary viewport: one final bounded wait for the marker
                // (each has() carries findNode's rate-limited miss reset).
                // D-217 round 21 (closure review): sleep between proofs so
                // consecutive has() reads sample the tree over time instead
                // of back-to-back.
                var proofs = 0
                while (!has(marker, prefix = true) && proofs++ < 3) {
                    SystemClock.sleep(150)
                }
                if (has(marker, prefix = true)) {
                    // D-217 walk diagnostics (revert before PR if not wanted)
                    Log.i("ULScaleWalk", "seekReturn marker=${has(marker, prefix = true)} steps=$steps stationary=$exitedStationary")
                    return
                }
                if (stationaryCount >= 8) {
                    // D-217 walk diagnostics (revert before PR if not wanted)
                    exitedStationary = true
                    Log.i("ULScaleWalk", "seekReturn marker=${has(marker, prefix = true)} steps=$steps stationary=$exitedStationary")
                    return
                }
            } else {
                stationaryCount = 0
            }
            previous = after
        }
        // D-217 walk diagnostics (revert before PR if not wanted)
        Log.i("ULScaleWalk", "seekReturn marker=${has(marker, prefix = true)} steps=$steps stationary=$exitedStationary")
    }

    fun traverse(expected: List<ScaleRow>): Int {
        check(expected.isNotEmpty())
        edge(last = false)
        var observed = 0
        var previousStart = 0
        var previous = emptyList<String>()
        val signatures = expected.map { it.signature }
        var retries = 0
        while (observed < expected.size) {
            tick()
            val window = candidateNodes().map(::signature)
            if (window.isEmpty()) {
                check(observed == 0)
                scroll(fraction = 0.3f)
                continue
            }
            val start = scaleWindowOffset(signatures, window, previousStart, observed)
            if (start == null || (window == previous && observed > 0)) {
                check(++retries <= 4) { "ambiguous, skipped or stationary candidate window" }
                scroll(forward = false, fraction = 0.2f)
                scroll(fraction = 0.3f)
                continue
            }
            check(start + window.size > observed) { "candidate traversal made no progress" }
            observed = start + window.size
            previousStart = start
            previous = window
            retries = 0
            if (observed < expected.size) scroll()
        }
        check(previous.last() == signatures.last())
        return observed
    }
}
