package com.unifiedledger.ui

import com.unifiedledger.application.backup.BACKUP_DISK_HEADROOM_BYTES
import com.unifiedledger.application.backup.BACKUP_FIXED_HEADER_LENGTH
import com.unifiedledger.application.backup.BACKUP_KEY_LENGTH_BITS
import com.unifiedledger.application.backup.BACKUP_MAX_CONTAINER_BYTES
import com.unifiedledger.application.backup.BACKUP_STREAM_CHUNK_BYTES
import com.unifiedledger.application.backup.BackupContainerHeader
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.BackupHeaderParseResult
import com.unifiedledger.application.backup.BackupPreflightRejection
import com.unifiedledger.application.backup.backupContainerAad
import com.unifiedledger.application.backup.backupContainerSelfConsistent
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
 * implements SAF `OpenDocument` / `JFileChooser`).
 *
 * P2-8 fix: the outcome is TYPED, so a picker that fails to launch is distinguishable from a user
 * cancel. Spec section 3.1 treats `null` as cancel only; a launch failure must not masquerade as a
 * cancel.
 */
fun interface BackupSourcePort {
    fun openSource(): BackupSourceOpenResult
}

/** The typed outcome of opening the user-chosen source (P2-8). */
sealed interface BackupSourceOpenResult {
    /** The user picked a source; the reader streams it in bounded chunks. */
    data class Opened(
        val reader: BackupSourceReader,
    ) : BackupSourceOpenResult

    /** The user dismissed the picker; no file, no diagnostics (spec section 3.1). */
    data object Cancelled : BackupSourceOpenResult

    /** The platform picker could not be launched (permission/unavailability); a typed failure. */
    data object LaunchFailed : BackupSourceOpenResult
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

