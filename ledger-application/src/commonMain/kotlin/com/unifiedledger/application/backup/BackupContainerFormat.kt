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
 * platform supplies the JVM/JCE implementation (`JvmBackupCryptoPrimitives` in ledger-application jvmMain).
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

// ---------------------------------------------------------------------------------------------
// Container format v2: chunked AEAD (container-format spec section 4.11, D-285). The v1 values
// above are FROZEN and unchanged; v2 only re-points two header fields and adds the chunking
// parameters below. The chunk size is a SPEC CONSTANT, deliberately not a header field: writing it
// into the header would make every chunk size a compatibility surface (spec section 4.11.2).
// ---------------------------------------------------------------------------------------------

/** `container_format_version` v2 (container-format spec section 4.11.2, offset 4); D-285. */
const val BACKUP_CONTAINER_FORMAT_VERSION_V2: Int = 2

/** `aead_id` = chunked AES-256-GCM, one independent seal per chunk (spec section 4.11.2, offset 7). */
const val BACKUP_AEAD_ID_CHUNKED: Int = 2

/** The v2 chunk size (spec section 4.11.2): 1 MiB; the last chunk may be 1..chunk_size bytes. */
const val BACKUP_V2_CHUNK_SIZE: Long = 1_048_576L

/**
 * The container overhead over the plaintext: fixed header (59) + salt (16) + IV (12) + tag (16)
 * = 103 bytes. The 06.B spec section 5 derives `container_size = plaintext_len + 103` from this;
 * since 1 GiB + 103 is far below the 2 GiB container bound, the 1 GiB plaintext gate is the
 * binding write-side limit and no separate container-size gate is needed.
 */
fun backupContainerOverheadBytes(): Long = (BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + BACKUP_IV_LENGTH + BACKUP_TAG_LENGTH).toLong()

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
fun backupContainerAad(
    header: ByteArray,
    salt: ByteArray,
): ByteArray {
    val aad = ByteArray(header.size + salt.size)
    header.copyInto(aad, 0)
    salt.copyInto(aad, header.size)
    return aad
}

/**
 * Assembles the 59-byte fixed header for container format v2 (container-format spec section
 * 4.11.2, D-285): byte-identical to [backupContainerHeader] except `container_format_version = 2`
 * (offset 4) and `aead_id = 2` (offset 7). The salt/IV lengths, KDF parameters, schema hint,
 * plaintext length and `payload_sha256` semantics are all unchanged from v1.
 */
fun backupContainerHeaderV2(
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
    writeU16(header, 4, BACKUP_CONTAINER_FORMAT_VERSION_V2.toLong())
    header[6] = BACKUP_KDF_ID.toByte()
    header[7] = BACKUP_AEAD_ID_CHUNKED.toByte()
    writeU32(header, 8, BACKUP_KDF_ITERATIONS.toLong())
    header[12] = BACKUP_SALT_LENGTH.toByte()
    header[13] = BACKUP_IV_LENGTH.toByte()
    header[14] = BACKUP_TAG_LENGTH.toByte()
    writeU32(header, 15, schemaVersion)
    writeU64(header, 19, plaintextLength)
    payloadSha256.copyInto(header, 27)
    return header
}

/**
 * The v2 per-chunk AAD (container-format spec section 4.11.3): `fixed header (59B) || salt ||
 * chunk index (BE u32, from 0)`. The chunk index inside the AAD is what makes chunk REORDERING,
 * chunk TRUNCATION and CROSS-CONTAINER SPLICING all fail tag verification (spec section 4.11.3);
 * it also makes the AAD unambiguously parseable, which the v1 `header || salt` composition already
 * guarantees because the fixed header carries `salt_len` (spec section 4.6).
 */
fun backupContainerV2ChunkAad(
    header: ByteArray,
    salt: ByteArray,
    chunkIndex: Long,
): ByteArray {
    require(chunkIndex in 0L..BACKUP_V2_CHUNK_INDEX_MAX) { "chunk index out of the u32 range: $chunkIndex" }
    val aad = ByteArray(header.size + salt.size + BACKUP_V2_CHUNK_AAD_INDEX_BYTES)
    header.copyInto(aad, 0)
    salt.copyInto(aad, header.size)
    writeChunkIndex(aad, header.size + salt.size, chunkIndex)
    return aad
}

/**
 * The v2 per-chunk IV (container-format spec section 4.11.3): the 12-byte base IV from the header
 * with its LAST 4 BYTES replaced by the chunk index (BE u32, from 0). The other 8 bytes stay fixed,
 * so the IVs of the chunks of one container are pairwise distinct and no IV is ever reused under
 * the per-container derived key (spec section 4.11.3).
 */
