package com.unifiedledger.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Fresh semantic nodes only; gesture geometry comes from the current scroll container. */
internal class AndroidScaleUi(
    private val instrumentation: Instrumentation,
    private val tick: () -> Unit,
) {
    private val automation get() = instrumentation.uiAutomation
    private val target = "com.unifiedledger.android"

    init {
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
    }

    fun launch(onPoll: (() -> Unit)? = null) {
        instrumentation.targetContext.startActivity(Intent.makeMainActivity(ComponentName(target, "$target.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await(onPoll = onPoll) { has("账本：", prefix = true) }
    }

    fun root(): AccessibilityNodeInfo? = automation.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == target }

    /** Every target-package root, not just the first one: multi-window ambiguity stays observable. */
    fun targetRoots(): List<AccessibilityNodeInfo> =
        automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == target }

    fun windowInfos(): List<AndroidColdstartForensics.WindowInfo> =
        automation.windows.take(AndroidColdstartForensics.MAX_WINDOWS_PER_SAMPLE).map { window ->
            AndroidColdstartForensics.WindowInfo(
                id = window.id,
                type = window.type,
                active = window.isActive,
                focused = window.isFocused,
                packageName = window.packageName?.toString(),
                rootPackage = window.root?.packageName?.toString(),
            )
        }

    fun visibleTexts(root: AccessibilityNodeInfo?): List<String> =
        nodes(root).filter { it.isVisibleToUser }.flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }

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

    fun has(
        text: String,
        prefix: Boolean = false,
    ): Boolean =
        nodes(root()).any { node ->
            node.isVisibleToUser && listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any { if (prefix) it.startsWith(text) else it == text }
        }

    // `onPoll` is a coldstart-only forensics hook: it runs after a passed tick,
    // must never call tick(), and stays null at every other call site.
    fun await(
        timeout: Long = 180000,
        onPoll: (() -> Unit)? = null,
        predicate: () -> Boolean,
    ) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            tick()
            onPoll?.invoke()
            if (predicate()) return
            SystemClock.sleep(150)
        }
        error("UI condition deadline exceeded")
    }

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
        error("semantic click refused")
    }

    fun click(
        text: String,
        prefix: Boolean = false,
    ) {
        await { has(text, prefix) }
        val node = nodes(root()).first { candidate -> candidate.isVisibleToUser && listOfNotNull(candidate.text?.toString(), candidate.contentDescription?.toString()).any { if (prefix) it.startsWith(text) else it == text } }
        clickNode(node)
    }

    fun scrollable(): AccessibilityNodeInfo = nodes(root()).filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { bounds(it).height() } ?: error("scroll container absent")

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
            val current = nodes(root()).filter { it.isVisibleToUser }.joinToString("|") { it.text.toString() }
            if (current == previous) {
                check(if (last) has("进入批量确认", prefix = true) || has("确认整组标记") || has("最近批量结果", prefix = true) else has("刷新清单")) { "stationary viewport is not a proven edge" }
                return
            }
            previous = current
            scroll(last, 0.8f)
        }
        error("edge traversal bound exceeded")
    }

    fun selectSafFixture() {
        seek("支付宝账单（CSV）", forward = false)
        val format = nodes(root()).first { it.text?.toString() == "支付宝账单（CSV）" }
        val picks = nodes(root()).filter { it.text?.toString() == "选择文件" && it.isVisibleToUser }
        clickNode(picks.minByOrNull { kotlin.math.abs(bounds(it).centerY() - bounds(format).centerY()) } ?: error("Alipay picker absent"))
        await {
            automation.windows.mapNotNull { it.root }.any { it.packageName?.toString()?.contains("documentsui") == true }
        }
        var navigated = false
        await {
            val picker = automation.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString()?.contains("documentsui") == true } ?: return@await false
            val entries = nodes(picker)
            val file = entries.firstOrNull { it.text?.toString() == "session-06.csv" && it.isVisibleToUser }
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
        return nodes(container)
            .filter { node ->
                node.isVisibleToUser &&
                    node.actionList.any { it.label?.toString() == "查看候选详情" } &&
                    bounds(node).let { it.height() > 0 && it.top >= viewport.top && it.bottom <= viewport.bottom }
            }.sortedBy { bounds(it).top }
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
        val container = scrollable()
        // This is detail navigation, not traversal coverage. A unique live signature is still mandatory.
        check(container.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id }) { "indexed detail navigation unavailable" }
        check(
            container.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, rank + 200)
                },
            ),
        )
        settle()
        repeat(80) {
            candidateNodes().firstOrNull { signature(it) == row.signature }?.let {
                clickNode(it)
                await { has("候选详情") }
                return
            }
            val window = candidateNodes().map(::signature)
            val first = window.firstOrNull()?.let { signature -> expected.indexOfFirst { it.signature == signature } }
            scroll(forward = first == null || first < rank, fraction = 0.3f)
        }
        error("unique candidate not reached")
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
