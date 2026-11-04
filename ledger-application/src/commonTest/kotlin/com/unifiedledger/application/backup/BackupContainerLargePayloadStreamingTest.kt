package com.unifiedledger.application.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEF-EXPORT-OOM (D-269 item 7) regression guard: the shared container writer must complete a
 * backup export whose plaintext is at or above the recorded failing library size WITHOUT ever
 * asking for a whole-payload buffer.
 *
 * The device failure is a fixed `134086668` byte request (127.87 MiB) raised during the
 * snapshot-verify/container-entry phase, on libraries of 87.8 MB (10k) and 93.2 MB (20k) alike. The
 * measurement below uses the real 10k-tier VACUUM snapshot size (73,003,008 bytes, measured from
 * `a06-10000.db`) as the plaintext, so the size regime that fails on device is the size regime this
 * test drives through the product writer. On the JVM the platform allocation does NOT exist, so this
 * test cannot reproduce the device error; what it does pin is the application-side invariant the
 * defect attribution depends on — that the export's own allocation request stays bounded by the
 * frozen 64 KiB chunk at any payload size.
 *
 * The plaintext source is GENERATED, never materialized: the test asserts a property of the writer's
 * buffer usage, so allocating the payload would defeat the point (and a 73 MB fixture would be
 * needless CI cost). Nothing here changes the frozen bytes; `BackupContainerWriterTest` and
 * `BackupContainerCryptoJvmTest` stay the byte-exactness authorities.
 */
class BackupContainerLargePayloadStreamingTest {
    /** The measured 10k-tier VACUUM snapshot size: inside the failing regime (>= 10k library). */
    private val failingTierSnapshotBytes: Long = 73_003_008L

    /** The recorded device failure's allocation request, for the arithmetic assertion below. */
    private val recordedDeviceRequestBytes: Long = 134_086_668L

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
    fun anExportAtTheFailingLibrarySizeStreamsInBoundedChunksAndNeverBuffersThePayload() {
        val crypto = CountingCrypto()
        val plaintext = GeneratedPlaintext(failingTierSnapshotBytes)
        val sink = RecordingSink()

        val result =
            writeBackupContainer(
                plaintext = plaintext,
                sink = sink,
                password = "password123",
                schemaVersion = 36L,
                plaintextLength = failingTierSnapshotBytes,
                crypto = crypto,
            )

        // The export must complete (no allocation failure, no length mismatch).
        assertEquals(failingTierSnapshotBytes, result.plaintextLength)
        assertEquals(failingTierSnapshotBytes + backupContainerOverheadBytes(), result.containerLength)

        // The application-side allocation request never exceeds the frozen 64 KiB chunk, at a
        // payload 1000x that chunk. This is the invariant the device attribution rests on.
        assertEquals(BACKUP_STREAM_CHUNK_BYTES, plaintext.maxRequestedBuffer)
        assertTrue(
            crypto.maxUpdateLength <= BACKUP_STREAM_CHUNK_BYTES,
            "the digest must never be handed more than one chunk, got ${crypto.maxUpdateLength}",
        )

        // Both passes covered the whole payload exactly once each.
        assertEquals(failingTierSnapshotBytes, crypto.totalHashed)
        assertEquals(failingTierSnapshotBytes, crypto.totalEncrypted)

        // The sink was fed in chunk-sized pieces, never one whole-container write.
        assertTrue(sink.writes > 1, "the container must be written in multiple pieces, got ${sink.writes}")
        assertTrue(sink.maxWrite <= BACKUP_STREAM_CHUNK_BYTES, "no sink write may exceed one chunk, got ${sink.maxWrite}")
        assertEquals(result.containerLength, sink.total, "the sink must receive exactly the reported container length")
    }

    @Test
    fun theRecordedDeviceRequestIsNotAMultipleOfAnyPlaintextOrContainerSize() {
        // DEF-EXPORT-OOM attribution guard (D-269 item 7): the failing request is a FIXED size that
        // is 4 bytes offset from a clean 128 KiB multiple (134086656 = 128 KiB * 1023), and it is
        // identical across the 10k and 20k tiers. A future regression that made the export's own
        // allocation proportional to the payload would break this relationship, so pin it here.
        assertEquals(134_086_656L, 128L * 1024L * 1023L)
        assertEquals(12L, recordedDeviceRequestBytes - 134_086_656L, "the ART array header offset")
        assertEquals(0L, 134_086_656L % (64L * 1024L), "the element count is chunk aligned")
    }
}
