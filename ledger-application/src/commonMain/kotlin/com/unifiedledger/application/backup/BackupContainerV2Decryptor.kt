package com.unifiedledger.application.backup

/*
 * P7-06 container v2 (spec `docs/specs/2026-09-24-p7-06-backup-container-format-design.md` section
 * 4.11, D-285): the streaming CHUNKED decrypt walk shared by the read side. The v1 single-seal path
 * (a whole-container decryptor fed `ciphertext || tag`) stays exactly as it is; this file is
 * purely additive.
 *
 * The walk mirrors the v2 writer parameters verbatim (spec section 4.11.3): per chunk an
 * independent single-use decryptor keyed with the per-chunk IV (`backupContainerV2ChunkIv`) and the
 * per-chunk AAD (`backupContainerV2ChunkAad` = header || salt || chunk index), the on-wire chunk
 * `ciphertext || tag` fed through `update` in bounded reads and the tag verified by the
 * no-argument `doFinal`. ANY chunk failing — wrong password, tampered bytes, reordered chunks, a
 * truncated tail, a spliced foreign chunk — surfaces as [BackupV2AuthenticationException] from that
 * chunk's `doFinal` (the platform AEAD exception rides as the cause), which the caller maps to the
 * same uniform `P706_CONTAINER_AUTHENTICATION_FAILED` code as the v1 path (spec section 4.11.4:
 * the whole decrypt fails; NO plaintext of a partially-authenticated prefix is committed).
 */

/** The platform AEAD tag verification of one v2 chunk failed (the uniform cryptographic failure). */
class BackupV2AuthenticationException(cause: Throwable) : RuntimeException("v2 chunk tag verification failed", cause)

/** The v2 cipher stream ended inside a chunk window (structurally short, NOT a crypto failure). */
class ShortChunkStreamException : RuntimeException("v2 cipher stream ended inside a chunk window")

/**
 * The v2 chunk-level source: the container after the fixed header, salt and base IV. [read] fills
 * [buffer] with the next ciphertext bytes (the `InputStream` convention: a non-positive count ends
 * the stream).
 */
fun interface BackupV2CipherSource {
    fun read(buffer: ByteArray): Int
}

/** The v2 chunk-level sink receiving the produced plaintext. */
fun interface BackupV2PlaintextSink {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )
}

/**
 * Streams the v2 payload of [header] through the per-chunk decryptors, writing every authenticated
 * plaintext byte to [plaintextSink] and returning the total plaintext length (which the caller
 * checks against the header before treating the staging file as trusted, exactly like v1).
 *
 * [key] is the ALREADY-derived key (the caller derives it from the header's salt/iterations so the
 * KDF runs exactly once per restore; the caller owns clearing it). The walk never holds more than
 * one 64 KiB read plus one chunk's decrypted output: memory stays bounded regardless of the
 * container size — the property D-285's chunking exists to guarantee. A caller that aborts mid-walk
 * must discard anything already written to its sink: the rollback semantics of spec section
 * 4.11.4 / section 5.3 belong to the caller's staging lifecycle, not to this pure walk.
 *
 * @throws BackupV2AuthenticationException when any chunk's tag verification fails (uniform code).
 * @throws ShortChunkStreamException when the cipher stream ends before the header's chunk count.
 */
