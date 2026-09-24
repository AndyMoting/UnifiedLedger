package com.unifiedledger.application.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 4.6 and 5): the
 * shared container writer's frozen byte layout, its two-pass hash-then-encrypt order and its
 * bounded-streaming property. The crypto primitives are a recording fake so the exact bytes and
 * the exact operation order are directly assertable; the real JCE bytes are pinned separately in
 * `BackupContainerCryptoJvmTest`.
 */
class BackupContainerWriterTest {
    /** Records every digest/encrypt call so the frozen ordering can be asserted. */
    private class RecordingCrypto(
        private val salt: ByteArray = ByteArray(16) { it.toByte() },
        private val iv: ByteArray = ByteArray(12) { (it + 16).toByte() },
        private val derivedKey: ByteArray = ByteArray(32) { (it + 100).toByte() },
    ) : BackupCryptoPrimitives {
        val events = mutableListOf<String>()

        override fun randomBytes(count: Int): ByteArray {
            events += "randomBytes:$count"
            return if (count == BACKUP_SALT_LENGTH) salt.copyOf() else iv.copyOf()
        }

        override fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray {
            events += "deriveKey:${password.concatToString()}:${iterations}:$keyLengthBits"
            return derivedKey.copyOf()
        }

        override fun sha256Digest(): BackupSha256Digest =
            object : BackupSha256Digest {
                var length = 0

                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    events += "hashUpdate:$length"
                    this.length += length
                }

                override fun digest(): ByteArray {
                    events += "hashDigest:$length"
                    return ByteArray(32) { 0xAB.toByte() }
                }
            }

