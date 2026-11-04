package com.unifiedledger.ui

import android.os.SystemClock
import android.util.Log

/** D-203 Android actual: one `ULStartup` logcat line per event, stamped with elapsedRealtime. */
internal actual object StartupTrace {
    private const val TAG = "ULStartup"

    actual fun emit(event: String) {
        runCatching {
            Log.i(TAG, "${SystemClock.elapsedRealtime()} $event")
        }
    }

    /**
     * D-269 item 7 (DEF-EXPORT-OOM): the trace-carrying variant. `Log.w(tag, msg, throwable)`
     * appends the full stack, which is what makes an OOM raised inside a `catch (Throwable)` block
     * attributable after the fact. Logging stays best effort and never throws.
     */
    actual fun emitFailure(
        event: String,
        failure: Throwable,
    ) {
        runCatching {
            Log.w(TAG, "${SystemClock.elapsedRealtime()} $event", failure)
        }
    }
}