fun decryptBackupContainerV2(
    cipherSource: BackupV2CipherSource,
    plaintextSink: BackupV2PlaintextSink,
    header: BackupContainerHeader,
    salt: ByteArray,
    baseIv: ByteArray,
    key: ByteArray,
    crypto: BackupCryptoPrimitives,
): Long {
    require(header.containerFormatVersion == BACKUP_CONTAINER_FORMAT_VERSION_V2) {
        "the v2 decrypt walk requires a parsed v2 header"
    }
    val headerBytes = headerBytesOf(header)
    val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
    val chunkCount = backupContainerV2ChunkCount(header.plaintextLength)
    var produced = 0L
    for (chunkIndex in 0L until chunkCount) {
        val chunkPlaintextLength = backupContainerV2ChunkPlaintextLength(header.plaintextLength, chunkIndex)
        val decryptor =
            crypto.gcmDecryptor(
                key,
                backupContainerV2ChunkIv(baseIv, chunkIndex),
                backupContainerV2ChunkAad(headerBytes, salt, chunkIndex),
            )
        var consumed = 0L
        // The on-wire chunk is `ciphertext (== chunk plaintext length) || tag (16)`: the trailing
        // tag MUST ride `update` so the no-argument `doFinal` can verify it (the declared port
        // shape, as in the v1 path).
        val chunkWireLength = chunkPlaintextLength + header.tagLength
        while (consumed < chunkWireLength) {
            // Read EXACTLY the remaining chunk window (the writer pass-2 pattern, spec section
            // 4.11.3): an unclamped whole-buffer read would overshoot a short window — on chunk 0
            // (wire 1 MiB + 16 = 16 x 64 KiB + 16) it swallows the next chunk's `ciphertext || tag`
            // wire into THIS chunk's decryptor, failing chunk 0's tag on a VALID container and
            // desynchronizing every later chunk. The product cipher source is a plain stream read
            // and relies on the walk to slice the windows.
            val want = minOf(buffer.size.toLong(), chunkWireLength - consumed).toInt()
            val target = if (want == buffer.size) buffer else ByteArray(want)
            val read = cipherSource.read(target)
            if (read <= 0) {
                // The size equation catches a truncated container pre-auth; a short CIPHER stream
                // here (a post-header tamper the equation cannot see) is structural, so it is NOT
                // reported as an authentication failure — the caller maps it to a read failure.
                throw ShortChunkStreamException()
            }
            consumed += read.toLong()
            val plaintext = decryptor.update(target, 0, read)
            if (plaintext.isNotEmpty()) {
                plaintextSink.write(plaintext, 0, plaintext.size)
                produced += plaintext.size.toLong()
            }
        }
        // The tag verification is the ONLY place the platform AEAD failure surfaces (GCM verifies
        // at doFinal); until it succeeds this chunk's plaintext is NOT trusted (spec section 4.9).
        val tail =
            try {
                decryptor.doFinal()
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                throw BackupV2AuthenticationException(failure)
            }
        if (tail.isNotEmpty()) {
            plaintextSink.write(tail, 0, tail.size)
            produced += tail.size.toLong()
        }
    }
    return produced
}

/**
 * Rebuilds the 59-byte fixed header from the parsed fields. The chunk AAD is `header || salt ||
 * index` and the writer used the ON-WIRE header bytes; the parse result is a lossless projection of
 * exactly those bytes (offsets 0..58), so rebuilding is byte-identical — and this stays true
 * because the parser writes nothing else into the projection.
 */
private fun headerBytesOf(header: BackupContainerHeader): ByteArray {
    val bytes = ByteArray(BACKUP_FIXED_HEADER_LENGTH)
    bytes[0] = 0x55
    bytes[1] = 0x4C
    bytes[2] = 0x42
    bytes[3] = 0x4B
    writeBeU16(bytes, 4, header.containerFormatVersion)
    bytes[6] = header.kdfId.toByte()
    bytes[7] = header.aeadId.toByte()
    writeBeU32(bytes, 8, header.kdfIterations)
    bytes[12] = header.saltLength.toByte()
    bytes[13] = header.ivLength.toByte()
    bytes[14] = header.tagLength.toByte()
    writeBeU32(bytes, 15, header.dbSchemaVersion)
    writeBeU64(bytes, 19, header.plaintextLength)
    header.payloadSha256.copyInto(bytes, 27)
    return bytes
}

/** Writes a BE u16 at [offset] (the frozen header layout is big-endian, spec section 4.3). */
private fun writeBeU16(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    target[offset] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 1] = (value and 0xFF).toByte()
}

/** Writes a BE u32 at [offset] (the frozen header layout is big-endian, spec section 4.3). */
private fun writeBeU32(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    for (index in 0 until 4) {
        target[offset + index] = ((value ushr ((3 - index) * 8)) and 0xFF).toByte()
    }
}

/** Writes a BE u64 at [offset] (the frozen header layout is big-endian, spec section 4.3). */
private fun writeBeU64(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    for (index in 0 until 8) {
        target[offset + index] = ((value ushr ((7 - index) * 8)) and 0xFF).toByte()
    }
}
