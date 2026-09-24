package com.unifiedledger.android

import android.net.Uri
import com.unifiedledger.ui.BackupTargetPort
import com.unifiedledger.ui.BackupTargetWriter
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` section 3.5 phase 2): the
 * Android save-target port. SAF `ActivityResultContracts.CreateDocument` picks the destination and
 * `ContentResolver.openOutputStream` streams the container in bounded chunks.
 *
 * THREADING (why the latch): the shared export use case is synchronous and runs on a background
 * thread (spec section 5 forbids the UI thread), while SAF delivery is asynchronous on the main
 * thread. The port therefore launches the picker on the main thread and BLOCKS the calling
 * background thread on a latch until the SAF callback delivers the chosen document; the main thread
 * is never blocked, so there is no deadlock. This keeps the commonMain contract synchronous (the
 * user target is created only after the precheck and both plaintext gates have passed — spec
 * sections 3.2/5), which a pick-first flow could not guarantee.
 *
 * The launcher, the stream opener and the main-thread poster are injected closures, so the port is
 * JVM-unit-testable without Robolectric (the `AndroidImportFilePickPort` injection pattern).
 */

/** How long [AndroidBackupTargetPort.openTarget] waits for the user's SAF choice. */
internal const val ANDROID_BACKUP_TARGET_WAIT_MILLIS: Long = 10L * 60L * 1000L

internal class AndroidBackupTargetPort<Picked>(
    /** Posts the SAF launch onto the main thread (SAF launchers are main-thread only). */
    private val launchCreateDocument: (suggestedName: String) -> Unit,
    private val openOutputStream: (Picked) -> OutputStream?,
) : BackupTargetPort {
    /** The pending wait: set while a chooser is in flight, released by the SAF callback. */
    @Volatile
    private var pending: PendingChoice<Picked>? = null

    override fun openTarget(): BackupTargetWriter? {
        val latch = CountDownLatch(1)
        val choice = PendingChoice<Picked>(latch)
        pending = choice
        try {
            launchCreateDocument(BACKUP_TARGET_NAME)
        } catch (failure: Exception) {
            pending = null
            return null
        }
        val delivered = latch.await(ANDROID_BACKUP_TARGET_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        pending = null
        if (!delivered) return null
        val picked = choice.picked ?: return null
        val stream =
            try {
                openOutputStream(picked)
            } catch (failure: Exception) {
                null
            }
        return stream?.let(::AndroidTargetWriter)
    }

    /** The SAF CreateDocument result callback: a null handle is the user's cancellation. */
    fun onCreateDocumentResult(picked: Picked?) {
        val choice = pending ?: return
        choice.picked = picked
        choice.latch.countDown()
    }

    private class PendingChoice<Picked>(
        val latch: CountDownLatch,
    ) {
        @Volatile
        var picked: Picked? = null
    }
}

/** The suggested container file name (display only; SAF may override it). */
private const val BACKUP_TARGET_NAME: String = "unifiedledger-backup.ulbk"

/**
 * The bounded streaming writer over one SAF output stream. SAF external providers generally cannot
 * be atomically replaced, so the content is written in place and the export reports success only
 * after a clean [commit] (spec section 3.5). [close] before commit abandons the stream without
 * claiming success; the spec promises no external-provider auto-delete, so no deletion is attempted.
 */
private class AndroidTargetWriter(
    private val stream: OutputStream,
) : BackupTargetWriter {
    private var committed = false

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        stream.write(bytes, offset, length)
    }

    override fun flushAndSync() {
        stream.flush()
    }

    override fun commit() {
        stream.flush()
        stream.close()
        committed = true
    }

    override fun close() {
        if (!committed) {
            runCatching { stream.close() }
        }
    }
}