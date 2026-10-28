package com.unifiedledger.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D01-9 follow-up: a Storage Access Framework grant can be revoked while the picked file is being
 * read, and the platform then throws [SecurityException] from the stream rather than returning -1.
 *
 * The bounded-read helper maps any [Throwable] onto one typed reason, so revocation is expected to
 * land on the same branch as any other mid-stream failure. This pins that mapping explicitly -
 * typed read failure, source closed, no exception text on the user-facing copy - because a revoked
 * grant is the one mid-stream failure the product cannot reproduce on demand.
 *
 * Scope note: [ImportFilePickBoundedReadTest] already pins the same branch with an anonymous
 * IllegalStateException. This file adds the *type-level* anchor for a revoked grant, so a future
 * refactor that starts special-casing SecurityException shows up here rather than only in a field
 * report. It does not claim device-side coverage.
 *
 * JVM source set on purpose: [SecurityException] is a platform type, and AOSP offers no
 * deterministic way to revoke a live grant, which is exactly why D01-9 stays PARTIAL.
 */
class ImportFilePickGrantRevocationTest {
    @Test
    fun aGrantRevokedBetweenChunksIsTheTypedReadFailureAndLeaksNothing() {
        val revokedUri = "content://com.android.providers.media.documents/document/synthetic-2026-09.csv"
        val revocation = SecurityException("Permission Denial: opening provider requires grant for $revokedUri")
        var position = 0
        var closed = false
        val raw =
            ImportPickRawRead(
                read = { buffer ->
                    if (position == 0) {
                        buffer[0] = 0x7A
                        position = 1
                        1
                    } else {
                        // The grant disappears between the first and the second chunk.
                        throw revocation
                    }
                },
                close = { closed = true },
            )

        val outcome = readImportPickBounded(sizeBytes = null) { raw }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, failed.reason)
        assertTrue(closed, "the source must be closed even when the grant is gone")
        // The user-facing copy is a frozen sentence, so an implementation that starts echoing the
        // thrown message fails here on the whole string rather than on two sampled substrings.
        assertEquals(FROZEN_READ_FAILED_COPY, importPickReadFailureText(failed.reason))
    }

    /**
     * The most common real shape: the grant is already gone when the first chunk is requested, so
     * the very first read throws. It must land on the same typed reason and still close the source.
     */
    @Test
    fun aGrantAlreadyRevokedAtTheFirstChunkIsTheSameTypedFailureAndClosesTheSource() {
        val revocation = SecurityException("Permission Denial: reading provider requires grant")
        var closed = false
        val raw =
            ImportPickRawRead(
                read = { throw revocation },
                close = { closed = true },
            )

        val outcome = readImportPickBounded(sizeBytes = null) { raw }

        val failed = assertIs<BoundedFileRead.ReadFailed>(outcome)
        assertEquals(ImportPickReadFailure.STREAM_READ_FAILED, failed.reason)
        assertTrue(closed, "the source must be closed even when the first read is refused")
        assertEquals(FROZEN_READ_FAILED_COPY, importPickReadFailureText(failed.reason))
    }

    private companion object {
        /** Pinned verbatim so the copy cannot drift into carrying provider text. */
        const val FROZEN_READ_FAILED_COPY = "文件读取失败（读取中途失败）。"
    }
}
