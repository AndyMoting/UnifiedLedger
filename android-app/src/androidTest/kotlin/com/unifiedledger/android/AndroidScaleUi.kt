package com.unifiedledger.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
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
        val events = listOf(
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
            MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN },
        )
        events.forEach { event ->
            check(automation.injectInputEvent(event, true)) { "coordinate tap injection refused" }
            event.recycle()
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
        repeat(30000) {
            val current = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { it.text?.toString().orEmpty() }
            if (current == previous) {
                // D-216: the client tree can be momentarily blind at the edge
                // (observed once on the local channel — the top of the list was
                // visibly rendered while a single has() returned false); give
                // the proof three bounded attempts, each carrying findNode's
                // rate-limited miss reset, before declaring the edge unproven.
                var proven = false
                var proofs = 0
                while (!proven && proofs++ < 3) {
                    proven = has("进入批量确认", prefix = true) ||
                        has("确认整组标记") ||
                        has("最近批量结果", prefix = true) ||
                        has("刷新清单")
                }
                check(proven) { "stationary viewport is not a proven edge" }
                return
            }
            previous = current
            scroll(last, 0.8f)
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

    private fun signature(node: AccessibilityNodeInfo): String {
        val text = labels(node).joinToString("\n")
        val amount = Regex("[0-9]+\\.[0-9]{2} CNY").find(text)?.value ?: error("candidate amount absent")
        val meta = Regex("类型 ordinary_flow；发生 [^\n]+?；方向 out；状态 settled；重复 (?:无|[A-Z_]+)").find(text)?.value ?: error("candidate metadata absent")
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
     * identified by its unique spec-derived amount — the seed+1 amount is
     * unique to the first row of the unique session by fixture construction,
     * and rows do not render their session id (verified interactively) —
     * then opened through the same deterministic scan as [openCandidate].
     * Signature uniqueness is enforced by requiring exactly one visible row
     * to carry the amount.
     */
    fun openCandidateByAmount(amount: Long) {
        val target = amountText(amount)
        // The review screen can be parked anywhere between its "recent
        // imports" header and the candidate groups; any of these markers (or
        // the target amount itself) proves the review surface is live.
        await { has("刷新清单") || has("待确认——缺用户决策", prefix = true) || has(target) }
        // D-217: the target row (unique session, first ordinal, no duplicate
        // candidate) is classified PENDING_USER_DECISION, and that group is
        // the FIRST group of the review list (frozen display order). Locate
        // the group header on screen, then match the amount among the rows
        // rendered below it — no list-wide scanning, no reliance on scroll
        // physics. If the header or the row is not visible, scroll down one
        // page and retry; the group sits at the top of the list so a couple
        // of pages suffice.
        var pages = 0
        var sawHeader = false
        var sawTargetRow = false
        var clickAttempts = 0
        for (attempt in 0 until 2) {
            if (attempt > 0) runCatching { resetAutomationCache() }
            pages = 0
            // The candidate groups sit below the format section; scroll in
            // SMALL steps until the pending-decision group header is on
            // screen — a full-viewport seek overshoots the header between
            // has() polls (observed: the walk ended four groups down without
            // ever seeing the text). Scrolling up first is unnecessary: from
            // any parking position the downward walk reaches the header.
            var found = false
            var steps = 0
            while (steps++ < 40 && !found) {
                if (has("待确认——缺用户决策", prefix = true)) {
                    found = true
                    break
                }
                scroll(forward = true, fraction = 0.25f)
                if (has("待确认——缺用户决策", prefix = true)) found = true
            }
            check(found) { "pending-decision group header never became visible" }
            while (pages++ < 6) {
                val header = findNode { node -> node.isVisibleToUser && (node.text?.toString() ?: "").startsWith("待确认——缺用户决策") }
                if (header == null) {
                    scroll(forward = false, fraction = 0.9f)
                    continue
                }
                sawHeader = true
                val headerBottom = bounds(header).bottom
                val rows = candidateNodes().filter { bounds(it).top >= headerBottom }
                val matches = rows.filter { signatureAmount(it) == target }
                if (matches.isNotEmpty()) sawTargetRow = true
                val row = matches.singleOrNull()
                if (row != null) {
                    clickAttempts++
                    clickNode(row)
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
                    if (opened && has(target)) return
                    if (opened) {
                        click("返回")
                        await { has("刷新清单") }
                    }
                }
                scroll(forward = true, fraction = 0.7f)
            }
        }
        error("unique candidate not reached (amount=$target sawHeader=$sawHeader sawTargetRow=$sawTargetRow clickAttempts=$clickAttempts pages=$pages)")
    }

    /**
     * D-217: collect-only traversal. Walks the review list once — top, then a
     * single forward pass to the stationary bottom — recording every visible
     * row signature in encounter order, and returns the number of collected
     * rows. The list renders the product's class groups in query order, a
     * different domain from the spec's row order, so no in-chain alignment is
     * attempted; reopen() aligns the collected multiset against the oracle's
     * quiescent snapshot. The unique-session first row must appear (its
     * amount is spec-derived), anchoring the collection to the fixture.
     */
    fun collectTraversal(seed: Long): List<String> {
        // D-217: the previous stage may have left the app on the detail
        // screen (its back click can silently fail on a stale node); the
        // detail screen has no scrollable container, so edge() below would
        // die there. Return to the review list with a verified click, then
        // run the top walk.
        if (has("候选详情")) {
            clickUntil("返回", "刷新清单")
        }
        // D-217: do NOT rely on backward scrolling to reach the list top —
        // the scrollable container selection can pick a container that does
        // not move under the gesture (observed: backward loops terminated
        // while the viewport sat below the target row). Anchor like
        // openCandidateByAmount does: locate the PENDING group header (first
        // group of the list, the one the target row belongs to) with small
        // downward steps from wherever we are, then start collecting from
        // the header.
        var found = false
        var steps = 0
        while (steps++ < 40 && !found) {
            if (has("待确认——缺用户决策", prefix = true)) {
                found = true
                break
            }
            scroll(forward = true, fraction = 0.25f)
            if (has("待确认——缺用户决策", prefix = true)) found = true
        }
        check(found) { "pending-decision group header never became visible" }
        val seen = LinkedHashSet<String>()
        var scans = 0
        // The seed+1 amount is unique to the unique session's first row by
        // fixture construction (rows do not render session ids), so the amount
        // prefix alone is the anchor.
        val anchor = amountText(seed + 1L) + "|"
        var sawAnchor = false
        while (scans++ < 4000) {
            candidateNodes().forEach { seen += signature(it) }
            // The container-scoped walk can blind out whole groups; the
            // targeted findNode (cache walk -> root refresh -> forced cache
            // reset) probes the anchor row through a different path every
            // iteration and reads its signature through the same walk's
            // result, so the anchor has three independent chances per step.
            if (!sawAnchor) {
                findNode { node ->
                    node.isVisibleToUser && signatureAmount(node) == anchor.removeSuffix("|")
                }?.let {
                    seen += signature(it)
                    sawAnchor = true
                }
            }
            val top = candidateNodes().firstOrNull()?.let { bounds(it).toString() + signature(it) } ?: ""
            scroll(forward = true, fraction = 0.8f)
            val after = candidateNodes().firstOrNull()?.let { bounds(it).toString() + signature(it) } ?: ""
            if (top.isNotEmpty() && top == after) break
        }
        check(sawAnchor) { "traversal never reached the unique-session first row" }
        return seen.toList()
    }

    private fun amountText(amount: Long): String = (amount / 100).toString() + "." + (amount % 100).toString().padStart(2, '0') + " CNY"

    private fun signatureAmount(node: AccessibilityNodeInfo): String? = Regex("[0-9]+\\.[0-9]{2} CNY").find(labels(node).joinToString("\n"))?.value

    /**
     * D-217: scrolls backward in small steps until the top of the list is
     * stationary or the given marker text becomes visible; the small fraction
     * keeps each step inside the client tree's refresh window (a full-viewport
     * fling outruns it — the D-203 family blindness).
     */
    fun seekBackToTop(marker: String = "最近批量结果") {
        var previous = ""
        var steps = 0
        while (steps++ < 40) {
            if (has(marker, prefix = true)) return
            val current = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { it.text?.toString().orEmpty() }
            scroll(forward = false, fraction = 0.25f)
            val after = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { it.text?.toString().orEmpty() }
            if (current.isNotEmpty() && current == after) {
                // Stationary viewport: one final bounded wait for the marker
                // (each has() carries findNode's rate-limited miss reset).
                var proofs = 0
                while (!has(marker, prefix = true) && proofs++ < 3) { /* bounded retries */ }
                return
            }
            previous = after
        }
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
