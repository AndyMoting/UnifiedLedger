package com.unifiedledger.ui

import com.unifiedledger.application.backup.BACKUP_DISK_HEADROOM_BYTES
import com.unifiedledger.application.backup.BACKUP_MAX_PLAINTEXT_BYTES
import com.unifiedledger.application.backup.BACKUP_MIN_PASSWORD_CODE_POINTS
import com.unifiedledger.application.backup.BACKUP_STREAM_CHUNK_BYTES
import com.unifiedledger.application.backup.BackupContainerSink
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.BackupPlaintextReader
import com.unifiedledger.application.backup.BackupPlaintextSource
import com.unifiedledger.application.backup.backupContainerOverheadBytes
import com.unifiedledger.application.backup.writeBackupContainer

/*
 * P7-06 06.B (D-177; spec `docs/specs/2026-09-24-p7-06-backup-export-design.md`): the shared,
 * platform-independent export use case. It owns the frozen 7-step order of spec section 3, the
 * disk precheck (section 3.2), the two write-side plaintext gates (section 5), the two-phase
 * bounded encrypted write (section 3.5), the success-only-after-authentication-tail rule
 * (section 3.6) and the private staging lifecycle (section 6).
 *
 * The use case is synchronous and returns a typed result; the composition root dispatches it on a
 * background thread (spec section 5: snapshot, KDF, streaming crypto and delivery must never run on
 * the UI thread). Every file access goes through the injected [LedgerFileSystem] port and every
 * database access through [BackupSnapshotPort], so the whole flow is exercisable in common tests
 * with fault-injecting fakes and each platform supplies the real implementations.
 */

/** The typed failure reasons of an export (spec section 3; names are implementation-batch). */
enum class BackupExportFailure {
    /** `acquireLease` returned `RuntimeNotReady`: no work was done, nothing was touched. */
    RUNTIME_NOT_READY,

    /** The password is empty or shorter than 8 Unicode code points (container spec section 4.10). */
    PASSWORD_TOO_SHORT,

    /** Available space is below the export-side formula (spec section 3.2). */
    INSUFFICIENT_SPACE,

    /** The active main file (pre-gate) or the actual snapshot (post-gate) exceeds 1 GiB. */
    PLAINTEXT_TOO_LARGE,

    /** `VACUUM INTO` failed. */
    SNAPSHOT_FAILED,

    /** The composition root wired no snapshot surface for the active graph (a wiring gap). */
    SNAPSHOT_SURFACE_UNAVAILABLE,

    /** The snapshot's `PRAGMA integrity_check` did not return `ok` (spec section 3.4). */
    SNAPSHOT_INTEGRITY_FAILED,

    /** The bounded authenticated container write failed. */
    CONTAINER_WRITE_FAILED,

    /** The user-target delivery stream failed before its clean close (spec section 3.5 phase 2). */
    TARGET_WRITE_FAILED,
}

/** The export outcome (spec section 3.6). Success is reported ONLY for [Succeeded]. */
sealed interface BackupExportResult {
    /** The authenticated tail was written and the delivery stream closed cleanly. */
    data class Succeeded(
        val containerBytes: Long,
    ) : BackupExportResult

    /** A typed failure; never reported as success, never leaves a success-claiming artifact. */
    data class Failed(
        val reason: BackupExportFailure,
    ) : BackupExportResult

    /** The user dismissed the target picker, or the cancellation signal fired. */
    data object Cancelled : BackupExportResult
}

/** A cooperative cancellation signal, checked between 64 KiB chunks (spec section 5). */
fun interface BackupCancellationSignal {
    fun isCancelled(): Boolean
}

/** Thrown internally when the cancellation signal fires; never escapes the use case. */
private class BackupCancelledException : RuntimeException("backup export cancelled")

/**
 * The controlled snapshot surface (spec sections 3.3/3.4). The composition root supplies the
 * platform implementation, which delegates to the commonMain driver-level helpers
 * (`runSnapshotIntoOn` / `verifySnapshotOn`) exactly like the statistics-refresh precedent.
 */
interface BackupSnapshotPort {
    /**
     * Runs `VACUUM INTO ?` on the controlled connection, writing the consistent snapshot to
     * [target]. The target must not exist (both engines refuse to overwrite); throws on failure.
     */
    fun snapshot(target: String)

    /** Closes the snapshot handle and validates the snapshot file (spec section 3.4). */
    fun verify(snapshotPath: String): BackupSnapshotVerification
}

/** The snapshot verification outcome (spec section 3.4). */
class BackupSnapshotVerification(
    /** `PRAGMA integrity_check` returned `ok`. */
    val integrityOk: Boolean,
    /** The snapshot's `PRAGMA user_version`, the non-authoritative header hint. */
    val schemaVersion: Long,
)

