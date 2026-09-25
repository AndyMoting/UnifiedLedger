package com.unifiedledger.ui

import com.unifiedledger.application.backup.BACKUP_DISK_HEADROOM_BYTES
import com.unifiedledger.application.backup.BACKUP_FIXED_HEADER_LENGTH
import com.unifiedledger.application.backup.BACKUP_KEY_LENGTH_BITS
import com.unifiedledger.application.backup.BACKUP_STREAM_CHUNK_BYTES
import com.unifiedledger.application.backup.BackupContainerHeader
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.BackupHeaderParseResult
import com.unifiedledger.application.backup.BackupPreflightRejection
import com.unifiedledger.application.backup.backupContainerAad
import com.unifiedledger.application.backup.backupContainerExpectedSize
import com.unifiedledger.application.backup.backupContainerWithinSizeBound
import com.unifiedledger.application.backup.parseBackupContainerHeader
import com.unifiedledger.application.backup.widenBackupPassword

/*
 * P7-06 06.C (D-179; spec `docs/specs/2026-09-25-p7-06-restore-preflight-design.md`): the shared,
 * platform-independent restore preflight. It owns the frozen 11-step order of spec section 3, the
 * pre-authentication format/size checks (section 4), the mandatory bounded disk precheck as its own
 * numbered step (section 3.3/6.2), the streaming authenticated decryption to private staging
 * (section 3.5/5.3, never `CipherInputStream`), the post-authentication `payload_sha256` check
 * (section 3.6), the authoritative `PRAGMA user_version` with the header/payload mismatch rejection
 * (section 3.7), the identity/ledger check (section 3.8), the strict isolated migration (section
 * 6.3), the no-seed-bootstrap integrity/FK/domain validation (section 6.4) and the preview + opaque
 * token (section 7).
 *
 * CORE INVARIANT: no failure or cancellation path writes the current library or touches the active
 * pointer. The preflight writes ONLY under the private backup staging directory and never publishes
 * a generation. It HOLDS an operation lease for its whole duration (section 7.3) so a `reopen` -
 * whose `openStableStorageLedger` runs the destructive `sweepBackupStaging` - cannot delete the
 * preflight's own authenticated staging artifacts and leave a dangling token.
 *
 * Every file access goes through the injected [LedgerFileSystem] port and every database access
 * through [RestoreIsolatedDatabasePort], so the whole flow is exercisable in common tests with
 * fault-injecting fakes; each platform supplies the real implementations.
 */

/**
 * P7-06 06.C (spec section 8.1): the bounded streaming SOURCE port, shaped like the
 * `ImportFilePickPort` precedent (commonMain declares value types and closures only; each platform
 * implements SAF `OpenDocument` / `JFileChooser`). [openSource] returns null when the user cancels.
 */
fun interface BackupSourcePort {
    fun openSource(): BackupSourceReader?
}

/**
 * A closable, bounded, chunked source reader (spec section 8.1). [read] follows the `InputStream`
 * convention: it returns the number of bytes read, or a non-positive value at end of stream, and it
 * must never read a whole file into memory. [reportedSize] is the platform's size metadata, or null
 * when the provider does not report one (the `PickedImportFile.sizeBytes` precedent).
 */
interface BackupSourceReader : AutoCloseable {
    fun read(buffer: ByteArray): Int

    val reportedSize: Long?
}

/**
 * The typed outcome of the strict isolated migration (spec section 6.3). The platform adapter
 * computes it through the ledger-data helpers; the use case only maps it to a rejection code.
 */
sealed interface RestoreMigrationOutcome {
    data class Migrated(
        val fromVersion: Long,
        val toVersion: Long,
    ) : RestoreMigrationOutcome

    data object Failed : RestoreMigrationOutcome
}

/**
 * The isolated-copy validation facts (spec section 6.4). Every boolean is computed on the migrated
 * isolated copy by the platform adapter through the ledger-data helpers, WITHOUT the seed bootstrap.
 */
class RestoreValidationFacts(
    val integrityOk: Boolean,
    val foreignKeyOk: Boolean,
    val domainOk: Boolean,
    val ledgerIdentityCount: Int,
    val formalTableCount: Int,
    val postingImbalanceCount: Int,
) {
    val allOk: Boolean get() = integrityOk && foreignKeyOk && domainOk
}