    /**
     * Every DISTINCT `ledger_id` observed across the payload's authoritative owner set (the class-3
     * identity check, spec section 3.8). An empty list means NO identity could be observed, which the
     * caller MUST treat as a class-3 rejection — never as an implicit target match (P1-1).
     */
    fun readObservedLedgerIdentities(snapshotPath: String): List<String>

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
 *
 * [currentSchemaVersion] is the schema version this build supports. It is INJECTED for the same
 * reason as the whitelist (P2-6): `app-ui` does not depend on `ledger-data`, so the shared preflight
 * cannot read `LedgerDatabase.Schema.version`; hard-coding it would silently treat a stale-schema
 * snapshot as current after a schema bump. The composition root supplies the real value.
 */
class RestorePreflightRequest(
    val password: String,
    /** The fixed target ledger identity (container-format spec section 5.4; single ledger only). */
    val targetLedgerId: String,
    val supportedSourceVersions: Set<Long>,
    /** The schema version this build supports (supplied by the composition root; P2-6). */
    val currentSchemaVersion: Long,
    /**
     * The container-size bound used by the counted-source fallback. Defaults to the frozen
     * [BACKUP_MAX_CONTAINER_BYTES]; a production caller never overrides it. It is injectable only so
     * a small synthetic stream can drive the counted-fallback rejection in tests (P2-5).
     */
    val containerSizeBound: Long = BACKUP_MAX_CONTAINER_BYTES,
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
 *
 * DEFERRED WIRING (P2-7, registered not silently dropped): this use case and its ports are NOT yet
 * constructed by either composition root. The 06.C design spec leaves two inputs OPEN that a root
 * needs before it can construct them — the supported old-schema whitelist SET (spec section 6.3 /
 * section 10 item 1, "must be fixed by a separate strict structure-identification gate") and the
 * preview/confirmation UI field set (spec section 10 item 13) — and the confirmation path itself
 * belongs to 06.D. On wiring, the root supplies `supportedSourceVersions` (the whitelist) and
 * `currentSchemaVersion` (`currentSupportedSchemaVersion()` from ledger-data), and dispatches
 * [preflight] off the UI thread. Until then A03/A04 are exercised through this use case's tests, not
 * end to end in the product.
 *
 * REGISTERED (P3-14): the operation lease is held while the platform source port waits for the user's
 * picker choice (the Android adapter's latch wait is bounded at 10 minutes), so a user who leaves the
 * picker open blocks `reopen`/`closeActiveGraph` and makes `quiesce()` time out for that duration.
 * That is the accepted cost of the whole-duration lease (spec section 7.3); the wait is bounded and a
 * picker dismissal returns promptly.
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
        // P2-2 fix: EVERYTHING after the lease is acquired runs inside the outer `try`, so the
        // `finally` below always releases it — including `newToken()` or a `layout.restore*File`
        // throw. A leaked lease would block reopen/closeActiveGraph and make quiesce() time out for
        // the process lifetime (spec section 7.3 requires release in a `finally`).
        var containerFile: String? = null
        var snapshotFile: String? = null
        var migratedFile: String? = null
        var keepArtifacts = false
        try {
            val tokenId = newToken()
            containerFile = layout.restoreContainerFile(tokenId)
            snapshotFile = layout.restoreSnapshotFile(tokenId)
            migratedFile = layout.restoreMigratedFile(tokenId)
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
            // keeps the authenticated plaintext snapshot (and migration copy) for a later
            // confirmation, which 06.D re-verifies (spec section 7.5).
            if (!keepArtifacts) {
                containerFile?.let { runCatching { fileSystem.delete(it) } }
                snapshotFile?.let { runCatching { fileSystem.delete(it) } }
                migratedFile?.let { runCatching { fileSystem.delete(it) } }
            } else {
                // P3-12: on success the staging CONTAINER copy (up to 2 GiB) is no longer needed —
                // the token binds the plaintext artifact digests, and confirmation must never re-read
                // the external container (spec section 7.2). Deleting it here bounds the retained
                // staging to the plaintext artifacts.
                containerFile?.let { runCatching { fileSystem.delete(it) } }
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
            when (val staged = copySourceBounded(cancellation, containerFile, request.containerSizeBound)) {
                SourceCopy.Cancelled -> return RestorePreflightResult.Cancelled
                // P2-8: a launch failure is a typed read failure, NOT a cancel.
                SourceCopy.LaunchFailed -> return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
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
        // The self-consistency equation needs the exact container size (spec section 4.1/4.8); the
        // shared pure helper owns the comparison so it cannot drift (P3-13).
        if (!backupContainerSelfConsistent(header, stagedContainerSize)) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_CONTAINER_SIZE_MISMATCH)
        }

        // Step 3: the mandatory bounded disk precheck BEFORE any decryption (spec section 3.3/6.2).
        // FAIL-OPEN ON UNKNOWN SPACE (the 06.C spec section 6.2 recommendation): an unknown value
        // only skips the early precheck; the private-staging writes then fail typed and can never
        // produce a false success. A KNOWN shortfall is the mandated hard rejection.
        val required = stagedContainerSize + header.plaintextLength + header.plaintextLength + BACKUP_DISK_HEADROOM_BYTES
        // P2-3 fix: a throwing `usableSpace` (an Exception) is a typed precheck failure, not an
        // untyped escape; Errors deliberately propagate (the arm below).
        val available =
            try {
                fileSystem.usableSpace(layout.hostDirectory)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_INSUFFICIENT_SPACE)
            }
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
            } catch (failure: StagingWriteException) {
                // P2-1 fix: a staging I/O failure is NOT a password failure. It is its own typed code
                // so the user is never told "wrong password" for a disk/permission problem.
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            } catch (failure: ContainerReadException) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            } catch (failure: AuthenticationFailureException) {
                // P2-1 fix: ONLY the platform AEAD failure (and the internal short-container case,
                // handled as a read failure above) maps to the uniform code. Container spec section
                // 4.7 scopes it to CRYPTOGRAPHIC failure: wrong password, tag failure, tampered
                // ciphertext. A fatal `Error` never reaches this branch: each of the 20
                // `catch (failure: Throwable)` sites in this file is immediately preceded by a
                // `catch (failure: Error) { throw failure }`, so a fatal failure propagates instead of
                // being reported as a rejection (see `decryptToStaging`'s doFinal wrapper below).
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_CONTAINER_AUTHENTICATION_FAILED)
            }

        // Step 6: post-authentication payload_sha256 check (spec section 3.6). Only reachable after
        // the tag passed; it detects local storage corruption of the staging plaintext.
        // P2-3 fix: a throwing `openRead` is a typed read failure, not an untyped escape.
        val payloadMatches =
            try {
                decryptedSize == header.plaintextLength && payloadSha256Matches(snapshotFile, header)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            }
        if (!payloadMatches) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_PAYLOAD_INTEGRITY_FAILED)
        }

        // Step 7: post-authentication payload structure check: the AUTHORITATIVE user_version, with
        // the header/payload MISMATCH rejection (class 2; spec section 3.7, D-179).
        // P2-1 fix: an I/O failure reading the version is a typed read failure, not a version claim.
        val sourceVersion =
            try {
                isolatedDatabase.readAuthoritativeUserVersion(snapshotFile)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            }
        val currentVersion = request.currentSchemaVersion
        if (sourceVersion == 0L || sourceVersion > currentVersion || header.dbSchemaVersion != sourceVersion) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED)
        }
        val needsMigration = sourceVersion != currentVersion
        if (needsMigration && sourceVersion !in request.supportedSourceVersions) {
            // Known but not whitelisted: the frozen rule is a typed rejection, never a stamp/guess.
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SCHEMA_VERSION_UNSUPPORTED)
        }

        // Step 8: post-authentication identity/ledger check (class 3; spec section 3.8).
        // P1-1 fix: the observed set spans the WHOLE authoritative owner set, and an EMPTY set is a
        // rejection — the preflight never fabricates a target match for a payload that shows none.
        val identities =
            try {
                isolatedDatabase.readObservedLedgerIdentities(snapshotFile)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                // P2-1 fix: an I/O failure reading identities is a read failure, not an identity claim.
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            }
        if (identities.isEmpty()) {
            // No verifiable identity anywhere: the payload cannot be shown to belong to the target.
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED)
        }
        if (identities.size > 1 || identities.any { it != request.targetLedgerId }) {
            return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_LEDGER_IDENTITY_UNSUPPORTED)
        }
        // The single observed id equals the target (both checks above passed), so this is the
        // OBSERVED identity, not a fabricated default.
        val sourceLedgerId = identities.first()

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
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
            }
            // P2-3 fix: a throwing `migrateStrictly` is a typed migration failure.
            val migrated =
                try {
                    isolatedDatabase.migrateStrictly(migratedFile, sourceVersion, request.supportedSourceVersions)
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Throwable) {
                    return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_MIGRATION_FAILED)
                }
            when (migrated) {
                is RestoreMigrationOutcome.Migrated -> migratedVersion = migrated.toVersion
                RestoreMigrationOutcome.Failed -> return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_MIGRATION_FAILED)
            }
            validationPath = migratedFile
        }

        // Step 10: integrity/FK/domain validation on the migrated copy, WITHOUT the seed bootstrap.
        val validation =
            try {
                isolatedDatabase.validate(validationPath)
            } catch (failure: Error) {
                throw failure
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
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Throwable) {
                    // N5 fix: reading the migrated copy to hash it is a read failure, reported with the
                    // same P706_SOURCE_READ_FAILED code as the other read paths (not a domain verdict).
                    return RestorePreflightResult.Rejected(BackupPreflightRejection.P706_SOURCE_READ_FAILED)
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

        data object LaunchFailed : SourceCopy

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
        sizeBound: Long,
    ): SourceCopy {
        val opened =
            try {
                source.openSource()
            } catch (failure: Error) {
                throw failure
            } catch (failure: Throwable) {
                // A throwing port is a launch failure, not a silent cancel.
                return SourceCopy.LaunchFailed
            }
        val sourceReader =
            when (opened) {
                is BackupSourceOpenResult.Opened -> opened.reader
                BackupSourceOpenResult.Cancelled -> return SourceCopy.Cancelled
                BackupSourceOpenResult.LaunchFailed -> return SourceCopy.LaunchFailed
            }
        try {
            fileSystem.createDirectories(layout.backupStagingDirectory)
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            runCatching { sourceReader.close() }
            return SourceCopy.Failed
        }
        sourceReader.use {
            val reported = sourceReader.reportedSize
            if (reported != null && !backupContainerWithinSizeBound(reported, sizeBound)) {
                // Provider reports > the bound: reject before reading (spec section 3.1).
                return SourceCopy.TooLarge
            }
            val writeStream =
                try {
                    fileSystem.openWrite(containerFile)
                } catch (failure: Error) {
                    throw failure
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
                        } catch (failure: Error) {
                            throw failure
                        } catch (failure: Throwable) {
                            return SourceCopy.Failed
                        }
                    if (read <= 0) break
                    total += read.toLong()
                    if (!backupContainerWithinSizeBound(total, sizeBound)) {
                        // The counted fallback: stop reading immediately, do not fill the disk.
                        return SourceCopy.TooLarge
                    }
                    try {
                        writeStream.write(buffer, 0, read)
                    } catch (failure: Error) {
                        throw failure
                    } catch (failure: Throwable) {
                        return SourceCopy.Failed
                    }
                }
                writeStream.flushAndSync()
                writeStream.commit()
                committed = true
                return SourceCopy.Staged(total)
            } catch (failure: Error) {
                throw failure
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
            } catch (failure: Error) {
                throw failure
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
     * Throws [AuthenticationFailureException] on a tag failure (mapped to the uniform code by the
     * caller); [StagingWriteException] on a staging write failure; [ContainerReadException] on a
     * container read failure or a short container. Returns the plaintext size.
     *
     * P3-2 fix: the staging write stream is opened BEFORE the password is widened, so a throwing
     * `openWrite` cannot leave the widened char array uncleared.
     *
     * N4 fix: the `try` opens immediately after [openStagingWrite], so a throw from the salt/IV
     * allocation or [widenBackupPassword] still runs the `finally` (closing the stream and deleting
     * the staging file). The widened array is nullable so the `finally` clears it only when produced.
     */
    private fun decryptToStaging(
        password: String,
        cancellation: BackupCancellationSignal,
        containerFile: String,
        header: BackupContainerHeader,
        snapshotFile: String,
    ): Long {
        // Opens first: if this throws, no secret material exists yet to clear.
        val writeStream = openStagingWrite(snapshotFile)
        var widened: CharArray? = null
        var key: ByteArray? = null
        var committed = false
        try {
            val headerBytes = ByteArray(BACKUP_FIXED_HEADER_LENGTH)
            val salt = ByteArray(header.saltLength)
            val iv = ByteArray(header.ivLength)
            widened = widenBackupPassword(password)
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
                        throw ContainerReadException()
                    }
                    remaining -= read.toLong()
                    val plaintext = if (read == 0) ByteArray(0) else decryptor.update(chunk, 0, read)
                    if (plaintext.isNotEmpty()) {
                        writeStaging(writeStream, plaintext)
                        produced += plaintext.size.toLong()
                    }
                }
                // P2-1: the tag verification is the ONLY place the platform AEAD failure surfaces;
                // it is wrapped in the uniform-failure type so no other throwable is misreported as
                // a wrong password.
                val tail =
                    try {
                        decryptor.doFinal()
                    } catch (failure: Error) {
                        throw failure
                    } catch (failure: Throwable) {
                        throw AuthenticationFailureException()
                    }
                if (tail.isNotEmpty()) {
                    writeStaging(writeStream, tail)
                    produced += tail.size.toLong()
                }
                try {
                    writeStream.flushAndSync()
                    writeStream.commit()
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Throwable) {
                    throw StagingWriteException()
                }
                committed = true
                return produced
            }
        } catch (failure: ContainerReadException) {
            throw failure
        } catch (failure: AuthenticationFailureException) {
            throw failure
        } catch (failure: StagingWriteException) {
            throw failure
        } catch (failure: RestorePreflightCancelledException) {
            throw failure
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            // Any other failure while reading the container or unwrapping the key is a read failure,
            // NOT a password failure (P2-1).
            throw ContainerReadException()
        } finally {
            // Container-format spec section 4.10 / 06.C spec section 5.5: clear the widened chars
            // and the derived key after use. `widened` is nullable because a throw before it was
            // produced (salt/IV allocation) must still reach this cleanup (N4).
            widened?.fill('\u0000')
            key?.fill(0)
            if (!committed) {
                runCatching { fileSystem.delete(snapshotFile) }
            }
            runCatching { writeStream.close() }
        }
    }

    /** Opens the staging snapshot stream, mapping a failure to a typed read code (P2-1/P3-2). */
    private fun openStagingWrite(snapshotFile: String): LedgerWriteStream =
        try {
            fileSystem.openWrite(snapshotFile)
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            throw StagingWriteException()
        }

    private fun writeStaging(
        writeStream: LedgerWriteStream,
        bytes: ByteArray,
    ) {
        try {
            writeStream.write(bytes, 0, bytes.size)
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            throw StagingWriteException()
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
            if (read <= 0) throw ContainerReadException()
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

/** The container ended before its header described, or the container could not be read. */
private class ContainerReadException : RuntimeException("backup container could not be read")

/**
 * The platform AEAD tag verification failed (wrong password, tag failure or tampered ciphertext).
 * This is the ONLY failure the uniform `P706_CONTAINER_AUTHENTICATION_FAILED` code covers
 * (container-format spec section 4.7 scopes it to cryptographic failure).
 */
private class AuthenticationFailureException : RuntimeException("backup container authentication failed")

/** Writing the decrypted plaintext to the private staging failed (a disk/permission problem). */
private class StagingWriteException : RuntimeException("backup staging write failed")

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
