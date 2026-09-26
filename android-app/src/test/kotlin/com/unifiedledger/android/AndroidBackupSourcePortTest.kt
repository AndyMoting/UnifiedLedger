package com.unifiedledger.android

import com.unifiedledger.ui.BackupSourceOpenResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 06.C (D-179; spec section 8.1): the Android SAF source port. The launcher, the stream opener,
 * the size lookup and the main-thread poster are injected closures, so the port runs on the JVM
 * without Robolectric (the `AndroidBackupTargetPortTest` pattern, P2-8). The latch bridge that lets
 * the synchronous preflight wait on the asynchronous SAF callback is exercised directly, and the
 * launch-failure/cancel distinction is pinned.
 */
class AndroidBackupSourcePortTest {
    private class RecordingLauncher {
        var launchedMime: String? = null

        val launch: (Array<String>) -> Unit = { mime -> launchedMime = mime.firstOrNull() }
    }

    /** A poster that records the block, then runs it inline (test determinism). */
    private class RecordingPoster {
        val posted = AtomicReference<(() -> Unit)?>(null)

        val post: ((() -> Unit) -> Unit) = { block ->
            posted.set(block)
            block()
        }
    }

    /** Daemon, so a regression that leaves the caller blocked on the 10-minute latch cannot pin the
     *  test JVM after the join times out. */
    private fun daemonThread(body: () -> Unit): Thread = Thread(body).apply { isDaemon = true }

    private fun awaitLaunch(launcher: RecordingLauncher) {
        val deadline = System.currentTimeMillis() + 5_000
        while (launcher.launchedMime == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun theSafLaunchIsPostedThroughTheMainThreadPosterAndDeliversAReader() {
        // The preflight runs on a background thread, but the SAF launch must run on the main thread.
        // This pins that the port hands the launch to the poster, then the SAF callback delivers.
        val launcher = RecordingLauncher()
        val poster = RecordingPoster()
        val payload = byteArrayOf(1, 2, 3, 4)
        val port =
            AndroidBackupSourcePort<String>(
                poster.post,
                launcher.launch,
                { ByteArrayInputStream(payload) },
                { 4L },
            )
        val holder = arrayOfNulls<BackupSourceOpenResult>(1)

        val preflightThread = daemonThread { holder[0] = port.openSource() }
        preflightThread.start()
        awaitLaunch(launcher)
        assertTrue(poster.posted.get() != null, "the launch must go through the main-thread poster")
        assertEquals(ANDROID_BACKUP_CONTAINER_MIME, launcher.launchedMime)
        port.onOpenDocumentResult("uri")
        preflightThread.join(5_000)
        assertFalse(preflightThread.isAlive, "the preflight thread must finish (a stuck latch would hang here)")

        val opened = assertIs<BackupSourceOpenResult.Opened>(holder[0])
        assertEquals(4L, opened.reader.reportedSize)
        val buffer = ByteArray(8)
        val read = opened.reader.read(buffer)
        assertContentEquals(payload, buffer.copyOf(read))
    }

    @Test
    fun aNullSafHandleIsReportedAsCancelled() {
        val launcher = RecordingLauncher()
        val port =
            AndroidBackupSourcePort<String>(
                { it() },
                launcher.launch,
                { ByteArrayInputStream(ByteArray(0)) },
                { null },
            )
        val holder = arrayOfNulls<BackupSourceOpenResult>(1)
        val preflightThread = daemonThread { holder[0] = port.openSource() }
        preflightThread.start()
        awaitLaunch(launcher)
        port.onOpenDocumentResult(null)
        preflightThread.join(5_000)

        assertEquals(BackupSourceOpenResult.Cancelled, holder[0])
    }

    @Test
    fun aThrowingPosterIsReportedAsLaunchFailedNotCancelled() {
        // P2-8: a launch failure must be distinguishable from a cancel.
        val port =
            AndroidBackupSourcePort<String>(
                { throw IllegalStateException("no main looper") },
                { },
                { ByteArrayInputStream(ByteArray(0)) },
                { null },
            )
        assertEquals(BackupSourceOpenResult.LaunchFailed, port.openSource())
    }

    @Test
    fun aFailedStreamOpenIsReportedAsLaunchFailed() {
        val launcher = RecordingLauncher()
        val port =
            AndroidBackupSourcePort<String>(
                { it() },
                launcher.launch,
                { _: String -> null as InputStream? },
                { null },
            )
        val holder = arrayOfNulls<BackupSourceOpenResult>(1)
        val preflightThread = daemonThread { holder[0] = port.openSource() }
        preflightThread.start()
        awaitLaunch(launcher)
        port.onOpenDocumentResult("uri")
        preflightThread.join(5_000)

        assertEquals(BackupSourceOpenResult.LaunchFailed, holder[0])
    }
}
