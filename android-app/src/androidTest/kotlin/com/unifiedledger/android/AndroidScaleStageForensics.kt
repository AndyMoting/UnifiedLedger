package com.unifiedledger.android

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Bounded stage-level forensics captured at the failure instant of any business
 * stage except coldstart (D-206, diagnostics only; coldstart keeps its own
 * richer D-202 capture with the sample buffer, whose device files must not be
 * overwritten). Motivated by maximum run 37118587515: coldstart passed but the
 * first-ever business stage (saf_import) failed in 524ms with
 * `IllegalStateException: scroll container absent`, and the failure-instant
 * screen was unknown because the host failure-ui.xml is captured only after
 * instrumentation exit.
 *
 * Capture runs only from the stage() failure branch before rethrow and never
 * on a passing path. It never calls tick(): the stage deadline bookkeeping is
 * already unwinding and any tick could rethrow a second failure. Every step is
 * budget-checked against [AndroidColdstartForensics.CAPTURE_BUDGET_MS] and
 * individually guarded; a step that fails degrades to a bounded note inside the
 * payload, a broken capture degrades to the minimal fallback payload, and if
 * even that write fails the capture gives up silently -- the original failure
 * always surfaces unchanged and no stage verdict ever depends on this file.
 *
 * The ui provider is a lambda so that lazily constructing the observer on a
 * stage that never touched it (for example replay) stays inside the same
 * guards instead of replacing the original failure at the call site. Stage
 * elapsed is deliberately not threaded here (no new failure-path state):
 * `waitedMs` records the capture window instead.
 *
 * No ledger content leaves the device: window metadata, bounded visible-text
 * samples ([AndroidScaleObserverDiag.MAX_TEXT_SAMPLES] entries of at most
 * [AndroidScaleObserverDiag.MAX_TEXT_CHARS] characters per target root) and
 * package-filtered thread frames only, with the same truncation honesty as the
 * coldstart forensics.
 */
internal object AndroidScaleStageForensics {
    const val DEVICE_JSON = "android-scale-stage-forensics.json"
    const val DEVICE_PNG = "android-scale-stage-forensics.png"
    const val MAX_TARGET_ROOTS = 4

    /** Best-effort capture within a fixed budget; the caller rethrows the original failure unchanged. */
    fun capture(
        instrumentation: Instrumentation,
        uiProvider: () -> AndroidScaleUi,
        sha: String,
        stage: String,
        failure: Throwable,
    ) {
        val captureStarted = SystemClock.elapsedRealtime()
        val budgetEnd = captureStarted + AndroidColdstartForensics.CAPTURE_BUDGET_MS
        try {
            val body = report(instrumentation, uiProvider, sha, stage, failure, captureStarted, budgetEnd)
            if (SystemClock.elapsedRealtime() < budgetEnd) {
                write(instrumentation, body)
            } else {
                // The only remaining step would overrun the budget; record the
                // bounded minimal payload instead of staying silent.
                write(instrumentation, minimal(instrumentation, sha, stage, failure, captureStarted, "capture budget exhausted"))
            }
        } catch (broken: Throwable) {
            try {
                write(instrumentation, minimal(instrumentation, sha, stage, failure, captureStarted, note(broken)))
            } catch (_: Throwable) {
                // The fallback itself failed; there is nowhere left to record
                // without risking the original failure path.
            }
        }
    }

    private fun report(
        instrumentation: Instrumentation,
        uiProvider: () -> AndroidScaleUi,
        sha: String,
        stage: String,
        failure: Throwable,
        captureStarted: Long,
        budgetEnd: Long,
    ): JSONObject {
        val ui = uiProvider()
        val skipped = mutableListOf<String>()
        val windows =
            if (SystemClock.elapsedRealtime() < budgetEnd) {
                AndroidColdstartForensics.boundWindows(ui.windowInfos())
            } else {
                skipped += "windows"
                AndroidColdstartForensics.Windows(emptyList(), false)
            }
        val roots =
            if (SystemClock.elapsedRealtime() < budgetEnd) ui.targetRoots() else {
                skipped += "targetRoots"
                emptyList()
            }
        val texts =
            if (SystemClock.elapsedRealtime() < budgetEnd) rootTexts(ui, roots) else {
                skipped += "texts"
                JSONObject().put("roots", JSONArray()).put("rootsTruncated", roots.size > MAX_TARGET_ROOTS)
            }
        val stacks =
            if (SystemClock.elapsedRealtime() < budgetEnd) {
                val stackTraces = Thread.getAllStackTraces()
                AndroidColdstartForensics.filterStacks(
                    stackTraces.entries.map { it.key.name to it.value }.sortedBy { it.first },
                )
            } else {
                skipped += "filterStacks"
                AndroidColdstartForensics.Stacks(emptyList(), false)
            }
        val screenshot =
            if (SystemClock.elapsedRealtime() < budgetEnd) screenshot(instrumentation, budgetEnd) else JSONObject().put("skipped", "capture budget exhausted")
        val body =
            header(instrumentation, sha, stage, captureStarted)
                .put(
                    "failure",
                    JSONObject()
                        .put("errorType", failure.javaClass.name)
                        .put("message", failure.message ?: JSONObject.NULL)
                        .put("waitedMs", SystemClock.elapsedRealtime() - captureStarted)
                        .put("windows", windowsJson(windows.windows))
                        .put("windowsTruncated", windows.truncated)
                        .put("texts", texts)
                        .put("targetRootVisibleNodes", ui.visibleNodeCount(roots.firstOrNull()))
                        .put("threads", threadsJson(stacks.threads))
                        .put("threadsTruncated", stacks.truncated)
                        .put("skipped", JSONArray(skipped))
                        .put("screenshot", screenshot),
                )
        // Hard file-size boundary: when the guard trips, only the bulk thread
        // array is dropped and the drop is recorded, never hidden. captureMs is
        // finalized after this re-serialization decision.
        if (AndroidColdstartForensics.jsonOversized(body.toString().length)) {
            body.getJSONObject("failure").put("threads", JSONArray()).put("threadsTruncated", true).put("threadsDroppedForSize", true)
        }
        body.getJSONObject("failure").put("captureMs", SystemClock.elapsedRealtime() - captureStarted)
        return body
    }

