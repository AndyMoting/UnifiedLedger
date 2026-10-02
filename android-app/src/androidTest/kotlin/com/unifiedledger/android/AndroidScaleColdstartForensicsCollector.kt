package com.unifiedledger.android

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded coldstart forensics captured before instrumentation exits.
 *
 * Sampling runs only on the coldstart launch wait path; the final capture runs
 * only after the first failure and always before rethrow, so the passing path
 * never writes a forensics file. Capture never calls tick(): the stage deadline
 * has already expired there and any tick would immediately rethrow. Every stage
 * (observation, stacks, screenshot, write) is budget-checked and individually
 * guarded: a forensics failure degrades to a minimal fallback payload and never
 * masks or replaces the original failure.
 */
internal class AndroidScaleColdstartForensicsCollector(
    private val instrumentation: Instrumentation,
    private val ui: AndroidScaleUi,
    private val sha: String,
) {
    private val context = instrumentation.targetContext
    private val started = SystemClock.elapsedRealtime()
    private val buffer = AndroidColdstartForensics.ColdstartSampleBuffer()
    private val sampleErrors = mutableListOf<String>()
    private var sampleErrorsTruncated = false
    private var lastSampleAt = -1L

    /** Called from the coldstart await loop: throttled, exception-swallowing, never ticks. */
    fun sample() {
        try {
            val now = SystemClock.elapsedRealtime()
            if (lastSampleAt >= 0 && now - lastSampleAt < AndroidColdstartForensics.SAMPLE_INTERVAL_MS) return
            lastSampleAt = now
            buffer.add(observation(now - started))
        } catch (failure: Throwable) {
            // Earliest error signatures survive; only the excess is dropped.
            if (sampleErrors.size >= AndroidColdstartForensics.MAX_SAMPLE_ERRORS) {
                sampleErrorsTruncated = true
            } else {
                sampleErrors += note(failure)
            }
        }
    }

    /** Best-effort capture within a fixed budget; the caller rethrows the original failure unchanged. */
    fun capture(failure: Throwable) {
        val captureStarted = SystemClock.elapsedRealtime()
        val budgetEnd = captureStarted + AndroidColdstartForensics.CAPTURE_BUDGET_MS
        try {
            val body = report(failure, captureStarted, budgetEnd)
            if (SystemClock.elapsedRealtime() < budgetEnd) {
                write(body)
            } else {
                // The only remaining step would overrun the budget; record the
                // bounded minimal payload instead of staying silent.
                write(minimal(failure, captureStarted, "capture budget exhausted"))
            }
        } catch (broken: Throwable) {
            try {
                write(minimal(failure, captureStarted, note(broken)))
            } catch (_: Throwable) {
                // The fallback itself failed; there is nowhere left to record
                // without risking the original failure path.
            }
        }
    }

    private fun report(
        failure: Throwable,
        captureStarted: Long,
        budgetEnd: Long,
    ): JSONObject {
        val skipped = mutableListOf<String>()
        val finalObservation =
            if (SystemClock.elapsedRealtime() < budgetEnd) {
                observation(captureStarted - started)
            } else {
                skipped += "observation"
                emptyObservation(captureStarted - started)
            }
        val stacks =
            if (SystemClock.elapsedRealtime() < budgetEnd) {
                AndroidColdstartForensics.filterStacks(
                    Thread.getAllStackTraces().entries.map { it.key.name to it.value }.sortedBy { it.first },
                )
            } else {
                skipped += "filterStacks"
                AndroidColdstartForensics.Stacks(emptyList(), false)
            }
        val screenshot =
            if (SystemClock.elapsedRealtime() < budgetEnd) screenshot(budgetEnd) else JSONObject().put("skipped", "capture budget exhausted")
        val body =
            header()
                .put("sampleIntervalMs", AndroidColdstartForensics.SAMPLE_INTERVAL_MS)
                .put("sampleCap", AndroidColdstartForensics.MAX_SAMPLES)
                .put("samples", samplesJson())
                .put("samplesTruncated", buffer.truncated)
                .put("sampleErrors", JSONArray(sampleErrors))
                .put("sampleErrorsTruncated", sampleErrorsTruncated)
                .put(
                    "failure",
                    JSONObject()
                        .put("errorType", failure.javaClass.name)
                        .put("message", failure.message ?: JSONObject.NULL)
                        .put("waitedMs", captureStarted - started)
                        .put("windows", windowsJson(finalObservation.windows))
                        .put("windowsTruncated", finalObservation.windowsTruncated)
                        .put("readyOnFirstTargetRoot", finalObservation.readyOnFirstTargetRoot)
                        .put("readyOnAnyTargetRoot", finalObservation.readyOnAnyTargetRoot)
                        .put("targetRootVisibleNodes", finalObservation.targetRootVisibleNodes)
                        .put("signals", JSONArray(finalObservation.signals))
                        .put("threads", threadsJson(stacks.threads))
                        .put("threadsTruncated", stacks.truncated)
                        .put("skipped", JSONArray(skipped))
                        .put("screenshot", screenshot),
                )
        // Hard file-size boundary: when the guard trips, only the bulk sample
        // array is dropped and the drop is recorded, never hidden. captureMs is
        // finalized after this re-serialization decision.
        if (AndroidColdstartForensics.jsonOversized(body.toString().length)) {
            body.put("samples", JSONArray()).put("samplesTruncated", true).put("samplesDroppedForSize", true)
        }
        body.getJSONObject("failure").put("captureMs", SystemClock.elapsedRealtime() - captureStarted)
        return body
    }

    /** Minimal always-cheap payload: identity, original failure, sample errors, truncation flags. */
    private fun minimal(
        failure: Throwable,
        captureStarted: Long,
        captureError: String,
    ): JSONObject =
        header()
            .put("samples", JSONArray())
            .put("samplesTruncated", true)
            .put("sampleErrors", JSONArray(sampleErrors))
            .put("sampleErrorsTruncated", sampleErrorsTruncated)
            .put("fallback", true)
            .put(
                "failure",
                JSONObject()
                    .put("errorType", failure.javaClass.name)
                    .put("message", failure.message ?: JSONObject.NULL)
                    .put("waitedMs", captureStarted - started)
                    .put("captureMs", SystemClock.elapsedRealtime() - captureStarted)
                    .put("captureError", captureError),
            )

    private fun observation(atMs: Long): AndroidColdstartForensics.Sample {
        val roots = ui.targetRoots()
        val firstTexts = ui.visibleTexts(roots.firstOrNull())
        val anyReady =
            roots.any { root -> ui.visibleTexts(root).any { it.startsWith(AndroidColdstartForensics.READY_PREFIX) } }
        val windows = AndroidColdstartForensics.boundWindows(ui.windowInfos())
        return AndroidColdstartForensics.Sample(
            atMs = atMs,
            windows = windows.windows,
            windowsTruncated = windows.truncated,
            readyOnFirstTargetRoot = firstTexts.any { it.startsWith(AndroidColdstartForensics.READY_PREFIX) },
            readyOnAnyTargetRoot = anyReady,
            targetRootVisibleNodes = ui.visibleNodeCount(roots.firstOrNull()),
            signals = AndroidColdstartForensics.signalHits(firstTexts + roots.drop(1).flatMap(ui::visibleTexts)),
        )
    }

    private fun emptyObservation(atMs: Long): AndroidColdstartForensics.Sample =
        AndroidColdstartForensics.Sample(atMs, emptyList(), false, false, false, 0, emptyList())

    private fun samplesJson(): JSONArray {
        val samples = JSONArray()
        for (sample in buffer.samples) {
            samples.put(
                JSONObject()
                    .put("atMs", sample.atMs)
                    .put("windows", windowsJson(sample.windows))
                    .put("windowsTruncated", sample.windowsTruncated)
                    .put("readyOnFirstTargetRoot", sample.readyOnFirstTargetRoot)
                    .put("readyOnAnyTargetRoot", sample.readyOnAnyTargetRoot)
                    .put("targetRootVisibleNodes", sample.targetRootVisibleNodes)
                    .put("signals", JSONArray(sample.signals)),
            )
        }
        return samples
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
    private fun screenshot(budgetEnd: Long): JSONObject {
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
            File(context.filesDir, AndroidColdstartForensics.DEVICE_PNG).writeBytes(bytes)
            JSONObject().put("file", AndroidColdstartForensics.DEVICE_PNG).put("bytes", bytes.size)
        } catch (broken: Throwable) {
            JSONObject().put("error", note(broken))
        }
    }

    private fun write(report: JSONObject) {
        val target = AtomicFile(File(context.filesDir, AndroidColdstartForensics.DEVICE_JSON))
        val output = target.startWrite()
        try {
            output.write(report.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    private fun header(): JSONObject =
        JSONObject()
            .put("schema", 1)
            .put("kind", "coldstart-forensics")
            .put("sha", sha)
            .put("phase", "chain")
            .put("stage", "coldstart")
            .put("pid", Process.myPid())
            .put("processName", processName())
            .put("startedElapsedMs", started)

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrNull() ?: context.packageName

    private fun note(failure: Throwable): String = (failure.javaClass.simpleName + ": " + (failure.message ?: "no message")).take(200)
}
