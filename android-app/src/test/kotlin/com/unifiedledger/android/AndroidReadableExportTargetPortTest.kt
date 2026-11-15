package com.unifiedledger.android

import com.unifiedledger.ui.BackupTargetWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P8-05 批 b (D-331 spec sections 4 批 b / D-E12; V-4): the Android readable-export sink bridge's
 * JVM injection tests (the `AndroidBackupTargetPortTest` injection pattern — poster, launcher and
 * stream opener are closures, so no Robolectric). The spec's sink-semantics-equivalence matrix:
 *
 * - the launch carries the readable export's OWN suggested name (the D-E7 date shape), never the
 *   backup container's frozen `.ulbk` name;
 * - commit is flushAndSync-then-commit EXACTLY ONCE and closes clean — the byte-delivery
 *   obligation folded into the bridge (the D-E12 rule the backup writer's own commit does not
 *   carry);
 * - an uncommitted close abandons the target WITHOUT claiming success (close-before-commit never
 *   reports success);
 * - a cancelled SAF choice (and a throwing poster) open nothing — the honest null.
 */
class AndroidReadableExportTargetPortTest {
    private class RecordingLauncher {
        val launchedNames = mutableListOf<String>()

        val launch: (String) -> Unit = { name -> launchedNames += name }
    }

    private class RecordingSink {
        val flushed = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        /** The invocation order of flushAndSync/commit/close (the F-5 ordering probe). */
        val callOrder = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun asWriter(sink: ByteArrayOutputStream): BackupTargetWriter =
            object : BackupTargetWriter {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    sink.write(bytes, offset, length)
                }

                override fun flushAndSync() {
                    callOrder += "flushAndSync"
                    flushed.countDown()
                }

                override fun commit() {
                    callOrder += "commit"
                    committed.countDown()
                }