fun backupContainerV2ChunkIv(
    baseIv: ByteArray,
    chunkIndex: Long,
): ByteArray {
    require(baseIv.size == BACKUP_IV_LENGTH) { "the v2 base IV must be ${BACKUP_IV_LENGTH} bytes" }
    require(chunkIndex in 0L..BACKUP_V2_CHUNK_INDEX_MAX) { "chunk index out of the u32 range: $chunkIndex" }
    val iv = baseIv.copyOf()
    writeChunkIndex(iv, BACKUP_IV_LENGTH - BACKUP_V2_CHUNK_AAD_INDEX_BYTES, chunkIndex)
    return iv
}

/**
 * The v2 chunk count (container-format spec section 4.11.2): `ceil(plaintext_len / chunk_size)`;
 * a ZERO-length plaintext has ZERO chunks (spec section 4.11.4) — the same rejection path as the
 * v1 zero-length payload.
 */
fun backupContainerV2ChunkCount(plaintextLength: Long): Long = if (plaintextLength <= 0L) 0L else (plaintextLength + BACKUP_V2_CHUNK_SIZE - 1) / BACKUP_V2_CHUNK_SIZE

/**
 * The plaintext length of the chunk at [chunkIndex] (container-format spec section 4.11.2): every
 * chunk is [BACKUP_V2_CHUNK_SIZE] bytes except the last, which is `1..chunk_size` for a non-empty
 * payload. A zero-length payload has no chunks and returns 0.
 */
fun backupContainerV2ChunkPlaintextLength(
    plaintextLength: Long,
    chunkIndex: Long,
): Long {
    require(chunkIndex in 0L until backupContainerV2ChunkCount(plaintextLength)) {
        "chunk index $chunkIndex out of range for plaintext length $plaintextLength"
    }
    val fullChunks = plaintextLength / BACKUP_V2_CHUNK_SIZE
    return if (chunkIndex < fullChunks) BACKUP_V2_CHUNK_SIZE else plaintextLength - fullChunks * BACKUP_V2_CHUNK_SIZE
}

/**
 * The v2 container overhead over the plaintext (container-format spec section 4.11.4): fixed
 * header (59) + salt (16) + IV (12) + one 16-byte tag PER CHUNK. For a zero-length payload this
 * equals the v1 overhead minus the v1 tag (87 bytes) — a v2 empty container carries no tag at all.
 */
fun backupContainerV2OverheadBytes(plaintextLength: Long): Long =
    (BACKUP_FIXED_HEADER_LENGTH + BACKUP_SALT_LENGTH + BACKUP_IV_LENGTH).toLong() +
        backupContainerV2ChunkCount(plaintextLength) * BACKUP_TAG_LENGTH

/** The v2 chunk index is a BE u32 (spec section 4.11.3): 4 bytes inside the AAD and the IV. */
private const val BACKUP_V2_CHUNK_AAD_INDEX_BYTES: Int = 4

/** The u32 ceiling for the v2 chunk index (spec section 4.11.3). */
private const val BACKUP_V2_CHUNK_INDEX_MAX: Long = 0xFFFFFFFFL

/** Writes a chunk index as a BE u32 at [offset]; shared by the AAD and the derived IV. */
private fun writeChunkIndex(
    target: ByteArray,
    offset: Int,
    chunkIndex: Long,
) {
    for (index in 0 until BACKUP_V2_CHUNK_AAD_INDEX_BYTES) {
        target[offset + index] = ((chunkIndex ushr ((3 - index) * 8)) and 0xFF).toByte()
    }
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

    /**
     * P7-06 06.C (D-179; spec `2026-09-25-p7-06-restore-preflight-design.md` section 5.1): the
     * symmetric read-side AES-GCM decryptor, bound to one key, IV and AAD. Implementations MUST
     * supply the AAD before any `update`/`doFinal` (container-format spec section 4.5) and MUST NOT
     * use `CipherInputStream` (container-format spec section 4.9: it returns plaintext before the
     * tag is verified).
     */
    fun gcmDecryptor(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
    ): BackupGcmDecryptor
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

/**
 * P7-06 06.C (D-179; spec section 5.1): a single-use AES-GCM decryptor, symmetric to
 * [BackupGcmEncryptor]. The declared shape has ONLY the no-argument [doFinal], so the trailing
 * `tag_len` bytes MUST be fed through [update] and the tag is verified by the no-argument
 * [doFinal] (the 06.C spec section 5.1 fixes this so the declared port shape and the prose agree).
 *
 * [update] returns the plaintext decrypted from one ciphertext chunk (GCM may buffer, so it can be
 * empty); [doFinal] verifies the tag and returns any remaining plaintext. Until [doFinal] returns
 * successfully the produced plaintext MUST NOT be treated as trusted input (container-format spec
 * section 4.9). A tag failure throws the platform AEAD exception, which the caller maps to the
 * uniform `P706_CONTAINER_AUTHENTICATION_FAILED` code.
 */
interface BackupGcmDecryptor {
    fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray

    /** Verifies the tag and returns the remaining plaintext; throws on a tag failure. */
    fun doFinal(): ByteArray
}
