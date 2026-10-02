package com.unifiedledger.android

import android.app.Application
import android.app.Instrumentation
import android.os.Process
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * D-205 bounded observer-cache-reset diagnostic.
 *
 * The coldstart wait path calls [record] after every forced client-cache reset
 * (`AndroidScaleUi.resetAutomationCache`, a `UiAutomation.setServiceInfo`
 * replacement that clears the client AccessibilityCache once). Each record
 * captures the decisive per-reset facts -- how many target-package roots the
 * window list holds, whether `has("账本：")` sees the ready title, and a bounded
 * sample of the visible texts -- so the next maximum round can attribute the
 * observation path instead of inferring it from 5s-spaced samples.
 *
 * Bounds and honesty rules mirror the D-202 forensics: at most
 * [AndroidScaleObserverDiag.MAX_RECORDS] records (an exceeded cap sets
 * `truncated`), at most [AndroidScaleObserverDiag.MAX_TEXT_SAMPLES] texts of at
 * most [AndroidScaleObserverDiag.MAX_TEXT_CHARS] characters each, one
 * `AtomicFile` write of a fixed name. A write failure is swallowed: this file
 * is supplementary diagnostics and must never change the wait verdict or mask
 * the original failure. The JSON is bounded by the caps and carries
 * `truncated`/`oversized` flags instead of dropping data silently.
 */
internal class AndroidScaleObserverDiagWriter(
    instrumentation: Instrumentation,
    private val sha: String,
) {
    private val context = instrumentation.targetContext
    private val records = JSONArray()
    private var truncated = false

    fun record(entry: AndroidScaleObserverDiag.Entry) {
        try {
            if (!AndroidScaleObserverDiag.recordAllowed(records.length())) {
                truncated = true
                write()
                return
            }
            records.put(
                JSONObject()
                    .put("atMs", entry.atMs)
                    .put("targetWindowIds", JSONArray(entry.targetWindowIds))
                    .put("hasReadyTitle", entry.hasReadyTitle)
                    .put("visibleTexts", JSONArray(AndroidScaleObserverDiag.textSamples(entry.visibleTexts))),
            )
            write()
        } catch (_: Throwable) {
            // Supplementary diagnostics only: a write failure must not escape
            // into the wait loop or change the stage verdict.
        }
    }

    private fun write() {
        val body =
            JSONObject()
                .put("schema", AndroidScaleObserverDiag.SCHEMA)
                .put("kind", AndroidScaleObserverDiag.KIND)
                .put("sha", sha)
                .put("phase", AndroidScaleObserverDiag.PHASE)
                .put("stage", AndroidScaleObserverDiag.STAGE)
                .put("pid", Process.myPid())
                .put("processName", processName())
                .put("resetIntervalMs", AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS)
                .put("recordCap", AndroidScaleObserverDiag.MAX_RECORDS)
                .put("records", records)
                .put("truncated", truncated)
        if (AndroidScaleObserverDiag.jsonOversized(body.toString().length)) {
            body.put("oversized", true)
        }
        val target = AtomicFile(File(context.filesDir, AndroidScaleObserverDiag.DEVICE_JSON))
        val output = target.startWrite()
        try {
            output.write(body.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Throwable) {
            target.failWrite(output)
            throw failure
        }
    }

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrNull() ?: context.packageName
}
