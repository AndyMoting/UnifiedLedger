package com.unifiedledger.android

import com.unifiedledger.application.ImportFormatId
import com.unifiedledger.ui.BoundedFileRead
import com.unifiedledger.ui.IMPORT_FILE_PICK_MAX_READ_BYTES
import com.unifiedledger.ui.ImportFilePickRequest
import com.unifiedledger.ui.ImportFilePickResult
import com.unifiedledger.ui.ImportPickReadFailure
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-04.A Android pick-port unit evidence (D-146 R-Q08-1, spec section 4.1): no Robolectric —
 * the SAF launch handle, the display-metadata resolver, and the stream opener are all
 * injected lambdas (the `AndroidStartupControllerTest` injection pattern), so the typed
 * result family, the metadata passthrough, and the L0 bounded-read wiring (size short
 * circuit, typed lifecycle/read failures, exact bytes) run on a plain JVM. The generic
 * `<Picked>` handle is driven with `String` doubles here; the product root instantiates it
 * with `android.net.Uri`.
 */
class AndroidImportFilePickPortTest {
    private class RecordingResults : (ImportFilePickResult) -> Unit {
        val received = mutableListOf<ImportFilePickResult>()

        override fun invoke(result: ImportFilePickResult) {
            received += result
        }
    }

    private class RecordingLaunch {
        val launchedMimeFilters = mutableListOf<Array<String>>()
        val launch: (Array<String>) -> Unit = { mimes -> launchedMimeFilters += mimes }
    }

    private fun port(
        results: RecordingResults,
        launch: (Array<String>) -> Unit,
        metadata: (String) -> PickedSafFileMetadata = { PickedSafFileMetadata("synthetic-statement.csv", null) },
        open: (String) -> InputStream? = { ByteArrayInputStream(ByteArray(0)) },
    ): AndroidImportFilePickPort<String> =
        AndroidImportFilePickPort(
            launchOpenDocument = launch,
            resolveMetadata = metadata,
            openInputStream = open,
            onResult = results,
        )

    private fun request(vararg mimes: String): ImportFilePickRequest =
        ImportFilePickRequest(
            format = ImportFormatId("cmb-csv"),
            mimeFilters = mimes.toList(),
        )

    @Test
    fun launchForwardsTheMatrixMimeFiltersToTheSafLauncher() {
        val launcher = RecordingLaunch()
        val results = RecordingResults()
        val pickPort = port(results, launcher.launch)

        pickPort.launch(request("text/csv", "application/csv"))

        assertContentEquals(arrayOf("text/csv", "application/csv"), launcher.launchedMimeFilters.single())
        assertEquals(0, results.received.size)
    }

    @Test
    fun launchFailureIsTheTypedPickerLaunchFailed() {
        val results = RecordingResults()
        val pickPort =
            port(results, launch = { throw SecurityException("synthetic launch failure") })

        pickPort.launch(request("text/csv"))

        val failed = assertIs<ImportFilePickResult.Failed>(results.received.single())
        assertEquals(com.unifiedledger.ui.ImportFilePickFailure.PICKER_LAUNCH_FAILED, failed.reason)
    }

    @Test
    fun nullSafHandleIsTheUserCancellation() {
        val results = RecordingResults()
        val pickPort = port(results, RecordingLaunch().launch)

        pickPort.onOpenDocumentResult(null)

        assertEquals(ImportFilePickResult.Cancelled, results.received.single())
    }

    @Test
    fun pickedFileCarriesDisplayMetadataAndReadsExactBoundedBytes() {
        val payload = ByteArray(100_000) { index -> (index % 253).toByte() }
        val results = RecordingResults()
        val pickPort =
            port(
                results,
                RecordingLaunch().launch,
                metadata = { PickedSafFileMetadata("synthetic-statement.csv", payload.size.toLong()) },
                open = { ByteArrayInputStream(payload) },
            )

        pickPort.onOpenDocumentResult("synthetic-handle")

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        assertEquals("synthetic-statement.csv", picked.file.displayName)
        assertEquals(payload.size.toLong(), picked.file.sizeBytes)
        val bytes = assertIs<BoundedFileRead.Bytes>(picked.file.readBoundedBytes())
        assertContentEquals(payload, bytes.bytes)
    }

    @Test
    fun knownOverLimitSizeShortCircuitsTheBoundedReadWithoutOpening() {
        val overLimit = IMPORT_FILE_PICK_MAX_READ_BYTES + 5
        val results = RecordingResults()
        var openCount = 0
        val pickPort =
            port(
                results,
                RecordingLaunch().launch,
                metadata = { PickedSafFileMetadata("synthetic-statement.csv", overLimit) },
                open = {
                    openCount++
                    ByteArrayInputStream(ByteArray(0))
                },
            )

        pickPort.onOpenDocumentResult("synthetic-handle")

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        val exceeded = assertIs<BoundedFileRead.ExceedsLimit>(picked.file.readBoundedBytes())
        assertEquals(overLimit, exceeded.actualBytes)
        assertEquals(0, openCount)
    }

    @Test
    fun missingStreamProviderIsTheTypedLifecycleFailure() {
        val results = RecordingResults()
        val pickPort =
            port(
                results,
                RecordingLaunch().launch,
                metadata = { PickedSafFileMetadata("synthetic-statement.csv", null) },
                open = { null },
            )

        pickPort.onOpenDocumentResult("synthetic-handle")

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        val failed = assertIs<BoundedFileRead.ReadFailed>(picked.file.readBoundedBytes())
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, failed.reason)
    }

    @Test
    fun midReadFailureIsTheTypedReadFailure() {
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
                RecordingLaunch().launch,
                metadata = { PickedSafFileMetadata("synthetic-statement.csv", null) },
                open = { brokenStream },
            )

        pickPort.onOpenDocumentResult("synthetic-handle")

        val picked = assertIs<ImportFilePickResult.Picked>(results.received.single())
        val failed = assertIs<BoundedFileRead.ReadFailed>(picked.file.readBoundedBytes())
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, failed.reason)
        assertTrue(brokenStream.closed)
    }
}
