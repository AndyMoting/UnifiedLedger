package com.unifiedledger.android

/**
 * D-205 bounded observer-cache-reset policy, diagnostic bounds and the shared
 * record shape.
 *
 * D-204 (eventTypes=TYPES_ALL_MASK plus a root-only refresh-on-miss rule) did
 * not repair the coldstart observation path: two maximum rounds kept reading
 * the 8-node loading tree for the whole 180s wait while ULStartup timing and
 * frame-buffer screenshots proved the product reached Ready in ~3s. The client
 * AccessibilityCache is cache-first with no expiry and only an event delivered
 * to this connection invalidates it, so a stalled delivery leaves every query
 * answered from the stale startup tree. `UiAutomation.setServiceInfo` replaces
 * the whole info and clears the client cache once
 * (UiAutomation.java:839-855, android-36.1), which makes a periodic re-assert
 * of the same info a programmable, client-side forced invalidation. Whether
 * the system-side delivery stall is the proven cause stays out of scope: this
 * is the client-side instrument, not a root-cause claim.
 *
 * Everything here is pure logic (bounds, policy, record shape) so it can be
 * shared between the device writer and the JVM tests; the reset call itself
 * needs a real instrumentation environment and stays CI-owned.
 */
internal object AndroidScaleObserverDiag {
    const val DEVICE_JSON = "android-scale-observer-diag.json"
    const val SCHEMA = 1
    const val KIND = "observer-diag"
    const val PHASE = "chain"
    const val STAGE = "coldstart"

    /** At most one forced reset per this interval while the predicate misses. */
    const val CACHE_RESET_INTERVAL_MS = 5000L

    /** Records and text samples are capped; the cap never drops silently. */
    const val MAX_RECORDS = 200
    const val MAX_TEXT_SAMPLES = 5
    const val MAX_TEXT_CHARS = 120
    const val MAX_JSON_CHARS = 256000

    data class Entry(
        val atMs: Long,
        /** Window ids of target-package windows (AccessibilityWindowInfo.getId()). */
        val targetWindowIds: List<Int>,
        val hasReadyTitle: Boolean,
        val visibleTexts: List<String>,
    )

    fun recordAllowed(existing: Int): Boolean = existing < MAX_RECORDS

    fun jsonOversized(chars: Int): Boolean = chars > MAX_JSON_CHARS

    /** Visible-text sample: first [MAX_TEXT_SAMPLES] entries, each capped at [MAX_TEXT_CHARS]. */
    fun textSamples(texts: List<String>): List<String> = texts.take(MAX_TEXT_SAMPLES).map { it.take(MAX_TEXT_CHARS) }
}

/** True when at least [AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS] passed since the last reset. */
internal fun scaleCacheResetDue(
    nowMs: Long,
    lastResetMs: Long,
): Boolean = nowMs - lastResetMs >= AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS
