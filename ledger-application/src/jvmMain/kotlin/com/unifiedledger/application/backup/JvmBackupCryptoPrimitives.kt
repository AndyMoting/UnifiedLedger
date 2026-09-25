package com.unifiedledger.application.backup

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * P7-06 06.B (D-177): the JVM/JCE implementation of [BackupCryptoPrimitives]. Both product
 * platforms are JVM/JCE environments (desktop JDK 21 `SunJCE`, Android `AndroidOpenSSL`), and the
 * container-format spec section 4.1 records that the same parameters produce byte-identical
 * derived keys and ciphertexts on both — so this single implementation serves both composition
 * roots and introduces no dependency (the 06.B spec section 8 lists exactly these primitives).
 *
 * Streaming SHA-256 uses `java.security.MessageDigest.update` incrementally; the product's
 * hand-written `Sha256.digestHex(ByteArray)` is deliberately NOT used because it only accepts a
 * fully-materialized array and would force a whole-file read (06.B spec section 5).
 */

/** The JVM/JCE crypto primitives (desktop and Android). */
class JvmBackupCryptoPrimitives(
    /**
     * An optional deterministic source for [randomBytes], used only by tests to pin the exact
     * container bytes against the frozen golden. The product always uses the platform CSPRNG.
     */
    private val deterministicRandomBytes: ((Int) -> ByteArray)? = null,
) : BackupCryptoPrimitives {
    private val secureRandom = SecureRandom()

    override fun randomBytes(count: Int): ByteArray = deterministicRandomBytes?.invoke(count) ?: ByteArray(count).also(secureRandom::nextBytes)

    override fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        try {
            val factory = SecretKeyFactory.getInstance(BACKUP_KDF_ALGORITHM)
            return factory.generateSecret(spec).encoded
        } finally {
            // Container-format spec section 4.10: the password char array is cleared after use.
            spec.clearPassword()
        }
    }

    override fun sha256Digest(): BackupSha256Digest = JvmSha256Digest(MessageDigest.getInstance("SHA-256"))

    override fun gcmEncryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmEncryptor {
        val cipher = Cipher.getInstance(BACKUP_AEAD_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(BACKUP_TAG_LENGTH * 8, iv))
        // AAD must be fully supplied before any ciphertext is produced (spec section 4.5).
        cipher.updateAAD(aad)
        return JvmGcmEncryptor(cipher)
    }

    override fun gcmDecryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmDecryptor {
        val cipher = Cipher.getInstance(BACKUP_AEAD_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(BACKUP_TAG_LENGTH * 8, iv))
        // AAD must be fully supplied before any ciphertext is decrypted (spec section 4.5).
        cipher.updateAAD(aad)
        return JvmGcmDecryptor(cipher)
    }
}

/** `PBKDF2WithHmacSHA256` (container-format spec section 4.4). */
private const val BACKUP_KDF_ALGORITHM: String = "PBKDF2WithHmacSHA256"

/** `AES/GCM/NoPadding` (container-format spec section 4.5). */
private const val BACKUP_AEAD_TRANSFORMATION: String = "AES/GCM/NoPadding"

private class JvmSha256Digest(
    private val digest: MessageDigest,
) : BackupSha256Digest {
    override fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        digest.update(bytes, offset, length)
    }

    override fun digest(): ByteArray = digest.digest()
}

private class JvmGcmEncryptor(
    private val cipher: Cipher,
) : BackupGcmEncryptor {
    override fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = if (length == 0) ByteArray(0) else cipher.update(bytes, offset, length) ?: ByteArray(0)

    override fun doFinal(): ByteArray = cipher.doFinal() ?: ByteArray(0)
}

/**
 * The JVM/JCE decryptor (P7-06 06.C, D-179; spec section 5.1). It uses explicit `update`/`doFinal`
 * and never `CipherInputStream` (container-format spec section 4.9). `doFinal` performs the GCM tag
 * verification and throws `AEADBadTagException` (a `javax.crypto.AEADBadTagException`) on failure.
 */
private class JvmGcmDecryptor(
    private val cipher: Cipher,
) : BackupGcmDecryptor {
    override fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray = if (length == 0) ByteArray(0) else cipher.update(bytes, offset, length) ?: ByteArray(0)

    override fun doFinal(): ByteArray = cipher.doFinal() ?: ByteArray(0)
}
