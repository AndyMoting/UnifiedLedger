package com.unifiedledger.application.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7-06 06.B (D-177; spec sections 4 and 5): the real-JCE byte-level golden for one small known
 * input. The salt and IV are injected deterministically (the only test-only seam on the primitives)
 * so the ENTIRE container — header, payload hash, derived key, ciphertext and tag — is asserted
 * byte for byte. This pins the frozen format against accidental drift: any change to the layout,
 * the KDF parameters or the widening breaks this test.
 *
 * The password is the container-format spec section 4.4 non-ASCII (CJK + emoji) vector, whose
 * derived key `4937c6e3...f1410044` is recorded there; a `toCharArray()` widening would produce a
 * different key and ciphertext and this test would go red.
 */
class BackupContainerCryptoJvmTest {
    private val cjkPassword = "\u5BC6\u7801\uD83D\uDD10"
    private val plaintext = "snapshot-bytes".encodeToByteArray()
    private val salt = "000102030405060708090a0b0c0d0e0f".hexToBytes()
    private val iv = "0f0e0d0c0b0a090807060504".hexToBytes()

    private fun deterministicCrypto() =
        JvmBackupCryptoPrimitives { count ->
            when (count) {
                BACKUP_SALT_LENGTH -> salt.copyOf()
                else -> iv.copyOf()
            }
        }

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

    @Test
    fun theWholeContainerMatchesTheFrozenGoldenBytes() {
        val sink = CollectingSink()
        val result =
            writeBackupContainer(
                plaintext = source(plaintext),
                sink = sink,
                password = cjkPassword,
                schemaVersion = 31,
                plaintextLength = plaintext.size.toLong(),
                crypto = deterministicCrypto(),
            )

        val expected =
            "554c424b00010101000927c0100c100000001f000000000000000e" +
                "7783d47a378f6c3ca8d1b29aa1b688ff6b14d26bd3bf8bae14785138db1eff0c" +
                "000102030405060708090a0b0c0d0e0f" +
                "0f0e0d0c0b0a090807060504" +
                "6dd125c90a76e8cffcfe3943666ad688916b725d45073987b67f1daeba8c"
        assertContentEquals(expected.hexToBytes(), sink.bytes.toByteArray())
        assertEquals(117L, result.containerLength)
        assertContentEquals("7783d47a378f6c3ca8d1b29aa1b688ff6b14d26bd3bf8bae14785138db1eff0c".hexToBytes(), result.payloadSha256)
    }

    @Test
    fun theDerivedKeyMatchesTheFrozenNonAsciiVector() {
        val key = JvmBackupCryptoPrimitives().deriveKey(widenBackupPassword(cjkPassword), salt, BACKUP_KDF_ITERATIONS, BACKUP_KEY_LENGTH_BITS)

        assertContentEquals("4937c6e3d290d839d95a6e075b29eb3fa8fb6f4be8c050f416b3859ff1410044".hexToBytes(), key)
    }

    @Test
    fun aNonAsciiPasswordRoundTripsThroughWideningAndJceButToCharArrayWouldNot() {
        // Widening is UTF-8-byte based; `toCharArray()` (the forbidden form) yields a different
        // PBEKeySpec for this input, so the two derived keys must differ — which is exactly why
        // the frozen rule exists.
        val widened = JvmBackupCryptoPrimitives().deriveKey(widenBackupPassword(cjkPassword), salt, BACKUP_KDF_ITERATIONS, BACKUP_KEY_LENGTH_BITS)
        val viaToCharArray = JvmBackupCryptoPrimitives().deriveKey(cjkPassword.toCharArray(), salt, BACKUP_KDF_ITERATIONS, BACKUP_KEY_LENGTH_BITS)

        assertTrue(!widened.contentEquals(viaToCharArray), "the widened and toCharArray keys must differ for a non-ASCII password")
    }

