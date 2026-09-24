package com.unifiedledger.desktop

import com.unifiedledger.data.runSnapshotIntoOn
import com.unifiedledger.data.verifySnapshotOn
import com.unifiedledger.ui.BackupSnapshotPort
import com.unifiedledger.ui.BackupSnapshotVerification
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/*
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 3.3/3.4): the
 * desktop snapshot surface. It delegates to the commonMain driver-level helpers, exactly like the
 * statistics-refresh precedent, so the driver stays private to the composition root and the shared
 * export use case never sees it.
 */

/**
 * The desktop [BackupSnapshotPort] over the active graph's JDBC driver.
 *
 * `VACUUM INTO` runs on the active connection ([runSnapshotIntoOn]); the verification opens a
 * SECOND, dedicated connection over the snapshot file ([verifySnapshotOn]) because the active
 * connection holds the active generation's main file. `JdbcSqliteDriver` creates on open, but the
 * snapshot file already exists (it was just produced), so this second open is read-only in effect.
 */
internal class DesktopBackupSnapshotPort(
    private val activeDriver: SqlDriver,
) : BackupSnapshotPort {
    override fun snapshot(target: String) {
        runSnapshotIntoOn(activeDriver, target)
    }

    override fun verify(snapshotPath: String): BackupSnapshotVerification {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$snapshotPath")
        return try {
            val verification = verifySnapshotOn(driver)
            BackupSnapshotVerification(verification.integrityOk, verification.schemaVersion)
        } finally {
            driver.close()
        }
    }
}