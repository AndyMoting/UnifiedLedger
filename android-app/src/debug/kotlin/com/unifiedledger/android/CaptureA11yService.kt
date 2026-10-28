package com.unifiedledger.android

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only recorder for the A-03 TalkBack walkthrough.
 *
 * TalkBack speaks the node it moves accessibility focus to, so recording each
 * TYPE_VIEW_ACCESSIBILITY_FOCUSED event in order captures the walkthrough's focus order, plus the
 * node's own text and contentDescription - the base of what TalkBack reads. Framework-derived
 * decoration (role words, "4 of 4", state suffixes) is NOT in these fields; the synthesiser log is
 * the authority for the spoken sentence. Both apps can hold an accessibility service at once, so
 * this runs beside TalkBack without changing what TalkBack does.
 *
 * The capture is the focus stream, not the synthesiser: TalkBack's own decorations ("按钮",
 * "可双击激活") and the rendered audio are outside what an accessibility service can observe.
 */
class CaptureA11yService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type =
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "a11y-focus"
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> "input-focus"
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
                else -> return
            }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            write("$type | ${event.packageName} | ${event.className}")
            return
        }
        val node = event.source ?: return
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val className = node.className?.toString().orEmpty()
        val flags =
            "clickable=${node.isClickable} enabled=${node.isEnabled} scrollable=${node.isScrollable}"
        write("$type | $className | $flags | text=[$text] | desc=[$description]")
    }

    override fun onInterrupt() = Unit

    /**
     * Debug hook: ask TalkBack to focus a node by text, so a node that linear navigation skips can
     * still be checked for read-aloud reachability. Debug builds only.
     */
    private val focusReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // The evidence file is the point of this service, so only the shell (adb broadcast)
                // and this app may drive it, and the query must stay a single printable token.
                val sender = sentFromUid
                if (sender != Process.SHELL_UID && sender != Process.myUid()) {
                    write("focus-request | rejected-sender | uid=" + sender)
                    return
                }
                val query = intent.getStringExtra(EXTRA_QUERY).orEmpty().trim()
                if (query.any { it.code < 0x20 || it == '|' }) {
                    write("focus-request | rejected-query")
                    return
                }
                if (query.isBlank()) {
                    write("focus-request | <blank> | not-found")
                    return
                }
                val root = rootInActiveWindow
                if (root == null) {
                    write("focus-request | $query | no-window")
                    return
                }
                val target = findFirst(root, query)
                if (target == null) {
                    write("focus-request | $query | not-found")
                    return
                }
                // A plain text node can refuse the action; walk up the parents and take the first
                // ancestor that accepts it. level > 0 means an ancestor took the focus, so what gets
                // announced is that ancestor's merged text, not necessarily this node's own text.
                var candidate: AccessibilityNodeInfo? = target
                var ok = false
                var level = 0
                while (candidate != null && !ok) {
                    ok = candidate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    if (!ok) {
                        candidate = candidate.parent
                        level++
                    }
                }
                write("focus-request | $query | ok=$ok level=$level text=[${target.text}] desc=[${target.contentDescription}]")
            }
        }

    private fun findFirst(
        node: AccessibilityNodeInfo,
        query: String,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_WALK_DEPTH) return null
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if (text.contains(query) || description.contains(query)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findFirst(child, query, depth + 1)
            if (found != null) return found
        }
        return null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val registered =
            runCatching {
                registerReceiver(focusReceiver, IntentFilter(ACTION_FOCUS_REQUEST), Context.RECEIVER_EXPORTED)
            }.isSuccess
        write("service-connected focus-hook=" + if (registered) "ready" else "unavailable")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(focusReceiver) }
        super.onDestroy()
    }

    private fun write(line: String) {
        val stamped = "${stamp()} | $line"
        Log.i(TAG, stamped)
        runCatching {
            val directory = getExternalFilesDir(null) ?: return
            File(directory, FILE_NAME).appendText(stamped + "\n")
        }
    }

    private val stampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun stamp(): String = stampFormat.format(Date())

    private companion object {
        const val TAG = "UL_A11Y_CAPTURE"
        const val FILE_NAME = "a11y-capture.log"
        const val ACTION_FOCUS_REQUEST = "com.unifiedledger.android.FOCUS_REQUEST"
        const val EXTRA_QUERY = "query"
        const val MAX_WALK_DEPTH = 60
    }
}
