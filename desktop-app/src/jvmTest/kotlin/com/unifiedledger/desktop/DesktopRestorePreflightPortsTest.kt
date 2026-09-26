package com.unifiedledger.desktop

import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.ui.RestoreMigrationOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7-06 06.C (D-179; spec section 8.2): the DESKTOP isolated-database adapter's call sites. The
 * shared preflight is tested against fakes in app-ui; this test pins that the real adapter wires the
 * commonMain strict helpers correctly on a real SQLite file, so the adapter cannot silently drift
 * (e.g. read the wrong version, or fail to run the strict migration).
 */
class DesktopRestorePreflightPortsTest {
    private val port = DesktopRestoreIsolatedDatabasePort()

    private fun <T> withDatabase(block: (Path) -> T): T {
        val path = Files.createTempFile("desktop-restore-port-", ".db")
        try {
            return block(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun theAdapterReadsTheAuthoritativeUserVersion() {
        withDatabase { path ->
            java.sql.DriverManager.getConnection("jdbc:sqlite:${path.absolutePathString()}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = 9") }
            }
            assertEquals(9L, port.readAuthoritativeUserVersion(path.absolutePathString()))
        }
    }

    @Test
    fun theAdapterRefusesAnUnsupportedSourceVersionWithoutTouchingTheFile() {
        withDatabase { path ->
            java.sql.DriverManager.getConnection("jdbc:sqlite:${path.absolutePathString()}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = 9") }
            }
            val outcome = port.migrateStrictly(path.absolutePathString(), 9L, setOf(1L, 2L))
            assertEquals(RestoreMigrationOutcome.Failed, outcome)
            // The strict helper did not stamp a new version.
            assertEquals(9L, port.readAuthoritativeUserVersion(path.absolutePathString()))
        }
    }

    @Test
    fun theAdapterMigratesASupportedOlderVersionToTheCurrentSchema() {
        withDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            // Build the v1 base surface with the frozen seed statements, then let the adapter migrate.
            java.sql.DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    VERSION_ONE_STATEMENTS_DESKTOP.forEach(statement::execute)
                }
            }
            val outcome = port.migrateStrictly(path.absolutePathString(), 1L, setOf(1L))
            val migrated = assertIs<RestoreMigrationOutcome.Migrated>(outcome)
            assertEquals(1L, migrated.fromVersion)
            assertEquals(LedgerDatabase.Schema.version, migrated.toVersion)
            assertEquals(LedgerDatabase.Schema.version, port.readAuthoritativeUserVersion(path.absolutePathString()))
        }
    }

    @Test
    fun theAdapterReadsThePayloadLedgerIdentities() {
        withDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            java.sql.DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    VERSION_ONE_STATEMENTS_DESKTOP.forEach(statement::execute)
                }
            }
            assertEquals(listOf("ledger-a"), port.readObservedLedgerIdentities(path.absolutePathString()))
        }
    }

    @Test
    fun theAdapterValidatesAFullBalancedLedger() {
        withDatabase { path ->
            val url = "jdbc:sqlite:${path.absolutePathString()}"
            java.sql.DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    VERSION_ONE_STATEMENTS_DESKTOP.forEach(statement::execute)
                }
            }
            // The formal surface only exists after the chain runs; migrate first.
            assertIs<RestoreMigrationOutcome.Migrated>(port.migrateStrictly(path.absolutePathString(), 1L, setOf(1L)))
            val facts = port.validate(path.absolutePathString())
            assertTrue(facts.integrityOk)
            assertTrue(facts.foreignKeyOk)
            assertTrue(facts.domainOk)
            assertEquals(8, facts.formalTableCount)
            assertEquals(1, facts.ledgerIdentityCount)
            assertEquals(0, facts.postingImbalanceCount)
        }
    }
}

