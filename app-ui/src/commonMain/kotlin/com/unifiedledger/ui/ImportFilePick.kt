package com.unifiedledger.ui

import com.unifiedledger.application.ImportFormatId

/** The frozen L0 platform read bound (R-Q08-2, spec 3.1.2): at most 16 MiB per picked file. */
const val IMPORT_FILE_PICK_MAX_READ_BYTES: Long = 16L * 1024L * 1024L

/**
 * One platform file-pick request (spec 4.1.1): the matrix format identifier plus its MIME
 * filter set, both taken from `ImportFormatCapabilities`. The platform picker filters on
 * the MIME set (SAF) or its best local equivalent (desktop file-name extensions).
 */
class ImportFilePickRequest(
    val format: ImportFormatId,
    val mimeFilters: List<String>,
)

/**
 * The result of one platform pick, delivered by the composition root back to the shared
 * coordinator (spec 4.1.1). [Cancelled] is the user's cancellation — a non-failure with zero
 * diagnostic noise; [Failed] is a typed platform launch failure (permission/availability),
 * never a silent drop.
 */
sealed interface ImportFilePickResult {
    /** The user picked a file; the bounded read stays lazy on [PickedImportFile.readBoundedBytes]. */
    class Picked(
        val file: PickedImportFile,
    ) : ImportFilePickResult

    /** The user dismissed the picker; no file, no diagnostics (spec 4.1.4). */
    data object Cancelled : ImportFilePickResult

    /** The platform picker itself failed to launch (typed; raw exceptions never leak, D06). */
    class Failed(
        val reason: ImportFilePickFailure,
    ) : ImportFilePickResult
}

/** Typed reasons a platform picker failed to launch (spec 4.1.1 "平台失败事件"). */
enum class ImportFilePickFailure {
    /** The platform pick intent/dialog could not be launched (no handler, permission, headless). */
    PICKER_LAUNCH_FAILED,
}

/**
 * One picked file (spec 4.1.1). [displayName] is display/diagnostic metadata only (D06:
 * never an identity, never persisted); [sizeBytes] is the picker/filesystem size metadata
 * when the platform reports one; [readBoundedBytes] is the lazy L0 bounded-read closure
 * implemented by the composition root and executed on the dispatch thread (non-UI, spec
 * 4.1.2).
 */
class PickedImportFile(
    val displayName: String,
    val sizeBytes: Long?,
    val readBoundedBytes: () -> BoundedFileRead,
)

/**
 * The L0 bounded-read outcome (R-Q08-2, spec 4.1.1/4.6): the bytes, or a typed over-limit
 * failure carrying the actual received count, or a typed read failure. Never a silent
 * truncation: an over-limit file is reported, not clipped.
 */
sealed interface BoundedFileRead {
    /** The file content read within the 16 MiB bound; exactly the received bytes. */
    class Bytes(
        val bytes: ByteArray,
    ) : BoundedFileRead

    /**
     * The file exceeds the L0 bound. [actualBytes] is the known size metadata when the
     * short circuit fired before any read, otherwise the received count (16 MiB + 1).
     */
    class ExceedsLimit(
        val actualBytes: Long,
    ) : BoundedFileRead

    /** A typed stream failure; the underlying exception text/path/URI never leaks (D06). */
    class ReadFailed(
        val reason: ImportPickReadFailure,
    ) : BoundedFileRead
}

/** Typed stream-failure reasons for [BoundedFileRead.ReadFailed]. */
enum class ImportPickReadFailure {
    /** Opening or closing the raw stream failed (lifecycle; includes revoked URI permission). */
    STREAM_OPEN_FAILED,

    /** Reading the raw stream failed mid-way. */
    STREAM_READ_FAILED,
}

/**
 * Platform file-pick port (D-146 Q08/R-Q08-1, spec section 4.1; frozen shape): ask the
 * platform to start the picker for [request]. Fire-and-forget about the result only — the
 * pick result flows back through the composition root's result channel as
 * [ImportFilePickResult], never through this method's signature. The call itself may block
 * (AB-CLI-QUAL-05): the desktop adapter shows a modal `JFileChooser` and does not return
 * until the user closes the dialog, so consumers must treat [launch] as possibly blocking
 * the (UI) calling thread; the Android SAF adapter starts the system picker and returns
 * immediately, with results arriving on its callback.
 *
 * P7-04.A shared-surface contract: app-ui commonMain declares this port and its value
 * types only; every platform surface (SAF, ContentResolver, JFileChooser, FileInputStream)
 * stays in the platform composition roots. commonMain has no java.io and no platform source
 * set, so no `InputStream`-shaped signature exists here — the picked file carries a lazy
 * bounded-read closure ([PickedImportFile.readBoundedBytes]) implemented by the composition
 * root and executed on the dispatch thread, and the platform feeds raw reads back to the
 * shared helper [readImportPickBounded] through the closure pair [ImportPickRawRead]. D06:
 * the display name is for the current session's display/diagnostics only and never enters
 * identity or persistence; typed diagnostics never leak raw exception text, paths, or URIs.
 */
