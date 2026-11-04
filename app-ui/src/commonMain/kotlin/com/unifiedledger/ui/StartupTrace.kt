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

    /**
     * Reports a swallowed failure WITH its stack trace. D-269 item 7 (DEF-EXPORT-OOM): the backup
     * export's `writeContainer` catches `Throwable` so the user gets a typed result instead of a
     * crash, which also discarded the only record of the allocating Java frames. Android appends the
     * trace to logcat; desktop drops it (no desktop trace surface). Callers pass event text only —
     * never payload, paths or secrets — and the trace itself carries class/method/file/line.
     */
    fun emitFailure(
        event: String,
        failure: Throwable,
    )
}
