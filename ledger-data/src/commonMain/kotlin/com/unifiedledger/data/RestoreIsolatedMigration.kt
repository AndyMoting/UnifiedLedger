package com.unifiedledger.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.data.db.LedgerDatabase

/*
 * P7-06 06.C (D-179; spec `docs/specs/2026-09-25-p7-06-restore-preflight-design.md` sections 6.3
 * and 6.4): the driver-level helpers for the STRICT isolated migration of a decrypted snapshot and
 * the post-migration integrity / foreign-key / domain validation. They are shaped like
 * [BackupSnapshotDriver] (the 06.B precedent) and reached through controlled entries on the
 * platform handles; the shared preflight never sees a driver.
 *
 * This file deliberately does NOT reuse the desktop lenient stamp branches (`Main.kt`
 * `from == 0` stamp/guess paths): container-format spec section 5.4 forbids them for foreign
 * backups. Only a version inside the caller-supplied supported set is migrated; everything else is
 * refused before this file is reached.
 *
 * NO SEED BOOTSTRAP: none of these helpers calls `store.bootstrap(...)` or `buildLedgerGraph(...)`,
 * so a snapshot with a missing table or a missing fact is rejected, never masked by seed data.
 */

/** Reads the AUTHORITATIVE `PRAGMA user_version` of a payload (container-format spec section 4.3.2). */
fun readAuthoritativeUserVersionOn(driver: SqlDriver): Long {
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

/** The typed reason a strict isolated migration failed. */
enum class StrictMigrationFailure {
    /** The payload version is not in the caller's supported set (should be refused earlier). */
    UNSUPPORTED_SOURCE_VERSION,

    /** The payload version is at or above the current schema (nothing to migrate). */
    NOT_AN_OLDER_VERSION,

    /** `LedgerDatabase.Schema.migrate` threw; the transaction rolled back. */
    MIGRATE_FAILED,

    /** Stamping the migrated version threw; the transaction rolled back. */
    STAMP_FAILED,
}

/** The strict migration outcome. */
sealed interface StrictMigrationResult {
    /** The snapshot is now at [targetVersion]; the caller still validates it. */
    data class Migrated(
        val fromVersion: Long,
        val targetVersion: Long,
    ) : StrictMigrationResult

    data class Failed(
        val reason: StrictMigrationFailure,
    ) : StrictMigrationResult
}

/**
 * Strict isolated migration (06.C spec section 6.3): only a `fromVersion` inside [supportedVersions]
 * and strictly below the current schema is migrated, in ONE transaction that runs
 * `LedgerDatabase.Schema.migrate(driver, from, current)` and then stamps `user_version = current`.
 * A failure rolls back the transaction, so the isolated copy keeps its pre-migration content and
 * the caller can delete it. The desktop lenient `from == 0` stamp/guess branches are NOT reachable
 * here.
 *
 * `current` is read from `LedgerDatabase.Schema.version`, so this helper cannot drift from the
 * generated schema.
 */
fun migrateIsolatedSnapshotStrictlyOn(
    driver: SqlDriver,
    fromVersion: Long,
    supportedVersions: Set<Long>,
): StrictMigrationResult {
    val current = LedgerDatabase.Schema.version
    if (fromVersion !in supportedVersions) {
        return StrictMigrationResult.Failed(StrictMigrationFailure.UNSUPPORTED_SOURCE_VERSION)
    }
    if (fromVersion >= current) {
        return StrictMigrationResult.Failed(StrictMigrationFailure.NOT_AN_OLDER_VERSION)
    }
    val database = LedgerDatabase(driver)
    return try {
        database.transaction {
            LedgerDatabase.Schema.migrate(driver, fromVersion, current)
            writeAuthoritativeUserVersionOn(driver, current)
        }
        StrictMigrationResult.Migrated(fromVersion, current)
    } catch (failure: Throwable) {
        StrictMigrationResult.Failed(StrictMigrationFailure.MIGRATE_FAILED)
    }
}

/** Stamps `PRAGMA user_version` on a payload; used only inside the migration transaction. */
internal fun writeAuthoritativeUserVersionOn(
    driver: SqlDriver,
    version: Long,
) {
    // `PRAGMA user_version = N` is a row-less statement; on Android `executeQuery` rejects
    // result-less statements, so it rides `driver.execute` (the row-less surface, same as ANALYZE).
    driver.execute(null, "PRAGMA user_version = $version", 0, null)
}

/**
 * The foreign-key validation result (06.C spec section 6.4). `PRAGMA foreign_key_check` returns one
 * row per violation; the check passes only when it returns no rows.
 */
class ForeignKeyCheckResult(
    val violationCount: Int,
) {
    val ok: Boolean get() = violationCount == 0
}

/** Runs `PRAGMA foreign_key_check` and counts the violations (a new product surface, 06.C). */
fun foreignKeyCheckOn(driver: SqlDriver): ForeignKeyCheckResult {
    var violations = 0
    driver
        .executeQuery(
            null,
            "PRAGMA foreign_key_check",
            { cursor ->
                while (cursor.next().value) violations += 1
                QueryResult.Unit
            },
            0,
            null,
        ).value
    return ForeignKeyCheckResult(violations)
}

/**
 * Reads `PRAGMA integrity_check` rows for the shared [snapshotIntegrityOk] mapping (06.C spec
 * section 6.4). The rows -> OK rule is the 06.B `snapshotIntegrityOk` (at least one row, every row
 * exactly `ok`), reused so the preflight cannot drift from the export's verdict.
 */
fun readIntegrityCheckRowsOn(driver: SqlDriver): List<String?> {
    val rows = mutableListOf<String?>()
    driver
        .executeQuery(
            null,
            "PRAGMA integrity_check",
            { cursor ->
                while (cursor.next().value) rows += cursor.getString(0)
                QueryResult.Unit
            },
            0,
            null,
        ).value
    return rows
}

/**
 * The DISTINCT `ledger_id` values carried by the payload's `ledger_transaction` (the class-3
 * identity check, 06.C spec section 3.8). A missing table throws, which the caller maps to the
 * identity rejection (a payload without the formal surface cannot be this product's ledger).
 */
fun readLedgerIdsOn(driver: SqlDriver): List<String> {
    val ids = mutableListOf<String>()
    driver
        .executeQuery(
            null,
            "SELECT DISTINCT ledger_id FROM ledger_transaction",
            { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { ids += it }
                }
                QueryResult.Unit
            },
            0,
            null,
        ).value
    return ids
}

