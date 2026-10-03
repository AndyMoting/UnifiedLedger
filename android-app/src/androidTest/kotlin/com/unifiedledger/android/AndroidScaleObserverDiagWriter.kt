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
 * captures the decisive per-reset facts -- the target-package window ids, whether
 * `has("账本：")` sees the ready title, and a bounded sample of the visible texts
 * -- so the next maximum round can attribute the observation path instead of
 * inferring it from 5s-spaced samples.
 *
 * Bounds and honesty rules mirror the D-202 forensics: at most
 * [AndroidScaleObserverDiag.MAX_RECORDS] records (an exceeded cap sets
 * `truncated`), at most [AndroidScaleObserverDiag.MAX_TEXT_SAMPLES] texts of at
 * most [AndroidScaleObserverDiag.MAX_TEXT_CHARS] characters each, and one
 * `AtomicFile` write of a fixed name. The size guard measures UTF-8 bytes (the
 * bytes that actually land in the file) and really drops trailing records,
 * flagging `oversized` and the drop count.
 *
 * A write failure is swallowed -- this file is supplementary diagnostics and
 * must never change the wait verdict or mask the original failure -- but it is
 * counted: a failed write cannot record itself, so the accumulated
 * `writeFailureCount`/`lastWriteError` ride along with the next successful
 * write. That is what separates "the reset never ran" from "every write failed"
 * when the host reads the file back.
 *
 * A reset or observation failure is recorded as its own degraded entry
 * (`resetFailed`/`observationFailed` with a bounded error note) instead of
 * escaping into the wait loop, so the file still tells what happened.
 */
internal class AndroidScaleObserverDiagWriter(
    instrumentation: Instrumentation,
    private val sha: String,
) {
    private val context = instrumentation.targetContext
    private val records = JSONArray()
    private var truncated = false
    private val failures = AndroidScaleObserverDiag.WriteFailures()

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
        } catch (failure: Throwable) {
            // A failed write leaves `records` intact for the next attempt and is
            // counted; it must not escape into the wait loop.
            failures.record(failure)
        }
    }

    /** Degraded record for a reset that failed: the diagnostic is not lost, it is flagged. */
    fun recordResetFailure(
        atMs: Long,
        failure: Throwable,
    ) {
        putFailure("resetFailed", "resetError", atMs, failure)
    }

    /** Degraded record for a reset whose observation failed after a successful reset. */
    fun recordObservationFailure(
        atMs: Long,
        failure: Throwable,
    ) {
        putFailure("observationFailed", "observationError", atMs, failure)
    }

    private fun putFailure(
        flag: String,
        errorField: String,
        atMs: Long,
        failure: Throwable,
    ) {
        try {
            if (!AndroidScaleObserverDiag.recordAllowed(records.length())) {
                truncated = true
                write()
                return
            }
            records.put(
                JSONObject()
                    .put("atMs", atMs)
                    .put(flag, true)
                    .put(errorField, AndroidScaleObserverDiag.errorText(failure)),
            )
            write()
        } catch (broken: Throwable) {
            failures.record(broken)
        }
    }

    private fun write() {
        val budget = AndroidScaleObserverDiag.PayloadBudget()
        var body = body(budget)
        while (budget.needsDrop(body.toString().toByteArray(Charsets.UTF_8).size) && records.length() > 0) {
            records.remove(records.length() - 1)
            budget.onRecordDropped()
            truncated = true
            body = body(budget)
        }
        if (budget.needsDrop(body.toString().toByteArray(Charsets.UTF_8).size)) {
            // Even an empty record list exceeds the budget (a pathological
            // future bound): still write the header, but mark the oversize
            // without pretending a record was dropped.
            budget.markOversized()
            truncated = true
            body = body(budget)
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

    private fun body(budget: AndroidScaleObserverDiag.PayloadBudget): JSONObject {
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
                .put("oversized", budget.oversized)
                .put("recordsDroppedForSize", budget.recordsDroppedForSize)
        // Part of the budgeted payload, so the size loop above accounts for it.
        if (failures.count > 0) {
            body.put("writeFailureCount", failures.count).put("lastWriteError", failures.lastError ?: JSONObject.NULL)
        }
        return body
    }

    private fun processName(): String = runCatching { Application.getProcessName() }.getOrNull() ?: context.packageName
}
