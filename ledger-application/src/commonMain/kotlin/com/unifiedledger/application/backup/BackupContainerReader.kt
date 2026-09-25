package com.unifiedledger.application.backup

/*
 * P7-06 06.C (D-179; spec `docs/specs/2026-09-25-p7-06-restore-preflight-design.md` section 4): the
 * read-side container parser. It mirrors the write-side assembler in [BackupContainerFormat] — the
 * same frozen constants, the same 59-byte fixed header, the same big-endian unsigned layout — and
 * adds the missing read primitives (`readU16`/`readU32`/`readU64`) and the pre-authentication
 * public format/size checks of container-format spec section 4.3.1 and section 4.8.
 *
 * Everything here is a PURE function over a byte array (no IO, no password, no platform call), so
 * the frozen rejection table is directly assertable in commonTest, exactly like the write-side
 * header assembler.
 *
 * NO FROZEN VALUE IS CHANGED. The rejection code names of the container-format spec section 4.3.1
 * (`P706_CONTAINER_FORMAT_UNSUPPORTED`, `P706_CONTAINER_VERSION_UNSUPPORTED`, `P706_KDF_UNSUPPORTED`,
 * `P706_AEAD_UNSUPPORTED`, `P706_KDF_PARAMETERS_UNSUPPORTED`, `P706_AEAD_PARAMETERS_UNSUPPORTED`),
 * section 4.8 (`P706_CONTAINER_TOO_LARGE`), section 4.7 (`P706_CONTAINER_AUTHENTICATION_FAILED`,
 * `P706_PAYLOAD_INTEGRITY_FAILED`), section 4.3.2/5.4 (`P706_SCHEMA_VERSION_UNSUPPORTED`) are reused
 * verbatim. The remaining names are PROPOSED by the 06.C design spec section 10 item 3 and are NOT
 * frozen; they are marked as such in [BackupPreflightRejection] and must not be presented as frozen.
 */

/**
 * The typed rejection reasons of a restore preflight. Every value is a code name, never a message:
 * the shared UI maps it to user-facing copy, and no value carries a path, a password or any
 * plaintext.
 *
 * FROZEN (container-format spec section 4.3.1 / 4.7 / 4.8, reused verbatim):
 * [P706_CONTAINER_FORMAT_UNSUPPORTED], [P706_CONTAINER_VERSION_UNSUPPORTED], [P706_KDF_UNSUPPORTED],
 * [P706_AEAD_UNSUPPORTED], [P706_KDF_PARAMETERS_UNSUPPORTED], [P706_AEAD_PARAMETERS_UNSUPPORTED],
 * [P706_CONTAINER_TOO_LARGE], [P706_CONTAINER_AUTHENTICATION_FAILED],
 * [P706_PAYLOAD_INTEGRITY_FAILED], [P706_SCHEMA_VERSION_UNSUPPORTED].
 *
 * PROPOSED (06.C design spec section 10 item 3 — "建议名，需批准", NOT frozen):
 * [P706_CONTAINER_TRUNCATED], [P706_PLAINTEXT_TOO_LARGE], [P706_CONTAINER_SIZE_MISMATCH],
 * [P706_LEDGER_IDENTITY_UNSUPPORTED], [P706_SOURCE_READ_FAILED], [P706_MIGRATION_FAILED],
 * [P706_DOMAIN_VALIDATION_FAILED], [P706_INSUFFICIENT_SPACE].
 */
enum class BackupPreflightRejection {
    // ---------------------------------------------------------------- FROZEN (container spec 4.3.1)
    /** `magic != "ULBK"` (container-format spec section 4.3.1). */
    P706_CONTAINER_FORMAT_UNSUPPORTED,

    /** `container_format_version != 1` (container-format spec section 4.3.1). */
    P706_CONTAINER_VERSION_UNSUPPORTED,

    /** `kdf_id != 1` (container-format spec section 4.3.1). */
    P706_KDF_UNSUPPORTED,

    /** `aead_id != 1` (container-format spec section 4.3.1). */
    P706_AEAD_UNSUPPORTED,

    /** `salt_len ∉ 16..32` or `kdf_iterations ∉ [600000, 10000000]` (spec section 4.3.1). */
    P706_KDF_PARAMETERS_UNSUPPORTED,

    /** `iv_len != 12` or `tag_len != 16` (container-format spec section 4.3.1). */
    P706_AEAD_PARAMETERS_UNSUPPORTED,

    /** Container size > 2 GiB (container-format spec section 4.8; frozen). */
    P706_CONTAINER_TOO_LARGE,

    /** Wrong password, tag failure or tampered ciphertext — one uniform code (spec section 4.7). */
    P706_CONTAINER_AUTHENTICATION_FAILED,

    /** Post-authentication storage-corruption check failed (container-format spec section 4.7). */
    P706_PAYLOAD_INTEGRITY_FAILED,

    /** Post-authentication payload `PRAGMA user_version` is unknown/zero/future/mismatched (4.3.2/5.4). */
    P706_SCHEMA_VERSION_UNSUPPORTED,

