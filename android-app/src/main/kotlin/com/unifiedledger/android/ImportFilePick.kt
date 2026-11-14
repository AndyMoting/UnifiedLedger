package com.unifiedledger.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator
import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.ImportFilePickFailure
import com.unifiedledger.ui.ImportFilePickPort
import com.unifiedledger.ui.ImportFilePickRequest
import com.unifiedledger.ui.ImportFilePickResult
import com.unifiedledger.ui.ImportPickRawRead
import com.unifiedledger.ui.ImportTemplateDownloadResult
import com.unifiedledger.ui.ImportTemplateTargetPort
import com.unifiedledger.ui.PickedImportFile
import com.unifiedledger.ui.readImportPickBounded
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF display metadata of one picked document: display/diagnostic only (D06 — the display
 * name never enters identity or persistence; a null size is honest metadata absence).
 */
internal class PickedSafFileMetadata(
    val displayName: String,
    val sizeBytes: Long?,
)

/**
 * P7-04.A Android pick port (D-146 R-Q08-1, spec sections 4.1/2.2 X-6): SAF
 * `ActivityResultContracts.OpenDocument` (MIME-filtered by the capability matrix) plus a
 * one-shot `ContentResolver.openInputStream` bounded read. No `takePersistableUriPermission`,
 * no file copy, no temp file — re-parsing means the user picks the file again.
 *
 * The SAF launch handle, the display-metadata resolver, and the stream opener are injected
 * lambdas, so JVM unit tests drive this port with plain `String` handles without Robolectric
 * (the `AndroidStartupControllerTest` injection pattern); the product root instantiates it
 * with `android.net.Uri`. Results flow back through [onResult] — the composition root's
 * result channel, never the port signature (spec 4.1.1). [readBounded] is the L0 adapter:
 * the shared commonMain driver owns the frozen 16 MiB semantics; this adapter only feeds it
 * the platform stream closures.
 */
internal class AndroidImportFilePickPort<Picked>(
    private val launchOpenDocument: (mimeFilters: Array<String>) -> Unit,
    private val resolveMetadata: (Picked) -> PickedSafFileMetadata,
    private val openInputStream: (Picked) -> InputStream?,
    private val onResult: (ImportFilePickResult) -> Unit,
) : ImportFilePickPort {
    /** Launch the SAF OpenDocument picker for the request's matrix MIME filters. */
    override fun launch(request: ImportFilePickRequest) {
        try {
            launchOpenDocument(request.mimeFilters.toTypedArray())
        } catch (failure: Exception) {
            onResult(ImportFilePickResult.Failed(ImportFilePickFailure.PICKER_LAUNCH_FAILED))
        }
    }

    /**
     * The SAF OpenDocument result callback: a null handle is the user's cancellation
     * (non-failure, zero diagnostics, spec 4.1.4). A picked handle resolves display
     * metadata immediately (picker metadata is lightweight; the heavy content read stays
     * lazy on the returned [PickedImportFile.readBoundedBytes] closure).
     */
    fun onOpenDocumentResult(picked: Picked?) {
        if (picked == null) {
            onResult(ImportFilePickResult.Cancelled)
            return
        }
        val metadata = resolveMetadata(picked)
        onResult(
            ImportFilePickResult.Picked(
                PickedImportFile(
                    displayName = metadata.displayName,
                    sizeBytes = metadata.sizeBytes,
                    readBoundedBytes = { readBounded(picked, metadata.sizeBytes) },
                ),
            ),
        )
    }

    /**
     * The L0 bounded-read adapter: open the picked document once and feed the raw reads to
     * the shared commonMain driver. Thread contract: synchronous; the host dispatches this
     * closure on a non-UI thread (spec 4.1.2) — `ContentResolver.openInputStream` is safe
     * there, and a revoked URI permission surfaces as the typed
     * [com.unifiedledger.ui.ImportPickReadFailure.STREAM_OPEN_FAILED], never raw text (D06).
     */
    internal fun readBounded(
        picked: Picked,
        sizeBytes: Long?,
    ): BoundedFileRead =
        readImportPickBounded(sizeBytes) {
            val stream =
                openInputStream(picked)
                    ?: throw IOException("no stream provider for the picked document")
            ImportPickRawRead(
                read = { buffer -> stream.read(buffer) },
                close = { stream.close() },
            )
        }
}

/**
 * P7-09 09.0 (spec section 6.3; D-225 Q19 P1): the SAF launcher slot shared between the
 * process-scoped runtime holder and the Activity composition.
 *
 * SAF launchers MUST be registered in the Activity's composition (before it is RESUMED) and they
 * are Activity-scoped, while the runtime owner is now PROCESS-scoped. The slot decouples the two:
 * the composition writes its launcher functions and result deliveries here on every composition
 * (so an Activity recreation re-points them), and the process-scoped port reads them at launch
 * time. A null launcher is an honest "no picker registered yet" — the port then reports its
 * existing typed launch failure rather than crashing.
 *
 * No platform object escapes: the slot stays in this composition root, and `common` sees only the
 * neutral result family (spec section 2.1).
 */
internal class AndroidSafLaunchSlot {
    /** Launches the import OpenDocument picker for the given MIME filters. */
    var launchOpenDocument: ((Array<String>) -> Unit)? = null

    /** Launches the backup-export CreateDocument picker for the suggested name. */
    var launchCreateDocument: ((String) -> Unit)? = null

    /** P8-04 i1 (D-11): launches the import-template CreateDocument picker for the suggested name. */
    var launchTemplateCreateDocument: ((String) -> Unit)? = null

