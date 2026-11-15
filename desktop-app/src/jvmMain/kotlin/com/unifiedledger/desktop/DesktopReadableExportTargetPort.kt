package com.unifiedledger.desktop

import com.unifiedledger.application.export.readable.ReadableExportSheetWriter
import com.unifiedledger.application.export.readable.ReadableExportSink
import com.unifiedledger.application.export.readable.ReadableLedgerXlsxStreamWriter
import com.unifiedledger.application.export.readable.suggestedFileName
import com.unifiedledger.ui.BackupTargetPort
import com.unifiedledger.ui.BackupTargetWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

/*
 * P8-05 批 b (D-331 spec D-E12): the desktop sink bridge — the platform-local half of the
 * adapter that turns the backup target-port family (app-ui commonMain) into the self-owned
 * `ReadableExportSink` byte face (ledger-application jvmMain) the readable-export use case
 * streams through. The `ReadableExportSink`/`ReadableLedgerXlsxStreamWriter` symbols are
 * jvmMain-only, so this bridge lives HERE alongside the backup target-port machinery it reuses
 * (the Swing SAVE_DIALOG + sibling-temp + ATOMIC_MOVE writer shape, `DesktopBackupTargetPort`).
 *
 * D-E12's flush discipline is the load-bearing rule this bridge exists for: the
 * `BackupTargetWriter.commit()` contract only flushes + closes, while the self-owned sink's
 * `commit()` must deliver every written byte durably ("文件字节达盘的义务由契约方承担，桥接时不得
 * 丢"). The bridge therefore folds `flushAndSync()` into the bridge's `commit()` BEFORE the
 * target's own commit — commit exactly once, clean-close-to-succeed semantics preserved, `close`
 * idempotent and abandoning (the desktop writer's own temp-file cleanup rides the close).
 *
 * D-E7 suggested name: the readable export opens its OWN save dialog with
 * `readableExportSuggestedFileName` (the frozen display-name shape over the injected wall-clock's
 * Shanghai date, batch a's `suggestedFileName` single constant source) — the backup port's frozen
 * suggestion stays untouched (D-E12: 两端口平台实现零改动).
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
 * The readable-export save target port: the backup port's Swing SAVE_DIALOG + sibling-temp +
 * ATOMIC_MOVE writer flow over the readable export's OWN suggested name. The dialog and the
 * stream opener are injected lambdas, so the port is unit-testable headless (the
 * `DesktopBackupTargetPort` injection pattern); the product wiring passes
 * [showSwingSaveFileChooser].
 */
class DesktopReadableExportTargetPort(
    private val showSaveFileChooser: (suggestedName: String) -> File?,
    private val openOutputStream: (File) -> FileOutputStream = { FileOutputStream(it) },
    /** Resolves the D-E7 suggested launch name (the date from the injected wall-clock). */
    private val suggestedName: () -> String = ::readableExportSuggestedFileName,
) : BackupTargetPort {
    override fun openTarget(): BackupTargetWriter? {
        val file = showSaveFileChooser(suggestedName()) ?: return null
        return DesktopReadableTargetWriter(file, openOutputStream)
    }
}

/**
 * The desktop bridge writer over the chosen target file: sibling temp file, [flushAndSync] fsync,
 * then ATOMIC_MOVE onto the target at [commit] (the `DesktopBackupTargetWriter` shape; where the
 * platform cannot move atomically the writer throws rather than publishing a partial file).
 * [close] before commit abandons the temp file idempotently and never claims success.
 */
internal class DesktopReadableTargetWriter(
    private val target: File,
    private val openOutputStream: (File) -> FileOutputStream,
) : BackupTargetWriter {
    private val temporary = File(target.parentFile, target.name + ".tmp")
    private val stream = openOutputStream(temporary).also { target.parentFile?.mkdirs() }
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
        stream.fd.sync()
    }

    override fun commit() {
        stream.flush()
        stream.fd.sync()
        stream.close()
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        committed = true
    }

    override fun close() {
        if (!committed) {
            runCatching { stream.close() }
            temporary.delete()
        }
    }
}

/**
 * Opens the desktop save target through [DesktopReadableExportTargetPort]'s save-dialog flow as
 * the self-owned [ReadableExportSink]. A null open stays the user's cancellation.
 */
fun readableExportSinkFromTargetPort(target: BackupTargetPort): ReadableExportSink? = target.openTarget()?.let(::ReadableTargetWriterSink)

/**
 * The bridge writer over one [BackupTargetWriter]. [commit] is flushAndSync-then-commit exactly
 * once (D-E12); [close] abandons an uncommitted target idempotently and never claims success.
 * Symmetric to the Android bridge — the sink semantics are platform-independent.
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
 * The composition-root assembly face of the desktop readable export: the sheet-writer factory
 * the binding's `newExport` closure captures. Opening the target (the save-dialog flow) and
 * building the jvmMain XLSX writer over it happen INSIDE the use case's run — never at wiring
 * time; a null open is the honest cancelled picker (the use case maps it to Cancelled).
 */
fun readableExportSheetWriterFactory(target: DesktopReadableExportTargetPort): () -> ReadableExportSheetWriter? = { readableExportSinkFromTargetPort(target)?.let(::ReadableLedgerXlsxStreamWriter) }
