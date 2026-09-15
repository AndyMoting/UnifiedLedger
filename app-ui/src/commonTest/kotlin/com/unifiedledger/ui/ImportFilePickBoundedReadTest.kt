package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P7-04.A L0 bounded-read helper tests (D-146 R-Q08-1/R-Q08-2, spec section 4.1).
 *
 * Pins the frozen L0 semantics against injected closures only (commonMain has no java.io
 * surface): the 16 MiB byte bound, the size-metadata short circuit (zero read), the
 * read-at-most-limit+1 loop with the typed over-limit failure carrying the actual received
 * count, exact content preservation (never a silent truncation), and the typed
 * open/read/close failure mapping that never leaks the underlying exception text.
 */
class ImportFilePickBoundedReadTest {
    /** Raw source double over anonymous synthetic bytes; platform streams adapt the same shape. */
    private class FakeSource(
        private val data: ByteArray,
        private val maxPerRead: Int = Int.MAX_VALUE,
        private val readFailure: RuntimeException? = null,
        private val closeFailure: RuntimeException? = null,
    ) {
        var position = 0
        var closed = false

        val raw =
            ImportPickRawRead(
                read = { buffer ->
                    readFailure?.let { throw it }
                    if (position >= data.size) {
                        -1
                    } else {
                        val count = minOf(minOf(buffer.size, maxPerRead), data.size - position)
                        data.copyInto(buffer, destinationOffset = 0, startIndex = position, endIndex = position + count)
                        position += count
                        count
                    }
                },
                close = {
                    closed = true
                    closeFailure?.let { throw it }
                },
            )
    }

    private fun synthetic(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }

    @Test
    fun knownOverLimitSizeShortCircuitsWithoutOpening() {
        val overLimit = IMPORT_FILE_PICK_MAX_READ_BYTES + 1
        var opened = false

        val outcome =
            readImportPickBounded(sizeBytes = overLimit) {
                opened = true
                throw IllegalStateException("must not open for a known-over-limit size")
            }

        val exceeded = assertIs<BoundedFileRead.ExceedsLimit>(outcome)
        assertEquals(overLimit, exceeded.actualBytes)
        assertFalse(opened)
    }

    @Test
    fun knownSizeAtExactLimitProceedsToRead() {
        val payload = synthetic(IMPORT_FILE_PICK_MAX_READ_BYTES.toInt())
        val source = FakeSource(payload)

        val outcome = readImportPickBounded(sizeBytes = IMPORT_FILE_PICK_MAX_READ_BYTES) { source.raw }

        val bytes = assertIs<BoundedFileRead.Bytes>(outcome)
        assertContentEquals(payload, bytes.bytes)
    }

    @Test
    fun unknownSizeSmallFileReadsExactBytesAcrossChunks() {
        val payload = synthetic(150_000)
        val source = FakeSource(payload, maxPerRead = 64)

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val bytes = assertIs<BoundedFileRead.Bytes>(outcome)
        assertContentEquals(payload, bytes.bytes)
    }

    @Test
    fun emptyStreamYieldsEmptyBytesNotATruncation() {
        val source = FakeSource(ByteArray(0))

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val bytes = assertIs<BoundedFileRead.Bytes>(outcome)
        assertEquals(0, bytes.bytes.size)
    }

    @Test
    fun unknownSizeOverLimitStopsAtLimitPlusOneWithActualReceivedCount() {
        val source = FakeSource(synthetic(IMPORT_FILE_PICK_MAX_READ_BYTES.toInt() + 2), maxPerRead = 1 shl 20)

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val exceeded = assertIs<BoundedFileRead.ExceedsLimit>(outcome)
        assertEquals(IMPORT_FILE_PICK_MAX_READ_BYTES + 1, exceeded.actualBytes)
        // The loop stops at limit+1 received bytes; the remaining stream content is never read.
        assertEquals(IMPORT_FILE_PICK_MAX_READ_BYTES.toInt() + 1, source.position)
    }

    @Test
    fun fileOfExactlyLimitPlusOneBytesIsOverLimit() {
        val source = FakeSource(synthetic(IMPORT_FILE_PICK_MAX_READ_BYTES.toInt() + 1), maxPerRead = 1 shl 20)

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val exceeded = assertIs<BoundedFileRead.ExceedsLimit>(outcome)
        assertEquals(IMPORT_FILE_PICK_MAX_READ_BYTES + 1, exceeded.actualBytes)
    }

