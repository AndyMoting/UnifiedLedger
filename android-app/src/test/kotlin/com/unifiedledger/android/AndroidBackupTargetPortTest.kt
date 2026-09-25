package com.unifiedledger.android

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec section 3.5 phase 2): the Android SAF save-target port. The launcher, the
 * stream opener and the main-thread poster are injected closures, so the port runs on the JVM
 * without Robolectric (the `AndroidImportFilePickPort` injection pattern). The latch bridge that
 * lets the synchronous export use case wait on the asynchronous SAF callback is exercised directly.
 */
class AndroidBackupTargetPortTest {
    private class RecordingLauncher {
        var launchedName: String? = null

        val launch: (String) -> Unit = { name -> launchedName = name }
    }

    /** A poster that records which thread ran the block, then runs it inline (test determinism). */
    private class RecordingPoster {
        val posted = AtomicReference<(() -> Unit)?>(null)

        val post: ((() -> Unit) -> Unit) = { block ->
            posted.set(block)
            block()
        }
    }

    /** P3-M fix: daemon, so a regression that leaves the export blocked on the 10-minute latch
     *  cannot pin the test JVM after the join times out. */
    private fun daemonThread(body: () -> Unit): Thread = Thread(body).apply { isDaemon = true }

    private fun awaitLaunch(launcher: RecordingLauncher) {
        val deadline = System.currentTimeMillis() + 5_000
        while (launcher.launchedName == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun theSafLaunchIsPostedThroughTheMainThreadPoster() {
        // The export runs on a background thread, but the SAF launch must run on the main thread.
        // This test pins that the port never calls the launcher directly: it hands the launch to
        // the injected poster, and only the poster's block reaches the launcher.
        val launcher = RecordingLauncher()
        val poster = RecordingPoster()
        val port = AndroidBackupTargetPort<String>(poster.post, launcher.launch) { ByteArrayOutputStream() }
        val holder = arrayOfNulls<com.unifiedledger.ui.BackupTargetWriter>(1)

        val exportThread = daemonThread { holder[0] = port.openTarget() }
        exportThread.start()
        // The poster ran the launch; the port is now waiting on the latch.
        val deadline = System.currentTimeMillis() + 5_000
        while (poster.posted.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(poster.posted.get() != null, "the launch must go through the main-thread poster")
        assertEquals("unifiedledger-backup.ulbk", launcher.launchedName)
        port.onCreateDocumentResult("uri")
        exportThread.join(5_000)
        assertFalse(exportThread.isAlive, "the export thread must finish (a stuck latch would hang here)")
        assertTrue(holder[0] != null)
    }

    @Test
    fun aDeliveredSafUriYieldsAWriterThatStreamsAndCommits() {
        val launcher = RecordingLauncher()
        val sink = ByteArrayOutputStream()
        val port = AndroidBackupTargetPort<String>({ it() }, launcher.launch) { sink }
        val payload = ByteArray(100_000) { (it % 251).toByte() }

        // The export thread opens the target; the SAF callback is delivered from another thread.
        val writerHolder = arrayOfNulls<com.unifiedledger.ui.BackupTargetWriter>(1)
        val exportThread =
            daemonThread {
                writerHolder[0] = port.openTarget()
            }
        exportThread.start()
        // Wait for the launch, then deliver the SAF result as the main-thread callback would.
        awaitLaunch(launcher)
        port.onCreateDocumentResult("uri")
        exportThread.join(5_000)
        assertFalse(exportThread.isAlive, "the export thread must finish (a stuck latch would hang here)")

        val writer = writerHolder[0]!!
        writer.write(payload, 0, payload.size)
        writer.flushAndSync()
        writer.commit()
        writer.close()

        assertContentEquals(payload, sink.toByteArray())
    }

    @Test
    fun aCancelledSafChoiceYieldsNoTarget() {
        val launcher = RecordingLauncher()
        val port = AndroidBackupTargetPort<String>({ it() }, launcher.launch) { ByteArrayOutputStream() }
        val holder = arrayOfNulls<com.unifiedledger.ui.BackupTargetWriter>(1)
        val exportThread =
            daemonThread {
                holder[0] = port.openTarget()
            }
        exportThread.start()
        awaitLaunch(launcher)
        port.onCreateDocumentResult(null)
        exportThread.join(5_000)
        assertFalse(exportThread.isAlive, "the export thread must finish (a stuck latch would hang here)")

        assertNull(holder[0])
    }

    @Test
    fun aThrowingMainThreadPosterYieldsNoTargetInsteadOfCrashing() {
        // A dead main looper must be a cancelled choice, never a crash of the export thread.
        val port = AndroidBackupTargetPort<String>({ throw IllegalStateException("no main looper") }, {}) { ByteArrayOutputStream() }

        assertNull(port.openTarget())
    }

    @Test
    fun anUncommittedWriterDoesNotCloseTheUnderlyingStreamWithASuccessClaim() {
        val launcher = RecordingLauncher()
        val closed = CountDownLatch(1)
        val stream =
            object : OutputStream() {
                override fun write(b: Int) {}

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {}

                override fun close() {
                    closed.countDown()
                }
            }
        val port = AndroidBackupTargetPort<String>({ it() }, launcher.launch) { stream }
        val holder = arrayOfNulls<com.unifiedledger.ui.BackupTargetWriter>(1)
        val exportThread =
            daemonThread {
                holder[0] = port.openTarget()
            }
        exportThread.start()
        awaitLaunch(launcher)
        port.onCreateDocumentResult("uri")
        exportThread.join(5_000)
        assertFalse(exportThread.isAlive, "the export thread must finish (a stuck latch would hang here)")

        holder[0]!!.close()

        assertTrue(closed.await(2, TimeUnit.SECONDS), "close must release the stream")
    }

    @Test
    fun dispatchToMainThreadRunsInlineOnTheMainLooperAndPostsOtherwise() {
        // P3-L fix (06.B review): mainThreadPoster() itself needs android.os (a stub off-device), so
        // its pure decision is pinned here. On the main looper the block runs inline; otherwise it
        // is handed to the poster and NOT run inline.
        var inlineRan = false
        dispatchToMainThread(onMainLooper = true, post = { error("must not post when already on main") }) { inlineRan = true }
        assertTrue(inlineRan)

        var posted: (() -> Unit)? = null
        var postedRan = false
        dispatchToMainThread(
            onMainLooper = false,
            post = { block ->
                posted = block
                postedRan = true
            },
        ) { error("must not run inline when off the main looper") }
        assertTrue(postedRan, "the block must be posted")
        assertTrue(posted != null)
    }
}