/**
 * P7-06 06.C (spec section 8.2): the controlled isolated-database surface. The platform adapter
 * opens the decrypted snapshot / migrated copy by ABSOLUTE PATH read-write without create-on-open
 * and without automatic migration, runs the STRICT migration helper and the validation helpers, and
 * closes the connection. The shared preflight never sees a driver.
 */
interface RestoreIsolatedDatabasePort {
    /** The AUTHORITATIVE `PRAGMA user_version` of the payload (container-format spec section 4.3.2). */
    fun readAuthoritativeUserVersion(snapshotPath: String): Long

    /** The DISTINCT `ledger_id` values in the payload (the class-3 identity check, spec section 3.8). */
    fun readLedgerIdentities(snapshotPath: String): List<String>

    /** Strict single-transaction migration of [snapshotPath] in place (spec section 6.3). */
    fun migrateStrictly(
        snapshotPath: String,
        fromVersion: Long,
        supportedVersions: Set<Long>,
    ): RestoreMigrationOutcome

    /** Integrity / foreign-key / domain validation on the migrated copy (spec section 6.4). */
    fun validate(snapshotPath: String): RestoreValidationFacts
}

/**
 * One restore-preflight request.
 *
 * [supportedSourceVersions] is the supported-old-schema whitelist. The 06.C design spec section 6.3
 * and section 10 item 1 register the whitelist SET as OPEN (it must be fixed by a separate strict
 * structure-identification gate), so it is INJECTED rather than hard-coded: the composition root
 * supplies it and the preflight only enforces the frozen RULE (whitelist migrates, everything else
 * is a typed rejection, the lenient stamp path is never reused).
 */
class RestorePreflightRequest(
    val password: String,
    /** The fixed target ledger identity (container-format spec section 5.4; single ledger only). */
    val targetLedgerId: String,
    val supportedSourceVersions: Set<Long>,
)

/** The preview summary (spec section 7.4). Carries no password, key, path or plaintext detail. */
class RestorePreflightSummary(
    val containerFormatVersion: Int,
    val containerSize: Long,
    /** The AUTHORITATIVE payload `user_version` BEFORE migration (spec section 3.7). */
    val sourceSchemaVersion: Long,
    /** The version AFTER migration (equal to [sourceSchemaVersion] when no migration was needed). */
    val migratedSchemaVersion: Long,
    /** The payload's ledger identity (must equal the target; a class-3 check). */
    val sourceLedgerId: String,
    val targetLedgerId: String,
    /** The authenticated artifact digest: the header `payload_sha256` (display form). */
    val authenticatedArtifactSha256Hex: String,
    val integrityOk: Boolean,
    val foreignKeyOk: Boolean,
    val domainOk: Boolean,
    val ledgerIdentityCount: Int,
    val formalTableCount: Int,
    val postingImbalanceCount: Int,
)

/**
 * The opaque preflight token (spec section 7.1). The shared UI sees only [handle]; the bound facts
 * (the captured generation, the target ledger identity and the authenticated artifact digests) are
 * internal so a confirmation (06.D) can re-verify them without trusting the token itself. The token
 * never encodes a file path or a password.
 */
class RestorePreflightToken internal constructor(
    /** The opaque handle shown to the shared UI. */
    val handle: String,
    internal val generation: Generation,
    internal val targetLedgerId: String,
    internal val authenticatedArtifactSha256: ByteArray,
    internal val migratedArtifactSha256: ByteArray?,
)

/** The preflight outcome (spec section 7). Success is reported ONLY for [PreviewReady]. */
sealed interface RestorePreflightResult {
    /**
     * The artifact was authenticated and validated; the preview summary and the opaque token are
     * ready. The authenticated staging artifacts remain in the private staging directory for a later
     * confirmation (06.D re-verifies them; the preflight itself does not clean them on success).
     */
    data class PreviewReady(
        val summary: RestorePreflightSummary,
        val token: RestorePreflightToken,
    ) : RestorePreflightResult

    /** A typed rejection; the current library and the active pointer are untouched. */
    data class Rejected(
        val code: BackupPreflightRejection,
    ) : RestorePreflightResult

    /**
     * The runtime owner was not Ready (no active generation), so the operation lease could not be
     * acquired and NO work was done. This is a runtime-state failure, not a container rejection, so
     * it is its own variant rather than a misleading rejection code.
     */
    data object RuntimeNotReady : RestorePreflightResult

    /** The user dismissed the source picker, or the cancellation signal fired. */
    data object Cancelled : RestorePreflightResult
}

/** Thrown internally when the cancellation signal fires; never escapes the use case. */
private class RestorePreflightCancelledException : RuntimeException("restore preflight cancelled")

