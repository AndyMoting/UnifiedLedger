package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/*
 * P7-06 06.B (D-177; spec `docs/specs/2026-09-24-p7-06-backup-export-design.md` sections 3.3/3.4):
 * the driver-level snapshot and snapshot-verification helpers, shaped like the statistics-refresh
 * precedent in [QueryStatisticsOptimize]. The composition roots reach them through controlled
 * entries on the platform handles (the driver stays private to each handle), and the shared export
 * use case never sees a driver.
 */

/**
 * Runs `VACUUM INTO ?` on [driver]'s connection, producing a self-consistent snapshot at
 * [target] (spec section 3.3). The target must NOT exist: both engines refuse to overwrite with
 * `output file already exists`, so the caller removes/renames a stale target first.
 *
 * `VACUUM INTO` is a row-less statement, so it rides `driver.execute` — the same surface as
 * `ANALYZE;` ([runFullAnalyzeOn]) and for the same reason: the Android `executeQuery` path rejects
 * statements that return no result rows. The parameter is bound through `bindString`, not
 * interpolated, so the path never enters the SQL text.
 *
 * UNVERIFIED (registered, spec section 9 item 4): the SQLDelight Android `driver.execute` binder
 * path for `VACUUM INTO` has not been device-verified; the gate evidence used the device `sqlite3`
 * CLI for Android and JDBC `PreparedStatement` for desktop. This helper is the implementation-batch
 * choice that must be verified on-device.
 */
fun runSnapshotIntoOn(
    driver: SqlDriver,
    target: String,
) {
    driver.execute(
        null,
        "VACUUM INTO ?",
        1,
        { bindString(0, target) },
    )
}

/** The snapshot verification outcome (spec section 3.4). */
class SnapshotVerification(
    val integrityOk: Boolean,
    val schemaVersion: Long,
)

/**
 * Validates a snapshot FILE through a second, dedicated connection (spec section 3.4): runs
 * `PRAGMA integrity_check` and reads `PRAGMA user_version` (the non-authoritative header hint).
 * The caller opens [driver] against the snapshot path read-only and closes it afterwards; the
 * active connection cannot stand in because it holds the active generation's main file.
 *
 * `PRAGMA integrity_check` returns result rows, so it rides `driver.executeQuery` (the
 * rawQuery-equivalent safe path; `PRAGMA optimize` uses the same surface for the same reason).
 */
fun verifySnapshotOn(driver: SqlDriver): SnapshotVerification {
    var integrityOk = false
    driver
        .executeQuery(
            null,
            "PRAGMA integrity_check",
            { cursor ->
                while (cursor.next().value) {
                    val text = cursor.getString(0)
                    if (text != null) integrityOk = text == "ok"
                }
                QueryResult.Unit
            },
            0,
            null,
        ).value
    return SnapshotVerification(integrityOk = integrityOk, schemaVersion = readSnapshotUserVersion(driver))
}

/** Reads `PRAGMA user_version` through [driver] (the header's non-authoritative schema hint). */
private fun readSnapshotUserVersion(driver: SqlDriver): Long {
    var version = 0L
    driver
        .executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                if (cursor.next().value) version = cursor.getLong(0) ?: 0L
                QueryResult.Unit
            },
            0,
            null,
        ).value
    return version
}