/**
 * The domain validation result (06.C spec section 6.4). The exact validation set is registered as
 * an implementation-batch item by the 06.C design spec section 10 item 6; this first cut asserts
 * the authoritative facts a formal ledger must carry and that no seed bootstrap ran:
 * [formalTableCount] counts the formal tables present, [ledgerIdentityCount] is the number of
 * DISTINCT `ledger_id` values on `ledger_transaction` (0 for an empty ledger, 1 for a single-ledger
 * ledger), and [postingImbalanceCount] is the number of postings sets whose postings do not sum to
 * zero per currency — the core accounting invariant that must hold without any seed data.
 */
class DomainValidationResult(
    val formalTableCount: Int,
    val ledgerIdentityCount: Int,
    val postingImbalanceCount: Int,
) {
    /** A valid formal ledger has the full formal table surface and every posting set balances. */
    val ok: Boolean get() = formalTableCount >= FORMAL_TABLE_COUNT && postingImbalanceCount == 0

    private companion object {
        /**
         * The formal core table count asserted present (the families of the container-format spec
         * section 3): ledger_transaction, transaction_version, ledger_transaction_current_version,
         * posting_set, posting, formal_transaction_metadata, formal_relation, formal_relation_member.
         */
        const val FORMAL_TABLE_COUNT = 8
    }
}

/** The formal tables whose presence the domain check asserts (container-format spec section 3). */
private val FORMAL_TABLE_NAMES: List<String> =
    listOf(
        "ledger_transaction",
        "transaction_version",
        "ledger_transaction_current_version",
        "posting_set",
        "posting",
        "formal_transaction_metadata",
        "formal_relation",
        "formal_relation_member",
    )

/**
 * Domain validation on the migrated isolated copy (06.C spec section 6.4), WITHOUT the seed
 * bootstrap: it reads the authoritative facts directly. A missing formal table, a second ledger
 * identity or an unbalanced posting set is reported through [DomainValidationResult.ok].
 *
 * The posting-balance query is grouped by `(posting_set_id, ledger_id, currency_code)` and flags a
 * group whose `SUM(amount_minor)` is not zero (the `posting` table's minor-unit amount column and
 * its `currency_code` column, both read from `Ledger.sq`).
 */
fun validateDomainOn(driver: SqlDriver): DomainValidationResult {
    val formalTableCount =
        countRows(
            driver,
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN (${FORMAL_TABLE_NAMES.joinToString(",") { "'$it'" }})",
        ).toInt()
    // A missing formal surface is a typed domain failure, not a crash: the identity and balance
    // probes would otherwise throw "no such table", which is exactly the "缺表一律类型化拒绝"
    // requirement (06.C spec section 6.4). Short-circuit with zeroed probes.
    if (formalTableCount < FORMAL_TABLE_NAMES.size) {
        return DomainValidationResult(formalTableCount, 0, 0)
    }
    val ledgerIdentityCount =
        countRows(
            driver,
            "SELECT count(*) FROM (SELECT DISTINCT ledger_id FROM ledger_transaction)",
        ).toInt()
    val postingImbalanceCount =
        countRows(
            driver,
            "SELECT count(*) FROM (SELECT posting_set_id FROM posting GROUP BY posting_set_id, ledger_id, currency_code HAVING SUM(amount_minor) <> 0)",
        ).toInt()
    return DomainValidationResult(formalTableCount, ledgerIdentityCount, postingImbalanceCount)
}

private fun countRows(
    driver: SqlDriver,
    sql: String,
): Long {
    var count = 0L
    driver
        .executeQuery(
            null,
            sql,
            { cursor ->
                if (cursor.next().value) count = cursor.getLong(0) ?: 0L
                QueryResult.Unit
            },
            0,
            null,
        ).value
    return count
}
