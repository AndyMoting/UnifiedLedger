package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 06.C (D-179; spec `2026-09-25-p7-06-restore-preflight-design.md` sections 6.3 and 6.4): the
 * strict isolated-migration helper, the authoritative `user_version` reader, the FK check and the
 * domain validation. Every branch is exercised on a real SQLite FILE (an in-memory URL would give
 * each driver a fresh database, so it cannot model the multi-open isolated copy).
 *
 * The desktop lenient stamp/guess branches are deliberately absent: a `from == 0` payload is NOT in
 * the supported set here and must be refused, which is the container-format spec section 5.4 rule.
 */
class RestoreIsolatedMigrationTest {
    private fun driver(url: String): JdbcSqliteDriver =
        JdbcSqliteDriver(
            url,
            Properties().apply { setProperty("foreign_keys", "true") },
        )

    /** A connection with FK enforcement OFF, used only to inject a violation for the check test. */
    private fun driverWithoutForeignKeys(url: String): JdbcSqliteDriver = JdbcSqliteDriver(url)

    private fun <T> withTempDatabase(block: (Path) -> T): T {
        val path = Files.createTempFile("restore-isolated-", ".db")
        try {
            return block(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    /**
     * Builds the v1 base surface (the frozen `VERSION_ONE_STATEMENTS` raw SQL, which already carries
     * one balanced formal transaction under `ledger-a`), then runs the real migration chain up to
     * [targetVersion] and stamps it. The rows are v1-shaped (no `canonical_kind` column at v1),
     * which the chain then extends.
     */
    private fun seedAtVersion(
        url: String,
        targetVersion: Long,
    ) {
        java.sql.DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                VERSION_ONE_STATEMENTS.forEach(statement::execute)
            }
        }
        if (targetVersion > 1) {
            driver(url).use { migrationDriver ->
                LedgerDatabase.Schema.migrate(migrationDriver, 1, targetVersion)
                migrationDriver.execute(null, "PRAGMA user_version = $targetVersion", 0)
            }
        }
    }

    @Test
    fun theExposedCurrentSchemaVersionMatchesTheGeneratedSchema() {
        // P2-6: pin the value the composition roots inject into the preflight against the generated
        // schema, so a schema bump cannot leave a stale preflight upper bound.
        assertEquals(LedgerDatabase.Schema.version, currentSupportedSchemaVersion())
        assertEquals(31L, currentSupportedSchemaVersion())
    }

    @Test
    fun readsTheAuthoritativeUserVersionFromThePayload() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            driver(url).use { it.execute(null, "PRAGMA user_version = 7", 0) }
            driver(url).use { isolated ->
                assertEquals(7L, readAuthoritativeUserVersionOn(isolated))
            }
        }
    }

    @Test
    fun aSupportedOlderVersionMigratesAndStampsTheCurrentVersion() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            seedAtVersion(url, 1)
            driver(url).use { isolated ->
                val result = migrateIsolatedSnapshotStrictlyOn(isolated, 1, setOf(1L))
                val migrated = assertIs<StrictMigrationResult.Migrated>(result)
                assertEquals(1L, migrated.fromVersion)
                assertEquals(LedgerDatabase.Schema.version, migrated.targetVersion)
                assertEquals(LedgerDatabase.Schema.version, readAuthoritativeUserVersionOn(isolated))
            }
        }
    }

    @Test
    fun anUnsupportedSourceVersionIsRefusedAndThePayloadIsUntouched() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            seedAtVersion(url, 5)
            driver(url).use { isolated ->
                val result = migrateIsolatedSnapshotStrictlyOn(isolated, 5, setOf(1L, 2L))
                assertEquals(StrictMigrationFailure.UNSUPPORTED_SOURCE_VERSION, assertIs<StrictMigrationResult.Failed>(result).reason)
                // The version stamp is unchanged: nothing ran.
                assertEquals(5L, readAuthoritativeUserVersionOn(isolated))
            }
        }
    }

    @Test
    fun aZeroVersionIsNeverMigratedByThisStrictHelper() {
        // `user_version == 0` is refused by the caller (class 2), and even if it reached here it is
        // not in the supported set: the lenient stamp branch must be unreachable.
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            driver(url).use { isolated ->
                val result = migrateIsolatedSnapshotStrictlyOn(isolated, 0, setOf(1L, 2L))
                assertEquals(StrictMigrationFailure.UNSUPPORTED_SOURCE_VERSION, assertIs<StrictMigrationResult.Failed>(result).reason)
            }
        }
    }

    @Test
    fun aVersionAtOrAboveTheCurrentSchemaIsRefusedAsNotOlder() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            driver(url).use { isolated ->
                val current = LedgerDatabase.Schema.version
                val result = migrateIsolatedSnapshotStrictlyOn(isolated, current, setOf(current))
                assertEquals(StrictMigrationFailure.NOT_AN_OLDER_VERSION, assertIs<StrictMigrationResult.Failed>(result).reason)
            }
        }
    }

    @Test
    fun aFailedMigrationRollsBackAndLeavesTheVersionUnchanged() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            // A supported older version whose payload lacks the v1 surface: `Schema.migrate` fails
            // inside the transaction, so the stamp must not survive.
            driver(url).use { isolated ->
                val result = migrateIsolatedSnapshotStrictlyOn(isolated, 1, setOf(1L))
                assertEquals(StrictMigrationFailure.MIGRATE_FAILED, assertIs<StrictMigrationResult.Failed>(result).reason)
                assertEquals(0L, readAuthoritativeUserVersionOn(isolated))
            }
        }
    }

    @Test
    fun theForeignKeyCheckReportsZeroViolationsForABalancedLedger() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            seedAtVersion(url, LedgerDatabase.Schema.version)
            driver(url).use { isolated ->
                assertTrue(foreignKeyCheckOn(isolated).ok)
            }
        }
    }

    @Test
    fun theForeignKeyCheckReportsAViolationForAnOrphanPosting() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            seedAtVersion(url, LedgerDatabase.Schema.version)
            // FK enforcement is off on this connection, so the orphan is accepted here and the
            // explicit `PRAGMA foreign_key_check` is what detects it.
            driverWithoutForeignKeys(url).use { isolated ->
                isolated.execute(
                    null,
                    "INSERT INTO posting(posting_id, posting_set_id, ledger_id, posting_index, account_id, amount_minor, currency_code, currency_precision) " +
                        "VALUES ('p-orphan','set-missing','ledger-a',0,'cash',1,'CNY',2)",
                    0,
                )
                assertEquals(1, foreignKeyCheckOn(isolated).violationCount)
            }
        }
    }

    @Test
    fun theDomainCheckPassesForAFullBalancedLedger() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            seedAtVersion(url, LedgerDatabase.Schema.version)
            driver(url).use { isolated ->
                val result = validateDomainOn(isolated)
                assertEquals(8, result.formalTableCount)
                assertEquals(1, result.ledgerIdentityCount)
                assertEquals(0, result.postingImbalanceCount)
                assertTrue(result.ok)
            }
        }
    }

    @Test
    fun theDomainCheckFlagsAnUnbalancedPostingSet() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            seedAtVersion(url, LedgerDatabase.Schema.version)
            driver(url).use { isolated ->
                isolated.execute(null, "UPDATE posting SET amount_minor = amount_minor + 1 WHERE posting_id = 'posting-bank-existing'", 0)
                val result = validateDomainOn(isolated)
                assertEquals(1, result.postingImbalanceCount)
                assertTrue(!result.ok)
            }
        }
    }

    @Test
    fun theDomainCheckFlagsASecondLedgerIdentity() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            seedAtVersion(url, LedgerDatabase.Schema.version)
            driver(url).use { isolated ->
                isolated.execute(
                    null,
                    "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-2','other-ledger','EXPENSE')",
                    0,
                )
                assertEquals(2, validateDomainOn(isolated).ledgerIdentityCount)
            }
        }
    }

    @Test
    fun theDomainCheckFailsWhenTheFormalSurfaceIsMissing() {
        withTempDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            driver(url).use { isolated ->
                isolated.execute(null, "CREATE TABLE unrelated (id INTEGER)", 0)
                val result = validateDomainOn(isolated)
                assertEquals(0, result.formalTableCount)
                assertTrue(!result.ok)
            }
        }
    }
}
