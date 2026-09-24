package com.unifiedledger.android

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec section 3.5 phase 2): the Android SAF save-target port. The launcher and
 * the stream opener are injected closures, so the port runs on the JVM without Robolectric (the
 * `AndroidImportFilePickPort` injection pattern). The latch bridge that lets the synchronous export
 * use case wait on the asynchronous SAF callback is exercised directly.
 */
class AndroidBackupTargetPortTest {
    private class RecordingLauncher {
        var launchedName: String? = null

        val launch: (String) -> Unit = { name -> launchedName = name }
    }

    @Test
    fun aDeliveredSafUriYieldsAWriterThatStreamsAndCommits() {
        val launcher = RecordingLauncher()
        val sink = ByteArrayOutputStream()
        val port = AndroidBackupTargetPort<String>(launcher.launch) { sink }
        val payload = ByteArray(100_000) { (it % 251).toByte() }

        // The export thread opens the target; the SAF callback is delivered from another thread.
        val writerHolder = arrayOfNulls<com.unifiedledger.ui.BackupTargetWriter>(1)
        val exportThread =
            Thread {
                writerHolder[0] = port.openTarget()
            }
        exportThread.start()
        // Wait for the launch, then deliver the SAF result as the main-thread callback would.
        val deadline = System.currentTimeMillis() + 5_000
        while (launcher.launchedName == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        port.onCreateDocumentResult("uri")
        exportThread.join(5_000)

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
        val port = AndroidBackupTargetPort<String>(launcher.launch) { ByteArrayOutputStream() }
        val holder = arrayOfNulls<com.unifiedledger.ui.BackupTargetWriter>(1)
        val exportThread =
            Thread {
                holder[0] = port.openTarget()
            }
        exportThread.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (launcher.launchedName == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        port.onCreateDocumentResult(null)
        exportThread.join(5_000)

        assertNull(holder[0])
    }

    @Test
    fun anUncommittedWriterDoesNotCloseTheUnderlyingStreamWithASuccessClaim() {
        val launcher = RecordingLauncher()
        val closed = CountDownLatch(1)
        val stream =
            object : OutputStream() {
                override fun write(b: Int) {}

                override fun write(b: ByteArray, off: Int, len: Int) {}

                override fun close() {
                    closed.countDown()
                }
            }
        val port = AndroidBackupTargetPort<String>(launcher.launch) { stream }
        val holder = arrayOfNulls<com.unifiedledger.ui.BackupTargetWriter>(1)
        val exportThread =
            Thread {
                holder[0] = port.openTarget()
            }
        exportThread.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (launcher.launchedName == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        port.onCreateDocumentResult("uri")
        exportThread.join(5_000)

        holder[0]!!.close()

        assertTrue(closed.await(2, TimeUnit.SECONDS), "close must release the stream")
    }
}