    /** Minimal always-cheap payload: identity, original failure and the capture error. */
    private fun minimal(
        instrumentation: Instrumentation,
        sha: String,
        stage: String,
        failure: Throwable,
        captureStarted: Long,
        captureError: String,
    ): JSONObject =
        header(instrumentation, sha, stage, captureStarted)
            .put("fallback", true)
            .put(
                "failure",
                JSONObject()
                    .put("errorType", failure.javaClass.name)
                    .put("message", failure.message ?: JSONObject.NULL)
                    .put("captureMs", SystemClock.elapsedRealtime() - captureStarted)
                    .put("captureError", captureError),
            )

    /** Bounded visible-text samples per target root; the truncation flag records dropped roots. */
    private fun rootTexts(ui: AndroidScaleUi, roots: List<AccessibilityNodeInfo>): JSONObject {
        val array = JSONArray()
        for (root in roots.take(MAX_TARGET_ROOTS)) {
            val texts = ui.visibleTexts(root)
            array.put(
                JSONObject()
                    .put("texts", JSONArray(AndroidScaleObserverDiag.textSamples(texts)))
                    .put("textsTruncated", texts.size > AndroidScaleObserverDiag.MAX_TEXT_SAMPLES),
            )
        }
        return JSONObject().put("roots", array).put("rootsTruncated", roots.size > MAX_TARGET_ROOTS)
    }

    private fun windowsJson(windows: List<AndroidColdstartForensics.WindowInfo>): JSONArray {
        val array = JSONArray()
        for (window in windows) {
            array.put(
                JSONObject()
                    .put("id", window.id)
                    .put("type", window.type)
                    .put("active", window.active)
                    .put("focused", window.focused)
                    .put("rootPackage", window.rootPackage ?: JSONObject.NULL),
            )
        }
        return array
    }

    private fun threadsJson(threads: List<AndroidColdstartForensics.ThreadStack>): JSONArray {
        val array = JSONArray()
        for (thread in threads) {
            array.put(JSONObject().put("name", thread.name).put("frames", JSONArray(thread.frames)))
        }
        return array
    }

    /** Same UiAutomation connection only: no uiautomator process, no reconnect. */
    private fun screenshot(instrumentation: Instrumentation, budgetEnd: Long): JSONObject {
        if (SystemClock.elapsedRealtime() >= budgetEnd) {
            return JSONObject().put("skipped", "capture budget exhausted")
        }
        return try {
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return JSONObject().put("error", "screenshot unavailable")
            val bytes =
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
            bitmap.recycle()
            if (bytes.size > AndroidColdstartForensics.MAX_PNG_BYTES) {
                return JSONObject().put("error", "screenshot oversized").put("bytes", bytes.size)
            }
            File(instrumentation.targetContext.filesDir, DEVICE_PNG).writeBytes(bytes)
            JSONObject().put("file", DEVICE_PNG).put("bytes", bytes.size)
        } catch (broken: Throwable) {
            JSONObject().put("error", note(broken))
        }
    }

    private fun write(instrumentation: Instrumentation, report: JSONObject) {
        val target = AtomicFile(File(instrumentation.targetContext.filesDir, DEVICE_JSON))
        val output = target.startWrite()
        try {
            output.write(report.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    private fun header(
        instrumentation: Instrumentation,
        sha: String,
        stage: String,
        captureStarted: Long,
    ): JSONObject =
        JSONObject()
            .put("schema", 1)
            .put("kind", "stage-forensics")
            .put("sha", sha)
            .put("phase", InstrumentationRegistry.getArguments().getString("scalePhase") ?: JSONObject.NULL)
            .put("stage", stage)
            .put("pid", Process.myPid())
            .put("processName", processName(instrumentation))
            .put("startedElapsedMs", captureStarted)

    private fun processName(instrumentation: Instrumentation): String =
        runCatching { Application.getProcessName() }.getOrNull() ?: instrumentation.targetContext.packageName

    private fun note(failure: Throwable): String = (failure.javaClass.simpleName + ": " + (failure.message ?: "no message")).take(200)
}
