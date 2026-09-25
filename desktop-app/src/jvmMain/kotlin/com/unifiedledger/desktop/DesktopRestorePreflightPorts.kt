package com.unifiedledger.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.ForeignKeyCheckResult
import com.unifiedledger.data.StrictMigrationResult
import com.unifiedledger.data.foreignKeyCheckOn
import com.unifiedledger.data.migrateIsolatedSnapshotStrictlyOn
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
 * thread (never the Compose UI thread). A user cancel is [BackupSourceOpenResult.Cancelled]; a
 * throwing chooser or a failed stream open is [BackupSourceOpenResult.LaunchFailed] (P2-8), never a
 * cancel.
 */
internal class DesktopBackupSourcePort(
    private val showOpenFileChooser: (description: String, extensions: Array<String>) -> File? = ::showSwingOpenBackupChooser,
    private val openInputStream: (File) -> InputStream? = { file -> FileInputStream(file) },
) : BackupSourcePort {
    override fun openSource(): BackupSourceOpenResult {
        val file =
            try {
                showOpenFileChooser("UnifiedLedger backup", arrayOf(BACKUP_CONTAINER_FILE_EXTENSION))
            } catch (failure: Exception) {
                return BackupSourceOpenResult.LaunchFailed
            } ?: return BackupSourceOpenResult.Cancelled
        val size = file.length()
        val stream =
            try {
                openInputStream(file)
            } catch (failure: Exception) {
                null
            } ?: return BackupSourceOpenResult.LaunchFailed
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
        // P3-8 (spec section 8.2): the create-on-open JDBC driver must not be handed a zero-length or
        // non-SQLite file — a create-on-open guard of the same shape as 06.1's
        // `isUsableSqliteMainFile` (existence, non-empty, SQLite magic). The isolated copy is a file
        // this preflight just wrote, but the guard still runs so a truncated/partial copy is refused
        // before the driver can initialize an empty schema.
        require(isUsableSqliteFile(path)) { "isolated restore target is not a usable SQLite file" }
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return try {
            block(driver)
        } finally {
            driver.close()
        }
    }
}

/**
 * The desktop pre-open guard (06.C spec section 8.2, the `isUsableSqliteMainFile` shape): the file
 * must exist, be non-empty, and start with the 16-byte SQLite file magic. Reads only the header
 * prefix, never the whole (potentially hundreds-of-MB) database.
 */
private fun isUsableSqliteFile(path: String): Boolean {
    val file = File(path)
    if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
    val prefix = ByteArray(SQLITE_HEADER.size)
    val read =
        FileInputStream(file).use { stream ->
            var filled = 0
            while (filled < prefix.size) {
                val count = stream.read(prefix, filled, prefix.size - filled)
                if (count <= 0) break
                filled += count
            }
            filled
        }
    if (read < SQLITE_HEADER.size) return false
    return SQLITE_HEADER.indices.all { prefix[it] == SQLITE_HEADER[it] }
}

/** The 16-byte SQLite file magic (`"SQLite format 3\u0000"`). */
private val SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()
