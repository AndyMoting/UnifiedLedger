package com.unifiedledger.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.ForeignKeyCheckResult
import com.unifiedledger.data.StrictMigrationResult
import com.unifiedledger.data.foreignKeyCheckOn
import com.unifiedledger.data.migrateIsolatedSnapshotStrictlyOn
import com.unifiedledger.data.readAuthoritativeUserVersionOn
import com.unifiedledger.data.readIntegrityCheckRowsOn
import com.unifiedledger.data.readLedgerIdsOn
import com.unifiedledger.data.snapshotIntegrityOk
import com.unifiedledger.data.validateDomainOn
import com.unifiedledger.ui.BackupSourcePort
import com.unifiedledger.ui.BackupSourceReader
import com.unifiedledger.ui.RestoreIsolatedDatabasePort
import com.unifiedledger.ui.RestoreMigrationOutcome
import com.unifiedledger.ui.RestoreValidationFacts
import java.awt.EventQueue
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/*
 * P7-06 06.C (D-179; spec `docs/specs/2026-09-25-p7-06-restore-preflight-design.md` sections 8.1
 * and 8.2): the desktop platform adapters for the restore preflight. The source port reuses the
 * frozen `JFileChooser` open precedent; the isolated-database port opens the decrypted snapshot /
 * migrated copy by absolute path through `JdbcSqliteDriver` and delegates to the commonMain helpers,
 * NEVER through the desktop lenient `migrateToCurrentSchema` (container-format spec section 5.4).
 */

/**
 * The desktop bounded streaming source port (spec section 8.1): a Swing `JFileChooser` open plus a
 * bounded `FileInputStream`. Both are injected lambdas so the port is headless-testable; the dialog
 * blocks until the user closes it, so the composition root dispatches [openSource] on a background
 * thread (never the Compose UI thread). Returns null on cancel.
 */
internal class DesktopBackupSourcePort(
    private val showOpenFileChooser: (description: String, extensions: Array<String>) -> File? = ::showSwingOpenBackupChooser,
    private val openInputStream: (File) -> InputStream? = { file -> FileInputStream(file) },
) : BackupSourcePort {
    override fun openSource(): BackupSourceReader? {
        val file = showOpenFileChooser("UnifiedLedger backup", arrayOf(BACKUP_CONTAINER_FILE_EXTENSION)) ?: return null
        val size = file.length()
        return object : BackupSourceReader {
            private val stream = openInputStream(file) ?: throw IllegalStateException("no stream for the picked backup")

            override fun read(buffer: ByteArray): Int = stream.read(buffer)

            override val reportedSize: Long? get() = size

            override fun close() {
                stream.close()
            }
        }
    }
}

/** The backup container file-name extension used as the chooser filter. */
private const val BACKUP_CONTAINER_FILE_EXTENSION: String = "ulbk"

/** The real Swing open dialog for a backup container (the `ImportFilePick` precedent). */
internal fun showSwingOpenBackupChooser(
    description: String,
    extensions: Array<String>,
): File? {
    val showDialog = {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        chooser.isMultiSelectionEnabled = false
        if (extensions.isNotEmpty()) {
            chooser.fileFilter = FileNameExtensionFilter(description, *extensions)
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
    return if (EventQueue.isDispatchThread()) {
        showDialog()
    } else {
        val chosen = arrayOfNulls<File>(1)
        EventQueue.invokeAndWait { chosen[0] = showDialog() }
        chosen[0]
    }
}

/**
 * The desktop isolated-database port (spec section 8.2). It opens the snapshot / migrated copy by
 * ABSOLUTE PATH with `JdbcSqliteDriver` and delegates every fact to the commonMain helpers. The
 * migration is the STRICT helper ([migrateIsolatedSnapshotStrictlyOn]); the desktop lenient
 * `migrateToCurrentSchema` is deliberately NOT reachable from here (container-format spec section
 * 5.4 forbids it for foreign backups).
 *
 * The isolated copy is a file this preflight just wrote, so create-on-open is harmless; the strict
 * helper performs no create and no stamp beyond the single migration transaction.
 */
internal class DesktopRestoreIsolatedDatabasePort : RestoreIsolatedDatabasePort {
    override fun readAuthoritativeUserVersion(snapshotPath: String): Long = withDriver(snapshotPath) { driver -> readAuthoritativeUserVersionOn(driver) }

    override fun readLedgerIdentities(snapshotPath: String): List<String> = withDriver(snapshotPath) { driver -> readLedgerIdsOn(driver) }

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
            val foreignKeys: ForeignKeyCheckResult = foreignKeyCheckOn(driver)
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
        block: (SqlDriver) -> T,
    ): T {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return try {
            block(driver)
        } finally {
            driver.close()
        }
    }
}