    // ------------------------------------------------- PROPOSED (06.C spec section 10 item 3)
    /** PROPOSED: fewer than the 59 fixed-header bytes are available (06.C spec section 3.2). */
    P706_CONTAINER_TRUNCATED,

    /** PROPOSED: `plaintext_len` exceeds the 1 GiB bound (container-format spec section 4.8). */
    P706_PLAINTEXT_TOO_LARGE,

    /** PROPOSED: `plaintext_len + 59 + salt_len + iv_len + tag_len != container size` (spec 4.8). */
    P706_CONTAINER_SIZE_MISMATCH,

    /** PROPOSED: cross-ledger / extra-ledger container (container-format spec section 5.4). */
    P706_LEDGER_IDENTITY_UNSUPPORTED,

    /** PROPOSED: the user-chosen source could not be read (06.C spec section 3.1). */
    P706_SOURCE_READ_FAILED,

    /** PROPOSED: the strict isolated migration failed (06.C spec section 6.3). */
    P706_MIGRATION_FAILED,

    /** PROPOSED: the post-migration integrity/FK/domain validation failed (spec section 6.4). */
    P706_DOMAIN_VALIDATION_FAILED,

    /** PROPOSED: available space is below the restore-side precheck (container spec section 4.8). */
    P706_INSUFFICIENT_SPACE,
}

/** The parsed fixed header (offsets 0..58; container-format spec section 4.3, big-endian unsigned). */
class BackupContainerHeader(
    /** `container_format_version` (offset 4); must be [BACKUP_CONTAINER_FORMAT_VERSION]. */
    val containerFormatVersion: Int,
    /** `kdf_id` (offset 6); must be [BACKUP_KDF_ID]. */
    val kdfId: Int,
    /** `aead_id` (offset 7); must be [BACKUP_AEAD_ID]. */
    val aeadId: Int,
    /** `kdf_iterations` (offset 8); the header value, validated into `[600000, 10000000]`. */
    val kdfIterations: Long,
    /** `salt_len` (offset 12); validated into `16..32`. */
    val saltLength: Int,
    /** `iv_len` (offset 13); validated to equal [BACKUP_IV_LENGTH]. */
    val ivLength: Int,
    /** `tag_len` (offset 14); validated to equal [BACKUP_TAG_LENGTH]. */
    val tagLength: Int,
    /**
     * `db_schema_version` (offset 15). NON-AUTHORITATIVE hint (container-format spec section 4.3.2):
     * the authoritative check is the payload's `PRAGMA user_version` after authentication.
     */
    val dbSchemaVersion: Long,
    /** `plaintext_len` (offset 19); the snapshot byte count, bounded by 1 GiB. */
    val plaintextLength: Long,
    /** `payload_sha256` (offset 27, 32 bytes); the post-authentication corruption check. */
    val payloadSha256: ByteArray,
)

/** The outcome of parsing the fixed header. */
sealed interface BackupHeaderParseResult {
    data class Parsed(
        val header: BackupContainerHeader,
    ) : BackupHeaderParseResult

    data class Rejected(
        val code: BackupPreflightRejection,
    ) : BackupHeaderParseResult
}

/**
 * Parses the 59-byte fixed header and applies EVERY pre-authentication public format/size check
 * that the fixed header alone can decide (container-format spec section 4.3.1 plus the
 * `plaintext_len` bound of section 4.8). The checks are short-circuit-safe: they depend on no
 * password and no decryption, so they are not a password oracle (container-format spec section 4.7
 * class 1).
 *
 * [bytes] may be longer than the fixed header; only offsets 0..58 are read. Fewer than 59 bytes is
 * [BackupPreflightRejection.P706_CONTAINER_TRUNCATED] (a PROPOSED code — a sub-59-byte file cannot
 * satisfy the frozen `magic` condition, so the frozen `P706_CONTAINER_FORMAT_UNSUPPORTED` must not
 * be widened to cover it).
 *
 * The container-size self-consistency equation needs the actual container size and is therefore NOT
 * decided here; call [backupContainerSelfConsistent].
 */
