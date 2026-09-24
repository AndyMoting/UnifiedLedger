package com.unifiedledger.application.backup

/*
 * P7-06 06.B (D-177; spec `docs/specs/2026-09-24-p7-06-backup-export-design.md` sections 4 and 5,
 * authoritative byte format in `docs/specs/2026-09-24-p7-06-backup-container-format-design.md`
 * section 4): the frozen, platform-independent backup container format constants, the frozen
 * password widening, the fixed-header assembly and the crypto primitives port.
 *
 * Every value here is copied verbatim from the approved container-format spec section 4.3-4.6.
 * No value may be changed without reopening that spec (the 06.B spec section 8 forbids it); the
 * shared writer in app-ui consumes these constants so both platforms emit the identical bytes.
 *
 * The crypto primitives are a port because `javax.crypto` is not available in commonMain: the
 * shared writer owns the byte layout and the two-pass hash-then-encrypt ordering, while each
 * platform supplies the JVM/JCE implementation (`JvmBackupCrypto` in ledger-application jvmMain).
 */

/** ASCII `ULBK` (container-format spec section 4.3, offset 0). */
const val BACKUP_CONTAINER_MAGIC: String = "ULBK"

/** `container_format_version` v1 (spec section 4.3, offset 4); independent of the DB schema. */
const val BACKUP_CONTAINER_FORMAT_VERSION: Int = 1

/** `kdf_id` = PBKDF2WithHmacSHA256 (spec section 4.3, offset 6). */
const val BACKUP_KDF_ID: Int = 1

/** `aead_id` = AES-256-GCM (spec section 4.3, offset 7). */
const val BACKUP_AEAD_ID: Int = 1

/** `kdf_iterations` written by the export side (spec section 4.4). */
const val BACKUP_KDF_ITERATIONS: Int = 600_000

/** `salt_len` written by the export side (spec section 4.4). */
const val BACKUP_SALT_LENGTH: Int = 16

/** `iv_len` (spec section 4.5). */
const val BACKUP_IV_LENGTH: Int = 12

/** `tag_len` (spec section 4.5). */
const val BACKUP_TAG_LENGTH: Int = 16

/** The fixed header length, offsets 0..58 (spec section 4.3). */
const val BACKUP_FIXED_HEADER_LENGTH: Int = 59

/** The derived key length in bits (spec section 4.4). */
const val BACKUP_KEY_LENGTH_BITS: Int = 256

/** The fixed streaming buffer (spec section 5; container-format spec section 4.8). */
const val BACKUP_STREAM_CHUNK_BYTES: Int = 64 * 1024

/** The 1 GiB write-side plaintext bound (spec section 5; container-format spec section 4.8). */
const val BACKUP_MAX_PLAINTEXT_BYTES: Long = 1L shl 30

/** The 2 GiB container bound (read side; spec section 5). */
const val BACKUP_MAX_CONTAINER_BYTES: Long = 2L shl 30

/** The export-side disk headroom (spec section 3.2; container-format spec section 4.8). */
const val BACKUP_DISK_HEADROOM_BYTES: Long = 64L * 1024L * 1024L

/** The v1 minimum password length in Unicode code points (container-format spec section 4.10). */
const val BACKUP_MIN_PASSWORD_CODE_POINTS: Int = 8

/**
 * The container overhead over the plaintext: fixed header (59) + salt (16) + IV (12) + tag (16)
 * = 103 bytes. The 06.B spec section 5 derives `container_size = plaintext_len + 103` from this;
 * since 1 GiB + 103 is far below the 2 GiB container bound, the 1 GiB plaintext gate is the
 * binding write-side limit and no separate container-size gate is needed.
 */
fun backupContainerOverheadBytes(): Long =
    (BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + BACKUP_IV_LENGTH + BACKUP_TAG_LENGTH).toLong()

/**
 * The FROZEN v1 password widening (container-format spec section 4.4, verbatim): UTF-8 encode,
 * then one `char` per byte taking the low 8 bits. `password.toCharArray()` is FORBIDDEN — the two
 * disagree for non-ASCII passwords and would make the two ends mutually undecryptable. No Unicode
 * normalization is performed (v1).
 */
