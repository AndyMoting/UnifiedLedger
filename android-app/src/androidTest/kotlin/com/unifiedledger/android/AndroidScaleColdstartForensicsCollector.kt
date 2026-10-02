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
 * has already expired there and any tick would immediately rethrow. Every
 * collection step is individually guarded; a forensics failure is recorded and
 * never masks or replaces the original failure.
 */
internal class AndroidScaleColdstartForensicsCollector(
    private val instrumentation: Instrumentation,
    private val ui: AndroidScaleUi,
    private val sha: String,
) {
    private val context = instrumentation.targetContext
    private val started = SystemClock.elapsedRealtime()
    private val buffer = AndroidColdstartForensics.ColdstartSampleBuffer()
    private var lastSampleAt = -1L
    private var sampleError: String? = null

    /** Called from the coldstart await loop: throttled, exception-swallowing, never ticks. */
    fun sample() {
        try {
            val now = SystemClock.elapsedRealtime()
            if (lastSampleAt >= 0 && now - lastSampleAt < AndroidColdstartForensics.SAMPLE_INTERVAL_MS) return
            lastSampleAt = now
            buffer.add(observation(now - started))
        } catch (failure: Throwable) {
            sampleError = note(failure)
        }
    }

    /** Best-effort capture within a fixed budget; the caller rethrows the original failure unchanged. */
    fun capture(failure: Throwable) {
        val captureStarted = SystemClock.elapsedRealtime()
        try {
            write(report(failure, captureStarted))
        } catch (broken: Throwable) {
            // Nowhere left to record a failed write; the sample error and the
            // build error are already inside the payload when they occurred.
        }
    }

    private fun report(
        failure: Throwable,
        captureStarted: Long,
    ): JSONObject {
        val budgetEnd = captureStarted + AndroidColdstartForensics.CAPTURE_BUDGET_MS
        val finalObservation = observation(captureStarted - started)
        val stacks =
            AndroidColdstartForensics.filterStacks(
                Thread.getAllStackTraces().entries.map { it.key.name to it.value }.sortedBy { it.first },
            )
        val screenshot = screenshot(budgetEnd)
        val body =
            JSONObject()
                .put("schema", 1)
                .put("kind", "coldstart-forensics")
                .put("sha", sha)
                .put("phase", "chain")
                .put("stage", "coldstart")
                .put("pid", Process.myPid())
                .put("processName", processName())
                .put("startedElapsedMs", started)
                .put("sampleIntervalMs", AndroidColdstartForensics.SAMPLE_INTERVAL_MS)
                .put("sampleCap", AndroidColdstartForensics.MAX_SAMPLES)
                .put("samples", samplesJson())
                .put("samplesTruncated", buffer.truncated)
                .put("sampleError", sampleError ?: JSONObject.NULL)
                .put(
                    "failure",
                    JSONObject()
                        .put("errorType", failure.javaClass.name)
                        .put("message", failure.message ?: JSONObject.NULL)
                        .put("waitedMs", captureStarted - started)
                        .put("windows", windowsJson(finalObservation.windows))
                        .put("readyOnFirstTargetRoot", finalObservation.readyOnFirstTargetRoot)
                        .put("readyOnAnyTargetRoot", finalObservation.readyOnAnyTargetRoot)
                        .put("targetRootVisibleNodes", finalObservation.targetRootVisibleNodes)
                        .put("signals", JSONArray(finalObservation.signals))
                        .put("threads", threadsJson(stacks.threads))
                        .put("threadsTruncated", stacks.truncated)
                        .put("captureMs", SystemClock.elapsedRealtime() - captureStarted)
                        .put("screenshot", screenshot),
                )
        // Hard file-size boundary: when the guard trips, only the bulk sample
        // array is dropped and the drop is recorded, never hidden.
        if (AndroidColdstartForensics.jsonOversized(body.toString().length)) {
            body.put("samples", JSONArray()).put("samplesTruncated", true).put("samplesDroppedForSize", true)
        }
        return body
    }

    private fun observation(atMs: Long): AndroidColdstartForensics.Sample {
        val roots = ui.targetRoots()
        val firstTexts = ui.visibleTexts(roots.firstOrNull())
        val anyReady =
            roots.any { root -> ui.visibleTexts(root).any { it.startsWith(AndroidColdstartForensics.READY_PREFIX) } }
        return AndroidColdstartForensics.Sample(
            atMs = atMs,
            windows = ui.windowInfos(),
            readyOnFirstTargetRoot = firstTexts.any { it.startsWith(AndroidColdstartForensics.READY_PREFIX) },
            readyOnAnyTargetRoot = anyReady,
            targetRootVisibleNodes = ui.visibleNodeCount(roots.firstOrNull()),
            signals = AndroidColdstartForensics.signalHits(firstTexts + roots.drop(1).flatMap(ui::visibleTexts)),
        )
    }

    private fun samplesJson(): JSONArray {
        val samples = JSONArray()
        for (sample in buffer.samples) {
            samples.put(
                JSONObject()
                    .put("atMs", sample.atMs)
                    .put("windows", windowsJson(sample.windows))
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
                    .put("package", window.packageName ?: JSONObject.NULL)
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

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrNull() ?: context.packageName

    private fun note(failure: Throwable): String = (failure.javaClass.simpleName + ": " + (failure.message ?: "no message")).take(200)
}
