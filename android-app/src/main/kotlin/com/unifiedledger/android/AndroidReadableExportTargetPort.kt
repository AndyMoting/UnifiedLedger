package com.unifiedledger.android

import android.net.Uri
import com.unifiedledger.application.export.readable.ReadableExportSheetWriter
import com.unifiedledger.application.export.readable.ReadableExportSink
import com.unifiedledger.application.export.readable.ReadableLedgerXlsxStreamWriter
import com.unifiedledger.application.export.readable.suggestedFileName
import com.unifiedledger.ui.BackupTargetPort
import com.unifiedledger.ui.BackupTargetWriter
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/*
 * P8-05 批 b (D-331 spec D-E12): the Android sink bridge — the platform-local half of the
 * adapter that turns the backup target-port family (app-ui commonMain) into the self-owned
 * `ReadableExportSink` byte face (ledger-application jvmMain) the readable-export use case
 * streams through. The `ReadableExportSink`/`ReadableLedgerXlsxStreamWriter` symbols are
 * jvmMain-only (the ImportTemplateTargetPort precedent: commonMain cannot see them), so this
 * bridge lives HERE alongside the backup target-port family it reuses.
 *
 * D-E12's flush discipline is the load-bearing rule this bridge exists for: the
 * `BackupTargetWriter.commit()` contract only flushes + closes
 * (`AndroidBackupTargetPort.kt:108-112`), while the self-owned sink's `commit()` must deliver
 * every written byte durably ("文件字节达盘的义务由契约方承担，桥接时不得丢"). The bridge therefore
 * folds `flushAndSync()` into the bridge's `commit()` BEFORE the target's own commit — commit
 * exactly once, clean-close-to-succeed semantics preserved, `close` idempotent and abandoning.
 *
 * D-E7 suggested name: the readable export launches its OWN CreateDocument port with
 * `readableExportSuggestedFileName` (the frozen display-name shape over the injected wall-clock's
 * Shanghai date, batch a's `suggestedFileName` single constant source) — the backup port's frozen
 * `unifiedledger-backup.ulbk` launch stays untouched (D-E12: 两端口平台实现零改动), and the two
 * ports never share one SAF launch/deliver slot pair (the P8-04 template-port precedent: one port
 * per slot pair).
 *
 * Dependency direction: app-ui -> ledger-application (this file imports both), never the reverse.
 */

/**
 * The D-E7 suggested launch name: the frozen display-name shape over the injected wall-clock's
 * Shanghai date (`suggestedFileName`, batch a's single constant source — never a local copy).
 * Defaulted to the process clock so the production wiring needs no extra parameter; tests inject
 * a fixed instant.
 */
fun readableExportSuggestedFileName(now: kotlin.time.Instant = Clock.System.now()): String = suggestedFileName(now)

/**
 * The readable-export save target port: the backup port's SAF CreateDocument latch flow (the
 * `AndroidBackupTargetPort` latch shape the spec cites) over the readable export's OWN suggested
 * name. A separate class with its own pending slot, so a backup launch in flight can never
 * consume the readable export's SAF result and vice versa. The launcher, the stream opener and
 * the main-thread poster are injected closures, so the port is JVM-unit-testable without
 * Robolectric (the `AndroidBackupTargetPortTest` injection pattern).
 */
class AndroidReadableExportTargetPort<Picked>(
    /** Posts [launchCreateDocument] onto the main thread (SAF launchers are main-thread only). */
    private val postToMainThread: ((() -> Unit) -> Unit),
    private val launchCreateDocument: (suggestedName: String) -> Unit,
    /** Opens the picked document as the port's writer shape (production wraps the SAF stream). */
    private val openWriter: (Picked) -> BackupTargetWriter?,
    /** Resolves the D-E7 suggested launch name (the date from the injected wall-clock). */
    private val suggestedName: () -> String = ::readableExportSuggestedFileName,
) : BackupTargetPort {
    /** The pending wait: set while a chooser is in flight, released by the SAF callback. */
    @Volatile
    private var pending: PendingChoice<Picked>? = null

    override fun openTarget(): BackupTargetWriter? {
        val latch = CountDownLatch(1)
        val choice = PendingChoice<Picked>(latch)
        pending = choice
        try {
            // The launch is posted to the main thread; the export thread then waits below. A
            // throwing poster (e.g. a dead main looper) is a cancelled choice, never a crash.
            postToMainThread { launchCreateDocument(suggestedName()) }
        } catch (failure: Exception) {
            pending = null
            return null
        }
        val delivered = latch.await(ANDROID_BACKUP_TARGET_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        pending = null
        if (!delivered) return null
        val picked = choice.picked ?: return null
        return try {
            openWriter(picked)
        } catch (failure: Exception) {
            null
        }
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

/**
 * Opens the Android SAF save target through [AndroidReadableExportTargetPort]'s latch flow as the
 * self-owned [ReadableExportSink]. A null open stays the user's cancellation.
 */
fun readableExportSinkFromTargetPort(target: BackupTargetPort): ReadableExportSink? = target.openTarget()?.let(::ReadableTargetWriterSink)

/**
 * The bridge writer over one [BackupTargetWriter]. [commit] is flushAndSync-then-commit exactly
 * once (D-E12); [close] abandons an uncommitted target idempotently and never claims success.
 */
internal class ReadableTargetWriterSink(
    private val target: BackupTargetWriter,
) : ReadableExportSink {
    private var committed = false

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        target.write(bytes, offset, length)
    }

    override fun commit() {
        // D-E12: fold flushAndSync INTO the commit — the target writer's own commit only
        // flushes + closes, and the byte-delivery obligation belongs to the bridging side.
        target.flushAndSync()
        target.commit()
        committed = true
    }

    override fun close() {
        if (!committed) {
            runCatching { target.close() }
        }
    }
}

/**
 * The raw SAF stream as a [BackupTargetWriter] (the shape the port's openWriter injection and the
 * sink bridge consume). Semantics mirror the backup port's registered SAF boundary: flush = stream
 * flush, commit = flush + close in place (SAF external providers cannot be atomically replaced),
 * close before commit abandons the stream without claiming success.
 */
fun safTargetWriter(stream: OutputStream): BackupTargetWriter = SafTargetWriter(stream)

private class SafTargetWriter(
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

/**
 * The composition-root assembly face of the Android readable export: the sheet-writer factory
 * the binding's `newExport` closure captures. Opening the target (the SAF latch flow) and
 * building the jvmMain XLSX writer over it happen INSIDE the use case's run — never at wiring
 * time; a null open is the honest cancelled picker (the use case maps it to Cancelled).
 */
fun readableExportSheetWriterFactory(target: AndroidReadableExportTargetPort<Uri>): () -> ReadableExportSheetWriter? =
    { readableExportSinkFromTargetPort(target)?.let(::ReadableLedgerXlsxStreamWriter) }
