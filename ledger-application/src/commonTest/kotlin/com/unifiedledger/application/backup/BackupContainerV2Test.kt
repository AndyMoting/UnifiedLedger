package com.unifiedledger.application.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 container v2 (spec section 4.11, D-285): the STRUCTURAL vector set for the chunked writer,
 * the extended parser and the chunked decrypt walk, over deterministic fakes — the same split as
 * v1, where `BackupContainerWriterTest` pins the layout and `BackupContainerCryptoJvmTest` pins the
 * real-JCE bytes. The REAL-JCE vectors (round-trip, reorder/truncate/splice rejection, the v2
 * golden) live in `BackupContainerV2CryptoJvmTest`; this file pins what must not depend on a
 * platform provider:
 *
 * - the v2 header is byte-identical to v1 except offsets 4 (u16 = 2) and 7 (u8 = 2);
 * - per-chunk IV/AAD binding: chunk k's IV is the base IV with its last 4 bytes = k (BE) and its
 *   AAD is `header || salt || k(BE u32)` (spec section 4.11.3);
 * - chunk boundaries: 1 MiB windows, a final short chunk, zero chunks for a zero payload;
 * - the v2 size equation matches the produced container for 0 bytes, exactly 1 MiB, 1 MiB + 1;
 * - the parser accepts exactly the pairs (1, 1) and (2, 2); version 1 with a foreign AEAD id is an
 *   AEAD rejection, while a non-v1 version with the v1 AEAD id stays on the frozen v1 VERSION row
 *   (the old-reader behavior of spec section 4.11.5);
 * - the walk feeds each chunk's `ciphertext || tag` to its own decryptor in order and fails typed
 *   on a structurally short cipher stream.
 */
class BackupContainerV2Test {
    // ------------------------------------------------------------------ deterministic fakes