fun parseBackupContainerHeader(bytes: ByteArray): BackupHeaderParseResult {
    if (bytes.size < BACKUP_FIXED_HEADER_LENGTH) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_CONTAINER_TRUNCATED)
    }
    if (bytes[0] != MAGIC_BYTES[0] || bytes[1] != MAGIC_BYTES[1] || bytes[2] != MAGIC_BYTES[2] || bytes[3] != MAGIC_BYTES[3]) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_CONTAINER_FORMAT_UNSUPPORTED)
    }
    val formatVersion = readU16(bytes, 4)
    if (formatVersion != BACKUP_CONTAINER_FORMAT_VERSION.toLong()) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_CONTAINER_VERSION_UNSUPPORTED)
    }
    val kdfId = bytes[6].toInt() and 0xFF
    if (kdfId != BACKUP_KDF_ID) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_KDF_UNSUPPORTED)
    }
    val aeadId = bytes[7].toInt() and 0xFF
    if (aeadId != BACKUP_AEAD_ID) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_AEAD_UNSUPPORTED)
    }
    val iterations = readU32(bytes, 8)
    val saltLength = bytes[12].toInt() and 0xFF
    val ivLength = bytes[13].toInt() and 0xFF
    val tagLength = bytes[14].toInt() and 0xFF
    if (saltLength !in BACKUP_SALT_LENGTH_MIN..BACKUP_SALT_LENGTH_MAX || iterations !in BACKUP_KDF_ITERATIONS_MIN..BACKUP_KDF_ITERATIONS_MAX) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_KDF_PARAMETERS_UNSUPPORTED)
    }
    if (ivLength != BACKUP_IV_LENGTH || tagLength != BACKUP_TAG_LENGTH) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_AEAD_PARAMETERS_UNSUPPORTED)
    }
    val schemaVersion = readU32(bytes, 15)
    val plaintextLength = readU64(bytes, 19)
    // `plaintext_len` is u64: a negative Long means the top bit is set, i.e. a value far above the
    // 1 GiB bound (container-format spec section 4.8). Either way it is a plaintext-limit rejection.
    if (plaintextLength < 0L || plaintextLength > BACKUP_MAX_PLAINTEXT_BYTES) {
        return BackupHeaderParseResult.Rejected(BackupPreflightRejection.P706_PLAINTEXT_TOO_LARGE)
    }
    val payloadSha256 = bytes.copyOfRange(27, 27 + BACKUP_PAYLOAD_SHA256_LENGTH)
    return BackupHeaderParseResult.Parsed(
        BackupContainerHeader(
            containerFormatVersion = formatVersion.toInt(),
            kdfId = kdfId,
            aeadId = aeadId,
            kdfIterations = iterations,
            saltLength = saltLength,
            ivLength = ivLength,
            tagLength = tagLength,
            dbSchemaVersion = schemaVersion,
            plaintextLength = plaintextLength,
            payloadSha256 = payloadSha256,
        ),
    )
}

/** The exact container size the header describes: `plaintext_len + 59 + salt_len + iv_len + tag_len`. */
fun backupContainerExpectedSize(header: BackupContainerHeader): Long =
    header.plaintextLength +
        BACKUP_FIXED_HEADER_LENGTH.toLong() +
        header.saltLength.toLong() +
        header.ivLength.toLong() +
        header.tagLength.toLong()

/**
 * Container-format spec section 4.8 self-consistency: `plaintext_len + 59 + salt_len + iv_len +
 * tag_len == container_size`. Decidable from the fixed header plus the actual container size, so it
 * is a pre-authentication check (section 4.7 class 1). A mismatch is
 * [BackupPreflightRejection.P706_CONTAINER_SIZE_MISMATCH] (PROPOSED name).
 */
fun backupContainerSelfConsistent(
    header: BackupContainerHeader,
    containerSize: Long,
): Boolean = backupContainerExpectedSize(header) == containerSize

/** The container size bound check (container-format spec section 4.8, frozen code). */
fun backupContainerWithinSizeBound(containerSize: Long): Boolean = containerSize <= BACKUP_MAX_CONTAINER_BYTES

/** Big-endian unsigned `u16`. */
private fun readU16(
    source: ByteArray,
    offset: Int,
): Long = ((source[offset].toLong() and 0xFF) shl 8) or (source[offset + 1].toLong() and 0xFF)

/** Big-endian unsigned `u32`. */
private fun readU32(
    source: ByteArray,
    offset: Int,
): Long {
    var value = 0L
    for (index in 0 until 4) {
        value = (value shl 8) or (source[offset + index].toLong() and 0xFF)
    }
    return value
}

/** Big-endian `u64`; a negative result means the top bit is set (out of the safe unsigned range). */
private fun readU64(
    source: ByteArray,
    offset: Int,
): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (source[offset + index].toLong() and 0xFF)
    }
    return value
}

/** ASCII `ULBK` (container-format spec section 4.3, offset 0). */
private val MAGIC_BYTES: ByteArray = BACKUP_CONTAINER_MAGIC.encodeToByteArray()

/** The `payload_sha256` field length (container-format spec section 4.3, offset 27). */
private const val BACKUP_PAYLOAD_SHA256_LENGTH: Int = 32

/** The read-side accepted `salt_len` lower bound (container-format spec section 4.4: 16..32). */
private const val BACKUP_SALT_LENGTH_MIN: Int = 16

/** The read-side accepted `salt_len` upper bound (container-format spec section 4.4). */
private const val BACKUP_SALT_LENGTH_MAX: Int = 32

/** The `kdf_iterations` lower bound (container-format spec section 4.4). */
private const val BACKUP_KDF_ITERATIONS_MIN: Long = 600_000L

/** The `kdf_iterations` upper bound (container-format spec section 4.4). */
private const val BACKUP_KDF_ITERATIONS_MAX: Long = 10_000_000L