                override fun close() {
                    callOrder += "close"
                    closed.countDown()
                }
            }
    }

    /** A poster that records which thread ran the block, then runs it inline (test determinism). */
    private class RecordingPoster {
        val posted = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

        val post: ((() -> Unit) -> Unit) = { block ->
            posted.set(block)
            block()
        }
    }

    /** P3-M fix: daemon, so a regression that leaves the caller blocked on the 10-minute latch
     *  cannot pin the test JVM after the join times out. */
    private fun daemonThread(body: () -> Unit): Thread = Thread(body).apply { isDaemon = true }

    private fun awaitLaunch(launcher: RecordingLauncher) {
        val deadline = System.currentTimeMillis() + 5_000
        while (launcher.launchedNames.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun theLaunchCarriesTheReadableExportSuggestedNameNotTheBackupName() {
        // D-E7: the readable export's own display-name shape (the frozen prefix + the Shanghai
        // date of the injected clock); the backup port's `.ulbk` name must never appear.
        val launcher = RecordingLauncher()
        val port =
            AndroidReadableExportTargetPort<String>(
                postToMainThread = { it() },
                launchCreateDocument = launcher.launch,
                openOutputStream = { ByteArrayOutputStream() },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )
        val holder = arrayOfNulls<com.unifiedledger.application.export.readable.ReadableExportSink>(1)

        val caller = daemonThread { holder[0] = readableExportSinkFromTargetPort(port) }
        caller.start()
        awaitLaunch(launcher)
        port.onCreateDocumentResult("uri")
        caller.join(5_000)
        assertFalse(caller.isAlive, "the caller thread must finish (a stuck latch would hang here)")

        assertEquals(listOf("unifiedledger-export-20261115.xlsx"), launcher.launchedNames)
        assertFalse(launcher.launchedNames.any { it.endsWith(".ulbk") }, "the backup container name must never launch")
        assertTrue(holder[0] != null)
    }

    @Test
    fun commitIsFlushThenCommitExactlyOnceAndTheCloseIsClean() {
        // D-E12: the bridge folds flushAndSync INTO the commit, before the target's own commit;
        // the byte-delivery obligation belongs to the bridging side.
        val launcher = RecordingLauncher()
        val sink = ByteArrayOutputStream()
        val recorder = RecordingSink()
        val port =
            AndroidReadableExportTargetPort<String>(
                postToMainThread = { it() },
                launchCreateDocument = launcher.launch,
                openOutputStream = { recorder.asWriter(sink) },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )
        val holder = arrayOfNulls<com.unifiedledger.application.export.readable.ReadableExportSink>(1)

        val caller = daemonThread { holder[0] = readableExportSinkFromTargetPort(port) }
        caller.start()
        awaitLaunch(launcher)
        port.onCreateDocumentResult("uri")
        caller.join(5_000)
        assertFalse(caller.isAlive, "the caller thread must finish (a stuck latch would hang here)")

        val writer = holder[0]!!
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        writer.write(payload, 0, payload.size)
        writer.commit()
        writer.close()

        assertContentEquals(payload, sink.toByteArray())
        assertTrue(recorder.flushed.await(2, TimeUnit.SECONDS), "commit must flushAndSync BEFORE the target commit")
        assertTrue(recorder.committed.await(2, TimeUnit.SECONDS), "commit must reach the target's commit exactly once")
        assertFalse(recorder.closed.await(100, TimeUnit.MILLISECONDS), "a committed close must not close the target again")
        // F-5: the two latches above both fire on this synchronous call path, so they pin the
        // REACH of flushAndSync/commit but not their ORDER; the recorded invocation sequence pins
        // the D-E12 fold order itself — flushAndSync strictly before the target's commit.
        assertEquals(listOf("flushAndSync", "commit"), recorder.callOrder)
    }

    @Test
    fun closingBeforeCommitAbandonsTheTargetWithoutClaimingSuccess() {
        // Close-before-commit never reports success: the bridge releases the target, and the
        // target's commit is never reached (no "committed half-product" semantics, V-4).
        val launcher = RecordingLauncher()
        val recorder = RecordingSink()
        val port =
            AndroidReadableExportTargetPort<String>(
                postToMainThread = { it() },
                launchCreateDocument = launcher.launch,
                openOutputStream = { recorder.asWriter(ByteArrayOutputStream()) },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )
        val holder = arrayOfNulls<com.unifiedledger.application.export.readable.ReadableExportSink>(1)

        val caller = daemonThread { holder[0] = readableExportSinkFromTargetPort(port) }
        caller.start()
        awaitLaunch(launcher)
        port.onCreateDocumentResult("uri")
        caller.join(5_000)
        assertFalse(caller.isAlive, "the caller thread must finish (a stuck latch would hang here)")

        holder[0]!!.close()

        assertTrue(recorder.closed.await(2, TimeUnit.SECONDS), "the uncommitted close must release the target")
        assertFalse(recorder.committed.await(100, TimeUnit.MILLISECONDS), "an abandoned sink must never commit")
        assertFalse(recorder.flushed.await(100, TimeUnit.MILLISECONDS), "an abandoned sink must never claim a flush")
    }

    @Test
    fun aCancelledSafChoiceOpensNoSink() {
        val launcher = RecordingLauncher()
        val port =
            AndroidReadableExportTargetPort<String>(
                postToMainThread = { it() },
                launchCreateDocument = launcher.launch,
                openOutputStream = { ByteArrayOutputStream() },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )
        val holder = arrayOfNulls<com.unifiedledger.application.export.readable.ReadableExportSink>(1)

        val caller = daemonThread { holder[0] = readableExportSinkFromTargetPort(port) }
        caller.start()
        awaitLaunch(launcher)
        port.onCreateDocumentResult(null)
        caller.join(5_000)
        assertFalse(caller.isAlive, "the caller thread must finish (a stuck latch would hang here)")

        assertNull(holder[0], "a cancelled picker must open nothing (the honest null)")
    }

    @Test
    fun aThrowingMainThreadPosterOpensNoSinkInsteadOfCrashing() {
        // A dead main looper must be a cancelled choice, never a crash of the export thread.
        val port =
            AndroidReadableExportTargetPort<String>(
                postToMainThread = { throw IllegalStateException("no main looper") },
                launchCreateDocument = {},
                openOutputStream = { ByteArrayOutputStream() },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )

        assertNull(readableExportSinkFromTargetPort(port))
    }

    @Test
    fun aNullOutputStreamOpensNoSink() {
        val launcher = RecordingLauncher()
        val port =
            AndroidReadableExportTargetPort<String>(
                postToMainThread = { it() },
                launchCreateDocument = launcher.launch,
                openOutputStream = { null },
                suggestedName = { "unifiedledger-export-20261115.xlsx" },
            )
        val holder = arrayOfNulls<com.unifiedledger.application.export.readable.ReadableExportSink>(1)

        val caller = daemonThread { holder[0] = readableExportSinkFromTargetPort(port) }
        caller.start()
        awaitLaunch(launcher)
        port.onCreateDocumentResult("uri")
        caller.join(5_000)
        assertFalse(caller.isAlive, "the caller thread must finish (a stuck latch would hang here)")

        assertNull(holder[0], "a provider-less document must open nothing (the honest null)")
    }
}
