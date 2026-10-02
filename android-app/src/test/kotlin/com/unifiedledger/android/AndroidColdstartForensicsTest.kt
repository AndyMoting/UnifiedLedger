package com.unifiedledger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM negatives for the bounded coldstart forensics contracts (no device). */
class AndroidColdstartForensicsTest {
    private fun stack(name: String, vararg frames: StackTraceElement) = name to arrayOf(*frames)

    @Test
    fun signalHitsKeepOnlyWhitelistedStartupWords() {
        val texts =
            listOf("账本：ledger-local-test", "底部导航噪音", "正在打开本地账本…", "无法打开本地账本（本地数据库不可用）", "账本为空，还没有任何交易。")
        assertEquals(
            listOf("账本：", "正在打开本地账本", "无法打开本地账本", "账本为空，还没有任何交易。"),
            AndroidColdstartForensics.signalHits(texts),
        )
    }

    @Test
    fun signalHitsDropUnrelatedLabelsDeduplicateAndCapTheList() {
        val noise = List(20) { "标签$it" }
        assertEquals(emptyList(), AndroidColdstartForensics.signalHits(noise))
        val repeated = listOf("账本：a", "账本：b", "账本为空，还没有任何交易。", "账本：c")
        assertEquals(listOf("账本：", "账本为空，还没有任何交易。"), AndroidColdstartForensics.signalHits(repeated))
        // Every text maps to a fixed whitelist word, so the hit list is deduplicated;
        // the cap stays a hard bound even for arbitrarily large label sets.
        val excess = List(100) { index -> if (index % 2 == 0) "账本：$index" else "无法打开本地账本" }
        assertTrue(AndroidColdstartForensics.signalHits(excess).size <= AndroidColdstartForensics.MAX_SIGNALS)
    }

    @Test
    fun sampleBufferKeepsTheFirstSamplesAndFlagsTruncation() {
        val buffer = AndroidColdstartForensics.ColdstartSampleBuffer()
        fun sample(atMs: Long) = AndroidColdstartForensics.Sample(atMs, emptyList(), false, false, false, 0, emptyList())
        repeat(AndroidColdstartForensics.MAX_SAMPLES) { buffer.add(sample(it.toLong())) }
        assertFalse(buffer.truncated)
        buffer.add(sample(10000))
        assertTrue(buffer.truncated)
        assertEquals(AndroidColdstartForensics.MAX_SAMPLES, buffer.samples.size)
        assertEquals(0, buffer.samples.first().atMs)
        assertEquals((AndroidColdstartForensics.MAX_SAMPLES - 1).toLong(), buffer.samples.last().atMs)
    }

    @Test
    fun boundWindowsCapsAndFlagsTheTruncation() {
        val window = AndroidColdstartForensics.WindowInfo(1, 1, true, false, "com.unifiedledger.android", "com.unifiedledger.android")
        val within = AndroidColdstartForensics.boundWindows(List(AndroidColdstartForensics.MAX_WINDOWS_PER_SAMPLE) { window })
        assertFalse(within.truncated)
        assertEquals(AndroidColdstartForensics.MAX_WINDOWS_PER_SAMPLE, within.windows.size)
        val excess = AndroidColdstartForensics.boundWindows(List(AndroidColdstartForensics.MAX_WINDOWS_PER_SAMPLE + 1) { window })
        assertTrue(excess.truncated)
        assertEquals(AndroidColdstartForensics.MAX_WINDOWS_PER_SAMPLE, excess.windows.size)
    }

    @Test
    fun foreignThreadsAreDroppedAndTargetThreadsKept() {
        val entries =
            listOf(
                stack("FinalizerDaemon", StackTraceElement("java.lang.Daemon", "run", "Daemon.java", 1)),
                stack("main", StackTraceElement("com.unifiedledger.android.MainActivity", "onCreate", "MainActivity.kt", 10)),
                stack(
                    "DefaultDispatcher-worker-1",
                    StackTraceElement("kotlinx.coroutines.scheduling.CoroutineWorker", "run", "CoroutineWorker.kt", 3),
                ),
                stack("idle-pool", StackTraceElement("com.unifiedledger.android.Ledger", "open", "Ledger.kt", 7)),
            )
        val result = AndroidColdstartForensics.filterStacks(entries)
        assertEquals(listOf("main", "DefaultDispatcher-worker-1", "idle-pool"), result.threads.map { it.name })
        assertFalse(result.truncated)
    }

    @Test
    fun stackFramesAreCappedAndThreadTruncationFlagged() {
        val longFrame = StackTraceElement("com.unifiedledger.android.Ledger", "open", "Ledger.kt", 7)
        val result = AndroidColdstartForensics.filterStacks(listOf(stack("main", *Array(100) { longFrame })))
        assertEquals(AndroidColdstartForensics.MAX_FRAMES, result.threads.single().frames.size)
        assertTrue(result.threads.single().frames.all { it.length <= AndroidColdstartForensics.MAX_FRAME_CHARS })
        val excess =
            List(AndroidColdstartForensics.MAX_THREADS + 5) { index ->
                stack("main-$index", StackTraceElement("com.unifiedledger.android.T$index", "run", "T.kt", index))
            }
        val truncated = AndroidColdstartForensics.filterStacks(excess)
        assertEquals(AndroidColdstartForensics.MAX_THREADS, truncated.threads.size)
        assertTrue(truncated.truncated)
    }

    @Test
    fun jsonSizeGuardTriggersOnlyAboveTheCap() {
        assertFalse(AndroidColdstartForensics.jsonOversized(AndroidColdstartForensics.MAX_JSON_CHARS))
        assertTrue(AndroidColdstartForensics.jsonOversized(AndroidColdstartForensics.MAX_JSON_CHARS + 1))
    }
}
