package com.unifiedledger.android

/*
 * DEF-EXPORT-OOM (D-269 item 7): the pure gates of the opt-in ART heap-dump seam. This file
 * deliberately imports NOTHING from `android.*`, so the decisions are unit-testable on the JVM
 * (the `runtimeNotReadySubmission` / `dispatchToMainThread` extraction pattern) while the effect
 * (`Debug.dumpHprofData`) stays in `BackupHeapCapture`, which cannot be JVM-tested.
 *
 * The two gates are NOT both resolved at wiring time:
 * - [heapCaptureEnabled] is resolved ONCE at wiring time from build flags plus the operator
 *   sentinel. When it is false the adapter is constructed with no hook at all.
 * - [heapCaptureDue] is evaluated per chunk, on a hook that already passed the first gate.
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
 * Gate 2: it is worth dumping now. Both conditions must hold:
 *
 * - heap use is at or above [thresholdBytes] (45% of the limit), and
 * - the export has already observed at least [HEAP_CAPTURE_MIN_CHUNKS] bounded reads.
 *
 * WHY THE CHUNK FLOOR EXISTS (D-269 item 7 review, OOM-5). An absolute bar alone does NOT defer the
 * dump: the A06 run shows `118MB/142MB` in use BEFORE the export starts, which is already 0.586 of
 * the 201,326,592 limit, so any bar at 0.45-0.49 fires on the FIRST read of pass 1 and the dump
 * predates the request under investigation by ~88 MB of streaming. The floor pushes the sample into
 * the body of pass 1, where the exporting frames are established and the heap has absorbed the
 * export's own growth. [HEAP_CAPTURE_MIN_CHUNKS] reads corresponds to the floor's MiB at the frozen
 * 64 KiB chunk size.
 *
 * The absolute bar stays because ART's `target footprint` (98,824,992 at failure) and its GC log
 * `used` figures are different counters measuring different things; neither is a precise predictor,
 * so the bar is a coarse "heap is busy" signal and the chunk floor supplies the timing.
 */
internal fun heapCaptureDue(
    usedBytes: Long,
    limitBytes: Long,
    chunksObserved: Long,
): Boolean = limitBytes > 0L && chunksObserved >= HEAP_CAPTURE_MIN_CHUNKS && usedBytes >= thresholdBytes(limitBytes)

/** The 45% heap bar: low enough to be reached, high enough to mean "the export is the pressure". */
internal fun thresholdBytes(limitBytes: Long): Long = limitBytes / HEAP_CAPTURE_THRESHOLD_DIVISOR * HEAP_CAPTURE_THRESHOLD_NUMERATOR

/**
 * Bounded reads observed before the first capture is allowed. At the frozen 64 KiB chunk this is
 * 16 MiB of streaming, which lands the dump well inside pass 1 instead of on its first read.
 */
internal const val HEAP_CAPTURE_MIN_CHUNKS: Long = 256

/** The numerator of the 45% heap bar. */
internal const val HEAP_CAPTURE_THRESHOLD_NUMERATOR: Long = 45

/** The denominator of the 45% heap bar. */
internal const val HEAP_CAPTURE_THRESHOLD_DIVISOR: Long = 100
