package com.unifiedledger.android

import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.verifyAndroidSnapshotFile
import com.unifiedledger.ui.BackupSnapshotPort
import com.unifiedledger.ui.BackupSnapshotVerification
import java.io.File

/*
 * P7-06 06.B (D-177; spec `2026-09-24-p7-06-backup-export-design.md` sections 3.3/3.4): the
 * Android snapshot surface. It delegates to the commonMain driver-level helpers through the
 * handle's controlled entries, exactly like the statistics-refresh precedent, so the driver stays
 * private to the handle and the shared export use case never sees it.
 */

/**
 * The Android [BackupSnapshotPort] over one graph's handle.
 *
 * `VACUUM INTO` runs on the handle's active connection ([AndroidLedgerDatabaseHandle.runSnapshotInto]);
 * the verification opens a SECOND, dedicated READ-ONLY connection over the snapshot file
 * ([verifyAndroidSnapshotFile]), because the active connection holds the active generation's main
 * file. The verification takes the snapshot's ABSOLUTE path (never a `Context.getDatabasePath`
 * name), so the subdirectory layout under `databases/` is not a problem.
 */
internal class AndroidBackupSnapshotPort(
    private val handle: AndroidLedgerDatabaseHandle,
) : BackupSnapshotPort {
    override fun snapshot(target: String) {
        require(File(target).isAbsolute) { "the Android snapshot target path must be absolute: $target" }
        handle.runSnapshotInto(target)
    }

    override fun verify(snapshotPath: String): BackupSnapshotVerification {
        // P3-N fix (06.B review): the sibling androidGenerationDriverName asserts the absolute
        // precondition; this path resolves the same class of file, so it applies the same guard.
        require(File(snapshotPath).isAbsolute) { "the Android snapshot verification path must be absolute: $snapshotPath" }
        val verification = verifyAndroidSnapshotFile(snapshotPath)
        return BackupSnapshotVerification(verification.integrityOk, verification.schemaVersion)
    }
}
