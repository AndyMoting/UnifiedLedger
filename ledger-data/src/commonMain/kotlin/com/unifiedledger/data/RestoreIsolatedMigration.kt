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

/**
 * The schema version this build supports, read from the generated schema. Composition roots pass
 * this into `RestorePreflightRequest.currentSchemaVersion` (P2-6): the shared preflight cannot read
 * `LedgerDatabase.Schema.version` itself (app-ui depends only on ledger-application), so exposing it
 * here prevents a hard-coded duplicate that would drift after a schema bump.
 */
fun currentSupportedSchemaVersion(): Long = LedgerDatabase.Schema.version

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

/**
 * The typed reason a strict isolated migration failed.
 *
 * There is deliberately no separate STAMP_FAILED: the version stamp runs INSIDE the same
 * transaction as `Schema.migrate`, so a stamp failure is indistinguishable in effect (one rolled-back
 * transaction) and is reported as [MIGRATE_FAILED] rather than as a branch this helper cannot
 * actually distinguish.
 */
enum class StrictMigrationFailure {
    /** The payload version is not in the caller's supported set (should be refused earlier). */
    UNSUPPORTED_SOURCE_VERSION,

    /** The payload version is at or above the current schema (nothing to migrate). */
    NOT_AN_OLDER_VERSION,

    /** `Schema.migrate` (or the in-transaction version stamp) threw; the transaction rolled back. */
    MIGRATE_FAILED,
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
 * Every DISTINCT `ledger_id` observed across the payload's ENTIRE authoritative owner set: every
 * user table in `sqlite_master` that carries a `ledger_id` column (the formal core, catalog, import
 * and `rg02_`-`rg12_` families alike), not just `ledger_transaction`.
 *
 * WHY THE WHOLE SET (P1-1 fix): checking only `ledger_transaction` lets a payload whose formal rows
 * carry the target id but whose catalog/import/rg owners carry a different id pass the class-3
 * check (container-format spec section 5.4 requires "no extra ledger"). The restore preflight uses
 * this function, so an identity observed anywhere in the payload is an identity observed.
 *
 * A payload with NO user table carrying `ledger_id` (or no rows) returns an empty list; the caller
 * must treat "nothing observed" as a class-3 rejection, never as an implicit target match.
 *
 * N2 fix (fail-closed): a table that HAS `ledger_id` but whose probe throws is NOT silently skipped —
 * that could hide a foreign id. "Column absent" and "probe failed" are distinguished: the
 * `PRAGMA table_info` probe ([tableHasColumn]) and the `SELECT DISTINCT` sweep are neither wrapped,
 * so a probe failure propagates and the caller (the preflight) reports it as the typed
 * `P706_SOURCE_READ_FAILED` read failure — the payload is never accepted with a partially observed
 * identity set. Table names come from our own generated schema (not user input) but are still quoted.
 */
fun readObservedLedgerIdsOn(driver: SqlDriver): List<String> {
    val tables = mutableListOf<String>()
    driver
        .executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { tables += it }
                }
                QueryResult.Unit
            },
            0,
            null,
        ).value
    val observed = linkedSetOf<String>()
    for (table in tables) {
        if (!tableHasColumn(driver, table, "ledger_id")) continue
        driver
            .executeQuery(
                null,
                "SELECT DISTINCT ledger_id FROM ${quoteIdentifier(table)}",
                { cursor ->
                    while (cursor.next().value) {
                        cursor.getString(0)?.let { observed += it }
                    }
                    QueryResult.Unit
                },
                0,
                null,
            ).value
    }
    return observed.toList()
}

/**
 * Whether [table] exposes a [column]. N2 fix: a probe that throws propagates (fail-closed) instead of
 * being reported as "column absent", so a carrying table that errors cannot be silently dropped from
 * the identity sweep.
 */
private fun tableHasColumn(
    driver: SqlDriver,
    table: String,
    column: String,
): Boolean {
    var found = false
    driver
        .executeQuery(
            null,
            "PRAGMA table_info(${quoteIdentifier(table)})",
            { cursor ->
                while (cursor.next().value) {
                    // PRAGMA table_info columns: cid(0), name(1), type(2), notnull(3), dflt(4), pk(5).
                    if (cursor.getString(1) == column) found = true
                }
                QueryResult.Unit
            },
            0,
            null,
        ).value
    return found
}

/** Quotes a schema identifier for interpolation (names come from our own schema). */
private fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

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
 * The posting-balance query is grouped by `(posting_set_id, ledger_id, currency_code,
 * currency_precision)`, matching the product's own balance invariant, which groups by
 * `(currency_code, currency_precision)` (`Ledger.sq`: `GROUP BY posting.currency_code,
 * posting.currency_precision`) and flags a group whose `SUM(amount_minor)` is not zero. Grouping by
 * currency alone would merge distinct precisions of the same currency and could report a false
 * imbalance (or mask a real one).
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
            "SELECT count(*) FROM (SELECT posting_set_id FROM posting GROUP BY posting_set_id, ledger_id, currency_code, currency_precision HAVING SUM(amount_minor) <> 0)",
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
