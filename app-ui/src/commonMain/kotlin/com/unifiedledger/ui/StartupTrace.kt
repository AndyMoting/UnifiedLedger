package com.unifiedledger.ui

/**
 * D-203 startup timing trace seam for the shared UI sources. commonMain emits event names only;
 * the platform actuals decide whether and how they surface (Android: one `ULStartup` logcat line
 * stamped with SystemClock.elapsedRealtime; desktop: no-op). Events carry stage names, booleans,
 * counts and durations ONLY — never ledger ids, row data, absolute paths; failures are reported
 * by exception class name. The emit never throws and never changes product behavior.
 */
internal expect object StartupTrace {
    fun emit(event: String)
}
