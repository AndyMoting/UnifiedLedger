package com.unifiedledger.application.backup

/*
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 4.6 and 5): the
 * shared container writer. It owns the frozen byte layout, the two-pass hash-then-encrypt order
 * and the fixed 64 KiB streaming buffer; it is platform-independent (the crypto primitives and the
 * chunk source/sink are ports), so the exact bytes can be asserted in a JVM test with real JCE
 * primitives and in a common test with a deterministic fake.
 *
 * ORDER IS LOAD-BEARING (spec section 5): `payload_sha256` sits at fixed-header offset 27 and the
 * fixed header is the encryption AAD, so the hash MUST be fully computed before the header is
 * written. There is no single-pass ordering. Pass 1 is a bounded streaming read that completes the
 * digest; pass 2 is a second bounded streaming read that encrypts. Neither pass may materialize the
 * plaintext in memory.
 */

/**
 * A re-openable source of plaintext chunks (the snapshot). [open] must return a fresh reader at
 * the start of the stream: the writer opens it twice (hash pass, encrypt pass).
 */
fun interface BackupPlaintextSource {
    fun open(): BackupPlaintextReader
}

/** A bounded chunked reader (the `InputStream` convention: a non-positive count ends the stream). */
interface BackupPlaintextReader : AutoCloseable {
    fun read(buffer: ByteArray): Int
}

/** A chunked sink for the produced container bytes. */
fun interface BackupContainerSink {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )
}

/** The result of writing one container. */
class BackupContainerWriteResult(
    /** The exact plaintext byte count written into the header (the pass-1 count). */
    val plaintextLength: Long,
    /** The total container size: header + salt + IV + ciphertext + tag. */
    val containerLength: Long,
    /** The 32-byte plaintext SHA-256 that was written into the header. */
    val payloadSha256: ByteArray,
)

/**
 * Writes one authenticated container (spec section 4.6, steps 1-8) to [sink].
 *
 * [plaintextLength] is the expected plaintext size (the snapshot file length). Pass 1 both hashes
 * and verifies that exactly that many bytes are available; a mismatch fails before any container
 * byte is produced, so the sink never receives a header describing a length it cannot back.
 */
fun writeBackupContainer(
    plaintext: BackupPlaintextSource,
    sink: BackupContainerSink,
    password: String,
    schemaVersion: Long,
    plaintextLength: Long,
    crypto: BackupCryptoPrimitives,
): BackupContainerWriteResult {
    val salt = crypto.randomBytes(BACKUP_SALT_LENGTH)
    val iv = crypto.randomBytes(BACKUP_IV_LENGTH)
    val widened = widenBackupPassword(password)
    val key = crypto.deriveKey(widened, salt, BACKUP_KDF_ITERATIONS, BACKUP_KEY_LENGTH_BITS)
    try {
        // Pass 1: bounded streaming read that COMPLETES payload_sha256 before the header exists.
        val digest = crypto.sha256Digest()
        val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
        var hashed = 0L
        plaintext.open().use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                hashed += read.toLong()
            }
        }
        require(hashed == plaintextLength) {
            "snapshot length changed between sizing and hashing: expected $plaintextLength, read $hashed"
        }
        val payloadSha256 = digest.digest()

        // The header (offset 27 carries payload_sha256) must be complete before encryption: it is
        // the AAD, so it can only be written after pass 1 finished.
        val header = backupContainerHeader(schemaVersion, plaintextLength, payloadSha256)
        sink.write(header, 0, header.size)
        sink.write(salt, 0, salt.size)
        sink.write(iv, 0, iv.size)

        // Pass 2: a second bounded streaming read; AAD is supplied before any ciphertext.
        val aad = backupContainerAad(header, salt)
        val encryptor = crypto.gcmEncryptor(key, iv, aad)
        var encrypted = 0L
        plaintext.open().use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                val produced = encryptor.update(buffer, 0, read)
                if (produced.isNotEmpty()) sink.write(produced, 0, produced.size)
                encrypted += read.toLong()
            }
        }
        require(encrypted == plaintextLength) {
            "snapshot length changed between the hash pass and the encrypt pass: expected $plaintextLength, read $encrypted"
        }
        val tail = encryptor.doFinal()
        if (tail.isNotEmpty()) sink.write(tail, 0, tail.size)

        return BackupContainerWriteResult(
            plaintextLength = plaintextLength,
            containerLength = backupContainerOverheadBytes() + plaintextLength,
            payloadSha256 = payloadSha256,
        )
    } finally {
        // Container-format spec section 4.10: clear the password characters and derived key.
        widened.fill('\u0000')
        key.fill(0)
    }
}