    @Test
    fun openFailureIsTypedOpenFailure() {
        val outcome =
            readImportPickBounded(sizeBytes = null) {
                throw IllegalStateException("synthetic open failure")
            }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, failed.reason)
    }

    @Test
    fun midReadFailureIsTypedReadFailureAndStillClosesTheSource() {
        val source = FakeSource(ByteArray(0), readFailure = IllegalStateException("synthetic read failure"))

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, failed.reason)
        assertEquals(true, source.closed)
    }

    @Test
    fun closeFailureAfterASuccessfulReadIsTypedLifecycleFailure() {
        val source = FakeSource(synthetic(10), closeFailure = IllegalStateException("synthetic close failure"))

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, failed.reason)
    }

    /**
     * AB-CLI-QUAL-01 (a): first failure wins — the mid-read primary cause survives an
     * additional close failure; the combined case must not be re-reported as
     * [ImportPickReadFailure.STREAM_OPEN_FAILED].
     */
    @Test
    fun midReadFailureSurvivesAnAdditionalCloseFailure() {
        val source =
            FakeSource(
                ByteArray(0),
                readFailure = IllegalStateException("synthetic read failure"),
                closeFailure = IllegalStateException("synthetic close failure"),
            )

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, failed.reason)
        assertEquals(true, source.closed)
    }

    /**
     * AB-CLI-QUAL-01 (a), registered corner: [BoundedFileRead.ExceedsLimit] counts as a
     * determinate read completion (the loop read limit+1 bytes successfully), so a close
     * failure after it is the only failure and is reported as the typed lifecycle failure.
     */
    @Test
    fun closeFailureAfterAnOverLimitReadIsTheTypedLifecycleFailure() {
        val source =
            FakeSource(
                synthetic(IMPORT_FILE_PICK_MAX_READ_BYTES.toInt() + 1),
                maxPerRead = 1 shl 20,
                closeFailure = IllegalStateException("synthetic close failure"),
            )

        val outcome = readImportPickBounded(sizeBytes = null) { source.raw }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, failed.reason)
    }

    /**
     * AB-CLI-QUAL-02: a source reporting more bytes than the buffer holds violates the read
     * contract; the loop rejects it typed on the first call — no second read is requested and
     * nothing is copied from a miscounted source.
     */
    @Test
    fun overCountingSourceIsTheTypedReadFailureWithNoFurtherCollection() {
        var readCalls = 0
        var closed = false
        val raw =
            ImportPickRawRead(
                read = { buffer ->
                    readCalls++
                    buffer.size + 1
                },
                close = { closed = true },
            )

        val outcome = readImportPickBounded(sizeBytes = null) { raw }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, failed.reason)
        assertEquals(1, readCalls)
        assertEquals(true, closed)
    }

    /**
     * AB-CLI-QUAL-06: an `Error` thrown by the read path (OOM, linkage, ...) still propagates
     * (never swallowed into a typed failure) but the close has already been attempted — the
     * platform resource is not leaked. A close `Exception` under the same `Error` is captured
     * into `closeFailed` so it cannot mask the propagating primary `Error`.
     */
    @Test
    fun readPathErrorStillClosesTheSourceAndPropagates() {
        val readError = AssertionError("synthetic read-path Error")
        var closeAttempted = false
        var closeFailureCount = 0
        val raw =
            ImportPickRawRead(
                read = { throw readError },
                close = {
                    closeAttempted = true
                    if (closeFailureCount == 0) {
                        closeFailureCount += 1
                        throw IllegalStateException("synthetic close failure under the Error")
                    }
                },
            )

        val thrown = assertFailsWith<AssertionError> { readImportPickBounded(sizeBytes = null) { raw } }
        assertSame(readError, thrown)
        assertEquals(true, closeAttempted)
    }

    /**
     * D06 (plan section 6.3, spec sections 4.1.2/6.4): a platform read failure surfaces only the
     * typed reason. Exceptions whose messages carry sensitive-looking text — a `file:///` URI
     * with a personal-looking path, a raw CSV-row-shaped fragment and a path-bearing exception
     * message — are injected at the open, mid-read and close seams; neither the typed outcome
     * nor the failure copy the UI renders from it ([importPickReadFailureText]) ever echoes
     * any of them.
     */
    @Test
    fun readFailureSurfacesOnlyTheTypedReasonAndNeverTheExceptionText() {
        val sensitiveUri = "file:///vault/synthetic-user/个人账单-张三-2026-08.csv"
        val sensitiveName = "张三"
        val sensitiveCard = "6222020200112233445"
        val rawRowInMessage = "行内容：2026-09-01 08:30:00,张三,消费,$sensitiveCard"
        val exceptionText = "FileNotFoundException: $sensitiveUri (系统找不到指定的路径)"

        val openTyped =
            assertIs<BoundedFileRead.ReadFailed>(
                readImportPickBounded(sizeBytes = null) { throw IllegalStateException(exceptionText) },
            )
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, openTyped.reason)

        val midReadSource = FakeSource(ByteArray(0), readFailure = IllegalStateException("$exceptionText；$rawRowInMessage"))
        val midReadTyped =
            assertIs<BoundedFileRead.ReadFailed>(readImportPickBounded(sizeBytes = null) { midReadSource.raw })
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, midReadTyped.reason)

        val closeSource = FakeSource(synthetic(10), closeFailure = IllegalStateException(exceptionText))
        val closeTyped =
            assertIs<BoundedFileRead.ReadFailed>(readImportPickBounded(sizeBytes = null) { closeSource.raw })
        assertEquals(ImportPickReadFailure.STREAM_OPEN_FAILED, closeTyped.reason)

        // The rendered failure copy over both typed reasons carries none of the injected text.
        val tokens = listOf(sensitiveUri, sensitiveName, rawRowInMessage, exceptionText, sensitiveCard)
        val surfaced =
            importPickReadFailureText(ImportPickReadFailure.STREAM_OPEN_FAILED) +
                importPickReadFailureText(ImportPickReadFailure.STREAM_READ_FAILED)
        tokens.forEach { token -> assertTrue(!surfaced.contains(token)) }
    }
}