interface ImportFilePickPort {
    /** Launch the platform file picker for the request's format (MIME-filtered). */
    fun launch(request: ImportFilePickRequest)
}

/**
 * The raw read pair a platform adapter feeds the shared L0 helper: [read] fills the given
 * buffer and returns the byte count read, or a non-positive count at end of stream (the
 * InputStream convention); [close] releases the platform resource. Closures only — this is
 * the commonMain shape of a platform stream, never a java.io signature.
 */
class ImportPickRawRead(
    val read: (buffer: ByteArray) -> Int,
    val close: () -> Unit,
)

/**
 * Shared L0 bounded-read driver (R-Q08-2, spec 3.1.2 row L0; the frozen logic, unit-tested
 * once in commonTest and fed by both platform adapters):
 *
 * 1. known size metadata over [IMPORT_FILE_PICK_MAX_READ_BYTES] short-circuits to
 *    [BoundedFileRead.ExceedsLimit] with the known size — zero read, zero open;
 * 2. otherwise at most limit+1 bytes are read; receiving the full limit+1 means the file is
 *    over the bound, so the read stops and [BoundedFileRead.ExceedsLimit] carries the
 *    received count — never a silent truncation;
 * 3. first failure wins (AB-CLI-QUAL-01): a mid-read failure is typed
 *    [ImportPickReadFailure.STREAM_READ_FAILED] and survives a close failure of the same
 *    source; an open failure — or a close failure after the read already completed
 *    determinately ([BoundedFileRead.Bytes] or [BoundedFileRead.ExceedsLimit], where the
 *    close is the only failure) — is typed
 *    [ImportPickReadFailure.STREAM_OPEN_FAILED]; the raw exception never leaks (D06).
 *
 * Thread contract: synchronous and stateless, safe on any single thread; the composition
 * root hands this closure to the host, which dispatches it on a non-UI thread (spec 4.1.2).
 */
fun readImportPickBounded(
    sizeBytes: Long?,
    openStream: () -> ImportPickRawRead,
): BoundedFileRead {
    if (sizeBytes != null && sizeBytes > IMPORT_FILE_PICK_MAX_READ_BYTES) {
        return BoundedFileRead.ExceedsLimit(sizeBytes)
    }
    val raw =
        try {
            openStream()
        } catch (failure: Exception) {
            return BoundedFileRead.ReadFailed(ImportPickReadFailure.STREAM_OPEN_FAILED)
        }
    // The read loop maps its own failures internally; this net keeps any unexpected
    // read-path throw on the read side of the first-failure-wins order below.
    val outcome =
        try {
            readImportPickChunks(raw.read)
        } catch (failure: Exception) {
            BoundedFileRead.ReadFailed(ImportPickReadFailure.STREAM_READ_FAILED)
        }
    val closeFailed =
        try {
            raw.close()
            false
        } catch (failure: Exception) {
            true
        }
    return if (closeFailed && outcome !is BoundedFileRead.ReadFailed) {
        // Close is the only failure (the read completed determinately), so it is reported.
        BoundedFileRead.ReadFailed(ImportPickReadFailure.STREAM_OPEN_FAILED)
    } else {
        // The read verdict survives: Bytes/ExceedsLimit pass through on a clean close, and a
        // mid-read ReadFailed keeps its primary cause even if closing also failed.
        outcome
    }
}

/** The chunked read loop: collects at most limit+1 bytes, then reports the typed outcome. */
private fun readImportPickChunks(readChunk: (ByteArray) -> Int): BoundedFileRead {
    val maxTotal = (IMPORT_FILE_PICK_MAX_READ_BYTES + 1L).toInt()
    var collected = ByteArray(minOf(IMPORT_PICK_READ_CHUNK_BYTES, maxTotal))
    var total = 0
    while (total < maxTotal) {
        try {
            val chunk = ByteArray(minOf(IMPORT_PICK_READ_CHUNK_BYTES, maxTotal - total))
            val read = readChunk(chunk)
            if (read <= 0) {
                return BoundedFileRead.Bytes(collected.copyOf(total))
            }
            if (read > chunk.size) {
                // A source reporting more bytes than the buffer holds violates the read
                // contract; fail typed instead of trusting a miscount.
                return BoundedFileRead.ReadFailed(ImportPickReadFailure.STREAM_READ_FAILED)
            }
            if (total + read > collected.size) {
                collected =
                    collected.copyOf(
                        minOf(
                            maxOf(collected.size * 2, total + read),
                            maxTotal,
                        ),
                    )
            }
            chunk.copyInto(collected, destinationOffset = total, startIndex = 0, endIndex = read)
            total += read
        } catch (failure: Exception) {
            return BoundedFileRead.ReadFailed(ImportPickReadFailure.STREAM_READ_FAILED)
        }
    }
    return BoundedFileRead.ExceedsLimit(maxTotal.toLong())
}

/** Read-request granularity of the L0 loop; the growth buffer doubles from this size. */
private const val IMPORT_PICK_READ_CHUNK_BYTES: Int = 64 * 1024