    @Test
    fun theStreamingSha256MatchesTheOneShotDigestForAChunkedRead() {
        val payload = ByteArray(200_000) { (it * 7 % 251).toByte() }
        val digest = JvmBackupCryptoPrimitives().sha256Digest()
        var position = 0
        while (position < payload.size) {
            val count = minOf(4096, payload.size - position)
            digest.update(payload, position, count)
            position += count
        }

        val expected = sha256(payload)
        assertContentEquals(expected, digest.digest())
    }

    // ------------------------------------------------- uniform authentication failure (spec 4.5)

    /**
     * Decrypts a produced container with real JCE, the way the 06.C read side will. This is a test
     * routine (not product code — preflight belongs to 06.C) that proves the WRITE side satisfies
     * the container-format spec section 4.5 uniform-failure obligation: the artifact it emits is
     * genuinely authenticated, so a wrong password and a tampered ciphertext are indistinguishable
     * (the same single failure) while the right password recovers the plaintext.
     */
    private fun decryptContainer(
        container: ByteArray,
        password: String,
    ): ByteArray {
        val header = container.copyOfRange(0, BACKUP_FIXED_HEADER_LENGTH)
        val salt = container.copyOfRange(BACKUP_FIXED_HEADER_LENGTH, BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH)
        val ivStart = BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH
        val iv = container.copyOfRange(ivStart, ivStart + BACKUP_IV_LENGTH)
        val ciphertextAndTag = container.copyOfRange(ivStart + BACKUP_IV_LENGTH, container.size)
        val key =
            JvmBackupCryptoPrimitives().deriveKey(
                widenBackupPassword(password),
                salt,
                BACKUP_KDF_ITERATIONS,
                BACKUP_KEY_LENGTH_BITS,
            )
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(BACKUP_TAG_LENGTH * 8, iv),
        )
        cipher.updateAAD(backupContainerAad(header, salt))
        return cipher.doFinal(ciphertextAndTag)
    }

    private fun writeGoldenContainer(payload: ByteArray): ByteArray {
        val sink = CollectingSink()
        writeBackupContainer(
            plaintext = source(payload),
            sink = sink,
            password = cjkPassword,
            schemaVersion = 31,
            plaintextLength = payload.size.toLong(),
            crypto = deterministicCrypto(),
        )
        return sink.bytes.toByteArray()
    }

    @Test
    fun theRightPasswordRecoversThePlaintextAndTheHeaderHashMatches() {
        val container = writeGoldenContainer(plaintext)

        val recovered = decryptContainer(container, cjkPassword)

        assertContentEquals(plaintext, recovered)
        // The header's payload_sha256 (offset 27) is the real SHA-256 of the recovered plaintext.
        val headerHash = container.copyOfRange(27, 59)
        assertContentEquals(sha256(recovered), headerHash)
    }

    @Test
    fun aWrongPasswordAndATamperedCiphertextFailWithTheSameSingleAuthenticationFailure() {
        val container = writeGoldenContainer(plaintext)

        // Wrong password.
        val wrongPassword =
            runCatching { decryptContainer(container, "password123") }.exceptionOrNull()
        // A single flipped ciphertext byte (same length, so no structural/size difference).
        val tampered = container.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        val tamperedFailure =
            runCatching { decryptContainer(tampered, cjkPassword) }.exceptionOrNull()

        assertTrue(
            wrongPassword is javax.crypto.AEADBadTagException,
            "a wrong password must fail as AEADBadTagException, got $wrongPassword",
        )
        assertTrue(
            tamperedFailure is javax.crypto.AEADBadTagException,
            "a tampered ciphertext must fail as AEADBadTagException, got $tamperedFailure",
        )
        // The uniform-failure obligation: identical exception class, so no oracle distinguishes the
        // two cases.
        assertEquals(wrongPassword::class, tamperedFailure::class)
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

private fun sha256(bytes: ByteArray): ByteArray {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes)
}
