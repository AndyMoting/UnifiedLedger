package com.unifiedledger.android

import com.unifiedledger.data.StrictMigrationResult
import com.unifiedledger.data.foreignKeyCheckOn
import com.unifiedledger.data.migrateIsolatedSnapshotStrictlyOn
import com.unifiedledger.data.openAndroidReadWriteDriver
import com.unifiedledger.data.readAuthoritativeUserVersionOn
import com.unifiedledger.data.readIntegrityCheckRowsOn
import com.unifiedledger.data.readObservedLedgerIdsOn
import com.unifiedledger.data.snapshotIntegrityOk
import com.unifiedledger.data.validateDomainOn
import com.unifiedledger.ui.BackupSourceOpenResult
import com.unifiedledger.ui.BackupSourcePort
import com.unifiedledger.ui.BackupSourceReader
import com.unifiedledger.ui.RestoreIsolatedDatabasePort
import com.unifiedledger.ui.RestoreMigrationOutcome
import com.unifiedledger.ui.RestoreValidationFacts
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * P7-06 06.C (D-179; spec `docs/specs/2026-09-25-p7-06-restore-preflight-design.md` sections 8.1
 * and 8.2): the Android platform adapters for the restore preflight. The source port uses SAF
 * `OpenDocument` + `ContentResolver.openInputStream` (the `App.kt` precedent); the isolated-database
 * port opens the decrypted snapshot / migrated copy by ABSOLUTE PATH read-write WITHOUT
 * create-on-open and delegates to the commonMain strict helpers.
 */

/** How long [AndroidBackupSourcePort.openSource] waits for the user's SAF choice. */
internal const val ANDROID_BACKUP_SOURCE_WAIT_MILLIS: Long = 10L * 60L * 1000L

/**
 * The Android bounded streaming source port (spec section 8.1). SAF `OpenDocument` picks the source
 * and `ContentResolver.openInputStream` streams it in bounded chunks.
 *
 * THREADING (the `AndroidBackupTargetPort` precedent): SAF launchers MUST be launched on the main
 * thread, while the preflight runs on a background thread. The port posts the launch to the main
 * thread and the preflight thread BLOCKS on a latch until the SAF callback delivers the document;
 * the main thread is never blocked, so there is no deadlock.
 *
 * P2-8: a null SAF handle is [BackupSourceOpenResult.Cancelled]; a throwing poster, a latch timeout
 * or a failed stream open is [BackupSourceOpenResult.LaunchFailed]. The two were previously
 * indistinguishable (both `null`).
 */
internal class AndroidBackupSourcePort<Picked>(
    private val postToMainThread: ((() -> Unit) -> Unit),
    private val launchOpenDocument: (mimeTypes: Array<String>) -> Unit,
    private val openInputStream: (Picked) -> InputStream?,
    private val sizeOf: (Picked) -> Long? = { null },
) : BackupSourcePort {
    @Volatile
    private var pending: PendingChoice<Picked>? = null

    override fun openSource(): BackupSourceOpenResult {
        val latch = CountDownLatch(1)
        val choice = PendingChoice<Picked>(latch)
        pending = choice
        try {
            postToMainThread { launchOpenDocument(arrayOf(ANDROID_BACKUP_CONTAINER_MIME)) }
        } catch (failure: Exception) {
            pending = null
            return BackupSourceOpenResult.LaunchFailed
        }
        val delivered = latch.await(ANDROID_BACKUP_SOURCE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        pending = null
        if (!delivered) return BackupSourceOpenResult.LaunchFailed
        val picked = choice.picked ?: return BackupSourceOpenResult.Cancelled
        val stream =
            try {
                openInputStream(picked)
            } catch (failure: Exception) {
                null
            } ?: return BackupSourceOpenResult.LaunchFailed
        val size = sizeOf(picked)
        return BackupSourceOpenResult.Opened(
            object : BackupSourceReader {
                override fun read(buffer: ByteArray): Int = stream.read(buffer)

                override val reportedSize: Long? get() = size

                override fun close() {
                    stream.close()
                }
            },
        )
    }

    /** The SAF OpenDocument result callback: a null handle is the user's cancellation. */
    fun onOpenDocumentResult(picked: Picked?) {
        val choice = pending ?: return
        choice.picked = picked
        choice.latch.countDown()
    }

    private class PendingChoice<Picked>(
        val latch: CountDownLatch,
    ) {
        @Volatile
        var picked: Picked? = null
    }
}

/** The MIME filter for the backup container (spec section 8.1: `application/octet-stream`). */
internal const val ANDROID_BACKUP_CONTAINER_MIME: String = "application/octet-stream"

/**
 * The Android isolated-database port (spec section 8.2). It opens the snapshot / migrated copy by
 * ABSOLUTE PATH read-write through [openAndroidReadWriteDriver] (the minimal non-create-on-open
 * driver, because `FrameworkSQLiteDatabase` is not on this module's compile classpath and adding it
 * needs separate approval) and delegates every fact to the commonMain strict helpers.
 */
internal class AndroidRestoreIsolatedDatabasePort : RestoreIsolatedDatabasePort {
    override fun readAuthoritativeUserVersion(snapshotPath: String): Long = withDriver(snapshotPath) { driver -> readAuthoritativeUserVersionOn(driver) }

    override fun readObservedLedgerIdentities(snapshotPath: String): List<String> = withDriver(snapshotPath) { driver -> readObservedLedgerIdsOn(driver) }

    override fun migrateStrictly(
        snapshotPath: String,
        fromVersion: Long,
        supportedVersions: Set<Long>,
    ): RestoreMigrationOutcome =
        withDriver(snapshotPath) { driver ->
            when (val result = migrateIsolatedSnapshotStrictlyOn(driver, fromVersion, supportedVersions)) {
                is StrictMigrationResult.Migrated -> RestoreMigrationOutcome.Migrated(result.fromVersion, result.targetVersion)
                is StrictMigrationResult.Failed -> RestoreMigrationOutcome.Failed
            }
        }

    override fun validate(snapshotPath: String): RestoreValidationFacts =
        withDriver(snapshotPath) { driver ->
            val integrity = snapshotIntegrityOk(readIntegrityCheckRowsOn(driver))
            val foreignKeys = foreignKeyCheckOn(driver)
            val domain = validateDomainOn(driver)
            RestoreValidationFacts(
                integrityOk = integrity,
                foreignKeyOk = foreignKeys.ok,
                domainOk = domain.ok,
                ledgerIdentityCount = domain.ledgerIdentityCount,
                formalTableCount = domain.formalTableCount,
                postingImbalanceCount = domain.postingImbalanceCount,
            )
        }

    private fun <T> withDriver(
        path: String,
        block: (app.cash.sqldelight.db.SqlDriver) -> T,
    ): T {
        val driver = openAndroidReadWriteDriver(path)
        return try {
            block(driver)
        } finally {
            driver.close()
        }
    }
}
