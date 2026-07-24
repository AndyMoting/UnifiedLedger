package com.unifiedledger.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.data.db.LedgerDatabase
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerDatabaseMigrationTest {
    @Test
    fun populatedVersionSixPreservesRg04ManualOwnersAtVersionSeven() {
        val path = Files.createTempFile("ledger-data-v6-v7-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) } }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 6)
                driver.execute(null, "INSERT INTO rg04_operation_request VALUES ('ledger-a','rg04-existing','CREDIT_PRINCIPAL_REPAYMENT')", 0)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver -> LedgerDatabase.Schema.migrate(driver, 6, 7) }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countRg04OperationRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg04ImportRequests().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg04ImportCandidates().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionSixToSevenDdlFailureRollsBackEveryNewOwner() {
        val path = Files.createTempFile("ledger-data-v6-v7-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) } }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 6)
                driver.execute(null, "CREATE TABLE rg04_import_candidate(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 6, 7) }
                }
            }
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'rg04_import_%' ORDER BY name").use { rows ->
                        val names = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("rg04_import_candidate"), names)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun populatedVersionFivePreservesRg03AndFormalOwnersAtVersionSix() {
        val path = Files.createTempFile("ledger-data-v5-v6-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) } }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 5)
                driver.execute(null, "INSERT INTO rg03_operation_request VALUES ('ledger-a','rg03-request','MANUAL_ACCOUNT_TRANSFER')", 0)
                driver.execute(null, "INSERT INTO rg03_confirmation VALUES ('ledger-a','rg03-confirmation','rg03-request',NULL,'tx-existing','MANUAL_TRANSFER')", 0)
                driver.execute(null, "INSERT INTO rg03_operation_receipt VALUES ('ledger-a','rg03-request','ACCEPTED','rg03-confirmation',NULL,'tx-existing',NULL,NULL)", 0)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver -> LedgerDatabase.Schema.migrate(driver, 5, 6) }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1L, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(2L, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg03OperationRequests().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg03Confirmations().executeAsOne())
                assertEquals(1L, database.ledgerQueries.countRg03OperationReceipts().executeAsOne())
                assertEquals(0L, database.ledgerQueries.countRg04OperationRequests().executeAsOne())
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test
    fun populatedVersionFourPreservesIncomeOwnersAndCurrentChainAtVersionFive() {
        val path = Files.createTempFile("ledger-data-v4-v5-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) } }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 4)
                driver.execute(null, "INSERT INTO manual_income_request VALUES ('ledger-a','income-existing',500,'CNY',2,'income-category','asset-bank-a','2026-01-16T00:00:00Z','','explicit_manual_save')", 0)
                driver.execute(null, "INSERT INTO confirmed_income_receipt VALUES ('ledger-a','income-existing','income-confirmation','tx-existing')", 0)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 4, 5) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
                assertEquals(1, database.ledgerQueries.countIncomeReceipts().executeAsOne())
                assertEquals("version-existing-v1", database.ledgerQueries.selectCurrentVersionId().executeAsOne())
                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun versionFourToFiveDdlFailureRollsBackEveryRg03Table() {
        val path = Files.createTempFile("ledger-data-v4-v5-rollback-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection -> connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) } }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, 1, 4)
                driver.execute(null, "CREATE TABLE rg03_transfer_candidate(blocker TEXT)", 0)
                assertFailsWith<SQLException> {
                    LedgerDatabase(driver).transaction { LedgerDatabase.Schema.migrate(driver, 4, 5) }
                }
            }
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'rg03_%' ORDER BY name").use { rows ->
                        val names = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("rg03_transfer_candidate"), names)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshSchemaCreatesEveryLedgerDataTableAtVersionSeven() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LedgerDatabase.Schema.create(driver)
            val database = LedgerDatabase(driver)
            SqlDelightConfirmedManualExpenseCommitPort(database, driver)

            assertEquals(7, LedgerDatabase.Schema.version)
            assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
            assertEquals(0, database.ledgerQueries.countRequests().executeAsOne())
            assertEquals(0, database.ledgerQueries.countReceipts().executeAsOne())
            assertEquals(0, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
            assertEquals(0, database.ledgerQueries.countIncomeReceipts().executeAsOne())
            assertEquals(0, database.ledgerQueries.countTransactionNoteUpdateRequests().executeAsOne())
            assertEquals(0, database.ledgerQueries.countTransactionNoteUpdateReceipts().executeAsOne())
            assertEquals(0, database.ledgerQueries.countTransactions().executeAsOne())
            assertEquals(0, database.ledgerQueries.countVersions().executeAsOne())
            assertEquals(0, database.ledgerQueries.countPostingSets().executeAsOne())
            assertEquals(0, database.ledgerQueries.countPostings().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrationFromVersionOnePreservesFormalRowsAndAddsTheCommitBoundary() {
        val path = Files.createTempFile("ledger-data-migration-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    VERSION_ONE_STATEMENTS.forEach(statement::execute)
                }
            }

            val driver = JdbcSqliteDriver(url, migrationSqliteProperties())
            try {
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 5)
            } finally {
                driver.close()
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { reopened ->
                val database = LedgerDatabase(reopened)
                SqlDelightConfirmedManualExpenseCommitPort(database, reopened)

                assertEquals("1", database.ledgerQueries.foreignKeysEnabled().executeAsOne())
                assertEquals(1, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(1, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(1, database.ledgerQueries.countPostingSets().executeAsOne())
                assertEquals(2, database.ledgerQueries.countPostings().executeAsOne())
                assertEquals(0, database.ledgerQueries.countRequests().executeAsOne())
                assertEquals(0, database.ledgerQueries.countReceipts().executeAsOne())

                assertFailsWith<java.sql.SQLException> {
                    reopened.execute(
                        null,
                        """
                            UPDATE ledger_transaction_current_version
                            SET current_version_id = 'version-missing'
                            WHERE transaction_id = 'tx-existing' AND ledger_id = 'ledger-a'
                        """.trimIndent(),
                        0,
                    )
                }
                reopened.execute(
                    null,
                    """
                        INSERT INTO transaction_note_update_request(
                          ledger_id, request_id, transaction_id, note, confirmation_marker
                        ) VALUES ('ledger-a', 'note-request-invalid-baseline', 'tx-existing',
                          'changed', 'explicit_manual_save')
                    """.trimIndent(),
                    0,
                )
                assertFailsWith<java.sql.SQLException> {
                    reopened.execute(
                        null,
                        """
                            INSERT INTO confirmed_transaction_note_update_receipt(
                              ledger_id, request_id, confirmation_id, transaction_id, version_id,
                              expected_current_version_id
                            ) VALUES ('ledger-a', 'note-request-invalid-baseline',
                              'confirmation-note-invalid-baseline', 'tx-existing',
                              'version-existing-v1', 'version-missing')
                        """.trimIndent(),
                        0,
                    )
                }
                reopened.execute(
                    null,
                    """
                        INSERT INTO manual_expense_request(
                          ledger_id, request_id, amount_minor, currency_code, currency_precision,
                          category_id, payment_account_id, occurred_at, note, confirmation_marker
                        ) VALUES ('ledger-other', 'request-other', 1, 'CNY', 2,
                          'category', 'asset', '2026-01-15T00:30:00Z', '', 'explicit_manual_save')
                    """.trimIndent(),
                    0,
                )
                assertFailsWith<java.sql.SQLException> {
                    reopened.execute(
                        null,
                        """
                            INSERT INTO confirmed_expense_receipt(
                              ledger_id, request_id, confirmation_id, transaction_id
                            ) VALUES ('ledger-other', 'request-other', 'confirmation-other', 'tx-existing')
                        """.trimIndent(),
                        0,
                    )
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun freshVersionSevenAndMigratedVersionOneHaveEquivalentSchemaMetadata() {
        val freshPath = Files.createTempFile("ledger-data-fresh-", ".db")
        val migratedPath = Files.createTempFile("ledger-data-migrated-", ".db")
        val freshUrl = "jdbc:sqlite:${freshPath.absolutePathString()}"
        val migratedUrl = "jdbc:sqlite:${migratedPath.absolutePathString()}"
        try {
            JdbcSqliteDriver(freshUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.create(driver)
            }
            DriverManager.getConnection(migratedUrl).use { connection ->
                connection.createStatement().use { statement ->
                    VERSION_ONE_STATEMENTS.forEach(statement::execute)
                }
            }
            JdbcSqliteDriver(migratedUrl, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 7)
            }

            assertEquals(schemaMetadata(freshUrl), schemaMetadata(migratedUrl))
        } finally {
            Files.deleteIfExists(freshPath)
            Files.deleteIfExists(migratedPath)
        }
    }

    @Test
    fun migrationFromVersionThreePreservesExpenseAndNoteCommitOwnersWithCurrentChain() {
        val path = Files.createTempFile("ledger-data-v3-v4-", ".db")
        val url = "jdbc:sqlite:${path.absolutePathString()}"
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 3)
                driver.execute(null, """
                    INSERT INTO manual_expense_request(
                      ledger_id, request_id, amount_minor, currency_code, currency_precision,
                      category_id, payment_account_id, occurred_at, note, confirmation_marker
                    ) VALUES ('ledger-a', 'request-expense-existing', 3580, 'CNY', 2,
                      'expense-category-breakfast', 'asset-bank-a', '2026-01-15T00:30:00Z', '',
                      'explicit_manual_save')
                """.trimIndent(), 0)
                driver.execute(null, """
                    INSERT INTO confirmed_expense_receipt(
                      ledger_id, request_id, confirmation_id, transaction_id
                    ) VALUES ('ledger-a', 'request-expense-existing',
                      'confirmation-expense-existing', 'tx-existing')
                """.trimIndent(), 0)
                driver.execute(null, """
                    INSERT INTO transaction_version(
                      version_id, transaction_id, ledger_id, version_number, posting_set_id,
                      occurred_at, statistics_at, effective_at, note
                    ) VALUES ('version-existing-v2', 'tx-existing', 'ledger-a', 2,
                      'posting-set-existing', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z',
                      '2026-01-15T00:30:00Z', 'replacement note')
                """.trimIndent(), 0)
                driver.execute(null, """
                    UPDATE ledger_transaction_current_version
                    SET current_version_id = 'version-existing-v2'
                    WHERE transaction_id = 'tx-existing' AND ledger_id = 'ledger-a'
                """.trimIndent(), 0)
                driver.execute(null, """
                    INSERT INTO transaction_note_update_request(
                      ledger_id, request_id, transaction_id, note, confirmation_marker
                    ) VALUES ('ledger-a', 'request-note-existing', 'tx-existing',
                      'replacement note', 'explicit_manual_save')
                """.trimIndent(), 0)
                driver.execute(null, """
                    INSERT INTO confirmed_transaction_note_update_receipt(
                      ledger_id, request_id, confirmation_id, transaction_id, version_id,
                      expected_current_version_id
                    ) VALUES ('ledger-a', 'request-note-existing', 'confirmation-note-existing',
                      'tx-existing', 'version-existing-v2', 'version-existing-v1')
                """.trimIndent(), 0)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                LedgerDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 5)
            }
            JdbcSqliteDriver(url, migrationSqliteProperties()).use { driver ->
                val database = LedgerDatabase(driver)
                assertEquals(1, database.ledgerQueries.countTransactions().executeAsOne())
                assertEquals(2, database.ledgerQueries.countVersions().executeAsOne())
                assertEquals(1, database.ledgerQueries.countRequests().executeAsOne())
                assertEquals(1, database.ledgerQueries.countReceipts().executeAsOne())
                assertEquals(1, database.ledgerQueries.countTransactionNoteUpdateRequests().executeAsOne())
                assertEquals(1, database.ledgerQueries.countTransactionNoteUpdateReceipts().executeAsOne())
                assertEquals("version-existing-v2", database.ledgerQueries.selectCurrentVersionId().executeAsOne())
                assertEquals("replacement note", database.ledgerQueries.selectCurrentNote().executeAsOne().note)
                assertEquals(0, database.ledgerQueries.countManualIncomeRequests().executeAsOne())
                assertEquals(0, database.ledgerQueries.countIncomeReceipts().executeAsOne())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

private data class SchemaMetadata(
    val objects: List<String>,
    val foreignKeys: List<String>,
    val indexes: List<String>,
)

private fun schemaMetadata(url: String): SchemaMetadata =
    DriverManager.getConnection(url).use { connection ->
        val objects = buildList {
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                        SELECT type, name, tbl_name, sql
                        FROM sqlite_master
                        WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL
                        ORDER BY type, name
                    """.trimIndent(),
                ).use { rows ->
                    while (rows.next()) {
                        add(
                            listOf(
                                rows.getString("type"),
                                rows.getString("name"),
                                rows.getString("tbl_name"),
                                normalizeSql(rows.getString("sql")),
                            ).joinToString("|"),
                        )
                    }
                }
            }
        }
        val tableNames = objects.asSequence()
            .filter { it.startsWith("table|") }
            .map { it.substringAfter('|').substringBefore('|') }
            .toList()
        val foreignKeys = buildList {
            tableNames.forEach { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_list('$table')").use { rows ->
                        while (rows.next()) {
                            add(
                                listOf(
                                    table,
                                    rows.getInt("id"),
                                    rows.getInt("seq"),
                                    rows.getString("table"),
                                    rows.getString("from"),
                                    rows.getString("to"),
                                    rows.getString("on_update"),
                                    rows.getString("on_delete"),
                                    rows.getString("match"),
                                ).joinToString("|"),
                            )
                        }
                    }
                }
            }
        }.sorted()
        val indexes = buildList {
            tableNames.forEach { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA index_list('$table')").use { rows ->
                        while (rows.next()) {
                            add(
                                listOf(
                                    table,
                                    rows.getString("name"),
                                    rows.getInt("unique"),
                                    rows.getString("origin"),
                                    rows.getInt("partial"),
                                ).joinToString("|"),
                            )
                        }
                    }
                }
            }
        }.sorted()
        SchemaMetadata(objects, foreignKeys, indexes)
    }

private fun normalizeSql(sql: String): String =
    sql.replace(Regex("\\s+"), " ").trim().replace("( ", "(").replace(" )", ")")

private fun migrationSqliteProperties(): Properties = Properties().apply {
    setProperty("foreign_keys", "true")
    setProperty("busy_timeout", "5000")
}

private val VERSION_ONE_STATEMENTS = listOf(
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
    "INSERT INTO posting_set VALUES ('posting-set-existing', 'ledger-a')",
    "INSERT INTO ledger_transaction VALUES ('tx-existing', 'ledger-a', 'EXPENSE')",
    """
        INSERT INTO transaction_version VALUES (
          'version-existing-v1', 'tx-existing', 'ledger-a', 1, 'posting-set-existing',
          '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', '2026-01-15T00:30:00Z', NULL
        )
    """.trimIndent(),
    "INSERT INTO ledger_transaction_current_version VALUES ('tx-existing', 'ledger-a', 'version-existing-v1')",
    """
        INSERT INTO posting VALUES (
          'posting-expense-existing', 'posting-set-existing', 'ledger-a', 0,
          'expense-account-breakfast', 3580, 'CNY', 2
        )
    """.trimIndent(),
    """
        INSERT INTO posting VALUES (
          'posting-bank-existing', 'posting-set-existing', 'ledger-a', 1,
          'asset-bank-a', -3580, 'CNY', 2
        )
    """.trimIndent(),
)
