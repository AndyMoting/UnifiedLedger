package com.unifiedledger.application.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 container v2 (spec section 4.11, D-285): the REAL-JCE cryptographic vector set, alongside
 * the v1 golden in `BackupContainerCryptoJvmTest`. The salt and base IV are injected
 * deterministically (the only test-only seam on the primitives) so the produced bytes are
 * reproducible; the vectors prove, with the platform provider the product actually uses:
 *
 * - v2 round-trip over a MULTI-CHUNK payload (1.5 MiB) returns the exact plaintext;
 * - the final SHORT chunk (2 MiB + 7: chunks 1 MiB / 1 MiB / 7) round-trips;
 * - chunk REORDER and CROSS-CONTAINER SPLICE fail the tag check (spec section 4.11.3: the chunk
 *   index binds both the AAD and the IV);
 * - TRUNCATION is caught pre-auth by the v2 size equation, and the walk fails typed if decryption
 *   is attempted anyway;
 * - a wrong password and a tampered ciphertext fail the chunk tag — the uniform failure.
 *
 * "NO PARTIAL COMMIT" SEMANTICS (spec section 4.11.4, identical to v1): a GCM decryptor's
 * `update` MAY return unverified plaintext before `doFinal` verifies the tag (the v1 writer test's
 * port contract documents exactly this). The walk therefore writes to the caller's staging sink as
 * it goes, and the CALLER owns the rollback: the preflight's `finally` deletes the staging file
 * unless the whole walk AND the size/hash checks committed (RestorePreflight `decryptToStaging`).
 * These vectors assert the failure surfaces; the rollback behavior is the preflight's existing,
 * unchanged lifecycle.
 */
class BackupContainerV2CryptoJvmTest {
    private val password = "password123"

    /** Read position into the container under test; reset by every [decryptV2] call. */
    private var position = 0

