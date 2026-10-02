package com.unifiedledger.data

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
}
