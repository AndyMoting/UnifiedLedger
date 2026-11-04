package com.unifiedledger.android

/*
 * DEF-EXPORT-OOM (D-269 item 7): the pure gates of the opt-in ART heap-dump seam. This file
 * deliberately imports NOTHING from `android.*`, so both decisions are unit-testable on the JVM
 * (the `runtimeNotReadySubmission` / `dispatchToMainThread` extraction pattern) while the effect
 * (`Debug.dumpHprofData`) stays in `BackupHeapCapture`, which cannot be JVM-tested.
 *
 * The gates are split in two so the production cost is zero:
 * - [heapCaptureEnabled] is resolved ONCE at wiring time from build flags plus the operator
 *   sentinel. When it is false the adapter is constructed with no hook at all, so a production
 *   build pays nothing per chunk;
 * - [heapCaptureDue] is evaluated per chunk, only on a hook that already passed the first gate.
 */

/**
 * Gate 1: the capture may exist at all. True only for a debuggable build whose operator created the
 * opt-in sentinel. A release build has `FLAG_DEBUGGABLE` clear and can therefore never capture,
 * whatever else is true.
 */
internal fun heapCaptureEnabled(
    debuggable: Boolean,
    optedIn: Boolean,
): Boolean = debuggable && optedIn

/**
 * Gate 2: heap use has reached the high-water mark, so a dump is worth its cost. The registered
 * failure reported `target footprint 98824992` against `growth limit 201326592` (~49%), so the
 * [thresholdBytes] bar sits below the observed pre-failure usage and above an idle export.
 */
internal fun heapCaptureDue(
    usedBytes: Long,
    limitBytes: Long,
): Boolean = limitBytes > 0L && usedBytes >= thresholdBytes(limitBytes)

/** Two fifths of the heap limit; small enough to fire before the 128 MiB request fails. */
internal fun thresholdBytes(limitBytes: Long): Long = limitBytes / HEAP_CAPTURE_THRESHOLD_DIVISOR * HEAP_CAPTURE_THRESHOLD_NUMERATOR

/** The numerator of the two-fifths heap high-water mark. */
internal const val HEAP_CAPTURE_THRESHOLD_NUMERATOR: Long = 2

/** The denominator of the two-fifths heap high-water mark. */
internal const val HEAP_CAPTURE_THRESHOLD_DIVISOR: Long = 5