/**
 * The user-target save port (spec section 3.5 phase 2; shaped like the `ImportFilePickPort`
 * precedent: commonMain declares value types and closures only, each platform implements SAF
 * `CreateDocument` / `JFileChooser`). [openTarget] returns null when the user cancels.
 */
fun interface BackupTargetPort {
    fun openTarget(): BackupTargetWriter?
}

/** The suggested container file name (display only; the platform picker may override it). */
const val BACKUP_TARGET_SUGGESTED_NAME: String = "unifiedledger-backup.ulbk"

/**
 * A bounded streaming writer to the user target. If the destination is a local file the adapter
 * stages a temp file and atomically replaces on [commit]; where the platform cannot replace
 * atomically it streams in place and reports success only after a clean [commit] (spec section
 * 3.5). [close] abandons an unpublished target and is idempotent after [commit].
 */
interface BackupTargetWriter : AutoCloseable {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    fun flushAndSync()

    /** Publishes the delivered content; must be called exactly once. */
    fun commit()
}

/** One export request. */
class BackupExportRequest(
    val password: String,
    /**
     * The active generation's main file (spec section 3.2 pre-gate and the disk formula's
     * plaintext_size input). Resolved by the composition root from the runtime owner's active
     * generation; never a tracked literal path.
     */
    val activeMainFile: String,
)

/**
 * The shared export use case. Construct once per composition root and call [export] on a
 * background thread.
 */
class BackupExportUseCase(
    private val owner: LedgerRuntimeOwner<*>,
    private val fileSystem: LedgerFileSystem,
    private val layout: LedgerStorageLayout,
    /**
     * Resolves the snapshot surface for the CURRENT active graph. The port is a provider rather
     * than a fixed instance because the platform driver is owned by the graph and replaced on a
     * reopen; the composition root reads the live graph here (a pure field read).
     */
    private val snapshotProvider: () -> BackupSnapshotPort?,
    private val target: BackupTargetPort,
    private val crypto: BackupCryptoPrimitives,
    private val newToken: () -> String,
) {
    /**
     * Builds the export request for [generation]: the active generation's main file resolved from
     * the platform storage layout (spec section 3.2). Used by the composition root's shared UI.
     */
    fun requestFor(
        password: String,
        generation: Generation,
    ): BackupExportRequest = BackupExportRequest(password, layout.mainFile(layout.generationDirectory(generation)))
    /**
     * Runs the frozen 7-step export (spec section 3). Never blocks on the runtime (the lease
     * acquisition is non-blocking) and never reports success unless the authenticated tail was
     * written and the delivery stream closed cleanly.
     */
    fun export(
        request: BackupExportRequest,
        cancellation: BackupCancellationSignal = BackupCancellationSignal { false },
    ): BackupExportResult {
        // Password policy (container spec section 4.10) is checked before any work.
        if (countUnicodeCodePoints(request.password) < BACKUP_MIN_PASSWORD_CODE_POINTS) {
            return BackupExportResult.Failed(BackupExportFailure.PASSWORD_TOO_SHORT)
        }

        // Step 1: operation lease for the whole export, released in `finally` (spec section 3.1).
        val lease =
            when (val acquired = owner.acquireLease()) {
                is LeaseAcquireResult.Acquired -> acquired.lease
                LeaseAcquireResult.RuntimeNotReady -> return BackupExportResult.Failed(BackupExportFailure.RUNTIME_NOT_READY)
            }
        val token = newToken()
        val snapshotFile = layout.backupSnapshotFile(token)
        val containerFile = layout.backupContainerFile(token)
        try {
            return runExport(request, cancellation, snapshotFile, containerFile)
        } finally {
            // Step 7 (spec section 3.7): clean the private staging on every path. A cleanup
            // failure is diagnostic only and never flips the result (the next-start sweep is the
            // fallback; the spec forbids claiming a secure erase).
            runCatching { fileSystem.delete(snapshotFile) }
            runCatching { fileSystem.delete(containerFile) }
            lease.close()
        }
    }

    private fun runExport(
        request: BackupExportRequest,
        cancellation: BackupCancellationSignal,
        snapshotFile: String,
        containerFile: String,
    ): BackupExportResult {
        // Step 2: disk precheck before the snapshot and before ANY user-target file (spec 3.2).
        val plaintextSize = fileSystem.length(request.activeMainFile)
        val containerSize = plaintextSize + backupContainerOverheadBytes()
        val required = containerSize + plaintextSize + BACKUP_DISK_HEADROOM_BYTES
        val available = fileSystem.usableSpace(layout.hostDirectory)
        if (available != null && available < required) {
            return BackupExportResult.Failed(BackupExportFailure.INSUFFICIENT_SPACE)
        }

        // Step 2 (same precheck step): the PRE-snapshot plaintext gate (spec section 5).
        if (plaintextSize > BACKUP_MAX_PLAINTEXT_BYTES) {
            return BackupExportResult.Failed(BackupExportFailure.PLAINTEXT_TOO_LARGE)
        }

        // Step 3: the snapshot target must NOT exist (VACUUM INTO refuses to overwrite).
        val snapshotPort =
            snapshotProvider()
                ?: return BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_SURFACE_UNAVAILABLE)
        fileSystem.createDirectories(layout.backupStagingDirectory)
        if (fileSystem.exists(snapshotFile)) {
            fileSystem.delete(snapshotFile)
        }
        try {
            snapshotPort.snapshot(snapshotFile)
        } catch (failure: BackupCancelledException) {
            return BackupExportResult.Cancelled
        } catch (failure: Throwable) {
            return BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_FAILED)
        }

        // Step 4: close the handle and verify the SNAPSHOT FILE; read the schema-version hint.
        val verification =
            try {
                snapshotPort.verify(snapshotFile)
            } catch (failure: Throwable) {
                return BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_INTEGRITY_FAILED)
            }
        if (!verification.integrityOk) {
            return BackupExportResult.Failed(BackupExportFailure.SNAPSHOT_INTEGRITY_FAILED)
        }

        // Step 4 (same step): the POST-snapshot plaintext gate on the ACTUAL snapshot size, which
        // is not guaranteed to equal the source size (spec section 5).
        val snapshotSize = fileSystem.length(snapshotFile)
        if (snapshotSize > BACKUP_MAX_PLAINTEXT_BYTES) {
            return BackupExportResult.Failed(BackupExportFailure.PLAINTEXT_TOO_LARGE)
        }

        // Step 5 phase 1: the bounded authenticated container into the private staging file.
        val containerBytes =
            try {
                writeContainer(request.password, cancellation, snapshotFile, containerFile, snapshotSize, verification.schemaVersion)
            } catch (failure: BackupCancelledException) {
                return BackupExportResult.Cancelled
            } catch (failure: Throwable) {
                return BackupExportResult.Failed(BackupExportFailure.CONTAINER_WRITE_FAILED)
            }

        // Step 5 phase 2 + step 6: deliver to the user target; success only after its clean close.
        return deliver(cancellation, containerFile, containerBytes)
    }

    private fun writeContainer(
        password: String,
        cancellation: BackupCancellationSignal,
        snapshotFile: String,
        containerFile: String,
        snapshotSize: Long,
        schemaVersion: Long,
    ): Long {
        val writeStream = fileSystem.openWrite(containerFile)
        var committed = false
        try {
            val source =
                BackupPlaintextSource {
                    val readStream = fileSystem.openRead(snapshotFile)
                    object : BackupPlaintextReader {
                        override fun read(buffer: ByteArray): Int {
                            if (cancellation.isCancelled()) throw BackupCancelledException()
                            return readStream.read(buffer)
                        }

                        override fun close() {
                            readStream.close()
                        }
                    }
                }
            val sink = BackupContainerSink { bytes, offset, length -> writeStream.write(bytes, offset, length) }
            val result =
                writeBackupContainer(
                    plaintext = source,
                    sink = sink,
                    password = password,
                    schemaVersion = schemaVersion,
                    plaintextLength = snapshotSize,
                    crypto = crypto,
                )
            writeStream.flushAndSync()
            writeStream.commit()
            committed = true
            return result.containerLength
        } finally {
            if (!committed) {
                // A failure/cancel before commit must leave no success-claiming staging artifact.
                runCatching { fileSystem.delete(containerFile) }
            }
            runCatching { writeStream.close() }
        }
    }

    private fun deliver(
        cancellation: BackupCancellationSignal,
        containerFile: String,
        containerBytes: Long,
    ): BackupExportResult {
        val writer = target.openTarget() ?: return BackupExportResult.Cancelled
        try {
            val readStream = fileSystem.openRead(containerFile)
            try {
                val buffer = ByteArray(BACKUP_STREAM_CHUNK_BYTES)
                while (true) {
                    if (cancellation.isCancelled()) throw BackupCancelledException()
                    val read = readStream.read(buffer)
                    if (read <= 0) break
                    writer.write(buffer, 0, read)
                }
            } finally {
                readStream.close()
            }
            writer.flushAndSync()
            writer.commit()
            return BackupExportResult.Succeeded(containerBytes)
        } catch (failure: BackupCancelledException) {
            return BackupExportResult.Cancelled
        } catch (failure: Throwable) {
            return BackupExportResult.Failed(BackupExportFailure.TARGET_WRITE_FAILED)
        } finally {
            // Best effort: a failed/cancelled delivery must not be left claiming success. The spec
            // promises no external-provider auto-delete, so this only abandons our own writer.
            runCatching { writer.close() }
        }
    }
}

/** Counts Unicode code points the way NIST SP 800-63B section 5.1.1.2 counts characters. */
internal fun countUnicodeCodePoints(value: String): Int {
    var count = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        index += if (char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}