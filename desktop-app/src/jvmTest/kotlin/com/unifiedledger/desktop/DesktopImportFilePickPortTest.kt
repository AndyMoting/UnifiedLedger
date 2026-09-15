package com.unifiedledger.desktop

import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.IMPORT_FILE_PICK_MAX_READ_BYTES
import com.unifiedledger.ui.ImportFilePickRequest
import com.unifiedledger.ui.ImportFilePickResult
import com.unifiedledger.ui.ImportPickReadFailure
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-04.A desktop pick-port unit evidence (D-146 R-Q08-1, spec section 4.1): the Swing
 * dialog and the stream opener are injected lambdas, so the typed result family, the
 * file-name/size passthrough, the matrix MIME -> file-extension filter mapping, and the L0
 * bounded-read adapter run headless — the real JFileChooser is product wiring and is never
 * instantiated here (CI has no display).
 */
class DesktopImportFilePickPortTest {
    private class RecordingResults : (ImportFilePickResult) -> Unit {
        val received = mutableListOf<ImportFilePickResult>()

        override fun invoke(result: ImportFilePickResult) {
            received += result
        }
    }

    private class RecordingDialog {
        val descriptions = mutableListOf<String>()
        val extensions = mutableListOf<Array<String>>()

        val dialog: (String, Array<String>) -> File? = { description, extensions ->
            descriptions += description
            this.extensions += extensions
            null
        }
    }

    private fun port(
        results: RecordingResults,
        dialog: (String, Array<String>) -> File?,
        open: (File) -> InputStream? = { FileInputStream(it) },
    ): DesktopImportFilePickPort = DesktopImportFilePickPort(onResult = results, showOpenFileChooser = dialog, openInputStream = open)

    @Test
    fun dismissedDialogYieldsTheTypedCancellation() {
        val results = RecordingResults()
        val pickPort = port(results, RecordingDialog().dialog)

        pickPort.launch(
            ImportFilePickRequest(
                format = ImportFormatCapabilities.CMB_CSV.identifier,
                mimeFilters = ImportFormatCapabilities.CMB_CSV.mimeFilters,
            ),
        )

        assertEquals(ImportFilePickResult.Cancelled, results.received.single())
    }

    @Test
    fun dialogLaunchFailureIsTheTypedPickerLaunchFailed() {
        val results = RecordingResults()
        val pickPort =
            port(
                results,
                dialog = { _, _ -> throw IOException("synthetic headless dialog failure") },
            )

        pickPort.launch(
            ImportFilePickRequest(
                format = ImportFormatCapabilities.CMB_CSV.identifier,
                mimeFilters = ImportFormatCapabilities.CMB_CSV.mimeFilters,
            ),
        )

        val failed = assertIs<ImportFilePickResult.Failed>(results.received.single())
        assertEquals(com.unifiedledger.ui.ImportFilePickFailure.PICKER_LAUNCH_FAILED, failed.reason)
    }

    @Test
    fun pickedFileCarriesItsNameAndReadsExactBoundedBytes() {
        val payload = ByteArray(150_000) { index -> (index % 251).toByte() }
        val file = File.createTempFile("p704-import-pick", ".csv")
        file.writeBytes(payload)
        file.deleteOnExit()
        val results = RecordingResults()

        val pickPort =
            port(
                results,
                dialog = { _, _ -> file },
                open = { FileInputStream(it) },
            )

        pickPort.launch(
            ImportFilePickRequest(
                format = ImportFormatCapabilities.CMB_CSV.identifier,
                mimeFilters = ImportFormatCapabilities.CMB_CSV.mimeFilters,
            ),
        )

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        assertEquals(file.name, picked.file.displayName)
        assertEquals(payload.size.toLong(), picked.file.sizeBytes)
        val bytes = assertIs<BoundedFileRead.Bytes>(picked.file.readBoundedBytes())
        assertContentEquals(payload, bytes.bytes)
    }