/**
 * Writes one authenticated container in the CHUNKED v2 format (container-format spec section 4.11,
 * D-285) to [sink]. The v1 [writeBackupContainer] above is untouched: v1 stays writable for the
 * compatibility proof, and both writers share the frozen widening, the two-pass hash-then-encrypt
 * order and the bounded 64 KiB streaming buffer.
 *
 * The two-pass order is LOAD-BEARING exactly as in v1: `payload_sha256` sits in the fixed header
 * and the header is inside every chunk's AAD, so pass 1 must complete the digest before the header
 * exists. Pass 2 seals one AES-256-GCM chunk per [BACKUP_V2_CHUNK_SIZE] bytes of plaintext with the
 * per-chunk IV ([backupContainerV2ChunkIv]) and per-chunk AAD ([backupContainerV2ChunkAad]) — the
 * per-chunk independent seal is what bounds the platform AEAD buffer (D-285: Android's GCM buffers
 * the whole message until `doFinal`, so with v2 the message it buffers is at most one chunk).
 */
fun writeBackupContainerV2(
    plaintext: BackupPlaintextSource,
    sink: BackupContainerSink,
    password: String,
    schemaVersion: Long,
    plaintextLength: Long,
    crypto: BackupCryptoPrimitives,
): BackupContainerWriteResult {
    val salt = crypto.randomBytes(BACKUP_SALT_LENGTH)
    val baseIv = crypto.randomBytes(BACKUP_IV_LENGTH)
    val widened = widenBackupPassword(password)
    val key = crypto.deriveKey(widened, salt, BACKUP_KDF_ITERATIONS, BACKUP_KEY_LENGTH_BITS)
    try {
        // Pass 1: bounded streaming read that COMPLETES payload_sha256 before the header exists.
        val digest = crypto.sha256Digest()
        val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
        var hashed = 0L
        plaintext.open().use { reader ->
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                hashed += read.toLong()
            }
        }
        require(hashed == plaintextLength) {
            "snapshot length changed between sizing and hashing: expected $plaintextLength, read $hashed"
        }
        val payloadSha256 = digest.digest()

        // The header (offset 27 carries payload_sha256) must be complete before encryption: it is
        // inside every chunk's AAD, so it can only be written after pass 1 finished.
        val header = backupContainerHeaderV2(schemaVersion, plaintextLength, payloadSha256)
        sink.write(header, 0, header.size)
        sink.write(salt, 0, salt.size)
        sink.write(baseIv, 0, baseIv.size)

        // Pass 2: a second bounded streaming read; every chunk is an independent seal whose AAD
        // and IV bind the chunk index, so reordering/truncation/splicing fails authentication.
        // The reader is opened ONCE for the whole pass: the chunk windows are sequential slices of
        // the same stream, exactly like the v1 single-seal loop.
        var encrypted = 0L
        var chunkIndex = 0L
        plaintext.open().use { reader ->
            while (encrypted < plaintextLength) {
                val chunkPlaintextLength = backupContainerV2ChunkPlaintextLength(plaintextLength, chunkIndex)
                val encryptor =
                    crypto.gcmEncryptor(
                        key,
                        backupContainerV2ChunkIv(baseIv, chunkIndex),
                        backupContainerV2ChunkAad(header, salt, chunkIndex),
                    )
                var sealed = 0L
                while (sealed < chunkPlaintextLength) {
                    // Read EXACTLY the remaining chunk window: a whole-buffer read could overshoot
                    // a short final window, so the target is sized to the window remainder (only
                    // the last sub-64-KiB read allocates).
                    val want = minOf(buffer.size.toLong(), chunkPlaintextLength - sealed).toInt()
                    val target = if (want == buffer.size) buffer else ByteArray(want)
                    val read = reader.read(target)
                    require(read > 0) {
                        "snapshot length changed between the hash pass and the encrypt pass: " +
                            "expected $plaintextLength, read $encrypted"
                    }
                    sealed += read.toLong()
                    encrypted += read.toLong()
                    val produced = encryptor.update(target, 0, read)
                    if (produced.isNotEmpty()) sink.write(produced, 0, produced.size)
                }
                val tail = encryptor.doFinal()
                if (tail.isNotEmpty()) sink.write(tail, 0, tail.size)
                chunkIndex += 1L
            }
        }

        return BackupContainerWriteResult(
            plaintextLength = plaintextLength,
            containerLength = backupContainerV2OverheadBytes(plaintextLength) + plaintextLength,
            payloadSha256 = payloadSha256,
        )
    } finally {
        // Container-format spec section 4.10: clear the password characters and derived key.
        widened.fill('\u0000')
        key.fill(0)
    }
}
