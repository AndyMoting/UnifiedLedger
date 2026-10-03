package com.unifiedledger.android

/**
 * D-205 bounded observer-cache-reset policy, diagnostic bounds and the shared
 * record shape, extended by D-208 with the miss-triggered reset interval.
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
 * Binder disclosure (D-205 review P2-2): `UiAutomation.getServiceInfo()` and
 * `setServiceInfo()` are synchronous binder calls with no client-side timeout.
 * On a wedged accessibility connection they can block the wait thread for
 * longer than this interval; the client cannot bound that, so only the host
 * phase deadline (which kills the adb/instrumentation process) ends the wait.
 * The interval is therefore wide (30s, at most six resets inside the 180s
 * coldstart window -- enough to hit a stalled cache, few enough to keep the
 * exposure small), and the wait loop re-checks its deadline immediately before
 * each reset.
 *
 * Everything here is pure logic (bounds, policy, record shapes, failure
 * counters) so it can be shared between the device writer and the JVM tests;
 * the reset call itself needs a real instrumentation environment and stays
 * CI-owned.
 */
internal object AndroidScaleObserverDiag {
    const val DEVICE_JSON = "android-scale-observer-diag.json"
    const val SCHEMA = 1
    const val KIND = "observer-diag"
    const val PHASE = "chain"
    const val STAGE = "coldstart"

    /**
     * At most one forced reset per this interval while the predicate misses.
     * 30s keeps the synchronous-binder exposure small: at most six resets fit
     * the 180s coldstart wait, which still exercises a stalled cache.
     */
    const val CACHE_RESET_INTERVAL_MS = 30000L

    /**
     * D-208: at most one miss-triggered forced reset per this interval. The
     * 825ms `saf_import` failure of run 37130347905 (frame-buffer screenshot
     * showed the import screen fully rendered while the in-process UiAutomation
     * still answered from the previous screen) needs a reset that can fire
     * inside a business-stage read path, so the interval is short -- but the
     * synchronous-binder exposure is bounded the same way as the 30s periodic
     * interval: at most one miss reset per 5s, sharing one stamp with the
     * periodic reset so the two cannot compound.
     */
    const val MISS_RESET_INTERVAL_MS = 5000L

    /** Records and text samples are capped; the cap never drops silently. */
    const val MAX_RECORDS = 200
    const val MAX_TEXT_SAMPLES = 5
    const val MAX_TEXT_CHARS = 120
    const val MAX_ERROR_CHARS = 200

    /**
     * Byte budget for one AtomicFile write. The record caps already bound the
     * payload, so this is a safety valve: when it trips, trailing records are
     * really dropped and the drop is flagged, never hidden.
     */
    const val MAX_JSON_BYTES = 256000

    data class Entry(
        val atMs: Long,
        /** Window ids of target-package windows (AccessibilityWindowInfo.getId()). */
        val targetWindowIds: List<Int>,
        val hasReadyTitle: Boolean,
        val visibleTexts: List<String>,
    )

    fun recordAllowed(existing: Int): Boolean = existing < MAX_RECORDS

    /** Visible-text sample: first [MAX_TEXT_SAMPLES] entries, each capped at [MAX_TEXT_CHARS]. */
    fun textSamples(texts: List<String>): List<String> = texts.take(MAX_TEXT_SAMPLES).map { it.take(MAX_TEXT_CHARS) }

    /** Bounded, single-line failure note shared by the reset and writer failure records. */
    fun errorText(failure: Throwable): String = (failure.javaClass.simpleName + ": " + (failure.message ?: "no message")).take(MAX_ERROR_CHARS)

    /** Bounded failure note for a payload that is not a Throwable (e.g. a null observation). */
    fun errorText(reason: String): String = reason.take(MAX_ERROR_CHARS)

    /**
     * Device-side write failures. A failed write cannot record itself, so the
     * count and the last error ride along with the next successful write; that
     * is what tells "never reset" apart from "every write failed".
     */
    class WriteFailures {
        var count: Int = 0
            private set
        var lastError: String? = null
            private set

        fun record(failure: Throwable) {
            count++
            lastError = errorText(failure)
        }
    }

    /**
     * Byte-budget bookkeeping for one payload build: the verdict is by UTF-8
     * byte length (the size that actually lands in the file), and every real
     * drop is counted.
     */
    class PayloadBudget(
        private val maxBytes: Int = MAX_JSON_BYTES,
    ) {
        var oversized: Boolean = false
            private set
        var recordsDroppedForSize: Int = 0
            private set

        fun needsDrop(bytes: Int): Boolean = bytes > maxBytes

        fun onRecordDropped() {
            oversized = true
            recordsDroppedForSize++
        }

        /** Oversize with nothing left to drop (e.g. a pathological future bound). */
        fun markOversized() {
            oversized = true
        }
    }
}

/** True when at least [AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS] passed since the last reset. */
internal fun scaleCacheResetDue(
    nowMs: Long,
    lastResetMs: Long,
): Boolean = nowMs - lastResetMs >= AndroidScaleObserverDiag.CACHE_RESET_INTERVAL_MS

/**
 * D-208 miss-triggered counterpart of [scaleCacheResetDue]: true when at least
 * [AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS] passed since the last
 * reset of any kind (both intervals read the same shared stamp).
 */
internal fun missResetDue(
    nowMs: Long,
    lastResetMs: Long,
): Boolean = nowMs - lastResetMs >= AndroidScaleObserverDiag.MISS_RESET_INTERVAL_MS
