package com.unifiedledger.desktop

import com.unifiedledger.ui.BACKUP_TARGET_SUGGESTED_NAME
import com.unifiedledger.ui.BackupTargetPort
import com.unifiedledger.ui.BackupTargetWriter
import java.awt.EventQueue
import java.io.File
import java.io.FileOutputStream
import javax.swing.JFileChooser

/*
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` section 3.5 phase 2): the
 * desktop save-target port. The JDK-built-in Swing `JFileChooser` picks the destination and the
 * container is streamed to a sibling temp file, fsynced, then atomically moved onto the target
 * (`ATOMIC_MOVE`) — the same atomic-replace semantics as the stable-storage pointer publish. Where
 * the platform cannot move atomically the writer throws rather than publishing a partial file.
 *
 * The dialog and the stream opener are injected lambdas, so the port is unit-testable headless; the
 * product wiring passes [showSwingSaveFileChooser].
 */
internal class DesktopBackupTargetPort(
    private val showSaveFileChooser: (suggestedName: String) -> File?,
    private val openOutputStream: (File) -> FileOutputStream = { FileOutputStream(it) },
) : BackupTargetPort {
    override fun openTarget(): BackupTargetWriter? {
        val file = showSaveFileChooser(BACKUP_TARGET_SUGGESTED_NAME) ?: return null
        return DesktopTargetWriter(file, openOutputStream)
    }
}

private class DesktopTargetWriter(
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
        java.nio.file.Files.move(
            temporary.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
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
 * The real Swing save dialog: `JFileChooser` in save mode with the suggested name pre-filled.
 * Modal on the AWT event-dispatch thread (also the Compose Desktop UI thread), so a launch from a
 * UI event handler shows the dialog synchronously; a launch from any other thread is routed through
 * [EventQueue.invokeAndWait] so the Swing threading contract always holds. The heavy streaming write
 * stays off the UI thread.
 */
internal fun showSwingSaveFileChooser(suggestedName: String): File? {
    val showDialog = {
        val chooser = JFileChooser()
        chooser.dialogType = JFileChooser.SAVE_DIALOG
        chooser.selectedFile = File(suggestedName)
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
    return if (EventQueue.isDispatchThread()) {
        showDialog()
    } else {
        val chosen = arrayOfNulls<File>(1)
        EventQueue.invokeAndWait { chosen[0] = showDialog() }
        chosen[0]
    }
}