    private class CollectingSink : BackupContainerSink {
        val bytes = ArrayList<Byte>()

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            for (index in offset until offset + length) this.bytes += bytes[index]
        }
    }

    private fun source(payload: ByteArray): BackupPlaintextSource =
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

    private fun deterministicCrypto(saltSeed: Byte = 0, ivSeed: Byte = 16) =
        JvmBackupCryptoPrimitives { count ->
            when (count) {
                BACKUP_SALT_LENGTH -> ByteArray(16) { (it + saltSeed).toByte() }
                else -> ByteArray(12) { (it + ivSeed).toByte() }
            }
        }

    private fun writeV2(payload: ByteArray, crypto: JvmBackupCryptoPrimitives = deterministicCrypto()): ByteArray {
        val sink = CollectingSink()
        writeBackupContainerV2(source(payload), sink, password, 31, payload.size.toLong(), crypto)
        return sink.bytes.toByteArray()
    }

    private fun parsedHeaderOf(container: ByteArray): BackupContainerHeader =
        assertIs<BackupHeaderParseResult.Parsed>(parseBackupContainerHeader(container.copyOf(BACKUP_FIXED_HEADER_LENGTH))).header

    /**
     * Decrypts a produced v2 container through the chunked walk with real JCE, the way the
     * preflight dispatches it (key derived ONCE from the header's salt/iterations, then handed to
     * the walk; cleared after use).
     */
    private fun decryptV2(container: ByteArray, password: String): ByteArray {
        val header = parsedHeaderOf(container)
        require(header.containerFormatVersion == BACKUP_CONTAINER_FORMAT_VERSION_V2) { "not a v2 container" }
        val salt = container.copyOfRange(BACKUP_FIXED_HEADER_LENGTH, BACKUP_FIXED_HEADER_LENGTH + header.saltLength)
        val ivStart = BACKUP_FIXED_HEADER_LENGTH + header.saltLength
        val baseIv = container.copyOfRange(ivStart, ivStart + header.ivLength)
        val crypto = JvmBackupCryptoPrimitives()
        val key = crypto.deriveKey(widenBackupPassword(password), salt, header.kdfIterations.toInt(), BACKUP_KEY_LENGTH_BITS)
        val output = ArrayList<Byte>()
        try {
            position = ivStart + header.ivLength
            decryptBackupContainerV2(
                cipherSource = { buffer ->
                    val count = minOf(buffer.size, container.size - position)
                    if (count <= 0) return@decryptBackupContainerV2 -1
                    container.copyInto(buffer, 0, position, position + count)
                    position += count
                    count
                },
                plaintextSink = { bytes, offset, length ->
                    for (index in offset until offset + length) output += bytes[index]
                },
                header = header,
                salt = salt,
                baseIv = baseIv,
                key = key,
                crypto = crypto,
            )
        } finally {
            key.fill(0)
        }
        return output.toByteArray()
    }

    // ------------------------------------------------------------------ round-trip vectors

    @Test
    fun aMultiChunkPayloadRoundTripsToTheExactPlaintext() {
        // 1.5 MiB: two chunks (1 MiB + 0.5 MiB) with a real GCM seal per chunk.
        val payload = ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + (BACKUP_V2_CHUNK_SIZE / 2).toInt()) { (it % 251).toByte() }
        val container = writeV2(payload)

        val recovered = decryptV2(container, password)

        assertContentEquals(payload, recovered)
        // The header hash (offset 27) is the real SHA-256 of the recovered plaintext.
        assertContentEquals(sha256(payload), container.copyOfRange(27, 59))
        assertEquals(2, container[5].toInt())
        assertEquals(2, container[7].toInt())
        assertEquals(87L + payload.size + 2L * BACKUP_TAG_LENGTH, container.size.toLong())
    }

    @Test
    fun theFinalShortChunkOfSevenBytesRoundTrips() {
        // 2 MiB + 7 bytes: chunks of exactly 1 MiB, 1 MiB and 7 bytes (the TASK vector).
        val payload = ByteArray(2 * BACKUP_V2_CHUNK_SIZE.toInt() + 7) { (it % 249).toByte() }
        val container = writeV2(payload)

        assertEquals(3L, backupContainerV2ChunkCount(payload.size.toLong()))
        assertEquals(7L, backupContainerV2ChunkPlaintextLength(payload.size.toLong(), 2L))
        val recovered = decryptV2(container, password)

        assertContentEquals(payload, recovered)
    }

    // ------------------------------------------------------------------ rejection vectors

    @Test
    fun aReorderedChunkPairFailsAuthentication() {
        val payload = ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + 64) { (it % 251).toByte() }
        val container = writeV2(payload)
        val chunkLength = BACKUP_V2_CHUNK_SIZE.toInt() + BACKUP_TAG_LENGTH
        val payloadStart = BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + BACKUP_IV_LENGTH

        // Swap the two chunks' wires (ciphertext || tag each) wholesale.
        val reordered = container.copyOf()
        val chunk0 = container.copyOfRange(payloadStart, payloadStart + chunkLength)
        container.copyOfRange(payloadStart + chunkLength, container.size).copyInto(reordered, payloadStart)
        chunk0.copyInto(reordered, payloadStart + chunkLength)

        val failure = runCatching { decryptV2(reordered, password) }.exceptionOrNull()
        assertTrue(failure is BackupV2AuthenticationException, "a reordered chunk must fail its tag, got $failure")
        // The platform AEAD exception rides as the cause (the evidence chain D-285's attribution
        // depended on): the caller maps the wrapper to the uniform code.
        assertTrue(failure?.cause is javax.crypto.AEADBadTagException, "cause: ${failure?.cause}")
    }

    @Test
    fun aTruncatedContainerFailsTheSizeEquationPreAuthAndTheWalkTypedIfDecrypted() {
        val payload = ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + 64) { (it % 251).toByte() }
        val container = writeV2(payload)
        // Drop the final chunk's wire entirely: the header still claims two chunks.
        val chunkLength = BACKUP_V2_CHUNK_SIZE.toInt() + BACKUP_TAG_LENGTH
        val truncated = container.copyOfRange(0, container.size - chunkLength)

        // Layer 1 (the caller's order): the v2 size equation rejects BEFORE any chunk decrypts.
        val header = parsedHeaderOf(truncated)
        assertTrue(!backupContainerV2SelfConsistent(header, truncated.size.toLong()))
        // Layer 2 (a caller that skipped the gate): the walk fails typed, never a trusted prefix.
        assertFailsWith<ShortChunkStreamException> { decryptV2(truncated, password) }
    }

    @Test
    fun aCrossContainerSpliceFailsAuthentication() {
        val payload = ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + 64) { (it % 251).toByte() }
        val containerA = writeV2(payload)
        val containerB = writeV2(payload, crypto = deterministicCrypto(saltSeed = 1, ivSeed = 17))
        assertEquals(containerA.size, containerB.size)

        val chunkLength = BACKUP_V2_CHUNK_SIZE.toInt() + BACKUP_TAG_LENGTH
        val payloadStart = BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + BACKUP_IV_LENGTH
        val spliced = containerA.copyOf()
        // Graft container B's final chunk onto container A: same password, but B's chunk was
        // sealed under B's salt-derived key with B's chunk IV/AAD — the tag cannot verify.
        containerB.copyOfRange(payloadStart + chunkLength, containerB.size).copyInto(spliced, payloadStart + chunkLength)

        val failure = runCatching { decryptV2(spliced, password) }.exceptionOrNull()
        assertTrue(failure is BackupV2AuthenticationException, "a spliced foreign chunk must fail its tag, got $failure")
        assertTrue(failure?.cause is javax.crypto.AEADBadTagException, "cause: ${failure?.cause}")
    }

    @Test
    fun aWrongPasswordFailsTheFirstChunkTagAsTheUniformFailure() {
        val payload = ByteArray((BACKUP_V2_CHUNK_SIZE / 2).toInt() + 11) { (it % 251).toByte() }
        val container = writeV2(payload)

        val failure = runCatching { decryptV2(container, "wrong-password") }.exceptionOrNull()
        assertTrue(failure is BackupV2AuthenticationException, "a wrong password must fail the first chunk, got $failure")
        assertTrue(failure?.cause is javax.crypto.AEADBadTagException, "cause: ${failure?.cause}")
    }

    @Test
    fun aTamperedCiphertextByteFailsItsChunkTag() {
        val payload = ByteArray((BACKUP_V2_CHUNK_SIZE / 2).toInt() + 11) { (it % 251).toByte() }
        val container = writeV2(payload)
        val tampered = container.copyOf()
        // The container tail is chunk 1's tag; flipping one of its bytes must fail the chunk.
        tampered[tampered.size - 3] = (tampered[tampered.size - 3].toInt() xor 0x01).toByte()

        val failure = runCatching { decryptV2(tampered, password) }.exceptionOrNull()
        assertTrue(failure is BackupV2AuthenticationException, "a tampered tag must fail the chunk, got $failure")
        assertTrue(failure?.cause is javax.crypto.AEADBadTagException, "cause: ${failure?.cause}")
    }

    // ------------------------------------------------------------------ size equation on real bytes

    @Test
    fun theSizeEquationHoldsForZeroOneChunkAndOneChunkPlusOneRealContainers() {
        run {
            val container = writeV2(ByteArray(0))
            assertEquals(87L, container.size.toLong())
            val header = parsedHeaderOf(container)
            assertEquals(87L, backupContainerV2ExpectedSize(header))
            assertTrue(backupContainerV2SelfConsistent(header, container.size.toLong()))
        }
        run {
            val container = writeV2(ByteArray(BACKUP_V2_CHUNK_SIZE.toInt()))
            assertEquals(87L + BACKUP_V2_CHUNK_SIZE + BACKUP_TAG_LENGTH, container.size.toLong())
            val header = parsedHeaderOf(container)
            assertEquals(container.size.toLong(), backupContainerV2ExpectedSize(header))
            assertTrue(backupContainerV2SelfConsistent(header, container.size.toLong()))
        }
        run {
            val container = writeV2(ByteArray(BACKUP_V2_CHUNK_SIZE.toInt() + 1))
            assertEquals(87L + BACKUP_V2_CHUNK_SIZE + 1 + 2 * BACKUP_TAG_LENGTH, container.size.toLong())
            val header = parsedHeaderOf(container)
            assertEquals(container.size.toLong(), backupContainerV2ExpectedSize(header))
            assertTrue(backupContainerV2SelfConsistent(header, container.size.toLong()))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sha256(bytes: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes)
    }
}