        override fun gcmEncryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmEncryptor {
            events += "gcmInit:aad=${aad.size}"
            return object : BackupGcmEncryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray {
                    events += "encryptUpdate:$length"
                    return bytes.copyOfRange(offset, offset + length)
                }

                override fun doFinal(): ByteArray {
                    events += "encryptFinal"
                    return ByteArray(BACKUP_TAG_LENGTH)
                }
            }
        }
    }

    private class RecordingSink : BackupContainerSink {
        val bytes = ArrayList<Byte>()

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            for (index in offset until offset + length) this.bytes += bytes[index]
        }
    }

    private fun sourceOf(payload: ByteArray): BackupPlaintextSource =
        BackupPlaintextSource {
            var position = 0
            object : BackupPlaintextReader {
                override fun read(buffer: ByteArray): Int {
                    if (position >= payload.size) return -1
                    val count = minOf(buffer.size, payload.size - position)
                    payload.copyInto(buffer, 0, position, position + count)
                    position += count
                    return count
                }

                override fun close() {}
            }
        }

    @Test
    fun theFixedHeaderIsWrittenByteForByteAtTheFrozenOffsets() {
        val payload = ByteArray(5) { (it + 1).toByte() }
        val crypto = RecordingCrypto()
        val sink = RecordingSink()

        val result =
            writeBackupContainer(
                plaintext = sourceOf(payload),
                sink = sink,
                password = "password123",
                schemaVersion = 31,
                plaintextLength = payload.size.toLong(),
                crypto = crypto,
            )

        val bytes = sink.bytes.toByteArray()
        // magic = ULBK
        assertContentEquals(byteArrayOf(0x55, 0x4C, 0x42, 0x4B), bytes.copyOfRange(0, 4))
        // container_format_version = 1 (u16 big-endian)
        assertContentEquals(byteArrayOf(0x00, 0x01), bytes.copyOfRange(4, 6))
        assertEquals(1, bytes[6].toInt()) // kdf_id
        assertEquals(1, bytes[7].toInt()) // aead_id
        // kdf_iterations = 600000 = 0x000927C0
        assertContentEquals(byteArrayOf(0x00, 0x09, 0x27, 0xC0.toByte()), bytes.copyOfRange(8, 12))
        assertEquals(16, bytes[12].toInt()) // salt_len
        assertEquals(12, bytes[13].toInt()) // iv_len
        assertEquals(16, bytes[14].toInt()) // tag_len
        // db_schema_version = 31
        assertContentEquals(byteArrayOf(0, 0, 0, 31), bytes.copyOfRange(15, 19))
        // plaintext_len = 5 (u64 big-endian)
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5), bytes.copyOfRange(19, 27))
        // payload_sha256
        assertContentEquals(ByteArray(32) { 0xAB.toByte() }, bytes.copyOfRange(27, 59))
        // salt + iv follow the fixed header
        assertContentEquals(crypto.randomBytes(BACKUP_SALT_LENGTH), bytes.copyOfRange(59, 75))
        assertContentEquals(crypto.randomBytes(BACKUP_IV_LENGTH), bytes.copyOfRange(75, 87))
        assertEquals(BACKUP_FIXED_HEADER_LENGTH + 16 + 12 + 5 + 16, bytes.size)
        assertEquals(5L, result.plaintextLength)
        assertEquals(bytes.size.toLong(), result.containerLength)
    }

    @Test
    fun theDigestIsCompletedBeforeTheHeaderIsWrittenAndBeforeEncryptionStarts() {
        // Spec section 5: payload_sha256 lives at header offset 27 and the header is the AAD, so
        // the hash pass MUST complete before any container byte is produced. Reversing the passes
        // (hashing after the header) makes the index assertions below fail.
        val payload = ByteArray(3) { it.toByte() }
        val crypto = RecordingCrypto()
        val sink = RecordingSink()

        writeBackupContainer(sourceOf(payload), sink, "password123", 31, payload.size.toLong(), crypto)

        val hashDigestIndex = crypto.events.indexOfFirst { it.startsWith("hashDigest") }
        val gcmInitIndex = crypto.events.indexOfFirst { it.startsWith("gcmInit") }
        assertTrue(hashDigestIndex >= 0, "the hash pass must run")
        assertTrue(gcmInitIndex > hashDigestIndex, "encryption must start only after the digest completes")
        // Two bounded streaming passes of the payload, one per pass (a 3-byte payload is one chunk
        // each): one hash update and one encrypt update.
        assertEquals(1, crypto.events.count { it.startsWith("hashUpdate") })
        assertEquals(1, crypto.events.count { it.startsWith("encryptUpdate") })
    }

    @Test
    fun theWriterStreamsInBoundedChunksAndNeverReadsTheWholePayloadAtOnce() {
        // 3 chunks + 1 byte, larger than the 64 KiB buffer, so the writer must loop.
        val payload = ByteArray(BACKUP_STREAM_CHUNK_BYTES * 3 + 1) { (it % 251).toByte() }
        val crypto = RecordingCrypto()
        val sink = RecordingSink()

        writeBackupContainer(sourceOf(payload), sink, "password123", 31, payload.size.toLong(), crypto)

        val updates = crypto.events.filter { it.startsWith("hashUpdate") || it.startsWith("encryptUpdate") }
        assertTrue(updates.size >= 8, "the payload must be read in multiple bounded chunks, got $updates")
        assertTrue(
            updates.all { it.substringAfter(':').toInt() <= BACKUP_STREAM_CHUNK_BYTES },
            "no single read may exceed the 64 KiB buffer: $updates",
        )
    }

    @Test
    fun aPayloadLengthMismatchFailsBeforeAnyContainerByteIsWritten() {
        val payload = ByteArray(4)
        val sink = RecordingSink()

        val failure =
            runCatching {
                writeBackupContainer(sourceOf(payload), sink, "password123", 31, plaintextLength = 9, crypto = RecordingCrypto())
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "a length mismatch must fail: $failure")
        assertEquals(0, sink.bytes.size, "no container byte may be produced for an unbacked length")
    }

    @Test
    fun thePasswordIsWidenedFromUtf8BytesTakingTheLowEightBits() {
        // The frozen v1 widening: one char per UTF-8 byte, low 8 bits. `toCharArray` would give a
        // different result for the CJK input below and the two ends would be mutually unreadable.
        val widened = widenBackupPassword("密码")
        assertContentEquals(byteArrayOf(0xE5.toByte(), 0xAF.toByte(), 0x86.toByte(), 0xE7.toByte(), 0xA0.toByte(), 0x81.toByte()), widened.map { it.code.toByte() }.toByteArray())
        assertEquals(6, widened.size)
    }
}