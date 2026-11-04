package com.unifiedledger.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.util.Log
import java.io.File

/**
 * DEF-EXPORT-OOM (D-269 item 7) instrumentation seam: an opt-in ART java heap dump taken from the
 * export's bounded-read progress hook, so the next device run produces the artifact D-269 asks for.
 *
 * WHY A PROGRESS HOOK AND NOT A FAILURE HOOK: the fixed ~128 MiB request fails INSIDE
 * `BackupExportUseCase.writeContainer`, which catches `Throwable` and maps it to the typed
 * `CONTAINER_WRITE_FAILED` (`BackupExport.kt:291-292`). No Android-side frame sees that failure and
 * the process survives (no FATAL, hence no framework stack trace). The dump therefore has to be
 * taken while the export is still running and heap use is high: the export reads the snapshot
 * through `AndroidLedgerFileSystem.openRead`, so sampling there lands the dump on the export thread
 * with the requesting frames live.
 *
 * TWO INDEPENDENT GATES (never production), decided by [heapCaptureEnabled] and [heapCaptureDue]:
 * 1. the running app must be debuggable (`FLAG_DEBUGGABLE` is clear in every release build) AND the
 *    operator must have created the sentinel in the app-private `files/` directory:
 *        adb shell run-as com.unifiedledger.android touch files/def-export-oom.optin
 * 2. heap use must be above two fifths of the runtime limit.
 *
 * Gate 1 is resolved ONCE by [resolveHook], so a build that does not opt in wires no hook at all and
 * pays nothing per chunk. The dump lands beside the sentinel as `files/def-export-oom.hprof` and is
 * retrieved with `run-as ... cat` (no root, no external storage, nothing leaves the app-private
 * directory on its own). This object never throws and dumps at most once per process.
 */
internal object BackupHeapCapture {
    private const val TAG = "ULBackupHeap"

    /** Operator opt-in sentinel; its presence in a debuggable build enables the capture. */
    private const val OPT_IN_FILE = "def-export-oom.optin"

    /** The dump target, beside the sentinel in the app-private `files/` directory. */
    private const val DUMP_FILE = "def-export-oom.hprof"

    @Volatile
    private var captured = false

    /** Diagnostics only: the last emitted sentence, so a device run can be confirmed from logcat. */
    @Volatile
    var lastStatus: String? = null
        private set

    /** True once this process actually wrote a dump (the device-run assertion). */
    val didCapture: Boolean get() = captured

    /**
     * Gate 1 at wiring time: returns the per-chunk hook only when this build/operator opted in, and
     * null otherwise so the adapter carries no diagnostic path in production.
     */
    fun resolveHook(context: Context?): (() -> Unit)? {
        val resolved = context ?: return null
        val enabled =
            runCatching {
                heapCaptureEnabled(
                    debuggable = (resolved.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                    optedIn = File(resolved.filesDir, OPT_IN_FILE).exists(),
                )
            }.getOrDefault(false)
        if (!enabled) return null
        return { onBoundedRead(resolved) }
    }

    /** Gate 2, per chunk: dump once when heap use is high enough. Never throws. */
    private fun onBoundedRead(context: Context) {
        if (captured) return
        runCatching {
            val runtime = Runtime.getRuntime()
            val limit = runtime.maxMemory()
            val used = runtime.totalMemory() - runtime.freeMemory()
            if (!heapCaptureDue(usedBytes = used, limitBytes = limit)) return
            captured = true
            val target = File(context.filesDir, DUMP_FILE)
            Debug.dumpHprofData(target.absolutePath)
            // Name, byte count and the sampled figures only: never a path, never payload.
            lastStatus = "heap dump captured $DUMP_FILE bytes=${target.length()} used=$used limit=$limit"
            Log.i(TAG, lastStatus ?: DUMP_FILE)
        }
    }
}