/**
 * The shared restore-preflight use case. Construct once per composition root and call [preflight] on
 * a background thread (container-format spec section 4.8: KDF, streaming decryption, migration and
 * validation must never run on the UI thread).
 */
class RestorePreflightUseCase(
    private val owner: LedgerRuntimeOwner<*>,
    private val fileSystem: LedgerFileSystem,
    private val layout: LedgerStorageLayout,
    private val source: BackupSourcePort,
    private val isolatedDatabase: RestoreIsolatedDatabasePort,
    private val crypto: BackupCryptoPrimitives,
    private val newToken: () -> String,
) {
    /**
     * Runs the frozen 11-step preflight (spec section 3). Never blocks on the runtime (lease
     * acquisition is non-blocking) and never reports success unless the artifact is authenticated
     * AND validated.
     */
    fun preflight(
        request: RestorePreflightRequest,
        cancellation: BackupCancellationSignal = BackupCancellationSignal { false },
    ): RestorePreflightResult {
        // Step 1 (part): the operation lease covers the WHOLE preflight (spec section 7.3) and is
        // released in `finally`. Capturing the generation here is what the token binds.
        val lease =
            when (val acquired = owner.acquireLease()) {
                is LeaseAcquireResult.Acquired -> acquired.lease
                LeaseAcquireResult.RuntimeNotReady -> return RestorePreflightResult.RuntimeNotReady
            }
        val tokenId = newToken()
        val containerFile = layout.restoreContainerFile(tokenId)
        val snapshotFile = layout.restoreSnapshotFile(tokenId)
        val migratedFile = layout.restoreMigratedFile(tokenId)
        var keepArtifacts = false
        try {
            val result =
                runPreflight(
                    request,
                    cancellation,
                    lease.generation,
                    tokenId,
                    containerFile,
                    snapshotFile,
                    migratedFile,
                )
            keepArtifacts = result is RestorePreflightResult.PreviewReady
            return result
        } finally {
            // Failure/cancel leaves no staging artifact behind (spec section 3.5/6.5); a success
            // keeps the authenticated artifacts for a later confirmation (06.D re-verifies them).
            if (!keepArtifacts) {
                runCatching { fileSystem.delete(containerFile) }
                runCatching { fileSystem.delete(snapshotFile) }
                runCatching { fileSystem.delete(migratedFile) }
            }
            lease.close()
        }
    }

    private fun runPreflight(
        request: RestorePreflightRequest,
        cancellation: BackupCancellationSignal,
        generation: Generation,
        tokenId: String,
        containerFile: String,
        snapshotFile: String,
        migratedFile: String,
    ): RestorePreflightResult {
        // Step 1: bounded copy of the user-chosen source into the private staging container, with
        // the 2 GiB bound enforced on EVERY path (spec section 3.1).
        val stagedContainerSize =
            when (val staged = copySourceBounded(cancellation, containerFile)) {
                SourceCopy.Cancelled -> return RestorePreflightResult.Cancelled
                SourceCopy.TooLarge -> return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_CONTAINER_TOO_LARGE)
                is SourceCopy.Failed -> return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
                is SourceCopy.Staged -> staged.size
            }

        // Step 2: read the 59-byte fixed header and run every pre-authentication public check.
        val header =
            when (val parsed = readHeader(containerFile)) {
                is HeaderRead.Rejected -> return RestorePreflightResult.Rejected(parsed.code)
                is HeaderRead.Parsed -> parsed.header
            }
        // The self-consistency equation needs the exact container size (spec section 4.1/4.8).
        if (backupContainerExpectedSize(header) != stagedContainerSize) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_CONTAINER_SIZE_MISMATCH)
        }

        // Step 3: the mandatory bounded disk precheck BEFORE any decryption (spec section 3.3/6.2).
        // FAIL-OPEN ON UNKNOWN SPACE (the 06.C spec section 6.2 recommendation): an unknown value
        // only skips the early precheck; the private-staging writes then fail typed and can never
        // produce a false success. A KNOWN shortfall is the mandated hard rejection.
        val required = stagedContainerSize + header.plaintextLength + header.plaintextLength + BACKUP_DISK_HEADROOM_BYTES
        val available = fileSystem.usableSpace(layout.hostDirectory)
        if (available != null && available < required) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_INSUFFICIENT_SPACE)
        }

        // Step 4-5: password -> KDF (header iterations/salt) -> streaming authenticated decryption
        // to the private plaintext snapshot. The tag is verified by `doFinal`; until then the
        // plaintext is never treated as trusted input (container-format spec section 4.9).
        val decryptedSize =
            try {
                decryptToStaging(request.password, cancellation, containerFile, header, snapshotFile)
            } catch (failure: RestorePreflightCancelledException) {
                return RestorePreflightResult.Cancelled
            } catch (failure: Throwable) {
                // Wrong password, tag failure and tampered ciphertext are ONE code (spec section 5.3).
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_CONTAINER_AUTHENTICATION_FAILED)
            }

        // Step 6: post-authentication payload_sha256 check (spec section 3.6). Only reachable after
        // the tag passed; it detects local storage corruption of the staging plaintext.
        if (decryptedSize != header.plaintextLength || !payloadSha256Matches(snapshotFile, header)) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_PAYLOAD_INTEGRITY_FAILED)
        }

        // Step 7: post-authentication payload structure check: the AUTHORITATIVE user_version, with
        // the header/payload MISMATCH rejection (class 2; spec section 3.7, D-179).
        val sourceVersion =
            try {
                isolatedDatabase.readAuthoritativeUserVersion(snapshotFile)
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED)
            }
        val currentVersion = CURRENT_SCHEMA_VERSION
        if (sourceVersion == 0L || sourceVersion > currentVersion || header.dbSchemaVersion != sourceVersion) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED)
        }
        val needsMigration = sourceVersion != currentVersion
        if (needsMigration && sourceVersion !in request.supportedSourceVersions) {
            // Known but not whitelisted: the frozen rule is a typed rejection, never a stamp/guess.
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED)
        }

        // Step 8: post-authentication identity/ledger check (class 3; spec section 3.8).
        val identities =
            try {
                isolatedDatabase.readLedgerIdentities(snapshotFile)
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED)
            }
        if (identities.size > 1 || identities.any { it != request.targetLedgerId }) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED)
        }
        val sourceLedgerId = identities.firstOrNull() ?: request.targetLedgerId

        // Step 9: strict isolated migration on a COPY; the decrypted snapshot is left untouched.
        val migratedVersion: Long
        val validationPath: String
        if (!needsMigration) {
            // Already at the current schema: no migration copy is needed; validate the snapshot.
            migratedVersion = sourceVersion
            validationPath = snapshotFile
        } else {
            try {
                fileSystem.copy(snapshotFile, migratedFile)
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            }
            when (val migrated = isolatedDatabase.migrateStrictly(migratedFile, sourceVersion, request.supportedSourceVersions)) {
                is RestoreMigrationOutcome.Migrated -> migratedVersion = migrated.toVersion
                RestoreMigrationOutcome.Failed -> return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_MIGRATION_FAILED)
            }
            validationPath = migratedFile
        }

        // Step 10: integrity/FK/domain validation on the migrated copy, WITHOUT the seed bootstrap.
        val validation =
            try {
                isolatedDatabase.validate(validationPath)
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_DOMAIN_VALIDATION_FAILED)
            }
        if (!validation.allOk) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_DOMAIN_VALIDATION_FAILED)
        }

        // Step 11: preview summary + opaque token (spec section 7). Still zero current-library writes.
        val migratedDigest =
            if (needsMigration) {
                try {
                    sha256OfFile(migratedFile)
                } catch (failure: Throwable) {
                    return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_DOMAIN_VALIDATION_FAILED)
                }
            } else {
                null
            }
        val summary =
            RestorePreflightSummary(
                containerFormatVersion = header.containerFormatVersion,
                containerSize = stagedContainerSize,
                sourceSchemaVersion = sourceVersion,
                migratedSchemaVersion = migratedVersion,
                sourceLedgerId = sourceLedgerId,
                targetLedgerId = request.targetLedgerId,
                authenticatedArtifactSha256Hex = header.payloadSha256.toHex(),
                integrityOk = validation.integrityOk,
                foreignKeyOk = validation.foreignKeyOk,
                domainOk = validation.domainOk,
                ledgerIdentityCount = validation.ledgerIdentityCount,
                formalTableCount = validation.formalTableCount,
                postingImbalanceCount = validation.postingImbalanceCount,
            )
        val token =
            RestorePreflightToken(
                handle = tokenId,
                generation = generation,
                targetLedgerId = request.targetLedgerId,
                authenticatedArtifactSha256 = header.payloadSha256.copyOf(),
                migratedArtifactSha256 = migratedDigest,
            )
        return RestorePreflightResult.PreviewReady(summary, token)
    }

    private sealed interface SourceCopy {
        data class Staged(
            val size: Long,
        ) : SourceCopy

        data object Cancelled : SourceCopy

        data object TooLarge : SourceCopy

        data object Failed : SourceCopy
    }

    /**
     * Step 1 (spec section 3.1): copies the source into [containerFile] in fixed 64 KiB chunks while
     * counting, enforcing the 2 GiB bound on every path. A provider-reported size above the bound is
     * rejected BEFORE any byte is read (the exact "do not read" of container-format spec section
     * 4.8); otherwise the counted stream is the registered fallback, so a provider that under-reports
     * its size still cannot bypass the bound. Returns the exact staged size.
     */
    private fun copySourceBounded(
        cancellation: BackupCancellationSignal,
        containerFile: String,
    ): SourceCopy {
        val reader =
            try {
                source.openSource()
            } catch (failure: Throwable) {
                return SourceCopy.Failed
            } ?: return SourceCopy.Cancelled
        try {
            fileSystem.createDirectories(layout.backupStagingDirectory)
        } catch (failure: Throwable) {
            runCatching { reader.close() }
            return SourceCopy.Failed
        }
        reader.use { sourceReader ->
            val reported = sourceReader.reportedSize
            if (reported != null && !backupContainerWithinSizeBound(reported)) {
                // Provider reports > 2 GiB: reject before reading (spec section 3.1).
                return SourceCopy.TooLarge
            }
            val writeStream =
                try {
                    fileSystem.openWrite(containerFile)
                } catch (failure: Throwable) {
                    return SourceCopy.Failed
                }
            var committed = false
            try {
                val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
                var total = 0L
                while (true) {
                    if (cancellation.isCancelled()) return SourceCopy.Cancelled
                    val read =
                        try {
                            sourceReader.read(buffer)
                        } catch (failure: Throwable) {
                            return SourceCopy.Failed
                        }
                    if (read <= 0) break
                    total += read.toLong()
                    if (!backupContainerWithinSizeBound(total)) {
                        // The counted fallback: stop reading immediately, do not fill the disk.
                        return SourceCopy.TooLarge
                    }
                    try {
                        writeStream.write(buffer, 0, read)
                    } catch (failure: Throwable) {
                        return SourceCopy.Failed
                    }
                }
                writeStream.flushAndSync()
                writeStream.commit()
                committed = true
                return SourceCopy.Staged(total)
            } catch (failure: Throwable) {
                return SourceCopy.Failed
            } finally {
                if (!committed) {
                    runCatching { fileSystem.delete(containerFile) }
                }
                runCatching { writeStream.close() }
            }
        }
    }

    private sealed interface HeaderRead {
        data class Parsed(
            val header: BackupContainerHeader,
        ) : HeaderRead

        data class Rejected(
            val code: BackupPreflightRejection,
        ) : HeaderRead
    }

    /** Step 2: reads the fixed header from the staged container and parses it (spec section 3.2). */
    private fun readHeader(containerFile: String): HeaderRead {
        val bytes = ByteArray(BACKUP_FIXED_HEADER_LENGTH)
        val filled =
            try {
                var count = 0
                fileSystem.openRead(containerFile).use { stream ->
                    while (count < bytes.size) {
                        val read = readInto(stream, bytes, count)
                        if (read <= 0) break
                        count += read
                    }
                }
                count
            } catch (failure: Throwable) {
                return HeaderRead.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            }
        return when (val parsed = parseBackupContainerHeader(bytes.copyOf(filled))) {
            is BackupHeaderParseResult.Parsed -> HeaderRead.Parsed(parsed.header)
            is BackupHeaderParseResult.Rejected -> HeaderRead.Rejected(parsed.code)
        }
    }

    /**
     * Steps 4-5 (spec section 3.5/5.3): derives the key from the header's (validated) salt and
     * iterations, streams the ciphertext + trailing tag through the decryptor in 64 KiB chunks, and
     * writes the plaintext to [snapshotFile]. The trailing tag rides `update` and the no-argument
     * `doFinal` verifies it (the declared port shape). `CipherInputStream` is never used.
     *
     * Throws on a tag failure (mapped to the uniform code by the caller); returns the plaintext size.
     */
    private fun decryptToStaging(
        password: String,
        cancellation: BackupCancellationSignal,
        containerFile: String,
        header: BackupContainerHeader,
        snapshotFile: String,
    ): Long {
        val headerBytes = ByteArray(BACKUP_FIXED_HEADER_LENGTH)
        val salt = ByteArray(header.saltLength)
        val iv = ByteArray(header.ivLength)
        val widened = widenBackupPassword(password)
        var key: ByteArray? = null
        val writeStream = fileSystem.openWrite(snapshotFile)
        var committed = false
        try {
            fileSystem.openRead(containerFile).use { stream ->
                readFully(stream, headerBytes)
                readFully(stream, salt)
                readFully(stream, iv)
                val derived = crypto.deriveKey(widened, salt, header.kdfIterations.toInt(), BACKUP_KEY_LENGTH_BITS)
                key = derived
                val decryptor = crypto.gcmDecryptor(derived, iv, backupContainerAad(headerBytes, salt))
                val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
                var remaining = header.plaintextLength + header.tagLength.toLong()
                var produced = 0L
                while (remaining > 0L) {
                    if (cancellation.isCancelled()) throw RestorePreflightCancelledException()
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val chunk = buffer.copyOf(want)
                    val read = stream.read(chunk)
                    if (read <= 0) {
                        // A short read means the container is smaller than its header claims.
                        throw RestoreContainerShortException()
                    }
                    remaining -= read.toLong()
                    val plaintext = if (read == 0) ByteArray(0) else decryptor.update(chunk, 0, read)
                    if (plaintext.isNotEmpty()) {
                        writeStream.write(plaintext, 0, plaintext.size)
                        produced += plaintext.size.toLong()
                    }
                }
                val tail = decryptor.doFinal()
                if (tail.isNotEmpty()) {
                    writeStream.write(tail, 0, tail.size)
                    produced += tail.size.toLong()
                }
                writeStream.flushAndSync()
                writeStream.commit()
                committed = true
                return produced
            }
        } finally {
            // Container-format spec section 4.10 / 06.C spec section 5.5: clear the widened chars
            // and the derived key after use.
            widened.fill('\u0000')
            key?.fill(0)
            if (!committed) {
                runCatching { fileSystem.delete(snapshotFile) }
            }
            runCatching { writeStream.close() }
        }
    }

    /** Step 6 (spec section 3.6): streaming SHA-256 of the staging plaintext vs the header digest. */
    private fun payloadSha256Matches(
        snapshotFile: String,
        header: BackupContainerHeader,
    ): Boolean {
        val digest = crypto.sha256Digest()
        val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
        fileSystem.openRead(snapshotFile).use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().contentEquals(header.payloadSha256)
    }

    private fun sha256OfFile(path: String): ByteArray {
        val digest = crypto.sha256Digest()
        val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
        fileSystem.openRead(path).use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun readFully(
        stream: LedgerReadStream,
        target: ByteArray,
    ) {
        var filled = 0
        while (filled < target.size) {
            val read = readInto(stream, target, filled)
            if (read <= 0) throw RestoreContainerShortException()
            filled += read
        }
    }

    /**
     * The [LedgerReadStream] contract reads into the WHOLE buffer at offset 0, so a partial read into
     * [target] at [offset] needs a scratch buffer of the remaining size. Returns the count read (the
     * `InputStream` convention).
     */
    private fun readInto(
        stream: LedgerReadStream,
        target: ByteArray,
        offset: Int,
    ): Int {
        val scratch = ByteArray(target.size - offset)
        val read = stream.read(scratch)
        if (read > 0) scratch.copyInto(target, destinationOffset = offset, startIndex = 0, endIndex = read)
        return read
    }
}

/** The container ended before its header described (mapped to the uniform code by the caller). */
private class RestoreContainerShortException : RuntimeException("backup container ended early")

/**
 * The schema version this build supports (v31).
 *
 * REGISTERED DUPLICATION (06.C spec section 10 item 1): the authoritative value is
 * `LedgerDatabase.Schema.version` in `ledger-data`, but `app-ui` depends only on `ledger-application`
 * (not `ledger-data`), so the shared preflight cannot read it directly. The composition root passes
 * the whitelist through [RestorePreflightRequest.supportedSourceVersions] and this constant is the
 * class-2 upper bound; a schema bump must update both. Keeping the class-2 decision in the shared
 * layer (rather than the platform port) is what makes the mismatch rejection testable without a
 * device.
 */
private const val CURRENT_SCHEMA_VERSION: Long = 31L

/** Lowercase hex for the display form of a digest (no path, no secret). */
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
