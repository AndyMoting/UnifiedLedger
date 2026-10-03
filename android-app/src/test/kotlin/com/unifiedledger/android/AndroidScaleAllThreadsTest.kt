package com.unifiedledger.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidScaleAllThreadsTest {
    @Test
    fun underCapEntriesPassThroughNameSortedAndUntruncated() {
        // D-213: below the bounds every thread and frame survives, and the
        // snapshot is name-sorted regardless of the caller's order.
        val bounded =
            AndroidScaleAllThreads.boundAllThreads(
                listOf(
                    "Worker-2" to listOf("f1", "f2"),
                    "main" to listOf("f3"),
                    "Worker-1" to listOf("f4", "f5", "f6"),
                ),
            )
        assertFalse(bounded.threadsTruncated)
        assertFalse(bounded.framesTruncated)
        assertEquals(listOf("Worker-1", "Worker-2", "main"), bounded.threads.map { it.name })
        assertEquals(listOf("f4", "f5", "f6"), bounded.threads.first().frames)
        assertEquals(listOf("f3"), bounded.threads.last().frames)
    }

    @Test
    fun threadCountBeyondTheCapTruncatesAndFlags() {
        val entries = (0..AndroidScaleAllThreads.MAX_ALL_THREADS).map { index -> "t%03d".format(index) to listOf("frame") }
        val bounded = AndroidScaleAllThreads.boundAllThreads(entries)
        assertTrue(bounded.threadsTruncated)
        assertFalse(bounded.framesTruncated)
        assertEquals(AndroidScaleAllThreads.MAX_ALL_THREADS, bounded.threads.size)
        assertEquals("t000", bounded.threads.first().name)
        assertEquals("t%03d".format(AndroidScaleAllThreads.MAX_ALL_THREADS - 1), bounded.threads.last().name)
    }

    @Test
    fun framesBeyondTheCapPerThreadTruncateAndFlag() {
        val long = (0..AndroidScaleAllThreads.MAX_ALL_FRAMES_PER_THREAD + 4).map { index -> "frame$index" }
        val bounded =
            AndroidScaleAllThreads.boundAllThreads(
                listOf("main" to long, "short" to listOf("f1")),
            )
        assertFalse(bounded.threadsTruncated)
        assertTrue(bounded.framesTruncated)
        assertEquals(AndroidScaleAllThreads.MAX_ALL_FRAMES_PER_THREAD, bounded.threads.first().frames.size)
        assertEquals(listOf("f1"), bounded.threads.last().frames)
    }

    @Test
    fun combinedBoundaryTruncatesBothAndFlagsBoth() {
        val long = (0..AndroidScaleAllThreads.MAX_ALL_FRAMES_PER_THREAD).map { index -> "frame$index" }
        val entries =
            (0..AndroidScaleAllThreads.MAX_ALL_THREADS).map { index ->
                "t%03d".format(index) to long
            }
        val bounded = AndroidScaleAllThreads.boundAllThreads(entries)
        assertTrue(bounded.threadsTruncated)
        assertTrue(bounded.framesTruncated)
        assertEquals(AndroidScaleAllThreads.MAX_ALL_THREADS, bounded.threads.size)
        assertEquals(AndroidScaleAllThreads.MAX_ALL_FRAMES_PER_THREAD, bounded.threads.first().frames.size)
    }

    @Test
    fun exactlySixtyFourThreadsPassThroughUntruncated() {
        // The >=/> off-by-one: the cap itself is kept, one more is dropped.
        val entries = (0 until AndroidScaleAllThreads.MAX_ALL_THREADS).map { index -> "t%03d".format(index) to listOf("frame") }
        val bounded = AndroidScaleAllThreads.boundAllThreads(entries)
        assertFalse(bounded.threadsTruncated)
        assertFalse(bounded.framesTruncated)
        assertEquals(AndroidScaleAllThreads.MAX_ALL_THREADS, bounded.threads.size)
        assertEquals("t000", bounded.threads.first().name)
        assertEquals("t%03d".format(AndroidScaleAllThreads.MAX_ALL_THREADS - 1), bounded.threads.last().name)
    }

    @Test
    fun exactlyFortyEightFramesPassThroughUnflagged() {
        val entries =
            listOf(
                "main" to (0 until AndroidScaleAllThreads.MAX_ALL_FRAMES_PER_THREAD).map { index -> "frame$index" },
                "short" to listOf("f1"),
            )
        val bounded = AndroidScaleAllThreads.boundAllThreads(entries)
        assertFalse(bounded.threadsTruncated)
        assertFalse(bounded.framesTruncated)
        assertEquals(AndroidScaleAllThreads.MAX_ALL_FRAMES_PER_THREAD, bounded.threads.first().frames.size)
        assertEquals(listOf("f1"), bounded.threads.last().frames)
    }

    @Test
    fun frameTextBeyondTheCharCapIsClamped() {
        // A pathological frame is clamped to the D-202-matching char cap so
        // one long frame cannot blow the JSON size guard; the clamp is not a
        // count truncation, so the flag stays false.
        val bounded =
            AndroidScaleAllThreads.boundAllThreads(
                listOf("main" to listOf("x".repeat(500), "short")),
            )
        assertFalse(bounded.threadsTruncated)
        assertFalse(bounded.framesTruncated)
        assertEquals(2, bounded.threads.single().frames.size)
        assertEquals("x".repeat(AndroidScaleAllThreads.MAX_ALL_FRAME_CHARS), bounded.threads.single().frames.first())
        assertEquals("short", bounded.threads.single().frames.last())
    }

    @Test
    fun theAllThreadBoundsStayPinned() {
        assertEquals(64, AndroidScaleAllThreads.MAX_ALL_THREADS)
        assertEquals(48, AndroidScaleAllThreads.MAX_ALL_FRAMES_PER_THREAD)
        assertEquals(200, AndroidScaleAllThreads.MAX_ALL_FRAME_CHARS)
    }
}