    /** Recording fake, shaped like the v1 `BackupContainerWriterTest` one; GCM is one-for-one. */
    private class RecordingCrypto(
        private val salt: ByteArray = ByteArray(16) { it.toByte() },
        private val baseIv: ByteArray = ByteArray(12) { (it + 16).toByte() },
    ) : BackupCryptoPrimitives {
        val events = mutableListOf<String>()

        override fun randomBytes(count: Int): ByteArray {
            events += "randomBytes:$count"
            return if (count == BACKUP_SALT_LENGTH) salt.copyOf() else baseIv.copyOf()
        }

        override fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray {
            events += "deriveKey:${password.concatToString()}:$iterations:$keyLengthBits"
            return ByteArray(32) { (it + 100).toByte() }
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
            events += "encryptInit:iv=${iv.toHex()}:aadLen=${aad.size}"
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
                    return ByteArray(BACKUP_TAG_LENGTH) { 0x33 }
                }
            }
        }

        override fun gcmDecryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmDecryptor {
            events += "decryptInit:iv=${iv.toHex()}:aadLen=${aad.size}"
            return object : BackupGcmDecryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray {
                    events += "decryptUpdate:$length"
                    return bytes.copyOfRange(offset, offset + length)
                }

                override fun doFinal(): ByteArray = ByteArray(0)
            }
        }
    }

    private class RecordingSink : BackupContainerSink {
        val bytes = ArrayList<Byte>()
        var writes = 0
        var maxWrite = 0

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writes += 1
            maxWrite = maxOf(maxWrite, length)
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

    /** Writes a v2 container of [payload] under [crypto] and returns the recording sink. */
    private fun writeV2(
        payload: ByteArray,
        crypto: BackupCryptoPrimitives,
    ): RecordingSink {
        val sink = RecordingSink()
        writeBackupContainerV2(sourceOf(payload), sink, "password123", 31, payload.size.toLong(), crypto)
        return sink
    }

    // ------------------------------------------------------------------ writer layout vectors

    @Test
    fun theV2HeaderIsByteIdenticalToV1ExceptTheVersionAndAeadFields() {
        val payload = ByteArray(5) { (it + 1).toByte() }
        val crypto = RecordingCrypto()
        val bytes = writeV2(payload, crypto).bytes.toByteArray()

        assertContentEquals(byteArrayOf(0x55, 0x4C, 0x42, 0x4B), bytes.copyOfRange(0, 4))
        // container_format_version = 2 (u16 BE, offset 4) and aead_id = 2 (offset 7); every other
        // field equals the frozen v1 header for the same inputs.
        assertContentEquals(byteArrayOf(0x00, 0x02), bytes.copyOfRange(4, 6))
        assertEquals(1, bytes[6].toInt())
        assertEquals(2, bytes[7].toInt())
        assertContentEquals(byteArrayOf(0x00, 0x09, 0x27, 0xC0.toByte()), bytes.copyOfRange(8, 12))
        assertEquals(16, bytes[12].toInt())
        assertEquals(12, bytes[13].toInt())
        assertEquals(16, bytes[14].toInt())
        assertContentEquals(byteArrayOf(0, 0, 0, 31), bytes.copyOfRange(15, 19))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5), bytes.copyOfRange(19, 27))
        assertContentEquals(ByteArray(32) { 0xAB.toByte() }, bytes.copyOfRange(27, 59))
        assertContentEquals(crypto.randomBytes(BACKUP_SALT_LENGTH), bytes.copyOfRange(59, 75))
        assertContentEquals(crypto.randomBytes(BACKUP_IV_LENGTH), bytes.copyOfRange(75, 87))
        // One chunk, one tag: 87 + 5 + 16.
        assertEquals(87 + 5 + BACKUP_TAG_LENGTH, bytes.size)
    }

    @Test
    fun theDigestCompletesBeforeTheFirstChunkEncryptorIsCreated() {
        // Same load-bearing order as v1: the header (inside every chunk's AAD) can only be written
        // after pass 1 finished.
        val crypto = RecordingCrypto()
        writeV2(ByteArray(3), crypto)

        val hashDigestIndex = crypto.events.indexOfFirst { it.startsWith("hashDigest") }
        val firstEncryptInit = crypto.events.indexOfFirst { it.startsWith("encryptInit") }
        assertTrue(hashDigestIndex in 0 until firstEncryptInit, "events: ${crypto.events}")
    }

    @Test
    fun eachChunkBindsItsOwnIvAndAadDerivedFromTheBaseIvAndChunkIndex() {
        // 1.5 MiB: chunks 0 (1 MiB) and 1 (0.5 MiB), each an independent seal.
        val payload = ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + (BACKUP_V2_CHUNK_SIZE / 2).toInt()) { (it % 251).toByte() }
        val crypto = RecordingCrypto()
        val sink = writeV2(payload, crypto)
        val bytes = sink.bytes.toByteArray()

        val inits = crypto.events.filter { it.startsWith("encryptInit") }
        assertEquals(2, inits.size)
        val baseIv = crypto.randomBytes(BACKUP_IV_LENGTH)
        // The derived chunk IV replaces the base IV's LAST 4 BYTES wholesale: bytes 8..11 become
        // the BE u32 chunk index (00 00 00 00 for chunk 0, 00 00 00 01 for chunk 1).
        val chunk0Iv = baseIv.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0 }
        val chunk1Iv = baseIv.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 1 }
        assertTrue(inits[0].endsWith("iv=${chunk0Iv.toHex()}:aadLen=${BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + 4}"), inits[0])
        assertTrue(inits[1].endsWith("iv=${chunk1Iv.toHex()}:aadLen=${BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + 4}"), inits[1])

        // Payload: chunk 0 ciphertext (1 MiB) + its tag, then chunk 1 ciphertext + its tag.
        val payloadStart = BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + BACKUP_IV_LENGTH
        assertContentEquals(payload.copyOfRange(0, BACKUP_V2_CHUNK_SIZE.toInt()), bytes.copyOfRange(payloadStart, payloadStart + BACKUP_V2_CHUNK_SIZE.toInt()))
        assertContentEquals(ByteArray(BACKUP_TAG_LENGTH) { 0x33 }, bytes.copyOfRange(payloadStart + BACKUP_V2_CHUNK_SIZE.toInt(), payloadStart + BACKUP_V2_CHUNK_SIZE.toInt() + BACKUP_TAG_LENGTH))
        val chunk1Start = payloadStart + BACKUP_V2_CHUNK_SIZE.toInt() + BACKUP_TAG_LENGTH
        val chunk1Length = payload.size - BACKUP_V2_CHUNK_SIZE.toInt()
        assertContentEquals(payload.copyOfRange(BACKUP_V2_CHUNK_SIZE.toInt(), payload.size), bytes.copyOfRange(chunk1Start, chunk1Start + chunk1Length))
        assertContentEquals(ByteArray(BACKUP_TAG_LENGTH) { 0x33 }, bytes.copyOfRange(chunk1Start + chunk1Length, bytes.size))
        assertEquals(chunk1Start + chunk1Length + BACKUP_TAG_LENGTH, bytes.size)
    }

    @Test
    fun theFinalShortChunkIsSealedAsItsOwnChunkAndStreamingStaysBounded() {
        // 2 MiB + 7 bytes: chunks of 1 MiB, 1 MiB and exactly 7 bytes.
        val payload = ByteArray(2 * BACKUP_V2_CHUNK_SIZE.toInt() + 7) { (it % 249).toByte() }
        val crypto = RecordingCrypto()
        val sink = writeV2(payload, crypto)

        assertEquals(3, crypto.events.count { it.startsWith("encryptInit") })
        // 2 MiB + 7 in 64 KiB windows: 32 full windows + 1 seven-byte window per pass.
        assertEquals(33, crypto.events.count { it.startsWith("hashUpdate") })
        assertEquals(33, crypto.events.count { it.startsWith("encryptUpdate") })
        assertTrue(sink.maxWrite <= BACKUP_STREAM_CHUNK_BYTES, "no sink write may exceed the 64 KiB buffer, got ${sink.maxWrite}")
        assertEquals(87L + payload.size + 3L * BACKUP_TAG_LENGTH, sink.bytes.size.toLong())
        // The chunk lengths: exactly 1 MiB, 1 MiB, 7.
        assertEquals(BACKUP_V2_CHUNK_SIZE, backupContainerV2ChunkPlaintextLength(payload.size.toLong(), 0L))
        assertEquals(BACKUP_V2_CHUNK_SIZE, backupContainerV2ChunkPlaintextLength(payload.size.toLong(), 1L))
        assertEquals(7L, backupContainerV2ChunkPlaintextLength(payload.size.toLong(), 2L))
    }

    // ------------------------------------------------------------------ size equation vectors

    @Test
    fun theSizeEquationMatchesTheProducedContainerForZeroOneChunkAndOneChunkPlusOne() {
        // 0 bytes: ZERO chunks (spec section 4.11.4) — header + salt + IV, no tag at all.
        run {
            val bytes = writeV2(ByteArray(0), RecordingCrypto()).bytes.toByteArray()
            val header = parsedHeaderOf(bytes)
            assertEquals(0L, backupContainerV2ChunkCount(0L))
            assertEquals(87L, bytes.size.toLong())
            assertEquals(87L, backupContainerV2ExpectedSize(header))
            assertTrue(backupContainerV2SelfConsistent(header, bytes.size.toLong()))
        }
        // Exactly 1 MiB: ONE chunk, one tag.
        run {
            val bytes = writeV2(ByteArray(BACKUP_V2_CHUNK_SIZE.toInt()), RecordingCrypto()).bytes.toByteArray()
            val header = parsedHeaderOf(bytes)
            assertEquals(1L, backupContainerV2ChunkCount(BACKUP_V2_CHUNK_SIZE))
            assertEquals(87L + BACKUP_V2_CHUNK_SIZE + BACKUP_TAG_LENGTH, bytes.size.toLong())
            assertEquals(bytes.size.toLong(), backupContainerV2ExpectedSize(header))
            assertTrue(backupContainerV2SelfConsistent(header, bytes.size.toLong()))
        }
        // 1 MiB + 1: TWO chunks, two tags, the second chunk exactly 1 byte.
        run {
            val bytes = writeV2(ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + 1), RecordingCrypto()).bytes.toByteArray()
            val header = parsedHeaderOf(bytes)
            assertEquals(2L, backupContainerV2ChunkCount(BACKUP_V2_CHUNK_SIZE + 1))
            assertEquals(87L + BACKUP_V2_CHUNK_SIZE + 1 + 2 * BACKUP_TAG_LENGTH, bytes.size.toLong())
            assertEquals(bytes.size.toLong(), backupContainerV2ExpectedSize(header))
            assertTrue(backupContainerV2SelfConsistent(header, bytes.size.toLong()))
            assertTrue(!backupContainerV2SelfConsistent(header, bytes.size.toLong() - 1))
            assertEquals(1L, backupContainerV2ChunkPlaintextLength(BACKUP_V2_CHUNK_SIZE + 1, 1L))
        }
    }

    @Test
    fun theV2SpecConstantsAndHelpersArePinnedToTheirLiteralValues() {
        assertEquals(2, BACKUP_CONTAINER_FORMAT_VERSION_V2)
        assertEquals(2, BACKUP_AEAD_ID_CHUNKED)
        assertEquals(1_048_576L, BACKUP_V2_CHUNK_SIZE)
        assertEquals(0L, backupContainerV2ChunkCount(0L))
        assertEquals(1L, backupContainerV2ChunkCount(1L))
        assertEquals(1L, backupContainerV2ChunkCount(BACKUP_V2_CHUNK_SIZE))
        assertEquals(2L, backupContainerV2ChunkCount(BACKUP_V2_CHUNK_SIZE + 1))
        // Overhead: 87 fixed bytes + one tag per chunk (zero chunks for a zero payload).
        assertEquals(87L, backupContainerV2OverheadBytes(0L))
        assertEquals(87L + BACKUP_TAG_LENGTH, backupContainerV2OverheadBytes(BACKUP_V2_CHUNK_SIZE))
        assertEquals(87L + 2 * BACKUP_TAG_LENGTH, backupContainerV2OverheadBytes(BACKUP_V2_CHUNK_SIZE + 1))
    }

    // ------------------------------------------------------------------ parser pair rules

    @Test
    fun aV2ContainerHeaderParsesWithBothVersionAndAeadCarried() {
        val bytes = writeV2(ByteArray(5), RecordingCrypto()).bytes.toByteArray()
        val parsed = assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(bytes.copyOf(BACKUP_FIXED_HEADER_LENGTH)))
        assertEquals(BACKUP_CONTAINER_FORMAT_VERSION_V2, parsed.header.containerFormatVersion)
        assertEquals(BACKUP_AEAD_ID_CHUNKED, parsed.header.aeadId)
    }

    @Test
    fun versionTwoWithTheV1AeadIdIsAVersionRejectionLikeTheOldReaderWouldGive() {
        // The frozen v1 row (`aFutureContainerFormatVersionIsVersionUnsupported` in the v1
        // regression set) pins (2, 1) -> P706_CONTAINER_VERSION_UNSUPPORTED, and spec section
        // 4.11.5 freezes exactly that row as the old-reader behavior for version 2. The version
        // row runs first and only carves out the single new pair (2, 2).
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(headerWith(version = 2, aead = 1)))
        assertEquals(BackupPreflightRejection.P706_CONTAINER_VERSION_UNSUPPORTED, rejected.code)
    }

    @Test
    fun aFutureVersionWithTheChunkedAeadIdIsStillAVersionRejection() {
        // The carve-out is exactly (2, 2): no future version may ride the chunked AEAD id past
        // the frozen version row.
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(headerWith(version = 3, aead = 2)))
        assertEquals(BackupPreflightRejection.P706_CONTAINER_VERSION_UNSUPPORTED, rejected.code)
    }

    @Test
    fun versionOneWithTheChunkedAeadIdIsAeadUnsupported() {
        val rejected = assertIs<BackupHeaderParseResult.Rejected>(parseBackupContainerHeader(headerWith(version = 1, aead = 2)))
        assertEquals(BackupPreflightRejection.P706_AEAD_UNSUPPORTED, rejected.code)
    }

    @Test
    fun theV1PairStillParsesWithTheUnchangedV1Equation() {
        val header = backupContainerHeader(31L, 4096L, ByteArray(32) { 1 })
        val parsed = assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(header))
        assertEquals(BACKUP_CONTAINER_FORMAT_VERSION, parsed.header.containerFormatVersion)
        assertEquals(BACKUP_AEAD_ID, parsed.header.aeadId)
        assertEquals(4199L, backupContainerExpectedSize(parsed.header))
        assertTrue(backupContainerSelfConsistent(parsed.header, 4199L))
        // The v1 equation does NOT accept the per-chunk-tag size: the equations stay separate.
        assertTrue(!backupContainerSelfConsistent(parsed.header, 4199L + BACKUP_TAG_LENGTH))
    }

    // ------------------------------------------------------------------ decrypt-walk structural vectors

    @Test
    fun theWalkFeedsEachChunkWireToItsOwnDecryptorInTheRecordedOrder() {
        // 1 MiB + 8 bytes: two chunks; the fake decryptor echoes the wire, so the recorded
        // decryptUpdate lengths prove the per-chunk window is `chunk plaintext length + tag`.
        val payload = ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + 8) { (it % 251).toByte() }
        val container = writeV2(payload, RecordingCrypto()).bytes.toByteArray()
        val header = parsedHeaderOf(container)
        val crypto = RecordingCrypto()
        val key = crypto.deriveKey(widenBackupPassword("password123"), ByteArray(16), BACKUP_KDF_ITERATIONS, BACKUP_KEY_LENGTH_BITS)
        val salt = container.copyOfRange(BACKUP_FIXED_HEADER_LENGTH, BACKUP_FIXED_HEADER_LENGTH + header.saltLength)
        val ivStart = BACKUP_FIXED_HEADER_LENGTH + header.saltLength
        val baseIv = container.copyOfRange(ivStart, ivStart + header.ivLength)
        var position = ivStart + header.ivLength
        val produced = ArrayList<Byte>()

        val total =
            decryptBackupContainerV2(
                cipherSource = { buffer ->
                    val count = minOf(buffer.size, container.size - position)
                    if (count <= 0) return@decryptBackupContainerV2 -1
                    container.copyInto(buffer, 0, position, position + count)
                    position += count
                    count
                },
                plaintextSink = { bytes, offset, length ->
                    for (index in offset until offset + length) produced += bytes[index]
                },
                header = header,
                salt = salt,
                baseIv = baseIv,
                key = key,
                crypto = crypto,
            )

        assertEquals(payload.size + 2L * BACKUP_TAG_LENGTH, total)
        // The fake echoes the WIRE one-for-one (the walk also feeds each chunk's 16-byte tag
        // through update), so the produced count is payload + both tags; the round-trip vectors
        // in BackupContainerV2CryptoJvmTest prove the real-GCM half.
        assertEquals(payload.size + 2L * BACKUP_TAG_LENGTH, produced.size.toLong())
        val decryptInits = crypto.events.filter { it.startsWith("decryptInit") }
        assertEquals(2, decryptInits.size)
        val baseIvAgain = container.copyOfRange(ivStart, ivStart + header.ivLength)
        assertTrue(
            decryptInits[0].endsWith(
                "iv=" +
                    baseIvAgain.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0 }.toHex() +
                    ":aadLen=${BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + 4}",
            ),
            decryptInits[0],
        )
        assertTrue(
            decryptInits[1].endsWith(
                "iv=" + baseIvAgain.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 1 }.toHex() +
                    ":aadLen=${BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + 4}",
            ),
            decryptInits[1],
        )
        val updates = crypto.events.filter { it.startsWith("decryptUpdate") }
        // Chunk 0 wire = 1 MiB + 16 = 16 full 64 KiB windows + one 16-byte window; chunk 1 wire =
        // 8 + 16 = one 24-byte window.
        assertEquals(18, updates.size)
        assertEquals((BACKUP_V2_CHUNK_SIZE + 8 + 2L * BACKUP_TAG_LENGTH).toInt(), updates.sumOf { it.substringAfter(':').toInt() })
    }

    @Test
    fun aStructurallyShortCipherStreamFailsTypedAfterTheCompleteFirstChunk() {
        val payload = ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + 8) { (it % 251).toByte() }
        val container = writeV2(payload, RecordingCrypto()).bytes.toByteArray()
        val header = parsedHeaderOf(container)
        val crypto = RecordingCrypto()
        val key = crypto.deriveKey(widenBackupPassword("password123"), ByteArray(16), BACKUP_KDF_ITERATIONS, BACKUP_KEY_LENGTH_BITS)
        val salt = container.copyOfRange(BACKUP_FIXED_HEADER_LENGTH, BACKUP_FIXED_HEADER_LENGTH + header.saltLength)
        val ivStart = BACKUP_FIXED_HEADER_LENGTH + header.saltLength
        // The cipher stream is cut short of the header's own chunk count (the tail chunk wire
        // missing): the walk must fail TYPED, never return a trusted prefix.
        val cutAt = container.size - (8 + BACKUP_TAG_LENGTH)
        var position = ivStart + header.ivLength
        val sinkWrites = intArrayOf(0)

        assertFailsWith<ShortChunkStreamException> {
            decryptBackupContainerV2(
                cipherSource = { buffer ->
                    if (position >= cutAt) return@decryptBackupContainerV2 -1
                    val count = minOf(buffer.size, cutAt - position)
                    container.copyInto(buffer, 0, position, position + count)
                    position += count
                    count
                },
                plaintextSink = { _, _, _ -> sinkWrites[0] += 1 },
                header = header,
                salt = salt,
                baseIv = container.copyOfRange(ivStart, ivStart + header.ivLength),
                key = key,
                crypto = crypto,
            )
        }
        // The failure is structural (the final chunk wire is missing), but the walk MUST have
        // decrypted the complete first chunk before discovering it — the sink received chunk 0's
        // plaintext (16 full windows + one 16-byte window) and nothing for the missing chunk.
        assertEquals(17, sinkWrites[0])
    }

    // ------------------------------------------------------------------ helpers

    private fun parsedHeaderOf(container: ByteArray): BackupContainerHeader =
        assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(container.copyOf(BACKUP_FIXED_HEADER_LENGTH))).header

    private fun headerWith(
        version: Int,
        aead: Int,
    ): ByteArray {
        val header = backupContainerHeader(31L, 4096L, ByteArray(32) { 1 })
        header[4] = ((version ushr 8) and 0xFF).toByte()
        header[5] = (version and 0xFF).toByte()
        header[7] = aead.toByte()
        return header
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            builder.append(digits[value ushr 4])
            builder.append(digits[value and 0x0F])
        }
        return builder.toString()
    }
}
