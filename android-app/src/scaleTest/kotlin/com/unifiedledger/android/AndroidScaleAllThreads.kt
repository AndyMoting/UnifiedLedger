package com.unifiedledger.android

/**
 * D-213 bounded unfiltered thread capture contract, shared by the JVM tests
 * and the stage forensics device chain.
 *
 * The D-202 stack filter ([AndroidColdstartForensics.filterStacks]) was
 * designed for coldstart observer forensics: it keeps only the main thread,
 * DefaultDispatcher workers and target-package-prefixed frames. Four maximum
 * rounds then died at the oracle's read-only observation (SQLITE_BUSY) while
 * the failure-instant stage forensics always showed all threads idle -- the
 * long-DB-op threads seen in logcat (tids 8572/8589) have unknown names and
 * were likely filtered out of every capture so far, so the filtered view
 * cannot show the SHARED-lock holder. The working hypothesis to discriminate
 * is a leaked reader (an open read transaction / unclosed cursor on a pooled
 * connection) holding SHARED forever and starving the oracle; the unfiltered
 * dump exposes the holder's park location directly. The dump stays bounded
 * (thread count and frames per thread) with explicit truncation honesty and
 * is name-sorted for a deterministic snapshot, mirroring the D-202 stack
 * sort; no ledger content is involved, only process-local thread names and
 * frames.
 */
internal object AndroidScaleAllThreads {
    const val MAX_ALL_THREADS = 64
    const val MAX_ALL_FRAMES_PER_THREAD = 48

    /** Outcome of one unfiltered capture: bounded threads plus truncation flags. */
    data class AllStacks(
        val threads: List<AndroidColdstartForensics.ThreadStack>,
        val threadsTruncated: Boolean,
        val framesTruncated: Boolean,
    )

    /**
     * Bounds every thread with no name or frame filtering, sorted by name for
     * determinism; dropped threads and dropped frames are recorded via the
     * flags instead of being hidden.
     */
    fun boundAllThreads(entries: List<Pair<String, List<String>>>): AllStacks {
        var threadsTruncated = false
        var framesTruncated = false
        val threads = ArrayList<AndroidColdstartForensics.ThreadStack>()
        for ((name, frames) in entries.sortedBy { it.first }) {
            if (threads.size >= MAX_ALL_THREADS) {
                threadsTruncated = true
                break
            }
            if (frames.size > MAX_ALL_FRAMES_PER_THREAD) {
                framesTruncated = true
            }
            threads += AndroidColdstartForensics.ThreadStack(name, frames.take(MAX_ALL_FRAMES_PER_THREAD))
        }
        return AllStacks(threads, threadsTruncated, framesTruncated)
    }
}