    @Test
    fun overLimitFileSizeShortCircuitsTheReadWithoutOpening() {
        val file = File.createTempFile("p704-import-pick-over", ".csv")
        file.deleteOnExit()
        RandomAccessFile(file, "rw").use { it.setLength(IMPORT_FILE_PICK_MAX_READ_BYTES + 1) }
        val results = RecordingResults()
        var openCount = 0

        val pickPort =
            port(
                results,
                dialog = { _, _ -> file },
                open = {
                    openCount++
                    FileInputStream(it)
                },
            )

        pickPort.launch(
            ImportFilePickRequest(
                format = ImportFormatCapabilities.CMB_CSV.identifier,
                mimeFilters = ImportFormatCapabilities.CMB_CSV.mimeFilters,
            ),
        )

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        val exceeded = assertIs<BoundedFileRead.ExceedsLimit>(picked.file.readBoundedBytes())
        assertEquals(IMPORT_FILE_PICK_MAX_READ_BYTES + 1, exceeded.actualBytes)
        assertEquals(0, openCount)
    }

    @Test
    fun matrixMimeFiltersMapOntoFileExtensionFilters() {
        val results = RecordingResults()
        val dialog = RecordingDialog()
        val pickPort = port(results, dialog.dialog)

        ImportFormatCapabilities.ALL.forEach { descriptor ->
            pickPort.launch(ImportFilePickRequest(descriptor.identifier, descriptor.mimeFilters))
        }

        val expectedExtensions =
            ImportFormatCapabilities.ALL.map { descriptor ->
                when (descriptor.identifier) {
                    ImportFormatCapabilities.WECHAT_XLSX.identifier -> arrayOf("xlsx")
                    ImportFormatCapabilities.ALIPAY_CSV.identifier -> arrayOf("csv")
                    ImportFormatCapabilities.CMB_CSV.identifier -> arrayOf("csv")
                    ImportFormatCapabilities.CCB_XLS.identifier -> arrayOf("xls")
                    else -> error("synthetic test expects one of the four frozen matrix formats: ${descriptor.identifier}")
                }
            }
        ImportFormatCapabilities.ALL.forEachIndexed { index, descriptor ->
            assertEquals(descriptor.displayName, dialog.descriptions[index])
            assertContentEquals(expectedExtensions[index], dialog.extensions[index])
        }
    }

    @Test
    fun unmappedMimeFallsBackToTheAcceptAllDialog() {
        val results = RecordingResults()
        val dialog = RecordingDialog()
        val pickPort = port(results, dialog.dialog)

        pickPort.launch(
            ImportFilePickRequest(
                format = ImportFormatCapabilities.CMB_CSV.identifier,
                mimeFilters = listOf("application/x-synthetic-unmapped"),
            ),
        )

        assertEquals(0, dialog.extensions.single().size)
        assertEquals(ImportFilePickResult.Cancelled, results.received.single())
    }

    @Test
    fun streamOpenFailureIsTheTypedLifecycleFailure() {
        val file = File.createTempFile("p704-import-pick-missing", ".csv")
        file.deleteOnExit()
        val results = RecordingResults()

        val pickPort =
            port(
                results,
                dialog = { _, _ -> file },
                open = { throw IOException("synthetic open failure") },
            )

        pickPort.launch(
            ImportFilePickRequest(
                format = ImportFormatCapabilities.CMB_CSV.identifier,
                mimeFilters = ImportFormatCapabilities.CMB_CSV.mimeFilters,
            ),
        )

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        val failed = assertIs<BoundedFileRead.ReadFailed>(picked.file.readBoundedBytes())
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, failed.reason)
    }

    @Test
    fun midReadFailureIsTheTypedReadFailure() {
        val file = File.createTempFile("p704-import-pick-broken", ".csv")
        file.deleteOnExit()
        val results = RecordingResults()
        val brokenStream =
            object : InputStream() {
                var closed = false

                override fun read(): Int = throw IOException("synthetic read failure")

                override fun read(buffer: ByteArray): Int = throw IOException("synthetic read failure")

                override fun close() {
                    closed = true
                }
            }

        val pickPort =
            port(
                results,
                dialog = { _, _ -> file },
                open = { brokenStream },
            )

        pickPort.launch(
            ImportFilePickRequest(
                format = ImportFormatCapabilities.CMB_CSV.identifier,
                mimeFilters = ImportFormatCapabilities.CMB_CSV.mimeFilters,
            ),
        )

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        val failed = assertIs<BoundedFileRead.ReadFailed>(picked.file.readBoundedBytes())
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, failed.reason)
        assertTrue(brokenStream.closed)
    }
}
