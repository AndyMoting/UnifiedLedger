package com.unifiedledger.desktop

import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.ImportFilePickFailure
import com.unifiedledger.ui.ImportFilePickPort
import com.unifiedledger.ui.ImportFilePickRequest
import com.unifiedledger.ui.ImportFilePickResult
import com.unifiedledger.ui.ImportPickRawRead
import com.unifiedledger.ui.PickedImportFile
import com.unifiedledger.ui.readImportPickBounded
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * P7-04.A desktop pick port (D-146 R-Q08-1, spec sections 4.1/4.1.3): the JDK-built-in
 * Swing `JFileChooser` plus `FileInputStream`. No new dependency, no file retention beyond
 * the user's own pick — the bounded read opens the file once, lazily, and the chooser keeps
 * no reference. The dialog and the stream opener are injected lambdas, so the port is
 * unit-testable headless; the product wiring passes [showSwingOpenFileChooser] and
 * `FileInputStream`. Results flow back through [onResult] — the composition root's result
 * channel, never the port signature (spec 4.1.1).
 */
internal class DesktopImportFilePickPort(
    private val onResult: (ImportFilePickResult) -> Unit,
    private val showOpenFileChooser: (description: String, extensions: Array<String>) -> File?,
    private val openInputStream: (File) -> InputStream?,
) : ImportFilePickPort {
    /** Launch the modal desktop file chooser for the request's format (extension-filtered). */
    override fun launch(request: ImportFilePickRequest) {
        val descriptor = ImportFormatCapabilities.byIdentifier(request.format)
        val file =
            try {
                showOpenFileChooser(descriptor.displayName, extensionFiltersFor(request))
            } catch (failure: Exception) {
                onResult(ImportFilePickResult.Failed(ImportFilePickFailure.PICKER_LAUNCH_FAILED))
                return
            }
        if (file == null) {
            onResult(ImportFilePickResult.Cancelled)
            return
        }
        val sizeBytes = file.length()
        onResult(
            ImportFilePickResult.Picked(
                PickedImportFile(
                    displayName = file.name,
                    sizeBytes = sizeBytes,
                    readBoundedBytes = { readBounded(file, sizeBytes) },
                ),
            ),
        )
    }

    /**
     * The L0 bounded-read adapter: the shared commonMain driver owns the frozen 16 MiB
     * semantics; this adapter only feeds it the platform stream closures. Thread contract:
     * synchronous; the host dispatches this closure on a non-UI thread (spec 4.1.2), so the
     * file read never runs on the Compose UI thread.
     */
    internal fun readBounded(
        file: File,
        sizeBytes: Long?,
    ): BoundedFileRead =
        readImportPickBounded(sizeBytes) {
            val stream = openInputStream(file) ?: throw IOException("no stream for the picked file")
            ImportPickRawRead(
                read = { buffer -> stream.read(buffer) },
                close = { stream.close() },
            )
        }

    /**
     * JFileChooser has no MIME filter surface; the matrix MIME filters map onto their
     * desktop file-extension equivalents. An unmapped MIME keeps the chooser's accept-all
     * default instead of blocking the pick — a wrong file still fails typed at parse.
     */
    private fun extensionFiltersFor(request: ImportFilePickRequest): Array<String> =
        request.mimeFilters
            .mapNotNull { MIME_FILE_EXTENSIONS[it] }
            .distinct()
            .toTypedArray()
}

/**
 * The real Swing pick dialog (spec 4.1.3): `JFileChooser`, FILES_ONLY, single selection,
 * one optional file-name-extension filter. Modal on the AWT event-dispatch thread — which
 * on Compose Desktop is also the Compose UI thread (the frozen Main.kt disclosure) — so a
 * launch from a UI event handler shows the dialog synchronously while the secondary Swing
 * event pump keeps the window alive; a launch from any other thread is routed through
 * [EventQueue.invokeAndWait] so the Swing threading contract always holds. Only this
 * dialog runs on the UI thread; the heavy bounded read stays lazy and off it (spec 4.1.2).
 */
internal fun showSwingOpenFileChooser(
    description: String,
    extensions: Array<String>,
): File? {
    val showDialog = {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        chooser.isMultiSelectionEnabled = false
        if (extensions.isNotEmpty()) {
            chooser.fileFilter = FileNameExtensionFilter(description, *extensions)
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
    return if (EventQueue.isDispatchThread()) {
        showDialog()
    } else {
        val chosen = arrayOfNulls<File>(1)
        EventQueue.invokeAndWait { chosen[0] = showDialog() }
        chosen[0]
    }
}

/** Matrix MIME filters -> desktop file extensions for the chooser filter. */
private val MIME_FILE_EXTENSIONS: Map<String, String> =
    mapOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "text/csv" to "csv",
        "application/csv" to "csv",
        "text/comma-separated-values" to "csv",
        "application/vnd.ms-excel" to "xls",
    )