/**
 * The v1 base surface for these port tests: the formal core tables plus one balanced transaction,
 * with the same FOREIGN KEY constraints as the frozen `VERSION_ONE_STATEMENTS`
 * (`ledger-data` jvmTest `LedgerDatabaseMigrationTest`) that the migration chain expects. It is kept
 * local because that fixture is not on the desktop test classpath; the constraints are reproduced so
 * the FK-enforced migration path exercises the same schema shape (P3-7).
 */
private val VERSION_ONE_STATEMENTS_DESKTOP: List<String> =
    listOf(
        """
        CREATE TABLE ledger_transaction (
          transaction_id TEXT NOT NULL PRIMARY KEY,
          ledger_id TEXT NOT NULL,
          kind TEXT NOT NULL CHECK (kind IN ('OPENING_BALANCE', 'EXPENSE')),
          UNIQUE (transaction_id, ledger_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE posting_set (
          posting_set_id TEXT NOT NULL PRIMARY KEY,
          ledger_id TEXT NOT NULL,
          UNIQUE (posting_set_id, ledger_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE transaction_version (
          version_id TEXT NOT NULL PRIMARY KEY,
          transaction_id TEXT NOT NULL,
          ledger_id TEXT NOT NULL,
          version_number INTEGER NOT NULL CHECK (version_number > 0),
          posting_set_id TEXT NOT NULL,
          occurred_at TEXT NOT NULL,
          statistics_at TEXT NOT NULL,
          effective_at TEXT NOT NULL,
          note TEXT,
          UNIQUE (transaction_id, version_number),
          UNIQUE (transaction_id, version_id, ledger_id),
          FOREIGN KEY (transaction_id, ledger_id)
            REFERENCES ledger_transaction(transaction_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED,
          FOREIGN KEY (posting_set_id, ledger_id)
            REFERENCES posting_set(posting_set_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED
        )
        """.trimIndent(),
        """
        CREATE TABLE posting (
          posting_id TEXT NOT NULL PRIMARY KEY,
          posting_set_id TEXT NOT NULL,
          ledger_id TEXT NOT NULL,
          posting_index INTEGER NOT NULL CHECK (posting_index >= 0),
          account_id TEXT NOT NULL,
          amount_minor INTEGER NOT NULL,
          currency_code TEXT NOT NULL,
          currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
          UNIQUE (posting_set_id, posting_index),
          UNIQUE (posting_id, ledger_id),
          FOREIGN KEY (posting_set_id, ledger_id)
            REFERENCES posting_set(posting_set_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED
        )
        """.trimIndent(),
        """
        CREATE TABLE ledger_transaction_current_version (
          transaction_id TEXT NOT NULL,
          ledger_id TEXT NOT NULL,
          current_version_id TEXT NOT NULL,
          PRIMARY KEY (transaction_id, ledger_id),
          FOREIGN KEY (transaction_id, ledger_id)
            REFERENCES ledger_transaction(transaction_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED,
          FOREIGN KEY (transaction_id, current_version_id, ledger_id)
            REFERENCES transaction_version(transaction_id, version_id, ledger_id)
            DEFERRABLE INITIALLY DEFERRED
        )
        """.trimIndent(),
        "INSERT INTO posting_set VALUES ('set-1', 'ledger-a')",
        "INSERT INTO ledger_transaction(transaction_id, ledger_id, kind) VALUES ('tx-1', 'ledger-a', 'EXPENSE')",
        """
        INSERT INTO transaction_version VALUES (
          'v-1', 'tx-1', 'ledger-a', 1, 'set-1',
          '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', NULL
        )
        """.trimIndent(),
        "INSERT INTO ledger_transaction_current_version VALUES ('tx-1', 'ledger-a', 'v-1')",
        "INSERT INTO posting VALUES ('p-1', 'set-1', 'ledger-a', 0, 'expense', 3580, 'CNY', 2)",
        "INSERT INTO posting VALUES ('p-2', 'set-1', 'ledger-a', 1, 'asset', -3580, 'CNY', 2)",
    )
