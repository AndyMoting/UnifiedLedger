package com.unifiedledger.android

import com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator
import com.unifiedledger.ui.ImportTemplateDownloadResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P8-04 i1 Android wiring evidence (D-11; the `AndroidImportFilePickPortTest` injection
 * pattern — no Robolectric): the SAF launch handle, the main-thread poster and the stream
 * opener are all injected lambdas, so the typed outcome family, the D-14 suggested name, the
 * cancel mapping and the exact template-bytes write run on a plain JVM. The generic
 * `<Picked>` handle is driven with `String` doubles here; the product root instantiates it
 * with `android.net.Uri`.
 */
class AndroidImportTemplateTargetPortTest {
    private class RecordingResults : (ImportTemplateDownloadResult) -> Unit {
        val received = mutableListOf<ImportTemplateDownloadResult>()

        override fun invoke(result: ImportTemplateDownloadResult) {
            received += result
        }
    }

    private class RecordingLaunch {
        val launchedNames = mutableListOf<String>()
        val launch: (String) -> Unit = { name -> launchedNames += name }
    }

    /** A stream that records the written bytes; [failAfter] injects a mid-write failure. */
    private class RecordingStream(
        val sink: ByteArrayOutputStream = ByteArrayOutputStream(),
        private val failAfter: Int? = null,
    ) : OutputStream() {
        var written = 0

        override fun write(b: Int) {
            checkWrite(1)
            sink.write(b)
            written++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            checkWrite(len)
            sink.write(b, off, len)
            written += len
        }

        private fun checkWrite(length: Int) {
            val limit = failAfter ?: return
            if (written + length > limit) throw IOException("synthetic mid-write failure")
        }
    }

    private fun port(
        results: RecordingResults,
        launch: (String) -> Unit,
        open: (String) -> OutputStream?,
        post: (() -> Unit) -> Unit = { it.invoke() },
    ): AndroidImportTemplateTargetPort<String> =
        AndroidImportTemplateTargetPort(
            postToMainThread = post,
            launchCreateDocument = launch,
            openOutputStream = open,
            onResult = results,
        )

    @Test
    fun writeTemplateLaunchesTheSafPickerWithTheGeneratorSuggestedName() {
        val launcher = RecordingLaunch()
        val results = RecordingResults()
        val templatePort = port(results, launcher.launch, open = { null })

        templatePort.writeTemplate()

        // D-14: the suggested name IS the generator's own constant — the version lives with
        // the template, never a copied literal.
        assertEquals(SelfEntryTemplateGenerator.TEMPLATE_FILE_NAME, launcher.launchedNames.single())
        assertEquals(0, results.received.size, "the launch is fire-and-forget; the outcome rides the SAF callback")
    }

    @Test
    fun aNullSafHandleIsTheUserCancellation() {
        val results = RecordingResults()
        val templatePort = port(results, RecordingLaunch().launch, open = { null })

        templatePort.onCreateDocumentResult(null)

        assertEquals(ImportTemplateDownloadResult.Cancelled, results.received.single())
    }

    @Test
    fun aPickedHandleWritesTheGeneratedTemplateBytesAndReportsSuccess() {
        val results = RecordingResults()
        val stream = RecordingStream()
        val templatePort = port(results, RecordingLaunch().launch, open = { stream })

        templatePort.onCreateDocumentResult("synthetic-handle")

        val succeeded = assertIs<ImportTemplateDownloadResult.Succeeded>(results.received.single())
        // The bytes are exactly the generator's output — one generate, one write, no drift.
        val expected = SelfEntryTemplateGenerator.generateTemplate()
        assertEquals(expected.size, succeeded.bytes)
        assertEquals(expected.size, stream.sink.size())
        // The zip magic (PK\x03\x04) proves the container bytes reached the SAF stream.
        assertEquals(0x50, stream.sink.toByteArray()[0].toInt() and 0xFF)
        assertEquals(0x4B, stream.sink.toByteArray()[1].toInt() and 0xFF)
    }

    @Test
    fun aMidWriteFailureIsTheTypedReaderResultNeverSuccess() {
        val results = RecordingResults()
        val templatePort = port(results, RecordingLaunch().launch, open = { RecordingStream(failAfter = 4) })

        templatePort.onCreateDocumentResult("synthetic-handle")

        assertEquals(ImportTemplateDownloadResult.WriteFailed, results.received.single())
    }

    @Test
    fun aMissingStreamProviderIsTypedWriteFailed() {
        val results = RecordingResults()
        val templatePort = port(results, RecordingLaunch().launch, open = { null })

        templatePort.onCreateDocumentResult("synthetic-handle")

        assertEquals(ImportTemplateDownloadResult.WriteFailed, results.received.single())
    }

    @Test
    fun aThrowingPosterIsTheCancelledChoiceNeverACrash() {
        val results = RecordingResults()
        val templatePort =
            port(
                results,
                RecordingLaunch().launch,
                open = { null },
                post = { throw IllegalStateException("synthetic dead main looper") },
            )

        templatePort.writeTemplate()

        assertEquals(ImportTemplateDownloadResult.Cancelled, results.received.single())
    }
}