    /** Launches the restore OpenDocument picker for the given MIME filters. */
    var launchRestoreSource: ((Array<String>) -> Unit)? = null

    /** Delivers the import picker's result back into the process-scoped pick port. */
    var deliverOpenDocumentResult: ((Uri?) -> Unit)? = null

    /** Delivers the backup CreateDocument result back into the process-scoped target port. */
    var deliverCreateDocumentResult: ((Uri?) -> Unit)? = null

    /** P8-04 i1 (D-11): delivers the template CreateDocument result back into the template port. */
    var deliverTemplateCreateDocumentResult: ((Uri?) -> Unit)? = null

    /** Delivers the restore OpenDocument result back into the process-scoped source port. */
    var deliverRestoreSourceResult: ((Uri?) -> Unit)? = null
}

/**
 * P8-04 i1 (D-11; spec `docs/specs/2026-11-14-p8-04-self-entry-import-design.md`): the Android
 * save-target port for the generated import template. SAF
 * `ActivityResultContracts.CreateDocument` picks the destination and
 * `ContentResolver.openOutputStream` receives the bytes generated by
 * `SelfEntryTemplateGenerator` (ledger-application jvmMain — visible here, D-11's runtime
 * generation; the D-14 suggested name comes from the generator's own constant, never a copy).
 *
 * SHAPES: the launch/deliver pair mirrors the `AndroidBackupTargetPort` CreateDocument flow,
 * but WITHOUT its latch — the template download is a fire-and-forget user action from the UI
 * thread (the `ImportTemplateTargetPort` contract), so `writeTemplate` posts the SAF launch and
 * returns immediately; the SAF callback runs the generate + write on the delivered result and
 * reports the typed outcome through the injected `onResult` (the
 * `AndroidImportFilePickPort` injection pattern: all closures, JVM-testable without
 * Robolectric).
 *
 * THREADING: SAF launchers are main-thread only; the caller (the shared host) is already on the
 * composition's main dispatcher, so the launch runs inline. The generate + write runs on the
 * posted launcher's callback thread (SAF delivers on main); the write is a few KiB — bounded by
 * the generator's own small output — and a failure maps to the typed
 * [ImportTemplateDownloadResult.WriteFailed] with the cause class only in the trace (D06: no
 * raw exception text, no paths, no URIs).
 */
internal class AndroidImportTemplateTargetPort<Picked>(
    /** Posts [launchCreateDocument] (SAF launchers are main-thread only; the poster keeps the seam testable). */
    private val postToMainThread: ((() -> Unit) -> Unit),
    private val launchCreateDocument: (suggestedName: String) -> Unit,
    private val openOutputStream: (Picked) -> OutputStream?,
    private val onResult: (ImportTemplateDownloadResult) -> Unit,
) : ImportTemplateTargetPort {
    /** Launches the template save (fire-and-forget; the outcome arrives through [onResult]). */
    override fun writeTemplate() {
        try {
            postToMainThread { launchCreateDocument(SelfEntryTemplateGenerator.TEMPLATE_FILE_NAME) }
        } catch (failure: Exception) {
            // A throwing poster (e.g. a dead main looper) is a cancelled choice, never a crash
            // (the AndroidBackupTargetPort.openTarget mapping).
            onResult(ImportTemplateDownloadResult.Cancelled)
        }
    }

    /**
     * The SAF CreateDocument result callback: a null handle is the user's cancellation
     * (non-failure); a picked handle generates the template and writes it in one pass, reporting
     * the typed outcome. Success ONLY after a clean flush + close (the BackupTargetWriter
     * commit discipline; no success-claiming artifact on a failure).
     */
    fun onCreateDocumentResult(picked: Picked?) {
        if (picked == null) {
            onResult(ImportTemplateDownloadResult.Cancelled)
            return
        }
        val result =
            try {
                val bytes = SelfEntryTemplateGenerator.generateTemplate()
                val stream =
                    openOutputStream(picked)
                        ?: throw IOException("no stream provider for the template document")
                stream.use { output ->
                    output.write(bytes)
                    output.flush()
                }
                ImportTemplateDownloadResult.Succeeded(bytes.size)
            } catch (failure: Exception) {
                // StartupTrace carries exception CLASS NAMES only (its contract: no paths, no raw
                // exception text), so the failure rides the event string, not a separate API.
                StartupTrace.emit("import.templateDownload android write failed causeClass=${failure::class.simpleName}")
                ImportTemplateDownloadResult.WriteFailed
            }
        onResult(result)
    }
}

/**
 * Resolves the SAF display metadata of a picked document (D06: display/diagnostic only).
 * The query is read-only; any failure or absence resolves to honest empty metadata instead
 * of failing the pick — the bounded read is independent of display metadata, and a missing
 * display name never blocks reading the user's own file.
 */
internal fun resolveSafFileMetadata(
    resolver: ContentResolver,
    uri: Uri,
): PickedSafFileMetadata {
    val metadata =
        try {
            resolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return@use null
                    }
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    PickedSafFileMetadata(
                        displayName =
                            if (nameIndex >= 0) {
                                cursor.getString(nameIndex).orEmpty()
                            } else {
                                ""
                            },
                        sizeBytes =
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                                cursor.getLong(sizeIndex)
                            } else {
                                null
                            },
                    )
                }
        } catch (failure: Exception) {
            null
        }
    return metadata ?: PickedSafFileMetadata(displayName = "", sizeBytes = null)
}
