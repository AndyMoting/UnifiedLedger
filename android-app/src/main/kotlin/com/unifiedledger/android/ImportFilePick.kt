package com.unifiedledger.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.ImportFilePickFailure
import com.unifiedledger.ui.ImportFilePickPort
import com.unifiedledger.ui.ImportFilePickRequest
import com.unifiedledger.ui.ImportFilePickResult
import com.unifiedledger.ui.ImportPickRawRead
import com.unifiedledger.ui.PickedImportFile
import com.unifiedledger.ui.readImportPickBounded
import java.io.IOException
import java.io.InputStream

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
