package com.unifiedledger.android

import android.os.SystemClock
import android.util.Log

/**
 * D-203 startup timing trace surface for the Android composition root. One line per event on
 * `logcat -s ULStartup`; the elapsed stamp is taken here (androidMain, SystemClock.elapsedRealtime)
 * so the shared commonMain seams only carry event names. Events carry stage names, booleans,
 * counts and durations ONLY — never ledger ids, row data, absolute paths; failures are reported
 * by exception class name. The emit itself never throws (logcat is best effort, diagnostics only).
 */
internal object StartupTrace {
    private const val TAG = "ULStartup"

    fun emit(event: String) {
        runCatching {
            Log.i(TAG, "${SystemClock.elapsedRealtime()} $event")
        }
    }
}
