package com.unifiedledger.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.util.Log
import java.io.File

/**
 * DEF-EXPORT-OOM (D-269 item 7) instrumentation seam: an opt-in ART java heap dump taken from a
 * bounded-read progress hook.
 *
 * COMPLEMENTARY, NOT PRIMARY: D-269's original plan was this dump. The FIRST attribution path is
 * now `BackupExportUseCase.writeContainer`'s catch block, which reports the swallowed Throwable
 * (`BackupExport.kt`), so the allocating Java frames reach logcat with no hook, no gate, no dump and
 * no second device round trip. The dump still adds what a stack trace cannot: the live heap's owning
 * classes and retained sizes, which is what distinguishes "the platform asked for 128 MiB" from
 * "some retained structure made it necessary".
 *
 * WHY A PROGRESS HOOK AND NOT A FAILURE HOOK: the request fails INSIDE `writeContainer`, which maps
 * it to the typed `CONTAINER_WRITE_FAILED`. No Android-side frame sees that failure, so a dump can
 * only be taken while the export is still running and heap use is high.
 *
 * WHAT IS SAMPLED (explicitly broader than export): the hook is installed on the `LedgerFileSystem`
 * instance that the composition root also hands to `AndroidRestoreWiring`, so it samples EVERY
 * bounded read on that instance — the restore preflight's container reads and its digest/migration
 * passes included, not just the export. That is the intended behaviour (see [backupHeapCaptureHook],
 * which is wired once at the composition root); it is stated here so a reader never assumes the
 * sampling is export-only.
 *
 * STORAGE: both the sentinel and the dump live in [Context.getNoBackupFilesDir], NOT `filesDir`.
 * The manifest declares no `android:allowBackup="false"` and no `dataExtractionRules`, so Android's
 * Auto Backup default is ON and `filesDir` is eligible for cloud and device-to-device transfer. A
 * whole-heap dump contains ledger rows, restore-path container plaintext and derived key material —
 * exactly what AGENTS.md's Non-Negotiable Boundaries forbid moving off the device. `noBackupFilesDir`
 * is excluded from both Auto Backup and D2D transfer by the platform.
 *
 * OPERATOR RECIPE (`no_backup` is the platform's directory name for `getNoBackupFilesDir`):
 *
 *     adb shell run-as com.unifiedledger.android touch no_backup/def-export-oom.optin
 *     adb exec-out run-as com.unifiedledger.android cat no_backup/def-export-oom.hprof > dump.hprof
 *
 * Run the >= 10k export between the two commands. `exec-out` is required: a plain `adb shell cat`
 * goes through a pty that translates CRLF and corrupts a binary dump. Remove the sentinel to disarm.
 *
 * Two gates, neither of which resolves "all at wiring time": [heapCaptureEnabled] is resolved once
 * by [resolveHook] (a build that does not opt in wires no hook at all); [heapCaptureDue] is
 * evaluated per chunk on the hook that exists, against a heap bar AND a minimum chunk count so the
 * dump lands inside pass 1 rather than on its first read. Per chunk the cost is one increment, one
 * hook dispatch and one `Runtime` sample; wiring costs one flags read and one `File.exists()`. The
 * code itself ships in the release APK (this is `src/main`, not a debug-only source set — the
 * repo's debug-only precedent is `android-app/src/debug/`), so what is zero in release is the
 * CAPTURE, not the bytecode. [resolveHook] returns null there, so the adapter holds no hook.
 *
 * This object never throws.
 */
internal object BackupHeapCapture {
    private const val TAG = "ULBackupHeap"

    /** Operator opt-in sentinel; its presence in a debuggable build enables the capture. */
    private const val OPT_IN_FILE = "def-export-oom.optin"

    /** The dump target, beside the sentinel in the no-backup directory. */
    private const val DUMP_FILE = "def-export-oom.hprof"

    /**
     * How many times a FAILED dump is retried. A single failure would otherwise be retried on every
     * subsequent chunk of a long export; three attempts covers a transient OOM while keeping the
     * worst case bounded and reported.
     */
    private const val MAX_ATTEMPTS: Int = 3

    @Volatile
    private var captured = false

    /** Dump attempts so far, bounding the retry a failed dump may incur on later chunks. */
    @Volatile
    private var attempts = 0

    /** Bounded reads observed so far; drives [HEAP_CAPTURE_MIN_CHUNKS]. */
    @Volatile
    private var chunksObserved: Long = 0L

    /**
     * Gate 1 at wiring time: returns the per-chunk hook only when this build/operator opted in, and
     * null otherwise so a build that did not opt in carries no hook at all.
     */
    fun resolveHook(context: Context?): (() -> Unit)? {
        val resolved = context ?: return null
        val enabled =
            runCatching {
                heapCaptureEnabled(
                    debuggable = (resolved.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                    optedIn = File(resolved.noBackupFilesDir, OPT_IN_FILE).exists(),
                )
            }.getOrDefault(false)
        if (!enabled) return null
        return { onBoundedRead(resolved) }
    }

    /** Gate 2, per chunk: dump once when heap use and stream progress both justify it. Never throws. */
    private fun onBoundedRead(context: Context) {
        if (captured || attempts >= MAX_ATTEMPTS) return
        runCatching {
            // Count BEFORE the gate: the count is what defers the dump past pass 1's first read.
            chunksObserved += 1L
            val runtime = Runtime.getRuntime()
            val limit = runtime.maxMemory()
            val used = runtime.totalMemory() - runtime.freeMemory()
            if (!heapCaptureDue(usedBytes = used, limitBytes = limit, chunksObserved = chunksObserved)) return
            val target = File(context.noBackupFilesDir, DUMP_FILE)
            // The FAILURE branch is logged too: without it a failed dump is indistinguishable from
            // "never armed" on the device. No payload, path or secret in either sentence.
            attempts += 1
            val attempt = attempts
            val outcome = runCatching { Debug.dumpHprofData(target.absolutePath) }
            outcome.onSuccess {
                // Set only AFTER the dump landed, so a failed dump is retried on a later chunk
                // (an OOM at high water is the expected failure, and it may clear).
                captured = true
                Log.i(TAG, "heap dump captured $DUMP_FILE bytes=${target.length()} chunks=$chunksObserved used=$used limit=$limit")
            }
            outcome.onFailure { failure ->
                Log.w(
                    TAG,
                    "heap dump failed $DUMP_FILE attempt=$attempt/${MAX_ATTEMPTS} " +
                        "causeClass=${failure::class.simpleName} chunks=$chunksObserved used=$used limit=$limit",
                )
            }
        }
    }
}