fun widenBackupPassword(password: String): CharArray {
    val bytes = password.encodeToByteArray()
    return CharArray(bytes.size) { index -> (bytes[index].toInt() and 0xFF).toChar() }
}

/**
 * Assembles the 59-byte fixed header (container-format spec section 4.3). All multi-byte integers
 * are big-endian unsigned; `db_schema_version` is the non-authoritative snapshot `user_version`
 * hint. The header is also the first part of the AAD (spec section 4.6), so it must be complete
 * before any ciphertext is produced — which is exactly why `payload_sha256` must be known first
 * (the two-pass order of the 06.B spec section 5).
 */
fun backupContainerHeader(
    schemaVersion: Long,
    plaintextLength: Long,
    payloadSha256: ByteArray,
): ByteArray {
    require(payloadSha256.size == 32) { "payload_sha256 must be 32 bytes" }
    val header = ByteArray(BACKUP_FIXED_HEADER_LENGTH)
    header[0] = 0x55
    header[1] = 0x4C
    header[2] = 0x42
    header[3] = 0x4B
    writeU16(header, 4, BACKUP_CONTAINER_FORMAT_VERSION.toLong())
    header[6] = BACKUP_KDF_ID.toByte()
    header[7] = BACKUP_AEAD_ID.toByte()
    writeU32(header, 8, BACKUP_KDF_ITERATIONS.toLong())
    header[12] = BACKUP_SALT_LENGTH.toByte()
    header[13] = BACKUP_IV_LENGTH.toByte()
    header[14] = BACKUP_TAG_LENGTH.toByte()
    writeU32(header, 15, schemaVersion)
    writeU64(header, 19, plaintextLength)
    payloadSha256.copyInto(header, 27)
    return header
}

/** The AAD is `fixed header || salt` (container-format spec section 4.6); the IV is excluded. */
fun backupContainerAad(header: ByteArray, salt: ByteArray): ByteArray {
    val aad = ByteArray(header.size + salt.size)
    header.copyInto(aad, 0)
    salt.copyInto(aad, header.size)
    return aad
}

private fun writeU16(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    target[offset] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 1] = (value and 0xFF).toByte()
}

private fun writeU32(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    for (index in 0 until 4) {
        target[offset + index] = ((value ushr ((3 - index) * 8)) and 0xFF).toByte()
    }
}

private fun writeU64(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    for (index in 0 until 8) {
        target[offset + index] = ((value ushr ((7 - index) * 8)) and 0xFF).toByte()
    }
}

/**
 * The platform crypto primitives the shared container writer needs. Implementations must be the
 * platform JCE providers (`javax.crypto`), never a third-party library (container-format spec
 * section 8: zero new crypto dependencies).
 */
interface BackupCryptoPrimitives {
    /** CSPRNG bytes for the salt/IV (container-format spec sections 4.4/4.5). */
    fun randomBytes(count: Int): ByteArray

    /**
     * Derives the AES key with `PBKDF2WithHmacSHA256`. [password] is the ALREADY-widened char
     * array ([widenBackupPassword]); the implementation constructs `PBEKeySpec(password, salt,
     * iterations, keyLengthBits)` and clears the spec after use.
     */
    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
    ): ByteArray

    /** A streaming SHA-256 accumulator; the snapshot is never read whole (spec section 5). */
    fun sha256Digest(): BackupSha256Digest

    /**
     * An AES-GCM encryptor bound to one key, IV and AAD. Implementations MUST supply the AAD
     * before any `update`/`doFinal` (container-format spec section 4.5).
     */
    fun gcmEncryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmEncryptor
}

/** A streaming SHA-256 accumulator (incremental `update`, never a whole-input digest). */
interface BackupSha256Digest {
    fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /** Finalizes and returns the 32-byte digest. */
    fun digest(): ByteArray
}

/**
 * A single-use AES-GCM encryptor. [update] returns the ciphertext produced for one plaintext
 * chunk (it may be empty while GCM buffers); [doFinal] returns the remaining ciphertext plus the
 * trailing 16-byte authentication tag.
 */
interface BackupGcmEncryptor {
    fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray

    fun doFinal(): ByteArray
}