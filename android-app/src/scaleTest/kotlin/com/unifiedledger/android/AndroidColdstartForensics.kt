package com.unifiedledger.android

/**
 * Bounded coldstart forensics contracts, shared by the JVM negatives and the
 * device chain. Only whitelisted startup/ready/error words, window metadata
 * and package-filtered thread frames may leave the device; no ledger content.
 */
object AndroidColdstartForensics {
    const val READY_PREFIX = "账本："
    const val EMPTY_LEDGER = "账本为空，还没有任何交易。"
    const val STARTING_PREFIX = "正在打开本地账本"
    const val ERROR_PREFIX = "无法打开本地账本"
    const val DEVICE_JSON = "android-scale-coldstart-forensics.json"
    const val DEVICE_PNG = "android-scale-coldstart-forensics.png"
    const val TARGET_PREFIX = "com.unifiedledger.android"
    const val MAIN_THREAD = "main"
    const val DISPATCHER_PREFIX = "DefaultDispatcher"
    const val SAMPLE_INTERVAL_MS = 5000L
    const val MAX_SAMPLES = 48
    const val MAX_WINDOWS_PER_SAMPLE = 8
    const val MAX_SIGNALS = 8
    const val CAPTURE_BUDGET_MS = 30000L
    const val MAX_THREADS = 24
    const val MAX_FRAMES = 40
    const val MAX_FRAME_CHARS = 200
    const val MAX_JSON_CHARS = 512000
    const val MAX_PNG_BYTES = 4000000

    data class WindowInfo(
        val id: Int,
        val type: Int,
        val active: Boolean,
        val focused: Boolean,
        val packageName: String?,
        val rootPackage: String?,
    )

    data class Sample(
        val atMs: Long,
        val windows: List<WindowInfo>,
        val readyOnFirstTargetRoot: Boolean,
        val readyOnAnyTargetRoot: Boolean,
        val targetRootVisibleNodes: Int,
        val signals: List<String>,
    )

    data class ThreadStack(val name: String, val frames: List<String>)

    data class Stacks(val threads: List<ThreadStack>, val truncated: Boolean)

    /** Keeps the first samples, refuses later ones and records the truncation. */
    class ColdstartSampleBuffer {
        private val collected = ArrayList<Sample>()
        var truncated = false
            private set

        val samples: List<Sample>
            get() = collected.toList()

        fun add(sample: Sample) {
            if (collected.size >= MAX_SAMPLES) {
                truncated = true
                return
            }
            collected += sample
        }
    }

    /** Whitelist only; unrelated labels never leave the device. */
    fun signalHits(texts: Collection<String>): List<String> {
        val hits = LinkedHashSet<String>()
        for (text in texts) {
            if (hits.size >= MAX_SIGNALS) break
            when {
                text.startsWith(READY_PREFIX) -> hits += READY_PREFIX
                text == EMPTY_LEDGER -> hits += EMPTY_LEDGER
                text.startsWith(STARTING_PREFIX) -> hits += STARTING_PREFIX
                text.startsWith(ERROR_PREFIX) -> hits += ERROR_PREFIX
            }
        }
        return hits.toList()
    }

    /**
     * Process-local stacks only: the main thread, coroutine dispatcher workers
     * and any thread with target-package frames. Frame text is bounded; the
     * truncation flag records dropped threads.
     */
    fun filterStacks(entries: List<Pair<String, Array<StackTraceElement>>>): Stacks {
        val threads = ArrayList<ThreadStack>()
        var truncated = false
        for ((name, frames) in entries) {
            val relevant = name == MAIN_THREAD ||
                name.startsWith(DISPATCHER_PREFIX) ||
                frames.any { it.className.startsWith(TARGET_PREFIX) }
            if (!relevant) continue
            if (threads.size >= MAX_THREADS) {
                truncated = true
                break
            }
            threads += ThreadStack(name, frames.take(MAX_FRAMES).map { it.toString().take(MAX_FRAME_CHARS) })
        }
        return Stacks(threads, truncated)
    }

    fun jsonOversized(chars: Int): Boolean = chars > MAX_JSON_CHARS
}
