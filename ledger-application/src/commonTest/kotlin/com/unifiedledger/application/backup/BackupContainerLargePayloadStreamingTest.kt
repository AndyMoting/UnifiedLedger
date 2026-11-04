package com.unifiedledger.application.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEF-EXPORT-OOM (D-269 item 7) regression guard: the shared container writer must complete a
 * backup export over a plaintext in the failing size regime WITHOUT ever asking for a whole-payload
 * buffer.
 *
 * The device failure is a fixed `134086656`-element request (127.87 MiB, reported as a 134086668-byte
 * allocation under a 12-byte ART array header) raised during the container-entry phase, on libraries
 * of 87.8 MB (10k) and 93.2 MB (20k) alike. This test drives the real product writer with a
 * plaintext of that order, so the size regime that fails on device is the regime exercised here.
 *
 * WHAT THIS TEST CANNOT CLAIM: the JVM has no such platform allocation, so this test does NOT
 * reproduce the device error and does NOT prove the request is platform-side. The static code read
 * (every production symbol on the export path) is what rules out an application array; the request
 * is in fact a clean `64 KiB x 2046`, so the arithmetic alone does NOT exclude an application array.
 * What is pinned here is one application-side invariant the attribution depends on: the writer's own
 * buffer request stays at the frozen 64 KiB chunk at any payload size.
 *
 * The plaintext source is GENERATED, never materialized: the test asserts a property of the writer's
 * buffer usage, so allocating the payload would defeat the point, and [ESTIMATED_FAILING_TIER_BYTES]
 * would be a needless CI cost. Nothing here changes the frozen bytes; `BackupContainerWriterTest`
 * and `BackupContainerCryptoJvmTest` stay the byte-exactness authorities.
 */
class BackupContainerLargePayloadStreamingTest {
    /**
     * ESTIMATED plaintext size for the failing tier — not a measurement. It sits above the 20k-tier
     * VACUUM snapshot (88,621,056 bytes, recorded in D-269) so the plaintext is unambiguously in the
     * failing regime. No artifact records a 10k-tier snapshot size.
     */
    private val failingTierPlaintextBytes: Long = ESTIMATED_FAILING_TIER_BYTES

    /** A counting crypto fake: it never copies the payload, so the test measures the WRITER. */
    private class CountingCrypto : BackupCryptoPrimitives {
        var maxUpdateLength = 0
        var totalHashed = 0L
        var totalEncrypted = 0L

        override fun randomBytes(count: Int): ByteArray = ByteArray(count) { 0x5A }

        override fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray = ByteArray(32) { 0x11 }

        override fun sha256Digest(): BackupSha256Digest =
            object : BackupSha256Digest {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    maxUpdateLength = maxOf(maxUpdateLength, length)
                    totalHashed += length.toLong()
                }

                override fun digest(): ByteArray = ByteArray(32) { 0x22 }
            }

        override fun gcmEncryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmEncryptor =
            object : BackupGcmEncryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray {
                    totalEncrypted += length.toLong()
                    // One-for-one ciphertext, like GCM: the produced array is at most the chunk the
                    // writer handed in, so this fake can never be the source of a whole-payload
                    // buffer. Emitting the real length also makes the sink-total assertion below
                    // meaningful (header + salt + IV + ciphertext + tag == containerLength).
                    return ByteArray(length)
                }

                override fun doFinal(): ByteArray = ByteArray(BACKUP_TAG_LENGTH)
            }

        override fun gcmDecryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmDecryptor = throw UnsupportedOperationException("export path only")
    }

    /** A generated plaintext source: fills the caller's bounded buffer from a running position. */
    private class GeneratedPlaintext(
        private val total: Long,
    ) : BackupPlaintextSource {
        /** The largest buffer the WRITER ever handed us; must stay at the frozen chunk size. */
        var maxRequestedBuffer = 0

        override fun open(): BackupPlaintextReader =
            object : BackupPlaintextReader {
                private var position = 0L

                override fun read(buffer: ByteArray): Int {
                    maxRequestedBuffer = maxOf(maxRequestedBuffer, buffer.size)
                    if (position >= total) return -1
                    val count = minOf(buffer.size.toLong(), total - position).toInt()
                    position += count.toLong()
                    return count
                }

                override fun close() = Unit
            }
    }

    /** Records the sink write sizes so an accidental whole-container write would be visible. */
    private class RecordingSink : BackupContainerSink {
        var writes = 0
        var maxWrite = 0
        var total = 0L

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writes += 1
            maxWrite = maxOf(maxWrite, length)
            total += length.toLong()
        }
    }

    @Test
    fun anExportInTheFailingSizeRegimeStreamsInBoundedChunksAndNeverBuffersThePayload() {
        val crypto = CountingCrypto()
        val plaintext = GeneratedPlaintext(failingTierPlaintextBytes)
        val sink = RecordingSink()

        val result =
            writeBackupContainer(
                plaintext = plaintext,
                sink = sink,
                password = "password123",
                schemaVersion = 36L,
                plaintextLength = failingTierPlaintextBytes,
                crypto = crypto,
            )

        // The export must complete (no allocation failure, no length mismatch).
        assertEquals(failingTierPlaintextBytes, result.plaintextLength)
        assertEquals(failingTierPlaintextBytes + backupContainerOverheadBytes(), result.containerLength)

        // The application-side allocation request never exceeds the frozen 64 KiB chunk, at a
        // payload over a thousand times that chunk.
        assertEquals(BACKUP_STREAM_CHUNK_BYTES, plaintext.maxRequestedBuffer)
        assertTrue(
            crypto.maxUpdateLength <= BACKUP_STREAM_CHUNK_BYTES,
            "the digest must never be handed more than one chunk, got ${crypto.maxUpdateLength}",
        )

        // Both passes covered the whole payload exactly once each.
        assertEquals(failingTierPlaintextBytes, crypto.totalHashed)
        assertEquals(failingTierPlaintextBytes, crypto.totalEncrypted)

        // The sink was fed in chunk-sized pieces, never one whole-container write.
        assertTrue(sink.writes > 1, "the container must be written in multiple pieces, got ${sink.writes}")
        assertTrue(sink.maxWrite <= BACKUP_STREAM_CHUNK_BYTES, "no sink write may exceed one chunk, got ${sink.maxWrite}")
        assertEquals(result.containerLength, sink.total, "the sink must receive exactly the reported container length")
    }
}

/**
 * ESTIMATED plaintext size for the failing tier (see the class KDoc): above the recorded 20k-tier
 * VACUUM snapshot of 88,621,056 bytes, so the plaintext is unambiguously in the failing regime. It
 * is NOT a measurement — no artifact records a 10k-tier snapshot size, and the recorded 10k library
 * file is 87,797,760 bytes. The exact device plaintext is irrelevant to what this test asserts (the
 * writer's buffer bound does not depend on the payload size); only the size regime matters.
 */
private const val ESTIMATED_FAILING_TIER_BYTES: Long = 90_000_000L
